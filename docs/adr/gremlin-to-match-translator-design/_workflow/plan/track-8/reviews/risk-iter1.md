<!-- MANIFEST
findings: 6   severity: {blocker: 0, should-fix: 4, suggestion: 2}
index:
  - {id: R1, sev: should-fix, loc: AbstractMatchPlanStep.java:89-133, anchor: "### R1 ", cert: E1, basis: "N-plan advance-on-drain seam is not provided by the Track 7 base (private per-stream state + single-ctx-per-arming capture); Track 8 scope omits modifying AbstractMatchPlanStep, contradicting Track 7's handoff"}
  - {id: R2, sev: should-fix, loc: YTDBMatchPlanStep.java:94-124, anchor: "### R2 ", cert: E2, basis: "multi-alias union children reopen the parent-context bleed the single-plan clone note explicitly warns about; each child plan needs its own isolated context; warrants the concurrency code-review category"}
  - {id: R3, sev: should-fix, loc: GremlinToMatchStrategy.java:399-421, anchor: "### R3 ", cert: E3, basis: "union cache policy underspecified: fingerprint is single-MatchPlanInputs and cache value is single InternalExecutionPlan; cross-shape collision + value-type mismatch risk"}
  - {id: R4, sev: should-fix, loc: AbstractMatchPlanStep.java:807-811, anchor: "### R4 ", cert: E4, basis: "leak surfaces: closePlan() must close never-started plans[N+1..], and a build-time partial-build must close plans[0..k-1] if plan[k] build throws"}
  - {id: R5, sev: suggestion, loc: MatchExecutionPlanner.java:4552, anchor: "### R5 ", cert: A1, basis: "union children must each be a SEPARATE SelectExecutionPlan; routing them into one MatchPlanInputs silently becomes a CartesianProductStep join"}
  - {id: R6, sev: suggestion, loc: AbstractMatchPlanStep.java:267-284, anchor: "### R6 ", cert: T1, basis: "exception-stops-advance semantics must replicate the base's terminal-failure release (addSuppressed, close-all, move to CLOSED); risk depends on which seam realization is chosen"}
evidence_base: {section: "## Evidence base", certs: 7, matches: 7}
cert_index:
  - {id: E1, verdict: HIGH,   anchor: "#### E1 "}
  - {id: E2, verdict: MEDIUM, anchor: "#### E2 "}
  - {id: E3, verdict: MEDIUM, anchor: "#### E3 "}
  - {id: E4, verdict: MEDIUM, anchor: "#### E4 "}
  - {id: A1, verdict: VALIDATED, anchor: "#### A1 "}
  - {id: A2, verdict: VALIDATED, anchor: "#### A2 "}
  - {id: T1, verdict: ACHIEVABLE, anchor: "#### T1 "}
flags: [CONTRACT_OK]
-->

# Track 8 risk review — iteration 1

Track 8 builds `union(...)` on the Track 7 boundary base. The union semantics
(concatenated multiset, all-children-agree-on-output-type decline gate) are
sound and the design correctly rejects MATCH's cartesian `splitDisjointPatterns`
path. The risk is not in the semantics — it is in the seam mechanics: the Track 7
base was extracted with a single-plan lifecycle whose per-stream state and
session-rebind are private and per-arming-singular, and Track 8's declared scope
does not include modifying it. Four should-fix findings cluster on that seam, on
the multi-alias clone-isolation surface Track 7 explicitly left without a
concurrency reviewer, and on the still-open union cache policy.

**Reference-accuracy caveat (applies to every finding below).** mcp-steroid is
reachable (`steroid_list_projects` returned the open `youtrackdb` project) but
PSI `steroid_execute_code` is known to time out in this repo (cold kotlinc >
~60s MCP HTTP limit — MEMORY `reference_mcp_steroid_psi_timeout`). Every symbol
fact here rests on grep + direct declaration reads, not PSI find-usages. Two
load-bearing facts — the extent of the base's private surface and the
single-plan-only cache shape — are direct source reads (cited file:line), so
they are high-confidence; the "no other caller" style claims carry the residual
that a reflective or non-obvious call site could be missed.

## Findings

### R1 [should-fix]
**Certificate**: Exposure E1 (N-plan iteration seam over the Track 7 base)
**Location**: Track 8 Plan of Work step 2 + `## Interfaces and Dependencies`
("In scope (modified)"); `AbstractMatchPlanStep.java:89-133`, `:237-285`,
`:326-360`, `:394-444`
**Issue**: Track 8 plans to iterate `plans[0..N]` "one live stream at a time,
advance on drain" by extending `AbstractMatchPlanStep` **without** listing it in
its modified scope (the "In scope (modified)" set names only the walker/translator,
`TranslationResult`, `GremlinPlanCache`, and `GremlinToMatchStrategy`). The base
as extracted does not support that:
- The advance decision lives inside the base's private `processNextStart()`
  (`:237-285`): when `shapedPayloads` runs dry it sets `state = DRAINED`,
  `releaseStream()`, and throws `FastNoSuchElement`. There is no "is there a next
  plan?" hook. All per-stream fields (`openStream`, `shapedPayloads`, `state`,
  `armingGraph`) and the driving methods (`openArming`, `releaseStream`,
  `releaseStreamAndClosePlan`, `openShapedPayloads`, `rowProjectionSource`) are
  `private`; projection (`projectOrSkip`, `projectMap`, …) is private too
  (`projectElement` alone is package-private, for tests).
- Track 7's own handoff (`plan/track-7.md` §Surprises, §Track completion) says
  Track 8 must add the advance path by **"override `processNextStart` or introduce
  an advance hook."** Neither works within the declared scope: an override cannot
  reach the private per-stream state or reuse the private projection/shaping, and
  "introduce an advance hook" means editing `AbstractMatchPlanStep` — a Track 7
  file that Track 8's scope omits.
- The one clean realization — `startPlanStream()` returns a *composite*
  `ExecutionStream` that chains the child plans — collides with two per-arming
  singularities in the base: (a) `openArming()` (`:394-421`) resolves
  `armingGraph`/`planContext()` and rebinds the session (`ctx.setDatabaseSession`)
  **once**, on the first child's context, so children `1..N` never get the
  iteration-thread session rebind and throw `SessionNotActivatedException` on a
  server worker thread; (b) `rowProjectionSource()` (`:326-327`) captures
  `var ctx = planContext()` **once** and threads that same ctx into
  `stream.hasNext(ctx)/next(ctx)` for every row, so a composite spanning
  differently-contexted child plans iterates children `1..N` against the wrong
  context. Both are exactly the isolation the per-child clone (R2) is meant to
  provide.

Likelihood the current scope is implementable as written: low — this is the
track's central feasibility gap. Impact: the decomposition would discover
mid-implementation that it must expand scope into the base, re-opening Track 7's
lifecycle (leak/double-close hazards Track 7's reviews closed).
**Proposed fix**: In decomposition, add `AbstractMatchPlanStep` to Track 8's
"In scope (modified)" set and design the N-plan seam explicitly — the natural
shape is a small `advanceToNextPlan()` / `hasNextPlan()` hook the base consults
on drain (before `state = DRAINED`), re-running the per-child session rebind and
re-resolving `planContext()` per child so the isolation R2 requires actually
holds. If instead the composite-stream realization is chosen, add an explicit
step that proves per-child session rebind and per-child context threading are
handled inside the composite, and pin it with a two-child test whose children
run on different sessions. Either way the seam is a Track-7-file edit that must
be named in scope, not discovered.

### R2 [should-fix]
**Certificate**: Exposure E2 (clone/context isolation for multi-alias children)
**Location**: Track 8 Plan of Work step 2 + step 5 (clone-isolation test);
`YTDBMatchPlanStep.java:94-124` (the single-plan clone template)
**Issue**: `YTDBMatchPlanStep.clone()` copies its one plan against a fresh
`BasicCommandContext` parented to `plan.getContext()`, and its invariant comment
(`:105-112`) is explicit that isolation holds **only** because "the template
context carries no `$current` / `$matched` / alias / LET bindings … The
single-node `g.V()` pattern seeds no such variables … **A pattern that seeds
alias or LET variables onto the plan's context at BUILD time would break it — the
shared parent would then be written concurrently through its unsynchronised
maps.**" Union children are multi-alias sub-patterns — the precise case the note
warns against. Two derived risks:
1. `MultiPlanMatchStep.clone()` must give **each** of the N child plans its own
   independent isolated child context (the single-plan pattern applied N times).
   A naive clone that copies all N children against one shared isolated context
   re-introduces cross-child bleed *inside* the clone — concurrent per-run
   `$current`/`$matched` writes on shared unsynchronised `HashMap`s.
2. Even with per-child isolation, if a union child's plan context carries
   alias/LET bindings at *build* time, the child→parent propagation
   (`BasicCommandContext.setVariable` for a key the parent already holds) lets a
   clone's per-run write reach the shared template context. Track 3 shipped
   multi-node single-plan patterns whose clone isolation "already covers
   multi-node patterns" (plan checklist, Track 2 strategy refresh), which is
   evidence the per-plan pattern is sound — but union multiplies the surface and
   was never exercised.

Track 7's completion note recorded that its roster had **no concurrency
reviewer** and that "plan-cache publication stays Track 5's concern" precisely
because Track 7 "adds no new cross-thread sharing." Track 8 *does* add a new
multi-plan clone surface with the multi-alias bleed hazard, so that deferral no
longer covers this track. Likelihood: medium; impact: data race / cross-query
result corruption under concurrent execution of the same cached union shape —
hard to reproduce, easy to ship.
**Proposed fix**: (a) Decomposition should require `MultiPlanMatchStep.clone()`
to isolate each child plan against its own child context, and add a
clone-isolation test that runs two clones of a multi-alias union concurrently and
asserts no cross-clone/parent variable bleed (Plan of Work step 5 already lists
"clone-isolation across multi-alias children" — strengthen it to a concurrent
assertion, not a sequential one). (b) Flag the `MultiPlanMatchStep` step for the
`concurrency` code-review category in Phase C — this is the Track 8 step that
warrants it, filling the gap Track 7 explicitly left open. (Reference-accuracy
caveat: whether union child contexts seed build-time alias/LET vars was not
confirmed by PSI; decomposition should verify against `MatchExecutionPlanner`
build with find-usages if PSI recovers.)

### R3 [should-fix]
**Certificate**: Exposure E3 (shared `GremlinPlanCache` union keying)
**Location**: Track 8 Plan of Work step 4 + Decision Log open item #2;
`GremlinToMatchStrategy.java:399-421` (`buildPlan`),
`GremlinPlanCache.java:70-110`, `GremlinPlanFingerprint.java:46`,
`GremlinToMatchTranslator.java:74-91` (`TranslationResult`)
**Issue**: The cache is single-plan on three axes, and Track 8's cache policy is
still an open Decision-Log item rather than a pinned design:
- `TranslationResult` holds **one** `MatchPlanInputs inputs` and one
  `boolean cacheEligible`; `GremlinPlanFingerprint.fingerprint(MatchPlanInputs)`
  keys on a single input. A union carries N child inputs. If Track 8 fingerprints
  only one child (or a composite as if single), two structurally distinct unions
  that share that one child collide and the cache serves a wrong plan — the exact
  "value leaking / structural token misclassified" failure D5's caveat warns of.
- `GremlinPlanCache`'s value type is a single `InternalExecutionPlan`, and a hit
  returns `result.copy(ctx)` (`:105`). A `MultiPlanMatchStep` holds a
  `List<SelectExecutionPlan>`, which does not fit that value type; caching a union
  requires either a multi-plan carrier that is itself an `InternalExecutionPlan`
  with a correct `copy(ctx)`/`canBeCached()`/`close()`, or a bypass.
- `putInternal` gates on `canBeCached()` and `getInternal` invalidates on
  timeout/DDL — a multi-plan carrier must forward all three (`canBeCached` = AND
  over children; `copy` = per-child copy; `close` = close all) or it silently
  breaks the R3 RID-bypass and the DDL-staleness guards Track 5/6 established.

Likelihood a naive implementation gets the fingerprint or value-type wrong:
medium; impact: wrong-plan service (correctness) or a cache that silently never
hits (perf regression, less severe).
**Proposed fix**: Decomposition should pin the union cache policy before
implementation, with the **safe default = do not cache union** (set
`cacheEligible = false` for any union translation, mirroring the RID-bypass path
at `GremlinToMatchStrategy.java:403`), and only opt into caching if a dedicated
multi-input fingerprint (over the ordered list of child `MatchPlanInputs`) **and**
a multi-plan cache value with correct `copy`/`canBeCached`/`close` are designed
and tested for cross-shape collisions. The Validation section already asks for
"no cross-shape fingerprint collision; RID-bearing shapes bypass" — make the
default-bypass explicit so implementation does not improvise a partial fingerprint.

### R4 [should-fix]
**Certificate**: Exposure E4 (close of un-run plans + build-time partial-build leak)
**Location**: Track 8 Plan of Work step 2 ("`close()` closes all child plans incl.
un-run") + step 5 (leak test); `AbstractMatchPlanStep.java:807-811` (`closePlan`),
`GremlinToMatchStrategy.java:384-428` (`applyTranslation`/`buildPlan`),
`SelectExecutionPlan.java:76-78` (`close` → `lastStep.close()`)
**Issue**: Two leak surfaces unique to N plans:
1. **Iterate/close time.** `MultiPlanMatchStep.closePlan()` must close every child
   including `plans[N+1..]` that were never `start()`ed. `SelectExecutionPlan.close()`
   is `lastStep.close()` — `lastStep` is non-null (set at `chain`/`setSteps`), so no
   NPE there, but closing the terminal step of a plan that never ran `start()`
   exercises each `ExecutionStepInternal.close()` in an unstarted state. The base's
   single-plan `close()` guards on `started = state != NEW` (`:530`) and so never
   closes an unstarted plan; the N-plan form deliberately must, so it cannot borrow
   that guard. Whether every step's `close()` is null-safe on an unstarted chain was
   not audited (reference-accuracy caveat — no PSI).
2. **Build time.** `buildPlan` builds the plan *before* the step swap so the
   throw-safety net catches a planner throw with the traversal intact
   (`:369-391`). For N plans, if building `plans[k]` throws after `plans[0..k-1]`
   are built, the safety net only *declines* (absence of mutation) — it does not
   close the already-built plans, so cursors those plans claimed leak.

Likelihood: medium (both are natural omissions); impact: cursor/resource leak that
survives to traversal teardown or beyond.
**Proposed fix**: (a) The leak test (step 5) must explicitly assert that `close()`
on a partially-consumed union closes both the drained child *and* every un-run
`plans[N+1..]`, e.g. with per-plan close spies. (b) Add a build-time guard: build
all N child plans inside a try that closes any already-built plans on a
mid-build throw before rethrowing (so the decline path leaks nothing), and cover
it with a test that throws on the k-th child build and asserts plans `0..k-1` are
closed. Confirm unstarted-plan close safety during decomposition (PSI find-usages
on `ExecutionStepInternal.close` if PSI recovers).

### R5 [suggestion]
**Certificate**: Assumption A1 (union must not ride `splitDisjointPatterns`)
**Location**: Track 8 `## Context and Orientation` + Plan of Work step 1;
`MatchExecutionPlanner.java:4547-4558` (`splitDisjointPatterns`)
**Issue**: The design's core correctness premise — union is a *concatenated*
multiset, not a cartesian product — is validated: `splitDisjointPatterns` splits
the pattern graph into connected components that "are later joined via a
`CartesianProductStep`" (`:4549-4550`). So routing union children into a **single**
`MatchPlanInputs` with disjoint sub-patterns would silently produce a cartesian
product — the opposite of union, and a green-suite-breaking result change. The
plan already commits to one full `SelectExecutionPlan` per child, which avoids
this; the residual risk is an implementation shortcut that folds children into one
inputs to reuse the existing single-plan `buildPlan`.
**Proposed fix**: Add an acceptance assertion that a two-child union over disjoint
patterns returns the concatenation count (`|c1| + |c2|`), not the product
(`|c1| * |c2|`) — a single test that fails loudly if a child ever reaches the
planner as a disjoint sub-pattern of one `MatchPlanInputs`. (Already implied by
"concatenation-multiset parity vs native" in step 5; make the anti-cartesian case
explicit with children whose product ≠ sum.)

### R6 [suggestion]
**Certificate**: Testability T1 (exception-stops-advance semantics)
**Location**: Track 8 Plan of Work step 2 + step 5 (exception-stops-advance test);
`AbstractMatchPlanStep.java:267-284` (terminal-failure handler)
**Issue**: "An exception in `plans[N]` closes the current stream and re-throws
without opening `plans[N+1..]`" is only automatic under one of the two seam
realizations. In the composite-stream realization the base's terminal handler
(`:269-284`) already does the right thing — move to `CLOSED`, `releaseStreamAndClosePlan()`
(which for the N-plan `closePlan()` closes all children including un-run), attach a
release failure with `addSuppressed`, keep the iteration failure primary — and
`plans[N+1..]` are never opened because the composite only opens the next child on
drain. But if Track 8 instead overrides `processNextStart()` (R1's other branch),
it must hand-replicate all of that terminal semantics, which is easy to get subtly
wrong (e.g. forgetting `addSuppressed`, or closing only the current plan and
leaking the rest).
**Proposed fix**: The exception-stops-advance test (step 5) should assert three
things, not one: (1) `plans[N+1..]` are never `start()`ed after a plan-N throw
(open-spy on later plans), (2) every plan including un-run ones is closed, and (3)
the original iteration exception is the one thrown (not masked by a close failure).
This pins the semantics regardless of which seam realization R1 resolves to.

## Evidence base

#### E1 — Exposure: N-plan iteration over the private single-plan lifecycle of `AbstractMatchPlanStep`
- **Track claim**: `MultiPlanMatchStep` extends the Track 7 base and iterates
  `plans[0..N]` "one live stream at a time, advance on drain," reusing the base's
  projection + shaping; `AbstractMatchPlanStep` is *not* listed in Track 8's
  modified scope.
- **Critical path trace**:
  1. `processNextStart()` @ `AbstractMatchPlanStep.java:237` — on `!shapedPayloads.hasNext()`
     sets `state = State.DRAINED` (`:261`), `releaseStream()` (`:262`), throws
     `FastNoSuchElement` (`:263`). No next-plan consultation.
  2. Per-stream fields `openStream` (`:125`), `shapedPayloads` (`:133`),
     `armingGraph` (`:136`), `state` (`:170`) are all `private`.
  3. `openArming()` @ `:394` resolves `armingGraph` + `planContext()` and rebinds
     the session `ctx.setDatabaseSession(tx.getDatabaseSession())` @ `:421` — once
     per arming, on the single `planContext()` result.
  4. `rowProjectionSource()` @ `:326` captures `var ctx = planContext()` once and
     reuses it for `stream.hasNext(ctx)/next(ctx)` for the whole arming.
  5. Extension seam = 4 protected hooks `planContext`/`rewindPlan`/`startPlanStream`/
     `closePlan` (`:791-811`) + `resetLifecycleForClone()` (`:552`). No advance hook.
- **Blast radius**: the whole track's feasibility — if the seam is not addable
  within declared scope, decomposition must re-open the Track 7 base, whose
  reviews closed the R3 leak / double-close hazards.
- **Existing safeguards**: Track 7's terminal-failure handler and idempotent
  `close()` are robust for one plan; none of them address multi-plan advance,
  per-child session rebind, or per-child context threading.
- **Residual risk**: HIGH — no in-scope mechanism as written; both candidate
  realizations need a Track-7-file edit or a non-trivial composite-stream that
  re-implements per-child session/context handling.

#### E2 — Exposure: clone/context isolation for multi-alias union children
- **Track claim**: "each child runs against an isolated cloned context (union's
  multi-alias children make context bleed a real hazard)."
- **Critical path trace**:
  1. `YTDBMatchPlanStep.clone()` @ `:94` → new `BasicCommandContext`
     `setParentWithoutOverridingChild(plan.getContext())` @ `:113-114` →
     `plan.copy(isolatedCtx)` @ `:117` → `resetLifecycleForClone()` @ `:122`.
  2. Invariant comment @ `:105-112`: isolation holds only if the template context
     seeds no `$current`/`$matched`/alias/LET vars; a build-time alias/LET seed
     makes child writes propagate up to the shared unsynchronised parent map.
- **Blast radius**: concurrent executions of the same cached union shape race on
  the shared parent context maps → cross-query result corruption.
- **Existing safeguards**: single-plan clone isolation (mirrors `HashJoinMatchStep`);
  Track 3 multi-node single-plan clone "already covers multi-node patterns."
- **Residual risk**: MEDIUM — the per-plan pattern is proven, but N-child
  multiplication + the "one shared context across children" implementation trap
  are new and unreviewed for concurrency (Track 7 had no concurrency reviewer).

#### E3 — Exposure: shared `GremlinPlanCache` union keying and value type
- **Track claim**: "pin whether/how a union plan caches — the fingerprint must key
  on the child shapes; RID-bearing shapes bypass (Track 5 R3)."
- **Critical path trace**:
  1. `buildPlan` @ `GremlinToMatchStrategy.java:399`: `!translation.cacheEligible()`
     → `buildPlanUncached` (R3 RID bypass); else
     `GremlinPlanFingerprint.fingerprint(translation.inputs())` @ `:406`.
  2. `TranslationResult` @ `GremlinToMatchTranslator.java:74` = one
     `MatchPlanInputs inputs` + one `boolean cacheEligible`.
  3. `GremlinPlanFingerprint.fingerprint(@Nonnull MatchPlanInputs)` @ `:46` —
     single-input signature.
  4. `GremlinPlanCache` value = `InternalExecutionPlan`; hit → `result.copy(ctx)`
     @ `:105`; put gated on `internal.canBeCached()` @ `:80`.
- **Blast radius**: a wrong or colliding fingerprint serves a wrong union plan
  (correctness); a value-type mismatch forces a bypass or a broken carrier.
- **Existing safeguards**: `canBeCached()` gate, DDL/timeout invalidation, RID
  bypass — all single-plan; none extend to a multi-plan value automatically.
- **Residual risk**: MEDIUM — the open Decision-Log item means the policy is
  unpinned; the safe default (no-cache union) is available and cheap.

#### E4 — Exposure: close of un-run plans + build-time partial-build leak
- **Track claim**: "`close()` closes every child plan including un-run ones";
  "exception in `plans[N]` closes the current stream and re-throws without opening
  `plans[N+1..]`."
- **Critical path trace**:
  1. `closePlan()` hook @ `AbstractMatchPlanStep.java:807` is called by
     `releaseStreamAndClosePlan()` and `close()`; the N-plan impl must close all
     children, including never-`start()`ed ones.
  2. `SelectExecutionPlan.close()` @ `:76` = `lastStep.close()`; `lastStep`
     non-null (set in `chain`/`setSteps` `:120-172`), but per-step `close()` on an
     unstarted chain is not audited here (no PSI).
  3. `applyTranslation` @ `GremlinToMatchStrategy.java:384` builds the plan before
     the step swap; the throw net only declines, it does not close already-built
     plans.
- **Blast radius**: cursor/resource leak (un-run child close bug, or a mid-build
  throw leaking earlier children).
- **Existing safeguards**: single-plan `close()` idempotence + sticky step-close
  guard (safe double-close); base `close()` skips unstarted (`started` guard) —
  but the N-plan form must *not* skip un-run children.
- **Residual risk**: MEDIUM — both are natural omissions with clear tests.

#### A1 — Assumption: union must not ride MATCH `splitDisjointPatterns`
- **Track claim**: union builds one `SelectExecutionPlan` per child and
  concatenates; it must not use `splitDisjointPatterns` (cartesian).
- **Evidence search**: grep `splitDisjointPatterns` over `core/src/main/java`
  (PSI unavailable). Hit: `MatchExecutionPlanner.java:4552`.
- **Code evidence**: `MatchExecutionPlanner.java:4547-4550` — components "are
  later joined via a `CartesianProductStep`." Confirms cartesian, the opposite of
  union concatenation.
- **Verdict**: VALIDATED — hazard real; the chosen one-plan-per-child design
  avoids it. Residual = an implementation shortcut folding children into one
  `MatchPlanInputs`.

#### A2 — Assumption: `walkChild` yields a predicate adapter, not a full plan
- **Track claim**: "the existing `walkChild` yields a WHERE-predicate adapter, not
  a full plan"; union needs a new child→full-`SelectExecutionPlan` path.
- **Evidence search**: grep `walkChild` / `SubTraversalPredicateAdapter` over the
  translator package (PSI unavailable).
- **Code evidence**: `RecognitionContext.java:296` and `WalkerContext.java:569`
  both declare `SubTraversalPredicateAdapter walkChild(Traversal.Admin<?,?>)`; all
  callers (`AndStepRecogniser`, `OrStep`/`ConnectiveStepSupport:41`,
  `NotStepRecogniser:63`, `WhereTraversalStepRecogniser:36`,
  `TraversalFilterStepRecogniser:75`) consume the adapter as a WHERE predicate.
- **Verdict**: VALIDATED — the child→full-plan path is genuinely new work, as the
  track claims (Plan of Work step 3). No existing seam supplies it.

#### T1 — Testability: N-plan lifecycle (exception-stops-advance, one-live-stream, leak, clone isolation)
- **Coverage target**: 85% line / 70% branch.
- **Difficulty assessment**: the deterministic lifecycle cases (one live stream,
  advance on drain, exception stops advance, close all incl. un-run) are
  ACHIEVABLE with plan spies / open+close counters over 2-3 child fixtures — the
  same style Track 7's `YTDBMatchPlanStepTest` already uses (32 tests incl. reopen
  and null-payload pins). The clone-isolation-under-concurrency case is DIFFICULT:
  a real race on the shared parent context is timing-dependent and needs a
  deterministic construction (two clones, interleaved variable writes) rather than
  a hopeful parallel loop.
- **Existing test infrastructure**: `YTDBMatchPlanStepTest` (boundary-step
  lifecycle patterns, the SCALAR-payload `Traverser.Admin<?>` read pattern Track 7
  Phase C surfaced); the translator package suite (442/0 green at Track 7 close).
- **Feasibility**: ACHIEVABLE for the lifecycle/leak/exception pins; the
  concurrency clone-isolation assertion needs a deterministic design (feeds R2's
  proposed fix and the `concurrency` category recommendation).
