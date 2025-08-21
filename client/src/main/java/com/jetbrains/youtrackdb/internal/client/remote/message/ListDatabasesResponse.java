package com.jetbrains.youtrackdb.internal.client.remote.message;

import com.jetbrains.youtrackdb.internal.client.remote.BinaryProtocolSession;
import com.jetbrains.youtrackdb.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrackdb.internal.enterprise.channel.binary.ChannelDataOutput;
import com.jetbrains.youtrackdb.internal.remote.RemoteDatabaseSessionInternal;
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
    MessageHelper.writeResult(result, channel,
        session != null ? session.getDatabaseTimeZone() : TimeZone.getDefault());
  }

  @Override
  public void read(RemoteDatabaseSessionInternal databaseSession, ChannelDataInput network,
      BinaryProtocolSession session) throws IOException {
    final var result = MessageHelper.readResult((RemoteDatabaseSessionInternal) null, network,
        TimeZone.getDefault());
    databases = result.getProperty("databases");
  }

  public Map<String, String> getDatabases() {
    return databases;
  }
}
