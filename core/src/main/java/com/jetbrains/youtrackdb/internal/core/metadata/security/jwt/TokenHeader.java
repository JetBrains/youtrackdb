package com.jetbrains.youtrackdb.internal.core.metadata.security.jwt;

/**
 *
 */
public interface TokenHeader {

  String getAlgorithm();

  void setAlgorithm(String alg);

  String getType();

  void setType(String typ);

  String getKeyId();

  void setKeyId(String kid);
}
