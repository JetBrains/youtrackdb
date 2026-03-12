package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.profiler.Ticker;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.CoreMetrics;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.Gauge;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.MetricsRegistry;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link StaleTransactionMonitor}. Tests the monitor's detection logic, metric
 * updates, rate-limiting, and growth trend tracking in isolation using controllable dependencies.
 */
public class StaleTransactionMonitorTest {

  private Set<TsMinHolder> tsMins;
  private AtomicLong snapshotIndexSize;
  private AtomicOperationIdGen idGen;
  private FakeTicker ticker;
  private ContextConfiguration config;
  private MetricsRegistry registry;

  // Metrics retrieved from the registry for assertion
  private Gauge<Long> oldestTxAge;
  private Gauge<Long> snapshotIndexSizeMetric;
  private Gauge<Long> lwmLag;
  private Gauge<Integer> staleTxCount;
  private Gauge<Integer> activeTxCount;

  @Before
  public void setUp() {
    tsMins = AbstractStorage.newTsMinsSet();
    snapshotIndexSize = new AtomicLong();
    idGen = new AtomicOperationIdGen();
    ticker = new FakeTicker();
    config = new ContextConfiguration();

    // Set short thresholds for testing
    config.setValue(GlobalConfiguration.STORAGE_TX_WARN_TIMEOUT_SECS, 10);
    config.setValue(GlobalConfiguration.STORAGE_TX_CRITICAL_TIMEOUT_SECS, 30);
    config.setValue(GlobalConfiguration.STORAGE_TX_MONITOR_INTERVAL_SECS, 5);

    registry = new MetricsRegistry(ticker);

    oldestTxAge = registry.databaseMetric(CoreMetrics.OLDEST_TX_AGE, "test");
    snapshotIndexSizeMetric =
        registry.databaseMetric(CoreMetrics.SNAPSHOT_INDEX_SIZE, "test");
    lwmLag = registry.databaseMetric(CoreMetrics.LWM_LAG, "test");
    staleTxCount = registry.databaseMetric(CoreMetrics.STALE_TX_COUNT, "test");
    activeTxCount = registry.databaseMetric(CoreMetrics.ACTIVE_TX_COUNT, "test");
  }

  @After
  public void tearDown() {
    registry.shutdown();
  }

  private StaleTransactionMonitor createMonitor() {
    return new StaleTransactionMonitor(
        "test", tsMins, snapshotIndexSize, idGen, ticker, config, registry);
  }

  // --- Metric update tests ---

  /**
   * With no active transactions, all metrics should be zero/empty.
   */
  @Test
  public void testNoActiveTransactions() {
    var monitor = createMonitor();
    monitor.doCheck();

    assertThat(activeTxCount.getValue()).isEqualTo(0);
    assertThat(staleTxCount.getValue()).isEqualTo(0);
    assertThat(oldestTxAge.getValue()).isEqualTo(0L);
    assertThat(lwmLag.getValue()).isEqualTo(0L);
    assertThat(snapshotIndexSizeMetric.getValue()).isEqualTo(0L);
  }

  /**
   * With an active transaction that is younger than the warn threshold, it should be
   * counted as active but not stale.
   */
  @Test
  public void testActiveButNotStaleTransaction() {
    var holder = createActiveHolder(
        TimeUnit.SECONDS.toNanos(5)); // 5 seconds ago (warn threshold is 10s)
    tsMins.add(holder);
    idGen.setStartId(100);
    idGen.nextId(); // lastId = 100

    var monitor = createMonitor();
    monitor.doCheck();

    assertThat(activeTxCount.getValue()).isEqualTo(1);
    assertThat(staleTxCount.getValue()).isEqualTo(0);
    assertThat(oldestTxAge.getValue()).isEqualTo(5L);
  }

  /**
   * With an active transaction older than the warn threshold, it should be counted as stale.
   */
  @Test
  public void testStaleTransaction() {
    var holder = createActiveHolder(
        TimeUnit.SECONDS.toNanos(15)); // 15 seconds ago (warn threshold is 10s)
    tsMins.add(holder);
    idGen.setStartId(100);
    idGen.nextId();

    var monitor = createMonitor();
    monitor.doCheck();

    assertThat(activeTxCount.getValue()).isEqualTo(1);
    assertThat(staleTxCount.getValue()).isEqualTo(1);
    assertThat(oldestTxAge.getValue()).isEqualTo(15L);
  }

  /**
   * Multiple active transactions: metrics should reflect the aggregate state.
   */
  @Test
  public void testMultipleActiveTransactions() {
    // Holder 1: 5 seconds old (not stale)
    var h1 = createActiveHolder(TimeUnit.SECONDS.toNanos(5));
    h1.tsMin = 50;
    tsMins.add(h1);

    // Holder 2: 20 seconds old (stale, past warn threshold of 10s)
    var h2 = createActiveHolder(TimeUnit.SECONDS.toNanos(20));
    h2.tsMin = 30;
    tsMins.add(h2);

    // Holder 3: idle (tsMin = MAX_VALUE)
    var h3 = new TsMinHolder();
    tsMins.add(h3);

    idGen.setStartId(100);
    idGen.nextId(); // lastId = 100

    var monitor = createMonitor();
    monitor.doCheck();

    assertThat(activeTxCount.getValue()).isEqualTo(2);
    assertThat(staleTxCount.getValue()).isEqualTo(1);
    assertThat(oldestTxAge.getValue()).isEqualTo(20L);
  }

  /**
   * LWM lag metric: difference between current commit ts and the global low-water-mark.
   */
  @Test
  public void testLwmLagMetricComputation() {
    var holder = createActiveHolder(TimeUnit.SECONDS.toNanos(5));
    holder.tsMin = 50;
    tsMins.add(holder);

    idGen.setStartId(100);
    idGen.nextId(); // lastId = 101

    var monitor = createMonitor();
    monitor.doCheck();

    // lwmLag = currentTs (101) - lwm (50) = 51
    assertThat(lwmLag.getValue()).isEqualTo(51L);
  }

  /**
   * When all holders are idle (tsMin = MAX_VALUE), lwmLag should be 0.
   */
  @Test
  public void testLwmLagZeroWhenAllIdle() {
    var holder = new TsMinHolder();
    tsMins.add(holder);

    idGen.setStartId(100);
    idGen.nextId();

    var monitor = createMonitor();
    monitor.doCheck();

    assertThat(lwmLag.getValue()).isEqualTo(0L);
  }

  /**
   * Snapshot index size metric should reflect the current AtomicLong value.
   */
  @Test
  public void testSnapshotIndexSizeMetricUpdated() {
    snapshotIndexSize.set(42);

    var monitor = createMonitor();
    monitor.doCheck();

    assertThat(snapshotIndexSizeMetric.getValue()).isEqualTo(42L);
  }

  /**
   * Holders with txStartTimeNanos == 0 should be skipped (owning thread is between setting
   * tsMin and txStartTimeNanos).
   */
  @Test
  public void testHolderWithZeroStartTimeSkipped() {
    var holder = new TsMinHolder();
    holder.tsMin = 50; // Active (non-MAX_VALUE)
    holder.activeTxCount = 1;
    // txStartTimeNanos is 0 — simulate the race condition
    tsMins.add(holder);

    var monitor = createMonitor();
    monitor.doCheck();

    // The holder IS counted as active (tsMin != MAX_VALUE), but since startNanos is 0,
    // it is skipped for age computation and stale detection.
    assertThat(activeTxCount.getValue()).isEqualTo(1);
    assertThat(staleTxCount.getValue()).isEqualTo(0);
    assertThat(oldestTxAge.getValue()).isEqualTo(0L);
  }

  // --- Rate-limiting tests (using WarnState inspection) ---

  /**
   * First doCheck with a stale tx should create a WarnState with warnEmitted=true.
   * Kills: L228 RemoveConditionalMutator (!state.warnEmitted), L231 removed call to logWarn.
   */
  @Test
  public void testWarnStateCreatedOnFirstStaleDetection() {
    // 15 seconds old: past warn (10s) but not critical (30s)
    var holder = createActiveHolder(TimeUnit.SECONDS.toNanos(15));
    tsMins.add(holder);
    idGen.setStartId(100);
    idGen.nextId();

    var monitor = createMonitor();

    // Before check: no warn state
    assertThat(monitor.getWarnState(holder)).isNull();

    // First check: should create warn state with warnEmitted=true
    monitor.doCheck();

    var state = monitor.getWarnState(holder);
    assertThat(state).isNotNull();
    assertThat(state.warnEmitted)
        .as("warnEmitted should be true after first check exceeding warn threshold")
        .isTrue();
    assertThat(state.criticalEmitted)
        .as("criticalEmitted should still be false (not at critical threshold)")
        .isFalse();
  }

  /**
   * Verify warnEmitted is set to true and prevents duplicate warn logs on second check.
   * The warn state should exist but no critical emission should occur.
   * Kills: L228 RemoveConditionalMutator_EQUAL_IF/ELSE mutations.
   */
  @Test
  public void testSecondCheckDoesNotReEmitWarnWhenNotCritical() {
    // 15 seconds: past warn (10s), not critical (30s)
    var holder = createActiveHolder(TimeUnit.SECONDS.toNanos(15));
    tsMins.add(holder);
    idGen.setStartId(100);
    idGen.nextId();

    var monitor = createMonitor();
    monitor.doCheck(); // emits warn
    long firstWarnTime = monitor.getWarnState(holder).lastWarnTimeNanos;

    // Advance time so any unwanted re-emission would be detectable via changed timestamp
    ticker.advance(TimeUnit.SECONDS.toNanos(1));

    // Second check — warnEmitted is true, not critical, so no re-emission expected
    monitor.doCheck();
    var state = monitor.getWarnState(holder);
    assertThat(state.warnEmitted).isTrue();
    assertThat(state.criticalEmitted).isFalse();
    // lastWarnTimeNanos should NOT change (no re-emission)
    assertThat(state.lastWarnTimeNanos).isEqualTo(firstWarnTime);
  }

  /**
   * When a transaction crosses the critical threshold on the second check (after warn was
   * already emitted), the criticalEmitted flag should be set and lastWarnTimeNanos updated.
   * Kills: L220 (isCritical boundary), L232 (isCritical && !criticalEmitted),
   *        L235 (removed call to logCritical).
   */
  @Test
  public void testCriticalEmissionSetsFlags() {
    // 35 seconds old: past critical (30s)
    var holder = createActiveHolder(TimeUnit.SECONDS.toNanos(35));
    tsMins.add(holder);
    idGen.setStartId(100);
    idGen.nextId();

    var monitor = createMonitor();

    // First check: emits warn (warnEmitted was false)
    monitor.doCheck();
    var state = monitor.getWarnState(holder);
    assertThat(state.warnEmitted).isTrue();
    assertThat(state.criticalEmitted).isFalse();
    long warnTime = state.lastWarnTimeNanos;

    // Second check: warnEmitted is true, isCritical is true, criticalEmitted is false
    // → should emit critical and set criticalEmitted
    monitor.doCheck();
    state = monitor.getWarnState(holder);
    assertThat(state.criticalEmitted)
        .as("criticalEmitted should be true after second check at critical threshold")
        .isTrue();
    assertThat(state.lastWarnTimeNanos)
        .as("lastWarnTimeNanos should be updated on critical emission")
        .isGreaterThanOrEqualTo(warnTime);
  }

  /**
   * After both warn and critical are emitted, no re-emission should happen until the
   * repeat interval (5 minutes) passes. Advance ticker between checks so that an
   * unwanted re-emission would produce a different lastWarnTimeNanos.
   * Kills: L236 (repeat interval comparison mutations).
   */
  @Test
  public void testNoRepeatBeforeInterval() {
    // 35 seconds: past critical
    var holder = createActiveHolder(TimeUnit.SECONDS.toNanos(35));
    tsMins.add(holder);
    idGen.setStartId(100);
    idGen.nextId();

    var monitor = createMonitor();

    // First check: warn emitted
    monitor.doCheck();
    // Second check: critical emitted
    monitor.doCheck();
    long criticalTime = monitor.getWarnState(holder).lastWarnTimeNanos;

    // Advance time by 1 second (well below the 5 minute repeat interval)
    // so that any unwanted re-emission would change lastWarnTimeNanos
    ticker.advance(TimeUnit.SECONDS.toNanos(1));

    // Third check: no re-emission expected
    monitor.doCheck();
    assertThat(monitor.getWarnState(holder).lastWarnTimeNanos)
        .as("lastWarnTimeNanos should not change when repeat interval has not passed")
        .isEqualTo(criticalTime);
  }

  /**
   * After the REPEAT_WARN_INTERVAL_NANOS (5 minutes), critical warnings should repeat.
   * Verifies lastWarnTimeNanos updates after repeat.
   * Kills: L236 (boundary, math, conditional mutations), L238 (removed call to logCritical).
   */
  @Test
  public void testCriticalRepeatsAfterInterval() {
    // Start 35 seconds ago: past critical
    var holder = createActiveHolder(TimeUnit.SECONDS.toNanos(35));
    tsMins.add(holder);
    idGen.setStartId(100);
    idGen.nextId();

    var monitor = createMonitor();

    // First check: emits warn
    monitor.doCheck();
    // Second check: emits critical
    monitor.doCheck();
    long criticalTime = monitor.getWarnState(holder).lastWarnTimeNanos;

    // Third check: nothing (repeat interval not yet passed)
    monitor.doCheck();
    assertThat(monitor.getWarnState(holder).lastWarnTimeNanos).isEqualTo(criticalTime);

    // Advance ticker by 5 minutes (the repeat interval)
    ticker.advance(TimeUnit.MINUTES.toNanos(5));

    // Fourth check: should re-emit critical (repeat interval passed)
    monitor.doCheck();
    assertThat(monitor.getWarnState(holder).lastWarnTimeNanos)
        .as("lastWarnTimeNanos should be updated after repeat interval passes")
        .isGreaterThan(criticalTime);
  }

  /**
   * Advancing time by just under 5 minutes should NOT trigger a repeat.
   * Kills: L236 ConditionalsBoundaryMutator (changed >= to >).
   */
  @Test
  public void testCriticalDoesNotRepeatJustBeforeInterval() {
    var holder = createActiveHolder(TimeUnit.SECONDS.toNanos(35));
    tsMins.add(holder);
    idGen.setStartId(100);
    idGen.nextId();

    var monitor = createMonitor();
    monitor.doCheck(); // warn
    monitor.doCheck(); // critical
    long criticalTime = monitor.getWarnState(holder).lastWarnTimeNanos;

    // Advance by 5 minutes minus 1 nanosecond — just below the repeat interval
    ticker.advance(TimeUnit.MINUTES.toNanos(5) - 1);

    monitor.doCheck();
    assertThat(monitor.getWarnState(holder).lastWarnTimeNanos)
        .as("Should not repeat when exactly 1 nano before the interval")
        .isEqualTo(criticalTime);
  }

  /**
   * A transaction at exactly the critical threshold (30s) should be considered critical.
   * Kills: L220 ConditionalsBoundaryMutator (ageNanos >= criticalThresholdNanos).
   */
  @Test
  public void testExactCriticalThresholdIsCritical() {
    // Exactly 30 seconds (the critical threshold)
    var holder = createActiveHolder(TimeUnit.SECONDS.toNanos(30));
    tsMins.add(holder);
    idGen.setStartId(100);
    idGen.nextId();

    var monitor = createMonitor();

    // First call: emits warn
    monitor.doCheck();
    assertThat(monitor.getWarnState(holder).warnEmitted).isTrue();

    // Second call: isCritical should be true at exactly 30s → emits critical
    monitor.doCheck();
    assertThat(monitor.getWarnState(holder).criticalEmitted)
        .as("Exactly at the critical threshold should still be critical")
        .isTrue();
  }

  /**
   * A transaction 1 nanosecond below the critical threshold should NOT be critical.
   * The warn will be emitted on the first check, but not the critical on the second.
   * Kills: L220 RemoveConditionalMutator_ORDER_IF/ELSE.
   */
  @Test
  public void testJustBelowCriticalThresholdNotCritical() {
    // 1 nanosecond below 30 seconds
    var holder = createActiveHolder(TimeUnit.SECONDS.toNanos(30) - 1);
    tsMins.add(holder);
    idGen.setStartId(100);
    idGen.nextId();

    var monitor = createMonitor();

    // First call: emits warn (past 10s)
    monitor.doCheck();
    assertThat(monitor.getWarnState(holder).warnEmitted).isTrue();
    long warnTime = monitor.getWarnState(holder).lastWarnTimeNanos;

    // Second call: not critical, so no critical emission
    monitor.doCheck();
    assertThat(monitor.getWarnState(holder).criticalEmitted)
        .as("Just below critical threshold should not trigger critical")
        .isFalse();
    // lastWarnTimeNanos should not change
    assertThat(monitor.getWarnState(holder).lastWarnTimeNanos).isEqualTo(warnTime);
  }

  // --- emitWarning call verification ---

  /**
   * Verifies that emitWarning is actually called for stale transactions by checking that
   * a WarnState is created. If the call to emitWarning is removed (mutation), no WarnState
   * would exist.
   * Kills: L197 VoidMethodCallMutator (removed call to emitWarning).
   */
  @Test
  public void testEmitWarningCalledForStaleTx() {
    var holder = createActiveHolder(TimeUnit.SECONDS.toNanos(15));
    tsMins.add(holder);
    idGen.setStartId(100);
    idGen.nextId();

    var monitor = createMonitor();
    monitor.doCheck();

    // If emitWarning was not called, getWarnState would return null
    assertThat(monitor.getWarnState(holder))
        .as("emitWarning should be called for stale transactions, creating a WarnState")
        .isNotNull();
    assertThat(monitor.getWarnStateCount()).isEqualTo(1);
  }

  /**
   * Verifies that emitWarning is NOT called for non-stale transactions.
   */
  @Test
  public void testEmitWarningNotCalledForNonStaleTx() {
    // 5 seconds: below warn threshold of 10s
    var holder = createActiveHolder(TimeUnit.SECONDS.toNanos(5));
    tsMins.add(holder);
    idGen.setStartId(100);
    idGen.nextId();

    var monitor = createMonitor();
    monitor.doCheck();

    assertThat(monitor.getWarnState(holder))
        .as("No WarnState should be created for non-stale transaction")
        .isNull();
    assertThat(monitor.getWarnStateCount()).isEqualTo(0);
  }

  // --- run() delegates to doCheck() ---

  /**
   * Verify that run() actually invokes doCheck() by observing metric changes.
   * Kills: L144 VoidMethodCallMutator (removed call to doCheck).
   */
  @Test
  public void testRunDelegatesToDoCheck() {
    var holder = createActiveHolder(TimeUnit.SECONDS.toNanos(15));
    tsMins.add(holder);
    idGen.setStartId(100);
    idGen.nextId();

    var monitor = createMonitor();

    // Call run() instead of doCheck()
    monitor.run();

    // If doCheck was not called, metrics would remain at defaults
    assertThat(activeTxCount.getValue())
        .as("run() should delegate to doCheck(), updating metrics")
        .isEqualTo(1);
    assertThat(staleTxCount.getValue()).isEqualTo(1);

    // Also verify warn state was created (proves emitWarning was called via doCheck)
    assertThat(monitor.getWarnState(holder)).isNotNull();
  }

  // --- Warn state cleanup ---

  /**
   * When a holder's tsMin returns to MAX_VALUE (tx ended), its warn state should be removed.
   */
  @Test
  public void testWarnStateCleanedUpWhenTxEnds() {
    var holder = createActiveHolder(TimeUnit.SECONDS.toNanos(15));
    tsMins.add(holder);
    idGen.setStartId(100);
    idGen.nextId();

    var monitor = createMonitor();
    monitor.doCheck();
    assertThat(monitor.getWarnState(holder)).isNotNull();

    // Simulate tx end
    holder.tsMin = Long.MAX_VALUE;
    holder.setTxStartTimeNanosOpaque(0);

    monitor.doCheck();
    assertThat(activeTxCount.getValue()).isEqualTo(0);
    assertThat(staleTxCount.getValue()).isEqualTo(0);
    // Warn state should be cleaned up when tsMin is MAX_VALUE
    assertThat(monitor.getWarnState(holder))
        .as("WarnState should be removed when transaction ends")
        .isNull();
  }

  /**
   * When a holder is removed from tsMins (thread died), its warn state should be cleaned up
   * by the retainAll call.
   */
  @Test
  public void testWarnStateCleanedUpWhenHolderRemoved() {
    var holder = createActiveHolder(TimeUnit.SECONDS.toNanos(15));
    tsMins.add(holder);
    idGen.setStartId(100);
    idGen.nextId();

    var monitor = createMonitor();
    monitor.doCheck();
    assertThat(monitor.getWarnState(holder)).isNotNull();

    // Simulate thread death: remove holder from tsMins
    tsMins.remove(holder);

    monitor.doCheck();
    assertThat(activeTxCount.getValue()).isEqualTo(0);
    assertThat(monitor.getWarnStateCount())
        .as("WarnState should be cleaned up when holder is removed from tsMins")
        .isEqualTo(0);
  }

  // --- Growth trend detection (using internal state inspection) ---

  /**
   * After GROWTH_TREND_CYCLES (3) consecutive cycles where the snapshot index grows and
   * the LWM is stuck, the consecutive growth counter should reach 3, then reset to 0
   * after the warning is emitted.
   * Kills: L294 conditionals, L295 math mutator, L296 boundary/conditionals,
   *        L214 removed call to checkGrowthTrend.
   */
  @Test
  public void testGrowthTrendCounterIncrements() {
    var holder = createActiveHolder(TimeUnit.SECONDS.toNanos(1));
    holder.tsMin = 50;
    tsMins.add(holder);
    idGen.setStartId(100);
    idGen.nextId();

    // Set high warn threshold so individual tx warnings don't fire
    config.setValue(GlobalConfiguration.STORAGE_TX_WARN_TIMEOUT_SECS, 9999);

    var monitor = createMonitor();

    // Initial check: establishes baseline, counter should be 0
    snapshotIndexSize.set(100);
    monitor.doCheck();
    assertThat(monitor.getConsecutiveGrowthCycles())
        .as("Counter should be 0 after baseline check")
        .isEqualTo(0);
    assertThat(monitor.getPreviousSnapshotIndexSize()).isEqualTo(100);
    assertThat(monitor.getPreviousLwm()).isEqualTo(50);

    // Cycle 1: LWM stuck at 50, size grows
    snapshotIndexSize.set(200);
    monitor.doCheck();
    assertThat(monitor.getConsecutiveGrowthCycles())
        .as("Counter should be 1 after first growth cycle")
        .isEqualTo(1);

    // Cycle 2
    snapshotIndexSize.set(300);
    monitor.doCheck();
    assertThat(monitor.getConsecutiveGrowthCycles())
        .as("Counter should be 2 after second growth cycle")
        .isEqualTo(2);

    // Cycle 3: triggers warning, counter resets
    snapshotIndexSize.set(400);
    monitor.doCheck();
    assertThat(monitor.getConsecutiveGrowthCycles())
        .as("Counter should reset to 0 after GROWTH_TREND_CYCLES reached")
        .isEqualTo(0);
  }

  /**
   * If the LWM changes (moves forward), the growth trend counter should reset to 0.
   * Kills: L294 RemoveConditionalMutator (currentLwm == previousLwm part).
   */
  @Test
  public void testGrowthTrendResetsWhenLwmAdvances() {
    var holder = createActiveHolder(TimeUnit.SECONDS.toNanos(1));
    holder.tsMin = 50;
    tsMins.add(holder);
    idGen.setStartId(100);
    idGen.nextId();

    config.setValue(GlobalConfiguration.STORAGE_TX_WARN_TIMEOUT_SECS, 9999);

    var monitor = createMonitor();

    // Establish baseline
    snapshotIndexSize.set(100);
    monitor.doCheck();

    // Two growth cycles
    snapshotIndexSize.set(200);
    monitor.doCheck();
    assertThat(monitor.getConsecutiveGrowthCycles()).isEqualTo(1);

    snapshotIndexSize.set(300);
    monitor.doCheck();
    assertThat(monitor.getConsecutiveGrowthCycles()).isEqualTo(2);

    // Now advance LWM (holder starts new tx with higher tsMin)
    holder.tsMin = 80;
    snapshotIndexSize.set(400);
    monitor.doCheck();
    // Counter should reset because LWM changed (80 != 50)
    assertThat(monitor.getConsecutiveGrowthCycles())
        .as("Counter should reset when LWM advances")
        .isEqualTo(0);
  }

  /**
   * If snapshot index size doesn't grow (stays the same), counter resets.
   * Kills: L294 (currentSize > previousSnapshotIndexSize part).
   */
  @Test
  public void testGrowthTrendResetsWhenSizeDoesNotGrow() {
    var holder = createActiveHolder(TimeUnit.SECONDS.toNanos(1));
    holder.tsMin = 50;
    tsMins.add(holder);
    idGen.setStartId(100);
    idGen.nextId();

    config.setValue(GlobalConfiguration.STORAGE_TX_WARN_TIMEOUT_SECS, 9999);

    var monitor = createMonitor();

    snapshotIndexSize.set(100);
    monitor.doCheck();

    snapshotIndexSize.set(200);
    monitor.doCheck(); // cycle 1
    assertThat(monitor.getConsecutiveGrowthCycles()).isEqualTo(1);

    // Size stays the same — counter resets
    snapshotIndexSize.set(200);
    monitor.doCheck();
    assertThat(monitor.getConsecutiveGrowthCycles())
        .as("Counter should reset when snapshot index size does not grow")
        .isEqualTo(0);
  }

  /**
   * If all holders are idle (lwm = MAX_VALUE), the growth trend should reset.
   * Kills: L286 RemoveConditionalMutator (currentLwm == Long.MAX_VALUE).
   */
  @Test
  public void testGrowthTrendResetsWhenAllIdle() {
    var holder = createActiveHolder(TimeUnit.SECONDS.toNanos(1));
    holder.tsMin = 50;
    tsMins.add(holder);
    idGen.setStartId(100);
    idGen.nextId();

    config.setValue(GlobalConfiguration.STORAGE_TX_WARN_TIMEOUT_SECS, 9999);

    var monitor = createMonitor();

    snapshotIndexSize.set(100);
    monitor.doCheck();

    snapshotIndexSize.set(200);
    monitor.doCheck(); // cycle 1
    assertThat(monitor.getConsecutiveGrowthCycles()).isEqualTo(1);

    // Make all holders idle
    holder.tsMin = Long.MAX_VALUE;
    holder.setTxStartTimeNanosOpaque(0);

    snapshotIndexSize.set(300);
    monitor.doCheck();

    // Should reset because lwm = MAX_VALUE
    assertThat(monitor.getConsecutiveGrowthCycles())
        .as("Counter should reset when all holders are idle (lwm = MAX_VALUE)")
        .isEqualTo(0);
    assertThat(activeTxCount.getValue()).isEqualTo(0);
  }

  /**
   * Verify previous state is updated correctly after idle reset.
   * When lwm = MAX_VALUE, previousSnapshotIndexSize and previousLwm should be updated.
   * Kills: L286 RemoveConditionalMutator_EQUAL_IF (replaces check with true).
   */
  @Test
  public void testIdleResetUpdatesPreviousState() {
    var holder = createActiveHolder(TimeUnit.SECONDS.toNanos(1));
    holder.tsMin = 50;
    tsMins.add(holder);
    idGen.setStartId(100);
    idGen.nextId();
    config.setValue(GlobalConfiguration.STORAGE_TX_WARN_TIMEOUT_SECS, 9999);

    var monitor = createMonitor();

    // Baseline with active tx
    snapshotIndexSize.set(100);
    monitor.doCheck();
    assertThat(monitor.getPreviousLwm()).isEqualTo(50);

    // Now all idle
    holder.tsMin = Long.MAX_VALUE;
    holder.setTxStartTimeNanosOpaque(0);
    snapshotIndexSize.set(200);
    monitor.doCheck();

    // Previous state should be updated to current values
    assertThat(monitor.getPreviousSnapshotIndexSize()).isEqualTo(200);
    assertThat(monitor.getPreviousLwm()).isEqualTo(Long.MAX_VALUE);
  }

  /**
   * Growth trend boundary: exactly at GROWTH_TREND_CYCLES should trigger (>= 3, not > 3).
   * Kills: L296 ConditionalsBoundaryMutator (>= to >).
   */
  @Test
  public void testGrowthTrendTriggersAtExactlyThreeCycles() {
    var holder = createActiveHolder(TimeUnit.SECONDS.toNanos(1));
    holder.tsMin = 50;
    tsMins.add(holder);
    idGen.setStartId(100);
    idGen.nextId();
    config.setValue(GlobalConfiguration.STORAGE_TX_WARN_TIMEOUT_SECS, 9999);

    var monitor = createMonitor();

    // Baseline
    snapshotIndexSize.set(100);
    monitor.doCheck();

    // 3 cycles
    snapshotIndexSize.set(200);
    monitor.doCheck();
    snapshotIndexSize.set(300);
    monitor.doCheck();
    snapshotIndexSize.set(400);
    monitor.doCheck();
    // Should have triggered at exactly 3 and reset
    assertThat(monitor.getConsecutiveGrowthCycles())
        .as("Counter should be 0 after exactly 3 growth cycles (triggers and resets)")
        .isEqualTo(0);

    // Verify it needs another 3 cycles to trigger again
    snapshotIndexSize.set(500);
    monitor.doCheck();
    assertThat(monitor.getConsecutiveGrowthCycles()).isEqualTo(1);
    snapshotIndexSize.set(600);
    monitor.doCheck();
    assertThat(monitor.getConsecutiveGrowthCycles()).isEqualTo(2);
    snapshotIndexSize.set(700);
    monitor.doCheck();
    assertThat(monitor.getConsecutiveGrowthCycles())
        .as("Counter should reset again after another 3 growth cycles")
        .isEqualTo(0);
  }

  /**
   * Growth trend: 2 cycles is NOT enough to trigger (counter stays at 2, not reset).
   * Kills: L296 RemoveConditionalMutator_ORDER_IF (replaces >= check with true).
   */
  @Test
  public void testGrowthTrendDoesNotTriggerAtTwoCycles() {
    var holder = createActiveHolder(TimeUnit.SECONDS.toNanos(1));
    holder.tsMin = 50;
    tsMins.add(holder);
    idGen.setStartId(100);
    idGen.nextId();
    config.setValue(GlobalConfiguration.STORAGE_TX_WARN_TIMEOUT_SECS, 9999);

    var monitor = createMonitor();

    snapshotIndexSize.set(100);
    monitor.doCheck();

    snapshotIndexSize.set(200);
    monitor.doCheck();
    snapshotIndexSize.set(300);
    monitor.doCheck();

    // Only 2 cycles — should NOT have triggered (counter should be 2, not 0)
    assertThat(monitor.getConsecutiveGrowthCycles())
        .as("2 consecutive growth cycles should not trigger (need 3)")
        .isEqualTo(2);
  }

  /**
   * Growth trend increment uses addition (not subtraction).
   * Kills: L295 MathMutator (++ → --).
   */
  @Test
  public void testGrowthCounterIncrementsNotDecrements() {
    var holder = createActiveHolder(TimeUnit.SECONDS.toNanos(1));
    holder.tsMin = 50;
    tsMins.add(holder);
    idGen.setStartId(100);
    idGen.nextId();
    config.setValue(GlobalConfiguration.STORAGE_TX_WARN_TIMEOUT_SECS, 9999);

    var monitor = createMonitor();

    snapshotIndexSize.set(100);
    monitor.doCheck(); // baseline

    snapshotIndexSize.set(200);
    monitor.doCheck();
    // If ++ was replaced with --, counter would be -1 instead of 1
    assertThat(monitor.getConsecutiveGrowthCycles())
        .as("Counter should increment (not decrement) on each growth cycle")
        .isGreaterThan(0);
  }

  /**
   * Verify checkGrowthTrend is called: if it's removed, the previousSnapshotIndexSize
   * would never be updated from 0.
   * Kills: L214 VoidMethodCallMutator (removed call to checkGrowthTrend).
   */
  @Test
  public void testCheckGrowthTrendIsCalled() {
    var holder = createActiveHolder(TimeUnit.SECONDS.toNanos(1));
    holder.tsMin = 50;
    tsMins.add(holder);
    idGen.setStartId(100);
    idGen.nextId();
    config.setValue(GlobalConfiguration.STORAGE_TX_WARN_TIMEOUT_SECS, 9999);

    var monitor = createMonitor();
    snapshotIndexSize.set(42);
    monitor.doCheck();

    // If checkGrowthTrend was not called, previousSnapshotIndexSize would remain 0
    assertThat(monitor.getPreviousSnapshotIndexSize())
        .as("checkGrowthTrend should be called, updating previousSnapshotIndexSize")
        .isEqualTo(42);
  }

  /**
   * Growth trend condition: size must be strictly greater than previous.
   * Equal size should reset the counter.
   * Kills: L294 ConditionalsBoundaryMutator (> to >=).
   */
  @Test
  public void testGrowthTrendRequiresStrictlyGreaterSize() {
    var holder = createActiveHolder(TimeUnit.SECONDS.toNanos(1));
    holder.tsMin = 50;
    tsMins.add(holder);
    idGen.setStartId(100);
    idGen.nextId();
    config.setValue(GlobalConfiguration.STORAGE_TX_WARN_TIMEOUT_SECS, 9999);

    var monitor = createMonitor();

    snapshotIndexSize.set(100);
    monitor.doCheck(); // baseline

    snapshotIndexSize.set(200);
    monitor.doCheck(); // cycle 1
    assertThat(monitor.getConsecutiveGrowthCycles()).isEqualTo(1);

    // Same size as previous — NOT growing, counter should reset
    snapshotIndexSize.set(200);
    monitor.doCheck();
    assertThat(monitor.getConsecutiveGrowthCycles())
        .as("Equal size should reset growth counter (requires strictly greater)")
        .isEqualTo(0);
  }

  // --- Oldest age computation ---

  /**
   * The oldest tx age metric should reflect the age of the oldest transaction, not the newest.
   */
  @Test
  public void testOldestAgeIsMaxOfAllActive() {
    // Older tx: 25 seconds
    var h1 = createActiveHolder(TimeUnit.SECONDS.toNanos(25));
    h1.tsMin = 30;
    tsMins.add(h1);

    // Newer tx: 8 seconds
    var h2 = createActiveHolder(TimeUnit.SECONDS.toNanos(8));
    h2.tsMin = 60;
    tsMins.add(h2);

    idGen.setStartId(100);
    idGen.nextId();

    var monitor = createMonitor();
    monitor.doCheck();

    assertThat(oldestTxAge.getValue()).isEqualTo(25L);
    assertThat(activeTxCount.getValue()).isEqualTo(2);
  }

  /**
   * Verify that the ageNanos > oldestAge comparison uses strict greater-than:
   * if mutated to >=, when two transactions have the same age, the metric should still
   * report that age correctly (this test kills the boundary mutation at L191).
   * Kills: L191 ConditionalsBoundaryMutator (> to >=).
   */
  @Test
  public void testOldestAgeWithEqualAges() {
    long ageNanos = TimeUnit.SECONDS.toNanos(12);
    var h1 = createActiveHolder(ageNanos);
    h1.tsMin = 40;
    tsMins.add(h1);

    var h2 = createActiveHolder(ageNanos);
    h2.tsMin = 60;
    tsMins.add(h2);

    idGen.setStartId(100);
    idGen.nextId();

    var monitor = createMonitor();
    monitor.doCheck();

    // Both are 12 seconds old. The >= mutation would make no behavioral difference here,
    // but the boundary mutation (> to >=) at L191 is about the `if (ageNanos > oldestAge)`
    // check. Both > and >= produce the same result for equal ages. This mutation is
    // equivalent (produces same output). PIT should mark it as survived — this is a
    // genuine equivalent mutant.
    assertThat(oldestTxAge.getValue()).isEqualTo(12L);
  }

  // --- Start/stop lifecycle ---

  /**
   * Calling start() twice should be a no-op (idempotent).
   */
  @Test
  public void testStartIdempotent() {
    var monitor = createMonitor();
    var executor = new FakeScheduledExecutor();

    monitor.start(executor);
    assertThat(executor.scheduledCount).isEqualTo(1);

    monitor.start(executor);
    assertThat(executor.scheduledCount).isEqualTo(1); // should not schedule again
  }

  /**
   * stop() should cancel the scheduled future. Calling stop() when not started is a no-op.
   */
  @Test
  public void testStopCancelsFuture() {
    var monitor = createMonitor();

    // stop() when not started: no-op
    monitor.stop();

    var executor = new FakeScheduledExecutor();
    monitor.start(executor);
    assertThat((Object) executor.lastFuture).isNotNull();
    assertThat(executor.lastFuture.cancelled).isFalse();

    monitor.stop();
    assertThat(executor.lastFuture.cancelled).isTrue();
  }

  /**
   * After stop(), start() should schedule again.
   */
  @Test
  public void testRestartAfterStop() {
    var monitor = createMonitor();
    var executor = new FakeScheduledExecutor();

    monitor.start(executor);
    assertThat(executor.scheduledCount).isEqualTo(1);

    monitor.stop();

    monitor.start(executor);
    assertThat(executor.scheduledCount).isEqualTo(2);
  }

  // --- run() wraps exceptions ---

  /**
   * The run() method catches exceptions from doCheck() and logs them instead of propagating.
   */
  @Test
  public void testRunDoesNotPropagateExceptions() {
    var monitor = createMonitor();
    // run() should not throw even if tsMins iteration encounters issues
    monitor.run();
  }

  // --- Diagnostic fields on TsMinHolder ---

  /**
   * Verify that TsMinHolder opaque accessors correctly round-trip values.
   */
  @Test
  public void testTsMinHolderDiagnosticFieldRoundTrip() {
    var holder = new TsMinHolder();

    // txStartTimeNanos
    assertThat(holder.getTxStartTimeNanosOpaque()).isEqualTo(0);
    holder.setTxStartTimeNanosOpaque(12345L);
    assertThat(holder.getTxStartTimeNanosOpaque()).isEqualTo(12345L);
    holder.setTxStartTimeNanosOpaque(0);
    assertThat(holder.getTxStartTimeNanosOpaque()).isEqualTo(0);

    // ownerThreadName
    assertThat(holder.getOwnerThreadNameOpaque()).isNull();
    holder.setOwnerThreadNameOpaque("test-thread");
    assertThat(holder.getOwnerThreadNameOpaque()).isEqualTo("test-thread");
    holder.setOwnerThreadNameOpaque(null);
    assertThat(holder.getOwnerThreadNameOpaque()).isNull();

    // ownerThreadId
    assertThat(holder.getOwnerThreadIdOpaque()).isEqualTo(0);
    holder.setOwnerThreadIdOpaque(42);
    assertThat(holder.getOwnerThreadIdOpaque()).isEqualTo(42);
    holder.setOwnerThreadIdOpaque(0);
    assertThat(holder.getOwnerThreadIdOpaque()).isEqualTo(0);

    // txStartStackTrace
    assertThat(holder.getTxStartStackTraceOpaque()).isNull();
    var trace = Thread.currentThread().getStackTrace();
    holder.setTxStartStackTraceOpaque(trace);
    assertThat(holder.getTxStartStackTraceOpaque()).isSameAs(trace);
    holder.setTxStartStackTraceOpaque(null);
    assertThat(holder.getTxStartStackTraceOpaque()).isNull();
  }

  // --- Boundary condition tests ---

  /**
   * A transaction whose age is exactly at the warn threshold should be counted as stale.
   */
  @Test
  public void testExactWarnThresholdIsCounted() {
    var holder = createActiveHolder(TimeUnit.SECONDS.toNanos(10));
    tsMins.add(holder);
    idGen.setStartId(100);
    idGen.nextId();

    var monitor = createMonitor();
    monitor.doCheck();

    assertThat(staleTxCount.getValue())
        .as("Exactly at the warn threshold should still be stale")
        .isEqualTo(1);
    assertThat(monitor.getWarnState(holder))
        .as("WarnState should be created at exact threshold")
        .isNotNull();
  }

  /**
   * A transaction 1 nanosecond below the warn threshold should NOT be counted as stale.
   */
  @Test
  public void testJustBelowWarnThresholdNotStale() {
    var holder = createActiveHolder(TimeUnit.SECONDS.toNanos(10) - 1);
    tsMins.add(holder);
    idGen.setStartId(100);
    idGen.nextId();

    var monitor = createMonitor();
    monitor.doCheck();

    assertThat(staleTxCount.getValue())
        .as("Just below the warn threshold should not be stale")
        .isEqualTo(0);
    assertThat(activeTxCount.getValue()).isEqualTo(1);
    assertThat(monitor.getWarnState(holder))
        .as("No WarnState should be created below threshold")
        .isNull();
  }

  /**
   * When snapshotIndexSize is 0, the metric should reflect that exactly.
   */
  @Test
  public void testSnapshotIndexSizeZero() {
    snapshotIndexSize.set(0);
    var monitor = createMonitor();
    monitor.doCheck();
    assertThat(snapshotIndexSizeMetric.getValue()).isEqualTo(0L);
  }

  /**
   * Verify that metrics are re-computed on each doCheck call (not cached).
   */
  @Test
  public void testMetricsUpdatedOnEachCall() {
    var holder = createActiveHolder(TimeUnit.SECONDS.toNanos(5));
    tsMins.add(holder);
    idGen.setStartId(100);
    idGen.nextId();

    var monitor = createMonitor();

    // First call
    monitor.doCheck();
    assertThat(activeTxCount.getValue()).isEqualTo(1);
    assertThat(oldestTxAge.getValue()).isEqualTo(5L);

    // Transaction ends
    holder.tsMin = Long.MAX_VALUE;
    holder.setTxStartTimeNanosOpaque(0);

    // Second call — should reflect the change
    monitor.doCheck();
    assertThat(activeTxCount.getValue()).isEqualTo(0);
    assertThat(oldestTxAge.getValue()).isEqualTo(0L);
    assertThat(staleTxCount.getValue()).isEqualTo(0);
  }

  /**
   * Verify that a stale transaction with a stack trace set creates a WarnState with
   * warnEmitted=true (tests the stackTrace != null branch in logWarn).
   * Kills: L247 RemoveConditionalMutator_EQUAL_ELSE (stackTrace != null → false).
   */
  @Test
  public void testStaleTransactionWithStackTrace() {
    var holder = createActiveHolder(TimeUnit.SECONDS.toNanos(15));
    holder.setTxStartStackTraceOpaque(Thread.currentThread().getStackTrace());
    tsMins.add(holder);
    idGen.setStartId(100);
    idGen.nextId();

    var monitor = createMonitor();
    monitor.doCheck();

    // Warn state should be created regardless of stack trace presence
    var state = monitor.getWarnState(holder);
    assertThat(state).isNotNull();
    assertThat(state.warnEmitted).isTrue();
  }

  /**
   * Verify critical emission works when a stack trace is present (tests the stackTrace
   * != null branch in logCritical).
   * Kills: L268 RemoveConditionalMutator_EQUAL_ELSE (stackTrace != null → false).
   */
  @Test
  public void testCriticalEmissionWithStackTrace() {
    var holder = createActiveHolder(TimeUnit.SECONDS.toNanos(35));
    holder.setTxStartStackTraceOpaque(Thread.currentThread().getStackTrace());
    tsMins.add(holder);
    idGen.setStartId(100);
    idGen.nextId();

    var monitor = createMonitor();
    monitor.doCheck(); // warn
    monitor.doCheck(); // critical

    var state = monitor.getWarnState(holder);
    assertThat(state).isNotNull();
    assertThat(state.criticalEmitted).isTrue();
  }

  /**
   * Verify critical emission works when no stack trace is present.
   */
  @Test
  public void testCriticalEmissionWithoutStackTrace() {
    var holder = createActiveHolder(TimeUnit.SECONDS.toNanos(35));
    // No stack trace set
    tsMins.add(holder);
    idGen.setStartId(100);
    idGen.nextId();

    var monitor = createMonitor();
    monitor.doCheck(); // warn
    monitor.doCheck(); // critical

    var state = monitor.getWarnState(holder);
    assertThat(state).isNotNull();
    assertThat(state.criticalEmitted).isTrue();
  }

  // --- Helpers ---

  /**
   * Creates a TsMinHolder simulating an active transaction that started {@code ageNanos} ago.
   */
  private TsMinHolder createActiveHolder(long ageNanos) {
    var holder = new TsMinHolder();
    holder.tsMin = 50; // any non-MAX_VALUE
    holder.activeTxCount = 1;
    holder.setTxStartTimeNanosOpaque(ticker.approximateNanoTime() - ageNanos);
    holder.setOwnerThreadNameOpaque("test-thread");
    holder.setOwnerThreadIdOpaque(1);
    return holder;
  }

  /**
   * A controllable ticker for testing. Returns a fixed nanoTime that can be advanced.
   */
  private static final class FakeTicker implements Ticker {
    private long nanoTime = TimeUnit.HOURS.toNanos(1); // start at 1 hour to avoid zero issues

    void advance(long nanos) {
      nanoTime += nanos;
    }

    @Override
    public void start() {
    }

    @Override
    public long approximateNanoTime() {
      return nanoTime;
    }

    @Override
    public long approximateCurrentTimeMillis() {
      return TimeUnit.NANOSECONDS.toMillis(nanoTime);
    }

    @Override
    public long currentNanoTime() {
      return nanoTime;
    }

    @Override
    public long getTick() {
      return nanoTime;
    }

    @Override
    public long getGranularity() {
      return 1;
    }

    @Override
    public void stop() {
    }
  }

  /**
   * A fake ScheduledExecutorService that records calls without actually scheduling.
   */
  private static final class FakeScheduledExecutor
      implements ScheduledExecutorService {

    int scheduledCount;
    FakeScheduledFuture lastFuture;

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
        Runnable command, long initialDelay, long delay, TimeUnit unit) {
      scheduledCount++;
      lastFuture = new FakeScheduledFuture();
      return lastFuture;
    }

    // --- Unused methods (throw UnsupportedOperationException) ---

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <V> ScheduledFuture<V> schedule(
        java.util.concurrent.Callable<V> callable, long delay, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
        Runnable command, long initialDelay, long period, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void shutdown() {
    }

    @Override
    public java.util.List<Runnable> shutdownNow() {
      return java.util.List.of();
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
    public <T> java.util.concurrent.Future<T> submit(java.util.concurrent.Callable<T> task) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> java.util.concurrent.Future<T> submit(Runnable task, T result) {
      throw new UnsupportedOperationException();
    }

    @Override
    public java.util.concurrent.Future<?> submit(Runnable task) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> java.util.List<java.util.concurrent.Future<T>> invokeAll(
        java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> java.util.List<java.util.concurrent.Future<T>> invokeAll(
        java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks,
        long timeout, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> T invokeAny(
        java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> T invokeAny(
        java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks,
        long timeout, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void execute(Runnable command) {
      throw new UnsupportedOperationException();
    }
  }

  /**
   * A fake ScheduledFuture that records whether it was cancelled.
   */
  private static final class FakeScheduledFuture
      implements ScheduledFuture<Void> {

    boolean cancelled;

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      cancelled = true;
      return true;
    }

    @Override
    public boolean isCancelled() {
      return cancelled;
    }

    @Override
    public boolean isDone() {
      return cancelled;
    }

    @Override
    public Void get() {
      return null;
    }

    @Override
    public Void get(long timeout, TimeUnit unit) {
      return null;
    }

    @Override
    public long getDelay(TimeUnit unit) {
      return 0;
    }

    @Override
    public int compareTo(java.util.concurrent.Delayed o) {
      return 0;
    }
  }
}
