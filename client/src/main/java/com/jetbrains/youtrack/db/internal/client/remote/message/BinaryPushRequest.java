package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.internal.client.remote.RemotePushHandler;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import java.io.IOException;

/**
 *
 */
public interface BinaryPushRequest<T extends BinaryPushResponse> {
  void write(DatabaseSessionInternal session, ChannelDataOutput channel) throws IOException;

  void read(DatabaseSessionInternal session, final ChannelDataInput network) throws IOException;

  T execute(DatabaseSessionInternal session, RemotePushHandler remote);

  BinaryPushResponse createResponse();

  byte getPushCommand();
}
