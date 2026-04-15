package com.jetbrains.youtrackdb.internal.common.stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * Tests for {@link Streams#mergeSortedSpliterators} — merging two sorted streams
 * with deduplication, comparator and Comparable paths, and resource cleanup.
 */
public class StreamsTest {

  // --- mergeSortedSpliterators: basic merging ---

  @Test
  public void testMergeBothEmpty() {
    // Merging two empty streams produces an empty stream.
    var result = Streams.mergeSortedSpliterators(
        Stream.<Integer>empty(), Stream.<Integer>empty(), Comparator.naturalOrder());
    assertArrayEquals(new Integer[0], result.toArray(Integer[]::new));
  }

  @Test
  public void testMergeFirstEmptySecondNonEmpty() {
    // When the first stream is empty, all elements come from the second.
    var result = Streams.mergeSortedSpliterators(
        Stream.<Integer>empty(), Stream.of(1, 2, 3), Comparator.naturalOrder());
    assertArrayEquals(new Integer[] {1, 2, 3}, result.toArray(Integer[]::new));
  }

  @Test
  public void testMergeFirstNonEmptySecondEmpty() {
    // When the second stream is empty, all elements come from the first.
    var result = Streams.mergeSortedSpliterators(
        Stream.of(1, 2, 3), Stream.<Integer>empty(), Comparator.naturalOrder());
    assertArrayEquals(new Integer[] {1, 2, 3}, result.toArray(Integer[]::new));
  }

  @Test
  public void testMergeInterleavedStreams() {
    // Two interleaved sorted streams merge into a single sorted sequence.
    var result = Streams.mergeSortedSpliterators(
        Stream.of(1, 3, 5), Stream.of(2, 4, 6), Comparator.naturalOrder());
    assertArrayEquals(new Integer[] {1, 2, 3, 4, 5, 6}, result.toArray(Integer[]::new));
  }

  @Test
  public void testMergeFirstStreamExhaustedFirst() {
    // When the first stream finishes before the second, remaining second-stream
    // elements are emitted.
    var result = Streams.mergeSortedSpliterators(
        Stream.of(1), Stream.of(2, 3, 4), Comparator.naturalOrder());
    assertArrayEquals(new Integer[] {1, 2, 3, 4}, result.toArray(Integer[]::new));
  }

  @Test
  public void testMergeSecondStreamExhaustedFirst() {
    // When the second stream finishes before the first, remaining first-stream
    // elements are emitted.
    var result = Streams.mergeSortedSpliterators(
        Stream.of(1, 3, 5), Stream.of(2), Comparator.naturalOrder());
    assertArrayEquals(new Integer[] {1, 2, 3, 5}, result.toArray(Integer[]::new));
  }

  // --- mergeSortedSpliterators: deduplication ---

  @Test
  public void testMergeEqualValuesDeduplicatedWhenEquals() {
    // When comparator returns 0 and equals() is true, duplicates are removed.
    var result = Streams.mergeSortedSpliterators(
        Stream.of(1, 3, 5), Stream.of(1, 3, 5), Comparator.naturalOrder());
    assertArrayEquals(new Integer[] {1, 3, 5}, result.toArray(Integer[]::new));
  }

  @Test
  public void testMergeComparatorZeroButNotEquals() {
    // When comparator returns 0 but equals() is false, both values are emitted.
    // Use a comparator that compares only the first character.
    var prefixComparator = Comparator.comparingInt((String s) -> s.charAt(0));
    var result = Streams.mergeSortedSpliterators(
        Stream.of("a1", "b1"), Stream.of("a2", "b2"), prefixComparator);
    // comparator sees "a1" == "a2" (same first char), but equals() is false,
    // so "a1" is emitted (firstValue when comparator == 0 and !equals).
    // Then "a2" still has secondValue cached from previous iteration.
    var items = result.toArray(String[]::new);
    // Expected: a1, a2, b1, b2 — each pair has comparator==0 but !equals,
    // so firstValue is emitted, then secondValue is compared next iteration.
    assertArrayEquals(new String[] {"a1", "a2", "b1", "b2"}, items);
  }

  // --- mergeSortedSpliterators: comparator < 0 and > 0 paths ---

  @Test
  public void testMergeComparatorLessThanZero() {
    // When comparator returns < 0, firstValue is emitted.
    var result = Streams.mergeSortedSpliterators(
        Stream.of(1, 5), Stream.of(3, 7), Comparator.naturalOrder());
    // Merge: 1 < 3 → emit 1; 5 > 3 → emit 3; 5 < 7 → emit 5; emit 7
    assertArrayEquals(new Integer[] {1, 3, 5, 7}, result.toArray(Integer[]::new));
  }

  @Test
  public void testMergeComparatorGreaterThanZero() {
    // When comparator returns > 0 for the first pair, secondValue is emitted first.
    var result = Streams.mergeSortedSpliterators(
        Stream.of(10, 20), Stream.of(5, 15), Comparator.naturalOrder());
    // Merge: 10 > 5 → emit 5; 10 < 15 → emit 10; 20 > 15 → emit 15; emit 20
    assertArrayEquals(new Integer[] {5, 10, 15, 20}, result.toArray(Integer[]::new));
  }

  // --- mergeSortedSpliterators: null comparator (Comparable path) ---

  @Test
  public void testMergeNullComparatorWithComparableObjects() {
    // When comparator is null, the implementation falls back to Comparable.compareTo.
    var result = Streams.mergeSortedSpliterators(
        Stream.of("a", "c", "e"), Stream.of("b", "d", "f"), null);
    assertArrayEquals(new String[] {"a", "b", "c", "d", "e", "f"},
        result.toArray(String[]::new));
  }

  @Test
  public void testMergeNullComparatorDoesNotDeduplicateEqualComparables() {
    // Unlike the explicit-comparator path, the Comparable fallback does not
    // deduplicate when compareTo returns 0 — both copies are emitted.
    var result = Streams.mergeSortedSpliterators(
        Stream.of(1, 3, 5), Stream.of(1, 3, 5), (Comparator<Integer>) null);
    assertArrayEquals(new Integer[] {1, 1, 3, 3, 5, 5}, result.toArray(Integer[]::new));
  }

  @Test
  public void testMergeNullComparatorWithNonComparable() {
    // When comparator is null and values are not Comparable, throws
    // IllegalArgumentException.
    var s1 = Stream.of(new Object());
    var s2 = Stream.of(new Object());
    try {
      var merged = Streams.mergeSortedSpliterators(s1, s2, null);
      // Force evaluation — the exception is thrown during tryAdvance
      merged.toArray();
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage().contains("Cannot compare values"));
    }
  }

  // --- composedClose: resource cleanup ---

  @Test
  public void testCloseCallsBothStreams() {
    // When the merged stream is closed, both underlying streams are closed.
    var firstClosed = new AtomicBoolean(false);
    var secondClosed = new AtomicBoolean(false);
    var s1 = Stream.of(1, 2).onClose(() -> firstClosed.set(true));
    var s2 = Stream.of(3, 4).onClose(() -> secondClosed.set(true));

    var merged = Streams.mergeSortedSpliterators(s1, s2, Comparator.naturalOrder());
    merged.close();

    assertTrue("First stream should be closed", firstClosed.get());
    assertTrue("Second stream should be closed", secondClosed.get());
  }

  @Test
  public void testCloseFirstThrowsSecondStillClosed() {
    // When the first stream's close throws, the second stream is still closed,
    // and the exception propagates.
    var secondClosed = new AtomicBoolean(false);
    var s1 = Stream.of(1).onClose(() -> {
      throw new RuntimeException("close error 1");
    });
    var s2 = Stream.of(2).onClose(() -> secondClosed.set(true));

    var merged = Streams.mergeSortedSpliterators(s1, s2, Comparator.naturalOrder());
    try {
      merged.close();
      fail("Expected RuntimeException from first stream close");
    } catch (RuntimeException e) {
      assertTrue(e.getMessage().contains("close error 1"));
    }
    assertTrue("Second stream should be closed even when first throws",
        secondClosed.get());
  }

  @Test
  public void testCloseBothThrowSuppressedException() {
    // When both streams throw on close, the second exception is suppressed
    // on the first.
    var s1 = Stream.of(1).onClose(() -> {
      throw new RuntimeException("close error 1");
    });
    var s2 = Stream.of(2).onClose(() -> {
      throw new RuntimeException("close error 2");
    });

    var merged = Streams.mergeSortedSpliterators(s1, s2, Comparator.naturalOrder());
    try {
      merged.close();
      fail("Expected RuntimeException from close");
    } catch (RuntimeException e) {
      assertTrue(e.getMessage().contains("close error 1"));
      var suppressed = e.getSuppressed();
      assertEquals("Second close exception should be suppressed", 1, suppressed.length);
      assertTrue(suppressed[0].getMessage().contains("close error 2"));
    }
  }

  @Test
  public void testCloseSecondThrowsFirstSucceeds() {
    // When only the second stream's close throws, the exception propagates directly
    // via the non-catch path in composedClose.
    var firstClosed = new AtomicBoolean(false);
    var s1 = Stream.of(1).onClose(() -> firstClosed.set(true));
    var s2 = Stream.of(2).onClose(() -> {
      throw new RuntimeException("close error 2");
    });

    var merged = Streams.mergeSortedSpliterators(s1, s2, Comparator.naturalOrder());
    try {
      merged.close();
      fail("Expected RuntimeException from second stream close");
    } catch (RuntimeException e) {
      assertTrue(e.getMessage().contains("close error 2"));
    }
    assertTrue("First stream should have been closed before second threw",
        firstClosed.get());
  }

  // --- mergeSortedSpliterators: single-element streams ---

  @Test
  public void testMergeSingleElementStreams() {
    // Merging two single-element streams.
    var result = Streams.mergeSortedSpliterators(
        Stream.of(2), Stream.of(1), Comparator.naturalOrder());
    assertArrayEquals(new Integer[] {1, 2}, result.toArray(Integer[]::new));
  }

  @Test
  public void testMergeSingleElementDuplicate() {
    // Merging two single-element streams with the same value — deduplicates.
    var result = Streams.mergeSortedSpliterators(
        Stream.of(42), Stream.of(42), Comparator.naturalOrder());
    assertArrayEquals(new Integer[] {42}, result.toArray(Integer[]::new));
  }

  // --- mergeSortedSpliterators: partial overlap ---

  @Test
  public void testMergePartialOverlap() {
    // Two streams with some shared and some unique values.
    var result = Streams.mergeSortedSpliterators(
        Stream.of(1, 2, 3, 5), Stream.of(2, 4, 5, 6), Comparator.naturalOrder());
    assertArrayEquals(new Integer[] {1, 2, 3, 4, 5, 6}, result.toArray(Integer[]::new));
  }
}
