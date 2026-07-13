# Track-Based Development Workflow

## Overview

This is the mandatory flow for ALL changes in this repository:

Research → (lazy) research log → user design review → adversarial review → umbrella draft PR →
user approves track split → per-track loop (implement → track code review → fixes → mandatory
user review → marker commit → optional satellite peer-review PR) → ready-for-review flip →
user-performed squash-merge → cleanup.

The flow scales with change size:

| Change size | What applies |
| --- | --- |
| Multi-track change | Full flow as described above. |
| Single-track change | No track split, no marker commits. Everything else applies. Optional peer review runs on the umbrella PR itself — no satellite (see § Ready-for-review flip, merge & cleanup). |
| Trivial change (typo, doc-only, mechanical rename, obvious one-file fix) | No split. Planned-changes section is a 2–3-sentence paragraph; the design review collapses into user consent to it. Micro adversarial review, or skip it with explicit user consent. Optional peer review as for single-track changes — on the umbrella PR. |

The mandatory user review gate applies at EVERY tier. For single-track and trivial changes the
whole branch diff is the track, so the user review sits after the agent code review and before
the ready-for-review flip.

## Research phase (lightweight)

Research is interactive exploration before any implementation: read real source code, trace call
chains, and clarify aims and constraints with the user. There is no design document, no
implementation plan, and no mandatory artifacts. The phase ends when the initial design of the
change is understood and has passed the user design review (see below).

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
Open questions feed the corresponding Planned-changes subsections, and the design review and
adversarial review verdict lines land in Risks & accepted trade-offs — and the file is deleted.
Decisions made after PR creation are appended to the PR description directly.

### Under-trigger guardrail

When shaping the PR description without a log, state this explicitly (e.g. "no log kept — one
trivial decision, no surprises") so the user can override and request one.

## User design review (mandatory, pre-adversarial)

When research converges, the agent presents the design to the user: the proposed approach, key
decisions with the alternatives rejected, risks, and open questions. The presentation input is
keyed on log existence, the same way as the adversarial review's: log exists → present from the
log; no log → present the draft Planned-changes statement. The agent then loops on user
feedback — revising the design (and log) — until the user explicitly approves. Only after that
approval does the adversarial review run.

Rationale: the user owns the design direction. Reviewing with the user first means the
adversarial review attacks a stabilized, user-endorsed design instead of one the user may still
redirect — adversarial rounds are not spent on designs that would change anyway. The loop
mechanics mirror the track loop's mandatory user review (present → feedback → explicit
approval); the position relative to machine review is deliberately inverted — here the user
reviews first.

Durable record — append a verdict line to the log:

```
Design review: user-approved — YYYY-MM-DD
```

With no log, append the line to the draft Planned-changes statement instead; it travels into
the PR description at umbrella-PR creation. (Crossing a session boundary before umbrella-PR
creation is research-log trigger #4 — open a log then; it becomes the verdict line's home.)
The verdict line is written at every tier; at the trivial tier it is appended after the
planned-changes paragraph, which stands in for the Risks & accepted trade-offs subsection.

Tier scaling: at the trivial tier the design review collapses into the user's consent to the
2–3-sentence planned-changes paragraph. For trivial and single-track changes the ask may be
batched: the agent presents the design together with the draft PR description in ONE
pre-adversarial ask, and the user's single approval covers both. Umbrella-PR creation still
follows the adversarial review; the description is re-presented only if adversarial triage
changed it.

## Adversarial review (mandatory, pre-implementation)

Adversarial review runs after the user approves the design in the user design review, BEFORE
the PR description is finalized and implementation starts, for EVERY change. The rationale,
briefly: critique activates latent knowledge that constructive planning does not
(generator/critic asymmetry), and a fresh-context reviewer has no anchoring on the author's
rationale.

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

Triage runs with the user, so a Reverse outcome is itself user-endorsed; after any reversal,
refresh the design-review verdict line (new date) before any further adversarial round (a
round-2 reversal refreshes the line before the change is declared reviewed).

One round by default; run a second round only if any decision was actually reversed. Append a
verdict line to the log (or to the draft Planned-changes statement if there is no log; it
travels into the PR description at umbrella-PR creation):

```
Adversarial review: passed, N accepted risks — YYYY-MM-DD
```

## Umbrella draft PR

Created once the design has passed the user design review and the adversarial review, before
implementation:

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
- **Risks & accepted trade-offs** — including the design review and adversarial review verdict
  lines.
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
   happened in step 4). (This ask is multi-track only — for single-track and trivial changes the
   peer-review ask happens at the flip; see § Ready-for-review flip, merge & cleanup.) Once a
   satellite is open, the track's review loop stays open: process peer observations and do NOT
   start the next track until the peer review is complete (completion signal defined in that
   doc) or the user explicitly waives completion. Record the peer-review state (open /
   completed / waived) in the track's Tracks table row — same mechanism as sticky answers — so
   a new session knows whether the loop is still open. Sticky answers are allowed: the user
   may reply "yes/no for all remaining tracks". Record the sticky answer as a one-line note
   under the Tracks table for cross-session durability and stop asking. Sticky answers apply
   only to this satellite ask — the step-4 user review stays mandatory for every track.

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

## Ready-for-review flip, merge & cleanup

The umbrella PR is the ONLY PR ever merged (squash, per repo conventions), and the merge is
performed BY THE USER — the agent never merges it. Flipping the PR to ready-for-review is the
agent's last act before handing the PR to the user; post-flip fix work and post-merge cleanup
stay agent duties (below). The flip is gated by this pre-flip checklist:

- Re-read the whole PR description end-to-end to confirm it still tells one consistent story.
- Every opened satellite's peer review is completed or explicitly user-waived — flipping never
  discards a pending review.
- All commits landed since the last user-approved gate (e.g., final-track peer-review fixes)
  are presented to the user.
- Strip the whole Tracks section from the description, whatever its form — the table for
  multi-track changes or the "N/A (single-track)" placeholder — plus any sticky-answer note
  under it. Track numbers are ephemeral branch-life identifiers: after the squash-merge the
  marker commits are gone and the satellites are closed, so track references would dangle in
  develop's history. The rest of the description — Motivation, Planned changes — stays: it
  becomes the squash-commit body.

Single-track and trivial changes: at the flip the agent asks whether the user wants a peer
review. If yes, peers review the ready umbrella PR directly — no satellite branches or PR are
created; the observation loop of `docs/dev-workflow/satellite-pr.md` applies, run on the
umbrella PR (fixes land as normal commits; no branch re-pinning is needed since the PR head is
the working branch).

After the flip: the user may wait for CI green and/or peer-review completion and ask the agent
to fix test failures or peer observations. The agent lands fixes as normal commits, keeps the
description in sync, and presents agent-landed commits to the user as they land. Commits pushed
directly by reviewers are visible in the PR UI; the agent reconciles the description with them
on its next task. The user's merge act is the final approval.

At merge: close all satellite PRs and delete all satellite review branches — an agent duty,
executed when the user reports the merge or a later session detects it (the leftover discovery
procedure lives in `docs/dev-workflow/satellite-pr.md` § Cleanup).

## Layering richer workflows on top

Richer internal planning and execution machinery — whatever agent tooling is in use — may be
layered on top of this baseline, provided it satisfies the mandatory gates: a user design
review before adversarial review, pre-implementation adversarial review, an umbrella draft PR
before coding starts, a code review per track, a mandatory user review per track, marker
commits at track boundaries, the per-track satellite-PR ask for multi-track changes (the
flip-time peer-review ask for single-track and trivial changes), and a user-performed merge
(the agent never merges the umbrella PR). This document defines the baseline that applies
regardless of the tooling.
