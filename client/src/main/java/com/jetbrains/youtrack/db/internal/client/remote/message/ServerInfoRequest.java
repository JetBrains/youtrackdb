package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.internal.client.binary.BinaryRequestExecutor;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryRequest;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.StorageRemoteSession;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetwork;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import java.io.IOException;

public class ServerInfoRequest implements BinaryRequest<ServerInfoResponse> {

  public ServerInfoRequest() {
  }

  @Override
  public void write(DatabaseSessionInternal databaseSession, ChannelDataOutput network,
      StorageRemoteSession session) throws IOException {
  }

  @Override
  public void read(DatabaseSessionInternal databaseSession, ChannelDataInput channel,
      int protocolVersion,
      RecordSerializerNetwork serializer)
      throws IOException {
  }

  @Override
  public byte getCommand() {
    return ChannelBinaryProtocol.REQUEST_SERVER_INFO;
  }

  @Override
  public String requiredServerRole() {
    return "server.info";
  }

  @Override
  public boolean requireServerUser() {
    return true;
  }

  @Override
  public String getDescription() {
    return "Server Info";
  }

  @Override
  public ServerInfoResponse createResponse() {
    return new ServerInfoResponse();
  }

  @Override
  public boolean requireDatabaseSession() {
    return false;
  }

  @Override
  public BinaryResponse execute(BinaryRequestExecutor ex) {
    return ex.executeServerInfo(this);
  }
}
