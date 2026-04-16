package com.jetbrains.youtrackdb.internal.core.command;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;

/**
 * Per-query-execution LRU cache for graph traversal results (out, in, both, outE, inE, bothE).
 *
 * <p>Within a single read-consistent SQL statement, traversing the same vertex via the same
 * function and labels always yields the same result. This cache eliminates redundant traversals
 * when the same source entity appears repeatedly across LET subquery executions.
 *
 * <p>This cache is <b>not thread-safe</b>. It is created per query execution and accessed only
 * from the single thread executing that query.
 *
 * <p>Results are materialized before caching: graph functions return lazy iterables that can only
 * be consumed once, so the cache stores {@link ArrayList} copies that can be iterated multiple
 * times.
 *
 * <p>Null results are not cached, so traversals that return null (e.g. record not found or not a
 * vertex) are always re-executed. These cases are uncommon and inexpensive.
 *
 * @see TraversalCacheKey
 */
public class TraversalCache {

  /**
   * Thread-local hit counter incremented every time {@link #get} finds an entry. Isolated per
   * thread so that concurrent queries do not interfere with each other. Intended only for
   * observability in tests; production code should not depend on this counter.
   */
  static final ThreadLocal<AtomicLong> THREAD_HIT_COUNT =
      ThreadLocal.withInitial(AtomicLong::new);

  private final Map<TraversalCacheKey, Object> cache;

  /** Number of times {@link #get} found an entry. */
  private long hitCount;

  /** Number of times {@link #get} found no entry. */
  private long missCount;

  public TraversalCache(int maxEntries) {
    if (maxEntries < 1) {
      throw new IllegalArgumentException(
          "maxEntries must be >= 1, got " + maxEntries
              + ". Use QUERY_TRAVERSAL_CACHE_ENABLED=false to disable caching.");
    }
    // Access-order LinkedHashMap with removeEldestEntry evicts the LRU entry when the cache
    // exceeds maxEntries. Uses > (not >=) so that exactly maxEntries entries are retained.
    this.cache =
        new LinkedHashMap<>(16, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(Map.Entry<TraversalCacheKey, Object> eldest) {
            return size() > maxEntries;
          }
        };
  }

  /**
   * Returns the cached result for the given key, or {@code null} if not present.
   *
   * <p>A {@code null} return always means a cache miss — null traversal results are not stored.
   */
  @Nullable public Object get(TraversalCacheKey key) {
    var value = cache.get(key);
    if (value != null) {
      hitCount++;
      THREAD_HIT_COUNT.get().incrementAndGet();
    } else {
      missCount++;
    }
    return value;
  }

  /** Returns the number of cache lookups that found an entry since this instance was created. */
  public long getHitCount() {
    return hitCount;
  }

  /** Returns the number of cache lookups that found no entry since this instance was created. */
  public long getMissCount() {
    return missCount;
  }

  /**
   * Resets the thread-local hit counter to zero. Call this before running a query in a test to
   * isolate hit counts from prior queries in the same thread.
   */
  public static void resetThreadLocalHitCount() {
    THREAD_HIT_COUNT.get().set(0);
  }

  /**
   * Returns the number of cache hits recorded on this thread since the last {@link
   * #resetThreadLocalHitCount} call. Each {@link #get} that returns a non-null value increments
   * this counter.
   */
  public static long getThreadLocalHitCount() {
    return THREAD_HIT_COUNT.get().get();
  }

  /**
   * Materializes {@code result}, stores it under {@code key}, and returns the materialized copy.
   * Returns {@code null} without storing if {@code result} is {@code null}.
   *
   * <p>Callers should use the returned value rather than calling {@link #get} after {@code put},
   * because the returned value is guaranteed to be the materialized copy regardless of eviction.
   */
  @Nullable public Object put(TraversalCacheKey key, Object result) {
    if (result == null) {
      return null;
    }
    var materialized = materialize(result);
    cache.put(key, materialized);
    return materialized;
  }

  /**
   * Converts a graph traversal result into a reusable form. Graph functions return lazy iterables
   * (e.g. {@code MultiCollectionIterator}) that can only be consumed once; this method drains them
   * into an {@link ArrayList} so subsequent cache hits can iterate the result freely.
   */
  private static Object materialize(Object result) {
    if (result instanceof Collection<?> col) {
      return new ArrayList<>(col);
    }
    if (result instanceof Iterable<?> iter) {
      var list = new ArrayList<>();
      iter.forEach(list::add);
      return list;
    }
    // Single value (e.g. a single Vertex) — already immutable after loading.
    return result;
  }

  /**
   * Installs a traversal cache on the given context if caching is enabled in the database
   * configuration and no cache has been installed yet (on this context or any ancestor). Called once
   * per query execution by {@code LetQueryStep} and {@code MaterializedLetGroupStep}.
   */
  public static void installIfNeeded(CommandContext ctx) {
    if (ctx.getTraversalCache() != null) {
      return;
    }
    var db = ctx.getDatabaseSession();
    if (db == null) {
      return;
    }
    var config = db.getConfiguration();
    if (!config.getValueAsBoolean(GlobalConfiguration.QUERY_TRAVERSAL_CACHE_ENABLED)) {
      return;
    }
    var maxEntries =
        config.getValueAsInteger(GlobalConfiguration.QUERY_TRAVERSAL_CACHE_MAX_ENTRIES);
    ctx.setTraversalCache(new TraversalCache(maxEntries));
  }

  /**
   * Extracts an {@link Identifiable} from a graph-function target for use as the cache key source.
   * Graph functions can be called with either a raw {@link Identifiable} (direct traversal) or a
   * {@link Result} that wraps a persistent entity (projection context). Returns {@code null} when
   * the target cannot be reduced to a single persistent entity.
   */
  @Nullable public static Identifiable extractSourceIdentifiable(Object target) {
    if (target instanceof Identifiable id) {
      return id;
    }
    if (target instanceof Result result && result.isEntity()) {
      return result.asEntity();
    }
    return null;
  }
}
