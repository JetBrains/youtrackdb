package com.jetbrains.youtrack.db.internal.client.remote.db;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RecordHook;
import com.jetbrains.youtrack.db.internal.client.remote.message.tx.RecordOperation38Response;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionAbstract;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.RecordInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.DocumentSerializerDelta;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionIndexChanges.OPERATION;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionOptimistic;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 *
 */
public class FrontendTransactionOptimisticClient extends FrontendTransactionOptimistic {

  private final Set<String> indexChanged = new HashSet<>();

  public FrontendTransactionOptimisticClient(DatabaseSessionAbstract iDatabase, boolean readOnly) {
    super(iDatabase, readOnly);
  }

  public FrontendTransactionOptimisticClient(DatabaseSessionAbstract iDatabase) {
    super(iDatabase);
  }

  public void replaceContent(List<RecordOperation38Response> operations) {

    Map<RecordId, RecordOperation> oldEntries = new LinkedHashMap<>(this.recordOperations);
    this.recordOperations.clear();
    this.recordsInTransaction.clear();

    var createCount = -2; // Start from -2 because temporary rids start from -2
    var db = getDatabaseSession();
    for (var operation : operations) {
      RecordAbstract record = null;
      var op = oldEntries.get(operation.getOldId());
      if (op != null) {
        record = op.record;
      }
      var localCache = db.getLocalCache();
      if (record == null) {
        record = localCache.findRecord(operation.getOldId());
      }
      if (record != null) {
        RecordInternal.unsetDirty(record);
        record.unload();
      } else {
        record =
            YouTrackDBEnginesManager.instance()
                .getRecordFactoryManager()
                .newInstance(operation.getRecordType(), operation.getOldId(), session);
        RecordInternal.unsetDirty(record);
      }
      if (operation.getType() == RecordOperation.UPDATED
          && operation.getRecordType() == EntityImpl.RECORD_TYPE) {
        // keep rid instance to support links consistency
        record.fromStream(operation.getOriginal());
        var deltaSerializer = DocumentSerializerDelta.instance();
        deltaSerializer.deserializeDelta(db, operation.getRecord(),
            (EntityImpl) record);
      } else {
        record.fromStream(operation.getRecord());
      }

      var rid = record.getIdentity();
      var operationId = operation.getId();

      rid.setClusterAndPosition(operationId.getClusterId(), operationId.getClusterPosition());

      RecordInternal.setVersion(record, operation.getVersion());
      RecordInternal.setContentChanged(record, operation.isContentChanged());
      if (operation.getType() == RecordOperation.UPDATED
          || operation.getType() == RecordOperation.CREATED) {
        localCache.updateRecord(record, session);
      } else if (operation.getType() == RecordOperation.DELETED) {
        localCache.deleteRecord(operation.getOldId());
      } else {
        throw new IllegalStateException("Unsupported operation type: " + operation.getType());
      }

      var callHook = checkCallHook(oldEntries, operation.getId(), operation.getType());
      addRecord(record, operation.getType(), null, callHook);
      if (operation.getType() == RecordOperation.CREATED) {
        createCount--;
      }
    }
    newRecordsPositionsGenerator = createCount;
  }

  private static boolean checkCallHook(Map<RecordId, RecordOperation> oldEntries, RecordId rid,
      byte type) {
    var val = oldEntries.get(rid);
    return val == null || val.type != type;
  }

  public void addRecord(
      RecordAbstract iRecord, final byte iStatus, final String iClusterName, boolean callHook) {
    try {
      if (callHook) {
        switch (iStatus) {
          case RecordOperation.CREATED: {
            session.beforeCreateOperations(iRecord, iClusterName);
          }
          break;
          case RecordOperation.UPDATED: {
            session.beforeUpdateOperations(iRecord, iClusterName);
          }
          break;

          case RecordOperation.DELETED:
            session.beforeDeleteOperations(iRecord, iClusterName);
            break;
        }
      }
      try {
        final var rid = iRecord.getIdentity();
        var txEntry = getRecordEntry(rid);

        if (txEntry == null) {
          if (!(rid.isTemporary() && iStatus != RecordOperation.CREATED)) {
            // NEW ENTRY: JUST REGISTER IT
            txEntry = new RecordOperation(iRecord, iStatus);
            recordOperations.put(rid.copy(), txEntry);
            recordsInTransaction.add(rid);
          }
        } else {
          // UPDATE PREVIOUS STATUS
          if (txEntry.record != iRecord) {
            throw new IllegalStateException(
                "Transaction already contains different record instance with id" + rid);
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
                recordsInTransaction.remove(rid);
              }
              break;
          }
        }
        if (callHook) {
          switch (iStatus) {
            case RecordOperation.CREATED:
              session.callbackHooks(RecordHook.TYPE.AFTER_CREATE, iRecord);
              break;
            case RecordOperation.UPDATED:
              session.callbackHooks(RecordHook.TYPE.AFTER_UPDATE, iRecord);
              break;
            case RecordOperation.DELETED:
              session.callbackHooks(RecordHook.TYPE.AFTER_DELETE, iRecord);
              break;
          }
        }
      } catch (Exception e) {
        if (callHook) {
          switch (iStatus) {
            case RecordOperation.CREATED:
              session.callbackHooks(RecordHook.TYPE.CREATE_FAILED, iRecord);
              break;
            case RecordOperation.UPDATED:
              session.callbackHooks(RecordHook.TYPE.UPDATE_FAILED, iRecord);
              break;
            case RecordOperation.DELETED:
              session.callbackHooks(RecordHook.TYPE.DELETE_FAILED, iRecord);
              break;
          }
        }

        throw BaseException.wrapException(
            new DatabaseException(session, "Error on saving record " + iRecord.getIdentity()), e,
            session);
      }
    } finally {
      if (callHook) {
        switch (iStatus) {
          case RecordOperation.CREATED:
            session.callbackHooks(RecordHook.TYPE.FINALIZE_CREATION, iRecord);
            break;
          case RecordOperation.UPDATED:
            session.callbackHooks(RecordHook.TYPE.FINALIZE_UPDATE, iRecord);
            break;
          case RecordOperation.DELETED:
            session.callbackHooks(RecordHook.TYPE.FINALIZE_DELETION, iRecord);
            break;
        }
      }
    }
  }

  public Set<String> getIndexChanged() {
    return indexChanged;
  }

  @Override
  public void addIndexEntry(
      Index index, String iIndexName, OPERATION iOperation, Object key,
      Identifiable iValue) {
    this.indexChanged.add(index.getName());
  }
}
