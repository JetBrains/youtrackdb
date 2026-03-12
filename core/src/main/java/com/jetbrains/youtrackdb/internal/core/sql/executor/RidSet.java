package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import javax.annotation.Nullable;
import org.roaringbitmap.longlong.Roaring64Bitmap;

/**
 * Memory-efficient Set&lt;RID&gt; implementation backed by compressed bitmaps.
 *
 * <p>Uses a fastutil {@link Int2ObjectOpenHashMap} keyed by collection ID, with each value being a
 * {@link Roaring64Bitmap} that stores the collection positions as compressed bitmaps. This provides
 * efficient storage for both dense and sparse position ranges.
 *
 * <p>RIDs with negative collection IDs or positions (temporary/new records) are stored separately
 * in a {@link HashSet}.
 *
 * <p>The iterator returns new {@link com.jetbrains.youtrackdb.internal.core.id.RecordId} instances
 * created on-the-fly from the stored bitmap data.
 */
public class RidSet implements Set<RID> {

  protected final Int2ObjectOpenHashMap<Roaring64Bitmap> content;
  protected final Set<RID> negatives;

  public RidSet() {
    content = new Int2ObjectOpenHashMap<>();
    negatives = new HashSet<>();
  }

  /**
   * @param bucketSize ignored, kept for backward compatibility
   */
  public RidSet(int bucketSize) {
    this();
  }

  @Override
  public int size() {
    long total = negatives.size();
    for (var bitmap : content.values()) {
      total += bitmap.getLongCardinality();
    }
    return total <= Integer.MAX_VALUE ? (int) total : Integer.MAX_VALUE;
  }

  @Override
  public boolean isEmpty() {
    return negatives.isEmpty() && content.isEmpty();
  }

  @Override
  public boolean contains(Object o) {
    RID rid;
    if (o instanceof RID r) {
      rid = r;
    } else if (o instanceof Identifiable identifiable) {
      rid = identifiable.getIdentity();
    } else {
      return false;
    }

    var collection = rid.getCollectionId();
    var position = rid.getCollectionPosition();
    if (collection < 0 || position < 0) {
      return negatives.contains(rid);
    }

    var bitmap = content.get(collection);
    return bitmap != null && bitmap.contains(position);
  }

  @Override
  public Iterator<RID> iterator() {
    return new RidSetIterator(this);
  }

  @Override
  public Object[] toArray() {
    var result = new Object[size()];
    var i = 0;
    for (var rid : this) {
      result[i++] = rid;
    }
    return result;
  }

  @Nullable @Override
  @SuppressWarnings("unchecked")
  public <T> T[] toArray(T[] a) {
    var sz = size();
    if (a.length < sz) {
      a = (T[]) java.lang.reflect.Array.newInstance(a.getClass().getComponentType(), sz);
    }
    var i = 0;
    for (var rid : this) {
      a[i++] = (T) rid;
    }
    if (a.length > sz) {
      a[sz] = null;
    }
    return a;
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

    var bitmap = content.get(collection);
    if (bitmap == null) {
      bitmap = new Roaring64Bitmap();
      content.put(collection, bitmap);
    }

    var existed = bitmap.contains(position);
    if (!existed) {
      bitmap.addLong(position);
    }
    return !existed;
  }

  @Override
  public boolean remove(Object o) {
    if (!(o instanceof RID identifiable)) {
      throw new IllegalArgumentException();
    }
    var collection = identifiable.getCollectionId();
    var position = identifiable.getCollectionPosition();
    if (collection < 0 || position < 0) {
      return negatives.remove(o);
    }

    var bitmap = content.get(collection);
    if (bitmap == null) {
      return false;
    }

    var existed = bitmap.contains(position);
    if (existed) {
      bitmap.removeLong(position);
      if (bitmap.isEmpty()) {
        content.remove(collection);
      }
    }
    return existed;
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
    var modified = false;
    for (var o : c) {
      if (add(o)) {
        modified = true;
      }
    }
    return modified;
  }

  @Override
  public boolean retainAll(Collection<?> c) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean removeAll(Collection<?> c) {
    var modified = false;
    for (var o : c) {
      if (remove(o)) {
        modified = true;
      }
    }
    return modified;
  }

  @Override
  public void clear() {
    content.clear();
    negatives.clear();
  }
}
