package com.jetbrains.youtrackdb.internal.core.index.engine.v1;

import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.config.IndexEngineData;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.id.TombstoneRID;
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
import java.util.Objects;
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
    nullTreeName = name + AbstractStorage.NULL_TREE_SUFFIX;

    if (version == 1 || version == 2 || version == 3) {
      throw new IllegalArgumentException("Unsupported version of index : " + version);
    } else if (version == 4) {
      svTree =
          new BTree<>(
              name, DATA_FILE_EXTENSION, NULL_BUCKET_FILE_EXTENSION, storage);
      svTree.setEngineId(id);
      nullTree =
          new BTree<>(
              nullTreeName, DATA_FILE_EXTENSION, NULL_BUCKET_FILE_EXTENSION, storage);
      nullTree.setEngineId(id);
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
    // A single iterate-and-remove pass may miss entries when the B-tree
    // spliterator's LSN-based cursor terminates prematurely after page
    // modifications from remove(). Retry until the tree is empty.
    int pass = 0;
    long sizeBeforePass;
    while ((sizeBeforePass = tree.size(atomicOperation)) > 0) {
      pass++;
      final int currentPass = pass;
      var firstKey = tree.firstKey(atomicOperation);
      var lastKey = tree.lastKey(atomicOperation);
      assert firstKey != null && lastKey != null
          : "tree.size() > 0 but firstKey/lastKey is null in engine=" + name + " id=" + id;
      if (firstKey == null || lastKey == null) {
        return;
      }
      try (var stream =
          tree.iterateEntriesBetween(firstKey, true, lastKey, true,
              true, atomicOperation)) {
        stream.forEach(
            pair -> {
              try {
                var removed = tree.remove(atomicOperation, pair.first());
                assert removed != null
                    : "doClearTree pass " + currentPass + ": remove() returned null"
                        + " for key from stream in engine=" + name + " id=" + id
                        + " key=" + pair.first();
              } catch (IOException e) {
                throw BaseException.wrapException(
                    new IndexException(storage.getName(), "Error during index cleaning"), e,
                    storage.getName());
              }
            });
      }
      long sizeAfterPass = tree.size(atomicOperation);
      long removedInPass = sizeBeforePass - sizeAfterPass;
      if (removedInPass <= 0) {
        throw new IndexException(storage.getName(),
            "doClearTree pass " + pass + " removed 0 entries"
                + " (sizeBefore=" + sizeBeforePass + ", sizeAfter=" + sizeAfterPass + ")"
                + " in engine=" + name + " id=" + id);
      }
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
    // Postcondition: doClearTree must remove every entry from both trees.
    assert svTree.firstKey(atomicOperation) == null
        : "doClearTree() left entries in svTree of engine=" + name + " id=" + id
            + " treeSize=" + svTree.size(atomicOperation);
    assert nullTree.firstKey(atomicOperation) == null
        : "doClearTree() left entries in nullTree of engine=" + name + " id=" + id
            + " treeSize=" + nullTree.size(atomicOperation);
    // Reset persisted counts on entry point pages after clearing tree data.
    // persistIndexCountDeltas adds only deltas from replayed entries after
    // the clear, yielding the correct final count.
    svTree.setApproximateEntriesCount(atomicOperation, 0);
    nullTree.setApproximateEntriesCount(atomicOperation, 0);
    indexesSnapshot.clear();
    nullIndexesSnapshot.clear();
    // In-memory counters are set eagerly inside the atomic operation. If the
    // enclosing transaction rolls back:
    // 1. WAL reverts the persisted page to pre-clear count (e.g., 1000)
    // 2. In-memory counters stay at 0
    // 3. Next commit's persistCountDelta adds delta (e.g., +5) to reverted
    //    page → 1005
    // 4. applyIndexCountDeltas adds delta to in-memory 0 → 5
    // 5. On restart, load() reads 1005, diverging from the 5 the live
    //    instance has
    //
    // Self-healing: buildInitialHistogram() recalibrates both from an exact
    // scan. This divergence is tolerable because counters are approximate and
    // rollback of clear() is an extremely rare edge case.
    approximateIndexEntriesCount.set(0);
    approximateNullCount.set(0);
    var mgr = histogramManager;
    if (mgr != null) {
      // Local try-catch needed: unlike BTreeSingleValueIndexEngine.clear(),
      // this method's outer scope does not catch IOException.
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
      return svTree.getVisibleStream(compositeKey, indexesSnapshot, atomicOperation);
    } else {
      // Null tree stores entries as [RID, version] with varying RIDs — a prefix
      // match on firstKey() would restrict to a single RID. Use the full iteration
      // path instead to collect all visible null-key entries.
      var prefixKey = nullTree.firstKey(atomicOperation);
      if (prefixKey == null) {
        return Stream.empty();
      }
      var stream = nullTree.iterateEntriesMajor(
          prefixKey, true, true, atomicOperation);
      return nullIndexesSnapshot.visibilityFilterValues(atomicOperation, stream);
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

  // Strips RID and version (2 trailing elements) to recover the user-visible key.
  @Nullable private static Object extractKey(final CompositeKey compositeKey) {
    return VersionedIndexOps.extractUserKey(compositeKey, 2);
  }

  /**
   * Returns a raw key stream that skips {@link TombstoneRID} entries but does
   * <b>not</b> apply SI visibility filtering. Suitable only for histogram
   * building/rebalance where approximate counts are acceptable.
   *
   * <p>See {@link BTreeSingleValueIndexEngine#rawKeyStreamForHistogram} for the
   * full rationale.
   */
  public Stream<Object> rawKeyStreamForHistogram(
      @Nonnull AtomicOperation atomicOperation) {
    final var firstKey = svTree.firstKey(atomicOperation);
    if (firstKey == null) {
      return Stream.empty();
    }
    return svTree.iterateEntriesMajor(firstKey, true, true, atomicOperation)
        .filter(pair -> !(pair.second() instanceof TombstoneRID))
        .map(pair -> extractKey(pair.first()))
        .filter(Objects::nonNull);
  }

  @Override
  public void buildInitialHistogram(@Nonnull AtomicOperation atomicOperation)
      throws IOException {
    var mgr = histogramManager;
    if (mgr == null) {
      return;
    }

    // Use approximate count for bucket sizing, scan for exact count + keys.
    long approxTotal = approximateIndexEntriesCount.get();
    long approxNull = approximateNullCount.get();

    long scannedNonNull;
    // Use the raw (non-SI-filtered) key stream for histogram building.
    // SI filtering is unnecessary here because the histogram tolerates the
    // tiny error from uncommitted/phantom entries (< 0.01% of index size),
    // and skipping it avoids drift between scanned counts and scalar counters.
    try (var keyStream = rawKeyStreamForHistogram(atomicOperation)) {
      scannedNonNull = mgr.buildHistogram(
          atomicOperation, keyStream,
          approxTotal, approxNull,
          mgr.getKeyFieldCount());
    }

    // Count visible null entries for recalibration. The null tree is typically
    // small (one entry per null-keyed document), so the scan cost is negligible
    // compared to the svTree scan.
    long exactNullCount;
    var nullFirstKey = nullTree.firstKey(atomicOperation);
    if (nullFirstKey == null) {
      exactNullCount = 0;
    } else {
      try (var nullStream = nullIndexesSnapshot.visibilityFilterValues(
          atomicOperation,
          nullTree.iterateEntriesMajor(nullFirstKey, true, true, atomicOperation))) {
        exactNullCount = nullStream.count();
      }
    }

    // Recalibrate from exact counts — persist first so that if setApproximateEntriesCount
    // throws, the in-memory counters remain at their prior (approximate) values.
    //
    // Note: if the enclosing atomic operation rolls back after this point, WAL reverts
    // the persisted page but the in-memory counters keep the new values. This temporary
    // divergence is acceptable because counters are approximate by design and the next
    // buildInitialHistogram() or load() will recalibrate them.
    svTree.setApproximateEntriesCount(atomicOperation, scannedNonNull);
    nullTree.setApproximateEntriesCount(atomicOperation, exactNullCount);
    approximateNullCount.set(exactNullCount);
    approximateIndexEntriesCount.set(scannedNonNull + exactNullCount);
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
    long updated = approximateIndexEntriesCount.addAndGet(delta);
    assert updated >= 0
        : "In-memory approximateIndexEntriesCount underflow: updated="
            + updated + " delta=" + delta;
  }

  @Override
  public void addToApproximateNullCount(long delta) {
    long updated = approximateNullCount.addAndGet(delta);
    assert updated >= 0
        : "In-memory approximateNullCount underflow: updated="
            + updated + " delta=" + delta;
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
