package com.jetbrains.youtrackdb.internal.client.remote.message;

import com.jetbrains.youtrackdb.internal.client.remote.BinaryProtocolSession;
import com.jetbrains.youtrackdb.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrackdb.internal.enterprise.channel.binary.ChannelDataOutput;
import com.jetbrains.youtrackdb.internal.remote.RemoteDatabaseSessionInternal;
import java.io.IOException;

/**
 *
 */
public class SubscribeResponse implements BinaryResponse {

  private BinaryResponse response;

  public SubscribeResponse() {
  }

  public SubscribeResponse(BinaryResponse response) {
    this.response = response;
  }

  @Override
  public void write(DatabaseSessionEmbedded session, ChannelDataOutput channel,
      int protocolVersion)
      throws IOException {
    response.write(session, channel, protocolVersion);
  }

  @Override
  public void read(RemoteDatabaseSessionInternal db, ChannelDataInput network,
      BinaryProtocolSession session) throws IOException {
    response.read(db, network, session);
  }

  public BinaryResponse getResponse() {
    return response;
  }
}
