package com.jetbrains.youtrack.db.internal.core.metadata.security.binary;

import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.metadata.security.jwt.BinaryTokenPayload;
import com.jetbrains.youtrack.db.internal.core.metadata.security.jwt.TokenMetaInfo;
import java.io.DataOutputStream;
import java.io.IOException;

public class BinaryTokenPayloadImpl implements BinaryTokenPayload {

  private String userName;
  private String database;
  private long expiry;
  private RID userRid;
  private String databaseType;
  private short protocolVersion;
  private String serializer;
  private String driverName;
  private String driverVersion;
  private boolean serverUser;

  @Override
  public String getDatabase() {
    return database;
  }

  public void setDatabase(String database) {
    this.database = database;
  }

  @Override
  public long getExpiry() {
    return expiry;
  }

  public void setExpiry(long expiry) {
    this.expiry = expiry;
  }

  @Override
  public RID getUserRid() {
    return userRid;
  }

  public void setUserRid(RID rid) {
    this.userRid = rid;
  }

  @Override
  public String getDatabaseType() {
    return databaseType;
  }

  public void setDatabaseType(String databaseType) {
    this.databaseType = databaseType;
  }

  @Override
  public short getProtocolVersion() {
    return protocolVersion;
  }

  public void setProtocolVersion(short protocolVersion) {
    this.protocolVersion = protocolVersion;
  }

  @Override
  public String getSerializer() {
    return serializer;
  }

  public void setSerializer(String serializer) {
    this.serializer = serializer;
  }

  @Override
  public String getDriverName() {
    return driverName;
  }

  public void setDriverName(String driverName) {
    this.driverName = driverName;
  }

  @Override
  public String getDriverVersion() {
    return driverVersion;
  }

  public void setDriverVersion(String driverVersion) {
    this.driverVersion = driverVersion;
  }

  @Override
  public boolean isServerUser() {
    return serverUser;
  }

  public void setServerUser(boolean serverUser) {
    this.serverUser = serverUser;
  }

  @Override
  public String getUserName() {
    return userName;
  }

  public void setUserName(String userName) {
    this.userName = userName;
  }

  @Override
  public void serialize(DataOutputStream output, TokenMetaInfo serializer) throws IOException {
    var toWrite = this.database;
    BinaryTokenSerializer.writeString(output, toWrite);
    if (this.databaseType == null) {
      output.writeByte(-1);
    } else {
      output.writeByte(serializer.getDbTypeID(this.databaseType));
    }
    var id = this.userRid;
    if (id == null) {
      output.writeShort(-1);
      output.writeLong(-1);
    } else {
      output.writeShort(id.getCollectionId());
      output.writeLong(id.getCollectionPosition());
    }
    output.writeLong(this.expiry);
    output.writeBoolean(this.serverUser);
    if (this.serverUser) {
      BinaryTokenSerializer.writeString(output, this.userName);
    }
    output.writeShort(this.protocolVersion);
    BinaryTokenSerializer.writeString(output, this.serializer);
    BinaryTokenSerializer.writeString(output, this.driverName);
    BinaryTokenSerializer.writeString(output, this.driverVersion);
  }

  @Override
  public String getPayloadType() {
    return "YouTrackDB";
  }
}
