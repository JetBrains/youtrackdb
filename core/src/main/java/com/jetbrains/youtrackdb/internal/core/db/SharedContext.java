package com.jetbrains.youtrackdb.internal.core.db;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.listener.ListenerManger;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.index.IndexException;
import com.jetbrains.youtrackdb.internal.core.index.IndexManagerEmbedded;
import com.jetbrains.youtrackdb.internal.core.index.Indexes;
import com.jetbrains.youtrackdb.internal.core.metadata.MetadataDefault;
import com.jetbrains.youtrackdb.internal.core.metadata.function.FunctionLibraryImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaEmbedded;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaShared;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.SequenceLibraryImpl;
import com.jetbrains.youtrackdb.internal.core.query.live.LiveQueryHook;
import com.jetbrains.youtrackdb.internal.core.query.live.LiveQueryHookV2.LiveQueryOps;
import com.jetbrains.youtrackdb.internal.core.schedule.SchedulerImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.QueryStats;
import com.jetbrains.youtrackdb.internal.core.sql.parser.ExecutionPlanCache;
import com.jetbrains.youtrackdb.internal.core.sql.parser.StatementCache;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import com.jetbrains.youtrackdb.internal.core.storage.StorageInfo;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;

public class SharedContext extends ListenerManger<MetadataUpdateListener> {
  protected YouTrackDBInternalEmbedded youtrackDB;
  protected Storage storage;
  protected SchemaShared schema;
  protected SecurityInternal security;

  protected FunctionLibraryImpl functionLibrary;
  protected SchedulerImpl scheduler;
  protected SequenceLibraryImpl sequenceLibrary;
  protected LiveQueryHook.LiveQueryOps liveQueryOps;
  protected LiveQueryOps liveQueryOpsV2;
  protected StatementCache statementCache;
  protected ExecutionPlanCache executionPlanCache;
  protected QueryStats queryStats;
  protected volatile boolean loaded = false;
  protected Map<String, Object> resources;
  protected StringCache stringCache;
  protected IndexManagerEmbedded indexManager;

  private final ReentrantLock lock = new ReentrantLock();

  public SharedContext(Storage storage, YouTrackDBInternalEmbedded youtrackDB) {
    super(true);

    this.youtrackDB = youtrackDB;
    this.storage = storage;

    init(storage);
  }

  protected void init(Storage storage) {
    stringCache =
        new StringCache(
            storage
                .getConfiguration()
                .getContextConfiguration()
                .getValueAsInteger(GlobalConfiguration.DB_STRING_CAHCE_SIZE));
    schema = new SchemaEmbedded();
    security = youtrackDB.getSecuritySystem().newSecurity(storage.getName());
    indexManager = new IndexManagerEmbedded(storage);
    functionLibrary = new FunctionLibraryImpl();
    scheduler = new SchedulerImpl(youtrackDB);
    sequenceLibrary = new SequenceLibraryImpl();
    liveQueryOps = new LiveQueryHook.LiveQueryOps();
    liveQueryOpsV2 = new LiveQueryOps();
    statementCache =
        new StatementCache(
            storage
                .getConfiguration()
                .getContextConfiguration()
                .getValueAsInteger(GlobalConfiguration.STATEMENT_CACHE_SIZE));

    executionPlanCache =
        new ExecutionPlanCache(
            storage
                .getConfiguration()
                .getContextConfiguration()
                .getValueAsInteger(GlobalConfiguration.STATEMENT_CACHE_SIZE));
    this.registerListener(executionPlanCache);

    queryStats = new QueryStats();
    ((AbstractStorage) storage)
        .setStorageConfigurationUpdateListener(
            update -> {
              for (var listener : browseListeners()) {
                listener.onStorageConfigurationUpdate(storage.getName(), update);
              }
            });
  }

  public void load(DatabaseSessionInternal database) {
    if (loaded) {
      return;
    }

    lock.lock();
    try {
      database.executeInTx(transaction -> {
        schema.load(database);
        schema.forceSnapshot();
        indexManager.load(database);
        // The Immutable snapshot should be after index and schema that require and before
        // everything else that use it
        schema.forceSnapshot();
        security.load(database);
        functionLibrary.load(database);
        scheduler.load(database);
        sequenceLibrary.load(database);
        loaded = true;
      });
    } finally {
      lock.unlock();
    }
  }


  public void close() {
    lock.lock();
    try {
      stringCache.close();
      schema.close();
      security.close();
      indexManager.close();
      functionLibrary.close();
      scheduler.close();
      sequenceLibrary.close();
      statementCache.clear();
      executionPlanCache.invalidate();
      liveQueryOps.close();
      liveQueryOpsV2.close();
      loaded = false;
    } finally {
      lock.unlock();
    }
  }


  public void reload(DatabaseSessionInternal database) {
    lock.lock();
    try {
      schema.reload(database);
      indexManager.reload(database);
      // The Immutable snapshot should be after index and schema that require and before everything
      // else that use it
      schema.forceSnapshot();
      security.load(database);
      functionLibrary.load(database);
      sequenceLibrary.load(database);
      scheduler.load(database);
    } finally {
      lock.unlock();
    }
  }

  public void create(DatabaseSessionEmbedded session) {
    lock.lock();
    try {
      schema.create(session);
      indexManager.create(session);
      security.create(session);
      FunctionLibraryImpl.create(session);
      SequenceLibraryImpl.create(session);
      SchedulerImpl.create(session);
      schema.forceSnapshot();

      // CREATE BASE VERTEX AND EDGE CLASSES
      schema.createClass(session, Entity.DEFAULT_CLASS_NAME);
      schema.createClass(session, "V");
      schema.createClass(session, "E");

      var config = storage.getConfiguration();
      var blobCollectionsCount = config.getContextConfiguration()
          .getValueAsInteger(GlobalConfiguration.STORAGE_BLOB_COLLECTIONS_COUNT);

      for (var i = 0; i < blobCollectionsCount; i++) {
        var blobCollectionId = session.addCollection("$blob" + i);
        schema.addBlobCollection(session, blobCollectionId);
      }

      // create geospatial classes
      try {
        var factory = Indexes.getFactory(SchemaClass.INDEX_TYPE.SPATIAL.toString(),
            "LUCENE");
        if (factory instanceof DatabaseLifecycleListener) {
          ((DatabaseLifecycleListener) factory).onCreate(session);
        }
      } catch (IndexException x) {
        // the index does not exist
      }

      loaded = true;
    } finally {
      lock.unlock();
    }
  }

  public void reInit(AbstractStorage storage2, DatabaseSessionInternal database) {
    lock.lock();
    try {
      this.close();
      this.storage = storage2;
      this.init(storage2);
      ((MetadataDefault) database.getMetadata()).init(this);
      this.load(database);
    } finally {
      lock.unlock();
    }
  }


  public SchemaShared getSchema() {
    return schema;
  }

  public SecurityInternal getSecurity() {
    return security;
  }

  public FunctionLibraryImpl getFunctionLibrary() {
    return functionLibrary;
  }

  public SchedulerImpl getScheduler() {
    return scheduler;
  }

  public SequenceLibraryImpl getSequenceLibrary() {
    return sequenceLibrary;
  }

  public LiveQueryHook.LiveQueryOps getLiveQueryOps() {
    return liveQueryOps;
  }

  public LiveQueryOps getLiveQueryOpsV2() {
    return liveQueryOpsV2;
  }

  public StatementCache getStatementCache() {
    return statementCache;
  }

  public ExecutionPlanCache getExecutionPlanCache() {
    return executionPlanCache;
  }

  public QueryStats getQueryStats() {
    return queryStats;
  }

  public StorageInfo getStorage() {
    return storage;
  }

  public YouTrackDBInternalEmbedded getYouTrackDB() {
    return youtrackDB;
  }

  public void setStorage(Storage storage) {
    this.storage = storage;
  }

  public IndexManagerEmbedded getIndexManager() {
    return indexManager;
  }

  public synchronized <T> T getResource(final String name, final Callable<T> factory) {
    if (resources == null) {
      resources = new HashMap<>();
    }
    @SuppressWarnings("unchecked")
    var resource = (T) resources.get(name);
    if (resource == null) {
      try {
        resource = factory.call();
      } catch (Exception e) {
        throw BaseException.wrapException(
            new DatabaseException((String) null,
                String.format("instance creation for '%s' failed", name)),
            e, (String) null);
      }
      resources.put(name, resource);
    }
    return resource;
  }

  public StringCache getStringCache() {
    return this.stringCache;
  }

  public boolean isLoaded() {
    return loaded;
  }

}
