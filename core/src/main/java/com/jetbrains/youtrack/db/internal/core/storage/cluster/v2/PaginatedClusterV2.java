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
package com.jetbrains.youtrack.db.internal.core.storage.cluster.v2;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.internal.common.io.FileUtils;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.profiler.metrics.CoreMetrics;
import com.jetbrains.youtrack.db.internal.common.serialization.types.ByteSerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrack.db.internal.common.util.RawPairObjectInteger;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.config.StorageClusterConfiguration;
import com.jetbrains.youtrack.db.internal.core.config.StoragePaginatedClusterConfiguration;
import com.jetbrains.youtrack.db.internal.core.conflict.RecordConflictStrategy;
import com.jetbrains.youtrack.db.internal.core.exception.PaginatedClusterException;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.metadata.MetadataInternal;
import com.jetbrains.youtrack.db.internal.core.storage.PhysicalPosition;
import com.jetbrains.youtrack.db.internal.core.storage.RawBuffer;
import com.jetbrains.youtrack.db.internal.core.storage.Storage;
import com.jetbrains.youtrack.db.internal.core.storage.StorageCluster;
import com.jetbrains.youtrack.db.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrack.db.internal.core.storage.cluster.ClusterPage;
import com.jetbrains.youtrack.db.internal.core.storage.cluster.ClusterPositionMap;
import com.jetbrains.youtrack.db.internal.core.storage.cluster.ClusterPositionMapBucket;
import com.jetbrains.youtrack.db.internal.core.storage.cluster.PaginatedCluster;
import com.jetbrains.youtrack.db.internal.core.storage.cluster.v2.ClusterPositionMapV2.ClusterPositionEntry;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractPaginatedStorage;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.ClusterBrowseEntry;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.ClusterBrowsePage;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.base.DurablePage;
import it.unimi.dsi.fastutil.ints.Int2ObjectFunction;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.ListIterator;
import java.util.Optional;
import java.util.function.Consumer;
import javax.annotation.Nonnull;

/**
 * @since 10/7/13
 */
public final class PaginatedClusterV2 extends PaginatedCluster {

  // max chunk size - nex page pointer - first record flag
  private static final int MAX_ENTRY_SIZE =
      ClusterPage.MAX_RECORD_SIZE - ByteSerializer.BYTE_SIZE - LongSerializer.LONG_SIZE;

  private static final int MIN_ENTRY_SIZE = ByteSerializer.BYTE_SIZE + LongSerializer.LONG_SIZE;

  private static final int STATE_ENTRY_INDEX = 0;
  private static final int BINARY_VERSION = 2;

  private static final int PAGE_INDEX_OFFSET = 16;
  private static final int RECORD_POSITION_MASK = 0xFFFF;

  private final boolean systemCluster;
  private final ClusterPositionMapV2 clusterPositionMap;
  private final FreeSpaceMap freeSpaceMap;
  private final String storageName;

  private StorageCluster.Meters meters = Meters.NOOP;

  private volatile int id;
  private long fileId;
  private RecordConflictStrategy recordConflictStrategy;

  public PaginatedClusterV2(
      @Nonnull final String name, @Nonnull final AbstractPaginatedStorage storage) {
    this(
        name,
        DEF_EXTENSION,
        ClusterPositionMap.DEF_EXTENSION,
        FreeSpaceMap.DEF_EXTENSION,
        storage);
  }

  public PaginatedClusterV2(
      final String name,
      final String dataExtension,
      final String cpmExtension,
      final String fsmExtension,
      final AbstractPaginatedStorage storage) {
    super(storage, name, dataExtension, name + dataExtension);

    systemCluster = MetadataInternal.SYSTEM_CLUSTER.contains(name);
    clusterPositionMap = new ClusterPositionMapV2(storage, getName(), getFullName(), cpmExtension);
    freeSpaceMap = new FreeSpaceMap(storage, name, fsmExtension, getFullName());
    storageName = storage.getName();

  }

  @Override
  public void configure(final int id, final String clusterName) throws IOException {
    acquireExclusiveLock();
    try {
      init(id, clusterName, null);
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
        final AtomicOperation atomicOperation = atomicOperationsManager.getCurrentOperation();
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
  public StoragePaginatedClusterConfiguration generateClusterConfig() {
    acquireSharedLock();
    try {
      return new StoragePaginatedClusterConfiguration(
          id,
          getName(),
          null,
          true,
          StoragePaginatedClusterConfiguration.DEFAULT_GROW_FACTOR,
          StoragePaginatedClusterConfiguration.DEFAULT_GROW_FACTOR,
          null,
          null,
          null,
          Optional.ofNullable(recordConflictStrategy)
              .map(RecordConflictStrategy::getName)
              .orElse(null),
          StorageClusterConfiguration.STATUS.ONLINE,
          BINARY_VERSION);

    } finally {
      releaseSharedLock();
    }
  }

  @Override
  public void configure(final Storage storage, final StorageClusterConfiguration config)
      throws IOException {
    acquireExclusiveLock();
    try {
      init(
          config.getId(),
          config.getName(),
          ((StoragePaginatedClusterConfiguration) config).conflictStrategy);
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
            clusterPositionMap.create(atomicOperation);
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
            clusterPositionMap.open(atomicOperation);
            if (freeSpaceMap.exists(atomicOperation)) {
              freeSpaceMap.open(atomicOperation);
            } else {
              final Object[] additionalArgs2 = new Object[]{getName(), storageName};
              LogManager.instance()
                  .info(
                      this,
                      "Free space map is absent inside of %s cluster of storage %s . Information"
                          + " about free space present inside of each page will be recovered.",
                      additionalArgs2);
              final Object[] additionalArgs1 = new Object[]{getName(), storageName};
              LogManager.instance()
                  .info(
                      this,
                      "Scanning of free space for cluster %s in storage %s started ...",
                      additionalArgs1);

              freeSpaceMap.create(atomicOperation);
              final long filledUpTo = getFilledUpTo(atomicOperation, fileId);
              for (int pageIndex = 0; pageIndex < filledUpTo; pageIndex++) {

                try (final CacheEntry cacheEntry =
                    loadPageForRead(atomicOperation, fileId, pageIndex)) {
                  final ClusterPage clusterPage = new ClusterPage(cacheEntry);
                  freeSpaceMap.updatePageFreeSpace(
                      atomicOperation, pageIndex, clusterPage.getMaxRecordSize());
                }

                if (pageIndex > 0 && pageIndex % 1_000 == 0) {
                  final Object[] additionalArgs =
                      new Object[]{
                          pageIndex + 1, filledUpTo, 100L * (pageIndex + 1) / filledUpTo, getName()
                      };
                  LogManager.instance()
                      .info(
                          this,
                          "%d pages out of %d (%d %) were processed in cluster %s ...",
                          additionalArgs);
                }
              }

              final Object[] additionalArgs = new Object[]{getName()};
              LogManager.instance()
                  .info(this, "Page scan for cluster %s " + "is completed.", additionalArgs);
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
      clusterPositionMap.close(flush);
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
            clusterPositionMap.delete(atomicOperation);
            freeSpaceMap.delete(atomicOperation);
          } finally {
            releaseExclusiveLock();
          }
        });
  }

  @Override
  public boolean isSystemCluster() {
    return systemCluster;
  }

  @Override
  public String compression() {
    acquireSharedLock();
    try {
      return null;
    } finally {
      releaseSharedLock();
    }
  }

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
                recordType, clusterPositionMap.allocate(atomicOperation), -1);
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
            final int[] result =
                serializeRecord(
                    content,
                    calculateClusterEntrySize(content.length),
                    recordType,
                    recordVersion,
                    -1,
                    atomicOperation,
                    entrySize -> findNewPageToWrite(atomicOperation, entrySize),
                    page -> {
                      final CacheEntry cacheEntry = page.getCacheEntry();
                      try {
                        cacheEntry.close();
                      } catch (final IOException e) {
                        throw BaseException.wrapException(
                            new PaginatedClusterException("Can not store the record", this), e);
                      }
                    });

            final int nextPageIndex = result[0];
            final int nextPageOffset = result[1];
            assert result[2] == 0;

            updateClusterState(1, content.length, atomicOperation);

            final long clusterPosition;
            if (allocatedPosition != null) {
              clusterPositionMap.update(
                  allocatedPosition.clusterPosition,
                  new ClusterPositionMapBucket.PositionEntry(nextPageIndex, nextPageOffset),
                  atomicOperation);
              clusterPosition = allocatedPosition.clusterPosition;
            } else {
              clusterPosition =
                  clusterPositionMap.add(nextPageIndex, nextPageOffset, atomicOperation);
            }
            return createPhysicalPosition(recordType, clusterPosition, recordVersion);
          } finally {
            releaseExclusiveLock();
          }
        });
  }

  private ClusterPage findNewPageToWrite(
      final AtomicOperation atomicOperation, final int entrySize) {
    final ClusterPage page;
    try {
      final int nextPageToWrite = findNextFreePageIndexToWrite(entrySize);

      final CacheEntry cacheEntry;
      boolean isNew;
      if (nextPageToWrite >= 0) {
        cacheEntry = loadPageForWrite(atomicOperation, fileId, nextPageToWrite, true);
        isNew = false;
      } else {
        cacheEntry = allocateNewPage(atomicOperation);
        isNew = true;
      }

      page = new ClusterPage(cacheEntry);
      if (isNew) {
        page.init();
      }
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new PaginatedClusterException("Can not store the record", this), e);
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
      final Int2ObjectFunction<ClusterPage> pageSupplier,
      final Consumer<ClusterPage> pagePostProcessor)
      throws IOException {

    int bytesToWrite = len;
    int chunkSize = calculateChunkSize(bytesToWrite);

    long nextRecordPointers = nextRecordPointer;
    int nextPageIndex = -1;
    int nextPageOffset = -1;

    while (bytesToWrite > 0) {
      final ClusterPage page = pageSupplier.apply(bytesToWrite);
      if (page == null) {
        return new int[]{nextPageIndex, nextPageOffset, bytesToWrite};
      }

      int maxRecordSize;
      try {
        int availableInPage = page.getMaxRecordSize();
        if (availableInPage > MIN_ENTRY_SIZE) {

          final int pageChunkSize = Math.min(availableInPage, chunkSize);

          final RawPairObjectInteger<byte[]> pair =
              serializeEntryChunk(
                  content, pageChunkSize, bytesToWrite, nextRecordPointers, recordType);
          final byte[] chunk = pair.first;

          final CacheEntry cacheEntry = page.getCacheEntry();
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
    final byte[] chunk = new byte[chunkSize];
    int offset = chunkSize - LongSerializer.LONG_SIZE;

    LongSerializer.INSTANCE.serializeNative(nextPagePointer, chunk, offset);

    int written = 0;
    // entry - entry size - record type
    final int contentSize = bytesToWrite - IntegerSerializer.INT_SIZE - ByteSerializer.BYTE_SIZE;
    // skip first record flag
    final int firstRecordOffset = --offset;

    // there are records data to write
    if (contentSize > 0) {
      final int contentToWrite = Math.min(contentSize, offset);
      System.arraycopy(
          recordContent,
          contentSize - contentToWrite,
          chunk,
          offset - contentToWrite,
          contentToWrite);
      written = contentToWrite;
    }

    int spaceLeft = chunkSize - written - LongSerializer.LONG_SIZE - ByteSerializer.BYTE_SIZE;

    if (spaceLeft > 0) {
      final int spaceToWrite = bytesToWrite - written;
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
          IntegerSerializer.INSTANCE.serializeNative(
              recordContent.length, chunk, ByteSerializer.BYTE_SIZE);
          chunk[firstRecordOffset] = 1;

          written += IntegerSerializer.INT_SIZE + ByteSerializer.BYTE_SIZE;
        } else {
          final int recordSizePart = spaceToWrite - ByteSerializer.BYTE_SIZE;
          assert recordSizePart <= IntegerSerializer.INT_SIZE;

          if (recordSizePart == IntegerSerializer.INT_SIZE
              && spaceLeft == IntegerSerializer.INT_SIZE) {
            IntegerSerializer.INSTANCE.serializeNative(recordContent.length, chunk, 0);
            written += IntegerSerializer.INT_SIZE;
          } else {
            final ByteOrder byteOrder = ByteOrder.nativeOrder();
            if (byteOrder == ByteOrder.LITTLE_ENDIAN) {
              for (int sizeOffset = (recordSizePart - 1) << 3;
                  sizeOffset >= 0 && spaceLeft > 0;
                  sizeOffset -= 8, spaceLeft--, written++) {
                final byte sizeByte = (byte) (0xFF & (recordContent.length >> sizeOffset));
                chunk[spaceLeft - 1] = sizeByte;
              }
            } else {
              for (int sizeOffset = (IntegerSerializer.INT_SIZE - recordSizePart) << 3;
                  sizeOffset < IntegerSerializer.INT_SIZE & spaceLeft > 0;
                  sizeOffset += 8, spaceLeft--, written++) {
                final byte sizeByte = (byte) (0xFF & (recordContent.length >> sizeOffset));
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
      final int halfChunkSize = calculateChunkSize(bytesToWrite / 2);
      pageIndex = freeSpaceMap.findFreePage(halfChunkSize / 2);

      return pageIndex;
    }

    int chunkSize = calculateChunkSize(bytesToWrite);
    pageIndex = freeSpaceMap.findFreePage(chunkSize);

    if (pageIndex < 0 && bytesToWrite > MAX_ENTRY_SIZE / 2) {
      final int halfChunkSize = calculateChunkSize(bytesToWrite / 2);
      if (halfChunkSize > 0) {
        pageIndex = freeSpaceMap.findFreePage(halfChunkSize / 2);
      }
    }

    return pageIndex;
  }

  private static int calculateClusterEntrySize(final int contentSize) {
    // content + record type + content size
    return contentSize + ByteSerializer.BYTE_SIZE + IntegerSerializer.INT_SIZE;
  }

  private static int calculateContentSizeFromClusterEntrySize(final int contentSize) {
    // content + record type + content size
    return contentSize - ByteSerializer.BYTE_SIZE - IntegerSerializer.INT_SIZE;
  }

  private static int calculateChunkSize(final int entrySize) {
    // entry content + first entry flag + next entry pointer
    return entrySize + ByteSerializer.BYTE_SIZE + LongSerializer.LONG_SIZE;
  }

  @Override
  public @Nonnull RawBuffer readRecord(final long clusterPosition, final boolean prefetchRecords)
      throws IOException {
    return readRecord(clusterPosition);
  }

  @Nonnull
  private RawBuffer readRecord(final long clusterPosition) throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final AtomicOperation atomicOperation = atomicOperationsManager.getCurrentOperation();

        final ClusterPositionMapBucket.PositionEntry positionEntry =
            clusterPositionMap.get(clusterPosition, atomicOperation);
        if (positionEntry == null) {
          throw new RecordNotFoundException(new RecordId(id, clusterPosition));
        }
        return internalReadRecord(
            clusterPosition,
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
      final long clusterPosition,
      long pageIndex,
      int recordPosition,
      final AtomicOperation atomicOperation)
      throws IOException {

    int recordVersion = 0;

    final List<byte[]> recordChunks = new ArrayList<>(2);
    int contentSize = 0;

    long nextPagePointer;
    boolean firstEntry = true;
    do {
      try (final CacheEntry cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex)) {
        final ClusterPage localPage = new ClusterPage(cacheEntry);
        if (firstEntry) {
          recordVersion = localPage.getRecordVersion(recordPosition);
        }

        if (localPage.isDeleted(recordPosition)) {
          if (recordChunks.isEmpty()) {
            throw new RecordNotFoundException(new RecordId(id, clusterPosition));
          } else {
            throw new PaginatedClusterException(
                "Content of record " + new RecordId(id, clusterPosition) + " was broken", this);
          }
        }

        final byte[] content =
            localPage.getRecordBinaryValue(
                recordPosition, 0, localPage.getRecordSize(recordPosition));
        assert content != null;

        if (firstEntry
            && content[content.length - LongSerializer.LONG_SIZE - ByteSerializer.BYTE_SIZE]
            == 0) {
          throw new RecordNotFoundException(new RecordId(id, clusterPosition));
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

    byte[] fullContent = convertRecordChunksToSingleChunk(recordChunks, contentSize);

    if (fullContent == null) {
      throw new RecordNotFoundException(new RecordId(id, clusterPosition));
    }

    int fullContentPosition = 0;

    final byte recordType = fullContent[fullContentPosition];
    fullContentPosition++;

    final int readContentSize =
        IntegerSerializer.INSTANCE.deserializeNative(fullContent, fullContentPosition);
    fullContentPosition += IntegerSerializer.INT_SIZE;

    byte[] recordContent =
        Arrays.copyOfRange(fullContent, fullContentPosition, fullContentPosition + readContentSize);

    return new RawBuffer(recordContent, recordVersion, recordType);
  }

  @Override
  public boolean deleteRecord(AtomicOperation atomicOperation, final long clusterPosition) {
    return calculateInsideComponentOperation(
        atomicOperation,
        operation -> {
          acquireExclusiveLock();
          try {
            final ClusterPositionMapBucket.PositionEntry positionEntry =
                clusterPositionMap.get(clusterPosition, atomicOperation);
            if (positionEntry == null) {
              return false;
            }

            long pageIndex = positionEntry.getPageIndex();
            int recordPosition = positionEntry.getRecordPosition();

            long nextPagePointer;
            int removedContentSize = 0;
            int removeRecordSize;

            do {
              boolean cacheEntryReleased = false;
              final int maxRecordSize;
              CacheEntry cacheEntry = loadPageForWrite(atomicOperation, fileId, pageIndex, true);
              try {
                ClusterPage localPage = new ClusterPage(cacheEntry);

                if (localPage.isDeleted(recordPosition)) {
                  if (removedContentSize == 0) {
                    cacheEntryReleased = true;
                    cacheEntry.close();
                    return false;
                  } else {
                    throw new PaginatedClusterException(
                        "Content of record " + new RecordId(id, clusterPosition) + " was broken",
                        this);
                  }
                } else if (removedContentSize == 0) {
                  cacheEntry.close();

                  cacheEntry = loadPageForWrite(atomicOperation, fileId, pageIndex, true);

                  localPage = new ClusterPage(cacheEntry);
                }

                final int initialFreeSpace = localPage.getFreeSpace();
                final byte[] content = localPage.deleteRecord(recordPosition, true);
                atomicOperation.addDeletedRecordPosition(
                    id, cacheEntry.getPageIndex(), recordPosition);
                assert content != null;
                removeRecordSize = calculateContentSizeFromClusterEntrySize(content.length);

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

            updateClusterState(-1, -removeRecordSize, atomicOperation);

            clusterPositionMap.remove(clusterPosition, atomicOperation);
            return true;
          } finally {
            releaseExclusiveLock();
          }
        });
  }

  @Override
  public void updateRecord(
      final long clusterPosition,
      final byte[] content,
      final int recordVersion,
      final byte recordType,
      final AtomicOperation atomicOperation) {
    executeInsideComponentOperation(
        atomicOperation,
        operation -> {
          acquireExclusiveLock();
          try {
            final ClusterPositionMapBucket.PositionEntry positionEntry =
                clusterPositionMap.get(clusterPosition, atomicOperation);
            if (positionEntry == null) {
              return;
            }

            int oldContentSize = 0;
            int nextPageIndex = (int) positionEntry.getPageIndex();
            int nextRecordPosition = positionEntry.getRecordPosition();

            long nextPagePointer = createPagePointer(nextPageIndex, nextRecordPosition);

            ArrayList<ClusterPage> storedPages = new ArrayList<>();

            while (nextPagePointer >= 0) {
              final CacheEntry cacheEntry =
                  loadPageForWrite(atomicOperation, fileId, nextPageIndex, true);
              final ClusterPage page = new ClusterPage(cacheEntry);
              final byte[] deletedRecord = page.deleteRecord(nextRecordPosition, true);
              assert deletedRecord != null;
              oldContentSize = calculateContentSizeFromClusterEntrySize(deletedRecord.length);
              nextPagePointer =
                  LongSerializer.INSTANCE.deserializeNative(
                      deletedRecord, deletedRecord.length - LongSerializer.LONG_SIZE);

              nextPageIndex = (int) getPageIndex(nextPagePointer);
              nextRecordPosition = getRecordPosition(nextPagePointer);

              storedPages.add(page);
            }

            final ListIterator<ClusterPage> reverseIterator =
                storedPages.listIterator(storedPages.size());
            int[] result =
                serializeRecord(
                    content,
                    calculateClusterEntrySize(content.length),
                    recordType,
                    recordVersion,
                    -1,
                    atomicOperation,
                    entrySize -> {
                      if (reverseIterator.hasPrevious()) {
                        return reverseIterator.previous();
                      }
                      return null;
                    },
                    page -> {
                      final CacheEntry cacheEntry = page.getCacheEntry();
                      try {
                        cacheEntry.close();
                      } catch (final IOException e) {
                        throw BaseException.wrapException(
                            new PaginatedClusterException(
                                "Can not update record with rid "
                                    + new RecordId(id, clusterPosition),
                                this),
                            e);
                      }
                    });

            nextPageIndex = result[0];
            nextRecordPosition = result[1];

            while (reverseIterator.hasPrevious()) {
              final ClusterPage page = reverseIterator.previous();
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
                        final CacheEntry cacheEntry = page.getCacheEntry();
                        try {
                          cacheEntry.close();
                        } catch (final IOException e) {
                          throw BaseException.wrapException(
                              new PaginatedClusterException(
                                  "Can not update record with rid "
                                      + new RecordId(id, clusterPosition),
                                  this),
                              e);
                        }
                      });

              nextPageIndex = result[0];
              nextRecordPosition = result[1];
            }

            assert result[2] == 0;
            updateClusterState(0, content.length - oldContentSize, atomicOperation);

            if (nextPageIndex != positionEntry.getPageIndex()
                || nextRecordPosition != positionEntry.getRecordPosition()) {
              clusterPositionMap.update(
                  clusterPosition,
                  new ClusterPositionMapBucket.PositionEntry(nextPageIndex, nextRecordPosition),
                  atomicOperation);
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

  @Override
  public PhysicalPosition getPhysicalPosition(final PhysicalPosition position)
      throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final AtomicOperation atomicOperation = atomicOperationsManager.getCurrentOperation();
        final long clusterPosition = position.clusterPosition;
        final ClusterPositionMapBucket.PositionEntry positionEntry =
            clusterPositionMap.get(clusterPosition, atomicOperation);

        if (positionEntry == null) {
          return null;
        }

        final long pageIndex = positionEntry.getPageIndex();
        final int recordPosition = positionEntry.getRecordPosition();

        try (final CacheEntry cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex)) {
          final ClusterPage localPage = new ClusterPage(cacheEntry);
          if (localPage.isDeleted(recordPosition)) {
            return null;
          }

          if (localPage.getRecordByteValue(
              recordPosition, -LongSerializer.LONG_SIZE - ByteSerializer.BYTE_SIZE)
              == 0) {
            return null;
          }

          final PhysicalPosition physicalPosition = new PhysicalPosition();
          physicalPosition.recordSize = -1;

          physicalPosition.recordType = localPage.getRecordByteValue(recordPosition, 0);
          physicalPosition.recordVersion = localPage.getRecordVersion(recordPosition);
          physicalPosition.clusterPosition = position.clusterPosition;

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
  public boolean exists(long clusterPosition) throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final AtomicOperation atomicOperation = atomicOperationsManager.getCurrentOperation();
        final ClusterPositionMapBucket.PositionEntry positionEntry =
            clusterPositionMap.get(clusterPosition, atomicOperation);

        if (positionEntry == null) {
          return false;
        }

        final long pageIndex = positionEntry.getPageIndex();
        final int recordPosition = positionEntry.getRecordPosition();

        try (final CacheEntry cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex)) {
          final ClusterPage localPage = new ClusterPage(cacheEntry);
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
        final AtomicOperation atomicOperation = atomicOperationsManager.getCurrentOperation();
        try (final CacheEntry pinnedStateEntry =
            loadPageForRead(atomicOperation, fileId, STATE_ENTRY_INDEX)) {
          return new PaginatedClusterStateV2(pinnedStateEntry).getSize();
        }
      } finally {
        releaseSharedLock();
      }
    } catch (final IOException ioe) {
      throw BaseException.wrapException(
          new PaginatedClusterException(
              "Error during retrieval of size of '" + getName() + "' cluster", this),
          ioe);
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
        final AtomicOperation atomicOperation = atomicOperationsManager.getCurrentOperation();
        return clusterPositionMap.getFirstPosition(atomicOperation);
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
        final AtomicOperation atomicOperation = atomicOperationsManager.getCurrentOperation();
        return clusterPositionMap.getLastPosition(atomicOperation);
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public long getNextPosition() throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final AtomicOperation atomicOperation = atomicOperationsManager.getCurrentOperation();
        return clusterPositionMap.getNextPosition(atomicOperation);
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
        clusterPositionMap.flush();
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
        final AtomicOperation atomicOperation = atomicOperationsManager.getCurrentOperation();

        try (final CacheEntry pinnedStateEntry =
            loadPageForRead(atomicOperation, fileId, STATE_ENTRY_INDEX)) {
          return new PaginatedClusterStateV2(pinnedStateEntry).getRecordsSize();
        }
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public PhysicalPosition[] higherPositions(final PhysicalPosition position) throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final AtomicOperation atomicOperation = atomicOperationsManager.getCurrentOperation();
        final long[] clusterPositions =
            clusterPositionMap.higherPositions(position.clusterPosition, atomicOperation);
        return convertToPhysicalPositions(clusterPositions);
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public PhysicalPosition[] ceilingPositions(final PhysicalPosition position) throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final AtomicOperation atomicOperation = atomicOperationsManager.getCurrentOperation();
        final long[] clusterPositions =
            clusterPositionMap.ceilingPositions(position.clusterPosition, atomicOperation);
        return convertToPhysicalPositions(clusterPositions);
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public PhysicalPosition[] lowerPositions(final PhysicalPosition position) throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final AtomicOperation atomicOperation = atomicOperationsManager.getCurrentOperation();
        final long[] clusterPositions =
            clusterPositionMap.lowerPositions(position.clusterPosition, atomicOperation);
        return convertToPhysicalPositions(clusterPositions);
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public PhysicalPosition[] floorPositions(final PhysicalPosition position) throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final AtomicOperation atomicOperation = atomicOperationsManager.getCurrentOperation();
        final long[] clusterPositions =
            clusterPositionMap.floorPositions(position.clusterPosition, atomicOperation);
        return convertToPhysicalPositions(clusterPositions);
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

  private void updateClusterState(
      final long sizeDiff, long recordSizeDiff, final AtomicOperation atomicOperation)
      throws IOException {
    try (final CacheEntry pinnedStateEntry =
        loadPageForWrite(atomicOperation, fileId, STATE_ENTRY_INDEX, true)) {
      final PaginatedClusterStateV2 paginatedClusterState =
          new PaginatedClusterStateV2(pinnedStateEntry);
      if (sizeDiff != 0) {
        paginatedClusterState.setSize((int) (paginatedClusterState.getSize() + sizeDiff));
      }
      if (recordSizeDiff != 0) {
        paginatedClusterState.setRecordsSize(
            (int) (paginatedClusterState.getRecordsSize() + recordSizeDiff));
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
  public void setClusterName(final String newName) {
    acquireExclusiveLock();
    try {
      writeCache.renameFile(fileId, newName + getExtension());
      clusterPositionMap.rename(newName);
      freeSpaceMap.rename(newName);

      setName(newName);
    } catch (IOException e) {
      throw BaseException.wrapException(
          new PaginatedClusterException("Error during renaming of cluster", this), e);
    } finally {
      releaseExclusiveLock();
    }
  }

  private static PhysicalPosition createPhysicalPosition(
      final byte recordType, final long clusterPosition, final int version) {
    final PhysicalPosition physicalPosition = new PhysicalPosition();
    physicalPosition.recordType = recordType;
    physicalPosition.recordSize = -1;
    physicalPosition.clusterPosition = clusterPosition;
    physicalPosition.recordVersion = version;
    return physicalPosition;
  }

  private static byte[] convertRecordChunksToSingleChunk(
      final List<byte[]> recordChunks, final int contentSize) {
    final byte[] fullContent;
    if (recordChunks.size() == 1) {
      fullContent = recordChunks.get(0);
    } else {
      fullContent = new byte[contentSize + LongSerializer.LONG_SIZE + ByteSerializer.BYTE_SIZE];
      int fullContentPosition = 0;
      for (final byte[] recordChuck : recordChunks) {
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
    try (final CacheEntry stateCacheEntry =
        loadPageForWrite(atomicOperation, fileId, STATE_ENTRY_INDEX, true)) {
      final PaginatedClusterStateV2 clusterState = new PaginatedClusterStateV2(stateCacheEntry);
      final int fileSize = clusterState.getFileSize();
      final long filledUpTo = getFilledUpTo(atomicOperation, fileId);

      if (fileSize == filledUpTo - 1) {
        cacheEntry = addPage(atomicOperation, fileId);
      } else {
        assert fileSize < filledUpTo - 1;

        cacheEntry = loadPageForWrite(atomicOperation, fileId, fileSize + 1, false);
      }

      clusterState.setFileSize(fileSize + 1);
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
      final PaginatedClusterStateV2 paginatedClusterState =
          new PaginatedClusterStateV2(stateEntry);
      paginatedClusterState.setSize(0);
      paginatedClusterState.setRecordsSize(0);
      paginatedClusterState.setFileSize(0);
    } finally {
      stateEntry.close();
    }
  }

  private static PhysicalPosition[] convertToPhysicalPositions(final long[] clusterPositions) {
    final PhysicalPosition[] positions = new PhysicalPosition[clusterPositions.length];
    for (int i = 0; i < positions.length; i++) {
      final PhysicalPosition physicalPosition = new PhysicalPosition();
      physicalPosition.clusterPosition = clusterPositions[i];
      positions[i] = physicalPosition;
    }
    return positions;
  }

  public RECORD_STATUS getRecordStatus(final long clusterPosition) throws IOException {
    final AtomicOperation atomicOperation = atomicOperationsManager.getCurrentOperation();
    acquireSharedLock();
    try {
      final byte status = clusterPositionMap.getStatus(clusterPosition, atomicOperation);

      return switch (status) {
        case ClusterPositionMapBucket.NOT_EXISTENT -> RECORD_STATUS.NOT_EXISTENT;
        case ClusterPositionMapBucket.ALLOCATED -> RECORD_STATUS.ALLOCATED;
        case ClusterPositionMapBucket.FILLED -> RECORD_STATUS.PRESENT;
        case ClusterPositionMapBucket.REMOVED -> RECORD_STATUS.REMOVED;
        default -> throw new IllegalStateException(
            "Invalid record status : " + status + " for cluster " + getName());
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
    return "plocal cluster: " + getName();
  }

  @Override
  public ClusterBrowsePage nextPage(final long lastPosition) throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final AtomicOperation atomicOperation = atomicOperationsManager.getCurrentOperation();

        final ClusterPositionEntry[] nextPositions =
            clusterPositionMap.higherPositionsEntries(lastPosition, atomicOperation);
        if (nextPositions.length > 0) {
          final long newLastPosition = nextPositions[nextPositions.length - 1].getPosition();
          final List<ClusterBrowseEntry> nexv = new ArrayList<>(nextPositions.length);
          for (final ClusterPositionEntry pos : nextPositions) {
            final RawBuffer buff =
                internalReadRecord(
                    pos.getPosition(), pos.getPage(), pos.getOffset(), atomicOperation);
            nexv.add(new ClusterBrowseEntry(pos.getPosition(), buff));
          }
          return new ClusterBrowsePage(nexv, newLastPosition);
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
