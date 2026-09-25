package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import java.util.Objects;

/**
 * The highest issued logical operation identifier, qualified by its storage and lineage.
 * Identity qualification prevents a raw number from authorizing work in another lineage.
 */
public record LogicalSequenceFloor(
    StorageIdentity storageIdentity,
    StorageLineageIdentity lineageIdentity,
    long highestIssued) {

  public LogicalSequenceFloor {
    Objects.requireNonNull(storageIdentity, "storageIdentity");
    Objects.requireNonNull(lineageIdentity, "lineageIdentity");
    if (highestIssued < 0) {
      throw new IllegalArgumentException("Logical sequence floor must not be negative");
    }
  }
}
