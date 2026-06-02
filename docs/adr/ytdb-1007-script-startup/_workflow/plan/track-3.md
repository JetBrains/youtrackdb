<!-- workflow-sha: 0676e2446f373e969da86da6748c91d442135161 -->
# Track 3: No-drift normalization and actions_taken wiring

## Purpose / Big Picture
After this track lands, `--mode full` performs the no-drift normalization commit when stamps fold to one `BASE_SHA` but sit on different commits, and reports that commit in `actions_taken` — the script's only autonomous mutation, behaviorally identical to today's prose.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Track 3 ports the no-drift normalization path byte-for-byte: recompute the stamped-file list, rewrite each line-1 stamp to the folded `BASE_SHA` with a printf-and-tail pattern, verify the two diff-shape guards, then land one all-or-nothing commit and feed it into `actions_taken`. This is the only mutating path in the script. It is authored live and wires into the `actions_taken` field Track 1 defines and the drift fold Track 1 computes.

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

The no-drift normalization path lives today in `workflow-drift-check.md § No-drift normalization`. It fires only when Phase 2 reports an empty `git log` (no drift) but `STAMPED_SHAS` carries more than one distinct SHA — the active plan's stamps fold to the same `BASE_SHA` yet sit on different commits on disk. Rewriting every artifact's line-1 stamp to `BASE_SHA` collapses the next gate's fold input to a single-element set.

This track ports that block into the script unchanged. The path:

1. Recomputes the stamped-file list under the same enumeration the Phase 1 walk uses (the walk exports `STAMPED_SHAS` and `UNSTAMPED_FILES` but not a companion path list, so the list is recomputed here).
2. Rewrites line 1 of each stamped artifact with a portable `printf` + `tail -n +2` pattern (not `sed -i`, whose `-i` flag differs between BSD and GNU).
3. Verifies two diff-shape guards: guard 1 rejects any hunk that starts off line 1; guard 2 rejects any dirty path inside the active plan's `_workflow/` that the rewrite did not touch.
4. On either mismatch, restores the stamped files from HEAD and exits non-zero with a diagnostic, landing no commit. On success, stages the stamped files, commits with subject `Normalize workflow-sha stamps to <short-BASE_SHA>`, and appends the commit to `actions_taken`.

This track depends on Track 1: it consumes the fold's `BASE_SHA`, runs inside `full` mode only, and writes into the `actions_taken` array Track 1 defines as empty. Surfacing the commit in `actions_taken` is the one behavior delta from today, where the normalization is fully silent — the agent names it in the resume summary; the script does not prompt.

## Plan of Work

The work ports the existing block in slices that each stay byte-faithful to the source.

1. **Stamp rewrite.** Recompute `STAMPED_FILES`, then rewrite line 1 of each with the `printf '<!-- workflow-sha: %s -->\n' "$BASE_SHA"; tail -n +2` pattern, in place via a `.tmp` + `mv`.
2. **The two diff-shape guards + abort-restore.** Guard 1: `git diff -U0` hunks must all start `@@ -1`. Guard 2: porcelain status scoped to the active plan's `_workflow/` must list only the stamped artifacts. On either failure, `git checkout -- $STAMPED_FILES` and exit non-zero with the off-line-1 hunks or unexpected paths named.
3. **Commit + `actions_taken` wiring.** On clean guards, stage the stamped files, commit with the `Normalize workflow-sha stamps to <short>` subject, set `drift.normalization_landed=true`, and append the commit entry to `actions_taken`.
4. **Normalization fixtures.** Cover the success path (multi-SHA fold → one commit, line-1-only diff, `actions_taken` populated) and the abort path (a deliberately malformed rewrite leaves the tree at HEAD, exits non-zero, lands no commit).

Ordering constraints and invariants to preserve (S3): the path fires only in `full` mode — `divergence-only` and `migrate-range` never mutate. The all-or-nothing contract holds: either every stamp moves to `BASE_SHA` in one commit, or the working tree is unchanged. Unrelated dirty files outside the active plan's `_workflow/` do not abort the normalization, matching the existing narrow-scope dirty check. The commit subject and line-1-only diff shape are byte-identical to today's.

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

- A `full`-mode run on a fixture with stamps that fold to one `BASE_SHA` but sit on distinct commits lands exactly one commit, subject `Normalize workflow-sha stamps to <short>`, with a line-1-only diff across the stamped artifacts.
- That run reports the commit in `actions_taken` and sets `drift.normalization_landed=true`.
- A fixture whose rewrite would touch more than line 1 (guard 1) or leaves an unexpected dirty path in `_workflow/` (guard 2) leaves the working tree at HEAD, exits non-zero with the offending hunks or paths named, and lands no commit.
- Stamps already uniform (single distinct SHA) trigger no normalization and no commit.
- `divergence-only` and `migrate-range` never mutate, even on a multi-SHA fixture.
- Unrelated dirty files outside `_workflow/` do not abort the normalization.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery
paths once steps are decomposed. -->

Track-level note: the normalization is itself the recovery-safe shape — the abort-restore path guarantees the working tree returns to HEAD on any guard mismatch, so a re-run after an aborted attempt sees the same pre-rewrite state. A successful run collapses the stamp set to a single SHA, so a re-run is a no-op (the multi-SHA precondition no longer holds).

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

**Byte-source contract:** the normalization path is byte-faithful to `workflow-drift-check.md § No-drift normalization` (recompute, printf+tail rewrite, two diff-shape guards, all-or-nothing commit). The commit subject and line-1-only diff shape match today's exactly (S3).

**Dependencies:** depends on Track 1 (the `full`-mode scaffold, the drift fold's `BASE_SHA`, and the `actions_taken` field). Consumed by Track 4, which cites `actions_taken` in the rewritten dispatch rule's resume-recital prose.
