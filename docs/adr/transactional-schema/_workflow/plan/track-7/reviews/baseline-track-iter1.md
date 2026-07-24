<!-- MANIFEST
findings: 6   severities: {blocker: 0, should-fix: 2, suggestion: 4}
scope: "CUMULATIVE Track 7 review, code-baseline + completeness perspective — git diff e2605c8ba3..5808d9b11e (24 commits), HEAD 5808d9b11e"
flags: [READ_ONLY, NO_MAVEN_RUN, NO_PRODUCT_CODE_MODIFIED, VERDICTS_INDEPENDENT_OF_STEP_REVIEWS]
-->

# Cumulative track review — Track 7, baseline/completeness perspective (iter 1)

**Artifact:** the entire Track 7 diff `e2605c8ba3..5808d9b11e` on branch `transactional-schema`
(24 commits; 46 files, +12850/−324; product surface: 17 files under `core/src/main`, +1759/−246).
**Method:** read-only; no Maven run. Slice reviews with gated fixes exist
(`track-7/reviews/*-step{1,3,4,5}-iter1.md`, `gate-*-fixes-iter1.md`); their VERIFIED verdicts are
taken as settled and are NOT re-litigated here — this review checks track-level completeness
(pin discharge, test totality, API surface, doc sync, commit hygiene, debris) against the code at
HEAD.

---

## 1. Charter (1) — SPEC COMPLETENESS. VERDICT: ALL PINS DISCHARGED (null finding); two recording nits (§6 CQ12, §6 BG11-adjacent note)

Premises and evidence, pin by pin. The authoritative design of record is
`_workflow/track-7-design-drafts.md` (base drafts + Rulings 2026-07-20 + Amendments pass-14 +
Amendments round 2 + pass-16 pins), per the plan's own supersession note (track-7.md:186-198).

### 1.1 The five steps

| Step | Commit | Present at HEAD | Evidence |
|---|---|---|---|
| 1. stale-seed / undo guard / CS2 | cb2d4d3b79 (+ review-fixes 1063e1d987, 7d2369cda0) | yes | `SchemaShared.copyForTx` seeds inside `computeWithFreshCommittedReads` (SchemaShared.java:282-314); `undoReconciledCollections` slot-reuse guard + `undoSchemaCarryRegistryPublication` + rollback-gated endTxCommit catch (AbstractStorage.java:6558/6573 hook reads); acceptance test `twoConcurrentSchemaTransactionsSerializeWithoutAbort` (MetadataWriteMutexTest.java:106) |
| 2. reload guard | fafac7e8b3 | yes | `runReloadingCommittedSchema` (DatabaseSessionEmbedded.java:3694-3703), `SchemaShared.reload` wraps `fromStream` (SchemaShared.java:736), seam branch in `IndexManagerEmbedded.recordMembershipChangeIntoTxLocalView`; regression `schemaReloadWithIndexedSuperclassDoesNotEngageMutex` (MetadataWriteMutexTest.java:215) |
| 3. Draft A handshake + Q-A2 skip | 11bf0eda26 (+ f7009df7a7, gated 9/9 VERIFIED) | yes | §1.2 below |
| 4. `exclusiveLockWithAbort` | 449d1745c0 (+ 5811042e95, gated VERIFIED) | yes | ScalableRWLock.java:622-765 |
| 5. Draft B freezer gate | 736cab68ec (+ b54384e08a, 3dd408a439; gate-step5-fixes-iter1: 9/9 VERIFIED) | yes | §1.3 below |

### 1.2 Draft A pins (rulings Q-A1..A5; pass-14 CN11–CN18/CS11/CS12; round-2 CN20+CS17/CN22/CS16/CN24; V2)

- **Q-A1** (release finally, funneled): release pass hoisted to `internalClose`'s OUTER finally
  (DatabaseSessionEmbedded.java:3430, with the CN12 rationale comment naming the pre-rollback throw
  points and the early return); documented in the mutex javadoc as required
  (MetadataWriteMutex.java:52-58). **Discharged.**
- **Q-A2 skip protocol** with the round-2 whitelist: `realClose` marks FIRST, re-validates via
  `hasInFlightForeignCommit()` (volatile `currentTx` → volatile `status`/`storageTxThreadId`,
  FrontendTransactionImpl.java:84-91/162-167/183-193), and on skip does ONLY mark+log — the comment
  enumerates every forbidden action including NO session-count decrement (CN20+CS17) and NO
  one-shot-guard consumption (DatabaseSessionEmbeddedPooled.java:69-108). The "remove pool-private
  activation" clause is documented as vacuous at the seam ("Nothing pool-thread-private was planted
  before this check"), matching the Step 3 episode's recorded deviation (5). **Discharged.**
- **Q-A3** all four pins: unbounded timed re-wait, 10s constant non-configurable
  (`ENGAGE_WARN_INTERVAL_SECONDS`, MetadataWriteMutex.java:65-70), holder-naming WARN, interruptible
  with flag restore + `DatabaseException` naming the holder, loop-top self-check of
  `teardownIntent`/status via the lock-free `getStatus()` (MetadataWriteMutex.java:143-176).
  **Discharged.**
- **Q-A4 + CN16 + CN24**: `teardownIntent` set in `internalClose` (both arms — the method is shared
  by recycle and full teardown), strictly AFTER the status guard and the teardown claim, with the
  no-mark-on-no-op-close rationale in the comment (DatabaseSessionEmbedded.java:3366-3388); cleared
  on `reuse()` as the second belt with the "ONLY belt for recycled borrows" correction from the
  Step 3 review-fix (DatabaseSessionEmbeddedPooled.java:48-58). **Discharged.**
- **Q-A5 / FM-A7**: same-session re-engage throws `IllegalStateException` naming ordinal, thread,
  elapsed, and "never released" (MetadataWriteMutex.java:122-131); pinned by
  `strandedSameSessionReengageThrowsLoudly` (type + both message fragments,
  MetadataWriteMutexTest.java:1010-1031). **Discharged.**
- **CN13**: `status` and `storageTxThreadId` volatile with the HB rationale in comments
  (FrontendTransactionImpl.java:84-91, 162-167). **Discharged.**
- **CN17**: all three release sites (owner tx-close finally — FrontendTransactionImpl.closeInternal
  finally; teardown outer-finally pass; engage Dekker self-release) funnel through the single
  `getAndSet(engagedOrdinal, 0)` claim (`releaseMetadataWriteMutexForTx`,
  DatabaseSessionEmbedded.java:3908-3929); the `(session, ordinal)` CAS second belt intact
  (MetadataWriteMutex.releaseFor:199-227). **Discharged.**
- **CN18 / FM-A4c**: accepted risk recorded (PR description item 3); the optional "atomic one-shot
  session claim" was actually implemented (`teardownClaim`,
  DatabaseSessionEmbedded.java:3356/3375-3384) — exceeds the pin. **Discharged.**
- **CN22**: pool side writes mark first then re-validates, falling through to full teardown when the
  commit finished; owner publishes volatile tx status first, reads mark in
  `completeDeferredTeardownAfterTxClose`; both sides carry the handshake comment naming the three
  interleavings (DatabaseSessionEmbeddedPooled.java:69-77;
  DatabaseSessionEmbedded.java:3968-4006). The accepted second defeater (pre-status-write tx-close
  throw) is on record in the plan episode (track-7.md §Step 3 review-fix "Accepted residual"),
  explicitly superseding the design's "only JVM death" wording. **Discharged.**
- **CS16**: completer runs in `close()`'s tail finally strictly after `closeInternal` (mutex
  released first; FrontendTransactionImpl.java:1028-1040), throw-isolated inside the session method
  (`catch (Throwable)` log-and-continue, DatabaseSessionEmbedded.java:3992-4005); pinned by
  `throwingCloseListenerNeverMasksCommitOutcome`. **Discharged.**
- **V2 ordering**: explicit "V2 mandatory ordering" comment at the ordinal-store-before-mark-read
  site (DatabaseSessionEmbedded.java:3875-3888). **Discharged.**

### 1.3 Draft B pins (rulings Q-B1..B5; CN10→CN19→CN25 checkpoint 2; CS10→CS15→CS19 clear; CN23/CS18; V1/V8; BG8)

- **Four checkpoints**, all reading ONE probe and throwing ONE factory (Q-B3 shared-helper pin,
  Q-B5 message): (1) entry probe hoisted above the pin (AbstractStorage.java:2540-2547); (2)
  `stateLock.exclusiveLockWithAbort(this::isOperatorFreezeActive, OPERATOR_FREEZE_ABORT_POLL_NANOS)`
  in `commitSchemaCarry` (AbstractStorage.java:3283-3297); (3) loop-top gate before the depth
  increment with count re-balanced (OperationsFreezer.java:124-127); (4) park-decision re-check
  strictly after the enqueue (OperationsFreezer.java:143-154). Probe:
  `isOperatorFreezeActive()` (AbstractStorage.java:6687-6695); factory:
  `operatorFreezeGateException()` — type `ModificationOperationProhibitedException`, distinct stable
  message with storage name (AbstractStorage.java:6697-6710). **Discharged.**
- **CS19 single-owner clear**: the pin at commit() (AbstractStorage.java:2549) is followed
  immediately by a NESTED try whose finally is the SOLE clear (AbstractStorage.java:2550-2590),
  with the comment explicitly rejecting the literal-outermost placement and noting the
  index-sort/atomic-op-read coverage; grep confirms exactly one
  `clearThreadLocalSchemaSnapshot` call in the file (:2589) — the old `applyCommitOperations`
  clear is deleted. **Discharged.**
- **CN19/CN25 primitive**: single bit-acquisition, per-iteration drain polling, success-edge
  re-check before returning true, full release on abort, interrupt restores flag +
  `DatabaseException`, phase-2 throw guard from the Step 4 review-fix
  (ScalableRWLock.java:686-764). **Discharged.**
- **Q-B1**: arming is `schemaContext != null` threaded `startTxCommit(…, schemaArmed)` →
  `startToApplyOperations(…, schemaArmed, schemaGate)` → `startOperation(schemaArmed, schemaGate)`
  (AbstractStorage.java:2802/6666-6675; AtomicOperationsManager.java:132-143); legacy DDL unarmed.
  The CN15-corrected accepted-risk record (FULL read+write outage via `stateLock.writeLock`)
  is in the PR description risk item (1) verbatim. **Discharged.**
- **Q-B2** three taxonomy constraints: (1) rebuild rides `doSynch` — no fifth site, all four sites
  mapped (freeze() both arms OPERATOR AbstractStorage.java:5806-5817; doSynch TRANSIENT :5633-5634;
  the two DiskStorage sites TRANSIENT :358-361/:1252-1256); (2) nesting tolerated — documented at
  `doSynch` ("1->2->1 while op stays 1") and in `freezeOperations`' javadoc; (3) `release()` →
  explicit OPERATOR decrement, now id-keyed with the retained-id deque and the `-1` sentinel as
  guarded fallback (AbstractStorage.java:312/5822/5865-5872; OperationsFreezer.releaseOperations).
  **Discharged.**
- **Q-B4**: herd documented as deliberate and bounded at the cut site
  (OperationsFreezer.java:206-225) and pinned by test (§2). **Discharged.**
- **CN23/CS18**: CAS-floor `decrementToFloor` (single RMW, decrement-only-if-positive,
  OperationsFreezer.java:322-337), clamp result feeds the `requests == 0` cut decision, log-never-
  throw on both decrements, lockstep assert deliberately absent with the rationale in the release
  javadoc. **Discharged.**
- **V1/V8 orderings**: explicit "V1 arm ordering, part 1/2" comments (OperationsFreezer.java:196,
  206), the entrant-side enqueue-before-recheck comment (:143-152), and "V8 retract ordering,
  part 1/2" (:283, 293). **Discharged.**
- **BG8 Option A**: rationale comments at the loop's supplier-check throw site
  (OperationsFreezer.java:129-136) and at the arm-cut site (:219-222), both marked user-ruled
  2026-07-23; pinning test asserts the SUPPLIER's exception surfaces (never the gate factory's)
  and post-release usability (§2). Recorded in plan (track-7.md:53-55, 326-338) and PR research
  log. **Discharged.**
- **6 accepted risks**: all six recorded verbatim (with the corrected CN15 blast radius and the
  CS13 narrowing) in the PR description "Track 7 accepted risks" paragraph; risks 2 and 5 are
  additionally carried into durable code comments (OperationsFreezer field javadoc:24-37 for the
  one-sided guarantee; `hasInFlightForeignCommit` javadoc for the TOCTOU). **Discharged.**

**Orphaned-pin sweep (alternative hypothesis: a pin discharged nowhere).** I checked every ID the
plan's seam-ownership summary names (CS16/CN22→Step 3; Q-B3/Q-B5 factory + CS19/CS15/CN21 +
CN10/CS10/CN15 + CN23/CS18→Step 5; CN19/CN25→Step 4) against the code above — none is orphaned.
Two letter-level recording nits, neither a pin violation: (a) the pass-14 CS13 note asked for "one
line in the track file"; the record lives in the design doc's risk list and the PR description,
while track-7.md carries it only via the Step 3 Discharges cross-reference — discoverable, but not
the literal track-file line (folded into CQ12's bookkeeping disposition below); (b) the design
doc's B.1 "worth a line in the track file" aside about the pre-existing reader-stall
(writer-preference behind a freezer-parked read-lock holder) is likewise only in the design doc —
that text was advisory prose, not a ruling, so NULL.

---

## 2. Charter (2) — TEST TOTALITY. VERDICT: ALL PINNED MATRICES PRESENT AND ASSERTING WHAT THEY CLAIM (null finding)

Spot-checked by name and assertion text (not re-derived; mechanism-level correctness was gated in
the step reviews):

- **Five-path `immutableCount` matrix** (CS15 amended matrix, each on a pooled session recycled and
  re-borrowed): (a) probe rejection — `probeThrowsOnPreEngagedOperatorFreeze` (pin-delta assert +
  stack attribution excluding `commitSchemaCarry`/`OperationsFreezer` frames,
  FreezerGateTest.java:117-152); (b) checkpoint-2 abort —
  `writeLockAbortThrowsWhenFreezeEngagesAfterProbe` (`pinsAfter == 0`, :162-231); (c) failed data
  commit (version conflict), (d) failed schema-carry commit (in-window hook fault), (e) success —
  `pinBalanceAcrossCommitOutcomesOnRecycledPooledSessions` (:753-802). All five funnel through
  `assertRecycledBorrowRunsDdl` (recycled borrower runs DDL — the mandated health probe).
- **Dual-path gate matrix + checkpoint-2 mechanism**: pre-engaged → probe throws zero-locks
  (test (a) above); probe-to-entry window → checkpoint-2 abort (test (b)); the mechanism pin —
  `writeLockAbortFiresWhileQueuedBehindFreezerParkedReader` (:245-398) blocks the armed commit
  behind a freezer-parked read-lock holder, requires `commitSchemaCarry` stack attribution, no
  freezer frame, balanced pin, and READS FLOWING while the freeze is still engaged (the outage
  property itself, bounded).
- **Cut-wake (checkpoint 3 + operator-arm cut)**: `parkedSchemaCommitWakesAndThrowsWhenOperator
  FreezeArrives` (:401-457) — parked behind a transient, woken by the cut, throws the gate
  exception with a direct pin-balance assert on the in-freezer throw path.
- **Herd (Q-B4)**: `operatorFreezeHerdReparksDataCommitsWithoutAdmission` (:466-529) — both data
  commits park, wake on the cut, re-park (asserted twice: post-cut and post-operator-release with
  transient still held), none admitted (`committed.getCount() == 2`), all complete after release,
  never throw.
- **Double-release / CAS-floor**: `doubleReleaseFloorsCountersAndGateStillArms` (:537-575) — both
  the id-keyed and `-1` sentinel double-release shapes, then a genuine freeze still arms the gate
  (the silent-disarm regression the guard exists for), then clean disarm.
- **Bookkeeping leak**: `repeatedFreezeReleaseCyclesDoNotLeakOperatorFreezeIds` (:582-604) — 16
  cycles over BOTH freeze arms, set size returns to baseline.
- **Legacy semantics unchanged**: `throwModeFreezeKeepsLegacySupplierForDataWrites` (supplier
  wording asserted present, gate wording asserted ABSENT) and
  `dataCommitParksUnderTransientFreezeUnchanged` (park-then-success, never throw).
- **BG8 pin**: `cutWokenParkedDataCommitThrowsSupplierUnderThrowModeOperatorFreeze` (:645-708) —
  parked behind a transient, throw-mode operator freeze engages at manager level with the legacy
  supplier, woken commit fails promptly with the SUPPLIER's exception (gate wording asserted
  absent), storage usable after releases; 60s timeout, bounded latches.
- **CS14b non-pin honored**: the class javadoc explicitly records "Deliberately NOT pinned: the
  absence of gate false-positives around the freeze-release instant" (FreezerGateTest.java:36-40) —
  the tests correctly do NOT assert no-false-positives.
- **Mutex matrix** (MetadataWriteMutexTest, 22 tests): FM-A2 —
  `teardownThrowBeforeTxCloseStillReleasesPermit` + `teardownWithSkippedRollbackStillReleasesPermit`
  (the mislabel split from the Step 3 review-fix); FM-A3 —
  `postAcquireDekkerRecheckSelfReleasesAndThrows`, `foreignTeardownHarvestsEngagedPermit`,
  `engageOnTeardownMarkedSessionFailsLoudAndLeavesPermitFree`; FM-A4b —
  `doubleReleaseKeepsSinglePermit`; FM-A7 — `strandedSameSessionReengageThrowsLoudly`
  (type + "already held by this session" + "never released"); CN22/Q-A2 —
  `poolCloseDuringCommitDefersTeardownToOwner` (whitelist observed, commit durable, owner
  completes, single session-count decrement) + `poolCloseFallsThroughToFullTeardownWhenNotCommitting`;
  CS16 — `throwingCloseListenerNeverMasksCommitOutcome`; claim race —
  `concurrentTeardownsRunExactlyOneFullTeardown`; Q-A3 interrupt —
  `interruptedEngageWaiterThrowsAndRestoresInterruptFlag`; CN12 loop isolation —
  `poolCloseLoopSurvivesThrowingSessionTeardown`. Step 1/2 acceptance + regression tests present
  (:106, :215).
- **Primitive matrix** (ScalableRWLockTest, 13 abort-primitive tests): plain round trip, phase-1
  queued abort, drain abort, success-edge (deterministic two-poll canary), writer preference,
  interrupt shape, argument validation, two-waiter serialization, throwing-predicate ×2
  (bit released), continuous-coverage liveness (`writerAcquiresUnderContinuousReaderCoverage` —
  the CN19 discriminator), bounded stress.
- **Liveness stress**: `OperationsFreezerLivenessTest.operatorFreezeChurnNeverWedgesTheWaitingList`
  — the driven reproducer pinning both the two-cutter wedge and the wake-only-walk livelock
  (bounded joins, fails instead of hanging the fork), as the Step 5 episode records.

---

## 3. Charter (3) — API SURFACE. VERDICT: APPROPRIATELY SCOPED (null finding); one stale javadoc (CQ11)

1. **No public-API change**: `git diff e2605c8ba3..5808d9b11e -- core/src/main/java/com/jetbrains/
   youtrackdb/api` is empty. Everything new lives under `internal`.
2. **`MetadataWriteMutex`** (internal): `engage(session)` → ordinal, `releaseFor(session, ordinal)`
   never-throw warn-noop, `isEngagedBy` — all with substantial contract javadoc including the
   double-belt release discipline. One nit: `isEngagedBy`'s javadoc claims a production consumer it
   does not have (CQ11 below).
3. **`ScalableRWLock.exclusiveLockWithAbort`**: public on an internal shared primitive; ~60-line
   contract javadoc covering both guarantees, the success-edge re-check, interrupt semantics, the
   fairness trade-off, and the throwing-predicate discipline. Note: introduces
   `core.exception.{BaseException,DatabaseException}` imports into `common.concur.lock` — a
   pre-existing pattern in that package (`AdaptiveLock`, `OneEntryPerKeyLockManager` already import
   core exceptions), so NULL.
4. **Armed overloads**: `AtomicOperationsManager.startToApplyOperations(op, schemaArmed, gate)` and
   `OperationsFreezer.startOperation(schemaArmed, gate)` — both javadoc'd, unarmed variants
   preserved byte-for-byte for data paths, null gate rejected loudly at the armed boundary
   (`Objects.requireNonNull`, OperationsFreezer.java:111-116). The dead one-arg `startTxCommit`
   overload was deleted (b54384e08a); grep confirms no leftover.
5. **`FreezeKind`**: two-value enum with taxonomy javadoc; signature change of
   `freezeWriteOperations(kind, supplier)` propagated to all five callers (grep-verified, no old
   signature remains).
6. **Test-only accessors**: `OperationsFreezer.registeredOperatorFreezeIdCount` (+ manager
   delegate) and `MetadataDefault.getThreadLocalSchemaSnapshotPinCount` — both explicitly javadoc'd
   "Test-observability only"/"Not used by production code". Test hooks
   (`setCommitWindowTestHook`, `setPostEngineBuildTestHook`, `setEndTxCommitFailureTestHook`,
   `setEndTxCommitPostDurabilityFailureTestHook`) are volatile fields read into locals at the fire
   site, documented as test-only seams, null in production — properly guarded. No `-ea`-dependent
   or reflective hooks.

---

## 4. Charter (4) — DOCUMENTATION SYNC. VERDICT: NULL — no user-facing doc covers the changed surface

Premise: the track changed observable behavior twice over — (i) a schema DDL commit under an
operator `freeze()` now aborts loudly with the new stable message ("Schema commit aborted:
operator freeze in progress on storage '<name>' …") instead of parking; (ii) under a THROW-mode
operator freeze, a cut-woken parked data commit now deterministically throws the legacy supplier
exception (BG8 Option A) instead of sometimes parking through.

Search evidence: `grep -rin "freeze" docs/` (excluding `docs/adr`) — zero hits;
`grep -rin "backup" docs/` (excluding `docs/adr`) — zero hits. `docs/README.md` indexes
getting-started, object-oriented modeling, security, YQL reference, and the query-engine book;
none documents `freeze()/release()`, backup quiesces, or DDL-under-freeze semantics. The YQL DDL
pages (`YQL-Create-Class.md` etc.) describe statement syntax only.

**Conclusion:** there is no page to sync — no flag raised, no edit needed. For the record (so the
orchestrator can carry it into the PR description if desired): if operator-freeze/backup
documentation is ever added to `docs/`, the stable gate message contract and the throw-mode
deterministic-abort behavior are the two operator-visible facts to document. The PR description
already records both (freezer-gate paragraph + the BG8 research-log line), which is where this
branch's observable-behavior story currently lives.

---

## 5. Charter (5) — COMMIT HYGIENE. VERDICT: substantively clean; one ephemeral-ID subject (CQ10)

All 24 commit messages read against their diffs:

1. **Accuracy**: each product commit's body matches its diff (spot-verified: 736cab68ec's claims
   about the four checkpoints, the synchronized cut, and the CAS-floor all correspond to code
   cited in §1.3; 11bf0eda26's handshake claims correspond to §1.2; dc5dfe006a correctly describes
   a test-only change; cb2d4d3b79/1063e1d987/7d2369cda0 match the Step 1 fix surface). The
   "Record …" documentation commits accurately summarize what they add. No message overclaims a
   fix it does not contain. **No finding.**
2. **Issue IDs**: product commits carry `YTDB-382` (the branch's issue) in the body — allowed
   (YTDB-#### is the sanctioned form); the squash-merge takes the shipped message from the PR
   title/description per repo policy.
3. **Ephemeral review-finding IDs**: one hit — HEAD commit `5808d9b11e`'s SUBJECT is "Record the
   BG8 resolution in the Track 7 plan" (BG8 is a step-review finding ID). One borderline hit —
   `865cb1de7f`'s body says "FM-A5-class residual" (a design-draft failure-mode label). Every
   other message describes findings in plain language ("the one deferred finding (cut-woken
   data-commit semantics…)" — exemplary discipline). See CQ10.

---

## 6. Charter (6) — LEFTOVER DEBRIS. Findings BG10, BG11, CQ12; otherwise clean

1. **No stray TODO/FIXME/XXX/HACK** anywhere in the track's added lines (grep over the full diff:
   zero hits; the one pre-existing "XXX" in `commit()`'s header comment predates the track).
2. **No dead code found**: the dead `startTxCommit` overload was removed; all new methods have
   callers (grep-verified for the release funnel, completer, skip detection, gate probe/factory,
   cut helper) — the sole zero-production-caller method is `isEngagedBy`, which is a deliberate
   test/diagnostic accessor (CQ11 is about its javadoc, not its existence).
3. **Untracked files at HEAD** (git status):
   - `docs/adr/transactional-schema/_workflow/plan/track-7/reviews/gate-step5-fixes-iter1.md` —
     the Step 5 fix-gate report (manifest: 9/9 VERIFIED) is NOT committed (BG10).
   - `followup-OBS6-minor-ddl-seams.md`, `followup-supernode-index-shortcut-crash.md` at the REPO
     ROOT — follow-up issue drafts, uncommitted, outside any workflow directory (BG11).
4. **Plan bookkeeping**: track-7.md's Progress phase checkbox "Step implementation" is still
   unchecked although all five steps are complete and individually ticked (CQ12).

---

## Findings

### BG9 — durable production comments cite ephemeral workflow finding/ruling IDs that die at merge — SHOULD-FIX (branch-completion scope, not track-blocking)

**Premises.** (1) The track's production diff introduces, for the first time on this branch
(grep at `e2605c8ba3` over `core/src/main`: zero such labels), design-review labels into shipped
code comments: `Q-A2` ×10, `Q-B3`/`Q-B4`/`Q-B5`, `Q-A3`/`Q-A5`, `FM-A7`, `BG8` ×2, and the
`V1`/`V2`/`V8` ordering tags (e.g. MetadataWriteMutex.java:124, DatabaseSessionEmbedded.java:3875,
DatabaseSessionEmbeddedPooled.java:69/83, OperationsFreezer.java:129/196/221/283,
FrontendTransactionImpl.java:84, AbstractStorage.java kind-mapping comments). (2) These labels
resolve only inside `docs/adr/transactional-schema/_workflow/**`, which is an ephemeral artifact
tree: merged ADRs retain only `adr.md` + `design-final.md` (every directory under
`docs-internal/adr/*` confirms this), and the repo's recorded discipline says durable content must
"cite by file path, class or method name, or stable workflow-doc anchor rather than ephemeral
track / step / finding labels" (docs-internal/adr/dd-workflow-review-skill/adr.md:312-319).
(3) Counterpoint checked (alternative hypothesis: the labels are durable like the pre-existing
`D`-record references): D-references (9 occurrences) exist at the merge base and anchor design.md
D-records that ship in the ADR's final design doc; the Q-/V-/FM-/BG- labels anchor the track-7
drafts/rulings/review findings, which do not ship.

**Impact.** Post-merge, comments like "Pinned contract (BG8, user-ruled 2026-07-23)" and
"V1 arm ordering, part 1" reference documents that no longer exist. Mitigation already present:
every such comment carries the full rationale in self-contained prose (the IDs are supplementary
anchors), so no correctness knowledge is lost — only the traceability link dangles.

**Fix bound (mechanical, at branch cleanup or ADR distillation).** Either (a) strip/expand the ID
tags into the already-present prose ("V1 arm ordering" → "publish-kind-before-count ordering",
"BG8, user-ruled" → "user-ruled 2026-07-23 contract"), or (b) ensure the rulings/orderings table
is folded into the shipped `design-final.md` and re-anchor the comments to it. Decision belongs to
the orchestrator at branch completion; recording here so it is not discovered post-merge.

### BG10 — Step 5 fix-gate report exists only as an untracked file — SHOULD-FIX

`docs/adr/transactional-schema/_workflow/plan/track-7/reviews/gate-step5-fixes-iter1.md`
(manifest: 9 findings, 9/9 VERIFIED, scope = commit b54384e08a) is present in the working tree but
uncommitted, while every earlier gate report (gate-step{1,3,4}-*) is in the archive. The Step 5
review-fix episode commit (68a1078e2f) landed BEFORE the gate ran, so nothing has picked it up
since. If the worktree is cleaned or the review directory is pruned by path, the only record that
the Step 5 fixes were gate-verified is lost. **Fix:** commit it with the cumulative-review episode
(alongside this report).

### BG11 — repo-root untracked follow-up drafts — SUGGESTION

`followup-OBS6-minor-ddl-seams.md` and `followup-supernode-index-shortcut-crash.md` sit untracked
at the repository ROOT. Content-wise they are follow-up YouTrack issue drafts (one explicitly
notes its finding is pre-existing on develop). The repo root is neither a durable nor a workflow
location: a careless `git add -A`/`git add .` in any later commit sweeps them into product
history, and a `git clean` loses them. **Fix:** file them as YouTrack issues and delete, or move
under the track's `_workflow` area until filed.

### CQ10 — ephemeral finding ID in a commit subject — SUGGESTION

Commit `5808d9b11e` subject: "Record the BG8 resolution in the Track 7 plan" — `BG8` is a
step-review finding ID, against the commit-hygiene rule (YTDB-#### only; describe findings in
plain language, as the sibling commit 68a1078e2f does: "the one deferred finding (cut-woken
data-commit semantics…)"). Secondary instance: `865cb1de7f` body's "FM-A5-class residual".
Graded suggestion, not should-fix, because (a) the squash-merge builds the shipped message from
the PR title/description, so these subjects never reach `develop` history, and (b) rewriting
pushed branch history would cost more than the violation. Actionable residue: ensure the PR
description (which DOES ship) keeps describing the BG8 ruling in plain language — it currently
does ("under a throw-mode operator freeze, cut-woken parked data commits deterministically throw
the registered supplier's exception"), with the ID only as a parenthetical alongside the
resolvable YTDB context; and carry the plain-language discipline into future subjects.

### CQ11 — `MetadataWriteMutex.isEngagedBy` javadoc claims a production consumer it lacks — SUGGESTION

MetadataWriteMutex.java:224-227: "Used by the engage-order assertion and by tests; not part of the
release protocol." Grep over `core/src/main`: zero production callers (the engage-order guard in
`DatabaseSessionEmbedded.engageMetadataWriteMutex` uses the schema/IM
`isWriteLockHeldByCurrentThread` probes, not this method); all 9+ callers are in
`MetadataWriteMutexTest`. Stale-comment class (the guideline: stale comments are worse than none).
**Fix:** one-line javadoc correction ("Test observability only; not part of the release
protocol.").

### CQ12 — plan bookkeeping: Progress phase checkbox + CS13 track-file line — SUGGESTION

(a) track-7.md:24 "- [ ] Step implementation" is unchecked although all five step slots carry
`[x] commit:` markers and all review-fix iterations are logged — tick it at track completion.
(b) The pass-14 CS13 note's letter asked for "one line in the track file" recording the skip's
protection boundary; the record lives in the design doc's risk list and the PR description
(risk 4, with the CN20+CS17 narrowing) but not as a track-file line. Since the PR description is
the durable carrier, either add the one-liner to the track file's Outcomes section at completion
or note the deliberate relocation there. Pure bookkeeping; no code impact.

### TQ12 — test totality — NULL VERDICT (no finding)

All pinned matrices are present, correctly named, and asserting their claimed properties (full
enumeration in §2): five-path pin balance on recycled pooled sessions, dual-path gate +
checkpoint-2 mechanism with stack attribution and the reads-flow outage probe, Q-B4 herd,
double-release CAS-floor (both shapes + re-arm), freeze-id leak regression, legacy throw-mode and
transient-park byte-for-byte pins, BG8 deterministic-throw pin, FM-A2/A3/A4b/A7 mutex shapes,
CN22 both deterministic interleavings, CS16 masked-outcome, Q-A3 interrupt, pool-loop isolation,
teardown-claim race, CN19/CN25 primitive matrix incl. the continuous-coverage liveness
discriminator, and the waiting-list liveness stress. The CS14b "must NOT pin no-false-positives"
constraint is explicitly honored in the test-class javadoc.

---

## Compact findings block

```
BG9  should-fix  durable core/src/main comments cite ephemeral workflow IDs (Q-A*/Q-B*/FM-A7/BG8/V1/V2/V8)
                 that resolve only in docs/adr/.../_workflow/** (dies at merge; discipline:
                 dd-workflow-review-skill/adr.md:312-319). Prose is self-contained — strip/expand tags or
                 re-anchor to the shipped design doc at branch cleanup. New with this track.
BG10 should-fix  gate-step5-fixes-iter1.md (9/9 VERIFIED) is UNTRACKED — commit it with the track-review
                 episode or the Step 5 gate record is lost.
BG11 suggestion  followup-OBS6-minor-ddl-seams.md + followup-supernode-index-shortcut-crash.md untracked at
                 repo ROOT — file as YouTrack issues and remove, or move under _workflow.
CQ10 suggestion  commit 5808d9b11e subject carries finding ID "BG8" (and 865cb1de7f body "FM-A5-class") —
                 against the YTDB-####-only rule; moot at squash-merge, keep PR description plain-language.
CQ11 suggestion  MetadataWriteMutex.isEngagedBy javadoc claims an "engage-order assertion" consumer; zero
                 production callers — mark test-observability-only.
CQ12 suggestion  track-7.md Progress "Step implementation" unchecked though all 5 steps done; CS13
                 track-file one-liner lives only in design doc + PR description — tick/note at completion.
NULL charter(1)  spec completeness: all five steps landed; every pin (Q-A1..A5, Q-B1..B5, CN10-25, CS10-19,
                 V1/V2/V8, CS19 nested clear, CN24 mark placement, Q-B5 message, BG8 Option A, 6 accepted
                 risks incl. corrected CN15 wording in PR description) discharged; no orphaned pin.
NULL charter(2)  test totality (TQ12): all pinned matrices present and asserting their claims.
NULL charter(3)  API surface: no public-API change; internal additions javadoc'd; test hooks/accessors
                 properly marked; dead overload removed.
NULL charter(4)  doc sync: no user-facing docs/ page covers freeze/release, backup, or DDL-under-freeze —
                 nothing to sync; PR description carries the behavior-change record.
```
