package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import org.junit.Test;

/**
 * Unit tests for {@link RidSet}, focusing on the bitmap-level
 * {@link RidSet#intersect} method and basic add/contains/size behaviour.
 */
public class RidSetTest {

  // =========================================================================
  // intersect — null handling
  // =========================================================================

  @Test
  public void intersect_bothNull_returnsNull() {
    assertThat(RidSet.intersect(null, null)).isNull();
  }

  @Test
  public void intersect_firstNull_returnsSecond() {
    var b = new RidSet();
    b.add(new RecordId(10, 1));
    var result = RidSet.intersect(null, b);
    assertThat(result).isSameAs(b);
  }

  @Test
  public void intersect_secondNull_returnsFirst() {
    var a = new RidSet();
    a.add(new RecordId(10, 1));
    var result = RidSet.intersect(a, null);
    assertThat(result).isSameAs(a);
  }

  // =========================================================================
  // intersect — shared collection IDs
  // =========================================================================

  @Test
  public void intersect_sharedElements_returnsCommon() {
    var a = new RidSet();
    a.add(new RecordId(10, 1));
    a.add(new RecordId(10, 2));
    a.add(new RecordId(10, 3));

    var b = new RidSet();
    b.add(new RecordId(10, 2));
    b.add(new RecordId(10, 3));
    b.add(new RecordId(10, 4));

    var result = RidSet.intersect(a, b);
    assertThat(result).isNotNull();
    assertThat(result.size()).isEqualTo(2);
    assertThat(result.contains(new RecordId(10, 2))).isTrue();
    assertThat(result.contains(new RecordId(10, 3))).isTrue();
    assertThat(result.contains(new RecordId(10, 1))).isFalse();
    assertThat(result.contains(new RecordId(10, 4))).isFalse();
  }

  // =========================================================================
  // intersect — disjoint collection IDs
  // =========================================================================

  @Test
  public void intersect_disjointCollections_returnsEmpty() {
    var a = new RidSet();
    a.add(new RecordId(10, 1));
    a.add(new RecordId(10, 2));

    var b = new RidSet();
    b.add(new RecordId(20, 1));
    b.add(new RecordId(20, 2));

    var result = RidSet.intersect(a, b);
    assertThat(result).isNotNull();
    assertThat(result.isEmpty()).isTrue();
  }

  // =========================================================================
  // intersect — smaller/larger iteration
  // =========================================================================

  @Test
  public void intersect_iteratesOverSmallerSet() {
    var a = new RidSet();
    a.add(new RecordId(10, 1));

    var b = new RidSet();
    b.add(new RecordId(10, 1));
    b.add(new RecordId(20, 1));
    b.add(new RecordId(30, 1));

    var result = RidSet.intersect(a, b);
    assertThat(result).isNotNull();
    assertThat(result.size()).isEqualTo(1);
    assertThat(result.contains(new RecordId(10, 1))).isTrue();

    var resultReversed = RidSet.intersect(b, a);
    assertThat(resultReversed).isNotNull();
    assertThat(resultReversed.size()).isEqualTo(1);
    assertThat(resultReversed.contains(new RecordId(10, 1))).isTrue();
  }

  // =========================================================================
  // intersect — multiple collection IDs
  // =========================================================================

  @Test
  public void intersect_multipleCollections_intersectsPerCollection() {
    var a = new RidSet();
    a.add(new RecordId(10, 1));
    a.add(new RecordId(10, 2));
    a.add(new RecordId(20, 5));

    var b = new RidSet();
    b.add(new RecordId(10, 2));
    b.add(new RecordId(20, 5));
    b.add(new RecordId(20, 6));

    var result = RidSet.intersect(a, b);
    assertThat(result).isNotNull();
    assertThat(result.size()).isEqualTo(2);
    assertThat(result.contains(new RecordId(10, 2))).isTrue();
    assertThat(result.contains(new RecordId(20, 5))).isTrue();
  }

  // =========================================================================
  // intersect — negative RIDs
  // =========================================================================

  @Test
  public void intersect_negativeRids_intersectsViaHashSet() {
    var a = new RidSet();
    a.add(new RecordId(-1, 1));
    a.add(new RecordId(-1, 2));

    var b = new RidSet();
    b.add(new RecordId(-1, 2));
    b.add(new RecordId(-1, 3));

    var result = RidSet.intersect(a, b);
    assertThat(result).isNotNull();
    assertThat(result.size()).isEqualTo(1);
    assertThat(result.contains(new RecordId(-1, 2))).isTrue();
  }

  @Test
  public void intersect_negativeRids_noOverlap_returnsEmpty() {
    var a = new RidSet();
    a.add(new RecordId(-1, 1));

    var b = new RidSet();
    b.add(new RecordId(-1, 2));

    var result = RidSet.intersect(a, b);
    assertThat(result).isNotNull();
    assertThat(result.isEmpty()).isTrue();
  }

  // =========================================================================
  // intersect — mixed positive and negative
  // =========================================================================

  @Test
  public void intersect_mixedPositiveAndNegative_intersectsBoth() {
    var a = new RidSet();
    a.add(new RecordId(10, 1));
    a.add(new RecordId(-1, 5));

    var b = new RidSet();
    b.add(new RecordId(10, 1));
    b.add(new RecordId(-1, 5));
    b.add(new RecordId(10, 99));

    var result = RidSet.intersect(a, b);
    assertThat(result).isNotNull();
    assertThat(result.size()).isEqualTo(2);
    assertThat(result.contains(new RecordId(10, 1))).isTrue();
    assertThat(result.contains(new RecordId(-1, 5))).isTrue();
  }

  // =========================================================================
  // intersect — empty sets
  // =========================================================================

  @Test
  public void intersect_bothEmpty_returnsEmpty() {
    var result = RidSet.intersect(new RidSet(), new RidSet());
    assertThat(result).isNotNull();
    assertThat(result.isEmpty()).isTrue();
  }

  @Test
  public void intersect_oneEmpty_returnsEmpty() {
    var a = new RidSet();
    a.add(new RecordId(10, 1));

    var result = RidSet.intersect(a, new RidSet());
    assertThat(result).isNotNull();
    assertThat(result.isEmpty()).isTrue();
  }

  // =========================================================================
  // intersect — bitmap-level (non-overlapping positions in same collection)
  // =========================================================================

  @Test
  public void intersect_sameCollectionNoOverlappingPositions_returnsEmpty() {
    var a = new RidSet();
    a.add(new RecordId(10, 1));
    a.add(new RecordId(10, 3));

    var b = new RidSet();
    b.add(new RecordId(10, 2));
    b.add(new RecordId(10, 4));

    var result = RidSet.intersect(a, b);
    assertThat(result).isNotNull();
    assertThat(result.isEmpty()).isTrue();
  }
}
