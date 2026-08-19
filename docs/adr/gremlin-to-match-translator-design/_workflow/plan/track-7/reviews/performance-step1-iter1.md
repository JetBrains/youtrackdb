<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
evidence_base: {section: "## Evidence base", certs: 4, matches: 4}
cert_index:
  - {id: C1, verdict: REFUTED, anchor: "#### C1 "}
  - {id: C2, verdict: REFUTED, anchor: "#### C2 "}
  - {id: C3, verdict: REFUTED, anchor: "#### C3 "}
  - {id: C4, verdict: MATCHES, anchor: "#### C4 "}
flags: [CONTRACT_OK]
-->

## Findings

No performance findings. The extraction is hot-path-neutral: the per-row projection loop keeps the same shape, the same allocations, and the same call frequency as before the split. The one structural delta — routing `plan.getContext()` through the new `planContext()` hook once per `processNextStart()` — is a monomorphic, JIT-inlinable call that collapses back to the pre-existing interface call, and per-row laziness in the stream drain is preserved (relevant to Step 2's first-result-latency / pull-count pins). Every candidate concern is refuted in the Evidence base with its scale check.

## Evidence base

#### C1 planContext() virtual dispatch in the per-row loop — REFUTED

Candidate claim: routing plan access through the abstract `planContext()` hook adds a virtual/megamorphic call in the projection inner loop, regressing per-row throughput.

Cost trace. `processNextStart()` (AbstractMatchPlanStep.java:231) resolves the context once before the drain loop: `var ctx = planContext();` (:241), then reuses `ctx` for every `openStream.hasNext(ctx)` / `openStream.next(ctx)` inside `while (true)`. The old code did the identical thing with `var ctx = plan.getContext();` (deleted YTDBMatchPlanStep line 989). So `planContext()` is called once per `processNextStart()` invocation — once per emitted row, the same frequency the old direct getter had. SKIP rows loop inside the single call and share the one resolved `ctx`; they add no extra hook calls.

Dispatch analysis. The old `plan.getContext()` was already an `invokeinterface` on `InternalExecutionPlan.getContext()`. The new form adds one level: a virtual call to `YTDBMatchPlanStep.planContext()` (AbstractMatchPlanStep.java:701 abstract, YTDBMatchPlanStep.java:387 concrete) which returns `plan.getContext()`. `AbstractMatchPlanStep.planContext()` has exactly one implementation today (`YTDBMatchPlanStep`), so the site is monomorphic; Track 8's `MultiPlanMatchStep` would make it bimorphic. Monomorphic and bimorphic sites are inline-cached and inlined by C2, so the hook body folds into `processNextStart` and the compiled form is again a single `plan.getContext()` interface call. Only the interpreter / C1 tier pays a marginal extra frame.

Scale check. At 100 rows: unmeasurable. At 100K rows: sub-millisecond, below noise. At 1M+ rows: an added inlinable call per row is a small fraction of the real per-row cost (a stream `next()` record read plus a `new YTDBVertexImpl` wrap plus traverser generation), so under 0.1% of loop cost even before the JIT erases it. Verdict: NEGLIGIBLE — no finding.

#### C2 Per-arming armingGraph re-read per row — REFUTED

Candidate claim: reading the mutable `armingGraph` field per row (rather than hoisting the resolved graph into a local) is redundant per-row work introduced by the extraction.

Trace. `projectOrSkip` reads `armingGraph` in the ELEMENT arm (AbstractMatchPlanStep.java:475), and `convertValue` / `convertMapColumn` / `convertGroupValue` read the field directly (:521, :608, :636). This is byte-for-byte the pre-split behavior — the old `YTDBMatchPlanStep` read the same field at the same points. The field moved from the concrete class to the base, but inherited fields share the object layout, so the access cost (a field load off `this`) is identical. No hoisting was lost and no new read was added. Nothing was introduced by this step. Verdict: REFUTED — unchanged and free.

#### C3 projectElement now package-private in a non-final base — REFUTED

Candidate claim: `projectElement` was package-private in the `final` `YTDBMatchPlanStep` (trivially devirtualized); moving it to the non-final abstract base makes it a virtual call site in the projection path.

Analysis. `projectElement` (AbstractMatchPlanStep.java:661) is package-private and not overridden by `YTDBMatchPlanStep` (which stays `final`), and the only future subclass (Track 8's `MultiPlanMatchStep`) shares projection rather than overriding it. Class-hierarchy analysis resolves the call to the single `AbstractMatchPlanStep.projectElement` implementation and inlines it; the same holds after Track 8 lands, because no subclass overrides it. The remaining private projection helpers (`projectMap`, `projectSingleValue`, `projectScalar`, `convertValue`, `resolveEntity`, `projectVertex`, etc.) are private within the base — `invokespecial`, statically bound, same as before. No new effective virtual dispatch reaches the inner loop. Verdict: REFUTED — CHA-devirtualized, no override.

Reference-accuracy caveat (grep-only): the "no override of `projectElement` / the projection helpers" and "`planContext()` has one implementation today" facts rest on reading the two step declarations in the diff plus a grep of `translator/step/` for subtypes of `AbstractMatchPlanStep`, not PSI find-implementations — mcp-steroid PSI (`steroid_execute_code`) times out on every call in this repo (cold kotlinc > ~60s MCP HTTP limit), per the track's Phase A note and the session preflight. A hidden override in an unindexed source path would flip C3 from devirtualized to bimorphic, but even bimorphic stays inline-cacheable, so the verdict does not change under the caveat.

#### C4 Per-row laziness and bounded memory in the drain — MATCHES (preserved, no regression)

Relevant to Step 2, which wires an ordered list-shaping op stage through this same loop and pins first-result latency / pull-count. `processNextStart()` still pulls one row per call and returns on the first non-SKIP payload (AbstractMatchPlanStep.java:246-257) — one `openStream.next(ctx)` per emitted traverser, no look-ahead, no buffering. The only eager path is `emitAccumulatedGroupMap` (:283), reached solely under `shaping.accumulateMap()` (the GROUP BY barrier, inherently a drain), which is unchanged from the old code and takes `ctx` as a parameter so it makes zero per-row hook calls. The ELEMENT / SINGLE_VALUE / SCALAR paths accumulate nothing; MAP allocates one bounded `LinkedHashMap` per row exactly as before. No change defeats laziness or bounded memory. Verdict: MATCHES — the hot path Step 2 depends on is intact.
