# YTDB-382 — Adversarial pass 16: micro round, scoped to the pass-15 re-amendments (2026-07-21)

Combined concurrency + crash-safety lens. Read-only round against branch `transactional-schema`
at HEAD `e2605c8ba3`. Scope is SOLELY the two pass-15 re-amendments in
`track-7-design-drafts.md` §"Amendments round 2 — adversarial pass 15 triage (2026-07-21)":

1. **CN19 re-amendment** — the new `ScalableRWLock` abort-predicate primitive
   `exclusiveLockWithAbort(BooleanSupplier abort, long pollNanos)` replacing the plain
   `stateLock.writeLock().lock()` at `commitSchemaCarry:3179`.
2. **CS15+CN21 re-amendment** — the single-owner snapshot clear: the pin at
   `AbstractStorage:2525` stays, the sole clear moves to `commit()`'s own outermost finally,
   the `applyCommitOperations:3142` clear is deleted; five-path `immutableCount`-balance test
   matrix.

Everything else (base design, rulings, pass-14 amendments, the rest of the round-2 section) is
settled and was used only as fixed context. Findings are numbered CN25+ / CS19+.

**Result: one should-fix (CS19), one suggestion (CN25). No blocker.** Both re-amendments are
implementable as sketched and mechanically sound; the two findings are a specification-wording
defect in the clear's placement (which, under its literal reading, reproduces the exact CS15
underflow class — but is deterministically caught by the re-amendment's own mandated test (a))
and a guarantee-wording overclaim plus a cheap hardening pin on the abort primitive's
admission edge.

## Surface attacked

Re-amendment (1), verbatim claims attacked:

- "Phase 1 loops `stampedLock.tryWriteLock(pollNanos)` checking `abort` between attempts —
  while queued the writer blocks no readers (readers poll `isWriteLocked()`, they never enqueue
  against the stamped writer queue, `:320-357`)."
- "Phase 2 acquires the write bit **ONCE** and holds it (new readers refused,
  writer-preference, exactly like `exclusiveLock`), then polls `abort` inside the existing
  reader-drain spin (`:604-609`)."
- "On predicate true: release the write bit fully (no residual writer-intent state — no queue
  entry, no held bit; the primitive is reusable) and return false."
- "(ii) freeze abort within one poll granularity (the bit is released before the throw, no
  reader stranded)."
- "An `InterruptedException` from the stamped timed acquire restores the interrupt flag and
  throws `DatabaseException` naming the holder/state."
- "Verified-safe V-15.5 (no new deadlock edge) applies to this primitive as it did to the loop."

Re-amendment (2), verbatim claims attacked:

- "The **sole clear moves into `commit()`'s own outermost finally**, paired lexically with the
  `:2525` pin." + "**No armed flag** is needed in the primary design."
- "`applyCommitOperations` has exactly two callers, both inside `commit()` (`:2537` data
  branch, `:3187` via `commitSchemaCarry`), so ownership moves cleanly with no handoff and no
  path that loses the clear."
- "Probe throw — occurs **before** the `:2525` pin … so no pin was taken and no clear is
  needed."
- "The pin's slightly longer hold (through the lock-release finallys and promotion) is inert."
- The five-path test matrix (a)–(e).

## Code grounding (files actually read, line-verified at HEAD)

| Reference | Verified content |
|---|---|
| `ScalableRWLock.java:320-348` | `sharedLock()`: reader sets `READING`, checks `stampedLock.isWriteLocked()`; on true, backs off and spin-yields on `isWriteLocked()`. Readers never touch the stamped queue. |
| `ScalableRWLock.java:283-296` | `isWriteLocked()` javadoc: "The underlying `StampedLock` tracks no owner". |
| `ScalableRWLock.java:389-411` | `exclusiveLock()`: `stampedLock.writeLock()` (bit acquired once), reader-state array scan with per-slot yield-spin drain. |
| `ScalableRWLock.java:422-429` | `exclusiveUnlock()`: `stampedLock.asWriteLock().unlock()`. |
| `ScalableRWLock.java:530-556` | `exclusiveTryLock()`: on finding a `READING` slot, releases the bit (`:551`) — leaves `readersStateArrayRef` cache set (precedent for "cache is not writer-intent state"). |
| `ScalableRWLock.java:586-616` | `exclusiveTryLockNanos()`: `stampedLock.tryWriteLock(nanos, NANOSECONDS)` at `:589` (declares `InterruptedException`); drain spin `:604-613`; budget-expiry release `:611`. |
| `AbstractStorage.java:2505-2542` | `commit()`: outermost `try` opens `:2514`; `final var session = frontendTransaction.getDatabaseSession()` `:2515`; `getTxSchemaState()` `:2522`; **pin `:2525`**; `getSortedIndexOperations` `:2527`; `getAtomicOperation` `:2528`; schema branch → `commitSchemaCarry` `:2532`; data branch `stateLock.readLock().lock()` `:2535`, `applyCommitOperations` `:2537`, read-lock release finally `:2540-2541`. Method ends in `catch (RuntimeException/Error/Throwable) → logAndPrepareForRethrow`; **no finally exists today**. |
| `AbstractStorage.java:2741-3145` | `applyCommitOperations`: `checkOpennessAndMigration` `:2750`, `makeStorageDirty` `:2752`, `startTxCommit` `:2766`, in-window rebuild `:2888` (requires pin count > 0), failure arm (rollback + registry undo) `:3033-3063`, success arm (endTxCommit `:3077`, promotion `:3120-3138`), outermost finally `:3139-3142` = `ensureThatComponentsUnlocked(atomicOperation)` `:3141` + **the clear `:3142`** (the deletion target). |
| `AbstractStorage.java:3159-3201` | `commitSchemaCarry`: SchemaShared write `:3174`, IM lock `:3176`, `stateLock.writeLock().lock()` **`:3179`** (the primitive's splice point), `enterCommitWindow` `:3185`, apply `:3187`, unwind finallys: `exitCommitWindow` `:3190`, write-lock unlock `:3192`, `releaseExclusiveLockForCommit` `:3196`, `releaseSchemaWriteLock(session, false)` `:3199`. Sole caller: `commit():2532`. |
| `AbstractStorage.java:5317-5339` | `synch()`: takes `stateLock.readLock()` **before** the transient freeze (`doSynch`). |
| `AbstractStorage.java:5347-5385` | `doSynch()`: `freezeWriteOperations(null)` `:5349`, body = engine flush + `flushAllData` (no stateLock re-entry), `unfreezeWriteOperations` finally `:5381`. |
| `AbstractStorage.java:5508-5561` | `freeze(db, throwException)` (operator freeze): takes `stateLock.readLock()` `:5510` **before** registering; throw-mode registers the `ModificationOperationProhibitedException` supplier `:5522-5525`. |
| `AbstractStorage.java:7624-7686` | `logAndPrepareForRethrow` overloads: log + `setDbName` + (`Error`/`Throwable` arms) `setInError`. No metadata/snapshot access. |
| `DiskStorage.java:357-365, 1249-1264` | The two backup transient freezes: bodies are WAL-only (`end`/`flush`/`appendNewSegment`); no `stateLock` acquisition inside the frozen body. |
| `OperationsFreezer.java` (whole file) | `startOperation`: op-count increment, freeze check, `throwFreezeExceptionIfNeeded`, park; `freezeOperations`: counter increment + **yield-spin** on `operationsCount` (freezer never parks itself); `releaseOperations`: cut-and-unpark, **no locks taken**. |
| `MetadataDefault.java:78-134` | `makeThreadLocalSchemaSnapshot` (`:78`): rebuilds only at count 0, else increment-only; `clearThreadLocalSchemaSnapshot` (`:88`): decrement + null-at-zero, **throw-free**; `forceClear` (`:95`): throws at count ≠ 0; `rebuild` (`:117`): throws at count 0; `getImmutableSchemaSnapshot` (`:127`): per-call fresh snapshot when unpinned. |
| `IndexManagerEmbedded.java:92-104, 637-662` | `acquireExclusiveLockForCommit`/`releaseExclusiveLockForCommit`: **pure lock/unlock** — no `forceClearThreadLocalSchemaSnapshot`, no `forceSnapshot`, no listeners. Those side effects live only in the public `releaseExclusiveLock` (`:637-662`), which the commit path does not use. |
| `SchemaShared.java:756-791` | `acquireSchemaWriteLock` / `releaseSchemaWriteLock(session, false)`: modificationCounter + shared `snapshot = null` + `advanceVersion` — no thread-local pin access. |
| `AbstractStorage.java:4780-4813` | `enterCommitWindow`/`exitCommitWindow`: ThreadLocal depth only. |
| `DatabaseSessionEmbedded.java:2144/2303` | `executeReadRecord`: balanced pin/clear pair (relevant to promotion's `session.load`). |
| `EntityImpl.java:4174` | `getGlobalPropertyById` fallback: pre-existing **unpaired** pin (no clear) — pre-existing at HEAD, noted by pass-15 CS15; unchanged by either reading of the re-amendment. |
| Whole-repo grep | `applyCommitOperations`: exactly two call sites (`:2537`, `:3187`); method is `private`; no test or reflective callers. `commitSchemaCarry`: exactly one caller (`:2532`). |

## Decision criteria and premises

- **D1 (defect bar).** A finding requires a concrete counterexample trace against the
  re-amendment text as written, grounded in HEAD code, whose consequence is a broken invariant
  (deadlock, stranded waiter, lost wakeup, unbounded outage, `immutableCount` imbalance,
  masked commit outcome) — or a specification whose natural legal reading produces one (the
  house rule established in round-1 CS12 and applied by pass-15 CS15).
- **D2 (comparison baseline for "new deadlock edge").** The primitive replaces HEAD's plain
  `exclusiveLock()` at `:3179`. "New edge" is measured against that baseline, not against the
  pass-14 retry loop (which the primitive supersedes); the primitive = HEAD's single
  acquisition + an extra exit, so it can only remove blocking relative to baseline.
- **D3 (settled backstops).** The four-checkpoint invariant, kind counters, the Q-B3 probe
  hoist, and checkpoints (3)/(4)'s kind-aware throw inside `startOperation` are settled; they
  may be *relied on* as backstops but their own text is out of scope except where a
  re-amendment explicitly restates it.
- **P1.** `ScalableRWLock` readers synchronize with writers ONLY through
  `stampedLock.isWriteLocked()` polling (`:328-347`); the stamped lock's queue is
  writer-vs-writer only.
- **P2.** `StampedLock.tryWriteLock(time, unit)` cancels its waiter node on timeout and throws
  `InterruptedException` on interrupt; `unlockWrite` signals queued successors.
- **P3.** `MetadataDefault.immutableCount` is session-local (P6 of the base design); make at
  count > 0 is increment-only; clear is throw-free; `forceClear` throws at count ≠ 0.
- **P4.** All transient freezers either hold `stateLock.readLock` before freezing
  (`synch:5319`, `freeze:5510`) or run stateLock-independent frozen bodies
  (`DiskStorage:357/:1249`, WAL-only); the operator freeze registers the shared gate
  exception supplier in throw mode (`:5522-5525`); `releaseOperations` takes no locks.

---

## CN25 [SUGGESTION] — the abort predicate is never evaluated on the acquisition-success edge: guarantee (ii)'s "freeze abort within one poll granularity" silently degrades, for a freeze arriving in that window, to a checkpoint-(3)/(4) throw taken while HOLDING `stateLock.writeLock`; add one post-drain predicate check and correct the wording

**Surface attacked.** "(ii) freeze abort within one poll granularity (the bit is released
before the throw, no reader stranded)" and the carried four-checkpoint statement "the unwind
proof is unchanged: on abort the write bit was never durably held past the throw."

**The window (exhaustively enumerated poll points).** The predicate is consulted at exactly
two kinds of points: (α) between phase-1 attempts; (β) inside phase-2's per-slot drain spin
(`exclusiveLock`'s `:404-408` shape). Therefore the predicate is *never* consulted on these
paths:

1. **During the final blocking `tryWriteLock(pollNanos)`** — up to `pollNanos` of blindness,
   ending in acquisition (not in a re-check).
2. **Phase 2 with an empty drain** — no slot is `READING`, the drain loop bodies never
   execute (`:398-409`: the inner `while` is skipped per slot), the method returns `true`
   without one predicate evaluation after the bit was set.
3. **After the last reader drains, before return** — same shape as 2.

**Counterexample trace (miss window, concrete).** Armed schema commit S passes the hoisted
probe and checkpoints, enters `exclusiveLockWithAbort`; no readers are in flight; the first
`tryWriteLock` succeeds instantly; phase 2's scan finds no `READING` slot; returns `true`.
One microsecond earlier, an operator `freeze(db, true)` on another thread (already holding
its `stateLock.readLock`, `:5510` — acquired before S's bit) incremented
`operatorFreezeRequests`. S now proceeds: `enterCommitWindow` (`:3185`) →
`applyCommitOperations` → `checkOpennessAndMigration` (`:2750`) → **`makeStorageDirty`
(`:2752`)** → `startTxCommit` (`:2766`) → `startOperation` → checkpoint (3) kind-aware gate
throws the shared factory exception — **while `stateLock.writeLock`, `SchemaShared.lock`, and
the IM lock are all held**. The unwind through `:3190-3199` is clean and prompt
(microseconds), and safety is preserved (D3: the freezer's `operationsCount` drain plus
checkpoints (3)/(4) own the admitted-commit case).

**Why this is a finding at all (and why only a suggestion).** Two texts overclaim:

- Guarantee (ii) as written promises abort within one poll granularity, unconditionally. In
  the miss window there is no abort at checkpoint (2) at all; the freeze-side handling
  transfers to checkpoints (3)/(4) with a *different held-lock profile* than the one
  checkpoint (2) advertises ("the bit was never durably held past the throw" is true of
  checkpoint (2)'s own throws, but the miss-window throw at checkpoint (3) happens with the
  write bit held — the carried pass-14 sentence "the first three throw with the write lock
  not yet held" is inaccurate for a schema-carry entrant reaching checkpoint (3), since
  `startOperation` runs inside the held write lock, `:3179` → `:2766`).
- The window also spans `makeStorageDirty` (`:2752`): one durable dirty-flag write can land
  after a freeze completed its `operationsCount` drain. This is a HEAD-identical property of
  the gap between lock admission and `startOperation` (the data branch has the same shape at
  `:2535` → `:2752`), so it is pre-existing, not introduced by the primitive — recorded here
  only because the guarantee wording invites the wrong conclusion that checkpoint (2) closes
  it.

No invariant breaks: the commit never blocks or parks under an operator freeze (the
kind-aware gate throws), the unwind is clean, and the held-write-lock throw window is
microseconds. Hence suggestion, not should-fix.

**Pin proposed (two lines at decomposition).** (a) Evaluate the predicate once more after the
drain completes, before returning `true` (and optionally once immediately after the bit is
acquired): on true, release the bit and return false. This closes cases 2 and 3 entirely and
shrinks case 1 to the blocking-wait residue, keeping checkpoint (2)'s "bit never held past
the throw" contract for freezes arriving up to the admission edge; the freezer-side backstop
still covers the irreducible TOCTOU. (b) Reword guarantee (ii) to "freeze abort within one
poll granularity **while acquisition is in progress**; a freeze arriving at the admission
edge is handled by checkpoints (3)/(4) under the freezer's operation drain."

**Alternative hypotheses checked.** "The drain always runs at least one predicate poll" —
refuted: the per-slot inner `while` is condition-first; with no `READING` slot (or an array
emptied by cleaner action) zero polls occur. "Checkpoint (2)'s contract only ever claimed
abort-during-wait" — the re-amendment's own wording (ii) does not scope it, and the carried
"never durably held past the throw" sentence actively contradicts the miss-window outcome.

**Verdict: suggestion** — add the post-drain predicate check; align the guarantee wording.

---

## CS19 [SHOULD-FIX] — "commit()'s own outermost finally" + "no armed flag" + "probe throw → no clear" are jointly unsatisfiable: the literal outermost placement clears on pre-pin throws and re-mints the CS15 underflow on the probe-rejection path; the wording must pin the nested pin-scoped try/finally

**Surface attacked.** "The **sole clear moves into `commit()`'s own outermost finally**,
paired lexically with the `:2525` pin" together with "**No armed flag** is needed in the
primary design" and the escape-path claim "Probe throw — occurs **before** the `:2525` pin …
so no pin was taken and no clear is needed."

**Premises (code-verified).**

1. `commit()`'s outermost `try` opens at `:2514`. Inside it, **before** the pin at `:2525`,
   sit `frontendTransaction.getDatabaseSession()` (`:2515`), `session.getTxSchemaState()`
   (`:2522`), and — per the settled CS10 hoist — the Q-B3 probe, whose throw is the *common*
   operator-freeze rejection.
2. A `finally` attached to that outermost `try` runs on every exit, including pre-pin throws.
   Without an armed flag (explicitly rejected by the re-amendment), such a finally cannot
   distinguish "pin taken" from "pin not taken" — the throwable carries no marker (the same
   structural argument pass-15 CS15 premise 2 made against the pass-14 wrapper).
3. `clearThreadLocalSchemaSnapshot` on an unpinned session drives `immutableCount` 0 → −1
   (`MetadataDefault:88-93`); from −1 the CS15 premise-3/4 consequences follow verbatim
   (`forceClear` throws at `:95-102` → `forceRebuildTxSchemaSnapshot`
   (`DatabaseSessionEmbedded:3560`) fails on the session's next first schema write;
   `rebuildThreadLocalSchemaSnapshot` fails at count 0 (`:117-125`) on the next
   class-creating schema commit; `getImmutableSchemaSnapshot` churns per read (`:127-134`)) —
   on a pooled session, landing on arbitrary future borrowers.
4. Mechanical detail confirming "outermost" cannot be meant literally: `session` is declared
   *inside* the try (`:2515`), so an outermost finally cannot even reference
   `session.getMetadata()` without restructuring the method — the restructuring is exactly
   where an implementer goes wrong.

**Counterexample (single trace, no concurrency needed).** Implement the re-amendment by its
literal words: add `finally { session.getMetadata().clearThreadLocalSchemaSnapshot(); }` to
the outermost try (hoisting `session` above it to make it compile). Operator freeze is
active. A pooled session runs `commit()` → hoisted probe throws the gate exception **before
the `:2525` pin** → the outermost finally clears → `immutableCount` 0 → −1 → premise-3
poisoning, i.e. **the exact failure class CS15 was reversed to eliminate, now on the exact
path the probe hoist was built for**. The design text simultaneously asserts "no pin was
taken and no clear is needed" — so the text contradicts its own named structure.

**Why should-fix and not blocker.** Two mitigations are already inside the re-amendment:
(a) the mandated test matrix's case (a) — probe rejection asserting `immutableCount` balance
on a recycled, re-borrowed pooled session — deterministically fails against the wrong
placement, so the defect cannot ship through the re-amendment's own verification gate;
(b) "paired lexically with the `:2525` pin" (the phrasing the pass-15 CS15 fix bound (a)
actually used, without "outermost") points a careful implementer at the correct structure:

```java
session.getMetadata().makeThreadLocalSchemaSnapshot();   // :2525 — pin
try {
  // getSortedIndexOperations … branch … commitSchemaCarry / data branch …
} finally {
  session.getMetadata().clearThreadLocalSchemaSnapshot(); // the SOLE clear
}
```

— a **nested** try opened immediately after the pin, covering everything from `:2527` to the
branch's completion, with the pre-pin region (probe, `:2515-2523`) outside it. That structure
satisfies all five escape-path claims exactly as enumerated: probe/pre-pin throws → no clear;
post-pin throws (incl. `:2527-2528`, the third-checkpoint abort, in-apply throws, the
`ensureThatComponentsUnlocked` shape, release-finally throws) → exactly one clear; success →
one clear. (Incidentally it also fixes a HEAD latent leak: today a throw from
`getSortedIndexOperations:2527` / `getAtomicOperation:2528` escapes after the pin with no
`:3142` clear ever reached — pin leaks +1. The re-amendment's move covers it; worth one line
of credit in the design text.)

**Fix bound (wording only, no design change).** Replace "moves into `commit()`'s own
outermost finally" with "moves into a try/finally opened immediately after the `:2525` pin,
whose finally is the last code `commit()` runs on every post-pin path (the pre-pin region —
the hoisted probe and the `:2515-2523` reads — stays outside it)". This is also the
consistency repair the section owes itself: CS15's own indictment of pass-14 was
"contradictory span endpoints", and the re-amendment reintroduced a span-endpoint ambiguity
in the sentence that was supposed to remove it.

**Alternative hypotheses checked.** "'Outermost' could be read as 'the outermost finally of
the pin-scoped region'" — possible, but D1's house rule judges the *natural legal reading*,
and "commit()'s own outermost finally" naturally names the method's outermost try/catch;
two implementers reading pass-15's fix bound vs. this re-amendment would produce different
structures. "The armed-flag rejection implies the nested placement, disambiguating" — it
implies it only to a reader who has already done this analysis; the text should not require
re-deriving the counterexample to be read correctly.

**Verdict: should-fix** — one-sentence wording pin; the mandated test (a) is the reason this
is not a blocker.

---

## Null verdicts on the remaining charter questions (justifications)

**N-16.1 — Phase-1 "queued writer blocks no readers" is TRUE against real code.** Readers
synchronize only via `stampedLock.isWriteLocked()` (`:328-347`, also `sharedTryLock:443` and
`sharedTryLockNanos:483`); a writer waiting inside `tryWriteLock(pollNanos)` has not set the
write bit, so reader admission is unaffected for the entire queued wait. The stamped queue
orders writers against writers only — and mutual writer exclusion is the desired property.

**N-16.2 — Phase 2's drain is the right abort-poll site; no reader/writer stranded, no lost
wakeup on abort-release.** Readers never park against `ScalableRWLock` — they yield-spin on
`isWriteLocked()` (`:345-347`) and observe the release on their next poll; other stamped
writers parked in the `StampedLock` queue are signaled by `unlockWrite` (P2); the freezer
never parks in `freezeOperations` (yield-spin on `operationsCount`), so no freezer wakeup
depends on the aborting writer. The abort-release leaves no residual writer-intent state:
a phase-1 timeout cancels the stamped waiter node (P2); a phase-2 abort holds only the bit,
which `asWriteLock().unlock()` fully releases; `readersStateArrayRef` staying populated is a
shared cache, not intent — HEAD precedent `exclusiveTryLock:551` leaves it identically. The
primitive is reusable as claimed.

**N-16.3 — No phase-1→phase-2 boundary window where NEITHER guarantee holds.** At every
instant one of three regimes applies: bit not held → readers flow freely (guarantee (i)'s
machinery is simply not yet engaged, and nothing is blocked on the armed writer); bit held
with a non-empty drain → abort polled per iteration (guarantee (ii)); acquisition complete →
the commit is admitted and the settled checkpoints (3)/(4) + freezer operation-drain own the
freeze interaction (D3). The residual overclaim in the *wording* of (ii) on the admission
edge is CN25, a suggestion — it is not a "neither guarantee" hole because the four-checkpoint
invariant's operational content (never block/park under an operator freeze) holds throughout.

**N-16.4 — Interrupt semantics are implementable as specified.** Phase 1's
`stampedLock.tryWriteLock(time, unit)` throws `InterruptedException` (declared by
`exclusiveTryLockNanos:586` today); restore-flag + `DatabaseException` is a caller-side
translation with precedent. Two scoped notes, neither a defect: (a) "naming the holder" is
unimplementable — `StampedLock` tracks no owner (`ScalableRWLock:283-289`'s own javadoc);
the disjunction "holder/state" is satisfiable by naming the state, and the pass-15 fix
direction said "naming the state" — carry that wording. (b) Phase 2 is non-interruptible
(pure yield-spin), matching HEAD's `exclusiveLock`; the re-amendment's interrupt clause is
correctly scoped to "the stamped timed acquire". A pre-set interrupt flag now fails a schema
commit that HEAD's non-interruptible `writeLock()` would have run — a deliberate, specified
behavior change (Q-A3 pin (3)), not a defect.

**N-16.5 — No new deadlock edge with the freezer's cut-and-unpark or the caller-held
SchemaShared/IM locks (V-15.5 carries, and is in fact strengthened).** By D2 the baseline is
HEAD's plain `exclusiveLock()` at `:3179`; the primitive is that acquisition plus an extra
exit, so every blocking edge it has, HEAD has. Enumerated cycles:
(1) *Operator freeze vs. drain*: freezer registered before S's bit → abort fires within one
poll; freezer arrives after S's bit → it blocks at its own `stateLock.readLock` (`:5510`)
**before registering anything**, so no freeze-vs-lock cycle can form (ordering stays
readLock→freeze) — it simply waits out S like any reader.
(2) *Transient freeze vs. drain*: a reader parked in `startOperation` on a transient freeze
holds `stateLock.readLock` and stalls S's drain for the transient's duration; the transient's
frozen bodies are stateLock-independent (P4: `doSynch` flush/`flushAllData`; DiskStorage WAL
ops), so they complete, `releaseOperations` (lock-free) unparks the reader, the drain
finishes — bounded, and blocking-equivalent to HEAD (V-15.5's transient case verbatim).
(3) *Caller-held SchemaShared/IM vs. drained readers*: if a `stateLock.readLock` holder could
block on `SchemaShared.lock`/IM held by S, HEAD's plain `exclusiveLock` at `:3179` wedges
identically — the shape is a base-design (settled) property that the primitive neither adds
nor worsens; it strictly improves it by adding the operator-freeze exit.
(4) *Cut-and-unpark itself*: `releaseOperations` acquires no locks; the unparked entrants are
readers whose progress does not depend on the armed writer's predicate.

**N-16.6 — "Exactly two callers" is TRUE; no path loses the clear.** Whole-repo grep:
`applyCommitOperations` is `private` with call sites `:2537` and `:3187` only (all other hits
are javadoc); `commitSchemaCarry`'s sole caller is `commit():2532`; no test or reflective
callers exist. Deleting `:3142` therefore moves ownership without a handoff, exactly as
claimed.

**N-16.7 — Nothing in the extended pin window depends on the clear having already happened.**
The delta window between the old clear (`:3142`, running after promotion and the failure-arm
undo, before the `commitSchemaCarry` finallys) and the new clear (commit()'s finally) is
exactly: `exitCommitWindow:3190` (ThreadLocal depth only), `stateLock` unlock `:3192`,
`releaseExclusiveLockForCommit:3196` (pure unlock — verified NOT the public
`releaseExclusiveLock:637-662`, which carries `forceClearThreadLocalSchemaSnapshot:645` and
would throw at count 1; the commit path is documented and coded to never use it),
`releaseSchemaWriteLock(session, false):3199` (modificationCounter + shared-snapshot null +
advanceVersion — no thread-local pin access), the data branch's read-lock unlock `:2540`,
the debug-log block, and `logAndPrepareForRethrow:7624-7686` (log/`setDbName`/`setInError` —
no metadata access). No code in the window reads `getImmutableSchemaSnapshot`, calls
`forceClear`, or asserts on the count. Promotion (`:3120-3138`) ran *before* the old clear
too, so its pin context is byte-identical; its `session.load` pins/clears a balanced nested
pair (`DatabaseSessionEmbedded:2144/:2303`, count 1→2→1). After-commit listeners and the
session-level tx teardown run after `commit()` returns — the clear has happened by then,
matching HEAD's post-return state.

**N-16.8 — Nested pins and re-entrancy behave.** `makeThreadLocalSchemaSnapshot` at
count > 0 is increment-only (`:78-85`), so an outer pin held at commit entry yields a
balanced +1/−1 delta regardless of clear placement; the in-window `rebuild:2888` requires the
pin held at `:2525`, which the move preserves; `commit()` is not re-entrant (promotion
explicitly avoids nested transactions, per the `:3107-3118` comment), and even a hypothetical
re-entry would balance frame-by-frame since each frame owns one pin and one finally-clear.
The pre-existing unpaired pin at `EntityImpl:4174` leaks identically under old and new
placement (it leaks +1 above whatever base count exists) — pre-existing at HEAD, already on
record via pass-15 CS15's nested-pin note, not worsened.

**N-16.9 — The five-path matrix covers the escape-path equivalence classes.** All post-pin
escapes funnel through the single clear: post-pin pre-branch throws (`:2527-2528`),
schema-carry lock-acquisition throws (`:3174/:3176`), the third-checkpoint abort, in-apply
throws (version conflict, validation, index-commit), the `ensureThatComponentsUnlocked`
throw shape, release-finally throws (`:3190-3199`), and success — one clear each under the
correct structure; the pre-pin class takes zero clears. Matrix cases (a)–(e) exercise both
classes and the two lock profiles (none/read/write); case (a) additionally detects the CS19
mis-scope, and cases (c)/(d) are precisely the paths that caught CS15. The uncovered
variants ((`:2527-2528` throws, release-finally throws) are members of already-tested
equivalence classes, not distinct behaviors — no matrix extension required.

## Hypothesis log

| # | Hypothesis | Checked against | Outcome |
|---|---|---|---|
| H1 | Phase-1 queued writer blocks readers (claim false) | `sharedLock:320-348`, `tryWriteLock` semantics | Refuted (N-16.1) |
| H2 | Abort-release strands a reader/writer or loses a wakeup | Reader spin-poll `:345-347`; StampedLock unlock signaling; `OperationsFreezer` spin | Refuted (N-16.2) |
| H3 | Abort leaves residual writer-intent state (primitive not reusable) | Timeout node cancel; `exclusiveTryLock:551` cache precedent | Refuted (N-16.2) |
| H4 | Phase-1→phase-2 boundary window where the abort guarantee silently lapses | Poll-point enumeration; empty-drain path `:398-409`; `makeStorageDirty:2752`; checkpoint (3) held-lock profile | **Confirmed (bounded, backstopped) → CN25 [suggestion]** |
| H5 | Interrupt clause unimplementable / wrong | `exclusiveTryLockNanos:586-589`; `isWriteLocked` javadoc `:283-289` (no owner) | Refuted as defect (N-16.4); carry "naming the state" wording |
| H6 | Deadlock with cut-and-unpark | `releaseOperations` (lock-free), `freezeOperations` (spin, no park) | Refuted (N-16.5) |
| H7 | Three-way wedge: transient freeze + parked reader + drain | `synch:5319`, `freeze:5510`, `doSynch:5347-5385`, `DiskStorage:357/:1249` bodies | Refuted as new edge — HEAD-equivalent blocking, transient bodies stateLock-independent (N-16.5) |
| H8 | Caller-held SchemaShared/IM deadlock vs. drained readers | D2 baseline `:3179` plain `exclusiveLock` | Refuted as new edge (N-16.5) |
| H9 | Sustained readers re-arm CN19 against the primitive | Single bit-acquisition, no `:611`-style inter-attempt release | Refuted (N-16.1/N-16.3) |
| H10 | Hidden `applyCommitOperations` caller loses the clear | Whole-repo grep; `private` modifier | Refuted (N-16.6) |
| H11 | Extended pin window breaks a count-dependent callee (esp. `releaseExclusiveLockForCommit` vs. the `forceClear`-carrying public release) | `IndexManagerEmbedded:92-104` vs `:637-662`; `SchemaShared:765-791`; `:4780-4813`; `:7624-7686` | Refuted (N-16.7) — the commit path uses the pure release |
| H12 | The moved clear can itself throw and mask the outcome | `MetadataDefault:88-93` | Refuted — throw-free |
| H13 | Nested pins / outer-pin-at-entry / re-entrant commit misbehave | `:78-85`, `:2888`, `:2144/:2303`, promotion comment | Refuted (N-16.8) |
| H14 | Literal "outermost finally" clears on pre-pin throws → CS15 class | `commit()` structure `:2514-2525`; hoisted-probe placement; `MetadataDefault` semantics | **Confirmed → CS19 [should-fix]**, mitigated by test (a) |
| H15 | Five-path matrix misses a behaviorally distinct escape path | Full escape-path enumeration of `commit()` | Refuted (N-16.9); noted the move incidentally fixes the HEAD `:2527-2528` pin leak |
| H16 | `EntityImpl:4174` unpaired pin interacts with the moved clear | `:4174` fallback; both placements | Refuted — identical pre-existing leak either way (N-16.8) |
| H17 | Line-number citations in the re-amendment are wrong enough to mislead | `:3179` exact; `:2537`/`:3187`/`:2535`/`:2525` exact; `:320-357` overshoots `sharedLock` by 9 lines into `sharedUnlock`'s javadoc; `:3196-3199` names the two metadata-lock releases | Refuted as defect — cosmetic only |

## Compact findings block

| ID | Severity | Location | Summary | Counterexample gist |
|---|---|---|---|---|
| CN25 | suggestion | Track-7 drafts §CN19 re-amendment (guarantee (ii) + poll placement) vs `ScalableRWLock:398-409`, `AbstractStorage:2752/:2766` | Abort predicate is never evaluated on the acquisition-success edge (empty drain / final blocking wait); "freeze abort within one poll granularity" and "bit never durably held past the throw" overstate — the miss-window freeze is handled at checkpoint (3)/(4) with `stateLock.writeLock` HELD; add one post-drain predicate check + reword | Freeze arms 1 µs before an empty-drain acquisition → `exclusiveLockWithAbort` returns true without one predicate poll → S runs `makeStorageDirty`, throws inside `startOperation` holding write+SchemaShared+IM locks — clean unwind, but not the advertised checkpoint-(2) profile |
| CS19 | should-fix | Track-7 drafts §CS15+CN21 re-amendment ("commit()'s own outermost finally") vs `AbstractStorage:2514-2525` | "Outermost finally" + "no armed flag" + "probe throw → no clear" are jointly unsatisfiable: the literal outermost placement clears on pre-pin throws, re-minting the CS15 underflow on the probe path; pin the wording to a nested try/finally opened immediately after the `:2525` pin | Implement literally: probe rejection (operator freeze) throws before the pin → outermost finally clears → `immutableCount` 0→−1 → pooled-session poisoning (forceClear/rebuild throws, per-read snapshot churn); mandated test (a) catches it — hence should-fix, not blocker |

Null verdict on everything else in scope: N-16.1–N-16.9 above — the abort-predicate primitive
is implementable exactly as sketched against the real `ScalableRWLock`, adds no deadlock edge
versus the HEAD baseline it replaces, and strands no waiter on abort; the single-owner clear
has exactly the two claimed callers, a clean extended-window dependency profile, balanced
nested-pin behavior, and an adequate five-path matrix.
