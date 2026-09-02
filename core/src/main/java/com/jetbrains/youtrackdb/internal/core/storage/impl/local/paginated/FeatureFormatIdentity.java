package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

/** Identifies one disk-storage feature format independently of its bootstrap encoding. */
public record FeatureFormatIdentity(int version) {

  public FeatureFormatIdentity {
    if (version <= 0) {
      throw new IllegalArgumentException("Feature format version must be positive");
    }
  }
}
