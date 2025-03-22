package com.jetbrains.youtrack.db.internal.core.tx;

import com.jetbrains.youtrack.db.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrack.db.api.exception.TransactionException;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordElement.STATUS;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityHelper;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.EntitySerializerDelta;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetworkV37;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class FrontendClientServerTransaction extends FrontendTransactionImpl {

  private final LinkedHashMap<RecordId, RecordId> updatedToOldRecordIdMap = new LinkedHashMap<>();
  private final IdentityHashMap<RecordId, RecordId> updatedToOldRecordIdIdentityMap = new IdentityHashMap<>();

  private final HashMap<RecordId, RecordOperation> operationsToSendOnClient = new HashMap<>();
  private final IdentityHashMap<RecordId, RecordOperation> operationsToSendOnClientIdentityMap =
      new IdentityHashMap<>();

  private boolean mergeInProgress = false;

  /**
   * Dirty counters for records that were received from server. This map is used to synchronize
   * dirty counters between client and server.
   */
  private final HashMap<RecordId, Long> receivedDirtyCounters = new HashMap<>();
  private final IdentityHashMap<RecordId, Long> receivedDirtyCountersIdentityMap = new IdentityHashMap<>();

  public FrontendClientServerTransaction(DatabaseSessionInternal database, long txId) {
    super(database, txId);
    sentToServer = session.isRemote();
  }

  public FrontendClientServerTransaction(DatabaseSessionInternal database, long txId,
      boolean readOnly) {
    super(database, txId, readOnly);
    sentToServer = session.isRemote();
  }

  public void mergeReceivedTransaction(@Nonnull List<NetworkRecordOperation> receivedOperations,
      @Nonnull List<RawPair<RecordId, Long>> receivedDirtyCounters) {
    var deletedRecords = new ArrayList<RecordAbstract>(receivedOperations.size());
    try {
      updatedToOldRecordIdMap.clear();
      operationsToSendOnClient.clear();

      mergeInProgress = true;
      try {
        // sort operations to ensure that created and not represented in tx yet are processed first
        //that will ensure that all deserialized rids of newly created records be correcly mapped
        //to instances of record rids registered in tx
        receivedOperations.sort((operationOne, operationTwo) -> {
          var typeComparison = -Byte.compare(operationOne.getType(), operationTwo.getType());
          if (typeComparison == 0) {
            //not existing records should be processed first
            var txEntryOne = getRecordEntry(operationOne.getId());
            var txEntryTwo = getRecordEntry(operationTwo.getId());

            if (txEntryOne == null) {
              return -1;
            } else if (txEntryTwo == null) {
              return 1;
            }

            return 0;
          }

          return typeComparison;
        });

        var receivedDirtyCountersMap = new HashMap<RecordId, Long>(receivedDirtyCounters.size());
        for (var pair : receivedDirtyCounters) {
          receivedDirtyCountersMap.put(pair.first, pair.second);
        }

        var newRecordsWithNetworkOperations = new ArrayList<RawPair<RecordAbstract, NetworkRecordOperation>>(
            receivedOperations.size());

        for (var recordOperation : receivedOperations) {
          var txEntry = getRecordEntry(recordOperation.getId());

          if (txEntry != null) {
            var receivedDirtyCounter = receivedDirtyCountersMap.remove(
                (RecordId) recordOperation.getId());
            if (receivedDirtyCounter != null) {
              txEntry.dirtyCounterOnClientSide = receivedDirtyCounter;
            }

            if (txEntry.type == RecordOperation.DELETED) {
              throw new TransactionException(
                  session,
                  "Record " + recordOperation.getId() + " is already deleted in transaction");
            }

            if (recordOperation.getType() == RecordOperation.UPDATED
                || recordOperation.getType() == RecordOperation.CREATED) {
              if (recordOperation.getDirtyCounter() == 0) {
                throw new IllegalStateException(
                    "Dirty counter is 0 for record: " + txEntry.record + " operation: "
                        + recordOperation);
              }

              mergeChanges(recordOperation, txEntry.record);
              addRecordOperation(txEntry.record, RecordOperation.UPDATED);

              syncDirtyCounter(recordOperation, txEntry);
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

                var recordType = recordOperation.getRecordType();
                if (EntityHelper.isEntity(recordType)) {
                  ((EntityImpl) record).setClassNameWithoutPropertiesPostProcessing(
                      RecordSerializerNetworkV37.deserializeClassName(recordOperation.getRecord()));
                }

                var oldRid = record.getIdentity().copy();
                var createOperation = addRecordOperation(record, RecordOperation.CREATED);
                newRecordsWithNetworkOperations.add(new RawPair<>(record, recordOperation));

                if (!oldRid.equals(record.getIdentity())) {
                  updatedToOldRecordIdMap.put(record.getIdentity().copy(), oldRid);
                  originalChangedRecordIdMap.put(oldRid, record.getIdentity());
                  operationsBetweenCallbacks.put(record.getIdentity(), createOperation);
                }
              }
              case RecordOperation.UPDATED -> {
                if (session.isRemote()) {
                  var record =
                      YouTrackDBEnginesManager.instance()
                          .getRecordFactoryManager()
                          .newInstance(recordOperation.getRecordType(),
                              (RecordId) recordOperation.getId(),
                              session);
                  record.unsetDirty();
                  record.recordSerializer = RecordSerializerNetworkV37.INSTANCE;
                  record.fromStream(recordOperation.getRecord());
                  record.setDirty();

                  record.recordSerializer = session.getSerializer();

                  addRecordOperation(record, RecordOperation.UPDATED);
                  syncDirtyCounter(recordOperation, record.txEntry);
                } else {
                  var record = loadRecordAndCheckVersion(recordOperation);
                  mergeChanges(recordOperation, record);
                  addRecordOperation(record, RecordOperation.UPDATED);
                  syncDirtyCounter(recordOperation, record.txEntry);
                }
              }
              case RecordOperation.DELETED -> {
                if (session.isRemote()) {
                  var record =
                      YouTrackDBEnginesManager.instance()
                          .getRecordFactoryManager()
                          .newInstance(recordOperation.getRecordType(),
                              (RecordId) recordOperation.getId(),
                              session);
                  record.setInternalStatus(STATUS.LOADED);
                  var deletedOperation = addRecordOperation(record, RecordOperation.DELETED);
                  syncDirtyCounter(recordOperation, deletedOperation);
                  deletedRecords.add(record);
                } else {
                  var record = loadRecordAndCheckVersion(recordOperation);
                  record.delete();

                  var txOperation = getRecordEntry(record.getIdentity());
                  syncDirtyCounter(recordOperation, txOperation);
                }
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

        syncDirtyCountersFromClient(receivedDirtyCountersMap.entrySet().stream()
            .map(entry -> new RawPair<>(entry.getKey(), entry.getValue())));
      } finally {
        mergeInProgress = false;
      }

      preProcessRecordsAndExecuteCallCallbacks();

      for (var deletedRecord : deletedRecords) {
        deletedRecord.markDeletedInServerTx();
      }
    } catch (Exception e) {
      session.rollback(true);
      throw e;
    }
  }

  public void syncDirtyCountersFromClient(Stream<RawPair<RecordId, Long>> dirtyCounters) {
    dirtyCounters.forEach(receivedEntry -> {
      var rid = receivedEntry.getFirst();
      var dirtyCounter = receivedEntry.getSecond();
      var txEntry = getRecordEntry(rid);

      if (txEntry != null) {
        txEntry.dirtyCounterOnClientSide = dirtyCounter;
      }
    });
  }

  public void syncDirtyCountersAfterServerMerge() {
    for (var recordOperation : recordOperations.values()) {
      var record = recordOperation.record;
      if (record.getDirtyCounter() > recordOperation.dirtyCounterOnClientSide) {
        receivedDirtyCounters.put(record.getIdentity(), record.getDirtyCounter());
        recordOperation.dirtyCounterOnClientSide = record.getDirtyCounter();
      }
    }
  }

  public void clearReceivedDirtyCounters() {
    receivedDirtyCounters.clear();
  }

  private void syncDirtyCounter(NetworkRecordOperation recordOperation,
      RecordOperation txEntry) {
    if (txEntry.dirtyCounterOnClientSide
        > recordOperation.getDirtyCounter()) {
      throw new IllegalStateException("Client and server transactions are not synchronized "
          + "client dirty counter is " + txEntry.dirtyCounterOnClientSide
          + " and server dirty counter is " + recordOperation.getDirtyCounter());
    }
    if (txEntry.recordCallBackDirtyCounter > recordOperation.getDirtyCounter()) {
      throw new IllegalStateException("Client and server transactions are not synchronized "
          + "client callback dirty counter is " + txEntry.recordCallBackDirtyCounter
          + " and server dirty counter is " + recordOperation.getDirtyCounter());
    }

    txEntry.record.setDirty(recordOperation.getDirtyCounter());
    txEntry.dirtyCounterOnClientSide = recordOperation.getDirtyCounter();

    if (txEntry.recordCallBackDirtyCounter < recordOperation.getDirtyCounter()) {
      operationsBetweenCallbacks.put(txEntry.record.getIdentity(), txEntry);
    }

    if (session.isRemote()) {
      var removed = receivedDirtyCounters.put(txEntry.record.getIdentity(),
          txEntry.dirtyCounterOnClientSide);
      if (removed != null) {
        throw new IllegalStateException(
            "New dirty counter is received for record " + txEntry.record +
                " while old one was not send to server");
      }
    }
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
      if (operation.getRecordType() == EntitySerializerDelta.DELTA_RECORD_TYPE) {
        var delta = EntitySerializerDelta.instance();
        delta.deserializeDelta(getDatabaseSession(), operation.getRecord(), entity);
      } else {
        throw new UnsupportedOperationException("Only delta serialization is supported");
      }
    } else {
      var serializer = RecordSerializerNetworkV37.INSTANCE;
      serializer.fromStream(getDatabaseSession(), operation.getRecord(), record);
      record.recordSerializer = session.getSerializer();
    }
  }

  public Map<RecordId, RecordId> getUpdateToOldRecordIdMap() {
    return updatedToOldRecordIdMap;
  }

  public List<RecordOperation> getOperationsToSendOnClient() {
    return new ArrayList<>(operationsToSendOnClient.values());
  }

  public List<RawPair<RecordId, Long>> getReceivedDirtyCounters() {
    var receivedDirtyCounters = new ArrayList<RawPair<RecordId, Long>>(
        this.receivedDirtyCounters.size());
    for (var entry : this.receivedDirtyCounters.entrySet()) {
      receivedDirtyCounters.add(new RawPair<>(entry.getKey(), entry.getValue()));
    }
    return receivedDirtyCounters;
  }

  @Override
  public void onBeforeIdentityChange(Object source) {
    super.onBeforeIdentityChange(source);

    var rid = (RecordId) source;
    var removedOperations = operationsToSendOnClient.remove(source);

    if (removedOperations != null) {
      operationsToSendOnClientIdentityMap.put(rid, removedOperations);
    }

    var removedCounter = receivedDirtyCounters.remove(source);
    if (removedCounter != null) {
      receivedDirtyCountersIdentityMap.put(rid, removedCounter);
    }

    updatedToOldRecordIdIdentityMap.put(rid, rid.copy());
  }

  @Override
  public void onAfterIdentityChange(Object source) {
    super.onAfterIdentityChange(source);

    var rid = (RecordId) source;

    var removedOperation = operationsToSendOnClientIdentityMap.remove(source);
    if (removedOperation != null) {
      operationsToSendOnClient.put(rid, removedOperation);
    }

    var removedCounter = receivedDirtyCountersIdentityMap.remove(source);
    if (removedCounter != null) {
      receivedDirtyCounters.put(rid, removedCounter);
    }

    var originalRid = updatedToOldRecordIdIdentityMap.remove(rid);
    var removedRid = updatedToOldRecordIdMap.put(rid.copy(), originalRid);

    if (removedRid != null) {
      throw new IllegalStateException("Record " + rid
          + " is already registered in transaction with original id " + removedRid);
    }
  }

  @Override
  @Nullable
  public List<RecordId> preProcessRecordsAndExecuteCallCallbacks() {
    var newRecordsToDelete = super.preProcessRecordsAndExecuteCallCallbacks();

    if (newRecordsToDelete != null) {
      for (var newRecord : newRecordsToDelete) {
        updatedToOldRecordIdMap.remove(newRecord);
        operationsToSendOnClient.remove(newRecord);
      }

      return newRecordsToDelete;
    }

    return null;
  }
}

