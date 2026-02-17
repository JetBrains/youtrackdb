package com.jetbrains.youtrackdb.internal.core.gql.executor;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.config.StorageConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.MetadataUpdateListener;
import com.jetbrains.youtrackdb.internal.core.index.IndexManagerAbstract;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaShared;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;

/**
 * LRU cache for already prepared GQL execution plans using Guava Cache.
 * Stores itself in SharedContext as a resource and acts as an entry point for the GQL executor.
 */
public class GqlExecutionPlanCache implements MetadataUpdateListener {

  private final int capacity;
  private final Cache<String, GqlExecutionPlan> cache;
  private final AtomicLong lastInvalidation = new AtomicLong(-1);

  /**
   * @param size maximum number of plans to cache; 0 means cache disabled (no storage)
   */
  public GqlExecutionPlanCache(int size) {
    this.capacity = size;
    this.cache = size > 0
        ? CacheBuilder.newBuilder().maximumSize(size).build()
        : null;
  }

  public static long getLastInvalidation(DatabaseSessionEmbedded db) {
    return instance(db).lastInvalidation.get();
  }

  /**
   * @param statement a GQL statement
   * @return true if the corresponding execution plan is present in the cache
   */
  @SuppressWarnings("unused")
  public boolean contains(String statement) {
    if (capacity == 0 || GlobalConfiguration.STATEMENT_CACHE_SIZE.getValueAsInteger() == 0) {
      return false;
    }
    return cache.asMap().containsKey(statement);
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
      String statement, GqlExecutionContext ctx, DatabaseSessionEmbedded db) {
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
  public static void put(String statement, GqlExecutionPlan plan, DatabaseSessionEmbedded db) {
    if (db == null) {
      throw new IllegalArgumentException("DB cannot be null");
    }
    if (statement == null) {
      return;
    }

    var resource = db.getSharedContext().getGqlExecutionPlanCache();
    resource.putInternal(statement, plan);
  }

  /**
   * Internal method to store a plan in cache.
   */
  public void putInternal(String statement, GqlExecutionPlan plan) {
    if (statement == null || capacity == 0
        || GlobalConfiguration.STATEMENT_CACHE_SIZE.getValueAsInteger() == 0) {
      return;
    }
    cache.put(statement, plan.copy());
  }

  /**
   * @param statement a GQL query string
   * @param ctx       execution context
   * @return the corresponding execution plan from cache, or null if not found
   */
  @SuppressWarnings("unused")
  @Nullable
  public GqlExecutionPlan getInternal(String statement, GqlExecutionContext ctx) {
    if (statement == null || capacity == 0
        || GlobalConfiguration.STATEMENT_CACHE_SIZE.getValueAsInteger() == 0) {
      return null;
    }
    var cached = cache.getIfPresent(statement);
    return cached != null ? cached.copy() : null;
  }

  public void invalidate() {
    if (cache != null) {
      cache.invalidateAll();
    }
    lastInvalidation.set(System.nanoTime());
  }

  @Override
  public void onSchemaUpdate(DatabaseSessionEmbedded session, String databaseName,
      SchemaShared schema) {
    invalidate();
  }

  @Override
  public void onIndexManagerUpdate(DatabaseSessionEmbedded session, String databaseName,
      IndexManagerAbstract indexManager) {
    invalidate();
  }

  @Override
  public void onFunctionLibraryUpdate(DatabaseSessionEmbedded session, String databaseName) {
    invalidate();
  }

  @Override
  public void onSequenceLibraryUpdate(DatabaseSessionEmbedded session, String databaseName) {
    invalidate();
  }

  @Override
  public void onStorageConfigurationUpdate(String databaseName, StorageConfiguration update) {
    invalidate();
  }

  public static GqlExecutionPlanCache instance(DatabaseSessionEmbedded db) {
    if (db == null) {
      throw new IllegalArgumentException("DB cannot be null");
    }

    return db.getSharedContext().getGqlExecutionPlanCache();
  }
}
