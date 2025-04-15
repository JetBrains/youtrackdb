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

import com.jetbrains.youtrack.db.internal.server.ClientConnection;
import com.jetbrains.youtrack.db.internal.server.YouTrackDBServer;
import com.jetbrains.youtrack.db.internal.server.network.protocol.NetworkProtocol;
import java.util.Collection;

public class ServerPluginHelper {

  public static void invokeHandlerCallbackOnClientConnection(
      final YouTrackDBServer iServer, final ClientConnection connection) {
    final var plugins = iServer.getPlugins();
    if (plugins != null) {
      for (var plugin : plugins) {
        final var pluginInstance = plugin.getInstance();
        if (pluginInstance != null) {
          plugin.getInstance().onClientConnection(connection);
        }
      }
    }
  }

  public static void invokeHandlerCallbackOnClientDisconnection(
      final YouTrackDBServer iServer, final ClientConnection connection) {
    final var plugins = iServer.getPlugins();
    if (plugins != null) {
      for (var plugin : plugins) {
        final var pluginInstance = plugin.getInstance();
        if (pluginInstance != null) {
          pluginInstance.onClientDisconnection(connection);
        }
      }
    }
  }

  public static void invokeHandlerCallbackOnBeforeClientRequest(
      final YouTrackDBServer iServer, final ClientConnection connection, final byte iRequestType) {
    final var plugins = iServer.getPlugins();
    if (plugins != null) {
      for (var plugin : plugins) {
        final var pluginInstance = plugin.getInstance();
        if (pluginInstance != null) {
          pluginInstance.onBeforeClientRequest(connection, iRequestType);
        }
      }
    }
  }

  public static void invokeHandlerCallbackOnAfterClientRequest(
      final YouTrackDBServer iServer, final ClientConnection connection, final byte iRequestType) {
    final var plugins = iServer.getPlugins();
    if (plugins != null) {
      for (var plugin : plugins) {
        final var pluginInstance = plugin.getInstance();
        if (pluginInstance != null) {
          pluginInstance.onAfterClientRequest(connection, iRequestType);
        }
      }
    }
  }

  public static void invokeHandlerCallbackOnClientError(
      final YouTrackDBServer iServer, final ClientConnection connection,
      final Throwable iThrowable) {
    final var plugins = iServer.getPlugins();
    if (plugins != null) {
      for (var plugin : plugins) {
        final var pluginInstance = plugin.getInstance();
        if (pluginInstance != null) {
          pluginInstance.onClientError(connection, iThrowable);
        }
      }
    }
  }

  public static void invokeHandlerCallbackOnSocketAccepted(
      final YouTrackDBServer iServer, final NetworkProtocol networkProtocol) {
    final var plugins = iServer.getPlugins();
    if (plugins != null) {
      for (var plugin : plugins) {
        final var pluginInstance = plugin.getInstance();
        if (pluginInstance != null) {
          pluginInstance.onSocketAccepted(networkProtocol);
        }
      }
    }
  }

  public static void invokeHandlerCallbackOnSocketDestroyed(
      final YouTrackDBServer iServer, final NetworkProtocol networkProtocol) {
    final var plugins = iServer.getPlugins();
    if (plugins != null) {
      for (var plugin : plugins) {
        final var pluginInstance = plugin.getInstance();
        if (pluginInstance != null) {
          pluginInstance.onSocketDestroyed(networkProtocol);
        }
      }
    }
  }
}
