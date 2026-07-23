<!-- MANIFEST
verdicts: {F1: VERIFIED, F2: VERIFIED, F3: VERIFIED, F4: VERIFIED, F5: VERIFIED, F6: VERIFIED}
scope: "git show 5811042e95 — Track 7 Step 4 review-fix commit vs findings in {concurrency,baseline}-step4-iter1.md"
flags: [READ_ONLY, NO_MAVEN_RUN, NO_PRODUCT_CODE_MODIFIED, VERDICTS_DERIVED_INDEPENDENTLY]
residual_notes: 2 (non-blocking nits, no verdict impact)
-->

# Gate verification — Track 7 Step 4 review fixes, commit 5811042e95 (iter 1)

**Artifact:** `git show 5811042e95` ("Harden abort-predicate lock against throwing predicates"),
branch `transactional-schema`. Working tree at `1db509fff6` (episode-record commit on top);
`git diff 5811042e95 1db509fff6` touches only `_workflow` docs, so working-tree line numbers cite
the fix commit exactly.
**Original findings:** `reviews/concurrency-step4-iter1.md` (CN38–CN41),
`reviews/baseline-step4-iter1.md` (BG6, CQ6, TQ6–TQ8). BG6 ≡ CN38 (same defect, both reviews).
**Method:** read-only; no Maven executed (test-execution ownership honored). Every verdict derived
from the code as landed — full exit-path enumeration of the guarded region, reader-path reads
(`sharedTryLock` back-off), analytic re-derivation of the coverage test's discrimination — with
the fix thread's red-first/timeout evidence (episode log, `plan/track-7.md:386-430`) used as
corroboration only, never as the basis.

---

## F1 (BG6 = CN38) — phase-2 throw guard, no leak, no double-unlock — **VERIFIED**

**Claim:** phase-2 body wrapped in try/catch(Throwable) that unlocks then rethrows; no
double-unlock against the two existing unlock-and-return-false branches; two regression tests,
red-first.

**Premises and trace:**

1. Guard shape as landed (`ScalableRWLock.java:735-763`): the `try` opens immediately after the
   phase-1 `while (stamp == 0)` loop exits — i.e., the write bit is held on every entry into the
   guarded region — and encloses the snapshot block (`:736-745`), the drain loop with its abort
   branch (unlock `:746`, `return false` `:747`), the success-edge re-check (unlock `:757`,
   `return false` `:758`), and `return true` (`:760`). The catch (`:761-763`) unlocks and
   rethrows `phaseTwoFailure`.
2. **Exhaustive exit enumeration of the guarded region** (probe question):
   - **E-success** (`:760`): bit held by contract, caller releases. Catch not involved.
   - **E-abort-drain** (`:746-747`) / **E-abort-edge** (`:757-758`): the unlock runs with the bit
     provably held (held continuously since phase-1 success; no prior release site exists in the
     try). `StampedLock.WriteLockView.unlock()` throws only `IllegalMonitorStateException`, only
     when the bit is *not* held — impossible here. The statement after each unlock is
     `return false`, a return of a constant expression, which cannot throw (JLS §14.17). So **no
     instruction can execute between the branch unlock and method exit** — the catch can never
     observe an unlocked bit, and no leak window exists between unlock and return. No
     double-unlock path.
   - **E-throw-predicate** (`abort.getAsBoolean()` at `:744` or `:756` throws): the throw occurs
     *during condition evaluation*, i.e., strictly before that branch's unlock could run. Catch
     entered with the bit held → exactly one unlock → rethrow. Correct.
   - **E-throw-snapshot** (`toArray` OOM etc., `:736-745`): bit held → catch unlocks →
     rethrows. Correct.
   - **E-throw-other** (`readerState.get()`, `Thread.yield()`): cannot throw except VM errors,
     which the catch handles identically (bit held at any such point). The only residual is a VM
     error *inside* an unlock or inside the catch itself — the same never-defended exposure class
     as every pre-existing method (`:431`, `:554`, `:614`); the baseline review itself scoped
     that out.
   - **Entry precondition check:** the catch cannot be reached from a phase-1 state (nothing
     held) — phase-1 throws (`:696` predicate, `:707-717` interrupt) exit before the `try`
     opens. So "any throw reaching the catch arrives with the bit still held" (comment
     `:731-734`) is exact.
3. **Precise-rethrow compilability:** `catch (final Throwable) { …; throw phaseTwoFailure; }`
   with no `throws` clause is legal — the try body calls nothing declaring checked exceptions,
   so precise rethrow narrows to `RuntimeException | Error`. Corroborated by the episode log's
   green full-suite run (`plan/track-7.md:428-430`).
4. **Regression tests:** `throwingPredicateAtSuccessEdgeReleasesBit`
   (`ScalableRWLockTest.java:654-680`) and `throwingPredicateDuringReaderDrainReleasesBit`
   (`:684-745`). Both assert the propagated exception is the predicate's own, the bit is released
   (`assertFalse(isWriteLocked())`), a fresh reader is admitted, and the primitive/lock is
   reusable. Both hit the second predicate call deterministically (fresh lock: call 1 = phase-1
   loop top; call 2 = success edge with empty drain, or drain poll when the latched residual
   reader guarantees a READING snapshot entry — the reader registers before `readerIn` counts
   down, and phase-1 `tryWriteLock` cannot time out because no writer holds the bit).
5. **Red-first, independently re-derived** (no Maven run needed): traced against the parent
   (unguarded) shape, both tests reach their leaked-bit assertion and fail it —
   success-edge test: throw propagates with bit held → caught by the test's
   `catch (IllegalStateException)` → `assertFalse(isWriteLocked())` **fails**; drain test: writer
   thread stores the throw, joins, then `assertFalse(isWriteLocked())` **fails**; the `finally`
   still frees the reader (its `sharedUnlock` only lazySets its own state — unaffected by a
   leaked write bit), so the red run is clean, not a hang. Matches the episode log's red-first
   record (`plan/track-7.md:391-394`).

**Alternative hypotheses checked:** (a) *guard placed too wide — could it swallow/mask the
abort-branch returns?* No: `return` from inside a `try` bypasses a `catch` (only `finally` would
intercept; there is none). (b) *guard changes the no-throw path behavior?* No: statement sequence
inside the try is byte-equivalent to the pre-fix body modulo one line-width rewrap of the
`toArray` assignment; the exact-2-polls edge test (`:433-451`) still passes by trace (the
`requireNonNull` at `:686` does not invoke the predicate).

## F2 (CN39) — continuous-coverage liveness test — **VERIFIED**

**Claim:** discriminating test added; times out against the retry-loop shape, passes stably
against the real shape; 60 s bound safe.

**Premises and trace:**

1. Test as landed (`ScalableRWLockTest.java:752-830`): four daemon readers, staggered starts
   `i*7` ms (0/7/14/21), unequal residences `15+i*5` ms (15/20/25/30), each re-acquiring through
   a tight `sharedTryLock` + `onSpinWait` spin (`:772-793`); coverage established by
   `holds >= 8` within 20 s (`:802-803`); writer runs
   `exclusiveLockWithAbort(() -> false, 1 ms)`; asserts acquisition and a 20 s sanity bound
   (`:805-816`); `finally` stops and joins the readers. Matches the finding-index parameters
   exactly.
2. **Discrimination is genuine — mechanism verified against the reader paths, not just claimed.**
   The load-bearing detail is `sharedTryLock`'s shape (`ScalableRWLock.java:446-464`): on refusal
   it backs off to `NOT_READING` and returns — so (a) refused spinners never wedge the writer's
   drain (the drain sees `NOT_READING`, terminates), and (b) a spinner re-reacts to a dropped
   write bit within nanoseconds. Against the **real single-acquisition shape**: readers never
   touch the StampedLock, so phase-1 `tryWriteLock` succeeds immediately (no competing writer);
   the bit then stays up, every re-admission is refused, and the drain completes within the
   longest in-flight residence (≤ 30 ms) — acquisition in tens of ms. Against the **retry shape**
   (`exclusiveTryLockNanos`-style, release on drain timeout): each ~1 ms attempt succeeds only if
   *every* in-residence reader has < 1 ms of residence remaining simultaneously; each release
   window re-admits the ns-reactive spinners, restarting 15–30 ms residences, and the unequal,
   incommensurate residences keep the four ends de-synchronized (covering-system behavior — at
   every residence end at least one other reader is mid-residence). Rough per-attempt success
   probability ≈ (1/15)(1/20)(1/25)(1/30) ≈ 4·10⁻⁶ → expected time-to-acquire in the hundreds of
   seconds ≫ the 60 s test timeout. Not literally "never" (probabilistic alignment or a lucky
   multi-reader descheduling gap could admit the retry shape eventually), but failure of the
   regressed shape is overwhelmingly probable per run — a genuine discriminator, and strictly
   stronger than the finite-workload stress it supplements. The episode log's empirical record
   (starved to the 60 s timeout on the reverted shape; 0.15–1.8 s over 3 runs on the real shape,
   `plan/track-7.md:395-399, 411-419`) corroborates the analysis and matches the claim.
3. **Flake safety of the 60 s bound (probe question):** every wait has a failure mode that is a
   clean assertion, not a hang, and the margins are ~30× the measured values:
   coverage-establishment needs 8 holds of 15–30 ms wall-clock each — even 10× scheduler
   degradation on an oversubscribed host lands ≈ 2–3 s ≪ 20 s (residences are wall-clock, so
   descheduling *extends* holds rather than stalling the counter); the writer's drain is bounded
   by one max residence plus scheduling quanta ≪ 20 s; reader joins after `stop` are ≤ one
   residence + one spin-loop check ≪ 5 s each. The three 20 s-class phases cannot each approach
   their bound in the same run (a 20 s coverage phase fails its own assertion before the writer
   ever runs). Worst realistic CI case (2-core host, 4 spinners + main): wall-clock residences
   still elapse, total ≈ 2-5 s. The only residual exposure is a > 20 s full-VM stall (GC/CI
   freeze), which every bounded-wait test in this file shares at 5 s bounds — the new test is
   *more* generous than the file's idiom, not less.
4. The test fails the regressed shape via the `@Test(timeout = 60_000)` (writer never returns) —
   consistent with the claimed timeout mode; the 20 s `acquireMillis` assertion additionally
   catches a hypothetical intermediate regression that acquires slowly through release windows.

**Alternative hypothesis checked:** *could refused spinners strand the writer's drain forever
(making the real shape flaky by deadlock)?* Rejected — `sharedTryLock`'s back-off to
`NOT_READING` (`ScalableRWLock.java:459-462`) guarantees drain termination; only in-flight
residences (bounded, 15–30 ms) hold READING.

## F3 (CQ6) — interrupt message generalized, only-certain-facts — **VERIFIED**

1. `ScalableRWLock.java:708-716`: message is now "interrupted while acquiring the write lock with
   an abort predicate (write bit not acquired by this call; another writer currently holds the
   write bit / no writer currently holds the write bit)". No "storage" wording — the
   shared-primitive complaint is closed.
2. Certainty audit: "write bit not acquired by this call" is invariant on this path
   (`stamp == 0`). The holder clause is a present-tense instantaneous snapshot with no queueing
   or contention claim — for the pre-interrupted-on-free-lock case (StampedLock checks
   `Thread.interrupted()` before attempting) it now truthfully reports "no writer currently holds
   the write bit" instead of the old, false "queued against contending writers". The rewritten
   comment (`:701-706`) explicitly names both best-effort caveats (pre-interrupted never parked;
   concurrent release), satisfying the keep-comments-in-sync rule.
3. No collateral: the interrupt test pins the fragment `"interrupted while acquiring"`
   (`ScalableRWLockTest.java:558-560`), which the new message still contains; no other test
   references the old wording (grepped).

## F4 (TQ6) — argument-validation + two-waiter tests — **VERIFIED**

1. `invalidArgumentsFailFastWithNothingHeld` (`ScalableRWLockTest.java:575-598`): pins
   `pollNanos == 0` and `-1` → IAE with "pollNanos" in the message (guard at
   `ScalableRWLock.java:687-689`), null predicate → NPE with "abort predicate" in the message —
   now *explicit* via `Objects.requireNonNull(abort, "abort predicate must not be null")`
   (`:686`, placed before the IAE guard), which upgrades the reviewer's N2 implicit-NPE note to a
   pinned contract. Nothing-held is asserted both ways (`isWriteLocked()` false + `sharedTryLock`
   succeeds). TQ6's counterexample (weakening the guard to `< 0`) now fails the first case.
2. `twoAbortWaitersSerializeCleanly` (`:606-644`): two abort-primitive waiters queue behind an
   `exclusiveLock` holder (the mutual-primitive-contention shape TQ6 asked for), the
   `assertTrue(isWriteLocked())` while both are queued is race-free (main still holds the bit),
   and after release both must acquire exactly once with the lock free after — bounded joins
   (10 s ≫ 5 ms poll), failures channel rethrown. Sound and deterministic in outcome.

## F5 (TQ7) — failure hygiene: daemons + finally — **VERIFIED**

1. Every helper thread in the new section is daemon-marked before start:
   `ScalableRWLockTest.java:339, 384, 395, 473, 493, 543, 626, 699, 719, 797, 893` — covering all
   four tests TQ7 cited plus the new tests and the stress suite (whose threads were the one
   pre-fix non-daemon group in the new section).
2. Unblocking moved to `finally` at every leak site TQ7 named: abort flag (`:345-349`), abort
   flag + reader latch (`:414-418`), both writer-preference latches (`:512-516`), interrupt
   (`:548-552`), drain-test reader latch (`:737-740`), coverage-test stop flag + joins
   (`:818-822`). Traced each cited failure path (e.g., `awaitParked` assertion fails → flag still
   flips → daemon waiter exits): no helper outlives a failed assertion.
3. Scope note: pre-existing tests (`:32-270`) keep the file's old idiom — consistent with TQ7,
   which scoped the finding to the new section and itself noted the pre-existing shape as
   established idiom. Not a gap.

## F6 (TQ8 + CN41 + CN40 disposition) — **VERIFIED**

1. TQ8: the dead `attempt` counter is gone from the stress writer (grep for `attempt` in the test
   file: no hits); `BooleanSupplier` is imported (`:17`) and used unqualified in `awaitCondition`
   (`:292`).
2. CN41: the fairness javadoc landed (`ScalableRWLock.java:666-670`) and is technically accurate —
   phase-1 timeout cancels the CLH node, retry re-enqueues at the tail, unbounded only under a
   permanent *writer* storm, with the consumer-scoping caveat CN41 asked for.
3. CN40 disposition (explicitly not taken): **sound.** CN40 was a suggestion whose own text
   conceded that no deterministic black-box pin exists for the no-release-window property and
   that "CN39's staggered-reader test, once added, covers the consequence of a release window —
   slip-in admission — deterministically, which is the stronger net." The landed coverage test is
   exactly that stronger net (§F2.2: any release window re-admits the spinners and the test times
   out), so declining per-phase sampling supersedes rather than ignores the finding. The
   disposition is recorded in the episode log (`plan/track-7.md:421-424`) with the same
   rationale. No open remainder.

---

## Residual notes (non-blocking, no verdict impact — orchestrator may fold into any future pass)

1. **Stale count in a test javadoc:** `writerAcquiresUnderContinuousReaderCoverage`'s doc comment
   opens with "two readers re-acquire with zero gap" (`ScalableRWLockTest.java:743`) while the
   test (and its inline comment, `:759`) uses four staggered readers. Likewise the inline
   "every reader has completed at least one full hold" gloss on `holds >= 8` (`:801`) is not
   literally guaranteed (8 is a total, not per-reader) — harmless, since the discrimination
   depends on the re-admission spin, not on per-reader hold counts. Two-line comment fix.
2. **Style nit:** `java.util.Objects.requireNonNull` is fully qualified inline
   (`ScalableRWLock.java:686`) rather than imported — inconsistent with the same commit's TQ8
   cleanup direction in the test file. Cosmetic; Spotless does not forbid it.

## Hypothesis log

| # | Hypothesis | Outcome |
|---|-----------|---------|
| G1 | A path exists where the F1 catch double-unlocks (abort branch unlocks, then throw reaches catch) | Refuted — post-unlock statement is `return false` (constant return, cannot throw); exit enumeration §F1.2 |
| G2 | A path exists where a phase-2 throw escapes with the bit held (guard too narrow) | Refuted — try opens at the phase-1/phase-2 boundary; every held-bit statement is inside it |
| G3 | The guard changes no-throw behavior (extra predicate polls / edge-test drift) | Refuted — body statement-equivalent; `requireNonNull` does not invoke the predicate; 2-poll edge test unaffected |
| G4 | F1 tests would pass against the unguarded parent (red-first claim false) | Refuted — both deterministically fail the leaked-bit assertion by trace (§F1.5) |
| G5 | Refused `sharedTryLock` spinners leave READING set, deadlocking the coverage test's drain | Refuted — back-off to NOT_READING at `ScalableRWLock.java:459-462` |
| G6 | The retry shape could pass the coverage test often enough to be a useless discriminator | Refuted — per-attempt success ≈ 4·10⁻⁶, expected acquire time ≫ 60 s (§F2.2); corroborated by the recorded reverted-shape timeout |
| G7 | The real shape can flake to the 60 s timeout on a slow CI host | Refuted for all bounded phases (≈30× margins, wall-clock residences immune to descheduling); residual is only a >20 s whole-VM stall, below the file's pre-existing exposure baseline |
| G8 | F3's reworded message breaks the interrupt test's substring pin | Refuted — pinned fragment "interrupted while acquiring" retained |
| G9 | A helper thread in the new section escaped the daemon/finally sweep | Refuted — all 11 creation sites daemon-marked; all unblock actions in finally (§F5) |
| G10 | CN40's decline leaves an untested regression class | Refuted — the release-window consequence (slip-in admission) is exactly what the coverage test fails on (§F6.3) |

## Compact verdict block

F1 (BG6=CN38): VERIFIED — guard encloses exactly the held-bit region; exit enumeration shows no
double-unlock and no leak path; both regression tests deterministic and red-by-trace against the
parent shape.
F2 (CN39): VERIFIED — discrimination mechanism independently re-derived (sharedTryLock back-off +
ns re-admission starves the retry shape, expected ≫ 60 s; real shape bounded by one residence);
60 s/20 s bounds carry ~30× margins, failure modes are clean assertions.
F3 (CQ6): VERIFIED — storage wording gone; message reports only instant-certain facts; no
queue-state claim for pre-interrupted threads; existing test pin unaffected.
F4 (TQ6): VERIFIED — IAE (0 and −1), explicit requireNonNull NPE, nothing-held asserts, plus the
two-waiter serialization shape.
F5 (TQ7): VERIFIED — all new-section helpers daemon; every unblock (flags, latches, interrupt,
stop) in finally; pre-existing tests intentionally out of scope per the finding itself.
F6 (TQ8/CN41/CN40): VERIFIED — dead counter removed, import cleaned, fairness javadoc accurate;
CN40's decline is sound (superseded by F2's strictly stronger end-to-end net, per CN40's own
text).
Blockers: none. Residuals: 2 cosmetic notes (stale "two readers" comment; inline FQN), no action
required for gate passage.
