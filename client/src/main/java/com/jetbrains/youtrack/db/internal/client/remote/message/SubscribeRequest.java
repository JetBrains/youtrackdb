package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.internal.client.binary.BinaryRequestExecutor;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryProtocolSession;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryRequest;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import com.jetbrains.youtrack.db.internal.remote.RemoteDatabaseSessionInternal;
import java.io.IOException;

/**
 *
 */
public class SubscribeRequest implements BinaryRequest<SubscribeResponse> {

  private byte pushMessage;
  private BinaryRequest<? extends BinaryResponse> pushRequest;

  public SubscribeRequest() {
  }

  public SubscribeRequest(BinaryRequest<? extends BinaryResponse> request) {
    this.pushMessage = request.getCommand();
    this.pushRequest = request;
  }

  @Override
  public void write(RemoteDatabaseSessionInternal databaseSession, ChannelDataOutput network,
      BinaryProtocolSession session) throws IOException {
    network.writeByte(pushMessage);
    pushRequest.write(databaseSession, network, session);
  }

  @Override
  public void read(DatabaseSessionEmbedded databaseSession, ChannelDataInput channel,
      int protocolVersion)
      throws IOException {
    pushMessage = channel.readByte();
    pushRequest = createBinaryRequest(pushMessage);
    pushRequest.read(databaseSession, channel, protocolVersion);
  }

  private BinaryRequest<? extends BinaryResponse> createBinaryRequest(byte message) {
    switch (message) {
      case ChannelBinaryProtocol.SUBSCRIBE_PUSH_LIVE_QUERY:
        return new SubscribeLiveQueryRequest();
    }

    throw new DatabaseException("Unknown message response for code:" + message);
  }

  @Override
  public byte getCommand() {
    return ChannelBinaryProtocol.SUBSCRIBE_PUSH;
  }

  @Override
  public SubscribeResponse createResponse() {
    return new SubscribeResponse(pushRequest.createResponse());
  }

  @Override
  public BinaryResponse execute(BinaryRequestExecutor executor) {
    return executor.executeSubscribe(this);
  }

  public byte getPushMessage() {
    return pushMessage;
  }

  public BinaryRequest<? extends BinaryResponse> getPushRequest() {
    return pushRequest;
  }

  @Override
  public String getDescription() {
    return "Subscribe to push message";
  }
}
