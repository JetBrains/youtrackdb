<!--
MANIFEST
dimension: bugs-concurrency
step: 3
iteration: 1
commit_range: af97354a82~1..af97354a82
verdict: pass-with-suggestions
blocker_count: 0
should_fix_count: 0
suggestion_count: 3
evidence_base: 3 certs (C1 recordExists enumeration gap; C2 exitCommitWindow assert-only underflow; C3 documented-not-asserted precondition)
cert_index: C1, C2, C3
flags: none
index:
  - id: BC1
    sev: suggestion
    anchor: "#bc1-recordexists-is-not-commit-window-aware-enumeration-gap-for-step-4"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java:4817
    cert: C1
    basis: PSI find-usages (recordExists, executeExists, exists) + grep .exists( in schema serializers + reachability trace
  - id: BC2
    sev: suggestion
    anchor: "#bc2-exitcommitwindow-underflow-is-guarded-only-by-an-assert"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java:3393
    cert: C2
    basis: diff read + assert-disabled-in-production semantics + isCommitWindowActive predicate trace
  - id: BC3
    sev: suggestion
    anchor: "#bc3-the-write-lock-held-precondition-has-no-runtime-guard"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java:3380
    cert: C3
    basis: ScalableRWLock PSI method list (no owner query) + design intent in track Decision Log
-->

## Findings

### BC1 [suggestion] `recordExists` is not commit-window-aware (enumeration gap for Step 4)

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (line 4817)

**Issue**: Step 3's stated deliverable is the full lock-free read substrate: "Enumerate the full reachable readLock set with PSI find-usages ... and confirm each is replaced or already lock-free" (track Concrete Steps §Step 3). The step made `getPhysicalCollectionNameById` and `readRecordInternal` commit-window-aware, but `recordExists` (line 4817) still takes `stateLock.readLock()` unconditionally with no `isCommitWindowActive()` check. It is a sibling record-read entry point on the same `stateLock` surface. If any code reached under the held write lock during commit calls `record.exists()`, the read-lock re-acquire busy-spins forever on the non-reentrant `ScalableRWLock` — the exact deadlock this step exists to prevent.

**Evidence**: PSI find-usages gives the reachability chain `RecordAbstract#exists` (RecordAbstract.java:382) -> `DatabaseSessionEmbedded#exists` -> `FrontendTransactionImpl#exists` (FrontendTransactionImpl.java:447) -> `DatabaseSessionEmbedded#executeExists` (line 1785) -> `AbstractStorage#recordExists` (line 4817, plain `stateLock.readLock().lock()`). `executeExists` also calls `getPhysicalCollectionNameById` (line 1792), so the exists path partly overlaps the now-lock-free surface yet leaves `recordExists` on the read lock. The commit-window read path today routes through `session.load` (`executeReadRecord` -> `storage.readRecord` -> `readRecordInternal`), not `.exists()`, and `grep .exists(` over `SchemaShared.java` / `SchemaClassImpl.java` returns nothing — so the schema serialize/promote path does not exercise this gap at present. That is why this is a suggestion, not a blocker: the deadlock is latent, not live in the tested scope.

**Refutation considered**: Could `recordExists` be unreachable from any commit-body read under the write lock, making the omission correct rather than a gap? For the schema serialize/promote path specifically, yes — it uses `load`, confirmed by the empty `.exists(` grep on the serializers. But Step 4 wires the window around the whole reconciliation + promotion body, and the enumeration mandate is about the *reachable set*, not just the schema serializers. The security check, listeners fired in-window, or a future promotion read could call `exists()`. I cannot prove the full Step-4-in-window call tree from this step's diff alone, so the safe conclusion is: the substrate is incomplete by one sibling method against its own enumeration mandate.

**Suggestion**: Either make `recordExists` commit-window-aware with the same `isCommitWindowActive()` guard the other two methods use, or, when Step 4 lands, run the full PSI enumeration of `stateLock.readLock()`-taking methods reachable from the window body and explicitly record that `recordExists` is provably unreachable. Cert C1.

### BC2 [suggestion] `exitCommitWindow` underflow is guarded only by an `assert`

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (line 3393)

**Issue**: `exitCommitWindow` decrements the depth behind `assert depth[0] > 0`. Java assertions are off by default in production, so an over-exit (one stray `exitCommitWindow` without a matching `enterCommitWindow`, e.g. a mis-placed `finally` in Step 4) silently drives the counter negative. A subsequent legitimate `enterCommitWindow` then leaves the counter at `0` (or still negative), so `isCommitWindowActive()` returns `false` while the commit holds the write lock — and the next lock-free-intended read re-acquires `stateLock.readLock()` and re-introduces the very busy-spin deadlock this substrate removes. The failure mode is a silent re-deadlock, not a loud error.

**Evidence**: Diff lines 68-72 (`exitCommitWindow`) and 80-82 (`isCommitWindowActive` tests `> 0`). `enterCommitWindow` (lines 57-59) has no symmetric guard. The depth is a plain `int[]` cell with no clamp. The substrate's own Javadoc names this exact hazard ("a leaked open window would make later reads on the same pooled thread skip the read lock unsafely") for the over-*enter* direction but the over-*exit* direction (counter going negative) is the one that silently disables the window.

**Refutation considered**: Is the `assert` adequate because the project runs tests with `-ea`? Tests catch a balanced/unbalanced pair under `-ea`, but production runs without it, and the consequence in production (re-deadlock) is the most severe outcome the whole step is built to avoid. Could production callers never over-exit? There are no production callers yet, so the discipline is unestablished; Step 4 introduces the first ones. This is a suggestion now (no live caller) but the cost of hardening is one comparison.

**Suggestion**: Consider clamping (`if (depth[0] > 0) depth[0]--;`) or keeping the `assert` for the test signal while ensuring the production decrement can never make a later window silently fail to open. At minimum, when Step 4 wires the enter/exit, pin a regression test that an unbalanced exit does not disable a later window. Cert C2.

### BC3 [suggestion] The write-lock-held precondition has no runtime guard

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (line 3380, `enterCommitWindow`)

**Issue**: The lock-free reads are visibility-safe and race-free only while the caller holds `stateLock.writeLock()` — the Javadoc states this as a precondition the caller MUST satisfy. There is no runtime check: opening the window without the write lock would let the lock-free reads of `collections` race a concurrent registrar's write with no happens-before edge, and no assertion or exception would fire. The precondition is documented-not-asserted.

**Evidence**: The PSI method list for `ScalableRWLock` is `readLock, writeLock, addState, sharedLock, sharedUnlock, exclusiveLock, exclusiveUnlock, sharedTryLock(...), exclusiveTryLock(...)` — there is no `isWriteLockedByCurrentThread` or equivalent owner-thread query, so a same-thread assertion is not expressible against this lock without adding state. `ScalableRWLock` uses a global `StampedLock.isWriteLocked()` with no owner identity (lines 325, 412), confirming the write lock cannot answer "do I hold it?". The track Decision Log (2026-06-26T15:39Z) records that A3, the reentrant-lock alternative that would have made an owner query possible, was deliberately rejected.

**Refutation considered**: Is this a defect or an accepted constraint? It is an accepted constraint of the chosen lock type, thoroughly documented in both the field comment (diff lines 9-22) and the `enterCommitWindow` Javadoc (diff lines 44-49). The memory-model reasoning is sound *given* the precondition: the commit thread's `exclusiveLock()` -> `stampedLock.writeLock()` happens-after every prior registrar's `exclusiveUnlock()`, so reads see the latest `collections`. So this is not a bug in the code as written; it is a residual unguarded invariant worth surfacing for the Step-4 reviewer, who owns the only callers that can violate it.

**Suggestion**: No code change required for this step. When Step 4 adds the callers, ensure every `enterCommitWindow()` is lexically adjacent to and after the `stateLock.writeLock().lock()` in the same method (not threaded through a helper), so the precondition is locally auditable. Cert C3.

## Evidence base

#### C1 — `recordExists` enumeration gap

Reachability established by PSI find-usages (mcp-steroid, project `transactional-schema-b4l1mcdq`):
- `AbstractStorage#recordExists(3)` <- `DatabaseSessionEmbedded#executeExists` (DatabaseSessionEmbedded.java:1785)
- `DatabaseSessionEmbedded#executeExists(1)` <- `FrontendTransactionImpl#exists` (FrontendTransactionImpl.java:447)
- `DatabaseSessionEmbedded#exists(1)` <- `RecordAbstract#exists` (RecordAbstract.java:382)

`recordExists` body (AbstractStorage.java:4817) takes `stateLock.readLock().lock()` with no `isCommitWindowActive()` consult. `grep .exists(` over `SchemaShared.java` and `SchemaClassImpl.java` returns no hits, so the schema serialize/promote path does not call exists today — the gap is latent, scoped to Step 4's full-window enumeration. Reference-accuracy: PSI-backed, no caveat.

#### C2 — `exitCommitWindow` assert-only underflow

Survived: confirmed as a real (latent) hazard. `exitCommitWindow` (diff 68-72) decrements behind `assert depth[0] > 0`; asserts are disabled in production; `isCommitWindowActive` (diff 80-82) tests strictly `> 0`. A negative counter from an over-exit silently keeps a later legitimate window closed, re-introducing the read-lock busy-spin deadlock. No production callers yet, so severity is suggestion. Basis: diff read + Java assertion semantics; no symbol search needed.

#### C3 — documented-not-asserted precondition

Survived as an accepted constraint, not a defect. `ScalableRWLock` PSI method list carries no owner-thread query, and `StampedLock.isWriteLocked()` (ScalableRWLock.java:325, 412) has no owner identity, so a same-thread precondition assertion is not expressible against this lock. The track Decision Log (2026-06-26T15:39Z) records the reentrant-lock alternative (A3) was rejected by design. Memory-model correctness of the lock-free reads holds given the precondition (write-acquire happens-after prior write-release via the underlying StampedLock). Surfaced for the Step-4 reviewer who owns the callers. Reference-accuracy: PSI-backed method list, no caveat.

Additional non-finding verifications (no cert needed, all clean):
- `commitWindowDepth` is `ThreadLocal<int[]>` (PSI field type) — strictly per-thread, so a window open on the commit thread cannot make another thread's reads skip the read lock. The "non-write-lock-holder observes the window open" race is structurally impossible. Clean.
- Deadlock motivation confirmed: `ScalableRWLock.sharedLock()` (ScalableRWLock.java:320-338) loops `while (stampedLock.isWriteLocked()) Thread.yield()` when any thread (including self) holds the write lock, so a write-lock holder calling `readLock().lock()` spins forever. The lock-free skip is necessary, not a premature optimization.
- `enterCommitWindow` / `exitCommitWindow` have no production callers (PSI: only the three test methods and Javadoc `{@link}` references) — additive substrate, consistent with the Step 1 episode and Step 4's wiring scope. Clean.
- Normal callers keep the read lock: `getPhysicalCollectionNameById` and `readRecordInternal` only skip the read lock when `isCommitWindowActive()`, which is false for every non-commit-window thread. The pure-data fast path is unchanged. Clean.
- Tests pass: `AbstractStorageCommitPrimitivesTest` runs 7/7, 0 failures, 11.06 s, no hang on the timed lock-free reads (`./mvnw -pl core test`).
