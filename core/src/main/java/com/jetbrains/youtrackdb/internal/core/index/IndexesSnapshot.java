package com.jetbrains.youtrackdb.internal.core.index;

import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.SnapshotMarkerRID;
import com.jetbrains.youtrackdb.internal.core.id.TombstoneRID;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Per-index in-memory snapshot store for snapshot isolation. Preserves historical
 * versions of index entries so that concurrent readers see a consistent point-in-time
 * view regardless of concurrent writes.
 *
 * <h3>Data structures</h3>
 * <ul>
 *   <li>{@code indexesSnapshot} — a sub-map view of the global
 *       {@code ConcurrentSkipListMap<CompositeKey, RID>}, scoped to this index's
 *       entries by the {@code [indexId, indexId+1)} key range. Stores pairs of
 *       entries per version change: a {@link TombstoneRID} at the old version and
 *       a plain {@link com.jetbrains.youtrackdb.internal.core.id.RecordId} guard
 *       at the new version.</li>
 *   <li>{@code visibilityIndex} — maps newVersion keys to oldVersion keys for
 *       LWM-based eviction. Keyed by the RecordId guard entry's key (newVersion)
 *       so that eviction can process entries in commit-timestamp order.</li>
 * </ul>
 *
 * <h3>Key layout</h3>
 * <p>Snapshot keys have the layout {@code [indexId, userKey..., version]}, where
 * {@code indexId} is prepended by {@link #enhanceIndexId} to namespace entries
 * across indexes in the shared global map.
 *
 * <h3>Visibility rules</h3>
 * <p>{@link #checkVisibility} implements the visibility decision: entries from
 * in-progress or phantom (future) transactions fall back to
 * {@link #lookupSnapshotRid} for historical state; committed entries are returned
 * directly (with {@link SnapshotMarkerRID} unwrapped to its identity).
 *
 * <h3>Thread safety</h3>
 * <p>All operations are safe for concurrent use. The underlying
 * {@code ConcurrentSkipListMap} provides thread-safe reads and writes.
 * {@link #addSnapshotPair} writes are not atomic (two puts), but
 * {@link #lookupSnapshotRid} handles partial state via prefix validation.
 */
public class IndexesSnapshot {

  private final NavigableMap<CompositeKey, RID> indexesSnapshot;
  private final NavigableMap<CompositeKey, CompositeKey> visibilityIndex;

  // Approximate entry count shared with AbstractStorage for O(1) cleanup threshold
  // checks. Same pattern as snapshotIndexSize / edgeSnapshotIndexSize: the counter
  // is owned by AbstractStorage, passed here for incrementing on addSnapshotPair(),
  // and passed to evictStaleIndexesSnapshotEntries() for decrementing on eviction.
  // Incremented by 2 per addSnapshotPair call (one TombstoneRID + one RecordId guard).
  @Nonnull
  private final AtomicLong snapshotSizeCounter;

  private final long indexId;

  public IndexesSnapshot(NavigableMap<CompositeKey, RID> indexesSnapshot,
      NavigableMap<CompositeKey, CompositeKey> visibilityIndex,
      @Nonnull AtomicLong snapshotSizeCounter, long indexId) {
    this.indexesSnapshot = indexesSnapshot.subMap(
        new CompositeKey(indexId), true, new CompositeKey(indexId + 1), false);
    this.visibilityIndex = visibilityIndex;
    this.snapshotSizeCounter = snapshotSizeCounter;
    this.indexId = indexId;
  }

  public void addSnapshotPair(CompositeKey addedKey, CompositeKey removedKey, RID value) {
    assert !(value instanceof SnapshotMarkerRID)
        : "addSnapshotPair value must be a plain RID, not SnapshotMarkerRID: " + value;
    assert !(value instanceof TombstoneRID)
        : "addSnapshotPair value must be a plain RID, not TombstoneRID: " + value;
    var enhancedAddedKey = enhanceIndexId(addedKey);
    var enhancedRemovedKey = enhanceIndexId(removedKey);
    // Write order: TombstoneRID (lower version) first, then RecordId guard
    // (higher version). These two puts are not atomic; a concurrent reader via
    // lowerEntry in lookupSnapshotRid may see partial state.
    //
    // This order: a same-key reader correctly sees "was alive" immediately.
    // A cross-key reader may see a foreign key's TombstoneRID without its
    // RecordId guard — the prefix check in lookupSnapshotRid prevents
    // this from leaking wrong results.
    //
    // Reversed order would be worse: a same-key reader would temporarily see
    // only the RecordId guard, concluding "was removed" — a false negative
    // for a record that was alive at the reader's snapshot.
    //
    // Neither order is safe without the prefix validation in
    // lookupSnapshotRid.
    //
    // Note on concurrent clear(): if clear() runs between the TombstoneRID write
    // (line below) and the visibilityIndex write, clear() will remove the
    // TombstoneRID from indexesSnapshot but the visibilityIndex entry will still be
    // written. This orphaned visibilityIndex entry is benign: eviction will attempt
    // to remove a non-existent key from indexesSnapshot (ConcurrentSkipListMap.remove
    // is idempotent) and decrement the counter (clamped to zero).
    indexesSnapshot.put(enhancedAddedKey, new TombstoneRID(value));
    indexesSnapshot.put(enhancedRemovedKey, value);
    // Key by removedKey (newVersion) so the comparator orders by newVersion.
    // Eviction checks newVersion < LWM — matching the collection/edge eviction
    // pattern where VisibilityKey.recordTs is the committing TX's timestamp.
    visibilityIndex.put(enhancedRemovedKey, enhancedAddedKey);
    // 2 entries added to snapshotData per pair (TombstoneRID + RecordId guard).
    // Matches the decrement in evictStaleIndexesSnapshotEntries.
    snapshotSizeCounter.addAndGet(2);
  }

  public Stream<RawPair<CompositeKey, RID>> visibilityFilter(
      @Nonnull AtomicOperation atomicOperation,
      Stream<RawPair<CompositeKey, RID>> stream) {
    return visibilityFilterMapped(atomicOperation, stream, Function.identity());
  }

  /**
   * Visibility filter that returns only the visible RIDs, skipping key mapping
   * and RawPair allocation. Use this instead of
   * {@code visibilityFilter(...).map(RawPair::second)} when only the RID values
   * are needed.
   */
  public Stream<RID> visibilityFilterValues(
      @Nonnull AtomicOperation atomicOperation,
      Stream<RawPair<CompositeKey, RID>> stream) {
    var opsSnapshot = atomicOperation.getAtomicOperationsSnapshot();
    var snapshotTs = opsSnapshot.snapshotTs();
    var inProgressVersions = opsSnapshot.inProgressTxs();

    return stream
        .mapMulti((pair, downstream) -> {
          var visibleRid = checkVisibility(
              pair.first(), pair.second(), snapshotTs, inProgressVersions);
          if (visibleRid != null) {
            downstream.accept(visibleRid);
          }
        });
  }

  /**
   * Visibility filter that also maps the CompositeKey via {@code keyMapper},
   * fusing filtering and key extraction into a single pass to avoid
   * intermediate RawPair allocations.
   */
  public <K> Stream<RawPair<K, RID>> visibilityFilterMapped(
      @Nonnull AtomicOperation atomicOperation,
      Stream<RawPair<CompositeKey, RID>> stream,
      Function<CompositeKey, K> keyMapper) {
    var opsSnapshot = atomicOperation.getAtomicOperationsSnapshot();
    var snapshotTs = opsSnapshot.snapshotTs();
    var inProgressVersions = opsSnapshot.inProgressTxs();

    // mapMulti instead of flatMap to avoid allocating a Stream object
    // (Stream.of / Stream.empty) per entry during full index scans.
    return stream
        .mapMulti((pair, downstream) -> {
          var visibleRid = checkVisibility(
              pair.first(), pair.second(), snapshotTs, inProgressVersions);
          if (visibleRid != null) {
            downstream.accept(new RawPair<>(keyMapper.apply(pair.first()), visibleRid));
          }
        });
  }

  /**
   * Checks the visibility of a single B-tree entry, encapsulating the full visibility
   * decision: in-progress check, phantom check, and committed-visible check with
   * snapshot fallback.
   *
   * <p>Visibility rules match {@code AtomicOperationsSnapshot.isEntryVisible()}:
   * <ul>
   *   <li>{@code version > snapshotTs} → phantom (truly future, not yet registered)
   *   <li>{@code version} in {@code inProgressVersions} → concurrent uncommitted TX
   *   <li>Otherwise → committed and visible
   * </ul>
   *
   * @param key the composite key from the B-tree entry (userKey..., version)
   * @param rid the RID from the B-tree entry (RecordId, TombstoneRID, or SnapshotMarkerRID)
   * @param snapshotTs the reader's snapshot timestamp (the true upper visibility bound,
   *     matching {@code AtomicOperationsSnapshot.snapshotTs()})
   * @param inProgressVersions set of in-progress transaction versions
   * @return the visible RID, or null if the entry is not visible
   */
  public @Nullable RID checkVisibility(CompositeKey key, RID rid,
      long snapshotTs, LongOpenHashSet inProgressVersions) {
    long version = (Long) key.getKeys().getLast();

    if (!inProgressVersions.isEmpty() && inProgressVersions.contains(version)) {
      // The in-progress TX may have replaced an older committed version that
      // was removed from the B-tree and now only exists in the snapshot.
      // For TombstoneRID/SnapshotMarkerRID, check the snapshot for historical
      // state. For plain RecordId (a new insert with no prior history), skip.
      if (!(rid instanceof RecordId)) {
        return lookupSnapshotRid(key, snapshotTs);
      }
      return null;
    }

    // Phantom: version registered after our snapshot — truly future
    if (version > snapshotTs) {
      if (rid instanceof RecordId) {
        return null;
      }
      // TombstoneRID/SnapshotMarkerRID from a future TX replaced an older
      // committed version — check snapshot for historical state.
      return lookupSnapshotRid(key, snapshotTs);
    }

    // Committed and visible: version <= snapshotTs and not in-progress.
    // Apply RID-type semantics.
    if (rid instanceof SnapshotMarkerRID) {
      return rid.getIdentity();
    } else if (!(rid instanceof TombstoneRID)) {
      return rid;
    }
    return null;
  }

  /**
   * Looks up the snapshot index for a historical version of the entry visible at
   * {@code snapshotTs}. Returns the visible RID if found, null otherwise.
   *
   * @param key the composite key from the B-tree entry (userKey..., version)
   * @param snapshotTs the reader's snapshot timestamp (upper visibility bound)
   * @return the visible RID (always a plain RecordId), or null if no historical
   *     version is visible
   */
  // Package-private for direct unit testing in IndexesSnapshotVisibilityFilterTest.
  @Nullable RID lookupSnapshotRid(CompositeKey key, long snapshotTs) {
    var keys = key.getKeys();
    // Build the search key in one allocation: CompositeKey(indexId, userKey..., snapshotTs+1)
    // instead of three intermediate CompositeKeys.
    // We want entries with version <= snapshotTs (inclusive). Since lowerEntry()
    // returns entries strictly less than the search key, add 1 to make the
    // bound inclusive. Guard against Long.MAX_VALUE overflow — in that case
    // lowerEntry(MAX_VALUE) still finds all entries with version < MAX_VALUE,
    // which is sufficient since MAX_VALUE is a sentinel, not a real version.
    var searchKey = new CompositeKey(keys.size() + 1);
    searchKey.addKeyDirect(indexId);
    for (int i = 0, end = keys.size() - 1; i < end; i++) {
      searchKey.addKeyDirect(keys.get(i));
    }
    long searchVersion = snapshotTs < Long.MAX_VALUE ? snapshotTs + 1 : Long.MAX_VALUE;
    searchKey.addKeyDirect(searchVersion);

    var latestSnapshotEntry = indexesSnapshot.lowerEntry(searchKey);
    if (latestSnapshotEntry != null && latestSnapshotEntry.getValue() instanceof TombstoneRID) {
      var snapshotKeys = latestSnapshotEntry.getKey().getKeys();

      // lowerEntry may return a foreign key's TombstoneRID during the narrow
      // window when addSnapshotPair has written the TombstoneRID but not yet
      // the RecordId guard (see write-order comment in addSnapshotPair).
      if (!snapshotUserKeyMatches(keys, snapshotKeys)) {
        return null;
      }

      return latestSnapshotEntry.getValue().getIdentity();
    }
    return null;
  }

  public Set<Entry<CompositeKey, RID>> allEntries() {
    return indexesSnapshot.entrySet();
  }

  /**
   * Checks whether the snapshot entry's user-key prefix matches the B-tree entry's
   * user-key prefix. The B-tree key layout is {@code [userKey..., version]}, while
   * the snapshot key layout is {@code [indexId, userKey..., version]} — so snapshot
   * elements are offset by 1.
   *
   * <p>Similar to {@code BTree.userKeyPrefixMatches()}, but accounts for the
   * {@code indexId} prefix in snapshot keys.
   *
   * @param btreeKeyElements the B-tree entry's key elements {@code [userKey..., version]}
   * @param snapshotKeyElements the snapshot entry's key elements
   *     {@code [indexId, userKey..., version]}
   * @return true if all user-key elements match
   */
  private static boolean snapshotUserKeyMatches(
      List<?> btreeKeyElements,
      List<?> snapshotKeyElements) {
    int userKeyLen = btreeKeyElements.size() - 1;
    if (snapshotKeyElements.size() - 2 != userKeyLen) {
      return false;
    }
    for (int i = 0; i < userKeyLen; i++) {
      if (!Objects.equals(btreeKeyElements.get(i), snapshotKeyElements.get(i + 1))) {
        return false;
      }
    }
    return true;
  }

  // Prepend indexId without varargs allocation and without the recursive
  // addKey(CompositeKey) path that also checks ChangeableIdentity per element.
  private CompositeKey enhanceIndexId(CompositeKey key) {
    var keys = key.getKeys();
    var result = new CompositeKey(keys.size() + 1);
    result.addKeyDirect(indexId);
    for (var o : keys) {
      result.addKeyDirect(o);
    }
    return result;
  }

  public void clear() {
    // Capture the current upper bound to guarantee termination even when
    // concurrent writers keep adding entries within this submap range.
    // Both entrySet() iteration and SubMap.clear() use weakly-consistent
    // iterators that can see entries inserted ahead of the current position,
    // causing livelock when writers add entries faster than the iterator
    // advances. Using pollFirstEntry() with an upper bound avoids this:
    // each poll atomically removes one entry, and we stop after reaching
    // the key that was last when clear() began.
    var lastEntry = indexesSnapshot.lastEntry();
    if (lastEntry == null) {
      return;
    }
    var upperBound = lastEntry.getKey();

    long entryCount = 0;
    Entry<CompositeKey, RID> entry;
    while ((entry = indexesSnapshot.pollFirstEntry()) != null) {
      entryCount++;
      // Remove the corresponding visibilityIndex entry for RecordId guard
      // entries (non-TombstoneRID). visibilityIndex keys are the removedKey
      // entries (newVersion).
      if (!(entry.getValue() instanceof TombstoneRID)) {
        visibilityIndex.remove(entry.getKey());
      }
      if (entry.getKey().compareTo(upperBound) >= 0) {
        break;
      }
    }
    if (entryCount > 0) {
      // Clamp to zero: concurrent clear() and eviction may both decrement
      // for the same entries (ConcurrentSkipListMap.remove is idempotent
      // but both callers counted entries during their own iteration pass).
      final long delta = entryCount;
      snapshotSizeCounter.updateAndGet(current -> Math.max(0, current - delta));
    }
  }
}
