package com.jetbrains.youtrackdb.internal.client.remote.message;

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

public class SetGlobalConfigurationRequest
    implements BinaryRequest<SetGlobalConfigurationResponse> {

  private String key;
  private String value;

  public SetGlobalConfigurationRequest(String config, String iValue) {
    key = config;
    value = iValue;
  }

  public SetGlobalConfigurationRequest() {
  }

  @Override
  public void write(RemoteDatabaseSessionInternal databaseSession, ChannelDataOutput network,
      BinaryProtocolSession session) throws IOException {
    network.writeString(key);
    network.writeString(value);
  }

  @Override
  public void read(DatabaseSessionEmbedded databaseSession, ChannelDataInput channel,
      int protocolVersion)
      throws IOException {
    key = channel.readString();
    value = channel.readString();
  }

  @Override
  public byte getCommand() {
    return ChannelBinaryProtocol.REQUEST_CONFIG_SET;
  }

  @Override
  public String getDescription() {
    return "Set config";
  }

  public String getKey() {
    return key;
  }

  public String getValue() {
    return value;
  }

  @Override
  public SetGlobalConfigurationResponse createResponse() {
    return new SetGlobalConfigurationResponse();
  }

  @Override
  public boolean requireDatabaseSession() {
    return false;
  }

  @Override
  public BinaryResponse execute(BinaryRequestExecutor executor) {
    return executor.executeSetGlobalConfig(this);
  }
}
