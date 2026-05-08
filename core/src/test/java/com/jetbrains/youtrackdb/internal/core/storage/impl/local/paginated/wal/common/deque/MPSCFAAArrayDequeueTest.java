package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.deque;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * Tests for {@link MPSCFAAArrayDequeue} — the multi-producer / single-consumer linked
 * deque used by the WAL writer. Coverage is split across:
 *
 * <ul>
 *   <li>Single-threaded sanity checks — empty-queue {@code poll()/peek()} returns
 *       {@code null}; one offer round-trips through one poll.
 *   <li>Cursor traversal — {@code peekFirst()} / {@code next()} / {@code peekLast()} /
 *       {@code prev()} walk a non-empty queue end-to-end without skipping items, and
 *       return {@code null} for an empty queue.
 *   <li>Multi-node growth — offer enough items to span more than one buffer node
 *       ({@code Node.BUFFER_SIZE}=1024 internally) so that the {@code casNext}/segment
 *       advance branches in {@code offer} and the segment-skip branches in {@code poll}
 *       are exercised.
 *   <li><b>Multi-producer / single-consumer concurrency smoke test</b> — 4 producer
 *       threads each offer N items, 1 consumer polls until total {@code 4*N} items are
 *       drained. Validates that under contention every item is visible exactly once
 *       (no losses, no duplicates). Synchronisation uses {@link CountDownLatch}, no
 *       {@code Thread.sleep}, total budget &lt; 5 s.
 * </ul>
 */
public class MPSCFAAArrayDequeueTest {

  /**
   * A freshly-constructed deque is empty: {@code poll()}, {@code peek()},
   * {@code peekFirst()}, {@code peekLast()} all return {@code null}.
   */
  @Test
  public void emptyDequeReturnsNullFromAllReadOperations() {
    var dq = new MPSCFAAArrayDequeue<Integer>();

    assertNull("poll on empty must return null", dq.poll());
    assertNull("peek on empty must return null", dq.peek());
    assertNull("peekFirst on empty must return null", dq.peekFirst());
    assertNull("peekLast on empty must return null", dq.peekLast());
  }

  /**
   * Cursor advance / retreat past the only element returns {@code null}, exercising
   * the end-of-queue branches in {@link MPSCFAAArrayDequeue#next} and
   * {@link MPSCFAAArrayDequeue#prev}.
   */
  @Test
  public void singleElementCursorTraversalTerminates() {
    var dq = new MPSCFAAArrayDequeue<Integer>();
    dq.offer(7);

    var first = dq.peekFirst();
    assertNotNull(first);
    assertEquals(Integer.valueOf(7), first.getItem());
    assertNull("next past last item must be null", MPSCFAAArrayDequeue.next(first));

    var last = dq.peekLast();
    assertNotNull(last);
    assertEquals(Integer.valueOf(7), last.getItem());
    assertNull("prev past first item must be null", MPSCFAAArrayDequeue.prev(last));
  }

  /**
   * {@code peek()} does not consume — repeated {@code peek()} returns the same head;
   * {@code poll()} after {@code peek()} returns the same item; the deque is empty
   * afterwards.
   */
  @Test
  public void peekIsNonDestructive() {
    var dq = new MPSCFAAArrayDequeue<String>();
    dq.offer("only");

    assertEquals("only", dq.peek());
    assertEquals("only", dq.peek());
    assertEquals("only", dq.poll());
    assertNull(dq.peek());
    assertNull(dq.poll());
  }

  /**
   * FIFO ordering is preserved on a single-threaded sequence. Offer 1..N, poll N
   * times, expect 1..N in the same order. N is small (well below
   * {@code Node.BUFFER_SIZE}) so this stays in a single buffer node.
   */
  @Test
  public void singleThreadedFifoOrdering() {
    var dq = new MPSCFAAArrayDequeue<Integer>();
    final var n = 16;
    for (var i = 0; i < n; i++) {
      dq.offer(i);
    }
    for (var i = 0; i < n; i++) {
      assertEquals(Integer.valueOf(i), dq.poll());
    }
    assertNull("queue must be empty after draining", dq.poll());
  }

  /**
   * Cursor walk exercises {@link MPSCFAAArrayDequeue#peekFirst} +
   * {@link MPSCFAAArrayDequeue#next}: visit every offered item in insertion order.
   * Pin the visited count so a regression that skips a slot inside a buffer node
   * fails fast.
   */
  @Test
  public void cursorWalkVisitsEveryItemInOrder() {
    var dq = new MPSCFAAArrayDequeue<Integer>();
    final var n = 32;
    for (var i = 0; i < n; i++) {
      dq.offer(i);
    }

    var seen = 0;
    var cursor = dq.peekFirst();
    while (cursor != null) {
      assertEquals(Integer.valueOf(seen), cursor.getItem());
      seen++;
      cursor = MPSCFAAArrayDequeue.next(cursor);
    }
    assertEquals("cursor walk must visit every item", n, seen);
  }

  /**
   * Reverse cursor walk exercises {@link MPSCFAAArrayDequeue#peekLast} +
   * {@link MPSCFAAArrayDequeue#prev}: visit every offered item in reverse insertion
   * order.
   */
  @Test
  public void reverseCursorWalkVisitsEveryItemInReverseOrder() {
    var dq = new MPSCFAAArrayDequeue<Integer>();
    final var n = 16;
    for (var i = 0; i < n; i++) {
      dq.offer(i);
    }

    var seen = 0;
    var expectedNext = n - 1;
    var cursor = dq.peekLast();
    while (cursor != null) {
      assertEquals(Integer.valueOf(expectedNext), cursor.getItem());
      expectedNext--;
      seen++;
      cursor = MPSCFAAArrayDequeue.prev(cursor);
    }
    assertEquals("reverse cursor walk must visit every item", n, seen);
  }

  /**
   * Offer enough items to span more than one buffer node (BUFFER_SIZE = 1024 items per
   * node). The first and final items must round-trip; the total count must match.
   * This exercises the {@code casNext} new-node branch in {@code offer} and the
   * advance-to-next-node branch in {@code poll}.
   */
  @Test
  public void multiNodeOfferAndPollPreservesOrderAndCount() {
    var dq = new MPSCFAAArrayDequeue<Integer>();
    final var n = 2_500; // > 2 * BUFFER_SIZE
    for (var i = 0; i < n; i++) {
      dq.offer(i);
    }
    for (var i = 0; i < n; i++) {
      assertEquals(Integer.valueOf(i), dq.poll());
    }
    assertNull(dq.poll());
  }

  /**
   * {@code static null} input handlers: {@link MPSCFAAArrayDequeue#next} and
   * {@link MPSCFAAArrayDequeue#prev} both return {@code null} when given a {@code null}
   * cursor. Pin the early-return branch.
   */
  @Test
  public void nextAndPrevAcceptNullCursorAndReturnNull() {
    assertNull(MPSCFAAArrayDequeue.next(null));
    assertNull(MPSCFAAArrayDequeue.prev(null));
  }

  /**
   * Cursor walk across a {@link Node#BUFFER_SIZE} boundary: offer more items than fit in
   * one node so the cursor must traverse through {@link Node#getNext()} during
   * {@code next()}. A regression that drops the {@code getNext()} hop in
   * {@link MPSCFAAArrayDequeue#next} would visit only the first {@code BUFFER_SIZE} items
   * and stop. Pin the entire visit count so the regression surfaces immediately.
   */
  @Test
  public void cursorWalkAcrossBufferNodeBoundary() {
    var dq = new MPSCFAAArrayDequeue<Integer>();
    final var n = Node.BUFFER_SIZE + 500; // 1524 — well past one node
    for (var i = 0; i < n; i++) {
      dq.offer(i);
    }

    var seen = 0;
    var cursor = dq.peekFirst();
    while (cursor != null) {
      assertEquals(Integer.valueOf(seen), cursor.getItem());
      seen++;
      cursor = MPSCFAAArrayDequeue.next(cursor);
    }
    assertEquals(
        "cursor walk must visit every item across the BUFFER_SIZE boundary",
        n, seen);
  }

  /**
   * Boundary check: offering exactly {@link Node#BUFFER_SIZE} items must fit in a single
   * node. Polling exactly {@code BUFFER_SIZE} times drains all of them; a subsequent
   * {@code poll()} returns {@code null}. A regression that off-by-ones the
   * {@code idx > BUFFER_SIZE - 1} guard at the boundary (e.g., swallowing the last slot
   * or allocating a redundant new node) would alter either the drain count or the
   * post-drain {@code null}.
   */
  @Test
  public void exactBufferSizeOffersFitInSingleNode() {
    var dq = new MPSCFAAArrayDequeue<Integer>();
    final var n = Node.BUFFER_SIZE;
    for (var i = 0; i < n; i++) {
      dq.offer(i);
    }
    for (var i = 0; i < n; i++) {
      assertEquals(Integer.valueOf(i), dq.poll());
    }
    assertNull("poll past the BUFFER_SIZE-th item must return null", dq.poll());
  }

  /**
   * Multi-producer / single-consumer concurrency smoke test. 4 producer threads each
   * offer {@code itemsPerProducer} unique integers; 1 consumer polls until total
   * {@code 4 * itemsPerProducer} items have been drained. The consumer collects every
   * polled value into a set; the test asserts the set size matches the producer total
   * and contains every produced ID — no losses, no duplicates.
   *
   * <p>{@link CountDownLatch} synchronises producer start so contention actually
   * happens; total budget is &lt; 5 seconds. No {@code Thread.sleep}.
   */
  @Test(timeout = 5_000L)
  public void multiProducerSingleConsumerSmokePreservesEveryItemExactlyOnce() throws Exception {
    final var producers = 4;
    final var itemsPerProducer = 4_000; // total 16k — well past one BUFFER_SIZE node
    final var totalItems = producers * itemsPerProducer;

    var dq = new MPSCFAAArrayDequeue<Integer>();
    var startLatch = new CountDownLatch(1);
    var producerErrors = new AtomicReference<Throwable>();
    var consumerError = new AtomicReference<Throwable>();
    var seen = new HashSet<Integer>();
    var pollCount = new AtomicInteger(0);

    var pool = Executors.newFixedThreadPool(producers + 1);
    try {
      for (var p = 0; p < producers; p++) {
        final var producerId = p;
        pool.submit(() -> {
          try {
            startLatch.await();
            // Each producer's IDs occupy a disjoint range so we can verify "no duplicates"
            // straight from the polled set.
            var base = producerId * itemsPerProducer;
            for (var i = 0; i < itemsPerProducer; i++) {
              dq.offer(base + i);
            }
          } catch (Throwable t) {
            producerErrors.compareAndSet(null, t);
          }
        });
      }

      Thread consumer = new Thread(() -> {
        try {
          startLatch.await();
          while (pollCount.get() < totalItems) {
            Integer v = dq.poll();
            if (v != null) {
              // Single consumer — no synchronisation needed on the local set.
              if (!seen.add(v)) {
                throw new AssertionError("duplicate polled: " + v);
              }
              pollCount.incrementAndGet();
            }
            // No sleep — busy-poll is intentional here (5 s timeout caps the test).
          }
        } catch (Throwable t) {
          consumerError.compareAndSet(null, t);
        }
      }, "mpsc-consumer");
      consumer.start();

      startLatch.countDown(); // release producers + consumer
      consumer.join(4_500L);

      pool.shutdown();
      assertTrue(
          "producer pool did not terminate in time",
          pool.awaitTermination(4_500L, TimeUnit.MILLISECONDS));

      assertNull("producer threw: " + producerErrors.get(), producerErrors.get());
      assertNull("consumer threw: " + consumerError.get(), consumerError.get());
      assertFalse("consumer must have completed", consumer.isAlive());

      assertEquals("polled count must equal produced count", totalItems, pollCount.get());
      assertEquals("seen set size must equal produced count", totalItems, seen.size());

      // Falsifiability: pin a couple of specific values from each producer's range
      // so a "missed every other item" bug couldn't pass.
      for (var p = 0; p < producers; p++) {
        var base = p * itemsPerProducer;
        assertTrue("missing first item from producer " + p, seen.contains(base));
        assertTrue(
            "missing last item from producer " + p,
            seen.contains(base + itemsPerProducer - 1));
      }
    } finally {
      pool.shutdownNow();
    }
  }

}
