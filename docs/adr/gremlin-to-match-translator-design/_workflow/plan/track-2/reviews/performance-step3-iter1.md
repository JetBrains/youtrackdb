<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
evidence_base: {section: "## Evidence base", certs: 5, matches: 0}
cert_index:
  - {id: C1, verdict: NEGLIGIBLE, anchor: "#### C1 "}
  - {id: C2, verdict: NEGLIGIBLE, anchor: "#### C2 "}
  - {id: C3, verdict: NEGLIGIBLE, anchor: "#### C3 "}
  - {id: C4, verdict: NEGLIGIBLE, anchor: "#### C4 "}
  - {id: C5, verdict: NEGLIGIBLE, anchor: "#### C5 "}
flags: [CONTRACT_OK]
-->

## Findings

No performance findings. `GremlinToMatchStrategy.apply()` runs on the every-traversal
critical path, but in this step it is a decline-only skeleton: the translator returns
`Optional.empty()` for every input, so the live cost per traversal is a short gate
cascade with two escape-analysis-scalarizable allocations and one single-digit
`instanceof` loop. The mutation, planner, and `MatchExecutionPlanner` paths are dead
code until a recognizer is wired. All four orchestrator focus items — short-circuit
ordering, idempotency scan cost, per-apply allocation, kill-switch resolution — resolve
to negligible at production traversal rates.

## Evidence base

#### C1 Kill-switch resolution is a cheap lookup, not a repeated expensive resolution — NEGLIGIBLE
CLAIM: the per-session config read on every non-graph-gated apply is expensive.
`resolveSessionIfEnabled` (`GremlinToMatchStrategy.java:253-256`) reads the flag via
`session.getConfiguration().getValueAsBoolean(QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED)`.
Tracing the call:
`ContextConfiguration.getValueAsBoolean` (`ContextConfiguration.java:149`) → `getValue`
(`:90`) → `config.containsKey(key)` + `config.get(key)` on a `ConcurrentHashMap`
(`:51`), else `GlobalConfiguration.getValue()` (`:1504`), a single `volatile` read.
The default is stored as a `Boolean`, so `getValueAsBoolean` takes the
`v instanceof Boolean` branch (`:154`) — no `Boolean.parseBoolean`, no `toString`, no
allocation. COST TRACE: O(1), at most two lock-free `ConcurrentHashMap` reads + one
volatile read; zero allocation, no I/O, no parse. SCALE CHECK at 1M+ traversals/sec:
noise. VERDICT: NEGLIGIBLE — matches the design's "cheap runtime kill-switch"
characterization. (grep-only: PSI `execute_code` timed out on index warmup, per the
orchestrator's fallback note; call chain read from source + confirmed by field types.)

#### C2 `tx.readWrite()` on the steady-state path does no work and allocates nothing — NEGLIGIBLE
CLAIM: `tx.readWrite()` (`GremlinToMatchStrategy.java:251`), called on every YTDB-graph
apply before the config read, does per-apply work. `readWrite()` is TinkerPop
`AbstractTransaction.readWrite()`, which runs `doReadWrite()`/`doOpen()` only when the
transaction is not already open. A Gremlin traversal is compiled inside an open
transaction, so `YTDBTransaction.isOpen()` (`YTDBTransaction.java:104-110`) returns
`activeSession.isTxActive()` and `readWrite()` returns after that check. COST TRACE:
one boolean method call on the happy path; no `begin()`, no allocation, no I/O. Only if
the strategy ran outside an open tx would `doOpen()` fire — not the compilation path.
VERDICT: NEGLIGIBLE. (grep-only, same fallback caveat; `readWrite` body is in the
TinkerPop fork jar, `isOpen`/`doOpen` read from `YTDBTransaction.java`.)

#### C3 Idempotency scan is O(steps) with no per-step allocation and is not re-run redundantly — NEGLIGIBLE
CLAIM: the whole-list boundary scan on every apply is a cost. `containsBoundaryStep`
(`GremlinToMatchStrategy.java:266-273`) iterates `traversal.getSteps()`. Decompiling
`DefaultTraversal.getSteps()` (gremlin-core `3.8.1-af9db90-SNAPSHOT`, the pom-pinned
version) shows it returns the cached `unmodifiableSteps` field — a single `getfield`,
zero allocation per call. The loop body is `instanceof YTDBMatchPlanStep` per step, no
allocation. Step count is single-digit, bounded by query shape (design D7). The scan
runs exactly once per apply (called once in `applyOrDecline`, `:206`); no redundant
re-run. COST TRACE: one `Iterator` allocation + O(steps) `instanceof`. SCALE CHECK:
negligible at any realistic traversal rate; the `Iterator` is an escape-analysis
candidate. VERDICT: NEGLIGIBLE — matches design D7's "O(N) over a single-digit step
count, negligible cost."

#### C4 Per-apply allocation on the decline path is two scalarizable objects; the seams are not re-allocated — NEGLIGIBLE
CLAIM: allocations on the decline path could be hoisted or avoided. The seam fields
`translator` and `planBuilder` (`GremlinToMatchStrategy.java:172-176`) are instance
fields assigned once in the constructor; the production `INSTANCE` (`:168-170`) captures
the two method references once at static init, not per apply. `NO_ORDERING = Set.of()`
(`:165`) is static; `applyPrior()`/`applyPost()` (`:378-385`) return it — no per-call
allocation. The skeleton `translate` returns `Optional.empty()` (`:212`), a singleton.
The only per-apply allocations are: (1) the `Optional` from `traversal.getGraph()`
(`:244`) — bytecode confirms `Optional.ofNullable` (and note it recurses up the parent
chain when the graph is EmptyGraph, but a top-level `g.V()` returns after one alloc);
(2) the `Iterator` in C3. Both are short-lived, escape-analysis candidates, and
`getGraph()`'s `Optional` is dictated by the TinkerPop contract (unavoidable without a
raw-field accessor the fork does not expose). COST TRACE: ~2 scalarizable allocations
per decline; the seam/ordering fields add none. VERDICT: NEGLIGIBLE.

#### C5 Short-circuit ordering is sound; no scan runs before the cheap discriminating gates — NEGLIGIBLE
CLAIM: an allocation or scan runs before the cheapest, most-likely-to-decline check.
The gate order in `applyOrDecline` (`GremlinToMatchStrategy.java:204-216`) is:
(1) `resolveSessionIfEnabled` — cheapest graph-type discriminator first
(`instanceof YTDBGraph` at `:245` rejects non-YTDB / detached traversals before `tx()`
or `readWrite()` is ever called); (2) `containsBoundaryStep` scan; (3)
`hasVertexGraphStart` (`getStartStep()` = `steps.get(0)`, O(1), zero alloc per
decompiled bytecode) — the gate that discriminates the common case, since most real
Gremlin traversals do not start with a vertex `GraphStep` and every `g.E()` edge start
is rejected here. The one nuance: the O(steps) idempotency scan (2) runs before the
O(1) start-step gate (3). Reordering (3) before (2) would let the far more common
non-`g.V()`-start traversal skip the scan entirely. But the scan is already negligible
(C3), the design fixes this order deliberately (D4/D7 place idempotency as the early
guard), and the reordering saves a single-digit `instanceof` loop only on traversals
that also failed the start gate — an unmeasurable delta. Not worth a finding at skeleton
scale; noted for the reviewer record only. VERDICT: NEGLIGIBLE.
