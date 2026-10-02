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
 * hit. An open schema transaction bypasses both shared maps without recording hits or misses.
 * Schema changes invalidate both maps through the same {@link MetadataUpdateListener} hook as
 * {@link com.jetbrains.youtrackdb.internal.core.sql.parser.YqlExecutionPlanCache}.
 *
 * <p>Hit/miss counters ({@link #getHits()} / {@link #getMisses()}) are lifetime totals on the
 * shared-context instance for the plan map. {@link #getTranslationHits()} / {@link
 * #getTranslationMisses()} are the same for the translation map. Each plan-map lookup also feeds
 * the global profiler rates {@link CoreMetrics#GREMLIN_PLAN_CACHE_HIT_RATE} / {@link
 * CoreMetrics#GREMLIN_PLAN_CACHE_MISS_RATE}.
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

  private volatile long lastGlobalTimeout =
      GlobalConfiguration.COMMAND_TIMEOUT.getValueAsLong();

  @Nullable private final Cache<String, Entry<GremlinTranslationTemplate>> translationCache;

  private final LongAdder translationHits = new LongAdder();

  private final LongAdder translationMisses = new LongAdder();

  // Publication and invalidation hooks are installed only by concurrency tests. Reads do not
  // consult them. Volatile access also lets a test drive a writer on another thread.
  @Nullable volatile Runnable afterPlanPublication;
  @Nullable volatile Runnable afterTranslationPublication;
  @Nullable volatile Runnable afterCounterIncrement;

  /**
   * @param size the size of the cache; 0 means cache disabled
   */
  public GremlinPlanCache(int size) {
    super(size);
    this.translationCache =
        size > 0 ? CacheBuilder.newBuilder().maximumSize(size).build() : null;
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

  @Nullable public static GremlinTranslationTemplate getTranslation(
      String shapeKey, DatabaseSessionEmbedded db) {
    if (db == null || shapeKey == null) {
      return null;
    }
    var cache = instance(db);
    cache.prepare(db);
    return cache.getTranslationInternal(shapeKey, db, cache.getInvalidationCounter());
  }

  /** Normalize timeout before capturing the generation, not after shape extraction. */
  void prepare(DatabaseSessionEmbedded db) {
    invalidateIfTimeoutChanged(db);
  }

  static void putTranslation(
      String shapeKey, GremlinTranslationTemplate template, DatabaseSessionEmbedded db,
      long generation) {
    if (db != null && shapeKey != null && template != null) {
      instance(db).putTranslationInternal(shapeKey, template, db, generation);
    }
  }

  void putInternal(
      String fingerprint, ExecutionPlan plan, DatabaseSessionEmbedded db, long generation) {
    // A tx-local schema must never publish a plan into the storage-wide cache.
    if (db.getTxSchemaState() != null || fingerprint == null || !cacheEnabled()) {
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
    var entry = new Entry<InternalExecutionPlan>(internal, generation);
    putCached(fingerprint, entry);
    var hook = afterPlanPublication;
    if (hook != null) {
      hook.run();
    }
    if (getInvalidationCounter() != generation) {
      // Compare by holder identity, so a stale writer cannot remove a newer replacement.
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

  /** Stored plan for {@code fingerprint} without recording a hit or miss (test inspection). */
  @Nullable InternalExecutionPlan peekStored(String fingerprint) {
    var entry = getCached(fingerprint);
    return entry == null ? null : entry.value;
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
    translationCache.put(shapeKey, entry);
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
