package com.jetbrains.youtrackdb.internal.client.remote;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrackdb.internal.enterprise.channel.binary.ChannelDataOutput;
import com.jetbrains.youtrackdb.internal.remote.RemoteDatabaseSessionInternal;
import java.io.IOException;

public interface BinaryResponse {
  void write(DatabaseSessionEmbedded session, ChannelDataOutput channel, int protocolVersion)
      throws IOException;

  void read(RemoteDatabaseSessionInternal db, final ChannelDataInput network,
      BinaryProtocolSession session) throws IOException;
}
