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

import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.StorageRemoteSession;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializer;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import java.io.IOException;

public class ReloadResponse implements BinaryResponse {

  private String[] collectionNames;
  private int[] collectionIds;

  public ReloadResponse() {
  }

  public ReloadResponse(String[] collectionNames, int[] collectionIds) {
    this.collectionNames = collectionNames;
    this.collectionIds = collectionIds;
  }

  @Override
  public void read(DatabaseSessionInternal db, ChannelDataInput network,
      StorageRemoteSession session) throws IOException {
    final var collections = MessageHelper.readCollectionsArray(network);
    collectionNames = collections.first;
    collectionIds = collections.second;
  }

  public void write(DatabaseSessionInternal session, ChannelDataOutput channel,
      int protocolVersion, RecordSerializer serializer)
      throws IOException {
    MessageHelper.writeCollectionsArray(
        channel, new RawPair<>(collectionNames, collectionIds), protocolVersion);
  }

  public String[] getCollectionNames() {
    return collectionNames;
  }

  public int[] getCollectionIds() {
    return collectionIds;
  }
}
