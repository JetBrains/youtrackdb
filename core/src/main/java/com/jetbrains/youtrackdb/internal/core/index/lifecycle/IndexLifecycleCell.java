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
    var descriptorIdentity = expected.buildState().descriptorIdentity();
    if (!descriptorIdentity.equals(replacement.buildState().descriptorIdentity())) {
      throw new IllegalArgumentException("The lifecycle snapshot belongs to another descriptor");
    }

    while (true) {
      var current = snapshot.get();
      if (current == null) {
        throw new IllegalStateException("The lifecycle cell belongs to a retired storage lineage");
      }
      if (!descriptorIdentity.equals(current.buildState().descriptorIdentity())) {
        throw new IllegalArgumentException("The lifecycle snapshot belongs to another descriptor");
      }
      if (replacement.recordVersion() < current.recordVersion()) {
        throw new IllegalArgumentException("A lifecycle snapshot cannot move to an older version");
      }
      if (!current.equals(expected)) {
        return false;
      }
      if (snapshot.compareAndSet(current, replacement)) {
        return true;
      }
    }
  }

  /** Publishes recovered state unless the cell already holds a newer durable revision. */
  void recover(@Nonnull IndexLifecycleSnapshot replacement) {
    while (true) {
      var current = snapshot.get();
      if (current == null) {
        throw new IllegalStateException("The lifecycle cell belongs to a retired storage lineage");
      }
      if (!current.buildState().descriptorIdentity()
          .equals(replacement.buildState().descriptorIdentity())) {
        throw new IllegalArgumentException("The lifecycle snapshot belongs to another descriptor");
      }
      if (replacement.recordVersion() < current.recordVersion()
          && replacement.buildState().failure()
              != IndexBuildFailure.INDEX_BUILD_STATE_MISSING) {
        return;
      }
      if (snapshot.compareAndSet(current, replacement)) {
        return;
      }
    }
  }

  void retire() {
    snapshot.set(null);
  }
}
