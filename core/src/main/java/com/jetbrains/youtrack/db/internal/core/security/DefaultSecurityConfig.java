package com.jetbrains.youtrack.db.internal.core.security;

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
