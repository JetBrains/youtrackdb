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

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.exception.TransactionException;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.api.record.Blob;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Edge;
import com.jetbrains.youtrack.db.api.record.EmbeddedEntity;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.record.StatefulEdge;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.transaction.RecordOperationType;
import com.jetbrains.youtrack.db.api.transaction.Transaction;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.db.LoadRecordResult;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.id.ChangeableIdentity;
import com.jetbrains.youtrack.db.internal.core.id.IdentityChangeListener;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.index.ClassIndexManager;
import com.jetbrains.youtrack.db.internal.core.index.CompositeKey;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Rule;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializer;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.RecordSerializationContext;
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
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class FrontendTransactionImpl implements
    IdentityChangeListener, FrontendTransaction {

  private static final AtomicLong txSerial = new AtomicLong();

  @Nonnull
  protected DatabaseSessionEmbedded session;
  protected TXSTATUS status = TXSTATUS.INVALID;

  protected final HashMap<RecordId, RecordOperation> recordOperations = new HashMap<>();
  private final IdentityHashMap<RecordId, RecordOperation> recordOperationsIdentityMap =
      new IdentityHashMap<>();
  protected final TreeSet<RecordId> recordsInTransaction = new TreeSet<>();

  protected final HashMap<RecordId, RecordOperation> operationsBetweenCallbacks = new HashMap<>();
  private final IdentityHashMap<RecordId, RecordOperation> operationsBetweenCallbacksIdentityMap =
      new IdentityHashMap<>();
  private final ArrayList<RecordOperation> operationsForCallbackIteration = new ArrayList<>();

  protected HashMap<RecordId, List<FrontendTransactionRecordIndexOperation>> recordIndexOperations =
      new HashMap<>();
  private final IdentityHashMap<RecordId, List<FrontendTransactionRecordIndexOperation>> recordIndexOperationsIdentityMap =
      new IdentityHashMap<>();

  protected HashMap<String, FrontendTransactionIndexChanges> indexEntries = new HashMap<>();

  protected final HashMap<RecordId, RecordId> originalChangedRecordIdMap = new HashMap<>();

  protected long id;
  protected int newRecordsPositionsGenerator = -2;
  private final HashMap<String, Object> userData = new HashMap<>();

  private boolean callbacksInProgress = false;
  private boolean beforeCallBacksInProgress = false;

  @Nullable
  private FrontendTransacationMetadataHolder metadata = null;
  @Nullable
  private List<byte[]> serializedOperations;

  protected int txStartCounter;
  protected boolean sentToServer = false;
  private final boolean readOnly;

  private final RecordSerializationContext recordSerializationContext = new RecordSerializationContext();

  public FrontendTransactionImpl(final DatabaseSessionEmbedded iDatabase) {
    this(iDatabase, false);
  }

  public FrontendTransactionImpl(@Nonnull final DatabaseSessionEmbedded session, boolean readOnly) {
    this.session = session;
    this.id = txSerial.incrementAndGet();
    this.readOnly = readOnly;
  }

  public FrontendTransactionImpl(@Nonnull final DatabaseSessionEmbedded session, long txId,
      boolean readOnly) {
    this.session = session;
    this.id = txId;
    this.readOnly = readOnly;
  }


  protected FrontendTransactionImpl(@Nonnull final DatabaseSessionEmbedded session, long id) {
    this.session = session;
    this.id = id;
    readOnly = false;
  }

  @Override
  public int beginInternal() {
    if (txStartCounter < 0) {
      throw new TransactionException(session, "Invalid value of TX counter: " + txStartCounter);
    }
    if (callbacksInProgress) {
      throw new TransactionException(session,
          "Callback processing is in progress. Cannot start a new transaction.");
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

  @Override
  public Map<RID, RID> commitInternal() {
    return commitInternal(false);
  }

  /**
   * The transaction is reentrant. If {@code begin()} has been called several times, the actual
   * commit happens only after the same amount of {@code commit()} calls
   *
   * @param force commit transaction even
   * @return Map between generated rids of new records and ones generated during records commit.
   */
  @Override
  public Map<RID, RID> commitInternal(final boolean force) {
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
      return doCommit();
    } else {
      if (txStartCounter < 0) {
        throw new TransactionException(session,
            "Transaction was committed more times than it was started.");
      }
    }

    return null;
  }

  @Override
  public RecordAbstract getRecord(final RID rid) {
    final var e = getRecordEntry(rid);
    if (e != null) {
      if (e.type == RecordOperation.DELETED) {
        return null;
      } else {
        assert e.record.getSession() == session;
        return e.record;
      }
    }
    return null;
  }

  @Override
  public void clearIndexEntries() {
    indexEntries.clear();
    recordIndexOperations.clear();
  }

  @Override
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

  @Override
  public Map<String, FrontendTransactionIndexChanges> getIndexOperations() {
    return indexEntries;
  }

  @Override
  public FrontendTransactionIndexChanges getIndexChangesInternal(final String indexName) {
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
    assert session.getStorage() instanceof AbstractStorage;

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
      rollbackInternal();
      throw e;
    }
  }

  /**
   * Buffer sizes index changes to be flushed at commit time.
   */
  @Override
  public FrontendTransactionIndexChanges getIndexChanges(final String iIndexName) {
    return indexEntries.get(iIndexName);
  }

  @Override
  public int amountOfNestedTxs() {
    return txStartCounter;
  }

  @Override
  public void rollbackInternal(boolean clearQueries) {
    if (txStartCounter < 0) {
      throw new TransactionException(session, "Invalid value of TX counter");
    }

    switch (status) {
      case ROLLBACKING -> {
        //do nothing
      }
      case ROLLED_BACK -> {
        throw new IllegalStateException("Transaction is already rolled back");
      }
      case BEGUN, COMMITTING -> {
        status = TXSTATUS.ROLLBACKING;

        if (isWriteTransaction()) {
          session.transactionMeters()
              .writeRollbackTransactions()
              .record();
        }

        //There are could be exceptions during session opening
        // that will force to rollback of txs started during this process.
        //Session is active only if it is opened successfully.
        if (session.isActiveOnCurrentThread()) {
          session.beforeRollbackOperations();
        }

        invalidateChangesInCacheDuringRollback();
        clear(clearQueries);
      }
      case INVALID, COMPLETED -> {
        throw new IllegalStateException("Transaction is in invalid state: " + status);
      }
      default -> {
        throw new IllegalStateException("Transaction is in unknown state: " + status);
      }
    }

    if (txStartCounter > 0) {
      txStartCounter--;
    }

    if (txStartCounter == 0) {
      closeInternal(clearQueries);
      status = TXSTATUS.ROLLED_BACK;

      //There are could be exceptions during session opening
      // that will force to rollback of txs started during this process.
      //Session is active only if it is opened successfully.
      if (session.isActiveOnCurrentThread()) {
        session.afterRollbackOperations();
      }
    }
  }


  private void invalidateChangesInCacheDuringRollback() {
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
  public boolean exists(@Nonnull RID rid) {
    checkTransactionValid();

    final DBRecord txRecord = getRecord(rid);
    if (isDeletedInTx(rid)) {
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

    if (isDeletedInTx(rid)) {
      throw new RecordNotFoundException(session, rid);
    }
    final var txRecord = getRecord(rid);
    if (txRecord != null) {
      return new LoadRecordResult(txRecord, null, null);
    }

    // DELEGATE TO THE STORAGE, NO TOMBSTONES SUPPORT IN TX MODE
    return session.executeReadRecord((RecordId) rid, false, false, true);
  }

  @Override
  public void deleteRecord(final RecordAbstract record) {
    try {
      addRecordOperation(record, RecordOperation.DELETED);
      //execute it here because after this operation record will be unloaded
      preProcessRecordsAndExecuteCallCallbacks();
    } catch (Exception e) {
      rollbackInternal();
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

  @Override
  public void setStatus(final TXSTATUS iStatus) {
    status = iStatus;
  }

  @Override
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
                + Transaction.class.getSimpleName()
                + ".load(record) before changing it");
      }
      if (record.isEmbedded()) {
        throw new DatabaseException(session,
            "Record "
                + record
                + " is embedded and can not added to list of records to be saved");
      }
      checkTransactionValid();
      var rid = record.getIdentity();

      if (rid.getCollectionId() == RID.COLLECTION_ID_INVALID) {
        var collectionId = session.assignAndCheckCollection(record);
        rid.setCollectionAndPosition(collectionId, newRecordsPositionsGenerator--);
      } else if (!rid.isValidPosition()) {
        rid.setCollectionPosition(newRecordsPositionsGenerator--);
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
            final var desc = switch (record) {
              case Entity entity -> entity.getSchemaClassName();
              case Blob ignored -> "BLOB";
              default -> record.getClass().getSimpleName(); // in case we extend our hierarchy
            };
            throw new TransactionException(session,
                "Found record in transaction with the same RID but different instance: " +
                    desc + " " + record.getIdentity());
          }
          if (record.txEntry != txEntry) {
            throw new TransactionException(session,
                "Record is already in transaction with different associated transaction entry");
          }

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

      assert txEntry.recordBeforeCallBackDirtyCounter <= record.getDirtyCounter();
      if (txEntry.recordBeforeCallBackDirtyCounter < record.getDirtyCounter()) {
        operationsBetweenCallbacks.put(record.getIdentity(), txEntry);
      }
    } catch (Exception e) {
      rollbackInternal();
      throw e;
    }

    return txEntry;
  }

  private Map<RID, RID> doCommit() {
    if (status == TXSTATUS.ROLLED_BACK || status == TXSTATUS.ROLLBACKING) {
      if (status == TXSTATUS.ROLLBACKING) {
        rollbackInternal();
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
      rollbackInternal();
      throw e;
    }

    var result = new HashMap<RID, RID>(originalChangedRecordIdMap);
    close();
    status = TXSTATUS.COMPLETED;

    return result;
  }

  @Override
  public boolean isScheduledForCallbackProcessing(RecordId rid) {
    if (operationsBetweenCallbacks.containsKey(rid)) {
      return true;
    }

    for (var operation : operationsForCallbackIteration) {
      if (operation.record.getIdentity().equals(rid)) {
        return true;
      }
    }

    return false;
  }

  @Override
  @Nullable
  public List<RecordId> preProcessRecordsAndExecuteCallCallbacks() {
    if (beforeCallBacksInProgress) {
      throw new IllegalStateException(
          "Callback processing is in progress, if you trigger this operation"
              + " in beforeCallBackXXX trigger please move it to the afterCallBackXXX trigger.");
    }

    if (operationsBetweenCallbacks.isEmpty()) {
      return null;
    }

    ArrayList<RecordId> newDeletedRecords = null;
    callbacksInProgress = true;
    try {
      var serializer = session.getSerializer();
      while (!operationsBetweenCallbacks.isEmpty()) {
        var recordOperationsToCallback = operationsBetweenCallbacks.values();
        operationsForCallbackIteration.clear();

        for (var recordOperationToCallback : recordOperationsToCallback) {
          var dirtyCounter = recordOperationToCallback.record.getDirtyCounter();
          assert dirtyCounter >= recordOperationToCallback.recordBeforeCallBackDirtyCounter;

          if (recordOperationToCallback.recordBeforeCallBackDirtyCounter < dirtyCounter) {
            operationsForCallbackIteration.add(recordOperationToCallback);
          }
        }

        beforeCallBacksInProgress = true;
        try {
          operationsBetweenCallbacks.clear();

          operationsForCallbackIteration.sort(
              Comparator.<RecordOperation>comparingInt(recordOperation -> recordOperation.type)
                  .reversed());

          for (var recordOperation : operationsForCallbackIteration) {
            //operations are processed and deleted from the map
            preProcessRecordOperationAndExecuteBeforeCallbacks(recordOperation, serializer);

            if (recordOperation.type == RecordOperation.DELETED
                && recordOperation.record.getIdentity().isNew()) {
              if (newDeletedRecords == null) {
                newDeletedRecords = new ArrayList<>();
              }

              newDeletedRecords.add(recordOperation.getRecordId());
            }
          }
        } finally {
          beforeCallBacksInProgress = false;
        }

        var postCallBackOperations = new ArrayList<>(operationsForCallbackIteration);
        operationsForCallbackIteration.clear();

        for (var recordOperation : postCallBackOperations) {
          callAfterCallbacks(recordOperation);
        }
      }
    } finally {
      callbacksInProgress = false;
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

    assert operationsForCallbackIteration.isEmpty();
    return newDeletedRecords;
  }

  @Override
  public boolean isCallBackProcessingInProgress() {
    return beforeCallBacksInProgress;
  }

  private void preProcessRecordOperationAndExecuteBeforeCallbacks(RecordOperation recordOperation,
      RecordSerializer serializer) {
    var record = recordOperation.record;
    var collectionName = session.getCollectionNameById(record.getIdentity().getCollectionId());

    if (recordOperation.type == RecordOperation.CREATED
        || recordOperation.type == RecordOperation.UPDATED) {
      String className = null;
      EntityImpl entityImpl = null;
      if (recordOperation.record instanceof EntityImpl entity) {
        entityImpl = entity;
        className = entity.getSchemaClassName();
        if (recordOperation.recordBeforeCallBackDirtyCounter != record.getDirtyCounter()) {
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

      recordOperation.record.processingInCallback = true;
      try {
        if (recordOperation.type == RecordOperation.CREATED) {
          if (recordOperation.recordBeforeCallBackDirtyCounter == 0) {
            if (className != null) {
              ClassIndexManager.checkIndexesAfterCreate(entityImpl, this);
            }
            session.beforeCreateOperations(record, collectionName);
          } else {
            if (className != null) {
              ClassIndexManager.checkIndexesAfterUpdate(entityImpl, this);
            }
            session.beforeUpdateOperations(record, collectionName);
          }
        } else {
          if (className != null) {
            ClassIndexManager.checkIndexesAfterUpdate(entityImpl, this);
          }
          session.beforeUpdateOperations(record, collectionName);
        }
      } finally {
        recordOperation.record.processingInCallback = false;
      }

    } else if (recordOperation.type == RecordOperation.DELETED) {
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

      recordOperation.record.processingInCallback = true;
      try {
        if (className != null) {
          ClassIndexManager.checkIndexesAfterDelete(entityImpl, this);
        }
        session.beforeDeleteOperations(record, collectionName);
      } finally {
        recordOperation.record.processingInCallback = false;
      }


    } else {
      throw new IllegalStateException(
          "Invalid record operation type " + recordOperation.type);
    }

    if (record instanceof EntityImpl entity) {
      entity.clearTrackData();
    }

    recordOperation.recordBeforeCallBackDirtyCounter = record.getDirtyCounter();
  }

  private void callAfterCallbacks(RecordOperation recordOperation) {
    var record = recordOperation.record;

    switch (recordOperation.type) {
      case RecordOperation.CREATED -> {
        if (recordOperation.recordPostCallBackDirtyCounter == 0) {
          session.afterCreateOperations(record);
        } else {
          session.afterUpdateOperations(record);
        }
      }
      case RecordOperation.UPDATED -> session.afterUpdateOperations(record);
      case RecordOperation.DELETED -> session.afterDeleteOperations(record);
    }

    recordOperation.recordPostCallBackDirtyCounter = record.getDirtyCounter();
  }

  @Override
  public void close() {
    closeInternal(true);
  }

  public void closeInternal(boolean clearQueries) {
    clear(clearQueries);

    session.setNoTxMode();
    status = TXSTATUS.INVALID;
  }

  private void clear(boolean clearQueries) {
    if (clearQueries) {
      session.closeActiveQueries();
    }

    final var dbCache = session.getLocalCache();
    for (var txEntry : recordOperations.values()) {
      var record = txEntry.record;

      if (!record.isUnloaded()) {
        if (record instanceof EntityImpl entity) {
          entity.clearTransactionTrackData();
        }

        record.txEntry = null;
        record.unsetDirty();
        record.unload();
      }
    }

    dbCache.unloadRecords();
    dbCache.clear();

    clearUnfinishedChanges();

    recordSerializationContext.clear();
  }

  private void clearUnfinishedChanges() {
    recordOperations.clear();
    recordsInTransaction.clear();
    indexEntries.clear();
    recordIndexOperations.clear();

    newRecordsPositionsGenerator = -2;

    userData.clear();
  }

  @Override
  public boolean assertIdentityChangedAfterCommit(final RecordId oldRid, final RecordId newRid) {
    if (oldRid.equals(newRid))
    // NO CHANGE, IGNORE IT
    {
      return true;
    }
    final var database = session;
    final var indexManager = database.getSharedContext().getIndexManager();
    for (var entry : indexEntries.entrySet()) {
      final var index = indexManager.getIndex(entry.getKey());
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

  @Nullable
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

  private static Dependency getTypeRidDependency(PropertyTypeInternal type) {
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

  @Override
  @Nullable
  public RecordId getFirstRid(int collectionId) {
    var result = recordsInTransaction.ceiling(new RecordId(collectionId, Long.MIN_VALUE));

    if (result == null) {
      return null;
    }

    if (result.getCollectionId() != collectionId) {
      return null;
    }

    var record = getRecordEntry(result);
    if (record != null && record.type == RecordOperation.DELETED) {
      return getNextRidInCollection(result);
    }

    return result;
  }

  @Override
  @Nullable
  public RecordId getLastRid(int collectionId) {
    var result = recordsInTransaction.floor(new RecordId(collectionId, Long.MAX_VALUE));

    if (result == null) {
      return null;
    }

    if (result.getCollectionId() != collectionId) {
      return null;
    }

    var record = getRecordEntry(result);
    if (record != null && record.type == RecordOperation.DELETED) {
      return getPreviousRidInCollection(result);
    }

    return result;
  }

  @Override
  @Nullable
  public RecordId getNextRidInCollection(@Nonnull RecordId rid) {
    var collectionId = rid.getCollectionId();

    while (true) {
      var result = recordsInTransaction.higher(rid);

      if (result == null) {
        return null;
      }
      if (result.getCollectionId() != collectionId) {
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
  @Nullable
  public RecordId getPreviousRidInCollection(@Nonnull RecordId rid) {
    var collectionId = rid.getCollectionId();
    while (true) {
      var result = recordsInTransaction.lower(rid);

      if (result == null) {
        return null;
      }
      if (result.getCollectionId() != collectionId) {
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

  @Override
  public long getId() {
    return id;
  }

  @Override
  public void clearRecordEntries() {
  }

  public void restore() {
  }

  @Override
  public int getEntryCount() {
    return recordOperations.size();
  }

  @Override
  public Collection<RecordOperation> getCurrentRecordEntries() {
    return recordOperations.values();
  }

  @Override
  public Collection<RecordOperation> getRecordOperationsInternal() {
    return recordOperations.values();
  }

  @Override
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

  @Override
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

  @Override
  public boolean isActive() {
    return status != TXSTATUS.INVALID
        && status != TXSTATUS.COMPLETED
        && status != TXSTATUS.ROLLED_BACK;
  }

  @Override
  public TXSTATUS getStatus() {
    return status;
  }

  @Override
  @Nonnull
  public final DatabaseSessionEmbedded getDatabaseSession() {
    return session;
  }

  @Override
  public void setSession(@Nonnull DatabaseSessionEmbedded session) {
    this.session = session;
  }

  @Nonnull
  @Override
  public Entity loadEntity(RID id) throws DatabaseException, RecordNotFoundException {
    checkIfActive();
    return session.loadEntity(id);
  }

  @Nonnull
  @Override
  public Entity loadEntity(Identifiable identifiable)
      throws DatabaseException, RecordNotFoundException {
    checkIfActive();

    if (identifiable instanceof Entity entity) {
      if (entity.isEmbedded()) {
        return entity;
      }
      if (entity.isNotBound(session)) {
        return loadEntity(entity.getIdentity());
      }

      return entity;
    } else if (identifiable instanceof DBRecord) {
      throw new DatabaseException(session, "Record " + identifiable + "is not an entity.");
    }

    return session.loadEntity(identifiable.getIdentity());
  }

  @Nullable
  @Override
  public Entity loadEntityOrNull(Identifiable identifiable) throws DatabaseException {
    checkIfActive();
    if (identifiable instanceof Entity entity) {
      if (entity.isNotBound(session)) {
        return loadEntityOrNull(entity.getIdentity());
      }
      return entity;
    } else if (identifiable instanceof DBRecord) {
      throw new DatabaseException(session, "Record " + identifiable + "is not an entity.");
    }

    try {
      return session.loadEntity(identifiable.getIdentity());
    } catch (RecordNotFoundException e) {
      return null;
    }
  }

  @Nullable
  @Override
  public Entity loadEntityOrNull(RID id) throws DatabaseException {
    checkIfActive();
    try {
      return session.loadEntity(id);
    } catch (RecordNotFoundException e) {
      return null;
    }
  }

  @Nonnull
  @Override
  public Vertex loadVertex(RID id) throws DatabaseException, RecordNotFoundException {
    checkIfActive();
    return session.loadVertex(id);
  }

  @Nullable
  @Override
  public Vertex loadVertexOrNull(RID id) throws RecordNotFoundException {
    checkIfActive();
    try {
      return session.loadVertex(id);
    } catch (RecordNotFoundException e) {
      return null;
    }
  }

  @Nonnull
  @Override
  public Vertex loadVertex(Identifiable identifiable)
      throws DatabaseException, RecordNotFoundException {
    checkIfActive();
    if (identifiable instanceof Vertex vertex) {
      if (vertex.isNotBound(session)) {
        return loadVertex(vertex.getIdentity());
      }

      return vertex;
    } else if (identifiable instanceof DBRecord) {
      throw new DatabaseException(session, "Record " + identifiable + "is not a vertex.");
    }

    return session.loadVertex(identifiable.getIdentity());
  }

  @Nullable
  @Override
  public Vertex loadVertexOrNull(Identifiable identifiable) throws RecordNotFoundException {
    checkIfActive();
    if (identifiable instanceof Vertex vertex) {
      if (vertex.isNotBound(session)) {
        return loadVertexOrNull(vertex.getIdentity());
      }
      return vertex;
    } else if (identifiable instanceof DBRecord) {
      throw new DatabaseException(session, "Record " + identifiable + "is not a vertex.");
    }

    try {
      return session.loadVertex(identifiable.getIdentity());
    } catch (RecordNotFoundException e) {
      return null;
    }

  }

  @Nonnull
  @Override
  public StatefulEdge loadEdge(@Nonnull RID id) throws DatabaseException, RecordNotFoundException {
    checkIfActive();
    return session.loadEdge(id);
  }

  @Nullable
  @Override
  public StatefulEdge loadEdgeOrNull(@Nonnull RID id) throws DatabaseException {
    checkIfActive();
    try {
      return session.loadEdge(id);
    } catch (RecordNotFoundException e) {
      return null;
    }
  }

  @Nonnull
  @Override
  public StatefulEdge loadEdge(@Nonnull Identifiable id)
      throws DatabaseException, RecordNotFoundException {
    checkIfActive();
    if (id instanceof StatefulEdge edge) {
      if (edge.isNotBound(session)) {
        return loadEdge(edge.getIdentity());
      }

      return edge;
    } else if (id instanceof DBRecord) {
      throw new DatabaseException(session, "Record " + id + "is not an edge.");
    }

    return session.loadEdge(id.getIdentity());
  }

  @Override
  public StatefulEdge loadEdgeOrNull(@Nonnull Identifiable id) throws DatabaseException {
    checkIfActive();
    if (id instanceof StatefulEdge edge) {
      if (edge.isNotBound(session)) {
        return loadEdgeOrNull(edge.getIdentity());
      }
      return edge;
    } else if (id instanceof DBRecord) {
      throw new DatabaseException(session, "Record " + id + "is not an edge.");
    }

    try {
      return session.loadEdge(id.getIdentity());
    } catch (RecordNotFoundException e) {
      return null;
    }
  }

  @Nonnull
  @Override
  public Blob loadBlob(@Nonnull RID id) throws DatabaseException, RecordNotFoundException {
    checkIfActive();
    return session.loadBlob(id);
  }

  @Nullable
  @Override
  public Blob loadBlobOrNull(@Nonnull RID id) throws DatabaseException, RecordNotFoundException {
    checkIfActive();
    try {
      return session.loadBlob(id);
    } catch (RecordNotFoundException e) {
      return null;
    }
  }

  @Nonnull
  @Override
  public Blob loadBlob(@Nonnull Identifiable id) throws DatabaseException, RecordNotFoundException {
    checkIfActive();
    if (id instanceof Blob blob) {
      if (blob.isNotBound(session)) {
        return loadBlob(blob.getIdentity());
      }
      return blob;
    } else if (id instanceof DBRecord) {
      throw new DatabaseException(session, "Record " + id + "is not a blob.");
    }

    return session.loadBlob(id.getIdentity());
  }

  @Override
  public Blob loadBlobOrNull(@Nonnull Identifiable id) throws DatabaseException {
    checkIfActive();
    if (id instanceof Blob blob) {
      if (blob.isNotBound(session)) {
        return loadBlobOrNull(blob.getIdentity());
      }

      return blob;
    } else if (id instanceof DBRecord) {
      throw new DatabaseException(session, "Record " + id + "is not a blob.");
    }

    try {
      return session.loadBlob(id.getIdentity());
    } catch (RecordNotFoundException e) {
      return null;
    }

  }

  @Override
  public Blob newBlob(@Nonnull byte[] bytes) {
    checkIfActive();
    return session.newBlob(bytes);
  }

  @Override
  public Blob newBlob() {
    checkIfActive();
    return session.newBlob();
  }

  @Override
  public Entity newEntity(String className) {
    checkIfActive();
    return session.newEntity(className);
  }

  @Override
  public Entity newEntity(SchemaClass cls) {
    checkIfActive();
    return session.newEntity(cls);
  }

  @Override
  public Entity newEntity() {
    checkIfActive();
    return session.newEntity();
  }

  @Override
  public EmbeddedEntity newEmbeddedEntity(SchemaClass schemaClass) {
    checkIfActive();
    return session.newEmbeddedEntity(schemaClass);
  }

  @Override
  public EmbeddedEntity newEmbeddedEntity(String schemaClass) {
    checkIfActive();
    return session.newEmbeddedEntity(schemaClass);
  }

  @Override
  public EmbeddedEntity newEmbeddedEntity() {
    checkIfActive();
    return session.newEmbeddedEntity();
  }

  @Override
  public <T extends DBRecord> T createOrLoadRecordFromJson(String json) {
    checkIfActive();
    return session.createOrLoadRecordFromJson(json);
  }

  @Override
  public Entity createOrLoadEntityFromJson(String json) {
    checkIfActive();
    return session.createOrLoadEntityFromJson(json);
  }

  @Override
  public StatefulEdge newStatefulEdge(Vertex from, Vertex to, SchemaClass type) {
    checkIfActive();
    return session.newStatefulEdge(from, to, type);
  }

  @Override
  public StatefulEdge newStatefulEdge(Vertex from, Vertex to, String type) {
    checkIfActive();
    return session.newStatefulEdge(from, to, type);
  }

  @Override
  public Edge newLightweightEdge(Vertex from, Vertex to, @Nonnull SchemaClass type) {
    checkIfActive();
    return session.newLightweightEdge(from, to, type);
  }

  @Override
  public Edge newLightweightEdge(Vertex from, Vertex to, @Nonnull String type) {
    checkIfActive();
    return session.newLightweightEdge(from, to, type);
  }

  @Override
  public Vertex newVertex(SchemaClass type) {
    checkIfActive();
    return session.newVertex(type);
  }

  @Override
  public Vertex newVertex(String type) {
    checkIfActive();
    return session.newVertex(type);
  }

  @Override
  public StatefulEdge newStatefulEdge(Vertex from, Vertex to) {
    checkIfActive();
    return session.newStatefulEdge(from, to);
  }

  @Nonnull
  @Override
  public <RET extends DBRecord> RET load(RID recordId) {
    checkIfActive();
    return session.load(recordId);
  }

  @Nullable
  @Override
  public <RET extends DBRecord> RET loadOrNull(RID recordId) {
    checkIfActive();
    return session.loadOrNull(recordId);
  }

  @Nonnull
  @Override
  public <RET extends DBRecord> RET load(Identifiable identifiable) {
    checkIfActive();

    if (identifiable instanceof DBRecord record) {
      if (record instanceof Entity entity && entity.isEmbedded()) {
        //noinspection unchecked
        return (RET) record;
      }
      if (record.isNotBound(session)) {
        return load(record.getIdentity());
      }
      //noinspection unchecked
      return (RET) record;
    }

    return session.load(identifiable.getIdentity());
  }

  @Nullable
  @Override
  public <RET extends DBRecord> RET loadOrNull(Identifiable identifiable) {
    checkIfActive();
    if (identifiable instanceof DBRecord record) {
      //noinspection unchecked
      return (RET) record;
    }

    return session.loadOrNull(identifiable.getIdentity());
  }

  @Override
  public void delete(@Nonnull DBRecord record) {
    checkIfActive();
    session.delete(record);
  }

  @Override
  public Map<RID, RID> commit() throws TransactionException {
    checkIfActive();
    return session.commit();
  }

  @Override
  public void rollback() throws TransactionException {
    checkIfActive();
    session.rollback();
  }

  @Override
  public ResultSet query(String query, Object... args)
      throws CommandSQLParsingException, CommandExecutionException {
    checkIfActive();
    return session.query(query, args);
  }

  @Override
  public ResultSet query(String query, @SuppressWarnings("rawtypes") Map args)
      throws CommandSQLParsingException, CommandExecutionException {
    checkIfActive();
    return session.query(query, args);
  }

  @Override
  public ResultSet execute(String query, Object... args)
      throws CommandSQLParsingException, CommandExecutionException {
    checkIfActive();
    return session.execute(query, args);
  }

  @Override
  public ResultSet execute(String query, @SuppressWarnings("rawtypes") Map args)
      throws CommandSQLParsingException, CommandExecutionException {
    checkIfActive();
    return session.execute(query, args);
  }

  @Override
  public void command(String query, Object... args)
      throws CommandSQLParsingException, CommandExecutionException {
    checkIfActive();
    session.command(query, args);
  }

  @Override
  public void command(String query, @SuppressWarnings("rawtypes") Map args)
      throws CommandSQLParsingException, CommandExecutionException {
    checkIfActive();
    session.command(query, args);
  }

  private void checkIfActive() {
    if (!isActive()) {
      throw new TransactionException(session,
          "Transaction is not active and can not be used.");
    }
  }

  @Override
  public @Nonnull Stream<com.jetbrains.youtrack.db.api.transaction.RecordOperation> getRecordOperations() {
    checkIfActive();
    return getRecordOperationsInternal().stream().map(recordOperation ->
        switch (recordOperation.type) {
          case RecordOperation.CREATED ->
              new com.jetbrains.youtrack.db.api.transaction.RecordOperation(recordOperation.record,
                  RecordOperationType.CREATED);
          case RecordOperation.UPDATED ->
              new com.jetbrains.youtrack.db.api.transaction.RecordOperation(recordOperation.record,
                  RecordOperationType.UPDATED);
          case RecordOperation.DELETED ->
              new com.jetbrains.youtrack.db.api.transaction.RecordOperation(recordOperation.record,
                  RecordOperationType.DELETED);
          default -> throw new IllegalStateException("Unexpected value: " + recordOperation.type);
        });
  }

  @Override
  public int getRecordOperationsCount() {
    checkIfActive();
    return getRecordOperationsInternal().size();
  }

  @Override
  public int activeTxCount() {
    checkIfActive();
    return amountOfNestedTxs();
  }

  @Override
  public @Nonnull RecordSerializationContext getRecordSerializationContext() {
    return recordSerializationContext;
  }

  private boolean isWriteTransaction() {
    return !recordOperations.isEmpty() || !indexEntries.isEmpty();
  }

  public static long generateTxId() {
    return txSerial.incrementAndGet();
  }

}
