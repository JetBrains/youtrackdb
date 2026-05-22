# Track 5: Self-improvement reflection for migration

## Purpose / Big Picture
After this track lands, every `/migrate-workflow` session ends with the same self-improvement reflection that closes `/execute-tracks` — process feedback on the migration itself feeds the `dev-workflow` YouTrack queue.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Parameterize `self-improvement-reflection.md` to accept a session-type input (`execute-tracks` or `migrate-workflow`) that controls the commit-clean check, the phase identifier in the issue body, the applicability sentence in §"When it runs", and the in-scope examples in §"What counts as a worth-recording issue". Then wire a final reflection step into the rewritten `migrate-workflow` SKILL that invokes it with `session-type=migrate-workflow`. Skip rules (YouTrack MCP unreachable, no work happened) carry through unchanged.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->

## Decision Log
<!-- Continuous-log. Empty at Phase 1. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. Empty at Phase 1. -->

## Context and Orientation

`.claude/workflow/self-improvement-reflection.md` (~660 lines) is today's `/execute-tracks`-only reflection protocol. The file's structure:

- §1 YouTrack MCP requirement (gating)
- §2 When it runs (applicability — currently names `/execute-tracks` exclusively)
- §3 What counts as a worth-recording issue (in-scope / out-of-scope examples)
- §4 Cost-benefit gate
- §5 Per-session cap (3-issue ceiling)
- §6 Reflection procedure (10 steps; Step 2 is the commit-clean check, Step 4 scans the session, Steps 7-9 present and create issues)
- §7 Issue body template (carries the `**Phase:**` field)
- §8 Type guide / §9 Severity guide / §10 What the agent must not do

Three locations branch on session-type:

1. **§2 applicability sentence** — currently "As the last step of every `/execute-tracks` session...". Becomes: "As the last step of every session run by a calling skill that opts into reflection (`/execute-tracks` today; `/migrate-workflow` from Track 5)..."

2. **§6 Step 2 commit-clean check** — currently mandatory ("Run `git status --porcelain`; the working tree must be clean."). For `migrate-workflow` this check would always fail by design (migration leaves the worktree dirty for user review). Branch on `session-type`: skip the check when `migrate-workflow`.

3. **§7 issue body template** — currently `**Phase:** state-0 | phase-a | phase-b | phase-c | phase-4`. Becomes: `**Phase:** state-0 | phase-a | phase-b | phase-c | phase-4 | migrate-workflow`.

The `migrate-workflow` SKILL needs a new Step 6 that invokes the parameterized reflection. The Skill lives at `.claude/skills/migrate-workflow/SKILL.md` and the new step lands AFTER Step 5 (final summary, renamed from today's Step 6 under Track 4's renumber-down) and BEFORE the session ends.

## Plan of Work

Edit `self-improvement-reflection.md` first.

1. Add a `## Inputs` block at the very top (before §1) listing the parameter:
   ```
   ## Inputs
   - `session-type` — `execute-tracks` (default; existing behavior) or `migrate-workflow` (new; Track 5 of YTDB-XXX).
   ```
   Document the three branching clauses inline.

2. Rewrite §"When it runs" intro to drop the `/execute-tracks`-exclusive phrasing. Add a small "Applicability by session-type" subsection naming the phase identifiers each session-type contributes (`/execute-tracks` → state-0/phase-a/phase-b/phase-c/phase-4; `/migrate-workflow` → migrate-workflow).

3. Add the in-scope examples sub-bullets for migration-shaped frictions (ambiguous classification rules, missing replay patterns, halt-and-ask conditions firing without docs, edge cases in the renames tracker). The existing in-scope list stays untouched; the new bullets are additive.

4. In §"Reflection procedure" Step 2, add a one-line branch: "*If `session-type = migrate-workflow`, skip this check — migration intentionally leaves the worktree dirty for user review. The Source line points at `HEAD` (pre-migration); `git rev-parse HEAD` and `git rev-parse --abbrev-ref HEAD` in Step 3 still run.*"

5. In §"Issue body template", extend the `**Phase:**` allowed values list to include `migrate-workflow`.

Then edit `.claude/skills/migrate-workflow/SKILL.md` to add Step 6:

```markdown
## Step 6 — Self-improvement reflection

Invoke the reflection protocol at `.claude/workflow/self-improvement-reflection.md`
with `session-type=migrate-workflow`. The protocol handles its own MCP-reachability
check and end-of-session contract; nothing else fires after it returns.
```

Update Step 0's umbrella task list to include "Self-improvement reflection" as task 6 (under Track 4's renumber, the umbrella task numbering tracks the new step numbers).

Verify after both edits: a fresh `/migrate-workflow` invocation that exhausts the queue and reaches Step 5's final summary then enters reflection, which scans the session, presents candidates (or "No improvements proposed"), and ends the session.

## Concrete Steps
<!-- Phase A placeholder — decomposition writes the step roster here. -->

## Episodes
<!-- Continuous-log. Empty at Phase 1. -->

## Validation and Acceptance

After Track 5 lands:

- `self-improvement-reflection.md` opens with an `## Inputs` block naming the `session-type` parameter.
- §"When it runs" no longer reads as `/execute-tracks`-exclusive.
- §"Reflection procedure" Step 2 carries a conditional clause skipping the commit-clean check for `migrate-workflow`.
- The §"Issue body template" `**Phase:**` field accepts `migrate-workflow` as a value.
- `.claude/skills/migrate-workflow/SKILL.md` carries a new Step 6 that invokes reflection with `session-type=migrate-workflow`.
- A `/migrate-workflow` session with YouTrack MCP reachable produces a reflection prompt at session end (Step 6) listing 0..3 candidate issues.
- A `/migrate-workflow` session with YouTrack MCP unreachable produces the "YouTrack MCP unreachable — self-improvement reflection skipped" notice and ends the session.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery paths once steps are decomposed. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Empty at Phase 1. -->

## Interfaces and Dependencies

**In-scope files:**
- `.claude/workflow/self-improvement-reflection.md` (top-of-file Inputs block, §"When it runs" intro, in-scope examples sub-bullets, §"Reflection procedure" Step 2 conditional, §"Issue body template" `**Phase:**` field)
- `.claude/skills/migrate-workflow/SKILL.md` (new Step 6 + Step 0 umbrella task update)

**Out-of-scope files:**
- `.claude/workflow/conventions.md` (Track 1)
- `.claude/skills/create-plan/SKILL.md`, `.claude/skills/edit-design/SKILL.md` (Track 2)
- `.claude/workflow/workflow-drift-check.md` (Track 3)
- Everything else in `.claude/workflow/` and `.claude/skills/` not named above.

**Inter-track dependencies:**
- **Depends on:** Track 4 (`migrate-workflow/SKILL.md` step numbering and the existence of a Step 5 final summary that this track appends Step 6 after — numbers reflect Track 4's renumber-down).
- Indirectly depends on Track 1 (`conventions.md` §1.6 grounds the workflow-SHA-stamp vocabulary the reflection step's issue bodies may reference) — but does not edit that file.

**External interfaces:**
- `mcp__youtrack__*` MCP tools (already used by reflection). No new tools required.
- `git rev-parse HEAD` and `git rev-parse --abbrev-ref HEAD` (already used in Step 3 of reflection). No new git invocations.
