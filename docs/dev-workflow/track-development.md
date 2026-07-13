# Track-Based Development Workflow

## Overview

This is the mandatory flow for ALL changes in this repository:

Research → (lazy) research log → adversarial review → umbrella draft PR → user approves track
split → per-track loop (implement → track code review → fixes → mandatory user review → marker
commit → optional satellite peer-review PR) → squash-merge + cleanup.

The flow scales with change size:

| Change size | What applies |
| --- | --- |
| Multi-track change | Full flow as described above. |
| Single-track change | No track split, no marker commits. Everything else applies. |
| Trivial change (typo, doc-only, mechanical rename, obvious one-file fix) | No split. Micro adversarial review, or skip it with explicit user consent. Planned-changes section is a 2–3-sentence paragraph. |

The mandatory user review gate applies at EVERY tier. For single-track and trivial changes the
whole branch diff is the track, so the user review sits after the agent code review and before
the squash-merge.

## Research phase (lightweight)

Research is interactive exploration before any implementation: read real source code, trace call
chains, and clarify aims and constraints with the user. There is no design document, no
implementation plan, and no mandatory artifacts. The phase ends when the initial design of the
change is understood.

## Research log (lazy-triggered)

Start research WITHOUT a log. Open one the moment any trigger below fires, then backfill the
decisions already made — backfilling is cheap while they are still in context.

| # | Trigger |
| --- | --- |
| 1 | Second non-trivial decision (a choice where a plausible alternative was rejected for a reason). |
| 2 | First surprise — the codebase behaves differently than assumed. |
| 3 | First risky invariant identified — concurrency, durability/WAL/recovery, transactional semantics, public API or behavioral change. |
| 4 | Research will cross a session boundary. |
| 5 | The change is multi-track. |
| 6 | The user requests it. |

### Log format

Four sections:

- **Initial request** — verbatim, written once.
- **Decision Log** — append-only. Each entry is at most 4 lines: the decision, why, and the
  alternatives rejected. Each entry MUST be self-sufficient in one sentence; optional evidence
  citations go in as deep links.
- **Surprises & Discoveries**
- **Open Questions**

### Persistence

During research the log lives as an untracked file `research-log.md` at the repo root. At
umbrella-PR creation its content is folded into the PR description — Key decisions, Risks, and
Open questions feed the corresponding Planned-changes subsections — and the file is deleted.
Decisions made after PR creation are appended to the PR description directly.

### Under-trigger guardrail

When shaping the PR description without a log, state this explicitly (e.g. "no log kept — one
trivial decision, no surprises") so the user can override and request one.

## Adversarial review (mandatory, pre-implementation)

Adversarial review runs after research converges, BEFORE the PR description is finalized and
implementation starts, for EVERY change. The rationale, briefly: critique activates latent
knowledge that constructive planning does not (generator/critic asymmetry), and a fresh-context
reviewer has no anchoring on the author's rationale.

The reviewer must be a fresh context (sub-agent or fresh session) that did not author the
decisions.

Input is keyed on log existence:

- Log exists → the adversary attacks the log (plus its cited evidence).
- No log → the adversary attacks the draft Planned-changes statement.
- Trivial tier → micro-review with one bounded question ("what breaks / what am I not seeing?"),
  or skipped with explicit user consent.

Charter scaling: for single-track changes, limit the mandate to correctness, hidden coupling, and
missed alternatives — style and speculative scope creep are out of bounds.

Licensed null verdict: "no substantive findings" is an acceptable, respected outcome — the review
prompt must say so.

Triage each finding with the user. Three outcomes:

- **Strengthen** — enrich the alternatives-rejected rationale of the attacked decision.
- **Reverse** — change the decision now, while it is still cheap.
- **Accept-as-risk** — record it in Open Questions / Risks.

One round by default; run a second round only if any decision was actually reversed. Append a
verdict line to the log (or straight to the PR description if there is no log):

```
Adversarial review: passed, N accepted risks — YYYY-MM-DD
```

## Umbrella draft PR

Created once the initial design is understood, before implementation:

```bash
gh pr create --draft --base develop
```

The description follows `.github/pull_request_template.md`: Motivation (why), "Planned changes"
(detailed but high-level), and "Tracks" (a display table).

### Planned-changes rules

Write at a high design level using the MAIN DOMAIN ENTITIES from the code — real class and
component names. Hard guards: no file paths, no method signatures. If a sentence would change when
a method is renamed, it is too deep.

Subsections activate when their content exists:

- **Current state** — the before-picture per affected area.
- **What changes** — the externally observable contract/behavior: API surface, semantics,
  defaults, on-disk format, compatibility, concurrency guarantees.
- **How** — design-level description.
- **Key decisions** — chosen vs rejected; preempts "why not X?" review comments.
- **Out of scope** — explicit non-goals.
- **Risks & accepted trade-offs** — including the adversarial review verdict line.
- **Verification approach** — 1–2 lines.

"Deep enough" test: a reviewer who knows the codebase but not this change can (1) predict which
subsystems the diff touches, (2) evaluate each track's diff against a stated intent, and
(3) answer "why not alternative X" without asking.

Note: the description becomes the squash-commit body on develop — it is the permanent
git-archaeology record for the change.

The user approves the proposed track split (and the description) before implementation starts.
Mid-flight changes to the split are re-presented to the user.

## Track loop

A track is one PR in a stacked-diff series: it builds on the tracks before it, stands alone as
an independently reviewable unit, and carries as much of the change as one reviewable diff
holds.

Sizing (soft bounds): a track of ≤~12 in-scope files folds into a neighbor; >~20–25 in-scope
files is a split candidate.

All development is linear on the single working branch; each track is a contiguous commit range.
Track numbering is append-only: completed tracks never renumber, a replanned remainder gets new
numbers, and abandoned planned tracks are struck through in the Tracks table — their numbers are
never reused.

Per-track sequence:

1. Implement the track (normal commit/test/push discipline per `docs/agents/orchestrator-guidelines.md` and `docs/agents/thread-guidelines.md`).
2. MANDATORY agent code review of the cumulative track diff `git diff <prev-marker>..HEAD` —
   correctness, test coverage, style, API surface, documentation sync.
3. Fix findings as normal commits.
4. MANDATORY user review: present the track summary and the track diff to the user, then loop on
   user feedback — landing fixes as normal commits — until the user explicitly approves. The
   agent waits for that approval; the marker commit certifies a fully user-reviewed track.
5. Land the marker commit.
6. Update the umbrella PR Tracks table row (status, satellite link); revise Planned changes only
   if reality diverged from it.
7. Ask the user whether to open a satellite review PR (see `docs/dev-workflow/satellite-pr.md`) —
   a peer-review vehicle for separate reviewers, not the primary user review (that already
   happened in step 4). Once a satellite is open, the track's review loop stays open: process
   peer observations and do NOT start the next track until the peer review is complete
   (completion signal defined in that doc) or the user explicitly waives completion. Record the
   peer-review state (open / completed / waived) in the track's Tracks table row — same
   mechanism as sticky answers — so a new session knows whether the loop is still open. Sticky
   answers are allowed: the user may reply "yes/no for all remaining tracks". Record the sticky
   answer as a one-line note under the Tracks table for cross-session durability and stop
   asking. Sticky answers apply only to this satellite ask — the step-4 user review stays
   mandatory for every track.

## Marker commits (source of truth for track boundaries)

Format — an empty commit with a zero-padded two-digit track number:

```bash
git commit --allow-empty -m "Track NN complete: <short name>"
# e.g.  Track 03 complete: wal-refactor
```

List all boundaries offline:

```bash
git log --oneline --grep '^Track [0-9]* complete:'
```

Track N's diff is `marker(N-1)..marker(N)`; track 01's base is the merge-base with develop.

Properties:

- **Rebase-resilient** — markers are in the history being rebased, so they move with it.
- **Zero cleanup** — the squash-merge into develop erases them.
- The umbrella PR Tracks table is a display-only index (names, scope lines, statuses,
  peer-review state, satellite links — never SHAs); it is never the source of truth for track
  boundaries.

Single-track changes land no markers — the whole branch diff is the track.

## Rebase protocol

After any rebase of the working branch: the markers moved automatically. Re-pin all satellite
base/head branches from the moved markers and push with `--force-with-lease`. Open satellite PRs
update automatically since their refs moved.

## Merge & cleanup

The umbrella PR is the ONLY PR ever merged (squash, per repo conventions). Before merge:

- Re-read the whole PR description end-to-end to confirm it still tells one consistent story.
- Any commits landed after the last user-approved gate (e.g., late peer-review fixes on the
  final track) get a user review.
- Every opened satellite's peer review is completed or explicitly user-waived — closing
  satellites at merge never discards a pending review.
- Strip the Tracks table (and any sticky-answer note under it) from the description. Track
  numbers are ephemeral branch-life identifiers: after the squash-merge the marker commits are
  gone and the satellites are closed, so track references would dangle in develop's history.
  The rest of the description — Motivation, Planned changes — stays: it becomes the
  squash-commit body.

At merge: close all satellite PRs and delete all satellite review branches.

## Layering richer workflows on top

Richer internal planning and execution machinery — whatever agent tooling is in use — may be
layered on top of this baseline, provided it satisfies the mandatory gates: pre-implementation
adversarial review, an umbrella draft PR before coding starts, a code review per track, a
mandatory user review per track, marker commits at track boundaries, and the per-track
satellite-PR ask. This document defines the baseline that applies regardless of the tooling.
