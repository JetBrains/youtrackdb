package com.jetbrains.youtrackdb.internal.core.storage.collection;

/**
 * Typed key for the visibility index used during snapshot cleanup. Identifies a record by its
 * storage collection id and position, with its transaction timestamp first for efficient range-scan
 * eviction via {@code headMap(lowWaterMark)}.
 *
 * <p>Natural ordering: {@code recordTs} first (enables efficient range-scan by timestamp), then
 * {@code collectionId}, then {@code collectionPosition}.
 */
public record VisibilityKey(long recordTs, int collectionId, long collectionPosition)
    implements Comparable<VisibilityKey> {

  @Override
  public int compareTo(VisibilityKey other) {
    int cmp = Long.compare(recordTs, other.recordTs);
    if (cmp != 0) {
      return cmp;
    }
    cmp = Integer.compare(collectionId, other.collectionId);
    if (cmp != 0) {
      return cmp;
    }
    return Long.compare(collectionPosition, other.collectionPosition);
  }
}
