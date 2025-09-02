package com.jetbrains.youtrackdb.internal.core.security;

import com.jetbrains.youtrackdb.internal.core.metadata.security.jwt.TokenHeader;

public interface TokenSign {

  byte[] signToken(TokenHeader header, byte[] unsignedToken);

  boolean verifyTokenSign(ParsedToken parsed);

  String getAlgorithm();

  String getDefaultKey();

  String[] getKeys();
}
