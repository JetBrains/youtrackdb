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
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class BTreeMultiValueIndexEngine
    implements MultiValueIndexEngine, BTreeIndexEngine {

  public static final String DATA_FILE_EXTENSION = ".cbt";
  private static final String NULL_BUCKET_FILE_EXTENSION = ".nbt";
  public static final String M_CONTAINER_EXTENSION = ".mbt";

  // Sentinel for null-keyed entries in indexesSnapshot/nullTree.
  private static final Object NULL_KEY_SENTINEL = null;

  @Nonnull
  private final CellBTreeSingleValue<CompositeKey> svTree;
  @Nonnull
  private final CellBTreeSingleValue<CompositeKey> nullTree;
  @Nonnull
  private final IndexesSnapshot indexesSnapshot;

  private final String name;
  private final int id;
  private final String nullTreeName;
  private final AbstractStorage storage;
  @Nullable private volatile IndexHistogramManager histogramManager;

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
          new PropertyTypeInternal[] {PropertyTypeInternal.LONG, PropertyTypeInternal.LINK,
              PropertyTypeInternal.LONG},
          3);
    } catch (IOException e) {
      throw BaseException.wrapException(
          new IndexException(storage.getName(), "Error during creation of index " + name), e,
          storage.getName());
    }
  }

  @Override
  public void delete(@Nonnull AtomicOperation atomicOperation) {
    try {
      doClearSVTree(atomicOperation);
      indexesSnapshot.clear();
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

  private void doClearSVTree(final @Nonnull AtomicOperation atomicOperation) {
    {
      final var firstKey = svTree.firstKey(atomicOperation);
      final var lastKey = svTree.lastKey(atomicOperation);

      try (var stream =
          svTree.iterateEntriesBetween(firstKey, true, lastKey, true,
              true, atomicOperation)) {
        stream.forEach(
            (pair) -> {
              try {
                svTree.remove(atomicOperation, pair.first());
              } catch (IOException e) {
                throw BaseException.wrapException(
                    new IndexException(storage.getName(), "Error during index cleaning"), e,
                    storage.getName());
              }
            });
      }
    }

    {
      final var firstKey = nullTree.firstKey(atomicOperation);
      final var lastKey = nullTree.lastKey(atomicOperation);

      if (firstKey != null && lastKey != null) {
        try (var stream =
            nullTree.iterateEntriesBetween(firstKey, true, lastKey, true,
                true, atomicOperation)) {
          stream.forEach(
              (pair) -> {
                try {
                  nullTree.remove(atomicOperation, pair.first());
                } catch (IOException e) {
                  throw BaseException.wrapException(
                      new IndexException(storage.getName(), "Error during index cleaning"), e,
                      storage.getName());
                }
              });
        }
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
        nullTreeName, 3,
        new PropertyTypeInternal[] {PropertyTypeInternal.LONG, PropertyTypeInternal.LINK,
            PropertyTypeInternal.LONG},
        new IndexMultiValuKeySerializer(), atomicOperation);
  }

  @Override
  public boolean remove(final @Nonnull AtomicOperation atomicOperation, Object key, RID value) {
    try {
      boolean removed = false;
      if (key != null) {
        final var compositeKey = createCompositeKey(key, value);

        var res =
            svTree.iterateEntriesBetween(compositeKey, true, compositeKey,
                true, true, atomicOperation)
                .findAny();

        if (res.isPresent()) {
          var pair = res.get();

          // should not re-delete the deleted entry
          if (pair.second() instanceof TombstoneRID) {
            return false;
          }

          svTree.remove(atomicOperation, pair.first());

          var removedVersion = atomicOperation.getCommitTs();
          var newKey = new CompositeKey(compositeKey, removedVersion);
          svTree.put(atomicOperation, newKey, new TombstoneRID(value));

          var snapshotAddedIndexKey = pair.first();
          var snapshotRemovedIndexKey = newKey;
          indexesSnapshot.addSnapshotPair(snapshotAddedIndexKey, snapshotRemovedIndexKey, value);

          removed = true;
        }
      } else {

        var compositeKey = new CompositeKey(NULL_KEY_SENTINEL, value);
        var res = nullTree.iterateEntriesBetween(
            compositeKey, true, compositeKey, true, true, atomicOperation)
            .findAny();

        if (res.isPresent()) {
          var pair = res.get();

          // should not re-delete the deleted entry
          if (pair.second() instanceof TombstoneRID) {
            return false;
          }

          nullTree.remove(atomicOperation, pair.first());

          var removedVersion = atomicOperation.getCommitTs();
          var newKey = new CompositeKey(compositeKey, removedVersion);
          nullTree.put(atomicOperation, newKey, new TombstoneRID(value));

          indexesSnapshot.addSnapshotPair(pair.first(), newKey, value);
          removed = true;
        }
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
    doClearSVTree(atomicOperation);
    indexesSnapshot.clear();
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
      final var firstKey = convertToCompositeKey(key);
      final var lastKey = convertToCompositeKey(key);

      var stream = svTree
          .iterateEntriesBetween(firstKey, true, lastKey, true, true, atomicOperation);
      return indexesSnapshot.visibilityFilter(atomicOperation, stream)
          .map(RawPair::second);
    } else {
      var prefixKey = new CompositeKey(NULL_KEY_SENTINEL);
      var stream = nullTree.iterateEntriesMajor(
          prefixKey, true, true, atomicOperation);
      return indexesSnapshot.visibilityFilter(atomicOperation, stream)
          .map(RawPair::second);
    }
  }

  @Override
  public Stream<RawPair<Object, RID>> stream(IndexEngineValuesTransformer valuesTransformer,
      @Nonnull AtomicOperation atomicOperation) {
    final var firstKey = svTree.firstKey(atomicOperation);
    if (firstKey == null) {
      return emptyStream();
    }

    return mapSVStream(
        indexesSnapshot.visibilityFilter(atomicOperation,
            svTree.iterateEntriesMajor(firstKey, true, true, atomicOperation)));
  }

  private static Stream<RawPair<Object, RID>> mapSVStream(
      Stream<RawPair<CompositeKey, RID>> stream) {
    return stream.map(entry -> new RawPair<>(extractKey(entry.first()), entry.second()));
  }

  private static Stream<RawPair<Object, RID>> emptyStream() {
    return StreamSupport.stream(Spliterators.emptySpliterator(), false);
  }

  @Override
  public Stream<RawPair<Object, RID>> descStream(
      IndexEngineValuesTransformer valuesTransformer, @Nonnull AtomicOperation atomicOperation) {
    final var lastKey = svTree.lastKey(atomicOperation);
    if (lastKey == null) {
      return emptyStream();
    }

    return mapSVStream(
        indexesSnapshot.visibilityFilter(atomicOperation,
            svTree.iterateEntriesMinor(lastKey, true, false, atomicOperation)));
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

        var compositeKey = createCompositeKey(key, value);
        // Find and remove existing entry by (userKey, RID) prefix
        var res = svTree.iterateEntriesBetween(
            compositeKey, true, compositeKey, true, true, atomicOperation)
            .findAny();
        RID removedRID = null;
        if (res.isPresent()) {
          var pair = res.get();
          removedRID = pair.second();
          svTree.remove(atomicOperation, pair.first());
        }

        var version = atomicOperation.getCommitTs();
        compositeKey.addKey(version);
        // if removedRID is a TombstoneRID, put a new RID value as SnapshotMarkerRID
        if (removedRID instanceof TombstoneRID || removedRID instanceof SnapshotMarkerRID) {
          wasInserted = svTree.put(atomicOperation, compositeKey, new SnapshotMarkerRID(value));
        } else {
          wasInserted = svTree.put(atomicOperation, compositeKey, value);
        }
      } else {

        var compositeKey = new CompositeKey(NULL_KEY_SENTINEL, value);
        var res = nullTree.iterateEntriesBetween(
            compositeKey, true, compositeKey, true, true, atomicOperation)
            .findAny();
        RID removedRID = null;
        if (res.isPresent()) {
          var pair = res.get();
          removedRID = pair.second();
          nullTree.remove(atomicOperation, pair.first());
        }

        var version = atomicOperation.getCommitTs();
        compositeKey.addKey(version);
        if (removedRID instanceof TombstoneRID || removedRID instanceof SnapshotMarkerRID) {
          wasInserted = nullTree.put(atomicOperation, compositeKey, new SnapshotMarkerRID(value));
        } else {
          wasInserted = nullTree.put(atomicOperation, compositeKey, value);
        }
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
      return mapSVStream(
          indexesSnapshot.visibilityFilter(atomicOperation,
              svTree.iterateEntriesMinor(toKey, toInclusive, ascSortOrder, atomicOperation)));
    }

    // "to" could be null, then "from" is not (major)
    final var fromKey = convertToCompositeKey(rangeFrom);
    if (rangeTo == null) {
      return mapSVStream(
          indexesSnapshot.visibilityFilter(atomicOperation,
              svTree.iterateEntriesMajor(fromKey, fromInclusive, ascSortOrder, atomicOperation)));
    }

    var stream =
        svTree.iterateEntriesBetween(fromKey, fromInclusive, toKey, toInclusive, ascSortOrder,
            atomicOperation);

    var filteredStream = indexesSnapshot.visibilityFilter(atomicOperation, stream);
    return mapSVStream(filteredStream);
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
    return svTreeEntries(atomicOperation);
  }

  private long svTreeEntries(@Nonnull AtomicOperation atomicOperation) {
    return stream(null, atomicOperation).count() + get(null, atomicOperation).count();
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
    final var userKeys = keys.subList(0, keys.size() - 2);
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

    // keyStream() extracts the original key from CompositeKey(key, RID)
    try (var keys = keyStream(atomicOperation)) {
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
