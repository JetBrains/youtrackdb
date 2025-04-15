package com.jetbrains.youtrack.db.internal.core.storage.index.sbtree.singlevalue.v3;

import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.comparator.DefaultComparator;
import java.util.Comparator;
import java.util.Objects;

public final class CellBTreeSingleValueEntryV3<K>
    implements Comparable<CellBTreeSingleValueEntryV3<K>> {

  private final Comparator<? super K> comparator = DefaultComparator.INSTANCE;

  private final int leftChild;
  private final int rightChild;
  public final K key;
  public final RID value;

  public CellBTreeSingleValueEntryV3(
      final int leftChild, final int rightChild, final K key, final RID value) {
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
    final var that = (CellBTreeSingleValueEntryV3<?>) o;
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
  public int compareTo(final CellBTreeSingleValueEntryV3<K> other) {
    return comparator.compare(key, other.key);
  }
}
