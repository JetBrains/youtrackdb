package com.jetbrains.youtrackdb.internal.core.metadata.security;

public class SecurityResourceSchema extends SecurityResource {

  public static SecurityResourceSchema INSTANCE = new SecurityResourceSchema("database.schema");

  private SecurityResourceSchema(String resourceString) {
    super(resourceString);
  }

  @Override
  public boolean equals(Object obj) {
    return obj instanceof SecurityResourceSchema;
  }

  @Override
  public int hashCode() {
    return 1;
  }
}
