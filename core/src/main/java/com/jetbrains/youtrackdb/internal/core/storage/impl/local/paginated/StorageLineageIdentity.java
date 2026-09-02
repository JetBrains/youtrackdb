package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import java.util.Objects;
import java.util.UUID;

/** Identifies one activated lifetime of a physical disk-storage target. */
public record StorageLineageIdentity(UUID value) {

  public StorageLineageIdentity {
    Objects.requireNonNull(value, "value");
  }

  public static StorageLineageIdentity random() {
    return new StorageLineageIdentity(UUID.randomUUID());
  }
}
