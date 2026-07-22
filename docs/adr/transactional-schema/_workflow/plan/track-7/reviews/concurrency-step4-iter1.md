# Concurrency review — Track 7 Step 4, iteration 1

**Artifact:** commit `449d1745c0` ("Add abort-predicate write acquisition to ScalableRWLock"),
branch `transactional-schema` (HEAD at review time `091267a708`; `git diff 449d1745c0 HEAD` on both
in-scope files is empty, so working-tree line numbers cite the commit exactly).
**Perspective:** concurrency, spec-conformance-critical. Read-only review; no Maven run (test
execution owned by another thread).
**Spec of record:** `docs/adr/transactional-schema/_workflow/track-7-design-drafts.md` —
§Amendments round 2 CN19 re-amendment (`:856-915`), §Pass-16 CN25 pin (`:1138`), Q-A3 pin (3)
interrupt semantics (carried into the CN19 amendment at `:897-899`); step definition
`plan/track-7.md` Step 4 (`:203-209`). Consumer contract note honored: the primitive returns
`false` on abort; the Step-5 caller throws — exception-neutrality is intentional and verified.

Charter scope: (1) phase-1 loop residual state + abort-check placement; (2) phase-1→phase-2
boundary vs reader admission; (3) phase-2 drain spin structure; (4) abort release completeness;
(5) CN25 re-check placement and reordering; (6) interrupt path; (7) test quality for the seven
concurrency shapes, incl. the corrected stress assertion.

---

## 0. Code map (verified by reading the whole class)

| Seam | Location (`ScalableRWLock.java`) |
|---|---|
| Reader admission (Dekker: `state.set(READING)` full-fence → `isWriteLocked()` check) | `:339-346` (`sharedLock`), `:452-462` (`sharedTryLock`), `:494-513` (`sharedTryLockNanos`) |
| Reader back-off spin (poll `isWriteLocked()` + `Thread.yield()`, no parking) | `:348-350` |
| Reference drain (`exclusiveLock`): array-cache protocol + yield spin | `:394-411` |
| `exclusiveUnlock` → `stampedLock.asWriteLock().unlock()` | `:424-432` |
| Timed-retry comparator (`exclusiveTryLockNanos`, releases bit on drain timeout) | `:590-620`, release at `:614` |
| **New:** `exclusiveLockWithAbort` javadoc | `:622-673` |
| **New:** `pollNanos` guard | `:675-677` |
| **New:** phase-1 loop (abort at loop top, timed `tryWriteLock`, interrupt catch) | `:681-702` |
| **New:** phase-2 array-cache block (verbatim copy of `:394-403`) | `:704-713` |
| **New:** phase-2 drain with per-iteration abort poll + release | `:715-726` |
| **New:** CN25 success-edge re-check | `:728-735` |
| Tests | `ScalableRWLockTest.java:275-625` (helpers `:275-297`, seven tests `:307-624`) |

**Byte-for-byte check (charter precondition):** the commit diff for `ScalableRWLock.java` contains
zero deletion lines — hunks are exactly the two imports (`BaseException`, `DatabaseException` at
`:26-27`, `BooleanSupplier` at `:37`) and the appended method after `exclusiveTryLockNanos`'s close.
All pre-existing methods are unchanged. The test file diff is likewise additive-only (five imports +
appended section). Confirmed.

Layering precedent for the `common.concur.lock` → `core.exception` imports: `AdaptiveLock` and
`OneEntryPerKeyLockManager` in the same package already import `BaseException`; both packages live
in the `core` Maven module. `BaseException.wrapException(exc, cause, (String) null)`
(`BaseException.java:39-64`) is null-safe for a non-`BaseException` cause (only `initCause` +
dbName-null path). `DatabaseException(String message)` exists (`DatabaseException.java:38`). No
issue.

---

## 1. Phase-1 loop — residual state and abort-check placement

**Decision criteria:** (a) a timed-out or interrupted `tryWriteLock` must leave no state that
blocks readers or later writers; (b) the abort flip must be detected within one `pollNanos` while
queued; (c) no path exits the loop holding partial state.

Premises:

1. `stampedLock.tryWriteLock(pollNanos, NANOSECONDS)` (`:687`) either returns a nonzero stamp
   (WBIT acquired), returns 0 on timeout, or throws `InterruptedException`. On timeout/interrupt
   StampedLock cancels its CLH node; cancelled nodes are unlinked/skipped by subsequent
   acquisitions. No writer-intent state survives a failed attempt — in particular
   `isWriteLocked()` (the only thing readers consult, `:344/:348/:457/:501`) stays false the
   whole time a candidate is queued.
2. Abort is checked at the loop top (`:683-685`) before every attempt. Latency enumeration:
   - flip before the check → detected immediately, `return false`, nothing ever held;
   - flip while parked, attempt times out → park bounded by `pollNanos`, next loop-top check
     detects: latency ≤ `pollNanos` + scheduling ε;
   - flip while parked, attempt *succeeds* → detection moves to phase 2: first drain iteration
     (`:717`) if any residual reader is READING, else the CN25 re-check (`:731`). Both are
     immediate (≤ one yield quantum). No interleaving exceeds one poll granularity — the CN19/CN25
     guarantee (ii) wording ("within one poll granularity while acquisition is in progress")
     holds exactly.
3. Interrupt exit: `stamp` is still 0 when the catch runs (`:688`), so no lock state exists to
   leak; see §6.
4. `pollNanos <= 0` is rejected (`:675-677`), which also forecloses the degenerate hard-spin
   (StampedLock treats a non-positive timeout as an instantaneous try).

**Verdict: no issue** (one latent hazard shared with phase 2 is deferred to §3/CN38: the abort
supplier itself throwing at `:683` is harmless here — nothing held — but not in phase 2).

## 2. Phase-1→phase-2 boundary — reader admission

**Decision criteria:** a QUEUED candidate blocks no readers; from the successful CAS onward new
readers are refused exactly as against `exclusiveLock`.

1. Readers never invoke any StampedLock acquisition method — they only read
   `stampedLock.isWriteLocked()` (all three reader entry points, `:344`, `:457`, `:501`, plus the
   back-off spin `:348`). A parked `tryWriteLock` waiter has not set WBIT, so `isWriteLocked()`
   is false and reader admission is completely unaffected. The spec's claim (drafts `:878-880`)
   is confirmed against the actual admission code, not just asserted.
2. The moment `tryWriteLock` succeeds, WBIT is set by CAS — the same acquire-fence write
   `stampedLock.writeLock()` performs in `exclusiveLock` (`:392`). The Dekker pair is therefore
   identical: a reader's `state.set(READING)` (full volatile set, StoreLoad — comment `:339-341`)
   either becomes visible to the writer's drain scan, or the reader observes WBIT and backs off
   to NOT_READING (`:345-350`). There is no third interleaving: the reader's full fence between
   its store and its load, and the writer's CAS between its store and its scan loads, make
   mutual invisibility impossible. Memory-ordering strength of `tryWriteLock(timeout)` equals
   `writeLock()` (both CAS the same `state` word); hypothesis H6 rejected.
3. Phase 2's array-cache block (`:704-713`) is a verbatim copy of `exclusiveLock`'s (`:394-403`),
   so the linearizability protocol vs. `addState()`'s invalidation (`addState:314-316`) is
   inherited unchanged: a reader registering after the array snapshot cannot enter (it would see
   WBIT), and a reader READING before WBIT was set is necessarily in the snapshot.

**Verdict: no issue.**

## 3. Phase-2 drain — spin structure

**Decision criteria:** abort polled at drain-iteration granularity; no busy-burn regression vs
`exclusiveLock`'s drain; no poll gap between drain end and the re-check.

1. Structure (`:715-726`): identical to `exclusiveLock`'s drain (`:408-411`) plus one
   `abort.getAsBoolean()` per inner-loop iteration, placed *before* the `Thread.yield()` — so an
   abort already pending when the drain first meets a READING state is caught before the first
   yield. Cost delta per iteration: one supplier call (pinned consumer: a single atomic read),
   strictly cheaper than the adjacent yield syscall. No busy-burn regression.
2. Poll-gap enumeration across the outer `for`: between finishing entry *i* and testing entry
   *i+1* there is no wait (straight-line loop advance), and between the last entry clearing and
   the CN25 re-check (`:731`) there is likewise straight-line code. The only waits are inside the
   inner `while`, every iteration of which polls. Exhaustive: no window longer than one yield
   quantum exists in phase 2.
3. Dead-reader-thread interaction: if a residual reader's thread dies mid-drain, the Cleaner's
   `CleanupAction.run` (`:163-170`) forces its state to NOT_READING — the drain exits, same as
   `exclusiveLock`. Inherited, unchanged.

**CN38 (should-fix) — a throwing abort supplier in phase 2 leaks the write bit permanently.**
Counterexample: the supplier at `:717` (or `:731`) throws any `RuntimeException` while WBIT is
held (acquired at `:687`). The exception propagates out of `exclusiveLockWithAbort` without
touching `unlock()`; the caller received neither `true` (so, per the contract at `:670-672`, it
must not call `exclusiveUnlock`) nor `false`. WBIT stays set forever: every subsequent
`sharedLock` spins in `:348-350` and every writer parks — a storage-wide, ownerless wedge, the
precise outage class this track exists to eliminate. Phase 1 (`:683`) is immune (nothing held).
Today's pinned consumer predicate (`operatorFreezeRequests > 0`, drafts `:885`) cannot throw, and
the javadoc constrains the predicate ("must be cheap… must not itself touch this lock", `:664-666`)
but does not forbid throwing — so this is latent, not live: should-fix, not blocker. Fix shape:
wrap the two phase-2 evaluations so any throw releases the bit before propagating (3-line
try/catch), or at minimum pin "must not throw" in the javadoc with the leak consequence named.

**Otherwise: no issue.**

## 4. Abort release — completeness, no strand, reusability

**Decision criteria:** the bit drops fully; every blocked party makes progress without a signal
the abort path could fail to send; the instance is reusable.

Release sites: `:721` (drain abort), `:732` (CN25 abort). Both are
`stampedLock.asWriteLock().unlock()` — the exact release `exclusiveUnlock` uses (`:431`).
Interleaving enumeration of every possible waiter at the release instant:

1. **Reader in the back-off spin** (`:348-350`): polls `isWriteLocked()` every yield — sees the
   drop, re-runs the outer Dekker loop, enters (or backs off again to a *new* writer, which is
   ordinary contention, not a strand). No parking channel exists anywhere on the reader side —
   there is no wakeup to lose, confirming the spec's claim structurally.
2. **Reader mid-Dekker** (between `state.set(READING)` and the `isWriteLocked` check): reads the
   bit before the drop → backs off into case 1; after → enters. Both progress.
3. **Queued plain writer** (`exclusiveLock` parked in `stampedLock.writeLock()`): StampedLock's
   own release signals the queue successor. Standard StampedLock behavior, not this code's
   responsibility to re-implement.
4. **Queued phase-1 abort-primitive waiter** (parked in `tryWriteLock(pollNanos)`): woken by the
   same StampedLock signal, or at worst retries at the `pollNanos` boundary.
5. **The aborting thread itself, re-calling the primitive:** no thread-local or instance state
   records the failed attempt (the only instance mutation possible is the `readersStateArrayRef`
   cache refresh at `:704-713`, which is the same idempotent protocol every exclusive method
   runs) — immediately reusable. Pinned by tests at `:352-355`, `:437-439`.

**Verdict: no issue.**

## 5. CN25 re-check — placement and reordering

**Decision criteria:** the re-check executes after drain completion (including the empty-drain
case) and before `return true`, and cannot be observably reordered before the drain's end.

1. Placement (`:728-735`): after the outer `for`, before `return true`; the empty-array /
   all-NOT_READING case falls through the loop straight into it — the zero-residual-readers
   window the CN25 pin names is covered, and the test at `:418-440` pins the exact two-poll
   shape (phase-1 loop top + re-check) deterministically.
2. Reordering: the drain's exit condition is computed from `readerState.get()` — volatile loads.
   Under the JMM a later load (the predicate's read of its volatile/atomic input, per the pinned
   consumer) cannot be hoisted above an earlier volatile load (LoadLoad after every volatile
   read, JSR-133 cookbook), and the loop exit is control- and data-dependent on those loads.
   Additionally the supplier is an opaque call unless the JIT inlines it, and even inlined it
   remains a volatile read behind volatile reads. The re-check is not reorderable before drain
   end. (Whether the predicate's *inputs* are read atomically is the Step-5 caller's obligation,
   per charter.)
3. Residual edge, enumerated for completeness: a flip occurring after the `:731` read returns
   false but before `return true` executes is missed by the primitive — this is exactly the
   residual edge the CN25 pin accepts ("the four-checkpoint invariant backstops the residual
   edge either way", drafts `:1138`). Not a defect; conformant.

**Verdict: no issue.**

## 6. Interrupt path

**Decision criteria:** flag restored exactly once; no lock state leaked; exception type/message
per pin; behavior sane for a pre-set flag.

1. The only interruptible wait is the phase-1 timed acquire (`:687`); the catch (`:688-701`)
   runs `Thread.currentThread().interrupt()` exactly once, then throws
   `wrapException(new DatabaseException("interrupted while acquiring the storage write lock…"),
   e, (String) null)`. At that point `stamp == 0` (the assignment never completed), so no lock
   state exists — nothing to leak, and no unlock is owed.
2. Pre-set flag at entry: `StampedLock.tryWriteLock(timeout)` checks `Thread.interrupted()`
   first (clearing the flag) and throws immediately even if the lock is free; the catch restores
   the flag — net effect: flag preserved, `DatabaseException` thrown. Consistent.
3. Interrupt during phase 2: the yield drain ignores it (yield neither consumes nor acts on the
   flag), identical to `exclusiveLock`'s drain, and the flag survives to the caller. The spec
   pins interrupt handling only for the timed acquire; the javadoc states the phase-2 behavior
   explicitly (`:659-661`). Conformant.
4. "Naming the holder/state" (drafts `:897-898`): StampedLock tracks no owner, so the message
   names the *state* ("queued against a holding writer / contending writers", `:696-698`) — the
   maximum available; acceptable reading of the pin, and the class javadoc already documents the
   ownerless design (`:275-281`).

**Verdict: no issue.** (Test coverage: `:503-543` pins flag, type, and message substring.)

## 7. Test quality — the seven concurrency shapes

Per-test regression analysis (would it fail if the pinned property regressed?):

| Test (`ScalableRWLockTest.java`) | Pins | Fails on regression? |
|---|---|---|
| `abortLockPlainRoundTripWithoutAbort` `:307` | success path, bit held, release, reuse | Yes — return-value/bit/reuse regressions all assert directly |
| `abortWhileQueuedBehindWriterReturnsFalsePromptly` `:329` | phase-1 abort: prompt false, holder untouched, reuse | Yes — a missing loop-top abort check leaves the waiter alive at `join(5000)`; residual state fails the reuse assert |
| `abortDuringReaderDrainReleasesBitAndReadersProceed` `:362` | drain abort: bit released, fresh reader admitted | Yes — missing drain poll → waiter alive; missing release → `isWriteLocked`/`sharedTryLock` asserts fail |
| `abortAtAcquisitionSuccessEdgeReturnsFalse` `:418` | CN25 re-check, exact two-poll shape | Yes — removing the re-check returns true with one poll; the `assertEquals(2, calls)` also detects accidental extra polls (shape drift). Determinism verified: fresh lock, empty `readersStateList`, single thread, uncontended CAS cannot fail spuriously, Cleaner touches only other instances' lists |
| `writerPreferenceRefusesNewReadersWhileBitHeld` `:442` | new readers refused from bit-set through release | Partially — see CN40 |
| `interruptDuringPhaseOneRestoresFlagAndThrows` `:503` | flag restored, type + message | Yes — asserts flag, type, substring independently |
| `stressReadersAgainstAbortingWriterLeavesLockUsable` `:545` | termination, outcome partition, no spurious false, usability after | Partially — see CN39 |

Timing soundness: every wait is bounded (`awaitParked`/`awaitCondition` 5 s spins, `join(5000)`,
latch awaits 5 s, stress joins 50 s under a 60 s timeout); parked-state detection is sound because
the waiters' only park sites are the timed acquire (TIMED_WAITING ≥ 99% duty cycle at 5/100 ms
polls). No unbounded sleep, no fixed-sleep race. No flake vector found beyond ordinary CI
scheduling generosity, which the margins absorb.

**Stress assertion analysis (charter question).** `successes + aborts == writerIterations` plus
`successes >= writerIterations/2` encodes: even (never-aborting) attempts possess no `false`
return path — the method must never spuriously fail on contention alone, which is precisely the
contract half the CN19 reversal demanded, and a regression reintroducing a timeout-false path
(e.g., delegating to `exclusiveTryLockNanos`) fails it deterministically (100 even attempts, any
false trips the ≥100 bound). As a *contract encoding* it is sound. What it does **not** encode is
the liveness half — CN39.

**CN39 (should-fix) — the stress test cannot fail on a bounded-acquisition (CN19 liveness)
regression, though the step's test matrix pins that property.** Step 4's pinned test list
(`plan/track-7.md:207`) requires "bounded acquisition under sustained concurrent readers (writer
completes in ≤ max residual reader residence — no starvation, the CN19 property)", and the stress
test's comment claims that role ("the bounded-acquisition guarantee under sustained readers",
`:600-601`). Counterexample: regress the implementation to the pass-14 retry loop
(`exclusiveTryLockNanos` + retry — the exact shape CN19 reversed). The 4×2000 reader iterations
are tiny (one `onSpinWait` residence, far below any drain timeout) and *finite*: readers finish
in seconds regardless of writer progress, after which the writer completes all 200 iterations
uncontended; every assertion passes. Even total writer starvation-while-readers-run passes,
because the reader load drains on its own. A deterministic distinguishing shape exists: two-plus
staggered readers maintaining continuous read coverage with residence ≫ the poll interval
(e.g., 20 ms holds overlapping so coverage never gaps), cycling until the writer reports done —
the single-acquisition primitive completes within ~one max residence (new readers refused the
moment WBIT sets), while the retry-loop regression provably never completes (each timeout
releases the bit, the backed-off staggered reader slips in, coverage never clears). Recommend
adding that test (test-only change; product code untouched).

**CN40 (suggestion) — `writerPreferenceRefusesNewReadersWhileBitHeld` samples reader refusal
once per phase.** The single `assertFalse(sharedTryLock())` at `:424` (drain in progress) and
`:431` (post-completion) each sample one instant; a hypothetical inter-attempt-release regression
holds the bit for all but microsecond windows, so both single samples (and, honestly, any
black-box sampling) pass with overwhelming probability. The refusal-*while-drain-in-progress*
assertion is still valuable (it pins that the bit is up before the residual reader leaves — the
writer-preference edge `exclusiveTryLockNanos` also has), but the no-release-window property
itself is structurally guaranteed by code shape (single acquisition at `:687`, releases only at
`:721/:732/caller`) and is effectively code-review-enforced, not test-enforced. Optionally add a
short bounded sampling loop during the drain window for cheap extra sensitivity; no deterministic
black-box pin exists, so this stays a suggestion. (CN39's staggered-reader test, once added,
covers the consequence of a release window — slip-in admission — deterministically, which is the
stronger net.)

**CN41 (suggestion, documentation-only) — phase-1 forfeits queue position on every poll; javadoc
silent.** Each timed-out `tryWriteLock` cancels its CLH node and the retry enqueues at the tail,
so under *sustained plain-writer contention* the abort-capable waiter is unfair and phase 1 is
unbounded in theory. This shape is spec-pinned (drafts `:877-878` prescribe exactly this loop),
the pinned consumer's lock (`stateLock`) has rare writers (freeze/backup/close/serialized schema
commits), and the CN19 bounded-acquisition guarantee is scoped to sustained *readers* — so this
is conformant and accepted, but the javadoc's `pollNanos` note (`:667-669`) could name the
fairness trade to keep a future consumer from using it on a writer-hot lock. No code change.

---

## Hypothesis log

| # | Hypothesis | Disposition |
|---|---|---|
| H1 | A queued (unacquired) stamped writer blocks readers | Rejected — readers only read `isWriteLocked()` (`:344/:348/:457/:501`); WBIT unset while queued |
| H2 | Timed-out/interrupted `tryWriteLock` leaves residual node blocking later acquisitions | Rejected — StampedLock cancels and unlinks; pinned behaviorally by the reuse asserts (`:352-355`) |
| H3 | Abort flip missed > one `pollNanos` in phase 1 | Rejected — enumeration §1.2; park bounded, loop-top re-check |
| H4 | CN25 re-check reorderable before drain end | Rejected — volatile drain loads + LoadLoad ordering + opaque call (§5.2) |
| H5 | Abort release loses a wakeup | Rejected — no reader parking channel exists; StampedLock signals its own queue (§4) |
| H6 | `tryWriteLock(timeout)` weaker memory semantics than `writeLock()` | Rejected — same CAS on the same state word |
| H7 | Post-abort reuse corrupts the `readersStateArrayRef` cache | Rejected — identical idempotent protocol as all existing exclusive methods |
| H8 | Interrupt path leaks lock state or double-restores the flag | Rejected — `stamp == 0` at throw; single `interrupt()` (§6) |
| H9 | Success-edge test nondeterministic (extra/fewer polls) | Rejected — single-threaded, empty reader list, uncontended CAS, Cleaner non-interfering (§7 table) |
| H10 | Cross-thread `exclusiveUnlock` in tests breaks StampedLock | Rejected — ownerless by design; and the unlocking thread is the holder in every test anyway |
| H11 | Predicate throw in phase 2 wedges the lock | **Confirmed — CN38** |
| H12 | Stress assertion masks a liveness regression | **Partially confirmed — the assertion itself is a sound spurious-false pin; the test's finite reader load is the gap — CN39** |
| H13 | `awaitParked` can observe the wrong park site | Rejected — waiter threads execute only `exclusiveLockWithAbort`; sole park site is the timed acquire |
| H14 | Main-thread ThreadLocal reader entries leak across tests | Rejected — `entry` is per-instance; every test uses a fresh lock |

## Spec-conformance summary

CN19 phase 1 (tryWriteLock(pollNanos) loop, abort between attempts, queued blocks no readers) —
conformant, verified against reader admission code, not just the design's claim. CN19 phase 2
(bit ONCE, held; writer preference; abort per drain iteration; full release + `false` on abort;
reusable; no strand) — conformant. CN25 (re-check after bit acquisition/drain completion, before
`return true`, empty-drain case included) — conformant. Interrupt pin (flag restored,
`DatabaseException` naming state) — conformant. Exception-neutrality (returns `false`; Step-5
caller throws) — conformant. Byte-for-byte-unchanged requirement — verified. Guarantee pair —
holds per the interleaving enumerations above, with the single latent exception CN38 (throwing
predicate) which is outside the pinned consumer's envelope.

## Findings

| ID | Severity | Location | Summary |
|---|---|---|---|
| CN38 | should-fix | `ScalableRWLock.java:717, :731` | A throwing abort supplier in phase 2 propagates with WBIT held — permanent, ownerless storage-wide wedge; wrap phase-2 predicate evaluation (release-then-rethrow) or pin "must not throw" in the javadoc. Latent (pinned Step-5 predicate cannot throw). |
| CN39 | should-fix | `ScalableRWLockTest.java:545-624` | Stress test cannot fail on a CN19 bounded-acquisition regression (finite, tiny-residence reader load drains on its own; even the reversed retry-loop shape passes); the step's test matrix pins that property — add a staggered continuous-coverage reader test (deterministic distinguisher). |
| CN40 | suggestion | `ScalableRWLockTest.java:424, :431` | Writer-preference refusal sampled once per phase; inter-attempt-release regressions are effectively undetectable black-box — property is code-shape-enforced; optional bounded sampling loop, and CN39's test covers the consequence deterministically. |
| CN41 | suggestion | `ScalableRWLock.java:667-669` (javadoc) | Phase-1 retry re-enqueues at CLH tail each poll — unfair/unbounded under sustained plain-writer contention; spec-pinned shape, fine for `stateLock`, but worth a javadoc caution for future consumers. |

No blockers. The primitive as implemented conforms to the CN19 re-amendment, the CN25 pin, and
the interrupt pin; both charter guarantees survive exhaustive interleaving enumeration.
