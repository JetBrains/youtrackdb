<!-- workflow-sha: 786f441e224ba6c8c4240dde5d9368866fb9b405 -->
# Track 4: Reindex rule_1 staged-mirror exemption (YTDB-1068)

## Purpose / Big Picture
After this track, `workflow-reindex.py` rule_1 no longer false-positives on staged-workflow copies, so the CI `workflow-toc-check.yml --check` gate passes on a non-draft PR for any workflow-modifying branch that stages a workflow copy. The fix unblocks this branch's own ready-time gate (Tracks 1-3 each stage workflow copies) and every future branch in the same shape.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Exempt the staged-workflow subtree from rule_1 (D9). Rule_1 demands a line-1 `workflow-sha` stamp on every `docs/adr/`-rooted in-scope path, but the only `docs/adr/`-rooted paths in `IN_SCOPE_GLOBS` are the staged-workflow mirror, which §1.7(e) requires to be a byte-verbatim copy of the unstamped live file (§1.6(f) excludes staged copies from the stamped set). The fix reuses the existing `_STAGED_SUBTREE_PREFIX_RE` to skip the staged subtree, syncs the now-stale rule_1 docstring, and inverts the regression test that asserts the pre-fix behavior. All edits are live `.claude/scripts/` changes, outside §1.7 staging scope, so the I6 staged-set invariant is unaffected.

## Base commit
6ca35362c6eda080410264802f2909418bb81df8

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

## Decision Log
<!-- Continuous-log. Execution-time decisions: inline-replan choices,
scope-downs, dependency reveals, gate-override reasons. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->

## Context and Orientation

All edit targets are live files under `.claude/scripts/`, NOT staged. `conventions.md §1.7` scopes staging to `.claude/workflow/**` and `.claude/skills/**` only; `.claude/scripts/` is outside that scope, so Track 4 edits the live `workflow-reindex.py`, its test file, and the rule_1 docstring directly. This is the structural difference from Tracks 1-3, which stage every edit: the I6 invariant is unaffected, because I6 constrains the staged set and Track 4 adds nothing to it. CI runs the live script from the branch tree, so the fix on this branch unblocks this branch's own TOC-check gate.

The bug. `check_rule_1_stamp_present` (`workflow-reindex.py:1544`) returns early for any path not under `docs/adr/` (`:1562`), then demands a line-1 `workflow-sha` stamp on everything else. The script's `IN_SCOPE_GLOBS` (`:118`–`:156`) mix live-path globs (`:120`–`:128`) with staged-workflow-mirror globs (`:142`–`:143`, plus the inert agent glob `:155`); rule_1's `docs/adr/` early-return (`:1562`) discards the live paths, so the only paths reaching the stamp check are the staged-workflow mirror (`docs/adr/*/_workflow/staged-workflow/.claude/...`). Per §1.7(e) those copies are byte-verbatim duplicates of the unstamped live files, and §1.6(f) excludes staged copies from the stamped set, so they correctly carry no stamp. Rule_1 therefore false-positives on every staged copy. CI `workflow-toc-check.yml` runs `--check` on non-draft PRs (reproduced exit=1 on this branch's staged copies), so this branch and every workflow-modifying branch fails the gate at ready-time.

The fix. Exempt the staged-workflow subtree from rule_1. The regex already exists: `_STAGED_SUBTREE_PREFIX_RE = re.compile(r"^docs/adr/[^/]+/_workflow/staged-workflow/")` at `workflow-reindex.py:166`. Reuse it inside `check_rule_1_stamp_present` (`if _STAGED_SUBTREE_PREFIX_RE.match(parsed.path): return []`) before the existing `docs/adr/` gate.

The wrinkle (Phase A decides). Once the staged mirror is exempt, rule_1 may have no remaining reachable target in this script, since the only `docs/adr/`-rooted paths it checks are the now-exempt mirror (the live globs in `IN_SCOPE_GLOBS` are filtered out by the `:1562` early-return before the stamp check). The plan's own stamped artifacts (`implementation-plan.md`, `design.md`, `plan/track-N.md`) are stamped per §1.6(f) but are not in this script's `IN_SCOPE_GLOBS`. Phase A picks one of: keep rule_1 as a harmless guard, or document it as enforced elsewhere (the drift gate). Either way the docstring at `:1547`–`:1560`, which currently calls rule_1 "a presence check for staged copies only", is now wrong and must be synced.

The test. `test_workflow_reindex.py` already carries three rule_1 cases: `test_rule_1_stamp_present_on_staged_path_passes` (`:653`), `test_rule_1_missing_stamp_on_staged_path_fails` (`:697`), and `test_rule_1_live_workflow_file_without_stamp_passes` (`:741`). The middle one asserts the pre-fix behavior (an unstamped staged copy currently fails rule_1), so after the exemption it must invert to expect a pass. The first stays green but for a new reason (exempted, not stamp-matched), so its docstring should reflect that. The live-file case stays green unchanged. The branch's own 18 staged copies are a live fixture for an end-to-end `--check` assertion.

Non-obvious terminology: **rule_1** (the reindex validator's line-1 `workflow-sha` stamp-presence check), **staged-workflow mirror** (the `docs/adr/<dir>/_workflow/staged-workflow/.claude/...` subtree holding verbatim copies of live workflow files per §1.7), **I6** (the invariant that the live `.claude/**` tree stays at develop's state for the branch lifetime — Track 4 leaves it intact because `.claude/scripts/` edits are outside the staged set).

## Plan of Work

The work is one code fix plus its test and doc-sync, all on live `.claude/scripts/` files:

1. **Exempt the staged subtree from rule_1** in `workflow-reindex.py`: reuse `_STAGED_SUBTREE_PREFIX_RE` inside `check_rule_1_stamp_present` to return no findings for staged-mirror paths.
2. **Resolve the wrinkle and sync the docstring**: decide whether rule_1 keeps a residual guard role or is documented as enforced by the drift gate, then rewrite the `:1547`–`:1560` docstring to match. Sync the `conventions.md §1.6(f)/§1.7(e)` cross-refs only if the script's design text claims staged copies are stamped.
3. **Update the regression test**: invert `test_rule_1_missing_stamp_on_staged_path_fails` to expect a pass for an unstamped staged copy (rename and re-document to match), confirm `test_rule_1_stamp_present_on_staged_path_passes` still passes for the new reason, and keep `test_rule_1_live_workflow_file_without_stamp_passes` green. Run the full `test_workflow_reindex.py` suite.

Ordering: step 1 (the fix) lands first so the test can assert against it; step 2 (docstring) and step 3 (test) follow and are largely independent. Phase A finalizes the step count and risk tags.

Invariants to preserve: the I6 staged-set invariant (Track 4 touches only live `.claude/scripts/`, never the staged set); rule_1's behavior on live workflow files (no stamp expected, `test_rule_1_live_workflow_file_without_stamp_passes` stays green); rule_1's empty-file and malformed-stamp handling for any path that remains in scope after the exemption.

<!-- Phase A appends a per-step sequencing summary referencing the Concrete Steps roster. -->

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

Track-level behavioral acceptance (Phase A turns these into per-step EARS/Gherkin lines):

- `check_rule_1_stamp_present` returns no finding for any path under `docs/adr/<dir>/_workflow/staged-workflow/.claude/...`, whether or not line 1 carries a `workflow-sha` stamp.
- `python3 .claude/scripts/workflow-reindex.py --check` exits 0 on this branch's tree (the 18 staged copies no longer trip rule_1), so the CI `workflow-toc-check.yml` gate passes on a non-draft PR.
- Rule_1 still passes live workflow files with no stamp (unchanged behavior); its empty-file and malformed-stamp findings still fire for any path that remains in scope after the exemption.
- The `:1547`–`:1560` rule_1 docstring describes the post-fix behavior, and no `conventions.md` text claims staged copies are stamped.
- `test_workflow_reindex.py` passes in full: the inverted staged-copy case asserts a pass, and the live-file and stamp-present cases stay green.

Per-step acceptance: Phase A placeholder.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery
paths once steps are decomposed. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't
belong to one specific step. Per-step episode content lives in
`## Episodes` above. Often empty. -->

## Interfaces and Dependencies

**In-scope files (LIVE edits under `.claude/scripts/`, NOT staged; §1.7 staging covers only `.claude/workflow/**` and `.claude/skills/**`):**
- `.claude/scripts/workflow-reindex.py` — `check_rule_1_stamp_present` (`:1544`): add the `_STAGED_SUBTREE_PREFIX_RE` (`:166`) exemption; sync the rule_1 docstring (`:1547`–`:1560`).
- `.claude/scripts/tests/test_workflow_reindex.py` — invert `test_rule_1_missing_stamp_on_staged_path_fails` (`:697`); re-document `test_rule_1_stamp_present_on_staged_path_passes` (`:653`); keep `test_rule_1_live_workflow_file_without_stamp_passes` (`:741`).

**Out-of-scope (deliberately not edited):**
- The staged-workflow tree under `docs/adr/step-size-recap/_workflow/staged-workflow/.claude/...` — correct as-is (verbatim unstamped copies per §1.7(e)); the fix is to exempt them, not to stamp them.
- The drift gate and `workflow-toc-check.yml` — read as the enforcement context for the docstring sync, not edited.
- Tracks 1-3's staged copies and the `implementation-plan.md` / `design.md` / `plan/track-N.md` stamps — untouched; those are in the stamped set, not in this script's `IN_SCOPE_GLOBS`.

**Dependencies:**
- **Independent track** — no dependency on Tracks 1-3, and none depends on it. The fix is self-contained in `.claude/scripts/`.

**Staging contract:** Track 4 does not stage. All edits are live `.claude/scripts/` changes outside the §1.7 staging scope, so the I6 invariant (live `.claude/workflow/**` and `.claude/skills/**` byte-unchanged from develop) is unaffected and no Phase C `§1.7(h)` staged-vs-live review applies to this track. Phase C reviews the live `.claude/scripts/` diff directly.
