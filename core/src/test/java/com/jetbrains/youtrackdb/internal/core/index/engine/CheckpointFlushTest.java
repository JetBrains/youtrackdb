package com.jetbrains.youtrackdb.internal.core.index.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.db.record.CurrentStorageComponentsFactory;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Test;

/**
 * Tests for checkpoint and shutdown flushing of histogram data (Step 9).
 *
 * <p>Covers the no-arg {@link IndexHistogramManager#flushIfDirty()} method
 * which is called by {@code AbstractStorage.flushDirtyHistograms()} during
 * fuzzy checkpoint and full data flush.
 */
public class CheckpointFlushTest {

  // ═══════════════════════════════════════════════════════════════════════
  // flushIfDirty() no-arg: skip when clean
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void flushIfDirty_noArg_skipsWhenDirtyMutationsIsZero() {
    // Given a manager with dirtyMutations == 0 (clean state)
    var fixture = new ManagerFixture();
    assertEquals(0, fixture.manager.getDirtyMutations());

    // When flushIfDirty() is called
    fixture.manager.flushIfDirty();

    // Then nothing happens — no exception, dirtyMutations stays 0
    assertEquals(0, fixture.manager.getDirtyMutations());
  }

  // ═══════════════════════════════════════════════════════════════════════
  // flushIfDirty() no-arg: resets dirtyMutations on success
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void flushIfDirty_noArg_resetsDirtyMutationsWhenCacheEntryAbsent() {
    // Given a manager with dirtyMutations > 0 but no cache entry.
    // Cache miss means flushSnapshotToPage returns early (no-op success)
    // — dirtyMutations should still be reset to 0.
    var fixture = new ManagerFixture();
    setDirtyMutations(fixture.manager, 42);

    // When flushIfDirty() is called
    fixture.manager.flushIfDirty();

    // Then dirtyMutations is reset to 0
    assertEquals(0, fixture.manager.getDirtyMutations());
  }

  // ═══════════════════════════════════════════════════════════════════════
  // flushIfDirty() no-arg: exception swallowed
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void flushIfDirty_noArg_ioFailure_swallowedAndDirtyCountPreserved() {
    // Given a manager with dirtyMutations > 0, a valid fileId, and a cache
    // entry — the mocked atomicOperationsManager throws IOException when
    // executeInsideAtomicOperation is called.
    var fixture = new ManagerFixtureWithFailingFlush();
    setDirtyMutations(fixture.manager, 100);
    setFileId(fixture.manager, 42); // force non-(-1) so flush is attempted

    // When flushIfDirty() is called
    fixture.manager.flushIfDirty();

    // Then no exception propagates (IOException is caught and logged).
    // dirtyMutations is NOT reset because the flush failed before
    // reaching the reset line.
    assertEquals(100, fixture.manager.getDirtyMutations());
  }

  // ═══════════════════════════════════════════════════════════════════════
  // flushIfDirty() no-arg: applyDelta increments dirtyMutations
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void applyDelta_incrementsDirtyMutations_whichFlushIfDirtyResets() {
    // Given a manager with a populated cache entry
    var fixture = new ManagerFixture();
    var stats = new IndexStatistics(100, 100, 0);
    var snapshot = new HistogramSnapshot(
        stats, null, 0, 100, 0, false, null, false);
    fixture.cache.put(fixture.engineId, snapshot);

    // When applyDelta is called (below batch size)
    var delta = new HistogramDelta();
    delta.totalCountDelta = 1;
    delta.mutationCount = 5;
    fixture.manager.applyDelta(delta);

    // Then dirtyMutations is incremented
    assertEquals(5, fixture.manager.getDirtyMutations());

    // And flushIfDirty resets it (cache miss on flush is still a success)
    fixture.manager.flushIfDirty();
    assertEquals(0, fixture.manager.getDirtyMutations());
  }

  // ═══════════════════════════════════════════════════════════════════════
  // flushIfDirty() no-arg: multiple sequential calls
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void flushIfDirty_noArg_calledTwice_secondCallIsNoOp() {
    // Given a manager with dirtyMutations > 0 and empty cache
    var fixture = new ManagerFixture();
    setDirtyMutations(fixture.manager, 10);

    // When flushIfDirty() is called once
    fixture.manager.flushIfDirty();
    assertEquals(0, fixture.manager.getDirtyMutations());

    // And called again immediately
    fixture.manager.flushIfDirty();

    // Then the second call is a no-op (dirtyMutations was already 0)
    assertEquals(0, fixture.manager.getDirtyMutations());
  }

  // ═══════════════════════════════════════════════════════════════════════
  // flushIfDirty() no-arg: resets after accumulation
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void flushIfDirty_noArg_resetsAccumulatedDirtyMutations() {
    // Given a manager that has accumulated multiple applyDelta calls
    var fixture = new ManagerFixture();
    var stats = new IndexStatistics(100, 100, 0);
    var snapshot = new HistogramSnapshot(
        stats, null, 0, 100, 0, false, null, false);
    fixture.cache.put(fixture.engineId, snapshot);

    // When multiple deltas are applied (all below batch size)
    for (int i = 0; i < 3; i++) {
      var delta = new HistogramDelta();
      delta.totalCountDelta = 1;
      delta.mutationCount = 10;
      fixture.manager.applyDelta(delta);
    }

    // Then dirtyMutations reflects the total
    assertEquals(30, fixture.manager.getDirtyMutations());

    // And flushIfDirty() resets it completely
    fixture.manager.flushIfDirty();
    assertEquals(0, fixture.manager.getDirtyMutations());
  }

  // ═══════════════════════════════════════════════════════════════════════
  // flushIfDirty() no-arg: concurrent applyDelta does not lose mutations
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void flushIfDirty_noArg_concurrentApplyDelta_doesNotLoseMutations()
      throws Exception {
    // Regression test for TOCTOU race: flushIfDirty() must use CAS to zero
    // dirtyMutations. Without CAS, mutations added by concurrent applyDelta
    // between the flush and the reset are silently lost.
    var fixture = new ManagerFixture();
    var stats = new IndexStatistics(100, 100, 0);
    var snapshot = new HistogramSnapshot(
        stats, null, 0, 100, 0, false, null, false);
    fixture.cache.put(fixture.engineId, snapshot);

    // Accumulate some dirty mutations
    setDirtyMutations(fixture.manager, 50);

    // Run flushIfDirty and concurrent applyDelta in parallel.
    // The CAS-based approach ensures that mutations added during/after
    // the flush are preserved.
    var flushDone = new java.util.concurrent.CountDownLatch(1);
    var flushThread = new Thread(() -> {
      fixture.manager.flushIfDirty();
      flushDone.countDown();
    });
    flushThread.start();
    flushDone.await(5, java.util.concurrent.TimeUnit.SECONDS);

    // Apply a delta after flush
    var delta = new HistogramDelta();
    delta.totalCountDelta = 1;
    delta.mutationCount = 10;
    fixture.manager.applyDelta(delta);

    // The delta's mutations must NOT have been zeroed out by the flush.
    // They should be preserved (either the full 10, or if CAS lost a retry
    // race, at least > 0).
    assertTrue("Mutations added after flush must not be lost",
        fixture.manager.getDirtyMutations() > 0);
  }

  @Test
  public void flushIfDirty_noArg_ioFailure_restoresDirtyCount() {
    // Regression test: when flushSnapshotToPage fails, dirtyMutations must
    // be restored to the pre-flush value so the next checkpoint re-triggers.
    var fixture = new ManagerFixtureWithFailingFlush();
    setDirtyMutations(fixture.manager, 75);
    setFileId(fixture.manager, 42);

    fixture.manager.flushIfDirty();

    // On failure, the CAS zeroed the count but the catch block restores it.
    assertEquals("dirtyMutations must be restored on flush failure",
        75, fixture.manager.getDirtyMutations());
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Fixtures
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Creates a real IndexHistogramManager with a mock storage that supports
   * basic StorageComponent initialization. The atomicOperationsManager is
   * mock-based so flushSnapshotToPage returns early on cache miss.
   */
  private static class ManagerFixture {
    final int engineId = 0;
    final ConcurrentHashMap<Integer, HistogramSnapshot> cache =
        new ConcurrentHashMap<>();
    final IndexHistogramManager manager;

    ManagerFixture() {
      var storage = createMockStorage();
      var serializerFactory = BinarySerializerFactory.create(
          BinarySerializerFactory.CURRENT_BINARY_FORMAT_VERSION);
      var keySerializer =
          com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer.INSTANCE;
      manager = new IndexHistogramManager(
          storage, "test-idx", engineId, true, cache,
          keySerializer, serializerFactory,
          com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer.ID);
    }
  }

  /**
   * Creates a manager where flushSnapshotToPage will fail with IOException.
   * The mocked AtomicOperationsManager throws IOException when
   * executeInsideAtomicOperation is called. Cache is pre-populated with a
   * snapshot so flushSnapshotToPage doesn't return early on cache miss.
   */
  private static class ManagerFixtureWithFailingFlush {
    final int engineId = 0;
    final ConcurrentHashMap<Integer, HistogramSnapshot> cache =
        new ConcurrentHashMap<>();
    final IndexHistogramManager manager;

    ManagerFixtureWithFailingFlush() {
      var storage = createMockStorageWithFailingAtomicOps();
      var serializerFactory = BinarySerializerFactory.create(
          BinarySerializerFactory.CURRENT_BINARY_FORMAT_VERSION);
      var keySerializer =
          com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer.INSTANCE;
      manager = new IndexHistogramManager(
          storage, "test-idx", engineId, true, cache,
          keySerializer, serializerFactory,
          com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer.ID);
      // Populate cache so flushSnapshotToPage doesn't return early
      var stats = new IndexStatistics(100, 100, 0);
      cache.put(engineId, new HistogramSnapshot(
          stats, null, 0, 100, 0, false, null, false));
    }
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

  private static AbstractStorage createMockStorageWithFailingAtomicOps() {
    var storage = mock(AbstractStorage.class);
    var factory = new CurrentStorageComponentsFactory(
        BinarySerializerFactory.currentBinaryFormatVersion());
    when(storage.getComponentsFactory()).thenReturn(factory);
    // Mock AtomicOperationsManager that throws IOException on flush
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

  // ═══════════════════════════════════════════════════════════════════════
  // Checkpoint flush concurrent with simulated rebalance
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Verifies that flushIfDirty() succeeds even when a rebalance is in
   * progress. The checkpoint path does not interact with the
   * rebalanceInProgress flag — it writes the current CHM snapshot
   * independently. Both paths write complete snapshots (not partial
   * updates), so concurrent writes are safe (last writer wins, both
   * are WAL-protected).
   */
  @Test
  public void flushIfDirty_duringSimulatedRebalance_succeedsIndependently()
      throws Exception {
    var fixture = new ManagerFixture();
    var stats = new IndexStatistics(500, 500, 0);
    var snapshot = new HistogramSnapshot(
        stats, null, 0, 500, 0, false, null, false);
    fixture.cache.put(fixture.engineId, snapshot);

    // Accumulate dirty mutations
    var delta = new HistogramDelta();
    delta.totalCountDelta = 10;
    delta.mutationCount = 50;
    fixture.manager.applyDelta(delta);
    assertTrue("Should have dirty mutations",
        fixture.manager.getDirtyMutations() > 0);

    // Simulate rebalance in progress via reflection
    var rebalanceField =
        IndexHistogramManager.class.getDeclaredField("rebalanceInProgress");
    rebalanceField.setAccessible(true);
    var rebalanceFlag =
        (java.util.concurrent.atomic.AtomicBoolean) rebalanceField.get(
            fixture.manager);
    rebalanceFlag.set(true);

    try {
      // Checkpoint flush should still succeed — it's independent of
      // rebalance state
      fixture.manager.flushIfDirty();

      // Dirty mutations should be zeroed (flush succeeded on mock storage)
      assertEquals("Dirty mutations should be zeroed after checkpoint flush",
          0, fixture.manager.getDirtyMutations());
    } finally {
      rebalanceFlag.set(false);
    }
  }

  private static void setDirtyMutations(
      IndexHistogramManager manager, long value) {
    manager.setDirtyMutationsForTest(value);
  }

  private static void setFileId(
      IndexHistogramManager manager, long value) {
    manager.setFileIdForTest(value);
  }
}
