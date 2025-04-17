package com.jetbrains.youtrack.db.internal.core.id;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import javax.annotation.Nonnull;

public class ChangeableRecordId extends RecordId implements ChangeableIdentity {

  private Set<IdentityChangeListener> identityChangeListeners;

  /**
   * Counter for temporal identity of record id till it will not be defined during storage of
   * record. This counter is not thread safe because we care only about difference in single thread.
   * Even if number will be duplicated in different threads it does not matter for us. JVM
   * guarantees atomicity arithmetic operations on <code>int</code>s
   */
  private static int tempIdCounter = 0;

  /**
   * Temporary identity of record id. It is used to identify record id in memory before it will be
   * stored in database and will get real identity. If record id would not be comparable we would
   * not need it.
   */
  private int tempId;

  public ChangeableRecordId() {
    tempId = tempIdCounter++;
  }

  public void setCollectionId(int collectionId) {
    if (collectionId == this.collectionId) {
      return;
    }

    checkCollectionLimits(collectionId);

    fireBeforeIdentityChange();
    this.collectionId = collectionId;
    fireAfterIdentityChange();
  }

  public void setCollectionPosition(long collectionPosition) {
    if (collectionPosition == this.collectionPosition) {
      return;
    }

    fireBeforeIdentityChange();
    this.collectionPosition = collectionPosition;
    fireAfterIdentityChange();
  }

  @Override
  public void setCollectionAndPosition(int collectionId, long collectionPosition) {
    if (collectionId == this.collectionId && collectionPosition == this.collectionPosition) {
      return;
    }

    checkCollectionLimits(collectionId);

    fireBeforeIdentityChange();
    this.collectionId = collectionId;
    this.collectionPosition = collectionPosition;
    fireAfterIdentityChange();
  }

  public void addIdentityChangeListener(IdentityChangeListener identityChangeListeners) {
    if (!canChangeIdentity()) {
      return;
    }

    if (this.identityChangeListeners == null) {
      this.identityChangeListeners = Collections.newSetFromMap(new WeakHashMap<>());
    }

    this.identityChangeListeners.add(identityChangeListeners);
  }

  public void removeIdentityChangeListener(IdentityChangeListener identityChangeListener) {
    if (this.identityChangeListeners != null) {
      this.identityChangeListeners.remove(identityChangeListener);

      if (this.identityChangeListeners.isEmpty()) {
        this.identityChangeListeners = null;
      }
    }
  }

  private void fireBeforeIdentityChange() {
    if (this.identityChangeListeners != null) {
      for (var listener : this.identityChangeListeners) {
        listener.onBeforeIdentityChange(this);
      }
    }
  }

  private void fireAfterIdentityChange() {
    if (this.identityChangeListeners != null) {
      for (var listener : this.identityChangeListeners) {
        listener.onAfterIdentityChange(this);
      }
    }
  }

  @Override
  public boolean canChangeIdentity() {
    return !isPersistent();
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null) {
      return false;
    }
    if (!(obj instanceof Identifiable)) {
      return false;
    }
    final var other = (RecordId) ((Identifiable) obj).getIdentity();

    if (collectionId == other.collectionId && collectionPosition == other.collectionPosition) {
      if (collectionId != COLLECTION_ID_INVALID || collectionPosition != COLLECTION_POS_INVALID) {
        return true;
      }

      if (other instanceof ChangeableRecordId otherRecordId) {
        return tempId == otherRecordId.tempId;
      }

      return true;
    }

    return false;
  }

  @Override
  public int hashCode() {
    if (collectionPosition != COLLECTION_POS_INVALID || collectionId != COLLECTION_ID_INVALID) {
      return super.hashCode();
    }

    return super.hashCode() + 17 * tempId;
  }

  public int compareTo(@Nonnull final Identifiable other) {
    if (other == this) {
      return 0;
    }

    var otherIdentity = other.getIdentity();
    final var otherCollectionId = otherIdentity.getCollectionId();
    if (collectionId == otherCollectionId) {
      final var otherCollectionPos = other.getIdentity().getCollectionPosition();

      if (collectionPosition == otherCollectionPos) {
        if ((collectionId == COLLECTION_ID_INVALID && collectionPosition == COLLECTION_POS_INVALID)
            && otherIdentity instanceof ChangeableRecordId otherRecordId) {
          return Integer.compare(tempId, otherRecordId.tempId);
        }

        return 0;
      }

      return Long.compare(collectionPosition, otherCollectionPos);
    } else if (collectionId > otherCollectionId) {
      return 1;
    }

    return -1;
  }

  public RecordId copy() {
    if (collectionId == COLLECTION_ID_INVALID && collectionPosition == COLLECTION_POS_INVALID) {
      var recordId = new ChangeableRecordId();
      recordId.collectionId = collectionId;
      recordId.collectionPosition = collectionPosition;
      recordId.tempId = tempId;

      return recordId;
    }

    var recordId = new RecordId();
    recordId.collectionId = collectionId;
    recordId.collectionPosition = collectionPosition;

    return recordId;
  }
}
