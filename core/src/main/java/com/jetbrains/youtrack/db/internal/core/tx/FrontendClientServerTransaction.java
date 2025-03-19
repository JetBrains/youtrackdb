package com.jetbrains.youtrack.db.internal.core.tx;

import com.jetbrains.youtrack.db.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrack.db.api.exception.TransactionException;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.DocumentSerializerDelta;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetworkV37;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;

public class FrontendClientServerTransaction extends FrontendTransactionImpl {

  private final LinkedHashMap<RecordId, RecordId> updatedToOldRecordIdMap = new LinkedHashMap<>();
  private final IdentityHashMap<RecordId, RecordId> updatedToOldRecordIdIdentityMap = new IdentityHashMap<>();

  private final HashMap<RecordId, RecordOperation> operationsToSendOnClient = new HashMap<>();
  private final IdentityHashMap<RecordId, RecordOperation> operationsToSendOnClientIdentityMap =
      new IdentityHashMap<>();

  private boolean mergeInProgress = false;

  public FrontendClientServerTransaction(DatabaseSessionInternal database, long txId) {
    super(database, txId);
    sentToServer = session.isRemote();
  }

  public FrontendClientServerTransaction(DatabaseSessionInternal database, long txId,
      boolean readOnly) {
    super(database, txId, readOnly);
    sentToServer = session.isRemote();
  }

  public void mergeReceivedTransaction(@Nonnull List<NetworkRecordOperation> operations) {
    updatedToOldRecordIdMap.clear();
    operationsToSendOnClient.clear();

    mergeInProgress = true;
    try {
      // SORT OPERATIONS BY TYPE TO BE SURE THAT CREATES ARE PROCESSED FIRST
      operations.sort(Comparator.comparingInt(NetworkRecordOperation::getType).reversed());

      var newRecordsWithNetworkOperations = new ArrayList<RawPair<RecordAbstract, NetworkRecordOperation>>(
          operations.size());

      for (var recordOperation : operations) {
        var txEntry = getRecordEntry(recordOperation.getId());

        if (txEntry != null) {
          if (txEntry.type == RecordOperation.DELETED) {
            throw new TransactionException(
                session,
                "Record " + recordOperation.getId() + " is already deleted in transaction");
          }

          if (recordOperation.getType() == RecordOperation.UPDATED
              || recordOperation.getType() == RecordOperation.CREATED) {
            mergeChanges(recordOperation, txEntry.record);

            syncDirtyCounter(recordOperation, txEntry);
            addRecordOperation(txEntry.record, recordOperation.getType());
          } else {
            // DELETED
            throw new TransactionException(
                session,
                "Invalid operation type " + recordOperation.getType() + " for record "
                    + recordOperation.getId());
          }
        } else {
          switch (recordOperation.getType()) {
            case RecordOperation.CREATED -> {
              var record =
                  YouTrackDBEnginesManager.instance()
                      .getRecordFactoryManager()
                      .newInstance(recordOperation.getRecordType(),
                          (RecordId) recordOperation.getId(),
                          session);
              record.unsetDirty();
              record.recordSerializer = RecordSerializerNetworkV37.INSTANCE;
              record.fromStream(recordOperation.getRecord());

              if (recordOperation.getRecordType() == EntityImpl.RECORD_TYPE) {
                ((EntityImpl) record).setClassNameWithoutPropertiesPostProcessing(
                    RecordSerializerNetworkV37.deserializeClassName(recordOperation.getRecord()));
              }

              var oldRid = record.getIdentity().copy();
              addRecordOperation(record, RecordOperation.CREATED);
              newRecordsWithNetworkOperations.add(new RawPair<>(record, recordOperation));

              if (!oldRid.equals(record.getIdentity())) {
                updatedToOldRecordIdMap.put(record.getIdentity().copy(), oldRid);
                originalChangedRecordIdMap.put(oldRid, record.getIdentity());
              }
            }
            case RecordOperation.UPDATED -> {
              var record = loadRecordAndCheckVersion(recordOperation);
              mergeChanges(recordOperation, record);

              syncDirtyCounter(recordOperation, record.txEntry);

            }
            case RecordOperation.DELETED -> {
              var record = loadRecordAndCheckVersion(recordOperation);
              record.delete();

              var txOperation = getRecordEntry(record.getIdentity());
              syncDirtyCounter(recordOperation, txOperation);
            }
            default -> {
              throw new TransactionException(
                  session,
                  "Invalid operation type " + recordOperation.getType() + " for record "
                      + recordOperation.getId());
            }
          }
        }
      }

      for (var recordWithNetworkOperationPair : newRecordsWithNetworkOperations) {
        var record = recordWithNetworkOperationPair.first;
        if (record.sourceIsParsedByProperties()) {
          throw new TransactionException("Record " + record.getIdentity()
              + " is early parsed by properties, that can lead to inconsistent state of link based properties");
        }

        assert record.recordSerializer == RecordSerializerNetworkV37.INSTANCE;
        if (record instanceof EntityImpl entity) {
          //deserialize properties using network serializer and update all links of new records to correct values
          entity.checkForProperties();
        }

        //back to normal serializer for entity
        record.recordSerializer = session.getSerializer();
        syncDirtyCounter(recordWithNetworkOperationPair.second, record.txEntry);
      }
    } finally {
      mergeInProgress = false;
    }

    preProcessRecordsAndExecuteCallCallbacks();
  }

  private static void syncDirtyCounter(NetworkRecordOperation recordOperation,
      RecordOperation txEntry) {
    if (txEntry.dirtyCounterOnClientSide
        >= recordOperation.getDirtyCounter()) {
      throw new IllegalStateException("Client and server transactions are not synchronized "
          + "client dirty counter is " + txEntry.dirtyCounterOnClientSide
          + " and server dirty counter is " + recordOperation.getDirtyCounter());
    }
    txEntry.record.setDirty(recordOperation.getDirtyCounter());
    txEntry.dirtyCounterOnClientSide = recordOperation.getDirtyCounter();
  }

  private RecordAbstract loadRecordAndCheckVersion(NetworkRecordOperation recordOperation) {
    var rid = recordOperation.getId();
    var version = recordOperation.getVersion();

    var record = (RecordAbstract) session.load(rid);
    if (record.getVersion() != version) {
      throw new ConcurrentModificationException(session.getDatabaseName(),
          record.getIdentity(),
          record.getVersion(), version, RecordOperation.DELETED);
    }
    return record;
  }

  @Override
  public RecordOperation addRecordOperation(RecordAbstract record, byte status) {
    var txEntry = super.addRecordOperation(record, status);
    if (!mergeInProgress
        && txEntry.dirtyCounterOnClientSide < record.getDirtyCounter()) {
      operationsToSendOnClient.put(record.getIdentity(), txEntry);
    }

    return txEntry;
  }

  private void mergeChanges(NetworkRecordOperation operation, RecordAbstract record) {
    if (record instanceof EntityImpl entity) {
      if (operation.getRecordType() == DocumentSerializerDelta.DELTA_RECORD_TYPE) {
        var delta = DocumentSerializerDelta.instance();
        delta.deserializeDelta(getDatabaseSession(), operation.getRecord(), entity);
      } else {
        throw new UnsupportedOperationException("Only delta serialization is supported");
      }
    } else {
      var serializer = RecordSerializerNetworkV37.INSTANCE;
      serializer.fromStream(getDatabaseSession(), operation.getRecord(), record);
    }
  }

  public Map<RecordId, RecordId> getUpdateToOldRecordIdMap() {
    return updatedToOldRecordIdMap;
  }

  public List<RecordOperation> getOperationsToSendOnClient() {
    return new ArrayList<>(operationsToSendOnClient.values());
  }

  @Override
  public void onBeforeIdentityChange(Object source) {
    super.onBeforeIdentityChange(source);

    var rid = (RecordId) source;
    var removed = operationsToSendOnClient.remove(source);

    if (removed != null) {
      operationsToSendOnClientIdentityMap.put(rid, removed);
    }

    updatedToOldRecordIdIdentityMap.put(rid, rid.copy());
  }

  @Override
  public void onAfterIdentityChange(Object source) {
    super.onAfterIdentityChange(source);

    var rid = (RecordId) source;
    var removed = operationsToSendOnClientIdentityMap.remove(source);
    if (removed != null) {
      operationsToSendOnClient.put(rid, removed);
    }

    var originalRid = updatedToOldRecordIdIdentityMap.remove(rid);
    var removedRid = updatedToOldRecordIdMap.put(rid.copy(), originalRid);

    if (removedRid != null) {
      throw new IllegalStateException("Record " + rid
          + " is already registered in transaction with original id " + removedRid);
    }
  }
}

