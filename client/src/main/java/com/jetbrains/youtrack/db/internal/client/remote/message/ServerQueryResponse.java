package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.remote.query.RemoteResult;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.StorageRemoteSession;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import com.jetbrains.youtrack.db.internal.remote.RemoteDatabaseSessionInternal;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ServerQueryResponse implements BinaryResponse {

  private String queryId;
  private List<RemoteResult> remoteResult;
  private List<Result> embeddedResult;

  private boolean hasNextPage;

  public ServerQueryResponse(
      String queryId,
      List<Result> result,
      boolean hasNextPage) {
    this.queryId = queryId;
    this.embeddedResult = result;
    this.hasNextPage = hasNextPage;
  }

  public ServerQueryResponse() {
  }

  @Override
  public void write(DatabaseSessionEmbedded session, ChannelDataOutput channel,
      int protocolVersion)
      throws IOException {
    channel.writeString(queryId);
    // THIS IS A PREFETCHED COLLECTION NOT YET HERE
    channel.writeInt(0);
    channel.writeInt(embeddedResult.size());
    for (var res : embeddedResult) {
      MessageHelper.writeResult(session, res, channel);
    }
    channel.writeBoolean(hasNextPage);
  }

  @Override
  public void read(RemoteDatabaseSessionInternal db, ChannelDataInput network,
      StorageRemoteSession session) throws IOException {
    queryId = network.readString();
    // THIS IS A PREFETCHED COLLECTION NOT YET HERE
    var prefetched = network.readInt();
    var size = network.readInt();
    this.remoteResult = new ArrayList<>(size);
    while (size-- > 0) {
      remoteResult.add(MessageHelper.readResult(db, network));
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
