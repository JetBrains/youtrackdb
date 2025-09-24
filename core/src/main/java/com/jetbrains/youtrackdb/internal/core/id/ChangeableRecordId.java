package com.jetbrains.youtrackdb.internal.core.id;

import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.core.serialization.MemoryStream;
import java.io.DataOutput;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/// This is a thread-safe version of RecordId that supports tracking of its identity changes.
///
/// Though the meaning of to be thread safe in this context means that changes of identity will be
/// visible in other threads but not that instances of this class can be changed in several threads
/// at once.
///
/// It **SHOULD NOT** generally be used except of:
/// 1. Deserialization of newly created records from the server response, as such deserialization is done in a
/// separate thread by an asynchronous Netty channel.
/// 2. Passing of RecordId instance to the newly created records, so once they become persistent,
/// their identity will be visible to other threads.
public final class ChangeableRecordId implements ChangeableIdentity, RecordIdInternal {

  @Nullable
  private ArrayList<WeakReference<IdentityChangeListener>> identityChangeListeners;

  /// Counter for temporal identity of the new record id till it will not be defined during storage
  /// of record.
  private static final AtomicLong tempIdCounter = new AtomicLong();

  /// Temporary identity of record id. It is used to identify record id before it will be stored in
  /// a database and will get real identity.
  private final long tempId;

  private final AtomicReference<RecordId> recordIdAtomicReference;
  private final ReentrantLock listenersLock = new ReentrantLock();

  public ChangeableRecordId(int collectionId, long collectionPosition) {
    recordIdAtomicReference = new AtomicReference<>(
        new RecordId(collectionId, collectionPosition));

    if (!isPersistent()) {
      tempId = tempIdCounter.getAndIncrement();
    } else {
      tempId = COLLECTION_POS_INVALID;
    }
  }

  public ChangeableRecordId(RecordIdInternal recordId) {
    var collectionId = recordId.getCollectionId();
    var collectionPosition = recordId.getCollectionPosition();

    recordIdAtomicReference = new AtomicReference<>(
        new RecordId(collectionId, collectionPosition));

    if (!recordId.isPersistent()) {
      tempId = tempIdCounter.getAndIncrement();
    } else {
      tempId = COLLECTION_POS_INVALID;
    }
  }

  public ChangeableRecordId() {
    tempId = tempIdCounter.getAndIncrement();

    recordIdAtomicReference = new AtomicReference<>(
        new RecordId(RID.COLLECTION_ID_INVALID, RID.COLLECTION_POS_INVALID));
  }

  private ChangeableRecordId(long tempId, int collectionId, long collectionPosition) {
    this.tempId = tempId;

    recordIdAtomicReference = new AtomicReference<>(
        new RecordId(collectionId, collectionPosition));
  }


  public void setCollectionId(int collectionId) {
    var oldRecordId = recordIdAtomicReference.get();

    if (collectionId == oldRecordId.collectionPosition()) {
      return;
    }

    RecordIdInternal.checkCollectionLimits(collectionId);
    fireBeforeIdentityChange();

    var result = recordIdAtomicReference.compareAndSet(oldRecordId,
        new RecordId(collectionId, oldRecordId.collectionPosition()));

    fireAfterIdentityChange();
    if (!result) {
      throw new IllegalStateException("Record id was changed concurrently");
    }
  }


  public void setCollectionPosition(long collectionPosition) {
    var oldRecordId = recordIdAtomicReference.get();
    if (collectionPosition == oldRecordId.collectionPosition()) {
      return;
    }

    fireBeforeIdentityChange();
    var result = recordIdAtomicReference.compareAndSet(oldRecordId,
        new RecordId(oldRecordId.collectionId(), collectionPosition));

    fireAfterIdentityChange();
    if (!result) {
      throw new IllegalStateException("Record id was changed concurrently");
    }
  }


  public void setCollectionAndPosition(int collectionId, long collectionPosition) {
    var oldRecordId = recordIdAtomicReference.get();
    if (collectionId == oldRecordId.collectionId()
        && collectionPosition == oldRecordId.collectionPosition()) {
      return;
    }

    RecordIdInternal.checkCollectionLimits(collectionId);

    fireBeforeIdentityChange();

    var result = recordIdAtomicReference.compareAndSet(oldRecordId,
        new RecordId(collectionId, collectionPosition));

    fireAfterIdentityChange();

    if (!result) {
      throw new IllegalStateException("Record id was changed concurrently");
    }
  }

  @Override
  public int getCollectionId() {
    return recordIdAtomicReference.get().collectionId();
  }

  @Override
  public long getCollectionPosition() {
    return recordIdAtomicReference.get().collectionPosition();
  }

  @Override
  public void addIdentityChangeListener(IdentityChangeListener identityChangeListener) {
    if (!canChangeIdentity()) {
      return;
    }

    listenersLock.lock();
    try {
      if (this.identityChangeListeners == null) {
        this.identityChangeListeners = new ArrayList<>();
      }

      this.identityChangeListeners.add(new WeakReference<>(identityChangeListener));
    } finally {
      listenersLock.unlock();
    }

  }

  @Override
  public void removeIdentityChangeListener(IdentityChangeListener identityChangeListener) {
    listenersLock.lock();
    try {
      if (identityChangeListeners == null) {
        return;
      }

      this.identityChangeListeners.removeIf(ref -> ref.get() == identityChangeListener);
    } finally {
      listenersLock.unlock();
    }
  }

  private void fireBeforeIdentityChange() {
    listenersLock.lock();
    try {
      if (this.identityChangeListeners == null) {
        return;
      }

      for (var listenerRef : this.identityChangeListeners) {
        var listener = listenerRef.get();
        if (listener != null) {
          listener.onBeforeIdentityChange(this);
        }
      }
    } finally {
      listenersLock.unlock();
    }

  }

  private void fireAfterIdentityChange() {
    listenersLock.lock();
    try {
      if (this.identityChangeListeners == null) {
        return;
      }

      for (var listenerRef : this.identityChangeListeners) {
        var listener = listenerRef.get();

        if (listener != null) {
          listener.onAfterIdentityChange(this);
        }
      }
    } finally {
      listenersLock.unlock();
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

    final var other = (RecordIdInternal) ((Identifiable) obj).getIdentity();

    var immutableRecordId = recordIdAtomicReference.get();

    if (immutableRecordId.collectionId() == other.getCollectionId()
        && immutableRecordId.collectionPosition() == other.getCollectionPosition()) {
      if (immutableRecordId.collectionId() != COLLECTION_ID_INVALID
          || immutableRecordId.collectionPosition() != COLLECTION_POS_INVALID) {
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
    var immutableRecordId = recordIdAtomicReference.get();

    if (immutableRecordId.collectionPosition() != COLLECTION_POS_INVALID
        || immutableRecordId.collectionId() != COLLECTION_ID_INVALID) {
      return immutableRecordId.hashCode();
    }

    return
        immutableRecordId.hashCode() + 17 * Long.hashCode(tempId);
  }

  @Override
  public int compareTo(@Nonnull final Identifiable other) {
    if (other == this) {
      return 0;
    }

    var immutableRecordId = recordIdAtomicReference.get();
    var otherIdentity = other.getIdentity();
    final var otherCollectionId = otherIdentity.getCollectionId();

    if (immutableRecordId.collectionId() == otherCollectionId) {
      final var otherCollectionPos = other.getIdentity().getCollectionPosition();

      if (immutableRecordId.collectionPosition() == otherCollectionPos) {
        if ((immutableRecordId.collectionId() == COLLECTION_ID_INVALID
            && immutableRecordId.collectionPosition() == COLLECTION_POS_INVALID)
            && otherIdentity instanceof ChangeableRecordId otherRecordId) {
          return Long.compare(tempId, otherRecordId.tempId);
        }

        return 0;
      }

      return Long.compare(immutableRecordId.collectionPosition(), otherCollectionPos);
    } else if (immutableRecordId.collectionId() > otherCollectionId) {
      return 1;
    }

    return -1;
  }

  @Override
  public boolean isValidPosition() {
    return getCollectionPosition() != COLLECTION_POS_INVALID;
  }

  @Override
  public boolean isPersistent() {
    var immutableRecordId = recordIdAtomicReference.get();
    return immutableRecordId.collectionId() > -1
        && immutableRecordId.collectionPosition() > COLLECTION_POS_INVALID;
  }

  @Override
  public boolean isNew() {
    return getCollectionPosition() < 0;
  }

  @Override
  public boolean isTemporary() {
    var immutableRecordId = recordIdAtomicReference.get();
    return immutableRecordId.collectionId() != -1
        && immutableRecordId.collectionPosition() < COLLECTION_POS_INVALID;
  }

  @Override
  public String toString() {
    var immutableRecordId = recordIdAtomicReference.get();
    return RecordIdInternal.generateString(immutableRecordId.collectionId(),
        immutableRecordId.collectionPosition());
  }

  @Override
  public StringBuilder toString(StringBuilder stringBuilder) {
    var immutableRecordId = recordIdAtomicReference.get();
    immutableRecordId.toString(stringBuilder);
    return stringBuilder;
  }

  @Override
  public void toStream(DataOutput out) throws IOException {
    var immutableRecordId = recordIdAtomicReference.get();
    immutableRecordId.toStream(out);
  }

  @Override
  public int toStream(OutputStream iStream) throws IOException {
    var immutableRecordId = recordIdAtomicReference.get();
    return immutableRecordId.toStream(iStream);
  }

  @Override
  public int toStream(MemoryStream iStream) throws IOException {
    var immutableRecordId = recordIdAtomicReference.get();
    return immutableRecordId.toStream(iStream);
  }

  @Override
  public byte[] toStream() {
    var immutableRecordId = recordIdAtomicReference.get();
    return immutableRecordId.toStream();
  }

  @Override
  public String next() {
    var immutableRecordId = recordIdAtomicReference.get();
    return immutableRecordId.next();
  }

  @Override
  public RecordIdInternal copy() {
    var immutableRecordId = recordIdAtomicReference.get();

    if (immutableRecordId.collectionId() == COLLECTION_ID_INVALID
        || immutableRecordId.collectionPosition() == COLLECTION_POS_INVALID) {
      return new ChangeableRecordId(tempId, immutableRecordId.collectionId(),
          immutableRecordId.collectionPosition());
    }

    return immutableRecordId;
  }
}
