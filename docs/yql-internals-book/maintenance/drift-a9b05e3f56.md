# Drift Report — Refresh Cycle 3

*Realignment of* Inside the YouTrackDB Query Engine *against a newer source tree.*
Produced by the drift-aware refresh procedure in
[`../../../yql-internals-book-builder/MAINTENANCE_PROMPT.md`](../../../yql-internals-book-builder/MAINTENANCE_PROMPT.md).
This is the first drift report for the book; it doubles as the template for later cycles.

**Status: cycle 3 COMPLETE.** Every known and sweep-discovered semantic drift is resolved; the
line-number sweep is finished across the sweep chapters (3–13, 15) and chapter 16; the pre-filter /
tx-cache review chapters (14, 16, 17) and the new Chapter 7 §7.9 are landed; the source-tree
baseline in `docs/yql-internals-book/README.md` is bumped to `a9b05e3f56` with a Cycle 3
refresh-history row; and this drift report is in place. The only outstanding item is a note for a
*future* MAINTENANCE_PROMPT.md fix (see Open items), which does not affect the book itself.

## Baseline window

| Field | Old baseline (`BOOK_SHA`) | New baseline (`NEW_SHA`) |
|---|---|---|
| Commit SHA | `cca739f215debc26bb82422ed9aaff3566d2e590` | `a9b05e3f56128de5dbf314c40d5be38ff10b5050` |
| Short SHA | `cca739f215` | `a9b05e3f56` |
| Date | 2026-04-22 | 2026-07-21 |
| Subject | `YTDB-650: Back-reference hash join for MATCH patterns (#946)` | `Bump anthropics/claude-code-base-action … (#1235)` |
| Branch | `develop` | `develop` (HEAD) |

**Drift window:** 13 commits · 62 files changed · **+9211 / −1398** lines.

Scanned paths (the four the maintenance prompt tracks):

- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/`
- `core/src/main/grammar/YouTrackDBSql.jjt` — **unchanged in this window** (absent from the diff).
- `core/src/main/java/com/jetbrains/youtrackdb/api/config/GlobalConfiguration.java`
  *(note: the file lives under `api/config/`, not the `internal/core/config/` path the maintenance prompt's snippet assumes — use the `api/config/` path in future cycles).*
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/`

All counts above were re-verified with `git log`/`git diff --stat` over the range at report time.

---

## Phase 0 — The drift window

### Commit summary (13 commits, newest first)

| Short SHA | Subject |
|---|---|
| `d96c81eb3a` | YTDB-660: Predicate Push-Down Past Per-Record LET Subqueries (#977) |
| `a8b9a5e688` | YTDB-1197: Fix ORDER BY null placement for index scans (#1218) |
| `8950fe0282` | [YTDB-1178] Fix optimistic-read mixed-state race in storage page reads (#1222) |
| `6054b8efa5` | YTDB-1167: rid equality fetch for select (#1208) |
| `4dabb80800` | YTDB-1176: Short-circuit contradictory WHERE equalities to EmptyStep (#1190) |
| `14bb3e40d9` | YTDB-629: match rid from where extract (#1124) |
| `87cbfc0b6d` | [YTDB-915] S0 — Analyzed-expression substrate (#1174) |
| `c47e62b866` | YTDB-820: tx result cache (#1077) |
| `c716a2d400` | YTDB-651: Prefilter selectivity fix (#973) |
| `f684630464` | [YTDB-958] Eliminate index entries-count divergence on rollback (#1088) |
| `47f6ddfea8` | YTDB-953: Recalibrate single-value null count on load (#1085) |
| `cefda907e2` | YTDB-669: Eliminate read-cache allocator/reader race (#1035) |
| `baa4ba8ff8` | [no-test-number-check] Raise core unit test coverage to 81.4% line / 71.1% branch (#1056) |

The commits with the largest book impact are `c716a2d400` (pre-filter selectivity rewrite),
`c47e62b866` (transactional result cache — a whole new subsystem), `14bb3e40d9` / `6054b8efa5`
(RID-from-WHERE extraction and RID-equality fetch, which reshape root selection), and
`87cbfc0b6d` (the analyzed-expression substrate).

### Changed files grouped by subsystem

Churn is shown as `+added/−removed`. `A` = added file, `D` = deleted file, otherwise modified.

**Parser & AST** (`internal/core/sql/parser/`)

| File | Churn | Note |
|---|---|---|
| `SQLWhereClause.java` | +355/−46 | RID-from-WHERE extraction |
| `SQLMathExpression.java` | +74/−237 | rewritten (arithmetic folding) |
| `AnalyzedAstAccess.java` | +79 `A` | analyzed-expression substrate (S0) |
| `SQLExpression.java` | +32 | |
| `LocalResultSet.java` | +32 | |
| `SQLRid.java` | +24 | RID handling for pinned-RID lists |
| `SQLBooleanExpression.java` | +12 | |
| `SQLIdentifier.java` | +10 | |
| `SQLSelectStatement.java` | +4 | |
| `SQLBinaryCondition.java` | +45/−2 | contradictory-equality short-circuit |
| `SQLBinaryCompareOperator.java` | +2/−1 | |

**Planner — MATCH** (`internal/core/sql/executor/match/`)

| File | Churn | Note |
|---|---|---|
| `MatchExecutionPlanner.java` | +539/−122 | root-selection RID-pin list refactor; `optimizeScheduleWithIntersections` now returns `boolean`; pre-filter two-path scheduling |
| `PreFilterSkipReason.java` | +69 `A` | new enum: why a pre-filter was not attached |

**Planner — SELECT** (`internal/core/sql/executor/`)

| File | Churn | Note |
|---|---|---|
| `SelectExecutionPlanner.java` | +576/−21 | RID-equality fetch, predicate push-down past LET, ORDER-BY-null |

**Executor & steps** (`internal/core/sql/executor/`, `.../match/`, `.../resultset/`)

| File | Churn | Note |
|---|---|---|
| `EdgeTraversal.java` | +988/−26 | pre-filter descriptor plumbing (highest raw churn in the window) |
| `BackRefHashJoinStep.java` | +183/−7 | hash-join guard reshaped |
| `FetchFromIndexStep.java` | +116/−188 | rewrite |
| `MatchStep.java` | +98/−2 | |
| `MatchEdgeTraverser.java` | +45/−10 | |
| `ExecutionStream.java` | +39/−29 | |
| `IdempotentExecutionStream.java` | +64 `A` | new idempotent stream wrapper |
| `FetchFromRidsStep.java` | +23/−2 | |
| `IteratorExecutionStream.java` | +24 | |
| `LoaderExecutionStream.java` | +22 | |
| `OptionalMatchStep.java` | +1 | trivial |

**Query result cache — NEW subsystem** (`internal/core/sql/executor/cache/`, all `A`)

| File | Churn |
|---|---|
| `ShapeClassifier.java` | +1022 |
| `CachedResultSetView.java` | +615 |
| `AggregateState.java` | +604 |
| `CachedEntry.java` | +510 |
| `QueryResultCache.java` | +428 |
| `DeltaBuilder.java` | +326 |
| `QueryCacheMetrics.java` | +161 |
| `NonDeterministicQueryDetector.java` | +144 |
| `CacheKey.java` | +116 |
| `TxDeltaCursor.java` | +106 |
| `AggregateCacheTapStep.java` | +99 |
| `CacheableShape.java` | +94 |

12 files, **≈ +4225 lines** — the single largest code addition in the window.

**Cost model** — no dedicated cost/estimator class exists as a discrete file; the cost logic
that the book documents lives embedded in `MatchExecutionPlanner.java`,
`RidFilterDescriptor.java`, and `IndexHistogramManager.java` (all listed under their home
subsystems above). Recorded here so future cycles do not go looking for a `CostModel.java`.

**Configuration** (`api/config/`)

| File | Churn | Note |
|---|---|---|
| `GlobalConfiguration.java` | +113/−6 | pre-filter knob split + 5 new tx-result-cache knobs (see Phase 1 knob scan) |

**Index engine** (`internal/core/index/engine/`)

| File | Churn |
|---|---|
| `BTreeSingleValueIndexEngine.java` | +221/−45 |
| `BTreeMultiValueIndexEngine.java` | +206/−33 |
| `IndexCountDelta.java` | +132 `A` |
| `IndexHistogramManager.java` | +111/−29 |
| `IndexCountDeltaHolder.java` | +65 `A` |
| `HistogramDeltaHolder.java` | +34 |

**Other SQL infrastructure & utilities** (`internal/core/sql/`)

| File | Churn | Note |
|---|---|---|
| `NumericOps.java` | +340 `A` | numeric helpers for the analyzed substrate |
| `DynamicSQLElementFactory.java` | +5/−32 | |
| `CommandExecutorSQLAbstract.java` | +4/−13 | |
| `SQLScriptEngine.java` | +10/−25 | |
| `SQLFunctionAverage.java` | +1/−2 | |
| `QueryOperatorTraverse.java` | +5/−6 | |
| `QueryOperatorContainsValue.java` | +4/−6 | |

**Deleted — the legacy command-executor / return-handler framework** (all `D`)

| File | Removed |
|---|---|
| `SQLEngine.java` (gutted, still present) | −89 |
| `RecordsReturnHandler.java` | −83 |
| `DefaultCommandExecutorSQLFactory.java` | −66 |
| `RecordCountHandler.java` | −50 |
| `UpdatedRecordsReturnHandler.java` | −48 |
| `OriginalRecordsReturnHandler.java` | −48 |
| `CommandExecutorSQLFactory.java` | −43 |
| `ReturnHandler.java` | −42 |

`SQLEngine.java` is anomalous: 89 deletions, 0 additions, but the file still exists (heavy
gutting, not a git-tracked delete). None of these classes is cited by the book — confirmed in
the verification-aids section below.

---

## Phase 1 — Impact on the book

### Impact table (changed *and* cited files)

Risk is **structural** (renamed class / removed method / new phase), **numeric** (line numbers
moved, claims unchanged), or **semantic** (same surface, different behaviour). Structural and
semantic entries need author attention; numeric-only entries are a citation sweep.

| Changed file | Chapters citing it | Risk | Reason |
|---|---|---|---|
| `MatchExecutionPlanner.java` | 04, 06–17 | semantic | Root-selection RID-pin refactored single→list; `optimizeScheduleWithIntersections` now returns `boolean`; pre-filter two-path scheduling. Line numbers also shifted throughout. |
| `RidFilterDescriptor.java` | 07, 12, 14, 17 | structural | Sealed-interface contract split into two variants (`EdgeRidLookup`, `IndexLookup`) — the Chapter 14 epicentre. |
| `GlobalConfiguration.java` | 07, 08, 10, 13, 14, 17 | structural + numeric | Pre-filter knob removed and split into two; new heap-adaptive `MAX_RIDSET_SIZE`; five new `QUERY_TX_RESULT_CACHE_*` knobs. Rebuilds Chapter 17 Tables 17.1/17.2. |
| `EdgeTraversal.java` | 06, 07, 09, 10, 12, 13, 14, 17 | semantic + numeric | Pre-filter descriptor plumbing added; large churn shifts every cited line. |
| `PreFilterSkipReason.java` (new) | 14, 16, 17 | structural | New enum surfacing why a pre-filter was skipped; now referenced by the rewritten pathology material. |
| `TraversalPreFilterHelper.java` | 14, 16, 17 | semantic | Pre-filter admission logic changed alongside the descriptor split. |
| `IndexSearchDescriptor.java` | 14 | semantic | Feeds the `IndexLookup` pre-filter path. |
| `SelectExecutionPlanner.java` | 07, 14 | numeric | Select-side rewrites (RID-equality fetch, predicate push-down, ORDER-BY-null); book cites it only lightly. |
| `SQLWhereClause.java` | 03, 04, 05, 09, 12, 15 | numeric | RID-from-WHERE extraction; cited line numbers shifted. |
| `BackRefHashJoinStep.java` | 10, 14, 16, 17 | numeric | Hash-join guard constant lines moved. |
| `MatchStep.java` | 05, 07, 10, 11, 12, 13, 15, 16, 17 | numeric | Line numbers shifted. |
| `MatchEdgeTraverser.java` | 10, 11, 12, 14, 16, 17 | numeric | Line numbers shifted. |
| `OptionalMatchStep.java` | 07, 10, 11, 12, 15, 16, 17 | numeric | One-line addition; citations essentially stable. |
| `SQLRid.java` | 04, 09 | semantic | RID handling ties into the pinned-RID-list refactor in Chapter 9. |
| `SQLExpression.java` | 04, 05, 14 | numeric | Line numbers shifted. |
| `SQLBinaryCondition.java` | 03 | numeric | Contradictory-equality short-circuit added; cited line shifted. |
| `SQLIdentifier.java` | 04 | numeric | Line numbers shifted. |
| `SQLSelectStatement.java` | 03 | numeric | Line numbers shifted. |
| `ExecutionStream.java` | 03, 11, 12, 17 | numeric | Line numbers shifted. |

**Changed but *not* cited** (no book action): the entire `sql/executor/cache/` subsystem (12
files — see the deferred-content note), `FetchFromIndexStep.java`, `FetchFromRidsStep.java`,
`SQLMathExpression.java`, `NumericOps.java`, `AnalyzedAstAccess.java`,
`IdempotentExecutionStream.java`, `IteratorExecutionStream.java`, `LoaderExecutionStream.java`,
the index-engine files (`BTree*IndexEngine`, `IndexCountDelta*`, `IndexHistogramManager`,
`HistogramDeltaHolder`), the misc SQL-infra files, and all eight deleted command-executor files.

### Chapter 17 Tables 17.1 / 17.2 scan

The configuration-knob table is where `GlobalConfiguration.java` bites hardest. Findings against
the new tree (`api/config/GlobalConfiguration.java`):

- **Removed knob:** `QUERY_PREFILTER_MAX_SELECTIVITY_RATIO` **no longer exists.** Any book
  reference must be retired.
- **Split into two** distinct admission bounds:
  - `QUERY_PREFILTER_EDGE_LOOKUP_MAX_RATIO` (`:1362`, key `youtrackdb.query.prefilter.edgeLookupMaxRatio`, default **`0.8`**) — reverse-edge `EdgeRidLookup` path.
  - `QUERY_PREFILTER_INDEX_LOOKUP_MAX_SELECTIVITY` (`:1370`, key `youtrackdb.query.prefilter.indexLookupMaxSelectivity`, default **`0.95`**) — `IndexLookup` path.
- **New heap-adaptive default:** `QUERY_PREFILTER_MAX_RIDSET_SIZE` (`:1351`) default is now
  `(int) Math.min(10_000_000L, Math.max(100_000L, Runtime.getRuntime().maxMemory() / 200))`
  (≈ 0.5 % of max heap, clamped to `[100000, 10000000]`).
- **Unchanged pre-filter knobs** still present: `QUERY_PREFILTER_MIN_LINKBAG_SIZE` (`:1379`,
  default `50`), `QUERY_PREFILTER_LOAD_TO_SCAN_RATIO` (`:1387`, default `100.0`).
- **Five new tx-result-cache knobs** (all under `youtrackdb.query.txResultCache.*`):

  | Constant | Line | Default |
  |---|---|---|
  | `QUERY_TX_RESULT_CACHE_ENABLED` | `:961` | `false` |
  | `QUERY_TX_RESULT_CACHE_MAX_ENTRIES` | `:971` | `200` |
  | `QUERY_TX_RESULT_CACHE_MAX_RECORDS_PER_ENTRY` | `:980` | `10000` |
  | `QUERY_TX_RESULT_CACHE_K0_NONE_INVALIDATION_THRESHOLD` | `:991` | `3` |
  | `QUERY_TX_RESULT_CACHE_MULTI_INVALIDATION_THRESHOLD` | `:1001` | `3` |

  Two of these carry a property key that diverges from the constant name
  (`deltaUnreconcilableInvalidationThreshold`, `matchMultiInvalidationThreshold`) — flagged in the
  table so a reader searching by property name can still find them.
- `STATEMENT_CACHE_SIZE` (`:1011`, key `youtrackdb.statement.cacheSize`, default `100`) is
  unchanged.

---

## Phase 2 — Triage

| Class | Chapters | Action |
|---|---|---|
| **Clean** (nothing to do) | 01, 02 | Cite no code touched in the window. Skipped. |
| **Sweep** (line numbers only) | 03, 04, 05, 06, 07, 08, 09, 10, 11, 12, 13, 15 | Re-verify each `file:line` citation and update where it shifted; no factual claims change. |
| **Review** (author re-read) | 14, 16, 17 | Pre-filter / selectivity rewrite epicentre — semantic changes, not just numbers. |
| **New content** (surgical) | 07 §7.9 | `QueryResultCache` — a genuinely new mechanism, added as a bounded subsection rather than a full chapter (see deferred note). |

Chapters 08 and 13 appear in both the impact table (they cite `GlobalConfiguration.java` /
`EdgeTraversal.java`) and the sweep list: their citations moved but the *claims* they make are
unaffected by the behavioural changes, so a line-number sweep is sufficient.

---

## Refresh actions taken (cycle 3)

1. **Chapter 14 — pre-filter two-path rewrite.** Rebuilt the `RidFilterDescriptor` section around
   the sealed-interface split: `EdgeRidLookup` (back-reference intersection) and `IndexLookup`
   (indexable field conditions), each with its own admission bound
   (`QUERY_PREFILTER_EDGE_LOOKUP_MAX_RATIO` vs `QUERY_PREFILTER_INDEX_LOOKUP_MAX_SELECTIVITY`).
   Documented that `optimizeScheduleWithIntersections` now returns a `boolean` `hasIndexLookup`
   used to short-circuit the forecast pass.
2. **Chapter 16 — pathology alignment.** Realigned the "missing pre-filter" pathology and the
   EXPLAIN discussion with the two-path model and the new `PreFilterSkipReason` enum.
3. **Chapter 17 — table rebuild.** Rebuilt Tables 17.1/17.2: retired
   `QUERY_PREFILTER_MAX_SELECTIVITY_RATIO`, added the two split knobs, updated
   `QUERY_PREFILTER_MAX_RIDSET_SIZE` to the heap-adaptive default, and added the five
   `QUERY_TX_RESULT_CACHE_*` rows with their (diverging) property keys.
4. **Chapter 7 §7.9 — tx-result-cache subsection.** Added surgical coverage of the
   per-transaction `QueryResultCache`: the master switch (`QUERY_TX_RESULT_CACHE_ENABLED`, off by
   default), the "enabling it never changes result cardinality" invariant, per-entry / per-map
   caps, the overflow-to-non-cacheable behaviour, and the two strike-limit thresholds.
5. **Chapter 4 — verified, no change.** Confirmed the analyzed-expression substrate
   (`AnalyzedAstAccess.java`, `NumericOps.java`, YTDB-915 "S0") is a separate, not-yet-wired IR
   layer: it does not change the parse→AST story Chapter 4 tells, so the chapter was left as-is.
6. **Full line-number sweep** across chapters 03–13, 15, 16, 17 — every `file:line` citation to a
   changed file re-verified against the new tree and updated where it had shifted.

### Semantic drift discovered *during* the sweep (beyond the known set)

- **(a) Chapter 9 §9.1 — root-entry RID pin, single → list.** The planner's per-alias pinned-RID
  constraint is no longer a single RID but a list: `Map<String, List<SQLRid>> aliasPinnedRids`
  (declared at `MatchExecutionPlanner.java:315`; consumed by
  `estimateRootEntries(...)` at `MatchExecutionPlanner.java:5192`). Root-entry estimation for a
  pinned alias is now "estimate = number of pinned RIDs" (the list size), not a fixed 1. Chapter 9
  and its worked rules were corrected to match.
- **(b) Chapter 5 — citation-format normalisation. DONE.** One legacy GitHub-permalink citation
  (the `$matches`-as-legacy-alias-for-`$patterns` recognition in `SQLMatchStatement.java`) was
  normalised to the book's inline `path:line` convention. `05-meet-match.md` no longer contains any
  `github.com` permalink; the citation now reads
  `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLMatchStatement.java:289`.
  *Note:* `SQLMatchStatement.java` was **not** touched in this drift window — this was a
  formatting-hygiene fix, not a code-driven change.
- **(c) Chapter 15 §15.7 — root-candidate exclusion mis-citation. CORRECTED.** The walkthrough
  attributed the root/start-candidate exclusion of alias `fof` to `MatchExecutionPlanner.java:513`,
  which is actually the **WHILE-alias inflation** loop: it raises `inferredWhileExprAliases`
  estimates to `Long.MAX_VALUE` so a low-cardinality inferred class never outcompetes a declared
  root — it does not exclude `fof`. Corrected to the two real mechanisms:
  - **Root/start exclusion** is enforced by the dependency graph. `getDependencies(...)`
    (`MatchExecutionPlanner.java:4382`) builds the alias→dependencies map, and the start-node
    dependency-satisfied check inside `getTopologicalSortedSchedule(...)`
    (`MatchExecutionPlanner.java:~2013`) refuses to start at a node whose dependencies are not yet
    satisfied.
  - **Prefetch exclusion** is separate, driven by `dependsOnExecutionContext(...)`
    (`MatchExecutionPlanner.java:651`) applied at the prefetch-candidate filter
    (`MatchExecutionPlanner.java:521`).

  Also corrected: `filterDependsOnContext(...)` (`MatchExecutionPlanner.java:773`) keys on
  `$matched.` and `$parent` (via `refersToParent()`), **not** `$currentMatch`.

### Deferred / new content required (out of refresh scope)

- **The `sql/executor/cache/` subsystem is large and only partly covered.** Twelve new files,
  ≈ 4225 lines (`ShapeClassifier` +1022, `CachedResultSetView` +615, `AggregateState` +604,
  `CachedEntry` +510, `DeltaBuilder` +326, plus metrics/keys/cursors). Only the top-level
  `QueryResultCache` lifecycle got surgical coverage in Chapter 7 §7.9. Deeper coverage — shape
  classification, cached-view iteration semantics, aggregate cache state, and delta reconciliation
  internals — is **deferred to a future cycle**. Per maintenance rule 5, a genuinely new subsystem
  of this size is new-content work, not a refresh; logged here for the user to decide whether to
  open a cycle-1/cycle-2-style authoring pass.
- **The deleted command-executor framework is confirmed uncited.** The seven deleted files
  (`CommandExecutorSQLFactory`, `DefaultCommandExecutorSQLFactory`, `ReturnHandler`,
  `RecordsReturnHandler`, `OriginalRecordsReturnHandler`, `UpdatedRecordsReturnHandler`,
  `RecordCountHandler`) plus the gutted `SQLEngine.java` are **not referenced by any chapter** — no
  removal/rewrite of book prose is required.

---

## Verification aids

**Deleted-class citation grep** — `grep -rn <class> docs/yql-internals-book/chapters/*.md` for
each removed/gutted class:

| Class | Cited by any chapter? |
|---|---|
| `CommandExecutorSQLFactory` | no |
| `DefaultCommandExecutorSQLFactory` | no |
| `ReturnHandler` | no |
| `RecordCountHandler` | no |
| `SQLEngine` | no |

Result: **none cited** — matches expectation. Removing these classes from the source has no book
consequence.

**Numeric re-verification** — commit count (13), file count (62), and line totals (+9211/−1398)
were re-derived from `git log`/`git diff --stat` over `BOOK_SHA..NEW_SHA` at report time and match
the header. `YouTrackDBSql.jjt` produced no diff and was confirmed unchanged.

---

## Open items for the maintainer

1. **Chapter 5 permalink — DONE.** `05-meet-match.md` was normalised: the `$matches` citation no
   longer uses a `github.com` permalink and now reads the inline
   `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLMatchStatement.java:289`
   `path:line` form. Nothing outstanding.
2. **Baseline table + refresh history — DONE.** `docs/yql-internals-book/README.md`'s Source-tree
   baseline table now shows `a9b05e3f56128de5dbf314c40d5be38ff10b5050` (2026-07-21, `develop`), and
   a `### Refresh history` subsection carries the Cycle 3 entry pointing at this report. Phase 5 is
   complete.
3. **Path correction for a future prompt fix (only remaining item):** `GlobalConfiguration.java`
   lives under `api/config/`, not the `internal/core/config/` path the maintenance prompt's Phase-0
   command snippet uses. Update `MAINTENANCE_PROMPT.md`'s snippet (or supply the corrected path) on
   the next prompt revision — this does not affect the book itself.
