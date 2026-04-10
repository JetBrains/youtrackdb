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

package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.comparator.DefaultComparator;
import com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.ShortSerializer;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.TooBigIndexKeyException;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.index.IndexesSnapshot;
import com.jetbrains.youtrackdb.internal.core.index.comparator.AlwaysGreaterKey;
import com.jetbrains.youtrackdb.internal.core.index.comparator.AlwaysLessKey;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValidator;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.OptimisticReadFailedException;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.StorageComponent;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.CellBTreeSingleValue;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Spliterator;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * This is implementation which is based on B+-tree implementation threaded tree. The main
 * differences are:
 *
 * <ol>
 *   <li>Buckets are not compacted/removed if they are empty after deletion of item. They reused
 *       later when new items are added.
 *   <li>All non-leaf buckets have links to neighbor buckets which contain keys which are less/more
 *       than keys contained in current bucket
 * </ol>
 * <p>
 * There is support of null values for keys, but values itself cannot be null. Null keys support is
 * switched off by default if null keys are supported value which is related to null key will be
 * stored in separate file which has only one page. Buckets/pages for usual (non-null) key-value
 * entries can be considered as sorted array. The first bytes of page contains such auxiliary
 * information as size of entries contained in bucket, links to neighbors which contain entries with
 * keys less/more than keys in current bucket. The next bytes contain sorted array of entries. Array
 * itself is split on two parts. First part is growing from start to end, and second part is growing
 * from end to start. First part is array of offsets to real key-value entries which are stored in
 * second part of array which grows from end to start. This array of offsets is sorted by accessing
 * order according to key value. So we can use binary search to find requested key. When new
 * key-value pair is added we append binary presentation of this pair to the second part of array
 * which grows from end of page to start, remember value of offset for this pair, and find proper
 * position of this offset inside of first part of array. Such approach allows to minimize amount of
 * memory involved in performing of operations and as result speed up data processing.
 *
 * @since 8/7/13
 */
public final class BTree<K> extends StorageComponent implements CellBTreeSingleValue<K> {

  private static final int SPLITERATOR_CACHE_SIZE =
      GlobalConfiguration.INDEX_CURSOR_PREFETCH_SIZE.getValueAsInteger();
  // Cached per-instance in create()/load() instead of reading from
  // GlobalConfiguration on every put(). Cannot be a static final because
  // the value depends on DISK_CACHE_PAGE_SIZE which is set by
  // AbstractStorage.checkPageSizeAndRelatedParametersInGlobalConfiguration()
  // after class loading.
  private volatile int maxKeySize;
  private static final AlwaysLessKey ALWAYS_LESS_KEY = new AlwaysLessKey();
  private static final AlwaysGreaterKey ALWAYS_GREATER_KEY = new AlwaysGreaterKey();

  private static final int MAX_PATH_LENGTH =
      GlobalConfiguration.BTREE_MAX_DEPTH.getValueAsInteger();

  private static final int ENTRY_POINT_INDEX = 0;
  private static final long ROOT_INDEX = 1;
  final Comparator<? super K> comparator = DefaultComparator.INSTANCE;

  private final String nullFileExtension;
  private volatile long fileId;
  private volatile long nullBucketFileId = -1;
  private volatile int keySize;
  private volatile BinarySerializer<K> keySerializer;
  private final BinarySerializerFactory serializerFactory;
  private volatile PropertyTypeInternal[] keyTypes;

  public BTree(
      @Nonnull final String name,
      final String dataFileExtension,
      final String nullFileExtension,
      final AbstractStorage storage) {
    super(storage, name, dataFileExtension, name + dataFileExtension, true);
    acquireExclusiveLock();
    try {
      this.nullFileExtension = nullFileExtension;
      serializerFactory = storage.getComponentsFactory().binarySerializerFactory;
    } finally {
      releaseExclusiveLock();
    }
  }

  public BTree(
      @Nonnull final String name,
      final String dataFileExtension,
      final String nullFileExtension,
      final AbstractStorage storage, BinarySerializerFactory serializerFactory) {
    super(storage, name, dataFileExtension, name + dataFileExtension, true);
    acquireExclusiveLock();
    try {
      this.nullFileExtension = nullFileExtension;
      this.serializerFactory = serializerFactory;
    } finally {
      releaseExclusiveLock();
    }
  }

  @Override
  public void create(
      @Nonnull final AtomicOperation atomicOperation,
      final BinarySerializer<K> keySerializer,
      final PropertyTypeInternal[] keyTypes,
      final int keySize) {
    assert keySerializer != null;

    executeInsideComponentOperation(
        atomicOperation,
        operation -> {
          acquireExclusiveLock();
          try {
            this.keySize = keySize;
            this.maxKeySize =
                GlobalConfiguration.BTREE_MAX_KEY_SIZE.getValueAsInteger();
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
                  new CellBTreeSingleValueEntryPointV3<K>(entryPointCacheEntry);
              entryPoint.init();
            }

            try (final var rootCacheEntry = addPage(atomicOperation, fileId)) {
              @SuppressWarnings("unused")
              final var rootBucket =
                  new CellBTreeSingleValueBucketV3<K>(rootCacheEntry);
              rootBucket.init(true);
            }

            try (final var nullCacheEntry = addPage(atomicOperation, nullBucketFileId)) {
              @SuppressWarnings("unused")
              final var nullBucket =
                  new CellBTreeSingleValueV3NullBucket(nullCacheEntry);
              nullBucket.init();
            }
          } finally {
            releaseExclusiveLock();
          }
        });
  }

  @Override
  @Nullable public RID get(K key, @Nonnull AtomicOperation atomicOperation) {
    try {
      if (key != null) {
        final var preprocessedKey =
            keySerializer.preprocess(serializerFactory, key, (Object[]) keyTypes);
        final var serializedKey =
            keySerializer.serializeNativeAsWhole(
                serializerFactory, preprocessedKey, (Object[]) keyTypes);

        return executeOptimisticStorageRead(
            atomicOperation,
            () -> getOptimistic(atomicOperation, serializedKey),
            () -> getPinned(atomicOperation, preprocessedKey, serializedKey));
      } else {
        return executeOptimisticStorageRead(
            atomicOperation,
            () -> getNullKeyOptimistic(atomicOperation),
            () -> getNullKeyPinned(atomicOperation));
      }
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new CellBTreeSingleValueV3Exception(
              "Error during retrieving  of sbtree with name " + getName(), this),
          e, storage.getName());
    }
  }

  /**
   * Optimistic path for non-null key get: traverses the B-tree using loadPageOptimistic()
   * with per-level stamp validation, then reads the value from the leaf page.
   */
  @Nullable private RID getOptimistic(
      final AtomicOperation atomicOperation,
      final byte[] serializedKey) {
    final var scope = atomicOperation.getOptimisticReadScope();
    var pageIndex = ROOT_INDEX;

    var depth = 0;
    while (true) {
      depth++;
      if (depth > MAX_PATH_LENGTH) {
        // Under the optimistic path, a concurrent tree restructuring could cause
        // transient depth overflow from following stale pointers. Fall back to the
        // pinned path which will either find the key or report genuine corruption.
        throw OptimisticReadFailedException.INSTANCE;
      }

      final var pageView = loadPageOptimistic(atomicOperation, fileId, pageIndex);
      final var bucket = new CellBTreeSingleValueBucketV3<K>(pageView);
      final var index = bucket.find(serializedKey, keySerializer, serializerFactory);

      if (bucket.isLeaf()) {
        if (index < 0) {
          return null;
        }
        return bucket.getValue(index, keySerializer, serializerFactory);
      }

      // Internal node: follow child pointer, then validate the stamp to catch eviction
      // before chasing a potentially stale page index.
      if (index >= 0) {
        pageIndex = bucket.getRight(index);
      } else {
        final var insertionIndex = -index - 1;
        if (insertionIndex >= bucket.size()) {
          pageIndex = bucket.getRight(insertionIndex - 1);
        } else {
          pageIndex = bucket.getLeft(insertionIndex);
        }
      }
      scope.validateLastOrThrow();
    }
  }

  /**
   * CAS-pinned fallback path for non-null key get: uses loadPageForRead() with
   * try-with-resources as before.
   */
  @Nullable private RID getPinned(
      final AtomicOperation atomicOperation,
      final K key, final byte[] serializedKey) throws IOException {
    final var bucketSearchResult =
        findBucketSerialized(key, serializedKey, atomicOperation);
    if (bucketSearchResult.getItemIndex() < 0) {
      return null;
    }

    final var pageIndex = bucketSearchResult.getPageIndex();

    try (final var keyBucketCacheEntry =
        loadPageForRead(atomicOperation, fileId, pageIndex)) {
      final var keyBucket =
          new CellBTreeSingleValueBucketV3<K>(keyBucketCacheEntry);
      return keyBucket.getValue(bucketSearchResult.getItemIndex(), keySerializer,
          serializerFactory);
    }
  }

  /** Optimistic path for null-key get. */
  @Nullable private RID getNullKeyOptimistic(final AtomicOperation atomicOperation) {
    final var pageView = loadPageOptimistic(atomicOperation, nullBucketFileId, 0);
    final var nullBucket = new CellBTreeSingleValueV3NullBucket(pageView);
    return nullBucket.getValue();
  }

  /** CAS-pinned fallback path for null-key get. */
  @Nullable private RID getNullKeyPinned(
      final AtomicOperation atomicOperation) throws IOException {
    try (final var nullBucketCacheEntry =
        loadPageForRead(atomicOperation, nullBucketFileId, 0)) {
      final var nullBucket =
          new CellBTreeSingleValueV3NullBucket(nullBucketCacheEntry);
      return nullBucket.getValue();
    }
  }

  @Override
  @Nullable public RID getVisible(K key, IndexesSnapshot snapshot,
      @Nonnull AtomicOperation atomicOperation) {
    assert key instanceof CompositeKey
        : "getVisible() requires CompositeKey, got " + (key == null ? "null" : key.getClass());
    // If the key has fewer user elements than the index expects (e.g., raw null
    // passed for a composite index becomes CompositeKey(null) with 1 element,
    // but a 2-field composite index expects 2 user elements), no entry can match.
    // Composite indexes have no "null key" concept — only composite keys with
    // individually-null fields.
    final var userKeyElements = ((CompositeKey) key).getKeys().size();
    if (userKeyElements < keySize - 1) {
      return null;
    }
    try {
      // Build a search key padded with Long.MIN_VALUE as the version component.
      // This ensures the serialized key has the full number of elements that
      // IndexMultiValuKeySerializer expects, and Long.MIN_VALUE guarantees the
      // search key sorts before any real versioned entry with the same user-key
      // prefix. bucket.find() will return a negative insertion point at the first
      // entry with the matching prefix.
      final var searchKey = buildSearchKey(key);
      final var preprocessedKey =
          keySerializer.preprocess(serializerFactory, searchKey, (Object[]) keyTypes);
      final var serializedKey =
          keySerializer.serializeNativeAsWhole(
              serializerFactory, preprocessedKey, (Object[]) keyTypes);

      final var opsSnapshot = atomicOperation.getAtomicOperationsSnapshot();
      final var snapshotTs = opsSnapshot.snapshotTs();
      final var inProgressVersions = opsSnapshot.inProgressTxs();

      return executeOptimisticStorageRead(
          atomicOperation,
          () -> getVisibleOptimistic(
              atomicOperation, preprocessedKey, serializedKey, snapshot,
              snapshotTs, inProgressVersions),
          () -> getVisiblePinned(
              atomicOperation, preprocessedKey, serializedKey, snapshot,
              snapshotTs, inProgressVersions));
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new CellBTreeSingleValueV3Exception(
              "Error during getVisible of sbtree with name " + getName(), this),
          e, storage.getName());
    }
  }

  /**
   * Builds a search key from the user-facing prefix key by appending {@code Long.MIN_VALUE}
   * as the version component. This ensures the search key is serializable with the full
   * {@code keyTypes} array and sorts before any real versioned entry with the same user-key
   * prefix, so {@code bucket.find()} returns the insertion point at the first matching entry.
   *
   * <p>Creates a defensive copy — the caller's key is not mutated.
   */
  /**
   * Builds a search key by padding to {@code keySize} with {@code Long.MIN_VALUE}.
   * Used by {@link #getVisible} where the caller supplies all user-key elements
   * and only the version element is missing.
   */
  @SuppressWarnings("unchecked")
  private K buildSearchKey(K prefixKey) {
    if (prefixKey instanceof CompositeKey compositeKey) {
      var fullKey = new CompositeKey(compositeKey);
      // Pad with Long.MIN_VALUE for each missing key element
      var itemsToAdd = keySize - fullKey.getKeys().size();
      for (var i = 0; i < itemsToAdd; i++) {
        fullKey.addKey(Long.MIN_VALUE);
      }
      return (K) fullKey;
    }
    return prefixKey;
  }

  /**
   * Optimistic path for getVisible: descends to the leaf page using loadPageOptimistic(),
   * then scans entries with prefix matching and inline visibility check. If the leaf page
   * is exhausted without finding a visible entry, falls through to pinned path (which
   * can follow right siblings for cross-page entries).
   */
  @Nullable private RID getVisibleOptimistic(
      final AtomicOperation atomicOperation,
      final K prefixKey,
      final byte[] serializedKey,
      final IndexesSnapshot snapshot,
      final long snapshotTs,
      final LongOpenHashSet inProgressVersions) {
    final var scope = atomicOperation.getOptimisticReadScope();
    var pageIndex = ROOT_INDEX;

    var depth = 0;
    while (true) {
      depth++;
      if (depth > MAX_PATH_LENGTH) {
        throw OptimisticReadFailedException.INSTANCE;
      }

      final var pageView = loadPageOptimistic(atomicOperation, fileId, pageIndex);
      final var bucket = new CellBTreeSingleValueBucketV3<K>(pageView);
      final var index = bucket.find(serializedKey, keySerializer, serializerFactory);

      if (bucket.isLeaf()) {
        return scanLeafForVisible(
            bucket, index, prefixKey, snapshot,
            snapshotTs, inProgressVersions, true);
      }

      if (index >= 0) {
        pageIndex = bucket.getRight(index);
      } else {
        final var insertionIndex = -index - 1;
        if (insertionIndex >= bucket.size()) {
          pageIndex = bucket.getRight(insertionIndex - 1);
        } else {
          pageIndex = bucket.getLeft(insertionIndex);
        }
      }
      scope.validateLastOrThrow();
    }
  }

  /**
   * Pinned fallback path for getVisible: uses loadPageForRead() with try-with-resources.
   * Follows right sibling pointers for cross-page entries.
   */
  @Nullable private RID getVisiblePinned(
      final AtomicOperation atomicOperation,
      final K prefixKey, final byte[] serializedKey,
      final IndexesSnapshot snapshot,
      final long snapshotTs,
      final LongOpenHashSet inProgressVersions) throws IOException {
    final var bucketSearchResult =
        findBucketSerialized(prefixKey, serializedKey, atomicOperation);

    var pageIndex = bucketSearchResult.getPageIndex();
    var foundIndex = bucketSearchResult.getItemIndex();

    while (true) {
      try (final var cacheEntry =
          loadPageForRead(atomicOperation, fileId, pageIndex)) {
        final var bucket = new CellBTreeSingleValueBucketV3<K>(cacheEntry);

        final var result = scanLeafForVisible(
            bucket, foundIndex, prefixKey, snapshot,
            snapshotTs, inProgressVersions, false);
        if (result != null) {
          return result;
        }

        // Leaf exhausted — follow right sibling for cross-page entries
        final var rightSibling = bucket.getRightSibling();
        if (rightSibling < 0) {
          return null;
        }
        pageIndex = (int) rightSibling;
        // On the next page, scan from the start
        foundIndex = Integer.MIN_VALUE;
      }
    }
  }

  @Override
  public Stream<RID> getVisibleStream(K key, IndexesSnapshot snapshot,
      @Nonnull AtomicOperation atomicOperation) {
    assert key instanceof CompositeKey
        : "getVisibleStream() requires CompositeKey, got "
            + (key == null ? "null" : key.getClass());
    // Reuse the existing iterateEntriesBetween infrastructure for correct B-tree
    // navigation, but collect results eagerly with inline visibility filtering
    // to avoid the stream pipeline overhead (Spliterator + mapMulti per entry).
    final var preprocessedKey =
        keySerializer.preprocess(serializerFactory, key, (Object[]) keyTypes);

    final var opsSnapshot = atomicOperation.getAtomicOperationsSnapshot();
    final var snapshotTs = opsSnapshot.snapshotTs();
    final var inProgressVersions = opsSnapshot.inProgressTxs();

    try (var entryStream = iterateEntriesBetween(
        preprocessedKey, true, preprocessedKey, true, true, atomicOperation)) {
      final var result = new ArrayList<RID>();
      entryStream.forEach(pair -> {
        final var visibleRid = snapshot.checkVisibility(
            (CompositeKey) pair.first(), pair.second(),
            snapshotTs, inProgressVersions);
        if (visibleRid != null) {
          result.add(visibleRid);
        }
      });
      return result.stream();
    }
  }

  /**
   * Scans leaf entries forward from the found index for the first visible entry matching
   * the user-key prefix.
   *
   * <p>The search key is the user key padded with {@code Long.MIN_VALUE}, which sorts before
   * any real versioned entry. So {@code bucket.find()} typically returns a negative insertion
   * point at the first version entry. In the rare case of an exact match (a real entry has
   * version {@code Long.MIN_VALUE}), the found index points directly at a matching entry.
   *
   * <p>Prefix matching uses {@link #userKeyPrefixMatches}, which compares all CompositeKey
   * elements except the last (version) element. This correctly identifies entries belonging
   * to the same user key regardless of their version values.
   *
   * @param bucket the leaf bucket to scan
   * @param foundIndex the result of bucket.find() — positive if exact match, negative
   *     (-(insertionPoint)-1) if not found. Use Integer.MIN_VALUE to scan from index 0
   *     (for continuation pages in pinned path).
   * @param prefixKey the preprocessed search key (user key + Long.MIN_VALUE) for
   *     forward prefix-match checks
   * @param snapshot the indexes snapshot for visibility checks
   * @param snapshotTs the reader's snapshot timestamp (upper visibility bound,
   *     matching {@code AtomicOperationsSnapshot.snapshotTs()})
   * @param inProgressVersions set of in-progress transaction versions
   * @param optimistic if true, throw OptimisticReadFailedException when leaf is exhausted
   *     instead of returning null (to fall through to pinned path for cross-page entries)
   * @return the first visible RID, or null if no visible entry found on this page
   */
  @Nullable private RID scanLeafForVisible(
      final CellBTreeSingleValueBucketV3<K> bucket,
      final int foundIndex,
      final K prefixKey,
      final IndexesSnapshot snapshot,
      final long snapshotTs,
      final LongOpenHashSet inProgressVersions,
      final boolean optimistic) {
    final var bucketSize = bucket.size();

    if (bucketSize == 0) {
      if (optimistic) {
        throw OptimisticReadFailedException.INSTANCE;
      }
      return null;
    }

    int startIndex;
    if (foundIndex == Integer.MIN_VALUE) {
      // Continuation page in pinned path: start from index 0
      startIndex = 0;
    } else if (foundIndex >= 0) {
      // Exact match (rare: entry with version Long.MIN_VALUE exists).
      // Start directly from this entry.
      startIndex = foundIndex;
    } else {
      // Negative: insertion point. The first entry with the prefix (if any)
      // is at the insertion point index.
      startIndex = -foundIndex - 1;
      if (startIndex >= bucketSize) {
        // All entries on this page sort before the search key — no match here
        if (optimistic) {
          throw OptimisticReadFailedException.INSTANCE;
        }
        return null;
      }
    }

    // Extract the user-key prefix elements (all except the last version element)
    // once before the loop to avoid repeated getKeys() / UnmodifiableList wrapper
    // allocations per entry.
    final var searchPrefixKeys = ((CompositeKey) prefixKey).getKeys();
    final var prefixLen = searchPrefixKeys.size() - 1;

    // Scan forward from startIndex applying visibility
    for (var i = startIndex; i < bucketSize; i++) {
      final var entry = bucket.getEntry(i, keySerializer, serializerFactory);

      // Check if entry still matches the user-key prefix (all elements except
      // the last version element). If the prefix differs, we've passed the range.
      final var entryComposite = (CompositeKey) entry.key;
      if (!userKeyPrefixMatches(searchPrefixKeys, prefixLen, entryComposite)) {
        return null;
      }

      final var rid = entry.value;
      final var visibleRid = snapshot.checkVisibility(
          entryComposite, rid, snapshotTs, inProgressVersions);
      if (visibleRid != null) {
        return visibleRid;
      }
    }

    // Leaf exhausted without finding a visible entry
    if (optimistic) {
      // Versions may span to right sibling — fall through to pinned path
      throw OptimisticReadFailedException.INSTANCE;
    }
    return null;
  }

  /**
   * Checks whether the entry's CompositeKey shares the same user-key prefix as the
   * pre-extracted search key elements. The prefix is all elements except the last
   * (version) element.
   *
   * @param searchPrefixKeys the search key's element list (pre-extracted to avoid
   *     repeated {@code getKeys()} allocations in the scan loop)
   * @param prefixLen the number of user-key elements to compare
   *     ({@code searchPrefixKeys.size() - 1})
   * @param entryComposite the entry's CompositeKey to check against
   */
  private static boolean userKeyPrefixMatches(
      List<?> searchPrefixKeys, int prefixLen,
      CompositeKey entryComposite) {
    var entryKeys = entryComposite.getKeys();
    if (entryKeys.size() - 1 != prefixLen) {
      return false;
    }
    for (var i = 0; i < prefixLen; i++) {
      var cmp = DefaultComparator.INSTANCE.compare(
          searchPrefixKeys.get(i), entryKeys.get(i));
      if (cmp != 0) {
        return false;
      }
    }
    return true;
  }

  @Override
  public boolean put(@Nonnull final AtomicOperation atomicOperation,
      final K key, final RID value) {
    return update(atomicOperation, key, value, null) > 0;
  }

  @Override
  public int validatedPut(
      @Nonnull AtomicOperation atomicOperation,
      final K key,
      final RID value,
      final IndexEngineValidator<K, RID> validator) {
    return update(atomicOperation, key, value, validator);
  }

  private int update(
      @Nonnull final AtomicOperation atomicOperation,
      final K k,
      final RID rid,
      final IndexEngineValidator<K, RID> validator) {
    return calculateInsideComponentOperation(
        atomicOperation,
        operation -> {
          acquireExclusiveLock();
          try {
            var key = k;
            var value = rid;

            if (key != null) {
              key = keySerializer.preprocess(serializerFactory, key, (Object[]) keyTypes);
              final var serializedKey =
                  keySerializer.serializeNativeAsWhole(serializerFactory, key, (Object[]) keyTypes);
              if (maxKeySize > 0 && serializedKey.length > maxKeySize) {
                throw new TooBigIndexKeyException(storage.getName(),
                    "Key size is more than allowed, operation was canceled. Current key size "
                        + serializedKey.length
                        + ", allowed  "
                        + maxKeySize,
                    getName());
              }
              var bucketSearchResult =
                  findBucketForUpdate(key, serializedKey, atomicOperation);

              var keyBucketCacheEntry =
                  loadPageForWrite(
                      atomicOperation, fileId, bucketSearchResult.getLastPathItem(), true);
              var keyBucket =
                  new CellBTreeSingleValueBucketV3<K>(keyBucketCacheEntry);
              final byte[] oldRawValue;
              if (bucketSearchResult.getItemIndex() > -1) {
                oldRawValue =
                    keyBucket.getRawValue(bucketSearchResult.getItemIndex(), keySerializer,
                        serializerFactory);
              } else {
                oldRawValue = null;
              }
              final RID oldValue;
              if (oldRawValue == null) {
                oldValue = null;
              } else {
                final int collectionId = ShortSerializer.INSTANCE.deserializeNative(oldRawValue, 0);
                final var collectionPosition =
                    LongSerializer.deserializeNative(
                        oldRawValue, ShortSerializer.SHORT_SIZE);
                oldValue = CellBTreeSingleValueBucketV3.decodeRID(collectionId, collectionPosition);
              }

              if (validator != null) {
                var failure = true; // assuming validation throws by default
                var ignored = false;

                try {

                  final var result = validator.validate(key, oldValue, value);
                  if (result == IndexEngineValidator.IGNORE) {
                    ignored = true;
                    failure = false;
                    return -1;
                  }

                  value = (RID) result;
                  failure = false;
                } finally {
                  if (failure || ignored) {
                    keyBucketCacheEntry.close();
                  }
                }
              }

              final var serializedValue =
                  new byte[ShortSerializer.SHORT_SIZE + LongSerializer.LONG_SIZE];
              ShortSerializer.INSTANCE.serializeNative(
                  (short) value.getCollectionId(), serializedValue, 0);
              LongSerializer.serializeNative(
                  value.getCollectionPosition(), serializedValue, ShortSerializer.SHORT_SIZE);

              int insertionIndex;
              final int sizeDiff;
              if (bucketSearchResult.getItemIndex() >= 0) {
                // Key already exists — this is an update, not an insert.
                assert oldRawValue != null;

                if (oldRawValue.length == serializedValue.length) {
                  keyBucket.updateValue(
                      bucketSearchResult.getItemIndex(), serializedValue, serializedKey.length);
                  keyBucketCacheEntry.close();
                  return 0; // update, not insert
                } else {
                  keyBucket.removeLeafEntry(bucketSearchResult.getItemIndex(), serializedKey);
                  insertionIndex = bucketSearchResult.getItemIndex();
                  sizeDiff = 0;
                }
              } else {
                insertionIndex = -bucketSearchResult.getItemIndex() - 1;
                sizeDiff = 1;
              }

              while (!keyBucket.addLeafEntry(insertionIndex, serializedKey, serializedValue)) {
                bucketSearchResult =
                    splitBucket(
                        keyBucket,
                        keyBucketCacheEntry,
                        bucketSearchResult.getPath(),
                        bucketSearchResult.getInsertionIndexes(),
                        insertionIndex,
                        atomicOperation);

                insertionIndex = bucketSearchResult.getItemIndex();

                final var pageIndex = bucketSearchResult.getLastPathItem();

                if (pageIndex != keyBucketCacheEntry.getPageIndex()) {
                  keyBucketCacheEntry.close();

                  keyBucketCacheEntry = loadPageForWrite(atomicOperation, fileId, pageIndex, true);
                }

                //noinspection ObjectAllocationInLoop
                keyBucket = new CellBTreeSingleValueBucketV3<>(keyBucketCacheEntry);
              }

              keyBucketCacheEntry.close();

              if (sizeDiff != 0) {
                updateSize(sizeDiff, atomicOperation);
              }
              // sizeDiff == 1 means a new key was inserted;
              // sizeDiff == 0 means a value was updated (remove + re-insert).
              return sizeDiff;
            } else {
              var sizeDiff = 0;
              final RID oldValue;
              try (final var cacheEntry =
                  loadPageForWrite(atomicOperation, nullBucketFileId, 0, true)) {
                final var nullBucket =
                    new CellBTreeSingleValueV3NullBucket(cacheEntry);
                oldValue = nullBucket.getValue();

                if (validator != null) {
                  final var result = validator.validate(null, oldValue, value);
                  if (result == IndexEngineValidator.IGNORE) {
                    return -1;
                  }
                }

                if (oldValue != null) {
                  sizeDiff = -1;
                }
                nullBucket.setValue(value);
              }
              sizeDiff++;
              updateSize(sizeDiff, atomicOperation);
              // sizeDiff == 1 means null key is new; 0 means null key updated.
              return sizeDiff;
            }
          } finally {
            releaseExclusiveLock();
          }
        });
  }

  @Override
  public void close() {
    acquireExclusiveLock();
    try {
      readCache.closeFile(fileId, true, writeCache);
      readCache.closeFile(nullBucketFileId, true, writeCache);
    } finally {
      releaseExclusiveLock();
    }
  }

  @Override
  public void delete(@Nonnull final AtomicOperation atomicOperation) {
    executeInsideComponentOperation(
        atomicOperation,
        operation -> {
          acquireExclusiveLock();
          try {
            deleteFile(atomicOperation, fileId);
            deleteFile(atomicOperation, nullBucketFileId);
          } finally {
            releaseExclusiveLock();
          }
        });
  }

  @Override
  public void load(
      final String name,
      final int keySize,
      final PropertyTypeInternal[] keyTypes,
      final BinarySerializer<K> keySerializer, @Nonnull AtomicOperation atomicOperation) {
    acquireExclusiveLock();
    try {
      fileId = openFile(atomicOperation, getFullName());
      nullBucketFileId = openFile(atomicOperation, name + nullFileExtension);

      this.keySize = keySize;
      this.maxKeySize =
          GlobalConfiguration.BTREE_MAX_KEY_SIZE.getValueAsInteger();
      this.keyTypes = keyTypes;
      this.keySerializer = keySerializer;
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new CellBTreeSingleValueV3Exception("Exception during loading of sbtree " + name, this),
          e, storage.getName());
    } finally {
      releaseExclusiveLock();
    }
  }

  @Override
  public long size(@Nonnull AtomicOperation atomicOperation) {
    try {
      return executeOptimisticStorageRead(
          atomicOperation,
          () -> {
            final var pageView =
                loadPageOptimistic(atomicOperation, fileId, ENTRY_POINT_INDEX);
            final var entryPoint =
                new CellBTreeSingleValueEntryPointV3<K>(pageView);
            return entryPoint.getTreeSize();
          },
          () -> {
            try (final var entryPointCacheEntry =
                loadPageForRead(atomicOperation, fileId, ENTRY_POINT_INDEX)) {
              final var entryPoint =
                  new CellBTreeSingleValueEntryPointV3<K>(entryPointCacheEntry);
              return entryPoint.getTreeSize();
            }
          });
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new CellBTreeSingleValueV3Exception(
              "Error during retrieving of size of index " + getName(), this),
          e, storage.getName());
    }
  }

  @Override
  public long getApproximateEntriesCount(@Nonnull AtomicOperation atomicOperation) {
    try {
      return executeOptimisticStorageRead(
          atomicOperation,
          () -> {
            final var pageView =
                loadPageOptimistic(atomicOperation, fileId, ENTRY_POINT_INDEX);
            final var entryPoint =
                new CellBTreeSingleValueEntryPointV3<K>(pageView);
            return entryPoint.getApproximateEntriesCount();
          },
          () -> {
            try (final var entryPointCacheEntry =
                loadPageForRead(atomicOperation, fileId, ENTRY_POINT_INDEX)) {
              final var entryPoint =
                  new CellBTreeSingleValueEntryPointV3<K>(entryPointCacheEntry);
              return entryPoint.getApproximateEntriesCount();
            }
          });
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new CellBTreeSingleValueV3Exception(
              "Error during retrieving approximate entries count of index "
                  + getName(),
              this),
          e, storage.getName());
    }
  }

  @Override
  public void setApproximateEntriesCount(
      @Nonnull AtomicOperation atomicOperation, long count) {
    assert count >= 0
        : "setApproximateEntriesCount called with negative count: " + count;
    executeInsideComponentOperation(
        atomicOperation,
        operation -> {
          try (final var entryPointCacheEntry =
              loadPageForWrite(
                  atomicOperation, fileId, ENTRY_POINT_INDEX, true)) {
            final var entryPoint =
                new CellBTreeSingleValueEntryPointV3<K>(entryPointCacheEntry);
            entryPoint.setApproximateEntriesCount(count);
          }
        });
  }

  @Override
  public void addToApproximateEntriesCount(
      @Nonnull AtomicOperation atomicOperation, long delta) {
    executeInsideComponentOperation(
        atomicOperation,
        operation -> {
          try (final var entryPointCacheEntry =
              loadPageForWrite(
                  atomicOperation, fileId, ENTRY_POINT_INDEX, true)) {
            final var entryPoint =
                new CellBTreeSingleValueEntryPointV3<K>(entryPointCacheEntry);
            long updated = entryPoint.getApproximateEntriesCount() + delta;
            assert updated >= 0
                : "approximateEntriesCount underflow: current="
                    + entryPoint.getApproximateEntriesCount()
                    + " delta=" + delta;
            entryPoint.setApproximateEntriesCount(updated);
          }
        });
  }

  @Override
  public RID remove(@Nonnull final AtomicOperation atomicOperation, final K key) {
    return calculateInsideComponentOperation(
        atomicOperation,
        operation -> {
          acquireExclusiveLock();
          try {
            if (key != null) {
              final RID removedValue;

              final var bucketSearchResult =
                  findBucketForRemove(key, atomicOperation);
              if (bucketSearchResult.isPresent()) {
                final var removeSearchResult = bucketSearchResult.get();

                final byte[] rawValue;
                try (final var keyBucketCacheEntry =
                    loadPageForWrite(
                        atomicOperation, fileId, removeSearchResult.getLeafPageIndex(), true)) {
                  final var keyBucket =
                      new CellBTreeSingleValueBucketV3<K>(keyBucketCacheEntry);
                  rawValue =
                      keyBucket.getRawValue(
                          removeSearchResult.getLeafEntryPageIndex(), keySerializer,
                          serializerFactory);
                  final var serializedKey =
                      keyBucket.getRawKey(
                          removeSearchResult.getLeafEntryPageIndex(), keySerializer,
                          serializerFactory);
                  keyBucket.removeLeafEntry(
                      removeSearchResult.getLeafEntryPageIndex(), serializedKey);
                  updateSize(-1, atomicOperation);

                  final int collectionId = ShortSerializer.INSTANCE.deserializeNative(rawValue, 0);
                  final var collectionPosition =
                      LongSerializer.deserializeNative(
                          rawValue, ShortSerializer.SHORT_SIZE);

                  removedValue =
                      CellBTreeSingleValueBucketV3.decodeRID(collectionId, collectionPosition);
                }
              } else {
                return null;
              }

              return removedValue;
            } else {
              return removeNullBucket(atomicOperation);
            }
          } finally {
            releaseExclusiveLock();
          }
        });
  }

  /**
   * Balance leaf node after item deletion. If the node reaches the minimal size, it will be merged
   * with its sibling. If the sibling is also too small, the parent node will be merged as well. It
   * is planned to be used in the future in periodic B-Tree GC.
   */
  @SuppressWarnings("unused")
  private boolean balanceLeafNodeAfterItemDelete(
      final AtomicOperation atomicOperation,
      final RemoveSearchResult removeSearchResult,
      final CellBTreeSingleValueBucketV3<K> keyBucket)
      throws IOException {
    final var parentItem =
        removeSearchResult.getPath().getLast();

    try (final var parentCacheEntry =
        loadPageForWrite(atomicOperation, fileId, parentItem.getPageIndex(), true)) {
      final var parentBucket =
          new CellBTreeSingleValueBucketV3<K>(parentCacheEntry);

      if (parentItem.isLeftChild()) {
        final var rightSiblingPageIndex = parentBucket.getRight(parentItem.getIndexInsidePage());

        // merge with left sibling
        try (final var rightSiblingEntry =
            loadPageForWrite(atomicOperation, fileId, rightSiblingPageIndex, true)) {
          final var rightSiblingBucket =
              new CellBTreeSingleValueBucketV3<K>(rightSiblingEntry);
          final var success =
              deleteFromNonLeafNode(atomicOperation, parentBucket, removeSearchResult.getPath());

          if (success) {
            final var leftSiblingIndex = keyBucket.getLeftSibling();
            assert rightSiblingBucket.getLeftSibling() == keyBucket.getCacheEntry().getPageIndex();

            rightSiblingBucket.setLeftSibling(leftSiblingIndex);

            if (leftSiblingIndex > 0) {
              try (final var leftSiblingEntry =
                  loadPageForWrite(atomicOperation, fileId, leftSiblingIndex, true)) {
                final var leftSiblingBucket =
                    new CellBTreeSingleValueBucketV3<K>(leftSiblingEntry);

                leftSiblingBucket.setRightSibling(rightSiblingPageIndex);
              }
            }

            return true;
          }

          return false;
        }
      }

      final var leftSiblingPageIndex = parentBucket.getLeft(parentItem.getIndexInsidePage());
      try (final var leftSiblingEntry =
          loadPageForWrite(atomicOperation, fileId, leftSiblingPageIndex, true)) {
        // merge with right sibling
        final var leftSiblingBucket =
            new CellBTreeSingleValueBucketV3<K>(leftSiblingEntry);
        final var success =
            deleteFromNonLeafNode(atomicOperation, parentBucket, removeSearchResult.getPath());

        if (success) {
          final var rightSiblingIndex = keyBucket.getRightSibling();

          assert leftSiblingBucket.getRightSibling() == keyBucket.getCacheEntry().getPageIndex();
          leftSiblingBucket.setRightSibling(rightSiblingIndex);

          if (rightSiblingIndex > 0) {
            try (final var rightSiblingEntry =
                loadPageForWrite(atomicOperation, fileId, rightSiblingIndex, true)) {
              final var rightSibling =
                  new CellBTreeSingleValueBucketV3<K>(rightSiblingEntry);
              rightSibling.setLeftSibling(leftSiblingPageIndex);
            }
          }

          return true;
        }

        return false;
      }
    }
  }

  private boolean deleteFromNonLeafNode(
      final AtomicOperation atomicOperation,
      final CellBTreeSingleValueBucketV3<K> bucket,
      final List<RemovalPathItem> path)
      throws IOException {
    final var bucketSize = bucket.size();
    assert bucketSize > 0;

    // currently processed node is a root node
    if (path.size() == 1) {
      if (bucketSize == 1) {
        return false;
      }
    }

    final var currentItem = path.getLast();

    if (bucketSize > 1) {
      bucket.removeNonLeafEntry(
          currentItem.getIndexInsidePage(), currentItem.isLeftChild(), keySerializer,
          serializerFactory);

      return true;
    }

    final var parentItem = path.get(path.size() - 2);

    try (final var parentCacheEntry =
        loadPageForWrite(atomicOperation, fileId, parentItem.getPageIndex(), true)) {
      final var parentBucket =
          new CellBTreeSingleValueBucketV3<K>(parentCacheEntry);

      final int orphanPointer;
      if (currentItem.isLeftChild()) {
        orphanPointer = bucket.getRight(currentItem.getIndexInsidePage());
      } else {
        orphanPointer = bucket.getLeft(currentItem.getIndexInsidePage());
      }

      if (parentItem.isLeftChild()) {
        final var rightSiblingPageIndex = parentBucket.getRight(parentItem.getIndexInsidePage());
        try (final var rightSiblingEntry =
            loadPageForWrite(atomicOperation, fileId, rightSiblingPageIndex, true)) {
          final var rightSiblingBucket =
              new CellBTreeSingleValueBucketV3<K>(rightSiblingEntry);

          final var rightSiblingBucketSize = rightSiblingBucket.size();
          if (rightSiblingBucketSize > 1) {
            return rotateNoneLeafLeftAndRemoveItem(
                parentItem, parentBucket, bucket, rightSiblingBucket, orphanPointer);
          } else if (rightSiblingBucketSize == 1) {
            return mergeNoneLeafWithRightSiblingAndRemoveItem(
                atomicOperation,
                parentItem,
                parentBucket,
                bucket,
                rightSiblingBucket,
                orphanPointer,
                path);
          }

          return false;
        }
      } else {
        final var leftSiblingPageIndex = parentBucket.getLeft(parentItem.getIndexInsidePage());
        try (final var leftSiblingEntry =
            loadPageForWrite(atomicOperation, fileId, leftSiblingPageIndex, true)) {
          final var leftSiblingBucket =
              new CellBTreeSingleValueBucketV3<K>(leftSiblingEntry);
          assert !leftSiblingBucket.isEmpty();

          final var leftSiblingBucketSize = leftSiblingBucket.size();
          if (leftSiblingBucketSize > 1) {
            return rotateNoneLeafRightAndRemoveItem(
                parentItem, parentBucket, bucket, leftSiblingBucket, orphanPointer);
          } else if (leftSiblingBucketSize == 1) {
            return mergeNoneLeafWithLeftSiblingAndRemoveItem(
                atomicOperation,
                parentItem,
                parentBucket,
                bucket,
                leftSiblingBucket,
                orphanPointer,
                path);
          }

          return false;
        }
      }
    }
  }

  private boolean rotateNoneLeafRightAndRemoveItem(
      final RemovalPathItem parentItem,
      final CellBTreeSingleValueBucketV3<K> parentBucket,
      final CellBTreeSingleValueBucketV3<K> bucket,
      final CellBTreeSingleValueBucketV3<K> leftSibling,
      final int orhanPointer) {
    if (bucket.size() != 1 || leftSibling.size() <= 1) {
      throw new CellBTreeSingleValueV3Exception("BTree is broken", this);
    }

    final var bucketKey = parentBucket.getRawKey(parentItem.getIndexInsidePage(), keySerializer,
        serializerFactory);
    final var leftSiblingSize = leftSibling.size();

    final var separatorKey = leftSibling.getRawKey(leftSiblingSize - 1, keySerializer,
        serializerFactory);

    if (!parentBucket.updateKey(parentItem.getIndexInsidePage(), separatorKey, keySerializer,
        serializerFactory)) {
      return false;
    }

    final var bucketLeft = leftSibling.getRight(leftSiblingSize - 1);

    leftSibling.removeNonLeafEntry(leftSiblingSize - 1, false, keySerializer, serializerFactory);

    bucket.removeNonLeafEntry(0, true, keySerializer, serializerFactory);

    final var result = bucket.addNonLeafEntry(0, bucketLeft, orhanPointer, bucketKey);
    assert result;

    return true;
  }

  private boolean rotateNoneLeafLeftAndRemoveItem(
      final RemovalPathItem parentItem,
      final CellBTreeSingleValueBucketV3<K> parentBucket,
      final CellBTreeSingleValueBucketV3<K> bucket,
      final CellBTreeSingleValueBucketV3<K> rightSibling,
      final int orphanPointer) {
    if (bucket.size() != 1 || rightSibling.size() <= 1) {
      throw new CellBTreeSingleValueV3Exception("BTree is broken", this);
    }

    final var bucketKey = parentBucket.getRawKey(parentItem.getIndexInsidePage(), keySerializer,
        serializerFactory);

    final var separatorKey = rightSibling.getRawKey(0, keySerializer, serializerFactory);

    if (!parentBucket.updateKey(parentItem.getIndexInsidePage(), separatorKey, keySerializer,
        serializerFactory)) {
      return false;
    }

    final var bucketRight = rightSibling.getLeft(0);
    bucket.removeNonLeafEntry(0, true, keySerializer, serializerFactory);

    final var result = bucket.addNonLeafEntry(0, orphanPointer, bucketRight, bucketKey);
    assert result;

    rightSibling.removeNonLeafEntry(0, true, keySerializer, serializerFactory);

    return true;
  }

  private boolean mergeNoneLeafWithRightSiblingAndRemoveItem(
      final AtomicOperation atomicOperation,
      final RemovalPathItem parentItem,
      final CellBTreeSingleValueBucketV3<K> parentBucket,
      final CellBTreeSingleValueBucketV3<K> bucket,
      final CellBTreeSingleValueBucketV3<K> rightSibling,
      final int orphanPointer,
      final List<RemovalPathItem> path)
      throws IOException {

    if (rightSibling.size() != 1 || bucket.size() != 1) {
      throw new CellBTreeSingleValueV3Exception("BTree is broken", this);
    }

    final var key = parentBucket.getRawKey(parentItem.getIndexInsidePage(), keySerializer,
        serializerFactory);
    final var success =
        deleteFromNonLeafNode(atomicOperation, parentBucket, path.subList(0, path.size() - 1));

    if (success) {
      final var rightChild = rightSibling.getLeft(0);

      final var result = rightSibling.addNonLeafEntry(0, orphanPointer, rightChild, key);
      assert result;

      addToFreeList(atomicOperation, bucket.getCacheEntry().getPageIndex());

      return true;
    }

    return false;
  }

  private boolean mergeNoneLeafWithLeftSiblingAndRemoveItem(
      final AtomicOperation atomicOperation,
      final RemovalPathItem parentItem,
      final CellBTreeSingleValueBucketV3<K> parentBucket,
      final CellBTreeSingleValueBucketV3<K> bucket,
      final CellBTreeSingleValueBucketV3<K> leftSibling,
      final int orphanPointer,
      final List<RemovalPathItem> path)
      throws IOException {

    if (leftSibling.size() != 1 || bucket.size() != 1) {
      throw new CellBTreeSingleValueV3Exception("BTree is broken", this);
    }

    final var key = parentBucket.getRawKey(parentItem.getIndexInsidePage(), keySerializer,
        serializerFactory);

    final var success =
        deleteFromNonLeafNode(atomicOperation, parentBucket, path.subList(0, path.size() - 1));

    if (success) {
      final var leftChild = leftSibling.getRight(0);
      final var result = leftSibling.addNonLeafEntry(1, leftChild, orphanPointer, key);
      assert result;

      addToFreeList(atomicOperation, bucket.getCacheEntry().getPageIndex());

      return true;
    }

    return false;
  }

  private void removePagesStoredInFreeList(
      AtomicOperation atomicOperation, IntOpenHashSet pages, int filledUpTo) throws IOException {
    final int freeListHead;

    try (final var entryCacheEntry =
        loadPageForRead(atomicOperation, fileId, ENTRY_POINT_INDEX)) {
      final var entryPoint =
          new CellBTreeSingleValueEntryPointV3<K>(entryCacheEntry);
      assert entryPoint.getPagesSize() == filledUpTo - 1;
      freeListHead = entryPoint.getFreeListHead();
    }

    var freePageIndex = freeListHead;
    while (freePageIndex >= 0) {
      pages.remove(freePageIndex);

      try (final var cacheEntry = loadPageForRead(atomicOperation, fileId, freePageIndex)) {
        final var bucket =
            new CellBTreeSingleValueBucketV3<K>(cacheEntry);
        freePageIndex = bucket.getNextFreeListPage();
        pages.remove(freePageIndex);
      }
    }
  }

  void assertFreePages(AtomicOperation atomicOperation) {
    try {
      executeOptimisticStorageRead(
          atomicOperation,
          () -> doAssertFreePages(atomicOperation),
          () -> doAssertFreePages(atomicOperation));
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new CellBTreeSingleValueV3Exception(
              "Error during checking  of btree with name " + getName(), this),
          e, storage.getName());
    }
  }

  private void doAssertFreePages(AtomicOperation atomicOperation) throws IOException {
    final var pages = new IntOpenHashSet();
    final var filledUpTo = (int) getFilledUpTo(atomicOperation, fileId);

    for (var i = 2; i < filledUpTo; i++) {
      pages.add(i);
    }

    removeUsedPages((int) ROOT_INDEX, pages, atomicOperation);
    removePagesStoredInFreeList(atomicOperation, pages, filledUpTo);

    assert pages.isEmpty();
  }

  private void removeUsedPages(
      final int pageIndex, final IntOpenHashSet pages, final AtomicOperation atomicOperation)
      throws IOException {

    try (final var cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex)) {
      final var bucket = new CellBTreeSingleValueBucketV3<K>(cacheEntry);
      if (bucket.isLeaf()) {
        return;
      }

      final var bucketSize = bucket.size();
      final var pagesToExplore = new IntArrayList(bucketSize);

      if (bucketSize > 0) {
        final var leftPage = bucket.getLeft(0);
        pages.remove(leftPage);
        pagesToExplore.add(leftPage);

        for (var i = 0; i < bucketSize; i++) {
          final var rightPage = bucket.getRight(i);
          pages.remove(rightPage);
          pagesToExplore.add(rightPage);
        }
      }

      for (var i = 0; i < pagesToExplore.size(); i++) {
        var pageToExplore = pagesToExplore.getInt(i);
        removeUsedPages(pageToExplore, pages, atomicOperation);
      }
    }
  }

  private void addToFreeList(AtomicOperation atomicOperation, int pageIndex) throws IOException {
    try (final var cacheEntry =
        loadPageForWrite(atomicOperation, fileId, pageIndex, true)) {
      final var bucket = new CellBTreeSingleValueBucketV3<K>(cacheEntry);

      try (final var entryPointEntry =
          loadPageForWrite(atomicOperation, fileId, ENTRY_POINT_INDEX, true)) {

        final var entryPoint =
            new CellBTreeSingleValueEntryPointV3<K>(entryPointEntry);

        final var freeListHead = entryPoint.getFreeListHead();
        entryPoint.setFreeListHead(pageIndex);
        bucket.setNextFreeListPage(freeListHead);
      }
    }
  }

  private RID removeNullBucket(final AtomicOperation atomicOperation) throws IOException {
    RID removedValue;
    try (final var nullCacheEntry =
        loadPageForWrite(atomicOperation, nullBucketFileId, 0, true)) {
      final var nullBucket =
          new CellBTreeSingleValueV3NullBucket(nullCacheEntry);
      removedValue = nullBucket.getValue();

      if (removedValue != null) {
        nullBucket.removeValue();
      }
    }

    if (removedValue != null) {
      updateSize(-1, atomicOperation);
    }
    return removedValue;
  }

  @Override
  public Stream<RawPair<K, RID>> iterateEntriesMinor(
      final K key, final boolean inclusive, final boolean ascSortOrder,
      @Nonnull AtomicOperation atomicOperation) {
    try {
      return executeOptimisticStorageRead(
          atomicOperation,
          () -> doIterateEntriesMinor(key, inclusive, ascSortOrder, atomicOperation),
          () -> doIterateEntriesMinor(key, inclusive, ascSortOrder, atomicOperation));
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new CellBTreeSingleValueV3Exception(
              "Error during iteration of sbtree with name " + getName(), this),
          e, storage.getName());
    }
  }

  private Stream<RawPair<K, RID>> doIterateEntriesMinor(
      final K key, final boolean inclusive, final boolean ascSortOrder,
      @Nonnull AtomicOperation atomicOperation) {
    if (!ascSortOrder) {
      return StreamSupport.stream(
          iterateEntriesMinorDesc(key, inclusive, atomicOperation), false);
    }
    return StreamSupport.stream(
        iterateEntriesMinorAsc(key, inclusive, atomicOperation), false);
  }

  @Override
  public Stream<RawPair<K, RID>> iterateEntriesMajor(
      final K key, final boolean inclusive, final boolean ascSortOrder,
      @Nonnull AtomicOperation atomicOperation) {
    try {
      return executeOptimisticStorageRead(
          atomicOperation,
          () -> doIterateEntriesMajor(key, inclusive, ascSortOrder, atomicOperation),
          () -> doIterateEntriesMajor(key, inclusive, ascSortOrder, atomicOperation));
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new CellBTreeSingleValueV3Exception(
              "Error during iteration of sbtree with name " + getName(), this),
          e, storage.getName());
    }
  }

  private Stream<RawPair<K, RID>> doIterateEntriesMajor(
      final K key, final boolean inclusive, final boolean ascSortOrder,
      @Nonnull AtomicOperation atomicOperation) {
    if (ascSortOrder) {
      return StreamSupport.stream(
          iterateEntriesMajorAsc(key, inclusive, atomicOperation), false);
    }
    return StreamSupport.stream(
        iterateEntriesMajorDesc(key, inclusive, atomicOperation), false);
  }

  @Override
  @Nullable public K firstKey(@Nonnull AtomicOperation atomicOperation) {
    try {
      return executeOptimisticStorageRead(
          atomicOperation,
          () -> doFirstKey(atomicOperation),
          () -> doFirstKey(atomicOperation));
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new CellBTreeSingleValueV3Exception(
              "Error during finding first key in sbtree [" + getName() + "]", this),
          e, storage.getName());
    }
  }

  @Nullable private K doFirstKey(@Nonnull AtomicOperation atomicOperation) throws IOException {
    final var searchResult = firstItem(atomicOperation);
    if (searchResult.isEmpty()) {
      return null;
    }

    final var result = searchResult.get();

    try (final var cacheEntry =
        loadPageForRead(atomicOperation, fileId, result.getPageIndex())) {
      final var bucket =
          new CellBTreeSingleValueBucketV3<K>(cacheEntry);
      return bucket.getKey(
          result.getItemIndex(), keySerializer, serializerFactory);
    }
  }

  @Override
  @Nullable public K lastKey(@Nonnull AtomicOperation atomicOperation) {
    try {
      return executeOptimisticStorageRead(
          atomicOperation,
          () -> doLastKey(atomicOperation),
          () -> doLastKey(atomicOperation));
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new CellBTreeSingleValueV3Exception(
              "Error during finding last key in sbtree [" + getName() + "]", this),
          e, storage.getName());
    }
  }

  @Nullable private K doLastKey(@Nonnull AtomicOperation atomicOperation) throws IOException {
    final var searchResult = lastItem(atomicOperation);
    if (searchResult.isEmpty()) {
      return null;
    }

    final var result = searchResult.get();
    try (final var cacheEntry =
        loadPageForRead(atomicOperation, fileId, result.getPageIndex())) {
      final var bucket =
          new CellBTreeSingleValueBucketV3<K>(cacheEntry);
      return bucket.getKey(
          result.getItemIndex(), keySerializer, serializerFactory);
    }
  }

  @Override
  public Stream<K> keyStream(@Nonnull AtomicOperation atomicOperation) {
    try {
      return executeOptimisticStorageRead(
          atomicOperation,
          () -> doKeyStream(atomicOperation),
          () -> doKeyStream(atomicOperation));
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new CellBTreeSingleValueV3Exception(
              "Error during key stream of sbtree with name " + getName(), this),
          e, storage.getName());
    }
  }

  private Stream<K> doKeyStream(@Nonnull AtomicOperation atomicOperation) {
    return StreamSupport.stream(
        new SpliteratorForward<>(this, null, null,
            false, false, atomicOperation),
        false)
        .map(RawPair::first);
  }

  @Override
  public Stream<RawPair<K, RID>> allEntries(@Nonnull AtomicOperation atomicOperation) {
    try {
      return executeOptimisticStorageRead(
          atomicOperation,
          () -> doAllEntries(atomicOperation),
          () -> doAllEntries(atomicOperation));
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new CellBTreeSingleValueV3Exception(
              "Error during iteration of sbtree with name " + getName(), this),
          e, storage.getName());
    }
  }

  private Stream<RawPair<K, RID>> doAllEntries(@Nonnull AtomicOperation atomicOperation) {
    return StreamSupport.stream(
        new SpliteratorForward<>(this, null, null, false,
            false, atomicOperation),
        false);
  }

  @Override
  public Stream<RawPair<K, RID>> iterateEntriesBetween(
      final K keyFrom,
      final boolean fromInclusive,
      final K keyTo,
      final boolean toInclusive,
      final boolean ascSortOrder, @Nonnull AtomicOperation atomicOperation) {
    try {
      return executeOptimisticStorageRead(
          atomicOperation,
          () -> doIterateEntriesBetween(keyFrom, fromInclusive, keyTo, toInclusive,
              ascSortOrder, atomicOperation),
          () -> doIterateEntriesBetween(keyFrom, fromInclusive, keyTo, toInclusive,
              ascSortOrder, atomicOperation));
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new CellBTreeSingleValueV3Exception(
              "Error during iteration of sbtree with name " + getName(), this),
          e, storage.getName());
    }
  }

  private Stream<RawPair<K, RID>> doIterateEntriesBetween(
      final K keyFrom, final boolean fromInclusive,
      final K keyTo, final boolean toInclusive,
      final boolean ascSortOrder, @Nonnull AtomicOperation atomicOperation) {
    if (ascSortOrder) {
      return StreamSupport.stream(
          iterateEntriesBetweenAscOrder(keyFrom, fromInclusive, keyTo, toInclusive,
              atomicOperation),
          false);
    } else {
      return StreamSupport.stream(
          iterateEntriesBetweenDescOrder(keyFrom, fromInclusive, keyTo, toInclusive,
              atomicOperation),
          false);
    }
  }

  /**
   * Acquires exclusive lock in the active atomic operation running on the current thread for this
   * SB-tree.
   */
  @Override
  public void acquireAtomicExclusiveLock(@Nonnull AtomicOperation atomicOperation) {
    atomicOperationsManager.acquireExclusiveLockTillOperationComplete(atomicOperation, this);
  }

  private void updateSize(final long diffSize, final AtomicOperation atomicOperation)
      throws IOException {
    try (final var entryPointCacheEntry =
        loadPageForWrite(atomicOperation, fileId, ENTRY_POINT_INDEX, true)) {
      final var entryPoint =
          new CellBTreeSingleValueEntryPointV3<K>(entryPointCacheEntry);
      entryPoint.setTreeSize(entryPoint.getTreeSize() + diffSize);
    }
  }

  private Spliterator<RawPair<K, RID>> iterateEntriesMinorDesc(K key, final boolean inclusive,
      AtomicOperation atomicOperation) {
    key = keySerializer.preprocess(serializerFactory, key, (Object[]) keyTypes);
    key = enhanceCompositeKeyMinorDesc(key, inclusive);

    return new SpliteratorBackward<>(this, null, key, false, inclusive, atomicOperation);
  }

  private Spliterator<RawPair<K, RID>> iterateEntriesMinorAsc(K key, final boolean inclusive,
      AtomicOperation atomicOperation) {
    key = keySerializer.preprocess(serializerFactory, key, (Object[]) keyTypes);
    key = enhanceCompositeKeyMinorAsc(key, inclusive);

    return new SpliteratorForward<>(this, null, key, false,
        inclusive, atomicOperation);
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

  private Spliterator<RawPair<K, RID>> iterateEntriesMajorAsc(K key, final boolean inclusive,
      AtomicOperation atomicOperation) {
    key = keySerializer.preprocess(serializerFactory, key, (Object[]) keyTypes);
    key = enhanceCompositeKeyMajorAsc(key, inclusive);

    return new SpliteratorForward<>(this, key, null, inclusive, false,
        atomicOperation);
  }

  private Spliterator<RawPair<K, RID>> iterateEntriesMajorDesc(K key, final boolean inclusive,
      AtomicOperation atomicOperation) {
    key = keySerializer.preprocess(serializerFactory, key, (Object[]) keyTypes);
    key = enhanceCompositeKeyMajorDesc(key, inclusive);

    return new SpliteratorBackward<>(this, key, null, inclusive, false,
        atomicOperation);
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
      var bucket = new CellBTreeSingleValueBucketV3<K>(cacheEntry);

      while (true) {
        if (!bucket.isLeaf()) {
          if (bucket.isEmpty() || itemIndex > bucket.size()) {
            if (!path.isEmpty()) {
              final var pagePathItemUnit = path.removeLast();

              bucketIndex = pagePathItemUnit.getPageIndex();
              itemIndex = pagePathItemUnit.getItemIndex() + 1;
            } else {
              return Optional.empty();
            }
          } else {
            //noinspection ObjectAllocationInLoop
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

              bucketIndex = pagePathItemUnit.getPageIndex();
              itemIndex = pagePathItemUnit.getItemIndex() + 1;
            } else {
              return Optional.empty();
            }
          } else {
            return Optional.of(new BucketSearchResult(0, bucketIndex));
          }
        }

        cacheEntry.close();

        cacheEntry = loadPageForRead(atomicOperation, fileId, bucketIndex);
        //noinspection ObjectAllocationInLoop
        bucket = new CellBTreeSingleValueBucketV3<>(cacheEntry);
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

    var bucket = new CellBTreeSingleValueBucketV3<K>(cacheEntry);

    var itemIndex = bucket.size() - 1;
    try {
      while (true) {
        if (!bucket.isLeaf()) {
          if (itemIndex < -1) {
            if (!path.isEmpty()) {
              final var pagePathItemUnit = path.removeLast();

              bucketIndex = pagePathItemUnit.getPageIndex();
              itemIndex = pagePathItemUnit.getItemIndex() - 1;
            } else {
              return Optional.empty();
            }
          } else {
            //noinspection ObjectAllocationInLoop
            path.add(new PagePathItemUnit(bucketIndex, itemIndex));

            if (itemIndex > -1) {
              bucketIndex = bucket.getRight(itemIndex);
            } else {
              bucketIndex = bucket.getLeft(0);
            }

            itemIndex = CellBTreeSingleValueBucketV3.MAX_PAGE_SIZE_BYTES + 1;
          }
        } else {
          if (bucket.isEmpty()) {
            if (!path.isEmpty()) {
              final var pagePathItemUnit = path.removeLast();

              bucketIndex = pagePathItemUnit.getPageIndex();
              itemIndex = pagePathItemUnit.getItemIndex() - 1;
            } else {
              return Optional.empty();
            }
          } else {
            return Optional.of(new BucketSearchResult(bucket.size() - 1, bucketIndex));
          }
        }

        cacheEntry.close();

        cacheEntry = loadPageForRead(atomicOperation, fileId, bucketIndex);

        //noinspection ObjectAllocationInLoop
        bucket = new CellBTreeSingleValueBucketV3<>(cacheEntry);
        if (itemIndex == CellBTreeSingleValueBucketV3.MAX_PAGE_SIZE_BYTES + 1) {
          itemIndex = bucket.size() - 1;
        }
      }
    } finally {
      cacheEntry.close();
    }
  }

  private Spliterator<RawPair<K, RID>> iterateEntriesBetweenAscOrder(
      K keyFrom, final boolean fromInclusive, K keyTo, final boolean toInclusive,
      AtomicOperation atomicOperation) {
    keyFrom = keySerializer.preprocess(serializerFactory, keyFrom, (Object[]) keyTypes);
    keyTo = keySerializer.preprocess(serializerFactory, keyTo, (Object[]) keyTypes);

    keyFrom = enhanceFromCompositeKeyBetweenAsc(keyFrom, fromInclusive);
    keyTo = enhanceToCompositeKeyBetweenAsc(keyTo, toInclusive);

    return new SpliteratorForward<>(this, keyFrom, keyTo, fromInclusive, toInclusive,
        atomicOperation);
  }

  private Spliterator<RawPair<K, RID>> iterateEntriesBetweenDescOrder(
      K keyFrom, final boolean fromInclusive, K keyTo, final boolean toInclusive,
      AtomicOperation atomicOperation) {
    keyFrom = keySerializer.preprocess(serializerFactory, keyFrom, (Object[]) keyTypes);
    keyTo = keySerializer.preprocess(serializerFactory, keyTo, (Object[]) keyTypes);

    keyFrom = enhanceFromCompositeKeyBetweenDesc(keyFrom, fromInclusive);
    keyTo = enhanceToCompositeKeyBetweenDesc(keyTo, toInclusive);

    return new SpliteratorBackward<>(this, keyFrom, keyTo, fromInclusive, toInclusive,
        atomicOperation);
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
      final CellBTreeSingleValueBucketV3<K> bucketToSplit,
      final CacheEntry entryToSplit,
      final LongList path,
      final IntList itemPointers,
      final int keyIndex,
      final AtomicOperation atomicOperation)
      throws IOException {
    final var splitLeaf = bucketToSplit.isLeaf();
    final var bucketSize = bucketToSplit.size();

    final var indexToSplit = bucketSize >>> 1;
    final var separationKey = bucketToSplit.getKey(indexToSplit, keySerializer, serializerFactory);
    final List<byte[]> rightEntries = new ArrayList<>(indexToSplit);

    final var startRightIndex = splitLeaf ? indexToSplit : indexToSplit + 1;
    if (startRightIndex == 0) {
      throw new CellBTreeSingleValueV3Exception("Left part of bucket is empty", this);
    }

    for (var i = startRightIndex; i < bucketSize; i++) {
      rightEntries.add(bucketToSplit.getRawEntry(i, keySerializer, serializerFactory));
    }

    if (rightEntries.isEmpty()) {
      throw new CellBTreeSingleValueV3Exception("Right part of bucket is empty", this);
    }

    if (entryToSplit.getPageIndex() != ROOT_INDEX) {
      return splitNonRootBucket(
          path,
          itemPointers,
          keyIndex,
          entryToSplit.getPageIndex(),
          bucketToSplit,
          splitLeaf,
          indexToSplit,
          separationKey,
          rightEntries,
          atomicOperation);
    } else {
      return splitRootBucket(
          keyIndex,
          entryToSplit,
          bucketToSplit,
          splitLeaf,
          indexToSplit,
          separationKey,
          rightEntries,
          atomicOperation);
    }
  }

  private UpdateBucketSearchResult splitNonRootBucket(
      final LongList path,
      final IntList itemPointers,
      final int keyIndex,
      final long pageIndex,
      final CellBTreeSingleValueBucketV3<K> bucketToSplit,
      final boolean splitLeaf,
      final int indexToSplit,
      final K separationKey,
      final List<byte[]> rightEntries,
      final AtomicOperation atomicOperation)
      throws IOException {

    final var rightBucketEntry = allocateNewPage(atomicOperation);
    try (rightBucketEntry) {
      final var newRightBucket =
          new CellBTreeSingleValueBucketV3<K>(rightBucketEntry);
      newRightBucket.init(splitLeaf);
      newRightBucket.addAll(rightEntries, keySerializer);

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
                new CellBTreeSingleValueBucketV3<K>(rightSiblingBucketEntry);
            rightSiblingBucket.setLeftSibling(rightBucketEntry.getPageIndex());
          }
        }
      }
      var parentIndex = path.getLong(path.size() - 2);
      var parentCacheEntry = loadPageForWrite(atomicOperation, fileId, parentIndex, true);
      try {
        var parentBucket =
            new CellBTreeSingleValueBucketV3<K>(parentCacheEntry);
        var insertionIndex = itemPointers.getInt(itemPointers.size() - 2);
        var currentPath = path.subList(0, path.size() - 1);
        var currentIndex = itemPointers.subList(0, itemPointers.size() - 1);
        while (!parentBucket.addNonLeafEntry(
            insertionIndex,
            (int) pageIndex,
            rightBucketEntry.getPageIndex(),
            keySerializer.serializeNativeAsWhole(serializerFactory, separationKey,
                (Object[]) keyTypes))) {
          final var bucketSearchResult =
              splitBucket(
                  parentBucket,
                  parentCacheEntry,
                  currentPath,
                  currentIndex,
                  insertionIndex,
                  atomicOperation);

          parentIndex = bucketSearchResult.getLastPathItem();
          insertionIndex = bucketSearchResult.getItemIndex();
          currentPath = bucketSearchResult.getPath();
          currentIndex = bucketSearchResult.getInsertionIndexes();

          if (parentIndex != parentCacheEntry.getPageIndex()) {
            parentCacheEntry.close();

            parentCacheEntry = loadPageForWrite(atomicOperation, fileId, parentIndex, true);
          }

          //noinspection ObjectAllocationInLoop
          parentBucket = new CellBTreeSingleValueBucketV3<>(parentCacheEntry);
        }

      } finally {
        parentCacheEntry.close();
      }
    }

    final var resultPath = new LongArrayList(path.subList(0, path.size() - 1));
    final var resultItemPointers =
        new IntArrayList(itemPointers.subList(0, itemPointers.size() - 1));

    if (keyIndex <= indexToSplit) {
      resultPath.add(pageIndex);
      resultItemPointers.add(keyIndex);

      return new UpdateBucketSearchResult(resultItemPointers, resultPath, keyIndex);
    }

    final var parentIndex = resultItemPointers.size() - 1;
    resultItemPointers.set(parentIndex, resultItemPointers.getInt(parentIndex) + 1);
    resultPath.add(rightBucketEntry.getPageIndex());

    if (splitLeaf) {
      resultItemPointers.add(keyIndex - indexToSplit);
      return new UpdateBucketSearchResult(resultItemPointers, resultPath, keyIndex - indexToSplit);
    }

    resultItemPointers.add(keyIndex - indexToSplit - 1);
    return new UpdateBucketSearchResult(
        resultItemPointers, resultPath, keyIndex - indexToSplit - 1);
  }

  private CacheEntry allocateNewPage(AtomicOperation atomicOperation) throws IOException {
    final CacheEntry rightBucketEntry;
    try (final var entryPointCacheEntry =
        loadPageForWrite(atomicOperation, fileId, ENTRY_POINT_INDEX, true)) {
      final var entryPoint =
          new CellBTreeSingleValueEntryPointV3<K>(entryPointCacheEntry);
      final var freeListHead = entryPoint.getFreeListHead();
      if (freeListHead > -1) {
        rightBucketEntry = loadPageForWrite(atomicOperation, fileId, freeListHead, false);

        if (rightBucketEntry == null) {
          throw new CellBTreeSingleValueV3Exception(
              "Page that supposed to be in free list of BTree was not found. Page index : "
                  + freeListHead + ", file id : " + fileId,
              this);
        }

        final CellBTreeSingleValueBucketV3<?> bucket =
            new CellBTreeSingleValueBucketV3<>(rightBucketEntry);
        entryPoint.setFreeListHead(bucket.getNextFreeListPage());
      } else {
        var pageSize = entryPoint.getPagesSize();
        if (pageSize < getFilledUpTo(atomicOperation, fileId) - 1) {
          pageSize++;
          rightBucketEntry = loadPageForWrite(atomicOperation, fileId, pageSize, false);
          entryPoint.setPagesSize(pageSize);
        } else {
          assert pageSize == getFilledUpTo(atomicOperation, fileId) - 1;

          rightBucketEntry = addPage(atomicOperation, fileId);
          entryPoint.setPagesSize(rightBucketEntry.getPageIndex());
        }
      }
    }

    return rightBucketEntry;
  }

  private UpdateBucketSearchResult splitRootBucket(
      final int keyIndex,
      final CacheEntry bucketEntry,
      CellBTreeSingleValueBucketV3<K> bucketToSplit,
      final boolean splitLeaf,
      final int indexToSplit,
      final K separationKey,
      final List<byte[]> rightEntries,
      final AtomicOperation atomicOperation)
      throws IOException {
    final List<byte[]> leftEntries = new ArrayList<>(indexToSplit);

    for (var i = 0; i < indexToSplit; i++) {
      leftEntries.add(bucketToSplit.getRawEntry(i, keySerializer, serializerFactory));
    }

    final CacheEntry leftBucketEntry;
    final CacheEntry rightBucketEntry;

    leftBucketEntry = allocateNewPage(atomicOperation);
    rightBucketEntry = allocateNewPage(atomicOperation);

    try {
      final var newLeftBucket =
          new CellBTreeSingleValueBucketV3<K>(leftBucketEntry);
      newLeftBucket.init(splitLeaf);
      newLeftBucket.addAll(leftEntries, keySerializer);

      if (splitLeaf) {
        newLeftBucket.setRightSibling(rightBucketEntry.getPageIndex());
      }

    } finally {
      leftBucketEntry.close();
    }

    try {
      final var newRightBucket =
          new CellBTreeSingleValueBucketV3<K>(rightBucketEntry);
      newRightBucket.init(splitLeaf);
      newRightBucket.addAll(rightEntries, keySerializer);

      if (splitLeaf) {
        newRightBucket.setLeftSibling(leftBucketEntry.getPageIndex());
      }
    } finally {
      rightBucketEntry.close();
    }

    bucketToSplit = new CellBTreeSingleValueBucketV3<>(bucketEntry);
    bucketToSplit.shrink(0, keySerializer, serializerFactory);
    if (splitLeaf) {
      bucketToSplit.switchBucketType();
    }
    bucketToSplit.addNonLeafEntry(
        0,
        leftBucketEntry.getPageIndex(),
        rightBucketEntry.getPageIndex(),
        keySerializer.serializeNativeAsWhole(serializerFactory, separationKey,
            (Object[]) keyTypes));

    final var resultPath = new LongArrayList(8);
    resultPath.add(ROOT_INDEX);

    final var itemPointers = new IntArrayList(8);

    if (keyIndex <= indexToSplit) {
      itemPointers.add(-1);
      itemPointers.add(keyIndex);

      resultPath.add(leftBucketEntry.getPageIndex());
      return new UpdateBucketSearchResult(itemPointers, resultPath, keyIndex);
    }

    resultPath.add(rightBucketEntry.getPageIndex());
    itemPointers.add(0);

    if (splitLeaf) {
      itemPointers.add(keyIndex - indexToSplit);
      return new UpdateBucketSearchResult(itemPointers, resultPath, keyIndex - indexToSplit);
    }

    itemPointers.add(keyIndex - indexToSplit - 1);
    return new UpdateBucketSearchResult(itemPointers, resultPath, keyIndex - indexToSplit - 1);
  }

  private Optional<RemoveSearchResult> findBucketForRemove(
      final K key, final AtomicOperation atomicOperation) throws IOException {

    final var path = new ArrayList<RemovalPathItem>(8);
    final var serializedKey =
        keySerializer.serializeNativeAsWhole(serializerFactory, key, (Object[]) keyTypes);

    var pageIndex = ROOT_INDEX;

    var depth = 0;
    while (true) {
      depth++;
      if (depth > MAX_PATH_LENGTH) {
        throw new CellBTreeSingleValueV3Exception(
            "We reached max level of depth of BTree but still found nothing, seems like tree is in"
                + " corrupted state. You should rebuild index related to given query."
                + " Key = "
                + key,
            this);
      }

      try (final var bucketEntry = loadPageForRead(atomicOperation, fileId, pageIndex)) {
        @SuppressWarnings("ObjectAllocationInLoop")
        final var bucket =
            new CellBTreeSingleValueBucketV3<K>(bucketEntry);

        final var index = bucket.find(serializedKey, keySerializer, serializerFactory);

        if (bucket.isLeaf()) {
          if (index < 0) {
            return Optional.empty();
          }

          return Optional.of(new RemoveSearchResult(pageIndex, index, path));
        }

        if (index >= 0) {
          path.add(new RemovalPathItem(pageIndex, index, false));

          pageIndex = bucket.getRight(index);
        } else {
          final var insertionIndex = -index - 1;
          if (insertionIndex >= bucket.size()) {
            path.add(new RemovalPathItem(pageIndex, insertionIndex - 1, false));

            pageIndex = bucket.getRight(insertionIndex - 1);
          } else {
            path.add(new RemovalPathItem(pageIndex, insertionIndex, true));

            pageIndex = bucket.getLeft(insertionIndex);
          }
        }
      }
    }
  }

  private BucketSearchResult findBucket(final K key, final AtomicOperation atomicOperation)
      throws IOException {
    var pageIndex = ROOT_INDEX;

    var depth = 0;
    while (true) {
      depth++;
      if (depth > MAX_PATH_LENGTH) {
        throw new CellBTreeSingleValueV3Exception(
            "We reached max level of depth of SBTree but still found nothing, seems like tree is in"
                + " corrupted state. You should rebuild index related to given query. Key = "
                + key,
            this);
      }

      try (final var bucketEntry = loadPageForRead(atomicOperation, fileId, pageIndex)) {
        @SuppressWarnings("ObjectAllocationInLoop")
        final var keyBucket =
            new CellBTreeSingleValueBucketV3<K>(bucketEntry);
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

  /**
   * Like {@link #findBucket(Object, AtomicOperation)} but uses a pre-serialized key for
   * zero-allocation in-buffer comparison during the B-tree traversal.
   */
  private BucketSearchResult findBucketSerialized(
      final K key, final byte[] serializedKey,
      final AtomicOperation atomicOperation) throws IOException {
    var pageIndex = ROOT_INDEX;

    var depth = 0;
    while (true) {
      depth++;
      if (depth > MAX_PATH_LENGTH) {
        throw new CellBTreeSingleValueV3Exception(
            "We reached max level of depth of SBTree but still found nothing, seems like tree is in"
                + " corrupted state. You should rebuild index related to given query. Key = "
                + key,
            this);
      }

      try (final var bucketEntry = loadPageForRead(atomicOperation, fileId, pageIndex)) {
        @SuppressWarnings("ObjectAllocationInLoop")
        final var keyBucket =
            new CellBTreeSingleValueBucketV3<K>(bucketEntry);
        final var index = keyBucket.find(serializedKey, keySerializer, serializerFactory);

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
      final K key, final byte[] serializedKey,
      final AtomicOperation atomicOperation) throws IOException {
    var pageIndex = ROOT_INDEX;

    final var path = new LongArrayList(8);
    final var itemIndexes = new IntArrayList(8);

    while (true) {
      if (path.size() > MAX_PATH_LENGTH) {
        throw new CellBTreeSingleValueV3Exception(
            "We reached max level of depth of SBTree but still found nothing, seems like tree is in"
                + " corrupted state. You should rebuild index related to given query. Key = "
                + key,
            this);
      }

      path.add(pageIndex);
      try (final var bucketEntry = loadPageForRead(atomicOperation, fileId, pageIndex)) {
        @SuppressWarnings("ObjectAllocationInLoop")
        final var keyBucket =
            new CellBTreeSingleValueBucketV3<K>(bucketEntry);
        final var index = keyBucket.find(serializedKey, keySerializer, serializerFactory);

        if (keyBucket.isLeaf()) {
          itemIndexes.add(index);
          return new UpdateBucketSearchResult(itemIndexes, path, index);
        }

        if (index >= 0) {
          pageIndex = keyBucket.getRight(index);
          itemIndexes.add(index + 1);
        } else {
          final var insertionIndex = -index - 1;

          if (insertionIndex >= keyBucket.size()) {
            pageIndex = keyBucket.getRight(insertionIndex - 1);
          } else {
            pageIndex = keyBucket.getLeft(insertionIndex);
          }

          itemIndexes.add(insertionIndex);
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

  public void fetchBackwardNextCachePortion(SpliteratorBackward<K> iter,
      @Nonnull AtomicOperation atomicOperation) {
    final K lastKey;
    if (iter.getDataCache().isEmpty()) {
      lastKey = null;
    } else {
      lastKey = iter.getDataCache().getLast().first();
    }

    iter.getDataCache().clear();
    iter.setCacheIterator(Collections.emptyIterator());

    try {
      fetchBackwardCachePortionInner(iter, atomicOperation, lastKey);
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new CellBTreeSingleValueV3Exception("Error during entity iteration", this), e,
          storage.getName());
    }
  }

  private void fetchBackwardCachePortionInner(SpliteratorBackward<K> iter,
      AtomicOperation atomicOperation, K lastKey) throws IOException {
    // Cursor fetch methods mutate spliterator state and have no optimistic variant,
    // so we acquire the shared lock directly rather than using
    // executeOptimisticStorageRead (which could retry with corrupted state).
    acquireSharedLock();
    try {
      doFetchBackwardCachePortion(iter, atomicOperation, lastKey);
    } finally {
      releaseSharedLock();
    }
  }

  private void doFetchBackwardCachePortion(SpliteratorBackward<K> iter,
      AtomicOperation atomicOperation, K lastKey) throws IOException {
    if (iter.getPageIndex() > -1) {
      if (readKeysFromBucketsBackward(atomicOperation, iter)) {
        return;
      }
    }

    if (iter.getDataCache().isEmpty()) {
      if (lastKey == null) {
        if (iter.getToKey() != null) {
          final var searchResult = findBucket(iter.getToKey(), atomicOperation);
          iter.setPageIndex((int) searchResult.getPageIndex());

          if (searchResult.getItemIndex() >= 0) {
            if (iter.isToKeyInclusive()) {
              iter.setItemIndex(searchResult.getItemIndex());
            } else {
              iter.setItemIndex(searchResult.getItemIndex() - 1);
            }
          } else {
            iter.setItemIndex(-searchResult.getItemIndex() - 2);
          }
        } else {
          final var bucketSearchResult = lastItem(atomicOperation);
          if (bucketSearchResult.isPresent()) {
            final var searchResult = bucketSearchResult.get();
            iter.setPageIndex((int) searchResult.getPageIndex());
            iter.setItemIndex(searchResult.getItemIndex());
          } else {
            return;
          }
        }

      } else {
        final var bucketSearchResult = findBucket(lastKey, atomicOperation);

        iter.setPageIndex((int) bucketSearchResult.getPageIndex());
        if (bucketSearchResult.getItemIndex() >= 0) {
          iter.setItemIndex(bucketSearchResult.getItemIndex() - 1);
        } else {
          iter.setItemIndex(-bucketSearchResult.getItemIndex() - 2);
        }
      }
      iter.setLastLSN(null);
      readKeysFromBucketsBackward(atomicOperation, iter);
    }
  }

  void fetchNextForwardCachePortion(SpliteratorForward<K> iter,
      @Nonnull AtomicOperation atomicOperation) {
    final K lastKey;
    if (!iter.getDataCache().isEmpty()) {
      lastKey = iter.getDataCache().getLast().first();
    } else {
      lastKey = null;
    }

    iter.getDataCache().clear();
    iter.setCacheIterator(Collections.emptyIterator());

    try {
      fetchForwardCachePortionInner(iter, atomicOperation, lastKey);
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new CellBTreeSingleValueV3Exception(
              "Error during entity iteration", BTree.this),
          e, storage.getName());
    }
  }

  private void fetchForwardCachePortionInner(SpliteratorForward<K> iter,
      AtomicOperation atomicOperation, K lastKey) throws IOException {
    // Cursor fetch methods mutate spliterator state and have no optimistic variant,
    // so we acquire the shared lock directly rather than using
    // executeOptimisticStorageRead (which could retry with corrupted state).
    acquireSharedLock();
    try {
      doFetchForwardCachePortion(iter, atomicOperation, lastKey);
    } finally {
      releaseSharedLock();
    }
  }

  private void doFetchForwardCachePortion(SpliteratorForward<K> iter,
      AtomicOperation atomicOperation, K lastKey) throws IOException {
    if (iter.getPageIndex() > -1) {
      if (readKeysFromBucketsForward(atomicOperation, iter)) {
        return;
      }
    }

    if (iter.getDataCache().isEmpty()) {
      if (lastKey == null) {
        if (iter.getFromKey() != null) {
          final var searchResult =
              findBucket(iter.getFromKey(), atomicOperation);
          iter.setPageIndex((int) searchResult.getPageIndex());

          if (searchResult.getItemIndex() >= 0) {
            if (iter.isFromKeyInclusive()) {
              iter.setItemIndex(searchResult.getItemIndex());
            } else {
              iter.setItemIndex(searchResult.getItemIndex() + 1);
            }
          } else {
            iter.setItemIndex(-searchResult.getItemIndex() - 1);
          }
        } else {
          final var bucketSearchResult = firstItem(atomicOperation);
          if (bucketSearchResult.isPresent()) {
            final var searchResult = bucketSearchResult.get();
            iter.setPageIndex((int) searchResult.getPageIndex());
            iter.setItemIndex(searchResult.getItemIndex());
          } else {
            return;
          }
        }

      } else {
        final var bucketSearchResult = findBucket(lastKey, atomicOperation);

        iter.setPageIndex((int) bucketSearchResult.getPageIndex());
        if (bucketSearchResult.getItemIndex() >= 0) {
          iter.setItemIndex(bucketSearchResult.getItemIndex() + 1);
        } else {
          iter.setItemIndex(-bucketSearchResult.getItemIndex() - 1);
        }
      }
      iter.setLastLSN(null);
      readKeysFromBucketsForward(atomicOperation, iter);
    }
  }

  private boolean readKeysFromBucketsForward(
      AtomicOperation atomicOperation, SpliteratorForward<K> iter) throws IOException {
    var cacheEntry = loadPageForRead(atomicOperation, fileId, iter.getPageIndex());
    try {
      var bucket = new CellBTreeSingleValueBucketV3<K>(cacheEntry);
      if (iter.getLastLSN() == null || bucket.getLsn().equals(iter.getLastLSN())) {
        while (true) {
          var bucketSize = bucket.size();
          if (iter.getItemIndex() >= bucketSize) {
            iter.setPageIndex((int) bucket.getRightSibling());

            if (iter.getPageIndex() < 0) {
              return true;
            }

            iter.setItemIndex(0);
            cacheEntry.close();

            cacheEntry = loadPageForRead(atomicOperation, fileId, iter.getPageIndex());
            bucket = new CellBTreeSingleValueBucketV3<>(cacheEntry);

            bucketSize = bucket.size();
          }

          iter.setLastLSN(bucket.getLsn());

          for (;
              iter.getItemIndex() < bucketSize
                  && iter.getDataCache().size() < SPLITERATOR_CACHE_SIZE;
              iter.setItemIndex(iter.getItemIndex() + 1)) {
            @SuppressWarnings("ObjectAllocationInLoop")
            var entry =
                bucket.getEntry(iter.getItemIndex(), keySerializer, serializerFactory);

            if (iter.getToKey() != null) {
              if (iter.isToKeyInclusive()) {
                if (comparator.compare(entry.key, iter.getToKey()) > 0) {
                  return true;
                }
              } else if (comparator.compare(entry.key, iter.getToKey()) >= 0) {
                return true;
              }
            }

            //noinspection ObjectAllocationInLoop
            iter.getDataCache().add(new RawPair<>(entry.key, entry.value));
          }

          if (iter.getDataCache().size() >= SPLITERATOR_CACHE_SIZE) {
            return true;
          }
        }
      }
    } finally {
      cacheEntry.close();
    }

    return false;
  }

  private boolean readKeysFromBucketsBackward(
      AtomicOperation atomicOperation, SpliteratorBackward<K> iter) throws IOException {
    var cacheEntry = loadPageForRead(atomicOperation, fileId, iter.getPageIndex());
    try {
      var bucket = new CellBTreeSingleValueBucketV3<K>(cacheEntry);
      if (iter.getLastLSN() == null || bucket.getLsn().equals(iter.getLastLSN())) {
        while (true) {
          if (iter.getItemIndex() < 0) {
            iter.setPageIndex((int) bucket.getLeftSibling());

            if (iter.getPageIndex() < 0) {
              return true;
            }

            cacheEntry.close();

            cacheEntry = loadPageForRead(atomicOperation, fileId, iter.getPageIndex());
            bucket = new CellBTreeSingleValueBucketV3<>(cacheEntry);
            final var bucketSize = bucket.size();
            iter.setItemIndex(bucketSize - 1);
          }

          iter.setLastLSN(bucket.getLsn());

          for (;
              iter.getItemIndex() >= 0 && iter.getDataCache().size() < SPLITERATOR_CACHE_SIZE;
              iter.setItemIndex(iter.getItemIndex() - 1)) {
            @SuppressWarnings("ObjectAllocationInLoop")
            var entry =
                bucket.getEntry(iter.getItemIndex(), keySerializer, serializerFactory);

            if (iter.getFromKey() != null) {
              if (iter.isFromKeyInclusive()) {
                if (comparator.compare(entry.key, iter.getFromKey()) < 0) {
                  return true;
                }
              } else if (comparator.compare(entry.key, iter.getFromKey()) <= 0) {
                return true;
              }
            }

            //noinspection ObjectAllocationInLoop
            iter.getDataCache().add(new RawPair<>(entry.key, entry.value));
          }

          if (iter.getDataCache().size() >= SPLITERATOR_CACHE_SIZE) {
            return true;
          }
        }
      }
    } finally {
      cacheEntry.close();
    }

    return false;
  }
}
