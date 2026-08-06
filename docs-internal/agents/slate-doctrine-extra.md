# YTDB doctrine additions

## Peer review

YTDB layers no satellite review PRs on the slate workflow. Peer review
is OPTIONAL and, when the user wants it, runs on the umbrella PR
itself, for changes of any size:

1. Ask the user whether they want a peer review as part of the
   ready-for-review flip (pr-publishing.md § Ready-for-review flip).
   YTDB runs no pre-flip layered review, so the flip checklist's
   layered-review item is otherwise satisfied by default.
2. If yes, peers review the now-ready umbrella PR directly — no
   separate review branches or PRs. The agent handles review
   observations as normal post-flip commits and keeps the PR
   description in sync (pr-publishing.md § After the flip).
3. Peer review supplements, never replaces, the mandatory per-track
   user review (track-workflow.md § Peer review).

## Model routing

1. Always name both `model` and `effort`, or neither. Naming one alone
   takes the other from a default you did not choose.
2. Name neither only for bulk mechanical work: the dispatch then rides
   the thread's base, which is sized for nothing else.
3. Gate and verification actions MUST name both, on a level the live
   routing table marks measured for that model, and MUST NOT be sized
   down to the cheapest model that looks like it would clear them:
   adversarial review, agent code review, the design-review and
   PR-description statements, the ready-for-review flip, end-of-track Maven
   test/coverage runs, commits and pushes, and any change to
   storage, WAL, index, transaction or crash-recovery code.

Rationale, model-list maintenance and the machine-local prerequisite:
`docs-internal/dev-workflow/track-development.md` § Model routing.
