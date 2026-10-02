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
 * Bounded physical-plan and translation caches for Gremlin-to-MATCH compilation. Both maps share
 * the base metadata generation. Reads require both the captured and live generations. Publication
 * cannot replace a newer generation and stale cleanup removes only its own identity holder.
 *
 * <p>Physical entries own closed templates. Boundaries copy before execution and never run or close
 * a shared template. Schema transactions bypass both maps without recording hits or misses.
 * Hit and miss counters are lifetime totals.
 */
public final class GremlinPlanCache
    extends AbstractMetadataUpdateCache<String, GremlinPlanCache.Entry<InternalExecutionPlan>> {

  /** Each publication has its own identity, including publications of the shared decline sentinel. */
  static final class Entry<T> {
    final T value;
    final long generation;

    Entry(T value, long generation) {
      this.value = value;
      this.generation = generation;
    }
  }

  private volatile long lastGlobalTimeout = GlobalConfiguration.COMMAND_TIMEOUT.getValueAsLong();
  @Nullable private final Cache<String, Entry<GremlinTranslationTemplate>> translationCache;
  private final LongAdder translationHits = new LongAdder();
  private final LongAdder translationMisses = new LongAdder();
  @Nullable private final Runnable beforeClear;

  // Test hooks expose publication and advance-before-clear interleavings without locking readers.
  @Nullable volatile Runnable afterPlanPublication;
  @Nullable volatile Runnable afterTranslationPublication;
  @Nullable volatile Runnable afterCounterIncrement;

  /** @param size maximum entries per map, or zero to disable caching */
  public GremlinPlanCache(int size) {
    this(size, null);
  }

  GremlinPlanCache(int size, @Nullable Runnable beforeClear) {
    super(size);
    this.translationCache = size > 0 ? CacheBuilder.newBuilder().maximumSize(size).build() : null;
    this.beforeClear = beforeClear;
  }

  boolean isEnabled() {
    return cacheEnabled();
  }

  /** Capture before settings, shape extraction, or walking can read metadata. */
  public long getGeneration() {
    return getInvalidationCounter();
  }

  /** Returns true only for a currently valid physical entry. */
  public boolean contains(String fingerprint) {
    return peekStored(fingerprint) != null;
  }

  /** Returns true only for a currently valid translation or decline entry. */
  public boolean containsTranslation(String shapeKey) {
    var entry = translationCache == null ? null : translationCache.getIfPresent(shapeKey);
    return entry != null && entry.generation == getInvalidationCounter();
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

  @Nullable public static GremlinTranslationTemplate getTranslation(
      String shapeKey, DatabaseSessionEmbedded db) {
    if (db == null || shapeKey == null) {
      return null;
    }
    var cache = instance(db);
    cache.prepare(db);
    return cache.getTranslationInternal(shapeKey, db, cache.getInvalidationCounter());
  }

  static void putTranslation(
      String shapeKey, GremlinTranslationTemplate template, DatabaseSessionEmbedded db,
      long generation) {
    if (db != null && shapeKey != null && template != null) {
      instance(db).putTranslationInternal(shapeKey, template, db, generation);
    }
  }

  /** Normalize timeout before capturing the generation, not after shape extraction. */
  void prepare(DatabaseSessionEmbedded db) {
    invalidateIfTimeoutChanged(db);
  }

  void putInternal(
      String fingerprint, ExecutionPlan plan, DatabaseSessionEmbedded db, long generation) {
    if (db.getTxSchemaState() != null || fingerprint == null || !cacheEnabled()) {
      return;
    }
    var internal = (InternalExecutionPlan) plan;
    // Session-specific count and security decisions must not enter the shared cache.
    if (!internal.canBeCached()) {
      return;
    }
    var copy = internal.copy(new BasicCommandContext(db));
    copy.close();
    var entry = new Entry<InternalExecutionPlan>(copy, generation);
    cache.asMap().compute(fingerprint,
        (key, previous) -> previous == null || previous.generation <= generation ? entry
            : previous);
    var hook = afterPlanPublication;
    if (hook != null) {
      hook.run();
    }
    if (getInvalidationCounter() != generation) {
      cache.asMap().remove(fingerprint, entry);
    }
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
    prepare(db);
    var entry = planEntry(fingerprint, db, getInvalidationCounter());
    return entry == null ? null : entry.value;
  }

  /** Returns only a current closed template, without recording a hit or miss. */
  @Nullable InternalExecutionPlan peekStored(String fingerprint) {
    var entry = getCached(fingerprint);
    return entry != null && entry.generation == getInvalidationCounter() ? entry.value : null;
  }

  /** The captured value precedes parameter harvesting; a newer entry cannot serve old bindings. */
  @Nullable Entry<InternalExecutionPlan> planEntry(
      String fingerprint, DatabaseSessionEmbedded db, long captured) {
    if (db.getTxSchemaState() != null) {
      return null;
    }
    prepare(db);
    if (fingerprint == null || !cacheEnabled()) {
      return null;
    }
    var entry = getCached(fingerprint);
    if (valid(entry, captured)) {
      recordHit();
      recordProfilerRate(CoreMetrics.GREMLIN_PLAN_CACHE_HIT_RATE);
      return entry;
    }
    recordMiss();
    recordProfilerRate(CoreMetrics.GREMLIN_PLAN_CACHE_MISS_RATE);
    return null;
  }

  @Nullable Entry<InternalExecutionPlan> peekEntry(String fingerprint, long captured) {
    var entry = getCached(fingerprint);
    return valid(entry, captured) ? entry : null;
  }

  private boolean valid(@Nullable Entry<?> entry, long captured) {
    return entry != null && entry.generation == captured
        && entry.generation == getInvalidationCounter();
  }

  @Nullable GremlinTranslationTemplate getTranslationInternal(
      String shapeKey, DatabaseSessionEmbedded db, long captured) {
    if (db.getTxSchemaState() != null) {
      return null;
    }
    prepare(db);
    if (shapeKey == null || translationCache == null) {
      return null;
    }
    var entry = translationCache.getIfPresent(shapeKey);
    if (valid(entry, captured)) {
      translationHits.increment();
      return entry.value;
    }
    translationMisses.increment();
    return null;
  }

  void putTranslationInternal(
      String shapeKey, GremlinTranslationTemplate template, DatabaseSessionEmbedded db,
      long generation) {
    if (db.getTxSchemaState() != null || shapeKey == null || translationCache == null) {
      return;
    }
    var entry = new Entry<GremlinTranslationTemplate>(template, generation);
    translationCache.asMap().compute(shapeKey,
        (key, previous) -> previous == null || previous.generation <= generation ? entry
            : previous);
    var hook = afterTranslationPublication;
    if (hook != null) {
      hook.run();
    }
    if (getInvalidationCounter() != generation) {
      translationCache.asMap().remove(shapeKey, entry);
    }
  }

  @Override
  protected void afterGenerationAdvanced() {
    var hook = afterCounterIncrement;
    if (hook != null) {
      hook.run();
    }
    if (beforeClear != null) {
      beforeClear.run();
    }
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
    if (registry != null) {
      registry.globalMetric(definition).record();
    }
  }

  @Nullable private static MetricsRegistry metricsRegistry() {
    try {
      return YouTrackDBEnginesManager.instance().getMetricsRegistry();
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  public static @Nonnull GremlinPlanCache instance(@Nonnull DatabaseSessionEmbedded db) {
    return db.getSharedContext().getGremlinPlanCache();
  }
}
