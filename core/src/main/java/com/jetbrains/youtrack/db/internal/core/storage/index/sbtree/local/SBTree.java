package com.jetbrains.youtrack.db.internal.core.storage.index.sbtree.local;

import com.jetbrains.youtrack.db.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.index.IndexKeyUpdater;
import com.jetbrains.youtrack.db.internal.core.index.engine.IndexEngineValidator;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.io.IOException;
import java.util.stream.Stream;

public interface SBTree<K, V> {

  void create(
      AtomicOperation atomicOperation,
      BinarySerializer<K> keySerializer,
      BinarySerializer<V> valueSerializer,
      PropertyTypeInternal[] keyTypes,
      int keySize,
      boolean nullPointerSupport)
      throws IOException;

  boolean isNullPointerSupport();

  V get(K key);

  void put(AtomicOperation atomicOperation, K key, V value) throws IOException;

  boolean validatedPut(
      AtomicOperation atomicOperation, K key, V value, IndexEngineValidator<K, V> validator)
      throws IOException;

  boolean update(
      AtomicOperation atomicOperation,
      K key,
      IndexKeyUpdater<V> updater,
      IndexEngineValidator<K, V> validator)
      throws IOException;

  void close(boolean flush);

  void close();

  void delete(AtomicOperation atomicOperation) throws IOException;

  void load(
      String name,
      BinarySerializer<K> keySerializer,
      BinarySerializer<V> valueSerializer,
      PropertyTypeInternal[] keyTypes,
      int keySize,
      boolean nullPointerSupport);

  long size();

  V remove(AtomicOperation atomicOperation, K key) throws IOException;

  Stream<RawPair<K, V>> iterateEntriesMinor(K key, boolean inclusive, boolean ascSortOrder);

  Stream<RawPair<K, V>> iterateEntriesMajor(K key, boolean inclusive, boolean ascSortOrder);

  K firstKey();

  K lastKey();

  Stream<K> keyStream();

  Stream<RawPair<K, V>> iterateEntriesBetween(
      K keyFrom, boolean fromInclusive, K keyTo, boolean toInclusive, boolean ascSortOrder);

  void flush();

  void acquireAtomicExclusiveLock();
}
