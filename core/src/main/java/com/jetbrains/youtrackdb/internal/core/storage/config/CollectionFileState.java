package com.jetbrains.youtrackdb.internal.core.storage.config;

import com.jetbrains.youtrackdb.internal.core.exception.ConfigurationException;

/** Immutable existence flags for the logical files of one collection. */
public record CollectionFileState(int bits) {

  public enum Role {
    DATA(1), POSITION_MAP(2), FREE_SPACE_MAP(4), DIRTY_PAGE_BITSET(8), LINK_BAG_TREE(16);

    private final int mask;

    Role(int mask) {
      this.mask = mask;
    }
  }

  public static final CollectionFileState ALL_CREATED = new CollectionFileState(31);

  public CollectionFileState {
    if ((bits & ~31) != 0) {
      throw new ConfigurationException((String) null,
          "Unknown collection file role flags: " + bits);
    }
  }

  public boolean isCreated(Role role) {
    return (bits & role.mask) != 0;
  }
}
