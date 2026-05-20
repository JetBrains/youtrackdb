# Track 5: Hardening — non-determinism, DML invalidation, memory bound, expression ORDER BY

## Purpose / Big Picture
After this track, the cache is correctness-complete: non-deterministic queries bypass cleanly, DML statements wipe it, memory stays bounded under pathological workloads, and K1 RECORD admits ORDER BY expressions that are deterministic.

Production-readiness for correctness: AST denylist for non-deterministic functions/variables, `NOCACHE` hint extension, full-wipe on non-idempotent `executeInternal()` calls, LRU enforcement at `maxEntries` (already wired in Track 3), per-entry overflow handling at `maxRecordsPerEntry`. Plus R-B: with `NonDeterministicQueryDetector` now in place, relax the K1 RECORD classify gate to admit ORDER BY items whose modifier chain (e.g., `name.upper()`, `priority.asString()`) the detector flags as deterministic. The current grammar (`YouTrackDBSql.jj`) accepts only `Identifier [Modifier]`, `Rid`, or `RECORD_ATTRIBUTE` in ORDER BY items; arithmetic expressions (`ORDER BY priority * 10`) are not grammar-supported and out of scope for v1. `OrderByComparator` (built in Track 4) delegates ranking to `SQLOrderByItem.compare(a, b, ctx)`, which already reaches into `modifier.execute(record, value, ctx)`, so no comparator changes are needed — the change is one extra check in `SharpMergePredicate.classify` plus tests. Observability (`QueryCacheMetrics` + JMH benchmark) is handled separately in Track 6.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries

## Decision Log

## Outcomes & Retrospective

## Context and Orientation

`NonDeterministicQueryDetector` is a new utility under `internal.core.tx` (or `internal.core.sql.functions`). It walks an `SQLStatement` and returns true if any of these are present:
- Function call with name (case-insensitive) in the denylist: `sysdate`, `random`, `uuid`, `eval`, `currenttimemillis`, `nanotime`. Plus zero-arg `date()` (arity check).
- Context-variable reference: identifier nodes whose textual value starts with `$` (the parser admits `$` as the leading character of an `IDENTIFIER` token, so `$now` parses as a single identifier whose string value begins with `$`; existing call sites at `SelectExecutionPlanner.java:932` and `SQLSuffixIdentifier.java:85` test this via `stringValue.charAt(0) == '$'`). The denylist names checked are `$now`, `$current`, `$currentdate`, `$currenttime`, `$thread`, `$parent`, `$depth`.
- (Reserved for future) function annotated as non-deterministic via a hypothetical SPI flag — not required in v1.

Walk uses the existing visitor pattern on the AST or a simple `toGenericStatement(StringBuilder)` approach (every node has it). Inspecting the rendered string for token presence is a cheap fallback if the visitor pattern is unwieldy.

`SQLSelectStatement.noCache` is already a `Boolean` field (line 48). Current code uses it to gate `executinPlanCanBeCached` (line 161 returns false by default; SQLSelectStatement overrides — verify). Track 5 extends semantics: if `noCache == TRUE`, the result cache also bypasses (no lookup, no put). This is a one-line gate in the cache-lookup helper from Track 2.

DML invalidation: `DatabaseSessionEmbedded.executeInternal()` (line 702) routes statements that bypass per-record hooks through `invalidateAll()`. The trigger is an **explicit type list** (helper `isBulkBypass(SQLStatement)`), not `!isIdempotent()`:
- Schema DDL: `SQLCreateClassStatement`, `SQLDropClassStatement`, `SQLAlterClassStatement`, `SQLCreatePropertyStatement`, `SQLDropPropertyStatement`, `SQLAlterPropertyStatement`, `SQLCreateIndexStatement`, `SQLDropIndexStatement`, `SQLRebuildIndexStatement`.
- Class-level bulk ops: `SQLTruncateClassStatement`. (The canonical SQL grammar source at `YouTrackDBSql.jjt:3726-3729` declares only `TRUNCATE CLASS` — no `TRUNCATE CLUSTER` / `TRUNCATE RECORD` productions exist.)

Regular `SQLInsertStatement`/`SQLUpdateStatement`/`SQLDeleteStatement` are **not** in this list. They flow through `addRecordOperation` per affected record, so per-entry sharp-merge from Track 4 handles them — adding `invalidateAll()` on top would destroy K1-merged state for zero benefit. Scripts (`computeScript(...)`) are outside `executeInternal()` entirely and are a Non-Goal of this plan. The hook fires before `statement.execute()` so subsequent queries within the same statement see a clean state. `invalidateAll()` iterates entries, closes each entry's stream via `entry.close()`, clears the map.

Per-entry overflow (`maxRecordsPerEntry`): when `CachedEntry.add(Result)` would exceed the cap, set `entry.overflow = true`, do NOT append. View still returns the result to the consumer (it pulls from stream directly when in overflow mode — but the entry stays unusable for future replay since it's incomplete). Next `query()` of the same key sees `entry.overflow == true` and treats it as a miss — it removes the overflow entry, executes fresh, builds a new entry. If the new entry also overflows, this happens once per query; emit a `WARN` log so operators can raise the knob.

Concrete deliverables:
- `NonDeterministicQueryDetector` class with one static `boolean contains(SQLStatement)`.
- `QueryResultCache.invalidateAll()` body.
- `noCache` gate in the cache-lookup helper.
- Per-entry overflow handling in `CachedEntry.add`.
- DML invalidation hook in `DatabaseSessionEmbedded.executeInternal`.
- Integration tests covering all four surfaces.

## Plan of Work

1. Implement `NonDeterministicQueryDetector.contains(SQLStatement)`. Walk every `SQLFunctionCall` and every identifier node whose textual value starts with `$` (matching the existing `charAt(0) == '$'` pattern at `SelectExecutionPlanner.java:932` / `SQLSuffixIdentifier.java:85`). Cache the result on the `CachedEntry` if computed (avoid re-walking on repeat queries — but the AST is `STATEMENT_CACHE_SIZE`-cached anyway, so the walk runs at most once per entry).
2. Wire the detector into the cache-lookup helper from Track 2 + extend `noCache` semantics. Path: enabled → cacheable-type → !noCache → !nonDeterministic → cache lookup. If any gate fails, skip cache entirely (no lookup, no put), execute normally. Update the helper to check `stmt instanceof SQLSelectStatement sel && Boolean.TRUE.equals(sel.noCache)`. Both gates fall under this single step because the wiring is one helper-refactor commit.
3. Wire DML invalidation. In `DatabaseSessionEmbedded.executeInternal`, before `statement.execute()`: `if (queryResultCache != null && isBulkBypass(statement)) queryResultCache.invalidateAll();`. The `isBulkBypass(SQLStatement)` helper returns true for the DDL + `SQLTruncateClassStatement` type list (see Context and Orientation). Regular `INSERT`/`UPDATE`/`DELETE` do **not** trigger the wipe; they rely on `addRecordOperation` from Track 4. Tests verify: `CREATE CLASS`, `DROP CLASS`, `CREATE INDEX`, `TRUNCATE CLASS` wipe; plain `INSERT`/`UPDATE`/`DELETE` do not (and per-entry sharp-merge from Track 4 is preserved across them).
4. Per-entry overflow. `CachedEntry.add(Result r)`: if `results.size() >= maxRecordsPerEntry`, set `overflow=true`, return. `CachedResultSetView.next()` checks: if pulling-from-stream and entry overflow, skip the `results.add(r)` step (still return r to consumer). Next `query()` of the overflow entry: in `QueryResultCache.lookup`, check `entry.overflow` and treat as miss (remove + return null). Log a WARN every Nth overflow to avoid spam.
5. R-B: relax K1 RECORD gate for deterministic modifier-chain ORDER BY. In `SharpMergePredicate.classify(SQLSelectStatement)`, the current ORDER BY gate accepts only items whose AST is a plain identifier (no modifier). Extend: for each `SQLOrderBy.items[i]`, accept the item if either (a) it has no modifier (plain identifier — current behavior), or (b) the item's `modifier` chain is non-null and `NonDeterministicQueryDetector.contains(item.modifier)` returns false. The grammar admits only `Identifier [Modifier]`, `Rid`, or `RECORD_ATTRIBUTE` in ORDER BY items, so arithmetic / function-call ORDER BY (`ORDER BY priority * 10`, `ORDER BY lower(name)`) is not reachable at this layer — no extra gate needed for those shapes. `OrderByComparator` from Track 4 delegates ranking to `SQLOrderByItem.compare`, which already calls `modifier.execute(record, value, ctx)`, so no comparator changes are needed; this is purely a classify-gate relaxation. Tests: (i) `SELECT FROM User WHERE active=true ORDER BY name.upper() LIMIT 10` → entry created with RECORD discriminator; mutations re-splice via the modifier-chain comparator. (ii) `SELECT FROM User ORDER BY name.upper() LIMIT 10` where modifier chain contains a non-deterministic function (constructed via a hypothetical `name.sysdate()` modifier if such a chain is parseable; otherwise `ORDER BY $now` at the alias level) → detector flags it, classify returns NONE. (iii) `SELECT FROM User ORDER BY name LIMIT 10` (plain identifier, no modifier) → K1 RECORD path unchanged from v1 baseline; mutation that changes `name` re-splices correctly.

6. Integration tests across the five surfaces. (a) `sysdate()` in WHERE → no entry created; (b) `noCache` hint → no entry; (c) `INSERT INTO User SET …` after a cached SELECT → cache wiped; (d) 201 distinct queries with maxEntries=200 → first one evicted (already covered by Track 3, re-assert here for completeness); (e) query returning 10001 rows with maxRecordsPerEntry=10000 → entry marked overflow, second issue re-executes; (f) `ORDER BY upper(name)` mid-tx mutations → K1 RECORD merge using expression comparator (already covered by step 5, re-assert in this integration suite).

## Concrete Steps

## Episodes

## Validation and Acceptance

- A query containing `sysdate()` or `random()` produces no cache entry — verified by `cache.size()` and by storage hit count on repeat issue.
- A query with `NOCACHE` hint (`SQLSelectStatement.noCache == TRUE`) produces no cache entry and gets a fresh execution on every issue.
- `CREATE CLASS` / `DROP CLASS` / `CREATE INDEX` / `TRUNCATE CLASS` wipe the cache. Plain `INSERT`/`UPDATE`/`DELETE` do **not** trigger a wipe — they flow through `addRecordOperation` per record, and per-entry sharp-merge from Track 4 stays intact across them (verified by asserting a cached `SELECT` hit survives one of these mutations with the expected merged state).
- The cache's entry count never exceeds `maxEntries`; LRU evicts oldest (already enforced by Track 3, re-tested here).
- A query whose result set exceeds `maxRecordsPerEntry` is observed as a miss on the second issue (entry marked overflow + removed at lookup time).
- Invariant I5 — non-deterministic queries never enter the cache and never hit it.
- R-B correctness: `ORDER BY` items whose modifier chain the detector flags as deterministic (e.g., `name.upper()`, `priority.asString()`) classify as K1 RECORD and re-splice correctly on mutation. Plain identifier ORDER BY (no modifier) keeps the v1-baseline behavior. `ORDER BY $now` and similar non-deterministic forms bypass the cache entirely. Arithmetic and function-call ORDER BY (`ORDER BY priority * 10`, `ORDER BY lower(name)`) are not grammar-supported by the current SQL parser and out of scope for v1.

## Idempotence and Recovery

## Artifacts and Notes

## Interfaces and Dependencies

**In scope:**
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/NonDeterministicQueryDetector.java` (NEW).
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/QueryResultCache.java` — `invalidateAll`, overflow lookup gate.
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/CachedEntry.java` — overflow field + bounded `add`.
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java` — DML invalidation site + helper-fence refactor for noCache / non-determinism.
- Tests under `core/src/test/java/com/jetbrains/youtrackdb/internal/core/tx/`.

**Out of scope:**
- Server-side (remote) cache propagation.
- Cache for Gremlin queries.
- `QueryCacheMetrics` and JMH benchmark — Track 6.

**Inter-track dependencies:**
- Depends on Tracks 1, 2, 3, 4 (uses every primitive built so far).
- Track 6 builds on `QueryResultCache` to add counters; otherwise no track depends on this one.

**Library / function signatures introduced:**
- `boolean NonDeterministicQueryDetector.contains(SQLStatement)`.
- `void QueryResultCache.invalidateAll()`.
- `boolean DatabaseSessionEmbedded.isBulkBypass(SQLStatement)` (static helper) — returns true for the DDL statement types and `SQLTruncateClassStatement`, which bypass per-record `addRecordOperation` hooks.
