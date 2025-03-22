package com.jetbrains.youtrack.db.internal.core.index.engine.v1;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.config.IndexEngineData;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.exception.StorageException;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.index.CompositeKey;
import com.jetbrains.youtrack.db.internal.core.index.IndexException;
import com.jetbrains.youtrack.db.internal.core.index.IndexMetadata;
import com.jetbrains.youtrack.db.internal.core.index.engine.IndexEngineValuesTransformer;
import com.jetbrains.youtrack.db.internal.core.index.engine.MultiValueIndexEngine;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.binary.impl.CompactedLinkSerializer;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.binary.impl.index.IndexMultiValuKeySerializer;
import com.jetbrains.youtrack.db.internal.core.storage.Storage;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractPaginatedStorage;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrack.db.internal.core.storage.index.sbtree.multivalue.CellBTreeMultiValue;
import com.jetbrains.youtrack.db.internal.core.storage.index.sbtree.multivalue.v2.CellBTreeMultiValueV2;
import com.jetbrains.youtrack.db.internal.core.storage.index.sbtree.singlevalue.CellBTreeSingleValue;
import com.jetbrains.youtrack.db.internal.core.storage.index.sbtree.singlevalue.v3.CellBTreeSingleValueV3;
import java.io.IOException;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.annotation.Nonnull;

public final class CellBTreeMultiValueIndexEngine
    implements MultiValueIndexEngine, CellBTreeIndexEngine {

  public static final String DATA_FILE_EXTENSION = ".cbt";
  private static final String NULL_BUCKET_FILE_EXTENSION = ".nbt";
  public static final String M_CONTAINER_EXTENSION = ".mbt";

  private final CellBTreeMultiValue<Object> mvTree;

  private final CellBTreeSingleValue<CompositeKey> svTree;
  private final CellBTreeSingleValue<Identifiable> nullTree;

  private final String name;
  private final int id;
  private final String nullTreeName;
  private final AbstractPaginatedStorage storage;

  public CellBTreeMultiValueIndexEngine(
      int id, @Nonnull String name, AbstractPaginatedStorage storage, final int version) {
    this.id = id;
    this.name = name;
    this.storage = storage;
    nullTreeName = name + "$null";

    if (version == 1) {
      throw new IllegalArgumentException("Unsupported version of index : " + version);
    } else if (version == 2) {
      this.mvTree =
          new CellBTreeMultiValueV2<>(
              name,
              DATA_FILE_EXTENSION,
              NULL_BUCKET_FILE_EXTENSION,
              M_CONTAINER_EXTENSION,
              storage);
      this.svTree = null;
      this.nullTree = null;
    } else if (version == 3) {
      throw new IllegalArgumentException("Unsupported version of index : " + version);
    } else if (version == 4) {
      mvTree = null;
      svTree =
          new CellBTreeSingleValueV3<>(
              name, DATA_FILE_EXTENSION, NULL_BUCKET_FILE_EXTENSION, storage);
      nullTree =
          new CellBTreeSingleValueV3<>(
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
  public void init(DatabaseSessionInternal db, IndexMetadata metadata) {
  }

  @Override
  public void flush() {
  }

  @Override
  public String getName() {
    return name;
  }

  public void create(AtomicOperation atomicOperation, IndexEngineData data) throws IOException {

    BinarySerializer keySerializer = storage.resolveObjectSerializer(data.getKeySerializedId());
    var serializerFactory = storage.getComponentsFactory().binarySerializerFactory;

    try {
      if (mvTree != null) {
        mvTree.create(
            keySerializer, data.getKeyTypes(), data.getKeySize(), atomicOperation);
      } else {
        final var sbTypes = calculateTypes(data.getKeyTypes());
        assert svTree != null;
        assert nullTree != null;

        svTree.create(
            atomicOperation,
            new IndexMultiValuKeySerializer(),
            sbTypes,
            data.getKeySize() + 1
        );
        nullTree.create(
            atomicOperation, CompactedLinkSerializer.INSTANCE,
            new PropertyTypeInternal[]{PropertyTypeInternal.LINK}, 1);
      }
    } catch (IOException e) {
      throw BaseException.wrapException(
          new IndexException(storage.getName(), "Error during creation of index " + name), e,
          storage.getName());
    }
  }

  @Override
  public void delete(AtomicOperation atomicOperation) {
    try {
      if (mvTree != null) {
        doClearMVTree(atomicOperation);
        mvTree.delete(atomicOperation);
      } else {
        assert svTree != null;
        assert nullTree != null;

        doClearSVTree(atomicOperation);
        svTree.delete(atomicOperation);
        nullTree.delete(atomicOperation);
      }
    } catch (IOException e) {
      throw BaseException.wrapException(
          new IndexException(storage.getName(), "Error during deletion of index " + name), e,
          storage.getName());
    }
  }

  private void doClearMVTree(final AtomicOperation atomicOperation) {
    assert mvTree != null;

    final var firstKey = mvTree.firstKey();
    final var lastKey = mvTree.lastKey();

    try (var stream =
        mvTree.iterateEntriesBetween(firstKey, true, lastKey, true, true)) {
      stream.forEach(
          (pair) -> {
            try {
              mvTree.remove(atomicOperation, pair.first, pair.second);
            } catch (IOException e) {
              throw BaseException.wrapException(
                  new IndexException(storage.getName(), "Error during cleaning of index " + name),
                  e,
                  storage.getName());
            }
          });
    }

    try (final var rids = mvTree.get(null)) {
      rids.forEach(
          (rid) -> {
            try {
              mvTree.remove(atomicOperation, null, rid);
            } catch (final IOException e) {
              throw BaseException.wrapException(
                  new StorageException(null, "Error during cleaning of index " + name), e,
                  storage.getName());
            }
          });
    }
  }

  private void doClearSVTree(final AtomicOperation atomicOperation) {
    assert svTree != null;
    assert nullTree != null;

    {
      final var firstKey = svTree.firstKey();
      final var lastKey = svTree.lastKey();

      try (var stream =
          svTree.iterateEntriesBetween(firstKey, true, lastKey, true, true)) {
        stream.forEach(
            (pair) -> {
              try {
                svTree.remove(atomicOperation, pair.first);
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
        try (var stream =
            nullTree.iterateEntriesBetween(firstKey, true, lastKey, true, true)) {
          stream.forEach(
              (pair) -> {
                try {
                  nullTree.remove(atomicOperation, pair.first);
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
    BinarySerializer keySerializer = storage.resolveObjectSerializer(data.getKeySerializedId());
    var serializerFactory = storage.getComponentsFactory().binarySerializerFactory;

    if (mvTree != null) {
      //noinspection unchecked
      mvTree.load(name, keySize, keyTypes, keySerializer);
    } else {
      assert svTree != null;
      assert nullTree != null;

      final var sbTypes = calculateTypes(keyTypes);

      svTree.load(name, keySize + 1, sbTypes, new IndexMultiValuKeySerializer());
      nullTree.load(
          nullTreeName, 1, new PropertyTypeInternal[]{PropertyTypeInternal.LINK},
          CompactedLinkSerializer.INSTANCE
      );
    }
  }

  @Override
  public boolean remove(final AtomicOperation atomicOperation, Object key, RID value) {
    try {
      if (mvTree != null) {
        return mvTree.remove(atomicOperation, key, value);
      } else {
        if (key != null) {
          assert svTree != null;

          final var compositeKey = createCompositeKey(key, value);

          final var removed = new boolean[1];
          try (var stream =
              svTree.iterateEntriesBetween(compositeKey, true, compositeKey, true, true)) {
            stream.forEach(
                (pair) -> {
                  try {
                    final var result = svTree.remove(atomicOperation, pair.first) != null;
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
          assert nullTree != null;
          return nullTree.remove(atomicOperation, value) != null;
        }
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
    if (mvTree != null) {
      doClearMVTree(atomicOperation);
    } else {
      doClearSVTree(atomicOperation);
    }
  }

  @Override
  public void close() {
    if (mvTree != null) {
      mvTree.close();
    } else {
      assert svTree != null;
      assert nullTree != null;

      svTree.close();
      nullTree.close();
    }
  }

  @Override
  public Stream<RID> get(Object key) {
    if (mvTree != null) {
      return mvTree.get(key);
    } else if (key != null) {
      assert svTree != null;

      final var firstKey = convertToCompositeKey(key);
      final var lastKey = convertToCompositeKey(key);

      //noinspection resource
      return svTree
          .iterateEntriesBetween(firstKey, true, lastKey, true, true)
          .map((pair) -> pair.second);
    } else {
      assert nullTree != null;

      //noinspection resource
      return nullTree
          .iterateEntriesBetween(
              new RecordId(0, 0), true, new RecordId(Short.MAX_VALUE, Long.MAX_VALUE), true,
              true)
          .map((pair) -> pair.second);
    }
  }

  @Override
  public Stream<RawPair<Object, RID>> stream(IndexEngineValuesTransformer valuesTransformer) {
    if (mvTree != null) {
      final var firstKey = mvTree.firstKey();
      if (firstKey == null) {
        return emptyStream();
      }

      return mvTree.iterateEntriesMajor(firstKey, true, true);
    } else {
      assert svTree != null;

      final var firstKey = svTree.firstKey();
      if (firstKey == null) {
        return emptyStream();
      }

      return mapSVStream(svTree.iterateEntriesMajor(firstKey, true, true));
    }
  }

  private static Stream<RawPair<Object, RID>> mapSVStream(
      Stream<RawPair<CompositeKey, RID>> stream) {
    return stream.map((entry) -> new RawPair<>(extractKey(entry.first), entry.second));
  }

  private static Stream<RawPair<Object, RID>> emptyStream() {
    return StreamSupport.stream(Spliterators.emptySpliterator(), false);
  }

  @Override
  public Stream<RawPair<Object, RID>> descStream(
      IndexEngineValuesTransformer valuesTransformer) {
    if (mvTree != null) {
      final var lastKey = mvTree.lastKey();
      if (lastKey == null) {
        return emptyStream();
      }
      return mvTree.iterateEntriesMinor(lastKey, true, false);
    } else {
      assert svTree != null;

      final var lastKey = svTree.lastKey();
      if (lastKey == null) {
        return emptyStream();
      }
      return mapSVStream(svTree.iterateEntriesMinor(lastKey, true, false));
    }
  }

  @Override
  public Stream<Object> keyStream() {
    if (mvTree != null) {
      return mvTree.keyStream();
    }

    assert svTree != null;
    //noinspection resource
    return svTree.keyStream().map(CellBTreeMultiValueIndexEngine::extractKey);
  }

  @Override
  public void put(AtomicOperation atomicOperation, Object key, RID value) {
    if (mvTree != null) {
      try {
        mvTree.put(atomicOperation, key, value);
      } catch (IOException e) {
        throw BaseException.wrapException(
            new IndexException(storage.getName(),
                "Error during insertion of key " + key + " and RID " + value + " to index " + name),
            e, storage.getName());
      }
    } else if (key != null) {
      assert svTree != null;
      try {
        svTree.put(atomicOperation, createCompositeKey(key, value), value);
      } catch (IOException e) {
        throw BaseException.wrapException(
            new IndexException(storage.getName(),
                "Error during insertion of key " + key + " and RID " + value + " to index " + name),
            e, storage.getName());
      }
    } else {
      assert nullTree != null;
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
  public Stream<RawPair<Object, RID>> iterateEntriesBetween(
      DatabaseSessionInternal db, Object rangeFrom,
      boolean fromInclusive,
      Object rangeTo,
      boolean toInclusive,
      boolean ascSortOrder,
      IndexEngineValuesTransformer transformer) {
    if (mvTree != null) {
      return mvTree.iterateEntriesBetween(
          rangeFrom, fromInclusive, rangeTo, toInclusive, ascSortOrder);
    }
    assert svTree != null;

    // "from", "to" are null, then scan whole tree as for infinite range
    if (rangeFrom == null && rangeTo == null) {
      return mapSVStream(svTree.allEntries());
    }

    // "from" could be null, then "to" is not (minor)
    final var toKey = convertToCompositeKey(rangeTo);
    if (rangeFrom == null) {
      return mapSVStream(svTree.iterateEntriesMinor(toKey, toInclusive, ascSortOrder));
    }
    final var fromKey = convertToCompositeKey(rangeFrom);
    // "to" could be null, then "from" is not (major)
    if (rangeTo == null) {
      return mapSVStream(svTree.iterateEntriesMajor(fromKey, fromInclusive, ascSortOrder));
    }
    return mapSVStream(
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
  public Stream<RawPair<Object, RID>> iterateEntriesMajor(
      Object fromKey,
      boolean isInclusive,
      boolean ascSortOrder,
      IndexEngineValuesTransformer transformer) {
    if (mvTree != null) {
      return mvTree.iterateEntriesMajor(fromKey, isInclusive, ascSortOrder);
    }
    assert svTree != null;

    final var firstKey = convertToCompositeKey(fromKey);
    return mapSVStream(svTree.iterateEntriesMajor(firstKey, isInclusive, ascSortOrder));
  }

  @Override
  public Stream<RawPair<Object, RID>> iterateEntriesMinor(
      Object toKey,
      boolean isInclusive,
      boolean ascSortOrder,
      IndexEngineValuesTransformer transformer) {
    if (mvTree != null) {
      return mvTree.iterateEntriesMinor(toKey, isInclusive, ascSortOrder);
    }
    assert svTree != null;

    final var lastKey = convertToCompositeKey(toKey);
    return mapSVStream(svTree.iterateEntriesMinor(lastKey, isInclusive, ascSortOrder));
  }

  @Override
  public long size(Storage storage, final IndexEngineValuesTransformer transformer) {
    if (mvTree != null) {
      return mvTreeSize(transformer);
    }

    assert svTree != null;
    assert nullTree != null;

    return svTreeEntries();
  }

  private long mvTreeSize(final IndexEngineValuesTransformer transformer) {
    assert mvTree != null;

    // calculate amount of keys
    if (transformer == null) {
      final var firstKey = mvTree.firstKey();
      final var lastKey = mvTree.lastKey();

      long counter = 0;

      try (var oridStream = mvTree.get(null)) {
        if (oridStream.iterator().hasNext()) {
          counter++;
        }
      }

      if (firstKey != null && lastKey != null) {
        final var prevKey = new Object[]{new Object()};
        try (final var stream =
            mvTree.iterateEntriesBetween(firstKey, true, lastKey, true, true)) {
          counter +=
              stream
                  .filter(
                      (pair) -> {
                        final var result = !prevKey[0].equals(pair.first);
                        prevKey[0] = pair.first;
                        return result;
                      })
                  .count();
        }
      }
      return counter;
    }
    // calculate amount of entries
    return mvTree.size();
  }

  private long svTreeEntries() {
    assert svTree != null;
    assert nullTree != null;
    return svTree.size() + nullTree.size();
  }

  @Override
  public boolean hasRangeQuerySupport() {
    return true;
  }

  @Override
  public boolean acquireAtomicExclusiveLock() {
    if (mvTree != null) {
      mvTree.acquireAtomicExclusiveLock();
    } else {
      assert svTree != null;
      assert nullTree != null;

      svTree.acquireAtomicExclusiveLock();
      nullTree.acquireAtomicExclusiveLock();
    }

    return true;
  }

  @Override
  public String getIndexNameByKey(Object key) {
    return name;
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
