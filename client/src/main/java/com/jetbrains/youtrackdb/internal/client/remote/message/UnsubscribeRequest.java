package com.jetbrains.youtrackdb.internal.client.remote.message;

import com.jetbrains.youtrackdb.api.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.client.binary.BinaryRequestExecutor;
import com.jetbrains.youtrackdb.internal.client.remote.BinaryProtocolSession;
import com.jetbrains.youtrackdb.internal.client.remote.BinaryRequest;
import com.jetbrains.youtrackdb.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import com.jetbrains.youtrackdb.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrackdb.internal.enterprise.channel.binary.ChannelDataOutput;
import com.jetbrains.youtrackdb.internal.remote.RemoteDatabaseSessionInternal;
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
  public void write(RemoteDatabaseSessionInternal databaseSession, ChannelDataOutput network,
      BinaryProtocolSession session) throws IOException {
    network.writeByte(unsubscribeMessage);
    unsubscribeRequest.write(databaseSession, network, session);
  }

  @Override
  public void read(DatabaseSessionEmbedded databaseSession, ChannelDataInput channel,
      int protocolVersion)
      throws IOException {
    unsubscribeMessage = channel.readByte();
    unsubscribeRequest = createBinaryRequest(unsubscribeMessage);
    unsubscribeRequest.read(databaseSession, channel, protocolVersion);
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
