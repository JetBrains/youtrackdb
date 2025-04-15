package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.StorageRemoteSession;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializer;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import java.io.IOException;
import java.util.Map;

public class ListDatabasesResponse implements BinaryResponse {

  private Map<String, String> databases;

  public ListDatabasesResponse(Map<String, String> databases) {
    this.databases = databases;
  }

  public ListDatabasesResponse() {
  }

  @Override
  public void write(DatabaseSessionInternal session, ChannelDataOutput channel,
      int protocolVersion, RecordSerializer serializer)
      throws IOException {
    final var result = new ResultInternal(null);
    result.setProperty("databases", databases);
    MessageHelper.writeResult(session, result, channel);
  }

  @Override
  public void read(DatabaseSessionInternal databaseSession, ChannelDataInput network,
      StorageRemoteSession session) throws IOException {
    final var result = MessageHelper.readResult(databaseSession, network);
    databases = result.getProperty("databases");
  }

  public Map<String, String> getDatabases() {
    return databases;
  }
}
