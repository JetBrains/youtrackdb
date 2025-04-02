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
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.record.RecordHook;
import com.jetbrains.youtrack.db.api.record.RecordHook.TYPE;
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
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializerFactory;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetwork;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetworkV37Client;
import com.jetbrains.youtrack.db.internal.core.storage.RecordMetadata;
import com.jetbrains.youtrack.db.internal.core.storage.Storage;
import com.jetbrains.youtrack.db.internal.core.storage.StorageInfo;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.BTreeCollectionManager;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendClientServerTransaction;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionImpl;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;


public class DatabaseSessionRemote extends DatabaseSessionAbstract {

  protected StorageRemoteSession sessionMetadata;
  private YouTrackDBConfigImpl config;
  private StorageRemote storage;

  public DatabaseSessionRemote(final StorageRemote storage, SharedContext sharedContext) {
    activateOnCurrentThread();

    try {
      status = STATUS.CLOSED;

      // OVERWRITE THE URL
      url = storage.getURL();
      this.storage = storage;
      this.sharedContext = sharedContext;
      this.componentsFactory = storage.getComponentsFactory();

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
    assertIfNotActive();
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
      this.user = new ImmutableUser(this, user, password, SecurityUserImpl.DATABASE_USER, null);

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
    if (this.currentTx != null && this.currentTx.isActive()) {
      var optimistic = (FrontendClientServerTransaction) this.currentTx;
      optimistic.preProcessRecordsAndExecuteCallCallbacks();

      var operationsToSend = optimistic.getOperationsToSendOnClient();
      var dirtyCountersToSend = optimistic.getReceivedDirtyCounters();

      if (!operationsToSend.isEmpty() || !dirtyCountersToSend.isEmpty()) {
        storage.sendTransactionState(optimistic);
      }
    }
  }

  @Override
  public ResultSet query(String query, Object... args) {
    checkOpenness();
    assert assertIfNotActive();
    beginReadOnly();

    try {
      checkAndSendTransaction();

      var result = storage.query(this, query, args);
      if (result.isReloadMetadata()) {
        reload();
      }

      return result.getResult();
    } catch (Exception e) {
      rollback(true);
      throw e;
    }
  }

  @Override
  public ResultSet query(String query, Map args) {
    checkOpenness();
    assert assertIfNotActive();
    beginReadOnly();

    try {
      checkAndSendTransaction();

      var result = storage.query(this, query, args);
      if (result.isReloadMetadata()) {
        reload();
      }

      return result.getResult();
    } catch (Exception e) {
      rollback(true);
      throw e;
    }
  }

  @Override
  public ResultSet execute(String query, Object... args) {
    checkOpenness();
    assert assertIfNotActive();

    try {
      checkAndSendTransaction();

      var result = storage.command(this, query, args);
      if (result.isReloadMetadata()) {
        reload();
      }

      return result.getResult();
    } catch (Exception e) {
      rollback(true);
      throw e;
    }
  }

  @Override
  public ResultSet execute(String query, Map args) {
    checkOpenness();
    assert assertIfNotActive();

    checkAndSendTransaction();
    try {
      var result = storage.command(this, query, args);

      if (result.isReloadMetadata()) {
        reload();
      }

      return result.getResult();
    } catch (Exception e) {
      rollback(true);
      throw e;
    }
  }

  @Override
  public int begin(FrontendTransactionImpl transaction) {
    var result = super.begin(transaction);

    if (result == 1) {
      storage.beginTransaction((FrontendClientServerTransaction) transaction);
    }

    return result;
  }

  @Override
  protected FrontendTransactionImpl newTxInstance(long txId) {
    assert assertIfNotActive();
    return new FrontendClientServerTransaction(this, txId);
  }


  @Override
  protected FrontendTransactionImpl newReadOnlyTxInstance(long txId) {
    assert assertIfNotActive();
    return new FrontendClientServerTransaction(this, txId, true);
  }

  @Override
  public ResultSet runScript(String language, String script, Object... args)
      throws CommandExecutionException, CommandScriptException {
    checkOpenness();
    assert assertIfNotActive();

    try {
      checkAndSendTransaction();
      var result = storage.execute(this, language, script, args);

      if (result.isReloadMetadata()) {
        reload();
      }

      return result.getResult();
    } catch (Exception e) {
      rollback(true);
      throw e;
    }
  }

  @Override
  public ResultSet runScript(String language, String script, Map<String, ?> args)
      throws CommandExecutionException, CommandScriptException {
    checkOpenness();
    assert assertIfNotActive();

    try {
      checkAndSendTransaction();

      var result = storage.execute(this, language, script, args);

      if (result.isReloadMetadata()) {
        reload();
      }

      return result.getResult();
    } catch (Exception e) {
      rollback(true);
      throw e;
    }
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
  public int addBlobCollection(final String iCollectionName, final Object... iParameters) {
    int id;
    assert assertIfNotActive();
    try (var resultSet = execute("create blob collection :1", iCollectionName)) {
      assert resultSet.hasNext();
      var result = resultSet.next();
      assert result.getProperty("value") != null;
      id = result.getProperty("value");
      return id;
    }
  }

  public void beforeUpdateOperations(final RecordAbstract recordAbstract,
      java.lang.String collectionName) {
    assert assertIfNotActive();

    callbackHooks(TYPE.BEFORE_UPDATE, recordAbstract);
  }

  @Override
  public void afterUpdateOperations(RecordAbstract recordAbstract) {
    assert assertIfNotActive();

    callbackHooks(TYPE.AFTER_UPDATE, recordAbstract);

  }

  public void beforeCreateOperations(final RecordAbstract recordAbstract, String collectionName) {
    assert assertIfNotActive();

    callbackHooks(TYPE.BEFORE_CREATE, recordAbstract);
  }

  @Override
  public void afterCreateOperations(RecordAbstract recordAbstract) {
    assert assertIfNotActive();

    callbackHooks(TYPE.AFTER_CREATE, recordAbstract);
  }

  public void beforeDeleteOperations(final RecordAbstract recordAbstract, String collectionName) {
    assert assertIfNotActive();

    callbackHooks(TYPE.BEFORE_DELETE, recordAbstract);
  }

  @Override
  public void afterDeleteOperations(RecordAbstract recordAbstract) {
    assert assertIfNotActive();

    callbackHooks(TYPE.AFTER_DELETE, recordAbstract);
  }

  @Override
  public boolean beforeReadOperations(RecordAbstract identifiable) {
    assert assertIfNotActive();
    return false;
  }

  @Override
  public void afterReadOperations(RecordAbstract identifiable) {
    assert assertIfNotActive();
    callbackHooks(RecordHook.TYPE.READ, identifiable);
  }

  @Override
  public boolean executeExists(@Nonnull RID rid) {
    checkOpenness();
    assert assertIfNotActive();

    try {
      if (getTransactionInternal().isDeletedInTx(rid)) {
        return false;
      }
      DBRecord record = getTransactionInternal().getRecord(rid);
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
                  + " (collection: "
                  + getStorage().getPhysicalCollectionNameById(rid.getCollectionId())
                  + ")"),
          t, this);
    }
  }

  public String getCollectionName(final @Nonnull DBRecord record) {
    throw new UnsupportedOperationException();
  }

  public void delete(final @Nonnull DBRecord record) {
    checkOpenness();
    assert assertIfNotActive();

    record.delete();
  }

  @Override
  public int addCollection(final String iCollectionName, final Object... iParameters) {
    assert assertIfNotActive();
    return storage.addCollection(this, iCollectionName, iParameters);
  }

  @Override
  public int addCollection(final String iCollectionName, final int iRequestedId) {
    assert assertIfNotActive();
    return storage.addCollection(this, iCollectionName, iRequestedId);
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
  public long countCollectionElements(int iCollectionId, boolean countTombstones) {
    assert assertIfNotActive();
    return storage.count(this, iCollectionId, countTombstones);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long countCollectionElements(int[] iCollectionIds, boolean countTombstones) {
    assert assertIfNotActive();
    return storage.count(this, iCollectionIds, countTombstones);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long countCollectionElements(final String iCollectionName) {
    assert assertIfNotActive();

    final var collectionId = getCollectionIdByName(iCollectionName);
    if (collectionId < 0) {
      throw new IllegalArgumentException("Collection '" + iCollectionName + "' was not found");
    }
    return storage.count(this, collectionId);
  }

  @Override
  public long getCollectionRecordSizeByName(final String collectionName) {
    assert assertIfNotActive();
    try {
      return storage.getCollectionRecordsSizeByName(collectionName);
    } catch (Exception e) {
      throw BaseException.wrapException(
          new DatabaseException(getDatabaseName(),
              "Error on reading records size for collection '" + collectionName + "'"),
          e, this);
    }
  }

  @Override
  public boolean dropCollection(final String iCollectionName) {
    assert assertIfNotActive();
    final var collectionId = getCollectionIdByName(iCollectionName);
    var schema = metadata.getSchema();
    var clazz = schema.getClassByCollectionId(collectionId);

    if (clazz != null) {
      throw new DatabaseException(this, "Cannot drop collection '" + getCollectionNameById(collectionId)
          + "' because it is mapped to class '" + clazz.getName() + "'");
    }

    if (schema.getBlobCollections().contains(collectionId)) {
      schema.removeBlobCollection(iCollectionName);
    }
    getLocalCache().freeCollection(collectionId);
    return storage.dropCollection(this, iCollectionName);
  }

  @Override
  public boolean dropCollection(final int collectionId) {
    assert assertIfNotActive();

    var schema = metadata.getSchema();
    final var clazz = schema.getClassByCollectionId(collectionId);
    if (clazz != null) {
      throw new DatabaseException(this, "Cannot drop collection '" + getCollectionNameById(collectionId)
          + "' because it is mapped to class '" + clazz.getName() + "'");
    }

    getLocalCache().freeCollection(collectionId);
    if (schema.getBlobCollections().contains(collectionId)) {
      schema.removeBlobCollection(getCollectionNameById(collectionId));
    }

    final var collectionName = getCollectionNameById(collectionId);
    if (collectionName == null) {
      return false;
    }

    final var iteratorCollection = browseCollection(collectionName);
    if (iteratorCollection == null) {
      return false;
    }

    executeInTxBatches(iteratorCollection,
        (session, record) -> record.delete());

    return storage.dropCollection(this, collectionId);
  }

  public boolean dropCollectionInternal(int collectionId) {
    assert assertIfNotActive();
    return storage.dropCollection(this, collectionId);
  }

  @Override
  public long getCollectionRecordSizeById(final int collectionId) {
    assert assertIfNotActive();
    try {
      return storage.getCollectionRecordsSizeById(collectionId);
    } catch (Exception e) {
      throw BaseException.wrapException(
          new DatabaseException(getDatabaseName(),
              "Error on reading records size for collection with id '" + collectionId + "'"),
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
  public void internalCommit(@Nonnull FrontendTransactionImpl transaction) {
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
        if (currentTx != null && currentTx.isActive()) {
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
  public String getCollectionRecordConflictStrategy(int collectionId) {
    throw new UnsupportedOperationException();
  }

  public FrontendClientServerTransaction getActiveTx() {
    assert assertIfNotActive();
    if (currentTx != null && currentTx.isActive()) {
      return (FrontendClientServerTransaction) currentTx;
    } else {
      throw new DatabaseException(getDatabaseName(), "No active transaction found");
    }
  }

  @Override
  public int[] getCollectionsIds(@Nonnull Set<String> filterCollections) {
    assert assertIfNotActive();
    return filterCollections.stream().map(this::getCollectionIdByName).mapToInt(i -> i).toArray();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void truncateCollection(String collectionName) {
    assert assertIfNotActive();
    execute("truncate collection " + collectionName).close();
  }

  @Override
  public void truncateClass(String name) {
    assert assertIfNotActive();
    execute("truncate class " + name).close();
  }

  @Override
  public long truncateCollectionInternal(String name) {
    throw new UnsupportedOperationException();
  }

  @Override
  public long truncateClass(String name, boolean polimorfic) {
    assert assertIfNotActive();

    long count = 0;
    if (polimorfic) {
      try (var result = execute("truncate class " + name + " polymorphic ")) {
        while (result.hasNext()) {
          count += result.next().<Long>getProperty("count");
        }
      }
    } else {
      try (var result = execute("truncate class " + name)) {
        while (result.hasNext()) {
          count += result.next().<Long>getProperty("count");
        }
      }
    }
    return count;
  }
}
