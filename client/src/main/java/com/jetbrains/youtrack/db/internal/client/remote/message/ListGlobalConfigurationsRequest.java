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

public class ListGlobalConfigurationsRequest
    implements BinaryRequest<ListGlobalConfigurationsResponse> {

  @Override
  public void write(RemoteDatabaseSessionInternal databaseSession, ChannelDataOutput network,
      StorageRemoteSession session) throws IOException {
  }

  @Override
  public void read(DatabaseSessionEmbedded databaseSession, ChannelDataInput channel,
      int protocolVersion)
      throws IOException {
  }

  @Override
  public byte getCommand() {
    return ChannelBinaryProtocol.REQUEST_CONFIG_LIST;
  }

  @Override
  public String requiredServerRole() {
    return "server.config.get";
  }

  @Override
  public boolean requireServerUser() {
    return true;
  }

  @Override
  public String getDescription() {
    return "List Config";
  }

  @Override
  public boolean requireDatabaseSession() {
    return false;
  }

  @Override
  public ListGlobalConfigurationsResponse createResponse() {
    return new ListGlobalConfigurationsResponse();
  }

  @Override
  public BinaryResponse execute(BinaryRequestExecutor executor) {
    return executor.executeListGlobalConfigurations(this);
  }
}
