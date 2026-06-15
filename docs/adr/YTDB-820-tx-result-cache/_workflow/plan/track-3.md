<!-- workflow-sha: e9377f7f133f5cd6ec3028936f28be2819e4ae96 -->
# Track 3: MATCH shapes — Etap A composition, partial Etap B, tombstone floor

## Purpose / Big Picture
After this track lands, MATCH queries cache: single-alias MATCH replays like a
RECORD query, and multi-alias / pattern-with-edges MATCH reconciles vertex
DELETE and pass→fail UPDATE incrementally while tombstoning the cases a skip-only
delta cannot handle — always matching fresh execution.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

This track adds MATCH caching. Etap A (single-alias) folds to RECORD shape via a
stored `returnProjector` that wraps a record into the single-binding tuple the
executor produces, reusing Track 1's record delta path. `MATCH_TUPLE_MULTI`
carries per-tuple bookkeeping (`aliasClasses`, `traversalEdgeClasses`,
`contributingRids`, `reverseIndex`) and a two-pass `buildForMatchMulti` with a
tombstone floor: a CREATE of a class in `effectiveFromClasses`, any edge-class
DELETE, and any update-into-match tombstone the entry; vertex DELETE and pass→fail
UPDATE drop incrementally. (A CREATE of a class outside the pattern's read set does
not tombstone — it cannot add a tuple.) Correctness rests entirely on this
delta-build because `MATCH_TUPLE_MULTI` has no version backstop.

## Progress
- [x] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

- [x] 2026-06-12T08:56Z [ctx=info] Review + decomposition complete

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->

## Decision Log
<!-- Continuous-log. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. -->

- [x] Technical: PASS at iteration 2 (6 findings, 6 accepted). Classify-gate completeness (T1), RETURN-mode gate (T2), `DatabaseSessionEmbedded` in-scope (T3), tombstone-build-in-`buildView` (T5) were should-fix; reuse-extraction (T4) and pass→fail null-guard (T6) suggestions — all folded into Plan of Work / Interfaces / Validation.
- [x] Risk: PASS at iteration 2 (7 findings, 7 accepted). One blocker (R1 edge-class extraction contract — the floor's whole edge-mutation story); should-fix R2-R5 (SELECT-only metadata helpers, tombstone-build site, `viewOwnsGuard` leak, CREATE-predicate scope); suggestions R6-R7 (endpoint-vertex co-UPDATE ordering, I4 corners).
- [x] Adversarial: PASS at iteration 2 (7 findings, 7 accepted). Two blockers (A1 SKIP/LIMIT/GROUP BY/RETURN DISTINCT gate gap; A2 edge-op-existence — resolved by confirming all edges are record-based, no lightweight mode); should-fix A3-A6 (link-deref detection mechanism, parameterized-label hazard, update-into-match definition, multi-class closure builder); suggestion A7 (tombstone-eviction pinned-entry discipline / I7).

## Context and Orientation

Track 1 shipped the RECORD delta path and `CachedResultSetView`; Etap A composes
directly onto it. `MATCH_TUPLE_MULTI` needs its own delta type and a tombstone
hook in the cache lookup.

- **`SQLMatchStatement`** (`internal/core/sql/parser/`) — the MATCH AST. Its
  grammar (`YouTrackDBSql.jjt:1245`) does not accept `NOCACHE` (asymmetry with
  SELECT, preserved deliberately; v2 candidate). `SQLMatchStatement.equals()`
  covers statement-level SKIP natively, so the cache key needs no special MATCH
  handling.
- **`SQLWhereClause.matchesFilters(Identifiable, CommandContext)`** — reused to re-evaluate each
  alias's pattern WHERE against a mutated record at delta-build.
- **`SchemaClass.getAllSubclasses()`** — the subclass-closure source for
  `aliasClasses` and `traversalEdgeClasses` (D11 symmetry with RECORD's
  `effectiveFromClasses`).
- **Traversal edges.** `.out/.in/.both(label)` steps name edge classes; folding
  their subclass closure into `effectiveFromClasses` is what lets an edge
  `RecordOperation` pass the class filter and trip the tombstone instead of being
  silently skipped — the gap the original edge-mutation bug slipped through. The
  edge class is the first param of a `SQLMethodCall` on `SQLMatchPathItem`
  (`SQLMatchPathItem.java:21-44`), not a bare field: the extractor must recognize
  every edge-binding method name (`out/in/both/outE/inE/bothE`), fold the parser's
  `null`→`"E"` default for a bare `out()` (base class `E`, whose closure is every
  edge class), handle multi-param steps (`out('E1','E2')`), and route a
  non-statically-resolvable label to K0_NONE. All edges are record-based in this
  engine (`DatabaseSessionEmbedded.addEdgeInternal:1563-1564`,
  `newEdgeInternal:1520`), so every edge CREATE/DELETE emits an edge-class
  `RecordOperation` — there is no lightweight-edge mode that skips the edge record,
  so the edge-closure route is sufficient (no endpoint-vertex-closure fallback
  needed). An edge CREATE also UPDATEs both endpoint vertices via `createLink`
  (lines 1571-1574); the pass-1 tombstone short-circuit must fire before pass 2
  processes those UPDATEs.

Non-obvious terminology: *Etap A* (single-alias MATCH → RECORD composition),
*Etap B* (multi-alias; only the partial floor ships in v1), *tombstone* (mark the
entry un-replayable at delta-build, evict + miss at lookup), *reverseIndex*
(`Map<RID, Set<tupleIndex>>` for incremental tuple drops), *update-into-match*
(an UPDATE that flips a record into an alias WHERE it did not previously bind).

```mermaid
flowchart TD
    Clf["classify(SQLMatchStatement)"] -->|single-alias| A["RECORD shape\n+ returnProjector"]
    Clf -->|multi-alias, gates hold| MM["MATCH_TUPLE_MULTI"]
    Clf -->|cross-alias-state / subquery / no class:| K0["K0_NONE (D7 gate)"]
    A --> RecPath["Track 1 buildForRecord\n(projector applied pre-sort)"]
    MM --> Build["buildForMatchMulti (two-pass)"]
    Build -->|CREATE / edge DELETE / update-into-match| TS["TOMBSTONE → evict + miss"]
    Build -->|vertex DELETE / pass-fail UPDATE| Drop["tupleSkipSet + ridSkipSet"]
    Drop --> View["CachedResultSetView (per-tuple skip)"]
```

Concrete deliverables: cacheable single- and multi-alias MATCH with the I4 MATCH
test matrix — vertex + edge CREATE/DELETE/UPDATE, update-into-match, bound-edge
pass→fail, and a cross-class-dereference WHERE mutated on the dereferenced record.

## Plan of Work

Approximate sequence (decomposer sets final boundaries). Phase A review
(technical / risk / adversarial, iteration 1) expanded steps 1-7 below — the
original sketch under-specified the K0_NONE gate, the tombstone-build site, the
MATCH-aware entry metadata, and `DatabaseSessionEmbedded`'s role; see
`## Outcomes & Retrospective` and `plan/track-3/reviews/` for the finding trail.

1. **MATCH classify branches — full K0_NONE gate.** Extend `ShapeClassifier.classify`
   for `SQLMatchStatement`, which today returns `MATCH_TUPLE_MULTI` unconditionally
   (`ShapeClassifier.java:137-142`). The gate mirrors `classifySelect`
   (`ShapeClassifier.java:148-170`); `classify` is schema-free (AST-only, no session),
   so anything needing schema resolution defers to entry construction (step 3). Route
   to **K0_NONE** when any of these holds, checked before the multi-alias / Etap-A
   split (the no-version-backstop `MATCH_TUPLE_MULTI` path makes a missed gate a
   silent stale-result bug):
   - **SKIP or LIMIT present** (first gate, as in `classifySelect`): a paginated cached
     prefix cannot be repaired incrementally — an in-window tuple drop emits the wrong
     cardinality.
   - **GROUP BY, UNWIND, RETURN DISTINCT, or NOT MATCH (`notMatchExpressions`)
     present**: a per-tuple skip/inject delta cannot reconcile any of these. All are
     real `SQLMatchStatement` fields (equals/hashCode lines 518-549).
   - **RETURN mode is not the alias-keyed form**: `returnsElements()` /
     `returnsPathElements()` (lines 266-296) flatten the row to one element with no
     alias keys, breaking the alias-keyed tuple assumption (`getProperty(alias)`,
     `reverseIndex`, the Etap-A `returnProjector`). Route to K0_NONE. (design.md's MATCH
     gate does not yet restrict RETURN mode — flag for Phase 4 design reconciliation.)
   - **LET binding or subquery WHERE target present.**
   - **Any pattern node lacks `class:`** (an unconstrained alias cannot seed a class
     filter).
   - **A cross-alias-state WHERE** — a predicate spanning two pattern aliases, detected
     via `SQLWhereClause.getMatchPatternInvolvedAliases` (`SQLWhereClause.java:811-844`).
   - **A link-path-dereference WHERE** (`where:(assignee.name = ?)`) — a dotted path
     whose head is a property/link rather than the bound alias, dereferencing into a
     class outside the pattern's read set. Structurally distinct from the cross-alias
     check and **not** covered by `getMatchPatternInvolvedAliases`: needs a dedicated
     walk of each alias WHERE for a path expression whose head is a property/link. A
     mutation of the dereferenced out-of-pattern record is otherwise dropped by the
     delta build's class filter.
   - **A non-statically-resolvable edge label or alias class** (parameterized
     `out(:edgeType)` / `class::param`, computed): the populate-time closure cannot be
     built from the AST alone, so route to K0_NONE rather than seed a wrong/empty
     closure.
   - **`n + m > maxRecordsPerEntry`** (design.md L506 cap).
   Everything else splits: single-alias → RECORD + `returnProjector` (step 2);
   multi-alias (>1 node, or any node with edges, or cross-join) → `MATCH_TUPLE_MULTI`
   (steps 3-6).

2. **Etap A `returnProjector` + MATCH-aware entry metadata.** Build the projector
   closure from the RETURN clause; the single-alias entry needs a **non-empty** class
   filter, its WHERE, and ORDER BY. The existing populate helpers `effectiveFromClasses`
   / `whereClauseOf` / `orderByOf` (`DatabaseSessionEmbedded.java:1134-1151`) are
   `instanceof SQLSelectStatement`-gated and return `Set.of()` / `null` for a MATCH — an
   entry built through them would have an empty filter, reconcile no mutation, and
   replay a stale frozen result. Extend the three helpers to handle `SQLMatchStatement`
   (single-alias: the one alias's class closure, its WHERE, the statement ORDER BY), or
   route Etap A through a dedicated MATCH populate path that sets them explicitly. Reuse
   Track 1's `buildForRecord`, applying the projector to each inject-list entry before
   the ORDER BY sort (so a projected ORDER BY column resolves). Assert the Etap-A
   entry's `effectiveFromClasses` is non-empty.

3. **MATCH multi-alias entry metadata + closure builder.** Populate `aliasClasses`,
   `traversalEdgeClasses`, `aliasWheres`, and `effectiveFromClasses` at **entry
   construction** (session/schema available — not in schema-free `classify`). The MATCH
   `effectiveFromClasses` is the union `{alias-class closures} ∪ {traversal-edge-class
   closures}`, which the existing single-`SchemaClass` `CachedEntry.computeEffectiveFromClasses`
   (`CachedEntry.java:147-157`) does not cover — add a multi-class closure builder
   (e.g. `computeMatchEffectiveFromClasses(aliasClasses, edgeClasses)`) calling
   `SchemaClass.getAllSubclasses()` per class and unioning. Reuse
   `SQLMatchStatement.buildPatterns` / `addAliases` (lines 211-340) for the alias→class
   and alias→where maps (`SQLMatchFilter.getClassName(CommandContext)` needs the
   context, line 107).
   **Edge-class extraction** is the new, correctness-critical work and the floor's
   whole edge-mutation story. All edges are record-based in this engine
   (`addEdgeInternal:1563-1564`; `newEdgeInternal:1520` always adds a CREATED edge-class
   `RecordOperation`), so an edge CREATE/DELETE always surfaces an edge-class op — there
   is **no lightweight-edge mode to guard**. Extract the edge class from
   `SQLMatchPathItem`'s `SQLMethodCall` (`SQLMatchPathItem.java:21-44`): direction is
   `method.methodName`, the edge class is the first param expression. Contract the
   extractor must honor, each silent if wrong:
   - Recognize every edge-binding traversal method: `out`, `in`, `both`, `outE`, `inE`,
     `bothE` (at minimum). Missing `outE`/`inE`/`bothE` drops the bound-edge shape the
     pass→fail branch relies on.
   - Fold the parser's `null`→`"E"` default (`SQLMatchPathItem.graphPath:32-36`): a bare
     `out()` names base class `E`, whose closure is every edge class (coarse but safe).
     Read the param as literal `"E"`; do not treat "no param" as "no edge class."
   - Handle multiple edge classes per step (`out('E1','E2')`) — each param contributes a
     closure.
   - A non-statically-resolvable label was already routed to K0_NONE in step 1.
   Populate `contributingRids` + `reverseIndex` during stream-pull.

4. **`MatchMultiDelta` + `DeltaBuilder.buildForMatchMulti`** (two-pass):
   - **Pass 1 — tombstone pre-scan.** Class-filter each op on `effectiveFromClasses`
     first, then return TOMBSTONE (short-circuiting the whole build) on: a **CREATE of a
     class in `effectiveFromClasses`** (the scoped predicate — an unrelated-class CREATE
     cannot add a tuple), an **edge-class DELETE**, or an **update-into-match**. The
     short-circuit must fire before pass 2 sees the endpoint-vertex UPDATEs an edge
     CREATE co-emits (`addEdgeInternal` → `createLink`, lines 1571-1574), so a correct
     edge-class fold (step 3) is what makes this case tombstone rather than
     mis-reconcile.
   - **update-into-match** (operational definition): any post-populate UPDATE on an
     alias-class record NOT in `contributingRids`, OR in `contributingRids` with a
     fail→pass WHERE-membership flip for some alias, tombstones. Accept the
     over-tombstone — the entry holds no before-state for records outside cached tuples.
   - **Pass 2 — per-tuple build.** `tupleSkipSet` (vertex DELETE drops every tuple
     holding that RID via `reverseIndex`) + per-RID `ridSkipSet` (pass→fail UPDATE: a
     bound record whose alias WHERE now fails). **Null-guard** the pass→fail check: a
     bound alias declared without a `where:` has a null `aliasWheres[alias]`; treat null
     as "always matches" (skip the check) rather than NPE.

5. **Tombstone build + evict — in `buildView`, not `lookup`.** Build
   `buildForMatchMulti` in `DatabaseSessionEmbedded.buildView`
   (`DatabaseSessionEmbedded.java:1105-1108`), where RECORD and aggregate already build
   their deltas — `lookup(CacheKey, long)` carries no `FrontendTransactionImpl` /
   `CommandContext` and does only the K0_NONE version gate, so building there would force
   a signature change and break the "lookup does no AST work" hit-path contract. On a
   TOMBSTONE result, call a new package-visible cache helper (e.g.
   `removeForTombstone(key)`) and return a fresh `executeUncached` instead of a view.
   - **`serveThroughCache` separate gate + `viewOwnsGuard` transfer** (Track 2
     carry-forward): add the MATCH branch as a separate gate alongside RECORD / K0_NONE /
     AGGREGATE_*, and set `viewOwnsGuard = result instanceof CachedResultSetView` (the
     `instanceof` test the RECORD/aggregate branches use, lines 809/824). A
     TOMBSTONE-driven uncached re-execution must leave `viewOwnsGuard == false` so the
     `finally` releases the depth bump — an unconditional `viewOwnsGuard = true` on a
     MATCH HIT would leak the guard for the rest of the transaction (every later
     `query()` would bypass the cache).
   - **Tombstone eviction discipline:** tombstone eviction is the one removal path that
     drops a *pinned* entry from the map, so it follows `overflowEntry`'s "remove from
     map, do NOT close the stream while a live view pins the entry" discipline
     (`QueryResultCache.java:177-182`), not `invalidate`'s immediate close (lines
     210-213). A live `CachedResultSetView` keeps its frozen snapshot (I7); re-execution
     is on the next `query()`. Tombstone is single-shot per mutationVersion.

6. **`CachedResultSetView` MATCH path.** Skip cached tuples in `tupleSkipSet`; on
   stream-pull, drop a tuple if any alias binding is in `ridSkipSet`, else append +
   extend `reverseIndex` / `contributingRids`. Cross-thread guard release stays on
   `exitCacheCodeUnchecked` via the view's `releasePin`.

7. **I4 MATCH test matrix.** Etap A equivalence (cache-miss vs hit+delta across
   CREATED/UPDATED/DELETED). Multi-alias incremental: vertex DELETE, pass→fail UPDATE,
   bound-edge pass→fail, no-WHERE-bound-alias UPDATE (the null-guard case). Tombstone:
   edge CREATE (incl. an edge whose endpoints are both already in cached tuples — the
   row that catches an edge-class-extraction miss end-to-end), edge DELETE,
   update-into-match (UPDATE a never-bound record so it now satisfies an alias). K0_NONE
   routing: MATCH + SKIP/LIMIT, MATCH + GROUP BY, MATCH RETURN DISTINCT, MATCH RETURN
   $elements, parameterized edge label; cross-class-dereference WHERE
   (`where:(assignee.name=?)`) asserting both K0_NONE classification AND correctness when
   the dereferenced record mutates, plus a negative row (`where:(i.title=?)` that does
   NOT route to K0_NONE) and an acceptance that an out-of-pattern-class CREATE does NOT
   tombstone. I7: open a live MATCH view, tombstone via a second `query()`, confirm the
   live view keeps its frozen tuples and the next `query()` re-executes fresh. Back the
   edge-class extraction with a direct unit test on `effectiveFromClasses` per
   method-name variant (`out/in/both/outE/inE/bothE`) and the unnamed-`out()`→`E` case,
   independent of the end-to-end matrix.

Ordering: step 1 gates the rest; step 2 (Etap A) is independent of steps 3-6
(multi-alias); tests last. Invariants to preserve: every result-changing mutation
touches a class in `effectiveFromClasses` and is either reconciled or tombstoned
(the delta-build completeness floor); the I7 frozen-view contract holds for
tombstone latency (a tombstoned entry's live views keep their frozen snapshot,
re-execution happens on the next `query()`).

## Concrete Steps

1. MATCH K0_NONE classify gate in `ShapeClassifier.classify` (Plan-of-Work step 1: SKIP/LIMIT first, then GROUP BY/UNWIND/RETURN DISTINCT/NOT MATCH, non-alias-keyed RETURN, LET/subquery, any node missing `class:`, cross-alias-state WHERE, link-path-deref WHERE via a new dedicated walk, non-statically-resolvable edge/class label, `n+m>maxRecordsPerEntry` → K0_NONE; non-gated MATCH stays `MATCH_TUPLE_MULTI`); `ShapeClassifierTest` routing assertions + the negative `where:(i.title=?)` row — risk: high (performance hot path: cache classify/lookup logic; the no-backstop floor's first gate — a missed shape silently serves stale results)  [ ]
2. Etap A single-alias MATCH → RECORD fold (Plan-of-Work step 2): `classify` single-alias→RECORD split, `CachedEntry.returnProjector`, MATCH-aware `effectiveFromClasses`/`whereClauseOf`/`orderByOf` (or a dedicated MATCH populate path) in `DatabaseSessionEmbedded`, and the `serveThroughCache`/`buildView` Etap-A branch reusing `buildForRecord` with the projector applied pre-sort; Etap A cache-miss-vs-hit equivalence tests across CREATE/UPDATE/DELETE + a non-empty-`effectiveFromClasses` assertion *(independent of steps 3-5)* — risk: high (performance hot path: cache lookup + view-build path; an empty `effectiveFromClasses` would serve stale)  [ ]
3. `MATCH_TUPLE_MULTI` entry metadata + `CachedEntry.computeMatchEffectiveFromClasses` + edge-class extraction (Plan-of-Work step 3): populate `aliasClasses`/`traversalEdgeClasses`/`aliasWheres`/`effectiveFromClasses` at entry construction (multi-class union closure), reuse `SQLMatchStatement.buildPatterns`/`addAliases` for the alias→class/where maps, and extract the edge class from `SQLMatchPathItem`'s `SQLMethodCall` recognizing `out/in/both/outE/inE/bothE`, folding the parser `null`→`E` default and multi-param; unit tests asserting `effectiveFromClasses` per method-name variant and the unnamed-`out()`→`E` base closure — risk: high (correctness floor: edge-class extraction is the entire edge-mutation tombstone story — R1 blocker)  [ ]
4. `MatchMultiDelta` + `DeltaBuilder.buildForMatchMulti` two-pass (Plan-of-Work step 4): pass-1 tombstone pre-scan (scoped CREATE / edge-class DELETE / update-into-match → TOMBSTONE short-circuit) and pass-2 per-tuple build (`tupleSkipSet` via `reverseIndex` on vertex DELETE, `ridSkipSet` on pass→fail UPDATE with the null-`aliasWheres` "always matches" guard); delta-builder unit tests on a constructed entry + staged tx ops covering each TOMBSTONE trigger, the skip-sets, and the no-WHERE-bound-alias UPDATE no-NPE case — risk: high (the delta-build is the entire correctness story for the no-version-backstop shape)  [ ]
5. Multi-alias session wiring + tombstone eviction + `CachedResultSetView` MATCH path + I4/I7 matrix (Plan-of-Work steps 5-7): `serveThroughCache` separate MATCH gate with the `viewOwnsGuard = result instanceof CachedResultSetView` transfer, `buildView` routing a TOMBSTONE through a new `QueryResultCache.removeForTombstone` (`overflowEntry` pinned-entry discipline) to `executeUncached`, and the `CachedResultSetView` MATCH per-tuple path (skip `tupleSkipSet`, drop on `ridSkipSet`, extend `reverseIndex`/`contributingRids` on stream-pull, cross-thread release via `exitCacheCodeUnchecked`); end-to-end MATCH equivalence matrix (vertex DELETE, pass→fail, bound-edge pass→fail, no-WHERE-alias UPDATE, tombstone on edge CREATE/DELETE/update-into-match, edge-between-already-cached-vertices, out-of-pattern-CREATE-does-not-tombstone) + a `viewOwnsGuard`-not-leaked regression + the I7 live-view-under-tombstone test — risk: high (concurrency: re-entrancy guard transfer + pinned-entry eviction; performance hot path: cache lookup/eviction + query-execution wiring)  [ ]

## Episodes
<!-- Continuous-log. -->

## Validation and Acceptance

- Single-alias `MATCH {as:u, class:X WHERE p} RETURN u, u.name` cached, then
  CREATE/UPDATE/DELETE between two `query()` calls → the second view matches a
  parallel uncached MATCH (Etap A equivalence). The Etap-A entry's
  `effectiveFromClasses` is non-empty.
- Multi-alias `MATCH {as:i, class:Issue}.out('project'){as:p, class:Project}
  RETURN i, p`:
  - `delete(issue)` → all tuples holding that RID drop (incremental).
  - WHERE-breaking UPDATE on `i` → affected tuples drop (incremental).
  - bound-edge `where:(weight>5)` UPDATE that flips `e` out → pass→fail drop.
  - UPDATE of a record bound by a no-`where:` alias (null `aliasWheres` entry) →
    no NPE; the alias never drives a pass→fail drop.
  - edge CREATE / edge DELETE / update-into-match → entry tombstoned, next
    `query()` re-executes fresh; output matches uncached.
  - edge CREATE whose endpoints are BOTH already in cached tuples → tombstoned
    (not reconciled via the endpoint-vertex UPDATEs); the row that catches an
    edge-class-extraction miss end-to-end.
  - CREATE of a class OUTSIDE the pattern's read set → does NOT tombstone; the
    entry still serves incrementally.
- **K0_NONE routing** (classify-assertion rows): MATCH + SKIP/LIMIT, MATCH + GROUP
  BY, MATCH RETURN DISTINCT, MATCH RETURN `$elements`, and a parameterized edge
  label (`out(:edgeType)`) each classify K0_NONE and serve correctly under the
  version gate after an in-scope mutation.
- A pattern WHERE dereferencing a link into an out-of-pattern class
  (`where:(assignee.name = ?)`) classifies as K0_NONE and is correct under the
  version gate when the dereferenced record is mutated. Negative row: a plain
  `where:(i.title = ?)` (no link-path deref) does NOT route to K0_NONE.
- **Edge-class extraction unit test** (independent of the end-to-end matrix): a
  direct `effectiveFromClasses` assertion per traversal method name
  (`out/in/both/outE/inE/bothE`) and the unnamed-`out()`→`E` base-closure case, so
  an extraction miss fails at the metadata layer rather than as a wrong query
  result.
- **I7 frozen-view under tombstone**: open a live MATCH view, tombstone the entry
  via a second `query()` after an edge CREATE, then continue draining the first
  view → it keeps its frozen tuples; the second `query()` re-executes fresh.
- Every scenario above matches a parallel uncached `query()` at the same moment
  (I4/I10).

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS/Gherkin acceptance lines. -->

## Idempotence and Recovery
<!-- Phase A placeholder. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Often empty. -->

## Interfaces and Dependencies

**In scope (new):** `MatchMultiDelta`.

**In scope (modified):** `ShapeClassifier` (the full MATCH K0_NONE gate + the
single/multi-alias split; schema-free), `DeltaBuilder` (match path —
`buildForMatchMulti`), `CachedResultSetView` (MATCH per-tuple path), `CachedEntry`
(MATCH metadata fields: `aliasClasses`, `traversalEdgeClasses`, `aliasWheres`,
`contributingRids`, `reverseIndex`, `tombstoned`, `returnProjector`; plus the new
multi-class closure builder `computeMatchEffectiveFromClasses`), `QueryResultCache`
(a package-visible `removeForTombstone(key)` helper following `overflowEntry`'s
pinned-entry discipline — NOT a tombstone build inside `lookup`), and
**`DatabaseSessionEmbedded`** — the SELECT-only view-building path needs five MATCH
edits: (1) `serveThroughCache` MATCH branch as a separate gate + the
`viewOwnsGuard = result instanceof CachedResultSetView` transfer (lines 809/824);
(2) `buildView` MATCH branch building `buildForMatchMulti` and handling the
TOMBSTONE-then-`executeUncached` outcome (lines 1105-1108); (3) `effectiveFromClasses`,
(4) `whereClauseOf`, (5) `orderByOf` extended for `SQLMatchStatement` (lines
1134-1151, currently `instanceof SQLSelectStatement`-gated), or a dedicated MATCH
populate path that sets the entry's filter/WHERE/ORDER BY explicitly.

**Out of scope (deferred to a separate ADR):** constrained-pattern-walk CREATE
discovery (`MatchPrefetchStep` + edge-CREATED dispatch hook), incremental
edge-DELETE (endpoint-content reverse index). Both are correctness-neutral
because the v1 floor tombstones the cases. Also out of scope: MATCH `NOCACHE`
grammar token (v2).

**Compatibility:** `MATCH_TUPLE_MULTI` carries no mutation-version backstop, so
the delta-build floor is the entire correctness story — the classify gates must
route every non-floor-handleable shape to K0_NONE. The tombstone path must honor
the I7 frozen-view contract (live views unaffected; re-execution on next query).

**Upstream dependency:** Track 1 (RECORD `buildForRecord` for Etap A,
`CachedResultSetView`, `CachedEntry`, the single-class `computeEffectiveFromClasses`
closure machinery, the classify scaffold, and the `serveThroughCache` / `buildView`
separated-gate pattern — the MATCH delta builds in `buildView` like RECORD/aggregate,
not in `lookup`). Track 2 is a sequencing predecessor (stacked-diff order) and the
source of the `serveThroughCache` separate-gate + `viewOwnsGuard`-transfer pattern;
MATCH does not consume aggregate internals.

## Base commit
6913778bbeeb02ce6711d2c1ec894bd27a39043d

**Downstream consumers:** none (final shape track).

**Key signatures:**
- `DeltaBuilder#buildForMatchMulti(CachedEntry, FrontendTransactionImpl, CommandContext): MatchMultiDelta` (or TOMBSTONE sentinel) — invoked from `DatabaseSessionEmbedded.buildView`, not `lookup`
- `MatchMultiDelta#shouldSkipTuple(int): boolean`, `#shouldSkipRid(RID): boolean`
- `CachedEntry#returnProjector: Function<RecordAbstract, Result>` (Etap A)
- `CachedEntry#computeMatchEffectiveFromClasses(Collection<SchemaClass> aliasClasses, Collection<SchemaClass> edgeClasses): Set<…>` (new multi-class closure builder)
- `QueryResultCache#removeForTombstone(CacheKey): void` (new; `overflowEntry` pinned-entry discipline)
- `SchemaClass#getAllSubclasses()` (existing, reused for closures)
- `SQLWhereClause#matchesFilters(Identifiable, CommandContext): boolean` (existing, reused per alias)
- `SQLWhereClause#getMatchPatternInvolvedAliases(...)` (existing; cross-alias-state gate only — does NOT detect link-path derefs)
