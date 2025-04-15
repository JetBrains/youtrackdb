package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.api.record.RID;
import java.util.Iterator;

/**
 *
 */
public class RidSetIterator implements Iterator<RID> {

  private final Iterator<RID> negativesIterator;
  private final RidSet set;
  private int currentCollection = -1;
  private long currentId = -1;

  protected RidSetIterator(RidSet set) {
    this.set = set;
    this.negativesIterator = set.negatives.iterator();
    fetchNext();
  }

  @Override
  public boolean hasNext() {
    return negativesIterator.hasNext() || currentCollection >= 0;
  }

  @Override
  public RID next() {
    if (negativesIterator.hasNext()) {
      return negativesIterator.next();
    }
    if (!hasNext()) {
      throw new IllegalStateException();
    }
    var result = new RecordId(currentCollection, currentId);
    currentId++;
    fetchNext();
    return result;
  }

  private void fetchNext() {
    if (currentCollection < 0) {
      currentCollection = 0;
      currentId = 0;
    }

    var currentArrayPos = currentId / 63;
    var currentBit = currentId % 63;
    var block = (int) (currentArrayPos / set.maxArraySize);
    var blockPositionByteInt = (int) (currentArrayPos % set.maxArraySize);

    while (currentCollection < set.content.length) {
      while (set.content[currentCollection] != null && block < set.content[currentCollection].length) {
        while (set.content[currentCollection][block] != null
            && blockPositionByteInt < set.content[currentCollection][block].length) {
          if (currentBit == 0 && set.content[currentCollection][block][blockPositionByteInt] == 0L) {
            blockPositionByteInt++;
            currentArrayPos++;
            continue;
          }
          if (set.contains(new RecordId(currentCollection, currentArrayPos * 63 + currentBit))) {
            currentId = currentArrayPos * 63 + currentBit;
            return;
          } else {
            currentBit++;
            if (currentBit > 63) {
              currentBit = 0;
              blockPositionByteInt++;
              currentArrayPos++;
            }
          }
        }
        if (set.content[currentCollection][block] == null
            && set.content[currentCollection].length >= block) {
          currentArrayPos += set.maxArraySize;
        }
        block++;
        blockPositionByteInt = 0;
        currentBit = 0;
      }
      block = 0;
      currentBit = 0;
      currentArrayPos = 0;
      blockPositionByteInt = 0;
      currentCollection++;
    }

    currentCollection = -1;
  }
}
