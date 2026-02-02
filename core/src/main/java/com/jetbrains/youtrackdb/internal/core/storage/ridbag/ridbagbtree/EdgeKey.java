package com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree;

public record EdgeKey(long ridBagId, int targetCollection, long targetPosition) implements
    Comparable<EdgeKey> {

  @Override
  public String toString() {
    return "EdgeKey{"
        + " ridBagId="
        + ridBagId
        + ", targetCollection="
        + targetCollection
        + ", targetPosition="
        + targetPosition
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
    return targetPosition == edgeKey.targetPosition;
  }

  @Override
  public int hashCode() {
    var result = (int) (ridBagId ^ (ridBagId >>> 32));
    result = 31 * result + targetCollection;
    result = 31 * result + (int) (targetPosition ^ (targetPosition >>> 32));
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

    if (targetPosition < other.targetPosition) {
      return -1;
    } else if (targetPosition > other.targetPosition) {
      return 1;
    }

    return 0;
  }
}
