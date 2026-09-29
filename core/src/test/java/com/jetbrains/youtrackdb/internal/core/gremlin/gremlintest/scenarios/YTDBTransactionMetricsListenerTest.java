package com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.scenarios;

import static org.apache.tinkerpop.gremlin.LoadGraphWith.GraphData.MODERN;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import com.jetbrains.youtrackdb.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.profiler.WallClockStep;
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

  private static long wallClockAllowanceMillis;

  @BeforeClass
  public static void beforeClass() {
    wallClockAllowanceMillis = WallClockStep.measureMillis();
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

  // 3.9. LIGHTWEIGHT mode bounds real database commits with the shared ticker.
  // Exact source provenance is proved by FrontendTransactionImplTickerTest.
  @Test
  @LoadGraphWith(MODERN)
  public void lightweightModeUsesApproximateTimestamps() {
    var ticker = YouTrackDBEnginesManager.instance().getTicker();
    int qualifying = 0;
    for (int total = 0; total < 20; total++) {
      var listener = new RememberingTxListener();
      var beforeMillis = ticker.approximateCurrentTimeMillis();
      var beforeNanos = ticker.approximateNanoTime();

      var tx = ytdbTx()
          .withQueryMonitoringMode(QueryMonitoringMode.LIGHTWEIGHT)
          .withTransactionListener(listener);
      tx.open();
      g().addV("TestVertex").property("name", "lightweight" + total).iterate();
      var tickerNanoBeforeCommit = ticker.approximateNanoTime();
      var tickerMillisBeforeCommit = ticker.approximateCurrentTimeMillis();
      tx.commit();

      // Read the ticker immediately after commit to identify a stable snapshot window.
      var tickerNanoAfterCommit = ticker.approximateNanoTime();
      var tickerMillisAfterCommit = ticker.approximateCurrentTimeMillis();
      var afterNanos = System.nanoTime();
      var afterMillis = System.currentTimeMillis();

      assertThat(listener.callCount).isEqualTo(1);
      assertThat(listener.commitAtMillis)
          .as("commit timestamp must not exceed ticker after commit")
          .isLessThanOrEqualTo(tickerMillisAfterCommit)
          .as("ticker must not run ahead of wall clock")
          .isLessThanOrEqualTo(afterMillis + wallClockAllowanceMillis)
          .isGreaterThanOrEqualTo(beforeMillis);
      if (tickerNanoBeforeCommit == tickerNanoAfterCommit) {
        // A stable ticker window permits an exact check. The controlled test proves provenance.
        assertThat(listener.commitAtMillis)
            .as("unchanged ticker requires the exact cached commit timestamp")
            .isEqualTo(tickerMillisBeforeCommit);
        qualifying++;
      }
      // The wall bound allows division truncation and one measured clock step. The
      // nanoTime window starts at the same ticker and ends at a real read after commit.
      assertThat(listener.commitTimeNanos)
          .isGreaterThanOrEqualTo(0)
          .isLessThanOrEqualTo(afterNanos - beforeNanos);
    }
    System.err.printf("LIGHTWEIGHT commit qualifying %d/20%n", qualifying);
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
