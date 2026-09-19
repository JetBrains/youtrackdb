package com.jetbrains.youtrackdb.internal.core.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Tests disk traversal, request-driven caching, and generation isolation. */
public class DatabaseDiskUsageTest {

  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  /** Nested regular files count, while symbolic links and their targets do not count twice. */
  @Test
  public void measureDirectoryCountsNestedFilesWithoutFollowingSymbolicLinks() throws Exception {
    var root = temporaryFolder.newFolder("database").toPath();
    Files.write(root.resolve("first.bin"), new byte[7]);
    var nested = Files.createDirectories(root.resolve("nested"));
    Files.write(nested.resolve("second.bin"), new byte[11]);
    Files.createSymbolicLink(root.resolve("linked-directory"), nested);
    Files.createSymbolicLink(root.resolve("linked-file"), nested.resolve("second.bin"));

    assertThat(DatabaseDiskUsage.measureDirectory(root)).isEqualTo(18);
  }

  /** A file removed during traversal is skipped, while other access failures remain visible. */
  @Test
  public void visitorSkipsRemovedFilesAndReportsOtherFailures() throws Exception {
    var visitor = new DatabaseDiskUsage.DirectorySizeVisitor();
    var removed = temporaryFolder.getRoot().toPath().resolve("removed.bin");

    assertThat(visitor.visitFileFailed(removed, new NoSuchFileException(removed.toString())))
        .isEqualTo(FileVisitResult.CONTINUE);
    assertThatThrownBy(
        () -> visitor.visitFileFailed(removed, new AccessDeniedException(removed.toString())))
        .isInstanceOf(AccessDeniedException.class);
  }

  /** A directory whose apparent file-size sum exceeds long range reports an input/output error. */
  @Test
  public void visitorReportsSizeOverflow() throws Exception {
    var visitor = new DatabaseDiskUsage.DirectorySizeVisitor();
    visitor.visitFile(temporaryFolder.newFile("large").toPath(), attributes(Long.MAX_VALUE));

    assertThatThrownBy(
        () -> visitor.visitFile(temporaryFolder.newFile("overflow").toPath(), attributes(1)))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("exceeds");
  }

  private static BasicFileAttributes attributes(long size) {
    return new BasicFileAttributes() {
      @Override
      public FileTime lastModifiedTime() {
        return FileTime.fromMillis(0);
      }

      @Override
      public FileTime lastAccessTime() {
        return FileTime.fromMillis(0);
      }

      @Override
      public FileTime creationTime() {
        return FileTime.fromMillis(0);
      }

      @Override
      public boolean isRegularFile() {
        return true;
      }

      @Override
      public boolean isDirectory() {
        return false;
      }

      @Override
      public boolean isSymbolicLink() {
        return false;
      }

      @Override
      public boolean isOther() {
        return false;
      }

      @Override
      public long size() {
        return size;
      }

      @Override
      public Object fileKey() {
        return null;
      }
    };
  }

  /** An expired value refreshes once, and failed refreshes do not become successful zero values. */
  @Test
  public void cacheRefreshesAfterExpiryAndReportsRefreshFailure() {
    var time = new AtomicLong();
    var scans = new AtomicInteger();
    DatabaseDiskUsage.DirectorySizer sizer = ignored -> {
      var scan = scans.incrementAndGet();
      if (scan == 3) {
        throw new IOException("unreadable");
      }
      return scan * 10L;
    };
    var usage = new DatabaseDiskUsage(10, null, sizer, time::get);
    var directory = temporaryFolder.getRoot().toPath();
    var entry = usage.register("db", () -> 0);

    assertThat(usage.get(entry, directory)).isEqualTo(10);
    time.set(TimeUnit.MILLISECONDS.toNanos(9));
    assertThat(usage.get(entry, directory)).isEqualTo(10);
    time.set(TimeUnit.MILLISECONDS.toNanos(10));
    assertThat(usage.get(entry, directory)).isEqualTo(20);
    time.set(TimeUnit.MILLISECONDS.toNanos(20));
    assertThatThrownBy(() -> usage.get(entry, directory))
        .hasMessageContaining("Cannot calculate disk usage");
    assertThat(scans).hasValue(3);
  }

  /** Concurrent cache misses wait for one shared scan and receive the same result. */
  @Test
  public void concurrentRequestsShareOneRefresh() throws Exception {
    var scans = new AtomicInteger();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    DatabaseDiskUsage.DirectorySizer sizer = ignored -> {
      scans.incrementAndGet();
      entered.countDown();
      try {
        if (!release.await(10, TimeUnit.SECONDS)) {
          throw new IOException("Timed out while waiting for test release");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted while waiting for test release", e);
      }
      return 42;
    };
    var usage = new DatabaseDiskUsage(1_000, null, sizer, () -> 0);
    var directory = temporaryFolder.getRoot().toPath();
    var entry = usage.register("db", () -> 0);

    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> usage.get(entry, directory));
      assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
      var second = executor.submit(() -> usage.get(entry, directory));
      release.countDown();

      assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(42);
      assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(42);
      assertThat(scans).hasValue(1);
    }
  }

  /** Concurrent callers share one failed refresh and receive the same reported failure. */
  @Test
  public void concurrentRequestsShareFailedRefresh() throws Exception {
    var scans = new AtomicInteger();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    DatabaseDiskUsage.DirectorySizer sizer = ignored -> {
      scans.incrementAndGet();
      entered.countDown();
      try {
        if (!release.await(10, TimeUnit.SECONDS)) {
          throw new IOException("Timed out while waiting for test release");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted while waiting for test release", e);
      }
      throw new IOException("unreadable");
    };
    var usage = new DatabaseDiskUsage(1_000, null, sizer, () -> 0);
    var entry = usage.register("db", () -> 0);
    var directory = temporaryFolder.getRoot().toPath();

    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> usage.get(entry, directory));
      assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
      var secondThread = new AtomicReference<Thread>();
      var secondStarted = new CountDownLatch(1);
      var second = executor.submit(() -> {
        secondThread.set(Thread.currentThread());
        secondStarted.countDown();
        return usage.get(entry, directory);
      });
      assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
      awaitWaiting(secondThread.get());
      release.countDown();

      assertThatThrownBy(() -> first.get(10, TimeUnit.SECONDS))
          .hasRootCauseMessage("unreadable");
      assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
          .hasRootCauseMessage("unreadable");
      assertThat(scans).hasValue(1);
    }
  }

  private static void awaitWaiting(Thread thread) {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertThat(thread.getState()).isEqualTo(Thread.State.WAITING);
  }

  /** A fetch handle obtained before removal stays detached from the replacement cache entry. */
  @Test
  public void removedEntryCannotBeRecreatedByDelayedFetch() {
    var scans = new AtomicInteger();
    var usage = new DatabaseDiskUsage(1_000, null,
        ignored -> scans.incrementAndGet() * 10L, () -> 0);
    var directory = temporaryFolder.getRoot().toPath();
    var oldEntry = usage.register("db", () -> 0);

    usage.remove("db");
    var replacementEntry = usage.register("db", () -> 0);

    assertThat(usage.get(oldEntry, directory)).isEqualTo(10);
    assertThat(usage.get(replacementEntry, directory)).isEqualTo(20);
    assertThat(usage.get(replacementEntry, directory)).isEqualTo(20);
  }

  /** Removing and registering a database isolates a replacement from an older in-flight scan. */
  @Test
  public void removedEntryCannotPublishIntoReplacementEntry() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var scans = new AtomicInteger();
    DatabaseDiskUsage.DirectorySizer sizer = ignored -> {
      if (scans.incrementAndGet() == 1) {
        entered.countDown();
        try {
          if (!release.await(10, TimeUnit.SECONDS)) {
            throw new IOException("Timed out while waiting for test release");
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("Interrupted while waiting for test release", e);
        }
        return 10;
      }
      return 20;
    };
    var usage = new DatabaseDiskUsage(1_000, null, sizer, () -> 0);
    var directory = temporaryFolder.getRoot().toPath();
    var oldEntry = usage.register("db", () -> 0);

    try (var executor = Executors.newSingleThreadExecutor()) {
      var oldScan = executor.submit(() -> usage.get(oldEntry, directory));
      assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
      usage.remove("db");
      var newEntry = usage.register("db", () -> 0);
      assertThat(usage.get(newEntry, directory)).isEqualTo(20);
      release.countDown();
      assertThat(oldScan.get(10, TimeUnit.SECONDS)).isEqualTo(10);
      assertThat(usage.get(newEntry, directory)).isEqualTo(20);
    }
  }

  /** The largest positive millisecond duration saturates safely instead of expiring immediately. */
  @Test
  public void maximumCacheDurationDoesNotOverflow() {
    var scans = new AtomicInteger();
    var time = new AtomicLong();
    var usage = new DatabaseDiskUsage(Long.MAX_VALUE, null,
        ignored -> scans.incrementAndGet(), time::get);
    var directory = temporaryFolder.getRoot().toPath();
    var entry = usage.register("db", () -> 0);

    assertThat(usage.get(entry, directory)).isEqualTo(1);
    time.set(Long.MAX_VALUE - 1);
    assertThat(usage.get(entry, directory)).isEqualTo(1);
    assertThat(scans).hasValue(1);
  }

  /** Registering a database creates cache state without calculating its directory size. */
  @Test
  public void registrationDoesNotScan() {
    var scans = new AtomicInteger();
    var usage = new DatabaseDiskUsage(1_000, null,
        ignored -> scans.incrementAndGet(), () -> 0);

    usage.register("db", () -> 0);

    assertThat(scans).hasValue(0);
  }

  /** Memory entries cache zero without invoking the directory scanner. */
  @Test
  public void memoryDatabaseReturnsZeroWithoutScanning() {
    var usage = new DatabaseDiskUsage(1_000, null,
        ignored -> {
          throw new AssertionError("Memory database must not scan a directory");
        }, () -> 0);

    var entry = usage.register("memory", () -> 0);
    assertThat(usage.get(entry, null)).isZero();
  }

  /** Closing cache state prevents a delayed creator from registering another entry. */
  @Test
  public void closePreventsDelayedRegistration() {
    var usage = new DatabaseDiskUsage(1_000, null);
    usage.register("db", () -> 0);

    usage.close();

    assertThat(usage.register("late", () -> 0)).isNull();
    assertThatThrownBy(() -> usage.get(null, temporaryFolder.getRoot().toPath()))
        .isInstanceOf(DatabaseException.class)
        .hasMessageContaining("closed");
  }

  /** Zero and negative cache durations are configuration errors. */
  @Test
  public void cacheDurationMustBePositive() {
    assertThatThrownBy(() -> new DatabaseDiskUsage(0, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
    assertThatThrownBy(() -> new DatabaseDiskUsage(-1, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
  }
}
