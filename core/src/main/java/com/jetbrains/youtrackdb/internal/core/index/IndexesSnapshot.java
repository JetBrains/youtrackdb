package com.jetbrains.youtrackdb.internal.core.index;

import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.SnapshotMarkerRID;
import com.jetbrains.youtrackdb.internal.core.id.TombstoneRID;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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
    assert !(value instanceof SnapshotMarkerRID);
    var enhancedAddedKey = enhanceIndexId(addedKey);
    var enhancedRemovedKey = enhanceIndexId(removedKey);
    // Write order: TombstoneRID (lower version) first, then RecordId guard
    // (higher version). These two puts are not atomic; a concurrent reader via
    // lowerEntry in emitSnapshotVisibility may see partial state.
    //
    // This order: a same-key reader correctly sees "was alive" immediately.
    // A cross-key reader may see a foreign key's TombstoneRID without its
    // RecordId guard — the prefix check in emitSnapshotVisibility prevents
    // this from leaking wrong results.
    //
    // Reversed order would be worse: a same-key reader would temporarily see
    // only the RecordId guard, concluding "was removed" — a false negative
    // for a record that was alive at the reader's snapshot.
    //
    // Neither order is safe without the prefix validation in
    // emitSnapshotVisibility.
    indexesSnapshot.put(enhancedAddedKey, new TombstoneRID(value));
    indexesSnapshot.put(enhancedRemovedKey, value);
    visibilityIndex.put(enhancedAddedKey, enhancedRemovedKey);
    // 2 entries added to snapshotData per pair (TombstoneRID + RecordId guard).
    // Matches the decrement in evictStaleIndexesSnapshotEntries.
    snapshotSizeCounter.addAndGet(2);
  }

  public Stream<RawPair<CompositeKey, RID>> visibilityFilter(AtomicOperation atomicOperation,
      Stream<RawPair<CompositeKey, RID>> stream) {
    return visibilityFilterMapped(atomicOperation, stream, Function.identity());
  }

  /**
   * Visibility filter that also maps the CompositeKey via {@code keyMapper},
   * fusing filtering and key extraction into a single pass to avoid
   * intermediate RawPair allocations.
   */
  public <K> Stream<RawPair<K, RID>> visibilityFilterMapped(
      AtomicOperation atomicOperation,
      Stream<RawPair<CompositeKey, RID>> stream,
      Function<CompositeKey, K> keyMapper) {
    long visibleVersion;
    LongOpenHashSet inProgressVersions;
    if (atomicOperation != null) {
      visibleVersion = atomicOperation.getAtomicOperationsSnapshot().maxActiveOperationTs();
      inProgressVersions = atomicOperation.getAtomicOperationsSnapshot().inProgressTxs();
    } else {
      visibleVersion = Long.MAX_VALUE;
      inProgressVersions = LongOpenHashSet.of();
    }

    // mapMulti instead of flatMap to avoid allocating a Stream object
    // (Stream.of / Stream.empty) per entry during full index scans.
    return stream
        .mapMulti((pair, downstream) -> {
          var visibleRid = checkVisibility(
              pair.first(), pair.second(), visibleVersion, inProgressVersions);
          if (visibleRid != null) {
            downstream.accept(new RawPair<>(keyMapper.apply(pair.first()), visibleRid));
          }
        });
  }

  /**
   * Checks the visibility of a single B-tree entry, encapsulating the full visibility
   * decision: in-progress check, committed check, phantom check, and snapshot fallback.
   *
   * @param key the composite key from the B-tree entry (userKey..., version)
   * @param rid the RID from the B-tree entry (RecordId, TombstoneRID, or SnapshotMarkerRID)
   * @param visibleVersion the reader's snapshot version threshold
   * @param inProgressVersions set of in-progress transaction versions
   * @return the visible RID, or null if the entry is not visible
   */
  public @Nullable RID checkVisibility(CompositeKey key, RID rid,
      long visibleVersion, LongOpenHashSet inProgressVersions) {
    long version = (Long) key.getKeys().getLast();

    if (!inProgressVersions.isEmpty() && inProgressVersions.contains(version)) {
      // The in-progress TX may have replaced an older committed version that
      // was removed from the B-tree and now only exists in the snapshot.
      // For TombstoneRID/SnapshotMarkerRID, check the snapshot for historical
      // state. For plain RecordId (a new insert with no prior history), skip.
      if (!(rid instanceof RecordId)) {
        return lookupSnapshotRid(key, visibleVersion);
      }
      return null;
    }

    // Committed before snapshot — visible if alive, hidden if tombstoned
    if (version < visibleVersion) {
      if (rid instanceof SnapshotMarkerRID) {
        return rid.getIdentity();
      } else if (!(rid instanceof TombstoneRID)) {
        return rid;
      }
      return null;
    }

    // version >= visibleVersion — phantom: no historical versions for plain RecordId
    if (rid instanceof RecordId) {
      return null;
    }

    // version >= visibleVersion — TombstoneRID or SnapshotMarkerRID
    // → check snapshot for historical state
    return lookupSnapshotRid(key, visibleVersion);
  }

  /**
   * Looks up the snapshot index for a historical version of the entry visible at
   * {@code visibleVersion}. Returns the visible RID if found, null otherwise.
   *
   * @param key the composite key from the B-tree entry (userKey..., version)
   * @param visibleVersion the reader's snapshot version threshold
   * @return the visible RID (always a plain RecordId), or null if no historical
   *     version is visible
   */
  @Nullable RID lookupSnapshotRid(CompositeKey key, long visibleVersion) {
    var keys = key.getKeys();
    // Build the search key in one allocation: CompositeKey(indexId, userKey..., visibleVersion)
    // instead of three intermediate CompositeKeys.
    var searchKey = new CompositeKey(keys.size() + 1);
    searchKey.addKey(indexId);
    for (int i = 0, end = keys.size() - 1; i < end; i++) {
      searchKey.addKey(keys.get(i));
    }
    searchKey.addKey(visibleVersion);

    var latestSnapshotEntry = indexesSnapshot.lowerEntry(searchKey);
    if (latestSnapshotEntry != null && latestSnapshotEntry.getValue() instanceof TombstoneRID) {
      var snapshotKeys = latestSnapshotEntry.getKey().getKeys();

      // lowerEntry may return a foreign key's TombstoneRID during the narrow
      // window when addSnapshotPair has written the TombstoneRID but not yet
      // the RecordId guard (see write-order comment in addSnapshotPair).
      // Verify the user-key prefix matches (skip indexId at [0] and version
      // at last).
      int userKeyLen = keys.size() - 1;
      if (snapshotKeys.size() - 2 != userKeyLen) {
        return null;
      }
      for (int i = 0; i < userKeyLen; i++) {
        if (!Objects.equals(keys.get(i), snapshotKeys.get(i + 1))) {
          return null;
        }
      }

      return latestSnapshotEntry.getValue().getIdentity();
    }
    return null;
  }

  /**
   * Looks up the snapshot index for a historical version of the entry visible at
   * {@code visibleVersion}. If found, emits it directly to {@code downstream},
   * avoiding Stream.of/Stream.empty allocations.
   */
  <K> void emitSnapshotVisibility(RawPair<CompositeKey, RID> pair,
      long visibleVersion, Function<CompositeKey, K> keyMapper,
      Consumer<RawPair<K, RID>> downstream) {
    var rid = lookupSnapshotRid(pair.first(), visibleVersion);
    if (rid != null) {
      downstream.accept(new RawPair<>(keyMapper.apply(pair.first()), rid));
    }
  }

  public Set<Entry<CompositeKey, RID>> allEntries() {
    return indexesSnapshot.entrySet();
  }

  // Prepend indexId without varargs allocation and without the recursive
  // addKey(CompositeKey) path that also checks ChangeableIdentity per element.
  private CompositeKey enhanceIndexId(CompositeKey key) {
    var keys = key.getKeys();
    var result = new CompositeKey(keys.size() + 1);
    result.addKey(indexId);
    for (var o : keys) {
      result.addKey(o);
    }
    return result;
  }

  public void clear() {
    // Remove versionIndex entries that correspond to this (sub-)snapshot's data.
    // versionIndex keys are the addedKey entries — those stored with TombstoneRID values.
    // Count entries before clearing so the size counter stays consistent.
    long entryCount = 0;
    for (var entry : indexesSnapshot.entrySet()) {
      entryCount++;
      if (entry.getValue() instanceof TombstoneRID) {
        visibilityIndex.remove(entry.getKey());
      }
    }
    indexesSnapshot.clear();
    if (entryCount > 0) {
      snapshotSizeCounter.addAndGet(-entryCount);
    }
  }
}
