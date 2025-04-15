package com.jetbrains.youtrack.db.internal.core.storage.ridbag.ridbagbtree;

import java.util.Objects;

final class TreeEntry implements Comparable<TreeEntry> {

  private final int leftChild;
  private final int rightChild;
  private final EdgeKey key;
  private final int value;

  public TreeEntry(final int leftChild, final int rightChild, final EdgeKey key, final int value) {
    this.leftChild = leftChild;
    this.rightChild = rightChild;
    this.key = key;
    this.value = value;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    final var that = (TreeEntry) o;
    return leftChild == that.leftChild
        && rightChild == that.rightChild
        && Objects.equals(key, that.key)
        && Objects.equals(value, that.value);
  }

  @Override
  public int hashCode() {
    return Objects.hash(leftChild, rightChild, key, value);
  }

  @Override
  public String toString() {
    return "CellBTreeEntry{"
        + "leftChild="
        + leftChild
        + ", rightChild="
        + rightChild
        + ", key="
        + key
        + ", value="
        + value
        + '}';
  }

  @Override
  public int compareTo(final TreeEntry other) {
    return key.compareTo(other.key);
  }

  public EdgeKey getKey() {
    return key;
  }

  public int getValue() {
    return value;
  }
}
