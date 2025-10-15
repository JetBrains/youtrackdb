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
package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.FileHandler;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitEndRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.FileCreatedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.FileDeletedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.UpdatePageRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.LinkBagBucketPointer;
import it.unimi.dsi.fastutil.ints.IntIntImmutablePair;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Note: all atomic operations methods are designed in context that all operations on single files
 * will be wrapped in shared lock.
 *
 * @since 12/3/13
 */
final class AtomicOperationBinaryTracking implements AtomicOperation {

  private final int storageId;
  private final long operationUnitId;

  private boolean rollback;

  private final Set<String> lockedObjects = new HashSet<>();
  private final Long2ObjectOpenHashMap<FileChanges> fileChanges = new Long2ObjectOpenHashMap<>();
  private final Object2LongOpenHashMap<String> newFileNamesId = new Object2LongOpenHashMap<>();
  private final LongOpenHashSet deletedFiles = new LongOpenHashSet();
  private final Object2LongOpenHashMap<String> deletedFileNameIdMap =
      new Object2LongOpenHashMap<>();

  private final ReadCache readCache;
  private final WriteCache writeCache;

  private final Map<String, AtomicOperationMetadata<?>> metadata = new LinkedHashMap<>();

  private int componentOperationsCount;

  /**
   * Pointers to ridbags deleted during current transaction. We can not reuse pointers if we delete
   * ridbag and then create new one inside of the same transaction.
   */
  private final Set<LinkBagBucketPointer> deletedBonsaiPointers = new HashSet<>();

  private final Map<IntIntImmutablePair, IntSet> deletedRecordPositions = new HashMap<>();

  AtomicOperationBinaryTracking(
      final long operationUnitId,
      final ReadCache readCache,
      final WriteCache writeCache,
      final int storageId) {
    newFileNamesId.defaultReturnValue(-1);
    deletedFileNameIdMap.defaultReturnValue(-1);

    this.storageId = storageId;
    this.operationUnitId = operationUnitId;

    this.readCache = readCache;
    this.writeCache = writeCache;
  }

  @Override
  public long getOperationUnitId() {
    return operationUnitId;
  }

  @Override
  public FileHandler loadFileHandler(long fileId) {
    return readCache.loadFileHandler(fileId);
  }

  @Nullable
  @Override
  public CacheEntry loadPageForWrite(
      FileHandler fileHandler, final long pageIndex, final int pageCount,
      final boolean verifyChecksum)
      throws IOException {
    assert pageCount > 0;
    var fileId = checkFileIdCompatibility(fileHandler.fileId(), storageId);

    if (deletedFiles.contains(fileId)) {
      throw new StorageException(writeCache.getStorageName(),
          "File with id " + fileId + " is deleted.");
    }
    final var changesContainer =
        fileChanges.computeIfAbsent(fileId, k -> new FileChanges());

    if (changesContainer.isNew) {
      if (pageIndex <= changesContainer.maxNewPageIndex) {
        return changesContainer.pageChangesMap.get(pageIndex);
      } else {
        return null;
      }
    } else {
      var pageChangesContainer = changesContainer.pageChangesMap.get(pageIndex);
      if (checkChangesFilledUpTo(changesContainer, pageIndex)) {
        if (pageChangesContainer == null) {
          final var delegate =
              readCache.loadForRead(fileHandler, pageIndex, writeCache, verifyChecksum);
          if (delegate != null) {
            pageChangesContainer = new CacheEntryChanges(verifyChecksum, this);
            changesContainer.pageChangesMap.put(pageIndex, pageChangesContainer);
            pageChangesContainer.delegate = delegate;
            return pageChangesContainer;
          }
        } else {
          if (pageChangesContainer.isNew) {
            return pageChangesContainer;
          } else {
            // Need to load the page again from cache for locking reasons
            pageChangesContainer.delegate =
                readCache.loadForRead(fileHandler, pageIndex, writeCache, verifyChecksum);
            return pageChangesContainer;
          }
        }
      }
    }
    return null;
  }

  @Nullable
  @Override
  public CacheEntry loadPageForRead(FileHandler fileHandler, final long pageIndex)
      throws IOException {

    var fileId = checkFileIdCompatibility(fileHandler.fileId(), storageId);

    if (deletedFiles.contains(fileId)) {
      throw new StorageException(writeCache.getStorageName(),
          "File with id " + fileId + " is deleted.");
    }

    final var changesContainer = fileChanges.get(fileId);
    if (changesContainer == null) {
      return readCache.loadForRead(fileHandler, pageIndex, writeCache, true);
    }

    if (changesContainer.isNew) {
      if (pageIndex <= changesContainer.maxNewPageIndex) {
        return changesContainer.pageChangesMap.get(pageIndex);
      } else {
        return null;
      }
    } else {
      final var pageChangesContainer =
          changesContainer.pageChangesMap.get(pageIndex);

      if (checkChangesFilledUpTo(changesContainer, pageIndex)) {
        if (pageChangesContainer == null) {
          return readCache.loadForRead(fileHandler, pageIndex, writeCache, true);
        } else {
          if (pageChangesContainer.isNew) {
            return pageChangesContainer;
          } else {
            // Need to load the page again from cache for locking reasons
            pageChangesContainer.delegate =
                readCache.loadForRead(fileHandler, pageIndex, writeCache, true);
            return pageChangesContainer;
          }
        }
      }
    }
    return null;
  }

  /**
   * Add metadata with given key inside of atomic operation. If metadata with the same key insist
   * inside of atomic operation it will be overwritten.
   *
   * @param metadata Metadata to add.
   * @see AtomicOperationMetadata
   */
  @Override
  public void addMetadata(final AtomicOperationMetadata<?> metadata) {
    this.metadata.put(metadata.getKey(), metadata);
  }

  /**
   * @param key Key of metadata which is looking for.
   * @return Metadata by associated key or <code>null</code> if such metadata is absent.
   */
  @Override
  public AtomicOperationMetadata<?> getMetadata(final String key) {
    return metadata.get(key);
  }

  /**
   * @return All keys and associated metadata contained inside of atomic operation
   */
  private Map<String, AtomicOperationMetadata<?>> getMetadata() {
    return Collections.unmodifiableMap(metadata);
  }

  @Override
  public void addDeletedRidBag(LinkBagBucketPointer rootPointer) {
    deletedBonsaiPointers.add(rootPointer);
  }

  @Override
  public Set<LinkBagBucketPointer> getDeletedBonsaiPointers() {
    return deletedBonsaiPointers;
  }

  @Override
  public CacheEntry addPage(long fileId) {
    fileId = checkFileIdCompatibility(fileId, storageId);

    if (deletedFiles.contains(fileId)) {
      throw new StorageException(writeCache.getStorageName(),
          "File with id " + fileId + " is deleted.");
    }

    final var changesContainer =
        fileChanges.computeIfAbsent(fileId, k -> new FileChanges());

    final var filledUpTo = internalFilledUpTo(fileId, changesContainer);

    var pageChangesContainer = changesContainer.pageChangesMap.get(filledUpTo);
    assert pageChangesContainer == null;

    pageChangesContainer = new CacheEntryChanges(false, this);
    pageChangesContainer.isNew = true;

    changesContainer.pageChangesMap.put(filledUpTo, pageChangesContainer);
    changesContainer.maxNewPageIndex = filledUpTo;
    pageChangesContainer.delegate =
        new CacheEntryImpl(
            fileId,
            (int) filledUpTo,
            new CachePointer(null, null, fileId, (int) filledUpTo),
            false,
            readCache);
    return pageChangesContainer;
  }

  @Override
  public void releasePageFromRead(final CacheEntry cacheEntry) {
    if (cacheEntry instanceof CacheEntryChanges) {
      releasePageFromWrite(cacheEntry);
    } else {
      readCache.releaseFromRead(cacheEntry);
    }
  }

  @Override
  public void releasePageFromWrite(final CacheEntry cacheEntry) {
    final var real = (CacheEntryChanges) cacheEntry;

    if (deletedFiles.contains(cacheEntry.getFileId())) {
      throw new StorageException(writeCache.getStorageName(),
          "File with id " + cacheEntry.getFileId() + " is deleted.");
    }

    if (cacheEntry.getCachePointer().getBuffer() != null) {
      readCache.releaseFromRead(real.getDelegate());
    } else {
      assert real.isNew || !cacheEntry.isLockAcquiredByCurrentThread();
    }
  }

  @Override
  public long filledUpTo(long fileId) {
    fileId = checkFileIdCompatibility(fileId, storageId);
    if (deletedFiles.contains(fileId)) {
      throw new StorageException(writeCache.getStorageName(),
          "File with id " + fileId + " is deleted.");
    }
    final var changesContainer = fileChanges.get(fileId);
    return internalFilledUpTo(fileId, changesContainer);
  }

  private long internalFilledUpTo(final long fileId, FileChanges changesContainer) {
    if (changesContainer == null) {
      changesContainer = new FileChanges();
      fileChanges.put(fileId, changesContainer);
    } else if (changesContainer.isNew || changesContainer.maxNewPageIndex > -2) {
      return changesContainer.maxNewPageIndex + 1;
    } else if (changesContainer.truncate) {
      return 0;
    }

    return writeCache.getFilledUpTo(fileId);
  }

  /**
   * This check if a file was trimmed or trunked in the current atomic operation.
   *
   * @param changesContainer changes container to check
   * @param pageIndex        limit to check against the changes
   * @return true if there are no changes or pageIndex still fit, false if the pageIndex do not fit
   * anymore
   */
  private static boolean checkChangesFilledUpTo(
      final FileChanges changesContainer, final long pageIndex) {
    if (changesContainer == null) {
      return true;
    } else if (changesContainer.isNew || changesContainer.maxNewPageIndex > -2) {
      return pageIndex < changesContainer.maxNewPageIndex + 1;
    } else {
      return !changesContainer.truncate;
    }
  }

  @Override
  public long addFile(final String fileName) {
    if (newFileNamesId.containsKey(fileName)) {
      throw new StorageException(writeCache.getStorageName(),
          "File with name " + fileName + " already exists.");
    }
    final long fileId;
    final boolean isNew;

    if (deletedFileNameIdMap.containsKey(fileName)) {
      fileId = deletedFileNameIdMap.removeLong(fileName);
      deletedFiles.remove(fileId);
      isNew = false;
    } else {
      fileId = writeCache.bookFileId(fileName);
      isNew = true;
    }
    newFileNamesId.put(fileName, fileId);

    final var fileChanges = new FileChanges();
    fileChanges.isNew = isNew;
    fileChanges.fileName = fileName;
    fileChanges.maxNewPageIndex = -1;

    this.fileChanges.put(fileId, fileChanges);

    return fileId;
  }

  @Override
  public long loadFile(final String fileName) throws IOException {
    var fileId = newFileNamesId.getLong(fileName);
    if (fileId == -1) {
      fileId = writeCache.loadFile(fileName);
    }
    this.fileChanges.computeIfAbsent(fileId, k -> new FileChanges());
    return fileId;
  }

  @Override
  public void deleteFile(long fileId) {
    fileId = checkFileIdCompatibility(fileId, storageId);

    final var fileChanges = this.fileChanges.remove(fileId);
    if (fileChanges != null && fileChanges.fileName != null) {
      newFileNamesId.removeLong(fileChanges.fileName);
    } else {
      deletedFiles.add(fileId);
      final var f = writeCache.fileNameById(fileId);
      if (f != null) {
        deletedFileNameIdMap.put(f, fileId);
      }
    }
  }

  @Override
  public boolean isFileExists(final String fileName) {
    if (newFileNamesId.containsKey(fileName)) {
      return true;
    }

    if (deletedFileNameIdMap.containsKey(fileName)) {
      return false;
    }

    return writeCache.exists(fileName);
  }

  @Override
  public String fileNameById(long fileId) {
    fileId = checkFileIdCompatibility(fileId, storageId);

    final var fileChanges = this.fileChanges.get(fileId);

    if (fileChanges != null && fileChanges.fileName != null) {
      return fileChanges.fileName;
    }

    if (deletedFiles.contains(fileId)) {
      throw new StorageException(writeCache.getStorageName(),
          "File with id " + fileId + " was deleted.");
    }

    return writeCache.fileNameById(fileId);
  }

  @Override
  public long fileIdByName(final String fileName) {
    var fileId = newFileNamesId.getLong(fileName);
    if (fileId > -1) {
      return fileId;
    }

    if (deletedFileNameIdMap.containsKey(fileName)) {
      return -1;
    }

    return writeCache.fileIdByName(fileName);
  }

  @Override
  public void truncateFile(long fileId) {
    fileId = checkFileIdCompatibility(fileId, storageId);

    final var fileChanges =
        this.fileChanges.computeIfAbsent(fileId, k -> new FileChanges());

    fileChanges.pageChangesMap.clear();
    fileChanges.maxNewPageIndex = -1;

    if (fileChanges.isNew) {
      return;
    }

    fileChanges.truncate = true;
  }

  @Override
  public LogSequenceNumber commitChanges(final WriteAheadLog writeAheadLog) throws IOException {
    LogSequenceNumber txEndLsn = null;

    final var startLSN = writeAheadLog.end();

    var deletedFilesIterator = deletedFiles.longIterator();
    while (deletedFilesIterator.hasNext()) {
      final var deletedFileId = deletedFilesIterator.nextLong();
      writeAheadLog.log(new FileDeletedWALRecord(operationUnitId, deletedFileId));
    }

    for (final var fileChangesEntry :
        fileChanges.long2ObjectEntrySet()) {
      final var fileChanges = fileChangesEntry.getValue();
      final var fileId = fileChangesEntry.getLongKey();

      if (fileChanges.isNew) {
        writeAheadLog.log(new FileCreatedWALRecord(operationUnitId, fileChanges.fileName, fileId));
      } else if (fileChanges.truncate) {
        LogManager.instance()
            .warn(
                this,
                "You performing truncate operation which is considered unsafe because can not be"
                    + " rolled back, as result data can be incorrectly restored after crash, this"
                    + " operation is not recommended to be used");
      }

      final Iterator<Long2ObjectMap.Entry<CacheEntryChanges>> filePageChangesIterator =
          fileChanges.pageChangesMap.long2ObjectEntrySet().iterator();
      while (filePageChangesIterator.hasNext()) {
        final var filePageChangesEntry =
            filePageChangesIterator.next();

        if (filePageChangesEntry.getValue().changes.hasChanges()) {
          final var pageIndex = filePageChangesEntry.getLongKey();
          final var filePageChanges = filePageChangesEntry.getValue();

          final var initialLSN = filePageChanges.getInitialLSN();
          Objects.requireNonNull(initialLSN);
          final var updatePageRecord =
              new UpdatePageRecord(
                  pageIndex, fileId, operationUnitId, filePageChanges.changes, initialLSN);
          writeAheadLog.log(updatePageRecord);
          filePageChanges.setChangeLSN(updatePageRecord.getLsn());

        } else {
          filePageChangesIterator.remove();
        }
      }
    }

    txEndLsn =
        writeAheadLog.log(new AtomicUnitEndRecord(operationUnitId, rollback, getMetadata()));

    deletedFilesIterator = deletedFiles.longIterator();
    while (deletedFilesIterator.hasNext()) {
      var deletedFileId = deletedFilesIterator.nextLong();
      readCache.deleteFile(deletedFileId, writeCache);
    }

    for (final var fileChangesEntry :
        fileChanges.long2ObjectEntrySet()) {
      final var fileChanges = fileChangesEntry.getValue();
      final var fileId = fileChangesEntry.getLongKey();

      if (fileChanges.isNew) {
        readCache.addFile(
            fileChanges.fileName, newFileNamesId.getLong(fileChanges.fileName), writeCache);
      } else if (fileChanges.truncate) {
        LogManager.instance()
            .warn(
                this,
                "You performing truncate operation which is considered unsafe because can not be"
                    + " rolled back, as result data can be incorrectly restored after crash, this"
                    + " operation is not recommended to be used");
        readCache.truncateFile(fileId, writeCache);
      }

      final Iterator<Long2ObjectMap.Entry<CacheEntryChanges>> filePageChangesIterator =
          fileChanges.pageChangesMap.long2ObjectEntrySet().iterator();
      while (filePageChangesIterator.hasNext()) {
        final var filePageChangesEntry =
            filePageChangesIterator.next();

        if (filePageChangesEntry.getValue().changes.hasChanges()) {
          final var pageIndex = filePageChangesEntry.getLongKey();
          final var filePageChanges = filePageChangesEntry.getValue();

          var cacheEntry =
              readCache.loadForWrite(
                  // todo update interface to avoid allocation?
                  new FileHandler(fileId, null), pageIndex, writeCache,
                  filePageChanges.verifyCheckSum, startLSN);
          if (cacheEntry == null) {
            if (!filePageChanges.isNew) {
              throw new StorageException(writeCache.getStorageName(),
                  "Page with index " + pageIndex + " is not found in file with id " + fileId);
            }
            do {
              if (cacheEntry != null) {
                readCache.releaseFromWrite(cacheEntry, writeCache, true);
              }

              cacheEntry = readCache.allocateNewPage(fileId, writeCache, startLSN);
            } while (cacheEntry.getPageIndex() != pageIndex);
          }

          try {
            final var durablePage = new DurablePage(cacheEntry);
            cacheEntry.setEndLSN(txEndLsn);

            durablePage.restoreChanges(filePageChanges.changes);
            durablePage.setLsn(filePageChanges.getChangeLSN());
          } finally {
            readCache.releaseFromWrite(cacheEntry, writeCache, true);
          }
        } else {
          filePageChangesIterator.remove();
        }
      }
    }

    return txEndLsn;
  }

  @Override
  public void rollbackInProgress() {
    rollback = true;
  }

  @Override
  public boolean isRollbackInProgress() {
    return rollback;
  }

  @Override
  public void addLockedObject(final String lockedObject) {
    lockedObjects.add(lockedObject);
  }

  @Override
  public boolean containsInLockedObjects(final String objectToLock) {
    return lockedObjects.contains(objectToLock);
  }

  @Override
  public Iterable<String> lockedObjects() {
    return lockedObjects;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    final var operation = (AtomicOperationBinaryTracking) o;

    return operationUnitId == operation.operationUnitId;
  }

  @Override
  public int hashCode() {
    return Long.hashCode(operationUnitId);
  }

  private static final class FileChanges {

    private final Long2ObjectOpenHashMap<CacheEntryChanges> pageChangesMap =
        new Long2ObjectOpenHashMap<>();
    private long maxNewPageIndex = -2;
    private boolean isNew;
    private boolean truncate;
    private String fileName;
  }

  private static int storageId(final long fileId) {
    return (int) (fileId >>> 32);
  }

  private static long composeFileId(final long fileId, final int storageId) {
    return (((long) storageId) << 32) | fileId;
  }

  private static long checkFileIdCompatibility(final long fileId, final int storageId) {
    // indicates that storage has no it's own id.
    if (storageId == -1) {
      return fileId;
    }
    if (storageId(fileId) == 0) {
      var newFileId = composeFileId(fileId, storageId);
      assert newFileId
          == fileId : "File id was updated during compatibility check. New FileHandler should be created.";
      return newFileId;
    }
    return fileId;
  }

  @Override
  public void addDeletedRecordPosition(int collectionId, int pageIndex, int recordPosition) {
    var key = new IntIntImmutablePair(collectionId, pageIndex);
    final var recordPositions =
        deletedRecordPositions.computeIfAbsent(key, k -> new IntOpenHashSet());
    recordPositions.add(recordPosition);
  }

  @Override
  public IntSet getBookedRecordPositions(int collectionId, int pageIndex) {
    return deletedRecordPositions.getOrDefault(
        new IntIntImmutablePair(collectionId, pageIndex), IntSets.emptySet());
  }

  @Override
  public void incrementComponentOperations() {
    componentOperationsCount++;
  }

  @Override
  public void decrementComponentOperations() {
    componentOperationsCount--;
  }

  @Override
  public int getComponentOperations() {
    return componentOperationsCount;
  }
}
