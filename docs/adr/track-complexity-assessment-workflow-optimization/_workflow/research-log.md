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
- **Knock-on (revised after gate A1 + the D3 reversal):** YTDB-1162 stated it
  subsumes YTDB-1100 and YTDB-1056 Part 2 via the step-level-review reshape.
  That reshape is **no longer adopted** — revised D3 keeps the live
  localized-versus-buried rule. Combined with the Fable deferral, **YTDB-1100
  is now fully out of scope** here (neither its implementer upgrade nor its
  step-level reshape lands) and **YTDB-1056 Part 2 is not adopted** either.
  Only **YTDB-1056 Part 1** (the `review-test-behavior` + `review-test-completeness`
  → `review-test-quality` merge) remains in scope. At issue-close: do **not**
  close YTDB-1100 or YTDB-1056-P2 as subsumed; only YTDB-1056-P1 is absorbed.
  The issue body's §"What this subsumes" and §"Step-level review" are walked
  back accordingly.
- **Alternatives rejected:** include Fable for `high` steps now (the issue's
  stated consumer 2) — deferred per user.

### D3: Step-level review keeps the live localized-versus-buried rule (REVISED after gate A1)
- 2026-06-26T09:20Z (decided) → **reversed 2026-06-26T10:40Z** after adversarial
  gate finding A1.
- Step-level review (Phase B sub-step 4, fires on `risk: high` steps) keeps the
  **live `localized-versus-buried` rule** (`review-agent-selection.md`
  §Step-level vs track-level routing) unchanged *in logic*; only the agent
  roster is adapted to the split/merge:
  - At a multi-step high step, the **bug-catcher runs at the step** (its
    bug/logic/leak/null findings get buried once the step diff folds into the
    cumulative diff) and the **test baselines defer to the Phase C track pass**
    (they read whole-suite quality off the cumulative diff identically, so the
    step adds nothing). After the split, the combined `review-bugs-concurrency`
    step-level burial role is inherited by **`review-bugs`** (always) +
    **`review-concurrency`** (when the concurrency category is present — a race
    in the step diff is buriable too). The merged **`review-test-quality`**
    inherits the deferred-to-track-pass role of the two test baselines.
  - The single-step-high override (a sole-step track runs the full
    track-pass-equivalent selection at the step, since Phase C is skipped) is
    unchanged.
- **Why:** the live rule is a reasoned single source of truth for step-vs-track
  timing; the burial argument is sound, and this change should adapt the rule
  to the new roster, not silently invert it. Gate A1 showed the earlier D3
  ("all triggered test reviewers at the step, production omitted") ran exactly
  the reviewers the live rule says add nothing at the step and dropped the one
  the burial test keeps. User chose "minimal change to the live rule."
- **Consequence:** the per-track complexity tag does **not** drive step-level
  selection — step-level stays gated on the per-**step** `risk: high` tag plus
  the live burial routing. The tag drives Phase-A panel breadth and Phase-C
  rigor only (D6). The substep4 evidence ([[substep4-catch-rate-study]]) and
  YTDB-1100's step-level reshape are **not** adopted (see D2 knock-on).
- **Alternatives rejected:** (a) D3-as-originally-stated (test reviewers at
  step, production omitted) — inverts the live rule; (b) keep `review-bugs` +
  add triggered test reviewers (the gate's compromise option) — still runs the
  low-value test passes at the step that Phase C catches identically.

### D4: (SUPERSEDED by revised D3) no separate symmetric step-level rule
- 2026-06-26T09:30Z → **superseded 2026-06-26T10:40Z** after gate A1.
- The earlier D4 generalized the rejected D3 into a "step-level runs
  verification reviewers, defers judgment/production" symmetric rule. With D3
  reversed to keep the live rule, that framing is dropped: the live
  `review-agent-selection.md` §Step-level vs track-level routing **already**
  governs both the code-review and workflow-review groups' step-vs-track
  routing. Step-level review for a workflow-machinery high step follows the
  live rule's **workflow-review-group narrowing**, roster-adapted only — not a
  new symmetric rule. The design reads that paragraph for the exact
  workflow-reviewer step/track split; no new D4 rule is invented.

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
- **Reconciliation re-entry / termination (resolves gate A4).** The missed
  reviewers run as ordinary Phase-A review passes — each its own review type
  under the existing **per-review-type cap-3** on sub-step 3, exactly like the
  predicted-intensity panel. Reconciliation itself fires **at most once per
  Phase A**: after the missed reviewers run and any re-decomposition lands, the
  divergence comparison is **not** re-evaluated against a second upward miss —
  the panel is already at the `high` ceiling, so there is no decompose↔re-review
  ping-pong. If re-decomposition raises `max(step tags)` again it can only reach
  `high`, which the missed-reviewer pass already covered. Design states the
  exact sub-step the missed-reviewer pass re-enters (it appends review types to
  the sub-step-3 panel, then returns to sub-step 4 once) so the loop is bounded.
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
    **Adversarial dropped on `low` is confirmed (gate A5, user choice):** the
    live rule runs Adversarial in every lite/full track; here a genuinely
    `low` track (pure refactor / tests / docs) gets Technical only. An
    architecture-central track does **not** fall through this gap — it hits the
    Architecture HIGH trigger (evaluated over the track's planned work, D9) and
    so tags `high` → Risk + Adversarial; the risk-tag override is the backstop
    for a subtle case the prediction misses.
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

### D9: The per-track tag is computed over the track's planned work, not a file-path list
- 2026-06-26T10:40Z [ctx=safe] — resolves gate A6.
- The pre-decomposition track-tag prediction is computed by running the
  `risk-tagging.md` HIGH-trigger criteria over the track's **planned work** —
  its `## Plan of Work` (the prose sequence of edits) plus its `## Interfaces
  and Dependencies` (the in-scope file set) — **not** over a bare file-path
  list. The HIGH triggers are **content predicates** ("introduces
  synchronization", "modifies WAL recovery", "adds an abstraction layer / SPI
  registration"); a path list alone cannot evaluate them. The planner has
  described the planned edits by the end of Phase 1, so the prediction has the
  content to read. It remains a **prediction** (the planner's described work,
  not the realized diff), reconciled with `max(step tags)` after decomposition
  (D5).
- **Two taxonomies, two purposes.** The **complexity tag** runs the 7
  `risk-tagging.md` HIGH triggers (the same set the per-step risk tag uses).
  **Phase-C reviewer selection** runs the 13 `code-review` file categories.
  They overlap but are distinct and stay distinct: the tag answers "how hard"
  (drives Phase-A breadth + Phase-C rigor, D6); the categories answer "which
  dimensions" (drives the Phase-C reviewer set, D6). The design states the
  mapping but does not merge them.
- **Why:** the whole model rests on the tag being computable pre-decomposition;
  grounding it in the planned work (content) rather than file paths is what
  makes the HIGH content predicates evaluable, and the D5 reconciliation
  absorbs the residual prediction error against the content-based step tags.
- **Alternatives rejected:** compute from the `## Interfaces` file-path set
  alone (the issue's loose "from the in-scope file set" wording) — file paths
  cannot evaluate verb-on-change HIGH triggers, so the prediction would be a
  crude path-location heuristic with a large reconciliation gap.

### D10: Ledger schema delta + resume disambiguation
- 2026-06-26T10:40Z [ctx=safe] — resolves gate A2 / A3 / A7.
- The phase ledger is the persistence home for the new state the removed
  `tier=` field used to carry, plus the new per-track tag. The schema delta:
  - **Remove** `tier=`.
  - **Add** `design_gate=` (`yes`/`no`) — the change-level design decision,
    seeded at Phase 1; replaces the `tier`-keyed model/effort pin and the
    consistency/structural-review design-presence gates.
  - **Add** a **plan-presence / track-count** signal (e.g. `tracks=N` or
    `plan=yes/no`), decided at end of Step 4b — replaces the `tier=minimal`
    trigger the no-plan/single-track resume machinery keys off (precheck's
    "default active track to 1", `workflow.md` "single-track ⇒ track-1, no
    `## Checklist`"). Resolves A3.
  - **Add** a **Phase-1-complete marker** so Step 1c distinguishes the new
    `design + single-track` steady state (`design.md` present, no plan, one
    track file, Phase-1 marker set) from a `full`-tier **mid-authoring crash**
    (`design.md` present, plan absent, Phase-1 marker **unset**). File
    presence alone cannot tell these apart — that is the A2 collision. The
    Step-1c router gains this ledger check. Resolves A2.
  - **Add** the **per-track reconciled-tag home** (the `max(steps)` value per
    track, written at the A→C boundary) so a fresh Phase-C session reads it for
    rigor selection (D6) and the Phase-4 `adr.md` predicate reads it for the
    "∃ track ≥ medium" test (D8). Resolves A7.
- **Touch list:** `workflow-startup-precheck.sh` (the `--append-ledger` key
  set + validation), its 2 test files, `determine_state`, and the Step-1c
  router in `create-plan/SKILL.md`. These are the executable workflow-machinery
  edits that force §1.7 staging.
- **Why:** D5's Phase-C governance and D8's `adr.md` predicate both dereference
  a per-track-tag persistence site from fresh sessions; D8's new cell creates a
  resume collision; D1 removed the `tier=minimal` resume trigger. All four need
  a concrete ledger address, co-resolved here as one schema delta.
- **Alternatives rejected:** persist the per-track tag in the **track file**
  (a marker the Phase-C / Phase-4 reader greps) — workable, but a fresh
  session would parse N track files for a value the ledger already centralizes
  for resume; the ledger is the established resume-state home (it already
  carried `tier`), so the tag joins it.

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
  workflow-machinery high step** — SUPERSEDED. The original answer (a new
  symmetric verification-reviewer rule, old D4) is gone after the D3 reversal.
  The answer is now the **live** `review-agent-selection.md` workflow-review-group
  step/track narrowing, roster-adapted (revised D3 / superseded D4).
- 2026-06-26T09:50Z [ctx=safe] Reconciliation rule (issue OQ1) — RESOLVED by
  D5. Reviewer floor + `domain × complexity` shape (issue OQ2) — RESOLVED by
  D6 (floor defined; complexity = rigor at Phase C, count at Phase A).
- 2026-06-26T09:50Z [ctx=safe] Tension #2 (Phase C `high` extra
  verification) — RESOLVED: `high` adds only iterate-to-convergence, no
  adversarial finding-verification (folded into D6).
- 2026-06-26T10:00Z [ctx=safe] Issue OQ3 (bugs/concurrency boundary) —
  RESOLVED by D7 (cognitive-mode ownership + backstop).

All three of the issue's open questions are resolved (D5 / D6 / D7), and the
adversarial gate's iter-1 findings are resolved into the Decision Log (A1 →
revised D3; A2/A3/A7 → D10; A4 → D5; A5 → D6; A6 → D9; A8/A9 noted below).
The core model is settled (D1-D10). Remaining items are genuine design-phase
detail, not foundational:

- 2026-06-26T10:40Z [ctx=safe] **Where the per-track tags are persisted** —
  RESOLVED by D10 (ledger, per-track reconciled-tag home).
- 2026-06-26T10:40Z [ctx=safe] **When/who computes the track-tag prediction** —
  RESOLVED by D9 (the Phase-1 planner, over the track's planned work).
- 2026-06-26T10:40Z [ctx=safe] **Domain taxonomy reconciliation** — RESOLVED by
  D9 (7 HIGH triggers drive the tag; 13 categories drive reviewer selection;
  separate, mapped not merged).
- 2026-06-26T10:40Z [ctx=safe] **Ledger schema change** — RESOLVED by D10
  (remove `tier=`; add `design_gate`, plan/track-count, Phase-1-complete
  marker, per-track tag home).
- 2026-06-26T10:40Z [ctx=safe] **STILL OPEN (design/impl detail, gate A9):**
  finding prefixes for the split agents. `review-bugs-concurrency` used `BC`;
  `review-bugs` and `review-concurrency` need prefixes (test side uses `TX`).
  Minor; affects `finding-synthesis-recipe.md` + references. Merged
  `review-test-quality` keeps `TB` + `TC` verbatim. Decide while authoring the
  agent files.
- 2026-06-26T10:40Z [ctx=safe] **Noted (gate A8/A9), no log change:** at
  issue-close, do not close YTDB-1100 / YTDB-1056-P2 as subsumed (D2 knock-on);
  author the two split agents in lockstep with the D7 cognitive-mode clauses +
  the triage backstop verbatim.

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

### Adversarial review of this log (2026-06-26T10:55Z) — PASS
Pointer: `_workflow/reviews/research-log-adversarial-iter2.md`. Verdict-producer
iter2: all 9 iter-1 findings **VERIFIED** (2 blockers + 5 should-fix + 2
suggestions resolved into D1–D10). One **new** suggestion **A10** (non-gating):
D1 unwelds single-track from no-design, so two single-track-no-plan shapes now
exist (old `minimal` = design_gate no; new D8 cell = design_gate yes); D10
supplies every ledger field to route both, but whether Step 1c collapses the
old `tier=minimal` single-track resume branch and the new design+single branch
into one `design_gate`-keyed branch or keeps them separate is a design-phase
rendering detail (no log change). **Gate CLEARED** — releases Step 4a.
