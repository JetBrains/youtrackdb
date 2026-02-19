package com.jetbrains.youtrackdb.internal.core.storage.collection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ConcurrentSkipListMap;
import org.junit.Test;

public class VisibilityKeyTest {

  @Test
  public void testNaturalOrderingByRecordTs() {
    var k1 = new VisibilityKey(100L, 1, 50L);
    var k2 = new VisibilityKey(200L, 1, 50L);

    assertThat(k1.compareTo(k2)).isNegative();
    assertThat(k2.compareTo(k1)).isPositive();
  }

  @Test
  public void testNaturalOrderingByComponentId() {
    var k1 = new VisibilityKey(100L, 1, 50L);
    var k2 = new VisibilityKey(100L, 2, 50L);

    assertThat(k1.compareTo(k2)).isNegative();
    assertThat(k2.compareTo(k1)).isPositive();
  }

  @Test
  public void testNaturalOrderingByCollectionPosition() {
    var k1 = new VisibilityKey(100L, 1, 50L);
    var k2 = new VisibilityKey(100L, 1, 100L);

    assertThat(k1.compareTo(k2)).isNegative();
    assertThat(k2.compareTo(k1)).isPositive();
  }

  @Test
  public void testEqualKeysCompareToZero() {
    var k1 = new VisibilityKey(100L, 1, 50L);
    var k2 = new VisibilityKey(100L, 1, 50L);

    assertThat(k1.compareTo(k2)).isZero();
    assertThat(k1).isEqualTo(k2);
    assertThat(k1.hashCode()).isEqualTo(k2.hashCode());
  }

  @Test
  public void testNotEqualKeys() {
    var k1 = new VisibilityKey(100L, 1, 50L);
    var k2 = new VisibilityKey(100L, 1, 51L);

    assertThat(k1).isNotEqualTo(k2);
    assertThat(k1.compareTo(k2)).isNotZero();
  }

  @Test
  public void testHeadMapRangeScanByTimestamp() {
    var map = new ConcurrentSkipListMap<VisibilityKey, String>();

    map.put(new VisibilityKey(10L, 1, 50L), "ts10");
    map.put(new VisibilityKey(20L, 1, 50L), "ts20");
    map.put(new VisibilityKey(30L, 1, 50L), "ts30");
    map.put(new VisibilityKey(40L, 2, 50L), "ts40");
    map.put(new VisibilityKey(50L, 1, 50L), "ts50");

    // headMap with exclusive upper bound at ts=30 — should get ts10, ts20
    var bound = new VisibilityKey(30L, Integer.MIN_VALUE, Long.MIN_VALUE);
    var head = map.headMap(bound, false);

    assertThat(head).hasSize(2);
    assertThat(head.values()).containsExactly("ts10", "ts20");
  }

  @Test
  public void testHeadMapIncludesAllComponentsBelow() {
    var map = new ConcurrentSkipListMap<VisibilityKey, String>();

    map.put(new VisibilityKey(10L, 1, 50L), "c1-ts10");
    map.put(new VisibilityKey(10L, 2, 50L), "c2-ts10");
    map.put(new VisibilityKey(10L, 3, 50L), "c3-ts10");
    map.put(new VisibilityKey(20L, 1, 50L), "c1-ts20");

    // All entries at ts=10 (all components) should be below ts=20
    var bound = new VisibilityKey(20L, Integer.MIN_VALUE, Long.MIN_VALUE);
    var head = map.headMap(bound, false);

    assertThat(head).hasSize(3);
    assertThat(head.values())
        .containsExactly("c1-ts10", "c2-ts10", "c3-ts10");
  }

  @Test
  public void testEmptyHeadMapWhenAllAboveBound() {
    var map = new ConcurrentSkipListMap<VisibilityKey, String>();

    map.put(new VisibilityKey(100L, 1, 50L), "high");
    map.put(new VisibilityKey(200L, 1, 50L), "higher");

    var bound = new VisibilityKey(50L, Integer.MIN_VALUE, Long.MIN_VALUE);
    var head = map.headMap(bound, false);

    assertThat(head).isEmpty();
  }
}
