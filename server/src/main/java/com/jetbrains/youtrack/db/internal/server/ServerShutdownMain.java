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

import com.jetbrains.youtrack.db.internal.client.binary.SocketChannelBinaryAsynchClient;
import com.jetbrains.youtrack.db.internal.client.remote.message.ShutdownRequest;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.NetworkProtocolException;
import com.jetbrains.youtrack.db.internal.server.network.ServerNetworkListener;
import com.jetbrains.youtrack.db.internal.tools.config.ServerConfiguration;
import java.io.IOException;
import java.util.Arrays;

/**
 * Sends a shutdown command to the server.
 */
public class ServerShutdownMain {

  public String networkAddress;
  public int[] networkPort;
  public SocketChannelBinaryAsynchClient channel;

  private final ContextConfiguration contextConfig;
  private final String rootUser;
  private final String rootPassword;

  public ServerShutdownMain(
      final String iServerAddress,
      final String iServerPorts,
      final String iRootUser,
      final String iRootPassword) {
    contextConfig = new ContextConfiguration();

    rootUser = iRootUser;
    rootPassword = iRootPassword;

    networkAddress = iServerAddress;
    networkPort = ServerNetworkListener.getPorts(iServerPorts);
  }

  public void connect(final int iTimeout) throws IOException {
    // TRY TO CONNECT TO THE RIGHT PORT
    for (var port : networkPort) {
      try {
        channel =
            new SocketChannelBinaryAsynchClient(
                networkAddress,
                port,
                contextConfig,
                ChannelBinaryProtocol.CURRENT_PROTOCOL_VERSION);
        break;
      } catch (Exception e) {
        LogManager.instance().error(this, "Error on connecting to %s:%d", e, networkAddress, port);
      }
    }

    if (channel == null) {
      throw new NetworkProtocolException(
          "Cannot connect to server host '"
              + networkAddress
              + "', ports: "
              + Arrays.toString(networkPort));
    }

    var request = new ShutdownRequest(rootUser, rootPassword);
    channel.writeByte(request.getCommand());
    channel.writeInt(0);
    channel.writeBytes(null);
    request.write(null, channel, null);
    channel.flush();

    channel.beginResponse(null, 0, true);
  }

  public static void main(final String[] iArgs) {

    var serverHost = "localhost";
    var serverPorts = "2424-2430";
    var rootPassword = "NOT_PRESENT";
    var rootUser = ServerConfiguration.DEFAULT_ROOT_USER;

    var printUsage = false;

    for (var i = 0; i < iArgs.length; i++) {
      var arg = iArgs[i];
      if ("-P".equals(arg) || "--ports".equals(arg)) {
        serverPorts = iArgs[i + 1];
      }
      if ("-h".equals(arg) || "--host".equals(arg)) {
        serverHost = iArgs[i + 1];
      }
      if ("-p".equals(arg) || "--password".equals(arg)) {
        rootPassword = iArgs[i + 1];
      }
      if ("-u".equals(arg) || "--user".equals(arg)) {
        rootUser = iArgs[i + 1];
      }
      if ("-h".equals(arg) || "--help".equals(arg)) {
        printUsage = true;
      }
    }

    if ("NOT_PRESENT".equals(rootPassword) || printUsage) {
      System.out.println("allowed parameters");
      System.out.println(
          "-h | --host hostname : name or ip of the host where YouTrackDB is running. Deafult to"
              + " localhost ");
      System.out.println(
          "-P | --ports  ports : ports in the form of single value or range. Default to 2424-2430");
      System.out.println("-p | --password password : the super user password");
      System.out.println("-u | --user username: the super user name. Default to root");
      System.out.println(
          "example: shutdown.sh -h orientserver.mydomain -P 2424-2430 -u root -p securePassword");
    }

    System.out.println("Sending shutdown command to remote YouTrackDB Server instance...");

    try {
      new ServerShutdownMain(serverHost, serverPorts, rootUser, rootPassword).connect(5000);
      System.out.println("Shutdown executed correctly");
    } catch (Exception e) {
      System.out.println("Error: " + e.getLocalizedMessage());
    }
    System.out.println();
  }
}
