package com.jetbrains.youtrackdb.internal.common.concur.collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vmlens.api.AllInterleavingsBuilder;
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

  private static final int MAX_ITERATIONS = 100;

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

  /**
   * Verifies that concurrent set() calls targeting the same index never write
   * the value to the adjacent index (index + 1).
   *
   * <p>The race scenario: two threads both call set(targetIndex, value, placeholder)
   * when size == targetIndex. Both enter the {@code add(value)} fast path (line 74-76
   * in CASObjectArray). One writer's CAS succeeds at targetIndex, but the other's
   * CAS fails and retries inside {@code add()} — reading the now-incremented size
   * and writing its value to targetIndex + 1 instead of targetIndex. The second
   * writer then returns without falling through to the overwrite path, leaving
   * its value permanently at the wrong index.
   *
   * <p>Two writer threads call {@code set(TARGET_INDEX, value, PLACEHOLDER)}
   * concurrently. A reader thread continuously probes {@code getOrDefault(TARGET_INDEX + 1)}
   * and asserts that only the placeholder is ever visible there — never a writer value.
   * After all threads join, we also verify the final state: TARGET_INDEX must hold
   * a writer value, and TARGET_INDEX + 1 must hold only the placeholder.
   */
  @Test
  public void concurrentSetToSameIndexShouldNeverWriteToNextIndex() throws Exception {
    final Integer PLACEHOLDER = -1;
    final int TARGET_INDEX = 2;
    final int VALUE_A = 100;
    final int VALUE_B = 200;

    try (var allInterleavings = new AllInterleavingsBuilder()
        .withMaximumIterations(MAX_ITERATIONS)
        .build("concurrentSetToSameIndexShouldNeverWriteToNextIndex")) {
      while (allInterleavings.hasNext()) {
        var array = new CASObjectArray<Integer>();

        // Pre-fill the array so that size == TARGET_INDEX.
        // This ensures both writers enter the add(value) expansion path
        // at line 74-76 rather than the overwrite path at line 97.
        for (int i = 0; i < TARGET_INDEX; i++) {
          array.add(PLACEHOLDER);
        }

        // Collects any non-placeholder value the reader observes at
        // TARGET_INDEX + 1 during the concurrent set() calls.
        var violations = new CopyOnWriteArrayList<Integer>();

        var writer1 = new Thread(() -> {
          array.set(TARGET_INDEX, VALUE_A, PLACEHOLDER);
        });

        var writer2 = new Thread(() -> {
          array.set(TARGET_INDEX, VALUE_B, PLACEHOLDER);
        });

        // Reader continuously probes the slot right after the target.
        // If a writer's add() overshoots due to the CAS retry, the
        // writer value will appear here instead of the placeholder.
        var reader = new Thread(() -> {
          for (int probe = 0; probe < 20; probe++) {
            try {
              var val = array.get(TARGET_INDEX + 1);
              if (!PLACEHOLDER.equals(val)) {
                violations.add(val);
              }
            } catch (ArrayIndexOutOfBoundsException e) {
              // index is not written yet, continue
              continue;
            }
          }
        });

        reader.start();
        writer1.start();
        writer2.start();
        writer1.join();
        writer2.join();
        reader.join();

        // The target index must hold one of the writer values.
        var targetValue = array.get(TARGET_INDEX);
        assertTrue(
            targetValue.equals(VALUE_A) || targetValue.equals(VALUE_B),
            "Target index should hold VALUE_A or VALUE_B, got: " + targetValue);

        // The slot at TARGET_INDEX + 1 must never have been written with
        // a writer value. If the bug triggers, one writer's add() overshoots
        // and lands its value here.
        var nextValue = array.get(TARGET_INDEX);
        assertTrue(
            PLACEHOLDER.equals(nextValue),
            "Index " + (TARGET_INDEX + 1) + " should only contain the placeholder ("
                + PLACEHOLDER + "), but found: " + nextValue
                + " — a set() targeting index " + TARGET_INDEX
                + " wrote its value to the wrong slot");

        // Also verify that the reader never observed a writer value leak
        // during execution, not just after completion.
        assertTrue(
            violations.isEmpty(),
            "Reader observed non-placeholder values at index " + (TARGET_INDEX + 1)
                + " during concurrent set(): " + violations);

        // If both writers correctly targeted index TARGET_INDEX, the size
        // should be TARGET_INDEX + 1 (one expansion). If a writer overshot,
        // the size would be TARGET_INDEX + 2.
        assertEquals(TARGET_INDEX + 1, array.size(),
            "Array grew beyond expected size — a writer likely wrote to the wrong index");
      }
    }
  }
}
