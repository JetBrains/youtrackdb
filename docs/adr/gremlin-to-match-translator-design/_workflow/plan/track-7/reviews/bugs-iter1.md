<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
evidence_base: {section: "## Evidence base", certs: 11, matches: 0}
cert_index:
  - {id: C1, verdict: REFUTED, anchor: "#### C1 "}
  - {id: C2, verdict: NOTE, anchor: "#### C2 "}
  - {id: C3, verdict: REFUTED, anchor: "#### C3 "}
  - {id: C4, verdict: REFUTED, anchor: "#### C4 "}
  - {id: C5, verdict: REFUTED, anchor: "#### C5 "}
  - {id: C6, verdict: REFUTED, anchor: "#### C6 "}
  - {id: C7, verdict: REFUTED, anchor: "#### C7 "}
  - {id: C8, verdict: REFUTED, anchor: "#### C8 "}
  - {id: C9, verdict: REFUTED, anchor: "#### C9 "}
  - {id: C10, verdict: NOTE, anchor: "#### C10 "}
  - {id: C11, verdict: NOTE, anchor: "#### C11 "}
flags: [CONTRACT_OK]
-->

## Findings

No bugs found by single-threaded sequential reasoning. The base extraction (Step 1) and the ordered list-shaping carrier (Step 2) combine into a behavior-neutral refactor: `listShapingOps` is empty for every traversal that exists today (grep confirms no production `withListShapingOps` caller), so `applyListShaping` returns the projection source by identity and `shapedPayloads` is exactly the per-row projection stream or the single group-map iterator that Step 1 produced inline.

The track-level lens — cross-step interactions the two step reviews could not see — clears as well. The one genuine cross-step seam is that Step 1's `reset()` was written before Step 2's `shapedPayloads` field existed and does not null it; I traced every reader and teardown path and confirmed the stale iterator is dropped before use on all of them (C1). The single behavioral change (the group-barrier path releases its stream one pull-cycle later than Step 1 did) is covered by `close()` on every contract-honoring path and opens no new leak class (C2). Null-as-payload handling, the empty-op structural bypass, exhaustion re-keying to the shaped iterator, `clone()` isolation, the strategy idempotency broadening, and the `ResultShaping` `@Nonnull` carrier all hold (C3–C9). The full refutation trail is in the Evidence base.

## Evidence base

Reference-accuracy caveat: mcp-steroid PSI (`steroid_execute_code`) is non-functional in this repo (cold-kotlinc compile exceeds the ~60s MCP HTTP limit), so every symbol audit below is grep + declaration reads over `core/src`, not PSI find-usages `(grep-only)`. The enumeration covered both `main` and `test` trees. Line numbers reference the post-track files (`AbstractMatchPlanStep.java` is the new base).

#### C1 Cross-step: Step 1's `reset()` does not null Step 2's `shapedPayloads`, and every reader/teardown covers it — REFUTED
Candidate (track-level, unseen by either step review): `reset()` was authored in Step 1, before `shapedPayloads` existed. Step 2 added `shapedPayloads` and nulled it in `openArming`'s reopen, `releaseStream()`, `releaseStreamAndClosePlan()`, and `resetLifecycleForClone()` — but not in `reset()`. So a `reset()` from `OPEN` leaves `shapedPayloads` pointing at the just-superseded stream's iterator. If any path read that stale iterator, it would drive a stream that a later reopen closed.

Traced to no stale read on any path out of `REARMED`:
- The only reader of `shapedPayloads` is `processNextStart`. On entry with `state == REARMED` it takes the NEW/REARMED branch, which calls `openArming()` (closing the stale stream) and then executes `shapedPayloads = null` before the `try` block reads the field. So the stale iterator is dropped and rebuilt against the fresh stream — the pull never sees it. This is load-bearing: the null assignment sits after `openArming()` inside the reopen branch, so the `if (shapedPayloads == null)` build guard fires and rebuilds.
- `close()` from `REARMED` sees `openStream != null` (a `reset()` from `OPEN` deliberately leaves the stream open) and routes to `releaseStreamAndClosePlan()`, which nulls `shapedPayloads`. No read occurs (state goes `CLOSED`).
- The stale iterator is never driven between the `reset()` and the reopen — there is no code path that touches `shapedPayloads` outside `processNextStart` and the teardown helpers.

So the omission in `reset()` is masked by the unconditional null at reopen and by the teardown helpers. It is not a defect today. It is fragile against a future change that reads `shapedPayloads` while `REARMED` without going through the reopen null; that risk belongs to Track 8's advance-on-drain path, which the track file already flags must route through the NEW/REARMED reopen. No finding for this track.

#### C2 Group-barrier path defers stream release by one pull-cycle vs Step 1's eager release — NOTE (behavioral change, covered by `close()`, no leak)
Focus claim: the group stream is no longer released eagerly, so a consumer that takes the single group map and abandons the traversal could leak the cursor.

The change is real and deliberate. Step 1's `emitAccumulatedGroupMap` drained the stream, set `state = DRAINED`, called `releaseStream()`, then returned the one map traverser, so the stream closed before `.next()` returned. Now `accumulatedGroupMapSource()` only drains the stream into the `LinkedHashMap` and returns `List.<Object>of(map).iterator()`; the `DRAINED` transition and `releaseStream()` fire on the *next* `processNextStart()` pull, when `shapedPayloads.hasNext()` returns false. Between the first pull (map emitted, `state == OPEN`, `openStream != null` with a drained-but-open cursor) and the second pull, the exhausted cursor stays open.

Traced to no leak on any contract-honoring path:
- Full iteration or explicit close: the second pull fires `DRAINED` + `releaseStream()`, or `close()` runs first. In `close()`, `state == OPEN` and `openStream != null`, so it calls `releaseStreamAndClosePlan()`, closing stream then plan. TinkerPop invokes `close()` on both exhaustion (`DefaultTraversal.hasNext` → `closeIterator`) and early termination (`Traversal.close()`). No leak.
- The single residual is a consumer that takes the one map via a bare `.next()` and then never exhausts, errors, or closes. Step 1 already left the *plan* open in exactly that pattern (the plan closes only through `close()`), so the close-or-exhaust contract was already required to avoid a leak there. Step 2 widens what a contract violation leaks from plan-only to stream+plan and aligns the group path with the per-row path, which has always left its stream open on an abandoned or partial consume. No new leak class originates here.
- No double-close: if the second pull runs `releaseStream()` first, `close()` then sees `openStream == null` and `started`, so it calls only `closePlan()` — stream closed once, plan once. If `close()` runs first, a later `processNextStart()` sees `state == CLOSED` and throws immediately, before any release.

Verdict: documented lifetime change, covered by `close()`, no cursor leak on any contract-honoring path.

#### C3 Empty-op structural bypass is behavior-neutral for the single-plan path (per-row and group) — REFUTED
Candidate: threading the projection source through a shaping stage changes output or laziness even with no op.

`applyListShaping` returns `source` by identity when `shaping.listShapingOps().isEmpty()` — no stage is wrapped. Grep across `core/src` finds `withListShapingOps` called only from the test file `(grep-only, both trees)`, and every production shaping producer builds from `ResultShaping.NONE` plus `withX` chains, so the op list is empty and the bypass is taken for every traversal today.
- Per-row: `shapedPayloads` is the `rowProjectionSource` iterator; each pull emits one traverser per non-`SKIP` row and drains on exhaustion — the same one-row-per-pull, skip-`SKIP`, drain-on-empty behavior as Step 1's inline `while` loop. First-pull laziness holds (one row consumed per pull), matching the `listShaping_emptyOps_firstPullAdvancesStreamByOneRow_notDrained` pin.
- Group: `accumulatedGroupMapSource()` builds the same `LinkedHashMap` through the same `convertMapColumn`/`convertGroupValue` calls while `armingGraph` is still set, and emits exactly one traverser carrying that map. Empty input still yields one empty-map traverser (`List.of(emptyMap)`), matching Step 1's unconditional single emission and native `group()` semantics.

#### C4 `shapedPayloads` never read after its underlying stream is released — REFUTED
Candidate: the shaped iterator outlives the stream it captured, so a later pull reads a closed stream.

`rowProjectionSource()` captures `openStream` into a local `stream` and `planContext()` into `ctx` at build time, binding the iterator to exactly the stream it was built for. The field is dropped everywhere the bound stream goes away: the NEW/REARMED reopen (after `openArming()` installs the fresh stream — see C1), `releaseStream()`, `releaseStreamAndClosePlan()`, and `resetLifecycleForClone()` each set `shapedPayloads = null`. The build guard (`shapedPayloads == null`) is reachable only with `state == OPEN` immediately after a successful `openArming()` (which sets `openStream` non-null), so the captured source is never null. No read-after-release path survives.

#### C5 Lazy `rowProjectionSource` honors the hasNext/next contract and the null-as-payload case — REFUTED
Candidate: the one-payload buffer mishandles null payloads, double-advances, or breaks idempotent `hasNext()`.

Null is a legitimate payload (an unmatched optional projects to `null`), and emission is tracked by the `hasBuffered` boolean, not a null sentinel. `next()` reads `bufferedPayload`, sets it back to `null`, and clears `hasBuffered`, so a buffered `null` returns correctly and the field doubling as both "cleared" and "a real null" is disambiguated by the flag. `hasNext()` is idempotent: with `hasBuffered` true it returns immediately, else it advances the stream once to buffer the next non-`SKIP` payload and sets the flag, so a repeat call returns the same buffered result without re-advancing. The skip loop consumes `dropNullRows`/`dropOnAbsent` rows without emitting them via the private-identity `SKIP` sentinel (`payload != SKIP` is a reference compare a real `null` passes). `next()` calls `hasNext()` only when `!hasBuffered`; `processNextStart` always calls `hasNext()` first, so the `NoSuchElementException` throw is unreachable on the production path. `armingGraph`, read by `projectOrSkip` during buffering, is non-null throughout an `OPEN` arming.

#### C6 Exhaustion re-keyed to `shapedPayloads.hasNext()` is correct, including for cardinality-changing ops — REFUTED
Candidate: keying exhaustion on the shaped iterator rather than the raw stream drops or duplicates results.

`processNextStart` ends the arming when `shapedPayloads.hasNext()` is false, not when the underlying stream is dry, then throws `FastNoSuchElementException` (which TinkerPop's `AbstractStep.hasNext` swallows to `false`). This is required once an op can decouple emitted-payload count from row count: a `fold`/`tail` window-drain fully consumes the stream while still owing its buffered result, and an `unfold` flat-map owes several payloads per row. For the empty-op bypass the shaped iterator *is* the source, so "shaped dry" equals "stream dry" — identical to Step 1. `FastNoSuchElementException` is caught and rethrown ahead of the terminal `catch (RuntimeException | Error)`, so normal exhaustion is never mistaken for an iteration failure. The test `TagRepeatOp` (1→N) confirms one source row yields two traversers and exhaustion fires only after both are emitted.

#### C7 `clone()` isolation via `resetLifecycleForClone()` covers `shapedPayloads` — REFUTED
Candidate: a clone inherits the original's per-arming state (aliased stream / shaped iterator) and drives or tears down the original's cursor.

`AbstractStep.clone()` shallow-copies the base's per-arming fields (`state`, `openStream`, `armingGraph`, `shapedPayloads`) into the clone, then calls the base `reset()` on the clone while it still aliases the original's stream — and `reset()` deliberately does not close the stream, so the original's in-flight cursor is not torn down. `YTDBMatchPlanStep.clone()` then installs the clone's own plan copy on an isolated child context and calls `resetLifecycleForClone()`, which nulls `openStream`, `armingGraph`, and `shapedPayloads` and sets `state = NEW`. Step 2 correctly extended `resetLifecycleForClone()` to include `shapedPayloads`; without it a clone taken from an `OPEN` step would alias the original's shaped iterator. `resetLifecycleForClone()` is `protected final`, so a subclass cannot forget or override the reset. The clone is born `NEW` with null per-arming state and its own plan copy.

#### C8 Strategy idempotency broadened from `YTDBMatchPlanStep` to `AbstractMatchPlanStep` is a current-set-preserving superset — REFUTED
Candidate: broadening `containsBoundaryStep` to `instanceof AbstractMatchPlanStep` matches steps it should not, or the `hasVertexGraphStart` fast-path plus the broadened scan changes recognition.

`YTDBMatchPlanStep` is the only subclass of `AbstractMatchPlanStep` today `(grep-only, both trees)`, so `step instanceof AbstractMatchPlanStep` matches exactly the same instances as the old `step instanceof YTDBMatchPlanStep` — a superset that currently equals the prior set, adding no false positive. The production idempotency scan (`GremlinToMatchStrategy.java:350`) is the only production reach retargeted; the remaining `instanceof YTDBMatchPlanStep` sites are all in test files, unchanged and still valid since production builds only the concrete step. Recognition and idempotency stay deterministic and behavior-neutral.

#### C9 `ResultShaping` `@Nonnull` carrier, defensive copy, and no broken construction site — REFUTED (CONTRACT_OK)
Candidate: the new 8th record component admits null, mutates through an aliased list, or breaks an existing construction site.

`listShapingOps` is `@Nonnull` and defensively copied in the compact constructor (`listShapingOps = List.copyOf(listShapingOps)`), which throws NPE on a null list or null element and blocks external mutation of the stored list. `applyListShaping` reads it through the accessor and calls `.isEmpty()` on a guaranteed-non-null list. Every `withX` builder threads `listShapingOps` through unchanged in the 8th position, `NONE` passes `List.of()`, and `withListShapingOps` supplies `ops`. No `new ResultShaping(...)` call exists outside the record itself `(grep-only)` — every producer uses `NONE` + `withX` — so adding the 8th component breaks no caller. The base copies `presencePropertyKeys` into a `Set` exactly as before.

#### C10 `openArming()` failure path and the REARMED start-fail double `closePlan()` — NOTE (pre-existing, idempotent)
Candidate: a `rewindPlan` / `startPlanStream` failure inside the reopen branch, which sits outside the `try`, leaves state inconsistent or double-closes the plan.

`openArming()` runs outside the `try` because it releases the plan itself on a partial start (its internal `catch` calls `closePlan()`), with no open stream for the outer handler to release. If `startPlanStream()` throws while `REARMED`, `openArming` closes the plan and rethrows; state stays `REARMED` (not `CLOSED`), so a later `close()` reaching the `else if (started)` branch calls `closePlan()` a second time. This double-close is benign — the class documents the `SelectExecutionPlan` close guard as sticky (idempotent) — and it is unchanged from Step 1 (the identical structure existed before the extraction). The added `shapedPayloads = null` sits after `openArming()` in the reopen branch, so it does not move `openArming()` relative to the `try` and introduces no new failure ordering. No regression.

#### C11 Concurrency triage backstop — clone-publication / shared-parent-context surface — NOTE (routing pointer, not an interleaving analysis)
`AbstractMatchPlanStep` / `YTDBMatchPlanStep` carry concurrent-looking code: `clone()` is documented as the per-execution isolation point against concurrent clones, the non-final `plan` field carries a JMM final-field-publication rationale, and `BasicCommandContext` parent/child variable propagation is called out as safe only while the template context seeds no `$current`/`$matched`/alias/LET bindings. This surface is largely inherited from Track 2 and only lightly touched here (the `resetLifecycleForClone()` extraction, the comment retarget). Track 7's review roster shows bugs + performance only (`bugs-step1`, `bugs-step2`, `performance-step*`), with no `concurrency` file for this track. If `review-concurrency` was not triaged onto this track, the clone-publication and shared-parent-context model remains its territory. This is a routing pointer only; reasoning about any interleaving is `review-concurrency`'s alone, per the ownership boundary. This step adds no new shared-mutable surface — `shapedPayloads` is `private`, nulled in lockstep with `openStream` on every release, and reset in `resetLifecycleForClone()` alongside the other per-arming fields.
