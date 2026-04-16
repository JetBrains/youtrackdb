package com.jetbrains.youtrackdb.internal.core.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Unit tests for {@link TraversalCache}: materialization of lazy results, LRU eviction, and null
 * handling.
 */
public class TraversalCacheTest {

  private static final RecordId RID_A = new RecordId(1, 1);
  private static final RecordId RID_B = new RecordId(1, 2);
  private static final RecordId RID_C = new RecordId(1, 3);

  private static TraversalCacheKey key(RecordId rid, String fn, String... labels) {
    return new TraversalCacheKey(rid, fn, List.of(labels));
  }

  /**
   * A Collection result is materialized into a new ArrayList so that subsequent cache hits do not
   * share the original mutable collection.
   */
  @Test
  public void putMaterializesCollectionResult() {
    var cache = new TraversalCache(10);
    var original = new ArrayList<>(List.of("v1", "v2"));
    var k = key(RID_A, "out", "KNOWS");

    cache.put(k, original);
    var retrieved = cache.get(k);

    assertThat(retrieved).isInstanceOf(List.class).asList().containsExactly("v1", "v2");
    // Modifying the original must not affect the cached copy.
    original.add("v3");
    assertThat(cache.get(k)).asList().containsExactly("v1", "v2");
  }

  /**
   * An Iterable result (e.g. lazy MultiCollectionIterator) is drained into an ArrayList so it can
   * be iterated multiple times from the cache.
   */
  @Test
  public void putMaterializesIterableResult() {
    var cache = new TraversalCache(10);
    Iterable<String> lazyIterable = List.of("e1", "e2", "e3");
    var k = key(RID_A, "outE", "LIKES");

    cache.put(k, lazyIterable);
    var first = cache.get(k);
    var second = cache.get(k);

    assertThat(first).isInstanceOf(List.class).asList().containsExactly("e1", "e2", "e3");
    // Cache can be read multiple times — the result is a reusable list.
    assertThat(second).isSameAs(first);
  }

  /**
   * A null result is not stored: subsequent get calls return null (cache miss), so the caller
   * re-executes the traversal. This avoids sentinel-value complexity in the cache API.
   */
  @Test
  public void putSkipsNullResult() {
    var cache = new TraversalCache(10);
    var k = key(RID_A, "out");

    cache.put(k, null);

    assertThat(cache.get(k)).isNull();
  }

  /**
   * get returns null for a key that was never put, and the same materialized object for a key that
   * was put.
   */
  @Test
  public void getReturnsCachedValue() {
    var cache = new TraversalCache(10);
    var k = key(RID_A, "in", "KNOWS");

    assertThat(cache.get(k)).isNull();

    cache.put(k, List.of("neighbor"));
    assertThat(cache.get(k)).isNotNull();
  }

  /**
   * When the cache reaches its capacity limit, the least-recently-used entry is evicted. The most
   * recently accessed entry is retained.
   */
  @Test
  public void lruEvictionRemovesOldestNotMostRecentlyUsed() {
    // Capacity of 2: only the two most-recently-used entries survive.
    var cache = new TraversalCache(2);
    var k1 = key(RID_A, "out");
    var k2 = key(RID_B, "out");
    var k3 = key(RID_C, "out");

    cache.put(k1, List.of("a"));
    cache.put(k2, List.of("b"));
    // Access k1 to make it the most recently used.
    cache.get(k1);
    // Adding k3 should evict k2 (LRU), not k1 (recently accessed).
    cache.put(k3, List.of("c"));

    assertThat(cache.get(k1)).isNotNull();
    assertThat(cache.get(k2)).isNull();
    assertThat(cache.get(k3)).isNotNull();
  }

  /**
   * Keys with the same RID and function name but different label lists are distinct cache entries.
   * Keys with the same RID, function name, and labels are equal.
   */
  @Test
  public void keyEqualityDependsOnAllThreeComponents() {
    var cache = new TraversalCache(10);
    var kKnows = key(RID_A, "out", "KNOWS");
    var kLikes = key(RID_A, "out", "LIKES");
    var kKnows2 = key(RID_A, "out", "KNOWS");

    cache.put(kKnows, List.of("n1"));
    cache.put(kLikes, List.of("n2"));

    assertThat(cache.get(kKnows)).asList().containsExactly("n1");
    assertThat(cache.get(kLikes)).asList().containsExactly("n2");
    // kKnows2 is a different object instance but equal by value.
    assertThat(cache.get(kKnows2)).asList().containsExactly("n1");
  }

  /**
   * Verifies that the parent-chain delegation in BasicCommandContext works: a child context with no
   * local cache delegates getTraversalCache() to its parent, enabling LET subquery child contexts
   * to share the cache installed on the outermost context.
   */
  @Test
  public void childContextDelegatesToParentCache() {
    var parent = new BasicCommandContext();
    var cache = new TraversalCache(10);
    parent.setTraversalCache(cache);

    var child = new BasicCommandContext();
    child.setParentWithoutOverridingChild(parent);

    assertThat(child.getTraversalCache()).isSameAs(cache);
  }

  /**
   * A child context that has its own local cache does not delegate to the parent.
   */
  @Test
  public void childLocalCacheTakesPrecedenceOverParent() {
    var parent = new BasicCommandContext();
    parent.setTraversalCache(new TraversalCache(10));

    var child = new BasicCommandContext();
    var childCache = new TraversalCache(5);
    child.setTraversalCache(childCache);
    child.setParentWithoutOverridingChild(parent);

    assertThat(child.getTraversalCache()).isSameAs(childCache);
  }

  /**
   * getHitCount() and getMissCount() track cache lookups. A hit is counted when get() returns a
   * non-null value; a miss is counted when get() returns null (either the key is absent or the
   * stored value was null — but we never store null, so absence is the only miss case).
   */
  @Test
  public void hitAndMissCountersTrackLookups() {
    var cache = new TraversalCache(10);
    var key = key(RID_A, "out");

    // Two misses before any value is stored.
    cache.get(key);
    cache.get(key);
    assertThat(cache.getMissCount()).isEqualTo(2);
    assertThat(cache.getHitCount()).isZero();

    cache.put(key, List.of("x"));

    // Two hits after the value is stored.
    cache.get(key);
    cache.get(key);
    assertThat(cache.getHitCount()).isEqualTo(2);
    assertThat(cache.getMissCount()).isEqualTo(2);
  }

  /**
   * The thread-local hit counter is independent of the per-instance counter. It accumulates hits
   * across all TraversalCache instances on the current thread and can be reset between tests.
   */
  @Test
  public void threadLocalHitCountAccumulatesAcrossInstances() {
    TraversalCache.resetThreadLocalHitCount();

    var cache1 = new TraversalCache(10);
    var cache2 = new TraversalCache(10);
    var key = key(RID_A, "out");

    cache1.put(key, List.of("a"));
    cache2.put(key, List.of("b"));

    cache1.get(key); // hit in cache1
    cache2.get(key); // hit in cache2
    cache1.get(new TraversalCacheKey(RID_B, "out", List.of())); // miss

    assertThat(TraversalCache.getThreadLocalHitCount())
        .as("thread-local counter should sum hits from both caches")
        .isEqualTo(2);
  }

  /**
   * Constructing a TraversalCache with maxEntries < 1 throws IllegalArgumentException. Use
   * QUERY_TRAVERSAL_CACHE_ENABLED=false to disable caching; zero-capacity caches are not supported
   * because the put-then-get pattern would silently lose results.
   */
  @Test
  public void constructorRejectsZeroMaxEntries() {
    assertThatThrownBy(() -> new TraversalCache(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxEntries must be >= 1");
  }

  /** Negative maxEntries is also rejected. */
  @Test
  public void constructorRejectsNegativeMaxEntries() {
    assertThatThrownBy(() -> new TraversalCache(-1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * put() returns the materialized copy of the stored result. Callers use this return value instead
   * of a separate get() call, which avoids data loss if the entry were immediately evicted.
   */
  @Test
  public void putReturnsMaterializedCopy() {
    var cache = new TraversalCache(10);
    var original = new ArrayList<>(List.of("v1", "v2"));
    var k = key(RID_A, "out", "KNOWS");

    var returned = cache.put(k, original);

    assertThat(returned).isInstanceOf(List.class).asList().containsExactly("v1", "v2");
    // The returned value is the same object stored in the cache.
    assertThat(cache.get(k)).isSameAs(returned);
  }

  /** put() returns null without storing when the result is null. */
  @Test
  public void putReturnsNullForNullResult() {
    var cache = new TraversalCache(10);
    var k = key(RID_A, "out");

    var returned = cache.put(k, null);

    assertThat(returned).isNull();
    assertThat(cache.get(k)).isNull();
  }

  /**
   * With maxEntries=1, the cache retains exactly one entry. Adding a second entry evicts the first.
   * The put return value is always the materialized result, regardless of eviction.
   */
  @Test
  public void maxEntriesOneBehavesCorrectly() {
    var cache = new TraversalCache(1);
    var k1 = key(RID_A, "out");
    var k2 = key(RID_B, "out");

    var r1 = cache.put(k1, List.of("a"));
    assertThat(r1).asList().containsExactly("a");
    assertThat(cache.get(k1)).isNotNull();

    // Adding k2 evicts k1.
    var r2 = cache.put(k2, List.of("b"));
    assertThat(r2).asList().containsExactly("b");
    assertThat(cache.get(k1)).isNull();
    assertThat(cache.get(k2)).isNotNull();
  }
}
