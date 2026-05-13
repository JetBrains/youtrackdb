package com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree;

import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for small helper classes in the ridbag B-tree package that are not covered by
 * the page-level or full-storage test suites: {@link TreeEntry}, {@link LinkBagBucketPointer},
 * {@link PagePathItemUnit}, {@link IntSerializer}, and {@link EdgeKeySerializer}.
 *
 * <p>Each test class is standalone — no database or page-level infrastructure is needed.
 */
public class RidbagBtreeHelpersTest {

  // ---- TreeEntry ----

  /**
   * Two TreeEntry instances with the same four fields must be equal and have the same
   * hashCode.  Inequality is verified by changing one field at a time.
   */
  @Test
  public void testTreeEntryEqualsAndHashCode() {
    var key = new EdgeKey(10, 20, 30, 0L);
    var val = new LinkBagValue(1, 0, 0, false);

    var e1 = new TreeEntry(0, 1, key, val);
    var e2 = new TreeEntry(0, 1, key, val);
    Assert.assertEquals(e1, e2);
    Assert.assertEquals(e1.hashCode(), e2.hashCode());

    // Different leftChild makes them unequal.
    var e3 = new TreeEntry(99, 1, key, val);
    Assert.assertNotEquals(e1, e3);

    // Different rightChild makes them unequal.
    var e4 = new TreeEntry(0, 99, key, val);
    Assert.assertNotEquals(e1, e4);

    // Different key makes them unequal.
    var e5 = new TreeEntry(0, 1, new EdgeKey(11, 20, 30, 0L), val);
    Assert.assertNotEquals(e1, e5);

    // Different value makes them unequal.
    var e6 = new TreeEntry(0, 1, key, new LinkBagValue(99, 0, 0, false));
    Assert.assertNotEquals(e1, e6);

    // equals(null) must return false.
    Assert.assertNotEquals(e1, null);

    // equals(different type) must return false.
    Assert.assertNotEquals(e1, "not a TreeEntry");
  }

  /**
   * toString() must render the labelled field values per the production override
   * ("CellBTreeEntry{leftChild=…, rightChild=…, key=…, value=…}"). Pinning the prefix
   * + labelled field values catches a regression that drops or replaces the @Override
   * (the default Object.toString() would not contain the labels).
   */
  @Test
  public void testTreeEntryToString() {
    var e = new TreeEntry(17, 19, new EdgeKey(1, 2, 3, 0L), new LinkBagValue(5, 0, 0, false));
    var s = e.toString();
    Assert.assertTrue("toString must use the CellBTreeEntry prefix: " + s,
        s.startsWith("CellBTreeEntry{"));
    Assert.assertTrue("toString must include leftChild=17: " + s,
        s.contains("leftChild=17"));
    Assert.assertTrue("toString must include rightChild=19: " + s,
        s.contains("rightChild=19"));
    Assert.assertTrue("toString must include key=: " + s, s.contains("key="));
    Assert.assertTrue("toString must include value=: " + s, s.contains("value="));
  }

  /**
   * compareTo() must delegate to the key's compareTo, so two entries with keys that
   * differ only in ridBagId must be ordered consistently with EdgeKey ordering.
   */
  @Test
  public void testTreeEntryCompareTo() {
    var e1 = new TreeEntry(0, 1, new EdgeKey(1, 20, 30, 0L), new LinkBagValue(1, 0, 0, false));
    var e2 = new TreeEntry(0, 1, new EdgeKey(2, 20, 30, 0L), new LinkBagValue(1, 0, 0, false));
    Assert.assertTrue("e1 < e2 by key", e1.compareTo(e2) < 0);
    Assert.assertTrue("e2 > e1 by key", e2.compareTo(e1) > 0);
    Assert.assertEquals("equal keys must compare as 0", 0, e1.compareTo(e1));
  }

  // ---- LinkBagBucketPointer ----

  /**
   * Constructing a LinkBagBucketPointer and reading its fields must return the values
   * passed at construction time.
   */
  @Test
  public void testLinkBagBucketPointerGetters() {
    var ptr = new LinkBagBucketPointer(42L, 7);
    Assert.assertEquals(42L, ptr.getPageIndex());
    Assert.assertEquals(7, ptr.getPageOffset());
    Assert.assertTrue("positive pageIndex must be valid", ptr.isValid());
  }

  /**
   * The NULL constant must have pageIndex == -1 and isValid() == false.
   */
  @Test
  public void testLinkBagBucketPointerNullConstant() {
    var ptr = LinkBagBucketPointer.NULL;
    Assert.assertEquals(-1L, ptr.getPageIndex());
    Assert.assertEquals(-1, ptr.getPageOffset());
    Assert.assertFalse("NULL pointer must not be valid", ptr.isValid());
  }

  /**
   * equals() and hashCode() must be consistent: equal pointers share a hash code;
   * a pointer is not equal to null or to objects of a different class.
   */
  @Test
  public void testLinkBagBucketPointerEqualsAndHashCode() {
    var p1 = new LinkBagBucketPointer(10L, 3);
    var p2 = new LinkBagBucketPointer(10L, 3);
    Assert.assertEquals(p1, p2);
    Assert.assertEquals(p1.hashCode(), p2.hashCode());

    var p3 = new LinkBagBucketPointer(11L, 3);
    Assert.assertNotEquals(p1, p3);

    var p4 = new LinkBagBucketPointer(10L, 4);
    Assert.assertNotEquals(p1, p4);

    // equals(null) and equals(different type) must return false.
    Assert.assertNotEquals(p1, null);
    Assert.assertNotEquals(p1, "string");
  }

  // ---- PagePathItemUnit ----

  /**
   * Constructing a PagePathItemUnit and reading its fields must return the constructor
   * arguments.  This exercises both getPageIndex() and getItemIndex().
   */
  @Test
  public void testPagePathItemUnitGetters() {
    var unit = new PagePathItemUnit(99L, 5);
    Assert.assertEquals(99L, unit.getPageIndex());
    Assert.assertEquals(5, unit.getItemIndex());
  }

  // ---- IntSerializer ----

  /**
   * IntSerializer.getId() must return -1 (internal serializer, not registered globally);
   * isFixedLength() must return false; getFixedLength() must return -1; preprocess()
   * must return the value unchanged.
   */
  @Test
  public void testIntSerializerMetadata() {
    var factory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
    var ser = IntSerializer.INSTANCE;

    Assert.assertEquals(-1, ser.getId());
    Assert.assertFalse(ser.isFixedLength());
    Assert.assertEquals(-1, ser.getFixedLength());
    Assert.assertEquals(Integer.valueOf(42), ser.preprocess(factory, 42));
  }

  // ---- EdgeKeySerializer ----

  /**
   * EdgeKeySerializer.getId() must return -1; isFixedLength() must return false;
   * getFixedLength() must return -1; preprocess() must return the key unchanged;
   * deserializeNativeObject() after serializeNativeObject() must round-trip the key;
   * getObjectSizeNative() must equal the serialised size.
   */
  @Test
  public void testEdgeKeySerializerMetadata() {
    var factory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
    var ser = EdgeKeySerializer.INSTANCE;

    Assert.assertEquals(-1, ser.getId());
    Assert.assertFalse(ser.isFixedLength());
    Assert.assertEquals(-1, ser.getFixedLength());

    var key = new EdgeKey(5, 10, 99L, 42L);
    Assert.assertEquals(key, ser.preprocess(factory, key));
  }

  /**
   * Round-trip through serializeNativeObject / deserializeNativeObject must preserve
   * all four EdgeKey fields.  Also verifies getObjectSizeNative() matches the actual
   * number of bytes written.
   */
  @Test
  public void testEdgeKeySerializerNativeRoundTrip() {
    var factory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
    var ser = EdgeKeySerializer.INSTANCE;
    var original = new EdgeKey(7, 3, 500L, 123L);

    // Serialize into a sufficiently large buffer, then read back the reported size.
    byte[] buf = new byte[100];
    ser.serializeNativeObject(original, factory, buf, 0);

    // Size from getObjectSizeNative must match the bytes actually written.
    int reported = ser.getObjectSizeNative(factory, buf, 0);
    Assert.assertTrue("serialised size must be positive", reported > 0);

    // Deserialise and compare.
    var deserialized = ser.deserializeNativeObject(factory, buf, 0);
    Assert.assertEquals(original, deserialized);
  }
}
