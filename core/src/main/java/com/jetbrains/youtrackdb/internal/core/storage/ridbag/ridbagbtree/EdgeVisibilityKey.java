package com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree;

/**
 * Typed key for the edge visibility index used during snapshot cleanup. Maps each edge snapshot
 * entry to its transaction timestamp, enabling efficient LWM-based eviction via {@code
 * headMap(lowWaterMark)}.
 *
 * <p>Natural ordering: {@code recordTs} first (enables efficient range-scan by timestamp for
 * eviction), then {@code componentId} → {@code ridBagId} → {@code targetCollection} → {@code
 * targetPosition}.
 *
 * <p>{@code componentId} is the {@code collectionId} that identifies the {@link
 * SharedLinkBagBTree} instance (same as in {@link EdgeSnapshotKey}).
 */
public record EdgeVisibilityKey(
    long recordTs,
    int componentId,
    long ridBagId,
    int targetCollection,
    long targetPosition)
    implements Comparable<EdgeVisibilityKey> {

  @Override
  public int compareTo(EdgeVisibilityKey other) {
    int cmp = Long.compare(recordTs, other.recordTs);
    if (cmp != 0) {
      return cmp;
    }
    cmp = Integer.compare(componentId, other.componentId);
    if (cmp != 0) {
      return cmp;
    }
    cmp = Long.compare(ridBagId, other.ridBagId);
    if (cmp != 0) {
      return cmp;
    }
    cmp = Integer.compare(targetCollection, other.targetCollection);
    if (cmp != 0) {
      return cmp;
    }
    return Long.compare(targetPosition, other.targetPosition);
  }
}
