<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
cert_index: []
flags: [CONTRACT_OK, GREP_ONLY]
-->

## Findings

No bug, logic error, resource leak, concurrency issue, null-safety defect, or
behavior-regressing guard survived review. All five orchestrator concern areas
were traced to their supporting code and refuted as defects; the refutations are
in the Evidence base. One forward-looking note (the guard's skip of
`rebuildSideEffects`) is recorded there as safe-by-construction for Phase 1's
recognized set, not a finding.

## Evidence base

Phase-4 refutation roster. Surviving (CONFIRMED-as-issue) claims are compressed
to one line; refuted / non-passing claims are rendered in full. No claim
survived, so every entry below is a refuted hypothesis rendered in full.

Reference-accuracy note (GREP_ONLY): mcp-steroid PSI (`steroid_execute_code`)
timed out on index warmup on the first symbol query, as the orchestrator
anticipated ("`execute_code` may time out on index warmup"). `steroid_list_projects`
confirmed the open IntelliJ project matches the working tree
(`/home/sandra-adamiec/IdeaProjects/youtrackdb`; the project is displayed as
`design.md` but its `path` is the repo root). Symbol facts below therefore rest
on direct source-declaration reads of the working-tree files plus `javap`
bytecode disassembly of the vendored TinkerPop fork jar
(`io.youtrackdb:gremlin-core:3.8.1-af9db90-SNAPSHOT`), not on a PSI find-usages /
find-implementations audit. The load-bearing facts are: `DefaultTraversal.getStartStep()`
bytecode (empty-list → `EmptyStep.instance()`), `DefaultTraversal.hasNext()`
bytecode (drain → `CloseableIterator.closeIterator(this)`), `TraversalStrategies.sortStrategies`
existence, and single-instantiation-site of the `final` `YTDBMatchPlanStep`. Any
finding that hinged on "no other override / no other caller" would carry a
grep-miss caveat; none does, because no finding is reported and each refutation
rests on a declaration read or a bytecode read, not a text-pattern usage search.

#### Concern 1 — `YTDBGraphStepStrategy.apply` guard misfires or regresses folding — REFUTED

The guard is `if (traversal.getStartStep() instanceof YTDBMatchPlanStep<?, ?>) { return; }`
at `YTDBGraphStepStrategy.java:40`, placed first in `apply`, before
`isPolymorphic` and the `rebuildTraversal` / `rebuildSideEffects` fold logic.
Three sub-claims checked:

(a) **Fires only on a translated boundary.** `YTDBMatchPlanStep` is `final`
(`YTDBMatchPlanStep.java:69`) and is instantiated at exactly one production site —
`GremlinToMatchStrategy.replaceAllStepsWithBoundary` (`GremlinToMatchStrategy.java:370-378`),
reached only after the translator recognizes a shape. No normal traversal (e.g.
`g.V().hasLabel(...)`) ever holds a `YTDBMatchPlanStep`, so the guard cannot skip
folding for a legitimate un-translated traversal — the existing `hasLabel`-into-`GraphStep`
folding is untouched for every non-translated query. Verified there is no second
`new YTDBMatchPlanStep(...)` in the working tree.

(b) **Positioned before the fold logic.** The early return at line 40 precedes
`rebuildTraversal` (line 50), whose loop treats any `current instanceof GraphStep`
(line 116) as a fold target and would wrap the boundary in `new YTDBGraphStep<>(graphStep)`
(line 124) — because `YTDBMatchPlanStep extends GraphStep` (`YTDBMatchPlanStep.java:69`),
confirmed by declaration read. Without the guard the boundary would be re-wrapped
and the translation destroyed; with it, a translated traversal is left untouched.
The guard is doing real, necessary work — correct ordering alone (translator
first) is not sufficient, because `YTDBGraphStepStrategy` still runs afterward and
sees the boundary as a `GraphStep`.

(c) **Cannot misfire on empty / partial traversal.** `javap` of the fork's
`DefaultTraversal.getStartStep()` shows `steps.isEmpty()` → `EmptyStep.instance()`
(returns the singleton, never null). `EmptyStep instanceof YTDBMatchPlanStep` is
`false`, so an empty traversal does not early-return; it proceeds to
`rebuildTraversal`, whose `while (current != null && !(current instanceof EmptyStep))`
loop (line 115) immediately exits — a no-op, which is the correct behavior for an
empty step list. A partially-built traversal whose start is any non-boundary step
also evaluates the `instanceof` to `false` and folds normally. No NPE, no misfire.
Verdict: REFUTED — the guard is correct and necessary. Not a finding.

#### Concern 2 — three `applyPrior` edits drop a prior strategy or pass a duplicate to `Set.of` — REFUTED

`YTDBGraphStepStrategy.applyPrior()` is newly created returning
`Set.of(GremlinToMatchStrategy.class)` (line 187-189) — one element, no
duplicate. `YTDBGraphCountStrategy.applyPrior()` widens
`Collections.singleton(YTDBGraphStepStrategy.class)` to
`Set.of(YTDBGraphStepStrategy.class, GremlinToMatchStrategy.class)` (line 118) —
the previously-listed `YTDBGraphStepStrategy.class` is preserved and
`GremlinToMatchStrategy.class` added; the two are distinct classes, so `Set.of`
does not throw `IllegalArgumentException` on a duplicate arg.
`YTDBGraphMatchStepStrategy.applyPrior()` widens identically (line 150). No prior
strategy is dropped by either widening. The ordering the edits declare is honored
by TinkerPop's resolver: `TraversalStrategies.sortStrategies(Set)` exists in the
fork (`javap` confirmed) and topologically sorts by each strategy's
`applyPrior`/`applyPost`, so with the three half-measures naming
`GremlinToMatchStrategy` in their `applyPrior` and the translator declaring empty
ordering (`NO_ORDERING`, `GremlinToMatchStrategy.java:148-149,382-388`), the
translator runs first and the half-measures become the decline fallback.
Separately verified the two count/match strategies need no boundary guard of
their own: `YTDBGraphCountStrategy.apply` gates on
`steps.getFirst() instanceof YTDBGraphStep` (line 58) and
`YTDBGraphMatchStepStrategy.apply` on `startStep instanceof YTDBGraphStep`
(line 79) — a translated start is a `YTDBMatchPlanStep`, which extends `GraphStep`
directly, not `YTDBGraphStep`, so both gates evaluate false and both strategies
no-op on a translated traversal. Only `YTDBGraphStepStrategy`, which treats any
`GraphStep` superclass instance as a fold target, needed the guard. Verdict:
REFUTED. Not a finding.

#### Concern 3 — minimal-prefix (size-1) gate wrongly rejects or accepts — REFUTED

`GremlinStepWalker.walk` declines when `traversal.getSteps().size() > MAX_RECOGNISED_STEPS`
(`GremlinStepWalker.java:107-109`, `MAX_RECOGNISED_STEPS = 1` line 78), before any
per-step work. Checked both directions:

- **Does not wrongly reject a bare `g.V()`.** At translator time
  (`ProviderOptimizationStrategy` runs after `LazyBarrierStrategy`), a bare
  `g.V()` with no follow-up step is a single `GraphStep` — `LazyBarrierStrategy`
  injects a `NoOpBarrierStep` only between steps, and there is none to sit
  between. Size is 1, so the gate passes it. The smoke test
  `translatesBareGvExactlyOneBoundaryStepAndReturnsAllVertices` asserts exactly
  one boundary step after `applyStrategies()`, which would fail if the gate
  declined a size-1 `g.V()`; the walker unit test
  `walk_multiStepTraversal_declinesUnderMinimalPrefixGate` drives the raw
  pre-strategy `g.V().count()` (size 2) and asserts decline. Both directions
  pinned.
- **Does not wrongly accept a multi-step traversal.** Any traversal with a
  follow-up step (`g.V().out()`, `g.V().has(...)`, `g.V().count()`) is size ≥ 2
  at translator time and declines up front. The gate is strictly redundant with
  the all-or-nothing per-step loop (a follow-up step has no recognizer and would
  decline anyway), so it can only ever decline traversals the loop would also
  decline — it cannot broaden the recognized set. The over-restriction risk (a
  future multi-step D10 claim blocked by a stale constant) is documented in the
  field Javadoc ("Later tracks that widen the recognised set raise this bound")
  and is an intentional forward-guard, not a defect.
  Verdict: REFUTED. Not a finding.

#### Concern 4 — smoke tests are tautological or the metrics test masks a real double-count — REFUTED

- **Parity test is a real ON-vs-OFF comparison.** `translatorOnVsOffReturnsSameMultiset`
  runs the traversal with `QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED` set true
  then false, sorts each result's names, and asserts `assertEquals(namesOff, namesOn)`
  (test lines 333) plus the absolute multiset `List.of("Alice","Alice","Bob","Carol")`
  including a deliberate duplicate value. It also asserts opposite engagement
  (one boundary step on, a `YTDBGraphStep` start off). Not self-comparing.
- **Metrics test avoids the double-fire trap and does not mask a translation
  double-count.** Confirmed the double-fire is real and pre-existing:
  `YTDBQueryMetricsStep.close()` (`YTDBQueryMetricsStep.java:116-152`) reports on
  every call when `hasStarted` is true, with no already-reported guard, so two
  `close()` calls fire `queryFinished` twice. The test runs `q.toList()` exactly
  once and deliberately does not use try-with-resources. `javap` of
  `DefaultTraversal.hasNext()` shows drain (`hasNext()` returns false) calls
  `CloseableIterator.closeIterator(this)`, which closes the traversal — one
  drain-close, one `close()` on the metrics step, one fire. A try-with-resources
  wrapper would add a second block-exit `close()` → a second fire; the test's
  comment states this and the run structure enforces one close. The double-fire is
  identical on native and translated paths (both append the metrics step via the
  `FinalizationStrategy` after the `ProviderOptimizationStrategy` runs
  `replaceAllSteps`), so running one `toList()` isolates the translation path
  rather than papering over a translation-specific double-count. The assertions
  are substantive: `callCount == 1`, `getQuerySummary() == "smoke-metrics-summary"`
  (read from the `querySummary` `OptionsStrategy` field, `YTDBQueryMetricsStep.java:137-139`,
  decoupled from the step list), `getQuery()` contains `"V("` and not
  `"YTDBMatchPlanStep"` (rendered from `traversal.getBytecode()`, line 131, fixed
  at construction, not from the spliced boundary), non-negative duration, count 3.
  Listener/tx wiring is correct: `graph.tx()` returns a thread-local singleton
  `YTDBTransaction` (`YTDBGraphImplAbstract.java:92-93,225-226`), so
  `withQueryListener`/`withQueryMonitoringMode` on `graph.tx()` and the traversal's
  `graph.tx()` resolve to the same instance on the test thread. `countBoundarySteps`
  counts only `YTDBMatchPlanStep`, so the appended `YTDBQueryMetricsStep` does not
  inflate the count to 2. Verdict: REFUTED — the tests are genuine and the metrics
  test isolates the translation path correctly. Not a finding.
- **Note (out of dimension):** the metrics test leaves `withQueryListener` set on
  the thread-local tx and only restores the config flag; a per-test-thread listener
  leak is a test-isolation concern for the test-structure reviewer, not a
  bug/concurrency defect in the production diff.

#### Concern 5 — registration into the optimization chain is wrong — REFUTED

`registerOptimizationStrategies` adds `GremlinToMatchStrategy.instance()` to the
strategy list alongside the existing five (`YTDBGraphImplAbstract.java:79-84`).
List position is informational (the comment at lines 75-78 says so, and the
resolver sorts by `applyPrior`/`applyPost`); actual order is
resolver-driven via the three `applyPrior` edits verified under Concern 2.
`GremlinToMatchStrategy.instance()` returns a stateless `static final` singleton
(`GremlinToMatchStrategy.java:151-153,186-188`) with immutable `translator` /
`planBuilder` function seams, safely published — no unsafe-publication or shared
mutable-state hazard from registering it globally. Verdict: REFUTED. Not a finding.

#### Forward-looking note (not a finding) — the guard also skips `rebuildSideEffects`

The `YTDBGraphStepStrategy.apply` early return on a `YTDBMatchPlanStep` start
step (line 40) skips both `rebuildTraversal` and `rebuildSideEffects` (line 51).
`rebuildSideEffects` rewrites `#`-prefixed String IDs and `ReferenceVertex` /
`ReferenceEdge` side effects into `RID`s. For Phase 1's recognized set
(`g.V()` / `g.V(ids)`) this is safe by construction: those shapes carry no
side-effect steps, and even if a `withSideEffect(...)` were attached, the whole
step list is replaced by the boundary and execution flows through the MATCH plan,
which does not consume TinkerPop traversal side effects — so the native RID
fixup is irrelevant to a translated traversal. This becomes worth revisiting only
if a future track recognizes a shape that both carries RID-typed side effects and
reads them downstream; at that point the boundary/MATCH path, not
`rebuildSideEffects`, would own the fixup. No action for this step.

#### Concurrency sweep — REFUTED (no shared mutable state introduced)

All four production edits are stateless: the three strategies and the walker are
`static final` singletons with immutable fields (`GremlinStepWalker.recognisers`
via `List.copyOf`; `MAX_RECOGNISED_STEPS` a constant). The guard reads
`traversal.getStartStep()` on a traversal that is thread-confined during its own
`applyStrategies()` compilation. No new field, no new lock, no cross-thread
publication. The `YTDBMatchPlanStep` clone/plan-copy isolation and non-final
`plan` field are Step-2 concerns already reviewed (BC1–BC4 there) and unchanged
by this step. No race, deadlock, or unsafe-publication surface added.
