package com.jetbrains.youtrackdb.internal.core.gremlin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.QueryMetricsListener;
import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.QueryMonitoringMode;
import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.TransactionMetricsListener;
import java.util.ArrayList;
import java.util.List;
import org.apache.tinkerpop.gremlin.structure.Transaction;
import org.junit.Test;

/**
 * Targets the {@link YTDBTransaction} listener / monitoring API surface that is otherwise
 * exercised only by JMH benchmarks and the higher-level
 * {@link GraphSuspendedTransactionTest}. Each test exercises a single contract:
 *
 * <ul>
 *   <li>{@code onReadWrite}/{@code onClose} accept a non-null consumer and return {@code this};
 *   <li>{@code onReadWrite}/{@code onClose} reject {@code null};
 *   <li>{@code addTransactionListener}/{@code removeTransactionListener}/
 *       {@code clearTransactionListeners} maintain a thread-safe listener set;
 *   <li>{@code withTrackingId} / {@code withQueryMonitoringMode} / {@code withQueryListener} /
 *       {@code withTransactionListener} are fluent setters returning {@code this} and
 *       reflected by the {@code is*Enabled} / {@code getQuery*} accessors;
 *   <li>{@code getTrackingId} returns the configured value or falls back to the active
 *       transaction's id when no tracking-id was set;
 *   <li>{@code getDatabaseSession} on a closed transaction throws.
 * </ul>
 *
 * <p>The class extends {@link GraphBaseTest} for the per-method graph fixture; a fresh
 * {@link YTDBTransaction} is obtained via {@code (YTDBTransaction) graph.tx()} for each test —
 * the {@link org.apache.tinkerpop.gremlin.structure.Graph#tx} method declares the upstream
 * {@link Transaction} return type so the cast is required to reach the YouTrackDB-specific
 * monitoring API.
 */
public class YTDBTransactionCoverageTest extends GraphBaseTest {

  private YTDBTransaction ytdbTx() {
    return (YTDBTransaction) graph.tx();
  }

  /** {@code onReadWrite} returns {@code this} for fluent chaining. */
  @Test
  public void onReadWriteReturnsThis() {
    var tx = ytdbTx();
    var returned = tx.onReadWrite(t -> {
    });
    assertSame(tx, returned);
  }

  /** {@code onReadWrite} rejects {@code null} via the {@code Optional.orElseThrow} arm. */
  @Test
  public void onReadWriteRejectsNull() {
    var tx = ytdbTx();
    try {
      tx.onReadWrite(null);
      fail("Expected rejection of null read-write consumer");
    } catch (IllegalArgumentException expected) {
      // ok
    }
  }

  /** {@code onClose} returns {@code this}. */
  @Test
  public void onCloseReturnsThis() {
    var tx = ytdbTx();
    var returned = tx.onClose(t -> {
    });
    assertSame(tx, returned);
  }

  /** {@code onClose} rejects {@code null} via the same {@code Optional.orElseThrow} arm. */
  @Test
  public void onCloseRejectsNull() {
    var tx = ytdbTx();
    try {
      tx.onClose(null);
      fail("Expected rejection of null close consumer");
    } catch (IllegalArgumentException expected) {
      // ok
    }
  }

  /**
   * Adding a listener via {@code addTransactionListener} causes it to fire on commit;
   * {@code removeTransactionListener} prevents the next commit's listener invocation.
   */
  @Test
  public void addAndRemoveTransactionListenerControlsCommitFiring() {
    List<Transaction.Status> events = new ArrayList<>();
    var listener = (java.util.function.Consumer<Transaction.Status>) events::add;

    graph.tx().open();
    graph.tx().addTransactionListener(listener);
    graph.addVertex();
    graph.tx().commit();
    assertEquals(List.of(Transaction.Status.COMMIT), events);

    // After commit, monitoring state is cleared; re-arm listener and verify removal.
    events.clear();
    graph.tx().open();
    graph.tx().addTransactionListener(listener);
    graph.tx().removeTransactionListener(listener);
    graph.addVertex();
    graph.tx().commit();
    assertTrue(
        "removed listener must not fire on subsequent commit",
        events.isEmpty());
  }

  /** {@code clearTransactionListeners} drops every listener. */
  @Test
  public void clearTransactionListenersDropsAllRegistrations() {
    List<Transaction.Status> events = new ArrayList<>();
    graph.tx().open();
    graph.tx().addTransactionListener(events::add);
    graph.tx().addTransactionListener(events::add);
    graph.tx().clearTransactionListeners();
    graph.addVertex();
    graph.tx().commit();
    assertTrue("cleared listeners must not fire", events.isEmpty());
  }

  /**
   * Default monitoring mode is {@link QueryMonitoringMode#LIGHTWEIGHT}; default listeners
   * are the {@code NO_OP} sentinels and the corresponding {@code is*Enabled} predicates
   * report {@code false}.
   */
  @Test
  public void defaultMonitoringStateIsLightweightAndDisabled() {
    var tx = ytdbTx();
    assertEquals(QueryMonitoringMode.LIGHTWEIGHT, tx.getQueryMonitoringMode());
    assertFalse(tx.isQueryMetricsEnabled());
    assertFalse(tx.isTransactionMetricsEnabled());
    assertSame(QueryMetricsListener.NO_OP, tx.getQueryMetricsListener());
  }

  /** Setting a non-NO_OP query listener flips {@code isQueryMetricsEnabled} to {@code true}. */
  @Test
  public void withQueryListenerEnablesMetricsFlag() {
    var tx = ytdbTx();
    QueryMetricsListener listener = (qd, startedAtMillis, executionTimeNanos) -> {
      // no-op test listener
    };
    var returned = tx.withQueryListener(listener);
    assertSame(tx, returned);
    assertTrue(tx.isQueryMetricsEnabled());
    assertSame(listener, tx.getQueryMetricsListener());
  }

  /**
   * Setting a non-NO_OP transaction listener flips {@code isTransactionMetricsEnabled}
   * to {@code true}. {@link TransactionMetricsListener} is not a single-abstract-method
   * functional interface (both methods are {@code default}), so we instantiate an anonymous
   * subclass directly.
   */
  @Test
  public void withTransactionListenerEnablesMetricsFlag() {
    var tx = ytdbTx();
    var listener = new TransactionMetricsListener() {
    };
    var returned = tx.withTransactionListener(listener);
    assertSame(tx, returned);
    assertTrue(tx.isTransactionMetricsEnabled());
  }

  /** {@code withQueryMonitoringMode} is reflected by {@code getQueryMonitoringMode}. */
  @Test
  public void withQueryMonitoringModeIsObservable() {
    var tx = ytdbTx();
    var returned = tx.withQueryMonitoringMode(QueryMonitoringMode.EXACT);
    assertSame(tx, returned);
    assertEquals(QueryMonitoringMode.EXACT, tx.getQueryMonitoringMode());
  }

  /** {@code withTrackingId} is reflected by {@code getTrackingId} verbatim. */
  @Test
  public void withTrackingIdIsObservable() {
    var tx = ytdbTx();
    var returned = tx.withTrackingId("test-tracking-id");
    assertSame(tx, returned);
    // Reading getTrackingId requires an active session — open a tx first.
    graph.tx().open();
    assertEquals("test-tracking-id", tx.getTrackingId());
    graph.tx().rollback();
  }

  /**
   * When {@code withTrackingId} is NOT called, {@code getTrackingId} falls back to the
   * active transaction id (an {@link Object#toString} of the {@code FrontendTransactionId}).
   */
  @Test
  public void getTrackingIdFallsBackToActiveTransactionId() {
    graph.tx().open();
    var fallback = ytdbTx().getTrackingId();
    assertNotNull(fallback);
    assertFalse("fallback tracking-id must be non-empty", fallback.isEmpty());
    graph.tx().rollback();
  }

  /**
   * After a rollback the monitoring state is cleared — pin the {@code clearMonitoringState}
   * branch in {@code fireOnRollback}.
   */
  @Test
  public void rollbackClearsMonitoringState() {
    var tx = ytdbTx();
    tx.withTrackingId("tracking")
        .withQueryMonitoringMode(QueryMonitoringMode.EXACT)
        .withQueryListener((qd, sam, etn) -> {
        })
        .withTransactionListener(new TransactionMetricsListener() {
        });

    graph.tx().open();
    graph.addVertex();
    graph.tx().rollback();

    // After rollback, monitoring state is cleared.
    assertEquals(QueryMonitoringMode.LIGHTWEIGHT, tx.getQueryMonitoringMode());
    assertFalse(tx.isQueryMetricsEnabled());
    assertFalse(tx.isTransactionMetricsEnabled());
  }

  /**
   * Fluent setters reject {@code null} via {@link java.util.Objects#requireNonNull} —
   * exercise each branch.
   */
  @Test
  public void fluentSettersRejectNull() {
    var tx = ytdbTx();
    assertThrowsNpe(() -> tx.withTrackingId(null));
    assertThrowsNpe(() -> tx.withQueryMonitoringMode(null));
    assertThrowsNpe(() -> tx.withQueryListener(null));
    assertThrowsNpe(() -> tx.withTransactionListener(null));
  }

  /**
   * {@code getDatabaseSession} on a closed transaction throws — pin the
   * {@code activeSession == null} branch.
   */
  @Test
  public void getDatabaseSessionOnClosedTxThrows() {
    var tx = ytdbTx();
    try {
      tx.getDatabaseSession();
      fail("Expected IllegalStateException for getDatabaseSession on closed tx");
    } catch (IllegalStateException expected) {
      assertTrue(expected.getMessage().contains("not active"));
    }
  }

  /**
   * On a fresh thread-local transaction without ever opening, {@code isOpen} returns
   * {@code false} — covers the {@code activeSession == null} path of
   * {@link YTDBTransaction#isOpen()}.
   */
  @Test
  public void freshTransactionIsClosed() {
    var tx = ytdbTx();
    assertFalse(tx.isOpen());
  }

  /**
   * Tiny helper — assert that a thunk throws {@link NullPointerException}. Avoids a
   * test-utility import from elsewhere (kept local).
   */
  private static void assertThrowsNpe(Runnable thunk) {
    try {
      thunk.run();
      fail("Expected NullPointerException");
    } catch (NullPointerException expected) {
      // ok
    }
  }
}
