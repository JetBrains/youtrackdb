package com.jetbrains.youtrackdb.internal.core.record.impl;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import javax.annotation.Nonnull;
import org.junit.Assert;
import org.junit.Test;

/**
 * Unit tests for {@link PreFilterableChainedIterable}.
 *
 * <p>Uses a lightweight {@link StubSub} fake implementation of
 * {@link PreFilterableLinkBagIterable} so we can exercise {@link
 * PreFilterableChainedIterable} contracts in isolation — {@link #size()} summation, {@link
 * #isSizeable()} aggregation, filter delegation, and iterator behavior — without provisioning a
 * real database. Integration tests that verify the end-to-end MATCH pre-filter wiring live in
 * {@code MatchEdgeMethodPreFilterTest} and {@code MatchPreFilterComprehensiveTest}.
 */
public class PreFilterableChainedIterableTest {

  // ---------- filter delegation ----------

  /**
   * {@code withClassFilter} must call {@code withClassFilter} on EVERY sub with the SAME {@link
   * IntSet}, and the returned chained iterable must preserve sub order so iteration semantics are
   * unchanged.
   */
  @Test
  public void withClassFilterDelegatesToEachSubInOrder() {
    var sub1 = new StubSub("first", List.of(elem("a")), 1, true);
    var sub2 = new StubSub("second", List.of(elem("b"), elem("c")), 2, true);
    var chained = new PreFilterableChainedIterable(sub1, sub2);

    IntSet classIds = new IntOpenHashSet(new int[] {7, 9});
    var filtered = chained.withClassFilter(classIds);

    Assert.assertNotSame(
        "withClassFilter must return a fresh PreFilterableChainedIterable, not mutate in place",
        chained, filtered);
    Assert.assertSame(
        "sub1 must have been invoked with the exact IntSet passed to the chain",
        classIds, sub1.lastClassFilter);
    Assert.assertSame(
        "sub2 must have been invoked with the exact IntSet passed to the chain",
        classIds, sub2.lastClassFilter);
    Assert.assertEquals(
        "element order across filtered subs must match original iteration order",
        List.of("filtered:first:a", "filtered:second:b", "filtered:second:c"),
        drainToStrings(filtered));
  }

  /**
   * {@code withRidFilter} must delegate identically to {@code withClassFilter} — same RID set to
   * each sub, result is a new chain. Covers the RID-filter branch of the same pattern.
   */
  @Test
  public void withRidFilterDelegatesToEachSubInOrder() {
    var sub1 = new StubSub("left", List.of(elem("x")), 1, true);
    var sub2 = new StubSub("right", Collections.emptyList(), 0, true);
    var chained = new PreFilterableChainedIterable(sub1, sub2);

    Set<RID> rids = Set.of(new RecordId(12, 34), new RecordId(12, 56));
    var filtered = chained.withRidFilter(rids);

    Assert.assertNotSame(
        "withRidFilter must return a fresh PreFilterableChainedIterable, not mutate in place",
        chained, filtered);
    Assert.assertSame(
        "sub1 must have been invoked with the exact Set<RID> passed to the chain",
        rids, sub1.lastRidFilter);
    Assert.assertSame(
        "sub2 must have been invoked with the exact Set<RID> passed to the chain",
        rids, sub2.lastRidFilter);
    Assert.assertEquals(
        "only sub1 contributes elements after filtering; sub2 was empty",
        List.of("filtered:left:x"), drainToStrings(filtered));
  }

  // ---------- size + isSizeable ----------

  /**
   * {@code size()} returns the SUM of sub sizes. Exercises the loop body that accumulates across
   * all sub-iterables — a unit test for the adaptive-abort guard input in {@code
   * TraversalPreFilterHelper}.
   */
  @Test
  public void sizeReturnsSumOfSubSizes() {
    var sub1 = new StubSub("a", Collections.emptyList(), 3, true);
    var sub2 = new StubSub("b", Collections.emptyList(), 5, true);
    var sub3 = new StubSub("c", Collections.emptyList(), 7, true);
    var chained = new PreFilterableChainedIterable(sub1, sub2, sub3);

    Assert.assertEquals(
        "size() must equal the sum 3 + 5 + 7 = 15 across all sub-iterables",
        15, chained.size());
  }

  @Test
  public void sizeOfSingleSubEqualsThatSubSize() {
    var sub = new StubSub("only", Collections.emptyList(), 42, true);
    var chained = new PreFilterableChainedIterable(sub);

    Assert.assertEquals(
        "with a single sub, size() must be that sub's size verbatim",
        42, chained.size());
  }

  @Test
  public void sizeIsZeroWhenAllSubsAreEmpty() {
    var chained = new PreFilterableChainedIterable(
        new StubSub("a", Collections.emptyList(), 0, true),
        new StubSub("b", Collections.emptyList(), 0, true));

    Assert.assertEquals("sum of zero sizes must be 0", 0, chained.size());
  }

  /**
   * {@code isSizeable()} must short-circuit to {@code false} as soon as ANY sub reports
   * non-sizeable — the guard relies on this to fall back to unconditional loading when any
   * underlying LinkBag is of unknown size.
   */
  @Test
  public void isSizeableFalseWhenAnySubIsNotSizeable() {
    var sizeable = new StubSub("sizeable", Collections.emptyList(), 10, true);
    var notSizeable = new StubSub("unsized", Collections.emptyList(), 0, false);

    Assert.assertFalse(
        "chain containing a non-sizeable sub must itself report non-sizeable",
        new PreFilterableChainedIterable(sizeable, notSizeable).isSizeable());
    Assert.assertFalse(
        "non-sizeable sub in first position must still produce a non-sizeable chain",
        new PreFilterableChainedIterable(notSizeable, sizeable).isSizeable());
  }

  /**
   * {@code isSizeable()} returns {@code true} only when EVERY sub is sizeable. Covers the
   * loop-completes-without-returning-false path.
   */
  @Test
  public void isSizeableTrueWhenAllSubsAreSizeable() {
    var chained = new PreFilterableChainedIterable(
        new StubSub("a", Collections.emptyList(), 1, true),
        new StubSub("b", Collections.emptyList(), 2, true),
        new StubSub("c", Collections.emptyList(), 3, true));

    Assert.assertTrue(
        "all subs sizeable → chain is sizeable", chained.isSizeable());
  }

  // ---------- iterator ordering and exhaustion ----------

  /**
   * Iterator drains sub-iterables in the order they were passed to the constructor; elements from
   * later subs never appear before elements from earlier subs.
   */
  @Test
  public void iteratorDrainsSubsInConstructorOrder() {
    var sub1 = new StubSub("a", List.of(elem("1"), elem("2")), 2, true);
    var sub2 = new StubSub("b", List.of(elem("3")), 1, true);
    var sub3 = new StubSub("c", List.of(elem("4"), elem("5")), 2, true);

    var elements = drainToStrings(new PreFilterableChainedIterable(sub1, sub2, sub3));
    Assert.assertEquals(
        "iterator must yield sub1 elements, then sub2, then sub3, in registration order",
        List.of("a:1", "a:2", "b:3", "c:4", "c:5"), elements);
  }

  /**
   * Iterator correctly handles empty sub-iterables anywhere in the chain — at the start, middle,
   * and end — without prematurely terminating or throwing.
   */
  @Test
  public void iteratorSkipsEmptySubsAtAllPositions() {
    var empty = new StubSub("empty", Collections.emptyList(), 0, true);
    var singleton = new StubSub("single", List.of(elem("x")), 1, true);

    Assert.assertEquals(
        "empty sub at head must be skipped, yielding singleton elements only",
        List.of("single:x"), drainToStrings(new PreFilterableChainedIterable(empty, singleton)));
    Assert.assertEquals(
        "empty sub between non-empty subs must not break chaining",
        List.of("single:x", "single:x"),
        drainToStrings(new PreFilterableChainedIterable(singleton, empty, singleton)));
    Assert.assertEquals(
        "empty sub at tail must leave earlier elements intact",
        List.of("single:x"), drainToStrings(new PreFilterableChainedIterable(singleton, empty)));
  }

  /**
   * Calling {@code next()} on an exhausted iterator must throw {@link NoSuchElementException} per
   * the standard Iterator contract — not return {@code null}, not loop forever, not NPE.
   */
  @Test
  public void nextThrowsNoSuchElementExceptionWhenExhausted() {
    var chained = new PreFilterableChainedIterable(
        new StubSub("a", List.of(elem("only")), 1, true));
    var it = chained.iterator();

    Assert.assertTrue("sanity: iterator has the one element", it.hasNext());
    Assert.assertEquals(
        "next() returns the sub-tagged element in the expected form", "a:only", it.next());
    Assert.assertFalse("after draining the only element, iterator must be exhausted", it.hasNext());

    try {
      it.next();
      Assert.fail("next() on exhausted iterator must throw NoSuchElementException");
    } catch (NoSuchElementException expected) {
      // expected
    }
  }

  /**
   * Calling {@code next()} on a chain where ALL subs are empty must throw immediately —
   * {@code hasNext()} reports false from the start and {@code next()} must respect it.
   */
  @Test
  public void nextThrowsImmediatelyWhenAllSubsEmpty() {
    var chained = new PreFilterableChainedIterable(
        new StubSub("a", Collections.emptyList(), 0, true),
        new StubSub("b", Collections.emptyList(), 0, true));
    var it = chained.iterator();

    Assert.assertFalse("all subs empty → iterator starts already exhausted", it.hasNext());
    try {
      it.next();
      Assert.fail("next() on an all-empty chain must throw NoSuchElementException");
    } catch (NoSuchElementException expected) {
      // expected
    }
  }

  // ---------- test fixtures ----------

  /** Build a recognizable element tag so assertions can verify order + origin. */
  private static Object elem(String id) {
    return new Object() {
      @Override
      public String toString() {
        return id;
      }
    };
  }

  /** Drain a chained iterable to a list of {@code "<subName>:<element>"} strings. */
  private static List<String> drainToStrings(PreFilterableChainedIterable chained) {
    var out = new ArrayList<String>();
    for (var element : chained) {
      out.add(element.toString());
    }
    return out;
  }

  /**
   * Stub {@link PreFilterableLinkBagIterable}. Captures the most-recent filter arguments so tests
   * can assert delegation; produces elements prefixed with a sub name so iteration order is
   * verifiable. Filter methods return a NEW stub with elements prefixed {@code "filtered:"} — this
   * lets tests distinguish the pre-filter path from the identity path without resorting to
   * reference equality (which would be insufficient since {@link PreFilterableChainedIterable}
   * wraps filtered subs in a fresh array).
   */
  private static final class StubSub implements PreFilterableLinkBagIterable {
    private final String name;
    private final List<Object> elements;
    private final int declaredSize;
    private final boolean sizeable;
    IntSet lastClassFilter;
    Set<RID> lastRidFilter;

    StubSub(String name, List<Object> elements, int declaredSize, boolean sizeable) {
      this.name = name;
      this.elements = elements;
      this.declaredSize = declaredSize;
      this.sizeable = sizeable;
    }

    @Nonnull
    @Override
    public PreFilterableLinkBagIterable withClassFilter(@Nonnull IntSet collectionIds) {
      lastClassFilter = collectionIds;
      return new StubSub("filtered:" + name, elements, declaredSize, sizeable);
    }

    @Nonnull
    @Override
    public PreFilterableLinkBagIterable withRidFilter(@Nonnull Set<RID> ridSet) {
      lastRidFilter = ridSet;
      return new StubSub("filtered:" + name, elements, declaredSize, sizeable);
    }

    @Nonnull
    @Override
    public Iterator<?> iterator() {
      // Tag each element with the sub name for order/origin-verification in tests.
      var tagged = new ArrayList<String>(elements.size());
      for (var element : elements) {
        tagged.add(name + ":" + element);
      }
      return Arrays.asList(tagged.toArray()).iterator();
    }

    @Override
    public int size() {
      return declaredSize;
    }

    @Override
    public boolean isSizeable() {
      return sizeable;
    }
  }
}
