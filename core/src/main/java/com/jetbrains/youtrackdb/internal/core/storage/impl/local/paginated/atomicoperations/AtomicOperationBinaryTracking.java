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

import com.jetbrains.youtrackdb.internal.common.directmemory.Pointer;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.index.engine.HistogramDeltaHolder;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.OptimisticReadScope;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPositionMapBucket.PositionEntry;
import com.jetbrains.youtrackdb.internal.core.storage.collection.SnapshotKey;
import com.jetbrains.youtrackdb.internal.core.storage.collection.VisibilityKey;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsTable.AtomicOperationsSnapshot;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.StorageComponent;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitEndRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.FileCreatedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.FileDeletedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.UpdatePageRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.EdgeSnapshotKey;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.EdgeVisibilityKey;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.LinkBagValue;
import it.unimi.dsi.fastutil.ints.IntIntImmutablePair;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Note: all atomic operations methods are designed in context that all operations on single files
 * will be wrapped in shared lock.
 *
 * @since 12/3/13
 */
final class AtomicOperationBinaryTracking implements AtomicOperation {

  private final int storageId;
  private long operationCommitTs = -1;

  private boolean rollback;

  private final Set<String> lockedObjects = new HashSet<>();
  private final ArrayList<StorageComponent> lockedComponents = new ArrayList<>();
  private final Long2ObjectOpenHashMap<FileChanges> fileChanges = new Long2ObjectOpenHashMap<>();
  private final Object2LongOpenHashMap<String> newFileNamesId = new Object2LongOpenHashMap<>();
  private final LongOpenHashSet deletedFiles = new LongOpenHashSet();
  private final Object2LongOpenHashMap<String> deletedFileNameIdMap =
      new Object2LongOpenHashMap<>();

  private final ReadCache readCache;
  private final WriteCache writeCache;

  private final Map<String, AtomicOperationMetadata<?>> metadata = new LinkedHashMap<>();

  private boolean active;

  private final Map<IntIntImmutablePair, IntSet> deletedRecordPositions = new HashMap<>();
  private final @Nonnull AtomicOperationsSnapshot snapshot;

  // References to storage-wide shared indexes (never null).
  private final ConcurrentSkipListMap<SnapshotKey, PositionEntry> sharedSnapshotIndex;
  private final ConcurrentSkipListMap<VisibilityKey, SnapshotKey> sharedVisibilityIndex;
  private final AtomicLong snapshotIndexSize;

  // Local overlay buffers — lazily allocated to avoid overhead for read-only transactions.
  // Snapshot buffer uses TreeMap to support efficient subMap range queries in
  // snapshotSubMapDescending without intermediate collection/sort.
  @Nullable private TreeMap<SnapshotKey, PositionEntry> localSnapshotBuffer;
  @Nullable private HashMap<VisibilityKey, SnapshotKey> localVisibilityBuffer;

  // Histogram delta accumulator — lazily allocated to avoid overhead for
  // transactions that do not modify indexed data.
  @Nullable private HistogramDeltaHolder histogramDeltas;

  // Edge snapshot: references to storage-wide shared indexes (never null).
  private final ConcurrentSkipListMap<EdgeSnapshotKey, LinkBagValue> sharedEdgeSnapshotIndex;
  private final ConcurrentSkipListMap<EdgeVisibilityKey, EdgeSnapshotKey> sharedEdgeVisibilityIndex;
  private final AtomicLong edgeSnapshotIndexSize;

  // Edge snapshot: local overlay buffers — lazily allocated.
  @Nullable private TreeMap<EdgeSnapshotKey, LinkBagValue> localEdgeSnapshotBuffer;
  @Nullable private HashMap<EdgeVisibilityKey, EdgeSnapshotKey> localEdgeVisibilityBuffer;

  // Optimistic read scope — reused across optimistic read attempts within
  // the same atomic operation. Eagerly allocated since optimistic reads are
  // the hot path this class is designed to support.
  private final OptimisticReadScope optimisticReadScope = new OptimisticReadScope();

  AtomicOperationBinaryTracking(
      final ReadCache readCache,
      final WriteCache writeCache,
      final int storageId,
      @Nonnull AtomicOperationsSnapshot snapshot,
      @Nonnull ConcurrentSkipListMap<SnapshotKey, PositionEntry> sharedSnapshotIndex,
      @Nonnull ConcurrentSkipListMap<VisibilityKey, SnapshotKey> sharedVisibilityIndex,
      @Nonnull AtomicLong snapshotIndexSize,
      @Nonnull ConcurrentSkipListMap<EdgeSnapshotKey, LinkBagValue> sharedEdgeSnapshotIndex,
      @Nonnull ConcurrentSkipListMap<EdgeVisibilityKey, EdgeSnapshotKey> sharedEdgeVisibilityIndex,
      @Nonnull AtomicLong edgeSnapshotIndexSize) {
    this.snapshot = snapshot;
    newFileNamesId.defaultReturnValue(-1);
    deletedFileNameIdMap.defaultReturnValue(-1);

    this.storageId = storageId;
    this.readCache = readCache;
    this.writeCache = writeCache;
    this.sharedSnapshotIndex = sharedSnapshotIndex;
    this.sharedVisibilityIndex = sharedVisibilityIndex;
    this.snapshotIndexSize = snapshotIndexSize;
    this.sharedEdgeSnapshotIndex = sharedEdgeSnapshotIndex;
    this.sharedEdgeVisibilityIndex = sharedEdgeVisibilityIndex;
    this.edgeSnapshotIndexSize = edgeSnapshotIndexSize;
    this.active = true;
  }

  @Override
  public @Nonnull AtomicOperationsSnapshot getAtomicOperationsSnapshot() {
    checkIfActive();

    return snapshot;
  }

  @Override
  public void startToApplyOperations(long commitTs) {
    checkIfActive();

    operationCommitTs = commitTs;
  }

  @Override
  public long getCommitTs() {
    checkIfActive();

    if (operationCommitTs == -1) {
      throw new DatabaseException("Atomic operation is not committed yet.");
    }

    return operationCommitTs;
  }

  @Override
  public long getCommitTsUnsafe() {
    return operationCommitTs;
  }

  @Nullable @Override
  public CacheEntry loadPageForWrite(
      long fileId, final long pageIndex, final int pageCount, final boolean verifyChecksum)
      throws IOException {
    checkIfActive();

    assert pageCount > 0;
    fileId = checkFileIdCompatibility(fileId, storageId);

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
              readCache.loadForRead(fileId, pageIndex, writeCache, verifyChecksum);
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
                readCache.loadForRead(fileId, pageIndex, writeCache, verifyChecksum);
            return pageChangesContainer;
          }
        }
      }
    }
    return null;
  }

  @Nullable @Override
  public CacheEntry loadPageForRead(long fileId, final long pageIndex) throws IOException {
    checkIfActive();

    fileId = checkFileIdCompatibility(fileId, storageId);

    if (deletedFiles.contains(fileId)) {
      throw new StorageException(writeCache.getStorageName(),
          "File with id " + fileId + " is deleted.");
    }

    final var changesContainer = fileChanges.get(fileId);
    if (changesContainer == null) {
      return readCache.loadForRead(fileId, pageIndex, writeCache, true);
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
          return readCache.loadForRead(fileId, pageIndex, writeCache, true);
        } else {
          if (pageChangesContainer.isNew) {
            return pageChangesContainer;
          } else {
            // Need to load the page again from cache for locking reasons
            pageChangesContainer.delegate =
                readCache.loadForRead(fileId, pageIndex, writeCache, true);
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
    checkIfActive();

    this.metadata.put(metadata.getKey(), metadata);
  }

  /**
   * @param key Key of metadata which is looking for.
   * @return Metadata by associated key or <code>null</code> if such metadata is absent.
   */
  @Override
  public AtomicOperationMetadata<?> getMetadata(final String key) {
    checkIfActive();

    return metadata.get(key);
  }

  /**
   * @return All keys and associated metadata contained inside of atomic operation
   */
  private Map<String, AtomicOperationMetadata<?>> getMetadata() {
    return Collections.unmodifiableMap(metadata);
  }

  @Override
  public CacheEntry addPage(long fileId) {
    checkIfActive();

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
            new CachePointer((Pointer) null, null, fileId, (int) filledUpTo),
            false,
            readCache);
    return pageChangesContainer;
  }

  @Override
  public void releasePageFromRead(final CacheEntry cacheEntry) {
    checkIfActive();

    if (cacheEntry instanceof CacheEntryChanges) {
      releasePageFromWrite(cacheEntry);
    } else {
      readCache.releaseFromRead(cacheEntry);
    }
  }

  @Override
  public void releasePageFromWrite(final CacheEntry cacheEntry) {
    checkIfActive();

    final var real = (CacheEntryChanges) cacheEntry;

    if (deletedFiles.contains(cacheEntry.getFileId())) {
      throw new StorageException(writeCache.getStorageName(),
          "File with id " + cacheEntry.getFileId() + " is deleted.");
    }

    if (cacheEntry.getCachePointer().getBuffer() != null) {
      readCache.releaseFromRead(real.getDelegate());
    } else {
      assert real.isNew;
    }
  }

  @Override
  public boolean hasChangesForPage(long fileId, final long pageIndex) {
    // Intentionally skips checkIfActive() — this is called on the optimistic
    // hot path and the caller is always within an active operation context.
    fileId = checkFileIdCompatibility(fileId, storageId);

    // Deleted file: force fallback so the pinned path raises StorageException
    if (deletedFiles.contains(fileId)) {
      return true;
    }

    final var changesContainer = fileChanges.get(fileId);
    if (changesContainer == null) {
      return false;
    }

    // Truncated file: all pre-existing pages are logically gone, force fallback
    // so the pinned path handles filledUpTo correctly
    if (changesContainer.truncate) {
      return true;
    }

    // New file: all pages up to maxNewPageIndex have local changes
    if (changesContainer.isNew) {
      return pageIndex <= changesContainer.maxNewPageIndex;
    }

    return changesContainer.pageChangesMap.containsKey(pageIndex);
  }

  @Override
  public long filledUpTo(long fileId) {
    checkIfActive();

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
  public long addFile(final String fileName, final boolean nonDurable) {
    assert fileName != null : "fileName must not be null";
    checkIfActive();

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
    fileChanges.nonDurable = nonDurable;
    fileChanges.maxNewPageIndex = -1;

    this.fileChanges.put(fileId, fileChanges);

    return fileId;
  }

  /**
   * Returns whether the file is non-durable. Checks both local state (for files
   * created in this operation) and the write cache registry (for existing files
   * loaded via {@link #loadFile}). Package-private for testing.
   */
  boolean isFileNonDurable(long fileId) {
    fileId = checkFileIdCompatibility(fileId, storageId);
    var changes = fileChanges.get(fileId);
    if (changes != null && changes.nonDurable) {
      return true;
    }
    return writeCache.isNonDurable(fileId);
  }

  @Override
  public long loadFile(final String fileName) throws IOException {
    checkIfActive();

    var fileId = newFileNamesId.getLong(fileName);
    if (fileId == -1) {
      fileId = writeCache.loadFile(fileName);
    }
    this.fileChanges.computeIfAbsent(fileId, k -> new FileChanges());
    return fileId;
  }

  @Override
  public void deleteFile(long fileId) {
    checkIfActive();

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
    checkIfActive();

    if (newFileNamesId.containsKey(fileName)) {
      return true;
    }

    if (deletedFileNameIdMap.containsKey(fileName)) {
      return false;
    }

    return writeCache.exists(fileName);
  }

  @Override
  public long fileIdByName(final String fileName) {
    checkIfActive();

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
    checkIfActive();

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
  public LogSequenceNumber commitChanges(long commitTs, @Nonnull final WriteAheadLog writeAheadLog)
      throws IOException {
    checkIfActive();
    try {
      LogSequenceNumber txEndLsn;

      // Defer WAL unit start: only emit when first durable change is encountered.
      // If the operation contains only non-durable changes, no WAL records are written.
      boolean walUnitStarted = false;
      // startLSN is captured immediately after the WAL unit start record, so it
      // points into the same segment as the start record. This is critical for
      // dirty-page tracking: pages pin this LSN to prevent WAL truncation of the
      // segment containing their WAL records.
      LogSequenceNumber startLSN = null;
      this.operationCommitTs = commitTs;

      var deletedFilesIterator = deletedFiles.longIterator();
      while (deletedFilesIterator.hasNext()) {
        final var deletedFileId = deletedFilesIterator.nextLong();
        if (writeCache.isNonDurable(deletedFileId)) {
          continue;
        }
        if (!walUnitStarted) {
          writeAheadLog.logAtomicOperationStartRecord(true, commitTs);
          walUnitStarted = true;
          startLSN = writeAheadLog.end();
        }
        writeAheadLog.log(new FileDeletedWALRecord(operationCommitTs, deletedFileId));
      }

      for (final var fileChangesEntry : fileChanges.long2ObjectEntrySet()) {
        final var fileChanges = fileChangesEntry.getValue();
        final var fileId = fileChangesEntry.getLongKey();
        final boolean nonDurable =
            fileChanges.nonDurable || writeCache.isNonDurable(fileId);

        if (fileChanges.isNew && !nonDurable) {
          if (!walUnitStarted) {
            writeAheadLog.logAtomicOperationStartRecord(true, commitTs);
            walUnitStarted = true;
            startLSN = writeAheadLog.end();
          }
          writeAheadLog.log(
              new FileCreatedWALRecord(operationCommitTs, fileChanges.fileName, fileId));
        } else if (fileChanges.truncate) {
          LogManager.instance()
              .warn(
                  this,
                  "You performing truncate operation which is considered unsafe because can not be"
                      + " rolled back, as result data can be incorrectly restored after crash, this"
                      + " operation is not recommended to be used");
        }

        if (nonDurable) {
          // Skip WAL logging for non-durable files. Page changes are applied
          // to cache below and empty entries are cleaned up in the cache loop.
          continue;
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
            if (!walUnitStarted) {
              writeAheadLog.logAtomicOperationStartRecord(true, commitTs);
              walUnitStarted = true;
              startLSN = writeAheadLog.end();
            }
            final var updatePageRecord =
                new UpdatePageRecord(
                    pageIndex, fileId, operationCommitTs, filePageChanges.changes, initialLSN);
            writeAheadLog.log(updatePageRecord);
            filePageChanges.setChangeLSN(updatePageRecord.getLsn());

          } else {
            filePageChangesIterator.remove();
          }
        }
      }

      if (walUnitStarted) {
        txEndLsn =
            writeAheadLog.log(
                new AtomicUnitEndRecord(operationCommitTs, rollback, getMetadata()));
      } else {
        // Pure non-durable operation — no WAL records emitted
        txEndLsn = null;
      }

      // Flush snapshot/visibility buffers to shared maps before applying page changes
      // to cache. This ensures entries are visible by the time concurrent readers can
      // see the new record versions through the cache. On rollback, buffers are
      // discarded — the write-nothing-on-error pattern.
      if (!rollback) {
        flushSnapshotBuffers();
        flushEdgeSnapshotBuffers();
      }

      deletedFilesIterator = deletedFiles.longIterator();
      while (deletedFilesIterator.hasNext()) {
        var deletedFileId = deletedFilesIterator.nextLong();
        readCache.deleteFile(deletedFileId, writeCache);
      }

      for (final var fileChangesEntry : fileChanges.long2ObjectEntrySet()) {
        final var fileChanges = fileChangesEntry.getValue();
        final var fileId = fileChangesEntry.getLongKey();
        final boolean nonDurable =
            fileChanges.nonDurable || writeCache.isNonDurable(fileId);

        if (fileChanges.isNew) {
          readCache.addFile(
              fileChanges.fileName,
              newFileNamesId.getLong(fileChanges.fileName),
              writeCache,
              nonDurable);
        } else if (fileChanges.truncate) {
          LogManager.instance()
              .warn(
                  this,
                  "You performing truncate operation which is considered unsafe because can not be"
                      + " rolled back, as result data can be incorrectly restored after crash, this"
                      + " operation is not recommended to be used");
          readCache.truncateFile(fileId, writeCache);
        }

        // Non-durable files use null startLSN — no WAL dependency exists,
        // and updateDirtyPagesTable (Track 4) skips them regardless.
        final var fileStartLSN = nonDurable ? null : startLSN;

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
                    fileId, pageIndex, writeCache, filePageChanges.verifyCheckSum, fileStartLSN);
            if (cacheEntry == null) {
              if (!filePageChanges.isNew) {
                throw new StorageException(writeCache.getStorageName(),
                    "Page with index " + pageIndex + " is not found in file with id " + fileId);
              }
              do {
                if (cacheEntry != null) {
                  readCache.releaseFromWrite(cacheEntry, writeCache, true);
                }

                cacheEntry = readCache.allocateNewPage(fileId, writeCache, fileStartLSN);
              } while (cacheEntry.getPageIndex() != pageIndex);
            }

            try {
              final var durablePage = new DurablePage(cacheEntry);

              durablePage.restoreChanges(filePageChanges.changes);

              // Non-durable pages have no WAL records, so endLSN and changeLSN
              // are not meaningful. The dirty-pages-table skip in
              // updateDirtyPagesTable (Track 4) ensures these pages
              // don't block WAL truncation.
              if (!nonDurable) {
                cacheEntry.setEndLSN(txEndLsn);
                durablePage.setLsn(filePageChanges.getChangeLSN());
              }
            } finally {
              readCache.releaseFromWrite(cacheEntry, writeCache, true);
            }
          } else {
            filePageChangesIterator.remove();
          }
        }
      }

      return txEndLsn;
    } finally {
      active = false;
    }
  }

  @Override
  public void deactivate() {
    active = false;
  }

  @Override
  @Nullable public HistogramDeltaHolder getHistogramDeltas() {
    return histogramDeltas;
  }

  @Override
  @Nonnull
  public HistogramDeltaHolder getOrCreateHistogramDeltas() {
    if (histogramDeltas == null) {
      histogramDeltas = new HistogramDeltaHolder();
    }
    return histogramDeltas;
  }

  @Override
  public OptimisticReadScope getOptimisticReadScope() {
    return optimisticReadScope;
  }

  @Override
  public boolean isActive() {
    return active;
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
  public void addLockedComponent(StorageComponent component) {
    lockedComponents.add(component);
  }

  @Override
  public Iterable<StorageComponent> lockedComponents() {
    return lockedComponents;
  }

  // --- Snapshot / Visibility index proxy methods ---

  @Override
  public void putSnapshotEntry(SnapshotKey key, PositionEntry value) {
    checkIfActive();
    assert key != null : "SnapshotKey must not be null";
    assert value != null : "PositionEntry must not be null";

    if (localSnapshotBuffer == null) {
      localSnapshotBuffer = new TreeMap<>();
    }
    localSnapshotBuffer.put(key, value);
  }

  @Override
  public PositionEntry getSnapshotEntry(SnapshotKey key) {
    checkIfActive();

    if (localSnapshotBuffer != null) {
      var local = localSnapshotBuffer.get(key);
      if (local != null) {
        return local;
      }
    }
    return sharedSnapshotIndex.get(key);
  }

  @Override
  public Iterable<Map.Entry<SnapshotKey, PositionEntry>> snapshotSubMapDescending(
      SnapshotKey fromInclusive, SnapshotKey toInclusive) {
    checkIfActive();
    assert fromInclusive.compareTo(toInclusive) <= 0
        : "fromInclusive must be <= toInclusive";

    var sharedDescending = sharedSnapshotIndex
        .subMap(fromInclusive, true, toInclusive, true)
        .descendingMap().entrySet();

    if (localSnapshotBuffer == null || localSnapshotBuffer.isEmpty()) {
      return sharedDescending;
    }

    // TreeMap.subMap returns a view — no copy, no sort needed.
    var localDescending = localSnapshotBuffer
        .subMap(fromInclusive, true, toInclusive, true)
        .descendingMap().entrySet();

    if (localDescending.isEmpty()) {
      return sharedDescending;
    }

    return () -> new MergingDescendingIterator<>(
        sharedDescending.iterator(), localDescending.iterator());
  }

  @Override
  public void putVisibilityEntry(VisibilityKey key, SnapshotKey value) {
    checkIfActive();
    assert key != null : "VisibilityKey must not be null";
    assert value != null : "SnapshotKey value must not be null";

    if (localVisibilityBuffer == null) {
      localVisibilityBuffer = new HashMap<>();
    }
    localVisibilityBuffer.put(key, value);
  }

  @Override
  public boolean containsVisibilityEntry(VisibilityKey key) {
    checkIfActive();

    if (localVisibilityBuffer != null && localVisibilityBuffer.containsKey(key)) {
      return true;
    }
    return sharedVisibilityIndex.containsKey(key);
  }

  // --- Edge snapshot methods ---

  @Override
  public void putEdgeSnapshotEntry(EdgeSnapshotKey key, LinkBagValue value) {
    checkIfActive();
    assert key != null : "EdgeSnapshotKey must not be null";
    assert value != null : "LinkBagValue must not be null";

    if (localEdgeSnapshotBuffer == null) {
      localEdgeSnapshotBuffer = new TreeMap<>();
    }
    localEdgeSnapshotBuffer.put(key, value);
  }

  @Override
  public LinkBagValue getEdgeSnapshotEntry(EdgeSnapshotKey key) {
    checkIfActive();

    if (localEdgeSnapshotBuffer != null) {
      var local = localEdgeSnapshotBuffer.get(key);
      if (local != null) {
        return local;
      }
    }
    return sharedEdgeSnapshotIndex.get(key);
  }

  @Override
  public Iterable<Map.Entry<EdgeSnapshotKey, LinkBagValue>> edgeSnapshotSubMapDescending(
      EdgeSnapshotKey fromInclusive, EdgeSnapshotKey toInclusive) {
    checkIfActive();
    assert fromInclusive.compareTo(toInclusive) <= 0
        : "fromInclusive must be <= toInclusive";

    var sharedDescending = sharedEdgeSnapshotIndex
        .subMap(fromInclusive, true, toInclusive, true)
        .descendingMap().entrySet();

    if (localEdgeSnapshotBuffer == null || localEdgeSnapshotBuffer.isEmpty()) {
      return sharedDescending;
    }

    var localDescending = localEdgeSnapshotBuffer
        .subMap(fromInclusive, true, toInclusive, true)
        .descendingMap().entrySet();

    if (localDescending.isEmpty()) {
      return sharedDescending;
    }

    return () -> new MergingDescendingIterator<>(
        sharedDescending.iterator(), localDescending.iterator());
  }

  @Override
  public void putEdgeVisibilityEntry(EdgeVisibilityKey key, EdgeSnapshotKey value) {
    checkIfActive();
    assert key != null : "EdgeVisibilityKey must not be null";
    assert value != null : "EdgeSnapshotKey value must not be null";

    if (localEdgeVisibilityBuffer == null) {
      localEdgeVisibilityBuffer = new HashMap<>();
    }
    localEdgeVisibilityBuffer.put(key, value);
  }

  @Override
  public boolean containsEdgeVisibilityEntry(EdgeVisibilityKey key) {
    checkIfActive();

    if (localEdgeVisibilityBuffer != null && localEdgeVisibilityBuffer.containsKey(key)) {
      return true;
    }
    return sharedEdgeVisibilityIndex.containsKey(key);
  }

  void flushSnapshotBuffers() {
    if (localSnapshotBuffer != null) {
      // Count only genuinely new entries (putIfAbsent semantics not needed — last writer wins
      // for the same key, and the counter is approximate). Using size() of the local buffer
      // is a good-enough approximation: slight overcounting is harmless (just triggers cleanup
      // slightly earlier).
      snapshotIndexSize.addAndGet(localSnapshotBuffer.size());
      sharedSnapshotIndex.putAll(localSnapshotBuffer);
    }
    if (localVisibilityBuffer != null) {
      sharedVisibilityIndex.putAll(localVisibilityBuffer);
    }
  }

  void flushEdgeSnapshotBuffers() {
    if (localEdgeSnapshotBuffer != null) {
      // Approximate count — same semantics as flushSnapshotBuffers: slight overcounting is
      // harmless (just triggers cleanup slightly earlier). The size counter drives the combined
      // threshold check in cleanupSnapshotIndex(); visibility entries are not counted because
      // eviction uses headMap range scans on the sorted map, not a size check.
      edgeSnapshotIndexSize.addAndGet(localEdgeSnapshotBuffer.size());
      sharedEdgeSnapshotIndex.putAll(localEdgeSnapshotBuffer);
    }
    if (localEdgeVisibilityBuffer != null) {
      sharedEdgeVisibilityIndex.putAll(localEdgeVisibilityBuffer);
    }
  }

  /**
   * Merges two iterators of {@code Map.Entry<K, V>} that are each sorted in <b>descending</b>
   * key order. On equal keys, the local entry takes priority (shadows the shared entry). Either
   * or both iterators may be empty. Used for both collection snapshot and edge snapshot merging.
   */
  static final class MergingDescendingIterator<K extends Comparable<K>, V>
      implements Iterator<Map.Entry<K, V>> {

    private final Iterator<Map.Entry<K, V>> sharedIter;
    private final Iterator<Map.Entry<K, V>> localIter;
    private Map.Entry<K, V> nextShared;
    private Map.Entry<K, V> nextLocal;

    MergingDescendingIterator(
        Iterator<Map.Entry<K, V>> sharedIter,
        Iterator<Map.Entry<K, V>> localIter) {
      this.sharedIter = sharedIter;
      this.localIter = localIter;
      this.nextShared = sharedIter.hasNext() ? sharedIter.next() : null;
      this.nextLocal = localIter.hasNext() ? localIter.next() : null;
    }

    @Override
    public boolean hasNext() {
      return nextShared != null || nextLocal != null;
    }

    @Override
    public Map.Entry<K, V> next() {
      if (nextLocal == null && nextShared == null) {
        throw new NoSuchElementException();
      }
      if (nextLocal == null) {
        var result = nextShared;
        nextShared = sharedIter.hasNext() ? sharedIter.next() : null;
        return result;
      }
      if (nextShared == null) {
        var result = nextLocal;
        nextLocal = localIter.hasNext() ? localIter.next() : null;
        return result;
      }

      // Both non-null — compare descending (larger key first)
      int cmp = nextLocal.getKey().compareTo(nextShared.getKey());
      if (cmp > 0) {
        // local key is larger → comes first in descending order
        var result = nextLocal;
        nextLocal = localIter.hasNext() ? localIter.next() : null;
        return result;
      } else if (cmp < 0) {
        // shared key is larger → comes first in descending order
        var result = nextShared;
        nextShared = sharedIter.hasNext() ? sharedIter.next() : null;
        return result;
      } else {
        // Equal keys: local shadows shared
        var result = nextLocal;
        nextLocal = localIter.hasNext() ? localIter.next() : null;
        nextShared = sharedIter.hasNext() ? sharedIter.next() : null;
        return result;
      }
    }
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

    return operationCommitTs == operation.operationCommitTs;
  }

  @Override
  public int hashCode() {
    return Long.hashCode(operationCommitTs);
  }

  private static final class FileChanges {

    private final Long2ObjectOpenHashMap<CacheEntryChanges> pageChangesMap =
        new Long2ObjectOpenHashMap<>();
    private long maxNewPageIndex = -2;
    private boolean isNew;
    private boolean truncate;
    private boolean nonDurable;
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
      return composeFileId(fileId, storageId);
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

  private void checkIfActive() {
    if (!active) {
      throw new DatabaseException(writeCache.getStorageName(),
          "Atomic operation is not active and can not be used");
    }
  }
}
