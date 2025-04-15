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
 * Iterator class to browse forward and backward the records of a collection. Once browsed in a
 * direction, the iterator cannot change it.
 */
public class RecordIteratorCollection<REC extends RecordAbstract> implements Iterator<REC> {

  private RecordAbstract nextRecord;
  private RecordAbstract currentRecord;
  private RecordId nextNextRID;
  private final int collectionId;

  @Nonnull
  private final DatabaseSessionInternal session;

  private final boolean forwardDirection;
  private boolean initialized = false;

  public RecordIteratorCollection(@Nonnull final DatabaseSessionInternal session,
      final int collectionId, boolean forwardDirection) {
    checkForSystemCollections(session, new int[]{collectionId});

    this.session = session;
    this.forwardDirection = forwardDirection;
    this.collectionId = collectionId;
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
        var nextResult = session.loadRecordAndNextRidInCollection(nextNextRID);

        if (nextResult == null) {
          nextRecord = null;
          nextNextRID = null;
        } else {
          nextRecord = nextResult.getFirst();
          nextNextRID = nextResult.getSecond();
        }
      } else {
        var nextResult = session.loadRecordAndPreviousRidInCollection(nextNextRID);

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

    if (currentRecord.isUnloaded()) {
      var activeTransaction = session.getActiveTransaction();
      return activeTransaction.load(currentRecord);
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
      var result = session.loadFirstRecordAndNextRidInCollection(collectionId);

      if (result != null) {
        nextRecord = result.getFirst();
        nextNextRID = result.getSecond();
      } else {
        nextRecord = null;
        nextNextRID = null;
      }
    } else {
      var result = session.loadLastRecordAndPreviousRidInCollection(collectionId);

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

  private static void checkForSystemCollections(
      final DatabaseSessionInternal session, final int[] collectionIds) {
    if (session.isRemote()) {
      return;
    }

    for (var clId : collectionIds) {
      if (session.getStorage().isSystemCollection(clId)) {
        final var dbUser = session.getCurrentUser();
        if (dbUser == null
            || dbUser.allow(session, Rule.ResourceGeneric.SYSTEM_COLLECTIONS, null,
            Role.PERMISSION_READ)
            != null) {
          break;
        }
      }
    }
  }
}
