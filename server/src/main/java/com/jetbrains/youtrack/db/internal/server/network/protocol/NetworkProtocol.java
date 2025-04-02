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
package com.jetbrains.youtrack.db.internal.server.network.protocol;

import com.jetbrains.youtrack.db.internal.client.binary.BinaryRequestExecutor;
import com.jetbrains.youtrack.db.internal.common.thread.SoftThread;
import com.jetbrains.youtrack.db.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrack.db.internal.enterprise.channel.SocketChannel;
import com.jetbrains.youtrack.db.internal.server.ClientConnection;
import com.jetbrains.youtrack.db.internal.server.YouTrackDBServer;
import com.jetbrains.youtrack.db.internal.server.network.ServerNetworkListener;
import java.io.IOException;
import java.net.Socket;
import javax.annotation.Nullable;

public abstract class NetworkProtocol extends SoftThread {

  protected YouTrackDBServer server;

  public NetworkProtocol(final ThreadGroup group, final String name) {
    super(group, name);
    setDumpExceptions(false);
  }

  public abstract void config(
      final ServerNetworkListener iListener,
      final YouTrackDBServer iServer,
      final Socket iSocket,
      ContextConfiguration iConfiguration)
      throws IOException;

  public abstract String getType();

  public abstract int getVersion();

  public abstract SocketChannel getChannel();

  @Nullable
  public String getListeningAddress() {
    final var c = getChannel();
    if (c != null) {
      return c.socket.getLocalAddress().getHostAddress();
    }
    return null;
  }

  public YouTrackDBServer getServer() {
    return server;
  }

  public abstract BinaryRequestExecutor executor(ClientConnection connection);
}
