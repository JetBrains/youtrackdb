package com.jetbrains.youtrackdb.internal.core.index.engine.v1;

import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.SnapshotMarkerRID;
import com.jetbrains.youtrackdb.internal.core.id.TombstoneRID;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.index.IndexesSnapshot;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexCountDelta;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.CellBTreeSingleValue;
import java.io.IOException;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Shared versioned put/remove logic used by both {@link BTreeSingleValueIndexEngine}
 * and {@link BTreeMultiValueIndexEngine}. Extracted to eliminate ~80 lines of
 * duplication that must stay synchronized during future changes.
 */
final class VersionedIndexOps {

  private VersionedIndexOps() {
  }

  /**
   * Core versioned-put logic: handles version stamping, snapshot pair creation,
   * tombstone resurrection, and count delta accumulation.
   *
   * @param tree the BTree to operate on
   * @param snapshot the index snapshot for visibility tracking
   * @param atomicOperation current atomic operation
   * @param compositeKey defensive copy of the user key (will be mutated by addKey)
   * @param value the RID to insert
   * @param engineId engine ID for delta accumulation
   * @param isNullKey true if this is a null-key entry (for null-count delta)
   * @param existing the result of scanning for an existing entry
   * @return true if a new B-tree entry was inserted
   */
  static boolean doVersionedPut(
      CellBTreeSingleValue<CompositeKey> tree,
      IndexesSnapshot snapshot,
      @Nonnull AtomicOperation atomicOperation,
      CompositeKey compositeKey,
      RID value,
      int engineId,
      boolean isNullKey,
      @Nonnull Optional<RawPair<CompositeKey, RID>> existing) throws IOException {

    var version = atomicOperation.getCommitTs();
    if (existing.isPresent()) {
      var pair = existing.get();
      var removedRID = pair.second();
      var oldKey = pair.first();
      long oldVersion = (Long) oldKey.getKeys().getLast();

      if ((removedRID instanceof RecordId || removedRID instanceof SnapshotMarkerRID)
          && oldVersion == version) {
        // Same TX re-put — entry is already correct, skip remove+re-insert.
        return false;
      }

      tree.remove(atomicOperation, oldKey);
      compositeKey.addKey(version);
      tree.put(atomicOperation, compositeKey, new SnapshotMarkerRID(value));
      if (removedRID instanceof RecordId || removedRID instanceof SnapshotMarkerRID) {
        snapshot.addSnapshotPair(oldKey, compositeKey, removedRID.getIdentity());
      }
      if (removedRID instanceof TombstoneRID) {
        IndexCountDelta.accumulate(atomicOperation, engineId, +1, isNullKey);
      }
      return true;
    } else {
      compositeKey.addKey(version);
      IndexCountDelta.accumulate(atomicOperation, engineId, +1, isNullKey);
      return tree.put(atomicOperation, compositeKey, value);
    }
  }

  /**
   * Core versioned-remove logic: scans for an existing entry, replaces it with a
   * tombstone, creates a snapshot pair, and accumulates count delta.
   *
   * @param tree the BTree to operate on
   * @param snapshot the index snapshot for visibility tracking
   * @param atomicOperation current atomic operation
   * @param compositeKey the key to remove
   * @param engineId engine ID for delta accumulation
   * @param isNullKey true if this is a null-key entry (for null-count delta)
   * @return true if an entry was removed (tombstoned)
   */
  static boolean doVersionedRemove(
      CellBTreeSingleValue<CompositeKey> tree,
      IndexesSnapshot snapshot,
      @Nonnull AtomicOperation atomicOperation,
      CompositeKey compositeKey,
      int engineId,
      boolean isNullKey) throws IOException {

    Optional<RawPair<CompositeKey, RID>> res;
    try (var stream = tree.iterateEntriesBetween(
        compositeKey, true, compositeKey, true, true, atomicOperation)) {
      res = stream.findAny();
    }

    if (res.isEmpty()) {
      return false;
    }

    var pair = res.get();

    // Should not re-delete a deleted entry.
    if (pair.second() instanceof TombstoneRID) {
      return false;
    }

    var value = pair.second().getIdentity();

    tree.remove(atomicOperation, pair.first());

    var removedVersion = atomicOperation.getCommitTs();
    var newKey = new CompositeKey(compositeKey, removedVersion);
    tree.put(atomicOperation, newKey, new TombstoneRID(value));

    snapshot.addSnapshotPair(pair.first(), newKey, value);
    IndexCountDelta.accumulate(atomicOperation, engineId, -1, isNullKey);
    return true;
  }

  /**
   * Strips internal trailing elements from a composite key to recover the user-visible key.
   *
   * @param compositeKey the full internal key (may be null)
   * @param trailingCount number of trailing internal elements to strip
   *     (1 for single-value: version; 2 for multi-value: RID + version)
   * @return the user key — a single element unwrapped, or a trimmed CompositeKey
   */
  @Nullable static Object extractUserKey(
      final CompositeKey compositeKey, int trailingCount) {
    if (compositeKey == null) {
      return null;
    }
    final var keys = compositeKey.getKeys();
    int userKeyCount = keys.size() - trailingCount;
    if (userKeyCount == 1) {
      return keys.getFirst();
    }
    var result = new CompositeKey(userKeyCount);
    for (int i = 0; i < userKeyCount; i++) {
      result.addKey(keys.get(i));
    }
    return result;
  }
}
