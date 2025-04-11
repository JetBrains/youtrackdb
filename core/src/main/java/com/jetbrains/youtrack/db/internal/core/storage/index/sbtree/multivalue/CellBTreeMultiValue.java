package com.jetbrains.youtrack.db.internal.core.storage.index.sbtree.multivalue;

import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.io.IOException;
import java.util.stream.Stream;

public interface CellBTreeMultiValue<K> {

  void create(
      BinarySerializer<K> keySerializer,
      PropertyTypeInternal[] keyTypes,
      int keySize,
      AtomicOperation atomicOperation)
      throws IOException;

  Stream<RID> get(K key);

  void put(AtomicOperation atomicOperation, K key, RID value) throws IOException;

  void close();

  void delete(AtomicOperation atomicOperation) throws IOException;

  void load(
      String name,
      int keySize,
      PropertyTypeInternal[] keyTypes,
      BinarySerializer<K> keySerializer);

  long size();

  boolean remove(AtomicOperation atomicOperation, K key, RID value) throws IOException;

  Stream<RawPair<K, RID>> iterateEntriesMinor(K key, boolean inclusive, boolean ascSortOrder);

  Stream<RawPair<K, RID>> iterateEntriesMajor(K key, boolean inclusive, boolean ascSortOrder);

  K firstKey();

  K lastKey();

  Stream<K> keyStream();

  Stream<RawPair<K, RID>> iterateEntriesBetween(
      K keyFrom, boolean fromInclusive, K keyTo, boolean toInclusive, boolean ascSortOrder);

  void acquireAtomicExclusiveLock();
}
