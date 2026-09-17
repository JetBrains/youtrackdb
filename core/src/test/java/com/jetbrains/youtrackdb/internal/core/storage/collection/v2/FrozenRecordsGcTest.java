package com.jetbrains.youtrackdb.internal.core.storage.collection.v2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.function.TxConsumer;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.CurrentStorageComponentsFactory;
import com.jetbrains.youtrackdb.internal.core.exception.ModificationOperationProhibitedException;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.disk.PeriodicRecordsGc;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.operationsfreezer.FreezeKind;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.StorageComponent;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/** Regression coverage for records garbage collection during an operator freeze. */
@Category(SequentialTest.class)
public class FrozenRecordsGcTest {

  /**
   * The real scheduled task observes a freeze after its coordinator probe, rejects each
   * collection once, and remains scheduled to reclaim the records after release.
   */
  @Test(timeout = 60_000)
  public void scheduledCleanupRaceIsBoundedAndResumesAfterRelease() throws Throwable {
    var manager = DbTestBase.createYTDBManagerAndDb(
        "test", DatabaseType.MEMORY, getClass());
    DatabaseSessionEmbedded session = null;
    var resourcesSafeToClose = new AtomicBoolean(true);
    try {
      session = manager.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
      var storage = storage(session);
      createDeadVersions(session, storage, "GcFrozen");
      storage.getContextConfiguration()
          .setValue(GlobalConfiguration.STORAGE_COLLECTION_GC_MIN_THRESHOLD, Integer.MAX_VALUE);
      storage.periodicRecordsGc();
      storage.getContextConfiguration()
          .setValue(GlobalConfiguration.STORAGE_COLLECTION_GC_MIN_THRESHOLD, 0);
      var collections = findCollections(session, storage, "GcFrozen");
      assertThat(collections).isNotEmpty();

      var originalAtomicManager = storage.getAtomicOperationsManager();
      var probingAtomicManager = spy(originalAtomicManager);
      var freezeId = new AtomicLong(-1);
      var armRace = new AtomicBoolean(true);
      doAnswer(invocation -> {
        var active = (boolean) invocation.callRealMethod();
        if (!active && armRace.compareAndSet(true, false)) {
          freezeId.set(originalAtomicManager.freezeWriteOperations(
              FreezeKind.OPERATOR,
              () -> new ModificationOperationProhibitedException(
                  storage.getName(), "Modification requests are prohibited")));
        }
        return active;
      }).when(probingAtomicManager).isOperatorFreezeActive();
      setAtomicOperationsManager(storage, probingAtomicManager);

      var captured = new CopyOnWriteArrayList<LogRecord>();
      var maximumCollectionAttempts = storage.getCollectionInstances().stream()
          .filter(PaginatedCollectionV2.class::isInstance)
          .count();
      var logger = Logger.getLogger(PaginatedCollectionV2.class.getName());
      var priorLevel = logger.getLevel();
      var handler = boundedCapturingHandler(captured, maximumCollectionAttempts);
      ScheduledExecutorService executor = null;
      ScheduledFuture<?> future = null;
      var workerTerminated = true;
      Throwable testFailure = null;
      var firstRunFinished = new CountDownLatch(1);
      var resumedRunFinished = new CountDownLatch(1);
      var released = new AtomicBoolean();
      var task = new PeriodicRecordsGc(storage);
      logger.setLevel(Level.ALL);
      logger.addHandler(handler);
      try {
        executor = Executors.newSingleThreadScheduledExecutor();
        future = executor.scheduleWithFixedDelay(() -> {
          task.run();
          if (released.get()
              && collections.stream()
                  .allMatch(collection -> collection.getDeadRecordCount() == 0)) {
            resumedRunFinished.countDown();
          } else if (!released.get()) {
            firstRunFinished.countDown();
          }
        }, 0, 10, TimeUnit.MILLISECONDS);

        assertThat(firstRunFinished.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(freezeId.get()).isNotNegative();
        assertThat(captured)
            .as("each eligible collection may log only its first raced startup rejection")
            .isNotEmpty()
            .hasSizeLessThanOrEqualTo((int) maximumCollectionAttempts)
            .allSatisfy(record -> {
              assertThat(record.getMessage()).contains("before page discovery");
              assertThat(String.valueOf(record.getThrown()))
                  .doesNotContain("Invalid operation depth");
            });

        originalAtomicManager.unfreezeWriteOperations(freezeId.get());
        released.set(true);
        assertThat(resumedRunFinished.await(30, TimeUnit.SECONDS)).isTrue();
        for (var collection : collections) {
          assertThat(collection.getDeadRecordCount())
              .as("scheduled cleanup must finish after release for " + collection.getName())
              .isZero();
        }
      } catch (Throwable failure) {
        testFailure = failure;
      } finally {
        if (future != null) {
          future.cancel(true);
        }
        if (!released.get() && freezeId.get() >= 0) {
          originalAtomicManager.unfreezeWriteOperations(freezeId.get());
          released.set(true);
        }
        if (executor != null) {
          executor.shutdownNow();
          workerTerminated = awaitTerminationPreservingInterrupt(
              executor, 5, TimeUnit.SECONDS);
        }
        if (workerTerminated) {
          try {
            setAtomicOperationsManager(storage, originalAtomicManager);
          } finally {
            logger.removeHandler(handler);
            logger.setLevel(priorLevel);
          }
        } else {
          // An unrelated stuck task must not race shared-state restoration or storage closure.
          resourcesSafeToClose.set(false);
        }
      }

      if (!workerTerminated) {
        var terminationFailure = new AssertionError(
            "scheduled cleanup worker did not terminate after release");
        if (testFailure != null) {
          testFailure.addSuppressed(terminationFailure);
          throw testFailure;
        }
        throw terminationFailure;
      }
      if (testFailure != null) {
        throw testFailure;
      }
      assertLiveVersions(session, "GcFrozen", 12, 3);
    } finally {
      if (resourcesSafeToClose.get()) {
        try {
          if (session != null) {
            session.close();
          }
        } finally {
          manager.close();
        }
      }
    }
  }

  /** An interrupted termination wait retries and restores the caller's interrupt status. */
  @Test
  public void interruptedTerminationWaitDoesNotEscapeWorker() throws Exception {
    var executor = mock(java.util.concurrent.ExecutorService.class);
    var attempts = new AtomicInteger();
    doAnswer(invocation -> {
      if (attempts.incrementAndGet() == 1) {
        throw new InterruptedException("injected termination-wait interruption");
      }
      return true;
    }).when(executor).awaitTermination(anyLong(), any(TimeUnit.class));

    try {
      assertThat(awaitTerminationPreservingInterrupt(executor, 5, TimeUnit.SECONDS)).isTrue();
      assertThat(attempts).hasValue(2);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  /** A startup-retry regression is stopped synchronously by the second injected attempt. */
  @Test
  public void rejectedStartupMakesOnlyOneDeterministicallyGuardedAttempt() throws Exception {
    var fixture = mockCollection();
    var attempts = new AtomicInteger();
    doAnswer(invocation -> {
      if (attempts.incrementAndGet() == 1) {
        throw new ModificationOperationProhibitedException(
            "test-storage", "Modification requests are prohibited");
      }
      throw new AssertionError("collection retried startup after rejection");
    }).when(fixture.atomicManager()).executeInsideAtomicOperation(any(TxConsumer.class));

    assertThat(fixture.collection().collectDeadRecords(
        new java.util.concurrent.ConcurrentSkipListMap<>())).isZero();

    assertThat(attempts).hasValue(1);
  }

  /** A known-page I/O failure advances to the following dirty-page search. */
  @Test
  public void genuinePageFailureContinuesWithFollowingDirtyPage() throws Exception {
    var fixture = mockCollection();
    var operation = mock(AtomicOperation.class);
    var dirtyPages = mock(CollectionDirtyPageBitSet.class);
    setField(fixture.collection(), "dirtyPageBitSet", dirtyPages);
    when(dirtyPages.nextSetBit(0, operation)).thenReturn(3);
    when(dirtyPages.nextSetBit(4, operation)).thenReturn(-1);
    when(operation.loadPageForWrite(0, 3, 1, true))
        .thenThrow(new IOException("simulated page read failure"));
    doAnswer(invocation -> {
      TxConsumer consumer = invocation.getArgument(0);
      consumer.accept(operation);
      return null;
    }).when(fixture.atomicManager()).executeInsideAtomicOperation(any(TxConsumer.class));
    doAnswer(invocation -> {
      TxConsumer consumer = invocation.getArgument(2);
      consumer.accept(operation);
      return null;
    }).when(fixture.atomicManager()).executeInsideComponentOperation(
        any(AtomicOperation.class), any(StorageComponent.class), any(TxConsumer.class));

    assertThat(fixture.collection().collectDeadRecords(
        new java.util.concurrent.ConcurrentSkipListMap<>())).isZero();

    verify(fixture.atomicManager(), times(2))
        .executeInsideAtomicOperation(any(TxConsumer.class));
  }

  /** A rejected frozen attempt changes no durable page state and later cleanup works after reopen. */
  @Test(timeout = 30_000)
  public void rejectedFrozenCleanupPreservesDataAcrossReopen() {
    var path = diskTestPath();
    var manager = createDiskInstance(path, "frozenreopen");
    try (var session = manager.open(
        "frozenreopen", "admin", DbTestBase.ADMIN_PASSWORD)) {
      var storage = storage(session);
      createDeadVersions(session, storage, "GcFrozenReopen");
      var collections = findCollections(session, storage, "GcFrozenReopen");

      session.freeze(true);
      try {
        for (var collection : collections) {
          assertThat(collection.collectDeadRecords(storage.getSharedSnapshotIndex())).isZero();
        }
      } finally {
        session.release();
      }
      assertLiveVersions(session, "GcFrozenReopen", 12, 3);
    }
    manager.close();

    var reopenedManager =
        (YouTrackDBImpl) com.jetbrains.youtrackdb.api.YourTracks.instance(path);
    try (reopenedManager; var session = reopenedManager.open(
        "frozenreopen", "admin", DbTestBase.ADMIN_PASSWORD)) {
      var storage = storage(session);
      var collections = findCollections(session, storage, "GcFrozenReopen");
      var reclaimed = 0L;
      for (var collection : collections) {
        reclaimed += collection.collectDeadRecords(storage.getSharedSnapshotIndex());
      }
      assertThat(reclaimed)
          .as("persisted dirty pages must remain available for cleanup after reopen")
          .isGreaterThan(0);
      assertLiveVersions(session, "GcFrozenReopen", 12, 3);
    }
  }

  private static void createDeadVersions(DatabaseSessionEmbedded session,
      AbstractStorage storage, String className) {
    storage.getContextConfiguration()
        .setValue(GlobalConfiguration.STORAGE_SNAPSHOT_INDEX_CLEANUP_THRESHOLD, 0);
    storage.getContextConfiguration()
        .setValue(GlobalConfiguration.STORAGE_COLLECTION_GC_MIN_THRESHOLD, 0);
    storage.getContextConfiguration()
        .setValue(GlobalConfiguration.STORAGE_COLLECTION_GC_SCALE_FACTOR, 0.0f);
    session.command("CREATE CLASS " + className);
    for (var i = 0; i < 12; i++) {
      session.begin();
      session.command("INSERT INTO " + className + " SET idx = " + i + ", ver = 0");
      session.commit();
    }
    for (var version = 1; version <= 3; version++) {
      session.begin();
      session.command("UPDATE " + className + " SET ver = " + version);
      session.commit();
    }
  }

  private static void assertLiveVersions(DatabaseSessionEmbedded session, String className,
      int expectedCount, int expectedVersion) {
    session.begin();
    var count = 0;
    try (var result = session.query("SELECT FROM " + className)) {
      while (result.hasNext()) {
        assertThat((int) result.next().getProperty("ver")).isEqualTo(expectedVersion);
        count++;
      }
    }
    session.commit();
    assertThat(count).isEqualTo(expectedCount);
  }

  private static List<PaginatedCollectionV2> findCollections(DatabaseSessionEmbedded session,
      AbstractStorage storage, String className) {
    var result = new ArrayList<PaginatedCollectionV2>();
    var collectionIds = session.getClass(className).getCollectionIds();
    for (var collectionId : collectionIds) {
      for (var collection : storage.getCollectionInstances()) {
        if (collection.getId() == collectionId
            && collection instanceof PaginatedCollectionV2 paginatedCollection) {
          result.add(paginatedCollection);
        }
      }
    }
    return result;
  }

  private static MockCollectionFixture mockCollection() {
    var storage = mock(AbstractStorage.class);
    var readCache = mock(ReadCache.class);
    var writeCache = mock(WriteCache.class);
    var atomicManager = mock(AtomicOperationsManager.class);
    when(writeCache.pageSize()).thenReturn(8 * 1024);
    when(storage.getReadCache()).thenReturn(readCache);
    when(storage.getWriteCache()).thenReturn(writeCache);
    when(storage.getAtomicOperationsManager()).thenReturn(atomicManager);
    when(storage.getName()).thenReturn("test-storage");
    when(storage.getComponentsFactory()).thenReturn(
        new CurrentStorageComponentsFactory(BinarySerializerFactory.CURRENT_BINARY_FORMAT_VERSION));
    return new MockCollectionFixture(new PaginatedCollectionV2("test-collection", storage),
        atomicManager);
  }

  private static boolean awaitTerminationPreservingInterrupt(
      java.util.concurrent.ExecutorService executor, long timeout, TimeUnit unit) {
    var deadline = System.nanoTime() + unit.toNanos(timeout);
    var interrupted = false;
    try {
      while (true) {
        try {
          return executor.awaitTermination(
              Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException ignored) {
          interrupted = true;
        }
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static Handler boundedCapturingHandler(List<LogRecord> records, long maximumRecords) {
    var handler = new Handler() {
      @Override
      public void publish(LogRecord record) {
        if (records.size() >= maximumRecords) {
          throw new AssertionError("records GC exceeded its deterministic log bound");
        }
        records.add(record);
      }

      @Override
      public void flush() {
      }

      @Override
      public void close() {
      }
    };
    handler.setLevel(Level.ALL);
    return handler;
  }

  private static void setAtomicOperationsManager(AbstractStorage storage,
      AtomicOperationsManager manager) throws ReflectiveOperationException {
    setField(storage, "atomicOperationsManager", manager);
  }

  private static void setField(Object target, String fieldName, Object value)
      throws ReflectiveOperationException {
    Field field = null;
    for (var type = target.getClass(); type != null && field == null; type = type.getSuperclass()) {
      try {
        field = type.getDeclaredField(fieldName);
      } catch (NoSuchFieldException ignored) {
        // Continue through Mockito-generated subclasses and production superclasses.
      }
    }
    if (field == null) {
      throw new NoSuchFieldException(fieldName);
    }
    field.setAccessible(true);
    field.set(target, value);
  }

  private record MockCollectionFixture(PaginatedCollectionV2 collection,
      AtomicOperationsManager atomicManager) {
  }

  private static AbstractStorage storage(DatabaseSessionEmbedded session) {
    return (AbstractStorage) session.getStorage();
  }

  private static Path diskTestPath() {
    return Path.of(System.getProperty("buildDirectory", "./target"))
        .toAbsolutePath().resolve("frozen-records-gc-" + System.nanoTime());
  }

  private static YouTrackDBImpl createDiskInstance(Path path, String databaseName) {
    FileUtils.deleteRecursively(path.toFile());
    var manager = (YouTrackDBImpl) com.jetbrains.youtrackdb.api.YourTracks.instance(path);
    manager.create(databaseName, DatabaseType.DISK,
        new com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential(
            "admin", DbTestBase.ADMIN_PASSWORD,
            com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole.ADMIN));
    return manager;
  }
}
