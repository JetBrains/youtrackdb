<!-- workflow-sha: f97512c02f4dbaaf66c7382397907580fd54391b -->
# Track 2: Read-side staged-read caveat (YTDB-1038)

## Purpose / Big Picture
After this track, every Phase 2, Phase A, and Phase B/C review and gate prompt on a workflow-modifying plan resolves its workflow-document reads through `§1.7(d)` precedence, so an agent stops reporting phantom mismatches against develop's version of a rule the branch already rewrote.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Every review and gate prompt names the live `.claude/...` path, so on a
workflow-modifying plan an agent reads develop's version of a rule the branch
already rewrote and reports a phantom mismatch. A marker-gated caveat in all
nine prompts routes reads through `§1.7(d)` precedence (staged copy when
present, else live).

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

## Decision Log
<!-- Continuous-log. Execution-time decisions: inline-replan choices,
scope-downs, dependency reveals, gate-override reasons. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->

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
  exists, and to the live file otherwise.
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
(D2, D3, S3), with the two context blocks treated as a parallel pair (S2).

1. Add the caveat block to the two canonical context blocks — the fenced
   prompt body of `step-implementation.md` sub-step 4(a) and its parallel copy
   in `track-code-review.md` — with matching meaning (S2).
2. Add the one-line mirror caveat to the seven other prompts:
   `dimensional-review-gate-check.md`, `consistency-review.md`,
   `structural-review.md`, `technical-review.md`, `risk-review.md`,
   `adversarial-review.md`, and `review-gate-verification.md`. The two gate
   prompts already list the diff, plan, and track file as inputs; the caveat
   rides next to that input block.
3. Verify uniformity (S3): the caveat reads the same across all nine prompts.

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
<!-- Phase A placeholder — decomposition writes a thin numbered
roster here: one entry per step with description, `risk:` tag, and a
`[ ]` status checkbox. Per-step episodes do NOT live here; they live
in `## Episodes` below. The roster is immutable after Phase A except
for the status checkbox flip and the optional `commit:` annotation
Phase B appends. -->

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step, identified by step number + commit SHA. Empty at
Phase 1; Phase A does not populate. -->

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

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery
paths once steps are decomposed. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't
belong to one specific step. Per-step episode content lives in
`## Episodes` above. Often empty. -->

## Interfaces and Dependencies

In-scope files (the nine prompt sites, all under `.claude/workflow/**`):
- `step-implementation.md` — sub-step 4(a) canonical context block.
- `track-code-review.md` — the parallel copy of that context block.
- `dimensional-review-gate-check.md` — gate prompt input block.
- `consistency-review.md`, `structural-review.md` — Phase 2 plan review prompts.
- `technical-review.md`, `risk-review.md`, `adversarial-review.md`,
  `review-gate-verification.md` — Phase A track review prompts.

(Phase A decomposition resolves the exact directory of each file under
`.claude/workflow/**`; the design names them by filename.)

Out-of-scope:
- The `§1.7(b)` marker definition and `§1.7(d)` precedence rule in
  `conventions.md` — relied on, not edited.
- `render-slim-plan.py` — relied on to surface `### Constraints`; not edited.

Inter-track dependencies:
- No upstream dependency.
- **Track 3 depends on this track**: the Phase A criteria addendum references
  the read caveat in `technical-review.md`, `risk-review.md`, and
  `adversarial-review.md`, so the caveat must land in those three files first.

Staging: per `§1.7`, all nine edits route through
`docs/adr/<dir>/_workflow/staged-workflow/.claude/workflow/...`; the live
files stay at develop's state until Phase 4 promotion.

Full design: design.md §"Read-side staging awareness".
