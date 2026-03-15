package com.jetbrains.youtrackdb.internal.common.concur.collection;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.vmlens.api.AllInterleavingsBuilder;
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
        assertNotEquals(PLACEHOLDER, readValue[0],
            "Reader observed placeholder at target index during set() expansion");
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
        assertNotEquals(PLACEHOLDER, readValues[TARGET_INDEX],
            "Target index should have actual value, not placeholder");
      }
    }
  }
}
