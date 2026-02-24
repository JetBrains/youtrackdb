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
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
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
import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPositionMapBucket.PositionEntry;
import com.jetbrains.youtrackdb.internal.core.storage.collection.PaginatedCollection;
import com.jetbrains.youtrackdb.internal.core.storage.collection.SnapshotKey;
import com.jetbrains.youtrackdb.internal.core.storage.collection.VisibilityKey;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.CollectionBrowseEntry;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.CollectionBrowsePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsTable.AtomicOperationsSnapshot;
import it.unimi.dsi.fastutil.ints.Int2ObjectFunction;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Version 2 implementation of a paginated record collection for the YouTrackDB storage engine.
 *
 * <p>A "collection" is the physical storage unit that holds records belonging to a database class
 * (vertex type, edge type, or document type). Each collection manages its own data pages, position
 * map, and free-space index. Records are identified by a <em>collection position</em> (a
 * monotonically increasing {@code long}), which, combined with the collection {@link #id}, forms
 * the full Record ID (RID) of the form {@code #collectionId:collectionPosition}.
 *
 * <h2>Architecture Overview</h2>
 *
 * <p>A {@code PaginatedCollectionV2} is composed of three on-disk components, each stored as a
 * separate file managed through the disk-cache layer ({@link
 * com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache ReadCache} /
 * {@link com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache WriteCache}):
 *
 * <pre>{@code
 *  PaginatedCollectionV2
 *  |
 *  |-- Data file  (.pcl)           -- CollectionPage pages holding serialized record chunks
 *  |     page 0: PaginatedCollectionStateV2 (collection metadata / file-size counter)
 *  |     page 1..N: CollectionPage instances with record entries
 *  |
 *  |-- Position map  (.cpm)        -- CollectionPositionMapV2
 *  |     page 0: MapEntryPoint (stores the count of bucket pages)
 *  |     page 1..M: CollectionPositionMapBucket pages
 *  |       Each bucket holds up to MAX_ENTRIES position entries:
 *  |         [status: 1B][pageIndex: 8B][recordPosition: 4B][recordVersion: 8B]
 *  |
 *  |-- Free-space map  (.fsm)      -- FreeSpaceMap (two-level segment tree)
 *        page 0: first-level summary (max free space per second-level page)
 *        page 1..K: second-level leaves (max free space per data page)
 * }</pre>
 *
 * <h2>Record Storage Layout</h2>
 *
 * <p>A record is serialized into an "entry" consisting of a metadata header followed by the raw
 * content bytes. The entry is then split into one or more <em>chunks</em> that are stored across
 * {@link CollectionPage} pages. Chunks form a singly-linked list via embedded page pointers,
 * enabling records larger than a single page to be stored.
 *
 * <pre>{@code
 *  Entry layout (logical):
 *  +------------+-------------+---------------------+-----------------------+
 *  | recordType | contentSize | collectionPosition  |    actual content     |
 *  |   (1 B)    |   (4 B)    |       (8 B)          |      (N bytes)        |
 *  +------------+-------------+---------------------+-----------------------+
 *  |<---------- METADATA_SIZE = 13 B -------------->|
 *
 *  Chunk layout on a CollectionPage (per page slot):
 *  +------------------+------------------+-----------------+
 *  |   entry data     | firstRecordFlag  | nextPagePointer |
 *  |   (variable)     |     (1 B)        |     (8 B)       |
 *  +------------------+------------------+-----------------+
 *
 *  Multi-chunk record (written tail-first, read head-first):
 *
 *  CollectionPage A (entry point)       CollectionPage B (tail)
 *  +---------------------------------+  +---------------------------+
 *  | metadata + content[0..P] | 1 |ptr--->| content[P+1..N] | 0 | -1 |
 *  +---------------------------------+  +---------------------------+
 *         ^                                    (nextPagePointer = -1
 *         |                                     means end of chain)
 *    Position map entry
 *    points here
 * }</pre>
 *
 * <p>The serialization writes chunks in <strong>reverse order</strong> (tail chunk first, head
 * chunk last). This means the entry-point chunk -- the one the position map points to -- is
 * always the last chunk written, which simplifies crash recovery: if the write is interrupted
 * before the entry point is written, the position map still points to the old (valid) data.
 *
 * <h2>MVCC and Snapshot Isolation</h2>
 *
 * <p>Records use multi-version concurrency control (MVCC) for snapshot isolation:
 * <ul>
 *   <li>Each record version is stamped with the writing transaction's {@code commitTs}.</li>
 *   <li>Before overwriting or deleting a record, the previous version's position entry is saved
 *       to a <em>snapshot index</em> (keyed by {@link SnapshotKey}) so concurrent readers can
 *       still access the old version.</li>
 *   <li>A <em>visibility index</em> (keyed by {@link VisibilityKey}) tracks when snapshot entries
 *       can be garbage-collected once all older transactions complete.</li>
 *   <li>Reads check version visibility via {@link
 *       com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsTable.AtomicOperationsSnapshot
 *       AtomicOperationsSnapshot} and fall back to historical entries when the current version
 *       is not yet visible.</li>
 * </ul>
 *
 * <h2>Concurrency</h2>
 *
 * <p>Read operations acquire a shared lock; write operations acquire an exclusive lock. All
 * structural mutations run inside an {@link AtomicOperation} for crash-safe atomicity via
 * write-ahead logging (WAL).
 *
 * @see CollectionPositionMapV2
 * @see FreeSpaceMap
 * @see CollectionPage
 * @see PaginatedCollectionStateV2
 */
public final class PaginatedCollectionV2 extends PaginatedCollection {

  /**
   * Maximum entry data that can fit in a single chunk on a {@link CollectionPage}. Derived from
   * {@link CollectionPage#MAX_RECORD_SIZE} minus the per-chunk overhead of the first-record flag
   * (1 byte) and the next-page pointer (8 bytes).
   */
  private static final int MAX_ENTRY_SIZE =
      CollectionPage.MAX_RECORD_SIZE - ByteSerializer.BYTE_SIZE - LongSerializer.LONG_SIZE;

  /**
   * Minimum chunk size: just the first-record flag (1 byte) + next-page pointer (8 bytes),
   * with no entry data. Used as a lower bound when requesting a page from the free-space map.
   */
  private static final int MIN_ENTRY_SIZE = ByteSerializer.BYTE_SIZE + LongSerializer.LONG_SIZE;

  /** Page index of the collection state page within the data file (.pcl). Always page 0. */
  private static final int STATE_ENTRY_INDEX = 0;

  /** On-disk binary format version for this collection implementation. */
  private static final int BINARY_VERSION = 3;

  /**
   * Size of the per-record metadata header that precedes the actual record content.
   *
   * <pre>{@code
   * Layout: [recordType: 1B][contentSize: 4B][collectionPosition: 8B] = 13 bytes
   * }</pre>
   */
  private static final int METADATA_SIZE =
      ByteSerializer.BYTE_SIZE + IntegerSerializer.INT_SIZE + LongSerializer.LONG_SIZE;

  /**
   * Bit offset used to pack a page index and a record position into a single {@code long}
   * page pointer. The page index occupies bits [16..63] and the record position occupies
   * bits [0..15].
   *
   * @see #createPagePointer(long, int)
   * @see #getPageIndex(long)
   * @see #getRecordPosition(long)
   */
  private static final int PAGE_INDEX_OFFSET = 16;

  /** Bitmask to extract the 16-bit record position from a packed page pointer. */
  private static final int RECORD_POSITION_MASK = 0xFFFF;

  /** Whether this collection stores internal system data (e.g., schema metadata). */
  private final boolean systemCollection;

  /**
   * Maps logical collection positions to physical locations (page index + offset) in the data
   * file. Also tracks record status (ALLOCATED, FILLED, REMOVED) and version.
   */
  private final CollectionPositionMapV2 collectionPositionMap;

  /**
   * Two-level segment tree that tracks the maximum free space available on each data page,
   * enabling O(log N) lookup for a page with enough room for a new record chunk.
   */
  private final FreeSpaceMap freeSpaceMap;

  /** Human-readable storage name, used in exception messages and logging. */
  private final String storageName;

  /** Per-collection rate meters for create/read/update/delete/conflict operations. */
  private StorageCollection.Meters meters = Meters.NOOP;

  /**
   * Numeric identifier for this collection, assigned during {@link #configure}. Forms the
   * "collection ID" part of a Record ID ({@code #id:collectionPosition}).
   */
  private volatile int id;
  private volatile long approximateRecordsCount;

  /** Internal file ID assigned by the disk cache when the data file (.pcl) is opened/created. */
  private long fileId;

  /** Strategy for resolving concurrent-modification conflicts on records in this collection. */
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
  public boolean exists(AtomicOperation atomicOperation) {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        return isFileExists(atomicOperation, getFullName());
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
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
          config.id(),
          config.name(),
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
            initCollectionState(atomicOperation);
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

            // Load the persistent approximate records count into the volatile field.
            try (final var stateCacheEntry =
                loadPageForRead(atomicOperation, fileId, STATE_ENTRY_INDEX)) {
              final var state = new PaginatedCollectionStateV2(stateCacheEntry);
              approximateRecordsCount = state.getApproximateRecordsCount();
            }

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

  /// Creates a new record in the collection. The record is serialized into one or more page chunks
  /// with the collection position embedded in the metadata header, then the position map is updated
  /// to point to the entry-point chunk.
  ///
  /// The collection position is determined before serialization so it can be embedded in the
  /// record's metadata header. If {@code allocatedPosition} is provided (pre-allocated via
  /// {@link #allocatePosition}), its position is reused; otherwise a new position is allocated via
  /// {@link CollectionPositionMapV2#allocate}. In both cases, after serialization the position map
  /// is updated with the final page location via {@link CollectionPositionMapV2#update}.
  @Override
  public PhysicalPosition createRecord(
      @Nonnull final byte[] content,
      final byte recordType,
      @Nullable final PhysicalPosition allocatedPosition,
      @Nonnull final AtomicOperation atomicOperation) {
    return calculateInsideComponentOperation(
        atomicOperation,
        operation -> {
          acquireExclusiveLock();
          try {
            return doCreateRecord(content, recordType, allocatedPosition,
                atomicOperation);
          } finally {
            releaseExclusiveLock();
          }
        });
  }

  /// Creates a new record in the collection with the given content.
  ///
  /// This method handles the actual record creation logic, including serialization, page
  /// allocation, and updating the collection position map.
  ///
  /// @param content           the binary content of the record
  /// @param recordType        the type identifier for the record
  /// @param allocatedPosition if not null, the pre-allocated position to use; otherwise a new
  ///                          position will be allocated
  /// @param atomicOperation   the atomic operation context
  /// @return the physical position of the created record
  /// @throws IOException if an I/O error occurs during record creation
  private @Nonnull PhysicalPosition doCreateRecord(@Nonnull byte[] content,
      byte recordType, @Nullable PhysicalPosition allocatedPosition,
      @Nonnull AtomicOperation atomicOperation)
      throws IOException {
    var newRecordVersion = atomicOperation.getCommitTs();

    final long collectionPosition;
    if (allocatedPosition != null) {
      collectionPosition = allocatedPosition.collectionPosition;
    } else {
      collectionPosition = collectionPositionMap.allocate(atomicOperation);
    }

    final var result =
        serializeRecord(
            content,
            calculateCollectionEntrySize(content.length),
            recordType,
            newRecordVersion,
            collectionPosition,
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

    collectionPositionMap.update(
        collectionPosition,
        new PositionEntry(nextPageIndex, nextPageOffset, newRecordVersion),
        atomicOperation);

    incrementApproximateRecordsCount(atomicOperation);

    return createPhysicalPosition(recordType, collectionPosition, newRecordVersion);
  }

  /**
   * Finds or allocates a data page with at least {@code entrySize} bytes of free space.
   *
   * <p>First consults the {@link FreeSpaceMap} for an existing page with enough room. If no
   * suitable page is found, a brand-new page is allocated from the data file and initialized.
   *
   * @param atomicOperation the current atomic operation context
   * @param entrySize       minimum free space required on the page (bytes)
   * @return a {@link CollectionPage} with enough room for the entry, never {@code null}
   */
  private CollectionPage findNewPageToWrite(
      final AtomicOperation atomicOperation, final int entrySize) {
    final CollectionPage page;
    try {
      // Ask the FSM for an existing page that can accommodate the chunk.
      final var nextPageToWrite = findNextFreePageIndexToWrite(entrySize, atomicOperation);

      final CacheEntry cacheEntry;
      boolean isNew;
      if (nextPageToWrite >= 0) {
        // Found an existing page with enough free space.
        cacheEntry = loadPageForWrite(atomicOperation, fileId, nextPageToWrite, true);
        isNew = false;
      } else {
        // No existing page fits -- grow the data file by one page.
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

  /// Serializes a record entry into one or more page chunks, writing them backward from the tail of
  /// the entry toward the head. Each chunk is stored on a page obtained from {@code pageSupplier}.
  /// The chunks form a singly-linked list via next-page pointers: the first chunk written (tail)
  /// has {@code nextPagePointer = -1}, and each subsequent chunk points to the previously written
  /// one. The last chunk written becomes the entry point (head of the chain, first to be read).
  ///
  /// <p>The entry layout is:
  /// {@code [recordType: 1B][contentSize: 4B][collectionPosition: 8B][actualContent: NB]}. Each
  /// chunk additionally carries a first-record flag (1B) and a next-page pointer (8B).
  ///
  /// @param content            the raw record bytes
  /// @param len                total entry size (metadata + content), or remaining bytes when
  ///                           continuing a partially written entry
  /// @param collectionPosition logical position embedded in the metadata header
  /// @param nextRecordPointer  initial next-page pointer ({@code -1} for a new entry, or a page
  ///                           pointer when continuing from a previous serialization)
  /// @param pageSupplier       provides a non-null {@link CollectionPage} with at least the
  ///                           requested free space; the current implementation uses
  ///                           {@code findNewPageToWrite} which always succeeds by allocating
  ///                           a new page if needed
  /// @param pagePostProcessor  called after each chunk is written (typically closes the page)
  /// @return {@code int[3]}: {@code [pageIndex, pageOffset, remainingBytes]} where
  /// {@code remainingBytes} is always 0
  private int[] serializeRecord(
      final byte[] content,
      final int len,
      final byte recordType,
      final long recordVersion,
      final long collectionPosition,
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
      // All current callers use findNewPageToWrite which always returns a page (either an
      // existing free page or a newly allocated one).
      final var page = pageSupplier.apply(Math.max(bytesToWrite, MIN_ENTRY_SIZE + 1));

      int maxRecordSize;
      try {
        // findNewPageToWrite guarantees that the page has adequate free space
        // (either via FSM lookup or by allocating a new page with full capacity).
        var availableInPage = page.getMaxRecordSize();
        final var pageChunkSize = Math.min(availableInPage, chunkSize);

        final var pair =
            serializeEntryChunk(
                content, pageChunkSize, bytesToWrite, nextRecordPointers, recordType,
                collectionPosition);
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
        maxRecordSize = page.getMaxRecordSize();
      } finally {
        pagePostProcessor.accept(page);
      }

      freeSpaceMap.updatePageFreeSpace(atomicOperation, nextPageIndex, maxRecordSize);
    }

    return new int[]{nextPageIndex, nextPageOffset, 0};
  }

  /// Builds a single chunk of a serialized record entry. The chunk layout is:
  /// {@code [data] [firstRecordFlag: 1B] [nextPagePointer: 8B]}, where {@code data} contains a
  /// portion of the entry bytes (content and/or metadata) written from the end backward.
  ///
  /// <p>The entry data is written in reverse order across chunks: the last bytes of actual
  /// content are written first (in the tail chunk), and the metadata header is written last (in the
  /// entry-point chunk). This reverse order, combined with the forward read-order concatenation of
  /// chunk data, reassembles the original entry layout:
  /// {@code [recordType][contentSize][collectionPosition][actualContent]}.
  ///
  /// <p><b>Metadata split prevention:</b> if the remaining metadata does not fully fit in the
  /// available space after content, the chunk is shrunk to hold only the content portion. This
  /// guarantees that metadata is never split across two chunks, which would corrupt the reassembled
  /// byte order during reading (since chunks are written backward but read forward).
  ///
  /// <p>The first-record flag is set to 1 only on the entry-point chunk (the chunk that
  /// completes the entire entry, i.e., {@code written == bytesToWrite}).
  ///
  /// @param recordContent      the raw record bytes (actual content only, no metadata)
  /// @param chunkSize          maximum size of the chunk byte array to produce
  /// @param bytesToWrite       total remaining entry bytes (metadata + content) to be written
  /// @param nextPagePointer    pointer to the next chunk in the chain ({@code -1} for the tail)
  /// @param recordType         record type byte stored in the metadata header
  /// @param collectionPosition logical position stored in the metadata header
  /// @return a pair of (chunk byte array, number of entry bytes written in this chunk)
  private static RawPairObjectInteger<byte[]> serializeEntryChunk(
      final byte[] recordContent,
      final int chunkSize,
      final int bytesToWrite,
      final long nextPagePointer,
      final byte recordType,
      final long collectionPosition) {
    var chunk = new byte[chunkSize];
    var offset = chunkSize - LongSerializer.LONG_SIZE;

    LongSerializer.serializeNative(nextPagePointer, chunk, offset);

    var written = 0;
    // Compute how many actual content bytes remain (total entry bytes minus metadata header).
    final var contentSize = bytesToWrite - METADATA_SIZE;
    // Reserve one byte for the first-record flag just before the next-page pointer.
    var firstRecordOffset = --offset;

    // Write actual content bytes from the end of recordContent backward. This places the
    // tail of the content closest to the first-record flag, and the head of the content
    // (or metadata) at the lowest addresses.
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

    // Space remaining for metadata, after content + first-record flag + next-page pointer.
    var spaceLeft = chunkSize - written - LongSerializer.LONG_SIZE - ByteSerializer.BYTE_SIZE;

    if (spaceLeft > 0) {
      final var metadataToWrite = bytesToWrite - written;
      assert metadataToWrite <= METADATA_SIZE;

      if (metadataToWrite <= spaceLeft) {
        // All remaining metadata fits — build the full metadata array and copy the
        // relevant suffix (for intermediate chunks that write the tail of metadata)
        // or the entire array (for the entry-point chunk that writes all metadata).
        final var metadata = new byte[METADATA_SIZE];
        metadata[0] = recordType;
        IntegerSerializer.serializeNative(
            recordContent.length, metadata, ByteSerializer.BYTE_SIZE);
        LongSerializer.serializeNative(
            collectionPosition, metadata,
            ByteSerializer.BYTE_SIZE + IntegerSerializer.INT_SIZE);

        final var metadataOffset = METADATA_SIZE - metadataToWrite;
        System.arraycopy(
            metadata, metadataOffset, chunk, spaceLeft - metadataToWrite, metadataToWrite);
        written += metadataToWrite;
      } else {
        // Not enough room for all remaining metadata. To prevent metadata from being
        // split across chunks (which would corrupt the read-order assembly), shrink
        // the chunk to hold only the content written so far. The metadata will be
        // written entirely in a subsequent (larger) chunk.
        final var shrunkSize = written + ByteSerializer.BYTE_SIZE + LongSerializer.LONG_SIZE;
        final var shrunkChunk = new byte[shrunkSize];
        System.arraycopy(chunk, offset - written, shrunkChunk, 0, written);
        LongSerializer.serializeNative(
            nextPagePointer, shrunkChunk, shrunkSize - LongSerializer.LONG_SIZE);
        chunk = shrunkChunk;
        firstRecordOffset = written;
      }
    }

    assert written <= bytesToWrite;

    // The entry-point chunk is the last one written and the first one read. Mark it with
    // the first-record flag so the read path can identify a valid record head.
    if (written == bytesToWrite) {
      chunk[firstRecordOffset] = 1;
    }

    return new RawPairObjectInteger<>(chunk, written);
  }

  /**
   * Queries the {@link FreeSpaceMap} for an existing data page that can hold a chunk of the
   * given size.
   *
   * <p>If no page has enough room for the full chunk, and the entry is large (more than half
   * of {@link #MAX_ENTRY_SIZE}), a second attempt is made with half the chunk size. This
   * allows large records to be split across more chunks rather than immediately allocating a
   * new page, improving space utilization.
   *
   * @param bytesToWrite    the entry bytes remaining to write (clamped to {@link #MAX_ENTRY_SIZE})
   * @param atomicOperation the current atomic operation context
   * @return the data-file page index of a suitable page, or {@code -1} if none found
   */
  private int findNextFreePageIndexToWrite(int bytesToWrite, AtomicOperation atomicOperation)
      throws IOException {
    // Clamp to the maximum entry data per chunk -- larger entries will be split.
    if (bytesToWrite > MAX_ENTRY_SIZE) {
      bytesToWrite = MAX_ENTRY_SIZE;
    }

    // After clamping, bytesToWrite <= MAX_ENTRY_SIZE (8095) which is always below
    // MAX_PAGE_SIZE_BYTES - NORMALIZATION_INTERVAL (8160), so the "near-page-size"
    // path that used to exist here is unreachable and was removed.

    var chunkSize = calculateChunkSize(bytesToWrite);
    var pageIndex = freeSpaceMap.findFreePage(chunkSize, atomicOperation);

    // Fallback: for large entries, try to find a page that can hold at least half.
    // This trades off more chunks for better page utilization.
    if (pageIndex < 0 && bytesToWrite > MAX_ENTRY_SIZE / 2) {
      final var halfChunkSize = calculateChunkSize(bytesToWrite / 2);
      if (halfChunkSize > 0) {
        pageIndex = freeSpaceMap.findFreePage(halfChunkSize / 2, atomicOperation);
      }
    }

    return pageIndex;
  }

  /// Computes the total entry size from the raw content size by adding the metadata header:
  /// {@code recordType (1B) + contentSize (4B) + collectionPosition (8B)}.
  private static int calculateCollectionEntrySize(final int contentSize) {
    return contentSize + METADATA_SIZE;
  }

  private static int calculateChunkSize(final int entrySize) {
    // entry content + first entry flag + next entry pointer
    return entrySize + ByteSerializer.BYTE_SIZE + LongSerializer.LONG_SIZE;
  }

  @Override
  @Nonnull
  public RawBuffer readRecord(final long collectionPosition, AtomicOperation atomicOperation)
      throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        return doReadRecord(collectionPosition, atomicOperation);
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  /// Reads a record from the collection at the specified position, with tombstone awareness
  /// for snapshot isolation.
  ///
  /// The method distinguishes three cases based on the position map entry status:
  /// 1. NOT_EXISTENT/ALLOCATED: the entry was never written -> RecordNotFoundException
  /// 2. REMOVED (tombstone): check if the deletion is visible to the current transaction.
  ///    If visible, the record is deleted -> RecordNotFoundException.
  ///    If not visible, read the old version from the history snapshot index.
  /// 3. FILLED: proceed with normal read (existing behavior with version visibility checks).
  ///
  /// @param collectionPosition the logical position of the record in the collection
  /// @param atomicOperation    the atomic operation context
  /// @return the raw buffer containing the record data, version, and type
  /// @throws IOException             if an I/O error occurs during record reading
  /// @throws RecordNotFoundException if the record does not exist at the specified position
  private @Nonnull RawBuffer doReadRecord(long collectionPosition,
      @Nonnull AtomicOperation atomicOperation) throws IOException {
    var entryWithStatus =
        collectionPositionMap.getWithStatus(collectionPosition, atomicOperation);
    var snapshot = atomicOperation.getAtomicOperationsSnapshot();
    var commitTs = atomicOperation.getCommitTsUnsafe();
    var status = entryWithStatus.status();

    // 1. Entry does not exist at all
    if (status == CollectionPositionMapBucket.NOT_EXISTENT
        || status == CollectionPositionMapBucket.ALLOCATED) {
      throw new RecordNotFoundException(storageName, new RecordId(id, collectionPosition));
    }

    // 2. Entry is a tombstone - check deletion visibility
    if (status == CollectionPositionMapBucket.REMOVED) {
      var deletionVersion = entryWithStatus.entry().getRecordVersion();
      if (isRecordVersionVisible(deletionVersion, commitTs, snapshot)) {
        // Deletion visible -> record is deleted from our perspective
        throw new RecordNotFoundException(storageName, new RecordId(id, collectionPosition));
      }
      // Deletion not visible -> read old version from history
      var historicalEntry = findHistoricalPositionEntry(
          collectionPosition, commitTs, snapshot, atomicOperation);
      if (historicalEntry == null) {
        throw new RecordNotFoundException(storageName, new RecordId(id, collectionPosition));
      }
      return readRecordFromHistoricalEntry(collectionPosition, historicalEntry.getPageIndex(),
          historicalEntry.getRecordPosition(), historicalEntry.getRecordVersion(),
          atomicOperation);
    }

    // 3. FILLED - use current entry (existing behavior with version visibility checks)
    var positionEntry = entryWithStatus.entry();
    return internalReadRecord(
        collectionPosition,
        positionEntry.getPageIndex(),
        positionEntry.getRecordPosition(),
        positionEntry.getRecordVersion(),
        atomicOperation);
  }

  @Nonnull
  private RawBuffer internalReadRecord(
      final long collectionPosition,
      long pageIndex,
      int recordPosition,
      long recordVersion,
      final AtomicOperation atomicOperation)
      throws IOException {

    final List<byte[]> recordChunks = new ArrayList<>(2);
    var contentSize = 0;

    long nextPagePointer;
    var firstEntry = true;
    var isRecordVisible = false;
    var snapshot = atomicOperation.getAtomicOperationsSnapshot();
    var operationTs = atomicOperation.getCommitTsUnsafe();

    do {
      try (final var cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex)) {
        final var localPage = new CollectionPage(cacheEntry);
        if (firstEntry) {
          recordVersion = localPage.getRecordVersion(recordPosition);
          isRecordVisible = isRecordVersionVisible(recordVersion, operationTs, snapshot);

          if (!isRecordVisible) {
            break;
          }
        }

        if (localPage.isDeleted(recordPosition)) {
          if (recordChunks.isEmpty()) {
            throw new RecordNotFoundException(storageName,
                new RecordId(id, collectionPosition));
          } else {
            throw new PaginatedCollectionException(storageName,
                "Content of record " + new RecordId(id, collectionPosition)
                    + " was broken", this);
          }
        }

        final var content =
            localPage.getRecordBinaryValue(
                recordPosition, 0, localPage.getRecordSize(recordPosition));
        assert content != null;

        if (firstEntry
            && content[content.length - LongSerializer.LONG_SIZE - ByteSerializer.BYTE_SIZE]
            == 0) {
          throw new RecordNotFoundException(storageName,
              new RecordId(id, collectionPosition));
        }

        recordChunks.add(content);
        nextPagePointer =
            LongSerializer.deserializeNative(
                content, content.length - LongSerializer.LONG_SIZE);
        contentSize += content.length - LongSerializer.LONG_SIZE - ByteSerializer.BYTE_SIZE;

        firstEntry = false;
      }

      pageIndex = getPageIndex(nextPagePointer);
      recordPosition = getRecordPosition(nextPagePointer);
    } while (nextPagePointer >= 0);

    if (!isRecordVisible) {
      var historicalPositionEntry = findHistoricalPositionEntry(collectionPosition, operationTs,
          snapshot, atomicOperation);

      if (historicalPositionEntry != null) {
        return readRecordFromHistoricalEntry(
            collectionPosition,
            historicalPositionEntry.getPageIndex(),
            historicalPositionEntry.getRecordPosition(),
            historicalPositionEntry.getRecordVersion(),
            atomicOperation);
      }

      throw new RecordNotFoundException(storageName, new RecordId(id, collectionPosition));
    }

    return parseRecordContent(collectionPosition, recordVersion, recordChunks, contentSize);
  }

  /// Reads record data from a known-valid historical position without re-checking version
  /// visibility. Used when the caller has already determined which historical version is
  /// appropriate (e.g., via {@link #findHistoricalPositionEntry} or from a REMOVED tombstone).
  /// The page-level version may have been updated in-place by {@link #updateRecordVersion},
  /// so the version from the historical position entry is used directly.
  @Nonnull
  private RawBuffer readRecordFromHistoricalEntry(
      final long collectionPosition,
      long pageIndex,
      int recordPosition,
      final long recordVersion,
      final AtomicOperation atomicOperation)
      throws IOException {

    final List<byte[]> recordChunks = new ArrayList<>(2);
    var contentSize = 0;
    long nextPagePointer;
    var firstEntry = true;

    do {
      try (final var cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex)) {
        final var localPage = new CollectionPage(cacheEntry);

        if (localPage.isDeleted(recordPosition)) {
          if (recordChunks.isEmpty()) {
            throw new RecordNotFoundException(storageName,
                new RecordId(id, collectionPosition));
          } else {
            throw new PaginatedCollectionException(storageName,
                "Content of record " + new RecordId(id, collectionPosition)
                    + " was broken", this);
          }
        }

        final var content =
            localPage.getRecordBinaryValue(
                recordPosition, 0, localPage.getRecordSize(recordPosition));
        assert content != null;

        if (firstEntry
            && content[content.length - LongSerializer.LONG_SIZE - ByteSerializer.BYTE_SIZE]
            == 0) {
          throw new RecordNotFoundException(storageName,
              new RecordId(id, collectionPosition));
        }

        recordChunks.add(content);
        nextPagePointer =
            LongSerializer.deserializeNative(
                content, content.length - LongSerializer.LONG_SIZE);
        contentSize += content.length - LongSerializer.LONG_SIZE - ByteSerializer.BYTE_SIZE;

        firstEntry = false;
      }

      pageIndex = getPageIndex(nextPagePointer);
      recordPosition = getRecordPosition(nextPagePointer);
    } while (nextPagePointer >= 0);

    return parseRecordContent(collectionPosition, recordVersion, recordChunks, contentSize);
  }

  /// Parses the metadata header and extracts the record content from assembled chunks.
  @Nonnull
  private RawBuffer parseRecordContent(
      long collectionPosition,
      long recordVersion,
      List<byte[]> recordChunks,
      int contentSize) {

    // convertRecordChunksToSingleChunk always returns a non-null result because
    // recordChunks is non-empty (callers add at least one chunk before invoking this method).
    var fullContent = convertRecordChunksToSingleChunk(recordChunks, contentSize);

    // Parse the metadata header: [recordType: 1B][contentSize: 4B][collectionPosition: 8B].
    var fullContentPosition = 0;

    final var recordType = fullContent[fullContentPosition];
    fullContentPosition++;

    final var readContentSize =
        IntegerSerializer.deserializeNative(fullContent, fullContentPosition);
    fullContentPosition += IntegerSerializer.INT_SIZE;

    assert readContentSize >= 0
        : "Negative content size in record #" + id + ":" + collectionPosition;
    // Verify that the collection position embedded in the record matches the expected one.
    assert assertPositionConsistency(collectionPosition,
        LongSerializer.deserializeNative(fullContent, fullContentPosition));
    fullContentPosition += LongSerializer.LONG_SIZE;

    // Extract the actual record content that follows the metadata header.
    assert fullContentPosition + readContentSize <= fullContent.length
        : "Content size " + readContentSize + " exceeds assembled data length "
        + fullContent.length + " at offset " + fullContentPosition;
    var recordContent =
        Arrays.copyOfRange(fullContent, fullContentPosition, fullContentPosition + readContentSize);

    return new RawBuffer(recordContent, recordVersion, recordType);
  }

  /// Checks whether a record version is visible to the current operation.
  ///
  /// The self-read shortcut ({@code recordVersion == currentOperationTs}) is essential:
  /// internal atomic operations (managed by {@code AtomicOperationsManager}) freely mix
  /// reads and writes within the same scope — e.g., storage configuration creates records
  /// then reads them back. These records are stamped with {@code commitTs} as their
  /// record version during page writes, and {@code commitTs >= maxActiveOperationTs},
  /// so {@code snapshot.isEntryVisible()} returns false for them.
  /// The self-read check makes these intra-operation reads work correctly.
  private static boolean isRecordVersionVisible(long recordVersion, long currentOperationTs,
      @Nonnull AtomicOperationsSnapshot snapshot) {
    if (recordVersion == currentOperationTs) {
      return true;
    }

    return snapshot.isEntryVisible(recordVersion);
  }

  /// Searches historical snapshot entries for the most recent visible version of a record.
  ///
  /// The {@code currentOperationTs} parameter serves two purposes that cannot be removed:
  ///
  /// 1. **Search bound**: When the current operation has a commitTs ({@code >= 0}), the
  ///    upper bound is {@code currentOperationTs} (which is {@code >= maxActiveOperationTs}
  ///    per the assertion). This ensures entries written by the current operation are
  ///    included in the range scan. Without this, the bound would be
  ///    {@code maxActiveOperationTs} and we would miss versions at or above that timestamp.
  ///
  /// 2. **Self-read shortcut**: The {@code version == currentOperationTs} check in the loop
  ///    makes versions written by the current atomic operation visible to its own reads.
  ///    Internal atomic operations (managed by {@code AtomicOperationsManager}) freely mix
  ///    reads and writes — e.g., storage configuration creates records then reads them
  ///    back. {@code snapshot.isEntryVisible()} returns false for these versions because
  ///    {@code commitTs >= maxActiveOperationTs}.
  @Nullable
  private PositionEntry findHistoricalPositionEntry(long collectionPosition,
      long currentOperationTs, @Nonnull AtomicOperationsSnapshot snapshot,
      @Nonnull AtomicOperation atomicOperation) {

    var maxActiveOperationTs = snapshot.maxActiveOperationTs();

    assert currentOperationTs == -1 || currentOperationTs >= maxActiveOperationTs;

    var searchBound = currentOperationTs >= 0
        ? currentOperationTs : maxActiveOperationTs;
    // Scope to this exact collection position; cover all historical versions
    var lowerKey = new SnapshotKey(id, collectionPosition, Long.MIN_VALUE);
    var upperKey = new SnapshotKey(id, collectionPosition, searchBound);
    var versionedChain =
        atomicOperation.snapshotSubMapDescending(lowerKey, upperKey);
    for (Map.Entry<SnapshotKey, PositionEntry> versionedEntry : versionedChain) {
      var version = versionedEntry.getKey().recordVersion();
      if (version == currentOperationTs || snapshot.isEntryVisible(version)) {
        return versionedEntry.getValue();
      }
    }

    return null;
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

            deleteRecordWithPreservingPreviousVersion(atomicOperation, collectionPosition,
                positionEntry);

            return true;
          } finally {
            releaseExclusiveLock();
          }
        });
  }

  /**
   * Deletes a record while preserving its previous version for MVCC readers. First saves the
   * current position entry to the snapshot index via {@link #keepPreviousRecordVersion}, then
   * marks the position map entry as REMOVED (tombstone) with the current transaction's commitTs
   * as the deletion version.
   */
  private void deleteRecordWithPreservingPreviousVersion(AtomicOperation atomicOperation,
      long collectionPosition,
      PositionEntry positionEntry) throws IOException {

    var recordVersion = atomicOperation.getCommitTs();
    keepPreviousRecordVersion(collectionPosition, recordVersion, atomicOperation, positionEntry);

    // Mark as REMOVED in the position map. The deletion version (commitTs) is stored so
    // readers can determine whether the deletion is visible to their snapshot.
    collectionPositionMap.remove(collectionPosition, recordVersion, atomicOperation);

    decrementApproximateRecordsCount(atomicOperation);
  }

  private void incrementApproximateRecordsCount(AtomicOperation atomicOperation)
      throws IOException {
    try (final var stateCacheEntry =
        loadPageForWrite(atomicOperation, fileId, STATE_ENTRY_INDEX, true)) {
      final var state = new PaginatedCollectionStateV2(stateCacheEntry);
      final var count = state.getApproximateRecordsCount();
      state.setApproximateRecordsCount(count + 1);
      approximateRecordsCount = count + 1;
    }
  }

  private void decrementApproximateRecordsCount(AtomicOperation atomicOperation)
      throws IOException {
    try (final var stateCacheEntry =
        loadPageForWrite(atomicOperation, fileId, STATE_ENTRY_INDEX, true)) {
      final var state = new PaginatedCollectionStateV2(stateCacheEntry);
      final var count = state.getApproximateRecordsCount();
      state.setApproximateRecordsCount(count - 1);
      approximateRecordsCount = count - 1;
    }
  }

  @Override
  public void updateRecord(
      final long collectionPosition,
      final byte[] content,
      final byte recordType,
      final AtomicOperation atomicOperation) {
    executeInsideComponentOperation(
        atomicOperation,
        operation -> {
          acquireExclusiveLock();
          try {
            doUpdateRecord(collectionPosition, content, recordType, atomicOperation);
          } finally {
            releaseExclusiveLock();
          }
        });
  }

  private void doUpdateRecord(long collectionPosition, @Nonnull byte[] content,
      byte recordType, @Nonnull AtomicOperation atomicOperation) throws IOException {
    final var positionEntry = collectionPositionMap.get(collectionPosition, atomicOperation);
    if (positionEntry == null) {
      throw new RecordNotFoundException(storageName, new RecordId(id, collectionPosition));
    }

    updateRecordWithPreservingPreviousVersion(collectionPosition, content,
        recordType, atomicOperation, positionEntry);
  }

  /**
   * Updates a record with new content while preserving the old version for MVCC readers. The
   * update always writes the new content to fresh page slot(s) (it does <em>not</em> overwrite
   * in place), then updates the position map to point to the new location. If the new content
   * happens to land on the same page/offset (unlikely in practice), only the version is updated.
   */
  private void updateRecordWithPreservingPreviousVersion(long collectionPosition, byte[] content,
      byte recordType, AtomicOperation atomicOperation,
      PositionEntry positionEntry) throws IOException {
    var newRecordVersion = atomicOperation.getCommitTs();
    keepPreviousRecordVersion(collectionPosition, newRecordVersion, atomicOperation, positionEntry);

    final var result =
        serializeRecord(
            content,
            calculateCollectionEntrySize(content.length),
            recordType,
            newRecordVersion,
            collectionPosition,
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
                        "Can not update record with rid "
                            + new RecordId(id, collectionPosition), this),
                    e, storageName);
              }
            });

    final var nextPageIndex = result[0];
    final var nextRecordPosition = result[1];

    assert result[2] == 0;

    if (nextPageIndex != positionEntry.getPageIndex()
        || nextRecordPosition != positionEntry.getRecordPosition()) {
      collectionPositionMap.update(
          collectionPosition,
          new PositionEntry(nextPageIndex, nextRecordPosition, newRecordVersion),
          atomicOperation);
    } else if (newRecordVersion != positionEntry.getRecordVersion()) {
      collectionPositionMap.updateVersion(
          collectionPosition, newRecordVersion, atomicOperation);
    }
  }

  /**
   * Saves the current version of a record to the snapshot index before it is overwritten or
   * deleted, enabling MVCC snapshot isolation for concurrent readers.
   *
   * <p>If the old version equals the new version (i.e., the same transaction is overwriting its
   * own write), the snapshot is skipped because no other transaction could have seen that version.
   *
   * <p>Two index entries are created:
   * <ol>
   *   <li><b>Snapshot entry</b> ({@link SnapshotKey} -> {@link PositionEntry}): maps the old
   *       {@code (collectionId, collectionPosition, oldVersion)} triple to the physical page
   *       location of the old record. This allows readers with a snapshot older than
   *       {@code newRecordVersion} to find and read the previous version.</li>
   *   <li><b>Visibility entry</b> ({@link VisibilityKey} -> {@link SnapshotKey}): keyed by
   *       {@code newRecordVersion}, enables garbage collection of the snapshot entry once all
   *       transactions that could see the old version have completed.</li>
   * </ol>
   *
   * @param collectionPosition logical position of the record being modified
   * @param newRecordVersion   the commitTs of the modifying transaction
   * @param atomicOperation    the current atomic operation context
   * @param positionEntry      the current physical location of the record to preserve
   */
  private void keepPreviousRecordVersion(long collectionPosition, long newRecordVersion,
      AtomicOperation atomicOperation, PositionEntry positionEntry) throws IOException {
    try (final var cacheEntry = loadPageForRead(atomicOperation, fileId,
        positionEntry.getPageIndex())) {
      final var localPage = new CollectionPage(cacheEntry);
      var oldRecordVersion = localPage.getRecordVersion(positionEntry.getRecordPosition());
      if (oldRecordVersion == newRecordVersion) {
        // Same transaction overwriting its own write -- no need to preserve, because
        // no other transaction could have seen this version.
        return;
      }

      // Store the old version's physical location in the snapshot index.
      var snapshotKey = new SnapshotKey(id, collectionPosition, oldRecordVersion);
      atomicOperation.putSnapshotEntry(snapshotKey, positionEntry);

      // Register a visibility entry so the snapshot can be garbage-collected once all
      // transactions that started before newRecordVersion have completed.
      var visibilityKey = new VisibilityKey(newRecordVersion, id, collectionPosition);
      atomicOperation.putVisibilityEntry(visibilityKey, snapshotKey);
    }
  }

  @Override
  public void updateRecordVersion(long collectionPosition,
      AtomicOperation atomicOperation) {
    executeInsideComponentOperation(
        atomicOperation,
        operation -> {
          acquireExclusiveLock();
          try {
            final var positionEntry =
                collectionPositionMap.get(collectionPosition, atomicOperation);
            if (positionEntry == null) {
              throw new RecordNotFoundException(storageName,
                  new RecordId(id, collectionPosition));
            }

            final var pageIndex = positionEntry.getPageIndex();
            final var recordPosition = positionEntry.getRecordPosition();
            final var newRecordVersion = atomicOperation.getCommitTs();

            keepPreviousRecordVersion(
                collectionPosition, newRecordVersion, atomicOperation, positionEntry);

            try (final var cacheEntry = loadPageForWrite(atomicOperation, fileId, pageIndex,
                true)) {
              final var localPage = new CollectionPage(cacheEntry);
              if (localPage.getRecordByteValue(
                  recordPosition, -LongSerializer.LONG_SIZE - ByteSerializer.BYTE_SIZE)
                  == 0) {
                throw new RecordNotFoundException(storageName,
                    new RecordId(id, collectionPosition));
              }

              if (!localPage.setRecordVersion(recordPosition, versionToInt(newRecordVersion))) {
                throw new RecordNotFoundException(storageName,
                    new RecordId(id, collectionPosition));
              }

              collectionPositionMap.updateVersion(
                  collectionPosition, newRecordVersion, atomicOperation);
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
  public PhysicalPosition getPhysicalPosition(final PhysicalPosition position,
      AtomicOperation atomicOperation)
      throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
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
          physicalPosition.recordVersion = positionEntry.getRecordVersion();
          physicalPosition.collectionPosition = position.collectionPosition;

          assert assertVersionConsistency(
              physicalPosition.recordVersion,
              localPage.getRecordVersion(recordPosition));

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
  public boolean exists(long collectionPosition, AtomicOperation atomicOperation)
      throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
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
  public long getEntries(AtomicOperation atomicOperation) {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        var snapshot = atomicOperation.getAtomicOperationsSnapshot();
        var commitTs = atomicOperation.getCommitTsUnsafe();

        var count = new long[]{0};
        // Single pass over all position map entries (FILLED and REMOVED).
        // FILLED entries are counted when their version is visible.
        // REMOVED entries (tombstones) are counted only when the deletion
        // is NOT visible (i.e., the record was deleted after the reader's
        // snapshot started) AND a visible historical version exists.
        collectionPositionMap.forEachEntry(atomicOperation,
            (position, status, recordVersion) -> {
              if (status == CollectionPositionMapBucket.FILLED) {
                if (isRecordVersionVisible(recordVersion, commitTs, snapshot)) {
                  count[0]++;
                } else {
                  var historical = findHistoricalPositionEntry(
                      position, commitTs, snapshot, atomicOperation);
                  if (historical != null) {
                    count[0]++;
                  }
                }
              } else {
                // REMOVED: skip positions deleted by the current transaction
                if (commitTs >= 0
                    && atomicOperation.containsVisibilityEntry(
                    new VisibilityKey(commitTs, id, position))) {
                  return;
                }

                // For REMOVED entries, recordVersion holds the deletion timestamp.
                // If the deletion is visible to the reader, the record is gone.
                if (isRecordVersionVisible(recordVersion, commitTs, snapshot)) {
                  return;
                }

                // The deletion is not yet visible - check if there's a historical
                // version that the reader can see.
                var historical = findHistoricalPositionEntry(
                    position, commitTs, snapshot, atomicOperation);
                if (historical != null) {
                  count[0]++;
                }
              }
            });

        return count[0];
      } finally {
        releaseSharedLock();
      }
    } catch (final IOException ioe) {
      throw BaseException.wrapException(
          new PaginatedCollectionException(storageName,
              "Error during retrieval of size of '"
                  + getName() + "' collection", this),
          ioe, storageName);
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public long getApproximateRecordsCount() {
    return approximateRecordsCount;
  }

  @Override
  public long getFirstPosition(AtomicOperation atomicOperation) throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        return collectionPositionMap.getFirstPosition(atomicOperation);
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public long getLastPosition(AtomicOperation atomicOperation) throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        return collectionPositionMap.getLastPosition(atomicOperation);
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
  @Override
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
  public PhysicalPosition[] higherPositions(final PhysicalPosition position, int limit,
      AtomicOperation atomicOperation)
      throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final var collectionPositions =
            collectionPositionMap.higherPositions(position.collectionPosition,
                atomicOperation, limit);
        return convertToPhysicalPositions(collectionPositions);
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public PhysicalPosition[] ceilingPositions(final PhysicalPosition position, int limit,
      AtomicOperation atomicOperation)
      throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
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
  public PhysicalPosition[] lowerPositions(final PhysicalPosition position, int limit,
      AtomicOperation atomicOperation)
      throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
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
  public PhysicalPosition[] floorPositions(final PhysicalPosition position, int limit,
      AtomicOperation atomicOperation)
      throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
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

  /**
   * Shared initialization logic called by both {@link #configure} overloads. Validates the
   * collection name, optionally sets the record conflict strategy, registers per-collection
   * metrics, and stores the numeric collection ID.
   */
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
      final byte recordType, final long collectionPosition, final long version) {
    final var physicalPosition = new PhysicalPosition();
    physicalPosition.recordType = recordType;
    physicalPosition.recordSize = -1;
    physicalPosition.collectionPosition = collectionPosition;
    physicalPosition.recordVersion = version;
    return physicalPosition;
  }

  /**
   * Reassembles a multi-chunk record into a single contiguous byte array by concatenating the
   * data portions of each chunk (stripping the trailing first-record flag and next-page pointer
   * from each). If the record fits in a single chunk, the original array is returned as-is.
   *
   * @param recordChunks list of raw chunk byte arrays in read order (head to tail)
   * @param contentSize  total data bytes across all chunks (excluding per-chunk overhead)
   * @return a single byte array containing the full entry (metadata + content)
   */
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

  /**
   * Packs a page index and a record position into a single {@code long} pointer.
   *
   * <pre>{@code
   * Bit layout of the packed pointer:
   *  63                 16  15              0
   *  +--------------------+-----------------+
   *  |    pageIndex       | recordPosition  |
   *  +--------------------+-----------------+
   * }</pre>
   *
   * @see #getPageIndex(long)
   * @see #getRecordPosition(long)
   */
  private static long createPagePointer(final long pageIndex, final int pagePosition) {
    return pageIndex << PAGE_INDEX_OFFSET | pagePosition;
  }

  /** Extracts the 16-bit record position from a packed page pointer. */
  private static int getRecordPosition(final long nextPagePointer) {
    return (int) (nextPagePointer & RECORD_POSITION_MASK);
  }

  /** Extracts the page index (bits 16..63) from a packed page pointer. */
  private static long getPageIndex(final long nextPagePointer) {
    return nextPagePointer >>> PAGE_INDEX_OFFSET;
  }

  /**
   * Allocates a new data page in the collection's data file and increments the file-size counter
   * stored on the state page (page 0).
   *
   * <p>The collection tracks its own "logical" file size in
   * {@link PaginatedCollectionStateV2#getFileSize()}, which may be less than the physical file
   * size reported by the cache ({@code filledUpTo}). If the logical size equals the physical
   * size minus 1 (accounting for page 0 being the state page), a truly new page is appended to
   * the file. Otherwise, the next page after the current logical end is reused (it already
   * exists on disk but was not yet claimed by this collection). This can happen after a crash
   * where pages were physically allocated but the state counter was not yet updated.
   *
   * @param atomicOperation the current atomic operation context
   * @return a {@link CacheEntry} for the newly allocated page (caller must close it)
   */
  private CacheEntry allocateNewPage(AtomicOperation atomicOperation) throws IOException {
    CacheEntry cacheEntry;
    try (final var stateCacheEntry =
        loadPageForWrite(atomicOperation, fileId, STATE_ENTRY_INDEX, true)) {
      final var collectionState = new PaginatedCollectionStateV2(stateCacheEntry);
      final var fileSize = collectionState.getFileSize();
      final var filledUpTo = getFilledUpTo(atomicOperation, fileId);

      if (fileSize == filledUpTo - 1) {
        // Logical end matches physical end -- must physically append a new page.
        cacheEntry = addPage(atomicOperation, fileId);
      } else {
        // Physical file has pages beyond the logical end -- reuse the next one.
        assert fileSize < filledUpTo - 1;
        cacheEntry = loadPageForWrite(atomicOperation, fileId, fileSize + 1, false);
      }

      collectionState.setFileSize(fileSize + 1);
    }
    return cacheEntry;
  }

  /**
   * Initializes page 0 of the data file as the collection state page. If the file is empty,
   * a new page is appended; otherwise the existing page 0 is loaded. The file-size counter is
   * reset to 0, meaning no data pages are currently allocated (page 0 itself is not counted).
   */
  private void initCollectionState(final AtomicOperation atomicOperation) throws IOException {
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
      paginatedCollectionState.setFileSize(0);
      paginatedCollectionState.setApproximateRecordsCount(0);
      approximateRecordsCount = 0;
    } finally {
      stateEntry.close();
    }
  }

  private static PhysicalPosition[] convertToPhysicalPositions(final long[] collectionPositions) {
    final var positions = new PhysicalPosition[collectionPositions.length];

    for (var i = 0; i < collectionPositions.length; i++) {
      final var physicalPosition = new PhysicalPosition();
      physicalPosition.collectionPosition = collectionPositions[i];
      positions[i] = physicalPosition;
    }

    return positions;
  }

  @Override
  public RECORD_STATUS getRecordStatus(final long collectionPosition,
      AtomicOperation atomicOperation) throws IOException {
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

  /// Verifies that the version stored in the position map matches the version stored on the data
  /// page. Must only be called within {@code assert} statements.
  @SuppressWarnings("SameReturnValue")
  private static boolean assertVersionConsistency(
      long positionMapVersion, long pageVersion) {
    if (positionMapVersion != pageVersion) {
      throw new AssertionError(
          "Version mismatch: positionMap=" + positionMapVersion
              + " page=" + pageVersion);
    }
    return true;
  }

  /// Verifies that the collection position stored in the serialized record matches the expected
  /// collection position. Must only be called within {@code assert} statements.
  @SuppressWarnings("SameReturnValue")
  private static boolean assertPositionConsistency(
      long expectedPosition, long storedPosition) {
    if (expectedPosition != storedPosition) {
      throw new AssertionError(
          "Collection position mismatch: expected=" + expectedPosition
              + " stored=" + storedPosition);
    }
    return true;
  }

  /// Safely narrows a {@code long} record version to {@code int}. Throws if the value does not fit,
  /// preventing silent truncation during the transition period before the rest of the codebase
  /// migrates from {@code int} to {@code long} versions.
  private static int versionToInt(long version) {
    if ((int) version != version) {
      throw new IllegalStateException(
          "Record version " + version + " exceeds int range");
    }
    return (int) version;
  }

  @Override
  public void acquireAtomicExclusiveLock(AtomicOperation atomicOperation) {
    atomicOperationsManager.acquireExclusiveLockTillOperationComplete(atomicOperation, this);
  }

  @Override
  public String toString() {
    return "disk collection: " + getName();
  }

  @Nullable
  @Override
  public CollectionBrowsePage nextPage(
      long prevPagePosition,
      final boolean forwards,
      AtomicOperation atomicOperation) throws IOException {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        if (prevPagePosition < 0) {
          prevPagePosition = -1;
        }

        final var nextPositions = forwards ?
            collectionPositionMap.higherPositionsEntries(prevPagePosition, atomicOperation) :
            collectionPositionMap.lowerPositionsEntriesReversed(prevPagePosition, atomicOperation);

        if (nextPositions.length > 0) {
          var snapshot = atomicOperation.getAtomicOperationsSnapshot();
          var operationTs = atomicOperation.getCommitTsUnsafe();

          final List<CollectionBrowseEntry> next = new ArrayList<>(nextPositions.length);
          for (final var pos : nextPositions) {
            // Check visibility before doing the expensive page read.
            // The position map is shared across transactions, so it may contain
            // entries for records inserted by concurrent transactions that are
            // not yet visible to this snapshot.
            if (isRecordVersionVisible(pos.getRecordVersion(), operationTs, snapshot)) {
              final var buff =
                  internalReadRecord(
                      pos.getPosition(), pos.getPage(), pos.getOffset(),
                      pos.getRecordVersion(), atomicOperation);
              next.add(new CollectionBrowseEntry(pos.getPosition(), buff));
            } else {
              // Record version not visible — check for a historical version
              // (e.g., the record was updated by a concurrent transaction and
              // the old version is still visible to this snapshot).
              var historicalEntry = findHistoricalPositionEntry(
                  pos.getPosition(), operationTs, snapshot, atomicOperation);
              if (historicalEntry != null) {
                final var buff = readRecordFromHistoricalEntry(
                    pos.getPosition(),
                    historicalEntry.getPageIndex(),
                    historicalEntry.getRecordPosition(),
                    historicalEntry.getRecordVersion(),
                    atomicOperation);
                next.add(new CollectionBrowseEntry(pos.getPosition(), buff));
              }
              // else: record was inserted by a concurrent transaction and has no
              // historical version visible to this snapshot — skip it.
            }
          }

          if (next.isEmpty()) {
            return null;
          }
          return new CollectionBrowsePage(next);
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
