package com.jetbrains.youtrackdb.internal.core.index.engine.v1;

import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.config.IndexEngineData;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.SnapshotMarkerRID;
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
import java.util.List;
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
  // query optimizer for cost estimation. Initialized via visibility-filtered scan
  // on load(). Adjusted at commit time via delta holder (not directly in
  // doPut/doRemove). AtomicLong because concurrent TXs can commit index changes
  // simultaneously. Recalibrated by buildInitialHistogram() as self-healing.
  private final AtomicLong approximateIndexEntriesCount = new AtomicLong();

  // Approximate count of null-key entries. Follows the same lifecycle as
  // approximateIndexEntriesCount: initialized on load(), adjusted at commit
  // time, recalibrated by buildInitialHistogram().
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

    // Initialize both counters via visibility-filtered scans so they start
    // accurate (tree.size() includes tombstones and markers).
    // Non-null entries live in svTree, null entries in nullTree — scan separately.
    long nonNullCount = 0;
    var svFirstKey = svTree.firstKey(atomicOperation);
    if (svFirstKey != null) {
      try (var svStream = indexesSnapshot.visibilityFilterMapped(atomicOperation,
          svTree.iterateEntriesMajor(svFirstKey, true, true, atomicOperation),
          BTreeMultiValueIndexEngine::extractKey)) {
        nonNullCount = svStream.count();
      }
    }

    long nullCount = 0;
    var nullFirstKey = nullTree.firstKey(atomicOperation);
    if (nullFirstKey != null) {
      try (var nullStream = nullIndexesSnapshot.visibilityFilterMapped(atomicOperation,
          nullTree.iterateEntriesMajor(nullFirstKey, true, true, atomicOperation),
          k -> null)) {
        nullCount = nullStream.count();
      }
    }

    approximateIndexEntriesCount.set(nonNullCount + nullCount);
    approximateNullCount.set(nullCount);
  }

  @Override
  public boolean remove(final @Nonnull AtomicOperation atomicOperation, Object key, RID value) {
    try {
      boolean removed;
      if (key != null) {
        removed = doRemove(svTree, indexesSnapshot, atomicOperation,
            createCompositeKey(key, value), value);
      } else {
        removed = doRemove(nullTree, nullIndexesSnapshot, atomicOperation,
            new CompositeKey(value), value);
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

  private boolean doRemove(
      CellBTreeSingleValue<CompositeKey> tree,
      IndexesSnapshot snapshot,
      @Nonnull AtomicOperation atomicOperation,
      CompositeKey compositeKey,
      RID value) throws IOException {
    Optional<RawPair<CompositeKey, RID>> res;
    try (var stream = tree.iterateEntriesBetween(
        compositeKey, true, compositeKey, true, true, atomicOperation)) {
      res = stream.findAny();
    }

    if (res.isEmpty()) {
      return false;
    }

    var pair = res.get();

    // should not re-delete the deleted entry
    if (pair.second() instanceof TombstoneRID) {
      return false;
    }

    tree.remove(atomicOperation, pair.first());

    var removedVersion = atomicOperation.getCommitTs();
    var newKey = new CompositeKey(compositeKey, removedVersion);
    tree.put(atomicOperation, newKey, new TombstoneRID(value));

    snapshot.addSnapshotPair(pair.first(), newKey, value);
    approximateIndexEntriesCount.decrementAndGet();
    return true;
  }

  @Override
  public void clear(Storage storage, @Nonnull AtomicOperation atomicOperation) {
    clearSVTree(atomicOperation);
    indexesSnapshot.clear();
    nullIndexesSnapshot.clear();
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
      final var compositeKey = convertToCompositeKey(key);

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
            createCompositeKey(key, value), value);
      } else {
        wasInserted = doPut(nullTree, nullIndexesSnapshot, atomicOperation,
            new CompositeKey(value), value);
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
      RID value) throws IOException {
    // Find existing entry by (userKey, RID) prefix
    Optional<RawPair<CompositeKey, RID>> res;
    try (var stream = tree.iterateEntriesBetween(
        newKey, true, newKey, true, true, atomicOperation)) {
      res = stream.findAny();
    }
    var version = atomicOperation.getCommitTs();
    if (res.isPresent()) {
      var pair = res.get();
      var removedRID = pair.second();
      var oldKey = pair.first();
      long oldVersion = (Long) oldKey.getKeys().getLast();

      if (removedRID instanceof RecordId && oldVersion == version) {
        // Same TX re-put (e.g., collapsed by interpretAsNonUnique).
        // Entry is already correct — skip remove+re-insert.
        return false;
      }
      tree.remove(atomicOperation, oldKey);
      newKey.addKey(version);
      tree.put(atomicOperation, newKey, new SnapshotMarkerRID(value));
      // For a live RecordId from a prior TX, preserve old version for
      // concurrent snapshot readers.
      if (removedRID instanceof RecordId) {
        snapshot.addSnapshotPair(oldKey, newKey, value);
      }
      if (removedRID instanceof TombstoneRID) {
        approximateIndexEntriesCount.incrementAndGet();
      }
      return true;
    } else {
      newKey.addKey(version);
      approximateIndexEntriesCount.incrementAndGet();
      return tree.put(atomicOperation, newKey, value);
    }
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
    final var toKey = convertToCompositeKey(rangeTo);
    if (rangeFrom == null) {
      return indexesSnapshot.visibilityFilterMapped(atomicOperation,
          svTree.iterateEntriesMinor(toKey, toInclusive, ascSortOrder, atomicOperation),
          BTreeMultiValueIndexEngine::extractKey);
    }

    // "to" could be null, then "from" is not (major)
    final var fromKey = convertToCompositeKey(rangeFrom);
    if (rangeTo == null) {
      return indexesSnapshot.visibilityFilterMapped(atomicOperation,
          svTree.iterateEntriesMajor(fromKey, fromInclusive, ascSortOrder, atomicOperation),
          BTreeMultiValueIndexEngine::extractKey);
    }

    var stream =
        svTree.iterateEntriesBetween(fromKey, fromInclusive, toKey, toInclusive, ascSortOrder,
            atomicOperation);

    return indexesSnapshot.visibilityFilterMapped(atomicOperation, stream,
        BTreeMultiValueIndexEngine::extractKey);
  }

  private static CompositeKey convertToCompositeKey(Object rangeFrom) {
    CompositeKey firstKey;
    if (rangeFrom instanceof CompositeKey compositeKey) {
      firstKey = compositeKey;
    } else {
      firstKey = new CompositeKey(rangeFrom);
    }
    return firstKey;
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
      throw new IndexException("Types of fields should be provided upon of creation of index");
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

    // Null entries live in nullTree, non-null in svTree — two separate scans
    // are unavoidable. But we combine the non-null count and key collection
    // into a single svTree pass (previously stream().count() + keyStream()
    // scanned svTree twice).
    long nullCount;
    try (var nullStream = get(null, atomicOperation)) {
      nullCount = nullStream.count();
    }

    var firstKey = svTree.firstKey(atomicOperation);
    if (firstKey == null) {
      approximateIndexEntriesCount.set(nullCount);
      approximateNullCount.set(nullCount);
      mgr.buildHistogram(atomicOperation, Stream.empty(), nullCount, nullCount,
          mgr.getKeyFieldCount());
      return;
    }

    List<RawPair<Object, RID>> nonNullEntries;
    try (var svStream = indexesSnapshot.visibilityFilterMapped(atomicOperation,
        svTree.iterateEntriesMajor(firstKey, true, true, atomicOperation),
        BTreeMultiValueIndexEngine::extractKey)) {
      nonNullEntries = svStream.toList();
    }

    long totalCount = nonNullEntries.size() + nullCount;

    // Recalibrate approximate counters from the exact scan to prevent
    // divergence over time (e.g., from rolled-back atomic operations).
    approximateIndexEntriesCount.set(totalCount);
    approximateNullCount.set(nullCount);

    mgr.buildHistogram(
        atomicOperation, nonNullEntries.stream().map(RawPair::first),
        totalCount, nullCount,
        mgr.getKeyFieldCount());
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
  public void addToApproximateEntryCount(long delta) {
    approximateIndexEntriesCount.addAndGet(delta);
  }

  @Override
  public void addToApproximateNullCount(long delta) {
    approximateNullCount.addAndGet(delta);
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
