# Concurrency review — Track 7 Step 5, iteration 1

**Artifact:** commit `736cab68ec` ("Gate schema commits against operator freezes"), branch
`transactional-schema` (HEAD at review time `e34daacf8c`; `git diff 736cab68ec e34daacf8c -- core/`
is empty, so all line numbers cite the commit exactly).
**Perspective:** concurrency, design-conformance-critical. Read-only; no Maven run (test execution
owned by another thread).
**Spec of record:** `track-7-design-drafts.md` Draft B §B.2 as amended — pass-14 CN10
four-checkpoint invariant, round-2 CN19 checkpoint-2 primitive, V1/V8 mandatory orderings, Q-B4
herd pins, CS14/CN23/CS18 guard pins, Q-B2 taxonomy constraints (nesting tolerance,
`release()`→OPERATOR mapping) — plus the recorded deviation in `plan/track-7.md` Step 5 episode
(`cutWaitingList` made `synchronized`; walk-only alternative rejected as proven livelock).

Charter: (1) V1/V8 orderings in the real code; (2) the synchronized-cut deviation's new blocking
edges; (3) exhaustive operator-freeze-engagement timings vs the four checkpoints, data commits
byte-for-byte; (4) nesting tolerance and double-release; (5) retract-window one-sidedness not
test-pinned; (6) test quality (8 gate tests + liveness stress).

---

## 0. Code map (verified by reading every in-scope file at the commit)

| Seam | Location |
|---|---|
| `FreezeKind` taxonomy | `FreezeKind.java` (new, whole file) |
| `operatorFreezeRequests` counter + one-sided-guarantee javadoc | `OperationsFreezer.java:36` |
| `operatorFreezeIds` id→kind release mapping | `OperationsFreezer.java:43` |
| Unarmed `startOperation()` delegate | `OperationsFreezer.java:58-60` |
| Armed `startOperation(boolean, Supplier)` | `OperationsFreezer.java:91-134` |
| Checkpoint (3) loop-top gate (count re-balanced at `:100`, before depth) | `OperationsFreezer.java:105-107` |
| Enqueue → checkpoint (4) park-decision re-check → park guard → park | `OperationsFreezer.java:113`, `:118-120`, `:122-123` |
| Post-wake `operationsCount.increment()` (re-balanced next iteration) | `OperationsFreezer.java:126` |
| `freezeOperations(kind, supplier)`: op++ (`:165`), ids.add (`:166`), fr++ (`:169`), supplier put (`:171-173`), operator cut-and-unpark (`:191-195`), drain spin (`:198-200`) | `OperationsFreezer.java:159-203` |
| `isOperatorFreezeActive()` single kind probe | `OperationsFreezer.java:210` |
| `releaseOperations`: kind resolve (`:232-238`), V8 fr-- (`:248`), op-- (`:259`), zero-cut (`:270-284`) | `OperationsFreezer.java:231-289` |
| `decrementToFloor` CAS-floor | `OperationsFreezer.java:292-302` |
| Lock-free enqueue (tail CAS, `next` store, latch countdown) | `WaitingList.java:12-28` |
| `synchronized cutWaitingList` + single-cutter contract javadoc | `WaitingList.java:37-53` |
| Cut protocol: tail read `:55`, head read `:56`, head==tail copy `:70-72`, head CAS `:73`, latch waits `:76-81`, terminal copy `:84` | `WaitingList.java:53-89` |
| Armed `startToApplyOperations` overload | `AtomicOperationsManager.java:140-142`; unarmed delegate `:128-129`; internal wrappers stay unarmed `:170`, `:197` |
| Checkpoint (1) entry probe (above the pin) | `AbstractStorage.java:2526-2534` |
| Pin `:2536`, nested single-owner try `:2547`, sole clear `:2576` | `AbstractStorage.java:2536-2576` |
| Deleted-clear tombstone comment in `applyCommitOperations` finally | `AbstractStorage.java:3230-3234` |
| Checkpoint (2) `exclusiveLockWithAbort` inside schema→IM lock nest | `AbstractStorage.java:3268-3285`; unlock pairing `:3300` (`writeLock().unlock()` → `exclusiveUnlock`, `ScalableRWLock.java:220-221`) |
| Armed `startTxCommit(atomicOperation, schemaContext != null)` — outside the rollback-paired try | `AbstractStorage.java:2789` |
| `doSynch` TRANSIENT (+nesting comment) | `AbstractStorage.java:5616-5621`, release `:5653` |
| `freeze()` both arms OPERATOR | `AbstractStorage.java:5792-5803`; `release()` `-1` sentinel `:5848-5850` |
| Shared kind probe / factory / poll constant | `AbstractStorage.java:6659-6692` (`OPERATOR_FREEZE_ABORT_POLL_NANOS` = 1 ms) |
| `DiskStorage` backup sites TRANSIENT | `DiskStorage.java:358-361`, `:1253-1256` |
| Tests | `FreezerGateTest.java` (8 tests at `:116/:148/:228/:286/:357/:395/:422/:464`), `OperationsFreezerLivenessTest.java` |

The `MetadataWriteMutex`/`DatabaseSessionEmbeddedPooled` hunks are log/comment wording only;
`MetadataDefault.getThreadLocalSchemaSnapshotPinCount` (`:95-101`) is a test-only plain-int
accessor. Concurrency-inert. The `freezeWriteOperations` signature change forces every caller to
name a kind — the compiler makes the four-site mapping exhaustive; grep confirms exactly the four
production registration sites and no other `startOperation` caller than
`startToApplyOperations:142`.

---

## 1. V1/V8 orderings in the real code (charter 1)

**Decision criteria:** (a) op++ strictly before fr++ on the OPERATOR arm; (b) cut strictly after
both increments; (c) entrant kind re-check strictly after `addThreadInWaitingList` returns;
(d) fr-- strictly before op-- on release; (e) explicit comments naming the invariants at each site.

Premises and traces:

1. **Arm order.** `freezeOperations` (`OperationsFreezer.java:159-169`): OPERATOR branch executes
   `operatorFreezeRequests.incrementAndGet()` (`:165`) then `operatorFreezeIds.add(id)` (`:166`)
   before `freezeRequests.incrementAndGet()` (`:169`). Both are `AtomicInteger` RMWs —
   sequentially consistent; program order = SC order. Comment "V1 arm ordering, part 1: publish
   the kind BEFORE the count" present at `:164`. **(a) holds.**
2. **Cut placement.** The cut block (`:175-195`) runs after both increments and after the
   supplier put; comment "V1 arm ordering, part 2 … strictly AFTER both increments" at `:176-177`.
   The drain spin follows the cut (`:198`). **(b) holds.**
3. **Entrant re-check.** `:113` enqueue → `:118` kind read; comment "strictly AFTER the enqueue
   returned (V1 entrant ordering)" at `:115-117`. The tail CAS in `addThreadInWaitingList`
   (`WaitingList.java:17`) and the `:118` atomic read give the SC edge the dichotomy proof needs
   (see §3 case 10). **(c) holds.**
4. **Retract order.** `releaseOperations`: `decrementToFloor(freezeRequests)` (`:248`) before
   `decrementToFloor(operatorFreezeRequests)` (`:259`); comments "V8 retract ordering, part 1/2"
   at `:247`/`:257`. **(d) holds.**
5. **Comments.** Field javadoc (`:23-35`) states the one-sided guarantee and the accepted
   retract-window false positive; the arm/retract sites carry the V1/V8 tags. The design's
   "implementation MUST preserve them with explicit comments" pin is discharged. **(e) holds.**

**Verdict: conformant, no issue.**

---

## 2. The synchronized-cut deviation (charter 2)

**Decision criteria:** (a) enumerate every blocking edge the monitor introduces; (b) prove
latch-completing threads never need the monitor; (c) cutter-vs-cutter serialization bounded;
(d) no lock-order cycle with freezer counters or storage locks.

**New blocking edges introduced by `synchronized cutWaitingList` (`WaitingList.java:53`):**

- **E1 — cutter ↔ cutter (monitor).** Two cutter populations: the OPERATOR arm
  (`OperationsFreezer.java:191`, holding at most `stateLock.readLock` when reached via
  `AbstractStorage.freeze()`; no locks when reached via manager-level/test calls) and the
  release-side zero-cut (`:277`, holding `stateLock.readLock` when reached via `doSynch`'s
  finally, nothing when reached via `release()` or the backup finallys). All monitor waiters hold
  at most *shared* storage read locks; the monitor holder never acquires any lock (verified: the
  cut body touches only the two `AtomicReference`s and node latches; the unpark loop runs
  *outside* the monitor, in `OperationsFreezer.java:192-195`). Leaf-lock property holds.
- **E2 — cutter → in-flight enqueuer (latch, now held inside the monitor).** The cutter awaits
  `linkLatch` (`WaitingList.java:76`, `:81`) and the head==null yield loop (`:63-67`). The
  completing side is `addThreadInWaitingList` (`:12-28`): after winning the tail CAS the enqueuer
  performs two plain stores (`last.next = node; last.linkLatch.countDown()`) or, first-enqueue,
  `head.set(node)` — straight-line, allocation done before the loop, **no monitor, no lock, no
  park** between the CAS and the countdown. So (b) holds: no enqueuer ever needs the monitor the
  cutter holds — no wedge. The only defeater is JVM death between the CAS and the countdown,
  which wedges the cutter — but that residual is byte-for-byte pre-existing (HEAD's single cutter
  had the identical latch wait, just outside a monitor).

**Boundedness of a cut (c):** the traversal only awaits latches of nodes strictly before the
captured tail (`:76-81`; the loop exits when `node.next == tail` and the captured tail's own latch
is never awaited — the terminal copy at `:84` replaces it). Every such node has a successor whose
enqueuer already won its tail CAS, so each awaited countdown is an in-flight two-store completion.
The head==null yield loop (`:63-67`) is reachable only mid-first-ever-enqueue (after any cut, head
is swung to the captured tail and never null again). Hence each cut is bounded by (finite detached
generation) × (in-flight enqueuer store latency). A release-side cutter queued behind an
operator-arm cutter therefore waits a bounded time; symmetric for the reverse. Note the
release-side zero-cut (`fr == 0`) and an operator-arm cut (own freeze registered, `fr > 0`) cannot
be *simultaneously pending for long chains of each other*: while an arm-cutter's own freeze is
registered, no release can take `fr` to 0 except through the accepted double-release theft, so the
monitor convoy depth is small in practice and bounded regardless.

**Can a cutter block the release path long enough to matter?** `releaseOperations` reaches the
monitor only on the 1→0 transition (`:270-284`), after both counter decrements — the gate and the
park admission already see the release; only waiter *wakeup* is delayed, by one bounded cut.
`freezeOperations`' drain spin (`:198-200`) runs after its own cut and waits on `operationsCount`
only; parked/thrown entrants have already re-balanced the count (`:100`), so the monitor cannot
extend the drain. No amplification.

**Lock-order audit (d):** observed orders are `stateLock.readLock → WaitingList-monitor →
linkLatch-await` (freeze()/doSynch paths) and `∅ → monitor → latch` (release()/backup/tests). The
latch-completing side acquires nothing. The monitor holder acquires nothing. No cycle is
constructible; the freezer counters are lock-free and never awaited under the monitor.

**Cut-correctness under the monitor:** with cutters serialized, the (tail, head) capture reverts
to the historically sound single-cutter protocol; the head CAS at `:73` has no competing head
mutator (first-enqueue `head.set` is excluded by the `:63` null-check yield loop), so the
cross-generation backwards-swing defect is structurally excluded. The `while(true)` retry is now
effectively dead but harmless.

**Verdict: the deviation is sound; no new wedge, bounded serialization, leaf monitor. No issue.**
(The episode's "single-cutter contract" javadoc demanded by the deviation record is present at
`WaitingList.java:37-52` and at the arm-cut call site `OperationsFreezer.java:175-190`.)

---

## 3. The four checkpoints — exhaustive engagement-timing enumeration (charter 3)

**Decision criterion:** for every timing of operator-freeze engagement relative to an armed schema
commit's timeline, the commit must end in **throw** (the shared factory exception), never an
unbounded block or park; data commits must be semantically untouched.

Armed-commit timeline: probe (`AbstractStorage:2532`) → pin (`:2536`) → sort/atomic-op reads
(`:2548-2549`) → `commitSchemaCarry`: schema write lock (`:3268`) → IM lock (`:3270`) →
checkpoint (2) (`:3281`) → commit window → `startTxCommit(…, true)` (`:2789`) → freezer entry
(`OperationsFreezer:91`) → apply. Operator arm publishes op++ (t₃) → fr++ (t₄) → cut-tail-read
(t₅), all SC (§1).

| # | Engagement timing | Caught by | Trace / proof |
|---|---|---|---|
| 1 | Fully engaged before commit entry | **(1)** probe throws | `:2532-2533`; zero locks, zero pin — verified probe precedes `:2536` pin |
| 2 | Probe-to-pin window | **(2)** | commit holds nothing yet, so `freeze()`'s `stateLock.readLock` (`:5782`) is grantable; commit proceeds to `:3281`, phase-1 first abort check (`ScalableRWLock:699-701`) returns false → throw with schema+IM locks unwinding through `:3302-3306` finallys; write bit never acquired, so the `:3300` unlock finally is never entered (the inner try opens only after acquisition succeeds — no unlock-without-lock) |
| 3 | Pin-to-checkpoint-2 (metadata locks being taken) | **(2)** | identical to #2; the pin is cleared exactly once by the `:2576` nested finally |
| 4 | While commit queued in checkpoint-2 phase 1 | **(2)** | abort polled each `pollNanos` (1 ms, `:6666`) between `tryWriteLock` attempts |
| 5 | While commit drains readers in phase 2 (freeze thread acquired `readLock` *before* the write bit was set, op++ lands mid-drain) | **(2)** | the freeze-holding reader cannot release its readLock before registering (program order in `freeze()`), so the drain cannot complete before op++ is visible; per-iteration poll (`ScalableRWLock:717-722`) aborts. Deadlock check: the commit never incremented `operationsCount` (it is pre-`startOperation`), so the freeze's drain spin (`OperationsFreezer:198`) is not waiting on the commit — no cycle between the two spins |
| 6 | op++ lands exactly after the CN25 success-edge re-check (`ScalableRWLock:728-734`) | **(3)** backstop | production-unreachable for a *new* freeze: `freeze()` needs `stateLock.readLock`, refused while the write bit is held; the already-holding-readLock shape is case 5 (drain can't complete past that reader). Manager-level (test-only) engagement post-write-lock reaches `startOperation` and throws at `:105-107`, count re-balanced at `:100` before the throw — freezer accounting clean, the arm's drain is not blocked by the thrown entrant |
| 7 | In-window, pre-`startOperation` | **(3)** | same argument as #6 |
| 8 | Armed commit **parked behind a TRANSIENT** (all four locks held, sanctioned bounded park), operator freeze layers over | cut → **(3)** | arm cut (`:191-195`) unparks the node; wake path `:123→:126→:97→:100→:105` throws. Production `freeze()` cannot even engage here (readLock refused by the parked commit's write lock) — the gate is the backstop the design demands, and `FreezerGateTest:228` drives it at manager level |
| 9 | Between loop-top check and enqueue | **(4)** | `:118` reads op after `:113` enqueue |
| 10 | Between enqueue and park-guard/park | **(4)** or cut | dichotomy, airtight under SC: if `:118` read op==0, then t₂ < t₃ < t₅ and the entrant's tail-CAS t₁ < t₂ < t₅ ⇒ the node is at-or-before the cut's captured tail ⇒ the cut unparks it (or pre-sets the permit if it has not parked yet — `LockSupport` permit semantics make the park return immediately) ⇒ next iteration throws at (3). If `:118` read op>0 ⇒ (4) throws. No third case |
| 11 | Entrant already parked | cut → **(3)** | same as #8 |
| 12 | Release retract window (op>0 stale after fr contribution retracted) | any checkpoint, **spurious throw** | accepted CS14(b)/risk 2; one-sided by design; not test-pinned (§5) |

Every row ends in throw (rows 8/10/11 via the sanctioned park→cut-wake→throw sequence, bounded by
one wake). The throw sites' unwind is clean at all four checkpoints: (1) pre-pin/pre-lock; (2)
metadata locks unwind, write bit never held; (3)/(4) count re-balanced, depth untouched,
`startTxCommit` sits outside the rollback-paired try (`:2789` before the `:2790` try), so no undo
arm fires against unpublished structure and `endTxCommit` is never reached — the V5 discipline.

**Data commits byte-for-byte:** the parent diff shows `startOperation()`'s body moved verbatim
into the armed overload with only the two `schemaArmed && …` short-circuit blocks inserted
(`:105-107`, `:118-120`); with `schemaArmed == false` (the only value data paths can pass —
`startOperation():59`, `startToApplyOperations:129`, internal wrappers `:170/:197`) both blocks
are dead. Park admission (`:122`), throw-supplier order (`:109`), count/depth accounting —
unchanged. The `stateLock.readLock` data branch of `commit()` is untouched (`:2555-2562`). The
only observable data-path change is the snapshot-clear moving from `applyCommitOperations`'
finally (parent `:3208`, inside the readLock) to `commit()`'s nested finally (`:2576`, outside
it) — the CS15 single-owner move; `immutableCount` is session-thread-local during a commit, so
the reordering is inert. **Conformant.**

---

## 4. Nesting tolerance, release() sentinel, double-release (charter 4)

1. **`freeze()` nests `doSynch`'s TRANSIENT inside OPERATOR** (Q-B2 constraint 2). Trace:
   `freeze()` registers OPERATOR (op 0→1, fr 0→1, cut, drain) at `:5798/:5803`; `doSynch()`
   (`:5621`) registers TRANSIENT (fr 1→2, op stays 1, no cut — the cut is OPERATOR-arm-only);
   `doSynch` finally releases its retained id (`:5653`): `operatorFreezeIds.remove(id)` misses →
   TRANSIENT → fr 2→1, op untouched, `requests==1` → no cut. `release()` (`:5850`) passes `-1` →
   explicit OPERATOR mapping (`OperationsFreezer:233-235`): fr 1→0, op 1→0, zero-cut wakes
   everyone. Counters exact through the nesting; the `:5616-5620` comment documents it. ✔
2. **`release()` sentinel mapping** (Q-B2 constraint 3): explicit at `:232-235` with the site
   comment at `:5848-5849`. ✔ — but see **CN42**: the sentinel path never removes the
   registration's id from `operatorFreezeIds`, so every production `freeze()`/`release()` cycle
   leaks one entry.
3. **Double release, gate-disarm shape:** id-keyed double release of an operator id: first remove
   → OPERATOR (fr--, op--); second remove misses → TRANSIENT (fr floored at 0 → logged `:250-256`,
   op untouched). The one-shot `Set.remove` prevents a double op-decrement — strictly better than
   the design's minimum. Sentinel double release with **no** other freeze: both counters floored,
   logged, gate re-arms (pinned by `FreezerGateTest:357`). ✔
4. **Double release with another freeze active (quiesce theft, accepted risk 6):** fr trajectory
   (2→1→0, cut, freeze voided) is *identical to HEAD's* `decrementAndGet` — the guard neither
   fires (nothing goes negative) nor changes admission. Sentinel double release with a second
   OPERATOR freeze active additionally steals the op count (gate disarmed while freeze #2 runs) —
   same identity-less-counter family the design accepted with the per-id-ledger note; not worse
   than the agreed design, not re-filed. The guard adds only the negative-disarm protection it was
   specified to add. ✔
5. **CAS-floor shape** (CN23/CS18): `decrementToFloor` (`:292-302`) is a
   decrement-only-if-positive CAS loop returning −1 on floor; no `set(0)` corrective write exists
   anywhere, so a concurrent legitimate increment cannot be wiped. Underflow is **logged, never
   thrown** at both decrements (`:250`, `:260`) — the transient-release-finally masking rule
   honored. No lockstep assert exists (`:227-229` documents why). ✔

---

## 5. Retract-window one-sidedness (charter 5)

`FreezerGateTest`'s class javadoc (`:35-39`) explicitly records "Deliberately NOT pinned: the
absence of gate false-positives around the freeze-release instant." Audit of all 8 tests: none
runs a schema commit concurrently with a freeze release and asserts success; the post-release
assertions (`doubleReleaseFloorsCountersAndGateStillArms:387-392`,
`parkedSchemaCommit…:278-282`) run after single-threaded releases where op is deterministically 0.
The liveness test's final `assertFalse(isOperatorFreezeActive())` runs after all churn threads
joined — deterministic. **No test pins no-false-positives. Conformant.**

---

## 6. Test quality (charter 6)

**Would the tests fail on regression of what they pin?**

- `parkedSchemaCommitWakesAndThrows…` (`:228`): removing the operator-arm cut → the parked commit
  stays parked → `pooledClosed.await(10s)` fails. Removing checkpoint (3) alone → (4) catches on
  the re-loop (same exception) — the *pair* is pinned as a property, not per-site; acceptable.
- Herd test (`:286`): **genuinely proves no-admission** — any admitted data commit decrements the
  `committed` latch before the `assertEquals(2, …)` at `:322`, and an admitted-and-finished worker
  makes `awaitThreadParked` return TERMINATED, failing the re-park assertion at `:318`. It also
  pins the operator-release/transient-still-parked layering and final completion. Solid.
- `doubleRelease…` (`:357`): pins floor + re-arm + clean disarm, both release shapes. Solid.
- `throwModeFreeze…`/`dataCommitParksUnderTransient…`: pin the legacy supplier wording and the
  park-never-throw data semantics — the byte-for-byte contract's observable half. Solid.
- Pin-balance matrix: all five design paths covered ((a) `:116`, (b) `:148`, (c)/(d)/(e) `:464`)
  with the recycled-re-borrow DDL health probe (`assertRecycledBorrowRunsDdl:99`) — the CS15/CS19
  enforcement net. Cross-thread `pinCount` reads are same-thread or latch-ordered
  (`pinsAfter.set` on the worker at `:174`, read after `done.await` — safe on the plain int).
- **Gap — checkpoint (2) is not actually pinned** (`writeLockAbortThrowsWhenFreezeEngagesAfterProbe`,
  `:148`): the driver engages the freeze *before* the committer reaches the gated acquisition
  (stateLock uncontended), so with checkpoint (2) regressed to a plain
  `stateLock.writeLock().lock()` the committer acquires uncontended, proceeds to `startOperation`,
  and throws the *same* exception at checkpoint (3) with the pin still balanced — **the test stays
  green while the CN10 outage returns** (the outage needs a reader parked in the freezer holding
  `stateLock.readLock`, which makes the plain acquisition block forever). The design's Step-5 test
  pin demanded "freeze arriving during the `exclusiveLockWithAbort` wait → abort throw". Not
  driven. → **CN43**.
- Same class, lower stakes: `probeThrowsOnPreEngagedOperatorFreeze` cannot distinguish checkpoint
  (1) from (2) either (probe deleted ⇒ (2) throws, pin balanced by the nested finally, test
  green); regression there is benign (metadata-lock churn only), so suggestion-weight. → **CN44**.

**Liveness test flake-safety:** `@Test(timeout=120_000)` with 5 s churn + 15 s bounded joins.
Healthy run: after `running=false` each churn thread finishes one freeze/release pair (drain spins
bounded — a data entrant seeing fr>0 re-balances the count before parking, so the drain cannot
hang); the final 1→0 release's cut precedes no lost wakeup (an entrant enqueued after the cut
re-checks `freezeRequests` at `:122` and skips the park) — all threads exit promptly; joins are
near-instant, total ≪ 120 s. Wedged run: the *first* over-15 s join triggers `failWedged` with the
stack dump — the test fails fast with diagnostics instead of eating the 120 s budget, and the
finally interrupts wedged churn threads (the latch wait is interruptible, `WaitingListNode:22-32`)
so no freeze leaks into the fork. The 15 s bound is generous versus the ~5 s reproduction window
the episode reports. **Flake-safe; regression-sensitive (join-timeout, not fork-hang) — exactly
the deviation record's pin.** Cost note: an unconditional 5 s sleep in the unit suite — accepted.

---

## Hypothesis log

| # | Hypothesis | Verdict |
|---|---|---|
| H1 | An enqueuer needs the `WaitingList` monitor → cutter-latch wedge | **Refuted** — enqueue is lock-free straight-line stores (`WaitingList:12-28`) |
| H2 | Cutter's latch wait unbounded | **Refuted** for all awaited nodes (successor enqueuer past its CAS); JVM-death residual pre-existing at HEAD |
| H3 | Head CAS failure loop under the monitor | **Refuted** — only cutters mutate head post-first-enqueue; first-enqueue window covered by the `:63` yield loop |
| H4 | Release path starved behind arm-cutters in the monitor | **Refuted** — each cut bounded; zero-cut only delays wakeup, not counter visibility |
| H5 | Armed commit admitted with op>0 (reads fr==0 pre-fr++, op++ already done) | **Confirmed reachable, benign** — the commit *runs* (never blocks/parks); the freeze's drain waits for it exactly as HEAD's drain waits for any in-flight apply; the four-checkpoint invariant is block/park-freedom, not mutual exclusion |
| H6 | Deadlock: cp2 phase-2 drain vs `freeze()` holding readLock spinning in the freezer drain | **Refuted** — the commit never incremented `operationsCount`; the poll sees op++ and aborts (§3 case 5) |
| H7 | Sentinel double release with 2nd OPERATOR active disarms the gate | **Confirmed, accepted** — quiesce-theft family (risk 6), identical class to the agreed design, not worse |
| H8 | CAS-floor guard worsens quiesce theft | **Refuted** — fr trajectory identical to HEAD |
| H9 | Stale throw-supplier after `release(-1)` with other freezes active | **Pre-existing** — the zero-sweep logic is unchanged from HEAD |
| H10 | `operatorFreezeIds` grows without bound on the `freeze()`/`release()` pairing | **Confirmed defect** → CN42 |
| H11 | Checkpoint (2) regression invisible to the suite | **Confirmed gap** → CN43 (and CN44 for the probe) |
| H12 | Herd test can green while a data commit was admitted | **Refuted** — latch-count + TERMINATED-state double net |
| H13 | Liveness test exceeds its 120 s timeout on healthy overloaded CI | **Refuted** — first wedged join fails at 15 s; healthy joins near-instant |
| H14 | `writeLock().unlock()` mispairs with `exclusiveLockWithAbort` | **Refuted** — `WriteLockView.unlock` → `exclusiveUnlock` (`ScalableRWLock:220-221`) |
| H15 | A thrown armed entrant's abandoned node wedges a later cut | **Refuted** — its latch is only awaited once a successor enqueued (who counts it down); as captured tail it is copied, never awaited |
| H16 | Cut's duplicate unpark disturbs a thread that left the freezer | **Refuted** — benign under the LockSupport guard-loop discipline used at every park site |
| H17 | Armed call with null gate supplier | Real but caller-controlled NPE shape → CN45 (suggestion) |

---

## Spec-conformance summary

Four checkpoints installed at the amended-design seams with the CS19 nested-finally single-owner
clear and the deleted `applyCommitOperations` clear (tombstone comment `:3230-3234`); checkpoint
(2) uses the Step-4 CN19/CN25 primitive with the specified predicate and a justified 1 ms poll;
V1/V8 orderings implemented with the mandated comments; Q-B2's three taxonomy constraints
discharged (nesting comment at `doSynch`, sentinel mapping, four-site kind exhaustiveness enforced
by the signature); Q-B4 herd pinned with the required cut-site comment; CN23/CS18 guard pins all
honored (CAS-floor, log-not-throw, no lockstep assert); Q-B5 factory single and stable; the
one-sided guarantee deliberately un-pinned. The synchronized-cut deviation is sound, bounded, and
documented exactly as the episode records. Residual defects found are one slow leak and two
test-pinning gaps — nothing touching the gate's liveness or safety.

---

## Findings

### CN42 — `operatorFreezeIds` leaks one entry per production `freeze()`/`release()` cycle — **should-fix**

`freezeOperations` adds every OPERATOR registration's id to `operatorFreezeIds`
(`OperationsFreezer.java:166`) — including `AbstractStorage.freeze()`'s, which **discards the
returned id** (`AbstractStorage.java:5798/:5803`). The paired `release()` goes through the `-1`
sentinel (`:5850` → `OperationsFreezer:233-235`), which never touches the set; the `requests == 0`
sweep (`:270-284`) cleans only `freezeParametersIdMap`. Counterexample: every
`freeze(false)`/`release()` cycle permanently strands one `Long` in a storage-lifetime
`ConcurrentHashMap.newKeySet` — unbounded growth at operator-action rate (a 5-minute backup cron ≈
100 k entries/year). No correctness impact in production (ids are never reused and nobody holds
the leaked ids to release), but it is a genuine unbounded leak and a hygiene trap (the set no
longer mirrors active operator freezes). **Fix caution:** a naive "clear the set when
`operatorFreezeRequests` hits 0" sweep is racy against a concurrent arm (`op++` at `:165` precedes
`add(id)` at `:166`; a sweep interleaving between a 0-read and the add wipes a *live* id, whose
later id-keyed release then classifies TRANSIENT, leaks `op` upward and leaves the gate
permanently armed — worse than the leak). Safe fixes: retain the id at the `AbstractStorage` level
(e.g., a deque `freeze()` pushes and `release()` pops, passing the real id instead of `-1`), or
reverse the arm order to `add(id)`-then-`op++` and sweep only ids below a watermark under the
`op==0` guard. Needs a small regression test either way (set size bounded across N cycles).

### CN43 — checkpoint (2)'s abort mechanism is not pinned by any test; a plain-lock regression silently resurrects the CN10 outage — **should-fix (test-only)**

`writeLockAbortThrowsWhenFreezeEngagesAfterProbe` (`FreezerGateTest.java:148`) engages the freeze
while the committer is blocked on the *schema* lock and `stateLock` is uncontended; if
`AbstractStorage.java:3281-3284` regressed to `stateLock.writeLock().lock()`, the acquisition
succeeds instantly and checkpoint (3) throws the identical exception with the pin balanced — the
test (and the whole suite) stays green while the design's blocker scenario (armed commit blocked
on the write lock behind a freezer-parked reader, writer-preference read outage for the freeze's
whole duration) returns. The Step-5 design pin explicitly demanded a "freeze arriving during the
`exclusiveLockWithAbort` wait → abort throw" case. Strengthen the test with the missing
ingredient: park a data commit behind a TRANSIENT freeze so it holds `stateLock.readLock` (the
`parkedSchemaCommit…` scaffolding already builds this), then engage the operator freeze
(manager-level) and let the armed committer reach checkpoint (2) — with the primitive it aborts
within one poll; with a plain lock it hangs and the bounded `done.await` fails. Optionally assert
checkpoint attribution via the exception's stack (top frames in `commitSchemaCarry`, not
`startOperation`).

### CN44 — checkpoint (1)'s hoisted-probe placement is likewise unpinned — **suggestion**

If the probe (`AbstractStorage.java:2532-2534`) were deleted, `probeThrowsOnPreEngagedOperatorFreeze`
still greens (checkpoint (2) throws; the nested finally balances the pin). Regression is benign
(same outcome, extra metadata-lock churn), so suggestion only: a stack-frame attribution assert
(`commit` frame above `commitSchemaCarry` absent) or a white-box zero-pin-taken hook would pin the
"zero side effects before the pin" property the CS10 amendment bought.

### CN45 — armed `startOperation` with a null gate supplier fails as a bare NPE at the throw site — **suggestion**

`startOperation(true, null)` (`OperationsFreezer.java:105-107`, `:118-120`) NPEs only when an
operator freeze is active — a latent miswiring would surface as a confusing NPE inside the freezer
under exactly the outage condition the gate protects. Both current call sites thread the factory
correctly (`AbstractStorage:6654-6656`); a `schemaArmed && Objects.requireNonNull(schemaGate)`
guard (or an assert) at method entry would make the contract self-diagnosing. No behavior change.

**No blockers.** V1/V8, the four checkpoints, the synchronized-cut deviation, nesting/underflow
behavior, and data-commit invariance all verified conformant against the amended design.
