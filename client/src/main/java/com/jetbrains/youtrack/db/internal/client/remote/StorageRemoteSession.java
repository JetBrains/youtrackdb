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
package com.jetbrains.youtrack.db.internal.client.remote;

import com.jetbrains.youtrack.db.internal.client.binary.SocketChannelBinaryAsynchClient;
import com.jetbrains.youtrack.db.internal.client.remote.message.CloseRequest;
import com.jetbrains.youtrack.db.internal.common.io.YTIOException;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.SocketChannelBinary;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import javax.annotation.Nullable;

/**
 *
 */
public class StorageRemoteSession {

  public boolean commandExecuting = false;
  protected int serverURLIndex = -1;
  protected String connectionUserName = null;
  protected String connectionUserPassword = null;
  protected Map<String, StorageRemoteNodeSession> sessions =
      new HashMap<String, StorageRemoteNodeSession>();

  private Set<SocketChannelBinary> connections =
      Collections.newSetFromMap(new WeakHashMap<SocketChannelBinary, Boolean>());
  private final int uniqueClientSessionId;
  private boolean closed = true;

  /**
   * Make the retry to happen only on the current session, if the current session is invalid or the
   * server is offline it kill the operation.
   *
   * <p>this is for avoid to send to the server wrong request expecting a specific state that is
   * not there anymore.
   */
  private int stickToSession = 0;

  protected String currentUrl;

  public StorageRemoteSession(final int sessionId) {
    this.uniqueClientSessionId = sessionId;
  }

  public boolean hasConnection(final SocketChannelBinary connection) {
    return connections.contains(connection);
  }

  public StorageRemoteNodeSession getServerSession(final String serverURL) {
    return sessions.get(serverURL);
  }

  public synchronized StorageRemoteNodeSession getOrCreateServerSession(final String serverURL) {
    var session = sessions.get(serverURL);
    if (session == null) {
      session = new StorageRemoteNodeSession(serverURL, uniqueClientSessionId);
      sessions.put(serverURL, session);
      closed = false;
    }
    return session;
  }

  public void addConnection(final SocketChannelBinary connection) {
    connections.add(connection);
  }

  public void close() {
    commandExecuting = false;
    serverURLIndex = -1;
    connections = new HashSet<SocketChannelBinary>();
    sessions = new HashMap<String, StorageRemoteNodeSession>();
    closed = true;
  }

  public boolean isClosed() {
    return closed;
  }

  public Integer getSessionId() {
    if (sessions.isEmpty()) {
      return -1;
    }
    final var curSession = sessions.values().iterator().next();
    return curSession.getSessionId();
  }

  @Nullable
  public String getServerUrl() {
    if (sessions.isEmpty()) {
      return null;
    }
    final var curSession = sessions.values().iterator().next();
    return curSession.getServerURL();
  }

  public synchronized void removeServerSession(final String serverURL) {
    sessions.remove(serverURL);
  }

  public synchronized Collection<StorageRemoteNodeSession> getAllServerSessions() {
    return sessions.values();
  }

  public void stickToSession() {
    this.stickToSession++;
  }

  public void unStickToSession() {
    this.stickToSession--;
  }

  public boolean isStickToSession() {
    return stickToSession > 0;
  }

  public void closeAllSessions(
      RemoteConnectionManager connectionManager, ContextConfiguration clientConfiguration) {
    for (var nodeSession : getAllServerSessions()) {
      SocketChannelBinaryAsynchClient network = null;
      try {
        network =
            StorageRemote.getNetwork(
                nodeSession.getServerURL(), connectionManager, clientConfiguration);
        var request = new CloseRequest();
        network.beginRequest(request.getCommand(), this);
        request.write(null, network, this);
        network.endRequest();
        connectionManager.release(network);
      } catch (YTIOException ex) {
        // IGNORING IF THE SERVER IS DOWN OR NOT REACHABLE THE SESSION IS AUTOMATICALLY CLOSED.
        LogManager.instance()
            .debug(this, "Impossible to comunicate to the server for close: %s", ex);
        connectionManager.remove(network);
      } catch (IOException ex) {
        // IGNORING IF THE SERVER IS DOWN OR NOT REACHABLE THE SESSION IS AUTOMATICALLY CLOSED.
        LogManager.instance()
            .debug(this, "Impossible to comunicate to the server for close: %s", ex);
        connectionManager.remove(network);
      }
    }
    close();
  }

  public String getDebugLastHost() {
    return currentUrl;
  }

  public String getCurrentUrl() {
    return currentUrl;
  }
}
