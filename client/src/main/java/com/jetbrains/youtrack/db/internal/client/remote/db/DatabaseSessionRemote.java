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

package com.jetbrains.youtrack.db.internal.client.remote.db;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.exception.CommandScriptException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.query.LiveQueryMonitor;
import com.jetbrains.youtrack.db.api.query.LiveQueryResultListener;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.record.RecordHook;
import com.jetbrains.youtrack.db.internal.client.remote.LiveQueryClientListener;
import com.jetbrains.youtrack.db.internal.client.remote.StorageRemote;
import com.jetbrains.youtrack.db.internal.client.remote.StorageRemoteSession;
import com.jetbrains.youtrack.db.internal.client.remote.message.RemoteResultSet;
import com.jetbrains.youtrack.db.internal.client.remote.metadata.schema.SchemaRemote;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.conflict.RecordConflictStrategy;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionAbstract;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.SharedContext;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBConfigImpl;
import com.jetbrains.youtrack.db.internal.core.index.IndexManagerRemote;
import com.jetbrains.youtrack.db.internal.core.metadata.MetadataDefault;
import com.jetbrains.youtrack.db.internal.core.metadata.security.ImmutableUser;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Rule;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityUserImpl;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Token;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EdgeEntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.VertexInternal;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializerFactory;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetwork;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetworkV37Client;
import com.jetbrains.youtrack.db.internal.core.storage.RecordMetadata;
import com.jetbrains.youtrack.db.internal.core.storage.Storage;
import com.jetbrains.youtrack.db.internal.core.storage.StorageInfo;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.BTreeCollectionManager;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionAbstract;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionNoTx;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionNoTx.NonTxReadMode;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionOptimistic;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 *
 */
public class DatabaseSessionRemote extends DatabaseSessionAbstract {

  protected StorageRemoteSession sessionMetadata;
  private YouTrackDBConfigImpl config;
  private StorageRemote storage;
  private FrontendTransactionNoTx.NonTxReadMode nonTxReadMode;

  public DatabaseSessionRemote(final StorageRemote storage, SharedContext sharedContext) {
    activateOnCurrentThread();

    try {
      status = STATUS.CLOSED;

      // OVERWRITE THE URL
      url = storage.getURL();
      this.storage = storage;
      this.sharedContext = sharedContext;
      this.componentsFactory = storage.getComponentsFactory();

      unmodifiableHooks = Collections.unmodifiableMap(hooks);

      try {
        var cfg = storage.getConfiguration();
        if (cfg != null) {
          var ctx = cfg.getContextConfiguration();
          if (ctx != null) {
            nonTxReadMode =
                FrontendTransactionNoTx.NonTxReadMode.valueOf(
                    ctx.getValueAsString(GlobalConfiguration.NON_TX_READS_WARNING_MODE));
          } else {
            nonTxReadMode = NonTxReadMode.WARN;
          }
        } else {
          nonTxReadMode = NonTxReadMode.WARN;
        }
      } catch (Exception e) {
        LogManager.instance()
            .warn(
                this,
                "Invalid value for %s, using %s",
                e,
                GlobalConfiguration.NON_TX_READS_WARNING_MODE.getKey(),
                NonTxReadMode.WARN);
        nonTxReadMode = NonTxReadMode.WARN;
      }

      init();

      databaseOwner = this;
    } catch (Exception t) {
      activeSession.remove();
      throw BaseException.wrapException(
          new DatabaseException(getDatabaseName(), "Error on opening database "), t, this);
    }
  }


  public DatabaseSession open(final String iUserName, final String iUserPassword) {
    throw new UnsupportedOperationException("Use YouTrackDB");
  }

  @Deprecated
  public DatabaseSession open(final Token iToken) {
    throw new UnsupportedOperationException("Deprecated Method");
  }

  @Override
  public DatabaseSession create() {
    throw new UnsupportedOperationException("Deprecated Method");
  }

  @Override
  public DatabaseSession create(String incrementalBackupPath) {
    throw new UnsupportedOperationException("use YouTrackDB");
  }

  @Override
  public DatabaseSession create(final Map<GlobalConfiguration, Object> iInitialSettings) {
    throw new UnsupportedOperationException("use YouTrackDB");
  }

  @Override
  public void drop() {
    throw new UnsupportedOperationException("use YouTrackDB");
  }

  @Override
  public void set(ATTRIBUTES iAttribute, Object iValue) {
    assert assertIfNotActive();
    var query = "alter database " + iAttribute.name() + " ? ";
    // Bypass the database command for avoid transaction management
    var result = storage.command(this, query, new Object[]{iValue});
    result.getResult().close();
    storage.reload(this);
  }

  @Override
  public void set(ATTRIBUTES_INTERNAL attribute, Object value) {
    assert assertIfNotActive();
    var query = "alter database " + attribute.name() + " ? ";
    // Bypass the database command for avoid transaction management
    var result = storage.command(this, query, new Object[]{value});
    result.getResult().close();
    storage.reload(this);
  }

  @Override
  public DatabaseSession setCustom(String name, Object iValue) {
    assert assertIfNotActive();
    if ("clear".equals(name) && iValue == null) {
      var query = "alter database CUSTOM 'clear'";
      // Bypass the database command for avoid transaction management
      var result = storage.command(this, query, new Object[]{});
      result.getResult().close();
    } else {
      var query = "alter database CUSTOM  " + name + " = ?";
      // Bypass the database command for avoid transaction management
      var result = storage.command(this, query, new Object[]{iValue});
      result.getResult().close();
      storage.reload(this);
    }
    return this;
  }

  public DatabaseSessionInternal copy() {
    var database = new DatabaseSessionRemote(storage, this.sharedContext);
    database.storage = storage.copy(this, database);

    database.storage.addUser();
    database.status = STATUS.OPEN;
    database.applyAttributes(config);
    database.initAtFirstOpen();
    database.user = this.user;
    this.activateOnCurrentThread();
    return database;
  }

  @Override
  public boolean exists() {
    throw new UnsupportedOperationException("use YouTrackDB");
  }

  public void internalOpen(String user, String password, YouTrackDBConfigImpl config) {
    this.config = config;
    applyAttributes(config);
    applyListeners(config);
    try {

      storage.open(this, user, password, config.getConfiguration());

      status = STATUS.OPEN;

      initAtFirstOpen();
      this.user = new ImmutableUser(this, -1,
          new SecurityUserImpl(this, user, password));

      // WAKE UP LISTENERS
      callOnOpenListeners();

    } catch (BaseException e) {
      close();
      activeSession.remove();
      throw e;
    } catch (Exception e) {
      close();
      activeSession.remove();
      throw BaseException.wrapException(
          new DatabaseException(getDatabaseName(), "Cannot open database url=" + getURL()), e,
          this);
    }
  }

  private void applyAttributes(YouTrackDBConfigImpl config) {
    for (var attrs : config.getAttributes().entrySet()) {
      this.set(attrs.getKey(), attrs.getValue());
    }
  }

  private void initAtFirstOpen() {
    if (initialized) {
      return;
    }

    var serializerFactory = RecordSerializerFactory.instance();
    serializer = serializerFactory.getFormat(RecordSerializerNetworkV37Client.NAME);
    localCache.startup(this);
    componentsFactory = storage.getComponentsFactory();
    user = null;

    loadMetadata();

    initialized = true;
  }

  @Override
  protected void loadMetadata() {
    metadata = new MetadataDefault(this);
    metadata.init(sharedContext);
    sharedContext.load(this);
  }

  private void applyListeners(YouTrackDBConfigImpl config) {
    for (var listener : config.getListeners()) {
      registerListener(listener);
    }
  }

  public StorageRemoteSession getSessionMetadata() {
    return sessionMetadata;
  }

  public void setSessionMetadata(StorageRemoteSession sessionMetadata) {
    assert assertIfNotActive();
    this.sessionMetadata = sessionMetadata;
  }

  @Override
  public RecordSerializerNetwork getSerializer() {
    return (RecordSerializerNetwork) super.getSerializer();
  }

  @Override
  public Storage getStorage() {
    return storage;
  }

  public StorageRemote getStorageRemote() {
    assert assertIfNotActive();
    return storage;
  }

  @Override
  public StorageInfo getStorageInfo() {
    return storage;
  }

  private void checkAndSendTransaction() {
    if (this.currentTx.isActive()) {
      var optimistic = (FrontendTransactionOptimistic) this.currentTx;
      optimistic.preProcessRecordsAndExecuteCallCallbacks();

      if (optimistic.isChanged()) {
        if (((FrontendTransactionOptimistic) this.getTransaction()).isStartedOnServer()) {
          storage.sendTransactionState(optimistic);
        } else {
          storage.beginTransaction(optimistic);
        }

        optimistic.resetChangesTracking();
        optimistic.setSentToServer(true);
      }
    }
  }

  @Override
  public ResultSet query(String query, Object... args) {
    checkOpenness();
    assert assertIfNotActive();
    beginReadOnly();
    checkAndSendTransaction();

    var result = storage.query(this, query, args);
    if (result.isReloadMetadata()) {
      reload();
    }

    return result.getResult();
  }

  @Override
  public ResultSet query(String query, Map args) {
    checkOpenness();
    assert assertIfNotActive();
    beginReadOnly();
    checkAndSendTransaction();

    var result = storage.query(this, query, args);
    if (result.isReloadMetadata()) {
      reload();
    }

    return result.getResult();
  }

  @Override
  public ResultSet command(String query, Object... args) {
    checkOpenness();
    assert assertIfNotActive();
    checkAndSendTransaction();

    var result = storage.command(this, query, args);
    if (result.isReloadMetadata()) {
      reload();
    }

    return result.getResult();
  }

  @Override
  public ResultSet command(String query, Map args) {
    checkOpenness();
    assert assertIfNotActive();

    checkAndSendTransaction();
    var result = storage.command(this, query, args);

    if (result.isReloadMetadata()) {
      reload();
    }

    return result.getResult();
  }

  @Override
  protected FrontendTransactionOptimistic newTxInstance() {
    assert assertIfNotActive();
    return new FrontendTransactionOptimisticClient(this);
  }


  @Override
  protected FrontendTransactionOptimistic newReadOnlyTxInstance() {
    assert assertIfNotActive();
    return new FrontendTransactionOptimisticClient(this, true);
  }

  @Override
  public ResultSet execute(String language, String script, Object... args)
      throws CommandExecutionException, CommandScriptException {
    checkOpenness();
    assert assertIfNotActive();
    checkAndSendTransaction();
    var result = storage.execute(this, language, script, args);

    if (result.isReloadMetadata()) {
      reload();
    }

    return result.getResult();
  }

  @Override
  public ResultSet execute(String language, String script, Map<String, ?> args)
      throws CommandExecutionException, CommandScriptException {
    checkOpenness();
    assert assertIfNotActive();
    checkAndSendTransaction();

    var result = storage.execute(this, language, script, args);

    if (result.isReloadMetadata()) {
      reload();
    }

    return result.getResult();
  }

  public void closeQuery(String queryId) {
    assert assertIfNotActive();
    storage.closeQuery(this, queryId);
    queryClosed(queryId);
  }

  public void fetchNextPage(RemoteResultSet rs) {
    checkOpenness();
    assert assertIfNotActive();
    checkAndSendTransaction();
    storage.fetchNextPage(this, rs);
  }

  @Override
  public LiveQueryMonitor live(String query, LiveQueryResultListener listener, Object... args) {
    assert assertIfNotActive();
    return storage.liveQuery(
        this, query, new LiveQueryClientListener(this.copy(), listener), args);
  }

  @Override
  public LiveQueryMonitor live(
      String query, LiveQueryResultListener listener, Map<String, ?> args) {
    assert assertIfNotActive();
    return storage.liveQuery(
        this, query, new LiveQueryClientListener(this.copy(), listener), args);
  }

  @Override
  public void recycle(DBRecord record) {
    throw new UnsupportedOperationException();
  }

  public static void updateSchema(StorageRemote storage) {
    //    storage.get
    var shared = storage.getSharedContext();
    if (shared != null) {
      ((SchemaRemote) shared.getSchema()).requestUpdate();
    }
  }

  public static void updateFunction(StorageRemote storage) {
    var shared = storage.getSharedContext();
    if (shared != null) {
      (shared.getFunctionLibrary()).update();
    }
  }

  public static void updateSequences(StorageRemote storage) {
    var shared = storage.getSharedContext();
    if (shared != null) {
      (shared.getSequenceLibrary()).update();
    }
  }

  public static void updateIndexManager(StorageRemote storage) {
    var shared = storage.getSharedContext();
    if (shared != null) {
      ((IndexManagerRemote) shared.getIndexManager()).requestUpdate();
    }
  }

  @Override
  public int addBlobCluster(final String iClusterName, final Object... iParameters) {
    int id;
    assert assertIfNotActive();
    try (var resultSet = command("create blob cluster :1", iClusterName)) {
      assert resultSet.hasNext();
      var result = resultSet.next();
      assert result.getProperty("value") != null;
      id = result.getProperty("value");
      return id;
    }
  }

  @Override
  public void beforeCreateOperations(RecordAbstract id, String iClusterName) {
    assert assertIfNotActive();
    checkSecurity(Role.PERMISSION_CREATE, id, iClusterName);
    callbackHooks(RecordHook.TYPE.BEFORE_CREATE, id);
  }

  @Override
  public void beforeUpdateOperations(RecordAbstract id, String iClusterName) {
    assert assertIfNotActive();
    checkSecurity(Role.PERMISSION_UPDATE, id, iClusterName);
    callbackHooks(RecordHook.TYPE.BEFORE_UPDATE, id);
  }

  @Override
  public void beforeDeleteOperations(RecordAbstract id, String iClusterName) {
    assert assertIfNotActive();
    checkSecurity(Role.PERMISSION_DELETE, id, iClusterName);

    if (id instanceof EntityImpl entity) {
      var clazz = entity.getImmutableSchemaClass(this);
      if (clazz != null) {
        if (clazz.isVertexType()) {
          VertexInternal.deleteLinks(entity.castToVertex());
        } else if (clazz.isEdgeType()) {
          EdgeEntityImpl.deleteLinks(this, entity.castToStatefulEdge());
        }
      }
    }

    callbackHooks(RecordHook.TYPE.BEFORE_DELETE, id);
  }

  public void afterUpdateOperations(final RecordAbstract id) {
    assert assertIfNotActive();
    callbackHooks(RecordHook.TYPE.AFTER_UPDATE, id);
  }

  public void afterCreateOperations(final RecordAbstract id) {
    assert assertIfNotActive();
    callbackHooks(RecordHook.TYPE.AFTER_CREATE, id);
  }

  public void afterDeleteOperations(final RecordAbstract id) {
    assert assertIfNotActive();
    callbackHooks(RecordHook.TYPE.AFTER_DELETE, id);
  }

  @Override
  public boolean beforeReadOperations(RecordAbstract identifiable) {
    assert assertIfNotActive();
    return callbackHooks(RecordHook.TYPE.BEFORE_READ, identifiable) == RecordHook.RESULT.SKIP;
  }

  @Override
  public void afterReadOperations(RecordAbstract identifiable) {
    assert assertIfNotActive();
    callbackHooks(RecordHook.TYPE.AFTER_READ, identifiable);
  }

  @Override
  public boolean executeExists(@Nonnull RID rid) {
    checkOpenness();
    assert assertIfNotActive();

    try {
      DBRecord record = getTransaction().getRecord(rid);
      if (record == FrontendTransactionAbstract.DELETED_RECORD) {
        return false;
      }
      if (record != null) {
        return true;
      }

      if (!rid.isPersistent()) {
        return false;
      }

      if (localCache.findRecord(rid) != null) {
        return true;
      }

      return storage.recordExists(this, rid);
    } catch (Exception t) {
      throw BaseException.wrapException(
          new DatabaseException(getDatabaseName(),
              "Error on retrieving record "
                  + rid
                  + " (cluster: "
                  + getStorage().getPhysicalClusterNameById(rid.getClusterId())
                  + ")"),
          t, this);
    }
  }

  public String getClusterName(final @Nonnull DBRecord record) {
    // DON'T ASSIGN CLUSTER WITH REMOTE: SERVER KNOWS THE RIGHT CLUSTER BASED ON LOCALITY
    return null;
  }

  public void delete(final @Nonnull DBRecord record) {
    checkOpenness();
    assert assertIfNotActive();

    record.delete();
  }

  @Override
  public int addCluster(final String iClusterName, final Object... iParameters) {
    assert assertIfNotActive();
    return storage.addCluster(this, iClusterName, iParameters);
  }

  @Override
  public int addCluster(final String iClusterName, final int iRequestedId) {
    assert assertIfNotActive();
    return storage.addCluster(this, iClusterName, iRequestedId);
  }

  public RecordConflictStrategy getConflictStrategy() {
    assert assertIfNotActive();
    return getStorageInfo().getRecordConflictStrategy();
  }

  public DatabaseSessionAbstract setConflictStrategy(final String iStrategyName) {
    assert assertIfNotActive();
    storage.setConflictStrategy(
        YouTrackDBEnginesManager.instance().getRecordConflictStrategy().getStrategy(iStrategyName));
    return this;
  }

  public DatabaseSessionAbstract setConflictStrategy(final RecordConflictStrategy iResolver) {
    assert assertIfNotActive();
    storage.setConflictStrategy(iResolver);
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long countClusterElements(int iClusterId, boolean countTombstones) {
    assert assertIfNotActive();
    return storage.count(this, iClusterId, countTombstones);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long countClusterElements(int[] iClusterIds, boolean countTombstones) {
    assert assertIfNotActive();
    return storage.count(this, iClusterIds, countTombstones);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long countClusterElements(final String iClusterName) {
    assert assertIfNotActive();

    final var clusterId = getClusterIdByName(iClusterName);
    if (clusterId < 0) {
      throw new IllegalArgumentException("Cluster '" + iClusterName + "' was not found");
    }
    return storage.count(this, clusterId);
  }

  @Override
  public long getClusterRecordSizeByName(final String clusterName) {
    assert assertIfNotActive();
    try {
      return storage.getClusterRecordsSizeByName(clusterName);
    } catch (Exception e) {
      throw BaseException.wrapException(
          new DatabaseException(getDatabaseName(),
              "Error on reading records size for cluster '" + clusterName + "'"),
          e, this);
    }
  }

  @Override
  public boolean dropCluster(final String iClusterName) {
    assert assertIfNotActive();
    final var clusterId = getClusterIdByName(iClusterName);
    var schema = metadata.getSchema();
    var clazz = schema.getClassByClusterId(clusterId);

    if (clazz != null) {
      throw new DatabaseException(this, "Cannot drop cluster '" + getClusterNameById(clusterId)
          + "' because it is mapped to class '" + clazz.getName() + "'");
    }

    if (schema.getBlobClusters().contains(clusterId)) {
      schema.removeBlobCluster(iClusterName);
    }
    getLocalCache().freeCluster(clusterId);
    checkForClusterPermissions(iClusterName);
    return storage.dropCluster(this, iClusterName);
  }

  @Override
  public boolean dropCluster(final int clusterId) {
    assert assertIfNotActive();

    var schema = metadata.getSchema();
    final var clazz = schema.getClassByClusterId(clusterId);
    if (clazz != null) {
      throw new DatabaseException(this, "Cannot drop cluster '" + getClusterNameById(clusterId)
          + "' because it is mapped to class '" + clazz.getName() + "'");
    }

    getLocalCache().freeCluster(clusterId);
    if (schema.getBlobClusters().contains(clusterId)) {
      schema.removeBlobCluster(getClusterNameById(clusterId));
    }

    checkForClusterPermissions(getClusterNameById(clusterId));

    final var clusterName = getClusterNameById(clusterId);
    if (clusterName == null) {
      return false;
    }

    final var iteratorCluster = browseCluster(clusterName);
    if (iteratorCluster == null) {
      return false;
    }

    executeInTxBatches(iteratorCluster,
        (session, record) -> record.delete());

    return storage.dropCluster(this, clusterId);
  }

  public boolean dropClusterInternal(int clusterId) {
    assert assertIfNotActive();
    return storage.dropCluster(this, clusterId);
  }

  @Override
  public long getClusterRecordSizeById(final int clusterId) {
    assert assertIfNotActive();
    try {
      return storage.getClusterRecordsSizeById(clusterId);
    } catch (Exception e) {
      throw BaseException.wrapException(
          new DatabaseException(getDatabaseName(),
              "Error on reading records size for cluster with id '" + clusterId + "'"),
          e, this);
    }
  }

  @Override
  public long getSize() {
    assert assertIfNotActive();
    return storage.getSize(this);
  }

  @Override
  public void checkSecurity(
      Rule.ResourceGeneric resourceGeneric, String resourceSpecific, int iOperation) {
  }

  @Override
  public void checkSecurity(
      Rule.ResourceGeneric iResourceGeneric, int iOperation, Object iResourceSpecific) {
  }

  @Override
  public void checkSecurity(
      Rule.ResourceGeneric iResourceGeneric, int iOperation, Object... iResourcesSpecific) {
  }

  @Override
  public void checkSecurity(String iResource, int iOperation) {
  }

  @Override
  public void checkSecurity(String iResourceGeneric, int iOperation, Object iResourceSpecific) {
  }

  @Override
  public void checkSecurity(
      String iResourceGeneric, int iOperation, Object... iResourcesSpecific) {
  }

  @Override
  public boolean isRemote() {
    return true;
  }

  @Override
  public String incrementalBackup(final Path path) throws UnsupportedOperationException {
    checkOpenness();
    assert assertIfNotActive();
    checkSecurity(Rule.ResourceGeneric.DATABASE, "backup", Role.PERMISSION_EXECUTE);

    return storage.incrementalBackup(this, path.toAbsolutePath().toString(), null);
  }

  @Override
  public RecordMetadata getRecordMetadata(final RID rid) {
    assert assertIfNotActive();
    return storage.getRecordMetadata(this, rid);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void freeze(final boolean throwException) {
    checkOpenness();
    LogManager.instance()
        .error(
            this,
            "Only local paginated storage supports freeze. If you are using remote client please"
                + " use YouTrackDB instance instead",
            null);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void freeze() {
    freeze(false);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void release() {
    checkOpenness();
    LogManager.instance()
        .error(
            this,
            "Only local paginated storage supports release. If you are using remote client please"
                + " use YouTrackDB instance instead",
            null);
  }

  /**
   * {@inheritDoc}
   */
  public BTreeCollectionManager getBTreeCollectionManager() {
    assert assertIfNotActive();
    return storage.getSBtreeCollectionManager();
  }

  @Override
  public void reload() {
    assert assertIfNotActive();

    if (this.isClosed()) {
      throw new DatabaseException(getDatabaseName(), "Cannot reload a closed db");
    }

    metadata.reload();
    storage.reload(this);
  }

  @Override
  public void internalCommit(@Nonnull FrontendTransactionOptimistic transaction) {
    assert assertIfNotActive();
    this.storage.commit(transaction);
  }

  @Override
  public boolean isClosed() {
    return status == STATUS.CLOSED || storage.isClosed(this);
  }

  public void internalClose(boolean recycle) {
    if (status != STATUS.OPEN) {
      return;
    }

    try {
      closeActiveQueries();
      localCache.shutdown();

      if (isClosed()) {
        status = STATUS.CLOSED;
        return;
      }

      try {
        if (currentTx.isActive()) {
          rollback(true);
        }
      } catch (Exception e) {
        LogManager.instance().error(this, "Exception during rollback of active transaction", e);
      }

      callOnCloseListeners();

      status = STATUS.CLOSED;
      if (!recycle) {
        sharedContext = null;

        if (storage != null) {
          storage.close(this);
        }
      }

    } finally {
      // ALWAYS RESET TL
      activeSession.remove();
    }
  }

  @Override
  public String getClusterRecordConflictStrategy(int clusterId) {
    throw new UnsupportedOperationException();
  }

  public FrontendTransactionOptimisticClient getActiveTx() {
    assert assertIfNotActive();
    if (currentTx.isActive()) {
      return (FrontendTransactionOptimisticClient) currentTx;
    } else {
      throw new DatabaseException(getDatabaseName(), "No active transaction found");
    }
  }

  @Override
  public int[] getClustersIds(@Nonnull Set<String> filterClusters) {
    assert assertIfNotActive();
    return filterClusters.stream().map(this::getClusterIdByName).mapToInt(i -> i).toArray();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void truncateCluster(String clusterName) {
    assert assertIfNotActive();
    command("truncate cluster " + clusterName).close();
  }

  @Override
  public void truncateClass(String name) {
    assert assertIfNotActive();
    command("truncate class " + name).close();
  }

  @Override
  public long truncateClusterInternal(String name) {
    throw new UnsupportedOperationException();
  }

  @Override
  public long truncateClass(String name, boolean polimorfic) {
    assert assertIfNotActive();

    long count = 0;
    if (polimorfic) {
      try (var result = command("truncate class " + name + " polymorphic ")) {
        while (result.hasNext()) {
          count += result.next().<Long>getProperty("count");
        }
      }
    } else {
      try (var result = command("truncate class " + name)) {
        while (result.hasNext()) {
          count += result.next().<Long>getProperty("count");
        }
      }
    }
    return count;
  }

  @Override
  public NonTxReadMode getNonTxReadMode() {
    assert assertIfNotActive();

    return nonTxReadMode;
  }
}
