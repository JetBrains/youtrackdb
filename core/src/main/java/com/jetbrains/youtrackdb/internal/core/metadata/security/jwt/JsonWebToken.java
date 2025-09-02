package com.jetbrains.youtrackdb.internal.core.metadata.security.jwt;

/**
 *
 */
public interface JsonWebToken {

  TokenHeader getHeader();

  JwtPayload getPayload();
}
