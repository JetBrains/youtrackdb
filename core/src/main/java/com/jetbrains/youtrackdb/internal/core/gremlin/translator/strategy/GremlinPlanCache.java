package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.CoreMetrics;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.MetricDefinition;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.MetricScope.Global;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.MetricsRegistry;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.TimeRate;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.AbstractMetadataUpdateCache;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * LRU cache for compiled Gremlin-to-MATCH execution plans, keyed by the post-walk {@link
 * GremlinPlanFingerprint}, plus a second map of {@link GremlinTranslationTemplate}s keyed by
 * {@link GremlinStepWalker#extractShape}. The plan map stores a deep-copied closed plan per
 * entry; {@link #template(String, DatabaseSessionEmbedded)} returns that stored instance without
 * copying so the boundary step can copy on first open. The translation map skips the walker on a
 * hit. Schema changes invalidate both maps through the same {@link MetadataUpdateListener} hook as
 * {@link com.jetbrains.youtrackdb.internal.core.sql.parser.YqlExecutionPlanCache}.
 *
 * <p>Hit/miss counters ({@link #getHits()} / {@link #getMisses()}) are lifetime totals on the
 * shared-context instance for the plan map. {@link #getTranslationHits()} / {@link
 * #getTranslationMisses()} are the same for the translation map. Each plan-map lookup also feeds
 * the global profiler rates {@link CoreMetrics#GREMLIN_PLAN_CACHE_HIT_RATE} / {@link
 * CoreMetrics#GREMLIN_PLAN_CACHE_MISS_RATE}.
 */
public final class GremlinPlanCache
    extends AbstractMetadataUpdateCache<String, InternalExecutionPlan> {

  private volatile long lastGlobalTimeout =
      GlobalConfiguration.COMMAND_TIMEOUT.getValueAsLong();

  @Nullable private final Cache<String, GremlinTranslationTemplate> translationCache;

  private final LongAdder translationHits = new LongAdder();

  private final LongAdder translationMisses = new LongAdder();

  /**
   * @param size the size of the cache; 0 means cache disabled
   */
  public GremlinPlanCache(int size) {
    super(size);
    this.translationCache =
        size > 0 ? CacheBuilder.newBuilder().maximumSize(size).build() : null;
  }

  public static long getLastInvalidation(@Nonnull DatabaseSessionEmbedded db) {
    return instance(db).getLastInvalidation();
  }

  /** Returns {@code true} when an entry exists for {@code fingerprint}. */
  public boolean contains(String fingerprint) {
    return containsKey(fingerprint);
  }

  /** Returns {@code true} when a translation-cache entry exists for {@code shapeKey}. */
  public boolean containsTranslation(String shapeKey) {
    return translationCache != null && translationCache.asMap().containsKey(shapeKey);
  }

  /** Lifetime translation-cache hits on this shared-context instance. */
  public long getTranslationHits() {
    return translationHits.sum();
  }

  /** Lifetime translation-cache misses on this shared-context instance. */
  public long getTranslationMisses() {
    return translationMisses.sum();
  }

  @Nullable public static InternalExecutionPlan get(
      String fingerprint, CommandContext ctx, DatabaseSessionEmbedded db) {
    if (db == null || fingerprint == null) {
      return null;
    }
    return instance(db).getInternal(fingerprint, ctx, db);
  }

  /**
   * Returns the stored closed plan template without copying it. The caller must not execute or
   * close the returned instance; {@code YTDBMatchPlanStep} copies on first open.
   */
  @Nullable public static InternalExecutionPlan template(
      String fingerprint, DatabaseSessionEmbedded db) {
    if (db == null || fingerprint == null) {
      return null;
    }
    return instance(db).templateInternal(fingerprint, db);
  }

  public static void put(
      String fingerprint, ExecutionPlan plan, DatabaseSessionEmbedded db) {
    if (db == null || fingerprint == null) {
      return;
    }
    instance(db).putInternal(fingerprint, plan, db);
  }

  @Nullable public static GremlinTranslationTemplate getTranslation(
      String shapeKey, DatabaseSessionEmbedded db) {
    if (db == null || shapeKey == null) {
      return null;
    }
    return instance(db).getTranslationInternal(shapeKey, db);
  }

  public static void putTranslation(
      String shapeKey, GremlinTranslationTemplate template, DatabaseSessionEmbedded db) {
    if (db == null || shapeKey == null || template == null) {
      return;
    }
    instance(db).putTranslationInternal(shapeKey, template);
  }

  void putInternal(String fingerprint, ExecutionPlan plan, DatabaseSessionEmbedded db) {
    if (fingerprint == null || !cacheEnabled()) {
      return;
    }
    var internal = (InternalExecutionPlan) plan;
    // Honor the step-level cacheability contract, exactly as the YQL / GQL-SQL plan cache does with
    // result.canBeCached(). A plan containing a non-cacheable step — CountFromClassStep, whose count
    // varies per execution and whose fast path is gated by a per-session security-policy check — must
    // never be cached and replayed on another session, or the build-time security decision leaks
    // across users and across a later policy change. See CountFromClassStep.canBeCached().
    if (!internal.canBeCached()) {
      return;
    }
    var copyCtx = new BasicCommandContext();
    copyCtx.setDatabaseSession(db);
    internal = internal.copy(copyCtx);
    internal.close();
    putCached(fingerprint, internal);
  }

  @Nullable InternalExecutionPlan getInternal(
      String fingerprint, CommandContext ctx, DatabaseSessionEmbedded db) {
    var stored = templateInternal(fingerprint, db);
    return stored == null ? null : stored.copy(ctx);
  }

  @Nullable InternalExecutionPlan templateInternal(String fingerprint, DatabaseSessionEmbedded db) {
    invalidateIfTimeoutChanged(db);
    if (fingerprint == null || !cacheEnabled()) {
      return null;
    }
    var result = getCached(fingerprint);
    if (result != null) {
      recordHit();
      recordProfilerRate(CoreMetrics.GREMLIN_PLAN_CACHE_HIT_RATE);
      return result;
    }
    recordMiss();
    recordProfilerRate(CoreMetrics.GREMLIN_PLAN_CACHE_MISS_RATE);
    return null;
  }

  /**
   * Stored plan for {@code fingerprint} without recording a hit or miss. Used after {@link #put}
   * to retrieve the just-stored closed template.
   */
  @Nullable InternalExecutionPlan peekStored(String fingerprint) {
    return getCached(fingerprint);
  }

  @Nullable GremlinTranslationTemplate getTranslationInternal(
      String shapeKey, DatabaseSessionEmbedded db) {
    invalidateIfTimeoutChanged(db);
    if (shapeKey == null || translationCache == null) {
      return null;
    }
    var result = translationCache.getIfPresent(shapeKey);
    if (result != null) {
      translationHits.increment();
      return result;
    }
    translationMisses.increment();
    return null;
  }

  void putTranslationInternal(String shapeKey, GremlinTranslationTemplate template) {
    if (shapeKey == null || translationCache == null) {
      return;
    }
    translationCache.put(shapeKey, template);
  }

  @Override
  public void invalidate() {
    super.invalidate();
    if (translationCache != null) {
      translationCache.invalidateAll();
    }
  }

  private void invalidateIfTimeoutChanged(DatabaseSessionEmbedded db) {
    var currentGlobalTimeout =
        db.getConfiguration().getValueAsLong(GlobalConfiguration.COMMAND_TIMEOUT);
    if (currentGlobalTimeout != this.lastGlobalTimeout) {
      invalidate();
      this.lastGlobalTimeout = currentGlobalTimeout;
    }
  }

  private static void recordProfilerRate(MetricDefinition<Global, TimeRate> definition) {
    var registry = metricsRegistry();
    if (registry == null) {
      return;
    }
    registry.globalMetric(definition).record();
  }

  @Nullable private static MetricsRegistry metricsRegistry() {
    try {
      return YouTrackDBEnginesManager.instance().getMetricsRegistry();
    } catch (RuntimeException ignored) {
      // Engine / profiler not initialised (common in unit tests).
      return null;
    }
  }

  public static @Nonnull GremlinPlanCache instance(@Nonnull DatabaseSessionEmbedded db) {
    return db.getSharedContext().getGremlinPlanCache();
  }
}
