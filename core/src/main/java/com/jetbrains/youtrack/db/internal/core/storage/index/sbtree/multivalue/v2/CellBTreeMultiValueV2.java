/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */

package com.jetbrains.youtrack.db.internal.core.storage.index.sbtree.multivalue.v2;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.TooBigIndexKeyException;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.comparator.DefaultComparator;
import com.jetbrains.youtrack.db.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.ByteSerializer;
import com.jetbrains.youtrack.db.internal.common.types.ModifiableLong;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.index.CompositeKey;
import com.jetbrains.youtrack.db.internal.core.index.comparator.AlwaysGreaterKey;
import com.jetbrains.youtrack.db.internal.core.index.comparator.AlwaysLessKey;
import com.jetbrains.youtrack.db.internal.core.iterator.EmptyIterator;
import com.jetbrains.youtrack.db.internal.core.iterator.EmptyMapEntryIterator;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrack.db.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractPaginatedStorage;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.base.DurableComponent;
import com.jetbrains.youtrack.db.internal.core.storage.index.sbtree.local.v2.SBTreeV2;
import com.jetbrains.youtrack.db.internal.core.storage.index.sbtree.multivalue.CellBTreeMultiValue;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * This is implementation which is based on B+-tree implementation threaded tree. The main
 * differences are:
 *
 * <ol>
 *   <li>Buckets are not compacted/removed if they are empty after deletion of item. They reused
 *       later when new items are added.
 *   <li>All non-leaf buckets have links to neighbor buckets which contain keys which are less/more
 *       than keys contained in current bucket
 *       <ol/>
 *         There is support of null values for keys, but values itself cannot be null. Null keys
 *         support is switched off by default if null keys are supported value which is related to
 *         null key will be stored in separate file which has only one page. Buckets/pages for usual
 *         (non-null) key-value entries can be considered as sorted array. The first bytes of page
 *         contains such auxiliary information as size of entries contained in bucket, links to
 *         neighbors which contain entries with keys less/more than keys in current bucket. The next
 *         bytes contain sorted array of entries. Array itself is split on two parts. First part is
 *         growing from start to end, and second part is growing from end to start. First part is
 *         array of offsets to real key-value entries which are stored in second part of array which
 *         grows from end to start. This array of offsets is sorted by accessing order according to
 *         key value. So we can use binary search to find requested key. When new key-value pair is
 *         added we append binary presentation of this pair to the second part of array which grows
 *         from end of page to start, remember value of offset for this pair, and find proper
 *         position of this offset inside of first part of array. Such approach allows to minimize
 *         amount of memory involved in performing of operations and as result speed up data
 *         processing.
 *
 * @since 8/7/13
 */
public final class CellBTreeMultiValueV2<K> extends DurableComponent
    implements CellBTreeMultiValue<K> {

  private static final int M_ID_BATCH_SIZE = 131_072;
  private static final int MAX_KEY_SIZE =
      GlobalConfiguration.BTREE_MAX_KEY_SIZE.getValueAsInteger();
  private static final AlwaysLessKey ALWAYS_LESS_KEY = new AlwaysLessKey();
  private static final AlwaysGreaterKey ALWAYS_GREATER_KEY = new AlwaysGreaterKey();

  private static final int MAX_PATH_LENGTH =
      GlobalConfiguration.BTREE_MAX_DEPTH.getValueAsInteger();

  private static final int ENTRY_POINT_INDEX = 0;
  private static final long ROOT_INDEX = 1;

  private final Comparator<? super K> comparator = DefaultComparator.INSTANCE;
  private final String nullFileExtension;
  private final String containerExtension;

  private long fileId;
  private long nullBucketFileId;

  private int keySize;
  private BinarySerializer<K> keySerializer;
  private PropertyTypeInternal[] keyTypes;

  private SBTreeV2<MultiValueEntry, Byte> multiContainer;
  private final ModifiableLong mIdCounter = new ModifiableLong();
  private final BinarySerializerFactory serializerFactory;

  public CellBTreeMultiValueV2(
      final String name,
      final String dataFileExtension,
      final String nullFileExtension,
      final String containerExtension,
      final AbstractPaginatedStorage storage) {
    super(storage, name, dataFileExtension, name + dataFileExtension);
    this.serializerFactory = storage.getComponentsFactory().binarySerializerFactory;
    acquireExclusiveLock();
    try {
      this.nullFileExtension = nullFileExtension;
      this.containerExtension = containerExtension;
    } finally {
      releaseExclusiveLock();
    }
  }

  public void create(
      final BinarySerializer<K> keySerializer,
      final PropertyTypeInternal[] keyTypes,
      final int keySize,
      AtomicOperation atomicOperation) {
    assert keySerializer != null;

    executeInsideComponentOperation(
        atomicOperation,
        operation -> {
          acquireExclusiveLock();
          try {
            this.keySize = keySize;

            if (keyTypes != null) {
              this.keyTypes = Arrays.copyOf(keyTypes, keyTypes.length);
            } else {
              this.keyTypes = null;
            }

            this.keySerializer = keySerializer;

            fileId = addFile(atomicOperation, getFullName());

            nullBucketFileId = addFile(atomicOperation, getName() + nullFileExtension);

            try (final var entryPointCacheEntry = addPage(atomicOperation, fileId)) {
              final var entryPoint =
                  new CellBTreeMultiValueV2EntryPoint<K>(entryPointCacheEntry);
              entryPoint.init();
            }

            try (final var rootCacheEntry = addPage(atomicOperation, fileId)) {
              @SuppressWarnings("unused") final var rootBucket =
                  new CellBTreeMultiValueV2Bucket<K>(rootCacheEntry);
              rootBucket.init(true);
            }

            try (final var nullBucketEntry = addPage(atomicOperation, nullBucketFileId)) {
              final var nullBucket =
                  new CellBTreeMultiValueV2NullBucket(nullBucketEntry);
              nullBucket.init(incrementMId(atomicOperation));
            }

            multiContainer = new SBTreeV2<>(getName(), containerExtension, null, storage);
            multiContainer.create(
                atomicOperation,
                MultiValueEntrySerializer.INSTANCE,
                ByteSerializer.INSTANCE,
                null,
                1,
                false
            );
          } finally {
            releaseExclusiveLock();
          }
        });
  }

  public Stream<RID> get(K key) {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final var atomicOperation = atomicOperationsManager.getCurrentOperation();
        if (key != null) {
          key = keySerializer.preprocess(serializerFactory, key, (Object[]) keyTypes);

          final var bucketSearchResult = findBucket(key, atomicOperation);
          if (bucketSearchResult.itemIndex < 0) {
            return Stream.empty();
          }

          final var pageIndex = bucketSearchResult.pageIndex;
          final var itemIndex = bucketSearchResult.itemIndex;

          long leftSibling = -1;
          long rightSibling = -1;

          final List<RID> result = new ArrayList<>(8);

          try (var cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex)) {
            final var bucket =
                new CellBTreeMultiValueV2Bucket<K>(cacheEntry);
            fetchValues(itemIndex, result, bucket);

            if (itemIndex == 0) {
              leftSibling = bucket.getLeftSibling();
            }

            if (itemIndex == bucket.size() - 1) {
              rightSibling = bucket.getRightSibling();
            }
          }

          while (leftSibling >= 0) {

            try (var cacheEntry = loadPageForRead(atomicOperation, fileId, leftSibling)) {
              @SuppressWarnings("ObjectAllocationInLoop") final var bucket =
                  new CellBTreeMultiValueV2Bucket<K>(cacheEntry);
              final var size = bucket.size();

              if (size > 0) {
                if (bucket.getKey(size - 1, keySerializer, serializerFactory).equals(key)) {
                  fetchValues(size - 1, result, bucket);

                  if (size == 1) {
                    leftSibling = bucket.getLeftSibling();
                  } else {
                    leftSibling = -1;
                  }
                } else {
                  leftSibling = -1;
                }
              } else {
                leftSibling = bucket.getLeftSibling();
              }
            }
          }

          while (rightSibling >= 0) {

            try (var cacheEntry = loadPageForRead(atomicOperation, fileId, rightSibling)) {
              final var bucket =
                  new CellBTreeMultiValueV2Bucket<K>(cacheEntry);
              final var size = bucket.size();

              if (size > 0) {
                if (bucket.getKey(0, keySerializer, serializerFactory).equals(key)) {
                  fetchValues(0, result, bucket);

                  if (size == 1) {
                    rightSibling = bucket.getRightSibling();
                  } else {
                    rightSibling = -1;
                  }
                } else {
                  rightSibling = -1;
                }
              } else {
                rightSibling = bucket.getRightSibling();
              }
            }
          }

          return result.stream();
        } else {

          try (final var nullCacheEntry =
              loadPageForRead(atomicOperation, nullBucketFileId, 0)) {
            final var nullBucket =
                new CellBTreeMultiValueV2NullBucket(nullCacheEntry);
            final var size = nullBucket.getSize();
            final var values = nullBucket.getValues();
            if (values.size() < size) {
              final var mId = nullBucket.getMid();

              try (final var stream =
                  multiContainer.iterateEntriesBetween(
                      new MultiValueEntry(mId, 0, 0),
                      true,
                      new MultiValueEntry(mId, Integer.MAX_VALUE, Long.MAX_VALUE),
                      true,
                      true)) {
                values.addAll(
                    stream
                        .map(
                            (pair) -> {
                              final var entry = pair.first;
                              return new RecordId(entry.clusterId, entry.clusterPosition);
                            })
                        .toList());
              }
            }
            return values.stream();
          }
        }
      } finally {
        releaseSharedLock();
      }
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new CellBTreeMultiValueException(
              "Error during retrieving  of sbtree with name " + getName(), this),
          e, storage.getName());
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  private void fetchValues(
      int itemIndex, List<RID> result, CellBTreeMultiValueV2Bucket<K> bucket) {
    final var entry =
        bucket.getLeafEntry(itemIndex, keySerializer, serializerFactory);
    result.addAll(entry.values);
    if (entry.values.size() < entry.entriesCount) {
      try (final var stream =
          multiContainer.iterateEntriesBetween(
              new MultiValueEntry(entry.mId, 0, 0),
              true,
              new MultiValueEntry(entry.mId, Integer.MAX_VALUE, Long.MAX_VALUE),
              true,
              true)) {

        result.addAll(
            stream
                .map(
                    (pair) -> {
                      final var multiValueEntry = pair.first;
                      return new RecordId(
                          multiValueEntry.clusterId, multiValueEntry.clusterPosition);
                    })
                .toList());
      }
    }
  }

  private void fetchMapEntries(
      @SuppressWarnings("SameParameterValue") final int itemIndex,
      final K key,
      final List<RawPair<K, RID>> result,
      final CellBTreeMultiValueV2Bucket<K> bucket) {
    final var entry =
        bucket.getLeafEntry(itemIndex, keySerializer, serializerFactory);
    fetchMapEntriesFromLeafEntry(key, result, entry);
  }

  private void fetchMapEntriesFromLeafEntry(
      K key, List<RawPair<K, RID>> result, CellBTreeMultiValueV2Bucket.LeafEntry entry) {
    for (final var rid : entry.values) {
      result.add(new RawPair<>(key, rid));
    }

    if (entry.values.size() < entry.entriesCount) {
      try (final var stream =
          multiContainer.iterateEntriesBetween(
              new MultiValueEntry(entry.mId, 0, 0),
              true,
              new MultiValueEntry(entry.mId, Integer.MAX_VALUE, Long.MAX_VALUE),
              true,
              true)) {

        result.addAll(
            stream
                .map(
                    (pair) -> {
                      final var multiValueEntry = pair.first;
                      return new RawPair<K, RID>(
                          key,
                          new RecordId(
                              multiValueEntry.clusterId, multiValueEntry.clusterPosition));
                    })
                .toList());
      }
    }
  }

  public void put(final AtomicOperation atomicOperation, final K pk, final RID value) {
    executeInsideComponentOperation(
        atomicOperation,
        operation -> {
          acquireExclusiveLock();
          try {
            var key = pk;

            if (key != null) {

              key = keySerializer.preprocess(serializerFactory, key, (Object[]) keyTypes);
              final var serializedKey =
                  keySerializer.serializeNativeAsWhole(serializerFactory, key, (Object[]) keyTypes);

              if (keySize > MAX_KEY_SIZE) {
                throw new TooBigIndexKeyException(storage.getName(),
                    "Key size is more than allowed, operation was canceled. Current key size "
                        + keySize
                        + ", allowed  "
                        + MAX_KEY_SIZE, getName());
              }

              var bucketSearchResult =
                  findBucketForUpdate(key, atomicOperation);

              var keyBucketCacheEntry =
                  loadPageForWrite(
                      atomicOperation, fileId, bucketSearchResult.getLastPathItem(), true);
              var keyBucket =
                  new CellBTreeMultiValueV2Bucket<K>(keyBucketCacheEntry);

              final byte[] keyToInsert;
              keyToInsert = serializedKey;

              final boolean isNew;
              int insertionIndex;
              if (bucketSearchResult.itemIndex >= 0) {
                insertionIndex = bucketSearchResult.itemIndex;
                isNew = false;
              } else {
                insertionIndex = -bucketSearchResult.itemIndex - 1;
                isNew = true;
              }

              while (!addEntry(
                  keyBucket, insertionIndex, isNew, keyToInsert, value, atomicOperation)) {
                bucketSearchResult =
                    splitBucket(
                        keyBucket,
                        keyBucketCacheEntry,
                        bucketSearchResult.path,
                        bucketSearchResult.insertionIndexes,
                        insertionIndex,
                        key,
                        atomicOperation);

                insertionIndex = bucketSearchResult.itemIndex;

                final var pageIndex = bucketSearchResult.getLastPathItem();

                if (pageIndex != keyBucketCacheEntry.getPageIndex()) {
                  keyBucketCacheEntry.close();

                  keyBucketCacheEntry = loadPageForWrite(atomicOperation, fileId, pageIndex, true);
                }

                keyBucket = new CellBTreeMultiValueV2Bucket<>(keyBucketCacheEntry);
              }

              keyBucketCacheEntry.close();

            } else {

              try (final var nullCacheEntry =
                  loadPageForWrite(atomicOperation, nullBucketFileId, 0, true)) {
                final var nullBucket =
                    new CellBTreeMultiValueV2NullBucket(nullCacheEntry);
                final var result = nullBucket.addValue(value);
                if (result >= 0) {
                  multiContainer.validatedPut(
                      atomicOperation,
                      new MultiValueEntry(result, value.getClusterId(), value.getClusterPosition()),
                      (byte) 1,
                      new IndexEngineValidatorNullIncrement(nullBucket));
                }
              }
            }
            updateSize(1, atomicOperation);
          } finally {
            releaseExclusiveLock();
          }
        });
  }

  private boolean addEntry(
      final CellBTreeMultiValueV2Bucket<K> bucketMultiValue,
      final int index,
      final boolean isNew,
      final byte[] key,
      final RID value,
      final AtomicOperation atomicOperation)
      throws IOException {

    if (isNew) {
      final var mId = incrementMId(atomicOperation);
      return bucketMultiValue.createMainLeafEntry(index, key, value, mId);
    }

    final var result = bucketMultiValue.appendNewLeafEntry(index, value);
    if (result >= 0) {
      multiContainer.validatedPut(
          atomicOperation,
          new MultiValueEntry(result, value.getClusterId(), value.getClusterPosition()),
          (byte) 1,
          new IndexEngineValidatorIncrement<>(bucketMultiValue, index));
      return true;
    }

    return result == -1;
  }

  private long incrementMId(final AtomicOperation atomicOperation) throws IOException {
    final var idCounter = mIdCounter.getValue();

    if ((idCounter & (M_ID_BATCH_SIZE - 1)) == 0) {
      try (final var cacheEntryPoint =
          loadPageForWrite(atomicOperation, fileId, ENTRY_POINT_INDEX, true)) {
        final var entryPoint =
            new CellBTreeMultiValueV2EntryPoint<K>(cacheEntryPoint);
        entryPoint.setEntryId(idCounter + M_ID_BATCH_SIZE);
      }
    }

    mIdCounter.setValue(idCounter + 1);

    return idCounter + 1;
  }

  public void close() {
    acquireExclusiveLock();
    try {
      readCache.closeFile(fileId, true, writeCache);
      readCache.closeFile(nullBucketFileId, true, writeCache);
      multiContainer.close();
    } finally {
      releaseExclusiveLock();
    }
  }

  public void delete(final AtomicOperation atomicOperation) {
    executeInsideComponentOperation(
        atomicOperation,
        operation -> {
          acquireExclusiveLock();
          try {
            deleteFile(atomicOperation, fileId);
            deleteFile(atomicOperation, nullBucketFileId);

            multiContainer.delete(atomicOperation);
          } finally {
            releaseExclusiveLock();
          }
        });
  }

  public void load(
      final String name,
      final int keySize,
      final PropertyTypeInternal[] keyTypes,
      final BinarySerializer<K> keySerializer) {
    acquireExclusiveLock();
    try {
      final var atomicOperation = atomicOperationsManager.getCurrentOperation();

      fileId = openFile(atomicOperation, getFullName());
      nullBucketFileId = openFile(atomicOperation, name + nullFileExtension);

      this.keySize = keySize;
      this.keyTypes = keyTypes;
      this.keySerializer = keySerializer;

      multiContainer = new SBTreeV2<>(getName(), containerExtension, null, storage);
      multiContainer.load(
          getName(),
          MultiValueEntrySerializer.INSTANCE,
          ByteSerializer.INSTANCE,
          null,
          1,
          false
      );

      try (final var entryPointCacheEntry =
          loadPageForRead(atomicOperation, fileId, ENTRY_POINT_INDEX)) {
        final var entryPoint =
            new CellBTreeMultiValueV2EntryPoint<K>(entryPointCacheEntry);
        mIdCounter.setValue(entryPoint.getEntryId());
      }

    } catch (final IOException e) {
      throw BaseException.wrapException(
          new CellBTreeMultiValueException("Exception during loading of sbtree " + name, this), e,
          storage.getName());
    } finally {
      releaseExclusiveLock();
    }
  }

  public long size() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final var atomicOperation = atomicOperationsManager.getCurrentOperation();

        try (final var entryPointCacheEntry =
            loadPageForRead(atomicOperation, fileId, ENTRY_POINT_INDEX)) {
          final var entryPoint =
              new CellBTreeMultiValueV2EntryPoint<K>(entryPointCacheEntry);
          return entryPoint.getTreeSize();
        }
      } finally {
        releaseSharedLock();
      }
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new CellBTreeMultiValueException(
              "Error during retrieving of size of index " + getName(), this),
          e, storage.getName());
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  public boolean remove(final AtomicOperation atomicOperation, final K k, final RID value) {
    return calculateInsideComponentOperation(
        atomicOperation,
        operation -> {
          var key = k;
          boolean removed;
          acquireExclusiveLock();
          try {
            if (key != null) {
              key = keySerializer.preprocess(serializerFactory, key, (Object[]) keyTypes);

              final var serializeKeySize =
                  keySerializer.getObjectSize(serializerFactory, key);

              final var bucketSearchResult = findBucket(key, atomicOperation);
              if (bucketSearchResult.itemIndex < 0) {
                return false;
              }

              final var pageIndex = bucketSearchResult.pageIndex;
              final var itemIndex = bucketSearchResult.itemIndex;

              long leftSibling = -1;
              long rightSibling = -1;

              try (var cacheEntry =
                  loadPageForWrite(atomicOperation, fileId, pageIndex, true)) {
                final var bucket =
                    new CellBTreeMultiValueV2Bucket<K>(cacheEntry);

                removed = removeEntry(atomicOperation, itemIndex, serializeKeySize, value, bucket);
                if (!removed) {
                  if (itemIndex == 0) {
                    leftSibling = bucket.getLeftSibling();
                  }

                  if (itemIndex == bucket.size() - 1) {
                    rightSibling = bucket.getRightSibling();
                  }
                }
              }

              while (!removed && leftSibling >= 0) {

                try (var cacheEntry =
                    loadPageForWrite(atomicOperation, fileId, leftSibling, true)) {
                  final var bucket =
                      new CellBTreeMultiValueV2Bucket<K>(cacheEntry);
                  final var size = bucket.size();

                  if (size > 0) {
                    removed = removeEntry(atomicOperation, size - 1, keySize, value, bucket);

                    if (!removed) {
                      if (size <= 1) {
                        leftSibling = bucket.getLeftSibling();
                      } else {
                        leftSibling = -1;
                      }
                    }
                  } else {
                    leftSibling = bucket.getLeftSibling();
                  }
                }
              }

              while (!removed && rightSibling >= 0) {

                try (var cacheEntry =
                    loadPageForWrite(atomicOperation, fileId, rightSibling, true)) {
                  final var bucket =
                      new CellBTreeMultiValueV2Bucket<K>(cacheEntry);
                  final var size = bucket.size();

                  if (size > 0) {
                    removed = removeEntry(atomicOperation, 0, serializeKeySize, value, bucket);

                    if (!removed) {
                      if (size <= 1) {
                        rightSibling = bucket.getRightSibling();
                      } else {
                        rightSibling = -1;
                      }
                    }
                  } else {
                    rightSibling = bucket.getRightSibling();
                  }
                }
              }

            } else {

              try (final var nullBucketCacheEntry =
                  loadPageForWrite(atomicOperation, nullBucketFileId, 0, true)) {
                final var nullBucket =
                    new CellBTreeMultiValueV2NullBucket(nullBucketCacheEntry);
                final var result = nullBucket.removeValue(value);
                if (result == 0) {
                  removed =
                      multiContainer.remove(
                          atomicOperation,
                          new MultiValueEntry(
                              nullBucket.getMid(),
                              value.getClusterId(),
                              value.getClusterPosition()))
                          != null;
                  if (removed) {
                    nullBucket.decrementSize();
                  }
                } else {
                  removed = result == 1;
                }
              }
            }
            if (removed) {
              updateSize(-1, atomicOperation);
            }
          } finally {
            releaseExclusiveLock();
          }

          return removed;
        });
  }

  private boolean removeEntry(
      final AtomicOperation atomicOperation,
      final int itemIndex,
      final int keySize,
      final RID value,
      final CellBTreeMultiValueV2Bucket<K> bucket) {
    final var entriesCount = bucket.removeLeafEntry(itemIndex, value);
    if (entriesCount == 0) {
      bucket.removeMainLeafEntry(itemIndex, keySize);
    }

    var removed = entriesCount >= 0;
    if (!removed && bucket.hasExternalEntries(itemIndex)) {
      final var mId = bucket.getMid(itemIndex);
      removed =
          multiContainer.remove(
              atomicOperation,
              new MultiValueEntry(mId, value.getClusterId(), value.getClusterPosition()))
              != null;
      if (removed) {
        if (bucket.decrementEntriesCount(itemIndex)) {
          bucket.removeMainLeafEntry(itemIndex, keySize);
        }
      }
    }

    return removed;
  }

  public Stream<RawPair<K, RID>> iterateEntriesMinor(
      final K key, final boolean inclusive, final boolean ascSortOrder) {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        if (!ascSortOrder) {
          return StreamSupport.stream(iterateEntriesMinorDesc(key, inclusive), false);
        }

        return StreamSupport.stream(iterateEntriesMinorAsc(key, inclusive), false);
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  public Stream<RawPair<K, RID>> iterateEntriesMajor(
      final K key, final boolean inclusive, final boolean ascSortOrder) {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        if (ascSortOrder) {
          return StreamSupport.stream(iterateEntriesMajorAsc(key, inclusive), false);
        }

        return StreamSupport.stream(iterateEntriesMajorDesc(key, inclusive), false);
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  public K firstKey() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final var atomicOperation = atomicOperationsManager.getCurrentOperation();

        final var searchResult = firstItem(atomicOperation);
        if (searchResult == null) {
          return null;
        }

        try (final var cacheEntry =
            loadPageForRead(atomicOperation, fileId, searchResult.pageIndex)) {
          final var bucket =
              new CellBTreeMultiValueV2Bucket<K>(cacheEntry);
          return bucket.getKey(searchResult.itemIndex, keySerializer, serializerFactory);
        }
      } finally {
        releaseSharedLock();
      }
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new CellBTreeMultiValueException(
              "Error during finding first key in sbtree [" + getName() + "]", this),
          e, storage.getName());
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  public K lastKey() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final var atomicOperation = atomicOperationsManager.getCurrentOperation();

        final var searchResult = lastItem(atomicOperation);
        if (searchResult == null) {
          return null;
        }

        try (final var cacheEntry =
            loadPageForRead(atomicOperation, fileId, searchResult.pageIndex)) {
          final var bucket =
              new CellBTreeMultiValueV2Bucket<K>(cacheEntry);
          return bucket.getKey(searchResult.itemIndex, keySerializer, serializerFactory);
        }
      } finally {
        releaseSharedLock();
      }
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new CellBTreeMultiValueException(
              "Error during finding last key in sbtree [" + getName() + "]", this),
          e, storage.getName());
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  public Stream<K> keyStream() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final var atomicOperation = atomicOperationsManager.getCurrentOperation();
        final var searchResult = firstItem(atomicOperation);
        if (searchResult == null) {
          return StreamSupport.stream(Spliterators.emptySpliterator(), false);
        }

        return StreamSupport.stream(new CellBTreeFullKeyCursor(searchResult.pageIndex), false);
      } finally {
        releaseSharedLock();
      }
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new CellBTreeMultiValueException(
              "Error during finding first key in sbtree [" + getName() + "]", this),
          e, storage.getName());
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  public Stream<RawPair<K, RID>> iterateEntriesBetween(
      final K keyFrom,
      final boolean fromInclusive,
      final K keyTo,
      final boolean toInclusive,
      final boolean ascSortOrder) {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        if (ascSortOrder) {
          return StreamSupport.stream(
              iterateEntriesBetweenAscOrder(keyFrom, fromInclusive, keyTo, toInclusive), false);
        } else {
          return StreamSupport.stream(
              iterateEntriesBetweenDescOrder(keyFrom, fromInclusive, keyTo, toInclusive), false);
        }
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  /**
   * Acquires exclusive lock in the active atomic operation running on the current thread for this
   * SB-tree.
   */
  public void acquireAtomicExclusiveLock() {
    atomicOperationsManager.acquireExclusiveLockTillOperationComplete(this);
  }

  private void updateSize(final long diffSize, final AtomicOperation atomicOperation)
      throws IOException {
    try (final var entryPointCacheEntry =
        loadPageForWrite(atomicOperation, fileId, ENTRY_POINT_INDEX, true)) {
      final var entryPoint =
          new CellBTreeMultiValueV2EntryPoint<K>(entryPointCacheEntry);
      entryPoint.setTreeSize(entryPoint.getTreeSize() + diffSize);
    }
  }

  private Spliterator<RawPair<K, RID>> iterateEntriesMinorDesc(K key, final boolean inclusive) {
    key = keySerializer.preprocess(serializerFactory, key, (Object[]) keyTypes);
    key = enhanceCompositeKeyMinorDesc(key, inclusive);

    return new CellBTreeCursorBackward(null, key, false, inclusive);
  }

  private Spliterator<RawPair<K, RID>> iterateEntriesMinorAsc(K key, final boolean inclusive) {
    key = keySerializer.preprocess(serializerFactory, key, (Object[]) keyTypes);
    key = enhanceCompositeKeyMinorAsc(key, inclusive);

    return new CellBTreeCursorForward(null, key, false, inclusive);
  }

  private K enhanceCompositeKeyMinorDesc(K key, final boolean inclusive) {
    final PartialSearchMode partialSearchMode;
    if (inclusive) {
      partialSearchMode = PartialSearchMode.HIGHEST_BOUNDARY;
    } else {
      partialSearchMode = PartialSearchMode.LOWEST_BOUNDARY;
    }

    key = enhanceCompositeKey(key, partialSearchMode);
    return key;
  }

  private K enhanceCompositeKeyMinorAsc(K key, final boolean inclusive) {
    final PartialSearchMode partialSearchMode;
    if (inclusive) {
      partialSearchMode = PartialSearchMode.HIGHEST_BOUNDARY;
    } else {
      partialSearchMode = PartialSearchMode.LOWEST_BOUNDARY;
    }

    key = enhanceCompositeKey(key, partialSearchMode);
    return key;
  }

  private Spliterator<RawPair<K, RID>> iterateEntriesMajorAsc(K key, final boolean inclusive) {
    key = keySerializer.preprocess(serializerFactory, key, (Object[]) keyTypes);
    key = enhanceCompositeKeyMajorAsc(key, inclusive);

    return new CellBTreeCursorForward(key, null, inclusive, false);
  }

  private Spliterator<RawPair<K, RID>> iterateEntriesMajorDesc(K key, final boolean inclusive) {
    acquireSharedLock();
    try {
      key = keySerializer.preprocess(serializerFactory, key, (Object[]) keyTypes);
      key = enhanceCompositeKeyMajorDesc(key, inclusive);

      return new CellBTreeCursorBackward(key, null, inclusive, false);

    } finally {
      releaseSharedLock();
    }
  }

  private K enhanceCompositeKeyMajorAsc(K key, final boolean inclusive) {
    final PartialSearchMode partialSearchMode;
    if (inclusive) {
      partialSearchMode = PartialSearchMode.LOWEST_BOUNDARY;
    } else {
      partialSearchMode = PartialSearchMode.HIGHEST_BOUNDARY;
    }

    key = enhanceCompositeKey(key, partialSearchMode);
    return key;
  }

  private K enhanceCompositeKeyMajorDesc(K key, final boolean inclusive) {
    final PartialSearchMode partialSearchMode;
    if (inclusive) {
      partialSearchMode = PartialSearchMode.LOWEST_BOUNDARY;
    } else {
      partialSearchMode = PartialSearchMode.HIGHEST_BOUNDARY;
    }

    key = enhanceCompositeKey(key, partialSearchMode);
    return key;
  }

  private BucketSearchResult firstItem(final AtomicOperation atomicOperation) throws IOException {
    final var path = new LinkedList<PagePathItemUnit>();

    var bucketIndex = ROOT_INDEX;

    var cacheEntry = loadPageForRead(atomicOperation, fileId, bucketIndex);
    var itemIndex = 0;
    try {
      var bucket = new CellBTreeMultiValueV2Bucket<K>(cacheEntry);

      while (true) {
        if (!bucket.isLeaf()) {
          if (bucket.isEmpty() || itemIndex > bucket.size()) {
            if (!path.isEmpty()) {
              final var pagePathItemUnit = path.removeLast();

              bucketIndex = pagePathItemUnit.pageIndex;
              itemIndex = pagePathItemUnit.itemIndex + 1;
            } else {
              return null;
            }
          } else {
            path.add(new PagePathItemUnit(bucketIndex, itemIndex));

            if (itemIndex < bucket.size()) {
              bucketIndex = bucket.getLeft(itemIndex);
            } else {
              bucketIndex = bucket.getRight(itemIndex - 1);
            }

            itemIndex = 0;
          }
        } else {
          if (bucket.isEmpty()) {
            if (!path.isEmpty()) {
              final var pagePathItemUnit = path.removeLast();

              bucketIndex = pagePathItemUnit.pageIndex;
              itemIndex = pagePathItemUnit.itemIndex + 1;
            } else {
              return null;
            }
          } else {
            return new BucketSearchResult(0, bucketIndex);
          }
        }

        cacheEntry.close();

        cacheEntry = loadPageForRead(atomicOperation, fileId, bucketIndex);

        bucket = new CellBTreeMultiValueV2Bucket<>(cacheEntry);
      }
    } finally {
      cacheEntry.close();
    }
  }

  private BucketSearchResult lastItem(final AtomicOperation atomicOperation) throws IOException {
    final var path = new LinkedList<PagePathItemUnit>();

    var bucketIndex = ROOT_INDEX;

    var cacheEntry = loadPageForRead(atomicOperation, fileId, bucketIndex);

    var bucket = new CellBTreeMultiValueV2Bucket<K>(cacheEntry);

    var itemIndex = bucket.size() - 1;
    try {
      while (true) {
        if (!bucket.isLeaf()) {
          if (itemIndex < -1) {
            if (!path.isEmpty()) {
              final var pagePathItemUnit = path.removeLast();

              bucketIndex = pagePathItemUnit.pageIndex;
              itemIndex = pagePathItemUnit.itemIndex - 1;
            } else {
              return null;
            }
          } else {
            path.add(new PagePathItemUnit(bucketIndex, itemIndex));

            if (itemIndex > -1) {
              bucketIndex = bucket.getRight(itemIndex);
            } else {
              bucketIndex = bucket.getLeft(0);
            }

            itemIndex = CellBTreeMultiValueV2Bucket.MAX_PAGE_SIZE_BYTES + 1;
          }
        } else {
          if (bucket.isEmpty()) {
            if (!path.isEmpty()) {
              final var pagePathItemUnit = path.removeLast();

              bucketIndex = pagePathItemUnit.pageIndex;
              itemIndex = pagePathItemUnit.itemIndex - 1;
            } else {
              return null;
            }
          } else {
            return new BucketSearchResult(bucket.size() - 1, bucketIndex);
          }
        }

        cacheEntry.close();

        cacheEntry = loadPageForRead(atomicOperation, fileId, bucketIndex);

        bucket = new CellBTreeMultiValueV2Bucket<>(cacheEntry);
        if (itemIndex == CellBTreeMultiValueV2Bucket.MAX_PAGE_SIZE_BYTES + 1) {
          itemIndex = bucket.size() - 1;
        }
      }
    } finally {
      cacheEntry.close();
    }
  }

  private Spliterator<RawPair<K, RID>> iterateEntriesBetweenAscOrder(
      K keyFrom, final boolean fromInclusive, K keyTo, final boolean toInclusive) {
    keyFrom = keySerializer.preprocess(serializerFactory, keyFrom, (Object[]) keyTypes);
    keyTo = keySerializer.preprocess(serializerFactory, keyTo, (Object[]) keyTypes);

    keyFrom = enhanceFromCompositeKeyBetweenAsc(keyFrom, fromInclusive);
    keyTo = enhanceToCompositeKeyBetweenAsc(keyTo, toInclusive);

    return new CellBTreeCursorForward(keyFrom, keyTo, fromInclusive, toInclusive);
  }

  private Spliterator<RawPair<K, RID>> iterateEntriesBetweenDescOrder(
      K keyFrom, final boolean fromInclusive, K keyTo, final boolean toInclusive) {
    keyFrom = keySerializer.preprocess(serializerFactory, keyFrom, (Object[]) keyTypes);
    keyTo = keySerializer.preprocess(serializerFactory, keyTo, (Object[]) keyTypes);

    keyFrom = enhanceFromCompositeKeyBetweenDesc(keyFrom, fromInclusive);
    keyTo = enhanceToCompositeKeyBetweenDesc(keyTo, toInclusive);

    return new CellBTreeCursorBackward(keyFrom, keyTo, fromInclusive, toInclusive);
  }

  private K enhanceToCompositeKeyBetweenAsc(K keyTo, final boolean toInclusive) {
    final PartialSearchMode partialSearchModeTo;
    if (toInclusive) {
      partialSearchModeTo = PartialSearchMode.HIGHEST_BOUNDARY;
    } else {
      partialSearchModeTo = PartialSearchMode.LOWEST_BOUNDARY;
    }

    keyTo = enhanceCompositeKey(keyTo, partialSearchModeTo);
    return keyTo;
  }

  private K enhanceFromCompositeKeyBetweenAsc(K keyFrom, final boolean fromInclusive) {
    final PartialSearchMode partialSearchModeFrom;
    if (fromInclusive) {
      partialSearchModeFrom = PartialSearchMode.LOWEST_BOUNDARY;
    } else {
      partialSearchModeFrom = PartialSearchMode.HIGHEST_BOUNDARY;
    }

    keyFrom = enhanceCompositeKey(keyFrom, partialSearchModeFrom);
    return keyFrom;
  }

  private K enhanceToCompositeKeyBetweenDesc(K keyTo, final boolean toInclusive) {
    final PartialSearchMode partialSearchModeTo;
    if (toInclusive) {
      partialSearchModeTo = PartialSearchMode.HIGHEST_BOUNDARY;
    } else {
      partialSearchModeTo = PartialSearchMode.LOWEST_BOUNDARY;
    }

    keyTo = enhanceCompositeKey(keyTo, partialSearchModeTo);
    return keyTo;
  }

  private K enhanceFromCompositeKeyBetweenDesc(K keyFrom, final boolean fromInclusive) {
    final PartialSearchMode partialSearchModeFrom;
    if (fromInclusive) {
      partialSearchModeFrom = PartialSearchMode.LOWEST_BOUNDARY;
    } else {
      partialSearchModeFrom = PartialSearchMode.HIGHEST_BOUNDARY;
    }

    keyFrom = enhanceCompositeKey(keyFrom, partialSearchModeFrom);
    return keyFrom;
  }

  private UpdateBucketSearchResult splitBucket(
      final CellBTreeMultiValueV2Bucket<K> bucketToSplit,
      final CacheEntry entryToSplit,
      final LongList path,
      final IntList insertionIndexes,
      final int keyIndex,
      final K keyToInsert,
      final AtomicOperation atomicOperation)
      throws IOException {
    final var splitLeaf = bucketToSplit.isLeaf();
    final var bucketSize = bucketToSplit.size();

    final var indexToSplit = bucketSize >>> 1;
    final var serializedSeparationKey =
        bucketToSplit.getRawKey(indexToSplit, keySerializer, serializerFactory);

    final List<CellBTreeMultiValueV2Bucket.Entry> rightEntries = new ArrayList<>(indexToSplit);

    final var startRightIndex = splitLeaf ? indexToSplit : indexToSplit + 1;
    if (splitLeaf) {
      for (var i = startRightIndex; i < bucketSize; i++) {
        rightEntries.add(bucketToSplit.getLeafEntry(i, keySerializer, serializerFactory));
      }
    } else {
      for (var i = startRightIndex; i < bucketSize; i++) {
        rightEntries.add(bucketToSplit.getNonLeafEntry(i, keySerializer, serializerFactory));
      }
    }

    if (entryToSplit.getPageIndex() != ROOT_INDEX) {
      return splitNonRootBucket(
          path,
          insertionIndexes,
          keyIndex,
          keyToInsert,
          entryToSplit.getPageIndex(),
          bucketToSplit,
          splitLeaf,
          indexToSplit,
          serializedSeparationKey,
          rightEntries,
          atomicOperation);
    } else {
      return splitRootBucket(
          keyIndex,
          keyToInsert,
          entryToSplit,
          bucketToSplit,
          splitLeaf,
          indexToSplit,
          serializedSeparationKey,
          rightEntries,
          atomicOperation);
    }
  }

  private K deserializeKey(final byte[] serializedKey) {
    return keySerializer.deserializeNativeObject(serializerFactory, serializedKey, 0);
  }

  private UpdateBucketSearchResult splitNonRootBucket(
      final LongList path,
      final IntList insertionIndexes,
      final int keyIndex,
      final K keyToInsert,
      final long pageIndex,
      final CellBTreeMultiValueV2Bucket<K> bucketToSplit,
      final boolean splitLeaf,
      final int indexToSplit,
      final byte[] serializedSeparationKey,
      final List<CellBTreeMultiValueV2Bucket.Entry> rightEntries,
      final AtomicOperation atomicOperation)
      throws IOException {

    final CacheEntry rightBucketEntry;
    try (final var entryPointCacheEntry =
        loadPageForWrite(atomicOperation, fileId, ENTRY_POINT_INDEX, true)) {
      final var entryPoint =
          new CellBTreeMultiValueV2EntryPoint<K>(entryPointCacheEntry);
      final var pageSize = entryPoint.getPagesSize();

      if (pageSize < getFilledUpTo(atomicOperation, fileId) - 1) {
        rightBucketEntry = loadPageForWrite(atomicOperation, fileId, pageSize + 1, false);
        entryPoint.setPagesSize(pageSize + 1);
      } else {
        assert pageSize == getFilledUpTo(atomicOperation, fileId) - 1;

        rightBucketEntry = addPage(atomicOperation, fileId);
        entryPoint.setPagesSize(rightBucketEntry.getPageIndex());
      }
    }

    K separationKey = null;

    try {
      final var newRightBucket =
          new CellBTreeMultiValueV2Bucket<K>(rightBucketEntry);
      newRightBucket.init(splitLeaf);

      newRightBucket.addAll(rightEntries);

      assert bucketToSplit.size() > 1;
      bucketToSplit.shrink(indexToSplit, keySerializer, serializerFactory);

      if (splitLeaf) {
        final var rightSiblingPageIndex = bucketToSplit.getRightSibling();

        newRightBucket.setRightSibling(rightSiblingPageIndex);
        newRightBucket.setLeftSibling(pageIndex);

        bucketToSplit.setRightSibling(rightBucketEntry.getPageIndex());

        if (rightSiblingPageIndex >= 0) {
          try (final var rightSiblingBucketEntry =
              loadPageForWrite(atomicOperation, fileId, rightSiblingPageIndex, true)) {
            final var rightSiblingBucket =
                new CellBTreeMultiValueV2Bucket<K>(rightSiblingBucketEntry);
            rightSiblingBucket.setLeftSibling(rightBucketEntry.getPageIndex());
          }
        }
      }

      var parentIndex = path.getLong(path.size() - 2);
      var parentCacheEntry = loadPageForWrite(atomicOperation, fileId, parentIndex, true);
      try {
        var parentBucket =
            new CellBTreeMultiValueV2Bucket<K>(parentCacheEntry);
        var insertionIndex = insertionIndexes.getInt(insertionIndexes.size() - 2);

        while (!parentBucket.addNonLeafEntry(
            insertionIndex,
            serializedSeparationKey,
            (int) pageIndex,
            rightBucketEntry.getPageIndex(),
            true)) {
          if (separationKey == null) {
            separationKey = deserializeKey(serializedSeparationKey);
          }

          final var bucketSearchResult =
              splitBucket(
                  parentBucket,
                  parentCacheEntry,
                  path.subList(0, path.size() - 1),
                  insertionIndexes.subList(0, insertionIndexes.size() - 1),
                  insertionIndex,
                  separationKey,
                  atomicOperation);

          parentIndex = bucketSearchResult.getLastPathItem();
          insertionIndex = bucketSearchResult.itemIndex;

          if (parentIndex != parentCacheEntry.getPageIndex()) {
            parentCacheEntry.close();
            parentCacheEntry = loadPageForWrite(atomicOperation, fileId, parentIndex, true);
          }

          parentBucket = new CellBTreeMultiValueV2Bucket<>(parentCacheEntry);
        }

      } finally {
        parentCacheEntry.close();
      }

    } finally {
      rightBucketEntry.close();
    }

    final var resultPath = new LongArrayList(path.subList(0, path.size() - 1));
    final var resultInsertionIndexes =
        new IntArrayList(insertionIndexes.subList(0, insertionIndexes.size() - 1));

    if (keyIndex < indexToSplit) {
      return addToTheLeftNonRootBucket(keyIndex, pageIndex, resultPath, resultInsertionIndexes);
    } else if (keyIndex > indexToSplit) {
      return addToTheRightNonRootBucket(
          keyIndex,
          splitLeaf,
          indexToSplit,
          rightBucketEntry.getPageIndex(),
          resultPath,
          resultInsertionIndexes);
    } else if (splitLeaf
        && keyToInsert.equals(
        Optional.ofNullable(separationKey)
            .orElseGet(() -> deserializeKey(serializedSeparationKey)))) {
      return addToTheRightNonRootBucket(
          keyIndex,
          true,
          indexToSplit,
          rightBucketEntry.getPageIndex(),
          resultPath,
          resultInsertionIndexes);
    } else {
      return addToTheLeftNonRootBucket(keyIndex, pageIndex, resultPath, resultInsertionIndexes);
    }
  }

  private static UpdateBucketSearchResult addToTheRightNonRootBucket(
      final int keyIndex,
      final boolean splitLeaf,
      final int indexToSplit,
      final long rightPageIndex,
      final LongArrayList resultPath,
      final IntArrayList resultItemPointers) {
    final var parentIndex = resultItemPointers.size() - 1;
    resultItemPointers.set(parentIndex, resultItemPointers.getInt(parentIndex) + 1);
    resultPath.add(rightPageIndex);

    if (splitLeaf) {
      resultItemPointers.add(keyIndex - indexToSplit);
      return new UpdateBucketSearchResult(resultItemPointers, resultPath, keyIndex - indexToSplit);
    }

    final var insertionIndex = keyIndex - indexToSplit - 1;

    resultItemPointers.add(insertionIndex);
    return new UpdateBucketSearchResult(resultItemPointers, resultPath, insertionIndex);
  }

  private static UpdateBucketSearchResult addToTheLeftNonRootBucket(
      final int keyIndex,
      final long pageIndex,
      final LongArrayList resultPath,
      final IntArrayList resultItemPointers) {
    resultPath.add(pageIndex);
    resultItemPointers.add(keyIndex);

    return new UpdateBucketSearchResult(resultItemPointers, resultPath, keyIndex);
  }

  private UpdateBucketSearchResult splitRootBucket(
      final int keyIndex,
      final K keyToInsert,
      final CacheEntry bucketEntry,
      CellBTreeMultiValueV2Bucket<K> bucketToSplit,
      final boolean splitLeaf,
      final int indexToSplit,
      final byte[] serializedSeparationKey,
      final List<CellBTreeMultiValueV2Bucket.Entry> rightEntries,
      final AtomicOperation atomicOperation)
      throws IOException {
    final List<CellBTreeMultiValueV2Bucket.Entry> leftEntries = new ArrayList<>(indexToSplit);

    if (splitLeaf) {
      if (bucketToSplit.size() > 1) {
        for (var i = 0; i < indexToSplit; i++) {
          leftEntries.add(bucketToSplit.getLeafEntry(i, keySerializer, serializerFactory));
        }
      } else {
        throw new IllegalStateException(
            "Bucket should have at least two entries to be able to split");
      }
    } else {
      for (var i = 0; i < indexToSplit; i++) {
        leftEntries.add(bucketToSplit.getNonLeafEntry(i, keySerializer, serializerFactory));
      }
    }

    final CacheEntry leftBucketEntry;
    final CacheEntry rightBucketEntry;

    try (final var entryPointCacheEntry =
        loadPageForWrite(atomicOperation, fileId, ENTRY_POINT_INDEX, true)) {
      final var entryPoint =
          new CellBTreeMultiValueV2EntryPoint<K>(entryPointCacheEntry);
      var pageSize = entryPoint.getPagesSize();

      final var filledUpTo = (int) getFilledUpTo(atomicOperation, fileId);

      if (pageSize < filledUpTo - 1) {
        pageSize++;
        leftBucketEntry = loadPageForWrite(atomicOperation, fileId, pageSize, false);
      } else {
        assert pageSize == filledUpTo - 1;

        leftBucketEntry = addPage(atomicOperation, fileId);
        pageSize = leftBucketEntry.getPageIndex();
      }

      if (pageSize < filledUpTo) {
        pageSize++;
        rightBucketEntry = loadPageForWrite(atomicOperation, fileId, pageSize, false);
      } else {
        assert pageSize == filledUpTo;

        rightBucketEntry = addPage(atomicOperation, fileId);
        pageSize = rightBucketEntry.getPageIndex();
      }

      entryPoint.setPagesSize(pageSize);
    }

    try {
      final var newLeftBucket =
          new CellBTreeMultiValueV2Bucket<K>(leftBucketEntry);
      newLeftBucket.init(splitLeaf);

      newLeftBucket.addAll(leftEntries);

      if (splitLeaf) {
        newLeftBucket.setRightSibling(rightBucketEntry.getPageIndex());
      }

    } finally {
      leftBucketEntry.close();
    }

    try {
      final var newRightBucket =
          new CellBTreeMultiValueV2Bucket<K>(rightBucketEntry);
      newRightBucket.init(splitLeaf);

      newRightBucket.addAll(rightEntries);

      if (splitLeaf) {
        newRightBucket.setLeftSibling(leftBucketEntry.getPageIndex());
      }
    } finally {
      rightBucketEntry.close();
    }

    bucketToSplit = new CellBTreeMultiValueV2Bucket<>(bucketEntry);
    bucketToSplit.shrink(0, keySerializer, serializerFactory);
    if (splitLeaf) {
      bucketToSplit.switchBucketType();
    }

    bucketToSplit.addNonLeafEntry(
        0,
        serializedSeparationKey,
        leftBucketEntry.getPageIndex(),
        rightBucketEntry.getPageIndex(),
        true);

    final var resultPath = new LongArrayList(8);
    resultPath.add(ROOT_INDEX);

    final var itemPointers = new IntArrayList(8);

    if (keyIndex < indexToSplit) {
      return addToTheLeftRootBucket(keyIndex, leftBucketEntry, resultPath, itemPointers);
    } else if (keyIndex > indexToSplit) {
      return addToTheRightRootBucket(
          keyIndex, splitLeaf, indexToSplit, rightBucketEntry, resultPath, itemPointers);
    } else if (splitLeaf && keyToInsert.equals(deserializeKey(serializedSeparationKey))) {
      return addToTheRightRootBucket(
          keyIndex, true, indexToSplit, rightBucketEntry, resultPath, itemPointers);
    } else {
      return addToTheLeftRootBucket(keyIndex, leftBucketEntry, resultPath, itemPointers);
    }
  }

  private static UpdateBucketSearchResult addToTheRightRootBucket(
      final int keyIndex,
      final boolean splitLeaf,
      final int indexToSplit,
      final CacheEntry rightBucketEntry,
      final LongArrayList resultPath,
      final IntArrayList itemPointers) {
    resultPath.add(rightBucketEntry.getPageIndex());
    itemPointers.add(0);

    if (splitLeaf) {
      itemPointers.add(keyIndex - indexToSplit);
      return new UpdateBucketSearchResult(itemPointers, resultPath, keyIndex - indexToSplit);
    }

    final var itemPointer = keyIndex - indexToSplit - 1;
    itemPointers.add(itemPointer);

    return new UpdateBucketSearchResult(itemPointers, resultPath, itemPointer);
  }

  private static UpdateBucketSearchResult addToTheLeftRootBucket(
      final int keyIndex,
      final CacheEntry leftBucketEntry,
      final LongArrayList resultPath,
      final IntArrayList itemPointers) {
    itemPointers.add(-1);
    itemPointers.add(keyIndex);

    resultPath.add(leftBucketEntry.getPageIndex());
    return new UpdateBucketSearchResult(itemPointers, resultPath, keyIndex);
  }

  private BucketSearchResult findBucket(final K key, final AtomicOperation atomicOperation)
      throws IOException {
    var pageIndex = ROOT_INDEX;

    var depth = 0;
    while (true) {
      depth++;
      if (depth > MAX_PATH_LENGTH) {
        throw new CellBTreeMultiValueException(
            "We reached max level of depth of SBTree but still found nothing, seems like tree is in"
                + " corrupted state. You should rebuild index related to given query.",
            this);
      }

      try (final var bucketEntry = loadPageForRead(atomicOperation, fileId, pageIndex)) {
        final var keyBucket =
            new CellBTreeMultiValueV2Bucket<K>(bucketEntry);
        final var index = keyBucket.find(key, keySerializer, serializerFactory);

        if (keyBucket.isLeaf()) {
          return new BucketSearchResult(index, pageIndex);
        }

        if (index >= 0) {
          pageIndex = keyBucket.getRight(index);
        } else {
          final var insertionIndex = -index - 1;
          if (insertionIndex >= keyBucket.size()) {
            pageIndex = keyBucket.getRight(insertionIndex - 1);
          } else {
            pageIndex = keyBucket.getLeft(insertionIndex);
          }
        }
      }
    }
  }

  private UpdateBucketSearchResult findBucketForUpdate(
      final K key, final AtomicOperation atomicOperation) throws IOException {
    var pageIndex = ROOT_INDEX;

    final var path = new LongArrayList(8);
    final var insertionIndexes = new IntArrayList(8);

    while (true) {
      if (path.size() > MAX_PATH_LENGTH) {
        throw new CellBTreeMultiValueException(
            "We reached max level of depth of SBTree but still found nothing, seems like tree is in"
                + " corrupted state. You should rebuild index related to given query.",
            this);
      }

      path.add(pageIndex);

      try (final var bucketEntry = loadPageForRead(atomicOperation, fileId, pageIndex)) {

        final var keyBucket =
            new CellBTreeMultiValueV2Bucket<K>(bucketEntry);
        final var index = keyBucket.find(key, keySerializer, serializerFactory);

        if (keyBucket.isLeaf()) {
          insertionIndexes.add(index);
          return new UpdateBucketSearchResult(insertionIndexes, path, index);
        }

        if (index >= 0) {
          pageIndex = keyBucket.getRight(index);
          insertionIndexes.add(index + 1);
        } else {
          final var insertionIndex = -index - 1;

          if (insertionIndex >= keyBucket.size()) {
            pageIndex = keyBucket.getRight(insertionIndex - 1);
          } else {
            pageIndex = keyBucket.getLeft(insertionIndex);
          }

          insertionIndexes.add(insertionIndex);
        }
      }
    }
  }

  private K enhanceCompositeKey(final K key, final PartialSearchMode partialSearchMode) {
    if (!(key instanceof CompositeKey compositeKey)) {
      return key;
    }

    if (!(keySize == 1
        || compositeKey.getKeys().size() == keySize
        || partialSearchMode.equals(PartialSearchMode.NONE))) {
      final var fullKey = new CompositeKey(compositeKey);
      final var itemsToAdd = keySize - fullKey.getKeys().size();

      final Comparable<?> keyItem;
      if (partialSearchMode.equals(PartialSearchMode.HIGHEST_BOUNDARY)) {
        keyItem = ALWAYS_GREATER_KEY;
      } else {
        keyItem = ALWAYS_LESS_KEY;
      }

      for (var i = 0; i < itemsToAdd; i++) {
        fullKey.addKey(keyItem);
      }

      //noinspection unchecked
      return (K) fullKey;
    }

    return key;
  }

  /**
   * Indicates search behavior in case of {@link CompositeKey} keys that have less amount of
   * internal keys are used, whether lowest or highest partially matched key should be used.
   */
  private enum PartialSearchMode {
    /**
     * Any partially matched key will be used as search result.
     */
    NONE,
    /**
     * The biggest partially matched key will be used as search result.
     */
    HIGHEST_BOUNDARY,

    /**
     * The smallest partially matched key will be used as search result.
     */
    LOWEST_BOUNDARY
  }

  private static final class BucketSearchResult {

    private final int itemIndex;
    private final long pageIndex;

    private BucketSearchResult(final int itemIndex, final long pageIndex) {
      this.itemIndex = itemIndex;
      this.pageIndex = pageIndex;
    }
  }

  private static final class UpdateBucketSearchResult {

    private final IntList insertionIndexes;
    private final LongArrayList path;
    private final int itemIndex;

    private UpdateBucketSearchResult(
        final IntList insertionIndexes, final LongArrayList path, final int itemIndex) {
      this.insertionIndexes = insertionIndexes;
      this.path = path;
      this.itemIndex = itemIndex;
    }

    private long getLastPathItem() {
      return path.getLong(path.size() - 1);
    }
  }

  private static final class PagePathItemUnit {

    private final long pageIndex;
    private final int itemIndex;

    private PagePathItemUnit(final long pageIndex, final int itemIndex) {
      this.pageIndex = pageIndex;
      this.itemIndex = itemIndex;
    }
  }

  public final class CellBTreeFullKeyCursor implements Spliterator<K> {

    private long pageIndex;
    private int itemIndex;

    private List<K> keysCache = new ArrayList<>();
    private Iterator<K> keysIterator = new EmptyIterator<>();

    private CellBTreeFullKeyCursor(final long startPageIndex) {
      pageIndex = startPageIndex;
      itemIndex = 0;
    }

    @Override
    public boolean tryAdvance(Consumer<? super K> action) {
      if (keysIterator == null) {
        return false;
      }

      if (keysIterator.hasNext()) {
        action.accept(keysIterator.next());
        return true;
      }

      keysCache.clear();

      final var prefetchSize = GlobalConfiguration.INDEX_CURSOR_PREFETCH_SIZE.getValueAsInteger();

      atomicOperationsManager.acquireReadLock(CellBTreeMultiValueV2.this);
      try {
        acquireSharedLock();
        try {
          final var atomicOperation = atomicOperationsManager.getCurrentOperation();

          while (keysCache.size() < prefetchSize) {
            if (pageIndex == -1) {
              break;
            }

            try (final var entryPointCacheEntry =
                loadPageForRead(atomicOperation, fileId, ENTRY_POINT_INDEX)) {
              final var entryPoint =
                  new CellBTreeMultiValueV2EntryPoint<K>(entryPointCacheEntry);
              if (pageIndex >= entryPoint.getPagesSize() + 1) {
                pageIndex = -1;
                break;
              }
            }

            try (final var cacheEntry =
                loadPageForRead(atomicOperation, fileId, pageIndex)) {
              final var bucket =
                  new CellBTreeMultiValueV2Bucket<K>(cacheEntry);

              final var bucketSize = bucket.size();
              if (itemIndex >= bucketSize) {
                pageIndex = bucket.getRightSibling();
                itemIndex = 0;
                continue;
              }

              while (itemIndex < bucketSize && keysCache.size() < prefetchSize) {
                keysCache.add(
                    deserializeKey(bucket.getRawKey(itemIndex, keySerializer, serializerFactory)));
                itemIndex++;
              }
            }
          }
        } finally {
          releaseSharedLock();
        }
      } catch (final IOException e) {
        throw BaseException.wrapException(
            new CellBTreeMultiValueException(
                "Error during entity iteration", CellBTreeMultiValueV2.this),
            e, storage.getName());
      } finally {
        atomicOperationsManager.releaseReadLock(CellBTreeMultiValueV2.this);
      }

      if (keysCache.isEmpty()) {
        keysCache = null;
        return false;
      }

      keysIterator = keysCache.iterator();
      action.accept(keysIterator.next());
      return true;
    }

    @Override
    public Spliterator<K> trySplit() {
      return null;
    }

    @Override
    public long estimateSize() {
      return Long.MAX_VALUE;
    }

    @Override
    public int characteristics() {
      return NONNULL | SORTED | ORDERED;
    }

    @Override
    public Comparator<? super K> getComparator() {
      return comparator;
    }
  }

  private final class CellBTreeCursorForward implements Spliterator<RawPair<K, RID>> {

    private K fromKey;
    private final K toKey;
    private boolean fromKeyInclusive;
    private final boolean toKeyInclusive;

    private final List<RawPair<K, RID>> dataCache = new ArrayList<>();

    @SuppressWarnings("unchecked")
    private Iterator<RawPair<K, RID>> dataCacheIterator = EmptyMapEntryIterator.INSTANCE;

    private CellBTreeCursorForward(
        final K fromKey,
        final K toKey,
        final boolean fromKeyInclusive,
        final boolean toKeyInclusive) {
      this.fromKey = fromKey;
      this.toKey = toKey;
      this.fromKeyInclusive = fromKeyInclusive;
      this.toKeyInclusive = toKeyInclusive;

      if (fromKey == null) {
        this.fromKeyInclusive = true;
      }
    }

    @Override
    public boolean tryAdvance(Consumer<? super RawPair<K, RID>> action) {
      if (dataCacheIterator == null) {
        return false;
      }

      if (dataCacheIterator.hasNext()) {
        final var entry = dataCacheIterator.next();

        fromKey = entry.first;
        fromKeyInclusive = false;

        action.accept(entry);
        return true;
      }

      dataCache.clear();

      final var prefetchSize = GlobalConfiguration.INDEX_CURSOR_PREFETCH_SIZE.getValueAsInteger();

      atomicOperationsManager.acquireReadLock(CellBTreeMultiValueV2.this);
      try {
        acquireSharedLock();
        try {
          final var atomicOperation = atomicOperationsManager.getCurrentOperation();

          final BucketSearchResult bucketSearchResult;

          if (fromKey != null) {
            bucketSearchResult = findBucket(fromKey, atomicOperation);
          } else {
            bucketSearchResult = firstItem(atomicOperation);
          }

          if (bucketSearchResult == null) {
            dataCacheIterator = null;
            return false;
          }

          var pageIndex = bucketSearchResult.pageIndex;
          int itemIndex;

          if (bucketSearchResult.itemIndex >= 0) {
            itemIndex =
                fromKeyInclusive ? bucketSearchResult.itemIndex : bucketSearchResult.itemIndex + 1;
          } else {
            itemIndex = -bucketSearchResult.itemIndex - 1;
          }

          K lastKey = null;

          var firstTry = true;
          mainCycle:
          while (true) {
            if (pageIndex == -1) {
              break;
            }

            try (final var cacheEntry =
                loadPageForRead(atomicOperation, fileId, pageIndex)) {
              final var bucket =
                  new CellBTreeMultiValueV2Bucket<K>(cacheEntry);
              if (firstTry
                  && fromKey != null
                  && fromKeyInclusive
                  && bucketSearchResult.itemIndex == 0) {
                var leftSibling = (int) bucket.getLeftSibling();
                while (leftSibling > 0) {
                  try (final var siblingCacheEntry =
                      loadPageForRead(atomicOperation, fileId, leftSibling)) {
                    final var siblingBucket =
                        new CellBTreeMultiValueV2Bucket<K>(siblingCacheEntry);

                    final var bucketSize = siblingBucket.size();
                    if (bucketSize == 0) {
                      leftSibling = (int) siblingBucket.getLeftSibling();
                    } else if (bucketSize == 1) {
                      final var key = siblingBucket.getKey(0, keySerializer, serializerFactory);

                      if (key.equals(fromKey)) {
                        lastKey = key;

                        fetchMapEntries(0, key, dataCache, siblingBucket);

                        leftSibling = (int) siblingBucket.getLeftSibling();
                      } else {
                        leftSibling = -1;
                      }
                    } else {
                      final var key = siblingBucket.getKey(bucketSize - 1, keySerializer,
                          serializerFactory);
                      if (key.equals(fromKey)) {
                        lastKey = key;

                        fetchMapEntries(0, key, dataCache, siblingBucket);
                      }
                      leftSibling = -1;
                    }
                  }
                }
              }

              firstTry = false;

              while (true) {
                if (itemIndex >= bucket.size()) {
                  pageIndex = bucket.getRightSibling();
                  itemIndex = 0;
                  continue mainCycle;
                }

                final var leafEntry =
                    bucket.getLeafEntry(itemIndex, keySerializer, serializerFactory);
                itemIndex++;

                final var key = deserializeKey(leafEntry.key);
                if (dataCache.size() >= prefetchSize && (lastKey == null || !lastKey.equals(key))) {
                  break mainCycle;
                }

                if (fromKeyInclusive) {
                  if (fromKey != null && comparator.compare(key, fromKey) < 0) {
                    continue;
                  }
                } else {
                  if (fromKey != null && comparator.compare(key, fromKey) <= 0) {
                    continue;
                  }
                }

                if (toKeyInclusive) {
                  if (toKey != null && comparator.compare(key, toKey) > 0) {
                    break mainCycle;
                  }
                } else {
                  if (toKey != null && comparator.compare(key, toKey) >= 0) {
                    break mainCycle;
                  }
                }

                lastKey = key;
                fetchMapEntriesFromLeafEntry(key, dataCache, leafEntry);
              }
            }
          }
        } finally {
          releaseSharedLock();
        }
      } catch (final IOException e) {
        throw BaseException.wrapException(
            new CellBTreeMultiValueException(
                "Error during entity iteration", CellBTreeMultiValueV2.this),
            e, storage.getName());
      } finally {
        atomicOperationsManager.releaseReadLock(CellBTreeMultiValueV2.this);
      }

      if (dataCache.isEmpty()) {
        dataCacheIterator = null;
        return false;
      }

      dataCacheIterator = dataCache.iterator();

      final var entry = dataCacheIterator.next();

      fromKey = entry.first;
      fromKeyInclusive = false;

      action.accept(entry);
      return true;
    }

    @Override
    public Spliterator<RawPair<K, RID>> trySplit() {
      return null;
    }

    @Override
    public long estimateSize() {
      return Long.MAX_VALUE;
    }

    @Override
    public int characteristics() {
      return NONNULL | SORTED | ORDERED;
    }

    @Override
    public Comparator<? super RawPair<K, RID>> getComparator() {
      return (pairOne, pairTwo) -> comparator.compare(pairOne.first, pairTwo.first);
    }
  }

  private final class CellBTreeCursorBackward implements Spliterator<RawPair<K, RID>> {

    private final K fromKey;
    private K toKey;
    private final boolean fromKeyInclusive;
    private boolean toKeyInclusive;

    private final List<RawPair<K, RID>> dataCache = new ArrayList<>();
    private Iterator<RawPair<K, RID>> dataCacheIterator = Collections.emptyIterator();

    private CellBTreeCursorBackward(
        final K fromKey,
        final K toKey,
        final boolean fromKeyInclusive,
        final boolean toKeyInclusive) {
      this.fromKey = fromKey;
      this.toKey = toKey;
      this.fromKeyInclusive = fromKeyInclusive;
      this.toKeyInclusive = toKeyInclusive;

      if (toKey == null) {
        this.toKeyInclusive = true;
      }
    }

    @Override
    public boolean tryAdvance(Consumer<? super RawPair<K, RID>> action) {
      if (dataCacheIterator == null) {
        return false;
      }

      if (dataCacheIterator.hasNext()) {
        final var entry = dataCacheIterator.next();
        toKey = entry.first;

        toKeyInclusive = false;

        action.accept(entry);
        return true;
      }

      dataCache.clear();

      final var prefetchSize = GlobalConfiguration.INDEX_CURSOR_PREFETCH_SIZE.getValueAsInteger();
      atomicOperationsManager.acquireReadLock(CellBTreeMultiValueV2.this);
      try {
        acquireSharedLock();
        try {
          final var atomicOperation = atomicOperationsManager.getCurrentOperation();

          final BucketSearchResult bucketSearchResult;

          if (toKey != null) {
            bucketSearchResult = findBucket(toKey, atomicOperation);
          } else {
            bucketSearchResult = lastItem(atomicOperation);
          }

          if (bucketSearchResult == null) {
            dataCacheIterator = null;
            return false;
          }

          var pageIndex = bucketSearchResult.pageIndex;

          int itemIndex;
          if (bucketSearchResult.itemIndex >= 0) {
            itemIndex =
                toKeyInclusive ? bucketSearchResult.itemIndex : bucketSearchResult.itemIndex - 1;
          } else {
            itemIndex = -bucketSearchResult.itemIndex - 2;
          }

          var firstTry = true;
          K lastKey = null;
          mainCycle:
          while (true) {
            if (pageIndex == -1) {
              break;
            }

            try (final var cacheEntry =
                loadPageForRead(atomicOperation, fileId, pageIndex)) {
              final var bucket =
                  new CellBTreeMultiValueV2Bucket<K>(cacheEntry);
              if (firstTry
                  && toKey != null
                  && toKeyInclusive
                  && bucketSearchResult.itemIndex == 0) {
                var rightSibling = (int) bucket.getRightSibling();
                while (rightSibling > 0) {
                  try (final var siblingCacheEntry =
                      loadPageForRead(atomicOperation, fileId, rightSibling)) {
                    final var siblingBucket =
                        new CellBTreeMultiValueV2Bucket<K>(siblingCacheEntry);

                    final var bucketSize = siblingBucket.size();
                    if (bucketSize == 0) {
                      rightSibling = (int) siblingBucket.getRightSibling();
                    } else if (bucketSize == 1) {
                      final var key = siblingBucket.getKey(0, keySerializer, serializerFactory);

                      if (key.equals(fromKey)) {
                        lastKey = key;

                        fetchMapEntries(0, key, dataCache, siblingBucket);
                        rightSibling = (int) siblingBucket.getRightSibling();
                      } else {
                        rightSibling = -1;
                      }
                    } else {
                      final var key = siblingBucket.getKey(0, keySerializer, serializerFactory);
                      if (key.equals(fromKey)) {
                        lastKey = key;

                        fetchMapEntries(0, key, dataCache, siblingBucket);
                      } else {
                        rightSibling = -1;
                      }
                    }
                  }
                }
              }

              firstTry = false;

              while (true) {
                if (itemIndex >= bucket.size()) {
                  itemIndex = bucket.size() - 1;
                }

                if (itemIndex < 0) {
                  pageIndex = bucket.getLeftSibling();
                  itemIndex = Integer.MAX_VALUE;
                  continue mainCycle;
                }

                final var leafEntry =
                    bucket.getLeafEntry(itemIndex, keySerializer, serializerFactory);
                itemIndex--;

                final var key = deserializeKey(leafEntry.key);
                if (dataCache.size() >= prefetchSize && (lastKey == null || !lastKey.equals(key))) {
                  break mainCycle;
                }

                if (toKeyInclusive) {
                  if (toKey != null && comparator.compare(key, toKey) > 0) {
                    continue;
                  }
                } else {
                  if (toKey != null && comparator.compare(key, toKey) >= 0) {
                    continue;
                  }
                }

                if (fromKeyInclusive) {
                  if (fromKey != null && comparator.compare(key, fromKey) < 0) {
                    break mainCycle;
                  }
                } else {
                  if (fromKey != null && comparator.compare(key, fromKey) <= 0) {
                    break mainCycle;
                  }
                }

                lastKey = key;

                fetchMapEntriesFromLeafEntry(key, dataCache, leafEntry);
              }
            }
          }
        } finally {
          releaseSharedLock();
        }
      } catch (final IOException e) {
        throw BaseException.wrapException(
            new CellBTreeMultiValueException(
                "Error during entity iteration", CellBTreeMultiValueV2.this),
            e, storage.getName());
      } finally {
        atomicOperationsManager.releaseReadLock(CellBTreeMultiValueV2.this);
      }

      if (dataCache.isEmpty()) {
        dataCacheIterator = null;
        return false;
      }

      dataCacheIterator = dataCache.iterator();

      final var entry = dataCacheIterator.next();

      toKey = entry.first;
      toKeyInclusive = false;

      action.accept(entry);
      return true;
    }

    @Override
    public Spliterator<RawPair<K, RID>> trySplit() {
      return null;
    }

    @Override
    public long estimateSize() {
      return Long.MAX_VALUE;
    }

    @Override
    public int characteristics() {
      return NONNULL | SORTED | ORDERED;
    }

    @Override
    public Comparator<? super RawPair<K, RID>> getComparator() {
      return (pairOne, pairTwo) -> -comparator.compare(pairOne.first, pairTwo.first);
    }
  }
}
