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
package com.jetbrains.youtrackdb.internal.core.record;

import com.jetbrains.youtrackdb.api.record.DBRecord;

/**
 * Listener interface to catch all the record events.
 *
 * @since 2.2
 */
@Deprecated
public interface RecordListener {

  enum EVENT {
    CLEAR,
    RESET,
    MARSHALL,
    UNMARSHALL,
    UNLOAD,
    IDENTITY_CHANGED
  }

  void onEvent(DBRecord record, EVENT iEvent);
}
