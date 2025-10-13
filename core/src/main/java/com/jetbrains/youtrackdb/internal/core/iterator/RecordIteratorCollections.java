/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.iterator;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import javax.annotation.Nonnull;
import org.apache.commons.lang.ArrayUtils;

/**
 * Iterator to browse multiple collections forward and backward. Once browsed in a direction, the
 * iterator cannot change it. This iterator with "live updates" set is able to catch updates to the
 * collection sizes while browsing. This is the case when concurrent clients/threads insert and remove
 * item in any collection the iterator is browsing. If the collection are hot removed by from the database
 * the iterator could be invalid and throw exception of collection not found.
 */
public class RecordIteratorCollections<REC extends RecordAbstract>
    implements Iterator<REC>, AutoCloseable {

  private final RecordIteratorCollection<REC>[] collectionIterators;

  private int collectionIndex = 0;
  private REC currentRecord;
  private RecordIteratorCollection<REC> currentCollectionIterator;

  @Nonnull
  private final DatabaseSessionInternal session;
  private final int[] collectionIds;

  public RecordIteratorCollections(
      @Nonnull final DatabaseSessionInternal session, final int[] iCollectionIds,
      boolean forwardDirection) {
    this.session = session;

    collectionIds = iCollectionIds.clone();
    Arrays.sort(collectionIds);

    if (!forwardDirection) {
      ArrayUtils.reverse(collectionIds);
    }

    //noinspection unchecked
    collectionIterators = new RecordIteratorCollection[collectionIds.length];

    for (var i = 0; i < collectionIds.length; ++i) {
      collectionIterators[i] = new RecordIteratorCollection<>(session, collectionIds[i], forwardDirection);
    }

    currentCollectionIterator = collectionIterators[collectionIndex];
  }

  public int[] getCollectionIds() {
    return collectionIds;
  }

  @Override
  public boolean hasNext() {
    if (currentCollectionIterator == null) {
      return false;
    }

    while (currentCollectionIterator != null && !currentCollectionIterator.hasNext()) {
      if (collectionIndex < collectionIterators.length - 1) {
        collectionIndex++;
        currentCollectionIterator = collectionIterators[collectionIndex];
      } else {
        currentCollectionIterator = null;
      }
    }

    return currentCollectionIterator != null;
  }

  @Override
  public REC next() {
    if (!hasNext()) {
      throw new NoSuchElementException();
    }
    return currentCollectionIterator.next();
  }

  @Override
  public void remove() {
    if (currentRecord == null) {
      throw new NoSuchElementException();
    }

    session.delete(currentRecord);
    currentRecord = null;
  }

  @Override
  public void close() {
    for (var iterator : collectionIterators) {
      iterator.close();
    }
  }
}
