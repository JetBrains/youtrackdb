package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

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
 * LRU cache for compiled Gremlin-to-MATCH execution plans, keyed by the post-walk {@link
 * GremlinPlanFingerprint}. Stores a deep-copied plan per entry; each hit returns a fresh {@link
 * InternalExecutionPlan#copy(CommandContext)} for the caller's context. Schema changes invalidate
 * the cache through the same {@link MetadataUpdateListener} hook as {@link
 * com.jetbrains.youtrackdb.internal.core.sql.parser.YqlExecutionPlanCache}.
 */
public final class GremlinPlanCache
    extends AbstractMetadataUpdateCache<String, InternalExecutionPlan> {

  private volatile long lastGlobalTimeout =
      GlobalConfiguration.COMMAND_TIMEOUT.getValueAsLong();

  /**
   * @param size the size of the cache; 0 means cache disabled
   */
  public GremlinPlanCache(int size) {
    super(size);
  }

  public static long getLastInvalidation(@Nonnull DatabaseSessionEmbedded db) {
    return instance(db).getLastInvalidation();
  }

  /** Returns {@code true} when an entry exists for {@code fingerprint}. */
  public boolean contains(String fingerprint) {
    return containsKey(fingerprint);
  }

  @Nullable public static InternalExecutionPlan get(
      String fingerprint, CommandContext ctx, DatabaseSessionEmbedded db) {
    if (db == null || fingerprint == null) {
      return null;
    }
    return instance(db).getInternal(fingerprint, ctx, db);
  }

  public static void put(
      String fingerprint, ExecutionPlan plan, DatabaseSessionEmbedded db) {
    if (db == null || fingerprint == null) {
      return;
    }
    instance(db).putInternal(fingerprint, plan, db);
  }

  void putInternal(String fingerprint, ExecutionPlan plan, DatabaseSessionEmbedded db) {
    if (fingerprint == null || !cacheEnabled()) {
      return;
    }
    var internal = (InternalExecutionPlan) plan;
    var copyCtx = new BasicCommandContext();
    copyCtx.setDatabaseSession(db);
    internal = internal.copy(copyCtx);
    internal.close();
    putCached(fingerprint, internal);
  }

  @Nullable InternalExecutionPlan getInternal(
      String fingerprint, CommandContext ctx, DatabaseSessionEmbedded db) {
    var currentGlobalTimeout =
        db.getConfiguration().getValueAsLong(GlobalConfiguration.COMMAND_TIMEOUT);
    if (currentGlobalTimeout != this.lastGlobalTimeout) {
      invalidate();
      this.lastGlobalTimeout = currentGlobalTimeout;
    }
    if (fingerprint == null || !cacheEnabled()) {
      return null;
    }
    var result = getCached(fingerprint);
    return result != null ? result.copy(ctx) : null;
  }

  public static @Nonnull GremlinPlanCache instance(@Nonnull DatabaseSessionEmbedded db) {
    return db.getSharedContext().getGremlinPlanCache();
  }
}
