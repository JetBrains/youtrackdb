package com.jetbrains.youtrack.db.internal.server;

import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.ConfigurationException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.SecurityAccessException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.internal.client.binary.BinaryRequestExecutor;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.CloseQueryRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.CloseQueryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.CloseRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.Connect37Request;
import com.jetbrains.youtrack.db.internal.client.remote.message.ConnectResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.CreateDatabaseRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.CreateDatabaseResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.DropDatabaseRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.DropDatabaseResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.ExistsDatabaseRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.ExistsDatabaseResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.FreezeDatabaseRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.FreezeDatabaseResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.GetGlobalConfigurationRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.GetGlobalConfigurationResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.ImportRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.ImportResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.IncrementalBackupRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.IncrementalBackupResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.ListDatabasesRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.ListDatabasesResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.ListGlobalConfigurationsRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.ListGlobalConfigurationsResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.Open37Request;
import com.jetbrains.youtrack.db.internal.client.remote.message.Open37Response;
import com.jetbrains.youtrack.db.internal.client.remote.message.QueryNextPageRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.QueryRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.QueryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.ReleaseDatabaseRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.ReleaseDatabaseResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.ReopenRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.ReopenResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.ServerInfoRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.ServerInfoResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.ServerQueryRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.ServerQueryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.SetGlobalConfigurationRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.SetGlobalConfigurationResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.ShutdownRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.ShutdownResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.SubscribeLiveQueryRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.SubscribeLiveQueryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.SubscribeRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.SubscribeResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.UnsubscribLiveQueryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.UnsubscribeLiveQueryRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.UnsubscribeRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.UnsubscribeResponse;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.util.CommonConst;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseImport;
import com.jetbrains.youtrack.db.internal.core.query.live.LiveQueryHookV2;
import com.jetbrains.youtrack.db.internal.core.sql.parser.LocalResultSetLifecycleDecorator;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import com.jetbrains.youtrack.db.internal.server.network.protocol.binary.HandshakeInfo;
import com.jetbrains.youtrack.db.internal.server.network.protocol.binary.NetworkProtocolBinary;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ConnectionBinaryExecutor implements BinaryRequestExecutor {

  private static final Logger logger = LoggerFactory.getLogger(ConnectionBinaryExecutor.class);

  private final ClientConnection connection;
  private final YouTrackDBServer server;
  private final HandshakeInfo handshakeInfo;

  public ConnectionBinaryExecutor(
      ClientConnection connection, YouTrackDBServer server, HandshakeInfo handshakeInfo) {
    this.connection = connection;
    this.server = server;
    this.handshakeInfo = handshakeInfo;
  }

  @Override
  public BinaryResponse executeServerInfo(ServerInfoRequest request) {
    try {
      return new ServerInfoResponse(ServerInfo.getServerInfo(server));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public BinaryResponse executeCreateDatabase(CreateDatabaseRequest request) {

    if (server.existsDatabase(request.getDatabaseName())) {
      throw new DatabaseException(
          "Database named '" + request.getDatabaseName() + "' already exists");
    }
    if (request.getBackupPath() != null && !request.getBackupPath().trim().isEmpty()) {
      server.restore(request.getDatabaseName(), request.getBackupPath());
    } else {
      server.createDatabase(
          request.getDatabaseName(),
          DatabaseType.valueOf(request.getStorageMode().toUpperCase(Locale.ENGLISH)),
          null);
    }
    LogManager.instance()
        .info(
            this,
            "Created database '%s' of type '%s'",
            request.getDatabaseName(),
            request.getStorageMode());

    // TODO: it should be here an additional check for open with the right user
    connection.setSession(
        server
            .getDatabases()
            .openNoAuthenticate(request.getDatabaseName(),
                connection.getServerUser().getName(null)));

    return new CreateDatabaseResponse();
  }

  @Nullable
  @Override
  public BinaryResponse executeClose(CloseRequest request) {
    server.getClientConnectionManager().disconnect(connection);
    return null;
  }

  @Override
  public BinaryResponse executeExistDatabase(ExistsDatabaseRequest request) {
    var result = server.existsDatabase(request.getDatabaseName());
    return new ExistsDatabaseResponse(result);
  }

  @Override
  public BinaryResponse executeDropDatabase(DropDatabaseRequest request) {

    server.dropDatabase(request.getDatabaseName());
    LogManager.instance().info(this, "Dropped database '%s'", request.getDatabaseName());
    connection.close();
    return new DropDatabaseResponse();
  }

  @Override
  public BinaryResponse executeGetGlobalConfiguration(GetGlobalConfigurationRequest request) {
    final var cfg = GlobalConfiguration.findByKey(request.getKey());
    var cfgValue = cfg != null ? cfg.isHidden() ? "<hidden>" : cfg.getValueAsString() : "";
    return new GetGlobalConfigurationResponse(cfgValue);
  }

  @Override
  public BinaryResponse executeListGlobalConfigurations(ListGlobalConfigurationsRequest request) {
    Map<String, String> configs = new HashMap<>();
    for (var cfg : GlobalConfiguration.values()) {
      String key;
      try {
        key = cfg.getKey();
      } catch (Exception e) {
        key = "?";
      }

      String value;
      if (cfg.isHidden()) {
        value = "<hidden>";
      } else {
        try {
          var config =
              connection.getProtocol().getServer().getContextConfiguration();
          value = config.getValueAsString(cfg) != null ? config.getValueAsString(cfg) : "";
        } catch (Exception e) {
          value = "";
        }
      }
      configs.put(key, value);
    }
    return new ListGlobalConfigurationsResponse(configs);
  }

  @Override
  public BinaryResponse executeFreezeDatabase(FreezeDatabaseRequest request) {
    var database =
        server
            .getDatabases()
            .openNoAuthenticate(request.getName(), connection.getServerUser().getName(null));
    connection.setSession(database);

    LogManager.instance()
        .info(this, "Freezing database '%s'", connection.getDatabaseSession().getURL());

    connection.getDatabaseSession().freeze(true);
    return new FreezeDatabaseResponse();
  }

  @Override
  public BinaryResponse executeReleaseDatabase(ReleaseDatabaseRequest request) {
    var database =
        server
            .getDatabases()
            .openNoAuthenticate(request.getName(), connection.getServerUser().getName(null));

    connection.setSession(database);

    LogManager.instance()
        .info(this, "Realising database '%s'", connection.getDatabaseSession().getURL());

    connection.getDatabaseSession().release();
    return new ReleaseDatabaseResponse();
  }

  @Override
  public BinaryResponse executeIncrementalBackup(IncrementalBackupRequest request) {
    var fileName = connection.getDatabaseSession()
        .incrementalBackup(Path.of(request.getBackupDirectory()));
    return new IncrementalBackupResponse(fileName);
  }

  @Override
  public BinaryResponse executeImport(ImportRequest request) {
    List<String> result = new ArrayList<>();
    LogManager.instance().info(this, "Starting database import");
    DatabaseImport imp;
    var session = connection.getDatabaseSession();
    try {
      imp =
          new DatabaseImport(
              session,
              request.getImporPath(),
              iText -> {
                LogManager.instance().debug(ConnectionBinaryExecutor.this, iText, logger);
                result.add(iText);
              });
      imp.setOptions(request.getOptions());
      imp.importDatabase();
      imp.close();
      new File(request.getImporPath()).delete();

    } catch (IOException e) {
      throw BaseException.wrapException(new DatabaseException(session, "error on import"), e,
          session);
    }
    return new ImportResponse(result);
  }

  @Override
  public BinaryResponse executeConnect37(Connect37Request request) {
    connection.getData().driverName = handshakeInfo.getDriverName();
    connection.getData().driverVersion = handshakeInfo.getDriverVersion();
    connection.getData().protocolVersion = handshakeInfo.getProtocolVersion();

    connection.setTokenBased(true);
    connection.getData().supportsLegacyPushMessages = false;
    connection.getData().collectStats = true;

    connection.setServerUser(
        server.authenticateUser(request.getUsername(), request.getPassword(), "server.connect"));

    if (connection.getServerUser() == null) {
      throw new SecurityAccessException(
          "Wrong user/password to [connect] to the remote YouTrackDB Server instance");
    }

    byte[] token = null;
    if (connection.getData().protocolVersion > ChannelBinaryProtocol.PROTOCOL_VERSION_26) {
      connection.getData().serverUsername = connection.getServerUser().getName(null);
      connection.getData().serverUser = true;

      if (Boolean.TRUE.equals(connection.getTokenBased())) {
        token = server.getTokenHandler().getSignedBinaryToken(null, null, connection.getData());
      } else {
        token = CommonConst.EMPTY_BYTE_ARRAY;
      }
    }

    return new ConnectResponse(connection.getId(), token);
  }

  @Override
  public BinaryResponse executeDatabaseOpen37(Open37Request request) {
    connection.setTokenBased(true);
    connection.getData().supportsLegacyPushMessages = false;
    connection.getData().collectStats = true;
    connection.getData().driverName = handshakeInfo.getDriverName();
    connection.getData().driverVersion = handshakeInfo.getDriverVersion();
    connection.getData().protocolVersion = handshakeInfo.getProtocolVersion();

    var session =
        server.openSession(
            request.getDatabaseName(),
            request.getUserName(),
            request.getUserPassword(),
            connection.getData());
    try {
      connection.setSession(session);
    } catch (BaseException e) {
      server.getClientConnectionManager().disconnect(connection);
      throw e;
    }

    var token =
        server
            .getTokenHandler()
            .getSignedBinaryToken(
                connection.getDatabaseSession(), connection.getDatabaseSession().getCurrentUser(),
                connection.getData());
    // TODO: do not use the parse split getSignedBinaryToken in two methods.
    server.getClientConnectionManager().connect(connection.getProtocol(), connection, token);

    return new Open37Response(connection.getId(), token,
        session.getStorageInfo().getConfiguration().getTimeZone());
  }

  @Override
  public BinaryResponse executeShutdown(ShutdownRequest request) {

    LogManager.instance().info(this, "Received shutdown command from the remote client ");

    final var user = request.getRootUser();
    final var passwd = request.getRootPassword();

    if (server.authenticate(user, passwd, "server.shutdown")) {
      LogManager.instance()
          .info(this, "Remote client authenticated. Starting shutdown of server...");

      runShutdownInNonDaemonThread();

      return new ShutdownResponse();
    }

    LogManager.instance()
        .error(this, "Authentication error of remote client: shutdown is aborted.", null);

    throw new SecurityAccessException("Invalid user/password to shutdown the server");
  }

  private void runShutdownInNonDaemonThread() {
    var shutdownThread =
        new Thread("YouTrackDB server shutdown thread") {
          public void run() {
            server.shutdown();
          }
        };
    shutdownThread.setDaemon(false);
    shutdownThread.start();
  }

  @Override
  public BinaryResponse executeReopen(ReopenRequest request) {
    return new ReopenResponse(connection.getId(),
        connection.getDatabaseSession().getStorageInfo().getConfiguration().getTimeZone());
  }

  @Override
  public BinaryResponse executeSetGlobalConfig(SetGlobalConfigurationRequest request) {

    final var cfg = GlobalConfiguration.findByKey(request.getKey());

    if (cfg != null) {
      cfg.setValue(request.getValue());
      if (!cfg.isChangeableAtRuntime()) {
        throw new ConfigurationException(
            "Property '"
                + request.getKey()
                + "' cannot be changed at runtime. Change the setting at startup");
      }
    } else {
      throw new ConfigurationException(
          "Property '" + request.getKey() + "' was not found in global configuration");
    }

    return new SetGlobalConfigurationResponse();
  }

  @Override
  public BinaryResponse executeServerQuery(ServerQueryRequest request) {
    var youTrackDB = server.getContext();

    ResultSet rs;

    if (request.isNamedParams()) {
      rs = youTrackDB.execute(request.getStatement(), request.getNamedParameters());
    } else {
      rs = youTrackDB.execute(request.getStatement(), request.getPositionalParameters());
    }

    // copy the result-set to make sure that the execution is successful
    var rsCopy = rs.stream().collect(Collectors.<Result>toList());

    return new ServerQueryResponse(
        ((LocalResultSetLifecycleDecorator) rs).getQueryId(),
        rsCopy,
        false
    );
  }

  @Override
  public BinaryResponse executeQuery(QueryRequest request) {
    var database = connection.getDatabaseSession();
    var metadataListener = new QueryMetadataUpdateListener();
    database.getSharedContext().registerListener(metadataListener);
    ResultSet rs;
    if (QueryRequest.QUERY == request.getOperationType()) {
      // TODO Assert is sql.
      if (request.isNamedParams()) {
        rs = database.query(request.getStatement(), request.getNamedParameters());
      } else {
        rs = database.query(request.getStatement(), request.getPositionalParameters());
      }
    } else {
      if (QueryRequest.COMMAND == request.getOperationType()) {
        if (request.isNamedParams()) {
          rs = database.execute(request.getStatement(), request.getNamedParameters());
        } else {
          rs = database.execute(request.getStatement(), request.getPositionalParameters());
        }
      } else {
        if (request.isNamedParams()) {
          //noinspection unchecked
          rs =
              database.computeScript(
                  request.getLanguage(), request.getStatement(),
                  request.getNamedParameters());
        } else {
          rs =
              database.computeScript(
                  request.getLanguage(), request.getStatement(),
                  request.getPositionalParameters());
        }
      }
    }

    // copy the result-set to make sure that the execution is successful
    var stream = rs.stream();
    if (database
        .getActiveQueries()
        .containsKey(((LocalResultSetLifecycleDecorator) rs).getQueryId())) {
      stream = stream.limit(request.getRecordsPerPage());
    }
    var rsCopy = stream.collect(Collectors.toList());

    var hasNext = rs.hasNext();
    database.getSharedContext().unregisterListener(metadataListener);

    return new QueryResponse(
        ((LocalResultSetLifecycleDecorator) rs).getQueryId(), rsCopy, hasNext);
  }

  @Override
  public BinaryResponse closeQuery(CloseQueryRequest oQueryRequest) {
    var queryId = oQueryRequest.getQueryId();
    var db = connection.getDatabaseSession();
    var query = db.getActiveQuery(queryId);
    if (query != null) {
      query.close();
    }
    return new CloseQueryResponse();
  }

  @Override
  public BinaryResponse executeQueryNextPage(QueryNextPageRequest request) {
    var session = connection.getDatabaseSession();
    var youTrackDB = session.getSharedContext().getYouTrackDB();
    var rs =
        (LocalResultSetLifecycleDecorator) session.getActiveQuery(request.getQueryId());

    if (rs == null) {
      throw new DatabaseException(session,
          String.format(
              "No query with id '%s' found probably expired session", request.getQueryId()));
    }

    try {
      youTrackDB.startCommand(null);
      // copy the result-set to make sure that the execution is successful
      var rsCopy = new ArrayList<Result>(request.getRecordsPerPage());
      var i = 0;
      // if it's InternalResultSet it means that it's a Command, not a Query, so the result has to
      // be
      // sent as it is, not streamed
      while (rs.hasNext() && (rs.isDetached() || i < request.getRecordsPerPage())) {
        rsCopy.add(rs.next());
        i++;
      }
      var hasNext = rs.hasNext();
      return new QueryResponse(
          rs.getQueryId(),
          rsCopy,
          hasNext);
    } finally {
      youTrackDB.endCommand();
    }
  }

  @Override
  public BinaryResponse executeSubscribe(SubscribeRequest request) {
    return new SubscribeResponse(request.getPushRequest().execute(this));
  }

  @Override
  public BinaryResponse executeUnsubscribe(UnsubscribeRequest request) {
    return new UnsubscribeResponse(request.getUnsubscribeRequest().execute(this));
  }

  @Override
  public BinaryResponse executeUnsubscribeLiveQuery(UnsubscribeLiveQueryRequest request) {
    var database = connection.getDatabaseSession();
    LiveQueryHookV2.unsubscribe(request.getMonitorId(), database);
    return new UnsubscribLiveQueryResponse();
  }

  @Override
  public BinaryResponse executeSubscribeLiveQuery(SubscribeLiveQueryRequest request) {
    var protocol = (NetworkProtocolBinary) connection.getProtocol();
    var listener =
        new ServerLiveQueryResultListener(protocol,
            connection.getDatabaseSession().getSharedContext());

    var session = connection.getDatabaseSession();
    var monitor = session.live(request.getQuery(), listener, request.getParams());
    listener.setMonitorId(monitor.getMonitorId());

    return new SubscribeLiveQueryResponse(monitor.getMonitorId());
  }

  @Override
  public ListDatabasesResponse executeListDatabases(ListDatabasesRequest request) {
    var dbs = server.listDatabases();

    var listener =
        server.getListenerByProtocol(NetworkProtocolBinary.class).getInboundAddr().toString();

    Map<String, String> toSend = new HashMap<>();
    for (var dbName : dbs) {
      toSend.put(dbName, "remote:" + listener + "/" + dbName);
    }

    return new ListDatabasesResponse(toSend);
  }
}
