package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.internal.client.remote.RemotePushHandler;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 *
 */
public class PushDistributedConfigurationRequest
    implements BinaryPushRequest<BinaryPushResponse> {

  public EntityImpl configuration;
  private List<String> hosts;

  public PushDistributedConfigurationRequest(List<String> hosts) {
    this.hosts = hosts;
  }

  public PushDistributedConfigurationRequest() {
  }

  @Override
  public byte getPushCommand() {
    return ChannelBinaryProtocol.REQUEST_PUSH_DISTRIB_CONFIG;
  }

  @Override
  public void write(DatabaseSessionInternal session, ChannelDataOutput channel)
      throws IOException {
    channel.writeInt(hosts.size());
    for (var host : hosts) {
      channel.writeString(host);
    }
  }

  @Override
  public void read(DatabaseSessionInternal session, ChannelDataInput network) throws IOException {
    var size = network.readInt();
    hosts = new ArrayList<>(size);
    while (size-- > 0) {
      hosts.add(network.readString());
    }
  }

  @Override
  public BinaryPushResponse execute(DatabaseSessionInternal session, RemotePushHandler remote) {
    return remote.executeUpdateDistributedConfig(this);
  }

  @Nullable
  @Override
  public BinaryPushResponse createResponse() {
    return null;
  }

  public List<String> getHosts() {
    return hosts;
  }
}
