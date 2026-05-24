# Handoff: Phase C — Track 2 dimensional review fan-out synthesised

**Paused:** 2026-05-24
**Phase:** C
**Context level at pause:** warning
**Branch:** YTDB-958-null-count-error
**HEAD:** b6d3dd6867 "YTDB-958: Record episode for Track 2 Step 4 (delete inline persist/apply calls)"
**Unpushed:** 0 commits (before this handoff commit)

## Durable artifacts on disk

- `docs/adr/null-count-error/_workflow/plan/track-2.md` — track file with Steps 1-4 episodes; Phase C row in Progress section pending
- `docs/adr/null-count-error/_workflow/implementation-plan.md` — strategic plan (post-Track-1 baseline; carries stale lines that M3-M5 fix)
- 4 production Java files + 9 test Java files (Steps 1-4 changes already committed to HEAD)
- `.claude/scripts/bg-task.sh`, `.claude/settings.json` (YTDB-971 pacing infrastructure)
- Pre-staged Phase C inputs (regenerate on resume):
  - `/tmp/claude-code-plan-slim-<PPID>.md` (regenerate via `render-slim-plan.py`)
  - `/tmp/claude-code-track-2-diff-<PPID>.patch`, `/tmp/claude-code-track-2-files-<PPID>.txt` (regenerate via `git diff 54ffb540..HEAD`)

## Pending decision

Phase C iteration 1 is pre-spawn. The dimensional review fan-out (13 agents) completed; synthesis below produced 10 in-scope findings (5 blockers + 5 should-fix) across ~9 files. The implementer spawn was deferred because the context-consumption check returned `warning` (30%) — spawning the implementer plus the post-spawn gate-check fan-out plus the track-completion approval-panel sequence inside a single warning-level session risks quality degradation.

Next action: spawn the `level=track`, `mode=FIX_REVIEW_FINDINGS` implementer with the findings block under "Verbatim re-present text" below, base_commit `54ffb540687ed0a78622bb96e86123c5e58af082`.

## Verbatim re-present text

The dimensional review fan-out covered 13 dimensions. Two returned empty (`review-crash-safety` returned 0 critical / 0 concerning / 3 informational safe-to-ship notes; `review-performance` returned 0 findings — empty-holder fast path is allocation-free, lock-free, and the `tryApply` lambda inlines under C2). The remaining 11 dimensions produced raw findings consolidated and deduplicated per `finding-synthesis-recipe.md` Step 1 (pivot order: file:line → issue shape → suggested fix shape → severity tie-break).

The five-dimensional consensus item was `MainCommitCounterSyncTest` documentation/code drift, flagged independently by test-crash-safety (TY1), test-completeness (TC1), test-behavior (TB2), test-structure (TS1), and code-quality. The six-file Javadoc-residue sweep (stale "deleted inline call" prose in IndexCountDeltaHolder, HistogramDeltaHolder, AbstractStorage, plus four test files) was flagged by code-quality and test-structure. Workflow-consistency surfaced three independent plan-file blockers (visibility mis-cite, stale line citations, deleted-handoff reference).

### Synthesised findings — iteration 1/3

#### In-scope this iteration

##### Finding M1 [blocker] — MainCommitCounterSyncTest restart-cycle Javadoc lies; add disk-backed restart half
**Location**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/MainCommitCounterSyncTest.java` (class Javadoc lines 22-48, before() comment line 60, nominalCommit Javadoc lines 77-86, body lines 94-115)
**Dimensions**: test-crash-safety (TY1), test-completeness (TC1), test-behavior (TB2), test-structure (TS1), code-quality
**Issue**: The class Javadoc, before() comment, and method Javadoc all claim the test runs against DISK storage with a close-and-reopen cycle that proves persisted EP-page durability via load() recalibration. The body opens with `DatabaseType.MEMORY`, reads counters exactly once after commit, never closes or reopens. A regression that turned `persistIndexCountDeltas` into a no-op would still pass the test because the apply hook writes the in-memory side directly from the in-tx delta accumulator regardless of Hook A's persisted-side effect. This is the single end-to-end nominal-commit test in the track and the regression anchor Tracks 3 and 4 will depend on.
**Proposed fix**: Switch `before()` to `DatabaseType.PLOCAL`, add a `db.close(); db = youTrackDB.open(...);` cycle after the existing post-commit assertions, re-resolve the `BTreeIndexEngine`, and assert `getTotalCount` / `getNullCount` return the same `5L` / `1L` values inside a fresh atomic operation. The restart-cycle pattern from `BTreeEnginePersistedCountIT` is the technique. Update the `before()` comment to match the new DISK type and refresh the class + method Javadocs to describe what the test now actually proves.

##### Finding M2 [blocker] — ClearIndexApiRollbackTest is a tautology; guard with explicit fail()
**Location**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/ClearIndexApiRollbackTest.java` (`clearIndexApiRollback_countersStayAtPreClearValues`, lines 93-133, body placeholder at line 118)
**Dimensions**: test-behavior (TB1)
**Issue**: The test reads counters into `preClearCounts`, leaves a placeholder comment (`Follow-up implementer wires: clearIndex invocation + forced WAL-flush rollback`), reads the same counters into `postClearCounts`, then asserts `preClearCounts == postClearCounts`. No `clearIndex` call, no rollback injection. The assertions are trivially true (two reads of an untouched `AtomicLong`). The `@Ignore` masks the false-pass today, but the `@Ignore` message implies the test will function once the follow-up lands. A future engineer who flips `@Ignore` without filling in the body lands a passing-but-meaningless test that names a contract it does not verify.
**Proposed fix**: Insert `fail("Scaffold body incomplete: removing @Ignore without first wiring the clearIndex invocation and rollback injection produces a trivially-passing tautology (two reads of an untouched counter). Track 3's regression-test step must fill in the clearIndex call and rollback injection before flipping the @Ignore annotation.");` between the `preClearCounts` read and the `postClearCounts` read. The `fail()` trips when `@Ignore` is removed without the body being completed, surfacing the gap loudly.

##### Finding M3 [blocker] — implementation-plan.md visibility claim contradicts as-implemented surface
**Location**: `docs/adr/null-count-error/_workflow/implementation-plan.md` (line 173)
**Dimensions**: workflow-consistency (WC1/F1)
**Issue**: Line 173 says "Visibility of `persistIndexCountDeltas`, `applyIndexCountDeltas`, and `applyHistogramDeltas` on `AbstractStorage` rises from `private` to `package-private`." The as-implemented surface (Step 1, commit `a10c7d9394`) raises all three to `public`. The track file's Concrete Steps Step 1 row, Decision Log Q2, Context and Orientation rationale at lines 73-74, and the production source at `AbstractStorage.java:2465` / `:2496` / `:2541` all agree on `public`. The plan's Integration Points section is the only outlier.
**Proposed fix**: Edit `implementation-plan.md` line 173: replace "package-private" with "public", append a brief note about the cross-package access requirement ("manager and storage live in different packages, so package-private would not cross; `public` matches the existing manager-callback surface — `moveToErrorStateIfNeeded`, `getName`, etc. — on the same class").

##### Finding M4 [blocker] — implementation-plan.md Track 2 row carries pre-Track-1 stale line citations
**Location**: `docs/adr/null-count-error/_workflow/implementation-plan.md` (lines 131, 143, 144, 186, 200)
**Dimensions**: workflow-consistency (WC2/F2)
**Issue**: The Track 2 checklist row at line 200 cites "Delete the manual calls at `AbstractStorage.commit` lines 2318, 2333, 2345 and their surrounding post-`endTxCommit` catches at 2334, 2346." The correct post-Track-1 baseline (used elsewhere in the plan at lines 12 and 118, in `track-2.md` throughout, and matching the actual base commit `54ffb540`) is `2340 / 2365 / 2381` with catches at `2366 / 2382`. Lines 131, 143, 144, 186 carry similar stale numbers. Track 1's Strategy refresh at plan line 197 claimed it had swept all stale citations across ~20 sites; this row was missed.
**Proposed fix**: Sweep `implementation-plan.md` lines 131, 143, 144, 186, 200 and bring every "AbstractStorage.commit line ..." citation in line with the post-Track-1 baseline (`2340` / `2365` / `2381` / `2366` / `2382`). Spot-check the rest of the plan with `grep` for any other stale `2318` / `2333` / `2345` occurrences.

##### Finding M5 [blocker] — implementation-plan.md references deleted handoff file
**Location**: `docs/adr/null-count-error/_workflow/implementation-plan.md` (line 81, end of `### Implementer pacing (YTDB-971)`)
**Dimensions**: workflow-consistency (WC3/F3)
**Issue**: The deferred-follow-up paragraph says "The on-disk record sits in `_workflow/handoff-phase-b-step3-fix.md` and the Step 3 episode (once written)". The handoff file was created in commit `cb92bfea0e` and deleted in commit `e2572c8d79`, both within the Track 2 commit range. The Step 3 episode at `track-2.md` lines 225-246 exists, so the "(once written)" parenthetical is also stale. Phase 4's design-final generator will follow the link and find nothing.
**Proposed fix**: Rewrite line 81 to point at the Step 3 episode block (`docs/adr/null-count-error/_workflow/plan/track-2.md` § Episodes § Step 3) as the sole durable on-disk record. Drop the "(once written)" parenthetical and the reference to the deleted handoff file.

##### Finding M6 [should-fix] — Sweep stale "deleted inline call" Javadoc/comment residue across 7 files
**Location**: Seven files carrying residue:
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/IndexCountDeltaHolder.java` (field Javadoc lines 42-52 and 61-75 — both `persisted` and `applied`)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/HistogramDeltaHolder.java` (field Javadoc lines 42-58)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (comment above `holder.setPersisted()` at lines 2481-2486)
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorageApplyDeltaTest.java` (class Javadoc lines 35-62, test Javadoc lines 149-186)
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/atomicoperations/EndAtomicOperationPersistHookTest.java` (class Javadoc lines 73-78, `applyAlreadyPersisted` Javadoc lines 354-359)
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/atomicoperations/EndAtomicOperationHookOrderingTest.java` (`applyIndexCountHookSkipsWhenHolderAlreadyApplied` Javadoc lines 444-452)
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/atomicoperations/EndAtomicOperationHookTestSupport.java` (`mockOperation` Javadoc lines 81-84)

**Dimensions**: code-quality (8 sub-findings), test-structure (TS2, TS3)
**Issue**: Step 4 deleted the inline `persistIndexCountDeltas` / `applyIndexCountDeltas` / `applyHistogramDeltas` calls in `AbstractStorage.commit`. Step 4's own episode says "Swept doc-comment prose-residue that named the deleted call sites in five files" — but at least seven additional sites carry the same residue. Each one describes the persist / apply latch as closing the window against the "inline call inside `commit()`" or "the legacy inline call" or "until the inline call is deleted in a later step." A reader landing on any of these latches today will hunt for the inline call and find nothing.
**Proposed fix**: Sweep all seven sites. Rewrite each Javadoc / comment to describe the latch as a defensive belt against future re-entry (a nested or mistakenly-replayed lifecycle pass), matching the rewritten Javadocs at `AbstractStorage.applyIndexCountDeltas` / `applyHistogramDeltas` that Step 4 already updated. The `mockOperation` Javadoc in `EndAtomicOperationHookTestSupport` should describe the histogram stub as a focus-narrowing default for the index-count branch tests, not as forward-compat.

##### Finding M10 [should-fix] — Add Hook B histogram VM-error escape tests
**Location**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/atomicoperations/EndAtomicOperationHookOrderingTest.java` (add four sibling tests after the existing index-count VM-error tests around lines 460-540)
**Dimensions**: test-completeness (TC2)
**Issue**: The four `applyHookDoesNotCatch{Oom,Linkage,StackOverflow,Internal}Error` tests route through `assertApplyVmErrorEscapesUnconverted`, which stubs only `applyIndexCountDeltas` to throw. The histogram apply branch at `AtomicOperationsManager.java:377` has its own `tryApply` call site; though the bounded catch is shared via the `tryApply` helper, the call site is independently gated and the post-VM-error verification surface differs (index-count branch ran successfully, the index-count holder is already latched, but the histogram holder is not yet latched). A future refactor that inlines one of the calls would slip through.
**Proposed fix**: Add four sibling tests `applyHistogramHookDoesNotCatch{OutOfMemoryError,LinkageError,StackOverflowError,InternalError}` routed through a shared `assertHistogramApplyVmErrorEscapesUnconverted` helper. The helper stubs `applyIndexCountDeltas` to succeed (`doNothing`), stubs `applyHistogramDeltas` to throw the given VM error, asserts `assertSame(thrown, escaped)`, verifies the index-count branch fired (`verify(storage, times(1)).applyIndexCountDeltas`), the histogram branch fired (`verify(storage, times(1)).applyHistogramDeltas`), the lock is still released (`assertTrue(lockedList.isEmpty())`, `verify(component, times(1)).unlockExclusive()`).

##### Finding M11 [should-fix] — Pin apply-after-`atomicOperationsTable.commitOperation` ordering
**Location**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/atomicoperations/EndAtomicOperationHookOrderingTest.java` (add `applyHooksRunAfterTableCommitOperation` test)
**Dimensions**: test-completeness (TC4)
**Issue**: `happyPathPersistBeforeCommitApplyBeforeRelease` verifies the source-ordering `persistIndexCountDeltas → commitChanges → applyIndexCountDeltas → applyHistogramDeltas → unlockExclusive`. The intermediate `atomicOperationsTable.commitOperation` call at `AtomicOperationsManager.java:329` is not part of the InOrder chain. The source comment at line 340 says "after `commitChanges` and `atomicOperationsTable` commit/persist, and before the inner-finally `releaseLocks` below" — the "and `atomicOperationsTable` commit/persist" half is unverified. A future refactor moving apply before the table commit would not be caught.
**Proposed fix**: Add an `InOrder` verify pinning `table.commitOperation(DEFAULT_COMMIT_TS) → storage.applyIndexCountDeltas(operation) → storage.applyHistogramDeltas(operation) → component.unlockExclusive()`. Either add as a new dedicated test or as an additional `InOrder` block in `happyPathPersistBeforeCommitApplyBeforeRelease`.

##### Finding M12 [should-fix] — Pin the apply warn-log emission
**Location**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/atomicoperations/EndAtomicOperationHookOrderingTest.java` (`applyIndexCountFailureIsSwallowed` lines 375-402, `applyHistogramFailureIsSwallowed` lines 408-442)
**Dimensions**: test-behavior (TB3)
**Issue**: The cache-only contract claims "apply failure is logged as a warn and swallowed." Both swallow tests pin the swallow (no throw, the histogram branch still ran, commit was recorded, locks released) but do not verify that anything was logged. If the production `tryApply` helper at `AtomicOperationsManager.java:444` were simplified to a silent swallow (drop the `LogManager.instance().warn(...)` line), both tests would still pass — operators would lose all observability of a real apply failure in production. The `track-2.md` Clarifications section line 93 flags `BTreeEngineTestFixtures.captureSevereOn` as available for cross-package JUL capture; the affordance was not taken up.
**Proposed fix**: Add a JUL `Handler` to each test that captures `LogRecord`s emitted to `Logger.getLogger(AtomicOperationsManager.class.getName())`. After the swallow assertion, assert exactly one `Level.WARNING` record matching the original throwable (via `r.getThrown()`) and matching a branch-identifying substring in the message (e.g., `"Index count delta"` for the index branch, `"Histogram delta"` for the histogram branch). Remove the handler in a `finally` block.

##### Finding M13 [should-fix] — Pin `moveToErrorStateIfNeeded` two-call order in the persist-failure ordering test
**Location**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/atomicoperations/EndAtomicOperationHookOrderingTest.java` (`persistFailureSkipsCommitAndBothApplyHooks` lines 234-264)
**Dimensions**: test-behavior (TB4)
**Issue**: The persist-failure ordering test verifies the rethrow, the skipped `commitChanges`, the skipped apply hooks, and the table rollback record. It does not verify the two-call shape on `moveToErrorStateIfNeeded` that `EndAtomicOperationPersistHookTest:140-142` pins — entry-level `moveToErrorStateIfNeeded(null)` followed by Hook A's `moveToErrorStateIfNeeded(persistFailure)`. The apply-hook skip claim's load-bearing precondition is that Hook A's catch flipped `currentError` between those two calls; without pinning both, a regression that drops `moveToErrorStateIfNeeded(persistFailure)` from the catch would pass the ordering test even though the storage error-mode contract would silently degrade on the persist-failure path.
**Proposed fix**: Add an `InOrder errorOrder = inOrder(storage); errorOrder.verify(storage).moveToErrorStateIfNeeded(null); errorOrder.verify(storage).moveToErrorStateIfNeeded(persistFailure);` block after the existing verify chain in `persistFailureSkipsCommitAndBothApplyHooks`. Deliberate overlap with the persist-hook test's pin — defense in depth across the two test files that exercise the same path from different angles.

#### Deferred to next iteration

- **M7 (should-fix)** — `track-2.md` `## Context and Orientation` baseline-vs-HEAD line drift (WC4/F4). Add a single header note "All line numbers below match base commit `54ffb540`" rather than refreshing every cite individually.
- **M8 (should-fix)** — Surprises notes miscite plan lines 85/87/90 (correct: 132/137/200) and `logAndPrepareForRethrow` overloads across three reference frames (WC5/F5, WC6/F6).
- **M9 (should-fix)** — `mockOperationWithLockedComponents` near-duplicates `EndAtomicOperationHookTestSupport.mockOperation`; `getBTreeIndexEngine` reflective helper duplicated across `MainCommitCounterSyncTest` and `ClearIndexApiRollbackTest` (TS4, CQ-duplicated-reflection).
- **M14 (should-fix)** — No regression test for `commitChanges` IOException after Hook A succeeded (TY2). Add a Mockito test that stubs `persistIndexCountDeltas` to `doNothing`, stubs `commitChanges` to throw IOException, asserts the IOException propagates as-is, the apply hooks did not fire, and the lock is released.
- **M16 (should-fix)** — YTDB-971 pacing addendum consumes ~7% of the always-loaded plan token budget (WB1, WB2). Compress to a 2-3 line pointer paragraph; promote the operational content (five required-clause bullets, the bash recipe, buffering-pipe ban, Bash-vs-Monitor wait clause, targeted-coverage path, full-suite-mid-step ban) into `.claude/workflow/implementer-rules.md` § Pacing long-running tasks.
- **M17 (should-fix)** — `.claude/scripts/bg-task.sh` hardening (WH1-6). Six recommended items: assert single-argument launch contract, document or assert hex-only jobid invariant, guard jobid generation against silent empty output, guard empty `.pid` / `.exit` reads, scope `cmd_list` to the calling agent's session prefix, document the 6 h `BASH_MAX_TIMEOUT_MS` cap-on-stuck-process implication.
- **M18 (should-fix)** — Em-dash overuse in workflow Markdown (WS1-4). Sweep `implementation-plan.md` lines 31, 33, 47, 49, 79 and `track-2.md` lines 4, 175, 191, 192, 193, 232, 246 per the one-em-dash-per-paragraph rule.
- **M19 (should-fix)** — Recovery-time atomic ops at `AbstractStorage:766-860` are not exercised through a real WAL restoration path (TY4). The null-holder gate is unit-tested; an integration test confirming that no recovery-time atomic op reaches the persist / apply hooks would close the gap.

#### Plan corrections (route to other tracks or follow-up issues)

- **M15 (should-fix)** — No multi-thread test exercising the real `ReentrantReadWriteLock` contention window during Hook B's apply (TX1/C1). All "lock held during apply" pins in the cumulative diff read mocked Boolean flags. The cleanest production-fidelity test wires two `AtomicOperation` instances against a real `StorageComponent` with a real `ReentrantReadWriteLock` (no Mockito, no production storage). Either land in iteration 2 if implementer time permits, or file as a follow-up YouTrack issue under `YTDB` tagged `dev-workflow` — Tracks 3 and 4 will depend on the contention guarantee being machine-verified rather than asserted only at the source-ordering level.

#### Synthesis audit trail

- `DROP CS1-CS3` — crash-safety reviewer returned 0 critical / 0 concerning / 3 informational; the informational items (partial WAL records orphan correctly, partial-loop divergence inherits prior contract, latch hoist is now decorative defense-in-depth) are reviewer-confirmed safe-to-ship behaviours, not findings.
- `DROP PF` — performance reviewer returned 0 findings; the empty-holder fast path is allocation-free and lock-free; the `tryApply` Runnable lambda inlines under C2.
- `DROP BC1-BC5` — bugs-concurrency reviewer marked all five entries as "Potential Concerns" / informational with no blockers. BC1 (partial-loop apply throw divergence) inherits the prior contract; BC2 (`!isRollbackInProgress` redundant) is documented symmetry pin; BC3 (`assert` for type narrowing), BC4 (public visibility hypothetical out-of-band invocation), BC5 (warn log identity manager vs storage) are minor diagnostic-quality suggestions, deferred as non-actionable polish.
- `DROP TC3, TC5-TC7, TY5-TY6, TX2, TB5, WC7-WC8, WB3, WH7-WH9, WS5-WS8, CQ-minor, TS6-TS8` — minor-severity items deferred as out-of-scope polish; no plan-correction route warranted. The track's overall test coverage is comprehensive; these items are nice-to-have rather than load-bearing.
- `OVER-BUDGET 10 findings / ~9 files` — iteration 1 carries five blockers (M1-M5) plus M6 (mechanical 7-file Javadoc sweep) plus four test-additions (M10-M13). Five of the seven sweep files in M6 are test files; M10-M12 add to one of those (`EndAtomicOperationHookOrderingTest.java`); M13 adds to another. File reuse keeps the spawn coherent within ~9 distinct files. Accepting the larger spawn keeps the blockers from being split across iterations.

## Resume notes

- **Do NOT redo**: the 13-agent dimensional review fan-out (returns are synthesised above); the synthesis steps (Steps 1-4 of `finding-synthesis-recipe.md`); the audit-trail bucketing decisions.
- **On user approval**: spawn the `level=track`, `mode=FIX_REVIEW_FINDINGS` implementer with the verbatim findings list above as `findings:`, `base_commit=54ffb540687ed0a78622bb96e86123c5e58af082`. Use the shared Implementer Prompt Template from `step-implementation.md` §Implementer Prompt Template. The model is `opus` per `risk-tagging.md` §"Risk levels — quick reference"; subagent_type is `general-purpose`.
- **Before spawning**, re-stage the per-track temp files (per `track-code-review.md` Phase C Startup step 7):
  - `python3 .claude/scripts/render-slim-plan.py --plan-path docs/adr/null-count-error/_workflow/implementation-plan.md --out /tmp/claude-code-plan-slim-$PPID.md`
  - `git diff 54ffb540687ed0a78622bb96e86123c5e58af082..HEAD > /tmp/claude-code-track-2-diff-$PPID.patch`
  - `git diff 54ffb540687ed0a78622bb96e86123c5e58af082..HEAD --name-only > /tmp/claude-code-track-2-files-$PPID.txt`
- **On fixes requested**: if the user re-reviews the findings list and proposes edits (severity changes, drop/add items, scope adjustments), apply them inline before composing the implementer prompt. No respawn of dimensional reviewers is needed unless the user explicitly redirects.
- **After implementer returns `SUCCESS`**: append `- [x] <ISO> [ctx=<level>] Track-level code review iteration 1 complete (1/3 iterations)` to the `## Progress` section of `track-2.md`; commit + push as a Workflow update; run the gate-check fan-out on the re-staged diff (re-staging required after the `Review fix:` commit grows the cumulative diff).
- **Iteration counter accounting**: this handoff resolves pre-spawn — no iteration has consumed a counter yet. After the iteration 1 implementer returns and the gate-check completes, 1/3 iterations is consumed.
