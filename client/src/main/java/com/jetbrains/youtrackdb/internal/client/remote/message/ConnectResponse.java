package com.jetbrains.youtrackdb.internal.client.remote.message;

import com.jetbrains.youtrackdb.internal.client.binary.SocketChannelBinaryAsynchClient;
import com.jetbrains.youtrackdb.internal.client.remote.BinaryProtocolSession;
import com.jetbrains.youtrackdb.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrackdb.internal.enterprise.channel.binary.ChannelDataOutput;
import com.jetbrains.youtrackdb.internal.remote.RemoteDatabaseSessionInternal;
import java.io.IOException;

public class ConnectResponse implements BinaryResponse {

  private int sessionId;
  private byte[] sessionToken;

  public ConnectResponse() {
  }

  public ConnectResponse(int sessionId, byte[] token) {
    this.sessionId = sessionId;
    this.sessionToken = token;
  }

  @Override
  public void write(DatabaseSessionEmbedded session, ChannelDataOutput channel,
      int protocolVersion)
      throws IOException {
    channel.writeInt(sessionId);
    channel.writeBytes(sessionToken);
  }

  @Override
  public void read(RemoteDatabaseSessionInternal db, ChannelDataInput network,
      BinaryProtocolSession session) throws IOException {
    sessionId = network.readInt();
    sessionToken = network.readBytes();
    session
        .getServerSession(((SocketChannelBinaryAsynchClient) network).getServerURL())
        .setSession(sessionId, sessionToken);
  }
}
