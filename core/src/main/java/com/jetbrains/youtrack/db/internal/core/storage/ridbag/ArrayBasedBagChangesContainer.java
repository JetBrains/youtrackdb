package com.jetbrains.youtrack.db.internal.core.storage.ridbag;

import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Spliterator;
import java.util.Spliterators;
import javax.annotation.Nonnull;

public class ArrayBasedBagChangesContainer implements BagChangesContainer {

  public static final Comparator<RawPair<RID, Change>> COMPARATOR = Comparator.comparing(
      RawPair::first);
  @SuppressWarnings("unchecked")
  private RawPair<RID, Change>[] changes = new RawPair[32];

  private int size = 0;

  @Override
  public Change getChange(RID rid) {
    var index = Arrays.binarySearch(changes, 0, size, new RawPair<>(rid, null), COMPARATOR);

    if (index >= 0) {
      return changes[index].second();
    } else {
      return null;
    }
  }

  @Override
  public void putChange(RID rid, Change change) {
    var index = Arrays.binarySearch(changes, 0, size, new RawPair<>(rid, null), COMPARATOR);

    if (index >= 0) {
      changes[index] = new RawPair<>(rid, change);
    } else {
      index = -index - 1;
      insertAt(rid, change, index);
    }
    assert ensureAllSorted();
  }

  private void insertAt(RID rid, Change change, int index) {
    if (size == changes.length) {
      changes = Arrays.copyOf(changes, changes.length << 1);
    }

    System.arraycopy(changes, index, changes, index + 1, size - index);
    changes[index] = new RawPair<>(rid, change);
    size++;
  }

  @Override
  public void fillAllSorted(Collection<? extends RawPair<RID, Change>> changes) {
    if (size > 0) {
      throw new IllegalStateException("Container is not empty");
    }

    ensureCapacity(changes.size());
    var i = 0;
    for (var change : changes) {
      this.changes[i] = change;
      i++;
    }
    size = changes.size();

    assert ensureAllSorted() : "Changes are not sorted.";
  }

  private boolean ensureAllSorted() {
    for (var i = 1; i < size; i++) {
      if (changes[i - 1].first().compareTo(changes[i].first()) > 0) {
        return false;
      }
    }
    return true;
  }

  private void ensureCapacity(int required) {
    if (required > changes.length) {
      var capacity = Math.max(changes.length << 1, ceilingPowerOfTwo(required));
      changes = Arrays.copyOf(changes, capacity);
    }
  }

  private static int ceilingPowerOfTwo(int value) {
    return 1 << (Integer.SIZE - Integer.numberOfLeadingZeros(value - 1));
  }

  @Override
  public int size() {
    return size;
  }

  @Nonnull
  @Override
  public Spliterator<RawPair<RID, Change>> spliterator() {
    return Spliterators.spliterator(changes, 0, size,
        Spliterator.SORTED | Spliterator.NONNULL | Spliterator.DISTINCT | Spliterator.SIZED);
  }

  @Nonnull
  @Override
  public Spliterator<RawPair<RID, Change>> spliterator(RID after) {
    var index = Arrays.binarySearch(changes, 0, size, new RawPair<>(after, null), COMPARATOR);
    if (index < 0) {
      index = -index - 1;
    } else {
      index++;
    }

    if (index < size) {
      return Spliterators.spliterator(changes, index, size,
          Spliterator.SORTED | Spliterator.NONNULL | Spliterator.DISTINCT | Spliterator.SIZED);
    }

    return Spliterators.emptySpliterator();
  }

  @Override
  public void clear() {
    for (var i = 0; i < size; i++) {
      changes[i] = null;
    }

    size = 0;
  }

  @Override
  public boolean isEmpty() {
    return size == 0;
  }
}
