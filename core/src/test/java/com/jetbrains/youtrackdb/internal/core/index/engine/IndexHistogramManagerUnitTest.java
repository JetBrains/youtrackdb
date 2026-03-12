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

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.core.db.record.CurrentStorageComponentsFactory;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.After;
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

  private final java.util.Map<GlobalConfiguration, Object> overrides =
      new java.util.LinkedHashMap<>();

  @After
  public void tearDown() {
    for (var entry : overrides.entrySet()) {
      entry.getKey().setValue(entry.getValue());
    }
    overrides.clear();
  }

  private void setConfig(GlobalConfiguration key, Object value) {
    if (!overrides.containsKey(key)) {
      overrides.put(key, key.getValue());
    }
    key.setValue(value);
  }

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
    // Single-value: distinctCount excludes nulls
    assertEquals(95, fixture.manager.getStatistics().distinctCount());
  }

  @Test
  public void singleValue_distinctCountExcludesNulls_afterMixedInserts() {
    // Given a single-value manager with no nulls
    var fixture = new Fixture();
    installSnapshot(fixture, 10, 10, 0, null);

    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);

    // When 3 non-null inserts and 2 null inserts are applied
    fixture.manager.onPut(op, 1, true, true);
    fixture.manager.onPut(op, 2, true, true);
    fixture.manager.onPut(op, 3, true, true);
    fixture.manager.onPut(op, null, true, true);
    fixture.manager.onPut(op, null, true, true);
    var delta = holder.getDeltas().get(fixture.engineId);
    fixture.manager.applyDelta(delta);

    // Then totalCount = 15, nullCount = 2, distinctCount = 15 - 2 = 13
    var stats = fixture.manager.getStatistics();
    assertEquals(15, stats.totalCount());
    assertEquals(2, stats.nullCount());
    assertEquals(13, stats.distinctCount());
  }

  @Test
  public void singleValue_distinctCountExcludesNulls_afterRemovingNonNullKey() {
    // Given a single-value manager with some nulls
    var fixture = new Fixture();
    installSnapshot(fixture, 100, 90, 10, null);

    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);

    // When a non-null key is removed
    fixture.manager.onRemove(op, 42, true);
    var delta = holder.getDeltas().get(fixture.engineId);
    fixture.manager.applyDelta(delta);

    // Then totalCount = 99, nullCount = 10, distinctCount = 99 - 10 = 89
    var stats = fixture.manager.getStatistics();
    assertEquals(99, stats.totalCount());
    assertEquals(10, stats.nullCount());
    assertEquals(89, stats.distinctCount());
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
        new Comparable<?>[] {0, 50, 100},
        new long[] {50, 50},
        new long[] {50, 50},
        100,
        null, 0);
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
        new Comparable<?>[] {0, 50, 100},
        new long[] {50, 50},
        new long[] {50, 50},
        100,
        null, 0);
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
        new Comparable<?>[] {0, 50, 100},
        new long[] {50, 50},
        new long[] {50, 50},
        100,
        null, 0);
    installSnapshot(fixture, 100, 100, 0, histogram);

    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(op, 75, true, true);
    fixture.manager.onPut(op, 25, true, true);
    fixture.manager.onPut(op, 80, true, true);
    fixture.manager.applyDelta(holder.getDeltas().get(fixture.engineId));

    var h = fixture.manager.getHistogram();
    assertNotNull(h);
    assertEquals(51, h.frequencies()[0]); // +1 (key=25)
    assertEquals(52, h.frequencies()[1]); // +2 (key=75, 80)
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
    fixture.manager.onPut(op, null, true, true); // null insert
    fixture.manager.onRemove(op, 10, true); // non-null remove
    fixture.manager.applyDelta(holder.getDeltas().get(fixture.engineId));

    var stats = fixture.manager.getStatistics();
    assertEquals(52, stats.totalCount()); // +3 inserts -1 remove = +2
    assertEquals(3, stats.nullCount()); // +1 null insert
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
  // maybeScheduleHistogramWork — scheduling decisions
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void maybeScheduleHistogramWork_nullExecutor_isNoOp() {
    // Given a manager with a snapshot that would trigger initial build
    var fixture = new Fixture();
    var stats = new IndexStatistics(5000, 5000, 0);
    fixture.cache.put(fixture.engineId,
        new HistogramSnapshot(stats, null, 0, 0, 0, false, null, false));

    // When called with null executor — no exception, no side effects
    fixture.manager.maybeScheduleHistogramWork(null);

    // Then rebalance is not in progress
    assertFalse(fixture.manager.isRebalanceInProgress());
  }

  @Test
  public void maybeScheduleHistogramWork_noCacheEntry_isNoOp() {
    // Given a manager with NO cache entry
    var fixture = new Fixture();

    // When called with a real executor — no exception
    var executor = new CapturingExecutor();
    fixture.manager.maybeScheduleHistogramWork(executor);

    // Then no task submitted
    assertTrue(executor.submitted.isEmpty());
  }

  @Test
  public void maybeScheduleHistogramWork_belowMinSize_doesNotSchedule() {
    // Given minSize = 1000 (default) and index has 500 non-null entries
    var fixture = new Fixture();
    var stats = new IndexStatistics(500, 500, 0);
    fixture.cache.put(fixture.engineId,
        new HistogramSnapshot(stats, null, 0, 0, 0, false, null, false));
    fixture.manager.setFileIdForTest(1);
    fixture.manager.setKeyStreamSupplier(
        () -> IntStream.range(0, 500).boxed().map(i -> (Object) i));

    var executor = new CapturingExecutor();
    fixture.manager.maybeScheduleHistogramWork(executor);

    // Then no task submitted (500 < 1000 default min size)
    assertTrue(executor.submitted.isEmpty());
  }

  @Test
  public void maybeScheduleHistogramWork_noHistogramNoLastBuild_schedulesInitialBuild() {
    // Given an index above min size with no histogram and no previous build
    setConfig(GlobalConfiguration.QUERY_STATS_HISTOGRAM_MIN_SIZE, 10);
    var fixture = new Fixture();
    var stats = new IndexStatistics(5000, 5000, 0);
    fixture.cache.put(fixture.engineId,
        new HistogramSnapshot(stats, null, 0, 0, 0, false, null, false));
    fixture.manager.setFileIdForTest(1);
    fixture.manager.setKeyStreamSupplier(
        () -> IntStream.range(0, 5000).boxed().map(i -> (Object) i));

    var executor = new CapturingExecutor();
    fixture.manager.maybeScheduleHistogramWork(executor);

    // Then a task is submitted for initial build
    assertFalse("Expected initial build task submitted",
        executor.submitted.isEmpty());
  }

  @Test
  public void maybeScheduleHistogramWork_mutationsExceedThreshold_schedulesRebalance() {
    // Given a histogram with mutations above the rebalance threshold
    setConfig(GlobalConfiguration.QUERY_STATS_HISTOGRAM_MIN_SIZE, 10);
    setConfig(
        GlobalConfiguration.QUERY_STATS_REBALANCE_MUTATION_FRACTION, 0.1);
    setConfig(
        GlobalConfiguration.QUERY_STATS_MIN_REBALANCE_MUTATIONS, 100L);
    setConfig(
        GlobalConfiguration.QUERY_STATS_MAX_REBALANCE_MUTATIONS,
        1_000_000L);

    var fixture = new Fixture();
    var histogram = createSimpleHistogram(10_000);
    var stats = new IndexStatistics(10_000, 10_000, 0);
    // threshold = 10,000 * 0.1 = 1,000; mutationsSinceRebalance = 1,500
    fixture.cache.put(fixture.engineId,
        new HistogramSnapshot(
            stats, histogram, 1500, 10_000, 0, false, null, false));
    fixture.manager.setFileIdForTest(1);
    fixture.manager.setKeyStreamSupplier(
        () -> IntStream.range(0, 10_000).boxed().map(i -> (Object) i));

    var executor = new CapturingExecutor();
    fixture.manager.maybeScheduleHistogramWork(executor);

    // Then rebalance is scheduled (1500 > 1000)
    assertFalse("Expected rebalance task submitted",
        executor.submitted.isEmpty());
  }

  @Test
  public void maybeScheduleHistogramWork_mutationsBelowThreshold_doesNotSchedule() {
    // Given a histogram with mutations below the rebalance threshold
    setConfig(GlobalConfiguration.QUERY_STATS_HISTOGRAM_MIN_SIZE, 10);
    setConfig(
        GlobalConfiguration.QUERY_STATS_REBALANCE_MUTATION_FRACTION, 0.1);
    setConfig(
        GlobalConfiguration.QUERY_STATS_MIN_REBALANCE_MUTATIONS, 100L);
    setConfig(
        GlobalConfiguration.QUERY_STATS_MAX_REBALANCE_MUTATIONS,
        1_000_000L);

    var fixture = new Fixture();
    var histogram = createSimpleHistogram(10_000);
    var stats = new IndexStatistics(10_000, 10_000, 0);
    // threshold = 10,000 * 0.1 = 1,000; mutationsSinceRebalance = 500
    fixture.cache.put(fixture.engineId,
        new HistogramSnapshot(
            stats, histogram, 500, 10_000, 0, false, null, false));
    fixture.manager.setFileIdForTest(1);
    fixture.manager.setKeyStreamSupplier(
        () -> IntStream.range(0, 10_000).boxed().map(i -> (Object) i));

    var executor = new CapturingExecutor();
    fixture.manager.maybeScheduleHistogramWork(executor);

    // Then no task submitted (500 < 1000)
    assertTrue(executor.submitted.isEmpty());
  }

  // ═══════════════════════════════════════════════════════════════════════
  // scheduleRebalance — cooldown and CAS guard
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void scheduleRebalance_recentFailure_skippedByCooldown() {
    // Given a manager with a recent failure timestamp and a snapshot
    // that would trigger rebalance
    setConfig(GlobalConfiguration.QUERY_STATS_HISTOGRAM_MIN_SIZE, 10);
    setConfig(
        GlobalConfiguration.QUERY_STATS_REBALANCE_MUTATION_FRACTION, 0.1);
    setConfig(
        GlobalConfiguration.QUERY_STATS_MIN_REBALANCE_MUTATIONS, 100L);
    setConfig(
        GlobalConfiguration.QUERY_STATS_MAX_REBALANCE_MUTATIONS,
        1_000_000L);
    // Set a very long cooldown so it's always active
    setConfig(
        GlobalConfiguration.QUERY_STATS_REBALANCE_FAILURE_COOLDOWN,
        600_000L);

    var fixture = new Fixture();
    var histogram = createSimpleHistogram(10_000);
    var stats = new IndexStatistics(10_000, 10_000, 0);
    // mutations above threshold
    fixture.cache.put(fixture.engineId,
        new HistogramSnapshot(
            stats, histogram, 2000, 10_000, 0, false, null, false));
    fixture.manager.setFileIdForTest(1);
    fixture.manager.setKeyStreamSupplier(
        () -> IntStream.range(0, 10_000).boxed().map(i -> (Object) i));
    // Set failure time to "just now"
    fixture.manager.setLastRebalanceFailureNanos(System.nanoTime());

    var executor = new CapturingExecutor();
    fixture.manager.maybeScheduleHistogramWork(executor);

    // Then no task submitted due to cooldown
    assertTrue("Expected no scheduling during cooldown period",
        executor.submitted.isEmpty());
  }

  @Test
  public void scheduleRebalance_alreadyInProgress_skippedByCas() {
    // Given a manager where rebalanceInProgress is already true
    setConfig(GlobalConfiguration.QUERY_STATS_HISTOGRAM_MIN_SIZE, 10);
    setConfig(
        GlobalConfiguration.QUERY_STATS_REBALANCE_MUTATION_FRACTION, 0.1);
    setConfig(
        GlobalConfiguration.QUERY_STATS_MIN_REBALANCE_MUTATIONS, 100L);

    var fixture = new Fixture();
    var histogram = createSimpleHistogram(10_000);
    var stats = new IndexStatistics(10_000, 10_000, 0);
    fixture.cache.put(fixture.engineId,
        new HistogramSnapshot(
            stats, histogram, 2000, 10_000, 0, false, null, false));
    fixture.manager.setFileIdForTest(1);
    fixture.manager.setKeyStreamSupplier(
        () -> IntStream.range(0, 10_000).boxed().map(i -> (Object) i));

    // First call claims the CAS
    var executor1 = new CapturingExecutor();
    fixture.manager.maybeScheduleHistogramWork(executor1);
    assertFalse(executor1.submitted.isEmpty());

    // rebalanceInProgress is now true (task was captured, not executed)
    assertTrue(fixture.manager.isRebalanceInProgress());

    // Second call should be skipped by CAS guard
    var executor2 = new CapturingExecutor();
    fixture.manager.maybeScheduleHistogramWork(executor2);
    assertTrue("Expected second call skipped by CAS guard",
        executor2.submitted.isEmpty());
  }

  @Test
  public void scheduleRebalance_rejectedExecution_resetsCasFlag() {
    // Given a shut-down executor that rejects tasks
    setConfig(GlobalConfiguration.QUERY_STATS_HISTOGRAM_MIN_SIZE, 10);
    setConfig(
        GlobalConfiguration.QUERY_STATS_REBALANCE_MUTATION_FRACTION, 0.1);
    setConfig(
        GlobalConfiguration.QUERY_STATS_MIN_REBALANCE_MUTATIONS, 100L);

    var fixture = new Fixture();
    var histogram = createSimpleHistogram(10_000);
    var stats = new IndexStatistics(10_000, 10_000, 0);
    fixture.cache.put(fixture.engineId,
        new HistogramSnapshot(
            stats, histogram, 2000, 10_000, 0, false, null, false));
    fixture.manager.setFileIdForTest(1);
    fixture.manager.setKeyStreamSupplier(
        () -> IntStream.range(0, 10_000).boxed().map(i -> (Object) i));

    // Create and immediately shut down an executor
    var executor = Executors.newSingleThreadExecutor();
    executor.shutdown();

    fixture.manager.maybeScheduleHistogramWork(executor);

    // Then CAS flag is reset (not stuck at true)
    assertFalse("Expected CAS flag reset after RejectedExecutionException",
        fixture.manager.isRebalanceInProgress());
  }

  // ═══════════════════════════════════════════════════════════════════════
  // computeRebalanceThreshold — drift bias (tested via
  // maybeScheduleHistogramWork)
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void rebalanceThreshold_driftedBuckets_halvesThreshold() {
    // Given hasDriftedBuckets=true, the threshold is halved.
    // Normal threshold for 10,000 entries at 0.1 fraction = 1,000.
    // Halved = 500.
    setConfig(GlobalConfiguration.QUERY_STATS_HISTOGRAM_MIN_SIZE, 10);
    setConfig(
        GlobalConfiguration.QUERY_STATS_REBALANCE_MUTATION_FRACTION, 0.1);
    setConfig(
        GlobalConfiguration.QUERY_STATS_MIN_REBALANCE_MUTATIONS, 100L);
    setConfig(
        GlobalConfiguration.QUERY_STATS_MAX_REBALANCE_MUTATIONS,
        1_000_000L);

    var fixture = new Fixture();
    var histogram = createSimpleHistogram(10_000);
    var stats = new IndexStatistics(10_000, 10_000, 0);

    // With 700 mutations and hasDriftedBuckets=true:
    // normal threshold = 1000, halved = 500 → 700 > 500 → schedules
    fixture.cache.put(fixture.engineId,
        new HistogramSnapshot(
            stats, histogram, 700, 10_000, 0, true, null, false));
    fixture.manager.setFileIdForTest(1);
    fixture.manager.setKeyStreamSupplier(
        () -> IntStream.range(0, 10_000).boxed().map(i -> (Object) i));

    var executor = new CapturingExecutor();
    fixture.manager.maybeScheduleHistogramWork(executor);

    // Then rebalance is scheduled (700 > 500 halved threshold)
    assertFalse("Expected rebalance with drifted buckets halving "
        + "threshold", executor.submitted.isEmpty());
  }

  @Test
  public void rebalanceThreshold_clampedToMax_whenFractionExceedsMax() {
    // Large index: 100M entries * 0.1 fraction = 10M, but max = 1000.
    // So threshold = 1000. With 1500 mutations → should schedule.
    setConfig(GlobalConfiguration.QUERY_STATS_HISTOGRAM_MIN_SIZE, 10);
    setConfig(
        GlobalConfiguration.QUERY_STATS_REBALANCE_MUTATION_FRACTION, 0.1);
    setConfig(
        GlobalConfiguration.QUERY_STATS_MIN_REBALANCE_MUTATIONS, 100L);
    setConfig(
        GlobalConfiguration.QUERY_STATS_MAX_REBALANCE_MUTATIONS, 1000L);

    var fixture = new Fixture();
    var histogram = createSimpleHistogram(10_000);
    var stats = new IndexStatistics(10_000, 10_000, 0);
    // totalCountAtLastBuild=100M, mutations=1500 > max-clamped 1000
    fixture.cache.put(fixture.engineId,
        new HistogramSnapshot(
            stats, histogram, 1500, 100_000_000L, 0, false, null, false));
    fixture.manager.setFileIdForTest(1);
    fixture.manager.setKeyStreamSupplier(
        () -> IntStream.range(0, 10_000).boxed().map(i -> (Object) i));

    var executor = new CapturingExecutor();
    fixture.manager.maybeScheduleHistogramWork(executor);

    assertFalse("Expected rebalance: 1500 > max-clamped threshold 1000",
        executor.submitted.isEmpty());
  }

  @Test
  public void rebalanceThreshold_clampedToMin_whenFractionBelowMin() {
    // Small index: 100 entries * 0.1 = 10, but min = 100.
    // So threshold = 100. With 50 mutations → should NOT schedule.
    setConfig(GlobalConfiguration.QUERY_STATS_HISTOGRAM_MIN_SIZE, 10);
    setConfig(
        GlobalConfiguration.QUERY_STATS_REBALANCE_MUTATION_FRACTION, 0.1);
    setConfig(
        GlobalConfiguration.QUERY_STATS_MIN_REBALANCE_MUTATIONS, 100L);
    setConfig(
        GlobalConfiguration.QUERY_STATS_MAX_REBALANCE_MUTATIONS,
        1_000_000L);

    var fixture = new Fixture();
    var histogram = createSimpleHistogram(100);
    var stats = new IndexStatistics(100, 100, 0);
    // totalCountAtLastBuild=100, mutations=50 < min-clamped 100
    fixture.cache.put(fixture.engineId,
        new HistogramSnapshot(
            stats, histogram, 50, 100, 0, false, null, false));
    fixture.manager.setFileIdForTest(1);
    fixture.manager.setKeyStreamSupplier(
        () -> IntStream.range(0, 100).boxed().map(i -> (Object) i));

    var executor = new CapturingExecutor();
    fixture.manager.maybeScheduleHistogramWork(executor);

    assertTrue("Expected no rebalance: 50 < min-clamped threshold 100",
        executor.submitted.isEmpty());
  }

  @Test
  public void maybeScheduleHistogramWork_freshlyRebalanced_doesNotSchedule() {
    // Steady state: histogram exists, mutations = 0, should not schedule
    setConfig(GlobalConfiguration.QUERY_STATS_HISTOGRAM_MIN_SIZE, 10);
    setConfig(
        GlobalConfiguration.QUERY_STATS_REBALANCE_MUTATION_FRACTION, 0.1);
    setConfig(
        GlobalConfiguration.QUERY_STATS_MIN_REBALANCE_MUTATIONS, 100L);

    var fixture = new Fixture();
    var histogram = createSimpleHistogram(10_000);
    var stats = new IndexStatistics(10_000, 10_000, 0);
    // mutationsSinceRebalance = 0 (freshly rebalanced)
    fixture.cache.put(fixture.engineId,
        new HistogramSnapshot(
            stats, histogram, 0, 10_000, 1, false, null, false));
    fixture.manager.setFileIdForTest(1);

    var executor = new CapturingExecutor();
    fixture.manager.maybeScheduleHistogramWork(executor);

    assertTrue("Freshly rebalanced should not schedule",
        executor.submitted.isEmpty());
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
        new Comparable<?>[] {0, half, (int) (nonNullCount - 1)},
        new long[] {half, nonNullCount - half},
        new long[] {half, nonNullCount - half},
        nonNullCount,
        null, 0);
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
   * ExecutorService that captures submitted tasks without executing them.
   * Used to verify whether scheduling logic submits a task.
   */
  private static class CapturingExecutor implements ExecutorService {
    final List<Runnable> submitted = new ArrayList<>();

    @Override
    public void execute(Runnable command) {
      submitted.add(command);
    }

    @Override
    public void shutdown() {
    }

    @Override
    public List<Runnable> shutdownNow() {
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return false;
    }

    @Override
    public boolean isTerminated() {
      return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return true;
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
      submitted.add(task);
      return CompletableFuture.completedFuture(result);
    }

    @Override
    public Future<?> submit(Runnable task) {
      submitted.add(task);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public <T> List<Future<T>> invokeAll(
        Collection<? extends Callable<T>> tasks) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> List<Future<T>> invokeAll(
        Collection<? extends Callable<T>> tasks,
        long timeout, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> T invokeAny(
        Collection<? extends Callable<T>> tasks) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> T invokeAny(
        Collection<? extends Callable<T>> tasks,
        long timeout, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  // computeNewSnapshot — clamping and version mismatch
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void computeNewSnapshot_clampsNegativeTotalToZero() {
    // Given a snapshot with totalCount=100, nullCount=50
    var stats = new IndexStatistics(100, 50, 50);
    var snapshot = new HistogramSnapshot(
        stats, null, 0, 100, 0, false, null, false);

    // When delta has totalCountDelta=-200 (would make totalCount=-100)
    var delta = new HistogramDelta();
    delta.totalCountDelta = -200;
    delta.nullCountDelta = -200;
    delta.mutationCount = 1;

    var result = IndexHistogramManager.computeNewSnapshot(snapshot, delta);

    // Then both are clamped to 0
    assertEquals(0, result.stats().totalCount());
    assertEquals(0, result.stats().nullCount());
  }

  @Test
  public void computeNewSnapshot_versionMismatch_discardsFrequencyDeltas() {
    // Given a snapshot with version=5 and a 2-bucket histogram
    var histogram = new EquiDepthHistogram(
        2,
        new Comparable<?>[] {0, 50, 100},
        new long[] {50, 50},
        new long[] {50, 50},
        100,
        null, 0);
    var stats = new IndexStatistics(100, 100, 0);
    var snapshot = new HistogramSnapshot(
        stats, histogram, 0, 100, 5, false, null, false);

    // When delta has snapshotVersion=3 and non-null frequencyDeltas
    var delta = new HistogramDelta();
    delta.totalCountDelta = 1;
    delta.mutationCount = 1;
    delta.frequencyDeltas = new int[] {10, 20};
    delta.snapshotVersion = 3;

    var result = IndexHistogramManager.computeNewSnapshot(snapshot, delta);

    // Then histogram frequencies are NOT updated (version mismatch)
    assertNotNull(result.histogram());
    assertEquals(50, result.histogram().frequencies()[0]);
    assertEquals(50, result.histogram().frequencies()[1]);
  }

  @Test
  public void computeNewSnapshot_accumulatesMutationsCorrectly() {
    // Given a snapshot with mutationsSinceRebalance=50
    var stats = new IndexStatistics(100, 100, 0);
    var snapshot = new HistogramSnapshot(
        stats, null, 50, 100, 0, false, null, false);

    // When delta has mutationCount=7
    var delta = new HistogramDelta();
    delta.mutationCount = 7;

    var result = IndexHistogramManager.computeNewSnapshot(snapshot, delta);

    // Then mutationsSinceRebalance = 50 + 7 = 57
    assertEquals(57, result.mutationsSinceRebalance());
  }

  // ═══════════════════════════════════════════════════════════════════════
  // onPut — frequency delta initialization guards
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void onPut_wasInsertFalse_doesNotInitializeFrequencyDeltas() {
    // Given a manager with a histogram
    var fixture = new Fixture();
    var histogram = createSimpleHistogram(200);
    installSnapshot(fixture, 200, 200, 0, histogram);

    // When onPut is called with wasInsert=false
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(op, 42, true, false);

    // Then delta's frequencyDeltas is null (update path returns early)
    var delta = holder.getDeltas().get(fixture.engineId);
    assertNull(delta.frequencyDeltas);
  }

  @Test
  public void onPut_nullKey_doesNotInitializeFrequencyDeltas() {
    // Given a manager with a histogram
    var fixture = new Fixture();
    var histogram = createSimpleHistogram(200);
    installSnapshot(fixture, 200, 200, 0, histogram);

    // When onPut is called with null key (wasInsert=true)
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(op, null, true, true);

    // Then delta's frequencyDeltas is null (null key goes to nullCount)
    var delta = holder.getDeltas().get(fixture.engineId);
    assertNull(delta.frequencyDeltas);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Multi-value HLL — insert-only semantics
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void multiValue_onRemove_doesNotUpdateHll() {
    // Given a multi-value manager with an HLL sketch in the snapshot
    var fixture = new Fixture(false);
    var hll = new HyperLogLogSketch();
    var snapshot = new HistogramSnapshot(
        new IndexStatistics(100, 80, 5), null,
        0, 100, 0, false, hll, false);
    fixture.cache.put(fixture.engineId, snapshot);

    // When onRemove is called (multi-value)
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onRemove(op, 42, false);

    // Then delta has no HLL sketch (HLL is insert-only)
    var delta = holder.getDeltas().get(fixture.engineId);
    assertNull(delta.hllSketch);
  }

  @Test
  public void singleValue_onPut_doesNotCreateHllSketch() {
    // Given a single-value manager with HLL in snapshot (unusual but
    // tests the isSingleVal guard in onPut)
    var fixture = new Fixture(true);
    var hll = new HyperLogLogSketch();
    var snapshot = new HistogramSnapshot(
        new IndexStatistics(100, 100, 0), null,
        0, 100, 0, false, hll, false);
    fixture.cache.put(fixture.engineId, snapshot);

    // When onPut is called with isSingleVal=true
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(op, 42, true, true);

    // Then delta has no HLL sketch (single-value skips HLL)
    var delta = holder.getDeltas().get(fixture.engineId);
    assertNull(delta.hllSketch);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // computeRebalanceThreshold — min/max clamping and drift halving
  // (tested directly via computeNewSnapshot + maybeScheduleHistogramWork)
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void computeRebalanceThreshold_clampsToMax() {
    // Given totalCountAtLastBuild=1000, fraction=0.5 → raw=500, max=200
    // Expected: min(500, 200) = 200. With 250 mutations → should schedule.
    setConfig(GlobalConfiguration.QUERY_STATS_HISTOGRAM_MIN_SIZE, 10);
    setConfig(
        GlobalConfiguration.QUERY_STATS_REBALANCE_MUTATION_FRACTION, 0.5);
    setConfig(
        GlobalConfiguration.QUERY_STATS_MIN_REBALANCE_MUTATIONS, 10L);
    setConfig(
        GlobalConfiguration.QUERY_STATS_MAX_REBALANCE_MUTATIONS, 200L);

    var fixture = new Fixture();
    var histogram = createSimpleHistogram(1000);
    var stats = new IndexStatistics(1000, 1000, 0);
    // 250 mutations > max-clamped 200 → should schedule
    fixture.cache.put(fixture.engineId,
        new HistogramSnapshot(
            stats, histogram, 250, 1000, 0, false, null, false));
    fixture.manager.setFileIdForTest(1);
    fixture.manager.setKeyStreamSupplier(
        () -> IntStream.range(0, 1000).boxed().map(i -> (Object) i));

    var executor = new CapturingExecutor();
    fixture.manager.maybeScheduleHistogramWork(executor);

    assertFalse("Expected rebalance: 250 > max-clamped 200",
        executor.submitted.isEmpty());
  }

  @Test
  public void computeRebalanceThreshold_clampsToMin() {
    // Given totalCountAtLastBuild=100, fraction=0.5 → raw=50, min=300
    // Expected: max(50, 300) = 300. With 200 mutations → should NOT schedule.
    setConfig(GlobalConfiguration.QUERY_STATS_HISTOGRAM_MIN_SIZE, 10);
    setConfig(
        GlobalConfiguration.QUERY_STATS_REBALANCE_MUTATION_FRACTION, 0.5);
    setConfig(
        GlobalConfiguration.QUERY_STATS_MIN_REBALANCE_MUTATIONS, 300L);
    setConfig(
        GlobalConfiguration.QUERY_STATS_MAX_REBALANCE_MUTATIONS, 1000L);

    var fixture = new Fixture();
    var histogram = createSimpleHistogram(100);
    var stats = new IndexStatistics(100, 100, 0);
    // 200 mutations < min-clamped 300 → should NOT schedule
    fixture.cache.put(fixture.engineId,
        new HistogramSnapshot(
            stats, histogram, 200, 100, 0, false, null, false));
    fixture.manager.setFileIdForTest(1);
    fixture.manager.setKeyStreamSupplier(
        () -> IntStream.range(0, 100).boxed().map(i -> (Object) i));

    var executor = new CapturingExecutor();
    fixture.manager.maybeScheduleHistogramWork(executor);

    assertTrue("Expected no rebalance: 200 < min-clamped 300",
        executor.submitted.isEmpty());
  }

  @Test
  public void computeRebalanceThreshold_halvesWhenDrifted() {
    // Given totalCountAtLastBuild=10000, fraction=0.1 → raw=1000.
    // With hasDriftedBuckets=true → threshold=500.
    // 600 mutations > 500 → should schedule.
    setConfig(GlobalConfiguration.QUERY_STATS_HISTOGRAM_MIN_SIZE, 10);
    setConfig(
        GlobalConfiguration.QUERY_STATS_REBALANCE_MUTATION_FRACTION, 0.1);
    setConfig(
        GlobalConfiguration.QUERY_STATS_MIN_REBALANCE_MUTATIONS, 100L);
    setConfig(
        GlobalConfiguration.QUERY_STATS_MAX_REBALANCE_MUTATIONS,
        1_000_000L);

    var fixture = new Fixture();
    var histogram = createSimpleHistogram(10_000);
    var stats = new IndexStatistics(10_000, 10_000, 0);
    // 600 > 500 (halved from 1000) → should schedule
    fixture.cache.put(fixture.engineId,
        new HistogramSnapshot(
            stats, histogram, 600, 10_000, 0, true, null, false));
    fixture.manager.setFileIdForTest(1);
    fixture.manager.setKeyStreamSupplier(
        () -> IntStream.range(0, 10_000).boxed().map(i -> (Object) i));

    var executor = new CapturingExecutor();
    fixture.manager.maybeScheduleHistogramWork(executor);

    assertFalse("Expected rebalance with drift halving: 600 > 500",
        executor.submitted.isEmpty());

    // But 400 mutations should NOT trigger (400 < 500)
    fixture.cache.put(fixture.engineId,
        new HistogramSnapshot(
            stats, histogram, 400, 10_000, 0, true, null, false));
    // Reset CAS guard from the previous scheduling
    fixture.manager.resetRebalanceInProgressForTest();

    var executor2 = new CapturingExecutor();
    fixture.manager.maybeScheduleHistogramWork(executor2);
    assertTrue("Expected no rebalance: 400 < halved threshold 500",
        executor2.submitted.isEmpty());
  }

  // ═══════════════════════════════════════════════════════════════════════
  // truncateBoundary — UTF-8 string truncation
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void truncateStringUtf8_preservesAsciiStringUnderLimit() {
    // "hello" is 5 bytes UTF-8 + 2 byte header = 7 bytes.
    // With maxBytes=100 it should be returned unchanged.
    @SuppressWarnings("unchecked")
    var serializer =
        (com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer<
            Object>) (com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer<
                ?>) com.jetbrains.youtrackdb.internal.common.serialization.types.UTF8Serializer.INSTANCE;
    var factory = BinarySerializerFactory.create(
        BinarySerializerFactory.CURRENT_BINARY_FORMAT_VERSION);

    var result = IndexHistogramManager.truncateBoundary(
        "hello", 100, serializer, factory);
    assertEquals("hello", result);
  }

  @Test
  public void truncateStringUtf8_truncatesLongAsciiString() {
    // "abcdefghijklmnop" is 16 chars, each 1 byte UTF-8.
    // UTF8Serializer header is 2 bytes (short). maxBytes=10 →
    // maxPayload = 10 - 2 = 8 bytes → truncated to 8 chars.
    @SuppressWarnings("unchecked")
    var serializer =
        (com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer<
            Object>) (com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer<
                ?>) com.jetbrains.youtrackdb.internal.common.serialization.types.UTF8Serializer.INSTANCE;
    var factory = BinarySerializerFactory.create(
        BinarySerializerFactory.CURRENT_BINARY_FORMAT_VERSION);

    var result = IndexHistogramManager.truncateBoundary(
        "abcdefghijklmnop", 10, serializer, factory);
    assertEquals("abcdefgh", result);
  }

  @Test
  public void truncateStringUtf8_doesNotSplitMultiByteCharacter() {
    // 'é' (U+00E9) is 2 bytes in UTF-8. A string of 5 'é' chars =
    // 10 bytes UTF-8. With maxBytes=7 (header 2 + payload 5),
    // we can fit only 2 chars (4 bytes) — not 2.5.
    @SuppressWarnings("unchecked")
    var serializer =
        (com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer<
            Object>) (com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer<
                ?>) com.jetbrains.youtrackdb.internal.common.serialization.types.UTF8Serializer.INSTANCE;
    var factory = BinarySerializerFactory.create(
        BinarySerializerFactory.CURRENT_BINARY_FORMAT_VERSION);

    var input = "\u00e9\u00e9\u00e9\u00e9\u00e9"; // 5 × 'é'
    var result = IndexHistogramManager.truncateBoundary(
        input, 7, serializer, factory);
    // maxPayload = 7 - 2 = 5 bytes. Each 'é' is 2 bytes.
    // 2 chars = 4 bytes ≤ 5, 3 chars = 6 bytes > 5 → truncated to 2
    assertEquals("\u00e9\u00e9", result);
  }

  @Test
  public void truncateStringUtf8_handlesSurrogatePairs() {
    // Emoji U+1F600 (grinning face) is 4 bytes in UTF-8 and represented
    // as a surrogate pair (2 chars) in Java. Test that truncation does
    // not split the surrogate pair.
    @SuppressWarnings("unchecked")
    var serializer =
        (com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer<
            Object>) (com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer<
                ?>) com.jetbrains.youtrackdb.internal.common.serialization.types.UTF8Serializer.INSTANCE;
    var factory = BinarySerializerFactory.create(
        BinarySerializerFactory.CURRENT_BINARY_FORMAT_VERSION);

    var emoji = "\uD83D\uDE00"; // U+1F600
    var input = emoji + emoji + emoji; // 3 emojis = 12 UTF-8 bytes
    // maxBytes=8: header 2 + payload 6. Each emoji is 4 bytes.
    // 1 emoji = 4 ≤ 6, 2 emojis = 8 > 6 → truncated to 1 emoji
    var result = IndexHistogramManager.truncateBoundary(
        input, 8, serializer, factory);
    assertEquals(emoji, result);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // fitToPage — MINIMUM_BUCKET_COUNT guard
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void fitToPage_respectsMinimumBucketCount() {
    // Create a BuildResult with 8 buckets where boundaries are very large
    // so the page budget forces reduction. Verify it stops at 4 (minimum).
    int buckets = 8;
    var boundaries = new Comparable<?>[buckets + 1];
    var frequencies = new long[buckets];
    var distinctCounts = new long[buckets];
    for (int i = 0; i <= buckets; i++) {
      boundaries[i] = i;
    }
    for (int i = 0; i < buckets; i++) {
      frequencies[i] = 100;
      distinctCounts[i] = 100;
    }

    var result = new IndexHistogramManager.BuildResult(
        boundaries, frequencies, distinctCounts, buckets, 800, null, 0);

    // Use a BoundarySizeCalculator that always returns a huge size
    // so that the loop keeps reducing bucket count.
    IndexHistogramManager.BoundarySizeCalculator hugeSize =
        (b, count) -> Integer.MAX_VALUE;

    // Single-value (no HLL), mcvKeySize=0
    var fitResult = IndexHistogramManager.fitToPage(
        result, 800, true, 0, hugeSize);

    // The while loop exits when bucketCount reaches MINIMUM_BUCKET_COUNT (4).
    // Even though boundaries don't fit, the histogram is returned with 4
    // buckets — fitToPage does not return null for single-value when the
    // minimum is reached.
    assertNotNull(fitResult);
    assertEquals(IndexHistogramManager.MINIMUM_BUCKET_COUNT,
        fitResult.histogram().bucketCount());
  }

  @Test
  public void fitToPage_stopsReducingAtMinimumBucketCount() {
    // Verify that when starting from 16 buckets and boundaries are too
    // large for all counts above 4, the result has exactly 4 buckets.
    int buckets = 16;
    var boundaries = new Comparable<?>[buckets + 1];
    var frequencies = new long[buckets];
    var distinctCounts = new long[buckets];
    for (int i = 0; i <= buckets; i++) {
      boundaries[i] = i;
    }
    for (int i = 0; i < buckets; i++) {
      frequencies[i] = 10;
      distinctCounts[i] = 10;
    }

    var result = new IndexHistogramManager.BuildResult(
        boundaries, frequencies, distinctCounts, buckets, 160, null, 0);

    // Returns huge except when bucketCount <= 4
    IndexHistogramManager.BoundarySizeCalculator selectiveSize =
        (b, count) -> count <= 4 ? 0 : Integer.MAX_VALUE;

    var fitResult = IndexHistogramManager.fitToPage(
        result, 160, true, 0, selectiveSize);

    assertNotNull(fitResult);
    assertEquals(4, fitResult.histogram().bucketCount());
  }

  // ═══════════════════════════════════════════════════════════════════════
  // scanAndBuild — MCV tie-breaking and exact bucket boundaries
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void scanAndBuild_mcvTieBreaking_firstKeyWins() {
    // Two keys with the same frequency: key 1 appears 3 times, then
    // key 2 appears 3 times. MCV should be key 1 (first seen, uses
    // strict > comparison).
    var keys = java.util.stream.Stream.<Object>of(
        1, 1, 1, 2, 2, 2, 3);

    var result = IndexHistogramManager.scanAndBuild(keys, 7, 4);

    assertNotNull(result);
    // key 1 has run length 3, key 2 has run length 3. Since the
    // comparator uses strict > (not >=), key 1 wins (it was first).
    assertEquals(1, result.mcvValue);
    assertEquals(3, result.mcvFrequency);
  }

  @Test
  public void scanAndBuild_exactBucketBoundary_with100Entries4Buckets() {
    // 100 entries [0..99], 4 buckets → ~25 per bucket
    var keys = IntStream.range(0, 100).boxed().map(i -> (Object) i);

    var result = IndexHistogramManager.scanAndBuild(keys, 100, 4);

    assertNotNull(result);
    // Verify we got the expected bucket count
    assertTrue("Expected at most 4 buckets",
        result.actualBucketCount <= 4);
    assertTrue("Expected at least 1 bucket",
        result.actualBucketCount >= 1);

    // Sum of frequencies should equal 100
    long totalFreq = 0;
    for (int i = 0; i < result.actualBucketCount; i++) {
      totalFreq += result.frequencies[i];
    }
    assertEquals(100, totalFreq);

    // Boundaries array should have actualBucketCount+1 entries
    // and the first boundary should be 0, the last should be 99
    assertEquals(0, result.boundaries[0]);
    assertEquals(99, result.boundaries[result.actualBucketCount]);

    // totalDistinct should be 100 (all unique)
    assertEquals(100, result.totalDistinct);
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
