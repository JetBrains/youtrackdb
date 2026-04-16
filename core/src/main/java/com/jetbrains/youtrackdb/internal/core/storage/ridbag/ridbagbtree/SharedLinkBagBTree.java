package com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.cache.AbstractWriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.FileHandler;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsTable.AtomicOperationsSnapshot;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurableComponent;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Spliterator;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class SharedLinkBagBTree extends DurableComponent {

  private static final int MAX_PATH_LENGTH =
      GlobalConfiguration.BTREE_MAX_DEPTH.getValueAsInteger();

  private static final int ENTRY_POINT_INDEX = 0;
  private static final int ROOT_INDEX = 1;

  private volatile FileHandler fileHandler;
  private final BinarySerializerFactory serializerFactory;

  public SharedLinkBagBTree(final AbstractStorage storage, final String name,
      final String fileExtension) {
    super(storage, name, fileExtension, name + fileExtension);

    this.serializerFactory = storage.getComponentsFactory().binarySerializerFactory;
  }

  public long getFileId() {
    return fileHandler.fileId();
  }

  public void create(final AtomicOperation atomicOperation) {
    executeInsideComponentOperation(
        atomicOperation,
        (operation) -> {
          acquireExclusiveLock();
          try {
            fileHandler = addFile(atomicOperation, getFullName());

            try (final var entryPointCacheEntry = addPage(atomicOperation, fileHandler)) {
              final var entryPoint = new EntryPoint(entryPointCacheEntry);
              entryPoint.init();
            }

            try (final var rootCacheEntry = addPage(atomicOperation, fileHandler)) {
              final var rootBucket = new Bucket(rootCacheEntry);
              rootBucket.init(true);
            }
          } finally {
            releaseExclusiveLock();
          }
        });
  }

  public void load(AtomicOperation atomicOperation) {
    acquireExclusiveLock();
    try {
      fileHandler = openFile(atomicOperation, getFullName());
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new StorageException(storage.getName(),
              "Exception during loading of rid bag " + getFullName()),
          e, storage.getName());
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
            deleteFile(atomicOperation, fileHandler.fileId());
          } finally {
            releaseExclusiveLock();
          }
        });
  }

  /**
   * Finds the current (single) B-tree entry for a logical edge identified by
   * the 3-tuple {@code (ridBagId, targetCollection, targetPosition)}, regardless
   * of its timestamp.
   *
   * <p>Since {@code ts} is the last comparison component in {@link EdgeKey}, a
   * prefix range search with {@code Long.MIN_VALUE} as the timestamp positions
   * us at or just before any entry with matching 3-tuple prefix. The
   * single-version invariant guarantees at most one B-tree entry per logical
   * edge, so checking the entry at the insertion point (and the right sibling's
   * first entry when the insertion point falls at the end of the bucket) is
   * sufficient.
   *
   * @return the current entry (key + value), or {@code null} if no entry exists
   *     for this logical edge
   */
  @Nullable public RawPair<EdgeKey, LinkBagValue> findCurrentEntry(
      final AtomicOperation atomicOperation,
      final long ridBagId,
      final int targetCollection,
      final long targetPosition) {
    try {
      return atomicOperationsManager.executeReadOperation(this,
          () -> findCurrentEntryInternal(atomicOperation, ridBagId, targetCollection,
              targetPosition));
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new StorageException(storage.getName(),
              "Error during prefix lookup in rid bag btree [" + getName() + "]"),
          e, storage.getName());
    }
  }

  /**
   * Internal prefix lookup — callable inside both read operations and write
   * operations (when the exclusive lock is already held). See
   * {@link #findCurrentEntry} for the algorithm description.
   */
  @Nullable private RawPair<EdgeKey, LinkBagValue> findCurrentEntryInternal(
      final AtomicOperation atomicOperation,
      final long ridBagId,
      final int targetCollection,
      final long targetPosition) throws IOException {
    final var searchKey =
        new EdgeKey(ridBagId, targetCollection, targetPosition, Long.MIN_VALUE);
    final var result = findBucket(searchKey, atomicOperation);

    final int candidateIndex;
    if (result.getItemIndex() >= 0) {
      candidateIndex = result.getItemIndex();
    } else {
      candidateIndex = -result.getItemIndex() - 1;
    }

    try (final var cacheEntry =
        loadPageForRead(atomicOperation, fileHandler, result.getPageIndex())) {
      final var bucket = new Bucket(cacheEntry);

      if (candidateIndex < bucket.size()) {
        return checkEntryPrefix(
            bucket, candidateIndex, ridBagId, targetCollection, targetPosition);
      }

      // Insertion point is at the end of this bucket. The matching entry
      // may be the first entry on the right sibling page (can happen when
      // a bucket split placed the separator between Long.MIN_VALUE and the
      // actual ts). One hop is sufficient because the single-version
      // invariant guarantees at most one entry per 3-tuple prefix, and
      // findBucket lands us in the correct neighborhood.
      final long rightSibling = bucket.getRightSibling();
      if (rightSibling < 0) {
        return null;
      }

      try (final var siblingCacheEntry =
          loadPageForRead(atomicOperation, fileHandler, rightSibling)) {
        final var siblingBucket = new Bucket(siblingCacheEntry);
        // Empty leaf buckets should not exist in a well-formed B-tree
        // (only transiently during splits). This is a safety net.
        if (siblingBucket.isEmpty()) {
          return null;
        }
        return checkEntryPrefix(
            siblingBucket, 0, ridBagId, targetCollection, targetPosition);
      }
    }
  }

  /**
   * Checks whether the entry at {@code index} in the given bucket matches the
   * 3-tuple prefix. Returns the key-value pair if it matches, {@code null}
   * otherwise.
   */
  @Nullable private RawPair<EdgeKey, LinkBagValue> checkEntryPrefix(
      final Bucket bucket,
      final int index,
      final long ridBagId,
      final int targetCollection,
      final long targetPosition) {
    final var entry = bucket.getEntry(index, serializerFactory);
    final var key = entry.getKey();
    if (key.ridBagId == ridBagId
        && key.targetCollection == targetCollection
        && key.targetPosition == targetPosition) {
      return new RawPair<>(key, entry.getValue());
    }
    return null;
  }

  /**
   * Returns {@code true} if the given edge version is visible to the current
   * transaction. Applies the self-read shortcut (version == currentOperationTs)
   * before checking the snapshot, matching the pattern in
   * PaginatedCollectionV2.isRecordVersionVisible().
   */
  static boolean isEdgeVersionVisible(long version, long currentOperationTs,
      @Nonnull AtomicOperationsSnapshot snapshot) {
    if (version == currentOperationTs) {
      return true;
    }
    return snapshot.isEntryVisible(version);
  }

  /**
   * Given a B-tree entry, checks its visibility for the current transaction:
   * <ul>
   *   <li>Visible and not tombstone → returns the entry</li>
   *   <li>Visible and tombstone → returns null (edge deleted)</li>
   *   <li>Not visible → searches the snapshot index for the newest visible
   *       non-tombstone version, returns it or null</li>
   * </ul>
   *
   * <p>Package-private: also called from spliterator cache-fill methods
   * (readKeysFromBucketsForward/Backward) for per-entry visibility resolution.
   *
   * @return visible entry as (EdgeKey, LinkBagValue), or null if no visible
   *     non-tombstone version exists
   */
  @Nullable RawPair<EdgeKey, LinkBagValue> resolveVisibleEntry(
      final EdgeKey bTreeKey,
      final LinkBagValue bTreeValue,
      final AtomicOperation atomicOp) {
    assert atomicOp != null : "resolveVisibleEntry requires a non-null atomicOperation";
    final long currentOperationTs = atomicOp.getCommitTsUnsafe();
    final var snapshot = atomicOp.getAtomicOperationsSnapshot();

    if (isEdgeVersionVisible(bTreeKey.ts, currentOperationTs, snapshot)) {
      // Visible — return if live, null if tombstone
      if (bTreeValue.tombstone()) {
        return null;
      }
      return new RawPair<>(bTreeKey, bTreeValue);
    }

    // Not visible — fall back to snapshot index for a visible version
    return findVisibleSnapshotEntry(
        bTreeKey.ridBagId, bTreeKey.targetCollection, bTreeKey.targetPosition,
        currentOperationTs, snapshot, atomicOp);
  }

  /**
   * Searches the edge snapshot index for the newest visible version of the
   * logical edge identified by (ridBagId, targetCollection, targetPosition).
   * Iterates entries in descending version order (newest first). If the newest
   * visible version is a tombstone, returns null (edge is deleted from the
   * reader's perspective). If it is a live entry, returns it. If no visible
   * version exists, returns null.
   */
  @Nullable private RawPair<EdgeKey, LinkBagValue> findVisibleSnapshotEntry(
      final long ridBagId,
      final int targetCollection,
      final long targetPosition,
      final long currentOperationTs,
      final AtomicOperationsSnapshot snapshot,
      final AtomicOperation atomicOp) {
    final int componentId = AbstractWriteCache.extractFileId(getFileId());
    assert componentId > 0 : "componentId must be positive, got " + componentId;

    // Range: all versions of this logical edge (from MIN to MAX)
    final var lowerKey = new EdgeSnapshotKey(
        componentId, ridBagId, targetCollection, targetPosition, Long.MIN_VALUE);
    final var upperKey = new EdgeSnapshotKey(
        componentId, ridBagId, targetCollection, targetPosition, Long.MAX_VALUE);

    for (Map.Entry<EdgeSnapshotKey, LinkBagValue> entry : atomicOp
        .edgeSnapshotSubMapDescending(lowerKey, upperKey)) {
      final long version = entry.getKey().version();
      if (isEdgeVersionVisible(version, currentOperationTs, snapshot)) {
        if (entry.getValue().tombstone()) {
          // Newest visible version is a tombstone — edge is deleted
          return null;
        }
        // Reconstruct an EdgeKey with the snapshot version's ts
        final var edgeKey = new EdgeKey(
            ridBagId, targetCollection, targetPosition, version);
        return new RawPair<>(edgeKey, entry.getValue());
      }
    }
    return null;
  }

  /**
   * Finds the visible entry for a logical edge, combining prefix lookup in
   * the B-tree with SI visibility resolution. Returns the visible non-tombstone
   * entry, or null if the edge is not visible (deleted, invisible, or absent).
   */
  @Nullable public RawPair<EdgeKey, LinkBagValue> findVisibleEntry(
      final AtomicOperation atomicOperation,
      final long ridBagId,
      final int targetCollection,
      final long targetPosition) {
    try {
      return atomicOperationsManager.executeReadOperation(this, () -> {
        final var current = findCurrentEntryInternal(
            atomicOperation, ridBagId, targetCollection, targetPosition);
        if (current == null) {
          // No B-tree entry at all — check snapshot index directly.
          // This can happen if a concurrent transaction replaced an entry
          // that was moved to the snapshot index and the new entry is also
          // invisible.
          final long currentOperationTs = atomicOperation.getCommitTsUnsafe();
          final var snapshot = atomicOperation.getAtomicOperationsSnapshot();
          return findVisibleSnapshotEntry(
              ridBagId, targetCollection, targetPosition,
              currentOperationTs, snapshot, atomicOperation);
        }
        return resolveVisibleEntry(current.first(), current.second(), atomicOperation);
      });
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new StorageException(storage.getName(),
              "Error during visible entry lookup in rid bag btree [" + getName() + "]"),
          e, storage.getName());
    }
  }

  public LinkBagValue get(final EdgeKey key, AtomicOperation atomicOperation) {
    try {
      return atomicOperationsManager.executeReadOperation(this, () -> {
        final var bucketSearchResult = findBucket(key, atomicOperation);
        if (bucketSearchResult.getItemIndex() < 0) {
          return null;
        }

        final var pageIndex = bucketSearchResult.getPageIndex();

        try (final var keyBucketCacheEntry =
            loadPageForRead(atomicOperation, fileHandler, pageIndex)) {
          final var keyBucket = new Bucket(keyBucketCacheEntry);
          return keyBucket.getValue(bucketSearchResult.getItemIndex(), serializerFactory);
        }
      });
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new StorageException(storage.getName(),
              "Error during retrieving  of value for rid bag with name " + getName()),
          e, storage.getName());
    }
  }

  public boolean put(final AtomicOperation atomicOperation, final EdgeKey key,
      final LinkBagValue value) {
    return calculateInsideComponentOperation(
        atomicOperation,
        operation -> {
          boolean result;
          boolean crossTxReplacement = false;
          acquireExclusiveLock();
          try {
            // SI: check for existing entry with same logical edge but
            // possibly different ts (cross-transaction update).
            final var existing = findCurrentEntryInternal(
                atomicOperation, key.ridBagId, key.targetCollection, key.targetPosition);
            if (existing != null && existing.first().ts != key.ts) {
              // Cross-transaction update: preserve old version in snapshot
              // index, then remove the old entry so the insert below proceeds
              // as a fresh insert. The net tree size stays the same
              // (removeEntryByKey decrements, insert below increments).
              final var oldKey = existing.first();
              final var oldValue = existing.second();

              if (key.ts <= oldKey.ts) {
                throw new StorageException(storage.getName(),
                    "Version monotonicity violated in link bag btree [" + getName()
                        + "]: newTs=" + key.ts + " <= oldTs=" + oldKey.ts);
              }

              preserveInSnapshot(atomicOperation, oldKey, oldValue);
              removeEntryByKey(atomicOperation, oldKey);
              crossTxReplacement = true;
            }

            // Standard insert/update logic. After cross-tx snapshot
            // preservation, the old entry is gone and findBucketForUpdate
            // will find the insertion point for the new key. For same-ts
            // overwrites, findBucketForUpdate finds the exact key.
            final var serializedKey =
                EdgeKeySerializer.INSTANCE.serializeNativeAsWhole(serializerFactory, key,
                    (Object[]) null);
            var bucketSearchResult = findBucketForUpdate(key, atomicOperation);

            var keyBucketCacheEntry =
                loadPageForWrite(
                    atomicOperation, fileHandler, bucketSearchResult.getLastPathItem(), true);
            var keyBucket = new Bucket(keyBucketCacheEntry);
            final byte[] oldRawValue;

            if (bucketSearchResult.getItemIndex() > -1) {
              oldRawValue = keyBucket.getRawValue(bucketSearchResult.getItemIndex(),
                  serializerFactory);
              result = false;
            } else {
              oldRawValue = null;
              result = true;
            }

            final var serializedValue =
                LinkBagValueSerializer.INSTANCE.serializeNativeAsWhole(serializerFactory, value,
                    (Object[]) null);

            int insertionIndex;
            final int sizeDiff;
            if (bucketSearchResult.getItemIndex() >= 0) {
              assert oldRawValue != null;

              if (oldRawValue.length == serializedValue.length) {
                keyBucket.updateValue(
                    bucketSearchResult.getItemIndex(), serializedValue, serializedKey.length,
                    serializerFactory);
                keyBucketCacheEntry.close();
                return false;
              } else {
                keyBucket.removeLeafEntry(
                    bucketSearchResult.getItemIndex(), serializedKey.length, oldRawValue.length);
                insertionIndex = bucketSearchResult.getItemIndex();
                sizeDiff = 0;
              }
            } else {
              insertionIndex = -bucketSearchResult.getItemIndex() - 1;
              sizeDiff = 1;
            }

            insertLeafWithGCAndSplit(
                key, serializedKey, serializedValue,
                bucketSearchResult, keyBucketCacheEntry, keyBucket,
                insertionIndex, atomicOperation);

            if (sizeDiff != 0) {
              updateSize(sizeDiff, atomicOperation);
            }

            // Cross-tx replacement: the standard logic sees the new key as
            // a fresh insert (result=true) because the old entry was removed.
            // Override to false — logically this is a replacement.
            if (crossTxReplacement) {
              result = false;
            }
          } finally {
            releaseExclusiveLock();
          }

          return result;
        });
  }

  /**
   * Preserves the given entry in the edge snapshot and visibility indexes.
   * Called before modifying or removing a B-tree entry in a cross-transaction
   * update, so older transactions can still read the old version.
   */
  private void preserveInSnapshot(
      final AtomicOperation atomicOperation,
      final EdgeKey oldKey,
      final LinkBagValue oldValue) {
    final int componentId = AbstractWriteCache.extractFileId(getFileId());
    final var snapshotKey = new EdgeSnapshotKey(
        componentId, oldKey.ridBagId, oldKey.targetCollection,
        oldKey.targetPosition, oldKey.ts);

    atomicOperation.putEdgeSnapshotEntry(snapshotKey, oldValue);
    atomicOperation.putEdgeVisibilityEntry(
        new EdgeVisibilityKey(oldKey.ts, componentId, oldKey.ridBagId,
            oldKey.targetCollection, oldKey.targetPosition),
        snapshotKey);
  }

  /**
   * Removes a B-tree entry by exact key. Used internally within write
   * operations that already hold the exclusive lock. Decrements the tree size.
   */
  private void removeEntryByKey(
      final AtomicOperation atomicOperation,
      final EdgeKey key) throws IOException {
    final var searchResult = findBucket(key, atomicOperation);
    if (searchResult.getItemIndex() < 0) {
      throw new StorageException(storage.getName(),
          "removeEntryByKey: entry not found in link bag btree [" + getName()
              + "] for key " + key);
    }

    final var serializedKey =
        EdgeKeySerializer.INSTANCE.serializeNativeAsWhole(serializerFactory, key);
    try (final var cacheEntry =
        loadPageForWrite(atomicOperation, fileHandler, searchResult.getPageIndex(), true)) {
      final var bucket = new Bucket(cacheEntry);
      final var rawValue =
          bucket.getRawValue(searchResult.getItemIndex(), serializerFactory);
      bucket.removeLeafEntry(searchResult.getItemIndex(), serializedKey.length, rawValue.length);
      updateSize(-1, atomicOperation);
    }
  }

  @Nullable public EdgeKey firstKey(AtomicOperation atomicOperation) {
    try {
      return atomicOperationsManager.executeReadOperation(this, () -> {
        final var searchResult = firstItem(atomicOperation);
        if (searchResult.isEmpty()) {
          return null;
        }

        final var result = searchResult.get();

        try (final var cacheEntry =
            loadPageForRead(atomicOperation, fileHandler, result.getPageIndex())) {
          final var bucket = new Bucket(cacheEntry);
          return bucket.getKey(result.getItemIndex(), serializerFactory);
        }
      });
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new StorageException(storage.getName(),
              "Error during finding first key in btree [" + getName() + "]"),
          e,
          storage.getName());
    }
  }

  private Optional<BucketSearchResult> firstItem(final AtomicOperation atomicOperation)
      throws IOException {
    final var path = new LinkedList<PagePathItemUnit>();

    long bucketIndex = ROOT_INDEX;

    var cacheEntry = loadPageForRead(atomicOperation, fileHandler, bucketIndex);
    var itemIndex = 0;
    try {
      var bucket = new Bucket(cacheEntry);

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

        cacheEntry = loadPageForRead(atomicOperation, fileHandler, bucketIndex);
        //noinspection ObjectAllocationInLoop
        bucket = new Bucket(cacheEntry);
      }
    } finally {
      cacheEntry.close();
    }
  }

  @Nullable public EdgeKey lastKey(AtomicOperation atomicOperation) {
    try {
      return atomicOperationsManager.executeReadOperation(this, () -> {
        final var searchResult = lastItem(atomicOperation);
        if (searchResult.isEmpty()) {
          return null;
        }

        final var result = searchResult.get();

        try (final var cacheEntry =
            loadPageForRead(atomicOperation, fileHandler, result.getPageIndex())) {
          final var bucket = new Bucket(cacheEntry);
          return bucket.getKey(result.getItemIndex(), serializerFactory);
        }
      });
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new StorageException(storage.getName(),
              "Error during finding last key in btree [" + getName() + "]"),
          e, storage.getName());
    }
  }

  private Optional<BucketSearchResult> lastItem(final AtomicOperation atomicOperation)
      throws IOException {
    final var path = new LinkedList<PagePathItemUnit>();

    long bucketIndex = ROOT_INDEX;

    var cacheEntry = loadPageForRead(atomicOperation, fileHandler, bucketIndex);

    var bucket = new Bucket(cacheEntry);

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

            itemIndex = Bucket.MAX_PAGE_SIZE_BYTES + 1;
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

        cacheEntry = loadPageForRead(atomicOperation, fileHandler, bucketIndex);

        //noinspection ObjectAllocationInLoop
        bucket = new Bucket(cacheEntry);
        if (itemIndex == Bucket.MAX_PAGE_SIZE_BYTES + 1) {
          itemIndex = bucket.size() - 1;
        }
      }
    } finally {
      cacheEntry.close();
    }
  }

  private UpdateBucketSearchResult splitBucket(
      final Bucket bucketToSplit,
      final CacheEntry entryToSplit,
      final IntList path,
      final IntList itemPointers,
      final int keyIndex,
      final AtomicOperation atomicOperation)
      throws IOException {
    final var splitLeaf = bucketToSplit.isLeaf();
    final var bucketSize = bucketToSplit.size();

    final var indexToSplit = bucketSize >>> 1;
    final var separationKey = bucketToSplit.getKey(indexToSplit, serializerFactory);
    final List<byte[]> rightEntries = new ArrayList<>(indexToSplit);

    final var startRightIndex = splitLeaf ? indexToSplit : indexToSplit + 1;

    for (var i = startRightIndex; i < bucketSize; i++) {
      rightEntries.add(bucketToSplit.getRawEntry(i, serializerFactory));
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
      final IntList path,
      final IntList itemPointers,
      final int keyIndex,
      final int pageIndex,
      final Bucket bucketToSplit,
      final boolean splitLeaf,
      final int indexToSplit,
      final EdgeKey separationKey,
      final List<byte[]> rightEntries,
      final AtomicOperation atomicOperation)
      throws IOException {

    final CacheEntry rightBucketEntry;
    try (final var entryPointCacheEntry =
        loadPageForWrite(atomicOperation, fileHandler, ENTRY_POINT_INDEX, true)) {
      final var entryPoint = new EntryPoint(entryPointCacheEntry);
      var pageSize = entryPoint.getPagesSize();

      if (pageSize < getFilledUpTo(atomicOperation, fileHandler) - 1) {
        pageSize++;
        rightBucketEntry = loadPageForWrite(atomicOperation, fileHandler, pageSize, false);
        entryPoint.setPagesSize(pageSize);
      } else {
        assert pageSize == getFilledUpTo(atomicOperation, fileHandler) - 1;

        rightBucketEntry = addPage(atomicOperation, fileHandler);
        entryPoint.setPagesSize(rightBucketEntry.getPageIndex());
      }
    }

    try {
      final var newRightBucket = new Bucket(rightBucketEntry);
      newRightBucket.init(splitLeaf);
      newRightBucket.addAll(rightEntries);

      bucketToSplit.shrink(indexToSplit, serializerFactory);

      if (splitLeaf) {
        final var rightSiblingPageIndex = bucketToSplit.getRightSibling();

        newRightBucket.setRightSibling(rightSiblingPageIndex);
        newRightBucket.setLeftSibling(pageIndex);

        bucketToSplit.setRightSibling(rightBucketEntry.getPageIndex());

        if (rightSiblingPageIndex >= 0) {

          try (final var rightSiblingBucketEntry =
              loadPageForWrite(atomicOperation, fileHandler, rightSiblingPageIndex, true)) {
            final var rightSiblingBucket = new Bucket(rightSiblingBucketEntry);
            rightSiblingBucket.setLeftSibling(rightBucketEntry.getPageIndex());
          }
        }
      }

      long parentIndex = path.getInt(path.size() - 2);
      var parentCacheEntry = loadPageForWrite(atomicOperation, fileHandler, parentIndex, true);
      try {
        var parentBucket = new Bucket(parentCacheEntry);
        var insertionIndex = itemPointers.getInt(itemPointers.size() - 2);
        while (!parentBucket.addNonLeafEntry(
            insertionIndex,
            pageIndex,
            rightBucketEntry.getPageIndex(),
            EdgeKeySerializer.INSTANCE.serializeNativeAsWhole(serializerFactory, separationKey,
                (Object[]) null),
            true)) {
          final var bucketSearchResult =
              splitBucket(
                  parentBucket,
                  parentCacheEntry,
                  path.subList(0, path.size() - 1),
                  itemPointers.subList(0, itemPointers.size() - 1),
                  insertionIndex,
                  atomicOperation);

          parentIndex = bucketSearchResult.getLastPathItem();
          insertionIndex = bucketSearchResult.getItemIndex();

          if (parentIndex != parentCacheEntry.getPageIndex()) {
            parentCacheEntry.close();

            parentCacheEntry = loadPageForWrite(atomicOperation, fileHandler, parentIndex, true);
          }

          //noinspection ObjectAllocationInLoop
          parentBucket = new Bucket(parentCacheEntry);
        }

      } finally {
        parentCacheEntry.close();
      }

    } finally {
      rightBucketEntry.close();
    }

    final var resultPath = new IntArrayList(path.subList(0, path.size() - 1));
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

  private UpdateBucketSearchResult splitRootBucket(
      final int keyIndex,
      final CacheEntry bucketEntry,
      Bucket bucketToSplit,
      final boolean splitLeaf,
      final int indexToSplit,
      final EdgeKey separationKey,
      final List<byte[]> rightEntries,
      final AtomicOperation atomicOperation)
      throws IOException {
    final List<byte[]> leftEntries = new ArrayList<>(indexToSplit);

    for (var i = 0; i < indexToSplit; i++) {
      leftEntries.add(bucketToSplit.getRawEntry(i, serializerFactory));
    }

    final CacheEntry leftBucketEntry;
    final CacheEntry rightBucketEntry;

    try (final var entryPointCacheEntry =
        loadPageForWrite(atomicOperation, fileHandler, ENTRY_POINT_INDEX, true)) {
      final var entryPoint = new EntryPoint(entryPointCacheEntry);
      var pageSize = entryPoint.getPagesSize();

      final var filledUpTo = (int) getFilledUpTo(atomicOperation, fileHandler);

      if (pageSize < filledUpTo - 1) {
        pageSize++;
        leftBucketEntry = loadPageForWrite(atomicOperation, fileHandler, pageSize, false);
      } else {
        assert pageSize == filledUpTo - 1;
        leftBucketEntry = addPage(atomicOperation, fileHandler);
        pageSize = leftBucketEntry.getPageIndex();
      }

      if (pageSize < filledUpTo) {
        pageSize++;
        rightBucketEntry = loadPageForWrite(atomicOperation, fileHandler, pageSize, false);
      } else {
        assert pageSize == filledUpTo;
        rightBucketEntry = addPage(atomicOperation, fileHandler);
        pageSize = rightBucketEntry.getPageIndex();
      }

      entryPoint.setPagesSize(pageSize);
    }

    try {
      final var newLeftBucket = new Bucket(leftBucketEntry);
      newLeftBucket.init(splitLeaf);
      newLeftBucket.addAll(leftEntries);

      if (splitLeaf) {
        newLeftBucket.setRightSibling(rightBucketEntry.getPageIndex());
      }

    } finally {
      leftBucketEntry.close();
    }

    try {
      final var newRightBucket = new Bucket(rightBucketEntry);
      newRightBucket.init(splitLeaf);
      newRightBucket.addAll(rightEntries);

      if (splitLeaf) {
        newRightBucket.setLeftSibling(leftBucketEntry.getPageIndex());
      }
    } finally {
      rightBucketEntry.close();
    }

    bucketToSplit = new Bucket(bucketEntry);
    bucketToSplit.shrink(0, serializerFactory);
    if (splitLeaf) {
      bucketToSplit.switchBucketType();
    }

    bucketToSplit.addNonLeafEntry(
        0,
        leftBucketEntry.getPageIndex(),
        rightBucketEntry.getPageIndex(),
        EdgeKeySerializer.INSTANCE.serializeNativeAsWhole(serializerFactory, separationKey,
            (Object[]) null),
        true);

    final var resultPath = new IntArrayList(8);
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

  private void updateSize(final long diffSize, final AtomicOperation atomicOperation)
      throws IOException {
    try (final var entryPointCacheEntry =
        loadPageForWrite(atomicOperation, fileHandler, ENTRY_POINT_INDEX, true)) {
      final var entryPoint = new EntryPoint(entryPointCacheEntry);
      entryPoint.setTreeSize(entryPoint.getTreeSize() + diffSize);
    }
  }

  // ---- Tombstone GC + split insert helpers ----

  /**
   * Inserts a serialized leaf entry into the B-tree, attempting tombstone GC
   * before splitting if the target bucket is full. This is the shared
   * implementation used by both {@code put()} and {@code remove()} (for
   * cross-tx tombstone insertion).
   *
   * <p>The method attempts GC at most once per call. If GC frees space, the
   * insert is retried on the filtered bucket. If the bucket is still full
   * (either GC found nothing to remove, or the freed space is insufficient),
   * the normal split path proceeds.
   *
   * <p>The {@code keyBucketCacheEntry} is closed before this method returns.
   *
   * @param key the edge key being inserted (used for re-deriving insertion
   *     index after GC rebuild)
   * @param serializedKey pre-serialized key bytes
   * @param serializedValue pre-serialized value bytes
   * @param bucketSearchResult the search result pointing to the target bucket
   * @param keyBucketCacheEntry the cache entry for the target bucket page
   * @param keyBucket the bucket wrapper
   * @param insertionIndex the initial insertion index within the bucket
   * @param atomicOperation the enclosing atomic operation
   */
  private void insertLeafWithGCAndSplit(
      final EdgeKey key,
      final byte[] serializedKey,
      final byte[] serializedValue,
      UpdateBucketSearchResult bucketSearchResult,
      CacheEntry keyBucketCacheEntry,
      Bucket keyBucket,
      int insertionIndex,
      final AtomicOperation atomicOperation) throws IOException {

    // GC flag: attempt tombstone filtering at most once per insert.
    // If GC frees enough space, the retry succeeds without splitting.
    // If not, the normal split proceeds on the already-filtered bucket.
    boolean gcAttempted = false;

    while (!keyBucket.addLeafEntry(insertionIndex, serializedKey, serializedValue)) {
      // Attempt tombstone GC before splitting — removes tombstones
      // below the global LWM that have no lingering snapshot entries.
      // The LWM is computed once per GC attempt; this is safe because
      // we hold the exclusive lock — no concurrent modifications.
      if (!gcAttempted) {
        gcAttempted = true;
        final long lwm = storage.computeGlobalLowWaterMark();
        assert lwm > 0 : "Global LWM must be positive, got " + lwm;
        final int componentId = AbstractWriteCache.extractFileId(getFileId());
        final int removedCount =
            filterAndRebuildBucket(keyBucket, lwm, componentId, atomicOperation);
        if (removedCount > 0) {
          // Tree size accounting: updateSize(-removedCount) is correct for
          // all callers — see put() and remove() for the full case analysis.
          // The caller's own size adjustment (sizeDiff for put, +1 for
          // cross-tx remove) is applied separately after this method returns.
          updateSize(-removedCount, atomicOperation);

          // Re-derive insertion index — bucket contents changed.
          insertionIndex = keyBucket.find(key, serializerFactory);
          if (insertionIndex >= 0) {
            throw new StorageException(storage.getName(),
                "Key exists in bucket after GC rebuild in ["
                    + getName() + "]; index=" + insertionIndex);
          }
          insertionIndex = -insertionIndex - 1;

          // Retry the insert on the filtered bucket.
          continue;
        }
      }

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

        keyBucketCacheEntry = loadPageForWrite(atomicOperation, fileHandler, pageIndex, true);
      }

      //noinspection ObjectAllocationInLoop
      keyBucket = new Bucket(keyBucketCacheEntry);
    }

    keyBucketCacheEntry.close();
  }

  // ---- Tombstone GC helpers (used during leaf page split) ----

  /**
   * Checks whether any snapshot entries exist for the given logical edge.
   * Uses lazy iteration — only calls {@code hasNext()} so at most one entry
   * is materialized. This is safe because we only need to know whether any
   * snapshot entry exists, not how many.
   *
   * <p>The edge snapshot index merges both the local (per-atomic-operation)
   * buffer and the shared (global) snapshot index. This means a single check
   * covers both — no separate local/shared queries are needed.
   */
  private boolean hasEdgeSnapshotEntries(
      final int componentId,
      final long ridBagId,
      final int targetCollection,
      final long targetPosition,
      final AtomicOperation atomicOp) {
    final var lowerKey = new EdgeSnapshotKey(
        componentId, ridBagId, targetCollection, targetPosition, Long.MIN_VALUE);
    final var upperKey = new EdgeSnapshotKey(
        componentId, ridBagId, targetCollection, targetPosition, Long.MAX_VALUE);
    return atomicOp.edgeSnapshotSubMapDescending(lowerKey, upperKey)
        .iterator().hasNext();
  }

  /**
   * Determines whether a B-tree entry is a tombstone that can be safely
   * removed during GC. A tombstone is removable when all three conditions
   * hold:
   * <ol>
   *   <li>The entry is a tombstone ({@code value.tombstone()}).</li>
   *   <li>Its timestamp is strictly below the low-water mark
   *       ({@code key.ts < lwm}). Strict inequality is required because
   *       a transaction with {@code commitTs == lwm} is still active and
   *       may need to see the tombstone.</li>
   *   <li>No snapshot entries exist for the same logical edge — if a
   *       snapshot entry lingers (cleanup is lazy/threshold-based),
   *       removing the tombstone would let a stale reader fall through
   *       to the snapshot index and resurrect a deleted edge.</li>
   * </ol>
   */
  private boolean isRemovableTombstone(
      final EdgeKey key,
      final LinkBagValue value,
      final long lwm,
      final int componentId,
      final AtomicOperation atomicOp) {
    if (!value.tombstone()) {
      return false;
    }
    if (key.ts >= lwm) {
      return false;
    }
    return !hasEdgeSnapshotEntries(
        componentId, key.ridBagId, key.targetCollection, key.targetPosition, atomicOp);
  }

  /**
   * Filters removable tombstones from a leaf bucket and rebuilds it in place
   * with only the surviving entries. Returns the number of removed tombstones
   * (0 if none were found, in which case the bucket is not modified).
   *
   * <p>The in-place rebuild uses the same {@code shrink(0)} + {@code addAll()}
   * pattern as {@code splitRootBucket()} — both operate under an atomic
   * operation that guarantees the page write is crash-safe.
   */
  private int filterAndRebuildBucket(
      final Bucket keyBucket,
      final long lwm,
      final int componentId,
      final AtomicOperation atomicOp) {
    assert AbstractWriteCache.extractFileId(fileHandler.fileId()) != 0
        : "fileId must be assigned before GC (fileId=" + fileHandler.fileId() + ")";
    assert keyBucket.isLeaf()
        : "filterAndRebuildBucket must only be called on leaf buckets";

    final int bucketSize = keyBucket.size();
    final List<byte[]> survivors = new ArrayList<>(bucketSize);
    int removedCount = 0;

    for (int i = 0; i < bucketSize; i++) {
      final var key = keyBucket.getKey(i, serializerFactory);
      final var value = keyBucket.getValue(i, serializerFactory);

      if (isRemovableTombstone(key, value, lwm, componentId, atomicOp)) {
        removedCount++;
      } else {
        survivors.add(keyBucket.getRawEntry(i, serializerFactory));
      }
    }

    if (removedCount == 0) {
      return 0;
    }

    assert removedCount + survivors.size() == bucketSize
        : "Partition invariant violated: removed (" + removedCount
            + ") + survivors (" + survivors.size()
            + ") != original size (" + bucketSize + ")";

    keyBucket.shrink(0, serializerFactory);
    assert keyBucket.size() == 0
        : "Bucket must be empty after shrink(0), got " + keyBucket.size();
    keyBucket.addAll(survivors);
    assert keyBucket.size() == survivors.size()
        : "Bucket size after rebuild (" + keyBucket.size()
            + ") must equal survivor count (" + survivors.size() + ")";

    return removedCount;
  }

  private UpdateBucketSearchResult findBucketForUpdate(
      final EdgeKey key, final AtomicOperation atomicOperation) throws IOException {
    var pageIndex = ROOT_INDEX;

    final var path = new IntArrayList(8);
    final var itemIndexes = new IntArrayList(8);

    while (true) {
      if (path.size() > MAX_PATH_LENGTH) {
        throw new StorageException(storage.getName(),
            "We reached max level of depth of SBTree but still found nothing, seems like tree is in"
                + " corrupted state. You should rebuild index related to given query.");
      }

      path.add(pageIndex);

      try (final var bucketEntry = loadPageForRead(atomicOperation, fileHandler, pageIndex)) {
        final var keyBucket = new Bucket(bucketEntry);
        final var index = keyBucket.find(key, serializerFactory);

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

  private BucketSearchResult findBucket(final EdgeKey key, final AtomicOperation atomicOperation)
      throws IOException {
    long pageIndex = ROOT_INDEX;

    var depth = 0;
    while (true) {
      depth++;
      if (depth > MAX_PATH_LENGTH) {
        throw new StorageException(storage.getName(),
            "We reached max level of depth of SBTree but still found nothing, seems like tree is in"
                + " corrupted state. You should rebuild index related to given query.");
      }

      try (final var bucketEntry = loadPageForRead(atomicOperation, fileHandler, pageIndex)) {
        final var keyBucket = new Bucket(bucketEntry);
        final var index = keyBucket.find(key, serializerFactory);

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
   * Removes an edge entry. For cross-transaction removes (the existing entry has
   * a different {@code ts}), the old value is preserved in the snapshot index and
   * the entry is replaced by a tombstone with the new {@code ts}. For
   * same-transaction removes (same {@code ts}), the entry is physically deleted
   * — the caller created it in this transaction, so no other transaction can see
   * it and no tombstone is needed.
   *
   * <p>Tree size: cross-tx remove is net zero (remove + tombstone insert),
   * same-tx remove decrements by 1 (physical delete).
   *
   * @param key the edge key with the caller's commitTs as {@code ts}
   * @return the old value before removal, or {@code null} if no entry exists
   *     for this logical edge
   */
  public LinkBagValue remove(final AtomicOperation atomicOperation, final EdgeKey key) {
    return calculateInsideComponentOperation(
        atomicOperation,
        operation -> {
          acquireExclusiveLock();
          try {
            // Find existing entry by prefix lookup (regardless of ts)
            final var existing = findCurrentEntryInternal(
                atomicOperation, key.ridBagId, key.targetCollection, key.targetPosition);
            if (existing == null) {
              return null;
            }

            final var oldKey = existing.first();
            final var oldValue = existing.second();

            // Already deleted — nothing to remove
            if (oldValue.tombstone()) {
              return null;
            }

            if (oldKey.ts != key.ts) {
              // Cross-transaction remove: preserve old version in snapshot index,
              // then replace the entry with a tombstone carrying the new ts.
              if (key.ts <= oldKey.ts) {
                throw new StorageException(storage.getName(),
                    "Version monotonicity violated in link bag btree [" + getName()
                        + "]: newTs=" + key.ts + " <= oldTs=" + oldKey.ts);
              }

              preserveInSnapshot(atomicOperation, oldKey, oldValue);
              removeEntryByKey(atomicOperation, oldKey);

              // Tombstone preserves the edge data, just marks as deleted
              final var tombstoneValue = new LinkBagValue(
                  oldValue.counter(), oldValue.secondaryCollectionId(),
                  oldValue.secondaryPosition(), true);
              assert tombstoneValue.tombstone()
                  : "Constructed tombstone value must have tombstone flag set";

              // Insert tombstone with new ts. removeEntryByKey decremented size
              // by 1; the insert below increments it back — net zero.
              final var serializedKey =
                  EdgeKeySerializer.INSTANCE.serializeNativeAsWhole(serializerFactory, key,
                      (Object[]) null);
              final var serializedValue =
                  LinkBagValueSerializer.INSTANCE.serializeNativeAsWhole(serializerFactory,
                      tombstoneValue, (Object[]) null);

              var bucketSearchResult = findBucketForUpdate(key, atomicOperation);
              assert bucketSearchResult.getItemIndex() < 0
                  : "Tombstone key must not exist in tree after removeEntryByKey; itemIndex="
                      + bucketSearchResult.getItemIndex();

              var keyBucketCacheEntry =
                  loadPageForWrite(
                      atomicOperation, fileHandler, bucketSearchResult.getLastPathItem(), true);
              var keyBucket = new Bucket(keyBucketCacheEntry);

              int insertionIndex = -bucketSearchResult.getItemIndex() - 1;

              insertLeafWithGCAndSplit(
                  key, serializedKey, serializedValue,
                  bucketSearchResult, keyBucketCacheEntry, keyBucket,
                  insertionIndex, atomicOperation);
              updateSize(1, atomicOperation);
            } else {
              // Same-transaction remove: physically delete the entry. The caller
              // created it in this transaction (same commitTs), so no other
              // transaction can see it — no snapshot preservation or tombstone
              // needed.
              removeEntryByKey(atomicOperation, oldKey);
            }

            return oldValue;
          } finally {
            releaseExclusiveLock();
          }
        });
  }

  public Stream<RawPair<EdgeKey, LinkBagValue>> iterateEntriesMinor(
      final EdgeKey key, final boolean inclusive, final boolean ascSortOrder,
      AtomicOperation atomicOperation) {
    return atomicOperationsManager.readUnderLock(this, () -> {
      if (!ascSortOrder) {
        return StreamSupport.stream(
            iterateEntriesMinorDesc(key, inclusive, atomicOperation), false);
      }

      return StreamSupport.stream(
          iterateEntriesMinorAsc(key, inclusive, atomicOperation), false);
    });
  }

  public Stream<RawPair<EdgeKey, LinkBagValue>> iterateEntriesMajor(
      final EdgeKey key, final boolean inclusive, final boolean ascSortOrder,
      AtomicOperation atomicOperation) {
    return atomicOperationsManager.readUnderLock(this, () -> {
      if (ascSortOrder) {
        return StreamSupport.stream(
            iterateEntriesMajorAsc(key, inclusive, atomicOperation), false);
      }
      return StreamSupport.stream(
          iterateEntriesMajorDesc(key, inclusive, atomicOperation), false);
    });
  }

  public Stream<RawPair<EdgeKey, LinkBagValue>> streamEntriesBetween(
      final EdgeKey keyFrom,
      final boolean fromInclusive,
      final EdgeKey keyTo,
      final boolean toInclusive,
      final boolean ascSortOrder, AtomicOperation atomicOperation) {
    return atomicOperationsManager.readUnderLock(this, () -> {
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
    });
  }

  public Spliterator<RawPair<EdgeKey, LinkBagValue>> spliteratorEntriesBetween(
      final EdgeKey keyFrom,
      final boolean fromInclusive,
      final EdgeKey keyTo,
      final boolean toInclusive,
      final boolean ascSortOrder, AtomicOperation atomicOperation) {
    return atomicOperationsManager.readUnderLock(this, () -> {
      if (ascSortOrder) {
        return iterateEntriesBetweenAscOrder(keyFrom, fromInclusive, keyTo, toInclusive,
            atomicOperation);
      } else {
        return iterateEntriesBetweenDescOrder(keyFrom, fromInclusive, keyTo, toInclusive,
            atomicOperation);
      }
    });
  }

  private Spliterator<RawPair<EdgeKey, LinkBagValue>> iterateEntriesMinorDesc(
      EdgeKey key, final boolean inclusive, AtomicOperation atomicOperation) {
    return new SpliteratorBackward(this, null, key, false, inclusive, atomicOperation);
  }

  private Spliterator<RawPair<EdgeKey, LinkBagValue>> iterateEntriesMinorAsc(
      EdgeKey key, final boolean inclusive, AtomicOperation atomicOperation) {
    return new SpliteratorForward(this, null, key, false,
        inclusive, atomicOperation);
  }

  private Spliterator<RawPair<EdgeKey, LinkBagValue>> iterateEntriesMajorAsc(
      EdgeKey key, final boolean inclusive, AtomicOperation atomicOperation) {
    return new SpliteratorForward(this, key, null, inclusive, false,
        atomicOperation);
  }

  private Spliterator<RawPair<EdgeKey, LinkBagValue>> iterateEntriesMajorDesc(
      EdgeKey key, final boolean inclusive, AtomicOperation atomicOperation) {
    return new SpliteratorBackward(this, key, null, inclusive, false,
        atomicOperation);
  }

  private Spliterator<RawPair<EdgeKey, LinkBagValue>> iterateEntriesBetweenAscOrder(
      EdgeKey keyFrom, final boolean fromInclusive, EdgeKey keyTo, final boolean toInclusive,
      AtomicOperation atomicOperation) {
    return new SpliteratorForward(this, keyFrom, keyTo, fromInclusive, toInclusive,
        atomicOperation);
  }

  private Spliterator<RawPair<EdgeKey, LinkBagValue>> iterateEntriesBetweenDescOrder(
      EdgeKey keyFrom, final boolean fromInclusive, EdgeKey keyTo, final boolean toInclusive,
      AtomicOperation atomicOperation) {
    return new SpliteratorBackward(this, keyFrom, keyTo, fromInclusive, toInclusive,
        atomicOperation);
  }

  public void fetchNextCachePortionForward(SpliteratorForward iter,
      AtomicOperation atomicOperation) {
    final EdgeKey lastKey;
    if (!iter.getDataCache().isEmpty()) {
      lastKey = iter.getDataCache().getLast().first();
    } else {
      lastKey = null;
    }

    iter.clearCache();

    try {
      atomicOperationsManager.executeReadOperation(SharedLinkBagBTree.this, () -> {
        if (iter.getPageIndex() > -1) {
          if (readKeysFromBucketsForward(iter, atomicOperation)) {
            return null;
          }
        }

        // this can only happen if page LSN does not equal to stored LSN or index of
        // current iterated page equals to -1
        // so we only started iteration
        if (iter.getDataCache().isEmpty()) {
          // iteration just started
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
                return null;
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
          readKeysFromBucketsForward(iter, atomicOperation);
        }
        return null;
      });
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new StorageException(storage.getName(), "Error during entity iteration"),
          e, storage.getName());
    }
  }

  private boolean readKeysFromBucketsForward(
      SpliteratorForward iter, AtomicOperation atomicOperation) throws IOException {
    var cacheEntry = loadPageForRead(atomicOperation, fileHandler, iter.getPageIndex());
    try {
      var bucket = new Bucket(cacheEntry);
      if (iter.getLastLSN() == null
          || (bucket.getLsn().equals(iter.getLastLSN()) && atomicOperation == null)) {
        while (true) {
          var bucketSize = bucket.size();
          if (iter.getItemIndex() >= bucketSize) {
            iter.setPageIndex((int) bucket.getRightSibling());

            if (iter.getPageIndex() < 0) {
              return true;
            }

            iter.setItemIndex(0);
            cacheEntry.close();

            cacheEntry = loadPageForRead(atomicOperation, fileHandler, iter.getPageIndex());
            bucket = new Bucket(cacheEntry);

            bucketSize = bucket.size();
          }

          iter.setLastLSN(bucket.getLsn());

          for (;
              iter.getItemIndex() < bucketSize && iter.getDataCache().size() < 10;
              iter.incrementItemIndex()) {
            @SuppressWarnings("ObjectAllocationInLoop")
            var entry = bucket.getEntry(iter.getItemIndex(), serializerFactory);

            if (iter.getToKey() != null) {
              if (iter.isToKeyInclusive()) {
                if (entry.getKey().compareTo(iter.getToKey()) > 0) {
                  return true;
                }
              } else if (entry.getKey().compareTo(iter.getToKey()) >= 0) {
                return true;
              }
            }

            // SI visibility: resolve entry, skip invisible/tombstone.
            // When atomicOperation is null (non-transactional read),
            // skip visibility resolution and add entry directly.
            // IMPORTANT: cache entries must use the ORIGINAL B-tree key
            // (not the resolved snapshot key) to preserve correct iteration
            // position tracking. fetchNextCachePortionForward uses
            // dataCache.getLast().first() (the last key) to re-position
            // the iterator after cache exhaustion — a snapshot key with a
            // lower ts would cause re-reading the same entry forever.
            if (atomicOperation != null) {
              @SuppressWarnings("ObjectAllocationInLoop")
              var visible =
                  resolveVisibleEntry(entry.getKey(), entry.getValue(), atomicOperation);
              if (visible != null) {
                //noinspection ObjectAllocationInLoop
                iter.getDataCache()
                    .add(new RawPair<>(entry.getKey(), visible.second()));
              }
            } else {
              //noinspection ObjectAllocationInLoop
              iter.getDataCache().add(new RawPair<>(entry.getKey(), entry.getValue()));
            }
          }

          if (iter.getDataCache().size() >= 10) {
            return true;
          }
        }
      }
    } finally {
      cacheEntry.close();
    }

    return false;
  }

  public void fetchNextCachePortionBackward(SpliteratorBackward iter,
      AtomicOperation atomicOperation) {
    final EdgeKey lastKey;
    if (iter.getDataCache().isEmpty()) {
      lastKey = null;
    } else {
      lastKey = iter.getDataCache().getLast().first();
    }

    iter.clearCache();

    try {
      atomicOperationsManager.executeReadOperation(SharedLinkBagBTree.this, () -> {
        if (iter.getPageIndex() > -1) {
          if (readKeysFromBucketsBackward(iter, atomicOperation)) {
            return null;
          }
        }

        // this can only happen if page LSN does not equal to stored LSN or index of
        // current iterated page equals to -1
        // so we only started iteration
        if (iter.getDataCache().isEmpty()) {
          // iteration just started
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
                return null;
              }
            }

          } else {
            final var bucketSearchResult = findBucket(lastKey, atomicOperation);

            iter.setPageIndex((int) bucketSearchResult.getPageIndex());
            if (bucketSearchResult.getItemIndex() >= 0) {
              iter.setItemIndex(bucketSearchResult.getItemIndex() - 1);
            } else {
              iter.setPageIndex(-bucketSearchResult.getItemIndex() - 2);
            }
          }
          iter.setLastLSN(null);
          readKeysFromBucketsBackward(iter, atomicOperation);
        }
        return null;
      });
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new StorageException(storage.getName(), "Error during entity iteration"),
          e, storage.getName());
    }
  }

  private boolean readKeysFromBucketsBackward(
      SpliteratorBackward iter, AtomicOperation atomicOperation) throws IOException {
    var cacheEntry = loadPageForRead(atomicOperation, fileHandler, iter.getPageIndex());
    try {
      var bucket = new Bucket(cacheEntry);
      if (iter.getLastLSN() == null
          || (bucket.getLsn().equals(iter.getLastLSN()) && atomicOperation == null)) {
        while (true) {
          if (iter.getItemIndex() < 0) {
            iter.setPageIndex((int) bucket.getLeftSibling());

            if (iter.getPageIndex() < 0) {
              return true;
            }

            cacheEntry.close();

            cacheEntry = loadPageForRead(atomicOperation, fileHandler, iter.getPageIndex());
            bucket = new Bucket(cacheEntry);
            final var bucketSize = bucket.size();
            iter.setItemIndex(bucketSize - 1);
          }

          iter.setLastLSN(bucket.getLsn());

          for (; iter.getItemIndex() >= 0 && iter.getDataCache().size() < 10;
              iter.decItemIndex()) {
            @SuppressWarnings("ObjectAllocationInLoop")
            var entry = bucket.getEntry(iter.getItemIndex(), serializerFactory);

            if (iter.getFromKey() != null) {
              if (iter.isFromKeyInclusive()) {
                if (entry.getKey().compareTo(iter.getFromKey()) < 0) {
                  return true;
                }
              } else if (entry.getKey().compareTo(iter.getFromKey()) <= 0) {
                return true;
              }
            }

            // SI visibility: resolve entry, skip invisible/tombstone.
            // See forward method comment for why we use the original
            // B-tree key instead of the resolved snapshot key.
            if (atomicOperation != null) {
              @SuppressWarnings("ObjectAllocationInLoop")
              var visible =
                  resolveVisibleEntry(entry.getKey(), entry.getValue(), atomicOperation);
              if (visible != null) {
                //noinspection ObjectAllocationInLoop
                iter.getDataCache()
                    .add(new RawPair<>(entry.getKey(), visible.second()));
              }
            } else {
              //noinspection ObjectAllocationInLoop
              iter.getDataCache().add(new RawPair<>(entry.getKey(), entry.getValue()));
            }
          }

          if (iter.getDataCache().size() >= 10) {
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
