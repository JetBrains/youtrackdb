package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.internal.client.binary.BinaryRequestExecutor;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryRequest;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.StorageRemoteSession;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetwork;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 *
 */
public class FetchTransaction38Request implements BinaryRequest<FetchTransaction38Response> {

  private long txId;
  private List<RawPair<RecordId, Long>> receivedDirtyCounters;

  public FetchTransaction38Request() {
  }

  public FetchTransaction38Request(long txId, List<RawPair<RecordId, Long>> receivedDirtyCounters) {
    this.txId = txId;
    this.receivedDirtyCounters = receivedDirtyCounters;
  }

  public List<RawPair<RecordId, Long>> getReceivedDirtyCounters() {
    return receivedDirtyCounters;
  }

  @Override
  public void write(DatabaseSessionInternal databaseSession, ChannelDataOutput network,
      StorageRemoteSession session) throws IOException {
    network.writeLong(txId);

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
    this.txId = channel.readLong();

    var receivedDirtyCountersSize = channel.readInt();
    this.receivedDirtyCounters = new ArrayList<>(receivedDirtyCountersSize);
    for (var i = 0; i < receivedDirtyCountersSize; i++) {
      var recordId = channel.readRID();
      var counter = channel.readLong();

      this.receivedDirtyCounters.add(new RawPair<>(recordId, counter));
    }
  }

  @Override
  public byte getCommand() {
    return ChannelBinaryProtocol.REQUEST_TX_FETCH;
  }

  @Override
  public FetchTransaction38Response createResponse() {
    return new FetchTransaction38Response();
  }

  @Override
  public BinaryResponse execute(BinaryRequestExecutor executor) {
    return executor.executeFetchTransaction38(this);
  }

  @Override
  public String getDescription() {
    return "Fetch Transaction";
  }

  public long getTxId() {
    return txId;
  }
}
