package com.jetbrains.youtrack.db.internal.client.remote;

import com.jetbrains.youtrack.db.internal.client.remote.message.BinaryPushRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.BinaryPushResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.LiveQueryPushRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.PushDistributedConfigurationRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.PushFunctionsRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.PushIndexManagerRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.PushSchemaRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.PushSequencesRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.PushStorageConfigurationRequest;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.SocketChannelBinary;

/**
 *
 */
public interface RemotePushHandler {

  SocketChannelBinary getNetwork(String host);

  BinaryPushRequest createPush(byte push);

  BinaryPushResponse executeUpdateDistributedConfig(PushDistributedConfigurationRequest request);

  BinaryPushResponse executeUpdateStorageConfig(PushStorageConfigurationRequest request);

  void executeLiveQueryPush(LiveQueryPushRequest pushRequest);

  void onPushReconnect(String host);

  void onPushDisconnect(SocketChannelBinary network, Exception e);

  void returnSocket(SocketChannelBinary network);

  BinaryPushResponse executeUpdateSchema(PushSchemaRequest request);

  BinaryPushResponse executeUpdateSequences(PushSequencesRequest request);

  BinaryPushResponse executeUpdateIndexManager(PushIndexManagerRequest request);

  BinaryPushResponse executeUpdateFunction(PushFunctionsRequest request);
}
