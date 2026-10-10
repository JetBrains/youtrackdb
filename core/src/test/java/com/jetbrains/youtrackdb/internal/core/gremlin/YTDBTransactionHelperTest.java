package com.jetbrains.youtrackdb.internal.core.gremlin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversal;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import org.apache.commons.lang3.function.FailableConsumer;
import org.apache.tinkerpop.gremlin.structure.Transaction;
import org.junit.Test;

/** Verifies that transaction helpers retain generic remote transaction compatibility. */
public class YTDBTransactionHelperTest {

  /** A successful consumer callback commits a non-YouTrackDB transaction. */
  @Test
  public void executeConsumerCommitsGenericTransaction() throws Exception {
    var fixture = genericTransaction();

    FailableConsumer<YTDBGraphTraversalSource, Exception> callback =
        source -> assertSame(fixture.begunSource(), source);

    YTDBTransaction.executeInTX(callback, fixture.source());

    verify(fixture.transaction()).commit();
    verify(fixture.transaction(), never()).rollback();
  }

  /** A failing consumer callback preserves its exception and rolls back the generic transaction. */
  @Test
  public void executeConsumerRollsBackGenericTransactionOnFailure() {
    var fixture = genericTransaction();
    var failure = new Exception("callback failed");

    FailableConsumer<YTDBGraphTraversalSource, Exception> callback = source -> {
      throw failure;
    };

    var thrown = assertThrows(
        Exception.class,
        () -> YTDBTransaction.executeInTX(callback, fixture.source()));

    assertSame(failure, thrown);
    verify(fixture.transaction()).rollback();
    verify(fixture.transaction(), never()).commit();
  }

  /** A successful traversal callback iterates its result and commits a generic transaction. */
  @Test
  public void executeTraversalCommitsGenericTransaction() throws Exception {
    var fixture = genericTransaction();
    YTDBGraphTraversal<?, ?> traversal = mock(YTDBGraphTraversal.class);

    YTDBTransaction.executeInTX(source -> traversal, fixture.source());

    verify(traversal).iterate();
    verify(fixture.transaction()).commit();
    verify(fixture.transaction(), never()).rollback();
  }

  /** A traversal execution failure is preserved and rolls back the generic transaction. */
  @Test
  public void executeTraversalRollsBackGenericTransactionOnFailure() {
    var fixture = genericTransaction();
    YTDBGraphTraversal<?, ?> traversal = mock(YTDBGraphTraversal.class);
    var failure = new IllegalStateException("iteration failed");
    doThrow(failure).when(traversal).iterate();

    var thrown = assertThrows(
        IllegalStateException.class,
        () -> YTDBTransaction.executeInTX(source -> traversal, fixture.source()));

    assertSame(failure, thrown);
    verify(fixture.transaction()).rollback();
    verify(fixture.transaction(), never()).commit();
  }

  /** A successful computation returns its value and commits a generic transaction. */
  @Test
  public void computeReturnsValueAndCommitsGenericTransaction() throws Exception {
    var fixture = genericTransaction();

    var result = YTDBTransaction.computeInTx(source -> "result", fixture.source());

    assertEquals("result", result);
    verify(fixture.transaction()).commit();
    verify(fixture.transaction(), never()).rollback();
  }

  /** A computation failure is preserved and rolls back the generic transaction. */
  @Test
  public void computeRollsBackGenericTransactionOnFailure() {
    var fixture = genericTransaction();
    var failure = new Exception("computation failed");

    var thrown = assertThrows(
        Exception.class,
        () -> YTDBTransaction.computeInTx(source -> {
          throw failure;
        }, fixture.source()));

    assertSame(failure, thrown);
    verify(fixture.transaction()).rollback();
    verify(fixture.transaction(), never()).commit();
  }

  /** Each helper and public entry point must preserve the exact unchecked commit failure. */
  @Test
  public void publicHelpersPreserveRuntimeCommitFailure() {
    for (var helper : Helper.values()) {
      for (var graphEntry : new boolean[] {false, true}) {
        var fixture = genericTransaction();
        var failure = new IllegalArgumentException("commit failed");
        doThrow(failure).when(fixture.transaction()).commit();
        assertSame(failure, assertThrows(RuntimeException.class,
            () -> helper.run(fixture, graphEntry, null)));
        verify(fixture.transaction()).commit();
        verify(fixture.transaction(), never()).rollback();
      }
    }
  }

  /** A defensive checked commit failure is wrapped with its cause on all six public routes. */
  @Test
  public void publicHelpersWrapCheckedCommitFailure() {
    for (var helper : Helper.values()) {
      for (var graphEntry : new boolean[] {false, true}) {
        var fixture = genericTransaction();
        var failure = new Exception("checked commit failure");
        doAnswer(invocation -> {
          throw failure;
        }).when(fixture.transaction()).commit();
        var thrown = assertThrows(IllegalStateException.class,
            () -> helper.run(fixture, graphEntry, null));
        assertEquals("Failed to commit transaction", thrown.getMessage());
        assertSame(failure, thrown.getCause());
        verify(fixture.transaction(), never()).rollback();
      }
    }
  }

  /** Rollback failures must not replace a callback failure on any public helper route. */
  @Test
  public void publicHelpersKeepBodyFailureWhenRollbackFails() {
    for (var helper : Helper.values()) {
      for (var graphEntry : new boolean[] {false, true}) {
        var fixture = genericTransaction();
        var failure = new Exception("body failed");
        doThrow(new IllegalStateException("rollback failed"))
            .when(fixture.transaction()).rollback();
        assertSame(failure, assertThrows(Exception.class,
            () -> helper.run(fixture, graphEntry, failure)));
        verify(fixture.transaction()).rollback();
        verify(fixture.transaction(), never()).commit();
      }
    }
  }

  private enum Helper {
    CONSUMER, TRAVERSAL, COMPUTE;

    void run(GenericTransactionFixture fixture, boolean graphEntry, Exception bodyFailure)
        throws Exception {
      var graph = mock(YTDBGraph.class, CALLS_REAL_METHODS);
      when(graph.traversal()).thenReturn(fixture.source());
      var traversal = mock(YTDBGraphTraversal.class);
      FailableConsumer<YTDBGraphTraversalSource, Exception> body = source -> {
        if (bodyFailure != null) {
          throw bodyFailure;
        }
      };
      switch (this) {
        case CONSUMER -> {
          if (graphEntry) {
            graph.executeInTx(body);
          } else {
            fixture.source().executeInTx(body);
          }
        }
        case TRAVERSAL -> {
          if (graphEntry) {
            graph.autoExecuteInTx(source -> {
              body.accept(source);
              return traversal;
            });
          } else {
            fixture.source().autoExecuteInTx(source -> {
              body.accept(source);
              return traversal;
            });
          }
        }
        case COMPUTE -> {
          if (graphEntry) {
            graph.computeInTx(source -> {
              body.accept(source);
              return "result";
            });
          } else {
            fixture.source().computeInTx(source -> {
              body.accept(source);
              return "result";
            });
          }
        }
      }
    }
  }

  private static GenericTransactionFixture genericTransaction() {
    var source = mock(YTDBGraphTraversalSource.class, CALLS_REAL_METHODS);
    var begunSource = mock(YTDBGraphTraversalSource.class);
    var transaction = mock(Transaction.class);
    doReturn(transaction).when(source).tx();
    when(transaction.begin(YTDBGraphTraversalSource.class)).thenReturn(begunSource);
    when(transaction.isOpen()).thenReturn(true);
    return new GenericTransactionFixture(source, begunSource, transaction);
  }

  private record GenericTransactionFixture(
      YTDBGraphTraversalSource source,
      YTDBGraphTraversalSource begunSource,
      Transaction transaction) {
  }
}
