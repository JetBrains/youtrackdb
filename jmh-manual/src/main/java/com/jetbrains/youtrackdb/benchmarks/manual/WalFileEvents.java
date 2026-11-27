package com.jetbrains.youtrackdb.benchmarks.manual;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

final class WalFileEvents {

  private static final String WRITE = "jdk.FileWrite";
  private static final String FORCE = "jdk.FileForce";
  private static final String LOSS = "jdk.DataLoss";
  private static final String MARKER = "benchmark.WalSample";
  private static final int JFR_MAGIC = 0x464c5200; // FLR followed by a zero byte.
  private static final int JFR_VERSION = 0x00020001;
  private static final long JFR_HEADER_BYTES = 68;
  private final Path dump;
  private final boolean enabled;
  private final Predicate<Path> isSegment;
  private Recording recording;

  WalFileEvents(Path root, boolean enabled, Predicate<Path> isSegment) {
    this.dump = root.resolve("iteration.jfr");
    this.enabled = enabled;
    this.isSegment = isSegment;
  }

  void start() {
    if (!enabled || !FlightRecorder.isAvailable()) {
      return;
    }
    try {
      var types = FlightRecorder.getFlightRecorder().getEventTypes().stream()
          .map(EventType::getName).toList();
      if (!types.containsAll(List.of(WRITE, FORCE, LOSS))) {
        return;
      }
      recording = new Recording();
      for (String event : List.of(WRITE, FORCE, LOSS)) {
        recording.enable(event).withoutThreshold().withoutStackTrace();
      }
      recording.enable(Sample.class).withoutThreshold().withoutStackTrace();
      recording.start();
    } catch (RuntimeException failure) {
      close();
    }
  }

  void mark(int sequence) {
    if (recording != null) {
      var marker = new Sample();
      marker.sequence = sequence;
      marker.commit();
    }
  }

  String report(int samples) {
    try {
      if (recording != null && samples >= 2) {
        recording.stop();
        recording.dump(dump);
        return read(dump, samples, isSegment);
      }
    } catch (IOException | RuntimeException failure) {
      return unavailable("unusable");
    } finally {
      close();
    }
    return unavailable(enabled ? "unusable" : "disabled");
  }

  static String read(Path dump, int samples, Predicate<Path> isSegment) {
    try {
      // JDK 21 copies finished chunks without merging them. Require one physical chunk
      // so all markers and file events use the same timestamp calibration.
      long chunks = countChunks(dump);
      if (chunks != 1) {
        return unavailable(chunks == 0 ? "unusable" : "rotated");
      }
      return readEvents(dump, samples, isSegment);
    } catch (IOException | RuntimeException failure) {
      return unavailable("unusable");
    }
  }

  private static <T> T parse(ParserCall<T> call) throws IOException {
    try {
      return call.read();
    } catch (InternalError failure) {
      // JFR throws InternalError for damaged dumps with an empty constant pool.
      throw new IOException("Cannot parse JFR dump", failure);
    }
  }

  @FunctionalInterface
  private interface ParserCall<T> {
    T read() throws IOException;
  }

  private static RecordedEvent nextEvent(RecordingFile events) throws IOException {
    return parse(() -> events.hasMoreEvents() ? events.readEvent() : null);
  }

  private static long countChunks(Path dump) throws IOException {
    long remaining = Files.size(dump);
    long chunks = 0;
    try (var header = new DataInputStream(Files.newInputStream(dump))) {
      // Check framing to the end of the dump. Extra bytes alone are not evidence of rotation.
      while (remaining > 0) {
        if (remaining < JFR_HEADER_BYTES || header.readInt() != JFR_MAGIC
            || header.readInt() != JFR_VERSION) {
          return 0;
        }
        long chunkBytes = header.readLong();
        long poolOffset = header.readLong();
        long metadataOffset = header.readLong();
        if (chunkBytes <= JFR_HEADER_BYTES || chunkBytes > remaining
            || poolOffset < JFR_HEADER_BYTES || poolOffset >= chunkBytes
            || metadataOffset < JFR_HEADER_BYTES || metadataOffset >= chunkBytes) {
          return 0;
        }
        // Skip start time, duration and start ticks. A stopped chunk has a zero file state.
        header.skipNBytes(3L * Long.BYTES);
        if (header.readLong() <= 0 || header.readUnsignedByte() != 0) {
          return 0;
        }
        // Skip the last three header bytes and the chunk payload.
        header.skipNBytes(chunkBytes - JFR_HEADER_BYTES + 3);
        remaining -= chunkBytes;
        chunks++;
      }
    }
    return chunks;
  }

  private static String readEvents(Path dump, int samples, Predicate<Path> isSegment)
      throws IOException {
    // Resolve the window on JFR's own timeline and require every accepted sample marker.
    // Two streaming passes avoid retaining file events that precede their boundary markers.
    var markers = new Instant[samples];
    try (var events = parse(() -> new RecordingFile(dump))) {
      for (var event = nextEvent(events); event != null; event = nextEvent(events)) {
        String name = event.getEventType().getName();
        if (name.equals(LOSS)) {
          return unavailable("loss");
        }
        if (name.equals(MARKER)) {
          int sequence = event.getInt("sequence");
          // A stop-crossing sample can leave a marker beyond the last accepted sequence.
          if (sequence >= 0 && sequence < samples) {
            if (markers[sequence] != null) {
              return unavailable("unusable");
            }
            markers[sequence] = event.getStartTime();
          }
        }
      }
    }
    Instant previous = null;
    for (Instant marker : markers) {
      if (marker == null || (previous != null && !marker.isAfter(previous))) {
        return unavailable("unusable");
      }
      previous = marker;
    }
    Instant start = markers[0];
    Instant end = markers[samples - 1];
    long writes = 0;
    long bytes = 0;
    long forces = 0;
    long nanos = 0;
    try (var events = parse(() -> new RecordingFile(dump))) {
      for (var event = nextEvent(events); event != null; event = nextEvent(events)) {
        String name = event.getEventType().getName();
        if ((!name.equals(WRITE) && !name.equals(FORCE)) || event.getStartTime().isBefore(start)
            || !event.getStartTime().isBefore(end) || event.getEndTime().isAfter(end)) {
          continue;
        }
        String path = event.getString("path");
        if (path != null && isSegment.test(Path.of(path).toAbsolutePath().normalize())) {
          if (name.equals(WRITE)) {
            writes++;
            bytes += event.getLong("bytesWritten");
          } else {
            forces++;
            nanos += event.getDuration().toNanos();
          }
        }
      }
    }
    return format("ok", writes, bytes, forces, nanos);
  }

  private static String unavailable(String status) {
    return format(status, -1, -1, -1, -1);
  }

  private static String format(String status, long writes, long bytes, long forces, long nanos) {
    return " fileEvents=" + status + " walFileWriteCount=" + IterationMetrics.value(writes)
        + " walFileWriteBytes=" + IterationMetrics.value(bytes)
        + " walFileForceCount=" + IterationMetrics.value(forces)
        + " walFileForceNanos=" + IterationMetrics.value(nanos);
  }

  void close() {
    if (recording != null) {
      recording.close();
      recording = null;
    }
    try {
      Files.deleteIfExists(dump);
    } catch (IOException failure) {
      // Trial teardown also removes the dump with the rest of the temporary directory.
    }
  }

  @Name(MARKER)
  static class Sample extends Event {

    int sequence;
  }
}
