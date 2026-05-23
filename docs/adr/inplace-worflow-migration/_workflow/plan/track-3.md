# Track 3: SHA-aware drift check

## Purpose / Big Picture
After this track lands, the `/execute-tracks` startup drift gate reads workflow-SHA stamps from the active plan's `_workflow/**` artifacts, compares against HEAD (not `origin/develop`), and routes the user to an in-branch migration. On no-drift with non-uniform stamps, it normalizes every stamp in the active plan to the fold result and commits the change in a separate commit.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Rewrite the Detection section of `workflow-drift-check.md` to walk every `_workflow/**` artifact in the active plan's `_workflow/` directory (D13), classify each as stamped or unstamped, and apply the two-phase rule: any unstamped artifact short-circuits to "drift detected" with no fold; when every artifact in the active plan is stamped, fold the SHA set pairwise through `git merge-base` to derive `BASE_SHA` and run `git log $BASE_SHA..HEAD` against workflow paths. Drop the `git fetch origin develop` step — comparison is purely against HEAD (D10). Update the "Migrate now" resolution text to instruct an in-branch re-invocation of `/migrate-workflow`. On no-drift with non-uniform stamps, normalize every artifact's stamp in the active plan to the fold result and create a separate commit (D11). Skip conditions tighten scope to the active plan (Track 3 Plan of Work); the Defer/Suppress flows stay structurally the same.

## Progress
- [x] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

- [x] 2026-05-23 [ctx=safe] Review + decomposition complete
- [x] 2026-05-23T04:32Z [ctx=safe] Step 1 complete (commit 6ad91336b7)
- [x] 2026-05-23T04:37Z [ctx=safe] Step 2 complete (commit 79e5d7c6ce)

## Surprises & Discoveries

- Phase A technical review (iteration 1) found that `design.md` §"Stamp range computation" carries a non-canonical unanchored regex (`grep -oE '[0-9a-f]{40}'`) that `conventions.md` §1.6(a1) explicitly rejects (false-positives on H1 lines containing a 40-hex run). The canonical anchored block lives at `conventions.md` §1.6(h). Cross-track impact: `track-4a.md` (line 40) also points at `design.md` as the byte-source; the same anchor correction needs to land in `track-4a.md` before Track 4a Phase B starts. Recorded here so Track 4a Pre-Flight Panel 1 picks it up.
- The same review surfaced a cross-file follow-up: `workflow.md` § "What to do before ending a session" (lines 416–427) carries a `cd ../develop` + worktree-switch instruction that contradicts Track 3's in-branch flow. Track 3 fixes the in-file Defer section in `workflow-drift-check.md` but the cross-file passage in `workflow.md` is out of scope per the track boundary; recorded here as a follow-up.
- The Phase 1 walk block is byte-identical between `conventions.md` §1.6(h) and `workflow-drift-check.md` §Detection after Step 1's landing; a one-liner `diff` between the extracted blocks verifies the byte-copy contract §1.6(h) commits to. Track 4a's Step 2 must copy the same block byte-for-byte; Phase C review of whichever track lands second should run the `diff` against the other copy. See Episodes §Step 1.

## Decision Log
<!-- Continuous-log. Empty at Phase 1. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective

- [x] Technical: PASS at iteration 2 (10 findings, 10 accepted — 1 blocker, 5 should-fix, 4 suggestions). Iteration 1 surfaced a canonical-source mismatch (Plan of Work pointed at `design.md §"Stamp range computation"` whose bash uses the unanchored regex `conventions.md` §1.6(a1) rejects); the canonical block at `conventions.md` §1.6(h) is the byte-source. Iteration 1 also expanded the in-file scope from "Migrate-now wording only" to cover the prompt template, the Defer sub-section, the intro paragraph, and a tightened diff-shape primitive for the no-drift normalization. Cross-track impact (track-4a.md mirrors the same anchor mistake) and a cross-file follow-up (`workflow.md` § "What to do before ending a session") are recorded in `## Surprises & Discoveries`.

## Context and Orientation

`.claude/workflow/workflow-drift-check.md` (~220 lines) is the turn-1 gate `/execute-tracks` runs after the Branch Divergence Check and before the handoff scan (workflow.md § Startup Protocol step 3a). Today's gate is branch-wide; the rewrite scopes it to the active plan's `_workflow/` directory (D13). Today's Detection section computes:

```bash
git fetch origin develop 2>/dev/null || true
git rev-parse --verify origin/develop >/dev/null 2>&1 || exit
FORK="$(git merge-base origin/develop HEAD)"
test -n "$FORK" || exit
git log --reverse --oneline "$FORK..origin/develop" -- .claude/workflow/ .claude/skills/ | head -10
```

The new derivation reads from `conventions.md` §1.6(h)'s Phase 1 walk block byte-for-byte (the canonical anchored regex); `design.md` §"Stamp range computation" carries the narrative explainer but uses a non-canonical regex variant that `conventions.md` §1.6(a1) explicitly rejects. Phase 1 (the walk) is shared with Track 4a: enumerate every ephemeral artifact under the active plan's `_workflow/**` (the plan dir the caller resolved at startup per `conventions.md` §1.6(g) and §1.2), classify each as stamped (line-1 stamp parses) or unstamped (no stamp on line 1), and collect `STAMPED_SHAS` and `UNSTAMPED_FILES`. Phase 2 (the decision) is caller-specific: the drift check signals drift unconditionally when `UNSTAMPED_FILES` is non-empty (no fold, no `git log`); when everything is stamped, it folds `STAMPED_SHAS` pairwise through `git merge-base` to derive `BASE_SHA` and runs `git log $BASE_SHA..HEAD` against workflow paths (no `git fetch` — the branch is a self-contained capsule per D10). The Phase 1 bash block is shared byte-for-byte with Track 4a; keeping them text-identical is what makes the drift check and the migration agree on what "drift" means.

A new behavior fires when Phase 2 determines no drift but `STAMPED_SHAS` contains more than one distinct SHA: normalize every artifact's line-1 stamp to `BASE_SHA` (the fold result) and create a separate commit titled along the lines of `Normalize workflow-sha stamps to <short-BASE_SHA>` (D11). The next gate's fold input is then a single-element set, and the gate becomes O(1). Branches whose stamps are already uniform skip the normalization silently.

The Resolutions section needs wording updates across the prompt template (three develop-relative strings — "commits on develop", "fork point `<short-FORK>`", "from a develop worktree" — that go wrong under D10's HEAD-as-upper-bound rule), the "Migrate now" branch (today's "Switch to a `develop` worktree (e.g., `cd ../develop`) and run `/migrate-workflow <branch>` there" becomes "Run `/migrate-workflow` from this worktree."), and the Defer sub-section (today's `$FORK` and `cd ../develop` references switch to `$BASE_SHA` and the in-branch instruction). The intro paragraph (today's lines 14–26) explains the dropped `git fetch origin develop` and the develop-worktree re-invocation, so it gets rewritten in lockstep.

The Skip conditions section is structurally unchanged, but scopes tighten to the active plan (D13): the "Active plan's `_workflow/` doesn't exist" skip (was branch-wide "No `_workflow/` subtree") checks just the resolved plan dir; the "Plan complete plus Phase 4 active" skip reads only the active plan's `implementation-plan.md` (was every plan on the branch); the "Empty diff" skip reads from the new range and stays the same in spirit.

## Plan of Work

Replace the Detection bash block by byte-copying `conventions.md` §1.6(h)'s Phase 1 walk block. `design.md` §"Stamp range computation" stays as the narrative explainer but is NOT the byte-source — its walk block uses the unanchored `[0-9a-f]{40}` regex `conventions.md` §1.6(a1) explicitly flags as non-canonical (see Surprises & Discoveries for the cross-track propagation). The new block has no `git fetch` and no `git rev-parse --verify origin/develop`; comparison is purely against HEAD.

Rewrite the intro paragraph (today's lines 14–26) to stay consistent with the new Detection. Replace "one `git log` against the post-fetch `origin/develop` tip" with "one `git log` over the active plan's stamp-derived range against HEAD"; drop the entire "Branch Divergence Check's `git fetch` targets the branch's upstream … so this gate fetches `origin develop` independently" paragraph; replace "re-invoke from a `develop` worktree" with "re-invoke from this worktree". Keep the skill-assumes-fresh-session framing (the contract still holds) but disconnect it from the worktree-switch rationale.

Update the Resolutions prompt template (today's lines 112–127) on three load-bearing strings: "commits on develop touch" → "commits in your branch's range touch" (or equivalent HEAD-relative phrasing); "since fork point `<short-FORK>`" → "since stamp base `<short-BASE_SHA>`"; "from a develop worktree" → "from this worktree". The `$FORK` variable references in the surrounding prose also become `$BASE_SHA` so the prompt and the implementation agree on which SHA is reported.

Add the no-drift normalization sub-step after the Phase 2 decision. When `STAMPED_SHAS` contains more than one distinct SHA and Phase 2 reports no drift, rewrite every artifact's line-1 stamp to `BASE_SHA` via `sed -i "1s/.*/<!-- workflow-sha: $BASE_SHA -->/"` and create a separate commit. Before the commit, verify the diff touches only line 1 of stamped artifacts and nothing else: run `git diff -U0 -- <stamped-artifact-paths>` and confirm every hunk header starts with `@@ -1` (line-1 only), plus `git status --porcelain` shows no modifications outside the stamped artifacts. On mismatch, run `git checkout -- <stamped-artifact-paths>` to restore the pre-normalization state, abort the normalization, and surface a clear error. This preserves Invariant I5's "either all stamps + commit, or no on-disk change" semantics.

Update the Resolutions section's "Migrate now" sub-section. Replace the two-worktree instruction with "Run `/migrate-workflow` from this worktree." Remove the standalone rationale paragraph about why the skill assumes a fresh session; the contract still holds, but the worktree-switch framing no longer applies. Keep the early-exit contract (end session before reaching `workflow.md § What to do before ending a session`).

Update the Defer sub-section (today's lines 158–179) to match. Rename `$FORK` to `$BASE_SHA` in the "seven-character abbreviation" derivation. Change "the same `cd ../develop` + `/migrate-workflow <branch>` instruction" to "the same in-branch `/migrate-workflow` instruction". `workflow.md § What to do before ending a session` (lines 416–427) carries a parallel passage that needs the same update but lies OUT-of-scope per the track boundary; the cross-file fix is recorded in Surprises & Discoveries as a follow-up.

Review the "After the choice" section's Remote-authoritative re-entry note for any worktree-switch claim that needs updating. The current text's only worktree-implying phrase is "re-invoke `/execute-tracks` in a fresh session", which is already worktree-agnostic. This bullet is review-only; the implementer confirms no edit is needed and notes that in the step episode.

Tighten the Skip conditions to the active plan (D13). Skip #1 ("No `_workflow/` subtree", was branch-wide `ls -d docs/adr/*/_workflow/ 2>/dev/null`) becomes "Active plan's `_workflow/` directory doesn't exist". Skip #2 ("Plan complete plus Phase 4 active") reads only the active plan's `implementation-plan.md` and drops the cross-plan AND-fold. Skip #3 ("Empty diff") stays in spirit, now reading from the new `$BASE_SHA..HEAD` range. The active plan dir is the `<dir>` argument the caller resolved at startup (see `conventions.md` §1.6(g) and §1.2); the gate reads this from session state, no new resolution logic lands in `workflow-drift-check.md`.

Add a one-line cross-reference note in the §Detection prose pointing readers at `conventions.md` §1.6(c) (range definition), §1.6(h) (Phase 1 walk byte-source), and §1.6(a1) (canonical parser regex). The `design.md` §"Stamp range computation" narrative remains a soft reference for context but not the byte-source for the bash block.

> **Step sequencing.** All four steps modify `.claude/workflow/workflow-drift-check.md` so they are sequential, not parallel. Step 1 lands the Detection bash rewrite + intro paragraph + cross-reference note. Step 2 rewrites the Resolutions section (prompt template + Migrate-now + Defer). Step 3 adds the new no-drift normalization sub-section with the full diff-shape guard. Step 4 tightens Skip conditions and reviews the After-the-choice note (likely no edit per T10).

## Concrete Steps

1. Rewrite Detection bash block + intro paragraph + add §1.6 cross-reference note in `workflow-drift-check.md` (byte-copy from `conventions.md` §1.6(h); drop `git fetch origin develop` + `origin/develop` references; replace develop-worktree re-invocation language; add the §Detection cross-reference paragraph citing §1.6(c), §1.6(h), §1.6(a1)) — risk: low (default: workflow-machinery markdown edits; no HIGH or MEDIUM triggers)  [x] commit: 6ad91336b7
2. Rewrite Resolutions section in `workflow-drift-check.md` — three prompt-template string substitutions ("commits on develop touch" → "commits in your branch's range touch"; "since fork point `<short-FORK>`" → "since stamp base `<short-BASE_SHA>`"; "from a develop worktree" → "from this worktree"), Migrate-now in-branch wording, Defer sub-section `$FORK` → `$BASE_SHA` and worktree-instruction update — risk: low (default: workflow-machinery markdown edits; no HIGH or MEDIUM triggers)  [x] commit: 79e5d7c6ce
3. Add no-drift normalization sub-step in `workflow-drift-check.md` (sed-based stamp rewrite + `git diff -U0` `@@ -1` hunk-header guard + `git status --porcelain` cross-check + `git checkout -- <paths>` restore-on-mismatch + auto-commit) — risk: low (default: workflow-machinery markdown edits; no HIGH or MEDIUM triggers)  [ ]
4. Tighten Skip conditions to active-plan scope (D13) + review After-the-choice Remote-authoritative re-entry note — risk: low (default: workflow-machinery markdown edits; no HIGH or MEDIUM triggers)  [ ]

## Episodes

### Step 1 — commit 6ad91336b7, 2026-05-23T04:32Z [ctx=safe]
**What was done:** Rewrote `.claude/workflow/workflow-drift-check.md` §Detection to drop the legacy `git fetch origin develop` + `merge-base origin/develop HEAD` + `FORK..origin/develop` bash and install the two-phase walk byte-copied from `conventions.md` §1.6(h). Phase 1 enumerates artifacts under the active plan's `_workflow/**` and classifies each as stamped or unstamped; Phase 2 (caller-specific) signals drift unconditionally on any unstamped artifact, otherwise folds the stamp set pairwise through `git merge-base` to derive `BASE_SHA` and runs `git log $BASE_SHA..HEAD` against workflow paths. Rewrote the intro paragraph to drop the duplicate-fetch rationale, the `origin/develop` framing, and the develop-worktree re-invocation language; the Migrate-now reference now points at in-branch re-invocation of `/migrate-workflow`. Added a §Detection cross-reference paragraph citing `conventions.md` §1.6(c) (range definition), §1.6(h) (Phase 1 walk byte-source), and §1.6(a1) (canonical parser regex), and explicitly demoted `design.md` §"Stamp range computation" to a soft reference (its walk uses the unanchored `[0-9a-f]{40}` regex §1.6(a1) rejects).

**What was discovered:** The Phase 1 walk in `conventions.md` §1.6(h) is byte-for-byte identical to the new Detection bash here (`diff` between the two extracted blocks differs only in the surrounding fence lines). This keeps the coordinated-edit cost on a future format change bounded to the writer sites enumerated in each track's §Interfaces and Dependencies — exactly the contract §1.6(h)'s opening paragraph commits to. The byte-identity property is verifiable mechanically by a one-liner `diff`, so Phase C review of Track 3 vs Track 4a can run the same `diff` once Track 4a's copy lands.

**Key files:**
- `.claude/workflow/workflow-drift-check.md` (modified)

### Step 2 — commit 79e5d7c6ce, 2026-05-23T04:37Z [ctx=safe]
**What was done:** Rewrote the Resolutions section of `.claude/workflow/workflow-drift-check.md` so every develop-relative reference now reads HEAD-relative. The prompt template reports "N commits in your branch's range … since stamp base `<short-BASE_SHA>`"; the `[migrate]` line says "run `/migrate-workflow` from this worktree"; the Migrate-now narrative collapses to a single instruction line ("Run `/migrate-workflow` from this worktree."); the Defer state shape carries `$BASE_SHA` and `<short-stamp-base-SHA>` (replacing `$FORK` and `<short-fork-SHA>`); the session-end summary references the in-branch `/migrate-workflow` rather than the `cd ../develop` pair. The Resolutions intro prose was updated in lockstep ("the short fork SHA" → "the short stamp-base SHA") so the prompt and the surrounding narrative stay consistent.

**What was discovered:** The Defer state-shape paragraph needed a small reflow after the `$FORK` → `$BASE_SHA` rename — the new variable name is longer and the natural ~70-char wrap shifted, producing one >100-char line. Reflowed in a follow-up patch within the same step. A name-length change in narrative prose can break paragraph wrap and Phase C reviewers may flag it; worth a re-check on similar future variable renames inside markdown narrative.

**What changed from the plan:** none.

**Key files:**
- `.claude/workflow/workflow-drift-check.md` (modified)

## Validation and Acceptance

After Track 3 lands:

- The Detection section of `workflow-drift-check.md` enumerates ephemeral artifacts under the active plan's `_workflow/**` (the plan dir the caller resolved at startup), reads line-1 stamps with the anchored regex `workflow-sha: [0-9a-f]{40}` (per `conventions.md` §1.6(a1)), classifies each artifact as stamped or unstamped, and applies the two-phase rule.
- Absent optional artifacts (specifically `design-mechanics.md` before the length trigger fires) are silently skipped by the §1.6(h) walk via the `2>/dev/null` suppression and contribute neither a stamp nor an unstamped flag; no caller-side branching needed.
- The Detection bash contains no `git fetch origin develop` and no `git rev-parse --verify origin/develop` — comparison is purely against HEAD.
- The "Migrate now" resolution wording no longer mentions `develop` worktree, `cd ../develop`, or any worktree switch.
- The "Empty diff" skip condition still triggers when `git log $BASE_SHA..HEAD -- .claude/workflow .claude/skills` is empty AND no artifact is unstamped.
- When the active plan carries any unstamped artifact, the gate signals drift unconditionally and routes to the three-resolution prompt (Migrate now / Defer / Suppress) — independent of whether `git log` would return commits.
- When every artifact in the active plan is stamped, the stamps fold to HEAD's latest workflow-touching commit, and the stamps are already uniform, the gate skips silently (empty range, no unstamped artifacts, no normalization needed).
- When every artifact in the active plan is stamped but the stamps are non-uniform yet the `$BASE_SHA..HEAD` range is empty for workflow paths, the gate normalizes every stamp in the active plan to `BASE_SHA` and creates a separate commit; the next gate run sees uniform stamps and a single-element fold input.
- The normalization step refuses to commit if the diff contains any change outside line-1 of stamped artifacts.
- The three-resolution prompt keeps its no-default contract.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

**Per-step acceptance:**
- Step 1: `workflow-drift-check.md` § Detection contains the §1.6(h) Phase 1 walk block byte-for-byte; the intro paragraph carries no `git fetch origin develop` claim and no develop-worktree re-invocation claim; a §Detection cross-reference paragraph cites §1.6(c), §1.6(h), and §1.6(a1).
- Step 2: The Resolutions prompt template uses HEAD-relative wording (no "commits on develop", no "fork point", no "develop worktree"); the Migrate-now branch instructs in-branch `/migrate-workflow`; the Defer sub-section uses `$BASE_SHA` and in-branch wording.
- Step 3: A new no-drift normalization sub-section names `sed -i "1s/.*/<!-- workflow-sha: $BASE_SHA -->/"`, the `git diff -U0` `@@ -1` hunk-header guard, the `git status --porcelain` cross-check, the `git checkout -- <paths>` restore-on-mismatch path, and the auto-commit.
- Step 4: Skip conditions scope all three skips to the active plan dir (per `conventions.md §1.6(g)`); the After-the-choice Remote-authoritative re-entry note carries either no edit (if no worktree-switch claim was found) or the documented tweaks recorded in the step episode.

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery

> **Per-step recovery:** All four steps are markdown edits to one file (`.claude/workflow/workflow-drift-check.md`). If a step fails mid-implementation, run `git restore .claude/workflow/workflow-drift-check.md` to drop uncommitted changes and re-apply the planned edits. No durable state, no DB or WAL interaction, no concurrency concerns. All four steps are idempotent — re-applying the same edit set produces the same final file state. Phase B's implementer governs the standard revert path on failure (`git reset --hard HEAD`) per `implementer-rules.md`.

## Artifacts and Notes
<!-- Continuous-log (rare). Empty at Phase 1. -->

## Interfaces and Dependencies

**In-scope files:**
- `.claude/workflow/workflow-drift-check.md` (Detection section bash + prose, Resolutions section "Migrate now" branch, "After the choice" Remote-authoritative note)

**Out-of-scope files:**
- `.claude/workflow/conventions.md` (Track 1)
- `.claude/skills/create-plan/SKILL.md`, `.claude/skills/edit-design/SKILL.md` (Track 2)
- `.claude/skills/migrate-workflow/SKILL.md` (Track 4a — reads the same bash block but owns its own copy in Step 2 after Track 4a's renumber-down)
- `.claude/workflow/branch-divergence-check.md` (the Remote-authoritative re-entry contract symmetry is explicitly NOT fixed here)

**Inter-track dependencies:**
- **Depends on:** Track 1 (stamp format + SHA computation rule defined in `conventions.md` §1.6).
- Coordinates with Track 4a on the bash block — both files carry a copy. The two copies must be byte-identical (verified by `diff` between the two extracted blocks during Phase C review of whichever track lands second).

**External interfaces:**
- `git merge-base` (pairwise fold), `git log $BASE_SHA..HEAD -- workflow paths`, `git rev-parse HEAD`, `sed -i` (stamp rewrite), `git add` + `git commit` (normalization commit). The `git fetch origin develop` and `git rev-parse --verify origin/develop` invocations are removed.

## Base commit
7b1b911701e6edea1a3a5d725698ba4a98b4af5d
