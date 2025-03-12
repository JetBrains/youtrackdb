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

public class HigherPhysicalPositionsRequest
    implements BinaryRequest<HigherPhysicalPositionsResponse> {

  private int clusterId;
  private PhysicalPosition clusterPosition;
  private int limit;

  public HigherPhysicalPositionsRequest(int iClusterId, PhysicalPosition iClusterPosition,
      int limit) {
    this.clusterId = iClusterId;
    this.clusterPosition = iClusterPosition;
    this.limit = limit;
  }

  public HigherPhysicalPositionsRequest() {
  }

  @Override
  public void write(DatabaseSessionInternal databaseSession, ChannelDataOutput network,
      StorageRemoteSession session) throws IOException {
    network.writeInt(clusterId);
    network.writeLong(clusterPosition.clusterPosition);
    network.writeInt(limit);
  }

  public void read(DatabaseSessionInternal databaseSession, ChannelDataInput channel,
      int protocolVersion,
      RecordSerializerNetwork serializer)
      throws IOException {
    clusterId = channel.readInt();
    clusterPosition = new PhysicalPosition(channel.readLong());
    limit = channel.readInt();
  }

  @Override
  public byte getCommand() {
    return ChannelBinaryProtocol.REQUEST_POSITIONS_HIGHER;
  }

  @Override
  public String getDescription() {
    return "Retrieve higher positions";
  }

  public int getClusterId() {
    return clusterId;
  }

  public PhysicalPosition getClusterPosition() {
    return clusterPosition;
  }

  public int getLimit() {
    return limit;
  }

  @Override
  public HigherPhysicalPositionsResponse createResponse() {
    return new HigherPhysicalPositionsResponse();
  }

  @Override
  public BinaryResponse execute(BinaryRequestExecutor executor) {
    return executor.executeHigherPosition(this);
  }
}
