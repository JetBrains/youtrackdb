<!-- workflow-sha: f97512c02f4dbaaf66c7382397907580fd54391b -->
# Track 2: Read-side staged-read caveat (YTDB-1038)

## Purpose / Big Picture
After this track, every Phase 2, Phase A, and Phase B/C review and gate prompt on a workflow-modifying plan resolves its workflow-document reads through `§1.7(d)` precedence, so an agent stops reporting phantom mismatches against develop's version of a rule the branch already rewrote. This track also amends `§1.7(d)` to bring review agents into the staged-first precedence scope it currently restricts to the implementer, so the rule the caveat invokes actually covers reviewers.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Every review and gate prompt names the live `.claude/...` path, so on a
workflow-modifying plan an agent reads develop's version of a rule the branch
already rewrote and reports a phantom mismatch. A marker-gated caveat in all
nine prompts routes reads through `§1.7(d)` precedence (staged copy when
present, else live).

## Progress
- [x] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion
- [x] 2026-06-02T03:55Z [ctx=info] Review + decomposition complete
- [x] 2026-06-02T04:21Z [ctx=safe] Step 1 complete (commit 0894418)

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->
- §1.7 reviewer-annotation gap (Step 1 dim review, declined as out-of-scope for the surgical edit): several §1.7 subsections address reviewers in prose yet annotate themselves with no reviewer role (§1.7(d) after this amendment; §1.7(b) already), so a reviewer following the §1.8 TOC read-filter cannot reach the rule that addresses it. No behavioral impact (reviewers act via the prompt caveat), but a future §1.7-wide consistency sweep should decide whether these subsections gain reviewer roles. See Episodes §Step 1.

## Decision Log
<!-- Continuous-log. Execution-time decisions: inline-replan choices,
scope-downs, dependency reveals, gate-override reasons. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->
- Step 1 review synthesis: declined WC1/WI2 (reviewer-less §1.7(d) annotation) and WI1 (final-designer/migrator not enumerated) as out-of-scope for the surgical §1.7(d) edit; applied none. WC1/WI2 is design-consistent (reviewers routed via the prompt caveat per D3) and shares the §1.7(b) pattern, so a §1.7(d)-only annotation widen would create asymmetry. WI1 conflates section readers with precedence consumers. Neither changes the §1.7(d) edit or its outcome. See Episodes §Step 1.

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->
- [x] Technical: PASS at iteration 2 (3 findings: T1 should-fix + T2/T3
  suggestions, all accepted and applied to the track description). T1 (surgical
  guidance for the `§1.7(d)` amendment) was the load-bearing finding, raised
  independently by Adversarial as A1. Risk review skipped (Moderate track with
  no critical-path or performance characteristic, per the complexity table).
- [x] Adversarial: PASS at iteration 2 (5 findings: A1 [= T1] and A2 should-fix
  plus A3/A5 accepted and applied; A4 should-fix valid but out of this track's
  edit scope, surfaced to the user). A2 added insertion-anchor guidance for the
  S2 parallel blocks; A3/A5 reframed S3 as byte-identical wording with a
  per-site binding note (incl. the indirect gate-check binding). A4: the plan's
  `### Constraints` cites `§1.7(h)` as a "self-application carve-out", but live
  `§1.7(h)` is titled "Forward-applicable to future workflow-modifying
  branches"; the mismatch lives in the plan Constraints and `§1.7(h)`, neither
  in this track's edit scope (`§1.7(d)` plus the nine prompts), so it was not
  auto-fixed. The gate confirmed leaving it touches neither the `§1.7(d)` edit
  nor its outcome.
- Self-application carve-out (§1.7(h)): the technical, adversarial, and
  gate-verification sub-agents all ran with hand-injected staging-aware reads
  (§1.7(d) precedence: Track 1 deliverables from the staged subtree,
  current-state premises about this track's target files from live) and
  prose-criteria (references verified as file paths and §-anchors via
  Read+Grep, not PSI), since the live machinery does not yet carry this
  branch's YTDB-1046 / YTDB-1038 fixes.

## Context and Orientation

The caveat reaches nine prompt sites across the three review layers. Every one
of them today hands the spawned agent the live `.claude/...` path:

- **Phase B/C dimensional review** — the canonical context block in
  `step-implementation.md` sub-step 4(a), its parallel copy in
  `track-code-review.md`, and `dimensional-review-gate-check.md`.
- **Phase 2 plan review** — `consistency-review.md` and `structural-review.md`.
- **Phase A track review** — `technical-review.md`, `risk-review.md`,
  `adversarial-review.md`, and `review-gate-verification.md`.

Non-obvious facts that shape the work:
- The two Phase B/C context blocks (`step-implementation.md` sub-step 4(a) and
  `track-code-review.md`) are parallel copies, not a shared include. The
  caveat must land in both with matching meaning, or a Phase C review behaves
  differently from its Phase B counterpart (S2).
- `§1.7(d)` reads precedence is the rule the caveat invokes: resolve a
  workflow file to its staged copy under `_workflow/staged-workflow/` when one
  exists, and to the live file otherwise. As written today `§1.7(d)` scopes
  that precedence to the implementer's per-spawn read site and explicitly
  excludes reviewers, listing "reviewers loading a workflow file from the
  worktree" among the consumers that keep reading live paths unchanged, with
  the now-false rationale that no such consumer has a staged copy to read. This track amends `§1.7(d)` to bring
  review agents on a workflow-modifying plan into the precedence scope and to
  drop that rationale, so the caveat invokes a rule that covers reviewers
  rather than one that excludes them.
- The marker is visible from the slim plan snapshot: `render-slim-plan.py`
  copies the plan's strategic header `pre` block unchanged and filters only
  the track checklist, so `### Constraints` (and the `§1.7(b)` marker inside
  it) survives into the snapshot the agent already loads. Verified this
  session.

Concrete deliverables: a short caveat block inside the fenced prompt body of
the two context blocks, plus a one-line mirror of the same caveat in each of
the seven other prompts.

## Plan of Work

The approach is one marker-gated caveat, byte-uniform across nine prompts
(D2, D3, S3), with the two context blocks treated as a parallel pair (S2). It
rests on a one-clause amendment to `§1.7(d)` so the precedence rule the caveat
invokes actually names review agents.

1. Amend `conventions.md §1.7(d)` surgically. The live rule lists four
   consumers that "keep reading live paths unchanged" under one shared
   rationale ("None of those consumers has a staged copy to read") and one
   shared scope clause ("applies to the implementer's per-spawn read site
   only"): the drift gate, the plan-slim renderer, sibling-track plan
   citations, and reviewers. Remove only the reviewer entry. Keep the drift
   gate, plan-slim renderer, and sibling-track citations excluded with their
   "keep reading live" framing intact: they genuinely have no staged copy (the
   drift gate compares live against develop, the renderer reads the plan, and
   sibling-track citations live under `docs/adr/**`), and this track relies on
   that (the renderer must keep surfacing `### Constraints`). Replace the
   shared blanket rationale so the three remaining consumers stay justified
   while review agents on a workflow-modifying plan now resolve staged-first,
   and keep a scope statement that still covers the implementer's per-spawn
   read site. Phrase the implementer clause and the reviewer clause as
   independent positive statements, not one defined by excluding the other.
   The caveat in the prompts then invokes a rule that covers reviewers.
2. Add the caveat block to the two canonical context blocks (the fenced prompt
   body of `step-implementation.md` sub-step 4(a) and its parallel copy in
   `track-code-review.md`) with matching meaning (S2). The two blocks are
   hand-maintained copies that already differ in section order and body, so
   land the caveat at the same logical anchor in both (relative to a section
   both carry, such as the path or tooling guidance) rather than the same
   absolute line. S2 parity across the pair is re-checked at Phase C by the
   `review-workflow-consistency` reviewer, the same reviewer that enforces the
   S1 selection mirror, so the pair is not unguarded.
3. Add the one-line mirror caveat to the seven other prompts:
   `dimensional-review-gate-check.md`, `consistency-review.md`,
   `structural-review.md`, `technical-review.md`, `risk-review.md`,
   `adversarial-review.md`, and `review-gate-verification.md`. Each gate prompt
   already carries an `Inputs:` block (the inputs differ by prompt:
   `review-gate-verification.md` lists the plan, track file, and codebase root,
   while `dimensional-review-gate-check.md` lists the diff, files, slim-plan,
   and track-file temp paths); the caveat rides next to that input block.
4. Verify uniformity (S3): the caveat sentence is byte-identical (the same
   wording) across all nine prompts. S3 is a wording-uniformity property,
   which is the checkable one; the caveat's binding referent still differs by
   site, which is expected. At the two context blocks and the three Phase A
   criteria prompts it scopes the agent's active reads; at the two Phase 2
   plan-review prompts it covers any `.claude/workflow/**` or `.claude/skills/**`
   read made while verifying plan references (the rendered plan, track, and
   design inputs are `docs/adr/**`, never staged-workflow); at the gate-check
   it binds indirectly, on any workflow file the agent re-opens while
   re-checking a finding, not on the temp-file inputs the prompt lists.

Caveat content (the same across all nine sites): when the plan's
`### Constraints` carries the `§1.7(b)` marker, resolve every read of a
`.claude/workflow/**` or `.claude/skills/**` file through `§1.7(d)`, taking the
staged copy under `_workflow/staged-workflow/` when present and the live file
otherwise.

Ordering and invariants:
- The caveat self-gates on the marker (D2), so it is inert on ordinary plans
  and at a fresh plan review where no staged copy exists. No orchestrator
  hand-injection in the steady state.
- It rides inside the fenced prompt body, not as a document section, so no TOC
  or per-section annotation churn in the host files (D3).

## Concrete Steps

1. Amend `conventions.md §1.7(d)` surgically per Plan of Work step 1: remove only the reviewer entry from the four-consumer "keep reading live" clause, bring review agents on a workflow-modifying plan into staged-first precedence, and replace the shared rationale so the drift gate, plan-slim renderer, and sibling-track citations stay justified (staged under `_workflow/staged-workflow/`). — risk: high (architecture: `§1.7(d)` is a load-bearing convention in the plan's Component Map / Integration Points, read by the implementer, drift gate, renderer, and reviewers; per finding T1/A1 a non-surgical edit silently breaks the three live-read consumers and no automated check guards it)  [x] commit: 0894418
2. Add the caveat block to the two parallel S2 context blocks (`step-implementation.md` sub-step 4(a) and `track-code-review.md`) at the same logical anchor relative to the shared path/tooling guidance, with matching meaning. — risk: medium (override: workflow-machinery multi-file edit to the two parallel context blocks that drive every Phase B/C dimensional review's read behavior; S2 parity is the risk; no HIGH trigger, additive and self-gating, staged)  [ ]
3. Mirror the one-line caveat into the four Phase A track-review prompts (`technical-review.md`, `risk-review.md`, `adversarial-review.md`, `review-gate-verification.md`); this satisfies Track 3's dependency that the caveat be present in technical/risk/adversarial. — risk: low (default: byte-identical one-line caveat mirrors, additive prose, S3-checkable)  [ ]
4. Mirror the one-line caveat into the gate-check and the two Phase 2 plan-review prompts (`dimensional-review-gate-check.md`, `consistency-review.md`, `structural-review.md`), then verify S3 byte-uniformity of the caveat across all nine sites. — risk: low (default: byte-identical one-line caveat mirrors plus the cross-site S3 verification; additive prose)  [ ]

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step, identified by step number + commit SHA. Empty at
Phase 1; Phase A does not populate. -->

### Step 1 — commit 0894418, 2026-06-02T04:21Z [ctx=safe]
**What was done:** Copied the live `conventions.md` into the staged subtree verbatim (first touch, §1.7(e)), then surgically amended the §1.7(d) reads-precedence closing paragraph there. Dropped the reviewer entry from the four-consumer "keep reading live" list, replaced the stale shared rationale ("None of those consumers has a staged copy to read") with a per-consumer rationale for the three remaining live-read consumers (drift gate vs `develop`, plan-slim renderer vs `implementation-plan.md`, sibling-track citations under `docs/adr/**`), and added a paragraph bringing review agents on a workflow-modifying plan into staged-first precedence. The implementer clause and the reviewer clause now read as independent positive statements. The §1.7(d) change is the staged copy's only diff against live (32 lines). Docs-only: no tests, no Spotless.
**What was discovered:** The risk:high dimensional review (4 workflow agents: consistency, instruction-completeness, context-budget, writing-style) returned PASS at iteration 1, zero blockers, three findings all declined on merit. WC1/WI2 (should-fix, raised by two agents): §1.7(d)'s §1.8 annotation and TOC row carry no reviewer role, so a reviewer following the TOC read-filter would skip the rule the amended prose now addresses to it. Declined: the amendment routes reviewers through the self-contained marker-gated prompt caveat (D3), not a direct §1.7(d) read (its closing sentence says so), and §1.7(b) carries the same pattern (reviewers in prose under `roles=orchestrator,implementer,planner`), so widening only §1.7(d) would introduce asymmetry; the §1.7-wide reviewer-annotation question is a separate consistency sweep. WI1 (should-fix): the closed consumer enumeration omits declared readers final-designer and migrator. Declined: section readers and precedence consumers are different axes (the orchestrator is a declared §1.7(d) reader never in the consumer list), and both roles read live by default before and after, so no behavior changed. WS1 (suggestion): an "is what routes" cleft; declined because the emphasis on the caveat-as-router reinforces the reviewer-routing point. The context-budget agent noted the expected rule_1 staged-residue (the verbatim-copied line 1 carries no `workflow-sha` stamp), which clears at Phase 4 promotion.
**Key files:** `docs/adr/ytdb-1038-review-gate-for-workflow/_workflow/staged-workflow/.claude/workflow/conventions.md` (new staged copy; §1.7(d) amended).
**Critical context:** Steps 3-4 add the marker-gated read caveat that the new §1.7(d) paragraph names. Keep that caveat wording consistent with this reference: it routes a reviewer's read through §1.7(d), gated on the §1.7(b) marker.

## Validation and Acceptance

Track-level behavioral acceptance:
- On a plan whose `### Constraints` carries the `§1.7(b)` marker, a review
  agent spawned with any of the nine prompts resolves a `.claude/workflow/**`
  or `.claude/skills/**` read to the staged copy when present, else live.
- On a plan without the marker, the caveat is inert: the agent reads the live
  path, unchanged behavior.
- At a fresh State-0 plan review and at Phase A of the first track, no staged
  copy exists yet, so `§1.7(d)` resolves to live and the caveat changes
  nothing. It bites on a re-run `/review-plan` mid-execution, a later track's
  Phase A, and Phase B/C once staging has produced copies.
- The two context blocks carry the same caveat (S2); all nine read uniformly
  (S3).
- `§1.7(d)` names review agents on a workflow-modifying plan as in-scope
  consumers of the staged-first precedence; the implementer-only scope clause
  and the stale no-staged-copy rationale no longer exclude reviewers.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery

Every step is a single commit of additive edits to staged files under
`_workflow/staged-workflow/.claude/workflow/` (the live tree is untouched until
Phase 4). Recovery for any step is the standard Phase B revert: `git reset
--hard HEAD` discards the uncommitted attempt, and re-running re-applies the
same staged edit, so each step is idempotent on re-run.

Per-step notes:
- **Step 1** copies the live `conventions.md` into the staged subtree on first
  touch (per `§1.7(e)`), then edits `§1.7(d)` there. If the staged copy already
  exists from a prior attempt, edit it in place rather than re-copying. The
  surgical edit is verified by reading the staged `§1.7(d)` back and confirming
  the three live-read consumers remain excluded with their rationale.
- **Steps 2-4** are additive caveat insertions; re-running an interrupted step
  re-inserts the same caveat at the same anchor (check for an existing caveat
  before inserting to stay idempotent). Step 4's S3 cross-check is a read-only
  `grep`/`diff` over the nine staged sites and has no side effect.

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't
belong to one specific step. Per-step episode content lives in
`## Episodes` above. Often empty. -->

## Interfaces and Dependencies

In-scope files (the nine prompt sites plus the `§1.7(d)` amendment, all under `.claude/workflow/**`):
- `step-implementation.md` — sub-step 4(a) canonical context block.
- `track-code-review.md` — the parallel copy of that context block.
- `dimensional-review-gate-check.md` — gate prompt input block.
- `prompts/consistency-review.md`, `prompts/structural-review.md` — Phase 2
  plan review prompts. Target the `prompts/` sub-agent prompt for
  structural-review, not the `.claude/workflow/structural-review.md`
  orchestration driver of the same name (consistency-review exists only under
  `prompts/`, so it carries no ambiguity).
- `technical-review.md`, `risk-review.md`, `adversarial-review.md`,
  `review-gate-verification.md` — Phase A track review prompts.
- `conventions.md` — `§1.7(d)` reads precedence: remove only the reviewer entry
  from the four-consumer "keep reading live" clause and bring review agents on
  a workflow-modifying plan into staged-first precedence. Keep the drift gate,
  plan-slim renderer, and sibling-track citations excluded; their live-read
  guarantees (this track relies on the renderer surfacing `### Constraints`)
  must survive the edit.

(Phase A decomposition resolves the exact directory of each file under
`.claude/workflow/**`; the design names them by filename.)

Out-of-scope:
- The `§1.7(b)` marker definition in `conventions.md` — relied on, not edited.
  (`§1.7(d)` is now in scope — see the in-scope list above.)
- `render-slim-plan.py` — relied on to surface `### Constraints`; not edited.

Inter-track dependencies:
- No upstream dependency.
- **Track 3 depends on this track**: the Phase A criteria addendum references
  the read caveat in `technical-review.md`, `risk-review.md`, and
  `adversarial-review.md`, so the caveat must land in those three files first.

Staging: per `§1.7`, the nine prompt edits and the `§1.7(d)` amendment all
route through `docs/adr/<dir>/_workflow/staged-workflow/.claude/workflow/...`;
the live files stay at develop's state until Phase 4 promotion.

Full design: design.md §"Read-side staging awareness".

## Base commit

81bc15de39468e00353325955e7c55090b719bd7
