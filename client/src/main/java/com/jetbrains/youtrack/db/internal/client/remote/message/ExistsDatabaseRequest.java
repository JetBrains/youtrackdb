package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.internal.client.binary.BinaryRequestExecutor;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryRequest;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.StorageRemoteSession;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import com.jetbrains.youtrack.db.internal.remote.RemoteDatabaseSessionInternal;
import java.io.IOException;

public class ExistsDatabaseRequest implements BinaryRequest<ExistsDatabaseResponse> {

  private String databaseName;
  private String storageType;

  public ExistsDatabaseRequest(String databaseName, String storageType) {
    this.databaseName = databaseName;
    this.storageType = storageType;
  }

  public ExistsDatabaseRequest() {
  }

  @Override
  public void write(RemoteDatabaseSessionInternal databaseSession, ChannelDataOutput network,
      StorageRemoteSession session) throws IOException {
    network.writeString(databaseName);
    network.writeString(storageType);
  }

  @Override
  public void read(DatabaseSessionEmbedded databaseSession, ChannelDataInput channel,
      int protocolVersion)
      throws IOException {
    databaseName = channel.readString();
    storageType = channel.readString();
  }

  @Override
  public byte getCommand() {
    return ChannelBinaryProtocol.REQUEST_DB_EXIST;
  }

  @Override
  public boolean requireServerUser() {
    return true;
  }

  @Override
  public boolean requireDatabaseSession() {
    return false;
  }

  @Override
  public String requiredServerRole() {
    return "database.exists";
  }

  @Override
  public String getDescription() {
    return "Exists Database";
  }

  public String getDatabaseName() {
    return databaseName;
  }

  public String getStorageType() {
    return storageType;
  }

  @Override
  public ExistsDatabaseResponse createResponse() {
    return new ExistsDatabaseResponse();
  }

  @Override
  public BinaryResponse execute(BinaryRequestExecutor ex) {
    return ex.executeExistDatabase(this);
  }
}
