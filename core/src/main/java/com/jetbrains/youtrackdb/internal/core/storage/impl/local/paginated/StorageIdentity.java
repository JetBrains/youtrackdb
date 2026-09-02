package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import java.util.Objects;
import java.util.UUID;

/** Identifies one physical disk-storage target across lineage replacements. */
public record StorageIdentity(UUID value) {

  public StorageIdentity {
    Objects.requireNonNull(value, "value");
  }

  public static StorageIdentity random() {
    return new StorageIdentity(UUID.randomUUID());
  }
}
