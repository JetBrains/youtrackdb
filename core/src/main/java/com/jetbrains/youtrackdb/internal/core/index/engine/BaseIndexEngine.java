package com.jetbrains.youtrackdb.internal.core.index.engine;

import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.config.IndexEngineData;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.index.IndexMetadata;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.io.IOException;
import java.util.stream.Stream;
import javax.annotation.Nullable;

public interface BaseIndexEngine {

  int getId();

  void init(DatabaseSessionEmbedded session, IndexMetadata metadata);

  void flush();

  void create(AtomicOperation atomicOperation, IndexEngineData data) throws IOException;

  void load(IndexEngineData data, AtomicOperation atomicOperation);

  void delete(AtomicOperation atomicOperation) throws IOException;

  void clear(Storage storage, AtomicOperation atomicOperation) throws IOException;

  void close();

  Stream<RawPair<Object, RID>> iterateEntriesBetween(
      Object rangeFrom,
      boolean fromInclusive,
      Object rangeTo,
      boolean toInclusive,
      boolean ascSortOrder,
      IndexEngineValuesTransformer transformer, AtomicOperation atomicOperation);

  Stream<RawPair<Object, RID>> iterateEntriesMajor(
      Object fromKey,
      boolean isInclusive,
      boolean ascSortOrder,
      IndexEngineValuesTransformer transformer, AtomicOperation atomicOperation);

  Stream<RawPair<Object, RID>> iterateEntriesMinor(
      final Object toKey,
      final boolean isInclusive,
      boolean ascSortOrder,
      IndexEngineValuesTransformer transformer, AtomicOperation atomicOperation);

  Stream<RawPair<Object, RID>> stream(IndexEngineValuesTransformer valuesTransformer,
      AtomicOperation atomicOperation);

  Stream<RawPair<Object, RID>> descStream(IndexEngineValuesTransformer valuesTransformer,
      AtomicOperation atomicOperation);

  Stream<Object> keyStream(AtomicOperation atomicOperation);

  long size(Storage storage, IndexEngineValuesTransformer transformer,
      AtomicOperation atomicOperation);

  int getEngineAPIVersion();

  String getName();

  /**
   * Acquires exclusive lock in the active atomic operation running on the current thread for this
   * index engine.
   */
  boolean acquireAtomicExclusiveLock(AtomicOperation atomicOperation);

  /**
   * Returns per-index statistics for cost-based query optimization.
   *
   * <p>Default returns {@code null} — safe for engines that do not support
   * histograms (e.g., hash indexes with no sorted key stream).
   */
  @Nullable default IndexStatistics getStatistics() {
    return null;
  }

  /**
   * Returns the equi-depth histogram for this index, or {@code null} if no
   * histogram is available (engine does not support histograms, or histogram
   * has not been built yet).
   */
  @Nullable default EquiDepthHistogram getHistogram() {
    return null;
  }
}
