package com.jetbrains.youtrackdb.internal.common.concur.collection;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pastalab.fray.junit.junit5.FrayTestExtension;
import org.pastalab.fray.junit.junit5.annotations.ConcurrencyTest;

@ExtendWith(FrayTestExtension.class)
public class CASObjectArrayMTTest {

  private static final int MAX_SIZE = 64;
  private static final int MAX_THREADS = 16;

  @ConcurrencyTest(
      iterations = 1000
  )
  public void addShouldReturnCorrectIndex() throws InterruptedException {
    ThreadLocalRandom.current();
    var array = new CASObjectArray<Integer>();
    var t1 = new Thread(() -> {
      Integer value = ThreadLocalRandom.current().nextInt();
      var idx = array.add(value);
      assert (array.get(idx).equals(value));
    });
    var t2 = new Thread(() -> {
      Integer value = ThreadLocalRandom.current().nextInt();
      var idx = array.add(value);
      assert (array.get(idx).equals(value));
    });
    t1.start();
    t2.start();
    t1.join();
    t2.join();
  }

  @ConcurrencyTest(
      iterations = 1000
  )
  public void setShouldEventuallyUpdateArray() throws InterruptedException {
    ThreadLocalRandom.current();
    var array = new CASObjectArray<Integer>();
    for (var i = 0; i < MAX_SIZE; i++) {
      array.add(-ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE));
    }
    List<Thread> threads = new ArrayList<>(MAX_THREADS);
    var startCountdown = new CountDownLatch(1);
    for (var t = 0; t < MAX_THREADS; t++) {
      var finalT = t + 1;
      var thread = new Thread(() -> {
        try {
          startCountdown.await();
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
        Integer newValue = finalT;
        Integer placeholder = finalT + 100;
        for (var i = 0; i < MAX_SIZE; i++) {
          array.set(i, newValue, placeholder);
        }
      });
      threads.add(thread);
    }
    for (var t = 0; t < MAX_THREADS; t++) {
      threads.get(t).start();
    }
    startCountdown.countDown();
    for (var t = 0; t < MAX_THREADS; t++) {
      threads.get(t).join();
    }
    assert (array.size() == MAX_SIZE);
    for (var i = 0; i < MAX_SIZE; i++) {
      var value = array.get(i);
      assert value > 0;
      assert value <= MAX_THREADS;
    }
  }

  @ConcurrencyTest(
      iterations = 1000
  )
  public void compareAndSetShouldUpdateOnlyOnce() throws InterruptedException {
    ThreadLocalRandom.current();
    var array = new CASObjectArray<Integer>();
    var initialValueReference = new CopyOnWriteArrayList<Integer>();
    for (var i = 0; i < MAX_SIZE; i++) {
      // valueOf is needed because compareAndSet in array uses == and not equals
      var initialValue = Integer.valueOf(-ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE));
      array.add(initialValue);
      initialValueReference.add(initialValue);
    }
    List<Thread> threads = new ArrayList<>(MAX_THREADS);
    var updaters = new ConcurrentLinkedQueue<Updater>();

    var startCountdown = new CountDownLatch(1);
    for (var t = 0; t < MAX_THREADS; t++) {
      var finalT = t + 1;
      var thread = new Thread(() -> {
        try {
          startCountdown.await();
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
        Integer newValue = finalT;
        for (var i = 0; i < MAX_SIZE; i++) {
          var oldValue = initialValueReference.get(i);
          if (array.compareAndSet(i, oldValue, newValue)) {
            updaters.add(new Updater(finalT, i));
          }
        }
      });
      threads.add(thread);
    }
    for (var t = 0; t < MAX_THREADS; t++) {
      threads.get(t).start();
    }
    startCountdown.countDown();
    for (var t = 0; t < MAX_THREADS; t++) {
      threads.get(t).join();
    }
    assert (array.size() == MAX_SIZE);
    var processesIndexes = new ConcurrentSkipListSet<Integer>();
    while (updaters.peek() != null) {
      var updater = updaters.poll();
      var added = processesIndexes.add(updater.index);
      assert added; // each index should be only updated once
      assert array.get(updater.index).equals(updater.thread);
    }
    for (var i = 0; i < MAX_SIZE; i++) {
      var removed = processesIndexes.remove(i);
      assert removed : "index " + i
          + " was not processed"; // if some index was not updated, it should be caught here
    }
  }

  private record Updater(Integer thread, Integer index) {

  }
}
