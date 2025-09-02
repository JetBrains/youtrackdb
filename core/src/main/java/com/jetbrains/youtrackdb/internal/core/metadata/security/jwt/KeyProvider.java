package com.jetbrains.youtrackdb.internal.core.metadata.security.jwt;

import java.security.Key;

/**
 *
 */
public interface KeyProvider {

  Key getKey(TokenHeader header);

  String[] getKeys();

  String getDefaultKey();
}
