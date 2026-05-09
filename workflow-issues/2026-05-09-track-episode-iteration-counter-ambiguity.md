---
severity: low
phase: phase-c
source-session: 2026-05-09 /execute-tracks unit-test-coverage
---

# Track-episode prose routinely cites "iteration N" counters; ephemeral-identifier rule is ambiguous on the carve-out

## Symptom

When collapsing the Track 21 plan entry, the orchestrator wrote
the `**Track episode:**` block citing "Phase C iteration 1",
"iteration 2", and "iteration 3" multiple times to discriminate
between the three `Review fix:` commits and convey the strategic
narrative ("iter-1 closed N findings, iter-2 reverted a regression
plus added M findings, iter-3 closed a residual"). The episode
also cites the three commit SHAs (`19857464e5`, `ff10bf63f5`,
`42f45cbc90`).

The earlier reference Track 20 episode follows the same pattern —
it cites "Track 20 Phase C iteration 1", "iteration 2", "iter
gate-checks", "iter-2 implementer", etc. throughout its episode.

Per `.claude/workflow/ephemeral-identifier-rule.md`'s forbidden
list (referenced from `conventions-execution.md` §2.3):

> Forbidden in durable content:
> - …
> - Review-loop iteration counters (`iteration 1`, `round 2`)
> - …

Track episodes are durable content (they survive the squash-merge
into `develop` after Phase 4 collapse). Yet every recent track
episode visibly violates the rule, and there is no recipe in the
workflow docs for how to phrase a multi-iteration review-fix
narrative without naming the iterations.

The orchestrator considered three workarounds, none satisfying:

- **Cite only the commit SHAs without iteration labels.** The reader
  has to git-log + git-show each SHA to recover the order and
  significance — the strategic-summary purpose is defeated.
- **Cite "first / second / third Review fix commit".** Ordinal
  English avoids the `iteration N` literal but conveys the same
  ephemeral information.
- **Drop the iteration narrative entirely.** Loses real strategic
  content (e.g., "iter-2 reverted a regression that iter-1
  introduced" is a key cross-track signal for future track planners).

## Reproduction context

- Phase: phase-c
- Workflow doc(s) involved:
  - `.claude/workflow/track-code-review.md` § "Track Completion"
    step 4 — track-episode template + "always keep / always drop"
    rules
  - `.claude/workflow/ephemeral-identifier-rule.md` (full rule)
  - `.claude/workflow/conventions-execution.md` §2.3 (rule stub)
- Tool / sub-agent involved: orchestrator collapse step
- ADR directory at the time: `docs/adr/unit-test-coverage/`
- Trigger condition: any Phase C track that needed ≥ 2 review-fix
  iterations (i.e., where the iteration narrative is materially
  different from "one Review fix commit closed everything").

## Why it's a problem

Two compounding concerns:

1. **The pattern is universal across recent tracks.** Track 20,
   Track 19, Track 18 all collapse with iteration labels in the
   episode (visible in `implementation-plan.md`). If the rule
   applies to track episodes, every recent track is non-compliant
   and Phase 4's `design-final.md` / `adr.md` synthesis will
   inherit the violations. If the rule does not apply, the
   `ephemeral-identifier-rule.md` forbidden list is overstated.

2. **The orchestrator currently has no recipe.** The
   "How to rewrite a forbidden reference" section of
   `ephemeral-identifier-rule.md` covers Track / Step / finding
   labels (rewrite to feature names, file paths, dates) but does
   not address iteration counters. The orchestrator falls back to
   the same pattern Track 20 used, propagating the ambiguity.

## Proposed fix

Three options, increasing in scope:

(a) **Cheap — explicit carve-out**: edit
`ephemeral-identifier-rule.md`'s forbidden list to mark
"Review-loop iteration counters" as forbidden in source code,
tests, Javadoc, PR body, and `design-final.md` / `adr.md`, but
**permitted in track episodes** (which themselves only land in
the plan file, removed at Phase 4 cleanup). Add a one-line note
in `track-code-review.md` § "Track Completion" step 4 pointing at
the carve-out.

(b) **Medium — provide a rewrite recipe**: keep the rule
universal (no carve-out) but add a "Rewriting iteration counters
in track episodes" recipe to `ephemeral-identifier-rule.md`.
Recipe candidates:
- "the regression-revert commit" (instead of "iteration 2")
- "the falsifiability sweep commit" (instead of "iteration 1")
- "the residual-cleanup commit" (instead of "iteration 3")
Each commit gets a descriptive role tag matching what it
actually did. The orchestrator picks the role tag at collapse
time.

(c) **Heavy — restructure track episodes**: replace the prose
narrative with a structured table (commit SHA → role → key
fixes). The table format avoids the iteration-counter problem
entirely and is easier to scan. Trade-off: episodes become more
mechanical, less narrative.

Recommended: (a) for now (cheap, unblocks the rule), with (b) as
a Phase 4 follow-up if `adr.md` synthesis stumbles on the same
problem.

## Acceptance criteria

- [ ] `ephemeral-identifier-rule.md` either explicitly permits
  "iteration N" in track episodes (option a) or provides a
  concrete rewrite recipe (option b).
- [ ] `track-code-review.md` § "Track Completion" step 4 carries a
  one-line pointer to whichever resolution lands.
- [ ] A grep over `docs/adr/unit-test-coverage/_workflow/implementation-plan.md`
  for `\biteration [0-9]+\b` either returns zero matches under
  the new rule (if option b is picked) OR is explicitly noted as
  acceptable per the new carve-out.
- [ ] Reproduction: a future Phase C session whose track episode
  needs ≥ 2 review-fix iterations either applies the carve-out
  cleanly or follows the rewrite recipe — no orchestrator-side
  one-off invention.
