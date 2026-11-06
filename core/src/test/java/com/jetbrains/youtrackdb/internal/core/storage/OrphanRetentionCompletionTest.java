package com.jetbrains.youtrackdb.internal.core.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.SequentialTest;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TemporaryFolder;

// Retention warnings must not overlap other classes that capture this logger globally.
@Category(SequentialTest.class)
public class OrphanRetentionCompletionTest {
  private static final Clock NOW = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"),
      ZoneOffset.UTC);
  private static final String OLD = "20260102T030405006Z-0123456789abcdef0123456789abcdef";

  @Rule
  public TemporaryFolder temporary = new TemporaryFolder();

  private Path storage() {
    return temporary.getRoot().toPath();
  }

  private Path expiredFile() throws IOException {
    final var folder = Files.createDirectories(storage().resolve("orphans").resolve(OLD));
    Files.writeString(folder.resolve("other"), "keep until a later pass");
    return Files.writeString(folder.resolve("data"), "expired");
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue("test barrier deadline", latch.await(5, TimeUnit.SECONDS));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private static Thread start(FutureTask<?> task) {
    final var thread = new Thread(task);
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  /** Stop is latched before submission, performs no I/O, and signals only when run exits. */
  @Test(timeout = 15000)
  public void stopBeforeRunSignalsWithoutFileOperations() throws Exception {
    final var retention = new TrackedRetention();
    retention.requestStop();
    retention.requestStop();
    assertFalse(retention.awaitCompletion(0, TimeUnit.NANOSECONDS));
    retention.run(storage(), null, 180);
    assertTrue(retention.awaitCompletion(0, TimeUnit.NANOSECONDS));
    assertEquals(0, retention.operations.get());
    assertThrows(IllegalStateException.class, () -> retention.run(storage(), null, 180));
    assertTrue(retention.awaitCompletion(0, TimeUnit.NANOSECONDS));
  }

  /** A stopped read or deletion finishes alone, then every close precedes the completion signal. */
  @Test(timeout = 30000)
  public void blockedOperationTimeoutThenCompletionAfterAllCloses() throws Exception {
    final var file = expiredFile();
    for (boolean delete : new boolean[] {false, true}) {
      final var retention = new TrackedRetention();
      retention.blockDelete = delete;
      retention.blockRead = !delete;
      retention.blockStorageClose = true;
      final var run = new FutureTask<Void>(() -> {
        retention.run(storage(), null, 180);
        return null;
      });
      final var thread = start(run);
      try {
        await(retention.operationEntered);
        assertThrows(IllegalStateException.class, () -> retention.run(storage(), null, 180));
        retention.requestStop();
        assertFalse(retention.awaitCompletion(1, TimeUnit.MILLISECONDS));
        final int operations = retention.operations.get();
        retention.releaseOperation.countDown();
        await(retention.closeEntered);
        assertEquals(operations, retention.operations.get());
        assertEquals(1, retention.openHandles.get());
        assertFalse(retention.awaitCompletion(0, TimeUnit.NANOSECONDS));
        retention.releaseClose.countDown();
        run.get(5, TimeUnit.SECONDS);
        assertTrue(retention.awaitCompletion(0, TimeUnit.NANOSECONDS));
        assertEquals(0, retention.openHandles.get());
        assertEquals(delete ? 1 : 0, retention.deletions.get());
        assertTrue(Files.isDirectory(file.getParent()));
        try (var entries = Files.list(file.getParent())) {
          assertEquals(delete ? 1 : 2, entries.count());
        }
      } finally {
        retention.releaseOperation.countDown();
        retention.releaseClose.countDown();
        thread.interrupt();
        thread.join(5000);
      }
    }
  }

  /** Runtime exceptions and Errors propagate unchanged, after cleanup and completion notification. */
  @Test(timeout = 15000)
  public void uncheckedFailuresSignalAfterHandleCleanup() throws Exception {
    expiredFile();
    for (var failure : new Throwable[] {new IllegalStateException("injected"),
        new AssertionError("injected")}) {
      final var retention = new TrackedRetention() {
        @Override
        void deleteEntry(Path path, SecureDirectoryStream<Path> parent, boolean directory) {
          if (failure instanceof Error error) {
            throw error;
          }
          throw (RuntimeException) failure;
        }
      };
      assertSame(failure, assertThrows(failure.getClass(),
          () -> retention.run(storage(), null, 180)));
      assertEquals(0, retention.openHandles.get());
      assertTrue(retention.awaitCompletion(0, TimeUnit.NANOSECONDS));
    }
  }

  /** An unowned stream is closed before iterator failure propagates and completion is signalled. */
  @Test(timeout = 15000)
  public void iteratorConstructionFailureClosesStreamBeforeCompletion() throws Exception {
    final var folder = Files.createDirectories(storage().resolve("orphans").resolve(OLD));
    for (boolean errorIterator : new boolean[] {false, true}) {
      for (Throwable closeFailure : new Throwable[] {null, new IOException("close"),
          new IllegalArgumentException("close"), new AssertionError("close")}) {
        final var retention = new FailingStreamsRetention();
        retention.iteratorFailurePath = folder;
        if (closeFailure != null) {
          retention.closeFailures.put(folder, closeFailure);
        }
        // Each run needs a fresh failure so suppressed exceptions do not leak across cases.
        final Throwable expected = errorIterator
            ? new AssertionError("iterator") : new IllegalStateException("iterator");
        retention.iteratorFailure = expected;
        assertSame(expected, assertThrows(expected.getClass(),
            () -> retention.run(storage(), null, 180)));
        assertEquals(closeFailure == null ? List.of() : List.of(closeFailure),
            List.of(expected.getSuppressed()));
        assertEquals(List.of(folder, folder.getParent(), storage()), retention.closeOrder);
        assertEquals(0, retention.openHandles.get());
        assertTrue(retention.awaitCompletion(0, TimeUnit.NANOSECONDS));
      }
    }
  }

  /** Runtime close failures retain their identity while every ancestor close is attempted. */
  @Test(timeout = 15000)
  public void runtimeCloseFailureAttemptsAllClosesBeforeCompletion() throws Exception {
    assertAllClosesBeforeCompletion(false);
  }

  /** Error close failures also retain their identity and suppress later unchecked close failures. */
  @Test(timeout = 15000)
  public void errorCloseFailureAttemptsAllClosesBeforeCompletion() throws Exception {
    assertAllClosesBeforeCompletion(true);
  }

  private void assertAllClosesBeforeCompletion(boolean errorFirst) throws Exception {
    final var folder = Files.createDirectories(storage().resolve("orphans").resolve(OLD));
    final var middle = Files.createDirectory(folder.resolve("middle"));
    final var deepest = Files.createDirectory(middle.resolve("deepest"));
    for (boolean stopped : new boolean[] {true, false}) {
      final var retention = new FailingStreamsRetention();
      retention.stopAtIteratorPath = stopped ? deepest : null;
      final Throwable first = errorFirst
          ? new AssertionError("first close") : new IllegalStateException("first close");
      final Throwable later = errorFirst
          ? new IllegalArgumentException("later close") : new AssertionError("later close");
      retention.closeFailures.put(deepest, first);
      retention.closeFailures.put(middle, new IOException("warn and continue"));
      retention.closeFailures.put(folder, later);
      assertSame(first, assertThrows(first.getClass(),
          () -> retention.run(storage(), null, 180)));
      assertEquals(List.of(deepest, middle, folder, folder.getParent(), storage()),
          retention.closeOrder);
      assertEquals(List.of(later), List.of(first.getSuppressed()));
      assertEquals(0, retention.openHandles.get());
      assertTrue(retention.awaitCompletion(0, TimeUnit.NANOSECONDS));
      assertTrue("close failure must not delete the candidate", Files.isDirectory(folder));
    }
  }

  /** Disabled retention, run expiry, missing storage and an early clock failure all signal. */
  @Test(timeout = 15000)
  public void earlyExitsSignalCompletion() throws Exception {
    for (int days : new int[] {0, -1, Integer.MIN_VALUE}) {
      final var retention = new TrackedRetention();
      retention.run(storage(), null, days);
      assertEquals(0, retention.operations.get());
      assertTrue(retention.awaitCompletion(0, TimeUnit.NANOSECONDS));
    }
    final var ticks = new AtomicInteger();
    final var expired = new OrphanRetention(NOW,
        () -> ticks.getAndIncrement() == 0 ? 0 : OrphanRetention.RUN_LIMIT_NANOS);
    expired.run(storage(), null, 180);
    assertTrue(expired.awaitCompletion(0, TimeUnit.NANOSECONDS));
    final var missing = new TrackedRetention();
    missing.run(storage().resolve("missing"), null, 180);
    assertTrue(missing.awaitCompletion(0, TimeUnit.NANOSECONDS));
    expiredFile();
    final var normal = new TrackedRetention();
    normal.run(storage(), null, 180);
    assertEquals(3, normal.deletions.get());
    assertEquals(0, normal.openHandles.get());
    assertTrue(normal.awaitCompletion(0, TimeUnit.NANOSECONDS));
    final var brokenClock = new OrphanRetention(NOW, () -> {
      throw new IllegalStateException("clock failure");
    });
    assertThrows(IllegalStateException.class, () -> brokenClock.run(storage(), null, 180));
    assertTrue(brokenClock.awaitCompletion(0, TimeUnit.NANOSECONDS));
  }

  /** All waiters are parked inside awaitCompletion before release, and wake well before timeout. */
  @Test(timeout = 15000)
  public void completionReleasesAllWaitingThreads() throws Exception {
    final var retention = new OrphanRetention(NOW, () -> 0);
    final var results = new ArrayList<FutureTask<Boolean>>();
    final var threads = new ArrayList<Thread>();
    try {
      for (int i = 0; i < 4; i++) {
        // A missing signal must fail the five-second get, not return true after a short timeout.
        final var result = new FutureTask<Boolean>(
            () -> retention.awaitCompletion(1, TimeUnit.DAYS));
        results.add(result);
        threads.add(start(result));
      }
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      for (var thread : threads) {
        while (thread.getState() != Thread.State.TIMED_WAITING) {
          assertTrue("waiter did not park", System.nanoTime() < deadline);
          Thread.yield();
        }
        assertTrue("waiter must be in completion wait",
            java.util.Arrays.stream(thread.getStackTrace())
                .anyMatch(frame -> frame.getMethodName().equals("awaitCompletion")));
      }
      retention.run(storage(), null, 0);
      for (var result : results) {
        assertTrue(result.get(5, TimeUnit.SECONDS));
      }
    } finally {
      for (var thread : threads) {
        thread.interrupt();
        thread.join(1000);
      }
    }
  }

  /** Interrupted waiting does not prevent a later waiter from observing completion. */
  @Test(timeout = 15000)
  public void interruptedWaitDoesNotConsumeCompletion() throws Exception {
    final var retention = new OrphanRetention(NOW, () -> 0);
    Thread.currentThread().interrupt();
    try {
      assertThrows(InterruptedException.class,
          () -> retention.awaitCompletion(1, TimeUnit.DAYS));
    } finally {
      Thread.interrupted();
    }
    retention.run(storage(), null, 0);
    assertTrue(retention.awaitCompletion(0, TimeUnit.NANOSECONDS));
  }

  // Inject failures at stream ownership boundaries without relying on provider-specific behavior.
  private static final class FailingStreamsRetention extends TrackedRetention {
    final List<Path> closeOrder = new ArrayList<>();
    final Map<Path, Throwable> closeFailures = new HashMap<>();
    Path iteratorFailurePath;
    Throwable iteratorFailure;
    Path stopAtIteratorPath;

    @Override
    DirectoryStream<Path> openStorage(Path path) throws IOException {
      return failAtBoundaries(path, super.openStorage(path));
    }

    @Override
    DirectoryStream<Path> openDirectory(Path path, SecureDirectoryStream<Path> parent)
        throws IOException {
      return failAtBoundaries(path, super.openDirectory(path, parent));
    }

    private DirectoryStream<Path> failAtBoundaries(Path path, DirectoryStream<Path> entries) {
      return new DirectoryStream<>() {
        @Override
        public Iterator<Path> iterator() {
          if (path.equals(iteratorFailurePath)) {
            throwUnchecked(iteratorFailure);
          }
          if (path.equals(stopAtIteratorPath)) {
            requestStop();
          }
          return entries.iterator();
        }

        @Override
        public void close() throws IOException {
          try {
            assertFalse("completion before close entry: " + path,
                awaitCompletion(0, TimeUnit.NANOSECONDS));
          } catch (InterruptedException interrupted) {
            throw new AssertionError(interrupted);
          }
          closeOrder.add(path);
          entries.close();
          final var failure = closeFailures.get(path);
          if (failure instanceof IOException ioFailure) {
            throw ioFailure;
          }
          if (failure != null) {
            throwUnchecked(failure);
          }
        }
      };
    }

    private static void throwUnchecked(Throwable failure) {
      if (failure instanceof Error error) {
        throw error;
      }
      throw (RuntimeException) failure;
    }
  }

  // Ordinary stream wrappers exercise the fallback on every provider and track iterator I/O too.
  private static class TrackedRetention extends OrphanRetention {
    final AtomicInteger operations = new AtomicInteger();
    final AtomicInteger openHandles = new AtomicInteger();
    final AtomicInteger deletions = new AtomicInteger();
    final CountDownLatch operationEntered = new CountDownLatch(1);
    final CountDownLatch releaseOperation = new CountDownLatch(1);
    final CountDownLatch closeEntered = new CountDownLatch(1);
    final CountDownLatch releaseClose = new CountDownLatch(1);
    boolean blockRead;
    boolean blockDelete;
    boolean blockStorageClose;

    TrackedRetention() {
      super(NOW, () -> 0);
    }

    private void blockOperation() {
      operationEntered.countDown();
      await(releaseOperation);
    }

    @Override
    DirectoryStream<Path> openStorage(Path path) throws IOException {
      operations.incrementAndGet();
      return track(super.openStorage(path), true);
    }

    @Override
    DirectoryStream<Path> openDirectory(Path path, SecureDirectoryStream<Path> parent)
        throws IOException {
      operations.incrementAndGet();
      return track(super.openDirectory(path, parent), false);
    }

    @Override
    BasicFileAttributes readAttributes(Path path, SecureDirectoryStream<Path> parent)
        throws IOException {
      operations.incrementAndGet();
      if (blockRead) {
        blockOperation();
      }
      return super.readAttributes(path, parent);
    }

    @Override
    void deleteEntry(Path path, SecureDirectoryStream<Path> parent, boolean directory)
        throws IOException {
      operations.incrementAndGet();
      if (blockDelete) {
        blockOperation();
      }
      super.deleteEntry(path, parent, directory);
      deletions.incrementAndGet();
    }

    private DirectoryStream<Path> track(DirectoryStream<Path> entries, boolean storage) {
      openHandles.incrementAndGet();
      return new DirectoryStream<>() {
        @Override
        public Iterator<Path> iterator() {
          final var iterator = entries.iterator();
          return new Iterator<>() {
            @Override
            public boolean hasNext() {
              operations.incrementAndGet();
              return iterator.hasNext();
            }

            @Override
            public Path next() {
              operations.incrementAndGet();
              return iterator.next();
            }
          };
        }

        @Override
        public void close() throws IOException {
          assertFalse("completion before close", completed());
          if (storage && blockStorageClose) {
            closeEntered.countDown();
            await(releaseClose);
          }
          entries.close();
          openHandles.decrementAndGet();
        }
      };
    }

    private boolean completed() {
      try {
        return awaitCompletion(0, TimeUnit.NANOSECONDS);
      } catch (InterruptedException interrupted) {
        throw new AssertionError(interrupted);
      }
    }
  }
}
