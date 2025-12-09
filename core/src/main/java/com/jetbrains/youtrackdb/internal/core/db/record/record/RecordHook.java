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
package com.jetbrains.youtrackdb.internal.core.db.record.record;

import javax.annotation.Nonnull;

/**
 * Hook interface to catch all events regarding records.
 *
 * @see RecordHookAbstract
 */
public interface RecordHook {

  enum TYPE {
    READ,

    BEFORE_CREATE,
    AFTER_CREATE,

    BEFORE_UPDATE,
    AFTER_UPDATE,

    BEFORE_DELETE,
    AFTER_DELETE,
  }

  default void onUnregister() {
  }

  void onTrigger(@Nonnull TYPE iType, @Nonnull DBRecord iRecord);
}
