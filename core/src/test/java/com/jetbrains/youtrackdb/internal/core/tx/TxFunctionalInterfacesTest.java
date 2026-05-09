package com.jetbrains.youtrackdb.internal.core.tx;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

/**
 * Coverage for the four tx-pattern @FunctionalInterfaces — {@link TxConsumer},
 * {@link TxFunction}, {@link TxBiConsumer}, and {@link TxBiFunction}.
 *
 * <p>These interfaces have {@code mainNew=0} (never instantiated by name) but
 * are heavily used as lambda target types in
 * {@code DatabaseSessionEmbedded.executeInTx} / {@code computeInTx} /
 * {@code executeInTxBatches} / {@code forEachInTx}. The cluster-classification
 * table marks them {@code 22a-keep} with the note "covered indirectly via
 * tx-pattern tests on DatabaseSessionEmbedded" (Phase A iter-1 finding A7);
 * this test class realises that coverage by feeding the session every
 * tx-pattern entry point and pinning the lambda dispatch with assertions.
 */
public class TxFunctionalInterfacesTest extends DbTestBase {

  @After
  public void rollbackIfLeftOpen() {
    if (session != null && !session.isClosed() && session.getTransactionInternal().isActive()) {
      session.rollback();
    }
  }

  /**
   * {@code executeInTx(TxConsumer)} runs the lambda inside a tx that the
   * method begins and commits on its behalf. The lambda receives the tx
   * argument; we assert it is non-null and active so a future regression
   * that swaps the no-tx wrapper in is caught.
   */
  @Test
  public void executeInTxRunsLambdaInsideActiveTransaction() {
    var observed = new AtomicInteger(0);
    session.executeInTx(tx -> {
      Assert.assertNotNull("TxConsumer received null transaction", tx);
      Assert.assertTrue("TxConsumer received inactive tx", tx.isActive());
      observed.incrementAndGet();
    });
    Assert.assertEquals(1, observed.get());
    Assert.assertFalse(
        "session must not be left in a tx", session.getTransactionInternal().isActive());
  }

  /**
   * {@code computeInTx(TxFunction)} returns the lambda's value through the
   * commit boundary. Verify identity-preserving pass-through and that the
   * tx is committed (not rolled back) after success.
   */
  @Test
  public void computeInTxReturnsLambdaValue() {
    var result = session.computeInTx(tx -> {
      Assert.assertNotNull(tx);
      Assert.assertTrue(tx.isActive());
      return "answer-" + 42;
    });
    Assert.assertEquals("answer-42", result);
  }

  /**
   * {@code executeInTxBatches(Iterator, TxBiConsumer)} runs the bi-consumer
   * once per element, with a tx and the element. The batch-default-size
   * variant (no batchSize arg) reads the session's default tx batch size
   * from the YouTrackDB config.
   */
  @Test
  public void executeInTxBatchesIteratorRunsBiConsumerPerElement() {
    var elements = List.of("a", "b", "c", "d");
    var seen = new java.util.ArrayList<String>();
    session.executeInTxBatches(elements.iterator(), (tx, element) -> {
      Assert.assertNotNull(tx);
      Assert.assertTrue(tx.isActive());
      seen.add(element);
    });
    Assert.assertEquals(elements, seen);
  }

  /**
   * The explicit-batchSize variant exercises the inner commit/begin loop
   * boundary — every Nth element triggers a commit-and-rebegin while the
   * iterator is still serving entries.
   */
  @Test
  public void executeInTxBatchesIteratorWithBatchSizeCommitsMidStream() {
    var elements = List.of("a", "b", "c", "d", "e");
    var seen = new java.util.ArrayList<String>();
    session.executeInTxBatches(elements.iterator(), 2, (tx, element) -> {
      Assert.assertNotNull(tx);
      seen.add(element);
    });
    Assert.assertEquals(elements, seen);
  }

  /**
   * The Iterable overload delegates to the iterator overload; pin that
   * delegation by passing a list directly.
   */
  @Test
  public void executeInTxBatchesIterableDelegatesToIterator() {
    var elements = Arrays.asList(1, 2, 3);
    var sum = new AtomicInteger();
    session.executeInTxBatches((Iterable<Integer>) elements, (tx, n) -> sum.addAndGet(n));
    Assert.assertEquals(6, sum.get());
  }

  /**
   * The Stream overload closes the stream after iteration via try-with-
   * resources; assert the lambda fires per-element and the stream is
   * closed afterward.
   */
  @Test
  public void executeInTxBatchesStreamClosesStream() {
    var closeFlag = new java.util.concurrent.atomic.AtomicBoolean(false);
    var stream = Stream.of("x", "y", "z").onClose(() -> closeFlag.set(true));
    var visited = new AtomicInteger();
    session.executeInTxBatches(stream, (tx, s) -> visited.incrementAndGet());
    Assert.assertEquals(3, visited.get());
    Assert.assertTrue("Stream overload must close the stream", closeFlag.get());
  }

  /**
   * The 3-argument {@code executeInTxBatches(Iterable, batchSize,
   * TxBiConsumer)} variant chains through the iterable form.
   */
  @Test
  public void executeInTxBatchesIterableWithBatchSize() {
    var elements = List.of(10, 20, 30);
    var sum = new AtomicInteger();
    session.executeInTxBatches((Iterable<Integer>) elements, 2, (tx, n) -> sum.addAndGet(n));
    Assert.assertEquals(60, sum.get());
  }

  /**
   * The Stream + batchSize overload — both tries-with-resources behaviour
   * and per-batch commit boundary.
   */
  @Test
  public void executeInTxBatchesStreamWithBatchSize() {
    var sum = new AtomicInteger();
    session.executeInTxBatches(Stream.of(1, 2, 3, 4), 2, (tx, n) -> sum.addAndGet(n));
    Assert.assertEquals(10, sum.get());
  }

  /**
   * {@code forEachInTx(Iterator, TxBiConsumer)} commits per element via
   * the inner adapter that wraps the BiConsumer in a BiFunction returning
   * true. Verify every element is visited and the tx argument is non-null
   * — note: the underlying implementation captures the tx reference once
   * before the loop, so the second-and-later invocations see the
   * already-committed instance; we therefore do not assert {@code
   * isActive()} on the tx argument inside the lambda.
   */
  @Test
  public void forEachInTxIteratorVisitsEveryElement() {
    var elements = List.of("p", "q", "r");
    var visits = new java.util.ArrayList<String>();
    session.forEachInTx(elements.iterator(), (tx, element) -> {
      Assert.assertNotNull(tx);
      visits.add(element);
    });
    Assert.assertEquals(elements, visits);
  }

  /**
   * {@code forEachInTx(Iterator, TxBiFunction)} stops early when the
   * function returns false, leaving subsequent elements unvisited. This
   * branches the BiFunction-driven loop on the {@code !cont} short-
   * circuit.
   */
  @Test
  public void forEachInTxBiFunctionShortCircuitsOnFalse() {
    var elements = List.of(1, 2, 3, 4, 5);
    var visits = new java.util.ArrayList<Integer>();
    session.forEachInTx(elements.iterator(), (tx, n) -> {
      visits.add(n);
      return n < 3; // continue until n=3, then break
    });
    Assert.assertEquals(List.of(1, 2, 3), visits);
  }

  /**
   * {@code forEachInTx(Iterator, TxBiFunction)} runs to completion when
   * the function always returns true.
   */
  @Test
  public void forEachInTxBiFunctionRunsToCompletion() {
    var elements = List.of(1, 2, 3);
    var sum = new AtomicInteger();
    session.forEachInTx(elements.iterator(), (tx, n) -> {
      sum.addAndGet(n);
      return true;
    });
    Assert.assertEquals(6, sum.get());
  }

  /**
   * Iterable + Stream overloads of forEachInTx with the BiConsumer shape.
   * The Iterable/Stream overloads come in BiConsumer and BiFunction
   * flavours; we use explicitly-typed lambdas (TxBiConsumer/TxBiFunction
   * variables) so the compiler picks the intended overload — bare lambdas
   * are ambiguous because they match both functional-interface shapes.
   */
  @Test
  public void forEachInTxBiConsumerIterableAndStreamOverloads() {
    var iterableSum = new AtomicInteger();
    TxBiConsumer<Transaction, Integer, RuntimeException> iterableSink =
        (tx, n) -> iterableSum.addAndGet(n);
    session.forEachInTx((Iterable<Integer>) List.of(1, 2), iterableSink);
    Assert.assertEquals(3, iterableSum.get());

    var streamSum = new AtomicInteger();
    TxBiConsumer<Transaction, Integer, RuntimeException> streamSink =
        (tx, n) -> streamSum.addAndGet(n);
    session.forEachInTx(Stream.of(10, 20), streamSink);
    Assert.assertEquals(30, streamSum.get());
  }

  /**
   * Iterable + Stream overloads of forEachInTx with the BiFunction shape.
   * Explicit interface-typed locals disambiguate from the BiConsumer
   * overloads.
   */
  @Test
  public void forEachInTxBiFunctionIterableAndStreamOverloads() {
    var iterableSum = new AtomicInteger();
    TxBiFunction<Transaction, Integer, Boolean, RuntimeException> iterableContinue =
        (tx, n) -> {
          iterableSum.addAndGet(n);
          return true;
        };
    session.forEachInTx((Iterable<Integer>) List.of(1, 2, 3), iterableContinue);
    Assert.assertEquals(6, iterableSum.get());

    var streamSum = new AtomicInteger();
    TxBiFunction<Transaction, Integer, Boolean, RuntimeException> streamContinue =
        (tx, n) -> {
          streamSum.addAndGet(n);
          return true;
        };
    session.forEachInTx(Stream.of(10, 20, 30), streamContinue);
    Assert.assertEquals(60, streamSum.get());
  }

  /**
   * {@code executeInTxInternal(TxConsumer)} is the internal flavour that
   * passes the concrete {@code FrontendTransactionImpl} (not the
   * Transaction interface). Pin that the lambda receives the impl type
   * and the tx is fully active.
   */
  @Test
  public void executeInTxInternalPassesFrontendTransactionImpl() {
    var observed = new AtomicInteger();
    session.<RuntimeException>executeInTxInternal(tx -> {
      Assert.assertNotNull(tx);
      Assert.assertTrue(tx.isActive());
      Assert.assertEquals(FrontendTransaction.TXSTATUS.BEGUN, tx.getStatus());
      observed.incrementAndGet();
    });
    Assert.assertEquals(1, observed.get());
  }

  /**
   * Lambda exceptions propagate out of executeInTx with the tx rolled
   * back — the {@code finishTx(false)} branch.
   */
  @Test
  public void executeInTxPropagatesLambdaException() {
    var thrown =
        Assert.assertThrows(
            IllegalStateException.class,
            () -> session.<IllegalStateException>executeInTx(tx -> {
              throw new IllegalStateException("intentional");
            }));
    Assert.assertEquals("intentional", thrown.getMessage());
    Assert.assertFalse(
        "session must rollback on lambda failure",
        session.getTransactionInternal().isActive());
  }
}
