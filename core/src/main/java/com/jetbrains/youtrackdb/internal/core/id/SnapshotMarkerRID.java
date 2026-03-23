package com.jetbrains.youtrackdb.internal.core.id;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import javax.annotation.Nonnull;

public record SnapshotMarkerRID(RID identity) implements RID {

  @Override
  public int getCollectionId() {
    return identity.getCollectionId();
  }

  @Override
  public long getCollectionPosition() {
    // Encode: shift+negate so that 0 → -1, 1 → -2, etc.
    return -(identity.getCollectionPosition() + 1);
  }

  @Override
  public boolean isPersistent() {
    return identity.isPersistent();
  }

  @Override
  public boolean isNew() {
    return identity.isNew();
  }

  @Nonnull
  @Override
  public RID getIdentity() {
    return identity;
  }

  @Override
  public int compareTo(Identifiable o) {
    return identity.compareTo(o);
  }

  @Override
  public String toString() {
    return "~" + identity.toString();
  }

}
