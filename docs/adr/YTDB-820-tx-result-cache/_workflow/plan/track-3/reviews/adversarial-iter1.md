<!-- workflow-sha: e9377f7f133f5cd6ec3028936f28be2819e4ae96 -->
# Adversarial review — Track 3 (MATCH shapes), iteration 1

- role: reviewer-adversarial
- phase: 3A
- track: "Track 3: MATCH shapes — Etap A composition, partial Etap B, tombstone floor"
- prefix: A
- previous_findings: none (iteration 1)

## Manifest

```yaml
findings: 7
blockers: 2
index:
  - id: A1
    sev: blocker
    anchor: "A1"
    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLMatchStatement.java:34-39, ShapeClassifier.classify:137-142"
    cert: "Violation scenario: MATCH SKIP/LIMIT/GROUP BY/UNWIND/RETURN DISTINCT bypasses the K0_NONE gate"
    basis: "MATCH grammar admits skip/limit/groupBy/orderBy/unwind/returnDistinct; classify gate in Plan-of-Work step 1 enumerates none of them"
  - id: A2
    sev: blocker
    anchor: "A2"
    loc: "VertexEntityImpl.addEdge:215-230, Track-3 Plan-of-Work step 4 tombstone pre-scan"
    cert: "Violation scenario: edge mutation between two out-of-alias vertices evades the tombstone pre-scan"
    basis: "edge persistence writes endpoint-vertex out_/in_ link props + (regular edge) an edge record; tombstone catches it only if the touched class is in effectiveFromClasses"
  - id: A3
    sev: should-fix
    anchor: "A3"
    loc: "ShapeClassifier (MATCH branch), SQLWhereClause.getMatchPatternInvolvedAliases:811-844"
    cert: "Assumption test: classify routes cross-class-dereference WHEREs to K0_NONE"
    basis: "involved-alias infra exists for cross-ALIAS state, but a link-PATH deref into an out-of-pattern class is a different shape needing a path-expression walk the track does not specify"
  - id: A4
    sev: should-fix
    anchor: "A4"
    loc: "SQLMethodCall.getParams:290, SQLMatchPathItem.graphPath:32-44"
    cert: "Assumption test: traversalEdgeClasses can be read statically from the AST"
    basis: "edge label is a method param expression; a parameterized or absent label ('E' default → all edges) breaks the static read or explodes the closure"
  - id: A5
    sev: should-fix
    anchor: "A5"
    loc: "Track-3 Plan-of-Work step 4 (update-into-match), DeltaBuilder.buildForRecord:136-179"
    cert: "Assumption test: update-into-match is detectable per-tuple at delta-build"
    basis: "an UPDATE flips a record INTO an alias the entry never bound; reverseIndex has no tuple to anchor on, so detection cannot be per-RID like the RECORD path"
  - id: A6
    sev: should-fix
    anchor: "A6"
    loc: "CachedEntry.computeEffectiveFromClasses:147-157, Track-3 Plan-of-Work steps 2-3"
    cert: "Challenge: D11 effectiveFromClasses reuse for the multi-class MATCH union"
    basis: "the existing helper takes ONE SchemaClass; MATCH needs a union over N alias classes + M edge closures with a different builder, not a reuse"
  - id: A7
    sev: suggestion
    anchor: "A7"
    loc: "Track-3 Plan-of-Work step 5 (tombstone single-shot), CachedResultSetView re-entrancy/pin:352-363"
    cert: "Violation scenario: tombstone single-shot-per-mutationVersion vs I7 frozen-view contract for a live view"
    basis: "tombstone evicts the entry at lookup; a concurrently-live view of the same entry keeps its frozen snapshot per I7 — the contract holds, but the single-shot interacts with the re-entrant lazy-pull window"
```

## Findings

### A1 [blocker]
**Certificate**: Violation scenario — MATCH SKIP/LIMIT/GROUP BY/UNWIND/RETURN DISTINCT bypasses the K0_NONE gate
**Target**: Plan-of-Work step 1 (MATCH classify branches); Invariant I4/I10 (view ≡ fresh execution)
**Challenge**: The MATCH classify gate in step 1 lists exclusions "no LET/UNWIND, no cross-alias-state WHEREs, no subqueries, no cross-class link-deref" but omits SKIP, LIMIT, GROUP BY, and RETURN DISTINCT entirely. The SELECT classifier treats SKIP/LIMIT and GROUP BY as the *first* gate to K0_NONE precisely because a paginated cached prefix is structurally incomplete: with ORDER BY + LIMIT the executor materialises only the top-N and discards the rest, so an in-tx delete of a cached top-N row cannot promote row N+1 into view (`ShapeClassifier.classifySelect:149-164`). A `MATCH {as:i, class:Issue}.out('p'){as:p, class:Project} RETURN i, p SKIP 10 LIMIT 5` classifies `MATCH_TUPLE_MULTI`, gets per-tuple incremental reconciliation, and after a vertex DELETE drops a tuple inside the window the view emits 4 rows where fresh execution would emit 5 (row 16 promoted into the window). RETURN DISTINCT has the same shape problem: incremental tuple drop cannot re-derive distinctness across the dropped tuple's projection.
**Evidence**: `SQLMatchStatement` carries `skip`, `limit`, `groupBy`, `orderBy`, `unwind`, `returnDistinct` fields (lines 34-39, accessors 568-644) — the MATCH grammar production at `YouTrackDBSql.jjt:1245+` admits all of them after RETURN. The current `classify` stub returns `MATCH_TUPLE_MULTI` for *any* `SQLMatchStatement` (`ShapeClassifier.classify:137-142`) with no field inspection. Feasibility: CONSTRUCTIBLE.
**Proposed fix**: Add a first-gate to the MATCH branch mirroring the SELECT path: `skip != null || limit != null || groupBy != null || unwind != null || returnDistinct` → K0_NONE, checked before the multi-alias/Etap-A split. Add the corresponding I4 test rows (MATCH + SKIP/LIMIT, MATCH + GROUP BY, MATCH RETURN DISTINCT, each with a mutation inside the window). Note ORDER BY is *not* a K0_NONE trigger by itself for the multi-alias path (the view sorts the inject list), but ORDER BY + LIMIT is — same as SELECT.

### A2 [blocker]
**Certificate**: Violation scenario — edge mutation between two out-of-alias vertices evades the tombstone pre-scan
**Target**: Plan-of-Work step 4 (tombstone pre-scan on edge-class DELETE / CREATE); Invariant "every result-changing mutation touches a class in effectiveFromClasses"
**Challenge**: The tombstone floor rests on edge CREATE / edge DELETE surfacing as a `RecordOperation` whose class is in `traversalEdgeClasses` (folded into `effectiveFromClasses`). But in this engine an edge mutation does not only touch the edge: `VertexEntityImpl.addEdge` writes the endpoint vertices' `out_<label>` / `in_<label>` link properties (`VertexEntityImpl:215-230, 445-541`), producing vertex-class UPDATE operations on the *endpoint vertex classes*. The delta build catches an edge mutation through one of two routes: (a) the edge record op if regular edges produce one and the edge class is in the closure, or (b) the endpoint-vertex UPDATE if that vertex's class is an alias class. Construct the gap: a pattern `MATCH {as:i, class:Issue}.out('worksOn'){as:p, class:Project}` where a third edge `assignedTo` is created from an `Issue` to a `User` (User not in any alias, worksOn ≠ assignedTo). The new `assignedTo` edge's record class is not in `traversalEdgeClasses` (only `worksOn`'s closure is), and the endpoint-vertex UPDATE is on `Issue` (an alias class) — so route (b) DOES catch it here, producing a vertex UPDATE on `i`, which the per-tuple build re-evaluates. That is correct. But now make the source vertex out-of-pattern: edge `worksOn` created from a `Contractor` (a sibling vertex class NOT folded into `aliasClasses` because the pattern only names `Issue`) to a `Project` already bound at alias `p`. The `Project` endpoint gets an `in_worksOn` UPDATE — `Project` IS alias class `p`, so route (b) fires. The genuinely dangerous case is when the edge create completes a NEW path among *already-bound* vertices without updating a property the pre-scan watches: this depends entirely on whether the engine emits an edge-class record op. The track asserts it does ("any edge-class DELETE … tombstone"); that assumption must be verified against edge persistence, and lightweight-edge mode (no edge record at all — only vertex link-prop updates) must be ruled in or out.
**Evidence**: `VertexEntityImpl.addEdge:215-230` resolves the edge class name then delegates; `out_`/`in_` link-property handling at `VertexEntityImpl:445-541, 604-708` shows endpoint mutation. The tombstone-via-edge-closure path (`Context and Orientation` "Traversal edges" bullet) is the explicit fix for "the original edge-mutation bug"; its correctness hinges on the edge op existing. Feasibility: CONSTRUCTIBLE if lightweight edges (or any edge-create path that emits no edge-class record op AND touches no alias-class vertex property) exist; THEORETICAL if every edge mutation provably touches an alias-class vertex content op.
**Proposed fix**: Before decomposition, verify (PSI find-usages on `addRecordOperation` from the edge create/delete path, or a step-0 spike test) exactly which `RecordOperation`s an edge CREATE and edge DELETE emit: (1) an edge-class record op, (2) endpoint-vertex UPDATEs, or both, and whether lightweight edges change this. Then state in the track which route the tombstone relies on. If it relies on the edge record op, add an explicit non-goal/guard for lightweight-edge mode; if it relies on the endpoint UPDATE, fold the endpoint-vertex closure (not just the edge closure) into `effectiveFromClasses` and add an I4 test for an edge whose endpoint vertex is out-of-pattern.

### A3 [should-fix]
**Certificate**: Assumption test — classify routes cross-class-dereference WHEREs to K0_NONE
**Target**: Plan-of-Work step 1 ("no WHERE that dereferences a link path into a class outside the read set"); Validation bullet (`where:(assignee.name=?)` → K0_NONE)
**Challenge**: The track claims classify routes a pattern WHERE like `where:(assignee.name = ?)` — dereferencing a link into an out-of-pattern class — to K0_NONE, which is correct only under the version gate when the dereferenced record (`assignee`'s target) mutates. The existing infrastructure `SQLWhereClause.getMatchPatternInvolvedAliases` (`SQLWhereClause:811-844`) detects cross-ALIAS-state WHEREs (a predicate spanning two pattern aliases), but a link-PATH dereference into a non-aliased class is a structurally different shape: `assignee` is a property on the alias vertex whose target is a record of class `User` that appears in NO pattern alias and therefore in NO `effectiveFromClasses`. A mutation of that `User` record is a result-changing mutation the delta build's class filter silently drops — exactly the I4 violation the K0_NONE routing is meant to prevent. Detecting it needs a walk of the WHERE for dotted-path / link-traversal expressions, not the alias-set check `getMatchPatternInvolvedAliases` provides. The track names the requirement but not the detection mechanism, and the obvious reuse (`getMatchPatternInvolvedAliases`) does not cover it.
**Evidence**: `getMatchPatternInvolvedAliases` returns the *aliases* a boolean expression references (`SQLWhereClause:811-844`); it does not surface a `prop.subprop` link-path that escapes the pattern. The RECORD delta build's class filter (`DeltaBuilder.buildForRecord:122-128`) drops any op whose class is outside `effectiveFromClasses` — so an out-of-pattern `User` mutation is invisible. Verdict: FRAGILE — the requirement is correct, the detection path is unspecified and not covered by the cited reuse.
**Proposed fix**: Specify the classify mechanism for link-path-deref detection (walk each alias WHERE for a path expression whose head is a property/link rather than the alias itself, route to K0_NONE on any such path). Add it as a distinct step from the cross-alias-state gate. Keep the I4 test row already listed (`where:(assignee.name=?)` mutated on the dereferenced record) and add a negative row (a plain `where:(i.title=?)` that does NOT route to K0_NONE).

### A4 [should-fix]
**Certificate**: Assumption test — traversalEdgeClasses can be read statically from the AST
**Target**: Plan-of-Work step 3 (populate `traversalEdgeClasses`); Context "Traversal edges" bullet
**Challenge**: The track folds `.out/.in/.both(label)` edge-class subclass closures into `effectiveFromClasses` by reading the label from the AST. Two static-read hazards: (1) the edge label is a method-call *param expression* (`SQLMethodCall.getParams:290`), not a bare identifier — `.out(:edgeType)` parameterizes it, so the label is unknown until bind time and cannot seed a populate-time closure; (2) a bare `.out()` with no label defaults the edge to base class `E` (`SQLMatchPathItem.graphPath:32-36` sets `edgeName.value = "E"` when null), whose subclass closure is *every edge class in the schema*. Case (2) is correct-but-coarse (the tombstone trips on any edge mutation), but case (1) is a correctness hole: a parameterized edge label means `traversalEdgeClasses` cannot be computed from the AST alone, and an entry built with an empty/wrong edge closure would miss an edge mutation that should tombstone.
**Evidence**: `graphPath` (`SQLMatchPathItem:32-44`) builds the method with the edge label as a `SQLExpression` param and defaults to `"E"`; `SQLMethodCall.getParams` returns `List<SQLExpression>` (line 290) which can hold an input-parameter expression. The cache key includes normalized params (D2), so two queries differing only in `:edgeType` are distinct entries — but the closure must still be computed correctly per entry. Verdict: FRAGILE — bare `.out()` is coarse-but-safe; a parameterized label is unhandled.
**Proposed fix**: In classify, treat a traversal whose edge label is not a static identifier (parameterized, computed, or empty) conservatively: either route the whole pattern to K0_NONE, or fold the base-`E` full-edge closure (every edge class) so the tombstone over-fires rather than misses. State which, and add an I4 row for a parameterized edge label.

### A5 [should-fix]
**Certificate**: Assumption test — update-into-match is detectable per-tuple at delta-build
**Target**: Plan-of-Work step 4 (UPDATE checks update-into-match → tombstone); definition of *update-into-match*
**Challenge**: The RECORD delta path detects a pass→fail / fail→pass transition by re-evaluating WHERE on the mutated record and dispatching on `(cached_at_build, match_after)` (`DeltaBuilder.buildForRecord:136-179`). For a multi-alias MATCH, an *update-into-match* is an UPDATE that flips a record INTO an alias binding it never previously held — but the entry's `reverseIndex` (`Map<RID, Set<tupleIndex>>`) has no tuple anchored on that RID, because the record never participated in any cached tuple. So the per-tuple build cannot see it as a tuple drop; it can only be seen as "a record now satisfies alias X's class+WHERE but contributes to no cached tuple, and re-running the pattern from it could produce NEW tuples (new edge traversals)". The track's answer is to tombstone, which is correct, but the *detection* of update-into-match is non-trivial: it requires, for every post-populate UPDATE on an alias-class record, checking whether the record now satisfies an alias's class+WHERE that it did not before — and the entry holds no per-record before-state for records outside the cached tuples. The track sketches "UPDATE checks update-into-match → tombstone" without specifying how a record absent from `reverseIndex` is recognised as a newly-qualifying alias binding.
**Evidence**: `reverseIndex` is `Map<RID, Set<tupleIndex>>` (Context terminology) — keyed only by RIDs already in cached tuples. The RECORD path's `cached_at_build` distinguisher (`DeltaBuilder.buildForRecord:139, 149-164`) is the analogous mechanism, but for MATCH the "would this UPDATE create a new tuple via traversal" question cannot be answered without re-walking the pattern. Verdict: FRAGILE — tombstoning is correct; the trigger condition needs a precise definition.
**Proposed fix**: Define update-into-match operationally: any post-populate UPDATE on a record of an alias class (or an edge class) where the record is NOT in `contributingRids`, OR is in `contributingRids` but its WHERE-membership for some alias flipped fail→pass → tombstone. Equivalently, state the conservative rule "any UPDATE on an alias-class record that is fail→pass for any alias, or any UPDATE on a record not currently contributing, tombstones" and accept the over-tombstone. Add an I4 row: UPDATE a never-bound `Issue` so it now satisfies alias `i`'s WHERE → entry tombstoned, fresh re-exec finds the new tuple.

### A6 [should-fix]
**Certificate**: Challenge — D11 effectiveFromClasses reuse for the multi-class MATCH union
**Target**: Decision D11 ("Traversal-edge classes fold into effectiveFromClasses"); Plan-of-Work steps 2-3; Interfaces ("SchemaClass#getAllSubclasses() (existing, reused)")
**Challenge**: The track presents the MATCH `effectiveFromClasses` as a reuse of Track 1's closure machinery, citing `SchemaClass.getAllSubclasses()` as "existing, reused". But the existing entry-construction helper `CachedEntry.computeEffectiveFromClasses` takes a *single* `SchemaClass` and returns its closure (`CachedEntry:147-157`). The MATCH union — `{alias_1 class ∪ … ∪ alias_N class} ∪ {edge_1 closure ∪ … ∪ edge_M closure}` — is a different computation requiring a new multi-class builder, not a call to the existing single-class one. Calling the existing helper N+M times and unioning is workable but is a new method, and the track's framing ("reuse Track 1's machinery") under-scopes it: the single-class helper cannot be reused as-is, and the alias-class resolution itself needs `getClassName(ctx)` per `SQLMatchFilter` (`SQLMatchFilter.getClassName:107-127`) plus a schema lookup that the AST-only classify path does NOT have (classify runs off the AST alone with no session — `ShapeClassifier` Javadoc lines 18-21). So the MATCH closure cannot be built in `classify`; it must be built at entry construction where the session/schema is available, which is a different seam than the RECORD path's.
**Evidence**: `computeEffectiveFromClasses` signature is single-`SchemaClass` (`CachedEntry:147`); `ShapeClassifier.classify` is explicitly schema-free (class Javadoc "no session, no schema lookup", lines 18-21); `SQLMatchFilter.getClassName(CommandContext)` needs a context (lines 107-127). Survival test: WEAK — the decision (fold closures) is right, but the reuse claim and the seam (classify vs entry-construction) are mis-stated.
**Proposed fix**: Reword steps 2-3 and Interfaces to add a new multi-class closure builder (e.g. `computeMatchEffectiveFromClasses(List<SchemaClass> aliasClasses, List<SchemaClass> edgeClasses)`), and make explicit that the MATCH closure is computed at *entry construction* (session available), not in `classify` (AST-only). Confirm `getClassName` resolution handles a parameterized class (`class::param`) — route to K0_NONE if the class name is not statically resolvable.

### A7 [suggestion]
**Certificate**: Violation scenario — tombstone single-shot-per-mutationVersion vs I7 frozen-view contract for a live view
**Target**: Plan-of-Work step 5 (tombstone single-shot per mutationVersion); Invariant I7 (view delta immutable post-construction); Compatibility note (live views unaffected)
**Challenge**: Step 5 makes the tombstone single-shot per mutationVersion: a `MATCH_TUPLE_MULTI` lookup that builds TOMBSTONE removes the entry and returns MISS. The track asserts I7 holds — a tombstoned entry's already-live views keep their frozen snapshot, re-execution happens on the next `query()`. Test the interaction with the entry-pin and the cache-code guard: a live `CachedResultSetView` holds the entry pinned (`liveViewCount > 0`) and owns the cache-code guard across its lazy stream pulls (`CachedResultSetView:352-363`). When a second `query()` of the same MATCH lookup tombstones-and-evicts the entry, `entries.remove(key)` drops the map reference but the live view still holds the entry directly and keeps pulling — which is exactly the LRU-eviction-of-pinned-entry pattern already handled (`QueryResultCache.evictEldestIfUnpinned:193-207` declines to evict a pinned entry, but tombstone eviction is unconditional). So a tombstone eviction differs from LRU eviction: it removes a *pinned* entry from the map. The live view survives (holds the entry ref), so I7 holds for that view — but the entry's stream close timing now depends on the view's `close()`/exhaustion rather than the map, and the next `query()` correctly re-executes fresh. The contract holds; the subtlety worth a written note is that tombstone eviction is the one path that removes a pinned entry, so it must NOT call `entry.close()` while a live view is mid-pull (it must mirror `overflowEntry`'s "do not close the stream" discipline, `QueryResultCache.overflowEntry:177-182`, not `invalidate`'s "close immediately", lines 210-213).
**Evidence**: `overflowEntry` deliberately does not close the stream because the triggering view still pulls (lines 170-182); `invalidate` closes immediately (lines 210-213); LRU eviction skips pinned entries (lines 193-200). Tombstone eviction is a fourth removal path with pinned-entry semantics like `overflowEntry`'s. Feasibility: THEORETICAL (requires a concurrent live view of the same MATCH entry at the tombstoning lookup — possible under consumer-paced iteration). Survival: holds, with a documentation/implementation guard.
**Proposed fix**: In step 5, specify that tombstone eviction follows `overflowEntry`'s discipline (remove from map, route key non-cacheable for the rest of the tx OR allow re-populate on next query per the single-shot rule, do NOT close the stream if a live view pins the entry). Add an I4/I7 test: open a live MATCH view, issue an edge CREATE, issue a second `query()` of the same MATCH (tombstones), then continue draining the first view — it must keep its frozen tuples; the second `query()` must re-execute fresh.

## Evidence base

### Decision challenges

#### Challenge: Decision D11 — MATCH Etap A as RECORD composition; partial Etap B via reverseIndex + tombstone floor
- **Chosen approach**: Fold single-alias MATCH into RECORD via `returnProjector`; multi-alias as `MATCH_TUPLE_MULTI` with a two-pass tombstone floor; traversal-edge closures folded into `effectiveFromClasses`; reuse `SchemaClass.getAllSubclasses()`.
- **Best rejected alternative**: K0 for all MATCH (version-gated, no per-tuple reconciliation). Under K0, every MATCH entry serves only while `tx.mutationVersion` is unchanged and re-executes on any mutation — far simpler, no tombstone floor, no reverseIndex, no edge-closure folding, no update-into-match detection.
- **Counterargument trace**:
  1. The chosen approach builds five pieces of new per-tuple machinery (`aliasClasses`, `traversalEdgeClasses`, `contributingRids`, `reverseIndex`, two-pass build) plus a tombstone floor whose correctness rests ENTIRELY on the delta-build (no version backstop, per the track's own BLUF and Compatibility note).
  2. The K0 alternative reuses the *already-shipped* K0_NONE version gate (`QueryResultCache.lookup:126-138`) verbatim — zero new correctness surface, and the version gate is the one mechanism in this feature with a hard backstop.
  3. Outcome difference: the chosen approach wins on hit-rate in read-mostly-with-occasional-write MATCH fragments (incremental vertex-DELETE / pass→fail-UPDATE survive a mutation); K0 invalidates the whole entry on any mutation. The cost is that A1-A5 are all correctness holes in the no-backstop path that K0 simply does not have.
- **Codebase evidence**: the K0 path is fully built and tested (`QueryResultCache.lookup` version gate, lines 126-138; `CachedResultSetView.computeNextK0None:222-236`). The MATCH multi machinery is entirely new.
- **Survival test**: WEAK. The decision survives on hit-rate grounds for the targeted Hub/DNQ MATCH workload, but the rationale under-weights that the no-backstop floor is the feature's single largest correctness surface and that A1 (missing SKIP/LIMIT/GROUP BY gate) and A2 (edge-op-existence assumption) are blocker-class. Strengthen the rationale by (a) documenting that single-alias Etap A and multi-alias share the K0_NONE fallback for every shape the floor cannot prove safe, and (b) confirming the D13 pre-merge gate measures MATCH hit-rate specifically, so the extra machinery is justified by data rather than assumed.

### Invariant challenges

#### Violation scenario: MATCH SKIP/LIMIT/GROUP BY/UNWIND/RETURN DISTINCT bypasses the K0_NONE gate
- **Invariant claim**: Every cacheable MATCH shape's view output equals a parallel uncached `query()` at the same moment (I4/I10).
- **Violation construction**:
  1. Start state: cache `MATCH {as:i, class:Issue}.out('p'){as:p, class:Project} RETURN i, p ORDER BY i.n SKIP 10 LIMIT 5`; 20 tuples produced, window shows tuples 11-15.
  2. Action sequence: `delete(issue holding tuple 12)` (`addRecordOperation`, vertex DELETE); second `query()` of the same MATCH.
  3. Intermediate state: classify returns `MATCH_TUPLE_MULTI` (the stub returns it for any MATCH, `ShapeClassifier.classify:137-142`; the track's step-1 gate omits SKIP/LIMIT); the per-tuple build drops tuple 12 via reverseIndex.
  4. Violation point: the view now emits 4 rows (tuples 11,13,14,15) — but fresh execution re-paginates and emits 5 (11,13,14,15,16, with 16 promoted into the window). `SQLMatchStatement` carries `skip`/`limit` (lines 34-39), grammar at `YouTrackDBSql.jjt:1245+`.
  5. Observable consequence: wrong cardinality — 4 rows vs 5 — silently.
- **Feasibility**: CONSTRUCTIBLE.

#### Violation scenario: edge mutation between out-of-alias vertices evades the tombstone pre-scan
- **Invariant claim**: Every result-changing mutation touches a class in `effectiveFromClasses` and is reconciled or tombstoned (the delta-build completeness floor).
- **Violation construction**:
  1. Start state: cache `MATCH {as:i, class:Issue}.out('worksOn'){as:p, class:Project} RETURN i, p`; `effectiveFromClasses = {Issue+subs, Project+subs, worksOn+subs}`.
  2. Action sequence: create a `worksOn` edge whose endpoints are an `Issue` already bound at `i` and a `Project` already bound at `p` but which were NOT previously connected (a new path completing the pattern).
  3. Intermediate state: the edge create writes the endpoint vertices' `out_worksOn`/`in_worksOn` link props (`VertexEntityImpl:445-541`) → vertex UPDATEs on `Issue` and `Project` (both alias classes), AND (if regular edges produce a record op) a `worksOn` record CREATE.
  4. Violation point: IF the engine emits the vertex UPDATEs, the pre-scan sees a vertex UPDATE on an alias class and the per-tuple build re-evaluates — but a vertex UPDATE is NOT a CREATE, so it routes to the pass→fail path, not the tombstone, and a pass→fail UPDATE that did not change the WHERE-membership is a no-op → the NEW tuple (i,p) is never discovered. The tombstone fires only on an edge-class CREATE (`worksOn` record op); if lightweight edges emit no record op, no tombstone, no new tuple.
  5. Observable consequence: a tuple that fresh execution produces is missing from the cached view.
- **Feasibility**: CONSTRUCTIBLE if (lightweight edges exist) OR (the vertex link-prop UPDATE is not classified as a tombstone trigger); THEORETICAL if every edge CREATE provably emits an edge-class record op that the closure catches.

#### Violation scenario: tombstone single-shot vs I7 frozen-view for a live view
- **Invariant claim**: I7 — a tombstoned entry's already-live views keep their frozen snapshot; re-execution on next `query()`.
- **Violation construction**:
  1. Start state: a live `CachedResultSetView` over a `MATCH_TUPLE_MULTI` entry, mid-iteration (`liveViewCount=1`, holds the cache-code guard, lazy-pulling the stream).
  2. Action sequence: edge CREATE; a second `query()` of the same MATCH → tombstone path removes the entry from the map and (per the unspecified close discipline) may call `entry.close()`.
  3. Intermediate state: if tombstone uses `invalidate`-style immediate close (`QueryResultCache:210-213`), the live view's shared stream is closed mid-pull.
  4. Violation point: the first view's next `pullOneFromStream` (`CachedResultSetView:319-344`) finds a closed stream → truncates the frozen view, violating I7.
  5. Observable consequence: the live view loses its tail rows.
- **Feasibility**: THEORETICAL (needs a concurrent live view at the tombstoning lookup; possible under consumer-paced iteration). Mitigated by specifying `overflowEntry`-style "do not close the pinned entry's stream" discipline for the tombstone path.

### Assumption tests

#### Assumption test: classify routes cross-class-dereference WHEREs to K0_NONE
- **Claim**: A pattern WHERE dereferencing a link into an out-of-pattern class (`where:(assignee.name=?)`) classifies K0_NONE.
- **Stress scenario**: `MATCH {as:i, class:Issue WHERE (assignee.name = :n)}.out('p'){as:p, class:Project} RETURN i, p`, then update the `User` record `assignee` points at.
- **Code evidence**: `getMatchPatternInvolvedAliases` (`SQLWhereClause:811-844`) surfaces referenced *aliases*, not a link-path escaping the pattern; the delta class filter drops out-of-closure ops (`DeltaBuilder.buildForRecord:122-128`). The link-path `assignee.name` references no second pattern alias, so the alias-set check does not flag it.
- **Verdict**: FRAGILE — the requirement is correct, the detection mechanism is unspecified and not covered by the cited reuse.

#### Assumption test: traversalEdgeClasses readable statically from the AST
- **Claim**: Edge classes from `.out/.in/.both(label)` fold into `effectiveFromClasses` at entry construction.
- **Stress scenario**: `.out(:edgeType)` (parameterized label) and bare `.out()` (no label → base `E`).
- **Code evidence**: edge label is a `SQLExpression` method param (`SQLMethodCall.getParams:290`); `graphPath` defaults to `"E"` when null (`SQLMatchPathItem:32-36`); base `E` closure is every edge class.
- **Verdict**: FRAGILE — bare `.out()` is coarse-but-safe (over-tombstones); a parameterized label is unhandled and would build a wrong/empty closure.

#### Assumption test: update-into-match detectable per-tuple at delta-build
- **Claim**: An UPDATE that flips a record into an alias it never bound is detected and tombstones.
- **Stress scenario**: UPDATE an `Issue` that previously failed alias `i`'s WHERE so it now passes; the record is in no cached tuple.
- **Code evidence**: `reverseIndex` is keyed by RIDs already in tuples (Context terminology); a never-bound record has no anchor, so the RECORD path's `cached_at_build`/`match_after` dispatch (`DeltaBuilder.buildForRecord:139,149-179`) has no tuple to drop.
- **Verdict**: FRAGILE — tombstoning is the right answer; the trigger condition for a non-contributing record needs an explicit operational definition.

#### Assumption test (survival, INFEASIBLE leg): single-alias Etap A ORDER BY on a projected column
- **Claim**: Step 2 applies the `returnProjector` to each inject-list entry before the ORDER BY sort so a projected ORDER BY column resolves.
- **Stress scenario**: `MATCH {as:u, class:X WHERE p} RETURN u, u.name ORDER BY name` — ORDER BY on the projection alias `name`, with a post-populate CREATE of a matching `X`.
- **Code evidence**: the MATCH grammar admits ORDER BY (`YouTrackDBSql.jjt:1245+`, `SQLMatchStatement.orderBy:36`); the RECORD path sorts the inject list by `entry.getOrderBy()` (`DeltaBuilder.buildForRecord:184-187`) and the view merges by the same ORDER BY (`CachedResultSetView.computeNextRecord:282-290`). Step 2 explicitly applies the projector before the sort. The order is correct IF the projector runs before `orderBy.compare` sees the row.
- **Verdict**: HOLDS — provided step 2's "apply projector before ORDER BY sort" is implemented as written and the I4 Etap A test includes an ORDER-BY-on-projected-column row with a post-populate CREATE/UPDATE. Flagged only to ensure that test row exists; not a finding.
