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
package com.jetbrains.youtrack.db.internal.core.id;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.util.PatternConst;
import com.jetbrains.youtrack.db.internal.core.serialization.BinaryProtocol;
import com.jetbrains.youtrack.db.internal.core.serialization.MemoryStream;
import com.jetbrains.youtrack.db.internal.core.serialization.SerializableStream;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serial;
import javax.annotation.Nonnull;

public class RecordId implements RID, SerializableStream {
  @Serial
  private static final long serialVersionUID = 247070594054408657L;
  // INT TO AVOID JVM PENALTY, BUT IT'S STORED AS SHORT
  protected int clusterId = CLUSTER_ID_INVALID;
  protected long clusterPosition = CLUSTER_POS_INVALID;

  protected RecordId() {
  }

  public RecordId(final int clusterId, final long position) {
    this.clusterId = clusterId;
    checkClusterLimits();
    clusterPosition = position;
  }

  public RecordId(final int iClusterIdId) {
    clusterId = iClusterIdId;
    checkClusterLimits();
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
    clusterId = parentRid.getClusterId();
    clusterPosition = parentRid.getClusterPosition();
  }

  public static String generateString(final int iClusterId, final long iPosition) {
    return String.valueOf(PREFIX) + iClusterId + SEPARATOR + iPosition;
  }

  public static boolean isValid(final long pos) {
    return pos != CLUSTER_POS_INVALID;
  }

  public static boolean isPersistent(final long pos) {
    return pos > CLUSTER_POS_INVALID;
  }

  public static boolean isNew(final long pos) {
    return pos < 0;
  }

  public static boolean isTemporary(final long clusterPosition) {
    return clusterPosition < CLUSTER_POS_INVALID;
  }

  public static boolean isA(final String iString) {
    return PatternConst.PATTERN_RID.matcher(iString).matches();
  }

  public void reset() {
    clusterId = CLUSTER_ID_INVALID;
    clusterPosition = CLUSTER_POS_INVALID;
  }

  public boolean isValid() {
    return clusterPosition != CLUSTER_POS_INVALID;
  }

  public boolean isPersistent() {
    return clusterId > -1 && clusterPosition > CLUSTER_POS_INVALID;
  }

  public boolean isNew() {
    return clusterPosition < 0;
  }

  public boolean isTemporary() {
    return clusterId != -1 && clusterPosition < CLUSTER_POS_INVALID;
  }

  @Override
  public String toString() {
    return generateString(clusterId, clusterPosition);
  }

  public StringBuilder toString(StringBuilder iBuffer) {
    if (iBuffer == null) {
      iBuffer = new StringBuilder();
    }

    iBuffer.append(PREFIX);
    iBuffer.append(clusterId);
    iBuffer.append(SEPARATOR);
    iBuffer.append(clusterPosition);
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

    return clusterId == other.clusterId && clusterPosition == other.clusterPosition;
  }

  @Override
  public int hashCode() {
    return 31 * clusterId + 103 * (int) clusterPosition;
  }

  public int compareTo(@Nonnull final Identifiable other) {
    if (other == this) {
      return 0;
    }

    var otherIdentity = other.getIdentity();
    final var otherClusterId = otherIdentity.getClusterId();
    if (clusterId == otherClusterId) {
      final var otherClusterPos = other.getIdentity().getClusterPosition();
      return Long.compare(clusterPosition, otherClusterPos);
    } else if (clusterId > otherClusterId) {
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
    return new RecordId(clusterId, clusterPosition);
  }

  public void toStream(final DataOutput out) throws IOException {
    out.writeShort(clusterId);
    out.writeLong(clusterPosition);
  }

  public void fromStream(final DataInput in) throws IOException {
    clusterId = in.readShort();
    clusterPosition = in.readLong();
  }

  public RecordId fromStream(final InputStream iStream) throws IOException {
    clusterId = BinaryProtocol.bytes2short(iStream);
    clusterPosition = BinaryProtocol.bytes2long(iStream);
    return this;
  }

  public RecordId fromStream(final MemoryStream iStream) {
    clusterId = iStream.getAsShort();
    clusterPosition = iStream.getAsLong();
    return this;
  }

  public RecordId fromStream(final byte[] iBuffer) {
    if (iBuffer != null) {
      clusterId = BinaryProtocol.bytes2short(iBuffer, 0);
      clusterPosition = BinaryProtocol.bytes2long(iBuffer, BinaryProtocol.SIZE_SHORT);
    }
    return this;
  }

  public int toStream(final OutputStream iStream) throws IOException {
    final var beginOffset = BinaryProtocol.short2bytes((short) clusterId, iStream);
    BinaryProtocol.long2bytes(clusterPosition, iStream);
    return beginOffset;
  }

  public int toStream(final MemoryStream iStream) throws IOException {
    final var beginOffset = BinaryProtocol.short2bytes((short) clusterId, iStream);
    BinaryProtocol.long2bytes(clusterPosition, iStream);
    return beginOffset;
  }

  public byte[] toStream() {
    final var buffer = new byte[BinaryProtocol.SIZE_SHORT + BinaryProtocol.SIZE_LONG];

    BinaryProtocol.short2bytes((short) clusterId, buffer, 0);
    BinaryProtocol.long2bytes(clusterPosition, buffer, BinaryProtocol.SIZE_SHORT);

    return buffer;
  }

  public int getClusterId() {
    return clusterId;
  }

  public long getClusterPosition() {
    return clusterPosition;
  }

  public void fromString(String iRecordId) {
    if (iRecordId != null) {
      iRecordId = iRecordId.trim();
    }

    if (iRecordId == null || iRecordId.isEmpty()) {
      clusterId = CLUSTER_ID_INVALID;
      clusterPosition = CLUSTER_POS_INVALID;
      return;
    }

    if (!StringSerializerHelper.contains(iRecordId, SEPARATOR)) {
      throw new IllegalArgumentException(
          "Argument '"
              + iRecordId
              + "' is not a RecordId in form of string. Format must be:"
              + " <cluster-id>:<cluster-position>");
    }

    final var parts = StringSerializerHelper.split(iRecordId, SEPARATOR, PREFIX);

    if (parts.size() != 2) {
      throw new IllegalArgumentException(
          "Argument received '"
              + iRecordId
              + "' is not a RecordId in form of string. Format must be:"
              + " #<cluster-id>:<cluster-position>. Example: #3:12");
    }

    clusterId = Integer.parseInt(parts.get(0));
    checkClusterLimits();
    clusterPosition = Long.parseLong(parts.get(1));
  }

  public String next() {
    return generateString(clusterId, clusterPosition + 1);
  }


  @Nonnull
  public RID getIdentity() {
    return this;
  }

  @Nonnull
  public <T extends DBRecord> T getRecord(@Nonnull DatabaseSession session) {
    if (!isValid()) {
      throw new RecordNotFoundException(session.getDatabaseName(), this);
    }

    return session.load(this);
  }

  private void checkClusterLimits() {
    checkClusterLimits(clusterId);
  }

  protected static void checkClusterLimits(int clusterId) {
    if (clusterId < -2) {
      throw new DatabaseException(
          "RecordId cannot support negative cluster id. Found: " + clusterId);
    }

    if (clusterId > CLUSTER_MAX) {
      throw new DatabaseException(
          "RecordId cannot support cluster id major than 32767. Found: " + clusterId);
    }
  }

  public void setClusterId(int clusterId) {
    checkClusterLimits(clusterId);

    this.clusterId = clusterId;
  }

  public void setClusterAndPosition(int clusterId, long clusterPosition) {
    checkClusterLimits(clusterId);

    this.clusterId = clusterId;
    this.clusterPosition = clusterPosition;
  }

  public void setClusterPosition(long clusterPosition) {
    this.clusterPosition = clusterPosition;
  }

  public static void serialize(RID id, DataOutput output) throws IOException {
    if (id == null) {
      output.writeInt(-2);
      output.writeLong(-2);
    } else {
      output.writeInt(id.getClusterId());
      output.writeLong(id.getClusterPosition());
    }
  }

  public static RecordId deserialize(DataInput input) throws IOException {
    var cluster = input.readInt();
    var pos = input.readLong();
    if (cluster == -2 && pos == -2) {
      return null;
    }
    return new RecordId(cluster, pos);
  }
}
