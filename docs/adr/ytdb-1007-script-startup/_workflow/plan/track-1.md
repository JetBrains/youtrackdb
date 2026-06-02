<!-- workflow-sha: 0676e2446f373e969da86da6748c91d442135161 -->
# Track 1: Detection core, modes, and JSON emit

## Purpose / Big Picture
After this track lands, `workflow-startup-precheck.sh` exists and emits correct JSON for the read-only detection paths — branch divergence, the two-phase drift walk, and the handoff scan — across all three `--mode` outputs.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Track 1 scaffolds `workflow-startup-precheck.sh` with `--mode` plumbing and the single jq emit point, then builds the read-only detection: branch divergence, the two-phase drift walk (Phase 1 stamp walk + Phase 2 fold and `git log`), the handoff scan, and the reduced `divergence-only` and `migrate-range` outputs (including `(file, sha)` pairs and an optional `--bootstrap-sha`). It defines the `actions_taken` field that Track 3 populates. The script is authored live under `.claude/scripts/`; this track adds no side effects — every detection function is read-only.

## Progress
- [x] 2026-06-02T14:51Z [ctx=info] Review + decomposition complete
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

1. Scaffold `workflow-startup-precheck.sh` — shebang (no global `set -e`), `§1.6(h)` header, `--mode {full,divergence-only,migrate-range}` + `--bootstrap-sha` parsing, unknown-mode error (non-zero exit + usage, no JSON), the single `emit_json` jq function with the explicit empty→null idiom, and `actions_taken` defined as an empty array; plus the initial Python stand-alone-runner test asserting the unknown-mode path and the jq null-vs-empty contract on synthetic vars — risk: medium (new component behavior: the one-contract-home jq emit + null idiom is the load-bearing S1 emit surface)  [ ]
2. Branch divergence detection + reusable git-fixture builder — `git rev-list --left-right --count HEAD...'@{u}'` with the upstream and fetch guards populating `divergence{detected,ahead,behind,skipped,skip_reason}`; introduce the Python git-fixture builder (temp `git init`, commit/branch/set-upstream, local `file://` bare remote) and clean / divergence / no-upstream / fetch-failed fixtures — risk: medium (new shared test infrastructure + new detection behavior)  [ ]
3. Drift Phase 1 — artifact walk + classification — byte-copy the `§1.6(h)` walk (anchored `§1.6(a1)` regex) classifying stamped/unstamped, plus the `§1.6(h)` source-extraction conformance test (glob-set + regex compared against the canonical block, `STAMPED_PAIRS` pairing whitelisted) and stamped / unstamped / empty-input fixtures — risk: medium (byte-parity logic; the conformance test is the spec-drift guard)  [ ]
4. Drift Phase 2 — pairwise merge-base fold + `git log` — the `full`-mode `break`-shape fold deriving `BASE_SHA`, `git log --reverse` on the trailing-slash pathspecs populating `base_sha`/`commit_count`/`first_commits`, the merge-base-failed short-circuit (null scalars), and the shared fold shell function parameterized by failure-handling; drift-detected / merge-base-failed / staged-subtree-exclusion fixtures — risk: medium (subtlest logic in the track; byte-parity with `workflow-drift-check.md § Detection`)  [ ]
5. Handoff scan + `state` stub — `ls -t handoff-*.md` (mtime order, empty-safe) populating `handoffs`, and `state` emitted as JSON `null`; handoffs-present (mtime order) and clean (empty `[]`) fixtures — risk: low (default: routine `ls -t` plus a null stub, fully fixture-covered, no MEDIUM trigger)  [ ]
6. Reduced-mode outputs `divergence-only` + `migrate-range` — `divergence-only` emits only `divergence` + `actions_taken`; `migrate-range` runs the continue-and-collect fold (collect every failing pair), the `STAMPED_PAIRS` `(file=sha)` array resolving `merge_base_failed` to artifact paths, the `--bootstrap-sha` fold-in, and `stamped_artifacts (file,sha)` + `unstamped_files` + `base_sha` + `git log` range, emitting no `state`/`handoffs`/`divergence`; divergence-only / migrate-range-stamped / multi-failure-collect-all / `--bootstrap-sha` fixtures — risk: medium (the continue-vs-break fold distinction is the T1/R1 byte-parity hazard; parameterized fold reuse)  [ ]

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step, identified by step number + commit SHA. Empty at
Phase 1; Phase A does not populate. -->

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
