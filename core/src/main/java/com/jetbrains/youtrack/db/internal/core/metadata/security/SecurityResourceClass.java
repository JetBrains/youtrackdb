package com.jetbrains.youtrack.db.internal.core.metadata.security;

import java.util.Objects;

public class SecurityResourceClass extends SecurityResource {

  public static final SecurityResourceClass ALL_CLASSES =
      new SecurityResourceClass("database.class.*", "*");

  private final String className;

  public SecurityResourceClass(String resourceString, String className) {
    super(resourceString);
    this.className = className;
  }

  public String getClassName() {
    return className;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    var that = (SecurityResourceClass) o;
    return Objects.equals(className, that.className);
  }

  @Override
  public int hashCode() {
    return Objects.hash(className);
  }
}
