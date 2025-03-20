package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.internal.client.binary.BinaryRequestExecutor;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryRequest;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.StorageRemote;
import com.jetbrains.youtrack.db.internal.client.remote.StorageRemoteSession;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetwork;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetworkV37Client;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import java.io.IOException;
import java.util.Map;

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
    this.params = StorageRemote.paramsArrayToParamsMap(params);
    this.namedParams = false;
  }

  public SubscribeLiveQueryRequest() {
  }

  @Override
  public void write(DatabaseSessionInternal databaseSession, ChannelDataOutput network,
      StorageRemoteSession session) throws IOException {
    var serializer = new RecordSerializerNetworkV37Client();
    network.writeString(query);
    // params
    var paramsResult = new ResultInternal(databaseSession);
    paramsResult.setProperty("params", this.params);
    MessageHelper.writeResult(databaseSession, paramsResult, network);
    network.writeBoolean(namedParams);
  }

  @Override
  public void read(DatabaseSessionInternal databaseSession, ChannelDataInput channel,
      int protocolVersion,
      RecordSerializerNetwork serializer)
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
