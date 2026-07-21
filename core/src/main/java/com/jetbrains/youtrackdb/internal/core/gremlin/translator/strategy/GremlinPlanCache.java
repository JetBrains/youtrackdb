package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.config.StorageConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.MetadataUpdateListener;
import com.jetbrains.youtrackdb.internal.core.index.IndexManagerAbstract;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaShared;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * LRU cache for compiled Gremlin-to-MATCH execution plans, keyed by the post-walk {@link
 * GremlinPlanFingerprint}. Stores a deep-copied plan per entry; each hit returns a fresh {@link
 * InternalExecutionPlan#copy(CommandContext)} for the caller's context. Schema changes invalidate
 * the cache through the same {@link MetadataUpdateListener} hook as {@link
 * com.jetbrains.youtrackdb.internal.core.sql.parser.YqlExecutionPlanCache}.
 */
public final class GremlinPlanCache implements MetadataUpdateListener {

  private final int capacity;
  @Nullable private final Cache<String, InternalExecutionPlan> cache;
  private final AtomicLong lastInvalidation = new AtomicLong(-1);
  private volatile long lastGlobalTimeout =
      GlobalConfiguration.COMMAND_TIMEOUT.getValueAsLong();

  /**
   * @param size the size of the cache; 0 means cache disabled
   */
  public GremlinPlanCache(int size) {
    this.capacity = size;
    this.cache = size > 0 ? CacheBuilder.newBuilder().maximumSize(size).build() : null;
  }

  public static long getLastInvalidation(@Nonnull DatabaseSessionEmbedded db) {
    return instance(db).lastInvalidation.get();
  }

  /** Returns {@code true} when an entry exists for {@code fingerprint}. */
  public boolean contains(String fingerprint) {
    if (capacity == 0 || cache == null) {
      return false;
    }
    return cache.asMap().containsKey(fingerprint);
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
    if (fingerprint == null || capacity == 0 || cache == null) {
      return;
    }
    var internal = (InternalExecutionPlan) plan;
    var copyCtx = new BasicCommandContext();
    copyCtx.setDatabaseSession(db);
    internal = internal.copy(copyCtx);
    internal.close();
    cache.put(fingerprint, internal);
  }

  @Nullable InternalExecutionPlan getInternal(
      String fingerprint, CommandContext ctx, DatabaseSessionEmbedded db) {
    var currentGlobalTimeout =
        db.getConfiguration().getValueAsLong(GlobalConfiguration.COMMAND_TIMEOUT);
    if (currentGlobalTimeout != this.lastGlobalTimeout) {
      invalidate();
      this.lastGlobalTimeout = currentGlobalTimeout;
    }
    if (fingerprint == null || capacity == 0 || cache == null) {
      return null;
    }
    var result = cache.getIfPresent(fingerprint);
    return result != null ? result.copy(ctx) : null;
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

  public static @Nonnull GremlinPlanCache instance(@Nonnull DatabaseSessionEmbedded db) {
    return db.getSharedContext().getGremlinPlanCache();
  }
}
