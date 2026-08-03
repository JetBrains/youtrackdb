<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 0, suggestion: 1}
index:
  - {id: PF1, sev: suggestion, loc: RangeGlobalStepRecogniser.java:96, anchor: "### PF1 ", cert: C1, basis: "a DECLINE aborts the whole walk, so withdrawing the post-union slice also withdraws the prefix from MATCH; compile cost falls, execution loses the prefix plan and the concatenator early-stop"}
evidence_base: {section: "## Evidence base", certs: 8, matches: 1}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: REFUTED,   anchor: "#### C2 "}
  - {id: C3, verdict: REFUTED,   anchor: "#### C3 "}
  - {id: C4, verdict: REFUTED,   anchor: "#### C4 "}
  - {id: C5, verdict: REFUTED,   anchor: "#### C5 "}
  - {id: C6, verdict: REFUTED,   anchor: "#### C6 "}
  - {id: C7, verdict: REFUTED,   anchor: "#### C7 "}
  - {id: C8, verdict: REFUTED,   anchor: "#### C8 "}
flags: [CONTRACT_OK]
-->

## Findings

### PF1 [suggestion] The decline takes the whole traversal off MATCH, not only the slice

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RangeGlobalStepRecogniser.java` (lines 96-98); mechanism in `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinStepWalker.java` (lines 301-303, 331-364)

**Issue**: The walk is all-or-nothing. `dispatchAll` returns `false` on the first declining recogniser, `walk` then returns `null`, and the strategy leaves the traversal untouched — there is no partial-splice path that keeps a recognised prefix. So the shapes this step withdraws give up more than the slice. `g.V().has("name", x).out("knows").union(out(), in()).limit(10)` compiled to two MATCH plans before the change and now runs end to end on the native traverser pipeline, prefix included.

The commit's own framing supports treating this as a coverage cost rather than a free correctness win: the walker Javadoc at lines 391-397 calls `union(...).limit(n)` common enough to justify mirroring the gate into the look-ahead. A shape common enough to warrant that mirror is common enough for its withdrawal to show up in workloads.

**Evidence** (`#### C1`, `#### C2`, `#### C8`):

COST TRACE for a withdrawn shape, per query execution:

- OPERATION (compile): `UnionStepRecogniser.recognize` returns DECLINE at the look-ahead call (`UnionStepRecogniser.java:69`), before `recognisedPrefixSteps()` and before the fork loop.
- COMPILE COST: falls by N × `UnionForkHostImpl.walkFork` for an N-arm union. Each `walkFork` clones every prefix step and every child step, allocates a fresh `DefaultGraphTraversal` and `WalkerContext`, re-resolves the polymorphism flag and the schema, and builds a full `MatchPlanInputs` AST. None of that is cached: `GremlinPlanCache` keys on a fingerprint computed after the walk (`GremlinToMatchStrategy.java:439-455`) and the multi-plan carrier is never a cache entry (same file, lines 464-466), so the fork is paid on every `applyStrategies()`.
- EXECUTION COST: rises. The prefix's filters and hops run as per-traverser steps instead of inside one MATCH plan, and the concatenator's early stop is gone. `PostConcatOp.Range` left arms after the first unopened when the limit was already satisfied; native `union` feeds every arm.
- ALLOCATIONS: net reduction at compile, unmeasured change at execution.
- I/O: unmeasured. Native keeps index-backed access for a leading filter (C2), so this is not a scan-versus-seek flip.

SCALE CHECK:

- AT SMALL SCALE (100 records): negligible on both sides.
- AT MEDIUM SCALE (100K records), selective leading filter: negligible. `YTDBGraphStepStrategy` folds the leading `has()` into `YTDBGraphStep`, which issues a plan-backed query the SQL planner can index-back (C2).
- AT PRODUCTION SCALE (1M+ records), filters that are not leading, or a wide union: noticeable. Non-leading `has()` predicates filter per traverser in memory rather than inside a MATCH plan, and every arm executes instead of only those the limit needs.
- VERDICT: MATTERS AT SCALE.

**Impact**: Higher query latency on `union(...).limit(n)`, `union(...).skip(n)` and `union(...).range(a, b)` where the traversal carries filters the MATCH planner would have handled, or where the union is wide. Compile-side cost drops for the same shapes. The magnitude is not measured; the worst case I checked for — losing index access on the leading filter — does not apply.

**Suggestion**: Three parts, none of them a change to this diff.

1. Record the withdrawn family in the track's coverage ledger, so the plan's stated coverage stays accurate: `union(...)` followed by `limit(n)`, `skip(n)` or `range(a, b)` with no immediately following `count()` no longer translates at all.
2. Post-concat `order()` recovers the family. A slice after a total sort picks the same rows whichever order the arms arrived in, so `union(...).order().by(k).limit(n)` becomes translatable once post-concat sort exists (`UnionStepRecogniser` Javadoc line 28 records that `order()` after a union declines in this cut). Ties on the sort key stay arrival-order-dependent, so the recovered shape needs a unique key or an explicit tie-break. If union paging matters to consumers, sequence post-concat sort ahead of other post-concat work.
3. Leave the degenerate empty slice declining. `limit(0)` and `range(k, k)` are order-independent and are now refused, but carving them out widens the accept surface on the exact shape that just shipped a wrong answer, for a shape nobody writes (C4).

## Evidence base

#### C1 A DECLINE aborts the whole walk, so the prefix loses MATCH too — CONFIRMED

`GremlinStepWalker.dispatchAll` (lines 331-364) returns `false` on the first declining recogniser and `walk` returns `null` at lines 301-303; no partial-splice path exists, so the withdrawal is traversal-wide. Raised as PF1.

#### C2 The native fallback loses index-backed access on the prefix — REFUTED

CLAIM: with the traversal off MATCH, a leading `has()` degrades from an index seek to a full class scan, making the withdrawal an order-of-magnitude regression.

REFUTATION: the native pipeline has its own pushdown. `YTDBGraphStepStrategy.rebuildTraversal` replaces the `GraphStep` with a `YTDBGraphStep` and folds every `HasStep` that directly follows it into that step as `HasContainer`s. `YTDBGraphStep.elements` (lines 85-129) then builds a query through `YTDBGraphQueryBuilder` and runs it as a plan-backed query — the class holds a `lastExecutionPlan` field for exactly that path, and its Javadoc names "those that have indexed properties with the wanted values" as one of the cases. So `g.V().has("name", x).union(...).limit(10)` still reaches an index on the native side.

RESIDUE: the folding is positional. A `has()` that does not directly follow the `GraphStep` takes the else branch, which extracts only `hasLabel` predicates into a `YTDBHasLabelStep` and leaves the rest as an in-memory filter step. Those are the filters the MATCH planner would have placed, and they are what the withdrawal actually costs. That residue is what PF1 reports, at the reduced magnitude.

METHOD CAVEAT: read by grep and file reads, not PSI — `steroid_execute_code` times out in this repo. The claim rests on reading two files end to end rather than on a caller search, so a missed reference would not flip it.

#### C3 The mirrored predicate makes the accepted path meaningfully more expensive — REFUTED

CLAIM: a translating shape such as `union(...).limit(3).count()` now evaluates the positional predicate twice per compilation — once in `GremlinStepWalker.postUnionSuffixTranslatable` (lines 412-419) and once in `RangeGlobalStepRecogniser.recognizePostUnion` (lines 84-98) — and the duplicate work is a real cost on the path the plan wants fast.

REFUTATION: the duplicate is one `normalize()` call plus one `peek(ahead + 1)` scan, per positional slice, per compilation. `normalize` unboxes two `Long`s and allocates one `NormalizedRange` record; `selectsPositionally` adds an `instanceof` test. A post-union suffix carries at most one positional slice in any shape the recogniser accepts, since a second `PostConcatOp.Range` declines at `recognizePostUnion`'s first loop (lines 78-83). So the accepted path pays roughly three extra allocations and a few tens of array reads.

Compare that against what the same compilation already pays: `UnionForkHostImpl.walkFork` runs once per arm and clones every prefix step and every child step, allocates a `DefaultGraphTraversal`, a `WalkerContext`, a `StepStreamCursor` and a `UnionForkHostImpl`, re-resolves polymorphism and the schema, and builds a full `MatchPlanInputs` AST of SQL nodes. The mirror's overhead is several orders of magnitude below the fork it guards.

SCALE CHECK: negligible at every scale. Not reported.

#### C4 The decline is wider than the defect — REFUTED

CLAIM: the step withdraws shapes that were order-independent and therefore safe, costing coverage for nothing.

REFUTATION: I enumerated what the gate withdraws and found one order-independent case, and it is degenerate.

- `union(...).limit(n)` / `.skip(n)` / `.range(lo, hi)` with `n > 0` and `hi > lo`: genuinely positional. Withdrawn correctly.
- `union(...).limit(n).dedup()` and `union(...).limit(n).dedup().count()`: the slice picks which n rows the dedup sees, so the result depends on arrival order. Withdrawn correctly, and the diff pins both.
- `union(out()).limit(3)` with a single arm: still divergent. Concatenation order for one arm is that child's MATCH order, and MATCH promises no arrival order, so N = 1 gives no exemption. The gate does not special-case arm count, which is right.
- `union(...).limit(0)`, `range(k, k)`, `range(hi, lo)` with `hi < lo`: `normalize` maps all three to `limit 0`, which is not `noop()`, so the gate calls them positional and declines. The result is empty in any order, so these were safe. This is the one over-wide case.

The over-wide case is degenerate. `limit(0)` after a union is not a shape queries contain, and a carve-out would mean widening the accept surface — adding a `normalized.limit() == 0` branch to `recognizePostUnion` and a matching one to `selectsPositionally` — on the same predicate that just shipped a silent wrong answer. The coverage is worth less than the risk.

In the other direction the line is drawn narrower than the underlying defect, not wider: the single-plan branch still pushes `limit` into a SQL `LIMIT` (lines 67-72), and MATCH's arrival order is no more native's there than it is after a union. Whether that is a defect belongs to the correctness reviewer; it matters here only because it is the direction that preserves coverage rather than losing it.

SCALE CHECK on the `limit(0)` residue: negligible at every scale. Not reported.

#### C5 The look-ahead and the recogniser can disagree today, costing discarded forks — REFUTED

CLAIM: the mirror is written in two different vocabularies, so it can pass a shape the recogniser then declines, paying N discarded sub-walks — the exact cost the look-ahead exists to avoid.

The two spellings are real. `GremlinStepWalker` line 416 tests registry identity, `recognisers.get(next.getClass()) != CountGlobalStepRecogniser.INSTANCE`. `RangeGlobalStepRecogniser.followedByCount` (lines 109-112) tests the exact class, `next.getClass() == CountGlobalStep.class`. Those agree only while `CountGlobalStep.class` is the sole registry key mapping to `CountGlobalStepRecogniser`.

REFUTATION: it is the sole key. `PRODUCTION_RECOGNISERS` has one count entry (`GremlinStepWalker.java:179`), and the TinkerPop fork ships no count placeholder — the 20 `*Placeholder` classes in `gremlin-core-3.8.1-67860f6-SNAPSHOT.jar` include `RangeGlobalStepPlaceholder`, `TailGlobalStepPlaceholder`, `IsStepPlaceholder` and `GraphStepPlaceholder`, but nothing for `CountGlobalStep`. So the two spellings are equivalent today and no fork is wasted.

RESIDUE: the equivalence is incidental, and the registry already carries the precedent that would break it — the range recogniser has two keys, `RangeGlobalStep.class` and `RangeGlobalStepPlaceholder.class`. If a fork upgrade adds a count placeholder and someone registers it, the look-ahead would pass `union(...).limit(3).<countPlaceholder>()` and the recogniser would then decline it, restoring the N-discarded-forks cost silently. Cheapest guard is to make `followedByCount` read the registry the way the walker does, so one key list drives both. That is a robustness point, not a cost today.

SCALE CHECK: no cost at any scale under the current registry and fork. Not reported.

#### C6 The look-ahead's quadratic peek scan matters — REFUTED

CLAIM: `postUnionSuffixTranslatable` calls `cursor.peek(ahead)` with a growing `ahead`, and `StepStreamCursor.peek(int)` (lines 55-76) rescans from `position` on every call, so the scan is O(k²) in the suffix length k. The new `peek(ahead + 1)` adds another O(k) pass.

REFUTATION: k is the post-union suffix length, and the allow-list bounds it in practice. The loop stops at the first step whose class does not map to the count, range or dedup recogniser, and each of those recognisers declines a repeat of its own op — a second `PostConcatOp.Range` or a range after a count both decline at `recognizePostUnion` lines 78-83. Every shape the diff's tests exercise has k of 1 to 3, giving under ten `List.get` calls plus the new pass. Constructing a pathological k would take a hand-written chain of dozens of `dedup()` calls, which is not a shape any workload produces.

SCALE CHECK: the scan reads an in-memory step list and does not grow with graph size, so the verdict is the same at 100 and at 1M records: negligible. Not reported.

#### C7 The new test fixture slows CI — REFUTED

CLAIM: `seedLongKnowsChain` and `assertSameMultisetOnAndOff` add per-test graph setup and repeated drains that lengthen the unit-test run.

REFUTATION: the fixture is eight vertices, seven edges and one commit. `assertSameMultisetOnAndOff` drains each traversal twice with a configuration flip and restores the original value in a `finally`, and the three new or rewritten tests together run roughly fifteen traversals over that graph. The rewritten `unionThenSkipAndRangeThenCount_sliceTheConcatenation` goes from four drained traversals to five. All of it is in-memory and sub-second.

SCALE CHECK: negligible. Not reported.

#### C8 The look-ahead's pre-fork placement is not a saving, or is too expensive to pay on translating shapes — REFUTED

CLAIM (two halves, both checked): that the mirror does not actually move work earlier, and that the look-ahead is too expensive to run on every union-bearing traversal that does translate.

REFUTATION of the first half: the placement is correct and the saving is real. `UnionStepRecogniser.recognize` calls `host.postUnionSuffixTranslatable()` at line 69, after the two constant-time null checks (`boundaryAlias`, `host`) and before `recognisedPrefixSteps()` at line 73 and the per-child loop at line 91. Without the mirror, `union(...).limit(3)` would pass the look-ahead — `RangeGlobalStepRecogniser.INSTANCE` is on the allow-list at line 217 — pay a full `walkFork` per arm, accept the union, and only then decline at `recognizePostUnion`, discarding every child plan. With the mirror it declines before the first fork. The saving is N × (prefix-plus-child step clones, one `DefaultGraphTraversal` and `WalkerContext`, a polymorphism and schema resolve, and one `MatchPlanInputs` AST), per compilation, on a path with no walk-level cache.

REFUTATION of the second half: the cost on translating shapes is the same handful of array reads and three allocations quantified in C3, against a fork that is orders of magnitude larger.

VERDICT: the placement is correct and cheap. Nothing to report; recorded so the question is not re-opened.
