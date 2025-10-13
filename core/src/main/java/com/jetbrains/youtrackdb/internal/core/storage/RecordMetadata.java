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

package com.jetbrains.youtrackdb.internal.core.storage;

import com.jetbrains.youtrackdb.api.record.RID;

/**
 * @since 18.12.12
 */
public final class RecordMetadata {

  private final RID recordId;
  private final long recordVersion;

  public RecordMetadata(RID recordId, long recordVersion) {
    this.recordId = recordId;
    this.recordVersion = recordVersion;
  }

  public RID getRecordId() {
    return recordId;
  }

  public long getVersion() {
    return recordVersion;
  }
}
