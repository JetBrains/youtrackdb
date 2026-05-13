package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import org.junit.Test;

/**
 * Tests for {@link CellBTreeSingleValueEntryV3} covering the contract of {@code equals},
 * {@code hashCode}, {@code toString}, and {@code compareTo}. The entry is a structural value
 * type used by the B-tree bucket during split and traversal operations; correct equality and
 * ordering are essential for correctness of the tree's insertion and lookup invariants.
 */
public class CellBTreeSingleValueEntryV3Test {

  private static CellBTreeSingleValueEntryV3<String> entry(
      int left, int right, String key, RecordId rid) {
    return new CellBTreeSingleValueEntryV3<>(left, right, key, rid);
  }

  // ─────────────────────────────────────────────────────────────────────────
  // equals
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * An entry is equal to itself (reflexive contract of {@link Object#equals}).
   */
  @Test
  public void equalsReturnsTrue_whenSameInstance() {
    var e = entry(1, 2, "key", new RecordId(1, 100));
    assertTrue("Entry must equal itself", e.equals(e));
  }

  /**
   * Two entries with identical fields must be equal.
   */
  @Test
  public void equalsReturnsTrue_whenAllFieldsMatch() {
    var e1 = entry(3, 7, "abc", new RecordId(0, 42));
    var e2 = entry(3, 7, "abc", new RecordId(0, 42));
    assertTrue("Entries with identical fields must be equal", e1.equals(e2));
  }

  /**
   * An entry is not equal to {@code null}.
   */
  @Test
  public void equalsReturnsFalse_whenComparedWithNull() {
    var e = entry(0, 0, "k", new RecordId(0, 0));
    assertFalse("Entry must not equal null", e.equals(null));
  }

  /**
   * An entry is not equal to an object of a different class.
   */
  @Test
  public void equalsReturnsFalse_whenComparedWithDifferentType() {
    var e = entry(0, 0, "k", new RecordId(0, 0));
    assertFalse("Entry must not equal a String", e.equals("not-an-entry"));
  }

  /**
   * Entries with different left-child pointers must not be equal.
   */
  @Test
  public void equalsReturnsFalse_whenLeftChildDiffers() {
    var e1 = entry(1, 7, "k", new RecordId(0, 1));
    var e2 = entry(2, 7, "k", new RecordId(0, 1));
    assertFalse("Different leftChild must break equality", e1.equals(e2));
  }

  /**
   * Entries with different right-child pointers must not be equal.
   */
  @Test
  public void equalsReturnsFalse_whenRightChildDiffers() {
    var e1 = entry(1, 7, "k", new RecordId(0, 1));
    var e2 = entry(1, 8, "k", new RecordId(0, 1));
    assertFalse("Different rightChild must break equality", e1.equals(e2));
  }

  // ─────────────────────────────────────────────────────────────────────────
  // hashCode
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Two equal entries must have the same hash code (contract of {@link Object#hashCode}).
   */
  @Test
  public void hashCode_equalEntriesHaveSameHash() {
    var e1 = entry(3, 7, "abc", new RecordId(0, 42));
    var e2 = entry(3, 7, "abc", new RecordId(0, 42));
    assertEquals("Equal entries must have the same hashCode", e1.hashCode(), e2.hashCode());
  }

  /**
   * Two entries that differ in at least one field should (in practice) have different
   * hash codes. This is not a strict contract requirement but verifies the hash is
   * sensitive to field values.
   */
  @Test
  public void hashCode_differentEntriesHaveDifferentHash() {
    var e1 = entry(1, 2, "key1", new RecordId(0, 1));
    var e2 = entry(1, 2, "key2", new RecordId(0, 2));
    assertNotEquals("Entries differing in key/value should have different hashCodes",
        e1.hashCode(), e2.hashCode());
  }

  // ─────────────────────────────────────────────────────────────────────────
  // toString
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * {@code toString()} must include the key and the child-pointer values so that it is
   * useful for debugging without requiring a debugger.
   */
  @Test
  public void toString_containsKeyAndChildPointers() {
    var e = entry(10, 20, "myKey", new RecordId(3, 99));
    var s = e.toString();
    assertTrue("toString() must contain key 'myKey'", s.contains("myKey"));
    assertTrue("toString() must contain leftChild 10", s.contains("10"));
    assertTrue("toString() must contain rightChild 20", s.contains("20"));
  }

  // ─────────────────────────────────────────────────────────────────────────
  // compareTo
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * An entry with a lexicographically smaller key must compare as less-than. The reverse
   * direction must be greater-than (asymmetric ordering), and the signed magnitudes must be
   * negations of each other (antisymmetry contract of {@link Comparable}).
   */
  @Test
  public void compareTo_smallerKeyComparesLess() {
    var e1 = entry(0, 0, "apple", new RecordId(0, 0));
    var e2 = entry(0, 0, "banana", new RecordId(0, 0));
    assertTrue("'apple' entry must compare less than 'banana' entry", e1.compareTo(e2) < 0);
    assertTrue("'banana' entry must compare greater than 'apple' entry (reverse)",
        e2.compareTo(e1) > 0);
    assertEquals(
        "compareTo must satisfy antisymmetry: signum(a.compareTo(b)) == -signum(b.compareTo(a))",
        Integer.signum(e1.compareTo(e2)), -Integer.signum(e2.compareTo(e1)));
  }

  /**
   * An entry with a larger key must compare as greater-than. The reverse direction must be
   * less-than, and the signed magnitudes must be negations of each other.
   */
  @Test
  public void compareTo_largerKeyComparesGreater() {
    var e1 = entry(0, 0, "zebra", new RecordId(0, 0));
    var e2 = entry(0, 0, "ant", new RecordId(0, 0));
    assertTrue("'zebra' entry must compare greater than 'ant' entry", e1.compareTo(e2) > 0);
    assertTrue("'ant' entry must compare less than 'zebra' entry (reverse)",
        e2.compareTo(e1) < 0);
    assertEquals(
        "compareTo must satisfy antisymmetry: signum(a.compareTo(b)) == -signum(b.compareTo(a))",
        Integer.signum(e1.compareTo(e2)), -Integer.signum(e2.compareTo(e1)));
  }

  /**
   * Two entries with equal keys must compare as zero, regardless of child pointers or values.
   * Antisymmetry holds trivially when both compareTo results are 0.
   */
  @Test
  public void compareTo_equalKeyComparesZero() {
    var e1 = entry(1, 2, "sameKey", new RecordId(0, 10));
    var e2 = entry(5, 6, "sameKey", new RecordId(1, 20));
    assertEquals("Entries with the same key must compare as 0", 0, e1.compareTo(e2));
    assertEquals("Reverse comparison of equal keys must also be 0", 0, e2.compareTo(e1));
    assertEquals("compareTo must satisfy antisymmetry on equal keys",
        Integer.signum(e1.compareTo(e2)), -Integer.signum(e2.compareTo(e1)));
  }
}
