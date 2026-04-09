package com.jetbrains.youtrackdb.internal.core.id;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import javax.annotation.Nonnull;

/**
 * Marker RID indicating a logically deleted index entry. Stored in the B-tree
 * in place of the original {@link RID} when an entry is removed, so that
 * snapshot readers can distinguish "deleted after my snapshot" from "never
 * existed". The wrapped {@link #identity()} is the RID of the deleted record.
 *
 * <p>{@link #getCollectionId()} encodes the collection ID as {@code -(id + 1)}
 * so that on-disk serialization can distinguish tombstones from live entries.
 */
public record TombstoneRID(RID identity) implements RID {

  public TombstoneRID {
    assert identity.getCollectionId() >= 0
        : "TombstoneRID requires non-negative collectionId: " + identity;
    assert identity.getCollectionPosition() >= 0
        : "TombstoneRID requires persistent RID (non-negative collectionPosition): " + identity;
  }

  @Override
  public int getCollectionId() {
    // Encode: shift+negate so that 0 → -1, 1 → -2, etc.
    return -(identity.getCollectionId() + 1);
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
  public int compareTo(@Nonnull Identifiable o) {
    return identity.compareTo(o);
  }

  @Override
  public String toString() {
    return "-" + identity.toString();
  }

}
