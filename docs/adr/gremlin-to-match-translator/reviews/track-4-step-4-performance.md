# Track 4, Step 4 — Performance Review

**Commit:** `3c363fe6f0` "Translate folded HasContainers on the start step"
**Reviewer scope:** performance only (allocation, complexity, lock contention,
JVM-friendliness). Other dimensions (correctness, crash-safety, security)
covered by sibling reviews. Reference accuracy caveat: mcp-steroid was not
available, so all line/file refs were checked via `git show` and `grep` only.

## Premises (call-frequency context)

- **P1 — `GremlinToMatchStrategy.apply` is on the per-traversal hot path.** It
  runs once per `Traversal.applyStrategies()` for every traversal a session
  produces. Under D5 there is no plan cache, so a tight loop of
  `g.V().has("name", "alice").toList()` invocations re-enters this code on
  every iteration. The structural gates short-circuit non-`g.V()` traversals
  cheaply, but the recogniser path activates for every `g.V()` shape (which
  is the dominant LDBC shape).
- **P2 — `StartStepRecogniser.recognize` runs once per `walk()` invocation
  per traversal.** The walker iterates steps and dispatches to recognisers in
  registration order; `StartStepRecogniser` is the first entry, so it is
  consulted at `stepIndex=0` on every `g.V()`-rooted traversal. Other
  recognisers in the registry early-return on `stepIndex != 0`, so the
  start-step recogniser's cost is paid even when later recognisers do the
  bulk of the work.
- **P3 — `collectFolded` walks `graphStep.getHasContainers()`.** After
  `YTDBGraphStepStrategy` runs, the typical container count for the dominant
  LDBC shape `g.V().has(propKey, value)` is 1 (one property predicate; label
  is folded only for `g.V().hasLabel(...)`). For
  `g.V().hasLabel("Person").has("name", "alice")` the count is 2. Pathological
  shapes (`g.V().has(a, x).has(b, y).has(c, z)…`) produce N containers; bounded
  by the user query, in practice 1–4.
- **P4 — Pure `g.V()` (no folded containers) is also in scope.** Every
  bare `g.V().out().…` traversal still routes through `collectFolded`,
  enters the empty-`startRids`/empty-`hasContainers` branch, and exits with
  an empty `Optional` for `idConstraint`. This is the most common shape in
  the Cucumber suite.

## Cost trace — `collectFolded` in the dominant cases

```
COST TRACE for StartStepRecogniser.collectFolded (file:line 222–280):
  CASE A: pure g.V() (no IDs, no hasContainers)
    - Optional.empty() returned for startRids → no LinkedHashSet alloc
    - for-loop body never executes (hasContainers is empty)
    - Allocates: 1 FoldedStartState record (3 fields, ~32 B)
    Total: 1 small allocation per call

  CASE B: g.V().has("name", "alice")  (1 property HasContainer)
    - Optional.empty() for startRids
    - Loop iter 1: not T.id, not T.label, falls into property branch
      - GremlinPredicateAdapter.toBooleanExpression call:
          allocates SQLBinaryCondition + SQLBaseExpression chain (~6 nodes)
      - Optional.of(translated) wrapper
      - propertyPredicate = translated.get() (no AND alloc on first hit)
    - Returns FoldedStartState
    Total: 1 record + 1 Optional + ~6 SQL AST nodes per call

  CASE C: g.V().hasLabel("Person").has("name", "alice")  (2 containers)
    - Iter 1 (label): Compare.eq + String value → narrowedClass = "Person",
      no SQL alloc
    - Iter 2 (property): adapter → ~6 nodes; propertyPredicate = result
    Total: 1 record + ~6 SQL AST nodes

  CASE D: g.V(id) (1 ID, no hasContainers)
    - Optional.of(new LinkedHashSet<>(startRids)) — 1 LinkedHashSet alloc
      with one element (HashMap backing, ~64 B amortised)
    - Loop never executes
    - Returns FoldedStartState
    Total: 1 record + 1 LinkedHashSet (16-bucket HashMap)

  CASE E: g.V(id1, id2, id3).has(T.id, eq(id4))  (intersection branch)
    - LinkedHashSet alloc for startRids (3 elements)
    - extractIdsFromPredicate alloc: another LinkedHashSet (1 element)
    - Intersection branch: NEW LinkedHashSet via copy of prior set,
      then retainAll(hcIds)
    Total: 3 LinkedHashSets + 1 record per call. Bounded by ID-list size.
```

```
COST TRACE for StartStepRecogniser.recognize after collectFolded (file:line 142–197):
  - List.copyOf(folded.idConstraint.get()) — when present, allocates
    ImmutableCollections$ListN copying the LinkedHashSet's iterator order
  - WHERE.andOptional(ridFilter, classEq, propertyPredicate):
      - varargs array of size 3 (always allocated by callsite)
      - new ArrayList<>(3) inside andOptional (line 148)
      - Then either short-circuits (size 1 → returned as-is) or allocates
        SQLAndBlock + new ArrayList<> wrapper (line 161)
```

## Comparative analysis

```
ALTERNATIVE CHECK for collectFolded — Optional<Set<RecordIdInternal>>:
  CURRENT: Optional<Set<RecordIdInternal>> with Optional.empty() and
    Optional.of(new LinkedHashSet<>(startRids)) on every recognize() call,
    even when there are no folded HasContainers and no IDs.
  ALTERNATIVE: nullable Set<RecordIdInternal> (use null sentinel for
    "no constraint"); short-circuit the LinkedHashSet allocation when
    graphStep.getHasContainers() is empty (the LinkedHashSet is only ever
    intersected if a T.id container appears; for pure g.V(id1, id2) the
    set is materialised once and immediately copied into List<> via
    List.copyOf, so the LinkedHashSet is pure waste in that path).
  EVIDENCE: nullable Set is the existing convention elsewhere in the file —
    normaliseIds returns @Nullable List, narrowedClass is @Nullable String,
    propertyPredicate is @Nullable. Optional was introduced only here.
  IMPROVEMENT: eliminates 1 Optional allocation per recognize() call, plus
    1 LinkedHashSet allocation per `g.V(ids)` call when no T.id-keyed
    HasContainer is present (i.e. the common case).
  TRADEOFF: minor — Optional makes the "absent vs empty-intersection"
    distinction explicit, but the same distinction is already encoded by
    null vs empty-set, and the recogniser already uses null sentinels.

ALTERNATIVE CHECK for collectFolded fast-exit when hasContainers is empty:
  CURRENT: even when graphStep.getHasContainers() is empty, the method
    still allocates the Optional<Set> wrapper (LinkedHashSet for startRids
    when non-empty, Optional.empty() otherwise) and the FoldedStartState
    record before returning.
  ALTERNATIVE: detect empty-HasContainers path early, skip building
    FoldedStartState, and return a shared sentinel instance representing
    "no folded constraints, just startRids".
  EVIDENCE: current code instantiates a fresh FoldedStartState every call;
    its three fields are independent so a single shared sentinel doesn't
    work directly, but a `FoldedStartState.empty(startRids)` factory could
    return a cached instance for the (empty-startRids, no folded) shape.
  IMPROVEMENT: eliminates 1 record allocation + 1 Optional allocation per
    pure-g.V() traversal (the most common shape in the Cucumber suite).
  TRADEOFF: minor readability hit. Worth pursuing only if Track 12's
    baseline shows allocation pressure here.

ALTERNATIVE CHECK for andOptional varargs alloc:
  CURRENT: WHERE.andOptional(ridFilter, classEq, propertyPredicate) allocates
    a 3-element SQLBooleanExpression[] at the call site, then
    andOptional itself allocates a new ArrayList<>(3) and copies the
    non-null entries; for ≥2 kept entries it allocates a third
    ArrayList<>(kept) (line 161) and the SQLAndBlock.
  ALTERNATIVE: provide a dedicated overload `andOptional(SQLBooleanExpression a,
    SQLBooleanExpression b, SQLBooleanExpression c)` that hand-counts non-nulls
    and short-circuits without varargs/ArrayList. Or expose
    `andOptional(SQLBooleanExpression a, SQLBooleanExpression b)` and chain.
  EVIDENCE: the call site is fixed at 3 operands (ridFilter, classEq,
    propertyPredicate) — varargs is overkill. The two-arg shape is the
    other documented use (start-step folded merge from prior tracks).
  IMPROVEMENT: eliminates 1 array allocation + 1 ArrayList allocation
    per recognize() call (≈ 2 small objects per traversal). Per-traversal
    saving is small but additive across the LDBC tight loop.
  TRADEOFF: API surface grows (one extra overload). Consider deferring
    until Track 12 measures this.

ALTERNATIVE CHECK for nested AND of property predicates:
  CURRENT: when multiple property HasContainers are present, the loop
    builds a left-deep AND chain via `WHERE.and(propertyPredicate,
    translated.get())`. For N containers this is N-1 binary AND nodes —
    a left-deep chain.
  ALTERNATIVE: collect into a List and call `WHERE.and(list.toArray(...))`
    once at the end, producing a flat N-way SQLAndBlock.
  EVIDENCE: SQLAndBlock supports N sub-blocks (see MatchWhereBuilder.combine
    callers). Left-deep chain still works but the planner traverses N
    nested blocks rather than one flat block.
  IMPROVEMENT: O(N) AST nodes vs O(N) for the flat shape (same asymptotic);
    saves N-1 SQLAndBlock allocations and produces a friendlier shape for
    the planner's filter-walking code in MatchExecutionPlanner. For
    N ≤ 4 (typical LDBC shapes) the saving is negligible.
  TRADEOFF: trivial code change; flat shape is also more readable in
    plan pretty-print. Worth pursuing if Track 12 finds AND-chain depth
    matters.

ALTERNATIVE CHECK for GremlinPredicateAdapter call cost:
  CURRENT: every non-T.id-non-T.label container hits
    GremlinPredicateAdapter.INSTANCE.toBooleanExpression(predicate, key),
    which dispatches via instanceof + switch on Compare/Contains/TextP and
    allocates 5–8 AST nodes per simple predicate. The adapter is stateless
    and the singleton is shared, so dispatch overhead is bounded.
  ALTERNATIVE: memoise per (P shape, key) pair. The (P, key) pair is the
    cache key the spec defers to Phase 2.
  EVIDENCE: D5 explicitly defers plan caching; per-predicate memoisation
    is a finer-grained variant of the same idea and runs into the same
    parameter-extraction discipline. Not appropriate for Phase 1.
  IMPROVEMENT: would amortise per-adapter-call alloc cost. Defer to
    Track 12 baseline + Phase 2 cache work.
  TRADEOFF: out of scope for Track 4.
```

## Scale validation

```
SCALE CHECK for the collectFolded allocation pattern:
  AT SMALL SCALE (one-shot test query): negligible — single-digit object
    allocations per traversal, GC handles trivially.
  AT MEDIUM SCALE (LDBC IS/IC queries running once each): negligible —
    each traversal pays 1–2 small allocations on top of the existing
    start-step cost.
  AT PRODUCTION SCALE (LDBC tight loop, 1M+ g.V().has(...) per minute):
    measurable. ~2 extra allocations per traversal × 1M traversals/min =
    2M allocations/min in Eden. GC pressure increase but well within
    young-gen capacity. Plan-cache absence (D5) dwarfs this cost; once
    Phase 2 caching lands, the per-call allocations become more visible.
  VERDICT: MATTERS AT SCALE — but secondary to D5's no-cache cost. Track 12
    baseline should attribute regression to (a) no plan cache and
    (b) per-call IR construction, in that order; the allocations called
    out here are only a meaningful fraction once (a) is solved.
```

## Findings

### Suggestion (defer to Track 12 baseline)

#### PF1 — `Optional<Set<RecordIdInternal>>` allocates an Optional wrapper on every recognize() call

- **File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/StartStepRecogniser.java` (line 205–209, 222–225)
- **Issue:** The `FoldedStartState.idConstraint` field is typed as
  `Optional<Set<RecordIdInternal>>`. Every `recognize()` call constructs
  either `Optional.empty()` (one cached singleton — no real cost) or
  `Optional.of(new LinkedHashSet<>(startRids))` (allocates an
  `Optional` wrapper plus the set). The rest of the file consistently uses
  `@Nullable` for absent values (`narrowedClass`, `propertyPredicate`,
  `normaliseIds`'s return), so this is the only `Optional`-typed field on
  the hot path.
- **Evidence:** COST TRACE Case D / Case E show the LinkedHashSet alloc
  occurs even when no folded T.id container is present and the set is
  immediately copied into a `List` via `List.copyOf` at line 157. The
  Optional itself is a thin wrapper but the `@Nullable Set` alternative is
  zero-alloc and matches the file's existing convention.
- **Impact:** ~1 small object per `g.V(ids)` recognise call (~24 B Optional
  + 64 B HashMap backing for the LinkedHashSet). At LDBC tight-loop scale
  this is measurable but secondary to the absent plan cache (D5). Eden
  pressure only.
- **Suggestion:** Change `idConstraint` to `@Nullable Set<RecordIdInternal>`
  (keep null = "no constraint", non-null = the constraint set). Skip
  building the `LinkedHashSet` when `graphStep.getHasContainers()` is empty
  AND `startRids.size() ≤ 1` — the set only matters for intersection, and
  for sizes 0/1 the set is built and immediately discarded (replaced by
  `List.copyOf`). Defer empirical confirmation to **Track 12**.

#### PF2 — `andOptional(...)` varargs + ArrayList allocation on every recognize() call

- **File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/builder/MatchWhereBuilder.java` (line 144–163), called from `StartStepRecogniser.java:178`
- **Issue:** `WHERE.andOptional(ridFilter, classEq, propertyPredicate)`
  always allocates: (a) a 3-element `SQLBooleanExpression[]` for varargs;
  (b) a `new ArrayList<>(3)` inside `andOptional` (line 148); (c) for the
  ≥2-kept case, a third `new ArrayList<>(kept)` plus the `SQLAndBlock`
  (line 160–161). The fixed call shape (always 3 operands) makes the
  varargs/ArrayList overhead unnecessary.
- **Evidence:** COST TRACE shows 2 transient allocations per recognise()
  call regardless of whether any operand is non-null. The fixed-arity
  caller in `StartStepRecogniser.recognize` is the only consumer in this
  step; the helper's other consumers (other recognisers in Tracks 4–10)
  also have fixed arities at their call sites, per the implementation plan.
- **Impact:** ~2 small allocations per traversal that goes through
  `recognize()`. At LDBC scale negligible compared to the SQL AST node
  allocations elsewhere; Eden pressure only.
- **Suggestion:** Add a dedicated `andOptional(SQLBooleanExpression a,
  SQLBooleanExpression b, SQLBooleanExpression c)` overload that
  hand-counts non-nulls and produces the empty/single/double/triple cases
  inline (no varargs, no ArrayList). Keep the varargs version for callers
  that genuinely need variable arity. Worth doing only if **Track 12**
  baseline flags `andOptional` allocations; otherwise leave as-is.

#### PF3 — Allocation of `FoldedStartState` for the pure-`g.V()` case

- **File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/StartStepRecogniser.java` (line 205–209, 222–225, 279)
- **Issue:** Every `recognize()` call — including the most common pure
  `g.V()` shape with no IDs and no folded HasContainers — allocates a
  `FoldedStartState` record. The record carries three fields all in their
  "absent" state for that case (`Optional.empty()`, `null`, `null`).
- **Evidence:** COST TRACE Case A shows 1 record allocation per pure-g.V()
  call. The `EdgeTraversalEquivalenceTest`'s 13 baseline cases plus the
  Cucumber suite's ~1900 scenarios mostly take this path (any traversal
  starting `g.V()` with no folded containers).
- **Impact:** 1 record allocation (~32 B) per non-declined `g.V()`-rooted
  traversal. Negligible per-call but additive at LDBC scale. Eden pressure
  only.
- **Suggestion:** Provide a static `FoldedStartState EMPTY` sentinel for
  the (Optional.empty(), null, null) case and short-circuit `collectFolded`
  to return it when `startRids.isEmpty() && graphStep.getHasContainers().isEmpty()`.
  Combine with PF1 (drop the Optional) to make the sentinel a true
  zero-state-fields singleton. Worth doing only if **Track 12** baseline
  flags this hot path.

#### PF4 — Left-deep AND chain for multi-property folded predicates

- **File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/StartStepRecogniser.java` (line 272–276)
- **Issue:** When N folded property HasContainers are present, the loop
  builds a left-deep AND tree:
  `WHERE.and(WHERE.and(WHERE.and(p1, p2), p3), p4)`. This produces N-1
  nested `SQLAndBlock` nodes versus a single flat N-child `SQLAndBlock` if
  the predicates were collected and AND-merged once at the end.
- **Evidence:** Inspection of `MatchWhereBuilder.and` (line 122–124) shows
  it accepts varargs and produces a single `SQLAndBlock` with N sub-blocks
  for N≥2 inputs. The left-deep loop shape ignores this capability.
- **Impact:** O(N) extra `SQLAndBlock` allocations and a deeper plan AST
  for queries like `g.V().has(a,x).has(b,y).has(c,z)`. For typical N≤4
  the impact is sub-microsecond per traversal; the planner's filter-walking
  code traverses both shapes equivalently.
- **Suggestion:** Collect the per-container `SQLBooleanExpression`s into a
  `List<SQLBooleanExpression>` inside `collectFolded`, then call
  `WHERE.and(list.toArray(new SQLBooleanExpression[0]))` once at the end.
  Pretty-print readability also improves. Defer to **Track 12** if
  empirical evidence is wanted; otherwise this is a small drive-by
  improvement worth doing in the next iteration.

### Notes (no action needed)

- **`extractIdsFromPredicate` LinkedHashSet allocations** — these are
  required for the intersection semantics and bounded by the user-supplied
  ID list size. Intersection via `retainAll` is O(n+m) on LinkedHashSet,
  appropriate. No optimisation opportunity.
- **`GremlinPredicateAdapter.INSTANCE` dispatch** — instanceof + switch on
  Compare/Contains/TextP enums is a JIT-friendly shape (the switch on an
  enum compiles to a tableswitch, not a megamorphic call). Per-call
  allocation is the per-AST-node cost which is unavoidable without
  caching (D5 defers cache to Phase 2). No action.
- **`List.copyOf(folded.idConstraint.get())` at line 157** — `List.copyOf`
  on a `LinkedHashSet` allocates a new immutable list. Necessary because
  the call sites downstream (`buildRidInExpression`, `aliasRids.put`)
  expect a `List`. If PF1 is adopted, this copy still happens because the
  set must be size-checked. Could be avoided with a custom helper but the
  saving is one allocation; not worth the helper.
- **`getDeclaredField` reflection** — Step 1 lifted the cached reflection
  into `SqlInOperatorBinding`; spot-checked `StartStepRecogniser` and
  confirmed the only reflection caller is `buildRidInExpression` →
  `SqlInOperatorBinding.setOperator`, which uses the cached `Field`. **No
  regression.**
- **No new stream usage in production code.** The diff's only stream usage
  is in tests (`admin.getSteps().stream().anyMatch(...)`). Production
  paths use plain for-each.
- **No autoboxing on hot paths** observed. `RecordIdInternal` and `String`
  are reference types; `Compare` / `Contains` / `T.id` / `T.label` are enum
  references. The `predicate.getValue()` return is `Object` (already
  boxed by the caller).
- **Lock contention** — none added or modified. The recogniser is
  stateless and uses no locks; `WalkerContext` is a per-traversal object.
- **Cache efficiency / direct memory / I/O** — none of these dimensions
  apply: the recogniser does no I/O, no page reads, no direct buffer
  allocations.

## Summary

The diff adds modest per-call allocation overhead on the per-traversal hot
path, dominated by the `Optional<Set<RecordIdInternal>>` wrapper, the
`andOptional` varargs/ArrayList round-trip, and the `FoldedStartState`
record allocation for the empty case. **All findings are suggestion-tier
and properly deferred to Track 12's perf baseline.** None are blockers and
none are likely to surface as regressions in any benchmark before Phase 2
plan caching lands — at which point the per-call allocation cost becomes
proportionally more visible.

Confirmed non-regressions: no new reflection on the hot path, no new
stream usage in production code, no boxing, no lock contention, no I/O,
no direct memory allocation. The cached `SQLInCondition.operator`
reflection (lifted in Step 1) remains the only reflection on this path
and is correctly cached.

## Findings summary table

| ID  | Severity   | Location                                                  | Defer to Track 12 |
| --- | ---------- | --------------------------------------------------------- | ----------------- |
| PF1 | suggestion | `StartStepRecogniser.java:205-209, 222-225` (Optional)    | yes               |
| PF2 | suggestion | `MatchWhereBuilder.java:144-163` (varargs/ArrayList)      | yes               |
| PF3 | suggestion | `StartStepRecogniser.java:205-209` (FoldedStartState alloc) | yes             |
| PF4 | suggestion | `StartStepRecogniser.java:272-276` (left-deep AND chain)  | optional          |
