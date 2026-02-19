package com.jetbrains.youtrackdb.internal.core.storage.collection;

/**
 * Typed key for the shared snapshot index. Replaces {@code CompositeKey(collectionPosition,
 * recordVersion)} with a type-safe, primitive-based record that supports efficient {@code
 * Comparable} ordering for use in {@code ConcurrentSkipListMap}.
 *
 * <p>Natural ordering: {@code componentId}, then {@code collectionPosition}, then {@code
 * recordVersion} (all ascending).
 */
public record SnapshotKey(int componentId, long collectionPosition, long recordVersion)
    implements Comparable<SnapshotKey> {

  @Override
  public int compareTo(SnapshotKey other) {
    int cmp = Integer.compare(componentId, other.componentId);
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
