package com.jetbrains.youtrackdb.internal.core.index.engine.v1;

import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.config.IndexEngineData;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.index.IndexException;
import com.jetbrains.youtrackdb.internal.core.index.IndexMetadata;
import com.jetbrains.youtrackdb.internal.core.index.IndexesSnapshot;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValuesTransformer;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexHistogramManager;
import com.jetbrains.youtrackdb.internal.core.index.engine.MultiValueIndexEngine;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl.index.IndexMultiValuKeySerializer;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.CellBTreeSingleValue;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.BTree;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class BTreeMultiValueIndexEngine
    implements MultiValueIndexEngine, BTreeIndexEngine {

  public static final String DATA_FILE_EXTENSION = ".cbt";
  private static final String NULL_BUCKET_FILE_EXTENSION = ".nbt";
  public static final String M_CONTAINER_EXTENSION = ".mbt";

  @Nonnull
  private final CellBTreeSingleValue<CompositeKey> svTree;
  @Nonnull
  private final CellBTreeSingleValue<CompositeKey> nullTree;
  @Nonnull
  private final IndexesSnapshot indexesSnapshot;
  @Nonnull
  private final IndexesSnapshot nullIndexesSnapshot;

  private final String name;
  private final int id;
  private final String nullTreeName;
  private final AbstractStorage storage;
  @Nullable private volatile IndexHistogramManager histogramManager;

  // Approximate count of visible index entries (svTree + nullTree), used by the
  // query optimizer for cost estimation. Read from persisted
  // APPROXIMATE_ENTRIES_COUNT on both trees on load(). Adjusted at commit time
  // via delta holder (not directly in doPut/doRemove). AtomicLong because
  // concurrent TXs can commit index changes simultaneously. Recalibrated by
  // buildInitialHistogram() as self-healing.
  private final AtomicLong approximateIndexEntriesCount = new AtomicLong();

  // Approximate count of null-key entries. Read from nullTree's persisted
  // APPROXIMATE_ENTRIES_COUNT on load(). Adjusted at commit time, recalibrated
  // by buildInitialHistogram().
  private final AtomicLong approximateNullCount = new AtomicLong();

  public BTreeMultiValueIndexEngine(
      int id, @Nonnull String name, AbstractStorage storage, final int version) {
    this.id = id;
    this.name = name;
    this.storage = storage;
    nullTreeName = name + "$null";

    if (version == 1 || version == 2 || version == 3) {
      throw new IllegalArgumentException("Unsupported version of index : " + version);
    } else if (version == 4) {
      svTree =
          new BTree<>(
              name, DATA_FILE_EXTENSION, NULL_BUCKET_FILE_EXTENSION, storage);
      nullTree =
          new BTree<>(
              nullTreeName, DATA_FILE_EXTENSION, NULL_BUCKET_FILE_EXTENSION, storage);
      indexesSnapshot = storage.subIndexSnapshot(id);
      nullIndexesSnapshot = storage.subNullIndexSnapshot(id);
    } else {
      throw new IllegalStateException("Invalid tree version " + version);
    }
  }

  @Override
  public int getId() {
    return id;
  }

  @Override
  public void init(DatabaseSessionEmbedded session, IndexMetadata metadata) {
  }

  @Override
  public void flush() {
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public void create(@Nonnull AtomicOperation atomicOperation, IndexEngineData data) {
    try {
      final var sbTypes = calculateTypes(data.getKeyTypes());
      svTree.create(
          atomicOperation,
          new IndexMultiValuKeySerializer(),
          sbTypes,
          data.getKeySize() + 1);
      nullTree.create(
          atomicOperation, new IndexMultiValuKeySerializer(),
          new PropertyTypeInternal[] {PropertyTypeInternal.LINK, PropertyTypeInternal.LONG},
          2);
      approximateIndexEntriesCount.set(0);
      approximateNullCount.set(0);
    } catch (IOException e) {
      throw BaseException.wrapException(
          new IndexException(storage.getName(), "Error during creation of index " + name), e,
          storage.getName());
    }
  }

  @Override
  public void delete(@Nonnull AtomicOperation atomicOperation) {
    try {
      clearSVTree(atomicOperation);
      indexesSnapshot.clear();
      nullIndexesSnapshot.clear();
      svTree.delete(atomicOperation);
      nullTree.delete(atomicOperation);
      var mgr = histogramManager;
      if (mgr != null) {
        mgr.deleteStatsFile(atomicOperation);
      }
    } catch (IOException e) {
      throw BaseException.wrapException(
          new IndexException(storage.getName(), "Error during deletion of index " + name), e,
          storage.getName());
    }
  }

  private void clearSVTree(final @Nonnull AtomicOperation atomicOperation) {
    doClearTree(svTree, atomicOperation);
    doClearTree(nullTree, atomicOperation);
  }

  private void doClearTree(CellBTreeSingleValue<CompositeKey> tree,
      @Nonnull AtomicOperation atomicOperation) {
    final var firstKey = tree.firstKey(atomicOperation);
    final var lastKey = tree.lastKey(atomicOperation);

    if (firstKey == null || lastKey == null) {
      return;
    }

    try (var stream =
        tree.iterateEntriesBetween(firstKey, true, lastKey, true,
            true, atomicOperation)) {
      stream.forEach(
          pair -> {
            try {
              tree.remove(atomicOperation, pair.first());
            } catch (IOException e) {
              throw BaseException.wrapException(
                  new IndexException(storage.getName(), "Error during index cleaning"), e,
                  storage.getName());
            }
          });
    }
  }

  @Override
  public void load(IndexEngineData data, @Nonnull AtomicOperation atomicOperation) {
    var name = data.getName();
    var keySize = data.getKeySize();
    var keyTypes = data.getKeyTypes();
    final var sbTypes = calculateTypes(keyTypes);

    svTree.load(name, keySize + 1, sbTypes, new IndexMultiValuKeySerializer(), atomicOperation);
    nullTree.load(
        nullTreeName, 2,
        new PropertyTypeInternal[] {PropertyTypeInternal.LINK, PropertyTypeInternal.LONG},
        new IndexMultiValuKeySerializer(), atomicOperation);

    // Read persisted visible counts from both trees' entry point pages — O(1)
    // instead of the previous O(n) visibility-filtered scans.
    long svCount = svTree.getApproximateEntriesCount(atomicOperation);
    if (svCount == 0) {
      // Upgrade path: APPROXIMATE_ENTRIES_COUNT was not present in prior format.
      // Use TREE_SIZE as initial estimate — overcounts (includes tombstones/markers)
      // but prevents the optimizer from seeing empty indexes until recalibration.
      svCount = svTree.size(atomicOperation);
    }
    long nullCount = nullTree.getApproximateEntriesCount(atomicOperation);
    if (nullCount == 0) {
      // Upgrade path: same fallback for the null tree.
      nullCount = nullTree.size(atomicOperation);
    }
    assert svCount >= 0
        : "Persisted svTree approximate entries count must be non-negative: " + svCount;
    assert nullCount >= 0
        : "Persisted nullTree approximate entries count must be non-negative: " + nullCount;
    approximateIndexEntriesCount.set(svCount + nullCount);
    approximateNullCount.set(nullCount);
  }

  @Override
  public boolean remove(final @Nonnull AtomicOperation atomicOperation, Object key, RID value) {
    try {
      boolean removed;
      if (key != null) {
        removed = VersionedIndexOps.doVersionedRemove(svTree, indexesSnapshot,
            atomicOperation, createCompositeKey(key, value), id, false);
      } else {
        removed = VersionedIndexOps.doVersionedRemove(nullTree, nullIndexesSnapshot,
            atomicOperation, new CompositeKey(value), id, true);
      }

      if (removed) {
        var mgr = histogramManager;
        if (mgr != null) {
          mgr.onRemove(atomicOperation, key, false);
        }
      }
      return removed;
    } catch (IOException e) {
      throw BaseException.wrapException(
          new IndexException(storage.getName(),
              "Error during removal of entry with key "
                  + key
                  + " and RID "
                  + value
                  + " from index "
                  + name),
          e, storage.getName());
    }
  }

  @Override
  public void clear(Storage storage, @Nonnull AtomicOperation atomicOperation) {
    clearSVTree(atomicOperation);
    // Reset persisted counts on entry point pages after clearing tree data.
    // persistIndexCountDeltas adds only deltas from replayed entries after
    // the clear, yielding the correct final count.
    svTree.setApproximateEntriesCount(atomicOperation, 0);
    nullTree.setApproximateEntriesCount(atomicOperation, 0);
    indexesSnapshot.clear();
    nullIndexesSnapshot.clear();
    // In-memory counters are set eagerly inside the atomic operation. If the
    // enclosing transaction rolls back, WAL reverts the persisted pages but these
    // counters will remain at 0, temporarily diverging from the on-disk state.
    // This is acceptable because counters are approximate by design and will be
    // recalibrated on the next buildInitialHistogram() call or on load().
    approximateIndexEntriesCount.set(0);
    approximateNullCount.set(0);
    var mgr = histogramManager;
    if (mgr != null) {
      try {
        mgr.resetOnClear(atomicOperation);
      } catch (IOException e) {
        throw BaseException.wrapException(
            new IndexException(storage.getName(),
                "Error during histogram reset on clear of index " + name),
            e, storage.getName());
      }
    }
  }

  @Override
  public void close() {
    svTree.close();
    nullTree.close();
    var mgr = histogramManager;
    if (mgr != null) {
      mgr.closeStatsFile();
    }
  }

  @Override
  public Stream<RID> get(Object key, @Nonnull AtomicOperation atomicOperation) {
    if (key != null) {
      final var compositeKey = CompositeKey.asCompositeKey(key);

      var stream = svTree
          .iterateEntriesBetween(compositeKey, true, compositeKey, true, true, atomicOperation);
      return indexesSnapshot.visibilityFilter(atomicOperation, stream)
          .map(RawPair::second);
    } else {
      var prefixKey = nullTree.firstKey(atomicOperation);
      if (prefixKey == null) {
        return Stream.empty();
      }
      var stream = nullTree.iterateEntriesMajor(
          prefixKey, true, true, atomicOperation);
      return nullIndexesSnapshot.visibilityFilter(atomicOperation, stream)
          .map(RawPair::second);
    }
  }

  @Override
  public Stream<RawPair<Object, RID>> stream(IndexEngineValuesTransformer valuesTransformer,
      @Nonnull AtomicOperation atomicOperation) {
    final var firstKey = svTree.firstKey(atomicOperation);
    if (firstKey == null) {
      return Stream.empty();
    }

    return indexesSnapshot.visibilityFilterMapped(atomicOperation,
        svTree.iterateEntriesMajor(firstKey, true, true, atomicOperation),
        BTreeMultiValueIndexEngine::extractKey);
  }

  @Override
  public Stream<RawPair<Object, RID>> descStream(
      IndexEngineValuesTransformer valuesTransformer, @Nonnull AtomicOperation atomicOperation) {
    final var lastKey = svTree.lastKey(atomicOperation);
    if (lastKey == null) {
      return Stream.empty();
    }

    return indexesSnapshot.visibilityFilterMapped(atomicOperation,
        svTree.iterateEntriesMinor(lastKey, true, false, atomicOperation),
        BTreeMultiValueIndexEngine::extractKey);
  }

  @Override
  public Stream<Object> keyStream(@Nonnull AtomicOperation atomicOperation) {
    return stream(null, atomicOperation).map(RawPair::first);
  }

  @Override
  public boolean put(@Nonnull AtomicOperation atomicOperation, Object key, RID value) {
    try {
      boolean wasInserted;
      if (key != null) {
        wasInserted = doPut(svTree, indexesSnapshot, atomicOperation,
            createCompositeKey(key, value), value, false);
      } else {
        wasInserted = doPut(nullTree, nullIndexesSnapshot, atomicOperation,
            new CompositeKey(value), value, true);
      }

      var mgr = histogramManager;
      if (mgr != null) {
        mgr.onPut(atomicOperation, key, false, wasInserted);
      }
      return wasInserted;
    } catch (IOException e) {
      throw BaseException.wrapException(
          new IndexException(storage.getName(),
              "Error during insertion of key " + key + " and RID " + value + " to index " + name),
          e, storage.getName());
    }
  }

  private boolean doPut(
      CellBTreeSingleValue<CompositeKey> tree,
      IndexesSnapshot snapshot,
      @Nonnull AtomicOperation atomicOperation,
      CompositeKey newKey,
      RID value,
      boolean isNullKey) throws IOException {
    // Find existing entry by (userKey, RID) prefix
    Optional<RawPair<CompositeKey, RID>> existing;
    try (var stream = tree.iterateEntriesBetween(
        newKey, true, newKey, true, true, atomicOperation)) {
      existing = stream.findAny();
    }
    return VersionedIndexOps.doVersionedPut(
        tree, snapshot, atomicOperation, newKey, value, id, isNullKey, existing);
  }

  @Override
  public Stream<RawPair<Object, RID>> iterateEntriesBetween(
      Object rangeFrom,
      boolean fromInclusive,
      Object rangeTo,
      boolean toInclusive,
      boolean ascSortOrder,
      IndexEngineValuesTransformer transformer, @Nonnull AtomicOperation atomicOperation) {

    // "from", "to" are null, then scan whole tree as for infinite range
    if (rangeFrom == null && rangeTo == null) {
      return ascSortOrder
          ? stream(transformer, atomicOperation)
          : descStream(transformer, atomicOperation);
    }

    // "from" could be null, then "to" is not (minor)
    if (rangeFrom == null) {
      final var toKey = CompositeKey.asCompositeKey(rangeTo);
      return indexesSnapshot.visibilityFilterMapped(atomicOperation,
          svTree.iterateEntriesMinor(toKey, toInclusive, ascSortOrder, atomicOperation),
          BTreeMultiValueIndexEngine::extractKey);
    }

    // "to" could be null, then "from" is not (major)
    final var fromKey = CompositeKey.asCompositeKey(rangeFrom);
    if (rangeTo == null) {
      return indexesSnapshot.visibilityFilterMapped(atomicOperation,
          svTree.iterateEntriesMajor(fromKey, fromInclusive, ascSortOrder, atomicOperation),
          BTreeMultiValueIndexEngine::extractKey);
    }

    final var toKey = CompositeKey.asCompositeKey(rangeTo);
    var stream =
        svTree.iterateEntriesBetween(fromKey, fromInclusive, toKey, toInclusive, ascSortOrder,
            atomicOperation);

    return indexesSnapshot.visibilityFilterMapped(atomicOperation, stream,
        BTreeMultiValueIndexEngine::extractKey);
  }

  @Override
  public Stream<RawPair<Object, RID>> iterateEntriesMajor(
      Object fromKey,
      boolean isInclusive,
      boolean ascSortOrder,
      IndexEngineValuesTransformer transformer, @Nonnull AtomicOperation atomicOperation) {
    return iterateEntriesBetween(
        fromKey, isInclusive, null, false, ascSortOrder, transformer, atomicOperation);
  }

  @Override
  public Stream<RawPair<Object, RID>> iterateEntriesMinor(
      Object toKey,
      boolean isInclusive,
      boolean ascSortOrder,
      IndexEngineValuesTransformer transformer, @Nonnull AtomicOperation atomicOperation) {
    return iterateEntriesBetween(
        null, false, toKey, isInclusive, ascSortOrder, transformer, atomicOperation);
  }

  @Override
  public long size(Storage storage, final IndexEngineValuesTransformer transformer,
      @Nonnull AtomicOperation atomicOperation) {
    return approximateIndexEntriesCount.get();
  }

  @Override
  public boolean acquireAtomicExclusiveLock(@Nonnull AtomicOperation atomicOperation) {
    svTree.acquireAtomicExclusiveLock(atomicOperation);
    nullTree.acquireAtomicExclusiveLock(atomicOperation);

    return true;
  }

  private static PropertyTypeInternal[] calculateTypes(final PropertyTypeInternal[] keyTypes) {
    final PropertyTypeInternal[] sbTypes;
    if (keyTypes != null) {
      sbTypes = new PropertyTypeInternal[keyTypes.length + 2];
      System.arraycopy(keyTypes, 0, sbTypes, 0, keyTypes.length);
      // property type for RID
      sbTypes[sbTypes.length - 2] = PropertyTypeInternal.LINK;
      // property type for version
      sbTypes[sbTypes.length - 1] = PropertyTypeInternal.LONG;
    } else {
      throw new IndexException("Types of fields should be provided upon creation of index");
    }
    return sbTypes;
  }

  private static CompositeKey createCompositeKey(final Object key, final RID value) {
    final var compositeKey = new CompositeKey(key);
    compositeKey.addKey(value);
    return compositeKey;
  }

  @Nullable private static Object extractKey(final CompositeKey compositeKey) {
    if (compositeKey == null) {
      return null;
    }

    final var keys = compositeKey.getKeys();
    // Strip RID and version (last 2 elements) — they are internal to this engine
    // and must not leak to the upper-layer API.
    int userKeyCount = keys.size() - 2;
    if (userKeyCount == 1) {
      return keys.getFirst();
    }
    var result = new CompositeKey(userKeyCount);
    for (int i = 0; i < userKeyCount; i++) {
      result.addKey(keys.get(i));
    }
    return result;
  }

  @Override
  public void buildInitialHistogram(@Nonnull AtomicOperation atomicOperation)
      throws IOException {
    var mgr = histogramManager;
    if (mgr == null) {
      return;
    }

    // Use approximate count for bucket sizing, scan for exact count + keys.
    // Null entries live in nullTree; counting visible nulls would require a
    // full scan, so approximate null count is used.
    long approxTotal = approximateIndexEntriesCount.get();
    long approxNull = approximateNullCount.get();

    long scannedNonNull;
    try (var keyStream = keyStream(atomicOperation)) {
      scannedNonNull = mgr.buildHistogram(
          atomicOperation, keyStream,
          approxTotal, approxNull,
          mgr.getKeyFieldCount());
    }

    // Recalibrate from exact count — persist first so that if setApproximateEntriesCount
    // throws, the in-memory counters remain at their prior (approximate) values.
    //
    // Note: if the enclosing atomic operation rolls back after this point, WAL reverts
    // the persisted page but the in-memory counters keep the new values. This temporary
    // divergence is acceptable because counters are approximate by design and the next
    // buildInitialHistogram() or load() will recalibrate them.
    svTree.setApproximateEntriesCount(atomicOperation, scannedNonNull);
    approximateIndexEntriesCount.set(scannedNonNull + approxNull);
  }

  @Override
  public long getNullCount(@Nonnull AtomicOperation atomicOperation) {
    return approximateNullCount.get();
  }

  @Override
  public long getTotalCount(@Nonnull AtomicOperation atomicOperation) {
    return approximateIndexEntriesCount.get();
  }

  @Override
  public void addToApproximateEntriesCount(long delta) {
    approximateIndexEntriesCount.addAndGet(delta);
  }

  @Override
  public void addToApproximateNullCount(long delta) {
    approximateNullCount.addAndGet(delta);
  }

  @Override
  public void persistCountDelta(
      AtomicOperation atomicOperation, long totalDelta, long nullDelta) {
    // Multi-value engine splits entries across two trees:
    // svTree holds non-null entries, nullTree holds null entries.
    // totalDelta = nonNullDelta + nullDelta, so nonNullDelta = totalDelta - nullDelta.
    long nonNullDelta = totalDelta - nullDelta;
    if (nonNullDelta != 0) {
      svTree.addToApproximateEntriesCount(atomicOperation, nonNullDelta);
    }
    if (nullDelta != 0) {
      nullTree.addToApproximateEntriesCount(atomicOperation, nullDelta);
    }
  }

  /**
   * Sets the histogram manager for this engine. Called during engine
   * lifecycle (create/load) once the manager is initialized.
   */
  @Override
  public void setHistogramManager(@Nullable IndexHistogramManager histogramManager) {
    this.histogramManager = histogramManager;
  }

  @Override
  @Nullable public IndexHistogramManager getHistogramManager() {
    return histogramManager;
  }
}
