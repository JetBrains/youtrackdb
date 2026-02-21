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
package com.jetbrains.youtrackdb.internal.server;

import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;

public class ServerShutdownHook extends Thread {

  private final YouTrackDBServer server;

  protected ServerShutdownHook(final YouTrackDBServer server) {
    this.server = server;
    YouTrackDBEnginesManager.instance().removeShutdownHook();
    Runtime.getRuntime().addShutdownHook(this);
  }

  /**
   * Catch the JVM exit and assure to shutdown the Youtrackdb Server.
   */
  @Override
  public void run() {
    if (server != null) {
      if (!server.shutdown()) {
        // ALREADY IN SHUTDOWN, WAIT FOR IT
        server.waitForShutdown();
      }
    }
  }

  public void cancel() {
    try {
      Runtime.getRuntime().removeShutdownHook(this);
    } catch (IllegalStateException e) {
      // Expected when JVM is already shutting down
    }
  }
}
