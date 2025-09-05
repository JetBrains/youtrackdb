package com.jetbrains.youtrackdb.internal.server.network.protocol.binary;

/**
 *
 */
public class HandshakeInfo {

  private short protocolVersion;
  private String driverName;
  private String driverVersion;
  private final byte encoding;
  private final byte errorEncoding;

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

  public byte getEncoding() {
    return encoding;
  }

  public byte getErrorEncoding() {
    return errorEncoding;
  }
}
