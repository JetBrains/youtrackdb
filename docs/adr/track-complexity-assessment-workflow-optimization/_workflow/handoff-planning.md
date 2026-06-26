# Handoff: Phase 1 — YTDB-1162 Step 4a done; Step 4b (plan + tracks) next

**Paused:** 2026-06-26
**Phase:** 1 (Step 4a design authoring COMPLETE; next = Step 4b plan/track derivation)
**Context level at pause:** info (~37%) — **proactive** checkpoint before the
Step-4b multi-round dual-clean track-authoring loop, not a forced warning/critical
pause. Step 4a's loop consumed ~19%; Step 4b is comparable, so a fresh session
keeps the durable track files out of critical-context degradation.
**Branch:** track-complexity-assessment-workflow-optimization
**HEAD:** cb49a824e5 "Add initial design"
**Unpushed:** 0 — branch pushed; draft PR #1175 open
(https://github.com/JetBrains/youtrackdb/pull/1175).

## What I was investigating

Implementing **YTDB-1162** — replace the whole-change `tier` enum with three
unbundled axes (design gate, track-count→plan, per-track complexity tag). Phase 0
research + the Phase 0→1 adversarial gate are COMPLETE (gate PASSED iter2). Tier
`full` confirmed; matched HIGH categories = Workflow machinery + Architecture /
cross-component coordination.

## Already ruled out / done

- **Step 4a COMPLETE.** `design.md` authored via `edit-design` (`phase1-creation`
  dual-clean loop), reviewed, frozen, committed at `cb49a824e5` ("Add initial
  design"), pushed. 808 lines, single file (no mechanics companion), stamped
  `a1311db00ca6d233d6c5883e0e29c5a09f4b4280`.
  - Review outcome: mechanical PASS, absorption PASS (D1–D10 covered both ways),
    comprehension gate **PASS**. Readability hit the designed never-clean
    should-fix tail (12→7→8 borderline nits over the 3 budgeted rounds + a
    terminal cleanup pass; no blockers). Full record in
    `_workflow/design-mutations.md` (Mutation 1).
  - One comprehension finding was a **false positive** and NOT applied: the
    footer heading `### Decisions & invariants` is the design-document-rules.md
    D11 rename (the mechanical checker accepts it); reverting to `### References`
    would regress. Do NOT "fix" it.
- **Draft PR #1175 open** with Motivation + Status + a placeholder `## Plan`
  ("track decomposition forthcoming, Step 4b"). After Step 4b derives the
  tracks, **update the PR `## Plan` section with the real one-line-per-track
  list** (keep PR description in sync — the squash message is built from it).
- Handoff `3b34c78eca` (Resume Phase 1) already resolved the prior research
  handoff; do NOT re-resolve it.

## Most promising lead / current state — Step 4b is next

Derive the thinned derived-mirror `implementation-plan.md` (Component Map +
Checklist only) plus N `plan/track-N.md` files **from the frozen
`design.md`**, via the Step-4b dual-clean loop (`design-author target=tracks`
per-round + `readability-auditor` one-spawn-per-track-file + `absorption-check`
+ de-warmed `comprehension-review` gate), then seed the phase ledger, then the
second commit. Read `planning.md` (deferred) for the track-sizing + plan-shape
rules before decomposing.

**Track decomposition — first-cut grouping only (settle it in Step 4b per
planning.md sizing; do NOT treat as fixed).** The change touches ~30 files
(research log `## Surprises & Discoveries` lists them). Natural seams:
- **Persistence + resume foundation:** the ledger schema delta
  (`workflow-startup-precheck.sh` `--append-ledger` key set + validation) + its
  2 test files + `determine_state` + the Step-1c router in `create-plan/SKILL.md`
  (D10). These are the executable `.claude/scripts/**` edits that force §1.7
  staging.
- **Reviewer roster split/merge:** the agent files (split
  `review-bugs-concurrency`→`review-bugs`+`review-concurrency` with the D7
  cognitive-mode clauses + triage backstop verbatim; merge the two test
  reviewers→`review-test-quality` keeping `TB`+`TC`) + the selection logic in
  `code-review/SKILL.md`, `review-agent-selection.md`, the `fix-ci-failure`
  mirror, `finding-synthesis-recipe.md`. **Decide the two new finding prefixes
  here** (Phase-B detail per the research log; `BC` retired).
- **Tier→tag re-keying + reconciliation + artifacts:** the prose re-keying
  across `track-review.md` (panel selection + Phase-A reconciliation D5),
  `conventions.md`/`conventions-execution.md`, `planning.md`, `research.md`,
  `workflow.md`, `consistency-review.md`, `structural-review.md`,
  `inline-replanning.md`, `plan-slim-rendering.md`, and the Phase-4 carrier
  table in `create-final-design.md` (D8 artifact derivation, the load-bearing
  hub).
Settle the actual track count/boundaries by file footprint per planning.md
(maximize-bundled, ~12–25 soft bounds, written justification if out of bounds).

## Open questions (Phase-B rendering details, NOT to hard-decide in the plan)

- **A10 — Step-1c branch structure** (collapse the two single-track resume
  branches into one `design_gate`-keyed branch vs keep separate). The design
  states the routing *contract* (D10 fields + Phase-1-complete marker resolving
  the A2 collision); the branch rendering is a Phase-B choice.
- **Split-agent finding prefixes** for `review-bugs` / `review-concurrency`
  (`review-test-quality` keeps `TB`+`TC`).

## Resume notes

- **Do NOT redo:** Phase 0 research; the tier classification (`full`); the
  adversarial gate (PASSED iter2); **Step 4a (`design.md` is frozen + committed
  — never re-author it, design-document-rules.md Rule 15)**.
- **Next action on resume:** read `planning.md`, then run Step 4b — derive the
  thinned plan + N track files from the frozen `design.md` through the Step-4b
  dual-clean loop; seed the phase ledger
  (`--phase 0 --tier full --categories "Workflow machinery,Architecture /
  cross-component coordination" --s17 <staging-mode>`); commit "Add initial
  implementation plan" (the second `full`-tier commit; upstream + PR already
  exist, so skip `-u` and the PR-open sub-steps); **update PR #1175 `## Plan`
  with the track list**; end the session.
- **§1.7 STAGING is mandatory** — the change edits `.claude/scripts/` (ledger
  schema) and reviewer-selection dispatch (executable/behavioral), so no
  prose-only opt-out. All `.claude/**` *implementation* edits (Phase B) stage
  under `_workflow/staged-workflow/.claude/`. (Note: `design.md`,
  `implementation-plan.md`, and `plan/track-N.md` live in `_workflow/` and are
  NOT staged — staging applies to the live-workflow `.claude/**` edits the
  tracks implement, not to the planning artifacts themselves.)
- **Belt-and-suspenders:** even without this handoff, `/create-plan` Step 1c
  would auto-resume into Step 4b (design.md committed + clean, no
  implementation-plan.md). This handoff is authoritative and carries the
  above notes; Step 1a runs before Step 1c.
