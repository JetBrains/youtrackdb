package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.StorageRemoteSession;
import com.jetbrains.youtrack.db.internal.client.remote.db.DatabaseSessionRemote;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
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
  public void write(DatabaseSessionEmbedded session, ChannelDataOutput channel,
      int protocolVersion)
      throws IOException {
    final var result = new ResultInternal(null);
    result.setProperty("databases", databases);
    MessageHelper.writeResult(session, result, channel);
  }

  @Override
  public void read(DatabaseSessionRemote databaseSession, ChannelDataInput network,
      StorageRemoteSession session) throws IOException {
    final var result = MessageHelper.readResult(databaseSession, network);
    databases = result.getProperty("databases");
  }

  public Map<String, String> getDatabases() {
    return databases;
  }
}
