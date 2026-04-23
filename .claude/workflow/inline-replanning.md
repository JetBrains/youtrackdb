# Inline Replanning (ESCALATE)

When strategy refresh produces ESCALATE, you handle replanning directly —
you have all the context: every track episode, the full plan file, and
architecture notes.

## When ESCALATE triggers

- Strategy refresh assessment is ESCALATE
- An ADJUST would require modifying Decision Records (automatic ESCALATE)
- Cross-track impact monitoring detects a fundamental assumption failure
- A step failure affects the track's approach at a level additional commits
  cannot fix
- User requests escalation during track review ("fundamental rework")

## Process

**1. Stop** — do not start new steps.

**2. Assess** — present the full situation to the user:

- All track episodes so far (completed tracks)
- Partial progress from any incomplete track (step episodes)
- What assumptions broke and why
- Which remaining tracks are affected and how
- What Decision Records are weakened or invalidated

**3. Propose** — draft a revised plan:

- New or modified tracks for remaining work
- Updated architecture notes (Component Map, Decision Records with revision
  notes, Invariants, Integration Points)
- Reordered dependencies based on what was learned
- Removed tracks that are no longer needed
- Clear rationale for each change

Decision Record revisions follow this format:
```markdown
#### D3: <Decision title> (revised after Track N)
- **Original decision**: <what was decided in planning>
- **What changed**: <discovery that invalidated it>
- **Revised decision**: <new approach>
- **Alternatives considered**: <what else was on the table>
- **Rationale**: <why this revision>
- **Risks/Caveats**: <known downsides>
- **Implemented in**: Track M (revised), Track P (new)
```

**File-location mechanics.** Each proposed track revision lands in a
specific file on disk depending on the track's current status and
whether the plan is new-format (backlog present) or legacy (no backlog).
See [§Updating plan and backlog](#updating-plan-and-backlog) below for
the authoritative rule per case.

**4. Review** — spawn a sub-agent to validate the revised plan using the
structural review protocol from Phase 2 (see `structural-review.md`).
The invocation passes `plan_path` + `backlog_path` per the legacy-fallback
sentinel rule in `.claude/skills/review-plan/SKILL.md` (always pass
`backlog_path`; when `implementation-backlog.md` does not exist on disk,
pass the would-be path annotated `(none — legacy plan)` as the sentinel).
The downstream `prompts/structural-review.md` prompt-reader carries the
"(may be absent — …)" degradation prose; the path-passing layer does not
conditionally omit the argument. The sub-agent receives the full plan
file including both completed track episodes and the proposed revisions,
plus the backlog (or its legacy-fallback sentinel) so pending-track
details that were split into `implementation-backlog.md` are reachable.

**5. Iterate** — if the review finds blockers, revise and re-review. Maximum
3 iterations.

**6. Resume or exit:**

- **Review PASS** — update the plan file with the revised plan. End the
  session. The next session picks up the revised plan and continues.

- **Blockers persist after 3 iterations** — the plan is fundamentally broken
  at a level that incremental revision cannot fix. Advise the user to restart
  from Phase 1 (`/create-plan`) with accumulated episodes as input context.

---

## Updating plan and backlog

When a revision drafted during step 3 of [§Process](#process) lands —
whether during the propose step itself or after review passes — each
affected track must be written to its authoritative file location. The "Description
lifecycle" table in `conventions-execution.md` §2.1 is the authority for
non-inline-replan phases (Phase A start, Phase A mid, Phase C after
collapse, Skipped at or before Phase A); this section is the authority
for inline-replan revisions. If the two ever diverge, a future plan
correction must resync them.

Enumerate by case — each case names the plan status at the moment of
revision and the file(s) that carry the new description:

1. **New track.** Add a thin checklist entry (title + intro paragraph +
   `**Scope:**` + optional `**Depends on:**`) to
   `implementation-plan.md`, and add the full
   `**What/How/Constraints/Interactions**` subsections (plus any
   track-level Mermaid diagram) to `implementation-backlog.md`.

2. **Revising a not-yet-started track** (status `[ ]`, no step file yet).
   Branch on plan shape by checking whether `implementation-backlog.md`
   exists on disk (this is the detection rule stated in `conventions.md`
   §1.2, used anywhere the workflow distinguishes new-format from legacy
   plans):
   - **New-format plan** (backlog file present): update that track's
     section in `implementation-backlog.md`. The plan-file checklist
     entry keeps its intro paragraph + `**Scope:**` + `**Depends on:**`
     unchanged unless the intro itself is being revised.
   - **Legacy plan** (backlog file absent): update the plan-file
     checklist entry's `**What/How/Constraints/Interactions**`
     subsections (today's behavior — the legacy plan carries the full
     description inline).

3. **Revising a mid-execution track** (status `[ ]` or `[>]`, step file
   exists). Update the step file's `## Description` section. The
   backlog entry was already removed at Phase A start (see
   `track-review.md` sub-step (e)), so the step file is now the single
   authoritative location for this track's description — do NOT add a
   new backlog entry. For legacy plans, the step file's
   `## Description` was populated from the plan-file checklist entry
   at Phase A start; update the step file here and, if the revision
   also changes the plan entry's intro paragraph, update that too.

4. **Revising a completed track** (status `[x]`). This is rare — code
   for `[x]` tracks is already merged, so a revision typically means
   one of: (a) the revision actually describes a new follow-up track,
   or (b) a documentation-only correction to the plan entry's intro
   paragraph or Track episode. **Pause and ask the user** before
   proceeding. The existing `[x]` status does not change; any new
   scope becomes a new track (case 1).

5. **Revising a skipped track** (status `[~]`). Update the plan entry.
   Skipped tracks never retain a backlog entry after the skip (see
   `track-skip.md` step 3), so the plan entry is always an
   authoritative location. If the track was skipped **after** Phase A
   began — the "Skipped after Phase A" sub-case in the Description
   lifecycle table in `conventions-execution.md` §2.1, where the step
   file is retained so the skip is traceable — also update the step
   file's `## Description`. Per `track-skip.md`'s "Backlog deletion is
   terminal" warning, a reader un-skipping a `[~]` track must re-author
   the description from scratch — the backlog is not a recovery source
   after skip, and the revision here is the re-authoring.

6. **Removing a track.** Remove the plan entry. If the backlog section
   still exists (the track had not yet entered Phase A and was not yet
   skipped), remove it too using the "Backlog section body extraction
   rule" in `conventions-execution.md` §2.1.
