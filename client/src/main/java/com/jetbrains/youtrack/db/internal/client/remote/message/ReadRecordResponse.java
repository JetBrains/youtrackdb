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
package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.StorageRemoteSession;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializer;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetworkV37Client;
import com.jetbrains.youtrack.db.internal.core.storage.RawBuffer;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import java.io.IOException;
import java.util.Set;

public final class ReadRecordResponse implements BinaryResponse {

  private byte recordType;
  private int version;
  private byte[] record;
  private Set<RecordAbstract> recordsToSend;
  private RawBuffer result;

  public ReadRecordResponse() {
  }

  public ReadRecordResponse(
      byte recordType, int version, byte[] record, Set<RecordAbstract> recordsToSend) {
    this.recordType = recordType;
    this.version = version;
    this.record = record;
    this.recordsToSend = recordsToSend;
  }

  public void write(DatabaseSessionInternal session, ChannelDataOutput network,
      int protocolVersion, RecordSerializer serializer)
      throws IOException {
    if (record != null) {
      network.writeByte((byte) 1);
      if (protocolVersion <= ChannelBinaryProtocol.PROTOCOL_VERSION_27) {
        network.writeBytes(record);
        network.writeVersion(version);
        network.writeByte(recordType);
      } else {
        network.writeByte(recordType);
        network.writeVersion(version);
        network.writeBytes(record);
      }
      for (var d : recordsToSend) {
        if (d.getIdentity().isValid()) {
          network.writeByte((byte) 2); // CLIENT CACHE
          // RECORD. IT ISN'T PART OF THE RESULT SET
          MessageHelper.writeRecord(session, network, d, serializer);
        }
      }
    }
    // End of the response
    network.writeByte((byte) 0);
  }

  @Override
  public void read(DatabaseSessionInternal sessionInternal, ChannelDataInput network,
      StorageRemoteSession session) throws IOException {
    var serializer = RecordSerializerNetworkV37Client.INSTANCE;
    if (network.readByte() == 0) {
      return;
    }

    final RawBuffer buffer;
    final var type = network.readByte();
    final var recVersion = network.readVersion();
    final var bytes = network.readBytes();
    buffer = new RawBuffer(bytes, recVersion, type);

    // TODO: This should not be here, move it in a callback or similar
    RecordAbstract record;
    while (network.readByte() == 2) {
      record = (RecordAbstract) MessageHelper.readIdentifiable(sessionInternal, network,
          serializer);

      if (sessionInternal != null && record != null) {
        var cacheRecord = sessionInternal.getLocalCache().findRecord(record.getIdentity());

        if (cacheRecord != record) {
          if (cacheRecord != null) {
            cacheRecord.fromStream(record.toStream());
            cacheRecord.setVersion(record.getVersion());
          } else {
            sessionInternal.getLocalCache().updateRecord(record, sessionInternal);
          }
        }
      }
    }
    result = buffer;
  }

  public byte[] getRecord() {
    return record;
  }

  public RawBuffer getResult() {
    return result;
  }
}
