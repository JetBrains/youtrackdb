package com.jetbrains.youtrackdb.internal.core.index.engine.v1;

import com.jetbrains.youtrackdb.api.exception.BaseException;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.common.collection.YTDBIteratorUtils;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.config.IndexEngineData;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.index.IndexException;
import com.jetbrains.youtrackdb.internal.core.index.engine.MultiValueIndexEngine;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl.CompactedLinkSerializer;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl.index.IndexMultiValuKeySerializer;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.CellBTreeSingleValue;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.BTree;
import java.io.IOException;
import java.util.Iterator;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;

public final class BTreeMultiValueIndexEngine
    implements MultiValueIndexEngine, BTreeIndexEngine {

  public static final String DATA_FILE_EXTENSION = ".cbt";
  private static final String NULL_BUCKET_FILE_EXTENSION = ".nbt";
  public static final String M_CONTAINER_EXTENSION = ".mbt";

  @Nonnull
  private final CellBTreeSingleValue<CompositeKey> svTree;
  @Nonnull
  private final CellBTreeSingleValue<Identifiable> nullTree;

  private final String name;
  private final int id;
  private final String nullTreeName;
  private final AbstractStorage storage;

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
    } else {
      throw new IllegalStateException("Invalid tree version " + version);
    }
  }

  @Override
  public int getId() {
    return id;
  }

  @Override
  public void flush() {
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public void create(AtomicOperation atomicOperation, IndexEngineData data) throws IOException {
    try {
      final var sbTypes = calculateTypes(data.getKeyTypes());
      svTree.create(
          atomicOperation,
          new IndexMultiValuKeySerializer(),
          sbTypes,
          data.getKeySize() + 1
      );
      nullTree.create(
          atomicOperation, CompactedLinkSerializer.INSTANCE,
          new PropertyTypeInternal[]{PropertyTypeInternal.LINK}, 1);
    } catch (IOException e) {
      throw BaseException.wrapException(
          new IndexException(storage.getName(), "Error during creation of index " + name), e,
          storage.getName());
    }
  }

  @Override
  public void delete(AtomicOperation atomicOperation) {
    try {
      doClearSVTree(atomicOperation);
      svTree.delete(atomicOperation);
      nullTree.delete(atomicOperation);
    } catch (IOException e) {
      throw BaseException.wrapException(
          new IndexException(storage.getName(), "Error during deletion of index " + name), e,
          storage.getName());
    }
  }


  private void doClearSVTree(final AtomicOperation atomicOperation) {
    {
      final var firstKey = svTree.firstKey();
      final var lastKey = svTree.lastKey();

      try (var iterator =
          svTree.iterateEntriesBetween(firstKey, true, lastKey, true, true)) {
        iterator.forEachRemaining(
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
      final var firstKey = nullTree.firstKey();
      final var lastKey = nullTree.lastKey();

      if (firstKey != null && lastKey != null) {
        try (var iterator =
            nullTree.iterateEntriesBetween(firstKey, true, lastKey, true, true)) {
          iterator.forEachRemaining(
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
  public void load(IndexEngineData data) {
    var name = data.getName();
    var keySize = data.getKeySize();
    var keyTypes = data.getKeyTypes();
    final var sbTypes = calculateTypes(keyTypes);

    svTree.load(name, keySize + 1, sbTypes, new IndexMultiValuKeySerializer());
    nullTree.load(
        nullTreeName, 1, new PropertyTypeInternal[]{PropertyTypeInternal.LINK},
        CompactedLinkSerializer.INSTANCE
    );
  }

  @Override
  public boolean remove(final AtomicOperation atomicOperation, Object key, RID value) {
    try {
      if (key != null) {
        final var compositeKey = createCompositeKey(key, value);

        final var removed = new boolean[1];
        try (var iterator =
            svTree.iterateEntriesBetween(compositeKey, true, compositeKey, true, true)) {
          iterator.forEachRemaining(
              (pair) -> {
                try {
                  final var result = svTree.remove(atomicOperation, pair.first()) != null;
                  removed[0] = result || removed[0];
                } catch (final IOException e) {
                  throw BaseException.wrapException(
                      new IndexException(storage.getName(),
                          "Error during remove of entry (" + key + ", " + value + ")"),
                      e, storage.getName());
                }
              });
        }

        return removed[0];
      } else {
        return nullTree.remove(atomicOperation, value) != null;
      }
    } catch (IOException e) {
      throw BaseException.wrapException(
          new IndexException(storage.getName(),
              "Error during removal of entry with key "
                  + key
                  + "and RID "
                  + value
                  + " from index "
                  + name),
          e, storage.getName());
    }
  }

  @Override
  public void clear(Storage storage, AtomicOperation atomicOperation) {
    doClearSVTree(atomicOperation);
  }

  @Override
  public void close() {
    svTree.close();
    nullTree.close();
  }

  @Override
  public Iterator<RID> get(Object key) {
    if (key != null) {
      final var firstKey = convertToCompositeKey(key);
      final var lastKey = convertToCompositeKey(key);

      return YTDBIteratorUtils.map(svTree
              .iterateEntriesBetween(firstKey, true, lastKey, true, true),
          RawPair::second);
    } else {
      return YTDBIteratorUtils.map(nullTree
          .iterateEntriesBetween(
              new RecordId(0, 0), true,
              new RecordId(Short.MAX_VALUE, Long.MAX_VALUE), true,
              true), RawPair::second);
    }
  }

  @Override
  public CloseableIterator<RawPair<Object, RID>> ascEntries() {
    final var firstKey = svTree.firstKey();
    if (firstKey == null) {
      return IteratorUtils.emptyIterator();
    }

    return mapSVIterator(svTree.iterateEntriesMajor(firstKey, true, true));
  }

  private static Iterator<RawPair<Object, RID>> mapSVIterator(
      Iterator<RawPair<CompositeKey, RID>> iterator) {
    return YTDBIteratorUtils.map(iterator,
        entry -> new RawPair<>(extractKey(entry.first()), entry.second()));
  }


  @Override
  public CloseableIterator<RawPair<Object, RID>> descEntries() {
    final var lastKey = svTree.lastKey();
    if (lastKey == null) {
      return IteratorUtils.emptyIterator();
    }
    return mapSVIterator(svTree.iterateEntriesMinor(lastKey, true, false));
  }

  @Override
  public CloseableIterator<Object> keys() {
    return YTDBIteratorUtils.map(svTree.keys(), BTreeMultiValueIndexEngine::extractKey);
  }

  @Override
  public void put(AtomicOperation atomicOperation, Object key, RID value) {
    if (key != null) {
      try {
        svTree.put(atomicOperation, createCompositeKey(key, value), value);
      } catch (IOException e) {
        throw BaseException.wrapException(
            new IndexException(storage.getName(),
                "Error during insertion of key " + key + " and RID " + value + " to index " + name),
            e, storage.getName());
      }
    } else {
      try {
        nullTree.put(atomicOperation, value, value);
      } catch (IOException e) {
        throw BaseException.wrapException(
            new IndexException(storage.getName(),
                "Error during insertion of null key and RID " + value + " to index " + name),
            e, storage.getName());
      }
    }
  }

  @Override
  public CloseableIterator<RawPair<Object, RID>> iterateEntriesBetween(
      DatabaseSessionEmbedded db, Object rangeFrom,
      boolean fromInclusive,
      Object rangeTo,
      boolean toInclusive,
      boolean ascSortOrder) {
    // "from", "to" are null, then scan whole tree as for infinite range
    if (rangeFrom == null && rangeTo == null) {
      return mapSVIterator(svTree.allEntries());
    }

    // "from" could be null, then "to" is not (minor)
    final var toKey = convertToCompositeKey(rangeTo);
    if (rangeFrom == null) {
      return mapSVIterator(svTree.iterateEntriesMinor(toKey, toInclusive, ascSortOrder));
    }
    final var fromKey = convertToCompositeKey(rangeFrom);
    // "to" could be null, then "from" is not (major)
    if (rangeTo == null) {
      return mapSVIterator(svTree.iterateEntriesMajor(fromKey, fromInclusive, ascSortOrder));
    }
    return mapSVIterator(
        svTree.iterateEntriesBetween(fromKey, fromInclusive, toKey, toInclusive, ascSortOrder));
  }

  private static CompositeKey convertToCompositeKey(Object rangeFrom) {
    CompositeKey firstKey;
    if (rangeFrom instanceof CompositeKey) {
      firstKey = (CompositeKey) rangeFrom;
    } else {
      firstKey = new CompositeKey(rangeFrom);
    }
    return firstKey;
  }

  @Override
  public CloseableIterator<RawPair<Object, RID>> iterateEntriesMajor(
      Object fromKey,
      boolean isInclusive,
      boolean ascSortOrder) {
    final var firstKey = convertToCompositeKey(fromKey);
    return mapSVIterator(svTree.iterateEntriesMajor(firstKey, isInclusive, ascSortOrder));
  }

  @Override
  public CloseableIterator<RawPair<Object, RID>> iterateEntriesMinor(
      Object toKey,
      boolean isInclusive,
      boolean ascSortOrder) {
    final var lastKey = convertToCompositeKey(toKey);
    return mapSVIterator(svTree.iterateEntriesMinor(lastKey, isInclusive, ascSortOrder));
  }

  @Override
  public long size(Storage storage) {
    return svTreeEntries();
  }

  private long svTreeEntries() {
    return svTree.size() + nullTree.size();
  }

  @Override
  public boolean hasRangeQuerySupport() {
    return true;
  }

  @Override
  public void acquireAtomicExclusiveLock() {
    svTree.acquireAtomicExclusiveLock();
    nullTree.acquireAtomicExclusiveLock();

  }

  private static PropertyTypeInternal[] calculateTypes(final PropertyTypeInternal[] keyTypes) {
    final PropertyTypeInternal[] sbTypes;
    if (keyTypes != null) {
      sbTypes = new PropertyTypeInternal[keyTypes.length + 1];
      System.arraycopy(keyTypes, 0, sbTypes, 0, keyTypes.length);
      sbTypes[sbTypes.length - 1] = PropertyTypeInternal.LINK;
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

  @Nullable
  private static Object extractKey(final CompositeKey compositeKey) {
    if (compositeKey == null) {
      return null;
    }
    final var keys = compositeKey.getKeys();

    final Object key;
    if (keys.size() == 2) {
      key = keys.getFirst();
    } else {
      key = new CompositeKey(keys.subList(0, keys.size() - 1));
    }
    return key;
  }
}
