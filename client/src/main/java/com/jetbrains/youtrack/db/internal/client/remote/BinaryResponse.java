package com.jetbrains.youtrack.db.internal.client.remote;

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import com.jetbrains.youtrack.db.internal.remote.RemoteDatabaseSessionInternal;
import java.io.IOException;

public interface BinaryResponse {
  void write(DatabaseSessionEmbedded session, ChannelDataOutput channel, int protocolVersion)
      throws IOException;

  void read(RemoteDatabaseSessionInternal db, final ChannelDataInput network,
      StorageRemoteSession session) throws IOException;
}
