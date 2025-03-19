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
package com.jetbrains.youtrack.db.internal.core.iterator;

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import javax.annotation.Nonnull;
import org.apache.commons.lang.ArrayUtils;

/**
 * Iterator to browse multiple clusters forward and backward. Once browsed in a direction, the
 * iterator cannot change it. This iterator with "live updates" set is able to catch updates to the
 * cluster sizes while browsing. This is the case when concurrent clients/threads insert and remove
 * item in any cluster the iterator is browsing. If the cluster are hot removed by from the database
 * the iterator could be invalid and throw exception of cluster not found.
 */
public class RecordIteratorClusters<REC extends RecordAbstract> implements Iterator<REC> {

  private RecordIteratorCluster<REC>[] clusterIterators;
  private int clusterIndex = 0;

  private REC currentRecord;
  private RecordIteratorCluster currentClusterIterator;

  @Nonnull
  private final DatabaseSessionInternal session;
  private final int[] clusterIds;

  public RecordIteratorClusters(
      @Nonnull final DatabaseSessionInternal session, final int[] iClusterIds,
      boolean forwardDirection) {
    this.session = session;

    clusterIds = iClusterIds.clone();
    Arrays.sort(clusterIds);

    if (!forwardDirection) {
      ArrayUtils.reverse(clusterIds);
    }

    //noinspection unchecked
    clusterIterators = new RecordIteratorCluster[clusterIds.length];

    for (var i = 0; i < clusterIds.length; ++i) {
      clusterIterators[i] = new RecordIteratorCluster<>(session, clusterIds[i],
          forwardDirection);
    }

    currentClusterIterator = clusterIterators[clusterIndex];
  }

  public int[] getClusterIds() {
    return clusterIds;
  }

  @Override
  public boolean hasNext() {
    if (currentClusterIterator == null) {
      return false;
    }

    while (currentClusterIterator != null && !currentClusterIterator.hasNext()) {
      if (clusterIndex < clusterIterators.length - 1) {
        clusterIndex++;
        currentClusterIterator = clusterIterators[clusterIndex];
      } else {
        currentClusterIterator = null;
      }
    }

    return currentClusterIterator != null;
  }

  @Override
  public REC next() {
    //noinspection unchecked
    currentRecord = (REC) currentClusterIterator.next();

    while (currentClusterIterator != null && !currentClusterIterator.hasNext()) {
      if (clusterIndex < clusterIterators.length - 1) {
        clusterIndex++;
        currentClusterIterator = clusterIterators[clusterIndex];
      } else {
        currentClusterIterator = null;
      }
    }

    return currentRecord;
  }

  @Override
  public void remove() {
    if (currentRecord == null) {
      throw new NoSuchElementException();
    }

    session.delete(currentRecord);
    currentRecord = null;
  }
}
