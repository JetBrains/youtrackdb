package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.remote.query.RemoteResult;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryProptocolSession;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import com.jetbrains.youtrack.db.internal.remote.RemoteDatabaseSessionInternal;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class QueryResponse implements BinaryResponse {

  public static final byte RECORD_TYPE_RID = 0;
  public static final byte RECORD_TYPE_PROJECTION = 1;
  public static final byte RECORD_TYPE_BLOB = 2;

  private String queryId;
  private List<RemoteResult> remoteResult;
  private List<Result> embeddedResult;
  private boolean hasNextPage;

  public QueryResponse(
      String queryId,
      List<Result> result,
      boolean hasNextPage) {
    this.queryId = queryId;
    this.embeddedResult = result;
    this.hasNextPage = hasNextPage;
  }

  public QueryResponse() {
  }

  @Override
  public void write(DatabaseSessionEmbedded session, ChannelDataOutput channel,
      int protocolVersion)
      throws IOException {
    channel.writeString(queryId);
    channel.writeInt(embeddedResult.size());
    for (var res : embeddedResult) {
      MessageHelper.writeResult(res, channel, session.getDatabaseTimeZone());
    }
    channel.writeBoolean(hasNextPage);
  }

  @Override
  public void read(RemoteDatabaseSessionInternal databaseSession, ChannelDataInput network,
      BinaryProptocolSession session) throws IOException {
    queryId = network.readString();
    var size = network.readInt();
    this.remoteResult = new ArrayList<>(size);
    while (size-- > 0) {
      remoteResult.add(MessageHelper.readResult(databaseSession, network,
          databaseSession.getDatabaseTimeZone()));
    }
    this.hasNextPage = network.readBoolean();
  }

  public String getQueryId() {
    return queryId;
  }

  public List<RemoteResult> getResult() {
    return remoteResult;
  }

  public boolean isHasNextPage() {
    return hasNextPage;
  }
}
