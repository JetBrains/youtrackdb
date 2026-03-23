package com.jetbrains.youtrackdb.internal.core.index;

import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.SnapshotMarkerRID;
import com.jetbrains.youtrackdb.internal.core.id.TombstoneRID;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Comparator;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.Function;
import java.util.stream.Stream;

public class IndexesSnapshot {

  // Version is always the last key element
  // Fall back to natural CompositeKey ordering for uniqueness
  private static final Comparator<CompositeKey> VERSION_COMPARATOR =
      Comparator.comparingLong((CompositeKey a) -> (Long) a.getKeys().getLast())
          .thenComparing(Function.identity());

  private final NavigableMap<CompositeKey, RID> indexesSnapshot;
  private final NavigableMap<CompositeKey, CompositeKey> versionIndex =
      new ConcurrentSkipListMap<>(VERSION_COMPARATOR);
  private long indexId = -1;

  public IndexesSnapshot() {
    indexesSnapshot = new ConcurrentSkipListMap<>();
  }

  private IndexesSnapshot(NavigableMap<CompositeKey, RID> subIndexesSnapshot, long indexId) {
    indexesSnapshot = subIndexesSnapshot;
    this.indexId = indexId;
  }

  public IndexesSnapshot subIndexSnapshot(long indexId) {
    return new IndexesSnapshot(
        indexesSnapshot.subMap(new CompositeKey(indexId), true,
            new CompositeKey(indexId + 1), false),
        indexId);
  }

  public void addSnapshotPair(CompositeKey addedKey, CompositeKey removedKey, RID value) {
    assert !(value instanceof SnapshotMarkerRID);
    var enhancedAddedKey = enhanceIndexId(addedKey);
    var enhancedRemovedKey = enhanceIndexId(removedKey);
    indexesSnapshot.put(enhancedAddedKey, new TombstoneRID(value));
    indexesSnapshot.put(enhancedRemovedKey, value);
    versionIndex.put(enhancedAddedKey, enhancedRemovedKey);
  }

  public Stream<RawPair<CompositeKey, RID>> visibilityFilter(AtomicOperation atomicOperation,
      Stream<RawPair<CompositeKey, RID>> stream) {
    long visibleVersion;
    LongOpenHashSet inProgressVersions;
    if (atomicOperation != null) {
      visibleVersion = atomicOperation.getAtomicOperationsSnapshot().maxActiveOperationTs();
      inProgressVersions = atomicOperation.getAtomicOperationsSnapshot().inProgressTxs();
    } else {
      visibleVersion = Long.MAX_VALUE;
      inProgressVersions = LongOpenHashSet.of();
    }

    return stream
        // mapMulti instead of flatMap to avoid allocating a Stream object
        // (Stream.of / Stream.empty) per entry during full index scans.
        .<RawPair<CompositeKey, RID>>mapMulti((pair, downstream) -> {
          long version = (Long) pair.first().getKeys().getLast();
          var rid = pair.second();

          // Committed before snapshot — visible if alive, hidden if tombstoned
          if (version < visibleVersion) {
            if (rid instanceof SnapshotMarkerRID) {
              downstream.accept(new RawPair<>(pair.getFirst(), rid.getIdentity()));
            } else if (!(rid instanceof TombstoneRID)) {
              downstream.accept(pair);
            }
            return;
          }

          // version >= visibleVersion — phantom, no historical versions
          if (rid instanceof RecordId) {
            return;
          }

          // version >= visibleVersion — rid is TombstoneRID or SnapshotMarkerRID
          // → check snapshot for historical state
          snapshotVisibility(pair, visibleVersion).forEach(downstream);
        })
        .filter(p -> !inProgressVersions.contains((Long) p.first().getKeys().getLast()));
  }

  Stream<RawPair<CompositeKey, RID>> snapshotVisibility(RawPair<CompositeKey, RID> pair,
      long visibleVersion) {
    var keys = pair.first().getKeys();
    var userKeyPrefix = new CompositeKey(keys.subList(0, keys.size() - 1));
    var snapshotCompositeKey = enhanceIndexId(userKeyPrefix);

    var latestSnapshotEntry = indexesSnapshot
        .lowerEntry(new CompositeKey(snapshotCompositeKey, visibleVersion));
    if (latestSnapshotEntry != null && latestSnapshotEntry.getValue() instanceof TombstoneRID) {
      // Strip the indexId prefix from the snapshot key to return a BTree-format key.
      // Snapshot keys are stored as CompositeKey(indexId, userKey..., version), but
      // the caller expects CompositeKey(userKey..., version).
      var snapshotKeys = latestSnapshotEntry.getKey().getKeys();
      var btreeKey = new CompositeKey(snapshotKeys.subList(1, snapshotKeys.size()));
      return Stream.of(new RawPair<>(
          btreeKey,
          latestSnapshotEntry.getValue().getIdentity()));
    } else {
      return Stream.empty();
    }
  }

  public void evictStaleIndexesSnapshotEntries(long lwm) {
    if (lwm == Long.MAX_VALUE) {
      return;
    }
    // Sentinel: a CompositeKey whose last element is LWM, with minimal preceding keys
    // so that headMap captures everything with version < LWM
    var sentinel = new CompositeKey(Long.MIN_VALUE, lwm);
    var stale = versionIndex.headMap(sentinel);
    for (var key : stale.entrySet()) {
      // clean IndexesSnapshot
      indexesSnapshot.remove(key.getKey());
      indexesSnapshot.remove(key.getValue());
    }
    stale.clear();
  }

  public Set<Entry<CompositeKey, RID>> allEntries() {
    return indexesSnapshot.entrySet();
  }

  int versionIndexSize() {
    return versionIndex.size();
  }

  private CompositeKey enhanceIndexId(CompositeKey key) {
    return new CompositeKey(indexId, key);
  }

  public void clear() {
    indexesSnapshot.clear();
    versionIndex.clear();
  }
}
