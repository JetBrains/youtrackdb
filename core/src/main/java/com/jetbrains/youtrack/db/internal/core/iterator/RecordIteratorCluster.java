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
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Rule;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import java.util.Iterator;
import java.util.NoSuchElementException;
import javax.annotation.Nonnull;

/**
 * Iterator class to browse forward and backward the records of a cluster. Once browsed in a
 * direction, the iterator cannot change it.
 */
public class RecordIteratorCluster<REC extends RecordAbstract> implements Iterator<REC> {

  private RecordAbstract nextRecord;
  private RecordAbstract currentRecord;
  private RecordId nextNextRID;
  private final int clusterId;

  @Nonnull
  private final DatabaseSessionInternal session;

  private final boolean forwardDirection;
  private boolean initialized = false;

  public RecordIteratorCluster(@Nonnull final DatabaseSessionInternal session,
      final int clusterId, boolean forwardDirection) {
    checkForSystemClusters(session, new int[]{clusterId});

    this.session = session;
    this.forwardDirection = forwardDirection;
    this.clusterId = clusterId;
  }

  @Override
  public boolean hasNext() {
    initialize();
    return nextRecord != null;
  }

  @Override
  public REC next() {
    initialize();

    if (nextRecord == null) {
      currentRecord = null;
      throw new NoSuchElementException();
    }

    currentRecord = nextRecord;

    if (nextNextRID != null) {
      if (forwardDirection) {
        var nextResult = session.loadRecordAndNextRidInCluster(nextNextRID);

        if (nextResult == null) {
          nextRecord = null;
          nextNextRID = null;
        } else {
          nextRecord = nextResult.getFirst();
          nextNextRID = nextResult.getSecond();
        }
      } else {
        var nextResult = session.loadRecordAndPreviousRidInCluster(nextNextRID);

        if (nextResult == null) {
          nextRecord = null;
          nextNextRID = null;
        } else {
          nextRecord = nextResult.getFirst();
          nextNextRID = nextResult.getSecond();
        }
      }
    } else {
      nextRecord = null;
    }

    //noinspection unchecked
    return (REC) currentRecord;
  }

  @Override
  public void remove() {
    if (currentRecord == null) {
      throw new NoSuchElementException();
    }

    session.delete(currentRecord);
    currentRecord = null;
  }

  private void initialize() {
    if (initialized) {
      return;
    }

    if (forwardDirection) {
      var result = session.loadFirstRecordAndNextRidInCluster(clusterId);

      if (result != null) {
        nextRecord = result.getFirst();
        nextNextRID = result.getSecond();
      } else {
        nextRecord = null;
        nextNextRID = null;
      }
    } else {
      var result = session.loadLastRecordAndPreviousRidInCluster(clusterId);

      if (result != null) {
        nextRecord = result.getFirst();
        nextNextRID = result.getSecond();
      } else {
        nextRecord = null;
        nextNextRID = null;
      }
    }

    initialized = true;
  }

  private static void checkForSystemClusters(
      final DatabaseSessionInternal session, final int[] clusterIds) {
    if (session.isRemote()) {
      return;
    }

    for (var clId : clusterIds) {
      if (session.getStorage().isSystemCluster(clId)) {
        final var dbUser = session.getCurrentUser();
        if (dbUser == null
            || dbUser.allow(session, Rule.ResourceGeneric.SYSTEM_CLUSTERS, null,
            Role.PERMISSION_READ)
            != null) {
          break;
        }
      }
    }
  }
}
