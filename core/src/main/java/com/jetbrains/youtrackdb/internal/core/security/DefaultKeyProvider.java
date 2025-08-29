package com.jetbrains.youtrackdb.internal.core.security;

import com.jetbrains.youtrackdb.internal.core.metadata.security.jwt.KeyProvider;
import com.jetbrains.youtrackdb.internal.core.metadata.security.jwt.TokenHeader;
import java.security.Key;
import javax.crypto.spec.SecretKeySpec;

/**
 *
 */
public class DefaultKeyProvider implements KeyProvider {

  private final SecretKeySpec secretKey;

  public DefaultKeyProvider(byte[] secret) {
    secretKey = new SecretKeySpec(secret, "HmacSHA256");
  }

  @Override
  public Key getKey(TokenHeader header) {
    return secretKey;
  }

  @Override
  public String getDefaultKey() {
    return "default";
  }

  @Override
  public String[] getKeys() {
    return new String[]{"default"};
  }
}
