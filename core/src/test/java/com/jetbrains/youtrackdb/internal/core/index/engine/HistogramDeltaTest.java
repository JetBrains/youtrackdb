package com.jetbrains.youtrackdb.internal.core.index.engine;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

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

  @Test
  public void testRealisticPutRemoveSequence() {
    // Simulate: 3 inserts (keys in buckets 0, 1, 1)
    // + 1 null insert + 1 remove (bucket 0)
    var delta = new HistogramDelta();

    // Insert key into bucket 0
    delta.totalCountDelta++;
    delta.mutationCount++;
    delta.initFrequencyDeltas(4, 0);
    delta.frequencyDeltas[0]++;

    // Insert key into bucket 1
    delta.totalCountDelta++;
    delta.mutationCount++;
    delta.frequencyDeltas[1]++;

    // Insert key into bucket 1 again
    delta.totalCountDelta++;
    delta.mutationCount++;
    delta.frequencyDeltas[1]++;

    // Insert null key
    delta.totalCountDelta++;
    delta.nullCountDelta++;
    delta.mutationCount++;

    // Remove key from bucket 0
    delta.totalCountDelta--;
    delta.mutationCount++;
    delta.frequencyDeltas[0]--;

    // Verify net effect
    assertEquals(3, delta.totalCountDelta);  // 4 inserts - 1 remove
    assertEquals(1, delta.nullCountDelta);
    assertEquals(5, delta.mutationCount);
    assertEquals(0, delta.frequencyDeltas[0]);  // +1 then -1
    assertEquals(2, delta.frequencyDeltas[1]);  // +2
    assertEquals(0, delta.frequencyDeltas[2]);
    assertEquals(0, delta.frequencyDeltas[3]);
  }

  @Test
  public void initFrequencyDeltasZeroBucketsAllocatesEmptyArray() {
    var delta = new HistogramDelta();
    delta.initFrequencyDeltas(0, 5);

    assertNotNull(delta.frequencyDeltas);
    assertEquals(0, delta.frequencyDeltas.length);
    assertEquals(5, delta.snapshotVersion);
  }

  @Test
  public void getOrCreateHllSmokeTest() {
    var delta = new HistogramDelta();
    var hll = delta.getOrCreateHll();

    hll.add(12345L);
    hll.add(67890L);
    assertTrue("HLL estimate should be > 0 after adding values",
        hll.estimate() > 0);
  }
}
