package com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.util.RawPairObjectInteger;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurableComponent;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Spliterator;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.annotation.Nullable;

public final class SharedLinkBagBTree extends DurableComponent {

  private static final int MAX_PATH_LENGTH =
      GlobalConfiguration.BTREE_MAX_DEPTH.getValueAsInteger();

  private static final int ENTRY_POINT_INDEX = 0;
  private static final int ROOT_INDEX = 1;

  private volatile long fileId;
  private final BinarySerializerFactory serializerFactory;

  public SharedLinkBagBTree(final AbstractStorage storage, final String name,
      final String fileExtension) {
    super(storage, name, fileExtension, name + fileExtension);

    this.serializerFactory = storage.getComponentsFactory().binarySerializerFactory;
  }

  public long getFileId() {
    return fileId;
  }

  public void create(final AtomicOperation atomicOperation) {
    executeInsideComponentOperation(
        atomicOperation,
        (operation) -> {
          acquireExclusiveLock();
          try {
            fileId = addFile(atomicOperation, getFullName());

            try (final var entryPointCacheEntry = addPage(atomicOperation, fileId)) {
              final var entryPoint = new EntryPoint(entryPointCacheEntry);
              entryPoint.init();
            }

            try (final var rootCacheEntry = addPage(atomicOperation, fileId)) {
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
      fileId = openFile(atomicOperation, getFullName());
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new StorageException(storage.getName(),
              "Exception during loading of rid bag " + getFullName()), e, storage.getName());
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
          } finally {
            releaseExclusiveLock();
          }
        });
  }

  public int get(final EdgeKey key, AtomicOperation atomicOperation) {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final var bucketSearchResult = findBucket(key, atomicOperation);
        if (bucketSearchResult.getItemIndex() < 0) {
          return -1;
        }

        final var pageIndex = bucketSearchResult.getPageIndex();

        try (final var keyBucketCacheEntry =
            loadPageForRead(atomicOperation, fileId, pageIndex)) {
          final var keyBucket = new Bucket(keyBucketCacheEntry);
          return keyBucket.getValue(bucketSearchResult.getItemIndex(), serializerFactory);
        }
      } finally {
        releaseSharedLock();
      }
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new StorageException(storage.getName(),
              "Error during retrieving  of value for rid bag with name " + getName()),
          e, storage.getName());
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  public boolean put(final AtomicOperation atomicOperation, final EdgeKey key, final int value) {
    return calculateInsideComponentOperation(
        atomicOperation,
        operation -> {
          final boolean result;
          acquireExclusiveLock();
          try {
            final var serializedKey =
                EdgeKeySerializer.INSTANCE.serializeNativeAsWhole(serializerFactory, key,
                    (Object[]) null);
            var bucketSearchResult = findBucketForUpdate(key, atomicOperation);

            var keyBucketCacheEntry =
                loadPageForWrite(
                    atomicOperation, fileId, bucketSearchResult.getLastPathItem(), true);
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
                IntSerializer.INSTANCE.serializeNativeAsWhole(serializerFactory, value,
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
              keyBucket = new Bucket(keyBucketCacheEntry);
            }

            keyBucketCacheEntry.close();

            if (sizeDiff != 0) {
              updateSize(sizeDiff, atomicOperation);
            }
          } finally {
            releaseExclusiveLock();
          }

          return result;
        });
  }

  @Nullable
  public EdgeKey firstKey(AtomicOperation atomicOperation) {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final var searchResult = firstItem(atomicOperation);
        if (searchResult.isEmpty()) {
          return null;
        }

        final var result = searchResult.get();

        try (final var cacheEntry =
            loadPageForRead(atomicOperation, fileId, result.getPageIndex())) {
          final var bucket = new Bucket(cacheEntry);
          return bucket.getKey(result.getItemIndex(), serializerFactory);
        }
      } finally {
        releaseSharedLock();
      }
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new StorageException(storage.getName(),
              "Error during finding first key in btree [" + getName() + "]"), e, storage.getName());
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  private Optional<BucketSearchResult> firstItem(final AtomicOperation atomicOperation)
      throws IOException {
    final var path = new LinkedList<PagePathItemUnit>();

    long bucketIndex = ROOT_INDEX;

    var cacheEntry = loadPageForRead(atomicOperation, fileId, bucketIndex);
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

        cacheEntry = loadPageForRead(atomicOperation, fileId, bucketIndex);
        //noinspection ObjectAllocationInLoop
        bucket = new Bucket(cacheEntry);
      }
    } finally {
      cacheEntry.close();
    }
  }

  @Nullable
  public EdgeKey lastKey(AtomicOperation atomicOperation) {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final var searchResult = lastItem(atomicOperation);
        if (searchResult.isEmpty()) {
          return null;
        }

        final var result = searchResult.get();

        try (final var cacheEntry =
            loadPageForRead(atomicOperation, fileId, result.getPageIndex())) {
          final var bucket = new Bucket(cacheEntry);
          return bucket.getKey(result.getItemIndex(), serializerFactory);
        }
      } finally {
        releaseSharedLock();
      }
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new StorageException(storage.getName(),
              "Error during finding last key in btree [" + getName() + "]"),
          e, storage.getName());
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  private Optional<BucketSearchResult> lastItem(final AtomicOperation atomicOperation)
      throws IOException {
    final var path = new LinkedList<PagePathItemUnit>();

    long bucketIndex = ROOT_INDEX;

    var cacheEntry = loadPageForRead(atomicOperation, fileId, bucketIndex);

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

        cacheEntry = loadPageForRead(atomicOperation, fileId, bucketIndex);

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
        loadPageForWrite(atomicOperation, fileId, ENTRY_POINT_INDEX, true)) {
      final var entryPoint = new EntryPoint(entryPointCacheEntry);
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
              loadPageForWrite(atomicOperation, fileId, rightSiblingPageIndex, true)) {
            final var rightSiblingBucket = new Bucket(rightSiblingBucketEntry);
            rightSiblingBucket.setLeftSibling(rightBucketEntry.getPageIndex());
          }
        }
      }

      long parentIndex = path.getInt(path.size() - 2);
      var parentCacheEntry = loadPageForWrite(atomicOperation, fileId, parentIndex, true);
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

            parentCacheEntry = loadPageForWrite(atomicOperation, fileId, parentIndex, true);
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
        loadPageForWrite(atomicOperation, fileId, ENTRY_POINT_INDEX, true)) {
      final var entryPoint = new EntryPoint(entryPointCacheEntry);
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
        loadPageForWrite(atomicOperation, fileId, ENTRY_POINT_INDEX, true)) {
      final var entryPoint = new EntryPoint(entryPointCacheEntry);
      entryPoint.setTreeSize(entryPoint.getTreeSize() + diffSize);
    }
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

      try (final var bucketEntry = loadPageForRead(atomicOperation, fileId, pageIndex)) {
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

      try (final var bucketEntry = loadPageForRead(atomicOperation, fileId, pageIndex)) {
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

  public int remove(final AtomicOperation atomicOperation, final EdgeKey key) {
    return calculateInsideComponentOperation(
        atomicOperation,
        operation -> {
          acquireExclusiveLock();
          try {
            final int removedValue;
            final var bucketSearchResult = findBucket(key, atomicOperation);

            if (bucketSearchResult.getItemIndex() < 0) {
              return -1;
            }

            final var serializedKey = EdgeKeySerializer.INSTANCE.serializeNativeAsWhole(
                serializerFactory, key);
            final byte[] rawValue;
            try (final var keyBucketCacheEntry =
                loadPageForWrite(
                    atomicOperation, fileId, bucketSearchResult.getPageIndex(), true)) {
              final var keyBucket = new Bucket(keyBucketCacheEntry);
              rawValue = keyBucket.getRawValue(bucketSearchResult.getItemIndex(),
                  serializerFactory);
              keyBucket.removeLeafEntry(
                  bucketSearchResult.getItemIndex(), serializedKey.length, rawValue.length);
              updateSize(-1, atomicOperation);
            }

            removedValue = IntSerializer.INSTANCE.deserializeNativeObject(serializerFactory,
                rawValue, 0);
            return removedValue;
          } finally {
            releaseExclusiveLock();
          }
        });
  }

  public Stream<RawPairObjectInteger<EdgeKey>> iterateEntriesMinor(
      final EdgeKey key, final boolean inclusive, final boolean ascSortOrder,
      AtomicOperation atomicOperation) {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        if (!ascSortOrder) {
          return StreamSupport.stream(iterateEntriesMinorDesc(key, inclusive, atomicOperation),
              false);
        }

        return StreamSupport.stream(iterateEntriesMinorAsc(key, inclusive, atomicOperation), false);
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  public Stream<RawPairObjectInteger<EdgeKey>> iterateEntriesMajor(
      final EdgeKey key, final boolean inclusive, final boolean ascSortOrder,
      AtomicOperation atomicOperation) {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        if (ascSortOrder) {
          return StreamSupport.stream(iterateEntriesMajorAsc(key, inclusive, atomicOperation),
              false);
        }
        return StreamSupport.stream(iterateEntriesMajorDesc(key, inclusive, atomicOperation),
            false);
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  public Stream<RawPairObjectInteger<EdgeKey>> streamEntriesBetween(
      final EdgeKey keyFrom,
      final boolean fromInclusive,
      final EdgeKey keyTo,
      final boolean toInclusive,
      final boolean ascSortOrder, AtomicOperation atomicOperation) {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        if (ascSortOrder) {
          return StreamSupport.stream(
              iterateEntriesBetweenAscOrder(keyFrom, fromInclusive, keyTo, toInclusive,
                  atomicOperation), false);
        } else {
          return StreamSupport.stream(
              iterateEntriesBetweenDescOrder(keyFrom, fromInclusive, keyTo, toInclusive,
                  atomicOperation), false);
        }
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  public Spliterator<RawPairObjectInteger<EdgeKey>> spliteratorEntriesBetween(
      final EdgeKey keyFrom,
      final boolean fromInclusive,
      final EdgeKey keyTo,
      final boolean toInclusive,
      final boolean ascSortOrder, AtomicOperation atomicOperation) {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        if (ascSortOrder) {
          return
              iterateEntriesBetweenAscOrder(keyFrom, fromInclusive, keyTo, toInclusive,
                  atomicOperation);
        } else {
          return
              iterateEntriesBetweenDescOrder(keyFrom, fromInclusive, keyTo, toInclusive,
                  atomicOperation);
        }
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  private Spliterator<RawPairObjectInteger<EdgeKey>> iterateEntriesMinorDesc(
      EdgeKey key, final boolean inclusive, AtomicOperation atomicOperation) {
    return new SpliteratorBackward(this, null, key, false, inclusive, atomicOperation);
  }

  private Spliterator<RawPairObjectInteger<EdgeKey>> iterateEntriesMinorAsc(
      EdgeKey key, final boolean inclusive, AtomicOperation atomicOperation) {
    return new SpliteratorForward(this, null, key, false,
        inclusive, atomicOperation);
  }

  private Spliterator<RawPairObjectInteger<EdgeKey>> iterateEntriesMajorAsc(
      EdgeKey key, final boolean inclusive, AtomicOperation atomicOperation) {
    return new SpliteratorForward(this, key, null, inclusive, false,
        atomicOperation);
  }

  private Spliterator<RawPairObjectInteger<EdgeKey>> iterateEntriesMajorDesc(
      EdgeKey key, final boolean inclusive, AtomicOperation atomicOperation) {
    return new SpliteratorBackward(this, key, null, inclusive, false,
        atomicOperation);
  }

  private Spliterator<RawPairObjectInteger<EdgeKey>> iterateEntriesBetweenAscOrder(
      EdgeKey keyFrom, final boolean fromInclusive, EdgeKey keyTo, final boolean toInclusive,
      AtomicOperation atomicOperation) {
    return new SpliteratorForward(this, keyFrom, keyTo, fromInclusive, toInclusive,
        atomicOperation);
  }

  private Spliterator<RawPairObjectInteger<EdgeKey>> iterateEntriesBetweenDescOrder(
      EdgeKey keyFrom, final boolean fromInclusive, EdgeKey keyTo, final boolean toInclusive,
      AtomicOperation atomicOperation) {
    return new SpliteratorBackward(this, keyFrom, keyTo, fromInclusive, toInclusive,
        atomicOperation);
  }

  public void fetchNextCachePortionForward(SpliteratorForward iter,
      AtomicOperation atomicOperation) {
    final EdgeKey lastKey;
    if (!iter.getDataCache().isEmpty()) {
      lastKey = iter.getDataCache().getLast().first;
    } else {
      lastKey = null;
    }

    iter.clearCache();

    atomicOperationsManager.acquireReadLock(SharedLinkBagBTree.this);
    try {
      acquireSharedLock();
      try {
        if (iter.getPageIndex() > -1) {
          if (readKeysFromBucketsForward(iter, atomicOperation)) {
            return;
          }
        }

        // this can only happen if page LSN does not equal to stored LSN or index of current
        // iterated page equals to -1
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
          readKeysFromBucketsForward(iter, atomicOperation);
        }
      } finally {
        releaseSharedLock();
      }
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new StorageException(storage.getName(), "Error during entity iteration"),
          e, storage.getName());
    } finally {
      atomicOperationsManager.releaseReadLock(SharedLinkBagBTree.this);
    }
  }

  private boolean readKeysFromBucketsForward(
      SpliteratorForward iter, AtomicOperation atomicOperation) throws IOException {
    var cacheEntry = loadPageForRead(atomicOperation, fileId, iter.getPageIndex());
    try {
      var bucket = new Bucket(cacheEntry);
      if (iter.getLastLSN() == null
          || bucket.getLsn().equals(iter.getLastLSN()) && atomicOperation == null) {
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

            //noinspection ObjectAllocationInLoop
            iter.getDataCache().add(new RawPairObjectInteger<>(entry.getKey(), entry.getValue()));
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
      lastKey = iter.getDataCache().getLast().first;
    }

    iter.clearCache();

    atomicOperationsManager.acquireReadLock(SharedLinkBagBTree.this);
    try {
      acquireSharedLock();
      try {
        if (iter.getPageIndex() > -1) {
          if (readKeysFromBucketsBackward(iter, atomicOperation)) {
            return;
          }
        }

        // this can only happen if page LSN does not equal to stored LSN or index of current
        // iterated page equals to -1
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
                return;
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
      } finally {
        releaseSharedLock();
      }
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new StorageException(storage.getName(), "Error during entity iteration"),
          e, storage.getName());
    } finally {
      atomicOperationsManager.releaseReadLock(SharedLinkBagBTree.this);
    }
  }

  private boolean readKeysFromBucketsBackward(
      SpliteratorBackward iter, AtomicOperation atomicOperation) throws IOException {
    var cacheEntry = loadPageForRead(atomicOperation, fileId, iter.getPageIndex());
    try {
      var bucket = new Bucket(cacheEntry);
      if (iter.getLastLSN() == null
          || bucket.getLsn().equals(iter.getLastLSN()) && atomicOperation == null) {
        while (true) {
          if (iter.getItemIndex() < 0) {
            iter.setPageIndex((int) bucket.getLeftSibling());

            if (iter.getPageIndex() < 0) {
              return true;
            }

            cacheEntry.close();

            cacheEntry = loadPageForRead(atomicOperation, fileId, iter.getPageIndex());
            bucket = new Bucket(cacheEntry);
            final var bucketSize = bucket.size();
            iter.setItemIndex(bucketSize - 1);
          }

          iter.setLastLSN(bucket.getLsn());

          for (; iter.getItemIndex() >= 0 && iter.getDataCache().size() < 10; iter.decItemIndex()) {
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

            //noinspection ObjectAllocationInLoop
            iter.getDataCache().add(new RawPairObjectInteger<>(entry.getKey(), entry.getValue()));
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
