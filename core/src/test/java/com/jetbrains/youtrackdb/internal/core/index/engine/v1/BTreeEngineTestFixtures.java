package com.jetbrains.youtrackdb.internal.core.index.engine.v1;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.record.CurrentStorageComponentsFactory;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.index.IndexesSnapshot;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexCountDeltaHolder;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexHistogramManager;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.CellBTreeSingleValue;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Shared test fixtures and helper methods for BTreeEngine unit tests.
 *
 * <p>Provides mock storage creation, reflective field injection, and
 * pre-wired fixture classes for single-value and multi-value B-tree
 * index engines.
 */
final class BTreeEngineTestFixtures {

  private BTreeEngineTestFixtures() {
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Mock storage factory
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Creates a fully-mocked {@link AbstractStorage} with:
   * <ul>
   *   <li>ComponentsFactory backed by the current binary serializer version</li>
   *   <li>AtomicOperationsManager that returns a mock AtomicOperation</li>
   *   <li>ReadCache and WriteCache mocks</li>
   *   <li>IndexesSnapshot mocks (with visibility filter pass-through) for
   *       both {@code subIndexSnapshot} and {@code subNullIndexSnapshot}</li>
   * </ul>
   */
  static AbstractStorage createMockStorage() {
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
    when(nullSnapshot.visibilityFilterValues(any(), any()))
        .thenAnswer(inv -> {
          Stream<RawPair<CompositeKey, RID>> stream = inv.getArgument(1);
          return stream.map(RawPair::second);
        });
    when(nullSnapshot.visibilityFilterMapped(any(), any(), any()))
        .thenAnswer(inv -> {
          Stream<RawPair<CompositeKey, RID>> stream = inv.getArgument(1);
          Function<CompositeKey, Object> mapper = inv.getArgument(2);
          return stream.map(p -> new RawPair<>(mapper.apply(p.first()), p.second()));
        });
    when(storage.subNullIndexSnapshot(anyLong())).thenReturn(nullSnapshot);
    return storage;
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Reflective field injection
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Injects a value into a (possibly inherited) private field via reflection.
   */
  static void injectField(Object target, String fieldName, Object value) {
    try {
      var field = findField(target.getClass(), fieldName);
      field.setAccessible(true);
      field.set(target, value);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(
          "Failed to inject field " + fieldName, e);
    }
  }

  /**
   * Walks the class hierarchy to find a declared field by name.
   */
  static java.lang.reflect.Field findField(Class<?> clazz, String name) {
    while (clazz != null) {
      try {
        return clazz.getDeclaredField(name);
      } catch (NoSuchFieldException e) {
        clazz = clazz.getSuperclass();
      }
    }
    throw new RuntimeException("Field not found: " + name);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Fixture classes
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Test fixture for {@link BTreeSingleValueIndexEngine} with a mocked
   * B-tree, storage, atomic operation, and histogram manager.
   *
   * <p>The atomic operation is pre-configured with:
   * <ul>
   *   <li>{@code getCommitTs()} returning {@code 1L}</li>
   *   <li>{@code getOrCreateIndexCountDeltas()} returning a fresh holder</li>
   * </ul>
   */
  static class SingleValueFixture {
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
      // Mock getCommitTs — validatedPut/remove append version to CompositeKey
      when(op.getCommitTs()).thenReturn(1L);
      // Mock index count delta holder — put/remove accumulate deltas here
      when(op.getOrCreateIndexCountDeltas()).thenReturn(new IndexCountDeltaHolder());
      manager = mock(IndexHistogramManager.class);

      engine = new BTreeSingleValueIndexEngine(0, "test-sv", storage, 4);
      // Replace the real sbTree with our mock via reflection
      injectField(engine, "sbTree", sbTree);
      engine.setHistogramManager(manager);
    }
  }

  /**
   * Test fixture for {@link BTreeMultiValueIndexEngine} with mocked trees
   * (svTree + nullTree), storage, atomic operation, and histogram manager.
   *
   * <p>The atomic operation is pre-configured with:
   * <ul>
   *   <li>{@code getCommitTs()} returning {@code 1L}</li>
   *   <li>{@code getOrCreateIndexCountDeltas()} returning a fresh holder</li>
   * </ul>
   */
  static class MultiValueFixture {
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
      // Mock getCommitTs — remove appends version to CompositeKey
      when(op.getCommitTs()).thenReturn(1L);
      // Mock index count delta holder — put/remove accumulate deltas here
      when(op.getOrCreateIndexCountDeltas()).thenReturn(new IndexCountDeltaHolder());
      manager = mock(IndexHistogramManager.class);

      engine = new BTreeMultiValueIndexEngine(0, "test-mv", storage, 4);
      injectField(engine, "svTree", svTree);
      injectField(engine, "nullTree", nullTree);
      engine.setHistogramManager(manager);
    }
  }
}
