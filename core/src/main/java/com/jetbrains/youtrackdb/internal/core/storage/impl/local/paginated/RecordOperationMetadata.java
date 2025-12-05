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
package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.storage.collection.PaginatedCollection;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationMetadata;
import java.util.HashSet;
import java.util.Set;

/**
 * This class is used inside of {@link PaginatedCollection} class as container for the records ids
 * which were changed during active atomic operation.
 */
public class RecordOperationMetadata implements AtomicOperationMetadata<Set<RID>> {

  public static final String RID_METADATA_KEY = "collection.record.rid";

  private final Set<RID> rids = new HashSet<>();

  public void addRid(RID rid) {
    rids.add(rid);
  }

  @Override
  public String getKey() {
    return RID_METADATA_KEY;
  }

  @Override
  public Set<RID> getValue() {
    return rids;
  }
}
