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
package com.jetbrains.youtrackdb.internal.core.db;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassImpl;
import javax.annotation.Nonnull;

/**
 * Listener Interface to receive callbacks on database usage.
 */
public interface DatabaseLifecycleListener {

  enum PRIORITY {
    FIRST,
    EARLY,
    REGULAR,
    LATE,
    LAST
  }

  default PRIORITY getPriority() {
    return PRIORITY.LAST;
  }

  default void onCreate(@Nonnull DatabaseSessionInternal session) {
  }

  default void onOpen(@Nonnull DatabaseSessionInternal session) {
  }

  default void onClose(@Nonnull DatabaseSessionInternal session) {
  }

  default void onDrop(@Nonnull DatabaseSessionInternal session) {
  }

  @Deprecated
  default void onCreateClass(DatabaseSessionEmbedded session, SchemaClassImpl iClass) {
  }

  @Deprecated
  default void onDropClass(DatabaseSessionEmbedded session, SchemaClassImpl iClass) {
  }
}
