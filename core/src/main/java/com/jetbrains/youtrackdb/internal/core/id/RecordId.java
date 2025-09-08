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

import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.core.serialization.BinaryProtocol;
import com.jetbrains.youtrackdb.internal.core.serialization.MemoryStream;
import java.io.DataOutput;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import javax.annotation.Nonnull;

public record RecordId(int collectionId, long collectionPosition) implements
    RecordIdInternal,
    Serializable {

  public RecordId {
    RecordIdInternal.checkCollectionLimits(collectionId);
  }

  public RecordId(final RID rid) {
    this(rid.getCollectionId(), rid.getCollectionPosition());
  }

  @Override
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

  @Override
  public boolean isTemporary() {
    return collectionId != -1 && collectionPosition < COLLECTION_POS_INVALID;
  }

  @Override
  public StringBuilder toString(StringBuilder stringBuilder) {
    if (stringBuilder == null) {
      stringBuilder = new StringBuilder();
    }

    stringBuilder.append(PREFIX);
    stringBuilder.append(collectionId);
    stringBuilder.append(SEPARATOR);
    stringBuilder.append(collectionPosition);

    return stringBuilder;
  }

  @Override
  public RecordIdInternal copy() {
    return this;
  }

  @Override
  public void toStream(DataOutput out) throws IOException {
    out.writeShort(collectionId);
    out.writeLong(collectionPosition);
  }

  @Override
  public int toStream(OutputStream iStream) throws IOException {
    final var beginOffset = BinaryProtocol.short2bytes((short) collectionId, iStream);
    BinaryProtocol.long2bytes(collectionPosition, iStream);
    return beginOffset;
  }

  @Override
  public int toStream(MemoryStream iStream) throws IOException {
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

  @Override
  public String next() {
    return RecordIdInternal.generateString(collectionId, collectionPosition + 1);
  }

  @Override
  public int compareTo(@Nonnull Identifiable other) {
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

  @Override
  public boolean equals(Object obj) {
    if (obj == this) {
      return true;
    }
    if (!(obj instanceof Identifiable)) {
      return false;
    }

    final var other = (RecordIdInternal) ((Identifiable) obj).getIdentity();
    return other.getCollectionId() == collectionId
        && other.getCollectionPosition() == collectionPosition;
  }

  @Override
  @Nonnull
  public String toString() {
    return RecordIdInternal.generateString(collectionId, collectionPosition);
  }
}
