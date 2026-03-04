package com.jetbrains.youtrackdb.internal.core.index.engine;

import static org.junit.Assert.assertNull;

import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.config.IndexEngineData;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.index.IndexMetadata;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * Tests that {@link BaseIndexEngine} default implementations of
 * {@code getStatistics()} and {@code getHistogram()} return {@code null}.
 *
 * <p>This verifies the safe default for engines that do not support histograms
 * (e.g., hash indexes). Uses a minimal stub implementation of the interface.
 */
public class BaseIndexEngineHistogramDefaultsTest {

  @Test
  public void defaultGetStatistics_returnsNull() {
    BaseIndexEngine engine = new StubIndexEngine();
    assertNull(engine.getStatistics());
  }

  @Test
  public void defaultGetHistogram_returnsNull() {
    BaseIndexEngine engine = new StubIndexEngine();
    assertNull(engine.getHistogram());
  }

  /**
   * Minimal stub that inherits the default histogram methods without
   * overriding them, simulating a non-B-tree engine.
   */
  private static class StubIndexEngine implements BaseIndexEngine {

    @Override
    public int getId() {
      return 0;
    }

    @Override
    public void init(DatabaseSessionEmbedded session, IndexMetadata metadata) {
    }

    @Override
    public void flush() {
    }

    @Override
    public void create(AtomicOperation atomicOperation, IndexEngineData data) {
    }

    @Override
    public void load(IndexEngineData data, AtomicOperation atomicOperation) {
    }

    @Override
    public void delete(AtomicOperation atomicOperation) {
    }

    @Override
    public void clear(Storage storage, AtomicOperation atomicOperation) {
    }

    @Override
    public void close() {
    }

    @Override
    public Stream<RawPair<Object, RID>> iterateEntriesBetween(
        Object rangeFrom, boolean fromInclusive, Object rangeTo,
        boolean toInclusive, boolean ascSortOrder,
        IndexEngineValuesTransformer transformer,
        AtomicOperation atomicOperation) {
      return Stream.empty();
    }

    @Override
    public Stream<RawPair<Object, RID>> iterateEntriesMajor(
        Object fromKey, boolean isInclusive, boolean ascSortOrder,
        IndexEngineValuesTransformer transformer,
        AtomicOperation atomicOperation) {
      return Stream.empty();
    }

    @Override
    public Stream<RawPair<Object, RID>> iterateEntriesMinor(
        Object toKey, boolean isInclusive, boolean ascSortOrder,
        IndexEngineValuesTransformer transformer,
        AtomicOperation atomicOperation) {
      return Stream.empty();
    }

    @Override
    public Stream<RawPair<Object, RID>> stream(
        IndexEngineValuesTransformer valuesTransformer,
        AtomicOperation atomicOperation) {
      return Stream.empty();
    }

    @Override
    public Stream<RawPair<Object, RID>> descStream(
        IndexEngineValuesTransformer valuesTransformer,
        AtomicOperation atomicOperation) {
      return Stream.empty();
    }

    @Override
    public Stream<Object> keyStream(AtomicOperation atomicOperation) {
      return Stream.empty();
    }

    @Override
    public long size(Storage storage, IndexEngineValuesTransformer transformer,
        AtomicOperation atomicOperation) {
      return 0;
    }

    @Override
    public int getEngineAPIVersion() {
      return 0;
    }

    @Override
    public String getName() {
      return "stub";
    }

    @Override
    public boolean acquireAtomicExclusiveLock(AtomicOperation atomicOperation) {
      return false;
    }
  }
}
