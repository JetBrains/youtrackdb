/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.id;

import com.jetbrains.youtrackdb.api.exception.DatabaseException;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.common.util.PatternConst;
import com.jetbrains.youtrackdb.internal.core.serialization.BinaryProtocol;
import com.jetbrains.youtrackdb.internal.core.serialization.MemoryStream;
import com.jetbrains.youtrackdb.internal.core.serialization.SerializableStream;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.StringSerializerHelper;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serial;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class RecordId implements RID, SerializableStream {

  private static final AtomicLong TEMP_ID_GENERATOR = new AtomicLong(0);

  @Serial
  private static final long serialVersionUID = 247070594054408657L;
  // INT TO AVOID JVM PENALTY, BUT IT'S STORED AS SHORT
  protected int collectionId = COLLECTION_ID_INVALID;
  protected long collectionPosition = COLLECTION_POS_INVALID;

  public RecordId() {
  }

  public RecordId(final int collectionId, final long position) {
    this.collectionId = collectionId;
    checkCollectionLimits();
    collectionPosition = position;
  }

  public RecordId(final int iCollectionIdId) {
    collectionId = iCollectionIdId;
    checkCollectionLimits();
  }

  public RecordId(final String iRecordId) {
    fromString(iRecordId);
  }

  /**
   * Copy constructor.
   *
   * @param parentRid Source object
   */
  public RecordId(final RID parentRid) {
    collectionId = parentRid.getCollectionId();
    collectionPosition = parentRid.getCollectionPosition();
  }

  public static String generateString(final int iCollectionId, final long iPosition) {
    return String.valueOf(PREFIX) + iCollectionId + SEPARATOR + iPosition;
  }

  public static boolean isValid(final long pos) {
    return pos != COLLECTION_POS_INVALID;
  }

  public static boolean isPersistent(final long pos) {
    return pos > COLLECTION_POS_INVALID;
  }

  public static boolean isNew(final long pos) {
    return pos < 0;
  }

  public static boolean isTemporary(final long collectionPosition) {
    return collectionPosition < COLLECTION_POS_INVALID;
  }

  public static boolean isA(final String iString) {
    return PatternConst.PATTERN_RID.matcher(iString).matches();
  }

  public void reset() {
    collectionId = COLLECTION_ID_INVALID;
    collectionPosition = COLLECTION_POS_INVALID;
  }

  public boolean isValidPosition() {
    return collectionPosition != COLLECTION_POS_INVALID;
  }

  @Override
  public boolean isPersistent() {
    return collectionId > -1 && collectionPosition > COLLECTION_POS_INVALID;
  }

  @Override
  public boolean isNew() {
    return collectionPosition < 0;
  }

  public boolean isTemporary() {
    return collectionId != -1 && collectionPosition < COLLECTION_POS_INVALID;
  }

  @Override
  public String toString() {
    return generateString(collectionId, collectionPosition);
  }

  public StringBuilder toString(StringBuilder iBuffer) {
    if (iBuffer == null) {
      iBuffer = new StringBuilder();
    }

    iBuffer.append(PREFIX);
    iBuffer.append(collectionId);
    iBuffer.append(SEPARATOR);
    iBuffer.append(collectionPosition);
    return iBuffer;
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

    return collectionId == other.collectionId && collectionPosition == other.collectionPosition;
  }

  @Override
  public int hashCode() {
    return 31 * collectionId + 103 * (int) collectionPosition;
  }

  @Override
  public int compareTo(@Nonnull final Identifiable other) {
    if (other == this) {
      return 0;
    }

    var otherIdentity = other.getIdentity();
    final var otherCollectionId = otherIdentity.getCollectionId();
    if (collectionId == otherCollectionId) {
      final var otherCollectionPos = other.getIdentity().getCollectionPosition();
      return Long.compare(collectionPosition, otherCollectionPos);
    } else if (collectionId > otherCollectionId) {
      return 1;
    }

    return -1;
  }

  public int compare(final Identifiable obj1, final Identifiable obj2) {
    if (obj1 == obj2) {
      return 0;
    }

    if (obj1 != null) {
      return obj1.compareTo(obj2);
    }

    return -1;
  }

  public RecordId copy() {
    return new RecordId(collectionId, collectionPosition);
  }

  public void toStream(final DataOutput out) throws IOException {
    out.writeShort(collectionId);
    out.writeLong(collectionPosition);
  }

  public void fromStream(final DataInput in) throws IOException {
    collectionId = in.readShort();
    collectionPosition = in.readLong();
  }

  public RecordId fromStream(final InputStream iStream) throws IOException {
    collectionId = BinaryProtocol.bytes2short(iStream);
    collectionPosition = BinaryProtocol.bytes2long(iStream);
    return this;
  }

  public RecordId fromStream(final MemoryStream iStream) {
    collectionId = iStream.getAsShort();
    collectionPosition = iStream.getAsLong();
    return this;
  }

  @Override
  public RecordId fromStream(final byte[] iBuffer) {
    if (iBuffer != null) {
      collectionId = BinaryProtocol.bytes2short(iBuffer, 0);
      collectionPosition = BinaryProtocol.bytes2long(iBuffer, BinaryProtocol.SIZE_SHORT);
    }
    return this;
  }

  public int toStream(final OutputStream iStream) throws IOException {
    final var beginOffset = BinaryProtocol.short2bytes((short) collectionId, iStream);
    BinaryProtocol.long2bytes(collectionPosition, iStream);
    return beginOffset;
  }

  public int toStream(final MemoryStream iStream) throws IOException {
    final var beginOffset = BinaryProtocol.short2bytes((short) collectionId, iStream);
    BinaryProtocol.long2bytes(collectionPosition, iStream);
    return beginOffset;
  }

  @Override
  public byte[] toStream() {
    final var buffer = new byte[BinaryProtocol.SIZE_SHORT + BinaryProtocol.SIZE_LONG];

    BinaryProtocol.short2bytes((short) collectionId, buffer, 0);
    BinaryProtocol.long2bytes(collectionPosition, buffer, BinaryProtocol.SIZE_SHORT);

    return buffer;
  }

  @Override
  public int getCollectionId() {
    return collectionId;
  }

  @Override
  public long getCollectionPosition() {
    return collectionPosition;
  }

  public void fromString(String iRecordId) {
    if (iRecordId != null) {
      iRecordId = iRecordId.trim();
    }

    if (iRecordId == null || iRecordId.isEmpty()) {
      collectionId = COLLECTION_ID_INVALID;
      collectionPosition = COLLECTION_POS_INVALID;
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

    collectionId = Integer.parseInt(parts.get(0));
    checkCollectionLimits();
    collectionPosition = Long.parseLong(parts.get(1));
  }

  public String next() {
    return generateString(collectionId, collectionPosition + 1);
  }


  @Override
  @Nonnull
  public RID getIdentity() {
    return this;
  }

  private void checkCollectionLimits() {
    checkCollectionLimits(collectionId);
  }

  protected static void checkCollectionLimits(int collectionId) {
    if (collectionId < -2) {
      throw new DatabaseException(
          "RecordId cannot support negative collection id. Found: " + collectionId);
    }

    if (collectionId > COLLECTION_MAX) {
      throw new DatabaseException(
          "RecordId cannot support collection id major than 32767. Found: " + collectionId);
    }
  }

  public void setCollectionId(int collectionId) {
    checkCollectionLimits(collectionId);

    this.collectionId = collectionId;
  }

  public void setCollectionAndPosition(int collectionId, long collectionPosition) {
    checkCollectionLimits(collectionId);

    this.collectionId = collectionId;
    this.collectionPosition = collectionPosition;
  }

  public void setCollectionPosition(long collectionPosition) {
    this.collectionPosition = collectionPosition;
  }

  public static void serialize(RID id, DataOutput output) throws IOException {
    if (id == null) {
      output.writeInt(-2);
      output.writeLong(-2);
    } else {
      output.writeInt(id.getCollectionId());
      output.writeLong(id.getCollectionPosition());
    }
  }

  @Nullable
  public static RecordId deserialize(DataInput input) throws IOException {
    var collection = input.readInt();
    var pos = input.readLong();
    if (collection == -2 && pos == -2) {
      return null;
    }
    return new RecordId(collection, pos);
  }

  public static RecordId tempRecordId() {
    return new RecordId(COLLECTION_ID_INVALID, TEMP_ID_GENERATOR.decrementAndGet());
  }
}
