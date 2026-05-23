# Handoff: Phase A — Track 3 decomposition pending

**Paused:** 2026-05-23
**Phase:** A
**Context level at pause:** warning (30%)
**Branch:** inplace-worflow-migration
**HEAD:** d935d3fb65 "Apply pre-flight amendments before Track 3" (pre-handoff; the handoff commit lands the track-file fixes + this file together)
**Unpushed:** 0 (will become 1 after the handoff commit)

## Durable artifacts on disk

- `docs/adr/inplace-worflow-migration/_workflow/implementation-plan.md` — Track 2 block carries the Pre-Flight CONTINUE strategy-refresh line (commit `d935d3fb65`).
- `docs/adr/inplace-worflow-migration/_workflow/plan/track-3.md` — Phase A technical review iteration 1 fixes (T1–T10) applied via `steroid_apply_patch` (5 hunks) plus one follow-up `Edit` to the `## Context and Orientation` Resolutions framing. `## Outcomes & Retrospective` carries `Technical: PASS at iteration 2 (10 findings, 10 accepted)`. `## Surprises & Discoveries` carries two cross-cutting items: the T1 cross-track mirror (track-4a.md line 40 needs the same `design.md` → `conventions.md §1.6(h)` anchor correction) and the workflow.md residue follow-up. The fixes are uncommitted in the working tree; the handoff commit lands them.

## Pending decision

None — no user input is required. The decomposition is the orchestrator's next sub-step; the verbatim text below is what the next session writes to `track-3.md` without re-derivation.

## Verbatim re-present text — decomposition draft

### `## Concrete Steps` content (replace the Phase A placeholder)

```
## Concrete Steps

1. Rewrite Detection bash block + intro paragraph + add §1.6 cross-reference note in `workflow-drift-check.md` (byte-copy from `conventions.md` §1.6(h); drop `git fetch origin develop` + `origin/develop` references; replace develop-worktree re-invocation language; add the §Detection cross-reference paragraph citing §1.6(c), §1.6(h), §1.6(a1)) — risk: low (default: workflow-machinery markdown edits; no HIGH or MEDIUM triggers)  [ ]
2. Rewrite Resolutions section in `workflow-drift-check.md` — three prompt-template string substitutions ("commits on develop touch" → "commits in your branch's range touch"; "since fork point `<short-FORK>`" → "since stamp base `<short-BASE_SHA>`"; "from a develop worktree" → "from this worktree"), Migrate-now in-branch wording, Defer sub-section `$FORK` → `$BASE_SHA` and worktree-instruction update — risk: low (default: workflow-machinery markdown edits; no HIGH or MEDIUM triggers)  [ ]
3. Add no-drift normalization sub-step in `workflow-drift-check.md` (sed-based stamp rewrite + `git diff -U0` `@@ -1` hunk-header guard + `git status --porcelain` cross-check + `git checkout -- <paths>` restore-on-mismatch + auto-commit) — risk: low (default: workflow-machinery markdown edits; no HIGH or MEDIUM triggers)  [ ]
4. Tighten Skip conditions to active-plan scope (D13) + review After-the-choice Remote-authoritative re-entry note — risk: low (default: workflow-machinery markdown edits; no HIGH or MEDIUM triggers)  [ ]
```

### Sequencing paragraph (append at the end of `## Plan of Work`)

> **Step sequencing.** All four steps modify `.claude/workflow/workflow-drift-check.md` so they are sequential, not parallel. Step 1 lands the Detection bash rewrite + intro paragraph + cross-reference note. Step 2 rewrites the Resolutions section (prompt template + Migrate-now + Defer). Step 3 adds the new no-drift normalization sub-section with the full diff-shape guard. Step 4 tightens Skip conditions and reviews the After-the-choice note (likely no edit per T10).

### Per-step acceptance (append to `## Validation and Acceptance` before the Move 3 placeholder)

```
**Per-step acceptance:**
- Step 1: `workflow-drift-check.md` § Detection contains the §1.6(h) Phase 1 walk block byte-for-byte; the intro paragraph carries no `git fetch origin develop` claim and no develop-worktree re-invocation claim; a §Detection cross-reference paragraph cites §1.6(c), §1.6(h), and §1.6(a1).
- Step 2: The Resolutions prompt template uses HEAD-relative wording (no "commits on develop", no "fork point", no "develop worktree"); the Migrate-now branch instructs in-branch `/migrate-workflow`; the Defer sub-section uses `$BASE_SHA` and in-branch wording.
- Step 3: A new no-drift normalization sub-section names `sed -i "1s/.*/<!-- workflow-sha: $BASE_SHA -->/"`, the `git diff -U0` `@@ -1` hunk-header guard, the `git status --porcelain` cross-check, the `git checkout -- <paths>` restore-on-mismatch path, and the auto-commit.
- Step 4: Skip conditions scope all three skips to the active plan dir (per `conventions.md §1.6(g)`); the After-the-choice Remote-authoritative re-entry note carries either no edit (if no worktree-switch claim was found) or the documented tweaks recorded in the step episode.
```

### Recovery paragraph (replace the Phase A placeholder in `## Idempotence and Recovery`)

> **Per-step recovery:** All four steps are markdown edits to one file (`.claude/workflow/workflow-drift-check.md`). If a step fails mid-implementation, run `git restore .claude/workflow/workflow-drift-check.md` to drop uncommitted changes and re-apply the planned edits. No durable state, no DB or WAL interaction, no concurrency concerns. All four steps are idempotent — re-applying the same edit set produces the same final file state. Phase B's implementer governs the standard revert path on failure (`git reset --hard HEAD`) per `implementer-rules.md`.

## Resume notes

- **Do NOT redo:** the Pre-Flight gate (Track 2's strategy-refresh CONTINUE line is committed in `d935d3fb65`); the Phase A reviews (technical review PASS at iteration 2 is already in `## Outcomes & Retrospective` of `track-3.md` once the handoff commit lands); the T1–T10 fixes (already in the working tree, committed alongside this handoff).
- **No risk or adversarial review was planned.** Complexity assessment landed on Moderate-no-architectural-no-critical-path so only Technical ran. The decomposition lists 4 steps all at `risk: low`.
- **Next action on resume:** apply the verbatim text above to the named `track-3.md` sections via one `steroid_apply_patch` (multi-site); append the D12 Progress entry (`- [x] <ISO> [ctx=<level>] Review + decomposition complete`) and flip the `Review + decomposition` checkbox in `## Progress` from `[ ]` to `[x]` in the same patch; commit + push (`Phase A review and decomposition for Track 3`); delete this handoff file + PAUSED marker + MEMORY.md cross-reference per `mid-phase-handoff.md §Resume protocol`; commit the deletion (`Resume Phase A and complete Track 3 decomposition`); end Phase A per the §Phase A Completion mandatory session boundary.
- **Cross-track impact for Track 4a Pre-Flight** is recorded in `track-3.md` `## Surprises & Discoveries` — Track 4a Phase A Pre-Flight Panel 1 will read it from there; no action needed at resume of this Phase A pause.
