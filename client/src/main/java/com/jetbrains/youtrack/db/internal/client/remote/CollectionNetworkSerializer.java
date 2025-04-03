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

package com.jetbrains.youtrack.db.internal.client.remote;

import com.jetbrains.youtrack.db.internal.core.storage.ridbag.LinkBagPointer;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.ridbagbtree.RidBagBucketPointer;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import java.io.IOException;

public class CollectionNetworkSerializer {

  public static final CollectionNetworkSerializer INSTANCE = new CollectionNetworkSerializer();

  public CollectionNetworkSerializer() {
  }

  public LinkBagPointer readCollectionPointer(ChannelDataInput client)
      throws IOException {
    final var fileId = client.readLong();
    final var rootPointer = readBonsaiBucketPointer(client);
    return new LinkBagPointer(fileId, rootPointer);
  }

  private RidBagBucketPointer readBonsaiBucketPointer(ChannelDataInput client)
      throws IOException {
    var pageIndex = client.readLong();
    var pageOffset = client.readInt();
    return new RidBagBucketPointer(pageIndex, pageOffset);
  }

  public void writeCollectionPointer(
      ChannelDataOutput client, LinkBagPointer treePointer) throws IOException {
    client.writeLong(treePointer.getFileId());
    client.writeLong(treePointer.getLinkBagId().getPageIndex());
    client.writeInt(treePointer.getLinkBagId().getPageOffset());
  }
}
