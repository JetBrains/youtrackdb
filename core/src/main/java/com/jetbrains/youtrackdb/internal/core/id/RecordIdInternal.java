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
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.common.util.PatternConst;
import com.jetbrains.youtrackdb.internal.core.serialization.BinaryProtocol;
import com.jetbrains.youtrackdb.internal.core.serialization.MemoryStream;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.StringSerializerHelper;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public sealed interface RecordIdInternal extends RID permits ChangeableRecordId, ContextualRecordId,
    RecordId {
  static String generateString(final int iCollectionId, final long iPosition) {
    return String.valueOf(PREFIX) + iCollectionId + SEPARATOR + iPosition;
  }

  static boolean isValid(final long pos) {
    return pos != COLLECTION_POS_INVALID;
  }

  static boolean isPersistent(final long pos) {
    return pos > COLLECTION_POS_INVALID;
  }

  static boolean isNew(final long pos) {
    return pos < 0;
  }

  static boolean isA(final String iString) {
    return PatternConst.PATTERN_RID.matcher(iString).matches();
  }


  boolean isValidPosition();

  @Override
  boolean isPersistent();

  @Override
  boolean isNew();

  boolean isTemporary();

  @Override
  String toString();

  StringBuilder toString(StringBuilder stringBuilder);

  RecordIdInternal copy();

  void toStream(final DataOutput out) throws IOException;

  static RecordIdInternal fromStream(final DataInput in) throws IOException {
    var collectionId = in.readShort();
    var collectionPosition = in.readLong();
    if (collectionId < 0) {
      return new ChangeableRecordId(collectionId, collectionPosition);
    }

    return new RecordId(collectionId, collectionPosition);
  }

  static RecordIdInternal fromStream(final InputStream iStream) throws IOException {
    var collectionId = BinaryProtocol.bytes2short(iStream);
    var collectionPosition = BinaryProtocol.bytes2long(iStream);
    if (collectionId < 0) {
      return new ChangeableRecordId(collectionId, collectionPosition);
    }

    return new RecordId(collectionId, collectionPosition);

  }

  static RecordIdInternal fromStream(final MemoryStream iStream) {
    var collectionId = iStream.getAsShort();
    var collectionPosition = iStream.getAsLong();
    if (collectionId < 0) {
      return new ChangeableRecordId(collectionId, collectionPosition);
    }

    return new RecordId(collectionId, collectionPosition);
  }


  int toStream(final OutputStream iStream) throws IOException;

  int toStream(final MemoryStream iStream) throws IOException;

  byte[] toStream();

  @Override
  int getCollectionId();

  @Override
  long getCollectionPosition();

  static RecordIdInternal fromString(String iRecordId, boolean changeable) {
    if (iRecordId != null) {
      iRecordId = iRecordId.trim();
    }

    if (iRecordId == null || iRecordId.isEmpty()) {
      return new ChangeableRecordId();
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

    var collectionId = Integer.parseInt(parts.get(0));
    checkCollectionLimits(collectionId);
    var collectionPosition = Long.parseLong(parts.get(1));

    if (changeable && collectionPosition < 0) {
      return new ChangeableRecordId(collectionId, collectionPosition);
    }

    return new RecordId(collectionId, collectionPosition);
  }

  String next();


  @Override
  @Nonnull
  default RID getIdentity() {
    return this;
  }

  static void checkCollectionLimits(int collectionId) {
    if (collectionId < -2) {
      throw new DatabaseException(
          "RecordId cannot support negative collection id. Found: " + collectionId);
    }

    if (collectionId > COLLECTION_MAX) {
      throw new DatabaseException(
          "RecordId cannot support collection id major than 32767. Found: " + collectionId);
    }
  }


  static void serialize(RID id, DataOutput output) throws IOException {
    if (id == null) {
      output.writeInt(-2);
      output.writeLong(-2);
    } else {
      output.writeInt(id.getCollectionId());
      output.writeLong(id.getCollectionPosition());
    }
  }

  @Nullable
  static RecordIdInternal deserialize(DataInput input) throws IOException {
    var collection = input.readInt();
    var pos = input.readLong();
    if (collection == -2 && pos == -2) {
      return null;
    }

    if (collection < 0) {
      return new ChangeableRecordId(collection, pos);
    }

    return new RecordId(collection, pos);
  }
}
