package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import com.jetbrains.youtrackdb.internal.core.index.engine.HistogramDeltaHolder;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.FileHandler;
import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPositionMapBucket.PositionEntry;
import com.jetbrains.youtrackdb.internal.core.storage.collection.SnapshotKey;
import com.jetbrains.youtrackdb.internal.core.storage.collection.VisibilityKey;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsTable.AtomicOperationsSnapshot;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurableComponent;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.EdgeSnapshotKey;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.EdgeVisibilityKey;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.LinkBagValue;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.io.IOException;
import java.util.Map;
import javax.annotation.Nonnull;

public interface AtomicOperation {

  @Nonnull
  AtomicOperationsSnapshot getAtomicOperationsSnapshot();

  void deactivate();

  long getCommitTs();

  long getCommitTsUnsafe();

  void startToApplyOperations(long commitTs);

  CacheEntry loadPageForWrite(FileHandler fileHandler, long pageIndex, int pageCount,
      boolean verifyChecksum)
      throws IOException;

  CacheEntry loadPageForRead(FileHandler fileHandler, long pageIndex) throws IOException;

  void addMetadata(AtomicOperationMetadata<?> metadata);

  AtomicOperationMetadata<?> getMetadata(String key);

  CacheEntry addPage(FileHandler fileHandler) throws IOException;

  void releasePageFromRead(CacheEntry cacheEntry);

  void releasePageFromWrite(CacheEntry cacheEntry) throws IOException;

  long filledUpTo(FileHandler fileHandler);

  FileHandler addFile(String fileName) throws IOException;

  FileHandler loadFile(String fileName) throws IOException;

  void deleteFile(long fileId) throws IOException;

  boolean isFileExists(String fileName);

  long fileIdByName(String name);

  void truncateFile(FileHandler fileHandler) throws IOException;

  boolean containsInLockedObjects(String lockName);

  void addLockedObject(String lockName);

  void rollbackInProgress();

  boolean isRollbackInProgress();

  LogSequenceNumber commitChanges(long commitTs, WriteAheadLog writeAheadLog) throws IOException;

  Iterable<String> lockedObjects();

  /** Tracks a locked DurableComponent so it can be released at operation end. */
  void addLockedComponent(DurableComponent component);

  /** Returns the DurableComponents locked by this operation. */
  Iterable<DurableComponent> lockedComponents();

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

  @SuppressWarnings("unused")
  boolean isActive();
}
