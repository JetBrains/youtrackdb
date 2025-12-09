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
package com.jetbrains.youtrackdb.internal.server.plugin;

import com.jetbrains.youtrackdb.internal.common.util.Service;
import com.jetbrains.youtrackdb.internal.server.YouTrackDBServer;
import com.jetbrains.youtrackdb.internal.server.config.ServerParameterConfiguration;

/**
 * Server handler interface. Used when configured in the server configuration.
 */
public interface ServerPlugin extends Service {

  /**
   * Configures the handler. Called at startup.
   */
  void config(YouTrackDBServer youTrackDBServer, ServerParameterConfiguration[] iParams) throws Exception;

  void sendShutdown();
}
