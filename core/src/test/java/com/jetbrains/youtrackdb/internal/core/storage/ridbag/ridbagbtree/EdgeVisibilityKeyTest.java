package com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ConcurrentSkipListMap;
import org.junit.Test;

public class EdgeVisibilityKeyTest {

  @Test
  public void testNaturalOrderingByRecordTs() {
    var k1 = new EdgeVisibilityKey(100L, 1, 50L, 10, 200L);
    var k2 = new EdgeVisibilityKey(200L, 1, 50L, 10, 200L);

    assertThat(k1.compareTo(k2)).isNegative();
    assertThat(k2.compareTo(k1)).isPositive();
  }

  @Test
  public void testNaturalOrderingByComponentId() {
    var k1 = new EdgeVisibilityKey(100L, 1, 50L, 10, 200L);
    var k2 = new EdgeVisibilityKey(100L, 2, 50L, 10, 200L);

    assertThat(k1.compareTo(k2)).isNegative();
    assertThat(k2.compareTo(k1)).isPositive();
  }

  @Test
  public void testNaturalOrderingByRidBagId() {
    var k1 = new EdgeVisibilityKey(100L, 1, 50L, 10, 200L);
    var k2 = new EdgeVisibilityKey(100L, 1, 60L, 10, 200L);

    assertThat(k1.compareTo(k2)).isNegative();
    assertThat(k2.compareTo(k1)).isPositive();
  }

  @Test
  public void testNaturalOrderingByTargetCollection() {
    var k1 = new EdgeVisibilityKey(100L, 1, 50L, 10, 200L);
    var k2 = new EdgeVisibilityKey(100L, 1, 50L, 20, 200L);

    assertThat(k1.compareTo(k2)).isNegative();
    assertThat(k2.compareTo(k1)).isPositive();
  }

  @Test
  public void testNaturalOrderingByTargetPosition() {
    var k1 = new EdgeVisibilityKey(100L, 1, 50L, 10, 200L);
    var k2 = new EdgeVisibilityKey(100L, 1, 50L, 10, 300L);

    assertThat(k1.compareTo(k2)).isNegative();
    assertThat(k2.compareTo(k1)).isPositive();
  }

  @Test
  public void testEqualKeysCompareToZero() {
    var k1 = new EdgeVisibilityKey(100L, 1, 50L, 10, 200L);
    var k2 = new EdgeVisibilityKey(100L, 1, 50L, 10, 200L);

    assertThat(k1.compareTo(k2)).isZero();
    assertThat(k1).isEqualTo(k2);
    assertThat(k1.hashCode()).isEqualTo(k2.hashCode());
  }

  @Test
  public void testNotEqualKeys() {
    var k1 = new EdgeVisibilityKey(100L, 1, 50L, 10, 200L);
    var k2 = new EdgeVisibilityKey(100L, 1, 50L, 10, 300L);

    assertThat(k1).isNotEqualTo(k2);
    assertThat(k1.compareTo(k2)).isNotZero();
  }

  @Test
  public void testHeadMapRangeScanByTimestamp() {
    // headMap with exclusive bound should include only entries below the timestamp
    var map = new ConcurrentSkipListMap<EdgeVisibilityKey, String>();

    map.put(new EdgeVisibilityKey(10L, 1, 50L, 10, 200L), "ts10");
    map.put(new EdgeVisibilityKey(20L, 1, 50L, 10, 200L), "ts20");
    map.put(new EdgeVisibilityKey(30L, 1, 50L, 10, 200L), "ts30");
    map.put(new EdgeVisibilityKey(40L, 2, 50L, 10, 200L), "ts40");
    map.put(new EdgeVisibilityKey(50L, 1, 50L, 10, 200L), "ts50");

    // headMap at ts=30 — should get ts10, ts20
    var bound = new EdgeVisibilityKey(30L, Integer.MIN_VALUE,
        Long.MIN_VALUE, Integer.MIN_VALUE, Long.MIN_VALUE);
    var head = map.headMap(bound, false);

    assertThat(head).hasSize(2);
    assertThat(head.values()).containsExactly("ts10", "ts20");
  }

  @Test
  public void testHeadMapIncludesAllComponentsBelow() {
    // All entries at a given timestamp (across different components) should be included
    var map = new ConcurrentSkipListMap<EdgeVisibilityKey, String>();

    map.put(new EdgeVisibilityKey(10L, 1, 50L, 10, 200L), "c1-ts10");
    map.put(new EdgeVisibilityKey(10L, 2, 50L, 10, 200L), "c2-ts10");
    map.put(new EdgeVisibilityKey(10L, 3, 50L, 10, 200L), "c3-ts10");
    map.put(new EdgeVisibilityKey(20L, 1, 50L, 10, 200L), "c1-ts20");

    var bound = new EdgeVisibilityKey(20L, Integer.MIN_VALUE,
        Long.MIN_VALUE, Integer.MIN_VALUE, Long.MIN_VALUE);
    var head = map.headMap(bound, false);

    assertThat(head).hasSize(3);
    assertThat(head.values())
        .containsExactly("c1-ts10", "c2-ts10", "c3-ts10");
  }

  @Test
  public void testEmptyHeadMapWhenAllAboveBound() {
    var map = new ConcurrentSkipListMap<EdgeVisibilityKey, String>();

    map.put(new EdgeVisibilityKey(100L, 1, 50L, 10, 200L), "high");
    map.put(new EdgeVisibilityKey(200L, 1, 50L, 10, 200L), "higher");

    var bound = new EdgeVisibilityKey(50L, Integer.MIN_VALUE,
        Long.MIN_VALUE, Integer.MIN_VALUE, Long.MIN_VALUE);
    var head = map.headMap(bound, false);

    assertThat(head).isEmpty();
  }

  @Test
  public void testHeadMapIncludesMultipleRidBagsAtSameTimestamp() {
    // Entries for different ridBagIds at the same timestamp should all be included
    var map = new ConcurrentSkipListMap<EdgeVisibilityKey, String>();

    map.put(new EdgeVisibilityKey(10L, 1, 50L, 10, 200L), "bag50");
    map.put(new EdgeVisibilityKey(10L, 1, 60L, 10, 200L), "bag60");
    map.put(new EdgeVisibilityKey(10L, 1, 70L, 10, 200L), "bag70");
    map.put(new EdgeVisibilityKey(20L, 1, 50L, 10, 200L), "ts20");

    var bound = new EdgeVisibilityKey(20L, Integer.MIN_VALUE,
        Long.MIN_VALUE, Integer.MIN_VALUE, Long.MIN_VALUE);
    var head = map.headMap(bound, false);

    assertThat(head).hasSize(3);
    assertThat(head.values()).containsExactly("bag50", "bag60", "bag70");
  }
}
