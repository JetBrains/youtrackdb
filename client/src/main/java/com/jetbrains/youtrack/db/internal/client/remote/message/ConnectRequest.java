package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.internal.client.binary.BinaryRequestExecutor;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryRequest;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.StorageRemote;
import com.jetbrains.youtrack.db.internal.client.remote.StorageRemoteSession;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBConstants;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetwork;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetworkV37;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import java.io.IOException;

public class ConnectRequest implements BinaryRequest<ConnectResponse> {

  private String username;
  private String password;
  private String driverName = StorageRemote.DRIVER_NAME;
  private String driverVersion = YouTrackDBConstants.getRawVersion();
  private short protocolVersion = ChannelBinaryProtocol.CURRENT_PROTOCOL_VERSION;
  private String clientId = null;
  private String recordFormat = RecordSerializerNetworkV37.NAME;
  private boolean tokenBased = true;
  private boolean supportPush = true;
  private boolean collectStats = true;

  public ConnectRequest(String username, String password) {
    this.username = username;
    this.password = password;
  }

  public ConnectRequest() {
  }

  @Override
  public void write(DatabaseSessionInternal databaseSession, ChannelDataOutput network,
      StorageRemoteSession session) throws IOException {
    network.writeString(driverName);
    network.writeString(driverVersion);
    network.writeShort(protocolVersion);
    network.writeString(clientId);

    network.writeString(recordFormat);
    network.writeBoolean(tokenBased);
    network.writeBoolean(supportPush);
    network.writeBoolean(collectStats);

    network.writeString(username);
    network.writeString(password);
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
    recordFormat = channel.readString();

    if (this.protocolVersion > ChannelBinaryProtocol.PROTOCOL_VERSION_26) {
      tokenBased = channel.readBoolean();
    } else {
      tokenBased = false;
    }

    if (this.protocolVersion > ChannelBinaryProtocol.PROTOCOL_VERSION_33) {
      supportPush = channel.readBoolean();
      collectStats = channel.readBoolean();
    } else {
      supportPush = true;
      collectStats = true;
    }

    username = channel.readString();
    password = channel.readString();
  }

  @Override
  public byte getCommand() {
    return ChannelBinaryProtocol.REQUEST_CONNECT;
  }

  @Override
  public String getDescription() {
    return "Connect";
  }

  public String getClientId() {
    return clientId;
  }

  public String getDriverName() {
    return driverName;
  }

  public String getDriverVersion() {
    return driverVersion;
  }

  public String getPassword() {
    return password;
  }

  public short getProtocolVersion() {
    return protocolVersion;
  }

  public String getUsername() {
    return username;
  }

  public String getRecordFormat() {
    return recordFormat;
  }

  public boolean isCollectStats() {
    return collectStats;
  }

  public boolean isSupportPush() {
    return supportPush;
  }

  public boolean isTokenBased() {
    return tokenBased;
  }

  @Override
  public boolean requireDatabaseSession() {
    return false;
  }

  @Override
  public ConnectResponse createResponse() {
    return new ConnectResponse();
  }

  @Override
  public BinaryResponse execute(BinaryRequestExecutor executor) {
    return executor.executeConnect(this);
  }
}
