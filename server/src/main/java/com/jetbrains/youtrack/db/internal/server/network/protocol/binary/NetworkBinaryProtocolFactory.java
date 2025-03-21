/*
 *
 *  *  Copyright YouTrackDB
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
package com.jetbrains.youtrack.db.internal.server.network.protocol.binary;

import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryRequest;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.AddClusterRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.BeginTransaction38Request;
import com.jetbrains.youtrack.db.internal.client.remote.message.CeilingPhysicalPositionsRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.CloseQueryRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.CloseRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.Commit38Request;
import com.jetbrains.youtrack.db.internal.client.remote.message.Connect37Request;
import com.jetbrains.youtrack.db.internal.client.remote.message.ConnectRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.CountRecordsRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.CountRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.CreateDatabaseRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.DropClusterRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.DropDatabaseRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.ExistsDatabaseRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.FetchTransaction38Request;
import com.jetbrains.youtrack.db.internal.client.remote.message.FloorPhysicalPositionsRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.FreezeDatabaseRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.GetGlobalConfigurationRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.GetRecordMetadataRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.GetSizeRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.HigherPhysicalPositionsRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.ImportRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.IncrementalBackupRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.ListDatabasesRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.ListGlobalConfigurationsRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.LowerPhysicalPositionsRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.Open37Request;
import com.jetbrains.youtrack.db.internal.client.remote.message.OpenRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.QueryNextPageRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.QueryRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.ReadRecordRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.RecordExistsRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.ReleaseDatabaseRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.ReloadRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.ReloadRequest37;
import com.jetbrains.youtrack.db.internal.client.remote.message.ReopenRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.RollbackTransactionRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.SBTCreateTreeRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.SBTFetchEntriesMajorRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.SBTFirstKeyRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.SBTGetRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.SendTransactionStateRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.ServerInfoRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.ServerQueryRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.SetGlobalConfigurationRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.ShutdownRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.SubscribeRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.UnsubscribeRequest;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import java.util.function.Function;

/**
 *
 */
public class NetworkBinaryProtocolFactory {

  private static final Function<Integer, BinaryRequest<? extends BinaryResponse>>
      defaultProtocol = NetworkBinaryProtocolFactory::createRequest;

  public static Function<Integer, BinaryRequest<? extends BinaryResponse>> defaultProtocol() {
    return defaultProtocol;
  }

  public static Function<Integer, BinaryRequest<? extends BinaryResponse>> matchProtocol(
      short protocolVersion) {
    return switch (protocolVersion) {
      case 37 -> NetworkBinaryProtocolFactory::createRequest37;
      case 38 -> NetworkBinaryProtocolFactory::createRequest38;
      default -> NetworkBinaryProtocolFactory::createRequest;
    };
  }

  /**
   * Legacy Protocol < 37
   */
  private static BinaryRequest<? extends BinaryResponse> createRequest(int requestType) {
    return switch (requestType) {
      case ChannelBinaryProtocol.REQUEST_DB_OPEN -> new OpenRequest();
      case ChannelBinaryProtocol.REQUEST_CONNECT -> new ConnectRequest();
      case ChannelBinaryProtocol.REQUEST_DB_REOPEN -> new ReopenRequest();
      case ChannelBinaryProtocol.REQUEST_SHUTDOWN -> new ShutdownRequest();
      case ChannelBinaryProtocol.REQUEST_DB_LIST -> new ListDatabasesRequest();
      case ChannelBinaryProtocol.REQUEST_SERVER_INFO -> new ServerInfoRequest();
      case ChannelBinaryProtocol.REQUEST_DB_RELOAD -> new ReloadRequest();
      case ChannelBinaryProtocol.REQUEST_DB_CREATE -> new CreateDatabaseRequest();
      case ChannelBinaryProtocol.REQUEST_DB_CLOSE -> new CloseRequest();
      case ChannelBinaryProtocol.REQUEST_DB_EXIST -> new ExistsDatabaseRequest();
      case ChannelBinaryProtocol.REQUEST_DB_DROP -> new DropDatabaseRequest();
      case ChannelBinaryProtocol.REQUEST_DB_SIZE -> new GetSizeRequest();
      case ChannelBinaryProtocol.REQUEST_DB_COUNTRECORDS -> new CountRecordsRequest();
      case ChannelBinaryProtocol.REQUEST_CLUSTER_COUNT -> new CountRequest();
      case ChannelBinaryProtocol.REQUEST_CLUSTER_ADD -> new AddClusterRequest();
      case ChannelBinaryProtocol.REQUEST_CLUSTER_DROP -> new DropClusterRequest();
      case ChannelBinaryProtocol.REQUEST_RECORD_METADATA -> new GetRecordMetadataRequest();
      case ChannelBinaryProtocol.REQUEST_RECORD_LOAD -> new ReadRecordRequest();
      case ChannelBinaryProtocol.REQUEST_RECORD_EXISTS -> new RecordExistsRequest();
      case ChannelBinaryProtocol.REQUEST_SEND_TRANSACTION_STATE ->
          new SendTransactionStateRequest();
      case ChannelBinaryProtocol.REQUEST_POSITIONS_HIGHER -> new HigherPhysicalPositionsRequest();
      case ChannelBinaryProtocol.REQUEST_POSITIONS_CEILING -> new CeilingPhysicalPositionsRequest();
      case ChannelBinaryProtocol.REQUEST_POSITIONS_LOWER -> new LowerPhysicalPositionsRequest();
      case ChannelBinaryProtocol.REQUEST_POSITIONS_FLOOR -> new FloorPhysicalPositionsRequest();
      case ChannelBinaryProtocol.REQUEST_SERVER_QUERY -> new ServerQueryRequest();
      case ChannelBinaryProtocol.REQUEST_QUERY -> new QueryRequest();
      case ChannelBinaryProtocol.REQUEST_CLOSE_QUERY -> new CloseQueryRequest();
      case ChannelBinaryProtocol.REQUEST_QUERY_NEXT_PAGE -> new QueryNextPageRequest();
      case ChannelBinaryProtocol.REQUEST_CONFIG_GET -> new GetGlobalConfigurationRequest();
      case ChannelBinaryProtocol.REQUEST_CONFIG_SET -> new SetGlobalConfigurationRequest();
      case ChannelBinaryProtocol.REQUEST_CONFIG_LIST -> new ListGlobalConfigurationsRequest();
      case ChannelBinaryProtocol.REQUEST_DB_FREEZE -> new FreezeDatabaseRequest();
      case ChannelBinaryProtocol.REQUEST_DB_RELEASE -> new ReleaseDatabaseRequest();
      case ChannelBinaryProtocol.REQUEST_CREATE_SBTREE_BONSAI -> new SBTCreateTreeRequest();
      case ChannelBinaryProtocol.REQUEST_SBTREE_BONSAI_GET -> new SBTGetRequest();
      case ChannelBinaryProtocol.REQUEST_SBTREE_BONSAI_FIRST_KEY -> new SBTFirstKeyRequest();
      case ChannelBinaryProtocol.REQUEST_SBTREE_BONSAI_GET_ENTRIES_MAJOR ->
          new SBTFetchEntriesMajorRequest<>();
      case ChannelBinaryProtocol.REQUEST_INCREMENTAL_BACKUP -> new IncrementalBackupRequest();
      case ChannelBinaryProtocol.REQUEST_DB_IMPORT -> new ImportRequest();
      default -> throw new DatabaseException("binary protocol command with code: " + requestType);
    };
  }

  /**
   * Protocol 37
   */
  public static BinaryRequest<? extends BinaryResponse> createRequest37(int requestType) {
    return switch (requestType) {
      case ChannelBinaryProtocol.SUBSCRIBE_PUSH -> new SubscribeRequest();
      case ChannelBinaryProtocol.UNSUBSCRIBE_PUSH -> new UnsubscribeRequest();
      case ChannelBinaryProtocol.REQUEST_TX_ROLLBACK -> new RollbackTransactionRequest();
      case ChannelBinaryProtocol.REQUEST_DB_OPEN -> new Open37Request();
      case ChannelBinaryProtocol.REQUEST_CONNECT -> new Connect37Request();
      case ChannelBinaryProtocol.REQUEST_DB_REOPEN -> new ReopenRequest();
      case ChannelBinaryProtocol.REQUEST_SHUTDOWN -> new ShutdownRequest();
      case ChannelBinaryProtocol.REQUEST_DB_LIST -> new ListDatabasesRequest();
      case ChannelBinaryProtocol.REQUEST_SERVER_INFO -> new ServerInfoRequest();
      case ChannelBinaryProtocol.REQUEST_DB_RELOAD -> new ReloadRequest37();
      case ChannelBinaryProtocol.REQUEST_DB_CREATE -> new CreateDatabaseRequest();
      case ChannelBinaryProtocol.REQUEST_DB_CLOSE -> new CloseRequest();
      case ChannelBinaryProtocol.REQUEST_DB_EXIST -> new ExistsDatabaseRequest();
      case ChannelBinaryProtocol.REQUEST_DB_DROP -> new DropDatabaseRequest();
      case ChannelBinaryProtocol.REQUEST_DB_SIZE -> new GetSizeRequest();
      case ChannelBinaryProtocol.REQUEST_DB_COUNTRECORDS -> new CountRecordsRequest();
      case ChannelBinaryProtocol.REQUEST_CLUSTER_COUNT -> new CountRequest();
      case ChannelBinaryProtocol.REQUEST_CLUSTER_ADD -> new AddClusterRequest();
      case ChannelBinaryProtocol.REQUEST_CLUSTER_DROP -> new DropClusterRequest();
      case ChannelBinaryProtocol.REQUEST_RECORD_METADATA -> new GetRecordMetadataRequest();
      case ChannelBinaryProtocol.REQUEST_RECORD_LOAD -> new ReadRecordRequest();
      case ChannelBinaryProtocol.REQUEST_POSITIONS_HIGHER -> new HigherPhysicalPositionsRequest();
      case ChannelBinaryProtocol.REQUEST_POSITIONS_CEILING -> new CeilingPhysicalPositionsRequest();
      case ChannelBinaryProtocol.REQUEST_POSITIONS_LOWER -> new LowerPhysicalPositionsRequest();
      case ChannelBinaryProtocol.REQUEST_POSITIONS_FLOOR -> new FloorPhysicalPositionsRequest();
      case ChannelBinaryProtocol.REQUEST_SERVER_QUERY -> new ServerQueryRequest();
      case ChannelBinaryProtocol.REQUEST_QUERY -> new QueryRequest();
      case ChannelBinaryProtocol.REQUEST_CLOSE_QUERY -> new CloseQueryRequest();
      case ChannelBinaryProtocol.REQUEST_QUERY_NEXT_PAGE -> new QueryNextPageRequest();
      case ChannelBinaryProtocol.REQUEST_CONFIG_GET -> new GetGlobalConfigurationRequest();
      case ChannelBinaryProtocol.REQUEST_CONFIG_SET -> new SetGlobalConfigurationRequest();
      case ChannelBinaryProtocol.REQUEST_CONFIG_LIST -> new ListGlobalConfigurationsRequest();
      case ChannelBinaryProtocol.REQUEST_DB_FREEZE -> new FreezeDatabaseRequest();
      case ChannelBinaryProtocol.REQUEST_DB_RELEASE -> new ReleaseDatabaseRequest();
      case ChannelBinaryProtocol.REQUEST_CREATE_SBTREE_BONSAI -> new SBTCreateTreeRequest();
      case ChannelBinaryProtocol.REQUEST_SBTREE_BONSAI_GET -> new SBTGetRequest();
      case ChannelBinaryProtocol.REQUEST_SBTREE_BONSAI_FIRST_KEY -> new SBTFirstKeyRequest();
      case ChannelBinaryProtocol.REQUEST_SBTREE_BONSAI_GET_ENTRIES_MAJOR ->
          new SBTFetchEntriesMajorRequest<>();
      case ChannelBinaryProtocol.REQUEST_INCREMENTAL_BACKUP -> new IncrementalBackupRequest();
      case ChannelBinaryProtocol.REQUEST_DB_IMPORT -> new ImportRequest();
      default -> throw new DatabaseException(
          "binary protocol command with code: " + requestType + " for protocol version 37");
    };
  }

  /**
   * Protocol 38
   */
  public static BinaryRequest<? extends BinaryResponse> createRequest38(int requestType) {
    return switch (requestType) {
      case ChannelBinaryProtocol.REQUEST_TX_FETCH -> new FetchTransaction38Request();
      case ChannelBinaryProtocol.REQUEST_TX_BEGIN -> new BeginTransaction38Request();
      case ChannelBinaryProtocol.REQUEST_SEND_TRANSACTION_STATE ->
          new SendTransactionStateRequest();
      case ChannelBinaryProtocol.REQUEST_TX_COMMIT -> new Commit38Request();
      default -> createRequest37(requestType);
    };
  }
}
