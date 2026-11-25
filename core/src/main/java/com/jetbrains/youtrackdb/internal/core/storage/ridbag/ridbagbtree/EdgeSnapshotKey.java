package com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree;

/**
 * Typed key for the edge snapshot index. Identifies a specific version of a logical edge in the
 * snapshot store. The snapshot index preserves old versions of link bag entries so that older
 * transactions can read them under snapshot isolation.
 *
 * <p>Natural ordering: {@code linkBagFileId} → {@code ridBagId} → {@code targetCollection} → {@code
 * targetPosition} → {@code version} (all ascending). This groups all versions of the same logical
 * edge together, enabling efficient descending subMap scans to find the newest visible version.
 *
 * <p>{@code linkBagFileId} is the internal file id of the {@link SharedLinkBagBTree} instance.
 */
public record EdgeSnapshotKey(
    int linkBagFileId,
    long ridBagId,
    int targetCollection,
    long targetPosition,
    long version)
    implements Comparable<EdgeSnapshotKey> {

  @Override
  public int compareTo(EdgeSnapshotKey other) {
    int cmp = Integer.compare(linkBagFileId, other.linkBagFileId);
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
    cmp = Long.compare(targetPosition, other.targetPosition);
    if (cmp != 0) {
      return cmp;
    }
    return Long.compare(version, other.version);
  }
}
