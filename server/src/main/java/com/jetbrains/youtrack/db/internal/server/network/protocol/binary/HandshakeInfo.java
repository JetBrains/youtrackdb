package com.jetbrains.youtrack.db.internal.server.network.protocol.binary;

import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetwork;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetworkFactory;

/**
 *
 */
public class HandshakeInfo {

  private short protocolVersion;
  private String driverName;
  private String driverVersion;
  private final byte encoding;
  private final byte errorEncoding;
  private final RecordSerializerNetwork serializer;

  public HandshakeInfo(
      short protocolVersion,
      String driverName,
      String driverVersion,
      byte encoding,
      byte errorEncoding) {
    this.protocolVersion = protocolVersion;
    this.driverName = driverName;
    this.driverVersion = driverVersion;
    this.encoding = encoding;
    this.errorEncoding = errorEncoding;
    this.serializer = RecordSerializerNetworkFactory.forProtocol(protocolVersion);
  }

  public short getProtocolVersion() {
    return protocolVersion;
  }

  public void setProtocolVersion(short protocolVersion) {
    this.protocolVersion = protocolVersion;
  }

  public String getDriverName() {
    return driverName;
  }

  public void setDriverName(String driverName) {
    this.driverName = driverName;
  }

  public String getDriverVersion() {
    return driverVersion;
  }

  public void setDriverVersion(String driverVersion) {
    this.driverVersion = driverVersion;
  }

  public RecordSerializerNetwork getSerializer() {
    return serializer;
  }

  public byte getEncoding() {
    return encoding;
  }

  public byte getErrorEncoding() {
    return errorEncoding;
  }
}
