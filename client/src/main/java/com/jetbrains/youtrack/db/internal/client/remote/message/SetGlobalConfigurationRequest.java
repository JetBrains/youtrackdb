package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.internal.client.binary.BinaryRequestExecutor;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryRequest;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.StorageRemoteSession;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetwork;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
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
  public void write(DatabaseSessionInternal databaseSession, ChannelDataOutput network,
      StorageRemoteSession session) throws IOException {
    network.writeString(key);
    network.writeString(value);
  }

  @Override
  public void read(DatabaseSessionInternal databaseSession, ChannelDataInput channel,
      int protocolVersion,
      RecordSerializerNetwork serializer)
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
