package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.internal.client.binary.BinaryRequestExecutor;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryRequest;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.StorageRemoteSession;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import com.jetbrains.youtrack.db.internal.remote.RemoteDatabaseSessionInternal;
import java.io.IOException;

public class GetGlobalConfigurationRequest
    implements BinaryRequest<GetGlobalConfigurationResponse> {

  private String key;

  public GetGlobalConfigurationRequest(String key) {
    this.key = key;
  }

  public GetGlobalConfigurationRequest() {
  }

  @Override
  public void write(RemoteDatabaseSessionInternal databaseSession, ChannelDataOutput network,
      StorageRemoteSession session) throws IOException {
    network.writeString(key);
  }

  @Override
  public void read(DatabaseSessionEmbedded databaseSession, ChannelDataInput channel,
      int protocolVersion)
      throws IOException {
    key = channel.readString();
  }

  @Override
  public byte getCommand() {
    return ChannelBinaryProtocol.REQUEST_CONFIG_GET;
  }

  @Override
  public String getDescription() {
    return "Get config";
  }

  public String getKey() {
    return key;
  }

  @Override
  public boolean requireDatabaseSession() {
    return false;
  }

  @Override
  public GetGlobalConfigurationResponse createResponse() {
    return new GetGlobalConfigurationResponse();
  }

  @Override
  public BinaryResponse execute(BinaryRequestExecutor executor) {
    return executor.executeGetGlobalConfiguration(this);
  }
}
