/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */

package com.jetbrains.youtrackdb.internal.core.index.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.StringSerializer;
import com.jetbrains.youtrackdb.internal.core.db.record.CurrentStorageComponentsFactory;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests covering gaps identified during code review:
 * <ul>
 *   <li>T1: Transaction rollback — deltas must NOT be applied</li>
 *   <li>T3: Page-level round-trip for large string boundaries near MAX_BOUNDARY_BYTES</li>
 *   <li>T4: Negative totalCount protection — deletes outpacing persisted inserts</li>
 * </ul>
 */
public class HistogramTestGapsCoverageTest {

  private BinarySerializerFactory serializerFactory;
  private ByteBufferPool bufferPool;

  @Before
  public void setUp() {
    serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
    bufferPool = new ByteBufferPool(DurablePage.MAX_PAGE_SIZE_BYTES);
  }

  @After
  public void tearDown() {
    if (bufferPool != null) {
      bufferPool.clear();
    }
  }

  // ═══════════════════════════════════════════════════════════════
  //  T1: Transaction rollback — deltas must NOT be applied
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void rollback_deltasAreNotApplied_countersUnchanged() {
    // Given: a manager with a populated snapshot
    var fixture = new ManagerFixture(serializerFactory);
    var stats = new IndexStatistics(1000, 1000, 50);
    var histogram = create4BucketHistogram();
    fixture.cache.put(fixture.engineId,
        new HistogramSnapshot(stats, histogram, 0, 1000, 0, false, null, false));

    // When: onPut accumulates deltas in the transaction-local holder...
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);

    fixture.manager.onPut(op, 30, true, true);
    fixture.manager.onPut(op, 60, true, true);
    fixture.manager.onRemove(op, 80, true);

    // ...but the transaction is rolled back (deltas are simply discarded,
    // applyDelta is never called)

    // Then: the CHM cache snapshot is completely unchanged
    var snapshot = fixture.cache.get(fixture.engineId);
    assertNotNull(snapshot);
    assertEquals("totalCount must be unchanged after rollback",
        1000, snapshot.stats().totalCount());
    assertEquals("nullCount must be unchanged after rollback",
        50, snapshot.stats().nullCount());
    assertEquals("distinctCount must be unchanged after rollback",
        1000, snapshot.stats().distinctCount());
    assertEquals("mutationsSinceRebalance must be unchanged",
        0, snapshot.mutationsSinceRebalance());

    // Histogram frequencies must be unchanged
    var h = snapshot.histogram();
    assertNotNull(h);
    assertEquals(250, h.frequencies()[0]);
    assertEquals(250, h.frequencies()[1]);
    assertEquals(250, h.frequencies()[2]);
    assertEquals(250, h.frequencies()[3]);
    assertEquals(1000, h.nonNullCount());
  }

  @Test
  public void rollback_deltasAreNotApplied_dirtyMutationsUnchanged() {
    // Given: a manager with zero dirty mutations
    var fixture = new ManagerFixture(serializerFactory);
    var stats = new IndexStatistics(100, 100, 0);
    fixture.cache.put(fixture.engineId,
        new HistogramSnapshot(stats, null, 0, 100, 0, false, null, false));

    assertEquals(0, fixture.manager.getDirtyMutations());

    // When: onPut accumulates deltas, then transaction is rolled back
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(op, 42, true, true);

    // Rollback: do NOT call applyDelta

    // Then: dirtyMutations is still 0 — no CHM modification occurred
    assertEquals("dirtyMutations must be 0 after rollback",
        0, fixture.manager.getDirtyMutations());
  }

  @Test
  public void rollback_followedByCommit_onlyCommittedDeltaApplied() {
    // Given: a manager with a populated snapshot
    var fixture = new ManagerFixture(serializerFactory);
    var stats = new IndexStatistics(1000, 1000, 0);
    fixture.cache.put(fixture.engineId,
        new HistogramSnapshot(stats, null, 0, 1000, 0, false, null, false));

    // When: first transaction inserts 10, then is rolled back
    var holder1 = new HistogramDeltaHolder();
    var op1 = mockOp(holder1);
    for (int i = 0; i < 10; i++) {
      fixture.manager.onPut(op1, i, true, true);
    }
    // Rollback: do NOT call applyDelta for holder1

    // Second transaction inserts 3, then is committed
    var holder2 = new HistogramDeltaHolder();
    var op2 = mockOp(holder2);
    for (int i = 0; i < 3; i++) {
      fixture.manager.onPut(op2, i + 100, true, true);
    }
    fixture.manager.applyDelta(
        holder2.getDeltas().get(fixture.engineId));

    // Then: only the 3 committed inserts are reflected
    var snapshot = fixture.cache.get(fixture.engineId);
    assertNotNull(snapshot);
    assertEquals("Only committed delta should be applied",
        1003, snapshot.stats().totalCount());
  }

  // ═══════════════════════════════════════════════════════════════
  //  T3: Page-level round-trip for large string boundaries
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void stringBoundaries_nearMaxBoundaryBytes_roundTripThroughPage() {
    // Given: a histogram with string boundaries near MAX_BOUNDARY_BYTES (256).
    // Each boundary is a 200-char ASCII string (serialized size: 4+2+200 = 206
    // bytes via StringSerializer which uses UTF-16 internally with a 2-byte
    // length prefix, but serializeNativeAsWhole produces 4+len*2 bytes for
    // StringSerializer). The exact size depends on the serializer; what matters
    // is that boundaries are large but within budget.
    int bucketCount = 4;
    var boundaries = new Comparable<?>[bucketCount + 1];
    for (int i = 0; i <= bucketCount; i++) {
      // Generate distinct 200-char strings (different first char ensures ordering)
      boundaries[i] = String.valueOf((char) ('A' + i))
          + "x".repeat(199);
    }
    var frequencies = new long[] {100, 200, 300, 400};
    var distinctCounts = new long[] {10, 20, 30, 40};
    var histogram = new EquiDepthHistogram(
        bucketCount, boundaries, frequencies, distinctCounts,
        1000, boundaries[2], 300);

    var stats = new IndexStatistics(1000, 100, 0);
    var snapshot = new HistogramSnapshot(
        stats, histogram, 42, 900, 5, false, null, false);

    // When: write to page and read back
    CacheEntry entry = allocatePage();
    try {
      @SuppressWarnings("unchecked")
      var serializer =
          (com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer<
              Object>) (Object) StringSerializer.INSTANCE;

      var page = new HistogramStatsPage(entry);
      page.writeSnapshot(snapshot, StringSerializer.ID, serializer,
          serializerFactory);

      var loaded = page.readSnapshot(serializer, serializerFactory);

      // Then: all data round-trips correctly
      assertNotNull(loaded);
      assertNotNull(loaded.histogram());
      assertEquals(bucketCount, loaded.histogram().bucketCount());
      assertEquals(1000, loaded.histogram().nonNullCount());
      assertEquals(42, loaded.mutationsSinceRebalance());
      assertEquals(900, loaded.totalCountAtLastBuild());

      // Verify boundary values
      for (int i = 0; i <= bucketCount; i++) {
        assertEquals("Boundary " + i + " must round-trip",
            boundaries[i], loaded.histogram().boundaries()[i]);
      }

      // Verify frequencies and distinctCounts
      for (int i = 0; i < bucketCount; i++) {
        assertEquals("Frequency " + i, frequencies[i],
            loaded.histogram().frequencies()[i]);
        assertEquals("DistinctCount " + i, distinctCounts[i],
            loaded.histogram().distinctCounts()[i]);
      }

      // Verify MCV round-trip
      assertEquals(boundaries[2], loaded.histogram().mcvValue());
      assertEquals(300, loaded.histogram().mcvFrequency());
    } finally {
      releasePage(entry);
    }
  }

  @Test
  public void stringBoundaries_atExactlyMaxBoundaryBytes_roundTrip() {
    // Given: boundaries at exactly 256 bytes serialized. StringSerializer
    // uses UTF-16 encoding internally: serializeNativeAsWhole produces
    // [4-byte length prefix] + [2 bytes per char]. A 126-char string
    // serializes to 4 + 126*2 = 256 bytes.
    int bucketCount = 2;
    var boundaries = new Comparable<?>[bucketCount + 1];
    for (int i = 0; i <= bucketCount; i++) {
      boundaries[i] = String.valueOf((char) ('A' + i))
          + "z".repeat(125);
    }
    var frequencies = new long[] {500, 500};
    var distinctCounts = new long[] {50, 50};
    var histogram = new EquiDepthHistogram(
        bucketCount, boundaries, frequencies, distinctCounts,
        1000, null, 0);

    var stats = new IndexStatistics(1000, 100, 0);
    var snapshot = new HistogramSnapshot(
        stats, histogram, 0, 1000, 0, false, null, false);

    CacheEntry entry = allocatePage();
    try {
      @SuppressWarnings("unchecked")
      var serializer =
          (com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer<
              Object>) (Object) StringSerializer.INSTANCE;

      var page = new HistogramStatsPage(entry);
      page.writeSnapshot(snapshot, StringSerializer.ID, serializer,
          serializerFactory);

      var loaded = page.readSnapshot(serializer, serializerFactory);

      assertNotNull(loaded);
      assertNotNull(loaded.histogram());
      assertEquals(bucketCount, loaded.histogram().bucketCount());
      for (int i = 0; i <= bucketCount; i++) {
        assertEquals("Boundary " + i, boundaries[i],
            loaded.histogram().boundaries()[i]);
      }
    } finally {
      releasePage(entry);
    }
  }

  // ═══════════════════════════════════════════════════════════════
  //  T4: Negative totalCount protection
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void negativeTotalCountDelta_clampedToZero() {
    // Scenario: after a crash, the persisted totalCount is stale (e.g., 100)
    // but many deletes have been applied since. A large negative delta could
    // push totalCount below zero. computeNewSnapshot must clamp to >= 0.
    var stats = new IndexStatistics(100, 100, 10);
    var snapshot = new HistogramSnapshot(
        stats, null, 0, 100, 0, false, null, false);

    var delta = new HistogramDelta();
    delta.totalCountDelta = -200; // more deletes than entries exist
    delta.nullCountDelta = -50; // more null deletes than nulls exist
    delta.mutationCount = 200;

    var result = IndexHistogramManager.computeNewSnapshot(snapshot, delta);

    assertNotNull(result);
    assertEquals("totalCount must be clamped to 0",
        0, result.stats().totalCount());
    assertEquals("nullCount must be clamped to 0",
        0, result.stats().nullCount());
    // distinctCount for single-value = max(0, total - null) = max(0, 0-0) = 0
    assertEquals("distinctCount must be clamped to 0",
        0, result.stats().distinctCount());
  }

  @Test
  public void negativeFrequencyDelta_clampedToZero_driftFlagSet() {
    // When bucket frequencies go negative (entries deleted that were inserted
    // before the histogram was built), they should be clamped to 0 and the
    // hasDriftedBuckets flag should be set.
    var histogram = create4BucketHistogram(); // 250 per bucket
    var stats = new IndexStatistics(1000, 1000, 0);
    var snapshot = new HistogramSnapshot(
        stats, histogram, 0, 1000, 0, false, null, false);

    var delta = new HistogramDelta();
    delta.totalCountDelta = -300;
    delta.mutationCount = 300;
    // Remove 300 entries from bucket 0 (only has 250 → goes to -50 → clamped to 0)
    delta.initFrequencyDeltas(4, 0);
    delta.frequencyDeltas[0] = -300;

    var result = IndexHistogramManager.computeNewSnapshot(snapshot, delta);

    assertNotNull(result);
    assertNotNull(result.histogram());
    assertEquals("Bucket 0 frequency must be clamped to 0",
        0, result.histogram().frequencies()[0]);
    assertEquals("Bucket 1 frequency must be unchanged",
        250, result.histogram().frequencies()[1]);
    assertTrue("hasDriftedBuckets must be set when frequency is clamped",
        result.hasDriftedBuckets());
    // nonNullCount = sum of clamped frequencies = 0 + 250 + 250 + 250 = 750
    assertEquals(750, result.histogram().nonNullCount());
  }

  @Test
  public void massiveDeletion_allCountersClampedToZero_noHistogram() {
    // Edge case: all entries deleted after histogram was built.
    // All counters go to zero, but the histogram structure remains
    // (with zero frequencies). The system stays functional.
    var histogram = create4BucketHistogram();
    var stats = new IndexStatistics(1000, 1000, 0);
    var snapshot = new HistogramSnapshot(
        stats, histogram, 0, 1000, 0, false, null, false);

    var delta = new HistogramDelta();
    delta.totalCountDelta = -1000;
    delta.mutationCount = 1000;
    delta.initFrequencyDeltas(4, 0);
    delta.frequencyDeltas[0] = -250;
    delta.frequencyDeltas[1] = -250;
    delta.frequencyDeltas[2] = -250;
    delta.frequencyDeltas[3] = -250;

    var result = IndexHistogramManager.computeNewSnapshot(snapshot, delta);

    assertNotNull(result);
    assertEquals(0, result.stats().totalCount());
    assertEquals(0, result.stats().distinctCount());
    // Histogram still present but with zero frequencies
    assertNotNull(result.histogram());
    assertEquals(0, result.histogram().nonNullCount());
    for (int i = 0; i < 4; i++) {
      assertEquals("All frequencies must be 0", 0,
          result.histogram().frequencies()[i]);
    }
  }

  @Test
  public void nullCountExceedsTotalCount_bothClampedIndependently() {
    // After crash recovery with stale counters, nullCount could exceed
    // totalCount. Both are clamped independently to >= 0.
    var stats = new IndexStatistics(50, 50, 30);
    var snapshot = new HistogramSnapshot(
        stats, null, 0, 50, 0, false, null, false);

    var delta = new HistogramDelta();
    delta.totalCountDelta = -40; // totalCount: 50 - 40 = 10
    delta.nullCountDelta = -5; // nullCount: 30 - 5 = 25
    delta.mutationCount = 40;
    // Now nullCount (25) > totalCount (10) — inconsistent but clamped

    var result = IndexHistogramManager.computeNewSnapshot(snapshot, delta);

    assertNotNull(result);
    assertEquals(10, result.stats().totalCount());
    assertEquals(25, result.stats().nullCount());
    // distinctCount = max(0, total - null) = max(0, 10-25) = 0
    assertEquals(0, result.stats().distinctCount());
  }

  // ═══════════════════════════════════════════════════════════════
  //  Helpers
  // ═══════════════════════════════════════════════════════════════

  private static EquiDepthHistogram create4BucketHistogram() {
    return new EquiDepthHistogram(
        4,
        new Comparable<?>[] {0, 25, 50, 75, 100},
        new long[] {250, 250, 250, 250},
        new long[] {25, 25, 25, 25},
        1000, null, 0);
  }

  private static AtomicOperation mockOp(HistogramDeltaHolder holder) {
    var op = mock(AtomicOperation.class);
    when(op.getOrCreateHistogramDeltas()).thenReturn(holder);
    return op;
  }

  private CacheEntry allocatePage() {
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    entry.acquireExclusiveLock();
    return entry;
  }

  private void releasePage(CacheEntry entry) {
    entry.releaseExclusiveLock();
    entry.getCachePointer().decrementReferrer();
  }

  private static class ManagerFixture {
    final int engineId = 0;
    final ConcurrentHashMap<Integer, HistogramSnapshot> cache =
        new ConcurrentHashMap<>();
    final IndexHistogramManager manager;

    ManagerFixture(BinarySerializerFactory serializerFactory) {
      var storage = mock(AbstractStorage.class);
      var factory = new CurrentStorageComponentsFactory(
          BinarySerializerFactory.currentBinaryFormatVersion());
      when(storage.getComponentsFactory()).thenReturn(factory);
      when(storage.getAtomicOperationsManager())
          .thenReturn(mock(AtomicOperationsManager.class));
      when(storage.getReadCache()).thenReturn(mock(ReadCache.class));
      when(storage.getWriteCache()).thenReturn(mock(WriteCache.class));
      manager = new IndexHistogramManager(
          storage, "test-idx", engineId, true, cache,
          IntegerSerializer.INSTANCE, serializerFactory,
          IntegerSerializer.ID);
    }
  }
}
