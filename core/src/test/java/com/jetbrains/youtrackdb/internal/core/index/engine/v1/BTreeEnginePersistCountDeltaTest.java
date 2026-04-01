package com.jetbrains.youtrackdb.internal.core.index.engine.v1;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.db.record.CurrentStorageComponentsFactory;
import com.jetbrains.youtrackdb.internal.core.index.IndexesSnapshot;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.CellBTreeSingleValue;
import org.junit.Test;

/**
 * Tests for {@link BTreeIndexEngine#persistCountDelta(AtomicOperation, long, long)}.
 *
 * <p>Verifies that single-value engine forwards the full totalDelta to its
 * single tree, and multi-value engine correctly splits the delta across svTree
 * (non-null entries) and nullTree (null entries).
 */
public class BTreeEnginePersistCountDeltaTest {

  // ═══════════════════════════════════════════════════════════════════════
  // Single-value engine
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Single-value engine applies the full totalDelta to sbTree. The nullDelta
   * is ignored because null and non-null entries share the same BTree.
   */
  @Test
  public void singleValue_persistCountDelta_forwardsFullDeltaToSbTree() {
    var f = new SingleValueFixture();

    f.engine.persistCountDelta(f.op, 10, 1);

    // Full totalDelta (10) forwarded to sbTree, nullDelta (1) is ignored
    verify(f.sbTree).addToApproximateEntriesCount(f.op, 10);
    verifyNoMoreInteractions(f.sbTree);
  }

  /**
   * Single-value engine correctly handles negative totalDelta (removals
   * exceeding additions in a transaction).
   */
  @Test
  public void singleValue_persistCountDelta_handlesNegativeDelta() {
    var f = new SingleValueFixture();

    // nullDelta (2) should be ignored — sbTree still gets -3, not -5
    f.engine.persistCountDelta(f.op, -3, 2);

    verify(f.sbTree).addToApproximateEntriesCount(f.op, -3);
    verifyNoMoreInteractions(f.sbTree);
  }

  /**
   * Single-value engine correctly handles zero delta (no net change in a
   * transaction, e.g. equal additions and removals).
   */
  @Test
  public void singleValue_persistCountDelta_handlesZeroDelta() {
    var f = new SingleValueFixture();

    // totalDelta=0 forwarded as-is; nullDelta=3 is ignored
    f.engine.persistCountDelta(f.op, 0, 3);

    verify(f.sbTree).addToApproximateEntriesCount(f.op, 0);
    verifyNoMoreInteractions(f.sbTree);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Multi-value engine
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Multi-value engine splits delta: svTree gets (totalDelta - nullDelta) for
   * non-null entries, nullTree gets nullDelta for null entries.
   */
  @Test
  public void multiValue_persistCountDelta_splitsDeltaAcrossTrees() {
    var f = new MultiValueFixture();

    f.engine.persistCountDelta(f.op, 10, 3);

    // svTree: 10 - 3 = 7 non-null entries
    verify(f.svTree).addToApproximateEntriesCount(f.op, 7);
    // nullTree: 3 null entries
    verify(f.nullTree).addToApproximateEntriesCount(f.op, 3);
    verifyNoMoreInteractions(f.svTree, f.nullTree);
  }

  /**
   * Multi-value engine handles all-null delta (totalDelta equals nullDelta):
   * svTree gets 0, nullTree gets the full delta.
   */
  @Test
  public void multiValue_persistCountDelta_allNullEntries() {
    var f = new MultiValueFixture();

    f.engine.persistCountDelta(f.op, 5, 5);

    verify(f.svTree).addToApproximateEntriesCount(f.op, 0);
    verify(f.nullTree).addToApproximateEntriesCount(f.op, 5);
    verifyNoMoreInteractions(f.svTree, f.nullTree);
  }

  /**
   * Multi-value engine handles no-null delta (nullDelta is 0): svTree gets
   * the full totalDelta, nullTree gets 0.
   */
  @Test
  public void multiValue_persistCountDelta_noNullEntries() {
    var f = new MultiValueFixture();

    f.engine.persistCountDelta(f.op, 8, 0);

    verify(f.svTree).addToApproximateEntriesCount(f.op, 8);
    verify(f.nullTree).addToApproximateEntriesCount(f.op, 0);
    verifyNoMoreInteractions(f.svTree, f.nullTree);
  }

  /**
   * Multi-value engine handles negative deltas (removals in a transaction).
   */
  @Test
  public void multiValue_persistCountDelta_handlesNegativeDeltas() {
    var f = new MultiValueFixture();

    f.engine.persistCountDelta(f.op, -4, -1);

    // svTree: -4 - (-1) = -3
    verify(f.svTree).addToApproximateEntriesCount(f.op, -3);
    // nullTree: -1
    verify(f.nullTree).addToApproximateEntriesCount(f.op, -1);
    verifyNoMoreInteractions(f.svTree, f.nullTree);
  }

  /**
   * Multi-value engine handles zero delta (no net change).
   */
  @Test
  public void multiValue_persistCountDelta_handlesZeroDelta() {
    var f = new MultiValueFixture();

    f.engine.persistCountDelta(f.op, 0, 0);

    verify(f.svTree).addToApproximateEntriesCount(f.op, 0);
    verify(f.nullTree).addToApproximateEntriesCount(f.op, 0);
    verifyNoMoreInteractions(f.svTree, f.nullTree);
  }

  /**
   * Multi-value engine: nullDelta exceeds totalDelta, resulting in a negative
   * non-null delta to svTree. This occurs when a transaction removes more
   * non-null entries than it adds while also adding null entries.
   */
  @Test
  public void multiValue_persistCountDelta_nullDeltaExceedsTotalDelta() {
    var f = new MultiValueFixture();

    f.engine.persistCountDelta(f.op, 2, 5);

    // svTree: 2 - 5 = -3 (non-null entries decreased)
    verify(f.svTree).addToApproximateEntriesCount(f.op, -3);
    // nullTree: 5
    verify(f.nullTree).addToApproximateEntriesCount(f.op, 5);
    verifyNoMoreInteractions(f.svTree, f.nullTree);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Test fixtures
  // ═══════════════════════════════════════════════════════════════════════

  private static class SingleValueFixture {
    final AtomicOperation op = mock(AtomicOperation.class);
    @SuppressWarnings("unchecked")
    final CellBTreeSingleValue<Object> sbTree =
        mock(CellBTreeSingleValue.class);
    final BTreeSingleValueIndexEngine engine;

    SingleValueFixture() {
      var storage = createMockStorage();
      engine = new BTreeSingleValueIndexEngine(0, "test-sv", storage, 4);
      injectField(engine, "sbTree", sbTree);
    }
  }

  private static class MultiValueFixture {
    final AtomicOperation op = mock(AtomicOperation.class);
    @SuppressWarnings("unchecked")
    final CellBTreeSingleValue<Object> svTree =
        mock(CellBTreeSingleValue.class);
    @SuppressWarnings("unchecked")
    final CellBTreeSingleValue<Object> nullTree =
        mock(CellBTreeSingleValue.class);
    final BTreeMultiValueIndexEngine engine;

    MultiValueFixture() {
      var storage = createMockStorage();
      engine = new BTreeMultiValueIndexEngine(0, "test-mv", storage, 4);
      injectField(engine, "svTree", svTree);
      injectField(engine, "nullTree", nullTree);
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
    var snapshot = mock(IndexesSnapshot.class);
    when(storage.subIndexSnapshot(anyLong())).thenReturn(snapshot);
    var nullSnapshot = mock(IndexesSnapshot.class);
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
