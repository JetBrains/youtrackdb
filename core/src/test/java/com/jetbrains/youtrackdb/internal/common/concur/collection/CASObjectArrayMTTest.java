package com.jetbrains.youtrackdb.internal.common.concur.collection;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.vmlens.api.AllInterleavings;
import com.vmlens.api.AllInterleavingsBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/**
 * Multi-threaded tests for {@link CASObjectArray} using VMLens for systematic
 * interleaving exploration. These tests verify that concurrent set() and get()
 * operations do not expose placeholder values to readers.
 *
 * <p>The original bug: {@code set(index, value, placeholder)} first expanded
 * the array with {@code add(placeholder)} at the target index, then overwrote
 * it with {@code container.set(index, value)}. A concurrent reader calling
 * {@code get(index)} between these two steps would observe the placeholder
 * instead of the actual value. On ARM's relaxed memory model, this race was
 * wide enough to cause snapshot isolation violations in
 * {@code AtomicOperationsTable}.
 */
public class CASObjectArrayMTTest {

  // VMLens exhaustively explores all thread interleaving, so small counts
  // provide thorough coverage (unlike random sampling which needs many threads).
  private static final int MAX_SIZE = 4;
  private static final int MAX_THREADS = 2;

  // Caps the number of interleaving VMLens explores to keep CI time bounded.
  private static final int MAX_ITERATIONS = 1000;

  private AllInterleavings allInterleavings(String name) {
    return new AllInterleavingsBuilder()
        .withMaximumIterations(MAX_ITERATIONS)
        .build(name);
  }

  /**
   * Verifies that a concurrent reader never observes the placeholder value
   * at the target index during a set() call that expands the array.
   *
   * <p>Thread 1 calls {@code set(2, ACTUAL, PLACEHOLDER)} which must expand
   * the array from size 0 to size 3. Thread 2 reads index 2 as soon as it
   * becomes available (size > 2). The read must never return PLACEHOLDER.
   */
  @Test
  public void setWithExpansionShouldNeverExposePlaceholderToReader() throws Exception {
    final int PLACEHOLDER = -1;
    final int ACTUAL = 42;
    final int TARGET_INDEX = 2;

    try (var allInterleavings = new AllInterleavingsBuilder()
        .withMaximumIterations(MAX_ITERATIONS)
        .build("setWithExpansionShouldNeverExposePlaceholderToReader")) {
      while (allInterleavings.hasNext()) {
        var array = new CASObjectArray<Integer>();

        var readValue = new int[] {0};

        var writer = new Thread(() -> {
          array.set(TARGET_INDEX, ACTUAL, PLACEHOLDER);
        });

        var reader = new Thread(() -> {
          // Spin until the target index is accessible
          while (array.size() <= TARGET_INDEX) {
            Thread.yield();
          }
          readValue[0] = array.get(TARGET_INDEX);
        });

        writer.start();
        reader.start();
        writer.join();
        reader.join();

        // The reader must see the actual value, never the placeholder.
        assertEquals(ACTUAL, readValue[0],
            "Reader should observe actual value, not placeholder or default");
      }
    }
  }

  /**
   * Verifies that gap-filling slots below the target index correctly receive
   * placeholder values, while the target index receives the actual value.
   *
   * <p>Thread 1 calls {@code set(3, ACTUAL, PLACEHOLDER)}. Thread 2 reads
   * all indices [0, 3] once they become available. Gap indices must have
   * the placeholder, and the target index must have the actual value.
   */
  @Test
  public void setWithGapsShouldFillPlaceholdersAndSetActualAtTarget() throws Exception {
    final int PLACEHOLDER = -1;
    final int ACTUAL = 99;
    final int TARGET_INDEX = 3;

    try (var allInterleavings = new AllInterleavingsBuilder()
        .withMaximumIterations(MAX_ITERATIONS)
        .build("setWithGapsShouldFillPlaceholdersAndSetActualAtTarget")) {
      while (allInterleavings.hasNext()) {
        var array = new CASObjectArray<Integer>();

        var readValues = new int[TARGET_INDEX + 1];

        var writer = new Thread(() -> {
          array.set(TARGET_INDEX, ACTUAL, PLACEHOLDER);
        });

        var reader = new Thread(() -> {
          // Wait until the full expansion is done
          while (array.size() <= TARGET_INDEX) {
            Thread.yield();
          }
          for (int i = 0; i <= TARGET_INDEX; i++) {
            readValues[i] = array.get(i);
          }
        });

        writer.start();
        reader.start();
        writer.join();
        reader.join();

        // Target index must have the actual value
        assertEquals(ACTUAL, readValues[TARGET_INDEX],
            "Target index should have actual value, not placeholder");
      }
    }
  }

  @Test
  public void addShouldReturnCorrectIndex() throws Exception {
    try (var allInterleavings = allInterleavings("addShouldReturnCorrectIndex")) {
      while (allInterleavings.hasNext()) {
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
    }
  }

  @Test
  public void setShouldEventuallyUpdateArray() throws Exception {
    try (var allInterleavings = allInterleavings("setShouldEventuallyUpdateArray")) {
      while (allInterleavings.hasNext()) {
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
    }
  }

  @Test
  public void compareAndSetShouldUpdateOnlyOnce() throws Exception {
    try (var allInterleavings = allInterleavings("compareAndSetShouldUpdateOnlyOnce")) {
      while (allInterleavings.hasNext()) {
        var array = new CASObjectArray<Integer>();
        var initialValueReference = new CopyOnWriteArrayList<Integer>();
        for (var i = 0; i < MAX_SIZE; i++) {
          // valueOf is needed because compareAndSet in array uses == and not equals
          var initialValue =
              Integer.valueOf(-ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE));
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
    }
  }

  /**
   * Verifies that getOrDefault does not livelock when called concurrently with add().
   *
   * <p>The get() method contains two spin-wait loops (with Thread.yield()) -- one waiting
   * for the container to be non-null, another for the value to be non-null. If there exists
   * a thread interleaving where getOrDefault passes the size check but then enters a spin-wait
   * that can never terminate, VMLens will detect it as a data race or liveness violation.
   *
   * <p>Multiple writers add items concurrently while a reader probes every index via
   * getOrDefault, maximizing the chance of hitting the race between size increment and
   * container/value publication.
   */
  @Test
  public void getOrEmptyShouldNotLivelockDuringConcurrentAdd()
      throws Exception {
    try (var allInterleavings =
        allInterleavings("getOrEmptyShouldNotLivelockDuringConcurrentAdd")) {
      while (allInterleavings.hasNext()) {
        var placeholder = Integer.valueOf(-1);
        var array = new CASObjectArray<>(placeholder);
        var itemsPerWriter = 2;
        var numWriters = 2;
        var totalItems = numWriters * itemsPerWriter;

        List<Thread> writers = new ArrayList<>(numWriters);
        for (var w = 0; w < numWriters; w++) {
          var writerId = w + 1;
          writers.add(new Thread(() -> {
            for (var i = 0; i < itemsPerWriter; i++) {
              array.add(writerId * 100 + i);
            }
          }));
        }

        var reader = new Thread(() -> {
          // Probe every index that will eventually exist.
          // getOrDefault must return either the placeholder (index not yet visible)
          // or a positive value written by one of the writers -- never spin forever.
          for (var i = 0; i < totalItems; i++) {
            var val = array.getOrDefault(i);
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
    }
  }

  /**
   * Verifies that getOrDefault does not livelock when set() is concurrently expanding
   * the array beyond its current size.
   *
   * <p>The set(index, value, placeholder) method calls add(placeholder) in a loop to grow
   * the array, then overwrites the target slot. During expansion, a concurrent getOrDefault
   * reader might see the intermediate size updates and attempt to read slots whose containers
   * or values are not yet published. If there's a scheduling where get() enters a spin-wait
   * that never terminates, VMLens will detect it as a data race or liveness violation.
   */
  @Test
  public void getOrEmptyShouldNotLivelockDuringConcurrentSetExpansion()
      throws Exception {
    try (var allInterleavings =
        allInterleavings("getOrEmptyShouldNotLivelockDuringConcurrentSetExpansion")) {
      while (allInterleavings.hasNext()) {
        var placeholder = Integer.valueOf(-1);
        var array = new CASObjectArray<>(placeholder);
        var targetIndex = 3;

        var t1 = new Thread(() -> {
          array.set(targetIndex, 42, placeholder);
        });

        var t2 = new Thread(() -> {
          // Read indices that are being created by the expansion.
          // Should never hang -- returns either the placeholder or an actual value.
          for (var i = 0; i <= targetIndex; i++) {
            var val = array.getOrDefault(i);
            assertNotNull(val, "getOrDefault returned null at index " + i);
          }
        });

        t1.start();
        t2.start();
        t1.join();
        t2.join();
      }
    }
  }

  /**
   * Verifies that concurrent add() calls from multiple threads produce a consistent array.
   * After N threads each add M items, the total size must be N*M and every index must hold
   * a non-null value.
   */
  @Test
  public void concurrentAddsShouldProduceConsistentSize() throws Exception {
    try (var allInterleavings =
        allInterleavings("concurrentAddsShouldProduceConsistentSize")) {
      while (allInterleavings.hasNext()) {
        var array = new CASObjectArray<Integer>();
        var itemsPerThread = 2;
        var numThreads = 2;

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
    }
  }

  private record Updater(Integer thread, Integer index) {

  }
}
