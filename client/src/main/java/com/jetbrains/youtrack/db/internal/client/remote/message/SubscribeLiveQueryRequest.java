package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.internal.client.binary.BinaryRequestExecutor;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryRequest;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.RemoteCommandsOrchestratorImpl;
import com.jetbrains.youtrack.db.internal.client.remote.StorageRemoteSession;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.result.binary.RemoteResultImpl;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import com.jetbrains.youtrack.db.internal.remote.RemoteDatabaseSessionInternal;
import java.io.IOException;
import java.util.Map;
import javax.annotation.Nullable;

/**
 *
 */
public class SubscribeLiveQueryRequest implements BinaryRequest<SubscribeLiveQueryResponse> {

  private String query;
  private Map<String, Object> params;
  private boolean namedParams;

  public SubscribeLiveQueryRequest(String query, Map<String, Object> params) {
    this.query = query;
    this.params = params;
    this.namedParams = true;
  }

  public SubscribeLiveQueryRequest(String query, Object[] params) {
    this.query = query;
    this.params = RemoteCommandsOrchestratorImpl.paramsArrayToParamsMap(params);
    this.namedParams = false;
  }

  public SubscribeLiveQueryRequest() {
  }

  @Override
  public void write(RemoteDatabaseSessionInternal databaseSession, ChannelDataOutput network,
      StorageRemoteSession session) throws IOException {
    network.writeString(query);
    // params
    var paramsResult = new RemoteResultImpl(databaseSession);
    paramsResult.setProperty("params", this.params);

    MessageHelper.writeResult(databaseSession, paramsResult, network);

    network.writeBoolean(namedParams);
  }

  @Override
  public void read(DatabaseSessionEmbedded databaseSession, ChannelDataInput channel,
      int protocolVersion)
      throws IOException {
    this.query = channel.readString();

    var paramsResult = MessageHelper.readResult(databaseSession, channel);
    this.params = paramsResult.getProperty("params");

    this.namedParams = channel.readBoolean();
  }

  @Override
  public byte getCommand() {
    return ChannelBinaryProtocol.SUBSCRIBE_PUSH_LIVE_QUERY;
  }

  @Override
  public SubscribeLiveQueryResponse createResponse() {
    return new SubscribeLiveQueryResponse();
  }

  @Override
  public BinaryResponse execute(BinaryRequestExecutor executor) {
    return executor.executeSubscribeLiveQuery(this);
  }

  @Nullable
  @Override
  public String getDescription() {
    return null;
  }

  public String getQuery() {
    return query;
  }

  public Map<String, Object> getParams() {
    return params;
  }
}
