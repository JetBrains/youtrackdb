package com.jetbrains.youtrackdb.internal.core.index.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for {@link HistogramDeltaHolder} — per-transaction container for
 * histogram deltas, stored directly on the AtomicOperation.
 */
public class HistogramDeltaHolderTest {

  @Test
  public void getOrCreateEngineReturnsNewDeltaForNewEngine() {
    var holder = new HistogramDeltaHolder();
    var delta = holder.getOrCreate(42);

    assertNotNull(delta);
    assertEquals(0, delta.totalCountDelta);
  }

  @Test
  public void getOrCreateEngineReturnsSameDeltaForSameEngine() {
    var holder = new HistogramDeltaHolder();
    var delta1 = holder.getOrCreate(42);
    var delta2 = holder.getOrCreate(42);

    assertSame(delta1, delta2);
  }

  @Test
  public void getOrCreateEngineSeparatesDeltasByEngineId() {
    var holder = new HistogramDeltaHolder();
    var delta1 = holder.getOrCreate(1);
    var delta2 = holder.getOrCreate(2);

    delta1.totalCountDelta = 10;
    delta2.totalCountDelta = 20;

    assertEquals(10, holder.getOrCreate(1).totalCountDelta);
    assertEquals(20, holder.getOrCreate(2).totalCountDelta);
  }

  @Test
  public void getDeltasReturnsDeltaMap() {
    var holder = new HistogramDeltaHolder();
    holder.getOrCreate(1);
    holder.getOrCreate(2);

    var map = holder.getDeltas();
    assertEquals(2, map.size());
    assertNotNull(map.get(1));
    assertNotNull(map.get(2));
    assertNull(map.get(3));
  }

  @Test
  public void getDeltasOnEmptyHolderReturnsEmptyMap() {
    var holder = new HistogramDeltaHolder();
    var map = holder.getDeltas();
    assertNotNull(map);
    assertEquals(0, map.size());
  }

  @Test
  public void negativeEngineIdWorksCorrectly() {
    var holder = new HistogramDeltaHolder();
    var delta = holder.getOrCreate(-1);
    assertNotNull(delta);

    delta.totalCountDelta = 42;
    assertEquals(42, holder.getOrCreate(-1).totalCountDelta);
  }

  /**
   * Latch contract at the source: {@link HistogramDeltaHolder} starts in
   * the non-applied state, and {@link HistogramDeltaHolder#setApplied()}
   * is idempotent. The lifecycle apply hook inside
   * {@code AtomicOperationsManager.endAtomicOperation} reads the latch in
   * the gate, and {@code AbstractStorage.applyHistogramDeltas} sets it at
   * the top of the method (before the per-engine loop) so a partial-loop
   * throw still latches the holder. Mirrors the
   * {@code IndexCountDeltaHolder.appliedLatchIsIdempotent} pattern on the
   * sibling holder.
   */
  @Test
  public void appliedLatchIsIdempotent() {
    var holder = new HistogramDeltaHolder();
    assertFalse("Holder starts in the non-applied state", holder.isApplied());
    holder.setApplied();
    assertTrue("Latch flips after setApplied()", holder.isApplied());
    holder.setApplied();
    assertTrue("setApplied() must be idempotent", holder.isApplied());
  }
}
