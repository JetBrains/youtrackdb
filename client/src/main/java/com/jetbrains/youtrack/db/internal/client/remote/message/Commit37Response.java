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

import com.jetbrains.youtrack.db.internal.client.remote.StorageRemoteSession;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializer;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.BonsaiCollectionPointer;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

public final class Commit37Response extends BeginTransactionResponse {

  private Map<UUID, BonsaiCollectionPointer> collectionChanges;

  public Commit37Response(
      long txId, Map<RecordId, RecordId> updatedToOldRecordIdMap,
      Map<UUID, BonsaiCollectionPointer> collectionChanges, DatabaseSessionInternal session) {
    super(txId, updatedToOldRecordIdMap, Collections.emptyList(), session);

    this.collectionChanges = collectionChanges;
  }

  public Commit37Response() {
  }

  @Override
  public void write(DatabaseSessionInternal session, ChannelDataOutput channel,
      int protocolVersion, RecordSerializer serializer)
      throws IOException {

    super.write(session, channel, protocolVersion, serializer);
    MessageHelper.writeCollectionChanges(channel, collectionChanges);
  }

  @Override
  public void read(DatabaseSessionInternal db, ChannelDataInput network,
      StorageRemoteSession session) throws IOException {

    super.read(db, network, session);
    collectionChanges = MessageHelper.readCollectionChanges(network);
  }

  public Map<UUID, BonsaiCollectionPointer> getCollectionChanges() {
    return collectionChanges;
  }
}
