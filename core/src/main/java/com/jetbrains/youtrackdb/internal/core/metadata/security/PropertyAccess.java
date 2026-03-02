package com.jetbrains.youtrackdb.internal.core.metadata.security;

import java.util.Set;

public class PropertyAccess {

  /** Singleton for the common case where no property filtering is needed. */
  public static final PropertyAccess NO_FILTER = new PropertyAccess((Set<String>) null);

  private final Set<String> filtered;

  public PropertyAccess(Set<String> filtered) {
    this.filtered = filtered;
  }

  public boolean hasFilters() {
    return filtered != null && !filtered.isEmpty();
  }

  public boolean isReadable(String property) {
    return filtered == null || !filtered.contains(property);
  }
}
