# YTDB doctrine additions

When bumping a pinned agent package in `.pi/settings.json`, read
`docs-internal/dev-workflow/agent-package-upgrades.md`.

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
   user review (`track-workflow.md § Peer review (project-layered)`).

## Model routing

1. Always name both `model` and `effort`, or neither. Naming one alone
   takes the other from a default you did not choose.
2. Name neither only for bulk mechanical work: the dispatch then rides
   the thread's base, which is sized for nothing else.
3. Gate and verification actions MUST name both arguments.
   Use a level that the live routing table marks as measured for that model.
   This rule covers change-class confirmation and each required high-level design presentation.
   It covers design adversarial review for complex and risky changes.
   It covers agent code review and every finding-verification gate.
   It covers the implementation-stage escape-hatch adversarial review.

   It covers draft PR creation and the ready-for-review flip.
   It covers every Maven test or coverage run, including end-of-track and post-flip runs.
   It covers commits, pushes, and changes to storage, write-ahead log, index, or transaction code.
   It also covers crash-recovery code changes.
   Do not reduce these actions to the cheapest model that might clear them.
   This project warns about an unmeasured level instead of refusing it.

Rationale, model-list maintenance and the machine-local prerequisite:
`docs-internal/dev-workflow/track-development.md` § Model routing.
