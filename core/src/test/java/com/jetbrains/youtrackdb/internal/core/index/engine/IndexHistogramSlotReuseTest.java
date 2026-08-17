package com.jetbrains.youtrackdb.internal.core.index.engine;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.Test;

/** Verifies that a detached histogram manager cannot publish into a reused engine slot. */
public class IndexHistogramSlotReuseTest {

  /** U1 covers the counters-only build publication after the early size exit. */
  @Test
  public void detachedManagerCannotPublishSmallBuild() throws Exception {
    var fixture = new Fixture();
    var replacement = fixture.detachAndSeedReplacement();

    fixture.manager.buildHistogram(fixture.operation, Stream.empty(), 1, 0, 1);

    assertSame(replacement, fixture.cache.get(fixture.engineId));
  }

  /** U2 covers the counters-only build publication after an empty full-size scan. */
  @Test
  public void detachedManagerCannotPublishEmptyFullSizeBuild() throws Exception {
    var fixture = new Fixture();
    var replacement = fixture.detachAndSeedReplacement();

    fixture.manager.buildHistogram(fixture.operation, Stream.empty(), 1_000, 0, 1);

    assertSame(replacement, fixture.cache.get(fixture.engineId));
  }

  /** U3 covers the complete histogram publication after a full key scan. */
  @Test
  public void detachedManagerCannotPublishCompleteBuild() throws Exception {
    var fixture = new Fixture();
    var replacement = fixture.detachAndSeedReplacement();
    var keys = IntStream.range(0, 1_000).boxed().map(value -> (Object) value);

    fixture.manager.buildHistogram(fixture.operation, keys, 1_000, 0, 1);

    assertSame(replacement, fixture.cache.get(fixture.engineId));
  }

  /** U4 covers the empty snapshot publication used when an index is cleared. */
  @Test
  public void detachedManagerCannotPublishClearReset() throws Exception {
    var fixture = new Fixture();
    var replacement = fixture.detachAndSeedReplacement();

    fixture.manager.resetOnClear(fixture.operation);

    assertSame(replacement, fixture.cache.get(fixture.engineId));
  }

  /** U5 detaches at the publication seam after synchronous analysis has scanned its stale keys. */
  @Test
  public void detachAtPublicationPreventsRebalanceMerge() {
    var fixture = new Fixture();
    fixture.manager.setFileIdForTest(42);
    fixture.cache.put(fixture.engineId, snapshot(8));
    fixture.manager.setKeyStreamSupplier(
        operation -> IntStream.range(0, 8).boxed().map(value -> (Object) value));
    var replacement = snapshot(77);
    fixture.installDetachAndSeedHook(replacement);

    try {
      assertSame(replacement, fixture.manager.analyzeIndex());
    } finally {
      fixture.manager.setPrePublicationTestHook(null);
    }

    assertTrue(fixture.manager.isDetached());
    assertSame(replacement, fixture.cache.get(fixture.engineId));
  }

  /** U6 detaches at the publication seam before a stale transaction delta can merge. */
  @Test
  public void detachAtPublicationPreventsDeltaMerge() {
    var fixture = new Fixture();
    fixture.cache.put(fixture.engineId, snapshot(8));
    var replacement = snapshot(77);
    fixture.installDetachAndSeedHook(replacement);
    var delta = new HistogramDelta();
    delta.totalCountDelta = 1;
    delta.mutationCount = 1;

    try {
      fixture.manager.applyDelta(delta);
    } finally {
      fixture.manager.setPrePublicationTestHook(null);
    }

    assertTrue(fixture.manager.isDetached());
    assertSame(replacement, fixture.cache.get(fixture.engineId));
  }

  /** U7 proves a detached dirty manager does not open an operation to flush replacement state. */
  @Test
  public void detachedManagerCannotFlushReplacementSnapshot() throws Exception {
    var fixture = new Fixture();
    fixture.manager.setFileIdForTest(42);
    var replacement = fixture.detachAndSeedReplacement();
    fixture.manager.setDirtyMutationsForTest(1);

    fixture.manager.flushIfDirty();
    fixture.manager.flushIfDirty(fixture.operation);

    assertSame(replacement, fixture.cache.get(fixture.engineId));
    verify(fixture.atomicOperationsManager, never()).executeInsideAtomicOperation(any());
  }

  /** U8 proves detached managers cannot submit background histogram work. */
  @Test
  public void detachedManagerCannotScheduleHistogramWork() {
    var fixture = new Fixture();
    fixture.detachAndSeedReplacement();
    var executor = mock(ExecutorService.class);

    fixture.manager.maybeScheduleHistogramWork(executor);

    verify(executor, never()).submit(any(Runnable.class));
  }

  /** U9 proves detached analysis returns before consulting the stale key supplier. */
  @Test
  public void detachedManagerCannotAnalyzeStaleKeys() {
    var fixture = new Fixture();
    fixture.detachAndSeedReplacement();
    var scans = new AtomicInteger();
    fixture.manager.setKeyStreamSupplier(operation -> {
      scans.incrementAndGet();
      return Stream.of(1);
    });

    assertNull(fixture.manager.analyzeIndex());
    assertTrue("detached analysis must not scan keys", scans.get() == 0);
  }

  private static HistogramSnapshot snapshot(long totalCount) {
    return new HistogramSnapshot(
        new IndexStatistics(totalCount, totalCount, 0),
        null,
        0,
        totalCount,
        0,
        false,
        null,
        false);
  }

  private static final class Fixture {
    private final int engineId = 7;
    private final ConcurrentHashMap<Integer, HistogramSnapshot> cache =
        new ConcurrentHashMap<>();
    private final AtomicOperation operation = mock(AtomicOperation.class);
    private final AtomicOperationsManager atomicOperationsManager =
        mock(AtomicOperationsManager.class);
    private final IndexHistogramManager manager;

    private Fixture() {
      var storage = mock(AbstractStorage.class);
      var factory = new CurrentStorageComponentsFactory(
          BinarySerializerFactory.currentBinaryFormatVersion());
      when(storage.getComponentsFactory()).thenReturn(factory);
      when(storage.getAtomicOperationsManager()).thenReturn(atomicOperationsManager);
      when(storage.getReadCache()).thenReturn(mock(ReadCache.class));
      when(storage.getWriteCache()).thenReturn(mock(WriteCache.class));
      when(atomicOperationsManager.startAtomicOperation()).thenReturn(operation);
      manager = new IndexHistogramManager(
          storage,
          "slot-reuse-test",
          engineId,
          true,
          cache,
          IntegerSerializer.INSTANCE,
          BinarySerializerFactory.create(
              BinarySerializerFactory.CURRENT_BINARY_FORMAT_VERSION),
          IntegerSerializer.ID);
    }

    private HistogramSnapshot detachAndSeedReplacement() {
      manager.detach();
      var replacement = snapshot(77);
      cache.put(engineId, replacement);
      return replacement;
    }

    private void installDetachAndSeedHook(HistogramSnapshot replacement) {
      manager.setPrePublicationTestHook(() -> {
        manager.setPrePublicationTestHook(null);
        manager.detach();
        cache.put(engineId, replacement);
      });
    }
  }
}
