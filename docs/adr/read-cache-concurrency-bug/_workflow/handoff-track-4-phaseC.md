# Handoff: Phase C — Track 4 CS1 disposition

**Paused:** 2026-05-13
**Phase:** C (track-completion approval — final user-approval gate)
**Context level at pause:** critical
**Branch:** read-cache-concurrency-bug
**HEAD:** `f60ccec4a7` "Workflow update: add PAUSED marker + handoff pointer to Track 4 Progress section"
**Unpushed:** 0

## Durable artifacts on disk

- 8 of 8 Track 4 steps complete (Step 5 [!] → 5a + 5b split per user-selected Option A).
- 3 of 3 Phase C implementer iterations applied 16 in-scope findings:
  - `bdb7da6217` — Review fix iter-1 (11 findings: workflow-label scrub + IHM lambda rename + dead-code removal + test-prose sweep + Javadoc rewrites + cross-ref comments + boundary-throw pin + WOWCacheTestIT pageIndex pins + awaitTermination).
  - `f2442c38be` — iter-1 progress update.
  - `ea6ae0aff0` — Review fix iter-2 (F12 / F13 / F14 + TC-M6: deleted stub-returns-stub test, rewrote vacuous verify with `verifyNoInteractions` on a wired mock, added reflection probe on `CachePointer.referrersCount`, 10th WOWCacheTestIT pin).
  - `4696499968` — iter-2 progress update.
  - `0ab1b2aa7a` — Review fix iter-3 (user-surfaced U1: IHM:248 workflow-label leak from PR #772, cleaned per Ephemeral Identifier Rule).
  - `fe5e1a088c` — iter-3 progress update.
- All 8 iter-3 gate-check dimensions PASS. One advisory residual: **TX-6** — F14 probe comment-prose claims `IllegalStateException` but `-ea` fires `AssertionError` first (test catches either via `Throwable`; documentation-imprecision only).
- Plan corrections committed:
  - `9dff2ac2e3` — Track 6 scope expansion (~2 → ~5-7 steps), absorbing CS1 + HLL-spill crash-then-second-spill recovery + StorageBackupMTStateTest @Ignore resurrection + I4 per-component MT pins. Non-Goals extended with 8-item unit-level test-hardening backlog.
  - `9761187d0f` — Track 5 scope expansion (~3-4 → ~4-5 steps), absorbing the `AtomicOperation.loadOrAddPageForWrite → allocatePageForWrite` rename (109 textual occurrences across 18 files; must route through IDE Rename via `mcp-steroid://ide/change-signature`).
- Step file: `tracks/track-4.md` Progress section shows `3/3 iterations … awaiting final user approval before marking [x]`. Plan-file Track 4 entry is still `[ ]`. PAUSED marker present.

## Pending decision

User halted at the **final track-completion approval prompt** to think through the CS1 partial-flush-orphan disposition in a fresh session. The investigation revealed the failure mode is **more severe than the orchestrator's earlier "bounded leak" framing** — it is an **availability impact** (storage component becomes unwriteable until manual recovery), not just a silent disk leak.

Three options were surfaced to the user:

1. **Approve completion as-is.** Track 6 plan correction already absorbs CS1 as an integration test target. The CPMV2 comments are technically accurate. Track 4 completes; Track 6 owns verification + disposition.

2. **Tighten the CPMV2 comments first, then approve.** Update the two CPMV2 comment blocks (`CollectionPositionMapV2.java:218-224` and `:245-249`) to name the actual availability impact ("storage component becomes unwriteable until manual recovery") rather than the softer "hard `IllegalStateException` rather than silent reuse" framing. Apply via a tiny fresh implementer spawn (would be iter-4 — **outside the 3-iteration budget; user permission needed**), then approve. Same Track 6 disposition.

3. **Block completion — design escalation on CS1.** Availability impact warrants a design decision now. Sub-options to weigh:
   - (a) Add `reuseOrphanPageForWrite` SPI in Track 4 or a new track — an AOBT-level fallback method that allows below-floor pageIndex when the caller's component lock plus entry-point bookkeeping guarantee the orphan is genuinely abandoned.
   - (b) Add a recovery-time `fileTruncate(entryPoint.fileSize * pageSize)` pass at storage open. Per-component cost: one read of `entryPoint.fileSize` + one `AsyncFile.shrink` / `fileChannel.truncate` if `getFilledUpTo > fileSize`. Bounds the exposure window to "storage open" rather than "every TX after crash".
   - (c) Accept the availability impact with explicit user acknowledgment. Document the trade-off in `adr.md` (Phase 4 final artifact).

User halted before answering. **Resume action: re-present these three options at the start of the next session.**

## Verbatim re-present text

### The deepened CS1 analysis (paste verbatim to the user on resume)

The partial-flush-orphan scenario at `CollectionPositionMapV2.allocate` **is mechanically reachable**, and the comments at `CollectionPositionMapV2.java:218-224` and `:245-249` accurately describe it (though the prose is slightly euphemistic about the impact).

**Mechanical chain** (verified by code reading the previous session):

1. `CPMV2.allocate` inside a TX calls `loadOrAddPageForWrite(fileId, N+1)`.
2. `AOBT.commitChanges` buffers WAL records in-memory (NO synchronous flush at TX commit; `AtomicOperationsManager.endAtomicOperation:244` calls `operation.commitChanges` but never calls `writeAheadLog.flush`).
3. Inside `commitChanges`'s per-page apply loop: `readCache.loadOrAddForWrite` → `WOWCache.loadOrAdd` extend branch:
   - `AsyncFile.allocateSpace(pageSize)` is just `this.size.getAndAdd(size)` — in-memory only (`AsyncFile.java:303-305`).
   - Submits `EnsurePageIsValidInFileTask` to `commitExecutor` (single-threaded `wowCacheFlushExecutor`).
4. Executor runs `writeValidPageInFile` (`WOWCache.java:3780+`):
   - **No `doubleWriteLog.write` call** (DWL only covers `flushPages` at line 3864, not magic-stamp writes).
   - **No WAL-flush coordination** — compare `flushPages:3835-3841` which waits `writeAheadLog.getFlushedLsn().compareTo(fullLogLSN) >= 0` before writing data pages. `writeValidPageInFile` has no such gate.
   - Calls `AsyncFile.write` → `fileChannel.write` (AsyncIO). NO `fileChannel.force(true)` fsync.
5. WAL background flusher: `RecordsWriter` scheduled via `scheduleWithFixedDelay(commitDelay, commitDelay, …)` on the same `commitExecutor` (`CASDiskWriteAheadLog.java:311-313`). The single-threaded FIFO executor runs ad-hoc `EnsurePageIsValid` submissions **as soon as the executor is idle** — they typically run before the next scheduled `RecordsWriter` invocation.
6. JVM crash window: between `commitChanges` return and the next `RecordsWriter` run (up to `commitDelay` ms). WAL records sit in the JVM's in-memory buffer; data file's magic-stamp may already be in OS pagecache.
7. Crash. OS may flush data file before WAL records (different files, no fsync barriers, no pagecache write-order guarantees).
8. On restart: WAL replay rolls back the incomplete TX (no `AtomicUnitEnd`). `AsyncFile.initSize` (`AsyncFile.java:92-144`) reads `fileChannel.size()` and sets `size = currentSize` from the physical file — **orphan included**.
9. `writeCache.getFilledUpTo(fileId)` returns orphan-inclusive size.
10. Next TX targeting `pageIndex = entryPoint.fileSize + 1 < getFilledUpTo` → AOBT allocator-only check at `AtomicOperationBinaryTracking.java:559-573` throws `IllegalStateException("loadOrAddPageForWrite is allocation-only; pageIndex N+1 is below allocationFloor N+2 ...")`.

**No orphan-truncation pass exists.** `AbstractStorage.flushAllData:4242` flushes/cuts WAL but never truncates data files. `restoreFromWAL:5176` doesn't either. So **the storage component becomes unwriteable until manual recovery** (DB repair / `fileTruncate` / restore-from-backup).

**Pre-Track-4 vs Post-Track-4:**
- Pre-Track-4: silent reuse — orphan reused with new content; replay correctness subtly compromised (LSN(-1,-1) magic stamp overwritten without WAL evidence of the prior orphan-creation TX).
- Post-Track-4: hard `IllegalStateException` — availability impact, but no hidden data risk.

**Exposure window:** between fuzzy checkpoints (default ~30s-5min via `WOWCache.flushTillSegment` / fuzzy checkpoint path at `WOWCache.java:1083-1111` which flushes WAL + fsyncs data + cuts WAL).

**Probability assessment:** low but nonzero. Higher under JVM-only crashes (OOM kill, `kill -9`) where OS continues and pagecache writeback is asynchronous; lower under simultaneous JVM+OS crashes where ordering depends on pagecache writeback policy at the failure instant.

### The compiled Track 4 episode (paste verbatim at collapse time if Option 1 or 2 is chosen)

> Track 4 delivered the write-side API collapse end-to-end: `addPage` / `allocateNewPage` deleted, 19 production call sites migrated to `op.loadOrAddPageForWrite(fileId, knownIndex)` on Track 1's `loadOrAdd` primitive, the three replay-loop reconciliation loops (`commitChanges`, `restoreAtomicUnit`, `restoreFromIncrementalBackup`) collapsed to single `loadOrAddForWrite` calls, the `internalFilledUpTo` prediction wrapper deleted, and BTree `doAssertFreePages` migrated to pure logical sizing.
>
> The original ~6-7-step decomposition expanded to 8 steps after Step 5 failed (in-memory commit-time replay NPE). User-selected Option A split into Step 5a (in-memory eager-install) + Step 5b (replay-loop collapse + `assert WOWCache.loadOrAdd != null` totality pin). The split absorbed 13 deferred items into Track 2-style and Track 6 follow-ups.
>
> **Phase C track-level review** ran 9 dimensional reviewers (4 baseline + 5 conditional; security skipped — no public-API touch). 0 blockers, 1 concerning crash-safety item (CS1), 16 should-fix / minor items. **3 review-fix iterations applied 16 in-scope findings:**
>
> - **Iter-1** (`bdb7da6217`): workflow-label scrub per the Ephemeral Identifier Rule (9 files), AOBT "is total" comment correction at BTree/SLBB to name the allocator-only contract, IHM `op2 → lockedOp` rename, dead `DUMMY` field + import removal, `committedFilledUpTo → allocationFloor` sweep in test prose, contract-first Javadoc at `CollectionPositionMapV2Test` orphan-reuse tests, cross-reference comments at the 3 disk-only asymmetric assert sites pointing to `AOBT.commitChanges`, `filledUpToOnTruncatedFileReturnsZero` honest-scope rename, WAL-replay-single-threaded Javadoc at the two `RestoreAtomicUnit` gap-fill tests, `allocationFloor 10` substring pin in boundary-throw test, 9 `WOWCacheTestIT` `assertEquals(i, pointer.getPageIndex())` re-additions, `pool.awaitTermination` in concurrent orphan-reuse test.
> - **Iter-2** (`ea6ae0aff0`): deleted stub-returns-stub `commitChangesFindsEagerlyInstalledPageOnInMemoryEngine`, rewrote `diskEngineWithNonFreshFileTakesStubBranch` with `verifyNoInteractions(readCache)` on the wired-in mock + rename + Javadoc, added a **reflection probe on `CachePointer.referrersCount`** + same-pointer-identity reload to the concurrent orphan-reuse test (implementer caught the orchestrator's `deleteFile + addFile` proposal as structurally invalid and chose a strictly stronger probe), added 10th `WOWCacheTestIT.testDataUpdate` pin (implementer noted the pre-migration code at this site had no post-check, contradicting TC-M6's original framing; pin landed as regression hardening regardless).
> - **Iter-3** (`0ab1b2aa7a`): user-surfaced IHM:248 workflow-label cleanup (`// for deferred wiring in Steps 5-6` → `// wired post-construction by the storage layer`). The leaked identifier was from PR #772 (YTDB-584), not this branch; the iter-1 scoping was conservative (this-branch only), and the user extended scope at track completion to honor the Ephemeral Identifier Rule for any leak in a file Track 4 touched.
>
> Iter-2 gate-check across 8 dimensions returned PASS. **TX-6** is the only advisory residual: the F14 probe's comment prose claims `IllegalStateException` would fire on double-decrement, but `-ea` fires `AssertionError` at `CachePointer.java:206` first. The test catches either via `Throwable`; documentation-imprecision only.
>
> **Cross-track impact (durable in plan corrections):**
> - **Track 5** scope expanded from ~3-4 steps to ~4-5 steps to absorb the `loadOrAddPageForWrite → allocatePageForWrite` rename (109 textual occurrences across 18 files; must route through IDE Rename refactoring engine per project rules). The current name is misleading — Track 4 Step 2 narrowed the AOBT-layer contract to allocator-only on the disk engine while keeping the historical "load or add" name; the AOBT Javadoc itself acknowledges this: *"Despite the historical name, this method does NOT load existing pages on the disk engine."*
> - **Track 6** scope expanded from ~2 steps to ~5-7 steps to absorb 4 integration-shaped deferrals: CS1 partial-flush-orphan recovery hazard verification (the deepened analysis recognises this as an availability impact, not a bounded leak — storage component becomes unwriteable until manual recovery), HLL-spill crash-then-second-spill recovery test, `StorageBackupMTStateTest` `@Ignore` resurrection, I4 per-component MT pins at the four allocator sites + IHM `flushSnapshotToPage` vs `writeSnapshotToPage` lock contract + strengthen `inMemoryEagerInstallToleratesConcurrentOrphanReuse` contention window.
> - **Non-Goals** absorbed an 8-item unit-level test-hardening backlog as Track 2-style follow-ups.
>
> **Step file:** `tracks/track-4.md` (8 steps, 0 failed at completion; Step 5 [!] split into Step 5a + Step 5b per user-selected Option A).

## Resume notes

- **Do NOT redo** (per `mid-phase-handoff.md` §Phase-specific "do NOT redo" defaults, Phase C row):
  - The iteration count (3/3) — already on disk in the Progress section.
  - The 9-reviewer fan-out at iter-1 — committed at `bdb7da6217`.
  - The 8-dimension gate-check at iter-2 — PASSed and on disk.
  - The 3 implementer iterations and their commits — all pushed.
  - The Track 5 and Track 6 plan corrections — `9761187d0f` and `9dff2ac2e3`, both committed.
  - The compiled Track 4 episode — drafted in the previous session, preserved verbatim above.
- **Open items at session pause** (these ARE the pending decision — do NOT mark resolved on resume):
  - CS1 disposition (the 3 options above).
  - TX-6 advisory: `IllegalStateException` vs `AssertionError` comment-prose imprecision. Currently scheduled for the unit-level test-hardening backlog (Non-Goals). User may want to fold this into Option 2's comment-tightening pass.

- **On user approval (Option 1):**
  - Write the Track 4 episode into the plan file Track 4 entry — paste the verbatim block above into the collapse step per `track-code-review.md` §Track Completion step 4.
  - Mark Track 4 `[x]` in the plan file checklist.
  - Commit + push: `Mark Track 4 complete — write-side API collapse and Phase C disposition`.
  - Run self-improvement reflection per `self-improvement-reflection.md`.
  - End the session.

- **On user choosing Option 2 (tighten comments first):**
  - Spawn a small implementer (counts as iter-4 — user has explicitly authorised stepping outside the 3-iter budget). Scope: update the two CPMV2 comment blocks (`CollectionPositionMapV2.java:218-224` and `:245-249`) to name the actual availability impact.
  - Re-stage the diff for any gate-check.
  - Spot-check Spotless on the touched file.
  - Then follow the Option 1 path above.

- **On user choosing Option 3 (escalate):**
  - Enter inline replanning per `inline-replanning.md`.
  - Discuss the 3a / 3b / 3c sub-options with the user.
  - Potentially add a new Track or expand Track 4. Plan + design + step-file edits go through the regular Phase 0 / 1 channels.

## Files of interest for next session

- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/collection/v2/CollectionPositionMapV2.java` lines 205-267 (the `allocate` method + the two comment blocks in question).
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/cache/local/WOWCache.java` lines 1566-1652 (the `loadOrAdd` extend + gap-fill branches), 3780-3826 (`writeValidPageInFile`), 1083-1119 (fuzzy checkpoint).
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/fs/AsyncFile.java` lines 92-232 (init + `allocateSpace` + write).
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/atomicoperations/AtomicOperationBinaryTracking.java` lines 559-573 (allocator-only check), 934-1170 (`commitChanges`).
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/wal/cas/CASDiskWriteAheadLog.java` lines 311-313 (scheduled `RecordsWriter`), 943-1014 (`log` method).
- `docs/adr/read-cache-concurrency-bug/_workflow/implementation-plan.md` (Track 5 + Track 6 entries already amended; Non-Goals already extended).
- `docs/adr/read-cache-concurrency-bug/_workflow/tracks/track-5.md` (rename-absorption section added).
- `docs/adr/read-cache-concurrency-bug/_workflow/tracks/track-6.md` (Phase C deferrals absorbed section added).
- `docs/adr/read-cache-concurrency-bug/_workflow/tracks/track-4.md` (Progress section shows `3/3 iterations … awaiting final user approval`).
