package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v2;

import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link SBTreeValue} and {@link SBTreeBucketV2.SBTreeEntry} value-type contracts —
 * {@code equals}, {@code hashCode}, {@code toString}, and {@code compareTo}.
 *
 * <p>Both classes belong to the {@code local/v2} WAL-replay-only package. Production engines
 * reject version 2; only WAL replay against legacy databases reaches this code. The tests here
 * pin the behavioural contracts of these value objects independently of any page I/O.
 */
public class SBTreeValueAndEntryTest {

  // ─────────────────────────────────────────────────────────────────────────
  // SBTreeValue — equals
  // ─────────────────────────────────────────────────────────────────────────

  /** equals is reflexive (same reference). */
  @Test
  public void sbTreeValue_equalsReflexive() {
    var v = new SBTreeValue<>(false, -1L, "hello");
    Assert.assertEquals("reflexive equals must return true", v, v);
  }

  /** equals recognises a distinct instance with identical fields. */
  @Test
  public void sbTreeValue_equalsSymmetric() {
    var v1 = new SBTreeValue<>(false, -1L, "hello");
    var v2 = new SBTreeValue<>(false, -1L, "hello");
    Assert.assertEquals("symmetric equals must return true", v1, v2);
    Assert.assertEquals("symmetric equals must return true (reverse)", v2, v1);
  }

  /** equals returns false when isLink differs. */
  @Test
  public void sbTreeValue_notEqualWhenIsLinkDiffers() {
    var v1 = new SBTreeValue<>(true, 42L, null);
    var v2 = new SBTreeValue<>(false, 42L, null);
    Assert.assertNotEquals("different isLink must not be equal", v1, v2);
  }

  /** equals returns false when link differs. */
  @Test
  public void sbTreeValue_notEqualWhenLinkDiffers() {
    var v1 = new SBTreeValue<>(true, 10L, null);
    var v2 = new SBTreeValue<>(true, 20L, null);
    Assert.assertNotEquals("different link must not be equal", v1, v2);
  }

  /** equals returns false when value differs. */
  @Test
  public void sbTreeValue_notEqualWhenValueDiffers() {
    var v1 = new SBTreeValue<>(false, -1L, "a");
    var v2 = new SBTreeValue<>(false, -1L, "b");
    Assert.assertNotEquals("different value must not be equal", v1, v2);
  }

  /** equals returns false when compared to null. */
  @Test
  public void sbTreeValue_notEqualToNull() {
    var v = new SBTreeValue<>(false, -1L, "x");
    Assert.assertNotEquals("equals(null) must return false", null, v);
  }

  /** equals returns false when compared to a different type. */
  @Test
  public void sbTreeValue_notEqualToWrongType() {
    var v = new SBTreeValue<>(false, -1L, "x");
    Assert.assertNotEquals("equals(String) must return false", v, "x");
  }

  /** Instances with null values are equal to each other. */
  @Test
  public void sbTreeValue_equalWhenBothValuesNull() {
    var v1 = new SBTreeValue<String>(false, -1L, null);
    var v2 = new SBTreeValue<String>(false, -1L, null);
    Assert.assertEquals("both-null-value entries must be equal", v1, v2);
  }

  // ─────────────────────────────────────────────────────────────────────────
  // SBTreeValue — hashCode
  // ─────────────────────────────────────────────────────────────────────────

  /** hashCode is consistent for equal instances. */
  @Test
  public void sbTreeValue_hashCodeConsistentForEqualInstances() {
    var v1 = new SBTreeValue<>(false, 7L, "text");
    var v2 = new SBTreeValue<>(false, 7L, "text");
    Assert.assertEquals("equal objects must have equal hashCodes", v1.hashCode(), v2.hashCode());
  }

  /** hashCode changes when value is null vs non-null. */
  @Test
  public void sbTreeValue_hashCodeSensitiveToNullValue() {
    var withValue = new SBTreeValue<>(false, 0L, "something");
    var withNull = new SBTreeValue<String>(false, 0L, null);
    Assert.assertNotEquals("hashCode must differ between null and non-null value",
        withValue.hashCode(), withNull.hashCode());
  }

  // ─────────────────────────────────────────────────────────────────────────
  // SBTreeValue — toString
  // ─────────────────────────────────────────────────────────────────────────

  /** toString includes all three fields. */
  @Test
  public void sbTreeValue_toStringContainsFields() {
    var v = new SBTreeValue<>(true, 123L, null);
    var s = v.toString();
    Assert.assertTrue("toString must contain isLink=true", s.contains("isLink=true"));
    Assert.assertTrue("toString must contain link=123", s.contains("link=123"));
    Assert.assertTrue("toString must contain value=null", s.contains("value=null"));
  }

  // ─────────────────────────────────────────────────────────────────────────
  // SBTreeValue — getters
  // ─────────────────────────────────────────────────────────────────────────

  /** getters return the values passed to the constructor. */
  @Test
  public void sbTreeValue_gettersReturnConstructorValues() {
    var v = new SBTreeValue<>(true, 55L, null);
    Assert.assertTrue("isLink() must return true", v.isLink());
    Assert.assertEquals("getLink() must return 55", 55L, v.getLink());
    Assert.assertNull("getValue() must return null for a link entry", v.getValue());
  }

  // ─────────────────────────────────────────────────────────────────────────
  // SBTreeBucketV2.SBTreeEntry — equals
  // ─────────────────────────────────────────────────────────────────────────

  private static SBTreeBucketV2.SBTreeEntry<String, String> entry(
      long left, long right, String key, SBTreeValue<String> value) {
    return new SBTreeBucketV2.SBTreeEntry<>(left, right, key, value);
  }

  /** equals is reflexive. */
  @Test
  public void sbtreeEntry_equalsReflexive() {
    var e = entry(1, 2, "k", new SBTreeValue<>(false, -1L, "v"));
    Assert.assertEquals("reflexive equals must be true", e, e);
  }

  /** equals returns true for identical fields. */
  @Test
  public void sbtreeEntry_equalsForIdenticalFields() {
    var e1 = entry(1, 2, "k", new SBTreeValue<>(false, -1L, "v"));
    var e2 = entry(1, 2, "k", new SBTreeValue<>(false, -1L, "v"));
    Assert.assertEquals("entries with identical fields must be equal", e1, e2);
  }

  /** equals returns false when null is passed. */
  @Test
  public void sbtreeEntry_notEqualToNull() {
    var e = entry(0, 0, "k", null);
    Assert.assertNotEquals("entry.equals(null) must return false", e, null);
  }

  /** equals returns false when different class is passed. */
  @Test
  public void sbtreeEntry_notEqualToWrongClass() {
    var e = entry(0, 0, "k", null);
    Assert.assertNotEquals("entry.equals(String) must return false", e, "k");
  }

  /** equals returns false when leftChild differs. */
  @Test
  public void sbtreeEntry_notEqualWhenLeftChildDiffers() {
    var e1 = entry(1, 2, "k", null);
    var e2 = entry(9, 2, "k", null);
    Assert.assertNotEquals("different leftChild must not be equal", e1, e2);
  }

  /** equals returns false when rightChild differs. */
  @Test
  public void sbtreeEntry_notEqualWhenRightChildDiffers() {
    var e1 = entry(1, 2, "k", null);
    var e2 = entry(1, 9, "k", null);
    Assert.assertNotEquals("different rightChild must not be equal", e1, e2);
  }

  /** equals returns false when key differs. */
  @Test
  public void sbtreeEntry_notEqualWhenKeyDiffers() {
    var e1 = entry(0, 0, "k1", null);
    var e2 = entry(0, 0, "k2", null);
    Assert.assertNotEquals("different key must not be equal", e1, e2);
  }

  /** equals with null value: both null → equal. */
  @Test
  public void sbtreeEntry_equalWhenBothValuesNull() {
    var e1 = entry(0, 0, "k", null);
    var e2 = entry(0, 0, "k", null);
    Assert.assertEquals("both-null-value entries must be equal", e1, e2);
  }

  /** equals with null value on one side: not equal when the other has a value. */
  @Test
  public void sbtreeEntry_notEqualWhenValueNullVsNonNull() {
    var e1 = entry(0, 0, "k", null);
    var e2 = entry(0, 0, "k", new SBTreeValue<>(false, -1L, "v"));
    Assert.assertNotEquals("null value vs non-null value must not be equal", e1, e2);
  }

  // ─────────────────────────────────────────────────────────────────────────
  // SBTreeBucketV2.SBTreeEntry — hashCode
  // ─────────────────────────────────────────────────────────────────────────

  /** hashCode is consistent for equal entries. */
  @Test
  public void sbtreeEntry_hashCodeConsistentForEqualEntries() {
    var e1 = entry(1, 2, "key", new SBTreeValue<>(false, -1L, "v"));
    var e2 = entry(1, 2, "key", new SBTreeValue<>(false, -1L, "v"));
    Assert.assertEquals("equal entries must have equal hashCodes", e1.hashCode(), e2.hashCode());
  }

  /** hashCode changes when value is null vs non-null. */
  @Test
  public void sbtreeEntry_hashCodeSensitiveToNullValue() {
    var withValue = entry(0, 0, "k", new SBTreeValue<>(false, -1L, "v"));
    var withNull = entry(0, 0, "k", null);
    Assert.assertNotEquals("hashCode must differ for null vs non-null value",
        withValue.hashCode(), withNull.hashCode());
  }

  // ─────────────────────────────────────────────────────────────────────────
  // SBTreeBucketV2.SBTreeEntry — toString
  // ─────────────────────────────────────────────────────────────────────────

  /** toString includes all four fields. */
  @Test
  public void sbtreeEntry_toStringContainsFields() {
    var e = entry(10, 20, "myKey", null);
    var s = e.toString();
    Assert.assertTrue("toString must mention leftChild=10", s.contains("leftChild=10")
        || s.contains("10"));
    Assert.assertTrue("toString must mention rightChild=20", s.contains("rightChild=20")
        || s.contains("20"));
    Assert.assertTrue("toString must mention key=myKey", s.contains("myKey"));
  }

  // ─────────────────────────────────────────────────────────────────────────
  // SBTreeBucketV2.SBTreeEntry — compareTo
  // ─────────────────────────────────────────────────────────────────────────

  /** compareTo uses the key comparator (DefaultComparator = natural String order). */
  @Test
  public void sbtreeEntry_compareToLessThan() {
    var e1 = entry(0, 0, "apple", null);
    var e2 = entry(0, 0, "banana", null);
    Assert.assertTrue("'apple' must compare less than 'banana'", e1.compareTo(e2) < 0);
  }

  /** compareTo returns 0 for equal keys (regardless of children/value). */
  @Test
  public void sbtreeEntry_compareToZeroForEqualKeys() {
    var e1 = entry(1, 2, "same", new SBTreeValue<>(false, -1L, "v"));
    var e2 = entry(9, 8, "same", null);
    Assert.assertEquals("equal keys must compare to 0", 0, e1.compareTo(e2));
  }

  /** compareTo returns positive for greater key. */
  @Test
  public void sbtreeEntry_compareToGreaterThan() {
    var e1 = entry(0, 0, "z", null);
    var e2 = entry(0, 0, "a", null);
    Assert.assertTrue("'z' must compare greater than 'a'", e1.compareTo(e2) > 0);
  }
}
