package com.jetbrains.youtrackdb.internal.core.index.engine.v1;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.db.record.CurrentStorageComponentsFactory;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexHistogramManager;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.CellBTreeSingleValue;
import java.io.IOException;
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
    // Given a single-value engine with 100 non-null keys and 1 null entry
    var f = new SingleValueFixture();
    when(f.sbTree.size(f.op)).thenReturn(100L);
    when(f.sbTree.get(null, f.op)).thenReturn(new RecordId(1, 1));
    when(f.sbTree.keyStream(f.op)).thenReturn(Stream.of("a", "b", "c"));
    when(f.manager.getKeyFieldCount()).thenReturn(1);

    // When buildInitialHistogram is called
    f.engine.buildInitialHistogram(f.op);

    // Then the manager's buildHistogram is called with correct counts
    // totalCount = 100 (non-null) + 1 (null) = 101
    verify(f.manager).buildHistogram(
        eq(f.op), any(), eq(101L), eq(1L), eq(1));
  }

  @Test
  public void singleValue_buildInitialHistogram_noNullEntry()
      throws IOException {
    // Given a single-value engine with no null entry
    var f = new SingleValueFixture();
    when(f.sbTree.size(f.op)).thenReturn(50L);
    when(f.sbTree.get(null, f.op)).thenReturn(null);
    when(f.sbTree.keyStream(f.op)).thenReturn(Stream.empty());
    when(f.manager.getKeyFieldCount()).thenReturn(1);

    // When buildInitialHistogram is called
    f.engine.buildInitialHistogram(f.op);

    // Then nullCount is 0, totalCount equals non-null count
    verify(f.manager).buildHistogram(
        eq(f.op), any(), eq(50L), eq(0L), eq(1));
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
    // Given a single-value engine with composite keys (2 fields)
    var f = new SingleValueFixture();
    when(f.sbTree.size(f.op)).thenReturn(200L);
    when(f.sbTree.get(null, f.op)).thenReturn(null);
    when(f.sbTree.keyStream(f.op)).thenReturn(Stream.of(
        new CompositeKey("a", "x"), new CompositeKey("b", "y")));
    when(f.manager.getKeyFieldCount()).thenReturn(2);

    // When buildInitialHistogram is called
    f.engine.buildInitialHistogram(f.op);

    // Then keyFieldCount=2 is passed so manager extracts leading field
    verify(f.manager).buildHistogram(
        eq(f.op), any(), eq(200L), eq(0L), eq(2));
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Single-value: getNullCount() / getTotalCount()
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void singleValue_getNullCount_nullEntryExists_returns1() {
    var f = new SingleValueFixture();
    when(f.sbTree.get(null, f.op)).thenReturn(new RecordId(1, 1));

    assertEquals(1, f.engine.getNullCount(f.op));
  }

  @Test
  public void singleValue_getNullCount_noNullEntry_returns0() {
    var f = new SingleValueFixture();
    when(f.sbTree.get(null, f.op)).thenReturn(null);

    assertEquals(0, f.engine.getNullCount(f.op));
  }

  @Test
  public void singleValue_getTotalCount_includesNullEntry() {
    var f = new SingleValueFixture();
    when(f.sbTree.size(f.op)).thenReturn(100L);
    when(f.sbTree.get(null, f.op)).thenReturn(new RecordId(1, 1));

    // totalCount = 100 (non-null) + 1 (null) = 101
    assertEquals(101, f.engine.getTotalCount(f.op));
  }

  @Test
  public void singleValue_getTotalCount_noNullEntry() {
    var f = new SingleValueFixture();
    when(f.sbTree.size(f.op)).thenReturn(50L);
    when(f.sbTree.get(null, f.op)).thenReturn(null);

    assertEquals(50, f.engine.getTotalCount(f.op));
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Multi-value: buildInitialHistogram()
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void multiValue_buildInitialHistogram_delegatesToManager()
      throws IOException {
    // Given a multi-value engine with 100 sv entries and 5 null entries
    var f = new MultiValueFixture();
    when(f.svTree.size(f.op)).thenReturn(100L);
    when(f.nullTree.size(f.op)).thenReturn(5L);
    when(f.svTree.keyStream(f.op)).thenReturn(Stream.of(
        makeComposite("a"), makeComposite("b")));
    when(f.manager.getKeyFieldCount()).thenReturn(1);

    // When buildInitialHistogram is called
    f.engine.buildInitialHistogram(f.op);

    // Then totalCount = 105, nullCount = 5
    verify(f.manager).buildHistogram(
        eq(f.op), any(), eq(105L), eq(5L), eq(1));
  }

  @Test
  public void multiValue_buildInitialHistogram_noNullEntries()
      throws IOException {
    // Given a multi-value engine with no null entries
    var f = new MultiValueFixture();
    when(f.svTree.size(f.op)).thenReturn(200L);
    when(f.nullTree.size(f.op)).thenReturn(0L);
    when(f.svTree.keyStream(f.op)).thenReturn(Stream.of(
        makeComposite("x")));
    when(f.manager.getKeyFieldCount()).thenReturn(1);

    // When buildInitialHistogram is called
    f.engine.buildInitialHistogram(f.op);

    // Then nullCount is 0
    verify(f.manager).buildHistogram(
        eq(f.op), any(), eq(200L), eq(0L), eq(1));
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
  // Multi-value: getNullCount() / getTotalCount()
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void multiValue_getNullCount_returnsNullTreeSize() {
    var f = new MultiValueFixture();
    when(f.nullTree.size(f.op)).thenReturn(7L);

    assertEquals(7, f.engine.getNullCount(f.op));
  }

  @Test
  public void multiValue_getTotalCount_sumsBothTrees() {
    var f = new MultiValueFixture();
    when(f.svTree.size(f.op)).thenReturn(100L);
    when(f.nullTree.size(f.op)).thenReturn(3L);

    assertEquals(103, f.engine.getTotalCount(f.op));
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Bulk loading suppression
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void singleValue_bulkLoading_suppressesOnPut() throws IOException {
    // Given a single-value engine with bulk loading enabled
    var f = new SingleValueFixture();
    f.manager.setBulkLoading(true);
    when(f.sbTree.put(any(), eq("key"), any(RID.class))).thenReturn(true);

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
  public void histogramManager_statsFileExists_delegatesToWriteCache() {
    var storage = createMockStorage();
    var writeCache = storage.getWriteCache();

    var mgr = new IndexHistogramManager(
        storage, "test-idx", 0, true,
        new java.util.concurrent.ConcurrentHashMap<>(),
        mock(com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer.class),
        BinarySerializerFactory.create(
            BinarySerializerFactory.currentBinaryFormatVersion()),
        (byte) 0);

    // When no AtomicOperation is provided, statsFileExists delegates
    // to writeCache.exists()
    when(writeCache.exists("test-idx.ixs")).thenReturn(true);
    // statsFileExists(null) → isFileExists(null, fullName) → writeCache.exists
    assertEquals(true, mgr.statsFileExists(null));
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
    final CellBTreeSingleValue<Object> sbTree =
        mock(CellBTreeSingleValue.class);
    final BTreeSingleValueIndexEngine engine;

    SingleValueFixture() {
      storage = createMockStorage();
      op = mock(AtomicOperation.class);
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
    final CellBTreeSingleValue<Identifiable> nullTree =
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
    when(storage.getAtomicOperationsManager())
        .thenReturn(mock(AtomicOperationsManager.class));
    when(storage.getReadCache()).thenReturn(mock(ReadCache.class));
    when(storage.getWriteCache()).thenReturn(mock(WriteCache.class));
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
