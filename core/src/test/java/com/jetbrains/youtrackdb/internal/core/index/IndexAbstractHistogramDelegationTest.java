package com.jetbrains.youtrackdb.internal.core.index;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.InvalidIndexEngineIdException;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.index.engine.BaseIndexEngine;
import com.jetbrains.youtrackdb.internal.core.index.engine.EquiDepthHistogram;
import com.jetbrains.youtrackdb.internal.core.index.engine.HistogramSnapshot;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexHistogramManager;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeIndexEngine;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for histogram-related delegation methods in {@link IndexAbstract}.
 *
 * <p>Tests verify that {@code getStatistics()}, {@code getHistogram()},
 * {@code analyzeHistogram()}, and the private {@code setBulkLoading()} /
 * {@code buildHistogramAfterFill()} correctly delegate to the engine and
 * histogram manager, including the retry loop on
 * {@link InvalidIndexEngineIdException} and null-safety paths.
 */
public class IndexAbstractHistogramDelegationTest {

  private static final RecordId DESCRIPTOR_IDENTITY = new RecordId(17, 42);

  private AbstractStorage storage;
  private DatabaseSessionEmbedded session;
  private IndexNotUnique index;

  @Before
  public void setUp() {
    storage = mock(AbstractStorage.class);
    session = mock(DatabaseSessionEmbedded.class);
    index = new IndexNotUnique(storage);
    setDescriptorIdentity(DESCRIPTOR_IDENTITY);
  }

  /**
   * Sets the indexId field on the IndexAbstract via reflection.
   */
  private void setIndexId(int indexId) {
    try {
      var field = IndexAbstract.class.getDeclaredField("indexId");
      field.setAccessible(true);
      field.set(index, indexId);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException("Failed to set indexId", e);
    }
  }

  /** Sets the descriptor identity needed for owner-bound stale-engine recovery. */
  private void setDescriptorIdentity(RecordId descriptorIdentity) {
    try {
      var field = IndexAbstract.class.getDeclaredField("identity");
      field.setAccessible(true);
      field.set(index, descriptorIdentity);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException("Failed to set descriptor identity", e);
    }
  }

  /**
   * Sets the im (IndexMetadata) field on the IndexAbstract via reflection.
   */
  private void setIndexMetadata(IndexMetadata metadata) {
    try {
      var field = IndexAbstract.class.getDeclaredField("im");
      field.setAccessible(true);
      field.set(index, metadata);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException("Failed to set im", e);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  // getStatistics() delegation
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Verifies that getStatistics() returns the statistics from the engine
   * when the engine is a BTreeIndexEngine with a histogram manager that
   * has statistics.
   */
  @Test
  public void getStatistics_btreeWithStats_returnsEngineStats()
      throws InvalidIndexEngineIdException {
    // Given: a B-tree engine whose getStatistics() returns a known value
    setIndexId(5);
    var stats = new IndexStatistics(100, 50, 10);
    var btreeEngine = mock(BTreeIndexEngine.class);
    when(btreeEngine.getStatistics()).thenReturn(stats);
    when(storage.getIndexEngine(5)).thenReturn(btreeEngine);

    // When
    var result = index.getStatistics(session);

    // Then: the exact same object is returned
    assertSame(stats, result);
  }

  /**
   * Verifies that getStatistics() returns null when the engine is a
   * non-B-tree engine (uses the BaseIndexEngine default).
   */
  @Test
  public void getStatistics_nonBtreeEngine_returnsNull()
      throws InvalidIndexEngineIdException {
    // Given: a non-B-tree engine that returns null for getStatistics()
    setIndexId(3);
    var engine = mock(BaseIndexEngine.class);
    when(engine.getStatistics()).thenReturn(null);
    when(storage.getIndexEngine(3)).thenReturn(engine);

    // When
    var result = index.getStatistics(session);

    // Then
    assertNull(result);
  }

  /**
   * Verifies that getStatistics() resolves a stale identifier through its descriptor owner and
   * succeeds on the second attempt.
   */
  @Test
  public void getStatistics_staleIdentifier_recoversByDescriptorOwner()
      throws InvalidIndexEngineIdException {
    // Given: first call throws, second call succeeds
    setIndexId(5);
    var im = mock(IndexMetadata.class);
    when(im.getName()).thenReturn("testIndex");
    setIndexMetadata(im);

    var stats = new IndexStatistics(200, 100, 0);
    var btreeEngine = mock(BTreeIndexEngine.class);
    when(btreeEngine.getStatistics()).thenReturn(stats);

    when(storage.getIndexEngine(5))
        .thenThrow(new InvalidIndexEngineIdException("stale"));
    when(storage.resolveIndexEngineByOwner(DESCRIPTOR_IDENTITY))
        .thenReturn(new AbstractStorage.ResolvedIndexEngine(7, null));
    when(storage.getIndexEngine(7)).thenReturn(btreeEngine);

    // When
    var result = index.getStatistics(session);

    // Then: returns the stats from the owner-resolved engine
    assertSame(stats, result);
    verify(storage).resolveIndexEngineByOwner(DESCRIPTOR_IDENTITY);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // getHistogram() delegation
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Verifies that getHistogram() returns the histogram from the engine
   * when one is available.
   */
  @Test
  public void getHistogram_btreeWithHistogram_returnsEngineHistogram()
      throws InvalidIndexEngineIdException {
    setIndexId(2);
    var histogram = mock(EquiDepthHistogram.class);
    var btreeEngine = mock(BTreeIndexEngine.class);
    when(btreeEngine.getHistogram()).thenReturn(histogram);
    when(storage.getIndexEngine(2)).thenReturn(btreeEngine);

    // When
    var result = index.getHistogram(session);

    // Then
    assertSame(histogram, result);
  }

  /**
   * Verifies that getHistogram() returns null when the engine has no
   * histogram (e.g., histogram not yet built or non-B-tree engine).
   */
  @Test
  public void getHistogram_noHistogram_returnsNull()
      throws InvalidIndexEngineIdException {
    setIndexId(2);
    var engine = mock(BaseIndexEngine.class);
    when(engine.getHistogram()).thenReturn(null);
    when(storage.getIndexEngine(2)).thenReturn(engine);

    // When
    var result = index.getHistogram(session);

    // Then
    assertNull(result);
  }

  /**
   * Verifies that getHistogram() resolves a stale identifier through its descriptor owner and
   * returns the histogram on the second attempt.
   */
  @Test
  public void getHistogram_staleIdentifier_recoversByDescriptorOwner()
      throws InvalidIndexEngineIdException {
    setIndexId(4);
    var im = mock(IndexMetadata.class);
    when(im.getName()).thenReturn("hIdx");
    setIndexMetadata(im);

    var histogram = mock(EquiDepthHistogram.class);
    var btreeEngine = mock(BTreeIndexEngine.class);
    when(btreeEngine.getHistogram()).thenReturn(histogram);

    when(storage.getIndexEngine(4))
        .thenThrow(new InvalidIndexEngineIdException("stale"));
    when(storage.resolveIndexEngineByOwner(DESCRIPTOR_IDENTITY))
        .thenReturn(new AbstractStorage.ResolvedIndexEngine(8, null));
    when(storage.getIndexEngine(8)).thenReturn(btreeEngine);

    // When
    var result = index.getHistogram(session);

    // Then
    assertSame(histogram, result);
    verify(storage).resolveIndexEngineByOwner(DESCRIPTOR_IDENTITY);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // analyzeHistogram() delegation
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Verifies that analyzeHistogram() returns the snapshot from the
   * histogram manager when the engine is a BTreeIndexEngine with a
   * non-null manager.
   */
  @Test
  public void analyzeHistogram_btreeWithManager_returnsSnapshot()
      throws InvalidIndexEngineIdException {
    setIndexId(1);
    var snapshot = mock(HistogramSnapshot.class);
    var manager = mock(IndexHistogramManager.class);
    when(manager.analyzeIndex()).thenReturn(snapshot);
    var btreeEngine = mock(BTreeIndexEngine.class);
    when(btreeEngine.getHistogramManager()).thenReturn(manager);
    when(storage.getIndexEngine(1)).thenReturn(btreeEngine);

    // When
    var result = index.analyzeHistogram(session);

    // Then
    assertSame(snapshot, result);
    verify(manager).analyzeIndex();
  }

  /**
   * Verifies that analyzeHistogram() returns null when the engine is a
   * BTreeIndexEngine but the histogram manager is null.
   */
  @Test
  public void analyzeHistogram_btreeWithNullManager_returnsNull()
      throws InvalidIndexEngineIdException {
    setIndexId(1);
    var btreeEngine = mock(BTreeIndexEngine.class);
    when(btreeEngine.getHistogramManager()).thenReturn(null);
    when(storage.getIndexEngine(1)).thenReturn(btreeEngine);

    // When
    var result = index.analyzeHistogram(session);

    // Then: returns null because manager is null
    assertNull(result);
  }

  /**
   * Verifies that analyzeHistogram() returns null when the engine is not
   * a BTreeIndexEngine (e.g., a hash index).
   */
  @Test
  public void analyzeHistogram_nonBtreeEngine_returnsNull()
      throws InvalidIndexEngineIdException {
    setIndexId(1);
    var engine = mock(BaseIndexEngine.class);
    when(storage.getIndexEngine(1)).thenReturn(engine);

    // When
    var result = index.analyzeHistogram(session);

    // Then
    assertNull(result);
  }

  /**
   * Verifies that analyzeHistogram() resolves a stale identifier through its descriptor owner and
   * returns the snapshot on the second attempt.
   */
  @Test
  public void analyzeHistogram_staleIdentifier_recoversByDescriptorOwner()
      throws InvalidIndexEngineIdException {
    setIndexId(3);
    var im = mock(IndexMetadata.class);
    when(im.getName()).thenReturn("aIdx");
    setIndexMetadata(im);

    var snapshot = mock(HistogramSnapshot.class);
    var manager = mock(IndexHistogramManager.class);
    when(manager.analyzeIndex()).thenReturn(snapshot);
    var btreeEngine = mock(BTreeIndexEngine.class);
    when(btreeEngine.getHistogramManager()).thenReturn(manager);

    when(storage.getIndexEngine(3))
        .thenThrow(new InvalidIndexEngineIdException("stale"));
    when(storage.resolveIndexEngineByOwner(DESCRIPTOR_IDENTITY))
        .thenReturn(new AbstractStorage.ResolvedIndexEngine(9, null));
    when(storage.getIndexEngine(9)).thenReturn(btreeEngine);

    // When
    var result = index.analyzeHistogram(session);

    // Then
    assertSame(snapshot, result);
    verify(storage).resolveIndexEngineByOwner(DESCRIPTOR_IDENTITY);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // setBulkLoading() (private) delegation
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Verifies that setBulkLoading(true) calls the histogram manager's
   * setBulkLoading(true) when the engine is a BTreeIndexEngine.
   */
  @Test
  public void setBulkLoading_btreeWithManager_delegatesToManager()
      throws Exception {
    setIndexId(6);
    var manager = mock(IndexHistogramManager.class);
    var btreeEngine = mock(BTreeIndexEngine.class);
    when(btreeEngine.getHistogramManager()).thenReturn(manager);
    when(storage.getIndexEngine(6)).thenReturn(btreeEngine);

    // When: call the private method via reflection
    invokeSetBulkLoading(true);

    // Then
    verify(manager).setBulkLoading(true);
  }

  /**
   * Verifies that setBulkLoading(false) calls setBulkLoading(false)
   * on the manager — ensuring the boolean argument is correctly forwarded.
   */
  @Test
  public void setBulkLoading_false_delegatesFalseToManager()
      throws Exception {
    setIndexId(6);
    var manager = mock(IndexHistogramManager.class);
    var btreeEngine = mock(BTreeIndexEngine.class);
    when(btreeEngine.getHistogramManager()).thenReturn(manager);
    when(storage.getIndexEngine(6)).thenReturn(btreeEngine);

    // When
    invokeSetBulkLoading(false);

    // Then
    verify(manager).setBulkLoading(false);
  }

  /**
   * Verifies that setBulkLoading() does NOT call the manager when the
   * engine's histogram manager is null (no NPE).
   */
  @Test
  public void setBulkLoading_btreeWithNullManager_doesNotThrow()
      throws Exception {
    setIndexId(6);
    var btreeEngine = mock(BTreeIndexEngine.class);
    when(btreeEngine.getHistogramManager()).thenReturn(null);
    when(storage.getIndexEngine(6)).thenReturn(btreeEngine);

    // When: no exception expected
    invokeSetBulkLoading(true);

    // Then: getHistogramManager was called, confirming the instanceof path was taken
    verify(btreeEngine).getHistogramManager();
  }

  /**
   * Verifies that setBulkLoading() skips delegation entirely when the
   * engine is not a BTreeIndexEngine.
   */
  @Test
  public void setBulkLoading_nonBtreeEngine_noInteraction()
      throws Exception {
    setIndexId(6);
    var engine = mock(BaseIndexEngine.class);
    when(storage.getIndexEngine(6)).thenReturn(engine);

    // When
    invokeSetBulkLoading(true);

    // Then: only getIndexEngine was called, nothing else
    verify(storage).getIndexEngine(6);
  }

  /** Verifies that setBulkLoading() recovers a stale identifier through its descriptor owner. */
  @Test
  public void setBulkLoading_staleIdentifier_recoversByDescriptorOwner()
      throws Exception {
    setIndexId(6);
    var im = mock(IndexMetadata.class);
    when(im.getName()).thenReturn("bulkIdx");
    setIndexMetadata(im);

    var manager = mock(IndexHistogramManager.class);
    var btreeEngine = mock(BTreeIndexEngine.class);
    when(btreeEngine.getHistogramManager()).thenReturn(manager);

    when(storage.getIndexEngine(6))
        .thenThrow(new InvalidIndexEngineIdException("stale"));
    when(storage.resolveIndexEngineByOwner(DESCRIPTOR_IDENTITY))
        .thenReturn(new AbstractStorage.ResolvedIndexEngine(10, null));
    when(storage.getIndexEngine(10)).thenReturn(btreeEngine);

    // When
    invokeSetBulkLoading(true);

    // Then
    verify(manager).setBulkLoading(true);
    verify(storage).resolveIndexEngineByOwner(DESCRIPTOR_IDENTITY);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // buildHistogramAfterFill() (private) — non-BTree and null-manager paths
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Verifies that buildHistogramAfterFill() does nothing when the engine
   * is not a BTreeIndexEngine (the instanceof check fails).
   */
  @Test
  public void buildHistogramAfterFill_nonBtreeEngine_doesNothing()
      throws Exception {
    setIndexId(7);
    var engine = mock(BaseIndexEngine.class);
    when(storage.getIndexEngine(7)).thenReturn(engine);

    // When: no exception, no interaction beyond getIndexEngine
    invokeBuildHistogramAfterFill();

    verify(storage).getIndexEngine(7);
  }

  /**
   * Verifies that buildHistogramAfterFill() does nothing when the engine
   * is a BTreeIndexEngine but the histogram manager is null.
   */
  @Test
  public void buildHistogramAfterFill_btreeWithNullManager_doesNothing()
      throws Exception {
    setIndexId(7);
    var btreeEngine = mock(BTreeIndexEngine.class);
    when(btreeEngine.getHistogramManager()).thenReturn(null);
    when(storage.getIndexEngine(7)).thenReturn(btreeEngine);

    var atomicOpsMgr = mock(AtomicOperationsManager.class);
    when(storage.getAtomicOperationsManager()).thenReturn(atomicOpsMgr);

    // When
    invokeBuildHistogramAfterFill();

    // Then: no executeInsideAtomicOperation call since manager is null
    verify(atomicOpsMgr, never()).executeInsideAtomicOperation(any());
  }

  /**
   * Verifies that buildHistogramAfterFill() calls
   * executeInsideAtomicOperation and within the atomic operation invokes
   * buildInitialHistogram on the B-tree engine when the histogram manager
   * is non-null.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void buildHistogramAfterFill_btreeWithManager_callsBuildInitialHistogram()
      throws Exception {
    setIndexId(7);
    var im = mock(IndexMetadata.class);
    when(im.getName()).thenReturn("buildIdx");
    setIndexMetadata(im);

    var manager = mock(IndexHistogramManager.class);
    var btreeEngine = mock(BTreeIndexEngine.class);
    when(btreeEngine.getHistogramManager()).thenReturn(manager);
    when(storage.getIndexEngine(7)).thenReturn(btreeEngine);

    // Mock executeInsideAtomicOperation to invoke the lambda
    var mockAtomicOp = mock(AtomicOperation.class);
    var atomicOpsMgr = mock(AtomicOperationsManager.class);
    when(storage.getAtomicOperationsManager()).thenReturn(atomicOpsMgr);
    doAnswer(invocation -> {
      com.jetbrains.youtrackdb.internal.common.function.TxConsumer consumer =
          invocation.getArgument(0);
      consumer.accept(mockAtomicOp);
      return null;
    }).when(atomicOpsMgr).executeInsideAtomicOperation(any());

    // When
    invokeBuildHistogramAfterFill();

    // Then: executeInsideAtomicOperation was called and
    // buildInitialHistogram was invoked with the atomic operation
    verify(atomicOpsMgr).executeInsideAtomicOperation(any());
    verify(btreeEngine).buildInitialHistogram(mockAtomicOp);
  }

  /**
   * Verifies that buildHistogramAfterFill() catches IOException thrown by
   * buildInitialHistogram and does NOT propagate it. The histogram build
   * failure must not fail the index rebuild — the histogram will be rebuilt
   * lazily on the next rebalance.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void buildHistogramAfterFill_ioExceptionIsCaught()
      throws Exception {
    setIndexId(7);
    var im = mock(IndexMetadata.class);
    when(im.getName()).thenReturn("failBuildIdx");
    setIndexMetadata(im);

    var manager = mock(IndexHistogramManager.class);
    var btreeEngine = mock(BTreeIndexEngine.class);
    when(btreeEngine.getHistogramManager()).thenReturn(manager);
    when(storage.getIndexEngine(7)).thenReturn(btreeEngine);

    // Mock executeInsideAtomicOperation to invoke the lambda, which
    // triggers IOException from buildInitialHistogram.
    var mockAtomicOp = mock(AtomicOperation.class);
    var atomicOpsMgr = mock(AtomicOperationsManager.class);
    when(storage.getAtomicOperationsManager()).thenReturn(atomicOpsMgr);
    doAnswer(invocation -> {
      com.jetbrains.youtrackdb.internal.common.function.TxConsumer consumer =
          invocation.getArgument(0);
      consumer.accept(mockAtomicOp);
      return null;
    }).when(atomicOpsMgr).executeInsideAtomicOperation(any());
    doAnswer(invocation2 -> {
      throw new java.io.IOException("simulated build failure");
    })
        .when(btreeEngine).buildInitialHistogram(mockAtomicOp);

    // When: no exception should propagate
    invokeBuildHistogramAfterFill();

    // Then: the method completed (IOException was caught and logged)
    verify(btreeEngine).buildInitialHistogram(mockAtomicOp);
  }

  /**
   * Verifies that buildHistogramAfterFill() resolves a stale identifier through its descriptor
   * owner and proceeds correctly.
   */
  @Test
  public void buildHistogramAfterFill_staleIdentifier_recoversByDescriptorOwner()
      throws Exception {
    setIndexId(7);
    var im = mock(IndexMetadata.class);
    when(im.getName()).thenReturn("fillIdx");
    setIndexMetadata(im);

    // First call throws, second succeeds with non-BTree engine (simple path)
    var engine = mock(BaseIndexEngine.class);
    when(storage.getIndexEngine(7))
        .thenThrow(new InvalidIndexEngineIdException("stale"));
    when(storage.resolveIndexEngineByOwner(DESCRIPTOR_IDENTITY))
        .thenReturn(new AbstractStorage.ResolvedIndexEngine(11, null));
    when(storage.getIndexEngine(11)).thenReturn(engine);

    // When
    invokeBuildHistogramAfterFill();

    // Then: owner recovery was triggered
    verify(storage).resolveIndexEngineByOwner(DESCRIPTOR_IDENTITY);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Helper methods for invoking private methods via reflection
  // ═══════════════════════════════════════════════════════════════════════

  private void invokeSetBulkLoading(boolean bulkLoading) throws Exception {
    var method = IndexAbstract.class.getDeclaredMethod(
        "setBulkLoading", boolean.class);
    method.setAccessible(true);
    method.invoke(index, bulkLoading);
  }

  private void invokeBuildHistogramAfterFill() throws Exception {
    var method = IndexAbstract.class.getDeclaredMethod(
        "buildHistogramAfterFill");
    method.setAccessible(true);
    method.invoke(index);
  }
}
