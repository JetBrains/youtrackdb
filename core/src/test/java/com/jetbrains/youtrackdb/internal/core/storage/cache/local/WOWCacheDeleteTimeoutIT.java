package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.collection.closabledictionary.ClosableLinkedContainer;
import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.storage.ChecksumMode;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.doublewritelog.DoubleWriteLogNoOP;
import com.jetbrains.youtrackdb.internal.core.storage.fs.File;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas.CASDiskWriteAheadLog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Verifies that WOWCache.delete() completes within a reasonable time even after many rapid
 * create/destroy cycles that accumulate cancelled tasks in the static executors.
 *
 * <p>Regression test for a bug where stopFlush() used TimeUnit.MINUTES instead of
 * TimeUnit.MILLISECONDS for the WAL_SHUTDOWN_TIMEOUT, causing an effectively infinite wait
 * (~7 days) when the periodic flush task was slow to complete.
 */
@Category(SequentialTest.class)
public class WOWCacheDeleteTimeoutIT {

  private static final int PAGE_SIZE = DurablePage.NEXT_FREE_POSITION + 8;
  private static final ByteBufferPool BUFFER_POOL = new ByteBufferPool(PAGE_SIZE);

  private static Path storagePath;
  private ExecutorService executor;

  @BeforeClass
  public static void beforeClass() {
    GlobalConfiguration.STORAGE_EXCLUSIVE_FILE_ACCESS.setValue(false);
    GlobalConfiguration.FILE_LOCK.setValue(false);

    var buildDirectory = System.getProperty("buildDirectory", ".");
    storagePath = Paths.get(buildDirectory).resolve("WOWCacheDeleteTimeoutIT");
  }

  @Before
  public void setUp() throws IOException {
    if (Files.exists(storagePath)) {
      FileUtils.deleteDirectory(storagePath.toFile());
    }
    Files.createDirectories(storagePath);
    executor = Executors.newCachedThreadPool();
  }

  @After
  public void tearDown() throws IOException {
    executor.shutdownNow();
    if (Files.exists(storagePath)) {
      FileUtils.deleteDirectory(storagePath.toFile());
    }
  }

  /**
   * Creates and destroys many WOWCache + WAL instances in a loop to accumulate cancelled
   * tasks in the static WOWCache.commitExecutor and CASDiskWriteAheadLog.commitExecutor.
   * Then verifies the final WOWCache.delete() completes within 30 seconds.
   *
   * <p>Before the fix, the timeout in stopFlush() was 10000 MINUTES (~7 days), so any
   * transient delay would hang indefinitely. With the fix (10000 MILLISECONDS = 10s),
   * the system recovers promptly. The test uses the same 10s shutdown timeout as
   * production to avoid false failures on slow CI hardware (e.g., ARM runners).
   */
  @Test
  public void testDeleteCompletesAfterManyCreateDestroyCycles() throws Exception {
    // Phase 1: rapid create/destroy cycles.
    // Each cycle creates a WAL (which schedules a periodic RecordsWriter on the static
    // WAL commitExecutor) and a WOWCache (which schedules a PeriodicFlushTask on the
    // static WOWCache commitExecutor). Destroying them cancels these tasks.
    for (var i = 0; i < 50; i++) {
      var instancePath = storagePath.resolve("instance_" + i);
      Files.createDirectories(instancePath);

      var wal = createWAL(instancePath, "storage_" + i);
      var files = new ClosableLinkedContainer<Long, File>(1024);
      var cache = createWOWCache(wal, files, instancePath, "storage_" + i);
      cache.loadRegisteredFiles();

      // Add a file so the cache has something to manage
      cache.addFile("test_" + i + ".dat");

      cache.delete();
      wal.delete();
    }

    // Phase 2: create one final instance and verify delete() completes promptly.
    var finalPath = storagePath.resolve("instance_final");
    Files.createDirectories(finalPath);

    var wal = createWAL(finalPath, "storage_final");
    var files = new ClosableLinkedContainer<Long, File>(1024);
    var cache = createWOWCache(wal, files, finalPath, "storage_final");
    cache.loadRegisteredFiles();
    cache.addFile("test_final.dat");

    // Run delete() on a separate thread with a timeout guard
    var completed = new CountDownLatch(1);
    var error = new AtomicReference<Throwable>();

    var deleteThread = new Thread(() -> {
      try {
        cache.delete();
        wal.delete();
      } catch (Throwable t) {
        error.set(t);
      } finally {
        completed.countDown();
      }
    }, "WOWCacheDeleteTimeoutIT-delete");
    deleteThread.start();

    // With the fix, delete should finish well within 30 seconds.
    // Before the fix, stopFlush() would wait 10000 minutes.
    var finishedInTime = completed.await(30, TimeUnit.SECONDS);

    if (!finishedInTime) {
      deleteThread.interrupt();
      Assert.fail(
          "WOWCache.delete() did not complete within 30 seconds — "
              + "stopFlush() likely has incorrect TimeUnit (MINUTES instead of MILLISECONDS).");
    }

    if (error.get() != null) {
      throw new AssertionError("WOWCache.delete() failed with exception", error.get());
    }
  }

  private static CASDiskWriteAheadLog createWAL(Path path, String name) throws IOException {
    return new CASDiskWriteAheadLog(
        name,
        path,
        path,
        ContextConfiguration.WAL_DEFAULT_NAME,
        12_000,
        128,
        null,
        null,
        Integer.MAX_VALUE,
        Integer.MAX_VALUE,
        25,
        true,
        Locale.US,
        -1,
        1000,
        false,
        false,
        true,
        10);
  }

  private WOWCache createWOWCache(
      CASDiskWriteAheadLog wal,
      ClosableLinkedContainer<Long, File> files,
      Path path,
      String name) {
    return new WOWCache(
        PAGE_SIZE,
        false,
        BUFFER_POOL,
        wal,
        new DoubleWriteLogNoOP(),
        1, // pagesFlushInterval: 1ms — short interval to trigger frequent periodic flushes
        10_000, // shutdownTimeout: 10s — long enough for flush to finish on slow hardware
        100,
        path,
        name,
        files,
        1,
        ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME,
        ChecksumMode.StoreAndVerify,
        null,
        null,
        false,
        executor);
  }
}
