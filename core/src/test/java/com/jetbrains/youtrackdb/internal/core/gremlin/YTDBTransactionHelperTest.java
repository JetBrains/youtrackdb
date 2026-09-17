package com.jetbrains.youtrackdb.internal.core.gremlin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
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

  private static GenericTransactionFixture genericTransaction() {
    var source = mock(YTDBGraphTraversalSource.class);
    var begunSource = mock(YTDBGraphTraversalSource.class);
    var transaction = mock(Transaction.class);
    when(source.tx()).thenReturn(transaction);
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
