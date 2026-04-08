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
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexCountDelta;
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
      var compositeKey = convertToCompositeKey(key);

      Optional<RawPair<CompositeKey, RID>> res;
      try (var stream = sbTree.iterateEntriesBetween(
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

      var value = pair.getSecond().getIdentity();

      sbTree.remove(atomicOperation, pair.first());

      var removedVersion = atomicOperation.getCommitTs();
      var newKey = new CompositeKey(compositeKey, removedVersion);
      sbTree.put(atomicOperation, newKey, new TombstoneRID(value));

      indexesSnapshot.addSnapshotPair(pair.first(), newKey, value);
      IndexCountDelta.accumulate(atomicOperation, id, -1, key == null);

      var mgr = histogramManager;
      if (mgr != null) {
        mgr.onRemove(atomicOperation, key, true);
      }
      return true;
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
    var compositeKey = convertToCompositeKey(key);
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
      boolean wasInserted;

      var newKey = convertToCompositeKey(key);
      Optional<RawPair<CompositeKey, RID>> existing;
      try (var stream = sbTree.iterateEntriesBetween(
          newKey, true, newKey, true, true, atomicOperation)) {
        existing = stream.findAny();
      }
      var version = atomicOperation.getCommitTs();
      if (existing.isPresent()) {
        var pair = existing.get();
        var removedRID = pair.second();
        var oldKey = pair.first();
        long oldVersion = (Long) oldKey.getKeys().getLast();

        if (removedRID instanceof RecordId && oldVersion == version) {
          // Same TX re-put.
          // Entry is already correct — skip remove+re-insert.
          wasInserted = false;
        } else {
          sbTree.remove(atomicOperation, oldKey);
          newKey.addKey(version);
          wasInserted =
              sbTree.put(atomicOperation, newKey, new SnapshotMarkerRID(value));
          if (removedRID instanceof RecordId || removedRID instanceof SnapshotMarkerRID) {
            indexesSnapshot.addSnapshotPair(oldKey, newKey, removedRID.getIdentity());
          }
          if (removedRID instanceof TombstoneRID) {
            IndexCountDelta.accumulate(atomicOperation, id, +1, key == null);
          }
        }
      } else {
        newKey.addKey(version);
        wasInserted = sbTree.put(atomicOperation, newKey, value);
        IndexCountDelta.accumulate(atomicOperation, id, +1, key == null);
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
          e,
          storage.getName());
    }
  }

  @Override
  public boolean validatedPut(
      @Nonnull AtomicOperation atomicOperation,
      Object key,
      RID value,
      IndexEngineValidator<Object, RID> validator) {
    try {
      boolean wasInserted;

      var compositeKey = convertToCompositeKey(key);
      Optional<RawPair<CompositeKey, RID>> existing;
      try (var stream = sbTree.iterateEntriesBetween(
          compositeKey, true, compositeKey, true, true, atomicOperation)) {
        existing = stream.findAny();
      }
      RID removedRID = null;
      if (existing.isPresent()) {
        removedRID = existing.get().second();
      }

      // Validate at engine level with the captured old value.
      // Tombstone means logically deleted — treat as no occupant.
      if (validator != null && removedRID != null && !(removedRID instanceof TombstoneRID)) {
        var result = validator.validate(key, removedRID.getIdentity(), value);
        if (result == IndexEngineValidator.IGNORE) {
          return false;
        }
      }

      var version = atomicOperation.getCommitTs();

      if (removedRID != null) {
        var oldKey = existing.get().first();
        sbTree.remove(atomicOperation, oldKey);
        compositeKey.addKey(version);
        wasInserted =
            sbTree.put(atomicOperation, compositeKey, new SnapshotMarkerRID(value));

        if (removedRID instanceof RecordId || removedRID instanceof SnapshotMarkerRID) {
          // Preserve the old value for snapshot readers that started before
          // this TX. getIdentity() unwraps SnapshotMarkerRID to the inner RID;
          // for RecordId it returns itself.
          indexesSnapshot.addSnapshotPair(oldKey, compositeKey, removedRID.getIdentity());
        }
        if (removedRID instanceof TombstoneRID) {
          IndexCountDelta.accumulate(atomicOperation, id, +1, key == null);
        }
      } else {
        compositeKey.addKey(version);
        wasInserted = sbTree.put(atomicOperation, compositeKey, value);
        IndexCountDelta.accumulate(atomicOperation, id, +1, key == null);
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
          e,
          storage.getName());
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
          sbTree.iterateEntriesMinor(toKey, toInclusive, ascSortOrder, atomicOperation),
          BTreeSingleValueIndexEngine::extractKey);
    }

    final var fromKey = convertToCompositeKey(rangeFrom);
    // "to" could be null, then "from" is not (major)
    if (rangeTo == null) {
      return indexesSnapshot.visibilityFilterMapped(atomicOperation,
          sbTree.iterateEntriesMajor(fromKey, fromInclusive, ascSortOrder, atomicOperation),
          BTreeSingleValueIndexEngine::extractKey);
    }

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
      throw new IndexException("Types of fields should be provided upon of creation of index");
    }
    return sbTypes;
  }

  private static CompositeKey convertToCompositeKey(Object key) {
    if (key instanceof CompositeKey compositeKey) {
      return compositeKey;
    }
    return new CompositeKey(key);
  }

  @Nullable private static Object extractKey(final CompositeKey compositeKey) {
    if (compositeKey == null) {
      return null;
    }
    final var keys = compositeKey.getKeys();
    // Strip the version timestamp (always the last element) — it is an internal
    // detail of this engine and must not leak to the upper-layer API.
    int userKeyCount = keys.size() - 1;
    if (userKeyCount == 1) {
      return keys.getFirst();
    }
    var result = new CompositeKey(userKeyCount);
    for (int i = 0; i < userKeyCount; i++) {
      result.addKey(keys.get(i));
    }
    return result;
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

    // Recalibrate from exact count
    long exactTotal = scannedNonNull + exactNullCount;
    approximateIndexEntriesCount.set(exactTotal);
    approximateNullCount.set(exactNullCount);
    sbTree.setApproximateEntriesCount(atomicOperation, exactTotal);
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
