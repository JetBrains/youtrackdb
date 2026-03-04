package com.jetbrains.youtrackdb.internal.core.index.engine.v1;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.db.record.CurrentStorageComponentsFactory;
import com.jetbrains.youtrackdb.internal.core.index.engine.EquiDepthHistogram;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexHistogramManager;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import org.junit.Test;

/**
 * Tests for the histogram-related methods on B-tree index engines.
 *
 * <p>Covers: default null behavior when no histogram manager is set, and
 * correct delegation when a histogram manager is present.
 */
public class BTreeIndexEngineHistogramTest {

  // ── Single-value engine ───────────────────────────────────────

  @Test
  public void singleValueEngine_getStatistics_returnsNullWhenNoManager() {
    // Given a single-value engine with no histogram manager set
    var engine = createSingleValueEngine();

    // When we ask for statistics
    var result = engine.getStatistics();

    // Then null is returned (safe default)
    assertNull(result);
  }

  @Test
  public void singleValueEngine_getHistogram_returnsNullWhenNoManager() {
    var engine = createSingleValueEngine();

    var result = engine.getHistogram();

    assertNull(result);
  }

  @Test
  public void singleValueEngine_getStatistics_delegatesToManager() {
    // Given a single-value engine with a histogram manager that returns stats
    var engine = createSingleValueEngine();
    var manager = mock(IndexHistogramManager.class);
    var expectedStats = new IndexStatistics(1000, 500, 10);
    when(manager.getStatistics()).thenReturn(expectedStats);
    engine.setHistogramManager(manager);

    // When we ask for statistics
    var result = engine.getStatistics();

    // Then it delegates to the manager and returns the expected value
    assertSame(expectedStats, result);
    verify(manager).getStatistics();
  }

  @Test
  public void singleValueEngine_getHistogram_delegatesToManager() {
    var engine = createSingleValueEngine();
    var manager = mock(IndexHistogramManager.class);
    var expectedHistogram = mock(EquiDepthHistogram.class);
    when(manager.getHistogram()).thenReturn(expectedHistogram);
    engine.setHistogramManager(manager);

    var result = engine.getHistogram();

    assertSame(expectedHistogram, result);
    verify(manager).getHistogram();
  }

  @Test
  public void singleValueEngine_getStatistics_returnsNullWhenManagerReturnsNull() {
    // Given a manager that has no statistics yet (e.g., not loaded)
    var engine = createSingleValueEngine();
    var manager = mock(IndexHistogramManager.class);
    when(manager.getStatistics()).thenReturn(null);
    engine.setHistogramManager(manager);

    var result = engine.getStatistics();

    assertNull(result);
  }

  @Test
  public void singleValueEngine_setHistogramManager_canClearManager() {
    // Given a manager was previously set
    var engine = createSingleValueEngine();
    var manager = mock(IndexHistogramManager.class);
    engine.setHistogramManager(manager);

    // When we set it to null
    engine.setHistogramManager(null);

    // Then getStatistics falls back to null
    assertNull(engine.getStatistics());
    assertNull(engine.getHistogram());
  }

  @Test
  public void singleValueEngine_getHistogramManager_returnsSetManager() {
    var engine = createSingleValueEngine();
    assertNull(engine.getHistogramManager());

    var manager = mock(IndexHistogramManager.class);
    engine.setHistogramManager(manager);
    assertSame(manager, engine.getHistogramManager());
  }

  // ── Multi-value engine ────────────────────────────────────────

  @Test
  public void multiValueEngine_getStatistics_returnsNullWhenNoManager() {
    var engine = createMultiValueEngine();

    var result = engine.getStatistics();

    assertNull(result);
  }

  @Test
  public void multiValueEngine_getHistogram_returnsNullWhenNoManager() {
    var engine = createMultiValueEngine();

    var result = engine.getHistogram();

    assertNull(result);
  }

  @Test
  public void multiValueEngine_getStatistics_delegatesToManager() {
    var engine = createMultiValueEngine();
    var manager = mock(IndexHistogramManager.class);
    var expectedStats = new IndexStatistics(5000, 1200, 50);
    when(manager.getStatistics()).thenReturn(expectedStats);
    engine.setHistogramManager(manager);

    var result = engine.getStatistics();

    assertSame(expectedStats, result);
    verify(manager).getStatistics();
  }

  @Test
  public void multiValueEngine_getHistogram_delegatesToManager() {
    var engine = createMultiValueEngine();
    var manager = mock(IndexHistogramManager.class);
    var expectedHistogram = mock(EquiDepthHistogram.class);
    when(manager.getHistogram()).thenReturn(expectedHistogram);
    engine.setHistogramManager(manager);

    var result = engine.getHistogram();

    assertSame(expectedHistogram, result);
    verify(manager).getHistogram();
  }

  @Test
  public void multiValueEngine_getStatistics_returnsNullWhenManagerReturnsNull() {
    var engine = createMultiValueEngine();
    var manager = mock(IndexHistogramManager.class);
    when(manager.getStatistics()).thenReturn(null);
    engine.setHistogramManager(manager);

    var result = engine.getStatistics();

    assertNull(result);
  }

  @Test
  public void multiValueEngine_setHistogramManager_canClearManager() {
    var engine = createMultiValueEngine();
    var manager = mock(IndexHistogramManager.class);
    engine.setHistogramManager(manager);

    engine.setHistogramManager(null);

    assertNull(engine.getStatistics());
    assertNull(engine.getHistogram());
  }

  @Test
  public void multiValueEngine_getHistogramManager_returnsSetManager() {
    var engine = createMultiValueEngine();
    assertNull(engine.getHistogramManager());

    var manager = mock(IndexHistogramManager.class);
    engine.setHistogramManager(manager);
    assertSame(manager, engine.getHistogramManager());
  }

  // ── Helpers ───────────────────────────────────────────────────

  /**
   * Creates a mock AbstractStorage with enough infrastructure to let the
   * BTree constructor succeed (it accesses getComponentsFactory(),
   * getAtomicOperationsManager(), getReadCache(), getWriteCache()).
   */
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

  private static BTreeSingleValueIndexEngine createSingleValueEngine() {
    return new BTreeSingleValueIndexEngine(
        0, "test-sv-idx", createMockStorage(), 4);
  }

  private static BTreeMultiValueIndexEngine createMultiValueEngine() {
    return new BTreeMultiValueIndexEngine(
        0, "test-mv-idx", createMockStorage(), 4);
  }
}
