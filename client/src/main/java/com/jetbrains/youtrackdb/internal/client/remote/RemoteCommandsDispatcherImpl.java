/*
 *
 *  *  Copyright YouTrackDB
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
 */
package com.jetbrains.youtrackdb.internal.client.remote;

import com.jetbrains.youtrackdb.api.common.query.BasicLiveQueryResultListener;
import com.jetbrains.youtrackdb.api.common.query.LiveQueryMonitor;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.exception.BaseException;
import com.jetbrains.youtrackdb.api.exception.DatabaseException;
import com.jetbrains.youtrackdb.api.exception.ModificationOperationProhibitedException;
import com.jetbrains.youtrackdb.api.exception.SecurityException;
import com.jetbrains.youtrackdb.api.remote.RemoteDatabaseSession;
import com.jetbrains.youtrackdb.api.remote.query.RemoteResult;
import com.jetbrains.youtrackdb.internal.client.NotSendRequestException;
import com.jetbrains.youtrackdb.internal.client.binary.SocketChannelBinaryAsynchClient;
import com.jetbrains.youtrackdb.internal.client.remote.db.DatabaseSessionRemote;
import com.jetbrains.youtrackdb.internal.client.remote.db.YTLiveQueryMonitorRemote;
import com.jetbrains.youtrackdb.internal.client.remote.message.BinaryPushRequest;
import com.jetbrains.youtrackdb.internal.client.remote.message.CloseQueryRequest;
import com.jetbrains.youtrackdb.internal.client.remote.message.ImportRequest;
import com.jetbrains.youtrackdb.internal.client.remote.message.IncrementalBackupRequest;
import com.jetbrains.youtrackdb.internal.client.remote.message.LiveQueryPushRequest;
import com.jetbrains.youtrackdb.internal.client.remote.message.Open37Request;
import com.jetbrains.youtrackdb.internal.client.remote.message.PaginatedResultSet;
import com.jetbrains.youtrackdb.internal.client.remote.message.QueryNextPageRequest;
import com.jetbrains.youtrackdb.internal.client.remote.message.QueryRequest;
import com.jetbrains.youtrackdb.internal.client.remote.message.ReopenRequest;
import com.jetbrains.youtrackdb.internal.client.remote.message.RollbackActiveTxRequest;
import com.jetbrains.youtrackdb.internal.client.remote.message.SubscribeLiveQueryRequest;
import com.jetbrains.youtrackdb.internal.client.remote.message.UnsubscribeLiveQueryRequest;
import com.jetbrains.youtrackdb.internal.client.remote.message.UnsubscribeRequest;
import com.jetbrains.youtrackdb.internal.common.concur.OfflineNodeException;
import com.jetbrains.youtrackdb.internal.common.concur.lock.ThreadInterruptedException;
import com.jetbrains.youtrackdb.internal.common.io.YTIOException;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.thread.ThreadPoolExecutors;
import com.jetbrains.youtrackdb.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabasePoolInternal;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBConfigImpl;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.security.SecurityManager;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.StringSerializerHelper;
import com.jetbrains.youtrackdb.internal.core.storage.RecordCallback;
import com.jetbrains.youtrackdb.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import com.jetbrains.youtrackdb.internal.enterprise.channel.binary.DistributedRedirectException;
import com.jetbrains.youtrackdb.internal.enterprise.channel.binary.SocketChannelBinary;
import com.jetbrains.youtrackdb.internal.remote.RemoteCommandsDispatcher;
import com.jetbrains.youtrackdb.internal.remote.RemoteDatabaseSessionInternal;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This object is bound to each remote ODatabase instances.
 */
public class RemoteCommandsDispatcherImpl implements RemotePushHandler,
    RemoteCommandsDispatcher {

  private static final Logger logger = LoggerFactory.getLogger(
      RemoteCommandsDispatcherImpl.class);

  private static final AtomicInteger sessionSerialId = new AtomicInteger(-1);

  public enum CONNECTION_STRATEGY {
    STICKY,
    ROUND_ROBIN_CONNECT,
    ROUND_ROBIN_REQUEST
  }

  private CONNECTION_STRATEGY connectionStrategy = CONNECTION_STRATEGY.STICKY;

  private final RemoteURLs serverURLs;

  private final ExecutorService asynchExecutor;
  private final AtomicInteger users = new AtomicInteger(0);
  private final ContextConfiguration clientConfiguration;
  private final int connectionRetry;
  private final int connectionRetryDelay;

  public RemoteConnectionManager connectionManager;
  private final Set<BinaryProtocolSession> sessions =
      Collections.newSetFromMap(new ConcurrentHashMap<>());

  private final Map<Integer, LiveQueryClientListener> liveQueryListener =
      new ConcurrentHashMap<>();
  private volatile StorageRemotePushThread pushThread;
  protected final YouTrackDBInternalRemote context;
  protected final String url;
  protected final ReentrantReadWriteLock stateLock;

  protected String name;

  protected volatile STATUS status = STATUS.CLOSED;

  public static final String ADDRESS_SEPARATOR = ";";

  private static String buildUrl(String[] hosts, String name) {
    return String.join(ADDRESS_SEPARATOR, hosts) + "/" + name;
  }

  public RemoteCommandsDispatcherImpl(
      final RemoteURLs hosts,
      String name,
      YouTrackDBInternalRemote context,
      RemoteConnectionManager connectionManager,
      YouTrackDBConfigImpl config)
      throws IOException {
    this(hosts, name, context, connectionManager, null, config);
  }

  public RemoteCommandsDispatcherImpl(
      final RemoteURLs hosts,
      String name,
      YouTrackDBInternalRemote context,
      RemoteConnectionManager connectionManager,
      final STATUS status,
      YouTrackDBConfigImpl config)
      throws IOException {

    this.name = normalizeName(name);

    if (StringSerializerHelper.contains(this.name, ',')) {
      throw new IllegalArgumentException("Invalid character in storage name: " + this.name);
    }

    url = buildUrl(hosts.getUrls().toArray(new String[]{}), name);

    stateLock = new ReentrantReadWriteLock();
    if (status != null) {
      this.status = status;
    }

    if (config != null) {
      clientConfiguration = config.getConfiguration();
    } else {
      clientConfiguration = new ContextConfiguration();
    }
    connectionRetry =
        clientConfiguration.getValueAsInteger(GlobalConfiguration.NETWORK_SOCKET_RETRY);
    connectionRetryDelay =
        clientConfiguration.getValueAsInteger(GlobalConfiguration.NETWORK_SOCKET_RETRY_DELAY);
    serverURLs = hosts;

    asynchExecutor = ThreadPoolExecutors.newSingleThreadScheduledPool("StorageRemote Async");

    this.connectionManager = connectionManager;
    this.context = context;
  }

  private static String normalizeName(String name) {
    if (StringSerializerHelper.contains(name, '/')) {
      name = name.substring(name.lastIndexOf('/') + 1);

      if (StringSerializerHelper.contains(name, '\\')) {
        return name.substring(name.lastIndexOf('\\') + 1);
      } else {
        return name;
      }

    } else {
      if (StringSerializerHelper.contains(name, '\\')) {
        name = name.substring(name.lastIndexOf('\\') + 1);

        if (StringSerializerHelper.contains(name, '/')) {
          return name.substring(name.lastIndexOf('/') + 1);
        } else {
          return name;
        }
      } else {
        return name;
      }
    }
  }


  public String getName() {
    return name;
  }


  public <T extends BinaryResponse> void asyncNetworkOperationNoRetry(
      DatabaseSessionRemote database, final BinaryAsyncRequest<T> request,
      int mode,
      final RecordIdInternal recordId,
      final RecordCallback<T> callback,
      final String errorMessage) {
    asyncNetworkOperationRetry(database, request, mode, recordId, callback, errorMessage, 0);
  }

  public <T extends BinaryResponse> void asyncNetworkOperationRetry(
      DatabaseSessionRemote database, final BinaryAsyncRequest<T> request,
      int mode,
      final RecordIdInternal recordId,
      final RecordCallback<T> callback,
      final String errorMessage,
      int retry) {
    final int pMode;
    if (mode == 1 && callback == null)
    // ASYNCHRONOUS MODE NO ANSWER
    {
      pMode = 2;
    } else {
      pMode = mode;
    }
    request.setMode((byte) pMode);
    baseNetworkOperation(database,
        (network, session) -> {
          // Send The request
          try {
            try {
              network.beginRequest(request.getCommand(), session);
              request.write(database, network, session);
            } finally {
              network.endRequest();
            }
          } catch (IOException e) {
            throw new NotSendRequestException("Cannot send request on this channel");
          }
          final var response = request.createResponse();
          T ret = null;
          if (pMode == 0) {
            // SYNC
            try {
              beginResponse(database, network, session);
              response.read(database, network, session);
            } finally {
              endResponse(network);
            }
            ret = response;
            connectionManager.release(network);
          } else {
            if (pMode == 1) {
              // ASYNC
              asynchExecutor.submit(
                  () -> {
                    try {
                      try {
                        beginResponse(database, network, session);
                        response.read(database, network, session);
                      } finally {
                        endResponse(network);
                      }
                      callback.call(recordId, response);
                      connectionManager.release(network);
                    } catch (Exception e) {
                      connectionManager.remove(network);
                      LogManager.instance().error(this, "Exception on async query", e);
                    } catch (Error e) {
                      connectionManager.remove(network);
                      LogManager.instance().error(this, "Exception on async query", e);
                      throw e;
                    }
                  });
            } else {
              // NO RESPONSE
              connectionManager.release(network);
            }
          }
          return ret;
        },
        errorMessage, retry);
  }

  public <T extends BinaryResponse> T networkOperationRetryTimeout(
      DatabaseSessionRemote database, final BinaryRequest<T> request, final String errorMessage,
      int retry, int timeout) {
    return baseNetworkOperation(database,
        (network, session) -> {
          try {
            try {
              network.beginRequest(request.getCommand(), session);
              request.write(database, network, session);
            } finally {
              network.endRequest();
            }
          } catch (IOException e) {
            if (network.isConnected()) {
              LogManager.instance().warn(this, "Error Writing request on the network", e);
            }
            throw new NotSendRequestException("Cannot send request on this channel");
          }

          var prev = network.getSocketTimeout();
          var response = request.createResponse();
          try {
            if (timeout > 0) {
              network.setSocketTimeout(timeout);
            }
            beginResponse(database, network, session);
            response.read(database, network, session);
          } finally {
            endResponse(network);
            if (timeout > 0) {
              network.setSocketTimeout(prev);
            }
          }
          connectionManager.release(network);
          return response;
        },
        errorMessage, retry);
  }

  public <T extends BinaryResponse> T networkOperationNoRetry(
      DatabaseSessionRemote database, final BinaryRequest<T> request,
      final String errorMessage) {
    return networkOperationRetryTimeout(database, request, errorMessage, 0, 0);
  }

  public <T extends BinaryResponse> T networkOperation(
      DatabaseSessionRemote database, final BinaryRequest<T> request,
      final String errorMessage) {
    return networkOperationRetryTimeout(database, request, errorMessage, connectionRetry, 0);
  }

  public <T> T baseNetworkOperation(
      DatabaseSessionRemote remoteSession, final StorageRemoteOperation<T> operation,
      final String errorMessage, int retry) {
    var session = getCurrentSession(remoteSession);
    if (session.commandExecuting) {
      throw new DatabaseException(name,
          "Cannot execute the request because an asynchronous operation is in progress. Please use"
              + " a different connection");
    }

    String serverUrl = null;
    do {
      SocketChannelBinaryAsynchClient network = null;

      if (serverUrl == null) {
        serverUrl = getNextAvailableServerURL(false, session);
      }

      do {
        try {
          network = getNetwork(serverUrl);
        } catch (BaseException e) {
          if (session.isStickToSession()) {
            throw e;
          } else {
            serverUrl = useNewServerURL(remoteSession, serverUrl);
            if (serverUrl == null) {
              throw e;
            }
          }
        }
      } while (network == null);

      try {
        session.commandExecuting = true;

        // In case i do not have a token or i'm switching between server i've to execute a open
        // operation.
        var nodeSession = session.getServerSession(network.getServerURL());
        if (nodeSession == null || !nodeSession.isValid() && !session.isStickToSession()) {
          if (nodeSession != null) {
            session.removeServerSession(nodeSession.getServerURL());
          }
          openRemoteDatabase(remoteSession, network);
          if (!network.tryLock()) {
            continue;
          }
        }

        return operation.execute(network, session);
      } catch (NotSendRequestException e) {
        connectionManager.remove(network);
        serverUrl = null;
      } catch (DistributedRedirectException e) {
        connectionManager.release(network);
        LogManager.instance()
            .debug(
                this,
                "Redirecting the request from server '%s' to the server '%s' because %s",
                logger,
                e,
                e.getFromServer(),
                e.toString(),
                e.getMessage());

        // RECONNECT TO THE SERVER SUGGESTED IN THE EXCEPTION
        serverUrl = e.getToServerAddress();
      } catch (ModificationOperationProhibitedException mope) {
        connectionManager.release(network);
        handleDBFreeze();
        serverUrl = null;
      } catch (OfflineNodeException e) {
        connectionManager.release(network);
        // Remove the current url because the node is offline
        this.serverURLs.remove(serverUrl);
        for (var activeSession : sessions) {
          // Not thread Safe ...
          activeSession.removeServerSession(serverUrl);
        }
        serverUrl = null;
      } catch (IOException | YTIOException e) {
        LogManager.instance()
            .warn(
                this,
                "Caught Network I/O errors on %s, trying an automatic reconnection... (error: %s)",
                network.getServerURL(),
                e.getMessage());
        LogManager.instance().debug(this, "I/O error stack: ", logger, e);
        connectionManager.remove(network);
        if (--retry <= 0) {
          throw BaseException.wrapException(new YTIOException(e.getMessage()), e, name);
        } else {
          try {
            //noinspection BusyWait
            Thread.sleep(connectionRetryDelay);
          } catch (java.lang.InterruptedException e1) {
            LogManager.instance()
                .error(this, "Exception was suppressed, original exception is ", e);
            throw BaseException.wrapException(new ThreadInterruptedException(e1.getMessage()),
                e1, name);
          }
        }
        serverUrl = null;
      } catch (BaseException e) {
        connectionManager.release(network);
        throw e;
      } catch (Exception e) {
        connectionManager.release(network);
        throw BaseException.wrapException(new StorageException(name, errorMessage), e, name);
      } finally {
        session.commandExecuting = false;
      }
    } while (true);
  }

  public int getSessionId(DatabaseSessionRemote database) {
    var session = getCurrentSession(database);
    return session != null ? session.getSessionId() : -1;
  }

  public TimeZone open(
      DatabaseSessionRemote dbSession, final String iUserName, final String iUserPassword,
      final ContextConfiguration conf) {
    addUser();
    try {
      var session = getCurrentSession(dbSession);
      if (status == STATUS.CLOSED
          || !iUserName.equals(session.connectionUserName)
          || !iUserPassword.equals(session.connectionUserPassword)
          || session.sessions.isEmpty()) {

        var ci = SecurityManager.instance().newCredentialInterceptor();

        if (ci != null) {
          ci.intercept(getURL(), iUserName, iUserPassword);
          session.connectionUserName = ci.getUsername();
          session.connectionUserPassword = ci.getPassword();
        } else {
          // Do Nothing
          session.connectionUserName = iUserName;
          session.connectionUserPassword = iUserPassword;
        }

        var strategy = conf.getValueAsString(GlobalConfiguration.CLIENT_CONNECTION_STRATEGY);
        if (strategy != null) {
          connectionStrategy = CONNECTION_STRATEGY.valueOf(strategy.toUpperCase(Locale.ENGLISH));
        }

        var timeZone = openRemoteDatabase(dbSession);
        initPush(dbSession);
        return timeZone;
      } else {
        return reopenRemoteDatabase(dbSession);
      }
    } catch (Exception e) {
      removeUser();
      if (e instanceof RuntimeException)
      // PASS THROUGH
      {
        throw (RuntimeException) e;
      } else {
        throw BaseException.wrapException(
            new StorageException(name, "Cannot open the remote storage: " + name), e, name);
      }
    }
  }

  public void close(DatabaseSessionRemote database, final boolean iForce) {
    if (status == STATUS.CLOSED) {
      return;
    }

    final var session = getCurrentSession(database);
    if (session != null) {
      final var nodes = session.getAllServerSessions();
      if (!nodes.isEmpty()) {
        session.closeAllSessions(connectionManager, clientConfiguration);
        if (!checkForClose(iForce)) {
          return;
        }
      } else {
        if (!iForce) {
          return;
        }
      }
      sessions.remove(session);
      checkForClose(iForce);
    }
  }

  public void shutdown() {
    if (status == STATUS.CLOSED || status == STATUS.CLOSING) {
      return;
    }

    // FROM HERE FORWARD COMPLETELY CLOSE THE STORAGE
    for (var listener : liveQueryListener.entrySet()) {
      listener.getValue().onEnd();
    }
    liveQueryListener.clear();

    stateLock.writeLock().lock();
    try {
      if (status == STATUS.CLOSED) {
        return;
      }

      status = STATUS.CLOSING;
      close(null, true);
    } finally {
      stateLock.writeLock().unlock();
    }
    if (pushThread != null) {
      pushThread.shutdown();
      try {
        pushThread.join();
      } catch (java.lang.InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    stateLock.writeLock().lock();
    try {
      // CLOSE ALL THE SOCKET POOLS
      status = STATUS.CLOSED;

    } finally {
      stateLock.writeLock().unlock();
    }
  }

  private boolean checkForClose(final boolean force) {
    if (status == STATUS.CLOSED) {
      return false;
    }

    final var remainingUsers = getUsers() > 0 ? removeUser() : 0;

    return force || remainingUsers == 0;
  }

  public int getUsers() {
    return users.get();
  }

  public void addUser() {
    users.incrementAndGet();
  }

  public int removeUser() {
    if (users.get() < 1) {
      throw new IllegalStateException(
          "Cannot remove user of the remote storage '" + this + "' because no user is using it");
    }

    return users.decrementAndGet();
  }

  public void incrementalBackup(DatabaseSessionRemote session, final String backupDirectory) {
    var request = new IncrementalBackupRequest(backupDirectory);
    networkOperationNoRetry(session, request,
        "Error on incremental backup");
  }

  public void stickToSession(DatabaseSessionRemote database) {
    var session = getCurrentSession(database);
    session.stickToSession();
  }

  public void unstickToSession(DatabaseSessionRemote database) {
    var session = getCurrentSession(database);
    session.unStickToSession();
  }

  public RemoteQueryResult query(DatabaseSessionRemote session, String query, Object[] args) {
    var recordsPerPage = clientConfiguration.getValueAsInteger(
        GlobalConfiguration.QUERY_REMOTE_RESULTSET_PAGE_SIZE);
    if (recordsPerPage <= 0) {
      recordsPerPage = 100;
    }
    var request =
        new QueryRequest(
            "sql", query, args, QueryRequest.QUERY, recordsPerPage);
    var response = networkOperation(session, request, "Error on executing command: " + query);
    try {

      var rs =
          new PaginatedResultSet(
              session,
              response.getQueryId(),
              response.getResult(),
              response.isHasNextPage());

      if (response.isHasNextPage() || response.isActiveTx()) {
        stickToSession(session);

        if (!response.isHasNextPage()) {
          session.queryClosed(response.getQueryId());
        }
      } else {
        session.queryClosed(response.getQueryId());
        unstickToSession(session);
      }

      return new RemoteQueryResult(rs);
    } catch (Exception e) {
      session.queryClosed(response.getQueryId());
      throw e;
    }
  }

  public RemoteQueryResult query(DatabaseSessionRemote session, String query,
      @SuppressWarnings("rawtypes") Map args) {
    var recordsPerPage = GlobalConfiguration.QUERY_REMOTE_RESULTSET_PAGE_SIZE.getValueAsInteger();
    if (recordsPerPage <= 0) {
      recordsPerPage = 100;
    }
    @SuppressWarnings("unchecked")
    var request =
        new QueryRequest("sql", query, args, QueryRequest.QUERY, recordsPerPage);
    var response = networkOperation(session, request, "Error on executing command: " + query);

    try {
      var rs =
          new PaginatedResultSet(
              session,
              response.getQueryId(),
              response.getResult(),
              response.isHasNextPage());

      if (response.isHasNextPage() || response.isActiveTx()) {
        stickToSession(session);
        if (!response.isHasNextPage()) {
          session.queryClosed(response.getQueryId());
        }
      } else {
        session.queryClosed(response.getQueryId());
        unstickToSession(session);
      }
      return new RemoteQueryResult(rs);
    } catch (Exception e) {
      session.queryClosed(response.getQueryId());
      throw e;
    }
  }

  public RemoteQueryResult command(DatabaseSessionRemote session, String query, Object[] args) {
    var recordsPerPage = GlobalConfiguration.QUERY_REMOTE_RESULTSET_PAGE_SIZE.getValueAsInteger();
    if (recordsPerPage <= 0) {
      recordsPerPage = 100;
    }
    var request =
        new QueryRequest("sql", query, args, QueryRequest.COMMAND, recordsPerPage);
    var response =
        networkOperationNoRetry(session, request, "Error on executing command: " + query);

    try {
      var rs =
          new PaginatedResultSet(
              session,
              response.getQueryId(),
              response.getResult(),
              response.isHasNextPage());
      if (response.isHasNextPage() || response.isActiveTx()) {
        stickToSession(session);

        if (!response.isHasNextPage()) {
          session.queryClosed(response.getQueryId());
        }
      } else {
        session.queryClosed(response.getQueryId());
        unstickToSession(session);
      }
      return new RemoteQueryResult(rs);
    } catch (Exception e) {
      session.queryClosed(response.getQueryId());
      throw e;
    }
  }

  public RemoteQueryResult command(DatabaseSessionRemote session, String query,
      @SuppressWarnings("rawtypes") Map args) {
    var recordsPerPage = GlobalConfiguration.QUERY_REMOTE_RESULTSET_PAGE_SIZE.getValueAsInteger();
    if (recordsPerPage <= 0) {
      recordsPerPage = 100;
    }
    @SuppressWarnings("unchecked")
    var request =
        new QueryRequest("sql", query, args, QueryRequest.COMMAND, recordsPerPage);
    var response =
        networkOperationNoRetry(session, request, "Error on executing command: " + query);
    try {
      var rs =
          new PaginatedResultSet(
              session,
              response.getQueryId(),
              response.getResult(),
              response.isHasNextPage());
      if (response.isHasNextPage() || response.isActiveTx()) {
        stickToSession(session);

        if (!response.isHasNextPage()) {
          session.queryClosed(response.getQueryId());
        }
      } else {
        session.queryClosed(response.getQueryId());
        unstickToSession(session);
      }
      return new RemoteQueryResult(rs);
    } catch (Exception e) {
      session.queryClosed(response.getQueryId());
      throw e;
    }
  }

  public RemoteQueryResult execute(
      DatabaseSessionRemote session, String language, String query, Object[] args) {
    var recordsPerPage = GlobalConfiguration.QUERY_REMOTE_RESULTSET_PAGE_SIZE.getValueAsInteger();
    if (recordsPerPage <= 0) {
      recordsPerPage = 100;
    }
    var request = new QueryRequest(language, query, args, QueryRequest.EXECUTE, recordsPerPage);
    var response =
        networkOperationNoRetry(session, request, "Error on executing command: " + query);

    try {
      var rs =
          new PaginatedResultSet(
              session,
              response.getQueryId(),
              response.getResult(),
              response.isHasNextPage());

      if (response.isHasNextPage() || response.isActiveTx()) {
        stickToSession(session);
        if (!response.isHasNextPage()) {
          session.queryClosed(response.getQueryId());
        }
      } else {
        session.queryClosed(response.getQueryId());
        unstickToSession(session);
      }

      return new RemoteQueryResult(rs);
    } catch (Exception e) {
      session.queryClosed(response.getQueryId());
      throw e;
    }
  }

  public RemoteQueryResult execute(
      DatabaseSessionRemote session, String language, String query,
      @SuppressWarnings("rawtypes") Map args) {
    var recordsPerPage = GlobalConfiguration.QUERY_REMOTE_RESULTSET_PAGE_SIZE.getValueAsInteger();
    if (recordsPerPage <= 0) {
      recordsPerPage = 100;
    }

    @SuppressWarnings("unchecked")
    var request =
        new QueryRequest(language, query, args, QueryRequest.EXECUTE, recordsPerPage);
    var response =
        networkOperationNoRetry(session, request, "Error on executing command: " + query);
    try {
      var rs =
          new PaginatedResultSet(
              session,
              response.getQueryId(),
              response.getResult(),
              response.isHasNextPage());
      if (response.isHasNextPage() || response.isActiveTx()) {
        stickToSession(session);

        if (!response.isHasNextPage()) {
          session.queryClosed(response.getQueryId());
        }
      } else {
        unstickToSession(session);
        session.queryClosed(response.getQueryId());
      }

      return new RemoteQueryResult(rs);
    } catch (Exception e) {
      session.queryClosed(response.getQueryId());
      throw e;
    }
  }

  public void closeQuery(DatabaseSessionRemote databaseSession, String queryId) {
    unstickToSession(databaseSession);
    var request = new CloseQueryRequest(queryId);
    networkOperation(databaseSession, request, "Error closing query: " + queryId);
  }

  public void rollbackActiveTx(DatabaseSessionRemote databaseSession) {
    unstickToSession(databaseSession);
    var request = new RollbackActiveTxRequest();
    networkOperation(databaseSession, request, "Error on rolling back active transaction");
  }

  public void fetchNextPage(DatabaseSessionRemote database, PaginatedResultSet rs) {
    var recordsPerPage = GlobalConfiguration.QUERY_REMOTE_RESULTSET_PAGE_SIZE.getValueAsInteger();
    if (recordsPerPage <= 0) {
      recordsPerPage = 100;
    }
    var request = new QueryNextPageRequest(rs.getQueryId(), recordsPerPage);
    var response =
        networkOperation(database, request,
            "Error on fetching next page for statment: " + rs.getQueryId());

    rs.fetched(
        response.getResult(),
        response.isHasNextPage());

    if (!response.isHasNextPage()) {
      unstickToSession(database);
      database.queryClosed(response.getQueryId());
    }
  }

  /**
   * Ends the request and unlock the write lock
   */
  public static void endRequest(final SocketChannelBinaryAsynchClient iNetwork) throws IOException {
    if (iNetwork == null) {
      return;
    }

    iNetwork.flush();
    iNetwork.releaseWriteLock();
  }

  /**
   * End response reached: release the channel in the pool to being reused
   */
  public static void endResponse(final SocketChannelBinaryAsynchClient iNetwork)
      throws IOException {
    iNetwork.endResponse();
  }

  public String getURL() {
    return EngineRemote.NAME + ":" + url;
  }

  @Nullable
  public String getUserName(DatabaseSessionRemote database) {
    final var session = getCurrentSession(database);
    if (session == null) {
      return null;
    }
    return session.connectionUserName;
  }

  private TimeZone reopenRemoteDatabase(DatabaseSessionRemote database) {
    var currentURL = getCurrentServerURL(database);
    do {
      do {
        final var network = getNetwork(currentURL);
        try {
          var session = getCurrentSession(database);
          var nodeSession =
              session.getOrCreateServerSession(network.getServerURL());
          if (nodeSession == null || !nodeSession.isValid()) {
            return openRemoteDatabase(database, network);
          } else {
            var request = new ReopenRequest();

            try {
              network.writeByte(request.getCommand());
              network.writeInt(nodeSession.getSessionId());
              network.writeBytes(nodeSession.getToken());
              request.write(null, network, session);
            } finally {
              endRequest(network);
            }

            var response = request.createResponse();
            try {
              var newToken = network.beginResponse(database, nodeSession.getSessionId(), true);
              response.read(database, network, session);
              if (newToken != null && newToken.length > 0) {
                nodeSession.setSession(response.getSessionId(), newToken);
              } else {
                nodeSession.setSession(response.getSessionId(), nodeSession.getToken());
              }
              LogManager.instance()
                  .debug(
                      this,
                      "Client connected to %s with session id=%d",
                      logger,
                      network.getServerURL(),
                      response.getSessionId());
              return response.getTimeZone();
            } finally {
              endResponse(network);
              connectionManager.release(network);
            }
          }
        } catch (YTIOException e) {
          if (network != null) {
            // REMOVE THE NETWORK CONNECTION IF ANY
            connectionManager.remove(network);
          }

          LogManager.instance().error(this, "Cannot open database with url " + currentURL, e);
        } catch (OfflineNodeException e) {
          if (network != null) {
            // REMOVE THE NETWORK CONNECTION IF ANY
            connectionManager.remove(network);
          }

          LogManager.instance()
              .debug(this, "Cannot open database with url " + currentURL, logger, e);
        } catch (SecurityException ex) {
          LogManager.instance().debug(this, "Invalidate token for url=%s", logger, ex, currentURL);
          var session = getCurrentSession(database);
          session.removeServerSession(currentURL);

          if (network != null) {
            // REMOVE THE NETWORK CONNECTION IF ANY
            try {
              connectionManager.remove(network);
            } catch (Exception e) {
              // IGNORE ANY EXCEPTION
              LogManager.instance()
                  .debug(this, "Cannot remove connection or database url=" + currentURL, logger, e);
            }
          }
        } catch (BaseException e) {
          connectionManager.release(network);
          // PROPAGATE ANY OTHER EXCEPTION
          throw e;

        } catch (Exception e) {
          LogManager.instance()
              .debug(this, "Cannot open database with url " + currentURL, logger, e);
          if (network != null) {
            // REMOVE THE NETWORK CONNECTION IF ANY
            try {
              connectionManager.remove(network);
            } catch (Exception ex) {
              // IGNORE ANY EXCEPTION
              LogManager.instance()
                  .debug(this, "Cannot remove connection or database url=" + currentURL, logger, e);
            }
          }
        }
      } while (connectionManager.getAvailableConnections(currentURL) > 0);

      currentURL = useNewServerURL(database, currentURL);

    } while (currentURL != null);

    // REFILL ORIGINAL SERVER LIST
    serverURLs.reloadOriginalURLs();

    throw new StorageException(name,
        "Cannot create a connection to remote server address(es): " + serverURLs.getUrls());
  }

  protected TimeZone openRemoteDatabase(DatabaseSessionRemote database) throws IOException {
    final var currentURL = getNextAvailableServerURL(true, getCurrentSession(database));
    return openRemoteDatabase(database, currentURL);
  }

  public TimeZone openRemoteDatabase(DatabaseSessionRemote database,
      SocketChannelBinaryAsynchClient network) throws IOException {

    var session = getCurrentSession(database);
    var nodeSession =
        session.getOrCreateServerSession(network.getServerURL());
    var request =
        new Open37Request(name, session.connectionUserName, session.connectionUserPassword);
    try {
      network.writeByte(request.getCommand());
      network.writeInt(nodeSession.getSessionId());
      network.writeBytes(null);
      request.write(null, network, session);
    } finally {
      endRequest(network);
    }
    final int sessionId;
    var response = request.createResponse();
    try {
      network.beginResponse(database, nodeSession.getSessionId(), true);
      response.read(database, network, session);
    } finally {
      endResponse(network);
      connectionManager.release(network);
    }
    sessionId = response.getSessionId();
    var token = response.getSessionToken();
    if (token.length == 0) {
      token = null;
    }

    nodeSession.setSession(sessionId, token);

    LogManager.instance()
        .debug(
            this, "Client connected to %s with session id=%d", logger,
            network.getServerURL(),
            sessionId);

    // READ COLLECTION CONFIGURATION
    // updateCollectionConfiguration(network.getServerURL(),
    // response.getDistributedConfiguration());

    // This need to be protected by a lock for now, let's see in future
    stateLock.writeLock().lock();
    try {
      status = STATUS.OPEN;
    } finally {
      stateLock.writeLock().unlock();
    }

    return response.getTimeZone();
  }

  private void initPush(DatabaseSessionRemote database) {
    if (pushThread == null) {
      stateLock.writeLock().lock();
      try {
        if (pushThread == null) {
          pushThread =
              new StorageRemotePushThread(
                  this,
                  getCurrentServerURL(database),
                  connectionRetryDelay,
                  clientConfiguration
                      .getValueAsLong(GlobalConfiguration.NETWORK_REQUEST_TIMEOUT));
          pushThread.start();
        }
      } finally {
        stateLock.writeLock().unlock();
      }
    }
  }

  protected TimeZone openRemoteDatabase(DatabaseSessionRemote database, String currentURL) {
    do {
      do {
        SocketChannelBinaryAsynchClient network = null;
        try {
          network = getNetwork(currentURL);
          return openRemoteDatabase(database, network);
        } catch (DistributedRedirectException e) {
          connectionManager.release(network);
          // RECONNECT TO THE SERVER SUGGESTED IN THE EXCEPTION
          currentURL = e.getToServerAddress();
        } catch (ModificationOperationProhibitedException mope) {
          connectionManager.release(network);
          handleDBFreeze();
          currentURL = useNewServerURL(database, currentURL);
        } catch (OfflineNodeException e) {
          connectionManager.release(network);
          currentURL = useNewServerURL(database, currentURL);
        } catch (YTIOException e) {
          if (network != null) {
            // REMOVE THE NETWORK CONNECTION IF ANY
            connectionManager.remove(network);
          }

          LogManager.instance()
              .debug(this, "Cannot open database with url " + currentURL, logger, e);

        } catch (BaseException e) {
          connectionManager.release(network);
          // PROPAGATE ANY OTHER EXCEPTION
          throw e;

        } catch (IOException e) {
          connectionManager.remove(network);
        } catch (Exception e) {
          if (network != null) {
            // REMOVE THE NETWORK CONNECTION IF ANY
            connectionManager.remove(network);
          }
          throw BaseException.wrapException(new StorageException(name, e.getMessage()), e, name);
        }
      } while (connectionManager.getReusableConnections(currentURL) > 0);

      if (currentURL != null) {
        currentURL = useNewServerURL(database, currentURL);
      }

    } while (currentURL != null);

    // REFILL ORIGINAL SERVER LIST
    serverURLs.reloadOriginalURLs();

    throw new StorageException(name,
        "Cannot create a connection to remote server address(es): " + serverURLs.getUrls());
  }

  protected String useNewServerURL(DatabaseSessionRemote database, final String iUrl) {
    var pos = iUrl.indexOf('/');
    if (pos >= iUrl.length() - 1)
    // IGNORE ENDING /
    {
      pos = -1;
    }

    final var url = pos > -1 ? iUrl.substring(0, pos) : iUrl;
    var newUrl = serverURLs.removeAndGet(url);
    var session = getCurrentSession(database);
    if (session != null) {
      session.currentUrl = newUrl;
      session.serverURLIndex = 0;
    }
    return newUrl;
  }


  protected String getNextAvailableServerURL(
      boolean iIsConnectOperation, BinaryProtocolSession session) {

    return serverURLs.getNextAvailableServerURL(
        iIsConnectOperation, session, connectionStrategy);
  }

  protected String getCurrentServerURL(DatabaseSessionRemote database) {
    return serverURLs.getServerURFromList(
        false, getCurrentSession(database));
  }

  @Override
  public SocketChannelBinaryAsynchClient getNetwork(final String iCurrentURL) {
    return getNetwork(iCurrentURL, connectionManager, clientConfiguration);
  }

  @Override
  @Nullable
  public BinaryPushRequest<?> createPush(byte type) {
    if (type == ChannelBinaryProtocol.REQUEST_PUSH_LIVE_QUERY) {
      return new LiveQueryPushRequest();
    }

    throw new IllegalArgumentException("Unsupported push type: " + type);
  }

  public static SocketChannelBinaryAsynchClient getNetwork(
      final String iCurrentURL,
      RemoteConnectionManager connectionManager,
      ContextConfiguration config) {
    SocketChannelBinaryAsynchClient network;
    do {
      try {
        network = connectionManager.acquire(iCurrentURL, config);
      } catch (YTIOException cause) {
        throw cause;
      } catch (Exception cause) {
        throw BaseException.wrapException(
            new StorageException(null, "Cannot open a connection to remote server: " + iCurrentURL),
            cause, (String) null);
      }
      if (!network.tryLock()) {
        // CANNOT LOCK IT, MAYBE HASN'T BE CORRECTLY UNLOCKED BY PREVIOUS USER?
        LogManager.instance()
            .error(
                RemoteCommandsDispatcherImpl.class,
                "Removing locked network channel '%s' (connected=%s)...",
                null,
                iCurrentURL,
                network.isConnected());
        connectionManager.remove(network);
        network = null;
      }
    } while (network == null);
    return network;
  }

  public static void beginResponse(
      DatabaseSessionRemote dbSession, SocketChannelBinaryAsynchClient iNetwork,
      BinaryProtocolSession session) throws IOException {
    var nodeSession = session.getServerSession(iNetwork.getServerURL());
    var newToken = iNetwork.beginResponse(dbSession, nodeSession.getSessionId(), true);
    if (newToken != null && newToken.length > 0) {
      nodeSession.setSession(nodeSession.getSessionId(), newToken);
    }
  }

  private void handleDBFreeze() {
    LogManager.instance()
        .warn(
            this,
            "DB is frozen will wait for "
                + clientConfiguration.getValue(GlobalConfiguration.CLIENT_DB_RELEASE_WAIT_TIMEOUT)
                + " ms. and then retry.");
    try {
      Thread.sleep(
          clientConfiguration.getValueAsInteger(
              GlobalConfiguration.CLIENT_DB_RELEASE_WAIT_TIMEOUT));
    } catch (java.lang.InterruptedException ie) {
      // IGNORE
    }
  }

  @Nullable
  protected BinaryProtocolSession getCurrentSession(@Nullable DatabaseSessionRemote db) {
    if (db == null) {
      return null;
    }

    var session = db.getSessionMetadata();
    if (session == null) {
      session = new BinaryProtocolSession(sessionSerialId.decrementAndGet());
      sessions.add(session);
      db.setSessionMetadata(session);
    }

    return session;
  }

  public boolean isClosed(DatabaseSessionRemote database) {
    if (status == STATUS.CLOSED) {
      return true;
    }
    final var session = getCurrentSession(database);
    if (session == null) {
      return false;
    }
    return session.isClosed();
  }

  public RemoteCommandsDispatcherImpl copy(
      final DatabaseSessionRemote source, final DatabaseSessionRemote dest) {
    final var session = source.getSessionMetadata();
    if (session != null) {
      // TODO:may run a session reopen
      final var newSession =
          new BinaryProtocolSession(sessionSerialId.decrementAndGet());
      newSession.connectionUserName = session.connectionUserName;
      newSession.connectionUserPassword = session.connectionUserPassword;
      dest.setSessionMetadata(newSession);
    }
    try {
      dest.activateOnCurrentThread();
      openRemoteDatabase(dest);
    } catch (IOException e) {
      LogManager.instance().error(this, "Error during database open", e);
    }

    return this;
  }

  public void importDatabase(
      DatabaseSessionRemote database, final String options,
      final InputStream inputStream,
      final String name,
      final CommandOutputListener listener) {
    var request = new ImportRequest(inputStream, options, name);

    var response =
        networkOperationRetryTimeout(database,
            request,
            "Error sending import request",
            0,
            clientConfiguration.getValueAsInteger(GlobalConfiguration.NETWORK_REQUEST_TIMEOUT));

    for (var message : response.getMessages()) {
      listener.onMessage(message);
    }
  }

  public LiveQueryMonitor liveQuery(
      DatabasePoolInternal<RemoteDatabaseSession> sessionPool,
      DatabaseSessionRemote database,
      String query,
      LiveQueryClientListener listener,
      Object[] params) {

    var request = new SubscribeLiveQueryRequest(query, params);
    var response = pushThread.subscribe(request, getCurrentSession(database), sessionPool);
    if (response == null) {
      throw new DatabaseException(name,
          "Impossible to start the live query, check server log for additional information");
    }
    registerLiveListener(response.getMonitorId(), listener);
    return new YTLiveQueryMonitorRemote(sessionPool, response.getMonitorId());
  }

  public LiveQueryMonitor liveQuery(
      DatabasePoolInternal<RemoteDatabaseSession> sessionPool,
      DatabaseSessionRemote database,
      String query,
      LiveQueryClientListener listener,
      Map<String, ?> params) {
    @SuppressWarnings("unchecked")
    var request =
        new SubscribeLiveQueryRequest(query, (Map<String, Object>) params);
    var response = pushThread.subscribe(request, getCurrentSession(database), sessionPool);
    if (response == null) {
      throw new DatabaseException(name,
          "Impossible to start the live query, check server log for additional information");
    }
    registerLiveListener(response.getMonitorId(), listener);
    return new YTLiveQueryMonitorRemote(sessionPool, response.getMonitorId());
  }

  public void unsubscribeLive(DatabaseSessionRemote database, int monitorId) {
    var request =
        new UnsubscribeRequest(new UnsubscribeLiveQueryRequest(monitorId));
    networkOperation(database, request, "Error on unsubscribe of live query");
  }

  public void registerLiveListener(int monitorId, LiveQueryClientListener listener) {
    liveQueryListener.put(monitorId, listener);
  }

  public static HashMap<String, Object> paramsArrayToParamsMap(Object[] positionalParams) {
    var params = new HashMap<String, Object>();
    if (positionalParams != null) {
      for (var i = 0; i < positionalParams.length; i++) {
        params.put(Integer.toString(i), positionalParams[i]);
      }
    }
    return params;
  }

  @Override
  public void executeLiveQueryPush(LiveQueryPushRequest pushRequest, SocketChannelBinary network) {
    try {
      pushRequest.readMonitorIdAndStatus(network);
      var listener = liveQueryListener.get(pushRequest.getMonitorId());

      if (listener != null) {
        try (var session = (RemoteDatabaseSessionInternal) listener.getPool().acquire()) {
          pushRequest.read(session, network);

          if (listener.onEvent(session, pushRequest)) {
            liveQueryListener.remove(pushRequest.getMonitorId());
          }
        }
      } else {
        if (logger.isWarnEnabled()) {
          logger.warn("Live query listener not found for monitorId: {}",
              pushRequest.getMonitorId());
        }
      }
    } catch (IOException e) {
      throw BaseException.wrapException(
          new DatabaseException(name, "Error on reading live query push request"), e, name);
    }
  }

  @Override
  public void onPushReconnect(String host) {
  }

  @Override
  public void onPushDisconnect(SocketChannelBinary network, Exception e) {
    if (this.connectionManager.getPool(((SocketChannelBinaryAsynchClient) network).getServerURL())
        != null) {
      this.connectionManager.remove((SocketChannelBinaryAsynchClient) network);
    }
    if (e instanceof java.lang.InterruptedException) {
      for (var liveListener : liveQueryListener.values()) {
        liveListener.onEnd();
      }
    } else {
      for (var liveListener : liveQueryListener.values()) {
        if (e instanceof BaseException) {
          liveListener.onError((BaseException) e);
        } else {
          liveListener.onError(
              BaseException.wrapException(new DatabaseException(name, "Live query disconnection "),
                  e, name));
        }
      }
    }
  }

  @Override
  public void returnSocket(SocketChannelBinary network) {
    this.connectionManager.remove((SocketChannelBinaryAsynchClient) network);
  }

  public STATUS getStatus() {
    return status;
  }

  public void close(DatabaseSessionRemote session) {
    close(session, false);
  }

  @Override
  public LiveQueryMonitor live(DatabasePoolInternal<RemoteDatabaseSession> sessionPool,
      String query,
      BasicLiveQueryResultListener<RemoteDatabaseSession, RemoteResult> listener,
      Map<String, ?> args) {
    try (var session = (DatabaseSessionRemote) sessionPool.acquire()) {
      return liveQuery(sessionPool,
          session, query, new LiveQueryClientListener(sessionPool, listener), args);
    }
  }

  @Override
  public LiveQueryMonitor live(DatabasePoolInternal<RemoteDatabaseSession> sessionPool,
      String query,
      BasicLiveQueryResultListener<RemoteDatabaseSession, RemoteResult> listener, Object... args) {
    try (var session = (DatabaseSessionRemote) sessionPool.acquire()) {
      return liveQuery(sessionPool,
          session, query, new LiveQueryClientListener(sessionPool, listener), args);
    }
  }

  public enum STATUS {
    CLOSED,
    OPEN,
    CLOSING,
  }
}
