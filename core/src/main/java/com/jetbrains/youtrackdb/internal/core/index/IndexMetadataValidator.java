package com.jetbrains.youtrackdb.internal.core.index;

import java.util.Map;

/** Validates the user-controlled index metadata namespace. */
public final class IndexMetadataValidator {

  public static final String RESERVED_PREFIX = "__ytdb_";

  private IndexMetadataValidator() {
  }

  public static void validate(Map<String, ?> metadata) {
    if (metadata == null) {
      return;
    }
    for (var key : metadata.keySet()) {
      if (key != null && key.startsWith(RESERVED_PREFIX)) {
        throw new IllegalArgumentException(
            "Index metadata key '" + key + "' uses reserved prefix " + RESERVED_PREFIX);
      }
    }
  }
}
