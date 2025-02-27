package com.jetbrains.youtrack.db.internal.server.tx;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.exception.TransactionException;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.record.RecordHook;
import com.jetbrains.youtrack.db.internal.client.remote.message.tx.RecordOperationRequest;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.exception.SerializationException;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.index.ClassIndexManager;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.RecordInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityInternalUtils;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.DocumentSerializerDelta;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetworkV37;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionOptimistic;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import javax.annotation.Nonnull;

public class FrontendTransactionOptimisticServer extends FrontendTransactionOptimistic {

  public FrontendTransactionOptimisticServer(DatabaseSessionInternal database, long txId) {
    super(database, txId);
  }

  public void mergeReceivedTransaction(List<RecordOperationRequest> operations) {
    if (operations == null) {
      return;
    }

    // SORT OPERATIONS BY TYPE TO BE SURE THAT CREATES ARE PROCESSED FIRST
    operations.sort(Comparator.comparingInt(RecordOperationRequest::getType).reversed());

    final HashMap<RID, RecordOperation> tempEntries = new LinkedHashMap<>();
    final var createdRecords = new HashMap<RID, RecordOperation>();
    final var updatedRecords = new HashMap<RecordId, RecordAbstract>();

    try {
      for (var operation : operations) {
        final var recordStatus = operation.getType();

        final var rid = (RecordId) operation.getId();

        @Nonnull final RecordOperation entry;
        switch (recordStatus) {
          case RecordOperation.CREATED: {
            var txEntry = getRecordEntry(rid);

            if (txEntry != null) {
              if (txEntry.type != RecordOperation.CREATED) {
                throw new IllegalStateException(
                    "Record " + rid + " was created on client side but updated on server side.");
              }
              entry = txEntry;

              mergeChanges(operation, entry.record, operation.getRecordType());
            } else {
              var record =
                  YouTrackDBEnginesManager.instance()
                      .getRecordFactoryManager()
                      .newInstance(operation.getRecordType(), rid, getDatabaseSession());

              RecordSerializerNetworkV37.INSTANCE.fromStream(getDatabaseSession(),
                  operation.getRecord(),
                  record);
              entry = new RecordOperation(record, RecordOperation.CREATED);
              RecordInternal.setVersion(record, 0);
            }
            createdRecords.put(rid.copy(), entry);
          }
          break;

          case RecordOperation.UPDATED: {
            var type = operation.getRecordType();

            var txEntry = getRecordEntry(rid);
            if (txEntry != null && txEntry.type == RecordOperation.DELETED) {
              throw new IllegalStateException(
                  "Record " + rid + " was updated on client side but deleted on server side.");
            }

            RecordAbstract updated;
            if (txEntry == null) {
              try {
                updated = session.load(rid);
              } catch (RecordNotFoundException e) {
                throw new IllegalStateException(
                    "Record " + rid + " was not found in database.");
              }
              txEntry = new RecordOperation(updated, RecordOperation.UPDATED);
            } else {
              updated = txEntry.record;
            }

            entry = txEntry;

            mergeChanges(operation, updated, type);
            updatedRecords.put(rid, updated);
          }
          break;
          case RecordOperation.DELETED:
            // LOAD RECORD TO BE SURE IT HASN'T BEEN DELETED BEFORE + PROVIDE CONTENT FOR ANY HOOK
            var recordEntry = getRecordEntry(rid);
            if (recordEntry != null && recordEntry.type == RecordOperation.DELETED) {
              // ALREADY DELETED
              continue;
            }

            RecordAbstract rec = rid.getRecord(session);
            entry = new RecordOperation(rec, RecordOperation.DELETED);
            var deleteVersion = operation.getVersion();
            RecordInternal.setVersion(rec, deleteVersion);
            break;
          default:
            throw new TransactionException(session, "Unrecognized tx command: " + recordStatus);
        }

        // PUT IN TEMPORARY LIST TO GET FETCHED AFTER ALL FOR CACHE
        tempEntries.put(entry.record.getIdentity(), entry);
      }

      var txOperations = new ArrayList<RecordOperation>(tempEntries.size());
      try {
        for (var entry : tempEntries.entrySet()) {
          var operation = entry.getValue();
          txOperations.add(preAddRecord(operation.record, entry.getValue().type));
        }

        for (var operation : txOperations) {
          postAddRecord(operation.record, operation.type, operation.callHooksOnServerTx);
        }
      } finally {
        for (var operation : txOperations) {
          finalizeAddRecord(operation.record, operation.type, operation.callHooksOnServerTx);
        }
      }

      tempEntries.clear();

      newRecordsPositionsGenerator = (createdRecords.size() + 2) * -1;
      // UNMARSHALL ALL THE RECORD AT THE END TO BE SURE ALL THE RECORD ARE LOADED IN LOCAL TX
      for (var recordOperation : createdRecords.values()) {
        var record = recordOperation.record;
        unmarshallRecord(record);
        if (record instanceof EntityImpl) {
          // Force conversion of value to class for trigger default values.
          EntityInternalUtils.autoConvertValueToClass(getDatabaseSession(), (EntityImpl) record);
        }
      }
      for (DBRecord record : updatedRecords.values()) {
        unmarshallRecord(record);
      }
    } catch (Exception e) {
      rollback();
      throw BaseException.wrapException(
          new SerializationException(session,
              "Cannot read transaction record from the network. Transaction aborted"),
          e, session);
    }
  }

  private void mergeChanges(RecordOperationRequest operation, RecordAbstract record,
      byte recordType) {
    if (record instanceof EntityImpl entity) {
      entity.deserializeFields();
      entity.clearTransactionTrackData();

      if (recordType == DocumentSerializerDelta.DELTA_RECORD_TYPE) {
        var delta = DocumentSerializerDelta.instance();
        delta.deserializeDelta(getDatabaseSession(), operation.getRecord(), entity);
      } else {
        var phantom = (EntityImpl) RecordSerializerNetworkV37.INSTANCE.fromStream(
            getDatabaseSession(),
            operation.getRecord(), null);
        entity.movePropertiesFromOtherEntity(phantom);
      }
    }
  }

  /**
   * Unmarshalls collections. This prevent temporary RIDs remains stored as are.
   */
  protected static void unmarshallRecord(final DBRecord iRecord) {
    if (iRecord instanceof EntityImpl) {
      ((EntityImpl) iRecord).deserializeFields();
    }
  }

  private boolean checkCallHooks(RecordId id, byte type) {
    var entry = recordOperations.get(id);
    return entry == null || entry.type != type;
  }

  private RecordOperation preAddRecord(RecordAbstract record, final byte iStatus) {
    changed = true;
    checkTransactionValid();

    var callHooks = checkCallHooks(record.getIdentity(), iStatus);
    if (callHooks) {
      switch (iStatus) {
        case RecordOperation.CREATED: {
          session.beforeCreateOperations(record, null);
        }
        break;
        case RecordOperation.UPDATED: {
          session.beforeUpdateOperations(record, null);
        }
        break;

        case RecordOperation.DELETED:
          session.beforeDeleteOperations(record, null);
          break;
      }
    }
    try {
      final var rid = record.getIdentity();

      if (!rid.isPersistent() && !rid.isTemporary()) {
        var oldRid = rid.copy();
        if (rid.getClusterPosition() == RecordId.CLUSTER_POS_INVALID) {
          rid.setClusterPosition(newRecordsPositionsGenerator--);
          generatedOriginalRecordIdMap.put(rid, oldRid);
        }
      }

      var txEntry = getRecordEntry(rid);

      if (txEntry == null) {
        // NEW ENTRY: JUST REGISTER IT
        var status = iStatus;
        if (status == RecordOperation.UPDATED && record.getIdentity().isTemporary()) {
          status = RecordOperation.CREATED;
        }
        txEntry = new RecordOperation(record, status);
        recordOperations.put(rid.copy(), txEntry);
      } else {
        // that is important to keep links to rids of this record inside transaction to be updated
        // after commit
        RecordInternal.setIdentity(record, txEntry.record.getIdentity());
        // UPDATE PREVIOUS STATUS
        if (txEntry.record != record) {
          throw new IllegalStateException(
              "Record " + rid + " is already presented in transaction with another instance");
        }

        switch (txEntry.type) {
          case RecordOperation.UPDATED:
            if (iStatus == RecordOperation.DELETED) {
              txEntry.type = RecordOperation.DELETED;
            }
            break;
          case RecordOperation.DELETED:
            break;
          case RecordOperation.CREATED:
            if (iStatus == RecordOperation.DELETED) {
              recordOperations.remove(rid);
              // txEntry.type = RecordOperation.DELETED;
            }
            break;
        }
      }

      if (!rid.isPersistent() && !rid.isTemporary()) {
        var oldRid = rid.copy();
        if (rid.getClusterId() == RecordId.CLUSTER_ID_INVALID) {
          session.assignAndCheckCluster(record, null);
          generatedOriginalRecordIdMap.put(rid, oldRid);
        }
      }

      txEntry.callHooksOnServerTx = callHooks;
      return txEntry;
    } catch (Exception e) {
      if (callHooks) {
        switch (iStatus) {
          case RecordOperation.CREATED:
            session.callbackHooks(RecordHook.TYPE.CREATE_FAILED, record);
            break;
          case RecordOperation.UPDATED:
            session.callbackHooks(RecordHook.TYPE.UPDATE_FAILED, record);
            break;
          case RecordOperation.DELETED:
            session.callbackHooks(RecordHook.TYPE.DELETE_FAILED, record);
            break;
        }
      }

      throw BaseException.wrapException(
          new DatabaseException(session, "Error on saving record " + record.getIdentity()), e,
          session);
    }
  }

  private void postAddRecord(DBRecord record, final byte iStatus, boolean callHooks) {
    checkTransactionValid();
    try {
      if (callHooks) {
        switch (iStatus) {
          case RecordOperation.CREATED:
            session.afterCreateOperations(record);
            break;
          case RecordOperation.UPDATED:
            session.afterUpdateOperations(record);
            break;
          case RecordOperation.DELETED:
            session.afterDeleteOperations(record);
            break;
        }
      } else {
        switch (iStatus) {
          case RecordOperation.CREATED:
            if (record instanceof EntityImpl) {
              ClassIndexManager.checkIndexesAfterCreate((EntityImpl) record, getDatabaseSession());
            }
            break;
          case RecordOperation.UPDATED:
            if (record instanceof EntityImpl) {
              ClassIndexManager.checkIndexesAfterUpdate((EntityImpl) record, getDatabaseSession());
            }
            break;
          case RecordOperation.DELETED:
            if (record instanceof EntityImpl) {
              ClassIndexManager.checkIndexesAfterDelete((EntityImpl) record, getDatabaseSession());
            }
            break;
        }
      }
      // RESET TRACKING
      if (record instanceof EntityImpl && ((EntityImpl) record).isTrackingChanges()) {
        ((EntityImpl) record).clearTrackData();
      }

    } catch (Exception e) {
      if (callHooks) {
        switch (iStatus) {
          case RecordOperation.CREATED:
            session.callbackHooks(RecordHook.TYPE.CREATE_FAILED, record);
            break;
          case RecordOperation.UPDATED:
            session.callbackHooks(RecordHook.TYPE.UPDATE_FAILED, record);
            break;
          case RecordOperation.DELETED:
            session.callbackHooks(RecordHook.TYPE.DELETE_FAILED, record);
            break;
        }
      }

      throw BaseException.wrapException(
          new DatabaseException(session, "Error on saving record " + record.getIdentity()), e,
          session);
    }
  }

  private void finalizeAddRecord(DBRecord record, final byte iStatus, boolean callHooks) {
    checkTransactionValid();
    if (callHooks) {
      switch (iStatus) {
        case RecordOperation.CREATED:
          session.callbackHooks(RecordHook.TYPE.FINALIZE_CREATION, record);
          break;
        case RecordOperation.UPDATED:
          session.callbackHooks(RecordHook.TYPE.FINALIZE_UPDATE, record);
          break;
        case RecordOperation.DELETED:
          session.callbackHooks(RecordHook.TYPE.FINALIZE_DELETION, record);
          break;
      }
    }
  }
}
