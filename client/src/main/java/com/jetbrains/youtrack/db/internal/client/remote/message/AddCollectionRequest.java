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
import com.jetbrains.youtrack.db.internal.client.remote.StorageRemoteSession;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetwork;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import java.io.IOException;

public final class AddCollectionRequest implements BinaryRequest<AddCollectionResponse> {

  private int requestedId = -1;
  private String collectionName;

  public AddCollectionRequest(int iRequestedId, String iCollectionName) {
    this.requestedId = iRequestedId;
    this.collectionName = iCollectionName;
  }

  public AddCollectionRequest() {
  }

  @Override
  public void write(DatabaseSessionInternal databaseSession, ChannelDataOutput network,
      StorageRemoteSession session) throws IOException {
    network.writeString(collectionName);
    network.writeShort((short) requestedId);
  }

  @Override
  public void read(DatabaseSessionInternal databaseSession, ChannelDataInput channel,
      int protocolVersion,
      RecordSerializerNetwork serializer)
      throws IOException {
    var type = "";
    if (protocolVersion < 24) {
      type = channel.readString();
    }

    this.collectionName = channel.readString();

    if (protocolVersion < 24 || type.equalsIgnoreCase("PHYSICAL"))
    // Skipping location is just for compatibility
    {
      channel.readString();
    }

    if (protocolVersion < 24)
    // Skipping data segment name is just for compatibility
    {
      channel.readString();
    }

    this.requestedId = channel.readShort();
  }

  @Override
  public byte getCommand() {
    return ChannelBinaryProtocol.REQUEST_COLLECTION_ADD;
  }

  @Override
  public String getDescription() {
    return "Add collection";
  }

  public String getCollectionName() {
    return collectionName;
  }

  public int getRequestedId() {
    return requestedId;
  }

  @Override
  public AddCollectionResponse createResponse() {
    return new AddCollectionResponse();
  }

  @Override
  public BinaryResponse execute(BinaryRequestExecutor executor) {
    return executor.executeAddCollection(this);
  }
}
