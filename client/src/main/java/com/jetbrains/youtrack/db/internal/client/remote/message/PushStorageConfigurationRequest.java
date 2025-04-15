package com.jetbrains.youtrack.db.internal.client.remote.message;

import static com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol.REQUEST_PUSH_STORAGE_CONFIG;

import com.jetbrains.youtrack.db.internal.client.remote.RemotePushHandler;
import com.jetbrains.youtrack.db.internal.client.remote.message.push.StorageConfigurationPayload;
import com.jetbrains.youtrack.db.internal.core.config.StorageConfiguration;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import java.io.IOException;
import javax.annotation.Nullable;

public class PushStorageConfigurationRequest implements BinaryPushRequest<BinaryPushResponse> {

  private final StorageConfigurationPayload payload;

  public PushStorageConfigurationRequest() {
    payload = new StorageConfigurationPayload();
  }

  public PushStorageConfigurationRequest(StorageConfiguration configuration) {
    payload = new StorageConfigurationPayload(configuration);
  }

  @Override
  public void write(DatabaseSessionInternal session, ChannelDataOutput channel)
      throws IOException {
    payload.write(channel);
  }

  @Override
  public void read(DatabaseSessionInternal session, ChannelDataInput network) throws IOException {
    payload.read(network);
  }

  @Override
  public BinaryPushResponse execute(DatabaseSessionInternal session,
      RemotePushHandler pushHandler) {
    return pushHandler.executeUpdateStorageConfig(this);
  }

  @Nullable
  @Override
  public BinaryPushResponse createResponse() {
    return null;
  }

  @Override
  public byte getPushCommand() {
    return REQUEST_PUSH_STORAGE_CONFIG;
  }

  public StorageConfigurationPayload getPayload() {
    return payload;
  }
}
