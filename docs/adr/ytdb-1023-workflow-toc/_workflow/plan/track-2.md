<!-- workflow-sha: 367f5f83f1bce0e98eaeb0679973f9728db64b61 -->
# Track 2: Reindex script + CI gate + audit agent updates

## Purpose / Big Picture

After this track lands, `workflow-reindex.py` mechanically validates schema compliance at pre-commit and CI time, and the `review-workflow-context-budget` agent absorbs the qualitative audit at PR review.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Build `.claude/scripts/workflow-reindex.py` (mechanical Python; `--check` and `--write` modes; stdlib only). Wire a pre-commit hook and a GitHub Actions step. Update `.claude/agents/review-workflow-context-budget.md` to absorb the audit. Tests under `.claude/scripts/tests/`.

## Progress
- [x] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

- [x] 2026-05-28T13:58Z [ctx=info] Review + decomposition complete
- [x] 2026-05-28T14:16Z [ctx=info] Step 1 complete (commit f676e9172b)
- [x] 2026-05-28T14:35Z [ctx=info] Step 2 complete (commit 91748d1a6d)
- [x] 2026-05-28T14:47Z [ctx=info] Step 3 complete (commit 39fd19b356)
- [x] 2026-05-28T14:56Z [ctx=info] Step 4 complete (commit 5525fcf4ed)

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

- §1.8 enum extraction requires column-0 token rule; wrap-text continuation lines (any line beginning with whitespace) are not enum values. Step 2's rule 5d ("tokens drawn from the bootstrap output") consumes the clean 15-role / 8-phase set with no further filtering. See Episodes §Step 1.
- Bootstrap API for downstream tracks: Track 4 (annotation rollout) and Track 5 (cross-ref enforcement) consume `load_bootstrap_enums(repo_root)` returning `BootstrapEnums(roles, phases, source)`, plus `parse_in_scope_files(repo_root)` returning `ParsedFile` records (each carries `fenced_lines` so ref-in-fence exclusion is a boolean lookup). See Episodes §Step 1.
- Step 4 hook input-set decision: should staged paths under `docs/adr/*/_workflow/staged-workflow/.claude/...` be in-scope for the script's discovery walk, or stay silently skipped? Step 2's behavior is silent-skip per A6; Step 4 needs to confirm or override before wiring the widened hook regex. See Episodes §Step 2.
- Pedagogical `§X.Y` examples in conventions.md and similar docs must sit inside inline-backtick or fenced spans to avoid rule_8 self-triggering. Track 4 reviewers should treat free-standing `§X.Y` outside backticks as a real finding even when the surrounding prose reads like an example. See Episodes §Step 2.
- Track 4 rollout ordering: `--write` populates the TOC table body only when delimiter comments already exist. The first-touch pass of the universal annotation rollout must add the empty `<!--Document index start-->` / `<!--Document index end-->` pair BEFORE running `--write`, otherwise the TOC half is a silent no-op for that file (caught later by `--check` rule 2). The ref auto-stamp half works on any file with `§X.Y` refs regardless of TOC presence, so the bootstrap-block rollout (Track 5) has no ordering constraint with the TOC rollout. See Episodes §Step 3.
- CI gate red-until-Track-5: the new `workflow-toc-check.yml` workflow lands wired but reports ~1500 findings against the un-annotated live workflow tree until Track 4 (annotation rollout) and Track 5 (bootstrap blocks) close them. This is by design per the plan's "schema-only state" acceptance language. Track 4 reviewers should expect the gate to flip red-to-green at the annotation rollout commit, not before; Track 5 closes any residual rule_7 (bootstrap-presence) findings. See Episodes §Step 4.

## Decision Log
<!-- Continuous-log. Execution-time decisions: inline-replan choices,
scope-downs, dependency reveals, gate-override reasons. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->

- [x] Technical: PASS at iteration 2 (7 findings, 7 accepted: T1, T2 blockers — D12 added for staged-aware §1.8 probe, hook restructure named in Step 4; T3, T4, T5 should-fix — staged-path regex widening, summary ≤120 char sub-rule 5c, `--write` mixed-content halt test; T6, T7 suggestions — prefix placement template pinned, dedicated CI workflow committed).
- [x] Adversarial: PASS at iteration 2 (7 findings, 7 accepted: A1 should-fix — D11 scope expanded to all six `review-workflow-*` agents per user decision; A2, A3, A4 should-fix — §1.8(e) gained inline-backtick exclusion clause + `any`-wildcard semantics paragraph, pre-commit vs CI scope gap documented via contrasting hook/CI bullets; A5, A6, A7 suggestions — fence-parser precedent cited, out-of-scope `--files` silent-skip sub-rule + test, Step 2/3 split rationale recorded under Plan of Work).

## Context and Orientation

`.claude/scripts/` carries two existing Python tools:

- `design-mechanical-checks.py` — design.md / design-mechanics.md mechanical checks invoked by `edit-design`.
- `render-slim-plan.py` — plan-file slim-rendering for `/execute-tracks` startup.
- `session-stats.py` — statusline cost/token aggregation.

`workflow-reindex.py` joins this set. Same style: stdlib-only Python 3, callable as `python3 .claude/scripts/<script>.py [args]`. Tests live under `.claude/scripts/tests/`.

CI today runs no workflow-doc validation (`find .github -type f | xargs grep -l workflow` returns empty for workflow-doc references). The CI integration in this track is greenfield: either a new GitHub Actions workflow file or a new step in an existing workflow.

Pre-commit hooks: `.githooks/prepare-commit-msg` exists for issue-prefix auto-prepending. `.githooks/pre-commit` already exists as a Spotless runner for staged Java files (gated on the JetBrains/youtrackdb origin). No workflow-file gating runs from it today. This track extends the existing `.githooks/pre-commit` by appending a second block that runs the reindex script on staged workflow files; the Spotless block is preserved.

The `review-workflow-context-budget` agent at `.claude/agents/review-workflow-context-budget.md` is the audit absorber per YTDB-1023. Current agent definition reviews "always-loaded surface, load-on-demand discipline, and instant per-operation consumption". This track extends the agent's responsibilities to include:

- Running the reindex script on workflow-machinery diffs.
- Surfacing script findings as `WB1, WB2, ...` items.

### Files in scope

- `.claude/scripts/workflow-reindex.py` — new file, live path (scripts are not staged per §1.7).
- `.claude/scripts/tests/test_workflow_reindex.py` — new test file, live path.
- `.githooks/pre-commit` — restructured (Java-only gate factored into a function; workflow-reindex block runs unconditionally other than the JetBrains-remote gate), live path.
- `.github/workflows/workflow-toc-check.yml` — new dedicated CI workflow (path-filtered), live path.
- `.claude/agents/review-workflow-context-budget.md` — updated (script invocation + `WB<N>` prefix), live path (agents are not staged per §1.7).
- `.claude/agents/review-workflow-hook-safety.md` — updated (`HS<N>` prefix), live path.
- `.claude/agents/review-workflow-prompt-design.md` — updated (`PD<N>` prefix), live path.
- `.claude/agents/review-workflow-instruction-completeness.md` — updated (`IC<N>` prefix), live path.
- `.claude/agents/review-workflow-writing-style.md` — updated (`WS<N>` prefix), live path.
- `.claude/agents/review-workflow-consistency.md` — updated (`CN<N>` prefix), live path.

### Files out of scope

- Schema definition (`conventions.md §1.8`) — Track 1 owns it; this track reads from it.
- Per-section annotation rollout — Track 4 territory.
- Telemetry script — Track 3 territory.

## Plan of Work

The track lands in five steps. The Step 2 / Step 3 split is intentional (A7): the shared parsing + fence/backtick state machine lives in Step 1 as a module-level resolver; Step 2 introduces the validate-only `--check` surface (independently testable on exit codes); Step 3 introduces the mutate `--write` surface (halt-on-unresolved atomicity). Merging would mix validate-only and mutate-on-write semantics in one commit; the split keeps each step's failure surface clean.

1. **Script core: parsing, discovery, fence + inline-backtick state machine, staged-aware §1.8 probe.** Implement file enumeration (fixed globs per design.md §"Reindex script" → §"Discovery mechanism"), heading + annotation parsing (regex on `^##`, `^###`, and the line after), TOC region detection (between delimiter comments), and the CommonMark fence + inline-backtick state machine that rules 6, 8, and `--write` all consume (per `design-mechanical-checks.py:209-263` precedent — fenced + same-char-and-length close match; inline-backtick exclusion is the Track-2 addition per A2 to cover pedagogical refs inside backtick spans). Self-bootstrap path per D12: probe `docs/adr/*/_workflow/staged-workflow/.claude/workflow/conventions.md` (single-match enforced; multiple matches halt with exit 2); read §1.8 from the matched staged copy, fall back to live `.claude/workflow/conventions.md` when no staged copy is present. No validation yet; just structured representation of every in-scope file's annotations and TOC plus the bootstrap output (15 role tokens, 8 phase tokens) the next step consumes.

2. **Validation rules 1-8 and `--check` mode.** Implement every check from `design.md §"Reindex script" > Validation rules`:
   - Rule 1: stamp present on line 1.
   - Rule 2: exactly one TOC region under H1 (accepts empty TOC for files with no `^## ` headings).
   - Rule 3: TOC matches annotations (every `^## ` and `^### ` heading has a TOC row; bootstrap-block heading exempt by literal-text match).
   - Rule 4: annotation present after every `^## ` and `^### ` heading (same bootstrap exemption).
   - Rule 5 (field well-formedness — sub-rules enumerated explicitly per T4, A3):
     - 5a: `roles=` field present, non-empty, comma-separated, no spaces around commas;
     - 5b: `phases=` same shape;
     - 5c: `summary="..."` double-quoted, **≤120 chars (closes Track 1 WI7)**; embedded quotes escaped per §1.8(c);
     - 5d: all tokens drawn from the bootstrap output (15 roles + 8 phases including `any`);
     - 5e: malformed annotation comment (missing closing `-->`, multi-line) fails.
   - Rule 6: cross-file ref suffix presence + subset validation per D10 and §1.8(e) `any`-wildcard semantics — `target.roles={any}` matches any citer role; `citer.roles={any}` requires `target.roles={any}`; sub-section refs resolve to that section's annotation directly; file-level refs resolve to the union of every section's annotations in the target file. Refs inside fenced code blocks **and inside inline backtick spans** are excluded (per §1.8(e) Cross-file drift detection paragraph).
   - Rule 7: bootstrap-block presence on the 38 in-scope system prompts (literal heading `## Reading workflow files (TOC protocol)`).
   - Rule 8: in-file `§X.Y(z)` ref auto-stamp suffix matches the target heading's current annotation; unstamped, stale, and unresolved refs all fail (same `--write` recovery for the first two; hand-edit for the third). Refs inside fenced code blocks and inline backtick spans excluded.
   - **Discovery-glob filter for `--files` input** (closes A6): the hook may pass a non-workflow-referencing SKILL.md path (e.g., `.claude/skills/run-jmh-benchmarks-hetzner/SKILL.md`) because the hook's regex is broader than the in-scope glob. Such out-of-scope files are silently skipped; the script exits 0 on a fully-skipped `--files` set.
   - Exit codes: `0` (clean), `1` (findings), `2` (script error or ambiguous bootstrap probe). Output format `path:line:category: explanation` per design.

3. **`--write` mode.** TOC region rebuild from current annotations. Auto-stamp every in-file `§X.Y(z)` reference with the `:roles:phases` suffix derived from the target heading's annotation. **Halt-on-unresolved contract** (closes T5): if any in-file ref does not resolve to a heading in the same file, halt with exit 2 and no writes — even when other refs in the same file are auto-fixable. The author hand-edits the citing prose to point at a real heading, then re-runs `--write`. Skips refs inside fenced code blocks **and inline backtick spans**. Idempotent: running `--write` twice produces no diff after the first run. Does NOT modify per-section annotations or cross-file `name.md:roles:phases` suffixes (those remain hand-written per D9).

4. **Pre-commit hook restructure + dedicated GitHub Actions workflow.**
   - **Hook restructure** (closes T2): `.githooks/pre-commit` currently early-exits on "no Java files staged" (line 17-19). The naive append-after-Spotless approach inherits this exit and silently skips workflow-only commits. Lift the Java-only gate into a function called only when `STAGED_JAVA_FILES` is non-empty, then run the workflow-reindex block unconditionally (other than the JetBrains-remote gate at line 11-13).
   - **Hook regex widening** (closes T3): widen the workflow-files filter to match both live paths (`^\.claude/(workflow|skills)/.*\.md$|^\.claude/agents/.*\.md$`) and staged paths (`^docs/adr/.*/_workflow/staged-workflow/\.claude/(workflow|skills|agents)/.*\.md$`). Use `--diff-filter=ACMR` to exclude deletions.
   - **New dedicated CI workflow** (closes T7): `.github/workflows/workflow-toc-check.yml` with `on: pull_request: paths: ['.claude/**/*.md', '.githooks/pre-commit', '.github/workflows/workflow-toc-check.yml', 'docs/adr/**/_workflow/staged-workflow/.claude/**/*.md']`. Single step: `python3 .claude/scripts/workflow-reindex.py --check`. Path filter keeps the workflow's runtime under ~10 seconds per PR that actually touches workflow content; doesn't block on Maven.

5. **Audit-agent prefix family + comprehensive test matrix.**
   - **Six dim-review agents gain the prefix family** per D11 expanded scope: `WB` (`review-workflow-context-budget`), `HS` (`review-workflow-hook-safety`), `PD` (`review-workflow-prompt-design`), `IC` (`review-workflow-instruction-completeness`), `WS` (`review-workflow-writing-style`), `CN` (`review-workflow-consistency`). Placement per T6: `**<PFX><N>** — File: ..., Axis: ..., Cost: ..., Issue: ..., Suggestion: ...` under the existing `#### Critical / #### Recommended / #### Minor` H4s; numeric IDs sequence across severities (not restarted per severity), matching the plan/track prefix family in `review-iteration.md`.
   - **Context-budget agent additionally invokes the reindex script** during workflow-machinery review and surfaces script findings as `WB<N>` items under the appropriate severity heading.
   - **Tests under `.claude/scripts/tests/test_workflow_reindex.py`** cover the validation matrix: valid file passes; missing TOC fails (rule 2); out-of-enum token fails (rule 5d); summary >120 chars fails (rule 5c, closes T4); annotation with spaces around commas fails (rule 5a/5b); cross-file ref without suffix fails (rule 6); cross-file subset violations (rule 6) — role-not-in-target, phase-not-in-target, `any`-wildcard cases per A3 (target-any + specific citer passes; citer-any + specific target fails; both-any passes); cross-file file-level ref subset-validates against the union; cross-file sub-section ref subset-validates against that section; in-file ref unstamped fails (rule 8); in-file ref stale-suffix fails (rule 8); in-file ref unresolved fails (rule 8); inline-backtick ref is NOT validated AND NOT auto-stamped (A2); bootstrap-heading exemption from rules 3 and 4; `--write` idempotence; `--write` auto-stamps in-file refs from target annotations; `--write` halts with exit 2 on mixed-content (stale + unresolved refs in same file) and writes nothing (closes T5); `--write` does NOT touch cross-file refs even on subset violation; out-of-scope SKILL.md path passed via `--files` is silently skipped (closes A6); staged-aware §1.8 probe finds the staged copy when present and falls back to live when absent (D12).

The script self-bootstraps from `conventions.md §1.8` for enum values via the D12 staged-aware probe. Tests use a fixture `conventions.md` with the enums to stay hermetic; the staged-aware probe code path is exercised by a separate fixture pair (live-only copy; staged + live copies; multiple staged copies → exit 2).

## Concrete Steps

1. Author `workflow-reindex.py` script core — file discovery (fixed in-scope globs per design.md §"Reindex script" → §"Discovery mechanism"); heading + annotation parsing (regex on `^##`, `^###`, line-after); TOC region detection (delimiter comments); CommonMark fence + inline-backtick state machine (per `design-mechanical-checks.py:209-263` precedent; inline-backtick is the Track 2 addition per A2); staged-aware §1.8 probe per D12 (single-match enforced; multiple matches halt with exit 2; fall back to live `.claude/workflow/conventions.md`); structured representation of every in-scope file's annotations and TOC; bootstrap output exposed to downstream rules — risk: medium (multi-file logic; new module introduces the resolver every downstream step consumes; staged-aware probe extends §1.7(d) reads-precedence scope per D12)  [x] commit: f676e9172b
2. Implement validation rules 1-8 and `--check` mode — rule 1 (stamp present); rule 2 (TOC present, empty-TOC accepted for files without `^## ` headings); rule 3 (TOC matches annotations, bootstrap-block exemption); rule 4 (annotation present at every `^## ` / `^### `, bootstrap-block exemption); rule 5 sub-checks 5a-5e (closes T4 / WI7 summary cap ≤120 chars); rule 6 cross-file subset with `any`-wildcard semantics per §1.8(e) A3 + fenced/inline-backtick exclusion per A2; rule 7 bootstrap-block presence; rule 8 in-file ref auto-stamp suffix (unstamped/stale/unresolved); `--files` filter silently skips out-of-scope paths per A6; exit codes 0/1/2 — risk: medium (multi-file logic; rule 5, 6, 8 carry the most novel logic — `any` semantics, subset validation, in-file ref auto-stamp; CI surface so bugs ship broken gate)  [x] commit: 91748d1a6d
3. Implement `--write` mode — TOC region rebuild from current annotations; in-file `§X.Y(z)` auto-stamp from target heading's annotation; halt-on-unresolved contract per T5 (exit 2, no writes, even when other refs in same file are auto-fixable); fenced + inline-backtick exclusion per A2; idempotent (running `--write` twice produces no diff); does NOT modify per-section annotations or cross-file `name.md:roles:phases` suffixes (those remain hand-written per D9) — risk: medium (multi-file mutation; halt-on-unresolved is correctness-sensitive; idempotence is testable)  [x] commit: 39fd19b356
4. Restructure `.githooks/pre-commit` (T2 — Java-only gate factored into function called only when `STAGED_JAVA_FILES` non-empty, workflow-reindex block runs unconditionally other than JetBrains-remote gate; T3 — widened filter regex covering both live `.claude/(workflow|skills|agents)/**/*.md` and staged `docs/adr/*/_workflow/staged-workflow/.claude/(workflow|skills|agents)/**/*.md`; `--diff-filter=ACMR` excludes deletions) + new `.github/workflows/workflow-toc-check.yml` (T7 — dedicated workflow with path filter covering `.claude/**/*.md`, `.githooks/pre-commit`, the workflow file itself, and `docs/adr/**/_workflow/staged-workflow/.claude/**/*.md`; single step `python3 .claude/scripts/workflow-reindex.py --check`) — risk: medium (build-config change; new CI workflow + hook restructure; broken hook fails every commit on the branch, but the change is mechanical and contained)  [x] commit: 5525fcf4ed
5. Expand audit-agent prefix family to all six `review-workflow-*` agents (A1 — `WB / HS / PD / IC / WS / CN` family, placement per T6 — `**<PFX><N>** — File: ..., Axis: ..., Cost: ..., Issue: ..., Suggestion: ...` under existing `#### Critical / #### Recommended / #### Minor` H4s, sequencing across severities); `review-workflow-context-budget.md` additionally invokes the reindex script during workflow-machinery review and surfaces script findings as `WB<N>` items; tests under `.claude/scripts/tests/test_workflow_reindex.py` cover the full validation matrix from Step 5 of Plan of Work (closes T5 mixed-content halt, T4 summary cap, A2 inline-backtick exclusion, A3 `any`-wildcard cases, A6 out-of-scope `--files` skip, D12 staged-aware probe fixtures) — risk: medium (test infrastructure; multi-file edits across six agent prompts, template-bound)  [ ]

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step, identified by step number + commit SHA. Empty at
Phase 1; Phase A does not populate. -->

### Step 4 — commit 5525fcf4ed, 2026-05-28T14:56Z [ctx=info]
**What was done:** Restructured `.githooks/pre-commit` into two named functions: `run_java_checks` keeps the existing Spotless apply + re-stage flow and now fires only when staged Java files are present; the new `run_workflow_reindex_check` runs unconditionally after the JetBrains/youtrackdb origin gate, computes the staged workflow-doc list with `git diff --cached --name-only --diff-filter=ACMR` plus a regex matching both the live `.claude/{workflow,skills,agents}/` surface and the staged `docs/adr/*/_workflow/staged-workflow/.claude/{workflow,skills,agents}/` subtree, and invokes `python3 .claude/scripts/workflow-reindex.py --check --files <list>`. The hook shebang is now `#!/usr/bin/env bash` since the function-and-array shape needs bash. Created `.github/workflows/workflow-toc-check.yml` as a dedicated CI workflow on `ubuntu-latest`, triggered by `pull_request` with `paths:` filter on `.claude/**/*.md`, `.githooks/pre-commit`, the workflow file itself, and `docs/adr/**/_workflow/staged-workflow/.claude/**/*.md`, plus `workflow_dispatch:` for manual re-runs. Single step runs `python3 .claude/scripts/workflow-reindex.py --check` (full repo walk) after `actions/checkout@v6` (fetch-depth 0) and `actions/setup-python@v6` (Python 3.11), matching the project's `jmh-alerter-tests.yml` shape. Extended `IN_SCOPE_GLOBS` with three staged-subtree patterns; `_normalise_file_path` already used the discovered set for `--files` membership, so the extension widens the predicate without extra plumbing. Added `test_discover_in_scope_files_picks_up_staged_paths` (hermetic fixture with one staged workflow file, one staged skill, one staged agent); 75/75 tests pass. Manual dry-run of the hook on a synthetic conventions.md edit confirms the gate fires correctly.

**What was discovered:** The new CI workflow will report findings until Tracks 4 and 5 land — the `--check` full-repo walk surfaces ~1500 findings against the un-annotated live workflow tree. This matches the plan's "schema-only state (Track 1 landed, Track 4 not yet)" acceptance language; Track 4 reviewers should expect the gate to flip from red to green at the universal annotation rollout commit, not before. The workflow-reindex hook block was a natural no-op on this commit because none of the four files in the diff match the workflow regex — script + hook + CI infrastructure edits are correctly out-of-scope for the schema gate. See Episodes §Step 4.

**Key files:**
- `.githooks/pre-commit` (modified)
- `.github/workflows/workflow-toc-check.yml` (new)
- `.claude/scripts/workflow-reindex.py` (modified)
- `.claude/scripts/tests/test_workflow_reindex.py` (modified)

**Critical context:** For Step 5 test fixtures: the discovery walk now includes staged paths, so any fixture that places a staged-workflow file under `docs/adr/<dir>/_workflow/staged-workflow/.claude/...` and invokes `validate()` against that fixture root exercises the staged-path code path automatically. Step 5's `--write` halt-on-unresolved and out-of-scope `--files` skip tests can rely on the staged-path discovery being live without extra fixture plumbing. The orchestrator's earlier "in-scope set vs hook regex" question (Step 2 critical context) is resolved by extending `IN_SCOPE_GLOBS` to cover staged paths — the script and the hook now agree on the scope.

### Step 3 — commit 39fd19b356, 2026-05-28T14:47Z [ctx=info]
**What was done:** Extended `.claude/scripts/workflow-reindex.py` in place with the `--write` surface: TOC region rebuild from current per-section annotations (one row per `^## ` / `^### ` heading in document order, bootstrap heading exempt, empty-TOC for files with no `^## `) and in-file `§X.Y(z)` ref auto-stamp from the target heading's annotation. Atomicity follows a two-pass contract: `compute_write_plan` parses every in-scope file and builds the proposed mutations in memory; any unresolved in-file ref raises `UnresolvedInFileRefError` with every site listed and the CLI exits 2 with no disk writes anywhere (closes T5). Only when every file resolves cleanly does `apply_write_plan` push content to disk, skipping no-diff files to preserve mtimes (idempotence). Files without delimiter comments are a no-op for the TOC half. Cross-file `name.md§X.Y:roles:phases` suffixes are never touched (hand-written per D9). Added 16 new test cases on top of the 58 from prior commits; runner passes 74/74. Smoke-tested `--write` on the live tree: halted with exit 2 on the expected unannotated workflow files, `git status` clean.

**What was discovered:** In-file ref rewrites apply per-line in descending column-order so an earlier-column edit cannot shift a later-column edit's offsets; `_apply_line_rewrites` groups by line then reverse-sorts before applying. The TOC rebuild slices around the start/end delimiter line numbers captured at parse time, which is stable across in-file ref rewrites because ref edits change only columns past `§X.Y` and leave heading lines and fence lines byte-stable. The two mutations compose cleanly in either order. See Episodes §Step 3.

**Key files:**
- `.claude/scripts/workflow-reindex.py` (modified)
- `.claude/scripts/tests/test_workflow_reindex.py` (modified)

**Critical context:** Public surface for downstream steps: `compute_write_plan(repo_root, files_filter=...) -> Dict[str, FileWritePlan]` raises `UnresolvedInFileRefError` (CLI maps to exit 2); `apply_write_plan(plan) -> List[str]` returns repo-relative paths whose content actually changed (no-diff files skipped for idempotence). The `FileWritePlan.changed` flag drives the writer's skip logic and is the cheapest way to assert idempotence in tests (second pass reports `changed=False` for every file). Step 4 can pass staged paths through `--files` without extending the script's in-scope set; out-of-scope paths stay silently skipped per A6.

### Step 2 — commit 91748d1a6d, 2026-05-28T14:35Z [ctx=info]
**What was done:** Extended `.claude/scripts/workflow-reindex.py` in place with the eight schema-validation rules (1 stamp; 2 TOC presence; 3 TOC↔annotations match; 4 annotation-after-heading; 5a-5e annotation field well-formedness including the ≤120-char `summary=` cap; 6 cross-file ref subset with `any`-wildcard semantics; 7 bootstrap-block presence; 8 in-file `§X.Y(z)` auto-stamp suffix), the `--check` and `--files` CLI surfaces, and the exit-code contract (0 clean, 1 findings, 2 script error). Added 40 new test cases on top of the 18 from Step 1; the runner passes 58/58. `--files` with a fully out-of-scope set exits 0 silently per A6.

**What was discovered:** TOC region rows expose `§X.Y` anchors that the in-file ref scanner had to skip; otherwise every TOC anchor surfaced as a rule_8 unstamped finding against itself. The fix encodes the TOC-row skip inside `_iter_non_fenced_lines` so cross-file (rule 6) and in-file (rule 8) scanners share the exclusion. CLAUDE.md is explicitly out of scope per §1.8(e); a real-repo smoke run surfaced both bare and stamped CLAUDE.md refs that the regex correctly matched but the validator must drop, handled via a `_CROSS_FILE_REF_OUT_OF_SCOPE` frozenset shared by both ref code paths. Rule 1 (stamp on line 1) applies only to `_workflow/**` artifacts per §1.6; the validator gates rule 1 on `path.startswith("docs/adr/")` so live workflow files pass without a stamp. See Episodes §Step 2.

**Key files:**
- `.claude/scripts/workflow-reindex.py` (modified)
- `.claude/scripts/tests/test_workflow_reindex.py` (modified)

**Critical context:** Step 4's hook (per plan §"Hook regex widening" / T3) will pass staged paths under `docs/adr/*/_workflow/staged-workflow/.claude/...` via `--files`. The current script silently skips those paths because `IN_SCOPE_GLOBS` walks only live `.claude/workflow/` and `.claude/skills/` trees. For Step 2 alone the silent-skip is correct per A6. Step 4 needs to decide whether the script's in-scope set expands to cover staged paths (so the hook can validate uncommitted staged edits) or whether the hook gates only the live-path subset of its widened regex. Public surface stable: `validate(repo_root, files_filter=...)` returns sorted `Finding` records; `Finding.render()` emits the canonical `path:line:rule_N: explanation` line.

### Step 1 — commit f676e9172b, 2026-05-28T14:16Z [ctx=info]
**What was done:** Authored `.claude/scripts/workflow-reindex.py` as the shared parsing core that downstream Track 2 commits extend with `--check` and `--write` surfaces. The module covers file discovery against the fixed in-scope globs from design.md §"Reindex script" → §"Discovery mechanism", heading + annotation parsing (regex on `^## `, `^### `, and the line after), TOC region detection between `<!--Document index start/end-->` delimiters, the CommonMark fence + inline-backtick state machine (fenced half modeled on `design-mechanical-checks.py:209-263`; inline-backtick half new here per A2), and the staged-aware §1.8 bootstrap probe per D12 (single staged match wins; multiple matches halt with `AmbiguousBootstrapProbeError` that the CLI surfaces as exit 2; fallback to live `.claude/workflow/conventions.md` when no staged copy exists). Test runner at `.claude/scripts/tests/test_workflow_reindex.py` follows the project's `python3 file.py` shape (no pytest dependency on CI). 18 tests cover module-load smoke, three bootstrap configurations (live-only, staged-wins, multiple-staged-halts), annotation parsing (well-formed plus space-after-comma malformed), heading collection plus bootstrap-block exemption flag, TOC region detection, fence handling, inline-backtick spans, and in-scope file discovery.

**What was discovered:** The §1.8 phase-enum block has multi-line continuation lines where wrap-text begins with leading whitespace; a naive "first token per non-empty line" rule captured `non-workflow-modifying:` as a bogus 9th phase token. The fix requires enum tokens to start at column 0; continuation lines are wrap-text, not enum values. Step 2's rule 5d inherits the clean 15-role / 8-phase set with no further filtering. Track 4 (annotation rollout) and Track 5 (cross-ref enforcement) consume the bootstrap output through `load_bootstrap_enums(repo_root)` returning a `BootstrapEnums(roles, phases, source)` tuple, plus `parse_in_scope_files(repo_root)` returning `ParsedFile` records carrying a `fenced_lines` list for ref exclusion. Python 3.14's `@dataclass` requires the module to be in `sys.modules` before `exec_module` runs (PEP-451 contract); the test runner registers the module explicitly. See Episodes §Step 1.

**Key files:**
- `.claude/scripts/workflow-reindex.py` (new)
- `.claude/scripts/tests/test_workflow_reindex.py` (new)

**Critical context:** The annotation field regex anchors on `(?:^|\s)` before the field name and on `(?=\s|$|-->)` after the comma-list value to reject the `roles=foo, bar` shape (space after comma) that the no-space-around-commas rule forbids. A simpler word-boundary regex accepts the malformed shape because the captured value `foo` is a clean token. Step 2's rule 5a / 5b can rely on `Annotation.well_formed=False`; the `raw` field is preserved so the error message can name the offending substring.

## Validation and Acceptance

After this track lands:

- `python3 .claude/scripts/workflow-reindex.py --check` runs against the schema-only state (Track 1 landed, Track 4 not yet) and exits 0, 1, or 2 deterministically. Exit 1 surfaces specific files-lines-categories; exit 2 covers script error or ambiguous bootstrap probe.
- The script self-bootstraps from `conventions.md §1.8` via the D12 staged-aware probe: it reads §1.8 from `docs/adr/*/_workflow/staged-workflow/.claude/workflow/conventions.md` when present (single match enforced; multiple matches halt with exit 2), and falls back to live `.claude/workflow/conventions.md` otherwise.
- Rule 5 sub-checks all fire: summary >120 chars fails (5c, closes Track 1 WI7); spaces around commas in `roles=` / `phases=` fail (5a/5b); out-of-enum tokens fail (5d); malformed annotation comment fails (5e).
- Rule 6 cross-file subset violations surface with both sides named; `any`-wildcard semantics per §1.8(e) — `target.roles={any}` matches any citer role, `citer.roles={any}` requires `target.roles={any}`. Refs inside fenced code blocks AND inline backtick spans are excluded.
- Rule 8 in-file ref validation catches unstamped, stale-suffix, and unresolved refs. Inline-backtick refs are excluded.
- `python3 .claude/scripts/workflow-reindex.py --write` rebuilds TOCs idempotently and auto-stamps in-file refs. Halts with exit 2 on the first unresolved ref in a file and writes nothing (atomicity contract — even when other refs in the same file are auto-fixable). Skips refs inside fenced code blocks and inline backtick spans. Does NOT touch cross-file `name.md:roles:phases` suffixes.
- Out-of-scope SKILL.md paths passed via `--files` (e.g., a non-workflow-referencing skill that the hook's broader regex pulled in) are silently skipped; the script exits 0 on a fully-skipped `--files` set.
- Pre-commit hook (`.githooks/pre-commit`) is restructured so the workflow-reindex block runs even on commits that touch no Java files (the existing Java-only gate is factored into a function). The hook's filter matches both live `.claude/{workflow,skills,agents}/**/*.md` paths and staged `docs/adr/*/_workflow/staged-workflow/.claude/**/*.md` paths.
- New `.github/workflows/workflow-toc-check.yml` fires on PRs touching workflow content (live or staged paths) and fails the PR with the same findings.
- All six `review-workflow-*` agents (`context-budget`, `hook-safety`, `prompt-design`, `instruction-completeness`, `writing-style`, `consistency`) emit per-finding numeric prefixes from the family `WB / HS / PD / IC / WS / CN` under the existing `Critical / Recommended / Minor` severity headings (D11 expanded scope per A1). Context-budget additionally invokes the script and surfaces script findings as `WB<N>` items.
- Tests under `.claude/scripts/tests/test_workflow_reindex.py` cover the validation matrix described in Step 5 of Plan of Work and pass cleanly. The staged-aware §1.8 probe is exercised by three fixture configurations: live-only, staged + live (staged wins per D12), multiple staged matches (exit 2).

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery

- **Step 1** (script core + staged-aware probe): the new file `.claude/scripts/workflow-reindex.py` and the test-fixture skeleton under `.claude/scripts/tests/` are atomic file writes. Re-running against an already-applied state produces no diff. Recovery from any failure reverts via the implementer's standard `git reset --hard HEAD`.
- **Step 2** (validation rules + `--check`): extends Step 1's file in place. Re-running produces no diff. The `--check` exit-code contract (0 / 1 / 2) is the only externally observable behavior and is testable on fixtures without filesystem side effects. Recovery reverts via `git reset --hard HEAD`.
- **Step 3** (`--write`): extends the same script file in place plus adds `--write` test fixtures. Critical idempotence claim — running `python3 .claude/scripts/workflow-reindex.py --write` twice produces no diff after the first run. The halt-on-unresolved contract leaves the working tree unchanged on the failure path (atomic). Recovery reverts via `git reset --hard HEAD`.
- **Step 4** (hook restructure + new CI workflow): edits `.githooks/pre-commit` in place plus adds `.github/workflows/workflow-toc-check.yml`. The hook restructure is mechanical (move existing logic into a function, prepend the new gate). Re-running the edit against an already-applied state produces no diff. Recovery reverts via `git reset --hard HEAD`; if the new hook causes commit failures on subsequent steps, the revert restores the original Spotless-only hook. The new CI workflow only fires on PRs; its addition does not affect any in-flight commit.
- **Step 5** (six agent prefix family + tests): edits six `.claude/agents/review-workflow-*.md` files in place plus extends the existing `test_workflow_reindex.py` from Step 1 with the full validation matrix. The six agent edits are template-bound (prepend `**<PFX><N>** — ` to the existing per-finding bullet shape in each Output Format section); re-running produces no diff. The test file is additive. Recovery reverts via `git reset --hard HEAD`.

Track-level recovery: each step's commit is independently revertable. A failure at Step N can be addressed by `git revert <step-N-SHA>` while preserving Steps 1..N-1. The staged conventions.md §1.8(e) clarifications committed by Phase A are independent of the script implementation and survive any Step revert; they would only need rollback if the design itself is reopened (ESCALATE territory).

## Base commit

1c18a0b5ca7b0a711bc070f2f80e22b2a00c43c8

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't
belong to one specific step. Per-step episode content lives in
`## Episodes` above. Often empty. -->

## Interfaces and Dependencies

### In-scope file set

- `.claude/scripts/workflow-reindex.py` (new)
- `.claude/scripts/tests/test_workflow_reindex.py` (new)
- `.githooks/pre-commit` (restructured: Java-only gate factored, workflow-reindex block appended)
- `.github/workflows/workflow-toc-check.yml` (new dedicated CI workflow)
- `.claude/agents/review-workflow-context-budget.md` (modified — script invocation + `WB<N>` prefix)
- `.claude/agents/review-workflow-hook-safety.md` (modified — `HS<N>` prefix)
- `.claude/agents/review-workflow-prompt-design.md` (modified — `PD<N>` prefix)
- `.claude/agents/review-workflow-instruction-completeness.md` (modified — `IC<N>` prefix)
- `.claude/agents/review-workflow-writing-style.md` (modified — `WS<N>` prefix)
- `.claude/agents/review-workflow-consistency.md` (modified — `CN<N>` prefix)

### Out-of-scope

- `conventions.md §1.8` — Track 1.
- Per-section annotation rollout — Track 4.
- Telemetry script — Track 3.

### Inter-track dependencies

- **Depends on Track 1.** Script reads enum tokens from `conventions.md §1.8`. Tests use a fixture, but the production self-bootstrap path needs §1.8 to exist.
- **Unblocks Track 4.** Annotation rollout uses `--write` to scaffold TOCs and `--check` to validate.
- **Unblocks Track 5.** Cross-reference suffix CI enforcement lives in the script.

### Library/function signatures touched

- `workflow-reindex.py` exports CLI: `--check` (exit 0/1/2), `--write` (in-place TOC rebuild + in-file ref auto-stamp), `--files <space-separated>` (scope to listed files; used by pre-commit hook; out-of-scope paths silently skipped). No public Python API; all interaction is CLI.
- `review-workflow-context-budget.md` agent system prompt: section added that invokes the script and folds script findings into the existing severity buckets. The agent's output gains a per-finding `WB<N>` prefix.
- The other five `review-workflow-*` agents (`hook-safety`, `prompt-design`, `instruction-completeness`, `writing-style`, `consistency`) each gain a parallel per-finding numeric prefix from the family `HS / PD / IC / WS / CN` per D11 expanded scope. Output Format edits are template-bound (placement under existing `Critical / Recommended / Minor` headings); finding content semantics unchanged.
