package com.jetbrains.youtrackdb.internal.common.stream;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * Tests for {@link BreakingForEach} — stream iteration with early termination
 * via the {@link BreakingForEach.Breaker} callback.
 */
public class BreakingForEachTest {

  @Test
  public void testForEachIteratesToEnd() {
    // Without calling stop(), all elements are visited.
    var collected = new ArrayList<Integer>();
    BreakingForEach.forEach(
        Stream.of(1, 2, 3, 4, 5),
        (elem, breaker) -> collected.add(elem));
    assertEquals(List.of(1, 2, 3, 4, 5), collected);
  }

  @Test
  public void testForEachStopAfterFirstElement() {
    // Calling stop() after the first element should prevent further iteration.
    var collected = new ArrayList<Integer>();
    BreakingForEach.forEach(
        Stream.of(1, 2, 3),
        (elem, breaker) -> {
          collected.add(elem);
          breaker.stop();
        });
    assertEquals(List.of(1), collected);
  }

  @Test
  public void testForEachStopMidStream() {
    // Stop after the third element — elements 4 and 5 should not be visited.
    var collected = new ArrayList<Integer>();
    BreakingForEach.forEach(
        Stream.of(1, 2, 3, 4, 5),
        (elem, breaker) -> {
          collected.add(elem);
          if (elem == 3) {
            breaker.stop();
          }
        });
    assertEquals(List.of(1, 2, 3), collected);
  }

  @Test
  public void testForEachEmptyStream() {
    // An empty stream should not invoke the consumer at all.
    var collected = new ArrayList<Integer>();
    BreakingForEach.forEach(
        Stream.<Integer>empty(),
        (elem, breaker) -> collected.add(elem));
    assertEquals(List.of(), collected);
  }

  @Test
  public void testForEachSingleElement() {
    // A single-element stream: element is visited, no further iteration.
    var collected = new ArrayList<String>();
    BreakingForEach.forEach(
        Stream.of("only"),
        (elem, breaker) -> collected.add(elem));
    assertEquals(List.of("only"), collected);
  }

  @Test
  public void testForEachStopOnCondition() {
    // Stop when a specific condition is met — collect elements until "stop".
    var collected = new ArrayList<String>();
    BreakingForEach.forEach(
        Stream.of("go", "go", "stop", "go"),
        (elem, breaker) -> {
          collected.add(elem);
          if ("stop".equals(elem)) {
            breaker.stop();
          }
        });
    assertEquals(List.of("go", "go", "stop"), collected);
  }
}
