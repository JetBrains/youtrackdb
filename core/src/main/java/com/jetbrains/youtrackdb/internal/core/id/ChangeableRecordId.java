package com.jetbrains.youtrackdb.internal.core.id;

import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.serialization.BinaryProtocol;
import com.jetbrains.youtrackdb.internal.core.serialization.MemoryStream;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.StringSerializerHelper;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;

/// This is a thread-safe version of RecordId that supports tracking of its identity changes.
///
/// Though even here the meaning of to be thread safe means that changes of identity will be visible
/// in other threads but not that it can be changed in other threads.
///
/// It **SHOULD NOT** be used except of:
/// 1. Deserialization of newly created records from the server response, as that is done in a
/// separate thread by an asynchronous Netty channel.
/// 2. Passing of RecordId instance to the newly created records, so once they become persistent,
/// their identity will be visible to other threads.
public class ChangeableRecordId extends RecordId implements ChangeableIdentity {
  private static final VarHandle volatileCollectionIdHandle;
  private static final VarHandle volatileCollectionPositionHandle;

  static {
    var lookup = MethodHandles.lookup();
    try {
      volatileCollectionIdHandle = lookup.findVarHandle(RecordId.class, "collectionId", int.class);
      volatileCollectionPositionHandle = lookup.findVarHandle(RecordId.class, "collectionPosition",
          long.class);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }

  }

  private Set<IdentityChangeListener> identityChangeListeners;

  /**
   * Counter for temporal identity of record id till it will not be defined during storage of
   * record.
   */
  private static final AtomicLong tempIdCounter = new AtomicLong();

  /**
   * Temporary identity of record id. It is used to identify record id in memory before it will be
   * stored in a database and will get real identity.
   */
  private final long tempId;

  public ChangeableRecordId(int collectionId, long collectionPosition) {
    volatileCollectionIdHandle.setVolatile(this, collectionId);
    volatileCollectionPositionHandle.setVolatile(this, collectionPosition);

    if (!isPersistent()) {
      tempId = tempIdCounter.getAndIncrement();
    } else {
      tempId = COLLECTION_POS_INVALID;
    }
  }

  public ChangeableRecordId(RecordId recordId) {
    var collectionId = recordId.getCollectionId();
    var collectionPosition = recordId.getCollectionPosition();

    volatileCollectionIdHandle.setVolatile(this, collectionId);
    volatileCollectionPositionHandle.setVolatile(this, collectionPosition);

    if (!recordId.isPersistent()) {
      tempId = tempIdCounter.getAndIncrement();
    } else {
      tempId = COLLECTION_POS_INVALID;
    }
  }

  public ChangeableRecordId() {
    tempId = tempIdCounter.getAndIncrement();
  }

  private ChangeableRecordId(long tempId) {
    this.tempId = tempId;
  }


  @Override
  public void setCollectionId(int collectionId) {
    if (collectionId == getCollectionId()) {
      return;
    }

    checkCollectionLimits(collectionId);

    fireBeforeIdentityChange();

    volatileCollectionIdHandle.setVolatile(this, collectionId);

    fireAfterIdentityChange();
  }

  @Override
  public void setCollectionPosition(long collectionPosition) {
    if (collectionPosition == getCollectionPosition()) {
      return;
    }

    fireBeforeIdentityChange();
    volatileCollectionPositionHandle.setVolatile(this, collectionPosition);

    fireAfterIdentityChange();
  }

  @Override
  public void setCollectionAndPosition(int collectionId, long collectionPosition) {
    if (collectionId == getCollectionId() && collectionPosition == getCollectionPosition()) {
      return;
    }

    checkCollectionLimits(collectionId);

    fireBeforeIdentityChange();

    volatileCollectionIdHandle.setVolatile(this, collectionId);
    volatileCollectionPositionHandle.setVolatile(this, collectionPosition);

    fireAfterIdentityChange();
  }

  @Override
  public int getCollectionId() {
    return (int) volatileCollectionIdHandle.getVolatile(this);
  }

  @Override
  public long getCollectionPosition() {
    return (long) volatileCollectionPositionHandle.getVolatile(this);
  }

  @Override
  public void addIdentityChangeListener(IdentityChangeListener identityChangeListeners) {
    if (!canChangeIdentity()) {
      return;
    }

    if (this.identityChangeListeners == null) {
      this.identityChangeListeners = Collections.newSetFromMap(new WeakHashMap<>());
    }

    this.identityChangeListeners.add(identityChangeListeners);
  }

  @Override
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

    var collectionId = getCollectionId();
    var collectionPosition = getCollectionPosition();

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
    var collectionId = getCollectionId();
    var collectionPosition = getCollectionPosition();

    if (collectionPosition != COLLECTION_POS_INVALID || collectionId != COLLECTION_ID_INVALID) {
      return 31 * collectionId + 103 * (int) collectionPosition;
    }

    return (31 * collectionId + 103 * (int) collectionPosition) + 17 * Long.hashCode(tempId);
  }

  @Override
  public int compareTo(@Nonnull final Identifiable other) {
    if (other == this) {
      return 0;
    }

    var otherIdentity = other.getIdentity();
    final var otherCollectionId = otherIdentity.getCollectionId();
    var collectionId = getCollectionId();
    var collectionPosition = getCollectionPosition();

    if (collectionId == otherCollectionId) {
      final var otherCollectionPos = other.getIdentity().getCollectionPosition();

      if (collectionPosition == otherCollectionPos) {
        if ((collectionId == COLLECTION_ID_INVALID && collectionPosition == COLLECTION_POS_INVALID)
            && otherIdentity instanceof ChangeableRecordId otherRecordId) {
          return Long.compare(tempId, otherRecordId.tempId);
        }

        return 0;
      }

      return Long.compare(collectionPosition, otherCollectionPos);
    } else if (collectionId > otherCollectionId) {
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
    var collectionId = getCollectionId();
    var collectionPosition = getCollectionPosition();

    return collectionId > -1 && collectionPosition > COLLECTION_POS_INVALID;
  }

  @Override
  public boolean isNew() {
    return getCollectionPosition() < 0;
  }

  @Override
  public boolean isTemporary() {
    var collectionId = getCollectionId();
    var collectionPosition = getCollectionPosition();

    return collectionId != -1 && collectionPosition < COLLECTION_POS_INVALID;
  }

  @Override
  public String toString() {
    return generateString(getCollectionId(), getCollectionPosition());
  }

  @Override
  public StringBuilder toString(StringBuilder iBuffer) {
    if (iBuffer == null) {
      iBuffer = new StringBuilder();
    }

    var collectionId = getCollectionId();
    var collectionPosition = getCollectionPosition();

    iBuffer.append(PREFIX);
    iBuffer.append(collectionId);
    iBuffer.append(SEPARATOR);
    iBuffer.append(collectionPosition);
    return iBuffer;
  }

  @Override
  public void toStream(DataOutput out) throws IOException {
    out.writeShort(getCollectionId());
    out.writeLong(getCollectionPosition());
  }

  @Override
  public int toStream(OutputStream iStream) throws IOException {
    final var beginOffset = BinaryProtocol.short2bytes((short) getCollectionId(), iStream);
    BinaryProtocol.long2bytes(getCollectionPosition(), iStream);
    return beginOffset;
  }

  @Override
  public int toStream(MemoryStream iStream) throws IOException {
    final var beginOffset = BinaryProtocol.short2bytes((short) getCollectionId(), iStream);
    BinaryProtocol.long2bytes(getCollectionPosition(), iStream);
    return beginOffset;
  }

  @Override
  public byte[] toStream() {
    final var buffer = new byte[BinaryProtocol.SIZE_SHORT + BinaryProtocol.SIZE_LONG];

    BinaryProtocol.short2bytes((short) getCollectionId(), buffer, 0);
    BinaryProtocol.long2bytes(getCollectionPosition(), buffer, BinaryProtocol.SIZE_SHORT);

    return buffer;
  }

  @Override
  public String next() {
    return generateString(getCollectionId(), getCollectionPosition() + 1);
  }

  @Override
  public void reset() {
    volatileCollectionIdHandle.setVolatile(this, COLLECTION_ID_INVALID);
    volatileCollectionPositionHandle.setVolatile(this, COLLECTION_POS_INVALID);
  }

  @Override
  public void fromStream(DataInput in) throws IOException {
    volatileCollectionIdHandle.setVolatile(this, in.readShort());
    volatileCollectionPositionHandle.setVolatile(this, in.readLong());
  }

  @Override
  public RecordId fromStream(InputStream iStream) throws IOException {
    volatileCollectionIdHandle.setVolatile(this, BinaryProtocol.bytes2short(iStream));
    volatileCollectionPositionHandle.setVolatile(this, BinaryProtocol.bytes2long(iStream));
    return this;
  }

  @Override
  public RecordId fromStream(MemoryStream iStream) {
    volatileCollectionIdHandle.setVolatile(this, iStream.getAsShort());
    volatileCollectionPositionHandle.setVolatile(this, iStream.getAsLong());
    return this;
  }

  @Override
  public RecordId fromStream(byte[] iBuffer) {
    if (iBuffer != null) {
      volatileCollectionIdHandle.setVolatile(this, BinaryProtocol.bytes2short(iBuffer, 0));
      volatileCollectionPositionHandle.setVolatile(this,
          BinaryProtocol.bytes2long(iBuffer, BinaryProtocol.SIZE_SHORT));
    }
    return this;
  }

  @Override
  public void fromString(String iRecordId) {
    if (iRecordId != null) {
      iRecordId = iRecordId.trim();
    }

    if (iRecordId == null || iRecordId.isEmpty()) {
      volatileCollectionIdHandle.setVolatile(this, COLLECTION_ID_INVALID);
      volatileCollectionPositionHandle.setVolatile(this, COLLECTION_POS_INVALID);
      return;
    }

    if (!StringSerializerHelper.contains(iRecordId, SEPARATOR)) {
      throw new IllegalArgumentException(
          "Argument '"
              + iRecordId
              + "' is not a RecordId in form of string. Format must be:"
              + " <collection-id>:<collection-position>");
    }

    final var parts = StringSerializerHelper.split(iRecordId, SEPARATOR, PREFIX);

    if (parts.size() != 2) {
      throw new IllegalArgumentException(
          "Argument received '"
              + iRecordId
              + "' is not a RecordId in form of string. Format must be:"
              + " #<collection-id>:<collection-position>. Example: #3:12");
    }

    volatileCollectionIdHandle.setVolatile(this, Integer.parseInt(parts.getFirst()));
    checkCollectionLimits();

    volatileCollectionPositionHandle.setVolatile(this, Integer.parseInt(parts.get(1)));
  }

  @Override
  public RecordId copy() {
    var collectionId = getCollectionId();
    var collectionPosition = getCollectionPosition();

    if (collectionId == COLLECTION_ID_INVALID && collectionPosition == COLLECTION_POS_INVALID) {
      var recordId = new ChangeableRecordId(tempId);

      volatileCollectionIdHandle.setVolatile(recordId, collectionId);
      volatileCollectionPositionHandle.setVolatile(recordId, collectionPosition);

      return recordId;
    }

    var recordId = new RecordId();
    recordId.collectionId = collectionId;
    recordId.collectionPosition = collectionPosition;

    return recordId;
  }

  public static RecordId deserialize(String ridStr) {
    if (ridStr != null) {
      ridStr = ridStr.trim();
    }

    if (ridStr == null || ridStr.isEmpty()) {
      return new ChangeableRecordId();
    }

    if (!StringSerializerHelper.contains(ridStr, SEPARATOR)) {
      throw new IllegalArgumentException(
          "Argument '"
              + ridStr
              + "' is not a RecordId in form of string. Format must be:"
              + " <collection-id>:<collection-position>");
    }

    final var parts = StringSerializerHelper.split(ridStr, SEPARATOR, PREFIX);

    if (parts.size() != 2) {
      throw new IllegalArgumentException(
          "Argument received '"
              + ridStr
              + "' is not a RecordId in form of string. Format must be:"
              + " #<collection-id>:<collection-position>. Example: #3:12");
    }

    var collectionId = Integer.parseInt(parts.get(0));
    checkCollectionLimits(collectionId);

    var collectionPosition = Long.parseLong(parts.get(1));

    if (collectionPosition < 0) {
      return new ChangeableRecordId(collectionId, collectionPosition);
    }

    return new RecordId(collectionId, collectionPosition);
  }
}
