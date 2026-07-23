<!-- MANIFEST
findings: 8   severity: {blocker: 0, should-fix: 2, suggestion: 6}
scope: "git show 736cab68ec — Track 7 Step 5 (freezer gate: FreezeKind taxonomy, four checkpoints, single-owner pin/clear, CAS-floor guards, synchronized single-cutter waiting list, FreezerGateTest + OperationsFreezerLivenessTest, two batched residual fixes), code-baseline charter"
index:
  - {id: BG7, sev: should-fix, loc: "OperationsFreezer.java:43,164-167,232-238 + AbstractStorage.java:5795-5804,5848-5850", anchor: "### BG7 ", basis: "every storage-level freeze()/release() cycle leaks one entry in operatorFreezeIds: freeze() discards the returned id, release() presents the -1 sentinel, and the sentinel branch of releaseOperations never removes the retained id — unbounded set growth on a long-lived server with periodic snapshot freezes; the requests==0 cleanup purges only freezeParametersIdMap"}
  - {id: BG8, sev: should-fix, loc: "OperationsFreezer.java:110 (throwFreezeExceptionIfNeeded on the wake path) reached via the arm cut :191-195", anchor: "### BG8 ", basis: "a data commit parked behind a pre-existing freeze that is woken by an operator-arm cut while a throw-mode supplier is registered (freeze(true)) now deterministically throws the legacy supplier where it historically parked through and completed after release — contradicts the commit message's 'byte-for-byte' data-commit claim; unpinned by any test (herd test covers only the park-mode operator freeze)"}
  - {id: CQ7, sev: suggestion, loc: "AbstractStorage.java:6643-6645", anchor: "### CQ7 ", basis: "the single-arg startTxCommit(AtomicOperation) overload is dead code — its only caller (applyCommitOperations:2789) was migrated to the two-arg form in this same commit"}
  - {id: CQ8, sev: suggestion, loc: "OperationsFreezer.java:93-119", anchor: "### CQ8 ", basis: "the armed-implies-non-null-gate contract is javadoc-only; startOperation(true, null) NPEs at schemaGate.get() mid-loop under a freeze instead of failing at the method boundary — add Objects.requireNonNull(schemaGate) (or an assert) when schemaArmed"}
  - {id: CQ9, sev: suggestion, loc: "OperationsFreezer.java:191-195 vs :277-283", anchor: "### CQ9 ", basis: "the cut-and-unpark loop is duplicated verbatim at the operator-arm and release-side cut sites; extract a private cutAndUnparkWaiters() helper"}
  - {id: TQ9, sev: suggestion, loc: "FreezerGateTest.java:116,357,395", anchor: "### TQ9 ", basis: "the three tests that run gated commits on the JUnit main thread carry no @Test(timeout): a total gate regression parks the main thread in the freezer with the operator freeze registered and the releasing finally unreached — the fork hangs to the global surefire timeout instead of failing one test (Step-4 bounded-failure precedent)"}
  - {id: TQ10, sev: suggestion, loc: "FreezerGateTest.java:68-79,148-225,286-354", anchor: "### TQ10 ", basis: "awaitThreadParked accepts any WAITING/TIMED_WAITING state, so the cp2 test can silently degrade to exercising checkpoint (1) (identical exception + pin outcome), and the herd test's 're-park' observation can be satisfied by the original park (unpark issued, thread not yet scheduled) — the checkpoint identity and the wake trajectory are not strictly verified"}
  - {id: TQ11, sev: suggestion, loc: "FreezerGateTest.java:228-283", anchor: "### TQ11 ", basis: "the in-freezer gate-throw path (checkpoints 3/4) never asserts the snapshot pin count directly — balance is checked only indirectly through pooled.close() succeeding; add the explicit worker-side pinCount assertion for symmetry with the cp2 test"}
evidence_base: {section: "## No-issue verifications (null verdicts)", certs: 20}
flags: [READ_ONLY, NO_MAVEN_RUN, NO_PRODUCT_CODE_MODIFIED]
-->

# Code-baseline review — Track 7 Step 5, commit 736cab68ec (iter 1)

**Artifact:** `git show 736cab68ec` ("Gate schema commits against operator freezes").
**In-scope files (as landed on `transactional-schema`):**
`FreezeKind.java` (new, 19 lines); `OperationsFreezer.java` (+194/-47: kind counters, armed `startOperation`, operator-arm cut, CAS-floor release); `WaitingList.java` (`cutWaitingList` now `synchronized` + protocol javadoc); `AtomicOperationsManager.java` (armed `startToApplyOperations` overload, kind-threading `freezeWriteOperations`, `isOperatorFreezeActive`); `AbstractStorage.java` (checkpoint (1) entry probe :2531-2539, single-owner pin/clear :2536-2577, checkpoint (2) `exclusiveLockWithAbort` :3271-3285, kind-labeled freeze sites :5615-5621/:5793-5804/:5848-5850, helpers :6643-6693); `DiskStorage.java` (two TRANSIENT_QUIESCE labels :358-361/:1253-1256); `MetadataDefault.java` (test accessor :95-102); `DatabaseSessionEmbeddedPooled.java` (comment fix); `MetadataWriteMutex.java` (release-log identity fix :202-224); `FreezerGateTest.java` (new, 8 tests, 514 lines); `OperationsFreezerLivenessTest.java` (new, 160 lines).
**Charter:** correctness & bugs (armed-overload API surface — old callers; unarmed paths byte-identical; gate-supplier null threading; `MetadataDefault` accessor exposure), code quality (rewritten `OperationsFreezer` readability, V1/V8 ordering comments, herd-cut comment, duplication, `FreezeKind` javadoc, log quality), test quality (contract coupling, bounded waits, CI safety of the 120 s liveness stress, pin-balance matrix strength, herd no-admission strength, double-release re-arm, thread hygiene per the Step-4 precedent).
**Method:** decision criteria + numbered premises; file:line traces against the files as landed; full branch/exit-path enumeration of the four new/changed concurrency-bearing methods; counterexample per defect, justification per no-issue; alternative-hypothesis check; hypothesis log. Read-only; no Maven executed; no product code modified.

## Decision criteria

1. **One-sided gate guarantee.** A schema-armed entrant must never park while `operatorFreezeRequests > 0` (the outage class the step exists to remove). The converse — no false-positive throws — is explicitly *not* guaranteed (documented in the counter javadoc and the test-class javadoc) and must not be pinned.
2. **Unarmed paths byte-identical.** Data commits and internal atomic-operation wrappers must traverse exactly the historical freezer code (the design's own risk containment; the commit message claims "byte-for-byte").
3. **Balanced accounting on every escape.** `operationsCount`, `operationDepth`, the snapshot pin count, and the two freeze counters must return to their pre-call values on every exit — normal, gate throw, supplier throw, and exceptional — with the CAS floor preventing negative poisoning.
4. **Liveness.** No new park/lock cycle: the cut-wake protocol must not wedge (single-cutter invariant), and no transient-freeze releaser may block on a lock the parked armed committer holds.
5. **Tests pin contracts, are bounded, and fail loudly instead of hanging** (Step-4 precedent: daemon threads, join-with-timeout in cleanup, diagnostics on wedge).

## Branch / exit-path enumerations

### A. `OperationsFreezer.startOperation(schemaArmed, schemaGate)` (:93-136)

| # | Path | Accounting on exit | Verdict |
|---|------|--------------------|---------|
| A0 | `operationDepth > 0` (nested) → depth++ only | unchanged from history | OK (N2) |
| A1 | no freeze → increment, depth++ | unchanged | OK (N2) |
| A2 | loop iter: count decrement (:101) → armed loop-top gate true (:105-107) → **throw** | count net 0 (entry increment or loop-bottom re-increment balanced by :101), depth untouched | OK (N5) |
| A3 | loop iter → `throwFreezeExceptionIfNeeded` throws (:110) | same balance as A2 | OK for fresh entrants (historical); **new reachability for woken parked entrants → BG8** |
| A4 | enqueue (:114) → armed park-decision gate true (:118-120) → **throw** | balanced; node abandoned in list (unparked later by a cut — benign duplicate unpark, thread ref only) | OK (N5) |
| A5 | park (:123) → wake → loop-bottom increment (:126) → loop re-check | count transiently +1 then −1; drain-loop tolerant | OK (N6) |
| A6 | `schemaArmed=true, schemaGate=null` misuse → NPE at :106/:119 | balanced (throw sits at the gate sites) | CQ8 |
| A7 | `schemaArmed=false` — both gate `if`s short-circuit without reading `operatorFreezeRequests` | code path byte-identical to the pre-commit method | OK (N2) |

### B. `OperationsFreezer.freezeOperations(kind, throwException)` (:159-206)

| # | Path | Verdict |
|---|------|---------|
| B1 | TRANSIENT: id, `freezeRequests++`, optional supplier, drain | byte-identical to history (no kind reads) — OK (N3) |
| B2 | OPERATOR: kind++ (:165) → id retained (:166) → `freezeRequests++` (:169) → supplier (:171-173) → cut+unpark (:191-195) → drain | V1 arm ordering as documented; supplier registered *before* the cut so woken entrants see it deterministically (feeds BG8) — ordering itself OK (N4) |
| B3 | concurrent OPERATOR arms | cutters serialized by the `WaitingList` monitor; counters atomic — OK (N7) |

### C. `OperationsFreezer.releaseOperations(id)` (:231-289)

| # | Path | Verdict |
|---|------|---------|
| C1 | `id == -1` → kind=OPERATOR (:233-235); id **not** removed from `operatorFreezeIds` (the remove sits in the `else`, :237) | count/kind decrements correct; **retained arm-side id leaks — BG7** |
| C2 | retained id, in set → remove → OPERATOR; both floors decrement | OK; id-based single-decrement means a double release of the same id resolves TRANSIENT the second time — the operator counter is double-protected (N8) |
| C3 | retained id, not in set → TRANSIENT; count floor only | OK (N8) |
| C4 | double release → `decrementToFloor` returns −1 → error log, no throw, no cut | matches the transient-finally masking rationale; `requests==0` cleanup+cut correctly skipped on −1 (N9) |
| C5 | `requests == 0` → param purge + release-side cut+unpark | unchanged shape from history — OK (N9) |

### D. `AbstractStorage.commit(...)` pin/clear escape paths (:2523-2583)

| # | Escape | Pin state | Verdict |
|---|--------|-----------|---------|
| D1 | checkpoint (1) probe throw (:2531-2533) — pre-pin | never pinned; nested finally not entered | OK (N10) |
| D2 | `getSortedIndexOperations`/`getAtomicOperation` throw (:2548-2549) | cleared once by :2576 — **fixes a pre-existing leak** (old clear lived inside `applyCommitOperations`, unreachable from here) | OK (N10) |
| D3 | checkpoint (2) abort throw (:3281-3285) — schema+index locks unwind via `commitSchemaCarry` finallys | cleared once by :2576 | OK (N10, N12) |
| D4 | in-freezer gate throw (checkpoints 3/4) — write lock + commit window unwind via `commitSchemaCarry` finallys (:3286-3305) | cleared once | OK (N10) |
| D5 | any `applyCommitOperations` exception (incl. version conflict) | cleared once (:3231-3235 deliberately does not clear; comment states why) | OK (N10) |
| D6 | success | cleared once | OK (N10) |

---

## Findings

### BG7 — should-fix — `operatorFreezeIds` leaks one entry per storage-level `freeze()`/`release()` cycle

**Location:** `OperationsFreezer.java:43` (the set), `:164-167` (arm-side `add`), `:232-238` (release-side resolution); `AbstractStorage.java:5795-5804` (`freeze()` discards the returned id), `:5848-5850` (`release()` presents `-1`).

**Premises:**
1. Every OPERATOR registration adds the freshly generated id to `operatorFreezeIds` (:166) — including the two storage-level `freeze()` arms, which discard the returned id (`AbstractStorage.java:5798-5803`, return value unused).
2. The paired release is `unfreezeWriteOperations(-1)` (:5850). In `releaseOperations`, the `-1` branch resolves the kind explicitly (:233-235) and **never touches `operatorFreezeIds`** — the `remove(id)` sits only in the `else` branch (:237).
3. The `requests == 0` cleanup (:266-273) purges only `freezeParametersIdMap`; `operatorFreezeIds` has no purge site other than the id-keyed remove that this pair never executes.
4. Ids are monotonically unique (`freezeIdGen`), so a stale entry can never be mis-resolved later — the defect is purely accumulation, plus bookkeeping drift that any future id-keyed logic (e.g., diagnostics enumerating "active operator freezes") would trip over.

**Counterexample:** N `session.freeze(false)`/`session.release()` cycles on a long-lived server (periodic filesystem-snapshot backups are exactly the intended use) leave N boxed `Long`s in the CHM key set, unbounded. The gate tests never detect it because `isOperatorFreezeActive` reads only the counter.

**Fix options (any one):** (a) have `freeze()` retain the id in a storage field and release by id (also retires the `-1` sentinel's special case); (b) clear `operatorFreezeIds` when `decrementToFloor(operatorFreezeRequests)` returns 0; (c) on the `-1` branch, remove one arbitrary stale entry — (a) is cleanest, (b) is smallest.

**Alternative hypothesis checked:** that the `requests == 0` cleanup or some other path clears the set — grepped all references to `operatorFreezeIds`; only :43/:166/:237. Rejected.

### BG8 — should-fix — the operator-arm cut converts "park through a throw-mode freeze" into a deterministic throw for already-parked data commits

**Location:** `OperationsFreezer.java:110` (`throwFreezeExceptionIfNeeded` on the loop path), newly reachable for parked entrants via the arm-side cut `:191-195`.

**Premises:**
1. Historically the only cut/unpark site was the release-side `requests 1→0` transition — a parked entrant woke only when all freezes were gone (modulo spurious `LockSupport.park` returns), re-checked `freezeRequests == 0`, and **proceeded**. `throwFreezeExceptionIfNeeded` was reachable in practice only on a fresh entry.
2. The operator arm now cuts and unparks on **every** OPERATOR registration (:191-195), with the supplier registered before the cut (:171-173).
3. A woken *unarmed* entrant re-runs the loop: `freezeRequests > 0` → decrement → loop-top gate skipped (unarmed) → `throwFreezeExceptionIfNeeded()` (:110) → throws the registered supplier.

**Counterexample:** incremental backup's TRANSIENT freeze is active (the WAL copy — minutes on a large store); data commits park behind it. Admin calls `session.freeze(true)`. The OPERATOR arm's cut wakes every parked data committer; each throws `ModificationOperationProhibitedException("Modification requests are prohibited")`. Before this commit, they would (absent a rare spurious wakeup) have stayed parked and **completed** after `release()`. The commit message states "Data commits keep the historical park semantics byte-for-byte" — observably false for this interleaving. No test covers it: `operatorFreezeHerdReparksDataCommitsWithoutAdmission` layers only a **park-mode** operator freeze (`null` supplier), where woken data entrants correctly re-park.

**Mitigations acknowledged (why not blocker):** (a) `LockSupport.park` is specified to permit spurious returns, so the old semantics already *allowed* this outcome nondeterministically — the new behavior sits inside the historical envelope, just made deterministic; (b) the unarmed *code path* is unchanged instruction-for-instruction — the change is in wake timing; (c) throwing at parked writers under `freeze(true)` is arguably the freeze mode's declared intent. The defect is therefore primarily a **contract/documentation mismatch on an untested, user-visible interleaving**: either pin the new deterministic throw as intended (test + comment/commit-lore correction), or make woken unarmed entrants bypass `throwFreezeExceptionIfNeeded` on re-evaluation (restoring strict park-through). A deliberate decision must land either way.

**Alternative hypothesis checked:** that woken data entrants re-park before reaching :110 — no: :110 sits above the enqueue/park in the same iteration; every wake re-executes it while any supplier is registered. Rejected.

### CQ7 — suggestion — dead single-arg `startTxCommit` overload

**Location:** `AbstractStorage.java:6643-6645`.

The commit migrated the sole caller (`applyCommitOperations:2789`) to `startTxCommit(atomicOperation, schemaContext != null)`. The single-arg private overload now has zero callers (verified by grep across the file and module; it is `private`, so no external callers are possible). Dead code invites drift — a future caller would silently take the unarmed path. Remove it (the two-arg form's `false` literal at any future call site is self-documenting).

### CQ8 — suggestion — armed-implies-non-null gate contract is javadoc-only

**Location:** `OperationsFreezer.java:93-119` (`schemaGate.get()` at :106 and :119).

`startOperation(true, null)` NPEs mid-loop, at gate-evaluation time, under an active operator freeze — the worst possible diagnosis point (inside the freezer, after the count decrement; the balance survives, but the failure names neither the misuse nor the contract). The only production caller threads the pair correctly (`AbstractStorage:6654-6656`), and the javadoc states the invariant (:91), but nothing enforces it. Add `Objects.requireNonNull(schemaGate, "...")` under `if (schemaArmed)` at method entry (or an `assert`), converting latent misuse into an immediate boundary failure. Same applies transitively to `AtomicOperationsManager.startToApplyOperations` (:140-142).

### CQ9 — suggestion — duplicated cut-and-unpark loop

**Location:** `OperationsFreezer.java:191-195` (operator arm) vs `:277-283` (release side).

The four-line detach-walk-unpark loop appears twice, character-identical. Extract `private void cutAndUnparkWaiters()`; the load-bearing ordering comments stay at the call sites (they describe *when* to cut, not *how*). Micro-benefit, micro-cost; suggestion only.

Otherwise the rewritten `OperationsFreezer` reads well: the V1 arm ordering is commented at both increment sites (:164, :176-190), the V8 retract ordering at both decrement sites (:247, :257), the herd cut carries the full Q-B4 rationale and both rejected-alternative shapes (:177-190), and the `FreezeKind` javadoc (:1-19 of the new file) states the operational meaning and the consequence of each kind — the "design REQUIRES explicit ordering comments" bar is met. The two underflow error logs name the cause, the invariant kept, and the consequence — good. The `MetadataWriteMutex` log fix correctly adds the *requester's* identity (previously the messages named only `describeHolder()`'s holder, useless for identifying a stale releaser); format args are in scope and specifier-matched.

### TQ9 — suggestion — main-thread gated commits carry no `@Test(timeout)`

**Location:** `FreezerGateTest.java:116` (`probeThrowsOnPreEngagedOperatorFreeze`), `:357` (`doubleReleaseFloorsCountersAndGateStillArms`), `:395` (`throwModeFreezeKeepsLegacySupplierForDataWrites`).

**Premises:**
1. Each runs a commit that the gate must reject **on the JUnit main thread**, with an operator freeze registered and the release sitting in a `finally` *after* the commit call.
2. A total gate regression (all four checkpoints lost — precisely the regression class these tests exist to catch) makes the commit **park** in the freezer instead of throwing. The main thread never reaches the releasing `finally`; the fork hangs to the global surefire timeout, taking the whole class's diagnostics with it.
3. The worker-thread tests are already bounded (latch awaits with timeouts + the `@After` join-or-fail at :57-66, which is the Step-4 daemon/finally pattern done right); only the main-thread paths lack a bound.

Add `@Test(timeout = ...)` to the three tests (the liveness test already models this at :60). Cheap, converts a fork hang into a single named failure.

### TQ10 — suggestion — checkpoint identity and wake trajectory are observationally under-determined

**Location:** `FreezerGateTest.java:68-79` (`awaitThreadParked`), used at `:190-192` (cp2) and `:329-333`/`:338-342` (herd).

**Premises:**
1. `awaitThreadParked` accepts *any* WAITING/TIMED_WAITING state — it cannot name the blocking site.
2. **Cp2:** the window between `goCommit.await()` returning and the entry probe executing contains frontend commit machinery; a transient wait observed there lets the main thread engage the freeze *before* the probe runs, and the worker then throws at checkpoint (1). The assertion set (gate exception + pin 0) is **identical** for checkpoints 1 and 2, so the test passes while checkpoint 2 goes unexercised — silent coverage loss, not a false pass (the held schema write lock still guarantees the throw happens; only *where* is undetermined).
3. **Herd:** the "each woken data commit must re-park" observation (:338-342) can be satisfied by the *original* park — `freezeWriteOperations` returns after issuing unparks, but a woken thread may not have been scheduled yet. The load-bearing assertions (no admission at :343-344 — sound, since exit from the loop requires `freezeRequests == 0`, impossible with two freezes registered; completion after release at :372-373) hold regardless, so this weakens only the test's *stated* story, not its regression power for the contract.

Where checkpoint identity matters, assert the blocking/throwing site via stack-trace inspection (the liveness test's `failWedged` already demonstrates the technique at :141-159). Suggestion only — the contract-level assertions are correctly one-sided and none of this creates flake risk (every mis-observation path still converges to the asserted outcome).

### TQ11 — suggestion — no direct pin-balance assertion on the in-freezer throw path

**Location:** `FreezerGateTest.java:228-283` (`parkedSchemaCommitWakesAndThrowsWhenOperatorFreezeArrives`).

The five-path matrix asserts pin balance directly for (a) probe rejection (:129-130), (b) write-lock abort (:203-204 via `pinsAfter`), (c)/(d)/(e) in `pinBalanceAcrossCommitOutcomesOnRecycledPooledSessions` (:464-513, each with the recycled-borrower DDL health probe). The **in-freezer gate throw** (checkpoints 3/4 — path D4 in the enumeration above) checks balance only indirectly: a leaked pin would make the pooled recycle fail inside `pooled.close()` (:249), missing the `pooledClosed` latch. That detection is real but unlabeled — the failure would read as a latch timeout, not a pin leak. Capture `pinCount(pooled)` in the worker after the caught throw (as the cp2 test does) and assert it. One-line symmetry fix.

---

## No-issue verifications (null verdicts)

- **N1 — old `freezeWriteOperations` callers all migrated.** The signature change is source-breaking by design; grep finds exactly the four registration sites, all kind-labeled correctly: `doSynch` TRANSIENT (`AbstractStorage:5620-5621` — with the freeze()-nesting note), both `freeze()` arms OPERATOR (:5798-5803), incremental-backup WAL copy and segment cut TRANSIENT (`DiskStorage:360-361`, `:1255-1256`). No other production or test caller of the old shape exists.
- **N2 — unarmed entrants byte-identical.** `startOperation()` delegates with `(false, null)` (:60-62); both new gate `if`s short-circuit on `schemaArmed` without reading the kind counter (:105, :118). The loop body is otherwise the pre-commit code verbatim (diff-verified). The internal wrappers (`calculateInsideAtomicOperation`, `executeInsideAtomicOperation`, component ops) all route through the preserved single-arg `startToApplyOperations` (`AtomicOperationsManager:128-130`, call sites :170/:197).
- **N3 — TRANSIENT `freezeOperations` byte-identical.** Kind checks bracket the historical body; a TRANSIENT registration executes exactly the old id/increment/supplier/drain sequence.
- **N4 — V1/V8 orderings sound as one-sided guarantees.** Arm: kind++ (:165) precedes count++ (:169), cut after both (:191); entrant re-check reads counters *after* enqueue (:114→:118). Either the entrant's node predates the cut (woken) or its re-check postdates the fully published counters (throws). Retract: count−− (:248) precedes kind−− (:259) — an entrant observing the count still observes the kind. The unavoidable false-positive window (kind visible after count retracted) is documented in the counter javadoc (:26-35) and explicitly excluded from test pinning (test-class javadoc :24-27). AtomicInteger linearizability supplies the needed same-variable read ordering; no cross-variable fence is required beyond program order of the RMWs.
- **N5 — gate-throw accounting balanced.** Paths A2/A4: the entry (or loop-bottom) increment is always balanced by the :101 decrement before either gate can throw; depth untouched; the abandoned waiting-list node holds only a thread reference and is detached by the next cut (its unpark of a non-parked/terminated thread is a specified no-op).
- **N6 — herd wake bounded.** Each woken data entrant executes exactly one loop iteration (increment, decrement, re-enqueue, re-park) per cut; the cut detaches a finite generation, so the unpark walk terminates; the arm's drain loop tolerates the transient count flicker. At most the concurrently parked committers wake once per operator engagement — as the Q-B4 comment states.
- **N7 — `cutWaitingList` monitor is deadlock-free and bounded.** Leaf lock (nothing acquired inside); enqueuers never take it; the `head == null` yield-spin inside the monitor waits only for an in-flight enqueuer's two plain stores; the link-latch waits are bounded by the same argument (every node up to the captured tail has a CAS-winning enqueuer obligated to link and count down). The single-element copy semantics (benign duplicate unpark, waiter always re-enqueues before re-parking) are unchanged and now documented. The cross-generation wedge analysis in the javadoc matches the code's tail-before-head capture exactly.
- **N8 — kind resolution on release is double-protected.** The id-keyed remove makes a second release of the same operator id resolve TRANSIENT (no second kind decrement); the floor then absorbs the count underflow. The `-1` sentinel is unguarded by id but paired 1:1 with `freeze()` by construction; a bogus extra `-1` hits both floors and logs. Pinned by `doubleReleaseFloorsCountersAndGateStillArms` — including the critical *re-arm* assertion (:377-385: a genuine freeze after the botched releases still gates a schema commit, then disarms cleanly), which is exactly the silent-disarm outage class the guard exists for. (Detail: the id-keyed double release in that test exercises only the `freezeRequests` floor — the second call resolves TRANSIENT — while the `-1` shape exercises the operator floor; combined coverage is complete.)
- **N9 — `decrementToFloor` correct.** Single CAS-RMW per attempt, no decrement-then-correct write that could wipe a concurrent increment; `-1` sentinel cleanly distinguishes underflow from a legitimate decrement to 0, so the `requests == 0` cleanup/cut cannot fire on a double release. Error logs (not throws) are right for decrements reachable from transient-release finallys.
- **N10 — pin/clear single-owner pairing complete.** All six escape classes enumerated (table D); exactly one clear per pin on each; the nested-try placement (not the outer try) is load-bearing and correctly excludes the pre-pin probe throw; `applyCommitOperations`'s old clear site now carries the explaining comment (:3231-3235). The change *fixes* a pre-existing leak (D2). `rebuildThreadLocalSchemaSnapshot`'s held-pin requirement remains satisfied (the pin spans the whole commit).
- **N11 — no deadlock via transient-freeze releasers.** A schema commit CAN park behind a TRANSIENT freeze holding all four locks (checkpoint 2's predicate is operator-only — by design; the park is bounded by the quiesce body per the `FreezeKind` contract). Verified that no transient body blocks on anything the parked committer holds: `doSynch` (:5615-5655) touches index-engine flushes, `flushAllData`, WAL only; the two `DiskStorage` bodies (:362-368, :1257-1270) are WAL-only. `synch()` and `freeze()` take `stateLock.readLock` *before* registering (:5585, :5782), so they cannot even register while the committer holds the write lock — which also validates the layered test's manager-level drive and its "cannot engage mid-window by construction" comment (:231-234).
- **N12 — checkpoint (2) usage conforms to the primitive's contract.** The predicate (`isOperatorFreezeActive`) is a single atomic read — cheap, non-throwing, never touches the lock; the Step-4 BG6 fix (phase-2 try/catch releasing the bit on a predicate throw) is present in the landed `ScalableRWLock` (:727-767), so even a hypothetical throwing predicate cannot leak the bit. On abort, the two metadata locks unwind through `commitSchemaCarry`'s finallys (:3300-3305) and the pin through D3. The 1 ms poll constant's javadoc correctly identifies it as the phase-1 bound only.
- **N13 — gate exception type/message correct.** `ModificationOperationProhibitedException` is a `HighLevelException`; `moveToErrorStateIfNeeded` (:5574-5581) therefore never error-states the storage on a gate abort. The message names the storage and is disjoint from the legacy supplier's wording; both directions pinned (`GATE_MESSAGE_FRAGMENT` containment in five tests; non-leakage onto the legacy path at :407-409).
- **N14 — the shared probe/factory helpers prevent drift as claimed.** All four checkpoints read `operatorFreezeRequests` (two via `isOperatorFreezeActive` delegation, two directly in the freezer — same counter); all four throw through `operatorFreezeGateException` (two directly, two via the threaded supplier `this::operatorFreezeGateException` at :6655-6656).
- **N15 — `MetadataDefault` accessor is safe and appropriately scoped.** Production-unused (grep: only the two test files); `public` is *required* (test lives in `storage.impl.local`, class in `metadata` — package-private would not compile); javadoc marks it test-only with the failure semantics of both imbalance directions. Cross-thread reads in the tests are ordered: worker-side reads published via `AtomicReference.set`/`CountDownLatch.countDown` before main-side reads (cp2 :167→:198-204); main-side reads follow `activateOnCurrentThread` on sessions whose worker use is join/latch-ordered.
- **N16 — five-path pin matrix genuinely asserts balance on recycled pooled sessions.** Paths (a),(b): before/after capture or explicit 0; paths (c),(d),(e): explicit 0 plus `assertRecycledBorrowRunsDdl` (:94-108), which asserts 0 *at recycle time*, recycles, re-borrows, and runs real DDL — a leaked pin trips the 0-assert or the recycle's force-clear guard; a double clear (negative count) poisons the next pin and fails the DDL. The (c) data-conflict path matters because the pin is unconditional (:2536) — data commits share the pairing. Matrix is sound (residual: TQ11's checkpoint-3 symmetry gap).
- **N17 — liveness test is CI-safe.** Fixed 5 s churn; healthy joins return in ms (churn threads exit at the flag; the last paired release's cut unparks all waiters — every parked data thread observed some freeze whose release chain ends in a 1→0 cut that includes its node, since cuts are the only node-removal sites); 120 s timeout with headroom even for several sequential 15 s wedge-joins before `failWedged` fires at the *first* wedged thread; daemon threads + interrupt-only-churn-threads failure hygiene (with the correct rationale for leaving parked data daemons alone, :122-127); wedge diagnostics include the stack and all thread states. Thread counts scale as `procs/2` bounded below by 3/4 — sane on both 2-core and many-core CI. The final `isOperatorFreezeActive` assertion (:135-136) is race-free: it runs after all churn threads (each freeze/release-paired) have joined.
- **N18 — liveness test pins the right defect.** With cutters unserialized, the tail-before-head cross-generation capture wedges a cutter on a link latch (the class javadoc's mechanism matches `cutWaitingList`'s code line-for-line); the churn (arm-cut per registration, release-cut per 1→0) plus continuous enqueue traffic is exactly the two-cutter interleaving generator; failure mode is a bounded join + diagnostics, not a fork hang. The rejected wake-only-walk alternative and its livelock mode are documented in both the code comment and the test javadoc, consistently.
- **N19 — remaining gate tests are flake-contained.** Every latch await and join is timeout-bounded; `awaitThreadParked` mis-observations (TQ10) converge to the asserted outcome in all cases (freezes make early main-thread progression harmless — commits cannot complete, and completion-after-release is separately asserted); `@After` joins-or-fails all spawned workers with interrupt; all worker freezes are released in finallys on every assertion-failure path (traced per test), so a failing test cannot wedge its successors in the fork.
- **N20 — batched residual fixes are sound.** `DatabaseSessionEmbeddedPooled`: comment-only, aligns wording with the internalClose atomic-teardown-claim mechanism it describes. `MetadataWriteMutex`: both release warn paths now identify the *requesting* session (identity hash + db name) distinct from `describeHolder()`'s holder-side identity — the previously ambiguous "for ordinal N" messages were undiagnosable for stale-releaser triage; args in scope, `%08X`/`%s`/`%d` specifiers match, and `getDatabaseName()` on a tearing-down session reads a stored field (no throw path).

## Hypothesis log

| # | Hypothesis | Method | Outcome |
|---|-----------|--------|---------|
| H1 | Old callers of the changed freezer APIs missed | grep all `freezeWriteOperations`/`startToApplyOperations`/`startOperation` call sites | Rejected — all migrated or intentionally preserved (N1, N2); found the dead `startTxCommit` overload instead (CQ7) |
| H2 | Armed gate throws unbalance `operationsCount`/depth | path enumeration A2/A4 | Rejected (N5) |
| H3 | `operatorFreezeIds` bookkeeping leaks or mis-resolves | trace all three touch points + storage freeze/release pairing | **Confirmed leak** on the `-1` pairing (BG7); mis-resolution impossible (monotonic ids) |
| H4 | Data-commit semantics not byte-for-byte under the new cut | interleaving construction: parked entrant + throw-mode supplier + arm cut | **Confirmed deviation** (BG8); park-mode re-park path clean (N6) |
| H5 | Schema commit parked behind TRANSIENT freeze holding 4 locks deadlocks the releaser | audit all three transient bodies for lock acquisition | Rejected (N11) |
| H6 | `synchronized` cut introduces a lock cycle or unbounded in-monitor wait | leaf-lock + enqueuer-progress analysis | Rejected (N7) |
| H7 | Release-side kind/count retract window breaks the gate guarantee | V8 ordering analysis | Rejected — one-sided by design, documented, not test-pinned (N4) |
| H8 | Pin/clear pairing double-clears or leaks on some escape | six-path enumeration (table D) | Rejected; change fixes a pre-existing leak (N10) |
| H9 | Double-release test fails to exercise the operator-counter floor | trace kind resolution on second id-keyed release | Partially confirmed — id-keyed shape resolves TRANSIENT, but the `-1` shape covers the operator floor; combined coverage complete (N8) |
| H10 | Liveness test can hang or flake CI | timing/joins/daemon/interrupt analysis + last-cut wakeup argument | Rejected (N17) |
| H11 | Gate tests can hang the fork on total gate regression | main-thread commit path trace | Confirmed for three tests — bounded-failure gap (TQ9) |
| H12 | cp2/herd observations under-determine checkpoint/wake identity | `awaitThreadParked` semantics analysis | Confirmed as coverage-precision (not correctness) gaps (TQ10) |
| H13 | `MetadataDefault` accessor unsafely published or production-reachable | grep + happens-before trace of test reads | Rejected (N15) |
| H14 | Spurious-wakeup envelope makes BG8 a non-change | `LockSupport.park` spec check | Partially accepted — folded into BG8 as severity mitigation, not dismissal |

**Verdict:** no blockers. Two should-fix items (BG7 leak; BG8 contract/behavior decision + pin), six suggestions. The core gate design — checkpoint placement, ordering discipline, accounting balance, the single-cutter fix, and the two test files' failure-mode engineering — verifies clean against the charter's decision criteria.
