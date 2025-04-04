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

package com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated;

import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.BTreeBasedLinkBag;

public class RidBagDeleteSerializationOperation implements RecordSerializationOperation {

  private final BTreeBasedLinkBag ridBag;

  public RidBagDeleteSerializationOperation(BTreeBasedLinkBag ridBag) {
    this.ridBag = ridBag;
  }

  @Override
  public void execute(
      final AtomicOperation atomicOperation, final AbstractStorage paginatedStorage) {
    paginatedStorage.deleteTreeRidBag(ridBag);
  }
}
