# YTDB-382 — Adversarial pass 11: concurrency, scoped (2026-06-12)

Eleventh adversarial pass, single lens: races, atomicity seams, lock/permit
lifecycle, memory visibility, interleaving analysis, check-then-act compounds.
Surface attacked: the pass-10 settlement text only — the F96/F97/F98/F99 folds
in `git diff da824ff9d5..HEAD` (D7's `Semaphore(1)` re-swap with the
session-keyed atomic release guard, the three-pin freezer-wiring rewrite, the
F71 re-correction, the F79 amendment, the F92/F98/F99 notes). All line
citations verified against the live tree; **mcp-steroid was reachable and
used**: `ReferencesSearch` re-confirmed `OperationsFreezer.endOperation` has
exactly one production caller (`AtomicOperationsManager:442`),
`OperationsFreezer.startOperation` exactly one (`AtomicOperationsManager:107`),
and `startToApplyOperations` three (`AbstractStorage:4710` = `startTxCommit`,
plus the two wrappers at `AtomicOperationsManager:135`/`:162` that pin (3)
quarantines). The F92 Gremlin-threading ground was not re-opened. Claims below
are marked PSI-verified or tree-read.

Attacked and survived: the CAS-atomicity claim (exactly one of the owner
`finally` and the pool teardown clears the holder and releases the permit —
the witness-CAS shape settles the named race); the F98 dissolution (no
clear-then-fail path exists when only the winning CAS clears); the F97
consequence attribution (both violation arms check out against
`OperationsFreezer:32`/`:56`/`:62`/`:81` and the `AbstractStorage:2293`
placement of `startTxCommit` outside the `:2396`–`:2425` paired `finally`);
the F99 widening (`getAllResources()` concatenates `resources` and
`resourcesOut`, `ResourcePool:191`–`:195`, tree-read); and the ordinal's ABA
story (no stale presenter survives pooled-session reuse, because pool close is
terminal for the session and same-session sequential releases are
program-ordered; the ordinal is redundant belt over the session key, which is
harmless). The transient null-holder window the F61 re-wait diagnostic can
observe mid-acquire is diagnostic-only and benign.

Verdict: 0 BLOCKER, 2 MAJOR, 3 MINOR. The re-swap's release guard is sound
for the one race it names, but the fold specifies no acquire-side handshake
against the one-shot pool teardown (the edge the withdrawn F79 sketch had
flagged and solved with reap retry), and the holder record it names three
times lacks the thread field its own engage guard needs.

---

## C33: The engage/teardown handshake is unspecified — a pool teardown racing a mid-flight acquire misses the engagement, the permit leaks with no remaining releaser, and the acceptance pair exercises neither this window nor the record's survival across `clear()` [MAJOR]

D7's heal guarantee is keyed entirely on the release site finding the
engagement: the teardown "presents its own session (the `this` of the
teardown) and the acquire-time ordinal". That works only when the acquisition
is fully recorded before the teardown's single pass reads it. The fold
specifies no ordering for the acquire side (permit acquire, holder write,
engagement record into tx/session state) and no behavior for a teardown that
arrives mid-acquire — and the pool teardown runs **once**:
`DatabasePoolImpl.close()` iterates `getAllResources()` and calls
`realClose()` per session with no retry (`DatabasePoolImpl:125`–`:134`,
tree-read).

The withdrawn F79 sketch had exactly this edge and named its mitigation:

> "Implementation edge: a reap firing between `tryAcquire` success and
> `owner.set` sees a null or stale slot and no-ops the semaphore; the F61
> re-wait diagnostic is the backstop and **the next reap cycle sees the
> token**."

The next-cycle backstop is what made the no-op tolerable, and it does not
exist for `pool.close()`. The interleaving an implementer following the fold
text produces:

1. Owner thread T1, session S, active tx, first schema mutation: engage
   acquires the permit; the holder write and the engagement record have not
   happened yet (or have happened in the other order — every ordering has
   this window, because nothing excludes the teardown during engage).
2. Pool-closing thread T2 runs S's teardown: `rollbackInternal` proceeds
   (`FrontendTransactionImpl:368`), the release site finds no engagement
   (holder null or stale, no recorded ordinal), warn-logs or skips, and
   touches no permit — the per-design behavior for a mismatch.
   `internalClose` then sets `STATUS.CLOSED` (`DatabaseSessionEmbedded:2211`
   ff., tree-read).
3. T1 completes the acquire: permit held, holder = (S, n), session closed.
   Its next session operation throws `DatabaseException` via `checkOpenness`
   (`:3341`). User code reacts with `session.rollback()` — which throws at
   `checkOpenness` (`:3253`/`:3256`) **before** reaching the outermost
   teardown `finally` D7 pins as the single release point (`:3131`/`:3253`).
   `commitImpl` gates identically (`:3145`/`:3151`).

No releaser remains: the teardown already ran, the owner is locked out of the
release point by the openness gate, and the YTDB-1114 reaper reclaims
registrations, not the mutex. DDL is wedged until restart — the exact outcome
F96 was resolved to reject ("we can not tolerate issues with `pool.close()`").

A second sub-failure rides the same under-specification: on the **normal**
heal path the release `finally` must read the acquire-time ordinal from state
that `rollbackInternal` has already processed — the BEGUN/COMMITTING arm runs
`clear()` (`:368` ff.) and `close()` nulls `atomicOperation` and zeroes
`storageTxThreadId` (`:954` region, tree-read). If the engagement record lives
in the analogous tx state and is wiped before the outermost `finally` consumes
it, the heal presents nothing, warn-logs, and the mutex wedges on the path the
design celebrates. The fold pins statement-level ordering elsewhere (F88, F97)
but leaves both of these orderings unstated.

The acceptance pair is blind to both: "pool-close heal" starts from a fully
engaged tx, and "owner `finally` racing the pool teardown" has both sides past
the release point. Fix: pin the handshake — either the teardown marks the
session dead first and the engage path re-checks after acquiring (release the
permit and throw on a closed session), or the engage sequence
(record-then-acquire-then-holder) plus a teardown-side definition for the
half-engaged state; pin that the engagement record survives `clear()`/`close()`
until the outermost `finally` consumes it; and add a third acceptance line:
`pool.close()` racing the engage itself → either the tx aborts loudly with the
permit released, or the heal completes — never a held permit with no releaser.
Affected: D7, F96, F61.

## C34: The holder record `(owning session, acquire ordinal)` lacks the thread identity pin (2)'s engage guard requires — the pass-9 lock supplied it natively, the re-swap dropped it without replacement, and both natural substitutes reject healthy contention [MAJOR]

Pin (2) survives the re-swap verbatim: "the engage path reads the holder
before acquiring and throws loudly when the mutex is held by a different
session **on the current thread**", guarding the embedded-alternation
self-deadlock (a thread parking on its own hold in the re-wait loop). Under
the pass-9 primitive the thread half of that predicate came from the lock
itself — a thread-owned lock knows its owner thread. Under the `Semaphore(1)`
nothing supplies it: the fold names the holder record as the guard's input
("the holder feeds the F61 timed-acquire diagnostic, the engage-side
rejection, and the release guard") and defines it three times — D7, the F96
resolution, the F79 amendment — as exactly `(owning session, acquire
ordinal)`. A semaphore has no owner; the record has no thread. The pin is
unimplementable from the named state.

The natural substitutes an implementer reaches for both produce concrete
failures:

- **Drop the thread qualifier** (`holder.session != engagingSession →
  throw`): every contended engage across threads — the routine two-DDL case
  D5 and the F61 re-wait loop exist for — throws instead of parking. That is
  abort-on-healthy-contention, the D5 violation F71 arm (1) was accepted to
  forbid, and no pinned test covers it: the acceptance pair tests the heal
  and the same-session release race, never a contended engage, so it ships
  invisible.
- **Ask the session** (`holder.session.isActiveOnCurrentThread()`):
  `activeSession` is a per-session `ThreadLocal<Boolean>`
  (`DatabaseSessionEmbedded:250`) set by `activateOnCurrentThread()` (`:3332`)
  and never cleared on other threads — the dual activation the F79 record
  already established, and which `realClose()` exercises by design
  (`DatabaseSessionEmbeddedPooled:58`–`:61` activates the closing thread,
  tree-read). The predicate is true on multiple threads at once, so the guard
  rejects engages on any thread that ever activated the holder session —
  spurious aborts with the same D5 shape.
- **A ThreadLocal inside the mutex** ("this thread holds it") breaks on the
  heal itself: the foreign-thread release cannot clear the owner thread's
  ThreadLocal, so the owner thread's next engage for a new session is
  rejected against a mutex that is actually free.

The F61 diagnostic has the same gap one severity lower: a wedged-owner report
that names a session but no thread leaves the operator without the one fact
the wedged/dead-owner scope decision says they act on.

Fix is one field: the holder record carries the acquiring thread —
diagnostic-and-guard input only, never compared by the release CAS (release
stays thread-independent, which is the point of F96). Pin (2)'s predicate then
reads `holder.thread == currentThread && holder.session != engagingSession`.
Affected: D7, F96, F61.

## C35: The heal can fire while the torn owner's commit thread is still inside the four-lock window — exclusion-after-heal rests on two unstated properties, and the fresh text's only owner-side claim covers the body-phase owner only [MINOR]

The teardown bullet's residual-owner claim is: "The torn-down owner's loud
signal stays what it is on develop: its next operation on the closed session
throws." True for an owner torn down mid-body (tree-read: every session
operation gates on `checkOpenness`, so the zombie never reaches commit and its
tx-local copy dies — this is also what makes the heal-then-second-writer
sequence safe in the body phase). It says nothing about the commit-phase
owner, which is the exact reach F99 just widened D7 to acknowledge:
`pool.close()` tears down a borrowed session whose owner thread is inside the
F48-scale commit, the COMMITTING arm proceeds foreign
(`FrontendTransactionImpl:368`), the teardown's release wins the CAS, and the
next DDL acquires the mutex while the zombie's commit thread is still
executing inside the four-lock window.

D7's exclusion does not actually break there, but only because of two
properties the ledger never states as load-bearing for the heal:

1. **F52's whole-commit schema-lock scope.** The successor's seed and commit
   serialize behind the zombie's remaining commit at `SchemaShared.lock`
   (held across the whole commit window), and the F88 allocator-seed pin
   inside `stateLock.write` keeps F80 id uniqueness safe even though the
   mutex no longer serializes the two commits. Relax either — for example a
   future snapshot-based seed that reads outside the schema lock — and the
   heal silently re-opens two-writer exposure on this path.
2. **The openness gate.** A body-phase zombie can never convert into a
   commit-phase one after the heal, because `checkOpenness` throws at
   `commitImpl:3151` — the property that caps the overlap at "one zombie
   commit already in flight", never a fresh one.

The torn unit itself stays the documented F85/C32 rare event; this finding is
about the mutex property, which under the pass-9 wedge incidentally held
through the race and under the heal is delegated to (1) and (2) without either
being written down. Same shape as the pass-9 dry-list precedent ("F86+F87
survived on an unstated enclosure property" → pin candidate). Fix: one
sentence in the teardown bullet naming both properties as what the heal's
exclusion rests on, so a later refactor of the seed path or the openness gate
trips over a stated invariant instead of an unstated one. The acceptance pair
needs no third line for this — it pins permit accounting, and the exclusion
half is exactly what the sentence would protect. Affected: D7, F96, F99.

## C36: Residual thread-owned-lock language survives the re-swap in live anchors — D7's own "Guard (F38)" bullet still mandates the same-thread release assert the teardown bullet revokes [MINOR]

The F96 fold scope (D7 header and primitive paragraph, F71, F79, F92, F98,
F99) missed four live anchors that still describe the pass-9 or pass-8
primitive:

1. **D7's acquire/release bullet, "Guard (F38)"**: "assert the releasing
   thread equals the acquiring thread; a session migrated to another thread
   mid-tx would make this `finally` release throw
   `IllegalMonitorStateException`." The same entry's teardown bullet now says
   the opposite — "F38's same-thread rule becomes this session-identity
   rule" and "the guard is thread-independent by construction, which is the
   point" — and the heal path depends on a foreign-thread release succeeding.
   An implementer wiring the bulleted assert at the release point fires
   `AssertionError` on the pool-teardown heal under `-ea`; the pool-close
   acceptance test catches it loudly, but two bullets of one D entry
   prescribing opposite release rules is the F96-class self-contradiction one
   pass later. `IllegalMonitorStateException` is also dead semantics — no
   primitive in the settled design can throw it.
2. **§2a map, F71 entry** (line ~711): ends at "arm (2) reversed 2026-06-11
   at the pass-9 settlement — thread-owned write lock". The map is the
   stated navigation layer for resolved findings; a reader routed through it
   lands on the rejected primitive.
3. **§2a map, F79 entry** (line ~735 and the pass-8 amendment block): still
   reads "owner-token release is retained ... `releaseStranded` parked as
   the postponed reaper's entry point" — two settlements stale, now
   accidentally half-true (the CAS shape is back, session-keyed; the
   `releaseStranded` arm is withdrawn).
4. **F13's live conclusion** (line ~301): "A thread-owned `ReentrantLock`
   held across the tx body (D7) is sound" — a ground-truth record, but the
   "(D7)" parenthetical binds the dead primitive to the live design.

One fold pass fixes all four: rewrite the Guard (F38) bullet to the
session-identity rule (or delete it and point at the teardown bullet), append
the F96 state to both map entries, and bound F13's sentence to its survey
date. Affected: D7, §2a, F13.

## C37: Pin (i) is satisfiable by a pre-call probe placement that re-opens F86's outage — a park-mode operator freeze engaging in the probe-to-`startOperation` window parks the schema commit inside the four-lock window for the whole freeze [MINOR]

The three rewritten wiring pins compose correctly against the mask and the
leak — verified against the tree: a gate throw before the depth increment and
outside the `:2396`–`:2425` paired `finally` reaches neither `endOperation`
path, `startTxCommit` at `AbstractStorage:2293` already sits outside that
try, and the leak/mask consequences match `OperationsFreezer:32`/`:56`/`:62`
and the sole `endOperation` caller at `AtomicOperationsManager:441`–`:443`
(PSI-verified). What the pin set does not constrain is **where** the gate
lives, and one compliant placement defeats the bullet's own promise.

"Throws strictly before the freezer depth increment" is trivially satisfied
by a standalone probe placed immediately before `startTxCommit` — it throws
before the increment because it runs before the call. That placement leaves a
window between the probe and `startOperation`'s own `freezeRequests` check.
A **park-mode operator freeze** (`freeze(db, false)`,
`AbstractStorage:3905` — no throw supplier) engaging in that window parks the
schema commit at `OperationsFreezer:47` inside the four-lock window: the
freeze itself completes (the entrant decremented `operationsCount` at `:38`
before parking, so the `:81` drain passes), and the commit stays parked for
the operator freeze's full duration holding the three metadata locks plus the
in-window `stateLock` position — the C18/F86 outage, one lock stronger, on
the path step (4) exists to keep loud. The trigger window is
instructions-wide, but the consequence lasts the whole freeze (minutes for a
backup snapshot), and the acceptance triple cannot see it: line 1 pre-engages
the freeze before the commit, lines 2 and 3 exercise the transient and
data-write arms.

The fused placement has no such window: the gate as the schema-path,
kind-aware variant of `startOperation`'s own check, inside the wake loop
(`:35`–`:50`), where every unpark re-evaluates `freezeRequests` and the
operator-kind test throws where `:40` throws today — count-balanced by the
shipped `:38` decrement and still before the `:56` depth increment, so all
three pins hold. The fold text's "the in-window gate **stays**" suggests this
is the intent; the pin wording just fails to exclude the probe reading. Fix:
one clause pinning the gate as the kind-aware check inside the freezer's own
entrant protocol (re-evaluated on every wake), not a separate pre-call probe.
Affected: D7, F97, F87.
