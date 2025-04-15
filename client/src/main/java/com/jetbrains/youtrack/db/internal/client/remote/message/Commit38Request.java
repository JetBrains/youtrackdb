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
import java.util.Collection;
import java.util.List;
import javax.annotation.Nonnull;

/**
 *
 */
public class Commit38Request implements BinaryRequest<Commit37Response> {

  private long txId;
  private List<NetworkRecordOperation> operations;
  private List<RawPair<RecordId, Long>> receivedDirtyCounters;

  public Commit38Request() {
  }

  public Commit38Request(
      DatabaseSessionInternal session, long txId,
      @Nonnull Collection<RecordOperation> operations,
      @Nonnull List<RawPair<RecordId, Long>> receivedDirtyCounters) {
    this.txId = txId;

    List<NetworkRecordOperation> netOperations = new ArrayList<>();
    for (var txEntry : operations) {
      var request = new NetworkRecordOperation(session, txEntry);
      netOperations.add(request);
    }
    this.operations = netOperations;
    this.receivedDirtyCounters = receivedDirtyCounters;
  }

  @Override
  public void write(DatabaseSessionInternal databaseSession, ChannelDataOutput network,
      StorageRemoteSession session) throws IOException {
    // from 3.0 the the serializer is bound to the protocol
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

    var dirtyCountersSize = channel.readInt();
    receivedDirtyCounters = new ArrayList<>(dirtyCountersSize);
    for (var i = 0; i < dirtyCountersSize; i++) {
      var rid = channel.readRID();
      var counter = channel.readLong();
      receivedDirtyCounters.add(new RawPair<>(rid, counter));
    }
  }

  @Override
  public byte getCommand() {
    return ChannelBinaryProtocol.REQUEST_TX_COMMIT;
  }

  @Override
  public Commit37Response createResponse() {
    return new Commit37Response();
  }

  @Override
  public BinaryResponse execute(BinaryRequestExecutor executor) {
    return executor.executeCommit38(this);
  }

  @Override
  public String getDescription() {
    return "Commit";
  }

  public long getTxId() {
    return txId;
  }

  public List<NetworkRecordOperation> getOperations() {
    return operations;
  }

  public List<RawPair<RecordId, Long>> getReceivedDirtyCounters() {
    return receivedDirtyCounters;
  }
}
