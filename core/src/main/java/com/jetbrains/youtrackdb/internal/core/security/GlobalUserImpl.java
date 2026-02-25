package com.jetbrains.youtrackdb.internal.core.security;

public class GlobalUserImpl implements GlobalUser {

  private String name;
  private String password;
  private String resources;

  public GlobalUserImpl(String name, String password, String resources) {
    this.name = name;
    this.password = password;
    this.resources = resources;
  }

  @Override
  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
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
