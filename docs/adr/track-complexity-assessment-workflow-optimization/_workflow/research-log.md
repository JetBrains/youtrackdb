# Research Log — YTDB-1162: Replace the whole-change tier with a per-track complexity tag

## Initial request

Implement **YTDB-1162** — "Replace the whole-change tier with a per-track
complexity tag" (Feature, Critical, dev-workflow).

Verbatim aim (from the issue): the workflow models change complexity as one
whole-change enum — the `tier` (`full`/`lite`/`minimal`) — but complexity is a
per-track property, and the tier conflates three independent questions into one
value. Remove the tier. Split it into the axes it conflated:

- **Gate 1 (design?)** stays change-level: produce `design.md` or not.
- **Track count** drives plan presence: `implementation-plan.md` exists iff the
  change has more than one track, regardless of Gate 1 — a *deferred*
  decision made once track files exist, not an up-front tier pick.
- **Per-track complexity tag** (`low`/`medium`/`high`) computed from the
  track's in-scope file set by running the existing `risk-tagging.md`
  HIGH-trigger criteria at track granularity; reconciled with `max(step risk
  tags)` after decomposition.

The per-track tag is the single control input for process intensity across
three consumers: (1) decomposition depth / panel intensity in Phase A, (2)
implementer model in Phase B (`high` → Fable 5, else Opus — YTDB-1100), (3)
reviewer selection at Phase B step-level and Phase C track-level (`domain ×
complexity`).

Roster changes: split `review-bugs-concurrency` → `review-bugs` (always-on) +
`review-concurrency` (fires on `concurrency` category); merge
`review-test-behavior` + `review-test-completeness` → `review-test-quality`.

Step-level review (Phase B sub-step 4, `risk: high` only) takes YTDB-1100's
shape: one trigger-matched test reviewer, one iteration, no production panel,
no loop.

Subsumes YTDB-1056 (Parts 1 + 2) and YTDB-1100; close both as subsumed once
accepted.

Stated by the issue as a workflow-modifying change that needs a `design.md`
and runs under §1.7 staging.

Three open design questions carried from the issue:
1. Track-tag → step-tag reconciliation — confirm `max(step tags)` governs
   Phase C; define behavior when the post-decomposition max diverges sharply
   from the track-level prediction (re-plan trigger vs accept drift).
2. Per-complexity reviewer selection — add aggressively, subtract cautiously:
   define the floor a `low` track still gets; keep the risk-tag override as the
   escalation path.
3. The bugs/concurrency non-overlap boundary — ownership of resource leaks on
   concurrent paths, and of logic bugs inside synchronized blocks.

## Decision Log

### D1: Plan presence is decided at the end of Phase 1 Step 4b, within planning
- 2026-06-26T09:20Z [ctx=safe]
- The "plan exists iff >1 track" rule fires once the planner has decomposed
  into tracks and authored the track files — the end of Step 4b — not at an
  up-front Step-4-part-1 tier pick, and not deferred into Phase A execution.
- **Why:** track count (hence the >1 test) is known at the end of Phase 1
  planning; the planner owns track decomposition. Deciding plan presence there
  keeps the whole plan-vs-no-plan choice in one writer's hands and avoids a
  mid-execution materialization. Phase A is per-track *step* decomposition,
  which is later and would needlessly split plan authoring across a session
  boundary.
- **Alternatives rejected:** (a) up-front tier-style pick at Step 4 part 1 —
  impossible, track count unknown before decomposition; (b) defer to Phase A —
  unnecessary, track count is already settled at end of Phase 1, and deferring
  splits plan authoring across a `/clear`.

### D2: Defer the Fable-5 implementer upgrade; implementer stays Opus
- 2026-06-26T09:20Z [ctx=safe]
- The per-track complexity tag drives **two** consumers in this change, not
  three: (1) decomposition depth / Phase-A panel intensity, and (3) reviewer
  selection (Phase B step-level + Phase C). Consumer (2), the implementer
  model, is **deferred** — the implementer stays Opus for every step.
- **Why:** keep this refactor focused on the tier → per-track-tag
  restructuring. The implementer-model swap (YTDB-1100 consumer 2) is an
  independently testable cost/quality experiment and can land in a separate
  change; bundling it couples a model experiment with a structural refactor.
  Not a downgrade — Opus stays everywhere, so [[no-weak-models-for-cost-levers]]
  is not in play.
- **Knock-on:** YTDB-1162 stated it subsumes YTDB-1100. With Fable deferred,
  YTDB-1100 is only *partially* absorbed here (its step-level-review reshape,
  see D3); its implementer-upgrade part stays open. Bookkeeping to reconcile at
  issue-close time (do not close YTDB-1100 as fully subsumed).
- **Alternatives rejected:** include Fable for `high` steps now (the issue's
  stated consumer 2) — deferred per user.

### D3: Step-level review = all domain-triggered TEST reviewers, production omitted
- 2026-06-26T09:20Z [ctx=safe]
- Phase B sub-step 4 (fires on `risk: high` steps only) runs **every** test
  reviewer whose domain trigger matches the step's changes — all related ones,
  not just one — and **omits production code reviewers** entirely. One
  iteration, no production panel, no iteration loop.
- **Why:** across 12/12 audited Java high steps, step-level dimensional review
  caught 0 production-logic bugs; the finding mass was test-quality
  ([[substep4-catch-rate-study]]). Phase C re-runs the full production panel
  over the cumulative diff, so step-level production review is redundant.
  Running all *triggered* test reviewers (vs the issue's single one) keeps the
  test-side feedback thorough while the step is fresh: a high step touching
  two domains (e.g. concurrency + crash-safety) gets both test-concurrency and
  test-crash-safety, not an arbitrary single pick.
- **Alternatives rejected:** (a) the issue's "one trigger-matched test
  reviewer" — too thin for multi-domain steps; (b) the current full
  dimensional panel incl. production reviewers — wasteful per the catch-rate
  evidence; (c) no step-level review at all — loses fresh-context test feedback
  the orchestrator can act on before moving to the next step.

### D4: Step-level review runs verification-side reviewers; one symmetric rule
- 2026-06-26T09:30Z [ctx=safe]
- Generalizes D3 across both code and workflow machinery: **at a high step,
  run the verification-side reviewers triggered by the step's domain; defer
  the judgment/production reviewers to Phase C.**
  - **Code side** — test reviewers (e.g. test-concurrency, test-crash-safety,
    test-quality) run; production reviewers (bugs, concurrency, security,
    code-quality, performance, crash-safety) omitted.
  - **Workflow side** — `review-workflow-consistency` (always — cross-file
    integrity: phantom refs, broken wiring, threshold/glossary drift) plus
    `review-workflow-hook-safety` (when the step touches scripts/hooks/
    settings) run; the judgment reviewers (writing-style, prompt-design,
    instruction-completeness, context-budget) omitted.
- **Why:** the verification reviewers catch *mechanical breakage* — a failing
  test, a phantom reference, a broken hook — that is cheap to fix while the
  step is fresh and expensive once later steps build on it (the D3
  fresh-feedback rationale). The judgment/production reviewers assess quality
  and are redundant with the Phase-C cumulative pass that re-runs the full
  domain-selected panel.
- **Alternatives rejected:** hook-safety only for workflow steps (the earlier
  read) — misses cross-file breakage introduced mid-track that propagates;
  full panel at step level — re-bloats step review with judgment passes Phase C
  already re-runs.

### D5: Reconciliation runs at Phase A, before Phase B, on any upward divergence
- 2026-06-26T09:50Z [ctx=safe]
- When decomposition (Phase A sub-step 4) produces `max(step tags)` **above**
  the predicted track tag — *any* upward miss, including one level — the
  orchestrator, still within Phase A and before the A→C commit:
  1. Runs the higher-intensity strategic reviewers the predicted-intensity
     panel skipped (the "missed reviewers"): predicted `low` + `max(steps)`
     `medium` → run Adversarial; predicted `low`/`medium` + `max(steps)`
     `high` → run Risk (+ Adversarial if not already run). I.e. bring the
     panel up to the reconciled intensity per the Phase-A complexity→panel
     map (D6).
  2. Feeds their findings back into the decomposition and re-checks whether to
     **re-decompose, revise the step plan, or adjust step quantity**
     (add / split / merge steps). Re-decomposition is an *outcome the missed
     reviewers may call for*, not an automatic action.
  3. Re-runs to PASS through the existing Phase-A review-iteration loop
     (cap 3); converges because the intensity ceiling is `high`.
  The reconciled tag (`max(steps)`) then governs Phase C as normal (D6).
- **Downward divergence** (`max(steps)` < prediction): no missed reviewers —
  the panel already over-ran (banked, safe). Phase C reads `max(steps)`,
  floored. Light flag to the decomposer to confirm the steps were not
  under-tagged ("subtract cautiously"); no re-review.
- **Why:** the divergence is detectable at the end of Phase A, before any
  implementation — the cheapest place to fix the plan. Running the missed
  reviewers there catches approach/decomposition problems before code is
  written and lets findings drive re-decomposition while it is free.
- **Supersedes** the earlier sketch (defer reconciliation to Phase C with a
  track-log dedup + post-implementation fix-steps). That deferred discovery
  until after implementation — risking rework if the approach itself was
  flawed — and split the handling across a Phase-A flag + a Phase-C lens that
  this single Phase-A mechanism removes. No track-log dedup is needed because
  nothing is implemented yet.
- **Alternatives rejected:** (a) accept ≤1-level drift, re-run only on a
  2-level miss — under-reviews a decomposition that revealed harder steps;
  user chose to run missed reviewers on any upward miss; (b) the Phase-C
  reconciliation lens — later, riskier, more machinery; (c) re-decompose
  automatically on divergence — re-decomposition is a possible outcome, not
  the trigger's action.

### D6: domain × complexity selection — complexity sets count only at Phase A
- 2026-06-26T09:50Z [ctx=safe]
- The two review phases run different reviewer populations, so complexity
  acts differently in each:
  - **Phase A** runs the strategic trio (technical / risk / adversarial),
    which are holistic, not domain-dimensional. Complexity sets **how many of
    the three** run; domain only primes the Risk/Adversarial lenses:
    - `low` → Technical only
    - `medium` → Technical + Adversarial (narrowed)
    - `high` → Technical + Risk + Adversarial (narrowed)
    This re-keys today's tier-driven panel onto the per-track tag with no
    structural change (Adversarial = the default extra at medium+, Risk = the
    high-stakes add, because a `high` tag *is* a HIGH-trigger characteristic).
  - **Phase C** runs the dimensional panel. **Domain alone** selects the
    reviewer set (the category→agent map); the set is **identical at every
    complexity level**. Complexity moves only the **rigor dial** — iteration
    depth: `low` = single shallow pass, `medium` = normal cap-3 iteration,
    `high` = iterate to convergence — and **nothing more**. `high` adds no
    adversarial finding-verification at Phase C (decided: the substep4
    evidence is that pre-emptive/extra review catches almost no
    production-logic bugs, so it would be unearned cost —
    [[substep4-catch-rate-study]]).
- **Why:** the Phase-C specialists are gated on largely the *same* HIGH
  triggers that make a track `high`, so domain and complexity are correlated
  — a genuinely `low`/`medium` track's domain rarely selects specialists, so
  it gets mostly the floor anyway. Letting complexity *suppress* a specialist
  whose domain is present would subtract review in the dangerous direction
  (e.g. a `low` track touching `configuration` getting less `review-security`).
  So the floor + domain-matched set is sacred at Phase C; complexity only
  decides how hard the crank turns.
- **Floor** (every track, the "subtract cautiously" minimum): `review-bugs`,
  `review-code-quality`, `review-test-quality` (+ `review-test-structure` if
  tests changed). Workflow-machinery analog: `review-workflow-consistency` +
  `review-workflow-context-budget`; specialists = `prompt-design` /
  `instruction-completeness` / `hook-safety` / `writing-style` by glob.
- **Alternatives rejected:** complexity gates *which* Phase-C specialists run
  ("any vs all") — there is no such mechanism; selection is deterministic on
  category presence, and gating it on complexity under-reviews mis-tagged or
  cross-domain tracks.

### D7: bugs/concurrency ownership is by cognitive mode, not location or symptom
- 2026-06-26T10:00Z [ctx=safe]
- The `review-bugs-concurrency` split draws the boundary on the **reasoning
  mode needed to find the defect**, not where the code sits or what the
  symptom is:
  - **`review-concurrency`** owns every defect whose existence or detection
    requires reasoning about **two or more threads interleaving** — data
    races, visibility / safe-publication, lock ordering / deadlock, and
    atomicity of compound operations (the lock not covering what it must).
  - **`review-bugs`** owns every defect findable by **single-threaded
    sequential reasoning** — logic errors, null safety, resource leaks, RID
    handling, state-machine / lifecycle correctness — *regardless of whether
    the code is inside a `synchronized` block, holds a lock, or runs on a
    concurrent path.*
- **Load-bearing clause:** syntactic location (inside a locked region) and
  symptom (a leaked resource) **never transfer ownership; only the reasoning
  mode does.** Resolutions of the three sub-cases:
  - Logic bug inside a `synchronized` block → `review-bugs` (the lock wrapper
    is irrelevant to finding an off-by-one). Only a defect in the
    *synchronization itself* → `review-concurrency`.
  - Resource leak on a concurrent path → `review-bugs` (a local
    acquire/release exit-path defect). The narrow exception: a leak that
    *only manifests under interleaving* → `review-concurrency` (same
    cognitive-mode test applied to leaks).
  - Data race → `review-concurrency` **only**; `review-bugs` defers it and
    never reports it. This is the non-overlap rule that kills the
    double-report.
- **Symmetric tiebreak:** when one piece of code has both a sequential flaw
  and an interleaving flaw, they are **two distinct findings**, one per
  reviewer — never the same defect reported twice.
- **Backstop for the trigger gap (confirmed in):** `review-bugs` is always-on
  but `review-concurrency` fires only on the `concurrency` category. When
  `review-bugs`, reasoning sequentially, encounters concurrent-looking code
  (shared mutable state, locks) that `review-concurrency` was **not** triaged
  onto, it emits a one-line "concurrency triage gap here" note — *not* an
  interleaving analysis — so the orchestrator can launch `review-concurrency`.
  Closes the case where subtle concurrency escapes the categorizer.
- **Why:** "one reviewer = one cognitive mode" (the split's stated principle)
  only holds if the boundary is the mode, not the code's location. Drawing it
  on location/symptom would re-mix the modes (a leak reviewer reasoning about
  races) and reintroduce the double-report the split exists to remove.

### D8: Artifact set derived from the three unbundled axes; adr ⟺ ≥1 medium/high track
- 2026-06-26T10:15Z [ctx=safe]
- The tier conflated exactly three questions (design? / how many tracks? /
  how hard?), so the per-tier artifact table is re-derived by tying each
  artifact to the axis that justifies it:
  - **`design.md` / `design-final.md`** ⟺ design gate = yes.
  - **`implementation-plan.md`** ⟺ track count > 1 (a cross-track summary,
    vacuous for one track).
  - **`adr.md`** ⟺ the change has at least one track of **medium or high**
    reconciled complexity. An all-`low` change writes no durable ADR — no
    decisions worth recording. The ADR is a decision record, so it tracks
    decision *substance* (complexity), not the track-count / design-need
    proxies an earlier draft used.
  - **Universal:** `research-log.md`, `phase-ledger.md`, `plan-review.md`,
    `plan/track-N.md`.
  - **PR-description verdict summary** = the floor, carrying the
    adversarial-gate verdict whenever no `adr.md` exists.
- **Maps to old tiers** (all three preserved): full → design+multi+high
  (`design-final.md` + `adr.md`); lite → multi (`adr.md`); minimal →
  single+low (PR-summary). **Refinements:** an all-`low` multi-track change
  now drops `adr.md` (old `lite` always wrote it); a high-complexity
  single-track change now *gets* `adr.md` (old `minimal` gave none); the
  design+single cell (the tier model's unrepresentable cell) gets
  `design.md` → `design-final.md` + `adr.md`, no plan.
- **Timing:** `adr.md` is a Phase-4 artifact; the complexity predicate reads
  the **reconciled** per-track tags (D5), settled by Phase 4. The
  verdict-fold checks the same predicate. The Phase-4 carrier selection in
  `create-final-design.md` (the old tier table, the load-bearing hub) becomes:
  `design-final` iff design exists; `adr` iff ∃ track ≥ medium.
- **Edge:** the design gate and complexity are highly correlated (a design is
  warranted when a HIGH category is central), so "design + all-low" is rare;
  if it occurs, `design-final.md` exists without `adr.md` and the decisions
  live in `design-final`'s D-records. Coherent.
- **Alternatives rejected:** (a) two-axis table (design × track-count) with
  `adr` ⟺ design OR multi-track — uses proxies for substance; gives a
  trivial all-low multi-track change a durable ADR it does not earn, and
  denies one to a complex single-track change that does; (b) `adr` ⟺
  multi-track only — same two flaws plus it strips the new design+single cell
  of a decision record.

## Surprises & Discoveries

- 2026-06-26T09:10Z [ctx=safe] The tier serves **three distinct mechanical
  roles** today, and the refactor must re-home all three separately — they are
  not one knob:
  1. **Artifact selection** — which Phase-1 + Phase-4 artifacts exist.
     `design.md` (full only), `implementation-plan.md` (lite/full),
     Phase-4 carrier (full→design-final+adr, lite→adr, minimal→PR-summary).
     New model: design.md ← design gate; plan ← track-count>1; Phase-4
     carrier ← (design exists→design-final+adr) + (track-count for adr/PR).
     All become **file-existence + track-count** driven, not tier-named.
  2. **Process intensity / review breadth** — Phase-A review pipeline
     (`track-review.md §"Tier-driven review selection"`, ~lines 620-664)
     currently keys off tier: `minimal`→Technical only; `lite`/`full`→
     Technical always + Risk track-characteristic-gated + Adversarial
     narrowed. New model: per-track tag (low/medium/high) drives this.
  3. **Resume routing** — Step 1c (`create-plan`) and `determine_state`
     (`workflow.md`) read the ledger `tier=` field to route resume. New
     model: route on design.md presence + plan presence + track count.

- 2026-06-26T09:10Z [ctx=safe] Phase A review **already runs per-track**
  (`/execute-tracks` handles one sub-phase of one track), but the panel
  reads the **whole-change** tier as its intensity knob. Swapping that read
  to the per-track tag is a natural fit — the panel granularity is already
  right; only the knob it reads is wrong.

- 2026-06-26T09:10Z [ctx=safe] The implementer model is currently
  hard-coded `model: "opus"` in `step-implementation.md` (~line 267)
  **regardless of risk tag**; `risk-tagging.md`'s quick-ref table shows opus
  for all of low/medium/high. The issue (consumer 2 / YTDB-1100) changes
  this: `high` steps → Fable 5, `low`/`medium` → Opus. Keyed off the
  **step** tag, which the track tag seeds. (Fable 5 is a peer/stronger
  model, not a weak-model cost lever — [[no-weak-models-for-cost-levers]]
  does not bar it.)

- 2026-06-26T09:10Z [ctx=safe] **Reviewer selection takes no complexity
  input today** — it is purely domain/category-driven (binary: category
  present → launch agent). Lives in `code-review/SKILL.md` Step 5,
  mirrored in `review-agent-selection.md`, dispatched by
  `track-code-review.md` + `step-implementation.md`, and **mirrored again
  in `fix-ci-failure/SKILL.md`** (a drift vector). `domain × complexity` is
  net-new selection logic.

- 2026-06-26T09:10Z [ctx=safe] The real edit surface is **wider than the
  issue's subsystem list** (~30 files). Beyond the issue's named files, the
  tier/roster blast radius also hits: `code-review-protocol.md`,
  `fix-ci-failure/SKILL.md`, `track-code-review.md`,
  `finding-synthesis-recipe.md`, `inline-replanning.md` (tier-escalation
  path), `plan-slim-rendering.md`, `conventions-execution.md`,
  `consistency-review.md`, `structural-review.md` (both prompt + workflow),
  `design-review.md`, `create-final-design.md` (Phase-4 carrier table is
  the load-bearing hub), `design-document-rules.md`, `workflow.md`,
  `planning.md`, `research.md`, plus `workflow-startup-precheck.sh` + its 2
  test files (ledger `tier=` field).

- 2026-06-26T09:10Z [ctx=safe] Roster mechanics for the split/merge:
  `review-bugs-concurrency` carries finding prefix `BC`, covers
  logic/null/thread-safety/races/deadlocks/leaks/RID/state — split into
  `review-bugs` (logic/null/leaks/RID/state, always-on) + `review-concurrency`
  (thread-safety/races/deadlocks/publication/lock-ordering, fires on the
  `concurrency` category). `review-test-behavior` (`TB`) +
  `review-test-completeness` (`TC`) merge into `review-test-quality` keeping
  both sub-protocols and prefixes verbatim. The test side **already** split
  concurrency out as `review-test-concurrency` (prefix `TX`) — that is the
  production-split template.

- 2026-06-26T10:00Z [ctx=safe] **This branch must use §1.7 staging — no
  prose-only opt-out.** The change touches `.claude/scripts/`
  (`workflow-startup-precheck.sh` ledger schema) and reviewer-selection
  dispatch logic, which is executable/behavioral workflow machinery, so the
  §1.7(k) prose-only opt-out does not apply. All `.claude/**` edits stage
  under `_workflow/staged-workflow/.claude/`; live workflow stays at develop
  state until the Phase 4 promotion. The branch itself runs under the
  **current** (tier) model — the new per-track-tag model goes live only at
  promotion — so "self-application" is validated by the workflow reviewers
  (consistency / instruction-completeness / prompt-design) reading the staged
  edits at Phase C, not by running the branch under the new model.

- 2026-06-26T09:35Z [ctx=safe] **Phase A ordering: reviews run before
  decomposition.** `track-review.md` §What You Do: sub-step 3 runs the
  technical/risk/adversarial panel, sub-step 4 decomposes into steps and
  assigns per-step risk tags. So when the panel runs, step tags do not yet
  exist — the panel intensity must be sized from the **track-tag prediction**
  alone (today: the change-level tier). `max(step tags)` is computable only
  after sub-step 4. This makes prediction-vs-reconciled divergence real: the
  panel can run at the predicted intensity and only afterward discover the
  steps are harder. (Reversing the order — decompose then review — would erase
  the divergence but change what the panel reviews; out of scope.)

- 2026-06-26T09:10Z [ctx=safe] **Plan-presence timing** — the issue says
  plan presence becomes "a deferred decision made once the track files
  exist." Track files exist at the **end of Phase 1 Step 4b** (the planner
  decomposes into tracks during planning; Phase A is per-track *step*
  decomposition, later). So plan presence is decided at end of Step 4b
  after track files are authored, not at the up-front Step-4-part-1 tier
  pick — a shift *within* Phase 1, not a deferral to Phase A. (Two Explore
  agents read it as "Phase A"; that appears to be an over-read of "Phase 1
  decomposition" — confirm with user.)

## Open Questions

- 2026-06-26T09:20Z [ctx=safe] **The "test reviewer" set under D3 for a
  workflow-machinery high step** — RESOLVED by D4: consistency (always) +
  hook-safety (when scripts/hooks/settings touched); judgment reviewers defer
  to Phase C.
- 2026-06-26T09:50Z [ctx=safe] Reconciliation rule (issue OQ1) — RESOLVED by
  D5. Reviewer floor + `domain × complexity` shape (issue OQ2) — RESOLVED by
  D6 (floor defined; complexity = rigor at Phase C, count at Phase A).
- 2026-06-26T09:50Z [ctx=safe] Tension #2 (Phase C `high` extra
  verification) — RESOLVED: `high` adds only iterate-to-convergence, no
  adversarial finding-verification (folded into D6).
- 2026-06-26T10:00Z [ctx=safe] Issue OQ3 (bugs/concurrency boundary) —
  RESOLVED by D7 (cognitive-mode ownership + backstop).

All three of the issue's open questions are now resolved (D5 / D6 / D7). The
core model is settled (D1-D7). Remaining items are **design-phase detail** to
settle while authoring `design.md`, not foundational research:

- 2026-06-26T10:00Z [ctx=safe] **Where the per-track tags are persisted.** The
  reconciled `max(steps)` tag governs Phase C, read by a *fresh*
  `/execute-tracks` Phase C session, so it must be durable — ledger field vs
  track-file marker. Lean ledger (a fresh session reads it without parsing the
  track). The pre-decomposition prediction also needs a home if it is to be
  compared at reconciliation.
- 2026-06-26T10:00Z [ctx=safe] **When/who computes the track-tag prediction.**
  From the track's in-scope file set in `## Interfaces and Dependencies`
  (populated at Phase 1) via the risk-tagging HIGH criteria — computed by the
  Phase-1 planner, or by the Phase-A orchestrator at panel-selection time.
  Either works; pick one for determinism.
- 2026-06-26T10:00Z [ctx=safe] **Domain taxonomy reconciliation.** Complexity
  tagging uses risk-tagging's 7 HIGH triggers; reviewer selection uses
  code-review's 13 file categories. They overlap but are not identical — design
  must state which taxonomy drives the complexity tag vs the Phase-C reviewer
  selection, and how they map.
- 2026-06-26T10:00Z [ctx=safe] **Finding prefixes for the split agents.**
  `review-bugs-concurrency` used `BC`; `review-bugs` and `review-concurrency`
  need prefixes (test side uses `TX` for `review-test-concurrency`). Minor;
  affects `finding-synthesis-recipe.md` example + references. Merged
  `review-test-quality` keeps `TB` + `TC` verbatim per the issue.
- 2026-06-26T10:00Z [ctx=safe] **Ledger schema change.** Remove the `tier=`
  field; add `design_gate=` (and the per-track tag home above). Touches
  `workflow-startup-precheck.sh` + its 2 test files +
  `determine_state`/Step-1c readers.

## Baseline and re-validation

Workflow-modifying branch. Baseline: branch forked from `develop` at
`a1311db00c` (2026-06-26). The branch edits `.claude/workflow/**`,
`.claude/skills/**`, `.claude/agents/**`, and `.claude/scripts/**` under §1.7
staging; live workflow stays at `develop` state until the Phase 4 promotion.
Re-validate this baseline against `develop` on any rebase/merge before
promotion.

## Adversarial gate record

### Adversarial review of this log (2026-06-26T10:30Z) — NEEDS REVISION: 2 blockers, 5 should-fix, 2 suggestions
Pointer: `_workflow/reviews/research-log-adversarial-iter1.md`.
- **A1 [blocker]** — D3/D4 invert the live `localized-versus-buried`
  step-routing rule (`review-agent-selection.md:97-137`): it keeps
  `review-bugs-concurrency` at the step (burial) and defers test baselines;
  D3 does the reverse. → back to research (user decision).
- **A2 [blocker]** — D8's design+single steady state is byte-identical to the
  Step-1c full-tier mid-authoring-crash signal; needs a disambiguating ledger
  signal. → resolve via ledger schema (with A3/A7).
- **A3/A4/A5/A6/A7 [should-fix]** — name the resume signal replacing
  `tier=minimal` (A3); define D5's reconciliation re-entry/cap (A4);
  architecture-centrality vs file-set complexity (A5); the per-track tag is
  computed over content not a bare file path list, taxonomy reconciliation
  unresolved (A6); name the tag persistence site + ledger schema delta (A7).
- **A8/A9 [suggestion]** — YTDB-1100 not fully subsumed (A8, already in D2);
  author the split agents in lockstep with prefixes (A9, design-phase).
