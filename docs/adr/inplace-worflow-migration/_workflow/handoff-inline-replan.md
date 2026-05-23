# Handoff: inline-replan — staging architecture for workflow-modifying branches

**Paused:** 2026-05-23
**Phase:** A (Track 4a Pre-Flight ESCALATE → inline-replanning)
**Context level at pause:** warning (34%)
**Branch:** inplace-worflow-migration
**HEAD:** 1b1d78731e "Mark Track 3 complete"
**Unpushed:** 0 commits (pre-pause)

## Durable artifacts on disk

- `docs/adr/inplace-worflow-migration/_workflow/design.md` — new section `## Staging for workflow-modifying branches` appended; new Core Concept paragraph `**Staged workflow subtree.**` inserted; Overview roadmap line updated ("Six new domain terms" → "Seven", added "Staging for workflow-modifying branches (the in-place migration's inverse case)"). All edits uncommitted.
- `docs/adr/inplace-worflow-migration/_workflow/plan/track-2.md` — line 78 episode text rephrased to remove the literal `design-mechanics.md §"<…>"` placeholder pattern that tripped the script's `full-design-link-resolution` over-match. Uncommitted.
- `docs/adr/inplace-worflow-migration/_workflow/plan/track-3.md` — line 39 retrospective rephrased so the `workflow.md` cross-file follow-up reference no longer reads as a `§"..."` reference attached to the preceding `design.md §"Stamp range computation"` on the same line. Uncommitted.

The mechanical-check sub-step of the edit-design skill (Step 3) ran and PASSed after these three fixes. The cold-read sub-step (Step 4) and the review-log append (Step 7) did NOT run.

## Pending decision

User has already approved the inline-replan shape (see verbatim block below). No further user decision is pending **for the replan itself**. The pending work is mechanical:

1. Spawn the cold-read sub-agent against `design.md` per `edit-design/SKILL.md` Step 4 (bounded scope: new section + Overview + Core Concepts + structure roadmap).
2. Append the mutation entry to `docs/adr/inplace-worflow-migration/_workflow/design-mutations.md` per Step 7 of the same skill.
3. Edit `_workflow/implementation-plan.md`: add D14 block in `#### D-records`; add Track 7 checklist entry after Track 6; add I6 invariant; add Non-Goals one-liner; add Integration Points one-liner; reset `## Plan Review` entry to `- [ ] Plan review (consistency + structural) — autonomous; runs as the first phase of /execute-tracks` per `inline-replanning.md` step 6.
4. Create `_workflow/plan/track-7.md` with the 14-section ExecPlan shape per `conventions-execution.md §2.1`. Populate the four Phase 1 sections (Purpose / Big Picture, Context and Orientation, Plan of Work, Interfaces and Dependencies). `## Base commit` = the inline-replan commit's SHA (compute at commit time).
5. Fix `_workflow/plan/track-4a.md` byte-source anchor at line 40 and line 52 (`design.md §"Stamp range computation"` → `conventions.md §1.6(h)`).
6. Run the advisory structural-review preview against the revised plan per `inline-replanning.md` step 4 (sub-agent at `.claude/skills/review-plan/SKILL.md`).
7. Commit `Inline replan: add staging architecture for workflow-modifying branches (Track 7)` and push.

## Verbatim re-present text

The user-approved replan scope (D14 + Track 7 + design.md section, Model A staging, Path X forward-only, drift-gate exclusion yes):

> **D14: Stage workflow document changes under `<plan-dir>/_workflow/staged-workflow/`; promote at Phase 4**
>
> - Alternatives considered: edit `.claude/workflow/**` and `.claude/skills/**` directly in place (today's behavior); separate git worktree on a sibling branch holding only the workflow changes; per-track Phase C citation hygiene + migration-side surfacing of plan-content drift (an earlier draft of this DR).
> - Rationale: workflow-modifying branches face a bootstrap problem the in-place pattern does not solve. Staging the workflow changes under `<plan-dir>/_workflow/staged-workflow/.claude/workflow/...` and `.../staged-workflow/.claude/skills/...` keeps the branch's own live workflow at develop's state throughout execution. At Phase 4, a "Promote workflow changes" step copies the staged subtree to the live paths in one commit, immediately before the existing final-artifacts commit. The earlier draft routed plan-content drift through per-track Phase C hygiene and migration-side surfacing; isolation at the source is cleaner.
> - Risks/Caveats: branches cannot dogfood the new workflow during execution. Bugs surface at promotion time. Mitigation: optional smoke-test via a sibling test branch. Promotion conflicts follow the rebase conflict shape. Drift gate's existing `git log` pathspec naturally excludes the staged subtree; defensive comment lands at the pathspec site.
> - Implemented in: Track 7 (new).
> - Full design: `design.md` §"Staging for workflow-modifying branches"
>
> **Track 7 checklist entry:**
>
> - [ ] Track 7: Staging architecture for workflow-modifying branches
>   > Introduce the staging convention for branches whose plan modifies `.claude/workflow/**` or `.claude/skills/**`. Workflow document changes accumulate under `<plan-dir>/_workflow/staged-workflow/.claude/workflow/...` and `.../staged-workflow/.claude/skills/...` during execution; the live workflow stays at develop's state for the branch's own sessions. A new "Promote workflow changes" step at Phase 4 copies the staged subtree to the live paths in one commit, immediately before the final-artifacts commit. Forward-applicable only — this branch (in-place migration) finishes under the current model; Track 7 codifies the new pattern for the next workflow-modifying branch.
>   > **Scope:** ~4-5 steps covering the staging convention in `conventions.md`, the implementer path-mapping rule in `implementer-rules.md` / `step-implementation.md`, the Phase 4 promotion step in `workflow.md` + `prompts/create-final-design.md`, the drift-gate defensive comment in `workflow-drift-check.md`, and a worked example of the staging tree shape.
>   > **Depends on:** none (forward-applicable; no sequencing dependency on Tracks 4a/4b/5/6).
>
> **Track placement:** after Track 6. Order becomes 1 (done), 2 (done), 3 (done), 4a, 4b, 5, 6, 7.
>
> **Other plan touches:**
> - Non-Goals gains: "Stamping the staged subtree (`<plan-dir>/_workflow/staged-workflow/**`) — the §1.6(h) walk does not enumerate these paths, and the subtree mirrors live workflow shapes rather than `_workflow/**` artifact templates."
> - Integration Points gains: "**Phase 4 promotion step in `workflow.md` § Final Artifacts** — copies `<plan-dir>/_workflow/staged-workflow/.claude/{workflow,skills}/**` to the corresponding live paths in one commit before the final-artifacts commit; only fires for workflow-modifying plans (detected by the presence of `<plan-dir>/_workflow/staged-workflow/`)."
> - New invariant: **I6**: live workflow paths in the branch's checkout stay at develop's state during execution; the Phase 4 promotion commit is the only transition.
>
> **Companion fix bundled in the same replan commit:** `track-4a.md` byte-source anchor mistakes at lines 40 + 52 (`design.md §"Stamp range computation"` → `conventions.md §1.6(h)`).
>
> **Plan Review reset:** `## Plan Review` entry reset to `[ ]` so next `/execute-tracks` enters State 0 and re-runs Phase 2 against the revised plan.

## Resume notes

- **Do NOT redo:**
  - The user's three-option Track Pre-Flight gate decision (ESCALATE).
  - The user's clarification answers (Model A staging / Path X forward-only / drift-gate exclusion = yes).
  - The draft + user shape approval of D14 and Track 7.
  - The three edits already on disk: design.md section-add + Core Concept + Overview roadmap; track-2.md:78 rephrase; track-3.md:39 rephrase.
  - The mechanical-check Step 3 PASS for the design.md section-add.
- **Next action on resume:** continue from the pending-decision list above. Spawn the cold-read sub-agent (Step 4 of edit-design) against the modified design.md, then proceed to Steps 5-7 of edit-design (merge findings, iterate if needed, append review log). Then the four plan-file edits (D14, Track 7, Plan Review reset, Non-Goals + Integration Points + I6), the track-7.md creation, the track-4a.md anchor fixes, the structural-review preview, the inline-replan commit, and the session-end self-improvement reflection.
- **On fixes requested:** if the cold-read sub-agent surfaces additional should-fix or blocker findings, iterate per edit-design Step 6 (up to 3 rounds). If structural-review preview blockers persist after 3 iterations, follow `inline-replanning.md` step 6 "Blockers persist after 3 iterations" branch (advise restart from /create-plan with accumulated episodes as input).
- **Self-improvement reflection at pause time:** deferred to next session (the friction of "inline-replanning consumed context past warning while landing 3 partial edits + planning 7 more" is the highest-value reflection input; the next session's reflection step naturally captures it).

## Files to expect uncommitted in working tree at resume

```
modified: docs/adr/inplace-worflow-migration/_workflow/design.md
modified: docs/adr/inplace-worflow-migration/_workflow/plan/track-2.md
modified: docs/adr/inplace-worflow-migration/_workflow/plan/track-3.md
```

These three files carry the partial-replan state. Do NOT revert them — they are intentional inputs to the resume.
