package com.jetbrains.youtrackdb.internal.core.metadata.security.jwt;

public interface BinaryTokenPayload extends TokenPayload {

  short getProtocolVersion();

  String getSerializer();

  String getDriverName();

  String getDriverVersion();

  boolean isServerUser();
}
