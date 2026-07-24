package com.jetbrains.youtrackdb.internal.core.sql.parser;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.AbstractMetadataUpdateCache;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * LRU cache for already prepared YQL/SQL execution plans using Guava Cache. Stores itself in
 * SharedContext as a resource and acts as an entry point for the SQL executor.
 */
public class YqlExecutionPlanCache extends AbstractMetadataUpdateCache<String, InternalExecutionPlan> {

  private volatile long lastGlobalTimeout =
      GlobalConfiguration.COMMAND_TIMEOUT.getValueAsLong();

  /**
   * @param size the size of the cache; 0 means cache disabled
   */
  public YqlExecutionPlanCache(int size) {
    super(size);
  }

  public static long getLastInvalidation(@Nonnull DatabaseSessionEmbedded db) {
    return instance(db).getLastInvalidation();
  }

  /**
   * @param statement an SQL statement
   * @return true if the corresponding executor is present in the cache
   */
  public boolean contains(String statement) {
    return containsKey(statement);
  }

  /**
   * Returns an already prepared SQL execution plan, taking it from the cache if it exists or
   * creating a new one if it doesn't.
   *
   * @param statement the SQL statement
   * @param ctx       the command context
   * @param db        the current DB instance
   * @return a statement executor from the cache
   */
  @Nullable
  public static ExecutionPlan get(
      String statement, CommandContext ctx, DatabaseSessionEmbedded db) {
    if (db == null) {
      throw new IllegalArgumentException("DB cannot be null");
    }
    if (statement == null) {
      return null;
    }

    var resource = db.getSharedContext().getYqlExecutionPlanCache();
    return resource.getInternal(statement, ctx, db);
  }

  public static void put(
      String statement, ExecutionPlan plan, DatabaseSessionEmbedded db) {
    if (db == null) {
      throw new IllegalArgumentException("DB cannot be null");
    }
    if (statement == null) {
      return;
    }

    var resource = db.getSharedContext().getYqlExecutionPlanCache();
    resource.putInternal(statement, plan, db);
  }

  public void putInternal(String statement, ExecutionPlan plan, DatabaseSessionEmbedded db) {
    if (statement == null || !cacheEnabled()) {
      return;
    }

    // Copy the plan outside the cache data structure — no lock contention on copy()
    var internal = (InternalExecutionPlan) plan;
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(db);
    internal = internal.copy(ctx);
    // this copy is never used, so it has to be closed to free resources
    internal.close();
    putCached(statement, internal);
  }

  /**
   * Returns the cached execution plan for the given SQL statement, or null if not found.
   *
   * @param statement an SQL statement
   * @param ctx       the command context
   * @param db        the database session
   * @return the corresponding execution plan from cache, or null if not found
   */
  @Nullable
  public ExecutionPlan getInternal(
      String statement, CommandContext ctx, DatabaseSessionEmbedded db) {
    var currentGlobalTimeout =
        db.getConfiguration().getValueAsLong(GlobalConfiguration.COMMAND_TIMEOUT);
    if (currentGlobalTimeout != this.lastGlobalTimeout) {
      invalidate();
      this.lastGlobalTimeout = currentGlobalTimeout;
    }

    if (statement == null || !cacheEnabled()) {
      return null;
    }

    // Guava Cache handles LRU eviction and concurrent access internally
    var result = getCached(statement);
    // Copy outside cache — no lock held during potentially expensive copy()
    return result != null ? result.copy(ctx) : null;
  }

  public static @Nonnull YqlExecutionPlanCache instance(@Nonnull DatabaseSessionEmbedded db) {
    return db.getSharedContext().getYqlExecutionPlanCache();
  }
}
