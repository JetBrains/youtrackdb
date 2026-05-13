package com.jetbrains.youtrackdb.internal.core.storage.cache.chm.writequeue;

import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link MPSCLinkedQueue} — a multiple-producer / single-consumer linked queue.
 *
 * <p>Covers the primary paths in {@code offer()}, {@code poll()}, and {@code isEmpty()}:
 * <ul>
 *   <li>Basic single-threaded enqueue / dequeue semantics.</li>
 *   <li>{@code isEmpty()} transitions on enqueue and after full drain.</li>
 *   <li>MPSC concurrency smoke: multiple concurrent producers and one consumer, verifying
 *       all items are eventually received without loss and the queue drains to empty.
 *       The concurrent producers exercise the {@code lazySetNext} → consumer {@code yield}
 *       path inside {@code poll()} that is only reachable when the consumer observes a
 *       partially-linked node from a concurrent producer.</li>
 * </ul>
 */
public class MPSCLinkedQueueTest {

  // ---- isEmpty semantics ----

  /**
   * A freshly constructed queue must report itself as empty: {@code head == tail} (both point
   * to the dummy sentinel node created in the constructor).
   */
  @Test
  public void testIsEmptyOnFreshQueue() {
    final var queue = new MPSCLinkedQueue<String>();
    Assert.assertTrue("A newly constructed MPSCLinkedQueue must be empty", queue.isEmpty());
  }

  /**
   * After one {@code offer()}, the tail advances past the head sentinel, so {@code isEmpty()}
   * must return false.
   */
  @Test
  public void testIsEmptyAfterOffer() {
    final var queue = new MPSCLinkedQueue<String>();
    queue.offer("a");
    Assert.assertFalse("Queue must not be empty after one offer", queue.isEmpty());
  }

  /**
   * After draining all elements with {@code poll()}, {@code isEmpty()} must return true again.
   */
  @Test
  public void testIsEmptyAfterFullDrain() {
    final var queue = new MPSCLinkedQueue<String>();
    queue.offer("x");
    queue.offer("y");
    Assert.assertEquals("x", queue.poll());
    Assert.assertEquals("y", queue.poll());
    Assert.assertTrue("Queue must be empty after draining all elements", queue.isEmpty());
  }

  // ---- single-threaded offer / poll ----

  /**
   * Single offer and poll: the polled value must equal the offered value, and a second poll
   * on an empty queue must return null.
   */
  @Test
  public void testSingleOfferAndPoll() {
    final var queue = new MPSCLinkedQueue<Integer>();
    queue.offer(42);
    final var result = queue.poll();
    Assert.assertEquals("poll() must return the single offered value",
        Integer.valueOf(42), result);
    Assert.assertNull("poll() on an empty queue must return null", queue.poll());
  }

  /**
   * Three items offered in order must be polled in FIFO order. The poll-FIFO contract pins the
   * linked structure: {@code prev.lazySetNext(newNode)} wires nodes in insertion order and the
   * consumer walks head → next.
   */
  @Test
  public void testFifoOrderingWithMultipleOffers() {
    final var queue = new MPSCLinkedQueue<Integer>();
    queue.offer(1);
    queue.offer(2);
    queue.offer(3);

    Assert.assertEquals("poll() #1 must return 1 (FIFO)", Integer.valueOf(1), queue.poll());
    Assert.assertEquals("poll() #2 must return 2 (FIFO)", Integer.valueOf(2), queue.poll());
    Assert.assertEquals("poll() #3 must return 3 (FIFO)", Integer.valueOf(3), queue.poll());
    Assert.assertNull("poll() on empty queue must return null", queue.poll());
  }

  /**
   * Poll on an empty queue (head == tail, dummy node) must return null without spinning.
   */
  @Test
  public void testPollOnEmptyQueueReturnsNull() {
    final var queue = new MPSCLinkedQueue<String>();
    Assert.assertNull("poll() on empty queue must return null immediately", queue.poll());
    Assert.assertTrue("Queue must remain empty after poll on empty", queue.isEmpty());
  }

  // ---- MPSC concurrency smoke ----

  /**
   * Multiple-producer / single-consumer smoke: 4 producers each offer {@code ITEMS_PER_PRODUCER}
   * unique integers concurrently; the consumer polls until all items are received.
   *
   * <p>The 4-producer burst drives CAS contention on {@code tail.getAndSet(newNode)}, which
   * causes the "yield loop" inside {@code poll()} — {@code while ((next = head.getNext()) == null)
   * Thread.yield()} — to be entered when the consumer observes a tail node whose {@code next}
   * field has not yet been written by the producer (the MPSC linked-list's known "lazySet" window).
   * This is the branch that drives the 1 uncovered line in the package.
   *
   * <p>Beyond a count-only check, the consumer tracks every polled item in a {@link HashSet}
   * and asserts that each item is unique — a regression that re-delivers a node would otherwise
   * pass the count check by under-counting the "missing" items. Producer-side {@link Throwable}s
   * are captured in an {@link AtomicReference} so an exception cannot be silently swallowed when
   * the daemon thread exits. The {@code @Test(timeout)} caps the worst case at 10 s so a
   * regression that hangs the consumer cannot stall the build forever.
   */
  @Test(timeout = 10_000)
  public void testMpscConcurrencySmoke() throws InterruptedException {
    final int producerCount = 4;
    final int itemsPerProducer = 500;
    final int totalItems = producerCount * itemsPerProducer;

    final var queue = new MPSCLinkedQueue<Integer>();
    final var startLatch = new CountDownLatch(1);
    final var producersDone = new CountDownLatch(producerCount);
    final var producerError = new AtomicReference<Throwable>();

    final var executor = Executors.newFixedThreadPool(producerCount);

    // Launch producers, each waiting on the start latch before offering their items.
    for (int p = 0; p < producerCount; p++) {
      final int producerId = p;
      executor.submit(() -> {
        try {
          startLatch.await();
          for (int i = 0; i < itemsPerProducer; i++) {
            queue.offer(producerId * itemsPerProducer + i);
          }
        } catch (Throwable e) {
          producerError.compareAndSet(null, e);
          if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
          }
        } finally {
          producersDone.countDown();
        }
      });
    }

    // Release all producers simultaneously to maximise CAS contention.
    startLatch.countDown();

    // Consumer: keep polling until totalItems are received, with a 5 s timeout.
    // Every item is recorded in `seen` so that any duplicate delivery (which would otherwise
    // mask itself by under-counting some other item) fails the test immediately.
    final var seen = new HashSet<Integer>();
    final var received = new AtomicInteger(0);
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (received.get() < totalItems) {
      if (System.nanoTime() > deadline) {
        Assert.fail("Consumer timed out after 5 s — only " + received.get()
            + " of " + totalItems + " items received");
      }
      final var item = queue.poll();
      if (item != null) {
        if (!seen.add(item)) {
          Assert.fail("Duplicate item polled: " + item);
        }
        received.incrementAndGet();
      } else {
        Thread.yield();
      }
    }

    // Wait for producers to finish (they should all be done by now).
    Assert.assertTrue("All producers must finish within 5 s",
        producersDone.await(5, TimeUnit.SECONDS));
    executor.shutdown();

    Assert.assertNull("Producer thread threw: " + producerError.get(), producerError.get());

    Assert.assertEquals("All offered items must be consumed exactly once",
        totalItems, received.get());
    Assert.assertEquals("Each polled item must be unique", totalItems, seen.size());

    // Pin specific sentinels from each producer's range — a regression that drops a whole
    // producer's contribution while picking up duplicates from another would still match
    // totalItems on the count-only check.
    for (int p = 0; p < producerCount; p++) {
      final int base = p * itemsPerProducer;
      Assert.assertTrue("missing first ID from producer " + p, seen.contains(base));
      Assert.assertTrue("missing last ID from producer " + p,
          seen.contains(base + itemsPerProducer - 1));
    }

    Assert.assertTrue("Queue must be empty after all items are consumed", queue.isEmpty());
  }

  // ---- Node class ----

  /**
   * {@link Node} is a package-private value holder. Its constructor, {@code getItem()}, and
   * {@code getNext()} (initially null) paths are directly exercised by unit-constructing a node.
   * The {@code lazySetNext} path is exercised by {@code MPSCLinkedQueue.offer()} via the queue
   * tests above; this test pins the initial state for readability.
   */
  @Test
  public void testNodeInitialState() {
    final var node = new Node<String>("hello");
    Assert.assertEquals("getItem() must return the value passed to the constructor",
        "hello", node.getItem());
    Assert.assertNull("getNext() must be null immediately after construction", node.getNext());
  }
}
