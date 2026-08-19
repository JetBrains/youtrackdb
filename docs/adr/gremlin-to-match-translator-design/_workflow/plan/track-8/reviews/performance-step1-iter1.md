<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
evidence_base: {section: "## Evidence base", certs: 5, matches: 5}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: NEGLIGIBLE, anchor: "#### C2 "}
  - {id: C3, verdict: NEGLIGIBLE, anchor: "#### C3 "}
  - {id: C4, verdict: NEGLIGIBLE, anchor: "#### C4 "}
  - {id: C5, verdict: NEGLIGIBLE, anchor: "#### C5 "}
flags: [CONTRACT_OK]
-->

## Findings

No performance findings. `MultiPlanMatchStep` is a lazy N-plan concatenation
that meets every performance goal set for this step: O(1) first-result latency
and O(1) live-stream memory in the child count, no per-row allocation, and
optimal O(total rows) concatenation. Every candidate concern examined below was
refuted at realistic scale; none reached should-fix or suggestion.

## Evidence base

The step's hot dimension is result-row count (unbounded); the child count N is
bounded by union arity in the query text (single digit, tens at the extreme).
The certificates below separate per-row cost (the hot path) from per-arming and
per-setup cost (cold), then scale-validate each candidate.

#### C1 Laziness and bounded footprint hold — the design goal is met (CONFIRMED)

Verified against the concatenator and the base's row pump. `startPlanStream`
(`MultiPlanMatchStep.java:211-252`) returns one `MultipleExecutionStream` over a
producer whose `next` pulls one child from `childPlans.iterator()` and opens it
via `childPlan.start()` only when asked. `MultipleExecutionStream.hasNext`
(`MultipleExecutionStream.java:16-27`) advances to the next child only when the
current child's `hasNext` returns false, and closes the drained child before
calling `streamsSource.next`, so exactly one child stream is ever live. The base
consumes this lazily: `rowProjectionSource`
(`AbstractMatchPlanStep.java:326-360`) buffers a single payload and pulls the
underlying stream one row at a time; `applyListShaping`
(`AbstractMatchPlanStep.java:307-317`) is a structural bypass for the empty-op
case, so a plain `union(...)` (`ResultShaping.NONE`) keeps per-row laziness.
First `processNextStart()` opens only child 1 to yield the first row —
first-result latency is O(1) in N, not O(N). On partial consumption (a
downstream `limit`), only the children actually reached are ever opened; live
memory is one child stream. Both stated goals hold.

#### C2 Per-arming allocation in `startPlanStream` is not on the row hot path (NEGLIGIBLE)

`startPlanStream` allocates one anonymous `ExecutionStreamProducer`, one list
iterator, one `MultipleExecutionStream`, plus one `ChildContextStream` per child
inside `producer.next`. Cost trace: 3 + N allocations, all small, once per
arming (per iteration pass), never per row. SCALE CHECK — at 100 / 100K / 1M
result rows the allocation count is unchanged (3 + N) because arming count does
not scale with rows; per-row allocation added by this step is zero. VERDICT:
NEGLIGIBLE. The `final var childPlans = plans` snapshot is a plain field read,
no allocation.

#### C3 `ChildContextStream` per-row delegation is a bounded indirection (NEGLIGIBLE)

`ChildContextStream.hasNext/next/close` (`MultiPlanMatchStep.java:303-316`)
forward to the wrapped child stream with the child's own context, adding one
virtual call per `hasNext`/`next`/`close`. Cost trace: O(1) extra dispatch per
row, no allocation. The wrapper is a `private static final class` with trivial
bodies, so the JIT inlines it; the enclosing `MultipleExecutionStream` call site
is already polymorphic across production callers (`ParallelExecStep`,
`FetchFromIndexStep`), so adding one implementer does not change its dispatch
shape. The wrapper is a correctness requirement (per-child context isolation),
not an optimization candidate. VERDICT: NEGLIGIBLE at every scale.

#### C4 `closePlan` and `rewindPlan` O(N) sweeps are per-setup, not per-row (NEGLIGIBLE)

`closePlan` (`MultiPlanMatchStep.java:255-283`) iterates all N children,
including un-run `plans[i+1..]`; `rewindPlan` (`MultiPlanMatchStep.java:200-208`)
resets all N. Cost trace: O(N) close/reset calls once per close and once per
re-arm respectively. Closing an un-run `SelectExecutionPlan` walks an unstarted
step chain (cheap). SCALE CHECK — N is bounded by union arity, independent of
result-row count; at 1M rows these sweeps still run once with N iterations.
Closing all children (including un-run) is a leak-prevention requirement, not
excess work. VERDICT: NEGLIGIBLE.

#### C5 `clone()` O(N) deep copy is off the hot path and mandated by isolation (NEGLIGIBLE)

`clone()` (`MultiPlanMatchStep.java:158-190`) allocates N `BasicCommandContext`
objects and N `childPlan.copy(...)` deep copies of the child step chains, plus
two `List.copyOf`. Cost trace: O(N) plan copies per clone. Frequency: the
boundary step replaces the whole recognized traversal (all-or-nothing, D3), so
the traversal is top-level, not a per-input-traverser child of a
`repeat`/`flatMap`; TinkerPop clones a top-level traversal only a handful of
times per execution setup (source spawn, strategy application), never per row.
The per-child deep copy against an isolated child context is required for
concurrent-clone correctness (each clone needs its own mutable per-run state);
sharing would be a concurrency defect, not a speedup. SCALE CHECK — clone count
does not grow with result-row count. VERDICT: NEGLIGIBLE, and the O(N) cost is
the price of a correctness invariant, not a removable inefficiency.

## Reviewer notes

Reference-accuracy caveat: mcp-steroid PSI (`steroid_execute_code`) times out in
this repo (cold kotlinc exceeds the 60s MCP limit), so caller-frequency and
call-site-polymorphism facts rest on grep plus declaration reads of
`MultipleExecutionStream`, `ExecutionStreamProducer`, and `AbstractMatchPlanStep`
rather than a PSI find-usages sweep. The hot/cold classification depends on two
such facts: (a) `startPlanStream` / `closePlan` / `rewindPlan` are called
per-arming, confirmed by reading the base's `openArming` / `close` / `reset`
call sites (`AbstractMatchPlanStep.java:242-248, 427-429, 524-541`); (b) the
boundary step is top-level (clone not per-row), confirmed from the D3
all-or-nothing translation contract in the plan. Neither conclusion would flip
under a missed caller — the base is the sole driver of these hooks and there is
no per-row hook among the four. The test file adds no CI-slowing cost (two
threads joined with a 5s timeout, mock-backed streams, no large loops or
sleeps).
