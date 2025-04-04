package com.jetbrains.youtrack.db.internal.server;

import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.ConfigurationException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.exception.SecurityAccessException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.client.binary.BinaryRequestExecutor;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.AddCollectionRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.AddCollectionResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.BeginTransaction38Request;
import com.jetbrains.youtrack.db.internal.client.remote.message.BeginTransactionResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.BinaryProtocolHelper;
import com.jetbrains.youtrack.db.internal.client.remote.message.CeilingPhysicalPositionsRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.CeilingPhysicalPositionsResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.CloseQueryRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.CloseQueryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.CloseRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.Commit37Response;
import com.jetbrains.youtrack.db.internal.client.remote.message.Commit38Request;
import com.jetbrains.youtrack.db.internal.client.remote.message.Connect37Request;
import com.jetbrains.youtrack.db.internal.client.remote.message.ConnectRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.ConnectResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.CountRecordsRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.CountRecordsResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.CountRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.CountResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.CreateDatabaseRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.CreateDatabaseResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.DropCollectionRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.DropCollectionResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.DropDatabaseRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.DropDatabaseResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.ExistsDatabaseRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.ExistsDatabaseResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.FetchTransaction38Request;
import com.jetbrains.youtrack.db.internal.client.remote.message.FetchTransaction38Response;
import com.jetbrains.youtrack.db.internal.client.remote.message.FloorPhysicalPositionsRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.FloorPhysicalPositionsResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.FreezeDatabaseRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.FreezeDatabaseResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.GetGlobalConfigurationRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.GetGlobalConfigurationResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.GetRecordMetadataRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.GetRecordMetadataResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.GetSizeRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.GetSizeResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.HigherPhysicalPositionsRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.HigherPhysicalPositionsResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.ImportRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.ImportResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.IncrementalBackupRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.IncrementalBackupResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.ListDatabasesRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.ListDatabasesResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.ListGlobalConfigurationsRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.ListGlobalConfigurationsResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.LowerPhysicalPositionsRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.LowerPhysicalPositionsResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.Open37Request;
import com.jetbrains.youtrack.db.internal.client.remote.message.Open37Response;
import com.jetbrains.youtrack.db.internal.client.remote.message.OpenRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.OpenResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.QueryNextPageRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.QueryRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.QueryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.ReadRecordRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.ReadRecordResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.RecordExistsRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.RecordExistsResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.ReleaseDatabaseRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.ReleaseDatabaseResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.ReloadRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.ReloadRequest37;
import com.jetbrains.youtrack.db.internal.client.remote.message.ReloadResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.ReloadResponse37;
import com.jetbrains.youtrack.db.internal.client.remote.message.ReopenRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.ReopenResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.RollbackTransactionRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.RollbackTransactionResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.SBTCreateTreeRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.SBTCreateTreeResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.SBTFetchEntriesMajorRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.SBTFetchEntriesMajorResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.SBTFirstKeyRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.SBTFirstKeyResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.SBTGetRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.SBTGetResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.SendTransactionStateRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.SendTransactionStateResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.ServerInfoRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.ServerInfoResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.ServerQueryRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.ServerQueryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.SetGlobalConfigurationRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.SetGlobalConfigurationResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.ShutdownRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.ShutdownResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.SubscribeFunctionsRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.SubscribeFunctionsResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.SubscribeIndexManagerRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.SubscribeIndexManagerResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.SubscribeLiveQueryRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.SubscribeLiveQueryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.SubscribeRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.SubscribeResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.SubscribeSchemaRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.SubscribeSchemaResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.SubscribeSequencesRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.SubscribeSequencesResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.SubscribeStorageConfigurationRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.SubscribeStorageConfigurationResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.UnsubscribLiveQueryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.UnsubscribeLiveQueryRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.UnsubscribeRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.UnsubscribeResponse;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.ByteSerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.NullSerializer;
import com.jetbrains.youtrack.db.internal.common.util.CommonConst;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBConstants;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseImport;
import com.jetbrains.youtrack.db.internal.core.fetch.FetchContext;
import com.jetbrains.youtrack.db.internal.core.fetch.FetchHelper;
import com.jetbrains.youtrack.db.internal.core.fetch.FetchListener;
import com.jetbrains.youtrack.db.internal.core.fetch.remote.RemoteFetchContext;
import com.jetbrains.youtrack.db.internal.core.fetch.remote.RemoteFetchListener;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.query.live.LiveQueryHookV2;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityHelper;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializerFactory;
import com.jetbrains.youtrack.db.internal.core.sql.parser.LocalResultSetLifecycleDecorator;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrack.db.internal.core.storage.index.sbtree.TreeInternal;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.LinkBagPointer;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendClientServerTransaction;
import com.jetbrains.youtrack.db.internal.core.tx.NetworkRecordOperation;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import com.jetbrains.youtrack.db.internal.server.network.protocol.binary.HandshakeInfo;
import com.jetbrains.youtrack.db.internal.server.network.protocol.binary.NetworkProtocolBinary;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class ConnectionBinaryExecutor implements BinaryRequestExecutor {

  private final ClientConnection connection;
  private final YouTrackDBServer server;
  private final HandshakeInfo handshakeInfo;

  public ConnectionBinaryExecutor(ClientConnection connection, YouTrackDBServer server) {
    this(connection, server, null);
  }

  public ConnectionBinaryExecutor(
      ClientConnection connection, YouTrackDBServer server, HandshakeInfo handshakeInfo) {
    this.connection = connection;
    this.server = server;
    this.handshakeInfo = handshakeInfo;
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

  @Override
  public BinaryResponse executeServerInfo(ServerInfoRequest request) {
    try {
      return new ServerInfoResponse(ServerInfo.getServerInfo(server));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public BinaryResponse executeDBReload(ReloadRequest request) {
    final var db = connection.getDatabaseSession();
    final var collections = db.getCollectionNames();

    var collectionNames = new String[collections.size()];
    var collectionIds = new int[collectionNames.length];

    var counter = 0;
    for (final var name : collections) {
      final var collectionId = db.getCollectionIdByName(name);
      if (collectionId >= 0) {
        collectionNames[counter] = name;
        collectionIds[counter] = collectionId;
        counter++;
      }
    }

    if (counter < collections.size()) {
      collectionNames = Arrays.copyOf(collectionNames, counter);
      collectionIds = Arrays.copyOf(collectionIds, counter);
    }

    return new ReloadResponse(collectionNames, collectionIds);
  }

  @Override
  public BinaryResponse executeDBReload(ReloadRequest37 request) {
    return new ReloadResponse37(connection.getDatabaseSession().getStorage().getConfiguration());
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
  public BinaryResponse executeGetSize(GetSizeRequest request) {
    var db = connection.getDatabaseSession();
    return new GetSizeResponse(db.getStorage().getSize(db));
  }

  @Override
  public BinaryResponse executeCountRecords(CountRecordsRequest request) {
    var db = connection.getDatabaseSession();
    return new CountRecordsResponse(db.getStorage().countRecords(db));
  }

  @Override
  public BinaryResponse executeCountCollection(CountRequest request) {
    final var count =
        connection
            .getDatabaseSession()
            .countCollectionElements(request.getCollectionIds(), request.isCountTombstones());
    return new CountResponse(count);
  }

  @Override
  public BinaryResponse executeAddCollection(AddCollectionRequest request) {
    final int num;
    if (request.getRequestedId() < 0) {
      num = connection.getDatabaseSession().addCollection(request.getCollectionName());
    } else {
      num = connection.getDatabaseSession()
          .addCollection(request.getCollectionName(), request.getRequestedId());
    }

    return new AddCollectionResponse(num);
  }

  @Override
  public BinaryResponse executeDropCollection(DropCollectionRequest request) {
    final var collectionName = connection.getDatabaseSession()
        .getCollectionNameById(request.getCollectionId());
    if (collectionName == null) {
      throw new IllegalArgumentException(
          "Collection "
              + request.getCollectionId()
              + " does not exist anymore. Refresh the db structure or just reconnect to the"
              + " database");
    }

    var result = connection.getDatabaseSession().dropCollection(collectionName);
    return new DropCollectionResponse(result);
  }

  @Override
  public BinaryResponse executeGetRecordMetadata(GetRecordMetadataRequest request) {
    var session = connection.getDatabaseSession();
    final var metadata = session.getRecordMetadata(request.getRid());
    if (metadata != null) {
      return new GetRecordMetadataResponse(metadata);
    } else {
      throw new DatabaseException(session,
          String.format("Record metadata for RID: %s, Not found", request.getRid()));
    }
  }

  @Override
  public BinaryResponse executeReadRecord(ReadRecordRequest request) {
    final var rid = request.getRid();
    final var fetchPlanString = request.getFetchPlan();
    var ignoreCache = false;
    ReadRecordResponse response;

    try {
      var db = connection.getDatabaseSession();
      final RecordAbstract record = db.load(rid);
      assert !record.isUnloaded();
      var bytes = getRecordBytes(connection, record);
      final Set<RecordAbstract> recordsToSend = new HashSet<>();
      if (!fetchPlanString.isEmpty()) {
        // BUILD THE SERVER SIDE RECORD TO ACCES TO THE FETCH
        // PLAN
        if (record instanceof EntityImpl entity) {
          final var fetchPlan = FetchHelper.buildFetchPlan(fetchPlanString);

          final FetchListener listener =
              new RemoteFetchListener() {
                @Override
                protected void sendRecord(RecordAbstract iLinked) {
                  recordsToSend.add(iLinked);
                }
              };
          final FetchContext context = new RemoteFetchContext();
          FetchHelper.fetch(db, entity, entity, fetchPlan, listener, context, "");
        }
      }
      response =
          new ReadRecordResponse(
              record.getRecordType(), record.getVersion(), bytes,
              recordsToSend);
    } catch (RecordNotFoundException e) {
      response = new ReadRecordResponse((byte) 0, 0, null, null);
    }

    return response;
  }

  @Override
  public BinaryResponse executeRecordExists(RecordExistsRequest request) {
    final var rid = request.getRecordId();
    final var recordExists = connection.getDatabaseSession().exists(rid);
    return new RecordExistsResponse(recordExists);
  }

  @Override
  public BinaryResponse executeHigherPosition(HigherPhysicalPositionsRequest request) {
    var db = connection.getDatabaseSession();
    var nextPositions =
        db
            .getStorage()
            .higherPhysicalPositions(db, request.getCollectionId(), request.getCollectionPosition(),
                request.getLimit());
    return new HigherPhysicalPositionsResponse(nextPositions);
  }

  @Override
  public BinaryResponse executeCeilingPosition(CeilingPhysicalPositionsRequest request) {
    var db = connection.getDatabaseSession();
    final var previousPositions =
        db.getStorage()
            .ceilingPhysicalPositions(db, request.getCollectionId(), request.getPhysicalPosition(),
                request.getLimit());
    return new CeilingPhysicalPositionsResponse(previousPositions);
  }

  @Override
  public BinaryResponse executeLowerPosition(LowerPhysicalPositionsRequest request) {
    var db = connection.getDatabaseSession();
    final var previousPositions =
        db
            .getStorage()
            .lowerPhysicalPositions(db, request.getiCollectionId(), request.getPhysicalPosition(),
                request.getLimit());
    return new LowerPhysicalPositionsResponse(previousPositions);
  }

  @Override
  public BinaryResponse executeFloorPosition(FloorPhysicalPositionsRequest request) {
    var db = connection.getDatabaseSession();
    final var previousPositions =
        db
            .getStorage()
            .floorPhysicalPositions(db, request.getCollectionId(), request.getPhysicalPosition(),
                request.getLimit());
    return new FloorPhysicalPositionsResponse(previousPositions);
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
  public BinaryResponse executeSBTreeCreate(SBTCreateTreeRequest request) {
    LinkBagPointer collectionPointer = null;
    final var session = connection.getDatabaseSession();
    try {
      final var storage = (AbstractStorage) session.getStorage();
      final var atomicOperationsManager = storage.getAtomicOperationsManager();
      collectionPointer =
          atomicOperationsManager.calculateInsideAtomicOperation(
              null,
              atomicOperation ->
                  connection
                      .getDatabaseSession()
                      .getBTreeCollectionManager()
                      .createBTree(request.getCollectionId(), atomicOperation, null, session));
    } catch (IOException e) {
      throw BaseException.wrapException(
          new DatabaseException(session, "Error during ridbag creation"), e, session);
    }

    return new SBTCreateTreeResponse(collectionPointer);
  }

  @Override
  public BinaryResponse executeSBTGet(SBTGetRequest request) {
    var session = connection.getDatabaseSession();
    final var bTreeCollectionManager = session.getBTreeCollectionManager();
    final var tree =
        bTreeCollectionManager.loadIsolatedBTree(request.getCollectionPointer());
    var serializerFactory = session.getSerializerFactory();
    var key = tree.getKeySerializer().deserialize(serializerFactory,
        request.getKeyStream(), 0);
    var result = tree.get(key);
    final BinarySerializer<? super Integer> valueSerializer;
    if (result == null) {
      valueSerializer = NullSerializer.INSTANCE;
    } else {
      valueSerializer = tree.getValueSerializer();
    }

    var stream = new byte[ByteSerializer.BYTE_SIZE + valueSerializer.getObjectSize(
        serializerFactory, result)];
    ByteSerializer.INSTANCE.serialize(valueSerializer.getId(), serializerFactory, stream, 0);
    valueSerializer.serialize(result, serializerFactory, stream, ByteSerializer.BYTE_SIZE);
    return new SBTGetResponse(stream);
  }

  @Override
  public BinaryResponse executeSBTFirstKey(SBTFirstKeyRequest request) {

    final var bTreeCollectionManager =
        connection.getDatabaseSession().getBTreeCollectionManager();
    final var tree =
        bTreeCollectionManager.loadIsolatedBTree(request.getCollectionPointer());
    byte[] stream;
    Identifiable result = tree.firstKey();
    final BinarySerializer keySerializer;
    if (result == null) {
      keySerializer = NullSerializer.INSTANCE;
    } else {
      keySerializer = tree.getKeySerializer();
    }

    var serializerFactory = connection.getDatabaseSession().getSerializerFactory();
    stream = new byte[ByteSerializer.BYTE_SIZE + keySerializer.getObjectSize(serializerFactory,
        result)];
    ByteSerializer.INSTANCE.serialize(keySerializer.getId(), serializerFactory, stream, 0);
    keySerializer.serialize(result, serializerFactory, stream, ByteSerializer.BYTE_SIZE);
    return new SBTFirstKeyResponse(stream);
  }

  @Override
  public BinaryResponse executeSBTFetchEntriesMajor(
      @SuppressWarnings("rawtypes") SBTFetchEntriesMajorRequest request) {

    var session = connection.getDatabaseSession();
    final var bTreeCollectionManager = session.getBTreeCollectionManager();
    final var tree =
        bTreeCollectionManager.loadIsolatedBTree(request.getPointer());
    final var keySerializer = tree.getKeySerializer();
    var key = keySerializer.deserialize(session.getSerializerFactory(), request.getKeyStream(),
        0);

    final var valueSerializer = tree.getValueSerializer();

    var listener =
        new TreeInternal.AccumulativeListener<RID, Integer>(request.getPageSize());
    tree.loadEntriesMajor(key, request.isInclusive(), true, listener);
    var result = listener.getResult();
    return new SBTFetchEntriesMajorResponse<>(keySerializer, valueSerializer, result);

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
                LogManager.instance().debug(ConnectionBinaryExecutor.this, iText);
                if (iText != null) {
                  result.add(iText);
                }
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
  public BinaryResponse executeConnect(ConnectRequest request) {
    BinaryProtocolHelper.checkProtocolVersion(this, request.getProtocolVersion());
    if (request.getProtocolVersion() > 36) {
      throw new ConfigurationException(
          "You can use connect as first operation only for protocol  < 37 please use handshake for"
              + " protocol >= 37");
    }
    connection.getData().driverName = request.getDriverName();
    connection.getData().driverVersion = request.getDriverVersion();
    connection.getData().protocolVersion = request.getProtocolVersion();
    connection.getData().clientId = request.getClientId();
    connection.getData().setSerializationImpl(request.getRecordFormat());

    connection.setTokenBased(request.isTokenBased());
    connection.getData().supportsLegacyPushMessages = request.isSupportPush();
    connection.getData().collectStats = request.isCollectStats();

    if (!request.isTokenBased()
        && !GlobalConfiguration.NETWORK_BINARY_ALLOW_NO_TOKEN.getValueAsBoolean()) {
      LogManager.instance()
          .warn(
              this,
              "Session open with token flag false is not supported anymore please use token based"
                  + " sessions");
      throw new ConfigurationException(
          "Session open with token flag false is not supported anymore please use token based"
              + " sessions");
    }

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
  public BinaryResponse executeConnect37(Connect37Request request) {
    connection.getData().driverName = handshakeInfo.getDriverName();
    connection.getData().driverVersion = handshakeInfo.getDriverVersion();
    connection.getData().protocolVersion = handshakeInfo.getProtocolVersion();
    connection.getData().setSerializer(handshakeInfo.getSerializer());

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
  public BinaryResponse executeDatabaseOpen(OpenRequest request) {
    BinaryProtocolHelper.checkProtocolVersion(this, request.getProtocolVersion());
    if (request.getProtocolVersion() > 36) {
      throw new ConfigurationException(
          "You can use open as first operation only for protocol  < 37 please use handshake for"
              + " protocol >= 37");
    }
    connection.getData().driverName = request.getDriverName();
    connection.getData().driverVersion = request.getDriverVersion();
    connection.getData().protocolVersion = request.getProtocolVersion();
    connection.getData().clientId = request.getClientId();
    connection.getData().setSerializationImpl(request.getRecordFormat());
    if (!request.isUseToken()
        && !GlobalConfiguration.NETWORK_BINARY_ALLOW_NO_TOKEN.getValueAsBoolean()) {
      LogManager.instance()
          .warn(
              this,
              "Session open with token flag false is not supported anymore please use token based"
                  + " sessions");
      throw new ConfigurationException(
          "Session open with token flag false is not supported anymore please use token based"
              + " sessions");
    }
    connection.setTokenBased(request.isUseToken());
    connection.getData().supportsLegacyPushMessages = request.isSupportsPush();
    connection.getData().collectStats = request.isCollectStats();

    try {
      connection.setSession(
          server.openSession(
              request.getDatabaseName(),
              request.getUserName(),
              request.getUserPassword(),
              connection.getData()));
    } catch (BaseException e) {
      server.getClientConnectionManager().disconnect(connection);
      throw e;
    }

    byte[] token = null;

    if (Boolean.TRUE.equals(connection.getTokenBased())) {
      token =
          server
              .getTokenHandler()
              .getSignedBinaryToken(
                  connection.getDatabaseSession(),
                  connection.getDatabaseSession().getCurrentUser(),
                  connection.getData());
      // TODO: do not use the parse split getSignedBinaryToken in two methods.
      server.getClientConnectionManager().connect(connection.getProtocol(), connection, token);
    }

    var db = connection.getDatabaseSession();
    final var collections = db.getCollectionNames();
    final byte[] tokenToSend;
    if (Boolean.TRUE.equals(connection.getTokenBased())) {
      tokenToSend = token;
    } else {
      tokenToSend = CommonConst.EMPTY_BYTE_ARRAY;
    }

    byte[] distriConf = null;

    var collectionNames = new String[collections.size()];
    var collectionIds = new int[collections.size()];

    var counter = 0;
    for (var name : collections) {
      final var collectionId = db.getCollectionIdByName(name);
      if (collectionId >= 0) {
        collectionNames[counter] = name;
        collectionIds[counter] = collectionId;
        counter++;
      }
    }

    if (counter < collections.size()) {
      collectionNames = Arrays.copyOf(collectionNames, counter);
      collectionIds = Arrays.copyOf(collectionIds, counter);
    }

    return new OpenResponse(
        connection.getId(),
        tokenToSend,
        collectionIds,
        collectionNames,
        distriConf,
        YouTrackDBConstants.getVersion());
  }

  @Override
  public BinaryResponse executeDatabaseOpen37(Open37Request request) {
    connection.setTokenBased(true);
    connection.getData().supportsLegacyPushMessages = false;
    connection.getData().collectStats = true;
    connection.getData().driverName = handshakeInfo.getDriverName();
    connection.getData().driverVersion = handshakeInfo.getDriverVersion();
    connection.getData().protocolVersion = handshakeInfo.getProtocolVersion();
    connection.getData().setSerializer(handshakeInfo.getSerializer());
    try {
      connection.setSession(
          server.openSession(
              request.getDatabaseName(),
              request.getUserName(),
              request.getUserPassword(),
              connection.getData()));
    } catch (BaseException e) {
      server.getClientConnectionManager().disconnect(connection);
      throw e;
    }

    byte[] token = null;

    token =
        server
            .getTokenHandler()
            .getSignedBinaryToken(
                connection.getDatabaseSession(), connection.getDatabaseSession().getCurrentUser(),
                connection.getData());
    // TODO: do not use the parse split getSignedBinaryToken in two methods.
    server.getClientConnectionManager().connect(connection.getProtocol(), connection, token);

    return new Open37Response(connection.getId(), token);
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
    return new ReopenResponse(connection.getId());
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

  public static byte[] getRecordBytes(ClientConnection connection,
      final RecordAbstract iRecord) {
    var db = connection.getDatabaseSession();
    final byte[] stream;
    var name = connection.getData().getSerializationImpl();
    if (EntityHelper.isEntity(iRecord.getRecordType())) {
      ((EntityImpl) iRecord).deserializeProperties();
      var ser = RecordSerializerFactory.instance().getFormat(name);
      stream = ser.toStream(db, iRecord);
    } else {
      stream = iRecord.toStream();
    }

    return stream;
  }

  @Override
  public BinaryResponse executeServerQuery(ServerQueryRequest request) {
    YouTrackDB youTrackDB = server.getContext();

    ResultSet rs;

    if (request.isNamedParams()) {
      rs = youTrackDB.execute(request.getStatement(), request.getNamedParameters());
    } else {
      rs = youTrackDB.execute(request.getStatement(), request.getPositionalParameters());
    }

    // copy the result-set to make sure that the execution is successful
    var rsCopy = rs.stream().collect(Collectors.toList());

    return new ServerQueryResponse(
        ((LocalResultSetLifecycleDecorator) rs).getQueryId(),
        false,
        rsCopy,
        rs.getExecutionPlan(),
        false,
        Collections.emptyMap(),
        false);
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
          rs =
              database.runScript(
                  request.getLanguage(), request.getStatement(),
                  request.getNamedParameters());
        } else {
          rs =
              database.runScript(
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
    var txChanges = false;
    if (database.getTransactionInternal().isActive()) {
      txChanges = !((FrontendClientServerTransaction) database.getTransactionInternal()).getOperationsToSendOnClient()
          .isEmpty();
    }
    database.getSharedContext().unregisterListener(metadataListener);

    return new QueryResponse(
        ((LocalResultSetLifecycleDecorator) rs).getQueryId(),
        txChanges,
        rsCopy,
        rs.getExecutionPlan(),
        hasNext,
        Collections.emptyMap(),
        metadataListener.isUpdated());
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
      youTrackDB.startCommand(Optional.empty());
      // copy the result-set to make sure that the execution is successful
      List<Result> rsCopy = new ArrayList<>(request.getRecordsPerPage());
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
          false,
          rsCopy,
          rs.getExecutionPlan(),
          hasNext,
          Collections.emptyMap(),
          false);
    } finally {
      youTrackDB.endCommand();
    }
  }

  @Override
  public BinaryResponse executeBeginTransaction(BeginTransaction38Request request) {
    var session = connection.getDatabaseSession();
    var recordOperations = request.getOperations();

    if (session.getTransactionInternal().isActive()) {
      throw new DatabaseException(session, "Transaction is already started on server");
    }

    var serverTransaction =
        doExecuteBeginTransaction(request.getTxId(), session, recordOperations,
            request.getReceivedDirtyCounters());

    return new BeginTransactionResponse(
        serverTransaction.getId(), serverTransaction.getUpdateToOldRecordIdMap(),
        serverTransaction.getOperationsToSendOnClient(), session);
  }

  private static FrontendClientServerTransaction doExecuteBeginTransaction(
      long txId, DatabaseSessionInternal database,
      List<NetworkRecordOperation> recordOperations,
      @Nonnull List<RawPair<RecordId, Long>> receivedDirtyCounters) {
    assert database.activeTxCount() == 0;

    database.begin(new FrontendClientServerTransaction(database, txId));
    var serverTransaction = (FrontendClientServerTransaction) database.getTransactionInternal();

    serverTransaction.mergeReceivedTransaction(recordOperations, receivedDirtyCounters);

    return serverTransaction;
  }

  @Override
  public BinaryResponse executeSendTransactionState(SendTransactionStateRequest request) {
    var session = connection.getDatabaseSession();
    var recordOperations = request.getOperations();

    var tx = session.getTransactionInternal();

    if (!tx.isActive()) {
      throw new DatabaseException(session,
          "Transaction with id " + request.getTxId() + " is not active on server.");
    }

    if (!(tx instanceof FrontendClientServerTransaction serverTransaction)) {
      throw new DatabaseException(session,
          "Invalid transaction type,"
              + " expected FrontendTransactionOptimisticServer but found "
              + tx.getClass().getName());
    }

    serverTransaction.mergeReceivedTransaction(recordOperations,
        request.getReceivedDirtyCounters());
    return new SendTransactionStateResponse(
        tx.getId(), serverTransaction.getUpdateToOldRecordIdMap(),
        serverTransaction.getOperationsToSendOnClient(), session);
  }

  @Override
  public BinaryResponse executeCommit38(Commit38Request request) {
    var recordOperations = request.getOperations();

    var session = connection.getDatabaseSession();
    var tx = session.getTransactionInternal();

    var started = tx.isActive();
    if (!started) {
      //case when transaction was sent during commit.
      tx = doExecuteBeginTransaction(request.getTxId(), session, recordOperations,
          request.getReceivedDirtyCounters());
    }

    if (tx.getId() != request.getTxId()) {
      throw new DatabaseException(session,
          "Invalid transaction id, expected " + tx.getId() + " but received " + request.getTxId());
    }

    if (!(tx instanceof FrontendClientServerTransaction serverTransaction)) {
      throw new DatabaseException(session,
          "Invalid transaction type,"
              + " expected FrontendTransactionOptimisticServer but found "
              + tx.getClass().getName());
    }

    try {
      if (started) {
        serverTransaction.mergeReceivedTransaction(recordOperations,
            request.getReceivedDirtyCounters());
      }
      if (serverTransaction.getTxStartCounter() != 1) {
        throw new DatabaseException(session, "Transaction can be started only once on server");
      }

      try {
        session.commit();
        final var collectionManager =
            connection.getDatabaseSession().getBTreeCollectionManager();
        Map<UUID, LinkBagPointer> changedIds = null;

        if (collectionManager != null) {
          changedIds = collectionManager.changedIds(session);
        }

        return new Commit37Response(serverTransaction.getId(),
            serverTransaction.getUpdateToOldRecordIdMap(),
            changedIds, session);
      } catch (final RuntimeException e) {
        if (serverTransaction.isActive()) {
          session.rollback(true);
        }

        final var collectionManager =
            connection.getDatabaseSession().getBTreeCollectionManager();
        if (collectionManager != null) {
          collectionManager.clearChangedIds(session);
        }

        throw e;
      }
    } catch (final RuntimeException e) {
      // Error during TX initialization, possibly index constraints violation.
      if (serverTransaction.isActive()) {
        session.rollback(true);
      }
      throw e;
    }
  }

  @Override
  public BinaryResponse executeFetchTransaction38(FetchTransaction38Request request) {
    var session = connection.getDatabaseSession();
    if (!session.getTransactionInternal().isActive()) {
      throw new DatabaseException(session, "No Transaction Active");
    }
    var tx = (FrontendClientServerTransaction) session.getTransactionInternal();
    if (tx.getId() != request.getTxId()) {
      throw new DatabaseException(session,
          "Invalid transaction id, expected " + tx.getId() + " but received " + request.getTxId());
    }

    var operationsToSend = tx.getOperationsToSendOnClient();
    var updateToOldRecordIdMap = tx.getUpdateToOldRecordIdMap();

    tx.syncDirtyCountersFromClient(request.getReceivedDirtyCounters().stream());

    return new FetchTransaction38Response(tx.getId(), updateToOldRecordIdMap, operationsToSend,
        session
    );
  }

  @Override
  public BinaryResponse executeRollback(RollbackTransactionRequest request) {
    var database = connection.getDatabaseSession();
    if (database.getTransactionInternal().isActive()) {
      database.rollback(true);
    }
    return new RollbackTransactionResponse();
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
  public BinaryResponse executeSubscribeStorageConfiguration(
      SubscribeStorageConfigurationRequest request) {
    var manager = server.getPushManager();
    manager.subscribeStorageConfiguration(
        connection.getDatabaseSession(), (NetworkProtocolBinary) connection.getProtocol());
    return new SubscribeStorageConfigurationResponse();
  }

  @Override
  public BinaryResponse executeSubscribeSchema(SubscribeSchemaRequest request) {
    var manager = server.getPushManager();
    manager.subscribeSchema(
        connection.getDatabaseSession(), (NetworkProtocolBinary) connection.getProtocol());
    return new SubscribeSchemaResponse();
  }

  @Override
  public BinaryResponse executeSubscribeIndexManager(SubscribeIndexManagerRequest request) {
    var manager = server.getPushManager();
    manager.subscribeIndexManager(
        connection.getDatabaseSession(), (NetworkProtocolBinary) connection.getProtocol());
    return new SubscribeIndexManagerResponse();
  }

  @Override
  public BinaryResponse executeSubscribeFunctions(SubscribeFunctionsRequest request) {
    var manager = server.getPushManager();
    manager.subscribeFunctions(
        connection.getDatabaseSession(), (NetworkProtocolBinary) connection.getProtocol());
    return new SubscribeFunctionsResponse();
  }

  @Override
  public BinaryResponse executeSubscribeSequences(SubscribeSequencesRequest request) {
    var manager = server.getPushManager();
    manager.subscribeSequences(
        connection.getDatabaseSession(), (NetworkProtocolBinary) connection.getProtocol());
    return new SubscribeSequencesResponse();
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
}
