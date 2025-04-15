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
import javax.annotation.Nonnull;

public class SendTransactionStateRequest implements BinaryRequest<SendTransactionStateResponse> {

  private long txId;

  @Nonnull
  private final List<NetworkRecordOperation> operations;
  private List<RawPair<RecordId, Long>> receivedDirtyCounters;

  public SendTransactionStateRequest() {
    operations = new ArrayList<>();
  }

  public SendTransactionStateRequest(DatabaseSessionInternal session, long txId,
      Iterable<RecordOperation> operations, List<RawPair<RecordId, Long>> receivedDirtyCounters) {
    this.txId = txId;
    this.operations = new ArrayList<>();

    for (var txEntry : operations) {
      var request = new NetworkRecordOperation(session, txEntry);
      this.operations.add(request);
    }
    this.receivedDirtyCounters = receivedDirtyCounters;
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
    for (var entry : receivedDirtyCounters) {
      network.writeRID(entry.getFirst());
      network.writeLong(entry.getSecond());
    }
  }

  @Override
  public void read(DatabaseSessionInternal databaseSession, ChannelDataInput channel,
      int protocolVersion,
      RecordSerializerNetwork serializer)
      throws IOException {
    txId = channel.readLong();

    operations.clear();
    var operationsSize = channel.readInt();

    for (var i = 0; i < operationsSize; i++) {
      var entry = MessageHelper.readTransactionEntry(channel);
      operations.add(entry);
    }

    var receivedDirtyCountersSize = channel.readInt();
    receivedDirtyCounters = new ArrayList<>(receivedDirtyCountersSize);
    for (var i = 0; i < receivedDirtyCountersSize; i++) {
      var rid = channel.readRID();
      var dirtyCounter = channel.readLong();
      receivedDirtyCounters.add(new RawPair<>(rid, dirtyCounter));
    }
  }

  @Override
  public SendTransactionStateResponse createResponse() {
    return new SendTransactionStateResponse();
  }

  @Override
  public BinaryResponse execute(BinaryRequestExecutor executor) {
    return executor.executeSendTransactionState(this);
  }

  @Override
  public byte getCommand() {
    return ChannelBinaryProtocol.REQUEST_SEND_TRANSACTION_STATE;
  }

  @Override
  public String getDescription() {
    return "Sync state of transaction between session opened on client and its mirror on server";
  }

  public long getTxId() {
    return txId;
  }

  @Nonnull
  public List<NetworkRecordOperation> getOperations() {
    return operations;
  }

  public List<RawPair<RecordId, Long>> getReceivedDirtyCounters() {
    return receivedDirtyCounters;
  }
}
