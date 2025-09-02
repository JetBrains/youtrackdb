package com.jetbrains.youtrackdb.internal.core.index.engine;

import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.config.IndexEngineData;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.index.IndexMetadata;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.io.IOException;
import java.util.stream.Stream;

public interface BaseIndexEngine {

  int getId();

  void init(DatabaseSessionInternal session, IndexMetadata metadata);

  void flush();

  void create(AtomicOperation atomicOperation, IndexEngineData data) throws IOException;

  void load(IndexEngineData data);

  void delete(AtomicOperation atomicOperation) throws IOException;

  void clear(Storage storage, AtomicOperation atomicOperation) throws IOException;

  void close();

  Stream<RawPair<Object, RID>> iterateEntriesBetween(
      DatabaseSessionEmbedded db, Object rangeFrom,
      boolean fromInclusive,
      Object rangeTo,
      boolean toInclusive,
      boolean ascSortOrder,
      IndexEngineValuesTransformer transformer);

  Stream<RawPair<Object, RID>> iterateEntriesMajor(
      Object fromKey,
      boolean isInclusive,
      boolean ascSortOrder,
      IndexEngineValuesTransformer transformer);

  Stream<RawPair<Object, RID>> iterateEntriesMinor(
      final Object toKey,
      final boolean isInclusive,
      boolean ascSortOrder,
      IndexEngineValuesTransformer transformer);

  Stream<RawPair<Object, RID>> stream(IndexEngineValuesTransformer valuesTransformer);

  Stream<RawPair<Object, RID>> descStream(IndexEngineValuesTransformer valuesTransformer);

  Stream<Object> keyStream();

  long size(Storage storage, IndexEngineValuesTransformer transformer);

  boolean hasRangeQuerySupport();

  int getEngineAPIVersion();

  String getName();

  /**
   * Acquires exclusive lock in the active atomic operation running on the current thread for this
   * index engine.
   */
  boolean acquireAtomicExclusiveLock();

  String getIndexNameByKey(Object key);
}
