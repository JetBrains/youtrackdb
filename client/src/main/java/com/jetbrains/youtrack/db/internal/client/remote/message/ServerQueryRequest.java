/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.internal.client.binary.BinaryRequestExecutor;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryRequest;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.RemoteCommandsOrchestratorImpl;
import com.jetbrains.youtrack.db.internal.client.remote.StorageRemoteSession;
import com.jetbrains.youtrack.db.internal.client.remote.db.DatabaseSessionRemote;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializer;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.result.binary.RemoteResultImpl;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import java.io.IOException;
import java.util.Map;
import javax.annotation.Nullable;

public final class ServerQueryRequest implements BinaryRequest<ServerQueryResponse> {

  public static byte COMMAND = 0;
  public static byte QUERY = 1;
  public static byte EXECUTE = 2;

  private int recordsPerPage = Integer.MAX_VALUE;
  private RecordSerializer serializer;
  private String language;
  private String statement;
  private byte operationType;
  private Map<String, Object> params;
  private boolean namedParams;

  public ServerQueryRequest(
      String language,
      String iCommand,
      Object[] positionalParams,
      byte operationType,
      int recordsPerPage) {
    this.language = language;
    this.statement = iCommand;
    params = RemoteCommandsOrchestratorImpl.paramsArrayToParamsMap(positionalParams);
    namedParams = false;
    this.operationType = operationType;
    if (this.recordsPerPage <= 0) {
      this.recordsPerPage = 100;
    }
    this.recordsPerPage = recordsPerPage;
  }

  public ServerQueryRequest(String language,
      String iCommand,
      Map<String, Object> namedParams,
      byte operationType,
      int recordsPerPage) {
    this.language = language;
    this.statement = iCommand;
    this.params = namedParams != null ? namedParams : Map.of();
    this.namedParams = true;
    this.recordsPerPage = recordsPerPage;
    if (this.recordsPerPage <= 0) {
      this.recordsPerPage = 100;
    }

    this.operationType = operationType;
  }

  public ServerQueryRequest() {
  }

  @Override
  public void write(DatabaseSessionRemote databaseSession, ChannelDataOutput network,
      StorageRemoteSession session) throws IOException {
    network.writeString(language);
    network.writeString(statement);
    network.writeByte(operationType);
    network.writeInt(recordsPerPage);
    // THIS IS FOR POSSIBLE FUTURE FETCH PLAN
    network.writeString(null);

    // params
    var paramsResult = new RemoteResultImpl(databaseSession);
    paramsResult.setProperty("params", params);

    MessageHelper.writeProjection(null, paramsResult, network);

    network.writeBoolean(namedParams);
  }

  public void read(DatabaseSessionEmbedded databaseSession, ChannelDataInput channel,
      int protocolVersion)
      throws IOException {
    this.language = channel.readString();
    this.statement = channel.readString();
    this.operationType = channel.readByte();
    this.recordsPerPage = channel.readInt();
    // THIS IS FOR POSSIBLE FUTURE FETCH PLAN
    channel.readString();

    var paramsResult = MessageHelper.readResult(databaseSession, channel);
    this.params = paramsResult.getProperty("params");

    this.namedParams = channel.readBoolean();
    this.serializer = serializer;
  }

  @Override
  public byte getCommand() {
    return ChannelBinaryProtocol.REQUEST_SERVER_QUERY;
  }

  @Override
  public String getDescription() {
    return "Execute remote query";
  }

  @Override
  public ServerQueryResponse createResponse() {
    return new ServerQueryResponse();
  }

  @Override
  public BinaryResponse execute(BinaryRequestExecutor executor) {
    return executor.executeServerQuery(this);
  }

  public String getStatement() {
    return statement;
  }

  public Map<String, Object> getParams() {
    return params;
  }

  public byte getOperationType() {
    return operationType;
  }

  public boolean isNamedParams() {
    return namedParams;
  }

  public Map<String, Object> getNamedParameters() {
    return params;
  }

  @Nullable
  public Object[] getPositionalParameters() {
    var params = this.params;
    if (params == null) {
      return null;
    }
    var result = new Object[params.size()];
    params
        .entrySet()
        .forEach(
            e -> {
              result[Integer.parseInt(e.getKey())] = e.getValue();
            });
    return result;
  }

  public int getRecordsPerPage() {
    return recordsPerPage;
  }

  public RecordSerializer getSerializer() {
    return serializer;
  }

  public String getLanguage() {
    return language;
  }

  public void setLanguage(String language) {
    this.language = language;
  }

  public boolean requireDatabaseSession() {
    return false;
  }
}
