package com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ConcurrentSkipListMap;
import org.junit.Test;

public class EdgeSnapshotKeyTest {

  @Test
  public void testNaturalOrderingByComponentId() {
    var k1 = new EdgeSnapshotKey(1, 100L, 10, 200L, 5L);
    var k2 = new EdgeSnapshotKey(2, 100L, 10, 200L, 5L);

    assertThat(k1.compareTo(k2)).isNegative();
    assertThat(k2.compareTo(k1)).isPositive();
  }

  @Test
  public void testNaturalOrderingByRidBagId() {
    var k1 = new EdgeSnapshotKey(1, 100L, 10, 200L, 5L);
    var k2 = new EdgeSnapshotKey(1, 200L, 10, 200L, 5L);

    assertThat(k1.compareTo(k2)).isNegative();
    assertThat(k2.compareTo(k1)).isPositive();
  }

  @Test
  public void testNaturalOrderingByTargetCollection() {
    var k1 = new EdgeSnapshotKey(1, 100L, 10, 200L, 5L);
    var k2 = new EdgeSnapshotKey(1, 100L, 20, 200L, 5L);

    assertThat(k1.compareTo(k2)).isNegative();
    assertThat(k2.compareTo(k1)).isPositive();
  }

  @Test
  public void testNaturalOrderingByTargetPosition() {
    var k1 = new EdgeSnapshotKey(1, 100L, 10, 200L, 5L);
    var k2 = new EdgeSnapshotKey(1, 100L, 10, 300L, 5L);

    assertThat(k1.compareTo(k2)).isNegative();
    assertThat(k2.compareTo(k1)).isPositive();
  }

  @Test
  public void testNaturalOrderingByVersion() {
    var k1 = new EdgeSnapshotKey(1, 100L, 10, 200L, 5L);
    var k2 = new EdgeSnapshotKey(1, 100L, 10, 200L, 10L);

    assertThat(k1.compareTo(k2)).isNegative();
    assertThat(k2.compareTo(k1)).isPositive();
  }

  @Test
  public void testEqualKeysCompareToZero() {
    var k1 = new EdgeSnapshotKey(1, 100L, 10, 200L, 5L);
    var k2 = new EdgeSnapshotKey(1, 100L, 10, 200L, 5L);

    assertThat(k1.compareTo(k2)).isZero();
    assertThat(k1).isEqualTo(k2);
    assertThat(k1.hashCode()).isEqualTo(k2.hashCode());
  }

  @Test
  public void testNotEqualKeys() {
    var k1 = new EdgeSnapshotKey(1, 100L, 10, 200L, 5L);
    var k2 = new EdgeSnapshotKey(1, 100L, 10, 200L, 10L);

    assertThat(k1).isNotEqualTo(k2);
    assertThat(k1.compareTo(k2)).isNotZero();
  }

  @Test
  public void testSubMapVersionRangeScan() {
    // Verify that subMap correctly isolates versions for a single logical edge
    var map = new ConcurrentSkipListMap<EdgeSnapshotKey, String>();

    map.put(new EdgeSnapshotKey(1, 50L, 10, 200L, 1L), "v1");
    map.put(new EdgeSnapshotKey(1, 50L, 10, 200L, 5L), "v5");
    map.put(new EdgeSnapshotKey(1, 50L, 10, 200L, 10L), "v10");
    map.put(new EdgeSnapshotKey(1, 50L, 10, 300L, 3L), "other-position");

    // Range scan for versions [MIN, 10) of edge (1, 50, 10, 200)
    var from = new EdgeSnapshotKey(1, 50L, 10, 200L, Long.MIN_VALUE);
    var to = new EdgeSnapshotKey(1, 50L, 10, 200L, 10L);
    var subMap = map.subMap(from, true, to, false);

    assertThat(subMap).hasSize(2);
    assertThat(subMap.values()).containsExactly("v1", "v5");
  }

  @Test
  public void testSubMapAllVersionsForSingleEdge() {
    // Verify that scanning all versions for a logical edge does not cross position boundaries
    var map = new ConcurrentSkipListMap<EdgeSnapshotKey, String>();

    map.put(new EdgeSnapshotKey(1, 50L, 10, 200L, 1L), "v1");
    map.put(new EdgeSnapshotKey(1, 50L, 10, 200L, 5L), "v5");
    map.put(new EdgeSnapshotKey(1, 50L, 10, 201L, 1L), "other-position");

    var from = new EdgeSnapshotKey(1, 50L, 10, 200L, Long.MIN_VALUE);
    var to = new EdgeSnapshotKey(1, 50L, 10, 200L, Long.MAX_VALUE);
    var subMap = map.subMap(from, true, to, true);

    assertThat(subMap).hasSize(2);
    assertThat(subMap.values()).containsExactly("v1", "v5");
  }

  @Test
  public void testComponentIdIsolation() {
    // Verify that scanning within one component does not include entries from another
    var map = new ConcurrentSkipListMap<EdgeSnapshotKey, String>();

    map.put(new EdgeSnapshotKey(1, 50L, 10, 200L, 1L), "c1");
    map.put(new EdgeSnapshotKey(2, 50L, 10, 200L, 1L), "c2");

    var from = new EdgeSnapshotKey(1, Long.MIN_VALUE, Integer.MIN_VALUE,
        Long.MIN_VALUE, Long.MIN_VALUE);
    var to = new EdgeSnapshotKey(1, Long.MAX_VALUE, Integer.MAX_VALUE,
        Long.MAX_VALUE, Long.MAX_VALUE);
    var subMap = map.subMap(from, true, to, true);

    assertThat(subMap).hasSize(1);
    assertThat(subMap.values()).containsExactly("c1");
  }

  @Test
  public void testRidBagIdIsolation() {
    // Verify that entries with different ridBagIds are not mixed in subMap scans
    var map = new ConcurrentSkipListMap<EdgeSnapshotKey, String>();

    map.put(new EdgeSnapshotKey(1, 50L, 10, 200L, 1L), "bag50");
    map.put(new EdgeSnapshotKey(1, 60L, 10, 200L, 1L), "bag60");

    var from = new EdgeSnapshotKey(1, 50L, Integer.MIN_VALUE,
        Long.MIN_VALUE, Long.MIN_VALUE);
    var to = new EdgeSnapshotKey(1, 50L, Integer.MAX_VALUE,
        Long.MAX_VALUE, Long.MAX_VALUE);
    var subMap = map.subMap(from, true, to, true);

    assertThat(subMap).hasSize(1);
    assertThat(subMap.values()).containsExactly("bag50");
  }

  @Test
  public void testDescendingSubMapForNewestVersionLookup() {
    // Verify descending iteration gives newest version first — used by visibility lookups
    var map = new ConcurrentSkipListMap<EdgeSnapshotKey, String>();

    map.put(new EdgeSnapshotKey(1, 50L, 10, 200L, 1L), "v1");
    map.put(new EdgeSnapshotKey(1, 50L, 10, 200L, 5L), "v5");
    map.put(new EdgeSnapshotKey(1, 50L, 10, 200L, 10L), "v10");

    var from = new EdgeSnapshotKey(1, 50L, 10, 200L, Long.MIN_VALUE);
    var to = new EdgeSnapshotKey(1, 50L, 10, 200L, Long.MAX_VALUE);
    var descending = map.subMap(from, true, to, true).descendingMap();

    assertThat(descending.values()).containsExactly("v10", "v5", "v1");
  }
}
