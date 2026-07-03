<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
verdicts:
  - {id: A1, verdict: VERIFIED}
  - {id: A2, verdict: VERIFIED}
  - {id: A3, verdict: VERIFIED}
  - {id: A4, verdict: VERIFIED}
  - {id: A5, verdict: VERIFIED}
  - {id: A6, verdict: VERIFIED}
  - {id: A7, verdict: VERIFIED}
  - {id: A8, verdict: VERIFIED}
  - {id: A9, verdict: VERIFIED}
  - {id: A10, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

# Adversarial gate-verification — Track 1: Style-machinery rework (iter2)

All ten iter-1 adversarial findings (A1–A10) are re-checked against the updated `track-1.md` and the live source files each fix cites. Every fix landed and introduced no regression. This is a pure-verdict pass: no new finding surfaced, so `findings: 0` and `## Findings` is empty.

## Findings

(none — pure-verdict pass)

## Evidence base

#### Verify A1: name-only drop-site grep misses exemplar-only site :185
- **Original issue**: the drop-site criterion for `review-workflow-writing-style.md` keyed on removed-rule names only, so line 185 — which names negative parallelism solely through its exemplar `"It's not X — it's Y"` — would be skipped, leaving a removed rule enforced at the reviewer's most-severe bucket.
- **Fix applied**: Plan of Work (`track-1.md:143`) and item 6 (`:222`) reframe the criterion to a case-insensitive grep of the six removed-rule names **and their canonical exemplar phrases** (`It's not X`, `In conclusion`, curly-quote glyphs), grep-derived rather than a hand-list, with the develop-state snapshot {29, 34, 38, 71, 89, 185, 188, 200} declared an authoritative floor the grep set must superset.
- **Re-check**:
  - Location: `.claude/agents/review-workflow-writing-style.md:185`; `track-1.md:143` and `:222`.
  - Current state: `grep -niE` on the six rule names returns {29, 34, 38, 71, 89, 188, 200} — line 185 is absent. The exemplar grep (`it's not`) returns {29, 71, 185}. The union is exactly the track's floor {29, 34, 38, 71, 89, 185, 188, 200}, and line 185 (`[Hard violations — "It's not X — it's Y" anti-pattern in load-bearing position...]`) is reachable only through the exemplar clause.
  - Criteria met: the reframed criterion provably covers the one site a names-only grep drops; the floor snapshot equals the observed names+exemplar union.
- **Regression check**: checked that the "surgical, keep kept-rule mentions" instruction still holds (line 29 mixes removed and kept rules → clause-level edit) — consistent, no contradiction. Clean.
- **Verdict**: VERIFIED

#### Verify A2: D2 hybrid missed edit-design's own gate-return parse contract
- **Original issue**: D2's hybrid narrative-plus-findings return got a consumer edit at `create-plan` Step-4b but not at `edit-design`'s own gate-return parse contract, which parses only a Verdict line plus a structural-findings list with no narrative slot.
- **Fix applied**: item 14 (`track-1.md:230`), Plan of Work (`:149`), and the `## Validation` A4 bullet (`:175`) extend `edit-design`'s parse contract to accept the hybrid on both output-path branches and route the narrative into the Step-6 author rework input.
- **Re-check**:
  - Location: `.claude/skills/edit-design/SKILL.md:664-676`; `track-1.md:149`, `:175`, `:230`.
  - Current state: lines 664-676 hold exactly the two-branch parse contract the finding describes — `output_path` absent parses the inline Verdict plus Structural findings list; `output_path` supplied parses Verdict plus counts then partial-fetches `## Structural findings`. Neither branch has a narrative slot, so the track's `~664-676` reference and "both output-path branches" characterization are accurate. Step 6 is confirmed as the author fix/rework loop (SKILL.md:659), so "route the narrative into the Step-6 author rework input" is coherent. All three track sites agree.
  - Criteria met: the parse-contract consumer is now covered on both branches, matching the live line span.
- **Regression check**: checked create-plan Step-4b consumer (item 21) and the Validation bullet for consistency — both name the same hybrid shape; no divergence. Clean.
- **Verdict**: VERIFIED

#### Verify A3: augmented promotion ran after the live commit → "same promote commit" unexecutable
- **Original issue**: the augmented promotion `cp`/`git add` was sequenced after the live Step-4 block's commit, so the two out-of-surface path classes could not ride the same promote commit.
- **Fix applied**: `## Idempotence and Recovery` (`track-1.md:188-198`) interleaves the augmented `cp "$STAGED_DIR/CLAUDE.md" ./CLAUDE.md` and `git add .claude/output-styles CLAUDE.md` **inside** the guarded block, after the four-prefix `git add` (`:548`) and before the `git diff --cached --quiet || git commit` (`:549`), with an explicit warning that running them after the block drops them from the PR.
- **Re-check**:
  - Location: `.claude/workflow/prompts/create-final-design.md:547-549`; `track-1.md:188-200`.
  - Current state: live lines are `547` `cp -r "$STAGED_DIR/.claude/." .claude/`, `548` `git add .claude/workflow .claude/skills .claude/agents .claude/scripts`, `549` `git diff --cached --quiet || git commit`. The track's line references and "block ends with :549" are exact. The interleaving lands both path classes in the same index as the four-prefix add, so a single promote commit carries them. The note that output-styles is already copied by the live `cp -r` (inside `.claude/`) while root `CLAUDE.md` is not is correct.
  - Criteria met: the "same promote commit" instruction is now executable; the divergence check is extended the same way (`:200`).
- **Regression check**: checked the idempotence claim (re-run yields empty index → short-circuit) — sound. Clean.
- **Verdict**: VERIFIED

#### Verify A4: post-rebase re-promotion would cp a stale mirror over develop's rebased changes
- **Original issue**: the divergence-halt recovery is a rebase, after which a straight re-promote would copy the branch-time full-file mirrors over develop's rebased content and silently revert it; no mirror-refresh step existed.
- **Fix applied**: `## Idempotence and Recovery` "Post-rebase mirror refresh (A4)" (`track-1.md:202`) adds a step: before re-promoting, for each hand-bootstrapped mirror (`_workflow/staged-workflow/CLAUDE.md` and the two `.claude/output-styles/**` files) re-copy the rebased live file into the mirror and re-apply the branch's edits, then promote.
- **Re-check**:
  - Location: `track-1.md:202`.
  - Current state: the text names the correct three mirrors, orders the operation as re-copy-then-re-apply-then-promote, and scopes it as this track's recovery procedure (noting the four-prefix surface shares the latent gap but is out of scope). The mechanism directly closes the stale-overwrite window.
  - Criteria met: the recovery text is coherent and executable.
- **Regression check**: checked against the A3 augmented-block text for order conflicts — the refresh precedes promotion, consistent with the interleaved block. Clean.
- **Verdict**: VERIFIED

#### Verify A5: live-write leak window was track-level only
- **Original issue**: the leak-detection window for stray live edits to the three out-of-surface files was track-level, since the live pre-commit gate does not watch output-styles or root `CLAUDE.md` this branch.
- **Fix applied**: the Live-tree-isolation invariant (`track-1.md:253`) gains a per-step guard: every step touching an out-of-surface file carries an inline copy-then-edit-into-mirror instruction and a per-commit assertion `git diff --cached --name-only -- .claude/output-styles CLAUDE.md` must be empty before the commit.
- **Re-check**:
  - Location: `track-1.md:253`.
  - Current state: the guard is present and the assertion checks that no live out-of-surface path is staged, shrinking detection from track-level to per-commit.
  - Criteria met: the per-step guard closes the leak window at commit granularity.
- **Regression check**: the assertion is empty-must-hold on the live paths only (mirror writes under `_workflow/` are unaffected) — no false trip on legitimate staged edits. Clean.
- **Verdict**: VERIFIED

#### Verify A6: item 13's hand-list under-enumerates design-review.md TL;DR sites
- **Original issue**: the item-13 hand-list of `design-review.md` TL;DR rename sites missed the design-sync accuracy clause (:131-134) and comprehension question 4 (:296).
- **Fix applied**: items 13 (`track-1.md:229`) and 14 (`:230`) now say "every `TL;DR` site a grep over the file returns" — grep-derived, with the structural-findings cluster and TOC row named as orientation, and the design-sync accuracy clause and comprehension question 4 called out as also reached.
- **Re-check**:
  - Location: `.claude/workflow/prompts/design-review.md`; `track-1.md:229-230`.
  - Current state: `grep -n "TL;DR"` returns {26, 131, 134, 296, 308, 323, 407}. Line 131/134 is the design-sync accuracy clause ("verify that every TL;DR ... contradicts the mechanics"); line 296 is comprehension question 4 ("Read the changed section's TL;DR; restate..."). Both are inside the grep-derived set the reframed instruction now covers.
  - Criteria met: the grep-derived criterion reaches the two previously-missed agent-facing sites.
- **Regression check**: checked that the edit-design item-14 grep reframe (`:230`) matches the same wording — consistent. Clean.
- **Verdict**: VERIFIED

#### Verify A7: D3 false-clean mitigations not pinned by acceptance
- **Original issue**: the three D3 false-clean mitigations (persona will-not-fill-in-gaps contract, cited comprehension answers, Sonnet pins) were narrative-only, not pinned by any acceptance criterion.
- **Fix applied**: a `## Validation` bullet (`track-1.md:171`) requires the staged `readability-auditor.md` will-not-fill-in-gaps target-reader contract, the staged `comprehension-review.md` cited-comprehension-answers obligation, and `model: sonnet` on both frontmatters.
- **Re-check**:
  - Location: `track-1.md:171`; `.claude/agents/readability-auditor.md:5`, `.claude/agents/comprehension-review.md:5`.
  - Current state: the acceptance bullet is present and names all three mitigations. Both live agent frontmatters today read `model: opus`, confirming they are real edit targets (the staged copies must flip to `sonnet`), so the acceptance is load-bearing rather than already-satisfied.
  - Criteria met: the false-clean mitigations are now pinned by a testable acceptance bullet.
- **Regression check**: cross-checked the D3 record and item 3/4 (Sonnet pins) — consistent with the acceptance. Clean.
- **Verdict**: VERIFIED

#### Verify A8: single-track sizing challenge
- **Original issue**: adversarial challenge to the single-track decomposition; both split candidates fall below the ~12 floor and overlap Track 1's files.
- **Fix applied**: none required — the disposition is "decision holds, no track change, recorded."
- **Re-check**:
  - Location: `track-1.md:241` (Sizing) and `:213-241` (Interfaces / single-track argumentation).
  - Current state: the Sizing paragraph records ~21 in-scope files within the ~20–25 ceiling and above the ~12 floor, and argues the §1.7 extension is the enabling mechanism for promoting the branch's own style files, not a separable unit, so the single-track gate is satisfied by "this is the whole change." The disposition is present and sound.
  - Criteria met: no track-structure change was needed; the recorded rationale withstands the challenge.
- **Regression check**: confirmed the file count still matches the Interfaces enumeration (21 entries) after the replan. Clean.
- **Verdict**: VERIFIED (no fix needed; disposition recorded and sound)

#### Verify A9: self-check removals live as sub-clauses inside kept items
- **Original issue**: some house-style.md self-check items mix kept and removed clauses (e.g. the "sentence case on H2+" clause), so whole-item deletion would drop kept content while sub-clause removal is needed.
- **Fix applied**: item 1 (`track-1.md:217`) states the self-check surgical sub-edits are enumerated by the same name+exemplar grep over house-style.md's own body: items mixing kept and removed clauses take clause-level edits, not whole-item deletion, with the "sentence case on H2+" clause named as the worked example.
- **Re-check**:
  - Location: `track-1.md:217`.
  - Current state: item 1 carries the clause-level-edit instruction and the worked example, and ties the enumeration to the same grep used for the reviewer drop-sites (A1) — one consistent criterion across surfaces.
  - Criteria met: the sub-clause removal discipline is captured with a concrete example.
- **Regression check**: the same grep-derived criterion is now used for both `review-workflow-writing-style.md` (A1) and `house-style.md`'s self-check (A9) — no contradiction between the two. Clean.
- **Verdict**: VERIFIED

#### Verify A10: deferred corpus-calibration risk near-nil
- **Original issue**: the corpus-calibration assertions are deferred to Phase-4 promotion; the residual risk, though near-nil under the zero-false-positive contract, was not retired.
- **Fix applied**: `## Idempotence and Recovery` (`track-1.md:206`) adds an optional Phase-B no-surprise spot-check: run the staged `design-mechanical-checks.py` by hand over the three live calibration ADRs and confirm zero `dsc-ai-tell` findings.
- **Re-check**:
  - Location: `track-1.md:206`; `.claude/scripts/tests/test_dsc_ai_tell.py:64-67`; `docs/adr/{persist-visible-count,index-gc,non-durable-wow}/adr.md`.
  - Current state: the three ADR paths in the spot-check exactly match `CALIBRATION_ADRS` (test lines 65-67), and all three `adr.md` files exist on disk, so the spot-check is executable. The rationale (removals cannot raise a zero count; `### Summary` additions touch shape checks the corpus filter never sees) is sound.
  - Criteria met: the near-nil residual risk is retired by a cheap, executable Phase-B check.
- **Regression check**: confirmed the spot-check is marked optional and does not displace the Phase-4 corpus run (R1/A3) — consistent. Clean.
- **Verdict**: VERIFIED

## Summary

PASS — all ten adversarial findings (A1–A10) VERIFIED, no regressions, no new findings.
