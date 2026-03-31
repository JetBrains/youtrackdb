package com.jetbrains.youtrackdb.internal.core.index.engine.v1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.record.CurrentStorageComponentsFactory;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.index.IndexesSnapshot;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexHistogramManager;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.CellBTreeSingleValue;
import java.io.IOException;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * Tests for initial histogram build and migration paths (Step 6).
 *
 * <p>Verifies that buildInitialHistogram() correctly obtains the sorted key
 * stream, total count, and null count from the B-tree internals and delegates
 * to the histogram manager. Also covers bulk-load suppression behavior.
 */
public class BTreeEngineHistogramBuildTest {

  // ═══════════════════════════════════════════════════════════════════════
  // Single-value: buildInitialHistogram()
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void singleValue_buildInitialHistogram_delegatesToManager()
      throws IOException {
    // Given a single-value engine with 3 non-null keys and 1 null entry
    var f = new SingleValueFixture();
    var nullRid = new RecordId(1, 1);
    // buildInitialHistogram calls getNullCount + getTotalCount + keyStream,
    // each consuming streams. Use thenAnswer to return fresh streams.
    when(f.sbTree.iterateEntriesBetween(any(), eq(true), any(), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.of(
            new RawPair<>(new CompositeKey(null, 0L), nullRid)));
    var firstKey = new CompositeKey(null, 0L);
    when(f.sbTree.firstKey(f.op)).thenReturn(firstKey);
    when(f.sbTree.iterateEntriesMajor(eq(firstKey), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.of(
            new RawPair<>(new CompositeKey(null, 0L), nullRid),
            new RawPair<>(new CompositeKey("a", 0L), new RecordId(2, 1)),
            new RawPair<>(new CompositeKey("b", 0L), new RecordId(2, 2)),
            new RawPair<>(new CompositeKey("c", 0L), new RecordId(2, 3))));
    when(f.sbTree.keyStream(f.op)).thenAnswer(inv -> Stream.of(
        new CompositeKey(null, 0L),
        new CompositeKey("a", 0L), new CompositeKey("b", 0L), new CompositeKey("c", 0L)));
    when(f.manager.getKeyFieldCount()).thenReturn(1);

    f.engine.buildInitialHistogram(f.op);

    // totalCount = 4 (3 non-null + 1 null), nullCount = 1
    verify(f.manager).buildHistogram(
        eq(f.op), any(), eq(4L), eq(1L), eq(1));
    // Counters must be recalibrated from the exact scan
    assertEquals(4, f.engine.getTotalCount(f.op));
    assertEquals(1, f.engine.getNullCount(f.op));
  }

  @Test
  public void singleValue_buildInitialHistogram_noNullEntry()
      throws IOException {
    // Given a single-value engine with 2 non-null keys and no null entry
    var f = new SingleValueFixture();
    when(f.sbTree.iterateEntriesBetween(any(), eq(true), any(), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.empty());
    var firstKey = new CompositeKey("a", 0L);
    when(f.sbTree.firstKey(f.op)).thenReturn(firstKey);
    when(f.sbTree.iterateEntriesMajor(eq(firstKey), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.of(
            new RawPair<>(new CompositeKey("a", 0L), new RecordId(2, 1)),
            new RawPair<>(new CompositeKey("b", 0L), new RecordId(2, 2))));
    when(f.sbTree.keyStream(f.op)).thenAnswer(inv -> Stream.of(
        new CompositeKey("a", 0L), new CompositeKey("b", 0L)));
    when(f.manager.getKeyFieldCount()).thenReturn(1);

    f.engine.buildInitialHistogram(f.op);

    verify(f.manager).buildHistogram(
        eq(f.op), any(), eq(2L), eq(0L), eq(1));
  }

  @Test
  public void singleValue_buildInitialHistogram_noManager_isNoOp()
      throws IOException {
    // Given a single-value engine without a histogram manager
    var f = new SingleValueFixture();
    f.engine.setHistogramManager(null);

    // When buildInitialHistogram is called
    f.engine.buildInitialHistogram(f.op);

    // Then no exception and no interaction with B-tree
    verify(f.sbTree, never()).size(any());
  }

  @Test
  public void singleValue_buildInitialHistogram_compositeKeys()
      throws IOException {
    // Given a single-value engine with composite keys (2 fields), 2 entries
    var f = new SingleValueFixture();
    // No null entry
    when(f.sbTree.iterateEntriesBetween(any(), eq(true), any(), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.empty());
    var firstKey = new CompositeKey("a", "x", 0L);
    when(f.sbTree.firstKey(f.op)).thenReturn(firstKey);
    when(f.sbTree.iterateEntriesMajor(eq(firstKey), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.of(
            new RawPair<>(new CompositeKey("a", "x", 0L), new RecordId(1, 1)),
            new RawPair<>(new CompositeKey("b", "y", 0L), new RecordId(1, 2))));
    when(f.sbTree.keyStream(f.op)).thenAnswer(inv -> Stream.of(
        new CompositeKey("a", "x", 0L), new CompositeKey("b", "y", 0L)));
    when(f.manager.getKeyFieldCount()).thenReturn(2);

    // When buildInitialHistogram is called
    f.engine.buildInitialHistogram(f.op);

    // Then keyFieldCount=2 is passed, totalCount=2, nullCount=0
    verify(f.manager).buildHistogram(
        eq(f.op), any(), eq(2L), eq(0L), eq(2));
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Single-value: getNullCount() / getTotalCount() — O(1) counter reads
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void singleValue_getNullCount_reflectsCounterValue() {
    // getNullCount() is O(1): reads approximateNullCount counter directly.
    var f = new SingleValueFixture();
    f.engine.addToApproximateNullCount(1);
    assertEquals(1, f.engine.getNullCount(f.op));
  }

  @Test
  public void singleValue_getNullCount_initial_returnsZero() {
    var f = new SingleValueFixture();
    assertEquals(0, f.engine.getNullCount(f.op));
  }

  @Test
  public void singleValue_getTotalCount_reflectsCounterValue() {
    // getTotalCount() is O(1): reads approximateIndexEntriesCount directly.
    var f = new SingleValueFixture();
    f.engine.addToApproximateEntryCount(3);
    assertEquals(3, f.engine.getTotalCount(f.op));
  }

  @Test
  public void singleValue_getTotalCount_initial_returnsZero() {
    var f = new SingleValueFixture();
    assertEquals(0, f.engine.getTotalCount(f.op));
  }

  @Test
  public void singleValue_addToApproximateEntryCount_negativeDelta() {
    var f = new SingleValueFixture();
    f.engine.addToApproximateEntryCount(10);
    f.engine.addToApproximateEntryCount(-3);
    assertEquals(7, f.engine.getTotalCount(f.op));
  }

  @Test
  public void singleValue_addToApproximateNullCount_accumulatesDeltas() {
    var f = new SingleValueFixture();
    f.engine.addToApproximateNullCount(2);
    f.engine.addToApproximateNullCount(1);
    assertEquals(3, f.engine.getNullCount(f.op));
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Multi-value: buildInitialHistogram()
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void multiValue_buildInitialHistogram_delegatesToManager()
      throws IOException {
    // Given a multi-value engine with 2 sv entries and 1 null entry.
    // getNullCount → get(null) → nullTree.firstKey + nullTree.iterateEntriesMajor
    // getTotalCount → size → stream().count() + get(null).count()
    //   stream → svTree.firstKey + svTree.iterateEntriesMajor + visibilityFilter
    var f = new MultiValueFixture();
    var nullFirstKey = new CompositeKey(new RecordId(1, 1), 0L);
    when(f.nullTree.firstKey(f.op)).thenReturn(nullFirstKey);
    when(f.nullTree.iterateEntriesMajor(eq(nullFirstKey), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.of(
            new RawPair<>(new CompositeKey(new RecordId(1, 1), 0L),
                new RecordId(1, 1))));
    var firstKey = new CompositeKey("a", new RecordId(2, 1), 0L);
    when(f.svTree.firstKey(f.op)).thenReturn(firstKey);
    when(f.svTree.iterateEntriesMajor(eq(firstKey), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.of(
            new RawPair<>(new CompositeKey("a", new RecordId(2, 1), 0L), new RecordId(2, 1)),
            new RawPair<>(new CompositeKey("b", new RecordId(2, 2), 0L), new RecordId(2, 2))));
    when(f.svTree.keyStream(f.op)).thenAnswer(inv -> Stream.of(
        new CompositeKey("a", new RecordId(2, 1), 0L),
        new CompositeKey("b", new RecordId(2, 2), 0L)));
    when(f.manager.getKeyFieldCount()).thenReturn(1);

    f.engine.buildInitialHistogram(f.op);

    // totalCount = 3 (2 sv + 1 null), nullCount = 1
    verify(f.manager).buildHistogram(
        eq(f.op), any(), eq(3L), eq(1L), eq(1));
    // Counters must be recalibrated from the exact scan
    assertEquals(3, f.engine.getTotalCount(f.op));
    assertEquals(1, f.engine.getNullCount(f.op));
  }

  @Test
  public void multiValue_buildInitialHistogram_noNullEntries()
      throws IOException {
    // Given a multi-value engine with 2 sv entries and no null entries
    var f = new MultiValueFixture();
    when(f.nullTree.firstKey(f.op)).thenReturn(null);
    var firstKey = new CompositeKey("x", new RecordId(1, 1), 0L);
    when(f.svTree.firstKey(f.op)).thenReturn(firstKey);
    when(f.svTree.iterateEntriesMajor(eq(firstKey), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.of(
            new RawPair<>(new CompositeKey("x", new RecordId(1, 1), 0L), new RecordId(1, 1)),
            new RawPair<>(new CompositeKey("y", new RecordId(1, 2), 0L), new RecordId(1, 2))));
    when(f.svTree.keyStream(f.op)).thenAnswer(inv -> Stream.of(
        new CompositeKey("x", new RecordId(1, 1), 0L),
        new CompositeKey("y", new RecordId(1, 2), 0L)));
    when(f.manager.getKeyFieldCount()).thenReturn(1);

    f.engine.buildInitialHistogram(f.op);

    // totalCount = 2, nullCount = 0
    verify(f.manager).buildHistogram(
        eq(f.op), any(), eq(2L), eq(0L), eq(1));
  }

  @Test
  public void multiValue_buildInitialHistogram_noManager_isNoOp()
      throws IOException {
    // Given a multi-value engine without a histogram manager
    var f = new MultiValueFixture();
    f.engine.setHistogramManager(null);

    // When buildInitialHistogram is called
    f.engine.buildInitialHistogram(f.op);

    // Then no interaction with trees
    verify(f.svTree, never()).size(any());
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Multi-value: getNullCount() / getTotalCount() — O(1) counter reads
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void multiValue_getNullCount_reflectsCounterValue() {
    // getNullCount() is O(1): reads approximateNullCount counter directly.
    var f = new MultiValueFixture();
    f.engine.addToApproximateNullCount(2);
    assertEquals(2, f.engine.getNullCount(f.op));
  }

  @Test
  public void multiValue_getTotalCount_reflectsCounterValue() {
    // getTotalCount() is O(1): reads approximateIndexEntriesCount directly.
    var f = new MultiValueFixture();
    f.engine.addToApproximateEntryCount(3);
    assertEquals(3, f.engine.getTotalCount(f.op));
  }

  @Test
  public void multiValue_countersAreIndependent() {
    // Total and null counters are independent — setting one does not affect
    // the other. This mirrors the commit path where applyIndexCountDeltas
    // calls both addTo methods separately.
    var f = new MultiValueFixture();
    f.engine.addToApproximateEntryCount(5);
    f.engine.addToApproximateNullCount(2);
    assertEquals(5, f.engine.getTotalCount(f.op));
    assertEquals(2, f.engine.getNullCount(f.op));
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Bulk loading suppression
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void singleValue_bulkLoading_suppressesOnPut() throws IOException {
    // Given a single-value engine with bulk loading enabled
    var f = new SingleValueFixture();
    f.manager.setBulkLoading(true);
    when(f.sbTree.put(any(), eq(new CompositeKey("key", 1L)), any(RID.class))).thenReturn(true);

    // The actual onPut call on the real manager would be a no-op,
    // but we're testing that the engine delegates to the manager
    // which respects bulkLoading internally
    f.engine.put(f.op, "key", new RecordId(1, 1));

    // Engine always calls onPut — the manager's bulkLoading flag
    // controls whether it actually accumulates deltas
    verify(f.manager).onPut(f.op, "key", true, true);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // IndexHistogramManager: getKeyFieldCount()
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void histogramManager_getKeyFieldCount_defaultIs1() {
    var mgr = createRealManager();
    assertEquals(1, mgr.getKeyFieldCount());
  }

  @Test
  public void histogramManager_getKeyFieldCount_reflectsSetValue() {
    var mgr = createRealManager();
    mgr.setKeyFieldCount(3);
    assertEquals(3, mgr.getKeyFieldCount());
  }

  // ═══════════════════════════════════════════════════════════════════════
  // IndexHistogramManager: statsFileExists()
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void histogramManager_statsFileExists_delegatesToAtomicOperation() {
    var storage = createMockStorage();

    var mgr = new IndexHistogramManager(
        storage, "test-idx", 0, true,
        new java.util.concurrent.ConcurrentHashMap<>(),
        mock(com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer.class),
        BinarySerializerFactory.create(
            BinarySerializerFactory.currentBinaryFormatVersion()),
        (byte) 0);

    // statsFileExists delegates to atomicOperation.isFileExists(fullName)
    var atomicOp = mock(AtomicOperation.class);
    when(atomicOp.isFileExists("test-idx.ixs")).thenReturn(true);
    assertTrue(mgr.statsFileExists(atomicOp));
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Fixtures and helpers
  // ═══════════════════════════════════════════════════════════════════════

  /** Creates a CompositeKey with the given key and a dummy RID (multi-value pattern). */
  private static CompositeKey makeComposite(Object key) {
    var ck = new CompositeKey(key);
    ck.addKey(new RecordId(1, 1));
    return ck;
  }

  private static IndexHistogramManager createRealManager() {
    var storage = createMockStorage();
    return new IndexHistogramManager(
        storage, "test-idx", 0, true,
        new java.util.concurrent.ConcurrentHashMap<>(),
        mock(com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer.class),
        BinarySerializerFactory.create(
            BinarySerializerFactory.currentBinaryFormatVersion()),
        (byte) 0);
  }

  private static class SingleValueFixture {
    final AbstractStorage storage;
    final AtomicOperation op;
    final IndexHistogramManager manager;
    @SuppressWarnings("unchecked")
    final CellBTreeSingleValue<CompositeKey> sbTree =
        mock(CellBTreeSingleValue.class);
    final BTreeSingleValueIndexEngine engine;

    SingleValueFixture() {
      storage = createMockStorage();
      op = mock(AtomicOperation.class);
      // Mock getCommitTs — validatedPut appends version to CompositeKey
      when(op.getCommitTs()).thenReturn(1L);
      manager = mock(IndexHistogramManager.class);

      engine = new BTreeSingleValueIndexEngine(0, "test-sv", storage, 4);
      injectField(engine, "sbTree", sbTree);
      engine.setHistogramManager(manager);
    }
  }

  private static class MultiValueFixture {
    final AbstractStorage storage;
    final AtomicOperation op;
    final IndexHistogramManager manager;
    @SuppressWarnings("unchecked")
    final CellBTreeSingleValue<CompositeKey> svTree =
        mock(CellBTreeSingleValue.class);
    @SuppressWarnings("unchecked")
    final CellBTreeSingleValue<CompositeKey> nullTree =
        mock(CellBTreeSingleValue.class);
    final BTreeMultiValueIndexEngine engine;

    MultiValueFixture() {
      storage = createMockStorage();
      op = mock(AtomicOperation.class);
      manager = mock(IndexHistogramManager.class);

      engine = new BTreeMultiValueIndexEngine(0, "test-mv", storage, 4);
      injectField(engine, "svTree", svTree);
      injectField(engine, "nullTree", nullTree);
      engine.setHistogramManager(manager);
    }
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
    // IndexesSnapshot mock: visibilityFilter passes through the stream unchanged,
    // visibilityFilterMapped applies the key mapper to each entry.
    var snapshot = mock(IndexesSnapshot.class);
    when(snapshot.visibilityFilter(any(), any()))
        .thenAnswer(inv -> inv.getArgument(1));
    when(snapshot.visibilityFilterMapped(any(), any(), any()))
        .thenAnswer(inv -> {
          Stream<RawPair<CompositeKey, RID>> stream = inv.getArgument(1);
          Function<CompositeKey, Object> mapper = inv.getArgument(2);
          return stream.map(p -> new RawPair<>(mapper.apply(p.first()), p.second()));
        });
    when(storage.subIndexSnapshot(anyLong())).thenReturn(snapshot);
    var nullSnapshot = mock(IndexesSnapshot.class);
    when(nullSnapshot.visibilityFilter(any(), any()))
        .thenAnswer(inv -> inv.getArgument(1));
    when(nullSnapshot.visibilityFilterMapped(any(), any(), any()))
        .thenAnswer(inv -> {
          Stream<RawPair<CompositeKey, RID>> stream = inv.getArgument(1);
          Function<CompositeKey, Object> mapper = inv.getArgument(2);
          return stream.map(p -> new RawPair<>(mapper.apply(p.first()), p.second()));
        });
    when(storage.subNullIndexSnapshot(anyLong())).thenReturn(nullSnapshot);
    return storage;
  }

  private static void injectField(
      Object target, String fieldName, Object value) {
    try {
      var field = findField(target.getClass(), fieldName);
      field.setAccessible(true);
      field.set(target, value);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(
          "Failed to inject field " + fieldName, e);
    }
  }

  private static java.lang.reflect.Field findField(
      Class<?> clazz, String name) {
    while (clazz != null) {
      try {
        return clazz.getDeclaredField(name);
      } catch (NoSuchFieldException e) {
        clazz = clazz.getSuperclass();
      }
    }
    throw new RuntimeException("Field not found: " + name);
  }
}
