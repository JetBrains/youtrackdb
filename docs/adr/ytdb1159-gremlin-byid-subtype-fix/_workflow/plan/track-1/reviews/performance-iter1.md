<!--
MANIFEST
dimension: performance
prefix: PF
iteration: 1
verdict: pass
high_water_mark: 3
findings_total: 3
blockers: 0
should_fix: 0
suggestions: 3
evidence_base: 3
cert_index: [C1, C2, C3]
flags: [reference-accuracy-caveat]
index:
  - id: PF1
    sev: suggestion
    anchor: "#pf1-per-element-stream-and-list-allocation-in-the-by-id-label-filter"
    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/traversal/step/sideeffect/YTDBGraphStep.java:117-128"
    cert: C1
    basis: code-read
  - id: PF2
    sev: suggestion
    anchor: "#pf2-getallsuperclasses-allocates-a-hashset-and-may-acquire-a-schema-read-lock-per-element"
    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/traversal/step/filter/YTDBLabelMatcher.java:49"
    cert: C2
    basis: code-read
  - id: PF3
    sev: suggestion
    anchor: "#pf3-stream-anymatch-allocation-inside-test-per-predicate-evaluation"
    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/traversal/step/filter/YTDBLabelMatcher.java:61-63"
    cert: C3
    basis: code-read
-->

## Findings

### PF1 [suggestion] Per-element Stream and List allocation in the by-id label filter

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/traversal/step/sideeffect/YTDBGraphStep.java` (line 117-128)

**Issue**: The filter lambda passed to `IteratorUtils.filter` runs once per resolved
element. Each invocation builds a fresh `labelContainers.stream()` plus an `allMatch`
closure, and inside it allocates a fresh `List.of((P<? super String>) labelContainer.getPredicate())`
per label container. The singleton list and the predicate cast do not depend on the
element — they could be hoisted out of the lambda into a `List<List<P<? super String>>>`
(or a `List<P<? super String>>` per container) computed once during the partition loop
at lines 107-115, alongside `labelContainers`/`otherContainers`. The partition itself is
already correctly hoisted; only the per-container predicate wrapping leaks into the
per-element path.

**Evidence**: See Evidence base C1. COST TRACE: per element, 1 Stream + 1 `allMatch`
closure + (1 `List.of` + 1 cast) per label container. DATA SCALE: element count is
bounded by the explicit id list in `g.V(ids...)` / `g.E(ids...)` — the by-id path is the
small-N path by construction (`elements()` line 101 guards on `this.ids.length > 0`; the
unbounded class-scan path is the `else` branch and was untouched). SCALE CHECK: at N=1 to
a few hundred ids the allocation is dwarfed by the per-element storage load already paid;
VERDICT: MATTERS AT SCALE only for unusually large explicit id batches, otherwise
negligible. Reported as a suggestion because the hoist is a clean, zero-risk readability-
neutral win, not because the current cost is visible.

**Impact**: Minor reduction in young-gen allocation on the by-id traversal path; no
latency change at typical id counts.

**Suggestion**: During the existing partition loop, precompute a parallel list of
`List<P<? super String>>` (one wrapped predicate list per label container) and capture
that in the lambda, so the lambda only iterates and calls `YTDBLabelMatcher.matches`.

### PF2 [suggestion] getAllSuperClasses() allocates a HashSet and may acquire a schema read lock per element

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/traversal/step/filter/YTDBLabelMatcher.java` (line 49)

**Issue**: When `polymorphic` is set and the concrete-class test misses, `matches` walks
`schemaClass.getAllSuperClasses()`. The runtime `SchemaClassImpl.getAllSuperClasses()`
(SchemaClassImpl.java:1436) allocates a fresh `HashSet`, recursively walks the hierarchy,
and acquires/releases a schema read lock on every call; the immutable variant
(SchemaImmutableClass.java:361) allocates a `HashSet` but takes no lock. This helper is
invoked once per element per label container on the by-id path, so a polymorphic
`g.V(ids...).hasLabel("Super")` over a large id batch repeats the set allocation (and
possibly the lock acquire) per element. On the class-scan path the same helper now backs
`YTDBHasLabelStep.filter`, which runs per traverser — a higher-frequency caller — though
that path is pre-existing behavior lifted verbatim, not new cost introduced by this track.

**Evidence**: See Evidence base C2. COST TRACE: per polymorphic miss, 1 `HashSet` alloc +
hierarchy walk + (runtime path) 1 schema read lock acquire/release. ALTERNATIVE CHECK: the
hierarchy is stable within a traversal; a step-level cache of resolved class-name sets
keyed by schema class would eliminate the repeat — and the source already flags this exact
idea in the `YTDBHasLabelStep.filter` comment ("will it make sense to add a step-level
cache..."). EVIDENCE for alternative: that comment is the only existing hook; no cache
infrastructure is present, so this is net-new work with a thread-safety question the
comment itself raises. SCALE CHECK: at by-id N=1 negligible; MATTERS AT SCALE for large
polymorphic id batches and, separately, for high-cardinality class-scan `hasLabel`. VERDICT:
suggestion — the lock-acquiring runtime path is the part worth confirming if a hot
`hasLabel` profile ever shows up.

**Impact**: GC pressure and possible lock-contention on deep or wide schema hierarchies
under high-frequency polymorphic label matching; negligible for shallow hierarchies and
small id lists.

**Suggestion**: If a future profile implicates `hasLabel`, add the step-level
class-name-set cache the existing comment proposes, or have `matches` consult the immutable
schema snapshot (which skips the lock) when available. No change needed now.

### PF3 [suggestion] Stream anyMatch allocation inside test() per predicate evaluation

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/traversal/step/filter/YTDBLabelMatcher.java` (line 61-63)

**Issue**: `test()` builds `predicates.stream().anyMatch(p -> p.test(className))` on every
call. `matches` calls `test()` once for the concrete class name and, when polymorphic and
missing, once per superclass — so a `K`-deep hierarchy allocates up to `K+1` Streams plus
the captured lambda per element. For the common single-predicate `hasLabel("X")` the
predicate list has size 1, and an indexed `for` loop (or `for (P p : predicates)`) over the
list avoids the Stream and lambda allocation entirely with no readability loss.

**Evidence**: See Evidence base C3. COST TRACE: per `test()` call, 1 Stream + 1 predicate
closure; called up to `K+1` times per element where `K` = superclass count. DATA SCALE:
`predicates` is the OR-list from one `hasLabel(...)` container, usually size 1-2. SCALE
CHECK: at typical by-id N and shallow hierarchies, negligible; the `K+1` multiplier only
bites when polymorphic matching misses through a deep hierarchy over a large id batch.
VERDICT: suggestion. This is a verbatim lift of the pre-existing `YTDBHasLabelStep.test`
helper, so the track did not introduce the pattern; flagged for the same micro-optimization
opportunity now that it sits on the by-id path too.

**Impact**: Minor young-gen allocation reduction; JIT-friendlier (no megamorphic Stream
pipeline) on the label-matching hot path.

**Suggestion**: Replace the Stream with a plain `for` loop over `predicates`. Low priority,
applies to both the class-scan and by-id callers since they share this helper.

## Evidence base

#### C1 — Per-element allocation in the by-id filter lambda

CONFIRMED-as-issue (suggestion). The lambda at YTDBGraphStep.java:117-128 is the body of
`IteratorUtils.filter`, evaluated once per element produced by `getElementsByIds`; the
element count is bounded by `this.ids` (guard at line 101). The `List.of(...)` wrap and
cast are element-independent and hoistable; the partition loop above them is already
hoisted, confirming the pattern is intended and the leak is incidental.

#### C2 — getAllSuperClasses cost and the two implementations

Refutation considered: "is this a scale risk on the by-id path?" — refuted as a
should-fix, retained as a suggestion. The by-id path N is bounded by the explicit id list,
so there is no million-element scenario; the unbounded class-scan path was not modified by
this track (`else` branch, lines 129-157, unchanged). The two `getAllSuperClasses`
implementations were read directly: `SchemaClassImpl.java:1436` allocates a `HashSet` and
acquires `acquireSchemaReadLock()`/`releaseSchemaReadLock()` per call; `SchemaImmutableClass.java:361`
allocates a `HashSet` with no lock. Which one fires depends on whether the by-id element's
entity exposes an immutable snapshot or the runtime class — not determinable from the diff
alone without tracing `getRawEntity().getSchemaClass()` return types through the record
layer. The lock-acquiring path is the part that would matter under contention; flagged
conditionally rather than asserted. The step-level-cache alternative is grounded in the
existing `YTDBHasLabelStep.filter` source comment, not invented.

REFERENCE-ACCURACY CAVEAT: mcp-steroid was not reachable this session, so caller frequency
for `YTDBLabelMatcher.matches` and `getAllSuperClasses` was established by grep plus reading
the two call sites in the diff (`YTDBHasLabelStep.filter`, `YTDBGraphStep.elements` by-id
branch). A polymorphic caller of `matches` outside these two sites, or an additional
override of `getAllSuperClasses`, would not be caught by grep. The grep for
`getAllSuperClasses` returned exactly the three declarations cited; no evidence of a fourth
implementation. The frequency claims (per-element on by-id, per-traverser on class-scan) are
read directly from the control flow in the two changed files and do not depend on a caller
search.

#### C3 — Stream allocation in test()

CONFIRMED-as-issue (suggestion). `test()` at YTDBLabelMatcher.java:61-63 builds a Stream
pipeline per call; `matches` calls it 1 + (0..K) times per element. Verbatim lift of the
prior `YTDBHasLabelStep.test`, so not a regression — a pre-existing micro-pattern now also
reached via the by-id path. A `for` loop is a behavior-identical replacement.
