<!-- workflow-sha: 0676e2446f373e969da86da6748c91d442135161 -->
# Track 1: Detection core, modes, and JSON emit

## Purpose / Big Picture
After this track lands, `workflow-startup-precheck.sh` exists and emits correct JSON for the read-only detection paths — branch divergence, the two-phase drift walk, and the handoff scan — across all three `--mode` outputs.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Track 1 scaffolds `workflow-startup-precheck.sh` with `--mode` plumbing and the single jq emit point, then builds the read-only detection: branch divergence, the two-phase drift walk (Phase 1 stamp walk + Phase 2 fold and `git log`), the handoff scan, and the reduced `divergence-only` and `migrate-range` outputs (including `(file, sha)` pairs and an optional `--bootstrap-sha`). It defines the `actions_taken` field that Track 3 populates. The script is authored live under `.claude/scripts/`; this track adds no side effects — every detection function is read-only.

## Progress
- [x] 2026-06-02T14:51Z [ctx=info] Review + decomposition complete
- [x] 2026-06-02T15:10Z [ctx=safe] Step 1 complete (commit bf6fca2b3f)
- [x] 2026-06-02T15:18Z [ctx=safe] Step 2 complete (commit 9cd797f0fc)
- [x] 2026-06-02T15:27Z [ctx=safe] Step 3 complete (commit d4f5a38eed)
- [x] 2026-06-02T15:39Z [ctx=safe] Step 4 complete (commit ffb217a754)
- [x] 2026-06-02T15:46Z [ctx=safe] Step 5 complete (commit 9b91772c76)
- [x] 2026-06-02T15:58Z [ctx=safe] Step 6 complete (commit fbe9349d0b)
- [x] 2026-06-02T15:58Z [ctx=safe] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

- 2026-06-02T15:10Z Step 1 plumbed the Track 2 seam (`STATE_JSON` → `null`)
  and the Track 3 seam (`ACTIONS_TAKEN_JSON` → `[]`) as overridable shell
  variables in `emit_json`; Tracks 2 and 3 set the variable rather than
  re-editing the jq call. See Episodes §Step 1.

- 2026-06-02T15:18Z Step 2 built the reusable `GitFixture` git-fixture
  builder (`file://` bare remotes, `GIT_CONFIG_GLOBAL`/`SYSTEM=/dev/null`
  isolation, `orphan_branch` for no-common-ancestor histories); Steps 3, 4,
  6 and Tracks 2, 3 reuse it for hermetic git state. Precheck tests that hit
  git detection must run inside a `GitFixture`, never bare-cwd, or they
  perform a real network `git fetch`. See Episodes §Step 2.

- 2026-06-02T15:27Z Step 3 extended `GitFixture` with `plan_artifact(relpath,
  stamp=...)`, `handoff(name)`, and a `plan_dir` property for fabricating
  stamped/unstamped `_workflow/` artifacts. Caveat for Tracks 2 and 3: the
  byte-source `PLAN_DIR` resolves to `docs/adr/<branch>` and the default
  fixture branch is `main`, so fixture plan artifacts must live under
  `docs/adr/main/_workflow/` (or pass a matching `default_branch` to
  `GitFixture`). See Episodes §Step 3.

- 2026-06-02T15:39Z Step 4 made the merge-base fold a shared
  `fold_stamps_to_base("break"|"continue")` function (the Step 6
  `migrate-range` seam). Track 3's no-drift normalization fires on the
  empty-range all-stamped path this step reports (`detected=false`,
  `base_sha` filled, `commit_count=0`) when `STAMPED_SHAS` holds more than one
  distinct SHA; `DRIFT_NORMALIZATION_LANDED` stays hard-false until Track 3.
  The fold function and the new `GitFixture` real-commit helpers are reusable
  by Tracks 2 and 3. See Episodes §Step 4.

- 2026-06-02T15:58Z Step 6 finalized the `migrate-range` JSON shape that
  Track 4's `migrate-workflow` SKILL Step 2 rewrite cites:
  `stamped_artifacts [{file,sha}]`, `unstamped_files [path]`, `base_sha`
  (`null` on a failed/empty fold), `log_range [{sha,subject}]` with full
  40-hex `%H` SHAs (distinct from the drift range's short `%h`), and
  `merge_base_failed [{base,sha,files}]` resolving failing SHAs to owning
  artifact paths via `STAMPED_PAIRS`. Track 4 reads these fields rather than
  the prose byte-copy. See Episodes §Step 6.

## Decision Log
<!-- Continuous-log. Execution-time decisions: inline-replan choices,
scope-downs, dependency reveals, gate-override reasons. -->

- **Test-harness language = Python (stand-alone runner).** D1 fixes the
  *script* as bash; the *test-harness* language was left open. Phase A
  chooses Python to match the existing `.claude/scripts/tests/` convention
  (pytest is absent on CI, so each test file is a stand-alone Python 3
  runner) — the harness shells out to the bash script and asserts on its
  JSON. Phase A review convergence drove this: technical, risk, and
  adversarial all flagged the unspecified harness language.

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->

- [x] Technical: PASS at iteration 2 (4 findings, 4 accepted) — T1 migrate-range-fold-distinction, T2 fetch-failed-acceptance (should-fix); T3 full git idiom, T4 empty-input pin (suggestion).
- [x] Risk: PASS at iteration 2 (5 findings, 5 accepted) — R1 fold byte-parity (== T1), R2 jq null-vs-empty contract, R3 shell-strictness/empty-handoff (should-fix); R4 conformance-test scoping, R5 git-fixture-builder infra (suggestion).
- [x] Adversarial: PASS at iteration 2 (6 findings, 4 accepted, 2 deferred) — A1 divergence-fixture + harness language, A2 state:null stub, A3 conformance source-extraction (should-fix); A5-actionable single-shared-fold + A6 step-budget split (suggestion, applied in decomposition); A4 (D1 rationale) and A5-rationale (D2 rationale) deferred — decisions survive per the reviewers' own survival tests and Decision Records are immutable mid-execution; recorded as Phase 4 design-final/adr rationale-pass candidates.

## Context and Orientation

Today the startup detection bash is scattered across three live files that this track reads as its byte-source spec:

- `branch-divergence-check.md` — the ahead/behind detection with the upstream and fetch guards.
- `workflow-drift-check.md § Detection` — the two-phase drift walk (Phase 1 stamp classification, Phase 2 fold + `git log`).
- `conventions.md §1.6(h)` — the canonical artifact walk that the drift Detection byte-copies; `§1.6(a1)` carries the anchored stamp regex.

The script is new and lives at `.claude/scripts/workflow-startup-precheck.sh`, alongside the existing `statusline-command.sh` and `session-stats.py`. `jq` (v1.8.1) is present and required.

Concrete deliverables of this track:

- `.claude/scripts/workflow-startup-precheck.sh` with `--mode {full,divergence-only,migrate-range}` dispatch, an `--bootstrap-sha` option for `migrate-range`, and one jq assembly point.
- Divergence detection populating `divergence` (`detected`, `ahead`, `behind`, `skipped`, `skip_reason`).
- The two-phase drift walk populating `drift` (`detected`, `kind` ∈ {stamped, unstamped, merge-base-failed}, `base_sha`, `commit_count`, `first_commits`, `normalization_landed`).
- The handoff scan populating `handoffs` in `ls -t` mtime order.
- The reduced `divergence-only` and `migrate-range` JSON shapes, including `migrate-range`'s `stamped_artifacts` `(file, sha)` pairs and `unstamped_files` list.
- The `actions_taken` field, defined as an empty array (Track 3 wires the normalization commit into it).
- An initial `.claude/scripts/tests/` harness with fixtures for the divergence and drift gate paths and the reduced-mode shapes.

The `state` key of `full`-mode JSON is stubbed as JSON `null` in this track (Track 2 fills it), and `actions_taken` stays empty until Track 3. The track-internal topology — arg-parse → mode dispatch → detection functions → single jq emit — is the live half of the plan's Component Map; it is not repeated as a track-level diagram here.

## Plan of Work

The approach builds the script outside-in: scaffold and the emit contract first, then each detection function, then the reduced-mode shapes.

1. **Scaffold + mode dispatch.** Shebang (no global `set -e`, matching `statusline-command.sh` and the byte-source blocks, which rely on defensive `|| true` rather than errexit, so the empty-handoff and no-divergence paths cannot abort mid-script), a header comment citing `conventions.md §1.6(h)` as the walk's spec, `--mode` argument parsing for the three modes plus `--bootstrap-sha`, and an unknown-mode error that exits non-zero with usage. Add a single `emit_json` function stub.
2. **The jq emit point.** One function assembles the JSON from plain shell variables, per mode. jq makes quoting and escaping correct by construction; emitting JSON `null` for an absent scalar is **not** automatic — the naive `--arg x "$VAR"` form emits `""` for an empty variable, so each nullable scalar uses the explicit `($x | if . == "" then null else . end)` idiom (and `... else tonumber end` for counts). This is the only site that knows the JSON shape, so the contract has one authoring home.
3. **Branch divergence detection.** Ahead/behind counts via the full byte-source idiom `git rev-list --left-right --count HEAD...'@{u}'` (prints `<ahead>\t<behind>`; both non-zero ⇒ diverged), with the upstream guard (`@{u}` absent → `skipped=true`, `skip_reason="no-upstream"`, `detected=false`) and the fetch guard (`git fetch` fails → `skipped=true`, `skip_reason="fetch-failed"`, `detected=false`). Byte-source: `branch-divergence-check.md § Detection`.
4. **Drift Phase 1 + Phase 2.** Byte-copy the `§1.6(h)` artifact walk to classify each artifact stamped/unstamped using the anchored `§1.6(a1)` regex. Phase 2 folds the stamp set pairwise through `git merge-base` to derive `BASE_SHA` using the `full`-mode (drift-check) shape — `break` on the first merge-base failure, capturing the single failing pair — then runs `git log --reverse BASE_SHA..HEAD` on the trailing-slash workflow pathspecs (`.claude/workflow/ .claude/skills/`, oldest-first) and sets `drift.kind`, `base_sha`, `commit_count`, and `first_commits`. The empty-input (both stamped and unstamped sets empty → silent no-drift), unstamped, and merge-base-failed short-circuits set `kind` accordingly with null scalars. Byte-source: `workflow-drift-check.md § Detection`.
5. **Handoff scan.** `ls -t` the active plan's `handoff-*.md`, preserve the mtime order, populate `handoffs`.
6. **Reduced-mode outputs.** `divergence-only` emits only `divergence` and `actions_taken`. `migrate-range` runs the artifact walk and a fold that byte-copies the `/migrate-workflow` SKILL Step 2 **continue-and-collect** shape (distinct from `full`-mode's `break` shape): it `continue`s past each merge-base failure to collect **every** failing pair, carries a `STAMPED_PAIRS` `(file=sha)` array so `merge_base_failed` resolves to failing **artifact paths** (not bare SHAs), folds in `--bootstrap-sha` when supplied, and emits `stamped_artifacts` as `(file, sha)` pairs, `unstamped_files`, `base_sha`, and the `git log` range. It emits no `state`, `handoffs`, or `divergence`. Byte-source: `migrate-workflow/SKILL.md` Step 2, which states the continue-vs-break contrast explicitly. In the script the two modes share one fold shell function parameterized by failure-handling (break vs continue), so the in-script fold stays single-sourced the way D4 single-sources the prose walk.

Ordering constraints and invariants to preserve: detection functions write plain shell variables only; the jq step is the sole JSON-authoring site (one-contract-home invariant). The artifact walk stays byte-identical to `§1.6(h)`. The `full`-mode `state` key is emitted as JSON `null` in this track — Track 2 replaces that `null` with the populated `{phase, track, substate}` object, so the stub shape is pinned and Track 2's first change is a clean `null` → object diff. The script performs no mutation in this track — divergence and the drift walk are read-only, and the `normalization_landed` flag is hard-false until Track 3.

The `## Concrete Steps` roster below decomposes this into six steps; per the Phase A review's fixture-cost guidance, step 2 bundles the reusable git-fixture builder with divergence detection and steps 3-4 split the drift walk (Phase 1 classification, Phase 2 fold) so the merge-base fixture work is budgeted on its own.

## Concrete Steps

1. Scaffold `workflow-startup-precheck.sh` — shebang (no global `set -e`), `§1.6(h)` header, `--mode {full,divergence-only,migrate-range}` + `--bootstrap-sha` parsing, unknown-mode error (non-zero exit + usage, no JSON), the single `emit_json` jq function with the explicit empty→null idiom, and `actions_taken` defined as an empty array; plus the initial Python stand-alone-runner test asserting the unknown-mode path and the jq null-vs-empty contract on synthetic vars — risk: medium (new component behavior: the one-contract-home jq emit + null idiom is the load-bearing S1 emit surface)  [x] commit: bf6fca2b3f
2. Branch divergence detection + reusable git-fixture builder — `git rev-list --left-right --count HEAD...'@{u}'` with the upstream and fetch guards populating `divergence{detected,ahead,behind,skipped,skip_reason}`; introduce the Python git-fixture builder (temp `git init`, commit/branch/set-upstream, local `file://` bare remote) and clean / divergence / no-upstream / fetch-failed fixtures — risk: medium (new shared test infrastructure + new detection behavior)  [x] commit: 9cd797f0fc
3. Drift Phase 1 — artifact walk + classification — byte-copy the `§1.6(h)` walk (anchored `§1.6(a1)` regex) classifying stamped/unstamped, plus the `§1.6(h)` source-extraction conformance test (glob-set + regex compared against the canonical block, `STAMPED_PAIRS` pairing whitelisted) and stamped / unstamped / empty-input fixtures — risk: medium (byte-parity logic; the conformance test is the spec-drift guard)  [x] commit: d4f5a38eed
4. Drift Phase 2 — pairwise merge-base fold + `git log` — the `full`-mode `break`-shape fold deriving `BASE_SHA`, `git log --reverse` on the trailing-slash pathspecs populating `base_sha`/`commit_count`/`first_commits`, the merge-base-failed short-circuit (null scalars), and the shared fold shell function parameterized by failure-handling; drift-detected / merge-base-failed / staged-subtree-exclusion fixtures — risk: medium (subtlest logic in the track; byte-parity with `workflow-drift-check.md § Detection`)  [x] commit: ffb217a754
5. Handoff scan + `state` stub — `ls -t handoff-*.md` (mtime order, empty-safe) populating `handoffs`, and `state` emitted as JSON `null`; handoffs-present (mtime order) and clean (empty `[]`) fixtures — risk: low (default: routine `ls -t` plus a null stub, fully fixture-covered, no MEDIUM trigger)  [x] commit: 9b91772c76
6. Reduced-mode outputs `divergence-only` + `migrate-range` — `divergence-only` emits only `divergence` + `actions_taken`; `migrate-range` runs the continue-and-collect fold (collect every failing pair), the `STAMPED_PAIRS` `(file=sha)` array resolving `merge_base_failed` to artifact paths, the `--bootstrap-sha` fold-in, and `stamped_artifacts (file,sha)` + `unstamped_files` + `base_sha` + `git log` range, emitting no `state`/`handoffs`/`divergence`; divergence-only / migrate-range-stamped / multi-failure-collect-all / `--bootstrap-sha` fixtures — risk: medium (the continue-vs-break fold distinction is the T1/R1 byte-parity hazard; parameterized fold reuse)  [x] commit: fbe9349d0b

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step, identified by step number + commit SHA. Empty at
Phase 1; Phase A does not populate. -->

### Step 1 — commit bf6fca2b3f, 2026-06-02T15:10Z [ctx=safe]
**What was done:** Scaffolded `.claude/scripts/workflow-startup-precheck.sh`:
shebang with no global `set -e`, a header citing `conventions.md §1.6(h)`
(the Phase 1 walk spec) and `§1.6(a1)` (the anchored value-extraction
regex), `--mode {full,divergence-only,migrate-range}` plus `--bootstrap-sha`
parsing, an unknown/missing-mode error path that exits 2 with usage on
stderr and no stdout JSON, and the single `emit_json` jq function carrying
the empty→null idiom `($x | if . == "" then null else . end)`.
`actions_taken` is a pinned empty array and `full`-mode `state` is JSON
`null`. Added the initial Python stand-alone-runner test (8 cases) over the
error path, the three valid mode shapes, and the null-vs-empty contract; all
8 pass.

**What was discovered:** The null idiom needs a live witness rather than a
hard-coded `null`, or the load-bearing emit surface would not be exercised by
this step's test. `migrate-range`'s `base_sha` is wired through the idiom from
`--bootstrap-sha` (absent → `null`, present → SHA); Step 6 replaces that
source with the folded `BASE_SHA`, and the idiom plus its test stay in place.
The Track 2 seam (`STATE_JSON` → `null`) and Track 3 seam
(`ACTIONS_TAKEN_JSON` → `[]`) are plumbed as overridable shell variables that
default correctly, so those tracks set the variable rather than re-editing the
jq call.

**Key files:**
- `.claude/scripts/workflow-startup-precheck.sh` (new)
- `.claude/scripts/tests/test_workflow_startup_precheck.py` (new)

### Step 2 — commit 9cd797f0fc, 2026-06-02T15:18Z [ctx=safe]
**What was done:** Added branch-divergence detection from the
`branch-divergence-check.md § Detection` byte-source: the upstream guard
(`@{u}` absent → `skipped=true`, `skip_reason="no-upstream"`), the fetch
guard (`git fetch` fails → `skipped=true`, `skip_reason="fetch-failed"`),
and `git rev-list --left-right --count HEAD...'@{u}'` with `detected=true`
only when both `ahead` and `behind` are non-zero. `detect_divergence` writes
plain shell variables; a `divergence_json` helper assembles the object
through the existing empty→null idiom (counts emit JSON `null` on skip, JSON
numbers otherwise). Divergence is wired into `full` and `divergence-only`;
`migrate-range` skips it. Introduced the reusable Python git-fixture builder
(a `GitFixture` context manager: temp `git init`, commit, `file://` bare
remote via `add_bare_remote` / `advance_remote` / `break_remote`, plus an
`orphan_branch` helper reserved for Step 4's merge-base-failed fixtures) and
four divergence fixtures (clean / diverged / no-upstream / fetch-failed)
plus a full-mode-divergence-present case. 13/13 tests pass.

**What was discovered:** Wiring divergence into `full` / `divergence-only`
regressed the two pre-existing mode-shape tests: they ran bare-cwd (the
runner's real checkout), so they began performing a real `git fetch` against
the GitHub origin — network-dependent and CI-flaky. Fixed by moving both
onto a clean `GitFixture` (`file://` remote, no network) with unchanged shape
assertions; the error-path, null-idiom, and `migrate-range` tests stay
bare-cwd because those paths run no git detection. `GitFixture` isolates from
host git config via `GIT_CONFIG_GLOBAL`/`GIT_CONFIG_SYSTEM=/dev/null` and pins
the initial branch with `-b main` so the working repo and bare remote agree
on the default branch (a mismatch silently breaks the divergence push).

**Key files:**
- `.claude/scripts/workflow-startup-precheck.sh` (modified)
- `.claude/scripts/tests/test_workflow_startup_precheck.py` (modified)

**Critical context:** The `GitFixture` builder is the shared git-fixture
infrastructure Steps 3, 4, and 6 reuse; its `cwd=` parameter on
`run_precheck` and the `orphan_branch` helper are the seam those steps
extend for stamp fabrication and merge-base-failed histories.

### Step 3 — commit d4f5a38eed, 2026-06-02T15:27Z [ctx=safe]
**What was done:** Added the drift Phase 1 artifact walk and classification.
`detect_drift` resolves the active plan dir from the current branch (`§1.6(g)`
→ `docs/adr/<branch>`), byte-copies the `§1.6(h)` Phase 1 walk with the
anchored `§1.6(a1)` value-extraction regex into the script-scoped
`STAMPED_SHAS` / `UNSTAMPED_FILES` sets, and resolves the three Phase 1
outcomes: empty-input → silent no-drift (`detected=false`, `kind=null`); any
unstamped → `detected=true`, `kind="unstamped"`; all-stamped → `kind="stamped"`
with the fold scalars left `null`. A `drift_json` helper assembles the object
through the same empty→null idiom, and `full` mode emits the populated drift
object in place of the scaffold `null`. Added a `§1.6(h)` source-extraction
conformance test (glob set + anchored regex compared against the canonical
block, `STAMPED_PAIRS` whitelisted and pinned absent from the drift walk) plus
stamped / unstamped / empty-input fixtures with strict JSON-null assertions.
19/19 tests pass (13 pre-existing + 6 new).

**What was discovered:** The conformance test first failed on the
`track-*.md` glob — the canonical `§1.6(h)` idiom closes the shell quote
mid-path (`"$PLAN_DIR/_workflow/plan/"track-*.md`), so a raw substring match
on the unquoted tail spuriously missed it. Both the canonical block and the
script carry this exact form, so the fix was in the test's normalizer
(`_glob_tails` strips quotes, the `$PLAN_DIR` prefix, and the `ls` tail before
comparing), not in the script. The branch-resolved `PLAN_DIR` is the one line
that legitimately differs from the `§1.6(h)` literal placeholder; the
conformance test compares the glob set and regex, not the `PLAN_DIR`
assignment, matching the byte-source's own "substituted at invocation time"
statement.

**Key files:**
- `.claude/scripts/workflow-startup-precheck.sh` (modified)
- `.claude/scripts/tests/test_workflow_startup_precheck.py` (modified)

**Critical context:** The Phase 1 → Phase 2 seam is exact: for the
all-stamped case `detect_drift` sets only `DRIFT_KIND="stamped"` and returns,
leaving `DRIFT_DETECTED=false` and the fold scalars (`DRIFT_BASE_SHA`,
`DRIFT_COMMIT_COUNT`, `DRIFT_FIRST_COMMITS_JSON`) at their null/`[]` defaults.
Step 4 extends that all-stamped branch with the merge-base fold + `git log`
and overrides `DRIFT_DETECTED`, so its first change is a clean fill of the
null scalars plus adding `kind="merge-base-failed"`. `STAMPED_SHAS` /
`UNSTAMPED_FILES` are script-scoped so Step 4's fold and Step 6's
`migrate-range` reuse the same walk output. The conformance test
`conformance_script_walk_carries_no_stamped_pairs_yet` pins the drift walk
WITHOUT `STAMPED_PAIRS`; Step 6's `migrate-range` walk adds the `$f=$SHA`
pairing as the whitelisted `§1.6(h)` extension, so Step 6 must scope that
"no STAMPED_PAIRS" assertion to the drift walk only, not the migrate-range
walk.

### Step 4 — commit ffb217a754, 2026-06-02T15:39Z [ctx=safe]
**What was done:** Extended the all-stamped drift branch with Phase 2: the
`break`-on-first-failure pairwise merge-base fold from
`workflow-drift-check.md § Detection` deriving `BASE_SHA`, then
`git log --reverse --format='%h %s' BASE_SHA..HEAD -- .claude/workflow/
.claude/skills/` populating `base_sha` / `commit_count` (full-range total) /
`first_commits` (first 10, oldest-first, each `{sha, subject}`). A merge-base
failure short-circuits to `kind="merge-base-failed"` with null fold scalars
and `detected=true`; an empty range is no-drift with `base_sha` filled and
`commit_count=0`. The fold lives in a shared `fold_stamps_to_base` function
parameterized by failure handling (break vs continue) so Step 6's
`migrate-range` reuses one fold, mirroring the single-sourced prose walk.
Added five drift fixtures (drift-detected, empty-range no-drift,
merge-base-failed via orphan branch, staged-subtree exclusion, real-vs-staged
distinguishing pair) and extended `GitFixture` with `head_sha` / `checkout` /
`workflow_commit` / `staged_workflow_commit` plus a capturing `_git_out`.
24/24 tests pass; the script runs clean on the real repo (`detected=false`,
`kind="stamped"`, `base_sha=0676e2446f`, `commit_count=0`).

**What was discovered:** Step 3's all-stamped seam test pinned the boundary
with synthetic SHAs (`b*40` / `c*40`); Phase 2 feeds those to
`git merge-base`, which fails on unresolvable SHAs and routes to
`kind="merge-base-failed"`, so that test was reworked to stamp with a real
HEAD commit and assert the resolved-fold no-drift read (renamed
`drift_all_stamped_classifies_stamped_then_folds`). Phase 2 fixtures must use
real commit SHAs, not synthetic ones, because the fold resolves them.
Separately, `GitFixture.orphan_branch` left the prior branch's files as
untracked working-tree files, blocking `git checkout` back to the default
branch ("untracked files would be overwritten"); fixed by wiping the working
tree after `git rm -rfq --cached .` so a later checkout restores the default
branch's tracked files cleanly.

**Key files:**
- `.claude/scripts/workflow-startup-precheck.sh` (modified)
- `.claude/scripts/tests/test_workflow_startup_precheck.py` (modified)

**Critical context:** `fold_stamps_to_base` is shared and parameterized by
its first positional arg (`"break"` | `"continue"`), writing `FOLD_BASE_SHA`
and `FOLD_FAILED_PAIRS` (space-delimited `BASE,SHA` pairs). Step 6's
`migrate-range` calls it with `"continue"` to collect every failing pair and
resolves them to artifact paths via its own `STAMPED_PAIRS` table.
`WORKFLOW_PATHSPECS=".claude/workflow/ .claude/skills/"` (trailing slashes,
unquoted-splice word list) is script-scoped and reused by the `git log`.
`GitFixture` now exposes `head_sha()`, `checkout(branch)`, `workflow_commit()`,
`staged_workflow_commit()`, and `_git_out()` for Step 6's fixtures. The
conformance test `conformance_script_walk_carries_no_stamped_pairs_yet` still
pins the drift walk WITHOUT `STAMPED_PAIRS` — Step 6 must scope that assertion
to the drift walk only when it adds the migrate-range pairing.

### Step 5 — commit 9b91772c76, 2026-06-02T15:46Z [ctx=safe]
**What was done:** Added the pending mid-phase handoff scan to full mode. A
new `scan_handoffs` function runs the canonical
`ls -t <plan_dir>/_workflow/handoff-*.md 2>/dev/null` idiom (byte-source:
`workflow.md § Startup Protocol` step 4 / `mid-phase-handoff.md`), resolving
the plan dir from the current branch the same way `detect_drift` does
(`§1.6(g)` → `docs/adr/<branch>`). It reports the file basenames (per
`design.md § "The JSON contract"`) in `ls -t` newest-first mtime order,
building the array with `jq -Rnc '[inputs]'`. The empty-safe path (`|| true`
plus a `[ -z ]` guard) collapses a no-match glob to the empty array.
`HANDOFFS_JSON` is wired into the full-mode emit via `--argjson` (replacing
the pinned `handoffs: []`). The `state` key was already JSON `null` from the
scaffold; this step pins it with a strict-null fixture. Added three tests
(handoffs mtime-order, handoffs-empty, state-null seam); 27/27 pass and the
script runs clean on the real repo (`handoffs=[]`, `state=null`, exit 0).

**What was discovered:** `ls` exits non-zero on a no-match glob even with
`2>/dev/null` swallowing the diagnostic, so the scan needs an explicit
`|| true` (matching the no-errexit script convention) or the empty-handoff
path would carry a failure status. The mtime-order fixture must force distinct
mtimes via `os.utime` — files authored in the same wall-clock second sort
ambiguously under `ls -t` and would flake; git operations do not alter
working-tree mtimes after the write, so the `os.utime` stamps hold.

**Key files:**
- `.claude/scripts/workflow-startup-precheck.sh` (modified)
- `.claude/scripts/tests/test_workflow_startup_precheck.py` (modified)

**Critical context:** `HANDOFFS_JSON` is a script-scoped JSON-array literal
defaulting to `[]`, consumed by `emit_json` via `--argjson handoffs`, and
`scan_handoffs` runs in the full dispatch case only — `divergence-only` and
`migrate-range` correctly omit `handoffs`. The state seam stays JSON `null`
via `--argjson state "${STATE_JSON:-null}"`, now pinned by
`test_state_stub_is_json_null_in_full_mode`; Track 2 sets the `STATE_JSON`
variable to the populated `{phase, track, substate}` object rather than
re-editing the jq call, and updates that test.

### Step 6 — commit fbe9349d0b, 2026-06-02T15:58Z [ctx=safe]
**What was done:** Implemented `migrate-range` detection in place of the
scaffold stub. Added `detect_migrate_range` (after `detect_drift`, keeping
the drift walk first in the file): a second `§1.6(h)` byte-copy artifact walk
extended with the whitelisted `STAMPED_PAIRS` (`$f=$SHA`) pairing, the shared
`fold_stamps_to_base` called with `"continue"` to collect every failing pair,
an optional `--bootstrap-sha` folded into the input set, a `STAMPED_PAIRS`
resolver (`mr_files_for_sha`) mapping failing SHAs to artifact paths, and a
`git log --reverse --format='%H %s'` range over the workflow pathspecs (full
`%H` SHAs, no head cap). Rewrote the `emit_json` `migrate-range` branch to
emit `stamped_artifacts ({file,sha})`, `unstamped_files`, `base_sha` (through
the empty→null idiom, now sourced from the fold), `log_range ({sha,subject})`,
and `merge_base_failed ({base,sha,files})`. Added four migrate-range behavior
tests (stamped-pairs+log-range, unstamped-reporting, bootstrap-fold-in,
multi-failure-collect-all), reworked the null-vs-empty and migrate-range-shape
tests onto `GitFixture`s, and split the `§1.6(h)` conformance suite to check
both walks. 32/32 tests pass; all three modes run clean on the real repo.

**What was discovered:** The continue fold's reset-then-reseed shape
(byte-identical to the SKILL Step 2 byte-source) means a merge-base failure
consumes the boundary: after a failure the running base resets and the next
stamp re-seeds without a merge-base call. Collecting TWO failing pairs needs
the walk order `[real, orphan, real, orphan]`, not three consecutive orphan
stamps. The `§1.6(h)` walk's `ls` sorts all operands lexically (`design.md` <
`implementation-plan.md` < `plan/track-1.md` < `plan/track-2.md`), so the
multi-failure fixture stamps four artifacts real/orphan/real/orphan in that
sorted order to force two non-consecutive failing merge-base calls.

**What changed from the plan:** none. `divergence-only` was already complete
from Step 2 (it emits only `{divergence, actions_taken}`), so this step added
no `divergence-only` code; the existing divergence-only-shape test is the
step's fixture. `base_sha`'s source moved from `--bootstrap-sha` (scaffold) to
the fold output, exactly as Step 1's episode anticipated; the empty→null idiom
and its test stay, now pinned through the fold path.

**Key files:**
- `.claude/scripts/workflow-startup-precheck.sh` (modified)
- `.claude/scripts/tests/test_workflow_startup_precheck.py` (modified)

**Critical context:** The conformance suite now distinguishes the two
`§1.6(h)` walks by the presence/absence of `STAMPED_PAIRS`, not by file
position: `_extract_drift_walk` selects the walk WITHOUT the pairing,
`_extract_migrate_range_walk` selects the one WITH it; the glob-set and
anchored-regex checks run against both. The old
`conformance_script_walk_carries_no_stamped_pairs_yet` was renamed to
`conformance_drift_walk_carries_no_stamped_pairs` and a positive
`conformance_migrate_range_walk_carries_stamped_pairs` added. This closes the
last Track 1 read-only detection step — `full` / `divergence-only` /
`migrate-range` all emit their final JSON shapes (`state` still `null` pending
Track 2, `actions_taken` still empty pending Track 3).

## Validation and Acceptance

Track-level behavioral acceptance:

- `--mode full` on a clean fixture emits valid JSON with `divergence.detected=false`, `drift.detected=false`, `handoffs=[]`, `actions_taken=[]`, and `state` as JSON `null` (the Track 2 seam stub); the script exits 0.
- `--mode full` on a divergence fixture reports correct `ahead` and `behind` counts; on a no-upstream fixture it reports `skipped=true` with `skip_reason="no-upstream"` and `detected=false`; on a fetch-failing fixture (upstream pointing at an unreachable or removed remote) it reports `skipped=true` with `skip_reason="fetch-failed"` and `detected=false`.
- `--mode full` on a drift fixture reports `drift.detected=true` with the correct `base_sha`, `commit_count`, and `first_commits` ordered oldest-first; on an unstamped fixture it reports `drift.kind="unstamped"` with scalars asserted as JSON `null` (via `jq -e '.drift.base_sha == null'`, not a truthiness check); on an empty-`_workflow/` fixture (only a transient `handoff-*.md`, no stampable artifact) it reports `drift.detected=false` with null scalars, distinct from the all-stamped clean case.
- `--mode divergence-only` emits only `divergence` and `actions_taken`.
- `--mode migrate-range` emits `stamped_artifacts` `(file, sha)` pairs, `unstamped_files`, `base_sha`, the `git log` range, and folds in `--bootstrap-sha` when supplied; it emits no `state`, `handoffs`, or `divergence`. On a multi-failure fixture (two or more stamps with no reachable common ancestor) it collects **all** failing pairs (continue-and-collect, not `break`) and resolves `merge_base_failed` to failing **artifact paths**, not bare SHAs.
- jq emits JSON `null` for every absent scalar, never the empty string — pinned by a `jq -e '... == null'` assertion on the unstamped and merge-base-failed shapes.
- A `§1.6(h)` byte-source conformance test extracts the canonical walk block from `conventions.md` and asserts the script's Phase 1 walk uses the same `ls`-glob set and the same anchored `§1.6(a1)` regex — a source comparison, not a behavior smoke test — while treating the `migrate-range` `STAMPED_PAIRS` pairing rows as the one sanctioned extension. A staged-subtree fixture asserts that a `staged-workflow/` path is excluded from the `git log` pathspec result (the trailing-slash exclusion holds).
- An unknown `--mode` exits non-zero with usage and emits no JSON.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery

All six decomposed steps are read-only detection or test-only additions; none performs a git mutation (the script's only mutation, the no-drift normalization commit, is Track 3). Each step is therefore naturally re-runnable and the script produces deterministic JSON for a fixed git state. No step leaves on-disk residue to recover from — a failed step is retried by re-running it, with no cleanup. The Python harness builds and tears down its own temporary git repos per fixture (via the step-2 git-fixture builder), so a partially-run test leaves no residue under the project tree.

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't
belong to one specific step. Per-step episode content lives in
`## Episodes` above. Often empty. -->

## Interfaces and Dependencies

**In scope:**
- `.claude/scripts/workflow-startup-precheck.sh` (create) — the detection script.
- `.claude/scripts/tests/` (add to existing dir) — a Python stand-alone-runner harness (matching the existing suite's convention: pytest is absent on CI, so each test file is a stand-alone Python 3 runner that shells out to the bash script and parses its JSON) plus divergence, drift, and reduced-mode fixtures, landing alongside the existing Python test suite and its `fixtures/` subdir (name the new fixtures so they do not collide with what is already there). The harness introduces a reusable git-fixture builder (per-fixture temp `git init` plus commit / branch / set-upstream / fabricated-stamp / orphan-branch helpers); the existing suite is fixture-file-only and has no git-fixture infrastructure to extend, so this is new scaffolding built from scratch — the divergence fixture (a local bare remote with divergent commits, `git fetch` succeeding against a `file://` remote) is its highest-effort piece.

**Out of scope (other tracks):**
- State determination — Track 2 fills the `state` key.
- The no-drift normalization commit — Track 3 wires it into `actions_taken`.
- All prose edits — Track 4 (staged).

**Byte-source contract:** the Phase 1 artifact walk is byte-copied from `conventions.md §1.6(h)`; the canonical stamp regex is the anchored form in `§1.6(a1)`, not the unanchored variant the design narrative once carried. The `full`-mode drift fold byte-copies `workflow-drift-check.md § Detection` (`break` on the first merge-base failure); the `migrate-range` fold byte-copies `migrate-workflow/SKILL.md` Step 2 (continue-and-collect across all failures plus the `STAMPED_PAIRS` `(file, sha)` pairing). The two folds differ by design, so the script parameterizes one shared fold function by its failure-handling rather than carrying two copies. §1.6(h) conformance is enforced by the source-extraction test described under Validation, not a behavior smoke test.

**Dependencies:** none (first track). Consumed by Tracks 2 and 3 (which extend the script) and Track 4 (which cites the final JSON shape).

**Signature:** `workflow-startup-precheck.sh --mode {full,divergence-only,migrate-range} [--bootstrap-sha <40-char-sha>]`, emitting JSON to stdout. `full` JSON: `{divergence, drift, handoffs, state, actions_taken}` (with `state` emitted as JSON `null` and `actions_taken` empty after this track).

## Base commit
17b05b0329ea683f69036da0dcef7745abf4b870
