# Mid-Phase Handoff Protocol

When the active context window is too full to safely continue, the
current phase must end without losing the work-in-progress that has
not yet landed in the durable plan / step / design files. This
document defines the canonical bridge between such a paused session
and its successor.

Loaded on-demand by:
- the context-consumption gate in `workflow.md` and the inline gates
  in `track-review.md`, `track-code-review.md`, `step-implementation.md`,
  `implementation-review.md`, and `prompts/create-final-design.md`, when
  the gate decides a pause is required and the next session would
  otherwise re-derive work already done;
- the startup protocols in `/execute-tracks` and `/create-plan`, when
  one or more `handoff-*.md` files are detected in `_workflow/`.

## When this protocol fires

Trigger: a context-consumption check returns `warning` (≥30%) or
`critical` (≥40%) **and** either

- the user asks to pause, or
- the current phase boundary has not yet landed in the durable
  plan / step / design files, so re-entering the phase from scratch
  on the next session would re-run sub-agents, gate-checks,
  reviewer iterations, or research already on disk.

If both conditions miss — the gate is fired and the next session can
re-derive everything from the plan / step file Progress section alone
— no handoff file is needed. The Progress section update plus the
end-of-session push are sufficient. Examples of the "no handoff
needed" case: a Phase B pause right after committing a step
(the next session resumes from the next `[ ]` step naturally); a
Phase A pause after `Review + decomposition` was marked `[x]`.

Examples that **do** need a handoff: a Phase C pause between
iteration 3 gate-checks PASSing and user track-completion approval; a
Phase 0 pause mid-investigation with promising leads but no findings
captured yet; a Phase 1 pause mid-plan-drafting with a half-written
Decision Record in a scratch buffer; a Phase 4 pause with `design-final.md`
half-written.

## File location

```
docs/adr/<dir-name>/_workflow/handoff-<phase-slug>[-<discriminator>].md
```

Naming convention:

| Phase | Filename |
|---|---|
| 0 — research (`/create-plan`) | `handoff-research.md` |
| 1 — planning (`/create-plan`) | `handoff-planning.md` |
| 2 — plan review (State 0 in `/execute-tracks`) | `handoff-state0.md` |
| A — track review | `handoff-track-N-phaseA.md` |
| B — step implementation | `handoff-track-N-phaseB.md` |
| C — track code review | `handoff-track-N-phaseC.md` |
| 4 — final artifacts (State D) | `handoff-phase4.md` |
| Ad-hoc research interlude | `handoff-research-<slug>.md` |

Multiple concurrent pauses are allowed (e.g., a paused track plus an
ad-hoc research interlude). The resume protocol processes them
most-recent-first by file mtime.

The handoff file lives under `_workflow/` for three reasons:
1. it survives `/clear` (it is on disk);
2. it survives local-disk loss because the end-of-session
   `git push` carries it into the draft PR;
3. it is removed automatically in the Phase 4 cleanup commit
   alongside the rest of `_workflow/`, so finished branches do not
   carry stale handoffs into the merged tree.

If `_workflow/` does not exist yet (very early in `/create-plan`),
create it before writing the handoff. `/create-plan` is already
required to create `_workflow/` as its first durable action, so this
case is rare.

## Detection at session start

Both `/create-plan` and `/execute-tracks` MUST run this check at the
top of their startup protocol, BEFORE any state evaluation:

```bash
ls docs/adr/<dir-name>/_workflow/handoff-*.md 2>/dev/null
```

If any files exist, load this document and follow §Resume protocol
below INSTEAD of the normal state-evaluation path. The file's
existence is the **authoritative pause signal** — the secondary
PAUSED line inside the step / plan file exists only as a visual cue
for humans reading those files directly, and is kept in sync with
the handoff file by the author and resume protocols below.

## Secondary marker (defense-in-depth)

In addition to writing the handoff file, leave a single line marker
inside the natural progress-tracking file for the phase:

| Phase | Marker location |
|---|---|
| 0 / 1 | top of `implementation-plan.md` under a `## Status` line (create the section if missing) |
| 2 (State 0) | beneath `## Plan Review` heading in `implementation-plan.md` |
| A / B / C | step file Progress section (`tracks/track-N.md`) |
| 4 | beneath `## Final Artifacts` heading in `implementation-plan.md` |
| Ad-hoc research | none — the handoff file is the sole signal |

Marker format:

```
**PAUSED <YYYY-MM-DD> at <phase-state> pending <decision-or-action>**
- Handoff: <relative-path-to-handoff-file>
```

The literal `**PAUSED ` prefix is greppable; if a regression in
the resume protocol misses the `ls handoff-*.md` check, a follow-up
`grep -rn '^\*\*PAUSED ' docs/adr/<dir>/_workflow/` recovers the
pointer.

## MEMORY.md cross-reference

After writing the handoff file, add or update a MEMORY.md entry under
the current branch. The entry has two bullets:

```markdown
## Branch: <branch-name> — <phase> paused
- [<short-slug>](<path-to-handoff-file>) — one-line hook
- **RESUME PROTOCOL (must read on /execute-tracks resume):** handoff
  file at `docs/adr/<dir>/_workflow/handoff-<phase>.md` carries the
  re-present text. Do not re-spawn reviewers / gate-checks before
  reading it.
```

The MEMORY.md entry is supplemental: the authoritative signal is the
handoff file under `_workflow/`. The cross-reference exists because
MEMORY.md is auto-loaded on every session start and gives the
orchestrator a second chance to notice a pause if the
`ls handoff-*.md` check is accidentally skipped.

## Templates

Both templates share a common header. Pick the body that matches the
phase: research-shaped for Phase 0 / 1 and ad-hoc interludes;
decision-shaped for State 0 / A / B / C / 4.

### Header (both templates)

```markdown
# Handoff: <phase> — <short scope>

**Paused:** <YYYY-MM-DD>
**Phase:** <0 / 1 / 2 (State 0) / A / B / C / 4 / ad-hoc>
**Context level at pause:** warning | critical
**Branch:** <branch-name>
**HEAD:** <sha> "<commit subject>"
**Unpushed:** <N> commits
```

### Research-shaped body (Phase 0 / 1, ad-hoc interludes)

```markdown
## What I was investigating
<1-2 sentences>

## Already ruled out
- <claim> — <evidence: path:line or short reason>

## Most promising lead
<1-2 sentences, with path:line anchors>

## Open questions
- <question>

## Raw notes / partial findings
<in-flight context that would otherwise be lost on /clear — paste
file excerpts, intermediate conclusions, half-formed designs>

## Resume protocol
- Do NOT re-explore: <topics / files already covered>
- Next action on resume: <specific>
```

### Decision-shaped body (State 0, Phase A / B / C / 4)

```markdown
## Durable artifacts on disk
- <path> — <one-line description>

## Pending decision
<what was being asked of the user; the options on the table>

## Verbatim re-present text
<exact text / episode to paste back to the user on resume so the
reasoning chain is not re-derived under high context pressure>

## Resume protocol
- Do NOT redo: <per-phase list — see §Phase-specific "do NOT redo"
  defaults below; add case-specific items as needed>
- On user approval: <next action>
- On fixes requested: <fallback path>
```

## Phase-specific "do NOT redo" defaults

Use these as the starting point for the `Do NOT redo` line. Add
case-specific items as needed.

| Phase | Do NOT redo |
|---|---|
| 0 / 1 | research paths in "Already ruled out"; sections already drafted in `design.md` or `implementation-plan.md` |
| 2 (State 0) | classifier passes whose findings already landed in the plan file |
| A | reviews already marked `[x]` in the step file's **Reviews completed** section |
| B | committed steps (Phase B orphan-commit recovery handles uncommitted ones; see `step-implementation-recovery.md`) |
| C | iteration count already in **Progress**; gate-checks already PASSed; plan corrections already committed |
| 4 | sections of `design-final.md` / `adr.md` already on disk |

## Author protocol — writing the handoff at pause time

When the context-consumption check returns `warning` or `critical` and
the trigger conditions in §When this protocol fires are met:

1. **Pick the filename** from the table in §File location.
2. **Write the handoff file** using the matching template. Be
   explicit about the verbatim re-present text and the durable-
   artifact list — re-deriving them in the next session burns
   context and may drift.
3. **Add the secondary PAUSED marker** to the natural progress-
   tracking file (see §Secondary marker table).
4. **Add or update the MEMORY.md cross-reference** for this branch.
5. **Commit all three changes together** with a message in the form
   `Workflow update: pause <phase> for context refresh`.
6. **Push** (`git push`) so the draft PR carries the handoff.
7. **Tell the user**: context is at `<level>`, work is paused, next
   session should resume via `/execute-tracks` (or `/create-plan`
   for Phase 0 / 1) — the handoff file will drive the resume.

## Resume protocol

When startup detects one or more `handoff-*.md` files, the
orchestrator MUST:

1. **Stop** — do NOT match the normal state-evaluation table yet, and
   do NOT spawn any sub-agents.
2. **Sort the handoffs** most-recent-first by mtime.
3. **For each handoff:**
   - Verify the durable artifacts listed in the file actually exist
     (commits in `git log`, files on disk). If anything is missing,
     flag it to the user before resuming — the handoff may be stale.
   - Present the body verbatim to the user (the research findings or
     the pending decision plus the verbatim re-present text).
   - Wait for the user's response (approval / fixes / redirect).
4. **After resolution** (per file):
   - Delete the handoff file.
   - Remove the matching PAUSED marker line from the step / plan
     file.
   - Remove the MEMORY.md cross-reference line(s) for this handoff.
   - Commit all three deletions with a message recording the
     resolution outcome (e.g., `Workflow update: resume Phase C and
     approve Track 4 completion`).
5. **After all handoffs are resolved**, run the normal startup
   state-evaluation table (the State 0 / A / C / D resume) and
   continue.

The orchestrator MUST NOT, while a handoff is unresolved:

- Re-spawn sub-agents (reviewers, implementer, gate-checks) before
  reading the handoff.
- Re-run the context-consumption check as a way to skip the handoff.
- Treat the in-file PAUSED marker as authoritative if it disagrees
  with the handoff file — the file always wins. If the marker is
  missing but a handoff file exists, the handoff file is still
  authoritative; the missing marker is a defense-in-depth gap, not a
  reason to ignore the handoff.

## Symmetry table — where each phase typically pauses

| Phase | Session command | Likely pause points |
|---|---|---|
| 0 | `/create-plan` | mid-research, after a deep dive that consumed context but before findings are captured |
| 1 | `/create-plan` | mid-plan-drafting, after sections of `implementation-plan.md` / `design.md` are partly written |
| 2 (State 0) | `/execute-tracks` | between consistency and structural reviews, or mid-classifier escalation |
| A | `/execute-tracks` | between sequential reviews (technical / risk / adversarial); after decomposition but before risk-tagging |
| B | `/execute-tracks` | after a committed step, before the next one starts (rare — usually no handoff needed) |
| C | `/execute-tracks` | between iterations, between gate checks, before track-completion approval |
| 4 | `/execute-tracks` (State D) | between sections of `design-final.md`, or between `design-final.md` and `adr.md` |
| Ad-hoc | any | open-ended research interlude inside an otherwise-active phase |

## See also

- `workflow.md` § Startup Protocol (Auto-Resume) — runs the
  `ls handoff-*.md` check before state evaluation
- `workflow.md` § Context Consumption Check — defines `warning` /
  `critical` thresholds and invokes this protocol
- `track-review.md`, `track-code-review.md`, `step-implementation.md`,
  `implementation-review.md`, `prompts/create-final-design.md` —
  inline gates that hand off to this document
- `.claude/skills/create-plan/SKILL.md` — creates `_workflow/` as
  its first durable action and runs the same startup check
- `commit-conventions.md` § Commit type prefixes — `Workflow update:`
  prefix for handoff write and handoff resolution commits
