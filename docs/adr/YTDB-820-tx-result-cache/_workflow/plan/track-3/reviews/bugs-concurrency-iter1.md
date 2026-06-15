<!--
MANIFEST
dimension: bugs-concurrency
step: 3-1
iteration: 1
commit_range: 2ca7dabc1764657778ead255c823932f192f209b~1..2ca7dabc1764657778ead255c823932f192f209b
verdict: changes-requested
counts: { blocker: 0, should-fix: 2, suggestion: 1 }
evidence_base: complete
cert_index: [C1, C2, C3]
flags: []
index:
  - { id: BC1, sev: should-fix, anchor: bc1, loc: "ShapeClassifier.java:265-292,302-319", cert: C1, basis: "AST accessors (getWhileCondition/getMaxDepth/isOptional on SQLMatchFilter); design.md:329 MATCH_TUPLE_MULTI definition; DatabaseSessionEmbedded.java:812-815 current uncached routing" }
  - { id: BC2, sev: should-fix, anchor: bc2, loc: "ShapeClassifier.java:265-292", cert: C2, basis: "vertexNodeForcesK0None reads only node.getFilter(); SQLMatchFilter.getWhileCondition() never consulted; design.md:332 cites WHILE conditions as in-scope for AST walks" }
  - { id: BC3, sev: suggestion, anchor: bc3, loc: "ShapeClassifier.java:265-292", cert: C3, basis: "SQLMatchFilter.isOptional(); SQLMatchPathItem.traversePatternEdge:194-196 null-binding for optional node" }
-->

## Findings

### BC1 [should-fix] Variable-depth traversal (`while:` / `maxDepth:`) is not gated, so a transitive-closure MATCH classifies MATCH_TUPLE_MULTI

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/ShapeClassifier.java` (lines 265-292, 302-319)

**Issue**: `classifyMatch` and its helpers gate on SKIP/LIMIT, GROUP BY/UNWIND/DISTINCT/NOT MATCH, RETURN mode, vertex `class:`, edge label, cross-alias WHERE, subquery WHERE, and link-deref WHERE — but nothing inspects a path item's `while:` condition or `maxDepth:` bound. A statement such as

```
MATCH {as:i, class:OUser}.out('member'){as:p, class:OUser, maxDepth:3} return i, p
```

passes every existing gate (static labels, alias-keyed RETURN, no cross-alias / link-deref / subquery in the `where:`) and classifies as `MATCH_TUPLE_MULTI`. A bounded- or while-bounded traversal produces a transitive-closure tuple set whose membership depends on multi-hop reachability. The `MATCH_TUPLE_MULTI` floor reconciles a vertex DELETE only through `reverseIndex`, which maps a RID to the tuples that *directly* hold it; deleting an *intermediate* vertex on a multi-hop path breaks reachability for downstream tuples that do not contain the deleted RID, so the floor cannot drop them. With no version backstop on `MATCH_TUPLE_MULTI`, that is a silent stale-result emission — exactly the failure class this gate exists to prevent (Concrete Steps step 1: "the no-backstop floor's first gate — a missed shape silently serves stale results").

**Evidence**: `SQLMatchFilter` exposes `getWhileCondition()` (SQLMatchFilter.java:97-105), `getMaxDepth()` (155-163), and `isOptional()` (165-172) — all real, parseable per-node features (`SQLMatchFilterItem` fields `whileCondition`/`maxDepth`/`optional`, lines 18-21). `design.md:329` defines MATCH_TUPLE_MULTI purely in terms of node count / edges / `class:` / cross-alias / link-deref / SKIP-LIMIT; it never restricts recursive or variable-depth traversal, and the Concrete-Steps step-1 enumeration omits it too, so the gap is in both plan and implementation. `classifyMatch` returns `MATCH_TUPLE_MULTI` (ShapeClassifier.java:238) for this shape.

**Refutation considered**: (1) *Intercepted upstream?* A `while:($depth < 3)` or `$currentMatch`-referencing while-condition is caught by `NonDeterministicQueryDetector` (design.md:332 lists `$depth`/`$currentMatch` and states the walk visits WHILE conditions). But `maxDepth:3` (literal) and `while:(title = ?)` (no context var) are deterministic and reach `classify` (DatabaseSessionEmbedded.java:795-798 runs the detector before `classify`; a literal-bounded depth survives it). (2) *Floor handles closures?* `reverseIndex` is direct-membership only (design.md:513), so multi-hop reachability is unreconcilable — confirmed. (3) *Harmless now?* At this step MATCH_TUPLE_MULTI routes to `executeUncached` (DatabaseSessionEmbedded.java:812-815), so the stale-result bug is latent, not live — it materializes when step 5 wires the view path. CONFIRMED as a gate gap; severity should-fix because it is latent at this commit but is precisely the class of miss the step is chartered to close.

**Suggestion**: Add a gate in `vertexNodeForcesK0None` (or a sibling per-item check in `matchExpressionForcesK0None`) that routes to `K0_NONE` when a path item carries a `while:` condition (`item.getFilter().getWhileCondition() != null`) or a `maxDepth:` (`getMaxDepth() != null`). Over-approximating these to K0_NONE is correctness-safe and matches the method's stated "deliberately broad" stance. Add a routing test for `maxDepth:` and a deterministic `while:`. If the team prefers to defer, record it as a tracked follow-up before step 5 wires the view path, since that is where it stops being latent.

### BC2 [should-fix] The `while:` condition WHERE escapes the link-deref, cross-alias, and subquery gates

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/ShapeClassifier.java` (lines 265-292)

**Issue**: `vertexNodeForcesK0None` reads the node's predicate via `node.getFilter()` (line 274), which on a `SQLMatchFilter` returns only the `where:` clause (`SQLMatchFilter.getFilter()`, SQLMatchFilter.java:71-79). The `while:` predicate is a separate `SQLWhereClause` reachable only through `getWhileCondition()` (SQLMatchFilter.java:97-105), and nothing in `classifyMatch` consults it. As a result the subquery gate (`subtreeHasSubquery`), the cross-alias gate (`whereReferencesOtherAlias`), and the link-deref gate (`whereHasLinkPathDeref`) all run against the `where:` predicate but never against the `while:` predicate. A pattern such as

```
MATCH {as:i, class:OUser}.out('member'){as:p, class:OUser, while:(assignee.name = ?)} return i, p
```

carries a link-path dereference into an out-of-pattern class inside the `while:` predicate; it passes every gate and classifies `MATCH_TUPLE_MULTI`. The dereferenced record's mutation is class-filtered out of the delta build (the same hazard the `where:`-side link-deref gate was added to close), so once the view path lands it serves a stale tuple set with no version backstop.

**Evidence**: `getFilter()` returns the first item's `filter` field only (SQLMatchFilter.java:71-79); `getWhileCondition()` is the parallel accessor for the `while:` predicate (97-105), never called from `ShapeClassifier`. The link-deref / cross-alias / subquery walks are invoked exclusively on the `getFilter()` result at lines 281, 286, 291.

**Refutation considered**: (1) *Does the `while:` WHERE reach a subquery / link-deref in practice?* The grammar permits a full `SQLWhereClause` in `while:`, structurally identical to `where:`, so the same hazards apply. (2) *Intercepted upstream?* Only the non-deterministic subset (`$matched`, `$depth`) is caught by `NonDeterministicQueryDetector`; a plain `assignee.name = ?` link-deref or an `IN (SELECT …)` subquery in `while:` is deterministic and survives to `classify`. (3) *Harmless now?* Latent for the same reason as BC1 (MATCH_TUPLE_MULTI is uncached at this commit). CONFIRMED as a gate omission; distinct mechanism from BC1 (BC1 is the missing variable-depth-shape gate, BC2 is the existing gates not being applied to the `while:` predicate even on a single bounded hop).

**Suggestion**: In `vertexNodeForcesK0None`, after handling `node.getFilter()`, also run `subtreeHasSubquery` / `whereReferencesOtherAlias` / `whereHasLinkPathDeref` against `node.getWhileCondition()` when non-null — or, if BC1 is fixed by routing any `while:`-bearing node straight to K0_NONE, BC2 is subsumed (a node with no `while:` cannot reach a `while:`-side predicate). Resolving BC1 with a presence check on `getWhileCondition()` closes both. Add a `while:(assignee.name = ?)` routing test mirroring `matchLinkPathDerefWhereClassifiesAsK0None`.

### BC3 [suggestion] An `optional:` node binds null; verify the alias-keyed tuple assumption tolerates it before the view path lands

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/ShapeClassifier.java` (lines 265-292)

**Issue**: `optional:true` on a pattern node is a parseable feature (`SQLMatchFilter.isOptional()`, SQLMatchFilter.java:165-172) and is not gated. An optional node that fails to match binds a null value into the tuple (`SQLMatchPathItem.traversePatternEdge` returns `Collections.emptySet()` for the matched-but-null case, lines 194-196), so a tuple from an optional-bearing MATCH can carry a null alias binding. The per-tuple delta path and `reverseIndex` are documented around alias→RID bindings (design.md:512-513); a null binding has no RID to index. This step only classifies, so there is no bug here yet, but `optional:` is the kind of shape that should be confirmed reconcilable (or routed to K0_NONE) before step 5 wires the view path.

**Evidence**: `isOptional()` exists and is unreferenced by the cache layer (no hit in `core/.../executor/cache/` or `DatabaseSessionEmbedded`); `traversePatternEdge` produces an empty/null candidate set for an unmatched optional node.

**Refutation considered**: Not a defect at this commit (MATCH_TUPLE_MULTI is uncached, DatabaseSessionEmbedded.java:812-815). Whether the partial-Etap-B floor handles a null binding is a step 3-4 question, not a step 1 one. Recorded as a suggestion so the optional-node shape is explicitly decided (gate to K0_NONE, or test the null-binding reconcile path) rather than silently inheriting MATCH_TUPLE_MULTI.

**Suggestion**: Decide the `optional:` shape explicitly when wiring the view path: either add it to the K0_NONE gate (cheapest, matches the conservative stance) or add a null-binding tuple to the step 4 delta-builder matrix. No change required in this step.

## Evidence base

#### C1 — Variable-depth traversal reaches `classify` and is unreconcilable by the floor

CONFIRMED-as-issue (survived refutation): `maxDepth:`/literal-`while:` shapes are deterministic, survive `NonDeterministicQueryDetector` (DatabaseSessionEmbedded.java:795-798), pass every existing gate, and return `MATCH_TUPLE_MULTI` (ShapeClassifier.java:238); the `reverseIndex` floor is direct-membership-only (design.md:513) so a multi-hop intermediate DELETE cannot be reconciled. Latent at this commit because MATCH_TUPLE_MULTI routes uncached (DatabaseSessionEmbedded.java:812-815); becomes live at step 5. AST accessors verified present: `SQLMatchFilter.getWhileCondition()` (97-105), `getMaxDepth()` (155-163). All 36 `ShapeClassifierTest` cases pass at this commit (surefire: Tests run 36, Failures 0), confirming the *tested* gates work — the gap is in untested shapes.

#### C2 — `while:` predicate is never passed to the link-deref / cross-alias / subquery gates

CONFIRMED-as-issue (survived refutation): `vertexNodeForcesK0None` operates only on `node.getFilter()` (ShapeClassifier.java:274, 281, 286, 291), which returns the `where:` clause (`SQLMatchFilter.getFilter()`, SQLMatchFilter.java:71-79). `getWhileCondition()` (97-105) is the separate accessor for the `while:` predicate and is not referenced anywhere in `ShapeClassifier`. A deterministic link-deref / subquery inside `while:` therefore bypasses gates that the equivalent `where:` predicate would trip; the positive test `matchLinkPathDerefWhereClassifiesAsK0None` covers only the `where:` side.

#### C3 — Optional-node null binding is ungated; reconcilability undecided

Not a defect at this commit. `SQLMatchFilter.isOptional()` (165-172) is parseable and unreferenced by the cache layer; `SQLMatchPathItem.traversePatternEdge` (194-196) yields a null/empty binding for an unmatched optional node, which has no RID for `reverseIndex` (design.md:512-513). Flagged so the shape is explicitly decided before the step-5 view path, not silently admitted to MATCH_TUPLE_MULTI.

Reference-accuracy note: mcp-steroid was reachable and the `youtrackdb` project confirmed open (`steroid_list_projects`). The "unreferenced by the cache layer" claims for `getWhileCondition`/`getMaxDepth`/`isOptional` are grep-based scoped reads over `core/.../executor/cache/` and `DatabaseSessionEmbedded.java` (orientation reads of a 2-file diff), not a full-project PSI find-usages; the findings do not hinge on a project-wide usage count — they hinge on these accessors being absent from `ShapeClassifier.classifyMatch` and its helpers, which is verified directly against the read source.
