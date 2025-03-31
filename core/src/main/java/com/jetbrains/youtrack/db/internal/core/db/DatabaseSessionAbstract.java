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

package com.jetbrains.youtrack.db.internal.core.db;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.SessionListener;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.HighLevelException;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.exception.SchemaException;
import com.jetbrains.youtrack.db.api.exception.SecurityException;
import com.jetbrains.youtrack.db.api.exception.TransactionException;
import com.jetbrains.youtrack.db.api.query.LiveQueryMonitor;
import com.jetbrains.youtrack.db.api.query.LiveQueryResultListener;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.api.record.Blob;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.record.Edge;
import com.jetbrains.youtrack.db.api.record.EmbeddedEntity;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.record.RecordHook;
import com.jetbrains.youtrack.db.api.record.RecordHook.TYPE;
import com.jetbrains.youtrack.db.api.record.StatefulEdge;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.api.record.collection.embedded.EmbeddedList;
import com.jetbrains.youtrack.db.api.record.collection.embedded.EmbeddedMap;
import com.jetbrains.youtrack.db.api.record.collection.embedded.EmbeddedSet;
import com.jetbrains.youtrack.db.api.record.collection.links.LinkList;
import com.jetbrains.youtrack.db.api.record.collection.links.LinkSet;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.transaction.Transaction;
import com.jetbrains.youtrack.db.internal.common.concur.NeedRetryException;
import com.jetbrains.youtrack.db.internal.common.listener.ListenerManger;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.cache.LocalRecordCache;
import com.jetbrains.youtrack.db.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrack.db.internal.core.db.record.CurrentStorageComponentsFactory;
import com.jetbrains.youtrack.db.internal.core.db.record.EntityEmbeddedListImpl;
import com.jetbrains.youtrack.db.internal.core.db.record.EntityEmbeddedMapImpl;
import com.jetbrains.youtrack.db.internal.core.db.record.EntityEmbeddedSetImpl;
import com.jetbrains.youtrack.db.internal.core.db.record.EntityLinkListImpl;
import com.jetbrains.youtrack.db.internal.core.db.record.EntityLinkMapIml;
import com.jetbrains.youtrack.db.internal.core.db.record.EntityLinkSetImpl;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordElement;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.exception.SessionNotActivatedException;
import com.jetbrains.youtrack.db.internal.core.exception.TransactionBlockedException;
import com.jetbrains.youtrack.db.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.iterator.RecordIteratorClass;
import com.jetbrains.youtrack.db.internal.core.iterator.RecordIteratorCluster;
import com.jetbrains.youtrack.db.internal.core.metadata.Metadata;
import com.jetbrains.youtrack.db.internal.core.metadata.MetadataDefault;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrack.db.internal.core.metadata.security.ImmutableUser;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Rule;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityShared;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityUserImpl;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EdgeImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.EdgeInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EmbeddedEntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.RecordBytes;
import com.jetbrains.youtrack.db.internal.core.record.impl.StatefullEdgeEntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.VertexEntityImpl;
import com.jetbrains.youtrack.db.internal.core.security.SecurityUser;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializer;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializerFactory;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.string.RecordSerializerJackson;
import com.jetbrains.youtrack.db.internal.core.storage.PhysicalPosition;
import com.jetbrains.youtrack.db.internal.core.storage.RawBuffer;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.BonsaiCollectionPointer;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransaction;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransaction.TXSTATUS;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionImpl;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionNoTx;
import com.jetbrains.youtrack.db.internal.core.tx.RollbackException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Entity API entrypoint.
 */
@SuppressWarnings("unchecked")
public abstract class DatabaseSessionAbstract extends ListenerManger<SessionListener>
    implements DatabaseSessionInternal {

  protected final HashMap<String, Object> properties = new HashMap<>();
  protected final HashSet<Identifiable> inHook = new HashSet<>();

  protected RecordSerializer serializer;
  protected String url;
  protected STATUS status;
  protected DatabaseSessionInternal databaseOwner;
  protected MetadataDefault metadata;
  protected ImmutableUser user;
  protected static final byte recordType = EntityImpl.RECORD_TYPE;
  protected final ArrayList<RecordHook> hooks = new ArrayList<>();
  protected boolean retainRecords = true;
  protected final LocalRecordCache localCache = new LocalRecordCache();
  protected CurrentStorageComponentsFactory componentsFactory;
  protected boolean initialized = false;
  protected FrontendTransaction currentTx;

  protected SharedContext sharedContext;

  private boolean prefetchRecords;

  protected ConcurrentHashMap<String, QueryDatabaseState> activeQueries = new ConcurrentHashMap<>();
  protected LinkedList<QueryDatabaseState> queryState = new LinkedList<>();
  private Map<UUID, BonsaiCollectionPointer> collectionsChanges;

  // database stats!
  protected long loadedRecordsCount;
  protected long totalRecordLoadMs;
  protected long minRecordLoadMs;
  protected long maxRecordLoadMs;
  protected long ridbagPrefetchCount;
  protected long totalRidbagPrefetchMs;
  protected long minRidbagPrefetchMs;
  protected long maxRidbagPrefetchMs;

  protected final ThreadLocal<DatabaseSessionInternal> activeSession = new ThreadLocal<>();

  protected DatabaseSessionAbstract() {
    // DO NOTHING IS FOR EXTENDED OBJECTS
    super(false);
  }

  /**
   * @return default serializer which is used to serialize documents. Default serializer is common
   * for all database instances.
   */
  public static RecordSerializer getDefaultSerializer() {
    return RecordSerializerFactory.instance().getDefaultRecordSerializer();
  }

  /**
   * Sets default serializer. The default serializer is common for all database instances.
   *
   * @param iDefaultSerializer new default serializer value
   */
  public static void setDefaultSerializer(RecordSerializer iDefaultSerializer) {
    RecordSerializerFactory.instance().setDefaultRecordSerializer(iDefaultSerializer);
  }

  public void callOnOpenListeners() {
    assert assertIfNotActive();
    wakeupOnOpenDbLifecycleListeners();
  }

  protected abstract void loadMetadata();

  public void callOnCloseListeners() {
    assert assertIfNotActive();
    wakeupOnCloseDbLifecycleListeners();
    wakeupOnCloseListeners();
  }

  private void wakeupOnOpenDbLifecycleListeners() {
    for (var it = YouTrackDBEnginesManager.instance()
        .getDbLifecycleListeners();
        it.hasNext(); ) {
      it.next().onOpen(getDatabaseOwner());
    }
  }


  private void wakeupOnCloseDbLifecycleListeners() {
    for (var it = YouTrackDBEnginesManager.instance()
        .getDbLifecycleListeners();
        it.hasNext(); ) {
      it.next().onClose(getDatabaseOwner());
    }
  }

  private void wakeupOnCloseListeners() {
    for (var listener : getListenersCopy()) {
      try {
        listener.onClose(getDatabaseOwner());
      } catch (Exception e) {
        LogManager.instance().error(this, "Error during call of database listener", e);
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  public <RET extends DBRecord> RET getRecord(final Identifiable iIdentifiable) {
    assert assertIfNotActive();
    if (iIdentifiable instanceof Record) {
      return (RET) iIdentifiable;
    }
    return load(iIdentifiable.getIdentity());
  }

  @Override
  public LiveQueryMonitor live(String query, LiveQueryResultListener listener,
      Map<String, ?> args) {
    var youTrackDb = sharedContext.youtrackDB;

    var configBuilder = (YouTrackDBConfigBuilderImpl) YouTrackDBConfig.builder();
    var contextConfig = getConfiguration();
    var poolConfig = configBuilder.fromContext(contextConfig).build();

    var userName = user.getName(this);

    var pool = youTrackDb.openPoolNoAuthenticate(getDatabaseName(), userName, poolConfig);
    var storage = getStorage();
    return storage.live(pool, query, listener, args);
  }

  @Override
  public LiveQueryMonitor live(String query, LiveQueryResultListener listener, Object... args) {
    var youTrackDb = sharedContext.youtrackDB;

    var configBuilder = (YouTrackDBConfigBuilderImpl) YouTrackDBConfig.builder();
    var contextConfig = getConfiguration();
    var poolConfig = configBuilder.fromContext(contextConfig).build();

    var userName = user.getName(this);

    var pool = youTrackDb.openPoolNoAuthenticate(getDatabaseName(), userName, poolConfig);
    var storage = getStorage();

    return storage.live(pool, query, listener, args);
  }

  /**
   * {@inheritDoc}
   */
  public byte getRecordType() {
    return recordType;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long countClusterElements(final int[] iClusterIds) {
    assert assertIfNotActive();
    return countClusterElements(iClusterIds, false);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long countClusterElements(final int iClusterId) {
    assert assertIfNotActive();
    return countClusterElements(iClusterId, false);
  }

  /**
   * {@inheritDoc}
   */
  public MetadataDefault getMetadata() {
    assert assertIfNotActive();
    checkOpenness();
    return metadata;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public DatabaseSessionInternal getDatabaseOwner() {
    assert assertIfNotActive();
    var current = databaseOwner;
    while (current != null && current != this && current.getDatabaseOwner() != current) {
      current = current.getDatabaseOwner();
    }
    return current;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public DatabaseSessionInternal setDatabaseOwner(DatabaseSessionInternal iOwner) {
    assert assertIfNotActive();
    databaseOwner = iOwner;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  public boolean isRetainRecords() {
    assert assertIfNotActive();
    return retainRecords;
  }

  /**
   * {@inheritDoc}
   */
  public DatabaseSession setRetainRecords(boolean retainRecords) {
    assert assertIfNotActive();
    this.retainRecords = retainRecords;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  public DatabaseSession setStatus(final STATUS status) {
    assert assertIfNotActive();
    this.status = status;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  public void setInternal(final ATTRIBUTES iAttribute, final Object iValue) {
    set(iAttribute, iValue);
  }

  /**
   * {@inheritDoc}
   */
  public SecurityUser getCurrentUser() {
    assert assertIfNotActive();
    return user;
  }

  @Override
  public String getCurrentUserName() {
    var user = getCurrentUser();
    if (user == null) {
      return null;
    }

    return user.getName(this);
  }

  /**
   * {@inheritDoc}
   */
  public void setUser(final SecurityUser user) {
    assert assertIfNotActive();
    if (user instanceof SecurityUserImpl) {
      final Metadata metadata = getMetadata();
      if (metadata != null) {
        final var security = sharedContext.getSecurity();
        this.user = new ImmutableUser(this, security.getVersion(this), user);
      } else {
        this.user = new ImmutableUser(this, -1, user);
      }
    } else {
      this.user = (ImmutableUser) user;
    }
  }

  public void reloadUser() {
    assert assertIfNotActive();

    if (user != null) {
      if (user.checkIfAllowed(this, Rule.ResourceGeneric.CLASS, SecurityUserImpl.CLASS_NAME,
          Role.PERMISSION_READ)
          != null) {

        Metadata metadata = getMetadata();
        if (metadata != null) {
          final var security = sharedContext.getSecurity();
          final var secGetUser = security.getUser(this, user.getName(this));
          if (secGetUser != null) {
            user = new ImmutableUser(this, security.getVersion(this), secGetUser);
          } else {
            throw new SecurityException(url, "User not found");
          }
        } else {
          throw new SecurityException(url, "Metadata not found");
        }
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  public boolean isMVCC() {
    assert assertIfNotActive();
    return true;
  }

  /**
   * {@inheritDoc}
   */
  public DatabaseSession setMVCC(boolean mvcc) {
    throw new UnsupportedOperationException();
  }

  /**
   * {@inheritDoc}
   */
  public void registerHook(final @Nonnull RecordHook iHookImpl) {
    checkOpenness();
    assert assertIfNotActive();

    if (!hooks.contains(iHookImpl)) {
      hooks.add(iHookImpl);
    }
  }

  /**
   * {@inheritDoc}
   */
  public void unregisterHook(final @Nonnull RecordHook iHookImpl) {
    assert assertIfNotActive();
    if (hooks.remove(iHookImpl)) {
      iHookImpl.onUnregister();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public LocalRecordCache getLocalCache() {
    return localCache;
  }


  @Nonnull
  @Override
  public RID refreshRid(@Nonnull RID rid) {
    if (rid.isPersistent()) {
      return rid;
    }

    checkTxActive();
    var record = currentTx.getRecordEntry(rid);
    if (record == null) {
      throw new RecordNotFoundException(this, rid);
    }

    return record.record.getIdentity();
  }


  /**
   * {@inheritDoc}
   */
  public List<RecordHook> getHooks() {
    assert assertIfNotActive();
    return Collections.unmodifiableList(hooks);
  }

  @Override
  public void deleteInternal(@Nonnull DBRecord record) {
    checkOpenness();
    assert assertIfNotActive();

    if (record instanceof EntityImpl entity) {
      var clazz = entity.getImmutableSchemaClass(this);
      if (clazz != null) {
        ensureEdgeConsistencyOnDeletion(entity, clazz);
      }
    }
    try {
      checkTxActive();
      currentTx.deleteRecord((RecordAbstract) record);
    } catch (BaseException e) {
      throw e;
    } catch (Exception e) {
      if (record instanceof EntityImpl) {
        throw BaseException.wrapException(
            new DatabaseException(getDatabaseName(),
                "Error on deleting record "
                    + record.getIdentity()
                    + " of class '"
                    + ((EntityImpl) record).getSchemaClassName()
                    + "'"),
            e, getDatabaseName());
      } else {
        throw BaseException.wrapException(
            new DatabaseException(getDatabaseName(),
                "Error on deleting record " + record.getIdentity()),
            e, getDatabaseName());
      }
    }
  }

  /**
   * Callback the registered hooks if any.
   *
   * @param type   Hook type. Define when hook is called.
   * @param record Record received in the callback
   */
  public void callbackHooks(final TYPE type, final RecordAbstract record) {
    assert assertIfNotActive();
    if (record == null || hooks.isEmpty() || record.getIdentity().getClusterId() == 0) {
      return;
    }

    var identity = record.getIdentity().copy();
    if (!pushInHook(identity)) {
      return;
    }

    try {
      for (var hook : hooks) {
        hook.onTrigger(type, record);
      }
    } finally {
      popInHook(identity);
    }
  }

  /**
   * {@inheritDoc}
   */
  public boolean isValidationEnabled() {
    assert assertIfNotActive();
    return (Boolean) get(ATTRIBUTES_INTERNAL.VALIDATION);
  }

  /**
   * {@inheritDoc}
   */
  public void setValidationEnabled(final boolean iEnabled) {
    assert assertIfNotActive();
    set(ATTRIBUTES_INTERNAL.VALIDATION, iEnabled);
  }

  @Override
  public ContextConfiguration getConfiguration() {
    assert assertIfNotActive();
    if (getStorageInfo() != null) {
      return getStorageInfo().getConfiguration().getContextConfiguration();
    }
    return null;
  }

  @Override
  public void close() {
    internalClose(false);
  }

  @Override
  public STATUS getStatus() {
    assert assertIfNotActive();
    return status;
  }

  @Override
  public String getDatabaseName() {
    return getStorageInfo() != null ? getStorageInfo().getName() : url;
  }

  @Override
  public String getURL() {
    return url != null ? url : getStorageInfo().getURL();
  }

  @Override
  public int getClusters() {
    assert assertIfNotActive();
    return getStorageInfo().getClusters();
  }

  @Override
  public boolean existsCluster(final String iClusterName) {
    assert assertIfNotActive();
    return getStorageInfo().getClusterNames().contains(iClusterName.toLowerCase(Locale.ENGLISH));
  }

  @Override
  public Collection<String> getClusterNames() {
    assert assertIfNotActive();
    return getStorageInfo().getClusterNames();
  }

  @Override
  public int getClusterIdByName(final String iClusterName) {
    if (iClusterName == null) {
      return -1;
    }

    assert assertIfNotActive();
    return getStorageInfo().getClusterIdByName(iClusterName.toLowerCase(Locale.ENGLISH));
  }

  @Override
  public String getClusterNameById(final int iClusterId) {
    if (iClusterId < 0) {
      return null;
    }

    assert assertIfNotActive();
    return getStorageInfo().getPhysicalClusterNameById(iClusterId);
  }

  public void checkForClusterPermissions(final String iClusterName) {
    assert assertIfNotActive();
    // CHECK FOR ORESTRICTED
    final var classes =
        getMetadata().getImmutableSchemaSnapshot().getClassesRelyOnCluster(iClusterName, this);

    for (var c : classes) {
      if (c.isSubClassOf(SecurityShared.RESTRICTED_CLASSNAME)) {
        throw new SecurityException(getDatabaseName(),
            "Class '"
                + c.getName()
                + "' cannot be truncated because has record level security enabled (extends '"
                + SecurityShared.RESTRICTED_CLASSNAME
                + "')");
      }
    }
  }

  @Override
  public Object setProperty(final String iName, final Object iValue) {
    assert assertIfNotActive();
    if (iValue == null) {
      return properties.remove(iName.toLowerCase(Locale.ENGLISH));
    } else {
      return properties.put(iName.toLowerCase(Locale.ENGLISH), iValue);
    }
  }

  @Override
  public Object getProperty(final String iName) {
    assert assertIfNotActive();
    return properties.get(iName.toLowerCase(Locale.ENGLISH));
  }

  @Override
  public Iterator<Map.Entry<String, Object>> getProperties() {
    assert assertIfNotActive();
    return properties.entrySet().iterator();
  }

  @Override
  public Object get(final ATTRIBUTES iAttribute) {
    assert assertIfNotActive();

    if (iAttribute == null) {
      throw new IllegalArgumentException("attribute is null");
    }
    final var storage = getStorageInfo();
    return switch (iAttribute) {
      case DATEFORMAT -> storage.getConfiguration().getDateFormat();
      case DATE_TIME_FORMAT -> storage.getConfiguration().getDateTimeFormat();
      case TIMEZONE -> storage.getConfiguration().getTimeZone().getID();
      case LOCALE_COUNTRY -> storage.getConfiguration().getLocaleCountry();
      case LOCALE_LANGUAGE -> storage.getConfiguration().getLocaleLanguage();
      case CHARSET -> storage.getConfiguration().getCharset();
    };
  }

  @Override
  public Object get(ATTRIBUTES_INTERNAL attribute) {
    assert assertIfNotActive();

    if (attribute == null) {
      throw new IllegalArgumentException("attribute is null");
    }

    final var storage = getStorageInfo();
    if (attribute == ATTRIBUTES_INTERNAL.VALIDATION) {
      return storage.getConfiguration().isValidationEnabled();
    }

    throw new IllegalArgumentException("attribute is not supported: " + attribute);
  }


  public FrontendTransaction getTransactionInternal() {
    assert assertIfNotActive();
    return currentTx;
  }

  /**
   * Returns the schema of the database.
   *
   * @return the schema of the database
   */
  @Override
  public Schema getSchema() {
    assert assertIfNotActive();
    return getMetadata().getSchema();
  }


  @Nonnull
  @SuppressWarnings("unchecked")
  @Override
  public <RET extends DBRecord> RET load(final RID recordId) {
    assert assertIfNotActive();
    return (RET) currentTx.loadRecord(recordId).recordAbstract();
  }

  @Nullable
  @Override
  public <RET extends RecordAbstract> RawPair<RET, RecordId> loadRecordAndNextRidInCluster(
      @Nonnull RecordId recordId) {
    assert assertIfNotActive();

    while (true) {
      var result = executeReadRecord(recordId, false, true, false);

      if (result.recordAbstract() == null) {
        if (result.nextRecordId() == null) {
          return null;
        } else {
          recordId = result.nextRecordId();
          continue;
        }
      }

      return new RawPair<>((RET) result.recordAbstract(), result.nextRecordId());
    }
  }

  @Nullable
  @Override
  public <RET extends RecordAbstract> RawPair<RET, RecordId> loadRecordAndPreviousRidInCluster(
      @Nonnull RecordId recordId) {
    assert assertIfNotActive();

    while (true) {
      var result = executeReadRecord(recordId, true, false, false);
      if (result.recordAbstract() == null) {
        if (result.previousRecordId() == null) {
          return null;
        } else {
          recordId = result.previousRecordId();
          continue;
        }
      }
      return new RawPair<>((RET) result.recordAbstract(), result.previousRecordId());
    }
  }

  @Override
  public boolean exists(RID rid) {
    assert assertIfNotActive();
    return currentTx.exists(rid);
  }

  /**
   * Deletes the record without checking the version.
   */
  public void delete(final RID iRecord) {
    checkOpenness();
    assert assertIfNotActive();

    final var rec = load(iRecord);
    delete(rec);
  }

  @Override
  public BinarySerializerFactory getSerializerFactory() {
    assert assertIfNotActive();
    return componentsFactory.binarySerializerFactory;
  }

  @Override
  public void setPrefetchRecords(boolean prefetchRecords) {
    assert assertIfNotActive();
    this.prefetchRecords = prefetchRecords;
  }

  @Override
  public boolean isPrefetchRecords() {
    assert assertIfNotActive();
    return prefetchRecords;
  }


  @Nullable
  @Override
  public <RET extends RecordAbstract> RawPair<RET, RecordId> loadFirstRecordAndNextRidInCluster(
      int clusterId) {
    assert assertIfNotActive();
    checkOpenness();

    var firstPosition = getStorage().ceilingPhysicalPositions(this, clusterId,
        new PhysicalPosition(0), 1);
    var firstTxRid = currentTx.getFirstRid(clusterId);

    if ((firstPosition == null || firstPosition.length == 0) && firstTxRid == null) {
      return null;
    }

    RecordId firstRid;
    if (firstPosition == null || firstPosition.length == 0) {
      firstRid = firstTxRid;
    } else if (firstTxRid == null) {
      firstRid = new RecordId(clusterId, firstPosition[0].clusterPosition);
    } else if (firstPosition[0].clusterPosition < firstTxRid.getClusterPosition()) {
      firstRid = new RecordId(clusterId, firstPosition[0].clusterPosition);
    } else {
      firstRid = firstTxRid;
    }

    if (currentTx.isDeletedInTx(firstRid)) {
      firstRid = fetchNextRid(firstRid);
    }

    if (firstRid == null) {
      return null;
    }

    var recordId = firstRid;
    while (true) {
      var result = executeReadRecord(recordId, false, true, false);

      if (result.recordAbstract() == null) {
        if (result.nextRecordId() == null) {
          return null;
        } else {
          recordId = result.nextRecordId();
          continue;
        }
      }

      return new RawPair<>((RET) result.recordAbstract(), result.nextRecordId());
    }
  }

  @Nullable
  @Override
  public <RET extends RecordAbstract> RawPair<RET, RecordId> loadLastRecordAndPreviousRidInCluster(
      int clusterId) {
    assert assertIfNotActive();
    checkOpenness();

    var lastPosition = getStorage().floorPhysicalPositions(this, clusterId,
        new PhysicalPosition(Long.MAX_VALUE), 1);
    var lastTxRid = currentTx.getLastRid(clusterId);

    if ((lastPosition == null || lastPosition.length == 0) && lastTxRid == null) {
      return null;
    }

    RecordId lastRid;
    if (lastPosition == null || lastPosition.length == 0) {
      lastRid = lastTxRid;
    } else if (lastTxRid == null) {
      lastRid = new RecordId(clusterId, lastPosition[0].clusterPosition);
    } else if (lastPosition[0].clusterPosition > lastTxRid.getClusterPosition()) {
      lastRid = new RecordId(clusterId, lastPosition[0].clusterPosition);
    } else {
      lastRid = lastTxRid;
    }

    if (currentTx.isDeletedInTx(lastRid)) {
      lastRid = fetchPreviousRid(lastRid);
    }

    if (lastRid == null) {
      return null;
    }

    var recordId = lastRid;
    while (true) {
      var result = executeReadRecord(recordId, true, false, false);
      if (result.recordAbstract() == null) {
        if (result.previousRecordId() == null) {
          return null;
        } else {
          recordId = result.previousRecordId();
          continue;
        }
      }
      return new RawPair<>((RET) result.recordAbstract(), result.previousRecordId());
    }
  }

  @Nullable
  public final LoadRecordResult executeReadRecord(final @Nonnull RecordId rid,
      boolean fetchPreviousRid, boolean fetchNextRid, boolean throwExceptionIfRecordNotFound) {
    checkOpenness();
    assert assertIfNotActive();

    RecordId previousRid = null;
    RecordId nextRid = null;
    getMetadata().makeThreadLocalSchemaSnapshot();
    try {
      checkSecurity(
          Rule.ResourceGeneric.CLUSTER,
          Role.PERMISSION_READ,
          getClusterNameById(rid.getClusterId()));
      // SEARCH IN LOCAL TX
      var txInternal = getTransactionInternal();
      if (txInternal.isDeletedInTx(rid)) {
        // DELETED IN TX
        return createRecordNotFoundResult(rid, fetchPreviousRid, fetchNextRid,
            throwExceptionIfRecordNotFound);
      }

      var record = getTransactionInternal().getRecord(rid);
      var cachedRecord = localCache.findRecord(rid);
      if (record == null) {
        record = cachedRecord;
      }
      if (record != null && record.isUnloaded()) {
        throw new IllegalStateException(
            "Unloaded record with rid " + rid + " was found in local cache");
      }

      if (record != null) {
        if (beforeReadOperations(record)) {
          return createRecordNotFoundResult(rid, fetchPreviousRid, fetchNextRid,
              throwExceptionIfRecordNotFound);
        }

        afterReadOperations(record);
        if (record instanceof EntityImpl entity) {
          entity.checkClass(this);
        }

        localCache.updateRecord(record, this);

        assert !record.isUnloaded();
        assert record.getSession() == this;

        if (fetchPreviousRid) {
          previousRid = fetchPreviousRid(rid);
        }

        if (fetchNextRid) {
          nextRid = fetchNextRid(rid);
        }

        return new LoadRecordResult(record, previousRid, nextRid);
      }

      loadedRecordsCount++;

      final RawBuffer recordBuffer;
      if (!rid.isValidPosition()) {
        recordBuffer = null;
      } else {
        try {
          var readRecordResult =
              getStorage().readRecord(this, rid, fetchPreviousRid, fetchNextRid);
          recordBuffer = readRecordResult.buffer();

          previousRid = readRecordResult.previousRecordId();
          nextRid = readRecordResult.nextRecordId();
        } catch (RecordNotFoundException e) {
          if (throwExceptionIfRecordNotFound) {
            throw e;
          } else {
            if (fetchNextRid) {
              nextRid = fetchNextRid(rid);
            }
            if (fetchPreviousRid) {
              previousRid = fetchPreviousRid(rid);
            }

            return new LoadRecordResult(null, previousRid, nextRid);
          }
        }
      }

      record =
          YouTrackDBEnginesManager.instance()
              .getRecordFactoryManager()
              .newInstance(recordBuffer.recordType, rid, this);
      final var rec = record;
      rec.unsetDirty();

      if (record.getRecordType() != recordBuffer.recordType) {
        throw new DatabaseException(getDatabaseName(),
            "Record type is different from the one in the database");
      }

      record.recordSerializer = serializer;
      record.fill(rid, recordBuffer.version, recordBuffer.buffer, false);

      if (record instanceof EntityImpl entity) {
        entity.checkClass(this);
      }

      localCache.updateRecord(record, this);
      record.fromStream(recordBuffer.buffer);

      if (beforeReadOperations(record)) {
        return createRecordNotFoundResult(rid, fetchPreviousRid, fetchNextRid,
            throwExceptionIfRecordNotFound);
      }

      afterReadOperations(record);

      assert !record.isUnloaded();
      assert record.getSession() == this;

      if (fetchPreviousRid) {
        var previousTxRid = currentTx.getPreviousRidInCluster(rid);

        if (previousRid == null) {
          if (previousTxRid != null) {
            previousRid = previousTxRid;
          }
        } else if (previousTxRid != null && previousTxRid.compareTo(previousRid) > 0) {
          previousRid = previousTxRid;
        }

        if (previousRid != null && currentTx.isDeletedInTx(previousRid)) {
          previousRid = fetchPreviousRid(previousRid);
        }
      }

      if (fetchNextRid) {
        var nextTxRid = currentTx.getNextRidInCluster(rid);

        if (nextRid == null) {
          if (nextTxRid != null) {
            nextRid = nextTxRid;
          }
        } else if (nextTxRid != null && nextTxRid.compareTo(nextRid) < 0) {
          nextRid = nextTxRid;
        }

        if (nextRid != null && currentTx.isDeletedInTx(nextRid)) {
          nextRid = fetchNextRid(nextRid);
        }
      }

      return new LoadRecordResult(record, previousRid, nextRid);
    } catch (RecordNotFoundException t) {
      throw t;
    } catch (Exception t) {
      if (rid.isTemporary()) {
        throw BaseException.wrapException(
            new DatabaseException(getDatabaseName(),
                "Error on retrieving record using temporary RID: " + rid), t, getDatabaseName());
      } else {
        throw BaseException.wrapException(
            new DatabaseException(getDatabaseName(),
                "Error on retrieving record "
                    + rid
                    + " (cluster: "
                    + getStorage().getPhysicalClusterNameById(rid.getClusterId())
                    + ")"),
            t, getDatabaseName());
      }
    } finally {
      getMetadata().clearThreadLocalSchemaSnapshot();
    }
  }

  private LoadRecordResult createRecordNotFoundResult(RecordId rid, boolean fetchPreviousRid,
      boolean fetchNextRid, boolean throwExceptionIfRecordNotFound) {
    RecordId previousRid = null;
    RecordId nextRid = null;
    if (throwExceptionIfRecordNotFound) {
      throw new RecordNotFoundException(getDatabaseName(), rid);
    } else {
      if (fetchNextRid) {
        nextRid = fetchNextRid(rid);
      }
      if (fetchPreviousRid) {
        previousRid = fetchPreviousRid(rid);
      }

      return new LoadRecordResult(null, previousRid, nextRid);
    }
  }

  @Nullable
  private RecordId fetchNextRid(RecordId rid) {
    RecordId nextRid;
    while (true) {
      var higherPositions = getStorage().higherPhysicalPositions(this, rid.getClusterId(),
          new PhysicalPosition(rid.getClusterPosition()), 1);
      var txNextRid = currentTx.getNextRidInCluster(rid);

      if (higherPositions != null && higherPositions.length > 0) {
        if (txNextRid == null) {
          nextRid = new RecordId(rid.getClusterId(), higherPositions[0].clusterPosition);
        } else if (higherPositions[0].clusterPosition > txNextRid.getClusterPosition()) {
          nextRid = txNextRid;
        } else {
          nextRid = new RecordId(rid.getClusterId(), higherPositions[0].clusterPosition);
        }
      } else {
        nextRid = txNextRid;
      }

      if (nextRid == null) {
        return null;
      }

      if (currentTx.isDeletedInTx(nextRid)) {
        rid = nextRid;
        continue;
      }

      break;
    }

    return nextRid;
  }

  @Nullable
  private RecordId fetchPreviousRid(RecordId rid) {
    RecordId previousRid;

    while (true) {
      var lowerPositions = getStorage().lowerPhysicalPositions(this, rid.getClusterId(),
          new PhysicalPosition(rid.getClusterPosition()), 1);
      var txPreviousRid = currentTx.getPreviousRidInCluster(rid);
      if (lowerPositions != null && lowerPositions.length > 0) {
        if (txPreviousRid == null) {
          previousRid = new RecordId(rid.getClusterId(), lowerPositions[0].clusterPosition);
        } else if (lowerPositions[0].clusterPosition < txPreviousRid.getClusterPosition()) {
          previousRid = txPreviousRid;
        } else {
          previousRid = new RecordId(rid.getClusterId(), lowerPositions[0].clusterPosition);
        }
      } else {
        previousRid = txPreviousRid;
      }

      if (previousRid == null) {
        return null;
      }

      if (currentTx.isDeletedInTx(previousRid)) {
        rid = previousRid;
        continue;
      }

      break;
    }
    return previousRid;
  }

  public int assignAndCheckCluster(DBRecord record) {
    assert assertIfNotActive();

    if (!getStorageInfo().isAssigningClusterIds()) {
      return RID.CLUSTER_ID_INVALID;
    }

    var rid = (RecordId) record.getIdentity();
    SchemaClassInternal schemaClass = null;
    // if cluster id is not set yet try to find it out
    if (rid.getClusterId() <= RID.CLUSTER_ID_INVALID) {
      if (record instanceof EntityImpl entity) {
        schemaClass = entity.getImmutableSchemaClass(this);
        if (schemaClass != null) {
          if (schemaClass.isAbstract()) {
            throw new SchemaException(getDatabaseName(),
                "Entity belongs to abstract class "
                    + schemaClass.getName()
                    + " and cannot be saved");
          }

          return schemaClass.getClusterForNewInstance((EntityImpl) record);
        } else {
          throw new DatabaseException(getDatabaseName(),
              "Cannot save (1) entity " + record + ": no class or cluster defined");
        }
      } else {
        if (record instanceof RecordBytes) {
          var blobs = getBlobClusterIds();
          if (blobs.length == 0) {
            throw new DatabaseException(getDatabaseName(),
                "Cannot save blob (2) " + record + ": no cluster defined");
          } else {
            return blobs[ThreadLocalRandom.current().nextInt(blobs.length)];
          }

        } else {
          throw new DatabaseException(getDatabaseName(),
              "Cannot save (3) entity " + record + ": no class or cluster defined");
        }
      }
    } else {
      if (record instanceof EntityImpl) {
        schemaClass = ((EntityImpl) record).getImmutableSchemaClass(this);
      }
    }
    // If the cluster id was set check is validity
    if (rid.getClusterId() > RID.CLUSTER_ID_INVALID) {
      if (schemaClass != null) {
        var messageClusterName = getClusterNameById(rid.getClusterId());
        checkRecordClass(schemaClass, messageClusterName, rid);
        if (!schemaClass.hasClusterId(rid.getClusterId())) {
          throw new IllegalArgumentException(
              "Cluster name '"
                  + messageClusterName
                  + "' (id="
                  + rid.getClusterId()
                  + ") is not configured to store the class '"
                  + schemaClass.getName()
                  + "', valid are "
                  + Arrays.toString(schemaClass.getClusterIds()));
        }
      }
    }

    var clusterId = rid.getClusterId();
    if (clusterId < 0) {
      throw new DatabaseException(getDatabaseName(),
          "Impossible to set cluster for record " + record + " class : " + schemaClass);
    }

    return clusterId;
  }

  public Transaction begin() {
    assert assertIfNotActive();

    if (currentTx.isActive()) {
      currentTx.beginInternal();
      return currentTx;
    }

    begin(newTxInstance(FrontendTransactionImpl.generateTxId()));
    return currentTx;
  }

  public void beginReadOnly() {
    assert assertIfNotActive();

    if (currentTx.isActive()) {
      return;
    }

    begin(newReadOnlyTxInstance(FrontendTransactionImpl.generateTxId()));
  }

  public int begin(FrontendTransactionImpl transaction) {
    checkOpenness();
    assert assertIfNotActive();

    // CHECK IT'S NOT INSIDE A HOOK
    if (!inHook.isEmpty()) {
      throw new IllegalStateException("Cannot begin a transaction while a hook is executing");
    }

    if (currentTx.isActive()) {
      if (currentTx instanceof FrontendTransactionImpl) {
        return currentTx.beginInternal();
      }
    }

    // WAKE UP LISTENERS
    for (var listener : browseListeners()) {
      try {
        listener.onBeforeTxBegin(currentTx);
      } catch (Exception e) {
        LogManager.instance().error(this, "Error before tx begin", e);
      }
    }

    currentTx = transaction;

    return currentTx.beginInternal();
  }

  protected FrontendTransactionImpl newTxInstance(long txId) {
    assert assertIfNotActive();
    return new FrontendTransactionImpl(this);
  }

  protected FrontendTransactionImpl newReadOnlyTxInstance(long txId) {
    assert assertIfNotActive();
    return new FrontendTransactionImpl(this, true);
  }


  public void setDefaultTransactionMode() {
    if (!(currentTx instanceof FrontendTransactionNoTx)) {
      currentTx = new FrontendTransactionNoTx(this);
    }
  }

  /**
   * Creates a new EntityImpl.
   */
  public EntityImpl newInstance() {
    assert assertIfNotActive();
    return newInstance(Entity.DEFAULT_CLASS_NAME);
  }

  @Override
  public EntityImpl newInternalInstance() {
    assert assertIfNotActive();

    var clusterId = getClusterIdByName(MetadataDefault.CLUSTER_INTERNAL_NAME);
    var rid = new ChangeableRecordId();

    rid.setClusterId(clusterId);

    var entity = new EntityImpl(this, rid);
    entity.setInternalStatus(RecordElement.STATUS.LOADED);

    var tx = (FrontendTransactionImpl) currentTx;
    tx.addRecordOperation(entity, RecordOperation.CREATED);

    return entity;
  }

  @Override
  public Blob newBlob(byte[] bytes) {
    assert assertIfNotActive();

    var blob = new RecordBytes(this, bytes);
    blob.setInternalStatus(RecordElement.STATUS.LOADED);

    var tx = (FrontendTransactionImpl) currentTx;
    tx.addRecordOperation(blob, RecordOperation.CREATED);

    return blob;
  }

  @Override
  public Blob newBlob() {
    assert assertIfNotActive();

    var blob = new RecordBytes(this);
    blob.setInternalStatus(RecordElement.STATUS.LOADED);

    var tx = (FrontendTransactionImpl) currentTx;
    tx.addRecordOperation(blob, RecordOperation.CREATED);

    return blob;
  }

  /**
   * Creates a entity with specific class.
   *
   * @param className the name of class that should be used as a class of created entity.
   * @return new instance of entity.
   */
  @Override
  public EntityImpl newInstance(final String className) {
    assert assertIfNotActive();

    var cls = getMetadata().getImmutableSchemaSnapshot().getClass(className);
    if (cls == null) {
      throw new IllegalArgumentException("Class " + className + " not found");
    }

    if (cls.isVertexType()) {
      throw new IllegalArgumentException(
          "The class " + cls.getName()
              + " is a vertex type and cannot be used to create an entity, "
              + "please use newVertex() method");
    }
    if (cls.isEdgeType()) {
      throw new IllegalArgumentException(
          "The class " + cls.getName() + " is an edge type and cannot be used to create an entity, "
              + "please use newStatefulEdge() method");
    }
    var entity = new EntityImpl(this, className);
    entity.setInternalStatus(RecordElement.STATUS.LOADED);

    currentTx.addRecordOperation(entity, RecordOperation.CREATED);
    //init default property values, can not do that in constructor as it is not registerd in tx
    entity.convertPropertiesToClassAndInitDefaultValues(entity.getImmutableSchemaClass(this));
    return entity;
  }

  @Override
  public Entity newEntity() {
    assert assertIfNotActive();
    return newInstance(Entity.DEFAULT_CLASS_NAME);
  }

  @Override
  public <T extends DBRecord> T createOrLoadRecordFromJson(String json) {
    assert assertIfNotActive();
    return (T) RecordSerializerJackson.fromString(this, json);
  }

  @Override
  public Entity createOrLoadEntityFromJson(String json) {
    assert assertIfNotActive();
    var result = RecordSerializerJackson.fromString(this, json);

    if (result instanceof Entity) {
      return (Entity) result;
    }

    throw new DatabaseException(getDatabaseName(), "The record is not an entity");
  }

  @Override
  public Entity newEntity(String className) {
    assert assertIfNotActive();
    return newInstance(className);
  }


  @Override
  public EmbeddedEntity newEmbeddedEntity(SchemaClass schemaClass) {
    assert assertIfNotActive();
    return new EmbeddedEntityImpl(schemaClass != null ? schemaClass.getName() : null, this);
  }

  @Override
  public EmbeddedEntity newEmbeddedEntity(String schemaClass) {
    assert assertIfNotActive();
    return new EmbeddedEntityImpl(schemaClass, this);
  }

  @Override
  public EmbeddedEntity newEmbeddedEntity() {
    assert assertIfNotActive();
    return new EmbeddedEntityImpl(this);
  }


  public Entity newEntity(SchemaClass clazz) {
    assert assertIfNotActive();
    return newInstance(clazz.getName());
  }

  public Vertex newVertex(final String className) {
    assert assertIfNotActive();

    var cls = getMetadata().getImmutableSchemaSnapshot().getClass(className);
    if (cls == null) {
      throw new IllegalArgumentException("Class " + className + " not found");
    }
    if (!cls.isVertexType()) {
      throw new IllegalArgumentException(
          "The class " + cls.getName()
              + " is not a vertex type and cannot be used to create a vertex, "
              + "please use newInstance() method");
    }

    checkSecurity(Rule.ResourceGeneric.CLASS, Role.PERMISSION_CREATE, className);
    var vertex = new VertexEntityImpl(this, className);
    currentTx.addRecordOperation(vertex, RecordOperation.CREATED);
    vertex.convertPropertiesToClassAndInitDefaultValues(vertex.getImmutableSchemaClass(this));

    return vertex;
  }

  private StatefullEdgeEntityImpl newStatefulEdgeInternal(final String className) {
    var cls = getMetadata().getImmutableSchemaSnapshot().getClass(className);
    if (cls == null) {
      throw new IllegalArgumentException("Class " + className + " not found");
    }
    if (!cls.isEdgeType()) {
      throw new IllegalArgumentException(
          "The class " + cls.getName()
              + " is not an edge type and cannot be used to create a stateful edge, "
              + "please use newInstance() method");
    }

    checkSecurity(Rule.ResourceGeneric.CLASS, Role.PERMISSION_CREATE, className);
    var edge = new StatefullEdgeEntityImpl(this, className);
    currentTx.addRecordOperation(edge, RecordOperation.CREATED);

    edge.convertPropertiesToClassAndInitDefaultValues(edge.getImmutableSchemaClass(this));

    return edge;
  }

  @Override
  public Vertex newVertex(SchemaClass type) {
    assert assertIfNotActive();
    if (type == null) {
      return newVertex("V");
    }
    return newVertex(type.getName());
  }

  @Override
  public StatefulEdge newStatefulEdge(Vertex from, Vertex to, String type) {
    assert assertIfNotActive();
    var cl = getMetadata().getImmutableSchemaSnapshot().getClass(type);
    if (cl == null || !cl.isEdgeType()) {
      throw new IllegalArgumentException(type + " is not a regular edge class");
    }
    if (cl.isAbstract()) {
      throw new IllegalArgumentException(
          type + " is an abstract class and can not be used for creation of regular edge");
    }

    return (StatefulEdge) addEdgeInternal(from, to, type, true);
  }

  @Override
  public Edge newLightweightEdge(Vertex from, Vertex to, @Nonnull String type) {
    assert assertIfNotActive();
    var cl = getMetadata().getImmutableSchemaSnapshot().getClass(type);
    if (cl == null || !cl.isEdgeType()) {
      throw new IllegalArgumentException(type + " is not a lightweight edge class");
    }
    if (!cl.isAbstract()) {
      throw new IllegalArgumentException(
          type + " is not an abstract class and can not be used for creation of lightweight edge");
    }

    return addEdgeInternal(from, to, type, false);
  }

  @Override
  public StatefulEdge newStatefulEdge(Vertex from, Vertex to, SchemaClass type) {
    assert assertIfNotActive();
    if (type == null) {
      return newStatefulEdge(from, to, "E");
    }

    return newStatefulEdge(from, to, type.getName());
  }

  @Override
  public Edge newLightweightEdge(Vertex from, Vertex to, @Nonnull SchemaClass type) {
    assert assertIfNotActive();
    return newLightweightEdge(from, to, type.getName());
  }

  private EdgeInternal addEdgeInternal(
      final Vertex toVertex,
      final Vertex inVertex,
      String className,
      boolean isRegular) {
    Objects.requireNonNull(toVertex, "From vertex is null");
    Objects.requireNonNull(inVertex, "To vertex is null");

    EdgeInternal edge;
    EntityImpl outEntity;
    EntityImpl inEntity;

    var outEntityModified = false;
    if (checkDeletedInTx(toVertex)) {
      throw new RecordNotFoundException(getDatabaseName(),
          toVertex.getIdentity(), "The vertex " + toVertex.getIdentity() + " has been deleted");
    }

    if (checkDeletedInTx(inVertex)) {
      throw new RecordNotFoundException(getDatabaseName(),
          inVertex.getIdentity(), "The vertex " + inVertex.getIdentity() + " has been deleted");
    }

    outEntity = (EntityImpl) toVertex;
    inEntity = (EntityImpl) inVertex;

    Schema schema = getMetadata().getImmutableSchemaSnapshot();
    final var edgeType = schema.getClass(className);

    if (edgeType == null) {
      throw new IllegalArgumentException("Class " + className + " does not exist");
    }

    className = edgeType.getName();

    var createLightweightEdge =
        !isRegular
            && (edgeType.isAbstract() || className.equals(EdgeInternal.CLASS_NAME));
    if (!isRegular && !createLightweightEdge) {
      throw new IllegalArgumentException(
          "Cannot create lightweight edge for class " + className + " because it is not abstract");
    }

    final var outFieldName = Vertex.getEdgeLinkFieldName(Direction.OUT, className);
    final var inFieldName = Vertex.getEdgeLinkFieldName(Direction.IN, className);

    if (createLightweightEdge) {
      var lightWeightEdge = newLightweightEdgeInternal(className, toVertex, inVertex);
      var transaction2 = getActiveTransaction();
      var transaction3 = getActiveTransaction();
      VertexEntityImpl.createLink(this, transaction3.load(toVertex), transaction2.load(inVertex),
          outFieldName);
      var transaction = getActiveTransaction();
      var transaction1 = getActiveTransaction();
      VertexEntityImpl.createLink(this, transaction1.load(inVertex), transaction.load(toVertex),
          inFieldName);
      edge = lightWeightEdge;
    } else {
      var statefulEdge = newStatefulEdgeInternal(className);
      var transaction = getActiveTransaction();
      statefulEdge.setPropertyInternal(EdgeInternal.DIRECTION_OUT, transaction.load(toVertex));
      statefulEdge.setPropertyInternal(Edge.DIRECTION_IN, inEntity);

      if (!outEntityModified) {
        // OUT-VERTEX ---> IN-VERTEX/EDGE
        VertexEntityImpl.createLink(this, outEntity, statefulEdge, outFieldName);
      }

      // IN-VERTEX ---> OUT-VERTEX/EDGE
      VertexEntityImpl.createLink(this, inEntity, statefulEdge, inFieldName);
      edge = statefulEdge;
    }
    // OK

    return edge;
  }

  private boolean checkDeletedInTx(Vertex currentVertex) {
    RID id;
    var transaction1 = getActiveTransaction();
    if (!transaction1.load(currentVertex).exists()) {
      var transaction = getActiveTransaction();
      id = transaction.load(currentVertex).getIdentity();
    } else {
      return false;
    }

    final var oper = getTransactionInternal().getRecordEntry(id);
    if (oper == null) {
      return ((RecordId) id).isTemporary();
    } else {
      return oper.type == RecordOperation.DELETED;
    }
  }

  /**
   * {@inheritDoc}
   */
  public RecordIteratorClass browseClass(final @Nonnull String className) {
    assert assertIfNotActive();
    return browseClass(className, true);
  }

  /**
   * {@inheritDoc}
   */
  public RecordIteratorClass browseClass(
      final @Nonnull String className, final boolean iPolymorphic) {
    return browseClass(className, iPolymorphic, true);
  }

  @Override
  public RecordIteratorClass browseClass(@Nonnull String className, boolean iPolymorphic,
      boolean forwardDirection) {
    assert assertIfNotActive();
    if (getMetadata().getImmutableSchemaSnapshot().getClass(className) == null) {
      throw new IllegalArgumentException(
          "Class '" + className + "' not found in current database");
    }

    checkSecurity(Rule.ResourceGeneric.CLASS, Role.PERMISSION_READ, className);
    return new RecordIteratorClass(this, className, iPolymorphic, forwardDirection);
  }

  @Override
  public RecordIteratorClass browseClass(@Nonnull SchemaClass clz) {
    assert assertIfNotActive();

    checkSecurity(Rule.ResourceGeneric.CLASS, Role.PERMISSION_READ, clz.getName());
    return new RecordIteratorClass(this, (SchemaClassInternal) clz,
        true, true);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <REC extends RecordAbstract> RecordIteratorCluster<REC> browseCluster(
      final String iClusterName) {
    assert assertIfNotActive();
    checkSecurity(Rule.ResourceGeneric.CLUSTER, Role.PERMISSION_READ, iClusterName);

    return new RecordIteratorCluster<>(this, getClusterIdByName(iClusterName), true);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Iterable<SessionListener> getListeners() {
    assert assertIfNotActive();
    return getListenersCopy();
  }

  /**
   * Returns the number of the records of the class iClassName.
   */
  public long countClass(final String iClassName) {
    assert assertIfNotActive();
    return countClass(iClassName, true);
  }

  /**
   * Returns the number of the records of the class iClassName considering also sub classes if
   * polymorphic is true.
   */
  public long countClass(final String iClassName, final boolean iPolymorphic) {
    assert assertIfNotActive();
    final var cls =
        (SchemaImmutableClass) getMetadata().getImmutableSchemaSnapshot().getClass(iClassName);
    if (cls == null) {
      throw new IllegalArgumentException("Class not found in database");
    }

    return countClass(cls, iPolymorphic);
  }

  protected long countClass(final SchemaImmutableClass cls, final boolean iPolymorphic) {
    checkOpenness();
    assert assertIfNotActive();

    var totalOnDb = cls.countImpl(iPolymorphic, this);

    long deletedInTx = 0;
    long addedInTx = 0;
    var className = cls.getName();
    if (getTransactionInternal().isActive()) {
      for (var op : getTransactionInternal().getRecordOperationsInternal()) {
        if (op.type == RecordOperation.DELETED) {
          final DBRecord rec = op.record;
          if (rec instanceof EntityImpl) {
            var schemaClass = ((EntityImpl) rec).getImmutableSchemaClass(this);
            if (iPolymorphic) {
              if (schemaClass.isSubClassOf(className)) {
                deletedInTx++;
              }
            } else {
              if (schemaClass != null && (className.equals(schemaClass.getName()))) {
                deletedInTx++;
              }
            }
          }
        }
        if (op.type == RecordOperation.CREATED) {
          final DBRecord rec = op.record;
          if (rec instanceof EntityImpl) {
            var schemaClass = ((EntityImpl) rec).getImmutableSchemaClass(this);
            if (schemaClass != null) {
              if (iPolymorphic) {
                if (schemaClass.isSubClassOf(className)) {
                  addedInTx++;
                }
              } else {
                if (className.equals(schemaClass.getName())) {
                  addedInTx++;
                }
              }
            }
          }
        }
      }
    }

    return (totalOnDb + addedInTx) - deletedInTx;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean commit() {
    checkOpenness();
    assert assertIfNotActive();

    if (currentTx.getStatus() == TXSTATUS.ROLLBACKING) {
      throw new RollbackException("Transaction is rolling back");
    }

    if (!currentTx.isActive()) {
      throw new DatabaseException(getDatabaseName(),
          "No active transaction to commit. Call begin() first");
    }

    if (currentTx.amountOfNestedTxs() > 1) {
      // This just do count down no real commit here
      currentTx.commitInternal();
      return false;
    }

    // WAKE UP LISTENERS

    try {
      beforeCommitOperations();
    } catch (BaseException e) {
      try {
        rollback();
      } catch (Exception re) {
        LogManager.instance()
            .error(this, "Exception during rollback `%08X`", re, System.identityHashCode(re));
      }
      throw e;
    }
    try {
      currentTx.commitInternal();
    } catch (RuntimeException e) {

      if ((e instanceof HighLevelException) || (e instanceof NeedRetryException)) {
        LogManager.instance()
            .debug(this, "Error on transaction commit `%08X`", e, System.identityHashCode(e));
      } else {
        LogManager.instance()
            .error(this, "Error on transaction commit `%08X`", e, System.identityHashCode(e));
      }

      // WAKE UP ROLLBACK LISTENERS
      beforeRollbackOperations();

      try {
        // ROLLBACK TX AT DB LEVEL
        currentTx.internalRollback();
      } catch (Exception re) {
        LogManager.instance()
            .error(
                this, "Error during transaction rollback `%08X`", re, System.identityHashCode(re));
      }

      // WAKE UP ROLLBACK LISTENERS
      afterRollbackOperations();
      throw e;
    }

    return true;
  }

  protected void beforeCommitOperations() {
    assert assertIfNotActive();
    for (var listener : browseListeners()) {
      try {
        listener.onBeforeTxCommit(currentTx);
      } catch (Exception e) {
        LogManager.instance()
            .error(
                this,
                "Cannot commit the transaction: caught exception on execution of"
                    + " %s.onBeforeTxCommit() `%08X`",
                e,
                listener.getClass().getName(),
                System.identityHashCode(e));
        throw BaseException.wrapException(
            new TransactionException(getDatabaseName(),
                "Cannot commit the transaction: caught exception on execution of "
                    + listener.getClass().getName()
                    + "#onBeforeTxCommit()"),
            e, getDatabaseName());
      }
    }
  }

  public void afterCommitOperations() {
    assert assertIfNotActive();
    for (var listener : browseListeners()) {
      try {
        listener.onAfterTxCommit(currentTx);
      } catch (Exception e) {
        final var message =
            "Error after the transaction has been committed. The transaction remains valid. The"
                + " exception caught was on execution of "
                + listener.getClass()
                + ".onAfterTxCommit() `%08X`";

        LogManager.instance().error(this, message, e, System.identityHashCode(e));

        throw BaseException.wrapException(
            new TransactionBlockedException(getDatabaseName(), message), e,
            getDatabaseName());
      }
    }
  }

  protected void beforeRollbackOperations() {
    assert assertIfNotActive();
    for (var listener : browseListeners()) {
      try {
        listener.onBeforeTxRollback(currentTx);
      } catch (Exception t) {
        LogManager.instance()
            .error(this, "Error before transaction rollback `%08X`", t, System.identityHashCode(t));
      }
    }
  }

  protected void afterRollbackOperations() {
    assert assertIfNotActive();
    for (var listener : browseListeners()) {
      try {
        listener.onAfterTxRollback(currentTx);
      } catch (Exception t) {
        LogManager.instance()
            .error(this, "Error after transaction rollback `%08X`", t, System.identityHashCode(t));
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void rollback() {
    assert assertIfNotActive();
    rollback(false);
  }

  @Override
  public void rollback(boolean force) throws TransactionException {
    checkOpenness();
    assert assertIfNotActive();

    if (currentTx.isActive()) {
      if (!force && currentTx.amountOfNestedTxs() > 1) {
        // This just decrement the counter no real rollback here
        currentTx.rollbackInternal();
        return;
      }

      // WAKE UP LISTENERS
      beforeRollbackOperations();
      currentTx.rollbackInternal(force, -1);
      // WAKE UP LISTENERS
      afterRollbackOperations();
    }
  }

  /**
   * This method is internal, it can be subject to signature change or be removed, do not use.
   */
  @Override
  public DatabaseSession getUnderlying() {
    throw new UnsupportedOperationException();
  }

  @Override
  public CurrentStorageComponentsFactory getStorageVersions() {
    assert assertIfNotActive();
    return componentsFactory;
  }

  public RecordSerializer getSerializer() {
    assert assertIfNotActive();
    return serializer;
  }

  /**
   * Sets serializer for the database which will be used for entity serialization.
   *
   * @param serializer the serializer to set.
   */
  public void setSerializer(RecordSerializer serializer) {
    assert assertIfNotActive();
    this.serializer = serializer;
  }

  @Override
  public void resetInitialization() {
    assert assertIfNotActive();
    for (var h : hooks) {
      h.onUnregister();
    }

    hooks.clear();
    close();

    initialized = false;
  }

  public void checkSecurity(final int operation, final Identifiable record, String cluster) {
    assert assertIfNotActive();
    if (cluster == null) {
      cluster = getClusterNameById(record.getIdentity().getClusterId());
    }
    checkSecurity(Rule.ResourceGeneric.CLUSTER, operation, cluster);

    if (record instanceof EntityImpl) {
      var clazzName = ((EntityImpl) record).getSchemaClassName();
      if (clazzName != null) {
        checkSecurity(Rule.ResourceGeneric.CLASS, operation, clazzName);
      }
    }
  }

  /**
   * @return <code>true</code> if database is obtained from the pool and <code>false</code>
   * otherwise.
   */
  @Override
  public boolean isPooled() {
    return false;
  }

  /**
   * Use #activateOnCurrentThread instead.
   */
  @Deprecated
  public void setCurrentDatabaseInThreadLocal() {
    activateOnCurrentThread();
  }

  /**
   * Activates current database instance on current thread.
   */
  @Override
  public void activateOnCurrentThread() {
    activeSession.set(this);
  }

  @Override
  public boolean isActiveOnCurrentThread() {
    return activeSession.get() == this;
  }

  protected void checkOpenness() {
    if (status == STATUS.CLOSED) {
      throw new DatabaseException(getDatabaseName(), "Database '" + getURL() + "' is closed");
    }
  }

  private void popInHook(Identifiable id) {
    inHook.remove(id);
  }

  private boolean pushInHook(Identifiable id) {
    return inHook.add(id);
  }

  protected void checkRecordClass(
      final SchemaClass recordClass, final String iClusterName, final RecordId rid) {
    assert assertIfNotActive();

    final var clusterIdClass =
        metadata.getImmutableSchemaSnapshot().getClassByClusterId(rid.getClusterId());
    if (recordClass == null && clusterIdClass != null
        || clusterIdClass == null && recordClass != null
        || (recordClass != null && !recordClass.equals(clusterIdClass))) {
      throw new IllegalArgumentException(
          "Record saved into cluster '"
              + iClusterName
              + "' should be saved with class '"
              + clusterIdClass
              + "' but has been created with class '"
              + recordClass
              + "'");
    }
  }

  protected void init() {
    assert assertIfNotActive();
    currentTx = new FrontendTransactionNoTx(this);
  }

  @Override
  public boolean assertIfNotActive() {
    var currentDatabase = activeSession.get();

    //noinspection deprecation
    if (currentDatabase instanceof DatabaseDocumentTx databaseDocumentTx) {
      currentDatabase = databaseDocumentTx.internal;
    }

    if (currentDatabase != this) {
      throw new SessionNotActivatedException(getDatabaseName());
    }

    return true;
  }

  public int[] getBlobClusterIds() {
    assert assertIfNotActive();
    return getMetadata().getSchema().getBlobClusters().toIntArray();
  }


  @Override
  public SharedContext getSharedContext() {
    assert assertIfNotActive();
    return sharedContext;
  }


  public EdgeInternal newLightweightEdgeInternal(String iClassName, Vertex from, Vertex to) {
    assert assertIfNotActive();
    var clazz =
        (SchemaImmutableClass) getMetadata().getImmutableSchemaSnapshot().getClass(iClassName);

    return new EdgeImpl(this, from, to, clazz);
  }

  public Edge newRegularEdge(String iClassName, Vertex from, Vertex to) {
    assert assertIfNotActive();
    var cl = getMetadata().getImmutableSchemaSnapshot().getClass(iClassName);

    if (cl == null || !cl.isEdgeType()) {
      throw new IllegalArgumentException(iClassName + " is not an edge class");
    }

    return addEdgeInternal(from, to, iClassName, true);
  }

  public synchronized void queryStarted(String id, QueryDatabaseState state) {
    assert assertIfNotActive();

    if (this.activeQueries.size() > 1 && this.activeQueries.size() % 10 == 0) {
      var msg =
          "This database instance has "
              + activeQueries.size()
              + " open command/query result sets, please make sure you close them with"
              + " ResultSet.close()";
      LogManager.instance().warn(this, msg);
      if (LogManager.instance().isDebugEnabled()) {
        activeQueries.values().stream()
            .map(pendingQuery -> pendingQuery.getResultSet().getExecutionPlan())
            .filter(Objects::nonNull)
            .forEach(plan -> LogManager.instance().debug(this, plan.toString()));
      }
    }
    this.activeQueries.put(id, state);

    getListeners().forEach((it) -> it.onCommandStart(this, state.getResultSet()));
  }

  public void queryClosed(String id) {
    assert assertIfNotActive();

    var removed = this.activeQueries.remove(id);
    getListeners().forEach((it) -> it.onCommandEnd(this, removed.getResultSet()));

  }

  public synchronized void closeActiveQueries() {
    while (!activeQueries.isEmpty()) {
      this.activeQueries
          .values()
          .iterator()
          .next()
          .close(); // the query automatically unregisters itself
    }
  }

  public Map<String, QueryDatabaseState> getActiveQueries() {
    assert assertIfNotActive();

    return activeQueries;
  }

  public ResultSet getActiveQuery(String id) {
    assert assertIfNotActive();

    var state = activeQueries.get(id);
    if (state != null) {
      return state.getResultSet();
    } else {
      return null;
    }
  }

  @Override
  public boolean isClusterEdge(int cluster) {
    assert assertIfNotActive();
    var clazz = getMetadata().getImmutableSchemaSnapshot().getClassByClusterId(cluster);
    return clazz != null && clazz.isEdgeType();
  }

  @Override
  public boolean isClusterVertex(int cluster) {
    assert assertIfNotActive();
    var clazz = getMetadata().getImmutableSchemaSnapshot().getClassByClusterId(cluster);
    return clazz != null && clazz.isVertexType();
  }


  public Map<UUID, BonsaiCollectionPointer> getCollectionsChanges() {
    assert assertIfNotActive();

    if (collectionsChanges == null) {
      collectionsChanges = new HashMap<>();
    }

    return collectionsChanges;
  }

  @Override
  public void executeInTx(@Nonnull Consumer<Transaction> code) {
    var ok = false;
    assert assertIfNotActive();
    var tx = begin();
    try {
      code.accept(tx);
      ok = true;
    } finally {
      finishTx(ok);
    }
  }

  @Override
  public <T> void executeInTxBatches(
      Iterable<T> iterable, int batchSize, BiConsumer<Transaction, T> consumer) {
    var ok = false;
    assert assertIfNotActive();
    var counter = 0;

    var tx = begin();
    try {
      for (var t : iterable) {
        consumer.accept(tx, t);
        counter++;

        if (counter % batchSize == 0) {
          commit();
          begin();
        }
      }

      ok = true;
    } finally {
      finishTx(ok);
    }
  }

  @Override
  public <T> void forEachInTx(Iterator<T> iterator, BiConsumer<Transaction, T> consumer) {
    assert assertIfNotActive();

    forEachInTx(iterator, (db, t) -> {
      consumer.accept(db, t);
      return true;
    });
  }

  @Override
  public <T> void forEachInTx(Iterable<T> iterable, BiConsumer<Transaction, T> consumer) {
    assert assertIfNotActive();

    forEachInTx(iterable.iterator(), consumer);
  }

  @Override
  public <T> void forEachInTx(Stream<T> stream, BiConsumer<Transaction, T> consumer) {
    assert assertIfNotActive();

    try (var s = stream) {
      forEachInTx(s.iterator(), consumer);
    }
  }

  @Override
  public <T> void forEachInTx(Iterator<T> iterator,
      BiFunction<Transaction, T, Boolean> consumer) {
    var ok = false;
    assert assertIfNotActive();

    var tx = begin();
    try {
      while (iterator.hasNext()) {
        var cont = consumer.apply(tx, iterator.next());
        commit();
        if (!cont) {
          break;
        }
        begin();
      }

      ok = true;
    } finally {
      finishTx(ok);
    }
  }

  @Override
  public <T> void forEachInTx(Iterable<T> iterable,
      BiFunction<Transaction, T, Boolean> consumer) {
    assert assertIfNotActive();

    forEachInTx(iterable.iterator(), consumer);
  }

  @Override
  public <T> void forEachInTx(Stream<T> stream,
      BiFunction<Transaction, T, Boolean> consumer) {
    assert assertIfNotActive();

    try (stream) {
      forEachInTx(stream.iterator(), consumer);
    }
  }

  private void finishTx(boolean ok) {
    if (currentTx.isActive()) {
      if (ok && currentTx.getStatus() != TXSTATUS.ROLLBACKING) {
        commit();
      } else {
        if (isActiveOnCurrentThread()) {
          rollback();
        } else {
          currentTx.rollbackInternal();
        }
      }
    }
  }

  @Override
  public <T> void executeInTxBatches(
      @Nonnull Iterator<T> iterator, int batchSize, BiConsumer<Transaction, T> consumer) {
    var ok = false;
    assert assertIfNotActive();
    var counter = 0;

    var tx = begin();
    try {
      while (iterator.hasNext()) {
        consumer.accept(tx, iterator.next());
        counter++;

        if (counter % batchSize == 0) {
          commit();
          begin();
        }
      }

      ok = true;
    } finally {
      finishTx(ok);
    }
  }

  @Override
  public <T> void executeInTxBatches(
      Iterator<T> iterator, BiConsumer<Transaction, T> consumer) {
    assert assertIfNotActive();

    executeInTxBatches(
        iterator,
        getConfiguration().getValueAsInteger(GlobalConfiguration.TX_BATCH_SIZE),
        consumer);
  }

  @Override
  public <T> void executeInTxBatches(
      Iterable<T> iterable, BiConsumer<Transaction, T> consumer) {
    assert assertIfNotActive();

    executeInTxBatches(
        iterable,
        getConfiguration().getValueAsInteger(GlobalConfiguration.TX_BATCH_SIZE),
        consumer);
  }

  @Override
  public <T> void executeInTxBatches(
      Stream<T> stream, int batchSize, BiConsumer<Transaction, T> consumer) {
    assert assertIfNotActive();

    try (stream) {
      executeInTxBatches(stream.iterator(), batchSize, consumer);
    }
  }

  @Override
  public <T> void executeInTxBatches(Stream<T> stream, BiConsumer<Transaction, T> consumer) {
    assert assertIfNotActive();

    try (stream) {
      executeInTxBatches(stream.iterator(), consumer);
    }
  }

  @Override
  public <R> R computeInTx(Function<Transaction, R> code) {
    assert assertIfNotActive();
    var ok = false;
    var tx = begin();
    try {
      var result = code.apply(tx);
      ok = true;
      return result;
    } finally {
      finishTx(ok);
    }
  }

  @Override
  public int activeTxCount() {
    assert assertIfNotActive();

    var transaction = getTransactionInternal();
    return transaction.amountOfNestedTxs();
  }

  @Override
  public <T> EmbeddedList<T> newEmbeddedList() {
    return new EntityEmbeddedListImpl<>();
  }

  @Override
  public <T> EmbeddedList<T> newEmbeddedList(int size) {
    return new EntityEmbeddedListImpl<>(size);
  }

  @Override
  public <T> EmbeddedList<T> newEmbeddedList(Collection<T> list) {
    return (EmbeddedList<T>) PropertyTypeInternal.EMBEDDEDLIST.copy(list, this);
  }

  @Override
  public EmbeddedList<String> newEmbeddedList(String[] source) {
    var trackedList = new EntityEmbeddedListImpl<String>(source.length);
    trackedList.addAll(Arrays.asList(source));
    return trackedList;
  }

  @Override
  public EmbeddedList<Date> newEmbeddedList(Date[] source) {
    var trackedList = new EntityEmbeddedListImpl<Date>(source.length);
    trackedList.addAll(Arrays.asList(source));
    return trackedList;
  }

  @Override
  public EmbeddedList<Byte> newEmbeddedList(byte[] source) {
    var trackedList = new EntityEmbeddedListImpl<Byte>(source.length);
    for (var b : source) {
      trackedList.add(b);
    }
    return trackedList;
  }

  @Override
  public EmbeddedList<Short> newEmbeddedList(short[] source) {
    var trackedList = new EntityEmbeddedListImpl<Short>(source.length);
    for (var s : source) {
      trackedList.add(s);
    }
    return trackedList;
  }

  @Override
  public EmbeddedList<Integer> newEmbeddedList(int[] source) {
    var trackedList = new EntityEmbeddedListImpl<Integer>(source.length);
    for (var i : source) {
      trackedList.add(i);
    }
    return trackedList;
  }

  @Override
  public EmbeddedList<Long> newEmbeddedList(long[] source) {
    var trackedList = new EntityEmbeddedListImpl<Long>(source.length);
    for (var l : source) {
      trackedList.add(l);
    }
    return trackedList;
  }

  @Override
  public EmbeddedList<Float> newEmbeddedList(float[] source) {
    var trackedList = new EntityEmbeddedListImpl<Float>(source.length);
    for (var f : source) {
      trackedList.add(f);
    }
    return trackedList;
  }

  @Override
  public EmbeddedList<Double> newEmbeddedList(double[] source) {
    var trackedList = new EntityEmbeddedListImpl<Double>(source.length);
    for (var d : source) {
      trackedList.add(d);
    }
    return trackedList;
  }

  @Override
  public EmbeddedList<Boolean> newEmbeddedList(boolean[] source) {
    var trackedList = new EntityEmbeddedListImpl<Boolean>(source.length);
    for (var b : source) {
      trackedList.add(b);
    }
    return trackedList;
  }

  @Override
  public LinkList newLinkList() {
    return new EntityLinkListImpl(this);
  }

  @Override
  public LinkList newLinkList(int size) {
    return new EntityLinkListImpl(this, size);
  }

  @Override
  public LinkList newLinkList(Collection<? extends Identifiable> source) {
    var list = new EntityLinkListImpl(this, source.size());
    list.addAll(source);
    return list;
  }

  @Override
  public <T> EmbeddedSet<T> newEmbeddedSet() {
    return new EntityEmbeddedSetImpl<>();
  }

  @Override
  public <T> EmbeddedSet<T> newEmbeddedSet(int size) {
    return new EntityEmbeddedSetImpl<>(size);
  }

  @Override
  public <T> EmbeddedSet<T> newEmbeddedSet(Collection<T> set) {
    return (EmbeddedSet<T>) PropertyTypeInternal.EMBEDDEDSET.copy(set, this);
  }

  @Override
  public LinkSet newLinkSet() {
    return new EntityLinkSetImpl(this);
  }

  @Override
  public LinkSet newLinkSet(int size) {
    return new EntityLinkSetImpl(size, this);
  }

  @Override
  public LinkSet newLinkSet(Collection<? extends Identifiable> source) {
    var linkSet = new EntityLinkSetImpl(source.size(), this);
    linkSet.addAll(source);
    return linkSet;
  }

  @Override
  public <V> Map<String, V> newEmbeddedMap() {
    return new EntityEmbeddedMapImpl<>();
  }

  @Override
  public <V> Map<String, V> newEmbeddedMap(int size) {
    return new EntityEmbeddedMapImpl<>(size);
  }

  @Override
  public <V> EmbeddedMap<V> newEmbeddedMap(Map<String, V> map) {
    return (EmbeddedMap<V>) PropertyTypeInternal.EMBEDDEDMAP.copy(map, this);
  }

  @Override
  public Map<String, Identifiable> newLinkMap() {
    return new EntityLinkMapIml(this);
  }

  @Override
  public Map<String, Identifiable> newLinkMap(int size) {
    return new EntityLinkMapIml(size, this);
  }

  @Override
  public Map<String, Identifiable> newLinkMap(Map<String, ? extends Identifiable> source) {
    var linkMap = new EntityLinkMapIml(source.size(), this);
    linkMap.putAll(source);
    return linkMap;
  }

  @Override
  public @Nonnull Transaction getActiveTransaction() {
    if (currentTx.isActive()) {
      return currentTx;
    }

    throw new DatabaseException(this, "There is no active transaction in session");
  }

  @Nullable
  @Override
  public Transaction getActiveTransactionOrNull() {
    if (currentTx.isActive()) {
      return currentTx;
    }

    return null;
  }

  private void checkTxActive() {
    if (currentTx == null || !currentTx.isActive()) {
      throw new TransactionException(getDatabaseName(), "There is no active transaction");
    }
  }

  private void ensureEdgeConsistencyOnDeletion(@Nonnull EntityImpl entity,
      @Nonnull SchemaImmutableClass clazz) {
    if (clazz.isVertexType()) {
      VertexEntityImpl.deleteLinks(entity.asVertex());
    } else if (clazz.isEdgeType()) {
      StatefullEdgeEntityImpl.deleteLinks(this, entity.asEdge());
    }
  }
}
