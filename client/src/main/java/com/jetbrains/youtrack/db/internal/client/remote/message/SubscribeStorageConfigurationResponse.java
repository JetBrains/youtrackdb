package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.internal.client.remote.BinaryProtocolSession;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import com.jetbrains.youtrack.db.internal.remote.RemoteDatabaseSessionInternal;
import java.io.IOException;

/**
 *
 */
public class SubscribeStorageConfigurationResponse implements BinaryResponse {

  @Override
  public void write(DatabaseSessionEmbedded session, ChannelDataOutput channel,
      int protocolVersion)
      throws IOException {
  }

  @Override
  public void read(RemoteDatabaseSessionInternal db, ChannelDataInput network,
      BinaryProtocolSession session) throws IOException {
  }
}
