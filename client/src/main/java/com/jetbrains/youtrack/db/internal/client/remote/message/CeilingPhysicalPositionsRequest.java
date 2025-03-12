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
import com.jetbrains.youtrack.db.internal.core.storage.PhysicalPosition;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import java.io.IOException;

public class CeilingPhysicalPositionsRequest
    implements BinaryRequest<CeilingPhysicalPositionsResponse> {

  private int clusterId;
  private PhysicalPosition physicalPosition;
  private int limit;

  public CeilingPhysicalPositionsRequest(int clusterId, PhysicalPosition physicalPosition,
      int limit) {
    this.clusterId = clusterId;
    this.physicalPosition = physicalPosition;
    this.limit = limit;
  }

  public CeilingPhysicalPositionsRequest() {
  }

  @Override
  public void write(DatabaseSessionInternal databaseSession, ChannelDataOutput network,
      StorageRemoteSession session) throws IOException {
    network.writeInt(clusterId);
    network.writeLong(physicalPosition.clusterPosition);
    network.writeInt(limit);
  }

  public void read(DatabaseSessionInternal databaseSession, ChannelDataInput channel,
      int protocolVersion,
      RecordSerializerNetwork serializer)
      throws IOException {
    this.clusterId = channel.readInt();
    this.physicalPosition = new PhysicalPosition(channel.readLong());
    this.limit = channel.readInt();
  }

  public PhysicalPosition getPhysicalPosition() {
    return physicalPosition;
  }

  @Override
  public String getDescription() {
    return "Retrieve ceiling positions";
  }

  @Override
  public byte getCommand() {
    return ChannelBinaryProtocol.REQUEST_POSITIONS_CEILING;
  }

  public int getClusterId() {
    return clusterId;
  }

  public int getLimit() {
    return limit;
  }

  @Override
  public CeilingPhysicalPositionsResponse createResponse() {
    return new CeilingPhysicalPositionsResponse();
  }

  @Override
  public BinaryResponse execute(BinaryRequestExecutor executor) {
    return executor.executeCeilingPosition(this);
  }
}
