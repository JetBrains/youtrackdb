package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue;

import com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValidator;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.io.IOException;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface CellBTreeSingleValue<K> {

  void create(
      AtomicOperation atomicOperation,
      BinarySerializer<K> keySerializer,
      PropertyTypeInternal[] keyTypes,
      int keySize)
      throws IOException;

  @Nullable
  RID get(K key, @Nonnull AtomicOperation atomicOperation);

  void put(AtomicOperation atomicOperation, K key, RID value) throws IOException;

  boolean validatedPut(
      AtomicOperation atomicOperation, K key, RID value,
      IndexEngineValidator<K, RID> validator)
      throws IOException;

  void close();

  void delete(AtomicOperation atomicOperation) throws IOException;

  void load(
      String name,
      int keySize,
      PropertyTypeInternal[] keyTypes,
      BinarySerializer<K> keySerializer, AtomicOperation atomicOperation);

  long size(AtomicOperation atomicOperation);

  @Nullable
  RID remove(AtomicOperation atomicOperation, K key) throws IOException;

  Stream<RawPair<K, RID>> iterateEntriesMinor(K key, boolean inclusive, boolean ascSortOrder,
      AtomicOperation atomicOperation);

  Stream<RawPair<K, RID>> iterateEntriesMajor(K key, boolean inclusive, boolean ascSortOrder,
      AtomicOperation atomicOperation);

  @Nullable
  K firstKey(AtomicOperation atomicOperation);

  @Nullable
  K lastKey(AtomicOperation atomicOperation);

  Stream<K> keyStream(AtomicOperation atomicOperation);

  Stream<RawPair<K, RID>> allEntries(AtomicOperation atomicOperation);

  Stream<RawPair<K, RID>> iterateEntriesBetween(
      K keyFrom, boolean fromInclusive, K keyTo, boolean toInclusive, boolean ascSortOrder,
      AtomicOperation atomicOperation);

  void acquireAtomicExclusiveLock(@Nonnull AtomicOperation atomicOperation);
}
