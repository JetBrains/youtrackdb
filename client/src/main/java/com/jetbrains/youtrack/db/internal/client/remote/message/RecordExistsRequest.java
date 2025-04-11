package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.client.binary.BinaryRequestExecutor;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryRequest;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.StorageRemoteSession;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetwork;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import java.io.IOException;

public class RecordExistsRequest implements BinaryRequest<RecordExistsResponse> {

  private RID recordId;

  public RecordExistsRequest() {
  }

  public RecordExistsRequest(RID recordId) {
    this.recordId = recordId;
  }

  @Override
  public void write(DatabaseSessionInternal databaseSession, ChannelDataOutput network,
      StorageRemoteSession session) throws IOException {
    network.writeRID(recordId);
  }

  @Override
  public void read(DatabaseSessionInternal databaseSession, ChannelDataInput channel,
      int protocolVersion,
      RecordSerializerNetwork serializer)
      throws IOException {
    recordId = channel.readRID();
  }

  @Override
  public byte getCommand() {
    return ChannelBinaryProtocol.REQUEST_RECORD_EXISTS;
  }

  @Override
  public String getDescription() {
    return "Check if record exists in storage";
  }

  public RID getRecordId() {
    return recordId;
  }

  @Override
  public RecordExistsResponse createResponse() {
    return new RecordExistsResponse();
  }

  @Override
  public BinaryResponse execute(BinaryRequestExecutor executor) {
    return executor.executeRecordExists(this);
  }
}
