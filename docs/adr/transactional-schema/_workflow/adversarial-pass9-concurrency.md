# YTDB-382 — Adversarial pass 9: concurrency (2026-06-11)

Ninth adversarial pass, single lens: races, lock ordering, memory visibility,
atomicity seams, thread-binding, deadlock, check-then-act compounds. Surface
attacked: the pass-8 settlement text only — the decision-log changes in
`git diff 589116eee3..f1c0c4928d` (D7's rewritten abnormal-termination bullet,
D7's composed F86+F87 freezer bullet, D3's F88 allocator-seed pin, D20's F90
and F91 rewrites, and the F83–F91 resolution records in §2a). All claims
verified against the live tree; **mcp-steroid was reachable and used**:
`ReferencesSearch` inventories were run for `freezeWriteOperations`,
`resetTsMin`, `addIndexEngine`, and `loadExternalIndexEngine`, and every other
anchor was confirmed by direct file reads. Reference-accuracy claims below are
marked PSI-verified or tree-read.

Verdict: 0 BLOCKER, 1 MAJOR, 1 MINOR. The composed freezer mechanism survived
every attack run against it — its safety rests on an unstated structural
property (transient-freeze engagement windows are enclosed in `stateLock.read`
windows) that the dry list records for pass 10. The MAJOR is in the teardown
rewrite: the settlement recast the F79 owner token as the mechanism that
rejects foreign-thread releases, but the token discriminates acquisitions,
not threads, and the one real foreign initiator the text names presents the
current token and wins the CAS.

---

## C27: The owner token cannot reject the foreign-thread release it is claimed to be load-bearing against — a Gremlin `afterTimeout` rollback presents the current token, the CAS succeeds, and the D7 mutex releases mid-transaction [MAJOR]

D7's rewritten abnormal-termination bullet promises owner-thread-only teardown
and names the F79 token as its enforcement for the mutex:

> "cross-thread teardown attempts are rejected or no-op, extending the
> thread-id-gate semantics that ship today (`close()` skips `resetTsMin` for
> foreign threads, `FrontendTransactionImpl:954`)" … "The token makes any
> stale, duplicate, or foreign release a detected, logged no-op — cheap (one
> CAS per schema-tx release; data txs never touch this lock) and load-bearing
> today against the stray Gremlin-hook initiators recorded in YTDB-1113."

The token cannot deliver that. The F79 reference sketch (decision-log
`:2382`–`:2409`) defines `record OwnerToken(DatabaseSessionEmbedded session,
long epoch)` with no thread field, stores the token "in the session's
schema-tx state", and implements release as
`owner.compareAndSet(token, null)`. Identity
CAS rejects *stale* tokens (a reaped-then-woken zombie's release, the C19
threat it was designed for). A foreign **thread** running the same session's
rollback reads the same session state, presents the *current* token, and the
CAS succeeds. The shipped gate the bullet claims to extend compares thread ids
(`FrontendTransactionImpl:954`, tree-read); the token compares object
identity. The extension drops the one discriminator the new invariant needs.
The F38 same-thread assertion "folds into this bookkeeping", but the
bookkeeping has no thread comparison to fold into, and an `assert` evaluates
nothing in production — the exact gap C19 closed for the permit count.

Interleaving (thread names per the YTDB-1113 inventory):

1. `T-worker` (Gremlin executor thread): schema tx active; D7 mutex acquired
   at first mutation; token K stored in the session's schema-tx state. The
   traversal evaluation is slow — or the commit is, since D12 builds indexes
   over populated classes inside the commit window.
2. `T-timer` (GremlinExecutor's scheduled executor): `evaluationTimeout`
   fires; the `afterTimeout` hook calls `tx.rollback()` on the shared
   `graphTraversalSource.tx()`
   (`server/src/main/java/com/jetbrains/youtrackdb/internal/server/plugin/gremlin/YTDBGremlinSession.java:219`–`:226`;
   the sessionless arm is `YTDBAbstractOpProcessor.java:614`–`:619`;
   tree-read; the reach to the shared non-thread-local `YTDBTransaction` is
   the settlement's own PSI sweep, recorded in F84's resolution). The rollback
   runs `rollbackInternal` on the foreign thread and reaches the outermost
   `finally` — D7's release point — with token K.
3. `T-timer`: `release(K)` → `owner.compareAndSet(K, null)` **succeeds** (K is
   the current acquisition; nothing reaped it — v1 has no reaper) →
   `permits.release()`. The mutex is free while `T-worker`'s tx is live. The
   "detected, logged no-op" branch never executes.
4. `T-user2`: a second session's schema tx acquires the mutex, seeds its
   tx-local schema from the committed schema, and proceeds.
5. `T-worker`: if it was mid-body, its next mutation throws on the rolled-back
   status and its own unwind fires the stale-release warn — after the window
   in which D5 was already violated. If it was mid-commit (past
   `checkTransactionValid`, the torn-commit timing F85's resolution maps to
   YTDB-1113), it continues through promotion and publishes into the shared
   `SchemaShared` concurrently with `T-user2`'s live schema tx.

Why the unsafe direction is reachable: both `afterTimeout` arms run on the
scheduler thread by GremlinExecutor contract; the settlement itself certifies
the call sites as real and reaching the shared tx ("ten `rollback()` call
sites in `server/src/main`, two of them scheduler-thread `afterTimeout`
arms"). An evaluation timeout overlapping a schema-writing traversal is
routine operations, and the design widens the overlap window by moving index
population into the commit. The cross-thread `close()` arm (pool teardown
closing a session from another thread) reaches the same release path and
widens the trigger surface beyond Gremlin.

Everything the design layers on D7 mutual exclusion silently re-premises on
the mutex never releasing mid-tx: F80's id uniqueness ("the D7 mutex
serializes only schema commits"), F53's publication happens-before ("the next
commit's seed observes it via the mutex's happens-before"), D8's promotion
serialization. The settlement's own F84-dissolution text shows the asymmetry:
the tsMin pin earned its owner-only property from a thread-id comparison
(`:954`); the mutex got an acquisition-identity comparison. Only the former
discriminates threads.

Severity: MAJOR — a named load-bearing mechanism does not exist as specified.
The sentence "load-bearing today against the stray Gremlin-hook initiators" is
contradicted by the design's own referenced sketch, and the schedule above is
reachable on today's server tree. Not BLOCKER only because the trigger rides
the YTDB-1113 today-path (slated for removal) and the immediate state tear of
that path is already recorded there; the new, design-owned defect is the false
rejection claim and the resulting loss of D5 mutual exclusion. Fix is one
sentence: the token carries the acquiring thread and `release()` hard-checks
`Thread.currentThread()` against it before the CAS (foreign thread → the
logged no-op the invariant promises), or equivalently the token lives in the
owner's thread-confined state instead of session-reachable state.

## C28: "Data commits keep today's uniform park everywhere" misstates the shipped gate — today a data commit throws against a throw-mode freeze, and implementing the stated park turns `freeze(db, true)` into a write hang [MINOR]

D7's rewritten freezer bullet closes with:

> "Data commits keep today's uniform park everywhere."

and F87's §2a resolution repeats "data commits keep the uniform park". The
shipped gate is not a uniform park. `OperationsFreezer.startOperation` runs
`throwFreezeExceptionIfNeeded()` before parking (`OperationsFreezer:35`–`:48`,
throw at `:40` via `:114`–`:118`), so a data commit arriving during a
throw-mode freeze throws the registered
`ModificationOperationProhibitedException` — that supplier is exactly what
`freeze(db, true)` registers (`AbstractStorage:3901`–`:3903`, tree-read).
Park-mode freezes park; throw-mode freezes reject loudly. That split is the
operator-facing contract of the throw-mode freeze: writes fail fast during a
prohibited-modification window instead of queueing.

The pass-7 fold said "Data commits keep today's behavior", which was accurate.
The settlement rewrite replaced it with a specific behavioral claim that the
tree contradicts. An implementer wiring the new kind-aware gate who "keeps"
the stated uniform park for data commits would delete the throw arm and
convert `freeze(db, true)`'s loud rejection into an indefinite hang until
`release(db)` — a silent contract change to a shipped public API, invisible to
the acceptance pair (which exercises only the schema-commit arms).

Severity: MINOR — under-specification an implementer could plausibly fill in
wrong, with a user-visible behavior change as the cost. One-sentence fix:
restore "data commits keep today's gate semantics (park for park-mode
freezes, throw for throw-mode freezes)" in both the D7 bullet and the F87
resolution record.

---

## Attacks run that produced no new finding

- **In-window transient freeze at the backstop gate (park holding all four
  locks, and the deadlock variant through `doSynch`).** Fails on a structural
  property the design never states: every transient freeze's engage→release
  window is enclosed in a `stateLock.read` window held by the same thread —
  `doSynch`'s freeze (`AbstractStorage:3749`→`:3781`) runs under `synch()`'s
  read lock (`:3719`–`:3729`), and both backup freezes
  (`DiskStorage:1248`→`:1262`, `:356`→`:363`) run under `backup()`'s read lock
  (`:925`–`:995`). An engaged transient freeze therefore cannot coexist with
  the schema commit's held `stateLock.write`; the only freeze that can be live
  inside the write-lock window is an operator freeze (engaged under `:3889`'s
  read lock but persisting after `freeze()` returns), for which the backstop's
  throw is the correct loud answer. PSI-verified premise: `ReferencesSearch`
  on `freezeWriteOperations` returns exactly the settlement's five sites
  (`doSynch:3749`, `freeze:3901`, `freeze:3905`, `copyWALToBackup:356`,
  `storeBackupDataToStream:1248`) and `OperationsFreezer.freezeOperations` has
  the single `AtomicOperationsManager:248` delegate caller. Worth a one-line
  plan pin: new freeze call sites must keep the read-lock enclosure, or the
  backstop's transient arm becomes reachable with all four locks held.
- **Backstop kind-ambiguity ("it throws" without restating the operator-only
  condition).** Harmless in every wiring because of the enclosure property
  above: only operator freezes are reachable in-window, so a kind-blind
  backstop throw can only ever fire against an operator freeze, and the
  kind-aware transient-park arm is dead code there.
- **No-timeout slip-through.** An operator freeze that engages between the
  entry probe and a first-attempt `tryLock` success is never re-probed (pin 3
  re-probes only on timeout) — caught by the backstop before the depth
  increment, with the clean unwind the wiring pin mandates. The prose assigns
  this case to pin 4's "engaging after the write lock is held" imprecisely
  (engagement after the write lock is impossible — engagement needs
  `stateLock.read`), but the mechanism composes.
- **Freeze-vs-commit deadlock.** `freeze(db)` blocks at `stateLock.read`
  (`:3889`) behind the held write lock until the commit completes;
  `release(db)` takes no locks (pass-7 ground, re-checked against the
  rewrite). No cycle through the fifth synchronization object.
- **Bounded try-acquire reader stalls and writer starvation.** Each timed
  attempt parks readers for at most one timeout (writer preference while
  queued), and the cancel-retry shape can delay the DDL behind overlapping
  reader chains; the consequence is DDL latency with reads flowing between
  attempts — no worse than the blocking acquire it replaces, and the "reads
  keep flowing" claim holds at timeout granularity.
- **Entry-probe transient park while holding the D7 mutex (deadlock hunt).**
  No transient freeze holder acquires the D7 mutex or any metadata lock inside
  its freeze window (`doSynch` flushes engines and pages; the backup freezes
  cut WAL segments); no cycle.
- **"Throw with zero locks held" vs the held D7 mutex.** The mutex is held at
  commit entry (engaged at first mutation), but it blocks only schema writers;
  the probe throw unwinds into rollback, which releases it. Wording, not
  mechanism (and the release path is C27's subject, not this one).
- **F88 seed vs deferred publication TOCTOU.** A registrar reading
  `indexEngines.size()` between the commit's seed read and its registry
  publication would mint a duplicate id — foreclosed by D19's hold-through
  rule: the schema commit "holds all three through promotion, overlay
  publication, and the trailing `forceSnapshot`" (D19, F52/F64 bullet), so
  `stateLock.write` spans seed-to-publication and registrars cannot
  interleave.
- **F88 registrar inventory.** Re-verified by PSI: `addIndexEngine` ←
  `IndexAbstract#create:196` and `#rebuild:305`; `loadExternalIndexEngine` ←
  `IndexAbstract#load:240` only. Matches the settlement's claimed-complete
  set.
- **Collection-id analogue of F88.** Non-commit collection registrars also run
  under `stateLock.write`; the same exclusion covers them, and the null-slot
  recycling behavior is shipped, not settlement-introduced.
- **`recreateIndexes` background thread vs the lock order.** Index lock →
  `stateLock.write` conforms to the F52/F64 suborder (no reverse edge); its
  engine-id reads are serialized by the same write-lock window the seed pin
  uses.
- **Owner-side double release of the tsMin pin.** Holds as the F84 resolution
  claims: `close()`'s `atomicOperation` null-guard (read `:951`, nulled
  `:964`) prevents the second entry, and the hard underflow throw
  (`AbstractStorage:4682`–`:4686`, behind the assert at `:4681`) backstops it.
  PSI: `resetTsMin` has exactly one gated production caller
  (`FrontendTransactionImpl:955`; the `AbstractStorage:6418` hit is a Javadoc
  `{@link}`, not a call).
- **Shipped memory-mode claims.** Verified against the tree: volatile `tsMin`
  (`TsMinHolder:81`), opaque end reset (`TsMinHolder:121`,
  `AbstractStorage:4696`), owner-only plain `activeTxCount` (`TsMinHolder:84`)
  — D7's "stand as shipped" list matches, and the cleanup thread reads only
  the volatile field.
- **Stranded schema tx wedging all DDL until restart.** Deliberate, documented
  scope decision (reclamation → YTDB-1114); not re-flagged. The YTDB-550
  monitor sees every mutex holder because begin pins `tsMin`
  (`AbstractStorage:4653`–`:4654`) before any mutation can engage the mutex,
  so no unmonitored-strand window exists.
- **Index-only commits and the entry probe.** D19's unified schema-carrying
  signal (schema OR index changes) is the same branch that takes the write
  lock, so the probe inherits index-only coverage by construction.
- **D20 F90/F91 concurrency surface.** None found: export and import are
  single-threaded operator procedures; the import's `synch()`
  (`DatabaseImport:252`) is a transient quiesce under the taxonomy and
  composes with the entry probe's bounded park; the fixed `.tmp`-name
  collision between two concurrent exports of one database is shipped exporter
  behavior the settlement only describes.
- **Wiring-pin rationale drift.** D7's parenthetical says the unguarded
  ordering "corrupts the freezer count" while F87's entry says it "masks" the
  gate throw (an `endOperation()` at depth 0 throws `IllegalStateException`,
  `OperationsFreezer:61`–`:62`, masking the original error; the count itself
  stays balanced). The pinned action — throw strictly before the depth
  increment, frontend-commit path only — is identical in both records and
  correct, so this is wording drift, not a mechanism defect.
