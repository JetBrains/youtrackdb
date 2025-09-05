package com.jetbrains.youtrackdb.internal.core.security;

import javax.annotation.Nullable;

public class DefaultSecurityConfig implements SecurityConfig {

  @Override
  public Syslog getSyslog() {
    throw new UnsupportedOperationException();
  }

  @Nullable
  @Override
  public String getConfigurationFile() {
    return null;
  }
}
