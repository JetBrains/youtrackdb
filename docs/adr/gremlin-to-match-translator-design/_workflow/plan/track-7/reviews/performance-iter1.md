<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
evidence_base: {section: "## Evidence base", certs: 6, matches: 0}
cert_index:
  - {id: C1, verdict: REFUTED, anchor: "#### C1 "}
  - {id: C2, verdict: REFUTED, anchor: "#### C2 "}
  - {id: C3, verdict: REFUTED, anchor: "#### C3 "}
  - {id: C4, verdict: REFUTED, anchor: "#### C4 "}
  - {id: C5, verdict: REFUTED, anchor: "#### C5 "}
  - {id: C6, verdict: REFUTED, anchor: "#### C6 "}
flags: [CONTRACT_OK]
-->

## Findings

No performance findings. The track is a performance-neutral refactor: the row-projection inner loop's per-row cost is unchanged (one indirection added, one per-row `getContext()` call removed, no per-row allocation), and the empty-op common case keeps its per-row laziness through a real structural bypass. Every candidate regression was traced and refuted; see the Evidence base.

## Evidence base

Scope: the six Java files in the track diff. The performance-relevant surface is `AbstractMatchPlanStep.java` (the extracted base carrying the projection inner loop and the new list-shaping stage), `ResultShaping.java` (new 8th record component), and `ListShapingOp.java` (new stream-stage contract). `YTDBMatchPlanStep.java` shrinks to plan-seam hooks; `GremlinToMatchStrategy.java` retypes one `instanceof`; the test file adds pins. Every candidate below carries a Scale Validation verdict; all refuted, so each is written in full per the roster rendering.

**Hot-path premise (P1).** `AbstractMatchPlanStep.processNextStart()` and the `rowProjectionSource()` iterator body are the per-row hot path — invoked once per emitted traverser for every recognised Gremlin traversal routed through MATCH, so unbounded in the row count of the underlying result set. `ResultShaping` construction and the boundary-step constructor are per-query-execution (built during the walk; the walker re-runs on every plan-cache hit and rebuilds the boundary from `translation.shaping()` per the track Decision Log), not per-row. This premise drives every verdict below.

**Reference-accuracy caveat.** mcp-steroid PSI (`steroid_execute_code`) times out in this repository (cold kotlinc > 60s MCP limit), so I did not run PSI find-usages to confirm caller frequency. The hot-vs-cold classification (P1) rests on reading the diff plus the track file's Decision Log and Episodes, not a symbol search. The classification is low-risk — the projection loop is self-evidently per-row from the code, and `ResultShaping`/constructor frequency is per-walk by construction — but a hidden per-row construction site of `ResultShaping` would not have been caught by a caller search I could not run.

#### C1 Per-row iterator-indirection layer vs the old inline stream loop — REFUTED
CLAIM: the new path routes each row through the `rowProjectionSource()` anonymous `Iterator` (`AbstractMatchPlanStep.java:332`), whereas the old `processNextStart` looped directly over `openStream`, so every emitted row now pays extra dispatch.

COST TRACE. Old per-row work: `processNextStart` did `plan.getContext()` (once per call), `openStream.hasNext(ctx)`, `openStream.next(ctx)`, `projectOrSkip`, `generate`. New per-row work: `processNextStart` does `shapedPayloads.hasNext()` + `shapedPayloads.next()` (the wrapper), and inside the wrapper `stream.hasNext(ctx)`, `projectOrSkip(stream.next(ctx))`, buffer via two instance fields; `planContext()` is now resolved once per arming (captured in the `rowProjectionSource` closure at `:333`), not per row. Net delta per row: +2 virtual dispatches (wrapper `hasNext`/`next`), a field write+read for buffering, and −1 `plan.getContext()` call. `next()` re-checks `hasNext()` only when `!hasBuffered` (`:357`), and `processNextStart` always calls `hasNext()` first, so there is no double projection.

ALLOCATIONS: none added per row. The anonymous iterator is one allocation per arming (per query execution), stored in `shapedPayloads` and rebuilt only on (re)open.

SCALE CHECK. AT 100 rows: unmeasurable. AT 100K rows: sub-millisecond, dominated by `new YTDBVertexImpl` + record access in `projectOrSkip`. AT 1M+ rows: a couple of million extra monomorphic/bimorphic dispatches, low single-digit ms at most, offset by the removed per-row `getContext()`, and swamped by per-row deserialization. VERDICT: NEGLIGIBLE. The indirection is also required infrastructure for Track 9's ops; special-casing it away would trade readability for no measurable gain.

#### C2 `applyListShaping` per-row overhead in the empty-op common case — REFUTED
CLAIM: threading list-shaping ops through the projection stream adds per-row cost even for today's traversals, which register no ops.

COST TRACE. `applyListShaping` (`AbstractMatchPlanStep.java:313`) reads `shaping.listShapingOps()` (a record accessor returning the field), calls `isEmpty()` (O(1)), and on empty returns `source` untouched — a genuine structural bypass, not a no-op stage wrapped around the source. This runs once per arming inside `openShapedPayloads()`, not per row. For the empty case there is zero per-row op cost and zero wrapper allocation; the projection stream flows straight through.

SCALE CHECK. Empty-op is the state of every traversal that exists today (`ResultShaping.NONE` seeds `listShapingOps = List.of()`). At every scale the empty-op cost is one `isEmpty()` check per arming. VERDICT: NEGLIGIBLE. Pinned by `listShaping_emptyOps_firstPullAdvancesStreamByOneRow_notDrained`, which asserts the first pull advances the stream by exactly one row.

#### C3 Per-arming allocations in `openShapedPayloads` / `rowProjectionSource` / `accumulatedGroupMapSource` — REFUTED
CLAIM: the new source iterators churn allocations on the hot path.

COST TRACE. `rowProjectionSource()` allocates one anonymous `Iterator` per arming (`:335`); its buffering uses two instance fields (`bufferedPayload`, `hasBuffered`) with no per-row allocation (`:341`–`:363`). `accumulatedGroupMapSource()` allocates the drained `LinkedHashMap` (as the old `emitAccumulatedGroupMap` did) plus one `List.of(map).iterator()` (`:386`) — the group path drains the whole stream once per arming, so this is one-time. `openShapedPayloads()` itself allocates nothing beyond delegating. All allocations are per-arming (per query execution), not per-row.

SCALE CHECK. One extra small object per query execution regardless of row count. VERDICT: NEGLIGIBLE.

#### C4 `ResultShaping` defensive `List.copyOf(listShapingOps)` allocation churn — REFUTED
CLAIM: the compact constructor now runs a second `List.copyOf` (`ResultShaping.java:932`) on every `withX` builder call, so building a shaping allocates repeatedly.

COST TRACE. `ResultShaping` is built during the walk, once per query execution (per P1), never per row. Each `withX` threads the existing `listShapingOps` reference through unchanged; `NONE` seeds it with `List.of()`. `List.copyOf` returns its argument as-is when the argument is already an immutable list (JDK `ImmutableCollections` short-circuit), so the empty/immutable common case copies nothing — no allocation across a `withX` chain. A real copy happens only once, when a caller passes a fresh mutable list via `withListShapingOps(...)` (a Track 9 concern; empty today). Same reasoning covers the pre-existing `presencePropertyKeys` copy, unchanged by this track.

SCALE CHECK. Per-query, and free for the empty-list default. VERDICT: NEGLIGIBLE.

#### C5 First-result latency / eager materialization of the row stream — REFUTED
CLAIM: the list-shaping stage could eagerly drain the projection stream before emitting the first traverser, destroying first-result latency and bounded memory.

COST TRACE. For the per-row paths (element / map / value / scalar) with no ops, the source is returned untouched (C2) and the `rowProjectionSource` iterator pulls exactly one row per emitted payload — first-result latency is one row, matching pre-refactor behavior. The group-barrier path (`accumulateMap`) is a barrier by nature and drained the whole stream before emitting in the old code too (`emitAccumulatedGroupMap`), so the new `accumulatedGroupMapSource` preserves, not regresses, that cost. The one lifecycle shift on the group path — the drained stream is released on the next `processNextStart` rather than in the emitting call — holds an already-drained cursor open for one extra pull; it is a resource-lifetime detail, not a hot-path cost, and outside the performance dimension.

SCALE CHECK. Laziness preserved at all scales for the per-row paths. VERDICT: NEGLIGIBLE. Pinned by the empty-op first-pull test and by `listShaping_flatMapOp_oneRowYieldsTwoTraversers_lazily`, which asserts the first emission costs one source row.

#### C6 Megamorphic dispatch / autoboxing introduced on the hot path — REFUTED
CLAIM: the `shapedPayloads.hasNext()/next()` call site (`AbstractMatchPlanStep.java:264`, `:271`) is polymorphic and could go megamorphic; the new stage could box.

COST TRACE. Today the call site sees at most two receiver types across a JVM run — the `rowProjectionSource` anonymous iterator (per-row paths) and the JDK singleton-list iterator (group path) — i.e. bimorphic, which the JIT resolves with an inline cache and no megamorphic penalty (that requires ≥3 observed types). The new plan-seam methods (`planContext` / `rewindPlan` / `startPlanStream` / `closePlan`) are abstract, but each is called only on open/rewind/close (per-arming), never per row. No autoboxing is introduced: the `SKIP` sentinel is compared by `==`, payloads are already boxed `Object`s from `Result`, and the empty `listShapingOps` list carries no ops to box arguments for. Track 9 will add op iterator types at this call site; evaluating megamorphism then belongs to Track 9, not this track.

SCALE CHECK. Bimorphic and box-free today. VERDICT: NEGLIGIBLE.
