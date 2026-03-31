package com.jetbrains.youtrackdb.internal.core.index.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

/**
 * Tests for {@link IndexCountDeltaHolder} — per-transaction container for
 * index entry count deltas, stored directly on the AtomicOperation.
 *
 * <p>Verifies: lazy creation of per-engine deltas, identity stability on
 * repeated access, engine isolation, unmodifiable view semantics, and
 * rollback-safety (discarding the holder discards all accumulated deltas).
 */
public class IndexCountDeltaHolderTest {

  /**
   * getOrCreate for a new engine ID returns a fresh delta with zero fields.
   */
  @Test
  public void getOrCreateReturnsNewDeltaForNewEngine() {
    var holder = new IndexCountDeltaHolder();
    var delta = holder.getOrCreate(42);

    assertNotNull(delta);
    assertEquals(0, delta.totalDelta);
    assertEquals(0, delta.nullDelta);
  }

  /**
   * Repeated getOrCreate with the same engine ID returns the same instance
   * (identity, not just equality), so mutations accumulate correctly.
   */
  @Test
  public void getOrCreateReturnsSameDeltaForSameEngine() {
    var holder = new IndexCountDeltaHolder();
    var delta1 = holder.getOrCreate(42);
    var delta2 = holder.getOrCreate(42);

    assertSame(delta1, delta2);
  }

  /**
   * Deltas for different engine IDs are independent — mutating one does not
   * affect the other.
   */
  @Test
  public void getOrCreateSeparatesDeltasByEngineId() {
    var holder = new IndexCountDeltaHolder();
    var delta1 = holder.getOrCreate(1);
    var delta2 = holder.getOrCreate(2);

    delta1.totalDelta = 10;
    delta1.nullDelta = 3;
    delta2.totalDelta = 20;
    delta2.nullDelta = 7;

    assertEquals(10, holder.getOrCreate(1).totalDelta);
    assertEquals(3, holder.getOrCreate(1).nullDelta);
    assertEquals(20, holder.getOrCreate(2).totalDelta);
    assertEquals(7, holder.getOrCreate(2).nullDelta);
  }

  /**
   * getDeltas returns a map containing the same delta instances that were
   * returned by getOrCreate — mutations made through getOrCreate are visible
   * during commit-time iteration over the map.
   */
  @Test
  public void getDeltasContainsSameInstancesAsGetOrCreate() {
    var holder = new IndexCountDeltaHolder();
    var delta1 = holder.getOrCreate(1);
    var delta2 = holder.getOrCreate(2);
    delta1.totalDelta = 11;
    delta2.nullDelta = 7;

    var map = holder.getDeltas();
    assertEquals(2, map.size());
    assertSame(delta1, map.get(1));
    assertSame(delta2, map.get(2));
    assertEquals(11, map.get(1).totalDelta);
    assertEquals(7, map.get(2).nullDelta);
    assertNull(map.get(3));
  }

  /**
   * getDeltas on a holder that was never accessed returns an empty map.
   */
  @Test
  public void getDeltasOnEmptyHolderReturnsEmptyMap() {
    var holder = new IndexCountDeltaHolder();
    var map = holder.getDeltas();
    assertNotNull(map);
    assertEquals(0, map.size());
  }

  /**
   * getDeltas returns an unmodifiable view — callers cannot corrupt the
   * holder's internal state by modifying the returned map.
   */
  @Test(expected = UnsupportedOperationException.class)
  public void getDeltasReturnsUnmodifiableMap() {
    var holder = new IndexCountDeltaHolder();
    holder.getOrCreate(1);
    holder.getDeltas().put(99, new IndexCountDelta());
  }

  /**
   * The holder is fully self-contained: accumulated deltas are exposed only
   * via getDeltas() and never pushed anywhere automatically. On rollback
   * the AtomicOperation (and its holder) is discarded — no external counter
   * is ever mutated. This test verifies the containment property.
   */
  @Test
  public void holderIsSelfContainedDeltasOnlyExposedViaGetDeltas() {
    var holder = new IndexCountDeltaHolder();
    var delta = holder.getOrCreate(7);
    delta.totalDelta = 5;
    delta.nullDelta = 2;

    // The holder retains the values in isolation, accessible only via
    // the map view — the design contract is that nothing is applied
    // until AbstractStorage.applyIndexCountDeltas() on commit.
    var map = holder.getDeltas();
    assertEquals(1, map.size());
    assertSame(delta, map.get(7));
    assertEquals(5, map.get(7).totalDelta);
    assertEquals(2, map.get(7).nullDelta);
  }

  /**
   * Engine ID 0 is a plausible default/uninitialized value; it must still
   * produce a stable, independent bucket.
   */
  @Test
  public void getOrCreateWithEngineIdZero() {
    var holder = new IndexCountDeltaHolder();
    var delta = holder.getOrCreate(0);

    assertNotNull(delta);
    assertEquals(0, delta.totalDelta);
    assertEquals(0, delta.nullDelta);
    assertSame(delta, holder.getOrCreate(0));
  }

  /**
   * Negative engine IDs are not assigned by DiskStorage but the API accepts
   * any int — verify the boundary works (mirrors HistogramDeltaHolderTest).
   */
  @Test
  public void negativeEngineIdWorksCorrectly() {
    var holder = new IndexCountDeltaHolder();
    var delta = holder.getOrCreate(-1);

    assertNotNull(delta);
    delta.totalDelta = 42;
    assertEquals(42, holder.getOrCreate(-1).totalDelta);
  }

  /**
   * Accumulating both positive and negative deltas for the same engine
   * produces the correct net value.
   */
  @Test
  public void netDeltaAccumulatesCorrectly() {
    var holder = new IndexCountDeltaHolder();
    var delta = holder.getOrCreate(1);

    // Simulate: 3 inserts, 1 remove, 1 null insert, 1 null remove
    delta.totalDelta += 3;
    delta.totalDelta -= 1;
    delta.nullDelta += 1;
    delta.nullDelta -= 1;

    assertEquals(2, delta.totalDelta);
    assertEquals(0, delta.nullDelta);
  }
}
