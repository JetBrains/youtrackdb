package com.jetbrains.youtrackdb.internal.core.id;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import javax.annotation.Nonnull;

/**
 * Marker RID indicating an index entry that replaced a prior version (either a
 * {@link TombstoneRID} or another live entry). Stored in the B-tree so that the
 * visibility filter can detect "this key was re-inserted or updated" and fall
 * back to the snapshot index for historical state. The wrapped
 * {@link #identity()} is the RID of the new record occupying this key.
 *
 * <p>{@link #getCollectionPosition()} encodes the position as
 * {@code -(position + 1)} so that on-disk serialization can distinguish
 * snapshot markers from live entries.
 */
public record SnapshotMarkerRID(RID identity) implements RID {

  public SnapshotMarkerRID {
    assert identity.getCollectionId() >= 0
        : "SnapshotMarkerRID requires non-negative collectionId: " + identity;
    assert identity.getCollectionPosition() >= 0
        : "SnapshotMarkerRID requires persistent RID (non-negative collectionPosition): "
            + identity;
  }

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
  public int compareTo(@Nonnull Identifiable o) {
    return identity.compareTo(o);
  }

  @Override
  public String toString() {
    return "~" + identity.toString();
  }

}
