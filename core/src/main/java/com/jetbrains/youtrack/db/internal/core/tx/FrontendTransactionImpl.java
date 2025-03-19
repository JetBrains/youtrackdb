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
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.LoadRecordResult;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.id.ChangeableIdentity;
import com.jetbrains.youtrack.db.internal.core.id.IdentityChangeListener;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.index.ClassIndexManager;
import com.jetbrains.youtrack.db.internal.core.index.CompositeKey;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Rule;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.RecordBytes;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializer;
import com.jetbrains.youtrack.db.internal.core.storage.StorageProxy;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractPaginatedStorage;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionIndexChanges.OPERATION;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class FrontendTransactionImpl implements
    IdentityChangeListener, FrontendTransaction {

  /**
   * Indicates the record deleted in a transaction.
   *
   * @see #getRecord(RID)
   */
  public static final RecordAbstract DELETED_RECORD = new RecordBytes(null);

  private static final AtomicLong txSerial = new AtomicLong();

  @Nonnull
  protected DatabaseSessionInternal session;
  protected TXSTATUS status = TXSTATUS.INVALID;

  protected final HashMap<RecordId, RecordOperation> recordOperations = new HashMap<>();
  private final IdentityHashMap<RecordId, RecordOperation> recordOperationsIdentityMap =
      new IdentityHashMap<>();
  protected final TreeSet<RecordId> recordsInTransaction = new TreeSet<>();

  protected final HashMap<RecordId, RecordOperation> operationsBetweenCallbacks = new HashMap<>();
  private final IdentityHashMap<RecordId, RecordOperation> operationsBetweenCallbacksIdentityMap =
      new IdentityHashMap<>();

  protected HashMap<RecordId, List<FrontendTransactionRecordIndexOperation>> recordIndexOperations =
      new HashMap<>();
  private final IdentityHashMap<RecordId, List<FrontendTransactionRecordIndexOperation>> recordIndexOperationsIdentityMap =
      new IdentityHashMap<>();

  protected HashMap<String, FrontendTransactionIndexChanges> indexEntries = new HashMap<>();

  protected final HashMap<RecordId, RecordId> originalChangedRecordIdMap = new HashMap<>();

  protected long id;
  protected int newRecordsPositionsGenerator = -2;
  private final HashMap<String, Object> userData = new HashMap<>();

  @Nullable
  private FrontendTransacationMetadataHolder metadata = null;
  @Nullable
  private List<byte[]> serializedOperations;

  protected int txStartCounter;
  protected boolean sentToServer = false;
  private final boolean readOnly;

  public FrontendTransactionImpl(final DatabaseSessionInternal iDatabase) {
    this(iDatabase, false);
  }

  public FrontendTransactionImpl(final DatabaseSessionInternal session, boolean readOnly) {
    this.session = session;
    this.id = txSerial.incrementAndGet();
    this.readOnly = readOnly;
  }

  public FrontendTransactionImpl(final DatabaseSessionInternal session, long txId,
      boolean readOnly) {
    this.session = session;
    this.id = txId;
    this.readOnly = readOnly;
  }


  protected FrontendTransactionImpl(final DatabaseSessionInternal session, long id) {
    this.session = session;
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
        return DELETED_RECORD;
      } else {
        assert e.record.getSession() == session;
        return e.record;
      }
    }
    return null;
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
    if (session.isRemote()) {
      return null;
    }

    return getIndexChanges(indexName);
  }

  @Override
  public void addIndexEntry(
      final Index index,
      final String iIndexName,
      final OPERATION iOperation,
      final Object key,
      final Identifiable value) {
    // index changes are tracked on server in case of client-server deployment
    assert session.getStorage() instanceof AbstractPaginatedStorage;

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
            recordIndexOperations.get((RecordId) value.getIdentity());

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
      rec.unsetDirty();
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
      ((StorageProxy) storage).rollback(FrontendTransactionImpl.this);
    }

    internalRollback();
  }

  @Override
  public boolean exists(RID rid) {
    checkTransactionValid();

    final DBRecord txRecord = getRecord(rid);
    if (txRecord == DELETED_RECORD) {
      return false;
    }

    if (txRecord != null) {
      return true;
    }

    return session.executeExists(rid);
  }

  @Override
  public @Nonnull LoadRecordResult loadRecord(RID rid) {
    checkTransactionValid();

    final var txRecord = getRecord(rid);
    if (txRecord == DELETED_RECORD) {
      // DELETED IN TX
      throw new RecordNotFoundException(session, rid);
    }

    if (txRecord != null) {
      return new LoadRecordResult(txRecord, null, null);
    }

    // DELEGATE TO THE STORAGE, NO TOMBSTONES SUPPORT IN TX MODE
    return session.executeReadRecord((RecordId) rid, false, false, true);
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

  public RecordOperation addRecordOperation(RecordAbstract record, byte status) {
    if (readOnly) {
      throw new DatabaseException(session, "Transaction is read-only");
    }

    RecordOperation txEntry;
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
        var clusterId = session.assignAndCheckCluster(record);
        rid.setClusterAndPosition(clusterId, newRecordsPositionsGenerator--);
      } else if (!rid.isValidPosition()) {
        rid.setClusterPosition(newRecordsPositionsGenerator--);
      }

      txEntry = getRecordEntry(rid);
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

          txEntry = new RecordOperation(record, status);
          record.txEntry = txEntry;

          recordOperations.put(record.getIdentity(), txEntry);
          recordsInTransaction.add(record.getIdentity());

          if (rid instanceof ChangeableIdentity changeableIdentity
              && changeableIdentity.canChangeIdentity()) {
            changeableIdentity.addIdentityChangeListener(this);
          }
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
              } else if (status == RecordOperation.CREATED) {
                throw new IllegalStateException(
                    "Invalid operation, record can not be created as it is already updated");
              }
              break;
            case RecordOperation.DELETED:
              throw new IllegalStateException(
                  "Invalid operation, record can not be updated, created or deleted as it is already deleted");
            case RecordOperation.CREATED:
              if (status == RecordOperation.DELETED) {
                txEntry.type = RecordOperation.DELETED;
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
        operationsBetweenCallbacks.put(record.getIdentity(), txEntry);
      }
    } catch (Exception e) {
      rollback(true, 0);
      throw e;
    }

    return txEntry;
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
    if (operationsBetweenCallbacks.isEmpty()) {
      return;
    }

    var serializer = session.getSerializer();
    ArrayList<RecordId> newDeletedRecords = null;
    while (!operationsBetweenCallbacks.isEmpty()) {
      var operations = new ArrayList<>(operationsBetweenCallbacks.values());
      operations.sort(
          Comparator.<RecordOperation>comparingInt(recordOperation -> recordOperation.type)
              .reversed());
      operationsBetweenCallbacks.clear();

      for (var recordOperation : operations) {
        preProcessRecordOperationAndExecuteCallbacks(recordOperation, serializer);
        if (recordOperation.type == RecordOperation.DELETED && recordOperation.record.getIdentity()
            .isNew()) {
          if (newDeletedRecords == null) {
            newDeletedRecords = new ArrayList<>();
          }
          newDeletedRecords.add(recordOperation.getRecordId());
        }
      }
    }

    if (newDeletedRecords != null) {
      for (var recordId : newDeletedRecords) {
        recordOperations.remove(recordId);
        recordsInTransaction.remove(recordId);

        if (recordId instanceof ChangeableIdentity changeableIdentity) {
          changeableIdentity.removeIdentityChangeListener(this);
        }
      }
    }

    assert operationsBetweenCallbacks.isEmpty();
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

        entity.recordSerializer = serializer;
      }

      if (recordOperation.recordCallBackDirtyCounter != record.getDirtyCounter()) {
        if (recordOperation.type == RecordOperation.CREATED) {
          if (recordOperation.recordCallBackDirtyCounter == 0) {
            if (className != null) {
              ClassIndexManager.checkIndexesAfterCreate(entityImpl, session);
            }
            processRecordCreation(recordOperation, record);
          } else {
            if (className != null) {
              ClassIndexManager.checkIndexesAfterUpdate(entityImpl, session);
            }
            processRecordUpdate(recordOperation, record);
          }
        } else {
          if (className != null) {
            ClassIndexManager.checkIndexesAfterUpdate(entityImpl, session);
          }
          processRecordUpdate(recordOperation, record);
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
      if (record instanceof EntityImpl) {
        ((EntityImpl) record).clearTrackData();
      }
    } catch (Exception e) {
      session.callbackHooks(TYPE.DELETE_FAILED, record);
      throw e;
    } finally {
      session.callbackHooks(TYPE.FINALIZE_DELETION, record);
    }
  }

  private void processRecordUpdate(RecordOperation recordOperation, RecordAbstract record) {
    var dirtyCounter = record.getDirtyCounter();
    var clusterName = session.getClusterNameById(record.getIdentity().getClusterId());

    recordOperation.recordCallBackDirtyCounter = dirtyCounter;
    session.beforeUpdateOperations(record, clusterName);
    try {
      session.afterUpdateOperations(record);
      if (record instanceof EntityImpl) {
        ((EntityImpl) record).clearTrackData();
      }
    } catch (Exception e) {
      session.callbackHooks(TYPE.UPDATE_FAILED, record);
      throw e;
    } finally {
      session.callbackHooks(TYPE.FINALIZE_UPDATE, record);
    }
  }

  private void processRecordCreation(RecordOperation recordOperation, RecordAbstract record) {
    var clusterName = session.getClusterNameById(record.getIdentity().getClusterId());
    recordOperation.recordCallBackDirtyCounter = record.getDirtyCounter();
    session.beforeCreateOperations(record, clusterName);
    try {
      session.afterCreateOperations(record);
      if (record instanceof EntityImpl) {
        ((EntityImpl) record).clearTrackData();
      }
    } catch (Exception e) {
      session.callbackHooks(TYPE.CREATE_FAILED, record);
      throw e;
    } finally {
      session.callbackHooks(TYPE.FINALIZE_CREATION, record);
    }
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

        record.unsetDirty();
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
    recordsInTransaction.clear();
    indexEntries.clear();
    recordIndexOperations.clear();

    newRecordsPositionsGenerator = -2;

    session.setDefaultTransactionMode();
    userData.clear();
  }

  public boolean assertIdentityChangedAfterCommit(final RecordId oldRid, final RecordId newRid) {
    if (oldRid.equals(newRid))
    // NO CHANGE, IGNORE IT
    {
      return true;
    }
    final var database = session;
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
        for (final var keyChanges : indexChanges.changesPerKey.values()) {
          assert !isIndexKeyMayDependOnRid(keyChanges.key, oldRid, fieldRidDependencies) :
              "Index key " + keyChanges.key
                  + " may depend on RID " + oldRid
                  + ", but it was not updated during record update. Index: "
                  + index.getName()
                  + ", key: "
                  + keyChanges.key;
        }
      }
    }

    // Update the identity.
    final var rec = getRecordEntry(oldRid);
    assert rec != null : "Record ID " + oldRid
        + " was not found in the transaction, but it was expected to be found. Record : "
        + oldRid;
    if (!rec.record.getIdentity().equals(newRid)) {
      final var recordId = rec.record.getIdentity();

      assert false : "Record ID " + recordId
          + " was not updated during record update, but it was expected to be updated. Record : "
          + rec.record;
    }

    // Update the indexes.
    final var transactionIndexOperations = recordIndexOperations.get(rec.getRecordId());
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
          assertChangesHaveOldIdentity(indexOperation.index, oldRid, keyChanges);
        }
      }
    }

    return true;
  }

  private static void assertChangesHaveOldIdentity(String indexName,
      RID oldRid, FrontendTransactionIndexChangesPerKey changesPerKey) {
    if (changesPerKey == null) {
      return;
    }

    for (final var indexEntry : changesPerKey.getEntriesAsList()) {
      assert !indexEntry.getValue().getIdentity().equals(oldRid) :
          "Index entry " + indexEntry.getValue()
              + " may depend on RID " + oldRid
              + ", but it was not updated during record update. Index: "
              + indexName
              + ", key: "
              + changesPerKey.key;
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

  @Override
  public void onBeforeIdentityChange(Object source) {
    var rid = (RecordId) source;

    var recordOperation = recordOperations.remove(rid);

    if (recordOperation != null) {
      recordOperationsIdentityMap.put(rid, recordOperation);
      var removed = originalChangedRecordIdMap.put(rid.copy(), rid);

      if (removed != null) {
        throw new IllegalStateException("RecordId " + rid
            + " was already changed in the transaction. Old RID: " + removed);
      }

      recordsInTransaction.remove(rid);
    }

    recordOperation = operationsBetweenCallbacks.remove(rid);
    if (recordOperation != null) {
      operationsBetweenCallbacksIdentityMap.put(rid, recordOperation);
    }

    var recordIndexOperation = recordIndexOperations.remove(rid);
    if (recordIndexOperation != null) {
      recordIndexOperationsIdentityMap.put(rid, recordIndexOperation);
    }
  }

  @Override
  public void onAfterIdentityChange(Object source) {
    var rid = (RecordId) source;

    var recordOperation = recordOperationsIdentityMap.remove(rid);
    if (recordOperation != null) {
      recordOperations.put(rid, recordOperation);
      recordsInTransaction.add(rid);
    }

    recordOperation = operationsBetweenCallbacksIdentityMap.remove(rid);
    if (recordOperation != null) {
      operationsBetweenCallbacks.put(rid, recordOperation);
    }

    var recordIndexOperation = recordIndexOperationsIdentityMap.remove(rid);
    if (recordIndexOperation != null) {
      recordIndexOperations.put(rid, recordIndexOperation);
    }
  }

  @Nullable
  public RecordId getFirstRid(int clusterId) {
    var result = recordsInTransaction.ceiling(new RecordId(clusterId, Long.MIN_VALUE));

    if (result == null) {
      return null;
    }

    if (result.getClusterId() != clusterId) {
      return null;
    }

    var record = getRecordEntry(result);
    if (record != null && record.type == RecordOperation.DELETED) {
      return getNextRidInCluster(result);
    }

    return result;
  }

  @Nullable
  public RecordId getLastRid(int clusterId) {
    var result = recordsInTransaction.floor(new RecordId(clusterId, Long.MAX_VALUE));

    if (result == null) {
      return null;
    }

    if (result.getClusterId() != clusterId) {
      return null;
    }

    var record = getRecordEntry(result);
    if (record != null && record.type == RecordOperation.DELETED) {
      return getPreviousRidInCluster(result);
    }

    return result;
  }

  @Nullable
  public RecordId getNextRidInCluster(@Nonnull RecordId rid) {
    var clusterId = rid.getClusterId();

    while (true) {
      var result = recordsInTransaction.higher(rid);

      if (result == null) {
        return null;
      }
      if (result.getClusterId() != clusterId) {
        return null;
      }

      var record = getRecordEntry(result);

      if (record != null && record.type == RecordOperation.DELETED) {
        rid = result;
        continue;
      }
      return result;
    }
  }

  @Nullable
  public RecordId getPreviousRidInCluster(@Nonnull RecordId rid) {
    var clusterId = rid.getClusterId();
    while (true) {
      var result = recordsInTransaction.lower(rid);

      if (result == null) {
        return null;
      }
      if (result.getClusterId() != clusterId) {
        return null;
      }

      var record = getRecordEntry(result);
      if (record != null && record.type == RecordOperation.DELETED) {
        rid = result;
        continue;
      }

      return result;
    }
  }

  @Override
  public boolean isDeletedInTx(@Nonnull RID rid) {
    var txEntry = getRecordEntry(rid);
    return txEntry != null && txEntry.type == RecordOperation.DELETED;
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

  public Collection<RecordOperation> getRecordOperationsInternal() {
    return recordOperations.values();
  }

  public RecordOperation getRecordEntry(RID rid) {
    assert rid instanceof RecordId;
    var operation = recordOperations.get(rid);

    if (operation == null) {
      var changedRid = originalChangedRecordIdMap.get(rid);
      if (changedRid != null) {
        operation = recordOperations.get(changedRid);
      }
    }

    return operation;
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

  public boolean isActive() {
    return status != TXSTATUS.INVALID
        && status != TXSTATUS.COMPLETED
        && status != TXSTATUS.ROLLED_BACK;
  }

  public TXSTATUS getStatus() {
    return status;
  }

  @Nonnull
  public final DatabaseSessionInternal getDatabaseSession() {
    return session;
  }

  public void setSession(@Nonnull DatabaseSessionInternal session) {
    this.session = session;
  }

  private boolean isWriteTransaction() {
    return !recordOperations.isEmpty() || !indexEntries.isEmpty();
  }

  public static long generateTxId() {
    return txSerial.incrementAndGet();
  }
}
