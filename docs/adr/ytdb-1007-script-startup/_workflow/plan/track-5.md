<!-- workflow-sha: 0676e2446f373e969da86da6748c91d442135161 -->
# Track 5: SKILL entry-point reconciliation (staged)

## Purpose / Big Picture
After this track lands, the two SKILL entry points that drive `/execute-tracks` and `/create-plan` startup consume the script consistently with the rewritten `workflow.md § Startup Protocol` and `workflow-drift-check.md`, and carry no stale inline copy of the startup-detection sequence. Every edit is STAGED under `staged-workflow/` and promotes with the rest at Phase 4.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Track 5 closes the consumer-set gap Track 4 leaves open. Track 4 rewrites the six workflow-doc surfaces, but the SKILLs that the plan's Goals and Integration Points name as `--mode full` consumers (`execute-tracks/SKILL.md`, `create-plan/SKILL.md`) still describe the old multi-gate startup sequence inline. This track reconciles the two SKILL bodies so the consolidation reaches the actual entry points and the post-merge workflow describes one startup procedure, not two.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

- Origin: discovered during Track 4 Phase A adversarial review (finding A1, 2026-06-03). The original six-surface Track 4 scope excluded the two SKILL entry points, even though the plan's Goals, Integration Points, and Component Map name `/execute-tracks startup` and `/create-plan Step 1.5` as `--mode full` callers. Folded into this dedicated track via inline replanning (user-selected over expanding Track 4, which would have pushed it past the 5-7 step cap).

## Decision Log
<!-- Continuous-log. Execution-time decisions: inline-replan choices,
scope-downs, dependency reveals, gate-override reasons. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->

## Context and Orientation

Two `.claude/skills/**` files drive the startup paths the script now backs, and each carries detection prose that Track 4's rewrites supersede:

- **`execute-tracks/SKILL.md`** (startup section, today ~lines 71-118) re-lists the startup protocol inline as its own steps 1-8: read the plan, identify track statuses, run the Branch Divergence Check, scan handoffs (`ls -t … handoff-*.md`), then determine session state (the State 0/A/C/D/Done table). It introduces this with "Follow the startup protocol in `workflow.md`:" — so it both defers to `workflow.md` and carries a parallel inline copy. After Track 4 rewrites `workflow.md § Startup Protocol` into a `--mode full` dispatch rule, this inline copy describes a contradicting old multi-gate sequence.
- **`create-plan/SKILL.md`** Step 1.5 (today ~lines 44-96) delegates the drift check to `workflow-drift-check.md` by reference ("Invoke the drift gate defined in `workflow-drift-check.md` … follow its § Detection … § No-drift normalization, and § Resolutions flow verbatim"). The delegation-by-reference propagates Track 4's rewrite automatically, but the step's own parenthetical "Phase 1 walk plus Phase 2 fold … where the value lands in shell scope" describes the old inline bash and goes stale once `workflow-drift-check.md § Detection` pivots to a script citation.

Both files are `.claude/skills/**`, so the §1.7 staging convention governs them: first touch copies the live file verbatim into the staged path, edits land on the staged copy, reads resolve staged-first (§1.7(d)), and the live tree stays at develop state until the Phase 4 promotion (§1.7(g), I6).

### Clarifications

This track is reconciliation, not new behavior. The startup-detection logic lives in the script and the rewritten `workflow.md`/`workflow-drift-check.md` (Track 4); Track 5 only removes the SKILLs' stale inline copies and points them at those rewritten surfaces. The exact mechanism for `execute-tracks/SKILL.md` (trim the inline recital to a pointer at the dispatch rule, vs. rewrite the recital to mirror the dispatch) is a Phase A / decomposition decision, bounded by the rule that the SKILL must not carry a detection sequence that contradicts the rewritten `workflow.md § Startup Protocol`.

## Plan of Work

The work edits each SKILL once Track 4's staged rewrites of `workflow.md § Startup Protocol` and `workflow-drift-check.md` are in place (staged-first reads make them visible). Each edit is a copy-then-edit on first touch under the staged path.

1. **`execute-tracks/SKILL.md` startup-recital reconciliation.** Trim the inline startup-protocol steps (the divergence check, the handoff `ls -t` scan, and the State 0/A/C/D/Done determination) so the SKILL defers to the rewritten `workflow.md § Startup Protocol` dispatch rule rather than carrying a contradicting old-sequence copy. Preserve the SKILL's own orientation content that `workflow.md` does not own — which phase doc to load per resume state, the one-phase-per-session boundary, the user-interaction model, and the self-improvement-reflection step.
2. **`create-plan/SKILL.md` Step 1.5 description sync.** Replace the stale "Phase 1 walk plus Phase 2 fold … where the value lands in shell scope" parenthetical with a description that matches the rewritten `workflow-drift-check.md § Detection` (a script-citation, not inline bash). Keep the delegation-by-reference and the conversational Migrate / Defer / Suppress resolution prose intact.

Ordering constraints and invariants to preserve: this track depends on Track 4 so the rewritten `workflow.md § Startup Protocol` and `workflow-drift-check.md` exist (staged) before the SKILLs defer to them. Every edit lands under `staged-workflow/` (S4 / I6); no live `.claude/skills/**` file changes. The rewritten SKILLs plus Track 4's rewritten workflow docs must describe ONE startup procedure end-to-end.

## Concrete Steps
<!-- Phase A placeholder — decomposition writes a thin numbered
roster here: one entry per step with description, `risk:` tag, and a
`[ ]` status checkbox. Per-step episodes do NOT live here; they live
in `## Episodes` below. The roster is immutable after Phase A except
for the status checkbox flip and the optional `commit:` annotation
Phase B appends. -->

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step, identified by step number + commit SHA. Empty at
Phase 1; Phase A does not populate. -->

## Validation and Acceptance

Track-level behavioral acceptance:

- `execute-tracks/SKILL.md`'s startup section no longer carries an inline multi-gate detection sequence (divergence → handoff scan → state determination) that contradicts the rewritten `workflow.md § Startup Protocol`; it defers to the dispatch rule for the detection-and-routing steps.
- `create-plan/SKILL.md` Step 1.5 no longer describes the drift check as an inline "Phase 1 walk plus Phase 2 fold"; its description matches the rewritten `workflow-drift-check.md § Detection` (script citation), and the delegation-by-reference plus the conversational resolution prose stay intact.
- The rewritten SKILLs and Track 4's rewritten `workflow.md § Startup Protocol` / `workflow-drift-check.md` describe one consistent startup procedure end-to-end — a reader following either lands on the same `--mode full` / drift behavior.
- Every edited file lives under `docs/adr/ytdb-1007-script-startup/_workflow/staged-workflow/.claude/skills/...`; no live `.claude/skills/**` file is modified (S4 / I6).

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery
paths once steps are decomposed. -->

Track-level note: copy-then-edit on first touch is idempotent — a file already staged is edited in place on its staged copy, not re-copied from live. The Phase 4 promotion is additive (`cp -r` staged over live) and re-entrant per `§1.7(j)`, so an interrupted promotion re-runs as a no-op.

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't
belong to one specific step. Per-step episode content lives in
`## Episodes` above. Often empty. -->

- The mechanism choice for `execute-tracks/SKILL.md` (trim-to-pointer vs. rewrite-recital-to-mirror) interacts with whether the SKILL re-derives the active track from the `## Checklist` walk (the script's `state` object omits a `track` field). If the SKILL keeps any State C routing description, it must route on `state.substate`, not `state.phase` — the same correction Track 4 applies to `workflow.md`.

## Interfaces and Dependencies

**In scope (all STAGED under `docs/adr/ytdb-1007-script-startup/_workflow/staged-workflow/`):**
- `.claude/skills/execute-tracks/SKILL.md` — startup-recital reconciliation (defer to the dispatch rule).
- `.claude/skills/create-plan/SKILL.md` — Step 1.5 drift-check description sync.

**Out of scope:**
- The six Track 4 surfaces (`workflow.md`, `workflow-drift-check.md`, `branch-divergence-check.md`, `conventions.md §1.6(h)`, `commit-conventions.md`, `migrate-workflow/SKILL.md`) — Track 4 owns them.
- `.claude/scripts/workflow-startup-precheck.sh` and `.claude/scripts/tests/` — authored live by Tracks 1-3, not staged (§1.7(a) does not govern `.claude/scripts/`; D6).
- Any live `.claude/workflow/**` or `.claude/skills/**` file — stays at develop state until the Phase 4 promotion (S4 / I6).

**Staging discipline:** staged-subtree layout per `§1.7(a)`; the `§1.7(b)` marker is declared in the plan's Constraints; reads resolve staged-first per `§1.7(d)`; first touch copies the live file verbatim then edits per `§1.7(e)`; the Phase 4 promotion rebases onto develop first per `§1.7(f)` and is additive and re-entrant per `§1.7(j)`.

**Dependencies:** depends on Track 4 — the rewritten `workflow.md § Startup Protocol` (the dispatch rule) and `workflow-drift-check.md` (the script citation) must exist staged before this track points the SKILLs at them. No downstream track consumes this one; the Phase 4 promotion is the next consumer.
