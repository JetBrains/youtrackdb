package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import com.jetbrains.youtrackdb.internal.core.index.engine.HistogramDeltaHolder;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexCountDeltaHolder;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.OptimisticReadScope;
import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPositionMapBucket.PositionEntry;
import com.jetbrains.youtrackdb.internal.core.storage.collection.SnapshotKey;
import com.jetbrains.youtrackdb.internal.core.storage.collection.VisibilityKey;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsTable.AtomicOperationsSnapshot;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.StorageComponent;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.EdgeSnapshotKey;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.EdgeVisibilityKey;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.LinkBagValue;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.io.IOException;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface AtomicOperation {

  @Nonnull
  AtomicOperationsSnapshot getAtomicOperationsSnapshot();

  void deactivate();

  long getCommitTs();

  long getCommitTsUnsafe();

  void startToApplyOperations(long commitTs);

  CacheEntry loadPageForWrite(long fileId, long pageIndex, int pageCount, boolean verifyChecksum)
      throws IOException;

  CacheEntry loadPageForRead(long fileId, long pageIndex) throws IOException;

  void addMetadata(AtomicOperationMetadata<?> metadata);

  AtomicOperationMetadata<?> getMetadata(String key);

  /**
   * Allocates a fresh page at the given {@code pageIndex} and registers an in-progress
   * overlay for it, returning a {@code CacheEntry} usable inside this atomic operation.
   * <b>Despite the historical name, this method does NOT load existing pages</b> — callers
   * targeting a {@code pageIndex} below the committed file size raise
   * {@link IllegalStateException}. Use {@link #loadPageForWrite} for existing pages.
   *
   * <p>The caller states the target pageIndex up front (typically computed from the
   * component's logical page count, e.g. {@code entryPoint.pagesSize + 1}). The page is
   * &quot;new&quot; iff the file was booked in this same TX or
   * {@code pageIndex >= writeCache.getFilledUpTo(fileId)}; otherwise the guard fires.
   *
   * <p>If the page is already part of this operation's local page-change overlay (because
   * it was previously loaded for write or allocated earlier in the same TX), the existing
   * {@link CacheEntryChanges} is returned so callers see a stable overlay across repeated
   * accesses inside the same atomic operation. The early-return short-circuits the
   * allocator-only guard for idempotent re-entry.
   *
   * <p>The returned overlay is always stub-shaped: the delegate wraps a
   * {@link com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer} with a
   * {@code null} native pointer. The real cache slot is materialized at commit time inside
   * {@code commitChanges}'s pageChangesMap replay loop, which calls
   * {@code readCache.loadOrAddForWrite} for each new-page entry. This means callers must not
   * read page bytes through the returned entry before commit; the {@code CacheEntryChanges}
   * overlay buffers writes via {@code changes} and the materialised slot picks them up.
   *
   * @param fileId    the file ID; must be open and registered with this operation
   * @param pageIndex zero-based page index to allocate; must be non-negative and target a
   *     page that does not yet exist in the committed file (otherwise
   *     {@link IllegalStateException})
   * @return a {@link CacheEntry} (a {@code CacheEntryChanges} overlay) positioned at the
   *     target page; never {@code null}
   * @throws IOException if the underlying cache primitive fails
   * @throws IllegalStateException if {@code pageIndex} is below the committed file size and
   *     no prior overlay exists for it
   */
  CacheEntry loadOrAddPageForWrite(long fileId, long pageIndex) throws IOException;

  CacheEntry addPage(long fileId) throws IOException;

  void releasePageFromRead(CacheEntry cacheEntry);

  void releasePageFromWrite(CacheEntry cacheEntry) throws IOException;

  long filledUpTo(long fileId);

  /**
   * Registers a new file with an explicit non-durability flag.
   *
   * @param fileName    the file name to register
   * @param nonDurable  if true, the file is non-durable (no WAL, no DWL, no fsync)
   * @return the file ID assigned to the new file
   */
  long addFile(String fileName, boolean nonDurable) throws IOException;

  /**
   * Registers a new durable file. Delegates to {@link #addFile(String, boolean)} with
   * {@code nonDurable = false}.
   */
  default long addFile(String fileName) throws IOException {
    return addFile(fileName, false);
  }

  long loadFile(String fileName) throws IOException;

  void deleteFile(long fileId) throws IOException;

  boolean isFileExists(String fileName);

  long fileIdByName(String name);

  void truncateFile(long fileId) throws IOException;

  boolean containsInLockedObjects(String lockName);

  void addLockedObject(String lockName);

  void rollbackInProgress();

  boolean isRollbackInProgress();

  LogSequenceNumber commitChanges(long commitTs, WriteAheadLog writeAheadLog) throws IOException;

  Iterable<String> lockedObjects();

  /** Tracks a locked StorageComponent so it can be released at operation end. */
  void addLockedComponent(StorageComponent component);

  /** Returns the StorageComponents locked by this operation. */
  Iterable<StorageComponent> lockedComponents();

  void addDeletedRecordPosition(final int collectionId, final int pageIndex,
      final int recordPosition);

  IntSet getBookedRecordPositions(final int collectionId, final int pageIndex);

  /**
   * Buffers a snapshot entry locally. The entry is flushed to the shared snapshot index
   * during {@link #commitChanges(long, WriteAheadLog)}, before page changes are applied.
   */
  void putSnapshotEntry(SnapshotKey key, PositionEntry value);

  /**
   * Retrieves a snapshot entry, checking the local buffer first, then the shared index.
   *
   * @return the entry, or {@code null} if not found in either layer
   */
  PositionEntry getSnapshotEntry(SnapshotKey key);

  /**
   * Returns entries from both the local buffer and the shared snapshot index in the range
   * {@code [fromInclusive, toInclusive]} in <b>descending</b> key order. Local buffer
   * entries shadow shared entries with the same key. The returned iterable is lazy — shared
   * map entries are not copied.
   */
  Iterable<Map.Entry<SnapshotKey, PositionEntry>> snapshotSubMapDescending(
      SnapshotKey fromInclusive, SnapshotKey toInclusive);

  /**
   * Buffers a visibility entry locally. The entry is flushed to the shared visibility index
   * during {@link #commitChanges(long, WriteAheadLog)}, before page changes are applied.
   */
  void putVisibilityEntry(VisibilityKey key, SnapshotKey value);

  /**
   * Checks whether a visibility entry exists, checking the local buffer first, then the
   * shared index.
   */
  boolean containsVisibilityEntry(VisibilityKey key);

  /**
   * Returns the histogram delta holder for this transaction, or {@code null}
   * if no histogram operations have occurred in this transaction yet.
   */
  @javax.annotation.Nullable HistogramDeltaHolder getHistogramDeltas();

  /**
   * Returns the histogram delta holder for this transaction, creating it
   * lazily if absent. Used by {@code IndexHistogramManager.onPut/onRemove}
   * to accumulate per-engine deltas within the transaction scope.
   */
  @Nonnull
  HistogramDeltaHolder getOrCreateHistogramDeltas();

  /**
   * Returns the index count delta holder for this transaction, or
   * {@code null} if no index count operations have occurred yet.
   */
  @Nullable IndexCountDeltaHolder getIndexCountDeltas();

  /**
   * Returns the index count delta holder for this transaction, creating it
   * lazily if absent. Used by index engine put/remove methods to accumulate
   * per-engine count deltas within the transaction scope.
   */
  @Nonnull
  IndexCountDeltaHolder getOrCreateIndexCountDeltas();

  // --- Edge snapshot methods (parallel to collection snapshot methods above) ---

  /**
   * Buffers an edge snapshot entry locally. The entry is flushed to the shared edge snapshot
   * index during {@link #commitChanges(long, WriteAheadLog)}, before page changes are applied.
   */
  void putEdgeSnapshotEntry(EdgeSnapshotKey key, LinkBagValue value);

  /**
   * Retrieves an edge snapshot entry, checking the local buffer first, then the shared index.
   *
   * @return the entry, or {@code null} if not found in either layer
   */
  LinkBagValue getEdgeSnapshotEntry(EdgeSnapshotKey key);

  /**
   * Returns edge snapshot entries from both the local buffer and the shared edge snapshot index
   * in the range {@code [fromInclusive, toInclusive]} in <b>descending</b> key order. Local
   * buffer entries shadow shared entries with the same key. The returned iterable is lazy.
   */
  Iterable<Map.Entry<EdgeSnapshotKey, LinkBagValue>> edgeSnapshotSubMapDescending(
      EdgeSnapshotKey fromInclusive, EdgeSnapshotKey toInclusive);

  /**
   * Buffers an edge visibility entry locally. The entry is flushed to the shared edge
   * visibility index during {@link #commitChanges(long, WriteAheadLog)}.
   */
  void putEdgeVisibilityEntry(EdgeVisibilityKey key, EdgeSnapshotKey value);

  /**
   * Checks whether an edge visibility entry exists, checking the local buffer first, then the
   * shared index.
   */
  boolean containsEdgeVisibilityEntry(EdgeVisibilityKey key);

  /**
   * Registers a logical page operation with this atomic operation. The operation is accumulated
   * in the page's {@link CacheEntryChanges} and flushed to WAL at component operation boundaries.
   * The page must already be loaded for write (CacheEntryChanges must exist).
   *
   * <p>Default no-op for non-tracking implementations (e.g., read-only operations).
   */
  default void registerPageOperation(long fileId, long pageIndex, PageOperation op) {
    // no-op by default
  }

  /**
   * Flushes all pending logical page operations to the WAL. Called at component operation
   * boundaries by {@code AtomicOperationsManager.executeInsideComponentOperation()}.
   *
   * <p>Default no-op for non-tracking implementations.
   */
  default void flushPendingOperations() throws IOException {
    // no-op by default
  }

  @SuppressWarnings("unused")
  boolean isActive();

  /**
   * Returns whether this atomic operation has uncommitted WAL changes for the given page.
   * Used by the optimistic read path to force a fallback to the CAS-pinned path when
   * the current transaction has in-progress modifications for the requested page,
   * ensuring optimistic reads never return stale committed data.
   *
   * @param fileId    the file ID
   * @param pageIndex the page index within the file
   * @return true if this operation has local changes for the page
   */
  boolean hasChangesForPage(long fileId, long pageIndex);

  /**
   * Returns the optimistic read scope for accumulating page stamps during multi-page
   * optimistic reads. The scope is reused across attempts within the same operation.
   *
   * <p>Default implementation throws UnsupportedOperationException as a defensive guard
   * for any external implementations that don't support optimistic reads.
   */
  default OptimisticReadScope getOptimisticReadScope() {
    throw new UnsupportedOperationException(
        "Optimistic read scope not supported by " + getClass().getSimpleName());
  }
}
