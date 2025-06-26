package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.internal.client.remote.BinaryProtocolSession;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import com.jetbrains.youtrack.db.internal.remote.RemoteDatabaseSessionInternal;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class ListGlobalConfigurationsResponse implements BinaryResponse {

  private Map<String, String> configs;

  public ListGlobalConfigurationsResponse() {
  }

  public ListGlobalConfigurationsResponse(Map<String, String> configs) {
    super();
    this.configs = configs;
  }

  @Override
  public void write(DatabaseSessionEmbedded session, ChannelDataOutput channel,
      int protocolVersion)
      throws IOException {
    channel.writeShort((short) configs.size());
    for (var entry : configs.entrySet()) {
      channel.writeString(entry.getKey());
      channel.writeString(entry.getValue());
    }
  }

  @Override
  public void read(RemoteDatabaseSessionInternal db, ChannelDataInput network,
      BinaryProtocolSession session) throws IOException {
    configs = new HashMap<String, String>();
    final int num = network.readShort();
    for (var i = 0; i < num; ++i) {
      configs.put(network.readString(), network.readString());
    }
  }

  public Map<String, String> getConfigs() {
    return configs;
  }
}
