package com.jetbrains.youtrackdb.internal.core.metadata.security.binary;

// The "node" token is for backward compatibility for old distributed binary, may be removed if
// we do not support runtime compatibility with 3.1 or less
public class DistributedBinaryTokenPayload extends BinaryTokenPayloadImpl {

  @Override
  public String getPayloadType() {
    return "node";
  }
}
