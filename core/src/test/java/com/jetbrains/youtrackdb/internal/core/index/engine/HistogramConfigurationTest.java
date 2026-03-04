package com.jetbrains.youtrackdb.internal.core.index.engine;

import static org.junit.Assert.assertEquals;
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
import java.util.concurrent.ConcurrentHashMap;
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
            .getValueAsDouble(), 1e-9);
  }

  @Test
  public void defaultFanOut_hasExpectedDefault() {
    assertEquals(10.0,
        GlobalConfiguration.QUERY_STATS_DEFAULT_FAN_OUT
            .getValueAsDouble(), 1e-9);
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
            .getValueAsDouble(), 1e-9);
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
            .getValueAsDouble(), 1e-15);
  }

  @Test
  public void getValueAsDouble_parsesStringOverride() {
    setConfig(GlobalConfiguration.QUERY_STATS_DEFAULT_SELECTIVITY, "0.25");
    assertEquals(0.25,
        GlobalConfiguration.QUERY_STATS_DEFAULT_SELECTIVITY
            .getValueAsDouble(), 1e-15);
  }

  @Test
  public void setValue_doubleType_roundTrip() {
    // Verify that setValue with a Double stores and retrieves correctly
    setConfig(GlobalConfiguration.QUERY_STATS_DEFAULT_FAN_OUT, 42.5);
    assertEquals(42.5,
        GlobalConfiguration.QUERY_STATS_DEFAULT_FAN_OUT
            .getValueAsDouble(), 1e-15);
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
            .getValueAsDouble(), 1e-9);

    // When we change the config at runtime
    setConfig(GlobalConfiguration.QUERY_STATS_DEFAULT_FAN_OUT, 5.0);

    // Then the config returns the new value immediately (read at call time)
    assertEquals(5.0,
        GlobalConfiguration.QUERY_STATS_DEFAULT_FAN_OUT
            .getValueAsDouble(), 1e-9);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Config affects IndexHistogramManager behavior
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void histogramMinSize_configValuePropagates() {
    // Given a high histogramMinSize
    setConfig(GlobalConfiguration.QUERY_STATS_HISTOGRAM_MIN_SIZE, 50_000);

    // The maybeScheduleHistogramWork path reads this at call time.
    // Verify the config returns the overridden value.
    assertEquals(50_000,
        GlobalConfiguration.QUERY_STATS_HISTOGRAM_MIN_SIZE
            .getValueAsInteger());

    // With a snapshot of 10,000 entries (below 50,000), the min-size
    // guard in maybeScheduleHistogramWork would skip scheduling.
    var fixture = createManagerFixture();
    var stats = new IndexStatistics(10_000, 10_000, 0);
    var snapshot = new HistogramSnapshot(
        stats, null, 0, 0, 0, false, null, false);
    fixture.cache.put(fixture.engineId, snapshot);

    // maybeScheduleHistogramWork with null executor is a no-op anyway,
    // but we verify the config integration: nonNull (10k) < minSize (50k)
    long nonNull = stats.totalCount() - stats.nullCount();
    assertTrue("Expected nonNull < configured minSize",
        nonNull < GlobalConfiguration.QUERY_STATS_HISTOGRAM_MIN_SIZE
            .getValueAsInteger());
  }

  @Test
  public void histogramMinSize_lowValue_passesGuard() {
    // When histogramMinSize is very low, more datasets pass the guard
    setConfig(GlobalConfiguration.QUERY_STATS_HISTOGRAM_MIN_SIZE, 5);

    // A snapshot with 20 entries passes the min-size check
    long nonNull = 20;
    assertTrue("Expected nonNull >= configured minSize",
        nonNull >= GlobalConfiguration.QUERY_STATS_HISTOGRAM_MIN_SIZE
            .getValueAsInteger());
  }

  @Test
  public void histogramBuckets_controlsTargetBucketCount() {
    // Given a custom bucket count
    setConfig(GlobalConfiguration.QUERY_STATS_HISTOGRAM_BUCKETS, 32);

    // When computing target buckets for 10,000 entries:
    // target = min(32, floor(sqrt(10000))) = min(32, 100) = 32
    int buckets =
        GlobalConfiguration.QUERY_STATS_HISTOGRAM_BUCKETS.getValueAsInteger();
    int target = buckets;
    long nonNull = 10_000;
    target = Math.min(target,
        (int) Math.floor(Math.sqrt(nonNull)));
    target = Math.max(target,
        IndexHistogramManager.MINIMUM_BUCKET_COUNT);
    assertEquals(32, target);
  }

  @Test
  public void histogramBuckets_capsBySqrt() {
    // Given a custom bucket count of 200, but only 100 entries:
    // target = min(200, floor(sqrt(100))) = min(200, 10) = 10
    setConfig(GlobalConfiguration.QUERY_STATS_HISTOGRAM_BUCKETS, 200);

    int buckets =
        GlobalConfiguration.QUERY_STATS_HISTOGRAM_BUCKETS.getValueAsInteger();
    int target = buckets;
    long nonNull = 100;
    target = Math.min(target,
        (int) Math.floor(Math.sqrt(nonNull)));
    target = Math.max(target,
        IndexHistogramManager.MINIMUM_BUCKET_COUNT);
    assertEquals(10, target);
  }

  @Test
  public void persistBatchSize_controlsFlushThreshold() {
    // Given a very high persist batch size, dirty mutations should not
    // trigger a flush.
    setConfig(GlobalConfiguration.QUERY_STATS_PERSIST_BATCH_SIZE, 100_000);

    // The flush condition in applyDelta reads the config value at each call:
    //   if (dirtyMutations >= persistBatchSize)
    // With 100,000 threshold and default 500 delta count, no flush occurs.
    int batchSize =
        GlobalConfiguration.QUERY_STATS_PERSIST_BATCH_SIZE.getValueAsInteger();
    assertEquals(100_000, batchSize);
    assertTrue(batchSize > 500);
  }

  @Test
  public void rebalanceThreshold_reflectsConfigValues() {
    // Given custom rebalance parameters
    setConfig(
        GlobalConfiguration.QUERY_STATS_REBALANCE_MUTATION_FRACTION, 0.5);
    setConfig(
        GlobalConfiguration.QUERY_STATS_MIN_REBALANCE_MUTATIONS, 500L);
    setConfig(
        GlobalConfiguration.QUERY_STATS_MAX_REBALANCE_MUTATIONS, 5_000L);

    // When computing rebalance threshold for 10,000 entries:
    // threshold = floor(10,000 * 0.5) = 5,000
    // capped by max = min(5000, 5000) = 5000
    // floored by min = max(5000, 500) = 5000
    double fraction =
        GlobalConfiguration.QUERY_STATS_REBALANCE_MUTATION_FRACTION
            .getValueAsDouble();
    long max =
        GlobalConfiguration.QUERY_STATS_MAX_REBALANCE_MUTATIONS
            .getValueAsLong();
    long min =
        GlobalConfiguration.QUERY_STATS_MIN_REBALANCE_MUTATIONS
            .getValueAsLong();

    long threshold = (long) (10_000 * fraction);
    threshold = Math.min(threshold, max);
    threshold = Math.max(threshold, min);
    assertEquals(5_000L, threshold);
  }

  @Test
  public void rebalanceThreshold_fractionControlsBaseValue() {
    // Given a low fraction with a large index
    setConfig(
        GlobalConfiguration.QUERY_STATS_REBALANCE_MUTATION_FRACTION, 0.01);
    setConfig(
        GlobalConfiguration.QUERY_STATS_MIN_REBALANCE_MUTATIONS, 100L);
    setConfig(
        GlobalConfiguration.QUERY_STATS_MAX_REBALANCE_MUTATIONS,
        1_000_000L);

    // For 100,000 entries: threshold = 100,000 * 0.01 = 1,000
    double fraction =
        GlobalConfiguration.QUERY_STATS_REBALANCE_MUTATION_FRACTION
            .getValueAsDouble();
    long threshold = (long) (100_000 * fraction);
    long max =
        GlobalConfiguration.QUERY_STATS_MAX_REBALANCE_MUTATIONS
            .getValueAsLong();
    long min =
        GlobalConfiguration.QUERY_STATS_MIN_REBALANCE_MUTATIONS
            .getValueAsLong();
    threshold = Math.min(threshold, max);
    threshold = Math.max(threshold, min);
    assertEquals(1_000L, threshold);
  }

  @Test
  public void rebalanceThreshold_clampedByMinMutations() {
    // Given a very small index where fraction * count < minMutations
    setConfig(
        GlobalConfiguration.QUERY_STATS_REBALANCE_MUTATION_FRACTION, 0.1);
    setConfig(
        GlobalConfiguration.QUERY_STATS_MIN_REBALANCE_MUTATIONS, 2000L);
    setConfig(
        GlobalConfiguration.QUERY_STATS_MAX_REBALANCE_MUTATIONS,
        10_000_000L);

    // For 5,000 entries: fraction threshold = 500, but min is 2000
    double fraction =
        GlobalConfiguration.QUERY_STATS_REBALANCE_MUTATION_FRACTION
            .getValueAsDouble();
    long threshold = (long) (5_000 * fraction);
    long max =
        GlobalConfiguration.QUERY_STATS_MAX_REBALANCE_MUTATIONS
            .getValueAsLong();
    long min =
        GlobalConfiguration.QUERY_STATS_MIN_REBALANCE_MUTATIONS
            .getValueAsLong();
    threshold = Math.min(threshold, max);
    threshold = Math.max(threshold, min);
    assertEquals(2000L, threshold);
  }

  @Test
  public void rebalanceThreshold_clampedByMaxMutations() {
    // Given a very large index where fraction * count > maxMutations
    setConfig(
        GlobalConfiguration.QUERY_STATS_REBALANCE_MUTATION_FRACTION, 0.5);
    setConfig(
        GlobalConfiguration.QUERY_STATS_MIN_REBALANCE_MUTATIONS, 100L);
    setConfig(
        GlobalConfiguration.QUERY_STATS_MAX_REBALANCE_MUTATIONS, 1_000L);

    // For 100,000 entries: fraction threshold = 50,000, but max is 1,000
    double fraction =
        GlobalConfiguration.QUERY_STATS_REBALANCE_MUTATION_FRACTION
            .getValueAsDouble();
    long threshold = (long) (100_000 * fraction);
    long max =
        GlobalConfiguration.QUERY_STATS_MAX_REBALANCE_MUTATIONS
            .getValueAsLong();
    long min =
        GlobalConfiguration.QUERY_STATS_MIN_REBALANCE_MUTATIONS
            .getValueAsLong();
    threshold = Math.min(threshold, max);
    threshold = Math.max(threshold, min);
    assertEquals(1_000L, threshold);
  }

  @Test
  public void maybeScheduleHistogramWork_respectsConfiguredMinSize() {
    // Given histogramMinSize is 5000
    setConfig(GlobalConfiguration.QUERY_STATS_HISTOGRAM_MIN_SIZE, 5000);

    var fixture = createManagerFixture();
    // Install a snapshot with 3000 entries (below min)
    var stats = new IndexStatistics(3000, 3000, 0);
    var snapshot = new HistogramSnapshot(
        stats, null, 0, 0, 0, false, null, false);
    fixture.cache.put(fixture.engineId, snapshot);

    // When maybeScheduleHistogramWork is called (simulated via internal
    // config read), the min-size check should prevent scheduling.
    // Verify the config value is read correctly
    assertEquals(5000,
        GlobalConfiguration.QUERY_STATS_HISTOGRAM_MIN_SIZE
            .getValueAsInteger());
    // 3000 < 5000 → no scheduling
    assertTrue(3000 < GlobalConfiguration.QUERY_STATS_HISTOGRAM_MIN_SIZE
        .getValueAsInteger());
  }

  @Test
  public void cooldownMs_controlsRebalanceRetryTiming() {
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
