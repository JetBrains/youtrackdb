package com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.TreeMap;
import org.junit.Test;

/**
 * Tests EdgeKey comparison semantics, equality, and hashCode behavior with the `ts` field.
 * Verifies that the 4-component ordering (ridBagId → targetCollection → targetPosition → ts)
 * is correct for B-tree key ordering and snapshot isolation prefix lookups.
 */
public class EdgeKeyTest {

  // --- compareTo tests ---

  /**
   * Two keys with the same 3-tuple but different ts should be ordered by ts last.
   */
  @Test
  public void testCompareToOrdersByTsLast() {
    var key1 = new EdgeKey(10, 20, 30, 100L);
    var key2 = new EdgeKey(10, 20, 30, 200L);

    assertTrue("key with smaller ts should be less", key1.compareTo(key2) < 0);
    assertTrue("key with larger ts should be greater", key2.compareTo(key1) > 0);
  }

  /**
   * Two keys that are identical in all 4 fields should compare as equal.
   */
  @Test
  public void testCompareToEqualKeys() {
    var key1 = new EdgeKey(10, 20, 30, 100L);
    var key2 = new EdgeKey(10, 20, 30, 100L);

    assertEquals(0, key1.compareTo(key2));
  }

  /**
   * ridBagId has highest precedence — different ridBagId should determine order regardless
   * of other fields.
   */
  @Test
  public void testCompareToRidBagIdPrecedence() {
    var key1 = new EdgeKey(1, 999, Long.MAX_VALUE, Long.MAX_VALUE);
    var key2 = new EdgeKey(2, 0, 0, 0L);

    assertTrue("ridBagId should take precedence", key1.compareTo(key2) < 0);
  }

  /**
   * targetCollection has second precedence — same ridBagId but different targetCollection.
   */
  @Test
  public void testCompareToTargetCollectionPrecedence() {
    var key1 = new EdgeKey(10, 5, Long.MAX_VALUE, Long.MAX_VALUE);
    var key2 = new EdgeKey(10, 6, 0, 0L);

    assertTrue("targetCollection should take precedence over targetPosition and ts",
        key1.compareTo(key2) < 0);
  }

  /**
   * targetPosition has third precedence — same ridBagId and targetCollection.
   */
  @Test
  public void testCompareToTargetPositionPrecedence() {
    var key1 = new EdgeKey(10, 20, 100, Long.MAX_VALUE);
    var key2 = new EdgeKey(10, 20, 101, 0L);

    assertTrue("targetPosition should take precedence over ts",
        key1.compareTo(key2) < 0);
  }

  /**
   * Verifies that keys with negative ridBagId values (used by the link bag system)
   * compare correctly.
   */
  @Test
  public void testCompareToWithNegativeRidBagId() {
    var key1 = new EdgeKey(-100, 20, 30, 50L);
    var key2 = new EdgeKey(-50, 20, 30, 50L);

    assertTrue("more negative ridBagId should be less", key1.compareTo(key2) < 0);
  }

  /**
   * Verifies that ts=Long.MIN_VALUE and ts=Long.MAX_VALUE form proper bounds for
   * prefix range scans on a logical edge (ridBagId, targetCollection, targetPosition).
   */
  @Test
  public void testTsBoundaryValuesForPrefixScan() {
    var lower = new EdgeKey(10, 20, 30, Long.MIN_VALUE);
    var middle = new EdgeKey(10, 20, 30, 42L);
    var upper = new EdgeKey(10, 20, 30, Long.MAX_VALUE);

    assertTrue("MIN_VALUE ts should be less than any other ts", lower.compareTo(middle) < 0);
    assertTrue("MAX_VALUE ts should be greater than any other ts", upper.compareTo(middle) > 0);
    assertTrue("MIN_VALUE ts should be less than MAX_VALUE ts", lower.compareTo(upper) < 0);
  }

  // --- equals and hashCode tests ---

  /**
   * Two keys with the same 3-tuple but different ts must NOT be equal.
   */
  @Test
  public void testEqualsDistinguishesByTs() {
    var key1 = new EdgeKey(10, 20, 30, 100L);
    var key2 = new EdgeKey(10, 20, 30, 200L);

    assertNotEquals("Keys with different ts should not be equal", key1, key2);
  }

  /**
   * Two keys with all 4 fields equal must be equal.
   */
  @Test
  public void testEqualsWithIdenticalKeys() {
    var key1 = new EdgeKey(10, 20, 30, 100L);
    var key2 = new EdgeKey(10, 20, 30, 100L);

    assertEquals(key1, key2);
  }

  /**
   * hashCode must differ for keys with different ts (not guaranteed by contract but strongly
   * expected for quality hash distribution).
   */
  @Test
  public void testHashCodeDiffersForDifferentTs() {
    var key1 = new EdgeKey(10, 20, 30, 100L);
    var key2 = new EdgeKey(10, 20, 30, 200L);

    // While hash collisions are technically possible, for these specific inputs with a
    // well-implemented hash function, they should differ.
    assertNotEquals("hashCode should differ for keys with different ts",
        key1.hashCode(), key2.hashCode());
  }

  /**
   * hashCode must be the same for equal keys.
   */
  @Test
  public void testHashCodeSameForEqualKeys() {
    var key1 = new EdgeKey(10, 20, 30, 100L);
    var key2 = new EdgeKey(10, 20, 30, 100L);

    assertEquals(key1.hashCode(), key2.hashCode());
  }

  // --- Natural ordering integration tests ---

  /**
   * TreeMap should sort EdgeKeys in the correct 4-component order, which is the same
   * ordering used by the B-tree. Keys for the same logical edge (same 3-tuple) should
   * be adjacent and ordered by ts.
   */
  @Test
  public void testTreeMapOrderingMatchesBTreeOrder() {
    var map = new TreeMap<EdgeKey, String>();

    // Same logical edge with different timestamps
    map.put(new EdgeKey(10, 20, 30, 300L), "v3");
    map.put(new EdgeKey(10, 20, 30, 100L), "v1");
    map.put(new EdgeKey(10, 20, 30, 200L), "v2");

    // Different logical edge
    map.put(new EdgeKey(10, 20, 31, 50L), "other");

    var keys = new ArrayList<>(map.keySet());

    // First 3 entries should be the same logical edge, ordered by ts ascending
    assertEquals(100L, keys.get(0).ts);
    assertEquals(200L, keys.get(1).ts);
    assertEquals(300L, keys.get(2).ts);

    // The different logical edge (targetPosition=31) should come after targetPosition=30
    assertEquals(31, keys.get(3).targetPosition);
  }

  /**
   * Collections.sort should produce the same order as natural ordering (Comparable).
   */
  @Test
  public void testCollectionsSortProducesCorrectOrder() {
    var keys = new ArrayList<EdgeKey>();

    keys.add(new EdgeKey(10, 20, 30, 200L));
    keys.add(new EdgeKey(5, 20, 30, 100L));
    keys.add(new EdgeKey(10, 20, 30, 100L));
    keys.add(new EdgeKey(10, 10, 30, 100L));

    Collections.sort(keys);

    // Expected order: ridBagId=5 first, then ridBagId=10 with targetCollection=10,
    // then ridBagId=10/tc=20/ts=100, then ridBagId=10/tc=20/ts=200
    assertEquals(5, keys.get(0).ridBagId);
    assertEquals(10, keys.get(1).targetCollection);
    assertEquals(100L, keys.get(2).ts);
    assertEquals(200L, keys.get(3).ts);
  }

  // --- toString test ---

  /**
   * toString should include the ts field for debugging.
   */
  @Test
  public void testToStringIncludesTs() {
    var key = new EdgeKey(10, 20, 30, 42L);
    var str = key.toString();

    assertTrue("toString should contain ts", str.contains("ts=42"));
    assertTrue("toString should contain ridBagId", str.contains("ridBagId=10"));
  }
}
