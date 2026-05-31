# Handoff: Phase B (Track 5) — inline replan PARTIALLY APPLIED, paused before the design.md mutation

**Paused:** 2026-05-31
**Phase:** B (Track 5) — mid inline-replan (ESCALATE resolution in progress)
**Context level at pause:** warning (38%) — paused at a clean boundary before `edit-design` would tip context into critical
**Branch:** ytdb-1023-workflow-toc
**HEAD at pause:** the "Pause inline replan" commit this handoff ships with
**Unpushed:** 0 commits (pushed at pause)

## Why this handoff exists

The user confirmed ESCALATE → inline replan (the prior version of this handoff drove that decision; that decision is DONE — do NOT re-present it). The replan's **plan/track edits are applied and committed** in this pause commit. Context hit `warning` (38%) at a clean boundary, and the next unit of work — the `edit-design` mutation on `design.md` — would load a 654-line skill plus 896-line rules plus a cold-read iteration and likely tip context into `critical`. So the replan is paused here with the plan edits durable and the design mutation + preview + final commit pending in a fresh session.

This is NOT a fresh ESCALATE. The decision, the scope, WC1, and the structure are all resolved. Resume = finish the mechanical tail of the replan.

## What is already DONE (committed in the pause commit — do NOT redo)

In `implementation-plan.md`:
- **D19 added** (after D18, before `### Invariants`): "Bootstrap block reader-side match rule expands the reader's own `any`; read window is delimiter-bounded." Documents the three body fixes + the reader-`any`-vs-citer-`any` distinction.
- **D8 gained a `- **Note (D19):**` line**.
- **Track 5 checklist entry** gained a `> **From Track 5 Step 4 dim review (D19 inline replan):**` note (WC1 = suffix all 20; the 11 prompts' fully-backticked `§1.5` is a Phase 4 deferred-drift candidate).
- **`## Plan Review` reset to `[ ]`** (the audit summary was removed) so the next `/execute-tracks` runs State 0.

In `plan/track-5.md`:
- **Concrete Steps roster restructured to 6 steps:** Step 4 flipped to `[x] commit: 09e2e0c521`; **new HIGH-risk Step 5** = "Bootstrap-body correction (D19)"; old verification renumbered to **Step 6**.
- **Step 4 episode written** (commit 09e2e0c521): bootstrap + refs sweep done; dim review gate-green but found inherited body gaps WP1/WP2/WI2 + record-only WC2/WP4/WB1; ESCALATE → body fix deferred to Step 5.
- **Renumber swept clean** across Plan of Work sketch note, Phase A sequencing, Idempotence, Interfaces, Validation (verified: every load-bearing `Step N` reference matches the 6-step roster). The Phase-1 sketch items 4/5 are intentionally left under the "roster wins" disclaimer; the append-only historical Progress/Surprises entries are not rewritten.
- **Decision Log** gained the inline-replan entry.
- **Progress** gained the Step 4-complete + replan-paused entries; the PAUSED marker is updated to this pause.

## What REMAINS (the resume work, in order)

1. **`edit-design` content-edit on `design.md`** (MANDATORY — do NOT direct-Edit; inline-replanning rule). `design-mechanics.md` is absent → design.md-only → direct `content-edit`. Target: `design.md §"Bootstrap protocol for agent system prompts"` → `### Bootstrap block content` fenced block (was lines ~260-273). Replace the fenced block body with the **exact corrected body** below. Optionally add a one-line `### Edge cases / Gotchas` bullet noting the reader-side `any` expansion (the cross-ref subset rule in §"Cross-reference convention" stays one-way; reader-side TOC matching expands the reader's own `any`). `edit-design` runs its own apply→auto-review→present; it edits `design.md` + appends to `design-mutations.md` and does NOT commit (you commit).

   **EXACT corrected fenced body to write into `### Bootstrap block content`:**

   ````markdown
   ```markdown
   ## Reading workflow files (TOC protocol)

   When you Read any file under `.claude/workflow/` or `.claude/skills/`, follow the protocol in `conventions.md §1.8`:

   1. Read the TOC region — from `<!--Document index start-->` to `<!--Document index end-->`. On large files like `conventions.md` this exceeds 30 lines; read to the closing delimiter rather than stopping at a fixed count.
   2. Match TOC rows where Roles contains your role (or your role is `any`, or the row's Roles is `any`) AND Phases contains your phase (or your phase is `any`, or the row's Phases is `any`).
   3. Use `Read(offset, limit)` to read only matched sections.

   Your role: <role token from §1.8 role enum>.
   Your phase: <fixed for agent files and prompts; auto-resume-derived for SKILL.md>.

   Inline refs you find inside workflow files carry the same `name:roles:phases` suffix; apply file-level filtering before opening. Backtick-wrapped refs carry no suffix; open or skip them at your discretion.
   ```
   ````

   The three changes vs the current (gap-carrying) body: step 1 keys the read on the delimiters (not "first ~30 lines"); step 2 expands a wildcard on either axis; the closing line gains the backtick-ref half-sentence.

2. **Advisory structural-review preview** (inline-replanning Process step 4): spawn the structural-review sub-agent on the revised plan (`plan_path` = implementation-plan.md, `plan_dir` = the plan dir) per `review-plan/SKILL.md` path-passing. This is a fail-fast PREVIEW, not the gate — the real gate is the State 0 re-run next session. Inject the workflow-modifying-branch staged-read guidance + the Markdown-not-Java/PSI scoping (recurring YTDB-1038 friction). Max 3 iterations; consistency findings are NOT surfaced here (they appear in the State 0 re-run).

3. **Final replan commit** (inline-replanning Process step 6): stage `design.md` + `design-mutations.md` (the plan/track files are already committed) and commit `Inline replan after Track 5 Step 4 (D19) — complete design mutation`. Then **resolve THIS handoff**: delete `handoff-track-5-phaseB.md`, remove the PAUSED marker from `track-5.md` Progress, remove the MEMORY.md bullets carrying this handoff's filename, and include those in the same commit (MEMORY.md is user-global, a separate non-repo Write). Push.

4. **End session.** The next `/execute-tracks` enters State 0 (Plan Review `[ ]`) → re-runs consistency + structural against the revised plan.

## Decisions already locked (do NOT re-ask)

- **WC1 (user-confirmed): suffix all 20 agents.** The house-style `§1.5` ref uses the whole-file-suffix + backticked-`§1.5` form (`conventions.md:<role>:<phase>` `` `§1.5 ...` ``) on all 20 agents — dr-audit's existing form, validated by rule 6. NOT the fully-backticked span (that would skip an annotatable target). This is Step 5 work, not replan work.
- **Structure (user-approved): Step 4 `[x]` + new Step 5 + verification → Step 6.** The §1.8(e)/(f) edit is Step 5 work, framed as continued staged authoring within Track 5 (pre-merge, not a post-merge completed-track revision).
- **edit-design only touches `design.md`** in the replan. The 38-file re-propagation + staged `conventions.md §1.8(e)/(f)` + WC1 + WP3 are **Step 5** (a future Phase B session, after State 0 passes), not replan work.

## Resume notes

- **Do NOT redo:** the plan/track edits (committed); the dim-review findings (captured in the Step 4 episode + D19); the WC1 / structure / ESCALATE decisions (user-confirmed).
- **Workflow-modifying branch:** reads of `.claude/workflow/**` / `.claude/skills/**` follow staged-first precedence. `design.md` is at `_workflow/design.md` (a plan artifact, edited live — NOT staged). When spawning the structural-preview sub-agent, inject staged-read guidance + Markdown-not-Java/PSI scoping.
- **design-mechanics.md is ABSENT** → design.md-only → direct `content-edit` (confirmed this session).
- **Reflection** for the replan session: run at end of the session that completes the replan (or already partially captured — the IDE-modal-blocking-apply-patch friction this session is an environment issue, not workflow-process; likely 0 dev-workflow issues, dup of the existing YTDB-1038 staged-read pattern).
