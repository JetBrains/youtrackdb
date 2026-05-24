# Track 5: Self-improvement reflection for migration

## Purpose / Big Picture
After this track lands, every `/migrate-workflow` session ends with the same self-improvement reflection that closes `/execute-tracks` — process feedback on the migration itself feeds the `dev-workflow` YouTrack queue.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Parameterize `self-improvement-reflection.md` to accept a session-type input (`execute-tracks` or `migrate-workflow`) that controls the commit-clean check, the phase identifier in the issue body, the applicability sentence in §"When it runs", and the in-scope examples in §"What counts as a worth-recording issue". Then wire a final reflection step into the rewritten `migrate-workflow` SKILL that invokes it with `session-type=migrate-workflow`. Skip rules (YouTrack MCP unreachable, no work happened) carry through unchanged.

## Progress
- [x] 2026-05-24T14:23Z [ctx=info] Review + decomposition complete
- [x] 2026-05-24T15:47Z [ctx=safe] Step 1 complete (commit 6ff21361a8)
- [x] 2026-05-24T15:52Z [ctx=info] Step 2 complete (commit bbac5771b1)
- [x] Step implementation
- [x] 2026-05-24T16:15Z [ctx=safe] Track-level code review iteration 1 complete (1/3 iterations)
- [x] 2026-05-24T16:19Z [ctx=info] Track-level code review iteration 2 complete (2/3 iterations)
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->

## Decision Log
<!-- Continuous-log. Empty at Phase 1. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
- [x] Technical: PASS at iteration 1 (6 findings — T1, T2, T3 should-fix absorbed into Step 1 / Step 2 decomposition; T4, T5, T6 suggestion absorbed as polish; no blockers).

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

The `migrate-workflow` SKILL needs a new Step 6 that invokes the parameterized reflection. The Skill lives at `.claude/skills/migrate-workflow/SKILL.md` and the new step lands AFTER Step 5 (final summary, renamed from today's Step 6 under Track 4b's renumber-down) and BEFORE the session ends.

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

Update Step 0's umbrella task list to include "Self-improvement reflection" as task 8 (post-Track-4b the umbrella has 7 slots, so reflection lands as task 8; the SKILL's new H2 step is Step 6, sitting after today's Step 5 final summary).

Verify after both edits: a fresh `/migrate-workflow` invocation that exhausts the queue and reaches Step 5's final summary then enters reflection, which scans the session, presents candidates (or "No improvements proposed"), and ends the session.

Phase A decomposition lands two steps. Step 1 sweeps `self-improvement-reflection.md` in one pass, applying the parameterization plus all ten session-type-dependent surfaces in a single commit so a partial revert never leaves the doc internally inconsistent. Step 2 wires the SKILL to call the parameterized reflection; ordering is Step 1 then Step 2 because Step 2's call site references the `## Inputs` block and the `session-type=migrate-workflow` value Step 1 introduces. Step 2 also fixes Step 5's trailing end-of-session line so the new Step 6 actually fires. Phase A technical review surfaced seven session-type-dependent surfaces beyond the three named above (top-of-file intro at line 3; §"When it runs" body enumeration of phase steps at lines 60–65; skip-conditions list at lines 67–74; `**Source session:**` template literal at line 487; `## Reproduction context` second `**Phase:**` enumeration at line 497; plus the `## Inputs` block's calling-convention contract and the in-scope friction examples sub-bullets); Step 1 covers them all.

## Concrete Steps

1. Parameterize `.claude/workflow/self-improvement-reflection.md` to accept a `session-type` input (`execute-tracks` default; `migrate-workflow` new). Add the `## Inputs` block at the top documenting the parameter, the default value, and the calling-convention (a calling skill names the value in its invocation line; when omitted the document behaves as `session-type=execute-tracks`). Sweep every session-type-dependent surface in one pass: line 3 intro generalized to name both callers; §"When it runs" intro plus body enumeration of phase steps (lines 46–65) plus skip-conditions list (lines 67–74) lifted to session-type-agnostic phrasing; new in-scope examples sub-bullets under §"What counts as a worth-recording issue" for migration-shaped frictions (ambiguous classification rules, missing replay patterns, halt-and-ask conditions firing without docs, edge cases in the renames tracker); §"Reflection procedure" Step 2 conditional clause skipping the commit-clean check when `session-type=migrate-workflow` (the Source line points at HEAD pre-migration; `git rev-parse HEAD` and `git rev-parse --abbrev-ref HEAD` in Step 3 still run); §"Issue body template" `**Phase:**` field (line 486) and `**Source session:**` template literal (line 487) extended to carry `migrate-workflow`; `## Reproduction context` `**Phase:**` enumeration (line 497) extended in parallel. — `risk: medium` (override: ten edit sites in one file requiring mutual consistency; coordinate-edit risk warrants focal-point Phase C review across the workflow-consistency dimension)  [x] commit: 6ff21361a8
2. Wire the reflection invocation into `.claude/skills/migrate-workflow/SKILL.md` as a new "## Step 6 — Self-improvement reflection" section sitting after today's Step 5 (Final summary). Step 6's body opens with "Mark Step 0's umbrella task 8 (`Self-improvement reflection`) as `in_progress`", invokes the reflection protocol at `.claude/workflow/self-improvement-reflection.md` with `session-type=migrate-workflow`, mirrors the existing-caller friction-seeding paragraph (migration-shaped frictions: ambiguous classification rules, missing replay patterns, halt-and-ask conditions firing without docs, edge cases in the renames tracker), closes with "mark umbrella task 8 as `completed`", and notes that the protocol owns its own MCP-reachability check and end-of-session contract. Replace Step 5's trailing "Then end the session." line at `SKILL.md:645` with "Then proceed to Step 6." so the session-end moves from Step 5 to Step 6. Update Step 0's umbrella task list at `SKILL.md:25-31` to add task 8 as the eighth bullet, mirroring the existing slot phrasing: "Self-improvement reflection: invoke `.claude/workflow/self-improvement-reflection.md` with `session-type=migrate-workflow`". — `risk: low` (default: single-file isolated change, markdown-only, provable behavior preservation)  [x] commit: bbac5771b1

## Episodes

### Step 1 — commit 6ff21361a8, 2026-05-24T15:47Z [ctx=safe]
**What was done:** Parameterized `.claude/workflow/self-improvement-reflection.md` with a `session-type` input (`execute-tracks` default; `migrate-workflow` new). Swept ten session-type-dependent surfaces in one atomic commit via `steroid_apply_patch`: new `## Inputs` block at the top documenting the parameter and its calling convention, line-3 intro generalised to name both callers, §"When it runs" intro plus phase-step body enumeration plus skip-conditions list lifted to session-type-agnostic phrasing, four migration-shaped friction sub-bullets under §"What counts as a worth-recording issue", §"Reflection procedure" Step 2 conditional clause that skips the commit-clean check for `migrate-workflow`, §"Issue body template" `**Phase:**` field and `**Source session:**` template literal extended to carry `migrate-workflow`, and `## Reproduction context` `**Phase:**` enumeration extended in parallel.

**What was discovered:** §"When it runs" carries an auto-resume / Track Pre-Flight gate clause that is genuinely `/execute-tracks`-specific (no analogue in `/migrate-workflow`); the implementer scoped it inside an explicit `session-type=execute-tracks` parenthetical rather than deleting it. A skip-condition bullet that names the missing prerequisite was split into two per-session-type sub-clauses because `/migrate-workflow`'s startup prerequisites differ — no plan file; instead, "no `_workflow/` artifacts to migrate". Both refinements went beyond the literal Plan-of-Work enumeration but were necessary to keep the document coherent under both callers. Forward guidance for Step 2: the SKILL's new Step 6 line MUST read explicitly "Invoke `.claude/workflow/self-improvement-reflection.md` with `session-type=migrate-workflow`." Phrasing that omits the parameter falls back to the `execute-tracks` default and silently breaks the four migration-aware clauses.

**Key files:**
- `.claude/workflow/self-improvement-reflection.md` (modified)

**Critical context:** The `**Source session:**` template literal in the issue body template now renders as a two-line alternation, one line per session-type. An issue triager reading a rendered body sees exactly one of the two lines populated per issue.

### Step 2 — commit bbac5771b1, 2026-05-24T15:52Z [ctx=info]
**What was done:** Appended a new "## Step 6 — Self-improvement reflection" section to `.claude/skills/migrate-workflow/SKILL.md`. Step 6's body opens with the umbrella task 8 in-progress mark, invokes the reflection protocol at `.claude/workflow/self-improvement-reflection.md` with explicit `session-type=migrate-workflow`, seeds the four migration-shaped friction examples (ambiguous classification rules, missing replay patterns, halt-and-ask conditions firing without docs, edge cases in the renames tracker), notes that the protocol owns its own MCP-reachability check and end-of-session contract, and closes with the umbrella task 8 completion mark. Redirected Step 5's trailing line from "Then end the session." to "Then proceed to Step 6." Extended Step 0's umbrella task list from seven slots to eight by appending task 8 in the same slot-phrasing as the existing entries.

**What was discovered:** Advisory line numbers in the step description (`SKILL.md:645`, `SKILL.md:25-31`) matched the post-Track-4b file state byte-for-byte, so content-anchored verification landed on the same anchors the description named. Step 1's forward guidance about citing the explicit `session-type=migrate-workflow` parameter was followed in three places: the new Step 6 body, the Step 0 umbrella list entry, and the commit message. Any of the three surfaces gives a future reader the same contract.

**Key files:**
- `.claude/skills/migrate-workflow/SKILL.md` (modified)

## Validation and Acceptance

After Track 5 lands:

- `self-improvement-reflection.md` opens with an `## Inputs` block naming the `session-type` parameter.
- §"When it runs" no longer reads as `/execute-tracks`-exclusive.
- §"Reflection procedure" Step 2 carries a conditional clause skipping the commit-clean check for `migrate-workflow`.
- The §"Issue body template" `**Phase:**` field accepts `migrate-workflow` as a value.
- `.claude/skills/migrate-workflow/SKILL.md` carries a new Step 6 that invokes reflection with `session-type=migrate-workflow`.
- A `/migrate-workflow` session with YouTrack MCP reachable produces a reflection prompt at session end (Step 6) listing 0..3 candidate issues.
- A `/migrate-workflow` session with YouTrack MCP unreachable produces the "YouTrack MCP unreachable — self-improvement reflection skipped" notice and ends the session.

Per-step acceptance:

- **Step 1** (Parameterize `self-improvement-reflection.md`): given the post-Step-1 doc, when an `/execute-tracks` phase doc loads reflection without passing `session-type`, then the default `execute-tracks` applies and the document reads correctly for all five `/execute-tracks` phase identifiers (state-0, phase-a, phase-b, phase-c, phase-4); when `/migrate-workflow` loads reflection with `session-type=migrate-workflow`, then §"Reflection procedure" Step 2 skips the commit-clean check, both the §"Issue body template" `**Phase:**` field and the `## Reproduction context` `**Phase:**` enumeration accept `migrate-workflow`, the `**Source session:**` template renders `<YYYY-MM-DD> /migrate-workflow <adr-dir-name>`, §"When it runs" reads correctly without any `/execute-tracks`-exclusive phrasing, and the in-scope examples list carries the four migration-shaped friction sub-bullets.
- **Step 2** (Wire `migrate-workflow/SKILL.md` Step 6): given the post-Step-2 SKILL, when a `/migrate-workflow` session reaches the end of Step 5 (Final summary), then Step 5 ends with "Then proceed to Step 6." and Step 6 invokes reflection with `session-type=migrate-workflow`; when reflection's MCP-reachability check passes, then the user is prompted with 0..3 candidate issues and the session ends via reflection's end-of-session contract; when MCP is unreachable, then the "YouTrack MCP unreachable — self-improvement reflection skipped" notice prints and the session ends; Step 0's umbrella task list contains exactly eight slots, with task 8 named "Self-improvement reflection".

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery

- **Step 1** — `git revert <SHA>` restores `.claude/workflow/self-improvement-reflection.md` to its pre-Step-1 state. The ten edit sites land in one commit, so the revert removes the `## Inputs` block, the generalized §"When it runs" prose, the migration-shaped in-scope examples sub-bullets, the conditional clause in §"Reflection procedure" Step 2, and the `migrate-workflow` values in both `**Phase:**` enumerations plus the session-type-aware `**Source session:**` template literal in one operation. Existing `/execute-tracks` callers — none of which passes a `session-type` argument today — keep working through the document because every conditional clause defaults to `session-type=execute-tracks`; the revert drops the conditionals without breaking any caller.
- **Step 2** — `git revert <SHA>` restores `.claude/skills/migrate-workflow/SKILL.md` to its pre-Step-2 state. The new Step 6 section disappears, Step 5's trailing line returns to "Then end the session.", and Step 0's umbrella task list drops back to seven slots. A `/migrate-workflow` session run on the reverted SKILL hits Step 5's "Then end the session." path and exits without reflection — behaviour identical to pre-Step-2.

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
- **Depends on:** Track 4b (`migrate-workflow/SKILL.md` step numbering and the existence of a Step 5 final summary that this track appends Step 6 after — numbers reflect Tracks 4a/4b's renumber-down).
- Indirectly depends on Track 1 (`conventions.md` §1.6 grounds the workflow-SHA-stamp vocabulary the reflection step's issue bodies may reference) — but does not edit that file.

**External interfaces:**
- `mcp__youtrack__*` MCP tools (already used by reflection). No new tools required.
- `git rev-parse HEAD` and `git rev-parse --abbrev-ref HEAD` (already used in Step 3 of reflection). No new git invocations.

## Base commit
7e93e7535bddfd57cd168a0291a014c31a0efd91
