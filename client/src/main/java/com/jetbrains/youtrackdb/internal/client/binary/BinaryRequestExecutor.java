package com.jetbrains.youtrackdb.internal.client.binary;

import com.jetbrains.youtrackdb.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrackdb.internal.client.remote.message.CloseQueryRequest;
import com.jetbrains.youtrackdb.internal.client.remote.message.CloseRequest;
import com.jetbrains.youtrackdb.internal.client.remote.message.Connect37Request;
import com.jetbrains.youtrackdb.internal.client.remote.message.CreateDatabaseRequest;
import com.jetbrains.youtrackdb.internal.client.remote.message.DropDatabaseRequest;
import com.jetbrains.youtrackdb.internal.client.remote.message.ExistsDatabaseRequest;
import com.jetbrains.youtrackdb.internal.client.remote.message.FreezeDatabaseRequest;
import com.jetbrains.youtrackdb.internal.client.remote.message.GetGlobalConfigurationRequest;
import com.jetbrains.youtrackdb.internal.client.remote.message.ImportRequest;
import com.jetbrains.youtrackdb.internal.client.remote.message.IncrementalBackupRequest;
import com.jetbrains.youtrackdb.internal.client.remote.message.ListDatabasesRequest;
import com.jetbrains.youtrackdb.internal.client.remote.message.ListGlobalConfigurationsRequest;
import com.jetbrains.youtrackdb.internal.client.remote.message.Open37Request;
import com.jetbrains.youtrackdb.internal.client.remote.message.QueryNextPageRequest;
import com.jetbrains.youtrackdb.internal.client.remote.message.QueryRequest;
import com.jetbrains.youtrackdb.internal.client.remote.message.ReleaseDatabaseRequest;
import com.jetbrains.youtrackdb.internal.client.remote.message.ReopenRequest;
import com.jetbrains.youtrackdb.internal.client.remote.message.RollbackActiveTxRequest;
import com.jetbrains.youtrackdb.internal.client.remote.message.ServerInfoRequest;
import com.jetbrains.youtrackdb.internal.client.remote.message.ServerQueryRequest;
import com.jetbrains.youtrackdb.internal.client.remote.message.SetGlobalConfigurationRequest;
import com.jetbrains.youtrackdb.internal.client.remote.message.ShutdownRequest;
import com.jetbrains.youtrackdb.internal.client.remote.message.SubscribeLiveQueryRequest;
import com.jetbrains.youtrackdb.internal.client.remote.message.SubscribeRequest;
import com.jetbrains.youtrackdb.internal.client.remote.message.UnsubscribeLiveQueryRequest;
import com.jetbrains.youtrackdb.internal.client.remote.message.UnsubscribeRequest;

public interface BinaryRequestExecutor {

  BinaryResponse executeServerInfo(ServerInfoRequest request);

  BinaryResponse executeCreateDatabase(CreateDatabaseRequest request);

  BinaryResponse executeClose(CloseRequest request);

  BinaryResponse executeExistDatabase(ExistsDatabaseRequest request);

  BinaryResponse executeDropDatabase(DropDatabaseRequest request);

  BinaryResponse executeGetGlobalConfiguration(GetGlobalConfigurationRequest request);

  BinaryResponse executeListGlobalConfigurations(ListGlobalConfigurationsRequest request);

  BinaryResponse executeFreezeDatabase(FreezeDatabaseRequest request);

  BinaryResponse executeReleaseDatabase(ReleaseDatabaseRequest request);

  BinaryResponse executeIncrementalBackup(IncrementalBackupRequest request);

  BinaryResponse executeImport(ImportRequest request);

  BinaryResponse executeSetGlobalConfig(SetGlobalConfigurationRequest request);

  BinaryResponse executeConnect37(Connect37Request request);

  BinaryResponse executeDatabaseOpen37(Open37Request request);

  BinaryResponse executeShutdown(ShutdownRequest request);

  BinaryResponse executeReopen(ReopenRequest request);

  BinaryResponse executeQuery(QueryRequest request);

  BinaryResponse executeServerQuery(ServerQueryRequest request);

  BinaryResponse closeQuery(CloseQueryRequest request);

  BinaryResponse executeQueryNextPage(QueryNextPageRequest request);

  BinaryResponse executeSubscribe(SubscribeRequest request);

  BinaryResponse executeSubscribeLiveQuery(SubscribeLiveQueryRequest request);

  BinaryResponse executeUnsubscribe(UnsubscribeRequest request);

  BinaryResponse executeUnsubscribeLiveQuery(UnsubscribeLiveQueryRequest request);

  BinaryResponse executeListDatabases(ListDatabasesRequest request);

  BinaryResponse executeRollbackActiveTx(RollbackActiveTxRequest request);
}
