# Track 7: SKIP support in K1 RECORD — prefix-cap merge

## Purpose / Big Picture
After this track, paginated `SELECT … SKIP n LIMIT m` queries within `n + m <= maxRecordsPerEntry` benefit from K1 RECORD merge — the same path that already handles plain `LIMIT m` shapes. UI list views in Hub that page through results within the same tx see cache hits for every page beyond the first, with mid-page insert/update/delete mutations re-spliced rather than wiping the entry.

R-C extension to Track 4's K1 RECORD: relax the `no SKIP` gate in `SharpMergePredicate.classify`, change the cached shape from "visible window of size LIMIT" to "full prefix of size `SKIP + LIMIT`", and shift the view's read offset by SKIP.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries

## Decision Log

## Outcomes & Retrospective

## Context and Orientation

`SharpMergePredicate.classify` (Track 4) currently returns `NONE` whenever `SQLSelectStatement.skip != null`. The rationale documented in Track 4's edge cases: "Sharp-merge doesn't know whether a CREATED record was in the skipped prefix or the returned tail."

Fix: cache the **full prefix** of size `n + m` (where `n = SKIP`, `m = LIMIT`), not just the visible window. A CREATED record that splices into the prefix at position `< n` becomes invisible to the view (correctly — it's now in the skipped region). A DELETED record at position `< n` shifts later records up, including potentially moving a record into the visible window.

`maxRecordsPerEntry` is the bound on the prefix size. When `n + m > maxRecordsPerEntry`, classify still returns `NONE` (K0 wipe fallback) — pathological deep pagination (`SKIP 1000000 LIMIT 10`) doesn't fit and can't benefit.

Changes touching three files:

1. `SharpMergePredicate.java` — gate change: `if (stmt.skip != null && (skip + limit) > maxRecordsPerEntry) return NONE; otherwise admit`.
2. `CachedEntry.java` — when `entry.skip > 0`, the `results` list represents the full prefix. Add a `skip: int` field on the entry; on splice/re-clip, the target size is `skip + limit`, not `limit`.
3. `CachedResultSetView.java` — `position` is the consumer's position within the **visible window**. When reading from `entry.results`, read at index `entry.skip + position`. When pulling from the live stream during initial population, the first `skip` records are still appended to the prefix list but not surfaced to the consumer.

Polymorphism gate and per-mutation dispatch logic are unchanged — they operate on the prefix list the same way they operate on a LIMIT-only list. Only the post-mutation re-clip target shifts from `limit` to `skip + limit`.

Concrete deliverables:
- `SharpMergePredicate.classify` — SKIP gate with cap.
- `CachedEntry` — new `skip: int` field (default 0 for non-SKIP queries), captured at entry creation.
- `CachedResultSetView` — offset-aware indexing into `entry.results`.
- `QueryResultCache.invalidateOnMutation` (RECORD branch) — re-clip target is `entry.skip + entry.limit` instead of `entry.limit` when `entry.skip > 0`.
- Tests covering SKIP queries within cap (CREATED mid-prefix → invisible; CREATED in window → visible; DELETED at position `< skip` shifts window; deep pagination above cap → K0 wipe).

## Plan of Work

1. Extend `SharpMergePredicate.classify` for SKIP. Add the gate: if `stmt instanceof SQLSelectStatement sel && sel.skip != null`, evaluate `sel.skip.getValue() + (sel.limit != null ? sel.limit.getValue() : Integer.MAX_VALUE)`; if the sum exceeds `GlobalConfiguration.QUERY_TX_RESULT_CACHE_MAX_RECORDS_PER_ENTRY` (read at classify time, not entry creation — the knob is hot-changeable but the value is snapshotted on classify), return `NONE`; otherwise continue with the existing `RECORD` checks. If `sel.limit == null` and `sel.skip != null`, return `NONE` (unbounded prefix, can't be capped).

2. Capture SKIP metadata on entry creation. In `DatabaseSessionEmbedded.query()` miss path (Track 2), when classify returns `RECORD`, read `sel.skip` and `sel.limit` and pass them into the `CachedEntry` constructor. Default both to 0 / `Integer.MAX_VALUE` for non-SKIP/non-LIMIT queries.

3. Adjust `CachedResultSetView`. On `next()`: if reading from `entry.results`, return `entry.results.get(entry.skip + position++)`; check the visible window bound `position < entry.limit`. On falling through to the live stream: first prime the prefix by pulling `entry.skip` records into `entry.results` without surfacing them to the consumer, then continue normal append-on-pull semantics.

4. Adjust `QueryResultCache.invalidateOnMutation` RECORD branch. The re-clip target becomes `entry.skip + entry.limit` when `entry.skip > 0`. CREATED splices into the prefix; UPDATED/DELETED operate on the prefix. No change to comparator or polymorphism gate.

5. Tests covering SKIP K1 paths:
   - (a) `SELECT FROM User WHERE active=true ORDER BY name SKIP 10 LIMIT 10`: pre-populate 25 users matching, query — first execution returns users 10-19; second execution from cache returns the same. Then INSERT a user that sorts at position 5 — the view's next read returns users 9-18 (user that was at position 9 is now visible at position 0).
   - (b) Same shape, DELETE a user at position 15 — view returns users 10-19 with one record shifted up.
   - (c) Same shape, INSERT a user that sorts at position 50 (outside prefix `0..19`) — entry's prefix unaffected, view still returns 10-19.
   - (d) `SELECT FROM User ORDER BY name SKIP 100000 LIMIT 10` (above cap): classify returns NONE; first mutation wipes the entry; second query is a miss.
   - (e) `SELECT FROM User ORDER BY name SKIP 5` (no LIMIT): classify returns NONE — unbounded prefix can't be capped.
   - (f) Re-clip after mutation cascade: 5 CREATEDs that all sort into the prefix → prefix stays at size `skip + limit` (re-clipped); the records pushed beyond `skip + limit` are dropped from cache.

## Concrete Steps

## Episodes

## Validation and Acceptance

- K1 SKIP merge correctness: for `SELECT FROM Class WHERE … ORDER BY … SKIP n LIMIT m` with `n + m <= maxRecordsPerEntry`, the cached visible window after one or more in-tx mutations matches what a fresh re-execution against the current state would return.
- Mutations on records at prefix positions `< skip`: shift the visible window correctly.
- Mutations on records at positions `[skip, skip+limit)`: respect the in-window update / delete / insert semantics.
- Mutations on records that would land at positions `>= skip + limit`: prefix re-clip drops them; the visible window is unaffected.
- Cap fallback: queries with `skip + limit > maxRecordsPerEntry` classify as NONE; first matching mutation wipes the entry.
- Unbounded prefix (`SKIP n` without `LIMIT`): classify returns NONE.
- Invariant I4 — post-merge entry observes the same WHERE / ORDER BY / SKIP / LIMIT contract as a fresh execution.

## Idempotence and Recovery

## Artifacts and Notes

## Interfaces and Dependencies

**In scope:**
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/SharpMergePredicate.java` — relax SKIP gate (cap-checked).
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/CachedEntry.java` — `skip: int`, `limit: int` fields (or `prefixLimit = skip + limit`); constructor takes them.
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/CachedResultSetView.java` — offset-aware indexing during read; prefix-prime during initial stream pull.
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/QueryResultCache.java` — `invalidateOnMutation` RECORD branch re-clip target.
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java` — entry-construction site reads `sel.skip` + `sel.limit`, passes to constructor.
- Tests under `core/src/test/java/com/jetbrains/youtrackdb/internal/core/tx/`.

**Out of scope:**
- MATCH per-tuple merge — Track 8.
- LET-based unions / unbounded SKIP (no LIMIT) / SKIP above cap — remain K0.
- Knob hot-change behavior mid-tx (entry's snapshotted cap stays consistent for its lifetime; new entries see the new value).

**Inter-track dependencies:**
- Depends on Track 4 (uses RECORD discriminator, `OrderByComparator`, `addRecordOperation` hook).
- Depends on Track 6 (Track 6's JMH baseline serves as the "before SKIP" measurement; this track adds a SKIP-specific JMH scenario).

**Library / function signatures introduced:**
- `CachedEntry(int skip, int limit, ...)` constructor extension (or `CachedEntry.withPrefix(int skip, int limit)` builder).
- `int CachedResultSetView.visibleIndex(int position)` helper — returns `entry.skip + position`.
