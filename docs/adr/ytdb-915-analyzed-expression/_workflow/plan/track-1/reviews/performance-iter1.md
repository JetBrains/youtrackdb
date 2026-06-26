<!--MANIFEST
review: performance
track: 1
step: 1
iter: 1
range: 96b269cfcca705da0c157047561a07fb36aa01c6~1..96b269cfcca705da0c157047561a07fb36aa01c6
verdict: PASS
blocker_count: 0
finding_count: 1
findings_under_recheck: 0
evidence_base: present
cert_index: [C1, C2, C3, C4, C5]
flags: []
index:
  - id: PF1
    sev: suggestion
    anchor: "#pf1-suggestion-funccall-changed-path-copies-its-argument-list-twice"
    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExpr.java:120-129"
    cert: C5
    basis: "PSI find-usages (0 production callers); cost trace of the FuncCall changed-arg path"
-->

# Performance Review — Track 1, Step 1 (iter 1)

## Findings

The substrate meets its documented hot-path contract. Dispatch is a single
`default`-free sealed `switch` over a closed five-variant set (D1/D2): the compiler
lowers it to one type-switch, each `visitX` arm is a direct single-receiver call, and
there is no megamorphic indirection, no boxing, and no allocation on the dispatch path
(C1). `transformChildren` allocates only on the root-to-changed-leaf path and shares
every off-path subtree by reference: the `BinaryOp`/`UnaryOp` arms short-circuit on
reference identity and return the parent unchanged when no child changed, and `FuncCall`
keeps its argument-list accumulator null until the first changed element, so an
all-unchanged call reallocates nothing (C2). The five `AnalyzedExprTransform` defaults
add no per-node cost and recurse through `dispatch` so a pass's variant override is
honored (C3).

This slice ships no production consumer — PSI find-usages reports zero non-test callers
of `dispatch` or `transformChildren`, and the only non-test implementer of the visitor
hierarchy is `AnalyzedExprTransform` itself (an interface). The substrate is therefore
not on any live hot path *yet*; the evaluator (Track 4) and optimizer passes (S3+) are
the future hot-path consumers, and they inherit this substrate's allocation and dispatch
shape. The one finding below is a minor allocation cleanup on that inherited template, not
a live regression.

### PF1 [suggestion] FuncCall changed-path copies its argument list twice

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExpr.java` (lines 120-129)
- **Issue**: On the changed-argument path, `transformChildren` builds the new argument
  list as a `new ArrayList<>(args.subList(0, i))` accumulator (line 120), then wraps it in
  `List.copyOf(newArgs)` when constructing the result `FuncCall` (line 129). `List.copyOf`
  on a non-`List.of` source allocates a second backing array plus a second list object, so
  a `FuncCall` whose args changed pays two list allocations where one suffices. The
  all-unchanged path is already optimal (returns `f`, allocates nothing — C2), so this
  only affects calls a transform actually rewrites.
- **Evidence**: COST TRACE — changed `FuncCall` arm: OPERATION = build accumulator
  `ArrayList` (1 list + 1 array), append transformed args, then `List.copyOf` (1 list + 1
  array); ALLOCATIONS = 2 list objects + 2 backing arrays per changed `FuncCall` node;
  COMPLEXITY = O(args) per changed call, args typically 1-3. SCALE CHECK — at small scale
  (few function calls per query, 1-3 args each): negligible; at production scale (1M+
  queries/s through a constant-folding pass that rewrites `FuncCall` args): one extra
  short-lived young-gen list per changed call, dies immediately, minor GC pressure;
  VERDICT = MATTERS AT SCALE, minor. Note the unchanged path already stores the caller's
  original list verbatim (line 117, `f.args()`), so the record does not enforce arg-list
  immutability on its own — `List.copyOf` on the changed path is an inconsistency, not a
  guarantee the unchanged path also upholds (C5).
- **Impact**: One redundant list + backing-array allocation per *changed* `FuncCall`
  node on every transform pass. Young-gen GC pressure only; no latency or throughput
  effect at realistic per-query scale.
- **Suggestion**: Drop the double copy. Either hand the accumulator directly to the
  record (`new FuncCall(f.name(), newArgs)`) and document that transform-built arg lists
  are owned by the new node, or replace the `ArrayList` + `List.copyOf` pair with a single
  immutable construction. Whichever is chosen, align it with the unchanged path so both
  paths treat arg-list immutability the same way. Optional — this is a micro-optimization
  on a not-yet-live path and does not gate merge.

## Evidence base

Phase-4 scale-validation roster. A claim whose verdict survived the refutation check
(CONFIRMED-as-issue, or CONFIRMED-as-sound for a contract the slice meets) is compressed
to one line; a refuted or negligible-verdict claim appears in full.

#### C1: Dispatch is monomorphic and allocation-free (D1/D2 contract met)
- **Verdict:** CONFIRMED-as-sound — `dispatch` (AnalyzedExpr.java:71-79) is one `default`-free sealed `switch` over the closed five-variant permits-list; compiles to a single type-switch, each `visitX` arm is a direct single-receiver call, no boxing, no allocation, no megamorphic indirection.

#### C2: transformChildren allocates only on the changed path and shares off-path subtrees by reference (D8 contract met)
- **Verdict:** CONFIRMED-as-sound — `Var`/`Const` return self; `BinaryOp`/`UnaryOp` return the parent by reference under the `left == b.left() && right == b.right()` (resp. `operand == u.operand()`) short-circuit and build one new record only when a child changed; `FuncCall` keeps `newArgs` null until the first changed argument so an all-unchanged call allocates no list. Verified by tests (a)-(f), the right-child-only and nested-spine cases, and the no-change `FuncCall` case asserting the same arg-list instance.

#### C3: AnalyzedExprTransform defaults add no per-node cost
- **Verdict:** CONFIRMED-as-sound — the five defaults are leaf-return-self / compound-call-`transformChildren`; no captured lambda, no per-call allocation. Children recurse through `dispatch(child, t)`, so a pass's variant override is honored at each level (the intended D2/D9 entry-point indirection) without adding an indirection layer beyond the one type-switch.

#### C4: No production hot path exists in this slice (call-frequency premise)
- **Verdict:** CONFIRMED — PSI find-usages reports 0 non-test callers of `AnalyzedExpr.dispatch` and `AnalyzedExpr.transformChildren` (every usage is the substrate's own internal recursion or the unit test), and the only non-test `AnalyzedExprVisitor` implementer is `AnalyzedExprTransform` itself (an interface, no concrete pass). The substrate is greenfield; the evaluator (Track 4) and optimizer passes (S3+) are the future hot-path consumers. Findings therefore assess whether the substrate's shape satisfies the documented contract future consumers inherit, not a live regression. Lock-contention, cache-efficiency, direct-memory, I/O, and index-operation categories have no target in this slice (pure immutable data structures, no shared mutable state, no I/O) and are omitted.

#### C5: FuncCall changed-path double list copy (the one surviving finding)
- **Verdict:** CONFIRMED-as-issue — changed `FuncCall` arm allocates an `ArrayList` accumulator (line 120) and then a second list via `List.copyOf` (line 129): 2 list objects + 2 backing arrays per changed `FuncCall`. The unchanged path (line 117 stores `f.args()` verbatim) does not enforce arg-list immutability, so `List.copyOf` on the changed path is an inconsistency rather than a guarantee. Scale verdict MATTERS AT SCALE (minor): one redundant short-lived list per changed `FuncCall` per transform pass, young-gen GC pressure only. Raised as a suggestion because the substrate is the template every future pass inherits, but it does not gate merge. See PF1.
