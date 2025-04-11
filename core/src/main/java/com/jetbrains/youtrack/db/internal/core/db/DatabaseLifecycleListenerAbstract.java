/*
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
package com.jetbrains.youtrack.db.internal.core.db;

import javax.annotation.Nonnull;

/**
 * Abstract Listener Interface to receive callbacks on database usage.
 */
public abstract class DatabaseLifecycleListenerAbstract implements DatabaseLifecycleListener {

  @Override
  public PRIORITY getPriority() {
    return PRIORITY.REGULAR;
  }

  @Override
  public void onCreate(@Nonnull DatabaseSessionInternal session) {
  }

  @Override
  public void onOpen(@Nonnull DatabaseSessionInternal session) {
  }

  @Override
  public void onClose(@Nonnull DatabaseSessionInternal session) {
  }

  @Override
  public void onDrop(@Nonnull DatabaseSessionInternal session) {
  }

}
