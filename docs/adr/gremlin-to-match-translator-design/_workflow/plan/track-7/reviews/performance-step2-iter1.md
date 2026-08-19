<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
evidence_base: {section: "## Evidence base", certs: 4, matches: 0}
cert_index:
  - {id: C1, verdict: REFUTED, anchor: "#### C1 "}
  - {id: C2, verdict: REFUTED, anchor: "#### C2 "}
  - {id: C3, verdict: REFUTED, anchor: "#### C3 "}
  - {id: C4, verdict: REFUTED, anchor: "#### C4 "}
flags: [CONTRACT_OK]
-->

## Findings

No performance findings. The change is performance-clean on the always-taken empty-op hot path and introduces no structural eager-drain on the future op-carrying path. The four candidate concerns raised by the step focus were each traced and refuted at scale — see `## Evidence base`.

## Evidence base

All four candidate performance-regression claims were subjected to the Phase-4 scale check and REFUTED (none survived as an issue), so each is written in full.

#### C1 REFUTED — `rowProjectionSource()` per-row indirection regresses the hot path

Claim: the anonymous `Iterator<Object>` at `AbstractMatchPlanStep.java:326-360` interposes a layer between `processNextStart()` and the underlying `ExecutionStream`, so every emitted row now pays an extra virtual `hasNext`/`next` dispatch plus one-payload buffer field writes that the old inline `while` loop did not.

Cost trace. Per row the added work over the old loop is: one `Iterator.hasNext()` + one `Iterator.next()` dispatch, two field writes (`bufferedPayload`, `hasBuffered`), two field reads, one field clear. The pre-existing per-row work on the same path is `ExecutionStream.hasNext(ctx)` + `ExecutionStream.next(ctx)` (interface calls into a stream chain that can reach the page cache), `projectOrSkip(...)` (for the ELEMENT path this wraps the raw entity in a `YTDBVertexImpl` — a heap allocation; the MAP path builds a map), and traverser generation. The added cost is a few nanoseconds of field traffic against hundreds of nanoseconds-to-microseconds of real per-row work.

Call-site shape. The `shapedPayloads.hasNext()/next()` site in `processNextStart` (`:258`, `:265`) sees at most two concrete types today (the `rowProjectionSource` anonymous iterator and the `List.of(map).iterator()` type from the group path) — bimorphic, which the JIT resolves with an inline cache, not a megamorphic vtable walk. No regression relative to the old code, whose `openStream.hasNext(ctx)` site was already on the many-implementation `ExecutionStream` interface (effectively megamorphic), so the projection loop was never in a monomorphic-inlined state the new layer could spoil.

Scale check. Small (100 rows): negligible. Medium (100K rows): negligible — dominated by projection + stream I/O. Production (1M+ rows): negligible for the same reason; the added field traffic is a sub-1% slice of per-row cost and JIT-inlinable. VERDICT: NEGLIGIBLE. Per the step focus ("Do NOT flag the indirection unless it plausibly matters"), not a finding.

#### C2 REFUTED — `openShapedPayloads()` / `rowProjectionSource()` allocate per row

Claim: building a fresh iterator per emitted payload would add one small-object allocation per row and steady GC pressure on large result sets.

Trace. The allocation is per-arming, not per-row. `processNextStart` builds `shapedPayloads` once under the `if (shapedPayloads == null)` guard (`:252-257`) and reuses it across every pull of the arming; the field is nulled only on (re)open (`:249`), on `releaseStream()`/`releaseStreamAndClosePlan()`, and in `resetLifecycleForClone()`. An arming corresponds to one plan open — once per traversal execution (bounded by `reset()` re-arms), not once per result. So one small anonymous-iterator allocation per query, amortised across the entire result multiset.

Scale check. At every scale this is a single small allocation per traversal execution — orders of magnitude below the per-row `YTDBVertexImpl` / map allocations the projection already makes. VERDICT: NEGLIGIBLE. Not a finding. (The step focus asked to confirm per-arming, not per-row — confirmed.)

#### C3 REFUTED — `accumulatedGroupMapSource()` wraps the map in `List.of(map).iterator()`

Claim: the group-barrier path now returns `List.<Object>of(map).iterator()` (`:380`) instead of emitting the map straight into the traverser generator, adding a one-element immutable list plus its iterator.

Trace. `group` / `groupCount` are barrier steps: the whole stream drains into one `LinkedHashMap` and exactly one payload emits per execution. The added `List.of(map)` + iterator is therefore two tiny allocations once per group query — not per row and not per group key. It buys the single shared list-shaping stage both projection paths reach, which is the design point (a future op must compose over the group map exactly as over the per-row stream).

Scale check. One-shot per query at any scale, dwarfed by the map the group already accumulated. VERDICT: NEGLIGIBLE. Not a finding.

Adjacent lifecycle note (out of performance scope): the group path now releases the drained stream on the second `processNextStart` pull (via the `!hasNext()` drain at `:258-263`) rather than eagerly on the first as the old `emitAccumulatedGroupMap` did. In normal TinkerPop iteration the consumer pulls again immediately, so the stream-close window is a single pull-cycle wide — no lock-during-I/O or contention effect at any scale. Resource-lifecycle correctness is a bugs/crash-safety concern, not performance; flagged here only so the performance verdict is not read as ignoring it.

#### C4 REFUTED — the op-carrying path or the empty-op path forces an eager drain

Claim: threading ops through the projection stream could eagerly drain the underlying stream, destroying first-result latency and bounded memory (the property the track's `## Validation and Acceptance` pins).

Trace, empty-op (always taken today). `applyListShaping` (`:307-317`) reads `shaping.listShapingOps()` — a stored `List.copyOf(List.of())` that resolves to the shared empty immutable list, so no per-call allocation — and on `ops.isEmpty()` returns `source` directly. This is a structural bypass, not a no-op stage: the `rowProjectionSource` iterator flows straight through, so the first pull advances the stream by exactly one non-SKIP row. The added test `listShaping_emptyOps_firstPullAdvancesStreamByOneRow_notDrained` pins `verify(stream, times(1)).next(ctx)` against a three-row stream — the laziness contract holds.

Trace, op-carrying (future). `applyListShaping` composes ops by wrapping each around the prior iterator (`:312-316`) — pure iterator composition, no draining in the framework itself. Whether a given op is lazy is the op's own property (`unfold` / `reverse` can emit before drain; `fold` / `tail` are window drains by nature, as the `ListShapingOp` contract documents). There is no structural point in the framework that forces eager draining regardless of the op, which is the only thing this dimension flags for the future path. The `TagRepeatOp` placeholder test proves 1→N cardinality with `verify(stream, times(1)).next(ctx)` after the first of two emissions — the composition is lazy end to end.

Scale check. First-result latency and bounded memory are preserved at every scale on the hot path. VERDICT: NEGLIGIBLE (no regression; the pinned property holds). Not a finding.

Reference-accuracy note: mcp-steroid PSI (`steroid_execute_code`) times out in this repo (cold kotlinc > ~60s MCP limit), so no PSI find-usages was run. No verdict here depends on a caller search — hot-path status and arming frequency were established from the `State` machine and the TinkerPop `AbstractStep.processNextStart` contract by direct source read of the diff and `AbstractMatchPlanStep.java`, and the changed surface is self-contained. No `(grep-only)` caveat therefore attaches to any finding (there are none).
