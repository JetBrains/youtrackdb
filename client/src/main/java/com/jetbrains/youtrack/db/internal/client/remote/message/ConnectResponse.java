package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.internal.client.binary.SocketChannelBinaryAsynchClient;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.StorageRemoteSession;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import com.jetbrains.youtrack.db.internal.remote.RemoteDatabaseSessionInternal;
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
    if (protocolVersion > ChannelBinaryProtocol.PROTOCOL_VERSION_26) {
      channel.writeBytes(sessionToken);
    }
  }

  @Override
  public void read(RemoteDatabaseSessionInternal db, ChannelDataInput network,
      StorageRemoteSession session) throws IOException {
    sessionId = network.readInt();
    sessionToken = network.readBytes();
    session
        .getServerSession(((SocketChannelBinaryAsynchClient) network).getServerURL())
        .setSession(sessionId, sessionToken);
  }
}
