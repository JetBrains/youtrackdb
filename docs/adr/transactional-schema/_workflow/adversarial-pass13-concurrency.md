# YTDB-382 — Adversarial pass 13: concurrency, scoped (2026-06-15)

Thirteenth adversarial pass, single lens (concurrency): races, lost wakeups,
atomicity seams, wake protocols, memory visibility. Surface attacked: **only
the fresh text** of this session's eight settlements (F114–F121), the
`git diff 0c4646c350..HEAD` of `_workflow/research-log.md`, and the D7
sentences those settlements rewrote (the freezer-bullet layered-case wake, the
teardown bullet's release-path split, the `checkOpenness`-cap demotion, the
uniform warn-noop alignment). Passes 1–12 are settled and out of scope; I did
not re-attack them.

Code grounding (live tree reads, line-cited; mcp-steroid not driven by this
agent — load-bearing code-facts are flagged `PSI-VERIFY` for the orchestrator):
`OperationsFreezer.java`, `WaitingList.java`, `WaitingListNode.java`,
`AtomicOperationsManager.java`, `AbstractStorage.freeze/release`,
`DiskStorage:356`, `DatabaseSessionEmbedded` (`status` field `:223`,
`internalClose:2211`, `commitImpl:3145`).

**Verdict: 1 BLOCKER, 0 MAJOR, 3 MINOR.** The F114 fix imports a second
concurrent caller into `cutWaitingList`, and the resolution's two load-bearing
liveness claims about that — "never a double-unpark" and "the cut wakes it" —
are **both false against the actual `WaitingList`/`OperationsFreezer` code**.
The double-unpark is benign (C2, MINOR), but the missed-wake (C1) reopens the
exact F86 outage F114 was settled to close. F115/F116/F117 survived the
direct attacks; the residual notes on them are MINOR wording/over-claim items.

---

### C1: The engage-time cut races the entrant's enqueue, and the cited Dekker pair does not order it — a one-shot operator-arm cut that runs ahead of the entrant's `addThreadInWaitingList` leaves the schema commit parked for the operator freeze's whole duration, reopening the exact F86 outage F114 closed [BLOCKER]

F114's resolution rests the layered-case liveness on: "The freezer's existing
Dekker discipline bounds the race — the freezer publishes `freezeRequests` and
the freeze kind before cutting, the entrant publishes `operationsCount` before
reading them, **so either the entrant already sees the operator freeze and
throws or the cut wakes it**." The cited pair is `operationsCount` /
`freezeRequests`. That is the **wrong pair** for the engage-time cut, and the
right pair only guarantees the park-causing direction.

The entrant's wake-loop (`OperationsFreezer:35`–`:51`):

```
35  while (freezeRequests.get() > 0) {
38    operationsCount.decrement();
40    throwFreezeExceptionIfNeeded();      // kind-check — the only throw site
44    operationsWaitingList.addThreadInWaitingList(thread);
46    if (freezeRequests.get() > 0) { park(this); }   // :47
50    operationsCount.increment();         // reached ONLY after a wake
51  }                                      // re-loops to :35 → :40 (re-throw)
```

The throw the design promises happens at `:40`, which the parked entrant
reaches again **only after being unparked from `:47`**. The `:46` re-check is
*not* a kind-check — it reads `freezeRequests > 0` and, finding it true (an
operator freeze layered on), **parks**. So "the entrant already sees the
operator freeze" does not make it throw at the engage-time layering; it makes
it *park*. Liveness therefore depends entirely on "the cut wakes it" — the
freezer's engage-time `cutWaitingList` must observe the entrant's node.

Breaking interleaving (transient already engaged; operator freeze layering in):

1. t0 — schema commit at `:40`: only a transient is registered (kind = none),
   kind-check passes, no throw.
2. t1 — operator freeze engages: `freezeRequests.incrementAndGet()` 1→2,
   registers its `FreezeParameters` throw supplier
   (`AbstractStorage:3901`–`:3903`), then runs the new operator-arm
   `cutWaitingList()`.
3. t2 — the operator-arm cut reads `tail`/`head`. The entrant has **not yet
   executed `:44`** (it is between `:40` and `:44`). The cut returns the
   pre-existing list (or null) and unparks those threads; **the entrant's node
   is not in it**.
4. t3 — entrant `:44` `addThreadInWaitingList(thread)` — node now linked.
5. t4 — entrant `:46` reads `freezeRequests == 2 > 0` → `:47` `park`.
6. The entrant is now parked at `:47` with its node in the list, but the
   one-shot engage-time cut already fired at t2 and missed it. **No further
   unpark fires until `freezeRequests` reaches 0** (`releaseOperations:97`) —
   i.e. until the operator freeze releases. The commit stays parked inside the
   four-lock window (D7 mutex + `SchemaShared.lock` + `IndexManager.lock` +
   `stateLock.read`) for the operator freeze's whole duration. **This is C38 /
   F86 verbatim — the outage F114 was settled to remove.**

Why the cited Dekker pair does not save it. Dekker guarantees *at least one* of
{A sees B's store, B sees A's store}. The two relevant pairs:

- `operationsCount` / `freezeRequests` (the pair the resolution names): orders
  the freezer's **drain** (`:81`) against the entrant's `:38` decrement. The
  entrant decrements at `:38` *before* enqueuing, so the drain never waits on
  this entrant. **This pair is irrelevant to the cut/enqueue race.**
- waiting-list node / `freezeRequests` (the pair that actually governs the
  cut): the entrant stores its node (`tail.compareAndSet`, `:44`) then loads
  `freezeRequests` (`:46`); the freezer stores `freezeRequests` (`:75` /
  `incrementAndGet`) then loads the list (`cutWaitingList`). This *is* a Dekker
  pattern, but it only guarantees one direction holds. In the interleaving
  above the entrant-sees-increment direction is the one that holds (its `:46`
  read returns 2) — and that direction makes it **park**, not throw. The
  freezer-sees-node direction (the one that would unpark it) is exactly the
  direction Dekker does **not** promise when the other already holds.

Why the *release*-path cut is immune but the engage-path cut is not. On
release, `releaseOperations` drops `freezeRequests` to 0 *before* cutting
(`:95`–`:97`). A racing entrant therefore either reads 0 at `:46` and **does
not park** (re-loops, exits at `:35`), or is already enqueued for the cut to
find. The "park ⇔ a cut is coming for me" invariant holds because the count
went to zero. The engage-time cut **keeps `freezeRequests > 0`**, so the `:46`
re-check unconditionally tells the entrant to park while giving no signal that
the (one-shot, possibly-already-fired) cut will reach it. The invariant the
release path relies on is precisely the one the engage path breaks.

Why it matters: this is a BLOCKER because it is not a new edge — it is the
original F86/C38 read outage, re-admitted by the very fix that claimed to close
it, on the same operational trigger (backup tooling, where transient quiesces
`DiskStorage:356`/`:1248` and operator `freeze(db,false)` co-occur). The
acceptance triple's fourth line ("loud abort within the bound") is not
delivered in this interleaving.

Resolution direction (pick one, then re-validate):

- **Re-check-after-enqueue on the engage path too.** The entrant must, after
  `:44`, atomically re-establish "I am enqueued AND a cut that will reach me is
  guaranteed." Concretely: have the operator-arm engage path bump a
  monotonically-increasing *cut-generation* counter (or set a
  `cutPending`/epoch) under the same publication as the `freezeRequests` store,
  and have the entrant, after enqueue, re-read it at `:46`; if a new
  operator-kind freeze is visible the entrant re-runs the **kind-check** (throw)
  rather than blindly parking. The decision at `:46` must be "park only if no
  operator-kind freeze is registered," not "park if `freezeRequests > 0`."
- **Make the engage cut not one-shot relative to late enqueuers.** Loop the
  operator-arm cut until the list is observed empty *after* the
  `freezeRequests` store is globally visible, so a node added at t3 is caught by
  a re-cut. (Heavier; must terminate.)
- **Shape (b) revisited** (timed `parkNanos`) — rejected in F114 for reopening a
  bounded outage, but a timed backstop *does* close this exact lost-wakeup; if
  C1's cut-race cannot be made airtight cheaply, the rejection rationale needs
  re-weighing against an unbounded outage versus a one-timeout-bounded one.

The cleanest is the first: the `:46` decision predicate must be kind-aware (the
same predicate as `:40`), not the raw count, so an entrant that loses the
cut/enqueue race still declines to park against an operator freeze.

`PSI-VERIFY: OperationsFreezer.startOperation` — confirm the wake-loop body
(`:35`–`:51`) is exactly increment / `while freezeRequests>0` / decrement /
`throwFreezeExceptionIfNeeded` / `addThreadInWaitingList` / `if freezeRequests>0
park` / increment, i.e. the `:46` re-check is a bare count read with **no**
kind-check before `park`, so an entrant that passed `:40` parks unconditionally
on a later operator-freeze layering.

---

### C2: The F114 resolution asserts `cutWaitingList` "stays cut-safe under the now-two unpark sites (never a double-unpark)"; the single-element branch and the un-emptied tail both double-return under two concurrent cutters — benign, but the asserted property is false as written [MINOR]

F114 imports a **second** concurrent caller of `cutWaitingList` (the
operator-arm engage cut) alongside the existing release-side cut. Pass-12's PSI
verified `cutWaitingList` had **exactly one** caller (`OperationsFreezer:105`);
that verification is now stale, and the resolution's replacement claim — "a
racing cutter takes the whole list or empty, never a double-unpark" — was never
checked against the code. It is false in two places:

- **Single-element branch (`WaitingList:47`–`:49`):** `if (head == tail) {
  return new WaitingListNode(head.item); }` — no CAS, no clear of `head`/`tail`.
  Two cutters that both observe a one-waiter list both return that waiter's
  thread → the waiter is unparked twice.
- **Multi-element branch (`:51`–`:64`):** after `head.compareAndSet(head,
  tail)` the list is left with `head == tail` (the old tail survives as a
  sentinel; the cut returns the segment with `node.next = new
  WaitingListNode(tail.item)` at `:62`, re-including the tail thread). A
  concurrent or subsequent cutter then hits the single-element branch and
  returns `tail.item` again → the tail waiter is unparked by both cuts.

In the single-cutter world (release-only) this was harmless by construction:
cuts were serialized by `freezeRequests == 0`, and the surviving-tail re-cut on
the next release is the intended design. F114 breaks the serialization
assumption.

Why it is only MINOR: `LockSupport.unpark` grants at most one permit; a double
unpark of the same thread collapses to one. The woken entrant re-checks `:46` /
re-loops `:35`→`:40`, so a spurious wake costs one re-evaluation, never
corrupts state. There is **no** permit leak that strands the thread (the
concern would be a *missed* unpark, which is C1, not this). So correctness
holds; the resolution's *stated* safety property does not.

Why it still matters: the false "never a double-unpark" sentence is load-bearing
prose in a settled record — an implementer who trusts it may add an assertion
("a node is unparked at most once") that fires under the new two-cutter regime,
or may reason about other invariants from it. Per the CLAUDE.md prose-accuracy
rule, a settled claim that the code contradicts should be corrected.

Resolution direction: reword the cut-safety clause to the true property — "a
racing cutter may unpark a given waiting thread more than once; double-unpark is
benign because `unpark` is permit-idempotent and the woken entrant re-evaluates
the gate" — and drop the "never a double-unpark" assertion. (If C1's fix makes
the engage path not call `cutWaitingList` at all — e.g. the kind-aware `:46`
predicate route — this finding dissolves with it.)

`PSI-VERIFY: WaitingList.cutWaitingList` — confirm (a) the `head == tail` branch
returns a fresh node without any CAS or head/tail mutation, and (b)
`cutWaitingList` now has TWO callers after F114 lands (release-side
`OperationsFreezer:105` plus the new operator-arm engage cut), superseding
pass-12's sole-caller finding.

---

### C3: F116's "admits at most one more zombie commit" over-states the bound — a plain-`status` read with no JMM edge can stay stale across several commit attempts; the count is unbounded, only the F52-lock safety is structural [MINOR]

F116's resolved block and the D7 teardown bullet say the visibility-lagged
`checkOpenness` cap "can admit **one** more zombie commit — harmless." The
demotion to best-effort is correct and the structural authority (F52's
whole-commit `SchemaShared.lock` on a cleared tx, F85) is correctly named — that
part survives (see Failed attacks). But the *quantifier* is wrong: `status` is a
plain field (`DatabaseSessionEmbedded:223`, PSI-confirmed plain in the live
tree) with no happens-before edge from the foreign teardown's CLOSED write
(`internalClose`). The JMM places **no bound** on how long a thread may observe
the stale OPEN value — a thread that cached `status == OPEN` can pass
`checkOpenness` on more than one commit attempt before the write becomes
visible. So the admitted-zombie count is "≥ 1, unbounded," not "exactly one
more."

Why it is only MINOR: the safety argument does not depend on the count. Every
admitted straggler serializes behind F52's lock and runs on a cleared tx (F85),
so N late-visible admissions are exactly as harmless as one. The over-claim is a
precision defect in the rationale, not a hole in the guarantee.

Resolution direction: change "admits at most one more zombie commit" to "admits
one or more late commits (the staleness window is unbounded under the plain
read), each harmless because it serializes behind F52's lock on a cleared tx
(F85)." Keeps the (correct) structural conclusion, removes the false bound.

---

### Failed attacks

- **F115 — owner `finally` vs foreign teardown, double-release.** Both paths CAS
  the **same** holder atomic, and the CAS key is `(session, ordinal)` (the F96
  resolved block: "presenting the teardown's own session and the acquire-time
  ordinal"), not session-only. The same-thread `finally` presents `(S, N)` from
  the surviving record; the foreign teardown presents `(S, N)` from the holder
  it read; same expected value, same atomic — exactly one wins, the other loses
  the CAS and warn-noops. Holds.
- **F115 — re-acquire ABA (teardown clears a fresh acquisition).** Even if the
  owner releases and re-engages a new schema tx on the same session between the
  teardown's holder-read and its CAS, the fresh acquisition carries a **new
  ordinal** N+k, so the teardown's `CAS(expect (S, N), set null)` fails against
  holder `(S, N+k)` and warn-noops. The ordinal in the CAS key (not the loose
  "session-keyed" shorthand at the F96 extension line 3378) defeats the ABA.
  Holds.
- **F115 — record vs holder ordinal divergence.** The same-thread `finally`
  reads the ordinal from the session record, the foreign teardown reads it from
  the holder; both carry the *same* acquire-time captured ordinal (F105 pins the
  holder carries it; the record carries the same captured value). They cannot
  disagree by construction, so the two paths present equal expected values.
  Holds.
- **F115 — holder volatility / read-before-act.** The design pins the holder as
  the volatile cross-thread carrier (F104/F105: "published cross-thread by the
  engage's holder write"), and the teardown "already loads the volatile holder
  to identify the session" before CAS-clearing. Read-before-CAS is explicit.
  Holds (flagged for PSI below as a build-time correctness check, not a design
  hole).
- **F116 — structural reliance on `checkOpenness`.** The resolved D7 teardown
  bullet rests the heal-vs-zombie exclusion on F52's lock scope, explicitly
  *not* on the gate ("not a second structural property"). No surviving
  concurrency path leans on the cap structurally. Holds (modulo the C3 count
  over-claim, which does not touch the structural leg).
- **F117 — a release-site different-session presenter that should be loud.** The
  only release sites are the same-thread `finally` and the foreign teardown
  `finally`; a throw from either masks the owner's real exception (the F97
  shape). A legitimate different-session release cannot arise (mid-tx thread
  migration is scoped out, F13), so a different-session presenter at release is a
  bug — and the warn-log preserves observability without the masking hazard. The
  loud signal lives correctly at the engage-side predicate (F105). Holds.
- **F114 — thundering-herd / data-entrant re-park livelock.** Every parked data
  entrant woken by the engage cut re-checks `:46`, re-adds, re-parks. This is
  bounded churn per operator-freeze engage (a rare event), not livelock: each
  woken data entrant makes progress to a re-park in O(1), and the engage cut
  fires once per layering. No starvation cycle. Holds (cost only, as the
  resolution states).
- **F114 — woken entrant reads a stale freeze kind and re-parks instead of
  throwing.** The kind is published via `freezeParametersIdMap.put` (a
  `ConcurrentHashMap`) and read via `throwFreezeExceptionIfNeeded` iterating
  `.values()` at `:40`; the entrant re-runs `:40` on each loop after a wake, and
  the map's happens-before (put → get on a `ConcurrentHashMap`) plus the
  `freezeRequests` volatile fence make the operator `FreezeParameters` visible
  to a woken entrant before its `:40` re-read. *Conditional on the entrant
  actually being woken* — which C1 shows is not guaranteed. So this sub-attack
  fails on its own terms (the kind IS visible once woken); the failure is the
  wake itself (C1), not a stale-kind read.

---

### PSI-VERIFY items (for the orchestrator to confirm centrally)

1. `OperationsFreezer.startOperation` — the wake-loop body `:35`–`:51` matches
   the quoted shape; specifically the `:46` pre-park re-check is a bare
   `freezeRequests.get() > 0` with **no** kind-check (`throwFreezeExceptionIfNeeded`
   or equivalent) before `LockSupport.park`, so an entrant that passed the `:40`
   kind-check parks unconditionally when an operator freeze layers on after `:40`.
   (Load-bearing for C1.)
2. `WaitingList.cutWaitingList` — (a) the `head == tail` branch (`:47`–`:49`)
   returns a fresh `WaitingListNode(head.item)` with no CAS and no head/tail
   mutation; (b) the multi-element branch leaves `head == tail` after its CAS so
   the tail thread is re-returnable by a subsequent cut. (Load-bearing for C2's
   double-return claim.)
3. `cutWaitingList` caller set after F114 lands — confirm it gains a SECOND
   caller (the operator-arm engage cut in `freezeOperations`) beyond the release
   path `OperationsFreezer:105`, superseding the pass-12 sole-caller PSI result.
   (Load-bearing for C2's "two concurrent cutters" premise.)
4. The transactional-schema mutex release CAS key is `(session, ordinal)` (not
   session-only), and the foreign teardown reads the volatile holder before its
   compare-and-clear. (Confirms the F115 failed-attacks; these are
   design-target symbols not yet in the tree, so verify against the F96/F104/F105
   record text rather than code.)
