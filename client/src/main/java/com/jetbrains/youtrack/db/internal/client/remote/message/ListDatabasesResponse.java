package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.internal.client.remote.BinaryProptocolSession;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import com.jetbrains.youtrack.db.internal.remote.RemoteDatabaseSessionInternal;
import java.io.IOException;
import java.util.Map;
import java.util.TimeZone;

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
    MessageHelper.writeResult(result, channel, session.getDatabaseTimeZone());
  }

  @Override
  public void read(RemoteDatabaseSessionInternal databaseSession, ChannelDataInput network,
      BinaryProptocolSession session) throws IOException {
    final var result = MessageHelper.readResult((RemoteDatabaseSessionInternal) null, network,
        TimeZone.getDefault());
    databases = result.getProperty("databases");
  }

  public Map<String, String> getDatabases() {
    return databases;
  }
}
