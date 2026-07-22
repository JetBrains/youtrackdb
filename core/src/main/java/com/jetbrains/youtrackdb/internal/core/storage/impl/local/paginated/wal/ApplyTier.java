/*
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal;

/**
 * Semantic apply tier of a {@link PageOperation} — the position of the operation's page delta in
 * the tier-ordered application of a commit to the shared page cache (YTDB-1203).
 *
 * <p>Today a commit's page deltas are applied in hash order and the cache passes through
 * logically inconsistent mid-apply states; a storage-wide seqlock ({@code ApplyPhaseEpoch})
 * aborts every optimistic read scope that overlaps any apply. Tier-ordered application replaces
 * that coupling: deltas are applied {@code NEW} &lt; {@code PAYLOAD} &lt; {@code PUBLISH} &lt;
 * {@code RETIRE} &lt; {@code GATE}, so that every <em>prefix</em> of a commit's application that
 * a validated {@code OptimisticReadScope} can observe yields either the committed-pre-commit
 * answer, the committed-post-commit answer, or an abort at stamp revalidation — never a
 * fabricated third answer (the prefix-cut argument; the full proof ships as JavaDoc with the
 * Track 04 apply-ordering machinery).
 *
 * <p>The tier is a static property of the operation <em>type</em>: it classifies what a
 * concurrent optimistic reader can observe through this page write, by traced reader behavior
 * rather than by field naming.
 *
 * <p><b>Side conditions</b> the classification must uphold (checked row-by-row in the golden
 * tier table):
 * <ul>
 *   <li><b>Publish-after-establish (SC-P)</b>: for content established <em>in place</em> on an
 *       existing page, the establishing delta's merged tier must stay strictly below every
 *       same-commit pointer targeting it. Establishment on fresh pages is immune via the
 *       NEW-force override; plain in-place updates of already-published entries carry no
 *       obligation (per-page write atomicity gives readers an old-or-new view).</li>
 *   <li><b>Retire-before-publish for relocations (SC-R)</b>: when a commit relocates an entry,
 *       some publish making the new copy reachable must have merged tier strictly below every
 *       retire removing the old copy; otherwise a prefix cut shows the entry at neither
 *       site.</li>
 *   <li><b>G2 (gates grant only commit-created content)</b>: a {@link #GATE} widening may make
 *       reachable only content <em>created</em> by the same commit; commit-<em>relocated</em>
 *       content must always be pointer-published at {@link #PUBLISH}. Equivalently: a gate may
 *       never be the sole publisher of a relocation.</li>
 * </ul>
 *
 * <p><b>Mechanics (Track 04, forward pointers only)</b>: tiers are accumulated per page in
 * {@code CacheEntryChanges} by max-merging the tiers of all operations recorded on that page —
 * same-page co-location is safe by construction because one merged page delta applies
 * atomically (both-or-neither). Pages freshly allocated by the commit are forced to
 * {@link #NEW}, overriding the max-merge (load-bearing for the reachability argument: a new
 * page is only reachable through a higher-tier publish, whose presence implies all new-page
 * deltas are present). The globally sorted apply loop, the file-creation pre-pass, the fallback
 * predicates, and the freed-page guard land in Track 04.
 */
public enum ApplyTier {

  /**
   * Content-establishing writes on pages freshly allocated by the commit. Such pages are
   * unreachable to readers until a later-tier publish applies, so their deltas may apply first
   * in any order. Track 04 additionally forces the merged delta of every newly allocated page
   * into this tier regardless of the operations recorded on it (NEW-force).
   */
  NEW,

  /**
   * In-place writes on already-existing pages that are not yet (or not by themselves)
   * reader-reachable answers: either establishing content a same-commit pointer will target
   * (subject to SC-P), or plain in-place updates of already-published entries where per-page
   * write atomicity yields an old-or-new view.
   */
  PAYLOAD,

  /**
   * Writes that make content reachable to readers: pointer inserts publishing a child or a
   * record location, sibling-link redirects, and single-page atomic entry inserts into already
   * reachable pages (an insert into a reachable page is its own publish). Must apply strictly
   * after the deltas establishing the published content (publish-after-establish) and strictly
   * before any same-commit retire of a relocated entry's old copy (SC-R).
   */
  PUBLISH,

  /**
   * Writes that remove reader-visible content or redirect reachability away from it: entry
   * removals, bucket shrinks, MVCC tombstones. Retire-last: all same-commit publishes apply
   * earlier, so a racing reader either observes the fully published new state or holds stamps
   * that die at final revalidation (this closes the historical stale-parent/shrunk-leaf
   * anomaly, YTDB-1178).
   */
  RETIRE,

  /**
   * End-of-commit bookkeeping, applied last. Includes both writer-only bookkeeping (free-space
   * maps, dirty-page bit sets, allocator counters) and reader-navigated gate fields such as
   * logical file-size gates that bound position-map lookups and iteration seeds. Gate widenings
   * are constrained by G2: they may expose only commit-created content, never act as the sole
   * publisher of a relocation.
   */
  GATE,

  /**
   * Escape tier for operations never produced by live code paths: legacy-dead storage formats
   * kept only for WAL deserialization compatibility, and dead code paths inside live components
   * (e.g., the unreachable B-tree merge/free-list chain). The presence of an UNORDERED
   * operation in a commit forces the whole commit onto the legacy hash-order apply path with
   * the epoch bracket (enforcement lands in Track 04), giving revived or future code a safe
   * default that is visible in the fallback counters.
   */
  UNORDERED
}
