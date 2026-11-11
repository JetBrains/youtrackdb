package com.jetbrains.youtrackdb.internal.core.storage.collection;

/**
 * Typed key for the shared snapshot index. Identifies a record version by its storage collection
 * id and position. Supports efficient {@code Comparable} ordering for use in {@code
 * ConcurrentSkipListMap}.
 *
 * <p>Natural ordering: {@code collectionId}, then {@code collectionPosition}, then {@code
 * recordVersion} (all ascending).
 */
public record SnapshotKey(int collectionId, long collectionPosition, long recordVersion)
    implements Comparable<SnapshotKey> {

  @Override
  public int compareTo(SnapshotKey other) {
    int cmp = Integer.compare(collectionId, other.collectionId);
    if (cmp != 0) {
      return cmp;
    }
    cmp = Long.compare(collectionPosition, other.collectionPosition);
    if (cmp != 0) {
      return cmp;
    }
    return Long.compare(recordVersion, other.recordVersion);
  }
}
