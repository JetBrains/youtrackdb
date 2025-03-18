package com.jetbrains.youtrack.db.internal.server.tx;

import com.jetbrains.youtrack.db.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrack.db.api.exception.TransactionException;
import com.jetbrains.youtrack.db.internal.client.remote.message.tx.RecordOperationRequest;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.RecordInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.DocumentSerializerDelta;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetworkV37;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionOptimistic;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public class FrontendTransactionOptimisticServer extends FrontendTransactionOptimistic {

  private final HashMap<RecordId, RecordId> generatedOriginalRecordIdMap = new HashMap<>();
  private final IdentityHashMap<RecordId, RecordId> generatedOriginalRecordIdIdentityMap = new IdentityHashMap<>();

  private final HashMap<RecordId, RecordOperation> operationsToSendOnClient = new HashMap<>();
  private final IdentityHashMap<RecordId, RecordOperation> operationsToSendOnClientIdentityMap =
      new IdentityHashMap<>();

  private boolean mergeInProgress = false;

  public FrontendTransactionOptimisticServer(DatabaseSessionInternal database, long txId) {
    super(database, txId);
  }

  public void mergeReceivedTransaction(List<RecordOperationRequest> operations) {
    if (operations == null) {
      return;
    }

    mergeInProgress = true;
    try {
      generatedOriginalRecordIdMap.clear();
      operationsToSendOnClient.clear();

      // SORT OPERATIONS BY TYPE TO BE SURE THAT CREATES ARE PROCESSED FIRST
      operations.sort(Comparator.comparingInt(RecordOperationRequest::getType).reversed());

      var newRecords = new ArrayList<RecordAbstract>(operations.size());

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
            addRecordOperation(txEntry.record, recordOperation.getType());
          } else {
            // DELETED
            throw new TransactionException(
                session,
                "Invalid operation type " + recordOperation.getType() + " for record "
                    + recordOperation.getId());
          }

          if (txEntry.recordCallBackUpdateCounterOnAnotherSide
              != recordOperation.getDirtyCounter()) {
            throw new IllegalStateException("Client and server transactions are not synchronized "
                + "client dirty counter is " + txEntry.recordCallBackUpdateCounterOnAnotherSide
                + " and server dirty counter is " + recordOperation.getDirtyCounter());
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
              RecordInternal.unsetDirty(record);

              record.recordSerializer = RecordSerializerNetworkV37.INSTANCE;

              record.fromStream(recordOperation.getRecord());

              addRecordOperation(record, RecordOperation.CREATED);
              newRecords.add(record);
            }
            case RecordOperation.UPDATED -> {
              var record = loadRecordAndCheckVersion(recordOperation);
              mergeChanges(recordOperation, record);
              addRecordOperation(record, RecordOperation.UPDATED);
            }
            case RecordOperation.DELETED -> {
              var record = loadRecordAndCheckVersion(recordOperation);
              record.delete();
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

      for (var record : newRecords) {
        if (record.sourceIsParsedByProperties()) {
          throw new TransactionException("Record " + record.getIdentity()
              + " is early parsed by properties, that can lead to inconsistent state of link based properties");
        }

        if (record instanceof EntityImpl entity) {
          //deserialize properties using network serializer and update all links of new records to correct values
          assert entity.recordSerializer == RecordSerializerNetworkV37.INSTANCE;

          entity.checkForProperties();
          assert record.sourceIsParsedByProperties();

          //back to normal serializer for entity
          entity.recordSerializer = session.getSerializer();
        }
      }
    } finally {
      mergeInProgress = false;
    }

    preProcessRecordsAndExecuteCallCallbacks();
  }

  private RecordAbstract loadRecordAndCheckVersion(RecordOperationRequest recordOperation) {
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

    //this condition ensures that addRecordOperation is always called if record changes are needed to be sent
    //to the client look EngineImpl#registerInTx
    if (txEntry.recordCallBackDirtyCounter < txEntry.recordCallBackUpdateCounterOnAnotherSide) {
      throw new IllegalStateException("Record " + record.getIdentity()
          + " is registered in transaction with callback dirty counter "
          + txEntry.recordCallBackDirtyCounter
          + " and client dirty counter "
          + txEntry.recordCallBackUpdateCounterOnAnotherSide
          + " that will lead to inconsistent state");
    }

    if (!mergeInProgress
        && txEntry.recordCallBackUpdateCounterOnAnotherSide < record.getDirtyCounter()) {
      operationsToSendOnClient.put(record.getIdentity(), txEntry);
    }

    return txEntry;
  }

  private void mergeChanges(RecordOperationRequest operation, RecordAbstract record) {
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

  public Map<RecordId, RecordId> getGeneratedOriginalRidsMap() {
    return generatedOriginalRecordIdMap;
  }

  @Override
  public void onBeforeIdentityChange(Object source) {
    super.onBeforeIdentityChange(source);

    var rid = (RecordId) source;
    var removed = operationsToSendOnClient.remove(source);

    if (removed != null) {
      operationsToSendOnClientIdentityMap.put(rid, removed);
    }

    generatedOriginalRecordIdIdentityMap.put(rid, rid.copy());
  }

  @Override
  public void onAfterIdentityChange(Object source) {
    super.onAfterIdentityChange(source);

    var rid = (RecordId) source;
    var removed = operationsToSendOnClientIdentityMap.remove(source);
    if (removed != null) {
      operationsToSendOnClient.put(rid, removed);
    }

    var originalRid = generatedOriginalRecordIdIdentityMap.remove(rid);
    var removedRid = generatedOriginalRecordIdMap.put(rid.copy(), originalRid);

    if (removedRid != null) {
      throw new IllegalStateException("Record " + rid
          + " is already registered in transaction with original id " + removedRid);
    }
  }
}
