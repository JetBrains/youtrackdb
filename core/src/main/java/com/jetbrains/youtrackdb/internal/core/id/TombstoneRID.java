package com.jetbrains.youtrackdb.internal.core.id;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import javax.annotation.Nonnull;

public record TombstoneRID(RID identity) implements RID {

  @Override
  public int getCollectionId() {
    return -identity.getCollectionId();
  }

  @Override
  public long getCollectionPosition() {
    return identity.getCollectionPosition();
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
    return "-" + identity.toString();
  }

}
