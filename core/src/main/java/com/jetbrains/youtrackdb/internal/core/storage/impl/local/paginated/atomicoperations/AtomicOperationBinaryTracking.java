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
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexCountDeltaHolder;
import com.jetbrains.youtrackdb.internal.core.storage.cache.AbstractWriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ApplyPhaseEpoch;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ComponentEpochRegistry;
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
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.memory.DirectMemoryOnlyDiskCache;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.EdgeSnapshotKey;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.EdgeVisibilityKey;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.LinkBagValue;
import it.unimi.dsi.fastutil.ints.IntIntImmutablePair;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
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
  @Nullable private final WriteAheadLog writeAheadLog;

  // Tracks whether the WAL atomic unit start record has been emitted (lazily).
  // Shared between flushPendingOperations() and commitChanges() so that the
  // start record is emitted at most once per atomic operation.
  private boolean walUnitStarted;
  @Nullable private LogSequenceNumber startLSN;

  // Fast-path flag: set to true when any page registers a PageOperation,
  // cleared after flushPendingOperations() completes. Allows the flush hook
  // in AtomicOperationsManager to short-circuit when no pending ops exist
  // (read-only operations, unconverted page types).
  private boolean hasPendingOperations;

  // Pages that have pending operations waiting to be flushed. Populated by
  // registerPageOperation(), drained by flushPendingOperations(). Avoids the
  // O(files × pages) full scan that would otherwise be needed to locate the
  // few pages with pending ops after each component operation boundary.
  private final ArrayList<PendingFlushEntry> pendingFlushEntries = new ArrayList<>();

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

  // Index entry count delta accumulator — lazily allocated to avoid overhead
  // for transactions that do not modify indexed data.
  @Nullable private IndexCountDeltaHolder indexCountDeltas;

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
  private final OptimisticReadScope optimisticReadScope;

  // TEST-ONLY seam for the commit-time page-apply loop (see PageApplyHook). Always null
  // in production — the apply loop takes the unmodified fast path on null. Volatile
  // because tests install the hook on the test thread while commitChanges may run on a
  // separate writer thread.
  @Nullable private volatile PageApplyHook pageApplyHook;

  // Per-storage registry resolving each fileId to the owning component's apply-phase
  // epoch (YTDB-1203), owned by AtomicOperationsManager and shared by all atomic
  // operations of the same storage. At commit time the epochs of all mutated components
  // are bumped around the cache-apply section of commitChanges so concurrent optimistic
  // readers of those components can detect overlap with a partially applied commit
  // (per-page stamps alone cannot — they are a temporal check only).
  private final ComponentEpochRegistry componentEpochRegistry;

  /**
   * Convenience constructor for standalone use (tests, tooling) where no epoch registry
   * is shared with concurrent optimistic readers: wires a private single-epoch registry
   * that resolves EVERY fileId to one epoch nobody else observes (see
   * {@link ComponentEpochRegistry#uniform}), so the fail-loud mutated-fileId resolution
   * in {@link #commitChanges} always succeeds and standalone commits keep the
   * pre-per-component behaviour of exactly one enter/exit pair per commit. Production
   * code must use the primary constructor with the registry owned by
   * {@link AtomicOperationsManager} and populated by the StorageComponent funnel — a
   * private registry would make commit-time applies invisible to optimistic readers of
   * other operations on the same storage.
   */
  AtomicOperationBinaryTracking(
      final ReadCache readCache,
      final WriteCache writeCache,
      @Nullable final WriteAheadLog writeAheadLog,
      final int storageId,
      @Nonnull AtomicOperationsSnapshot snapshot,
      @Nonnull ConcurrentSkipListMap<SnapshotKey, PositionEntry> sharedSnapshotIndex,
      @Nonnull ConcurrentSkipListMap<VisibilityKey, SnapshotKey> sharedVisibilityIndex,
      @Nonnull AtomicLong snapshotIndexSize,
      @Nonnull ConcurrentSkipListMap<EdgeSnapshotKey, LinkBagValue> sharedEdgeSnapshotIndex,
      @Nonnull ConcurrentSkipListMap<EdgeVisibilityKey, EdgeSnapshotKey> sharedEdgeVisibilityIndex,
      @Nonnull AtomicLong edgeSnapshotIndexSize) {
    this(readCache, writeCache, writeAheadLog, storageId, snapshot, sharedSnapshotIndex,
        sharedVisibilityIndex, snapshotIndexSize, sharedEdgeSnapshotIndex,
        sharedEdgeVisibilityIndex, edgeSnapshotIndexSize,
        ComponentEpochRegistry.uniform(new ApplyPhaseEpoch()));
  }

  AtomicOperationBinaryTracking(
      final ReadCache readCache,
      final WriteCache writeCache,
      @Nullable final WriteAheadLog writeAheadLog,
      final int storageId,
      @Nonnull AtomicOperationsSnapshot snapshot,
      @Nonnull ConcurrentSkipListMap<SnapshotKey, PositionEntry> sharedSnapshotIndex,
      @Nonnull ConcurrentSkipListMap<VisibilityKey, SnapshotKey> sharedVisibilityIndex,
      @Nonnull AtomicLong snapshotIndexSize,
      @Nonnull ConcurrentSkipListMap<EdgeSnapshotKey, LinkBagValue> sharedEdgeSnapshotIndex,
      @Nonnull ConcurrentSkipListMap<EdgeVisibilityKey, EdgeSnapshotKey> sharedEdgeVisibilityIndex,
      @Nonnull AtomicLong edgeSnapshotIndexSize,
      @Nonnull ComponentEpochRegistry componentEpochRegistry) {
    this.snapshot = snapshot;
    newFileNamesId.defaultReturnValue(-1);
    deletedFileNameIdMap.defaultReturnValue(-1);

    this.storageId = storageId;
    this.readCache = readCache;
    this.writeCache = writeCache;
    this.writeAheadLog = writeAheadLog;
    this.sharedSnapshotIndex = sharedSnapshotIndex;
    this.sharedVisibilityIndex = sharedVisibilityIndex;
    this.snapshotIndexSize = snapshotIndexSize;
    this.sharedEdgeSnapshotIndex = sharedEdgeSnapshotIndex;
    this.sharedEdgeVisibilityIndex = sharedEdgeVisibilityIndex;
    this.edgeSnapshotIndexSize = edgeSnapshotIndexSize;
    this.componentEpochRegistry = componentEpochRegistry;
    // No epoch is bound at scope construction: the scope re-captures the epoch of the
    // component each read attempt targets via reset(ApplyPhaseEpoch) (YTDB-1203).
    this.optimisticReadScope = new OptimisticReadScope();
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

  /**
   * Write-side page allocator. <b>Cross-engine asymmetry: allocator-only on disk;
   * eager-install total on in-memory.</b> On the disk engine the contract is strictly
   * allocator-only — callers targeting a {@code pageIndex} below the committed file size
   * raise {@link IllegalStateException}; use {@link #loadPageForWrite} for existing pages.
   * On the in-memory engine the same call eagerly installs the page in {@code MemoryFile}
   * (see "Per-engine delegate shape" below) and bypasses the strict allocator-only check
   * so a TX rollback's cache-resident orphans can be re-used by the next TX. Returns a
   * {@link CacheEntryChanges} overlay that the caller accumulates writes into via
   * {@code CacheEntryChanges.changes}; the underlying delegate shape differs by engine
   * (see "Per-engine delegate shape" below).
   *
   * <p>The caller must supply a target {@code pageIndex} that is genuinely new — either
   * the file was booked in this TX (everything past page -1 is new) or
   * {@code pageIndex >= writeCache.getFilledUpTo(fileId)}. Asking for an existing page
   * raises {@link IllegalStateException} on the disk engine; use {@link #loadPageForWrite}
   * to mutate an existing page. On the in-memory engine the strict allocator-only check
   * is bypassed to support rollback-orphan re-use (see "Per-engine delegate shape" → in-memory
   * branch).
   *
   * <p>Idempotency: a second call for the same {@code (fileId, pageIndex)} within the
   * same TX returns the previously-registered overlay. The early-return is what makes
   * the SLBB.splitRootBucket two-page recipe safe — two consecutive calls with the
   * same {@code entryPoint.pagesSize + 1} value would return the same overlay to both
   * bucket entries and silently merge their writes.
   *
   * <p>Bookkeeping mirrors legacy {@code addPage}: the overlay is registered in
   * {@code pageChangesMap} keyed by the actual {@code pageIndex}, and
   * {@code maxNewPageIndex} is bumped so the in-progress visibility horizon consulted
   * by {@link #loadPageForWrite}, {@link #loadPageForRead}, and {@link #hasChangesForPage}
   * stays consistent. Fresh-booked files take the stub-shape path only on the disk engine
   * (where {@code readCache.addFile} runs in {@code commitChanges}); on the in-memory
   * engine {@link #addFile} eagerly registers the underlying {@code MemoryFile} so the
   * eager-install branch covers fresh-booked-file pages too.
   *
   * <p><b>Per-engine delegate shape.</b>
   *
   * <ul>
   *   <li><b>Disk engine</b> ({@code LockFreeReadCache} + {@code WOWCache}): the delegate
   *       wraps a {@link CachePointer} with a null native pointer (the stub shape). The
   *       real cache slot is materialized at commit time inside {@link #commitChanges},
   *       when the {@code pageChangesMap} replay loop calls
   *       {@code readCache.loadOrAddForWrite} for each new-page entry. The disk-engine
   *       {@code loadOrAddForWrite} bottoms out on the total {@code WriteCache.loadOrAdd}
   *       primitive, so the install always succeeds at commit time.
   *   <li><b>In-memory engine</b> ({@link DirectMemoryOnlyDiskCache}): we eagerly install
   *       the page in {@code MemoryFile} via {@code readCache.loadOrAdd}, which delegates
   *       to {@code MemoryFile.loadOrAddPage} and bumps the returned pointer's
   *       readers-referrer count by 1. We then decrement that bump once so the net
   *       referrer balance matches the disk-engine path (whose commit-time install never
   *       hands out an extra readers reference). The eager install is required because
   *       the in-memory engine's read-cache wrappers are deliberately non-total
   *       ({@code loadOrAddForWrite} / {@code loadForRead} return {@code null} on miss),
   *       so without the install the {@link #commitChanges} replay loop's
   *       {@code loadOrAddForWrite} call would return null and the per-page accumulated
   *       changes would have no slot to apply against. The strict allocator-only check
   *       is bypassed on this branch — a rolled-back TX leaves its eagerly-installed
   *       pages in {@code MemoryFile} (the cache is not rolled back), so the next TX
   *       legitimately sees {@code writeCache.getFilledUpTo()} above the logical
   *       allocation horizon (e.g., {@code mapEntryPoint.fileSize}) and must re-allocate
   *       the same logical page. {@code MemoryFile.loadOrAddPage}'s {@code putIfAbsent}
   *       semantics keep the re-use safe: the orphan's magic-empty-LSN header carries
   *       no real data and the new TX overwrites it via {@code CacheEntryChanges}.
   *       Fresh-booked files take this branch too: {@link #addFile} eagerly calls
   *       {@code readCache.addFile} on the in-memory engine so the underlying
   *       {@code MemoryFile} is registered before {@code allocatePageForWrite} fires,
   *       and {@code commitChanges} skips its late {@code readCache.addFile} call via
   *       the {@code FileChanges.eagerlyInstalledInCache} flag.
   * </ul>
   *
   * <p>Stub shape rationale (disk engine only): the overlay's delegate wraps a
   * {@link CachePointer} with a null native pointer. {@link #releasePageFromWrite} gates
   * the underlying cache release on a non-null buffer, so multi-access in the same TX
   * (via repeated {@code loadPageForWrite} on the same pageIndex returning the existing
   * overlay) stays a no-op on the cache's usage counter. A real cache-installed delegate
   * would underflow as soon as the second close fires.
   *
   * <p>The in-memory eager-install branch wraps the cache-installed pointer in a
   * {@link CacheEntryImpl} (insideCache=false, no exclusive lock held) and relies on
   * the readers-referrer balancing to keep the {@link #releasePageFromWrite} gate
   * accurate: the gate checks {@code getCachePointer().getBuffer() != null}, which is
   * true on this branch, and the call goes through {@code readCache.releaseFromRead}
   * which on the in-memory engine only manipulates usagesCount and never touches
   * readers-referrer. The eager-install path therefore explicitly decrements the
   * readers-referrer the {@code readCache.loadOrAdd} call handed us, returning the
   * page to the cache's "no caller-held reference" baseline before the overlay is
   * wrapped; subsequent {@code releaseFromRead}-driven release inside
   * {@link #releasePageFromWrite} is a usages-only decrement with no leak.
   */
  // The IOException on the signature comes from the AtomicOperation interface, which
  // covers implementations that perform I/O (today's body does not, but the contract
  // must remain compatible). ErrorProne otherwise flags the unused throws clause.
  // The "deprecation" suppression covers the documented internal call to
  // WriteCache.getFilledUpTo inside the isNew slow-path classifier below — the
  // method is now @Deprecated for external callers but this allocation-floor read,
  // taken under the per-component exclusive lock, is one of the retained internal
  // sites listed on WriteCache.getFilledUpTo's Javadoc.
  @SuppressWarnings({"CheckedExceptionNotThrown", "deprecation"})
  @Override
  public CacheEntry allocatePageForWrite(long fileId, final long pageIndex)
      throws IOException {
    checkIfActive();
    assert pageIndex >= 0 : "pageIndex out of range: " + pageIndex;

    fileId = checkFileIdCompatibility(fileId, storageId);

    if (deletedFiles.contains(fileId)) {
      throw new StorageException(writeCache.getStorageName(),
          "File with id " + fileId + " is deleted.");
    }

    final var changesContainer =
        fileChanges.computeIfAbsent(fileId, k -> new FileChanges());

    // Idempotency inside the same TX: if a prior loadPageForWrite or
    // allocatePageForWrite already wrapped this pageIndex, return the existing
    // overlay so the WAL change buffer stays a single accumulator for the page.
    var existing = changesContainer.pageChangesMap.get(pageIndex);
    if (existing != null) {
      return existing;
    }

    final boolean fileIsNew = changesContainer.isNew;

    // Engine dispatch happens BEFORE the allocator-only contract check. The two engines
    // have different invariants around "physical extent vs logical allocation horizon":
    //   - Disk engine: WriteCache.loadOrAdd is total and below-floor calls are caller
    //     bugs — the strict allocator-only contract surfaces the violation as
    //     IllegalStateException. The check fires below.
    //   - In-memory engine: a TX rollback after this method eagerly installs pages
    //     leaves the page in MemoryFile (rollback does not roll back the cache).
    //     The next TX sees writeCache.getFilledUpTo() > mapEntryPoint.fileSize and
    //     the strict check would fire on a perfectly legal allocator-only re-entry.
    //     We deliberately bypass the strict check on this branch — re-using a
    //     rollback-orphan page is the in-memory engine's equivalent of the disk
    //     engine's partial-replay-orphan recovery, and MemoryFile.loadOrAddPage's
    //     putIfAbsent semantics make the re-use safe (the orphan's magic-empty-LSN
    //     header carries no real data; this TX overwrites it via CacheEntryChanges).
    // Fresh-booked files (fileIsNew=true) on the in-memory engine also take the
    // eager-install branch: AtomicOperationBinaryTracking.addFile eagerly calls
    // readCache.addFile when the engine is DirectMemoryOnlyDiskCache, so the
    // underlying MemoryFile is already registered and readCache.loadOrAdd will not
    // throw "File with id ... not found". On the disk engine, fresh-booked files
    // must still take the stub-shape branch — readCache.addFile creates physical
    // state (WAL records, AsyncFile open) that has not yet run at this point.
    final CacheEntry delegate;
    if (readCache instanceof DirectMemoryOnlyDiskCache inMemoryCache) {
      // In-memory engine: eagerly install the page in MemoryFile so the commitChanges
      // replay loop's loadOrAddForWrite call (which is null-on-miss on this engine, per
      // DirectMemoryOnlyDiskCache.java's deliberate divergence from the disk engine)
      // finds the page via MemoryFile.loadPage. Without this install, commitChanges
      // would see null and the per-page accumulated changes would have no slot to
      // apply against — that exact NPE has been observed in regression scenarios
      // where this install was skipped and commitChanges then hit MemoryFile.loadPage
      // on a fresh in-memory database.
      //
      // verifyChecksums is irrelevant on the in-memory engine (no on-disk image to
      // verify); pass false to match the legacy addPage shape. DirectMemoryOnlyDiskCache
      // explicitly ignores the flag.
      final var pointer = inMemoryCache.loadOrAdd(fileId, pageIndex, false);
      assert pointer != null
          : "DirectMemoryOnlyDiskCache.loadOrAdd returned null for fileId=" + fileId
              + " pageIndex=" + pageIndex + " (totality contract violated)";
      // CachePointer stores the int file id (the high-32 storage-id bits are stripped by
      // DirectMemoryOnlyDiskCache.extractFileId before MemoryFile.loadOrAddPage builds the
      // pointer), so compare against the low-32-bit extraction of the request's fileId.
      assert pointer.getFileId() == AbstractWriteCache.extractFileId(fileId)
          && pointer.getPageIndex() == (int) pageIndex
          : "in-memory loadOrAdd returned pointer at ("
              + pointer.getFileId() + ", " + pointer.getPageIndex()
              + ") but requested (" + AbstractWriteCache.extractFileId(fileId)
              + ", " + pageIndex + "); composed fileId=" + fileId;
      // MemoryFile.loadOrAddPage bumped the readers-referrer count by 1 for the caller
      // (the bump runs under the per-file clearLock readLock so a concurrent
      // clear() / deleteFile() / truncateFile() cannot recycle the frame between
      // publication and the increment). Our CacheEntryImpl(insideCache=false) wrapper
      // is released through ReadCache.releaseFromRead -> DirectMemoryOnlyDiskCache.doRelease,
      // which only manipulates usagesCount and never decrements readers-referrer (unlike
      // LockFreeReadCache.releaseFromRead's !insideCache branch on the disk engine).
      // Without this decrement, every page allocated through this branch would leak
      // both a `readers` reference and a `referrersCount` reference. On
      // MemoryFile.clear, the cache's decrementReferrer would bring referrersCount
      // from 2 to 1 (not 0), so the page frame stays allocated past clear() until the
      // leaked readers reference is released — which never happens. After the decrement
      // the count is 1 (the in-cache referrer held by installEmptyPage) so the page
      // stays resident and subsequent commit-time loadOrAddForWrite still finds it via
      // MemoryFile.loadPage. The in-cache referrer is intentionally retained across
      // the AOBT lifecycle so commit-time loadOrAddForWrite finds the page on the
      // in-memory engine.
      pointer.decrementReadersReferrer();
      // No exclusive lock is acquired on this branch. The AOBT lifecycle relies on
      // the CacheEntryChanges overlay (the change buffer) to serialize all in-TX
      // writes against the page; the underlying CachePointer's exclusive lock is
      // only acquired at commit time by commitChanges (which calls
      // readCache.loadOrAddForWrite for each pageChangesMap entry, and that method
      // returns a write-locked entry on both engines). Acquiring the per-page lock
      // here would not change observable behavior and would diverge from the
      // legacy stub-shape branch immediately below.
      delegate = new CacheEntryImpl(fileId, (int) pageIndex, pointer, false, readCache);
      // The AOBT-created wrapper starts with usagesCount=0. The eventual release at
      // releasePageFromWrite (gated by getBuffer() != null below) routes through
      // readCache.releaseFromRead -> doRelease.decrementUsages(), which would drive
      // the count to -1 without this increment. Pairing the increment here with the
      // release-driven decrement keeps the count balanced on a 1→0 transition,
      // matching the disk-engine path's invariant.
      delegate.incrementUsages();
      assert delegate.getCachePointer().getBuffer() != null
          : "in-memory eager-install delegate must carry a non-null buffer so"
              + " releasePageFromWrite drives readCache.releaseFromRead; fileId="
              + fileId + " pageIndex=" + pageIndex;
    } else {
      // Stub-shape branch: disk engine (any file state). The in-memory engine never
      // reaches this branch — its dispatch above covers both pre-existing and fresh-
      // booked files (addFile eagerly registers the underlying MemoryFile). The strict
      // allocator-only contract applies here: the disk engine's WriteCache.loadOrAdd
      // is total at commit time, so a below-floor target is unambiguously a caller bug
      // (use loadPageForWrite for existing pages); the fresh-booked-file path has no
      // committed pages so the check trivially passes.
      //
      // Lower bound on legal fresh-page indices. For new files this is 0. For existing
      // files with at least one prior in-TX allocation, this is maxNewPageIndex + 1 — by
      // the per-component lock, no cross-TX writer can have extended past
      // maxNewPageIndex, so the in-progress horizon is an equally valid floor (and is
      // immutable across this TX). Otherwise we probe the write cache once for the
      // committed cross-TX horizon.
      final long allocationFloor;
      if (fileIsNew) {
        allocationFloor = 0;
      } else if (changesContainer.maxNewPageIndex > -2) {
        allocationFloor = changesContainer.maxNewPageIndex + 1;
      } else {
        allocationFloor = writeCache.getFilledUpTo(fileId);
      }
      if (!(fileIsNew || pageIndex >= allocationFloor)) {
        throw new IllegalStateException(
            "allocatePageForWrite is allocation-only; pageIndex " + pageIndex
                + " is below allocationFloor " + allocationFloor
                + " on file with id " + fileId
                + ". Use loadPageForWrite for existing pages.");
      }
      // Stub-shape delegate: null native pointer so multi-access in the same TX stays a
      // no-op on the cache's usage counter (releasePageFromWrite gates the release on
      // a non-null buffer; commitChanges installs the real CachePointer at apply time
      // via readCache.loadOrAddForWrite).
      delegate =
          new CacheEntryImpl(
              fileId,
              (int) pageIndex,
              new CachePointer((Pointer) null, null, fileId, (int) pageIndex),
              false,
              readCache);
      assert delegate.getCachePointer().getBuffer() == null
          : "stub-shape delegate must carry a null buffer for fresh allocations";
    }

    final var changes = new CacheEntryChanges(false, this);
    changes.isNew = true;
    changes.delegate = delegate;

    // Bookkeeping: place the overlay entry in the page-changes map keyed by the
    // actual pageIndex (no prediction), and bump maxNewPageIndex so the in-progress
    // visibility horizon stays consistent with the new allocation. The pre-insert
    // assert mirrors legacy addPage's invariant — the per-component lock contract
    // promises a single in-TX allocator for each (fileId, pageIndex), and the
    // idempotent-call shape is already covered by the early-return above.
    assert changesContainer.pageChangesMap.get(pageIndex) == null
        : "pageChangesMap already contains pageIndex=" + pageIndex
            + " — concurrent allocatePageForWrite for the same (fileId, pageIndex)"
            + " inside a single AtomicOperation; per-component lock contract violated";
    changesContainer.pageChangesMap.put(pageIndex, changes);
    if (pageIndex > changesContainer.maxNewPageIndex) {
      changesContainer.maxNewPageIndex = pageIndex;
    }

    return changes;
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
  public void registerPageOperation(long fileId, long pageIndex, PageOperation op) {
    checkIfActive();
    assert op != null : "PageOperation must not be null";

    fileId = checkFileIdCompatibility(fileId, storageId);

    final var changesContainer = fileChanges.get(fileId);
    assert changesContainer != null
        : "File " + fileId + " has no FileChanges — page must be loaded for write first";

    final var pageChanges = changesContainer.pageChangesMap.get(pageIndex);
    assert pageChanges != null
        : "Page " + pageIndex + " in file " + fileId
            + " has no CacheEntryChanges — page must be loaded for write first";

    pageChanges.addPendingOperation(op);
    pendingFlushEntries.add(
        new PendingFlushEntry(fileId, changesContainer.nonDurable, pageChanges));
    hasPendingOperations = true;
  }

  @Override
  public void flushPendingOperations() throws IOException {
    if (!hasPendingOperations) {
      return;
    }

    checkIfActive();
    assert writeAheadLog != null
        : "flushPendingOperations called but WriteAheadLog is null";
    assert operationCommitTs != -1
        : "flushPendingOperations called before operationCommitTs was set"
            + " — call startToApplyOperations first";

    for (final var entry : pendingFlushEntries) {
      // Skip non-durable files — they never produce WAL records
      if (entry.nonDurable() || writeCache.isNonDurable(entry.fileId())) {
        entry.changes().clearPendingOperations();
        continue;
      }

      final var pageChanges = entry.changes();
      final var pendingOps = pageChanges.getPendingOperations();

      // A page may appear more than once in the list if multiple operations
      // were registered between flushes; earlier visits already drained it.
      if (pendingOps.isEmpty()) {
        continue;
      }

      // Lazy emission of AtomicUnitStartRecord before the first WAL record
      if (!walUnitStarted) {
        startLSN = emitWalUnitStart(writeAheadLog, operationCommitTs);
        walUnitStarted = true;
      }

      for (final var op : pendingOps) {
        op.setOperationUnitId(operationCommitTs);
        final var lsn = writeAheadLog.log(op);
        pageChanges.setChangeLSN(lsn);
      }

      pageChanges.clearPendingOperations();
    }

    pendingFlushEntries.clear();
    hasPendingOperations = false;
  }

  // The committed-file fall-through below reads physical size via WriteCache.getFilledUpTo,
  // one of the retained internal callers documented on that method's Javadoc. Layer B
  // helpers (StorageComponent.physicalSize) intentionally route through this method so the
  // FileChanges placeholder side-effect is registered on first in-TX touch — the read
  // surface stays a single AOBT method even though the cache primitive is deprecated for
  // external callers.
  @SuppressWarnings("deprecation")
  @Override
  public long filledUpTo(long fileId) {
    checkIfActive();

    fileId = checkFileIdCompatibility(fileId, storageId);
    if (deletedFiles.contains(fileId)) {
      throw new StorageException(writeCache.getStorageName(),
          "File with id " + fileId + " is deleted.");
    }
    // Three-arm logic preserved verbatim from the prior private helper: a missing
    // entry registers an empty FileChanges placeholder and falls through to the
    // committed-file branch; a new or page-overlay-bearing entry returns the
    // logical extent (maxNewPageIndex + 1); a truncate-flagged entry returns 0;
    // otherwise the physical extent from the write cache is the answer. The
    // placeholder is registered as a side effect so subsequent in-TX queries hit
    // the existing-entry arm without re-allocating.
    var changesContainer = fileChanges.get(fileId);
    if (changesContainer == null) {
      fileChanges.put(fileId, new FileChanges());
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
  public long addFile(final String fileName, final boolean nonDurable) throws IOException {
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

    // In-memory engine: eagerly register the file with the read cache so subsequent
    // allocatePageForWrite calls in this TX can take the eager-install branch even for
    // pages 0/1 of a freshly-booked file. The disk engine cannot do this — its
    // readCache.addFile creates physical state (WAL records, AsyncFile open) that would
    // need to be rolled back on TX failure. The in-memory engine has neither: addFile
    // just inserts a MemoryFile into the per-storage map, and a rolled-back TX leaves
    // the empty MemoryFile orphaned in the cache (bounded session-scoped leak, same
    // shape as the rollback-orphan page leak documented in allocatePageForWrite).
    //
    // The eagerlyInstalledInCache flag tells commitChanges to skip the late
    // readCache.addFile call for this fileChanges entry (a second addFile would throw
    // "File with id ... already exists"). The flag is only set for the truly-fresh
    // path (isNew && readCache instanceof DirectMemoryOnlyDiskCache); deleted-then-readded
    // files reuse the cache's existing entry so no eager call is needed.
    if (isNew && readCache instanceof DirectMemoryOnlyDiskCache) {
      readCache.addFile(fileName, fileId, writeCache, nonDurable);
      fileChanges.eagerlyInstalledInCache = true;
    }

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
    assert this.writeAheadLog == null || this.writeAheadLog == writeAheadLog
        : "commitChanges WAL instance differs from flushPendingOperations WAL instance";
    try {
      LogSequenceNumber txEndLsn;

      // Resolve the mutated components' epochs FIRST — before flushPendingOperations
      // and before any WAL record is written. The mutated set is frozen at entry:
      // flushPendingOperations only assigns changeLSNs to already-registered page
      // overlays (it creates no FileChanges entries and adds no binary changes), and
      // the WAL phase below only prunes page entries WITHOUT changes — so resolving
      // early is result-identical on the success path. What it changes is the failure
      // point: the fail-loud IllegalStateException for an unregistered mutated file
      // (see collectMutatedComponentEpochs) now fires BEFORE the durability point of
      // no return. Resolving after the AtomicUnitEndRecord would leave a torn state on
      // a miss — WAL says committed, the cache apply never ran, and recovery would
      // replay the orphaned unit against later transactions' writes (review finding
      // CS-1).
      final var mutatedComponentEpochs = collectMutatedComponentEpochs();

      // Precompute non-durable classification once per file to guarantee
      // consistent classification between the WAL phase and cache application
      // phase. Reading the volatile nonDurableFileIds twice could see different
      // snapshots if a concurrent addFile/deleteFile publishes between phases.
      final var nonDurableFlags = new Long2BooleanOpenHashMap(fileChanges.size());
      for (final var entry : fileChanges.long2ObjectEntrySet()) {
        final var fileId = entry.getLongKey();
        nonDurableFlags.put(
            fileId, entry.getValue().nonDurable || writeCache.isNonDurable(fileId));
      }

      // walUnitStarted and startLSN are instance fields (shared with
      // flushPendingOperations). If flushPendingOperations was called before
      // commitChanges, walUnitStarted may already be true and startLSN set.
      this.operationCommitTs = commitTs;

      // Flush any remaining pending PageOperations that were registered outside
      // executeInsideComponentOperation boundaries (e.g., standalone atomic
      // operations like histogram snapshot flush). In the normal component
      // operation path, hasPendingOperations is false (already flushed at the
      // boundary), so this is a no-op fast-path.
      flushPendingOperations();
      assert !hasPendingOperations
          : "hasPendingOperations still true after flushPendingOperations in commitChanges";

      var deletedFilesIterator = deletedFiles.longIterator();
      while (deletedFilesIterator.hasNext()) {
        final var deletedFileId = deletedFilesIterator.nextLong();
        if (writeCache.isNonDurable(deletedFileId)) {
          continue;
        }
        if (!walUnitStarted) {
          startLSN = emitWalUnitStart(writeAheadLog, commitTs);
          walUnitStarted = true;
        }
        writeAheadLog.log(new FileDeletedWALRecord(operationCommitTs, deletedFileId));
      }

      for (final var fileChangesEntry : fileChanges.long2ObjectEntrySet()) {
        final var fileChanges = fileChangesEntry.getValue();
        final var fileId = fileChangesEntry.getLongKey();
        final boolean nonDurable = nonDurableFlags.get(fileId);

        if (fileChanges.isNew && !nonDurable) {
          if (!walUnitStarted) {
            startLSN = emitWalUnitStart(writeAheadLog, commitTs);
            walUnitStarted = true;
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

          final var filePageChanges = filePageChangesEntry.getValue();

          if (filePageChanges.changes.hasChanges()) {
            final var pageIndex = filePageChangesEntry.getLongKey();

            // All durable page types must register PageOperations, which are
            // flushed to WAL at component boundaries by flushPendingOperations().
            // changeLSN is set by the flush. If it is still null here, a page
            // mutation did not register its PageOperation — fail loud.
            if (filePageChanges.getChangeLSN() == null) {
              throw new StorageException(
                  writeCache.getStorageName(),
                  "Durable page at index " + pageIndex + " in file " + fileId
                      + " has WAL changes but no changeLSN"
                      + " — missing PageOperation registration");
            }
            assert filePageChanges.getPendingOperations().isEmpty()
                : "Pending operations remain after flush for page "
                    + pageIndex + " in file " + fileId;
          } else {
            filePageChangesIterator.remove();
          }
        }
      }

      // Safety invariant: if WAL unit was not started and there were file deletions
      // or file page changes, all affected files must be non-durable. Violation would
      // mean a durable file's changes silently lost WAL protection. Note: operations
      // with loaded-but-unmodified durable files legitimately have walUnitStarted=false
      // because no WAL records are needed when no changes exist.
      assert walUnitStarted || deletedFiles.isEmpty()
          || !deletedFiles.longStream().anyMatch(
              fId -> !writeCache.isNonDurable(fId))
          : "WAL unit not started but operation deletes durable files";

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

      // Apply-phase epoch bracket (per component, YTDB-1203): exactly ONE enter/exit
      // pair per MUTATED COMPONENT, spanning the entire shared-cache mutation section —
      // the readCache.deleteFile loop plus the whole per-file apply loop
      // (addFile/truncateFile/loadOrAddForWrite/releaseFromWrite). Pages are applied one
      // at a time in hash order, so a concurrent optimistic reader overlapping this
      // section could see a mix of pre- and post-commit pages with every per-page stamp
      // still valid; the epoch lets it detect the overlap and fall back to the pinned
      // path. Epochs are per top-level component, so this commit only invalidates
      // optimistic reads of the components it actually mutates — readers of unrelated
      // components are untouched. The exits MUST be in a finally block: an exception
      // escaping this section must not leave any epoch permanently "in apply", which
      // would disable optimistic reads of that component for the storage's lifetime.
      // Rolled-back operations never reach commitChanges (see the gate in
      // AtomicOperationsManager.endAtomicOperation), so rollback bumps no epoch. The WAL
      // phase and snapshot-buffer flushes above are deliberately NOT bracketed — they do
      // not mutate shared cache pages (snapshot-index entries are SI-filtered on read).
      //
      // Zero-change commits — read-only atomic operations, pure metadata ops — resolve
      // an empty mutated set (computed at method entry, before the WAL phase), perform
      // no readCache calls in the section below, and bump nothing; bumping would only
      // spuriously invalidate concurrently overlapping optimistic reads.
      for (final var epoch : mutatedComponentEpochs) {
        epoch.enterApplyPhase();
      }
      try {
        deletedFilesIterator = deletedFiles.longIterator();
        while (deletedFilesIterator.hasNext()) {
          var deletedFileId = deletedFilesIterator.nextLong();
          readCache.deleteFile(deletedFileId, writeCache);
        }

        for (final var fileChangesEntry : fileChanges.long2ObjectEntrySet()) {
          final var fileChanges = fileChangesEntry.getValue();
          final var fileId = fileChangesEntry.getLongKey();
          final boolean nonDurable = nonDurableFlags.get(fileId);

          if (fileChanges.isNew) {
            // On the in-memory engine, addFile already eagerly registered this fileId with
            // readCache so that fresh-booked-file pages could take the eager-install branch
            // in allocatePageForWrite (see AtomicOperationBinaryTracking.addFile). A second
            // readCache.addFile call would throw "File with id ... already exists" from
            // DirectMemoryOnlyDiskCache.addFile.
            if (!fileChanges.eagerlyInstalledInCache) {
              readCache.addFile(
                  fileChanges.fileName,
                  newFileNamesId.getLong(fileChanges.fileName),
                  writeCache,
                  nonDurable);
            }
          } else if (fileChanges.truncate) {
            LogManager.instance()
                .warn(
                    this,
                    "You performing truncate operation which is considered unsafe because can not"
                        + " be rolled back, as result data can be incorrectly restored after"
                        + " crash, this operation is not recommended to be used");
            readCache.truncateFile(fileId, writeCache);
          }

          // Non-durable files use null startLSN — no WAL dependency exists,
          // and updateDirtyPagesTable (Track 4) skips them regardless.
          final var fileStartLSN = nonDurable ? null : startLSN;

          // Snapshot the test hook once per file so a concurrent (test-side) uninstall
          // cannot switch paths mid-file. Null in production — the fast path below is
          // byte-for-byte the pre-seam loop.
          final var hook = pageApplyHook;
          if (hook == null) {
            final Iterator<Long2ObjectMap.Entry<CacheEntryChanges>> filePageChangesIterator =
                fileChanges.pageChangesMap.long2ObjectEntrySet().iterator();
            while (filePageChangesIterator.hasNext()) {
              final var filePageChangesEntry =
                  filePageChangesIterator.next();

              if (filePageChangesEntry.getValue().changes.hasChanges()) {
                applyPageChangesToCache(
                    fileId,
                    filePageChangesEntry.getLongKey(),
                    filePageChangesEntry.getValue(),
                    nonDurable,
                    fileStartLSN,
                    txEndLsn);
              } else {
                filePageChangesIterator.remove();
              }
            }
          } else {
            applyPageChangesWithHook(hook, fileId, fileChanges, nonDurable, fileStartLSN,
                txEndLsn);
          }
        }
      } finally {
        // Indexed loop on purpose: no Iterator allocation inside this finally, so the
        // bracket exit cannot itself fail under allocation pressure while unwinding
        // (review finding CS-3). exitApplyPhase() never throws.
        for (var i = 0; i < mutatedComponentEpochs.size(); i++) {
          mutatedComponentEpochs.get(i).exitApplyPhase();
        }
      }

      return txEndLsn;
    } finally {
      active = false;
    }
  }

  /**
   * Applies one page's accumulated binary changes to the shared read cache. Extracted
   * from the commitChanges apply loop so the production iterator path and the test-only
   * hook-ordered path ({@link #applyPageChangesWithHook}) share the exact same per-page
   * logic. Must only be called inside the apply-phase epoch bracket.
   */
  private void applyPageChangesToCache(
      final long fileId,
      final long pageIndex,
      final CacheEntryChanges filePageChanges,
      final boolean nonDurable,
      @Nullable final LogSequenceNumber fileStartLSN,
      @Nullable final LogSequenceNumber txEndLsn) throws IOException {
    final var cacheEntry =
        readCache.loadOrAddForWrite(
            fileId, pageIndex, writeCache, filePageChanges.verifyCheckSum, fileStartLSN);
    // loadOrAddForWrite is total on both engines at this point:
    //   - Disk engine (LockFreeReadCache + WOWCache): WriteCache.loadOrAdd gap-fills
    //     intermediate pages and returns a usable entry for the requested pageIndex.
    //   - In-memory engine (DirectMemoryOnlyDiskCache): allocatePageForWrite eagerly
    //     installs the page in MemoryFile during the TX, so the commit-time
    //     loadOrAddForWrite reads the page back via MemoryFile.loadPage.
    // Of the four sibling loadOrAddForWrite sites, this one is the only reachable
    // site on the in-memory engine (the AbstractStorage WAL-replay branches and
    // DiskStorage incremental-backup restore are disk-only because
    // MemoryWriteAheadLog is a no-op). So an assertion is not sufficient here:
    // any future extension or test caller that bypasses allocatePageForWrite on
    // the in-memory engine would surface a null return only in -ea test builds.
    // Throw unconditionally so the violation is visible in production builds too.
    if (cacheEntry == null) {
      throw new IllegalStateException(
          "readCache.loadOrAddForWrite returned null in commitChanges for fileId="
              + fileId + " pageIndex=" + pageIndex
              + " isNew=" + filePageChanges.isNew
              + " (in-memory engine: eager-install in allocatePageForWrite must"
              + " have run; disk engine: WriteCache.loadOrAdd is total);"
              + " WriteCache.loadOrAdd totality contract violated");
    }

    try {
      final var durablePage = new DurablePage(cacheEntry);

      durablePage.restoreChanges(filePageChanges.changes);

      // Non-durable pages have no WAL records, so endLSN and changeLSN
      // are not meaningful. The dirty-pages-table skip in
      // updateDirtyPagesTable (Track 4) ensures these pages
      // don't block WAL truncation.
      if (!nonDurable) {
        // flushPendingOperations sets changeLSN for all durable pages.
        // The WAL phase above throws StorageException if changeLSN is
        // null for a durable page with changes — this assert is a
        // defense-in-depth double-check.
        assert filePageChanges.getChangeLSN() != null
            : "Durable page must have changeLSN set for page "
                + cacheEntry.getPageIndex();
        cacheEntry.setEndLSN(txEndLsn);
        durablePage.setLsn(filePageChanges.getChangeLSN());
      }
    } finally {
      readCache.releaseFromWrite(cacheEntry, writeCache, true);
    }
  }

  /**
   * Resolves the apply-phase epochs of every component this commit will mutate in the
   * shared read-cache apply section: the owners of all deleted files plus of every file
   * with a new/truncate flag or at least one page with accumulated changes — exactly the
   * set of files the apply section acts on. Merely-loaded files (empty
   * {@link FileChanges}) resolve nothing, so zero-change commits (read-only atomic
   * operations) return an empty list and skip the epoch bracket entirely.
   *
   * <p>Invoked at the very top of {@link #commitChanges} — before
   * {@code flushPendingOperations} and before any WAL record is written — so the
   * fail-loud miss below aborts the commit cleanly instead of firing after the
   * {@code AtomicUnitEndRecord} durability point (which would produce a
   * recovery-visible torn state: WAL committed, cache never applied). The early call is
   * safe because the mutated set is frozen at {@code commitChanges} entry.
   *
   * <p>Epochs are deduplicated BY IDENTITY: sub-components share their parent
   * component's epoch instance, and one commit frequently touches several files of the
   * same component family, which must produce exactly one enter/exit pair on that epoch.
   *
   * <p>Fail-loud contract (review finding AR-2): a mutated fileId with no registry entry
   * throws {@link IllegalStateException}. Every production file is registered by the
   * {@code StorageComponent.addFile/openFile} funnel before it can be mutated, so a miss
   * means a file was created or loaded behind the funnel's back — its optimistic readers
   * would silently lose epoch protection if the bump were skipped. Operations built via
   * the standalone convenience constructor cannot miss: their private
   * {@link ComponentEpochRegistry#uniform} registry resolves every fileId to one
   * universal epoch. Tests using the primary constructor must register an epoch for
   * every fileId they mutate, exactly as the production funnel does.
   */
  private ArrayList<ApplyPhaseEpoch> collectMutatedComponentEpochs() {
    final var epochs = new ArrayList<ApplyPhaseEpoch>();
    final var deletedFilesIterator = deletedFiles.longIterator();
    while (deletedFilesIterator.hasNext()) {
      addMutatedComponentEpoch(epochs, deletedFilesIterator.nextLong());
    }
    for (final var fileChangesEntry : fileChanges.long2ObjectEntrySet()) {
      final var changes = fileChangesEntry.getValue();
      if (changes.isNew || changes.truncate || anyPageHasChanges(changes)) {
        addMutatedComponentEpoch(epochs, fileChangesEntry.getLongKey());
      }
    }
    return epochs;
  }

  /**
   * Returns whether any page of the given file carries accumulated binary changes — the
   * per-page half of the mutated-file predicate in {@link #collectMutatedComponentEpochs}
   * (the apply loop applies exactly the pages for which this holds).
   */
  private static boolean anyPageHasChanges(final FileChanges changes) {
    for (final var pageEntry : changes.pageChangesMap.long2ObjectEntrySet()) {
      if (pageEntry.getValue().changes.hasChanges()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Resolves {@code fileId} to its component epoch and appends it to {@code epochs}
   * unless the identical instance is already present. See
   * {@link #collectMutatedComponentEpochs} for the fail-loud contract on misses.
   */
  private void addMutatedComponentEpoch(
      final ArrayList<ApplyPhaseEpoch> epochs, final long fileId) {
    final var epoch = componentEpochRegistry.epochFor(fileId);
    if (epoch == null) {
      throw new IllegalStateException(
          "No component apply-phase epoch registered for mutated file " + fileId
              + " in storage " + writeCache.getStorageName()
              + " — the file was created or loaded outside the"
              + " StorageComponent.addFile/openFile funnel, so optimistic readers of its"
              + " pages would not be protected against this commit's apply phase");
    }
    // Identity-based dedupe: sub-components share the parent's epoch INSTANCE, and the
    // mutated set is small (typically 1-3 components per commit), so a linear reference
    // scan beats a hash-based identity set.
    for (final var existing : epochs) {
      if (existing == epoch) {
        return;
      }
    }
    epochs.add(epoch);
  }

  /**
   * TEST-ONLY variant of the per-file page-apply loop, active only when a
   * {@link PageApplyHook} is installed. Applies the file's changed pages in the order
   * chosen by {@link PageApplyHook#orderPageApplications} (default: the map's native
   * order) and invokes {@link PageApplyHook#beforePageApply} before each application.
   * Runs inside the apply-phase epoch bracket, so a hook that throws still triggers the
   * bracket's finally-exit (the epoch can never be left permanently "in apply").
   */
  private void applyPageChangesWithHook(
      final PageApplyHook hook,
      final long fileId,
      final FileChanges fileChanges,
      final boolean nonDurable,
      @Nullable final LogSequenceNumber fileStartLSN,
      @Nullable final LogSequenceNumber txEndLsn) throws IOException {
    var pageIndexes = fileChanges.pageChangesMap.keySet().toLongArray();
    final var reordered = hook.orderPageApplications(fileId, pageIndexes.clone());
    if (reordered != null) {
      // Enforce the "complete permutation" contract: an unknown
      // index would apply nothing for a real page, a duplicate would double-apply,
      // and an omission would silently drop a WAL-committed page's changes from the
      // cache — all of which would silently weaken the tests using this seam.
      final var expected = new LongOpenHashSet(pageIndexes);
      final var seen = new LongOpenHashSet(reordered.length);
      for (final var pageIndex : reordered) {
        if (!expected.contains(pageIndex)) {
          throw new IllegalStateException(
              "PageApplyHook.orderPageApplications returned unknown page index " + pageIndex
                  + " for file " + fileId);
        }
        if (!seen.add(pageIndex)) {
          throw new IllegalStateException(
              "PageApplyHook.orderPageApplications returned duplicate page index " + pageIndex
                  + " for file " + fileId);
        }
      }
      if (reordered.length != pageIndexes.length) {
        throw new IllegalStateException(
            "PageApplyHook.orderPageApplications returned an incomplete permutation for file "
                + fileId + ": expected " + pageIndexes.length + " pages, got "
                + reordered.length);
      }
      pageIndexes = reordered;
    }

    for (final var pageIndex : pageIndexes) {
      final var filePageChanges = fileChanges.pageChangesMap.get(pageIndex);
      // The permutation validation above (and keySet origin on the default path)
      // guarantees every index resolves; this is a defensive invariant only.
      assert filePageChanges != null
          : "page changes vanished for page index " + pageIndex + " in file " + fileId;

      if (filePageChanges.changes.hasChanges()) {
        hook.beforePageApply(fileId, pageIndex);
        applyPageChangesToCache(fileId, pageIndex, filePageChanges, nonDurable, fileStartLSN,
            txEndLsn);
      } else {
        fileChanges.pageChangesMap.remove(pageIndex);
      }
    }
  }

  /**
   * Installs the TEST-ONLY page-apply hook (see {@link PageApplyHook}). Must never be
   * called from production code — the hook exists solely so tests can deterministically
   * order and pause the commit-time page-apply loop inside the apply-phase epoch bracket.
   */
  void setPageApplyHook(@Nullable final PageApplyHook hook) {
    this.pageApplyHook = hook;
  }

  /**
   * TEST-ONLY seam into the commit-time page-apply loop of {@link #commitChanges}
   * (YTDB-1178). The page-apply order is normally fastutil hash order, which makes
   * mixed-state races (a reader overlapping a partially applied commit) practically
   * impossible to reproduce deterministically. This hook lets a test (a) dictate the
   * order in which a file's changed pages are applied and (b) block at a barrier between
   * two page applications — while the writer is inside the apply-phase epoch bracket and
   * still holds its component exclusive locks — so a concurrent reader can be run
   * deterministically against the mixed state.
   *
   * <p>Production impact: a single null check per file in the apply loop; the hook field
   * is always null outside tests.
   */
  interface PageApplyHook {

    /**
     * Returns the order in which the given changed pages of {@code fileId} should be
     * applied, or {@code null} to keep the default (map iteration) order. The returned
     * array must be a COMPLETE permutation of {@code pageIndexes}: unknown indexes,
     * duplicates, and omissions all fail the commit with
     * {@link IllegalStateException} (an omission would otherwise silently drop a
     * WAL-committed page's changes from the cache).
     */
    @Nullable default long[] orderPageApplications(final long fileId, final long[] pageIndexes) {
      return null;
    }

    /**
     * Called immediately before the changes for {@code (fileId, pageIndex)} are applied
     * to the shared read cache. May block (e.g., on a barrier) to pause the writer
     * mid-apply between two page applications. Runs inside the apply-phase epoch
     * bracket; a thrown exception aborts the commit but still passes through the
     * bracket's finally-exit.
     */
    default void beforePageApply(final long fileId, final long pageIndex) {
    }
  }

  /**
   * Emits the WAL atomic operation start record and returns the startLSN
   * (captured immediately after the start record, pointing into the same
   * segment).
   */
  private static LogSequenceNumber emitWalUnitStart(
      WriteAheadLog writeAheadLog, long commitTs) throws IOException {
    writeAheadLog.logAtomicOperationStartRecord(true, commitTs);
    return writeAheadLog.end();
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
  @Nullable public IndexCountDeltaHolder getIndexCountDeltas() {
    return indexCountDeltas;
  }

  @Override
  @Nonnull
  public IndexCountDeltaHolder getOrCreateIndexCountDeltas() {
    if (indexCountDeltas == null) {
      indexCountDeltas = new IndexCountDeltaHolder();
    }
    return indexCountDeltas;
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

  // Lightweight entry linking a page's pending operations to its parent file
  // identity, used by flushPendingOperations() to avoid a full scan of all
  // files and pages.
  private record PendingFlushEntry(
      long fileId, boolean nonDurable, CacheEntryChanges changes) {
  }

  private static final class FileChanges {

    private final Long2ObjectOpenHashMap<CacheEntryChanges> pageChangesMap =
        new Long2ObjectOpenHashMap<>();
    private long maxNewPageIndex = -2;
    private boolean isNew;
    private boolean truncate;
    private boolean nonDurable;
    private String fileName;
    /**
     * In-memory engine only: {@code true} when {@link AtomicOperationBinaryTracking#addFile}
     * already called {@code readCache.addFile} for this fileId so that fresh-booked-file
     * pages can take the eager-install branch in {@code allocatePageForWrite}. When set,
     * {@code commitChanges} must skip its late {@code readCache.addFile} call to avoid a
     * duplicate-registration error from {@code DirectMemoryOnlyDiskCache}. Always
     * {@code false} on the disk engine.
     */
    private boolean eagerlyInstalledInCache;
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
