package com.jetbrains.youtrackdb.internal.client.remote.message;

import com.jetbrains.youtrackdb.internal.client.binary.BinaryRequestExecutor;
import com.jetbrains.youtrackdb.internal.client.remote.BinaryProtocolSession;
import com.jetbrains.youtrackdb.internal.client.remote.BinaryRequest;
import com.jetbrains.youtrackdb.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import com.jetbrains.youtrackdb.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrackdb.internal.enterprise.channel.binary.ChannelDataOutput;
import com.jetbrains.youtrackdb.internal.remote.RemoteDatabaseSessionInternal;
import java.io.IOException;

public class ReleaseDatabaseRequest implements BinaryRequest<ReleaseDatabaseResponse> {

  private String name;
  private String storageType;

  public ReleaseDatabaseRequest(String name, String storageType) {
    this.name = name;
    this.storageType = storageType;
  }

  public ReleaseDatabaseRequest() {
  }

  @Override
  public void write(RemoteDatabaseSessionInternal databaseSession, ChannelDataOutput network,
      BinaryProtocolSession session) throws IOException {
    network.writeString(name);
    network.writeString(storageType);
  }

  @Override
  public void read(DatabaseSessionEmbedded databaseSession, ChannelDataInput channel,
      int protocolVersion)
      throws IOException {
    name = channel.readString();
    storageType = channel.readString();
  }

  @Override
  public byte getCommand() {
    return ChannelBinaryProtocol.REQUEST_DB_RELEASE;
  }

  @Override
  public String requiredServerRole() {
    return "database.release";
  }

  @Override
  public boolean requireServerUser() {
    return true;
  }

  @Override
  public String getDescription() {
    return "Release Database";
  }

  public String getName() {
    return name;
  }

  public String getStorageType() {
    return storageType;
  }

  @Override
  public boolean requireDatabaseSession() {
    return false;
  }

  @Override
  public ReleaseDatabaseResponse createResponse() {
    return new ReleaseDatabaseResponse();
  }

  @Override
  public BinaryResponse execute(BinaryRequestExecutor executor) {
    return executor.executeReleaseDatabase(this);
  }
}
