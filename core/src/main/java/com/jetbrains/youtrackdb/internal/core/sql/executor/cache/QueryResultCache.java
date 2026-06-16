package com.jetbrains.youtrackdb.internal.core.sql.executor.cache;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The per-transaction store of cached query results. One instance lives on a single {@code
 * FrontendTransactionImpl}, so every method runs on the transaction's owning thread; the only
 * cross-thread caller is {@link #clear()} during pool shutdown, which inherits the same best-effort
 * contract the transaction's own {@code clear()} sink already carries. No field is synchronised.
 *
 * <p><b>LRU bound.</b> Entries are held in a {@link LinkedHashMap} in access order. When a put would
 * push the live-entry count past {@link #maxEntries}, the cache evicts the least-recently-used entry
 * whose view refcount is zero. A pinned entry ({@code liveViewCount > 0}, a mid-iteration view) is
 * exempt so a view never loses rows under cache pressure (I9); the map therefore grows transiently
 * above the bound while every eldest candidate is pinned. Eviction closes the entry's paused stream
 * (I3), routes the evicted key to {@link #nonCacheableKeys} so the same query does not immediately
 * re-populate and churn the LRU, and counts an overflow.
 *
 * <p><b>Re-entrancy.</b> Two guards keep a query issued from inside the cache's own lookup-and-view
 * scope from recursively consulting the cache. The transaction-level {@code cacheCodeDepth}
 * counter on {@code FrontendTransactionImpl} brackets the whole lookup-and-view scope at the session;
 * the lookup-level {@link #inFlightLookup} boolean here guards the {@link #lookup} call itself. A
 * re-entrant {@code lookup} (for example a user-defined function in a WHERE clause issuing its own
 * {@code query()}) sees {@code inFlightLookup == true} and returns {@code null} so the caller falls
 * back to uncached execution.
 *
 * <p><b>K0_NONE version gate.</b> A {@link CacheableShape#K0_NONE} entry is reproducible from storage
 * plus the AST but cannot be reconciled record by record, so it serves a cached read only while no
 * mutation has happened since it was populated. {@link #lookup} compares the supplied current
 * mutation version against the entry's populate version: equal is a hit, diverged invalidates the
 * entry, counts a K0 invalidation, and — once a key has been invalidated {@code
 * k0NoneInvalidationThreshold} times — routes the key to {@link #nonCacheableKeys} to bound repopulate
 * churn in a write-heavy fragment.
 *
 * <p><b>Lifecycle.</b> {@link #clear()} is the transaction-end sink and is idempotent: it closes every
 * entry's paused stream and empties the map; a second call sees an empty map and is a no-op (I6). It
 * snapshots the entry set before iterating because {@code CachedEntry#close} touches the entry's own
 * state and a future change could reach back into the map (I1). {@link #invalidateAll()} is the
 * bulk-DML hook with the same snapshot discipline; it keeps the cache instance live (the transaction
 * continues) but drops every cached result.
 */
public final class QueryResultCache {

  private final QueryCacheMetrics metrics;
  private final int maxEntries;
  private final int maxRecordsPerEntry;
  private final int k0NoneInvalidationThreshold;

  /**
   * The cached entries in access order. {@code accessOrder=true} so a successful lookup moves an entry
   * to the most-recently-used end and eviction always targets the cold end. Auto-eviction via {@code
   * removeEldestEntry} is disabled (it cannot skip a pinned eldest); eviction is driven manually in
   * {@link #put} so it can walk past pinned entries.
   */
  private final LinkedHashMap<CacheKey, CachedEntry> entries =
      new LinkedHashMap<>(16, 0.75f, true);

  /**
   * Keys that must bypass the cache for the rest of the transaction: overflowed entries and
   * K0_NONE keys that crossed the invalidation-strike threshold. Uncapped per transaction.
   */
  private final Set<CacheKey> nonCacheableKeys = new HashSet<>();

  /**
   * Cumulative K0_NONE invalidation strikes per key. The strike count must survive the entry it
   * counts because an invalidation removes the entry, so it is tracked here on the cache rather than
   * on {@link CachedEntry}.
   */
  private final Map<CacheKey, Integer> k0Strikes = new HashMap<>();

  /** Lookup-level re-entrancy guard: true while a {@link #lookup} call is in progress. */
  private boolean inFlightLookup;

  public QueryResultCache(@Nonnull QueryCacheMetrics metrics) {
    this.metrics = metrics;
    this.maxEntries = GlobalConfiguration.QUERY_TX_RESULT_CACHE_MAX_ENTRIES.getValueAsInteger();
    this.maxRecordsPerEntry =
        GlobalConfiguration.QUERY_TX_RESULT_CACHE_MAX_RECORDS_PER_ENTRY.getValueAsInteger();
    this.k0NoneInvalidationThreshold =
        GlobalConfiguration.QUERY_TX_RESULT_CACHE_K0_NONE_INVALIDATION_THRESHOLD
            .getValueAsInteger();
  }

  /**
   * Looks up the entry for {@code key} at the transaction's current mutation version.
   *
   * <p>Returns {@code null} — caller falls back to uncached execution — when: a {@link #lookup} is
   * already in progress on this thread (the re-entrancy guard); the key is in {@link
   * #nonCacheableKeys}; no entry is cached; or a {@link CacheableShape#K0_NONE} entry's populate
   * version no longer equals {@code currentMutationVersion}. The version-mismatch case also evicts the
   * stale entry, counts a K0 invalidation, and routes the key to the non-cacheable set after the
   * configured number of strikes. A returned entry has been moved to the most-recently-used end and
   * its {@code hits} counter incremented; a {@code null} return for a real cache miss (key absent, not
   * a guard rejection) increments {@code misses}.
   *
   * @param currentMutationVersion the owning transaction's mutation version at lookup time, used only
   *     to gate {@link CacheableShape#K0_NONE} entries.
   */
  @Nullable public CachedEntry lookup(@Nonnull CacheKey key, long currentMutationVersion) {
    // Re-entrancy guard: a nested lookup (e.g. a UDF in a WHERE clause issuing query()) must not
    // recurse into the cache. Return before touching the flag so the outer lookup's finally still
    // owns the reset.
    if (inFlightLookup) {
      return null;
    }
    if (nonCacheableKeys.contains(key)) {
      // Permanently bypassed key (overflowed or K0-strike-exceeded). Not counted as a cache miss: the
      // decision to bypass was already accounted for when the key was routed to the set.
      return null;
    }
    inFlightLookup = true;
    try {
      var entry = entries.get(key);
      if (entry == null) {
        metrics.incrementMisses();
        return null;
      }
      if (entry.getShape() == CacheableShape.K0_NONE
          && entry.getPopulateMutationVersion() != currentMutationVersion) {
        // The transaction mutated since this K0_NONE entry was populated, and a K0_NONE result cannot
        // be reconciled record by record, so the cached answer is stale. Drop it, count the strike,
        // and route the key out of the cache once it has been invalidated often enough.
        invalidate(key, entry);
        metrics.incrementK0Invalidations();
        var strikes = k0Strikes.merge(key, 1, Integer::sum);
        if (strikes >= k0NoneInvalidationThreshold) {
          nonCacheableKeys.add(key);
        }
        return null;
      }
      if (entry.getShape() == CacheableShape.MATCH_TUPLE_MULTI) {
        // A multi-alias MATCH hit is only confirmed after the caller's class-scoped staleness gate runs
        // (it needs the transaction's record operations, which lookup does not carry). The caller counts
        // the hit via recordHit() on the served branch, or an invalidation via invalidateMatchMulti() on
        // the stale branch. Counting a hit here would double-count a stale hit as hit + invalidation,
        // diverging from the K0_NONE gate, which decides inside lookup and counts only the invalidation.
        return entry;
      }
      metrics.incrementHits();
      return entry;
    } finally {
      inFlightLookup = false;
    }
  }

  /**
   * Records a cache hit whose decision is made outside {@link #lookup}: the multi-alias MATCH
   * class-scoped gate, which returns a tentative entry from {@code lookup} and confirms it is served
   * only after the staleness scan. Keeps multi-alias MATCH hit accounting consistent with every other
   * shape (one hit per served query) without double-counting a stale hit.
   */
  public void recordHit() {
    metrics.incrementHits();
  }

  /**
   * Stores {@code entry} under {@code key}, then enforces the LRU bound. A key in {@link
   * #nonCacheableKeys} is never stored: the entry is closed immediately and the put is a no-op, so an
   * overflowed or strike-exceeded query stays uncached for the rest of the transaction. After the
   * insert, if the entry count exceeds {@link #maxEntries}, the eldest (least-recently-used) entry is
   * evicted when it is unpinned (see {@link #evictEldestIfUnpinned}).
   */
  public void put(@Nonnull CacheKey key, @Nonnull CachedEntry entry) {
    if (nonCacheableKeys.contains(key)) {
      // The key was already routed out of the cache; do not resurrect it. Release the just-built
      // entry's stream so the populating execution's resources are not leaked.
      entry.close();
      return;
    }
    entries.put(key, entry);
    // Install the per-entry record cap. The entry's lazy row append fires this callback the moment a
    // populate would push it past maxRecordsPerEntry: the entry is dropped from the cache and its key
    // routed out, so no further query re-populates it, while the live view keeps streaming every row.
    entry.setOverflowGuard(maxRecordsPerEntry, () -> overflowEntry(key));
    if (entries.size() > maxEntries) {
      evictEldestIfUnpinned();
    }
  }

  /**
   * Handles a per-entry record-cap overflow reported by a populating view: removes the over-cap
   * entry from the map, routes its key to the non-cacheable set so the same query stays uncached for
   * the rest of the transaction, and counts an overflow. The entry's stream is deliberately NOT closed
   * here: the view that triggered the overflow is still pulling from it and owns the consumer-facing
   * result. The entry is no longer reachable through the map, so it is released when that view closes.
   */
  private void overflowEntry(@Nonnull CacheKey key) {
    if (entries.remove(key) != null) {
      nonCacheableKeys.add(key);
      metrics.incrementOverflows();
    }
  }

  /**
   * Mirrors the {@code LinkedHashMap.removeEldestEntry} decision: inspects only the eldest
   * (least-recently-used) entry. When it is unpinned ({@code liveViewCount == 0}) it is evicted — its
   * paused stream is closed (I3), its key is routed to {@link #nonCacheableKeys} so the same query
   * does not immediately re-populate and churn the LRU, and an overflow is counted. When the
   * eldest is pinned by a live view the map is left transiently over the bound rather than truncating
   * that view (I9); a deeper hot entry is never evicted in its place, because doing so would discard a
   * fresher, more useful result to spare the cold pinned one.
   */
  private void evictEldestIfUnpinned() {
    // The first entry of an access-ordered LinkedHashMap is the eldest (least-recently-used); a put
    // does not promote, so the just-stored entry is at the hot end and is never the eldest here.
    var eldest = entries.entrySet().iterator().next();
    if (eldest.getValue().getLiveViewCount() != 0) {
      // Eldest is pinned by a live view; stay over the bound rather than truncate it.
      return;
    }
    var victimKey = eldest.getKey();
    var victimEntry = eldest.getValue();
    entries.remove(victimKey);
    nonCacheableKeys.add(victimKey);
    victimEntry.close();
    metrics.incrementOverflows();
  }

  /** Removes {@code key}'s entry from the map and closes its stream. Used by the K0 version gate. */
  private void invalidate(@Nonnull CacheKey key, @Nonnull CachedEntry entry) {
    entries.remove(key);
    entry.close();
  }

  /**
   * Invalidates a multi-alias MATCH ({@link CacheableShape#MATCH_TUPLE_MULTI}) entry whose class-scoped
   * version gate found a post-populate mutation in one of the pattern's read classes. Mirrors the
   * K0_NONE gate in {@link #lookup}: drops the entry, counts an invalidation, and routes the key
   * non-cacheable once it has been invalidated the configured number of times, bounding repopulate churn
   * in a write-heavy fragment. The strike count shares {@link #k0Strikes} with the K0_NONE gate, which
   * is sound because a given key has one fixed shape, so the two gates never count the same key. Called
   * from the session's hit-path gate rather than from {@link #lookup}, because the class-scoped scan
   * needs the transaction's record operations the lookup contract does not carry. A no-op when the key
   * has no live entry (already evicted).
   */
  public void invalidateMatchMulti(@Nonnull CacheKey key) {
    var entry = entries.get(key);
    if (entry == null) {
      return;
    }
    invalidate(key, entry);
    metrics.incrementK0Invalidations();
    var strikes = k0Strikes.merge(key, 1, Integer::sum);
    if (strikes >= k0NoneInvalidationThreshold) {
      nonCacheableKeys.add(key);
    }
  }

  /**
   * Drops every cached entry while keeping the cache instance live, closing each entry's paused stream
   * first. The bulk-DML hook for the one mid-transaction operation (TRUNCATE CLASS) that can change
   * stored data without flowing through the mutation log. Snapshots the values before iterating so an
   * entry close cannot disturb the map mid-walk. {@link #nonCacheableKeys} and {@link #k0Strikes} are
   * left intact: a key bypassed before the bulk operation stays bypassed.
   */
  public void invalidateAll() {
    var snapshot = new ArrayList<>(entries.values());
    entries.clear();
    for (var entry : snapshot) {
      entry.close();
    }
  }

  /**
   * Transaction-end sink: closes every entry's paused stream and empties all cache state. Idempotent
   * (I6) — a second call sees an empty map and returns without effect — and safe to reach from the
   * pool-shutdown thread because the underlying stream close is itself idempotent (I3, via {@link
   * IdempotentExecutionStream}). Snapshots the values before iterating for the same reason as {@link
   * #invalidateAll()}.
   */
  public void clear() {
    if (entries.isEmpty()) {
      // Idempotent fast path: a second tx-end clear finds nothing to release. Still drop the bypass
      // sets so a reused cache instance starts a fresh transaction clean.
      nonCacheableKeys.clear();
      k0Strikes.clear();
      return;
    }
    var snapshot = new ArrayList<>(entries.values());
    entries.clear();
    nonCacheableKeys.clear();
    k0Strikes.clear();
    for (var entry : snapshot) {
      entry.close();
    }
  }

  /**
   * Installs the aggregate contributor cap and a one-shot overflow callback on {@code state}, mirroring
   * the per-entry record cap {@link #put} installs on a {@link CachedEntry}. The cap bounds the
   * aggregate's per-contributor collections (not its single-scalar {@code results}); the callback fires
   * the first time {@link AggregateState#observe} crosses {@code maxRecordsPerEntry} during the eager
   * populate drive, routing {@code key} non-cacheable and counting an overflow. Unlike the record cap,
   * the entry is not yet in the map at populate-time overflow (the cache-put happens after the drive), so
   * the callback adds the key directly rather than removing a mapped entry; a later {@link #put} of that
   * now-overflowed key is a no-op (the {@link #nonCacheableKeys} guard in {@code put} closes it).
   */
  public void installAggregateOverflowGuard(
      @Nonnull CacheKey key, @Nonnull AggregateState state) {
    state.setOverflowGuard(maxRecordsPerEntry, () -> {
      // Idempotent on the key: nonCacheableKeys is a Set and the metric counts once because the state's
      // own one-shot latch fires this callback at most once per populate.
      nonCacheableKeys.add(key);
      metrics.incrementOverflows();
    });
  }

  /**
   * Counts an aggregate-splice fallback: a miss whose populating plan had no {@code
   * AggregateProjectionCalculationStep} to splice the side-tap above (e.g. a hardwired {@code
   * CountFromClassStep}, or any unexpected plan shape), so the query ran uncached. Routes through the
   * metric bridge to the global splice-failure rate.
   */
  public void incrementSpliceFailures() {
    metrics.incrementSpliceFailures();
  }

  /** Number of live cache entries. */
  public int size() {
    return entries.size();
  }

  /** Whether a key has been routed out of the cache for the rest of this transaction. */
  public boolean isNonCacheable(@Nonnull CacheKey key) {
    return nonCacheableKeys.contains(key);
  }

  public QueryCacheMetrics getMetrics() {
    return metrics;
  }

  /**
   * A snapshot of the live cache entries, for tests that assert an entry's populate-time metadata
   * (shape, class closure, projector) without going through {@link #lookup} and perturbing the
   * hit/miss metrics. Package-visible: the cache exposes no entry enumeration on its production API.
   */
  @Nonnull
  Collection<CachedEntry> entriesForTest() {
    return new ArrayList<>(entries.values());
  }
}
