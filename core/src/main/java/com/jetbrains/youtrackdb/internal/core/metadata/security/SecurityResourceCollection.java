package com.jetbrains.youtrackdb.internal.core.metadata.security;

import java.util.Objects;

public class SecurityResourceCollection extends SecurityResource {

  public static final SecurityResourceCollection ALL_COLLECTIONS =
      new SecurityResourceCollection("database.collection.*", "*");
  public static final SecurityResourceCollection SYSTEM_COLLECTIONS =
      new SecurityResourceCollection("database.systemcollections", "");

  private final String collectionName;

  public SecurityResourceCollection(String resourceString, String collectionName) {
    super(resourceString);
    this.collectionName = collectionName;
  }

  public String getCollectionName() {
    return collectionName;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    var that = (SecurityResourceCollection) o;
    return Objects.equals(collectionName, that.collectionName);
  }

  @Override
  public int hashCode() {
    return Objects.hash(collectionName);
  }
}
