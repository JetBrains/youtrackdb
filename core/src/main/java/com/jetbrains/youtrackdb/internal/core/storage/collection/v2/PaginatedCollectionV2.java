/*
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.storage.collection.v2;

import com.jetbrains.youtrackdb.api.exception.BaseException;
import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.CoreMetrics;
import com.jetbrains.youtrackdb.internal.common.serialization.types.ByteSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrackdb.internal.common.util.RawPairObjectInteger;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.config.StorageCollectionConfiguration;
import com.jetbrains.youtrackdb.internal.core.config.StoragePaginatedCollectionConfiguration;
import com.jetbrains.youtrackdb.internal.core.conflict.RecordConflictStrategy;
import com.jetbrains.youtrackdb.internal.core.exception.PaginatedCollectionException;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.metadata.MetadataInternal;
import com.jetbrains.youtrackdb.internal.core.storage.PhysicalPosition;
import com.jetbrains.youtrackdb.internal.core.storage.RawBuffer;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import com.jetbrains.youtrackdb.internal.core.storage.StorageCollection;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPage;
import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPositionMap;
import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPositionMapBucket;
import com.jetbrains.youtrackdb.internal.core.storage.collection.PaginatedCollection;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.CollectionBrowseEntry;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.CollectionBrowsePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import it.unimi.dsi.fastutil.ints.Int2ObjectFunction;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * @since 10/7/13
 */
public final class PaginatedCollectionV2 extends PaginatedCollection {

  // max chunk size - nex page pointer - first record flag
  private static final int MAX_ENTRY_SIZE =
      CollectionPage.MAX_RECORD_SIZE - ByteSerializer.BYTE_SIZE - LongSerializer.LONG_SIZE;

  private static final int MIN_ENTRY_SIZE = ByteSerializer.BYTE_SIZE + LongSerializer.LONG_SIZE;

  private static final int STATE_ENTRY_INDEX = 0;
  private static final int BINARY_VERSION = 2;

  private static final int PAGE_INDEX_OFFSET = 16;
  private static final int RECORD_POSITION_MASK = 0xFFFF;

  private final boolean systemCollection;
  private final CollectionPositionMapV2 collectionPositionMap;
  private final FreeSpaceMap freeSpaceMap;
  private final String storageName;

  private StorageCollection.Meters meters = Meters.NOOP;

  private volatile int id;
  private long fileId;
  private RecordConflictStrategy recordConflictStrategy;

  public PaginatedCollectionV2(
      @Nonnull final String name, @Nonnull final AbstractStorage storage) {
    this(
        name,
        DEF_EXTENSION,
        CollectionPositionMap.DEF_EXTENSION,
        FreeSpaceMap.DEF_EXTENSION,
        storage);
  }

  public PaginatedCollectionV2(
      final String name,
      final String dataExtension,
      final String cpmExtension,
      final String fsmExtension,
      final AbstractStorage storage) {
    super(storage, name, dataExtension, name + dataExtension);

    systemCollection = MetadataInternal.SYSTEM_COLLECTION.contains(name);
    collectionPositionMap = new CollectionPositionMapV2(storage, getName(), getFullName(),
        cpmExtension);
    freeSpaceMap = new FreeSpaceMap(storage, name, fsmExtension, getFullName());
    storageName = storage.getName();

  }

  @Override
  public void configure(final int id, final String collectionName) throws IOException {
    acquireExclusiveLock();
    try {
      init(id, collectionName, null);
    } finally {
      releaseExclusiveLock();
    }
  }

  @Override
  public boolean exists() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final var atomicOperation = atomicOperationsManager.getCurrentOperation();
        return isFileExists(atomicOperation, getFullName());
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public int getBinaryVersion() {
    return BINARY_VERSION;
  }

  @Override
  public StoragePaginatedCollectionConfiguration generateCollectionConfig() {
    acquireSharedLock();
    try {
      return new StoragePaginatedCollectionConfiguration(
          id,
          getName(),
          null,
          true,
          StoragePaginatedCollectionConfiguration.DEFAULT_GROW_FACTOR,
          StoragePaginatedCollectionConfiguration.DEFAULT_GROW_FACTOR,
          null,
          null,
          null,
          Optional.ofNullable(recordConflictStrategy)
              .map(RecordConflictStrategy::getName)
              .orElse(null),
          BINARY_VERSION);

    } finally {
      releaseSharedLock();
    }
  }

  @Override
  public void configure(final Storage storage, final StorageCollectionConfiguration config)
      throws IOException {
    acquireExclusiveLock();
    try {
      init(
          config.getId(),
          config.getName(),
          ((StoragePaginatedCollectionConfiguration) config).conflictStrategy);
    } finally {
      releaseExclusiveLock();
    }
  }

  @Override
  public void create(AtomicOperation atomicOperation) {
    executeInsideComponentOperation(
        atomicOperation,
        operation -> {
          acquireExclusiveLock();
          try {
            fileId = addFile(atomicOperation, getFullName());
            initCusterState(atomicOperation);
            collectionPositionMap.create(atomicOperation);
            freeSpaceMap.create(atomicOperation);
          } finally {
            releaseExclusiveLock();
          }
        });
  }

  @Override
  public void open(AtomicOperation atomicOperation) {
    executeInsideComponentOperation(
        atomicOperation,
        operation -> {
          acquireExclusiveLock();
          try {
            fileId = openFile(atomicOperation, getFullName());
            collectionPositionMap.open(atomicOperation);
            if (freeSpaceMap.exists(atomicOperation)) {
              freeSpaceMap.open(atomicOperation);
            } else {
              final var additionalArgs2 = new Object[]{getName(), storageName};
              LogManager.instance()
                  .info(
                      this,
                      "Free space map is absent inside of %s collection of storage %s . Information"
                          + " about free space present inside of each page will be recovered.",
                      additionalArgs2);
              final var additionalArgs1 = new Object[]{getName(), storageName};
              LogManager.instance()
                  .info(
                      this,
                      "Scanning of free space for collection %s in storage %s started ...",
                      additionalArgs1);

              freeSpaceMap.create(atomicOperation);
              final var filledUpTo = getFilledUpTo(atomicOperation, fileId);
              for (var pageIndex = 0; pageIndex < filledUpTo; pageIndex++) {

                try (final var cacheEntry =
                    loadPageForRead(atomicOperation, fileId, pageIndex)) {
                  final var collectionPage = new CollectionPage(cacheEntry);
                  freeSpaceMap.updatePageFreeSpace(
                      atomicOperation, pageIndex, collectionPage.getMaxRecordSize());
                }

                if (pageIndex > 0 && pageIndex % 1_000 == 0) {
                  final var additionalArgs =
                      new Object[]{
                          pageIndex + 1, filledUpTo, 100L * (pageIndex + 1) / filledUpTo, getName()
                      };
                  LogManager.instance()
                      .info(
                          this,
                          "%d pages out of %d (%d %) were processed in collection %s ...",
                          additionalArgs);
                }
              }

              final var additionalArgs = new Object[]{getName()};
              LogManager.instance()
                  .info(this, "Page scan for collection %s " + "is completed.", additionalArgs);
            }
          } finally {
            releaseExclusiveLock();
          }
        });
  }

  @Override
  public void close() {
    close(true);
  }

  @Override
  public void close(final boolean flush) {
    acquireExclusiveLock();
    try {
      if (flush) {
        synch();
      }
      readCache.closeFile(fileId, flush, writeCache);
      collectionPositionMap.close(flush);
    } finally {
      releaseExclusiveLock();
    }
  }

  @Override
  public void delete(AtomicOperation atomicOperation) {
    executeInsideComponentOperation(
        atomicOperation,
        operation -> {
          acquireExclusiveLock();
          try {
            deleteFile(atomicOperation, fileId);
            collectionPositionMap.delete(atomicOperation);
            freeSpaceMap.delete(atomicOperation);
          } finally {
            releaseExclusiveLock();
          }
        });
  }

  @Override
  public boolean isSystemCollection() {
    return systemCollection;
  }

  @Nullable
  @Override
  public String compression() {
    acquireSharedLock();
    try {
      return null;
    } finally {
      releaseSharedLock();
    }
  }

  @Nullable
  @Override
  public String encryption() {
    acquireSharedLock();
    try {
      return null;
    } finally {
      releaseSharedLock();
    }
  }

  @Override
  public PhysicalPosition allocatePosition(
      final byte recordType, AtomicOperation atomicOperation) {
    return calculateInsideComponentOperation(
        atomicOperation,
        operation -> {
          acquireExclusiveLock();
          try {
            return createPhysicalPosition(
                recordType, collectionPositionMap.allocate(atomicOperation), -1);
          } finally {
            releaseExclusiveLock();
          }
        });
  }

  @Override
  public PhysicalPosition createRecord(
      final byte[] content,
      final int recordVersion,
      final byte recordType,
      final PhysicalPosition allocatedPosition,
      final AtomicOperation atomicOperation) {
    return calculateInsideComponentOperation(
        atomicOperation,
        operation -> {
          acquireExclusiveLock();
          try {
            final var result =
                serializeRecord(
                    content,
                    calculateCollectionEntrySize(content.length),
                    recordType,
                    recordVersion,
                    -1,
                    atomicOperation,
                    entrySize -> findNewPageToWrite(atomicOperation, entrySize),
                    page -> {
                      final var cacheEntry = page.getCacheEntry();
                      try {
                        cacheEntry.close();
                      } catch (final IOException e) {
                        throw BaseException.wrapException(
                            new PaginatedCollectionException(storageName,
                                "Can not store the record",
                                this), e, storageName);
                      }
                    });

            final var nextPageIndex = result[0];
            final var nextPageOffset = result[1];
            assert result[2] == 0;

            updateCollectionState(1, content.length, atomicOperation);

            final long collectionPosition;
            if (allocatedPosition != null) {
              collectionPositionMap.update(
                  allocatedPosition.collectionPosition,
                  new CollectionPositionMapBucket.PositionEntry(nextPageIndex, nextPageOffset),
                  atomicOperation);
              collectionPosition = allocatedPosition.collectionPosition;
            } else {
              collectionPosition =
                  collectionPositionMap.add(nextPageIndex, nextPageOffset, atomicOperation);
            }
            return createPhysicalPosition(recordType, collectionPosition, recordVersion);
          } finally {
            releaseExclusiveLock();
          }
        });
  }

  private CollectionPage findNewPageToWrite(
      final AtomicOperation atomicOperation, final int entrySize) {
    final CollectionPage page;
    try {
      final var nextPageToWrite = findNextFreePageIndexToWrite(entrySize);

      final CacheEntry cacheEntry;
      boolean isNew;
      if (nextPageToWrite >= 0) {
        cacheEntry = loadPageForWrite(atomicOperation, fileId, nextPageToWrite, true);
        isNew = false;
      } else {
        cacheEntry = allocateNewPage(atomicOperation);
        isNew = true;
      }

      page = new CollectionPage(cacheEntry);
      if (isNew) {
        page.init();
      }
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new PaginatedCollectionException(storageName, "Can not store the record", this), e,
          storageName);
    }

    return page;
  }

  private int[] serializeRecord(
      final byte[] content,
      final int len,
      final byte recordType,
      final int recordVersion,
      final long nextRecordPointer,
      final AtomicOperation atomicOperation,
      final Int2ObjectFunction<CollectionPage> pageSupplier,
      final Consumer<CollectionPage> pagePostProcessor)
      throws IOException {

    var bytesToWrite = len;
    var chunkSize = calculateChunkSize(bytesToWrite);

    var nextRecordPointers = nextRecordPointer;
    var nextPageIndex = -1;
    var nextPageOffset = -1;

    while (bytesToWrite > 0) {
      final var page = pageSupplier.apply(Math.max(bytesToWrite, MIN_ENTRY_SIZE + 1));
      if (page == null) {
        return new int[]{nextPageIndex, nextPageOffset, bytesToWrite};
      }

      int maxRecordSize;
      try {
        var availableInPage = page.getMaxRecordSize();
        if (availableInPage > MIN_ENTRY_SIZE) {

          final var pageChunkSize = Math.min(availableInPage, chunkSize);

          final var pair =
              serializeEntryChunk(
                  content, pageChunkSize, bytesToWrite, nextRecordPointers, recordType);
          final var chunk = pair.first;

          final var cacheEntry = page.getCacheEntry();
          nextPageOffset =
              page.appendRecord(
                  recordVersion,
                  chunk,
                  -1,
                  atomicOperation.getBookedRecordPositions(id, cacheEntry.getPageIndex()));
          assert nextPageOffset >= 0;

          bytesToWrite -= pair.second;
          assert bytesToWrite >= 0;

          nextPageIndex = cacheEntry.getPageIndex();

          if (bytesToWrite > 0) {
            chunkSize = calculateChunkSize(bytesToWrite);

            nextRecordPointers = createPagePointer(nextPageIndex, nextPageOffset);
          }
        } else {
          return new int[]{nextPageIndex, nextPageOffset, bytesToWrite};
        }
        maxRecordSize = page.getMaxRecordSize();
      } finally {
        pagePostProcessor.accept(page);
      }

      freeSpaceMap.updatePageFreeSpace(atomicOperation, nextPageIndex, maxRecordSize);
    }

    return new int[]{nextPageIndex, nextPageOffset, 0};
  }

  private static RawPairObjectInteger<byte[]> serializeEntryChunk(
      final byte[] recordContent,
      final int chunkSize,
      final int bytesToWrite,
      final long nextPagePointer,
      final byte recordType) {
    final var chunk = new byte[chunkSize];
    var offset = chunkSize - LongSerializer.LONG_SIZE;

    LongSerializer.INSTANCE.serializeNative(nextPagePointer, chunk, offset);

    var written = 0;
    // entry - entry size - record type
    final var contentSize = bytesToWrite - IntegerSerializer.INT_SIZE - ByteSerializer.BYTE_SIZE;
    // skip first record flag
    final var firstRecordOffset = --offset;

    // there are records data to write
    if (contentSize > 0) {
      final var contentToWrite = Math.min(contentSize, offset);
      System.arraycopy(
          recordContent,
          contentSize - contentToWrite,
          chunk,
          offset - contentToWrite,
          contentToWrite);
      written = contentToWrite;
    }

    var spaceLeft = chunkSize - written - LongSerializer.LONG_SIZE - ByteSerializer.BYTE_SIZE;

    if (spaceLeft > 0) {
      final var spaceToWrite = bytesToWrite - written;
      assert spaceToWrite <= IntegerSerializer.INT_SIZE + ByteSerializer.BYTE_SIZE;

      // we need to write only record type
      if (spaceToWrite == 1) {
        chunk[0] = recordType;
        chunk[firstRecordOffset] = 1;
        written++;
      } else {
        // at least part of record size and record type has to be written
        // record size and record type can be written at once
        if (spaceLeft == IntegerSerializer.INT_SIZE + ByteSerializer.BYTE_SIZE) {
          chunk[0] = recordType;
          IntegerSerializer.serializeNative(
              recordContent.length, chunk, ByteSerializer.BYTE_SIZE);
          chunk[firstRecordOffset] = 1;

          written += IntegerSerializer.INT_SIZE + ByteSerializer.BYTE_SIZE;
        } else {
          final var recordSizePart = spaceToWrite - ByteSerializer.BYTE_SIZE;
          assert recordSizePart <= IntegerSerializer.INT_SIZE;

          if (recordSizePart == IntegerSerializer.INT_SIZE
              && spaceLeft == IntegerSerializer.INT_SIZE) {
            IntegerSerializer.serializeNative(recordContent.length, chunk, 0);
            written += IntegerSerializer.INT_SIZE;
          } else {
            final var byteOrder = ByteOrder.nativeOrder();
            if (byteOrder == ByteOrder.LITTLE_ENDIAN) {
              for (var sizeOffset = (recordSizePart - 1) << 3;
                  sizeOffset >= 0 && spaceLeft > 0;
                  sizeOffset -= 8, spaceLeft--, written++) {
                final var sizeByte = (byte) (0xFF & (recordContent.length >> sizeOffset));
                chunk[spaceLeft - 1] = sizeByte;
              }
            } else {
              for (var sizeOffset = (IntegerSerializer.INT_SIZE - recordSizePart) << 3;
                  sizeOffset < IntegerSerializer.INT_SIZE & spaceLeft > 0;
                  sizeOffset += 8, spaceLeft--, written++) {
                final var sizeByte = (byte) (0xFF & (recordContent.length >> sizeOffset));
                chunk[spaceLeft - 1] = sizeByte;
              }
            }

            if (spaceLeft > 0) {
              chunk[0] = recordType;
              chunk[firstRecordOffset] = 1;
              written++;
            }
          }
        }
      }
    }

    return new RawPairObjectInteger<>(chunk, written);
  }

  private int findNextFreePageIndexToWrite(int bytesToWrite) throws IOException {
    if (bytesToWrite > MAX_ENTRY_SIZE) {
      bytesToWrite = MAX_ENTRY_SIZE;
    }

    int pageIndex;

    // if page is empty we will not find it inside of free mpa because of the policy
    // that always requests to find page which is bigger than current record
    // so we find page with at least half of the space at the worst case
    // we will split record by two anyway.
    if (bytesToWrite >= DurablePage.MAX_PAGE_SIZE_BYTES - FreeSpaceMap.NORMALIZATION_INTERVAL) {
      final var halfChunkSize = calculateChunkSize(bytesToWrite / 2);
      pageIndex = freeSpaceMap.findFreePage(halfChunkSize / 2);

      return pageIndex;
    }

    var chunkSize = calculateChunkSize(bytesToWrite);
    pageIndex = freeSpaceMap.findFreePage(chunkSize);

    if (pageIndex < 0 && bytesToWrite > MAX_ENTRY_SIZE / 2) {
      final var halfChunkSize = calculateChunkSize(bytesToWrite / 2);
      if (halfChunkSize > 0) {
        pageIndex = freeSpaceMap.findFreePage(halfChunkSize / 2);
      }
    }

    return pageIndex;
  }

  private static int calculateCollectionEntrySize(final int contentSize) {
    // content + record type + content size
    return contentSize + ByteSerializer.BYTE_SIZE + IntegerSerializer.INT_SIZE;
  }

  private static int calculateContentSizeFromCollectionEntrySize(final int contentSize) {
    // content + record type + content size
    return contentSize - ByteSerializer.BYTE_SIZE - IntegerSerializer.INT_SIZE;
  }

  private static int calculateChunkSize(final int entrySize) {
    // entry content + first entry flag + next entry pointer
    return entrySize + ByteSerializer.BYTE_SIZE + LongSerializer.LONG_SIZE;
  }

  @Nonnull
  public RawBuffer readRecord(final long collectionPosition) throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final var atomicOperation = atomicOperationsManager.getCurrentOperation();

        final var positionEntry =
            collectionPositionMap.get(collectionPosition, atomicOperation);
        if (positionEntry == null) {
          throw new RecordNotFoundException(storageName, new RecordId(id, collectionPosition));
        }
        return internalReadRecord(
            collectionPosition,
            positionEntry.getPageIndex(),
            positionEntry.getRecordPosition(),
            atomicOperation);
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Nonnull
  private RawBuffer internalReadRecord(
      final long collectionPosition,
      long pageIndex,
      int recordPosition,
      final AtomicOperation atomicOperation)
      throws IOException {

    var recordVersion = 0;

    final List<byte[]> recordChunks = new ArrayList<>(2);
    var contentSize = 0;

    long nextPagePointer;
    var firstEntry = true;
    do {
      try (final var cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex)) {
        final var localPage = new CollectionPage(cacheEntry);
        if (firstEntry) {
          recordVersion = localPage.getRecordVersion(recordPosition);
        }

        if (localPage.isDeleted(recordPosition)) {
          if (recordChunks.isEmpty()) {
            throw new RecordNotFoundException(storageName, new RecordId(id, collectionPosition));
          } else {
            throw new PaginatedCollectionException(storageName,
                "Content of record " + new RecordId(id, collectionPosition) + " was broken", this);
          }
        }

        final var content =
            localPage.getRecordBinaryValue(
                recordPosition, 0, localPage.getRecordSize(recordPosition));
        assert content != null;

        if (firstEntry
            && content[content.length - LongSerializer.LONG_SIZE - ByteSerializer.BYTE_SIZE]
            == 0) {
          throw new RecordNotFoundException(storageName, new RecordId(id, collectionPosition));
        }

        recordChunks.add(content);
        nextPagePointer =
            LongSerializer.INSTANCE.deserializeNative(
                content, content.length - LongSerializer.LONG_SIZE);
        contentSize += content.length - LongSerializer.LONG_SIZE - ByteSerializer.BYTE_SIZE;

        firstEntry = false;
      }

      pageIndex = getPageIndex(nextPagePointer);
      recordPosition = getRecordPosition(nextPagePointer);
    } while (nextPagePointer >= 0);

    var fullContent = convertRecordChunksToSingleChunk(recordChunks, contentSize);

    if (fullContent == null) {
      throw new RecordNotFoundException(storageName, new RecordId(id, collectionPosition));
    }

    var fullContentPosition = 0;

    final var recordType = fullContent[fullContentPosition];
    fullContentPosition++;

    final var readContentSize =
        IntegerSerializer.deserializeNative(fullContent, fullContentPosition);
    fullContentPosition += IntegerSerializer.INT_SIZE;

    var recordContent =
        Arrays.copyOfRange(fullContent, fullContentPosition, fullContentPosition + readContentSize);

    return new RawBuffer(recordContent, recordVersion, recordType);
  }

  @Override
  public boolean deleteRecord(AtomicOperation atomicOperation, final long collectionPosition) {
    return calculateInsideComponentOperation(
        atomicOperation,
        operation -> {
          acquireExclusiveLock();
          try {
            final var positionEntry =
                collectionPositionMap.get(collectionPosition, atomicOperation);
            if (positionEntry == null) {
              return false;
            }

            var pageIndex = positionEntry.getPageIndex();
            var recordPosition = positionEntry.getRecordPosition();

            long nextPagePointer;
            var removedContentSize = 0;
            int removeRecordSize;

            do {
              var cacheEntryReleased = false;
              final int maxRecordSize;
              var cacheEntry = loadPageForWrite(atomicOperation, fileId, pageIndex, true);
              try {
                var localPage = new CollectionPage(cacheEntry);

                if (localPage.isDeleted(recordPosition)) {
                  if (removedContentSize == 0) {
                    cacheEntryReleased = true;
                    cacheEntry.close();
                    return false;
                  } else {
                    throw new PaginatedCollectionException(storageName,
                        "Content of record " + new RecordId(id, collectionPosition) + " was broken",
                        this);
                  }
                } else if (removedContentSize == 0) {
                  cacheEntry.close();

                  cacheEntry = loadPageForWrite(atomicOperation, fileId, pageIndex, true);

                  localPage = new CollectionPage(cacheEntry);
                }

                final var initialFreeSpace = localPage.getFreeSpace();
                final var content = localPage.deleteRecord(recordPosition, true);
                atomicOperation.addDeletedRecordPosition(
                    id, cacheEntry.getPageIndex(), recordPosition);
                assert content != null;
                removeRecordSize = calculateContentSizeFromCollectionEntrySize(content.length);

                maxRecordSize = localPage.getMaxRecordSize();
                removedContentSize += localPage.getFreeSpace() - initialFreeSpace;
                nextPagePointer =
                    LongSerializer.INSTANCE.deserializeNative(
                        content, content.length - LongSerializer.LONG_SIZE);
              } finally {
                if (!cacheEntryReleased) {
                  cacheEntry.close();
                }
              }

              freeSpaceMap.updatePageFreeSpace(atomicOperation, (int) pageIndex, maxRecordSize);

              pageIndex = getPageIndex(nextPagePointer);
              recordPosition = getRecordPosition(nextPagePointer);
            } while (nextPagePointer >= 0);

            updateCollectionState(-1, -removeRecordSize, atomicOperation);

            collectionPositionMap.remove(collectionPosition, atomicOperation);
            return true;
          } finally {
            releaseExclusiveLock();
          }
        });
  }

  @Override
  public void updateRecord(
      final long collectionPosition,
      final byte[] content,
      final int recordVersion,
      final byte recordType,
      final AtomicOperation atomicOperation) {
    executeInsideComponentOperation(
        atomicOperation,
        operation -> {
          acquireExclusiveLock();
          try {
            final var positionEntry =
                collectionPositionMap.get(collectionPosition, atomicOperation);
            if (positionEntry == null) {
              throw new RecordNotFoundException(storageName, new RecordId(id, collectionPosition));
            }

            var oldContentSize = 0;
            var nextPageIndex = (int) positionEntry.getPageIndex();
            var nextRecordPosition = positionEntry.getRecordPosition();

            var nextPagePointer = createPagePointer(nextPageIndex, nextRecordPosition);

            var storedPages = new ArrayList<CollectionPage>();

            while (nextPagePointer >= 0) {
              final var cacheEntry =
                  loadPageForWrite(atomicOperation, fileId, nextPageIndex, true);
              final var page = new CollectionPage(cacheEntry);
              final var deletedRecord = page.deleteRecord(nextRecordPosition, true);
              assert deletedRecord != null;
              oldContentSize = calculateContentSizeFromCollectionEntrySize(deletedRecord.length);
              nextPagePointer =
                  LongSerializer.INSTANCE.deserializeNative(
                      deletedRecord, deletedRecord.length - LongSerializer.LONG_SIZE);

              nextPageIndex = (int) getPageIndex(nextPagePointer);
              nextRecordPosition = getRecordPosition(nextPagePointer);

              storedPages.add(page);
            }

            final var reverseIterator =
                storedPages.listIterator(storedPages.size());
            var result =
                serializeRecord(
                    content,
                    calculateCollectionEntrySize(content.length),
                    recordType,
                    recordVersion,
                    -1,
                    atomicOperation,
                    entrySize -> {
                      if (reverseIterator.hasPrevious()) {
                        return reverseIterator.previous();
                      }
                      //noinspection ReturnOfNull
                      return null;
                    },
                    page -> {
                      final var cacheEntry = page.getCacheEntry();
                      try {
                        cacheEntry.close();
                      } catch (final IOException e) {
                        throw BaseException.wrapException(
                            new PaginatedCollectionException(storageName,
                                "Can not update record with rid "
                                    + new RecordId(id, collectionPosition), this),
                            e, storageName);
                      }
                    });

            nextPageIndex = result[0];
            nextRecordPosition = result[1];

            while (reverseIterator.hasPrevious()) {
              final var page = reverseIterator.previous();
              page.getCacheEntry().close();
            }

            if (result[2] != 0) {
              result =
                  serializeRecord(
                      content,
                      result[2],
                      recordType,
                      recordVersion,
                      createPagePointer(nextPageIndex, nextRecordPosition),
                      atomicOperation,
                      entrySize -> findNewPageToWrite(atomicOperation, entrySize),
                      page -> {
                        final var cacheEntry = page.getCacheEntry();
                        try {
                          cacheEntry.close();
                        } catch (final IOException e) {
                          throw BaseException.wrapException(
                              new PaginatedCollectionException(storageName,
                                  "Can not update record with rid "
                                      + new RecordId(id, collectionPosition), this),
                              e, storageName);
                        }
                      });

              nextPageIndex = result[0];
              nextRecordPosition = result[1];
            }

            assert result[2] == 0;
            updateCollectionState(0, content.length - oldContentSize, atomicOperation);

            if (nextPageIndex != positionEntry.getPageIndex()
                || nextRecordPosition != positionEntry.getRecordPosition()) {
              collectionPositionMap.update(
                  collectionPosition,
                  new CollectionPositionMapBucket.PositionEntry(nextPageIndex, nextRecordPosition),
                  atomicOperation);
            }
          } finally {
            releaseExclusiveLock();
          }
        });
  }

  @Override
  public void updateRecordVersion(long collectionPosition, int recordVersion,
      AtomicOperation atomicOperation) {
    executeInsideComponentOperation(
        atomicOperation,
        operation -> {
          acquireExclusiveLock();
          try {
            final var positionEntry =
                collectionPositionMap.get(collectionPosition, atomicOperation);
            if (positionEntry == null) {
              throw new RecordNotFoundException(storageName, new RecordId(id, collectionPosition));
            }

            final var pageIndex = positionEntry.getPageIndex();
            final var recordPosition = positionEntry.getRecordPosition();

            try (final var cacheEntry = loadPageForWrite(atomicOperation, fileId, pageIndex,
                true)) {
              final var localPage = new CollectionPage(cacheEntry);
              if (localPage.getRecordByteValue(
                  recordPosition, -LongSerializer.LONG_SIZE - ByteSerializer.BYTE_SIZE)
                  == 0) {
                throw new RecordNotFoundException(storageName,
                    new RecordId(id, collectionPosition));
              }

              if (!localPage.updateRecordVersion(recordPosition)) {
                throw new RecordNotFoundException(storageName,
                    new RecordId(id, collectionPosition));
              }
            }
          } finally {
            releaseExclusiveLock();
          }
        });
  }

  @Override
  public long getTombstonesCount() {
    return 0;
  }

  @Nullable
  @Override
  public PhysicalPosition getPhysicalPosition(final PhysicalPosition position)
      throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final var atomicOperation = atomicOperationsManager.getCurrentOperation();
        final var collectionPosition = position.collectionPosition;
        final var positionEntry =
            collectionPositionMap.get(collectionPosition, atomicOperation);

        if (positionEntry == null) {
          return null;
        }

        final var pageIndex = positionEntry.getPageIndex();
        final var recordPosition = positionEntry.getRecordPosition();

        try (final var cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex)) {
          final var localPage = new CollectionPage(cacheEntry);
          if (localPage.isDeleted(recordPosition)) {
            return null;
          }

          if (localPage.getRecordByteValue(
              recordPosition, -LongSerializer.LONG_SIZE - ByteSerializer.BYTE_SIZE)
              == 0) {
            return null;
          }

          final var physicalPosition = new PhysicalPosition();
          physicalPosition.recordSize = -1;

          physicalPosition.recordType = localPage.getRecordByteValue(recordPosition, 0);
          physicalPosition.recordVersion = localPage.getRecordVersion(recordPosition);
          physicalPosition.collectionPosition = position.collectionPosition;

          return physicalPosition;
        }
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public boolean exists(long collectionPosition) throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final var atomicOperation = atomicOperationsManager.getCurrentOperation();
        final var positionEntry =
            collectionPositionMap.get(collectionPosition, atomicOperation);

        if (positionEntry == null) {
          return false;
        }

        final var pageIndex = positionEntry.getPageIndex();
        final var recordPosition = positionEntry.getRecordPosition();

        try (final var cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex)) {
          final var localPage = new CollectionPage(cacheEntry);
          return !localPage.isDeleted(recordPosition);
        }
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public long getEntries() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final var atomicOperation = atomicOperationsManager.getCurrentOperation();
        try (final var pinnedStateEntry =
            loadPageForRead(atomicOperation, fileId, STATE_ENTRY_INDEX)) {
          return new PaginatedCollectionStateV2(pinnedStateEntry).getSize();
        }
      } finally {
        releaseSharedLock();
      }
    } catch (final IOException ioe) {
      throw BaseException.wrapException(
          new PaginatedCollectionException(storageName,
              "Error during retrieval of size of '" + getName() + "' collection", this),
          ioe, storageName);
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public long getFirstPosition() throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final var atomicOperation = atomicOperationsManager.getCurrentOperation();
        return collectionPositionMap.getFirstPosition(atomicOperation);
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public long getLastPosition() throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final var atomicOperation = atomicOperationsManager.getCurrentOperation();
        return collectionPositionMap.getLastPosition(atomicOperation);
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public long getNextFreePosition() throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final var atomicOperation = atomicOperationsManager.getCurrentOperation();
        return collectionPositionMap.getNextPosition(atomicOperation);
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public String getFileName() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        return writeCache.fileNameById(fileId);
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public int getId() {
    return id;
  }

  /**
   * Returns the fileId used in disk cache.
   */
  public long getFileId() {
    return fileId;
  }

  @Override
  public void synch() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        writeCache.flush(fileId);
        collectionPositionMap.flush();
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public long getRecordsSize() throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final var atomicOperation = atomicOperationsManager.getCurrentOperation();

        try (final var pinnedStateEntry =
            loadPageForRead(atomicOperation, fileId, STATE_ENTRY_INDEX)) {
          return new PaginatedCollectionStateV2(pinnedStateEntry).getRecordsSize();
        }
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public PhysicalPosition[] higherPositions(final PhysicalPosition position, int limit)
      throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final var atomicOperation = atomicOperationsManager.getCurrentOperation();
        final var collectionPositions =
            collectionPositionMap.higherPositions(position.collectionPosition, atomicOperation,
                limit);
        return convertToPhysicalPositions(collectionPositions);
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public PhysicalPosition[] ceilingPositions(final PhysicalPosition position, int limit)
      throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final var atomicOperation = atomicOperationsManager.getCurrentOperation();
        final var collectionPositions =
            collectionPositionMap.ceilingPositions(position.collectionPosition, atomicOperation,
                limit);
        return convertToPhysicalPositions(collectionPositions);
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public PhysicalPosition[] lowerPositions(final PhysicalPosition position, int limit)
      throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final var atomicOperation = atomicOperationsManager.getCurrentOperation();
        final var collectionPositions =
            collectionPositionMap.lowerPositions(position.collectionPosition, atomicOperation,
                limit);
        return convertToPhysicalPositions(collectionPositions);
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public PhysicalPosition[] floorPositions(final PhysicalPosition position, int limit)
      throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final var atomicOperation = atomicOperationsManager.getCurrentOperation();
        final var collectionPositions =
            collectionPositionMap.floorPositions(position.collectionPosition, atomicOperation,
                limit);
        return convertToPhysicalPositions(collectionPositions);
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public RecordConflictStrategy getRecordConflictStrategy() {
    return recordConflictStrategy;
  }

  @Override
  public void setRecordConflictStrategy(final String stringValue) {
    acquireExclusiveLock();
    try {
      recordConflictStrategy =
          YouTrackDBEnginesManager.instance().getRecordConflictStrategy().getStrategy(stringValue);
    } finally {
      releaseExclusiveLock();
    }
  }

  private void updateCollectionState(
      final long sizeDiff, long recordSizeDiff, final AtomicOperation atomicOperation)
      throws IOException {
    try (final var pinnedStateEntry =
        loadPageForWrite(atomicOperation, fileId, STATE_ENTRY_INDEX, true)) {
      final var paginatedCollectionState =
          new PaginatedCollectionStateV2(pinnedStateEntry);
      if (sizeDiff != 0) {
        paginatedCollectionState.setSize((int) (paginatedCollectionState.getSize() + sizeDiff));
      }
      if (recordSizeDiff != 0) {
        paginatedCollectionState.setRecordsSize(
            (int) (paginatedCollectionState.getRecordsSize() + recordSizeDiff));
      }
    }
  }

  private void init(final int id, final String name, final String conflictStrategy)
      throws IOException {
    FileUtils.checkValidName(name);

    if (conflictStrategy != null) {
      this.recordConflictStrategy =
          YouTrackDBEnginesManager.instance().getRecordConflictStrategy()
              .getStrategy(conflictStrategy);
    }

    final var metrics = YouTrackDBEnginesManager.instance().getMetricsRegistry();
    this.meters = new Meters(
        metrics.classMetric(CoreMetrics.RECORD_CREATE_RATE, storageName, name),
        metrics.classMetric(CoreMetrics.RECORD_READ_RATE, storageName, name),
        metrics.classMetric(CoreMetrics.RECORD_UPDATE_RATE, storageName, name),
        metrics.classMetric(CoreMetrics.RECORD_DELETE_RATE, storageName, name),
        metrics.classMetric(CoreMetrics.RECORD_CONFLICT_RATE, storageName, name)
    );
    this.id = id;
  }

  @Override
  public void setCollectionName(final String newName) {
    acquireExclusiveLock();
    try {
      writeCache.renameFile(fileId, newName + getExtension());
      collectionPositionMap.rename(newName);
      freeSpaceMap.rename(newName);

      setName(newName);
    } catch (IOException e) {
      throw BaseException.wrapException(
          new PaginatedCollectionException(storageName, "Error during renaming of collection",
              this),
          e, storageName);
    } finally {
      releaseExclusiveLock();
    }
  }

  private static PhysicalPosition createPhysicalPosition(
      final byte recordType, final long collectionPosition, final int version) {
    final var physicalPosition = new PhysicalPosition();
    physicalPosition.recordType = recordType;
    physicalPosition.recordSize = -1;
    physicalPosition.collectionPosition = collectionPosition;
    physicalPosition.recordVersion = version;
    return physicalPosition;
  }

  private static byte[] convertRecordChunksToSingleChunk(
      final List<byte[]> recordChunks, final int contentSize) {
    final byte[] fullContent;
    if (recordChunks.size() == 1) {
      fullContent = recordChunks.getFirst();
    } else {
      fullContent = new byte[contentSize + LongSerializer.LONG_SIZE + ByteSerializer.BYTE_SIZE];
      var fullContentPosition = 0;
      for (final var recordChuck : recordChunks) {
        System.arraycopy(
            recordChuck,
            0,
            fullContent,
            fullContentPosition,
            recordChuck.length - LongSerializer.LONG_SIZE - ByteSerializer.BYTE_SIZE);
        fullContentPosition +=
            recordChuck.length - LongSerializer.LONG_SIZE - ByteSerializer.BYTE_SIZE;
      }
    }
    return fullContent;
  }

  private static long createPagePointer(final long pageIndex, final int pagePosition) {
    return pageIndex << PAGE_INDEX_OFFSET | pagePosition;
  }

  private static int getRecordPosition(final long nextPagePointer) {
    return (int) (nextPagePointer & RECORD_POSITION_MASK);
  }

  private static long getPageIndex(final long nextPagePointer) {
    return nextPagePointer >>> PAGE_INDEX_OFFSET;
  }

  private CacheEntry allocateNewPage(AtomicOperation atomicOperation) throws IOException {
    CacheEntry cacheEntry;
    try (final var stateCacheEntry =
        loadPageForWrite(atomicOperation, fileId, STATE_ENTRY_INDEX, true)) {
      final var collectionState = new PaginatedCollectionStateV2(stateCacheEntry);
      final var fileSize = collectionState.getFileSize();
      final var filledUpTo = getFilledUpTo(atomicOperation, fileId);

      if (fileSize == filledUpTo - 1) {
        cacheEntry = addPage(atomicOperation, fileId);
      } else {
        assert fileSize < filledUpTo - 1;

        cacheEntry = loadPageForWrite(atomicOperation, fileId, fileSize + 1, false);
      }

      collectionState.setFileSize(fileSize + 1);
    }
    return cacheEntry;
  }

  private void initCusterState(final AtomicOperation atomicOperation) throws IOException {
    final CacheEntry stateEntry;
    if (getFilledUpTo(atomicOperation, fileId) == 0) {
      stateEntry = addPage(atomicOperation, fileId);
    } else {
      stateEntry = loadPageForWrite(atomicOperation, fileId, STATE_ENTRY_INDEX, false);
    }

    assert stateEntry.getPageIndex() == 0;
    try {
      final var paginatedCollectionState =
          new PaginatedCollectionStateV2(stateEntry);
      paginatedCollectionState.setSize(0);
      paginatedCollectionState.setRecordsSize(0);
      paginatedCollectionState.setFileSize(0);
    } finally {
      stateEntry.close();
    }
  }

  private static PhysicalPosition[] convertToPhysicalPositions(final long[] collectionPositions) {
    final var positions = new PhysicalPosition[collectionPositions.length];
    for (var i = 0; i < positions.length; i++) {
      final var physicalPosition = new PhysicalPosition();
      physicalPosition.collectionPosition = collectionPositions[i];
      positions[i] = physicalPosition;
    }
    return positions;
  }

  public RECORD_STATUS getRecordStatus(final long collectionPosition) throws IOException {
    final var atomicOperation = atomicOperationsManager.getCurrentOperation();
    acquireSharedLock();
    try {
      final var status = collectionPositionMap.getStatus(collectionPosition, atomicOperation);

      return switch (status) {
        case CollectionPositionMapBucket.NOT_EXISTENT -> RECORD_STATUS.NOT_EXISTENT;
        case CollectionPositionMapBucket.ALLOCATED -> RECORD_STATUS.ALLOCATED;
        case CollectionPositionMapBucket.FILLED -> RECORD_STATUS.PRESENT;
        case CollectionPositionMapBucket.REMOVED -> RECORD_STATUS.REMOVED;
        default -> throw new IllegalStateException(
            "Invalid record status : " + status + " for collection " + getName());
      };

    } finally {
      releaseSharedLock();
    }
  }

  @Override
  public void acquireAtomicExclusiveLock() {
    atomicOperationsManager.acquireExclusiveLockTillOperationComplete(this);
  }

  @Override
  public String toString() {
    return "disk collection: " + getName();
  }

  @Nullable
  @Override
  public CollectionBrowsePage nextPage(final long lastPosition) throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final var atomicOperation = atomicOperationsManager.getCurrentOperation();

        final var nextPositions =
            collectionPositionMap.higherPositionsEntries(lastPosition, atomicOperation);
        if (nextPositions.length > 0) {
          final var newLastPosition = nextPositions[nextPositions.length - 1].getPosition();
          final List<CollectionBrowseEntry> nexv = new ArrayList<>(nextPositions.length);
          for (final var pos : nextPositions) {
            final var buff =
                internalReadRecord(
                    pos.getPosition(), pos.getPage(), pos.getOffset(), atomicOperation);
            nexv.add(new CollectionBrowseEntry(pos.getPosition(), buff));
          }
          return new CollectionBrowsePage(nexv, newLastPosition);
        } else {
          return null;
        }
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public Meters meters() {
    return meters;
  }
}
