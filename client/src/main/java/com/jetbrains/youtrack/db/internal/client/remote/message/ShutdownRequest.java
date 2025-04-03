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
import javax.annotation.Nullable;

public class ShutdownRequest implements BinaryRequest<BinaryResponse> {

  private String rootUser;
  private String rootPassword;

  public ShutdownRequest(String rootUser, String rootPassword) {
    super();
    this.rootUser = rootUser;
    this.rootPassword = rootPassword;
  }

  public ShutdownRequest() {
  }

  @Override
  public void write(DatabaseSessionInternal databaseSession, ChannelDataOutput network,
      StorageRemoteSession session) throws IOException {
    network.writeString(rootUser);
    network.writeString(rootPassword);
  }

  @Override
  public void read(DatabaseSessionInternal databaseSession, ChannelDataInput channel,
      int protocolVersion,
      RecordSerializerNetwork serializer)
      throws IOException {
    rootUser = channel.readString();
    rootPassword = channel.readString();
  }

  @Override
  public byte getCommand() {
    return ChannelBinaryProtocol.REQUEST_SHUTDOWN;
  }

  @Override
  public String getDescription() {
    return "Shutdown Server";
  }

  public String getRootPassword() {
    return rootPassword;
  }

  public String getRootUser() {
    return rootUser;
  }

  @Nullable
  @Override
  public BinaryResponse createResponse() {
    return null;
  }

  @Override
  public boolean requireDatabaseSession() {
    return false;
  }

  @Override
  public BinaryResponse execute(BinaryRequestExecutor executor) {
    return executor.executeShutdown(this);
  }
}
