# Track 2: Stamp writers

## Purpose / Big Picture
After this track lands, every newly created `_workflow/**` artifact carries a line-1 workflow-SHA stamp at the moment of creation — no manual step, no separate helper invocation.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Update `/create-plan` SKILL and `edit-design` SKILL to emit the stamp at every artifact-creation site. Four sites total: `implementation-plan.md`, `plan/track-N.md`, and `design.md` (all created in `/create-plan` Step 4 via `Write`); `design-mechanics.md` (created either in `/create-plan` Step 4 on dual-seed or in `edit-design` under `length-trigger-crossing` mid-life). `edit-design phase1-creation` stamps as a backstop when invoked directly outside `/create-plan` (idempotent over an already-stamped file). Direct mutations through `edit-design` leave the stamp untouched. `design-mutations.md` is deliberately excluded: append-only log, no replay, no stamp.

## Progress
- [x] 2026-05-22T19:59Z [ctx=info] Review + decomposition complete
- [x] 2026-05-23T02:42Z [ctx=safe] Step 1 complete (commit f8d4317713)
- [x] 2026-05-23T02:48Z [ctx=safe] Step 2 complete (commit 493348e20d)
- [x] 2026-05-23T02:52Z [ctx=info] Step 3 complete (commit 20d8fb718f)
- [x] 2026-05-23T02:52Z [ctx=info] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
- Technical review iteration 1 flagged that I4 (direct mutations preserve the line-1 stamp byte-for-byte) rests on prose discipline alone; Step 3's mechanical-checks pipeline (`design-mechanical-checks.py`) carries no line-1 stamp-presence assertion. Out of scope for Track 2's writer-side coverage. Recorded as a candidate follow-up `dev-workflow` issue: add `head -1 <design_path> | grep -qE '<!-- workflow-sha: [0-9a-f]{40} -->'` to the script for kinds other than `phase1-creation` and `length-trigger-crossing`. Phase A self-improvement reflection picks this up.
- The header of `edit-design/SKILL.md` (lines 14–17) claims `phase1-creation` is the canonical creator of `design.md`, while the `/create-plan` flow writes it directly via the planning-transition step's template. The idempotency guard added in the next step must cover both invocation paths: `target=both` from `/create-plan` (file already stamped) and direct invocation outside `/create-plan` (file unstamped or pre-stamped). See Episodes §Step 1.
- The ephemeral-identifier pre-commit gate's `Step N` regex matches SKILL-internal procedural-step headers (a file's own "Step 4" prose), not just plan-file Track/Step labels. Rewrite the SKILL prose to use a descriptive name for the step instead of carving a gate exception. See Episodes §Step 1.
- The same pre-commit gate's `\b[A-Z]{1,3}-?[0-9]+\b` regex also matches Markdown element names like `H1`/`H2`/`H3`. These resolve to neither Forbidden category (Track / Step labels, review finding IDs, iteration counters, named invariants by label only) and pass through under the Allowed list's class/element-name coverage. Treat the regex hit on `H1` as a passing match per the gate's inspect-then-rewrite contract — no rewrite needed. See Episodes §Step 2.

## Decision Log
- 2026-05-23T02:42Z [Step 1] Resolved the Plan of Work `(a)/(b)` choice for `design-mechanics.md` dual-seed coverage to path (b): the dual-seed write continues to route through `edit-design phase1-creation` with `target=both`. The next step inherits the full dual-seed stamp obligation; no fourth fenced template lands in `create-plan`. See Episodes §Step 1.

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
- [x] Technical: PASS at iteration 2 (4 findings — T1 + T2 should-fix VERIFIED via track-file amendments to `## Plan of Work` and `## Context and Orientation`; T3 suggestion DEFERRED as a `dev-workflow` follow-up candidate logged under `## Surprises & Discoveries`; T4 suggestion VERIFIED via the non-uniformity paragraph in `## Context and Orientation`).

## Context and Orientation

Two SKILL files own all the artifact-creation sites:

- `.claude/skills/create-plan/SKILL.md` — Step 4 owns the literal `Write` of four templates in this order: `implementation-plan.md`, each `plan/track-N.md`, `design.md`, and (when seeded with `target=both`) `design-mechanics.md`. The templates are embedded markdown blocks within Step 4's prose; the writer is the agent invoking `Write` with the rendered template. The `edit-design` SKILL header at lines 14-17 claims `phase1-creation` is the canonical creator of `design.md`, but today's `/create-plan` flow writes it directly; Track 2 stamps the `design.md` template in `create-plan` Step 4 alongside the other three so the four-site coverage is symmetric.
- `.claude/skills/edit-design/SKILL.md` — Step 1's `phase1-creation` paragraph (`:117-134`) also seeds `design.md` and (with `target=both`) `design-mechanics.md` when invoked directly outside `/create-plan` (manual upgrade of a drafted design). Step 1 has no narrative branch for `length-trigger-crossing` today; that kind sits in the discipline table at line 87 and is name-dropped from inside the `phase1-creation` paragraph at line 133, with the splitting procedure itself undocumented. Track 2 introduces a new Step 1 paragraph for `length-trigger-crossing` between the `phase4-creation` paragraph (`:136-143`) and the `design-sync` cross-reference (`:145-146`), covering both the split procedure and the stamp prepend on the freshly-created `design-mechanics.md`. (`design-mutations.md` is created by Step 7's first-append branch but is deliberately not stamped; see the intro.)

The stamp value comes from `conventions.md` §1.6(b): the paired test-and-fallback idiom. Line 1 reads `git log -1 --format=%H HEAD -- .claude/workflow .claude/skills` into `WORKFLOW_SHA`; line 2 falls back to `git rev-parse HEAD` when the log returns empty. The pair runs once per `create-plan` session and once per `edit-design` invocation; multiple artifacts created in the same session share the resulting SHA. Track 1's episode marks both lines as byte-for-byte writer input, with the fallback covering the fresh-repo and moved-paths case.

The stamp goes on line 1, immediately before the H1. For `implementation-plan.md` the line-2 H1 is `# <Feature Name>`; for `design.md` it is `# <Feature Name> — Design`; for `plan/track-N.md` it is `# Track N: <title>`. The template body in each SKILL needs a `<!-- workflow-sha: $SHA -->` line prepended.

One non-uniformity to expect: when `length-trigger-crossing` fires later in the design's life, the `$WORKFLOW_SHA` computed at trigger time can differ from the `design.md` stamp written at original phase1-creation. The new `design-mechanics.md` carries a later SHA than its `design.md` sibling, and the plan's stamps go non-uniform. The drift gate's no-drift normalization (Track 3, per D11) collapses the divergence on the next clean gate run; the migration's lockstep advance (Track 4b) reunifies stamps end-of-migration. The asymmetry is expected and absorbed downstream.

## Plan of Work

Edit `create-plan/SKILL.md` first. In Step 4, add a one-line preamble computing `$WORKFLOW_SHA` once via the §1.6(b) paired idiom (copy both lines byte-for-byte: the path-scoped `git log` first, the `git rev-parse HEAD` empty-output fallback second), with the value reused for every artifact created in this `/create-plan` session. Then prepend `<!-- workflow-sha: $WORKFLOW_SHA -->` (followed by a newline) above the H1 in each fenced template block Step 4 emits via `Write`:

1. `implementation-plan.md` template at `create-plan/SKILL.md:183-232` (H1 stays `# <Feature Name>`).
2. `plan/track-N.md` template at `:250-332` (H1 stays `# Track N: <title>`).
3. `design.md` template at `:341-361` (H1 stays `# <Feature Name> — Design`).
4. `design-mechanics.md` template — for the dual-seed path. Today's Step 4 carries no fenced `design-mechanics.md` template; the dual-seed write runs through `edit-design phase1-creation` with `target=both`. Decomposition picks one of two shapes: (a) add a fourth fenced template block to `create-plan` Step 4 for the dual-seed case so all four templates land symmetrically; or (b) keep the dual-seed write routed through `edit-design phase1-creation`, which then stamps via the idempotency-guarded directive below. Pick the simpler of the two at decomposition time.

Then edit `edit-design/SKILL.md`. In Step 1's `phase1-creation` paragraph (`:117-134`), add an idempotency-guarded stamp directive. When invoked directly (caller is not `/create-plan`, so the file has not been pre-stamped), fetch `$WORKFLOW_SHA` via the §1.6(b) paired idiom and prepend it to the seeded file(s); when invoked as a follow-up to `/create-plan` (the design template already starts with a workflow-sha line), skip the prepend. Idempotency rule: `head -1 <design_path> | grep -qE '<!-- workflow-sha: [0-9a-f]{40} -->'` returning zero means skip, non-zero means prepend. Symmetric guard for `design-mechanics.md`.

Add a new Step 1 paragraph for `length-trigger-crossing` between the `phase4-creation` paragraph (`:136-143`) and the `design-sync` cross-reference (`:145-146`). The paragraph documents the split procedure (today missing from Step 1 prose: move long-form mechanism content from `design.md` into the new `design-mechanics.md`, with section names matching across the two files) and includes the stamp prepend on the freshly-created `design-mechanics.md` (`$WORKFLOW_SHA` computed via the §1.6(b) idiom at trigger time).

Step 7 (the `design-mutations.md` writer) is deliberately NOT touched. The file is excluded from stamping. Add a short note in Step 7's prose explaining the exclusion so future SKILL readers do not re-add a stamp out of mistaken uniformity.

Cross-cutting: add a one-paragraph "Stamp" note near the top of each SKILL explaining that stamps are written at creation only; direct-mutation kinds (`content-edit`, `section-add`, `section-remove`, etc.) leave the stamp untouched. The note nails down invariant I4 from the plan.

Sanity-test: after the edits land, an integration probe (one Bash session) creates a tiny fake `_workflow/` plan directory through the SKILL flow and verifies every produced file has the stamp on line 1 matching the regex `^<!-- workflow-sha: [0-9a-f]{40} -->$`.

Per-step sequencing: Step 1 ships the `create-plan` SKILL changes (preamble + three existing templates stamped + dual-seed-path resolution + Stamp note). Step 2 ships the `edit-design` `phase1-creation` idempotency guard, the Step 7 exclusion note, and the top-of-file Stamp note. Step 3 ships the new `edit-design` Step 1 `length-trigger-crossing` paragraph. Each step's local verification runs an integration probe against the SKILL it changes: a `/create-plan` smoke for Step 1; an `edit-design phase1-creation` smoke against both stamped and unstamped targets for Step 2; a fake length-trigger crossing for Step 3.

## Concrete Steps

1. Update `create-plan/SKILL.md` Step 4: add the one-line `$WORKFLOW_SHA` preamble (§1.6(b) paired idiom), prepend `<!-- workflow-sha: $WORKFLOW_SHA -->` above the H1 in the three existing fenced templates (`implementation-plan.md`, `plan/track-N.md`, `design.md`), apply path (a) or (b) for `design-mechanics.md` dual-seed coverage, and add the cross-cutting Stamp note near the top of the SKILL. — risk: low (default: markdown SKILL prose edits, no HIGH or MEDIUM triggers)  [x]  commit: f8d4317713
2. Update `edit-design/SKILL.md` `phase1-creation` paragraph plus Step 7 plus top-of-file Stamp note: add the idempotency-guarded stamp directive in the `phase1-creation` paragraph (with the §1.6(b) idiom and the `head -1 | grep -qE '<!-- workflow-sha: [0-9a-f]{40} -->'` presence check), add a short exclusion note to Step 7 explaining why `design-mutations.md` is not stamped, and add the cross-cutting Stamp note near the top of the SKILL. — risk: low (default: markdown SKILL prose edits, no HIGH or MEDIUM triggers)  [x]  commit: 493348e20d
3. Add new `edit-design/SKILL.md` Step 1 `length-trigger-crossing` paragraph between the `phase4-creation` paragraph (`:136-143`) and the `design-sync` cross-reference (`:145-146`), documenting the split procedure (move long-form mechanism content from `design.md` into the new `design-mechanics.md`, section names matching across the two files) plus the stamp prepend on the freshly-created `design-mechanics.md` (`$WORKFLOW_SHA` computed via the §1.6(b) idiom at trigger time). — risk: low (default: markdown SKILL prose edits, no HIGH or MEDIUM triggers)  [x]  commit: 20d8fb718f

## Episodes

### Step 3 — commit 20d8fb718f, 2026-05-23T02:52Z [ctx=info]
**What was done:** Added a `length-trigger-crossing` paragraph to `.claude/skills/edit-design/SKILL.md` between the `phase4-creation` paragraph and the `design-sync` cross-reference. The paragraph documents the split procedure: move every long-form mechanism walk-through, full state-machine table, exhaustive worked example, and file:line citation out of `design.md` and into the freshly-created `design-mechanics.md`; keep Overview / Core Concepts / per-section TL;DR + mechanism overview + edge cases + references footer in `design.md`; section names match byte-for-byte across the two files so `Mechanics: design-mechanics.md §"<…>"` links and the plan / track-file `**Full design**` refs resolve in either file. The paragraph then links to `design-document-rules.md` § Length-triggered split into `design-mechanics.md` for the canonical underlying rule. The paragraph then stamps the freshly-created `design-mechanics.md` via the same idempotency-guarded directive used in the `phase1-creation` paragraph: the §1.6(a1) presence check followed by the §1.6(b) paired idiom when the presence check returns non-zero. The new file is unstamped at creation, so the prepend always fires on the first invocation; the guard kept for symmetry tolerates re-invocation against an already-split pair. The paragraph closes by naming the expected SHA asymmetry (`design-mechanics.md`'s stamp can sit later than its `design.md` sibling's), pointing at the drift-gate no-drift normalization and the per-branch migration as the two downstream collapses, and asserting that `design.md`'s existing stamp stays byte-for-byte intact under §1.6(a)'s position-preservation contract.

**What was discovered:** none

**What changed from the plan:** none

**Key files:**
- `.claude/skills/edit-design/SKILL.md` (modified)

**Critical context:** none

### Step 2 — commit 493348e20d, 2026-05-23T02:48Z [ctx=safe]
**What was done:** Added three blocks to `.claude/skills/edit-design/SKILL.md`. The `phase1-creation` paragraph gained a per-path idempotency-guarded stamp directive: for each path the kind touches (`design_path`; also `design_mechanics_path` when `target=both`) run the §1.6(a1) presence check `head -1 <path> | grep -qE '<!-- workflow-sha: [0-9a-f]{40} -->'`; zero exit skips the prepend, non-zero exit computes `$WORKFLOW_SHA` via the §1.6(b) paired idiom (copied byte-for-byte from `conventions.md`) and prepends `<!-- workflow-sha: $WORKFLOW_SHA -->` above the H1. `$WORKFLOW_SHA` is computed at most once per invocation so sibling files start life with matching stamps when both need a stamp. The review-log append step (Step 7) gained a one-paragraph exclusion note explaining why `design-mutations.md` is deliberately not stamped (append-only by contract, replay-immune by construction, listed as exclusion in `conventions.md` §1.6(f)). A top-of-file Stamp-discipline blockquote note (placed right after the lead paragraph, mirroring `create-plan/SKILL.md`'s same-position note from Step 1) nails down I4 for readers entering the SKILL cold: stamps are written at creation only, every other mutation kind preserves the stamp byte-for-byte, `design-mutations.md` and Phase 4 final artifacts are not stamped, `conventions.md` §1.6 is the single source of truth.

**What was discovered:** The pre-commit ephemeral-identifier gate's `\b[A-Z]{1,3}-?[0-9]+\b` regex matches the Markdown structural-element name `H1`. `H1`/`H2`/`H3` are HTML/Markdown element names — they resolve to neither category in the rule file's Forbidden list (Track labels, Step labels, review finding IDs, iteration counters, named invariants by label only) and pass through under the Allowed list's class/element-name-style identifier coverage. The same shape surfaces in pre-existing prose (e.g., `create-plan/SKILL.md` uses `H1` repeatedly), so the gate's inspect-then-rewrite contract treats `H1` as a passing match. No rewrite was needed.

**What changed from the plan:** none

**Key files:**
- `.claude/skills/edit-design/SKILL.md` (modified)

**Critical context:** none

### Step 1 — commit f8d4317713, 2026-05-23T02:42Z [ctx=safe]
**What was done:** Added a one-line `$WORKFLOW_SHA` preamble at the start of the planning-transition step in `create-plan/SKILL.md`, copying the §1.6(b) paired test-and-fallback idiom byte-for-byte (path-scoped `git log` over `.claude/workflow` and `.claude/skills`, with `git rev-parse HEAD` fallback for fresh repos and moved paths). Prepended `<!-- workflow-sha: $WORKFLOW_SHA -->` above the H1 in each of the three fenced templates the planning-transition step emits via `Write`: `implementation-plan.md`, `plan/track-N.md`, and `design.md`. Added a cross-cutting "Stamp discipline" note immediately after the house-style block nailing down I4 (direct-mutation kinds preserve the line-1 stamp; only creation, migration replay, and no-drift normalization write it). Resolved the dual-seed `design-mechanics.md` coverage to path (b): the dual-seed write keeps routing through `edit-design phase1-creation` with `target=both`, and the idempotency-guarded stamp directive lands there in the next step. No fourth fenced template added.

**What was discovered:** The header of `edit-design/SKILL.md` (lines 14–17) claims `phase1-creation` is the canonical creator of `design.md`, but the `/create-plan` flow writes it directly via the planning-transition step's template. The asymmetry is informational for the next step's idempotency guard — the guard must cover both the `target=both` path (called from `/create-plan` with the already-stamped `design.md`) and the direct-invocation path (called outside `/create-plan` against unstamped or pre-stamped targets). Separately, the ephemeral-identifier pre-commit gate's `Step N` regex catches SKILL-internal procedural-step references; the implementer rewrote the offending prose without losing meaning rather than carve out an exception.

**What changed from the plan:** Plan of Work offered path (a) (a fourth fenced template in `create-plan` for `design-mechanics.md`) or path (b) (route the dual-seed write through `edit-design phase1-creation`). The implementer chose (b). The next step inherits the full dual-seed stamp obligation; no further-track impact.

**Key files:**
- `.claude/skills/create-plan/SKILL.md` (modified)

**Critical context:** none

## Validation and Acceptance

After Track 2 lands:

- A fresh `/create-plan` session produces stamped `implementation-plan.md`, `plan/track-N.md` files, and `design.md` (plus `design-mechanics.md` when the planner picks the dual-seed shape), each carrying `<!-- workflow-sha: <40-char SHA> -->` on line 1.
- A fresh `edit-design phase1-creation` invocation, when the caller is not `/create-plan` and the file is unstamped, prepends the stamp to `design.md`. On an already-stamped file the idempotency guard skips the write. The same idempotent rule applies for `design-mechanics.md` when `target=both`.
- `design-mutations.md` carries NO line-1 stamp after any number of `edit-design` mutations. `head -1 design-mutations.md` returns the H1 `# Design Mutations Log`, not a workflow-sha comment.
- A direct-mutation kind (e.g., `section-add`) on an already-stamped `design.md` leaves the line-1 stamp byte-for-byte identical (verifiable via `head -1` before and after).
- The `length-trigger-crossing` mutation, when it creates `design-mechanics.md`, prepends the stamp to the new file via the new Step 1 paragraph's directive. The fresh SHA may differ from the `design.md` stamp; the drift gate (Track 3, D11) and the migration (Track 4b) absorb the resulting non-uniformity.

Per-step acceptance:

- **Step 1** (When `/create-plan` runs Step 4): the resulting `implementation-plan.md`, `plan/track-N.md`, and `design.md` each have a line matching `<!-- workflow-sha: [0-9a-f]{40} -->` as line 1; the H1 sits on line 2. `design-mechanics.md` coverage is deferred to Step 2 by the Decision Log's path-(b) choice. The SKILL's new top-of-file Stamp note states that stamps are written at creation only.
- **Step 2** (When `edit-design phase1-creation` runs): given a `design.md` whose line 1 does NOT match the workflow-sha regex, the resulting file has the stamp on line 1; given a file whose line 1 already matches the regex, line 1 is byte-for-byte identical before and after. Same rule for `design-mechanics.md` when `target=both`. Step 7's prose carries a one-sentence note saying `design-mutations.md` is excluded from stamping. The SKILL's new top-of-file Stamp note states the same creation-only discipline as Step 1.
- **Step 3** (When `edit-design length-trigger-crossing` runs): the newly created `design-mechanics.md` has a workflow-sha stamp on line 1 computed via the §1.6(b) paired idiom at trigger time; the H1 sits on line 2; section names in `design-mechanics.md` match the corresponding sections in `design.md` (the split-procedure prose specifies the section-name-matching rule).

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery

- **Step 1** — `git revert <SHA>` restores `create-plan/SKILL.md` to its pre-stamping state. No production data, schema, or runtime contract depends on the change; reverting the single commit is sufficient.
- **Step 2** — `git revert <SHA>` restores `edit-design/SKILL.md` to its pre-Step-2 state. The idempotency guard's behavior is purely SKILL prose; reverting removes the guard, the Step 7 exclusion note, and the cross-cutting Stamp note in one operation.
- **Step 3** — `git revert <SHA>` restores `edit-design/SKILL.md` to its pre-Step-3 state. The new Step 1 paragraph for `length-trigger-crossing` is removed; previously-stamped artifacts on the branch keep their stamps (the writer-side removal does not retroactively unstamp anything).

## Artifacts and Notes
<!-- Continuous-log (rare). Empty at Phase 1. -->

## Interfaces and Dependencies

**In-scope files:**
- `.claude/skills/create-plan/SKILL.md` — Step 4 preamble (one-line `$WORKFLOW_SHA` computation), Step 4 templates for `implementation-plan.md`, `plan/track-N.md`, `design.md`, and (when dual-seed is in scope) `design-mechanics.md`; plus a Stamp-discipline note.
- `.claude/skills/edit-design/SKILL.md` — Step 1 `phase1-creation` paragraph (idempotency-guarded stamp prepend), new Step 1 `length-trigger-crossing` paragraph (split procedure + stamp prepend on the new `design-mechanics.md`), Step 7 first-append (exclusion note); plus a Stamp-discipline note.

**Out-of-scope files:**
- `.claude/workflow/conventions.md` (Track 1)
- `.claude/workflow/workflow-drift-check.md` (Track 3)
- `.claude/skills/migrate-workflow/SKILL.md` (Tracks 4a and 4b)
- `.claude/workflow/self-improvement-reflection.md` (Track 5)
- Phase 4 artifact creation in `.claude/workflow/prompts/create-final-design.md` — Non-Goal (D3).

**Inter-track dependencies:**
- **Depends on:** Track 1 (stamp format definition in `conventions.md` §1.6 — the SKILL bodies link there rather than restating the format).
- Tracks 3 and 4 read stamps from artifacts produced by this track. Until Track 2 lands, those readers fall back to fork-point semantics for every artifact (since none are stamped). Track 2 unlocks the SHA-aware behavior end-to-end.

**External interfaces:**
- `git log -1 --format=%H HEAD -- .claude/workflow .claude/skills` is the only new git invocation introduced. It runs at SKILL-invocation time, not at file-write time, so it is read-only with respect to the artifacts.

## Base commit
f97da6b26092fb5ae608af0c29905e1a03e79c1a
