package com.jetbrains.youtrackdb.internal.core.storage.collection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ConcurrentSkipListMap;
import org.junit.Test;

public class SnapshotKeyTest {

  @Test
  public void testNaturalOrderingByComponentId() {
    var k1 = new SnapshotKey(1, 100L, 10L);
    var k2 = new SnapshotKey(2, 100L, 10L);

    assertThat(k1.compareTo(k2)).isNegative();
    assertThat(k2.compareTo(k1)).isPositive();
  }

  @Test
  public void testNaturalOrderingByCollectionPosition() {
    var k1 = new SnapshotKey(1, 100L, 10L);
    var k2 = new SnapshotKey(1, 200L, 10L);

    assertThat(k1.compareTo(k2)).isNegative();
    assertThat(k2.compareTo(k1)).isPositive();
  }

  @Test
  public void testNaturalOrderingByRecordVersion() {
    var k1 = new SnapshotKey(1, 100L, 10L);
    var k2 = new SnapshotKey(1, 100L, 20L);

    assertThat(k1.compareTo(k2)).isNegative();
    assertThat(k2.compareTo(k1)).isPositive();
  }

  @Test
  public void testEqualKeysCompareToZero() {
    var k1 = new SnapshotKey(1, 100L, 10L);
    var k2 = new SnapshotKey(1, 100L, 10L);

    assertThat(k1.compareTo(k2)).isZero();
    assertThat(k1).isEqualTo(k2);
    assertThat(k1.hashCode()).isEqualTo(k2.hashCode());
  }

  @Test
  public void testNotEqualKeys() {
    var k1 = new SnapshotKey(1, 100L, 10L);
    var k2 = new SnapshotKey(1, 100L, 20L);

    assertThat(k1).isNotEqualTo(k2);
    assertThat(k1.compareTo(k2)).isNotZero();
  }

  @Test
  public void testSubMapRangeScan() {
    var map = new ConcurrentSkipListMap<SnapshotKey, String>();

    // Two components, each with entries at different positions/versions
    map.put(new SnapshotKey(1, 50L, 1L), "c1-p50-v1");
    map.put(new SnapshotKey(1, 50L, 5L), "c1-p50-v5");
    map.put(new SnapshotKey(1, 50L, 10L), "c1-p50-v10");
    map.put(new SnapshotKey(1, 100L, 3L), "c1-p100-v3");
    map.put(new SnapshotKey(2, 50L, 1L), "c2-p50-v1");

    // Range scan for component 1, position 50, versions [MIN, 10)
    var from = new SnapshotKey(1, 50L, Long.MIN_VALUE);
    var to = new SnapshotKey(1, 50L, 10L);
    var subMap = map.subMap(from, true, to, false);

    assertThat(subMap).hasSize(2);
    assertThat(subMap.values()).containsExactly("c1-p50-v1", "c1-p50-v5");
  }

  @Test
  public void testSubMapForSinglePosition() {
    var map = new ConcurrentSkipListMap<SnapshotKey, String>();

    map.put(new SnapshotKey(1, 50L, 1L), "v1");
    map.put(new SnapshotKey(1, 50L, 5L), "v5");
    map.put(new SnapshotKey(1, 51L, 1L), "other-position");

    // Scan all versions for component 1, position 50
    var from = new SnapshotKey(1, 50L, Long.MIN_VALUE);
    var to = new SnapshotKey(1, 50L, Long.MAX_VALUE);
    var subMap = map.subMap(from, true, to, true);

    assertThat(subMap).hasSize(2);
    assertThat(subMap.values()).containsExactly("v1", "v5");
  }

  @Test
  public void testComponentIdIsolation() {
    var map = new ConcurrentSkipListMap<SnapshotKey, String>();

    map.put(new SnapshotKey(1, 50L, 1L), "c1");
    map.put(new SnapshotKey(2, 50L, 1L), "c2");

    // Scanning component 1 should not include component 2
    var from = new SnapshotKey(1, Long.MIN_VALUE, Long.MIN_VALUE);
    var to = new SnapshotKey(1, Long.MAX_VALUE, Long.MAX_VALUE);
    var subMap = map.subMap(from, true, to, true);

    assertThat(subMap).hasSize(1);
    assertThat(subMap.values()).containsExactly("c1");
  }
}
