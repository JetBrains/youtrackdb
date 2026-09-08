package com.jetbrains.youtrackdb.internal.core.index.lifecycle;

/** Machine-readable reason attached to failed or invalid index build state. */
public enum IndexBuildFailure {
  NONE, UNIQUE_KEY_CONFLICT, INDEX_BUILD_STATE_MISSING
}
