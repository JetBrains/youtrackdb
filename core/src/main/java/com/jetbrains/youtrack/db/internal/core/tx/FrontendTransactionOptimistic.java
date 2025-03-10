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
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.id.ChangeableIdentity;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.index.ClassIndexManager;
import com.jetbrains.youtrack.db.internal.core.index.CompositeKey;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.index.IndexInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Rule;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.RecordInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializer;
import com.jetbrains.youtrack.db.internal.core.storage.StorageProxy;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractPaginatedStorage;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionIndexChanges.OPERATION;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class FrontendTransactionOptimistic extends FrontendTransactionAbstract implements
    TransactionInternal {

  private static final AtomicLong txSerial = new AtomicLong();

  protected final HashMap<RecordId, RecordId> generatedOriginalRecordIdMap = new HashMap<>();
  protected final HashMap<RecordId, RecordOperation> recordOperations = new HashMap<>();
  protected final HashMap<RecordId, RecordOperation> updatedOperations = new HashMap<>();

  protected HashMap<String, FrontendTransactionIndexChanges> indexEntries = new HashMap<>();
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
  private final boolean readOnly;

  public FrontendTransactionOptimistic(final DatabaseSessionInternal iDatabase) {
    this(iDatabase, false);
  }

  public FrontendTransactionOptimistic(final DatabaseSessionInternal iDatabase, boolean readOnly) {
    super(iDatabase);
    this.id = txSerial.incrementAndGet();
    this.readOnly = readOnly;
  }

  protected FrontendTransactionOptimistic(final DatabaseSessionInternal iDatabase, long id) {
    super(iDatabase);
    this.id = id;
    readOnly = false;
  }

  public int begin() {
    if (txStartCounter < 0) {
      throw new TransactionException(session, "Invalid value of TX counter: " + txStartCounter);
    }

    if (txStartCounter == 0) {
      status = TXSTATUS.BEGUN;

      session.transactionMeters()
          .totalTransactions()
          .record();
      var localCache = session.getLocalCache();
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
      throw new TransactionException(session.getDatabaseName(),
          "Invalid value of tx counter: " + txStartCounter);
    }
    if (force) {
      preProcessRecordsAndExecuteCallCallbacks();
      txStartCounter = 0;
    } else {
      if (txStartCounter == 1) {
        preProcessRecordsAndExecuteCallCallbacks();
      }
      txStartCounter--;
    }

    if (txStartCounter == 0) {
      doCommit();
    } else {
      if (txStartCounter < 0) {
        throw new TransactionException(session,
            "Transaction was committed more times than it was started.");
      }
    }
  }

  public RecordAbstract getRecord(final RID rid) {
    final var e = getRecordEntry(rid);
    if (e != null) {
      if (e.type == RecordOperation.DELETED) {
        return FrontendTransactionAbstract.DELETED_RECORD;
      } else {
        assert e.record.getSession() == session;
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
      for (var entry : recordOperations.values()) {
        if (entry.type == RecordOperation.CREATED) {
          result.add(entry);
        }
      }
    } else {
      // FILTER RECORDS BY CLASSNAME
      for (var entry : recordOperations.values()) {
        if (entry.type == RecordOperation.CREATED) {
          if (entry.record != null) {
            if (entry.record instanceof EntityImpl entity) {
              if (iPolymorphic) {
                var cls = entity.getImmutableSchemaClass(session);
                if (iClass.isSuperClassOf(session, cls)) {
                  result.add(entry);
                }
              } else {
                if (iClass.getName(session)
                    .equals(((EntityImpl) entry.record).getSchemaClassName())) {
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
      for (var entry : recordOperations.values()) {
        if (entry.type == RecordOperation.CREATED) {
          result.add(entry);
        }
      }
    } else
    // FILTER RECORDS BY ID
    {
      for (var entry : recordOperations.values()) {
        for (var id : iIds) {
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
    for (var indexName : indexEntries.keySet()) {
      if (list == null) {
        list = new ArrayList<>();
      }
      list.add(indexName);
    }
    return list;
  }

  public Map<String, FrontendTransactionIndexChanges> getIndexOperations() {
    return indexEntries;
  }

  public FrontendTransactionIndexChanges getIndexChangesInternal(final String indexName) {
    if (getDatabaseSession().isRemote()) {
      return null;
    }
    return getIndexChanges(indexName);
  }

  @Override
  public void addIndexEntry(
      final IndexInternal index,
      final String iIndexName,
      final OPERATION iOperation,
      final Object key,
      final Identifiable value) {
    // index changes are tracked on server in case of client-server deployment
    assert session.getStorage() instanceof AbstractPaginatedStorage;

    changed = true;
    try {
      var indexEntry = indexEntries.get(iIndexName);
      if (indexEntry == null) {
        indexEntry = new FrontendTransactionIndexChanges(index);
        indexEntries.put(iIndexName, indexEntry);
      }

      if (iOperation == OPERATION.CLEAR) {
        indexEntry.setCleared();
      } else {
        var changes = indexEntry.getChangesPerKey(key);
        changes.add(value, iOperation);

        if (changes.key == key
            && key instanceof ChangeableIdentity changeableIdentity
            && changeableIdentity.canChangeIdentity()) {
          changeableIdentity.addIdentityChangeListener(indexEntry);
        }

        if (value == null) {
          return;
        }

        var transactionIndexOperations =
            recordIndexOperations.get(value.getIdentity());

        if (transactionIndexOperations == null) {
          transactionIndexOperations = new ArrayList<>();
          recordIndexOperations.put(((RecordId) value.getIdentity()).copy(),
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
      session.transactionMeters()
          .writeRollbackTransactions()
          .record();
    }

    invalidateChangesInCache();

    close();
    status = TXSTATUS.ROLLED_BACK;
  }

  private void invalidateChangesInCache() {
    for (final var v : recordOperations.values()) {
      final var rec = v.record;
      RecordInternal.unsetDirty(rec);
      rec.unload();
    }

    var localCache = session.getLocalCache();
    localCache.unloadRecords();
    localCache.clear();
  }

  @Override
  public void rollback(boolean force, int commitLevelDiff) {
    if (txStartCounter < 0) {
      throw new TransactionException(session, "Invalid value of TX counter");
    }
    checkTransactionValid();

    txStartCounter += commitLevelDiff;
    status = TXSTATUS.ROLLBACKING;

    if (!force && txStartCounter > 0) {
      return;
    }

    if (session.isRemote()) {
      final var storage = session.getStorage();
      ((StorageProxy) storage).rollback(FrontendTransactionOptimistic.this);
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

    return session.executeExists(rid);
  }

  @Override
  public @Nonnull DBRecord loadRecord(RID rid) {

    checkTransactionValid();

    final var txRecord = getRecord(rid);
    if (txRecord == FrontendTransactionAbstract.DELETED_RECORD) {
      // DELETED IN TX
      throw new RecordNotFoundException(session, rid);
    }

    if (txRecord != null) {
      return txRecord;
    }

    if (rid.isTemporary()) {
      throw new RecordNotFoundException(session, rid);
    }

    // DELEGATE TO THE STORAGE, NO TOMBSTONES SUPPORT IN TX MODE
    return session.executeReadRecord((RecordId) rid);
  }

  public void deleteRecord(final RecordAbstract record) {
    try {
      addRecordOperation(record, RecordOperation.DELETED);
      //execute it here because after this operation record will be unloaded
      preProcessRecordsAndExecuteCallCallbacks();
    } catch (Exception e) {
      rollback(true, 0);
      throw e;
    }
  }

  @Override
  public String toString() {
    return "FrontendTransactionOptimistic [id="
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

  public void addRecordOperation(RecordAbstract record, byte status) {
    if (readOnly) {
      throw new DatabaseException(session, "Transaction is read-only");
    }

    try {
      if (record.isUnloaded()) {
        throw new DatabaseException(session,
            "Record "
                + record
                + " is not bound to session, please call "
                + DatabaseSession.class.getSimpleName()
                + ".bindToSession(record) before changing it");
      }
      if (record.isEmbedded()) {
        throw new DatabaseException(session,
            "Record "
                + record
                + " is embedded and can not added to list of records to be saved");
      }
      checkTransactionValid();
      var rid = record.getIdentity();

      if (rid.getClusterId() == RID.CLUSTER_ID_INVALID) {
        throw new TransactionException(session, "Invalid cluster id : " + rid);
      }
      if (!rid.isValid()) {
        var clusterName = session.getClusterNameById(record.getIdentity().getClusterId());
        session.assignAndCheckCluster(record, clusterName);
        rid.setClusterPosition(newRecordsPositionsGenerator--);
      }

      var txEntry = getRecordEntry(rid);
      try {
        if (txEntry == null) {
          if (rid.isTemporary() && status == RecordOperation.UPDATED) {
            throw new IllegalStateException(
                "Temporary records can not be added to the transaction");
          }
          if (record.txEntry != null) {
            throw new TransactionException(session,
                "Record is already in transaction with different associated transaction entry");
          }

          record.txEntry = txEntry;
          txEntry = new RecordOperation(record, status);
          recordOperations.put(txEntry.initialRecordId, txEntry);
          changed = true;
        } else {
          if (txEntry.record != record) {
            throw new TransactionException(session,
                "Found record in transaction with the same RID but different instance");
          }
          if (record.txEntry != null && record.txEntry != txEntry) {
            throw new TransactionException(session,
                "Record is already in transaction with different associated transaction entry");
          }
          record.txEntry = txEntry;

          switch (txEntry.type) {
            case RecordOperation.UPDATED:
              if (status == RecordOperation.DELETED) {
                txEntry.type = RecordOperation.DELETED;
                changed = true;
              } else if (status == RecordOperation.CREATED) {
                throw new IllegalStateException(
                    "Invalid operation, record can not be created as it is already updated");
              }
              break;
            case RecordOperation.DELETED:
              if (status == RecordOperation.UPDATED || status == RecordOperation.CREATED) {
                throw new IllegalStateException(
                    "Invalid operation, record can not be updated or created as it is already deleted");
              }
            case RecordOperation.CREATED:
              if (status == RecordOperation.DELETED) {
                txEntry.type = RecordOperation.DELETED;
                changed = true;
              } else if (status == RecordOperation.CREATED) {
                throw new IllegalStateException(
                    "Invalid operation, record can not be created as it is already created");
              }
              break;
          }
        }
      } catch (final Exception e) {
        throw BaseException.wrapException(
            new DatabaseException(session,
                "Error on execution of operation on record " + record.getIdentity()), e, session);
      }

      assert txEntry.recordCallBackDirtyCounter <= record.getDirtyCounter();
      if (txEntry.recordCallBackDirtyCounter < record.getDirtyCounter()) {
        changed = true;
        updatedOperations.put(txEntry.initialRecordId, txEntry);
      }
    } catch (Exception e) {
      rollback(true, 0);
      throw e;
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
        session.internalCommit(this);
        session.transactionMeters()
            .writeTransactions()
            .record();

        try {
          session.afterCommitOperations();
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

  public void preProcessRecordsAndExecuteCallCallbacks() {
    var serializer = session.getSerializer();

    List<RecordId> newDeletedRecords = null;
    while (changed) {
      changed = false;
      var operations = new ArrayList<>(updatedOperations.values());
      updatedOperations.clear();

      for (var recordOperation : operations) {
        preProcessRecordOperationAndExecuteCallbacks(recordOperation, serializer);
        if (recordOperation.type == RecordOperation.DELETED && recordOperation.record.getIdentity()
            .isNew()) {
          if (newDeletedRecords == null) {
            newDeletedRecords = new ArrayList<>();
          }
          newDeletedRecords.add(recordOperation.record.getIdentity());
        }
      }
    }

    if (newDeletedRecords != null) {
      for (var newDeletedRecord : newDeletedRecords) {
        recordOperations.remove(newDeletedRecord);
      }
    }

    assert updatedOperations.isEmpty();
  }

  private void preProcessRecordOperationAndExecuteCallbacks(RecordOperation recordOperation,
      RecordSerializer serializer) {
    var record = recordOperation.record;
    if (recordOperation.type == RecordOperation.CREATED
        || recordOperation.type == RecordOperation.UPDATED) {
      String className = null;
      EntityImpl entityImpl = null;
      if (recordOperation.record instanceof EntityImpl entity) {
        entityImpl = entity;
        className = entity.getSchemaClassName();
        if (recordOperation.recordCallBackDirtyCounter != record.getDirtyCounter()) {
          entity.checkClass(session);
          entity.checkAllMultiValuesAreTrackedVersions();

          if (recordOperation.type == RecordOperation.CREATED) {
            if (className != null) {
              session.checkSecurity(Rule.ResourceGeneric.CLASS, Role.PERMISSION_CREATE,
                  className);
            }
          } else {
            // UPDATE: CHECK ACCESS ON SCHEMA CLASS NAME (IF ANY)
            if (className != null) {
              session.checkSecurity(Rule.ResourceGeneric.CLASS, Role.PERMISSION_UPDATE,
                  className);
            }
          }
        }

        entity.recordFormat = serializer;
      }

      if (recordOperation.recordCallBackDirtyCounter != record.getDirtyCounter()) {
        if (recordOperation.type == RecordOperation.CREATED) {
          if (recordOperation.recordCallBackDirtyCounter == 0) {
            if (className != null) {
              ClassIndexManager.checkIndexesAfterCreate(entityImpl, session);
            }
            if (processRecordCreation(recordOperation, record)) {
              changed = true;
            }
          } else {
            if (className != null) {
              ClassIndexManager.checkIndexesAfterUpdate(entityImpl, session);
            }
            if (processRecordUpdate(recordOperation, record)) {
              changed = true;
            }
          }
        } else {
          if (className != null) {
            ClassIndexManager.checkIndexesAfterUpdate(entityImpl, session);
          }
          if (processRecordUpdate(recordOperation, record)) {
            changed = true;
          }
        }
      }
    } else if (recordOperation.type == RecordOperation.DELETED) {
      if (recordOperation.recordCallBackDirtyCounter != record.getDirtyCounter()) {
        String className = null;
        EntityImpl entityImpl = null;

        if (recordOperation.record instanceof EntityImpl entity) {
          entityImpl = entity;
          className = entity.getSchemaClassName();
          entity.checkClass(session);

          if (className != null) {
            session.checkSecurity(Rule.ResourceGeneric.CLASS, Role.PERMISSION_DELETE,
                className);
          }
        }
        processRecordDelete(recordOperation, record);
        if (className != null) {
          ClassIndexManager.checkIndexesAfterDelete(entityImpl, session);
        }
      }
    } else {
      throw new IllegalStateException(
          "Invalid record operation type " + recordOperation.type);
    }
  }

  private void processRecordDelete(RecordOperation recordOperation, RecordAbstract record) {
    var dirtyCounter = record.getDirtyCounter();

    var clusterName = session.getClusterNameById(record.getIdentity().getClusterId());
    recordOperation.recordCallBackDirtyCounter = dirtyCounter;

    session.beforeDeleteOperations(record, clusterName);
    try {
      session.afterDeleteOperations(record);
      if (record instanceof EntityImpl && ((EntityImpl) record).isTrackingChanges()) {
        ((EntityImpl) record).clearTrackData();
      }
    } catch (Exception e) {
      session.callbackHooks(TYPE.DELETE_FAILED, record);
      throw e;
    } finally {
      session.callbackHooks(TYPE.FINALIZE_DELETION, record);
    }
  }

  private boolean processRecordUpdate(RecordOperation recordOperation, RecordAbstract record) {
    var dirtyCounter = record.getDirtyCounter();
    var clusterName = session.getClusterNameById(record.getIdentity().getClusterId());

    recordOperation.recordCallBackDirtyCounter = dirtyCounter;
    session.beforeUpdateOperations(record, clusterName);
    try {
      session.afterUpdateOperations(record);
      if (record instanceof EntityImpl && ((EntityImpl) record).isTrackingChanges()) {
        ((EntityImpl) record).clearTrackData();
      }
    } catch (Exception e) {
      session.callbackHooks(TYPE.UPDATE_FAILED, record);
      throw e;
    } finally {
      session.callbackHooks(TYPE.FINALIZE_UPDATE, record);
    }

    return record.getDirtyCounter() != recordOperation.recordCallBackDirtyCounter;
  }

  private boolean processRecordCreation(RecordOperation recordOperation, RecordAbstract record) {
    session.assignAndCheckCluster(recordOperation.record, null);

    var clusterName = session.getClusterNameById(record.getIdentity().getClusterId());
    recordOperation.recordCallBackDirtyCounter = record.getDirtyCounter();
    session.beforeCreateOperations(record, clusterName);
    try {
      session.afterCreateOperations(record);
      if (record instanceof EntityImpl && ((EntityImpl) record).isTrackingChanges()) {
        ((EntityImpl) record).clearTrackData();
      }
    } catch (Exception e) {
      session.callbackHooks(TYPE.CREATE_FAILED, record);
      throw e;
    } finally {
      session.callbackHooks(TYPE.FINALIZE_CREATION, record);
    }

    return recordOperation.recordCallBackDirtyCounter != record.getDirtyCounter();
  }

  public void resetChangesTracking() {
    isAlreadyStartedOnServer = true;
    changed = false;
  }

  @Override
  public void close() {
    final var dbCache = session.getLocalCache();
    for (var txEntry : recordOperations.values()) {
      var record = txEntry.record;

      if (!record.isUnloaded()) {
        if (record instanceof EntityImpl entity) {
          entity.clearTransactionTrackData();
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

    session.setDefaultTransactionMode();
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
    final var database = getDatabaseSession();
    if (!database.isRemote()) {
      final var indexManager = database.getMetadata().getIndexManagerInternal();
      for (var entry : indexEntries.entrySet()) {
        final var index = indexManager.getIndex(database, entry.getKey());
        if (index == null) {
          throw new TransactionException(session,
              "Cannot find index '" + entry.getValue() + "' while committing transaction");
        }

        final var fieldRidDependencies = getIndexFieldRidDependencies(index);
        if (!isIndexMayDependOnRids(fieldRidDependencies)) {
          continue;
        }

        final var indexChanges = entry.getValue();
        for (final var iterator =
            indexChanges.changesPerKey.values().iterator();
            iterator.hasNext(); ) {
          final var keyChanges = iterator.next();
          if (isIndexKeyMayDependOnRid(keyChanges.key, oldRid, fieldRidDependencies)) {
            keyRecordsToReinsert.add(new KeyChangesUpdateRecord(keyChanges, indexChanges));
            iterator.remove();

            if (keyChanges.key instanceof ChangeableIdentity changeableIdentity) {
              changeableIdentity.removeIdentityChangeListener(indexChanges);
            }
          }
        }
      }
    }

    // Update the identity.

    final var rec = getRecordEntry(oldRid);
    if (rec != null) {
      generatedOriginalRecordIdMap.put(newRid.copy(), oldRid.copy());

      if (!rec.record.getIdentity().equals(newRid)) {
        final var recordId = rec.record.getIdentity();
        recordId.setClusterPosition(newRid.getClusterPosition());
        recordId.setClusterId(newRid.getClusterId());
      }
    }

    // Reinsert the potentially affected index keys.

    for (var record : keyRecordsToReinsert) {
      record.indexChanges.changesPerKey.put(record.keyChanges.key, record.keyChanges);
    }

    // Update the indexes.

    var val = getRecordEntry(oldRid);
    final var transactionIndexOperations =
        recordIndexOperations.get(val != null ? val.getRecordId() : null);
    if (transactionIndexOperations != null) {
      for (final var indexOperation : transactionIndexOperations) {
        var indexEntryChanges = indexEntries.get(indexOperation.index);
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

  private static void updateChangesIdentity(
      RID oldRid, RID newRid, FrontendTransactionIndexChangesPerKey changesPerKey) {
    if (changesPerKey == null) {
      return;
    }

    for (final var indexEntry : changesPerKey.getEntriesAsList()) {
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
  public boolean isReadOnly() {
    return readOnly;
  }

  @Override
  public Object getCustomData(String iName) {
    return userData.get(iName);
  }

  private static Dependency[] getIndexFieldRidDependencies(Index index) {
    final var definition = index.getDefinition();

    if (definition == null) { // type for untyped index is still not resolved
      return null;
    }

    final var types = definition.getTypes();
    final var dependencies = new Dependency[types.length];

    for (var i = 0; i < types.length; ++i) {
      dependencies[i] = getTypeRidDependency(types[i]);
    }

    return dependencies;
  }

  private static boolean isIndexMayDependOnRids(Dependency[] fieldDependencies) {
    if (fieldDependencies == null) {
      return true;
    }

    for (var dependency : fieldDependencies) {
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
      final var subKeys = ((CompositeKey) key).getKeys();
      for (var i = 0; i < subKeys.size(); ++i) {
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
      throw new TransactionException(session,
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
    assert ridPar instanceof RecordId;

    var rid = ridPar;
    RecordOperation entry;
    do {
      entry = recordOperations.get(rid);
      if (entry == null) {
        rid = generatedOriginalRecordIdMap.get(rid);
      }
    } while (entry == null && rid != null && !rid.equals(ridPar));

    return entry;
  }

  public Map<RecordId, RecordId> getGeneratedOriginalRecordIdMap() {
    return generatedOriginalRecordIdMap;
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

  public Iterator<byte[]> getSerializedOperations() {
    if (serializedOperations != null) {
      return serializedOperations.iterator();
    } else {
      return Collections.emptyIterator();
    }
  }

  public int getTxStartCounter() {
    return txStartCounter;
  }

  private boolean isWriteTransaction() {
    return !recordOperations.isEmpty() || !indexEntries.isEmpty();
  }
}
