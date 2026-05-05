# Conventions — Execution (Phase 3)

Execution-specific formats and rules. Loaded only during Phase 3
(`/execute-tracks`). For core conventions shared across all phases, see
[`conventions.md`](conventions.md).

---

## 2.1 Plan File — Execution Additions

These subsections extend the plan file structure defined in
`conventions.md` §1.2.

The phase-specific plan-entry mutations (collapse-on-completion and
strategy-refresh-line append) live with their owning phase
documents — they are not loaded by every Phase 3 session:

- **Track-completion collapse** (Phase C): see
  [`track-code-review.md`](track-code-review.md) § Track Completion
  step 4 — the "Always keep" / "Always drop" rule, the track-episode
  fields, and the final on-disk form.
- **Strategy refresh line** (State A): see
  [`strategy-refresh.md`](strategy-refresh.md) step 5 — the line
  format for CONTINUE / ADJUST and the `[~]`-track variant.

### Session state detection

Moved to `workflow.md` §Startup Protocol — the only place where state
detection is used.

### Step file content (`tracks/track-N.md`)

````markdown
# Track N: <title>

## Description
<assembled at Phase A start — intro paragraph from the plan entry plus
`**What/How/Constraints/Interactions**` + optional diagram from the
backlog. See the "Description lifecycle" subsection at the end of §2.1
for authoritative-location rules across phases.>

## Progress
- [x] Review + decomposition
- [ ] Step implementation (2/5 complete)
- [ ] Track-level code review

## Base commit
`abc1234`

## Reviews completed
- [x] Technical
- [x] Risk

## Steps
- [x] Step: <description>
  - [x] Context: safe
  > **Risk:** high — concurrency (introduces lock acquisition)
  >
  > **What was done:** ...
  > **What was discovered:** ...
  > **Key files:** ...

- [x] Step: <description>
  - [x] Context: info
  > **Risk:** low — default (pure refactoring)
  >
  > **What was done:** ...
  > **Key files:** ...

- [ ] Step: <description>
  > **Risk:** medium — multi-file logic in core (no HIGH triggers)
- [ ] Step: <description>
  > **Risk:** low — default (extract helper)
- [ ] Step: <description>
  > **Risk:** high — public API change
````

The **Description** section carries the track's full description —
intro paragraph (sourced from the plan-file entry), `**What**:` /
`**How**:` / `**Constraints**:` / `**Interactions**:` subsections, and
any track-level Mermaid diagram (both sourced from
`implementation-backlog.md`). Phase A orchestration writes the section
once at the start of the track, concatenating the plan-entry intro
with the backlog-section body; it is re-written only if
[`inline-replanning.md`](inline-replanning.md) updates a mid-execution
track's description. Phase B and Phase C sub-agents that already read
the step file see the description here automatically — see the
Description lifecycle subsection below for the authoritative-location
rules across phases.

The **Progress** section tracks which phase the track is in. The execution
agent updates it at each phase transition:
- Mark `Review + decomposition` as `[x]` when the step file is first written
- Update `Step implementation` count as each step completes (e.g.,
  `(3/5 complete)`); mark `[x]` when all steps are done
- Update `Track-level code review` iteration count as each review
  iteration completes (e.g., `(1/3 iterations)`); mark `[x]` when the
  review passes or max iterations are reached

The **Context check** sub-item under each step records the context window
level measured at sub-step 6 of step-implementation.md and recorded when
the episode is written in sub-step 7. The agent marks it `[x]` with the
measured level (`safe`, `info`, `warning`, or `critical`). If measurement
failed (file missing or command error), record `- [x] Context: unavailable`.
This sub-item is written to the step file (under `_workflow/tracks/`)
alongside the episode and is committed and pushed with the episode
commit. It must be marked before the step is considered complete — an
unmarked context check means the agent skipped the check.

The **Risk:** line in each step's description blockquote names the step's
risk level (`low`, `medium`, or `high`) and the triggering category (or
`default` / `override: <reason>`). It is written by the Phase A decomposer
and reviewed by the user before the step file is approved. Phase B reads
it to gate sub-step 4 — the dimensional review loop runs only when the
tag is `high`; for `medium` and `low` steps Phase B skips directly to
sub-step 5. If implementation reveals that a step is more invasive than
tagged, Phase B may upgrade the tag in place (recording the upgrade in
the risk note) but never downgrade. After the step is committed and the
episode written, the risk tag is locked. Phase C reads the locked tags
from the step file and treats `medium` and `high` step ranges as focal
points when reviewing the cumulative track diff. Full criteria, override
rules, and lifecycle live in [`risk-tagging.md`](risk-tagging.md), which
is loaded only by Phase A (and rarely by Phase B on the upgrade path);
sub-step 4's gating decision in normal Phase B execution reads the tag
value from the step file alone.

The **Base commit** section records the git SHA of `HEAD` at the start of
Phase B, before the first implementation commit. Phase B writes this once
at session start. Phase C reads it to compute `git diff {base_commit}..HEAD`
for the track-level code review.

The **Reviews completed** section records which pre-execution reviews
finished. If a session is interrupted during Phase A, the next session
can skip completed reviews and only re-run missing ones.

Step files are created during Phase A (review + decomposition) when steps
are decomposed. They do not exist during Phase 1 (planning) or Phase 2
(structural review) — only scope indicators in the plan file exist at
that point.

**The plan is a strategic guide, not a rigid task graph.** Track
descriptions, architecture notes, and inter-track dependencies are the
load-bearing parts. Step-level detail is tactical and should emerge
just-in-time during execution when the agent has maximum codebase
context. Phase A always has freedom to adapt step-level decomposition
without formal replanning — only track-level or decision-level changes
require escalation.

### Description lifecycle

A track's detailed description (the `**What/How/Constraints/Interactions**`
subsections plus any track-level Mermaid diagram) travels between files
as the track moves through the workflow. The table below is the
canonical reference for where that content lives at each phase for
pending, active, and completed tracks. Phase A resume logic (see
[`track-review.md`](track-review.md)) and inline replanning (see
[`inline-replanning.md`](inline-replanning.md)) both read the same
rules from here. Skipped tracks follow a separate retention rule —
see [`strategy-refresh.md`](strategy-refresh.md) step 5 for the
authoritative statement on the `[~]`-track plan entry (intro
paragraph + `**Skipped:**` + `**Strategy refresh:**`; never collapsed).

| Phase | Authoritative location | Writer | Reader(s) |
|---|---|---|---|
| Pre-Phase-1 | (none) | — | — |
| Phase 1 write | `implementation-backlog.md` | Phase 1 agent (via `create-plan/SKILL.md`) | Phase 2 consistency + structural reviews |
| Phase A start — before step-file write | `implementation-backlog.md` | (inherited from Phase 1) | Phase A orchestration (reads the track's backlog section) |
| Phase A mid — step file has `## Description`, backlog entry still present | **step file** (Phase A writes the step file first, then removes the backlog entry — the step file takes authority the moment it is written, so an interrupted session always resumes against a single source) | Phase A orchestration (wrote the step file) | Phase A resume logic (idempotent re-entry); Phase A review sub-agents |
| Phase A end | step file | Phase A orchestration (backlog section already removed) | Phase A review sub-agents (Track 3 prompts) |
| Phase B / Phase C | step file | (stable — only inline replan rewrites) | Phase B implementer + Phase B/C code-review sub-agents |
| Phase C after collapse | step file + plan intro paragraph | Phase C collapse (writes intro + episode) | future-track sessions (as strategic context) |
| Skipped at or before Phase A | plan file entry (retained under `[~]`) + (backlog entry removed; step file never created) | `track-skip` | strategy refresh / future sessions |
| Skipped after Phase A (rare) | plan file entry (retained under `[~]`) + step file (retained so the skip is traceable) | `track-skip` | strategy refresh / future sessions |
| Inline replan | per authoritative-location rule in [`inline-replanning.md`](inline-replanning.md) | inline-replanning orchestration | — |

Track-level Mermaid diagrams follow the same trajectory as the
description (backlog → step file at Phase A; never rendered in the
plan file). The writer and readers at each phase are the same as the
corresponding description row above.

**Monotonic shrinkage:** The backlog grows only during Phase 1 or inline
replanning, and shrinks as tracks enter Phase A or are skipped. Normal
Phase A / B / C execution never adds entries. The file lives under
`docs/adr/<dir-name>/_workflow/` — tracked in git during the branch
lifetime so changes are pushed to the draft PR for team visibility and
disk-loss backup, and removed in the Phase 4 cleanup commit before
merge (see `conventions.md` §1.2 for the full lifecycle and
`workflow.md` § Final Artifacts for the cleanup procedure).

### Backlog section body extraction rule

Track N's section body in `implementation-backlog.md` is everything
between the `## Track N: <title>` header line and the next
`## Track M: <title>` header line (or EOF, if Track N is the last
section), excluding the `## Track N:` header line itself. Optional
trailing blank lines between Track N's content and the next
`## Track M:` header are stripped from the extracted body so repeated
extract-then-insert cycles do not accumulate whitespace.

Do **NOT** use line-count deletion to implement the removal — that
approach breaks when track-level `mermaid` diagrams or
multi-paragraph blockquotes change a section's line count between
when the agent originally read it and when the removal runs. Always
search for the header boundary at removal time.

This is the single authoritative definition used wherever the workflow
reads or removes a track's backlog section: Phase A description-move
(see [`track-review.md`](track-review.md) §What You Do item 2,
sub-step (d)), `track-skip` backlog cleanup (see
[`track-skip.md`](track-skip.md)), and inline-replanning updates (see
[`inline-replanning.md`](inline-replanning.md)). All three entry points
apply the rule verbatim — keeping the extraction logic identical
across them avoids accidental divergence when section bodies contain
track-level `mermaid` diagrams or multi-paragraph blockquotes.

---

## 2.2 Episode Formats

Three episode types: **step completion** (`[x]`), **step failed** (`[!]`),
and **track episode** (in plan file after user approval).

Step completion fields: **What was done** (always), **What was discovered**
(when applicable — fill whenever anything unexpected is found),
**What changed from the plan** (when applicable — name affected future
steps), **Key files** (always), **Critical context** (rare).

Step failed fields: **What was attempted**, **Why it failed**, **Impact on
remaining steps**, **Key files**.

Episodes are immutable once written. Code is committed first, then the
episode is written to the step file on disk. Episode length is proportional
to cross-track impact.

**Full format, rules, and examples:**
[`episode-format-reference.md`](episode-format-reference.md)

---

## 2.3 Ephemeral identifier rule — pointer

The full rule (forbidden categories with examples, allowed list,
rewrite examples, branch-only-commit exemption) lives in
[`ephemeral-identifier-rule.md`](ephemeral-identifier-rule.md). Load
that file when about to author durable content — source code, tests,
Javadoc, PR title/body, `design-final.md`, or `adr.md`.

**Quick recap (do not rely on this list alone for borderline cases —
read the full rule):**

Forbidden in durable content:
- Track / Step labels (`Track N`, `Step M`, `Track N Step M`)
- Review finding IDs / prefixes (`CQ33`, `F-12`, `R-4`, …)
- Review-loop iteration counters (`iteration 1`, `round 2`)
- Named invariants cited by label only ("Single-authority invariant", …)
- Plan-file Decision Record IDs not restated in `adr.md`

Allowed: file paths, class/method/field names, commit SHAs,
`adr.md`-defined DR IDs, issue tracker IDs (e.g. `YTDB-123`).

**Self-check before any code/test commit:**

```bash
git diff --cached -- ':!docs/adr/*/_workflow' | grep -nE '\b(Track|Step)[ ]?[0-9]+|\b[A-Z]{1,3}[0-9]+\b'
```

If the grep fires zero matches, the commit is clean and
`ephemeral-identifier-rule.md` does not need to be loaded for that
commit. If it fires any matches — load the full rule and consult its
"How to rewrite a forbidden reference" section before resolving.

**Branch-only commit messages are exempt.** Individual commit messages
on the development branch may cite Track / Step / finding labels —
they are squashed away on merge.

---

## 2.4 Commit messages, code review, complexity, decomposition

These rules are needed only when their specific phase or action runs — not
at session startup. Load on demand:

- **Commit message format** — follow the project's `CLAUDE.md` commit
  conventions. Both code changes and workflow-file changes (under
  `_workflow/`) are committed during the branch lifetime; the
  `_workflow/` directory is removed in the Phase 4 cleanup commit
  before merge (see §1.2 in `conventions.md` and `workflow.md`
  § Final Artifacts). Every commit is pushed immediately to the
  branch's draft PR for team visibility; see
  [`commit-conventions.md`](commit-conventions.md) for the push rule
  and the execution-specific prefixes (`Review fix:`) used during
  session resume. The Ephemeral identifier rule (§2.3 stub above; full
  rule in [`ephemeral-identifier-rule.md`](ephemeral-identifier-rule.md))
  applies to durable content — branch-only commit messages are exempt.
- **Two-tier dimensional code review** (step-level and track-level
  sub-agent reviews, 4 baseline + up to 6 conditional, max 3 iterations):
  [`code-review-protocol.md`](code-review-protocol.md).
- **Complexity tiers** (which pre-execution reviews to run for Simple /
  Moderate / Complex tracks): covered in
  [`track-review.md`](track-review.md) §Complexity Assessment.
- **Checklist decomposition rules** (step sizing, cross-cutting concerns,
  parallel step annotation): covered in
  [`track-review.md`](track-review.md) §Step Decomposition.
