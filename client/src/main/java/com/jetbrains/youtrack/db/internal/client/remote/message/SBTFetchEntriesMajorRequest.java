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

import com.jetbrains.youtrack.db.internal.client.binary.BinaryRequestExecutor;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryRequest;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.CollectionNetworkSerializer;
import com.jetbrains.youtrack.db.internal.client.remote.StorageRemoteSession;
import com.jetbrains.youtrack.db.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetwork;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.LinkBagPointer;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import java.io.IOException;

public class SBTFetchEntriesMajorRequest<K, V>
    implements BinaryRequest<SBTFetchEntriesMajorResponse<K, V>> {

  private boolean inclusive;
  private byte[] keyStream;
  private LinkBagPointer pointer;
  private int pageSize;
  private BinarySerializer<K> keySerializer;
  private BinarySerializer<V> valueSerializer;

  public SBTFetchEntriesMajorRequest(
      boolean inclusive,
      byte[] keyStream,
      LinkBagPointer pointer,
      BinarySerializer<K> keySerializer,
      BinarySerializer<V> valueSerializer) {
    this.inclusive = inclusive;
    this.keyStream = keyStream;
    this.pointer = pointer;
    this.keySerializer = keySerializer;
    this.valueSerializer = valueSerializer;
  }

  public SBTFetchEntriesMajorRequest() {
  }

  @Override
  public void write(DatabaseSessionInternal databaseSession, ChannelDataOutput network,
      StorageRemoteSession session) throws IOException {
    CollectionNetworkSerializer.writeCollectionPointer(network, pointer);
    network.writeBytes(keyStream);
    network.writeBoolean(inclusive);
    network.writeInt(128);
  }

  @Override
  public void read(DatabaseSessionInternal databaseSession, ChannelDataInput channel,
      int protocolVersion,
      RecordSerializerNetwork serializer)
      throws IOException {
    this.pointer = CollectionNetworkSerializer.readCollectionPointer(channel);
    this.keyStream = channel.readBytes();
    this.inclusive = channel.readBoolean();
    if (protocolVersion >= 21) {
      this.pageSize = channel.readInt();
    } else {
      this.pageSize = 128;
    }
  }

  @Override
  public byte getCommand() {
    return ChannelBinaryProtocol.REQUEST_SBTREE_BONSAI_GET_ENTRIES_MAJOR;
  }

  @Override
  public String getDescription() {
    return "SB-Tree bonsai get values major";
  }

  public byte[] getKeyStream() {
    return keyStream;
  }

  public LinkBagPointer getPointer() {
    return pointer;
  }

  public boolean isInclusive() {
    return inclusive;
  }

  public int getPageSize() {
    return pageSize;
  }

  @Override
  public SBTFetchEntriesMajorResponse<K, V> createResponse() {
    return new SBTFetchEntriesMajorResponse<>(keySerializer, valueSerializer);
  }

  @Override
  public BinaryResponse execute(BinaryRequestExecutor executor) {
    return executor.executeSBTFetchEntriesMajor(this);
  }
}
