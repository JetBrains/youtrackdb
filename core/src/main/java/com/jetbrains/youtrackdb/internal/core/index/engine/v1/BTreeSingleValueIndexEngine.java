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
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValidator;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValuesTransformer;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexHistogramManager;
import com.jetbrains.youtrackdb.internal.core.index.engine.SingleValueIndexEngine;
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

public final class BTreeSingleValueIndexEngine
    implements SingleValueIndexEngine, BTreeIndexEngine {

  private static final String DATA_FILE_EXTENSION = ".cbt";
  private static final String NULL_BUCKET_FILE_EXTENSION = ".nbt";

  private final CellBTreeSingleValue<CompositeKey> sbTree;
  private final IndexesSnapshot indexesSnapshot;
  private final String name;
  private final int id;
  private final AbstractStorage storage;
  @Nullable private volatile IndexHistogramManager histogramManager;

  // Approximate count of visible index entries, used by the query optimizer for
  // cost estimation. Read from persisted APPROXIMATE_ENTRIES_COUNT on load().
  // Adjusted at commit time via delta holder (not directly in put/remove).
  // AtomicLong because concurrent TXs can commit index changes simultaneously.
  // Recalibrated by buildInitialHistogram() as self-healing.
  private final AtomicLong approximateIndexEntriesCount = new AtomicLong();

  // Approximate count of null-key entries. Set to 0 on load() (not separately
  // persisted for single-value — at most off by 1). Adjusted at commit time,
  // recalibrated by buildInitialHistogram().
  private final AtomicLong approximateNullCount = new AtomicLong();

  public BTreeSingleValueIndexEngine(
      int id, String name, AbstractStorage storage, int version) {
    this.name = name;
    this.id = id;
    this.storage = storage;

    if (version == 3 || version == 4) {
      this.sbTree =
          new BTree<>(
              name, DATA_FILE_EXTENSION, NULL_BUCKET_FILE_EXTENSION, storage);
      indexesSnapshot = storage.subIndexSnapshot(id);
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
      sbTree.create(
          atomicOperation,
          new IndexMultiValuKeySerializer(),
          sbTypes,
          data.getKeySize() + 1);
      approximateIndexEntriesCount.set(0);
      approximateNullCount.set(0);
    } catch (IOException e) {
      throw BaseException.wrapException(
          new IndexException(storage.getName(), "Error of creation of index " + name),
          e, storage.getName());
    }
  }

  @Override
  public void delete(final @Nonnull AtomicOperation atomicOperation) {
    try {
      doClearTree(atomicOperation);
      indexesSnapshot.clear();
      sbTree.delete(atomicOperation);
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

  private void doClearTree(@Nonnull AtomicOperation atomicOperation) {
    try (var stream = sbTree.keyStream(atomicOperation)) {
      stream.forEach(
          key -> {
            try {
              sbTree.remove(atomicOperation, key);
            } catch (IOException e) {
              throw BaseException.wrapException(
                  new IndexException(storage.getName(), "Can not clear index"), e,
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

    sbTree.load(name, keySize + 1, sbTypes, new IndexMultiValuKeySerializer(), atomicOperation);

    // Read persisted visible count from the BTree entry point page — O(1)
    // instead of the previous O(n) visibility-filtered scan.
    long count = sbTree.getApproximateEntriesCount(atomicOperation);
    if (count == 0) {
      // Upgrade path: APPROXIMATE_ENTRIES_COUNT was not present in prior format.
      // Use TREE_SIZE as initial estimate — overcounts (includes tombstones/markers)
      // but prevents the optimizer from seeing empty indexes until recalibration.
      count = sbTree.size(atomicOperation);
    }
    assert count >= 0
        : "Persisted approximate entries count must be non-negative: " + count;
    approximateIndexEntriesCount.set(count);
    // Null count is not separately persisted for single-value indexes (always
    // 0 or 1). Set to 0; buildInitialHistogram() recalibrates it.
    approximateNullCount.set(0);
  }

  @Override
  public boolean remove(@Nonnull AtomicOperation atomicOperation, Object key) {
    try {
      var compositeKey = convertToCompositeKeyDefensive(key);

      boolean removed = VersionedIndexOps.doVersionedRemove(
          sbTree, indexesSnapshot, atomicOperation, compositeKey, id, key == null);

      if (removed) {
        var mgr = histogramManager;
        if (mgr != null) {
          mgr.onRemove(atomicOperation, key, true);
        }
      }
      return removed;
    } catch (IOException e) {
      throw BaseException.wrapException(
          new IndexException(storage.getName(),
              "Error during removal of key " + key + " from index " + name),
          e,
          storage.getName());
    }
  }

  @Override
  public void clear(Storage storage, @Nonnull AtomicOperation atomicOperation) {
    try {
      doClearTree(atomicOperation);
      // Reset persisted count on entry point page after clearing tree data.
      // persistIndexCountDeltas adds only deltas from replayed entries after
      // the clear, yielding the correct final count.
      sbTree.setApproximateEntriesCount(atomicOperation, 0);
      indexesSnapshot.clear();
      // In-memory counters are set eagerly inside the atomic operation. If the
      // enclosing transaction rolls back, WAL reverts the persisted pages but these
      // counters will remain at 0, temporarily diverging from the on-disk state.
      // This is acceptable because counters are approximate by design and will be
      // recalibrated on the next buildInitialHistogram() call or on load().
      approximateIndexEntriesCount.set(0);
      approximateNullCount.set(0);
      var mgr = histogramManager;
      if (mgr != null) {
        mgr.resetOnClear(atomicOperation);
      }
    } catch (IOException e) {
      throw BaseException.wrapException(
          new IndexException(storage.getName(), "Error during clear of index " + name),
          e, storage.getName());
    }
  }

  @Override
  public void close() {
    sbTree.close();
    var mgr = histogramManager;
    if (mgr != null) {
      mgr.closeStatsFile();
    }
  }

  @Override
  public Stream<RID> get(Object key, @Nonnull AtomicOperation atomicOperation) {
    var compositeKey = CompositeKey.asCompositeKey(key);
    // Direct leaf-page lookup — avoids cursor/spliterator/stream overhead.
    // Null keys are handled uniformly: for single-field indexes,
    // CompositeKey(null) is padded with Long.MIN_VALUE for the version slot
    // and matched normally. For composite indexes, getVisible() returns null
    // immediately because the key has fewer user elements than expected
    // (composite indexes have no "null key" — only composite keys with
    // individually-null fields).
    var rid = sbTree.getVisible(compositeKey, indexesSnapshot, atomicOperation);
    return rid != null ? Stream.of(rid) : Stream.empty();
  }

  @Override
  public Stream<RawPair<Object, RID>> stream(IndexEngineValuesTransformer valuesTransformer,
      @Nonnull AtomicOperation atomicOperation) {
    final var firstKey = sbTree.firstKey(atomicOperation);
    if (firstKey == null) {
      return Stream.empty();
    }

    return indexesSnapshot.visibilityFilterMapped(atomicOperation,
        sbTree.iterateEntriesMajor(firstKey, true, true, atomicOperation),
        BTreeSingleValueIndexEngine::extractKey)
        .filter(p -> p.first() != null);
  }

  @Override
  public Stream<RawPair<Object, RID>> descStream(
      IndexEngineValuesTransformer valuesTransformer, @Nonnull AtomicOperation atomicOperation) {
    final var lastKey = sbTree.lastKey(atomicOperation);
    if (lastKey == null) {
      return Stream.empty();
    }

    return indexesSnapshot.visibilityFilterMapped(atomicOperation,
        sbTree.iterateEntriesMinor(lastKey, true, false, atomicOperation),
        BTreeSingleValueIndexEngine::extractKey)
        .filter(p -> p.first() != null);
  }

  @Override
  public Stream<Object> keyStream(@Nonnull AtomicOperation atomicOperation) {
    return stream(null, atomicOperation).map(RawPair::first).filter(Objects::nonNull);
  }

  @Override
  public boolean put(@Nonnull AtomicOperation atomicOperation, Object key, RID value) {
    try {
      var compositeKey = convertToCompositeKeyDefensive(key);
      boolean wasInserted =
          doPutSingleValue(atomicOperation, compositeKey, value, key);

      var mgr = histogramManager;
      if (mgr != null) {
        mgr.onPut(atomicOperation, key, true, wasInserted);
      }
      return wasInserted;
    } catch (IOException e) {
      throw BaseException.wrapException(
          new IndexException(storage.getName(),
              "Error during insertion of key " + key + " into index " + name),
          e, storage.getName());
    }
  }

  @Override
  public boolean validatedPut(
      @Nonnull AtomicOperation atomicOperation,
      Object key,
      RID value,
      IndexEngineValidator<Object, RID> validator) {
    try {
      var compositeKey = convertToCompositeKeyDefensive(key);

      // Validate at engine level before mutation, keeping the scan result
      // to avoid a second B-tree descent inside doPutSingleValue.
      boolean wasInserted;
      if (validator != null) {
        Optional<RawPair<CompositeKey, RID>> prefetched;
        try (var stream = sbTree.iterateEntriesBetween(
            compositeKey, true, compositeKey, true, true, atomicOperation)) {
          prefetched = stream.findAny();
        }
        if (prefetched.isPresent()) {
          var removedRID = prefetched.get().second();
          // Tombstone means logically deleted — treat as no occupant
          if (!(removedRID instanceof TombstoneRID)) {
            var result = validator.validate(key, removedRID.getIdentity(), value);
            if (result == IndexEngineValidator.IGNORE) {
              return false;
            }
          }
        }
        wasInserted =
            doPutSingleValue(atomicOperation, compositeKey, value, key, prefetched);
      } else {
        wasInserted =
            doPutSingleValue(atomicOperation, compositeKey, value, key);
      }

      var mgr = histogramManager;
      if (mgr != null) {
        mgr.onPut(atomicOperation, key, true, wasInserted);
      }
      return wasInserted;
    } catch (IOException e) {
      throw BaseException.wrapException(
          new IndexException(storage.getName(),
              "Error during insertion of key " + key + " into index " + name),
          e, storage.getName());
    }
  }

  /**
   * Called by put() — always performs its own B-tree scan.
   */
  private boolean doPutSingleValue(
      @Nonnull AtomicOperation atomicOperation,
      CompositeKey compositeKey,
      RID value,
      Object key) throws IOException {
    Optional<RawPair<CompositeKey, RID>> existing;
    try (var stream = sbTree.iterateEntriesBetween(
        compositeKey, true, compositeKey, true, true, atomicOperation)) {
      existing = stream.findAny();
    }
    return VersionedIndexOps.doVersionedPut(
        sbTree, indexesSnapshot, atomicOperation, compositeKey, value,
        id, key == null, existing);
  }

  /**
   * Called by validatedPut() — reuses the prefetched scan result.
   */
  private boolean doPutSingleValue(
      @Nonnull AtomicOperation atomicOperation,
      CompositeKey compositeKey,
      RID value,
      Object key,
      @Nonnull Optional<RawPair<CompositeKey, RID>> prefetched) throws IOException {
    return VersionedIndexOps.doVersionedPut(
        sbTree, indexesSnapshot, atomicOperation, compositeKey, value,
        id, key == null, prefetched);
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
          sbTree.iterateEntriesMinor(toKey, toInclusive, ascSortOrder, atomicOperation),
          BTreeSingleValueIndexEngine::extractKey);
    }

    final var fromKey = CompositeKey.asCompositeKey(rangeFrom);
    // "to" could be null, then "from" is not (major)
    if (rangeTo == null) {
      return indexesSnapshot.visibilityFilterMapped(atomicOperation,
          sbTree.iterateEntriesMajor(fromKey, fromInclusive, ascSortOrder, atomicOperation),
          BTreeSingleValueIndexEngine::extractKey);
    }

    final var toKey = CompositeKey.asCompositeKey(rangeTo);
    return indexesSnapshot.visibilityFilterMapped(atomicOperation,
        sbTree.iterateEntriesBetween(
            fromKey, fromInclusive, toKey, toInclusive, ascSortOrder, atomicOperation),
        BTreeSingleValueIndexEngine::extractKey);
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
    sbTree.acquireAtomicExclusiveLock(atomicOperation);
    return true;
  }

  private static PropertyTypeInternal[] calculateTypes(final PropertyTypeInternal[] keyTypes) {
    final PropertyTypeInternal[] sbTypes;
    if (keyTypes != null) {
      sbTypes = new PropertyTypeInternal[keyTypes.length + 1];
      System.arraycopy(keyTypes, 0, sbTypes, 0, keyTypes.length);
      // property type for version
      sbTypes[sbTypes.length - 1] = PropertyTypeInternal.LONG;
    } else {
      throw new IndexException("Types of fields should be provided upon creation of index");
    }
    return sbTypes;
  }

  /**
   * Creates a defensive copy of the key as a CompositeKey. Required for mutation
   * paths (put/remove) where addKey(version) appends to the key, which would
   * otherwise mutate the caller's object.
   */
  private static CompositeKey convertToCompositeKeyDefensive(Object key) {
    if (key instanceof CompositeKey compositeKey) {
      return new CompositeKey(compositeKey);
    }
    return new CompositeKey(key);
  }

  // Strips the version timestamp (1 trailing element) to recover the user-visible key.
  @Nullable private static Object extractKey(final CompositeKey compositeKey) {
    return VersionedIndexOps.extractUserKey(compositeKey, 1);
  }

  /** Single null lookup — at most 1 entry for a unique index. */
  private long countNulls(@Nonnull AtomicOperation atomicOperation) {
    try (var nullStream = get(null, atomicOperation)) {
      return nullStream.count();
    }
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
    long exactNullCount = countNulls(atomicOperation);

    long scannedNonNull;
    try (var keyStream = keyStream(atomicOperation)) {
      scannedNonNull = mgr.buildHistogram(
          atomicOperation, keyStream,
          approxTotal, exactNullCount,
          mgr.getKeyFieldCount());
    }

    // Recalibrate from exact count — persist first so that if setApproximateEntriesCount
    // throws, the in-memory counters remain at their prior (approximate) values rather
    // than diverging from the rolled-back persisted state.
    //
    // Note: if the enclosing atomic operation rolls back after this point, WAL reverts
    // the persisted page but the in-memory counters keep the new values. This temporary
    // divergence is acceptable because counters are approximate by design and the next
    // buildInitialHistogram() or load() will recalibrate them.
    long exactTotal = scannedNonNull + exactNullCount;
    sbTree.setApproximateEntriesCount(atomicOperation, exactTotal);
    approximateIndexEntriesCount.set(exactTotal);
    approximateNullCount.set(exactNullCount);
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
    // Single-value engine stores all entries (including nulls) in one BTree.
    // The full totalDelta applies to the single tree's persisted count.
    // nullDelta intentionally ignored — single tree stores all entries.
    if (totalDelta != 0) {
      sbTree.addToApproximateEntriesCount(atomicOperation, totalDelta);
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
