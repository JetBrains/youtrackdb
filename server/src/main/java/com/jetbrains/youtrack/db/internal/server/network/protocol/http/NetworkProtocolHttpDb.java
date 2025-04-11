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
package com.jetbrains.youtrack.db.internal.server.network.protocol.http;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.internal.client.binary.BinaryRequestExecutor;
import com.jetbrains.youtrack.db.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrack.db.internal.server.ClientConnection;
import com.jetbrains.youtrack.db.internal.server.YouTrackDBServer;
import com.jetbrains.youtrack.db.internal.server.network.ServerNetworkListener;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.post.ServerCommandPostImportDatabase;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.post.ServerCommandPostUploadSingleFile;
import java.io.IOException;
import java.net.Socket;
import javax.annotation.Nullable;

public class NetworkProtocolHttpDb extends NetworkProtocolHttpAbstract {

  private static final int CURRENT_PROTOCOL_VERSION = 10;

  public NetworkProtocolHttpDb(YouTrackDBServer server) {
    super(server);
  }

  @Override
  public void config(
      final ServerNetworkListener iListener,
      final YouTrackDBServer iServer,
      final Socket iSocket,
      final ContextConfiguration iConfiguration)
      throws IOException {
    server = iServer;
    setName(
        "YouTrackDB HTTP Connection "
            + iSocket.getLocalAddress()
            + ":"
            + iSocket.getLocalPort()
            + "<-"
            + iSocket.getRemoteSocketAddress());

    super.config(iListener, server, iSocket, iConfiguration);
    cmdManager.registerCommand(new ServerCommandPostImportDatabase());
    cmdManager.registerCommand(new ServerCommandPostUploadSingleFile());

    connection.getData().serverInfo =
        iConfiguration.getValueAsString(GlobalConfiguration.NETWORK_HTTP_SERVER_INFO);
  }

  @Override
  public int getVersion() {
    return CURRENT_PROTOCOL_VERSION;
  }

  public String getType() {
    return "http";
  }

  @Nullable
  @Override
  public BinaryRequestExecutor executor(ClientConnection connection) {
    return null;
  }
}
