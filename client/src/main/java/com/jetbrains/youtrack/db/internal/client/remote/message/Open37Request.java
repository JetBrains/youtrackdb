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

public class Open37Request implements BinaryRequest<Open37Response> {

  private String databaseName;
  private String userName;
  private String userPassword;

  public Open37Request(String databaseName, String userName, String userPassword) {
    this.databaseName = databaseName;
    this.userName = userName;
    this.userPassword = userPassword;
  }

  public Open37Request() {
  }

  @Override
  public void write(DatabaseSessionInternal databaseSession, ChannelDataOutput network,
      StorageRemoteSession session) throws IOException {
    network.writeString(databaseName);
    network.writeString(userName);
    network.writeString(userPassword);
  }

  @Override
  public void read(DatabaseSessionInternal databaseSession, ChannelDataInput channel,
      int protocolVersion,
      RecordSerializerNetwork serializer)
      throws IOException {
    databaseName = channel.readString();
    userName = channel.readString();
    userPassword = channel.readString();
  }

  @Override
  public byte getCommand() {
    return ChannelBinaryProtocol.REQUEST_DB_OPEN;
  }

  @Override
  public String getDescription() {
    return "Open Database";
  }

  public String getDatabaseName() {
    return databaseName;
  }

  public String getUserName() {
    return userName;
  }

  public String getUserPassword() {
    return userPassword;
  }

  @Override
  public boolean requireDatabaseSession() {
    return false;
  }

  @Override
  public Open37Response createResponse() {
    return new Open37Response();
  }

  @Override
  public BinaryResponse execute(BinaryRequestExecutor executor) {
    return executor.executeDatabaseOpen37(this);
  }
}
