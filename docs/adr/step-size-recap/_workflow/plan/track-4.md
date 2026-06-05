<!-- workflow-sha: 786f441e224ba6c8c4240dde5d9368866fb9b405 -->
# Track 4: Reindex rule_1 staged-mirror exemption (YTDB-1068)

## Purpose / Big Picture
After this track, `workflow-reindex.py` rule_1 no longer false-positives on staged-workflow copies, so the CI `workflow-toc-check.yml --check` gate passes on a non-draft PR for any workflow-modifying branch that stages a workflow copy. The fix unblocks this branch's own ready-time gate (Tracks 1-3 each stage workflow copies) and every future branch in the same shape.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Exempt the staged-workflow subtree from rule_1 (D9). Rule_1 demands a line-1 `workflow-sha` stamp on every `docs/adr/`-rooted in-scope path, but the only `docs/adr/`-rooted paths in `IN_SCOPE_GLOBS` are the staged-workflow mirror, which §1.7(e) requires to be a byte-verbatim copy of the unstamped live file (§1.6(f) excludes staged copies from the stamped set). The fix reuses the existing `_STAGED_SUBTREE_PREFIX_RE` to skip the staged subtree, syncs the now-stale rule_1 docstring, and inverts the regression test that asserts the pre-fix behavior. All edits are live `.claude/scripts/` changes, outside §1.7 staging scope, so the I6 staged-set invariant is unaffected.

## Base commit
6ca35362c6eda080410264802f2909418bb81df8

## Progress
- [x] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

- [x] 2026-06-05T05:10Z [ctx=info] Review + decomposition complete

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

## Decision Log
<!-- Continuous-log. Execution-time decisions: inline-replan choices,
scope-downs, dependency reveals, gate-override reasons. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

- **DL1 (Phase A — wrinkle resolution).** Keep rule_1 in place as a harmless guard and rewrite its full docstring (`workflow-reindex.py:1545-1561`) truthfully: after the staged-mirror exemption rule_1 has no reachable in-scope target, the staged mirror is intentionally unstamped per §1.6(f)/§1.7(e), and the §1.6(f) stamped artifacts are enforced by the `workflow-startup-precheck.sh` drift gate (a disjoint set, not in this script's `IN_SCOPE_GLOBS`). Rejected the "document rule_1 as drift-gate-enforced" option: the drift gate does not re-check rule_1's target, so that framing would write a false comment into the live script. Driven by adversarial finding A1.
- **DL2 (Phase A — approach confirmed).** Adversarial review (A3) constructed and failed all three sharpest challenges. The exemption regex `^docs/adr/[^/]+/_workflow/staged-workflow/` matches only paths required to be unstamped, so no real missing-stamp defect slips through. Editing `.claude/scripts/*.py` is outside §1.7 staging scope and outside the pre-commit `*.md` filter, so the I6 invariant and the staged set are unaffected. Removing the staged globs from `IN_SCOPE_GLOBS` would lose rules-2-8 schema validation of staged copies, so the fix is correctly targeted at rule_1's path gate, not at glob membership. Recorded so these are not re-raised at Phase C.
- **DL3 (Phase A — CI-gate framing verified).** `workflow-toc-check.yml` runs `--check` only on non-draft PRs and its `paths:` filter includes the staged-copy subtree. On this branch `--check` reports exactly 18 findings, all rule_1 on staged copies and zero of the ~1500 un-annotated findings the workflow comment warns about, so the universal-annotation rollout has reached the live tree and the gate is load-bearing here. rule_1 is the sole blocker today, so the Purpose-section "unblocks the ready-time gate" framing is accurate. No track-file change (adversarial suggestion A4 dismissed on this evidence).
- **DL4 (Phase A — 2-step decomposition).** The fix and the inversion of `test_rule_1_missing_stamp_on_staged_path_fails` must land in one commit: the exemption makes that test's `assert rule1` fail, so splitting them would break the suite at the commit boundary. Step 1 bundles the fix, the docstring sync, and the existing-test adaptation; Step 2 adds the independent direct-call regression coverage for the orphaned empty-file/malformed-stamp branches (reachable by direct call regardless of the exemption). Two steps keep Phase C running as a full track-level review (a single `medium`/`low` step would too, but the split gives cleaner review focal points).

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->

- [x] Technical: PASS at iteration 2 (5 findings — 1 blocker, 2 should-fix, 2 suggestions; all accepted and applied to the track file: the missing test-registry edit site `:4824-4827`, the untestable empty-file/malformed-stamp acceptance criterion, the now-redundant stamp-present test, the count-keyed `--check` acceptance, and the widened docstring-sync scope `:1545-1561`).
- [x] Adversarial: PASS at iteration 2 (4 findings — 2 should-fix applied: the wrinkle resolved as keep-as-guard plus a truthful docstring (DL1, rejecting the drift-gate-enforcement framing) and the direct-call regression test for the orphaned `:1564`/`:1573` branches (Step 2); 1 suggestion dismissed on evidence — the CI gate is load-bearing on this branch (DL3); 1 skip-severity recorded — the core approach holds against all three sharpest challenges (DL2)).

## Context and Orientation

All edit targets are live files under `.claude/scripts/`, NOT staged. `conventions.md §1.7` scopes staging to `.claude/workflow/**` and `.claude/skills/**` only; `.claude/scripts/` is outside that scope, so Track 4 edits the live `workflow-reindex.py`, its test file, and the rule_1 docstring directly. This is the structural difference from Tracks 1-3, which stage every edit: the I6 invariant is unaffected, because I6 constrains the staged set and Track 4 adds nothing to it. CI runs the live script from the branch tree, so the fix on this branch unblocks this branch's own TOC-check gate.

The bug. `check_rule_1_stamp_present` (`workflow-reindex.py:1544`) returns early for any path not under `docs/adr/` (`:1562`), then demands a line-1 `workflow-sha` stamp on everything else. The script's `IN_SCOPE_GLOBS` (`:118`–`:156`) mix live-path globs (`:120`–`:128`) with staged-workflow-mirror globs (`:142`–`:143`, plus the inert agent glob `:155`); rule_1's `docs/adr/` early-return (`:1562`) discards the live paths, so the only paths reaching the stamp check are the staged-workflow mirror (`docs/adr/*/_workflow/staged-workflow/.claude/...`). Per §1.7(e) those copies are byte-verbatim duplicates of the unstamped live files, and §1.6(f) excludes staged copies from the stamped set, so they correctly carry no stamp. Rule_1 therefore false-positives on every staged copy. CI `workflow-toc-check.yml` runs `--check` on non-draft PRs (reproduced exit=1 on this branch's staged copies), so this branch and every workflow-modifying branch fails the gate at ready-time.

The fix. Exempt the staged-workflow subtree from rule_1. The regex already exists: `_STAGED_SUBTREE_PREFIX_RE = re.compile(r"^docs/adr/[^/]+/_workflow/staged-workflow/")` at `workflow-reindex.py:166`. Reuse it inside `check_rule_1_stamp_present` (`if _STAGED_SUBTREE_PREFIX_RE.match(parsed.path): return []`) before the existing `docs/adr/` gate.

The wrinkle (resolved by Phase A — see Decision Log DL1). Once the staged mirror is exempt, rule_1 has no remaining reachable in-scope target in this script: the live globs in `IN_SCOPE_GLOBS` are filtered out by the `:1562` `docs/adr/` early-return before the stamp check, and the only `docs/adr/`-rooted globs are the now-exempt staged mirror. The plan's own stamped artifacts (`implementation-plan.md`, `design.md`, `plan/track-N.md`) are stamped per §1.6(f) but are not in this script's `IN_SCOPE_GLOBS`, and `--files` drops any path outside `IN_SCOPE_GLOBS` before it reaches rule_1. Phase A keeps rule_1 as a harmless guard against a future re-introduced non-exempt `docs/adr/` glob and rewrites the whole docstring (`:1545`–`:1561`) to say so truthfully. The rejected alternative, documenting rule_1 as "enforced by the drift gate", is factually wrong: the startup-precheck drift gate enforces line-1 stamps on the §1.6(f) stamped artifact set, a set disjoint from `IN_SCOPE_GLOBS`, not on rule_1's staged-mirror target. The current docstring at `:1547`–`:1560`, which calls rule_1 "a presence check for staged copies only", is now wrong and must be synced.

The test. `test_workflow_reindex.py` already carries three rule_1 cases: `test_rule_1_stamp_present_on_staged_path_passes` (`:653`), `test_rule_1_missing_stamp_on_staged_path_fails` (`:697`), and `test_rule_1_live_workflow_file_without_stamp_passes` (`:741`). The middle one asserts the pre-fix behavior (an unstamped staged copy currently fails rule_1), so after the exemption it must invert to expect a pass. The first stays green but for a new reason (exempted, not stamp-matched), so its docstring should reflect that. The live-file case stays green unchanged. Phase A adds a fourth case (Step 2) that calls `check_rule_1_stamp_present` directly, so the empty-file and malformed-stamp branches stay covered after the exemption orphans them. The branch's own 18 staged copies back a one-time manual `--check` exit-0 acceptance run, not a committed test (see `## Validation and Acceptance`).

Non-obvious terminology: **rule_1** (the reindex validator's line-1 `workflow-sha` stamp-presence check), **staged-workflow mirror** (the `docs/adr/<dir>/_workflow/staged-workflow/.claude/...` subtree holding verbatim copies of live workflow files per §1.7), **I6** (the invariant that the live `.claude/**` tree stays at develop's state for the branch lifetime — Track 4 leaves it intact because `.claude/scripts/` edits are outside the staged set).

## Plan of Work

The work is one validator fix plus its doc-sync and test changes, all on live `.claude/scripts/` files, decomposed into two steps (see `## Concrete Steps`):

1. **Step 1 — exempt the staged subtree, sync the docstring, and adapt the existing tests** (one commit; `workflow-reindex.py` + `test_workflow_reindex.py`). Reuse `_STAGED_SUBTREE_PREFIX_RE` (`:166`) inside `check_rule_1_stamp_present` to return no findings for staged-mirror paths, inserting the check before the `:1562` `docs/adr/` early-return. Rewrite the whole rule_1 docstring (`:1545`–`:1561`) to the truthful framing in DL1 (no reachable in-scope target; staged mirror intentionally unstamped; stamped artifacts enforced by the startup-precheck drift gate, a disjoint set). Adapt the existing rule_1 tests in lockstep so the suite stays green at the commit boundary: invert and rename `test_rule_1_missing_stamp_on_staged_path_fails` (`:697`), and its registry entry in the manual test list (`:4824`–`:4827`, both the label string and the function reference), to assert an unstamped staged copy is now exempt; re-document `test_rule_1_stamp_present_on_staged_path_passes` (`:653`) to state it passes via the exemption regardless of line-1 content (the exemption is stamp-agnostic); keep `test_rule_1_live_workflow_file_without_stamp_passes` (`:741`) green.

2. **Step 2 — add direct-call regression coverage for the orphaned branches** (one commit; `test_workflow_reindex.py` only). The exemption orphans rule_1's empty-file (`:1564`) and malformed-stamp (`:1573`) branches: no `IN_SCOPE_GLOBS` path reaches them after the fix. Add a test that calls `check_rule_1_stamp_present` directly on a synthetic non-exempt `docs/adr/<dir>/_workflow/<name>.md` `ParsedFile` — an empty-lines case expecting the empty-file finding and a non-stamp-line-1 case expecting the malformed-stamp finding — and register it in the test list. This keeps both live branches covered independent of glob reachability (driven by adversarial finding A2).

Ordering: Step 1 fixes the bug and keeps the suite green in one commit; Step 2 adds independent coverage and does not depend on Step 1 (the direct-call branches are reachable regardless of the exemption).

Invariants to preserve: the I6 staged-set invariant (Track 4 touches only live `.claude/scripts/`, never the staged set, confirmed unaffected by adversarial A3 and DL2); rule_1's behavior on live workflow files (no stamp expected, `test_rule_1_live_workflow_file_without_stamp_passes` stays green); rule_1's empty-file and malformed-stamp logic, now exercised only by the Step 2 direct-call test.

## Concrete Steps

1. Exempt the staged-workflow subtree from rule_1 (reuse `_STAGED_SUBTREE_PREFIX_RE` before the `:1562` `docs/adr/` gate), rewrite the full rule_1 docstring (`workflow-reindex.py:1545-1561`) to the DL1 framing, and adapt the existing rule_1 tests so the suite stays green: invert and rename `test_rule_1_missing_stamp_on_staged_path_fails` and its registry entry (`:4824-4827`), re-document `test_rule_1_stamp_present_on_staged_path_passes`, keep the live-file test green; verify the suite passes and `--check` exits 0 — risk: medium (CI-validation tooling: changes the observable behavior of the rule_1 validator)  [ ]
2. Add a direct-call regression test exercising rule_1's now-orphaned empty-file (`:1564`) and malformed-stamp (`:1573`) branches on a synthetic non-exempt `docs/adr/` `ParsedFile`, and register it in the test list — risk: low (new tests)  [ ]

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step, identified by step number + commit SHA. Empty at
Phase 1; Phase A does not populate. -->

## Validation and Acceptance

Track-level behavioral acceptance:

- `check_rule_1_stamp_present` returns no finding for any path under `docs/adr/<dir>/_workflow/staged-workflow/.claude/...`, whether or not line 1 carries a `workflow-sha` stamp (the exemption is stamp-agnostic).
- `python3 .claude/scripts/workflow-reindex.py --check` exits 0 on this branch's tree (the 18 staged copies no longer trip rule_1), so the CI `workflow-toc-check.yml` gate passes on a non-draft PR. This is a one-time manual acceptance run, not a committed test; the staged-copy count is asserted nowhere, since it changes as staged files are added or removed.
- Rule_1 still passes live workflow files with no stamp (unchanged behavior; `test_rule_1_live_workflow_file_without_stamp_passes` stays green). Its empty-file (`:1564`) and malformed-stamp (`:1573`) branches still fire when `check_rule_1_stamp_present` is called directly on a non-exempt `docs/adr/`-rooted `ParsedFile`, even though no `IN_SCOPE_GLOBS` path reaches them via `validate`/`--check` after the exemption (covered by the Step 2 direct-call test).
- The rule_1 docstring (`:1545`–`:1561`) describes the post-fix behavior per DL1: rule_1 has no remaining reachable in-scope target, the staged mirror is intentionally unstamped per §1.6(f)/§1.7(e), and stamp enforcement for the §1.6(f) artifact set lives in the startup-precheck drift gate (a disjoint set). No comment claims the drift gate re-checks rule_1's target, and no `conventions.md` text claims staged copies are stamped.
- `test_workflow_reindex.py` passes in full: the inverted and renamed staged-copy case asserts a pass, the re-documented stamp-present case stays green via the exemption, the live-file case stays green, and the new direct-call case asserts both the empty-file and malformed-stamp findings fire.

Per-step acceptance:

- **Step 1** — GIVEN this branch's tree with its 18 staged-workflow copies, WHEN `python3 .claude/scripts/workflow-reindex.py --check` runs, THEN it exits 0 with no rule_1 finding; AND `python3 .claude/scripts/tests/test_workflow_reindex.py` exits 0 with the inverted, re-documented, and live-file rule_1 cases all green.
- **Step 2** — GIVEN a `ParsedFile` whose path is `docs/adr/<dir>/_workflow/<name>.md` (non-exempt, non-staged), WHEN `check_rule_1_stamp_present` is called on it with empty lines, THEN it returns the empty-file finding; AND WHEN called with a non-stamp line 1, THEN it returns the malformed-stamp finding; AND the new test is registered and green in the full suite.

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery
paths once steps are decomposed. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't
belong to one specific step. Per-step episode content lives in
`## Episodes` above. Often empty. -->

## Interfaces and Dependencies

**In-scope files (LIVE edits under `.claude/scripts/`, NOT staged; §1.7 staging covers only `.claude/workflow/**` and `.claude/skills/**`):**
- `.claude/scripts/workflow-reindex.py` — `check_rule_1_stamp_present` (`:1544`): add the `_STAGED_SUBTREE_PREFIX_RE` (`:166`) exemption before the `:1562` `docs/adr/` early-return; rewrite the full rule_1 docstring (`:1545`–`:1561`).
- `.claude/scripts/tests/test_workflow_reindex.py` — invert and rename `test_rule_1_missing_stamp_on_staged_path_fails` (`:697`) AND its registry entry in the manual test list (`:4824`–`:4827`: the label string at `:4825` and the function reference at `:4826`); re-document `test_rule_1_stamp_present_on_staged_path_passes` (`:653`); keep `test_rule_1_live_workflow_file_without_stamp_passes` (`:741`) green; add a new direct-call regression test for the empty-file/malformed-stamp branches and register it.

**Out-of-scope (deliberately not edited):**
- The staged-workflow tree under `docs/adr/step-size-recap/_workflow/staged-workflow/.claude/...` — correct as-is (verbatim unstamped copies per §1.7(e)); the fix is to exempt them, not to stamp them.
- The drift gate and `workflow-toc-check.yml` — read as the enforcement context for the docstring sync, not edited.
- Tracks 1-3's staged copies and the `implementation-plan.md` / `design.md` / `plan/track-N.md` stamps — untouched; those are in the stamped set, not in this script's `IN_SCOPE_GLOBS`.

**Dependencies:**
- **Independent track** — no dependency on Tracks 1-3, and none depends on it. The fix is self-contained in `.claude/scripts/`.

**Staging contract:** Track 4 does not stage. All edits are live `.claude/scripts/` changes outside the §1.7 staging scope, so the I6 invariant (live `.claude/workflow/**` and `.claude/skills/**` byte-unchanged from develop) is unaffected and no Phase C `§1.7(h)` staged-vs-live review applies to this track. Phase C reviews the live `.claude/scripts/` diff directly.
