package com.jetbrains.youtrackdb.internal.core.index.lifecycle;

import javax.annotation.Nonnull;

/** Stable storage-scoped carrier shared by every handle for one durable index descriptor. */
public final class IndexLifecycleCell {

  private volatile IndexLifecycle value;

  IndexLifecycleCell(@Nonnull IndexLifecycle value) {
    this.value = value;
  }

  public IndexLifecycle get() {
    return value;
  }

  public void set(@Nonnull IndexLifecycle value) {
    this.value = value;
  }
}
