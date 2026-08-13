package com.jetbrains.youtrackdb.internal.core.index.engine;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Process-local engine identity, including a generation that cannot fit in the packed id. */
public final class IndexEngineReference {

  private final int slot;
  private final int apiVersion;
  private final long generation;
  @Nullable private volatile RID ownerDescriptorIdentity;

  public IndexEngineReference(int slot, int apiVersion, long generation) {
    if (slot < 0) {
      throw new IllegalArgumentException("Engine slot must be non-negative");
    }
    if (generation <= 0) {
      throw new IllegalArgumentException("Engine generation must be positive");
    }

    this.slot = slot;
    this.apiVersion = apiVersion;
    this.generation = generation;
  }

  public int slot() {
    return slot;
  }

  public int apiVersion() {
    return apiVersion;
  }

  public long generation() {
    return generation;
  }

  @Nullable public RID ownerDescriptorIdentity() {
    return ownerDescriptorIdentity;
  }

  public synchronized void bindOwner(@Nonnull RID ownerDescriptorIdentity) {
    if (this.ownerDescriptorIdentity == null) {
      this.ownerDescriptorIdentity = ownerDescriptorIdentity;
      return;
    }

    if (!this.ownerDescriptorIdentity.equals(ownerDescriptorIdentity)) {
      throw new IllegalStateException(
          "Index engine owner is already bound to " + this.ownerDescriptorIdentity
              + " and cannot be rebound to " + ownerDescriptorIdentity);
    }
  }
}
