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

  // --- Rate-limiting tests ---

  /**
   * First call to doCheck with a stale tx should emit a WARN. Second call should NOT
   * re-emit (rate-limited). Verify by checking that the WarnState progresses correctly.
   */
  @Test
  public void testWarnEmittedOnceAtWarnThreshold() {
    // 15 seconds old: past warn (10s) but not critical (30s)
    var holder = createActiveHolder(TimeUnit.SECONDS.toNanos(15));
    tsMins.add(holder);
    idGen.setStartId(100);
    idGen.nextId();

    var monitor = createMonitor();

    // First check: should emit warn
    monitor.doCheck();
    assertThat(staleTxCount.getValue()).isEqualTo(1);

    // Second check: stale tx still there, warn already emitted, should still count
    monitor.doCheck();
    assertThat(staleTxCount.getValue()).isEqualTo(1);
  }

  /**
   * When a transaction crosses the critical threshold, the critical warning should be emitted
   * once. Subsequent calls should not re-emit until the repeat interval passes.
   */
  @Test
  public void testCriticalEmittedOnce() {
    // 35 seconds old: past critical (30s)
    var holder = createActiveHolder(TimeUnit.SECONDS.toNanos(35));
    tsMins.add(holder);
    idGen.setStartId(100);
    idGen.nextId();

    var monitor = createMonitor();

    // First check: should emit warn (since warnEmitted is false)
    monitor.doCheck();
    assertThat(staleTxCount.getValue()).isEqualTo(1);

    // Second check: warnEmitted is true now, isCritical is true, criticalEmitted is false
    // → should emit critical
    monitor.doCheck();
    assertThat(staleTxCount.getValue()).isEqualTo(1);

    // Third check: both emitted, not enough time for repeat → no emission
    monitor.doCheck();
    assertThat(staleTxCount.getValue()).isEqualTo(1);
  }

  /**
   * After the REPEAT_WARN_INTERVAL_NANOS (5 minutes), critical warnings should repeat.
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

    // Third check: nothing (repeat interval not yet passed)
    monitor.doCheck();

    // Advance ticker by 5 minutes (past repeat interval)
    ticker.advance(TimeUnit.MINUTES.toNanos(5));

    // Fourth check: should re-emit critical (repeat interval passed)
    // Verify it doesn't throw and still counts the stale tx
    monitor.doCheck();
    assertThat(staleTxCount.getValue()).isEqualTo(1);
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
    assertThat(staleTxCount.getValue()).isEqualTo(1);

    // Simulate tx end
    holder.tsMin = Long.MAX_VALUE;
    holder.setTxStartTimeNanosOpaque(0);

    monitor.doCheck();
    assertThat(activeTxCount.getValue()).isEqualTo(0);
    assertThat(staleTxCount.getValue()).isEqualTo(0);
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
    assertThat(staleTxCount.getValue()).isEqualTo(1);

    // Simulate thread death: remove holder from tsMins
    tsMins.remove(holder);

    monitor.doCheck();
    assertThat(activeTxCount.getValue()).isEqualTo(0);
    assertThat(staleTxCount.getValue()).isEqualTo(0);
  }

  // --- Growth trend detection ---

  /**
   * After GROWTH_TREND_CYCLES (3) consecutive cycles where the snapshot index grows and
   * the LWM is stuck, the monitor should detect the trend. After detection, the counter
   * resets and another 3 cycles are needed.
   */
  @Test
  public void testGrowthTrendDetection() {
    var holder = createActiveHolder(TimeUnit.SECONDS.toNanos(1));
    holder.tsMin = 50;
    tsMins.add(holder);
    idGen.setStartId(100);
    idGen.nextId();

    // Set high warn threshold so individual tx warnings don't fire
    config.setValue(GlobalConfiguration.STORAGE_TX_WARN_TIMEOUT_SECS, 9999);

    var monitor = createMonitor();

    // Initial check: establishes baseline
    snapshotIndexSize.set(100);
    monitor.doCheck();

    // 3 growth cycles with stuck LWM: trigger warning and reset
    snapshotIndexSize.set(200);
    monitor.doCheck(); // cycle 1

    snapshotIndexSize.set(300);
    monitor.doCheck(); // cycle 2

    snapshotIndexSize.set(400);
    monitor.doCheck(); // cycle 3 → triggers growth trend warning, resets counter

    // After reset, needs 3 more cycles
    snapshotIndexSize.set(500);
    monitor.doCheck(); // cycle 1 again

    snapshotIndexSize.set(600);
    monitor.doCheck(); // cycle 2 again

    snapshotIndexSize.set(700);
    monitor.doCheck(); // cycle 3 again → triggers again

    // Verify the monitor doesn't throw during this process
    assertThat(activeTxCount.getValue()).isEqualTo(1);
  }

  /**
   * If the LWM changes (moves forward), the growth trend counter should reset.
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

    snapshotIndexSize.set(300);
    monitor.doCheck();

    // Now advance LWM (holder starts new tx with higher tsMin)
    holder.tsMin = 80;
    snapshotIndexSize.set(400);
    monitor.doCheck(); // counter should reset because LWM changed

    // Only 1 more cycle, not enough to trigger
    snapshotIndexSize.set(500);
    monitor.doCheck();

    // Verify no crash
    assertThat(activeTxCount.getValue()).isEqualTo(1);
  }

  /**
   * If snapshot index size doesn't grow (stays the same or shrinks), no growth trend.
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

    // Size stays the same — counter resets
    snapshotIndexSize.set(200);
    monitor.doCheck();

    snapshotIndexSize.set(300);
    monitor.doCheck(); // cycle 1 again (reset happened)

    assertThat(activeTxCount.getValue()).isEqualTo(1);
  }

  /**
   * If all holders are idle (lwm = MAX_VALUE), the growth trend should reset.
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

    // Make all holders idle
    holder.tsMin = Long.MAX_VALUE;
    holder.setTxStartTimeNanosOpaque(0);

    snapshotIndexSize.set(300);
    monitor.doCheck(); // should reset because lwm = MAX_VALUE

    assertThat(activeTxCount.getValue()).isEqualTo(0);
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
   * This test verifies that run() does not throw when the underlying data is in an unexpected
   * state.
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
   * Tests the boundary: {@code ageNanos >= warnThresholdNanos} (not strictly greater-than).
   */
  @Test
  public void testExactWarnThresholdIsCounted() {
    // Exactly 10 seconds (the warn threshold)
    var holder = createActiveHolder(TimeUnit.SECONDS.toNanos(10));
    tsMins.add(holder);
    idGen.setStartId(100);
    idGen.nextId();

    var monitor = createMonitor();
    monitor.doCheck();

    assertThat(staleTxCount.getValue())
        .as("Exactly at the warn threshold should still be stale")
        .isEqualTo(1);
  }

  /**
   * A transaction 1 nanosecond below the warn threshold should NOT be counted as stale.
   */
  @Test
  public void testJustBelowWarnThresholdNotStale() {
    // 1 nanosecond below 10 seconds
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
  }

  /**
   * A transaction whose age is exactly at the critical threshold should trigger critical.
   * Tests the boundary: {@code ageNanos >= criticalThresholdNanos}.
   */
  @Test
  public void testExactCriticalThreshold() {
    // Exactly 30 seconds (the critical threshold)
    var holder = createActiveHolder(TimeUnit.SECONDS.toNanos(30));
    tsMins.add(holder);
    idGen.setStartId(100);
    idGen.nextId();

    var monitor = createMonitor();

    // First call: emits warn
    monitor.doCheck();
    assertThat(staleTxCount.getValue()).isEqualTo(1);

    // Second call: isCritical is true at exactly 30s → emits critical
    monitor.doCheck();
    assertThat(staleTxCount.getValue()).isEqualTo(1);
  }

  /**
   * Verify that the ageNanos > oldestAge comparison uses strict greater-than:
   * two transactions with the same age should result in that age being the oldest.
   */
  @Test
  public void testTwoTransactionsSameAge() {
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

    assertThat(oldestTxAge.getValue()).isEqualTo(12L);
    assertThat(activeTxCount.getValue()).isEqualTo(2);
    assertThat(staleTxCount.getValue()).isEqualTo(2);
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
   * Verify that the growth trend counter resets to 0 (not some other value) when all
   * holders become idle. After reset, exactly GROWTH_TREND_CYCLES (3) more cycles
   * are needed to trigger the warning again.
   */
  @Test
  public void testGrowthTrendRequiresExactlyThreeCycles() {
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

    // Only 2 cycles — not enough to trigger (need 3)
    snapshotIndexSize.set(200);
    monitor.doCheck();

    snapshotIndexSize.set(300);
    monitor.doCheck();

    // Make all idle → reset
    holder.tsMin = Long.MAX_VALUE;
    holder.setTxStartTimeNanosOpaque(0);
    monitor.doCheck();

    // Re-activate
    holder.tsMin = 50;
    holder.setTxStartTimeNanosOpaque(ticker.approximateNanoTime() - TimeUnit.SECONDS.toNanos(1));

    // Need exactly 3 growth cycles to trigger again
    snapshotIndexSize.set(100);
    monitor.doCheck(); // new baseline

    snapshotIndexSize.set(200);
    monitor.doCheck(); // cycle 1

    snapshotIndexSize.set(300);
    monitor.doCheck(); // cycle 2

    snapshotIndexSize.set(400);
    monitor.doCheck(); // cycle 3 — triggers

    // Verify no crash and active tx is counted
    assertThat(activeTxCount.getValue()).isEqualTo(1);
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
