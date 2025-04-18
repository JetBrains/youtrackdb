package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.StorageRemoteSession;
import com.jetbrains.youtrack.db.internal.client.remote.db.DatabaseSessionRemote;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import java.io.IOException;

public class ServerInfoResponse implements BinaryResponse {

  private String result;

  public ServerInfoResponse(String result) {
    this.result = result;
  }

  public ServerInfoResponse() {
  }

  @Override
  public void write(DatabaseSessionEmbedded session, ChannelDataOutput channel,
      int protocolVersion)
      throws IOException {
    channel.writeString(result);
  }

  @Override
  public void read(DatabaseSessionRemote db, ChannelDataInput network,
      StorageRemoteSession session) throws IOException {
    result = network.readString();
  }

  public String getResult() {
    return result;
  }
}
