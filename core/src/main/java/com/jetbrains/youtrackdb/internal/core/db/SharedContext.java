package com.jetbrains.youtrackdb.internal.core.db;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.exception.BaseException;
import com.jetbrains.youtrackdb.api.exception.DatabaseException;
import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.internal.common.listener.ListenerManger;
import com.jetbrains.youtrackdb.internal.core.index.IndexException;
import com.jetbrains.youtrackdb.internal.core.index.IndexManagerEmbedded;
import com.jetbrains.youtrackdb.internal.core.index.Indexes;
import com.jetbrains.youtrackdb.internal.core.metadata.function.FunctionLibraryImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaManager;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaSnapshot;
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.SequenceLibraryImpl;
import com.jetbrains.youtrackdb.internal.core.query.live.LiveQueryHook;
import com.jetbrains.youtrackdb.internal.core.query.live.LiveQueryHookV2.LiveQueryOps;
import com.jetbrains.youtrackdb.internal.core.schedule.SchedulerImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.QueryStats;
import com.jetbrains.youtrackdb.internal.core.sql.parser.ExecutionPlanCache;
import com.jetbrains.youtrackdb.internal.core.sql.parser.StatementCache;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;

public final class SharedContext extends ListenerManger<MetadataUpdateListener> {

  YouTrackDBInternalEmbedded youtrackDB;
  private AbstractStorage storage;
  private SchemaManager schema;
  private SecurityInternal security;

  private FunctionLibraryImpl functionLibrary;
  private SchedulerImpl scheduler;
  private SequenceLibraryImpl sequenceLibrary;
  private LiveQueryHook.LiveQueryOps liveQueryOps;
  private LiveQueryOps liveQueryOpsV2;
  private StatementCache statementCache;
  private ExecutionPlanCache executionPlanCache;
  private QueryStats queryStats;
  private volatile boolean loaded = false;
  private Map<String, Object> resources;
  private StringCache stringCache;
  private IndexManagerEmbedded indexManager;

  private final ReentrantLock lock = new ReentrantLock();

  private volatile SchemaSnapshot snapshot;
  private final ReentrantLock snapshotLock = new ReentrantLock();

  public SharedContext(AbstractStorage storage, YouTrackDBInternalEmbedded youtrackDB) {
    super(true);

    this.youtrackDB = youtrackDB;
    this.storage = storage;

    init(storage);
  }

  private void init(Storage storage) {
    stringCache =
        new StringCache(
            storage
                .getConfiguration()
                .getContextConfiguration()
                .getValueAsInteger(GlobalConfiguration.DB_STRING_CAHCE_SIZE));
    schema = new SchemaManager();
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
        indexManager.load(database);
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
      indexManager.reload(database);
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
      indexManager.create(session);
      security.create(session);
      FunctionLibraryImpl.create(session);
      SequenceLibraryImpl.create(session);
      SchedulerImpl.create(session);

      // CREATE BASE VERTEX AND EDGE CLASSES
      schema.createClass(session, Entity.DEFAULT_CLASS_NAME);
      schema.createClass(session, "V");
      schema.createClass(session, "E");

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
      database.getMetadata().init(this);
      this.load(database);
    } finally {
      lock.unlock();
    }
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

  public AbstractStorage getStorage() {
    return storage;
  }

  public YouTrackDBInternalEmbedded getYouTrackDB() {
    return youtrackDB;
  }

  public void setStorage(AbstractStorage storage) {
    this.storage = storage;
  }

  public IndexManagerEmbedded getIndexManager() {
    return indexManager;
  }

  public SchemaManager getSchema() {
    return schema;
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

  public SchemaSnapshot makeSnapshot(DatabaseSessionEmbedded session) {
    var snapshot = this.snapshot;
    if (snapshot == null) {
      snapshotLock.lock();
      try {
        if (this.snapshot == null) {
          this.snapshot = new SchemaSnapshot(this, session);
        }

        return this.snapshot;
      } finally {
        snapshotLock.unlock();
      }
    }

    return snapshot;
  }

  public void forceSnapshot() {
    if (snapshot == null) {
      return;
    }

    snapshotLock.lock();
    try {
      snapshot = null;
    } finally {
      snapshotLock.unlock();
    }
  }

}
