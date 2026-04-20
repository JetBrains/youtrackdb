package com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.scenarios;

import static org.apache.tinkerpop.gremlin.LoadGraphWith.GraphData.MODERN;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import com.jetbrains.youtrackdb.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.QueryMetricsListener;
import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.QueryMonitoringMode;
import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.TransactionMetricsListener;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import org.apache.tinkerpop.gremlin.LoadGraphWith;
import org.apache.tinkerpop.gremlin.process.GremlinProcessRunner;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

@Category(SequentialTest.class)
@RunWith(GremlinProcessRunner.class)
public class YTDBTransactionMetricsListenerTest extends YTDBAbstractGremlinTest {

  // The ticker-based millis timestamp is derived from two independently-refreshed volatile
  // fields (nanoTime and nanoTimeDifference). When nanoTimeDifference is recalibrated,
  // integer truncation in nanoTime/1_000_000 can cause the result to dip by up to 1 ms.
  private static final long ALLOWED_TICKER_JITTER_MS = 1;

  private static long TICKER_POSSIBLE_LAG_MILLIS;
  private static long TICKER_GRANULARITY_MILLIS;

  @BeforeClass
  public static void beforeClass() throws InterruptedException {
    var granularity =
        YouTrackDBEnginesManager.instance().getTicker().getGranularity();
    TICKER_GRANULARITY_MILLIS = granularity / 1_000_000;
    // The ticker's background sampler fires at fixed-rate intervals of `granularity`, but
    // on virtualized CI the scheduler can delay a fire far beyond one granularity — Windows
    // has a ~15.6 ms OS timer quantum, and GitHub-hosted macOS arm (virtualized Apple
    // Silicon) has shown per-fire ticker lag up to ~75 ms for a 10 ms granularity across
    // the tests in this package. A 10x multiplier (100 ms for a 10 ms granularity) covers
    // both environments with headroom while still catching a ticker that drifts much
    // farther behind real wall-clock time. The tight "ticker never runs ahead of
    // wall-clock" direction is guarded independently in the assertion below at one
    // granularity + ALLOWED_TICKER_JITTER_MS, which is not relaxed.
    var tickerPossibleLagNanos = granularity * 10;
    TICKER_POSSIBLE_LAG_MILLIS = tickerPossibleLagNanos / 1_000_000;

    // Ensure ticker has had time to stabilize after graph setup.
    Thread.sleep(100);
  }

  // 3.1. Basic commit callback — write transaction triggers listener exactly once.
  @Test
  @LoadGraphWith(MODERN)
  public void writeTransactionCallsListener() {
    var listener = new RememberingTxListener();

    var tx = ytdbTx()
        .withTransactionListener(listener);
    tx.open();
    g().addV("TestVertex").property("name", "test").iterate();
    tx.commit();

    assertThat(listener.callCount).isEqualTo(1);
  }

  // 3.2. Read-only transaction — listener is NOT called.
  @Test
  @LoadGraphWith(MODERN)
  public void readOnlyTransactionDoesNotCallListener() {
    var listener = new RememberingTxListener();

    var tx = ytdbTx()
        .withQueryMonitoringMode(QueryMonitoringMode.EXACT)
        .withTransactionListener(listener);
    tx.open();
    g().V().hasLabel("person").toList();
    tx.commit();

    assertThat(listener.callCount).isEqualTo(0);
  }

  // 3.3. Empty transaction — listener is NOT called.
  @Test
  @LoadGraphWith(MODERN)
  public void emptyTransactionDoesNotCallListener() {
    var listener = new RememberingTxListener();

    var tx = ytdbTx()
        .withTransactionListener(listener);
    tx.open();
    tx.commit();

    assertThat(listener.callCount).isEqualTo(0);
  }

  // 3.4. Rollback — listener is NOT called.
  @Test
  @LoadGraphWith(MODERN)
  public void rollbackDoesNotCallListener() {
    var listener = new RememberingTxListener();

    var tx = ytdbTx()
        .withTransactionListener(listener);
    tx.open();
    g().addV("TestVertex").property("name", "rollbackMe").iterate();
    tx.rollback();

    assertThat(listener.callCount).isEqualTo(0);
  }

  // 3.5. Exception during commit — writeTransactionCommitted is NOT called,
  // but writeTransactionFailed IS called with the cause.
  // Forces a ConcurrentModificationException by modifying the same vertex in a second session
  // before the monitored transaction commits.
  @Test
  @LoadGraphWith(MODERN)
  public void commitFailureCallsFailedListener() {
    var listener = new RememberingTxListener();

    // Start a monitored transaction and modify an existing vertex.
    var tx = ytdbTx()
        .withTransactionListener(listener);
    tx.open();
    var marko = g().V().has("name", "marko").next();
    g().V(marko.id()).property("age", 99).iterate();

    // Concurrently modify the same vertex in a separate session and commit first.
    try (var session2 = ((YTDBGraphEmbedded) graph()).acquireSession()) {
      var tx2 = session2.begin();
      var v = tx2.loadEntity((RID) marko.id());
      v.setProperty("age", 100);
      tx2.commit();
    }

    // The monitored commit should fail due to the version conflict.
    assertThatThrownBy(tx::commit)
        .isInstanceOf(ConcurrentModificationException.class);

    // writeTransactionCommitted must NOT have been called.
    assertThat(listener.callCount).isEqualTo(0);
    // writeTransactionFailed must have been called with timing data and cause.
    assertThat(listener.failCount).isEqualTo(1);
    assertThat(listener.failCause).isInstanceOf(ConcurrentModificationException.class);
    assertThat(listener.failCommitAtMillis).isGreaterThan(0);
    assertThat(listener.failCommitTimeNanos).isGreaterThanOrEqualTo(0);
  }

  // 3.6. Listener exception safety — buggy listener does not break the commit.
  @Test
  @LoadGraphWith(MODERN)
  public void listenerExceptionDoesNotBreakCommit() {
    TransactionMetricsListener throwingListener = new TransactionMetricsListener() {
      @Override
      public void writeTransactionCommitted(
          TransactionDetails txDetails, long commitAtMillis, long commitTimeNanos) {
        throw new RuntimeException("Listener bug");
      }
    };

    var tx = ytdbTx()
        .withTransactionListener(throwingListener);
    tx.open();
    g().addV("TestVertex").property("name", "safe").iterate();
    // Should not throw — the listener exception is caught internally.
    tx.commit();

    // Verify the vertex was actually persisted despite the listener exception.
    g.tx().open();
    assertThat(g().V().has("name", "safe").hasNext()).isTrue();
    g.tx().commit();

    // Verify monitoring state was cleaned up (next tx doesn't carry the listener).
    assertThat(ytdbTx().isTransactionMetricsEnabled()).isFalse();
  }

  // 3.7. Tracking ID — explicit.
  @Test
  @LoadGraphWith(MODERN)
  public void explicitTrackingIdAppearsInDetails() {
    var listener = new RememberingTxListener();

    var tx = ytdbTx()
        .withTrackingId("my-tx-42")
        .withTransactionListener(listener);
    tx.open();
    g().addV("TestVertex").property("name", "tracked").iterate();
    tx.commit();

    assertThat(listener.trackingId).isEqualTo("my-tx-42");
  }

  // 3.8. Tracking ID — auto-generated.
  @Test
  @LoadGraphWith(MODERN)
  public void autoGeneratedTrackingIdIsPresent() {
    var listener = new RememberingTxListener();

    var tx = ytdbTx()
        .withTransactionListener(listener);
    tx.open();
    g().addV("TestVertex").property("name", "autoId").iterate();
    tx.commit();

    assertThat(listener.trackingId).isNotNull().isNotEmpty();
  }

  // 3.9. LIGHTWEIGHT mode — approximate timestamps.
  @Test
  @LoadGraphWith(MODERN)
  public void lightweightModeUsesApproximateTimestamps() {
    var listener = new RememberingTxListener();
    var beforeMillis = System.currentTimeMillis();

    var tx = ytdbTx()
        .withQueryMonitoringMode(QueryMonitoringMode.LIGHTWEIGHT)
        .withTransactionListener(listener);
    tx.open();
    g().addV("TestVertex").property("name", "lightweight").iterate();
    tx.commit();

    var afterMillis = System.currentTimeMillis();

    assertThat(listener.callCount).isEqualTo(1);
    // Ticker must never run ahead of real wall-clock time. The ticker exposes a past
    // nanoTime sample, so forward drift can only come from nanoTimeDifference
    // recalibration and integer truncation — one granularity plus ALLOWED_TICKER_JITTER_MS
    // covers both. This tight bound is independent of scheduler noise on virtualized CI.
    assertThat(listener.commitAtMillis)
        .as("ticker must not run ahead of wall clock")
        .isLessThanOrEqualTo(
            afterMillis + TICKER_GRANULARITY_MILLIS + ALLOWED_TICKER_JITTER_MS);
    // In lightweight mode, timestamps come from the ticker and may lag behind real time
    // by up to TICKER_POSSIBLE_LAG_MILLIS due to scheduler starvation on virtualized CI.
    assertThat(listener.commitAtMillis)
        .isGreaterThanOrEqualTo(beforeMillis - TICKER_POSSIBLE_LAG_MILLIS);
    // In LIGHTWEIGHT mode, if the commit is faster than the ticker granularity, both the
    // start and end approximate nano times can be identical, yielding a zero duration.
    assertThat(listener.commitTimeNanos).isGreaterThanOrEqualTo(0);
  }

  // 3.9 (cont). EXACT mode — precise timestamps.
  @Test
  @LoadGraphWith(MODERN)
  public void exactModeUsesPreciseTimestamps() {
    var listener = new RememberingTxListener();
    var beforeMillis = System.currentTimeMillis();

    var tx = ytdbTx()
        .withQueryMonitoringMode(QueryMonitoringMode.EXACT)
        .withTransactionListener(listener);
    tx.open();
    g().addV("TestVertex").property("name", "exact").iterate();
    tx.commit();

    var afterMillis = System.currentTimeMillis();

    assertThat(listener.callCount).isEqualTo(1);
    assertThat(listener.commitAtMillis)
        .isGreaterThanOrEqualTo(beforeMillis)
        .isLessThanOrEqualTo(afterMillis);
    assertThat(listener.commitTimeNanos).isGreaterThan(0);
  }

  // 3.10. Listener cleanup on commit — next transaction doesn't carry the listener.
  @Test
  @LoadGraphWith(MODERN)
  public void listenerIsNotCarriedOverToNextTransaction() {
    var listener = new RememberingTxListener();

    var tx = ytdbTx()
        .withTransactionListener(listener);
    tx.open();
    g().addV("TestVertex").property("name", "first").iterate();
    tx.commit();

    assertThat(listener.callCount).isEqualTo(1);

    // Second transaction without re-registering the listener.
    tx.open();
    g().addV("TestVertex").property("name", "second").iterate();
    tx.commit();

    // Listener should not have been called again.
    assertThat(listener.callCount).isEqualTo(1);
  }

  // 3.11. Both listeners together — query and transaction listeners fire in same tx.
  @Test
  @LoadGraphWith(MODERN)
  public void bothListenersFireInSameTransaction() {
    var txListener = new RememberingTxListener();
    var queryListener = new RememberingQueryListener();

    var tx = ytdbTx()
        .withQueryMonitoringMode(QueryMonitoringMode.EXACT)
        .withQueryListener(queryListener)
        .withTransactionListener(txListener);
    tx.open();
    g().addV("TestVertex").property("name", "both").iterate();
    tx.commit();

    assertThat(txListener.callCount).isEqualTo(1);
    assertThat(queryListener.callCount).isGreaterThanOrEqualTo(1);
  }

  // 3.12. Multiple writes — listener fires exactly once.
  @Test
  @LoadGraphWith(MODERN)
  public void multipleWritesSingleCallback() {
    var listener = new RememberingTxListener();

    var tx = ytdbTx()
        .withTransactionListener(listener);
    tx.open();
    g().addV("TestVertex").property("name", "v1").iterate();
    g().addV("TestVertex").property("name", "v2").iterate();
    g().addV("TestVertex").property("name", "v3").iterate();
    tx.commit();

    assertThat(listener.callCount).isEqualTo(1);
  }

  private YTDBTransaction ytdbTx() {
    return (YTDBTransaction) g.tx();
  }

  // Single-threaded test helper — no synchronization needed.
  static class RememberingTxListener implements TransactionMetricsListener {

    int callCount;
    String trackingId;
    long commitAtMillis;
    long commitTimeNanos;

    int failCount;
    long failCommitAtMillis;
    long failCommitTimeNanos;
    Exception failCause;

    @Override
    public void writeTransactionCommitted(
        TransactionDetails txDetails, long commitAtMillis, long commitTimeNanos) {
      this.callCount++;
      this.trackingId = txDetails.getTransactionTrackingId();
      this.commitAtMillis = commitAtMillis;
      this.commitTimeNanos = commitTimeNanos;
    }

    @Override
    public void writeTransactionFailed(
        TransactionDetails txDetails, long commitAtMillis, long commitTimeNanos,
        Exception cause) {
      this.failCount++;
      this.failCommitAtMillis = commitAtMillis;
      this.failCommitTimeNanos = commitTimeNanos;
      this.failCause = cause;
    }
  }

  static class RememberingQueryListener implements QueryMetricsListener {

    int callCount;

    @Override
    public void queryFinished(
        QueryDetails queryDetails, long startedAtMillis, long executionTimeNanos) {
      this.callCount++;
    }
  }
}
