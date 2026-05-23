# Handoff: Phase B — Track 2 Step 2 dim-review iteration 1 complete

**Paused:** 2026-05-23
**Phase:** B
**Context level at pause:** warning
**Branch:** YTDB-958-null-count-error
**HEAD:** 46a8edfa06 "YTDB-958: Add persist hook to endAtomicOperation lifecycle"
**Unpushed:** 0 commits

## Durable artifacts on disk

- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/IndexCountDeltaHolder.java` — `persisted` boolean + accessors (Step 2 commit `46a8edfa06`).
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` — `holder.setPersisted()` call at end of `persistIndexCountDeltas` (Step 2 commit).
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/atomicoperations/AtomicOperationsManager.java` — Hook A + typed re-raise (Step 2 commit).
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/atomicoperations/EndAtomicOperationPersistHookTest.java` — 10 Mockito tests (Step 2 commit).
- `docs/adr/null-count-error/_workflow/plan/track-2.md` — Step 1 episode + `## Base commit` recorded. Step 2 roster still `[ ]` (episode pending).
- `docs/adr/null-count-error/_workflow/implementation-plan.md` — YTDB-971 implementer pacing section (commit `41bb1492c1`).
- `/tmp/claude-code-plan-slim-850.md` — slim plan snapshot (regenerated after the YTDB-971 commit; will be stale at resume — regenerate before respawning).
- Background test artifacts under `core/target/databases/` may exist from the agent's killed Maven runs; safe to ignore.

## Pending decision

**Decision-shape:** dim-review iteration 1 of Step 2 (risk: high) returned ~22 findings from 9 review agents. The synthesized list is below. The next session must respawn the Step 2 implementer with `mode=FIX_REVIEW_FINDINGS` carrying the findings, run gate-check iteration 2 on the resulting `Review fix:` commit, and proceed to sub-steps 5–8 (cross-track impact + context check + episode write + push).

No user decision needed for the findings themselves; this is automatic dispatch. The user's only choice on resume is whether to accept the full findings list or have the orchestrator trim to a smaller subset (the recovery protocol's `≥ 15` rule applies — current list is ~22 items including suggestions).

## Verbatim re-present text

### Iteration 1 synthesized findings

**Must-fix (should-fix severity) — implementer respawn input:**

M1. `EndAtomicOperationPersistHookTest.java` test `persistHookConvertsRuntimeExceptionToRollbackAndShortCircuits` (line 200) missing two `verify(...)` lines that its `ComponentException` sibling has. Add: `verify(storage, times(1)).persistIndexCountDeltas(operation);` and `verify(table, never()).commitOperation(42L);`. (review-test-behavior TB1)

M2. The three persist-failure tests (`persistHookConvertsComponentException...`, `...RuntimeException...`, `...AssertionErrorToRollbackAndWraps`) at lines 158, 200, 242 pin `moveToErrorStateIfNeeded(thrown)` but not the entry-level `moveToErrorStateIfNeeded(null)`. Use `Mockito.inOrder(storage)` then `.verify(storage).moveToErrorStateIfNeeded(null)` then `.verify(storage).moveToErrorStateIfNeeded(thrown)`. (review-test-behavior TB2 + review-test-completeness R2)

M3. `persistHookConvertsAssertionErrorToRollbackAndWraps` (line 242) missing `verify(storage, times(1)).persistIndexCountDeltas(operation);` and `verify(table, never()).commitOperation(42L);`. (review-test-behavior TB3)

M4. Bounded-catch coverage: add three sibling tests modeled on `persistHookDoesNotCatchOutOfMemoryError` for `LinkageError`, `StackOverflowError`, `InternalError`. The Javadoc claims VM-error escape for all four; only OOM is pinned. Parameterized JUnit 4 with `{thrown}` row supplier acceptable. (review-test-completeness R1)

M5. Add `InOrder` verification on the AssertionError test (most load-bearing): `ord.verify(operation).rollbackInProgress(); ord.verify(operation).lockedComponents(); ord.verify(operation).deactivate();`. Pins the locks-released-before-throw invariant that is the architectural reason the re-raise lives outside the inner-try. (review-test-completeness R3)

M6. Add test `persistHookBehaviourWhenStorageAlreadyInError`: storage in error mode (`when(storage.isInError()).thenReturn(true)`) but inbound `error == null`. Hook A's gate does NOT check `isInError()` — it only checks `currentError == null && !operation.isRollbackInProgress()`. Test either contract: persist attempts anyway (current behavior) OR the hook should skip. If the production code lacks the `isInError()` guard intentionally, document that in the test Javadoc and pin the "attempts anyway" contract. (review-test-crash-safety TY1)

M7. Cross-layer assertion: AssertionError persist-failure must leave storage usable per the Track 1 `setInError` AssertionError guard (`AbstractStorage` lines 1769–1771). The current mocked test cannot verify this. Two acceptable resolutions: (a) add a Javadoc `@see SetInErrorAssertionErrorGuardTest` cross-reference (preferred — the guard is already pinned in Track 1's test class); (b) add a partial Mockito spy delegating `moveToErrorStateIfNeeded` and `isInError` to the real `AbstractStorage` instance. Pick (a) unless the spy is needed for other reasons. (review-test-crash-safety TY2)

M8. `primeFreezer` helper (line 288–299) hardening: wrap the cast + `startOperation()` in one try block; catch `ClassCastException | NullPointerException | IllegalStateException` and re-throw as `AssertionError("primeFreezer no longer matches AtomicOperationsManager.writeOperationsFreezer contract: ...", e)`. Assert `freezer != null` before calling. (review-test-structure TS1)

M9. Drop unused stubs from `setUp` at lines 263–277: `getReadCache()`, `getWriteCache()`, `getIdGen()`. Keep `getName` and `getWALInstance`. (review-test-structure TS2)

M10. Extract `EndAtomicOperationHookTestSupport` (package-private, in `atomicoperations` test package) with `primeFreezer`, `mockOperation`, and the standard mock-storage builder. Step 3 (Hook B) will reuse it. The refactor is small and avoids a copy-paste in Step 3. (review-test-structure TS3)

M11. Plan documentation drift: `docs/adr/null-count-error/_workflow/plan/track-2.md` lines 87 and 175 still reference `IOException | RuntimeException | AssertionError` for Hook A's catch. The as-implemented catch is `RuntimeException | AssertionError` because `AbstractStorage.persistIndexCountDeltas` does not declare `IOException`. Narrow the citations to match. **This is orchestrator-owned (track file edit), not implementer-owned.** Apply as a workflow-update commit alongside the episode commit. (review-bugs-concurrency BC1)

**Suggestion-severity — implementer may include if cheap:**

S1. Replace hard line-number citations with stable anchors. Sites: `AtomicOperationsManager.java:283–285` and `:297–304` comment blocks, `EndAtomicOperationPersistHookTest.java:214–253` class Javadoc (the "lines 1769–1771" reference). Replace with method-name phrases (`AssertionError skip-branch in AbstractStorage.setInError`). (review-code-quality CQ1 + review-test-structure TS6)

S2. Add `import java.util.Collections;` and replace `java.util.Collections.emptyList()` / `emptySet()` at lines 312–313 with imported short-form. (review-code-quality CQ2)

S3. One-line comment above `currentError != error` gate at `AtomicOperationsManager.java:357`: `// currentError != error is true only when Hook A's catch overwrote the slot, since Hook A is the only mutator in this method.`. (review-code-quality CQ3)

S4. Threading-contract note on `IndexCountDeltaHolder.persisted` field. Add to the existing field Javadoc: `// Thread-confinement note: plain boolean because the holder lives on a single AtomicOperation, driven by exactly one thread between startAtomicOperation and endAtomicOperation. If a future path persists from a different thread, this field must become volatile or move behind a synchronizer.` (review-code-quality CQ6 + review-test-concurrency TX1)

S5. Add production assert at `AtomicOperationsManager.java` around line 357 (inside the `if (currentError != null && currentError != error)` block, before the `instanceof` dispatch): `assert currentError instanceof RuntimeException || currentError instanceof AssertionError : "Hook A inner-catch must yield RuntimeException | AssertionError, got " + currentError.getClass().getName();`. Catches a future regression that broadens Hook A's inner catch without updating the rethrow dispatch. (review-test-crash-safety TY3)

S6. Symmetric verifications in `persistHookSkipsWhenHolderIsNull` (line 557 → actual line varies in current file): add `verify(storage, times(1)).moveToErrorStateIfNeeded(null);` and `verify(table, never()).rollbackOperation(42L);`. (review-test-behavior TB4)

S7. OOM test (line 277) explicit anchors: `verify(storage, times(1)).persistIndexCountDeltas(operation);`, `verify(table, never()).rollbackOperation(any(Long.class));`, `verify(table, never()).commitOperation(any(Long.class));`. (review-test-behavior TB5 + review-test-structure TS5)

S8. AssertionError wrap test (line 242) — add `dbName` + message pinning: `assertEquals(STORAGE_NAME, wrapped.getDbName());` and `assertTrue(wrapped.getMessage().contains("Error during transaction commit"));`. (review-test-completeness M1)

S9. Non-empty holder forwarding test — populate the holder with `holder.getOrCreate(7).addDelta(3L, 1L)` in a new or modified happy-path test and assert `holder.isPersisted() == false` after the mocked (no-op) persist returns. Pins the contract that the hook itself does not set the latch — `AbstractStorage.persistIndexCountDeltas` is responsible. (review-test-completeness M2)

S10. `mockOperation` helper — document the `getHistogramDeltas() → null` stub purpose with a one-line comment naming it as forward compatibility with Step 3. (review-test-structure TS7)

**Deferred (not in scope for Step 2 review iteration):**

D1. BC2: Verify `atomicOperationsTable.rollbackOperation` has no state-machine guard that fires when called from Hook A's new path. Cover in Step 3's `EndAtomicOperationHookOrderingTest`.

D2. BC3: Test that `AbstractStorage.persistIndexCountDeltas` invokes `holder.setPersisted()`. Cover in Step 4's `MainCommitCounterSyncTest` with an integration-level assertion.

D3. CS1: Reorder Hook A catch body so `operation.rollbackInProgress()` fires first (defensive against future maintainers adding I/O to `moveToErrorStateIfNeeded`). Not a current bug; hardening for future. Defer.

### Synthesised findings list for FIX_REVIEW_FINDINGS prompt

The implementer prompt should pass the M-suite plus S1–S10 (skip D1–D3). The full prompt template lives in `step-implementation.md` § Implementer Prompt Template; populate `mode: FIX_REVIEW_FINDINGS`, `findings:` with M1–M11 + S1–S10 verbatim from above.

**Orchestrator-owned (M11) handling:** the track file edit for the catch-list documentation drift is NOT in the implementer's `findings:` field — apply it directly during the episode write in sub-step 7 (touch the same workflow-update commit), or carry it as a separate workflow-update commit.

## Resume notes

- **Do NOT redo:** Step 1 episode (already committed at `797d3b326d`); Step 2 implementer commit (`46a8edfa06`); dim-review iteration 1 fan-out (9 agents already returned with findings synthesised above); the YTDB-971 implementer-pacing plan addition (committed at `41bb1492c1`).
- **On user approval (default path):** regenerate `/tmp/claude-code-plan-slim-$PPID.md` against the current implementation-plan.md; re-stage the Step 2 diff (`git diff 46a8edfa06~1..46a8edfa06 > /tmp/claude-code-step-2-2-diff-$PPID.patch` and the matching files list); respawn the Step 2 implementer with `mode=FIX_REVIEW_FINDINGS`, `findings:` from the M-suite + S-suite block above, and the YTDB-971 pacing clauses (now in implementation-plan.md). Run gate-check iteration 2 on the resulting `Review fix:` commit. Then sub-steps 5–8 (cross-track impact, context check, episode write, push).
- **On user request to trim findings:** keep M1–M7 + M11 only (the 7 strongest must-fix items); defer the rest to Phase C track-level review.
- **On user redirect:** any redirect is acceptable — the Step 2 commit `46a8edfa06` stands on disk and is fully green at the unit-test level (18,291 tests pass) per the agent's transcript at `2026-05-23T16:24:24Z`.

### Special note — YTDB-971 implementer pacing applies on respawn

The new section `### Implementer pacing (YTDB-971)` in `implementation-plan.md` (lines after `### Constraints`) requires that every implementer prompt include the four pacing clauses verbatim. The next orchestrator MUST include them in the FIX_REVIEW_FINDINGS respawn prompt. The Step 2 implementer's two prior exits without RESULT block were the exact orphan-Maven anti-pattern the section codifies; do not repeat the pattern.

## Reflection candidates (for self-improvement)

Captured here because reflection at warning level is mandatory but the formal protocol burn would push into critical. Items worth a YouTrack issue under `YTDB` with tag `dev-workflow`:

1. **Orchestrator must not interpret harness `status: completed` as terminal proof of agent termination.** The harness reported `completed` after a SendMessage resumption, and a 5-minute JSONL transcript gap was misread as the agent dying. The agent was alive and running Maven. Need a more reliable signal — perhaps a `RESULT:` block in the agent's last assistant message, not the harness status field.

2. **Orchestrator killed running Maven JVMs (PID 44036, 44061, 45028) based on the misread.** The user interrupted before further damage. Add a hard rule to the recovery protocol: before sending any kill signal to a process the implementer started, verify the agent's JSONL has at least N minutes of inactivity (suggested N=15 for Maven test runs). The YTDB-971 anti-pattern documents the implementer side; this is the orchestrator side.

3. **YTDB-971 pacing rules need to be wired into the standard implementer prompt template at `step-implementation.md`**, not just per-branch in implementation plans. The branch-level addition is correct as a near-term mitigation but the durable fix is in the workflow itself.
