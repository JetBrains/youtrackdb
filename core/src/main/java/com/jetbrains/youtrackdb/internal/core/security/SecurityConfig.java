package com.jetbrains.youtrackdb.internal.core.security;

public interface SecurityConfig {

  Syslog getSyslog();

  String getConfigurationFile();
}
