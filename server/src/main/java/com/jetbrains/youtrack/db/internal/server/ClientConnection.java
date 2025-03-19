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

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.internal.client.binary.BinaryRequestExecutor;
import com.jetbrains.youtrack.db.internal.common.exception.SystemException;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Token;
import com.jetbrains.youtrack.db.internal.core.security.ParsedToken;
import com.jetbrains.youtrack.db.internal.core.security.SecurityUser;
import com.jetbrains.youtrack.db.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.SocketChannelBinary;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.TokenSecurityException;
import com.jetbrains.youtrack.db.internal.server.network.protocol.NetworkProtocol;
import com.jetbrains.youtrack.db.internal.server.network.protocol.NetworkProtocolData;
import com.jetbrains.youtrack.db.internal.server.network.protocol.binary.NetworkProtocolBinary;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.Nullable;

public class ClientConnection {

  private final int id;
  private final long since;
  private final Set<NetworkProtocol> protocols =
      Collections.newSetFromMap(new WeakHashMap<NetworkProtocol, Boolean>());
  private volatile NetworkProtocol protocol;
  private volatile DatabaseSessionInternal session;
  private volatile SecurityUser serverUser;
  private NetworkProtocolData data = new NetworkProtocolData();
  private final ClientConnectionStats stats = new ClientConnectionStats();
  private final Lock lock = new ReentrantLock();
  private Boolean tokenBased;
  private byte[] tokenBytes;
  private ParsedToken token;
  private boolean disconnectOnAfter;
  private final BinaryRequestExecutor executor;

  public ClientConnection(final int id, final NetworkProtocol protocol) {
    this.id = id;
    this.protocol = protocol;
    this.protocols.add(protocol);
    this.since = System.currentTimeMillis();
    this.executor = protocol.executor(this);
  }

  public void close() {
    if (session != null) {
      if (!session.isClosed()) {
        session.activateOnCurrentThread();
        try {
          session.close();
        } catch (Exception e) {
          // IGNORE IT (ALREADY CLOSED?)
        }
      }

      session = null;
    }
  }

  /**
   * Acquires the connection. This is fundamental to manage concurrent requests using the same
   * session id.
   */
  public void acquire() {
    lock.lock();
  }

  /**
   * Releases an acquired connection.
   */
  public void release() {
    lock.unlock();
  }

  @Override
  public String toString() {
    Object address;
    if (protocol != null && protocol.getChannel() != null && protocol.getChannel().socket != null) {
      address = protocol.getChannel().socket.getRemoteSocketAddress();
    } else {
      address = "?";
    }
    return "ClientConnection [id=" + id + ", source=" + address + ", since=" + since + "]";
  }

  /**
   * Returns the remote network address in the format <ip>:<port>.
   */
  public String getRemoteAddress() {
    Socket socket = null;
    if (protocol != null) {
      socket = protocol.getChannel().socket;
    } else {
      for (var protocol : this.protocols) {
        socket = protocol.getChannel().socket;
        if (socket != null) {
          break;
        }
      }
    }

    if (socket != null) {
      final var remoteAddress = (InetSocketAddress) socket.getRemoteSocketAddress();
      return remoteAddress.getAddress().getHostAddress() + ":" + remoteAddress.getPort();
    }
    return null;
  }

  @Override
  public int hashCode() {
    return id;
  }

  @Override
  public boolean equals(final Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null) {
      return false;
    }
    if (getClass() != obj.getClass()) {
      return false;
    }
    final var other = (ClientConnection) obj;
    return id == other.id;
  }

  public SocketChannelBinary getChannel() {
    return (SocketChannelBinary) protocol.getChannel();
  }

  public NetworkProtocol getProtocol() {
    return protocol;
  }

  public byte[] getTokenBytes() {
    return tokenBytes;
  }

  public void validateSession(
      byte[] tokenFromNetwork, TokenHandler handler, NetworkProtocolBinary protocol) {
    if (tokenFromNetwork == null || tokenFromNetwork.length == 0) {
      if (!protocols.contains(protocol)) {
        throw new TokenSecurityException("No valid session found, provide a token");
      }
    } else {
      // IF the byte from the network are the same of the one i have a don't check them
      if (tokenBytes != null && tokenBytes.length > 0) {
        if (Arrays.equals(
            tokenBytes, tokenFromNetwork)) // SAME SESSION AND TOKEN NO NEED CHECK VALIDITY
        {
          return;
        }
      }

      ParsedToken token = null;
      try {
        if (tokenFromNetwork != null) {
          token = handler.parseOnlyBinary(tokenFromNetwork);
        }
      } catch (Exception e) {
        throw BaseException.wrapException(new SystemException("Error on token parse"), e,
            (String) null);
      }

      if (token == null || !handler.validateBinaryToken(token)) {
        cleanSession();
        protocol.getServer().getClientConnectionManager().disconnect(this);
        throw new TokenSecurityException(
            "The token provided is not a valid token, signature does not match");
      }
      if (!handler.validateBinaryToken(token)) {
        cleanSession();
        protocol.getServer().getClientConnectionManager().disconnect(this);
        throw new TokenSecurityException("The token provided is expired");
      }
      if (tokenBased == null) {
        tokenBased = Boolean.TRUE;
      }
      if (!Arrays.equals(this.tokenBytes, tokenFromNetwork)) {
        cleanSession();
      }
      this.tokenBytes = tokenFromNetwork;
      this.token = token;
      protocols.add(protocol);
    }
  }

  public void cleanSession() {
    if (session != null && !session.isClosed()) {
      session.activateOnCurrentThread();
      session.close();
    }
    session = null;
    protocols.clear();
  }

  public void endOperation() {
    if (session != null) {
      if (!session.isClosed()
          && !session.getTransactionInternal().isActive()
          && session.getLocalCache() != null) {
        session.getLocalCache().clear();
      }
    }

    stats.lastCommandExecutionTime = System.currentTimeMillis() - stats.lastCommandReceived;
    stats.totalCommandExecutionTime += stats.lastCommandExecutionTime;

    stats.lastCommandInfo = data.commandInfo;
    stats.lastCommandDetail = data.commandDetail;

    data.commandDetail = "-";
    release();
  }

  public void init(final YouTrackDBServer server) {
    if (session == null) {
      data = server.getTokenHandler().getProtocolDataFromToken(this, token.getToken());

      if (data == null) {
        throw new TokenSecurityException("missing in token data");
      }

      final var dbName = token.getToken().getDatabaseName();
      final var type = token.getToken().getDatabaseType();
      if (dbName != null && type != null) {
        session = server.openSession(dbName, token);
      }
    }
  }

  public Boolean getTokenBased() {
    return tokenBased;
  }

  public void setTokenBased(Boolean tokenBased) {
    this.tokenBased = tokenBased;
  }

  public void setTokenBytes(byte[] tokenBytes) {
    this.tokenBytes = tokenBytes;
  }

  public Token getToken() {
    if (token != null) {
      return token.getToken();
    } else {
      return null;
    }
  }

  public int getId() {
    return id;
  }

  public long getSince() {
    return since;
  }

  public void setProtocol(NetworkProtocol protocol) {
    this.protocol = protocol;
  }

  @Nullable
  public DatabaseSessionInternal getDatabaseSession() {
    return session;
  }

  public void activateDatabaseOnCurrentThread() {
    var database = this.session;
    if (database != null) {
      database.activateOnCurrentThread();
    }
  }

  public String getDatabaseName() {
    if (session != null) {
      return session.getDatabaseName();
    } else if (token != null) {
      return token.getToken().getDatabaseName();
    } else {
      return null;
    }
  }

  public void setSession(DatabaseSessionInternal session) {
    this.session = session;
  }

  public SecurityUser getServerUser() {
    return serverUser;
  }

  public void setServerUser(SecurityUser serverUser) {
    this.serverUser = serverUser;
  }

  public NetworkProtocolData getData() {
    return data;
  }

  public void setData(NetworkProtocolData data) {
    this.data = data;
  }

  public ClientConnectionStats getStats() {
    return stats;
  }

  public void statsUpdate() {
    if (session != null) {
      session.activateOnCurrentThread();
      stats.lastDatabase = session.getDatabaseName();
      stats.lastUser =
          session.getCurrentUser() != null ? session.getCurrentUser().getName(session) : null;
      stats.activeQueries = getActiveQueries(session);
    } else {
      stats.lastDatabase = null;
      stats.lastUser = null;
    }

    ++stats.totalRequests;
    data.commandInfo = "Listening";
    data.commandDetail = "-";
    stats.lastCommandReceived = System.currentTimeMillis();
  }

  private static List<String> getActiveQueries(DatabaseSessionInternal database) {
    try {
      List<String> result = new ArrayList<>();
      var queries = database.getActiveQueries();
      for (var oResultSet : queries.values()) {
        var plan = oResultSet.getResultSet().getExecutionPlan();
        if (plan == null) {
          continue;
        }
        if (plan instanceof InternalExecutionPlan internalExecutionPlan) {
          var stm = internalExecutionPlan.getStatement();
          if (stm != null) {
            result.add(stm);
          }
        }
      }
      return result;
    } catch (Exception e) {
    }
    return null;
  }

  public void setDisconnectOnAfter(boolean disconnectOnAfter) {
    this.disconnectOnAfter = disconnectOnAfter;
  }

  public boolean isDisconnectOnAfter() {
    return disconnectOnAfter;
  }

  public BinaryRequestExecutor getExecutor() {
    return executor;
  }

  public boolean tryAcquireForExpire() {
    try {
      return lock.tryLock(1, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return false;
  }

  public void setToken(ParsedToken parsedToken, byte[] tokenBytes) {
    this.token = parsedToken;
    this.tokenBytes = tokenBytes;
    this.tokenBased = true;
  }
}
