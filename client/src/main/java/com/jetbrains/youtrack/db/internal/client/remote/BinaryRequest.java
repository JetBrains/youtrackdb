package com.jetbrains.youtrack.db.internal.client.remote;

import com.jetbrains.youtrack.db.internal.client.binary.BinaryRequestExecutor;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetwork;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import java.io.IOException;

/**
 *
 */
public interface BinaryRequest<T extends BinaryResponse> {

  void write(DatabaseSessionInternal databaseSession, final ChannelDataOutput network,
      StorageRemoteSession session) throws IOException;

  void read(DatabaseSessionInternal databaseSession, ChannelDataInput channel, int protocolVersion,
      RecordSerializerNetwork serializer)
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
