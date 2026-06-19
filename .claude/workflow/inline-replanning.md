# Inline Replanning (ESCALATE)

<!--Document index start-->

| Section | Roles | Phases | Summary |
|---|---|---|---|
| §When ESCALATE triggers | orchestrator | 3A,3C | The triggers that route to inline replanning: deep amendments, broken assumptions, unfixable step failures. |
| §Process | orchestrator | 3A,3C | Stop, assess, propose a revised plan (PSI-backed), preview-review, then resume or exit. |
| §Updating plan and track files | orchestrator | 3A,3C | Per-case rule for which on-disk file carries a revised track description by the track's current status. |

<!--Document index end-->

When the Track Pre-Flight gate (or any other ESCALATE trigger below)
produces ESCALATE, you handle replanning directly — you have all the
context: every track episode, the full plan file, and architecture
notes.

> **House style for chat-scale prose.** User-facing prose produced from this file (status updates, escalation prompts, replanning summaries, review-mode loop turns, handoff notes, whichever apply) follows the AI-tell subset of `house-style.md`: `## Banned sentence patterns`, `## Banned analysis patterns`, `## Orientation`, and `## Plain language`. Structural rules (`§ BLUF lead`, `§ Structural rules` for the ≤200-word section cap, `§ Document-shape rules (design / ADR-specific)`) do not apply to chat-scale prose. See conventions.md:any:any `§1.5` for the workflow-level anchor and tier mapping.

## When ESCALATE triggers
<!-- roles=orchestrator phases=3A,3C summary="The triggers that route to inline replanning: deep amendments, broken assumptions, unfixable step failures." -->

- Track Pre-Flight Panel 1 (strategy assessment) returns ESCALATE
  and the user accepts (see track-review.md:orchestrator:3A
  § Track Pre-Flight step 1)
- Track Pre-Flight review mode produces an `ESCALATE` action item,
  or the user picks **Escalate now** on the Mixed-set policy panel
  (see review-mode.md:orchestrator:3A,3C § ESCALATE detection and
  § Mixed-set policy) — i.e., the requested change touches Decision
  Records, Architecture Notes, Goals, Constraints, **adds** a new
  track, crosses cross-track interaction surfaces, or the user
  describes it as "fundamental rework" (deep-amendment list in
  `track-review.md` § Track Pre-Flight step 4). **Removing** a
  remaining track is light (`SKIP_TRACK`) and does not trigger
  inline replanning.
- Cross-track impact monitoring detects a fundamental assumption failure
- A step failure affects the track's approach at a level additional commits
  cannot fix

## Process
<!-- roles=orchestrator phases=3A,3C summary="Stop, assess, propose a revised plan (PSI-backed), preview-review, then resume or exit." -->

**1. Stop** — do not start new steps.

**2. Assess** — present the full situation to the user:

- All track episodes so far (completed tracks)
- Partial progress from any incomplete track (step episodes)
- What assumptions broke and why
- Which remaining tracks are affected and how
- What Decision Records are weakened or invalidated

If `pending_escalate_description` is set in conversation context
when inline-replanning fires from a Track Pre-Flight or Track
Completion gate (captured by a Strip-and-apply earlier in the same
session per review-mode.md:orchestrator:3A,3C § Mixed-set policy),
read the stashed text as the user-supplied deep-change description
for this Assess. Do not prompt the user to re-state it. Clear the
slot after consumption.

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
(see planning.md:planner:1 §"Tooling — PSI-backed Component
Map and integration points" and conventions.md:any:any `§1.4`
*Tooling discipline*). Run `steroid_list_projects` once before
the first symbol audit; do not re-probe. Fall back to grep with an
explicit reference-accuracy caveat in any plan claim that depends on
a symbol search only when mcp-steroid is unreachable. Silent grep
misses become Phase A surprises in the revised tracks.

When the replan is triggered by a discovery in an already-completed
track ("we changed X, but Y still depends on the old contract") or
needs to scope new tracks against the upward call chain of a
low-level signature, load the **`call-hierarchy`** recipe (see
conventions.md:any:any `§1.4` *Recipes*). Multi-hop
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

**Cross-track propagation duty.** A decision whose full inline Decision
Record is duplicated across several track files stays one logical live
record. When a replan revises such a decision, you (the orchestrator)
carry the revision to every track that holds a copy, in this same
replan — there is no separate later pass:

- **Not-yet-completed tracks (status `[ ]`).** Update the revised
  Decision Record in the `## Decision Log` of every not-yet-completed
  track that carries it. This is the duty's primary write path; it is
  open without a separate pause because `## Decision Log` is in the
  cases 2 and 3 updatable-section lists in
  [§Updating plan and track files](#updating-plan-and-track-files)
  below (and in the mid-execution-rewrite line of
  conventions-execution.md:orchestrator,decomposer:3A,3B,3C `§2.1`).
- **Completed tracks (status `[x]`).** Append a supersession note
  (naming the superseded record) to the `## Decision Log` of any
  completed track that carried the decision. This append rides the
  case 4 documentation-only carve-out (it touches no merged code and
  changes no `[x]` status), so it does not trigger the completed-track
  user-pause.

The copy-shape rule is decision-state-based, not replan-event-based:
any post-seed copy of a decision that has ever been revised is written
in the inline-replan revision format above (the seed decision stays in
its `**Original decision**` field), never as clean revised text, so
every post-seed copy carries the marker that routes it to the fidelity
check's provenance-only path (the write-time cold-read fidelity
criterion in `prompts/design-review.md`). State-based means the marker
attaches to every post-seed copy of an ever-revised decision, not only
the copies the current replan happens to touch: a copy written into a
new track during a later replan still carries the revision format
because the decision's state is "ever revised," even though this later
replan did not itself revise it.

**Tier upgrade rides this same path (D12).** A mid-flight tier upgrade
(a `minimal` change that grows a second track, or a `lite` change that
turns out to need a design) is an inline replan like any other: it adds
the new tier's artifacts and runs that tier's Phase-3A passes from the
upgrade point onward. It does not retroactively insert a Phase-2 pass
an earlier session already skipped, and a downgrade is likewise not
automatic — a completed review cannot be un-run. No dedicated
tier-upgrade mechanism exists; the ESCALATE replan is the carrier.

**Materialize first, then write the upgraded tier (D11).** A `minimal`
upgrade is the demanding case: under D2 the `minimal` tier has no
`implementation-plan.md` and no `design.md`, so the upgrade carrier must
**materialize** the dropped artifacts before it records the new tier. Land
them in this order:

1. **Materialize the artifacts the source tier never had.** A
   `minimal`→`lite` (or `minimal`→`full`) upgrade writes a thinned
   `implementation-plan.md` (the derived-mirror plan `lite`/`full` carry but
   `minimal` does not); a `*`→`full` upgrade additionally writes the
   `design.md` seed. A `lite`→`full` upgrade only adds the `design.md` seed,
   since `lite` already has a plan. Seed each artifact from the existing
   research log and the track files, the same sources `create-plan` reads at
   confirmation.
2. **Append the upgraded tier as a ledger event.** The tier home is the
   phase ledger's `tier` field (D4), not a plan line, so the upgrade records
   the new tier by appending a boundary:

   ```bash
   .claude/scripts/workflow-startup-precheck.sh --append-ledger --tier <new-tier>
   ```

   The ledger is last-value-wins, so the appended `tier=<new-tier>` is what
   every Phase-2/3A/4 selector reads ledger-first from the next State-0
   re-run onward (step 6 below resets review state, routing the next
   `/execute-tracks` session through Phase 2). The materialize-then-write
   order keeps the same-commit set internally consistent: the materialized
   plan and the `tier` append land together in the step-6 commit, so the
   committed pair never shows an upgraded tier pointing at a plan that is
   not yet written. A `full` upgrade that crosses the `§1.7` staging line
   also appends the `s17` field on the same or a following boundary.

Without the materialize step, the re-entered selectors would resolve the
new tier from the ledger but find no plan or design to drive the wider
tier's passes; without the ledger append, every selector would keep reading
the stale pre-upgrade tier and run the lighter tier's passes, so the upgrade
would be announced but never take effect downstream. The tier field is
normally a `create-plan`-owned ledger write at confirmation (it is read-only
for every execution-time consumer), and the upgrade is the one
execution-time exception: the ESCALATE replan owns this single tier append,
the same way it owns the revised Decision Records it lands.

Neither the materialized artifacts nor the `tier` append is committed until
the step-6 commit, so an escalation interrupted anywhere before that commit
is recoverable with the standard `git reset --hard HEAD`: the reset reverts
the uncommitted plan/design and the ledger `tier` append together, restoring
the pre-upgrade `minimal` state.

**File-location mechanics.** Each proposed track revision lands in a
specific file on disk depending on the track's current status. See
[§Updating plan and track files](#updating-plan-and-track-files) below
for the authoritative rule per case.

**Design intent stays in the plan, not in `design.md`.** The design
document is frozen after Phase 1 (`design-document-rules.md` Rule 15),
so a replan never mutates it — not even when the revision invalidates a
Decision Record's `**Full design**` link, would have added a design
section, or would have renamed one. Record the design intent where
execution already reads it: revise the affected Decision Record using
the revision format above (the `**What changed**` / `**Revised
decision**` fields carry the new design intent) and capture any
mechanism-level detail in the affected track file's narrative
(`## Context and Orientation`, `## Plan of Work`). The frozen
`design.md` may now diverge from a revised DR's mechanism; that
divergence is expected, and the Phase 4 `design-final.md` reconciles
the as-built design. Do not invoke the `edit-design` skill during a
replan; it runs only in Phase 1 and Phase 4
(edit-design/SKILL.md:orchestrator,planner,final-designer:1,4). The
structural review in step 4 below validates the revised plan.

**4. Review (advisory preview)** — spawn a sub-agent to run the
structural review protocol from Phase 2 (see `structural-review.md`)
on the revised plan. This is a fail-fast preview, **not the gate** —
the definitive gate is the State 0 re-run on the next
`/execute-tracks` session, which runs consistency + structural
together (see implementation-review.md:orchestrator,reviewer-plan:2
§ Replanning). The preview exists so you don't end the session and
clear context only to learn on the next invocation that the revision
was structurally broken. The invocation passes `plan_path` +
`plan_dir` per the path-passing rule in
`review-plan/SKILL.md`. The sub-agent receives the
full plan file including both completed track episodes and the
proposed revisions, plus the track-file directory so pending-track
details (each track's `## Purpose / Big Picture` plus the
detail sections — `## Context and Orientation`, `## Plan of Work`,
`## Interfaces and Dependencies`) are reachable.

**5. Iterate** — if the preview finds structural blockers, revise and
re-preview. Maximum 3 iterations. Consistency findings (phantom
references, flow-trace mismatches) are **not** surfaced by this
preview — they will appear in the next-session State 0 re-run.

**6. Resume or exit:**

- **Review PASS** — update the plan file with the revised plan **and
  reset review state to State 0** by appending a `phase=0` boundary to the
  phase ledger:

  ```bash
  .claude/scripts/workflow-startup-precheck.sh --append-ledger --phase 0
  ```

  The ledger is append-only and last-value-wins, so a `phase=0` appended
  after the prior `phase=A` makes `0` the resolved phase; `determine_state`
  reads it as State 0 (the replan invalidates the earlier passed verdict).
  The reset routes the next `/execute-tracks` session through State 0, which
  re-runs Phase 2 against the revised plan and catches any consistency drift
  the replan introduced (see
  implementation-review.md:orchestrator,reviewer-plan:2 § Replanning for the
  contract). Then commit and push the workflow changes immediately so the
  next implementer spawn doesn't lose them via `git reset --hard HEAD`:

  ```bash
  git add docs/adr/<dir-name>/_workflow/phase-ledger.md \
          docs/adr/<dir-name>/_workflow/implementation-plan.md \
          docs/adr/<dir-name>/_workflow/plan/track-*.md
  git commit -m "Inline replan after Track <N>"
  git push
  ```

  Stage the phase ledger (always — the `phase=0` reset, and the `tier`
  append on a tier upgrade), plus only the other paths the revision actually
  touched (the enumeration in
  [§Updating plan and track files](#updating-plan-and-track-files) tells you
  which files apply per case). A `minimal` upgrade additionally stages the
  newly-materialized `implementation-plan.md` (and `design.md` for `full`)
  from the materialize-then-write step above. Outside a `full` upgrade a
  replan never touches `design.md` (it is frozen after Phase 1 — see step 3's
  "Design intent stays in the plan" rule).

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

## Updating plan and track files
<!-- roles=orchestrator phases=3A,3C summary="Per-case rule for which on-disk file carries a revised track description by the track's current status." -->

When a revision drafted during step 3 of [§Process](#process) lands —
whether during the propose step itself or after review passes — each
affected track must be written to its authoritative file location. The
"Section lifecycle" table in conventions-execution.md:orchestrator,decomposer:3A,3B,3C `§2.1` is the
authority for non-inline-replan phases (Phase 1 write, Phase A,
Phase C after collapse, Skipped at or before Phase A); this section is
the authority for inline-replan revisions. If the two ever diverge, a
future plan correction must resync them.

Enumerate by case — each case names the plan status at the moment of
revision and the file(s) that carry the new description:

1. **New track.** Add a thin checklist entry (title + intro paragraph +
   `**Scope:**` + optional `**Depends on:**`) to
   `implementation-plan.md`, and create a new `plan/track-N.md` track
   file in the canonical 14-section ExecPlan shape (see
   conventions-execution.md:orchestrator,decomposer:3A,3B,3C `§2.1` *Track
   file content* for the full template: 12 OpenAI sections,
   `## Episodes`, `## Base commit`). The intro paragraph lands in
   `## Purpose / Big Picture`; the track-level detail prose splits
   across `## Context and Orientation` (current state and the
   pre-revision baseline), `## Plan of Work` (step sequencing,
   constraints, ordering rationale), and `## Interfaces and
   Dependencies` (interactions with other tracks and files). The
   track-level acceptance criteria land in `## Validation and
   Acceptance` (per-step EARS/Gherkin lines start as Phase A
   placeholders). Any track-level Mermaid diagram lands in
   `## Context and Orientation`. `## Progress` starts with the four
   pre-seeded phase checkpoints (`- [ ] Review + decomposition`,
   `- [ ] Step implementation`, `- [ ] Track-level code review`,
   `- [ ] Track completion`) so the State C resume protocol in
   `workflow.md` can read them as phase markers; the other
   continuous-log sections (`## Surprises & Discoveries`,
   `## Decision Log`, `## Outcomes & Retrospective`, `## Episodes`)
   start empty (HTML-comment placeholders are fine); the
   deferred-sibling-Move sections start with the standard
   reserved-slot HTML comments per D6/D10. `## Base commit` records
   the SHA at which the new track will start executing, typically
   `HEAD` at the time of the inline-replan commit.

2. **Revising a not-yet-started track** (status `[ ]`, no Phase A
   reviews recorded yet). Update the track file's `## Purpose / Big
   Picture` (intro paragraph), `## Context and Orientation` (current
   state and pre-revision baseline, plus any track-level Mermaid
   diagram), `## Plan of Work` (step sequencing, constraints,
   ordering rationale), `## Interfaces and Dependencies`
   (interactions with other tracks and files), `## Validation and
   Acceptance` (track-level acceptance criteria; per-step EARS/Gherkin
   lines remain Phase A placeholders), and `## Decision Log` (the
   track's full inline Decision Records — when the replan revises a
   decision this track carries, update the copy here per the
   cross-track propagation duty in step 3 of [§Process](#process)).
   The plan-file checklist entry keeps its intro paragraph,
   `**Scope:**`, and `**Depends on:**` unchanged unless the intro
   itself is being revised.

3. **Revising a mid-execution track** (status `[ ]` with Phase A
   reviews recorded and/or steps decomposed in the track file; the
   execution workflow never sets `[>]` on a track). Update the track
   file's `## Purpose / Big Picture` (intro paragraph), `## Context
   and Orientation` (current state and pre-revision baseline, plus
   any track-level Mermaid diagram), `## Plan of Work` (step
   sequencing, constraints, ordering rationale), `## Interfaces and
   Dependencies` (interactions with other tracks and files),
   `## Validation and Acceptance` (track-level acceptance criteria;
   per-step EARS/Gherkin lines remain Phase A placeholders), and
   `## Decision Log` (the track's full inline Decision Records — when
   the replan revises a decision this track carries, update the copy
   here per the cross-track propagation duty in step 3 of
   [§Process](#process)).
   If the revision changes the intro paragraph, update the plan-file
   checklist entry's intro paragraph to match.

4. **Revising a completed track** (status `[x]`). This is rare — code
   for `[x]` tracks is already merged, so a revision typically means
   one of: (a) the revision actually describes a new follow-up track,
   or (b) a documentation-only correction to the plan entry's intro
   paragraph or Track episode. **Pause and ask the user** before
   proceeding. The existing `[x]` status does not change; any new
   scope becomes a new track (case 1).

   **Documentation-only carve-out: the cross-track supersession note.**
   The user-pause does not apply to one specific append. When the
   current replan revises a decision whose full Decision Record was
   duplicated into this completed track's `## Decision Log`, the
   propagation duty (step 3 of [§Process](#process)) appends a
   supersession note naming the superseded record to that
   `## Decision Log`. The append touches no merged code, changes no
   `[x]` status, and adds no scope — it only keeps the completed
   track's audit trail consistent with the live decision the replan
   just revised in the not-yet-completed tracks. Append it as part of
   the same replan, without a separate pause. Any revision that is not
   this documentation-only supersession-note append still pauses and
   asks per the rule above.

5. **Revising a skipped track** (status `[~]`). Update the plan entry.
   Skipped tracks never retain a track file after the skip (see
   `track-skip.md` step 3), so the plan entry is the only
   authoritative location. Per `track-skip.md`'s "Track-file deletion
   is terminal" warning, a reader un-skipping a `[~]` track must
   re-author the description from scratch — the deleted track file is
   not a recovery source after skip, and the revision here is the
   re-authoring.

6. **Removing a track.** Remove the plan entry and delete the track
   file at `plan/track-N.md` if it still exists. (If the track had
   already been skipped, its track file was deleted then; case 6
   becomes a no-op for the track file.)
