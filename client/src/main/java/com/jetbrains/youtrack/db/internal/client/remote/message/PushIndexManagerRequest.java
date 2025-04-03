package com.jetbrains.youtrack.db.internal.client.remote.message;

import static com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol.REQUEST_PUSH_INDEX_MANAGER;

import com.jetbrains.youtrack.db.internal.client.remote.RemotePushHandler;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import java.io.IOException;
import javax.annotation.Nullable;

public class PushIndexManagerRequest implements BinaryPushRequest<BinaryPushResponse> {

  public PushIndexManagerRequest() {
  }


  @Override
  public void write(DatabaseSessionInternal session, ChannelDataOutput channel)
      throws IOException {
  }

  @Override
  public void read(DatabaseSessionInternal db, ChannelDataInput network) throws IOException {
  }

  @Override
  public BinaryPushResponse execute(DatabaseSessionInternal session,
      RemotePushHandler pushHandler) {
    return pushHandler.executeUpdateIndexManager(this);
  }

  @Nullable
  @Override
  public BinaryPushResponse createResponse() {
    return null;
  }

  @Override
  public byte getPushCommand() {
    return REQUEST_PUSH_INDEX_MANAGER;
  }
}
