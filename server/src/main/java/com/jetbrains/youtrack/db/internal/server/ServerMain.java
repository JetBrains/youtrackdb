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
package com.jetbrains.youtrack.db.internal.server;

import com.jetbrains.youtrack.db.internal.common.log.LogManager;

public class ServerMain {

  private static YouTrackDBServer instance;

  public static YouTrackDBServer create() throws Exception {
    instance = new YouTrackDBServer();
    return instance;
  }

  public static YouTrackDBServer create(boolean shutdownEngineOnExit) throws Exception {
    instance = new YouTrackDBServer(shutdownEngineOnExit);
    return instance;
  }

  public static YouTrackDBServer server() {
    return instance;
  }

  public static void main(final String[] args) throws Exception {
    // STARTS YouTrackDB IN A NON DAEMON THREAD TO PREVENT EXIT
    final var t =
        new Thread() {
          @Override
          public void run() {
            try {
              instance = ServerMain.create();
              instance.startup().activate();
              instance.waitForShutdown();
            } catch (Exception e) {
              LogManager.instance().error(this, "Error during server execution", e);
            }
          }
        };

    t.setDaemon(false);

    t.start();
    t.join();
    System.exit(1);
  }
}
