package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.internal.client.binary.BinaryRequestExecutor;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryRequest;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.StorageRemote;
import com.jetbrains.youtrack.db.internal.client.remote.StorageRemoteSession;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBConstants;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetwork;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetworkBase;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import java.io.IOException;

public class OpenRequest implements BinaryRequest<OpenResponse> {

  private String driverName = StorageRemote.DRIVER_NAME;
  private String driverVersion = YouTrackDBConstants.getRawVersion();
  private short protocolVersion = ChannelBinaryProtocol.CURRENT_PROTOCOL_VERSION;
  private String clientId = null;
  private String recordFormat = RecordSerializerNetworkBase.NAME;
  private boolean useToken = true;
  private boolean supportsPush = true;
  private boolean collectStats = true;
  private String databaseName;
  private String userName;
  private String userPassword;
  private String dbType;

  public OpenRequest(String databaseName, String userName, String userPassword) {
    this.databaseName = databaseName;
    this.userName = userName;
    this.userPassword = userPassword;
  }

  public OpenRequest() {
  }

  @Override
  public void write(DatabaseSessionInternal databaseSession, ChannelDataOutput network,
      StorageRemoteSession session) throws IOException {
    network.writeString(driverName);
    network.writeString(driverVersion);
    network.writeShort(protocolVersion);
    network.writeString(clientId);
    network.writeString(recordFormat);
    network.writeBoolean(useToken);
    network.writeBoolean(supportsPush);
    network.writeBoolean(collectStats);
    network.writeString(databaseName);
    network.writeString(userName);
    network.writeString(userPassword);
  }

  @Override
  public void read(DatabaseSessionInternal databaseSession, ChannelDataInput channel,
      int protocolVersion,
      RecordSerializerNetwork serializer)
      throws IOException {

    driverName = channel.readString();
    driverVersion = channel.readString();
    this.protocolVersion = channel.readShort();
    clientId = channel.readString();
    this.recordFormat = channel.readString();

    if (this.protocolVersion > ChannelBinaryProtocol.PROTOCOL_VERSION_26) {
      useToken = channel.readBoolean();
    } else {
      useToken = false;
    }
    if (this.protocolVersion > ChannelBinaryProtocol.PROTOCOL_VERSION_33) {
      supportsPush = channel.readBoolean();
      collectStats = channel.readBoolean();
    } else {
      supportsPush = true;
      collectStats = true;
    }
    databaseName = channel.readString();
    if (this.protocolVersion <= ChannelBinaryProtocol.PROTOCOL_VERSION_32) {
      dbType = channel.readString();
    }
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

  public String getDriverName() {
    return driverName;
  }

  public String getDriverVersion() {
    return driverVersion;
  }

  public String getClientId() {
    return clientId;
  }

  public short getProtocolVersion() {
    return protocolVersion;
  }

  public String getRecordFormat() {
    return recordFormat;
  }

  public boolean isCollectStats() {
    return collectStats;
  }

  public boolean isSupportsPush() {
    return supportsPush;
  }

  public boolean isUseToken() {
    return useToken;
  }

  @Override
  public boolean requireDatabaseSession() {
    return false;
  }

  @Override
  public OpenResponse createResponse() {
    return new OpenResponse();
  }

  @Override
  public BinaryResponse execute(BinaryRequestExecutor executor) {
    return executor.executeDatabaseOpen(this);
  }
}
