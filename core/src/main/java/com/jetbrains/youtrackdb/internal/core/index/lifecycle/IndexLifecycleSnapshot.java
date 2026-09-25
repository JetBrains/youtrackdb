package com.jetbrains.youtrackdb.internal.core.index.lifecycle;

import java.util.Objects;

/** Immutable in-memory view of one durable index build state revision. */
public record IndexLifecycleSnapshot(IndexBuildState buildState, long recordVersion) {

  public IndexLifecycleSnapshot {
    Objects.requireNonNull(buildState, "buildState");
    if (recordVersion < 0) {
      throw new IllegalArgumentException("The record version cannot be negative");
    }
  }

  public IndexLifecycle lifecycle() {
    return buildState.lifecycle();
  }
}
