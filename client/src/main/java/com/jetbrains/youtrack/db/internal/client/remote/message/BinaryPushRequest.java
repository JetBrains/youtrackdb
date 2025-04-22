package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.internal.client.remote.RemotePushHandler;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.SocketChannelBinary;
import com.jetbrains.youtrack.db.internal.remote.RemoteDatabaseSessionInternal;
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
