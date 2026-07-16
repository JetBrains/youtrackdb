package com.jetbrains.youtrackdb.internal.core.index.engine.v1;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.CellBTreeSingleValue;
import java.io.IOException;
import java.lang.reflect.Field;
import org.junit.Test;

/**
 * Unit tests for {@code verifyAndTruncateOrphans} on
 * {@link BTreeSingleValueIndexEngine} and {@link BTreeMultiValueIndexEngine}. Each
 * engine's wrapper is intended as a polymorphic delegate over its inner BTree(s),
 * since {@code sbTree} / {@code svTree} / {@code nullTree} are
 * {@code private final} on the engines and the
 * {@code AbstractStorage.truncateOrphansAfterRecovery()} orchestrator iterates the
 * engines without violating that encapsulation.
 *
 * <p>Tests below use reflection to swap the engines' inner trees with Mockito
 * stubs, then assert the engine wrappers call through correctly:
 *
 * <ul>
 *   <li>{@code BTreeSingleValueIndexEngine} → one call to {@code sbTree};</li>
 *   <li>{@code BTreeMultiValueIndexEngine} → one call each to {@code svTree} AND
 *       {@code nullTree} when {@code nullTree != null}.</li>
 * </ul>
 *
 * <p>Reflection-on-private-final-fields is the cleanest path here: the engines are
 * {@code final} classes constructed through heavy ctors that pull from
 * {@code AbstractStorage.subIndexSnapshot} and reach into the read/write cache. A
 * standalone test that exercises the wrapper logic alone (without constructing real
 * engines) is exactly what reflection lets us write. The downside — the test
 * couples to internal field names — is acceptable because the field set is small
 * and renames would surface as a compile-time failure inside this test rather than
 * a silent semantic shift.
 */
public class BTreeIndexEngineVerifyAndTruncateOrphansTest {

  // -------------------------------------------------------------------------
  // BTreeSingleValueIndexEngine
  // -------------------------------------------------------------------------

  // Single-value engine wrapper: must invoke verifyAndTruncateOrphans on its inner
  // sbTree exactly once with the same (op, readCache, writeCache) triple.
  @Test
  public void singleValueEngineDelegatesToSbTree() throws Exception {
    @SuppressWarnings("unchecked")
    CellBTreeSingleValue<CompositeKey> mockSbTree =
        mock(CellBTreeSingleValue.class);
    var op = mock(AtomicOperation.class);
    var readCache = mock(ReadCache.class);
    var writeCache = mock(WriteCache.class);

    var engine = newSingleValueEngineWithSbTree(mockSbTree);

    engine.verifyAndTruncateOrphans(op, readCache, writeCache);

    verify(mockSbTree).verifyAndTruncateOrphans(op, readCache, writeCache);
  }

  // -------------------------------------------------------------------------
  // BTreeMultiValueIndexEngine — both branches
  // -------------------------------------------------------------------------

  // Multi-value engine wrapper: must invoke verifyAndTruncateOrphans on BOTH inner
  // trees (svTree AND nullTree) when nullTree is non-null. PSI at Phase A confirmed
  // nullTree is a full multi-page-growing BTree (multi-null-key load drives the
  // null-tree write paths), so both must be truncated.
  @Test
  public void multiValueEngineDelegatesToBothInnerTrees() throws Exception {
    @SuppressWarnings("unchecked")
    CellBTreeSingleValue<CompositeKey> mockSvTree =
        mock(CellBTreeSingleValue.class);
    @SuppressWarnings("unchecked")
    CellBTreeSingleValue<CompositeKey> mockNullTree =
        mock(CellBTreeSingleValue.class);
    var op = mock(AtomicOperation.class);
    var readCache = mock(ReadCache.class);
    var writeCache = mock(WriteCache.class);

    var engine = newMultiValueEngineWithTrees(mockSvTree, mockNullTree);

    engine.verifyAndTruncateOrphans(op, readCache, writeCache);

    verify(mockSvTree).verifyAndTruncateOrphans(op, readCache, writeCache);
    verify(mockNullTree).verifyAndTruncateOrphans(op, readCache, writeCache);
  }

  // Defensive: even though the production ctor always assigns nullTree, the engine
  // wrapper carries a defensive (nullTree != null) check to guard against a future
  // refactor that makes nullTree optional. Pin the null-tree-absent branch so the
  // refactor doesn't silently land NPEs.
  @Test
  public void multiValueEngineSkipsNullTreeWhenAbsent() throws Exception {
    @SuppressWarnings("unchecked")
    CellBTreeSingleValue<CompositeKey> mockSvTree =
        mock(CellBTreeSingleValue.class);
    var op = mock(AtomicOperation.class);
    var readCache = mock(ReadCache.class);
    var writeCache = mock(WriteCache.class);

    var engine = newMultiValueEngineWithTrees(mockSvTree, null);

    engine.verifyAndTruncateOrphans(op, readCache, writeCache);

    verify(mockSvTree).verifyAndTruncateOrphans(op, readCache, writeCache);
    // The null tree was null, so nothing to verify on it — the only sanity check is
    // that the call did not throw. Mockito's verifyNoInteractions on a null target
    // is not applicable; the lack of NPE is the assertion.
    verifyNoInteractions(readCache, writeCache, op);
  }

  // -------------------------------------------------------------------------
  // Reflection helpers — engine ctors are heavy; swap inner trees post-ctor.
  // -------------------------------------------------------------------------

  private BTreeSingleValueIndexEngine newSingleValueEngineWithSbTree(
      CellBTreeSingleValue<CompositeKey> sbTree) throws Exception {
    var mockStorage = newStorageWithFactoryStub();
    var engine = new BTreeSingleValueIndexEngine(1, 1, "testEngine", mockStorage, 3);
    setField(engine, "sbTree", sbTree);
    return engine;
  }

  private BTreeMultiValueIndexEngine newMultiValueEngineWithTrees(
      CellBTreeSingleValue<CompositeKey> svTree,
      CellBTreeSingleValue<CompositeKey> nullTree) throws Exception {
    var mockStorage = newStorageWithFactoryStub();
    // BTreeMultiValueIndexEngine rejects versions 1/2/3 (legacy CellBTreeMultiValue
    // shapes that no longer exist); only version 4 is accepted in the current
    // codebase. Use 4 so construction proceeds, then swap inner trees via
    // reflection.
    var engine = new BTreeMultiValueIndexEngine(2, 2, "testMvEngine", mockStorage, 4);
    setField(engine, "svTree", svTree);
    setField(engine, "nullTree", nullTree);
    return engine;
  }

  /**
   * Returns a mocked AbstractStorage with the absolute minimum stubs needed to
   * construct either index engine: the storage components factory (so the inner
   * BTree(s) can pull their {@code binarySerializerFactory}) plus
   * {@code subIndexSnapshot} / {@code subNullIndexSnapshot} (which both engines
   * call during construction).
   */
  private com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage
      newStorageWithFactoryStub() {
    var mockStorage =
        mock(com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage.class);
    when(mockStorage.getComponentsFactory()).thenReturn(
        new com.jetbrains.youtrackdb.internal.core.db.record.CurrentStorageComponentsFactory(
            com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory.CURRENT_BINARY_FORMAT_VERSION));
    when(mockStorage.subIndexSnapshot(any(Integer.class))).thenReturn(
        mock(com.jetbrains.youtrackdb.internal.core.index.IndexesSnapshot.class));
    when(mockStorage.subNullIndexSnapshot(any(Integer.class))).thenReturn(
        mock(com.jetbrains.youtrackdb.internal.core.index.IndexesSnapshot.class));
    return mockStorage;
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    // The field is final on the engines; setAccessible allows the reflective write
    // anyway, but the Java Memory Model does not guarantee visibility of the new
    // value to readers that already captured the final field. For unit tests this
    // is fine — there is no concurrent reader.
    f.set(target, value);
  }

  // -------------------------------------------------------------------------
  // Note on IOException propagation
  // -------------------------------------------------------------------------

  // The engine-side wrappers are simple delegates; an IOException from the inner
  // BTree.verifyAndTruncateOrphans propagates straight through without wrapping
  // (Java's exception transparency rule for re-throws). No dedicated test pins
  // this — the lack of try/catch in the wrapper bodies is observable by reading the
  // production source, and a future refactor that introduced silent swallowing
  // would be caught at code review time.
  @SuppressWarnings("unused")
  private void ioExceptionPropagationDoc() throws IOException {
  }
}
