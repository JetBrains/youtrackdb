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
