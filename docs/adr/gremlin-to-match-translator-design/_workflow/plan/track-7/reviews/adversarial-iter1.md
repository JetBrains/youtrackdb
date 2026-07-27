<!-- MANIFEST
findings: 5   severity: {blocker: 0, should-fix: 3, suggestion: 2}
index:
  - {id: A1, sev: should-fix, loc: "track-7.md:23,32,50; YTDBMatchPlanStep.java:130,525,571,658,686", anchor: "### A1 ", cert: C3, basis: "Row projection is coupled to the mutable per-arming armingGraph field (set :348, nulled on release/clone), so 'projection + ResultShaping read' is not the self-contained liftable unit the track frames; a projector capturing a graph at construction gets null or the wrong clone's graph — pin graph-per-arming injection."}
  - {id: A2, sev: should-fix, loc: "track-7.md:52,64; fork jar FoldStep/UnfoldStep/ReverseStep/TailGlobalStep", anchor: "### A2 ", cert: C4, basis: "The four Track 9 ops span three iteration-cardinality classes (1→1 map, 1→N flat-map, N→1/window drain — jar-confirmed supertypes), but the framework is verified only with no-op placeholders, which naturally freeze a row-mapper op contract that cannot express fold/unfold/tail; pin the op contract as a stream-stage with a cardinality-changing placeholder."}
  - {id: A3, sev: should-fix, loc: "track-7.md:5,64; YTDBMatchPlanStep.java:275-286", anchor: "### A3 ", cert: C6, basis: "An eager collect-apply-emit stage with an empty op list passes every acceptance line (same multiset, same output type) while destroying per-row laziness — O(N) first-result latency and memory on partial consumption, invisible to the toList-collecting equivalence suites and untested by any positive pull-count assertion; require structural bypass plus a laziness pin."}
  - {id: A4, sev: suggestion, loc: "track-7.md:20-26 (Decision Log); GremlinPlanFingerprint.java:46-53", anchor: "### A4 ", cert: C5, basis: "The ordered carrier is fingerprint-exempt by construction (fingerprint covers MatchPlanInputs only; the walker re-runs on every cache hit and the boundary is built fresh from translation.shaping()), but the exemption decision — pre-split A4's pin — survives in none of the three post-split track files; record it so Track 9 neither over- nor under-keys."}
  - {id: A5, sev: suggestion, loc: "track-7.md:52,80", anchor: "### A5 ", cert: C1, basis: "The carrier+framework allocation to Track 7 survives the move-to-Track-9 alternative (Track 9 is at its ceiling; a late retrofit would edit three emission loops across two shipped tracks), but the track file never states this rationale — record it so the decomposer does not re-litigate or drop the framework half."}
evidence_base: {section: "## Evidence base", certs: 7, matches: 4}
cert_index:
  - {id: C1, verdict: HOLDS,         anchor: "#### C1 "}
  - {id: C2, verdict: HOLDS,         anchor: "#### C2 "}
  - {id: C3, verdict: BREAKS,        anchor: "#### C3 "}
  - {id: C4, verdict: BREAKS,        anchor: "#### C4 "}
  - {id: C5, verdict: HOLDS,         anchor: "#### C5 "}
  - {id: C6, verdict: CONSTRUCTIBLE, anchor: "#### C6 "}
  - {id: C7, verdict: INFEASIBLE,    anchor: "#### C7 "}
flags: [CONTRACT_OK]
-->

# Adversarial review — Track 7 (post-split), iteration 1

Scope per spawn: track realization only — (1) scope/sizing, (2) cross-track-episode reality, (3) invariant violation. Builds on the post-split technical (T1–T4) and risk (R1–R3) reviews; their verified facts (class shape, sole construction site, two emission paths, the `getDeclaredField("plan")` breakage, the base-granularity seam) are taken as established and not re-derived.

Verdict summary: no blocker and no skip — the split track is the right unit and its two deliverables are buildable. Three should-fixes attack what the prior reviews' framing left open: the "projection + shaping read" unit the base lifts is coupled to the mutable per-arming `armingGraph` field, which neither the abstract-base nor the composed-projector wording accounts for (A1); the ordered-op framework's verification plan (no-op placeholders) cannot prove the op contract hosts Track 9's three cardinality classes, jar-confirmed as map / flat-map / drain (A2); and the "no-op when the op list is empty" acceptance is satisfiable by an eager drain that silently destroys the boundary's per-row laziness (A3). Two suggestions record decisions the split dropped or never wrote down (A4 fingerprint exemption, A5 allocation rationale). The sizing band (~10–14 files) survives challenge, as does keeping the carrier in this track rather than Track 9 (C1, C2).

**Tooling caveat.** mcp-steroid PSI was reachable but non-functional this session (kotlinc cold-compile exceeds the ~60s MCP HTTP limit; confirmed by both prior post-split reviewers after retries — not re-attempted per spawn instruction). Evidence is direct `Read` of source, `grep`/`find`, and `javap` on the build-resolved fork jar `gremlin-core-3.8.1-af9db90-SNAPSHOT.jar` (version pinned at `pom.xml:114`; bytecode facts exact). The `armingGraph` usage enumeration is complete by language rule (a `private` field in a `final` class is referenceable only inside its declaring file, which was read in full). Two claims carry a grep caveat, recorded on C5 and C6.

## Findings

### A1 [should-fix]
**Certificate**: C3
**Target**: Track file `## Decision Log` base-shape bullet (line 23), `## Context and Orientation` (line 32), `## Plan of Work` item 1 (line 50) — the framing of "row projection + the `ResultShaping` read" as a liftable unit. Builds on technical T1 / risk R2 (base granularity) with a coupling neither names.
**Challenge**: Row projection is not config + row; it reads the mutable per-arming `armingGraph` field at four sites — `projectOrSkip → projectElement(row, armingGraph)` (`YTDBMatchPlanStep.java:525`), `convertMapColumn` (`:571`), `convertValue` (`:658,662`), `convertGroupValue` (`:686`) — and `armingGraph` is lifecycle state: set on each arming (`:348`), nulled on release (`:400,416`) and on clone (`:514`). The strategy constructs the boundary step at translation time, before any graph is resolved, so a composed row-projector that captures a graph at construction holds `null`; one that caches it across armings holds the wrong graph after `clone()` — the existing `clone_twoClonesDrivenConcurrently_eachRunsOwnPlanCopy` scenario has each clone resolve its own graph via its own `tx` (`:365-367`), and a shared cached graph would wrap one clone's rows as `YTDBVertexImpl`s against the other clone's session. The Decision Log's "smallest surface that lets both boundary steps share projection + shaping without exposing mutable lifecycle state" is therefore not satisfiable as written: the projection half itself consumes per-arming mutable state.
**Evidence**: `YTDBMatchPlanStep.java:130,348,365-367,400,416,514,525,571,658,662,686`; construction site `GremlinToMatchStrategy.java:437` (no graph in scope). Enumeration complete by the private-field language rule — no grep caveat.
**Proposed fix**: In decomposition, pin graph-per-arming injection as part of the base contract: either every projection entry point takes the graph as a parameter (the pattern `projectElement(row, graph)` already uses — extend it to the map/value converters), or the base owns the `armingGraph` field alongside the per-stream open/drain/release primitives risk R2 assigns it. A third shape that satisfies the Decision Log's no-mutable-state preference outright: a stateless package-private projector whose methods take `(row, graph, shaping, presenceKeySet, boundaryAlias, outputType)` explicitly — it also sidesteps risk R1's field-move test breakage because no field moves.

### A2 [should-fix]
**Certificate**: C4
**Target**: `## Plan of Work` item 3 (line 52: framework "verified with placeholder / no-op ops") and `## Validation and Acceptance` (line 64: ordering unit test). Extends risk R3 (parameter-bearing placeholder) from parameter shape to iteration cardinality; distinct from technical T2 (which pins where the stage hooks, not what an op is).
**Challenge**: The four Track 9 ops the framework must eventually host span three iteration-cardinality classes, confirmed by the fork jar: `ReverseStep extends ScalarMapStep` (1→1 per-payload map), `UnfoldStep extends FlatMapStep` (1→N), `FoldStep extends ReducingBarrierStep` (N→1 drain), `TailGlobalStep` (global window — full drain into a ring buffer). The boundary's emission contract is one traverser per `processNextStart()` call (`YTDBMatchPlanStep.java:275-286`), so 1→N needs cross-call output buffering and N→1/window needs drain-before-first-emission. A framework verified only with no-op placeholders — even a parameter-bearing one per R3 — naturally freezes a row-mapper contract (`Object apply(Object)`), which expresses exactly one of the four ops. Track 9 then reworks the frozen op contract, which by then is wired into the base and inherited by Track 8's shipped `MultiPlanMatchStep` — the foundation-shape rework the split was meant to prevent. The track file names "the `unfold` flat-map, the `tail` ring buffer, the `fold` `LIST` drain" as Track 9's stages, so the knowledge exists; the verification plan just never forces the framework to demonstrate it.
**Evidence**: `javap` on `gremlin-core-3.8.1-af9db90-SNAPSHOT.jar` — the four supertypes above (exact); `YTDBMatchPlanStep.java:275-286` (one-traverser-per-call loop); track-7.md:52 (stage names vs placeholder verification).
**Proposed fix**: Pin the op contract as a stream-stage (e.g. each op transforms an iterator/stream, or declares its class: map / flat-map / barrier) rather than a row mapper, and verify the declared-order framework with at least one cardinality-changing placeholder (a 1→2 duplicating op, or a drain-last-N marker mimicking `tail`) in addition to R3's parameter-bearing one. The acceptance line at :64 should assert ordering across a cardinality change, not only across two 1→1 markers.

### A3 [should-fix]
**Certificate**: C6
**Target**: The track's behavior-neutrality invariant (`## Purpose` line 5: "produces the exact same result multiset and output type"; `## Validation` line 64: "a no-op when the op list is empty") plus the plan constraint "Multiset equality is the contract."
**Challenge**: The acceptance is satisfiable by an implementation that violates what users observe. An ordered-op stage written as collect-all → apply ops (zero of them) → re-emit produces the identical multiset and output type on every path — every stated acceptance passes, and the equivalence suites (`ProjectionEquivalenceTest`, `EdgeTraversalEquivalenceTest`, `PredicateTraversalEquivalenceTest`) collect full results, so they cannot see the difference. What changes: today the boundary pulls one row per `processNextStart()` (`YTDBMatchPlanStep.java:275-286`); the eager stage drains the whole `ExecutionStream` on the first call. For a partially-consumed traversal (`g.V().next()` on a large class — no `limit` step, so no plan-side `SQLLimit` bounds the stream), first-result latency and memory go O(N), and an early `Traversal.close()` no longer aborts the scan. Three of the four Track 9 ops are drain-shaped (C4), so collect-first is the natural framework shape — this is a likely default, not a contrived strawman. No existing test pins pull-per-call behavior: `YTDBMatchPlanStepTest` verifies `never()).next(ctx)` on non-consuming paths (`:190,239`) but has no positive `times(n)).next` assertion, and the JMH baseline that would catch the latency cliff lands only in Track 9.
**Evidence**: `YTDBMatchPlanStep.java:275-286`; `YTDBMatchPlanStepTest.java:190,239` (pattern scan of the full test file — absence claim, grep caveat); track-7.md:5,64. Feasibility: CONSTRUCTIBLE.
**Proposed fix**: Strengthen the acceptance line: with an empty op list the boundary's per-row pull path is *structurally unchanged* (the stage is bypassed or is a pass-through iterator), not merely result-equal. Add a laziness pin to the equivalence surface: drive one `processNextStart()` against a mocked multi-row stream and assert exactly one `next(ctx)` pull — one test, using the mock infrastructure the lifecycle suite already has.

### A4 [suggestion]
**Certificate**: C5
**Target**: `## Decision Log` (lines 20–26) — the ordered-carrier bullet is silent on the plan cache; assumption that cache congruence needs no decision.
**Challenge**: Track 6's episode made fingerprint congruence a proven failure class (`GremlinPlanFingerprint` omitted result-shaping clauses → `limit(2)`/`limit(5)` collision, track-6.md:36,45), and the pre-split adversarial A4 demanded a one-sentence pin that boundary-only post-process flags stay out of the fingerprint by design. That sentence survives in none of the three post-split files: track-7.md is silent, track-8.md pins only union keying (:24,54), track-9.md mentions the cache only for JMH (:5). The code answer is already correct — `GremlinPlanFingerprint.fingerprint(inputs)` covers `MatchPlanInputs` only (`:46-53`), the fingerprint is computed post-walk (`GremlinToMatchStrategy.java:399`), so on every cache hit the walker has re-run and the boundary is constructed fresh from `translation.shaping()` (`:437-444`); ordered ops never enter the plan, so `tail(2)` and `tail(5)` sharing one cached plan is correct, unlike the plan-side limit collision Track 6 fixed by keying. But with the decision unrecorded, Track 9's decomposer inherits only the Track 6 episode's "key it" instinct and may over-key (harmless cache misses) or — if `tail` ever migrates plan-side — under-key.
**Evidence**: `GremlinPlanFingerprint.java:15-23,46-53`; `GremlinToMatchStrategy.java:392-414,437-444`; track-6.md:36,45; pre-split `adversarial-iter1.md` A4; grep over track-7/8/9.md for fingerprint/cache mentions (keyword scan — a paraphrased pin without those words would be missed; grep caveat).
**Proposed fix**: One Decision Log sentence in this track (the carrier's owner): the ordered post-process list is fingerprint-exempt by design because it never enters `MatchPlanInputs` and the boundary receives it fresh on every walk, cache hit or miss; revisit only if an op is ever pushed plan-side.

### A5 [suggestion]
**Certificate**: C1 (supported by C2)
**Target**: The track boundary itself — `## Plan of Work` item 3 + `## Interfaces and Dependencies` (line 80): the carrier + application framework live in Track 7, real ops in Track 9; the spawn's scope question "should the ordered-carrier work split to Track 9?".
**Challenge**: The strongest alternative — land the carrier and framework in Track 9 beside their only real consumers, keeping Track 7 a pure base extraction — was argued and lost, but the track file never records why. It loses on two grounds. (1) Track 9 already claims ~14–20 files at the split ceiling (plan checklist); moving the carrier, the application stage, and the ordering tests (+4–6 files) pushes it over the ceiling the 2026-07-27 split was made to respect. (2) Risk R2 establishes that emission orchestration (`processNextStart` loops) stays on the concrete steps; a Track 9 retrofit would therefore edit three emission sites across two already-shipped tracks — `YTDBMatchPlanStep`'s per-row loop, its group-barrier path, and `MultiPlanMatchStep`'s fresh Track 8 loop — instead of pre-wiring one base now that Track 8 copies at birth. Landing the framework here is right; the un-stated rationale is the gap, because a decomposer reading item 3's "verified with placeholder / no-op ops" could reasonably defer the framework half to Track 9 as speculative and re-open the retrofit problem.
**Evidence**: plan checklist Track 8/9 scope bands (implementation-plan.md:548,566-569); risk R2 (orchestration stays concrete); technical T2 (two emission paths per step); D8 revised (implementation-plan.md:212-253, user-approved split). Survival: YES — the allocation holds.
**Proposed fix**: One sentence in `## Purpose` or the Decision Log: the application framework must precede Track 8 because both boundary steps' emission paths wire through it at birth, and a post-Track-8 retrofit would touch three emission loops across two merged PRs; Track 9 registers ops only.

## Evidence base

Certificates grouped by review criterion. PSI non-functional this session (see tooling caveat); symbol evidence is direct source reads, grep (caveats noted per certificate), and `javap` on the pinned fork jar.

### Scope challenges

#### C1 Challenge: ordered-carrier allocation — Track 7 vs Track 9
- **Chosen approach**: the ordered post-process carrier + declared-order application framework land in Track 7 (Plan of Work item 3); Track 9 registers the real ops.
- **Best rejected alternative**: land carrier + framework in Track 9 beside their only real consumers; Track 7 stays a pure base extraction.
- **Counterargument trace**:
  1. Track 9's scope band is ~14–20 files (implementation-plan.md:566-569), at the ~20–25 split ceiling; the carrier + stage + ordering tests add ~4–6 files, breaching the ceiling the D8 split was approved to respect.
  2. Risk R2 pins emission orchestration on the concrete steps, so a Track 9 retrofit edits `YTDBMatchPlanStep`'s per-row loop (`:275-286`), its group-barrier path (`:311-327`, technical T2), and Track 8's shipped `MultiPlanMatchStep` loop — three sites across two merged PRs.
  3. Pre-wiring the stage in Track 7's base means Track 8's step routes through it from birth; the carrier also touches exactly the files this track already refactors (`ResultShaping`, the base's emission paths), so bundling costs no extra file surface.
- **Codebase evidence**: as cited in the trace; `GremlinToMatchStrategy.java:437` (single splice point both tracks share).
- **Survival test**: YES — the allocation survives; the rationale is unrecorded. → A5

#### C2 Challenge: the ~10–14 file scope indicator
- **Chosen approach**: `**Scope:** ~10–14 files` (plan checklist, implementation-plan.md:529).
- **Best rejected alternative**: none credible — the challenge is whether the band is honest in both directions (under the ~12 merge floor, or hiding a larger sweep).
- **Counterargument trace**:
  1. ResultShaping-component carrier path: `ResultShaping.java` (only positional-construction sites are the record's own `NONE` + `withX`, grep-confirmed — a clean seam), `YTDBMatchPlanStep.java`, the new base, the op type(s), the ordered-stage test, equivalence-suite extension, `YTDBMatchPlanStepTest` edits (risk R1), `WalkerContextResultShapingTest` — ~8–11 files.
  2. Adjacent-type carrier path adds `WalkerContext`, `RecognitionContext`, `SubTraversalPredicateAdapter` (its sub-walk swallow of shaping setters, track-6.md:102), `GremlinStepWalker`, `GremlinToMatchTranslator` — ~13–15 files, the band's top.
  3. The low end grazes the ~12 merge-candidate floor, but the written justification exists: D8's user-approved split (implementation-plan.md:240-244) plus the track file's foundation-slice framing (track-7.md:9).
- **Codebase evidence**: grep `new ResultShaping(` over `core/src` (8 hits, all inside `ResultShaping.java`); file census above.
- **Survival test**: YES — the band is honest; both carrier paths land inside it. No finding.

### Assumption challenges (cross-track-episode reality)

#### C3 Assumption test: "row projection + the `ResultShaping` read" is a self-contained liftable unit
- **Claim**: track-7.md:23,32,50 — the base carries projection + shaping read (+ lifecycle), and the Decision Log prefers sharing "projection + shaping without exposing mutable lifecycle state".
- **Stress scenario**: a composed row-projector built at step construction; two clones driven concurrently on different threads/sessions.
- **Code evidence**: projection reads the mutable per-arming `armingGraph` at `YTDBMatchPlanStep.java:525,571,658,662,686`; the field is set per arming (`:348`), nulled on release (`:400,416`) and clone (`:514`); the step is constructed before any graph resolution (`GremlinToMatchStrategy.java:437`); each clone resolves its own graph via its own `tx` (`:365-367`). Enumeration complete by the private-field language rule.
- **Verdict**: BREAKS — projection-as-framed omits a per-arming mutable dependency; a projector holding a captured graph is null or wrong-clone. Track 6's Step 7 episode (track-6.md:193-204) is when this coupling deepened: the MAP/SINGLE_VALUE/SCALAR converters all wrap through `armingGraph`. → A1

#### C4 Assumption test: no-op placeholder verification proves Track 9's stages are expressible
- **Claim**: track-7.md:52 — the framework is "verified with placeholder / no-op ops"; the concrete stages are Track 9's.
- **Stress scenario**: decomposition freezes a row-mapper op contract; Track 9 implements `fold`/`unfold`/`tail`.
- **Code evidence**: fork jar (`pom.xml:114` pins `3.8.1-af9db90-SNAPSHOT`): `FoldStep extends ReducingBarrierStep` (N→1), `UnfoldStep extends FlatMapStep` (1→N), `ReverseStep extends ScalarMapStep` (1→1), `TailGlobalStep extends AbstractStep implements TailGlobalStepContract` (global window). Boundary emits one traverser per `processNextStart()` call (`YTDBMatchPlanStep.java:275-286`), so cardinality-changing ops need cross-call buffering or drain-before-emit that a row-mapper contract cannot express.
- **Verdict**: BREAKS — the verification plan cannot falsify a wrong op abstraction; three of four real ops don't fit the shape the placeholders suggest. → A2

#### C5 Assumption test: the ordered carrier needs no plan-cache/fingerprint decision
- **Claim**: implicit — track-7.md's Decision Log and Interfaces never mention the cache; pre-split A4's pin sentence is in no post-split file.
- **Stress scenario**: Track 9's decomposer, primed by the Track 6 fingerprint-collision episode, must decide whether ordered ops enter the cache key.
- **Code evidence**: `GremlinPlanFingerprint.fingerprint(inputs)` covers `MatchPlanInputs` only (`:46-53`; Javadoc `:15-23` enumerates pattern/filters/NOT/projection/plan-side shaping clauses); computed post-walk at `GremlinToMatchStrategy.java:399`, so the walker has re-run on every hit and the boundary is built fresh from `translation.shaping()` (`:437-444`). Boundary-only ops never affect the cached `InternalExecutionPlan`, so plan sharing across differing op lists is correct — the opposite polarity from the plan-side `limit(2)`/`limit(5)` collision Track 6 fixed (track-6.md:36,45). Grep over track-7/8/9.md finds no exemption pin (keyword scan; caveat: a paraphrase without fingerprint/cache words would be missed).
- **Verdict**: HOLDS — the code is congruent by construction; only the decision record is missing. → A4

### Invariant challenges

#### C6 Violation scenario: behavior-neutrality with an empty op list
- **Invariant claim**: track-7.md:5 — identical result multiset and output type for every recognised traversal; :64 — the stage "is a no-op when the op list is empty"; plan Constraints — multiset equality.
- **Violation construction**:
  1. Start state: a recognised `g.V()` over a large class; translator on; ordered-op stage implemented as collect-all → apply ops → re-emit (the natural shape given three of four ops are drain-like, C4).
  2. Action sequence: user calls `traversal.next()` once → first `processNextStart()` → stage drains the whole `ExecutionStream` (vs one `openStream.next(ctx)` pull today, `YTDBMatchPlanStep.java:275-286`) → emits the first buffered row.
  3. Intermediate state: full result set materialised; state already DRAINED; stream closed.
  4. Violation point: not the multiset — the emitted values are identical, so every stated acceptance and all three equivalence suites (full-collection comparisons) pass. The break is the boundary's lazy iteration contract: O(N) first-result latency/memory on partial consumption, and early `Traversal.close()` (the Javadoc-documented early-termination path, `:456-462`) no longer aborts a scan.
  5. Observable consequence: user-visible latency/memory cliff on partially-consumed recognised traversals, shipped under a green "behavior-neutral" gate; first measurable signal arrives with Track 9's JMH baseline, two tracks late.
- **Feasibility**: CONSTRUCTIBLE — no existing test pins pull-per-call behavior (`YTDBMatchPlanStepTest` has `never()).next(ctx)` at `:190,239` but no positive `times(n)).next` assertion; pattern scan of the full file, absence-claim caveat). → A3

#### C7 Violation scenario: D7 idempotency + exactly-one-boundary-step invariants under the base extraction
- **Invariant claim**: plan Invariants — a recognised traversal contains exactly one boundary step after `applyStrategies()`; re-application is a no-op.
- **Violation construction**: attempted — re-apply the strategy to a translated traversal after Track 7 lands. The idempotency scan keys on `instanceof YTDBMatchPlanStep<?,?>` (`GremlinToMatchStrategy.java:343`); Track 7 keeps `YTDBMatchPlanStep` the sole constructed concrete type (technical C1/C3/C10, T4 ctor preservation), and inserting a supertype above a class changes neither `instanceof` on the subclass nor `StringFactory.stepString`'s runtime-class marker in `explain()` (`YTDBMatchPlanStep.java:246-248`). No path to a second boundary or a missed scan exists within this track's edits; broadening the scan to the base is explicitly Track 8's (plan D7).
- **Feasibility**: INFEASIBLE — the invariants survive the base extraction untouched. Recorded so decomposition neither worries about the scan nor prematurely broadens it. No finding.
