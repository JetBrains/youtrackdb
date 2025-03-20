package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.query.ExecutionPlan;
import com.jetbrains.youtrack.db.api.query.ExecutionStep;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.StorageRemoteSession;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializer;
import com.jetbrains.youtrack.db.internal.core.sql.executor.InfoExecutionPlan;
import com.jetbrains.youtrack.db.internal.core.sql.executor.InfoExecutionStep;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 *
 */
public class ServerQueryResponse implements BinaryResponse {

  public static final byte RECORD_TYPE_BLOB = 0;
  public static final byte RECORD_TYPE_VERTEX = 1;
  public static final byte RECORD_TYPE_EDGE = 2;
  public static final byte RECORD_TYPE_ELEMENT = 3;
  public static final byte RECORD_TYPE_PROJECTION = 4;

  private String queryId;
  private boolean txChanges;
  private List<Result> result;
  private ExecutionPlan executionPlan;
  private boolean hasNextPage;
  private Map<String, Long> queryStats;
  private boolean reloadMetadata;

  public ServerQueryResponse(
      String queryId,
      boolean txChanges,
      List<Result> result,
      ExecutionPlan executionPlan,
      boolean hasNextPage,
      Map<String, Long> queryStats,
      boolean reloadMetadata) {
    this.queryId = queryId;
    this.txChanges = txChanges;
    this.result = result;
    this.executionPlan = executionPlan;
    this.hasNextPage = hasNextPage;
    this.queryStats = queryStats;
    this.reloadMetadata = reloadMetadata;
  }

  public ServerQueryResponse() {
  }

  @Override
  public void write(DatabaseSessionInternal session, ChannelDataOutput channel,
      int protocolVersion, RecordSerializer serializer)
      throws IOException {
    channel.writeString(queryId);
    channel.writeBoolean(txChanges);
    writeExecutionPlan(session, executionPlan, channel);
    // THIS IS A PREFETCHED COLLECTION NOT YET HERE
    channel.writeInt(0);
    channel.writeInt(result.size());
    for (var res : result) {
      MessageHelper.writeResult(session, res, channel);
    }
    channel.writeBoolean(hasNextPage);
    writeQueryStats(queryStats, channel);
    channel.writeBoolean(reloadMetadata);
  }

  @Override
  public void read(DatabaseSessionInternal db, ChannelDataInput network,
      StorageRemoteSession session) throws IOException {
    queryId = network.readString();
    txChanges = network.readBoolean();
    executionPlan = readExecutionPlan(db, network);
    // THIS IS A PREFETCHED COLLECTION NOT YET HERE
    var prefetched = network.readInt();
    var size = network.readInt();
    this.result = new ArrayList<>(size);
    while (size-- > 0) {
      result.add(MessageHelper.readResult(db, network));
    }
    this.hasNextPage = network.readBoolean();
    this.queryStats = readQueryStats(network);
    reloadMetadata = network.readBoolean();
  }

  private void writeQueryStats(Map<String, Long> queryStats, ChannelDataOutput channel)
      throws IOException {
    if (queryStats == null) {
      channel.writeInt(0);
      return;
    }
    channel.writeInt(queryStats.size());
    for (var entry : queryStats.entrySet()) {
      channel.writeString(entry.getKey());
      channel.writeLong(entry.getValue());
    }
  }

  private Map<String, Long> readQueryStats(ChannelDataInput channel) throws IOException {
    Map<String, Long> result = new HashMap<>();
    var size = channel.readInt();
    for (var i = 0; i < size; i++) {
      var key = channel.readString();
      Long val = channel.readLong();
      result.put(key, val);
    }
    return result;
  }

  private void writeExecutionPlan(
      DatabaseSessionInternal session, ExecutionPlan executionPlan,
      ChannelDataOutput channel)
      throws IOException {
    if (executionPlan != null
        && GlobalConfiguration.QUERY_REMOTE_SEND_EXECUTION_PLAN.getValueAsBoolean()) {
      channel.writeBoolean(true);
      MessageHelper.writeResult(session, executionPlan.toResult(session), channel
      );
    } else {
      channel.writeBoolean(false);
    }
  }

  private ExecutionPlan readExecutionPlan(DatabaseSessionInternal db,
      ChannelDataInput network) throws IOException {
    var present = network.readBoolean();
    if (!present) {
      return null;
    }
    var result = new InfoExecutionPlan();
    Result read = MessageHelper.readResult(db, network);
    result.setCost(((Number) read.getProperty("cost")).intValue());
    result.setType(read.getProperty("type"));
    result.setJavaType(read.getProperty("javaType"));
    result.setPrettyPrint(read.getProperty("prettyPrint"));
    result.setStmText(read.getProperty("stmText"));
    List<Result> subSteps = read.getProperty("steps");
    if (subSteps != null) {
      subSteps.forEach(x -> result.getSteps().add(toInfoStep(x)));
    }
    return result;
  }

  public String getQueryId() {
    return queryId;
  }

  public List<Result> getResult() {
    return result;
  }

  public ExecutionPlan getExecutionPlan() {
    return executionPlan;
  }

  public boolean isHasNextPage() {
    return hasNextPage;
  }

  public Map<String, Long> getQueryStats() {
    return queryStats;
  }

  private ExecutionStep toInfoStep(Result x) {
    var result = new InfoExecutionStep();
    result.setName(x.getProperty("name"));
    result.setType(x.getProperty("type"));
    result.setJavaType(x.getProperty("javaType"));
    result.setCost(x.getProperty("cost") == null ? -1 : x.getProperty("cost"));
    List<Result> ssteps = x.getProperty("subSteps");
    if (ssteps != null) {
      ssteps.stream().forEach(sstep -> result.getSubSteps().add(toInfoStep(sstep)));
    }
    result.setDescription(x.getProperty("description"));
    return result;
  }

  public boolean isTxChanges() {
    return txChanges;
  }

  public boolean isReloadMetadata() {
    return reloadMetadata;
  }
}
