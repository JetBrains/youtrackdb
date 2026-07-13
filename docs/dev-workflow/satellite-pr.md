# Satellite Review PRs

## Purpose & invariants

A satellite PR is a review vehicle for ONE track's diff, aimed at SEPARATE PEER REVIEWERS. The
primary user review happens in-session as a mandatory per-track gate (see
`docs/dev-workflow/track-development.md`) and is never replaced by a satellite. Two invariants:

- It is **never merged**.
- It is **never marked "ready for review"** — it stays DRAFT for its whole life.

Why draft matters: every PR-gate workflow in this repo (maven-pipeline including the coverage and
test-count gates, pr-title-prefix, block-merge-commits) skips drafts, so satellites incur zero CI
cost. Additionally, the test-count gate's baseline is hardcoded to origin/develop and would
misfire on a non-develop base if the PR ever left draft.

## When

Optional per track and user-gated: after a track's user-review approval and marker commit land,
the agent asks whether to create one. Sticky answers ("yes/no for all remaining tracks") are
honored and recorded under the umbrella PR's Tracks table — they never waive the mandatory
in-session user review.

## Creation

Two pinned branches per track:

- Base `<branch>/track-NN-base` — at the PREVIOUS track's marker commit (for track 01: the
  merge-base with develop).
- Head `<branch>/track-NN-head` — at THIS track's marker commit.

Push both, then:

```bash
gh pr create --draft \
  --base <branch>/track-NN-base \
  --head <branch>/track-NN-head \
  --title "[Track NN] <name> (review only — do not merge)" \
  --body <track summary>
```

The body is the track summary: scope, key decisions relevant to this track, and suggested review
focus. Link the satellite in the umbrella PR's Tracks table.

## Observation loop

The reviewer leaves observations in the satellite PR. The agent reads them (`gh pr view
--comments` / review threads), fixes them as NORMAL commits on the working branch (never on the
satellite branches), then force-updates `<branch>/track-NN-head` to the current working-branch
HEAD with `--force-with-lease`.

Once a satellite is open, the track's review loop stays open: the next track does NOT start
until the peer review completes or the user explicitly waives completion. Peer-fix commits land
after the track's user approval, so they are covered by the pre-merge user review of post-gate
commits (see `docs/dev-workflow/track-development.md` § Merge & cleanup).

Caveat: if later tracks have started (i.e., the user waived completion), the updated head
pollutes the satellite's diff with later-track commits. Mitigations: GitHub's "changes since
your last review" view shows only the fix commits, and observations should be resolved promptly
— preferably before the next track completes.

## Rebase re-pinning

After a working-branch rebase, recompute both branches from the (moved) marker commits and push
with `--force-with-lease`.

## Cleanup

When the umbrella PR merges: close every satellite PR and delete every `track-NN-base` /
`track-NN-head` branch.
