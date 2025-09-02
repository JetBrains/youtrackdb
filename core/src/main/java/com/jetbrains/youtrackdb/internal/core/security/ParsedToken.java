package com.jetbrains.youtrackdb.internal.core.security;

import com.jetbrains.youtrackdb.internal.core.metadata.security.Token;

public class ParsedToken {

  private final Token token;
  private final byte[] tokenBytes;
  private final byte[] signature;

  public ParsedToken(Token token, byte[] tokenBytes, byte[] signature) {
    super();
    this.token = token;
    this.tokenBytes = tokenBytes;
    this.signature = signature;
  }

  public Token getToken() {
    return token;
  }

  public byte[] getTokenBytes() {
    return tokenBytes;
  }

  public byte[] getSignature() {
    return signature;
  }
}
