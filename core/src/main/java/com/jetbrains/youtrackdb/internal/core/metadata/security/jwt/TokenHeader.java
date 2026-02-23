package com.jetbrains.youtrackdb.internal.core.metadata.security.jwt;

/**
 * Represents the header portion of a JSON Web Token (JWT).
 */
public interface TokenHeader {

  String getAlgorithm();

  void setAlgorithm(String alg);

  String getType();

  void setType(String typ);

  String getKeyId();

  void setKeyId(String kid);
}
