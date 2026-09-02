package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import java.util.Objects;

/** Supplies the format and source identity context required to adopt a cross-lineage floor. */
public record LineageFloorAdoption(
    FeatureFormatIdentity format, LogicalSequenceFloor sourceFloor) {

  public LineageFloorAdoption {
    Objects.requireNonNull(format, "format");
    Objects.requireNonNull(sourceFloor, "sourceFloor");
  }
}
