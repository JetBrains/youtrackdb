package com.jetbrains.youtrackdb.internal.core.sql.executor.cache;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YqlStatementCache;
import java.util.NoSuchElementException;
import java.util.Set;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Verifies the lookup/eviction/invalidation contract of {@link QueryResultCache}: the access-order
 * LRU bound with live-view pinning, the K0_NONE mutation-version gate with strike-based routing
 * to the non-cacheable set, the pin-aware removal paths that defer a pinned entry's stream close to
 * the last view release (and back it with the tx-end {@code closePending} sweep), the
 * snapshot-before-iterate close paths, and the idempotent transaction-end {@code clear()} that
 * closes every entry's paused stream.
 *
 * <p>The cache reads {@code maxEntries} and {@code k0NoneInvalidationThreshold} from {@link
 * GlobalConfiguration} at construction, so each test sets a small, known bound before constructing
 * the cache and the {@link After} hook restores the production defaults.
 */
@Category(SequentialTest.class)
public class QueryResultCacheTest extends DbTestBase {

  private int savedMaxEntries;
  private int savedK0Threshold;
  private int savedMaxRecords;

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
    savedMaxRecords =
        GlobalConfiguration.QUERY_TX_RESULT_CACHE_MAX_RECORDS_PER_ENTRY.getValueAsInteger();
  }

  @After
  public void restoreConfig() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_MAX_ENTRIES.setValue(savedMaxEntries);
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_K0_NONE_INVALIDATION_THRESHOLD.setValue(
        savedK0Threshold);
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_MAX_RECORDS_PER_ENTRY.setValue(savedMaxRecords);
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
   * {@code isEmpty()} reports true only when the cache holds no entry AND no key has been routed
   * non-cacheable, the exact precondition the session's empty-cache short-circuit relies on to skip
   * the key build and the lookup/isNonCacheable gates. A stored entry makes it false; so does a
   * non-cacheable key with no live entry (the K0-strike path leaves {@code entries} empty but {@code
   * nonCacheableKeys} populated, so an {@code isNonCacheable} check could still bypass and must not be
   * skipped); {@code clear()} restores it to true.
   */
  @Test
  public void isEmptyReflectsEntriesAndNonCacheableKeys() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_K0_NONE_INVALIDATION_THRESHOLD.setValue(1);
    var cache = new QueryResultCache(new QueryCacheMetrics());
    var k = key("select from OUser group by name");

    Assert.assertTrue("A fresh cache is empty", cache.isEmpty());

    // A stored entry makes the cache non-empty (the entries-non-empty arm).
    cache.put(k, k0Entry(new CountingStream(), 1L));
    Assert.assertFalse("A stored entry makes the cache non-empty", cache.isEmpty());

    // One K0 strike at threshold 1 removes the entry AND routes the key non-cacheable, so entries is
    // empty again while nonCacheableKeys is not: isEmpty() must still report false (the nonCacheable
    // arm), otherwise the short-circuit would skip a bypass that should fire.
    Assert.assertNull(cache.lookup(k, 2L));
    Assert.assertEquals("the strike removed the entry", 0, cache.size());
    Assert.assertTrue("the key was routed non-cacheable", cache.isNonCacheable(k));
    Assert.assertFalse(
        "a non-cacheable key with no live entry still makes the cache non-empty", cache.isEmpty());

    // clear() drops both the entries and the non-cacheable set, so the cache is empty once more.
    cache.clear();
    Assert.assertTrue("clear() makes the cache empty again", cache.isEmpty());
  }

  /**
   * {@code recordMiss()} feeds the same miss counter {@code lookup} increments on an absent key, so
   * the session's empty-cache short-circuit (which skips {@code lookup} entirely) still accounts for
   * the miss it stands in for, and the hit/miss metric is identical whether or not the short-circuit
   * fired. It touches only the miss counter.
   */
  @Test
  public void recordMissCountsAMiss() {
    var metrics = new QueryCacheMetrics();
    var cache = new QueryResultCache(metrics);

    cache.recordMiss();

    Assert.assertEquals("recordMiss must increment the miss counter", 1, metrics.getMisses());
    Assert.assertEquals("recordMiss must not touch the hit counter", 0, metrics.getHits());
  }

  /**
   * With {@code maxEntries == 1}, putting a second unpinned entry must evict the least-recently-used
   * one: its stream is closed, an overflow is counted, and its key is routed to the
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

  /** A non-identifiable property-bag row, enough to drive {@link CachedEntry#recordPulledRow}. */
  private Result row() {
    return new ResultInternal(session);
  }

  /**
   * The K0_NONE version gate invalidates a stale entry, but a live view still pinning it must not have
   * its stream closed under it — that is the row-truncation bug. A pinned invalidation removes the entry
   * from the map (a re-lookup misses) yet keeps the stream open; the stream closes exactly once, when the
   * last view releases its pin. Simulates the live view with {@code incrementLiveViewCount}, the call the
   * view makes in its constructor.
   */
  @Test
  public void invalidatePinnedK0EntryDefersCloseUntilUnpinned() {
    var cache = new QueryResultCache(new QueryCacheMetrics());
    var stream = new CountingStream();
    var k = key("select from OUser where name = 'a'");
    var entry = k0Entry(stream, 5L);
    cache.put(k, entry);
    entry.incrementLiveViewCount(); // a live view is iterating this entry

    // A mutation advanced the version: the K0_NONE gate invalidates and drops the entry from the map.
    Assert.assertNull("Stale K0_NONE lookup must invalidate and miss", cache.lookup(k, 6L));
    Assert.assertEquals(
        "A pinned entry's stream must NOT close under a live view", 0, stream.closeCount);
    Assert.assertEquals("The invalidated entry must leave the map", 0, cache.size());

    // The view finishes and releases its pin: the deferred close now fires, exactly once.
    entry.decrementLiveViewCount();
    Assert.assertEquals(
        "The last pin release closes the deferred stream once", 1, stream.closeCount);
  }

  /**
   * invalidateAll (the TRUNCATE CLASS hook) drops every entry, but a pinned entry defers its stream
   * close so a TRUNCATE issued while a view iterates does not truncate the view. The stream closes when
   * the view releases its pin.
   */
  @Test
  public void invalidateAllDefersCloseForPinnedEntry() {
    var cache = new QueryResultCache(new QueryCacheMetrics());
    var stream = new CountingStream();
    var k = key("select from OUser where name = 'a'");
    var entry = recordEntry(stream);
    cache.put(k, entry);
    entry.incrementLiveViewCount();

    cache.invalidateAll();
    Assert.assertEquals(
        "invalidateAll must not close a pinned entry under its live view", 0, stream.closeCount);
    Assert.assertEquals(0, cache.size());

    entry.decrementLiveViewCount();
    Assert.assertEquals("The last pin release closes the deferred stream once", 1,
        stream.closeCount);
  }

  /**
   * A per-entry record-cap overflow removes the entry from the map. When the populating view still pins
   * it, the close is deferred to the last pin release rather than leaked: before the fix the overflowed
   * entry left the map and {@code clear()} could never reach it, so an early view close never released
   * its stream. With {@code maxRecordsPerEntry == 2}, the third {@code recordPulledRow} overflows.
   */
  @Test
  public void overflowDefersCloseForPinnedEntryNoLeak() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_MAX_RECORDS_PER_ENTRY.setValue(2);
    var cache = new QueryResultCache(new QueryCacheMetrics());
    var stream = new CountingStream();
    var k = key("select from OUser where name = 'a'");
    var entry = recordEntry(stream);
    cache.put(k, entry); // installs the per-entry cap + overflow callback
    entry.incrementLiveViewCount(); // the populating view pins it while it pulls

    // Drive the cached row count past the cap of 2; the third append fires the overflow callback.
    entry.recordPulledRow(row());
    entry.recordPulledRow(row());
    entry.recordPulledRow(row());

    Assert.assertTrue("Overflow must route the key non-cacheable", cache.isNonCacheable(k));
    Assert.assertEquals("The overflowed entry must leave the map", 0, cache.size());
    Assert.assertEquals(
        "A pinned overflowed entry must not close under its live view", 0, stream.closeCount);

    entry.decrementLiveViewCount();
    Assert.assertEquals(
        "The last pin release closes the overflowed entry once (no leak)", 1, stream.closeCount);
  }

  /**
   * A pinned entry invalidated and then abandoned (its view never releases the pin) must still close at
   * transaction end: {@code closeOrDefer} parks it in {@code closePending} and {@code clear()} sweeps
   * that set. Without the backstop a detached, abandoned entry would leak its stream past the map for
   * the rest of the transaction.
   */
  @Test
  public void abandonedPinnedInvalidatedEntryIsClosedByClear() {
    var cache = new QueryResultCache(new QueryCacheMetrics());
    var stream = new CountingStream();
    var k = key("select from OUser where name = 'a'");
    var entry = k0Entry(stream, 5L);
    cache.put(k, entry);
    entry.incrementLiveViewCount();

    cache.lookup(k, 6L); // version mismatch invalidates; pinned, so the close is deferred
    Assert.assertEquals(
        "The view never releases its pin (abandoned), so nothing closes yet", 0, stream.closeCount);

    cache.clear(); // transaction end
    Assert.assertEquals(
        "clear() must close the abandoned detached entry via closePending", 1, stream.closeCount);
  }

  /**
   * With no live view pinning it, an invalidated entry closes immediately — the deferral applies only
   * while a view holds the pin. Preserves the eager-close behaviour for the common, unpinned case.
   */
  @Test
  public void invalidateUnpinnedEntryClosesImmediately() {
    var cache = new QueryResultCache(new QueryCacheMetrics());
    var stream = new CountingStream();
    var k = key("select from OUser where name = 'a'");
    cache.put(k, k0Entry(stream, 5L));

    Assert.assertNull(cache.lookup(k, 6L)); // version mismatch, no pin
    Assert.assertEquals(
        "An unpinned invalidated entry closes immediately", 1, stream.closeCount);
    Assert.assertEquals(0, cache.size());
  }
}
