package com.jetbrains.youtrackdb.internal.core.index.engine;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * Tests for {@link HistogramDelta} — transaction-local accumulator for
 * histogram changes.
 */
public class HistogramDeltaTest {

  @Test
  public void newDeltaHasZeroCounters() {
    var delta = new HistogramDelta();

    assertEquals(0, delta.totalCountDelta);
    assertEquals(0, delta.nullCountDelta);
    assertNull(delta.frequencyDeltas);
    assertEquals(0, delta.snapshotVersion);
    assertEquals(0, delta.mutationCount);
    assertNull(delta.hllSketch);
  }

  @Test
  public void initFrequencyDeltasAllocatesArrayOnce() {
    var delta = new HistogramDelta();
    delta.initFrequencyDeltas(4, 7);

    assertNotNull(delta.frequencyDeltas);
    assertEquals(4, delta.frequencyDeltas.length);
    assertEquals(7, delta.snapshotVersion);

    // Second call with different arguments is a no-op
    delta.initFrequencyDeltas(10, 99);
    assertEquals(4, delta.frequencyDeltas.length);
    assertEquals(7, delta.snapshotVersion);
  }

  @Test
  public void frequencyDeltasAccumulate() {
    var delta = new HistogramDelta();
    delta.initFrequencyDeltas(3, 1);

    delta.frequencyDeltas[0] += 5;
    delta.frequencyDeltas[1] -= 2;
    delta.frequencyDeltas[2] += 10;

    assertEquals(5, delta.frequencyDeltas[0]);
    assertEquals(-2, delta.frequencyDeltas[1]);
    assertEquals(10, delta.frequencyDeltas[2]);
  }

  @Test
  public void getOrCreateHllLazilyAllocatesSketch() {
    var delta = new HistogramDelta();
    assertNull(delta.hllSketch);

    var hll = delta.getOrCreateHll();
    assertNotNull(hll);

    // Same instance on second call
    var hll2 = delta.getOrCreateHll();
    assertSame(hll, hll2);
  }

  @Test
  public void scalarCountersAccumulate() {
    var delta = new HistogramDelta();
    delta.totalCountDelta += 3;
    delta.totalCountDelta -= 1;
    delta.nullCountDelta += 2;
    delta.mutationCount += 4;

    assertEquals(2, delta.totalCountDelta);
    assertEquals(2, delta.nullCountDelta);
    assertEquals(4, delta.mutationCount);
  }
}
