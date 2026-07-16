# YTDB doctrine additions

## Satellite review PRs

Full mechanics (creation, observation loop, re-pinning, cleanup) live in
`docs-internal/dev-workflow/satellite-pr.md` — read it before acting;
these rules only bind the workflow, they do not restate it.

1. Multi-track changes only: after each track's user approval and
   marker commit land, ask the user whether to open a draft satellite
   review PR for that track.
2. Satellites are review-only: always draft, never merged, never marked
   ready for review.
3. Once a satellite is open, the track blocks the next one until the
   peer review completes or the user explicitly waives completion.
4. Sticky answers ("yes/no for all remaining tracks") are honored and
   recorded under the umbrella PR's Tracks table.
