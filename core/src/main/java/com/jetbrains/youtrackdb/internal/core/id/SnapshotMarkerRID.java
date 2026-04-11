package com.jetbrains.youtrackdb.internal.core.id;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import javax.annotation.Nonnull;

/**
 * Marker RID indicating an index entry that replaced a prior version (either a
 * {@link TombstoneRID} or another live entry). Stored in the B-tree so that the
 * visibility filter can detect "this key was re-inserted or updated" and fall
 * back to the snapshot index for historical state. The identity (recoverable
 * via {@link #getIdentity()}) is the RID of the new record occupying this key.
 *
 * <p>{@link #getCollectionPosition()} encodes the position as
 * {@code -(position + 1)} so that on-disk serialization can distinguish
 * snapshot markers from live entries.
 *
 * <p>Stores primitive fields directly to avoid an intermediate {@link RecordId}
 * allocation on the hot decode path.
 */
public final class SnapshotMarkerRID implements RID {

  private final int collectionId;
  private final long collectionPosition;
  private final RecordId identity;

  public SnapshotMarkerRID(int collectionId, long collectionPosition) {
    assert collectionId >= 0
        : "SnapshotMarkerRID requires non-negative collectionId: " + collectionId;
    assert collectionPosition >= 0
        : "SnapshotMarkerRID requires non-negative collectionPosition: " + collectionPosition;
    this.collectionId = collectionId;
    this.collectionPosition = collectionPosition;
    this.identity = new RecordId(collectionId, collectionPosition);
  }

  /** Wrapping constructor for convenience (existing call sites). */
  public SnapshotMarkerRID(RID identity) {
    this(identity.getCollectionId(), identity.getCollectionPosition());
  }

  @Override
  public int getCollectionId() {
    return collectionId;
  }

  @Override
  public long getCollectionPosition() {
    // Encode: shift+negate so that 0 → -1, 1 → -2, etc.
    return -(collectionPosition + 1);
  }

  @Override
  public boolean isPersistent() {
    return collectionId > -1 && collectionPosition > RID.COLLECTION_POS_INVALID;
  }

  @Override
  public boolean isNew() {
    return collectionPosition < 0;
  }

  @Nonnull
  @Override
  public RID getIdentity() {
    return identity;
  }

  @Override
  public int compareTo(@Nonnull Identifiable o) {
    // Comparison uses unwrapped identity (real RID), not the encoded getters
    // (which negate collectionPosition for on-disk marker detection).
    return identity.compareTo(o);
  }

  @Override
  public String toString() {
    return "~#" + collectionId + ":" + collectionPosition;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) {
      return true;
    }
    if (!(obj instanceof Identifiable identifiable)) {
      return false;
    }
    var other = identifiable.getIdentity();
    return other.getCollectionId() == collectionId
        && other.getCollectionPosition() == collectionPosition;
  }

  @Override
  public int hashCode() {
    // Must match RecordId's record-generated hashCode algorithm (accumulator
    // starts at 0) so that equal objects produce equal hash codes.
    return 31 * Integer.hashCode(collectionId) + Long.hashCode(collectionPosition);
  }
}
