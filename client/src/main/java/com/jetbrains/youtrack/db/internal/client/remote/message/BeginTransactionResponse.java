package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.StorageRemoteSession;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializer;
import com.jetbrains.youtrack.db.internal.core.tx.NetworkRecordOperation;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

public class BeginTransactionResponse implements BinaryResponse {

  private long txId;
  private ArrayList<RawPair<RecordId, RecordId>> oldToUpdatedRids;
  private ArrayList<NetworkRecordOperation> recordOperations;

  public BeginTransactionResponse(long txId, Map<RecordId, RecordId> updatedToOldRidMap,
      Collection<RecordOperation> recordOperations, DatabaseSessionInternal session) {
    this.txId = txId;
    this.oldToUpdatedRids = new ArrayList<>(updatedToOldRidMap.size());

    for (var entry : updatedToOldRidMap.entrySet()) {
      oldToUpdatedRids.add(new RawPair<>(entry.getValue(), entry.getKey()));
    }
    this.recordOperations = new ArrayList<>(recordOperations.size());
    for (var operation : recordOperations) {
      var recordOperation = new NetworkRecordOperation(session, operation);
      this.recordOperations.add(recordOperation);
    }
  }

  public BeginTransactionResponse() {
  }

  @Override
  public void write(DatabaseSessionInternal session, ChannelDataOutput channel,
      int protocolVersion, RecordSerializer serializer)
      throws IOException {
    channel.writeLong(txId);
    channel.writeInt(oldToUpdatedRids.size());

    for (var ids : oldToUpdatedRids) {
      channel.writeRID(ids.getFirst());
      channel.writeRID(ids.getSecond());
    }

    channel.writeInt(recordOperations.size());
    for (var recordOperation : recordOperations) {
      MessageHelper.writeTransactionEntry(channel, recordOperation);
    }
  }

  @Override
  public void read(DatabaseSessionInternal db, ChannelDataInput network,
      StorageRemoteSession session) throws IOException {
    txId = network.readLong();
    var size = network.readInt();
    oldToUpdatedRids = new ArrayList<>(size);

    while (size-- > 0) {
      var key = network.readRID();
      var value = network.readRID();
      oldToUpdatedRids.add(new RawPair<>(key, value));
    }

    var recordOperationsSize = network.readInt();
    recordOperations = new ArrayList<>(recordOperationsSize);

    for (var i = 0; i < recordOperationsSize; i++) {
      var recordOperation = MessageHelper.readTransactionEntry(network);
      recordOperations.add(recordOperation);
    }
  }

  public long getTxId() {
    return txId;
  }

  public ArrayList<RawPair<RecordId, RecordId>> getOldToUpdatedRids() {
    return oldToUpdatedRids;
  }

  public ArrayList<NetworkRecordOperation> getRecordOperations() {
    return recordOperations;
  }
}
