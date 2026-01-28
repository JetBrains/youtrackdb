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
package com.jetbrains.youtrackdb.internal.core.storage;

import com.jetbrains.youtrackdb.internal.core.exception.SerializationException;
import com.jetbrains.youtrackdb.internal.core.serialization.BinaryProtocol;
import com.jetbrains.youtrackdb.internal.core.serialization.SerializableStream;
import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

public class PhysicalPosition implements SerializableStream, Externalizable {

  private static final int binarySize =
      BinaryProtocol.SIZE_LONG // collectionPosition
          + BinaryProtocol.SIZE_BYTE // recordType
          + BinaryProtocol.SIZE_LONG // recordVersion
          + BinaryProtocol.SIZE_INT; // recordSize
  public long collectionPosition;
  public byte recordType;
  public long recordVersion = 0;
  public int recordSize;

  public PhysicalPosition() {
  }

  public PhysicalPosition(final long iCollectionPosition) {
    collectionPosition = iCollectionPosition;
  }

  public PhysicalPosition(final byte iRecordType) {
    recordType = iRecordType;
  }

  public PhysicalPosition(final long iCollectionPosition, final long iVersion) {
    collectionPosition = iCollectionPosition;
    recordVersion = iVersion;
  }

  private void copyTo(final PhysicalPosition iDest) {
    iDest.collectionPosition = collectionPosition;
    iDest.recordType = recordType;
    iDest.recordVersion = recordVersion;
    iDest.recordSize = recordSize;
  }

  public void copyFrom(final PhysicalPosition iSource) {
    iSource.copyTo(this);
  }

  @Override
  public String toString() {
    return "rid(?:"
        + collectionPosition
        + ") record(type:"
        + recordType
        + " size:"
        + recordSize
        + " v:"
        + recordVersion
        + ")";
  }

  @Override
  public SerializableStream fromStream(final byte[] iStream) throws SerializationException {
    var pos = 0;

    collectionPosition = BinaryProtocol.bytes2long(iStream);
    pos += BinaryProtocol.SIZE_LONG;

    recordType = iStream[pos];
    pos += BinaryProtocol.SIZE_BYTE;

    recordSize = BinaryProtocol.bytes2int(iStream, pos);
    pos += BinaryProtocol.SIZE_INT;

    recordVersion = BinaryProtocol.bytes2long(iStream, pos);

    return this;
  }

  @Override
  public byte[] toStream() throws SerializationException {
    final var buffer = new byte[binarySize];
    var pos = 0;

    BinaryProtocol.long2bytes(collectionPosition, buffer, pos);
    pos += BinaryProtocol.SIZE_LONG;

    buffer[pos] = recordType;
    pos += BinaryProtocol.SIZE_BYTE;

    BinaryProtocol.int2bytes(recordSize, buffer, pos);
    pos += BinaryProtocol.SIZE_INT;

    BinaryProtocol.long2bytes(recordVersion, buffer, pos);
    return buffer;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == null || !(obj instanceof PhysicalPosition other)) {
      return false;
    }

    return collectionPosition == other.collectionPosition
        && recordType == other.recordType
        && recordVersion == other.recordVersion
        && recordSize == other.recordSize;
  }

  @Override
  public int hashCode() {
    var result = (int) (31 * collectionPosition);
    result = 31 * result + (int) recordType;
    result = 31 * result + (int) recordVersion;
    result = 31 * result + recordSize;
    return result;
  }

  @Override
  public void writeExternal(final ObjectOutput out) throws IOException {
    out.writeLong(collectionPosition);
    out.writeByte(recordType);
    out.writeInt(recordSize);
    out.writeLong(recordVersion);
  }

  @Override
  public void readExternal(final ObjectInput in) throws IOException, ClassNotFoundException {
    collectionPosition = in.readLong();
    recordType = in.readByte();
    recordSize = in.readInt();
    recordVersion = in.readLong();
  }
}
