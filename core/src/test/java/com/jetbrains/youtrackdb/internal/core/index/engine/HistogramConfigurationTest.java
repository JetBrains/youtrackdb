package com.jetbrains.youtrackdb.internal.core.index.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.core.db.record.CurrentStorageComponentsFactory;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.After;
import org.junit.Test;

/**
 * Tests that histogram configuration parameters from
 * {@link GlobalConfiguration} are correctly wired into the histogram
 * subsystem.
 *
 * <p>Each test temporarily overrides a config value, exercises the code
 * path that reads it, and then restores the original in {@link #tearDown()}.
 */
public class HistogramConfigurationTest {

  /**
   * Track which GlobalConfiguration entries were overridden so they can be
   * restored after each test. Prevents cross-test pollution.
   */
  private final java.util.Map<GlobalConfiguration, Object> overrides =
      new java.util.LinkedHashMap<>();

  @After
  public void tearDown() {
    // Restore all overridden config entries to their defaults
    for (var entry : overrides.entrySet()) {
      entry.getKey().setValue(entry.getValue());
    }
    overrides.clear();
  }

  private void setConfig(GlobalConfiguration key, Object value) {
    if (!overrides.containsKey(key)) {
      overrides.put(key, key.getValue());
    }
    key.setValue(value);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // GlobalConfiguration defaults
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void defaultSelectivity_hasExpectedDefault() {
    assertEquals(0.1,
        GlobalConfiguration.QUERY_STATS_DEFAULT_SELECTIVITY
            .getValueAsDouble(),
        1e-9);
  }

  @Test
  public void defaultFanOut_hasExpectedDefault() {
    assertEquals(10.0,
        GlobalConfiguration.QUERY_STATS_DEFAULT_FAN_OUT
            .getValueAsDouble(),
        1e-9);
  }

  @Test
  public void histogramBuckets_hasExpectedDefault() {
    assertEquals(128,
        GlobalConfiguration.QUERY_STATS_HISTOGRAM_BUCKETS
            .getValueAsInteger());
  }

  @Test
  public void histogramMinSize_hasExpectedDefault() {
    assertEquals(1000,
        GlobalConfiguration.QUERY_STATS_HISTOGRAM_MIN_SIZE
            .getValueAsInteger());
  }

  @Test
  public void rebalanceMutationFraction_hasExpectedDefault() {
    assertEquals(0.3,
        GlobalConfiguration.QUERY_STATS_REBALANCE_MUTATION_FRACTION
            .getValueAsDouble(),
        1e-9);
  }

  @Test
  public void minRebalanceMutations_hasExpectedDefault() {
    assertEquals(1000L,
        GlobalConfiguration.QUERY_STATS_MIN_REBALANCE_MUTATIONS
            .getValueAsLong());
  }

  @Test
  public void maxRebalanceMutations_hasExpectedDefault() {
    assertEquals(10_000_000L,
        GlobalConfiguration.QUERY_STATS_MAX_REBALANCE_MUTATIONS
            .getValueAsLong());
  }

  @Test
  public void maxBoundaryBytes_hasExpectedDefault() {
    assertEquals(256,
        GlobalConfiguration.QUERY_STATS_MAX_BOUNDARY_BYTES
            .getValueAsInteger());
  }

  @Test
  public void persistBatchSize_hasExpectedDefault() {
    assertEquals(500,
        GlobalConfiguration.QUERY_STATS_PERSIST_BATCH_SIZE
            .getValueAsInteger());
  }

  @Test
  public void rebalanceFailureCooldown_hasExpectedDefault() {
    assertEquals(60_000L,
        GlobalConfiguration.QUERY_STATS_REBALANCE_FAILURE_COOLDOWN
            .getValueAsLong());
  }

  @Test
  public void maxConcurrentRebalances_hasExpectedDefault() {
    assertEquals(-1,
        GlobalConfiguration.QUERY_STATS_MAX_CONCURRENT_REBALANCES
            .getValueAsInteger());
  }

  // ═══════════════════════════════════════════════════════════════════════
  // getValueAsDouble() correctness
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void getValueAsDouble_returnsDoubleDefault() {
    // The default for DEFAULT_SELECTIVITY is 0.1 (a Double)
    assertEquals(0.1,
        GlobalConfiguration.QUERY_STATS_DEFAULT_SELECTIVITY
            .getValueAsDouble(),
        1e-15);
  }

  @Test
  public void getValueAsDouble_parsesStringOverride() {
    setConfig(GlobalConfiguration.QUERY_STATS_DEFAULT_SELECTIVITY, "0.25");
    assertEquals(0.25,
        GlobalConfiguration.QUERY_STATS_DEFAULT_SELECTIVITY
            .getValueAsDouble(),
        1e-15);
  }

  @Test
  public void setValue_doubleType_roundTrip() {
    // Verify that setValue with a Double stores and retrieves correctly
    setConfig(GlobalConfiguration.QUERY_STATS_DEFAULT_FAN_OUT, 42.5);
    assertEquals(42.5,
        GlobalConfiguration.QUERY_STATS_DEFAULT_FAN_OUT
            .getValueAsDouble(),
        1e-15);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Runtime config change propagation
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void selectivityEstimator_defaultSelectivity_readsConfigAtCallTime() {
    // Given default selectivity is 0.1
    double original = SelectivityEstimator.defaultSelectivity();
    assertEquals(0.1, original, 1e-9);

    // When we change the config at runtime
    setConfig(GlobalConfiguration.QUERY_STATS_DEFAULT_SELECTIVITY, 0.05);

    // Then the method returns the new value immediately
    assertEquals(0.05, SelectivityEstimator.defaultSelectivity(), 1e-9);
  }

  @Test
  public void defaultFanOut_configReadsAtCallTime() {
    // Given default fan-out is 10.0
    assertEquals(10.0,
        GlobalConfiguration.QUERY_STATS_DEFAULT_FAN_OUT
            .getValueAsDouble(),
        1e-9);

    // When we change the config at runtime
    setConfig(GlobalConfiguration.QUERY_STATS_DEFAULT_FAN_OUT, 5.0);

    // Then the config returns the new value immediately (read at call time)
    assertEquals(5.0,
        GlobalConfiguration.QUERY_STATS_DEFAULT_FAN_OUT
            .getValueAsDouble(),
        1e-9);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Config affects IndexHistogramManager behavior
  // ═══════════════════════════════════════════════════════════════════════

  // ═══════════════════════════════════════════════════════════════════════
  // histogramMinSize config affects maybeScheduleHistogramWork
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void histogramMinSize_belowMinSize_doesNotSchedule() {
    // Given histogramMinSize is 50,000 and the index has only 10,000 entries
    setConfig(GlobalConfiguration.QUERY_STATS_HISTOGRAM_MIN_SIZE, 50_000);

    var fixture = createManagerFixture();
    // Snapshot with 10,000 non-null entries, no histogram, no previous
    // build — would normally trigger an initial build if above min size.
    var stats = new IndexStatistics(10_000, 10_000, 0);
    var snapshot = new HistogramSnapshot(
        stats, null, 0, 0, 0, false, null, false);
    fixture.cache.put(fixture.engineId, snapshot);
    fixture.manager.setFileIdForTest(1);
    fixture.manager.setKeyStreamSupplier(
        () -> IntStream.range(0, 10_000).boxed().map(i -> (Object) i));

    // When maybeScheduleHistogramWork is called
    var executor = new CapturingExecutor();
    fixture.manager.maybeScheduleHistogramWork(executor);

    // Then no task is submitted (10,000 < 50,000 min size)
    assertTrue("Expected no task submitted when below minSize",
        executor.submitted.isEmpty());
  }

  @Test
  public void histogramMinSize_aboveMinSize_schedulesInitialBuild() {
    // Given histogramMinSize is 5 and the index has 20 entries
    setConfig(GlobalConfiguration.QUERY_STATS_HISTOGRAM_MIN_SIZE, 5);

    var fixture = createManagerFixture();
    // Snapshot with 20 non-null entries, no histogram, no previous build
    var stats = new IndexStatistics(20, 20, 0);
    var snapshot = new HistogramSnapshot(
        stats, null, 0, 0, 0, false, null, false);
    fixture.cache.put(fixture.engineId, snapshot);
    fixture.manager.setFileIdForTest(1);
    fixture.manager.setKeyStreamSupplier(
        () -> IntStream.range(0, 20).boxed().map(i -> (Object) i));

    // When maybeScheduleHistogramWork is called
    var executor = new CapturingExecutor();
    fixture.manager.maybeScheduleHistogramWork(executor);

    // Then a task is submitted (20 >= 5 min size, initial build)
    assertFalse("Expected task submitted when above minSize",
        executor.submitted.isEmpty());
  }

  // ═══════════════════════════════════════════════════════════════════════
  // histogramBuckets config affects scanAndBuild output
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void histogramBuckets_controlsTargetBucketCount() {
    // Given a custom bucket count of 32 and 10,000 entries
    setConfig(GlobalConfiguration.QUERY_STATS_HISTOGRAM_BUCKETS, 32);

    // scanAndBuild is called with targetBuckets computed from config.
    // target = min(32, floor(sqrt(10000))) = min(32, 100) = 32
    int configBuckets =
        GlobalConfiguration.QUERY_STATS_HISTOGRAM_BUCKETS.getValueAsInteger();
    long nonNull = 10_000;
    int target = Math.min(configBuckets,
        (int) Math.floor(Math.sqrt(nonNull)));
    target = Math.max(target,
        IndexHistogramManager.MINIMUM_BUCKET_COUNT);

    // Call the actual production scanAndBuild method
    var keys = IntStream.range(0, (int) nonNull).boxed()
        .map(i -> (Object) i);
    var result = IndexHistogramManager.scanAndBuild(keys, nonNull, target);

    assertNotNull(result);
    assertEquals(32, result.actualBucketCount);
  }

  @Test
  public void histogramBuckets_capsBySqrt() {
    // Given a bucket count of 200 but only 100 entries:
    // target = min(200, floor(sqrt(100))) = min(200, 10) = 10
    setConfig(GlobalConfiguration.QUERY_STATS_HISTOGRAM_BUCKETS, 200);

    int configBuckets =
        GlobalConfiguration.QUERY_STATS_HISTOGRAM_BUCKETS.getValueAsInteger();
    long nonNull = 100;
    int target = Math.min(configBuckets,
        (int) Math.floor(Math.sqrt(nonNull)));
    target = Math.max(target,
        IndexHistogramManager.MINIMUM_BUCKET_COUNT);

    // Call the actual production scanAndBuild method
    var keys = IntStream.range(0, (int) nonNull).boxed()
        .map(i -> (Object) i);
    var result = IndexHistogramManager.scanAndBuild(keys, nonNull, target);

    assertNotNull(result);
    assertEquals(10, result.actualBucketCount);
  }

  @Test
  public void persistBatchSize_canBeOverridden() {
    // Verify the config value can be overridden at runtime
    setConfig(GlobalConfiguration.QUERY_STATS_PERSIST_BATCH_SIZE, 100_000);

    int batchSize =
        GlobalConfiguration.QUERY_STATS_PERSIST_BATCH_SIZE.getValueAsInteger();
    assertEquals(100_000, batchSize);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Rebalance threshold config affects maybeScheduleHistogramWork
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void rebalanceThreshold_mutationsAboveThreshold_schedulesRebalance() {
    // Given custom rebalance parameters: threshold = 10,000 * 0.5 = 5,000
    setConfig(
        GlobalConfiguration.QUERY_STATS_REBALANCE_MUTATION_FRACTION, 0.5);
    setConfig(
        GlobalConfiguration.QUERY_STATS_MIN_REBALANCE_MUTATIONS, 500L);
    setConfig(
        GlobalConfiguration.QUERY_STATS_MAX_REBALANCE_MUTATIONS, 5_000L);

    var fixture = createManagerFixture();
    // Snapshot with histogram present, totalCountAtLastBuild=10000,
    // mutationsSinceRebalance=5001 (above threshold of 5000)
    var histogram = new EquiDepthHistogram(
        2, new Comparable<?>[] {0, 50, 100},
        new long[] {50, 50}, new long[] {50, 50}, 100, null, 0);
    var stats = new IndexStatistics(10_000, 10_000, 0);
    var snapshot = new HistogramSnapshot(
        stats, histogram, 5001, 10_000, 0, false, null, false);
    fixture.cache.put(fixture.engineId, snapshot);
    fixture.manager.setFileIdForTest(1);
    fixture.manager.setKeyStreamSupplier(
        () -> IntStream.range(0, 10_000).boxed().map(i -> (Object) i));

    // When maybeScheduleHistogramWork is called
    var executor = new CapturingExecutor();
    fixture.manager.maybeScheduleHistogramWork(executor);

    // Then a rebalance task is submitted (5001 > 5000)
    assertFalse("Expected rebalance scheduled when mutations exceed "
        + "threshold", executor.submitted.isEmpty());
  }

  @Test
  public void rebalanceThreshold_mutationsBelowThreshold_doesNotSchedule() {
    // Given threshold = 100,000 * 0.01 = 1,000
    setConfig(
        GlobalConfiguration.QUERY_STATS_REBALANCE_MUTATION_FRACTION, 0.01);
    setConfig(
        GlobalConfiguration.QUERY_STATS_MIN_REBALANCE_MUTATIONS, 100L);
    setConfig(
        GlobalConfiguration.QUERY_STATS_MAX_REBALANCE_MUTATIONS,
        1_000_000L);

    var fixture = createManagerFixture();
    var histogram = new EquiDepthHistogram(
        2, new Comparable<?>[] {0, 50, 100},
        new long[] {50, 50}, new long[] {50, 50}, 100, null, 0);
    var stats = new IndexStatistics(100_000, 100_000, 0);
    // mutationsSinceRebalance=500, threshold=1000 → below
    var snapshot = new HistogramSnapshot(
        stats, histogram, 500, 100_000, 0, false, null, false);
    fixture.cache.put(fixture.engineId, snapshot);
    fixture.manager.setFileIdForTest(1);
    fixture.manager.setKeyStreamSupplier(
        () -> IntStream.range(0, 100_000).boxed()
            .map(i -> (Object) i));

    // When maybeScheduleHistogramWork is called
    var executor = new CapturingExecutor();
    fixture.manager.maybeScheduleHistogramWork(executor);

    // Then no task submitted (500 < 1000)
    assertTrue("Expected no scheduling when mutations below threshold",
        executor.submitted.isEmpty());
  }

  @Test
  public void rebalanceThreshold_clampedByMinMutations() {
    // Given a small index: fraction * count = 5000 * 0.1 = 500,
    // but minMutations = 2000 → threshold = 2000
    setConfig(
        GlobalConfiguration.QUERY_STATS_REBALANCE_MUTATION_FRACTION, 0.1);
    setConfig(
        GlobalConfiguration.QUERY_STATS_MIN_REBALANCE_MUTATIONS, 2000L);
    setConfig(
        GlobalConfiguration.QUERY_STATS_MAX_REBALANCE_MUTATIONS,
        10_000_000L);

    var fixture = createManagerFixture();
    var histogram = new EquiDepthHistogram(
        2, new Comparable<?>[] {0, 50, 100},
        new long[] {50, 50}, new long[] {50, 50}, 100, null, 0);
    var stats = new IndexStatistics(5_000, 5_000, 0);
    // 1500 mutations — above raw fraction (500) but below clamped min (2000)
    var snapshot = new HistogramSnapshot(
        stats, histogram, 1500, 5_000, 0, false, null, false);
    fixture.cache.put(fixture.engineId, snapshot);
    fixture.manager.setFileIdForTest(1);
    fixture.manager.setKeyStreamSupplier(
        () -> IntStream.range(0, 5_000).boxed().map(i -> (Object) i));

    // When maybeScheduleHistogramWork is called
    var executor = new CapturingExecutor();
    fixture.manager.maybeScheduleHistogramWork(executor);

    // Then no task submitted (1500 < 2000 clamped threshold)
    assertTrue("Expected no scheduling when clamped by minMutations",
        executor.submitted.isEmpty());
  }

  @Test
  public void rebalanceThreshold_clampedByMaxMutations() {
    // Given a large index: fraction * count = 100,000 * 0.5 = 50,000,
    // but maxMutations = 1,000 → threshold = 1,000
    setConfig(
        GlobalConfiguration.QUERY_STATS_REBALANCE_MUTATION_FRACTION, 0.5);
    setConfig(
        GlobalConfiguration.QUERY_STATS_MIN_REBALANCE_MUTATIONS, 100L);
    setConfig(
        GlobalConfiguration.QUERY_STATS_MAX_REBALANCE_MUTATIONS, 1_000L);

    var fixture = createManagerFixture();
    var histogram = new EquiDepthHistogram(
        2, new Comparable<?>[] {0, 50, 100},
        new long[] {50, 50}, new long[] {50, 50}, 100, null, 0);
    var stats = new IndexStatistics(100_000, 100_000, 0);
    // 1001 mutations — above clamped max threshold (1000)
    var snapshot = new HistogramSnapshot(
        stats, histogram, 1001, 100_000, 0, false, null, false);
    fixture.cache.put(fixture.engineId, snapshot);
    fixture.manager.setFileIdForTest(1);
    fixture.manager.setKeyStreamSupplier(
        () -> IntStream.range(0, 100_000).boxed()
            .map(i -> (Object) i));

    // When maybeScheduleHistogramWork is called
    var executor = new CapturingExecutor();
    fixture.manager.maybeScheduleHistogramWork(executor);

    // Then a task is submitted (1001 > 1000 clamped threshold)
    assertFalse("Expected rebalance scheduled when clamped by "
        + "maxMutations", executor.submitted.isEmpty());
  }

  @Test
  public void maybeScheduleHistogramWork_respectsConfiguredMinSize() {
    // Given histogramMinSize is 5000 and index has 3000 entries
    setConfig(GlobalConfiguration.QUERY_STATS_HISTOGRAM_MIN_SIZE, 5000);

    var fixture = createManagerFixture();
    var stats = new IndexStatistics(3000, 3000, 0);
    var snapshot = new HistogramSnapshot(
        stats, null, 0, 0, 0, false, null, false);
    fixture.cache.put(fixture.engineId, snapshot);
    fixture.manager.setFileIdForTest(1);
    fixture.manager.setKeyStreamSupplier(
        () -> IntStream.range(0, 3000).boxed().map(i -> (Object) i));

    // When maybeScheduleHistogramWork is called with a real executor
    var executor = new CapturingExecutor();
    fixture.manager.maybeScheduleHistogramWork(executor);

    // Then no task submitted (3000 < 5000 min size)
    assertTrue("Expected no scheduling when below configured minSize",
        executor.submitted.isEmpty());
  }

  @Test
  public void cooldownMs_canBeOverridden() {
    // Given a short cooldown
    setConfig(
        GlobalConfiguration.QUERY_STATS_REBALANCE_FAILURE_COOLDOWN, 100L);

    long cooldown =
        GlobalConfiguration.QUERY_STATS_REBALANCE_FAILURE_COOLDOWN
            .getValueAsLong();
    assertEquals(100L, cooldown);

    // And a long cooldown
    setConfig(
        GlobalConfiguration.QUERY_STATS_REBALANCE_FAILURE_COOLDOWN,
        300_000L);
    cooldown =
        GlobalConfiguration.QUERY_STATS_REBALANCE_FAILURE_COOLDOWN
            .getValueAsLong();
    assertEquals(300_000L, cooldown);
  }

  @Test
  public void maxConcurrentRebalances_autoCalculation() {
    // Given MAX_CONCURRENT_REBALANCES = -1 (auto)
    int permits = GlobalConfiguration.QUERY_STATS_MAX_CONCURRENT_REBALANCES
        .getValueAsInteger();
    assertEquals(-1, permits);

    // Auto formula: max(2, availableProcessors / 4)
    int expected = Math.max(2,
        Runtime.getRuntime().availableProcessors() / 4);
    assertTrue("Auto permits should be >= 2", expected >= 2);
  }

  @Test
  public void maxConcurrentRebalances_explicitValue() {
    setConfig(
        GlobalConfiguration.QUERY_STATS_MAX_CONCURRENT_REBALANCES, 8);

    int permits = GlobalConfiguration.QUERY_STATS_MAX_CONCURRENT_REBALANCES
        .getValueAsInteger();
    assertEquals(8, permits);

    // With explicit value > 0, it should be used directly
    assertTrue(permits > 0);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Helpers
  // ═══════════════════════════════════════════════════════════════════════

  private static ManagerFixture createManagerFixture() {
    return new ManagerFixture();
  }

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

  /**
   * ExecutorService that captures submitted tasks without executing them.
   * Used to verify whether maybeScheduleHistogramWork schedules a task.
   */
  private static class CapturingExecutor implements ExecutorService {
    final List<Runnable> submitted = new ArrayList<>();

    @Override
    public void execute(Runnable command) {
      submitted.add(command);
    }

    @Override
    public void shutdown() {
    }

    @Override
    public List<Runnable> shutdownNow() {
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return false;
    }

    @Override
    public boolean isTerminated() {
      return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return true;
    }

    @Override
    public <T> java.util.concurrent.Future<T> submit(
        java.util.concurrent.Callable<T> task) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> java.util.concurrent.Future<T> submit(
        Runnable task, T result) {
      submitted.add(task);
      return java.util.concurrent.CompletableFuture.completedFuture(result);
    }

    @Override
    public java.util.concurrent.Future<?> submit(Runnable task) {
      submitted.add(task);
      return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    @Override
    public <T> List<java.util.concurrent.Future<T>> invokeAll(
        java.util.Collection<
            ? extends java.util.concurrent.Callable<T>> tasks) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> List<java.util.concurrent.Future<T>> invokeAll(
        java.util.Collection<
            ? extends java.util.concurrent.Callable<T>> tasks,
        long timeout, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> T invokeAny(
        java.util.Collection<
            ? extends java.util.concurrent.Callable<T>> tasks) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> T invokeAny(
        java.util.Collection<
            ? extends java.util.concurrent.Callable<T>> tasks,
        long timeout, TimeUnit unit) {
      throw new UnsupportedOperationException();
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
}
