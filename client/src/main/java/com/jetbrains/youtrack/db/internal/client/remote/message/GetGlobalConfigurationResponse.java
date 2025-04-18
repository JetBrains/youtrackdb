package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.StorageRemoteSession;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import com.jetbrains.youtrack.db.internal.remote.RemoteDatabaseSessionInternal;
import java.io.IOException;

public class GetGlobalConfigurationResponse implements BinaryResponse {

  private String value;

  public GetGlobalConfigurationResponse(String value) {
    this.value = value;
  }

  public GetGlobalConfigurationResponse() {
  }

  @Override
  public void write(DatabaseSessionEmbedded session, ChannelDataOutput channel,
      int protocolVersion)
      throws IOException {
    channel.writeString(value);
  }

  @Override
  public void read(RemoteDatabaseSessionInternal db, ChannelDataInput network,
      StorageRemoteSession session) throws IOException {
    value = network.readString();
  }

  public String getValue() {
    return value;
  }
}
