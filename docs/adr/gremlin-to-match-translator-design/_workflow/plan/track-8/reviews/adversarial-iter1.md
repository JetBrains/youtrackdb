<!-- MANIFEST
findings: 5   severity: {blocker: 3, should-fix: 1, suggestion: 1}
index:
  - {id: A1, sev: blocker,    loc: plan/track-8.md:51, anchor: "### A1 ", cert: C2, basis: "no post-union walk policy; count/dedup/order recognisers are registered and keep walking after the union claim — silent wrong multiset or undefined ctx state"}
  - {id: A2, sev: blocker,    loc: AbstractMatchPlanStep.java:117, anchor: "### A2 ", cert: C3, basis: "enum-only output-type gate passes children with divergent ResultShaping / boundary alias; base holds ONE shaping+alias — wrong drops and null payloads"}
  - {id: A3, sev: blocker,    loc: plan/track-7.md:96, anchor: "### A3 ", cert: C4, basis: "per-plan-reopen advance bakes per-child list-shaping: Track 9's design-sanctioned union().fold() folds per child (wrong); MultipleExecutionStream realization avoids it plus the private-state/reset collisions"}
  - {id: A4, sev: should-fix, loc: plan/track-8.md:81, anchor: "### A4 ", cert: C6, basis: "modified-list misdirects the decomposer: registry lives in GremlinStepWalker, broaden-scan already done, strategy splice/build seams and cache value-type constraint unlisted"}
  - {id: A5, sev: suggestion, loc: plan/track-8.md:51, anchor: "### A5 ", cert: C7, basis: "nested union inside a child is unpinned — the child-to-one-plan path cannot represent it; pin decline (or flatten)"}
evidence_base: {section: "## Evidence base", certs: 8, matches: 2}
cert_index:
  - {id: C1, verdict: HOLDS, anchor: "#### C1 "}
  - {id: C2, verdict: CONSTRUCTIBLE, anchor: "#### C2 "}
  - {id: C3, verdict: CONSTRUCTIBLE, anchor: "#### C3 "}
  - {id: C4, verdict: FRAGILE, anchor: "#### C4 "}
  - {id: C5, verdict: WEAK, anchor: "#### C5 "}
  - {id: C6, verdict: BREAKS, anchor: "#### C6 "}
  - {id: C7, verdict: THEORETICAL, anchor: "#### C7 "}
  - {id: C8, verdict: HOLDS, anchor: "#### C8 "}
flags: [CONTRACT_OK]
-->

## Findings

### A1 [blocker]
**Certificate**: C2 (violation scenario — non-terminal union).
**Target**: Plan of Work item 1 (`UnionStepRecogniser` claim rule, plan/track-8.md:51) against the
plan invariant "translator-on and translator-off produce equal result multisets"
(implementation-plan.md:347).
**Challenge**: The track never states what happens to steps *after* the `UnionStep`. This is not a
gap that fails safe: the walker keeps dispatching after the union claim
(`GremlinStepWalker.dispatchAll`, GremlinStepWalker.java:278–304), and `CountGlobalStep`,
`DedupGlobalStep`, `OrderGlobalStep`, and `RangeGlobalStep` recognisers are all registered
(GremlinStepWalker.java:152–162). None of those distribute over concatenation: per-child `count`
emits N counts instead of one, per-child `limit(n)` emits up to N·n rows, per-child `dedup` misses
cross-child duplicates. Whatever the suffix recognisers do to the shared `WalkerContext` (whose
pattern builder holds only the prefix at that point), the outcome is a wrong multiset or an
undefined plan — not a decline. The design sanctions exactly one suffix family after union:
the Track 9 list-shaping terminators (`fold`/`tail` "immediately after the prior terminator
(vertex hop / projection / aggregate / group / union)", design.md:663–666); everything else after
union is unsanctioned yet currently walkable.
**Evidence**: registry entries at GremlinStepWalker.java:152–162; walk loop continues past any
accepted claim (GremlinStepWalker.java:281–302); design.md:663–666. Trace in C2.
**Proposed fix**: Pin in Plan of Work item 1 and Validation: in Track 8 the union claim must be the
last recognised step — after consuming the `UnionStep` the recogniser declines unless the cursor is
exhausted (`cursor.peek() == null`). Track 9 relaxes this only for the sanctioned list-shaping
post-steps. Add a decline test for `g.V().union(out(), in()).count()` (and one `dedup` variant) to
the test list.

### A2 [blocker]
**Certificate**: C3 (violation scenario — divergent child shaping / alias).
**Target**: The D8 decline gate as realized ("verify all children agree on output type",
plan/track-8.md:51; design.md:1348–1350) against the multiset invariant.
**Challenge**: "Agree on output type" read as `BoundaryOutputType` enum equality is insufficient,
because the Track 7 base holds exactly one `ResultShaping` (AbstractMatchPlanStep.java:117) and one
`boundaryAlias` (:104), both private final, applied to every row of every child plan. Two
constructible failures: (1) `g.V().union(__.values("name"), __.values("age"))` — both children are
`SINGLE_VALUE` (PropertiesStepRecogniser pins `SINGLE_VALUE` + `dropOnAbsent` with the key as the
presence key), so the enum gate passes, but the single shaping carries one presence key list and
`projectSingleValue` reads `presencePropertyKeys().getFirst()` (:635) — child 2's rows are
presence-checked against the wrong key, silently dropping or emitting wrong rows. (2)
`g.V().union(__.out(), __.in().in())` — each child is sub-walked with a fresh `WalkerContext` and
its own `$g2m_anon_` counter (WalkerContext.java:173), so children with different hop counts end at
different boundary aliases; the base projects via `row.getVertex(boundaryAlias)` (:772–778) with
the one constructor alias, so every row of the other child projects to `null`.
**Evidence**: AbstractMatchPlanStep.java:104,117,633–651,772–778; ResultShaping is a record, so
whole-record equality is available for the gate. Traces in C3.
**Proposed fix**: Pin the gate as full projection-contract agreement: equal `BoundaryOutputType`,
equal `returnClass`, equal `ResultShaping` (record equality), and a canonical boundary alias —
rewrite each child's RETURN alias to one shared alias at translation time (the reconciliation Plan
of Work item 3 names, made concrete). Add both scenarios above to the test list: the
shaping-mismatch decline and the alias-canonicalization parity case.

### A3 [blocker]
**Certificate**: C4 (assumption test — the base's advance seam), C5 (simplification challenge —
`MultipleExecutionStream`).
**Target**: Plan of Work item 2 / the Track 7 hand-off prescription ("advance-on-drain … route each
child-plan reopen through the NEW/REARMED branch so `shapedPayloads` rebuilds fresh per plan",
plan/track-7.md:96,101).
**Challenge**: Two independent counter-arguments against the prescribed per-plan-reopen
architecture. (1) *It bakes in a Track 9 defect.* The design sanctions `union(...).fold()` in
Phase 1 (design.md:663–666). Track 9's terminator recognisers register ops into the one
`ResultShaping` the boundary carries; native semantics fold the *whole* union output into one list.
With per-plan reopen, `applyListShaping` (AbstractMatchPlanStep.java:307–317) re-threads the ops
over each child's fresh `shapedPayloads` — `fold` then emits one list per child, N lists instead of
one, a multiset violation discoverable only in Track 9 and fixable only by re-architecting the
private per-arming stage. (2) *The base does not actually expose an advance seam.* The `State`
machine, `state` field, and `shapedPayloads` are private (:125–170); the only DRAINED→REARMED mover
is `reset()` (:506–512), which is also TinkerPop's public whole-step re-arm contract — using it for
internal advance collides with external reset semantics (plan index rewind, per-plan rewind
bookkeeping, and the stale-stream close in `openArming` (:395–401) going to `planContext()` of the
*new* plan). Per-child session rebind and positional-parameter install also live inside private
`openArming` (:419–424) keyed to the base's single constructor-time `inputParameters` map, while
each child walk produces its own parameter map. Track 7's own episode concedes the gap ("override
`processNextStart` or introduce an advance hook", plan/track-7.md:25) — and the track file lists no
`AbstractMatchPlanStep` modification.
**Evidence**: the engine already ships the correct machinery: `MultipleExecutionStream`
(sql/executor/resultset/MultipleExecutionStream.java:16–27) is a lazy one-live-stream concatenator
(closes the drained child, opens the next on demand), production-used by `ParallelExecStep`
(sql/executor/ParallelExecStep.java:14–16, "executes multiple sub-plans and concatenates their
result streams"). Realizing `MultiPlanMatchStep.startPlanStream()` as a `MultipleExecutionStream`
over a producer that (per child) rebinds the session, installs that child's parameters, and starts
the plan keeps ONE base arming for all N plans: no base change, no reset ambiguity, exception in
child N never opens N+1 (the producer never advances), `closePlan()` closes all children including
un-run ones — and one `shapedPayloads` spans the concatenation, so Track 9's `union().fold()` is
correct by construction.
**Proposed fix**: Re-point Plan of Work item 2 from "advance on drain / reopen per child" to the
concatenating-stream realization (or record at decomposition why not, and then add
`AbstractMatchPlanStep` to In scope (modified) for a protected advance seam plus pins for external
`reset()` semantics, per-plan rewind bookkeeping, and per-child parameter binding). Keep the stated
lifecycle acceptance lines — they hold under either realization — and add a
union-list-shaping-readiness note so Track 9 inherits whole-stream op application.

### A4 [should-fix]
**Certificate**: C6 (assumption test — pipeline seams and file list).
**Target**: `## Interfaces and Dependencies` In scope (modified) (plan/track-8.md:81) and the
union cache-policy Decision Log entry (plan/track-8.md:24).
**Challenge**: Three inaccuracies that misdirect the decomposer. (1) "GremlinToMatchStrategy
(register the union recogniser…)" — recognisers register in
`GremlinStepWalker.PRODUCTION_RECOGNISERS` (GremlinStepWalker.java:140–168), not in the strategy;
and the "broaden the D7 idempotency scan" sub-item is already done
(`instanceof AbstractMatchPlanStep`, GremlinToMatchStrategy.java:350 — the plan's Track 7 strategy
refresh already says the decomposer drops it). (2) The strategy edits that *are* needed go
unnamed: `TranslationResult` carries one `@Nonnull MatchPlanInputs`
(GremlinToMatchTranslator.java:74–75), the `MatchPlanBuilder` seam returns one
`InternalExecutionPlan` (GremlinToMatchStrategy.java:486–491), and `replaceAllStepsWithBoundary`
hard-constructs `YTDBMatchPlanStep` (:439–454) — the splice path must branch single-vs-multi.
(3) Plan of Work item 1 has the recogniser sub-walking "to a full `SelectExecutionPlan`" —
walk-time plan building would sit outside `buildPlan`'s concurrent-DDL invalidation guard
(`planningStart` captured at :257, checked at :417) and outside the cache, whose value type is a
single plan (`AbstractMetadataUpdateCache<String, InternalExecutionPlan>`,
GremlinPlanCache.java:32–33). The sub-walk should yield per-child `MatchPlanInputs` carried in the
multi-plan `TranslationResult`; the strategy builds the N plans inside the guarded path. That
seam choice then shapes the cache pin: per-child fingerprint get/put honoring per-child
`canBeCached()`, or whole-union bypass — either is coherent; walk-time building forces bypass.
**Evidence**: file:line cites above; the corrected footprint (walker registry + translator record +
strategy splice/build + possibly `AbstractMatchPlanStep` per A3) lands the track at the upper edge
of its ~10–14 estimate, still inside the soft ceiling (C8).
**Proposed fix**: Rewrite In scope (modified): registration → `GremlinStepWalker`; drop the
broaden-scan sub-item; add the strategy splice/build seams and the `TranslationResult` carrier
explicitly; state the child→plan seam as "sub-walk yields per-child inputs, strategy builds plans
in the guarded path", and pin the cache policy to one of the two named options.

### A5 [suggestion]
**Certificate**: C7 (violation scenario — nested union).
**Target**: Plan of Work item 1 child-translation rule (plan/track-8.md:51).
**Challenge**: A union child can itself contain a `UnionStep`
(`g.V().union(__.out(), __.union(__.in(), __.both()))` — legal Gremlin). The child→full-plan path
produces exactly one `SelectExecutionPlan` per child, which cannot represent a nested multi-plan
child; the sub-walk would either recurse into an unsupported shape or mis-translate. Multiset-wise
`union(a, union(b, c)) ≡ union(a, b, c)`, so flattening is semantically available, but it is extra
scope for a rare shape.
**Evidence**: `UnionStep` is an ordinary registered-or-not step inside child traversals; the child
sub-walk runs the same registry (Plan of Work item 1), so whatever key `UnionStepRecogniser`
registers under is reachable recursively.
**Proposed fix**: Pin Phase 1 behavior: a child whose sub-walk encounters a `UnionStep` declines
the whole union (D3), with a decline test; note flattening as a cheap Phase 2 option.

## Evidence base

Reference-accuracy caveat: mcp-steroid PSI (`steroid_execute_code`) reliably times out in this
repo (cold kotlinc > ~60s MCP limit), so every symbol claim below rests on grep, full declaration
reads of the named files, and `javap` against the fork jar actually resolved by the build
(`gremlin-core-3.8.1-af9db90-SNAPSHOT`, pom.xml:114). Caller enumerations are textual, not
PSI-verified; a reflection-hidden call site would be missed.

#### C1 — Assumption test: Track 7's realized outputs exist as Track 8 assumes (cross-track-episode reality)
- **Claim**: The boundary base surface the track file and Track 7's episodes describe — the four
  plan-seam hooks, `resetLifecycleForClone()`, one-live-stream `processNextStart`, the
  `shapedPayloads` rebuild-on-reopen contract, the base-keyed idempotency scan — exists in code.
- **Stress scenario**: read the shipped declarations instead of trusting the episode.
- **Code evidence**: `AbstractMatchPlanStep` declares exactly `planContext()` / `rewindPlan(ctx)` /
  `startPlanStream()` / `closePlan()` as protected abstract hooks (:791–811) and
  `protected final resetLifecycleForClone()` (:552–557). `processNextStart` drives one live stream:
  drain sets `DRAINED`, releases the stream, throws `FastNoSuchElementException` (:258–263); an
  iteration failure moves to `CLOSED` and calls `releaseStreamAndClosePlan()` (:269–283), so
  "exception in plan N closes everything and never opens N+1" is base-guaranteed given `closePlan()`
  closes all children. `shapedPayloads` is nulled on (re)open and rebuilt lazily (:242–257,
  :451–461). The idempotency scan keys on the base (`instanceof AbstractMatchPlanStep`,
  GremlinToMatchStrategy.java:350), so no strategy change is needed for detection — matching the
  plan's Track 7 strategy-refresh note. Fork surface: `UnionStep extends BranchStep` with inherited
  `getGlobalChildren()`; `BranchStep.addChildOption` appends `ComputerAwareStep$EndStep` to each
  child (bytecode confirmed) — the "strip the child EndStep" plan item is real and names the right
  class family; `GraphTraversalSource.union(...)` exists and fork `UnionStep` carries `isStart()` +
  `UNION_STARTER`, so the start-position decline acceptance line is constructible and
  `hasVertexGraphStart` (:364–367) handles it unchanged.
- **Verdict**: HOLDS — with one deliberate omission the episode itself names: the base has no
  cross-plan advance seam (see C4).

#### C2 — Violation scenario: post-union suffix steps break the concatenation multiset (INVARIANT CHALLENGES)
- **Invariant claim**: translator-on/off multiset equality for every recognized shape
  (implementation-plan.md:347).
- **Violation construction**:
  1. Start state: `g.V().union(__.out(), __.in()).count()` on any graph; translator enabled.
  2. Action sequence: strategy gates pass (`hasVertexGraphStart` true — start is `GraphStep`);
     walker dispatches `GraphStep` → `StartStepRecogniser`, `UnionStep` → `UnionStepRecogniser`
     (claims, builds two child plans, pins the boundary), loop continues
     (GremlinStepWalker.java:281–302); `CountGlobalStep` → `CountGlobalStepRecogniser`
     (registered, :162) runs against the shared `WalkerContext` whose pattern builder holds only
     the prefix `V`.
  3. Intermediate state: count recogniser re-pins the boundary metadata (SCALAR) and installs a
     count RETURN item over the prefix-only pattern; the union child plans sit in whatever carrier
     the recogniser used.
  4. Violation point: whichever of the two translations wins the carrier, the emitted result is
     either N per-child counts or a count over the prefix pattern — native emits exactly one
     number, the total across both children.
  5. Observable consequence: silent wrong result (or an undefined-planner exception degraded to a
     decline by the safety net — indistinguishable from a correct decline, masking the bug).
- **Feasibility**: CONSTRUCTIBLE — every step in the trace uses a recogniser that exists today;
  nothing in the track file forbids the walk from continuing. Analogous traces for
  `dedup()` (cross-child duplicates survive) and `limit(n)` (up to N·n rows).

#### C3 — Violation scenario: enum-only "agree on output type" gate (INVARIANT CHALLENGES)
- **Invariant claim**: children that agree on output type translate to the native-equal
  concatenated multiset (plan/track-8.md:64).
- **Violation construction**:
  1. Start state: `g.V().union(__.values("name"), __.values("age"))`; vertices exist with only one
     of the two properties.
  2. Action sequence: both children sub-walk to `SINGLE_VALUE` with `dropOnAbsent` and their own
     presence key; enum gate compares `SINGLE_VALUE == SINGLE_VALUE` → translate. The base receives
     ONE `ResultShaping` (AbstractMatchPlanStep.java:117) — whichever child's shaping was chosen.
  3. Intermediate state: child 2's plan streams rows carrying `age` columns; the base's
     `projectSingleValue` presence-checks `presencePropertyKeys().getFirst()` = `"name"` (:633–645).
  4. Violation point: rows whose entity lacks `name` but has `age` return `SKIP` (:637–639) —
     dropped; rows with both project `name`'s value out of an `age` child.
  5. Observable consequence: wrong multiset, silently. Second alias variant: children of different
     hop depth get different `$g2m_anon_` boundary aliases (fresh `WalkerContext` per child,
     WalkerContext.java:173); base projects `row.getVertex(boundaryAlias)` (:772–778) with the
     single ctor alias → all rows of the mismatched child project to `null` payloads.
- **Feasibility**: CONSTRUCTIBLE — both scenarios use already-shipped recognisers and the shipped
  base projection. The gate must compare the full projection contract and canonicalize the RETURN
  alias; `ResultShaping` is a record, so record equality is one expression.

#### C4 — Assumption test: "the four hooks + resetLifecycleForClone give Track 8 everything advance-on-drain needs" (ASSUMPTION CHALLENGES / cross-track-episode reality)
- **Claim**: plan/track-7.md:25 ("the four plan-seam hooks plus `resetLifecycleForClone()` give
  Track 8's `MultiPlanMatchStep` everything it needs"), immediately qualified by the same bullet:
  the N-plan advance path itself is NOT a base seam.
- **Stress scenario**: implement advance-on-drain as the episode prescribes (catch drain, reopen
  per child through NEW/REARMED). The `State` enum, `state`, `openStream`, `shapedPayloads` are all
  private (:125–170); the only DRAINED→REARMED transition is `reset()` (:506–512), which is also
  the public TinkerPop re-arm contract — so internal advance and external reset alias the same
  method with different required semantics (advance: next plan; external reset: plan index → 0,
  every consumed plan rewound). Further collisions: `openArming` closes a stale stream against
  `planContext()` (:395–401) — after an index move that is the *wrong plan's* context; `rewindPlan`
  fires on every REARMED open (:427–429) including the first open of a never-run child, forcing
  per-plan has-run bookkeeping; session rebind + `inputParameters` install are private per-arming
  logic (:419–424) bound to the base's single ctor-time map, while each child sub-walk mints its
  own positional-parameter map (GremlinStepWalker.buildResult :406–414).
- **Code evidence**: file:line cites above; track file lists no `AbstractMatchPlanStep`
  modification (plan/track-8.md:81).
- **Verdict**: FRAGILE — implementable, but only with either a new protected seam on the base
  (unlisted file) or a subtle super.reset()-based dance plus three pinned disambiguations the track
  file does not carry. Feeds A3, which also shows the per-plan-reopen contract is the wrong
  architecture for Track 9's `union().fold()` (design.md:663–666): per-plan `shapedPayloads`
  rebuild re-applies list-shaping ops per child, folding per child instead of over the union.

#### C5 — Challenge: hand-rolled N-plan advance vs the engine's existing concatenator (SIMPLIFICATION CHALLENGES / rejected-alternative search)
- **Chosen approach**: `MultiPlanMatchStep` overrides the base lifecycle to advance plans on drain
  (plan/track-8.md:52, following plan/track-7.md:96,101).
- **Best rejected alternative**: not listed anywhere in D8's alternatives — reuse
  `MultipleExecutionStream` (sql/executor/resultset/MultipleExecutionStream.java:16–27), the lazy
  one-live-stream sub-plan concatenator `ParallelExecStep` already uses in production for OR-branch
  plans (ParallelExecStep.java:14–16,41–85).
- **Counterargument trace**:
  1. In the advance-on-drain scenario, the chosen approach must re-enter the base's private
     lifecycle per child (C4's collisions) because the base was designed around one arming per
     stream (AbstractMatchPlanStep.java:237–285).
  2. The alternative implements `startPlanStream()` as one `MultipleExecutionStream` over a
     producer that per child rebinds the session, installs that child's parameters, and calls
     `plan.start()` — `hasNext` closes the drained child and opens the next
     (MultipleExecutionStream.java:17–27), giving one-live-stream, exception-stops-advance, and
     lazy open for free, all inside a single base arming.
  3. Concrete difference: no base modification, no reset aliasing, per-child parameter binding gets
     a natural home, and one `shapedPayloads` spans the concatenation — which is exactly the
     whole-stream semantics Track 9's `union().fold()` needs.
- **Codebase evidence**: `ParallelExecStep` is the in-engine precedent that sequential
  concatenation of independent `InternalExecutionPlan`s via this stream is production-safe.
- **Survival test**: WEAK — the chosen prescription survives only as "a way that can be made to
  work"; the unlisted alternative is strictly simpler on every axis the track's own acceptance
  lines name, and it repairs the Track 9 interaction the prescription breaks. The decomposer
  should adopt it or record why not (A3).

#### C6 — Assumption test: "the union recogniser drops into the pipeline as the track's modified-file list describes" (SCOPE CHALLENGES / track-file accuracy)
- **Claim**: In scope (modified) — walker child-walk, `TranslationResult`, `GremlinPlanCache`,
  `GremlinToMatchStrategy` "(register the union recogniser, and broaden the D7 idempotency scan…)"
  (plan/track-8.md:81).
- **Stress scenario**: follow the list literally at decomposition.
- **Code evidence**: registration lives in `GremlinStepWalker.PRODUCTION_RECOGNISERS`
  (GremlinStepWalker.java:140–168) — the strategy has no registry; the idempotency scan already
  keys on the base (GremlinToMatchStrategy.java:350), and the plan's Track 7 strategy-refresh
  already instructs the decomposer to drop that sub-item; the strategy edits actually required are
  unlisted — `MatchPlanBuilder` returns one plan (:486–491), `replaceAllStepsWithBoundary`
  hard-constructs `YTDBMatchPlanStep` (:439–454), `TranslationResult.inputs` is a single `@Nonnull`
  record component (GremlinToMatchTranslator.java:74–75). Cache: value type is a single
  `InternalExecutionPlan` (GremlinPlanCache.java:32–33) and the concurrent-DDL guard lives in the
  strategy's `buildPlan` (`planningStart` :257, guard :417) — child plans built inside the walk
  (Plan of Work item 1's literal wording) would sit outside both.
- **Verdict**: BREAKS as written — the list points the decomposer at one wrong file-reason, one
  already-done item, and omits the real splice-path work; the walk-time-plan-build wording
  forecloses the per-child-fingerprint cache option before the policy pin is made (A4).

#### C7 — Violation scenario: nested union inside a child (ASSUMPTION CHALLENGES)
- **Invariant claim**: each child sub-walks to exactly one `SelectExecutionPlan`
  (plan/track-8.md:51).
- **Violation construction**: `g.V().union(__.out(), __.union(__.in(), __.both()))` — the inner
  child's sub-walk meets a `UnionStep`; the child→one-plan contract cannot hold. Depending on
  registry wiring the sub-walk either recurses into a multi-plan child (no carrier for it) or hits
  an unregistered class and declines the whole traversal (safe).
- **Feasibility**: THEORETICAL-leaning-CONSTRUCTIBLE — legal Gremlin, rare in practice; the failure
  mode is most likely a safe decline, but the track file should pin it rather than inherit whatever
  the registry wiring happens to do (A5). Flattening (`union(a, union(b,c)) ≡ union(a,b,c)` as
  multisets) is a cheap Phase 2 option, not Phase 1 scope.

#### C8 — Scope check: ~10–14 files (SCOPE CHALLENGES / sizing)
- **Claim**: `> **Scope:** ~10–14 files` (implementation-plan.md:556–559).
- **Stress scenario**: enumerate against the corrected seam list. New: `UnionStepRecogniser`,
  `MultiPlanMatchStep`, plus tests (recogniser/translation, `MultiPlanMatchStep` lifecycle,
  equivalence/parity, cache policy — 3–5 test files). Modified: `GremlinStepWalker`,
  `GremlinToMatchTranslator` (`TranslationResult`), `GremlinToMatchStrategy` (splice/build),
  `GremlinPlanCache` and/or `GremlinPlanFingerprint`, possibly `WalkerContext`, possibly
  `AbstractMatchPlanStep` (only if A3's seam route is chosen over the concatenating stream).
- **Code evidence**: file census above; 12–16 realistic total.
- **Verdict**: HOLDS — lands at the estimate's upper edge, inside the soft ~20–25 split ceiling
  and above the ~12 merge floor once tests are counted; the D8-recorded user-approved split
  (2026-07-27) already justifies standalone status. No sizing finding.
