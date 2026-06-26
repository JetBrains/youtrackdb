<!-- review-file schema §2.5 — adversarial, research-log scope, iter2 (verdict-producer) -->

## Manifest

```yaml
role: reviewer-adversarial
scope: research-log
phase: 1
iteration: 2
mode: verdict-producer
target: docs/adr/track-complexity-assessment-workflow-optimization/_workflow/research-log.md
prior_review: docs/adr/track-complexity-assessment-workflow-optimization/_workflow/reviews/research-log-adversarial-iter1.md
matched_categories: [Workflow machinery, Architecture / cross-component coordination]
verdict: PASS
prior_findings: 9
prior_verdicts:
  - {id: A1, sev: blocker,     verdict: VERIFIED,   reason: "revised D3 restores the live localized-versus-buried rule (bug-catcher at step, test baselines defer); roster adaptation to review-bugs/review-concurrency/review-test-quality is faithful to review-agent-selection.md:128-137"}
  - {id: A2, sev: blocker,     verdict: VERIFIED,   reason: "D10 adds a Phase-1-complete marker that distinguishes design+single steady state (marker SET) from full mid-authoring crash (marker UNSET); file presence alone could not, the ledger field can"}
  - {id: A3, sev: should-fix,  verdict: VERIFIED,   reason: "D10 names the replacement for tier=minimal: a plan-presence/track-count signal (tracks=N or plan=yes/no) decided at end of Step 4b; co-resolved with A7 as one schema delta"}
  - {id: A4, sev: should-fix,  verdict: VERIFIED,   reason: "D5 amended: missed reviewers run as ordinary per-review-type cap-3 passes appended to sub-step 3; reconciliation fires at most once per Phase A; bounded because the ceiling is high and the divergence comparison is not re-evaluated"}
  - {id: A5, sev: should-fix,  verdict: VERIFIED,   reason: "D6 amended: architecture-central tracks tag high via the Architecture HIGH trigger (risk-tagging.md:145-150, computed over planned work per D9) so they keep Risk+Adversarial; low-track Adversarial drop confirmed as user choice"}
  - {id: A6, sev: should-fix,  verdict: VERIFIED,   reason: "D9 resolves the taxonomy OQ into the Decision Log: tag computed over ## Plan of Work + ## Interfaces (content, not bare path list); 7 HIGH triggers drive the tag, 13 categories drive reviewer selection, mapped not merged"}
  - {id: A7, sev: should-fix,  verdict: VERIFIED,   reason: "D10 names the per-track reconciled-tag persistence home (ledger, written at A→C boundary) read by fresh Phase-C and Phase-4 adr predicate; ledger schema delta enumerated with touch list"}
  - {id: A8, sev: suggestion,  verdict: VERIFIED,   reason: "D2 knock-on revised: YTDB-1100 + YTDB-1056-P2 now fully out of scope (step reshape not adopted after D3 reversal), only YTDB-1056-P1 absorbed; close-as-subsumed bookkeeping corrected"}
  - {id: A9, sev: suggestion,  verdict: VERIFIED,   reason: "noted in Open Questions as a design/impl-phase item: split-agent finding prefixes still open, authored in lockstep with D7 cognitive-mode clauses + triage backstop"}
new_findings: 1
findings: 1
counts:
  blocker: 0
  should-fix: 0
  suggestion: 1
index:
  - {id: A10, sev: suggestion, anchor: "### A10 ", loc: "research-log.md D10 vs create-plan/SKILL.md:230-240", cert: "Assumption A10", basis: "D10 keys the design+single resume disambiguator on a NEW Phase-1-complete marker, but a parallel minimal resume branch already exists keyed on tier=minimal + plan/track-1.md; the design must reconcile the two single-track resume branches (collapse or coexist) since D1 unwelds single-track from no-design"}
evidence_base:
  challenges: 0
  violation_scenarios: 0
  assumption_tests: 1
```

## Evidence base

The iter-1 certificates (7 challenges, 2 assumption tests) produced findings A1–A9.
This verdict-producer pass re-grounds each against the **revised** log (D1–D10) and the
live `.claude/` files, then raises any new finding the revisions introduced. One new
assumption test is added (A10).

### Re-grounding notes (per prior finding)

- **A1 (D3 reversal).** Confirmed against `review-agent-selection.md:122-137`
  (§Step-level vs track-level routing → Baseline group): the live rule runs
  `review-bugs-concurrency` at a multi-step high step (its "bug, logic-error,
  resource-leak, and null-safety defects that get buried once a step's diff
  folds into the cumulative diff" must see each step in isolation) and the two
  test baselines "read whole-suite quality off the cumulative diff identically,
  so the step adds nothing." Revised D3 (`research-log.md:96-129`) keeps this
  logic verbatim and only re-labels the roster: the combined burial role →
  `review-bugs` (always) + `review-concurrency` (on the concurrency category, a
  race in the step diff being buriable too); the merged `review-test-quality`
  inherits the deferred-to-track-pass role. The single-step-high override
  ("full track-pass-equivalent selection at the step") is the live
  `review-agent-selection.md` "Single-step-high override (read first)" block,
  cited unchanged. D3 no longer inverts the live rule. **VERIFIED.**

- **A2 (resume collision).** The live Step-1c branch
  (`create-plan/SKILL.md:174-218`) disambiguates "design exists / plan absent"
  only as `full`-tier crash-recovery, split on `git log`/`git status`
  (committed+clean ⇒ resume 4b; uncommitted/dirty ⇒ resume 4a). It carries **no
  signal** for a design+single *steady* state — file presence is identical.
  D10 (`research-log.md:351-369`) adds a **Phase-1-complete marker**: design+single
  steady state = marker SET (Phase 1 finished, the single track is the final
  state); full mid-authoring crash = marker UNSET (Phase 1 never completed).
  The Step-1c router "gains this ledger check." This is a real on-disk
  disambiguator that file presence alone cannot supply, and it is distinct from
  the existing git-state probe (which separates frozen-design from
  unfrozen-design, not steady-state from crash). **VERIFIED.**

- **A3 (tier=minimal trigger replacement).** The live precheck
  (`workflow-startup-precheck.sh:1932-1966`, `determine_state_from_ledger`)
  defaults the active track to `1` for the "single-track `minimal` tier whose
  ledger names no track," and reads `LEDGER_TIER`. D10 names the replacement: a
  **plan-presence / track-count signal** (`tracks=N` or `plan=yes/no`) decided
  at end of Step 4b, replacing the `tier=minimal` trigger, co-resolved with A7
  as one schema delta. The resume signal that replaces `tier=minimal` is named.
  **VERIFIED.**

- **A4 (reconciliation re-entry / cap).** The live loop
  (`track-review.md` §Review iteration protocol) is "Max 3 iterations per review
  type, findings cumulative"; decomposition (§Step Decomposition) is the
  downstream sub-step with no reviewer re-entry. D5's amendment
  (`research-log.md:161-171`) states the missed reviewers "run as ordinary
  Phase-A review passes — each its own review type under the existing
  per-review-type cap-3 on sub-step 3"; reconciliation "fires at most once per
  Phase A"; the divergence comparison "is not re-evaluated against a second
  upward miss" because the panel is already at the `high` ceiling. The
  decompose↔re-review ping-pong is bounded. **VERIFIED.**

- **A5 (architecture-centrality vs file-set complexity).** The live Risk gate
  (`track-review.md` Risk-gating-characteristics table) runs Risk + Adversarial
  on "major architectural decisions." The Architecture HIGH trigger exists in
  `risk-tagging.md:145-150` ("Introduces a new abstraction layer or moves a
  load-bearing one"; "Adds a new SPI registration"). D6 (`research-log.md:206-212`)
  now routes the architecture-central case through that HIGH trigger —
  evaluated over the track's *planned work* per D9, not a path list — so the
  track tags `high` and keeps Risk + Adversarial; the risk-tag override is the
  backstop. The low-track Adversarial drop is confirmed as a user choice. The
  collapse A5 flagged (file-set complexity ≠ architectural centrality) is
  resolved by computing the tag over content (D9), which lets the Architecture
  content predicate fire. **VERIFIED.**

- **A6 (track tag computable from a file set).** D9 (`research-log.md:322-349`)
  resolves the load-bearing taxonomy OQ into the Decision Log: the tag is
  computed over the track's `## Plan of Work` (prose edit sequence) plus
  `## Interfaces and Dependencies` (file set) — **content, not a bare path
  list** — exactly because the HIGH triggers are verb-on-change content
  predicates (`risk-tagging.md:104-179`). The 7 HIGH triggers drive the tag; the
  13 `code-review` categories drive reviewer selection; "separate, mapped not
  merged." The OQ is moved from `## Open Questions` to RESOLVED-by-D9. **VERIFIED.**

- **A7 (tag persistence home + ledger schema delta).** D10 (`research-log.md:370-373`)
  adds the **per-track reconciled-tag home** (the `max(steps)` value per track,
  written at the A→C boundary) so a fresh Phase-C session reads it for rigor
  (D6) and the Phase-4 `adr.md` predicate reads it for "∃ track ≥ medium" (D8).
  The schema delta is enumerated (remove `tier=`; add `design_gate=`,
  plan/track-count, Phase-1-complete marker, per-track tag home) with a touch
  list (`workflow-startup-precheck.sh` key set + validation + its 2 test files,
  `determine_state`, the Step-1c router). The persistence address the adr
  predicate and Phase-C read both dereference is now concrete. **VERIFIED.**

- **A8 (subsume bookkeeping).** D2's knock-on (`research-log.md:82-94`) is
  revised past what A8 asked for: because the D3 reversal drops the step-level
  reshape entirely, YTDB-1100 is now *fully* out of scope (neither implementer
  upgrade nor step reshape lands) and YTDB-1056-P2 is not adopted; only
  YTDB-1056-P1 (the test-baseline merge) is absorbed. At issue-close "do not
  close YTDB-1100 or YTDB-1056-P2 as subsumed." The partial-subsume hazard A8
  raised is eliminated. **VERIFIED.**

- **A9 (split-agent authoring contract).** Recorded as a design/impl-phase item
  in `## Open Questions` (`research-log.md:519-528`): the split-agent finding
  prefixes are still open ("decide while authoring the agent files"); the two
  agents are authored "in lockstep with the D7 cognitive-mode clauses + the
  triage backstop verbatim." A9 was a suggestion flagged for the agent-authoring
  phase, and the log carries it there. **VERIFIED.**

### ASSUMPTION CHALLENGES (new)

#### Assumption test: D10's design+single resume disambiguator and the existing minimal-resume branch are the only two single-track resume branches, and they do not conflict
- **Claim**: D10 adds a Phase-1-complete marker so Step 1c distinguishes the
  new `design + single-track` steady state from a `full`-tier mid-authoring
  crash. The implicit assumption is that this is the only single-track resume
  ambiguity D1's unwelding introduces.
- **Stress scenario**: D1 unwelds "single-track ⇒ no plan" from "no design," so
  there are now **two** single-track-no-plan on-disk shapes: (i) design=no,
  single-track (the old `minimal`) and (ii) design=yes, single-track (the new
  D8 cell). The live Step-1c router already carries a dedicated branch for
  shape (i) — `create-plan/SKILL.md:230-240` "**`minimal` resume — ledger
  present with `tier=minimal`, `plan/track-1.md` present, no
  `implementation-plan.md`, no `design.md`**" — keyed on `tier=minimal`. D10
  removes `tier=` and re-keys the no-plan/single-track signal onto track-count
  (A3), and adds a Phase-1-complete marker for shape (ii). But D10 does not
  state whether shape (i) and shape (ii) collapse into one re-keyed single-track
  resume branch (distinguished by `design_gate=yes/no`) or stay two branches.
  The difference is whether the Step-1c router gets one re-keyed branch or two.
- **Code evidence**: `create-plan/SKILL.md:230-240` (the live dedicated
  `tier=minimal` single-track resume branch, separate from the design-exists
  crash-recovery branch at 174-218); D10's touch list names "the Step-1c router
  in `create-plan/SKILL.md`" once, without distinguishing the two single-track
  shapes.
- **Verdict**: HOLDS (barely). D10 supplies every *field* the design needs
  (track-count signal, `design_gate`, Phase-1-complete marker), so the
  reconciliation is mechanically possible and is a Phase-1 design-detail, not a
  research-log gap. This is a `suggestion`, not a gate: the resolution is a
  matter for the design's Step-1c rewrite, and the log already lists the router
  in D10's touch list. Flagging it so the design phase does not re-key shape (i)
  and add shape (ii) as if they were independent and leave a third ambiguous
  case (e.g., `design_gate=yes` + track-count read failing) unrouted.

## Findings

### A10 [suggestion]
**Certificate**: Assumption test — D10's resume disambiguator and the existing minimal-resume branch are the only two single-track resume branches and do not conflict
**Target**: Decision D10 (resume disambiguation) + Decision D1 (single-track unwelding)
**Challenge**: D1 unwelds single-track from no-design, producing two single-track-no-plan on-disk shapes: design=no (the old `minimal`, served today by the dedicated `tier=minimal` resume branch at `create-plan/SKILL.md:230-240`) and design=yes (the new D8 cell, served by D10's Phase-1-complete marker). D10 supplies the fields to route both but does not state whether the two single-track shapes collapse into one re-keyed branch (distinguished by `design_gate`) or stay two — a Step-1c structural choice the design must make so the re-key of shape (i) and the addition of shape (ii) do not leave a single-track case unrouted.
**Evidence**: `create-plan/SKILL.md:230-240` (live `tier=minimal` single-track resume branch, distinct from the design-exists crash-recovery branch at 174-218); D10's touch list names the Step-1c router once without distinguishing the two single-track shapes; `research-log.md:351-369` (D10 schema delta).
**Proposed fix**: No log change required — D10 already names every field (track-count signal, `design_gate`, Phase-1-complete marker) and lists the Step-1c router in its touch list. Flag for the design phase: the Step-1c rewrite must reconcile the existing `tier=minimal` single-track branch with the new design+single branch into a coherent re-keyed routing (one branch on `design_gate` or two), confirming no single-track shape falls through. Suggestion severity: the decision is mechanically supplied; only its design-phase rendering is open.
