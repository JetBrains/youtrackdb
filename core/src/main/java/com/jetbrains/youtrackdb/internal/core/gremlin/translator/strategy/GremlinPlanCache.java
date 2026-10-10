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
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Bounded physical-plan and translation caches for Gremlin-to-MATCH compilation. Both maps share
 * one metadata generation because every metadata event invalidates both. Each publication keeps
 * its own build or walk-start generation. Lookups accept only the current generation, without a
 * cache-wide lock. Invalidation advances first, then clears both maps.
 *
 * <p>Physical entries own closed templates. Boundaries copy before execution and never run or close
 * a shared template. Complete single-plan translation hits skip walking and physical planning.
 * Multi-plan traversals walk again and cache eligible children independently. Schema transactions
 * bypass both maps without recording hits or misses.
 *
 * <p>Hit and miss counters are lifetime totals. Physical lookups also feed the global profiler rates
 * {@link CoreMetrics#GREMLIN_PLAN_CACHE_HIT_RATE} and
 * {@link CoreMetrics#GREMLIN_PLAN_CACHE_MISS_RATE}.
 */
public final class GremlinPlanCache
    extends AbstractMetadataUpdateCache<String, GremlinPlanCache.StampedPlan> {

  private final AtomicLong generation = new AtomicLong();

  private volatile long lastGlobalTimeout = GlobalConfiguration.COMMAND_TIMEOUT.getValueAsLong();

  @Nullable private final Cache<String, StampedTranslation> translationCache;

  private final LongAdder translationHits = new LongAdder();
  private final LongAdder translationMisses = new LongAdder();

  // Package-private constructor seam for deterministic advance-before-clear race tests.
  @Nullable private final Runnable beforeClear;

  record StampedPlan(InternalExecutionPlan plan, long generation) {
  }

  // The envelope identifies each publication, including declines with no plan payload.
  private record StampedTranslation(GremlinTranslationTemplate template, long generation) {
  }

  /** @param size maximum entries per map, or zero to disable caching */
  public GremlinPlanCache(int size) {
    this(size, null);
  }

  GremlinPlanCache(int size, @Nullable Runnable beforeClear) {
    super(size);
    this.translationCache = size > 0 ? CacheBuilder.newBuilder().maximumSize(size).build() : null;
    this.beforeClear = beforeClear;
  }

  public static long getLastInvalidation(@Nonnull DatabaseSessionEmbedded db) {
    return instance(db).getLastInvalidation();
  }

  boolean isEnabled() {
    return cacheEnabled();
  }

  /** Capture before settings, shape extraction, or walking can read metadata. */
  public long getGeneration() {
    return generation.get();
  }

  /** Returns true only for a currently valid physical entry. */
  public boolean contains(String fingerprint) {
    return peekStored(fingerprint) != null;
  }

  /** Returns true only for a currently valid translation or decline entry. */
  public boolean containsTranslation(String shapeKey) {
    var entry = translationCache == null ? null : translationCache.getIfPresent(shapeKey);
    return entry != null && entry.generation() == generation.get();
  }

  public long getTranslationHits() {
    return translationHits.sum();
  }

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

  /** Returns a closed template. The caller must copy it before executing or closing. */
  @Nullable public static InternalExecutionPlan template(
      String fingerprint, DatabaseSessionEmbedded db) {
    if (db == null || fingerprint == null) {
      return null;
    }
    return instance(db).templateInternal(fingerprint, db);
  }

  /** Publishes with the unchanged generation captured before the build's first metadata read. */
  @Nullable public static InternalExecutionPlan put(
      String fingerprint, InternalExecutionPlan plan, DatabaseSessionEmbedded db,
      long buildGeneration) {
    if (db == null || fingerprint == null) {
      return null;
    }
    return instance(db).putInternal(fingerprint, plan, db, buildGeneration);
  }

  @Nullable public static GremlinTranslationTemplate getTranslation(
      String shapeKey, DatabaseSessionEmbedded db) {
    if (db == null || shapeKey == null) {
      return null;
    }
    return instance(db).getTranslationInternal(shapeKey, db);
  }

  public static void putTranslation(
      String shapeKey, GremlinTranslationTemplate template, DatabaseSessionEmbedded db,
      long walkGeneration) {
    if (db == null || shapeKey == null || template == null) {
      return;
    }
    instance(db).putTranslationInternal(shapeKey, template, db, walkGeneration);
  }

  @Nullable InternalExecutionPlan putInternal(String fingerprint, InternalExecutionPlan plan,
      DatabaseSessionEmbedded db, long buildGeneration) {
    if (db.getTxSchemaState() != null || fingerprint == null || !cacheEnabled()
        || !plan.canBeCached()) {
      return null;
    }
    var copyCtx = new BasicCommandContext(db);
    var copy = plan.copy(copyCtx);
    copy.close();
    var entry = new StampedPlan(copy, buildGeneration);
    // Compare inside compute so an older publisher never overwrites a newer entry. Late stale
    // entries stay stored until replacement or eviction, but every lookup rejects their stamp.
    var published = cache.asMap().compute(fingerprint,
        (key, previous) -> previous == null || previous.generation() <= buildGeneration ? entry
            : previous);
    // Return provenance for this exact publication, not another plan with an equal key.
    return published == entry && entry.generation() == generation.get() ? copy : null;
  }

  @Nullable InternalExecutionPlan getInternal(
      String fingerprint, CommandContext ctx, DatabaseSessionEmbedded db) {
    var stored = templateInternal(fingerprint, db);
    return stored == null ? null : stored.copy(ctx);
  }

  @Nullable InternalExecutionPlan templateInternal(String fingerprint, DatabaseSessionEmbedded db) {
    if (db.getTxSchemaState() != null) {
      return null;
    }
    invalidateIfTimeoutChanged(db);
    if (fingerprint == null || !cacheEnabled()) {
      return null;
    }
    var result = peekStored(fingerprint);
    if (result != null) {
      recordHit();
      recordProfilerRate(CoreMetrics.GREMLIN_PLAN_CACHE_HIT_RATE);
      return result;
    }
    recordMiss();
    recordProfilerRate(CoreMetrics.GREMLIN_PLAN_CACHE_MISS_RATE);
    return null;
  }

  /** Returns only a current closed template, without recording a hit or miss. */
  @Nullable InternalExecutionPlan peekStored(String fingerprint) {
    var entry = getCached(fingerprint);
    return entry != null && entry.generation() == generation.get() ? entry.plan() : null;
  }

  @Nullable GremlinTranslationTemplate getTranslationInternal(
      String shapeKey, DatabaseSessionEmbedded db) {
    if (db.getTxSchemaState() != null) {
      return null;
    }
    invalidateIfTimeoutChanged(db);
    if (shapeKey == null || translationCache == null) {
      return null;
    }
    var entry = translationCache.getIfPresent(shapeKey);
    if (entry != null && entry.generation() == generation.get()) {
      translationHits.increment();
      return entry.template();
    }
    translationMisses.increment();
    return null;
  }

  void putTranslationInternal(String shapeKey, GremlinTranslationTemplate template,
      DatabaseSessionEmbedded db, long walkGeneration) {
    if (db.getTxSchemaState() != null || shapeKey == null || translationCache == null) {
      return;
    }
    var entry = new StampedTranslation(template, walkGeneration);
    translationCache.asMap().compute(shapeKey,
        (key, previous) -> previous == null || previous.generation() <= walkGeneration ? entry
            : previous);
  }

  @Override
  public void invalidate() {
    generation.incrementAndGet();
    if (beforeClear != null) {
      beforeClear.run();
    }
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
