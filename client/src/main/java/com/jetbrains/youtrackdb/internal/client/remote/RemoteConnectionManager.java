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
package com.jetbrains.youtrackdb.internal.client.remote;

import static com.jetbrains.youtrackdb.api.config.GlobalConfiguration.CLIENT_CHANNEL_IDLE_CLOSE;
import static com.jetbrains.youtrackdb.api.config.GlobalConfiguration.CLIENT_CHANNEL_IDLE_TIMEOUT;
import static com.jetbrains.youtrackdb.api.config.GlobalConfiguration.NETWORK_LOCK_TIMEOUT;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.client.binary.SocketChannelBinaryAsynchClient;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages network connections against YouTrackDB servers. All the connection pools are managed in a
 * Map<url,pool>, but in the future we could have a unique pool per sever and manage database
 * connections over the protocol.
 */
public class RemoteConnectionManager {

  private static final Logger logger = LoggerFactory.getLogger(RemoteConnectionManager.class);
  public static final String PARAM_MAX_POOL = "maxpool";

  protected final ConcurrentMap<String, RemoteConnectionPool> connections;
  protected final long timeout;
  protected final long idleTimeout;
  private final TimerTask idleTask;

  public RemoteConnectionManager(final ContextConfiguration clientConfiguration, Timer timer) {
    connections = new ConcurrentHashMap<>();
    timeout = clientConfiguration.getValueAsLong(NETWORK_LOCK_TIMEOUT);
    var idleSecs = clientConfiguration.getValueAsInteger(CLIENT_CHANNEL_IDLE_TIMEOUT);
    this.idleTimeout = TimeUnit.MILLISECONDS.convert(idleSecs, TimeUnit.SECONDS);
    if (clientConfiguration.getValueAsBoolean(CLIENT_CHANNEL_IDLE_CLOSE)) {
      idleTask =
          new TimerTask() {
            @Override
            public void run() {
              checkIdle();
            }
          };
      var delay = this.idleTimeout / 3;
      timer.schedule(this.idleTask, delay, delay);
    } else {
      idleTask = null;
    }
  }

  public void close() {
    for (var entry : connections.entrySet()) {
      closePool(entry.getValue());
    }

    connections.clear();
    if (idleTask != null) {
      idleTask.cancel();
    }
  }

  @Nullable
  public SocketChannelBinaryAsynchClient acquire(
      String iServerURL, final ContextConfiguration clientConfiguration) {

    var localTimeout = timeout;

    var pool = connections.get(iServerURL);
    if (pool == null) {
      var maxPool = 8;

      if (clientConfiguration != null) {
        final var max =
            clientConfiguration.getValue(GlobalConfiguration.CLIENT_CHANNEL_MAX_POOL);
        if (max != null) {
          maxPool = Integer.parseInt(max.toString());
        }

        final var netLockTimeout = clientConfiguration.getValue(NETWORK_LOCK_TIMEOUT);
        if (netLockTimeout != null) {
          localTimeout = Integer.parseInt(netLockTimeout.toString());
        }
      }

      pool = new RemoteConnectionPool(maxPool);
      final var prev = connections.putIfAbsent(iServerURL, pool);
      if (prev != null) {
        // ALREADY PRESENT, DESTROY IT AND GET THE ALREADY EXISTENT OBJ
        pool.pool().close();
        pool = prev;
      }
    }

    try {
      // RETURN THE RESOURCE
      var ret = pool.acquire(iServerURL, localTimeout,
          clientConfiguration);
      ret.markInUse();
      return ret;

    } catch (RuntimeException e) {
      // ERROR ON RETRIEVING THE INSTANCE FROM THE POOL
      throw e;
    } catch (Exception e) {
      // ERROR ON RETRIEVING THE INSTANCE FROM THE POOL
      LogManager.instance()
          .debug(this, "Error on retrieving the connection from pool: " + iServerURL, logger, e);
    }
    return null;
  }

  public void release(final SocketChannelBinaryAsynchClient conn) {
    if (conn == null) {
      return;
    }

    conn.markReturned();
    final var pool = connections.get(conn.getServerURL());
    if (pool != null) {
      if (!conn.isConnected()) {
        LogManager.instance()
            .debug(
                this,
                "Network connection pool is receiving a closed connection to reuse: discard it",
                logger);
        remove(conn);
      } else {
        pool.pool().returnResource(conn);
      }
    }
  }

  public void remove(final SocketChannelBinaryAsynchClient conn) {
    if (conn == null) {
      return;
    }

    final var pool = connections.get(conn.getServerURL());
    if (pool == null) {
      throw new IllegalStateException(
          "Connection cannot be released because the pool doesn't exist anymore");
    }

    pool.pool().remove(conn);

    try {
      conn.unlock();
    } catch (Exception e) {
      LogManager.instance().debug(this, "Cannot unlock connection lock", logger, e);
    }

    try {
      conn.close();
    } catch (Exception e) {
      LogManager.instance().debug(this, "Cannot close connection", logger, e);
    }
  }

  public Set<String> getURLs() {
    return connections.keySet();
  }

  public int getMaxResources(final String url) {
    final var pool = connections.get(url);
    if (pool == null) {
      return 0;
    }

    return pool.pool().getMaxResources();
  }

  public int getAvailableConnections(final String url) {
    final var pool = connections.get(url);
    if (pool == null) {
      return 0;
    }

    return pool.pool().getAvailableResources();
  }

  public int getReusableConnections(final String url) {
    if (url == null) {
      return 0;
    }
    final var pool = connections.get(url);
    if (pool == null) {
      return 0;
    }

    return pool.pool().getInPoolResources();
  }

  public int getCreatedInstancesInPool(final String url) {
    final var pool = connections.get(url);
    if (pool == null) {
      return 0;
    }

    return pool.pool().getCreatedInstances();
  }

  public void closePool(final String url) {
    final var pool = connections.remove(url);
    if (pool == null) {
      return;
    }

    closePool(pool);
  }

  protected void closePool(RemoteConnectionPool pool) {
    final List<SocketChannelBinaryAsynchClient> conns =
        new ArrayList<>(pool.pool().getAllResources());
    for (var c : conns) {
      try {
        // Unregister the listener that make the connection return to the closing pool.
        c.close();
      } catch (Exception e) {
        LogManager.instance().debug(this, "Cannot close binary channel", logger, e);
      }
    }
    pool.pool().close();
  }

  public RemoteConnectionPool getPool(String url) {
    return connections.get(url);
  }

  public void checkIdle() {
    for (var entry : connections.entrySet()) {
      entry.getValue().checkIdle(idleTimeout);
    }
  }
}
