package com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree;

public final class EdgeKey implements Comparable<EdgeKey> {

  public final long ridBagId;
  public final int targetCollection;
  public final long targetPosition;
  public final long ts;

  public EdgeKey(long ridBagId, int targetCollection, long targetPosition, long ts) {
    this.ridBagId = ridBagId;
    this.targetCollection = targetCollection;
    this.targetPosition = targetPosition;
    this.ts = ts;
  }

  @Override
  public String toString() {
    return "EdgeKey{"
        + " ridBagId="
        + ridBagId
        + ", targetCollection="
        + targetCollection
        + ", targetPosition="
        + targetPosition
        + ", ts="
        + ts
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    var edgeKey = (EdgeKey) o;

    if (ridBagId != edgeKey.ridBagId) {
      return false;
    }
    if (targetCollection != edgeKey.targetCollection) {
      return false;
    }
    if (targetPosition != edgeKey.targetPosition) {
      return false;
    }
    return ts == edgeKey.ts;
  }

  @Override
  public int hashCode() {
    var result = (int) (ridBagId ^ (ridBagId >>> 32));
    result = 31 * result + targetCollection;
    result = 31 * result + (int) (targetPosition ^ (targetPosition >>> 32));
    result = 31 * result + (int) (ts ^ (ts >>> 32));
    return result;
  }

  @Override
  public int compareTo(final EdgeKey other) {
    if (ridBagId != other.ridBagId) {
      if (ridBagId < other.ridBagId) {
        return -1;
      } else {
        return 1;
      }
    }

    if (targetCollection != other.targetCollection) {
      if (targetCollection < other.targetCollection) {
        return -1;
      } else {
        return 1;
      }
    }

    if (targetPosition != other.targetPosition) {
      if (targetPosition < other.targetPosition) {
        return -1;
      } else {
        return 1;
      }
    }

    if (ts != other.ts) {
      if (ts < other.ts) {
        return -1;
      } else {
        return 1;
      }
    }

    return 0;
  }
}
