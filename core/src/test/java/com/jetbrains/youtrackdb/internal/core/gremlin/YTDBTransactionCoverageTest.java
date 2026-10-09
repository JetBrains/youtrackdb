package com.jetbrains.youtrackdb.internal.core.gremlin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.jetbrains.youtrackdb.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.QueryMetricsListener;
import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.QueryMonitoringMode;
import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.TransactionMetricsListener;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.SessionListener;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.apache.commons.configuration2.BaseConfiguration;
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
    assertThrows(IllegalArgumentException.class, () -> tx.onReadWrite(null));
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
    assertThrows(IllegalArgumentException.class, () -> tx.onClose(null));
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
   * Rollback clears the completed activation's monitoring after notification callbacks finish.
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
    var thrown = assertThrows(IllegalStateException.class, tx::getDatabaseSession);
    assertTrue(
        "expected message to mention 'not active' but was: " + thrown.getMessage(),
        thrown.getMessage().contains("not active"));
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

  /** Listeners can read completed settings before or after graph close, even when they then fail. */
  @Test
  public void completionNotificationsObserveCompletedMonitoring() {
    for (var commit : new boolean[] {true, false}) {
      for (var fail : new boolean[] {false, true}) {
        for (var beforeGraph : new boolean[] {false, true}) {
          var tx = ytdbTx();
          tx.clearTransactionListeners();
          var queryListener = mock(QueryMetricsListener.class);
          var transactionListener = mock(TransactionMetricsListener.class);
          var notified = new AtomicBoolean();
          var failure = new IllegalStateException("after reading completed monitoring");
          Consumer<Transaction.Status> listener = status -> {
            assertEquals(commit ? Transaction.Status.COMMIT : Transaction.Status.ROLLBACK, status);
            assertEquals("t1", tx.getTrackingId());
            assertEquals(QueryMonitoringMode.EXACT, tx.getQueryMonitoringMode());
            assertSame(queryListener, tx.getQueryMetricsListener());
            assertTrue(tx.isQueryMetricsEnabled());
            assertTrue(tx.isTransactionMetricsEnabled());
            notified.set(true);
            if (fail) {
              throw failure;
            }
          };
          if (beforeGraph) {
            tx.addTransactionListener(listener);
          }
          tx.withTrackingId("t1").withQueryMonitoringMode(QueryMonitoringMode.EXACT)
              .withQueryListener(queryListener).withTransactionListener(transactionListener);
          tx.open();
          graph.addVertex();
          var session = tx.getDatabaseSession();
          if (!beforeGraph) {
            tx.addTransactionListener(listener);
          }
          if (fail) {
            assertSame(failure, assertThrows(RuntimeException.class, () -> complete(tx, commit)));
          } else {
            complete(tx, commit);
          }
          assertTrue("the monitoring-reading listener must run", notified.get());
          assertTrue(session.isClosed());
          assertFalse(tx.isOpen());
          assertMonitoringCleared(tx);
          assertThrows(IllegalStateException.class, tx::getTrackingId);
          verify(transactionListener, times(commit ? 1 : 0))
              .writeTransactionCommitted(any(), anyLong(), anyLong());
          tx.removeTransactionListener(listener);
        }
      }
    }
  }

  /** Identical values belong to the newer generation, including configuration before open. */
  @Test
  public void identicalMonitoringSettingsSurviveNewerActivation() {
    for (var commit : new boolean[] {true, false}) {
      for (var fail : new boolean[] {false, true}) {
        for (var beforeGraph : new boolean[] {false, true}) {
          for (var configureBeforeOpen : new boolean[] {false, true}) {
            var tx = ytdbTx();
            tx.clearTransactionListeners();
            var queryListener = mock(QueryMetricsListener.class);
            var transactionListener = mock(TransactionMetricsListener.class);
            var failure = new IllegalArgumentException("after identical configuration");
            Runnable configure = () -> tx.withTrackingId("same")
                .withQueryMonitoringMode(QueryMonitoringMode.EXACT)
                .withQueryListener(queryListener).withTransactionListener(transactionListener);
            Consumer<Transaction.Status> listener = status -> {
              if (configureBeforeOpen) {
                configure.run();
                tx.open();
              } else {
                tx.open();
                assertMonitoringCleared(tx);
                configure.run();
              }
              if (fail) {
                throw failure;
              }
            };
            if (beforeGraph) {
              tx.addTransactionListener(listener);
            }
            configure.run();
            tx.open();
            graph.addVertex();
            if (!beforeGraph) {
              tx.addTransactionListener(listener);
            }
            if (fail) {
              assertSame(failure, assertThrows(RuntimeException.class, () -> complete(tx, commit)));
            } else {
              complete(tx, commit);
            }
            assertTrue(tx.isOpen());
            assertEquals("same", tx.getTrackingId());
            assertEquals(QueryMonitoringMode.EXACT, tx.getQueryMonitoringMode());
            assertSame(queryListener, tx.getQueryMetricsListener());
            assertTrue(tx.isQueryMetricsEnabled());
            assertTrue(tx.isTransactionMetricsEnabled());
            tx.removeTransactionListener(listener);
            graph.addVertex();
            tx.commit();
            verify(transactionListener, times(commit ? 2 : 1))
                .writeTransactionCommitted(any(), anyLong(), anyLong());
          }
        }
      }
    }
  }

  /** Each setter starts fresh before reopen and carries no other completed settings with it. */
  @Test
  public void partialMonitoringConfiguredBeforeReopenStartsFromDefaults() {
    var queryListener = mock(QueryMetricsListener.class);
    var transactionListener = mock(TransactionMetricsListener.class);
    List<Consumer<YTDBTransaction>> setters = List.of(
        tx -> tx.withTrackingId("newer"),
        tx -> tx.withQueryMonitoringMode(QueryMonitoringMode.EXACT),
        tx -> tx.withQueryListener(queryListener),
        tx -> tx.withTransactionListener(transactionListener));
    for (var commit : new boolean[] {true, false}) {
      for (var setting = 0; setting < setters.size(); setting++) {
        var tx = ytdbTx();
        tx.clearTransactionListeners();
        var selectedSetting = setting;
        Runnable assertConfigured = () -> {
          assertEquals(selectedSetting == 1 ? QueryMonitoringMode.EXACT
              : QueryMonitoringMode.LIGHTWEIGHT, tx.getQueryMonitoringMode());
          assertSame(selectedSetting == 2 ? queryListener : QueryMetricsListener.NO_OP,
              tx.getQueryMetricsListener());
          assertEquals(selectedSetting == 2, tx.isQueryMetricsEnabled());
          assertEquals(selectedSetting == 3, tx.isTransactionMetricsEnabled());
        };
        Consumer<Transaction.Status> listener = status -> {
          setters.get(selectedSetting).accept(tx);
          assertConfigured.run();
          if (selectedSetting == 0) {
            assertEquals("newer", tx.getTrackingId());
          } else {
            assertThrows(IllegalStateException.class, tx::getTrackingId);
          }
          tx.open();
        };
        tx.addTransactionListener(listener);
        monitor(tx, "completed");
        tx.open();
        complete(tx, commit);
        assertTrue(tx.isOpen());
        assertFalse("completed".equals(tx.getTrackingId()));
        if (selectedSetting == 0) {
          assertEquals("newer", tx.getTrackingId());
        }
        assertConfigured.run();
        tx.removeTransactionListener(listener);
        tx.rollback();
      }
    }
  }

  /** An early completion listener failure stops notifications but releases the session and metrics. */
  @Test
  public void earlyListenerFailureCleansCommitAndRollback() {
    for (var commit : new boolean[] {true, false}) {
      var tx = ytdbTx();
      tx.clearTransactionListeners();
      var failure = new IllegalStateException("listener failed");
      List<Transaction.Status> events = new ArrayList<>();
      Consumer<Transaction.Status> listener = status -> {
        events.add(status);
        throw failure;
      };
      tx.addTransactionListener(listener);
      monitor(tx, "completed");
      tx.open();
      graph.addVertex();
      var session = tx.getDatabaseSession();
      var scope = ((YTDBGraphImplAbstract) graph).currentScope();
      assertSame(failure, assertThrows(RuntimeException.class,
          () -> complete(tx, commit)));
      assertEquals(List.of(commit ? Transaction.Status.COMMIT : Transaction.Status.ROLLBACK),
          events);
      assertTrue(session.isClosed());
      assertFalse(tx.isOpen());
      assertMonitoringCleared(tx);
      // Both cleanup owners can run without closing the detached session again.
      ((YTDBGraphImplAbstract) graph).closeCachedSession(scope, session);
      tx.removeTransactionListener(listener);
      tx.open();
      assertFalse("completed".equals(tx.getTrackingId()));
      assertEquals(commit ? 1L : 0L, (long) graph.traversal().V().count().next());
      // Remove committed data before the rollback case in this test.
      graph.traversal().V().drop().iterate();
      tx.commit();
    }
  }

  /** Explicit and traversal-driven listener activations survive failure before or after graph close. */
  @Test
  public void listenerFailurePreservesNewerActivation() {
    for (var traversal : new boolean[] {false, true}) {
      for (var beforeGraph : new boolean[] {false, true}) {
        var tx = ytdbTx();
        tx.clearTransactionListeners();
        var failure = new IllegalArgumentException("after reopen");
        Consumer<Transaction.Status> listener = status -> {
          if (traversal) {
            graph.traversal().V().count().next();
          } else {
            tx.open();
          }
          monitor(tx, "newer");
          throw failure;
        };
        if (beforeGraph) {
          tx.addTransactionListener(listener);
        }
        monitor(tx, "completed");
        tx.open();
        var attemptedSession = tx.getDatabaseSession();
        if (!beforeGraph) {
          tx.addTransactionListener(listener);
        }
        assertSame(failure, assertThrows(RuntimeException.class, tx::commit));
        assertTrue(tx.isOpen());
        assertTrue(tx.getDatabaseSession().isTxActive());
        assertFalse(tx.getDatabaseSession().isClosed());
        if (beforeGraph) {
          assertSame(attemptedSession, tx.getDatabaseSession());
        }
        assertEquals("newer", tx.getTrackingId());
        assertEquals(QueryMonitoringMode.EXACT, tx.getQueryMonitoringMode());
        assertTrue(tx.isQueryMetricsEnabled());
        assertTrue(tx.isTransactionMetricsEnabled());
        tx.removeTransactionListener(listener);
        tx.rollback();
      }
    }
  }

  /** A later listener can reopen after a successful notification without losing the new metrics. */
  @Test
  public void successfulNotificationPreservesNewerMonitoring() {
    for (var commit : new boolean[] {true, false}) {
      var tx = ytdbTx();
      tx.open();
      Consumer<Transaction.Status> listener = status -> {
        tx.open();
        monitor(tx, "newer");
      };
      tx.addTransactionListener(listener);
      complete(tx, commit);
      assertTrue(tx.isOpen());
      assertEquals("newer", tx.getTrackingId());
      assertTrue(tx.isTransactionMetricsEnabled());
      tx.removeTransactionListener(listener);
      tx.rollback();
    }
  }

  /** Failed commit and rollback cleanup preserve metrics configured by a real session-close hook. */
  @Test
  public void sessionCloseCallbackPreservesNewerMonitoringAfterListenerFailure() {
    for (var commit : new boolean[] {true, false}) {
      var tx = ytdbTx();
      tx.clearTransactionListeners();
      var failure = new IllegalStateException("early listener failed");
      Consumer<Transaction.Status> listener = status -> {
        throw failure;
      };
      tx.addTransactionListener(listener);
      monitor(tx, "completed");
      tx.open();
      var attemptedSession = tx.getDatabaseSession();
      var reopened = new AtomicBoolean();
      QueryMetricsListener queryListener = (details, start, duration) -> {
      };
      var transactionListener = mock(TransactionMetricsListener.class);
      attemptedSession.registerListener(new SessionListener() {
        @Override
        public void onClose(DatabaseSessionEmbedded session) {
          if (reopened.compareAndSet(false, true)) {
            tx.open();
            assertMonitoringCleared(tx);
            tx.withTrackingId("newer").withQueryMonitoringMode(QueryMonitoringMode.EXACT)
                .withQueryListener(queryListener).withTransactionListener(transactionListener);
          }
        }
      });
      assertSame(failure, assertThrows(RuntimeException.class, () -> complete(tx, commit)));
      assertTrue("session-close callback must run", reopened.get());
      assertTrue(attemptedSession.isClosed());
      assertTrue(tx.isOpen());
      assertFalse(tx.getDatabaseSession().isClosed());
      assertEquals("newer", tx.getTrackingId());
      assertEquals(QueryMonitoringMode.EXACT, tx.getQueryMonitoringMode());
      assertSame(queryListener, tx.getQueryMetricsListener());
      assertTrue(tx.isTransactionMetricsEnabled());
      tx.removeTransactionListener(listener);
      graph.addVertex();
      tx.commit();
      verify(transactionListener).writeTransactionCommitted(any(), anyLong(), anyLong());
    }
  }

  /** Successful session close can reopen with fresh defaults and retain even identical settings. */
  @Test
  public void sessionCloseCallbackPreservesNewerMonitoringAfterSuccessfulCompletion() {
    for (var commit : new boolean[] {true, false}) {
      var tx = ytdbTx();
      var queryListener = mock(QueryMetricsListener.class);
      var transactionListener = mock(TransactionMetricsListener.class);
      Runnable configure = () -> tx.withTrackingId("same")
          .withQueryMonitoringMode(QueryMonitoringMode.EXACT)
          .withQueryListener(queryListener).withTransactionListener(transactionListener);
      configure.run();
      tx.open();
      var attemptedSession = tx.getDatabaseSession();
      var reopened = new AtomicBoolean();
      attemptedSession.registerListener(new SessionListener() {
        @Override
        public void onClose(DatabaseSessionEmbedded session) {
          if (reopened.compareAndSet(false, true)) {
            assertEquals("same", tx.getTrackingId());
            assertEquals(QueryMonitoringMode.EXACT, tx.getQueryMonitoringMode());
            tx.open();
            assertMonitoringCleared(tx);
            configure.run();
          }
        }
      });
      complete(tx, commit);
      assertTrue(reopened.get());
      assertTrue(attemptedSession.isClosed());
      assertTrue(tx.isOpen());
      assertEquals("same", tx.getTrackingId());
      assertEquals(QueryMonitoringMode.EXACT, tx.getQueryMonitoringMode());
      assertSame(queryListener, tx.getQueryMetricsListener());
      assertTrue(tx.isTransactionMetricsEnabled());
      graph.addVertex();
      tx.commit();
      verify(transactionListener).writeTransactionCommitted(any(), anyLong(), anyLong());
    }
  }

  /** Reopening without new settings never inherits completed metrics, on either outcome route. */
  @Test
  public void notificationReopenWithoutConfigurationGetsFreshMonitoring() {
    for (var commit : new boolean[] {true, false}) {
      for (var fail : new boolean[] {false, true}) {
        for (var beforeGraph : new boolean[] {false, true}) {
          var tx = ytdbTx();
          tx.clearTransactionListeners();
          var failure = new IllegalArgumentException("after reopen");
          Consumer<Transaction.Status> listener = status -> {
            tx.open();
            if (fail) {
              throw failure;
            }
          };
          if (beforeGraph) {
            tx.addTransactionListener(listener);
          }
          var completedListener = mock(TransactionMetricsListener.class);
          monitor(tx, "completed");
          tx.withTransactionListener(completedListener);
          tx.open();
          graph.addVertex();
          if (!beforeGraph) {
            tx.addTransactionListener(listener);
          }
          if (fail) {
            assertSame(failure, assertThrows(RuntimeException.class, () -> complete(tx, commit)));
          } else {
            complete(tx, commit);
          }
          assertTrue(tx.isOpen());
          assertFalse("completed".equals(tx.getTrackingId()));
          assertMonitoringCleared(tx);
          tx.removeTransactionListener(listener);
          graph.addVertex();
          tx.commit();
          verify(completedListener, times(commit ? 1 : 0))
              .writeTransactionCommitted(any(), anyLong(), anyLong());
        }
      }
    }
  }

  /** Nested completion restores the enclosing attempt and never closes a third activation. */
  @Test
  public void reentrantCompletionPreservesNewestActivationAndMonitoring() {
    var tx = ytdbTx();
    tx.clearTransactionListeners();
    monitor(tx, "outer");
    var reentered = new AtomicBoolean();
    Consumer<Transaction.Status> listener = status -> {
      if (reentered.compareAndSet(false, true)) {
        assertEquals("outer", tx.getTrackingId());
        tx.open();
        assertMonitoringCleared(tx);
        monitor(tx, "nested");
        tx.commit();
        tx.open();
        assertMonitoringCleared(tx);
        monitor(tx, "newest");
      } else {
        assertEquals("nested", tx.getTrackingId());
        assertEquals(QueryMonitoringMode.EXACT, tx.getQueryMonitoringMode());
        assertTrue(tx.isQueryMetricsEnabled());
        assertTrue(tx.isTransactionMetricsEnabled());
      }
    };
    tx.addTransactionListener(listener);
    tx.open();
    var attemptedSession = tx.getDatabaseSession();
    tx.commit();
    assertTrue(reentered.get());
    assertTrue(tx.isOpen());
    assertFalse(tx.getDatabaseSession().isClosed());
    assertEquals("newest", tx.getTrackingId());
    assertEquals(QueryMonitoringMode.EXACT, tx.getQueryMonitoringMode());
    assertTrue(tx.isQueryMetricsEnabled());
    assertTrue(tx.isTransactionMetricsEnabled());
    tx.removeTransactionListener(listener);
    tx.rollback();
    assertTrue(attemptedSession.isClosed());
  }

  /** Outer commit and rollback close only their session while the suspended inner cache stays live. */
  @Test
  public void successfulOuterCompletionLeavesIdleOrActiveInnerSessionAlone() {
    for (var commit : new boolean[] {true, false}) {
      for (var activeInner : new boolean[] {false, true}) {
        var tx = ytdbTx();
        monitor(tx, "outer");
        tx.open();
        var outerSession = tx.getDatabaseSession();
        List<Transaction.Status> events = new ArrayList<>();
        Consumer<Transaction.Status> listener = events::add;
        tx.addTransactionListener(listener);
        graph.withSuspendedTransaction(() -> {
          var inner = ytdbTx();
          monitor(inner, "inner");
          if (activeInner) {
            inner.open();
          }
          var innerSession = ((YTDBGraphImplAbstract) graph).getUnderlyingDatabaseSession();
          complete(tx, commit);
          assertTrue(outerSession.isClosed());
          assertFalse(tx.isOpen());
          assertMonitoringCleared(tx);
          assertFalse(innerSession.isClosed());
          assertSame(innerSession,
              ((YTDBGraphImplAbstract) graph).getUnderlyingDatabaseSession());
          assertEquals(activeInner, inner.isOpen());
          assertEquals("inner", inner.getTrackingId());
          assertTrue(inner.isTransactionMetricsEnabled());
          assertEquals(List.of(commit ? Transaction.Status.COMMIT : Transaction.Status.ROLLBACK),
              events);
          if (activeInner) {
            assertSame(innerSession, inner.getDatabaseSession());
            inner.rollback();
          }
          return null;
        });
        assertSame(tx, graph.tx());
        tx.removeTransactionListener(listener);
      }
    }
  }

  /** A listener failing after outer graph cleanup must not close an idle suspended inner session. */
  @Test
  public void failedOuterNotificationLeavesIdleInnerSessionAlone() {
    for (var commit : new boolean[] {true, false}) {
      var tx = ytdbTx();
      monitor(tx, "outer");
      tx.open();
      var outerSession = tx.getDatabaseSession();
      var failure = new IllegalStateException("late listener failed");
      Consumer<Transaction.Status> listener = status -> {
        assertTrue("graph listener must close the outer session first", outerSession.isClosed());
        throw failure;
      };
      tx.addTransactionListener(listener);
      graph.withSuspendedTransaction(() -> {
        var inner = ytdbTx();
        monitor(inner, "inner");
        var innerSession = ((YTDBGraphImplAbstract) graph).getUnderlyingDatabaseSession();
        assertSame(failure, assertThrows(RuntimeException.class, () -> complete(tx, commit)));
        assertTrue(outerSession.isClosed());
        assertMonitoringCleared(tx);
        assertFalse(innerSession.isClosed());
        assertFalse(inner.isOpen());
        assertSame(innerSession, ((YTDBGraphImplAbstract) graph).getUnderlyingDatabaseSession());
        assertEquals("inner", inner.getTrackingId());
        assertTrue(inner.isTransactionMetricsEnabled());
        return null;
      });
      assertSame(tx, graph.tx());
      tx.removeTransactionListener(listener);
    }
  }

  /** A conflicting outer commit in a suspended scope closes only the outer session. */
  @Test
  public void failedOuterCommitLeavesSuspendedInnerSessionActive() {
    var tx = ytdbTx();
    tx.open();
    var vertex = graph.addVertex();
    var id = vertex.id();
    tx.commit();
    monitor(tx, "outer");
    tx.open();
    graph.traversal().V(id).property("age", 1).iterate();
    var outerSession = tx.getDatabaseSession();
    graph.withSuspendedTransaction(() -> {
      var inner = ytdbTx();
      inner.open();
      graph.traversal().V(id).property("age", 2).iterate();
      inner.commit();
      monitor(inner, "inner");
      inner.open();
      var innerSession = inner.getDatabaseSession();
      assertThrows(ConcurrentModificationException.class, tx::commit);
      assertTrue(outerSession.isClosed());
      assertMonitoringCleared(tx);
      assertSame(innerSession, inner.getDatabaseSession());
      assertTrue(inner.isOpen());
      assertFalse(innerSession.isClosed());
      assertEquals("inner", inner.getTrackingId());
      inner.rollback();
      return null;
    });
    assertSame(tx, graph.tx());
    tx.open();
    assertFalse("outer".equals(tx.getTrackingId()));
    tx.rollback();
  }

  /** A database failure stays primary when close also fails, including identical throwable objects. */
  @Test
  public void databaseFailureSuppressesDistinctCloseFailureAndDetachesOnce() {
    for (var sameFailure : new boolean[] {false, true}) {
      var fixture = mockSessionGraph();
      var failure = new AssertionError("database failure");
      var closeFailure = sameFailure ? failure : new AssertionError("incomplete teardown");
      doThrow(failure).when(fixture.session()).commit();
      doThrow(closeFailure).when(fixture.session()).close();
      var tx = fixture.graph().tx();
      monitor(tx, "completed");
      tx.open();
      var scope = fixture.graph().currentScope();
      assertSame(failure, assertThrows(AssertionError.class, tx::commit));
      assertEquals(sameFailure ? 0 : 1, failure.getSuppressed().length);
      if (!sameFailure) {
        assertSame(closeFailure, failure.getSuppressed()[0]);
      }
      assertMonitoringCleared(tx);
      assertFalse(tx.isOpen());
      fixture.graph().closeCachedSession(scope, fixture.session());
      verify(fixture.session(), times(1)).close();
      var fresh = mock(DatabaseSessionEmbedded.class);
      when(fixture.graph().acquireSession()).thenReturn(fresh);
      assertSame(fresh, fixture.graph().getUnderlyingDatabaseSession());
    }
  }

  /** A real nested database begin leaves work active after graph commit and reaches the guard. */
  @Test
  public void nestedDatabaseCommitRejectsStillActiveSessionAndCleansIt() {
    var tx = ytdbTx();
    tx.clearTransactionListeners();
    var nestingObserved = new AtomicBoolean();
    Consumer<Transaction.Status> earlyListener = status -> {
      assertEquals(Transaction.Status.COMMIT, status);
      var session = ((YTDBGraphImplAbstract) graph).getUnderlyingDatabaseSession();
      assertTrue("nested commit must leave the database transaction active", session.isTxActive());
      assertEquals(1, session.getActiveTransaction().amountOfNestedTxs());
      nestingObserved.set(true);
    };
    tx.addTransactionListener(earlyListener);
    monitor(tx, "nested");
    tx.open();
    var session = tx.getDatabaseSession();
    session.begin();
    assertEquals(2, session.getActiveTransaction().amountOfNestedTxs());
    graph.addVertex();
    var lateNotified = new AtomicBoolean();
    Consumer<Transaction.Status> lateListener = status -> lateNotified.set(true);
    tx.addTransactionListener(lateListener);
    var failure = assertThrows(IllegalStateException.class, tx::commit);
    assertEquals("Transaction is still active", failure.getMessage());
    assertTrue(nestingObserved.get());
    assertFalse("the guard must stop later notifications", lateNotified.get());
    assertTrue(session.isClosed());
    assertFalse(tx.isOpen());
    assertMonitoringCleared(tx);
    tx.removeTransactionListener(earlyListener);
    tx.removeTransactionListener(lateListener);
    tx.open();
    assertFalse("nested".equals(tx.getTrackingId()));
    assertEquals("unfinished nested work must not be saved", 0L,
        (long) graph.traversal().V().count().next());
    tx.rollback();
  }

  /** A graph-listener close failure is primary and must not trigger a second pooled close. */
  @Test
  public void graphListenerCloseFailureIsPrimaryAndNotRetried() {
    var fixture = mockSessionGraph();
    var failure = new IllegalStateException("close failed");
    doThrow(failure).when(fixture.session()).close();
    var tx = fixture.graph().tx();
    monitor(tx, "completed");
    tx.open();
    assertSame(failure, assertThrows(RuntimeException.class, tx::commit));
    assertEquals(0, failure.getSuppressed().length);
    assertMonitoringCleared(tx);
    verify(fixture.session(), times(1)).close();
  }

  private static void complete(YTDBTransaction tx, boolean commit) {
    if (commit) {
      tx.commit();
    } else {
      tx.rollback();
    }
  }

  private static void monitor(YTDBTransaction tx, String id) {
    tx.withTrackingId(id).withQueryMonitoringMode(QueryMonitoringMode.EXACT)
        .withQueryListener((details, start, duration) -> {
        }).withTransactionListener(new TransactionMetricsListener() {
        });
  }

  private static void assertMonitoringCleared(YTDBTransaction tx) {
    assertEquals(QueryMonitoringMode.LIGHTWEIGHT, tx.getQueryMonitoringMode());
    assertFalse(tx.isQueryMetricsEnabled());
    assertFalse(tx.isTransactionMetricsEnabled());
    assertSame(QueryMetricsListener.NO_OP, tx.getQueryMetricsListener());
  }

  private static MockSessionGraph mockSessionGraph() {
    var graph = mock(YTDBGraphImplAbstract.class,
        withSettings().useConstructor(new BaseConfiguration()).defaultAnswer(CALLS_REAL_METHODS));
    var session = mock(DatabaseSessionEmbedded.class);
    var active = new AtomicBoolean();
    when(graph.acquireSession()).thenReturn(session);
    when(session.isTxActive()).thenAnswer(invocation -> active.get());
    when(session.begin()).thenAnswer(invocation -> {
      active.set(true);
      return null;
    });
    doAnswer(invocation -> {
      active.set(false);
      return null;
    }).when(session).commit();
    // Monitored commit delegates to the same controlled database step.
    doAnswer(invocation -> {
      session.commit();
      return null;
    }).when(session).monitoredCommit(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    return new MockSessionGraph(graph, session);
  }

  private record MockSessionGraph(YTDBGraphImplAbstract graph, DatabaseSessionEmbedded session) {
  }

  /**
   * Tiny helper — assert that a thunk throws {@link NullPointerException}. Avoids a
   * test-utility import from elsewhere (kept local).
   */
  private static void assertThrowsNpe(Runnable thunk) {
    assertThrows(NullPointerException.class, thunk::run);
  }
}
