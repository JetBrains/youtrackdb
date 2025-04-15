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
package com.jetbrains.youtrack.db.internal.server.plugin;

import com.jetbrains.youtrack.db.internal.common.util.Service;
import com.jetbrains.youtrack.db.internal.server.ClientConnection;
import com.jetbrains.youtrack.db.internal.server.YouTrackDBServer;
import com.jetbrains.youtrack.db.internal.server.network.protocol.NetworkProtocol;
import com.jetbrains.youtrack.db.internal.tools.config.ServerParameterConfiguration;

/**
 * Server handler interface. Used when configured in the server configuration.
 */
public interface ServerPlugin extends Service {

  /**
   * Callback invoked when a client connection begins.
   */
  void onClientConnection(ClientConnection iConnection);

  /**
   * Callback invoked when a client connection ends.
   */
  void onClientDisconnection(ClientConnection iConnection);

  /**
   * Callback invoked before a client request is processed.
   */
  void onBeforeClientRequest(ClientConnection iConnection, byte iRequestType);

  /**
   * Callback invoked after a client request is processed.
   */
  void onAfterClientRequest(ClientConnection iConnection, byte iRequestType);

  /**
   * Callback invoked when a client connection has errors.
   *
   * @param iThrowable Throwable instance received
   */
  void onClientError(ClientConnection iConnection, Throwable iThrowable);

  /**
   * Configures the handler. Called at startup.
   */
  void config(YouTrackDBServer youTrackDBServer, ServerParameterConfiguration[] iParams);

  default void onSocketAccepted(NetworkProtocol protocol) {
  }

  default void onSocketDestroyed(NetworkProtocol protocol) {
  }

  void sendShutdown();

  Object getContent(final String iURL);
}
