package com.jetbrains.youtrack.db.internal.core.db;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.index.IndexException;
import com.jetbrains.youtrack.db.internal.core.index.IndexFactory;
import com.jetbrains.youtrack.db.internal.core.index.IndexManagerShared;
import com.jetbrains.youtrack.db.internal.core.index.Indexes;
import com.jetbrains.youtrack.db.internal.core.metadata.MetadataDefault;
import com.jetbrains.youtrack.db.internal.core.metadata.function.FunctionLibraryImpl;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaEmbedded;
import com.jetbrains.youtrack.db.internal.core.metadata.sequence.SequenceLibraryImpl;
import com.jetbrains.youtrack.db.internal.core.query.live.LiveQueryHook;
import com.jetbrains.youtrack.db.internal.core.query.live.LiveQueryHookV2.LiveQueryOps;
import com.jetbrains.youtrack.db.internal.core.schedule.SchedulerImpl;
import com.jetbrains.youtrack.db.internal.core.sql.executor.QueryStats;
import com.jetbrains.youtrack.db.internal.core.sql.parser.ExecutionPlanCache;
import com.jetbrains.youtrack.db.internal.core.sql.parser.StatementCache;
import com.jetbrains.youtrack.db.internal.core.storage.Storage;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractPaginatedStorage;
import java.util.concurrent.locks.ReentrantLock;

/**
 *
 */
public class SharedContextEmbedded extends SharedContext {

  private final ReentrantLock lock = new ReentrantLock();

  public SharedContextEmbedded(Storage storage, YouTrackDBEmbedded youtrackDB) {
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
    indexManager = new IndexManagerShared(storage);
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
    ((AbstractPaginatedStorage) storage)
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
      database.executeInTx(() -> {
        schema.load(database);
        schema.forceSnapshot(database);
        indexManager.load(database);
        // The Immutable snapshot should be after index and schema that require and before
        // everything else that use it
        schema.forceSnapshot(database);
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

  @Override
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
      schema.forceSnapshot(database);
      security.load(database);
      functionLibrary.load(database);
      sequenceLibrary.load(database);
      scheduler.load(database);
    } finally {
      lock.unlock();
    }
  }

  public void create(DatabaseSessionInternal session) {
    lock.lock();
    try {
      schema.create(session);
      indexManager.create(session);
      security.create(session);
      FunctionLibraryImpl.create(session);
      SequenceLibraryImpl.create(session);
      security.createClassTrigger(session);
      SchedulerImpl.create(session);
      schema.forceSnapshot(session);

      // CREATE BASE VERTEX AND EDGE CLASSES
      schema.createClass(session, Entity.DEFAULT_CLASS_NAME);
      schema.createClass(session, "V");
      schema.createClass(session, "E");

      var config = storage.getConfiguration();
      var blobClustersCount = config.getContextConfiguration()
          .getValueAsInteger(GlobalConfiguration.STORAGE_BLOB_CLUSTERS_COUNT);

      for (var i = 0; i < blobClustersCount; i++) {
        var blobClusterId = session.addCluster("$blob" + i);
        schema.addBlobCluster(session, blobClusterId);
      }

      // create geospatial classes
      try {
        IndexFactory factory = Indexes.getFactory(SchemaClass.INDEX_TYPE.SPATIAL.toString(),
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

  public void reInit(AbstractPaginatedStorage storage2, DatabaseSessionInternal database) {
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

}
