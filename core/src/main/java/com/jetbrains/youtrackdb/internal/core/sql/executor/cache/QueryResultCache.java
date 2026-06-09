/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.sql.executor.cache;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import java.util.ArrayList;
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
 * re-populate and churn the LRU (D8), and counts an overflow.
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
 * mutation has happened since it was populated (D7/D18). {@link #lookup} compares the supplied current
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
   * Keys that must bypass the cache for the rest of the transaction: overflowed entries (D8) and
   * K0_NONE keys that crossed the invalidation-strike threshold (D7). Uncapped per transaction.
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
      metrics.incrementHits();
      return entry;
    } finally {
      inFlightLookup = false;
    }
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
    if (entries.size() > maxEntries) {
      evictEldestIfUnpinned();
    }
  }

  /**
   * Mirrors the {@code LinkedHashMap.removeEldestEntry} decision: inspects only the eldest
   * (least-recently-used) entry. When it is unpinned ({@code liveViewCount == 0}) it is evicted — its
   * paused stream is closed (I3), its key is routed to {@link #nonCacheableKeys} so the same query
   * does not immediately re-populate and churn the LRU (D8), and an overflow is counted. When the
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
}
