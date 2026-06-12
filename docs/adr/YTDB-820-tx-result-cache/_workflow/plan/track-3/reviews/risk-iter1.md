<!-- MANIFEST
findings: 7   severity: {blocker: 1, should-fix: 4, suggestion: 2}
index:
  - {id: R1, sev: blocker,    loc: "SQLMatchPathItem.java:21-44; design-mechanics.md:99-102", anchor: "### R1 ", cert: E1, basis: "Edge-label extraction is the sole tombstone trigger for edge mutations; a missed method-name variant or default-E mishandling silently serves stale results under no version backstop"}
  - {id: R2, sev: should-fix, loc: "DatabaseSessionEmbedded.java:1134-1151", anchor: "### R2 ", cert: A1, basis: "effectiveFromClasses / whereClauseOf / orderByOf are SELECT-only; unextended they yield empty set + null for MATCH, so the entry's class filter matches nothing"}
  - {id: R3, sev: should-fix, loc: "QueryResultCache.java:107-144; DatabaseSessionEmbedded.java:1084-1108", anchor: "### R3 ", cert: A2, basis: "Plan step 5 places tombstone in cache.lookup but the delta build (hence TOMBSTONE detection) lives in buildView; lookup returns the entry before any delta runs"}
  - {id: R4, sev: should-fix, loc: "DatabaseSessionEmbedded.java:812-835", anchor: "### R4 ", cert: E2, basis: "TOMBSTONE-miss returns an uncached result with no view; viewOwnsGuard must stay false on that branch or the cache-code depth bump leaks for the rest of the tx"}
  - {id: R5, sev: should-fix, loc: "design-mechanics.md:99-104; track-3.md:17,106", anchor: "### R5 ", cert: A3, basis: "Tombstone-on-CREATE scope inconsistent across docs (any CREATE vs CREATE-of-effectiveFromClasses-class); the implemented predicate must be pinned before the I4 matrix can assert it"}
  - {id: R6, sev: suggestion, loc: "design-mechanics.md:116-138", anchor: "### R6 ", cert: A4, basis: "Edge CREATE also produces UPDATED ops on endpoint vertices (createLink); correctness relies on the step-2 short-circuit firing first, which again depends on R1"}
  - {id: R7, sev: suggestion, loc: "core/src/test/.../cache/AggregateCacheEquivalenceTest.java", anchor: "### R7 ", cert: T1, basis: "I4 MATCH matrix is achievable on the existing equivalence-test harness; main coverage risk is the cross-class-dereference-to-K0_NONE classify branch and the default-E edge case"}
evidence_base: {section: "## Evidence base", certs: 8, matches: 8}
cert_index:
  - {id: E1, verdict: WRONG, anchor: "#### E1 "}
  - {id: E2, verdict: MATCHES, anchor: "#### E2 "}
  - {id: A1, verdict: CONTRADICTED, anchor: "#### A1 "}
  - {id: A2, verdict: CONTRADICTED, anchor: "#### A2 "}
  - {id: A3, verdict: UNVALIDATED, anchor: "#### A3 "}
  - {id: A4, verdict: VALIDATED, anchor: "#### A4 "}
  - {id: T1, verdict: VALIDATED, anchor: "#### T1 "}
  - {id: V1, verdict: VALIDATED, anchor: "#### V1 "}
flags: [CONTRACT_OK]
-->

# Track 3 risk review — iteration 1

Track 3 wires MATCH caching onto the Track 1 RECORD path. Etap A (single-alias) folds to RECORD via a stored `returnProjector`; `MATCH_TUPLE_MULTI` reconciles vertex DELETE and pass→fail UPDATE incrementally and tombstones every case a skip-only delta cannot handle. The whole correctness story rests on the tombstone floor because `MATCH_TUPLE_MULTI` carries no mutation-version backstop. The highest-stakes risk is the edge-class folding into `effectiveFromClasses`: a single missed traversal-method variant or default-`E` mishandling drops the tombstone trigger and serves a stale result with no second line of defence.

## Findings

### R1 [blocker]
**Certificate**: E1 (edge-class fold into `effectiveFromClasses`)
**Location**: Plan of Work step 3 + Context bullet "Traversal edges"; `SQLMatchPathItem.java:21-44`; `design-mechanics.md:99-104`.
**Issue**: The tombstone floor for edge CREATE and edge DELETE fires only when the edge class is in `effectiveFromClasses` (mechanics step 2 class-filters on it before testing `op.type`). The track and design both describe edge-class extraction as reading "the edge class named by a `.out/.in/.both(label)` traversal" — as if the class were directly on the path item. It is not. `SQLMatchPathItem` stores the traversal as a `SQLMethodCall method`: the direction is `method.methodName` and the edge class is the first param, a `SQLBaseExpression` built from the identifier text (`graphPath`, lines 32-44). Three real cases the prose does not name make this extraction error-prone, and any miss is silent:
  - **Method-name variants beyond `out/in/both`.** Edge-binding traversals use `outE/inE/bothE` (and the vertex hops `outV/inV/bothV`); the grammar accepts all. An extractor that matches only `out/in/both` misses `outE('WorksOn')` — which is exactly the bound-edge shape the floor relies on for pass→fail.
  - **Default `E` when unnamed.** `graphPath` substitutes `edgeName = "E"` when the traversal names no label (line 33-36). `out()` therefore folds the base edge class `E`; `getAllSubclasses()` on `E` is every edge class, so every edge mutation tombstones — correct but only if the extractor handles the `null`→`E` default the parser already applied. Reading the param as literal `"E"` works; reading "no param ⇒ no edge class" would drop the trigger.
  - **Multiple edge classes per step** (`out('E1','E2')`) — each param contributes a closure.
The likelihood of getting one of these wrong on first implementation is high, and the failure mode is the worst possible: the edge `RecordOperation` (a CREATED edge entity, confirmed at `DatabaseSessionEmbedded.newEdgeInternal:1519-1520`) is class-filtered out, the tombstone never fires, and — because the same edge CREATE also UPDATEs the endpoint vertices (R6) — the pass→fail / update-into-match branches run on the vertex ops and silently serve a result missing the new tuple. No version gate catches it. This is "the gap the original edge-mutation bug slipped through," restated.
**Proposed fix**: Add an explicit decomposed step (or acceptance line) that pins the edge-class extraction contract: enumerate every traversal method name the extractor recognises (`out/in/both/outE/inE/bothE` at minimum), assert the `null`→`E` default is folded as the base edge class, and cover multi-param steps. Back it with a direct unit test on `effectiveFromClasses` for each method-name variant and the unnamed-`E` case, independent of the end-to-end I4 matrix, so an extraction miss fails loudly at the metadata layer rather than as a wrong query result.

### R2 [should-fix]
**Certificate**: A1 (MATCH metadata helpers are SELECT-only today)
**Location**: Plan of Work steps 2-3; `DatabaseSessionEmbedded.java:1134-1151` (`effectiveFromClasses`, `whereClauseOf`, `orderByOf`).
**Issue**: The three helpers that seed a `CachedEntry`'s class filter, WHERE, and ORDER BY are all `instanceof SQLSelectStatement`-gated and return `Set.of()` / `null` for a MATCH statement. For Etap A this is silently wrong rather than loudly broken: an entry built with `effectiveFromClasses == {}` passes the class filter on nothing, so its RECORD delta reconciles no mutation and the view replays a stale frozen result. The track names `effectiveFromClasses` population in steps 2-3 but does not call out that the existing populate path (`populateAndBuildView:894-902`) feeds these three SELECT-only helpers into the `CachedEntry` constructor — Etap A reuses `populateAndBuildView`, so unless the helpers are extended (or a MATCH-specific populate path is added), the projector composition lands on an entry whose filter is empty.
**Proposed fix**: In the Etap-A step, extend `effectiveFromClasses(statement)`, `whereClauseOf`, and `orderByOf` to handle `SQLMatchStatement` (single-alias: the one alias's class closure, its WHERE, the statement ORDER BY), or route Etap A through a dedicated populate path that sets these explicitly. Add an assertion/test that an Etap-A entry's `effectiveFromClasses` is non-empty.

### R3 [should-fix]
**Certificate**: A2 (tombstone detection site vs plan wording)
**Location**: Plan of Work step 5 ("Tombstone handling in `QueryResultCache.lookup`"); `QueryResultCache.java:107-144`; `DatabaseSessionEmbedded.java:1084-1108` (`buildView`).
**Issue**: The plan places tombstone handling in `QueryResultCache.lookup`, but `lookup` today only does the K0_NONE version gate and returns the entry; the delta build for every shape happens later, in the session's `buildView` (RECORD → `buildForRecord`, aggregate → `buildForAggregate`). `buildForMatchMulti` will likewise run in `buildView`, which is where the TOMBSTONE sentinel is first observed — after `lookup` has already returned the entry and the view is about to be built. So "evict + miss at lookup" cannot be a pure `lookup` concern: `buildView` must detect TOMBSTONE, call back into the cache to remove the entry, and return a fresh uncached execution instead of a view. Wiring this as the plan literally states (inside `lookup`) is impossible without moving the MATCH delta build into `lookup`, which would break the established hit-path contract that `lookup` does no AST work.
**Proposed fix**: Re-word step 5 to locate the tombstone build-and-evict in the MATCH branch of `buildView` (mirroring how RECORD/aggregate build their deltas there), with a new package-visible cache method (e.g. `removeForTombstone(key)`) the session calls on a TOMBSTONE result. Keep `lookup` unchanged. Decomposition should make the call site explicit so the I7 frozen-view obligation (R-note below) is tested at the right layer.

### R4 [should-fix]
**Certificate**: E2 (`viewOwnsGuard` transfer on the MATCH branches)
**Location**: Plan of Work steps 5-6; `DatabaseSessionEmbedded.java:782-835`, `1098-1108`.
**Issue**: The two-guard re-entrancy contract requires that `viewOwnsGuard` transfer to the view only when a `CachedResultSetView` is actually returned; on any branch that returns an uncached result the `finally` must release the depth bump (`serveThroughCache:826-835`). The MATCH path adds two such branches: (a) a HIT whose `buildForMatchMulti` returns TOMBSTONE must return an uncached re-execution with `viewOwnsGuard == false`, and (b) the MATCH MISS populate path returns a view only on success. The aggregate path already models this exactly (`viewOwnsGuard = aggregateResult instanceof CachedResultSetView`, line 809). The risk is that the MATCH HIT path currently assumes `hit != null` ⇒ build a view and set `viewOwnsGuard = true` (lines 786-791); the tombstone outcome makes a HIT able to produce an uncached result, so that unconditional `viewOwnsGuard = true` would leak the guard for the rest of the transaction (every later `query()` bypasses the cache).
**Proposed fix**: In the MATCH HIT branch, gate `viewOwnsGuard` on the actual return being a `CachedResultSetView` (the `instanceof` test the populate paths already use), so a TOMBSTONE-driven uncached re-execution leaves the guard for the `finally` to release. Add a regression test that issues a MATCH that tombstones on the second `query()` and then asserts a third unrelated cacheable query still hits the cache (guard not leaked). The cross-thread release stays on `exitCacheCodeUnchecked` via the view's `releasePin` for the view-built path — no change there.

### R5 [should-fix]
**Certificate**: A3 (tombstone-on-CREATE predicate scope)
**Location**: Track Purpose line 17 ("any CREATE"); Plan of Work step 4 ("CREATE of any class in `effectiveFromClasses`"); `design-mechanics.md:99-104`; `design.md:514`.
**Issue**: The tombstone-on-CREATE trigger is described three different ways. The track Purpose says "any CREATE … tombstone the entry" (unscoped). Plan step 4 and the mechanics pseudocode scope it to "CREATE of any class in `effectiveFromClasses`" (the class filter runs first, line 98). Design.md:514 also scopes it. The scoped form is the implementable and correct one (an unrelated-class CREATE cannot add a tuple), but the unscoped wording in the track Purpose, if taken literally, would tombstone on every transaction-wide CREATE and destroy the cache's value in any write-mixed fragment. The I4 acceptance matrix asserts "edge CREATE / vertex CREATE → tombstone" but does not state the predicate precisely enough for a reviewer to confirm an out-of-pattern CREATE does *not* tombstone.
**Proposed fix**: Reconcile the track Purpose to the scoped predicate ("CREATE of any class in `effectiveFromClasses`") and add an acceptance line asserting that a CREATE of a class outside the pattern's read set does NOT tombstone (the entry still serves incrementally). This is a prose/spec fix, not a code risk, but it removes the ambiguity the implementer would otherwise have to resolve by guessing.

### R6 [suggestion]
**Certificate**: A4 (edge CREATE also updates endpoint vertices)
**Location**: `design-mechanics.md:116-138` (step-3 UPDATED branch); `DatabaseSessionEmbedded.java:1567-1574` (`addEdgeInternal` → `createLink`).
**Issue**: An edge CREATE is not a single op. `addEdgeInternal` creates the edge entity (CREATED on the edge class) *and* calls `createLink` on both endpoint vertices, which mutates their link fields and produces UPDATED ops on the vertex (alias) classes. The floor's correctness for this case depends entirely on the step-2 pre-scan firing the edge-class-CREATE tombstone *before* the step-3 UPDATED branch ever sees the endpoint-vertex UPDATEs (the pre-scan `return TOMBSTONE` short-circuits the whole build). That ordering is correct as designed. The residual risk is purely consequential on R1: if edge-class extraction misses (R1), the edge CREATED op is filtered out, the short-circuit never fires, and the surviving endpoint-vertex UPDATEs are then processed by the pass→fail / update-into-match branches — producing a confidently-wrong incremental result rather than an obvious failure. Worth an explicit test that an edge CREATE between two already-cached vertices tombstones (not "drops/keeps tuples via the vertex UPDATEs").
**Proposed fix**: No code change beyond R1. Add one I4 matrix row asserting that an edge CREATE whose endpoints are both already in cached tuples tombstones the entry, and that the post-tombstone re-execution shows the new tuple — this row is the one that catches an R1 extraction miss end-to-end.

### R7 [suggestion]
**Certificate**: T1 (I4 MATCH matrix testability)
**Location**: Plan of Work step 7; existing `core/src/test/.../cache/AggregateCacheEquivalenceTest.java`, `TxResultCacheInvariantsTest.java`, `ShapeClassifierTest.java`.
**Issue**: The I4 MATCH matrix is achievable — the equivalence-test pattern (run the same query cache-miss then cache-hit-plus-delta and assert against a parallel uncached `query()`) already exists for aggregates and RECORD, and the test base classes cover graph fixtures. Two coverage corners are easy to under-test: (a) the cross-class-dereference WHERE (`where:(assignee.name = ?)`) must be asserted to classify K0_NONE *and* to stay correct when the dereferenced record is mutated — testing only the classification half leaves the version-gate behaviour uncovered; (b) the default-`E` unnamed-traversal edge case from R1 needs its own row or the 85%/70% branch target can be met while the highest-risk branch stays unexercised.
**Proposed fix**: Ensure step 7's matrix includes a classify-assertion plus a mutation-correctness assertion for the cross-class-dereference shape, and a dedicated unnamed-`out()`-traversal edge-mutation row. Both fit the existing harness; no new infrastructure needed.

## Evidence base

#### E1 Exposure: edge-class fold into `effectiveFromClasses` is the sole edge-mutation tombstone trigger
- **Track claim**: ".out/.in/.both(label) steps name edge classes; folding their subclass closure into `effectiveFromClasses` is what lets an edge `RecordOperation` pass the class filter and trip the tombstone instead of being silently skipped."
- **Critical path trace**:
  1. Edge CREATE entry: `DatabaseSessionEmbedded.newEdgeInternal:1519` `new EdgeEntityImpl(...)` then `:1520` `currentTx.addRecordOperation(edge, RecordOperation.CREATED)` — a CREATED op whose record is an `EdgeEntityImpl` (`EdgeEntityImpl extends EntityImpl`, so `instanceof Entity` and `getSchemaClassName()` return the edge class).
  2. Version stamp: `FrontendTransactionImpl.addRecordOperation:619` `txEntry.version = ++mutationVersion`.
  3. Delta build (planned `buildForMatchMulti`, modelled on `DeltaBuilder.buildForRecord:112-130`): class filter `effectiveFromClasses.contains(className)` at `:126`. An op whose class is absent is `continue`d — invisible to the build.
  4. Tombstone pre-scan (`design-mechanics.md:96-104`): the `op.type == CREATED` test runs only after the `cls not in entry.effectiveFromClasses: continue` at line 98.
- **Blast radius**: If the edge class is not in `effectiveFromClasses`, an edge CREATE/DELETE produces no tombstone; the entry keeps serving and (because the endpoint vertices were also mutated, E? / R6) the surviving vertex UPDATE ops are mis-reconciled, yielding a result missing or retaining a tuple. No version backstop for `MATCH_TUPLE_MULTI` (`design.md:545`), so the wrong result is served until tx-end.
- **Existing safeguards**: `SchemaClass.getAllSubclasses()` is the proven closure source (used at `CachedEntry.computeEffectiveFromClasses:147-157`). The class filter itself is proven for RECORD. The MATCH alias-class extraction has an existing analogue (`SQLMatchStatement.addAliases:319-361` populates `aliasClasses` from `matchFilter.getClassName`). What is unproven is the *edge-label* extraction from `SQLMatchPathItem.method` — there is no existing code path that folds traversal-edge classes into a closure.
- **Residual risk**: HIGH — `SQLMatchPathItem` (lines 21-44) requires reading `method.methodName` (variants `out/in/both/outE/inE/bothE/...`) and the first param of `method.getParams()`, with the parser-applied `null`→`"E"` default. A missed variant or default mishandling is silent and unbacked.

#### E2 Exposure: `viewOwnsGuard` transfer + cross-thread `exitCacheCodeUnchecked` on the MATCH branches
- **Track claim**: Compatibility note — "transfer `viewOwnsGuard` when it builds a `CachedResultSetView`"; cross-thread release uses `exitCacheCodeUnchecked`.
- **Critical path trace**:
  1. `serveThroughCache:782` `tx.enterCacheCode()`, `:783` `viewOwnsGuard = false`.
  2. HIT branch `:786-791`: builds a view and sets `viewOwnsGuard = true` unconditionally today.
  3. `finally :832-834`: releases the depth bump only when `!viewOwnsGuard`.
  4. View-held guard released on close/exhaustion via `CachedResultSetView.releasePin:352-363` → `tx.exitCacheCodeUnchecked()` (cross-thread-safe, owning-thread assert dropped for the pool-shutdown path).
- **Blast radius**: A MATCH HIT that tombstones returns an uncached result while `viewOwnsGuard == true` would skip the `finally` release → `cacheCodeDepth` stuck > 0 → every subsequent `query()` in the tx bypasses the cache (silent perf regression, no correctness loss).
- **Existing safeguards**: The aggregate path already gates `viewOwnsGuard` on `instanceof CachedResultSetView` (`:809`) and the RECORD populate path on the same (`:824`); the pattern to copy exists and is correct. The view's `releasePin` already routes through `exitCacheCodeUnchecked`, so the cross-thread half needs no new work.
- **Residual risk**: MEDIUM — purely a wiring discipline carry-over; the established aggregate precedent makes it low-effort to get right, but the MATCH HIT branch is the one place a HIT can now yield a non-view, which the current code does not anticipate.

#### A1 Assumption: the `CachedEntry` metadata helpers already populate MATCH classes/WHERE/ORDER BY
- **Track claim**: Steps 2-3 "set `whereClause`, `orderBy`, `effectiveFromClasses`"; Etap A "reuse Track 1's `buildForRecord`".
- **Evidence search**: Read `DatabaseSessionEmbedded.effectiveFromClasses/whereClauseOf/orderByOf` (1134-1151) and `populateAndBuildView` (863-906); grep for the helpers' call sites.
- **Code evidence**: All three helpers are `instanceof SQLSelectStatement`-gated and fall through to `Set.of()` / `null` for any non-SELECT (`:1135,1142,1146,1150`). `populateAndBuildView:896-898` feeds exactly these into `new CachedEntry(...)`.
- **Verdict**: CONTRADICTED.
- **Detail**: Etap A as written reuses `populateAndBuildView`, which would build a MATCH entry with empty `effectiveFromClasses` and null WHERE/ORDER BY — the class filter matches nothing, so the delta reconciles nothing. The helpers must be extended for MATCH or a MATCH populate path must set them explicitly.

#### A2 Assumption: tombstone eviction belongs in `QueryResultCache.lookup`
- **Track claim**: Plan step 5 — "Tombstone handling in `QueryResultCache.lookup`. For a `MATCH_TUPLE_MULTI` entry, invoke `buildForMatchMulti`; on TOMBSTONE remove the entry and return MISS."
- **Evidence search**: Read `QueryResultCache.lookup` (107-144) and `DatabaseSessionEmbedded.buildView` (1084-1108).
- **Code evidence**: `lookup` does only the K0_NONE version gate and returns the entry; no delta builder is invoked there. RECORD/aggregate deltas are built in `buildView` (`:1101,1106`). `lookup` deliberately does no AST work on the hit path (`serveThroughCache:787-788` documents this).
- **Verdict**: CONTRADICTED (location), not the mechanism.
- **Detail**: `buildForMatchMulti` (an AST/WHERE-re-eval walk) cannot run inside `lookup` without breaking that contract. The detection and evict belong in `buildView`'s MATCH branch with a cache callback; the plan's literal placement is infeasible as stated.

#### A3 Assumption: the tombstone-on-CREATE predicate is consistently specified
- **Track claim**: Purpose "any CREATE … tombstone"; step 4 "CREATE of any class in `effectiveFromClasses`".
- **Evidence search**: grep tombstone/CREATE across track-3.md, design.md, design-mechanics.md.
- **Code evidence**: `design-mechanics.md:98-101` scopes the trigger (class filter precedes the `op.type == CREATED` test); `design.md:514` scopes it; track Purpose line 17 leaves it unscoped.
- **Verdict**: UNVALIDATED (spec ambiguity, not yet code).
- **Detail**: The scoped form is correct; the unscoped Purpose wording is a latent mis-spec that would gut cache value if implemented literally. Reconcile before decomposition.

#### A4 Assumption: an edge CREATE produces only the edge-class op the floor keys on
- **Track claim**: Implicit in "edge CREATE (tombstone)" — that the floor sees an edge-class CREATED op.
- **Evidence search**: Read `addEdgeInternal` (1527-1579), `newEdgeInternal` (1506-1525).
- **Code evidence**: `:1565` creates the edge entity (CREATED op via `newEdgeInternal:1520`); `:1571,1574` `createLink` mutates both endpoint vertices' link fields → UPDATED ops on the vertex classes. Comment `:1563-1564` "All edges are record-based" confirms no lightweight-edge path that would skip the edge record.
- **Verdict**: VALIDATED (with a caveat).
- **Detail**: The edge CREATE does emit an edge-class CREATED op (good for the floor), but also two vertex UPDATED ops. Correctness depends on the step-2 short-circuit firing on the edge CREATE before step-3 sees the vertex UPDATEs — sound as designed, but only if E1's extraction folds the edge class in. Reinforces R1/R6.

#### T1 Testability: I4 MATCH equivalence matrix
- **Coverage target**: 85% line / 70% branch.
- **Difficulty assessment**: The matrix needs a graph fixture (vertices + edges across classes), parallel cached/uncached `query()` comparison, and assertions across CREATE/DELETE/UPDATE on both vertices and edges plus update-into-match and cross-class-dereference. The tombstone branches and the `null`→`E` default branch are the hard-to-reach ones.
- **Existing test infrastructure**: `AggregateCacheEquivalenceTest`, `TxResultCacheInvariantsTest`, `ShapeClassifierTest`, `CachedResultSetViewTest`, `QueryResultCacheTest` (all under `core/src/test/.../sql/executor/cache/`) establish the cache-on/parallel-uncached comparison pattern and graph-fixture setup.
- **Feasibility**: ACHIEVABLE.
- **Detail**: The only coverage gaps likely to slip are the cross-class-dereference classify-plus-mutation pair (R7a) and the unnamed-`out()` edge-mutation row (R7b); both fit the existing harness.

#### V1 Assumption (prior-episode): cross-thread release model carries from Track 1 unchanged
- **Track claim**: prior_episodes — "cross-thread release uses `exitCacheCodeUnchecked`"; "`recordPulledRow` cap does not apply (it targets `AggregateState`)".
- **Evidence search**: Read `CachedResultSetView.releasePin` (352-363) and `recordPulledRow` (`CachedEntry:290-309`).
- **Code evidence**: `releasePin:361` calls `tx.exitCacheCodeUnchecked()` for every view shape (RECORD/K0/aggregate); the MATCH view will reuse the same `CachedResultSetView` lifecycle. `recordPulledRow` enforces the per-entry cap on `results` for the lazy-pull shapes; the MATCH view pulls through the same path, so the cap still applies to the cached tuple list (the prior-episode note that "the cap targets `AggregateState`" refers to the aggregate-specific cap, not the record-list cap — both coexist).
- **Verdict**: VALIDATED.
- **Detail**: No new concurrency risk from the guard/release model; the MATCH view inherits Track 1's `releasePin`/`exitCacheCodeUnchecked` mechanics intact. The per-tuple stream-pull path (step 6) must still route appends through `recordPulledRow` so the record-list cap and `reverseIndex`/`contributingRids` extension stay consistent — a step-6 implementation note, not a risk-level finding.
