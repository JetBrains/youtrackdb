package com.jetbrains.youtrackdb.internal.core.index.lifecycle;

/** In-memory lifecycle state used to gate index maintenance and reads. */
public enum IndexLifecycle {
  EXISTS, MAINTAINED, USABLE, INVALID
}
