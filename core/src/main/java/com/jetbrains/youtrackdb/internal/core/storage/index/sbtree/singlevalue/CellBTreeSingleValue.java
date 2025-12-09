package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue;

import com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValidator;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.io.IOException;
import java.util.stream.Stream;
import javax.annotation.Nullable;

public interface CellBTreeSingleValue<K> {

  void create(
      AtomicOperation atomicOperation,
      BinarySerializer<K> keySerializer,
      PropertyTypeInternal[] keyTypes,
      int keySize)
      throws IOException;

  @Nullable
  RID get(K key);

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
      BinarySerializer<K> keySerializer);

  long size();

  @Nullable
  RID remove(AtomicOperation atomicOperation, K key) throws IOException;

  Stream<RawPair<K, RID>> iterateEntriesMinor(K key, boolean inclusive, boolean ascSortOrder);

  Stream<RawPair<K, RID>> iterateEntriesMajor(K key, boolean inclusive, boolean ascSortOrder);

  @Nullable
  K firstKey();

  @Nullable
  K lastKey();

  Stream<K> keyStream();

  Stream<RawPair<K, RID>> allEntries();

  Stream<RawPair<K, RID>> iterateEntriesBetween(
      K keyFrom, boolean fromInclusive, K keyTo, boolean toInclusive, boolean ascSortOrder);

  void acquireAtomicExclusiveLock();
}
