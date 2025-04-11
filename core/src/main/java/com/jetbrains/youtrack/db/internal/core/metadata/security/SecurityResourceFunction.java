package com.jetbrains.youtrack.db.internal.core.metadata.security;

import java.util.Objects;

public class SecurityResourceFunction extends SecurityResource {

  public static final SecurityResourceFunction ALL_FUNCTIONS =
      new SecurityResourceFunction("database.function.*", "*");

  private final String functionName;

  public SecurityResourceFunction(String resourceString, String functionName) {
    super(resourceString);
    this.functionName = functionName;
  }

  public String getFunctionName() {
    return functionName;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    var that = (SecurityResourceFunction) o;
    return Objects.equals(functionName, that.functionName);
  }

  @Override
  public int hashCode() {
    return Objects.hash(functionName);
  }
}
