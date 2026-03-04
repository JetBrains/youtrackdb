package com.jetbrains.youtrackdb.internal.core.index.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.core.db.record.CurrentStorageComponentsFactory;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.Test;

/**
 * Tests for the rebalance trigger and IO executor plumbing (Step 10).
 *
 * <p>Covers:
 * <ul>
 *   <li>{@code getHistogram()} calling {@code maybeScheduleHistogramWork()}</li>
 *   <li>{@code setIoExecutor()} with proactive rebalance check</li>
 *   <li>Null executor safety (no scheduling when executor is absent)</li>
 *   <li>Rebalance scheduling when mutation threshold is exceeded</li>
 * </ul>
 */
public class RebalanceTriggerTest {

  // ═══════════════════════════════════════════════════════════════════════
  // getHistogram() returns cached value
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void getHistogram_returnsNullWhenCacheIsEmpty() {
    // Given a manager with an empty cache and no executor
    var fixture = createManagerFixture();

    // When getHistogram() is called
    var result = fixture.manager.getHistogram();

    // Then null is returned (no snapshot in cache)
    assertNull(result);
  }

  @Test
  public void getHistogram_returnsHistogramFromCache() {
    // Given a manager with a histogram in the cache
    var fixture = createManagerFixture();
    var histogram = createTestHistogram();
    var stats = new IndexStatistics(1000, 1000, 0);
    var snapshot = new HistogramSnapshot(
        stats, histogram, 0, 1000, 0, false, null, false);
    fixture.cache.put(fixture.engineId, snapshot);

    // When getHistogram() is called
    var result = fixture.manager.getHistogram();

    // Then the cached histogram is returned
    assertSame(histogram, result);
  }

  @Test
  public void getHistogram_returnsNullWhenSnapshotHasNoHistogram() {
    // Given a manager with a snapshot that has no histogram (uniform tier)
    var fixture = createManagerFixture();
    var stats = new IndexStatistics(100, 100, 0);
    var snapshot = new HistogramSnapshot(
        stats, null, 0, 100, 0, false, null, false);
    fixture.cache.put(fixture.engineId, snapshot);

    // When getHistogram() is called
    var result = fixture.manager.getHistogram();

    // Then null is returned
    assertNull(result);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // getHistogram() triggers rebalance when threshold exceeded
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void getHistogram_schedulesRebalanceWhenMutationsExceedThreshold()
      throws Exception {
    // Given a manager with a histogram, a key stream supplier, fileId set,
    // and mutations exceeding the rebalance threshold
    var fixture = createRebalanceCapableFixture(2000, 10000);

    var executor = Executors.newSingleThreadExecutor();
    fixture.manager.setIoExecutor(executor);
    try {
      // When getHistogram() is called (rebalance already triggered by
      // setIoExecutor's proactive check)
      fixture.manager.getHistogram();

      // Then a rebalance task completes on the executor
      executor.shutdown();
      assertTrue("Executor should terminate after rebalance",
          executor.awaitTermination(10, TimeUnit.SECONDS));

      // After rebalance, mutationsSinceRebalance should be reset to 0
      var updatedSnapshot = fixture.cache.get(fixture.engineId);
      assertNotNull(updatedSnapshot);
      assertEquals(0, updatedSnapshot.mutationsSinceRebalance());
    } finally {
      executor.shutdownNow();
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  // getHistogram() does not schedule rebalance when no executor
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void getHistogram_doesNotScheduleRebalanceWhenNoExecutor() {
    // Given a manager with mutations exceeding threshold but no executor set
    var fixture = createManagerFixture();
    var histogram = createTestHistogram();
    var stats = new IndexStatistics(2000, 2000, 0);
    var snapshot = new HistogramSnapshot(
        stats, histogram, 10000, 2000, 0, false, null, false);
    fixture.cache.put(fixture.engineId, snapshot);

    // When getHistogram() is called (no executor)
    var result = fixture.manager.getHistogram();

    // Then the histogram is still returned without scheduling rebalance
    assertSame(histogram, result);
    // And the snapshot is unchanged (no rebalance ran)
    var unchangedSnapshot = fixture.cache.get(fixture.engineId);
    assertEquals(10000, unchangedSnapshot.mutationsSinceRebalance());
  }

  // ═══════════════════════════════════════════════════════════════════════
  // getHistogram() does not schedule rebalance below threshold
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void getHistogram_doesNotScheduleRebalanceWhenBelowThreshold() {
    // Given a manager with mutations below the threshold
    var fixture = createManagerFixture();
    var histogram = createTestHistogram();
    var stats = new IndexStatistics(2000, 2000, 0);
    // totalCountAtLastBuild=2000, mutationsSinceRebalance=100
    // threshold = max(min(2000*0.3=600, 10M), 1000) = 1000
    // 100 < 1000 → no rebalance
    var snapshot = new HistogramSnapshot(
        stats, histogram, 100, 2000, 0, false, null, false);
    fixture.cache.put(fixture.engineId, snapshot);

    var executor = Executors.newSingleThreadExecutor();
    fixture.manager.setIoExecutor(executor);
    try {
      // When getHistogram() is called
      fixture.manager.getHistogram();

      // Then no rebalance task is submitted — shutdown returns immediately
      executor.shutdown();
      assertTrue("Executor should be idle (no tasks submitted)",
          executor.awaitTermination(1, TimeUnit.SECONDS));

      // Snapshot is unchanged
      var unchangedSnapshot = fixture.cache.get(fixture.engineId);
      assertEquals(100, unchangedSnapshot.mutationsSinceRebalance());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } finally {
      executor.shutdownNow();
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  // setIoExecutor() triggers proactive rebalance check
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void setIoExecutor_triggersProactiveRebalanceWhenThresholdExceeded()
      throws Exception {
    // Given a manager with a snapshot whose mutationsSinceRebalance
    // exceeds the threshold (simulating accumulated mutations before crash)
    var fixture = createRebalanceCapableFixture(5000, 5000);

    // When setIoExecutor() is called with a non-null executor
    var executor = Executors.newSingleThreadExecutor();
    try {
      fixture.manager.setIoExecutor(executor);

      // Then a rebalance is scheduled proactively. Wait for it.
      executor.shutdown();
      assertTrue("Proactive rebalance should complete",
          executor.awaitTermination(10, TimeUnit.SECONDS));

      // After rebalance, mutationsSinceRebalance is reset
      var updatedSnapshot = fixture.cache.get(fixture.engineId);
      assertNotNull(updatedSnapshot);
      assertEquals(0, updatedSnapshot.mutationsSinceRebalance());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  public void setIoExecutor_doesNotScheduleRebalanceWhenBelowThreshold() {
    // Given a manager with mutations below threshold
    var fixture = createManagerFixture();
    var stats = new IndexStatistics(2000, 2000, 0);
    var snapshot = new HistogramSnapshot(
        stats, createTestHistogram(), 100, 2000, 0, false, null, false);
    fixture.cache.put(fixture.engineId, snapshot);

    var executor = Executors.newSingleThreadExecutor();
    try {
      // When setIoExecutor() is called
      fixture.manager.setIoExecutor(executor);

      // Then no rebalance is submitted
      executor.shutdown();
      assertTrue("No task should be submitted",
          executor.awaitTermination(1, TimeUnit.SECONDS));

      // Snapshot unchanged
      assertEquals(100,
          fixture.cache.get(fixture.engineId).mutationsSinceRebalance());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  public void setIoExecutor_withNull_doesNotThrow() {
    // Given a manager with any state
    var fixture = createManagerFixture();
    var stats = new IndexStatistics(5000, 5000, 0);
    var snapshot = new HistogramSnapshot(
        stats, createTestHistogram(), 5000, 5000, 0, false, null, false);
    fixture.cache.put(fixture.engineId, snapshot);

    // When setIoExecutor(null) is called
    fixture.manager.setIoExecutor(null);

    // Then no exception and no rebalance scheduled
    assertEquals(5000,
        fixture.cache.get(fixture.engineId).mutationsSinceRebalance());
  }

  // ═══════════════════════════════════════════════════════════════════════
  // setIoExecutor() triggers initial build (Uniform → Histogram)
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void setIoExecutor_triggersInitialBuildWhenNoHistogramExists()
      throws Exception {
    // Given a manager with enough entries but no histogram and
    // totalCountAtLastBuild == 0 (never built)
    var fixture = createManagerFixture();
    var stats = new IndexStatistics(2000, 2000, 0);
    var snapshot = new HistogramSnapshot(
        stats, null, 0, 0, 0, false, null, false);
    fixture.cache.put(fixture.engineId, snapshot);

    fixture.manager.setKeyStreamSupplier(
        () -> IntStream.range(0, 2000).mapToObj(i -> (Object) i).sorted());
    setFileId(fixture.manager, 42);

    // When setIoExecutor() is called
    var executor = Executors.newSingleThreadExecutor();
    try {
      fixture.manager.setIoExecutor(executor);

      executor.shutdown();
      assertTrue("Initial build should complete",
          executor.awaitTermination(10, TimeUnit.SECONDS));

      // Then a histogram is built
      var updatedSnapshot = fixture.cache.get(fixture.engineId);
      assertNotNull(updatedSnapshot);
      assertNotNull("Histogram should be built",
          updatedSnapshot.histogram());
    } finally {
      executor.shutdownNow();
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  // setIoExecutor() does not build when below HISTOGRAM_MIN_SIZE
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void setIoExecutor_doesNotBuildWhenBelowMinSize() {
    // Given a manager with totalCount below HISTOGRAM_MIN_SIZE (1000)
    var fixture = createManagerFixture();
    var stats = new IndexStatistics(500, 500, 0);
    var snapshot = new HistogramSnapshot(
        stats, null, 0, 0, 0, false, null, false);
    fixture.cache.put(fixture.engineId, snapshot);

    var executor = Executors.newSingleThreadExecutor();
    try {
      // When setIoExecutor() is called
      fixture.manager.setIoExecutor(executor);

      executor.shutdown();
      assertTrue("No build should be submitted",
          executor.awaitTermination(1, TimeUnit.SECONDS));

      // Then no histogram is built
      assertNull(fixture.cache.get(fixture.engineId).histogram());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } finally {
      executor.shutdownNow();
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Drift-biased trigger: halved threshold when hasDriftedBuckets is set
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void getHistogram_schedulesRebalanceSoonerWhenDrifted()
      throws Exception {
    // Given a manager with hasDriftedBuckets=true and mutations that exceed
    // the halved threshold but not the normal threshold
    var fixture = createManagerFixture();
    var histogram = createTestHistogram();
    var stats = new IndexStatistics(5000, 5000, 0);
    // totalCountAtLastBuild=5000, normal threshold=max(min(1500,10M),1000)=1500
    // Halved threshold = 750. mutationsSinceRebalance=1000.
    // 1000 > 750 (halved) → rebalance fires
    // 1000 < 1500 (normal) → would NOT fire without drift
    var snapshot = new HistogramSnapshot(
        stats, histogram, 1000, 5000, 0, true, null, false);
    fixture.cache.put(fixture.engineId, snapshot);

    fixture.manager.setKeyStreamSupplier(
        () -> IntStream.range(0, 5000).mapToObj(i -> (Object) i).sorted());
    setFileId(fixture.manager, 42);

    var executor = Executors.newSingleThreadExecutor();
    fixture.manager.setIoExecutor(executor);
    try {
      // When getHistogram() is called
      fixture.manager.getHistogram();

      executor.shutdown();
      assertTrue("Drift-biased rebalance should complete",
          executor.awaitTermination(10, TimeUnit.SECONDS));

      // Then rebalance ran — mutationsSinceRebalance reset
      var updatedSnapshot = fixture.cache.get(fixture.engineId);
      assertNotNull(updatedSnapshot);
      assertEquals(0, updatedSnapshot.mutationsSinceRebalance());
    } finally {
      executor.shutdownNow();
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  // getHistogram() does not schedule rebalance when cache is empty
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void getHistogram_withExecutor_doesNotScheduleWhenCacheEmpty() {
    // Given a manager with an executor set but empty cache
    var fixture = createManagerFixture();
    var executor = Executors.newSingleThreadExecutor();
    fixture.manager.setIoExecutor(executor);
    try {
      // When getHistogram() is called
      var result = fixture.manager.getHistogram();

      // Then null is returned and no rebalance is scheduled
      assertNull(result);

      executor.shutdown();
      assertTrue("No task should be submitted",
          executor.awaitTermination(1, TimeUnit.SECONDS));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } finally {
      executor.shutdownNow();
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  // RejectedExecutionException: executor shut down during scheduling
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void getHistogram_handlesShutdownExecutorGracefully() {
    // Given a manager with mutations exceeding the threshold and an
    // executor that has been shut down (simulating database closing)
    var fixture = createManagerFixture();
    var histogram = createTestHistogram();
    var stats = new IndexStatistics(2000, 2000, 0);
    var snapshot = new HistogramSnapshot(
        stats, histogram, 10000, 2000, 0, false, null, false);
    fixture.cache.put(fixture.engineId, snapshot);

    var executor = Executors.newSingleThreadExecutor();
    executor.shutdown();  // shut down before setting on manager
    fixture.manager.setIoExecutor(executor);

    // When getHistogram() is called (executor is shut down, submit()
    // would throw RejectedExecutionException)
    var result = fixture.manager.getHistogram();

    // Then no exception propagates — the histogram is returned normally
    assertSame(histogram, result);
    // And the snapshot is unchanged (rebalance was not scheduled)
    assertEquals(10000,
        fixture.cache.get(fixture.engineId).mutationsSinceRebalance());
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Cooldown: rebalance skipped when in failure cooldown
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void getHistogram_skipsRebalanceWhenInCooldown() {
    // Given a manager with mutations exceeding the threshold but the
    // lastRebalanceFailureTime set to now (simulating a recent failure)
    var fixture = createManagerFixture();
    var histogram = createTestHistogram();
    var stats = new IndexStatistics(2000, 2000, 0);
    var snapshot = new HistogramSnapshot(
        stats, histogram, 10000, 2000, 0, false, null, false);
    fixture.cache.put(fixture.engineId, snapshot);

    // Set lastRebalanceFailureTime to now via reflection
    setLastRebalanceFailureTime(fixture.manager, System.currentTimeMillis());

    var executor = Executors.newSingleThreadExecutor();
    fixture.manager.setIoExecutor(executor);
    try {
      // When getHistogram() is called
      fixture.manager.getHistogram();

      // Then no rebalance is scheduled (cooldown active)
      executor.shutdown();
      assertTrue("No rebalance should run during cooldown",
          executor.awaitTermination(1, TimeUnit.SECONDS));

      // Snapshot unchanged
      assertEquals(10000,
          fixture.cache.get(fixture.engineId).mutationsSinceRebalance());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } finally {
      executor.shutdownNow();
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Fixtures and helpers
  // ═══════════════════════════════════════════════════════════════════════

  private static ManagerFixture createManagerFixture() {
    return new ManagerFixture();
  }

  /**
   * Creates a fixture with all the wiring needed for a complete rebalance:
   * key stream supplier, fileId set, and a snapshot with the given count
   * and mutation level.
   */
  private static ManagerFixture createRebalanceCapableFixture(
      long totalCount, long mutationsSinceRebalance) {
    var fixture = createManagerFixture();
    var histogram = createTestHistogram();
    var stats = new IndexStatistics(totalCount, totalCount, 0);
    var snapshot = new HistogramSnapshot(
        stats, histogram, mutationsSinceRebalance, totalCount,
        0, false, null, false);
    fixture.cache.put(fixture.engineId, snapshot);

    fixture.manager.setKeyStreamSupplier(
        () -> IntStream.range(0, (int) totalCount)
            .mapToObj(i -> (Object) i)
            .sorted());
    setFileId(fixture.manager, 42);

    return fixture;
  }

  private static EquiDepthHistogram createTestHistogram() {
    return new EquiDepthHistogram(
        4,
        new Comparable<?>[]{0, 25, 50, 75, 100},
        new long[]{250, 250, 250, 250},
        new long[]{25, 25, 25, 25},
        1000,
        null,
        0
    );
  }

  /**
   * Creates a real IndexHistogramManager with a mock storage.
   */
  private static class ManagerFixture {
    final int engineId = 0;
    final ConcurrentHashMap<Integer, HistogramSnapshot> cache =
        new ConcurrentHashMap<>();
    final IndexHistogramManager manager;

    ManagerFixture() {
      var storage = createMockStorage();
      var serializerFactory = BinarySerializerFactory.create(
          BinarySerializerFactory.CURRENT_BINARY_FORMAT_VERSION);
      manager = new IndexHistogramManager(
          storage, "test-idx", engineId, true, cache,
          IntegerSerializer.INSTANCE, serializerFactory,
          IntegerSerializer.ID);
    }
  }

  private static AbstractStorage createMockStorage() {
    var storage = mock(AbstractStorage.class);
    var factory = new CurrentStorageComponentsFactory(
        BinarySerializerFactory.currentBinaryFormatVersion());
    when(storage.getComponentsFactory()).thenReturn(factory);
    when(storage.getAtomicOperationsManager())
        .thenReturn(mock(AtomicOperationsManager.class));
    when(storage.getReadCache()).thenReturn(mock(ReadCache.class));
    when(storage.getWriteCache()).thenReturn(mock(WriteCache.class));
    return storage;
  }

  private static void setFileId(
      IndexHistogramManager manager, long value) {
    try {
      var field =
          IndexHistogramManager.class.getDeclaredField("fileId");
      field.setAccessible(true);
      field.setLong(manager, value);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }

  private static void setLastRebalanceFailureTime(
      IndexHistogramManager manager, long value) {
    try {
      var field = IndexHistogramManager.class
          .getDeclaredField("lastRebalanceFailureTime");
      field.setAccessible(true);
      field.setLong(manager, value);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }
}
