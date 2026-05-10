---
severity: high
phase: phase-b
source-session: 2026-05-10 /execute-tracks unit-test-coverage
---

# Analysis-only step has no clean fit in implementer rulebook

## Symptom

Track 22a Step 1 was an analysis-only step: re-validate a cluster
classification table via PSI find-usages, run a `WHEN-FIXED` grep
cross-reference, and update the cluster table in the step file's
`## Description` section + write a recovery-gap residuals list in
the episode. The step description literally said
`Output: validated cluster table on disk (in this step file), plus
a recovery-gap-residuals list in the episode.`

The implementer rulebook (`.claude/workflow/implementer-rules.md`)
forbids step-file modification (`The implementer MUST NOT modify
the step file, the plan file, or the backlog`) and treats
`RESULT: SUCCESS` as requiring a commit (`COMMIT is empty when
RESULT != SUCCESS. On SUCCESS, it is the SHA of the implementer's
commit.`). Together these rules leave no path for an analysis-only
step whose deliverable IS a step-file edit.

The orchestrator (Phase B main agent) handled Step 1 directly — it
delegated the PSI sweep to a `general-purpose` sub-agent, applied
the resulting cluster-table corrections itself, wrote the episode,
committed as a Workflow update, and marked the step `[x]`. This
worked but is undocumented as a pattern, so a future agent
encountering a similarly shaped step would likely spawn the
implementer first and run into the contract gap before improvising.

## Reproduction context

- Phase: phase-b
- Workflow doc(s) involved:
  - `.claude/workflow/step-implementation.md` §Per-Step Orchestration Loop
  - `.claude/workflow/implementer-rules.md` §"What the implementer does (sub-steps 1–3, expanded)" + §Return contract
- Tool / sub-agent involved (if any): per-step implementer
  (`general-purpose` opus)
- ADR directory at the time:
  `docs/adr/unit-test-coverage/_workflow/`
- Trigger condition: any Phase B step whose deliverable is purely
  workflow-metadata (an edit to the step file's
  `## Description` section, or to a sibling artifact under
  `_workflow/`) with no production-source or test-source code
  change. Often surfaces in coverage / sweep / classification
  tracks (Track 22a Step 1 is the canonical example) where one
  step's job is to validate prior-track inputs before remaining
  steps consume them.

## Why it's a problem

Without a documented pattern, every future analysis-only step
either (a) spawns an implementer and dies on the contract gap
(implementer cannot modify the step file, has nothing to commit;
must return `RESULT: FAILED` with `recommended_action: escalate`,
which is wrong for a successful analysis), or (b) the orchestrator
improvises, like this session did, but without confidence that
the deviation is sanctioned. Either path costs the agent at least
one round of confusion and risks silently violating the rulebook.

The pattern is rare enough that a one-off improv is tolerable, but
common enough in coverage / sweep / refactor tracks that it
deserves a documented branch.

## Proposed fix

Two options, in increasing order of invasiveness:

1. **Add a §"Analysis-only steps" subsection to
   `step-implementation.md`** that names the orchestrator-handled
   path: orchestrator delegates PSI/grep/classification work to a
   sub-agent (Explore or general-purpose) for context isolation,
   applies any step-file edits itself as a Workflow update commit,
   writes the episode, and marks the step `[x]`. Cite Track 22a
   Step 1 as the canonical worked example. Required signal: the
   step description's deliverable is a step-file edit or a
   workflow-metadata artifact, not source code.

2. **Extend the implementer return contract** to allow
   `RESULT: SUCCESS` with `COMMIT: empty` when the step is
   analysis-only, plus a new `ANALYSIS_OUTPUT` block that the
   orchestrator can copy verbatim into the step file's
   appropriate section. This is more invasive (changes the
   contract every implementer parses) but normalises the path.

Option 1 is cheaper and aligns with the existing "orchestrator
owns workflow-file mutations" rule. Recommend Option 1.

## Acceptance criteria

- `.claude/workflow/step-implementation.md` has an explicit
  subsection (e.g., §"Orchestrator-handled steps" or
  §"Analysis-only steps") that names when the orchestrator
  bypasses the implementer spawn for a step.
- The subsection cites at least one observed example
  (Track 22a Step 1 cluster table re-validation) and the
  load-bearing signals (deliverable is a step-file edit / a
  workflow-metadata artifact under `_workflow/`).
- Future Phase B sessions encountering a similarly shaped step
  do not spawn an implementer that returns `RESULT: FAILED` due
  to the contract gap.
- A grep on subsequent track step files for the
  `Output: ... in this step file` shape resolves cleanly to the
  documented orchestrator-handled path.
