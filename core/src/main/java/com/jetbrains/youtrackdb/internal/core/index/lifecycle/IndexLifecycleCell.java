package com.jetbrains.youtrackdb.internal.core.index.lifecycle;

import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;

/** Stable storage-scoped carrier shared by every handle for one durable index descriptor. */
public final class IndexLifecycleCell {

  private final AtomicReference<IndexLifecycleSnapshot> snapshot;

  IndexLifecycleCell(@Nonnull IndexLifecycleSnapshot snapshot) {
    this.snapshot = new AtomicReference<>(snapshot);
  }

  public IndexLifecycleSnapshot snapshot() {
    var current = snapshot.get();
    if (current == null) {
      throw new IllegalStateException("The lifecycle cell belongs to a retired storage lineage");
    }
    return current;
  }

  public IndexLifecycle get() {
    return snapshot().lifecycle();
  }

  public boolean compareAndSet(
      @Nonnull IndexLifecycleSnapshot expected, @Nonnull IndexLifecycleSnapshot replacement) {
    if (replacement.recordVersion() < expected.recordVersion()) {
      throw new IllegalArgumentException("A lifecycle snapshot cannot move to an older version");
    }
    if (snapshot.get() == null) {
      throw new IllegalStateException("The lifecycle cell belongs to a retired storage lineage");
    }
    return snapshot.compareAndSet(expected, replacement);
  }

  void retire() {
    snapshot.set(null);
  }
}
