package com.jetbrains.youtrackdb.internal.core.gql.executor;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.config.StorageConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.MetadataUpdateListener;
import com.jetbrains.youtrackdb.internal.core.index.IndexManagerAbstract;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaShared;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * LRU cache for already prepared GQL execution plans.
 * Analogous to SQL's ExecutionPlanCache.
 *
 * Stores itself in SharedContext as a resource and acts as an entry point for the GQL executor.
 */
public class GqlExecutionPlanCache implements MetadataUpdateListener {

  Map<String, GqlExecutionPlan> map;
  int mapSize;

  protected long lastInvalidation = -1;

  /**
   * @param size the size of the cache
   */
  public GqlExecutionPlanCache(int size) {
    this.mapSize = size;
    map =
        new LinkedHashMap<>(size) {
          @Override
          protected boolean removeEldestEntry(
              final Map.Entry<String, GqlExecutionPlan> eldest) {
            return super.size() > mapSize;
          }
        };
  }

  public static long getLastInvalidation(DatabaseSessionInternal db) {
    if (db == null) {
      throw new IllegalArgumentException("DB cannot be null");
    }

    var resource = db.getSharedContext().getGqlExecutionPlanCache();
    synchronized (resource) {
      return resource.lastInvalidation;
    }
  }

  /**
   * @param statement a GQL statement
   * @return true if the corresponding execution plan is present in the cache
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
   * Returns an already prepared GQL execution plan from cache, or null if not found.
   *
   * @param statement the GQL query string
   * @param ctx       the execution context
   * @param db        the current DB instance
   * @return an execution plan from the cache, or null if not cached
   */
  @Nullable
  public static GqlExecutionPlan get(
      String statement, GqlExecutionContext ctx, DatabaseSessionInternal db) {
    if (db == null) {
      throw new IllegalArgumentException("DB cannot be null");
    }
    if (statement == null) {
      return null;
    }

    var resource = db.getSharedContext().getGqlExecutionPlanCache();
    return resource.getInternal(statement, ctx);
  }

  /**
   * Stores a GQL execution plan in the cache.
   *
   * @param statement the GQL query string
   * @param plan      the execution plan to cache
   * @param db        the current DB instance
   */
  public static void put(String statement, GqlExecutionPlan plan, DatabaseSessionInternal db) {
    if (db == null) {
      throw new IllegalArgumentException("DB cannot be null");
    }
    if (statement == null) {
      return;
    }

    var resource = db.getSharedContext().getGqlExecutionPlanCache();
    resource.putInternal(statement, plan, db);
  }

  /**
   * Internal method to store a plan in cache.
   */
  public void putInternal(String statement, GqlExecutionPlan plan, DatabaseSessionInternal db) {
    if (statement == null) {
      return;
    }

    if (GlobalConfiguration.STATEMENT_CACHE_SIZE.getValueAsInteger() == 0) {
      return;
    }

    synchronized (map) {
      // Create a copy for caching (to avoid shared state issues)
      var cachedPlan = plan.copy();
      map.put(statement, cachedPlan);
    }
  }

  /**
   * @param statement a GQL query string
   * @param ctx       execution context
   * @return the corresponding execution plan from cache, or null if not found
   */
  @Nullable
  public GqlExecutionPlan getInternal(String statement, GqlExecutionContext ctx) {
    if (statement == null) {
      return null;
    }
    if (GlobalConfiguration.STATEMENT_CACHE_SIZE.getValueAsInteger() == 0) {
      return null;
    }

    GqlExecutionPlan result;
    synchronized (map) {
      // LRU: remove and re-insert to update access order
      result = map.remove(statement);
      if (result != null) {
        map.put(statement, result);
        // Return a copy to avoid concurrent modification issues
        result = result.copy();
      }
    }

    return result;
  }

  /**
   * Invalidates (clears) the entire cache.
   */
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

  public static GqlExecutionPlanCache instance(DatabaseSessionInternal db) {
    if (db == null) {
      throw new IllegalArgumentException("DB cannot be null");
    }

    var resource = db.getSharedContext().getGqlExecutionPlanCache();
    return resource;
  }
}
