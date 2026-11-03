package com.jetbrains.youtrackdb.internal.core.storage.config;

import com.jetbrains.youtrackdb.internal.core.config.IndexEngineData;
import com.jetbrains.youtrackdb.internal.core.exception.ConfigurationException;

/** Immutable existence flags for one supported B-tree engine kind. */
public record IndexEngineFileState(boolean multivalue, int bits) {

  public enum Role {
    MAIN_TREE(1), MAIN_TREE_NULL_BUCKET(2), STATISTICS(4), NULL_KEY_TREE(
        8), NULL_KEY_TREE_NULL_BUCKET(16);

    private final int mask;

    Role(int mask) {
      this.mask = mask;
    }
  }

  public static int roleMask(boolean multivalue) {
    var mask = 0;
    for (var role : Role.values()) {
      if (multivalue || (role != Role.NULL_KEY_TREE && role != Role.NULL_KEY_TREE_NULL_BUCKET)) {
        mask |= role.mask;
      }
    }
    return mask;
  }

  public IndexEngineFileState {
    if ((bits & ~roleMask(multivalue)) != 0) {
      throw new ConfigurationException((String) null, "Unknown index engine file flags: " + bits);
    }
  }

  public static IndexEngineFileState allCreated(boolean multivalue) {
    return new IndexEngineFileState(multivalue, roleMask(multivalue));
  }

  public boolean isCreated(Role role) {
    return (bits & role.mask) != 0;
  }

  // These are exactly the implementation versions accepted by the two B-tree constructors.
  public static void requireSupportedKind(IndexEngineData data, String storageName) {
    if (!"BTREE".equals(data.getAlgorithm())
        || (data.getVersion() != 4 && (data.isMultivalue() || data.getVersion() != 3))) {
      throw new ConfigurationException(storageName,
          "Index engine entry '" + data.getName() + "' has no file role set: algorithm "
              + data.getAlgorithm() + ", implementation version " + data.getVersion());
    }
  }
}
