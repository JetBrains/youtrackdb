<!-- workflow-sha: 0676e2446f373e969da86da6748c91d442135161 -->
# Track 3: No-drift normalization and actions_taken wiring

## Purpose / Big Picture
After this track lands, `--mode full` performs the no-drift normalization commit when stamps fold to one `BASE_SHA` but sit on different commits, and reports that commit in `actions_taken` — the script's only autonomous mutation, behaviorally identical to today's prose.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Track 3 ports the no-drift normalization path byte-for-byte: recompute the stamped-file list, rewrite each line-1 stamp to the folded `BASE_SHA` with a printf-and-tail pattern, verify the two diff-shape guards, then land one all-or-nothing commit and feed it into `actions_taken`. This is the only mutating path in the script. It is authored live and wires into the `actions_taken` field Track 1 defines and the drift fold Track 1 computes.

## Progress
- [x] 2026-06-03T07:33Z [ctx=info] Review + decomposition complete
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

- [x] Technical: PASS at iteration 2 (4 findings, 4 accepted). T1 (blocker) — distinct-SHA fire gate is new selecting logic, not a byte-copy; T2/T3 (should-fix) — `$BASE_SHA`→`DRIFT_BASE_SHA` binding and success-`return`-vs-abort-`exit 1`; T4 (suggestion) — guard-1 is black-box reachable via a pre-dirtied line-2+ body.
- [x] Risk: PASS at iteration 2 (7 findings, 6 accepted, 1 rejected). R1/R4 duplicate T3/T1; R2 (note) — `&&`+no-`set -e` orphan-`.tmp` caught by guard 2; R5 — all-or-nothing scoped to guard mismatch, interruption is recoverable/self-healing; R6 — guard-2 fixture for a dirty in-`_workflow/` non-stamped file; R3 rejected — `BASE_SHA` correctness is Track 1's fold concern (trust boundary recorded).

## Context and Orientation

The no-drift normalization path lives today in `workflow-drift-check.md § No-drift normalization`. It fires only when Phase 2 reports an empty `git log` (no drift) but `STAMPED_SHAS` carries more than one distinct SHA — the active plan's stamps fold to the same `BASE_SHA` yet sit on different commits on disk. Rewriting every artifact's line-1 stamp to `BASE_SHA` collapses the next gate's fold input to a single-element set.

This track ports that block into the script unchanged. The path:

1. Recomputes the stamped-file list under the same enumeration the Phase 1 walk uses (the walk exports `STAMPED_SHAS` and `UNSTAMPED_FILES` but not a companion path list, so the list is recomputed here).
2. Rewrites line 1 of each stamped artifact with a portable `printf` + `tail -n +2` pattern (not `sed -i`, whose `-i` flag differs between BSD and GNU).
3. Verifies two diff-shape guards: guard 1 rejects any hunk that starts off line 1; guard 2 rejects any dirty path inside the active plan's `_workflow/` that the rewrite did not touch.
4. On either mismatch, restores the stamped files from HEAD and exits non-zero with a diagnostic, landing no commit. On success, stages the stamped files, commits with subject `Normalize workflow-sha stamps to <short-BASE_SHA>`, and appends the commit to `actions_taken`.

This track depends on Track 1: it consumes the fold's `BASE_SHA`, runs inside `full` mode only, and writes into the `actions_taken` array Track 1 defines as empty. Surfacing the commit in `actions_taken` is the one behavior delta from today, where the normalization is fully silent — the agent names it in the resume summary; the script does not prompt.

## Plan of Work

The work ports the existing block with three sanctioned adaptations the byte-source's surrounding prose forces once the block moves into a function-structured script (named in the byte-source-contract note under `## Interfaces and Dependencies`). Each slice keeps the source mechanism; the adaptations are control-flow and variable-binding, not algorithm changes.

1. **Fire gate — the distinct-SHA precondition.** The byte-source's bash block does not itself test "more than one distinct stamp"; that precondition lives in the section's surrounding prose (`workflow-drift-check.md § No-drift normalization`: "Fires only when … `STAMPED_SHAS` carries more than one distinct SHA"). The script's current no-drift branch (`if [ -z "$log_lines" ]`) does not yet distinguish already-uniform stamps (skip) from stamps that fold to one `BASE_SHA` while sitting on distinct commits (normalize), so this gate is **new selecting logic**, not a body byte-copy: compute the distinct count (representative form `printf '%s\n' $STAMPED_SHAS | LC_ALL=C sort -u | grep -c .`) and run the rewrite only when it exceeds 1. A single distinct SHA skips silently with `normalization_landed=false`.
2. **Stamp rewrite.** Recompute `STAMPED_FILES` under the same enumeration the Phase 1 walk uses (that walk exports `STAMPED_SHAS`/`UNSTAMPED_FILES` but no companion path list), then rewrite line 1 of each with the `printf '<!-- workflow-sha: %s -->\n' "$DRIFT_BASE_SHA"; tail -n +2` pattern, in place via a `.tmp` + `mv`. The byte-source names this `$BASE_SHA`; the in-script fold result is `DRIFT_BASE_SHA` (the one sanctioned variable binding — a literal `$BASE_SHA` copy would expand to the empty string under the script's no-`set -u` posture and rewrite every stamp blank). The `&&` between the `printf`/`tail` redirect and the `mv` runs under the script's no-`set -e` posture: a failed write leaves the original file intact and at worst an orphan `.tmp`, which guard 2 catches as an untracked `_workflow/` path.
3. **The two diff-shape guards + abort-restore.** Guard 1: every `git diff -U0` hunk header must start `@@ -1`. Guard 2: porcelain status scoped to the active plan's `_workflow/` must list only the stamped artifacts. On either failure, `git checkout -- $STAMPED_FILES` and `exit 1` (a hard exit that halts the session, byte-faithful to today) with the off-line-1 hunks or unexpected paths named.
4. **Commit + `actions_taken` wiring + success-path `return`.** On clean guards, stage the stamped files, commit with the `Normalize workflow-sha stamps to <short>` subject, set `DRIFT_NORMALIZATION_LANDED=true`, and set `ACTIONS_TAKEN_JSON` to a one-element array describing the commit. The success path then **`return`s** into `emit_json`; it does **not** `exit 0` as the byte-source's terminal line does, because the script must still emit the `full`-mode JSON carrying `actions_taken` and `drift.normalization_landed`. This success-`return` vs abort-`exit 1` asymmetry is the second sanctioned control-flow adaptation.
5. **Normalization fixtures.** Cover: the success path (multi-SHA fold on distinct commits → one commit, line-1-only diff, `actions_taken` populated, valid five-key `full` JSON on stdout, exit 0); the uniform-stamp skip (single distinct SHA → no commit, `normalization_landed=false`); the guard-2 abort (a dirty non-stamped file inside `_workflow/`, e.g. a `handoff-*.md`, → tree at HEAD, exit 1, no commit, the path named); the guard-1 abort (a stamped artifact with a pre-existing line-2+ body edit so the post-rewrite diff spans past line 1 → off-line-1 hunk named, tree at HEAD, exit 1, no commit); and the narrow-scope no-abort (a dirty file outside `_workflow/` does not abort).

Ordering constraints and invariants to preserve (S3): the path fires only in `full` mode. Because the normalization lives inside `detect_drift`, which the dispatch runs only for `--mode full`, the mode gate is structural and automatic; `divergence-only` and `migrate-range` never call it. The all-or-nothing contract holds against guard mismatch: nothing is staged until both guards pass, so `git checkout --` alone restores the tree. Unrelated dirty files outside the active plan's `_workflow/` do not abort the normalization, matching the existing narrow-scope dirty check. The commit subject and line-1-only diff shape are byte-identical to today's. `BASE_SHA` correctness is Track 1's fold concern, not this track's: Track 3 trusts the folded `DRIFT_BASE_SHA`.

## Concrete Steps

1. No-drift normalization mutating path — in `detect_drift`'s no-drift branch, add the distinct-SHA fire gate, recompute `STAMPED_FILES`, line-1 rewrite to `DRIFT_BASE_SHA`, the two diff-shape guards, abort-restore (`exit 1`), the commit, and the success-`return` into `emit_json` (Plan of Work slices 1-4). Lands with all safety fixtures: success (one commit, line-1-only diff, valid `full` JSON, exit 0), uniform-stamp skip, guard-1 abort (pre-dirtied line-2+ body), guard-2 abort (dirty non-stamped `_workflow/` file), narrow-scope no-abort, and `divergence-only`/`migrate-range` no-mutate (slice 5). — risk: high (override: the script's only mutating path — autonomous `git commit` + working-tree rewrite under an all-or-nothing abort-restore; Phase A reviews surfaced one blocker plus three should-fixes on its correctness)  [ ]
2. `actions_taken` + `normalization_landed` reporting — on the Step 1 success path set `DRIFT_NORMALIZATION_LANDED=true` and `ACTIONS_TAKEN_JSON` to a one-element entry naming the normalization commit (short SHA + subject); the `full`-mode emit already splices both. Fixtures: a successful normalization emits `full` JSON with `actions_taken` populated and `drift.normalization_landed=true`; the uniform-skip and no-drift paths keep `actions_taken=[]` and `normalization_landed=false` (S1: the only behavior delta vs today is the commit surfacing in `actions_taken`). — risk: medium (observability — populates the JSON reporting contract Track 1 stubbed; observable output change, no new mutation)  [ ]

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step, identified by step number + commit SHA. Empty at
Phase 1; Phase A does not populate. -->

## Validation and Acceptance

Track-level behavioral acceptance:

- A `full`-mode run on a fixture with stamps that fold to one `BASE_SHA` but sit on distinct commits lands exactly one commit, subject `Normalize workflow-sha stamps to <short>`, with a line-1-only diff across the stamped artifacts.
- That same run reports the commit in `actions_taken`, sets `drift.normalization_landed=true`, emits the full five-key `full` JSON on stdout, and exits 0 (the success-path `return`, not `exit 0`, keeps the emit reachable).
- Guard 2 abort: a dirty non-stamped file inside the active plan's `_workflow/` (e.g. an uncommitted `handoff-*.md`) leaves the working tree at HEAD, exits non-zero with the unexpected path named, and lands no commit.
- Guard 1 abort: a stamped artifact carrying a pre-existing line-2+ body edit (so the post-rewrite diff spans past line 1) leaves the working tree at HEAD, exits non-zero with the off-line-1 hunk named, and lands no commit.
- Stamps already uniform (single distinct SHA) trigger no normalization and no commit, with `normalization_landed=false`.
- `divergence-only` and `migrate-range` never mutate, even on a multi-SHA fixture.
- Unrelated dirty files outside `_workflow/` do not abort the normalization.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery
paths once steps are decomposed. -->

Track-level note: the normalization is itself the recovery-safe shape — the abort-restore path guarantees the working tree returns to HEAD on any guard mismatch, so a re-run after an aborted attempt sees the same pre-rewrite state. A successful run collapses the stamp set to a single SHA, so a re-run is a no-op (the multi-SHA precondition no longer holds). The all-or-nothing guarantee is exact against guard mismatch (nothing staged until both guards pass); it is narrower against an interruption between the rewrite and the guard check (no `trap` handler), which leaves a half-rewritten tree with no commit. That state is recoverable and self-healing: the rewritten stamps are already uniform to `DRIFT_BASE_SHA`, so a re-run does not re-fire the normalization, and the per-file `.tmp`+`mv` is atomic so no orphan `.tmp` survives a clean `mv`.

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't
belong to one specific step. Per-step episode content lives in
`## Episodes` above. Often empty. -->

## Interfaces and Dependencies

**In scope:**
- The `no_drift_normalization` logic added to `.claude/scripts/workflow-startup-precheck.sh` (fires in `full` mode; populates `actions_taken` and `drift.normalization_landed`).
- Normalization fixtures under `.claude/scripts/tests/` covering the success and abort-restore paths.

**Out of scope (other tracks):**
- The drift walk and fold that compute `BASE_SHA` — Track 1.
- State determination — Track 2.
- All prose edits — Track 4 (staged).

**Byte-source contract:** the normalization path is byte-faithful to `workflow-drift-check.md § No-drift normalization` (recompute, printf+tail rewrite, two diff-shape guards, all-or-nothing commit). The commit subject and line-1-only diff shape match today's exactly (S3). Three adaptations are sanctioned and expected when the prose block moves into the function-structured script, so Phase C should not flag them as byte-faithfulness violations: (1) the byte-source's bare `$BASE_SHA` binds to the script's fold result `DRIFT_BASE_SHA`; (2) the success path `return`s into `emit_json` rather than `exit 0`, so the `full`-mode JSON still emits with `actions_taken` populated (the abort path keeps `exit 1`); (3) the distinct-SHA fire gate, which the byte-source carries only in its surrounding prose, gains an explicit in-script home (the byte-source bash block alone assumes the caller already established the multi-SHA precondition). The folded `DRIFT_BASE_SHA` value itself is Track 1's contract — this track trusts it and does not re-derive it.

**Dependencies:** depends on Track 1 (the `full`-mode scaffold, the drift fold's `BASE_SHA`, and the `actions_taken` field). Consumed by Track 4, which cites `actions_taken` in the rewritten dispatch rule's resume-recital prose.
