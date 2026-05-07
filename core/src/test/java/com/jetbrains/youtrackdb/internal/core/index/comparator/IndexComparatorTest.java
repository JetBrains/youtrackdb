package com.jetbrains.youtrackdb.internal.core.index.comparator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import org.junit.Test;

/**
 * Tests the comparator classes used for index entry ordering:
 * <ul>
 *   <li>{@link AscComparator} — compares pairs by key in ascending order</li>
 *   <li>{@link DescComparator} — compares pairs by key in ascending order (symmetric with Asc;
 *       the caller reverses direction by swapping arguments)</li>
 *   <li>{@link AlwaysGreaterKey} — sentinel that is always greater than any key</li>
 *   <li>{@link AlwaysLessKey} — sentinel that is always less than any key</li>
 * </ul>
 */
public class IndexComparatorTest {

  private static final RID DUMMY_RID = RecordIdInternal.fromString("#1:1", false);

  // ---- AscComparator ---------------------------------------------------------
  // Note: a "singleton tautology" test (assertEquals(INSTANCE, INSTANCE)) was removed —
  // any non-null object equals itself, so the assertion is uninformative. The behaviour
  // tests below cover the comparator's actual contract.

  /**
   * Verifies that AscComparator returns 0 for entries with equal integer keys.
   */
  @Test
  public void testAscComparatorEqualKeys() {
    var a = new RawPair<Object, RID>(10, DUMMY_RID);
    var b = new RawPair<Object, RID>(10, DUMMY_RID);
    assertEquals(0, AscComparator.INSTANCE.compare(a, b));
  }

  /**
   * Verifies that AscComparator returns negative when the first key is less than the second.
   */
  @Test
  public void testAscComparatorLessThan() {
    var a = new RawPair<Object, RID>(5, DUMMY_RID);
    var b = new RawPair<Object, RID>(10, DUMMY_RID);
    assertTrue(AscComparator.INSTANCE.compare(a, b) < 0);
  }

  /**
   * Verifies that AscComparator returns positive when the first key is greater than the second.
   */
  @Test
  public void testAscComparatorGreaterThan() {
    var a = new RawPair<Object, RID>(20, DUMMY_RID);
    var b = new RawPair<Object, RID>(10, DUMMY_RID);
    assertTrue(AscComparator.INSTANCE.compare(a, b) > 0);
  }

  /**
   * Verifies AscComparator works correctly with String keys.
   */
  @Test
  public void testAscComparatorStringKeys() {
    var a = new RawPair<Object, RID>("apple", DUMMY_RID);
    var b = new RawPair<Object, RID>("banana", DUMMY_RID);
    assertTrue(AscComparator.INSTANCE.compare(a, b) < 0);
    assertTrue(AscComparator.INSTANCE.compare(b, a) > 0);
  }

  // ---- DescComparator --------------------------------------------------------
  // Singleton-tautology test removed for the same reason as the Asc variant above.

  /**
   * Verifies that DescComparator returns 0 for entries with equal integer keys.
   */
  @Test
  public void testDescComparatorEqualKeys() {
    var a = new RawPair<Object, RID>(10, DUMMY_RID);
    var b = new RawPair<Object, RID>(10, DUMMY_RID);
    assertEquals(0, DescComparator.INSTANCE.compare(a, b));
  }

  /**
   * Verifies DescComparator with ordered integer keys — comparison is by key value
   * (caller uses reversed argument order for descending iteration).
   */
  @Test
  public void testDescComparatorLessThan() {
    var a = new RawPair<Object, RID>(5, DUMMY_RID);
    var b = new RawPair<Object, RID>(10, DUMMY_RID);
    assertTrue(DescComparator.INSTANCE.compare(a, b) < 0);
  }

  /**
   * Verifies DescComparator returns positive when the first key is greater.
   */
  @Test
  public void testDescComparatorGreaterThan() {
    var a = new RawPair<Object, RID>(20, DUMMY_RID);
    var b = new RawPair<Object, RID>(10, DUMMY_RID);
    assertTrue(DescComparator.INSTANCE.compare(a, b) > 0);
  }

  // ---- AlwaysGreaterKey ------------------------------------------------------

  /**
   * Verifies that AlwaysGreaterKey.compareTo returns positive for any comparable value,
   * confirming it always acts as the maximum sentinel.
   */
  @Test
  public void testAlwaysGreaterKeyCompareTo() {
    var greater = new AlwaysGreaterKey();
    // compareTo returns 1 regardless of the argument
    assertEquals(1, greater.compareTo("anyString"));
    assertEquals(1, greater.compareTo(Integer.MAX_VALUE));
    assertEquals(1, greater.compareTo(null));
  }

  /**
   * Verifies that CompositeKey.compareTo treats an AlwaysGreaterKey slot as greater than
   * any normal key at that position — the composite key with a real value is less than
   * a composite with AlwaysGreaterKey.
   */
  @Test
  public void testCompositeKeyCompareToAlwaysGreaterKey() {
    var normal = new com.jetbrains.youtrackdb.internal.core.index.CompositeKey();
    normal.addKey("z");

    var withSentinel = new com.jetbrains.youtrackdb.internal.core.index.CompositeKey();
    withSentinel.addKey(new AlwaysGreaterKey());

    // normal < withSentinel because AlwaysGreaterKey is always greater
    assertTrue(normal.compareTo(withSentinel) < 0);
    assertTrue(withSentinel.compareTo(normal) > 0);
  }

  // ---- AlwaysLessKey ---------------------------------------------------------

  /**
   * Verifies that AlwaysLessKey.compareTo returns negative for any comparable value,
   * confirming it always acts as the minimum sentinel.
   */
  @Test
  public void testAlwaysLessKeyCompareTo() {
    var less = new AlwaysLessKey();
    assertEquals(-1, less.compareTo("anyString"));
    assertEquals(-1, less.compareTo(0));
    assertEquals(-1, less.compareTo(null));
  }

  /**
   * Verifies that CompositeKey.compareTo treats an AlwaysLessKey slot as less than
   * any normal key at that position.
   */
  @Test
  public void testCompositeKeyCompareToAlwaysLessKey() {
    var normal = new com.jetbrains.youtrackdb.internal.core.index.CompositeKey();
    normal.addKey("a");

    var withSentinel = new com.jetbrains.youtrackdb.internal.core.index.CompositeKey();
    withSentinel.addKey(new AlwaysLessKey());

    // normal > withSentinel because AlwaysLessKey is always less
    assertTrue(normal.compareTo(withSentinel) > 0);
    assertTrue(withSentinel.compareTo(normal) < 0);
  }
}
