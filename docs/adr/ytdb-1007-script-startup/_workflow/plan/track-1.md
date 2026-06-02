<!-- workflow-sha: 0676e2446f373e969da86da6748c91d442135161 -->
# Track 1: Detection core, modes, and JSON emit

## Purpose / Big Picture
After this track lands, `workflow-startup-precheck.sh` exists and emits correct JSON for the read-only detection paths — branch divergence, the two-phase drift walk, and the handoff scan — across all three `--mode` outputs.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Track 1 scaffolds `workflow-startup-precheck.sh` with `--mode` plumbing and the single jq emit point, then builds the read-only detection: branch divergence, the two-phase drift walk (Phase 1 stamp walk + Phase 2 fold and `git log`), the handoff scan, and the reduced `divergence-only` and `migrate-range` outputs (including `(file, sha)` pairs and an optional `--bootstrap-sha`). It defines the `actions_taken` field that Track 3 populates. The script is authored live under `.claude/scripts/`; this track adds no side effects — every detection function is read-only.

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

The `state` key of `full`-mode JSON is stubbed in this track (Track 2 fills it), and `actions_taken` stays empty until Track 3. The track-internal topology — arg-parse → mode dispatch → detection functions → single jq emit — is the live half of the plan's Component Map; it is not repeated as a track-level diagram here.

## Plan of Work

The approach builds the script outside-in: scaffold and the emit contract first, then each detection function, then the reduced-mode shapes.

1. **Scaffold + mode dispatch.** Shebang, a header comment citing `conventions.md §1.6(h)` as the walk's spec, `--mode` argument parsing for the three modes plus `--bootstrap-sha`, and an unknown-mode error that exits non-zero with usage. Add a single `emit_json` function stub.
2. **The jq emit point.** One function assembles the JSON from plain shell variables, per mode. jq builds every blob so quoting, escaping, and `null` for absent scalars are correct by construction. This is the only site that knows the JSON shape, so the contract has one authoring home.
3. **Branch divergence detection.** Ahead/behind counts via `git rev-list --count`, with the upstream guard (`@{u}` absent → `skipped=true`, `skip_reason="no-upstream"`, `detected=false`) and the fetch guard (`skip_reason="fetch-failed"`).
4. **Drift Phase 1 + Phase 2.** Byte-copy the `§1.6(h)` artifact walk to classify each artifact stamped/unstamped using the anchored `§1.6(a1)` regex. Phase 2 folds the stamp set pairwise through `git merge-base` to derive `BASE_SHA`, runs `git log BASE_SHA..HEAD` on the workflow pathspecs, and sets `drift.kind`, `base_sha`, `commit_count`, and `first_commits`. The unstamped and merge-base-failed short-circuits set `kind` accordingly with null scalars.
5. **Handoff scan.** `ls -t` the active plan's `handoff-*.md`, preserve the mtime order, populate `handoffs`.
6. **Reduced-mode outputs.** `divergence-only` emits only `divergence` and `actions_taken`. `migrate-range` runs the drift walk and fold, emits `stamped_artifacts` as `(file, sha)` pairs, `unstamped_files`, `base_sha`, a `merge_base_failed` flag with the failing pair, and the `git log` range; it folds in `--bootstrap-sha` when supplied and emits no `state`, `handoffs`, or `divergence`.

Ordering constraints and invariants to preserve: detection functions write plain shell variables only; the jq step is the sole JSON-authoring site (one-contract-home invariant). The artifact walk stays byte-identical to `§1.6(h)`. The script performs no mutation in this track — divergence and the drift walk are read-only, and the `normalization_landed` flag is hard-false until Track 3.

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

Track-level behavioral acceptance:

- `--mode full` on a clean fixture emits valid JSON with `divergence.detected=false`, `drift.detected=false`, `handoffs=[]`, and `actions_taken=[]`.
- `--mode full` on a divergence fixture reports correct `ahead` and `behind` counts; on a no-upstream fixture it reports `skipped=true` with `skip_reason="no-upstream"` and `detected=false`.
- `--mode full` on a drift fixture reports `drift.detected=true` with the correct `base_sha`, `commit_count`, and `first_commits` ordered oldest-first; on an unstamped fixture it reports `drift.kind="unstamped"` with null scalars.
- `--mode divergence-only` emits only `divergence` and `actions_taken`.
- `--mode migrate-range` emits `stamped_artifacts` `(file, sha)` pairs, `unstamped_files`, `base_sha`, the `git log` range, and folds in `--bootstrap-sha` when supplied; it emits no `state`, `handoffs`, or `divergence`.
- jq emits `null` for absent scalars, never the empty string.
- An unknown `--mode` exits non-zero with usage and emits no JSON.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery
paths once steps are decomposed. -->

Track-level note: every detection function in this track is read-only, so steps are naturally re-runnable and the script produces deterministic JSON for a fixed git state. No step in this track leaves on-disk residue to recover from.

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't
belong to one specific step. Per-step episode content lives in
`## Episodes` above. Often empty. -->

## Interfaces and Dependencies

**In scope:**
- `.claude/scripts/workflow-startup-precheck.sh` (create) — the detection script.
- `.claude/scripts/tests/` (create) — the fixture harness plus divergence, drift, and reduced-mode fixtures.

**Out of scope (other tracks):**
- State determination — Track 2 fills the `state` key.
- The no-drift normalization commit — Track 3 wires it into `actions_taken`.
- All prose edits — Track 4 (staged).

**Byte-source contract:** the Phase 1 artifact walk is byte-copied from `conventions.md §1.6(h)`; the canonical stamp regex is the anchored form in `§1.6(a1)`, not the unanchored variant the design narrative once carried.

**Dependencies:** none (first track). Consumed by Tracks 2 and 3 (which extend the script) and Track 4 (which cites the final JSON shape).

**Signature:** `workflow-startup-precheck.sh --mode {full,divergence-only,migrate-range} [--bootstrap-sha <40-char-sha>]`, emitting JSON to stdout. `full` JSON: `{divergence, drift, handoffs, state, actions_taken}` (with `state` stubbed and `actions_taken` empty after this track).
