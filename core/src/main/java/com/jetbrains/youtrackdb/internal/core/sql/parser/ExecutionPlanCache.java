package com.jetbrains.youtrackdb.internal.core.sql.parser;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.config.StorageConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.MetadataUpdateListener;
import com.jetbrains.youtrackdb.internal.core.index.IndexManagerAbstract;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaShared;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * This class is an LRU cache for already prepared SQL execution plans. It stores itself in the
 * storage as a resource. It also acts an an entry point for the SQL executor.
 */
public class ExecutionPlanCache implements MetadataUpdateListener {

  Map<String, InternalExecutionPlan> map;
  int mapSize;

  protected long lastInvalidation = -1;
  protected long lastGlobalTimeout = GlobalConfiguration.COMMAND_TIMEOUT.getValueAsLong();

  /**
   * @param size the size of the cache
   */
  public ExecutionPlanCache(int size) {
    this.mapSize = size;
    map =
        new LinkedHashMap<>(size) {
          protected boolean removeEldestEntry(
              final Map.Entry<String, InternalExecutionPlan> eldest) {
            return super.size() > mapSize;
          }
        };
  }

  public static long getLastInvalidation(DatabaseSessionInternal db) {
    if (db == null) {
      throw new IllegalArgumentException("DB cannot be null");
    }

    var resource = db.getSharedContext().getExecutionPlanCache();
    synchronized (resource) {
      return resource.lastInvalidation;
    }
  }

  /**
   * @param statement an SQL statement
   * @return true if the corresponding executor is present in the cache
   */
  public boolean contains(String statement) {
    if (GlobalConfiguration.STATEMENT_CACHE_SIZE.getValueAsInteger() == 0) {
      return false;
    }
    synchronized (map) {
      return map.containsKey(statement);
    }
  }

  /**
   * returns an already prepared SQL execution plan, taking it from the cache if it exists or
   * creating a new one if it doesn't
   *
   * @param statement the SQL statement
   * @param ctx
   * @param db        the current DB instance
   * @return a statement executor from the cache
   */
  @Nullable
  public static ExecutionPlan get(
      String statement, CommandContext ctx, DatabaseSessionInternal db) {
    if (db == null) {
      throw new IllegalArgumentException("DB cannot be null");
    }
    if (statement == null) {
      return null;
    }

    var resource = db.getSharedContext().getExecutionPlanCache();
    var result = resource.getInternal(statement, ctx, db);
    return result;
  }

  public static void put(String statement, ExecutionPlan plan, DatabaseSessionEmbedded db) {
    if (db == null) {
      throw new IllegalArgumentException("DB cannot be null");
    }
    if (statement == null) {
      return;
    }

    var resource = db.getSharedContext().getExecutionPlanCache();
    resource.putInternal(statement, plan, db);
  }

  public void putInternal(String statement, ExecutionPlan plan, DatabaseSessionEmbedded db) {
    if (statement == null) {
      return;
    }

    if (GlobalConfiguration.STATEMENT_CACHE_SIZE.getValueAsInteger() == 0) {
      return;
    }

    synchronized (map) {
      var internal = (InternalExecutionPlan) plan;
      var ctx = new BasicCommandContext();
      ctx.setDatabaseSession(db);
      internal = internal.copy(ctx);
      // this copy is never used, so it has to be closed to free resources
      internal.close();
      map.put(statement, internal);
    }
  }

  /**
   * @param statement an SQL statement
   * @param ctx
   * @return the corresponding executor, taking it from the internal cache, if it exists
   */
  @Nullable
  public ExecutionPlan getInternal(
      String statement, CommandContext ctx, DatabaseSessionInternal db) {
    InternalExecutionPlan result;

    var currentGlobalTimeout =
        db.getConfiguration().getValueAsLong(GlobalConfiguration.COMMAND_TIMEOUT);
    if (currentGlobalTimeout != this.lastGlobalTimeout) {
      invalidate();
    }
    this.lastGlobalTimeout = currentGlobalTimeout;

    if (statement == null) {
      return null;
    }
    if (GlobalConfiguration.STATEMENT_CACHE_SIZE.getValueAsInteger() == 0) {
      return null;
    }
    synchronized (map) {
      // LRU
      result = map.remove(statement);
      if (result != null) {
        map.put(statement, result);
        result = result.copy(ctx);
      }
    }

    return result;
  }

  public void invalidate() {
    if (GlobalConfiguration.STATEMENT_CACHE_SIZE.getValueAsInteger() == 0) {
      lastInvalidation = System.currentTimeMillis();
      return;
    }

    synchronized (this) {
      synchronized (map) {
        map.clear();
      }
      lastInvalidation = System.currentTimeMillis();
    }
  }

  @Override
  public void onSchemaUpdate(DatabaseSessionInternal session, String databaseName,
      SchemaShared schema) {
    invalidate();
  }

  @Override
  public void onIndexManagerUpdate(DatabaseSessionInternal session, String databaseName,
      IndexManagerAbstract indexManager) {
    invalidate();
  }

  @Override
  public void onFunctionLibraryUpdate(DatabaseSessionInternal session, String databaseName) {
    invalidate();
  }

  @Override
  public void onSequenceLibraryUpdate(DatabaseSessionInternal session, String databaseName) {
    invalidate();
  }

  @Override
  public void onStorageConfigurationUpdate(String databaseName, StorageConfiguration update) {
    invalidate();
  }

  public static ExecutionPlanCache instance(DatabaseSessionInternal db) {
    if (db == null) {
      throw new IllegalArgumentException("DB cannot be null");
    }

    var resource = db.getSharedContext().getExecutionPlanCache();
    return resource;
  }
}
