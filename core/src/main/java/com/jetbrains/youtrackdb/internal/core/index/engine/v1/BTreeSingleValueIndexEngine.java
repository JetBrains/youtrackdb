package com.jetbrains.youtrackdb.internal.core.index.engine.v1;

import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.config.IndexEngineData;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.id.SnapshotMarkerRID;
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

  private void doClearTree(@Nonnull AtomicOperation atomicOperation) throws IOException {
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
  }

  @Override
  public boolean remove(@Nonnull AtomicOperation atomicOperation, Object key) {
    try {
      boolean removed = false;
      var compositeKey = convertToCompositeKey(key);

      var res =
          sbTree
              .iterateEntriesBetween(compositeKey, true, compositeKey, true, true,
                  atomicOperation)
              .findAny();

      if (res.isPresent()) {
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

        var snapshotAddedIndexKey = pair.first();
        var snapshotRemovedIndexKey = newKey;

        indexesSnapshot.addSnapshotPair(snapshotAddedIndexKey, snapshotRemovedIndexKey, value);
        removed = true;
      }

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
      indexesSnapshot.clear();
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

    var stream = sbTree
        .iterateEntriesBetween(compositeKey, true, compositeKey, true, true, atomicOperation);
    var filtered = indexesSnapshot.visibilityFilter(atomicOperation, stream);

    if (key == null) {
      // Avoid matching composite keys with null first field (e.g., CompositeKey(null, "Smith")).
      // A true null key is stored as CompositeKey(null, version), which extractKey returns as null.
      filtered = filtered.filter(pair -> extractKey(pair.first()) == null);
    }

    return filtered.map(RawPair::second);
  }

  @Override
  public Stream<RawPair<Object, RID>> stream(IndexEngineValuesTransformer valuesTransformer,
      @Nonnull AtomicOperation atomicOperation) {
    final var firstKey = sbTree.firstKey(atomicOperation);
    if (firstKey == null) {
      return Stream.empty();
    }

    return mapSVStream(
        indexesSnapshot.visibilityFilter(atomicOperation,
            sbTree.iterateEntriesMajor(firstKey, true, true, atomicOperation)))
        .filter(p -> p.first() != null);
  }

  @Override
  public Stream<RawPair<Object, RID>> descStream(
      IndexEngineValuesTransformer valuesTransformer, @Nonnull AtomicOperation atomicOperation) {
    final var lastKey = sbTree.lastKey(atomicOperation);
    if (lastKey == null) {
      return Stream.empty();
    }

    return mapSVStream(
        indexesSnapshot.visibilityFilter(atomicOperation,
            sbTree.iterateEntriesMinor(lastKey, true, false, atomicOperation)))
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

      var compositeKey = convertToCompositeKey(key);
      var existing = sbTree.iterateEntriesBetween(
          compositeKey, true, compositeKey, true, true, atomicOperation)
          .findAny();
      RID removedRID = null;
      if (existing.isPresent()) {
        removedRID = existing.get().second();
        sbTree.remove(atomicOperation, existing.get().first());
      }

      var version = atomicOperation.getCommitTs();
      compositeKey.addKey(version);

      if (removedRID instanceof TombstoneRID || removedRID instanceof SnapshotMarkerRID) {
        wasInserted = sbTree.put(atomicOperation, compositeKey, new SnapshotMarkerRID(value));
      } else {
        wasInserted = sbTree.put(atomicOperation, compositeKey, value);
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
      var existing = sbTree.iterateEntriesBetween(
          compositeKey, true, compositeKey, true, true, atomicOperation)
          .findAny();
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

      // SnapshotMarkerRID should not be updated
      assert !(removedRID instanceof SnapshotMarkerRID);

      if (removedRID != null) {
        sbTree.remove(atomicOperation, existing.get().first());
      }

      var version = atomicOperation.getCommitTs();
      compositeKey.addKey(version);

      if (removedRID instanceof TombstoneRID) {
        wasInserted = sbTree.put(atomicOperation, compositeKey, new SnapshotMarkerRID(value));
      } else {
        wasInserted = sbTree.put(atomicOperation, compositeKey, value);
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
      var stream = sbTree.iterateEntriesMinor(toKey, toInclusive, ascSortOrder, atomicOperation);
      return mapSVStream(indexesSnapshot.visibilityFilter(atomicOperation, stream));
    }

    final var fromKey = convertToCompositeKey(rangeFrom);
    // "to" could be null, then "from" is not (major)
    if (rangeTo == null) {
      var stream =
          sbTree.iterateEntriesMajor(fromKey, fromInclusive, ascSortOrder, atomicOperation);
      return mapSVStream(indexesSnapshot.visibilityFilter(atomicOperation, stream));
    }

    var stream = sbTree.iterateEntriesBetween(
        fromKey, fromInclusive, toKey, toInclusive, ascSortOrder, atomicOperation);
    return mapSVStream(indexesSnapshot.visibilityFilter(atomicOperation, stream));
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
    return stream(transformer, atomicOperation).count() + get(null, atomicOperation).count();
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

  private static Stream<RawPair<Object, RID>> mapSVStream(
      Stream<RawPair<CompositeKey, RID>> stream) {
    return stream.map(entry -> new RawPair<>(extractKey(entry.first()), entry.second()));
  }

  @Nullable private static Object extractKey(final CompositeKey compositeKey) {
    if (compositeKey == null) {
      return null;
    }
    final var keys = compositeKey.getKeys();
    // Strip the version timestamp (always the last element) — it is an internal
    // detail of this engine and must not leak to the upper-layer API.
    final var userKeys = keys.subList(0, keys.size() - 1);
    if (userKeys.size() == 1) {
      return userKeys.getFirst();
    }
    return new CompositeKey(userKeys);
  }

  @Override
  public void buildInitialHistogram(@Nonnull AtomicOperation atomicOperation)
      throws IOException {
    var mgr = histogramManager;
    if (mgr == null) {
      return;
    }

    long nullCount = getNullCount(atomicOperation);
    long totalCount = getTotalCount(atomicOperation);

    try (
        var keys = keyStream(atomicOperation)) {
      mgr.buildHistogram(
          atomicOperation, keys, totalCount, nullCount,
          mgr.getKeyFieldCount());
    }
  }

  @Override
  public long getNullCount(@Nonnull AtomicOperation atomicOperation) {
    return get(null, atomicOperation).count();
  }

  @Override
  public long getTotalCount(@Nonnull AtomicOperation atomicOperation) {
    return size(storage, null, atomicOperation);
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
