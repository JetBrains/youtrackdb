package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Special implementation of Java Set&lt;RID&gt; to efficiently handle memory and performance. It
 * does not store actual RIDs, but it only keeps track that a RID was stored, so the iterator will
 * return new instances.
 */
public class RidSet implements Set<RID> {

  protected static int INITIAL_BLOCK_SIZE = 4096;

  /*
   * collection / offset / bitmask
   * eg. inserting #12:0 you will have content[12][0][0] = 1
   * eg. inserting #12:(63*maxArraySize + 1) you will have content[12][1][0] = 1
   *
   */
  protected long[][][] content = new long[8][][];

  private long size = 0;
  protected Set<RID> negatives = new HashSet<>();

  protected int maxArraySize;

  /**
   * instantiates an RidSet with a bucket size of Integer.MAX_VALUE / 10
   */
  public RidSet() {
    this(Integer.MAX_VALUE / 10);
  }

  /**
   * @param bucketSize the maximum size of each internal bucket array
   */
  public RidSet(int bucketSize) {
    maxArraySize = bucketSize;
  }

  @Override
  public int size() {
    return size + negatives.size() <= Integer.MAX_VALUE
        ? (int) size + negatives.size()
        : Integer.MAX_VALUE;
  }

  @Override
  public boolean isEmpty() {
    return size == 0L;
  }

  @Override
  public boolean contains(Object o) {
    if (size == 0L && negatives.isEmpty()) {
      return false;
    }

    if (!(o instanceof RID)) {
      if (o instanceof Identifiable identifiable) {
        o = identifiable.getIdentity();
      } else {
        return false;
      }
    }

    var rid = (RID) o;
    var collection = rid.getCollectionId();
    var position = rid.getCollectionPosition();
    if (collection < 0 || position < 0) {
      return negatives.contains(rid);
    }

    var positionByte = (position / 63);
    var positionBit = (int) (position % 63);
    var block = (int) (positionByte / maxArraySize);
    var blockPositionByteInt = (int) (positionByte % maxArraySize);

    if (content.length <= collection) {
      return false;
    }
    if (content[collection] == null) {
      return false;
    }
    if (content[collection].length <= block) {
      return false;
    }
    if (content[collection][block] == null) {
      return false;
    }
    if (content[collection][block].length <= blockPositionByteInt) {
      return false;
    }

    var currentMask = 1L << positionBit;
    var existed = content[collection][block][blockPositionByteInt] & currentMask;

    return existed > 0L;
  }

  @Override
  public Iterator<RID> iterator() {
    return new RidSetIterator(this);
  }

  @Override
  public Object[] toArray() {
    return new Object[0];
  }

  @Nullable
  @Override
  public <T> T[] toArray(T[] a) {
    return null;
  }

  @Override
  public boolean add(RID identifiable) {
    if (identifiable == null) {
      throw new IllegalArgumentException();
    }
    var collection = identifiable.getCollectionId();
    var position = identifiable.getCollectionPosition();
    if (collection < 0 || position < 0) {
      return negatives.add(identifiable);
    }
    var positionByte = (position / 63);
    var positionBit = (int) (position % 63);
    var block = (int) (positionByte / maxArraySize);
    var blockPositionByteInt = (int) (positionByte % maxArraySize);

    if (content.length <= collection) {
      var oldContent = content;
      content = new long[collection + 1][][];
      System.arraycopy(oldContent, 0, content, 0, oldContent.length);
    }
    if (content[collection] == null) {
      content[collection] = createCollectionArray(block, blockPositionByteInt);
    }

    if (content[collection].length <= block) {
      content[collection] = expandCollectionBlocks(content[collection], block,
          blockPositionByteInt);
    }
    if (content[collection][block] == null) {
      content[collection][block] =
          expandCollectionArray(new long[INITIAL_BLOCK_SIZE], blockPositionByteInt);
    }
    if (content[collection][block].length <= blockPositionByteInt) {
      content[collection][block] = expandCollectionArray(content[collection][block],
          blockPositionByteInt);
    }

    var original = content[collection][block][blockPositionByteInt];
    var currentMask = 1L << positionBit;
    var existed = content[collection][block][blockPositionByteInt] & currentMask;
    content[collection][block][blockPositionByteInt] = original | currentMask;
    if (existed == 0L) {
      size++;
    }
    return existed == 0L;
  }

  private static long[][] expandCollectionBlocks(long[][] longs, int block,
      int blockPositionByteInt) {
    var result = new long[block + 1][];
    System.arraycopy(longs, 0, result, 0, longs.length);
    result[block] = expandCollectionArray(new long[INITIAL_BLOCK_SIZE], blockPositionByteInt);
    return result;
  }

  private static long[][] createCollectionArray(int block, int positionByteInt) {
    var currentSize = INITIAL_BLOCK_SIZE;
    while (currentSize <= positionByteInt) {
      currentSize *= 2;
      if (currentSize < 0) {
        currentSize = positionByteInt + 1;
        break;
      }
    }
    var result = new long[block + 1][];
    result[block] = new long[currentSize];
    return result;
  }

  private static long[] expandCollectionArray(long[] original, int positionByteInt) {
    var currentSize = original.length;
    while (currentSize <= positionByteInt) {
      currentSize *= 2;
      if (currentSize < 0) {
        currentSize = positionByteInt + 1;
        break;
      }
    }
    var result = new long[currentSize];
    System.arraycopy(original, 0, result, 0, original.length);
    return result;
  }

  @Override
  public boolean remove(Object o) {
    if (!(o instanceof RID identifiable)) {
      throw new IllegalArgumentException();
    }
    if (identifiable == null) {
      throw new IllegalArgumentException();
    }
    var collection = identifiable.getCollectionId();
    var position = identifiable.getCollectionPosition();
    if (collection < 0 || position < 0) {
      return negatives.remove(o);
    }
    var positionByte = (position / 63);
    var positionBit = (int) (position % 63);
    var block = (int) (positionByte / maxArraySize);
    var blockPositionByteInt = (int) (positionByte % maxArraySize);

    if (content.length <= collection) {
      return false;
    }
    if (content[collection] == null) {
      return false;
    }
    if (content[collection].length <= block) {
      return false;
    }
    if (content[collection][block].length <= blockPositionByteInt) {
      return false;
    }

    var original = content[collection][block][blockPositionByteInt];
    var currentMask = 1L << positionBit;
    var existed = content[collection][block][blockPositionByteInt] & currentMask;
    currentMask = ~currentMask;
    content[collection][block][blockPositionByteInt] = original & currentMask;
    if (existed > 0) {
      size--;
    }
    return existed == 0L;
  }

  @Override
  public boolean containsAll(Collection<?> c) {
    for (var o : c) {
      if (!contains(o)) {
        return false;
      }
    }
    return true;
  }

  @Override
  public boolean addAll(Collection<? extends RID> c) {
    var added = false;
    for (var o : c) {
      added = added && add(o);
    }
    return added;
  }

  @Override
  public boolean retainAll(Collection<?> c) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean removeAll(Collection<?> c) {
    for (var o : c) {
      remove(o);
    }
    return true;
  }

  @Override
  public void clear() {
    content = new long[8][][];
    size = 0;
    this.negatives.clear();
  }
}
