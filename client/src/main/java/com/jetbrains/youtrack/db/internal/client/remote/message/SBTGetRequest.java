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
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetwork;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.LinkBagPointer;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import java.io.IOException;

public class SBTGetRequest implements BinaryRequest<SBTGetResponse> {

  private LinkBagPointer collectionPointer;
  private byte[] keyStream;

  public SBTGetRequest(LinkBagPointer collectionPointer, byte[] keyStream) {
    this.collectionPointer = collectionPointer;
    this.keyStream = keyStream;
  }

  public SBTGetRequest() {
  }

  @Override
  public void write(DatabaseSessionInternal databaseSession, ChannelDataOutput network,
      StorageRemoteSession session) throws IOException {
    CollectionNetworkSerializer.INSTANCE.writeCollectionPointer(network, collectionPointer);
    network.writeBytes(keyStream);
  }

  public void read(DatabaseSessionInternal databaseSession, ChannelDataInput channel,
      int protocolVersion,
      RecordSerializerNetwork serializer)
      throws IOException {
    this.collectionPointer = CollectionNetworkSerializer.INSTANCE.readCollectionPointer(channel);
    this.keyStream = channel.readBytes();
  }

  @Override
  public byte getCommand() {
    return ChannelBinaryProtocol.REQUEST_SBTREE_BONSAI_GET;
  }

  @Override
  public String getDescription() {
    return "SB-Tree bonsai get";
  }

  public LinkBagPointer getCollectionPointer() {
    return collectionPointer;
  }

  public byte[] getKeyStream() {
    return keyStream;
  }

  @Override
  public SBTGetResponse createResponse() {
    return new SBTGetResponse();
  }

  @Override
  public BinaryResponse execute(BinaryRequestExecutor executor) {
    return executor.executeSBTGet(this);
  }
}
