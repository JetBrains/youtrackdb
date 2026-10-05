package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.CRC32C;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;
import javax.annotation.Nullable;

/**
 * Streaming side-file codec. Successful decoding proves content integrity, not WAL coverage.
 * The caller holds save order from capture through publication and checks the capture failure fence.
 * Streams stay open so the publisher can force its channel after encoding.
 */
final class ChangedPageTrackerFile {

  private static final int MAGIC = 0x59544350;
  private static final int VERSION = 1;
  private static final int BUFFER_BYTES = 4096;

  private ChangedPageTrackerFile() {
  }

  /** Big-endian fixed-width records avoid counts that can race with concurrent bitmap growth. */
  static void write(OutputStream stream, ChangedPageTracker.SaveState state,
      LogSequenceNumber coverage) throws IOException {
    if (coverage.getSegment() < 0 || coverage.getPosition() < 0) {
      throw new IllegalArgumentException("Invalid coverage LSN");
    }
    var checksum = new CRC32C();
    var out = new DataOutputStream(new CheckedOutputStream(
        new BufferedOutputStream(stream, BUFFER_BYTES), checksum));
    out.writeInt(MAGIC);
    out.writeInt(VERSION);
    out.writeByte((state.trusted() ? 1 : 0)
        | (state.lastCompletedIdentifier() != null ? 2 : 0) | (state.sealed() != null ? 4 : 0));
    coverage.toStream(out);
    writeId(out, state.trackerIdentifier());
    if (state.lastCompletedIdentifier() != null) {
      writeId(out, state.lastCompletedIdentifier());
    }
    if (state.sealed() != null) {
      writeId(out, state.sealed().identifier());
    }
    writeGeneration(out, state.active());
    if (state.sealed() != null) {
      writeGeneration(out, state.sealed().pages());
    }
    out.writeInt((int) checksum.getValue());
    out.flush();
  }

  private static void writeId(DataOutputStream out, UUID id) throws IOException {
    out.writeLong(id.getMostSignificantBits());
    out.writeLong(id.getLeastSignificantBits());
  }

  private static void writeGeneration(DataOutputStream out, ChangedPageTracker.Generation pages)
      throws IOException {
    try {
      pages.forEachFile((file, bitmap) -> bitmap.forEachWord((word, bits) -> {
        try {
          out.writeInt(file);
          out.writeLong(word);
          out.writeLong(bits);
        } catch (IOException failure) {
          throw new UncheckedIOException(failure);
        }
      }));
    } catch (UncheckedIOException failure) {
      throw failure.getCause();
    }
    out.writeInt(0);
  }

  /** Missing content loses authority. Other filesystem errors remain distinct checked errors. */
  static Loaded load(Path path) throws IOException {
    try (var stream = Files.newInputStream(path)) {
      return read(stream);
    } catch (NoSuchFileException missing) {
      return untrusted("missing side file");
    }
  }

  /** Partial restoration is private until the checksum and exact end of stream are validated. */
  static Loaded read(InputStream stream) throws IOException {
    var checksum = new CRC32C();
    var checked = new CheckedInputStream(new BufferedInputStream(stream, BUFFER_BYTES), checksum);
    var in = new DataInputStream(new RecordInputStream(checked));
    try {
      require(in.readInt() == MAGIC, "magic");
      require(in.readInt() == VERSION, "version");
      int flags = in.readUnsignedByte();
      require((flags & ~7) == 0, "flags");
      var coverage = new LogSequenceNumber(in);
      require(coverage.getSegment() >= 0 && coverage.getPosition() >= 0, "coverage LSN");
      var trackerId = readId(in);
      var completed = (flags & 2) == 0 ? null : readId(in);
      var sealed = (flags & 4) == 0 ? null : readId(in);
      var builder = ChangedPageTracker.restoration(trackerId, completed, (flags & 1) != 0, sealed);
      readGeneration(in, builder::activeWord);
      if (sealed != null) {
        readGeneration(in, builder::sealedWord);
      }
      int expected = (int) checksum.getValue();
      require(in.readInt() == expected, "checksum");
      require(checked.read() == -1, "trailing bytes");
      return new Loaded(builder.build(), coverage);
    } catch (InvalidContent damaged) {
      return untrusted(damaged.getMessage());
    }
  }

  private static UUID readId(DataInputStream in) throws IOException {
    return new UUID(in.readLong(), in.readLong());
  }

  private static void readGeneration(DataInputStream in, LoadedWord consumer) throws IOException {
    int previousFile = 0;
    long previousWord = -1;
    while (true) {
      int file = in.readInt();
      if (file == 0) {
        return;
      }
      long word = in.readLong();
      long bits = in.readLong();
      require(file > 0 && file >= previousFile, "file index or ordering");
      require(word >= 0 && word <= (Long.MAX_VALUE >>> 6), "word index");
      require(file != previousFile || word > previousWord, "duplicate or unordered word");
      require(bits != 0, "empty word");
      consumer.accept(file, word, bits);
      previousFile = file;
      previousWord = word;
    }
  }

  private static void require(boolean valid, String field) throws InvalidContent {
    if (!valid) {
      throw new InvalidContent("Invalid changed-page tracker " + field);
    }
  }

  private static Loaded untrusted(String reason) {
    LogManager.instance().warn(ChangedPageTrackerFile.class,
        "Changed-page tracker side file is untrusted: " + reason);
    return new Loaded(new ChangedPageTracker(), null);
  }

  record Loaded(ChangedPageTracker tracker, @Nullable LogSequenceNumber coverageLsn) {
  }

  @FunctionalInterface
  private interface LoadedWord {
    void accept(int file, long word, long bits);
  }

  // Required record reads treat a returned -1 as malformed content. Exceptions from the
  // supplied stream propagate unchanged, including EOFException. The final end check bypasses
  // this wrapper because end of input is expected there.
  private static final class RecordInputStream extends FilterInputStream {
    private RecordInputStream(InputStream stream) {
      super(stream);
    }

    @Override
    public int read() throws IOException {
      int value = in.read();
      if (value == -1) {
        throw new InvalidContent("truncated fixed-width record");
      }
      return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      int count = in.read(bytes, offset, length);
      if (count == -1) {
        throw new InvalidContent("truncated fixed-width record");
      }
      return count;
    }
  }

  private static final class InvalidContent extends IOException {
    private InvalidContent(String message) {
      super(message);
    }
  }
}
