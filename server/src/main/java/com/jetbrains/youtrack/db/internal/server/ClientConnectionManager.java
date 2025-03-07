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

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequestText;
import com.jetbrains.youtrack.db.internal.core.security.ParsedToken;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.TokenSecurityException;
import com.jetbrains.youtrack.db.internal.server.monitoring.NetworkConnectionsStatsEvent;
import com.jetbrains.youtrack.db.internal.server.network.protocol.NetworkProtocol;
import com.jetbrains.youtrack.db.internal.server.network.protocol.binary.NetworkProtocolBinary;
import com.jetbrains.youtrack.db.internal.server.plugin.ServerPluginHelper;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLSocket;
import jdk.jfr.FlightRecorder;

public class ClientConnectionManager {

  private static final long TIMEOUT_PUSH = 3000;

  protected final ConcurrentMap<Integer, ClientConnection> connections =
      new ConcurrentHashMap<Integer, ClientConnection>();
  protected AtomicInteger connectionSerial = new AtomicInteger(0);
  protected final ConcurrentMap<HashToken, ClientSessions> sessions =
      new ConcurrentHashMap<HashToken, ClientSessions>();
  protected final TimerTask timerTask;
  private final YouTrackDBServer server;

  public ClientConnectionManager(YouTrackDBServer server) {
    final int delay = GlobalConfiguration.SERVER_CHANNEL_CLEAN_DELAY.getValueAsInteger();

    timerTask =
        YouTrackDBEnginesManager.instance()
            .getScheduler()
            .scheduleTask(
                () -> {
                  try {
                    cleanExpiredConnections();
                  } catch (Exception e) {
                    LogManager.instance().debug(this, "Error on client connection purge task", e);
                  }
                },
                delay,
                delay);

    FlightRecorder.addPeriodicEvent(NetworkConnectionsStatsEvent.class, () -> {
      new NetworkConnectionsStatsEvent(connections.size()).commit();
    });

    this.server = server;
  }

  public void cleanExpiredConnections() {
    final Iterator<Entry<Integer, ClientConnection>> iterator = connections.entrySet().iterator();
    while (iterator.hasNext()) {
      final Entry<Integer, ClientConnection> entry = iterator.next();

      if (entry.getValue().tryAcquireForExpire()) {
        try {

          final Socket socket;
          if (entry.getValue().getProtocol() == null
              || entry.getValue().getProtocol().getChannel() == null) {
            socket = null;
          } else {
            socket = entry.getValue().getProtocol().getChannel().socket;
          }

          if (socket == null || socket.isClosed() || socket.isInputShutdown()) {
            LogManager.instance()
                .debug(
                    this,
                    "[ClientConnectionManager] found and removed pending closed channel %d (%s)",
                    entry.getKey(),
                    socket);
            try {
              CommandRequestText command = entry.getValue().getData().command;
              if (command != null && command.isIdempotent()) {
                entry.getValue().getProtocol().sendShutdown();
                entry.getValue().getProtocol().interrupt();
              }
              removeConnectionFromSession(entry.getValue());
              entry.getValue().close();

            } catch (Exception e) {
              LogManager.instance()
                  .error(this, "Error during close of connection for close channel", e);
            }
            iterator.remove();
          } else if (Boolean.TRUE.equals(entry.getValue().getTokenBased())) {
            if (entry.getValue().getToken() != null
                && !server.getTokenHandler().validateBinaryToken(entry.getValue().getToken())) {
              // Close the current session but not the network because can be used by another
              // session.
              removeConnectionFromSession(entry.getValue());
              entry.getValue().close();
              iterator.remove();
            }
          }
        } finally {
          entry.getValue().release();
        }
      }
    }
    server.getPushManager().cleanPushSockets();
  }

  /**
   * Create a connection.
   *
   * @param iProtocol protocol which will be used by connection
   * @return new connection
   */
  public ClientConnection connect(final NetworkProtocol iProtocol) {

    final ClientConnection connection;

    connection = new ClientConnection(connectionSerial.incrementAndGet(), iProtocol);

    connections.put(connection.getId(), connection);
    LogManager.instance().debug(this, "Remote client connected from: " + connection);
    ServerPluginHelper.invokeHandlerCallbackOnClientConnection(iProtocol.getServer(), connection);
    return connection;
  }

  /**
   * Create a connection.
   *
   * @param iProtocol protocol which will be used by connection
   * @return new connection
   */
  public ClientConnection connect(
      final NetworkProtocol iProtocol,
      final ClientConnection connection,
      final byte[] tokenBytes) {

    ParsedToken parsedToken;
    try {
      parsedToken = server.getTokenHandler().parseOnlyBinary(tokenBytes);
    } catch (Exception e) {
      throw BaseException.wrapException(new TokenSecurityException("Error on token parsing"), e);
    }
    if (!server.getTokenHandler().validateBinaryToken(parsedToken)) {
      throw new TokenSecurityException("The token provided is expired");
    }
    ClientSessions session;
    synchronized (sessions) {
      session = new ClientSessions(tokenBytes);
      sessions.put(new HashToken(tokenBytes), session);
    }
    connection.setToken(parsedToken, tokenBytes);
    session.addConnection(connection);
    LogManager.instance().debug(this, "Remote client connected from: " + connection);
    ServerPluginHelper.invokeHandlerCallbackOnClientConnection(iProtocol.getServer(), connection);
    return connection;
  }

  public ClientConnection reConnect(final NetworkProtocol iProtocol, final byte[] tokenBytes) {
    final ClientConnection connection;
    connection = new ClientConnection(connectionSerial.incrementAndGet(), iProtocol);
    connections.put(connection.getId(), connection);
    ParsedToken parsedToken;
    try {
      parsedToken = server.getTokenHandler().parseOnlyBinary(tokenBytes);
    } catch (Exception e) {
      throw BaseException.wrapException(new TokenSecurityException("Error on token parsing"), e);
    }
    if (!server.getTokenHandler().validateBinaryToken(parsedToken)) {
      throw new TokenSecurityException("The token provided is expired");
    }

    HashToken key = new HashToken(tokenBytes);
    ClientSessions sess;
    synchronized (sessions) {
      sess = sessions.get(key);
      if (sess == null) {
        // RECONNECT
        sess = new ClientSessions(tokenBytes);
        sessions.put(new HashToken(tokenBytes), sess);
      }
    }
    connection.setToken(parsedToken, tokenBytes);
    sess.addConnection(connection);
    ServerPluginHelper.invokeHandlerCallbackOnClientConnection(iProtocol.getServer(), connection);
    return connection;
  }

  /**
   * Retrieves the connection by id.
   *
   * @param iChannelId id of connection
   * @return The connection if any, otherwise null
   */
  public ClientConnection getConnection(final int iChannelId, NetworkProtocol protocol) {
    // SEARCH THE CONNECTION BY ID
    ClientConnection connection = connections.get(iChannelId);
    if (connection != null) {
      connection.setProtocol(protocol);
    }

    return connection;
  }

  /**
   * Retrieves the connection by address/port.
   *
   * @param iAddress The address as string in the format address as format <ip>:<port>
   * @return The connection if any, otherwise null
   */
  public ClientConnection getConnection(final String iAddress) {
    for (ClientConnection conn : connections.values()) {
      if (iAddress.equals(conn.getRemoteAddress())) {
        return conn;
      }
    }
    return null;
  }

  /**
   * Disconnects and kill the associated network manager.
   *
   * @param iChannelId id of connection
   */
  public void kill(final int iChannelId) {
    kill(connections.get(iChannelId));
  }

  /**
   * Disconnects and kill the associated network manager.
   *
   * @param connection connection to kill
   */
  public void kill(final ClientConnection connection) {
    if (connection != null) {
      final NetworkProtocol protocol = connection.getProtocol();

      try {
        // INTERRUPT THE NEWTORK MANAGER TOO
        protocol.interrupt();
      } catch (Exception e) {
        LogManager.instance().error(this, "Error during interruption of binary protocol", e);
      }

      disconnect(connection);

      // KILL THE NETWORK MANAGER TOO
      protocol.sendShutdown();
    }
  }

  public boolean has(final int id) {
    return connections.containsKey(id);
  }

  /**
   * Interrupt the associated network manager.
   *
   * @param iChannelId id of connection
   */
  public void interrupt(final int iChannelId) {
    final ClientConnection connection = connections.get(iChannelId);
    if (connection != null) {
      final NetworkProtocol protocol = connection.getProtocol();
      if (protocol != null)
      // INTERRUPT THE NEWTORK MANAGER
      {
        protocol.softShutdown();
      }
    }
  }

  /**
   * Disconnects a client connections
   *
   * @param iChannelId id of connection
   */
  public void disconnect(final int iChannelId) {
    LogManager.instance().debug(this, "Disconnecting connection with id=%d", iChannelId);

    final ClientConnection connection = connections.remove(iChannelId);

    if (connection != null) {
      ServerPluginHelper.invokeHandlerCallbackOnClientDisconnection(server, connection);
      connection.close();
      removeConnectionFromSession(connection);

      // CHECK IF THERE ARE OTHER CONNECTIONS
      for (Entry<Integer, ClientConnection> entry : connections.entrySet()) {
        if (entry.getValue().getProtocol().equals(connection.getProtocol())) {
          LogManager.instance()
              .debug(
                  this,
                  "Disconnected connection with id=%d but are present other active channels",
                  iChannelId);
          return;
        }
      }

      LogManager.instance()
          .debug(
              this,
              "Disconnected connection with id=%d, no other active channels found",
              iChannelId);
      return;
    }

    LogManager.instance().debug(this, "Cannot find connection with id=%d", iChannelId);
  }

  private void removeConnectionFromSession(ClientConnection connection) {
    if (connection.getProtocol() instanceof NetworkProtocolBinary) {
      byte[] tokenBytes = connection.getTokenBytes();
      HashToken hashToken = new HashToken(tokenBytes);
      synchronized (sessions) {
        ClientSessions sess = sessions.get(hashToken);
        if (sess != null) {
          sess.removeConnection(connection);
          if (!sess.isActive()) {
            sessions.remove(hashToken);
          }
        }
      }
    }
  }

  public void disconnect(final ClientConnection iConnection) {
    LogManager.instance().debug(this, "Disconnecting connection %s...", iConnection);
    ServerPluginHelper.invokeHandlerCallbackOnClientDisconnection(server, iConnection);
    removeConnectionFromSession(iConnection);
    iConnection.close();

    int totalRemoved = 0;
    for (Entry<Integer, ClientConnection> entry :
        new HashMap<Integer, ClientConnection>(connections).entrySet()) {
      final ClientConnection conn = entry.getValue();
      if (conn != null && conn.equals(iConnection)) {
        connections.remove(entry.getKey());
        totalRemoved++;
      }
    }

    LogManager.instance()
        .debug(this, "Disconnected connection %s found %d channels", iConnection, totalRemoved);
  }

  public List<ClientConnection> getConnections() {
    return new ArrayList<ClientConnection>(connections.values());
  }

  public int getTotal() {
    return connections.size();
  }

  public void shutdown() {
    timerTask.cancel();

    List<NetworkProtocol> toWait = new ArrayList<NetworkProtocol>();

    for (Entry<Integer, ClientConnection> entry : connections.entrySet()) {
      final NetworkProtocol protocol = entry.getValue().getProtocol();

      if (protocol != null) {
        protocol.sendShutdown();
      }

      LogManager.instance().debug(this, "Sending shutdown to thread %s", protocol);

      CommandRequestText command = entry.getValue().getData().command;
      if (command != null && command.isIdempotent()) {
        protocol.interrupt();
      } else {
        if (protocol instanceof NetworkProtocolBinary
            && ((NetworkProtocolBinary) protocol).getRequestType()
            == ChannelBinaryProtocol.REQUEST_SHUTDOWN) {
          continue;
        }

        final Socket socket;
        if (protocol == null || protocol.getChannel() == null) {
          socket = null;
        } else {
          socket = protocol.getChannel().socket;
        }

        if (socket != null && !socket.isClosed() && !socket.isInputShutdown()) {
          try {
            LogManager.instance().debug(this, "Closing input socket of thread %s", protocol);
            if (!(socket
                instanceof SSLSocket)) // An SSLSocket will throw an UnsupportedOperationException.
            {
              socket.shutdownInput();
            }
          } catch (IOException e) {
            LogManager.instance()
                .debug(
                    this,
                    "Error on closing connection of %s client during shutdown",
                    e,
                    entry.getValue().getRemoteAddress());
          }
        }
        if (protocol.isAlive()) {
          if (protocol instanceof NetworkProtocolBinary
              && ((NetworkProtocolBinary) protocol).getRequestType() == -1) {
            try {
              LogManager.instance().debug(this, "Closing socket of thread %s", protocol);
              protocol.getChannel().close();
            } catch (Exception e) {
              LogManager.instance().debug(this, "Error during chanel close at shutdown", e);
            }
            LogManager.instance().debug(this, "Sending interrupt signal to thread %s", protocol);
            protocol.interrupt();
          }
          toWait.add(protocol);
        }
      }
    }

    for (NetworkProtocol protocol : toWait) {
      try {
        protocol.join(
            server
                .getContextConfiguration()
                .getValueAsInteger(GlobalConfiguration.SERVER_CHANNEL_CLEAN_DELAY));
        if (protocol.isAlive()) {
          protocol.interrupt();
          protocol.join();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  public void killAllChannels() {
    for (Map.Entry<Integer, ClientConnection> entry : connections.entrySet()) {
      try {
        NetworkProtocol protocol = entry.getValue().getProtocol();

        protocol.getChannel().close();

        final Socket socket;
        if (protocol == null || protocol.getChannel() == null) {
          socket = null;
        } else {
          socket = protocol.getChannel().socket;
        }

        if (socket != null && !socket.isClosed() && !socket.isInputShutdown()) {
          if (!(socket
              instanceof SSLSocket)) // An SSLSocket will throw an UnsupportedOperationException.
          {
            socket.shutdownInput();
          }
        }

      } catch (Exception e) {
        LogManager.instance()
            .debug(
                this,
                "Error on killing connection to %s client",
                e,
                entry.getValue().getRemoteAddress());
      }
    }
  }

  public ClientSessions getSession(ClientConnection connection) {
    HashToken key = new HashToken(connection.getTokenBytes());
    synchronized (sessions) {
      return sessions.get(key);
    }
  }
}
