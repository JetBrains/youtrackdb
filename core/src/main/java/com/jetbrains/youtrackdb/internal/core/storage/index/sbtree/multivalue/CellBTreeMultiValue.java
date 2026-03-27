package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue;

import com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.io.IOException;
import java.util.stream.Stream;
import javax.annotation.Nonnull;

public interface CellBTreeMultiValue<K> {

  void create(
      BinarySerializer<K> keySerializer,
      PropertyTypeInternal[] keyTypes,
      int keySize,
      @Nonnull AtomicOperation atomicOperation)
      throws IOException;

  Stream<RID> get(K key);

  void put(@Nonnull AtomicOperation atomicOperation, K key, RID value) throws IOException;

  void close();

  void delete(@Nonnull AtomicOperation atomicOperation) throws IOException;

  void load(
      String name,
      int keySize,
      PropertyTypeInternal[] keyTypes,
      BinarySerializer<K> keySerializer);

  long size();

  boolean remove(@Nonnull AtomicOperation atomicOperation, K key, RID value) throws IOException;

  Stream<RawPair<K, RID>> iterateEntriesMinor(K key, boolean inclusive, boolean ascSortOrder);

  Stream<RawPair<K, RID>> iterateEntriesMajor(K key, boolean inclusive, boolean ascSortOrder);

  K firstKey();

  K lastKey();

  Stream<K> keyStream();

  Stream<RawPair<K, RID>> iterateEntriesBetween(
      K keyFrom, boolean fromInclusive, K keyTo, boolean toInclusive, boolean ascSortOrder);

  void acquireAtomicExclusiveLock();
}
