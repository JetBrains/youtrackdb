# Mid-Phase Handoff Protocol

A handoff file bridges a paused workflow session and its successor
when the current phase has work-in-progress that has not yet landed
in the durable plan / step / design files. Without it, the next
session re-runs sub-agents, gate-checks, reviewer iterations, or
research already on disk.

> **House style for chat-scale prose.** User-facing prose produced from this file (status updates, escalation prompts, replanning summaries, review-mode loop turns, handoff notes, whichever apply) follows the AI-tell subset of `.claude/output-styles/house-style.md`: `## Banned vocabulary`, `## Banned sentence patterns`, `## Banned analysis patterns`, and `### Em-dash discipline`. Structural rules (`§ BLUF lead`, `§ Structural rules` for the ≤200-word section cap, `§ Document-shape rules (design / ADR-specific)`) do not apply to chat-scale prose. See [conventions.md §1.5 Writing style for Markdown and prose artifacts](conventions.md) for the workflow-level anchor and tier mapping.

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

If both conditions miss, no handoff file is needed: the gate is
fired and the next session can re-derive everything from the plan /
track file Progress section alone. The Progress section update plus
the end-of-session push are sufficient. Examples of the "no handoff
needed" case:
- a Phase B pause right after committing a step (the next session
  resumes from the next `[ ]` step naturally);
- a Phase A pause after `Review + decomposition` was marked `[x]`.

Examples that **do** need a handoff:
- a Phase C pause between iteration 3 gate-checks PASSing and user
  track-completion approval;
- a Phase 0 pause mid-investigation with promising leads but no
  findings captured yet;
- a Phase 1 pause mid-plan-drafting with a half-written Decision
  Record in a scratch buffer;
- a Phase 4 pause with `design-final.md` half-written.

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

**Filename collisions.** If a handoff with the chosen filename
already exists (re-pausing the same phase before resolving the prior
handoff, or two ad-hoc interludes sharing a slug), append `-2`, `-3`,
… to the discriminator (e.g., `handoff-track-3-phaseC-2.md`). The
resume protocol's mtime-first sort processes the newest first; the
older handoff resolves on its own turn. Do NOT overwrite an existing
handoff.

The handoff file lives under `_workflow/` for three reasons:
1. it survives `/clear` (it is on disk);
2. it survives local-disk loss because the end-of-session
   `git push` carries it into the draft PR;
3. it is removed automatically in the Phase 4 cleanup commit
   alongside the rest of `_workflow/`, so finished branches do not
   carry stale handoffs into the merged tree.

The author protocol below has an unconditional Step 0 that creates
`_workflow/` if missing, so writing a handoff is safe even on a
mid-Phase-0 pause before `/create-plan`'s own Step 1b has run.

## Detection at session start

Both `/create-plan` and `/execute-tracks` MUST run this check early
in their startup protocol, before any state evaluation. For
`/execute-tracks`, the check runs at Startup Protocol step 4, after
the Branch Divergence Check (step 3) and the Workflow Drift Check
(step 3a). For `/create-plan`, the check runs at Step 1a, after
Step 1.5's Workflow Drift Check:

```bash
ls -t docs/adr/<dir-name>/_workflow/handoff-*.md 2>/dev/null
```

`-t` sorts most-recent-first by mtime, matching the resume
protocol's processing order.

If any files exist, load this document and follow §Resume protocol
below INSTEAD of the normal state-evaluation path. The file's
existence is the **authoritative pause signal** — the secondary
PAUSED line inside the step / plan file exists only as a visual cue
for humans reading those files directly, and is kept in sync with
the handoff file by the author and resume protocols below.

`/review-plan` is not a typical resume entry — users normally resume
through `/execute-tracks` after a `/clear`. A State 0 handoff written
during a manual `/review-plan` session MUST still be resolvable on
the next `/execute-tracks` invocation; `/review-plan` itself does not
need a handoff-detection step at the top of its skill instructions.

## Secondary marker (defense-in-depth)

In addition to writing the handoff file, leave a single line marker
inside the natural progress-tracking file for the phase:

| Phase | Marker location |
|---|---|
| 0 / 1 | none — `implementation-plan.md` may not exist yet during early Phase 0, and the handoff file + MEMORY.md cross-reference are sufficient signals |
| 2 (State 0) | beneath `## Plan Review` heading in `implementation-plan.md` |
| A / B / C | track file Progress section (`plan/track-N.md`) |
| 4 | beneath `## Final Artifacts` heading in `implementation-plan.md` |
| Ad-hoc research | none — the handoff file is the sole signal |

Marker format:

```
**PAUSED <YYYY-MM-DD> at <phase-state> pending <decision-or-action>**
- Handoff: <relative-path-to-handoff-file>
```

The literal `**PAUSED ` prefix is greppable; if a regression in
the resume protocol misses the `ls handoff-*.md` check, a follow-up
`grep -rn '^\*\*PAUSED ' docs/adr/<dir-name>/_workflow/` recovers the
pointer.

## MEMORY.md cross-reference

After writing the handoff file, add or update a MEMORY.md entry under
the current branch. MEMORY.md is user-global, not project-local — it
lives at `~/.claude/projects/<project>/memory/MEMORY.md` and is
auto-loaded on every session start.

**Single-handoff case** — create a new `## Branch:` section:

```markdown
## Branch: <branch-name> — <phase> paused
- [<short-slug>](<path-to-handoff-file>) — one-line hook
- **RESUME PROTOCOL (must read on /execute-tracks resume):** handoff
  file at `docs/adr/<dir-name>/_workflow/handoff-<phase>.md` carries
  the re-present text. Do not re-spawn reviewers / gate-checks before
  reading it. (handoff: `<handoff-filename>`)
```

**Multiple handoffs on the same branch** — if a `## Branch:
<branch-name>` heading already exists in MEMORY.md, append the two
bullets under the existing heading rather than creating a duplicate.
Every bullet pair carries its handoff filename in a parenthetical so
the resume protocol can remove only the bullets belonging to the
resolved handoff. Do NOT create a second `## Branch:` heading for the
same branch.

The MEMORY.md entry is supplemental: the authoritative signal is the
handoff file under `_workflow/`. The cross-reference exists because
MEMORY.md is auto-loaded on every session start, so the orchestrator
still notices the pause if the `ls handoff-*.md` check is
accidentally skipped.

## Templates

Both templates share a common header. Pick the body that matches the
pause's shape, not just its phase:

- **Research-shaped** — Phase 0 / 1, ad-hoc interludes, OR any pause
  that captures exploratory findings without a clear pending
  decision (regardless of phase). Example: a Phase B pause that
  noticed a cross-track impact but has no committed step yet and no
  decision waiting on the user.
- **Decision-shaped** — State 0 / A / B / C / 4 pauses where a
  specific decision is waiting on the user (approval / fixes /
  redirect) and the verbatim re-present text must survive `/clear`.

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

If `git rev-list --count @{u}..HEAD` fails (no upstream set, or
detached HEAD), write `<no upstream — see workflow.md §What to do
before ending a session>` on the **Unpushed:** line.

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

## Resume notes
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

## Resume notes
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
| A | reviews already marked `[x]` in the track file's **Outcomes & Retrospective** section |
| B | committed steps (Phase B orphan-commit recovery handles uncommitted ones; see `step-implementation-recovery.md`) |
| C | iteration count already in **Progress**; gate-checks already PASSed; plan corrections already committed |
| 4 | sections of `design-final.md` / `adr.md` already on disk |

## Author protocol — writing the handoff at pause time

When the context-consumption check returns `warning` or `critical` and
the trigger conditions in §When this protocol fires are met:

0. **Ensure `_workflow/` exists.** Run
   `mkdir -p docs/adr/<dir-name>/_workflow` (idempotent — safe to
   re-run). This is unconditional so a mid-Phase-0 pause that fires
   before `/create-plan` Step 1b still has a place to write the
   handoff.
1. **Pick the filename** from the table in §File location. If the
   chosen filename collides with an existing handoff, append `-2`,
   `-3`, … to the discriminator per §File location → "Filename
   collisions".
2. **Write the handoff file** using the matching template. Be
   explicit about the verbatim re-present text and the durable-
   artifact list — re-deriving them in the next session burns
   context and may drift.
3. **Add the secondary PAUSED marker** to the natural progress-
   tracking file (see §Secondary marker table). Skip this step for
   Phase 0 / 1 and ad-hoc interludes — those rows have no marker.
4. **Add or update the MEMORY.md cross-reference** for this branch
   per §MEMORY.md cross-reference (append under existing branch
   heading if one is already present).
5. **Commit all changes together** with a bare imperative message,
   e.g. `Pause Phase C for context refresh — write handoff`. Stage
   explicit paths only (the handoff file, the marker host file if
   applicable, and the MEMORY.md update); never `git add -A`.
5a. **If the commit fails** (pre-commit hook rejection, dirty
    unrelated paths picked up by the hook): fix the underlying
    issue, re-stage, and create a **new** commit. Do NOT `--amend`
    and do NOT bypass hooks (no `--no-verify`). Repeat until the
    commit lands. See `commit-conventions.md` § Push failure
    handling for the analogous push case.
6. **Push** (`git push`) so the draft PR carries the handoff.
6a. **If the push fails non-fast-forward**, route through
    `branch-divergence-check.md` — do NOT force-push, and do NOT
    rebase before resolving with the user. For any other push
    failure, warn the user that the handoff is committed locally
    but not yet on the PR, then ask whether to retry now or accept
    the degraded-durability state.
7. **Run self-improvement reflection** per
   `self-improvement-reflection.md` **when the context level is
   `warning`**. The friction that triggered the pause is the
   highest-value reflection input. **Skip reflection at `critical`
   level**: context is too tight to safely run the reflection itself,
   and the durable trail (handoff file + commit + push) already
   captures what the next session needs.
8. **Tell the user**: context is at `<level>`, work is paused, next
   session should resume via `/execute-tracks` (or `/create-plan`
   for Phase 0 / 1) — the handoff file will drive the resume.

## Resume protocol

### Per-handoff loop

When startup detects one or more `handoff-*.md` files, the
orchestrator MUST:

1. **Stop** — do NOT match the normal state-evaluation table yet, and
   do NOT spawn any sub-agents.
2. **Sort the handoffs** most-recent-first by mtime (e.g., using
   `ls -t`, which matches the detection command above). On mtime tie,
   fall back to filename sort order.
3. **For each handoff** (one at a time — present, wait, resolve,
   then move to the next):
   - **Verify durable artifacts.** Confirm every commit / file
     listed in the handoff's "Durable artifacts on disk" (decision-
     shaped) or "Already ruled out" / "Most promising lead" path
     references (research-shaped) actually exist. If anything is
     missing, present the missing items to the user and the
     three-option resolution table below.
   - **Present the body** based on the template shape:
     - **Decision-shaped body**: present the *Pending decision* and
       the *Verbatim re-present text* exactly as written, then wait
       for one of `approve` / `request fixes` / `redirect`.
     - **Research-shaped body**: present *What I was investigating*,
       *Already ruled out*, *Most promising lead*, *Raw notes*, and
       *Resume notes → Next action on resume*; then ask the user to
       choose `proceed with Next action on resume` / `redirect` /
       `pause again` (the research has no pending decision, so the
       agent needs explicit guidance on how to proceed).
4. **After resolution** (per file):
   - Delete the handoff file.
   - Remove the matching PAUSED marker line from the step / plan
     file (skip if the marker row was "none" for this phase).
   - Remove the MEMORY.md cross-reference bullets that carry this
     handoff's filename in their parenthetical. If they were the
     last bullets under the `## Branch:` heading, remove the
     heading too.
   - Commit the deletions together with a bare imperative message
     describing the resolution outcome, e.g.
     `Resume Phase C and approve Track 4 completion`.
   - **Phase 4 cleanup exception**: when the resolved handoff is
     `handoff-phase4.md` AND `adr.md` is already committed, do NOT
     produce a separate resolution commit. The Phase 4 cleanup
     commit (Step 5 of `create-final-design.md`) `git rm -r`s
     `_workflow/` and removes the handoff file, PAUSED marker host
     (the plan file), and MEMORY.md entry in the same commit.
   - **Phase 4 promotion pause site** (workflow-modifying plans
     only): Phase 4 is a resumable pause site between the
     promote-staged-workflow commit and the final-artifacts commit
     on workflow-modifying plans. The promote commit defined in
     Step 4 of `prompts/create-final-design.md` lands ahead of the
     final-artifacts commit and copies the staged subtree onto the
     live tree; a pause window opens between the two. On resume,
     the `[ -d "$STAGED_DIR/.claude" ]` guard in that same Step 4
     handles re-entry idempotently per the *Aborted promotion* edge
     case in `design.md` § *Edge cases / Gotchas*: when the next
     session re-enters Phase 4 with the promotion already on disk,
     `cp -r` runs again against the already-promoted live tree as a
     no-op and the promotion commit may then be empty (which the
     implementer detects and skips). For plans without the staged
     subtree (the default — the directory-presence guard evaluates
     false), the existing State D resume from `workflow.md`
     § *Startup Protocol* covers the path; this pause site exists
     only on workflow-modifying plans. The contract surface is Step
     4 of `prompts/create-final-design.md`.
5. **After all handoffs are resolved**, run the normal startup
   state-evaluation table (the State 0 / A / C / D resume) and
   continue.

### Stale-handoff resolution

If durable-artifact verification fails on a handoff, present the
missing items and ask the user to pick one of:

| Option | What it means | When to pick it |
|---|---|---|
| **Discard handoff** | Delete the file + marker + MEMORY.md bullets without resolving the underlying work. Move on to the next handoff in the mtime-sorted list. | The handoff is stale and the missing artifact is irrelevant — e.g., the work was redone differently and the in-flight notes are obsolete. |
| **Proceed anyway** | Keep the handoff authoritative; the missing artifact is the next-session work the handoff is supposed to drive. Continue with §Per-handoff loop step 3. | The missing artifact is exactly what the handoff says is left to do (e.g., a not-yet-written commit named in "Next action on resume"). |
| **Abort** *(default)* | End the session without resolving any handoffs. The user investigates manually and re-runs the session. | The missing artifact is unexpected and not covered by either of the above. |

Default is **Abort** — silent fall-through would mask a real
problem. On Discard, the next handoff in the list is processed; on
Abort, the session ends and no further handoffs are touched.

### Forbidden actions while unresolved

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
| A | `/execute-tracks` | between sequential reviews (technical / risk / adversarial); after decomposition but before the atomic track-file write |
| B | `/execute-tracks` | after a committed step, before the next one starts (rare — usually no handoff needed) |
| C | `/execute-tracks` | between iterations, between gate checks, before track-completion approval |
| 4 | `/execute-tracks` (State D) | between sections of `design-final.md`, or between `design-final.md` and `adr.md` |
| Ad-hoc | any | open-ended research interlude inside an otherwise-active phase |

State A (Track Pre-Flight gate, between Panel 1 and Panel 2) is out
of scope — the gate is short enough that mid-panel pauses are not
supported. Finish both panels before any pause; if context fills up
between completing State A and starting Phase A, the pause lands as
a Phase A pause and uses the Phase A row above.

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
- `step-implementation-recovery.md` — Phase B orphan-commit recovery
  referenced from the §Phase-specific "do NOT redo" table
- `commit-conventions.md` § Commit type prefixes — commit category
  used for handoff write and handoff resolution commits
