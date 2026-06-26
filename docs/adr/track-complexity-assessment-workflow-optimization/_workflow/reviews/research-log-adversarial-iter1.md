<!-- review-file schema §2.5 — adversarial, research-log scope, iter1 -->

## Manifest

```yaml
role: reviewer-adversarial
scope: research-log
phase: 1
iteration: 1
target: docs/adr/track-complexity-assessment-workflow-optimization/_workflow/research-log.md
matched_categories: [Workflow machinery, Architecture / cross-component coordination]
verdict: NEEDS REVISION
findings: 9
counts:
  blocker: 2
  should-fix: 5
  suggestion: 2
index:
  - {id: A1, sev: blocker,     anchor: "### A1 ",  loc: "research-log.md D3/D4", cert: "Challenge D3", basis: "step-level test-only review inverts the live localized-vs-buried routing rule; production omission contradicts the catch-rate evidence it cites"}
  - {id: A2, sev: blocker,     anchor: "### A2 ",  loc: "research-log.md D8 + create-plan Step 1c", cert: "Challenge D8", basis: "design+single-track cell collides with the existing full-tier crash-recovery resume branch; resume cannot disambiguate"}
  - {id: A3, sev: should-fix,  anchor: "### A3 ",  loc: "research-log.md D1", cert: "Challenge D1", basis: "plan-exists-iff->1-track decided end-of-Step-4b is sound but undermines the minimal single-track-file resume invariant the precheck and workflow.md hard-code"}
  - {id: A4, sev: should-fix,  anchor: "### A4 ",  loc: "research-log.md D5", cert: "Challenge D5 / Assumption", basis: "reconciliation re-feeds findings into the cap-3 Phase-A loop but the loop has no re-decompose re-entry; convergence-on-high claim untested"}
  - {id: A5, sev: should-fix,  anchor: "### A5 ",  loc: "research-log.md D6", cert: "Challenge D6", basis: "Phase-A complexity->panel map drops Risk for an architecture-central low/medium track that today's track-characteristic gate would run Risk on"}
  - {id: A6, sev: should-fix,  anchor: "### A6 ",  loc: "research-log.md OQ (taxonomy)", cert: "OQ taxonomy", basis: "track-tag computed from a file set via per-step-content HIGH triggers is undefined; the unresolved domain-taxonomy OQ is load-bearing for the single control input"}
  - {id: A7, sev: should-fix,  anchor: "### A7 ",  loc: "research-log.md OQ (tag home) + ledger schema", cert: "OQ tag-home", basis: "where reconciled per-track tags persist is unresolved yet every downstream consumer (Phase C, adr predicate) reads it; ledger schema change left open"}
  - {id: A8, sev: suggestion,  anchor: "### A8 ",  loc: "research-log.md D2", cert: "Challenge D2", basis: "deferring Fable leaves YTDB-1162's stated 3-consumer model 2-consumer; subsume-claim bookkeeping risk on YTDB-1100 close"}
  - {id: A9, sev: suggestion,  anchor: "### A9 ",  loc: "research-log.md D7", cert: "Challenge D7", basis: "cognitive-mode bugs/concurrency boundary needs the live agent prompts rewritten in lockstep or the backstop note becomes the only working seam"}
evidence_base:
  challenges: 7
  violation_scenarios: 0
  assumption_tests: 2
```

## Evidence base

### DECISION CHALLENGES

#### Challenge: Decision D3 — Step-level review = all domain-triggered TEST reviewers, production omitted
- **Chosen approach**: At a `risk: high` step, run *every* triggered test reviewer (test-concurrency, test-crash-safety, test-quality, …), omit all production reviewers, one iteration, no loop.
- **Best rejected alternative**: the alternative the log lists as "(a) the issue's one trigger-matched test reviewer" is the wrong foil. The strongest rejected alternative is the **live rule**, which D3 does not name: run the *production* bug-catcher at the step and *defer* the test reviewers to Phase C — the exact inverse of D3.
- **Counterargument trace**:
  1. The live routing rule (`review-agent-selection.md` §Step-level vs track-level routing, lines 125-137) runs **only `review-bugs-concurrency`** at a multi-step high step and explicitly **defers `review-test-behavior` and `review-test-completeness` to the track pass**, with a stated rationale: "The two test-review baselines read whole-suite quality off the cumulative diff identically, so the step adds nothing" (line 135-137). The localized-versus-buried test (lines 98-101) is the governing principle: an agent runs at a step only when its findings are *localized to that step's diff and would be buried if deferred*.
  2. D3 inverts this exactly: it runs the test reviewers at the step (whose findings the live rule says are NOT buried — they read identically off the cumulative diff) and omits the production bug-catcher (whose findings the live rule says ARE buried).
  3. D3's cited evidence — `substep4-catch-rate-study`, "0 production-logic bugs across 12/12 high steps" — argues that the *production panel* catches nothing at the step. But that is an argument for dropping the heavy production *panel* (security, performance, code-quality), not for dropping `review-bugs-concurrency`, which the live rule keeps at the step precisely because resource-leak / null / logic findings get buried when the step folds into the cumulative diff. D3 throws out the one reviewer the live rule's burial test keeps and keeps the ones it discards.
- **Codebase evidence**: `review-agent-selection.md:128-137` (baseline narrowing to `review-bugs-concurrency` only; test baselines deferred with the "reads identically off cumulative diff" rationale); `risk-tagging.md:68` (the live high-step set is `review-bugs-concurrency` + triggered conditional, not a test-only panel).
- **Survival test**: NO (should reconsider). The decision is internally coherent but rests on a misread of which reviewer the catch-rate study indicts. The study indicts the production *panel*; D3 reads it as indicting the production *bug-catcher* and inverts the live burial routing without confronting it. At minimum the Why must explain why the localized-versus-buried principle no longer governs.

#### Challenge: Decision D4 — generalize D3 to "run verification-side reviewers, defer judgment/production"
- **Chosen approach**: One symmetric rule — at a high step run the verification reviewers (test + consistency + hook-safety), defer the judgment/production reviewers to Phase C.
- **Best rejected alternative**: keep the live asymmetry — the workflow side already runs `prompt-design` at the step (a *judgment* reviewer), because its findings are localized to the changed prompt file.
- **Counterargument trace**:
  1. D4 classes `prompt-design` as a "judgment reviewer" to defer to Phase C. The live rule (`review-agent-selection.md:150-156`) runs `prompt-design` *at the step* and gives the reason: "prompt-design's findings (one prompt's internal decision rules, frontmatter, `$ARGUMENTS`) are localized to the changed file."
  2. D4's clean "verification runs / judgment defers" split therefore drops a reviewer the live rule keeps at the step on the same localization logic D4 claims to honor. The symmetry D4 imposes is cleaner than the live rule but loses the localized prompt-design coverage.
  3. The code side has the mirror problem: D4 says "test reviewers run, production reviewers omitted," but the live rule runs the production `review-bugs-concurrency` at the step and defers the test baselines. D4's symmetry is the inverse of the live machinery on both sides at once.
- **Codebase evidence**: `review-agent-selection.md:139-162` (workflow step-level group: hook-safety AND prompt-design run; consistency/context-budget/writing-style/instruction-completeness defer). D4's "consistency always + hook-safety" omits prompt-design.
- **Survival test**: WEAK. The generalization is attractive but mis-maps onto the live agent assignment on both axes; the rationale must reconcile with the localized-versus-buried single-source-of-truth rule rather than replace it silently.

#### Challenge: Decision D8 — adr ⟺ ≥1 medium/high track; the new design+single-track cell
- **Chosen approach**: Re-derive the artifact set on three axes; introduce a "design + single-track" cell that produces `design.md` → `design-final.md` + `adr.md` and **no plan**.
- **Best rejected alternative**: keep the design-implies-multi-track invariant (the live `planning.md §Tier classification` rule, line 126: "A design-needing change is multi-track by construction").
- **Counterargument trace**:
  1. The live `create-plan` Step 1c resume router (`SKILL.md:174-218`) treats "`design.md` exists, `implementation-plan.md` does not" as the **`full`-tier crash-recovery branch** — it assumes the only reason a design exists without a plan is an interrupted Step 4a/4b, and it runs `git log`/`git status` probes to decide whether to resume into 4a or 4b.
  2. D8's new "design + single-track" cell produces exactly this on-disk shape **as a steady state**: `design.md` present, no `implementation-plan.md` (single track ⇒ no plan per D1), one `plan/track-1.md`. A fresh `/execute-tracks` or `/create-plan` resume hitting this state will mis-route it into the crash-recovery branch and try to re-derive a plan that should not exist.
  3. The `create-final-design.md` carrier table (lines 89-103) keys the ADR boundary on **Gate 2 (multi-track)** explicitly: "Gate 2 (multi-track) is the durable-ADR boundary." D8 re-keys it on complexity (≥1 medium/high). That is a substantive rewrite of the load-bearing Phase-4 hub, and the new design+single cell means a single-track change now reaches `design-final.md` — a carrier the table currently calls multi-track-only — with no resume path defined for the design+single+no-plan disk state.
- **Codebase evidence**: `create-plan/SKILL.md:174-234` (design-exists/plan-absent = full crash-recovery, mutually exclusive branches keyed on file presence); `create-final-design.md:97-103` (carrier table keyed on tier with Gate-2/multi-track as the explicit ADR boundary); `planning.md:126` (design ⇒ multi-track invariant).
- **Survival test**: NO (should reconsider). The design+single-track cell is a genuinely new disk state the resume routers were never built to recognize, and it collides byte-for-byte with the full-tier crash-recovery signal. D8 must define how resume distinguishes "design+single steady state" from "full mid-authoring crash" — file presence alone cannot, which is the whole basis of the current Step 1c branch.

#### Challenge: Decision D1 — plan presence decided at end of Phase 1 Step 4b
- **Chosen approach**: "plan exists iff >1 track," decided once track files exist at end of Step 4b.
- **Best rejected alternative**: none better on *timing* — the log is right that track count is known at end of Step 4b. The challenge is to the *invariant it breaks*, not the timing.
- **Counterargument trace**:
  1. Today "no plan" is welded to `minimal` = no-design + single-track. The precheck (`workflow-startup-precheck.sh:1932,1964`) and `workflow.md:306-310` hard-code "single-track ⇒ `track-1` by construction, no `## Checklist`" and default the active track to `1` *because the tier is `minimal`*.
  2. D1 unbundles "single-track ⇒ no plan" from the design gate, so a single-track change can now also be design=yes (the D8 cell). The "no plan, default to track-1" machinery currently reads the ledger `tier=minimal` field as its trigger; with tier removed, the trigger must be re-expressed as "track count == 1" — but track count is not a ledger field today.
  3. So D1 is sound on timing but silently obligates a new resume signal: the machinery that today keys "single-track / no-plan" off `tier=minimal` must re-key off a track-count or plan-presence signal the ledger does not yet carry. The log's Open Question "where the per-track tags are persisted" touches this but does not name the plan-presence / track-count signal the resume routers need.
- **Codebase evidence**: `workflow-startup-precheck.sh:1962-1966` (default track to 1 for single-track minimal); `workflow.md:306-310` (single-track = track-1 by construction, tier-gated).
- **Survival test**: WEAK. The decision holds but its Why omits the resume-signal obligation: removing `tier` removes the `minimal` trigger the no-plan/single-track path keys off, and D1 must say what replaces it.

#### Challenge: Decision D5 — reconciliation re-runs through the existing cap-3 Phase-A loop
- **Chosen approach**: On any upward divergence, run the missed strategic reviewers, feed findings into decomposition, re-decompose/revise, and "re-run to PASS through the existing Phase-A review-iteration loop (cap 3); converges because the intensity ceiling is high."
- **Best rejected alternative**: the superseded Phase-C reconciliation sketch (the log argues against it well) — not the strongest foil. The strongest is: the existing cap-3 loop has no re-decomposition re-entry, so "re-run through the existing loop" assumes machinery that is not there.
- **Counterargument trace**:
  1. The Phase-A review-iteration protocol (`track-review.md:802-810`) is "max 3 iterations per review type, findings cumulative" over the *review* sub-step (sub-step 3). Decomposition is sub-step 4, *after* the review loop converges (`track-review.md §What You Do` ordering, confirmed in the Surprises log).
  2. D5 wants reconciliation to (a) run reviewers that were skipped at sub-step 3, then (b) feed findings back into sub-step-4 decomposition, then (c) "re-run to PASS." But the live loop counts iterations *per review type* on the *review* pass; there is no defined re-entry that re-opens decomposition and re-runs the panel against a re-decomposed roster. D5 asserts the loop absorbs this without pointing at the re-entry mechanism.
  3. "Converges because the ceiling is high" conflates two loops: the review-iteration cap-3 (per review type) and the propose→re-decompose→re-review cycle D5 introduces. The latter has no cap stated. If re-decomposition raises `max(step tags)` again (a step split into two high steps), the prediction-vs-reconciled comparison could re-fire — D5 does not bound that.
- **Codebase evidence**: `track-review.md:802-810` (cap-3 is per-review-type on sub-step 3); `track-review.md:811-902` (decomposition is sub-step 4, downstream of the review loop, with no re-entry from a later reviewer pass).
- **Survival test**: WEAK. The mechanism is plausible but the "existing loop absorbs it" claim is unverified against the actual loop, which caps the review pass, not a decompose↔re-review cycle. Design must specify the re-entry and its termination.

#### Challenge: Decision D6 — Phase-A complexity→panel map (low=Tech only; medium=+Adversarial; high=+Risk)
- **Chosen approach**: Complexity sets how many of the strategic trio run; `low`→Technical only, `medium`→+Adversarial, `high`→+Risk+Adversarial.
- **Best rejected alternative**: keep the live track-characteristic gate for Risk (`track-review.md:649-654`): Risk runs whenever the track has critical paths, performance constraints, OR major architectural decisions — independent of a complexity tag.
- **Counterargument trace**:
  1. Today (`track-review.md:645-664`) Risk is **track-characteristic-gated**, and Adversarial runs in *every* `lite`/`full` track. D6 re-keys both onto the complexity tag: a `low` track gets Technical only — *no Adversarial*.
  2. Counterexample: a track that is `low` by the file-set HIGH-trigger computation (small footprint, no synchronization/WAL/API edits) but realizes a **major architectural decision** in those few files. The live gate runs Risk + Adversarial on it (architectural decisions warrant Risk, line 654). D6 gives it Technical only — it drops both Risk and Adversarial for an architecture-central low-complexity track.
  3. `matched_categories` for THIS log include "Architecture / cross-component coordination," and the issue itself is a small-footprint-but-architecturally-central change pattern — exactly the case D6 under-reviews. The complexity tag (file-set-derived) and the architectural-centrality characteristic are not the same axis; D6 collapses them.
- **Codebase evidence**: `track-review.md:649-664` (Risk gated on critical paths / perf / *major architectural decisions*; Adversarial runs in every lite/full track — D6 makes Adversarial conditional on `medium+`, a strict reduction at `low`).
- **Survival test**: WEAK. D6 is a clean re-keying for the common case but strictly reduces review for the low-footprint-architecturally-central track the live characteristic gate covers. Either fold "architectural decision present" into a panel-bump override, or justify dropping the live architectural-decision Risk row.

#### Challenge: Decision D2 — defer Fable, implementer stays Opus
- **Chosen approach**: Drop consumer (2); ship a 2-consumer model.
- **Best rejected alternative**: include Fable for high steps now (the issue's stated 3-consumer model).
- **Counterargument trace**: low-stakes — the user directed the deferral and Opus-everywhere is safe per `[[no-weak-models-for-cost-levers]]`. The residual risk is the subsume bookkeeping (D2 itself flags it): YTDB-1162 stated it subsumes YTDB-1100, but with Fable deferred YTDB-1100 is only partially absorbed, so closing it "as subsumed" at accept-time would lose its implementer-upgrade part.
- **Codebase evidence**: `step-implementation.md` hard-codes `model: "opus"` (Surprises log, ~line 267); no Fable wiring exists, so deferral is a no-op on the code surface.
- **Survival test**: YES. Decision survives; only the issue-close bookkeeping needs the partial-subsume note D2 already carries.

### ASSUMPTION CHALLENGES

#### Assumption test: the track tag is computable from the in-scope file set via the risk-tagging HIGH triggers
- **Claim**: "per-track complexity tag computed from the track's in-scope file set by running the existing `risk-tagging.md` HIGH-trigger criteria at track granularity."
- **Stress scenario**: the HIGH triggers are overwhelmingly **per-step-content** predicates, not file-set predicates: "Introduces or modifies synchronization," "Modifies WAL records," "Changes the record-read path," "Adds a new SPI registration." None of these can be evaluated from a *file list* in `## Interfaces and Dependencies` — they require reading what the change *does* to those files, which is exactly what is not yet decided pre-decomposition.
- **Code evidence**: `risk-tagging.md:97-179` — every HIGH trigger is a verb on a change ("introduces", "modifies", "changes", "adds"), not a property of a path. The only file-shaped trigger is the Workflow-machinery "edits a hook/script/settings" and "edits root CLAUDE.md," and even those distinguish behavioral from prose edits (the prose-only cap, lines 282-309), which a file list cannot tell apart.
- **Verdict**: FRAGILE. The track tag is computable only if "run the HIGH criteria at track granularity" is redefined as a *coarser* heuristic over the planned edits (the `## Plan of Work` prose, not just the file set), and the log's own Open Question "Domain taxonomy reconciliation" admits this is unsettled (risk-tagging's 7 triggers vs code-review's 13 categories). The single control input rests on an undefined computation.

#### Assumption test: removing the tier leaves no orphaned tier readers
- **Claim**: implicit — the refactor "removes the whole-change tier enum" and re-homes its three roles cleanly.
- **Stress scenario**: the `tier=` ledger field and the literal strings `full`/`lite`/`minimal` are read at no fewer than five live sites that must all be rewritten in one staged batch or the resume machinery wedges: `create-plan/SKILL.md:144-265` (tier-dependent routing, 20+ references), `workflow.md:294-310` (tier-gated active-track re-derivation), `create-final-design.md:89-103` (tier-keyed carrier table), `track-review.md:620-664` (tier-keyed Phase-A panel), `workflow-startup-precheck.sh` (`--tier` flag, `LEDGER_TIER`, `reject_bad_ledger_value "tier"`, single-track default) + its 2 test files.
- **Code evidence**: the Surprises log's own "wider than the issue's subsystem list (~30 files)" entry undercounts the *resume-routing* readers specifically — the create-plan Step 1c branch logic and the workflow.md active-track re-derivation are control-flow, not just artifact-selection, and a half-migrated tier read leaves resume routing ambiguous (see A2, A3).
- **Verdict**: FRAGILE. The blast-radius inventory is present but the resume-routing subset is the dangerous part and is under-emphasized; A2 and A3 are concrete instances where a tier read carries control-flow that file-presence alone cannot replace.

## Findings

### A1 [blocker]
**Certificate**: Challenge D3 — Step-level review = all domain-triggered TEST reviewers, production omitted
**Target**: Decision D3 (and its generalization D4)
**Challenge**: D3 inverts the live `localized-versus-buried` step-routing rule. The live machinery (`review-agent-selection.md` §Step-level vs track-level routing) runs **`review-bugs-concurrency` at the step** (its bug/leak/null findings get buried in the cumulative diff) and **defers the test baselines to Phase C** (they "read whole-suite quality off the cumulative diff identically, so the step adds nothing"). D3 does the opposite: test reviewers at the step, production omitted. The `substep4-catch-rate-study` evidence D3 cites indicts the heavy production *panel*, not the production bug-catcher the burial rule keeps. As written, D3 would stop running the one step-level reviewer the live burial test says must see each step in isolation, and start running the reviewers it says the step adds nothing for.
**Evidence**: `review-agent-selection.md:125-137` (baseline narrows to `review-bugs-concurrency`; test baselines deferred with explicit rationale); `review-agent-selection.md:98-101` (localized-versus-buried governing principle); `risk-tagging.md:68` (live high-step set).
**Proposed fix**: Reconcile D3/D4 with the live localized-versus-buried single-source-of-truth rule rather than replacing it silently. Either (a) keep `review-bugs-concurrency`/`review-bugs` at the step on the burial argument and add the *triggered* test reviewers whose findings are genuinely localized (test-concurrency on a new threading test), or (b) state explicitly why the burial principle no longer governs and why test-baseline findings (which the live rule says read identically off the cumulative diff) are now worth a step-level pass. Until reconciled, this gates: it sends the wrong reviewer to the step.

### A2 [blocker]
**Certificate**: Challenge D8 — adr ⟺ ≥1 medium/high track; the new design+single-track cell
**Target**: Decision D8
**Challenge**: D8's new "design + single-track" cell produces a steady-state on-disk shape — `design.md` present, no `implementation-plan.md`, one `plan/track-1.md` — that is **byte-for-byte the signal the live `create-plan` Step 1c router reads as a `full`-tier mid-authoring crash** (design exists, plan absent). The router runs `git log`/`git status` probes and resumes into Step 4a/4b to *derive a plan*. A design+single steady state hitting this branch would be mis-routed into plan derivation that must not happen. Separately, D8 re-keys the `create-final-design.md` ADR boundary from Gate-2/multi-track (the live "Gate 2 (multi-track) is the durable-ADR boundary") to complexity, and routes a single-track change to `design-final.md`, a carrier that table calls multi-track-only — with no resume path defined for the new disk state.
**Evidence**: `create-plan/SKILL.md:174-234` (design-exists/plan-absent = full crash-recovery; mutually-exclusive file-presence branches); `create-final-design.md:97-103` (tier-keyed carrier table, Gate-2 ADR boundary); `planning.md:126` (live design⇒multi-track invariant D8 breaks).
**Proposed fix**: Either keep the design⇒multi-track invariant (so the design+single cell never occurs and the crash-recovery branch stays unambiguous), or define the resume signal that distinguishes a design+single steady state from a full mid-authoring crash — file presence cannot, so a new ledger field (e.g. plan-presence/track-count, or an explicit "Phase 1 complete" marker) is required, and D8 must specify it and the Step-1c re-route. Record the decision in the log before any artifact derives.

### A3 [should-fix]
**Certificate**: Challenge D1 — plan presence decided at end of Phase 1 Step 4b
**Target**: Decision D1
**Challenge**: D1's timing is right, but it removes the `tier=minimal` trigger the no-plan/single-track resume machinery keys off without naming the replacement. The precheck defaults the active track to `1` and `workflow.md` hard-codes "single-track ⇒ `track-1`, no `## Checklist`" *because tier is `minimal`*. With tier gone and single-track no longer welded to no-design, that machinery must re-key off a "track count == 1" or "plan absent" signal that the ledger does not carry today.
**Evidence**: `workflow-startup-precheck.sh:1962-1966` (single-track minimal defaults track to 1); `workflow.md:306-310` (tier-gated single-track re-derivation).
**Proposed fix**: Add a Decision-Log entry (or fold into D1) naming the resume signal that replaces `tier=minimal` for the no-plan/single-track path: a ledger track-count or plan-presence field, decided at end of Step 4b alongside the plan-presence rule. This overlaps the open "where tags persist" question (A7) — resolve them together.

### A4 [should-fix]
**Certificate**: Challenge D5 — reconciliation re-runs through the existing cap-3 Phase-A loop
**Target**: Decision D5
**Challenge**: D5 asserts reconciliation "re-runs to PASS through the existing Phase-A review-iteration loop (cap 3); converges because the ceiling is high." The existing loop caps iterations *per review type* on the *review* sub-step (sub-step 3); decomposition is the downstream sub-step 4 with no defined re-entry that re-opens decomposition and re-runs the panel against a re-decomposed roster. The decompose→re-review cycle D5 introduces is a different loop with no stated cap, and re-decomposition that raises `max(step tags)` again could re-fire the divergence comparison.
**Evidence**: `track-review.md:802-810` (cap-3 is per-review-type on sub-step 3); `track-review.md:811-902` (decomposition sub-step 4, no reviewer re-entry).
**Proposed fix**: In design, specify the reconciliation re-entry explicitly: which sub-step it re-enters, how the missed-reviewer pass interacts with the per-review-type cap-3, and the termination condition for the decompose↔re-review cycle (e.g. reconciliation runs once at the reconciled ceiling and does not re-fire on a second upward miss within the same Phase A).

### A5 [should-fix]
**Certificate**: Challenge D6 — Phase-A complexity→panel map
**Target**: Decision D6
**Challenge**: D6 makes Adversarial conditional on `medium+` and Risk conditional on `high`, keyed purely on the file-set complexity tag. The live gate runs Risk + Adversarial whenever a track has critical paths, performance constraints, OR **major architectural decisions** — independent of footprint. A small-footprint track that realizes a major architectural decision computes as `low` but warrants Risk + Adversarial under the live gate; D6 gives it Technical only. Architectural centrality and file-set complexity are different axes; D6 collapses them. The matched category for this very log includes "Architecture / cross-component coordination," so the under-review case is live.
**Evidence**: `track-review.md:649-664` (Risk gated on critical paths / perf / major architectural decisions; Adversarial runs in every lite/full track today).
**Proposed fix**: Either retain an "architectural decision present" panel-bump override on top of the complexity map (so a `low` architecture-central track still gets Risk + Adversarial), or justify in the Why why dropping the live architectural-decision Risk row is acceptable. Note that D6 also makes Adversarial — which runs in *every* live lite/full track — conditional; confirm that reduction is intended.

### A6 [should-fix]
**Certificate**: Assumption test — track tag computable from the file set via HIGH triggers
**Target**: Open Question "Domain taxonomy reconciliation" + the core model's single control input
**Challenge**: The whole model rests on "the per-track tag computed from the in-scope file set via the risk-tagging HIGH criteria," but the HIGH triggers are per-step-*content* predicates ("introduces synchronization," "modifies WAL"), not file-set predicates — they cannot be evaluated from a path list in `## Interfaces and Dependencies` without reading what the change does. The log's own Open Question (risk-tagging's 7 triggers vs code-review's 13 categories, "design must state which taxonomy drives the complexity tag") is load-bearing for the single control input and is unresolved. Per the gate's research-log criteria, an unresolved load-bearing open question is at least a should-fix.
**Evidence**: `risk-tagging.md:97-309` (every HIGH/MEDIUM trigger is a verb-on-change, plus the prose-only cap that a file list cannot evaluate); the log's Open Questions block (taxonomy reconciliation unresolved).
**Proposed fix**: Resolve the taxonomy Open Question into the Decision Log before deriving artifacts: define precisely what "run the HIGH criteria at track granularity" computes over (the `## Plan of Work` planned edits, not just the file set), how the 7-trigger risk taxonomy maps to the 13-category reviewer taxonomy, and the prediction's tolerance to being wrong (A4's reconciliation absorbs the upward miss, but the computation itself must be defined).

### A7 [should-fix]
**Certificate**: Open Question — where the reconciled per-track tags persist + ledger schema change
**Target**: Open Questions "Where the per-track tags are persisted," "Ledger schema change"
**Challenge**: The reconciled `max(steps)` tag governs Phase C, read by a *fresh* `/execute-tracks` Phase C session, and the `adr.md` predicate (D8) reads the reconciled tags at Phase 4 — both fresh sessions with no in-memory state. Yet where these tags live (ledger field vs track-file marker) is still an Open Question, and the ledger schema change (remove `tier=`, add `design_gate=` and the per-track tag home) is unresolved. These are load-bearing: D8's adr predicate and D5's Phase-C governance both dereference a persistence site that is not yet decided.
**Evidence**: `workflow-startup-precheck.sh:46-86` (ledger schema is a fixed key set the script parses; adding a per-track tag is a schema change touching the script + 2 test files); D8 (adr predicate reads reconciled tags); the log's Open Questions block.
**Proposed fix**: Decide the per-track-tag persistence home and the ledger schema delta in the Decision Log before Phase-1 artifacts derive — the adr predicate (D8) and the Phase-C tag read both need a concrete address. Co-resolve with A3's plan-presence/track-count signal, since both are new ledger fields.

### A8 [suggestion]
**Certificate**: Challenge D2 — defer Fable
**Target**: Decision D2
**Challenge**: The deferral is safe and user-directed. The only residual is the subsume bookkeeping D2 itself flags: YTDB-1162 claims to subsume YTDB-1100, but with Fable deferred YTDB-1100 is only partially absorbed (its step-level-review reshape lands via D3/D4; its implementer-upgrade does not). Closing YTDB-1100 "as subsumed" at accept-time would silently drop the implementer-upgrade work.
**Evidence**: D2's own Knock-on note; Surprises log (`model: "opus"` hard-coded, no Fable wiring exists).
**Proposed fix**: Keep D2 as-is; the note is sufficient. At issue-close, do not close YTDB-1100 as fully subsumed — split out or re-scope its implementer-upgrade part. No artifact change needed.

### A9 [suggestion]
**Certificate**: Challenge D7 — cognitive-mode bugs/concurrency boundary
**Target**: Decision D7
**Challenge**: D7's cognitive-mode boundary is well-reasoned, but it is a *prompt-content* contract: the boundary only holds if `review-bugs` and `review-concurrency` agent prompts are rewritten in lockstep to encode "single-threaded sequential reasoning owns leaks/logic even inside locks" and "data race ⇒ concurrency only, bugs defers." The split also needs the backstop note ("concurrency triage gap here") wired into the `review-bugs` prompt, or the trigger-gap case (concurrency category not triaged on) silently loses concurrency coverage. The live `review-bugs-concurrency` prompt is one agent; splitting it is a content rewrite where a missed clause re-mixes the modes.
**Evidence**: `review-bugs-concurrency.md` (single agent, prefix `BC`); Surprises log (test side `review-test-concurrency`/`TX` is the split template); the unresolved finding-prefix Open Question for the split agents.
**Proposed fix**: No log change needed — D7's reasoning is sound. Flag for the design/implementation phase: the two new agent prompts must be authored together with the cognitive-mode clauses and the backstop verbatim, and the new finding prefixes resolved (the open prefix question), so the boundary the log defines actually lands in the agent contracts.
