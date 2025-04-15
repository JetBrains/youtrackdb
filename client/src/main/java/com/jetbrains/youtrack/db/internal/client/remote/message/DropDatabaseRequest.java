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

public class DropDatabaseRequest implements BinaryRequest<DropDatabaseResponse> {

  private String databaseName;
  private String storageType;

  public DropDatabaseRequest(String databaseName, String storageType) {
    this.databaseName = databaseName;
    this.storageType = storageType;
  }

  public DropDatabaseRequest() {
  }

  @Override
  public void write(DatabaseSessionInternal databaseSession, ChannelDataOutput network,
      StorageRemoteSession session) throws IOException {
    network.writeString(databaseName);
    network.writeString(storageType);
  }

  @Override
  public void read(DatabaseSessionInternal databaseSession, ChannelDataInput channel,
      int protocolVersion,
      RecordSerializerNetwork serializer)
      throws IOException {
    databaseName = channel.readString();
    storageType = channel.readString();
  }

  public String getDatabaseName() {
    return databaseName;
  }

  public String getStorageType() {
    return storageType;
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
    return "database.drop";
  }

  @Override
  public byte getCommand() {
    return ChannelBinaryProtocol.REQUEST_DB_DROP;
  }

  @Override
  public String getDescription() {
    return "Drop Database";
  }

  @Override
  public DropDatabaseResponse createResponse() {
    return new DropDatabaseResponse();
  }

  @Override
  public BinaryResponse execute(BinaryRequestExecutor ex) {
    return ex.executeDropDatabase(this);
  }
}
