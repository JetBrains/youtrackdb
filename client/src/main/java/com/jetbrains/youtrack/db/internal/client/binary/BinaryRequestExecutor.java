package com.jetbrains.youtrack.db.internal.client.binary;

import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.AddClusterRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.BeginTransaction38Request;
import com.jetbrains.youtrack.db.internal.client.remote.message.CeilingPhysicalPositionsRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.CloseQueryRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.CloseRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.CommandRequest;
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
import com.jetbrains.youtrack.db.internal.client.remote.message.SubscribeFunctionsRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.SubscribeIndexManagerRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.SubscribeLiveQueryRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.SubscribeRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.SubscribeSchemaRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.SubscribeSequencesRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.SubscribeStorageConfigurationRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.UnsubscribeLiveQueryRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.UnsubscribeRequest;

public interface BinaryRequestExecutor {

  BinaryResponse executeListDatabases(ListDatabasesRequest request);

  BinaryResponse executeServerInfo(ServerInfoRequest request);

  BinaryResponse executeDBReload(ReloadRequest request);

  BinaryResponse executeDBReload(ReloadRequest37 request);

  BinaryResponse executeCreateDatabase(CreateDatabaseRequest request);

  BinaryResponse executeClose(CloseRequest request);

  BinaryResponse executeExistDatabase(ExistsDatabaseRequest request);

  BinaryResponse executeDropDatabase(DropDatabaseRequest request);

  BinaryResponse executeGetSize(GetSizeRequest request);

  BinaryResponse executeCountRecords(CountRecordsRequest request);

  BinaryResponse executeCountCluster(CountRequest request);

  BinaryResponse executeAddCluster(AddClusterRequest request);

  BinaryResponse executeDropCluster(DropClusterRequest request);

  BinaryResponse executeGetRecordMetadata(GetRecordMetadataRequest request);

  BinaryResponse executeReadRecord(ReadRecordRequest request);

  BinaryResponse executeRecordExists(RecordExistsRequest request);


  BinaryResponse executeHigherPosition(HigherPhysicalPositionsRequest request);

  BinaryResponse executeCeilingPosition(CeilingPhysicalPositionsRequest request);

  BinaryResponse executeLowerPosition(LowerPhysicalPositionsRequest request);

  BinaryResponse executeFloorPosition(FloorPhysicalPositionsRequest request);

  BinaryResponse executeCommand(CommandRequest request);

  BinaryResponse executeGetGlobalConfiguration(GetGlobalConfigurationRequest request);

  BinaryResponse executeListGlobalConfigurations(ListGlobalConfigurationsRequest request);

  BinaryResponse executeFreezeDatabase(FreezeDatabaseRequest request);

  BinaryResponse executeReleaseDatabase(ReleaseDatabaseRequest request);

  BinaryResponse executeSBTreeCreate(SBTCreateTreeRequest request);

  BinaryResponse executeSBTGet(SBTGetRequest request);

  BinaryResponse executeSBTFirstKey(SBTFirstKeyRequest request);

  BinaryResponse executeSBTFetchEntriesMajor(
      @SuppressWarnings("rawtypes") SBTFetchEntriesMajorRequest request);

  BinaryResponse executeIncrementalBackup(IncrementalBackupRequest request);

  BinaryResponse executeImport(ImportRequest request);

  BinaryResponse executeSetGlobalConfig(SetGlobalConfigurationRequest request);

  BinaryResponse executeConnect(ConnectRequest request);

  BinaryResponse executeConnect37(Connect37Request request);

  BinaryResponse executeDatabaseOpen(OpenRequest request);

  BinaryResponse executeDatabaseOpen37(Open37Request request);

  BinaryResponse executeShutdown(ShutdownRequest request);

  BinaryResponse executeReopen(ReopenRequest request);

  BinaryResponse executeQuery(QueryRequest request);

  BinaryResponse executeServerQuery(ServerQueryRequest request);

  BinaryResponse closeQuery(CloseQueryRequest request);

  BinaryResponse executeQueryNextPage(QueryNextPageRequest request);


  BinaryResponse executeSendTransactionState(SendTransactionStateRequest request);

  BinaryResponse executeRollback(RollbackTransactionRequest request);

  BinaryResponse executeSubscribe(SubscribeRequest request);


  BinaryResponse executeSubscribeLiveQuery(SubscribeLiveQueryRequest request);

  BinaryResponse executeUnsubscribe(UnsubscribeRequest request);

  BinaryResponse executeUnsubscribeLiveQuery(UnsubscribeLiveQueryRequest request);

  BinaryResponse executeSubscribeStorageConfiguration(
      SubscribeStorageConfigurationRequest request);

  BinaryResponse executeSubscribeSchema(SubscribeSchemaRequest request);

  BinaryResponse executeSubscribeIndexManager(SubscribeIndexManagerRequest request);

  BinaryResponse executeSubscribeFunctions(SubscribeFunctionsRequest request);

  BinaryResponse executeSubscribeSequences(SubscribeSequencesRequest request);

  BinaryResponse executeBeginTransaction(BeginTransaction38Request request);

  BinaryResponse executeCommit38(Commit38Request request);

  BinaryResponse executeFetchTransaction38(FetchTransaction38Request request);
}
