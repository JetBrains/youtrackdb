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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.core.db.record.CurrentStorageComponentsFactory;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Test;

/**
 * Unit tests for {@link IndexHistogramManager} — Section 10.1 of the ADR.
 *
 * <p>Exercises the manager's incremental update lifecycle: onPut/onRemove delta
 * accumulation, applyDelta commit, rollback discard, null key handling, CHM
 * cache behavior, and mutationsSinceRebalance tracking.
 *
 * <p>These tests use a mock storage (no real disk I/O) with a real CHM cache
 * and real {@link HistogramDeltaHolder} instances to verify observable behavior
 * through the cache.
 *
 * <p><b>Note on Section 10.1 scenario 7 (page persistence round-trip):</b>
 * Testing create/close/re-load with real page I/O requires integration-level
 * storage infrastructure. That scenario is covered by the integration tests in
 * {@code BTreeEngineHistogramBuildTest} and {@code CheckpointFlushTest}. This
 * class verifies the CHM cache consistency that underpins persistence.
 */
public class IndexHistogramManagerUnitTest {

  // ═══════════════════════════════════════════════════════════════════════
  // Empty statistics on creation
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void newManager_getStatistics_returnsNullBeforeStatsFileCreated() {
    // Given a freshly constructed manager (no createStatsFile/openStatsFile)
    var fixture = new Fixture();

    // When getStatistics is called
    var stats = fixture.manager.getStatistics();

    // Then null — no cache entry installed yet
    assertNull(stats);
  }

  @Test
  public void newManager_afterCachePopulated_returnsEmptyStatistics() {
    // Given a manager with an empty snapshot installed in the cache
    // (simulates createStatsFile without real disk I/O)
    var fixture = new Fixture();
    installEmptySnapshot(fixture);

    // When getStatistics is called
    var stats = fixture.manager.getStatistics();

    // Then statistics are zero-valued
    assertNotNull(stats);
    assertEquals(0, stats.totalCount());
    assertEquals(0, stats.distinctCount());
    assertEquals(0, stats.nullCount());
  }

  @Test
  public void newManager_getHistogram_returnsNullForEmptySnapshot() {
    // Given an empty snapshot (no histogram built yet)
    var fixture = new Fixture();
    installEmptySnapshot(fixture);

    // When getHistogram is called
    var histogram = fixture.manager.getHistogram();

    // Then null — no histogram has been built
    assertNull(histogram);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // onPut(wasInsert=true) → counter increments via applyDelta
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void onPut_insert_incrementsTotalCountAfterApplyDelta() {
    // Given a manager with an initial snapshot (totalCount=100)
    var fixture = new Fixture();
    installSnapshot(fixture, 100, 100, 0, null);

    // When onPut(wasInsert=true) is called and the delta is applied
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(op, 42, true, true);
    var delta = holder.getDeltas().get(fixture.engineId);
    fixture.manager.applyDelta(delta);

    // Then totalCount increases by 1
    var stats = fixture.manager.getStatistics();
    assertEquals(101, stats.totalCount());
    // Single-value: distinctCount == totalCount
    assertEquals(101, stats.distinctCount());
  }

  @Test
  public void onPut_multipleInserts_incrementsTotalCountByInsertCount() {
    // Given a manager with initial snapshot (totalCount=50)
    var fixture = new Fixture();
    installSnapshot(fixture, 50, 50, 0, null);

    // When 5 onPut(wasInsert=true) calls are made in one transaction
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    for (int i = 0; i < 5; i++) {
      fixture.manager.onPut(op, i, true, true);
    }
    var delta = holder.getDeltas().get(fixture.engineId);
    fixture.manager.applyDelta(delta);

    // Then totalCount increases by 5
    assertEquals(55, fixture.manager.getStatistics().totalCount());
  }

  // ═══════════════════════════════════════════════════════════════════════
  // onRemove → counter decrements via applyDelta
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void onRemove_decrementsTotalCountAfterApplyDelta() {
    // Given a manager with initial snapshot (totalCount=100)
    var fixture = new Fixture();
    installSnapshot(fixture, 100, 100, 0, null);

    // When onRemove is called and delta applied
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onRemove(op, 42, true);
    var delta = holder.getDeltas().get(fixture.engineId);
    fixture.manager.applyDelta(delta);

    // Then totalCount decreases by 1
    assertEquals(99, fixture.manager.getStatistics().totalCount());
  }

  @Test
  public void onRemove_clampsTotalCountToZero() {
    // Given a manager with totalCount=1
    var fixture = new Fixture();
    installSnapshot(fixture, 1, 1, 0, null);

    // When two removes are applied (would go negative)
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onRemove(op, 1, true);
    fixture.manager.onRemove(op, 2, true);
    var delta = holder.getDeltas().get(fixture.engineId);
    fixture.manager.applyDelta(delta);

    // Then totalCount is clamped to 0
    assertEquals(0, fixture.manager.getStatistics().totalCount());
  }

  // ═══════════════════════════════════════════════════════════════════════
  // onPut(wasInsert=false) → no counter or frequency changes
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void onPut_update_doesNotChangeTotalCount() {
    // Given a manager with initial snapshot (totalCount=100) and a histogram
    var fixture = new Fixture();
    var histogram = createSimpleHistogram(100);
    installSnapshot(fixture, 100, 100, 0, histogram);

    // When onPut(wasInsert=false) is called (single-value update)
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(op, 5, true, false);
    var delta = holder.getDeltas().get(fixture.engineId);
    fixture.manager.applyDelta(delta);

    // Then totalCount is unchanged
    assertEquals(100, fixture.manager.getStatistics().totalCount());
    // And histogram frequencies are unchanged
    var h = fixture.manager.getHistogram();
    assertNotNull(h);
    assertEquals(100, h.nonNullCount());
  }

  @Test
  public void onPut_update_doesNotChangeFrequencyDeltas() {
    // Given a manager with a histogram
    var fixture = new Fixture();
    var histogram = createSimpleHistogram(200);
    installSnapshot(fixture, 200, 200, 0, histogram);

    // When onPut(wasInsert=false) is called
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(op, 5, true, false);

    // Then the delta has no frequency deltas
    var delta = holder.getDeltas().get(fixture.engineId);
    assertNull(delta.frequencyDeltas);
    assertEquals(0, delta.totalCountDelta);
    assertEquals(0, delta.nullCountDelta);
  }

  @Test
  public void onPut_update_stillIncrementsMutationCount() {
    // Updates still count as mutations (for rebalance threshold)
    var fixture = new Fixture();
    installSnapshot(fixture, 100, 100, 0, null);

    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(op, 5, true, false);

    var delta = holder.getDeltas().get(fixture.engineId);
    assertEquals(1, delta.mutationCount);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Single-value repeated put with same key → wasInsert=false
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void repeatedPut_sameKey_wasInsertFalse_totalCountUnchanged() {
    // Simulates the B-tree returning wasInsert=false for a duplicate key.
    // The manager should not increment totalCount.
    var fixture = new Fixture();
    installSnapshot(fixture, 100, 100, 0, null);

    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    // First insert
    fixture.manager.onPut(op, 42, true, true);
    // Second put of same key → update (wasInsert=false)
    fixture.manager.onPut(op, 42, true, false);

    var delta = holder.getDeltas().get(fixture.engineId);
    fixture.manager.applyDelta(delta);

    // totalCount should increase by 1 (only the first insert counts)
    assertEquals(101, fixture.manager.getStatistics().totalCount());
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Null key handling (nullCount tracking)
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void onPut_nullKey_incrementsNullCount() {
    // Given a manager with initial snapshot (nullCount=5)
    var fixture = new Fixture();
    installSnapshot(fixture, 100, 100, 5, null);

    // When onPut with null key
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(op, null, true, true);
    var delta = holder.getDeltas().get(fixture.engineId);
    fixture.manager.applyDelta(delta);

    // Then nullCount increases by 1
    assertEquals(6, fixture.manager.getStatistics().nullCount());
    assertEquals(101, fixture.manager.getStatistics().totalCount());
  }

  @Test
  public void onRemove_nullKey_decrementsNullCount() {
    // Given a manager with initial snapshot (nullCount=5)
    var fixture = new Fixture();
    installSnapshot(fixture, 100, 100, 5, null);

    // When onRemove with null key
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onRemove(op, null, true);
    var delta = holder.getDeltas().get(fixture.engineId);
    fixture.manager.applyDelta(delta);

    // Then nullCount decreases by 1 and totalCount decreases by 1
    assertEquals(4, fixture.manager.getStatistics().nullCount());
    assertEquals(99, fixture.manager.getStatistics().totalCount());
  }

  @Test
  public void onPut_nullKey_doesNotAffectFrequencyDeltas() {
    // Null keys go to nullCount, not to histogram bucket frequencies
    var fixture = new Fixture();
    var histogram = createSimpleHistogram(100);
    installSnapshot(fixture, 105, 105, 5, histogram);

    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(op, null, true, true);

    var delta = holder.getDeltas().get(fixture.engineId);
    // No frequency deltas for null keys
    assertNull(delta.frequencyDeltas);
    assertEquals(1, delta.nullCountDelta);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // mutationsSinceRebalance increments on each operation
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void mutationsSinceRebalance_incrementsOnInsert() {
    var fixture = new Fixture();
    installSnapshot(fixture, 100, 100, 0, null);

    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(op, 1, true, true);
    fixture.manager.applyDelta(holder.getDeltas().get(fixture.engineId));

    assertEquals(1, fixture.manager.getSnapshot().mutationsSinceRebalance());
  }

  @Test
  public void mutationsSinceRebalance_incrementsOnUpdate() {
    // Updates (wasInsert=false) also count toward rebalance threshold
    var fixture = new Fixture();
    installSnapshot(fixture, 100, 100, 0, null);

    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(op, 1, true, false);
    fixture.manager.applyDelta(holder.getDeltas().get(fixture.engineId));

    assertEquals(1, fixture.manager.getSnapshot().mutationsSinceRebalance());
  }

  @Test
  public void mutationsSinceRebalance_incrementsOnRemove() {
    var fixture = new Fixture();
    installSnapshot(fixture, 100, 100, 0, null);

    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onRemove(op, 1, true);
    fixture.manager.applyDelta(holder.getDeltas().get(fixture.engineId));

    assertEquals(1, fixture.manager.getSnapshot().mutationsSinceRebalance());
  }

  @Test
  public void mutationsSinceRebalance_accumulatesAcrossMultipleDeltas() {
    var fixture = new Fixture();
    installSnapshot(fixture, 100, 100, 0, null);

    // First transaction: 3 mutations
    var holder1 = new HistogramDeltaHolder();
    var op1 = mockOp(holder1);
    fixture.manager.onPut(op1, 1, true, true);
    fixture.manager.onPut(op1, 2, true, true);
    fixture.manager.onRemove(op1, 3, true);
    fixture.manager.applyDelta(holder1.getDeltas().get(fixture.engineId));

    // Second transaction: 2 mutations
    var holder2 = new HistogramDeltaHolder();
    var op2 = mockOp(holder2);
    fixture.manager.onPut(op2, 4, true, true);
    fixture.manager.onPut(op2, 5, true, false);
    fixture.manager.applyDelta(holder2.getDeltas().get(fixture.engineId));

    // Total: 3 + 2 = 5 mutations
    assertEquals(5, fixture.manager.getSnapshot().mutationsSinceRebalance());
  }

  // ═══════════════════════════════════════════════════════════════════════
  // CHM cache hit/miss behavior
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void getStatistics_cacheMiss_returnsNull() {
    // Given a manager with no cache entry
    var fixture = new Fixture();

    // When getStatistics is called
    // Then null — cache miss
    assertNull(fixture.manager.getStatistics());
  }

  @Test
  public void getStatistics_cacheHit_returnsCurrentStats() {
    // Given a manager with a cache entry
    var fixture = new Fixture();
    installSnapshot(fixture, 42, 42, 7, null);

    // When getStatistics is called
    var stats = fixture.manager.getStatistics();

    // Then returns the cached stats
    assertNotNull(stats);
    assertEquals(42, stats.totalCount());
    assertEquals(7, stats.nullCount());
  }

  @Test
  public void getSnapshot_cacheMiss_returnsNull() {
    var fixture = new Fixture();
    assertNull(fixture.manager.getSnapshot());
  }

  @Test
  public void getSnapshot_cacheHit_returnsFullSnapshot() {
    var fixture = new Fixture();
    var histogram = createSimpleHistogram(100);
    installSnapshot(fixture, 100, 100, 0, histogram);

    var snapshot = fixture.manager.getSnapshot();
    assertNotNull(snapshot);
    assertNotNull(snapshot.histogram());
    assertEquals(100, snapshot.stats().totalCount());
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Null snapshot during onPut/onRemove → frequency deltas skipped
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void onPut_noCacheEntry_skipsFrequencyDeltasButUpdatesCounters() {
    // Given a manager with NO cache entry (engine not yet opened)
    var fixture = new Fixture();
    // Do NOT install a snapshot

    // When onPut is called
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(op, 42, true, true);

    // Then delta has scalar counters but no frequency deltas
    var delta = holder.getDeltas().get(fixture.engineId);
    assertEquals(1, delta.totalCountDelta);
    assertEquals(1, delta.mutationCount);
    assertNull(delta.frequencyDeltas);
  }

  @Test
  public void onRemove_noCacheEntry_skipsFrequencyDeltasButUpdatesCounters() {
    // Given a manager with NO cache entry
    var fixture = new Fixture();

    // When onRemove is called
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onRemove(op, 42, true);

    // Then delta has scalar counters but no frequency deltas
    var delta = holder.getDeltas().get(fixture.engineId);
    assertEquals(-1, delta.totalCountDelta);
    assertEquals(1, delta.mutationCount);
    assertNull(delta.frequencyDeltas);
  }

  @Test
  public void onPut_cacheEntryWithNoHistogram_skipsFrequencyDeltas() {
    // Given a snapshot with counters but no histogram (below min size)
    var fixture = new Fixture();
    installSnapshot(fixture, 10, 10, 0, null);

    // When onPut is called with a non-null key
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(op, 42, true, true);

    // Then delta has scalar counters but no frequency deltas
    var delta = holder.getDeltas().get(fixture.engineId);
    assertEquals(1, delta.totalCountDelta);
    assertNull(delta.frequencyDeltas);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // onPut/onRemove with histogram → frequency deltas populated
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void onPut_withHistogram_populatesFrequencyDeltaForCorrectBucket() {
    // Given a 2-bucket histogram: [0..50) and [50..100]
    var fixture = new Fixture();
    var histogram = new EquiDepthHistogram(
        2,
        new Comparable<?>[]{0, 50, 100},
        new long[]{50, 50},
        new long[]{50, 50},
        100,
        null, 0
    );
    installSnapshot(fixture, 100, 100, 0, histogram);

    // When onPut is called with key=75 (should go to bucket 1)
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(op, 75, true, true);

    // Then frequency delta for bucket 1 is +1
    var delta = holder.getDeltas().get(fixture.engineId);
    assertNotNull(delta.frequencyDeltas);
    assertEquals(0, delta.frequencyDeltas[0]);
    assertEquals(1, delta.frequencyDeltas[1]);
  }

  @Test
  public void onRemove_withHistogram_populatesNegativeFrequencyDelta() {
    // Given a 2-bucket histogram: [0..50) and [50..100]
    var fixture = new Fixture();
    var histogram = new EquiDepthHistogram(
        2,
        new Comparable<?>[]{0, 50, 100},
        new long[]{50, 50},
        new long[]{50, 50},
        100,
        null, 0
    );
    installSnapshot(fixture, 100, 100, 0, histogram);

    // When onRemove is called with key=25 (bucket 0)
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onRemove(op, 25, true);

    // Then frequency delta for bucket 0 is -1
    var delta = holder.getDeltas().get(fixture.engineId);
    assertNotNull(delta.frequencyDeltas);
    assertEquals(-1, delta.frequencyDeltas[0]);
    assertEquals(0, delta.frequencyDeltas[1]);
  }

  @Test
  public void onPut_insert_thenApplyDelta_updatesHistogramFrequencies() {
    // End-to-end: insert into a bucket, apply delta, verify histogram
    var fixture = new Fixture();
    var histogram = new EquiDepthHistogram(
        2,
        new Comparable<?>[]{0, 50, 100},
        new long[]{50, 50},
        new long[]{50, 50},
        100,
        null, 0
    );
    installSnapshot(fixture, 100, 100, 0, histogram);

    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(op, 75, true, true);
    fixture.manager.onPut(op, 25, true, true);
    fixture.manager.onPut(op, 80, true, true);
    fixture.manager.applyDelta(holder.getDeltas().get(fixture.engineId));

    var h = fixture.manager.getHistogram();
    assertNotNull(h);
    assertEquals(51, h.frequencies()[0]);  // +1 (key=25)
    assertEquals(52, h.frequencies()[1]);  // +2 (key=75, 80)
    assertEquals(103, h.nonNullCount());
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Rollback discards deltas
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void rollback_discardsDeltas_chmUnchanged() {
    // Given a manager with snapshot (totalCount=100)
    var fixture = new Fixture();
    installSnapshot(fixture, 100, 100, 0, null);

    // When onPut is called but applyDelta is NEVER called (simulating
    // rollback — the delta holder is simply discarded)
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(op, 42, true, true);
    fixture.manager.onPut(op, 43, true, true);
    // No applyDelta — rollback

    // Then CHM is unchanged
    assertEquals(100, fixture.manager.getStatistics().totalCount());
    assertEquals(0, fixture.manager.getSnapshot().mutationsSinceRebalance());
  }

  @Test
  public void rollback_afterInsertAndRemove_chmUnchanged() {
    // More complex: mix of inserts and removes, then rollback
    var fixture = new Fixture();
    var histogram = createSimpleHistogram(200);
    installSnapshot(fixture, 200, 200, 0, histogram);

    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(op, 5, true, true);
    fixture.manager.onRemove(op, 15, true);
    fixture.manager.onPut(op, null, true, true);
    // No applyDelta — rollback

    // CHM unchanged
    assertEquals(200, fixture.manager.getStatistics().totalCount());
    assertEquals(200, fixture.manager.getHistogram().nonNullCount());
    assertEquals(0, fixture.manager.getSnapshot().mutationsSinceRebalance());
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Delta application with null CHM entry (engine deleted)
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void applyDelta_nullChmEntry_silentlyDiscarded() {
    // Given a manager with NO cache entry (engine was deleted between
    // transaction start and commit)
    var fixture = new Fixture();
    // Do NOT install a snapshot

    // When applyDelta is called
    var delta = new HistogramDelta();
    delta.totalCountDelta = 10;
    delta.mutationCount = 10;
    fixture.manager.applyDelta(delta);

    // Then no exception, and no cache entry is created
    assertNull(fixture.manager.getStatistics());
    assertNull(fixture.manager.getSnapshot());
  }

  @Test
  public void applyDelta_chmEntryRemovedDuringCommit_silentlyDiscarded() {
    // Given a manager with a cache entry
    var fixture = new Fixture();
    installSnapshot(fixture, 100, 100, 0, null);

    // When cache entry is removed (engine deleted) before applyDelta
    fixture.cache.remove(fixture.engineId);
    var delta = new HistogramDelta();
    delta.totalCountDelta = 5;
    delta.mutationCount = 5;
    fixture.manager.applyDelta(delta);

    // Then no cache entry is recreated
    assertNull(fixture.manager.getSnapshot());
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Bulk loading suppresses onPut/onRemove
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void bulkLoading_onPut_isNoOp() {
    var fixture = new Fixture();
    installSnapshot(fixture, 100, 100, 0, null);

    fixture.manager.setBulkLoading(true);
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(op, 42, true, true);

    // Delta holder should not have an entry for this engine
    assertFalse(holder.getDeltas().containsKey(fixture.engineId));
  }

  @Test
  public void bulkLoading_onRemove_isNoOp() {
    var fixture = new Fixture();
    installSnapshot(fixture, 100, 100, 0, null);

    fixture.manager.setBulkLoading(true);
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onRemove(op, 42, true);

    assertFalse(holder.getDeltas().containsKey(fixture.engineId));
  }

  @Test
  public void bulkLoading_toggleOff_resumesNormalOperation() {
    var fixture = new Fixture();
    installSnapshot(fixture, 100, 100, 0, null);

    fixture.manager.setBulkLoading(true);
    var holder1 = new HistogramDeltaHolder();
    var op1 = mockOp(holder1);
    fixture.manager.onPut(op1, 42, true, true);
    assertFalse(holder1.getDeltas().containsKey(fixture.engineId));

    fixture.manager.setBulkLoading(false);
    var holder2 = new HistogramDeltaHolder();
    var op2 = mockOp(holder2);
    fixture.manager.onPut(op2, 42, true, true);
    assertTrue(holder2.getDeltas().containsKey(fixture.engineId));
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Mixed insert/remove/update transactions
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void mixedTransaction_insertsAndRemoves_netEffectApplied() {
    // Insert 3, remove 1 → net +2 totalCount
    var fixture = new Fixture();
    installSnapshot(fixture, 50, 50, 2, null);

    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(op, 10, true, true);
    fixture.manager.onPut(op, 20, true, true);
    fixture.manager.onPut(op, null, true, true);  // null insert
    fixture.manager.onRemove(op, 10, true);       // non-null remove
    fixture.manager.applyDelta(holder.getDeltas().get(fixture.engineId));

    var stats = fixture.manager.getStatistics();
    assertEquals(52, stats.totalCount());  // +3 inserts -1 remove = +2
    assertEquals(3, stats.nullCount());    // +1 null insert
  }

  // ═══════════════════════════════════════════════════════════════════════
  // CHM cache consistency across multiple delta applications
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void chmCacheReflectsLatestDeltaApplication() {
    // Verifies that successive delta applications produce consistent
    // state in the CHM cache (the authoritative in-memory state).
    var fixture = new Fixture();
    installSnapshot(fixture, 0, 0, 0, null);

    // Transaction 1: insert 10 items
    var holder1 = new HistogramDeltaHolder();
    var op1 = mockOp(holder1);
    for (int i = 0; i < 10; i++) {
      fixture.manager.onPut(op1, i, true, true);
    }
    fixture.manager.applyDelta(holder1.getDeltas().get(fixture.engineId));

    assertEquals(10, fixture.manager.getStatistics().totalCount());

    // Transaction 2: insert 5 more, remove 3
    var holder2 = new HistogramDeltaHolder();
    var op2 = mockOp(holder2);
    for (int i = 10; i < 15; i++) {
      fixture.manager.onPut(op2, i, true, true);
    }
    for (int i = 0; i < 3; i++) {
      fixture.manager.onRemove(op2, i, true);
    }
    fixture.manager.applyDelta(holder2.getDeltas().get(fixture.engineId));

    assertEquals(12, fixture.manager.getStatistics().totalCount());
    assertEquals(18, fixture.manager.getSnapshot().mutationsSinceRebalance());
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Multi-value index: HLL sketch handling in onPut
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void multiValue_onPut_updatesHllInDelta() {
    // Given a multi-value manager with an HLL sketch in the snapshot
    var fixture = new Fixture(false);
    var hll = new HyperLogLogSketch();
    var snapshot = new HistogramSnapshot(
        new IndexStatistics(100, 80, 5), null,
        0, 100, 0, false, hll, false);
    fixture.cache.put(fixture.engineId, snapshot);

    // When onPut is called (multi-value: isSingleVal=false)
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(op, 42, false, true);

    // Then the delta has an HLL sketch
    var delta = holder.getDeltas().get(fixture.engineId);
    assertNotNull(delta.hllSketch);
  }

  @Test
  public void multiValue_onPut_nullHllInSnapshot_noHllInDelta() {
    // Given a multi-value manager with no HLL sketch (small index)
    var fixture = new Fixture(false);
    var snapshot = new HistogramSnapshot(
        new IndexStatistics(10, 8, 0), null,
        0, 10, 0, false, null, false);
    fixture.cache.put(fixture.engineId, snapshot);

    // When onPut is called
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(op, 42, false, true);

    // Then no HLL in delta (snapshot has no HLL to merge with)
    var delta = holder.getDeltas().get(fixture.engineId);
    assertNull(delta.hllSketch);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Fixtures and helpers
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Creates a simple 2-bucket histogram over integer keys [0..nonNullCount).
   * Bucket 0: [0, nonNullCount/2), Bucket 1: [nonNullCount/2, nonNullCount-1].
   */
  private static EquiDepthHistogram createSimpleHistogram(long nonNullCount) {
    int half = (int) (nonNullCount / 2);
    return new EquiDepthHistogram(
        2,
        new Comparable<?>[]{0, half, (int) (nonNullCount - 1)},
        new long[]{half, nonNullCount - half},
        new long[]{half, nonNullCount - half},
        nonNullCount,
        null, 0
    );
  }

  /** Creates a mock AtomicOperation that returns the given holder. */
  private static AtomicOperation mockOp(HistogramDeltaHolder holder) {
    var op = mock(AtomicOperation.class);
    when(op.getOrCreateHistogramDeltas()).thenReturn(holder);
    return op;
  }

  /** Installs an empty snapshot into the cache for the given fixture. */
  private static void installEmptySnapshot(Fixture fixture) {
    var stats = new IndexStatistics(0, 0, 0);
    fixture.cache.put(fixture.engineId,
        new HistogramSnapshot(stats, null, 0, 0, 0, false, null, false));
  }

  /** Installs a snapshot with the given counters into the cache. */
  private static void installSnapshot(Fixture fixture, long totalCount,
      long distinctCount, long nullCount, EquiDepthHistogram histogram) {
    var stats = new IndexStatistics(totalCount, distinctCount, nullCount);
    fixture.cache.put(fixture.engineId,
        new HistogramSnapshot(
            stats, histogram, 0, totalCount, 0, false, null, false));
  }

  private static AbstractStorage createMockStorage() {
    var storage = mock(AbstractStorage.class);
    var factory = new CurrentStorageComponentsFactory(
        BinarySerializerFactory.currentBinaryFormatVersion());
    when(storage.getComponentsFactory()).thenReturn(factory);
    when(storage.getAtomicOperationsManager())
        .thenReturn(mock(AtomicOperationsManager.class));
    when(storage.getReadCache()).thenReturn(mock(ReadCache.class));
    when(storage.getWriteCache()).thenReturn(mock(WriteCache.class));
    return storage;
  }

  /**
   * Test fixture with mock storage and real CHM cache.
   *
   * @param isSingleValue true for single-value index, false for multi-value
   */
  private static class Fixture {
    final int engineId = 0;
    final ConcurrentHashMap<Integer, HistogramSnapshot> cache =
        new ConcurrentHashMap<>();
    final IndexHistogramManager manager;

    Fixture() {
      this(true);
    }

    Fixture(boolean isSingleValue) {
      var storage = createMockStorage();
      var serializerFactory = BinarySerializerFactory.create(
          BinarySerializerFactory.CURRENT_BINARY_FORMAT_VERSION);
      manager = new IndexHistogramManager(
          storage, "test-idx", engineId, isSingleValue, cache,
          IntegerSerializer.INSTANCE, serializerFactory,
          IntegerSerializer.ID);
    }
  }
}
