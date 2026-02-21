package com.jetbrains.youtrackdb.internal.common.concur.collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
      assertEquals(value, array.get(idx));
    });
    var t2 = new Thread(() -> {
      Integer value = ThreadLocalRandom.current().nextInt();
      var idx = array.add(value);
      assertEquals(value, array.get(idx));
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
    assertEquals(MAX_SIZE, array.size());
    for (var i = 0; i < MAX_SIZE; i++) {
      var value = array.get(i);
      assertTrue(value > 0, "Expected positive value at index " + i + ", got: " + value);
      assertTrue(value <= MAX_THREADS,
          "Expected value <= " + MAX_THREADS + " at index " + i + ", got: " + value);
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
    assertEquals(MAX_SIZE, array.size());
    var processedIndexes = new ConcurrentSkipListSet<Integer>();
    while (updaters.peek() != null) {
      var updater = updaters.poll();
      assertTrue(processedIndexes.add(updater.index()),
          "index " + updater.index() + " was updated more than once");
      assertEquals(updater.thread(), array.get(updater.index()));
    }
    for (var i = 0; i < MAX_SIZE; i++) {
      assertTrue(processedIndexes.remove(i),
          "index " + i + " was not processed");
    }
  }

  /**
   * Verifies that getOrEmpty does not livelock when called concurrently with add().
   *
   * <p>The get() method contains two spin-wait loops (with Thread.yield()) — one waiting
   * for the container to be non-null, another for the value to be non-null. If there exists
   * a thread interleaving where getOrEmpty passes the size check but then enters a spin-wait
   * that can never terminate, Fray will detect it as a liveness violation.
   *
   * <p>Multiple writers add items concurrently while a reader probes every index via
   * getOrEmpty, maximizing the chance of hitting the race between size increment and
   * container/value publication.
   */
  @ConcurrencyTest(
      iterations = 1000
  )
  public void getOrEmptyShouldNotLivelockDuringConcurrentAdd()
      throws InterruptedException {
    var placeholder = Integer.valueOf(-1);
    var array = new CASObjectArray<>(placeholder);
    int itemsPerWriter = 4;
    int numWriters = 3;
    int totalItems = numWriters * itemsPerWriter;

    List<Thread> writers = new ArrayList<>(numWriters);
    for (int w = 0; w < numWriters; w++) {
      var writerId = w + 1;
      writers.add(new Thread(() -> {
        for (int i = 0; i < itemsPerWriter; i++) {
          array.add(writerId * 100 + i);
        }
      }));
    }

    var reader = new Thread(() -> {
      // Probe every index that will eventually exist.
      // getOrEmpty must return either the placeholder (index not yet visible)
      // or a positive value written by one of the writers — never spin forever.
      for (int i = 0; i < totalItems; i++) {
        var val = array.getOrEmpty(i);
        assertTrue(placeholder.equals(val) || (val != null && val > 0),
            "Expected placeholder or positive value at index " + i + ", got: " + val);
      }
    });

    for (var w : writers) {
      w.start();
    }
    reader.start();
    for (var w : writers) {
      w.join();
    }
    reader.join();
  }

  /**
   * Verifies that getOrEmpty does not livelock when set() is concurrently expanding
   * the array beyond its current size.
   *
   * <p>The set(index, value, placeholder) method calls add(placeholder) in a loop to grow
   * the array, then overwrites the target slot. During expansion, a concurrent getOrEmpty
   * reader might see the intermediate size updates and attempt to read slots whose containers
   * or values are not yet published. If there's a scheduling where get() enters a spin-wait
   * that never terminates, Fray will report a LivenessException.
   */
  @ConcurrencyTest(
      iterations = 1000
  )
  public void getOrEmptyShouldNotLivelockDuringConcurrentSetExpansion()
      throws InterruptedException {
    var placeholder = Integer.valueOf(-1);
    var array = new CASObjectArray<>(placeholder);
    var targetIndex = 10;

    var t1 = new Thread(() -> {
      array.set(targetIndex, 42, placeholder);
    });

    var t2 = new Thread(() -> {
      // Read indices that are being created by the expansion.
      // Should never hang — returns either the placeholder or an actual value.
      for (int i = 0; i <= targetIndex; i++) {
        var val = array.getOrEmpty(i);
        assertNotNull(val, "getOrEmpty returned null at index " + i);
      }
    });

    t1.start();
    t2.start();
    t1.join();
    t2.join();
  }

  /**
   * Verifies that concurrent add() calls from multiple threads produce a consistent array.
   * After N threads each add M items, the total size must be N*M and every index must hold
   * a non-null value.
   */
  @ConcurrencyTest(
      iterations = 1000
  )
  public void concurrentAddsShouldProduceConsistentSize() throws InterruptedException {
    var array = new CASObjectArray<Integer>();
    int itemsPerThread = 4;
    int numThreads = 4;

    List<Thread> threads = new ArrayList<>(numThreads);
    for (var t = 0; t < numThreads; t++) {
      var threadId = t + 1;
      var thread = new Thread(() -> {
        for (var i = 0; i < itemsPerThread; i++) {
          var idx = array.add(threadId * 100 + i);
          assertEquals(Integer.valueOf(threadId * 100 + i), array.get(idx));
        }
      });
      threads.add(thread);
    }

    for (var thread : threads) {
      thread.start();
    }
    for (var thread : threads) {
      thread.join();
    }

    assertEquals(numThreads * itemsPerThread, array.size());
    for (var i = 0; i < array.size(); i++) {
      assertNotNull(array.get(i), "null value at index " + i);
    }
  }

  private record Updater(Integer thread, Integer index) {

  }
}
