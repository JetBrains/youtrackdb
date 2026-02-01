package com.jetbrains.youtrackdb.internal.core.index.engine.v1;

import com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.config.IndexEngineData;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.index.IndexException;
import com.jetbrains.youtrackdb.internal.core.index.IndexMetadata;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValidator;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValuesTransformer;
import com.jetbrains.youtrackdb.internal.core.index.engine.SingleValueIndexEngine;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.CellBTreeSingleValue;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.BTree;
import java.io.IOException;
import java.util.stream.Stream;

public final class BTreeSingleValueIndexEngine
    implements SingleValueIndexEngine, BTreeIndexEngine {

  private static final String DATA_FILE_EXTENSION = ".cbt";
  private static final String NULL_BUCKET_FILE_EXTENSION = ".nbt";

  private final CellBTreeSingleValue<Object> sbTree;
  private final String name;
  private final int id;
  private final AbstractStorage storage;

  public BTreeSingleValueIndexEngine(
      int id, String name, AbstractStorage storage, int version) {
    this.name = name;
    this.id = id;
    this.storage = storage;

    if (version == 3 || version == 4) {
      this.sbTree =
          new BTree<>(
              name, DATA_FILE_EXTENSION, NULL_BUCKET_FILE_EXTENSION, storage);
    } else {
      throw new IllegalStateException("Invalid tree version " + version);
    }
  }

  @Override
  public int id() {
    return id;
  }

  @Override
  public void init(DatabaseSessionInternal session, IndexMetadata metadata) {
  }

  @Override
  public void flush() {
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public void create(AtomicOperation atomicOperation, IndexEngineData data) throws IOException {
    BinarySerializer keySerializer = storage.resolveObjectSerializer(data.getKeySerializedId());
    var serializerFactory = storage.getComponentsFactory().binarySerializerFactory;
    try {
      sbTree.create(
          atomicOperation, keySerializer, data.getKeyTypes(), data.getKeySize());
    } catch (IOException e) {
      throw BaseException.wrapException(
          new IndexException(storage.getName(), "Error of creation of index " + name),
          e, storage.getName());
    }
  }

  @Override
  public void delete(final AtomicOperation atomicOperation) {
    try {
      doClearTree(atomicOperation);
      sbTree.delete(atomicOperation);
    } catch (IOException e) {
      throw BaseException.wrapException(
          new IndexException(storage.getName(), "Error during deletion of index " + name), e,
          storage.getName());
    }
  }

  private void doClearTree(AtomicOperation atomicOperation) throws IOException {
    try (var stream = sbTree.keyStream()) {
      stream.forEach(
          (key) -> {
            try {
              sbTree.remove(atomicOperation, key);
            } catch (IOException e) {
              throw BaseException.wrapException(
                  new IndexException(storage.getName(), "Can not clear index"), e,
                  storage.getName());
            }
          });
    }
    sbTree.remove(atomicOperation, null);
  }

  @Override
  public void load(IndexEngineData data) {
    var keySize = data.getKeySize();
    var keyTypes = data.getKeyTypes();
    BinarySerializer keySerializer = storage.resolveObjectSerializer(data.getKeySerializedId());
    var serializerFactory = storage.getComponentsFactory().binarySerializerFactory;
    sbTree.load(name, keySize, keyTypes, keySerializer);
  }

  @Override
  public boolean remove(AtomicOperation atomicOperation, Object key) {
    try {
      return sbTree.remove(atomicOperation, key) != null;
    } catch (IOException e) {
      throw BaseException.wrapException(
          new IndexException(storage.getName(),
              "Error during removal of key " + key + " from index " + name), e,
          storage.getName());
    }
  }

  @Override
  public void clear(Storage storage, AtomicOperation atomicOperation) {
    try {
      doClearTree(atomicOperation);
    } catch (IOException e) {
      throw BaseException.wrapException(
          new IndexException(storage.getName(), "Error during clear of index " + name),
          e, storage.getName());
    }
  }

  @Override
  public void close() {
    sbTree.close();
  }

  @Override
  public Stream<RID> get(Object key) {
    final var rid = sbTree.get(key);
    if (rid == null) {
      return Stream.empty();
    }
    return Stream.of(rid);
  }

  @Override
  public Stream<RawPair<Object, RID>> stream(IndexEngineValuesTransformer valuesTransformer) {
    final var firstKey = sbTree.firstKey();
    if (firstKey == null) {
      return Stream.empty();
    }
    return sbTree.iterateEntriesMajor(firstKey, true, true);
  }

  @Override
  public Stream<RawPair<Object, RID>> descStream(
      IndexEngineValuesTransformer valuesTransformer) {
    final var lastKey = sbTree.lastKey();
    if (lastKey == null) {
      return Stream.empty();
    }
    return sbTree.iterateEntriesMinor(lastKey, true, false);
  }

  @Override
  public Stream<Object> keyStream() {
    return sbTree.keyStream();
  }

  @Override
  public void put(AtomicOperation atomicOperation, Object key, RID value) {
    try {
      sbTree.put(atomicOperation, key, value);
    } catch (IOException e) {
      throw BaseException.wrapException(
          new IndexException(storage.getName(),
              "Error during insertion of key " + key + " into index " + name), e,
          storage.getName());
    }
  }

  @Override
  public boolean validatedPut(
      AtomicOperation atomicOperation,
      Object key,
      RID value,
      IndexEngineValidator<Object, RID> validator) {
    try {
      return sbTree.validatedPut(atomicOperation, key, value, validator);
    } catch (IOException e) {
      throw BaseException.wrapException(
          new IndexException(storage.getName(),
              "Error during insertion of key " + key + " into index " + name), e,
          storage.getName());
    }
  }

  @Override
  public Stream<RawPair<Object, RID>> iterateEntriesBetween(
      DatabaseSessionEmbedded db, Object rangeFrom,
      boolean fromInclusive,
      Object rangeTo,
      boolean toInclusive,
      boolean ascSortOrder,
      IndexEngineValuesTransformer transformer) {
    return sbTree.iterateEntriesBetween(
        rangeFrom, fromInclusive, rangeTo, toInclusive, ascSortOrder);
  }

  @Override
  public Stream<RawPair<Object, RID>> iterateEntriesMajor(
      Object fromKey,
      boolean isInclusive,
      boolean ascSortOrder,
      IndexEngineValuesTransformer transformer) {
    return sbTree.iterateEntriesMajor(fromKey, isInclusive, ascSortOrder);
  }

  @Override
  public Stream<RawPair<Object, RID>> iterateEntriesMinor(
      Object toKey,
      boolean isInclusive,
      boolean ascSortOrder,
      IndexEngineValuesTransformer transformer) {
    return sbTree.iterateEntriesMinor(toKey, isInclusive, ascSortOrder);
  }

  @Override
  public long size(Storage storage, final IndexEngineValuesTransformer transformer) {
    return sbTree.size();
  }

  @Override
  public boolean hasRangeQuerySupport() {
    return true;
  }

  @Override
  public boolean acquireAtomicExclusiveLock() {
    sbTree.acquireAtomicExclusiveLock();
    return true;
  }

  @Override
  public String getIndexNameByKey(Object key) {
    return name;
  }
}
