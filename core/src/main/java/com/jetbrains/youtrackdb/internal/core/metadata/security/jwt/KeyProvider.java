package com.jetbrains.youtrackdb.internal.core.metadata.security.jwt;

import java.security.Key;

/**
 * Provides cryptographic keys used for signing and verifying tokens.
 */
public interface KeyProvider {

  Key getKey(TokenHeader header);

  String[] getKeys();

  String getDefaultKey();
}
