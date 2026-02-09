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
package com.jetbrains.youtrackdb.internal.core;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;

/**
 * Abstract implementation of YouTrackDBListener interface.
 */
public abstract class YouTrackDBListenerAbstract
    implements YouTrackDBListener, YouTrackDBStartupListener, YouTrackDBShutdownListener {

  @Override
  public void onStartup() {
  }

  @Override
  public void onStorageRegistered(AbstractStorage iStorage) {
  }

  @Override
  public void onStorageUnregistered(AbstractStorage iStorage) {
  }

  @Override
  public void onShutdown() {
  }
}
