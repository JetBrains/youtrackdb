# YTDB doctrine additions

## Peer review

YTDB layers no satellite review PRs on the slate workflow. Peer review
is OPTIONAL and runs directly on the umbrella PR, for changes of any
size:

1. At the ready-for-review flip, ask the user whether they want a peer
   review. The flip never discards a pending review (pre-flip
   checklist item — pr-publishing.md § Ready-for-review flip).
2. If yes, peers review the now-ready umbrella PR directly — no
   separate review branches or PRs are created. The agent reads
   observations (`gh pr view --comments` / review threads) and lands
   fixes as normal commits on the working branch, keeping the PR
   description in sync (pr-publishing.md § After the flip).
3. Peer review supplements, never replaces, the mandatory per-track
   user review (track-workflow.md § Peer review).
