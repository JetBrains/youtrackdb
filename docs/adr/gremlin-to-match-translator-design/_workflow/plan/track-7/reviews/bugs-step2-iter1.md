<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
evidence_base: {section: "## Evidence base", certs: 8, matches: 0}
cert_index:
  - {id: C1, verdict: NOTE, anchor: "#### C1 "}
  - {id: C2, verdict: REFUTED, anchor: "#### C2 "}
  - {id: C3, verdict: REFUTED, anchor: "#### C3 "}
  - {id: C4, verdict: REFUTED, anchor: "#### C4 "}
  - {id: C5, verdict: REFUTED, anchor: "#### C5 "}
  - {id: C6, verdict: REFUTED, anchor: "#### C6 "}
  - {id: C7, verdict: REFUTED, anchor: "#### C7 "}
  - {id: C8, verdict: NOTE, anchor: "#### C8 "}
flags: [CONTRACT_OK]
-->

## Findings

No bugs found. The step wires an ordered list-shaping carrier through the boundary base without changing any observable result: `listShapingOps` is empty for every traversal that exists today, so `applyListShaping` returns the projection source untouched and the shaped iterator is exactly the projection stream (per-row) or the single-map iterator (group). The new `shapedPayloads` field is nulled on every path that releases the stream it reads from — the NEW/REARMED reopen, `releaseStream()`, `releaseStreamAndClosePlan()`, and `resetLifecycleForClone()` — so a stale iterator never outlives its source. The lazy `rowProjectionSource` iterator handles the null-is-a-legitimate-payload case with a separate `hasBuffered` flag and keeps `hasNext()` idempotent. The one behavioral change — the group-barrier path defers its stream release by one pull-cycle — was traced and confirmed covered by `close()` (see C1). The `@Nonnull` / defensive-copy contract on the new carrier is honored. The refutation trail is in the Evidence base.

## Evidence base

Reference-accuracy caveat: mcp-steroid PSI (`steroid_execute_code`) is non-functional in this repo (cold-kotlinc compile exceeds the ~60s MCP HTTP limit), so all symbol audits below are grep + declaration reads over `core/src`, not PSI find-usages `(grep-only)`. The enumeration covered both `main` and `test` trees.

#### C1 Group-barrier path defers stream release by one pull-cycle — NOTE (verified behavioral change, not a defect)
Focus claim: the group-barrier stream is no longer released eagerly, so it could leak the cursor when a consumer takes the single group result and abandons the traversal.

The change is real and deliberate. Pre-step (Step 1) `emitAccumulatedGroupMap` drained the stream, set `state = DRAINED`, called `releaseStream()`, then returned the one map traverser — so the stream was closed before `.next()` returned. Now `accumulatedGroupMapSource()` (AbstractMatchPlanStep.java:368-381) only drains the stream into the map and returns `List.<Object>of(map).iterator()`; the `DRAINED` transition + `releaseStream()` fire on the *next* `processNextStart()` pull when `shapedPayloads.hasNext()` returns false (:258-263). Between the first pull (map emitted, `state == OPEN`, `openStream != null`) and the second pull, the fully-drained cursor stays open.

Traced to no leak on any contract-honoring path:
- Full iteration or explicit close: the second pull fires `DRAINED` + `releaseStream()` (:261-262), or `close()` runs first. In `close()` (:523-541) `state == OPEN` and `openStream != null`, so it calls `releaseStreamAndClosePlan()` (:535), closing stream and plan. `AutoCloseable` is honored; TinkerPop invokes `close()` on both exhaustion (`DefaultTraversal.hasNext` → `closeIterator`) and early termination (`Traversal.close()`). No leak.
- The single residual is a consumer that takes the one map via a bare `.next()` and then neither exhausts, errors, nor closes the traversal. Step 1 already left the *plan* open in that same pattern (the plan closes only through `close()`), so the close-or-exhaust contract was already required to avoid a leak there. Step 2 extends what a contract violation leaks from plan-only to stream+plan, and makes the group path consistent with the per-row path, which has always left its stream open on an abandoned or partial consume. No new leak class originates here.
- No double-close: if the second pull fires `releaseStream()` first, `close()` then sees `openStream == null` and `started`, so it only calls `closePlan()` (:536-538) — stream closed once, plan closed once. If `close()` fires first, a later `processNextStart()` sees `state == CLOSED` and throws at :238-240 before any release. Neither ordering closes the stream twice.

Verdict: documented lifetime change, covered by `close()`, no cursor leak on any contract-honoring path.

#### C2 `shapedPayloads` is nulled on every path that releases its underlying stream — REFUTED
Candidate: the shaped iterator outlives the stream it captured, so a later pull reads a closed stream.

`rowProjectionSource()` captures `openStream` into a local `stream` and `planContext()` into `ctx` at build time (:327-328), so the iterator is bound to exactly the stream it was built for. The field is dropped everywhere the bound stream goes away:
- NEW/REARMED reopen: `shapedPayloads = null` (:249) after `openArming()` installs the fresh stream, so a stale iterator a superseded arming left behind (e.g. `reset()` from `OPEN`, which does not null it — :506-512) is discarded and the pull rebuilds against the new stream. Load-bearing: without it, the `if (shapedPayloads == null)` build at :252 would be skipped and the old iterator would read the closed prior stream.
- `releaseStream()` (:457), `releaseStreamAndClosePlan()` (:475), and `resetLifecycleForClone()` (:555) each set `shapedPayloads = null` alongside `openStream = null`.
The build guard (`shapedPayloads == null`, :252) can only be reached with `state == OPEN` immediately after a successful `openArming()` (which sets `openStream` non-null), so the source captured in `rowProjectionSource()`/`accumulatedGroupMapSource()` is never null. No stale-iterator or read-after-release path survives.

#### C3 Shaped build is correctly inside the try; openArming stays outside — REFUTED
Candidate: the eager group drain runs outside the terminal handler, so a drain failure would not release the plan.

`openArming()` runs outside the try (:244, in the NEW/REARMED block), correct because it releases the plan itself on a partial start (its internal catch calls `closePlan()`, :433-442) — no stream is open for the outer handler to release. The shaped build runs inside the try (:252-257): `openShapedPayloads()` → `accumulatedGroupMapSource()` drains the stream eagerly, and a drain failure propagates to the `catch (RuntimeException | Error)` at :269, which sets `state = CLOSED` and calls `releaseStreamAndClosePlan()` (:277-283) — the plan is released. `FastNoSuchElementException` is caught first (:267-268) and rethrown without the terminal release, so normal exhaustion is not mistaken for a failure. Ordering and placement are correct; the `shapedPayloads = null` added at :249 does not move `openArming()` relative to the try.

#### C4 Lazy `rowProjectionSource` iterator honors the hasNext/next contract — REFUTED
Candidate: the one-payload buffer mishandles null payloads, double-advances, or breaks idempotent `hasNext()`.

`rowProjectionSource()` (:326-360):
- Null is a legitimate payload (an unmatched optional projects to `null`), and emission is tracked by the `hasBuffered` boolean, not by a null sentinel (:330-331). `next()` reads `bufferedPayload`, sets it back to `null` and clears `hasBuffered` (:354-357), so a buffered `null` is returned correctly and the field doubling as both "cleared" and "a real null" is disambiguated by the flag.
- `hasNext()` is idempotent: with `hasBuffered` true it returns immediately (:335-336); otherwise it advances the stream once to buffer the next non-`SKIP` payload and sets the flag (:338-344), so a second `hasNext()` returns the same buffered result without re-advancing.
- The skip-loop (`while (stream.hasNext(ctx))` … `if (payload != SKIP)`, :338-345) consumes `dropNullRows`/`dropOnAbsent` rows without emitting them — `SKIP` is a private identity sentinel, so `payload != SKIP` is a safe reference compare that a real `null` payload passes.
- `next()` calls `hasNext()` only when `!hasBuffered` (:351) and throws `NoSuchElementException` when the stream is dry — but `processNextStart` only calls `next()` after `hasNext()` returned true (:258-265), so the throw is unreachable on the production path. `armingGraph`, read by `projectOrSkip` during buffering, is non-null throughout an `OPEN` arming (C1 of the Step 1 review; unchanged here).

#### C5 Empty op list is a true structural bypass — per-row and group output byte-for-byte identical to Step 1 — REFUTED
Candidate: wrapping the source in a shaping stage changes output or laziness even with no op.

`applyListShaping` returns `source` unchanged when `shaping.listShapingOps().isEmpty()` (:307-311) — no stage is wrapped, so the source iterator is returned by identity. No production code sets `listShapingOps`: every shaping producer builds from `ResultShaping.NONE` plus `withX` chains, and grep across `core/src` finds `withListShapingOps` called only from the test file `(grep-only, both trees)`. So the bypass is taken for every traversal today.
- Per-row: `shapedPayloads` is the `rowProjectionSource` iterator; each `processNextStart` pull emits one traverser per non-`SKIP` row and drains on exhaustion — the same one-row-per-pull, skip-`SKIP`, drain-on-empty behavior as the Step 1 inline `while` loop. First-pull laziness is preserved (one row consumed per pull), matching the `listShaping_emptyOps_firstPullAdvancesStreamByOneRow_notDrained` pin.
- Group: `accumulatedGroupMapSource()` builds the same `LinkedHashMap` via the same `convertMapColumn`/`convertGroupValue` calls while `armingGraph` is still set, and emits exactly one traverser carrying that map. Empty input still yields one empty-map traverser (`List.of(emptyMap)`), matching Step 1's unconditional single emission and native `group()` semantics.

#### C6 Exhaustion re-keyed to `shapedPayloads.hasNext()` is correct for cardinality-changing ops — REFUTED
Candidate: keying exhaustion on the shaped iterator instead of the raw stream drops or duplicates results.

`processNextStart` ends the arming when `shapedPayloads.hasNext()` is false (:258-263), not when the underlying stream is dry. This is required once an op can decouple emitted-payload count from row count: a `fold`/`tail` window-drain fully consumes the stream while still owing its buffered result, and an `unfold` flat-map owes several payloads per row. For the empty-op bypass the shaped iterator *is* the source, so "shaped dry" equals "stream dry" — identical to Step 1. The test `TagRepeatOp` (1→N) confirms one source row yields two traversers and exhaustion fires only after both are emitted. Correct.

#### C7 `@Nonnull` carrier, defensive copy, and no broken construction site — REFUTED
Candidate: the new record component admits null, mutates through an aliased list, or breaks an existing `ResultShaping` construction site.

`listShapingOps` is `@Nonnull` on the record component (ResultShaping.java, record header) and defensively copied in the compact constructor (`listShapingOps = List.copyOf(listShapingOps)`), which throws NPE on a null list or null element — enforcing the contract at construction and blocking external mutation of the stored list. `applyListShaping` reads it via the accessor and calls `.isEmpty()` on a guaranteed-non-null list (:308-309). Every `withX` builder threads `listShapingOps` through unchanged and `withListShapingOps` supplies `ops` in the 8th position; all eight components are passed positionally in each builder and in `NONE`. No `new ResultShaping(...)` call site exists outside the record itself `(grep-only)` — every production producer uses `NONE` + `withX` — so adding the 8th component breaks no caller. `CONTRACT_OK`.

#### C8 Single-threaded lifecycle field, no new shared-mutable surface — NOTE (triage backstop)
The new `shapedPayloads` field is per-instance mutable lifecycle state written and read only inside `processNextStart` / the release / clone paths, driven single-threaded per traversal instance exactly as `openStream` / `armingGraph` / `state` are. This is the same concurrent-looking lifecycle surface the Step 1 review flagged for `review-concurrency` triage awareness (clone publication of the non-final plan, per-execution context isolation). This step adds no new shared-mutable surface — `shapedPayloads` is `private`, nulled in lockstep with `openStream` on every release, and reset in `resetLifecycleForClone()` alongside the other per-arming fields — so no new interleaving hazard originates here. Routing pointer only, not an interleaving analysis (which is `review-concurrency`'s alone): if `review-concurrency` was not triaged onto this step, the clone-publication model remains its territory.
