package com.jetbrains.youtrackdb.internal.core.sql.executor.cache;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YqlStatementCache;
import java.lang.reflect.Field;
import java.util.NoSuchElementException;
import java.util.Set;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Verifies the lookup/eviction/invalidation contract of {@link QueryResultCache}: the access-order
 * LRU bound with live-view pinning (I9), the K0_NONE mutation-version gate with strike-based routing
 * to the non-cacheable set, the two re-entrancy guards' lookup-level boolean, the
 * snapshot-before-iterate close paths, and the idempotent transaction-end {@code clear()} (I6) that
 * closes every entry's paused stream (I3).
 *
 * <p>The cache reads {@code maxEntries} and {@code k0NoneInvalidationThreshold} from {@link
 * GlobalConfiguration} at construction, so each test sets a small, known bound before constructing
 * the cache and the {@link After} hook restores the production defaults.
 */
@Category(SequentialTest.class)
public class QueryResultCacheTest extends DbTestBase {

  private int savedMaxEntries;
  private int savedK0Threshold;

  /** A stream that counts close calls so a test can prove eviction / clear closed it exactly once. */
  private static final class CountingStream implements ExecutionStream {

    int closeCount;

    @Override
    public boolean hasNext(CommandContext ctx) {
      return false;
    }

    @Override
    public Result next(CommandContext ctx) {
      throw new NoSuchElementException();
    }

    @Override
    public void close(CommandContext ctx) {
      closeCount++;
    }
  }

  @Before
  public void saveConfig() {
    savedMaxEntries = GlobalConfiguration.QUERY_TX_RESULT_CACHE_MAX_ENTRIES.getValueAsInteger();
    savedK0Threshold =
        GlobalConfiguration.QUERY_TX_RESULT_CACHE_K0_NONE_INVALIDATION_THRESHOLD
            .getValueAsInteger();
  }

  @After
  public void restoreConfig() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_MAX_ENTRIES.setValue(savedMaxEntries);
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_K0_NONE_INVALIDATION_THRESHOLD.setValue(
        savedK0Threshold);
  }

  /** Distinct query text yields a distinct {@link SQLStatement}, hence a distinct {@link CacheKey}. */
  private CacheKey key(String text) {
    SQLStatement stmt = YqlStatementCache.get(text, session);
    return CacheKey.forArgs(stmt, null);
  }

  private static CachedEntry recordEntry(ExecutionStream stream) {
    return new CachedEntry(
        CacheableShape.RECORD, Set.of("OUser"), null, null, stream, null, null, 0L);
  }

  private static CachedEntry k0Entry(ExecutionStream stream, long populateVersion) {
    return new CachedEntry(
        CacheableShape.K0_NONE, Set.of("OUser"), null, null, stream, null, null, populateVersion);
  }

  /**
   * A put followed by a lookup at the same mutation version returns the stored entry and counts a
   * hit; a lookup of an absent key returns {@code null} and counts a miss. This is the basic
   * memoisation contract for a RECORD entry, which has no version gate.
   */
  @Test
  public void putThenLookupHitsAndCountsMetrics() {
    var metrics = new QueryCacheMetrics();
    var cache = new QueryResultCache(metrics);
    var k = key("select from OUser where name = 'a'");
    var entry = recordEntry(new CountingStream());

    cache.put(k, entry);
    var hit = cache.lookup(k, 0L);

    Assert.assertSame("Lookup must return the stored entry", entry, hit);
    Assert.assertEquals(1, metrics.getHits());

    var miss = cache.lookup(key("select from OUser where name = 'absent'"), 0L);
    Assert.assertNull("Absent key must miss", miss);
    Assert.assertEquals(1, metrics.getMisses());
  }

  /**
   * With {@code maxEntries == 1}, putting a second unpinned entry must evict the least-recently-used
   * one: its stream is closed (I3), an overflow is counted, and its key is routed to the
   * non-cacheable set so it does not immediately re-populate. The surviving entry stays
   * reachable.
   */
  @Test
  public void lruEvictsColdestUnpinnedEntry() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_MAX_ENTRIES.setValue(1);
    var metrics = new QueryCacheMetrics();
    var cache = new QueryResultCache(metrics);

    var kOld = key("select from OUser where name = 'old'");
    var oldStream = new CountingStream();
    cache.put(kOld, recordEntry(oldStream));

    var kNew = key("select from OUser where name = 'new'");
    var newEntry = recordEntry(new CountingStream());
    cache.put(kNew, newEntry);

    Assert.assertEquals("Cache must hold exactly maxEntries after eviction", 1, cache.size());
    Assert.assertEquals("Evicted entry's stream must be closed", 1, oldStream.closeCount);
    Assert.assertEquals(1, metrics.getOverflows());
    Assert.assertTrue("Evicted key must be routed to the non-cacheable set",
        cache.isNonCacheable(kOld));
    Assert.assertSame("The fresh entry must survive eviction", newEntry, cache.lookup(kNew, 0L));
  }

  /**
   * I9: an entry with a live view ({@code liveViewCount > 0}) must never be evicted, even when it is
   * the least-recently-used. With {@code maxEntries == 1} and the cold entry pinned, putting a second
   * entry leaves the cache transiently over the bound rather than truncating the pinned view; the
   * pinned entry's stream is not closed.
   */
  @Test
  public void pinnedEntryIsExemptFromEviction() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_MAX_ENTRIES.setValue(1);
    var cache = new QueryResultCache(new QueryCacheMetrics());

    var kPinned = key("select from OUser where name = 'pinned'");
    var pinnedStream = new CountingStream();
    var pinnedEntry = recordEntry(pinnedStream);
    pinnedEntry.incrementLiveViewCount();
    cache.put(kPinned, pinnedEntry);

    var kNew = key("select from OUser where name = 'new'");
    cache.put(kNew, recordEntry(new CountingStream()));

    Assert.assertEquals(
        "A pinned eldest must not be evicted, so the cache stays over the bound", 2, cache.size());
    Assert.assertEquals("Pinned entry's stream must stay open", 0, pinnedStream.closeCount);
    Assert.assertSame("Pinned entry must remain reachable", pinnedEntry, cache.lookup(kPinned, 0L));
  }

  /**
   * A K0_NONE entry hits only while the transaction's mutation version still equals its
   * populate version. A lookup at the populate version is a hit; a lookup at a diverged version
   * invalidates the entry (removing it and closing its stream) and counts a K0 invalidation.
   */
  @Test
  public void k0NoneHitsAtSameVersionAndInvalidatesOnDivergence() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_K0_NONE_INVALIDATION_THRESHOLD.setValue(3);
    var metrics = new QueryCacheMetrics();
    var cache = new QueryResultCache(metrics);

    var k = key("select from OUser group by name");
    var stream = new CountingStream();
    cache.put(k, k0Entry(stream, 5L));

    Assert.assertNotNull("Same-version lookup must hit", cache.lookup(k, 5L));
    Assert.assertEquals(1, metrics.getHits());

    var afterMutation = cache.lookup(k, 6L);
    Assert.assertNull("Diverged-version K0_NONE lookup must invalidate and miss", afterMutation);
    Assert.assertEquals(1, metrics.getK0Invalidations());
    Assert.assertEquals("Invalidated entry's stream must be closed", 1, stream.closeCount);
    Assert.assertEquals("Invalidated entry must be removed from the map", 0, cache.size());
  }

  /**
   * Once a K0_NONE key has been invalidated as many times as {@code k0NoneInvalidationThreshold},
   * it is routed to the non-cacheable set and every later put for that key is a no-op (the entry's
   * stream is closed immediately), bounding repopulate churn in a write-heavy fragment.
   */
  @Test
  public void k0NoneStrikesRouteKeyToNonCacheable() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_K0_NONE_INVALIDATION_THRESHOLD.setValue(2);
    var cache = new QueryResultCache(new QueryCacheMetrics());
    var k = key("select from OUser group by name");

    // Strike 1: populate at version 1, invalidate by looking up at version 2.
    cache.put(k, k0Entry(new CountingStream(), 1L));
    Assert.assertNull(cache.lookup(k, 2L));
    Assert.assertFalse("One strike is below the threshold", cache.isNonCacheable(k));

    // Strike 2 reaches the threshold and routes the key out of the cache.
    cache.put(k, k0Entry(new CountingStream(), 3L));
    Assert.assertNull(cache.lookup(k, 4L));
    Assert.assertTrue("Threshold strikes must route the key to the non-cacheable set",
        cache.isNonCacheable(k));

    // A subsequent put for the now-bypassed key is a no-op and closes the entry's stream.
    var rejectedStream = new CountingStream();
    cache.put(k, k0Entry(rejectedStream, 5L));
    Assert.assertEquals("Put on a non-cacheable key must close the rejected entry's stream",
        1, rejectedStream.closeCount);
    Assert.assertEquals("Put on a non-cacheable key must not store an entry", 0, cache.size());
    Assert.assertNull("A non-cacheable key must keep missing", cache.lookup(k, 5L));
  }

  /**
   * I1/I6: {@code clear()} closes every entry's paused stream and empties the cache; a second
   * {@code clear()} is a no-op. The streams are closed exactly once across both calls.
   */
  @Test
  public void clearClosesAllStreamsAndIsIdempotent() {
    var cache = new QueryResultCache(new QueryCacheMetrics());
    var sA = new CountingStream();
    var sB = new CountingStream();
    cache.put(key("select from OUser where name = 'a'"), recordEntry(sA));
    cache.put(key("select from OUser where name = 'b'"), recordEntry(sB));

    cache.clear();
    Assert.assertEquals("clear must empty the cache", 0, cache.size());
    Assert.assertEquals(1, sA.closeCount);
    Assert.assertEquals(1, sB.closeCount);

    // Second clear is a no-op: no further close, no exception.
    cache.clear();
    Assert.assertEquals("Second clear must not re-close", 1, sA.closeCount);
    Assert.assertEquals(1, sB.closeCount);
    Assert.assertEquals(0, cache.size());
  }

  /**
   * The lookup-level re-entrancy guard ({@code if (inFlightLookup) return null;}, the second of the two
   * CR1 guards): while a {@code lookup} is in flight, a re-entrant {@code lookup} on the same cache must
   * short-circuit to {@code null} before touching the entry map or the metrics, so a nested lookup
   * (e.g. a UDF in a WHERE clause issuing {@code query()}) cannot recurse into the cache. The wired path
   * trips the tx-level {@code cacheCodeDepth} guard before {@code lookup} is ever re-entered, so this
   * branch has no end-to-end driver; this white-box test forces {@code inFlightLookup} true directly and
   * asserts a present-key lookup still returns null AND counts neither a hit nor a miss — proving the
   * guard returns at the very top of {@code lookup}, ahead of both the map probe and the miss counter.
   * Deleting or inverting the guard would make this lookup return the stored entry (or count a hit),
   * failing the assertions.
   */
  @Test
  public void inFlightLookupGuardShortCircuitsReentrantLookupToNull() throws Exception {
    var metrics = new QueryCacheMetrics();
    var cache = new QueryResultCache(metrics);
    var k = key("select from OUser where name = 'a'");
    var entry = recordEntry(new CountingStream());
    cache.put(k, entry);

    // Simulate a lookup already in progress on this cache instance (the state an outer lookup sets in
    // its try-block). The field is private and has no natural same-thread re-entry point inside lookup,
    // so drive the documented branch directly via reflection rather than leaving it untested.
    Field inFlight = QueryResultCache.class.getDeclaredField("inFlightLookup");
    inFlight.setAccessible(true);
    inFlight.setBoolean(cache, true);

    var reentrant = cache.lookup(k, 0L);

    Assert.assertNull("A lookup re-entered while one is in flight must return null", reentrant);
    Assert.assertEquals("The re-entrancy short-circuit must not count a hit", 0, metrics.getHits());
    Assert.assertEquals("The re-entrancy short-circuit must not count a miss (it precedes the map "
        + "probe)", 0, metrics.getMisses());

    // With the guard cleared, the same lookup hits normally — confirming the entry was reachable and
    // the null above came from the guard, not from an absent/closed entry.
    inFlight.setBoolean(cache, false);
    Assert.assertSame("Once the guard clears, the present key hits", entry, cache.lookup(k, 0L));
    Assert.assertEquals(1, metrics.getHits());
  }

  /**
   * The bulk-DML hook {@code invalidateAll()} drops every cached entry and closes each stream while
   * keeping the cache instance live, so a query issued after a TRUNCATE CLASS re-populates from
   * storage rather than serving a stale cached result.
   */
  @Test
  public void invalidateAllDropsEntriesAndClosesStreams() {
    var cache = new QueryResultCache(new QueryCacheMetrics());
    var sA = new CountingStream();
    var kA = key("select from OUser where name = 'a'");
    cache.put(kA, recordEntry(sA));

    cache.invalidateAll();

    Assert.assertEquals(0, cache.size());
    Assert.assertEquals(1, sA.closeCount);
    // The cache instance is still usable for the rest of the transaction.
    Assert.assertNull("A query after invalidateAll must miss and re-populate",
        cache.lookup(kA, 0L));
  }
}
