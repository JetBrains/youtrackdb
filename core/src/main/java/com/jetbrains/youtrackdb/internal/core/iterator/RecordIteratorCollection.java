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

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.storage.RawBuffer;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.CollectionBrowseEntry;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.CollectionBrowsePage;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import java.util.Iterator;
import java.util.NoSuchElementException;

/// Iterator class to browse forward and backward the records of a collection. Once browsed in a
/// direction, the iterator cannot change it. It handles both new (not yet committed, with negative
/// RID positions) and existing records.
public class RecordIteratorCollection<REC extends RecordAbstract>
    implements Iterator<REC>, AutoCloseable {

  private final DatabaseSessionEmbedded session;
  private final int collectionId;
  private final boolean forwardDirection;

  private Iterator<CollectionBrowsePage> storageIterator;
  private FrontendTransaction currentTx;
  private long minTxPosition;
  private RecordIdInternal nextTxId;
  private Iterator<CollectionBrowseEntry> collectionIterator;
  private REC next;

  public RecordIteratorCollection(
      DatabaseSessionEmbedded session,
      int collectionId,
      boolean forwardDirection
  ) {
    this(session, collectionId, forwardDirection, true);
  }

  public RecordIteratorCollection(
      DatabaseSessionEmbedded session,
      int collectionId,
      boolean forwardDirection,
      boolean checkAccess
  ) {
    if (checkAccess) {
      RecordIteratorUtil.checkCollectionAccess(session, collectionId);
    }
    this.session = session;
    this.collectionId = collectionId;
    this.forwardDirection = forwardDirection;

    // if this iterator was created inside the transaction, we will initialize it right away.
    // this way this iterator won't see any records that were created in the same transaction after
    // the iterator was created.
    initialize(false);
  }

  void initialize(boolean requireActiveTx) {
    if (currentTx != null) {
      // already initialized
      return;
    }
    currentTx = requireActiveTx ?
        session.getActiveTransaction() :
        session.getActiveTransactionOrNull();

    if (currentTx == null) {
      // no active transaction at the moment, we will initialize it on the first call to hasNext()
      return;
    }

    if (forwardDirection) {
      // starting from records created in the current transaction
      moveTxIdForward();

      if (nextTxId == null) {
        // if no new records, initialize the storage iterator
        initStorageIterator();
      }
    } else {
      // remember the lowest RID position in the current transaction. later we will
      // ignore all records with lower RID positions (created after this call).
      final var lowestTxRid = currentTx.getNextRidInCollection(
          new RecordId(collectionId, Long.MIN_VALUE),
          0
      );
      if (lowestTxRid != null) {
        minTxPosition = lowestTxRid.getCollectionPosition();
      }

      // starting from storage iteration
      initStorageIterator();
    }
  }

  @Override
  public boolean hasNext() {
    initialize(true);

    // this loop tries to initialize the next record to be returned by this iterator.
    // there are two main phases of the iteration: iterating over new records from the current transaction
    // and iterating over existing records from the storage.
    // the order of these phases is different for forward and backward iteration.
    // FORWARD: transaction records first, then storage records.
    // BACKWARD: storage records first, then transaction records.

    while (next == null) {
      final RecordIdInternal ridToLoad;
      final RawBuffer recordBuffer;

      // 3 cases:
      // 1) iterating of new records from the current transaction (nextTxId != null)
      // 2) iterating over existing records from the storage (storageIterator != null)
      // 3) no more records to iterate over (return false)

      if (nextTxId != null) {
        ridToLoad = nextTxId;
        recordBuffer = null;

        // moving to the next record
        if (forwardDirection) {
          moveTxIdForward();
        } else {
          moveTxIdBackward();
        }
        // if no more records, initialize the storage iterator (only for forward iteration)
        if (nextTxId == null && forwardDirection) {
          initStorageIterator();
        }
      } else if (storageIterator != null) {

        // iterating over the storage iterator until we find a record
        while (storageIterator != null &&
            (collectionIterator == null || !collectionIterator.hasNext())) {
          if (storageIterator.hasNext()) {
            collectionIterator = storageIterator.next().iterator();
          } else {
            storageIterator = null;
            collectionIterator = null;
          }
        }

        // if at this point if collectionIterator is null, we're done
        if (collectionIterator == null) {
          ridToLoad = null;
          recordBuffer = null;

          // initializing nextTxId to start iterating over new tx records (only for backward iteration)
          if (!forwardDirection) {
            moveTxIdBackward();
          }
        } else {
          final var nextEntry = collectionIterator.next();
          ridToLoad = new RecordId(collectionId, nextEntry.collectionPosition());
          recordBuffer = nextEntry.buffer();
        }
      } else {
        return false;
      }

      if (ridToLoad != null) {
        //noinspection unchecked
        next = (REC) session.executeReadRecord(ridToLoad, recordBuffer, false);
      }
    }

    return true;
  }

  @Override
  public REC next() {
    if (!hasNext()) {
      throw new NoSuchElementException();
    }
    final var toReturn = next;
    next = null;
    return toReturn;
  }

  private void moveTxIdForward() {
    if (nextTxId == null) {
      nextTxId = new RecordId(collectionId, Long.MIN_VALUE);
    }
    nextTxId = currentTx.getNextRidInCollection(nextTxId, 0);
  }

  private void moveTxIdBackward() {
    if (nextTxId == null) {
      nextTxId = new RecordId(collectionId, 0);
    }
    nextTxId = currentTx.getPreviousRidInCollection(nextTxId, minTxPosition);
  }

  private void initStorageIterator() {
    storageIterator =
        ((AbstractStorage) session.getStorage()).browseCollection(collectionId, forwardDirection);
  }

  @Override
  public void close() {
    // do nothing at the moment
  }
}
