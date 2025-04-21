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
import com.jetbrains.youtrack.db.api.common.query.LiveQueryMonitor;
import com.jetbrains.youtrack.db.api.common.query.collection.embedded.EmbeddedList;
import com.jetbrains.youtrack.db.api.common.query.collection.embedded.EmbeddedMap;
import com.jetbrains.youtrack.db.api.common.query.collection.embedded.EmbeddedSet;
import com.jetbrains.youtrack.db.api.common.query.collection.links.LinkList;
import com.jetbrains.youtrack.db.api.common.query.collection.links.LinkMap;
import com.jetbrains.youtrack.db.api.common.query.collection.links.LinkSet;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.HighLevelException;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.exception.SchemaException;
import com.jetbrains.youtrack.db.api.exception.SecurityException;
import com.jetbrains.youtrack.db.api.exception.TransactionException;
import com.jetbrains.youtrack.db.api.query.LiveQueryResultListener;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Edge;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.record.RecordHook;
import com.jetbrains.youtrack.db.api.record.RecordHook.TYPE;
import com.jetbrains.youtrack.db.api.record.StatefulEdge;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.transaction.Transaction;
import com.jetbrains.youtrack.db.api.transaction.TxBiConsumer;
import com.jetbrains.youtrack.db.api.transaction.TxBiFunction;
import com.jetbrains.youtrack.db.api.transaction.TxConsumer;
import com.jetbrains.youtrack.db.api.transaction.TxFunction;
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
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.exception.SessionNotActivatedException;
import com.jetbrains.youtrack.db.internal.core.exception.TransactionBlockedException;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.index.IndexManagerAbstract;
import com.jetbrains.youtrack.db.internal.core.iterator.RecordIteratorClass;
import com.jetbrains.youtrack.db.internal.core.iterator.RecordIteratorCollection;
import com.jetbrains.youtrack.db.internal.core.metadata.Metadata;
import com.jetbrains.youtrack.db.internal.core.metadata.MetadataDefault;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrack.db.internal.core.metadata.security.ImmutableUser;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Rule;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityUserImpl;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EdgeImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.EdgeInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.RecordBytes;
import com.jetbrains.youtrack.db.internal.core.record.impl.StatefullEdgeEntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.VertexEntityImpl;
import com.jetbrains.youtrack.db.internal.core.security.SecurityUser;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializer;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerBinary;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.string.JSONSerializerJackson;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransaction;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransaction.TXSTATUS;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionImpl;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entity API entrypoint.
 */
@SuppressWarnings("unchecked")
public abstract class DatabaseSessionAbstract<IM extends IndexManagerAbstract> extends
    ListenerManger<SessionListener> implements DatabaseSessionInternal {

  private static final Logger logger = LoggerFactory.getLogger(DatabaseSessionAbstract.class);

  protected final HashMap<String, Object> properties = new HashMap<>();
  protected final HashSet<Identifiable> inHook = new HashSet<>();

  protected RecordSerializer serializer = RecordSerializerBinary.INSTANCE;
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

  protected SharedContext<IM> sharedContext;

  private boolean prefetchRecords;

  protected ConcurrentHashMap<String, QueryDatabaseState<ResultSet>> activeQueries = new ConcurrentHashMap<>();
  protected LinkedList<QueryDatabaseState<ResultSet>> queryState = new LinkedList<>();

  // database stats!
  protected long loadedRecordsCount;
  protected long totalRecordLoadMs;
  protected long minRecordLoadMs;
  protected long maxRecordLoadMs;
  protected long ridbagPrefetchCount;
  protected long totalRidbagPrefetchMs;
  protected long minRidbagPrefetchMs;
  protected long maxRidbagPrefetchMs;

  protected final ThreadLocal<Boolean> activeSession = new ThreadLocal<>();


  protected DatabaseSessionAbstract() {
    // DO NOTHING IS FOR EXTENDED OBJECTS
    super(false);
  }

  @Override
  public void callOnOpenListeners() {
    assert assertIfNotActive();
    wakeupOnOpenDbLifecycleListeners();
  }

  protected abstract void loadMetadata();

  @Override
  public void callOnCloseListeners() {
    assert assertIfNotActive();
    wakeupOnCloseDbLifecycleListeners();
    wakeupOnCloseListeners();
  }

  private void wakeupOnOpenDbLifecycleListeners() {
    for (var it = YouTrackDBEnginesManager.instance()
        .getDbLifecycleListeners();
        it.hasNext(); ) {
      it.next().onOpen(this);
    }
  }


  private void wakeupOnCloseDbLifecycleListeners() {
    for (var it = YouTrackDBEnginesManager.instance()
        .getDbLifecycleListeners();
        it.hasNext(); ) {
      it.next().onClose(this);
    }
  }

  private void wakeupOnCloseListeners() {
    for (var listener : getListenersCopy()) {
      try {
        listener.onClose(this);
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
  @Override
  public byte getRecordType() {
    return recordType;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long countCollectionElements(final int[] iCollectionIds) {
    assert assertIfNotActive();
    return countCollectionElements(iCollectionIds, false);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long countCollectionElements(final int iCollectionId) {
    assert assertIfNotActive();
    return countCollectionElements(iCollectionId, false);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public MetadataDefault getMetadata() {
    assert assertIfNotActive();
    checkOpenness();
    return metadata;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isRetainRecords() {
    assert assertIfNotActive();
    return retainRecords;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public DatabaseSession setRetainRecords(boolean retainRecords) {
    assert assertIfNotActive();
    this.retainRecords = retainRecords;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public DatabaseSession setStatus(final STATUS status) {
    assert assertIfNotActive();
    this.status = status;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setInternal(final ATTRIBUTES iAttribute, final Object iValue) {
    set(iAttribute, iValue);
  }

  /**
   * {@inheritDoc}
   */
  @Override
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
  @Override
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

  @Override
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
  @Override
  public boolean isMVCC() {
    assert assertIfNotActive();
    return true;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public DatabaseSession setMVCC(boolean mvcc) {
    throw new UnsupportedOperationException();
  }

  /**
   * {@inheritDoc}
   *
   * @return
   */
  @Override
  public RecordHook registerHook(final @Nonnull RecordHook iHookImpl) {
    checkOpenness();
    assert assertIfNotActive();

    if (!hooks.contains(iHookImpl)) {
      hooks.add(iHookImpl);
    }

    return iHookImpl;
  }

  /**
   * {@inheritDoc}
   */
  @Override
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
  @Override
  public List<RecordHook> getHooks() {
    assert assertIfNotActive();
    return Collections.unmodifiableList(hooks);
  }

  @Override
  public void deleteInternal(@Nonnull RecordAbstract record) {
    checkOpenness();
    assert assertIfNotActive();

    if (record instanceof EntityImpl entity) {
      ensureEdgeConsistencyOnDeletion(entity);
    }

    currentTx.preProcessRecordsAndExecuteCallCallbacks();
    record.dirty++;

    try {
      checkTxActive();
      currentTx.deleteRecord(record);
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
  @Override
  public void callbackHooks(final TYPE type, final RecordAbstract record) {
    assert assertIfNotActive();
    if (record == null || hooks.isEmpty() || record.getIdentity().getCollectionId() == 0) {
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
  @Override
  public boolean isValidationEnabled() {
    assert assertIfNotActive();
    return (Boolean) get(ATTRIBUTES_INTERNAL.VALIDATION);
  }

  /**
   * {@inheritDoc}
   */
  @Override
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
  public int getCollections() {
    assert assertIfNotActive();
    return getStorageInfo().getCollections();
  }

  @Override
  public boolean existsCollection(final String iCollectionName) {
    assert assertIfNotActive();
    return getStorageInfo().getCollectionNames()
        .contains(iCollectionName.toLowerCase(Locale.ENGLISH));
  }

  @Override
  public Collection<String> getCollectionNames() {
    assert assertIfNotActive();
    return getStorageInfo().getCollectionNames();
  }

  @Override
  public int getCollectionIdByName(final String iCollectionName) {
    if (iCollectionName == null) {
      return -1;
    }

    assert assertIfNotActive();
    return getStorageInfo().getCollectionIdByName(iCollectionName.toLowerCase(Locale.ENGLISH));
  }

  @Override
  public String getCollectionNameById(final int iCollectionId) {
    if (iCollectionId < 0) {
      return null;
    }

    assert assertIfNotActive();
    return getStorageInfo().getPhysicalCollectionNameById(iCollectionId);
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


  @Override
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
  public <RET extends RecordAbstract> RawPair<RET, RecordId> loadRecordAndNextRidInCollection(
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
  public <RET extends RecordAbstract> RawPair<RET, RecordId> loadRecordAndPreviousRidInCollection(
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


  @Override
  public int assignAndCheckCollection(DBRecord record) {
    assert assertIfNotActive();

    if (!getStorageInfo().isAssigningCollectionIds()) {
      return RID.COLLECTION_ID_INVALID;
    }

    var rid = (RecordId) record.getIdentity();
    SchemaClassInternal schemaClass = null;
    // if collection id is not set yet try to find it out
    if (rid.getCollectionId() <= RID.COLLECTION_ID_INVALID) {
      if (record instanceof EntityImpl entity) {
        schemaClass = entity.getImmutableSchemaClass(this);
        if (schemaClass != null) {
          if (schemaClass.isAbstract()) {
            throw new SchemaException(getDatabaseName(),
                "Entity belongs to abstract class "
                    + schemaClass.getName()
                    + " and cannot be saved");
          }

          return schemaClass.getCollectionForNewInstance(entity);
        } else {
          throw new DatabaseException(getDatabaseName(),
              "Cannot save (1) entity " + record + ": no class or collection defined");
        }
      } else {
        if (record instanceof RecordBytes) {
          var blobs = getBlobCollectionIds();
          if (blobs.length == 0) {
            throw new DatabaseException(getDatabaseName(),
                "Cannot save blob (2) " + record + ": no collection defined");
          } else {
            return blobs[ThreadLocalRandom.current().nextInt(blobs.length)];
          }

        } else {
          throw new DatabaseException(getDatabaseName(),
              "Cannot save (3) entity " + record + ": no class or collection defined");
        }
      }
    } else {
      if (record instanceof EntityImpl) {
        schemaClass = ((EntityImpl) record).getImmutableSchemaClass(this);
      }
    }
    // If the collection id was set check is validity
    if (rid.getCollectionId() > RID.COLLECTION_ID_INVALID) {
      if (schemaClass != null) {
        var messageCollectionName = getCollectionNameById(rid.getCollectionId());
        checkRecordClass(schemaClass, messageCollectionName, rid);
        if (!schemaClass.hasCollectionId(rid.getCollectionId())) {
          throw new IllegalArgumentException(
              "Collection name '"
                  + messageCollectionName
                  + "' (id="
                  + rid.getCollectionId()
                  + ") is not configured to store the class '"
                  + schemaClass.getName()
                  + "', valid are "
                  + Arrays.toString(schemaClass.getCollectionIds()));
        }
      }
    }

    var collectionId = rid.getCollectionId();
    if (collectionId < 0) {
      throw new DatabaseException(getDatabaseName(),
          "Impossible to set collection for record " + record + " class : " + schemaClass);
    }

    return collectionId;
  }


  @Override
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


  /**
   * Creates a new EntityImpl.
   */
  @Override
  public EntityImpl newInstance() {
    assert assertIfNotActive();
    return newInstance(Entity.DEFAULT_CLASS_NAME);
  }


  @Override
  public Entity newEntity() {
    assert assertIfNotActive();
    return newInstance(Entity.DEFAULT_CLASS_NAME);
  }

  @Override
  public <T extends DBRecord> T createOrLoadRecordFromJson(String json) {
    assert assertIfNotActive();
    return (T) JSONSerializerJackson.fromString(this, json);
  }

  @Override
  public Entity createOrLoadEntityFromJson(String json) {
    assert assertIfNotActive();
    var result = JSONSerializerJackson.fromString(this, json);

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
  public Entity newEntity(SchemaClass clazz) {
    assert assertIfNotActive();
    return newInstance(clazz.getName());
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


  /**
   * {@inheritDoc}
   */
  @Override
  public RecordIteratorClass browseClass(final @Nonnull String className) {
    assert assertIfNotActive();
    return browseClass(className, true);
  }

  /**
   * {@inheritDoc}
   */
  @Override
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
  public <REC extends RecordAbstract> RecordIteratorCollection<REC> browseCollection(
      final String iCollectionName) {
    assert assertIfNotActive();
    checkSecurity(Rule.ResourceGeneric.COLLECTION, Role.PERMISSION_READ, iCollectionName);

    return new RecordIteratorCollection<>(this, getCollectionIdByName(iCollectionName), true);
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
  @Override
  public long countClass(final String iClassName) {
    assert assertIfNotActive();
    return countClass(iClassName, true);
  }

  /**
   * Returns the number of the records of the class iClassName considering also sub classes if
   * polymorphic is true.
   */
  @Override
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
        if (logger.isDebugEnabled()) {
          LogManager.instance()
              .debug(this, "Error on transaction commit `%08X`", logger, e,
                  System.identityHashCode(e));
        }
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

  @Override
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


  @Override
  public CurrentStorageComponentsFactory getStorageVersions() {
    assert assertIfNotActive();
    return componentsFactory;
  }

  @Override
  public RecordSerializer getSerializer() {
    assert assertIfNotActive();
    return serializer;
  }

  /**
   * Sets serializer for the database which will be used for entity serialization.
   *
   * @param serializer the serializer to set.
   */
  @Override
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

  @Override
  public void checkSecurity(final int operation, final Identifiable record, String collection) {
    assert assertIfNotActive();
    if (collection == null) {
      collection = getCollectionNameById(record.getIdentity().getCollectionId());
    }
    checkSecurity(Rule.ResourceGeneric.COLLECTION, operation, collection);

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
    activeSession.set(true);
  }

  @Override
  public boolean isActiveOnCurrentThread() {
    var isActive = activeSession.get();
    return isActive != null && isActive;
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
      final SchemaClass recordClass, final String iCollectionName, final RecordId rid) {
    assert assertIfNotActive();

    final var collectionIdClass =
        metadata.getImmutableSchemaSnapshot().getClassByCollectionId(rid.getCollectionId());
    if (recordClass == null && collectionIdClass != null
        || collectionIdClass == null && recordClass != null
        || (recordClass != null && !recordClass.equals(collectionIdClass))) {
      throw new IllegalArgumentException(
          "Record saved into collection '"
              + iCollectionName
              + "' should be saved with class '"
              + collectionIdClass
              + "' but has been created with class '"
              + recordClass
              + "'");
    }
  }

  @Override
  public boolean assertIfNotActive() {
    var currentDatabase = activeSession.get();

    if (currentDatabase == null || !currentDatabase) {
      throw new SessionNotActivatedException(getDatabaseName());
    }

    return true;
  }

  @Override
  public int[] getBlobCollectionIds() {
    assert assertIfNotActive();
    return getMetadata().getSchema().getBlobCollections().toIntArray();
  }


  @Override
  public SharedContext<IM> getSharedContext() {
    assert assertIfNotActive();
    return sharedContext;
  }


  @Override
  public EdgeInternal newLightweightEdgeInternal(String iClassName, Vertex from, Vertex to) {
    assert assertIfNotActive();
    var clazz =
        (SchemaImmutableClass) getMetadata().getImmutableSchemaSnapshot().getClass(iClassName);

    return new EdgeImpl(this, from, to, clazz);
  }


  public synchronized void queryStarted(String id, QueryDatabaseState<ResultSet> state) {
    assert assertIfNotActive();

    if (this.activeQueries.size() > 1 && this.activeQueries.size() % 10 == 0) {
      var msg =
          "This database instance has "
              + activeQueries.size()
              + " open command/query result sets, please make sure you close them with"
              + " ResultSet.close()";
      LogManager.instance().warn(this, msg);
      if (logger.isDebugEnabled()) {
        activeQueries.values().stream()
            .map(pendingQuery -> pendingQuery.getResultSet().getExecutionPlan())
            .forEach(plan -> LogManager.instance().debug(this, plan.toString(), logger));
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

  @Override
  public synchronized void closeActiveQueries() {
    while (!activeQueries.isEmpty()) {
      this.activeQueries
          .values()
          .iterator()
          .next()
          .close(); // the query automatically unregisters itself
    }
  }

  @Override
  public Map<String, QueryDatabaseState<?>> getActiveQueries() {
    assert assertIfNotActive();

    //noinspection rawtypes
    return (Map) activeQueries;
  }

  @Override
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
  public boolean isCollectionEdge(int collection) {
    assert assertIfNotActive();
    var clazz = getMetadata().getImmutableSchemaSnapshot().getClassByCollectionId(collection);
    return clazz != null && clazz.isEdgeType();
  }

  @Override
  public boolean isCollectionVertex(int collection) {
    assert assertIfNotActive();
    var clazz = getMetadata().getImmutableSchemaSnapshot().getClassByCollectionId(collection);
    return clazz != null && clazz.isVertexType();
  }


  @Override
  public <X extends Exception> void executeInTxInternal(
      @Nonnull TxConsumer<FrontendTransaction, X> code) throws X {
    var ok = false;
    assert assertIfNotActive();
    begin();
    try {
      code.accept(currentTx);
      ok = true;
    } finally {
      finishTx(ok);
    }
  }

  @Override
  public <T, X extends Exception> void executeInTxBatches(
      Iterable<T> iterable, int batchSize, TxBiConsumer<Transaction, T, X> consumer) throws X {
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
  public <T, X extends Exception> void forEachInTx(Iterator<T> iterator,
      TxBiConsumer<Transaction, T, X> consumer) throws X {
    assert assertIfNotActive();
    forEachInTx(iterator, (db, t) -> {
      consumer.accept(db, t);
      return true;
    });
  }


  @Override
  public <T, X extends Exception> void forEachInTx(Iterable<T> iterable,
      TxBiConsumer<Transaction, T, X> consumer) throws X {
    assert assertIfNotActive();

    forEachInTx(iterable.iterator(), consumer);
  }


  @Override
  public <T, X extends Exception> void forEachInTx(Stream<T> stream,
      TxBiConsumer<Transaction, T, X> consumer) throws X {
    assert assertIfNotActive();

    try (var s = stream) {
      forEachInTx(s.iterator(), consumer);
    }
  }


  @Override
  public <T, X extends Exception> void forEachInTx(Iterator<T> iterator,
      TxBiFunction<Transaction, T, Boolean, X> consumer) throws X {
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
  public <T, X extends Exception> void forEachInTx(Iterable<T> iterable,
      TxBiFunction<Transaction, T, Boolean, X> consumer) throws X {
    assert assertIfNotActive();

    forEachInTx(iterable.iterator(), consumer);
  }

  @Override
  public <T, X extends Exception> void forEachInTx(Stream<T> stream,
      TxBiFunction<Transaction, T, Boolean, X> consumer) throws X {
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
  public <T, X extends Exception> void executeInTxBatchesInternal(
      @Nonnull Iterator<T> iterator, int batchSize,
      TxBiConsumer<FrontendTransaction, T, X> consumer) throws X {
    var ok = false;
    assert assertIfNotActive();
    var counter = 0;

    begin();
    try {
      while (iterator.hasNext()) {
        consumer.accept(currentTx, iterator.next());
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
  public <T, X extends Exception> void executeInTxBatchesInternal(
      Iterator<T> iterator, TxBiConsumer<FrontendTransaction, T, X> consumer) throws X {
    assert assertIfNotActive();

    executeInTxBatchesInternal(
        iterator,
        getConfiguration().getValueAsInteger(GlobalConfiguration.TX_BATCH_SIZE),
        consumer);
  }

  @Override
  public <T, X extends Exception> void executeInTxBatches(
      Iterable<T> iterable, TxBiConsumer<Transaction, T, X> consumer) throws X {
    assert assertIfNotActive();
    executeInTxBatches(
        iterable,
        getConfiguration().getValueAsInteger(GlobalConfiguration.TX_BATCH_SIZE),
        consumer);
  }

  @Override
  public <T, X extends Exception> void executeInTxBatches(
      Stream<T> stream, int batchSize, TxBiConsumer<Transaction, T, X> consumer) throws X {
    assert assertIfNotActive();

    try (stream) {
      executeInTxBatches(stream.iterator(), batchSize, consumer);
    }
  }


  @Override
  public <T, X extends Exception> void executeInTxBatchesInternal(Stream<T> stream,
      TxBiConsumer<FrontendTransaction, T, X> consumer) throws X {
    assert assertIfNotActive();

    try (stream) {
      executeInTxBatchesInternal(stream.iterator(), consumer);
    }
  }


  @Override
  public <R, X extends Exception> R computeInTxInternal(TxFunction<FrontendTransaction, R, X> code)
      throws X {
    assert assertIfNotActive();
    var ok = false;
    begin();
    try {
      var result = code.apply(currentTx);
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
  public LinkSet newLinkSet(Collection<? extends Identifiable> source) {
    var linkSet = new EntityLinkSetImpl(this);
    linkSet.addAll(source);
    return linkSet;
  }

  @Override
  public <V> EmbeddedMap<V> newEmbeddedMap() {
    return new EntityEmbeddedMapImpl<>();
  }

  @Override
  public <V> EmbeddedMap<V> newEmbeddedMap(int size) {
    return new EntityEmbeddedMapImpl<>(size);
  }

  @Override
  public <V> EmbeddedMap<V> newEmbeddedMap(Map<String, V> map) {
    return (EmbeddedMap<V>) PropertyTypeInternal.EMBEDDEDMAP.copy(map, this);
  }

  @Override
  public LinkMap newLinkMap() {
    return new EntityLinkMapIml(this);
  }

  @Override
  public LinkMap newLinkMap(int size) {
    return new EntityLinkMapIml(size, this);
  }

  @Override
  public LinkMap newLinkMap(Map<String, ? extends Identifiable> source) {
    var linkMap = new EntityLinkMapIml(source.size(), this);
    linkMap.putAll(source);
    return linkMap;
  }

  @Override
  public @Nonnull FrontendTransaction getActiveTransaction() {
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

  private void ensureEdgeConsistencyOnDeletion(@Nonnull EntityImpl entity) {
    if (entity.isVertex()) {
      VertexEntityImpl.deleteLinks(entity.asVertex());
    } else if (entity.isEdge()) {
      StatefullEdgeEntityImpl.deleteLinks(this, entity.asEdge());
    }
  }
}
