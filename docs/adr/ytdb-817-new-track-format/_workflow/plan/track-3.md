# Track 3: Update writer SKILLs (`/create-plan`, `/review-plan`, inline-replanning, track-skip)

## Purpose / Big Picture

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Update every writer SKILL that authors or amends a per-track file or the root index: `/create-plan` SKILL gets the new track-file template; `/review-plan` is a thin wrapper to verify; `inline-replanning.md` cases 2–6 get section-name updates against the new shape (case 1 was already rewired in Track 2 step 6's atomic switch + Phase C iter-1 review-fix WI3); `track-skip.md` picks up section-name updates. **The episode-writer rewire that originally lived here as step 5 (`step-implementation.md` sub-step 7, `episode-format-reference.md`, and the D12 canonical write order across every Progress writer) has moved into Track 2's atomic shape switch (D13)** so the writer logic, the on-disk shape, and this branch's own track files all change in one commit. Track 3 is now strictly the writer-SKILL section-name + template update — the orchestrator-driven episode-write logic is already on the new shape by the time Track 3 starts, and `inline-replanning.md` case 1 is already on the new shape.

## Progress
- [x] 2026-05-16T17:25Z [ctx=safe] Review + decomposition complete

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->

## Decision Log
<!-- Continuous-log. Empty at Phase 1. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
- [x] Technical: PASS at iteration 2 (5 findings — T1/T2/T3 should-fix applied; T4/T5 suggestions deferred to decomposition step-ordering and step-description annotation; T6 suggestion applied as Interfaces and Dependencies clarification)

## Context and Orientation

The writer SKILLs today still emit the legacy five-section shape (`## Description` / `## Progress` / `## Reviews completed` / `## Base commit` / `## Steps`) and reference legacy section names in their step-by-step prose. Track 2 has already landed the new spec (`conventions.md`, `conventions-execution.md` §2.1, `planning.md`, `design-document-rules.md`) describing the 14-section ExecPlan template, has renamed the on-disk directory and prose terminology, and via its atomic shape switch (D13) has migrated this branch's own track files plus rewired the episode-writer (`step-implementation.md` sub-step 7 + `episode-format-reference.md` + the D12 canonical write order across every Progress writer). Track 2 step 6 atomic switch + Phase C iter-1 review-fix WI3 also rewired `inline-replanning.md` **case 1** (new-track inline-replan) to author the 14-section shape. The remaining gap: `create-plan/SKILL.md` still embeds the legacy template; `review-plan/SKILL.md`, `track-skip.md`, and `inline-replanning.md` cases 2–6 still reference legacy section names (`## Description`, `## Reviews completed`, `## Steps`); and two line-broken "step file" residuals escaped Track 2 step 2's terminology rename in `inline-replanning.md` (cases 3 and 6). Track 3 closes those gaps.

## Plan of Work

Update each writer SKILL in turn. `/create-plan` SKILL is the largest edit — Step 4's embedded track-file template block rewrites from the legacy 5-section shape to the 14-section shape; the verbatim Phase-1 template body stays embedded in `create-plan/SKILL.md` (which is durable) rather than pointing at `conventions-execution.md` §2.1 or `design.md`. (`conventions-execution.md` §2.1 carries the section list, the lifecycle table, and one realistic example body per section; `design.md` carries the verbatim ready-to-paste template block but is ephemeral — removed by the Phase 4 cleanup commit — so it cannot be a durable pointer target.) The same step also rewrites the prose blockquote-shape references to `**What/How/Constraints/Interactions**` subsections (`create-plan/SKILL.md:117–123, :163–164`) to name the four 14-section homes (`## Purpose / Big Picture` for the intro; `## Context and Orientation` / `## Plan of Work` / `## Interfaces and Dependencies` for the detail subsections). Step 1b's `mkdir` line was already updated to `_workflow/plan/` in Track 2 Phase C iter-1 review-fix WC1. `/review-plan` SKILL update covers the section-name ref on line 42 AND the residual `tracks/` path-token on line 57 that escaped Track 2 step 1's path-token sweep — Track 3 is editing this file anyway, so picking up the residual costs near zero. `inline-replanning.md` § Updating plan and track files updates cases 2–6 for the new section names — three `## Description` mentions on lines 153 (step 4 sub-agent prompt directive), 241 (case 2), 249 (case 3); cases 3 and 6 also receive a small fix for two line-broken "step file" residuals on lines 248–249 and 270–271 that escaped Track 2 step 2's terminology rename. Case 1 was already rewired in Track 2 step 6 + Phase C iter-1 WI3. `track-skip.md` updates the three `## Description` mentions (lines 60, 67, 89) AND rewrites the two legacy `**What/How/Constraints/Interactions**` blockquote-shape references on lines 47 and 88 to the four 14-section homes.

## Concrete Steps

1. Update `inline-replanning.md` cases 2–6: sweep `## Description` refs on lines 153 (step 4 sub-agent prompt directive), 241 (case 2), 249 (case 3) to name the appropriate 14-section homes; fix the two line-broken "step file" residuals on lines 248–249 and 270–271 to "track file" per Track 2 step 2's terminology rename. Case 1 (line 217+) is already on the new shape (Track 2 step 6 + Phase C iter-1 WI3). — `risk: low (default — workflow-doc text sweep)`  [ ]
2. Update `track-skip.md`: sweep the three `## Description` refs on lines 60, 67, 89 to name the appropriate 14-section homes; rewrite the two legacy `**What/How/Constraints/Interactions**` blockquote-shape references on lines 47 and 88 to name the four 14-section homes (`## Purpose / Big Picture` for the intro; `## Context and Orientation` / `## Plan of Work` / `## Interfaces and Dependencies` for the detail subsections). — `risk: low (default — workflow-doc text sweep)`  [ ]
3. Update `review-plan/SKILL.md`: sweep the one `## Description` ref on line 42 to name `## Purpose / Big Picture` (since the description-equivalent intro now lives there); fix the residual `tracks/` path-token on line 57 to `plan/` per Track 2 step 1's path rename invariant. — `risk: low (default — workflow-doc text sweep)`  [ ]
4. Update `create-plan/SKILL.md`: rewrite Step 4's embedded track-file template body from the legacy 5-section shape (`## Description` / `## Progress` / `## Reviews completed` / `## Steps`, lines ~222–262) to the canonical 14-section shape — section list and lifecycle from `conventions-execution.md` §2.1, verbatim template body kept embedded here (durable); sweep all legacy `## Description` prose refs (lines 116, 122, 165, 222, 229, 255, 257) and `**What/How/Constraints/Interactions**` blockquote-shape references (lines 117–123, 163–164) to name the four 14-section homes. — `risk: low (default — workflow-doc template update)`  [ ]

## Episodes
<!-- Continuous-log. Empty until Phase B writes the first step block. -->

## Validation and Acceptance
<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery

All four steps are markdown-only text sweeps with deterministic substitutions (legacy section names → 14-section names; legacy blockquote shape → four 14-section homes; line-broken "step file" → "track file"; `tracks/` path-token → `plan/`). Re-running any step against an already-updated file is a no-op because the legacy targets no longer exist on disk.

Per-step recovery:

- **Step 1 (`inline-replanning.md`)** — single-file edit, single commit. On failure, `git reset --hard HEAD~1` reverts; re-attempt the sweep targeting the same lines (153, 241, 248–249, 249, 270–271). If grep on the legacy tokens (`## Description`, line-broken "step\nfile") shows zero matches before the retry, the previous attempt landed and the apparent failure is a false alarm — verify the diff before reverting.
- **Step 2 (`track-skip.md`)** — same shape as step 1. On failure, revert + re-attempt. Verify line numbers haven't drifted (lines 47, 60, 67, 88, 89) since other tracks haven't touched this file in this session.
- **Step 3 (`review-plan/SKILL.md`)** — smallest step, two-line fix. On failure, revert + re-attempt. The `tracks/` path-token grep (`grep -n 'tracks/' .claude/skills/review-plan/SKILL.md`) is the fastest pre-/post-check.
- **Step 4 (`create-plan/SKILL.md`)** — largest step, embedded template rewrite + prose sweep. On failure, revert + re-attempt. Validation: post-edit `grep -nE '## Description|## Reviews completed|## Steps$|\*\*What\*\*|\*\*How\*\*|\*\*Constraints\*\*|\*\*Interactions\*\*' .claude/skills/create-plan/SKILL.md` must return zero matches for the template body section (legacy refs inside surrounding prose context may remain if they describe historical state, but the template body and forward-looking prose must be clean). Two-failure rule (per `step-implementation-recovery.md` § Two-Failure Rule) applies — if the implementer fails twice, escalate.

Cross-step recovery: a failure mid-track (e.g., step 2 fails after step 1 lands) leaves the workflow tooling in a partial state. This is acceptable since (a) the writer SKILLs are not invoked during Track 3 execution itself; (b) inline-replanning.md case 1 is already on the new shape (Track 2 step 6), so any inline-replan during Track 3 routes through case-1 (new track) without hitting the not-yet-updated cases 2–6; (c) cases 4 and 6 are intrinsically safe (case 4 pauses-and-asks; case 6 deletes the file). Steps 2 and 3 (not-yet-started or mid-execution tracks) carry transient inconsistency risk but Phase 2 structural-review catches a malformed track file on the next session.

## Artifacts and Notes
<!-- Cross-step artifacts only. Empty until cross-step content surfaces. -->

## Interfaces and Dependencies

**In-scope files**: `.claude/skills/create-plan/SKILL.md`, `.claude/skills/review-plan/SKILL.md`, `.claude/workflow/inline-replanning.md`, `.claude/workflow/track-skip.md`.

**Out-of-scope**: reader workflow docs and section-name references (Track 4), sub-agent prompts (Track 4), `workflow.md` startup (Track 4). The episode-writer rewire (`step-implementation.md` sub-step 7, `episode-format-reference.md`, and the D12 canonical write order across every Progress writer) is handled by Track 2 step 6's atomic shape switch (D13), not by this track.

**Template consistency**: the track-file template in `create-plan/SKILL.md` Step 4 and the per-track shape `inline-replanning.md` case 1 produces must be byte-identical to the section list, lifecycle, and per-section examples in `conventions-execution.md` §2.1 (Track 2's responsibility). The two writers emit a track-file shape that conforms to that canonical spec; they each carry their own verbatim template body (durable embed), not a cross-file include of `design.md` (which is ephemeral and removed in Phase 4 cleanup).

**Markdown-only changes**: no Java, no Maven, no tests.

**Inter-track dependencies**:
- **Depends on Track 1** (workflow-review triage) for correct Phase C dispatch on this track's diff, and **Track 2** (spec + atomic shape switch per D13 — the episode-writer rewire that originally lived here as step 5 already landed in Track 2 step 6, and `inline-replanning.md` case 1 already landed in Track 2 Phase C iter-1 review-fix WI3). By the time Track 3 starts, the episode-writer in `step-implementation.md` sub-step 7 already follows the multi-section convention, this branch's own track files are already on the new shape, and `inline-replanning.md` case 1 already authors the 14-section shape; this track only updates the writer SKILLs that PRODUCE per-track files (vs. the orchestrator logic that WRITES into them, which is Track 2's territory now).
- Does not share `step-implementation.md` with Track 4 — Track 2 step 6 handled the writer half; Track 4 owns the reader half. Track 3 does not touch `step-implementation.md` at all.
- `inline-replanning.md` is a writer (it authors per-track files during replans), so cases 2–6 stay in Track 3 alongside the other writer SKILLs. Track 4's reader-pass does not include `inline-replanning.md` per Track 4's In-scope list (track file `## Interfaces and Dependencies`).

## Base commit
a7b3b8d96a51e6023177cb25a4bc10bbaa6ba422
