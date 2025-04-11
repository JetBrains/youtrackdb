package com.jetbrains.youtrack.db.internal.common.collection.closabledictionary;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

public class ClosableLinkedContainerTest {

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
