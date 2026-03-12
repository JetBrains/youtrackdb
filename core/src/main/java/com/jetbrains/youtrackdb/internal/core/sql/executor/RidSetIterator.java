package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.Iterator;
import java.util.NoSuchElementException;
import org.roaringbitmap.longlong.LongIterator;
import org.roaringbitmap.longlong.Roaring64Bitmap;

/**
 * Iterator over the record identifiers contained in a {@link RidSet}.
 *
 * <p>Iterates first over negative RIDs, then over collection IDs in ascending order. Within each
 * collection, positions are yielded in ascending order via the {@link Roaring64Bitmap} iterator.
 */
public class RidSetIterator implements Iterator<RID> {

  private final Iterator<RID> negativesIterator;
  private final Iterator<Int2ObjectMap.Entry<Roaring64Bitmap>> collectionIterator;
  private int currentCollection = -1;
  private LongIterator currentPositionIterator;

  protected RidSetIterator(RidSet set) {
    this.negativesIterator = set.negatives.iterator();

    // Sort collection IDs for deterministic iteration order
    var sortedEntries = new java.util.ArrayList<>(set.content.int2ObjectEntrySet());
    sortedEntries.sort(java.util.Comparator.comparingInt(Int2ObjectMap.Entry::getIntKey));
    this.collectionIterator = sortedEntries.iterator();

    advanceCollection();
  }

  @Override
  public boolean hasNext() {
    return negativesIterator.hasNext()
        || (currentPositionIterator != null && currentPositionIterator.hasNext());
  }

  @Override
  public RID next() {
    if (negativesIterator.hasNext()) {
      return negativesIterator.next();
    }
    if (currentPositionIterator == null || !currentPositionIterator.hasNext()) {
      throw new NoSuchElementException();
    }
    var position = currentPositionIterator.next();
    var result = new RecordId(currentCollection, position);

    // If current bitmap is exhausted, advance to the next collection
    if (!currentPositionIterator.hasNext()) {
      advanceCollection();
    }
    return result;
  }

  private void advanceCollection() {
    while (collectionIterator.hasNext()) {
      var entry = collectionIterator.next();
      var bitmap = entry.getValue();
      if (!bitmap.isEmpty()) {
        currentCollection = entry.getIntKey();
        currentPositionIterator = bitmap.getLongIterator();
        return;
      }
    }
    currentPositionIterator = null;
  }
}
