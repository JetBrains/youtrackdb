package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v1;

import org.junit.Assert;
import org.junit.Test;

/**
 * Dead-code shape pin for {@link SBTreeValue}.
 *
 * <p>PSI analysis confirmed that {@code SBTreeValue} has 8 main references, but all are
 * intra-package within {@code local/v1} itself (used by {@code SBTreeBucketV1} and
 * {@code SBTreeNullBucketV1}). Once the bucket classes are deleted, {@code SBTreeValue} becomes
 * unreachable and is deleted in the same coordinated commit.
 *
 * <p>These tests pin the {@code equals}/{@code hashCode}/{@code toString}/{@code isLink}/
 * {@code getLink}/{@code getValue} contracts so that the eventual deletion commit either removes
 * this file in lockstep or fails at compile time.
 *
 * <p>WHEN-FIXED: delete this file in the same commit that deletes the v1 source classes
 * ({@code SBTreeBucketV1}, {@code SBTreeNullBucketV1}, {@code SBTreeValue}) along with the
 * legacy test files ({@code SBTreeLeafBucketV1Test}, {@code SBTreeNonLeafBucketV1Test},
 * {@code SBTreeNullBucketV1Test}).
 */
public class SBTreeValueTest {

  /** equals is reflexive. */
  @Test
  public void equals_reflexive() {
    var v = new SBTreeValue<>(false, -1L, "hello");
    Assert.assertEquals("reflexive equals must return true", v, v);
  }

  /** equals is symmetric for identical values. */
  @Test
  public void equals_symmetricForIdenticalValues() {
    var v1 = new SBTreeValue<>(false, -1L, "test");
    var v2 = new SBTreeValue<>(false, -1L, "test");
    Assert.assertEquals("symmetric equals must return true", v1, v2);
    Assert.assertEquals("symmetric equals must return true (reverse)", v2, v1);
  }

  /** equals returns false when isLink differs. */
  @Test
  public void equals_falseWhenIsLinkDiffers() {
    var v1 = new SBTreeValue<>(true, 5L, null);
    var v2 = new SBTreeValue<>(false, 5L, null);
    Assert.assertNotEquals("different isLink must not be equal", v1, v2);
  }

  /** equals returns false when link value differs. */
  @Test
  public void equals_falseWhenLinkDiffers() {
    var v1 = new SBTreeValue<>(true, 10L, null);
    var v2 = new SBTreeValue<>(true, 20L, null);
    Assert.assertNotEquals("different link must not be equal", v1, v2);
  }

  /** equals returns false when the payload value differs. */
  @Test
  public void equals_falseWhenValueDiffers() {
    var v1 = new SBTreeValue<>(false, -1L, "a");
    var v2 = new SBTreeValue<>(false, -1L, "b");
    Assert.assertNotEquals("different value must not be equal", v1, v2);
  }

  /** equals returns false when compared to null. */
  @Test
  public void equals_falseForNull() {
    var v = new SBTreeValue<>(false, -1L, "x");
    Assert.assertNotEquals("equals(null) must return false", null, v);
  }

  /** equals returns false when compared to a different type. */
  @Test
  public void equals_falseForWrongType() {
    var v = new SBTreeValue<>(false, -1L, "x");
    Assert.assertNotEquals("equals(String) must return false", v, "x");
  }

  /** Two instances with null values are equal. */
  @Test
  public void equals_trueWhenBothValuesNull() {
    var v1 = new SBTreeValue<String>(false, -1L, null);
    var v2 = new SBTreeValue<String>(false, -1L, null);
    Assert.assertEquals("both-null-value instances must be equal", v1, v2);
  }

  /** hashCode is consistent for equal instances. */
  @Test
  public void hashCode_consistentForEqualInstances() {
    var v1 = new SBTreeValue<>(false, 7L, "text");
    var v2 = new SBTreeValue<>(false, 7L, "text");
    Assert.assertEquals("equal objects must have equal hashCodes", v1.hashCode(), v2.hashCode());
  }

  /** hashCode differs when value is null vs non-null. */
  @Test
  public void hashCode_sensitiveToNullValue() {
    var withValue = new SBTreeValue<>(false, 0L, "something");
    var withNull = new SBTreeValue<String>(false, 0L, null);
    Assert.assertNotEquals("hashCode must differ for null vs non-null value payload",
        withValue.hashCode(), withNull.hashCode());
  }

  /** toString includes all three fields. */
  @Test
  public void toString_containsAllFields() {
    var v = new SBTreeValue<>(true, 99L, null);
    var s = v.toString();
    Assert.assertTrue("toString must contain isLink=true", s.contains("isLink=true"));
    Assert.assertTrue("toString must contain link=99", s.contains("link=99"));
    Assert.assertTrue("toString must contain value=null", s.contains("value=null"));
  }

  /** getters return the values passed to the constructor. */
  @Test
  public void getters_returnConstructorValues() {
    var v = new SBTreeValue<>(true, 42L, null);
    Assert.assertTrue("isLink() must return true", v.isLink());
    Assert.assertEquals("getLink() must return 42", 42L, v.getLink());
    Assert.assertNull("getValue() must return null for a link entry", v.getValue());
  }

  /** Payload getter returns the correct non-null value. */
  @Test
  public void getValue_returnsNonNullPayload() {
    var v = new SBTreeValue<>(false, -1L, "payload");
    Assert.assertEquals("getValue() must return 'payload'", "payload", v.getValue());
    Assert.assertFalse("isLink() must be false for a value entry", v.isLink());
    Assert.assertEquals("getLink() must return -1 for a value entry", -1L, v.getLink());
  }
}
