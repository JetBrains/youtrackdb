package com.jetbrains.youtrackdb.internal.core.storage.collection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.TreeMap;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for the natural ordering and equality of {@link SnapshotKey} and
 * {@link VisibilityKey}. Both are records used as keys in
 * {@code ConcurrentSkipListMap}; their {@code compareTo()} implementations must
 * establish a consistent total ordering.
 */
public class SnapshotKeyVisibilityKeyTest {

  // ---- SnapshotKey compareTo ----

  /**
   * componentId has highest precedence: keys with different componentId values must be
   * ordered by componentId regardless of the other fields.
   */
  @Test
  public void testSnapshotKeyComponentIdPrecedence() {
    var k1 = new SnapshotKey(1, Long.MAX_VALUE, Long.MAX_VALUE);
    var k2 = new SnapshotKey(2, 0L, 0L);
    Assert.assertTrue("componentId 1 < componentId 2", k1.compareTo(k2) < 0);
    Assert.assertTrue("componentId 2 > componentId 1", k2.compareTo(k1) > 0);
  }

  /**
   * collectionPosition has second precedence: same componentId but different
   * collectionPosition must be ordered by collectionPosition.
   */
  @Test
  public void testSnapshotKeyCollectionPositionPrecedence() {
    var k1 = new SnapshotKey(5, 10L, Long.MAX_VALUE);
    var k2 = new SnapshotKey(5, 11L, 0L);
    Assert.assertTrue("smaller collectionPosition should be less", k1.compareTo(k2) < 0);
    Assert.assertTrue("larger collectionPosition should be greater", k2.compareTo(k1) > 0);
  }

  /**
   * recordVersion has lowest precedence: same componentId and collectionPosition but
   * different recordVersion must be ordered by recordVersion.
   */
  @Test
  public void testSnapshotKeyRecordVersionPrecedence() {
    var k1 = new SnapshotKey(5, 10L, 100L);
    var k2 = new SnapshotKey(5, 10L, 200L);
    Assert.assertTrue("smaller recordVersion should be less", k1.compareTo(k2) < 0);
    Assert.assertTrue("larger recordVersion should be greater", k2.compareTo(k1) > 0);
  }

  /**
   * Two keys with the same 3-tuple must compare as equal.
   */
  @Test
  public void testSnapshotKeyEqualComparison() {
    var k1 = new SnapshotKey(3, 42L, 99L);
    var k2 = new SnapshotKey(3, 42L, 99L);
    Assert.assertEquals(0, k1.compareTo(k2));
    Assert.assertEquals(k1, k2); // record equality via auto-generated equals
  }

  /**
   * SnapshotKey must work as a map key inside a TreeMap, preserving the 3-component
   * ordering.  Keys for the same (componentId, collectionPosition) prefix must be
   * adjacent and ordered by recordVersion.
   */
  @Test
  public void testSnapshotKeyTreeMapOrdering() {
    var map = new TreeMap<SnapshotKey, String>();
    map.put(new SnapshotKey(1, 10L, 300L), "v3");
    map.put(new SnapshotKey(1, 10L, 100L), "v1");
    map.put(new SnapshotKey(1, 10L, 200L), "v2");
    map.put(new SnapshotKey(1, 11L, 50L), "other");

    var keys = new ArrayList<>(map.keySet());
    Assert.assertEquals(new SnapshotKey(1, 10L, 100L), keys.get(0));
    Assert.assertEquals(new SnapshotKey(1, 10L, 200L), keys.get(1));
    Assert.assertEquals(new SnapshotKey(1, 10L, 300L), keys.get(2));
    Assert.assertEquals(new SnapshotKey(1, 11L, 50L), keys.get(3));
  }

  /**
   * Collections.sort must produce the same ordering as the natural ordering.
   */
  @Test
  public void testSnapshotKeySortOrdering() {
    var keys = new ArrayList<SnapshotKey>();
    keys.add(new SnapshotKey(2, 5L, 10L));
    keys.add(new SnapshotKey(1, 5L, 10L));
    keys.add(new SnapshotKey(1, 5L, 5L));
    keys.add(new SnapshotKey(1, 4L, 99L));
    Collections.sort(keys);

    Assert.assertEquals(new SnapshotKey(1, 4L, 99L), keys.get(0));
    Assert.assertEquals(new SnapshotKey(1, 5L, 5L), keys.get(1));
    Assert.assertEquals(new SnapshotKey(1, 5L, 10L), keys.get(2));
    Assert.assertEquals(new SnapshotKey(2, 5L, 10L), keys.get(3));
  }

  // ---- VisibilityKey compareTo ----

  /**
   * recordTs has highest precedence: keys with different recordTs must be ordered by
   * recordTs regardless of other fields.  This ordering enables efficient
   * headMap(lowWaterMark) range scans.
   */
  @Test
  public void testVisibilityKeyRecordTsPrecedence() {
    var k1 = new VisibilityKey(100L, Integer.MAX_VALUE, Long.MAX_VALUE);
    var k2 = new VisibilityKey(200L, 0, 0L);
    Assert.assertTrue("smaller recordTs should be less", k1.compareTo(k2) < 0);
    Assert.assertTrue("larger recordTs should be greater", k2.compareTo(k1) > 0);
  }

  /**
   * componentId has second precedence: same recordTs but different componentId must
   * be ordered by componentId.
   */
  @Test
  public void testVisibilityKeyComponentIdPrecedence() {
    var k1 = new VisibilityKey(50L, 1, Long.MAX_VALUE);
    var k2 = new VisibilityKey(50L, 2, 0L);
    Assert.assertTrue("smaller componentId should be less", k1.compareTo(k2) < 0);
    Assert.assertTrue("larger componentId should be greater", k2.compareTo(k1) > 0);
  }

  /**
   * collectionPosition has lowest precedence: same recordTs and componentId but
   * different collectionPosition must be ordered by collectionPosition.
   */
  @Test
  public void testVisibilityKeyCollectionPositionPrecedence() {
    var k1 = new VisibilityKey(50L, 3, 10L);
    var k2 = new VisibilityKey(50L, 3, 20L);
    Assert.assertTrue("smaller collectionPosition should be less", k1.compareTo(k2) < 0);
    Assert.assertTrue("larger collectionPosition should be greater", k2.compareTo(k1) > 0);
  }

  /**
   * Two keys with the same 3-tuple must compare as equal.
   */
  @Test
  public void testVisibilityKeyEqualComparison() {
    var k1 = new VisibilityKey(99L, 7, 42L);
    var k2 = new VisibilityKey(99L, 7, 42L);
    Assert.assertEquals(0, k1.compareTo(k2));
    Assert.assertEquals(k1, k2);
  }

  /**
   * VisibilityKey must work as a map key inside a TreeMap.  Keys for the same ts prefix
   * must be adjacent and ordered by (componentId, collectionPosition).
   */
  @Test
  public void testVisibilityKeyTreeMapOrdering() {
    var map = new TreeMap<VisibilityKey, String>();
    map.put(new VisibilityKey(10L, 1, 30L), "c");
    map.put(new VisibilityKey(10L, 1, 10L), "a");
    map.put(new VisibilityKey(10L, 1, 20L), "b");
    map.put(new VisibilityKey(10L, 2, 5L), "other");

    var keys = new ArrayList<>(map.keySet());
    Assert.assertEquals(new VisibilityKey(10L, 1, 10L), keys.get(0));
    Assert.assertEquals(new VisibilityKey(10L, 1, 20L), keys.get(1));
    Assert.assertEquals(new VisibilityKey(10L, 1, 30L), keys.get(2));
    Assert.assertEquals(new VisibilityKey(10L, 2, 5L), keys.get(3));
  }
}
