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

public class CreateDatabaseRequest implements BinaryRequest<CreateDatabaseResponse> {

  private String databaseName;
  private String databaseType;
  private String storageMode;
  private String backupPath;

  public CreateDatabaseRequest(
      String databaseName, String databaseType, String storageMode, String backupPath) {
    this.databaseName = databaseName;
    this.databaseType = databaseType;
    this.storageMode = storageMode;
    this.backupPath = backupPath;
  }

  public CreateDatabaseRequest() {
  }

  @Override
  public void write(DatabaseSessionInternal databaseSession, ChannelDataOutput network,
      StorageRemoteSession session) throws IOException {
    network.writeString(databaseName);
    network.writeString(databaseType);
    network.writeString(storageMode);
    network.writeString(backupPath);
  }

  @Override
  public void read(DatabaseSessionInternal databaseSession, ChannelDataInput channel,
      int protocolVersion,
      RecordSerializerNetwork serializer)
      throws IOException {
    this.databaseName = channel.readString();
    this.databaseType = channel.readString();
    this.storageMode = channel.readString();
    if (protocolVersion > 35) {
      this.backupPath = channel.readString();
    }
  }

  @Override
  public String requiredServerRole() {
    return "database.create";
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
  public byte getCommand() {
    return ChannelBinaryProtocol.REQUEST_DB_CREATE;
  }

  @Override
  public String getDescription() {
    return "Create database";
  }

  public String getBackupPath() {
    return backupPath;
  }

  public String getDatabaseName() {
    return databaseName;
  }

  public String getDatabaseType() {
    return databaseType;
  }

  public String getStorageMode() {
    return storageMode;
  }

  @Override
  public CreateDatabaseResponse createResponse() {
    return new CreateDatabaseResponse();
  }

  @Override
  public BinaryResponse execute(BinaryRequestExecutor ex) {
    return ex.executeCreateDatabase(this);
  }
}
