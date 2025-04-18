package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.db.DatabaseSessionRemote;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.client.remote.StorageRemoteSession;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
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
  public void read(DatabaseSessionRemote db, ChannelDataInput network,
      StorageRemoteSession session) throws IOException {
    response.read(db, network, session);
  }

  public BinaryResponse getResponse() {
    return response;
  }
}
