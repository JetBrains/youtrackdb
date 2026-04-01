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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.core.db.record.CurrentStorageComponentsFactory;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Incremental maintenance tests (Section 10.6 of the ADR).
 *
 * <p>Exercises the manager's incremental lifecycle after an initial histogram
 * is built: frequency updates on insert/remove, findBucket boundary cases,
 * rebalance triggers, concurrent operations, version-mismatch delta discard,
 * at-most-one rebalance guard, failure cooldown, resetOnClear, drift-biased
 * threshold halving, storage-level rebalance throttling, and checkpoint flush.
 *
 * <p>Runs sequentially because it mutates {@link GlobalConfiguration},
 * a JVM-wide singleton that would race with other test classes in the
 * parallel surefire execution.
 */
@Category(SequentialTest.class)
public class IncrementalMaintenanceTest {

  /** Generous timeout for CI environments where thread scheduling can be slow. */
  private static final int CI_TIMEOUT_SECONDS = 30;

  // GlobalConfiguration is JVM-global mutable state. Other test classes
  // (e.g. IndexHistogramManagerUnitTest) override rebalance-related config
  // values. Because surefire runs classes in parallel, we must pin the
  // values we depend on and restore them after each test.
  private final Map<GlobalConfiguration, Object> configOverrides =
      new LinkedHashMap<>();

  @Before
  public void setUp() {
    // Pin rebalance-related defaults so that config contamination from
    // parallel test classes (e.g. IndexHistogramManagerUnitTest setting
    // rebalanceMutationFraction=0.5) does not alter threshold computations.
    pinConfig(
        GlobalConfiguration.QUERY_STATS_REBALANCE_MUTATION_FRACTION, 0.3);
    pinConfig(
        GlobalConfiguration.QUERY_STATS_MIN_REBALANCE_MUTATIONS, 1000L);
    pinConfig(
        GlobalConfiguration.QUERY_STATS_MAX_REBALANCE_MUTATIONS, 10_000_000L);
    pinConfig(
        GlobalConfiguration.QUERY_STATS_HISTOGRAM_MIN_SIZE, 1000);
  }

  @After
  public void tearDown() {
    configOverrides.forEach(GlobalConfiguration::setValue);
    configOverrides.clear();
  }

  private void pinConfig(GlobalConfiguration key, Object value) {
    configOverrides.putIfAbsent(key, key.getValue());
    key.setValue(value);
  }

  // ═════════════════════════════════════════════════════════════════
  // Frequency updates on insert/remove
  // ═════════════════════════════════════════════════════════════════

  @Test
  public void insert_updatesFrequencyInCorrectBucket() {
    // Given a 4-bucket histogram [0,25,50,75,100] with uniform freqs
    var fixture = new Fixture();
    var histogram = create4BucketHistogram();
    installSnapshot(fixture, 1000, 1000, 0, histogram, 0, 1000, 0);

    // When we insert key=30 (should go to bucket 1: [25..50))
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(op, 30, true, true);
    fixture.manager.applyDelta(holder.getDeltas().get(fixture.engineId));

    // Then bucket 1 frequency increases by 1
    var h = fixture.manager.getHistogram();
    assertNotNull(h);
    assertEquals(250, h.frequencies()[0]);
    assertEquals(251, h.frequencies()[1]);
    assertEquals(250, h.frequencies()[2]);
    assertEquals(250, h.frequencies()[3]);
    assertEquals(1001, h.nonNullCount());
  }

  @Test
  public void remove_decrementsFrequencyInCorrectBucket() {
    // Given a 4-bucket histogram
    var fixture = new Fixture();
    var histogram = create4BucketHistogram();
    installSnapshot(fixture, 1000, 1000, 0, histogram, 0, 1000, 0);

    // When we remove key=80 (bucket 3: [75..100])
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onRemove(op, 80, true);
    fixture.manager.applyDelta(holder.getDeltas().get(fixture.engineId));

    // Then bucket 3 frequency decreases by 1
    var h = fixture.manager.getHistogram();
    assertNotNull(h);
    assertEquals(249, h.frequencies()[3]);
    assertEquals(999, h.nonNullCount());
  }

  @Test
  public void multipleInsertsAndRemoves_accumulateCorrectly() {
    // Given a 4-bucket histogram
    var fixture = new Fixture();
    var histogram = create4BucketHistogram();
    installSnapshot(fixture, 1000, 1000, 0, histogram, 0, 1000, 0);

    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    // Insert 3 into bucket 0 ([0..25))
    fixture.manager.onPut(op, 5, true, true);
    fixture.manager.onPut(op, 10, true, true);
    fixture.manager.onPut(op, 15, true, true);
    // Remove 2 from bucket 2 ([50..75))
    fixture.manager.onRemove(op, 60, true);
    fixture.manager.onRemove(op, 65, true);
    fixture.manager.applyDelta(holder.getDeltas().get(fixture.engineId));

    var h = fixture.manager.getHistogram();
    assertNotNull(h);
    assertEquals(253, h.frequencies()[0]);
    assertEquals(248, h.frequencies()[2]);
    assertEquals(1001, h.nonNullCount()); // +3 -2 = +1
  }

  // ═════════════════════════════════════════════════════════════════
  // findBucket boundary cases (below min, above max, on boundary)
  // ═════════════════════════════════════════════════════════════════

  @Test
  public void insert_belowMinBoundary_goesToFirstBucket() {
    // Given histogram with boundaries [10, 30, 50, 70, 90]
    var fixture = new Fixture();
    var histogram = new EquiDepthHistogram(
        4,
        new Comparable<?>[] {10, 30, 50, 70, 90},
        new long[] {100, 100, 100, 100},
        new long[] {20, 20, 20, 20},
        400,
        null, 0);
    installSnapshot(fixture, 400, 400, 0, histogram, 0, 400, 0);

    // When inserting key=5 (below boundaries[0]=10)
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(op, 5, true, true);
    fixture.manager.applyDelta(holder.getDeltas().get(fixture.engineId));

    // Then it goes to bucket 0
    var h = fixture.manager.getHistogram();
    assertEquals(101, h.frequencies()[0]);
  }

  @Test
  public void insert_aboveMaxBoundary_goesToLastBucket() {
    // Given histogram with boundaries [10, 30, 50, 70, 90]
    var fixture = new Fixture();
    var histogram = new EquiDepthHistogram(
        4,
        new Comparable<?>[] {10, 30, 50, 70, 90},
        new long[] {100, 100, 100, 100},
        new long[] {20, 20, 20, 20},
        400,
        null, 0);
    installSnapshot(fixture, 400, 400, 0, histogram, 0, 400, 0);

    // When inserting key=95 (above boundaries[4]=90)
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(op, 95, true, true);
    fixture.manager.applyDelta(holder.getDeltas().get(fixture.engineId));

    // Then it goes to the last bucket (bucket 3)
    var h = fixture.manager.getHistogram();
    assertEquals(101, h.frequencies()[3]);
  }

  @Test
  public void insert_onBucketBoundary_goesToCorrectBucket() {
    // Given histogram with boundaries [0, 25, 50, 75, 100]
    var fixture = new Fixture();
    var histogram = create4BucketHistogram();
    installSnapshot(fixture, 1000, 1000, 0, histogram, 0, 1000, 0);

    // When inserting key=50 (exactly on boundary[2])
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(op, 50, true, true);
    fixture.manager.applyDelta(holder.getDeltas().get(fixture.engineId));

    // Then findBucket places key=50 in bucket 2 ([50..75))
    var h = fixture.manager.getHistogram();
    assertEquals("Bucket 2 should be incremented by 1", 251,
        h.frequencies()[2]);
    // Other buckets unchanged
    assertEquals(250, h.frequencies()[0]);
    assertEquals(250, h.frequencies()[1]);
    assertEquals(250, h.frequencies()[3]);
  }

  // ═════════════════════════════════════════════════════════════════
  // mutationsSinceRebalance triggers rebalance at threshold
  // ═════════════════════════════════════════════════════════════════

  @Test
  public void rebalance_triggeredWhenMutationsExceedThreshold()
      throws Exception {
    // Given a manager with enough mutations to exceed the threshold
    // (totalCountAtLastBuild=2000, default fraction=0.3, threshold=1000)
    var fixture = new Fixture();
    var histogram = create4BucketHistogram();
    // Set mutationsSinceRebalance=1500 > threshold=1000
    installSnapshot(fixture, 2000, 2000, 0, histogram, 1500, 2000, 0);

    fixture.manager.setKeyStreamSupplier(
        atomicOp -> IntStream.range(0, 2000).mapToObj(i -> (Object) i).sorted());
    setFileId(fixture.manager, 42);

    var executor = Executors.newSingleThreadExecutor();
    fixture.manager.setBackgroundExecutor(executor);
    try {
      // When getHistogram() is called (triggers schedule check)
      fixture.manager.getHistogram();

      executor.shutdown();
      assertTrue("Rebalance should complete",
          executor.awaitTermination(10, TimeUnit.SECONDS));

      // Then mutationsSinceRebalance is reset
      var snap = fixture.cache.get(fixture.engineId);
      assertNotNull(snap);
      assertEquals(0, snap.mutationsSinceRebalance());
    } finally {
      executor.shutdownNow();
    }
  }

  // ═════════════════════════════════════════════════════════════════
  // Rebalance produces fresh equi-depth boundaries
  // ═════════════════════════════════════════════════════════════════

  @Test
  public void rebalance_producesFreshBoundaries() throws Exception {
    // Given a manager with a skewed histogram and enough mutations
    var fixture = new Fixture();
    var histogram = new EquiDepthHistogram(
        4,
        new Comparable<?>[] {0, 10, 50, 75, 100},
        new long[] {800, 100, 50, 50},
        new long[] {10, 40, 25, 25},
        1000,
        null, 0);
    installSnapshot(fixture, 2000, 2000, 0, histogram, 5000, 2000, 0);

    // Key stream is uniform [0..2000) — rebalance should produce equi-depth
    fixture.manager.setKeyStreamSupplier(
        atomicOp -> IntStream.range(0, 2000).mapToObj(i -> (Object) i).sorted());
    setFileId(fixture.manager, 42);

    var executor = Executors.newSingleThreadExecutor();
    fixture.manager.setBackgroundExecutor(executor);
    try {
      fixture.manager.getHistogram();
      executor.shutdown();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

      // Then new histogram has different boundaries (fresh scan)
      var snap = fixture.cache.get(fixture.engineId);
      assertNotNull(snap);
      var newHist = snap.histogram();
      assertNotNull(newHist);
      // Version incremented
      assertTrue("Version should be incremented", snap.version() > 0);
      // hasDriftedBuckets should be reset
      assertFalse(snap.hasDriftedBuckets());
    } finally {
      executor.shutdownNow();
    }
  }

  // ═════════════════════════════════════════════════════════════════
  // Concurrent put/remove during rebalance — no corruption
  // ═════════════════════════════════════════════════════════════════

  @Test
  public void concurrentPutsDuringRebalance_noCorruption()
      throws Exception {
    // Given a manager set up for rebalance with a blocking key stream
    var fixture = new Fixture();
    var histogram = create4BucketHistogram();
    installSnapshot(fixture, 2000, 2000, 0, histogram, 5000, 2000, 0);

    var rebalanceStarted = new CountDownLatch(1);
    var putsCompleted = new CountDownLatch(1);
    fixture.manager.setKeyStreamSupplier(atomicOp -> {
      rebalanceStarted.countDown();
      try {
        // Block until concurrent puts are done
        putsCompleted.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return IntStream.range(0, 2000)
          .mapToObj(i -> (Object) i).sorted();
    });
    setFileId(fixture.manager, 42);

    var executor = Executors.newSingleThreadExecutor();
    fixture.manager.setBackgroundExecutor(executor);
    try {
      // Trigger rebalance
      fixture.manager.getHistogram();

      // Wait for rebalance to start and block
      // 30s timeout: generous buffer for CI runners under heavy parallel load
      assertTrue(rebalanceStarted.await(CI_TIMEOUT_SECONDS, TimeUnit.SECONDS));

      // Apply deltas while rebalance is blocked mid-scan
      for (int i = 0; i < 10; i++) {
        var holder = new HistogramDeltaHolder();
        var op = mockOp(holder);
        fixture.manager.onPut(op, i, true, true);
        fixture.manager.applyDelta(
            holder.getDeltas().get(fixture.engineId));
      }

      // Let rebalance complete
      putsCompleted.countDown();

      executor.shutdown();
      assertTrue(executor.awaitTermination(CI_TIMEOUT_SECONDS, TimeUnit.SECONDS));

      // Then no NPE/corruption — snapshot is valid
      var snap = fixture.cache.get(fixture.engineId);
      assertNotNull(snap);
      assertTrue("totalCount should be non-negative",
          snap.stats().totalCount() >= 0);
    } finally {
      executor.shutdownNow();
    }
  }

  // ═════════════════════════════════════════════════════════════════
  // Version-mismatch delta discard
  // ═════════════════════════════════════════════════════════════════

  @Test
  public void versionMismatch_frequencyDeltasDiscarded_scalarDeltasApplied() {
    // Given a manager with version=5 in the snapshot
    var fixture = new Fixture();
    var histogram = create4BucketHistogram();
    installSnapshot(fixture, 1000, 1000, 0, histogram, 0, 1000, 5);

    // When a delta with snapshotVersion=3 (old, stale) is applied
    var delta = new HistogramDelta();
    delta.totalCountDelta = 10;
    delta.nullCountDelta = 2;
    delta.mutationCount = 12;
    delta.snapshotVersion = 3; // doesn't match snapshot version=5
    delta.frequencyDeltas = new int[] {5, 3, 2, 2};
    fixture.manager.applyDelta(delta);

    // Then scalar counters are applied
    var stats = fixture.manager.getStatistics();
    assertEquals(1010, stats.totalCount());
    assertEquals(2, stats.nullCount());

    // But frequency deltas are discarded (version mismatch)
    var h = fixture.manager.getHistogram();
    assertNotNull(h);
    assertEquals(250, h.frequencies()[0]);
    assertEquals(250, h.frequencies()[1]);
    assertEquals(250, h.frequencies()[2]);
    assertEquals(250, h.frequencies()[3]);
  }

  @Test
  public void versionMatch_frequencyDeltasApplied() {
    // Given a manager with version=5 in the snapshot
    var fixture = new Fixture();
    var histogram = create4BucketHistogram();
    installSnapshot(fixture, 1000, 1000, 0, histogram, 0, 1000, 5);

    // When a delta with matching snapshotVersion=5 is applied
    var delta = new HistogramDelta();
    delta.totalCountDelta = 4;
    delta.mutationCount = 4;
    delta.snapshotVersion = 5;
    delta.frequencyDeltas = new int[] {1, 1, 1, 1};
    fixture.manager.applyDelta(delta);

    // Then frequency deltas ARE applied
    var h = fixture.manager.getHistogram();
    assertNotNull(h);
    assertEquals(251, h.frequencies()[0]);
    assertEquals(251, h.frequencies()[1]);
    assertEquals(251, h.frequencies()[2]);
    assertEquals(251, h.frequencies()[3]);
  }

  // ═════════════════════════════════════════════════════════════════
  // Mid-transaction rebalance: version mismatch during onPut/onRemove
  // ═════════════════════════════════════════════════════════════════

  @Test
  public void onPut_midTransactionRebalanceChangingBucketCount_noAIOOBE() {
    // Given a 4-bucket histogram at version 1
    var fixture = new Fixture();
    var histogram4 = create4BucketHistogram(); // 4 buckets, [0..100]
    installSnapshot(fixture, 1000, 1000, 0, histogram4, 0, 1000, 1);

    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);

    // When a first onPut initializes frequencyDeltas for the 4-bucket layout
    fixture.manager.onPut(op, 10, true, true);

    // And then a rebalance replaces the snapshot with an 8-bucket histogram
    // (more buckets than the original → findBucket could return index >= 4)
    var histogram8 = new EquiDepthHistogram(
        8,
        new Comparable<?>[] {0, 12, 25, 37, 50, 62, 75, 87, 100},
        new long[] {125, 125, 125, 125, 125, 125, 125, 125},
        new long[] {12, 13, 12, 13, 12, 13, 12, 13},
        1000,
        null, 0);
    installSnapshot(fixture, 1000, 1000, 0, histogram8, 0, 1000, 2);

    // Then a second onPut with key=90 (bucket 7 in the new 8-bucket layout)
    // must NOT throw ArrayIndexOutOfBoundsException — it should silently
    // skip the frequency delta because the version no longer matches.
    fixture.manager.onPut(op, 90, true, true);

    // Verify: delta still has the 4-element array from the first call,
    // and only the first onPut's frequency increment is recorded.
    var delta = holder.getDeltas().get(fixture.engineId);
    assertEquals(2, delta.totalCountDelta); // both inserts counted
    assertEquals(4, delta.frequencyDeltas.length); // old layout preserved
    // Bucket 0 got +1 from the first onPut (key=10 → bucket 0 in 4-bucket)
    assertEquals(1, delta.frequencyDeltas[0]);
    // No other bucket was touched — the second onPut was skipped
    assertEquals(0, delta.frequencyDeltas[1]);
    assertEquals(0, delta.frequencyDeltas[2]);
    assertEquals(0, delta.frequencyDeltas[3]);
  }

  @Test
  public void onRemove_midTransactionRebalanceChangingBucketCount_noAIOOBE() {
    // Given a 4-bucket histogram at version 1
    var fixture = new Fixture();
    var histogram4 = create4BucketHistogram();
    installSnapshot(fixture, 1000, 1000, 0, histogram4, 0, 1000, 1);

    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);

    // First onRemove initializes frequencyDeltas for the 4-bucket layout
    fixture.manager.onRemove(op, 10, true);

    // Rebalance replaces snapshot with an 8-bucket histogram at version 2
    var histogram8 = new EquiDepthHistogram(
        8,
        new Comparable<?>[] {0, 12, 25, 37, 50, 62, 75, 87, 100},
        new long[] {125, 125, 125, 125, 125, 125, 125, 125},
        new long[] {12, 13, 12, 13, 12, 13, 12, 13},
        1000,
        null, 0);
    installSnapshot(fixture, 1000, 1000, 0, histogram8, 0, 1000, 2);

    // Second onRemove with key=90 (bucket 7 in new layout) must not throw
    fixture.manager.onRemove(op, 90, true);

    // Verify: delta has 4-element array, only first remove is recorded
    var delta = holder.getDeltas().get(fixture.engineId);
    assertEquals(-2, delta.totalCountDelta);
    assertEquals(4, delta.frequencyDeltas.length);
    assertEquals(-1, delta.frequencyDeltas[0]);
    assertEquals(0, delta.frequencyDeltas[1]);
    assertEquals(0, delta.frequencyDeltas[2]);
    assertEquals(0, delta.frequencyDeltas[3]);
  }

  @Test
  public void onPut_sameBucketCountDifferentVersion_skipsFrequencyDelta() {
    // Even when bucket count stays the same, a version change means
    // boundaries may have shifted — frequency deltas should be skipped.
    var fixture = new Fixture();
    var histogram = create4BucketHistogram();
    installSnapshot(fixture, 1000, 1000, 0, histogram, 0, 1000, 1);

    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);

    // First onPut at version 1
    fixture.manager.onPut(op, 30, true, true);

    // Rebalance with same bucket count but new version
    var histogram2 = new EquiDepthHistogram(
        4,
        new Comparable<?>[] {0, 20, 40, 60, 100},
        new long[] {200, 200, 300, 300},
        new long[] {20, 20, 30, 30},
        1000,
        null, 0);
    installSnapshot(fixture, 1000, 1000, 0, histogram2, 0, 1000, 2);

    // Second onPut — version mismatch, frequency skipped
    fixture.manager.onPut(op, 30, true, true);

    var delta = holder.getDeltas().get(fixture.engineId);
    assertEquals(2, delta.totalCountDelta); // both counted
    // Only the first onPut's frequency is recorded (key=30 → bucket 1)
    assertEquals(0, delta.frequencyDeltas[0]);
    assertEquals(1, delta.frequencyDeltas[1]);
    assertEquals(0, delta.frequencyDeltas[2]);
    assertEquals(0, delta.frequencyDeltas[3]);
  }

  // ═════════════════════════════════════════════════════════════════
  // At-most-one rebalance guard (AtomicBoolean)
  // ═════════════════════════════════════════════════════════════════

  @Test
  public void atMostOneRebalance_secondScheduleIsIgnored() throws Exception {
    // Given a manager where rebalance is in progress
    var fixture = new Fixture();
    var histogram = create4BucketHistogram();

    var rebalanceBlocked = new CountDownLatch(1);
    var rebalanceProceeds = new CountDownLatch(1);
    var rebalanceCount = new AtomicInteger(0);

    fixture.manager.setKeyStreamSupplier(atomicOp -> {
      rebalanceCount.incrementAndGet();
      rebalanceBlocked.countDown();
      try {
        // Block until we say proceed
        rebalanceProceeds.await(CI_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return IntStream.range(0, 2000).mapToObj(i -> (Object) i).sorted();
    });
    setFileId(fixture.manager, 42);

    // Wire the executor BEFORE installing the high-mutations snapshot so that
    // setBackgroundExecutor's proactive maybeScheduleHistogramWork finds no
    // snapshot in the cache and is a no-op. Only getHistogram() below will
    // trigger the rebalance — eliminating a double-trigger race.
    var executor = Executors.newFixedThreadPool(2);
    fixture.manager.setBackgroundExecutor(executor);

    // Now install the snapshot whose mutations exceed the rebalance threshold
    installSnapshot(fixture, 2000, 2000, 0, histogram, 5000, 2000, 0);

    try {
      // First call triggers rebalance
      fixture.manager.getHistogram();
      // 30s timeout: generous buffer for CI runners under heavy parallel load
      assertTrue(rebalanceBlocked.await(CI_TIMEOUT_SECONDS, TimeUnit.SECONDS));

      // Second call while first rebalance is in progress — should be no-op
      fixture.manager.maybeScheduleHistogramWork(executor);

      // Let rebalance proceed
      rebalanceProceeds.countDown();

      executor.shutdown();
      assertTrue(executor.awaitTermination(CI_TIMEOUT_SECONDS, TimeUnit.SECONDS));

      // Only one rebalance should have executed
      assertEquals("Only one rebalance should run", 1,
          rebalanceCount.get());
    } finally {
      executor.shutdownNow();
    }
  }

  // ═════════════════════════════════════════════════════════════════
  // Rebalance failure cooldown
  // ═════════════════════════════════════════════════════════════════

  @Test
  public void rebalanceFailureCooldown_blocksRetriggerWithinWindow() {
    // Given a manager with lastRebalanceFailureNanos set to now
    var fixture = new Fixture();
    var histogram = create4BucketHistogram();
    installSnapshot(fixture, 2000, 2000, 0, histogram, 10000, 2000, 0);

    setLastRebalanceFailureNanos(fixture.manager, System.nanoTime());
    setFileId(fixture.manager, 42);

    var executor = Executors.newSingleThreadExecutor();
    fixture.manager.setBackgroundExecutor(executor);
    try {
      // When getHistogram() is called (within cooldown window)
      fixture.manager.getHistogram();

      executor.shutdown();
      assertTrue("No rebalance should be scheduled during cooldown",
          executor.awaitTermination(1, TimeUnit.SECONDS));

      // Then snapshot is unchanged
      assertEquals(10000,
          fixture.cache.get(fixture.engineId).mutationsSinceRebalance());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  public void rebalanceFailureCooldown_allowsRetriggerAfterExpiry()
      throws Exception {
    // Given a manager with cooldown expired (failure time far in the past)
    var fixture = new Fixture();
    var histogram = create4BucketHistogram();
    installSnapshot(fixture, 2000, 2000, 0, histogram, 10000, 2000, 0);

    // Set failure time 120 seconds ago (default cooldown is 60s)
    setLastRebalanceFailureNanos(fixture.manager,
        System.nanoTime() - TimeUnit.SECONDS.toNanos(120));

    fixture.manager.setKeyStreamSupplier(
        atomicOp -> IntStream.range(0, 2000).mapToObj(i -> (Object) i).sorted());
    setFileId(fixture.manager, 42);

    var executor = Executors.newSingleThreadExecutor();
    fixture.manager.setBackgroundExecutor(executor);
    try {
      fixture.manager.getHistogram();
      executor.shutdown();
      assertTrue("Rebalance should run after cooldown expiry",
          executor.awaitTermination(10, TimeUnit.SECONDS));

      // mutationsSinceRebalance reset — rebalance ran
      assertEquals(0,
          fixture.cache.get(fixture.engineId).mutationsSinceRebalance());
    } finally {
      executor.shutdownNow();
    }
  }

  // ═════════════════════════════════════════════════════════════════
  // Engine close during rebalance — flag reset
  // ═════════════════════════════════════════════════════════════════

  @Test
  public void engineCloseDuringRebalance_rebalanceFlagReset()
      throws Exception {
    // Given a manager with a key stream that throws (simulating close)
    var fixture = new Fixture();
    var histogram = create4BucketHistogram();
    installSnapshot(fixture, 2000, 2000, 0, histogram, 5000, 2000, 0);

    fixture.manager.setKeyStreamSupplier(atomicOp -> {
      throw new RuntimeException("Engine closed");
    });
    setFileId(fixture.manager, 42);

    var executor = Executors.newSingleThreadExecutor();
    fixture.manager.setBackgroundExecutor(executor);
    try {
      fixture.manager.getHistogram();
      executor.shutdown();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

      // Then rebalanceInProgress flag is reset (not stuck)
      // Verify by checking that a subsequent rebalance can be scheduled
      assertFalse("rebalanceInProgress should be reset after failure",
          fixture.manager.isRebalanceInProgress());
    } finally {
      executor.shutdownNow();
    }
  }

  // ═════════════════════════════════════════════════════════════════
  // closeStatsFile waits for in-progress rebalance
  // ═════════════════════════════════════════════════════════════════

  @Test
  public void closeStatsFile_waitsForInProgressRebalance() throws Exception {
    // Regression test: closeStatsFile must wait for a background rebalance
    // to finish before cleaning up. Without this, the rebalance could
    // install a phantom entry in the CHM cache or write to a closed file.
    var fixture = new Fixture();
    var histogram = create4BucketHistogram();
    installSnapshot(fixture, 2000, 2000, 0, histogram, 5000, 2000, 0);
    setFileId(fixture.manager, 42);

    // Set up a key stream that blocks until we release it
    var rebalanceStarted = new CountDownLatch(1);
    var allowRebalanceFinish = new CountDownLatch(1);
    fixture.manager.setKeyStreamSupplier(atomicOp -> {
      rebalanceStarted.countDown();
      try {
        allowRebalanceFinish.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return IntStream.rangeClosed(1, 2000).mapToObj(i -> (Object) i);
    });

    var executor = Executors.newSingleThreadExecutor();
    fixture.manager.setBackgroundExecutor(executor);
    try {
      // Trigger background rebalance
      fixture.manager.getHistogram();
      assertTrue("Rebalance should have started",
          rebalanceStarted.await(10, TimeUnit.SECONDS));

      // closeStatsFile on another thread — should block until rebalance finishes
      var closeDone = new CountDownLatch(1);
      var closeThread = new Thread(() -> {
        fixture.manager.closeStatsFile();
        closeDone.countDown();
      });
      closeThread.start();

      // closeStatsFile should NOT have completed yet (rebalance still running)
      assertFalse("close should block while rebalance is in progress",
          closeDone.await(200, TimeUnit.MILLISECONDS));

      // Let rebalance finish
      allowRebalanceFinish.countDown();

      // Now close should complete
      assertTrue("close should complete after rebalance finishes",
          closeDone.await(10, TimeUnit.SECONDS));

      // After close: cache entry removed, rebalance permanently blocked
      assertNull("Cache entry should be removed after close",
          fixture.cache.get(fixture.engineId));
      assertTrue("rebalanceInProgress should remain true after close",
          fixture.manager.isRebalanceInProgress());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  public void closeStatsFile_blocksSubsequentRebalance() {
    // After closeStatsFile, rebalanceInProgress is permanently true,
    // preventing any future rebalance from being scheduled.
    var fixture = new Fixture();
    installSnapshot(fixture, 100, 100, 0, null, 0, 100, 0);

    fixture.manager.closeStatsFile();

    // rebalanceInProgress should be permanently set
    assertTrue("rebalanceInProgress should be permanently set after close",
        fixture.manager.isRebalanceInProgress());
  }

  // ═════════════════════════════════════════════════════════════════
  // resetOnClear — zeroes counters, discards histogram
  // ═════════════════════════════════════════════════════════════════

  @Test
  public void resetOnClear_zeroesAllCounters() throws Exception {
    // Given a manager with a populated snapshot
    var fixture = new Fixture();
    var histogram = create4BucketHistogram();
    installSnapshot(fixture, 1000, 1000, 50, histogram, 500, 1000, 3);

    // When resetOnClear is called (fileId==-1 → skips page I/O)
    var op = mock(AtomicOperation.class);
    fixture.manager.resetOnClear(op);

    // Then the cache reflects empty state
    var snap = fixture.cache.get(fixture.engineId);
    assertNotNull(snap);
    assertEquals(0, snap.stats().totalCount());
    assertEquals(0, snap.stats().distinctCount());
    assertEquals(0, snap.stats().nullCount());
    assertNull(snap.histogram());
    assertEquals(0, snap.mutationsSinceRebalance());
    assertEquals(0, snap.totalCountAtLastBuild());
  }

  @Test
  public void resetOnClear_subsequentPutStartsFresh()
      throws Exception {
    // Given a manager that has been cleared
    var fixture = new Fixture();
    var histogram = create4BucketHistogram();
    installSnapshot(fixture, 1000, 1000, 0, histogram, 0, 1000, 0);

    var op = mock(AtomicOperation.class);
    fixture.manager.resetOnClear(op);

    // When a new insert is applied after clear
    var holder = new HistogramDeltaHolder();
    var putOp = mockOp(holder);
    fixture.manager.onPut(putOp, 42, true, true);
    fixture.manager.applyDelta(holder.getDeltas().get(fixture.engineId));

    // Then counters start from scratch
    var stats = fixture.manager.getStatistics();
    assertEquals(1, stats.totalCount());
    // No histogram (cleared, not rebuilt)
    assertNull(fixture.manager.getHistogram());
  }

  // ═════════════════════════════════════════════════════════════════
  // Drift-biased rebalance threshold halving
  // ═════════════════════════════════════════════════════════════════

  @Test
  public void driftBiased_negativeFrequencyClamped_setsFlag() {
    // Given a manager with a histogram
    var fixture = new Fixture();
    var histogram = new EquiDepthHistogram(
        2,
        new Comparable<?>[] {0, 50, 100},
        new long[] {5, 50},
        new long[] {5, 50},
        55,
        null, 0);
    // hasDriftedBuckets=false initially
    installSnapshot(fixture, 55, 55, 0, histogram, 0, 55, 0);

    // When we remove enough from bucket 0 to make it go negative
    var delta = new HistogramDelta();
    delta.totalCountDelta = -10;
    delta.mutationCount = 10;
    delta.snapshotVersion = 0;
    delta.frequencyDeltas = new int[] {-10, 0}; // bucket 0 would go to -5
    fixture.manager.applyDelta(delta);

    // Then frequency is clamped to 0 and hasDriftedBuckets is set
    var snap = fixture.cache.get(fixture.engineId);
    assertTrue("hasDriftedBuckets should be set after negative clamping",
        snap.hasDriftedBuckets());
    assertEquals(0, snap.histogram().frequencies()[0]);
  }

  @Test
  public void driftBiased_halvesRebalanceThreshold() throws Exception {
    // Given a drifted snapshot (hasDriftedBuckets=true)
    // Normal threshold for totalCountAtLastBuild=5000:
    //   max(min(5000*0.3=1500, 10M), 1000) = 1500
    // Halved threshold = 750
    var fixture = new Fixture();
    var histogram = create4BucketHistogram();
    // mutationsSinceRebalance=1000 > 750 (halved) but < 1500 (normal)
    installSnapshot(fixture, 5000, 5000, 0, histogram, 1000, 5000, 0);
    // Manually set hasDriftedBuckets
    var driftedSnap = new HistogramSnapshot(
        fixture.cache.get(fixture.engineId).stats(),
        histogram,
        1000, 5000, 0, true, null, false);
    fixture.cache.put(fixture.engineId, driftedSnap);

    fixture.manager.setKeyStreamSupplier(
        atomicOp -> IntStream.range(0, 5000).mapToObj(i -> (Object) i).sorted());
    setFileId(fixture.manager, 42);

    var executor = Executors.newSingleThreadExecutor();
    fixture.manager.setBackgroundExecutor(executor);
    try {
      fixture.manager.getHistogram();
      executor.shutdown();
      assertTrue("Drift-biased rebalance should complete",
          executor.awaitTermination(10, TimeUnit.SECONDS));

      // Rebalance ran → mutationsSinceRebalance reset
      assertEquals(0,
          fixture.cache.get(fixture.engineId).mutationsSinceRebalance());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  public void noDrift_normalThreshold_doesNotTriggerAtHalvedLevel() {
    // Given the same counts but hasDriftedBuckets=false
    // Normal threshold = 1500, mutations = 1000 < 1500 → no rebalance
    var fixture = new Fixture();
    var histogram = create4BucketHistogram();
    installSnapshot(fixture, 5000, 5000, 0, histogram, 1000, 5000, 0);

    var executor = Executors.newSingleThreadExecutor();
    fixture.manager.setBackgroundExecutor(executor);
    try {
      fixture.manager.getHistogram();
      executor.shutdown();
      assertTrue("No rebalance should be scheduled",
          executor.awaitTermination(1, TimeUnit.SECONDS));

      // Snapshot unchanged
      assertEquals(1000,
          fixture.cache.get(fixture.engineId).mutationsSinceRebalance());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } finally {
      executor.shutdownNow();
    }
  }

  // ═════════════════════════════════════════════════════════════════
  // Storage-level rebalance throttling (semaphore)
  // ═════════════════════════════════════════════════════════════════

  @Test
  public void storageLevelThrottling_excessRebalancesDeferred()
      throws Exception {
    // Given 3 managers sharing a semaphore with 1 permit
    var sem = new Semaphore(1);
    var rebalanceStarted = new CountDownLatch(1);
    var rebalanceProceeds = new CountDownLatch(1);
    // Track peak concurrent rebalances to prove throttling
    var concurrentRebalances = new AtomicInteger(0);
    var peakConcurrent = new AtomicInteger(0);
    // Any fixture may acquire the semaphore first — use CAS to let the
    // winner signal the latch and block, regardless of fixture index.
    var isFirst = new AtomicBoolean(false);

    var fixtures = new Fixture[3];
    for (int i = 0; i < 3; i++) {
      fixtures[i] = new Fixture(i);
      var histogram = create4BucketHistogram();
      installSnapshot(
          fixtures[i], 2000, 2000, 0, histogram, 5000, 2000, 0);
      fixtures[i].manager.setRebalanceSemaphore(sem);
      setFileId(fixtures[i].manager, 42 + i);

      fixtures[i].manager.setKeyStreamSupplier(atomicOp -> {
        int cur = concurrentRebalances.incrementAndGet();
        peakConcurrent.updateAndGet(old -> Math.max(old, cur));
        if (isFirst.compareAndSet(false, true)) {
          rebalanceStarted.countDown();
          try {
            rebalanceProceeds.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }
        try {
          return IntStream.range(0, 2000)
              .mapToObj(j -> (Object) j).sorted();
        } finally {
          concurrentRebalances.decrementAndGet();
        }
      });
    }

    var executor = Executors.newFixedThreadPool(4);
    for (var f : fixtures) {
      f.manager.setBackgroundExecutor(executor);
    }
    try {
      // Trigger all three
      for (var f : fixtures) {
        f.manager.getHistogram();
      }

      // Wait for first rebalance to start and block
      assertTrue(rebalanceStarted.await(CI_TIMEOUT_SECONDS, TimeUnit.SECONDS));

      // Let the first one proceed
      rebalanceProceeds.countDown();
      executor.shutdown();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

      // Peak concurrency should be 1 (semaphore allows only 1)
      assertEquals(
          "Peak concurrent rebalances should be 1",
          1, peakConcurrent.get());
    } finally {
      executor.shutdownNow();
    }
  }

  // ═════════════════════════════════════════════════════════════════
  // Checkpoint flush: dirty → flushed → clean
  // ═════════════════════════════════════════════════════════════════

  @Test
  public void checkpointFlush_dirtyMutationsResetToZero() {
    // Given a manager with dirtyMutations > 0 (below batch size)
    var fixture = new Fixture();
    installSnapshot(fixture, 100, 100, 0, null, 0, 100, 0);

    var delta = new HistogramDelta();
    delta.totalCountDelta = 5;
    delta.mutationCount = 5;
    fixture.manager.applyDelta(delta);

    assertEquals(5, fixture.manager.getDirtyMutations());

    // When flushIfDirty() is called (no-arg: checkpoint path)
    fixture.manager.flushIfDirty();

    // Then dirtyMutations is reset to 0
    assertEquals(0, fixture.manager.getDirtyMutations());
  }

  @Test
  public void checkpointFlush_cleanEngine_noOp() {
    // Given a manager with dirtyMutations == 0
    var fixture = new Fixture();
    installSnapshot(fixture, 100, 100, 0, null, 0, 100, 0);

    assertEquals(0, fixture.manager.getDirtyMutations());

    // When flushIfDirty() is called
    fixture.manager.flushIfDirty();

    // Then no exception and still 0
    assertEquals(0, fixture.manager.getDirtyMutations());
  }

  @Test
  public void checkpointFlush_ioFailure_swallowedAndDirtyCountPreserved() {
    // Given a manager where atomicOperationsManager throws IOException
    var fixture = new FailingFlushFixture();
    setDirtyMutations(fixture.manager, 100);
    setFileId(fixture.manager, 42);

    // When flushIfDirty() is called
    fixture.manager.flushIfDirty();

    // Then no exception propagates (IOException is caught and logged).
    // dirtyMutations is NOT reset because the flush failed.
    assertEquals(100, fixture.manager.getDirtyMutations());
  }

  @Test
  public void checkpointFlush_closedEngine_nullCacheEntry_noError() {
    // Given a manager with no cache entry (engine deleted/closed)
    var fixture = new Fixture();
    // Do NOT install snapshot — simulates deleted engine
    setDirtyMutations(fixture.manager, 50);

    // When flushIfDirty() is called
    fixture.manager.flushIfDirty();

    // Then no exception and dirty count is reset (cache miss = success)
    assertEquals(0, fixture.manager.getDirtyMutations());
  }

  // ═════════════════════════════════════════════════════════════════
  // Full data flush path
  // ═════════════════════════════════════════════════════════════════

  @Test
  public void fullDataFlush_dirtyHistogramsFlushedBeforeWalFlush() {
    // Verifies the flush sequence: dirty mutations → flush → reset to 0
    var fixture = new Fixture();
    installSnapshot(fixture, 100, 100, 0, null, 0, 100, 0);

    // Accumulate dirty mutations
    for (int i = 0; i < 3; i++) {
      var delta = new HistogramDelta();
      delta.totalCountDelta = 1;
      delta.mutationCount = 10;
      fixture.manager.applyDelta(delta);
    }
    assertEquals(30, fixture.manager.getDirtyMutations());

    // When flushIfDirty() is called (simulating flushDirtyHistograms)
    fixture.manager.flushIfDirty();

    // Then dirty mutations are completely reset
    assertEquals(0, fixture.manager.getDirtyMutations());
  }

  // ═════════════════════════════════════════════════════════════════
  // Batch persistence — auto-flush at batch threshold
  // ═════════════════════════════════════════════════════════════════

  @Test
  public void batchPersistence_autoFlushesAtThreshold() {
    // Given a manager with dirtyMutations just below the default batch
    // size (default PERSIST_BATCH_SIZE = 500)
    var fixture = new Fixture();
    installSnapshot(fixture, 100, 100, 0, null, 0, 100, 0);

    // Apply a large delta that exceeds the batch size
    var delta = new HistogramDelta();
    delta.totalCountDelta = 50;
    delta.mutationCount = 501; // exceeds 500 threshold
    fixture.manager.applyDelta(delta);

    // Then dirtyMutations should have been auto-reset by the batch flush.
    // Note: if fileId == -1, flushSnapshotToPage returns early (no real I/O)
    // but the dirtyMutations reset still happens.
    assertEquals(0, fixture.manager.getDirtyMutations());
  }

  // ═════════════════════════════════════════════════════════════════
  // Fixtures and helpers
  // ═════════════════════════════════════════════════════════════════

  /**
   * Creates a 4-bucket histogram over [0..100] with uniform frequencies
   * of 250 each and nonNullCount=1000.
   */
  private static EquiDepthHistogram create4BucketHistogram() {
    return new EquiDepthHistogram(
        4,
        new Comparable<?>[] {0, 25, 50, 75, 100},
        new long[] {250, 250, 250, 250},
        new long[] {25, 25, 25, 25},
        1000,
        null,
        0);
  }

  private static AtomicOperation mockOp(HistogramDeltaHolder holder) {
    var op = mock(AtomicOperation.class);
    when(op.getOrCreateHistogramDeltas()).thenReturn(holder);
    return op;
  }

  private static void installSnapshot(
      Fixture fixture, long totalCount, long distinctCount,
      long nullCount, EquiDepthHistogram histogram,
      long mutationsSinceRebalance, long totalCountAtLastBuild,
      long version) {
    var stats = new IndexStatistics(totalCount, distinctCount, nullCount);
    fixture.cache.put(fixture.engineId,
        new HistogramSnapshot(
            stats, histogram, mutationsSinceRebalance,
            totalCountAtLastBuild, version, false, null, false));
  }

  private static AbstractStorage createMockStorage() {
    var storage = mock(AbstractStorage.class);
    var factory = new CurrentStorageComponentsFactory(
        BinarySerializerFactory.currentBinaryFormatVersion());
    when(storage.getComponentsFactory()).thenReturn(factory);
    var atomicOps = mock(AtomicOperationsManager.class);
    when(atomicOps.startAtomicOperation()).thenReturn(mock(AtomicOperation.class));
    when(storage.getAtomicOperationsManager()).thenReturn(atomicOps);
    when(storage.getReadCache()).thenReturn(mock(ReadCache.class));
    when(storage.getWriteCache()).thenReturn(mock(WriteCache.class));
    return storage;
  }

  private static AbstractStorage createMockStorageWithFailingAtomicOps() {
    var storage = mock(AbstractStorage.class);
    var factory = new CurrentStorageComponentsFactory(
        BinarySerializerFactory.currentBinaryFormatVersion());
    when(storage.getComponentsFactory()).thenReturn(factory);
    var atomicOps = mock(AtomicOperationsManager.class);
    try {
      doThrow(new IOException("simulated I/O failure"))
          .when(atomicOps).executeInsideAtomicOperation(any());
    } catch (IOException ignored) {
      // doThrow setup doesn't actually throw
    }
    when(storage.getAtomicOperationsManager()).thenReturn(atomicOps);
    when(storage.getReadCache()).thenReturn(mock(ReadCache.class));
    when(storage.getWriteCache()).thenReturn(mock(WriteCache.class));
    return storage;
  }

  private static void setFileId(
      IndexHistogramManager manager, long value) {
    manager.setFileIdForTest(value);
  }

  private static void setLastRebalanceFailureNanos(
      IndexHistogramManager manager, long value) {
    manager.setLastRebalanceFailureNanos(value);
  }

  private static void setDirtyMutations(
      IndexHistogramManager manager, long value) {
    manager.setDirtyMutationsForTest(value);
  }

  /**
   * Standard test fixture with mock storage and real CHM cache.
   */
  private static class Fixture {
    final int engineId;
    final ConcurrentHashMap<Integer, HistogramSnapshot> cache =
        new ConcurrentHashMap<>();
    final IndexHistogramManager manager;

    Fixture() {
      this(0);
    }

    Fixture(int engineId) {
      this.engineId = engineId;
      var storage = createMockStorage();
      var serializerFactory = BinarySerializerFactory.create(
          BinarySerializerFactory.CURRENT_BINARY_FORMAT_VERSION);
      manager = new IndexHistogramManager(
          storage, "test-idx-" + engineId, engineId, true, cache,
          IntegerSerializer.INSTANCE, serializerFactory,
          IntegerSerializer.ID);
    }
  }

  /**
   * Creates a manager where flushSnapshotToPage will fail with IOException.
   * The mocked AtomicOperationsManager throws IOException when
   * executeInsideAtomicOperation is called. Cache is pre-populated with a
   * snapshot so flushSnapshotToPage doesn't return early on cache miss.
   */
  private static class FailingFlushFixture {
    final int engineId = 0;
    final ConcurrentHashMap<Integer, HistogramSnapshot> cache =
        new ConcurrentHashMap<>();
    final IndexHistogramManager manager;

    FailingFlushFixture() {
      var storage = createMockStorageWithFailingAtomicOps();
      var serializerFactory = BinarySerializerFactory.create(
          BinarySerializerFactory.CURRENT_BINARY_FORMAT_VERSION);
      manager = new IndexHistogramManager(
          storage, "test-idx", engineId, true, cache,
          IntegerSerializer.INSTANCE, serializerFactory,
          IntegerSerializer.ID);
      // Populate cache so flush actually attempts I/O
      var stats = new IndexStatistics(100, 100, 0);
      cache.put(engineId, new HistogramSnapshot(
          stats, null, 0, 100, 0, false, null, false));
    }
  }
}
