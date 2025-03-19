package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.internal.client.binary.BinaryRequestExecutor;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryRequest;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.StorageRemoteSession;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
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

  public BeginTransaction38Request(DatabaseSessionInternal session, long txId,
      Iterable<RecordOperation> operations) {
    super();
    this.txId = txId;
    this.operations = new ArrayList<>();

    for (var txEntry : operations) {
      var request = new NetworkRecordOperation(session, txEntry);
      this.operations.add(request);
    }
  }

  public BeginTransaction38Request() {
  }

  @Override
  public void write(DatabaseSessionInternal databaseSession, ChannelDataOutput network,
      StorageRemoteSession session) throws IOException {
    network.writeLong(txId);

    for (var txEntry : operations) {
      network.writeByte((byte) 1);
      MessageHelper.writeTransactionEntry(network, txEntry);
    }

    // END OF RECORD ENTRIES
    network.writeByte((byte) 0);

  }

  @Override
  public void read(DatabaseSessionInternal databaseSession, ChannelDataInput channel,
      int protocolVersion,
      RecordSerializerNetwork serializer)
      throws IOException {
    txId = channel.readLong();

    operations = new ArrayList<>();
    byte hasEntry;
    do {
      hasEntry = channel.readByte();
      if (hasEntry == 1) {
        var entry = MessageHelper.readTransactionEntry(channel);
        operations.add(entry);
      }
    } while (hasEntry == 1);
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
}
