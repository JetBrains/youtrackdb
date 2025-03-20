package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.internal.client.binary.BinaryRequestExecutor;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryRequest;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.StorageRemoteSession;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetwork;
import com.jetbrains.youtrack.db.internal.core.tx.NetworkRecordOperation;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


public class BeginTransaction38Request implements BinaryRequest<BeginTransactionResponse> {
  private long txId;
  private List<NetworkRecordOperation> operations;
  private List<RawPair<RecordId, Long>> receivedDirtyCounters;

  public BeginTransaction38Request(DatabaseSessionInternal session, long txId,
      Iterable<RecordOperation> operations, List<RawPair<RecordId, Long>> receivedDirtyCounters) {
    super();
    this.txId = txId;
    this.operations = new ArrayList<>();

    for (var txEntry : operations) {
      var request = new NetworkRecordOperation(session, txEntry);
      this.operations.add(request);
    }
    this.receivedDirtyCounters = receivedDirtyCounters;
  }

  public BeginTransaction38Request() {
  }

  @Override
  public void write(DatabaseSessionInternal databaseSession, ChannelDataOutput network,
      StorageRemoteSession session) throws IOException {
    network.writeLong(txId);

    network.writeInt(operations.size());
    for (var txEntry : operations) {
      MessageHelper.writeTransactionEntry(network, txEntry);
    }

    network.writeInt(receivedDirtyCounters.size());
    for (var pair : receivedDirtyCounters) {
      network.writeRID(pair.getFirst());
      network.writeLong(pair.getSecond());
    }
  }

  @Override
  public void read(DatabaseSessionInternal databaseSession, ChannelDataInput channel,
      int protocolVersion,
      RecordSerializerNetwork serializer)
      throws IOException {
    txId = channel.readLong();

    var operationsSize = channel.readInt();

    operations = new ArrayList<>(operationsSize);
    for (var i = 0; i < operationsSize; i++) {
      var entry = MessageHelper.readTransactionEntry(channel);
      operations.add(entry);
    }

    var receivedDirtyCountersSize = channel.readInt();
    receivedDirtyCounters = new ArrayList<>(receivedDirtyCountersSize);
    for (var i = 0; i < receivedDirtyCountersSize; i++) {
      var recordId = channel.readRID();
      var counter = channel.readLong();
      receivedDirtyCounters.add(new RawPair<>(recordId, counter));
    }
  }

  @Override
  public byte getCommand() {
    return ChannelBinaryProtocol.REQUEST_TX_BEGIN;
  }

  @Override
  public BeginTransactionResponse createResponse() {
    return new BeginTransactionResponse();
  }

  @Override
  public BinaryResponse execute(BinaryRequestExecutor executor) {
    return executor.executeBeginTransaction(this);
  }

  @Override
  public String getDescription() {
    return "Begin Transaction";
  }

  public List<NetworkRecordOperation> getOperations() {
    return operations;
  }

  public long getTxId() {
    return txId;
  }

  public List<RawPair<RecordId, Long>> getReceivedDirtyCounters() {
    return receivedDirtyCounters;
  }
}
