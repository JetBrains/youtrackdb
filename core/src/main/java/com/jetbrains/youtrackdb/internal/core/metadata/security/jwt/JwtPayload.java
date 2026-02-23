package com.jetbrains.youtrackdb.internal.core.metadata.security.jwt;

/**
 * Payload of a JSON Web Token containing standard JWT claims.
 */
public interface JwtPayload extends TokenPayload {

  String getIssuer();

  void setIssuer(String iss);

  long getIssuedAt();

  void setIssuedAt(long iat);

  long getNotBefore();

  void setNotBefore(long nbf);

  void setUserName(String sub);

  String getAudience();

  void setAudience(String aud);

  String getTokenId();

  void setTokenId(String jti);

  void setDatabase(String database);

  void setDatabaseType(String databaseType);
}
