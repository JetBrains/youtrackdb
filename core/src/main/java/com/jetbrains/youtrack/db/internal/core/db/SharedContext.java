package com.jetbrains.youtrack.db.internal.core.db;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.internal.common.listener.ListenerManger;
import com.jetbrains.youtrack.db.internal.core.index.IndexManagerAbstract;
import com.jetbrains.youtrack.db.internal.core.metadata.function.FunctionLibraryImpl;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaShared;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.sequence.SequenceLibraryImpl;
import com.jetbrains.youtrack.db.internal.core.query.live.LiveQueryHook;
import com.jetbrains.youtrack.db.internal.core.query.live.LiveQueryHookV2.LiveQueryOps;
import com.jetbrains.youtrack.db.internal.core.schedule.SchedulerImpl;
import com.jetbrains.youtrack.db.internal.core.sql.executor.QueryStats;
import com.jetbrains.youtrack.db.internal.core.sql.parser.ExecutionPlanCache;
import com.jetbrains.youtrack.db.internal.core.sql.parser.StatementCache;
import com.jetbrains.youtrack.db.internal.core.storage.Storage;
import com.jetbrains.youtrack.db.internal.core.storage.StorageInfo;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractPaginatedStorage;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 *
 */
public abstract class SharedContext extends ListenerManger<MetadataUpdateListener> {

  protected YouTrackDBInternal youtrackDB;
  protected StorageInfo storage;
  protected SchemaShared schema;
  protected SecurityInternal security;
  protected IndexManagerAbstract indexManager;
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

  public SharedContext() {
    super(true);
  }

  public SchemaShared getSchema() {
    return schema;
  }

  public SecurityInternal getSecurity() {
    return security;
  }

  public IndexManagerAbstract getIndexManager() {
    return indexManager;
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

  public abstract void load(DatabaseSessionInternal oDatabaseDocumentInternal);

  public abstract void reload(DatabaseSessionInternal database);

  public abstract void close();

  public StorageInfo getStorage() {
    return storage;
  }

  public YouTrackDBInternal getYouTrackDB() {
    return youtrackDB;
  }

  public void setStorage(Storage storage) {
    this.storage = storage;
  }

  public synchronized <T> T getResource(final String name, final Callable<T> factory) {
    if (resources == null) {
      resources = new HashMap<>();
    }
    @SuppressWarnings("unchecked")
    T resource = (T) resources.get(name);
    if (resource == null) {
      try {
        resource = factory.call();
      } catch (Exception e) {
        throw BaseException.wrapException(
            new DatabaseException(String.format("instance creation for '%s' failed", name)), e);
      }
      resources.put(name, resource);
    }
    return resource;
  }

  public synchronized void reInit(
      AbstractPaginatedStorage storage2, DatabaseSessionInternal database) {
    throw new UnsupportedOperationException();
  }

  public StringCache getStringCache() {
    return this.stringCache;
  }
}
