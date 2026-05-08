# Inline Replanning (ESCALATE)

When the Track Pre-Flight gate (or any other ESCALATE trigger below)
produces ESCALATE, you handle replanning directly — you have all the
context: every track episode, the full plan file, and architecture
notes.

## When ESCALATE triggers

- Track Pre-Flight Panel 1 (strategy assessment) returns ESCALATE
  and the user accepts (see [`track-review.md`](track-review.md)
  § Track Pre-Flight step 1)
- Track Pre-Flight `Adjust` would touch a deep amendment category
  — Decision Records, Architecture Notes, Goals, Constraints,
  adding/removing tracks, cross-track interaction surfaces, or
  user-requested "fundamental rework" (see `track-review.md`
  § Track Pre-Flight step 4)
- Cross-track impact monitoring detects a fundamental assumption failure
- A step failure affects the track's approach at a level additional commits
  cannot fix

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

**Tooling — PSI for code references in the revised plan.** Replans
routinely add new claims about the codebase (a Component Map entry
names a class, a Decision Record cites a method, an Invariant points
at an enforcement site, an Integration Point names callers). Those
are reference-accuracy facts and must be verified through mcp-steroid
PSI find-usages / find-implementations / type-hierarchy via
`steroid_execute_code`, not grep, when the mcp-steroid MCP server is
reachable per the SessionStart hook — same rule as Phase 1 planning
(see [`planning.md`](planning.md) §"Tooling — PSI-backed Component
Map and integration points" and [`conventions.md`](conventions.md)
§1.4 *Tooling discipline*). Run `steroid_list_projects` once before
the first symbol audit; do not re-probe. Fall back to grep with an
explicit reference-accuracy caveat in any plan claim that depends on
a symbol search only when mcp-steroid is unreachable. Silent grep
misses become Phase A surprises in the revised tracks.

When the replan is triggered by a discovery in an already-completed
track ("we changed X, but Y still depends on the old contract") or
needs to scope new tracks against the upward call chain of a
low-level signature, load the **`call-hierarchy`** recipe (see
[`conventions.md`](conventions.md) §1.4 *Recipes*). Multi-hop
impact at the depth of "callers of callers of X" is exactly the
question grep cannot answer reliably and is the most common reason
inline-replan estimates underscope.

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
specific file on disk depending on the track's current status. See
[§Updating plan and backlog](#updating-plan-and-backlog) below for the
authoritative rule per case.

**Design coherence.** When the revision invalidates a Decision
Record's `**Full design**` link, adds a new design section, or
renames an existing one, the design changes go through the
mutation discipline defined in
[`design-document-rules.md`](design-document-rules.md) § Mutation
discipline — one atomic action that bundles `(apply edit →
auto-review → bounded iterate → present)`. Do not directly Edit
`design.md` mid-replan; invoke the mutation action so the
auto-review gate (mechanical checks + cold-read sub-agent) fires.
The structural review in step 4 below validates the plan; the
design's own narrative quality is owned by the mutation action.

**Working/sync vs direct mutation.** Pick the mutation kind based
on the size of the inline-replanning revision (see
`design-document-rules.md` § Two-mode editing — working vs sync):

- For a single targeted change (one bullet, one section rename,
  one section add), use the direct mutation kinds — `content-edit`,
  `section-add`, `section-rename`, etc. Full discipline runs in
  one shot; the inline-replan completes in one mutation.
- For a multi-section revision **on a design that already has a
  `design-mechanics.md` companion**, follow the working/sync loop:
  `mechanics-edit` rounds for the substantive changes, ending in a
  `design-sync` to re-publish `design.md`. This keeps `design.md`
  stable as a review reference while the agent works through the
  multi-section revision. The working/sync loop is **only** valid
  when `design-mechanics.md` exists at the time of the
  inline-replan — `mechanics-edit` mutates that file, and there's
  no equivalent on a design.md-only design.
- For a multi-section revision **on a design.md-only design**
  (no mechanics companion), either run a sequence of direct
  mutations (`content-edit` / `section-add` / `section-rename` —
  each one is its own atomic action with full discipline), or, if
  the revision is large enough that the design genuinely now needs
  long-form mechanism content, first run a `length-trigger-crossing`
  to create the mechanics companion and then drop into the
  working/sync loop.

**Invocation:** use the `edit-design` skill
([`.claude/skills/edit-design/SKILL.md`](../skills/edit-design/SKILL.md)),
not direct `Edit` / `Write` calls.

**4. Review (advisory preview)** — spawn a sub-agent to run the
structural review protocol from Phase 2 (see `structural-review.md`)
on the revised plan. This is a fail-fast preview, **not the gate** —
the definitive gate is the State 0 re-run on the next
`/execute-tracks` session, which runs consistency + structural
together (see [`implementation-review.md`](implementation-review.md)
§ Replanning). The preview exists so you don't end the session and
clear context only to learn on the next invocation that the revision
was structurally broken. The invocation passes `plan_path` +
`backlog_path` per the path-passing rule in
`.claude/skills/review-plan/SKILL.md`. The sub-agent receives the
full plan file including both completed track episodes and the
proposed revisions, plus the backlog so pending-track details split
into `implementation-backlog.md` are reachable.

**5. Iterate** — if the preview finds structural blockers, revise and
re-preview. Maximum 3 iterations. Consistency findings (phantom
references, flow-trace mismatches) are **not** surfaced by this
preview — they will appear in the next-session State 0 re-run.

**6. Resume or exit:**

- **Review PASS** — update the plan file with the revised plan **and
  reset the `## Plan Review` section** to
  `- [ ] Plan review (consistency + structural) — autonomous; runs as
  the first phase of /execute-tracks`. The reset routes the next
  `/execute-tracks` session through State 0, which re-runs Phase 2
  against the revised plan and catches any consistency drift the
  replan introduced (see
  [`implementation-review.md`](implementation-review.md) § Replanning
  for the contract). Then commit and push the workflow changes
  immediately so the next implementer spawn doesn't lose them via
  `git reset --hard HEAD`:

  ```bash
  git add docs/adr/<dir-name>/_workflow/implementation-plan.md \
          docs/adr/<dir-name>/_workflow/implementation-backlog.md \
          docs/adr/<dir-name>/_workflow/tracks/track-*.md
  git commit -m "Inline replan after Track <N>"
  git push
  ```

  Stage only the paths that the revision actually touched (the
  enumeration in [§Updating plan and backlog](#updating-plan-and-backlog)
  tells you which files apply per case). Any `design.md` /
  `design-mechanics.md` changes from step 3 land via the
  `edit-design` skill, which writes a separate
  `<plan-dir>/_workflow/design-mutations.md` log entry — include those
  files in the commit too if they were touched.

  This is a Workflow update commit (single-commit-per-replan; per
  the table in `commit-conventions.md` § Commit type prefixes).
  Resume orphan-detection treats it as scaffolding and does not
  count it toward any `[x]` step. End the session after the push;
  the next `/execute-tracks` session enters State 0, re-runs Phase 2
  against the revised plan, then resumes track execution.

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
   Update that track's section in `implementation-backlog.md`. The
   plan-file checklist entry keeps its intro paragraph + `**Scope:**` +
   `**Depends on:**` unchanged unless the intro itself is being revised.

3. **Revising a mid-execution track** (status `[ ]` with a step file on
   disk — the execution workflow never sets `[>]` on a track). Update
   the step file's `## Description` section. The backlog entry was
   already removed at Phase A start (see `track-review.md` sub-step (d)),
   so the step file is now the single authoritative location for this
   track's description — do NOT add a new backlog entry. If the
   revision changes the intro paragraph, update the plan-file checklist
   entry's intro paragraph to match.

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
