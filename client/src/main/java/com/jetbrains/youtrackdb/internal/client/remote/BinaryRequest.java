package com.jetbrains.youtrackdb.internal.client.remote;

import com.jetbrains.youtrackdb.internal.client.binary.BinaryRequestExecutor;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrackdb.internal.enterprise.channel.binary.ChannelDataOutput;
import com.jetbrains.youtrackdb.internal.remote.RemoteDatabaseSessionInternal;
import java.io.IOException;

/**
 *
 */
public interface BinaryRequest<T extends BinaryResponse> {

  void write(RemoteDatabaseSessionInternal databaseSession, final ChannelDataOutput network,
      BinaryProtocolSession session) throws IOException;

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
