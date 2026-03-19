Read and follow the workflow for Phase 2 (Structural Review).

Read these workflow documents in order before starting:
1. `.claude/workflow/conventions.md` — shared formats,
   glossary, plan file structure, scope indicators, review iteration protocol
2. `.claude/workflow/structural-review.md` — Phase 2 instructions:
   structural review prompt, gate verification, review iteration, review output

You are the Structural Review Orchestrator. Your job is to validate the plan's
structure, consistency, and completeness before execution begins. You do NOT
need to read the codebase — this review is about plan quality, not technical
accuracy.

Technical, risk, and adversarial reviews happen later, per-track during
Phase 3 execution.

Plan directory name: if "$ARGUMENTS" is non-empty, use it as the directory
name. Otherwise, default to the current git branch name
(`git branch --show-current`).

Plan file: docs/adr/<dir-name>/implementation-plan.md
Review output directory: docs/adr/<dir-name>/reviews/

Steps:
1. Read the plan file and the workflow documents (conventions.md and
   structural-review.md). Also consult `planning.md` §Architecture Notes
   format for the rules the structural review validates against (architecture
   notes, track descriptions, scope indicators, track sizing).
2. Spawn the structural review sub-agent with the prompt from
   `.claude/workflow/prompts/structural-review.md`. Pass the workflow
   directory path (`.claude/workflow/`) so the sub-agent can reference
   the conventions.
3. Receive the findings report.
4. Present findings to the user grouped by severity (blocker → should-fix
   → suggestion). For each finding, show:
   - The issue
   - The proposed fix
   - Your recommendation (accept/modify/reject) with reasoning
5. Wait for the user's decision on each finding.
6. Apply accepted fixes to the plan file.
7. Spawn the gate verification sub-agent with:
   - The previous findings list
   - The updated plan
   - Instructions to verify fixes and flag regressions
8. If the gate finds new blockers, present them and loop (max 3 iterations).
   If fixes significantly restructured the plan (tracks reordered,
   tracks added/removed, scope indicators changed substantially), re-run
   the full structural review instead of the gate.
9. When the gate is clean, save the review document to
   docs/adr/<dir-name>/reviews/structural.md using the format from
   structural-review.md.

Finding IDs are cumulative across iterations (S1, S2, ... S6, S7).

When the review passes:
10. Summarize all changes made to the plan.
11. Confirm the plan is ready for Phase 3 — the user can invoke
    `/execute-tracks` to begin implementation. Remind the user that
    technical/risk/adversarial reviews will happen per-track during execution.
