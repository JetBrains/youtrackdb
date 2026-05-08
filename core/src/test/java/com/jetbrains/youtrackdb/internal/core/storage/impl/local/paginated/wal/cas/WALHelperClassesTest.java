package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.NonWritableChannelException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Standalone tests for the small helper classes in {@code paginated.wal.cas}:
 * {@link EventWrapper}, {@link WrittenUpTo}, {@link WALChannelFile}, and
 * {@link WALFile} static factory methods.
 *
 * <p>These helpers are incidentally covered when {@code CASDiskWriteAheadLog} is exercised,
 * but having direct tests pins their individual contracts and satisfies the package-level
 * coverage gate for the helper LoC that the lifecycle tests do not reach.
 */
public class WALHelperClassesTest {

  // Unique per JVM so concurrent test forks (or stale state from a crashed prior run) cannot
  // collide — mandated by the user-global rule "every temporary file or directory must
  // include a unique suffix".
  private static final Path TEST_DIR =
      Paths.get(
          System.getProperty("buildDirectory", "." + File.separator + "target"),
          "walHelperClassesTest-" + UUID.randomUUID());

  @Before
  public void before() throws IOException {
    deleteTestDir();
    Files.createDirectories(TEST_DIR);
  }

  @After
  public void after() throws IOException {
    deleteTestDir();
  }

  private static void deleteTestDir() {
    final var dir = TEST_DIR.toFile();
    if (dir.exists()) {
      for (final var f : dir.listFiles()) {
        f.delete();
      }
      dir.delete();
    }
  }

  // ---------- EventWrapper ----------

  /**
   * Verifies that {@link EventWrapper#fire()} executes the wrapped {@link Runnable} exactly
   * once even when called multiple times in sequence. The second call is a no-op because
   * {@code fired} is already set to {@code true}.
   */
  @Test
  public void eventWrapperFiresExactlyOnceOnRepeatedCalls() {
    final var callCount = new AtomicInteger(0);
    final var wrapper = new EventWrapper(callCount::incrementAndGet);

    wrapper.fire();
    wrapper.fire();
    wrapper.fire();

    // Event must have been executed exactly once regardless of how many times fire() is called.
    assertEquals(1, callCount.get());
  }

  /**
   * Verifies that {@link EventWrapper#fire()} fires immediately on the first call — the counter
   * increments to 1 after a single invocation.
   */
  @Test
  public void eventWrapperFiresOnFirstCall() {
    final var callCount = new AtomicInteger(0);
    final var wrapper = new EventWrapper(callCount::incrementAndGet);

    wrapper.fire();

    assertEquals(1, callCount.get());
  }

  /**
   * Verifies that {@link EventWrapper} enforces the single-fire guarantee under concurrent
   * calls. Multiple threads race on {@link EventWrapper#fire()}; the wrapped event must be
   * executed exactly once regardless of which thread wins the CAS. The {@code @Test(timeout)}
   * caps any worst-case hang at 10 s so a regression cannot stall the build, and the
   * {@code errorRef} captures any thread-side {@link Throwable} so that an exception in a
   * worker thread cannot be silently swallowed when the daemon thread exits.
   */
  @Test(timeout = 10_000)
  public void eventWrapperFiresOnceFromMultipleThreads() throws InterruptedException {
    final var callCount = new AtomicInteger(0);
    final var wrapper = new EventWrapper(callCount::incrementAndGet);

    final int threadCount = 8;
    final var startLatch = new CountDownLatch(1);
    final var doneLatch = new CountDownLatch(threadCount);
    final var errorRef = new AtomicReference<Throwable>();

    for (int t = 0; t < threadCount; t++) {
      final var thread = new Thread(() -> {
        try {
          startLatch.await();
          wrapper.fire();
        } catch (Throwable e) {
          errorRef.compareAndSet(null, e);
          if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
          }
        } finally {
          doneLatch.countDown();
        }
      });
      thread.start();
    }

    startLatch.countDown();
    assertTrue(
        "All worker threads must finish within 5 s",
        doneLatch.await(5, TimeUnit.SECONDS));
    assertNull("Worker thread threw: " + errorRef.get(), errorRef.get());

    // Exactly one thread must have executed the runnable.
    assertEquals(1, callCount.get());
  }

  // ---------- WrittenUpTo ----------

  /**
   * Verifies that {@link WrittenUpTo} preserves both the {@link LogSequenceNumber} and the
   * raw file {@code position} value passed at construction. The record's component accessors
   * must return the exact values, not proxies.
   */
  @Test
  public void writtenUpToPreservesLsnAndPosition() {
    final var lsn = new LogSequenceNumber(7L, 4096);
    final long position = 1048576L;

    final var record = new WrittenUpTo(lsn, position);

    // Pin specific getter values — equality on the record alone is insufficient as a
    // falsifiability check.
    assertEquals(7L, record.lsn().getSegment());
    assertEquals(4096, record.lsn().getPosition());
    assertEquals(position, record.position());
  }

  /**
   * Verifies that two {@link WrittenUpTo} instances with the same field values are equal
   * (Java record semantics) and that inequality holds when the position differs.
   */
  @Test
  public void writtenUpToEqualityByValue() {
    final var lsn = new LogSequenceNumber(3L, 128);

    final var a = new WrittenUpTo(lsn, 512L);
    final var b = new WrittenUpTo(lsn, 512L);
    final var c = new WrittenUpTo(lsn, 1024L);

    assertEquals(a, b);
    // c differs in position — must not be equal to a.
    assertNotEquals(a, c);
  }

  // ---------- WALChannelFile / WALFile ----------

  /**
   * Verifies that {@link WALChannelFile#segmentId()} returns the value passed at construction.
   * Creates a write-mode WAL file via {@link WALFile#createWriteWALFile} and checks the
   * segment ID accessor.
   */
  @Test
  public void walChannelFileSegmentIdMatchesConstructorArg() throws IOException {
    final var path = TEST_DIR.resolve("segment42.1.wal");
    final long expectedSegmentId = 42L;

    try (final var file = WALFile.createWriteWALFile(path, expectedSegmentId)) {
      // segmentId() must return the value stored at construction, not a computed value.
      assertEquals(expectedSegmentId, file.segmentId());
    }
  }

  /**
   * Verifies that data written via {@link WALChannelFile#write(ByteBuffer)} can be read back
   * with the same content via {@link WALChannelFile#readBuffer(ByteBuffer)} after repositioning.
   * This exercises the {@code write}, {@code position(long)}, and {@code readBuffer} paths of
   * {@link WALChannelFile}.
   */
  @Test
  public void walChannelFileWriteAndReadRoundTrip() throws IOException {
    final var path = TEST_DIR.resolve("roundtrip.1.wal");
    final byte[] payload = {1, 2, 3, 4, 5, 6, 7, 8};

    // Write phase — use a write-mode WALFile.
    try (final var writeFile = WALFile.createWriteWALFile(path, 1L)) {
      final var buf = ByteBuffer.wrap(payload).order(ByteOrder.nativeOrder());
      writeFile.write(buf);
      writeFile.force(true);
    }

    // Read phase — use a read-mode WALFile.
    try (final var readFile = WALFile.createReadWALFile(path, 1L)) {
      // Verify position() starts at 0 for a freshly opened read file.
      assertEquals(0L, readFile.position());

      final var buf = ByteBuffer.allocate(payload.length).order(ByteOrder.nativeOrder());
      readFile.readBuffer(buf);

      // Pin the exact byte content — a mere non-null check is insufficient.
      assertArrayEquals(payload, buf.array());
    }
  }

  /**
   * Verifies that {@link WALChannelFile#position(long)} repositions the channel so that
   * a subsequent {@link WALChannelFile#readBuffer(ByteBuffer)} reads from the new offset.
   * Writes two distinct byte sequences at known offsets and confirms independent reads.
   */
  @Test
  public void walChannelFilePositionSeekAndRead() throws IOException {
    final var path = TEST_DIR.resolve("seek.1.wal");
    final byte[] first = {10, 20, 30, 40};
    final byte[] second = {50, 60, 70, 80};

    // Build a 8-byte file: first 4 bytes = first[], last 4 bytes = second[].
    try (final var writeFile = WALFile.createWriteWALFile(path, 1L)) {
      writeFile.write(ByteBuffer.wrap(first));
      writeFile.write(ByteBuffer.wrap(second));
      writeFile.force(false);
    }

    try (final var readFile = WALFile.createReadWALFile(path, 1L)) {
      // Read second block by seeking to offset 4.
      readFile.position(4L);
      assertEquals(4L, readFile.position());

      final var buf = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder());
      readFile.readBuffer(buf);

      // Pin specific byte values at the seeked offset.
      assertArrayEquals(second, buf.array());
    }
  }

  /**
   * Verifies that {@link WALFile#createWriteWALFile} creates or truncates the file and
   * {@link WALFile#createReadWALFile} opens it read-only. A read-only channel must not
   * accept writes — attempting {@link WALFile#write(ByteBuffer)} on the read-mode file
   * must throw {@link NonWritableChannelException}, confirming the channel mode is
   * actually read-only and not just nominally distinguished by the factory name.
   */
  @Test
  public void walFileStaticFactoriesCreateCorrectChannelModes() throws IOException {
    final var path = TEST_DIR.resolve("modes.1.wal");
    final byte[] payload = {99, 88, 77};

    // createWriteWALFile must create and allow writes.
    try (final var wf = WALFile.createWriteWALFile(path, 5L)) {
      wf.write(ByteBuffer.wrap(payload));
      // Pin segmentId returned by the write-mode file.
      assertEquals(5L, wf.segmentId());
    }

    // createReadWALFile must open the same file and allow reads.
    try (final var rf = WALFile.createReadWALFile(path, 5L)) {
      final var buf = ByteBuffer.allocate(payload.length);
      rf.readBuffer(buf);
      assertArrayEquals(payload, buf.array());

      // Attempting to write through the read-mode channel must throw — the underlying
      // FileChannel is opened with StandardOpenOption.READ only.
      try {
        rf.write(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        fail("Writing to a read-mode WALFile must throw NonWritableChannelException");
      } catch (NonWritableChannelException expected) {
        // Pass — the read channel rejected the write as required.
      }
    }
  }

  /**
   * Verifies that opening a new write-mode WAL file with
   * {@link WALFile#createWriteWALFile} on a pre-existing path truncates the old content
   * (due to {@link StandardOpenOption#TRUNCATE_EXISTING}), so the channel starts at position 0.
   */
  @Test
  public void walFileCreateWriteTruncatesExistingContent() throws IOException {
    final var path = TEST_DIR.resolve("trunc.1.wal");

    // Write initial content.
    try (final var wf = WALFile.createWriteWALFile(path, 1L)) {
      wf.write(ByteBuffer.wrap(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}));
    }

    // Re-open in write mode — TRUNCATE_EXISTING must reset the file to 0 bytes.
    try (final var wf2 = WALFile.createWriteWALFile(path, 2L)) {
      // After truncation the channel position is 0.
      assertEquals(0L, wf2.position());
      // segmentId from the re-opened instance must match the new value, not the old one.
      assertEquals(2L, wf2.segmentId());
    }

    // File must now be 0 bytes (completely truncated).
    assertEquals(0L, Files.size(path));
  }
}
