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

package com.jetbrains.youtrackdb.internal.core.conflict;

import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Auto merges new record with the existent. Collections are also merged, item by item.
 */
public class AutoMergeRecordConflictStrategy extends VersionRecordConflictStrategy {

  public static final String NAME = "automerge";

  @Override
  public byte[] onUpdate(
      Storage storage,
      byte iRecordType,
      final RecordId rid,
      final int iRecordVersion,
      final byte[] iRecordContent,
      final AtomicInteger iDatabaseVersion) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getName() {
    return NAME;
  }
}
