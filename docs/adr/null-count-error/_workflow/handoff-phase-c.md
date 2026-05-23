# Phase C handoff — Track 1 (`null-count-error`)

Iteration 1 of the track-level code review loop landed; context hit critical (40%) at the iteration boundary, so the session paused before the gate-check fan-out.

## State at pause

- **Phase**: Phase C (track-level code review + track completion)
- **Track**: 1 (Containment fixes)
- **Iteration count**: 1 of 3 used
- **HEAD**: `030ca27488` — `YTDB-958: Review fix: tighten Track 1 cascade-containment review findings` (pushed to `origin/YTDB-958-null-count-error`)
- **Base commit for diff**: `6dd104000bba4134b9c47ee26a90cd1bb80da7d4`

## What landed in iteration 1

9 synthesised findings applied in one `Review fix:` commit:

| ID | Severity | Area | One-liner |
|---|---|---|---|
| M1 | blocker | `CommitNonRuntimeExceptionFallbackTest.java` | Tightened falsifiability: added `instanceof StorageException`, immediate-cause-is-AssertionError, and `capturedOp.isRollbackInProgress()` assertions |
| M2 | blocker | 3 underflow / cascade test classes | Added `@Category(SequentialTest.class)` to MV underflow, SV underflow, StorageCascade tests (JUL handler contention under surefire `<parallel>classes</parallel>`) |
| M3 | should-fix | `AbstractStorage.java` post-`endTxCommit` catches | Broadened `applyIndexCountDeltas` (line :2366) and `applyHistogramDeltas` (line :2382) catches to `RuntimeException \| AssertionError`, symmetric with the broadened pre-`endTxCommit` catch |
| M4 | should-fix | `AbstractStorage.setInError` | Added postcondition assert `!(e instanceof AssertionError) \|\| error.get() != e` |
| M5 | should-fix | Both engines | Wrapped 100-char-overflow inline comments at MV:681, SV:683 |
| M6 | should-fix | Both 16-thread tests | Replaced `pool.shutdownNow()` with graceful shutdown + `awaitTermination` + fallback |
| M7 | should-fix | Both 16-thread tests | Pre-warmed pool, lifted `barrier.await` budget 5s → 30s |
| M8 | should-fix | `AtomicOperationsManagerWrapperAssertionErrorTest` | Added `assertNull(capturedError.get(), …)` on OOM wrapper test |
| M9 | suggestion | `AbstractStorage.setInError` Javadoc | Reworded "already been logged" → "will be logged and rethrown by the surrounding `logAndPrepareForRethrow(...)`" |

Implementer reported 127 tests passing, 100% line + 100% branch coverage on the changed lines.

## Recovery notes

The first implementer (`a79825c575fe35e10`) emitted a spurious `task completed` notification mid-flight, which led the orchestrator to spawn a duplicate continuation implementer (`a697dae5f89dfef25`). Both implementers were briefly running concurrently with their own Maven test invocations. The first implementer actually completed its work successfully — `030ca27488` was its commit, pushed before the orchestrator killed the duplicate Maven runs. The continuation implementer received a `shutdown_request` after the duplicate state was detected.

This pattern (spurious task-completed notification → duplicate spawn → race) is worth a workflow improvement issue under `YTDB project tag: dev-workflow`; record in self-improvement reflection at session end.

## Next session — resume protocol

1. Re-run `/execute-tracks`. The startup protocol will see iteration 1 complete in the track file's Progress section and the `**PAUSED**` marker pointing here; load this handoff file.

2. **Skip re-spawning iteration 1 implementer** — `030ca27488` is its commit and is already on origin. Re-staging the diff and running the gate-check fan-out is the next step.

3. **Regenerate the staged temp files** per `track-code-review.md` § Sub-agents → § "Pre-staged diff and changed-files list":

   ```
   git diff 6dd104000bba4134b9c47ee26a90cd1bb80da7d4..HEAD \
       > /tmp/claude-code-track-1-diff-$PPID.patch
   git diff 6dd104000bba4134b9c47ee26a90cd1bb80da7d4..HEAD --name-only \
       > /tmp/claude-code-track-1-files-$PPID.txt
   python3 .claude/scripts/render-slim-plan.py \
       --plan-path docs/adr/null-count-error/_workflow/implementation-plan.md \
       --out /tmp/claude-code-plan-slim-$PPID.md
   ```

   The `$PPID` here is the resuming orchestrator's PID, not the prior one. Sub-agents reach the snapshot through the path the orchestrator substitutes when composing their prompt.

4. **Run the gate-check fan-out** per `track-code-review.md` § Review loop step 3 using the compact gate-check prompt at `.claude/workflow/prompts/dimensional-review-gate-check.md`. Open findings to forward (per dimension):

   - **review-test-behavior** — `M1` (gate-check: did the falsifiability assertions land as proposed?)
   - **review-test-structure** — `M2` (gate-check: do the three test classes carry the `@Category(SequentialTest.class)` marker and route through the sequential surefire execution?)
   - **review-crash-safety** — `M3` (gate-check: are the post-`endTxCommit` catches now symmetric? cleanupSnapshotIndex deliberately left alone — see implementer's `what_was_discovered`)
   - **review-test-crash-safety** — `M4` (gate-check: postcondition assert in place; comment names the invariant)
   - **review-code-quality** — `M5` (gate-check: line widths under 100 chars; Spotless clean)
   - **review-test-concurrency** — `M6` + `M7` (gate-check: graceful shutdown + pre-warm + lifted barrier budget)
   - **review-test-behavior** — `M8` (gate-check: `assertNull` on captured error arg)
   - **review-bugs-concurrency** — `M9` (gate-check: Javadoc wording corrected)

   Each gate-check sub-agent uses the compact prompt template with the verdicts `VERIFIED` / `REJECTED` / `MOOT` / `STILL OPEN` / `REGRESSION`.

5. **Synthesise gate-check returns** per `finding-synthesis-recipe.md` § Gate-check synthesis. Forward `STILL OPEN` and `REGRESSION` rows as iteration 2 input.

6. **Iteration 2 candidates** (carry-forward + deferred from iteration 1):
   - All-stale-line-citations in `track-1.md` + `implementation-plan.md` (the prior `2334/2346/2358/2370` numbers drifted forward by 15-25 lines after Steps 1-7 inserted comment blocks; M3 implementer noted the correct current lines are 2366 / 2382)
   - Em-dash overruns at Episode Step 2 line 172 and Step 4 line 203 in `track-1.md`
   - "fork-per-class" comment cleanup in 5 storage tests (the comments incorrectly state surefire mode; should reference "parallel-by-class executor")
   - Workflow context-budget compression in `track-1.md` (Surprises ↔ Episodes dedup, Decision Log ↔ Clarifications redundancy)
   - The `cleanupSnapshotIndex` catch one level below the M3 catches is still `RuntimeException`-only — implementer explicitly scoped M3 away from it; iteration 2 may opt to broaden for symmetry

7. **Track-completion deferred findings** (to present at the approval panel):
   - 6 new-test recommendations: compose-test for `wrapper → moveToErrorStateIfNeeded → setInError(AssertionError)` reach; `Long.MIN_VALUE` boundary + wraparound documentation test; close+reopen latch lifecycle test; concurrent-commit cascade test (8 threads); durable rollback verification across restart; `setInError` guard downstream behavior at the close path
   - Refactoring suggestions: extract shared `reportAndClampUnderflow` to a `BTreeIndexEngine` default method or package-private utility; consolidate parallel MV/SV underflow test classes via an abstract base
   - Diagnostic-polish: `delta == 0` underflow log spam on already-negative counter; log emission for transient-negative state (counter goes negative-then-positive between observation and CAS)

## Notable cross-track signals captured this iteration

- **Line-number drift in track-1.md** is widespread (~15 critical findings from `review-workflow-consistency`). Track 2's deletion targets currently cite stale lines. Iteration 2 of this Phase C should sweep the file.
- **Phase 4 reflection candidate**: spurious `task completed` notification → duplicate-spawn race. Worth a YouTrack issue under `YTDB`, `dev-workflow` tag, type Bug.
- **StorageBackupTest pre-existing failure mode**: the iteration-1 implementer reported transient `NoClassDefFound`/duplicate-DB failures in `StorageBackupTest` from killed coverage-build residue under `core/target/databases/`. Not caused by Track 1 changes; cleared with `rm -rf`. Future implementers / Phase B step recovery should be aware.

## Self-improvement reflection — pending

Run `.claude/workflow/self-improvement-reflection.md` at end of next session (not this paused session — reflection runs at end of phase, not at handoff write). Material to seed it:

- The spurious task-completed notification race (above).
- The pattern where an implementer launches a background Maven and waits for its completion, then exits at message-budget rather than at task completion — leaves orphan Maven plus a `tail -f` watcher.
- Useful pattern: when an implementer's RESULT block is "missing", surgical inspection of the JSONL transcript (last entry's `type` + `last_block` summary via Python, no full dump) discriminates "actually exited" from "still working but runtime sent stale notification" — worth codifying.
