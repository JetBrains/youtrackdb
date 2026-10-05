package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32C;
import org.junit.Test;

public class ChangedPageTrackerFileTest {

  private static final LogSequenceNumber COVERAGE = new LogSequenceNumber(17, 32);

  // Both generations, extreme indices and continuity survive decoding and the startup merge.
  @Test
  public void roundTripPreservesIdentifiersCoverageAndBothGenerations() throws Exception {
    var builder = ChangedPageTracker.restoration(UUID.randomUUID(), UUID.randomUUID(), true,
        UUID.randomUUID());
    builder.activeWord(1, 0, 3);
    builder.activeWord(Integer.MAX_VALUE, Long.MAX_VALUE >>> 6, Long.MIN_VALUE);
    builder.sealedWord(1, 512, 4);
    var source = capture(builder.build());
    var loaded = read(encode(source));
    assertTrue(loaded.tracker().isTrusted());
    assertEquals(COVERAGE, loaded.coverageLsn());
    var state = capture(loaded.tracker());
    assertEquals(source.trackerIdentifier(), state.trackerIdentifier());
    assertEquals(source.lastCompletedIdentifier(), state.lastCompletedIdentifier());
    assertEquals(source.sealed().identifier(), state.sealed().identifier());
    assertPages(state.active(), 1, 0, 1);
    assertPages(state.active(), Integer.MAX_VALUE, Long.MAX_VALUE);
    assertPages(state.sealed().pages(), 1, 32770);
    loaded.tracker().mergeLoadedSealed();
    state = capture(loaded.tracker());
    assertNull(state.sealed());
    assertPages(state.active(), 1, 0, 1, 32770);
    assertEquals(source.lastCompletedIdentifier(), state.lastCompletedIdentifier());
    var empty = read(encode(capture(new ChangedPageTracker())));
    assertFalse(empty.tracker().isTrusted());
    assertNotNull(empty.coverageLsn());
  }

  // Every shorter byte length and every one-bit byte mutation loses authority. No bad content
  // escapes as an exception. A missing path has the same fail-closed result.
  @Test
  public void missingTruncatedAndBitFlippedInputsFailClosed() throws Exception {
    var builder = ChangedPageTracker.restoration(UUID.randomUUID(), UUID.randomUUID(), true,
        UUID.randomUUID());
    builder.activeWord(1, 0, 1);
    builder.sealedWord(1, 1, 2);
    var bytes = encode(capture(builder.build()));
    for (int length = 0; length < bytes.length; length++) {
      assertUntrusted(Arrays.copyOf(bytes, length));
    }
    for (int index = 0; index < bytes.length; index++) {
      var damaged = bytes.clone();
      damaged[index] ^= 1;
      assertUntrusted(damaged);
    }
    var directory = Files.createTempDirectory("tracker-codec");
    try {
      var missing = ChangedPageTrackerFile.load(directory.resolve("absent"));
      assertFalse(missing.tracker().isTrusted());
      assertNull(missing.coverageLsn());
      Files.write(directory.resolve("valid"), bytes);
      assertEquals(COVERAGE, ChangedPageTrackerFile.load(directory.resolve("valid")).coverageLsn());
    } finally {
      Files.deleteIfExists(directory.resolve("valid"));
      Files.delete(directory);
    }
  }

  // Recompute checksums so structural validation, not CRC failure, rejects malformed fields.
  // The untrusted header is 37 bytes. Two active records are 20 bytes each, followed by a terminator.
  @Test
  public void validChecksumsCannotAuthorizeMalformedHeadersIndicesOrOrdering() throws Exception {
    int[] offsets = {0, 4, 8, 9, 17, 37, 37, 57, 41, 41, 61, 61, 49};
    long[] values = {0, 2, 8, -1, -1, -1, 2, 0, -1, 2, Long.MAX_VALUE, 0, 0};
    for (int index = 0; index < offsets.length; index++) {
      var bytes = sample();
      var buffer = ByteBuffer.wrap(bytes);
      int offset = offsets[index];
      if (offset == 8) {
        buffer.put(offset, (byte) values[index]);
      } else if (offset == 9 || offset == 41 || offset == 61 || offset == 49) {
        buffer.putLong(offset, values[index]);
      } else {
        buffer.putInt(offset, (int) values[index]);
      }
      repairChecksum(bytes);
      assertUntrusted(bytes);
    }
    var bytes = sample();
    // Duplicate the full first record, including its file identifier and word index.
    System.arraycopy(bytes, 37, bytes, 57, 20);
    repairChecksum(bytes);
    assertUntrusted(bytes);
    var trailing = Arrays.copyOf(sample(), sample().length + 1);
    assertUntrusted(trailing);
  }

  // Transport failures are checked I/O errors, not damaged-content results. Writer callbacks
  // preserve the original error. Invalid coverage cannot be encoded as authoritative content.
  @Test
  public void transportFailuresRemainDistinctAndStreamsStayOpen() throws Exception {
    var failure = new IOException("injected transport failure");
    assertSame(failure, assertThrows(IOException.class, () -> ChangedPageTrackerFile.read(
        new InputStream() {
          @Override
          public int read() throws IOException {
            throw failure;
          }
        })));
    var source = capture(new ChangedPageTracker());
    assertThrows(IllegalArgumentException.class, () -> ChangedPageTrackerFile.write(
        OutputStream.nullOutputStream(), source, LogSequenceNumber.NOT_TRACKED));
    var tracker = new ChangedPageTracker();
    for (int word = 0; word < 1024; word++) {
      tracker.mark(1, word * 64L);
    }
    assertSame(failure, assertThrows(IOException.class, () -> ChangedPageTrackerFile.write(
        new OutputStream() {
          @Override
          public void write(int value) throws IOException {
            throw failure;
          }
        }, capture(tracker), COVERAGE)));
    var out = new ByteArrayOutputStream() {
      @Override
      public void close() {
        throw new AssertionError("Codec closed caller stream");
      }
    };
    ChangedPageTrackerFile.write(out, source, COVERAGE);
    ChangedPageTrackerFile.read(new ByteArrayInputStream(out.toByteArray()) {
      @Override
      public void close() {
        throw new AssertionError("Codec closed caller stream");
      }
    });
  }

  // Transport EOF exceptions at the header, inside a word and during the final end check keep
  // their identity. Only a returned -1 before a complete record is damaged content.
  @Test
  public void transportEofExceptionsPropagateAtHeaderRecordAndEndCheck() throws Exception {
    var bytes = sample();
    for (int failureOffset : new int[] {0, 45, bytes.length}) {
      var failure = new EOFException("injected transport EOF at " + failureOffset);
      var stream = new InputStream() {
        private int position;

        @Override
        public int read() throws IOException {
          if (position == failureOffset) {
            throw failure;
          }
          return position == bytes.length ? -1 : bytes[position++] & 0xff;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
          if (position == failureOffset) {
            throw failure;
          }
          int count = Math.min(length, failureOffset - position);
          System.arraycopy(bytes, position, target, offset, count);
          position += count;
          return count;
        }
      };
      assertSame(failure, assertThrows(EOFException.class,
          () -> ChangedPageTrackerFile.read(stream)));
    }
  }

  // Pause the encoder at its first buffer write. Writers publish words and new segments without
  // save order. Every one-shot mark completed before capture remains in the decoded state.
  @Test
  public void concurrentMarkingCannotLoseMarksCompletedBeforeCapture() throws Exception {
    var tracker = new ChangedPageTracker();
    for (int word = 0; word < 1024; word++) {
      tracker.mark(1, word * 64L);
    }
    var entered = new CountDownLatch(1);
    var released = new CountDownLatch(1);
    var out = new ByteArrayOutputStream() {
      @Override
      public synchronized void write(byte[] bytes, int offset, int length) {
        entered.countDown();
        try {
          assertTrue(released.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
          throw new AssertionError(failure);
        }
        super.write(bytes, offset, length);
      }
    };
    var executor = Executors.newSingleThreadExecutor();
    var future = executor.submit(() -> {
      tracker.saveOrderLock().lock();
      try {
        ChangedPageTrackerFile.write(out, tracker.capture(), COVERAGE);
      } finally {
        tracker.saveOrderLock().unlock();
      }
      return null;
    });
    try {
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      for (int word = 0; word < 2048; word++) {
        tracker.mark(1, word * 64L + 1);
      }
      released.countDown();
      future.get(10, TimeUnit.SECONDS);
      var pages = new TreeSet<Long>();
      capture(read(out.toByteArray()).tracker()).active().forEachCandidate(1, pages::add);
      for (int word = 0; word < 1024; word++) {
        assertTrue(pages.contains(word * 64L));
      }
    } finally {
      released.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  // A child JVM excludes VMLens instrumentation. Sparse input rejects a full extra bitmap.
  // Dense input rejects whole-file buffering. Both budgets include all transient objects.
  @Test
  public void encodingAndLoadingAllocateOnlyBoundedScratchBeyondLoadedBitmaps() throws Exception {
    var process = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-Xmx128m",
        "-XX:-DoEscapeAnalysis", "-cp",
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
        AllocationProbe.class.getName()).redirectErrorStream(true).start();
    try {
      assertTrue(process.waitFor(30, TimeUnit.SECONDS));
      var output = new String(process.getInputStream().readAllBytes(),
          java.nio.charset.StandardCharsets.UTF_8);
      assertEquals(output, 0, process.exitValue());
      assertTrue(output, output.contains("codec allocation"));
    } finally {
      process.destroyForcibly();
      assertTrue(process.waitFor(10, TimeUnit.SECONDS));
    }
  }

  public static final class AllocationProbe {
    public static void main(String[] args) throws Exception {
      var tracker = new ChangedPageTracker();
      int segments = 2048;
      for (int generation = 0; generation < 2; generation++) {
        for (int segment = 0; segment < segments / 2; segment++) {
          tracker.mark(1, segment * 32768L);
        }
        if (generation == 0) {
          tracker.beginBackup();
        }
      }
      long payload = (long) segments * ChangedPageTracker.SEGMENT_BYTES;
      measureAllocation(capture(tracker), payload, payload / 4, "sparse");

      var dense = new ChangedPageTracker();
      int denseSegments = 16;
      for (int segment = 0; segment < denseSegments; segment++) {
        for (int word = 0; word < 512; word++) {
          dense.mark(1, segment * 32768L + word * 64L);
        }
      }
      // Dense wire records exceed the 16 KiB scratch allowance. Buffering the whole file
      // cannot fit, even though the restored bit payload is only 64 KiB.
      measureAllocation(capture(dense), (long) denseSegments * ChangedPageTracker.SEGMENT_BYTES,
          16384, "dense");
    }

    private static void measureAllocation(ChangedPageTracker.SaveState state, long payload,
        long scratchAllowance, String label) throws IOException {
      // Warm codec and restoration paths before measuring. The wire array is caller-owned.
      var bytes = encode(state);
      read(bytes);
      var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
      bean.setThreadAllocatedMemoryEnabled(true);
      long thread = Thread.currentThread().threadId();
      long before = bean.getThreadAllocatedBytes(thread);
      ChangedPageTrackerFile.write(OutputStream.nullOutputStream(), state, COVERAGE);
      long encoding = bean.getThreadAllocatedBytes(thread) - before;
      before = bean.getThreadAllocatedBytes(thread);
      var loaded = read(bytes);
      long loading = bean.getThreadAllocatedBytes(thread) - before;
      long budget = payload + scratchAllowance;
      System.out.println("codec allocation: " + label + ", encoding=" + encoding
          + ", loading=" + loading + ", wire=" + bytes.length + ", loading budget=" + budget);
      assertTrue(label + " encoding allocated " + encoding, encoding < 65536);
      assertTrue(label + " loading allocated " + loading + " with budget " + budget,
          loading < budget);
      assertNotNull(loaded.coverageLsn());
    }
  }

  private static byte[] sample() throws IOException {
    var tracker = new ChangedPageTracker();
    tracker.mark(1, 0);
    tracker.mark(1, 64);
    return encode(capture(tracker));
  }

  private static ChangedPageTracker.SaveState capture(ChangedPageTracker tracker) {
    tracker.saveOrderLock().lock();
    try {
      return tracker.capture();
    } finally {
      tracker.saveOrderLock().unlock();
    }
  }

  private static byte[] encode(ChangedPageTracker.SaveState state) throws IOException {
    var out = new ByteArrayOutputStream();
    ChangedPageTrackerFile.write(out, state, COVERAGE);
    return out.toByteArray();
  }

  private static ChangedPageTrackerFile.Loaded read(byte[] bytes) throws IOException {
    return ChangedPageTrackerFile.read(new ByteArrayInputStream(bytes));
  }

  private static void assertUntrusted(byte[] bytes) throws IOException {
    var loaded = read(bytes);
    assertFalse(loaded.tracker().isTrusted());
    assertNull(loaded.coverageLsn());
  }

  private static void repairChecksum(byte[] bytes) {
    var checksum = new CRC32C();
    checksum.update(bytes, 0, bytes.length - 4);
    ByteBuffer.wrap(bytes).putInt(bytes.length - 4, (int) checksum.getValue());
  }

  private static void assertPages(ChangedPageTracker.Generation generation, int file,
      long... expected) {
    var actual = new TreeSet<Long>();
    generation.forEachCandidate(file, actual::add);
    var pages = new TreeSet<Long>();
    for (long page : expected) {
      pages.add(page);
    }
    assertEquals(pages, actual);
  }
}
