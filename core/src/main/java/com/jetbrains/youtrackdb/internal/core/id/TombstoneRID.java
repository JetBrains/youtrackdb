package com.jetbrains.youtrackdb.internal.core.id;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * Marker RID indicating a logically deleted index entry. Stored in the B-tree
 * in place of the original {@link RID} when an entry is removed, so that
 * snapshot readers can distinguish "deleted after my snapshot" from "never
 * existed". The wrapped identity (recoverable via {@link #getIdentity()}) is
 * the RID of the deleted record.
 *
 * <p>{@link #getCollectionId()} encodes the collection ID as {@code -(id + 1)}
 * so that on-disk serialization can distinguish tombstones from live entries.
 *
 * <p>Stores primitive fields directly to avoid an intermediate {@link RecordId}
 * allocation on the hot decode path.
 */
public final class TombstoneRID implements RID {

  private final int collectionId;
  private final long collectionPosition;

  public TombstoneRID(int collectionId, long collectionPosition) {
    assert collectionId >= 0
        : "TombstoneRID requires non-negative collectionId: " + collectionId;
    assert collectionPosition >= 0
        : "TombstoneRID requires non-negative collectionPosition: " + collectionPosition;
    this.collectionId = collectionId;
    this.collectionPosition = collectionPosition;
  }

  /** Wrapping constructor for convenience (existing call sites). */
  public TombstoneRID(RID identity) {
    this(identity.getCollectionId(), identity.getCollectionPosition());
  }

  @Override
  public int getCollectionId() {
    // Encode: shift+negate so that 0 → -1, 1 → -2, etc.
    return -(collectionId + 1);
  }

  @Override
  public long getCollectionPosition() {
    return collectionPosition;
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
    return new RecordId(collectionId, collectionPosition);
  }

  @Override
  public int compareTo(@Nonnull Identifiable o) {
    // Comparison uses unwrapped identity (real RID), not the encoded getters
    // (which negate collectionId for on-disk tombstone detection).
    return getIdentity().compareTo(o);
  }

  @Override
  public String toString() {
    return "-#" + collectionId + ":" + collectionPosition;
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
    return Objects.hash(collectionId, collectionPosition);
  }
}
