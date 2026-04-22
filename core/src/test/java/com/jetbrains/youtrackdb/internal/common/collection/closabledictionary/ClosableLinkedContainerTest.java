package com.jetbrains.youtrackdb.internal.common.collection.closabledictionary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

public class ClosableLinkedContainerTest {

  /**
   * Resets state shared across tests via the static {@link CItem#openFiles} / {@link
   * CItem#maxDeltaLimit} counters, so that each test starts from a clean slate regardless of
   * JUnit's class/method ordering.
   */
  @Before
  public void resetStaticCounters() {
    CItem.openFiles.set(0);
    CItem.maxDeltaLimit.set(0);
  }

  @Test
  public void testSingleItemAddRemove() throws Exception {
    final ClosableItem closableItem = new CItem(10);
    final var dictionary =
        new ClosableLinkedContainer<Long, ClosableItem>(10);

    dictionary.add(1L, closableItem);

    var entry = dictionary.acquire(0L);
    Assert.assertNull(entry);

    entry = dictionary.acquire(1L);
    Assert.assertNotNull(entry);
    dictionary.release(entry);

    Assert.assertTrue(dictionary.checkAllLRUListItemsInMap());
    Assert.assertTrue(dictionary.checkAllOpenItemsInLRUList());
  }

  @Test
  public void testCloseHalfOfTheItems() throws Exception {
    final var dictionary =
        new ClosableLinkedContainer<Long, ClosableItem>(10);

    for (var i = 0; i < 10; i++) {
      final ClosableItem closableItem = new CItem(i);
      dictionary.add((long) i, closableItem);
    }

    var entry = dictionary.acquire(10L);
    Assert.assertNull(entry);

    for (var i = 0; i < 5; i++) {
      entry = dictionary.acquire((long) i);
      dictionary.release(entry);
    }

    dictionary.emptyBuffers();

    Assert.assertTrue(dictionary.checkAllLRUListItemsInMap());
    Assert.assertTrue(dictionary.checkAllOpenItemsInLRUList());

    for (var i = 0; i < 5; i++) {
      dictionary.add(10L + i, new CItem(10 + i));
    }

    for (var i = 0; i < 5; i++) {
      Assert.assertTrue(dictionary.get((long) i).isOpen());
    }

    for (var i = 5; i < 10; i++) {
      Assert.assertFalse(dictionary.get((long) i).isOpen());
    }

    for (var i = 10; i < 15; i++) {
      Assert.assertTrue(dictionary.get((long) i).isOpen());
    }

    Assert.assertTrue(dictionary.checkAllLRUListItemsInMap());
    Assert.assertTrue(dictionary.checkAllOpenItemsInLRUList());
  }

  /**
   * Drives the container into the exact shape that triggered the macOS-arm CI hang: {@code
   * openFiles > openLimit} while every entry in the LRU list is ACQUIRED. The returned list owns
   * those acquired entries — the caller must release them in a {@code finally}.
   *
   * <p>Recipe (single-threaded, deterministic):
   * <ol>
   *   <li>Add exactly {@code openLimit} entries, then acquire every one of them.</li>
   *   <li>{@code add(openLimit)}: inline eviction inside {@code LogAdd} closes the new OPEN
   *       entry (all older ones are ACQUIRED), leaving it CLOSED in the data map.</li>
   *   <li>{@code acquire(openLimit)}: transitions CLOSED → ACQUIRED via {@code
   *       makeAcquiredFromClosed}; {@code logOpen} bumps {@code openFiles} to
   *       {@code openLimit + 1}. All LRU entries are now ACQUIRED and the counter exceeds the
   *       limit.</li>
   * </ol>
   */
  private static List<ClosableEntry<Long, ClosableItem>> primeAllAcquiredOverflow(
      final ClosableLinkedContainer<Long, ClosableItem> dictionary, final int openLimit)
      throws InterruptedException {
    for (long i = 0; i < openLimit; i++) {
      dictionary.add(i, new CItem((int) i));
    }
    final var acquired = new ArrayList<ClosableEntry<Long, ClosableItem>>(openLimit + 1);
    for (long i = 0; i < openLimit; i++) {
      final var entry = dictionary.acquire(i);
      Assert.assertNotNull("priming: acquire(" + i + ") must succeed", entry);
      acquired.add(entry);
    }

    dictionary.add((long) openLimit, new CItem(openLimit));
    final var reopened = dictionary.acquire((long) openLimit);
    Assert.assertNotNull(
        "priming: reopen of just-closed entry must return a handle", reopened);
    acquired.add(reopened);

    Assert.assertTrue(
        "priming: openFiles must exceed openLimit to exercise the livelock path —"
            + " got openFiles=" + dictionary.openFilesCount() + ", openLimit=" + openLimit,
        dictionary.openFilesCount() > openLimit);
    return acquired;
  }

  /**
   * Runs the given task on a fresh single-thread executor and blocks up to {@code timeoutMs}
   * milliseconds for its result. A timeout fails the test with {@code failureMessage} — the
   * pre-fix livelocked thread does not respond to {@code interrupt()}, so we cancel the future
   * to stop waiting and {@code shutdownNow} + {@code awaitTermination} surface the leak instead
   * of hiding it.
   *
   * <p>Timeout budget (10 s by default) is ~10 000× the fix's run-time (microseconds); any
   * timeout indicates a real hang, not CI jitter.
   */
  private static <T> T runBounded(
      final Callable<T> task, final long timeoutMs, final String failureMessage)
      throws Exception {
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      final Future<T> future = executor.submit(task);
      try {
        return future.get(timeoutMs, TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        future.cancel(true);
        Assert.fail(failureMessage);
        throw new AssertionError("unreachable");
      }
    } finally {
      executor.shutdownNow();
      if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
        System.err.println(
            "warning: worker thread did not terminate after shutdownNow — likely still"
                + " livelocked in the production code");
      }
    }
  }

  /**
   * Regression test for a livelock in {@code checkOpenFilesLimit}: when every currently-open
   * entry is in ACQUIRED state and {@code openFiles > openLimit}, {@code emptyBuffers()} cannot
   * close anything, and the looping thread spun forever while holding {@code openLatch},
   * blocking every other thread that tries to add or acquire an entry.
   *
   * <p>This hang was observed on the macOS-arm integration-test pipeline (the deadlock-watchdog
   * fired after 3600 s).
   *
   * <p>The test verifies three contracts of the soft-limit fix:
   * <ol>
   *   <li>{@code add()} returns within a bounded time even while {@code openFiles > openLimit}
   *       and every entry is ACQUIRED.</li>
   *   <li>The newly-added entry is actually inserted into the data map (not silently dropped).
   *       </li>
   *   <li>Once the acquired entries are released, a follow-up eviction brings {@code openFiles}
   *       back to at most {@code openLimit} — the soft-limit violation is transient.</li>
   * </ol>
   */
  @Test
  public void testAddDoesNotLivelockWhenAllEntriesAreAcquired() throws Exception {
    final int openLimit = 4;
    final var dictionary = new ClosableLinkedContainer<Long, ClosableItem>(openLimit);

    final var acquired = primeAllAcquiredOverflow(dictionary, openLimit);
    try {
      runBounded(
          () -> {
            dictionary.add((long) openLimit + 1, new CItem(openLimit + 1));
            return null;
          },
          10_000L,
          "add() hung while every entry was acquired — checkOpenFilesLimit livelocked");

      Assert.assertNotNull(
          "add must insert the new entry into the data map, not silently drop it",
          dictionary.get((long) openLimit + 1));
    } finally {
      for (final var entry : acquired) {
        dictionary.release(entry);
      }
    }

    // Soft-limit recovery: once acquired entries are released, a follow-up eviction must bring
    // openFiles back to within openLimit. Another add triggers the eviction path.
    dictionary.add((long) openLimit + 2, new CItem(openLimit + 2));
    dictionary.emptyBuffers();
    Assert.assertTrue(
        "soft-limit violation should be transient; after release openFilesCount="
            + dictionary.openFilesCount() + " exceeds openLimit=" + openLimit,
        dictionary.openFilesCount() <= openLimit);
    Assert.assertTrue(dictionary.checkAllLRUListItemsInMap());
    Assert.assertTrue(dictionary.checkAllOpenItemsInLRUList());
  }

  /**
   * Companion regression test for {@code acquire()} — the {@code add} test exercises the same
   * {@code checkOpenFilesLimit} entry point, but a dedicated {@code acquire}-under-overflow
   * test pins the contract for both callers of {@code checkOpenFilesLimit} against future
   * refactors.
   *
   * <p>Because every entry is ACQUIRED by the main thread, a second thread re-acquiring an
   * existing key bumps the acquire counter and returns the same handle; the test asserts the
   * return is non-null and corresponds to the expected key.
   */
  @Test
  public void testAcquireDoesNotLivelockWhenAllEntriesAreAcquired() throws Exception {
    final int openLimit = 4;
    final var dictionary = new ClosableLinkedContainer<Long, ClosableItem>(openLimit);

    final var acquired = primeAllAcquiredOverflow(dictionary, openLimit);
    try {
      final ClosableEntry<Long, ClosableItem> reAcquired =
          runBounded(
              () -> dictionary.acquire(0L),
              10_000L,
              "acquire() hung while every entry was acquired — checkOpenFilesLimit livelocked");

      Assert.assertNotNull(
          "re-acquiring an existing key must return the same handle, not null", reAcquired);
      Assert.assertSame(
          "acquired handle must reference the CItem that was originally added under key 0",
          acquired.get(0).get(), reAcquired.get());
      dictionary.release(reAcquired);
    } finally {
      for (final var entry : acquired) {
        dictionary.release(entry);
      }
    }
  }

  /**
   * Regression test for {@link ClosableLinkedContainer#tryAcquire(Object)}: when every entry is
   * acquired and {@code openFiles > openLimit}, {@code tryAcquire} must return in a bounded
   * time — {@code null} for a key that is currently acquired by another thread (cannot be
   * re-acquired in that direction of the state machine), or the handle otherwise. The main
   * thread holds key {@code 0L} exclusively through this test's reference to the list; a
   * background {@code tryAcquire(0L)} call on the SAME container must not block indefinitely.
   *
   * <p>(The state-machine contract around {@code tryAcquire}: it reuses {@code doAcquireEntry},
   * which increments the acquire count if the entry is already ACQUIRED. So {@code tryAcquire}
   * can actually return a non-null handle even while key {@code 0L} is held elsewhere. What
   * matters for this regression is the bounded-time contract, not which branch fires.)
   */
  @Test
  public void testTryAcquireDoesNotLivelockWhenAllEntriesAreAcquired() throws Exception {
    final int openLimit = 4;
    final var dictionary = new ClosableLinkedContainer<Long, ClosableItem>(openLimit);

    final var acquired = primeAllAcquiredOverflow(dictionary, openLimit);
    try {
      final ClosableEntry<Long, ClosableItem> result =
          runBounded(
              () -> dictionary.tryAcquire(0L),
              10_000L,
              "tryAcquire() hung while every entry was acquired —"
                  + " tryCheckOpenFilesLimit livelocked");
      // Whether result is null (limit still exceeded → soft-skip) or non-null (increment of
      // acquire count), only bounded termination is the regression contract. If non-null, we
      // must release it so the main-thread finally doesn't leak references.
      if (result != null) {
        dictionary.release(result);
      }
    } finally {
      for (final var entry : acquired) {
        dictionary.release(entry);
      }
    }
  }

  /**
   * Regression test for the multi-thread gating contract of {@code openLatch}: N threads
   * simultaneously call {@code add} while every entry is ACQUIRED. All must return within a
   * bounded time.
   *
   * <p>This catches a future refactor that removes {@code latch.countDown()} from the fix's
   * {@code finally} block — the single-thread regressions wouldn't notice, but the waiters in
   * this test would hang on {@code ol.await()} until the 10 s budget trips.
   */
  @Test
  public void testConcurrentAddsUnblockWhenAllEntriesAreAcquired() throws Exception {
    final int openLimit = 4;
    final int contenders = 8;
    final var dictionary = new ClosableLinkedContainer<Long, ClosableItem>(openLimit);

    final var acquired = primeAllAcquiredOverflow(dictionary, openLimit);
    final var barrier = new CyclicBarrier(contenders);
    final ExecutorService pool = Executors.newFixedThreadPool(contenders);
    try {
      final List<Future<Void>> futures = new ArrayList<>(contenders);
      for (int i = 0; i < contenders; i++) {
        final long key = (long) openLimit + 1 + i;
        futures.add(
            pool.submit(
                () -> {
                  barrier.await();
                  dictionary.add(key, new CItem((int) key));
                  return null;
                }));
      }

      for (int i = 0; i < contenders; i++) {
        try {
          futures.get(i).get(10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
          for (final var f : futures) {
            f.cancel(true);
          }
          Assert.fail(
              "concurrent add(" + ((long) openLimit + 1 + i) + ") hung —"
                  + " at least one waiter was not released by openLatch.countDown()");
        }
      }

      for (int i = 0; i < contenders; i++) {
        Assert.assertNotNull(
            "concurrent add must insert each key into the data map",
            dictionary.get((long) openLimit + 1 + i));
      }
    } finally {
      pool.shutdownNow();
      Assert.assertTrue(
          "pool must terminate after shutdownNow",
          pool.awaitTermination(5, TimeUnit.SECONDS));
      Collections.reverse(acquired);
      for (final var entry : acquired) {
        dictionary.release(entry);
      }
    }
  }

  @Test
  @Ignore
  public void testMultipleThreadsConsistency() throws Exception {
    CItem.openFiles.set(0);
    CItem.maxDeltaLimit.set(0);

    var executor = Executors.newCachedThreadPool();
    List<Future<Void>> futures = new ArrayList<Future<Void>>();
    var latch = new CountDownLatch(1);

    var limit = 60000;

    var dictionary =
        new ClosableLinkedContainer<Long, CItem>(16);
    futures.add(executor.submit(new Adder(dictionary, latch, 0, limit / 3)));
    futures.add(executor.submit(new Adder(dictionary, latch, limit / 3, 2 * limit / 3)));

    var stop = new AtomicBoolean();

    for (var i = 0; i < 16; i++) {
      futures.add(executor.submit(new Acquier(dictionary, latch, limit, stop)));
    }

    latch.countDown();

    Thread.sleep(60000);

    futures.add(executor.submit(new Adder(dictionary, latch, 2 * limit / 3, limit)));

    Thread.sleep(15 * 60000);

    stop.set(true);
    for (var future : futures) {
      future.get();
    }

    dictionary.emptyBuffers();

    Assert.assertTrue(dictionary.checkAllLRUListItemsInMap());
    Assert.assertTrue(dictionary.checkAllOpenItemsInLRUList());
    Assert.assertTrue(dictionary.checkNoClosedItemsInLRUList());
    Assert.assertTrue(dictionary.checkLRUSize());
    Assert.assertTrue(dictionary.checkLRUSizeEqualsToCapacity());

    System.out.println("Open files " + CItem.openFiles.get());
    System.out.println("Max open files limit overhead " + CItem.maxDeltaLimit.get());
  }

  private class Adder implements Callable<Void> {

    private final ClosableLinkedContainer<Long, CItem> dictionary;
    private final CountDownLatch latch;
    private final int from;
    private final int to;

    public Adder(
        ClosableLinkedContainer<Long, CItem> dictionary, CountDownLatch latch, int from, int to) {
      this.dictionary = dictionary;
      this.latch = latch;
      this.from = from;
      this.to = to;
    }

    @Override
    public Void call() throws Exception {
      latch.await();

      try {
        for (var i = from; i < to; i++) {
          dictionary.add((long) i, new CItem(i));
        }
      } catch (Exception e) {
        e.printStackTrace();
        throw e;
      }

      System.out.println("Add from " + from + " to " + to + " completed");

      return null;
    }
  }

  private class Acquier implements Callable<Void> {

    private final ClosableLinkedContainer<Long, CItem> dictionary;
    private final CountDownLatch latch;
    private final int limit;
    private final AtomicBoolean stop;

    public Acquier(
        ClosableLinkedContainer<Long, CItem> dictionary,
        CountDownLatch latch,
        int limit,
        AtomicBoolean stop) {
      this.dictionary = dictionary;
      this.latch = latch;
      this.limit = limit;
      this.stop = stop;
    }

    @Override
    public Void call() throws Exception {
      latch.await();

      long counter = 0;
      var start = System.nanoTime();

      try {
        var random = new Random();

        while (!stop.get()) {
          var index = random.nextInt(limit);
          final var entry = dictionary.acquire((long) index);
          if (entry != null) {
            Assert.assertTrue(entry.get().isOpen());
            counter++;
            dictionary.release(entry);
          }
        }

      } catch (Exception e) {
        e.printStackTrace();
        throw e;
      }

      var end = System.nanoTime();

      System.out.println(
          "Files processed " + counter + " nanos per item " + (end - start) / counter);
      return null;
    }
  }

  private static class CItem implements ClosableItem {

    public static AtomicInteger openFiles = new AtomicInteger();
    public static AtomicInteger maxDeltaLimit = new AtomicInteger();

    private volatile boolean open = true;

    private final int openLimit;

    public CItem(int openLimit) {
      this.openLimit = openLimit;

      countOpenFiles();
    }

    @Override
    public boolean isOpen() {
      return open;
    }

    @Override
    public void close() {
      open = false;

      var count = openFiles.decrementAndGet();

      if (count - openLimit > 0) {
        while (true) {
          var max = maxDeltaLimit.get();
          if (count - openLimit > max) {
            if (maxDeltaLimit.compareAndSet(max, count - openLimit)) {
              break;
            }
          } else {
            break;
          }
        }
      }
    }

    @Override
    public void open() {
      open = true;

      countOpenFiles();
    }

    private void countOpenFiles() {
      var count = openFiles.incrementAndGet();
      if (count - openLimit > 0) {
        while (true) {
          var max = maxDeltaLimit.get();
          if (count - openLimit > max) {
            if (maxDeltaLimit.compareAndSet(max, count - openLimit)) {
              break;
            }
          } else {
            break;
          }
        }
      }
    }
  }
}
