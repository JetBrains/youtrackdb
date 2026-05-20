# Episode Format Reference

Detailed episode format rules and examples. Referenced from
`conventions-execution.md` §2.1 and §2.2.

The per-step episode is no longer a nested blockquote under a legacy
`## Steps` roster item (that section name retired with the move to
the 14-section per-track shape; the canonical home for the step
roster is now `## Concrete Steps`). Per D9, D11, and D12, the writer
(the Phase B orchestrator's sub-step 7) lands the episode across up
to four sections in a deterministic order — one statusline read
followed by two always-run writes and two conditional promotions.
The Concrete Steps roster carries only the plan fields (description,
`risk:` tag, status checkbox, optional `commit:` annotation); the
full episode content lives in `## Episodes`.

Episode prose is Tier A house-style content. The four episode fields
(`What was done`, `What was discovered`, `What changed from the
plan`, `Critical context`) and the matching `## Surprises &
Discoveries` / `## Decision Log` promotion lines land in durable
track-file Markdown and survive into the eventual ADR aggregation.
Apply the full rule set in `.claude/output-styles/house-style.md`
when drafting them: BLUF lead, banned vocabulary, em-dash
discipline, ≤200-word section cap, structural rules. The four
banned-section heading slugs to apply are `## Banned vocabulary`,
`## Banned sentence patterns`, `## Banned analysis patterns`, and
`### Em-dash discipline`.
See [conventions.md §1.5 Writing style for Markdown and prose artifacts](conventions.md) for the workflow-level pointer.

---

## The four-section write checklist

The workflow has two write shapes that share the statusline-read-
first prefix:

| Writer | Shape |
|---|---|
| Phase B sub-step 7 (per-step success) | per-step (sub-steps 0, 1, 2, 3, 4) |
| Phase B failed-step `[!]` writer | per-step (sub-steps 0, 1, 2, 3, 4) — Surprises / Decision Log promotion still applies when the failure surfaces a cross-cutting fact or a tagged decision |
| Phase A decomposition-complete | Progress-only (sub-steps 0, 2) |
| Phase C iteration / track-completion / iteration-failure | Progress-only (sub-steps 0, 2) |

- **Per-step write shape** — used by Phase B sub-step 7 (the
  `on_success` writer in
  [`step-implementation.md`](step-implementation.md)) and the
  failed-step `[!]` writers in
  [`step-implementation-recovery.md`](step-implementation-recovery.md)
  §Step Failure and §`rollback_and_handle_failure`. Episodes block
  (with roster checkbox flip) + Progress entry are always written;
  Surprises and Decision Log promotions are conditional. The full
  four-sub-step checklist below applies verbatim.
- **Progress-only write shape** — used by Phase A decomposition-
  complete (see [`track-review.md`](track-review.md) §Phase A:
  Review + Decomposition step 5), Phase C iteration writes, Phase C
  track-completion, and Phase C iteration-failure (see
  [`track-code-review.md`](track-code-review.md) §Review loop).
  These writers do **not** append an Episodes block, do **not** flip
  a `## Concrete Steps` checkbox, and do **not** consider Surprises
  / Decision Log promotion. Only sub-step 0 (statusline read) and
  sub-step 2 (Progress append) fire; sub-steps 1, 3, and 4 are
  skipped. The Phase C `## Outcomes & Retrospective` write that
  records review-iteration entries is performed alongside sub-step 2
  by the Phase C writer in `track-code-review.md`.

**Sub-step 0 — Read the statusline (always).** Read
`/tmp/claude-code-context-usage-$PPID.txt` and parse the `level=`
value (one of `safe` / `info` / `warning` / `critical`). If the
file is missing or the parse fails, use `unknown` per the D12
fallback rule — do not skip the write. The parsed `<level>` is
inlined into the writes below.

**Sub-step 1 — Append the Episodes block (per-step shape only).**
Append a heading + four episode fields to the track file's
`## Episodes` section, and flip the matching `## Concrete Steps`
roster line from `[ ]` to `[x]`. Skipped under the Progress-only
write shape.

**Sub-step 2 — Append the Progress entry (always).** Append a
one-line entry to the track file's `## Progress` section.

**Sub-step 3 — Promote to Surprises & Discoveries (per-step shape,
conditional).** If the discovery is cross-cutting, append a
one-line summary to `## Surprises & Discoveries` with a
back-reference to the Episodes block. Skipped under the Progress-
only write shape (Phase A / Phase C writers do not produce per-step
episodes).

**Sub-step 4 — Promote to Decision Log (per-step shape,
conditional).** If the plan-deviation names an execution-time
decision, append a one-line entry to `## Decision Log` with a
back-reference to the Episodes block. Skipped under the Progress-
only write shape.

---

## Completed-step Episodes block

The standard template for a successful step:

```markdown
### Step N — commit <SHA>, <ISO> [ctx=<level>]
**What was done:** <factual summary>

**What was discovered:** <or omit if nothing unexpected>

**What changed from the plan:** <or omit; names affected future steps>

**Key files:**
- `path/to/file.java` (modified)
- `path/to/new-file.java` (new)

**Critical context:** <or omit; use sparingly>
```

The header carries three fields after `Step N`:
- `commit <SHA>` — the implementer's primary commit SHA (or the
  `Review fix:` commit SHA when sub-step 7 fires after a successful
  fix iteration).
- `<ISO>` — ISO 8601 timestamp at write time (`YYYY-MM-DDTHH:MMZ`
  UTC).
- `[ctx=<level>]` — mandatory per D12. Reflects the orchestrator's
  context window state at write time, as read from
  `/tmp/claude-code-context-usage-$PPID.txt`. The level is one of
  `safe` / `info` / `warning` / `critical`, or `unknown` if the
  statusline file was missing at write time.

### Episode fields

Episodes are produced by the **Phase B orchestrator** from the
implementer's `EPISODE_DRAFT` return field, merged with cross-track
impact observations from sub-step 5. The implementer drafts the
episode after committing the code changes; the orchestrator finalises
and writes it after sub-step 4 (dimensional review for `risk: high`
steps) and sub-step 5 (cross-track impact check) complete.

| Field | Required | Purpose |
|---|---|---|
| **What was done** | Always | Factual summary of the implementation — files created/modified, approach taken |
| **What was discovered** | When applicable | Unexpected findings about the codebase, APIs, or behavior that weren't anticipated by the plan. This is the most important field — it's the mechanism for adapting to new information |
| **What changed from the plan** | When applicable | Any deviations from the planned approach, and which future steps may be affected. If the deviation is significant, flag specific step IDs |
| **Key files** | Always | Files created or modified, with (new) or (modified) annotations |
| **Critical context** | When applicable | Free-form field for anything essential that doesn't fit the structured fields above — e.g., a fundamental architectural insight, a performance characteristic that changes the approach for the whole feature, or a constraint discovered that affects multiple tracks. Use sparingly; most episodes won't need this |

### Rules

- **"What was discovered" must be filled whenever anything unexpected
  is found** — even if it didn't block the current step. Future
  sessions and track reviews depend on this field to adapt.
- **"What changed from the plan" must name affected future steps**
  when the deviation could impact them. The Phase B orchestrator (and
  later Phase C track-level review) uses this to adapt remaining
  steps within the track and across tracks.
- Keep each field concise but complete. A reviewer should understand
  the full step outcome from the episode alone, without reading the
  diff.
- Episodes are immutable once written. If later work reveals an
  episode was wrong, append a correction note to a later step's
  episode; don't edit the original.
- Fields with no content are **omitted entirely**, not left as "N/A"
  placeholders. A minimal episode that found nothing unexpected
  carries only `What was done` and `Key files`.

### Minimal episode example (nothing unexpected)

```markdown
### Step 4 — commit abc1234, 2026-05-16T15:10Z [ctx=safe]
**What was done:** Extended `LeafPage` with 16-byte histogram header.
Added serialization/deserialization in `LeafPageSerializer`.

**Key files:**
- `LeafPage.java` (modified)
- `LeafPageSerializer.java` (modified)
- `LeafPageHistogramTest.java` (new)
```

---

## Failed-step Episodes block

A failed step (`[!]` checkbox in `## Concrete Steps`) follows the
same shape with a different header marker and a different field set:

```markdown
### Step N — FAILED, <ISO> [ctx=<level>]
**What was attempted:** ...

**Why it failed:** ...

**Impact on remaining steps:** ...

**Key files:**
- `path/to/file.java` (modified before revert)
```

The header carries `FAILED` instead of `commit <SHA>` (no commit
landed for a failed attempt). `<ISO>` and `[ctx=<level>]` follow the
same rules as the completed-step header — the `[ctx=<level>]` field
is mandatory per D12 and is populated from the same sub-step 0
statusline read.

When a step implementation cannot complete its work (tests won't
pass, coverage can't be met, code reviewer finds fundamental
issues, wrong API assumption), the implementer reverts uncommitted
changes (`git reset --hard HEAD`) and returns `RESULT: FAILED` with
a `FAILURE` block. The orchestrator writes the failed Episodes
block from `FAILURE` to the track file, flips the matching
`## Concrete Steps` roster line to `[!]`, and appends a Progress
entry: `- [!] <ISO> [ctx=<level>] Step N failed — see Episodes
§Step N (FAILED)`. The `(FAILED)` suffix disambiguates the
failure entry from the eventual retry's success entry; see
§Back-reference convention below. If the failure arrived from a
`mode=FIX_REVIEW_FINDINGS`
respawn (post-commit), the orchestrator additionally runs the
`Revert step:` rollback per
[`step-implementation-recovery.md`](step-implementation-recovery.md)
§Post-Commit Handlers before writing the Episodes block.

The orchestrator then decides:
- **Retry** with a different approach.
- **Split** the step into smaller pieces that can succeed
  independently.
- **Adjust** upcoming steps to work around the discovered
  constraint.
- **Escalate** if the failure undermines the track's approach.

If the same step fails twice, stop and present the situation to the
user (see
[`step-implementation-recovery.md`](step-implementation-recovery.md)
§Two-Failure Rule).

Failed Episodes blocks live in `## Episodes` alongside completed
blocks; future sessions and reviews scan the section for both
shapes.

---

## Authoritative copies and back-references

Sub-steps 3 and 4 promote selected facts into `## Surprises &
Discoveries` and `## Decision Log` for resume-reader visibility, but
the Episodes block remains the authoritative copy. The promotion
rule:

- **Per-step episode content** → `## Episodes` is canonical.
- **Cross-cutting facts** → `## Surprises & Discoveries` is
  canonical for the high-level summary; the Episodes block holds the
  originating step's local context.
- **Execution-time decisions** → `## Decision Log` is canonical for
  the decision tag (inline-replan / scope-down / dependency-reveal /
  gate-override); the Episodes block holds the surrounding context.
- **Phase state** → `## Progress` is canonical; the Episodes entry
  provides the per-step detail.
- **Cross-step artifacts (rare)** → `## Artifacts and Notes` is
  canonical; not joined to any per-step block.

### Back-reference convention

Sub-step 3 (Surprises promotion) and sub-step 4 (Decision Log
promotion) always include a back-reference of the form
`See Episodes §Step N` so a resume reader scanning the continuous-
log sections can drop into the per-step detail in one navigation
step. The Surprises / Decision Log entries are summaries, not
full episode copies — keep them to one line each.

**Failed-step disambiguator.** Two Episodes blocks may share the
same `### Step N — …` heading prefix when a step failed and was
later retried (one block ends in `… FAILED, <ISO> …`, the other in
`… commit <SHA>, <ISO> …`). For references that target the failed
attempt, append `(FAILED)` to the back-reference: `See Episodes
§Step N (FAILED)`. References to the completed retry use the plain
`See Episodes §Step N` form. The Progress entry written by the
failed-step writer uses the `(FAILED)` form; the Surprises /
Decision Log promotions follow the same rule when they point at a
failed-attempt episode.

Example Surprises promotion:

```markdown
## Surprises & Discoveries
- 2026-05-16T15:10Z Step 4 discovered: `LeafPageSerializer` already
  carries a 4-byte header reserved for histograms — narrowed scope
  of Step 5. See Episodes §Step 4.
```

Example Decision Log promotion:

```markdown
## Decision Log
- 2026-05-16T15:10Z (scope-down) Step 4 narrowed histogram width
  from 32 to 16 bytes per the pre-existing reservation. See
  Episodes §Step 4.
```

---

## Minimum-write contract (when to skip optional writes)

Sub-steps 0, 1, and 2 always fire — the statusline read, the
Episodes block, and the Progress entry. Sub-steps 3 and 4 fire
conditionally:

- **Sub-step 3 (Surprises)** fires when the `What was discovered`
  field mentions a track number other than the current track, or
  names a class/file outside the track's in-scope list, or otherwise
  identifies a fact future sessions need without reading the full
  episode. The orchestrator makes this judgment at sub-step 7 with
  full episode context.
- **Sub-step 4 (Decision Log)** fires when the `What changed from
  the plan` field names a tagged decision (inline-replan, scope-
  down, dependency-reveal, gate-override). Mechanical "tweaked the
  variable name" deviations do not promote.

A step that runs entirely as planned, found nothing unexpected, and
made no decisions writes only the Episodes block (sub-step 1) and
the Progress entry (sub-step 2). Sub-steps 3 and 4 produce nothing.

---

## Where episodes live

Step-level episodes: **track file** under
`docs/adr/<dir-name>/_workflow/plan/track-N.md` `## Episodes`
section.

Track-level episodes: **plan file** (`implementation-plan.md`) under
the track's checklist entry — a strategic summary synthesised from
step episodes by Phase C track completion (see
[`track-code-review.md`](track-code-review.md) § Track Completion).

---

## Code commit and episode ordering

Code changes are committed first (including any code review fix
commits). After all code is committed, the orchestrator writes the
Episodes block + Progress entry to the track file (under
`_workflow/plan/`) and commits the track-file change in a follow-up
commit (e.g., `Record episode for <step description>`), then pushes.
Episodes live under `_workflow/` for the branch lifetime and are
aggregated into the ADR during Phase 4; the entire `_workflow/`
directory is removed by the Phase 4 cleanup commit before merge.

---

## Episode length rule

Proportional to cross-track impact. A track that went as planned and
produced no surprises needs 1-2 sentences. A track that discovered
architectural issues, changed assumptions, or deviated from the plan
should include enough detail for downstream sessions (Phase B in
later tracks, Phase C track review, the next session's Track
Pre-Flight Panel 1 strategy assessment) to assess impact on
remaining tracks without reading the track file. There is no hard
line limit — clarity and completeness for downstream decision-making
is the criterion.

The same principle applies to step episodes: a trivial rename needs
one line; a step that uncovered a concurrency bug needs a full
explanation.
