# YTDB-382 — Adversarial pass 10: concurrency (2026-06-12)

Tenth adversarial pass, single lens: races, lock ordering, lock-ownership
semantics, memory visibility, atomicity seams, thread-binding, deadlock,
check-then-act compounds. Surface attacked: the pass-9 settlement text only —
the decision-log changes in `git diff a031b4f73a..c496195c8b` (D7's
mutex-primitive swap and rewritten teardown bullet, the F79 token withdrawal,
the F93 fold's park/throw split and wiring-pin reconciliation, and the
correction notes on F71/F79/F84/F85/F87/F92). All line citations verified
against the live tree; **mcp-steroid was reachable and used**:
`ReferencesSearch` inventories were run for
`FrontendTransactionImpl.rollbackInternal`, `FrontendTransactionImpl.close`,
and `OperationsFreezer.endOperation` (the last has exactly one production
caller, `AtomicOperationsManager:442`), and the five-site
`freezeWriteOperations` set was re-confirmed. The F92 Gremlin-threading ground
was not re-explored; its citations are inherited as settled. Claims below are
marked PSI-verified or tree-read.

Verdict: 0 BLOCKER, 2 MAJOR, 2 MINOR. The ReentrantLock-semantics claims in
the swap pins are correct (foreign unlock throws; reentrant hold count would
admit a second session; the semaphore re-wait would hang on the same shape),
and the F93 acceptance triple's third line pins exactly what the tree does.
Both MAJORs are settlement text contradicting either the tree or its own
ledger: the swap's "nothing real is lost" claim fails on the one cross-thread
path the same pin names, and the reconciled wiring-pin rationale attributes
the mask to the wrong pin while deleting the accurate corruption consequence.

---

## C29: "Nothing real is lost" is false on the path pin (1) itself names — under the withdrawn semaphore the pool-shutdown release succeeded after rollback and DDL recovered; the lock wedges it until restart, and F92, F79, and D7 now contradict each other on exactly this case [MAJOR]

D7's lock-swap pin claims outcome-neutrality:

> "(1) A foreign-thread unlock now throws: the cross-thread `close()` path
> (pool shutdown of an abandoned session) catches and warn-logs it, and the
> mutex stays held until restart, uniform with the wedged-owner scope
> decision. Nothing real is lost: the routine disconnect releases on the
> owner thread (the Gremlin kill path rolls back on the session's own
> single-thread executor, F92 ground), and the wedged/dead cases had no
> foreign-release caller under the semaphore either."

and the F71 correction generalizes it:

> "In the residual wedged/dead-owner cases the semaphore's cross-thread
> releasability had no remaining caller, so the primitive choice no longer
> changes any outcome"

The no-loss argument enumerates {routine disconnect, wedged, dead} — and the
pin's own subject is a fourth case outside that enumeration: pool shutdown of
an **abandoned** session, owner thread neither wedged nor dead, teardown run
to completion by a live foreign thread. On that path the two primitives
produce different outcomes, and the foreign-release caller exists under both.

Tree evidence for the path (all tree-read): `DatabasePoolImpl.close()`
iterates every resource and calls `realClose()`
(`DatabasePoolImpl:125`–`:134`); `realClose` activates the session on the
closing thread and runs the full close
(`DatabaseSessionEmbeddedPooled:58`–`:61`), which reaches `rollback()` inside
`internalClose` (`DatabaseSessionEmbedded:2227`) and from there
`rollbackInternal` → `close()` (`FrontendTransactionImpl:400`) — the
exemption the design cites (`FrontendTransactionImpl:130`–`:133`). The
teardown completes: the tx is fully rolled back before the outermost release
point runs on the closing thread. Under the withdrawn F79 design that release
is the well-ordered case, by the settlement's own analysis — F92's facet (a)
states "a foreign thread running the same session's rollback reads the
*current* token from session state and wins the CAS". The permit frees and
DDL recovers without a restart. Under the thread-owned lock the same release
throws, the pin warn-logs it, and DDL is down until process restart. The
primitive choice changes the outcome on exactly the path the pin governs;
something real is lost, and it is the self-healing of the one cross-thread
teardown class the design keeps.

The ledger now contradicts itself across three fresh records. F92's
resolution rejects facet (a)'s thread-carrying token because, among other
things,

> "it would turn pool-shutdown cleanup of an abandoned schema tx into a
> permanent mutex wedge, and it would forbid `releaseStranded`, the
> YTDB-1114 reaper's entry point."

Two commits later the design adopted a primitive with both properties: D7's
pin (1) accepts that same wedge as "uniform with the wedged-owner scope
decision", and the F79 correction states "`releaseStranded` has no planned
caller" while D7 states "1114 reclaims SI resources, not the mutex". A reader
of F92's resolution alone concludes the wedge is disqualifying and YTDB-1114
needs a mutex-release entry point — the exact opposite of the settled D7. The
F92 record carries no correction note, although F84 and F85 received them in
the same settlement.

Concrete failure: an implementer ships the swap believing it outcome-neutral,
so no mitigation is specified for the downgraded case. An embedded
application that closes a `DatabasePool` while one borrowed session holds an
open schema tx (or a server pool teardown in the same state) converts what
the planned design handled as orderly cleanup-plus-release into a
DDL-until-restart outage, with the warn-log as the only trace and no
YTDB-550 escalation pinned, because the rationale ledger says nothing was
lost. Separately, a future pass that consults F92's facet-(a) rejection
re-derives the semaphore-plus-token. Fix is small: either scope the no-loss
claim honestly (the abandoned-session pool-shutdown case trades self-healing
for wedge-until-restart, accepted because pool shutdown usually precedes
process exit — if that is the argument, state it) and add the matching
correction note to F92, or have the pool-shutdown path skip the unlock
attempt and escalate to the YTDB-550 monitor explicitly.

## C30: The reconciled wiring-pin rationale is backwards — violating throw-before-increment cannot produce the `endOperation` mask, it produces the freezer-count corruption the fold deleted; the mask is produced by violating a clause that never made it into D7 [MAJOR]

The F93 fold rewrote D7's wiring-pin parenthetical to:

> "it throws strictly before the freezer depth increment (else
> `endAtomicOperation`'s unconditional `endOperation()` masks the gate
> throw, `AtomicOperationsManager:442`; wording reconciled to F87's per F93)"

replacing the prior "corrupts the freezer count", on F93's ruling that "the
F87 wording is the accurate one". The grounding added to F87 and F93 reads:

> "at depth 0 it throws `IllegalStateException` before any decrement, so a
> misplaced gate throw is replaced by the finally-side exception (the mask,
> user-visible) while the orphaned count increment leaks (the corruption,
> secondary)."

The tree contradicts the attribution. `OperationsFreezer.endOperation` has
exactly one production caller — the `finally` of `endAtomicOperation`
(`AtomicOperationsManager:441`–`:443`, PSI-verified). On the frontend-commit
path, `endAtomicOperation` is reachable only from the
rollback-or-`endTxCommit` `finally` (`AbstractStorage:2396`–`:2425`, via
`rollback`'s `:3704` or `endTxCommit`'s `:4626`), and that `finally` guards a
try entered only after `startTxCommit` (`:2293`) has returned — that is, only
with the freezer depth already at 1. Walk the "else" branch of the pin (gate
throws after the depth increment) through this structure:

- Throw escapes from inside the try (`:2294` ff.): the `finally` runs
  `rollback(error, atomicOperation)` → `endAtomicOperation` →
  `endOperation()` at depth 1, which decrements cleanly
  (`OperationsFreezer:59`–`:69` throws only at depth ≤ 0). The gate throw
  propagates **unmasked**. No mask exists on this branch.
- Throw escapes from inside `startTxCommit` itself, after `startOperation`
  returned (e.g. a probe added inside `startToApplyOperations` after
  `AtomicOperationsManager:107`): the paired `finally` is never entered, and
  no other caller of `endOperation` exists. The thread's `operationDepth`
  leaks at 1 and `operationsCount` leaks at +1 permanently. Every later
  `startOperation` on that thread takes the depth≠0 fast path
  (`OperationsFreezer:32`) and bypasses the freeze gate entirely; every later
  `freezeOperations` spins forever in the drain loop (`:81`). One misrouted
  schema commit then hangs `freeze(db)`, `doSynch` (`AbstractStorage:3749`,
  behind every `synch()`), and both backup freezes (`DiskStorage:356`,
  `:1248`) forever, parks every other thread's writes behind the stuck
  freeze, and lets the poisoned thread write through engaged freeze windows.
  That is the "corrupts the freezer count" consequence the fold deleted as
  the inaccurate wording — it was the accurate one for this pin.

The mask the grounding describes requires `endOperation` to run at depth 0
during the gate throw's unwind — reachable only when the net-new
schema-commit path calls `endAtomicOperation` on the unwind of a depth-0
throw, i.e. when `startTxCommit`/the gate sits *inside* the
rollback/`endTxCommit`-paired try. Exactly that placement is forbidden by
F87's clean-unwind clause — "throw before depth increment; `startTxCommit`
outside the rollback/endTxCommit branch" — whose second half the fold did not
carry into D7's bullet. Today's tree demonstrates the clean variant: a data
commit rejected by a throw-mode freeze throws at depth 0 from `:2293`,
bypasses the paired `finally` entirely, and surfaces as the loud
`ModificationOperationProhibitedException` the acceptance triple pins.

Concrete failures: (1) an implementer building the design's net-new
schema-commit path from D7 alone satisfies both stated pins — probe throws
before any freezer touch, frontend path only — yet writes the natural
`try { startTxCommit(op); … } finally { error != null ? rollback : endTxCommit }`
shape, and every pre-lock or in-window gate throw now surfaces as
`IllegalStateException("Invalid operation depth 0")` from the `finally`,
replacing the operator-facing freeze rejection; the acceptance triple's first
line asserts only "loud error", so the wrong exception can pass it and ship.
(2) The consequence model misranks the pin it annotates: a reviewer triaging
"violating this merely masks an exception" deprioritizes a misplacement whose
real effect is a storage-wide freeze hang plus a per-thread freeze-gate
bypass. Fix: restore "corrupts the freezer count (and disables the gate on
the thread)" as the increment-ordering pin's consequence, attach the mask to
an explicit second clause in D7 — the F87 clean-unwind placement,
`startTxCommit` outside the rollback/`endTxCommit` branch — and correct the
F93/F87 grounding paragraphs, whose depth-0-mask-plus-orphaned-increment
compound no single misplacement can produce.

## C31: "Cleared at release" is unconditional while the release itself can fail — the failed foreign unlock anonymizes the wedged lock and feeds null to the different-session guard [MINOR]

D7 specifies the holder field's lifecycle in two clauses that collide on the
pool-shutdown path:

> "A volatile holder-diagnostic field (owning session, acquire ordinal)
> written at acquire and cleared at release feeds the F61 timed-acquire
> diagnostic and the different-session rejection."

and pin (1): the foreign-thread unlock throws and "the cross-thread `close()`
path … catches and warn-logs it, and the mutex stays held until restart."
Nothing states what happens to the holder field when the release *fails*. The
natural single-release-point implementation clears it unconditionally
(`holder = null; try { lock.unlock(); } catch (IllegalMonitorStateException e)
{ warn(…); }` — or clear inside the same `finally`); a plain or volatile
field write succeeds from any thread, so the pool-shutdown path clears the
holder of a lock it then fails to release. Two consumers the design names
then read null for the rest of the process lifetime:

- The F61 re-wait diagnostic ("a diagnostic naming the holder") names nothing
  for precisely the wedge the operator must diagnose until restart — the only
  state in which the diagnostic is the promised observability tool.
- Pin (2)'s engage guard ("reads the holder field before acquiring and throws
  loudly when the lock is held by a different session on the current thread")
  evaluates `isHeldByCurrentThread() == true` with a null holder when the
  abandoned tx's owner thread — alive, application-owned — later starts a new
  session's schema tx. `holder.session` NPEs, or the null-guarded variant
  silently admits the new session through the reentrant hold count: the exact
  admit pin (2) exists to reject. The admitted tx's own release then unlocks
  once, leaving hold count 1, and the thread becomes a permanent
  silent-admit channel while every other thread re-waits forever.

Concrete failure: an implementer following "cleared at release" literally
produces the anonymous wedge and the null-fed guard; no test in the
acceptance set exercises engage-after-failed-foreign-release. Fix is one
sentence in D7: the holder clear is conditional on the unlock succeeding
(equivalently, the foreign path skips both clear and unlock and only
warn-logs), mirroring the `:954` thread-id gate the same bullet cites for the
`tsMin` release.

## C32: "No foreign `rollbackInternal` entry exists" overstates the Gremlin-scoped result — the pool-shutdown path is one, and it closes borrowed mid-commit sessions, not only abandoned ones [MINOR]

F85's fresh correction note closes with an absolute claim:

> "The Gremlin hooks run on the eval worker and resolve per-thread
> transactions, so no foreign `rollbackInternal` entry exists; YTDB-1113 was
> closed as invalid. 'No second claimant' holds today without any
> prerequisite fix."

The PSI sweep that grounds it covered `rollback()` sites in `server/src/main`
(F92). Core retains a foreign `rollbackInternal` entry the same settlement
documents one entry over: `DatabasePoolImpl.close()` → `realClose()` →
`internalClose` → `rollback()` → `rollbackInternal`
(`DatabasePoolImpl:125`–`:134`, `DatabaseSessionEmbeddedPooled:58`–`:61`,
`DatabaseSessionEmbedded:2227`; the `:130`–`:133` exemption admits it), whose
`BEGUN, COMMITTING ->` arm proceeds for the foreign caller
(`FrontendTransactionImpl:368`). And the path's reach is wider than D7's
"pool-shutdown `close()` of abandoned sessions": `getAllResources()` returns
checked-out sessions too (`ResourcePool:191`–`:195` concatenates `resources`
and `resourcesOut`, tree-read), so a `pool.close()` racing an in-flight
borrowed-session commit enters the F85 tear shape with no abandonment
involved — owner inside `doCommit` serializing `recordOperations`, closer
thread's `rollbackInternal` running `clear()` against the same maps.

This does not reopen F85's scope decision: the pre-existing rare-event
classification under the YTDB-550 monitor is D7's to make, the race ships on
`develop` today, and the design adds no new initiator. The defect is the
record's wording. "No second claimant … without any prerequisite fix" states
a structural impossibility the tree contradicts, in the very record whose
job is to document where the torn-commit shape survives; and "abandoned
sessions" understates the caller class an implementer must reason about when
deciding what the cross-thread `close()` path may safely touch. Concrete
failure: a Phase-1 implementer takes F85's correction at face value, treats
the `BEGUN, COMMITTING ->` arm as owner-only, and extends it (for example,
folding the D7 mutex release or the freezer disengage into that arm) without
the foreign-caller tolerance the `:130`–`:133` exemption obligates. Fix: scope
the F85 sentence to the Gremlin inventory ("no Gremlin-side second claimant;
the core-side pool-shutdown entry remains the documented rare-event path"),
and widen D7's caller-class wording from "abandoned sessions" to "any
checked-out session at pool close".
