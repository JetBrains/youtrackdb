# Handoff: Phase A — Track 2 iter-1 reviews done, fixes pending

**Paused:** 2026-05-23
**Phase:** A
**Context level at pause:** warning
**Branch:** YTDB-958-null-count-error
**HEAD:** 21821cfa21 "YTDB-958: Apply pre-flight amendments before Track 2"
**Unpushed:** 0 commits

## Durable artifacts on disk

- `docs/adr/null-count-error/_workflow/plan/track-2.md` — Pre-Flight amendments + Clarifications committed (21821cfa21).
- `docs/adr/null-count-error/_workflow/implementation-plan.md` — citation refresh + Strategy refresh line committed (21821cfa21).
- `## Outcomes & Retrospective` in track-2.md is EMPTY. No Phase A review entries have landed yet. Iter-1 findings live only in this handoff.

## Pending decision

Two design decisions block iter-1 fix-application. They must be resolved before the orchestrator writes track-file amendments and spawns the iter-2 gate-check.

**Q1. Hook A persist-failure re-raise wrapping.** Blocker A1 (compile failure of `throw error`) + Blocker A2 (re-raised `IOException` lands at `commit()` `catch(Throwable)` at AbstractStorage:2429 → `logAndPrepareForRethrow(Throwable)` → `setInError` → permanent error state). Today's manual persist at AbstractStorage:2340 wraps as `StorageException` (line 2341 catch); the wrap lands at outer `catch(RuntimeException)` at line 2425 → `logAndPrepareForRethrow(RuntimeException)` which does NOT call `setInError` (Track 1 cross-track signal e). Track 2's `throw error;` as-written re-raises the raw type, losing the StorageException wrap — regression from today.

Options:
- **(a) Wrap as StorageException in Hook A's catch (RECOMMENDED).** Matches today's contract at line 2341. After releaseLocks, `throw error` re-raises the StorageException (a RuntimeException via BaseException). Lands at outer catch(RuntimeException) → no setInError. Compiles cleanly: endAtomicOperation already declares `throws IOException`; RuntimeException is unchecked.
- **(b) Instanceof-chain rethrow.** Preserve original exception types via `if (e instanceof IOException io) throw io; else if (e instanceof RuntimeException re) throw re; else if (e instanceof Error err) throw err;`. Changes the caller-visible exception type for IOException specifically (today: StorageException; option b: IOException) — but the IOException routing through catch(Throwable) still triggers setInError.
- **(c) Add try/catch around endTxCommit at AbstractStorage:2363.** Convert escaping IOException to a rollback signal at the commit-method level. More invasive — touches commit() structure beyond Hook A.

Recommendation: (a). Reasons: preserves today's exception-type contract, avoids the setInError regression, keeps the change self-contained to Hook A's catch in AtomicOperationsManager.

**Q2. Visibility-raise mechanism for `persistIndexCountDeltas`, `applyIndexCountDeltas`, `applyHistogramDeltas`.** The Phase 2 Gemini-deferred decision now needs resolution. AbstractStorage lives in `impl.local`; AtomicOperationsManager lives in `impl.local.paginated.atomicoperations` — different packages, so plain package-private does not grant cross-package access. Three reviews split:

Options:
- **(a) Raise to `public` on AbstractStorage (T5 recommendation).** Matches existing manager-callback surface on the same class (`moveToErrorStateIfNeeded`, `getName`, `checkErrorState`, `getSnapshotIndexSize` are all public despite being internal-only). Lowest friction, consistent with precedent.
- **(b) Package-private + thin callback interface in the manager's package (R3 recommendation).** Introduce a `CounterLifecycleHook` (or similar) `@FunctionalInterface` in `paginated.atomicoperations`; install via the AtomicOperationsManager constructor; keep the three methods `private` on AbstractStorage. Tighter encapsulation; one indirection.
- **(c) Package-private + thin package-private accessor methods in the manager's package on AbstractStorage (A13 variation).** Add e.g. `AbstractStorage.runIndexCountPersistHook(AtomicOperation)` package-private wrappers near the existing three; the methods stay private; the wrappers are accessible from the manager. Two extra methods on AbstractStorage; one less indirection than (b).

Recommendation: (a). Reasons: matches the existing manager-callback precedent on AbstractStorage; the tighter-encapsulation arguments (b/c) treat these as "new exposure" but the class is already an `internal/` final-class surface — the `internal` package marker conveys the intent better than the visibility modifier. If the team wants tighter encapsulation of the manager-callback surface, that is a follow-up that tightens ALL existing public callbacks consistently, out of scope for this track.

## Verbatim re-present text — iter-1 findings (33 total)

### Technical findings (T1–T11)

- **T1 [should-fix]** — Hook A re-raise mechanism under-specified. Track prose says "`throw error;` after the inner-finally `releaseLocks`" without pinning placement relative to outer finally (writeOperationsFreezer.endOperation at AtomicOperationsManager:293) or naming the rethrow strategy. Decomposition must show exact statement. **Tied to Q1.**
- **T2 [should-fix]** — Exception-type contract change on persist-failure path. Today line 2341 wraps as StorageException; after Track 2 the re-raise loses the wrap (IOException / AssertionError / RuntimeException original types). Tests expecting StorageException would break. **Tied to Q1; option (a) preserves wrap.**
- **T3 [should-fix]** — Drop `assert status == STATUS.OPEN` in the three methods. The early-exit `if (holder == null) return;` is the load-bearing protection; the proposed assert fires false-positives during STATUS.MIGRATION (open-time site at AbstractStorage:860) and STATUS.CLOSING. APPLY: remove `assert status == STATUS.OPEN` line from track-2.md Plan of Work step 1 (line 76).
- **T4 [suggestion]** — `RecordSerializationContext` FQN in Clarifications #5 should resolve to `com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.RecordSerializationContext`, not the `serialization.serializer.record.binary.*` path PSI find-class produced NOT FOUND for. APPLY: tighten the Clarification text to name the package once.
- **T5 [suggestion]** — Visibility-raise: prefer `public` matching existing `AbstractStorage` manager-callback surface (`moveToErrorStateIfNeeded` at :3637, `getName`, `checkErrorState`, `getSnapshotIndexSize` all public). **Tied to Q2.**
- **T6 [suggestion]** — Add explicit "rollback skips both Hook A and Hook B" bullet to track-2.md `## Validation and Acceptance`. APPLY.
- **T7 [suggestion]** — Existing tests in `AtomicOperationsManagerNullLSNTest` and `AtomicOperationsManagerWrapperAssertionErrorTest` call `endAtomicOperation` directly with mock AtomicOperation. New Hook A/B early-exit because mock returns null from getIndexCountDeltas/getHistogramDeltas — green by accident. Phase B sub-task: run those tests after step 3 lands and stub the getters explicitly. DEFER to Phase B.
- **T8 [suggestion]** — Cross-track signal for Track 3: `ClearIndexApiRollbackTest` lands `@Ignore` at Track 2; Track 3's regression-test step owns the activation. Record at Track 2 completion in `## Outcomes & Retrospective`. DEFER to Track 2 completion.
- **T9 [suggestion]** — Hook B warn message strings should be byte-identical to the existing strings at AbstractStorage:2374–2378 and 2389–2392 (`"Index count delta application failed after successful commit"`, `"Histogram delta application failed after successful commit"`). DEFER to Phase B step-implementation.
- **T10 [suggestion]** — One-time FQN orientation for engine classes: `com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeMultiValueIndexEngine`, `…v1.BTreeSingleValueIndexEngine`. `IndexHistogramManager` lives at `…core.index.engine.IndexHistogramManager` (not `…index.*`). APPLY in track-2.md (one orientation line).
- **T11 [suggestion]** — Track 2's Out-of-scope correctly excludes IndexCountDelta flag fields; the holder shape is unchanged. Documents the cross-reference for the decomposer. No action; informational.

### Risk findings (R1–R9)

- **R1 [should-fix]** — Lock-window placement is invisible to a future reader of endAtomicOperation. The only structural defense is the planned `EndAtomicOperationHookOrderingTest`. Require Phase B to land an inline source-comment block above Hook B citing (a) the per-index lock at AbstractStorage:2255, (b) releaseLocks inner-finally as the release site, (c) Tracks 3/4 as dependents, (d) the test as the regression guard. DEFER to Phase B step-implementation prose.
- **R2 [should-fix]** — D2 Risks/Caveats's "load-bearing" framing in implementation-plan.md is generic ("outer catch (Error) cascade"). Refine to name the specific cascade vectors: (i) IOException → catch(Throwable) → setInError (load-bearing); (ii) AssertionError — Track 1's guard contains independently; (iii) RuntimeException — logAndPrepareForRethrow(RuntimeException) does not call setInError; conversion for rollback semantics, not cascade containment. APPLY to implementation-plan.md D2.
- **R3 [should-fix]** — Visibility mechanism: prefer package-private + thin callback interface (CounterLifecycleHook). **Tied to Q2.**
- **R4 [suggestion]** — Add a comment in Phase B above Hook A and Hook B noting `LinkageError | OutOfMemoryError | StackOverflowError` are deliberately excluded (matches Track 1 wrapper precedent). DEFER to Phase B.
- **R5 [suggestion]** — Better assert message — moot if T3 is applied (drop the assert).
- **R6 [suggestion]** — Stub `getIndexCountDeltas()` / `getHistogramDeltas()` to return null in existing tests (`AtomicOperationsManagerNullLSNTest`, `AtomicOperationsManagerWrapperAssertionErrorTest`) so the early-exit contract is explicit. DEFER to Phase B.
- **R7 [suggestion]** — Optional concurrency test `EndAtomicOperationLockWindowConcurrencyTest`. Defense-in-depth regression guard. DEFER (optional).
- **R8 [suggestion]** — Add a paragraph to D2 or track-2.md C&O: "Hook A's `persistCountDelta` writes go through the atomic op's `pageChangesMap`, not directly to disk. They are durable only after `commitChanges` completes the WAL flush and cache application. If `commitChanges` throws, the persist's effects are discarded along with the rest of the atomic op's writes. The conversion catch in Hook A is therefore only required for failures *during* `persistCountDelta` itself (BTree underflow, IO at page allocation), not for failures during `commitChanges`." APPLY to track-2.md C&O.
- **R9 [suggestion]** — Test placement consistency: `MainCommitCounterSyncTest` and `ClearIndexApiRollbackTest` next to Track 1 cascade tests (`core/.../storage/impl/local/`); `EndAtomicOperationHookOrderingTest` next to existing manager tests (`core/.../paginated/atomicoperations/`). DEFER to Phase B (pin in step prose).

### Adversarial findings (A1–A13)

- **A1 [BLOCKER]** — `throw error;` will not compile. `error` is `Throwable`; method only declares `throws IOException`. **Tied to Q1.**
- **A2 [BLOCKER]** — Persist-IOException re-raise routes through `commit()` outer `catch(Throwable)` at AbstractStorage:2429 → `logAndPrepareForRethrow(Throwable)` → `setInError` → permanent error state. Regression from today's StorageException-wrap path at line 2341. **Tied to Q1; option (a) resolves.**
- **A3 [should-fix]** — `IndexHistogramManager.applyDelta` (called from Hook B's `applyHistogramDeltas`) conditionally invokes `flushSnapshotToPage`, which spawns a nested `executeInsideAtomicOperation` at `IndexHistogramManager.java:1975`. If the nested op's commitChanges throws IOException, the nested `endAtomicOperation` calls `moveToErrorStateIfNeeded(error)` → `setInError` — flipping the storage to permanent error state inside a "successful" outer commit. Today's manual `applyHistogramDeltas` at AbstractStorage:2381 already has this vulnerability; Track 2 inherits it (not a Track 2 regression). APPLY: document in D3 Risks/Caveats; consider follow-up YouTrack issue. NO design change.
- **A4 [should-fix]** — Nested-op lock release fragility: `releaseLocks` walks `operation.lockedComponents()`. If a future nested op inside Hook B adds the same component, its `releaseLocks` could release a lock the outer op considers held. APPLY: add assertion in `EndAtomicOperationHookOrderingTest` (Phase B step 4) that outer op's lockedComponents set is unchanged after Hook B returns. DEFER test creation to Phase B.
- **A5 [should-fix]** — `ClearIndexApiRollbackTest @Ignore` until Track 3 — effective Hook A/B coverage on the `clearIndex` API path between Track 2 land and Track 3 land is ZERO (today's `clear()` writes AtomicLong directly, never accumulates a delta; Hook B early-exits with holder == null). Two responses: (i) acknowledge coverage gap in `## Validation and Acceptance` explicitly; (ii) add an interim test that reflectively injects a non-null IndexCountDelta into the holder for the `clearIndex` path to exercise Hook A/B. Option (i) is light, (ii) is heavier. APPLY (i) now; DEFER (ii) to Phase B test step if reviewers demand it.
- **A6 [should-fix]** — Hook A idempotency only transitively enforced (through `loadPageForWrite`'s `checkIfActive()`). Add an explicit `operation.checkIfActive()` at the start of Hook A so the contract is self-evident at the lifecycle level. DEFER to Phase B step-implementation (one-line add).
- **A7 [should-fix]** — Hook B "last before releaseLocks" no code-level enforcement. Same as R1 — DEFER to Phase B (inline comment block).
- **A8 [suggestion]** — cleanupSnapshotIndex catch at AbstractStorage:2396 covers RuntimeException only, not AssertionError. Track 2 leaves it; Track 1 carry-forward exists. Since Track 2 already touches adjacent lines, broadening for symmetry is cheap. APPLY in Phase B (one-token change) OR DEFER to the Track 1 carry-forward queue. Decomposer's call.
- **A9 [suggestion]** — Assert wording inconsistency — moot if T3 is applied (drop the assert).
- **A10 [suggestion]** — Document Error-subtype handling (OOM, SOE, LinkageError, InternalError) in D2 Risks/Caveats — Hook A's catch is bounded to IOException | RuntimeException | AssertionError; other Error subtypes deliberately escape. APPLY to D2.
- **A11 [suggestion]** — Doc-comment sync in tests: 12 test/main files reference the manual call sites by name in comments. Track 2's deletion makes those stale. DEFER to Phase B step 4 sub-task.
- **A12 [skip]** — JIT dead-store elimination of `error = persistFailure`. Rejected by the adversarial reviewer's own scrutiny — invariant holds. No action.
- **A13 [suggestion]** — Visibility mechanism: package-private + accessor. **Tied to Q2.**

## Triage map (after Q1/Q2 resolution)

**Apply to track-2.md (Phase A track-file amendments):**
- T3 — drop `assert status == STATUS.OPEN` from Plan of Work step 1
- T4 — correct RecordSerializationContext FQN in Clarifications
- T6 — add rollback-skip bullet to Validation and Acceptance
- T10 — add v1 sub-package FQN orientation (one line)
- R8 — add Hook A page-changes-map note to C&O
- A5(i) — add coverage-gap acknowledgement to Validation
- T1+A1 — pin re-raise pseudocode per Q1 outcome (in Plan of Work step 2)
- T2 — exception-wrapping contract per Q1 outcome (in Plan of Work step 2 commentary)
- T5/R3/A13 — visibility mechanism per Q2 outcome (in Plan of Work step 1)

**Apply to implementation-plan.md (D2/D3 amendments):**
- R2 — refine D2 Risks/Caveats to name IOException vector
- A3 — document histogram nested-op vulnerability in D3 Risks/Caveats
- A10 — document Error-subtype handling in D2 Risks/Caveats

**Defer to Phase B step-implementation:**
- R1+A7 — inline Hook B placement comment block
- R4 — LinkageError/OOM/SOE comment on Hook A/B
- A6 — explicit `operation.checkIfActive()` at Hook A start
- R6 — stub holder getters in existing tests
- A11 — doc-comment sync in tests
- T7 — verify existing endAtomicOperation tests survive
- T9 — exact warn message strings
- R9 — test file placement

**Defer to Phase B test step (4):**
- A4 — nested-op lock release assertion in EndAtomicOperationHookOrderingTest
- A5(ii) — interim test for clearIndex Hook B coverage (optional)
- A8 — cleanupSnapshotIndex catch broadening (optional)
- R7 — concurrency test (optional)

**Defer to Track 2 completion:**
- T8 — record ClearIndexApiRollbackTest @Ignore in Outcomes & Retrospective

**No action (informational / rejected):**
- T11 — Track 3 dependency cross-reference (informational)
- R5/A9 — moot if T3 is applied
- A12 — rejected by reviewer

## Resume notes

**Do NOT redo:**
- Iter-1 technical / risk / adversarial reviews (already complete; findings above).
- Pre-Flight gate (committed in 21821cfa21 — Strategy refresh ADJUST + Clarifications + citation refresh).
- Branch Divergence Check (cleared at session start — 0 behind, 29 ahead, working tree clean except the pre-existing root-level HANDOFF_*.md and Pre_Tests log untracked files).

**On user approval of Q1 + Q2 outcomes:**
1. Apply mechanical track-file amendments (T3, T4, T6, T10, R8, A5(i)) and Q1/Q2-tied amendments (T1+A1+T2, T5/R3/A13).
2. Apply implementation-plan.md amendments (R2, A3, A10).
3. Commit the amendments (single workflow-update commit per `commit-conventions.md`).
4. Spawn iter-2 gate-check sub-agent — one verification per finding accepted in iter-1, using the prompt at `.claude/workflow/prompts/review-gate-verification.md` (one per review type — Technical, Risk, Adversarial). Use the four-section template defined in `review-iteration.md`.
5. On iter-2 PASS: record three `## Outcomes & Retrospective` entries (`- [x] Technical: PASS at iteration 2 (N findings, K accepted)`, `Risk: PASS at iteration 2 …`, `Adversarial: PASS at iteration 2 …`). Then proceed to step decomposition (§What You Do sub-step 4 of `track-review.md`).
6. Decompose Track 2 into 4 steps with `risk:` tags:
   - Step 1: visibility raise + drop bogus assert (per Q2 outcome). `risk: medium`.
   - Step 2: Hook A persist + conversion catch + re-raise (per Q1 outcome) + inline comments. `risk: high` (lifecycle code, blocker A2's regression vector).
   - Step 3: Hook B apply + histogram parallel + log-and-swallow + inline placement comment + `EndAtomicOperationHookOrderingTest`. `risk: high` (lock-window invariant load-bearing).
   - Step 4: delete manual calls in AbstractStorage.commit + add `MainCommitCounterSyncTest` + `@Ignore`d `ClearIndexApiRollbackTest` + doc-comment sync sweep. `risk: medium`.
7. Append Progress entry `- [x] <ISO> [ctx=<level>] Review + decomposition complete`.
8. Single workflow-update commit `Phase A review and decomposition for Track 2`. Push.
9. End session via self-improvement reflection (workflow rule).

**On Q1 / Q2 disagreement** (user rejects (a) for either): re-triage T1/T2/A1/A2/T5/R3/A13 against the chosen alternative; the mechanical fixes (T3/T4/T6/T10/R8/A5(i)) and plan-file amendments (R2/A3/A10) apply regardless.

**On fixes requested for the iter-1 verdict** (user disagrees with any finding's severity): re-classify, adjust the apply list, then proceed as above.

**Cross-references:**
- Track 1 episode lives at `docs/adr/null-count-error/_workflow/implementation-plan.md` lines 118–128.
- Track 2 four Phase 1 sections + Clarifications subsection on disk in `_workflow/plan/track-2.md` (post-Pre-Flight state).
- `ClearIndexApiRollbackTest` activation is owned by Track 3's regression-test step.
- Adversarial review's full text is preserved in this handoff (severity + one-line fix) — re-spawning the adversarial reviewer is forbidden by §Forbidden actions while unresolved.
