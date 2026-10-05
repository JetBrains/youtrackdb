package com.jetbrains.youtrackdb.internal.core.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.BaseMemoryInternalDatabase;
import com.jetbrains.youtrackdb.internal.LogRecordCollector;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.cache.WeakValueHashMap;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphImplAbstract;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.AbstractMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.MultiPlanMatchStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.DBSequence;
import com.jetbrains.youtrackdb.internal.core.query.RegisteredQuery;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalResultSet;
import com.jetbrains.youtrackdb.internal.core.sql.executor.cache.QueryResultCache;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.LocalResultSet;
import com.jetbrains.youtrackdb.internal.core.sql.parser.LocalResultSetLifecycleDecorator;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction.TXSTATUS;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionNoTx;
import com.jetbrains.youtrackdb.internal.core.tx.Transaction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.configuration2.Configuration;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.util.DefaultTraversal;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
@Category(SequentialTest.class)
public class RegisteredQueryRegistryTest extends BaseMemoryInternalDatabase {

  @Parameterized.Parameters(name = "serverMode-{0}")
  public static Object[] retentionModes() {
    return new Object[] {false, true};
  }

  private final boolean serverMode;

  public RegisteredQueryRegistryTest(boolean serverMode) {
    this.serverMode = serverMode;
  }

  @Override
  protected Configuration createConfig() {
    var config = super.createConfig();
    config.setProperty(GlobalConfiguration.QUERY_RESULT_SET_OPEN_WARNING_THRESHOLD.getKey(), 2);
    return config;
  }

  @Before
  public void openSessionWithSelectedRetentionMode() {
    // Follow session.copy() initialization, but select the registry retention policy explicitly.
    var original = session;
    var storage = original.getStorage();
    storage.open(original, null, null, original.getConfiguration());
    session = new DatabaseSessionEmbedded(storage, serverMode);
    session.init((YouTrackDBConfigImpl) original.getConfig(), original.getSharedContext());
    session.internalOpen(adminUser, adminPassword);
    original.close();
    session.activateOnCurrentThread();
    assertTrue(session.getActiveQueries().isEmpty());
  }

  @Test
  public void registryGettersExposeContractAndPreserveRetentionPolicy() {
    Map<String, RegisteredQuery> entries = session.getActiveQueries();
    assertTrue(serverMode ? entries instanceof HashMap : entries instanceof WeakValueHashMap);
    var query = new TestQuery(session, "synthetic", "synthetic description");
    session.queryStarted(query.id, query);
    RegisteredQuery entry = session.getActiveQuery(query.id);
    assertSame(query, entry);
    assertSame(query, entries.get(query.id));
    assertEquals("synthetic description", entry.getDescription());
    assertNull(session.getActiveQuery("missing"));
    query.close();
    assertEquals(1, query.closes);
    assertNull(session.getActiveQuery(query.id));
    assertTrue(entries.isEmpty());
  }

  @Test
  public void yqlResultSetKeepsPlanDescriptionAndExplicitCloseDeregisters() {
    session.begin();
    try (var result = session.query("select 42 as answer")) {
      var entries = session.getActiveQueries();
      assertEquals(1, entries.size());
      var id = entries.keySet().iterator().next();
      assertSame(result, session.getActiveQuery(id));
      assertEquals(result.getExecutionPlan().toString(), result.getDescription());
      assertEquals(42, ((Number) result.next().getProperty("answer")).intValue());
      result.close();
      assertNull(session.getActiveQuery(id));
      assertTrue(entries.isEmpty());
    } finally {
      session.rollback();
    }
  }

  @Test
  public void mixedSnapshotClosesEachEntryDespiteDeregistrationAndLeavesOtherSessionOpen() {
    session.begin();
    try (var result = session.query("select 1 as answer");
        var otherSession = openDatabase()) {
      otherSession.begin();
      try (var otherResult = otherSession.query("select 2 as answer")) {
        var first = new TestQuery(session, "first", "first description");
        var second = new TestQuery(session, "second", "second description");
        session.queryStarted(first.id, first);
        session.queryStarted(second.id, second);
        assertEquals(3, session.getActiveQueries().size());
        assertEquals(1, otherSession.getActiveQueries().size());

        session.closeActiveQueries();

        assertTrue(result.isClosed());
        assertFalse(result.hasNext());
        assertEquals(1, first.closes);
        assertEquals(1, second.closes);
        assertTrue(session.getActiveQueries().isEmpty());
        assertFalse(otherResult.isClosed());
        assertSame(otherResult, otherSession.getActiveQueries().values().iterator().next());
        assertEquals(2, ((Number) otherResult.next().getProperty("answer")).intValue());
        session.closeActiveQueries();
        assertEquals(1, first.closes);
        assertEquals(1, second.closes);
      } finally {
        otherSession.rollback();
      }
    } finally {
      session.rollback();
    }
  }

  @Test
  public void warningUsesPreInsertionCountAndExistingEntryDescriptions() {
    session.begin();
    try (var logs = LogRecordCollector.attachTo(DatabaseSessionEmbedded.class);
        var result = session.query("select 3 as answer")) {
      var existing = new TestQuery(session, "existing", "existing synthetic description");
      var incoming = new TestQuery(session, "incoming", "incoming synthetic description");
      session.queryStarted(existing.id, existing);
      assertEquals(2, session.getActiveQueries().size());
      assertTrue(logs.messages().isEmpty());

      session.queryStarted(incoming.id, incoming);

      assertEquals(3, session.getActiveQueries().size());
      assertSame(incoming, session.getActiveQuery(incoming.id));
      assertEquals(List.of(warning(session, 2)), logs.messages().stream()
          .filter(message -> message.startsWith("WARNING ")).toList());
      assertTrue(logs.messages().contains(logPrefix("FINE", session) + result.getExecutionPlan()));
      assertTrue(logs.messages().contains(logPrefix("FINE", session) + existing.getDescription()));
      assertFalse(logs.messages().stream().anyMatch(message -> message.contains(
          "incoming synthetic description")));
      session.closeActiveQueries();
    } finally {
      session.rollback();
    }
  }

  @Test
  public void nullPlanResultSetDescriptionIsSafeInDebugDiagnostics() {
    try (var logs = LogRecordCollector.attachTo(DatabaseSessionEmbedded.class);
        var result = new InternalResultSet(null)) {
      assertNull(result.getExecutionPlan());
      assertEquals("null", result.getDescription());
      session.queryStarted("no-plan", result);
      var existing = new TestQuery(session, "existing", "synthetic description");
      var incoming = new TestQuery(session, "incoming", "incoming description");
      session.queryStarted(existing.id, existing);
      session.queryStarted(incoming.id, incoming);
      assertTrue(logs.messages().contains(warning(session, 2)));
      assertTrue(logs.messages().contains(logPrefix("FINE", session) + "null"));
      session.closeActiveQueries();
      assertTrue(result.isClosed());
      // Bulk closure removes entries even when their type has no lifecycle listener.
      assertTrue(session.getActiveQueries().isEmpty());
    }
  }

  @Test
  public void disabledThresholdNeverWarnsAndThresholdOneStillSkipsCountsZeroAndOne() {
    for (var threshold : new int[] {0, -1, 1}) {
      var registry = registryWithThreshold(threshold);
      var logger = Logger.getLogger(DatabaseSessionEmbedded.class.getName());
      var oldLevel = logger.getLevel();
      try (var logs = LogRecordCollector.attachTo(DatabaseSessionEmbedded.class)) {
        // Exercise the non-debug path without replacing the logger or the session registry.
        logger.setLevel(Level.INFO);
        var queries = new ArrayList<TestQuery>();
        for (var i = 0; i < 4; i++) {
          var query = new TestQuery(registry, "entry-" + i, "description-" + i);
          queries.add(query); // Keep weak values strongly owned for the entire assertion scope.
          registry.queryStarted(query.id, query);
          if (i < 2) {
            assertTrue(logs.messages().isEmpty());
          }
        }
        assertEquals(threshold == 1 ? List.of(warning(registry, 2), warning(registry, 3))
            : List.of(), logs.messages());
        registry.closeActiveQueries();
        assertTrue(registry.getActiveQueries().isEmpty());
        queries.forEach(query -> assertEquals(1, query.closes));
      } finally {
        logger.setLevel(oldLevel);
      }
    }
  }

  /** Bulk closure attempts and removes mixed YQL/synthetic entries before rethrowing failures. */
  @Test
  public void bulkCloseFailuresRetireEverySnapshotEntryAndNeverRetryFailedYql() {
    session.begin();
    var attempts = new ArrayList<Throwable>();
    var plan = mock(InternalExecutionPlan.class);
    var stream = mock(ExecutionStream.class);
    when(plan.getContext()).thenReturn(new BasicCommandContext());
    when(plan.start()).thenReturn(stream);
    var yqlFailure = new AssertionError("YQL stream close");
    doAnswer(invocation -> {
      attempts.add(yqlFailure);
      throw yqlFailure;
    }).when(stream).close(any());
    var failedYql = new LocalResultSet(session, plan);
    failedYql.addLifecycleListener(session);
    session.queryStarted(failedYql.getQueryId(), failedYql);
    var syntheticFailure = new IllegalStateException("synthetic close");
    RegisteredQuery failedSynthetic = new RegisteredQuery() {
      @Override
      public void close() {
        attempts.add(syntheticFailure);
        throw syntheticFailure;
      }

      @Override
      public String getDescription() {
        return "failing synthetic";
      }
    };
    session.queryStarted("failing", failedSynthetic);
    var successful = new TestQuery(session, "successful", "successful synthetic");
    session.queryStarted(successful.id, successful);
    var result = session.query("select 42 as answer");
    var thrown = assertThrows(Throwable.class, session::closeActiveQueries);
    assertEquals(2, attempts.size());
    assertSame(attempts.getFirst(), thrown);
    assertEquals(List.of(attempts.get(1)), List.of(thrown.getSuppressed()));
    assertEquals(1, successful.closes);
    assertTrue(result.isClosed());
    assertTrue(session.getActiveQueries().isEmpty());
    session.closeActiveQueries();
    session.commit();
    verify(stream, times(1)).close(any());
    assertEquals(2, attempts.size());
    session.begin();
    var later = new TestQuery(session, "later", "new transaction");
    session.queryStarted(later.id, later);
    assertEquals(1, session.getActiveQueries().size());
    session.rollback();
    assertEquals(1, later.closes);
    verify(stream, times(1)).close(any());
  }

  /** Repeated identical failure objects do not cause self-suppression or stop later closure. */
  @Test
  public void bulkCloseSameFailureObjectStillAttemptsAllEntries() {
    var failure = new IllegalStateException("shared failure");
    var attempts = new ArrayList<String>();
    var owners = new ArrayList<RegisteredQuery>();
    for (int i = 0; i < 3; i++) {
      var id = "same-" + i;
      RegisteredQuery query = new RegisteredQuery() {
        @Override
        public void close() {
          attempts.add(id);
          throw failure;
        }

        @Override
        public String getDescription() {
          return id;
        }
      };
      owners.add(query);
      session.queryStarted(id, query);
    }
    assertSame(failure, assertThrows(IllegalStateException.class, session::closeActiveQueries));
    assertEquals(3, attempts.size());
    assertEquals(0, failure.getSuppressed().length);
    assertTrue(session.getActiveQueries().isEmpty());
    session.closeActiveQueries();
    assertEquals(3, attempts.size());
  }

  /** A close callback can register a distinct new execution outside the stable snapshot. */
  @Test
  public void bulkCloseKeepsReplacementExecutionOutsideItsSnapshot() {
    var replacement = new TestQuery(session, "replace", "replacement");
    RegisteredQuery original = new RegisteredQuery() {
      @Override
      public void close() {
        session.queryStarted(replacement.id, replacement);
      }

      @Override
      public String getDescription() {
        return "original";
      }
    };
    session.queryStarted("replace", original);
    session.closeActiveQueries();
    assertSame(replacement, session.getActiveQuery("replace"));
    assertEquals(0, replacement.closes);
    session.closeActiveQueries();
    assertEquals(1, replacement.closes);
    assertTrue(session.getActiveQueries().isEmpty());
  }

  /** Commit, rollback, and direct close finish transaction cleanup before a bulk failure escapes. */
  @Test
  public void bulkFailureFinishesTransactionStateStorageAndRecordCleanup() {
    for (int end = 0; end < 3; end++) {
      var tx = session.begin();
      var operation = tx.getAtomicOperation();
      var entity = tx.newVertex("V");
      entity.setProperty("cleanupProbe", end);
      tx.setCustomData("cleanup", "present");
      var failure = end == 1 ? new AssertionError("bulk close")
          : new IllegalStateException("bulk close");
      var closes = new int[1];
      RegisteredQuery query = new RegisteredQuery() {
        @Override
        public void close() {
          closes[0]++;
          if (failure instanceof Error error) {
            throw error;
          }
          throw (RuntimeException) failure;
        }

        @Override
        public String getDescription() {
          return "transaction cleanup probe";
        }
      };
      session.queryStarted("cleanup", query);
      if (end == 0) {
        // Use the actual transaction so the storage commit runs before its close failure.
        assertSame(failure, assertThrows(RuntimeException.class, tx::commitInternal));
      } else if (end == 1) {
        assertSame(failure, assertThrows(AssertionError.class, session::rollback));
      } else {
        assertSame(failure, assertThrows(RuntimeException.class, tx::close));
      }
      assertEquals(end == 0 ? TXSTATUS.COMPLETED : end == 1 ? TXSTATUS.ROLLED_BACK
          : TXSTATUS.INVALID, tx.getStatus());
      assertFalse(tx.isActive());
      assertNull(tx.getAtomicOperation());
      assertEquals(0, tx.getEntryCount());
      assertNull(tx.getCustomData("cleanup"));
      assertTrue(entity.isUnloaded());
      assertTrue(session.getTransactionInternal() instanceof FrontendTransactionNoTx);
      assertTrue(session.getActiveQueries().isEmpty());
      assertFalse(operation.isActive());
      session.closeActiveQueries();
      tx.close();
      assertEquals(1, closes[0]);
      // A new transaction must work and the committed data must survive the reported failure.
      session.begin();
      try (var rows = session.query("select from V where cleanupProbe = 0")) {
        assertTrue(rows.hasNext());
        rows.next();
        assertFalse(rows.hasNext());
      }
      session.rollback();

    }
  }

  /** Later cleanup failures keep the bulk failure suppressed and never carry it into another close. */
  @Test
  public void laterCleanupFailureSuppressesAndDiscardsBulkFailure() throws Exception {
    for (boolean rollback : new boolean[] {false, true}) {
      for (boolean sameFailure : new boolean[] {false, true}) {
        var tx = session.begin();
        var bulkFailure = new IllegalStateException("bulk close");
        var laterFailure = sameFailure ? bulkFailure : new AssertionError("cache clear");
        var cache = mock(QueryResultCache.class);
        var clears = new int[1];
        doAnswer(invocation -> {
          if (clears[0]++ == 0) {
            if (laterFailure instanceof Error error) {
              throw error;
            }
            throw (RuntimeException) laterFailure;
          }
          return null;
        }).when(cache).clear();
        var field = FrontendTransactionImpl.class.getDeclaredField("queryResultCache");
        field.setAccessible(true);
        field.set(tx, cache);
        RegisteredQuery query = new RegisteredQuery() {
          @Override
          public void close() {
            throw bulkFailure;
          }

          @Override
          public String getDescription() {
            return "bulk close failure before cache cleanup";
          }
        };
        session.queryStarted("cleanup-failure", query);

        assertSame(laterFailure, assertThrows(Throwable.class,
            rollback ? session::rollback : tx::close));
        assertEquals(sameFailure ? List.of() : List.of(bulkFailure),
            List.of(laterFailure.getSuppressed()));
        assertTrue(session.getActiveQueries().isEmpty());
        // Unrelated cleanup failures remain immediate. A later successful close completes cleanup.
        tx.close();
        tx.close();
        assertTrue(session.getTransactionInternal() instanceof FrontendTransactionNoTx);
        assertNull(tx.getAtomicOperation());
        session.begin();
        session.commit();
        assertTrue(session.getTransactionInternal() instanceof FrontendTransactionNoTx);
      }
    }
  }

  /** Real commit and rollback preserve either reset order for single and union MATCH in both modes. */
  @Test
  public void matchTransactionEndReopensFreshExecutionInBothResetOrders() {
    for (boolean commit : new boolean[] {false, true}) {
      for (boolean union : new boolean[] {false, true}) {
        for (boolean resetFirst : new boolean[] {false, true}) {
          var graph = mock(YTDBGraphImplAbstract.class);
          var graphTx = new YTDBTransaction(graph);
          when(graph.tx()).thenReturn(graphTx);
          when(graph.getUnderlyingDatabaseSession()).thenReturn(session);
          graphTx.open();
          var frontendTx = session.getActiveTransaction();
          var operation = frontendTx.getAtomicOperation();
          var traversal = new DefaultTraversal<Object, Vertex>(graph);
          var context = new BasicCommandContext();
          var plan = mock(InternalExecutionPlan.class);
          var copy = mock(InternalExecutionPlan.class);
          var stream = mock(ExecutionStream.class);
          var freshStream = mock(ExecutionStream.class);
          var row = mock(Result.class);
          when(row.getPropertyNames()).thenReturn(List.of("scalar"));
          when(row.getProperty("scalar")).thenReturn(42L);
          when(plan.getContext()).thenReturn(context);
          when(copy.getContext()).thenReturn(context);
          when(plan.start()).thenReturn(stream);
          when(copy.start()).thenReturn(freshStream);
          when(plan.copy(any())).thenReturn(copy);
          when(stream.hasNext(any())).thenReturn(true);
          when(stream.next(any())).thenReturn(row);
          when(freshStream.hasNext(any())).thenReturn(true);
          when(freshStream.next(any())).thenReturn(row);
          AbstractMatchPlanStep<Object, Vertex> step = union
              ? new MultiPlanMatchStep<>(traversal, Vertex.class, List.of(plan), "v",
                  BoundaryOutputType.SCALAR)
              : new YTDBMatchPlanStep<>(traversal, Vertex.class, plan, "v",
                  BoundaryOutputType.SCALAR);
          assertTrue(session.getActiveQueries().isEmpty());
          assertEquals(42L, ((Traverser.Admin<?>) step.next()).get());
          var oldId = session.getActiveQueries().keySet().iterator().next();
          var oldHandle = session.getActiveQuery(oldId); // Strong owner also in weak registry mode.
          if (resetFirst) {
            step.reset();
          }
          if (commit) {
            graphTx.commit();
          } else {
            graphTx.rollback();
          }
          assertEquals(commit ? TXSTATUS.COMPLETED : TXSTATUS.ROLLED_BACK, frontendTx.getStatus());
          assertFalse(operation.isActive());
          assertTrue(session.getTransactionInternal() instanceof FrontendTransactionNoTx);
          assertTrue(session.getActiveQueries().isEmpty());
          verify(plan, times(1)).close();
          verify(stream, times(1)).close(any());
          if (!resetFirst) {
            assertFalse(step.hasNext());
            step.reset();
          }
          assertEquals(42L, ((Traverser.Admin<?>) step.next()).get());
          assertTrue(graphTx.isOpen());
          assertTrue(session.getActiveTransaction() != frontendTx);
          assertEquals(1, session.getActiveQueries().size());
          assertNull(session.getActiveQuery(oldId));
          var freshHandle = session.getActiveQueries().values().iterator().next();
          assertTrue(freshHandle != oldHandle);
          verify(plan, times(1)).copy(any());
          verify(copy, times(1)).start();
          verify(plan, times(1)).start();
          oldHandle.close();
          verify(copy, times(0)).close();
          step.close();
          step.reset();
          step.close();
          verify(plan, times(1)).close();
          verify(copy, times(1)).close();
          assertTrue(session.getActiveQueries().isEmpty());
          graphTx.rollback();
        }
      }
    }
  }

  /** Rollback retains failures from both clear passes until storage and transaction cleanup finish. */
  @Test
  public void rollbackAggregatesFailuresFromBothClearPasses() {
    for (boolean sameFailure : new boolean[] {false, true}) {
      var tx = session.begin();
      var first = new IllegalStateException("first clear");
      var second = sameFailure ? first : new IllegalStateException("second clear");
      RegisteredQuery later = new RegisteredQuery() {
        @Override
        public void close() {
          throw second;
        }

        @Override
        public String getDescription() {
          return "second clear pass";
        }
      };
      RegisteredQuery initial = new RegisteredQuery() {
        @Override
        public void close() {
          session.queryStarted("later-clear", later);
          throw first;
        }

        @Override
        public String getDescription() {
          return "first clear pass";
        }
      };
      session.queryStarted("initial-clear", initial);
      assertSame(first, assertThrows(IllegalStateException.class, session::rollback));
      assertEquals(sameFailure ? List.of() : List.of(second), List.of(first.getSuppressed()));
      assertEquals(TXSTATUS.ROLLED_BACK, tx.getStatus());
      assertNull(tx.getAtomicOperation());
      assertTrue(session.getTransactionInternal() instanceof FrontendTransactionNoTx);
      assertTrue(session.getActiveQueries().isEmpty());
    }
  }

  /** Session close reports bulk failures only after rollback, listeners, cache and storage release. */
  @Test
  public void sessionCloseFinishesTeardownBeforeReportingBulkFailure() {
    for (boolean fatal : new boolean[] {false, true}) {
      var storage = mock(AbstractStorage.class, delegatesTo(session.getStorage()));
      var closing = openSessionWithStorageProbe(storage);
      var tx = closing.begin();
      var operation = tx.getAtomicOperation();
      var entity = tx.newVertex("V");
      var events = new ArrayList<String>();
      Throwable failure = fatal ? new AssertionError("session bulk close")
          : new IllegalStateException("session bulk close");
      var query = failingQuery(failure, events);
      closing.queryStarted("session-close", query);
      closing.registerListener(new SessionListener() {
        @Override
        public void onAfterTxRollback(Transaction transaction) {
          events.add("rollback");
        }

        @Override
        public void onClose(DatabaseSessionEmbedded database) {
          events.add("session");
          assertTrue(database.getTransactionInternal() instanceof FrontendTransactionNoTx);
          assertTrue(database.getActiveQueries().isEmpty());
          assertEquals(0, database.getLocalCache().getSize());
        }
      });
      try {
        assertSame(failure, assertThrows(Throwable.class, closing::close));
        assertEquals(List.of("query", "rollback", "session"), events);
        assertEquals(DatabaseSessionEmbedded.STATUS.CLOSED, closing.getStatus());
        assertEquals(TXSTATUS.ROLLED_BACK, tx.getStatus());
        assertFalse(tx.isActive());
        assertNull(tx.getAtomicOperation());
        assertFalse(operation.isActive());
        assertTrue(entity.isUnloaded());
        verify(storage, times(1)).close(closing);
        closing.close();
        assertEquals(List.of("query", "rollback", "session"), events);
        verify(storage, times(1)).close(closing);
      } finally {
        closing.close();
        session.activateOnCurrentThread();
      }
    }
  }

  /**
   * A rollback listener Error is reported after session listeners and storage close. An initial
   * bulk-close failure is retained, with no self-suppression when both failures are the same object.
   */
  @Test
  public void sessionCloseFinishesTeardownAfterRollbackListenerError() {
    for (int scenario = 0; scenario < 4; scenario++) {
      var storage = mock(AbstractStorage.class, delegatesTo(session.getStorage()));
      var closing = openSessionWithStorageProbe(storage);
      var tx = closing.begin();
      var operation = tx.getAtomicOperation();
      var entity = tx.newVertex("V");
      var rollbackFailure = new AssertionError("rollback listener");
      Throwable bulkFailure = switch (scenario) {
        case 0 -> null;
        case 1 -> new IllegalStateException("initial bulk close");
        case 2 -> new AssertionError("initial bulk close");
        default -> rollbackFailure;
      };
      var events = new ArrayList<String>();
      var query = bulkFailure == null ? null : failingQuery(bulkFailure, events);
      if (query != null) {
        closing.queryStarted("session-close", query);
      }
      closing.registerListener(new SessionListener() {
        @Override
        public void onAfterTxRollback(Transaction transaction) {
          events.add("rollback");
          throw rollbackFailure;
        }

        @Override
        public void onClose(DatabaseSessionEmbedded database) {
          events.add("session");
          assertTrue(database.getTransactionInternal() instanceof FrontendTransactionNoTx);
          assertTrue(database.getActiveQueries().isEmpty());
          assertEquals(0, database.getLocalCache().getSize());
        }
      });
      try {
        assertSame(rollbackFailure, assertThrows(AssertionError.class, closing::close));
        assertEquals(bulkFailure == null || bulkFailure == rollbackFailure ? List.of()
            : List.of(bulkFailure), List.of(rollbackFailure.getSuppressed()));
        var expectedEvents = bulkFailure == null ? List.of("rollback", "session")
            : List.of("query", "rollback", "session");
        assertEquals(expectedEvents, events);
        assertEquals(DatabaseSessionEmbedded.STATUS.CLOSED, closing.getStatus());
        assertEquals(TXSTATUS.ROLLED_BACK, tx.getStatus());
        assertNull(tx.getAtomicOperation());
        assertFalse(operation.isActive());
        assertTrue(entity.isUnloaded());
        verify(storage, times(1)).close(closing);
        closing.close();
        assertEquals(expectedEvents, events);
        verify(storage, times(1)).close(closing);
      } finally {
        closing.close();
        session.activateOnCurrentThread();
      }
    }
  }

  /** An early rollback Error cannot leave a CLOSED session with records or a live storage operation. */
  @Test
  public void sessionCloseForceFinishesAfterBeforeRollbackError() {
    for (int scenario = 0; scenario < 4; scenario++) {
      var storage = mock(AbstractStorage.class, delegatesTo(session.getStorage()));
      var closing = openSessionWithStorageProbe(storage);
      clearInvocations(storage); // Opening the session uses its own storage transactions.
      var tx = closing.begin();
      var operation = tx.getAtomicOperation();
      var entity = tx.newVertex("V");
      var earlyFailure = new AssertionError("before rollback");
      Throwable bulkFailure = switch (scenario) {
        case 0 -> null;
        case 1 -> new IllegalStateException("bulk close");
        case 2 -> new AssertionError("bulk close");
        default -> earlyFailure;
      };
      var events = new ArrayList<String>();
      var query = bulkFailure == null ? null : failingQuery(bulkFailure, events);
      if (query != null) {
        closing.queryStarted("early-rollback", query);
      }
      closing.registerListener(new SessionListener() {
        @Override
        public void onBeforeTxRollback(Transaction transaction) {
          events.add("before");
          throw earlyFailure;
        }

        @Override
        public void onAfterTxRollback(Transaction transaction) {
          events.add("after");
        }

        @Override
        public void onClose(DatabaseSessionEmbedded database) {
          events.add("close");
          assertTrue(database.getTransactionInternal() instanceof FrontendTransactionNoTx);
          assertTrue(database.getActiveQueries().isEmpty());
        }
      });
      try {
        assertSame(earlyFailure, assertThrows(AssertionError.class, closing::close));
        assertEquals(bulkFailure == null || bulkFailure == earlyFailure ? List.of()
            : List.of(bulkFailure), List.of(earlyFailure.getSuppressed()));
        assertEquals(bulkFailure == null ? List.of("before", "after", "close")
            : List.of("query", "before", "after", "close"), events);
        assertEquals(DatabaseSessionEmbedded.STATUS.CLOSED, closing.getStatus());
        assertEquals(TXSTATUS.ROLLED_BACK, tx.getStatus());
        assertNull(tx.getAtomicOperation());
        assertFalse(operation.isActive());
        assertTrue(entity.isUnloaded());
        verify(storage, times(1)).resetTsMin();
        verify(storage, times(1)).close(closing);
      } finally {
        closing.close();
        session.activateOnCurrentThread();
      }
    }
  }

  /** Before-rollback Exceptions stay logged, and a failed first clear is force-finished cleanly. */
  @Test
  public void sessionCloseLogsRollbackExceptionsAndCleansTransaction() throws Exception {
    for (boolean failClear : new boolean[] {false, true}) {
      var closing = openSessionWithStorageProbe(session.getStorage());
      var tx = closing.begin();
      var operation = tx.getAtomicOperation();
      var entity = tx.newVertex("V");
      var failure = new IllegalStateException("rollback exception");
      var events = new ArrayList<String>();
      var cache = mock(QueryResultCache.class);
      if (failClear) {
        doThrow(failure).doNothing().when(cache).clear();
        var field = FrontendTransactionImpl.class.getDeclaredField("queryResultCache");
        field.setAccessible(true);
        field.set(tx, cache);
      }
      closing.registerListener(new SessionListener() {
        @Override
        public void onBeforeTxRollback(Transaction transaction) {
          events.add("before");
          if (!failClear) {
            throw failure;
          }
        }

        @Override
        public void onAfterTxRollback(Transaction transaction) {
          events.add("after");
        }

        @Override
        public void onClose(DatabaseSessionEmbedded database) {
          assertTrue(database.getTransactionInternal() instanceof FrontendTransactionNoTx);
        }
      });
      try (var logs = LogRecordCollector.attachTo(DatabaseSessionEmbedded.class)) {
        closing.close();
        assertTrue(logs.messages().stream().anyMatch(message -> message.contains(failClear
            ? "Exception during rollback of active transaction"
            : "Error before transaction rollback")));
        assertEquals(List.of("before", "after"), events);
        assertEquals(DatabaseSessionEmbedded.STATUS.CLOSED, closing.getStatus());
        assertEquals(TXSTATUS.ROLLED_BACK, tx.getStatus());
        assertNull(tx.getAtomicOperation());
        assertFalse(operation.isActive());
        assertTrue(entity.isUnloaded());
        if (failClear) {
          verify(cache, times(2)).clear();
        }
      } finally {
        closing.close();
        session.activateOnCurrentThread();
      }
    }
  }

  /** A second rollback failure leaves OPEN, retains earlier failures, and releases the retry claim. */
  @Test
  public void doubleRollbackFailureKeepsSessionOpenUntilRetry() throws Exception {
    for (int scenario = 0; scenario < 3; scenario++) {
      var storage = mock(AbstractStorage.class, delegatesTo(session.getStorage()));
      var closing = openSessionWithStorageProbe(storage);
      var tx = closing.begin();
      var operation = tx.getAtomicOperation();
      var entity = tx.newVertex("V");
      Throwable first = scenario == 0 ? new AssertionError("before rollback")
          : new IllegalStateException("first clear");
      Throwable second = scenario == 2 ? new AssertionError("forced clear")
          : new IllegalStateException("forced clear");
      var bulk = new AssertionError("initial bulk close");
      var events = new ArrayList<String>();
      var query = failingQuery(bulk, events);
      closing.queryStarted("double-failure", query);
      var cache = mock(QueryResultCache.class);
      if (scenario == 0) {
        closing.registerListener(new SessionListener() {
          @Override
          public void onBeforeTxRollback(Transaction transaction) {
            events.add("before");
            throw (Error) first;
          }
        });
        doThrow(second).doNothing().when(cache).clear();
      } else {
        doThrow(first, second).doNothing().when(cache).clear();
      }
      var field = FrontendTransactionImpl.class.getDeclaredField("queryResultCache");
      field.setAccessible(true);
      field.set(tx, cache);
      closing.registerListener(new SessionListener() {
        @Override
        public void onAfterTxRollback(Transaction transaction) {
          events.add("after");
        }

        @Override
        public void onClose(DatabaseSessionEmbedded database) {
          events.add("close");
          assertTrue(database.getTransactionInternal() instanceof FrontendTransactionNoTx);
        }
      });
      try {
        assertSame(second, assertThrows(Throwable.class, closing::close));
        assertEquals(List.of(bulk, first), List.of(second.getSuppressed()));
        assertEquals(DatabaseSessionEmbedded.STATUS.OPEN, closing.getStatus());
        assertEquals(TXSTATUS.ROLLBACKING, tx.getStatus());
        assertTrue(operation.isActive());
        closing.activateOnCurrentThread();
        assertSame(tx, closing.getActiveTransaction());
        verify(storage, times(0)).close(closing);
        assertFalse(events.contains("after"));
        assertFalse(events.contains("close"));
        closing.close();
        assertEquals(DatabaseSessionEmbedded.STATUS.CLOSED, closing.getStatus());
        assertEquals(TXSTATUS.ROLLED_BACK, tx.getStatus());
        assertNull(tx.getAtomicOperation());
        assertFalse(operation.isActive());
        assertTrue(entity.isUnloaded());
        assertEquals(List.of("after", "close"), events.subList(events.size() - 2, events.size()));
        verify(storage, times(1)).close(closing);
      } finally {
        closing.activateOnCurrentThread();
        closing.close();
        session.activateOnCurrentThread();
      }
    }
  }

  /** Session close collapses a depth-two transaction and releases its storage operation once. */
  @Test
  public void sessionCloseRollsBackEveryNestedLevel() {
    var storage = mock(AbstractStorage.class, delegatesTo(session.getStorage()));
    var closing = openSessionWithStorageProbe(storage);
    clearInvocations(storage); // Count only the nested transaction's storage cleanup.
    var tx = closing.begin();
    var operation = tx.getAtomicOperation();
    closing.begin();
    assertEquals(2, tx.amountOfNestedTxs());
    closing.registerListener(new SessionListener() {
      @Override
      public void onClose(DatabaseSessionEmbedded database) {
        assertTrue(database.getTransactionInternal() instanceof FrontendTransactionNoTx);
      }
    });
    try {
      closing.close();
      assertEquals(DatabaseSessionEmbedded.STATUS.CLOSED, closing.getStatus());
      assertEquals(0, tx.amountOfNestedTxs());
      assertEquals(TXSTATUS.ROLLED_BACK, tx.getStatus());
      assertNull(tx.getAtomicOperation());
      assertFalse(operation.isActive());
      verify(storage, times(1)).resetTsMin();
    } finally {
      closing.close();
      session.activateOnCurrentThread();
    }
  }

  /**
   * A query registered during the initial close snapshot can fail with an Error during rollback.
   * Session teardown still completes and retains the initial failure, including shared identity.
   */
  @Test
  public void sessionCloseRetainsBulkErrorDiscoveredDuringRollback() {
    for (boolean sameFailure : new boolean[] {false, true}) {
      var storage = mock(AbstractStorage.class, delegatesTo(session.getStorage()));
      var closing = openSessionWithStorageProbe(storage);
      var tx = closing.begin();
      var operation = tx.getAtomicOperation();
      var rollbackFailure = new AssertionError("rollback bulk close");
      Throwable initialFailure = sameFailure ? rollbackFailure
          : new IllegalStateException("initial bulk close");
      var events = new ArrayList<String>();
      var later = failingQuery(rollbackFailure, events);
      var initial = failingQuery(initialFailure, events);
      RegisteredQuery registering = new RegisteredQuery() {
        @Override
        public void close() {
          closing.queryStarted("rollback-close", later);
          initial.close();
        }

        @Override
        public String getDescription() {
          return "register a failing rollback query";
        }
      };
      closing.queryStarted("initial-close", registering);
      closing.registerListener(new SessionListener() {
        @Override
        public void onAfterTxRollback(Transaction transaction) {
          events.add("rollback");
        }

        @Override
        public void onClose(DatabaseSessionEmbedded database) {
          events.add("session");
          assertTrue(database.getTransactionInternal() instanceof FrontendTransactionNoTx);
          assertTrue(database.getActiveQueries().isEmpty());
        }
      });
      try {
        assertSame(rollbackFailure, assertThrows(AssertionError.class, closing::close));
        assertEquals(sameFailure ? List.of() : List.of(initialFailure),
            List.of(rollbackFailure.getSuppressed()));
        assertEquals(List.of("query", "query", "rollback", "session"), events);
        assertEquals(DatabaseSessionEmbedded.STATUS.CLOSED, closing.getStatus());
        assertEquals(TXSTATUS.ROLLED_BACK, tx.getStatus());
        assertFalse(operation.isActive());
        verify(storage, times(1)).close(closing);
        closing.close();
        assertEquals(4, events.size());
      } finally {
        closing.close();
        session.activateOnCurrentThread();
      }
    }
  }

  /**
   * An unrelated storage-close failure stays primary and retains both earlier failures exactly
   * once. Shared identity among any of the three failures must not cause self-suppression.
   */
  @Test
  public void laterSessionTeardownFailureRetainsBulkAndRollbackErrors() {
    for (int scenario = 0; scenario < 4; scenario++) {
      var realStorage = session.getStorage();
      var storage = mock(AbstractStorage.class, delegatesTo(realStorage));
      var closing = openSessionWithStorageProbe(storage);
      var tx = closing.begin();
      var bulkFailure = new AssertionError("initial bulk close");
      var rollbackFailure = scenario == 3 ? bulkFailure : new AssertionError("rollback listener");
      Throwable laterFailure = switch (scenario) {
        case 1 -> bulkFailure;
        case 2 -> rollbackFailure;
        default -> new IllegalStateException("storage close");
      };
      var events = new ArrayList<String>();
      var query = failingQuery(bulkFailure, events);
      closing.queryStarted("initial-close", query);
      closing.registerListener(new SessionListener() {
        @Override
        public void onAfterTxRollback(Transaction transaction) {
          events.add("rollback");
          throw rollbackFailure;
        }

        @Override
        public void onClose(DatabaseSessionEmbedded database) {
          events.add("session");
        }
      });
      doAnswer(invocation -> {
        realStorage.close(closing);
        throw laterFailure;
      }).when(storage).close(closing);
      try {
        assertSame(laterFailure, assertThrows(Throwable.class, closing::close));
        var expectedSuppressed = new ArrayList<Throwable>();
        if (bulkFailure != laterFailure) {
          expectedSuppressed.add(bulkFailure);
        }
        if (rollbackFailure != laterFailure && rollbackFailure != bulkFailure) {
          expectedSuppressed.add(rollbackFailure);
        }
        assertEquals(expectedSuppressed, List.of(laterFailure.getSuppressed()));
        assertEquals(List.of("query", "rollback", "session"), events);
        assertEquals(TXSTATUS.ROLLED_BACK, tx.getStatus());
        assertEquals(DatabaseSessionEmbedded.STATUS.CLOSED, closing.getStatus());
        closing.close();
        verify(storage, times(1)).close(closing);
        assertEquals(3, events.size());
      } finally {
        closing.close();
        session.activateOnCurrentThread();
      }
    }
  }

  /** A later storage-close failure stays primary without self-suppression or a stale retry failure. */
  @Test
  public void sessionCloseKeepsLaterFailurePrimaryAndDiscardsBulkFailure() {
    for (boolean sameFailure : new boolean[] {false, true}) {
      var realStorage = session.getStorage();
      var storage = mock(AbstractStorage.class, delegatesTo(realStorage));
      var closing = openSessionWithStorageProbe(storage);
      var tx = closing.begin();
      var bulkFailure = new AssertionError("session bulk close");
      Throwable laterFailure =
          sameFailure ? bulkFailure : new IllegalStateException("storage close");
      var events = new ArrayList<String>();
      var query = failingQuery(bulkFailure, events);
      closing.queryStarted("session-close", query);
      doAnswer(invocation -> {
        realStorage.close(closing);
        throw laterFailure;
      }).when(storage).close(closing);
      try {
        assertSame(laterFailure, assertThrows(Throwable.class, closing::close));
        assertEquals(sameFailure ? List.of() : List.of(bulkFailure),
            List.of(laterFailure.getSuppressed()));
        assertEquals(TXSTATUS.ROLLED_BACK, tx.getStatus());
        assertEquals(DatabaseSessionEmbedded.STATUS.CLOSED, closing.getStatus());
        closing.close();
        verify(storage, times(1)).close(closing);
        assertEquals(List.of("query"), events);
      } finally {
        closing.close();
        session.activateOnCurrentThread();
      }
    }
  }

  /** Rollback reports the bulk error after listeners run and a sequence-drop listener unregisters. */
  @Test
  public void rollbackBulkFailureStillRunsListenersAndUnregistersSequenceDrop() {
    var sequences = session.getMetadata().getSequenceLibrary();
    session.executeInTx(tx -> sequences.createSequence("rollbackProbe",
        DBSequence.SEQUENCE_TYPE.ORDERED, new DBSequence.CreateParams().setDefaults()));
    for (boolean fatal : new boolean[] {false, true}) {
      var tx = session.begin();
      var baseline = listenerSnapshot();
      sequences.dropSequence("rollbackProbe");
      assertEquals(baseline.size() + 1, listenerSnapshot().size());
      var events = new ArrayList<String>();
      Throwable failure = fatal ? new AssertionError("rollback bulk close")
          : new IllegalStateException("rollback bulk close");
      var query = failingQuery(failure, events);
      session.queryStarted("rollback-close", query);
      var listener = new SessionListener() {
        @Override
        public void onAfterTxRollback(Transaction transaction) {
          events.add("rollback");
          assertTrue(session.getTransactionInternal() instanceof FrontendTransactionNoTx);
          assertEquals(TXSTATUS.ROLLED_BACK, tx.getStatus());
        }
      };
      session.registerListener(listener);
      try {
        assertSame(failure, assertThrows(Throwable.class, session::rollback));
        assertEquals(List.of("query", "rollback"), events);
        tx.close();
        assertEquals(List.of("query", "rollback"), events);
      } finally {
        session.unregisterListener(listener);
      }
      assertEquals(baseline, listenerSnapshot());
      session.begin();
      assertTrue(sequences.getSequence("rollbackProbe") != null);
      session.commit();
    }
  }

  /** Plain rollback restores a dropped sequence and runs every listener despite self-removal. */
  @Test
  public void plainRollbackAfterSequenceDropRunsAllListenersWithoutException() {
    var sequences = session.getMetadata().getSequenceLibrary();
    session.executeInTx(tx -> sequences.createSequence("plainRollbackProbe",
        DBSequence.SEQUENCE_TYPE.ORDERED, new DBSequence.CreateParams().setDefaults()));
    var tx = session.begin();
    var baseline = listenerSnapshot();
    sequences.dropSequence("plainRollbackProbe");
    assertEquals(baseline.size() + 1, listenerSnapshot().size());
    var callbacks = new ArrayList<Integer>();
    var observers = new ArrayList<SessionListener>();
    for (int index = 0; index < 2; index++) {
      var observerId = index;
      var observer = new SessionListener() {
        @Override
        public void onAfterTxRollback(Transaction transaction) {
          callbacks.add(observerId);
        }
      };
      observers.add(observer);
      session.registerListener(observer);
    }
    try {
      session.rollback();
      assertEquals(2, callbacks.size());
      assertEquals(Set.of(0, 1), new HashSet<>(callbacks));
      assertEquals(TXSTATUS.ROLLED_BACK, tx.getStatus());
      assertTrue(session.getTransactionInternal() instanceof FrontendTransactionNoTx);
    } finally {
      observers.forEach(session::unregisterListener);
    }
    assertEquals(baseline, listenerSnapshot());
    session.begin();
    assertTrue(sequences.getSequence("plainRollbackProbe") != null);
    session.commit();
  }

  /** Commit failure stays primary when completed rollback also reports a bulk-close failure. */
  @Test
  public void failedCommitPreservesOriginalAndSuppressesRollbackBulkFailure() {
    for (int scenario = 0; scenario < 3; scenario++) {
      var storage = mock(AbstractStorage.class, delegatesTo(session.getStorage()));
      var committing = openSessionWithStorageProbe(storage);
      var tx = committing.begin();
      var operation = tx.getAtomicOperation();
      tx.newVertex("V"); // Ensure doCommit enters the storage write path.
      var commitFailure = new IllegalStateException("storage commit");
      Throwable closeFailure = scenario == 0 ? commitFailure
          : scenario == 1 ? new AssertionError("rollback bulk close")
              : new IllegalStateException("rollback bulk close");
      var events = new ArrayList<String>();
      var query = failingQuery(closeFailure, events);
      committing.queryStarted("commit-close", query);
      committing.registerListener(new SessionListener() {
        @Override
        public void onAfterTxRollback(Transaction transaction) {
          events.add("rollback");
        }
      });
      doThrow(commitFailure).when(storage).commit(tx);
      try {
        assertSame(commitFailure, assertThrows(IllegalStateException.class, tx::commitInternal));
        assertEquals(scenario == 0 ? List.of() : List.of(closeFailure),
            List.of(commitFailure.getSuppressed()));
        assertEquals(List.of("query", "rollback"), events);
        assertEquals(TXSTATUS.ROLLED_BACK, tx.getStatus());
        assertTrue(committing.getTransactionInternal() instanceof FrontendTransactionNoTx);
        assertTrue(committing.getActiveQueries().isEmpty());
        assertFalse(operation.isActive());
        assertNull(tx.getAtomicOperation());
        tx.close();
        assertEquals(List.of("query", "rollback"), events);
        committing.begin();
        committing.commit();
      } finally {
        committing.close();
        session.activateOnCurrentThread();
      }
    }
  }

  /**
   * A failed commit propagates a rollback listener Error, not the commit exception. A distinct
   * bulk-close failure remains suppressed on the listener Error, and a shared failure is not
   * self-suppressed. Every case finishes transaction cleanup in both registry retention modes.
   */
  @Test
  public void failedCommitPropagatesRollbackListenerErrorAndRetainsBulkFailure() {
    for (int scenario = 0; scenario < 4; scenario++) {
      var storage = mock(AbstractStorage.class, delegatesTo(session.getStorage()));
      var committing = openSessionWithStorageProbe(storage);
      var tx = committing.begin();
      var operation = tx.getAtomicOperation();
      tx.newVertex("V"); // Ensure doCommit enters the storage write path.
      var commitFailure = new IllegalStateException("storage commit");
      var listenerFailure = new AssertionError("rollback listener");
      Throwable closeFailure = switch (scenario) {
        case 0 -> null;
        case 1 -> new IllegalStateException("rollback bulk close");
        case 2 -> new AssertionError("rollback bulk close");
        default -> listenerFailure;
      };
      var events = new ArrayList<String>();
      var query = closeFailure == null ? null : failingQuery(closeFailure, events);
      if (query != null) {
        committing.queryStarted("commit-close", query);
      }
      var listener = new SessionListener() {
        @Override
        public void onAfterTxRollback(Transaction transaction) {
          events.add("rollback");
          throw listenerFailure;
        }
      };
      committing.registerListener(listener);
      doThrow(commitFailure).when(storage).commit(tx);
      try {
        assertSame(listenerFailure, assertThrows(AssertionError.class, tx::commitInternal));
        assertEquals(closeFailure == null || closeFailure == listenerFailure ? List.of()
            : List.of(closeFailure), List.of(listenerFailure.getSuppressed()));
        assertEquals(List.of(), List.of(commitFailure.getSuppressed()));
        var expectedEvents = closeFailure == null ? List.of("rollback")
            : List.of("query", "rollback");
        assertEquals(expectedEvents, events);
        assertEquals(TXSTATUS.ROLLED_BACK, tx.getStatus());
        assertTrue(committing.getTransactionInternal() instanceof FrontendTransactionNoTx);
        assertTrue(committing.getActiveQueries().isEmpty());
        assertFalse(operation.isActive());
        assertNull(tx.getAtomicOperation());
        tx.close();
        assertEquals(expectedEvents, events);
        committing.begin();
        committing.commit();
      } finally {
        committing.unregisterListener(listener);
        committing.close();
        session.activateOnCurrentThread();
      }
    }
  }

  /** Every helper retires real queries in both retention modes, with empty and non-empty rows. */
  @Test
  public void consumingHelpersDeregisterAndKeepExecutionPlanReadable() {
    session.createVertexClass("HelperVertex");
    session.createEdgeClass("HelperEdge");
    session.executeInTx(tx -> tx.newVertex("HelperVertex")
        .addEdge(tx.newVertex("HelperVertex"), "HelperEdge"));
    session.begin();
    try (var logs = LogRecordCollector.attachTo(DatabaseSessionEmbedded.class)) {
      for (var helper : consumingHelpers()) {
        for (var empty : new boolean[] {false, true}) {
          // This local variable strongly owns the query until all registry assertions finish.
          var rows = session.query("select from " + helper.target()
              + (empty ? " where 1 = 0" : ""));
          assertEquals(helper.name(), 1, session.getActiveQueries().size());
          var plan = rows.getExecutionPlan();
          var description = plan.prettyPrint(0, 2);
          helper.consume().accept(rows);
          assertTrue(helper.name(), rows.isClosed());
          assertTrue(helper.name(), session.getActiveQueries().isEmpty());
          assertSame(plan, rows.getExecutionPlan());
          assertEquals(description, rows.getExecutionPlan().prettyPrint(0, 2));
          rows.close();
          assertTrue(session.getActiveQueries().isEmpty());
        }
      }
      assertFalse(logs.warnedWithAll("open command/query result sets"));
    } finally {
      session.rollback();
    }
  }

  /** A reading failure reaches the caller and every consuming helper still deregisters its query. */
  @Test
  public void consumingHelpersDeregisterWhenReadingThrows() {
    for (var helper : consumingHelpers()) {
      var plan = mock(InternalExecutionPlan.class);
      var stream = mock(ExecutionStream.class);
      when(plan.getContext()).thenReturn(new BasicCommandContext());
      when(plan.start()).thenReturn(stream);
      var failure = new IllegalStateException("reading " + helper.name());
      when(stream.hasNext(any())).thenThrow(failure);
      var rows = new LocalResultSet(session, plan);
      rows.addLifecycleListener(session);
      session.queryStarted(rows.getQueryId(), rows);
      assertSame(failure, assertThrows(IllegalStateException.class,
          () -> helper.consume().accept(rows)));
      assertTrue(helper.name(), rows.isClosed());
      assertTrue(helper.name(), session.getActiveQueries().isEmpty());
      rows.close();
      verify(stream, times(1)).close(any());
      verify(plan, times(1)).close();
    }
  }

  /** A user callback failure closes entity, vertex and edge queries before it escapes. */
  @Test
  public void forEachHelpersDeregisterWhenCallbackThrows() {
    session.createVertexClass("CallbackVertex");
    session.createEdgeClass("CallbackEdge");
    session.executeInTx(tx -> tx.newVertex("CallbackVertex")
        .addEdge(tx.newVertex("CallbackVertex"), "CallbackEdge"));
    var failure = new IllegalArgumentException("callback");
    List<Consumer<ResultSet>> helpers = List.of(
        rows -> rows.forEachEntity(entity -> {
          throw failure;
        }),
        rows -> rows.forEachVertex(vertex -> {
          throw failure;
        }),
        rows -> rows.forEachEdge(edge -> {
          throw failure;
        }));
    session.begin();
    try {
      for (int index = 0; index < helpers.size(); index++) {
        var rows = session.query("select from "
            + (index == 2 ? "CallbackEdge" : "CallbackVertex"));
        var helper = helpers.get(index);
        assertSame(failure, assertThrows(IllegalArgumentException.class,
            () -> helper.accept(rows)));
        assertTrue(rows.isClosed());
        assertTrue(session.getActiveQueries().isEmpty());
        rows.close();
      }
    } finally {
      session.rollback();
    }
  }

  /** Cleanup failures retire direct and decorated queries once and keep the original error primary. */
  @Test
  public void closeFailuresNotifyListenersAndNeverRetryCleanup() {
    for (int scenario = 0; scenario < 7; scenario++) {
      var plan = mock(InternalExecutionPlan.class);
      var stream = mock(ExecutionStream.class);
      when(plan.getContext()).thenReturn(new BasicCommandContext());
      when(plan.start()).thenReturn(stream);
      Throwable failure = scenario % 2 == 0 ? new IllegalStateException("cleanup")
          : new AssertionError("cleanup");
      var failPlan = scenario == 2;
      if (failPlan) {
        doThrow(failure).when(plan).close();
      } else {
        doThrow(failure).when(stream).close(any());
      }
      var local = new LocalResultSet(session, plan);
      var decorated = new LocalResultSetLifecycleDecorator(local);
      boolean useDecorator = scenario >= 3 && scenario < 6;
      ResultSet rows = useDecorator ? decorated : local;
      var id = useDecorator ? decorated.getQueryId() : local.getQueryId();
      if (useDecorator) {
        decorated.addLifecycleListener(session);
      } else {
        local.addLifecycleListener(session);
      }
      session.queryStarted(id, rows);
      // Later listener failures must not replace a cleanup failure or suppress it on itself.
      if (scenario == 1 || scenario >= 4) {
        // Both direct and decorated result sets must avoid self-suppression.
        var listenerFailure = scenario >= 5 ? failure : new AssertionError("listener");
        QueryLifecycleListener listener = queryId -> {
          if (listenerFailure instanceof Error error) {
            throw error;
          }
          throw (RuntimeException) listenerFailure;
        };
        if (useDecorator) {
          decorated.addLifecycleListener(listener);
        } else {
          local.addLifecycleListener(listener);
        }
      }
      assertSame(failure, assertThrows(Throwable.class, rows::toList));
      assertTrue(rows.isClosed());
      assertFalse(rows.hasNext());
      assertNull(local.getBoundToSession());
      assertTrue(session.getActiveQueries().isEmpty());
      assertEquals(scenario == 1 || scenario == 4 ? 1 : 0, failure.getSuppressed().length);
      rows.close();
      verify(stream, times(1)).close(any());
      verify(plan, times(failPlan ? 1 : 0)).close();
    }
  }

  /**
   * A throwing first listener cannot block registry removal or later listeners. Cleanup remains
   * primary when it fails, otherwise the first listener failure is primary and later ones suppressed.
   */
  @Test
  public void throwingFirstListenerStillNotifiesSessionAndRemainingListeners() {
    for (var decorate : new boolean[] {false, true}) {
      for (int cleanupScenario = 0; cleanupScenario < 3; cleanupScenario++) {
        for (var listenerError : new boolean[] {false, true}) {
          var plan = mock(InternalExecutionPlan.class);
          var stream = mock(ExecutionStream.class);
          when(plan.getContext()).thenReturn(new BasicCommandContext());
          when(plan.start()).thenReturn(stream);
          Throwable cleanupFailure = cleanupScenario == 0 ? null
              : cleanupScenario == 1 ? new IllegalStateException("cleanup")
                  : new AssertionError("cleanup");
          if (cleanupFailure != null) {
            doThrow(cleanupFailure).when(stream).close(any());
          }
          var local = new LocalResultSet(session, plan);
          var decorator = new LocalResultSetLifecycleDecorator(local);
          ResultSet rows = decorate ? decorator : local;
          Consumer<QueryLifecycleListener> addListener = decorate
              ? decorator::addLifecycleListener : local::addLifecycleListener;
          var id = decorate ? decorator.getQueryId() : local.getQueryId();
          Throwable firstFailure = listenerError ? new AssertionError("first listener")
              : new IllegalStateException("first listener");
          var laterFailure = new IllegalArgumentException("later listener");
          var calls = new ArrayList<String>();
          QueryLifecycleListener first = queryId -> {
            calls.add("first");
            if (firstFailure instanceof Error error) {
              throw error;
            }
            throw (RuntimeException) firstFailure;
          };
          // Register the throwing listener BEFORE the real session listener.
          addListener.accept(first);
          addListener.accept(session);
          addListener.accept(queryId -> {
            calls.add("later");
            throw laterFailure;
          });
          // The same throwable must not be suppressed onto itself.
          addListener.accept(first);
          addListener.accept(queryId -> calls.add("last"));
          session.queryStarted(id, rows);
          var primary = cleanupFailure == null ? firstFailure : cleanupFailure;
          assertSame(primary, assertThrows(Throwable.class, rows::close));
          assertEquals(List.of("first", "later", "first", "last"), calls);
          assertTrue(rows.isClosed());
          assertNull(local.getBoundToSession());
          assertTrue(session.getActiveQueries().isEmpty());
          assertEquals(1, firstFailure.getSuppressed().length);
          assertSame(laterFailure, firstFailure.getSuppressed()[0]);
          if (cleanupFailure != null) {
            assertEquals(1, cleanupFailure.getSuppressed().length);
            assertSame(firstFailure, cleanupFailure.getSuppressed()[0]);
          }
          rows.close();
          assertEquals(4, calls.size());
          verify(stream, times(1)).close(any());
          verify(plan, times(cleanupFailure == null ? 1 : 0)).close();
        }
      }
    }
  }

  /** The decorator alone guards delegation, even if its inner result set does not guard close. */
  @Test
  public void decoratorNeverDelegatesSecondCloseToCountingInnerResultSet() {
    for (int scenario = 0; scenario < 3; scenario++) {
      var inner = mock(ResultSet.class);
      Throwable failure = scenario == 0 ? null : scenario == 1
          ? new IllegalStateException("inner close") : new AssertionError("inner close");
      if (failure != null) {
        doThrow(failure).when(inner).close();
      }
      var rows = new LocalResultSetLifecycleDecorator(inner);
      var calls = new ArrayList<String>();
      rows.addLifecycleListener(calls::add);
      if (failure == null) {
        rows.close();
      } else {
        assertSame(failure, assertThrows(Throwable.class, rows::close));
      }
      assertTrue(rows.isClosed());
      rows.close();
      verify(inner, times(1)).close();
      assertEquals(List.of(rows.getQueryId()), calls);
    }
  }

  /** Real leaked result sets still trigger the configured warning and explicit close retires them. */
  @Test
  public void genuinelyOpenResultSetsStillWarn() {
    session.begin();
    try (var logs = LogRecordCollector.attachTo(DatabaseSessionEmbedded.class);
        var first = session.query("select 1 as answer");
        var second = session.query("select 2 as answer")) {
      assertFalse(logs.warnedWithAll("open command/query result sets"));
      try (var third = session.query("select 3 as answer")) {
        assertEquals(3, session.getActiveQueries().size());
        assertTrue(logs.warnedWithAll("2", "open command/query result sets"));
        assertTrue(first.hasNext());
        assertTrue(second.hasNext());
        assertTrue(third.hasNext());
      }
    } finally {
      assertTrue(session.getActiveQueries().isEmpty());
      session.rollback();
    }
  }

  private record ConsumingHelper(String name, String target, Consumer<ResultSet> consume) {
  }

  private static List<ConsumingHelper> consumingHelpers() {
    return List.of(
        new ConsumingHelper("toList", "HelperVertex", ResultSet::toList),
        new ConsumingHelper("detach", "HelperVertex", ResultSet::detach),
        new ConsumingHelper("toDetachedList", "HelperVertex", ResultSet::toDetachedList),
        new ConsumingHelper("toEntityList", "HelperVertex", ResultSet::toEntityList),
        new ConsumingHelper("toVertexList", "HelperVertex", ResultSet::toVertexList),
        new ConsumingHelper("toRidList", "HelperVertex", ResultSet::toRidList),
        new ConsumingHelper("toEdgeList", "HelperEdge", ResultSet::toEdgeList),
        new ConsumingHelper("forEachEntity", "HelperVertex", rows -> rows.forEachEntity(e -> {
        })),
        new ConsumingHelper("forEachVertex", "HelperVertex", rows -> rows.forEachVertex(v -> {
        })),
        new ConsumingHelper("forEachEdge", "HelperEdge", rows -> rows.forEachEdge(e -> {
        })));
  }

  private Set<SessionListener> listenerSnapshot() {
    var snapshot = new HashSet<SessionListener>();
    session.getListenersCopy().forEach(snapshot::add);
    return snapshot;
  }

  private DatabaseSessionEmbedded openSessionWithStorageProbe(AbstractStorage storage) {
    // Delegate storage operations to the real fixture while observing the teardown/commit boundary.
    storage.open(session, null, null, session.getConfiguration());
    var observed = new DatabaseSessionEmbedded(storage, serverMode);
    observed.init((YouTrackDBConfigImpl) session.getConfig(), session.getSharedContext());
    observed.internalOpen(adminUser, adminPassword);
    return observed;
  }

  private static RegisteredQuery failingQuery(Throwable failure, List<String> events) {
    return new RegisteredQuery() {
      @Override
      public void close() {
        events.add("query");
        if (failure instanceof Error error) {
          throw error;
        }
        throw (RuntimeException) failure;
      }

      @Override
      public String getDescription() {
        return "failing cleanup probe";
      }
    };
  }

  private DatabaseSessionEmbedded registryWithThreshold(int threshold) {
    var config = session.getConfiguration();
    var key = GlobalConfiguration.QUERY_RESULT_SET_OPEN_WARNING_THRESHOLD;
    var previous = config.setValue(key, threshold);
    try {
      // A registry-only session uses the real constructor without opening storage resources.
      return new DatabaseSessionEmbedded(session.getStorage(), serverMode);
    } finally {
      config.setValue(key, previous);
    }
  }

  private static String warning(DatabaseSessionEmbedded registry, int count) {
    return logPrefix("WARNING", registry) + "This database instance has " + count
        + " open command/query result sets, please make sure you close them with ResultSet.close()";
  }

  private static String logPrefix(String level, DatabaseSessionEmbedded registry) {
    var dbName = registry.getDatabaseName();
    return level + " youtrackdb:" + dbName + " [" + dbName + "] ";
  }

  private static final class TestQuery implements RegisteredQuery {

    private final DatabaseSessionEmbedded registry;
    private final String id;
    private final String description;
    private int closes;

    private TestQuery(DatabaseSessionEmbedded registry, String id, String description) {
      this.registry = registry;
      this.id = id;
      this.description = description;
    }

    @Override
    public void close() {
      closes++;
      registry.queryClosed(id);
    }

    @Override
    public String getDescription() {
      return description;
    }
  }
}
