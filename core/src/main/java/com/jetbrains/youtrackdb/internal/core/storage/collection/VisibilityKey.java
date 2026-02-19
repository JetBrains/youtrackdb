package com.jetbrains.youtrackdb.internal.core.storage.collection;

/**
 * Typed key for the visibility index used during snapshot cleanup. Replaces {@code
 * CompositeKey(newRecordVersion, collectionPosition)} with a type-safe, primitive-based record
 * that supports efficient {@code Comparable} ordering for range-scan eviction via {@code
 * headMap(lowWaterMark)}.
 *
 * <p>Natural ordering: {@code recordTs} first (enables efficient range-scan by timestamp), then
 * {@code componentId}, then {@code collectionPosition}.
 */
public record VisibilityKey(long recordTs, int componentId, long collectionPosition)
    implements Comparable<VisibilityKey> {

  @Override
  public int compareTo(VisibilityKey other) {
    int cmp = Long.compare(recordTs, other.recordTs);
    if (cmp != 0) {
      return cmp;
    }
    cmp = Integer.compare(componentId, other.componentId);
    if (cmp != 0) {
      return cmp;
    }
    return Long.compare(collectionPosition, other.collectionPosition);
  }
}
