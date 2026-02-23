package com.jetbrains.youtrackdb.internal.core.metadata.security.jwt;

/**
 * Represents a JSON Web Token (JWT) consisting of a header and payload.
 */
public interface JsonWebToken {

  TokenHeader getHeader();

  JwtPayload getPayload();
}
