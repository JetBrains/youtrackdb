package com.jetbrains.youtrack.db.internal.client.remote;

import com.jetbrains.youtrack.db.internal.client.remote.message.BinaryPushRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.LiveQueryPushRequest;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.SocketChannelBinary;

/**
 *
 */
public interface RemotePushHandler {

  SocketChannelBinary getNetwork(String host);

  BinaryPushRequest createPush(byte push);

  void executeLiveQueryPush(LiveQueryPushRequest pushRequest, SocketChannelBinary network);

  void onPushReconnect(String host);

  void onPushDisconnect(SocketChannelBinary network, Exception e);

  void returnSocket(SocketChannelBinary network);
}
