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

package com.jetbrains.youtrack.db.internal.core.storage.index.sbtree.local.v2;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.TooBigIndexKeyException;
import com.jetbrains.youtrack.db.internal.common.comparator.DefaultComparator;
import com.jetbrains.youtrack.db.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.index.CompositeKey;
import com.jetbrains.youtrack.db.internal.core.index.IndexKeyUpdater;
import com.jetbrains.youtrack.db.internal.core.index.IndexUpdateAction;
import com.jetbrains.youtrack.db.internal.core.index.comparator.AlwaysGreaterKey;
import com.jetbrains.youtrack.db.internal.core.index.comparator.AlwaysLessKey;
import com.jetbrains.youtrack.db.internal.core.index.engine.IndexEngineValidator;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrack.db.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractPaginatedStorage;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.base.DurableComponent;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrack.db.internal.core.storage.index.sbtree.local.SBTree;
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
import java.util.concurrent.atomic.AtomicLong;
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
public class SBTreeV2<K, V> extends DurableComponent implements SBTree<K, V> {

  private static final int SPLITERATOR_CACHE_SIZE =
      GlobalConfiguration.INDEX_CURSOR_PREFETCH_SIZE.getValueAsInteger();
  private static final int MAX_KEY_SIZE =
      GlobalConfiguration.BTREE_MAX_KEY_SIZE.getValueAsInteger();
  private static final int MAX_EMBEDDED_VALUE_SIZE =
      GlobalConfiguration.BTREE_MAX_EMBEDDED_VALUE_SIZE.getValueAsInteger();
  private static final AlwaysLessKey ALWAYS_LESS_KEY = new AlwaysLessKey();
  private static final AlwaysGreaterKey ALWAYS_GREATER_KEY = new AlwaysGreaterKey();

  private static final int MAX_PATH_LENGTH =
      GlobalConfiguration.BTREE_MAX_DEPTH.getValueAsInteger();

  private static final long ROOT_INDEX = 0;
  private final Comparator<? super K> comparator = DefaultComparator.INSTANCE;
  private final String nullFileExtension;
  private long fileId;
  private long nullBucketFileId = -1;
  private int keySize;
  private BinarySerializer<K> keySerializer;
  private PropertyTypeInternal[] keyTypes;
  private BinarySerializer<V> valueSerializer;
  private final BinarySerializerFactory serializerFactory;
  private boolean nullPointerSupport;
  private final AtomicLong bonsayFileId = new AtomicLong(0);

  public SBTreeV2(
      final String name,
      final String dataFileExtension,
      final String nullFileExtension,
      final AbstractPaginatedStorage storage) {
    super(storage, name, dataFileExtension, name + dataFileExtension);
    acquireExclusiveLock();
    try {
      this.nullFileExtension = nullFileExtension;
      this.serializerFactory = storage.getComponentsFactory().binarySerializerFactory;
    } finally {
      releaseExclusiveLock();
    }
  }

  @Override
  public void create(
      final AtomicOperation atomicOperation,
      final BinarySerializer<K> keySerializer,
      final BinarySerializer<V> valueSerializer,
      final PropertyTypeInternal[] keyTypes,
      final int keySize,
      final boolean nullPointerSupport)
      throws IOException {
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

            this.valueSerializer = valueSerializer;
            this.nullPointerSupport = nullPointerSupport;

            fileId = addFile(atomicOperation, getFullName());

            if (nullPointerSupport) {
              nullBucketFileId = addFile(atomicOperation, getName() + nullFileExtension);
            }

            try (final var rootCacheEntry = addPage(atomicOperation, fileId)) {
              final var rootBucket = new SBTreeBucketV2<K, V>(rootCacheEntry);
              rootBucket.init(true);
              rootBucket.setTreeSize(0);
            }
          } finally {
            releaseExclusiveLock();
          }
        });
  }

  @Override
  public boolean isNullPointerSupport() {
    acquireSharedLock();
    try {
      return nullPointerSupport;
    } finally {
      releaseSharedLock();
    }
  }

  @Override
  public V get(K key) {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        checkNullSupport(key);

        final var atomicOperation = atomicOperationsManager.getCurrentOperation();
        if (key != null) {
          key = keySerializer.preprocess(serializerFactory, key, (Object[]) keyTypes);

          final var bucketSearchResult = findBucket(key, atomicOperation);
          if (bucketSearchResult.itemIndex < 0) {
            return null;
          }

          final var pageIndex = bucketSearchResult.getLastPathItem();

          try (final var keyBucketCacheEntry =
              loadPageForRead(atomicOperation, fileId, pageIndex)) {
            final var keyBucket = new SBTreeBucketV2<K, V>(keyBucketCacheEntry);

            final var treeEntry =
                keyBucket.getEntry(bucketSearchResult.itemIndex, keySerializer, valueSerializer,
                    serializerFactory);
            return treeEntry.value.getValue();
          }
        } else {
          if (getFilledUpTo(atomicOperation, nullBucketFileId) == 0) {
            return null;
          }

          try (final var nullBucketCacheEntry =
              loadPageForRead(atomicOperation, nullBucketFileId, 0)) {
            final var nullBucket =
                new SBTreeNullBucketV2<V>(nullBucketCacheEntry);
            final var treeValue = nullBucket.getValue(valueSerializer, serializerFactory);
            if (treeValue == null) {
              return null;
            }

            return treeValue.getValue();
          }
        }
      } finally {
        releaseSharedLock();
      }
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new SBTreeException("Error during retrieving  of sbtree with name " + getName(), this),
          e, storage.getName());
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public void put(final AtomicOperation atomicOperation, final K key, final V value) {
    put(atomicOperation, key, value, null);
  }

  @Override
  public boolean validatedPut(
      final AtomicOperation atomicOperation,
      final K key,
      final V value,
      final IndexEngineValidator<K, V> validator) {
    return put(atomicOperation, key, value, validator);
  }

  private boolean put(
      final AtomicOperation atomicOperation,
      final K key,
      final V value,
      final IndexEngineValidator<K, V> validator) {
    return update(
        atomicOperation, key, (x, bonsayFileId) -> IndexUpdateAction.changed(value), validator);
  }

  @Override
  @SuppressWarnings("unchecked")
  public boolean update(
      AtomicOperation atomicOperation,
      final K k,
      final IndexKeyUpdater<V> updater,
      final IndexEngineValidator<K, V> validator) {
    return calculateInsideComponentOperation(
        atomicOperation,
        operation -> {
          acquireExclusiveLock();
          try {
            var key = k;
            checkNullSupport(key);

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

              var bucketSearchResult = findBucket(key, atomicOperation);

              var keyBucketCacheEntry =
                  loadPageForWrite(
                      atomicOperation, fileId, bucketSearchResult.getLastPathItem(), true);
              var keyBucket = new SBTreeBucketV2<K, V>(keyBucketCacheEntry);
              final byte[] oldRawValue;
              if (bucketSearchResult.itemIndex > -1) {
                oldRawValue =
                    keyBucket.getRawValue(
                        bucketSearchResult.itemIndex, keySerializer, valueSerializer,
                        serializerFactory);
              } else {
                oldRawValue = null;
              }
              final V oldValue;
              if (oldRawValue == null) {
                oldValue = null;
              } else {
                oldValue = valueSerializer.deserializeNativeObject(serializerFactory, oldRawValue,
                    0);
              }

              final var updatedValue = updater.update(oldValue, bonsayFileId);
              if (updatedValue.isChange()) {
                var value = updatedValue.getValue();

                if (validator != null) {
                  var failure = true; // assuming validation throws by default
                  var ignored = false;

                  try {

                    final var result = validator.validate(key, oldValue, value);
                    if (result == IndexEngineValidator.IGNORE) {
                      ignored = true;
                      failure = false;
                      return false;
                    }

                    value = (V) result;
                    failure = false;
                  } finally {
                    if (failure || ignored) {
                      keyBucketCacheEntry.close();
                    }
                  }
                }

                final var valueSize = valueSerializer.getObjectSize(serializerFactory, value);
                final var serializeValue = new byte[valueSize];
                valueSerializer.serializeNativeObject(value, serializerFactory, serializeValue, 0);

                final var createLinkToTheValue = valueSize > MAX_EMBEDDED_VALUE_SIZE;
                assert !createLinkToTheValue;

                int insertionIndex;
                final int sizeDiff;
                if (bucketSearchResult.itemIndex >= 0) {
                  assert oldRawValue != null;

                  if (oldRawValue.length == serializeValue.length) {
                    keyBucket.updateValue(
                        bucketSearchResult.itemIndex, serializeValue, serializedKey.length);
                    keyBucketCacheEntry.close();
                    return true;
                  } else {
                    keyBucket.removeLeafEntry(
                        bucketSearchResult.itemIndex, serializedKey, oldRawValue);
                    insertionIndex = bucketSearchResult.itemIndex;
                    sizeDiff = 0;
                  }
                } else {
                  insertionIndex = -bucketSearchResult.itemIndex - 1;
                  sizeDiff = 1;
                }

                while (!keyBucket.addLeafEntry(insertionIndex, serializedKey, serializeValue)) {
                  keyBucketCacheEntry.close();

                  bucketSearchResult =
                      splitBucket(bucketSearchResult.path, insertionIndex, key, atomicOperation);

                  insertionIndex = bucketSearchResult.itemIndex;

                  keyBucketCacheEntry =
                      loadPageForWrite(
                          atomicOperation, fileId, bucketSearchResult.getLastPathItem(), true);

                  //noinspection ObjectAllocationInLoop
                  keyBucket = new SBTreeBucketV2<>(keyBucketCacheEntry);
                }

                keyBucketCacheEntry.close();

                if (sizeDiff != 0) {
                  updateSize(sizeDiff, atomicOperation);
                }
              } else if (updatedValue.isRemove()) {
                removeKey(atomicOperation, bucketSearchResult, serializedKey);
                keyBucketCacheEntry.close();
              } else if (updatedValue.isNothing()) {
                keyBucketCacheEntry.close();
              }
            } else {
              final CacheEntry cacheEntry;
              var isNew = false;

              if (getFilledUpTo(atomicOperation, nullBucketFileId) == 0) {
                cacheEntry = addPage(atomicOperation, nullBucketFileId);
                isNew = true;
              } else {
                cacheEntry = loadPageForWrite(atomicOperation, nullBucketFileId, 0, true);
              }

              var sizeDiff = 0;

              try {
                final var nullBucket = new SBTreeNullBucketV2<V>(cacheEntry);
                if (isNew) {
                  nullBucket.init();
                }
                final var oldRawValue = nullBucket.getRawValue(valueSerializer, serializerFactory);
                final var oldValue =
                    Optional.ofNullable(oldRawValue)
                        .map(rawValue -> valueSerializer.deserializeNativeObject(serializerFactory,
                            rawValue, 0))
                        .orElse(null);

                final var updatedValue = updater.update(oldValue, bonsayFileId);
                if (updatedValue.isChange()) {
                  final var value = updatedValue.getValue();
                  final var valueSize = valueSerializer.getObjectSize(serializerFactory, value);
                  if (validator != null) {
                    final var result = validator.validate(null, oldValue, value);
                    if (result == IndexEngineValidator.IGNORE) {
                      return false;
                    }
                  }

                  if (oldValue != null) {
                    sizeDiff = -1;
                  }

                  final var serializeValue = new byte[valueSize];
                  valueSerializer.serializeNativeObject(value, serializerFactory, serializeValue,
                      0);

                  nullBucket.setValue(serializeValue, valueSerializer);
                } else if (updatedValue.isRemove()) {
                  removeNullBucket(atomicOperation);
                } else //noinspection StatementWithEmptyBody
                  if (updatedValue.isNothing()) {
                    // Do Nothing
                  }
              } finally {
                cacheEntry.close();
              }

              sizeDiff++;
              updateSize(sizeDiff, atomicOperation);
            }
            return true;
          } finally {
            releaseExclusiveLock();
          }
        });
  }

  @Override
  public void close(final boolean flush) {
    acquireExclusiveLock();
    try {
      readCache.closeFile(fileId, flush, writeCache);

      if (nullPointerSupport) {
        readCache.closeFile(nullBucketFileId, flush, writeCache);
      }

    } finally {
      releaseExclusiveLock();
    }
  }

  @Override
  public void close() {
    close(true);
  }

  @Override
  public void delete(final AtomicOperation atomicOperation) throws IOException {
    executeInsideComponentOperation(
        atomicOperation,
        operation -> {
          acquireExclusiveLock();
          try {
            deleteFile(atomicOperation, fileId);

            if (nullPointerSupport) {
              deleteFile(atomicOperation, nullBucketFileId);
            }
          } finally {
            releaseExclusiveLock();
          }
        });
  }

  @Override
  public void load(
      final String name,
      final BinarySerializer<K> keySerializer,
      final BinarySerializer<V> valueSerializer,
      final PropertyTypeInternal[] keyTypes,
      final int keySize,
      final boolean nullPointerSupport) {
    acquireExclusiveLock();
    try {
      this.keySize = keySize;
      if (keyTypes != null) {
        this.keyTypes = Arrays.copyOf(keyTypes, keyTypes.length);
      } else {
        this.keyTypes = null;
      }

      this.nullPointerSupport = nullPointerSupport;

      final var atomicOperation = atomicOperationsManager.getCurrentOperation();

      fileId = openFile(atomicOperation, getFullName());
      if (nullPointerSupport) {
        nullBucketFileId = openFile(atomicOperation, name + nullFileExtension);
      }

      this.keySerializer = keySerializer;
      this.valueSerializer = valueSerializer;
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new SBTreeException("Exception during loading of sbtree " + name, this), e,
          storage.getName());
    } finally {
      releaseExclusiveLock();
    }
  }

  @Override
  public long size() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final var atomicOperation = atomicOperationsManager.getCurrentOperation();

        try (final var rootCacheEntry =
            loadPageForRead(atomicOperation, fileId, ROOT_INDEX)) {
          final var rootBucket = new SBTreeBucketV2<K, V>(rootCacheEntry);
          return rootBucket.getTreeSize();
        }
      } finally {
        releaseSharedLock();
      }
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new SBTreeException("Error during retrieving of size of index " + getName(), this), e,
          storage.getName());
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public V remove(AtomicOperation atomicOperation, final K k) {
    return calculateInsideComponentOperation(
        atomicOperation,
        operation -> {
          acquireExclusiveLock();
          try {
            final V removedValue;
            var key = k;
            if (key != null) {
              key = keySerializer.preprocess(serializerFactory, key, (Object[]) keyTypes);

              final var bucketSearchResult = findBucket(key, atomicOperation);
              if (bucketSearchResult.itemIndex < 0) {
                return null;
              }

              final var serializedKey = keySerializer.serializeNativeAsWhole(serializerFactory,
                  key);
              final var rawRemovedValue =
                  removeKey(atomicOperation, bucketSearchResult, serializedKey);
              removedValue = valueSerializer.deserializeNativeObject(serializerFactory,
                  rawRemovedValue, 0);
            } else {
              if (getFilledUpTo(atomicOperation, nullBucketFileId) == 0) {
                return null;
              }

              removedValue = removeNullBucket(atomicOperation);
            }
            return removedValue;
          } finally {
            releaseExclusiveLock();
          }
        });
  }

  private V removeNullBucket(final AtomicOperation atomicOperation) throws IOException {
    V removedValue;
    try (final var nullCacheEntry =
        loadPageForWrite(atomicOperation, nullBucketFileId, 0, true)) {
      final var nullBucket = new SBTreeNullBucketV2<V>(nullCacheEntry);
      final var treeValue = nullBucket.getValue(valueSerializer, serializerFactory);

      if (treeValue != null) {
        removedValue = treeValue.getValue();
        nullBucket.removeValue(valueSerializer);
      } else {
        removedValue = null;
      }
    }

    if (removedValue != null) {
      updateSize(-1, atomicOperation);
    }
    return removedValue;
  }

  private byte[] removeKey(
      final AtomicOperation atomicOperation,
      final BucketSearchResult bucketSearchResult,
      final byte[] rawKey)
      throws IOException {
    byte[] removedValue;
    try (final var keyBucketCacheEntry =
        loadPageForWrite(atomicOperation, fileId, bucketSearchResult.getLastPathItem(), true)) {
      final var keyBucket = new SBTreeBucketV2<K, V>(keyBucketCacheEntry);

      removedValue =
          keyBucket.getRawValue(bucketSearchResult.itemIndex, keySerializer, valueSerializer,
              serializerFactory);
      keyBucket.removeLeafEntry(bucketSearchResult.itemIndex, rawKey, removedValue);
      updateSize(-1, atomicOperation);
    }
    return removedValue;
  }

  @Override
  public Stream<RawPair<K, V>> iterateEntriesMinor(
      final K key, final boolean inclusive, final boolean ascSortOrder) {

    if (!ascSortOrder) {
      return StreamSupport.stream(iterateEntriesMinorDesc(key, inclusive), false);
    }

    return StreamSupport.stream(iterateEntriesMinorAsc(key, inclusive), false);
  }

  @Override
  public Stream<RawPair<K, V>> iterateEntriesMajor(
      final K key, final boolean inclusive, final boolean ascSortOrder) {
    if (ascSortOrder) {
      return StreamSupport.stream(iterateEntriesMajorAsc(key, inclusive), false);
    }

    return StreamSupport.stream(iterateEntriesMajorDesc(key, inclusive), false);
  }

  @Override
  public K firstKey() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final var atomicOperation = atomicOperationsManager.getCurrentOperation();

        final var searchResult = firstItem(atomicOperation);
        if (searchResult.isEmpty()) {
          return null;
        }

        final var result = searchResult.get();

        try (final var cacheEntry =
            loadPageForRead(atomicOperation, fileId, result.getLastPathItem())) {
          final var bucket = new SBTreeBucketV2<K, V>(cacheEntry);
          return bucket.getKey(result.itemIndex, keySerializer, serializerFactory);
        }
      } finally {
        releaseSharedLock();
      }
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new SBTreeException(
              "Error during finding first key in sbtree [" + getName() + "]", this),
          e, storage.getName());
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public K lastKey() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final var atomicOperation = atomicOperationsManager.getCurrentOperation();

        final var searchResult = lastItem(atomicOperation);
        if (searchResult.isEmpty()) {
          return null;
        }

        final var result = searchResult.get();

        try (final var cacheEntry =
            loadPageForRead(atomicOperation, fileId, result.getLastPathItem())) {
          final var bucket = new SBTreeBucketV2<K, V>(cacheEntry);
          return bucket.getKey(result.itemIndex, keySerializer, serializerFactory);
        }
      } finally {
        releaseSharedLock();
      }
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new SBTreeException("Error during finding last key in sbtree [" + getName() + "]", this),
          e, storage.getName());
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public Stream<K> keyStream() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        return StreamSupport.stream(new SpliteratorForward(null, null, false, false), false)
            .map((entry) -> entry.first);
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public Stream<RawPair<K, V>> iterateEntriesBetween(
      final K keyFrom,
      final boolean fromInclusive,
      final K keyTo,
      final boolean toInclusive,
      final boolean ascSortOrder) {

    if (ascSortOrder) {
      return StreamSupport.stream(
          iterateEntriesBetweenAscOrder(keyFrom, fromInclusive, keyTo, toInclusive), false);
    } else {
      return StreamSupport.stream(
          iterateEntriesBetweenDescOrder(keyFrom, fromInclusive, keyTo, toInclusive), false);
    }
  }

  @Override
  public void flush() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        writeCache.flush();
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
  @Override
  public void acquireAtomicExclusiveLock() {
    atomicOperationsManager.acquireExclusiveLockTillOperationComplete(this);
  }

  private void checkNullSupport(final K key) {
    if (key == null && !nullPointerSupport) {
      throw new SBTreeException("Null keys are not supported.", this);
    }
  }

  private void updateSize(final long diffSize, final AtomicOperation atomicOperation)
      throws IOException {
    try (final var rootCacheEntry =
        loadPageForWrite(atomicOperation, fileId, ROOT_INDEX, true)) {
      final var rootBucket = new SBTreeBucketV2<K, V>(rootCacheEntry);
      rootBucket.setTreeSize(rootBucket.getTreeSize() + diffSize);
    }
  }

  private Spliterator<RawPair<K, V>> iterateEntriesMinorDesc(K key, final boolean inclusive) {
    key = keySerializer.preprocess(serializerFactory, key, (Object[]) keyTypes);
    key = enhanceCompositeKeyMinorDesc(key, inclusive);

    return new SpliteratorBackward(null, key, false, inclusive);
  }

  private Spliterator<RawPair<K, V>> iterateEntriesMinorAsc(K key, final boolean inclusive) {
    key = keySerializer.preprocess(serializerFactory, key, (Object[]) keyTypes);
    key = enhanceCompositeKeyMinorAsc(key, inclusive);

    return new SpliteratorForward(null, key, false, inclusive);
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

  private Spliterator<RawPair<K, V>> iterateEntriesMajorAsc(K key, final boolean inclusive) {
    key = keySerializer.preprocess(serializerFactory, key, (Object[]) keyTypes);
    key = enhanceCompositeKeyMajorAsc(key, inclusive);

    return new SpliteratorForward(key, null, inclusive, false);
  }

  private Spliterator<RawPair<K, V>> iterateEntriesMajorDesc(K key, final boolean inclusive) {
    key = keySerializer.preprocess(serializerFactory, key, (Object[]) keyTypes);
    key = enhanceCompositeKeyMajorDesc(key, inclusive);

    return new SpliteratorBackward(key, null, inclusive, false);
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

  private Optional<BucketSearchResult> firstItem(final AtomicOperation atomicOperation)
      throws IOException {
    final var path = new LinkedList<PagePathItemUnit>();

    var bucketIndex = ROOT_INDEX;

    var cacheEntry = loadPageForRead(atomicOperation, fileId, bucketIndex);
    var itemIndex = 0;
    try {
      var bucket = new SBTreeBucketV2<K, V>(cacheEntry);

      while (true) {
        if (!bucket.isLeaf()) {
          if (bucket.isEmpty() || itemIndex > bucket.size()) {
            if (!path.isEmpty()) {
              final var pagePathItemUnit = path.removeLast();

              bucketIndex = pagePathItemUnit.pageIndex;
              itemIndex = pagePathItemUnit.itemIndex + 1;
            } else {
              return Optional.empty();
            }
          } else {
            //noinspection ObjectAllocationInLoop
            path.add(new PagePathItemUnit(bucketIndex, itemIndex));

            if (itemIndex < bucket.size()) {
              @SuppressWarnings("ObjectAllocationInLoop") final var entry =
                  bucket.getEntry(itemIndex, keySerializer, valueSerializer, serializerFactory);
              bucketIndex = entry.leftChild;
            } else {
              @SuppressWarnings("ObjectAllocationInLoop") final var entry =
                  bucket.getEntry(itemIndex - 1, keySerializer, valueSerializer, serializerFactory);
              bucketIndex = entry.rightChild;
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
              return Optional.empty();
            }
          } else {
            final var resultPath = new LongArrayList(path.size() + 1);
            for (final var pathItemUnit : path) {
              resultPath.add(pathItemUnit.pageIndex);
            }

            resultPath.add(bucketIndex);
            return Optional.of(new BucketSearchResult(0, resultPath));
          }
        }

        cacheEntry.close();

        cacheEntry = loadPageForRead(atomicOperation, fileId, bucketIndex);

        //noinspection ObjectAllocationInLoop
        bucket = new SBTreeBucketV2<>(cacheEntry);
      }
    } finally {
      cacheEntry.close();
    }
  }

  private Optional<BucketSearchResult> lastItem(final AtomicOperation atomicOperation)
      throws IOException {
    final var path = new LinkedList<PagePathItemUnit>();

    var bucketIndex = ROOT_INDEX;

    var cacheEntry = loadPageForRead(atomicOperation, fileId, bucketIndex);

    var bucket = new SBTreeBucketV2<K, V>(cacheEntry);

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
              return Optional.empty();
            }
          } else {
            //noinspection ObjectAllocationInLoop
            path.add(new PagePathItemUnit(bucketIndex, itemIndex));

            if (itemIndex > -1) {
              @SuppressWarnings("ObjectAllocationInLoop") final var entry =
                  bucket.getEntry(itemIndex, keySerializer, valueSerializer, serializerFactory);
              bucketIndex = entry.rightChild;
            } else {
              @SuppressWarnings("ObjectAllocationInLoop") final var entry =
                  bucket.getEntry(0, keySerializer, valueSerializer, serializerFactory);
              bucketIndex = entry.leftChild;
            }

            itemIndex = SBTreeBucketV2.MAX_PAGE_SIZE_BYTES + 1;
          }
        } else {
          if (bucket.isEmpty()) {
            if (!path.isEmpty()) {
              final var pagePathItemUnit = path.removeLast();

              bucketIndex = pagePathItemUnit.pageIndex;
              itemIndex = pagePathItemUnit.itemIndex - 1;
            } else {
              return Optional.empty();
            }
          } else {
            final var resultPath = new LongArrayList(path.size() + 1);
            for (final var pathItemUnit : path) {
              resultPath.add(pathItemUnit.pageIndex);
            }

            resultPath.add(bucketIndex);

            return Optional.of(new BucketSearchResult(bucket.size() - 1, resultPath));
          }
        }

        cacheEntry.close();

        cacheEntry = loadPageForRead(atomicOperation, fileId, bucketIndex);

        //noinspection ObjectAllocationInLoop
        bucket = new SBTreeBucketV2<>(cacheEntry);
        if (itemIndex == SBTreeBucketV2.MAX_PAGE_SIZE_BYTES + 1) {
          itemIndex = bucket.size() - 1;
        }
      }
    } finally {
      cacheEntry.close();
    }
  }

  private Spliterator<RawPair<K, V>> iterateEntriesBetweenAscOrder(
      K keyFrom, final boolean fromInclusive, K keyTo, final boolean toInclusive) {
    keyFrom = keySerializer.preprocess(serializerFactory, keyFrom, (Object[]) keyTypes);
    keyTo = keySerializer.preprocess(serializerFactory, keyTo, (Object[]) keyTypes);

    keyFrom = enhanceFromCompositeKeyBetweenAsc(keyFrom, fromInclusive);
    keyTo = enhanceToCompositeKeyBetweenAsc(keyTo, toInclusive);

    return new SpliteratorForward(keyFrom, keyTo, fromInclusive, toInclusive);
  }

  private Spliterator<RawPair<K, V>> iterateEntriesBetweenDescOrder(
      K keyFrom, final boolean fromInclusive, K keyTo, final boolean toInclusive) {
    keyFrom = keySerializer.preprocess(serializerFactory, keyFrom, (Object[]) keyTypes);
    keyTo = keySerializer.preprocess(serializerFactory, keyTo, (Object[]) keyTypes);

    keyFrom = enhanceFromCompositeKeyBetweenDesc(keyFrom, fromInclusive);
    keyTo = enhanceToCompositeKeyBetweenDesc(keyTo, toInclusive);

    return new SpliteratorBackward(keyFrom, keyTo, fromInclusive, toInclusive);
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

  private BucketSearchResult splitBucket(
      final LongList path,
      final int keyIndex,
      final K keyToInsert,
      final AtomicOperation atomicOperation)
      throws IOException {
    final var pageIndex = path.getLong(path.size() - 1);

    try (final var bucketEntry =
        loadPageForWrite(atomicOperation, fileId, pageIndex, true)) {
      final var bucketToSplit = new SBTreeBucketV2<K, V>(bucketEntry);

      final var splitLeaf = bucketToSplit.isLeaf();
      final var bucketSize = bucketToSplit.size();

      final var indexToSplit = bucketSize >>> 1;
      final var separationKey = bucketToSplit.getKey(indexToSplit, keySerializer,
          serializerFactory);
      final List<byte[]> rightEntries = new ArrayList<>(indexToSplit);

      final var startRightIndex = splitLeaf ? indexToSplit : indexToSplit + 1;

      for (var i = startRightIndex; i < bucketSize; i++) {
        rightEntries.add(bucketToSplit.getRawEntry(i, keySerializer, valueSerializer,
            serializerFactory));
      }

      if (pageIndex != ROOT_INDEX) {
        return splitNonRootBucket(
            path,
            keyIndex,
            keyToInsert,
            pageIndex,
            bucketToSplit,
            splitLeaf,
            indexToSplit,
            separationKey,
            rightEntries,
            atomicOperation);
      } else {
        return splitRootBucket(
            path,
            keyIndex,
            keyToInsert,
            bucketEntry,
            bucketToSplit,
            splitLeaf,
            indexToSplit,
            separationKey,
            rightEntries,
            atomicOperation);
      }
    }
  }

  private BucketSearchResult splitNonRootBucket(
      final LongList path,
      final int keyIndex,
      final K keyToInsert,
      final long pageIndex,
      final SBTreeBucketV2<K, V> bucketToSplit,
      final boolean splitLeaf,
      final int indexToSplit,
      final K separationKey,
      final List<byte[]> rightEntries,
      final AtomicOperation atomicOperation)
      throws IOException {
    long rightPageIndex;
    try (final var rightBucketEntry = addPage(atomicOperation, fileId)) {
      rightPageIndex = rightBucketEntry.getPageIndex();
      final var newRightBucket = new SBTreeBucketV2<K, V>(rightBucketEntry);
      newRightBucket.init(splitLeaf);
      newRightBucket.addAll(rightEntries, keySerializer, valueSerializer);

      bucketToSplit.shrink(indexToSplit, keySerializer, valueSerializer, serializerFactory);

      if (splitLeaf) {
        final var rightSiblingPageIndex = bucketToSplit.getRightSibling();

        newRightBucket.setRightSibling(rightSiblingPageIndex);
        newRightBucket.setLeftSibling(pageIndex);

        bucketToSplit.setRightSibling(rightBucketEntry.getPageIndex());

        if (rightSiblingPageIndex >= 0) {

          try (final var rightSiblingBucketEntry =
              loadPageForWrite(atomicOperation, fileId, rightSiblingPageIndex, true)) {
            final var rightSiblingBucket =
                new SBTreeBucketV2<K, V>(rightSiblingBucketEntry);
            rightSiblingBucket.setLeftSibling(rightBucketEntry.getPageIndex());
          }
        }
      }

      var parentIndex = path.getLong(path.size() - 2);
      var parentCacheEntry = loadPageForWrite(atomicOperation, fileId, parentIndex, true);
      try {
        var parentBucket = new SBTreeBucketV2<K, V>(parentCacheEntry);
        final var rawSeparationKey = keySerializer.serializeNativeAsWhole(serializerFactory,
            separationKey);

        var insertionIndex = parentBucket.find(separationKey, keySerializer, serializerFactory);
        assert insertionIndex < 0;

        insertionIndex = -insertionIndex - 1;
        while (!parentBucket.addNonLeafEntry(
            insertionIndex, rawSeparationKey, pageIndex, rightBucketEntry.getPageIndex(), true)) {
          parentCacheEntry.close();

          final var bucketSearchResult =
              splitBucket(
                  path.subList(0, path.size() - 1), insertionIndex, separationKey, atomicOperation);

          parentIndex = bucketSearchResult.getLastPathItem();
          parentCacheEntry = loadPageForWrite(atomicOperation, fileId, parentIndex, true);

          insertionIndex = bucketSearchResult.itemIndex;

          //noinspection ObjectAllocationInLoop
          parentBucket = new SBTreeBucketV2<>(parentCacheEntry);
        }

      } finally {
        parentCacheEntry.close();
      }
    }

    final var resultPath = new LongArrayList(path.subList(0, path.size() - 1));

    if (comparator.compare(keyToInsert, separationKey) < 0) {
      resultPath.add(pageIndex);
      return new BucketSearchResult(keyIndex, resultPath);
    }

    resultPath.add(rightPageIndex);
    if (splitLeaf) {
      return new BucketSearchResult(keyIndex - indexToSplit, resultPath);
    }

    resultPath.add(rightPageIndex);
    return new BucketSearchResult(keyIndex - indexToSplit - 1, resultPath);
  }

  private BucketSearchResult splitRootBucket(
      final LongList path,
      final int keyIndex,
      final K keyToInsert,
      final CacheEntry bucketEntry,
      SBTreeBucketV2<K, V> bucketToSplit,
      final boolean splitLeaf,
      final int indexToSplit,
      final K separationKey,
      final List<byte[]> rightEntries,
      final AtomicOperation atomicOperation)
      throws IOException {
    final var treeSize = bucketToSplit.getTreeSize();

    final List<byte[]> leftEntries = new ArrayList<>(indexToSplit);

    for (var i = 0; i < indexToSplit; i++) {
      leftEntries.add(bucketToSplit.getRawEntry(i, keySerializer, valueSerializer,
          serializerFactory));
    }

    final var leftBucketEntry = addPage(atomicOperation, fileId);

    final var rightBucketEntry = addPage(atomicOperation, fileId);
    try {
      final var newLeftBucket = new SBTreeBucketV2<K, V>(leftBucketEntry);
      newLeftBucket.init(splitLeaf);
      newLeftBucket.addAll(leftEntries, keySerializer, valueSerializer);

      if (splitLeaf) {
        newLeftBucket.setRightSibling(rightBucketEntry.getPageIndex());
      }

    } finally {
      leftBucketEntry.close();
    }

    try {
      final var newRightBucket = new SBTreeBucketV2<K, V>(rightBucketEntry);
      newRightBucket.init(splitLeaf);
      newRightBucket.addAll(rightEntries, keySerializer, valueSerializer);

      if (splitLeaf) {
        newRightBucket.setLeftSibling(leftBucketEntry.getPageIndex());
      }
    } finally {
      rightBucketEntry.close();
    }

    bucketToSplit = new SBTreeBucketV2<>(bucketEntry);
    bucketToSplit.shrink(0, keySerializer, valueSerializer, serializerFactory);
    if (splitLeaf) {
      bucketToSplit.switchBucketType();
    }

    bucketToSplit.setTreeSize(treeSize);

    bucketToSplit.addNonLeafEntry(
        0,
        keySerializer.serializeNativeAsWhole(serializerFactory, separationKey),
        leftBucketEntry.getPageIndex(),
        rightBucketEntry.getPageIndex(),
        true);

    final var resultPath = new LongArrayList(path.subList(0, path.size() - 1));

    if (comparator.compare(keyToInsert, separationKey) < 0) {
      resultPath.add(leftBucketEntry.getPageIndex());
      return new BucketSearchResult(keyIndex, resultPath);
    }

    resultPath.add(rightBucketEntry.getPageIndex());

    if (splitLeaf) {
      return new BucketSearchResult(keyIndex - indexToSplit, resultPath);
    }

    return new BucketSearchResult(keyIndex - indexToSplit - 1, resultPath);
  }

  private BucketSearchResult findBucket(final K key, final AtomicOperation atomicOperation)
      throws IOException {
    var pageIndex = ROOT_INDEX;
    final var path = new LongArrayList(8);

    while (true) {
      if (path.size() > MAX_PATH_LENGTH) {
        throw new SBTreeException(
            "We reached max level of depth of SBTree but still found nothing, seems like tree is in"
                + " corrupted state. You should rebuild index related to given query.",
            this);
      }

      path.add(pageIndex);

      final SBTreeBucketV2.SBTreeEntry<K, V> entry;

      try (final var bucketEntry = loadPageForRead(atomicOperation, fileId, pageIndex)) {
        @SuppressWarnings("ObjectAllocationInLoop") final var keyBucket = new SBTreeBucketV2<K, V>(
            bucketEntry);
        final var index = keyBucket.find(key, keySerializer, serializerFactory);

        if (keyBucket.isLeaf()) {
          return new BucketSearchResult(index, path);
        }

        if (index >= 0) {
          //noinspection ObjectAllocationInLoop
          entry = keyBucket.getEntry(index, keySerializer, valueSerializer, serializerFactory);
        } else {
          final var insertionIndex = -index - 1;
          if (insertionIndex >= keyBucket.size()) {
            //noinspection ObjectAllocationInLoop
            entry = keyBucket.getEntry(insertionIndex - 1, keySerializer, valueSerializer,
                serializerFactory);
          } else {
            //noinspection ObjectAllocationInLoop
            entry = keyBucket.getEntry(insertionIndex, keySerializer, valueSerializer,
                serializerFactory);
          }
        }
      }

      if (comparator.compare(key, entry.key) >= 0) {
        pageIndex = entry.rightChild;
      } else {
        pageIndex = entry.leftChild;
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

  private static class BucketSearchResult {

    private final int itemIndex;
    private final LongArrayList path;

    private BucketSearchResult(final int itemIndex, final LongArrayList path) {
      this.itemIndex = itemIndex;
      this.path = path;
    }

    long getLastPathItem() {
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

  private final class SpliteratorForward implements Spliterator<RawPair<K, V>> {

    private final K fromKey;
    private final K toKey;
    private final boolean fromKeyInclusive;
    private final boolean toKeyInclusive;

    private int pageIndex = -1;
    private int itemIndex = -1;

    private LogSequenceNumber lastLSN = null;

    private final List<RawPair<K, V>> dataCache = new ArrayList<>();
    private Iterator<RawPair<K, V>> cacheIterator = Collections.emptyIterator();

    private SpliteratorForward(
        final K fromKey,
        final K toKey,
        final boolean fromKeyInclusive,
        final boolean toKeyInclusive) {
      this.fromKey = fromKey;
      this.toKey = toKey;

      this.toKeyInclusive = toKeyInclusive;
      this.fromKeyInclusive = fromKeyInclusive;
    }

    @Override
    public boolean tryAdvance(Consumer<? super RawPair<K, V>> action) {
      if (cacheIterator == null) {
        return false;
      }

      if (cacheIterator.hasNext()) {
        action.accept(cacheIterator.next());
        return true;
      }

      fetchNextCachePortion();

      cacheIterator = dataCache.iterator();

      if (cacheIterator.hasNext()) {
        action.accept(cacheIterator.next());
        return true;
      }

      cacheIterator = null;

      return false;
    }

    private void fetchNextCachePortion() {
      final K lastKey;
      if (dataCache.isEmpty()) {
        lastKey = null;
      } else {
        lastKey = dataCache.getLast().first;
      }

      dataCache.clear();
      cacheIterator = Collections.emptyIterator();

      atomicOperationsManager.acquireReadLock(SBTreeV2.this);
      try {
        acquireSharedLock();
        try {
          final var atomicOperation = atomicOperationsManager.getCurrentOperation();
          if (pageIndex > -1) {
            if (readKeysFromBuckets(atomicOperation)) {
              return;
            }
          }

          // this can only happen if page LSN does not equal to stored LSN or index of current
          // iterated page equals to -1
          // so we only started iteration
          if (dataCache.isEmpty()) {
            // iteration just started
            if (lastKey == null) {
              if (this.fromKey != null) {
                final var searchResult = findBucket(fromKey, atomicOperation);
                pageIndex = (int) searchResult.getLastPathItem();

                if (searchResult.itemIndex >= 0) {
                  if (fromKeyInclusive) {
                    itemIndex = searchResult.itemIndex;
                  } else {
                    itemIndex = searchResult.itemIndex + 1;
                  }
                } else {
                  itemIndex = -searchResult.itemIndex - 1;
                }
              } else {
                final var bucketSearchResult = firstItem(atomicOperation);
                if (bucketSearchResult.isPresent()) {
                  final var searchResult = bucketSearchResult.get();
                  pageIndex = (int) searchResult.getLastPathItem();
                  itemIndex = searchResult.itemIndex;
                } else {
                  return;
                }
              }

            } else {
              final var bucketSearchResult = findBucket(lastKey, atomicOperation);

              pageIndex = (int) bucketSearchResult.getLastPathItem();
              if (bucketSearchResult.itemIndex >= 0) {
                itemIndex = bucketSearchResult.itemIndex + 1;
              } else {
                itemIndex = -bucketSearchResult.itemIndex - 1;
              }
            }
            readKeysFromBuckets(atomicOperation);
          }
        } finally {
          releaseSharedLock();
        }
      } catch (final IOException e) {
        throw BaseException.wrapException(
            new SBTreeException("Error during entity iteration", SBTreeV2.this), e,
            storage.getName());
      } finally {
        atomicOperationsManager.releaseReadLock(SBTreeV2.this);
      }
    }

    private boolean readKeysFromBuckets(AtomicOperation atomicOperation) throws IOException {
      var cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex);
      try {
        var bucket = new SBTreeBucketV2<K, V>(cacheEntry);
        if (lastLSN == null || bucket.getLsn().equals(lastLSN)) {
          while (true) {
            final var bucketSize = bucket.size();
            lastLSN = bucket.getLsn();

            for (;
                itemIndex < bucketSize && dataCache.size() < SPLITERATOR_CACHE_SIZE;
                itemIndex++) {
              @SuppressWarnings("ObjectAllocationInLoop")
              var entry =
                  bucket.getEntry(itemIndex, keySerializer, valueSerializer, serializerFactory);

              if (toKey != null) {
                if (toKeyInclusive) {
                  if (comparator.compare(entry.key, toKey) > 0) {
                    return true;
                  }
                } else if (comparator.compare(entry.key, toKey) >= 0) {
                  return true;
                }
              }

              //noinspection ObjectAllocationInLoop
              dataCache.add(new RawPair<>(entry.key, entry.value.getValue()));
            }

            if (itemIndex >= bucketSize) {
              pageIndex = (int) bucket.getRightSibling();
              itemIndex = 0;
            }

            if (dataCache.size() < SPLITERATOR_CACHE_SIZE) {
              if (pageIndex < 0) {
                return true;
              } else {
                cacheEntry.close();

                cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex);
                //noinspection ObjectAllocationInLoop
                bucket = new SBTreeBucketV2<>(cacheEntry);
              }
            } else {
              return true;
            }
          }
        }
      } finally {
        cacheEntry.close();
      }

      lastLSN = null;
      return false;
    }

    @Override
    public Spliterator<RawPair<K, V>> trySplit() {
      return null;
    }

    @Override
    public long estimateSize() {
      return Long.MAX_VALUE;
    }

    @Override
    public int characteristics() {
      return SORTED | NONNULL | ORDERED;
    }

    @Override
    public Comparator<? super RawPair<K, V>> getComparator() {
      return (pairOne, pairTwo) -> comparator.compare(pairOne.first, pairTwo.first);
    }
  }

  private final class SpliteratorBackward implements Spliterator<RawPair<K, V>> {

    private final K fromKey;
    private final K toKey;
    private final boolean fromKeyInclusive;
    private final boolean toKeyInclusive;

    private int pageIndex = -1;
    private int itemIndex = -1;

    private LogSequenceNumber lastLSN = null;

    private final List<RawPair<K, V>> dataCache = new ArrayList<>();
    private Iterator<RawPair<K, V>> cacheIterator = Collections.emptyIterator();

    private SpliteratorBackward(
        final K fromKey,
        final K toKey,
        final boolean fromKeyInclusive,
        final boolean toKeyInclusive) {
      this.fromKey = fromKey;
      this.toKey = toKey;
      this.fromKeyInclusive = fromKeyInclusive;
      this.toKeyInclusive = toKeyInclusive;
    }

    @Override
    public boolean tryAdvance(Consumer<? super RawPair<K, V>> action) {
      if (cacheIterator == null) {
        return false;
      }

      if (cacheIterator.hasNext()) {
        action.accept(cacheIterator.next());
        return true;
      }

      fetchNextCachePortion();

      cacheIterator = dataCache.iterator();

      if (cacheIterator.hasNext()) {
        action.accept(cacheIterator.next());
        return true;
      }

      cacheIterator = null;

      return false;
    }

    private void fetchNextCachePortion() {
      final K lastKey;
      if (dataCache.isEmpty()) {
        lastKey = null;
      } else {
        lastKey = dataCache.getLast().first;
      }

      dataCache.clear();
      cacheIterator = Collections.emptyIterator();

      atomicOperationsManager.acquireReadLock(SBTreeV2.this);
      try {
        acquireSharedLock();
        try {
          final var atomicOperation = atomicOperationsManager.getCurrentOperation();
          if (pageIndex > -1) {
            if (readKeysFromBuckets(atomicOperation)) {
              return;
            }
          }

          // this can only happen if page LSN does not equal to stored LSN or index of current
          // iterated page equals to -1
          // so we only started iteration
          if (dataCache.isEmpty()) {
            // iteration just started
            if (lastKey == null) {
              if (this.toKey != null) {
                final var searchResult = findBucket(toKey, atomicOperation);
                pageIndex = (int) searchResult.getLastPathItem();

                if (searchResult.itemIndex >= 0) {
                  if (toKeyInclusive) {
                    itemIndex = searchResult.itemIndex;
                  } else {
                    itemIndex = searchResult.itemIndex - 1;
                  }
                } else {
                  itemIndex = -searchResult.itemIndex - 2;
                }
              } else {
                final var bucketSearchResult = lastItem(atomicOperation);
                if (bucketSearchResult.isPresent()) {
                  final var searchResult = bucketSearchResult.get();
                  pageIndex = (int) searchResult.getLastPathItem();
                  itemIndex = searchResult.itemIndex;
                } else {
                  return;
                }
              }

            } else {
              final var bucketSearchResult = findBucket(lastKey, atomicOperation);

              pageIndex = (int) bucketSearchResult.getLastPathItem();
              if (bucketSearchResult.itemIndex >= 0) {
                itemIndex = bucketSearchResult.itemIndex - 1;
              } else {
                itemIndex = -bucketSearchResult.itemIndex - 2;
              }
            }
            readKeysFromBuckets(atomicOperation);
          }
        } finally {
          releaseSharedLock();
        }
      } catch (final IOException e) {
        throw BaseException.wrapException(
            new SBTreeException("Error during entity iteration", SBTreeV2.this), e,
            storage.getName());
      } finally {
        atomicOperationsManager.releaseReadLock(SBTreeV2.this);
      }
    }

    private boolean readKeysFromBuckets(AtomicOperation atomicOperation) throws IOException {
      var cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex);
      try {
        var bucket = new SBTreeBucketV2<K, V>(cacheEntry);
        if (lastLSN == null || bucket.getLsn().equals(lastLSN)) {
          while (true) {
            final var bucketSize = bucket.size();
            if (itemIndex == Integer.MIN_VALUE) {
              itemIndex = bucketSize - 1;
            } else if (itemIndex < -1) {
              throw new IllegalStateException("Invalid value of item index");
            }

            lastLSN = bucket.getLsn();

            for (; itemIndex >= 0 && dataCache.size() < SPLITERATOR_CACHE_SIZE; itemIndex--) {
              @SuppressWarnings("ObjectAllocationInLoop")
              var entry =
                  bucket.getEntry(itemIndex, keySerializer, valueSerializer, serializerFactory);

              if (fromKey != null) {
                if (fromKeyInclusive) {
                  if (comparator.compare(entry.key, fromKey) < 0) {
                    return true;
                  }
                } else if (comparator.compare(entry.key, fromKey) <= 0) {
                  return true;
                }
              }

              //noinspection ObjectAllocationInLoop
              dataCache.add(new RawPair<>(entry.key, entry.value.getValue()));
            }

            if (itemIndex < 0) {
              pageIndex = (int) bucket.getLeftSibling();
              itemIndex = Integer.MIN_VALUE;
            }

            if (dataCache.size() < SPLITERATOR_CACHE_SIZE) {
              if (pageIndex < 0) {
                return true;
              } else {
                cacheEntry.close();

                cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex);
                //noinspection ObjectAllocationInLoop
                bucket = new SBTreeBucketV2<>(cacheEntry);
              }
            } else {
              return true;
            }
          }
        }
      } finally {
        cacheEntry.close();
      }

      lastLSN = null;
      return false;
    }

    @Override
    public Spliterator<RawPair<K, V>> trySplit() {
      return null;
    }

    @Override
    public long estimateSize() {
      return Long.MAX_VALUE;
    }

    @Override
    public int characteristics() {
      return SORTED | NONNULL | ORDERED;
    }

    @Override
    public Comparator<? super RawPair<K, V>> getComparator() {
      return (pairOne, pairTwo) -> -comparator.compare(pairOne.first, pairTwo.first);
    }
  }
}
