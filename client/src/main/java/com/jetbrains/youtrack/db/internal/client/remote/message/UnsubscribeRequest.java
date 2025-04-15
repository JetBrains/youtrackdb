package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.api.exception.DatabaseException;
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

/**
 *
 */
public class UnsubscribeRequest implements BinaryRequest<UnsubscribeResponse> {

  private byte unsubscribeMessage;
  private BinaryRequest<? extends BinaryResponse> unsubscribeRequest;

  public UnsubscribeRequest(BinaryRequest<? extends BinaryResponse> unsubscribeRequest) {
    this.unsubscribeMessage = unsubscribeRequest.getCommand();
    this.unsubscribeRequest = unsubscribeRequest;
  }

  public UnsubscribeRequest() {
  }

  @Override
  public void write(DatabaseSessionInternal databaseSession, ChannelDataOutput network,
      StorageRemoteSession session) throws IOException {
    network.writeByte(unsubscribeMessage);
    unsubscribeRequest.write(databaseSession, network, session);
  }

  @Override
  public void read(DatabaseSessionInternal databaseSession, ChannelDataInput channel,
      int protocolVersion,
      RecordSerializerNetwork serializer)
      throws IOException {
    unsubscribeMessage = channel.readByte();
    unsubscribeRequest = createBinaryRequest(unsubscribeMessage);
    unsubscribeRequest.read(databaseSession, channel, protocolVersion, serializer);
  }

  private BinaryRequest<? extends BinaryResponse> createBinaryRequest(byte message) {
    if (message == ChannelBinaryProtocol.UNSUBSCRIBE_PUSH_LIVE_QUERY) {
      return new UnsubscribeLiveQueryRequest();
    }

    throw new DatabaseException("Unknown message response for code:" + message);
  }

  @Override
  public byte getCommand() {
    return ChannelBinaryProtocol.UNSUBSCRIBE_PUSH;
  }

  @Override
  public UnsubscribeResponse createResponse() {
    return new UnsubscribeResponse(unsubscribeRequest.createResponse());
  }

  @Override
  public BinaryResponse execute(BinaryRequestExecutor executor) {
    return executor.executeUnsubscribe(this);
  }

  @Override
  public String getDescription() {
    return "Unsubscribe from a push request";
  }

  public BinaryRequest<? extends BinaryResponse> getUnsubscribeRequest() {
    return unsubscribeRequest;
  }
}
