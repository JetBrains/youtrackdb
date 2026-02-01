package com.jetbrains.youtrackdb.internal.core.security.authenticator;

import com.jetbrains.youtrackdb.internal.core.security.GlobalUser;

public class TemporaryGlobalUser implements GlobalUser {

  private String name;
  private String password;
  private String resources;

  public TemporaryGlobalUser(String user, String password, String resources) {
    super();
    this.name = user;
    this.password = password;
    this.resources = resources;
  }

  @Override
  public String getName() {
    return name;
  }

  public void setName(String user) {
    this.name = user;
  }

  @Override
  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }

  @Override
  public String getResources() {
    return resources;
  }

  public void setResources(String resources) {
    this.resources = resources;
  }
}
