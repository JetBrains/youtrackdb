package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.TreeSet;
import org.junit.Test;

/**
 * Tests for {@link PageKey} — the package-private composite (fileId, pageIndex) key used by
 * WOWCache's dirty-pages and write-cache maps. Pins equals/hashCode/compareTo/previous/toString
 * so a refactor that changes either the natural ordering (used by {@code TreeSet} in
 * {@code localDirtyPagesBySegment}) or the equals contract (used by {@code ConcurrentHashMap}
 * in {@code writeCachePages}) is detected as a regression.
 *
 * <p>{@code PageKey} is package-private; this test must live in the same package.
 */
public class PageKeyTest {

  /**
   * Two PageKeys with identical fileId and pageIndex must be equal and have equal hash codes —
   * the precondition for using PageKey as a {@code ConcurrentHashMap} key.
   */
  @Test
  public void testEqualsAndHashCodeForIdenticalKeys() {
    var a = new PageKey(7, 42L);
    var b = new PageKey(7, 42L);
    assertEquals(a, b);
    assertEquals(b, a);
    assertEquals(a.hashCode(), b.hashCode());
  }

  /** A PageKey is equal to itself (reflexivity). */
  @Test
  public void testEqualsReflexive() {
    var a = new PageKey(7, 42L);
    assertEquals(a, a);
  }

  /** Different fileId but same pageIndex must not be equal. */
  @Test
  public void testNotEqualsDifferentFileId() {
    var a = new PageKey(7, 42L);
    var b = new PageKey(8, 42L);
    assertNotEquals(a, b);
  }

  /** Same fileId but different pageIndex must not be equal. */
  @Test
  public void testNotEqualsDifferentPageIndex() {
    var a = new PageKey(7, 42L);
    var b = new PageKey(7, 43L);
    assertNotEquals(a, b);
  }

  /** A PageKey is not equal to null. */
  @SuppressWarnings("ConstantConditions")
  @Test
  public void testNotEqualsNull() {
    var a = new PageKey(7, 42L);
    assertFalse(a.equals(null));
  }

  /** A PageKey is not equal to an object of a different class. */
  @SuppressWarnings("EqualsBetweenInconvertibleTypes")
  @Test
  public void testNotEqualsDifferentType() {
    var a = new PageKey(7, 42L);
    assertFalse(a.equals("not a page key"));
  }

  /**
   * compareTo orders by fileId first, then pageIndex — required by {@code TreeSet} in
   * {@code localDirtyPagesBySegment} so iteration produces (fileId, pageIndex) ascending.
   */
  @Test
  public void testCompareToOrdersByFileIdThenPageIndex() {
    var lowFileLowPage = new PageKey(1, 0L);
    var lowFileHighPage = new PageKey(1, 100L);
    var highFileLowPage = new PageKey(2, 0L);

    // fileId dominates: any page in file 1 < any page in file 2
    assertTrue(lowFileHighPage.compareTo(highFileLowPage) < 0);
    assertTrue(highFileLowPage.compareTo(lowFileHighPage) > 0);

    // Within same fileId, lower pageIndex sorts first
    assertTrue(lowFileLowPage.compareTo(lowFileHighPage) < 0);
    assertTrue(lowFileHighPage.compareTo(lowFileLowPage) > 0);
  }

  /** compareTo on equal keys returns 0. */
  @Test
  public void testCompareToEqualKeys() {
    var a = new PageKey(5, 10L);
    var b = new PageKey(5, 10L);
    assertEquals(0, a.compareTo(b));
  }

  /**
   * TreeSet iteration produces keys in (fileId, pageIndex) ascending order. Pins the
   * compareTo contract by observing the actual end-to-end TreeSet behavior, not just the
   * sign of compareTo.
   */
  @Test
  public void testTreeSetIterationOrder() {
    var set = new TreeSet<PageKey>();
    set.add(new PageKey(2, 0L));
    set.add(new PageKey(1, 100L));
    set.add(new PageKey(1, 0L));
    set.add(new PageKey(2, 50L));

    var iter = set.iterator();
    assertEquals(new PageKey(1, 0L), iter.next());
    assertEquals(new PageKey(1, 100L), iter.next());
    assertEquals(new PageKey(2, 0L), iter.next());
    assertEquals(new PageKey(2, 50L), iter.next());
    assertFalse(iter.hasNext());
  }

  /**
   * previous() returns a new PageKey with pageIndex - 1 (same fileId). Used by WOWCache to
   * walk backwards over dirty pages within a file.
   */
  @Test
  public void testPreviousNormalCase() {
    var key = new PageKey(7, 5L);
    var prev = key.previous();
    assertEquals(7, prev.fileId);
    assertEquals(4L, prev.pageIndex);
  }

  /**
   * previous() returns the same instance when pageIndex is -1, avoiding integer underflow.
   * The boundary is documented in the source: {@code pageIndex == -1 ? this : new PageKey(...)}.
   */
  @Test
  public void testPreviousAtMinusOneReturnsSameInstance() {
    var key = new PageKey(7, -1L);
    var prev = key.previous();
    // Identity is part of the contract: the implementation returns `this` to avoid
    // allocation when there is no smaller key — pinning identity catches a refactor that
    // accidentally constructs a new PageKey(fileId, -2) (which would be a real bug because
    // the dirty-pages map uses pageIndex == -1 as a sentinel).
    assertSame(key, prev);
  }

  /**
   * toString produces a stable, recognisable format. Used in log messages and assertions —
   * a refactor that changes the format would break log greps and is worth flagging.
   */
  @Test
  public void testToStringFormat() {
    var key = new PageKey(7, 42L);
    assertEquals("PageKey{fileId=7, pageIndex=42}", key.toString());
  }
}
