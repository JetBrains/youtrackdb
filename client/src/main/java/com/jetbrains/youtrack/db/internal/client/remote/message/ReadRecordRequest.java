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
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetwork;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import java.io.IOException;

public final class ReadRecordRequest implements BinaryRequest<ReadRecordResponse> {

  private boolean ignoreCache;
  private RecordId rid;
  private String fetchPlan;
  private boolean loadTumbstone;

  public ReadRecordRequest(
      boolean iIgnoreCache, RecordId iRid, String iFetchPlan, boolean iLoadTumbstone) {
    this.ignoreCache = iIgnoreCache;
    this.rid = iRid;
    this.fetchPlan = iFetchPlan;
    this.loadTumbstone = iLoadTumbstone;
  }

  public ReadRecordRequest() {
  }

  @Override
  public void write(DatabaseSessionInternal databaseSession, ChannelDataOutput network,
      StorageRemoteSession session) throws IOException {
    network.writeRID(rid);
    network.writeString(fetchPlan != null ? fetchPlan : "");
    network.writeByte((byte) (ignoreCache ? 1 : 0));
    network.writeByte((byte) (loadTumbstone ? 1 : 0));
  }

  public void read(DatabaseSessionInternal databaseSession, ChannelDataInput channel,
      int protocolVersion,
      RecordSerializerNetwork serializer)
      throws IOException {
    rid = channel.readRID();
    fetchPlan = channel.readString();
    ignoreCache = channel.readByte() != 0;
    loadTumbstone = channel.readByte() != 0;
  }

  @Override
  public byte getCommand() {
    return ChannelBinaryProtocol.REQUEST_RECORD_LOAD;
  }

  @Override
  public String getDescription() {
    return "Load record";
  }

  public RecordId getRid() {
    return rid;
  }

  public String getFetchPlan() {
    return fetchPlan;
  }

  public boolean isIgnoreCache() {
    return ignoreCache;
  }

  public boolean isLoadTumbstone() {
    return loadTumbstone;
  }

  @Override
  public ReadRecordResponse createResponse() {
    return new ReadRecordResponse();
  }

  @Override
  public BinaryResponse execute(BinaryRequestExecutor executor) {
    return executor.executeReadRecord(this);
  }
}
