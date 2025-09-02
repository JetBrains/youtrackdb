package com.jetbrains.youtrackdb.internal.core.metadata.security.auth;

import java.util.Optional;

public class UserPasswordAuthInfo implements AuthenticationInfo {

  private String database;
  private String user;
  private String password;

  @Override
  public Optional<String> getDatabase() {
    return Optional.ofNullable(database);
  }

  public String getUser() {
    return user;
  }

  public String getPassword() {
    return password;
  }
}
