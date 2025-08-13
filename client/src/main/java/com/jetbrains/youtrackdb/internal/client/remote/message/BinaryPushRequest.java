package com.jetbrains.youtrackdb.internal.client.remote.message;

import com.jetbrains.youtrackdb.internal.client.remote.RemotePushHandler;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrackdb.internal.enterprise.channel.binary.ChannelDataOutput;
import com.jetbrains.youtrackdb.internal.enterprise.channel.binary.SocketChannelBinary;
import com.jetbrains.youtrackdb.internal.remote.RemoteDatabaseSessionInternal;
import java.io.IOException;

public interface BinaryPushRequest<T extends BinaryPushResponse> {
  void write(DatabaseSessionEmbedded session, ChannelDataOutput channel) throws IOException;

  void readMonitorIdAndStatus(ChannelDataInput network) throws IOException;

  void read(RemoteDatabaseSessionInternal session, final ChannelDataInput network)
      throws IOException;

  T execute(RemotePushHandler remote, SocketChannelBinary network);
  BinaryPushResponse createResponse();
  byte getPushCommand();
}
