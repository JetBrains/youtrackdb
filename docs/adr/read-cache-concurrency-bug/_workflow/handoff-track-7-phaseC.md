# Handoff: Phase C — Track 7 (Recovery-time orphan-truncation pass)

**Paused:** 2026-05-19
**Phase:** C (Track-level code review + Track Completion, post-review-loop, pre-approval)
**Context level at pause:** warning (32%)
**Branch:** read-cache-concurrency-bug
**HEAD:** `a6d3fe770c` "Apply plan corrections from Track 7 review"
**Unpushed:** 0 commits

## Durable artifacts on disk

- `docs/adr/read-cache-concurrency-bug/_workflow/tracks/track-7.md` — Progress section marks `Track-level code review (1/3 iterations, PASS)`. Description section's "Independent of Track 5" bullet rewritten to acknowledge dependence on Track 5's `StorageComponent.physicalSize`.
- `docs/adr/read-cache-concurrency-bug/_workflow/implementation-plan.md` — Track 6's checklist entry extended with three plan-correction bullets (ChecksumMode matrix, multi-value null-tree IT, executable IR-wiring test). Track 7's plan entry NOT yet collapsed to its intro paragraph + track episode (that lands on Approve).
- Commit `fdbe455b00` "Review fix: tighten orphan-truncation sentinels + LFRC leak fix" — applies all 8 in-scope iteration-1 findings (M1-M8).
- Commit `fc20f0ef97` "Track 7 Phase C: record iteration 1 complete" — Progress update.
- Commit `a6d3fe770c` "Apply plan corrections from Track 7 review" — plan corrections (Track 6 scope extension + track-7.md stale-claim fix).

## Pending decision

User entered review mode on the Track 7 Phase C track-completion approval panel (Approve / Review mode / ESCALATE). The review loop closed at iteration 1 with all 5 gate-check sub-agents returning PASS (BC / CS / TB / TS / TY). No STILL OPEN / REGRESSION / new findings. The plan corrections have already landed (commit `a6d3fe770c`). The remaining work is to accept the compiled track episode (verbatim text below) and run the gate's post-Approve writes (write episode + collapse description + mark `[x]` in plan file + commit + push), OR to apply additional FIX_FINDING / QUESTION / ESCALATE items via review mode before the final Approve.

## Verbatim re-present text

### Phase C summary

- **Review fan-out**: 9 dimensional reviewers (CQ / BC / TB / TC / CS / TY / PF / TX / TS) returned 68 raw findings against the ~5700-line cumulative diff.
- **Synthesis**: 8 in-scope merged findings (1 blocker, 7 should-fix); rest deferred or routed to plan corrections.
- **Iteration 1**: implementer commit `fdbe455b00` applied all 8 fixes — surfaced one cross-engine compile-fix (`DirectMemoryOnlyDiskCache.shrinkFile` 3-arg needed `throws IOException`). Tests pass, coverage gate 92.3%/85.7%.
- **Gate-check**: 5/5 PASS at iteration 1 (BC / CS / TB / TS / TY) — no STILL OPEN / REGRESSION / new findings.
- **Plan corrections**: commit `a6d3fe770c` extends Track 6 scope with 3 deferred IT-coverage items (StoreAndThrow matrix, multi-value null-tree IT, executable IR-wiring test); also fixed a stale claim in `track-7.md` about Track 5 helper usage.

### Compiled track episode (will land on plan-file approval)

> **Track episode:**
> Landed the recovery-time orphan-truncation pass that establishes Invariant I6 (`logical == physical` after `open()` and `postProcessIncrementalRestore` return). The layered shrink architecture pairs a new `WriteCache.shrinkFile(fileId, targetBytes)` SPI (WOWCache impl + range-scoped `removeCachedPagesAtLeast` + `AsyncFile.shrink` in-place semantics fix at Step 1) with `LockFreeReadCache.shrinkFile(fileId, targetBytes, writeCache)` orchestrator (range-scoped `clearFileRange` + new `ConcurrentLongIntHashMap.removeByFileIdAtLeast`). Per-component `verifyAndTruncateOrphans` helpers on BTree / SharedLinkBagBTree / CollectionPositionMapV2 / PaginatedCollectionV2 read EP page logical state via Track 5's `StorageComponent.physicalSize(...PhysicalReadIntent.RECOVERY_REBUILD)` (new enum constant) and dispatch the layered shrink under `stateLock.writeLock`. The new `LinkCollectionsBTreeManagerShared.verifyAndTruncateAllOrphans` iteration delegate covers SLBB's lazy-load population shape. The private `AbstractStorage.truncateOrphansAfterRecovery()` orchestrator wires from `open()` after `recoverIfNeeded()` + `openCollections` + `openIndexes` + `linkCollectionsBTreeManager::load`, and from `DiskStorage.postProcessIncrementalRestore` after `flushAllData()` — both entry points run post-flush to avoid the flush-after-truncate hazard. Integration coverage via `TruncateOrphansAfterRecoveryIT` uses deterministic orphan fabrication (`RandomAccessFile` + `MAGIC_NUMBER_WITHOUT_CHECKSUM` + LSN `(-1,-1)`) under `ChecksumMode.Off`.
>
> **Phase C track-level review.** Ran the 9-reviewer fan-out for one iteration against the cumulative diff (~5700 lines, 37 files). 68 raw findings deduped to 8 in-scope merged findings — one blocker (source-text wiring test gave false confidence via loose substring matching) plus seven should-fix (clearFileRange pinned-entry leak, AsyncFile.shrink misleading no-op-guard comment, DirectMemoryOnlyDiskCache.shrinkFile silently ignoring the writeCache argument, WOWCache no-op shrink not preserving dirty entries, LFRC ShrinkFile order assertion loose enough to pass under swapped production order, corruption-skip WARN log not asserted, CPMV2/PCV2 corruption-guard Javadoc misframing the partial-flush-orphan case). Iteration 1 implementer commit `fdbe455b00` applied all eight fixes and surfaced one cross-engine compile-fix (the 3-arg `DirectMemoryOnlyDiskCache.shrinkFile` override gained `throws IOException` after forwarding to the writeCache interface). Gate-check fan-out across BC / CS / TB / TS / TY: 5/5 PASS at iteration 1 with no STILL OPEN / REGRESSION / new findings.
>
> **Cross-track impact.**
>
> - **Track 6 (Integration regression test).** Plan correction commit `a6d3fe770c` extends Track 6's scope with three deferred Track 7 IT-coverage items: (1) `ChecksumMode.StoreAndThrow` + `Off` matrix across CS1 partial-flush-orphan scenarios (pins the "orphan bodies are never read during truncate" claim under the production CI default); (2) multi-value engine `.nbt` null-tree orphan-truncation IT alongside the four EP-equipped components (mock-only coverage today); (3) executable IR-wiring test replacing the source-text sentinel — natural fit alongside the StorageBackupMTStateTest resurrection already in Track 6 scope. Track 6's existing CS1 partial-flush-orphan bullet already absorbs the multi-component (BTree/SLBB/CPMV2/PCV2) coverage.
> - **Track 5 — no changes.** Track 7 confirmed dependence on Track 5's `StorageComponent.physicalSize` + `PhysicalReadIntent` enum, validating the gated-helper design. Track-7.md doc fix on the stale "does NOT call Track 5 helpers" claim landed in the same plan-correction commit.
>
> **Known follow-ups (not on plan, surfaced for future cache/recovery hygiene passes):**
> - LFRC short-circuit perf (PF1/BC4): `LockFreeReadCache.shrinkFile` always runs `clearFileRange` even when `WriteCache.shrinkFile` was a clean-shutdown pre-flight no-op — bounded but real at scale (~77ms on 300-component databases). Optional cleanup; not on Track 7's critical path.
> - Defensive `assert` statements (TY6/TY7/TY8): orchestrator invariants (`atomicOperation != null`, `linkCollectionsBTreeManager != null`), WOWCache.shrinkFile page-alignment post-guard, AsyncFile.shrink post-truncate file-length invariant — zero-cost in production builds, catches refactor regressions in dev runs.
> - MT defense-in-depth pins (TX1/TX2): WOWCache.shrinkFile vs periodic-flush FIFO discipline; LFRC.shrinkFile for fileId A vs concurrent `loadForRead` for fileId B (different fileId, expected safe). Today's coordination via `stateLock.writeLock` + `commitExecutor` FIFO is sound — pins guard against future relaxation.
> - FSM-rebuild ordering carve-out (CS3): PCV2.open's FSM-rebuild scan reads `[0, physicalSize)` and may touch orphans before the orchestrator runs. Already noted in `PaginatedCollectionV2.java:396-401` as a known gap; FSM is EP-less and out of Track 7 scope per Non-Goals. Worth a design.md mention in a future doc pass.
> - Various style / naming / dead-code suggestions (CQ / TS / TC / TX / PF dimensions): IT scaffolding deduplication, per-component test fixture extraction, FQN-in-source noise, stale line-number references in Javadoc, license-header normalisation, NULL_BUCKET_FILE_ID dead constant, test naming consistency. Surfaced for a future cache-layer test-hardening pass.
>
> **What changed from the plan.** Nothing structural. Plan corrections affect Track 6 scope only (deferred IT coverage matrix extensions). No design decisions surfaced; no ESCALATE.
>
> **Step file:** `tracks/track-7.md` (5 steps, 0 failed; Phase C iter-1 review fix `fdbe455b00`)

### Git log of Track 7 commits (14 commits between base `7d0c88567e` and HEAD `a6d3fe770c`)

```
a6d3fe770c Apply plan corrections from Track 7 review
fc20f0ef97 Track 7 Phase C: record iteration 1 complete
fdbe455b00 Review fix: tighten orphan-truncation sentinels + LFRC leak fix
72c3f89617 Record episode for Step 5 — truncateOrphansAfterRecovery orchestrator
f2f35391a0 Review fix: M1-M7 + m1-m4 for orphan-truncation orchestrator wiring
fe6b728f2e Add recovery-time orphan-truncation orchestrator and wire it
625d3f0d97 Record episode for Step 4 — SLBB manager iteration delegate
b7d0915770 Add verifyAndTruncateAllOrphans delegate to SLBB manager
d437fafa9e Record episode for Step 3 — per-component verifyAndTruncateOrphans
9a2baf5074 Add verifyAndTruncateOrphans to four EP-equipped components + engines
3709bdd3d1 Record episode for Step 2 — layered shrinkFile primitive
70274af6e2 Review fix: promote shrinkFile asserts to production guards
60cf566b16 Review fix: harden shrinkFile coverage + defensive asserts
baa0c5a069 Add layered shrinkFile primitive across cache layers
7eda3e1584 Record episode for Step 1 — AsyncFile.shrink in-place semantics fix
743572c5fb Fix AsyncFile.shrink in-place size semantics
```

### Unresolved findings

None blocker-grade. All 8 in-scope findings cleared at gate-check iter 1. The "Known follow-ups" bullets above are deferred to future tracks / hygiene passes, not Track 7's responsibility.

## Resume notes

- **Do NOT redo:**
  - The 9-dimension reviewer fan-out (results synthesised; the 8 in-scope merged findings landed in commit `fdbe455b00`).
  - The 5 gate-check sub-agents (all returned PASS; iteration 1 closes the review loop).
  - Plan corrections (already committed in `a6d3fe770c`).
  - The track-7.md stale-claim fix (already in commit `a6d3fe770c`).
  - The track episode compilation (full verbatim text above — paste it back to the user; do not re-derive from the step file episodes under high context pressure).

- **On user Approve:** run `track-code-review.md` § Track Completion step 4 — write the track episode + collapse the description (keep intro paragraph only) + mark `[x]` in the plan file; then step 5 (commit `Mark Track 7 complete` + push); then step 6 (self-improvement reflection); session ends.

- **On Review mode (user-requested):** the user already picked Review mode at the original approval panel. Resume by inviting input per `review-mode.md` § Flow step 1 — open with one brief sentence ("Review mode — what did you notice?"), then accumulate observations into the buffer until the user signals completion. On Apply, side-effecting items execute per § Flow step 5 (FIX_FINDING items spawn a fresh implementer with `level=track, mode=FIX_REVIEW_FINDINGS`; QUESTION items are answered inline; ESCALATE → inline replanning).

- **On ESCALATE:** route to `inline-replanning.md`.

## Conversation context at pause

- The user picked "Review mode" on the AskUserQuestion panel that surfaced the Track 7 completion approval. The session continued via a manual `/exit` + restart sequence; the user explicitly asked to "make a handoff and start to work in review mode in continuation of this session." This handoff captures the state so a future-session crash does not lose the review-mode entry point.
- Review-mode accumulation buffer: **empty** at the time of handoff write — the user has not yet dropped any observations after entering review mode.
- All staged temp files for Track 7 still exist at `/tmp/claude-code-track-7-diff-220888.patch` and `/tmp/claude-code-track-7-files-220888.txt` (PID 220888), regenerated after the `Review fix:` commit. On resume in a new session those temp files won't exist (different PID), and the resume protocol per `track-code-review.md` § Track Completion step 1 will re-read `git diff {base_commit}..HEAD` against current HEAD before re-compiling. The base commit is `7d0c88567e43628d8c86d12d14ac0991a8f28aef`.
