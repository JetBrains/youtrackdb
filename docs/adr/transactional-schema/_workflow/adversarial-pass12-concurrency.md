# YTDB-382 — Adversarial pass 12: concurrency, scoped (2026-06-12)

Twelfth adversarial pass, single lens: races, atomicity seams, lock/permit
lifecycle, memory visibility (JMM/SC arguments), wake protocols, interleaving
analysis. Surface attacked: the pass-11 settlement text only — the F104–F108
folds in `git diff 8e12fb5510..51f6b81d70` (commits `3feed92a17`,
`896c26fe1a`, `04f52b68ca` (concurrency half), `0ba94c2cc3`, `a280e8a365`,
`960b0ca7dc`), plus the rewritten D7 regions those commits touch (the re-keyed
Guard (F38) bullet, the teardown bullet's handshake/exclusion/predicate
clauses, the freezer bullet's placement clause, the §2a map sweeps, the F13
bound). The sibling pass-12 durability report (U33–U36) covers F109–F113; no
overlap below. All line citations verified against the live tree;
**mcp-steroid was reachable and used** (`steroid_list_projects` preflight:
`transactional-schema` open at the repo root): `ReferencesSearch` confirmed
`OperationsFreezer.releaseOperations` has exactly one production caller
(`AtomicOperationsManager:252`, `unfreezeWriteOperations`),
`WaitingList.cutWaitingList` exactly one (`OperationsFreezer:105`),
`WaitingList.addThreadInWaitingList` exactly one (`OperationsFreezer:44`),
and `freezeOperations` exactly one (`AtomicOperationsManager:248`) — so the
freezer's waiting list has no wake path outside `releaseOperations`.
Everything else below is a single-file tree read. No grep-caveats attach to
any claim in this report.

Verdict: 0 BLOCKER, 2 MAJOR, 2 MINOR. The F104 Dekker pair is sound for the
two variables it names, and the F108 entrant/freezer handshake is sound
against the real `LongAdder`/`AtomicInteger` code — but both folds attach a
liveness story their named mechanisms do not deliver: the freezer's wake
protocol only fires at request-count zero (so the layered case the F108 text
claims per-unpark re-evaluation covers gets no wake at all), and the F104
heal reads a third piece of state (the session-side ordinal record) whose
write ordering the handshake never pins.

---

## C38: "Per-unpark re-evaluation covers the layered case" is false — `releaseOperations` unparks only when `freezeRequests` reaches zero, so a transient quiesce's release under a layered operator freeze delivers no wake, and the schema commit stays parked inside the four-lock window for the operator freeze's whole duration [MAJOR]

The F108 resolution and D7's new placement clause rest the no-window claim on
two legs: the entrant/freezer Dekker handshake (sound — see Failed attacks),
and per-unpark re-evaluation: "re-evaluated on every unpark … Per-unpark
re-evaluation covers the layered case (park behind a transient quiesce,
operator freeze engages meanwhile, wake → throw)." The second leg assumes a
wake exists. The tree says otherwise:

- `releaseOperations` (`OperationsFreezer:88`–`:112`): `final long requests =
  freezeRequests.decrementAndGet(); if (requests == 0) { … cutWaitingList …
  unpark … }`. **No unpark happens unless the decrement reaches zero.**
- `freezeOperations` (`:72`–`:86`) never unparks — registration only
  increments and drains.
- PSI-verified: `releaseOperations` is the sole caller of the unpark path
  (`cutWaitingList`'s only caller is `:105`; `addThreadInWaitingList`'s only
  caller is `:44`; `releaseOperations`' only production caller is
  `AtomicOperationsManager:252`). There is no other wake source for a thread
  parked at `OperationsFreezer:47`.

The layered interleaving the resolution names, run against that protocol:

1. A transient quiesce engages (`freezeRequests` 0→1) — e.g. the
   incremental-backup WAL copy (`DiskStorage:356`), running inside its own
   `stateLock.read` window, which coexists with the commit's `stateLock.read`
   (`AbstractStorage:2285`; `startTxCommit` at `:2293` sits inside it, so the
   schema entrant holds the three metadata locks plus `stateLock.read` at the
   gate — read locks do not exclude each other, so the enclosure property
   from the pass-9 dry list does not make this state unreachable).
2. The schema commit reaches the in-window gate: `:33` increment, `:35` sees
   1 > 0, `:38` decrement, kind-check at the `:40` position — transient, not
   operator → no throw; `:44` add to waiting list, `:46` re-check > 0,
   `:47` park.
3. The operator filesystem-snapshot freeze engages (`freeze(db,false)`,
   `AbstractStorage:3905`): `freezeRequests` 1→2; its drain passes because
   the entrant decremented at `:38` before parking.
4. The transient releases: `freezeRequests` 2→1 ≠ 0 → **no unpark**. The
   "wake → throw" the resolution promises never happens.
5. The schema commit stays parked at `:47` holding the D7 mutex,
   `SchemaShared.lock`, `IndexManager.lock`, and `stateLock.read` for the
   operator freeze's full duration (minutes for a backup snapshot) — the
   C18/F86 outage, on exactly the path the placement clause exists to keep
   loud. The wake finally arrives at step 6 (operator releases, 1→0), after
   the outage has already run its course; the entrant then exits the loop
   and commits, so nothing in the acceptance triple ever sees it (line 1
   pre-engages the freeze, line 2's transient resolves before any layering,
   line 3 is the data arm).

The direct case is genuinely closed by the fused placement (an operator
freeze engaged at any evaluation point — first entry or any wake — throws),
and the demotion of steps (2)/(3) to best-effort early exits would be safe if
the backstop were airtight; this hole is the backstop's only gap, and it is
the same gap for every layering whose last releaser is the operator freeze.
Backup tooling is precisely where transient quiesces (WAL copy, segment cut)
and operator freezes co-occur, so the trigger is an operational pairing, not
a thought experiment.

Fix: one companion pin on the placement clause — the wake protocol must
deliver a re-evaluation when an operator-kind freeze engages, not only when
all freezes release. Cheapest shape: the operator-kind arm of
`freezeOperations` cuts and unparks the waiting list after incrementing the
count (woken data entrants re-check `:46`, re-add, and re-park — harmless
churn, rare event; the woken schema entrant's kind-check throws). Alternative
shape: the schema-commit entrant parks timed (`parkNanos`) and re-evaluates
on expiry. Either way, the acceptance triple needs a fourth line: schema
commit parked behind a transient quiesce + operator freeze engages before the
transient releases → commit aborts loudly with locks released within the
bound, never parked for the operator freeze's duration. Affected: F108, D7
(freezer bullet placement clause), F86.

## C39: The F104 handshake pins holder-vs-mark but never pins when the session-side ordinal record is written — the Dekker guarantee delivers the holder to the teardown's eyes, not the record, and a record written after the mark re-check re-creates the wedge the handshake closes [MAJOR]

The heal's release site does not read only the holder. D7's release-protocol
sentence: "the release site presents its own session (the `this` of the
teardown) and the acquire-time ordinal" — and the F104 fold names where the
ordinal lives: "The session/tx-side engagement state carrying the presented
acquire-time ordinal survives `rollbackInternal`'s `clear()` and `close()`'s
field wipes until the outermost `finally` consumes it." So the teardown's
release pass (the same outermost-`finally` site, reached via
`internalClose:2227` → `rollback():3260` → `rollbackInternal`) must read a
session-side record to have anything to present. That record is a third
participant in the handshake, and the fold pins its **survival** but not its
**write position**: the specified engage order is exactly "writes the holder
after the permit acquire, then re-checks the mark" — acquire → holder → mark
re-check, record placement free. (Shape (b)'s rejection does not constrain
it: (b) was record-then-**acquire**-then-holder; record-between-acquire-and-
holder, or record-after-mark-check, are both outside the rejected shape and
both legal readings.)

The Dekker argument covers two variables. In the engage-misses-mark arm the
synchronization order forces `holder-store <SO mark-load <SO mark-store <SO
release-pass-load`, so the teardown is guaranteed to see the **holder** —
the fold's "an engage that misses the mark is seen by the release pass
(normal heal)" is correct for the holder and only for the holder. Run the
same arm with the record written after the mark re-check:

1. T1 (engage): permit acquire → holder-store `(S, n, T1)` → mark-load
   (misses — legal, the load precedes the store in SO) → record-store
   (a plain write into tx/session state).
2. T2 (teardown of S): mark-store at `realClose()` entry →
   `rollbackInternal` → release pass: reads the holder (sees it, per the SO
   chain) — and reads the session-side record for the ordinal to present.
   The record-store is a plain write with no happens-before edge to T2's
   read (and may not even have executed yet — the release pass can run
   inside T1's three-instruction window). T2 finds no recorded ordinal,
   takes the mismatch arm, warn-noops, touches no permit.
3. T1 proceeds (it missed the mark) on a session whose teardown has already
   spent its one release pass; `internalClose:2234` sets CLOSED; T1's next
   operation throws, its `rollback()` throws at `checkOpenness` (`:3256`)
   before the outermost `finally` — permit held, holder set, no releaser.
   The acceptance triple's third line fails by exactly the wedge F104 was
   resolved to close.

Fix is one ordering sentence with zero new fences: the engagement record is
written **before** the holder write, so the holder's volatile store
publishes it (a release pass that sees the holder has a happens-before edge
to everything sequenced before the holder store, record included) —
completing shape (a) as acquire → record → holder → mark re-check.
Equivalent alternative: pin that the teardown's release pass derives the
presented ordinal from the holder value it just read (read-then-CAS-the-
observed-value), making the session-side record an owner-path concern only;
either pin suffices, the current text has neither. Affected: F104, D7
(teardown bullet handshake clause), F96.

## C40: The `checkOpenness` cap F106 names as load-bearing is a plain-field read with no JMM edge from the foreign teardown — "never a fresh one" is best-effort, not structural, under the same memory-model standard the adjacent F104 clause applies [MINOR]

D7's new exclusion sentence: "the `checkOpenness` gate (`commitImpl:3151`)
caps the overlap at the one zombie commit already in flight, never a fresh
one." The gate's mechanics: `checkOpenness` (`DatabaseSessionEmbedded:3341`–
`:3345`) reads `status`, a **plain** field (`:223`, no `volatile`); the
foreign teardown thread writes `STATUS.CLOSED` at `internalClose:2234`. No
synchronization edge connects that write to the owner thread's read: the
pass-8 settlement pinned normal-path memory modes as shipped (plain tx
status, plain `activeTxCount`), and the F104 teardown mark — the one volatile
the teardown does publish — is written *before* the status write and is never
read by the commit path, so seeing it proves nothing about CLOSED. Under the
JMM the owner can pass `checkOpenness` arbitrarily long after the teardown
completes and start exactly the "fresh" zombie commit the sentence excludes.

The blast radius is contained, which is why this is MINOR and not MAJOR: the
straggler commit serializes behind everything at F52's whole-commit
`SchemaShared.lock` (the first named property does the actual exclusion
work), and it operates on a transaction the teardown already cleared
(`rollbackInternal`'s `clear()` at `FrontendTransactionImpl:385` precedes the
CLOSED write), so its outcome funnels into the documented F85/C32 torn-commit
rare-event ground. But the fresh sentence promotes the gate from incidental
behavior to a named load-bearing property whose relaxation "re-opens
two-writer exposure" — and a property promoted to load-bearing inherits the
fold's own standard of rigor, which one paragraph earlier insists on a
dedicated volatile for the engage/teardown race. As written, a reader
auditing the heal's exclusion finds a guarantee ("never") the field's memory
mode cannot give.

Fix: one clause, either direction — (a) state the cap as best-effort
(visibility-lagged) with F52's lock scope as the exclusion authority, or
(b) pin the visibility edge: make `status` volatile (one word at `:223`), or
have `commitImpl` re-check the F104 teardown mark (already volatile, already
written before the release pass) alongside `checkOpenness`. Affected: F106,
D7 (teardown bullet exclusion sentence).

## C41: The re-keyed Guard (F38) bullet splits release-mismatch outcomes — "rejected loudly" vs "warn-noops" — contradicting the single warn-log outcome the mechanism bullet it cites specifies for all mismatch arms [MINOR]

The F107 fold re-keyed the Guard (F38) bullet to: "a different-session
presenter is rejected loudly, **and** a stale same-session presenter loses
the CAS and warn-noops" (D7 acquire/release bullet). The abnormal-termination
bullet it points readers at specifies one outcome for all three mismatch
arms: "on mismatch (different session, stale ordinal, holder already null)
the guard warn-logs and leaves the permit untouched." The explicit contrast
in the new sentence — loudly for one arm, warn-noop for the other — implies
two behaviors where the mechanism has one. If "rejected loudly" means a
throw, it is wrong twice over: the release site sits in the outermost
teardown `finally`, where the same D7 bullet invokes the F97 mask shape to
justify warn-noop for the late owner ("a throw from the teardown `finally`
would mask the owner's real exception"), and a different-session presenter
reaches that same site. If it means the warn-log, the contrast is empty and
steers an implementer toward inventing a severity split the design rejected.
The teardown bullet's pre-existing "A buggy different-session release is
rejected loudly" carried the same ambiguity through passes 10–11
unchallenged, but the F107 fold has now reproduced it at the exact anchor
F107 existed to clean, and sharpened it into a two-outcome split — fresh
text, fresh attack surface.

Fix: one wording pass — all release-site mismatches warn-log and leave the
permit untouched (per F97's mask rationale); "loud" belongs to the
engage-side throw (the F105 predicate), the only D7 site where a throw is
both safe and intended. Align the teardown bullet's "rejected loudly"
sentence in the same pass. Affected: D7 (Guard (F38) bullet and the teardown
bullet's F38 sentence), F107, F97.

## Failed attacks

- **F104 holder-vs-mark Dekker soundness.** All four accesses are
  volatile/atomic (dedicated volatile mark, `AtomicReference` holder), so
  they sit in the synchronization order; store-then-load on both sides
  yields the at-least-one-sees guarantee exactly as claimed. Sound for the
  two variables it names (C39 is about the third).
- **Exactly-once release under three presenters** (teardown pass, engage
  self-release, owner `finally`): every release is gated by the single
  holder CAS, so all orderings net exactly one permit release; the engage's
  self-release winning while a later teardown pass finds holder-null
  warn-noops, and vice versa.
- **The separate-flag-not-CLOSED rationale.** Confirmed against the tree:
  `internalClose` runs `rollback()` (`:2227`) before `status = CLOSED`
  (`:2234`), and `rollback()` calls `checkOpenness` (`:3256`); a hoisted
  CLOSED would abort the teardown's own rollback (caught and logged at
  `:2228`–`:2230`), killing the very release pass the heal needs.
- **F104 barrier bill.** Accurate: the mark re-check is one volatile read
  per DDL engage; the mark write is one volatile store per `realClose`;
  data transactions never engage, so data paths are untouched.
- **Mark re-check vs the re-wait loop.** The re-check is positioned
  post-acquire, and failed timed-acquire iterations hold no permit; a
  torn-down session's parked engage self-releases and throws at whichever
  iteration finally acquires — no per-iteration re-check is needed for
  permit accounting.
- **Pool `acquire()` racing `pool.close()`.** A session handed out after
  the `getAllResources()` snapshot escapes `realClose` entirely
  (`DatabasePoolImpl:126`–`:131`), so no teardown ever runs for it and its
  owner releases normally — a session leak (pre-existing pool semantics),
  not a permit orphan.
- **F105 predicate vs dual activation.** The predicate reads
  `holder.thread`, never `activeSession`, so C34's dual-activation misfire
  (`activateOnCurrentThread` at `:3332` set-and-never-cleared) is
  structurally avoided.
- **F105 stale-holder read racing the heal** (thread re-engaging via session
  B while its abandoned session A's teardown is mid-CAS): the spurious throw
  window is exactly the teardown's CAS latency, fires only for a thread that
  abandoned an open schema tx, and post-CAS reads see null — a defensible
  loud abort, never a wedge.
- **F105 heal residue.** The winning CAS clears the holder wholesale; the
  thread member leaves with the record and there is no mutex-side
  ThreadLocal to strand — "the heal's thread-independence is untouched"
  holds.
- **"A different-thread holder parks normally (healthy contention)"
  overbreadth.** The non-healthy sub-case (same session on a different
  thread) requires mid-tx session migration plus a second engage over a
  surviving hold; the first is scoped out (F13, enforced by
  `assertIfNotActive`), and same-transaction re-engagement is once-only via
  tx state — every reachable park case is healthy cross-thread contention.
- **F105 zero-new-fences bill.** True: the thread member rides the existing
  atomic holder store as a field of the immutable record.
- **Thread identity recycling in the holder/diagnostic.** `Thread` objects
  are held by reference and never recycled while referenced; a diagnostic
  naming a terminated thread is the dead-owner fact the operator acts on.
- **F106's F88 parenthetical.** Accurate: the allocator-seed read pinned
  inside the `stateLock.write` window is exactly F88's pin, and its F80
  id-uniqueness role transfers to the zombie-overlap case unchanged.
- **A body-phase zombie converting into a second commit-phase zombie.**
  A fresh same-session commit must pass `checkOpenness` on a CLOSED session
  — capped modulo C40's visibility caveat — and a fresh-session commit is a
  legitimate successor, not a zombie; the cap's *structure* survives, only
  its memory mode is the C40 gap.
- **F108 entrant/freezer Dekker against the real code.** Sound:
  `LongAdder.increment` CASes a volatile base/cell (Striped64), `sum()`
  reads volatile cells, `freezeRequests` is an `AtomicInteger`; the
  per-iteration store/load argument holds even though `sum()` is not an
  atomic snapshot — a sum that misses the entrant's increment forces the
  entrant's `:35`/`:46` read to see the freeze and back out.
- **Lost-unpark between the `:38` decrement and the `:44` list add.**
  Closed by the `:46` re-check ordered after the tail-CAS add and by the
  release side's decrement-before-cut order (`:95` before `:105`): if the
  entrant's `:46` read saw a positive count, the SO chain puts its node add
  before the cut, so the cut unparks it; if the release won, the `:46`
  re-check reads zero and never parks.
- **Kind-aware arm vs `throwFreezeExceptionIfNeeded`.** Today's
  throw-supplier semantics (`:114`–`:118`) are untouched for data entrants;
  the schema arm adds operator-kind-park-mode → throw without touching the
  supplier map iteration.
- **"Armed only for the schema-commit entrant" plumbing.** A two-signature
  change (`startTxCommit` → `startToApplyOperations` → `startOperation`)
  carrying the already-pinned schema-carrying signal; pin (3) confines the
  gate to the frontend-commit path, and `startToApplyOperations` has three
  production callers (pass-11 PSI, inherited) of which the two wrappers are
  quarantined.
- **depth≠0 fast path bypassing the schema gate (`:32`).** The frontend
  commit is the outermost storage operation on its thread (depth 0 at
  `startTxCommit`); nested `startOperation` calls inside the commit are
  post-gate by design — F97's leak analysis depends on exactly this
  structure.
- **Spurious `LockSupport` unparks.** Benign: the wake loop re-evaluates on
  every return from `park`, which is the desired behavior (and with C38's
  fix, the mechanism the design wants anyway).
- **F107 §2a map sweeps.** The F71 entry now ends at the F96 re-reversal
  and the F79 amendment block carries the two-settlement supersession; no
  remaining reader path lands on a dead primitive.
- **F107's F13 bound.** The fresh parenthetical dates the survey conclusion
  and points at the live primitive; the stranding caveat that follows reads
  through the same date bound, and the live D7 carries the migration
  scope-out on its own terms.
