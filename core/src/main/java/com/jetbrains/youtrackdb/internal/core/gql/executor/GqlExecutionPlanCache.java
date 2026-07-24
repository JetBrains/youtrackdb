package com.jetbrains.youtrackdb.internal.core.gql.executor;

import com.jetbrains.youtrackdb.internal.core.db.AbstractMetadataUpdateCache;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * LRU cache for already prepared GQL execution plans using Guava Cache.
 * Stores itself in SharedContext as a resource and acts as an entry point for the GQL executor.
 */
public class GqlExecutionPlanCache extends AbstractMetadataUpdateCache<String, GqlExecutionPlan> {

  /**
   * @param size maximum number of plans to cache; 0 means cache disabled (no storage)
   */
  public GqlExecutionPlanCache(int size) {
    super(size);
  }

  public static long getLastInvalidation(@Nonnull DatabaseSessionEmbedded db) {
    return instance(db).getLastInvalidation();
  }

  /**
   * Returns true if the corresponding execution plan is present in the cache.
   *
   * @param statement a GQL statement
   * @return true if the corresponding execution plan is present in the cache
   */
  @SuppressWarnings("unused")
  public boolean contains(@Nonnull String statement) {
    return containsKey(statement);
  }

  /**
   * Returns an already prepared GQL execution plan from cache, or null if not found.
   *
   * @param statement the GQL query string
   * @param ctx       the execution context
   * @param db        the current DB instance
   * @return an execution plan from the cache, or null if not cached
   */
  @Nullable public static GqlExecutionPlan get(
      @Nonnull String statement, @Nonnull GqlExecutionContext ctx,
      @Nonnull DatabaseSessionEmbedded db) {
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
  public static void put(@Nonnull String statement, @Nonnull GqlExecutionPlan plan,
      @Nonnull DatabaseSessionEmbedded db) {
    var resource = db.getSharedContext().getGqlExecutionPlanCache();
    resource.putInternal(statement, plan);
  }

  /**
   * Internal method to store a plan in cache.
   */
  public void putInternal(@Nonnull String statement, @Nonnull GqlExecutionPlan plan) {
    if (!cacheEnabled()) {
      return;
    }
    putCached(statement, plan.copy());
  }

  /**
   * Returns the cached execution plan for the given GQL statement, or null if not found.
   *
   * @param statement a GQL query string
   * @param ctx       execution context
   * @return the corresponding execution plan from cache, or null if not found
   */
  @SuppressWarnings("unused")
  @Nullable public GqlExecutionPlan getInternal(@Nonnull String statement, @Nonnull GqlExecutionContext ctx) {
    if (!cacheEnabled()) {
      return null;
    }
    var cached = getCached(statement);
    return cached != null ? cached.copy() : null;
  }

  public static @Nonnull GqlExecutionPlanCache instance(@Nonnull DatabaseSessionEmbedded db) {
    return db.getSharedContext().getGqlExecutionPlanCache();
  }
}
