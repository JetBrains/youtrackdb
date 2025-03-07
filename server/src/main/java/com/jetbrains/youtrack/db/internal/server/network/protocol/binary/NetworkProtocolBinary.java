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
package com.jetbrains.youtrack.db.internal.server.network.protocol.binary;

import com.jetbrains.youtrack.db.api.config.ContextConfiguration;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.SecurityAccessException;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.client.binary.BinaryRequestExecutor;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryRequest;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.BinaryProtocolHelper;
import com.jetbrains.youtrack.db.internal.client.remote.message.BinaryPushRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.BinaryPushResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.Error37Response;
import com.jetbrains.youtrack.db.internal.client.remote.message.ErrorResponse;
import com.jetbrains.youtrack.db.internal.common.concur.OfflineNodeException;
import com.jetbrains.youtrack.db.internal.common.concur.lock.LockException;
import com.jetbrains.youtrack.db.internal.common.concur.lock.ThreadInterruptedException;
import com.jetbrains.youtrack.db.internal.common.exception.ErrorCode;
import com.jetbrains.youtrack.db.internal.common.exception.InvalidBinaryChunkException;
import com.jetbrains.youtrack.db.internal.common.io.YTIOException;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseRecordThreadLocal;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.exception.CoreException;
import com.jetbrains.youtrack.db.internal.core.exception.SerializationException;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.RecordInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializer;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializerFactory;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.SerializationThreadLocal;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetworkFactory;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.string.RecordSerializerSchemaAware2CSV;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.sbtree.SBTreeCollectionManager;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.NetworkProtocolException;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.SocketChannelBinary;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.SocketChannelBinaryServer;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.TokenSecurityException;
import com.jetbrains.youtrack.db.internal.server.ClientConnection;
import com.jetbrains.youtrack.db.internal.server.ConnectionBinaryExecutor;
import com.jetbrains.youtrack.db.internal.server.OServerAware;
import com.jetbrains.youtrack.db.internal.server.YouTrackDBServer;
import com.jetbrains.youtrack.db.internal.server.distributed.DistributedException;
import com.jetbrains.youtrack.db.internal.server.distributed.DistributedRequest;
import com.jetbrains.youtrack.db.internal.server.distributed.DistributedResponse;
import com.jetbrains.youtrack.db.internal.server.distributed.ODistributedDatabase;
import com.jetbrains.youtrack.db.internal.server.distributed.ODistributedServerManager;
import com.jetbrains.youtrack.db.internal.server.monitoring.BinaryProtocolRequestEvent;
import com.jetbrains.youtrack.db.internal.server.network.ServerNetworkListener;
import com.jetbrains.youtrack.db.internal.server.network.protocol.NetworkProtocol;
import com.jetbrains.youtrack.db.internal.server.plugin.ServerPluginHelper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.function.Function;
import java.util.logging.Level;
import javax.annotation.Nonnull;

public class NetworkProtocolBinary extends NetworkProtocol {

  protected final Level logClientExceptions;
  protected final boolean logClientFullStackTrace;
  protected SocketChannelBinary channel;
  protected volatile int requestType;
  protected int clientTxId;
  protected boolean okSent;
  private boolean tokenConnection = true;
  private long requests = 0;
  private HandshakeInfo handshakeInfo;
  private volatile BinaryPushResponse expectedPushResponse;
  private final BlockingQueue<BinaryPushResponse> pushResponse =
      new SynchronousQueue<BinaryPushResponse>();

  private Function<Integer, BinaryRequest<? extends BinaryResponse>> factory =
      NetworkBinaryProtocolFactory.defaultProtocol();

  public NetworkProtocolBinary(YouTrackDBServer server) {
    this(server, "YouTrackDB <- BinaryClient/?");
  }

  public NetworkProtocolBinary(YouTrackDBServer server, final String iThreadName) {
    super(server.getThreadGroup(), iThreadName);
    logClientExceptions =
        Level.parse(
            server
                .getContextConfiguration()
                .getValueAsString(GlobalConfiguration.SERVER_LOG_DUMP_CLIENT_EXCEPTION_LEVEL));
    logClientFullStackTrace =
        server
            .getContextConfiguration()
            .getValueAsBoolean(
                GlobalConfiguration.SERVER_LOG_DUMP_CLIENT_EXCEPTION_FULLSTACKTRACE);
  }

  /**
   * Internal varialbe injection useful for testing.
   */
  public void initVariables(final YouTrackDBServer server, SocketChannelBinary channel) {
    this.server = server;
    this.channel = channel;
  }

  @Override
  public void config(
      final ServerNetworkListener iListener,
      final YouTrackDBServer iServer,
      final Socket iSocket,
      final ContextConfiguration iConfig)
      throws IOException {

    SocketChannelBinaryServer channel = new SocketChannelBinaryServer(iSocket, iConfig);
    initVariables(iServer, channel);

    // SEND PROTOCOL VERSION
    channel.writeShort((short) getVersion());

    channel.flush();

    ServerPluginHelper.invokeHandlerCallbackOnSocketAccepted(server, this);

    start();
    setName(
        "YouTrackDB ("
            + iSocket.getLocalSocketAddress()
            + ") <- BinaryClient ("
            + iSocket.getRemoteSocketAddress()
            + ")");
  }

  @Override
  public void startup() {
    super.startup();
  }

  @Override
  public void shutdown() {

    sendShutdown();
    channel.close();

    ServerPluginHelper.invokeHandlerCallbackOnSocketDestroyed(server, this);
  }

  private static boolean isHandshaking(int requestType) {
    return requestType == ChannelBinaryProtocol.REQUEST_CONNECT
        || requestType == ChannelBinaryProtocol.REQUEST_DB_OPEN
        || requestType == ChannelBinaryProtocol.REQUEST_SHUTDOWN
        || requestType == ChannelBinaryProtocol.REQUEST_DB_REOPEN
        || requestType == ChannelBinaryProtocol.DISTRIBUTED_CONNECT;
  }

  private static boolean isDistributed(int requestType) {
    return requestType == ChannelBinaryProtocol.DISTRIBUTED_REQUEST
        || requestType == ChannelBinaryProtocol.DISTRIBUTED_RESPONSE;
  }

  private static boolean isCoordinated(int requestType) {
    return requestType == ChannelBinaryProtocol.COORDINATED_DISTRIBUTED_MESSAGE;
  }

  @Override
  protected void execute() throws Exception {
    requestType = -1;

    if (server.rejectRequests()) {
      this.softShutdown();
      return;
    }
    // do not remove this or we will get deadlock upon shutdown.
    if (isShutdownFlag()) {
      return;
    }

    clientTxId = 0;
    okSent = false;
    try {
      channel.setWaitRequestTimeout();
      requestType = channel.readByte();
      channel.setReadRequestTimeout();

      if (server.rejectRequests()) {
        // MAKE SURE THAT IF THE SERVER IS GOING DOWN THE CONNECTIONS ARE TERMINATED BEFORE HANDLE
        // ANY OPERATIONS
        this.softShutdown();
        if (requestType != ChannelBinaryProtocol.REQUEST_HANDSHAKE
            && isDistributed(requestType)
            && requestType != ChannelBinaryProtocol.REQUEST_OK_PUSH) {
          clientTxId = channel.readInt();
          channel.clearInput();
          sendError(null, clientTxId, new OfflineNodeException("Node Shutting down"));
        }
        return;
      }

      if (requestType == ChannelBinaryProtocol.REQUEST_HANDSHAKE) {
        handleHandshake();
        return;
      }
      if (requestType == ChannelBinaryProtocol.REQUEST_OK_PUSH) {
        handlePushResponse();
        return;
      }

      clientTxId = channel.readInt();
      // GET THE CONNECTION IF EXIST
      ClientConnection connection =
          server.getClientConnectionManager().getConnection(clientTxId, this);
      if (connection != null) {
        connection.activateDatabaseOnCurrentThread();
      }

      if (isCoordinated(requestType)) {
        coordinatedRequest(connection, requestType, clientTxId);
      } else if (isDistributed(requestType)) {
        distributedRequest(connection, requestType, clientTxId);
      } else {
        sessionRequest(connection, requestType, clientTxId);
      }
    } catch (IOException e) {
      // if an exception arrive to this point we need to kill the current socket.
      sendShutdown();
      throw e;
    }
  }

  private void coordinatedRequest(ClientConnection connection, int requestType, int clientTxId)
      throws IOException {
    byte[] tokenBytes = channel.readBytes();
    connection = onBeforeOperationalRequest(connection, tokenBytes);
    ((OServerAware) server.getDatabases())
        .coordinatedRequest(connection, requestType, clientTxId, channel);
  }

  private void handleHandshake() throws IOException {
    short protocolVersion = channel.readShort();
    String driverName = channel.readString();
    String driverVersion = channel.readString();
    byte encoding = channel.readByte();
    byte errorEncoding = channel.readByte();
    BinaryProtocolHelper.checkProtocolVersion(this, protocolVersion);
    this.handshakeInfo =
        new HandshakeInfo(protocolVersion, driverName, driverVersion, encoding, errorEncoding);
    this.factory = NetworkBinaryProtocolFactory.matchProtocol(protocolVersion);
  }

  public void setHandshakeInfo(HandshakeInfo handshakeInfo) {
    this.handshakeInfo = handshakeInfo;
  }

  public boolean shouldReadToken(ClientConnection connection, int requestType) {
    if (handshakeInfo != null || requestType == ChannelBinaryProtocol.DISTRIBUTED_CONNECT) {
      return true;
    } else {
      if (connection == null) {
        return !isHandshaking(requestType)
            || requestType == ChannelBinaryProtocol.REQUEST_DB_REOPEN;
      } else {
        return Boolean.TRUE.equals(connection.getTokenBased()) && !isHandshaking(requestType);
      }
    }
  }

  private void sessionRequest(@Nonnull ClientConnection connection, int requestType,
      int clientTxId) {

    LogManager.instance().debug(this, "Request id:" + clientTxId + " type:" + requestType);

    final var event = new BinaryProtocolRequestEvent();
    event.begin();
    try {
      BinaryRequest<? extends BinaryResponse> request = factory.apply(requestType);
      if (request != null) {
        Exception exception = null;

        try {
          byte[] tokenBytes = null;
          if (shouldReadToken(connection, requestType)) {
            tokenBytes = channel.readBytes();
          }
          if (isHandshaking(requestType)) {
            connection = onBeforeHandshakeRequest(connection, tokenBytes);
          } else {
            connection = onBeforeOperationalRequest(connection, tokenBytes);
          }
          if (connection != null) {
            connection.getData().commandInfo = request.getDescription();
            connection.setProtocol(this); // This is need for the request command
          }
        } catch (RuntimeException | IOException ex) {
          exception = ex;
        }
        // Also in case of session validation error i read the message from the socket.
        try {
          int protocolVersion = ChannelBinaryProtocol.CURRENT_PROTOCOL_VERSION;
          RecordSerializer serializer =
              RecordSerializerNetworkFactory.INSTANCE.forProtocol(protocolVersion);
          if (connection != null) {
            protocolVersion = connection.getData().protocolVersion;
            serializer = connection.getData().getSerializer();

            request.read(connection.getDatabase(), channel, protocolVersion, serializer);
          } else {
            request.read(null, channel, protocolVersion, serializer);
          }

        } catch (IOException e) {
          connection.endOperation();
          LogManager.instance()
              .debug(
                  this, "I/O Error on client clientId=%d reqType=%d", clientTxId, requestType, e);
          sendShutdown();
          return;
        } catch (Throwable e) {
          if (connection != null) {
            connection.endOperation();
          }
          LogManager.instance().error(this, "Error reading request", e);
          sendShutdown();
          return;
        }

        BinaryResponse response = null;
        if (exception == null) {
          try {

            if (request.requireServerUser()) {
              checkServerAccess(connection.getDatabase(), request.requiredServerRole(), connection);
            }

            if (request.requireDatabaseSession()) {
              if (connection.getDatabase() == null) {
                throw new DatabaseException("Required database session");
              }
            }
            response = request.execute(connection.getExecutor());
          } catch (RuntimeException t) {
            // This should be moved in the execution of the command that manipulate data
            if (connection.getDatabase() != null) {
              final SBTreeCollectionManager collectionManager =
                  connection.getDatabase().getSbTreeCollectionManager();
              if (collectionManager != null) {
                collectionManager.clearChangedIds();
              }
            }
            exception = t;
          } catch (Throwable err) {
            sendShutdown();
            connection.release();
            throw err;
          }
        }
        if (exception != null) {
          // TODO: Replace this with build error response
          try {
            okSent = true;
            sendError(connection, clientTxId, exception);
          } catch (IOException e) {
            LogManager.instance()
                .debug(
                    this, "I/O Error on client clientId=%d reqType=%d", clientTxId, requestType, e);
            sendShutdown();
          } finally {
            afterOperationRequest(connection);
          }
        } else {
          try {
            if (response != null) {
              beginResponse();
              try {
                sendOk(connection, clientTxId);
                response.write(connection.getDatabase(),
                    channel,
                    connection.getData().protocolVersion, connection.getData().getSerializer());
              } finally {
                endResponse();
              }
            }
          } catch (InvalidBinaryChunkException e) {
            LogManager.instance()
                .warn(
                    this, "I/O Error on client clientId=%d reqType=%d", clientTxId, requestType, e);
            sendShutdown();
          } catch (IOException e) {
            LogManager.instance()
                .debug(
                    this, "I/O Error on client clientId=%d reqType=%d", clientTxId, requestType, e);
            sendShutdown();
          } catch (Exception | Error e) {
            LogManager.instance().error(this, "Error while binary response serialization", e);
            sendShutdown();
            throw e;
          } finally {
            afterOperationRequest(connection);
          }
        }
        tokenConnection = Boolean.TRUE.equals(connection.getTokenBased());
      } else {
        LogManager.instance().error(this, "Request not supported. Code: " + requestType, null);
        handleConnectionError(
            connection,
            new NetworkProtocolException("Request not supported. Code: " + requestType));
        sendShutdown();
      }

    } finally {
      event.commit();

      SerializationThreadLocal.INSTANCE.get().clear();
    }
  }

  private ClientConnection onBeforeHandshakeRequest(
      ClientConnection connection, byte[] tokenBytes) {
    try {
      if (requestType != ChannelBinaryProtocol.REQUEST_DB_REOPEN) {
        if (clientTxId >= 0
            && connection == null
            && (requestType == ChannelBinaryProtocol.REQUEST_DB_OPEN
            || requestType == ChannelBinaryProtocol.REQUEST_CONNECT
            || requestType == ChannelBinaryProtocol.DISTRIBUTED_CONNECT)) {
          // THIS EXCEPTION SHULD HAPPEN IN ANY CASE OF OPEN/CONNECT WITH SESSIONID >= 0, BUT FOR
          // COMPATIBILITY IT'S ONLY IF THERE
          // IS NO CONNECTION
          shutdown();
          throw new NetworkProtocolException("Found unknown session " + clientTxId);
        }
        connection = server.getClientConnectionManager().connect(this);
        connection.getData().sessionId = clientTxId;
        connection.setTokenBytes(null);
        connection.acquire();
      } else {
        connection.validateSession(tokenBytes, server.getTokenHandler(), this);
        server.getClientConnectionManager().disconnect(clientTxId);
        connection =
            server.getClientConnectionManager().reConnect(this, connection.getTokenBytes());
        connection.acquire();
        waitDistribuedIsOnline(connection);
        connection.init(server);

        if (connection.getData().serverUser) {
          connection.setServerUser(
              server.getSecurity()
                  .getUser(connection.getData().serverUsername, connection.getDatabase()));
        }
      }
    } catch (RuntimeException e) {
      if (connection != null) {
        server.getClientConnectionManager().disconnect(connection);
      }
      DatabaseRecordThreadLocal.instance().remove();
      throw e;
    }

    connection.statsUpdate();

    ServerPluginHelper.invokeHandlerCallbackOnBeforeClientRequest(
        server, connection, (byte) requestType);
    return connection;
  }

  private void distributedRequest(ClientConnection connection, int requestType, int clientTxId) {
    try {
      byte[] tokenBytes = channel.readBytes();
      connection = onBeforeOperationalRequest(connection, tokenBytes);
      LogManager.instance().debug(this, "Request id:" + clientTxId + " type:" + requestType);

      try {
        switch (requestType) {
          case ChannelBinaryProtocol.DISTRIBUTED_REQUEST:
            executeDistributedRequest(connection);
            break;

          case ChannelBinaryProtocol.DISTRIBUTED_RESPONSE:
            executeDistributedResponse(connection);
            break;
        }
      } finally {
        requests++;
        afterOperationRequest(connection);
      }

    } catch (Exception t) {
      // IN CASE OF DISTRIBUTED ANY EXCEPTION AT THIS POINT CAUSE THIS CONNECTION TO CLOSE
      LogManager.instance()
          .warn(
              this,
              "I/O Error on distributed channel  clientId=%d reqType=%d",
              t,
              clientTxId,
              requestType);
      sendShutdown();
    }
  }

  private ClientConnection onBeforeOperationalRequest(
      ClientConnection connection, byte[] tokenBytes) {
    try {
      if (connection == null && requestType == ChannelBinaryProtocol.REQUEST_DB_CLOSE) {
        return null;
      }

      if (handshakeInfo != null) {
        if (connection == null) {
          throw new TokenSecurityException("missing session and token");
        }
        connection.acquire();
        connection.validateSession(tokenBytes, server.getTokenHandler(), this);
        waitDistribuedIsOnline(connection);
        connection.init(server);
        if (connection.getData().serverUser) {
          connection.setServerUser(
              server.getSecurity()
                  .getUser(connection.getData().serverUsername, connection.getDatabase()));
        }
      } else {
        if (connection != null && !Boolean.TRUE.equals(connection.getTokenBased())) {
          // BACKWARD COMPATIBILITY MODE
          connection.setTokenBytes(null);
          connection.acquire();
        } else {
          // STANDARD FLOW
          if (!tokenConnection) {
            // ARRIVED HERE FOR DIRECT TOKEN CONNECTION, BUT OLD STYLE SESSION.
            throw new YTIOException("Found unknown session " + clientTxId);
          }
          if (connection == null && tokenBytes != null && tokenBytes.length > 0) {
            // THIS IS THE CASE OF A TOKEN OPERATION WITHOUT HANDSHAKE ON THIS CONNECTION.
            connection = server.getClientConnectionManager().connect(this);
            connection.setDisconnectOnAfter(true);
          }
          if (connection == null) {
            throw new TokenSecurityException("missing session and token");
          }
          connection.acquire();
          connection.validateSession(tokenBytes, server.getTokenHandler(), this);
          waitDistribuedIsOnline(connection);
          connection.init(server);
          if (connection.getData().serverUser) {
            connection.setServerUser(
                server.getSecurity()
                    .getUser(connection.getData().serverUsername, connection.getDatabase()));
          }
        }
      }

      connection.statsUpdate();
      ServerPluginHelper.invokeHandlerCallbackOnBeforeClientRequest(
          server, connection, (byte) requestType);
    } catch (Throwable e) {
      if (connection != null) {
        connection.endOperation();
        server.getClientConnectionManager().disconnect(connection);
      }
      DatabaseRecordThreadLocal.instance().remove();
      throw e;
    }
    return connection;
  }

  private void waitDistribuedIsOnline(ClientConnection connection) {
    if (requests == 0) {
      final ODistributedServerManager manager = server.getDistributedManager();
      if (manager != null && connection.hasDatabase()) {
        try {
          if (manager.getMessageService() != null) {
            String databaseName = connection.getDatabaseName();
            final ODistributedDatabase dDatabase = manager.getDatabase(databaseName);
            if (dDatabase != null) {
              dDatabase.waitForOnline();
            } else {
              manager.waitUntilNodeOnline(manager.getLocalNodeName(), databaseName);
            }
          }
        } catch (java.lang.InterruptedException e) {
          Thread.currentThread().interrupt();
          throw BaseException.wrapException(new ThreadInterruptedException("Request interrupted"),
              e);
        }
      }
    }
  }

  protected void afterOperationRequest(ClientConnection connection) {
    requests++;
    ServerPluginHelper.invokeHandlerCallbackOnAfterClientRequest(
        server, connection, (byte) requestType);

    if (connection != null) {
      setDataCommandInfo(connection, "Listening");
      connection.endOperation();
      if (connection.isDisconnectOnAfter()) {
        server.getClientConnectionManager().disconnect(connection);
      }
    }
  }

  protected void checkServerAccess(DatabaseSessionInternal session, final String iResource,
      ClientConnection connection) {
    if (connection.getData().protocolVersion <= ChannelBinaryProtocol.PROTOCOL_VERSION_26) {
      if (connection.getServerUser() == null) {
        throw new SecurityAccessException("Server user not authenticated");
      }

      if (!server.getSecurity()
          .isAuthorized(session, connection.getServerUser().getName(session), iResource)) {
        throw new SecurityAccessException(
            "User '"
                + connection.getServerUser().getName(session)
                + "' cannot access to the resource ["
                + iResource
                + "]. Use another server user or change permission in the file"
                + " config/youtrackdb-server-config.xml");
      }
    } else {
      if (!connection.getData().serverUser) {
        throw new SecurityAccessException("Server user not authenticated");
      }

      if (!server.getSecurity()
          .isAuthorized(session, connection.getData().serverUsername, iResource)) {
        throw new SecurityAccessException(
            "User '"
                + connection.getData().serverUsername
                + "' cannot access to the resource ["
                + iResource
                + "]. Use another server user or change permission in the file"
                + " config/youtrackdb-server-config.xml");
      }
    }
  }

  private void executeDistributedRequest(ClientConnection connection) throws IOException {
    setDataCommandInfo(connection, "Distributed request");

    checkServerAccess(connection.getDatabase(), "server.replication", connection);

    final ODistributedServerManager manager = server.getDistributedManager();
    final DistributedRequest req = new DistributedRequest(manager);

    req.fromStream(channel.getDataInput());

    final String dbName = req.getDatabaseName();
    ODistributedDatabase ddb = null;
    if (dbName != null) {
      ddb = manager.getDatabase(dbName);
      if (ddb == null && req.getTask().isNodeOnlineRequired()) {
        throw new DistributedException(
            "Database configuration not found for database '" + req.getDatabaseName() + "'");
      }
    }

    // SET THE SENDER IN THE TASK
    String senderNodeName = manager.getNodeNameById(req.getId().getNodeId());
    req.getTask().setNodeSource(senderNodeName);

    if (ddb != null) {
      ddb.processRequest(req, true);
    } else {
      manager.executeOnLocalNodeFromRemote(req);
    }
  }

  private void executeDistributedResponse(ClientConnection connection) throws IOException {
    setDataCommandInfo(connection, "Distributed response");

    checkServerAccess(connection.getDatabase(), "server.replication", connection);

    final ODistributedServerManager manager = server.getDistributedManager();
    final DistributedResponse response = new DistributedResponse();

    response.fromStream(channel.getDataInput());

    // WHILE MSG SERVICE IS UP & RUNNING
    while (manager.getMessageService() == null) {
      try {
        Thread.sleep(100);
      } catch (java.lang.InterruptedException e) {
        return;
      }
    }

    manager.getMessageService().dispatchResponseToThread(response);
  }

  protected void sendError(
      final ClientConnection connection, final int iClientTxId, final Throwable t)
      throws IOException {
    channel.acquireWriteLock();
    try {

      channel.writeByte(ChannelBinaryProtocol.RESPONSE_STATUS_ERROR);
      channel.writeInt(iClientTxId);
      if (handshakeInfo != null) {
        byte[] renewedToken = null;
        if (connection != null && connection.getToken() != null) {
          renewedToken = server.getTokenHandler().renewIfNeeded(connection.getToken());
          if (renewedToken.length > 0) {
            connection.setTokenBytes(renewedToken);
          }
        }
        channel.writeBytes(renewedToken);
        channel.writeByte((byte) requestType);
      } else {
        if (tokenConnection
            && requestType != ChannelBinaryProtocol.REQUEST_CONNECT
            && (requestType != ChannelBinaryProtocol.REQUEST_DB_OPEN
            && requestType != ChannelBinaryProtocol.DISTRIBUTED_CONNECT
            && requestType != ChannelBinaryProtocol.REQUEST_SHUTDOWN
            || (connection != null
            && connection.getData() != null
            && connection.getData().protocolVersion
            <= ChannelBinaryProtocol.PROTOCOL_VERSION_32))
            || requestType == ChannelBinaryProtocol.REQUEST_DB_REOPEN) {
          // TODO: Check if the token is expiring and if it is send a new token

          if (connection != null && connection.getToken() != null) {
            byte[] renewedToken = server.getTokenHandler().renewIfNeeded(connection.getToken());
            channel.writeBytes(renewedToken);
          } else {
            channel.writeBytes(new byte[]{});
          }
        }
      }
      final Throwable current;
      if (t instanceof BaseException
          && t.getCause() instanceof java.lang.InterruptedException
          && !server.isActive()) {
        current = new OfflineNodeException("Node shutting down");
      } else if (t instanceof LockException && t.getCause() instanceof DatabaseException)
      // BYPASS THE DB POOL EXCEPTION TO PROPAGATE THE RIGHT SECURITY ONE
      {
        current = t.getCause();
      } else {
        current = t;
      }

      Map<String, String> messages = new HashMap<>();
      Throwable it = current;
      while (it != null) {
        messages.put(current.getClass().getName(), current.getMessage());
        it = it.getCause();
      }
      final byte[] result;
      if (handshakeInfo == null
          || handshakeInfo.getErrorEncoding() == ChannelBinaryProtocol.ERROR_MESSAGE_JAVA) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        final ObjectOutputStream objectOutputStream = new ObjectOutputStream(outputStream);
        objectOutputStream.writeObject(current);
        objectOutputStream.flush();
        objectOutputStream.close();
        result = outputStream.toByteArray();
      } else if (handshakeInfo.getErrorEncoding() == ChannelBinaryProtocol.ERROR_MESSAGE_STRING) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        current.printStackTrace(new PrintStream(outputStream));
        result = outputStream.toByteArray();
      } else {
        result = new byte[]{};
      }
      BinaryResponse error;
      if (handshakeInfo != null) {
        ErrorCode code;
        if (current instanceof CoreException) {
          code = ((CoreException) current).getErrorCode();
          if (code == null) {
            code = ErrorCode.GENERIC_ERROR;
          }
        } else {
          code = ErrorCode.GENERIC_ERROR;
        }
        error = new Error37Response(code, 0, messages, result);
      } else {
        error = new ErrorResponse(messages, result);
      }
      int protocolVersion = ChannelBinaryProtocol.CURRENT_PROTOCOL_VERSION;
      RecordSerializer serializationImpl = RecordSerializerNetworkFactory.INSTANCE.current();
      if (connection != null) {
        protocolVersion = connection.getData().protocolVersion;
        serializationImpl = connection.getData().getSerializer();
        error.write(connection.getDatabase(), channel, protocolVersion, serializationImpl);
      } else {
        error.write(null, channel, protocolVersion, serializationImpl);
      }

      channel.flush();

      if (LogManager.instance().isLevelEnabled(logClientExceptions)) {
        if (logClientFullStackTrace) {
          assert t != null;
          LogManager.instance()
              .log(
                  this,
                  LogManager.fromJulToSLF4JLevel(logClientExceptions),
                  "Sent run-time exception to the client %s: %s",
                  t,
                  channel.socket.getRemoteSocketAddress(),
                  t.toString());
        } else {
          assert t != null;
          LogManager.instance()
              .log(
                  this,
                  LogManager.fromJulToSLF4JLevel(logClientExceptions),
                  "Sent run-time exception to the client %s: %s",
                  null,
                  channel.socket.getRemoteSocketAddress(),
                  t.toString());
        }
      }
    } catch (Exception e) {
      if (e instanceof SocketException) {
        shutdown();
      } else {
        LogManager.instance().error(this, "Error during sending an error to client", e);
      }
    } finally {
      if (channel.getLockWrite().isHeldByCurrentThread())
      // NO EXCEPTION SO FAR: UNLOCK IT
      {
        channel.releaseWriteLock();
      }
    }
  }

  protected void beginResponse() {
    channel.acquireWriteLock();
  }

  protected void endResponse() throws IOException {
    channel.flush();
    channel.releaseWriteLock();
  }

  protected void setDataCommandInfo(ClientConnection connection, final String iCommandInfo) {
    if (connection != null) {
      connection.getData().commandInfo = iCommandInfo;
    }
  }

  protected void sendOk(ClientConnection connection, final int iClientTxId) throws IOException {
    channel.writeByte(ChannelBinaryProtocol.RESPONSE_STATUS_OK);
    channel.writeInt(iClientTxId);
    okSent = true;
    if (handshakeInfo != null) {
      byte[] renewedToken = null;
      if (connection != null && connection.getToken() != null) {
        renewedToken = server.getTokenHandler().renewIfNeeded(connection.getToken());
        if (renewedToken.length > 0) {
          connection.setTokenBytes(renewedToken);
        }
      }
      channel.writeBytes(renewedToken);
      channel.writeByte((byte) requestType);
    } else {
      if (connection != null
          && Boolean.TRUE.equals(connection.getTokenBased())
          && connection.getToken() != null
          && requestType != ChannelBinaryProtocol.REQUEST_CONNECT
          && requestType != ChannelBinaryProtocol.DISTRIBUTED_CONNECT
          && requestType != ChannelBinaryProtocol.REQUEST_DB_OPEN) {
        // TODO: Check if the token is expiring and if it is send a new token
        byte[] renewedToken = server.getTokenHandler().renewIfNeeded(connection.getToken());
        channel.writeBytes(renewedToken);
      }
    }
  }

  protected void handleConnectionError(ClientConnection connection, final Throwable e) {
    try {
      channel.flush();
    } catch (IOException e1) {
      LogManager.instance().debug(this, "Error during channel flush", e1);
    }
    LogManager.instance().error(this, "Error executing request", e);
    ServerPluginHelper.invokeHandlerCallbackOnClientError(server, connection, e);
  }

  public static String getRecordSerializerName(ClientConnection connection) {
    return connection.getData().getSerializationImpl();
  }

  @Override
  public int getVersion() {
    return ChannelBinaryProtocol.CURRENT_PROTOCOL_VERSION;
  }

  @Override
  public SocketChannelBinary getChannel() {
    return channel;
  }

  /**
   * Write a Identifiable instance using this format:<br> - 2 bytes: class id [-2=no record, -3=rid,
   * -1=no class id, > -1 = valid] <br> - 1 byte: record type [d,b,f] <br> - 2 bytes: cluster id
   * <br> - 8 bytes: position in cluster <br> - 4 bytes: record version <br> - x bytes: record
   * content <br>
   *
   * @param channel TODO
   */
  public static void writeIdentifiable(
      SocketChannelBinary channel, ClientConnection connection, final Identifiable o)
      throws IOException {
    if (o == null) {
      channel.writeShort(ChannelBinaryProtocol.RECORD_NULL);
    } else if (o instanceof RecordId) {
      channel.writeShort(ChannelBinaryProtocol.RECORD_RID);
      channel.writeRID((RID) o);
    } else {
      writeRecord(channel, connection, o.getRecord());
    }
  }

  public String getType() {
    return "binary";
  }

  protected void sendErrorOrDropConnection(
      ClientConnection connection, final int iClientTxId, final Throwable t) throws IOException {
    if (okSent || requestType == ChannelBinaryProtocol.REQUEST_DB_CLOSE) {
      handleConnectionError(connection, t);
      sendShutdown();
    } else {
      okSent = true;
      sendError(connection, iClientTxId, t);
    }
  }

  public static byte[] getRecordBytes(ClientConnection connection,
      final RecordAbstract iRecord) {
    final byte[] stream;
    String dbSerializerName = null;
    if (DatabaseRecordThreadLocal.instance().getIfDefined() != null) {
      dbSerializerName = (iRecord.getSession()).getSerializer().toString();
    }
    String name = connection.getData().getSerializationImpl();
    if (RecordInternal.getRecordType(iRecord) == EntityImpl.RECORD_TYPE
        && (dbSerializerName == null || !dbSerializerName.equals(name))) {
      ((EntityImpl) iRecord).deserializeFields();
      RecordSerializer ser = RecordSerializerFactory.instance().getFormat(name);
      stream = ser.toStream(connection.getDatabase(), iRecord);
    } else {
      stream = iRecord.toStream();
    }

    return stream;
  }

  private static void writeRecord(
      SocketChannelBinary channel, ClientConnection connection, final RecordAbstract iRecord)
      throws IOException {
    channel.writeShort((short) 0);
    channel.writeByte(RecordInternal.getRecordType(iRecord));
    channel.writeRID(iRecord.getIdentity());
    channel.writeVersion(iRecord.getVersion());
    try {
      final byte[] stream = getRecordBytes(connection, iRecord);

      // TODO: This Logic should not be here provide an api in the Serializer if asked for trimmed
      // content.
      int realLength = trimCsvSerializedContent(connection, stream);

      channel.writeBytes(stream, realLength);
    } catch (Exception e) {
      channel.writeBytes(null);
      final String message =
          "Error on unmarshalling record " + iRecord.getIdentity().toString() + " (" + e + ")";

      throw BaseException.wrapException(new SerializationException(message), e);
    }
  }

  protected static int trimCsvSerializedContent(ClientConnection connection, final byte[] stream) {
    int realLength = stream.length;
    final DatabaseSessionInternal db = DatabaseRecordThreadLocal.instance().getIfDefined();
    if (db != null) {
      if (RecordSerializerSchemaAware2CSV.NAME.equals(
          connection.getData().getSerializationImpl())) {
        // TRIM TAILING SPACES (DUE TO OVERSIZE)
        for (int i = stream.length - 1; i > -1; --i) {
          if (stream[i] == 32) {
            --realLength;
          } else {
            break;
          }
        }
      }
    }
    return realLength;
  }

  public int getRequestType() {
    return requestType;
  }

  public String getRemoteAddress() {
    final Socket socket = channel.socket;
    if (socket != null) {
      final InetSocketAddress remoteAddress = (InetSocketAddress) socket.getRemoteSocketAddress();
      return remoteAddress.getAddress().getHostAddress() + ":" + remoteAddress.getPort();
    }
    return null;
  }

  @Override
  public BinaryRequestExecutor executor(ClientConnection connection) {
    return new ConnectionBinaryExecutor(connection, server, handshakeInfo);
  }

  public BinaryPushResponse push(DatabaseSessionInternal session, BinaryPushRequest request)
      throws IOException {
    expectedPushResponse = request.createResponse();
    channel.acquireWriteLock();
    try {
      channel.writeByte(ChannelBinaryProtocol.PUSH_DATA);
      channel.writeByte(request.getPushCommand());
      request.write(session, channel);
      channel.flush();
      if (expectedPushResponse != null) {
        try {
          return pushResponse.take();
        } catch (java.lang.InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    } finally {
      channel.releaseWriteLock();
    }
    return null;
  }

  private void handlePushResponse() throws IOException {
    expectedPushResponse.read(channel);
    this.pushResponse.offer(expectedPushResponse);
  }
}
