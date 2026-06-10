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

/**
 * Per-transaction counter holder for the tx-result cache. Lives on the cache instance owned by a
 * single {@code FrontendTransactionImpl}, so it observes only the owning thread and needs no
 * synchronisation: plain {@code long} fields suffice.
 *
 * <p>The counters are cumulative for the lifetime of the transaction and are discarded with it.
 * Later steps wire the increments at the lookup/put/eviction sites; this step establishes the
 * holder and its API so the cache classes can reference it without forward declarations. No call
 * site increments any counter yet.
 *
 * <ul>
 *   <li>{@code hits} — lookups that returned a usable cached entry.
 *   <li>{@code misses} — lookups that found no entry and drove a fresh execution.
 *   <li>{@code spliceFailures} — aggregate splices that fell back to uncached execution because the
 *       planner emitted an unexpected execution-plan shape.
 *   <li>{@code k0Invalidations} — K0_NONE entries invalidated because an intervening mutation
 *       advanced the mutation version past the entry's populate version.
 *   <li>{@code overflows} — entries removed by a cache bound: either LRU eviction at the per-tx entry
 *       count ({@code maxEntries}) or a populate crossing the per-entry record cap
 *       ({@code maxRecordsPerEntry}). Both route the key to the non-cacheable set.
 * </ul>
 */
public final class QueryCacheMetrics {

  private long hits;
  private long misses;
  private long spliceFailures;
  private long k0Invalidations;
  private long overflows;

  public void incrementHits() {
    hits++;
  }

  public void incrementMisses() {
    misses++;
  }

  public void incrementSpliceFailures() {
    spliceFailures++;
  }

  public void incrementK0Invalidations() {
    k0Invalidations++;
  }

  public void incrementOverflows() {
    overflows++;
  }

  public long getHits() {
    return hits;
  }

  public long getMisses() {
    return misses;
  }

  public long getSpliceFailures() {
    return spliceFailures;
  }

  public long getK0Invalidations() {
    return k0Invalidations;
  }

  public long getOverflows() {
    return overflows;
  }
}
