package com.jetbrains.youtrackdb.internal.core.metadata.security;

public class SecurityResourceAll extends SecurityResource {

  public static SecurityResourceAll INSTANCE = new SecurityResourceAll("*");

  private SecurityResourceAll(String resourceString) {
    super(resourceString);
  }

  @Override
  public boolean equals(Object obj) {
    return obj instanceof SecurityResourceAll;
  }

  @Override
  public int hashCode() {
    return 1;
  }
}
