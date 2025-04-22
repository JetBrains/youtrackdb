package com.jetbrains.youtrack.db.internal.client.remote;

import com.jetbrains.youtrack.db.internal.client.binary.BinaryRequestExecutor;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import com.jetbrains.youtrack.db.internal.remote.RemoteDatabaseSessionInternal;
import java.io.IOException;

/**
 *
 */
public interface BinaryRequest<T extends BinaryResponse> {

  void write(RemoteDatabaseSessionInternal databaseSession, final ChannelDataOutput network,
      StorageRemoteSession session) throws IOException;

  void read(DatabaseSessionEmbedded databaseSession, ChannelDataInput channel, int protocolVersion)
      throws IOException;

  byte getCommand();

  T createResponse();

  BinaryResponse execute(BinaryRequestExecutor executor);

  String getDescription();

  default boolean requireServerUser() {
    return false;
  }

  default boolean requireDatabaseSession() {
    return true;
  }

  default String requiredServerRole() {
    return "";
  }
}
