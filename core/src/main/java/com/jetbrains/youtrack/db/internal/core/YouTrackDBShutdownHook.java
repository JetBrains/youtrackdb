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
package com.jetbrains.youtrack.db.internal.core;

import com.jetbrains.youtrack.db.internal.common.log.LogManager;

public class YouTrackDBShutdownHook extends Thread {

  protected YouTrackDBShutdownHook() {
    try {
      Runtime.getRuntime().addShutdownHook(this);
    } catch (IllegalStateException ignore) {
      // we may be asked to initialize the runtime and install the hook from another shutdown hook
      // during the shutdown
    }
  }

  /**
   * Shutdown YouTrackDB engine.
   */
  @Override
  public void run() {
    YouTrackDBEnginesManager.instance().shutdown();
  }

  public void cancel() {
    try {
      Runtime.getRuntime().removeShutdownHook(this);
    } catch (IllegalStateException ignore) {
    }
  }
}
