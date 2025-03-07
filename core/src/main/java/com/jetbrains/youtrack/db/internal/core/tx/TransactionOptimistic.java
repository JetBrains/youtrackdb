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

package com.jetbrains.youtrack.db.internal.core.tx;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.exception.TransactionException;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.record.RecordHook.TYPE;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.cache.LocalRecordCache;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.exception.StorageException;
import com.jetbrains.youtrack.db.internal.core.id.ChangeableIdentity;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.index.ClassIndexManager;
import com.jetbrains.youtrack.db.internal.core.index.CompositeKey;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.index.IndexDefinition;
import com.jetbrains.youtrack.db.internal.core.index.IndexManagerAbstract;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrack.db.internal.core.metadata.sequence.SequenceLibraryProxy;
import com.jetbrains.youtrack.db.internal.core.query.live.LiveQueryHook;
import com.jetbrains.youtrack.db.internal.core.query.live.LiveQueryHookV2;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.RecordInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.DirtyManager;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityInternalUtils;
import com.jetbrains.youtrack.db.internal.core.schedule.ScheduledEvent;
import com.jetbrains.youtrack.db.internal.core.storage.Storage;
import com.jetbrains.youtrack.db.internal.core.storage.StorageProxy;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractPaginatedStorage;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionIndexChanges.OPERATION;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionIndexChangesPerKey.TransactionIndexEntry;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class TransactionOptimistic extends FrontendTransactionAbstract implements
    TransactionInternal {

  private static final AtomicLong txSerial = new AtomicLong();

  // order of updates is critical during synchronization of remote transactions
  protected LinkedHashMap<RecordId, RecordId> txGeneratedRealRecordIdMap = new LinkedHashMap<>();
  protected LinkedHashMap<RecordId, RecordOperation> recordOperations = new LinkedHashMap<>();

  protected LinkedHashMap<String, FrontendTransactionIndexChanges> indexEntries = new LinkedHashMap<>();
  protected HashMap<RID, List<FrontendTransactionRecordIndexOperation>> recordIndexOperations =
      new HashMap<>();

  protected long id;
  protected int newRecordsPositionsGenerator = -2;
  private final HashMap<String, Object> userData = new HashMap<>();

  @Nullable
  private FrontendTransacationMetadataHolder metadata = null;

  @Nullable
  private List<byte[]> serializedOperations;

  protected boolean changed = true;
  private boolean isAlreadyStartedOnServer = false;
  protected int txStartCounter;
  private boolean sentToServer = false;

  public TransactionOptimistic(final DatabaseSessionInternal iDatabase) {
    super(iDatabase);
    this.id = txSerial.incrementAndGet();
  }

  protected TransactionOptimistic(final DatabaseSessionInternal iDatabase, long id) {
    super(iDatabase);
    this.id = id;
  }

  public int begin() {
    if (txStartCounter < 0) {
      throw new TransactionException("Invalid value of TX counter: " + txStartCounter);
    }

    if (txStartCounter == 0) {
      status = TXSTATUS.BEGUN;
      database.transactionMeters()
          .totalTransactions()
          .record();

      var localCache = database.getLocalCache();
      localCache.unloadNotModifiedRecords();
      localCache.clear();
    } else {
      if (status == TXSTATUS.ROLLED_BACK || status == TXSTATUS.ROLLBACKING) {
        throw new RollbackException(
            "Impossible to start a new transaction because the current was rolled back");
      }
    }

    txStartCounter++;
    return txStartCounter;
  }

  public void commit() {
    commit(false);
  }

  /**
   * The transaction is reentrant. If {@code begin()} has been called several times, the actual
   * commit happens only after the same amount of {@code commit()} calls
   *
   * @param force commit transaction even
   */
  @Override
  public void commit(final boolean force) {
    checkTransactionValid();
    if (txStartCounter < 0) {
      throw new StorageException("Invalid value of tx counter: " + txStartCounter);
    }
    if (force) {
      txStartCounter = 0;
    } else {
      txStartCounter--;
    }

    if (txStartCounter == 0) {
      doCommit();
    } else {
      if (txStartCounter < 0) {
        throw new TransactionException(
            "Transaction was committed more times than it was started.");
      }
    }
  }

  public RecordAbstract getRecord(final RID rid) {
    final RecordOperation e = getRecordEntry(rid);
    if (e != null) {
      if (e.type == RecordOperation.DELETED) {
        return FrontendTransactionAbstract.DELETED_RECORD;
      } else {
        assert e.record.getSession() == database;
        return e.record;
      }
    }
    return null;
  }

  /**
   * Called by class iterator.
   */
  public List<RecordOperation> getNewRecordEntriesByClass(
      final SchemaClass iClass, final boolean iPolymorphic) {
    final List<RecordOperation> result = new ArrayList<>();

    if (iClass == null)
    // RETURN ALL THE RECORDS
    {
      for (RecordOperation entry : recordOperations.values()) {
        if (entry.type == RecordOperation.CREATED) {
          result.add(entry);
        }
      }
    } else {
      // FILTER RECORDS BY CLASSNAME
      for (RecordOperation entry : recordOperations.values()) {
        if (entry.type == RecordOperation.CREATED) {
          if (entry.record != null) {
            if (entry.record instanceof EntityImpl) {
              if (iPolymorphic) {
                if (iClass.isSuperClassOf(
                    EntityInternalUtils.getImmutableSchemaClass(((EntityImpl) entry.record)))) {
                  result.add(entry);
                }
              } else {
                if (iClass.getName().equals(((EntityImpl) entry.record).getClassName())) {
                  result.add(entry);
                }
              }
            }
          }
        }
      }
    }

    return result;
  }

  /**
   * Called by cluster iterator.
   */
  public List<RecordOperation> getNewRecordEntriesByClusterIds(final int[] iIds) {
    final List<RecordOperation> result = new ArrayList<>();

    if (iIds == null)
    // RETURN ALL THE RECORDS
    {
      for (RecordOperation entry : recordOperations.values()) {
        if (entry.type == RecordOperation.CREATED) {
          result.add(entry);
        }
      }
    } else
    // FILTER RECORDS BY ID
    {
      for (RecordOperation entry : recordOperations.values()) {
        for (int id : iIds) {
          if (entry.record != null) {
            if (entry.record.getIdentity().getClusterId() == id
                && entry.type == RecordOperation.CREATED) {
              result.add(entry);
              break;
            }
          }
        }
      }
    }

    return result;
  }

  public void clearIndexEntries() {
    indexEntries.clear();
    recordIndexOperations.clear();
  }

  public List<String> getInvolvedIndexes() {
    List<String> list = null;
    for (String indexName : indexEntries.keySet()) {
      if (list == null) {
        list = new ArrayList<>();
      }
      list.add(indexName);
    }
    return list;
  }

  public EntityImpl getIndexChanges() {

    final EntityImpl result = new EntityImpl().setAllowChainedAccess(false)
        .setTrackingChanges(false);

    for (Entry<String, FrontendTransactionIndexChanges> indexEntry : indexEntries.entrySet()) {
      final EntityImpl indexDoc = new EntityImpl().setTrackingChanges(false);
      EntityInternalUtils.addOwner(indexDoc, result);

      result.field(indexEntry.getKey(), indexDoc, PropertyType.EMBEDDED);

      if (indexEntry.getValue().cleared) {
        indexDoc.field("clear", Boolean.TRUE);
      }

      final List<EntityImpl> entries = new ArrayList<>();
      indexDoc.field("entries", entries, PropertyType.EMBEDDEDLIST);

      // STORE INDEX ENTRIES
      for (FrontendTransactionIndexChangesPerKey entry : indexEntry.getValue().changesPerKey.values()) {
        entries.add(serializeIndexChangeEntry(entry, indexDoc));
      }

      indexDoc.field(
          "nullEntries", serializeIndexChangeEntry(indexEntry.getValue().nullKeyChanges, indexDoc));
    }

    indexEntries.clear();

    return result;
  }

  public Map<String, FrontendTransactionIndexChanges> getIndexOperations() {
    return indexEntries;
  }

  public FrontendTransactionIndexChanges getIndexChangesInternal(final String indexName) {
    if (getDatabase().isRemote()) {
      return null;
    }
    return getIndexChanges(indexName);
  }

  public void addIndexEntry(
      final Index delegate,
      final String iIndexName,
      final FrontendTransactionIndexChanges.OPERATION iOperation,
      final Object key,
      final Identifiable iValue) {
    // index changes are tracked on server in case of client-server deployment
    assert database.getStorage() instanceof AbstractPaginatedStorage;

    changed = true;

    try {
      FrontendTransactionIndexChanges indexEntry = indexEntries.get(iIndexName);
      if (indexEntry == null) {
        indexEntry = new FrontendTransactionIndexChanges();
        indexEntries.put(iIndexName, indexEntry);
      }

      if (iOperation == OPERATION.CLEAR) {
        indexEntry.setCleared();
      } else {
        FrontendTransactionIndexChangesPerKey changes = indexEntry.getChangesPerKey(key);
        changes.add(iValue, iOperation);

        if (changes.key == key
            && key instanceof ChangeableIdentity changeableIdentity
            && changeableIdentity.canChangeIdentity()) {
          changeableIdentity.addIdentityChangeListener(indexEntry);
        }

        if (iValue == null) {
          return;
        }

        List<FrontendTransactionRecordIndexOperation> transactionIndexOperations =
            recordIndexOperations.get(iValue.getIdentity());

        if (transactionIndexOperations == null) {
          transactionIndexOperations = new ArrayList<>();
          recordIndexOperations.put(((RecordId) iValue.getIdentity()).copy(),
              transactionIndexOperations);
        }

        transactionIndexOperations.add(
            new FrontendTransactionRecordIndexOperation(iIndexName, key, iOperation));
      }
    } catch (Exception e) {
      rollback(true, 0);
      throw e;
    }
  }

  /**
   * Buffer sizes index changes to be flushed at commit time.
   */
  public FrontendTransactionIndexChanges getIndexChanges(final String iIndexName) {
    return indexEntries.get(iIndexName);
  }

  @Override
  public int amountOfNestedTxs() {
    return txStartCounter;
  }

  public void rollback() {
    rollback(false, -1);
  }

  public void internalRollback() {
    status = TXSTATUS.ROLLBACKING;

    if (isWriteTransaction()) {
      database.transactionMeters()
          .writeRollbackTransactions()
          .record();
    }

    invalidateChangesInCache();

    close();
    status = TXSTATUS.ROLLED_BACK;
  }

  private void invalidateChangesInCache() {
    for (final RecordOperation v : recordOperations.values()) {
      final RecordAbstract rec = v.record;
      RecordInternal.unsetDirty(rec);
      rec.unload();
    }

    var localCache = database.getLocalCache();
    localCache.unloadRecords();
    localCache.clear();
  }

  @Override
  public void rollback(boolean force, int commitLevelDiff) {
    if (txStartCounter < 0) {
      throw new StorageException("Invalid value of TX counter");
    }
    checkTransactionValid();

    txStartCounter += commitLevelDiff;
    status = TXSTATUS.ROLLBACKING;

    if (!force && txStartCounter > 0) {
      return;
    }

    if (database.isRemote()) {
      final Storage storage = database.getStorage();
      ((StorageProxy) storage).rollback(TransactionOptimistic.this);
    }

    internalRollback();
  }

  @Override
  public boolean exists(RID rid) {
    checkTransactionValid();

    final DBRecord txRecord = getRecord(rid);
    if (txRecord == FrontendTransactionAbstract.DELETED_RECORD) {
      return false;
    }

    if (txRecord != null) {
      return true;
    }

    return database.executeExists(rid);
  }

  @Override
  public @Nonnull DBRecord loadRecord(RID rid) {

    checkTransactionValid();

    final RecordAbstract txRecord = getRecord(rid);
    if (txRecord == FrontendTransactionAbstract.DELETED_RECORD) {
      // DELETED IN TX
      throw new RecordNotFoundException(rid);
    }

    if (txRecord != null) {
      return txRecord;
    }

    if (rid.isTemporary()) {
      throw new RecordNotFoundException(rid);
    }

    // DELEGATE TO THE STORAGE, NO TOMBSTONES SUPPORT IN TX MODE
    return database.executeReadRecord((RecordId) rid);
  }

  public void deleteRecord(final RecordAbstract iRecord) {
    try {
      var records = RecordInternal.getDirtyManager(iRecord).getUpdateRecords();
      final var newRecords = RecordInternal.getDirtyManager(iRecord).getNewRecords();
      var recordsMap = new HashMap<>(16);

      if (records != null) {
        for (var rec : records) {
          rec = rec.getRecord();
          var prev = recordsMap.put(rec.getIdentity(), rec);

          if (prev != null && prev != rec) {
            var db = getDatabase();
            throw new IllegalStateException(
                "Database :"
                    + db.getName()
                    + " .For record "
                    + rec
                    + " second instance of record  "
                    + prev
                    + " was registered in dirty manager, such case may lead to data corruption");
          }

          saveRecord(rec, null);
        }
      }

      if (newRecords != null) {
        for (var rec : newRecords) {
          rec = rec.getRecord();
          var prev = recordsMap.put(rec.getIdentity(), rec);
          if (prev != null && prev != rec) {
            var db = getDatabase();
            throw new IllegalStateException(
                "Database :"
                    + db.getName()
                    + " .For record "
                    + rec
                    + " second instance of record  "
                    + prev
                    + " was registered in dirty manager, such case may lead to data corruption");
          }
          saveRecord(rec, null);
        }
      }

      addRecord(iRecord, RecordOperation.DELETED, null);
    } catch (Exception e) {
      rollback(true, 0);
      throw e;
    }
  }

  public DBRecord saveRecord(RecordAbstract passedRecord, final String clusterName) {
    try {
      if (passedRecord == null) {
        return null;
      }
      if (passedRecord.isUnloaded()) {
        throw new DatabaseException(
            "Record "
                + passedRecord
                + " is not bound to session, please call "
                + DatabaseSession.class.getSimpleName()
                + ".bindToSession(record) before changing it");
      }
      // fetch primary record if the record is a proxy record.
      passedRecord = passedRecord.getRecord();

      var recordsMap = new HashMap<>(16);
      recordsMap.put(passedRecord.getIdentity(), passedRecord);

      boolean originalSaved = false;
      final DirtyManager dirtyManager = RecordInternal.getDirtyManager(passedRecord);
      do {
        final var newRecord = dirtyManager.getNewRecords();
        final var updatedRecord = dirtyManager.getUpdateRecords();
        dirtyManager.clear();
        if (newRecord != null) {
          for (RecordAbstract rec : newRecord) {
            rec = rec.getRecord();

            var prev = recordsMap.put(rec.getIdentity(), rec);
            if (prev != null && prev != rec) {
              var db = getDatabase();
              throw new IllegalStateException(
                  "Database :"
                      + db.getName()
                      + " .For record "
                      + rec
                      + " second instance of record  "
                      + prev
                      + " was registered in dirty manager, such case may lead to data corruption");
            }

            if (rec instanceof EntityImpl) {
              EntityInternalUtils.convertAllMultiValuesToTrackedVersions((EntityImpl) rec);
            }
            if (rec == passedRecord) {
              addRecord(rec, RecordOperation.CREATED, clusterName);
              originalSaved = true;
            } else {
              addRecord(rec, RecordOperation.CREATED, database.getClusterName(rec));
            }
          }
        }
        if (updatedRecord != null) {
          for (var rec : updatedRecord) {
            rec = rec.getRecord();

            var prev = recordsMap.put(rec.getIdentity(), rec);
            if (prev != null && prev != rec) {
              var db = getDatabase();
              throw new IllegalStateException(
                  "Database :"
                      + db.getName()
                      + " .For record "
                      + rec
                      + " second instance of record  "
                      + prev
                      + " was registered in dirty manager, such case may lead to data corruption");
            }

            if (rec instanceof EntityImpl) {
              EntityInternalUtils.convertAllMultiValuesToTrackedVersions((EntityImpl) rec);
            }
            if (rec == passedRecord) {
              final byte operation =
                  passedRecord.getIdentity().isValid()
                      ? RecordOperation.UPDATED
                      : RecordOperation.CREATED;

              addRecord(rec, operation, clusterName);
              originalSaved = true;
            } else {
              addRecord(rec, RecordOperation.UPDATED, database.getClusterName(rec));
            }
          }
        }
      } while (dirtyManager.getNewRecords() != null || dirtyManager.getUpdateRecords() != null);

      if (!originalSaved && passedRecord.isDirty()) {
        final byte operation =
            passedRecord.getIdentity().isValid()
                ? RecordOperation.UPDATED
                : RecordOperation.CREATED;
        addRecord(passedRecord, operation, clusterName);
      }
      return passedRecord;
    } catch (Exception e) {
      rollback(true, 0);
      throw e;
    }
  }

  @Override
  public String toString() {
    return "TransactionOptimistic [id="
        + id
        + ", status="
        + status
        + ", recEntries="
        + recordOperations.size()
        + ", idxEntries="
        + indexEntries.size()
        + ']';
  }

  public void setStatus(final TXSTATUS iStatus) {
    status = iStatus;
  }

  public void addRecord(RecordAbstract record, byte status, String clusterName) {
    if (record.isUnloaded()) {
      throw new DatabaseException(
          "Record "
              + record
              + " is not bound to session, please call "
              + DatabaseSession.class.getSimpleName()
              + ".bindToSession(record) before changing it");
    }
    changed = true;
    checkTransactionValid();

    if (clusterName == null) {
      clusterName = database.getClusterNameById(record.getIdentity().getClusterId());
    }

    try {
      final RecordId rid = record.getIdentity();
      RecordOperation txEntry = getRecordEntry(rid);

      if (txEntry != null) {
        if (txEntry.record != record) {
          throw new TransactionException(
              "Found record in transaction with the same RID but different instance");
        }
      }

      if (status == RecordOperation.CREATED && txEntry != null) {
        status = RecordOperation.UPDATED;
      }
      switch (status) {
        case RecordOperation.CREATED: {
          Identifiable res = database.beforeCreateOperations(record, clusterName);
          if (res != null) {
            record = (RecordAbstract) res;
          }
        }
        break;
        case RecordOperation.UPDATED: {
          Identifiable res = database.beforeUpdateOperations(record, clusterName);
          if (res != null) {
            record = (RecordAbstract) res;
          }
        }
        break;
        case RecordOperation.DELETED:
          database.beforeDeleteOperations(record, clusterName);
          break;
      }

      try {
        if (!rid.isValid()) {
          database.assignAndCheckCluster(record, clusterName);
          rid.setClusterPosition(newRecordsPositionsGenerator--);
        }
        if (txEntry == null) {
          if (!(rid.isTemporary() && status != RecordOperation.CREATED)) {
            // NEW ENTRY: JUST REGISTER IT
            txEntry = new RecordOperation(record, status);
            recordOperations.put(rid.copy(), txEntry);
          }
        } else {
          // UPDATE PREVIOUS STATUS
          txEntry.record = record;

          switch (txEntry.type) {
            case RecordOperation.UPDATED:
              if (status == RecordOperation.DELETED) {
                txEntry.type = RecordOperation.DELETED;
              }
              break;
            case RecordOperation.DELETED:
              break;
            case RecordOperation.CREATED:
              if (status == RecordOperation.DELETED) {
                recordOperations.remove(rid);
              }
              break;
          }
        }

        switch (status) {
          case RecordOperation.CREATED:
            database.afterCreateOperations(record);
            break;
          case RecordOperation.UPDATED:
            database.afterUpdateOperations(record);
            break;
          case RecordOperation.DELETED:
            database.afterDeleteOperations(record);
            break;
        }

        // RESET TRACKING
        if (record instanceof EntityImpl && ((EntityImpl) record).isTrackingChanges()) {
          EntityInternalUtils.clearTrackData(((EntityImpl) record));
        }
      } catch (final Exception e) {
        switch (status) {
          case RecordOperation.CREATED:
            database.callbackHooks(TYPE.CREATE_FAILED, record);
            break;
          case RecordOperation.UPDATED:
            database.callbackHooks(TYPE.UPDATE_FAILED, record);
            break;
          case RecordOperation.DELETED:
            database.callbackHooks(TYPE.DELETE_FAILED, record);
            break;
        }
        throw BaseException.wrapException(
            new DatabaseException("Error on saving record " + record.getIdentity()), e);
      }
    } finally {
      switch (status) {
        case RecordOperation.CREATED:
          database.callbackHooks(TYPE.FINALIZE_CREATION, record);
          break;
        case RecordOperation.UPDATED:
          database.callbackHooks(TYPE.FINALIZE_UPDATE, record);
          break;
        case RecordOperation.DELETED:
          database.callbackHooks(TYPE.FINALIZE_DELETION, record);
          break;
      }
    }
  }

  private void doCommit() {
    if (status == TXSTATUS.ROLLED_BACK || status == TXSTATUS.ROLLBACKING) {
      if (status == TXSTATUS.ROLLBACKING) {
        internalRollback();
      }

      throw new RollbackException(
          "Given transaction was rolled back, and thus cannot be committed.");
    }

    try {
      status = TXSTATUS.COMMITTING;

      if (sentToServer || isWriteTransaction()) {
        database.internalCommit(this);
        database.transactionMeters()
            .writeTransactions()
            .record();

        try {
          database.afterCommitOperations();
        } catch (Exception e) {
          LogManager.instance().error(this,
              "Error during after commit callback invocation", e);
        }
      }

    } catch (Exception e) {
      rollback(true, 0);
      throw e;
    }

    close();
    status = TXSTATUS.COMPLETED;
  }

  private boolean isWriteTransaction() {
    return !recordOperations.isEmpty() || !indexEntries.isEmpty();
  }

  public void resetChangesTracking() {
    isAlreadyStartedOnServer = true;
    changed = false;
  }

  @Override
  public void close() {
    final LocalRecordCache dbCache = database.getLocalCache();
    for (RecordOperation txEntry : recordOperations.values()) {
      var record = txEntry.record;

      if (!record.isUnloaded()) {
        if (record instanceof EntityImpl entity) {
          EntityInternalUtils.clearTransactionTrackData(entity);
        }

        RecordInternal.unsetDirty(record);
        record.unload();
      }
    }

    dbCache.unloadRecords();
    dbCache.clear();

    clearUnfinishedChanges();

    status = TXSTATUS.INVALID;
  }

  private void clearUnfinishedChanges() {
    recordOperations.clear();
    indexEntries.clear();
    recordIndexOperations.clear();

    newRecordsPositionsGenerator = -2;

    database.setDefaultTransactionMode();
    userData.clear();
  }

  public void updateIdentityAfterCommit(final RecordId oldRid, final RecordId newRid) {
    if (oldRid.equals(newRid))
    // NO CHANGE, IGNORE IT
    {
      return;
    }

    // XXX: Identity update may mutate the index keys, so we have to identify and reinsert
    // potentially affected index keys to keep
    // the FrontendTransactionIndexChanges.changesPerKey in a consistent state.

    final List<KeyChangesUpdateRecord> keyRecordsToReinsert = new ArrayList<>();
    final DatabaseSessionInternal database = getDatabase();
    final IndexManagerAbstract indexManager = database.getMetadata().getIndexManagerInternal();
    for (Entry<String, FrontendTransactionIndexChanges> entry : indexEntries.entrySet()) {
      final Index index = indexManager.getIndex(database, entry.getKey());
      if (index == null) {
        throw new TransactionException(
            "Cannot find index '" + entry.getValue() + "' while committing transaction");
      }

      final Dependency[] fieldRidDependencies = getIndexFieldRidDependencies(index);
      if (!isIndexMayDependOnRids(fieldRidDependencies)) {
        continue;
      }

      final FrontendTransactionIndexChanges indexChanges = entry.getValue();
      for (final Iterator<FrontendTransactionIndexChangesPerKey> iterator =
          indexChanges.changesPerKey.values().iterator();
          iterator.hasNext(); ) {
        final FrontendTransactionIndexChangesPerKey keyChanges = iterator.next();
        if (isIndexKeyMayDependOnRid(keyChanges.key, oldRid, fieldRidDependencies)) {
          keyRecordsToReinsert.add(new KeyChangesUpdateRecord(keyChanges, indexChanges));
          iterator.remove();

          if (keyChanges.key instanceof ChangeableIdentity changeableIdentity) {
            changeableIdentity.removeIdentityChangeListener(indexChanges);
          }
        }
      }
    }

    // Update the identity.

    final RecordOperation rec = getRecordEntry(oldRid);
    if (rec != null) {
      txGeneratedRealRecordIdMap.put(newRid.copy(), oldRid.copy());

      if (!rec.record.getIdentity().equals(newRid)) {
        final RecordId recordId = rec.record.getIdentity();
        if (recordId == null) {
          RecordInternal.setIdentity(rec.record, new RecordId(newRid));
        } else {
          recordId.setClusterPosition(newRid.getClusterPosition());
          recordId.setClusterId(newRid.getClusterId());
        }
      }
    }

    // Reinsert the potentially affected index keys.

    for (KeyChangesUpdateRecord record : keyRecordsToReinsert) {
      record.indexChanges.changesPerKey.put(record.keyChanges.key, record.keyChanges);
    }

    // Update the indexes.

    RecordOperation val = getRecordEntry(oldRid);
    final List<FrontendTransactionRecordIndexOperation> transactionIndexOperations =
        recordIndexOperations.get(val != null ? val.getRecordId() : null);
    if (transactionIndexOperations != null) {
      for (final FrontendTransactionRecordIndexOperation indexOperation : transactionIndexOperations) {
        FrontendTransactionIndexChanges indexEntryChanges = indexEntries.get(indexOperation.index);
        if (indexEntryChanges == null) {
          continue;
        }
        final FrontendTransactionIndexChangesPerKey keyChanges;
        if (indexOperation.key == null) {
          keyChanges = indexEntryChanges.nullKeyChanges;
        } else {
          keyChanges = indexEntryChanges.changesPerKey.get(indexOperation.key);
        }
        if (keyChanges != null) {
          updateChangesIdentity(oldRid, newRid, keyChanges);
        }
      }
    }
  }

  private EntityImpl serializeIndexChangeEntry(
      FrontendTransactionIndexChangesPerKey entry, final EntityImpl indexDoc) {
    // SERIALIZE KEY

    EntityImpl keyContainer = new EntityImpl();
    keyContainer.setTrackingChanges(false);

    if (entry.key != null) {
      if (entry.key instanceof CompositeKey) {
        final List<Object> keys = ((CompositeKey) entry.key).getKeys();

        keyContainer.field("key", keys, PropertyType.EMBEDDEDLIST);
        keyContainer.field("binary", false);
      } else {
        keyContainer.field("key", entry.key);
        keyContainer.field("binary", false);
      }

    } else {
      keyContainer = null;
    }

    final List<EntityImpl> operations = new ArrayList<>();

    // SERIALIZE VALUES
    if (!entry.isEmpty()) {
      for (TransactionIndexEntry e : entry.getEntriesAsList()) {

        final EntityImpl changeDoc = new EntityImpl().setAllowChainedAccess(false);
        EntityInternalUtils.addOwner(changeDoc, indexDoc);

        // SERIALIZE OPERATION
        changeDoc.field("o", e.getOperation().ordinal());

        if (e.getValue() instanceof DBRecord && e.getValue().getIdentity().isNew()) {
          DBRecord saved = getRecord(e.getValue().getIdentity());
          if (saved != null && saved != FrontendTransactionAbstract.DELETED_RECORD) {
            e.setValue(saved);
          } else {
            ((DBRecord) e.getValue()).save();
          }
        }

        changeDoc.field("v", e.getValue() != null ? e.getValue().getIdentity() : null);

        operations.add(changeDoc);
      }
    }
    EntityImpl res = new EntityImpl();
    res.setTrackingChanges(false);
    EntityInternalUtils.addOwner(res, indexDoc);
    return res.setAllowChainedAccess(false)
        .field("k", keyContainer, PropertyType.EMBEDDED)
        .field("ops", operations, PropertyType.EMBEDDEDLIST);
  }

  private void updateChangesIdentity(
      RID oldRid, RID newRid, FrontendTransactionIndexChangesPerKey changesPerKey) {
    if (changesPerKey == null) {
      return;
    }

    for (final TransactionIndexEntry indexEntry : changesPerKey.getEntriesAsList()) {
      if (indexEntry.getValue().getIdentity().equals(oldRid)) {
        indexEntry.setValue(newRid);
      }
    }
  }

  @Override
  public void setCustomData(String iName, Object iValue) {
    userData.put(iName, iValue);
  }

  @Override
  public Object getCustomData(String iName) {
    return userData.get(iName);
  }

  private static Dependency[] getIndexFieldRidDependencies(Index index) {
    final IndexDefinition definition = index.getDefinition();

    if (definition == null) { // type for untyped index is still not resolved
      return null;
    }

    final PropertyType[] types = definition.getTypes();
    final Dependency[] dependencies = new Dependency[types.length];

    for (int i = 0; i < types.length; ++i) {
      dependencies[i] = getTypeRidDependency(types[i]);
    }

    return dependencies;
  }

  private static boolean isIndexMayDependOnRids(Dependency[] fieldDependencies) {
    if (fieldDependencies == null) {
      return true;
    }

    for (Dependency dependency : fieldDependencies) {
      switch (dependency) {
        case Unknown:
        case Yes:
          return true;
        case No:
          break; // do nothing
      }
    }

    return false;
  }

  private static boolean isIndexKeyMayDependOnRid(
      Object key, RID rid, Dependency[] keyDependencies) {
    if (key instanceof CompositeKey) {
      final List<Object> subKeys = ((CompositeKey) key).getKeys();
      for (int i = 0; i < subKeys.size(); ++i) {
        if (isIndexKeyMayDependOnRid(
            subKeys.get(i), rid, keyDependencies == null ? null : keyDependencies[i])) {
          return true;
        }
      }
      return false;
    }

    return isIndexKeyMayDependOnRid(key, rid, keyDependencies == null ? null : keyDependencies[0]);
  }

  private static boolean isIndexKeyMayDependOnRid(Object key, RID rid, Dependency dependency) {
    if (dependency == Dependency.No) {
      return false;
    }

    if (key instanceof Identifiable) {
      return key.equals(rid);
    }

    return dependency == Dependency.Unknown || dependency == null;
  }

  private static Dependency getTypeRidDependency(PropertyType type) {
    // fallback to the safest variant, just in case
    return switch (type) {
      case CUSTOM, ANY -> Dependency.Unknown;
      case EMBEDDED, LINK -> Dependency.Yes;
      case LINKLIST, LINKSET, LINKMAP, LINKBAG, EMBEDDEDLIST, EMBEDDEDSET, EMBEDDEDMAP ->
        // under normal conditions, collection field type is already resolved to its
        // component type
          throw new IllegalStateException("Collection field type is not allowed here");
      default -> // all other primitive types which doesn't depend on rids
          Dependency.No;
    };
  }

  private enum Dependency {
    Unknown,
    Yes,
    No
  }

  private static class KeyChangesUpdateRecord {

    final FrontendTransactionIndexChangesPerKey keyChanges;
    final FrontendTransactionIndexChanges indexChanges;

    KeyChangesUpdateRecord(
        FrontendTransactionIndexChangesPerKey keyChanges,
        FrontendTransactionIndexChanges indexChanges) {
      this.keyChanges = keyChanges;
      this.indexChanges = indexChanges;
    }
  }

  protected void checkTransactionValid() {
    if (status == TXSTATUS.INVALID) {
      throw new TransactionException(
          "Invalid state of the transaction. The transaction must be begun.");
    }
  }

  public boolean isChanged() {
    return changed;
  }

  public boolean isStartedOnServer() {
    return isAlreadyStartedOnServer;
  }

  public void setSentToServer(boolean sentToServer) {
    this.sentToServer = sentToServer;
  }

  public long getId() {
    return id;
  }

  public void clearRecordEntries() {
  }

  public void restore() {
  }

  @Override
  public int getEntryCount() {
    return recordOperations.size();
  }

  public Collection<RecordOperation> getCurrentRecordEntries() {
    return recordOperations.values();
  }

  public Collection<RecordOperation> getRecordOperations() {
    return recordOperations.values();
  }

  public RecordOperation getRecordEntry(RID ridPar) {
    RID rid = ridPar;
    RecordOperation entry;
    do {
      entry = recordOperations.get(rid);
      if (entry == null) {
        rid = txGeneratedRealRecordIdMap.get(rid);
      }
    } while (entry == null && rid != null && !rid.equals(ridPar));
    return entry;
  }

  public Map<RecordId, RecordId> getTxGeneratedRealRecordIdMap() {
    return txGeneratedRealRecordIdMap;
  }

  @Override
  @Nullable
  public byte[] getMetadata() {
    if (metadata != null) {
      return metadata.metadata();
    }
    return null;
  }

  @Override
  public void storageBegun() {
    if (metadata != null) {
      metadata.notifyMetadataRead();
    }
  }

  @Override
  public void setMetadataHolder(FrontendTransacationMetadataHolder metadata) {
    this.metadata = metadata;
  }

  @Override
  public void prepareSerializedOperations() throws IOException {
    List<byte[]> operations = new ArrayList<>();
    for (RecordOperation value : recordOperations.values()) {
      FrontendTransactionDataChange change = new FrontendTransactionDataChange(database, value);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      change.serialize(new DataOutputStream(out));
      operations.add(out.toByteArray());
    }
    this.serializedOperations = operations;
  }

  public Iterator<byte[]> getSerializedOperations() {
    if (serializedOperations != null) {
      return serializedOperations.iterator();
    } else {
      return Collections.emptyIterator();
    }
  }

  @Override
  public void resetAllocatedIds() {
    for (Map.Entry<RecordId, RecordOperation> op : recordOperations.entrySet()) {
      if (op.getValue().type == RecordOperation.CREATED) {
        var lastCreateId = op.getValue().getRecordId().copy();
        RecordId oldNew =
            new RecordId(lastCreateId.getClusterId(), op.getKey().getClusterPosition());
        updateIdentityAfterCommit(lastCreateId, oldNew);
        txGeneratedRealRecordIdMap.put(oldNew, op.getKey());
      }
    }
  }

  public void fill(final Iterator<RecordOperation> operations) {
    while (operations.hasNext()) {
      RecordOperation change = operations.next();
      recordOperations.put(change.getRecordId(), change);
      resolveTracking(change);
    }
  }

  protected void resolveTracking(final RecordOperation change) {
    if (!(change.record instanceof EntityImpl rec)) {
      return;
    }

    switch (change.type) {
      case RecordOperation.CREATED: {
        final EntityImpl entity = (EntityImpl) change.record;
        LiveQueryHook.addOp(entity, RecordOperation.CREATED, database);
        LiveQueryHookV2.addOp(database, entity, RecordOperation.CREATED);
        final SchemaImmutableClass clazz = EntityInternalUtils.getImmutableSchemaClass(entity);
        if (clazz != null) {
          ClassIndexManager.processIndexOnCreate(database, rec);
          if (clazz.isFunction()) {
            database.getSharedContext().getFunctionLibrary().createdFunction(entity);
          }
          if (clazz.isSequence()) {
            ((SequenceLibraryProxy) database.getMetadata().getSequenceLibrary())
                .getDelegate()
                .onSequenceCreated(database, entity);
          }
          if (clazz.isScheduler()) {
            database.getMetadata().getScheduler()
                .scheduleEvent(database, new ScheduledEvent(entity, database));
          }
        }
      }
      break;
      case RecordOperation.UPDATED: {
        final Identifiable updateRecord = change.record;
        EntityImpl updateDoc = (EntityImpl) updateRecord;
        LiveQueryHook.addOp(updateDoc, RecordOperation.UPDATED, database);
        LiveQueryHookV2.addOp(database, updateDoc, RecordOperation.UPDATED);
        final SchemaImmutableClass clazz = EntityInternalUtils.getImmutableSchemaClass(updateDoc);
        if (clazz != null) {
          ClassIndexManager.processIndexOnUpdate(database, updateDoc);
          if (clazz.isFunction()) {
            database.getSharedContext().getFunctionLibrary().updatedFunction(updateDoc);
          }
        }
      }
      break;
      case RecordOperation.DELETED: {
        final EntityImpl entity = (EntityImpl) change.record;
        final SchemaImmutableClass clazz = EntityInternalUtils.getImmutableSchemaClass(entity);
        if (clazz != null) {
          ClassIndexManager.processIndexOnDelete(database, rec);
          if (clazz.isFunction()) {
            database.getSharedContext().getFunctionLibrary().droppedFunction(entity);
            database
                .getSharedContext()
                .getYouTrackDB()
                .getScriptManager()
                .close(database.getName());
          }
          if (clazz.isSequence()) {
            ((SequenceLibraryProxy) database.getMetadata().getSequenceLibrary())
                .getDelegate()
                .onSequenceDropped(database, entity);
          }
          if (clazz.isScheduler()) {
            final String eventName = entity.field(ScheduledEvent.PROP_NAME);
            database.getSharedContext().getScheduler().removeEventInternal(eventName);
          }
        }
        LiveQueryHook.addOp(entity, RecordOperation.DELETED, database);
        LiveQueryHookV2.addOp(database, entity, RecordOperation.DELETED);
      }
      break;
      default:
        break;
    }
  }

  public int getTxStartCounter() {
    return txStartCounter;
  }
}
