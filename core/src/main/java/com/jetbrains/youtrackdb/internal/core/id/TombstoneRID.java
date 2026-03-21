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
    return -1;
  }

  @Override
  public String toString() {
    return "-" + identity.toString();
  }

  @Override
  public boolean equals(Object o) {
    if (o instanceof TombstoneRID other) {
      return identity.equals(other.identity);
    }
    return false;
  }
}
