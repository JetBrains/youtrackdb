# YTDB-382 — Adversarial pass 8: durability and crash safety (2026-06-11)

Verdict: 4 new findings (0 BLOCKER, 3 MAJOR, 1 minor). This pass targeted the
pass-7 fold text accepted on 2026-06-11: F76's reap scoping and its
storage-error/restart envelope, F77's tx-aware population split, F78's
freezer-gate reject-loudly semantics, F80's commit-local structural-id
allocator, and F81/F82's legacy-dump and manifest write discipline. The F77
and F80 folds survived every crash-boundary attack (see the dry list). The
three MAJORs all share the pass-7 common root, recurring once more: a fold
names a mechanism the live machinery does not provide — the reap-scope test
has no synchronized carrier and the rollback path it would reuse explicitly
permits tearing down a mid-commit transaction, the freezer has no
per-operation throwing variant and both available approximations break a
design premise, and the legacy exporter's own failure path manufactures
exactly the well-formed dumps the F81 completeness check assumes cannot
exist.

Method: decision log read end to end, all six prior reports read including
every failed-attack list; every claim verified against the live tree
(`OperationsFreezer`, `AtomicOperationsManager`, `AtomicOperationsTable`,
`AtomicOperationBinaryTracking`, `FrontendTransactionImpl`, `AbstractStorage`,
`DiskStorage`, `DatabaseExport`, `DatabaseImport`, `FileUtils`). mcp-steroid
was not reachable in this session, so PSI find-usages was unavailable: every
reference-accuracy claim below ("the only callers", "no variant exists") is
grep-based and may miss polymorphic call sites or reflective access; claims
marked PSI-verified were verified in prior passes and are cited as such.
Each attack asks "the process dies, stalls, or the routine failure fires at
instruction boundary X; what does the durable state, recovery, or the import
see?" against the real code.

---

## U17: The reap's between-operations scope test has no synchronized carrier, and `rollbackInternal` explicitly rolls back a COMMITTING tx — a reap racing a zombie owner's commit entry can commit a durable unit built from concurrently-torn tx state [MAJOR]

**The claim under attack.** D7's F76 fold scopes the reap to
between-operations stranding and routes mid-commit-window strands to the
storage-error/restart path, with the F79 caveat that "the reap path tolerates
the owner racing it." The scope test needs a carrier the reaper can read and
a guarantee that the owner cannot enter the commit window after the test
passes. Neither exists, and the tolerated race is unsound on the durability
path.

**No carrier.** The natural carrier is the tx status:
`FrontendTransactionImpl.status` is a plain non-volatile field
(`FrontendTransactionImpl:81`), written to `COMMITTING` by the owner at
commit entry (`doCommit:668`) with no synchronization a reaper thread
participates in. A reaper reading it can see a stale `BEGUN` for an owner
already inside the storage commit, and the owner can see a stale `BEGUN`
after the reaper wrote `ROLLBACKING` — `doCommit`'s own guard (`:637`–`:644`)
runs once at entry and provides no protection after it passes. The
U15-proposed "commit-window flag next to `storageTxThreadId`" never made it
into the D7 text, and a plain flag would have the same two failure modes.

**The rollback path the reap reuses tears down a live commit.** D7's reap
runs "the session's full tx rollback", which is `rollbackInternal`, the
cross-thread-permitted path (`assertOnOwningThread` exempts it,
`FrontendTransactionImpl:126`–`:138`). Its status machine has an explicit
`BEGUN, COMMITTING ->` arm that proceeds with the rollback (`:368`–`:386`);
the arm exists for the owner's own commit-failure path (`doCommit:690`,
same thread), but nothing distinguishes that caller from a reaper. The
rollback then runs `invalidateChangesInCacheDuringRollback`, which calls
`unsetDirty()` + `unload()` on every record in `recordOperations`
(`:418`–`:428`), followed by `clear()` (`recordOperations.clear()` via
`clearUnfinishedChanges:998`–`:1002`) and `close()`
(`atomicOperation.deactivate()` + `atomicOperation = null`, `:948`–`:970`).

**Interleaving (concrete).** Schema tx on worker thread T; T stalls
mid-statement (GC pause, long scan); the channel drops; the reaper reads
`status == BEGUN`, declares between-operations stranding, and starts
`rollbackInternal`. T wakes, finishes the statement, and its executor
proceeds to commit: the stale-read guard at `:637` passes, `status =
COMMITTING` (`:668`, clobbering the reaper's `ROLLBACKING`), and
`internalCommit` enters `AbstractStorage.commit`: position allocation,
`commitEntry` serialization (`:2362`/`:5351`), `commitIndexes` (`:2375`),
`commitChanges`. Meanwhile the reaper's rollback is concurrently unloading
the same record objects `commitEntry` serializes and clearing the same
`HashMap`s the commit iterates. Outcomes range over: torn serialized bytes
inside the unit; a `HashMap` iterated under concurrent `clear()` silently
yielding a subset, so the unit commits an arbitrary partial record set;
`ConcurrentModificationException` mid-commit (loud, lands on the rollback
path); and the reaper's `deactivate()` (`AtomicOperationBinaryTracking:1203`,
a plain boolean `:121` with no happens-before) either invisible to the owner
or tripping `checkIfActive` (`:1605`–`:1609`) at an arbitrary point —
including after the end record is durable, the U15(b) poisoned-storage
shape. The first two outcomes are durable and silent: a committed WAL unit
whose contents were assembled from concurrently-mutated state, replayed
faithfully at every future recovery.

**Why this is the fold's defect.** F79's accepted caveat ("two threads touch
one session's tx state unsynchronized... the reap path tolerates the owner
racing it") accepts exactly this access pattern; the tolerance is sound for
the mutex token (a CAS) and unsound for the tx state, because the rollback
side mutates what the commit side serializes into the durable unit. The
scoped-out mid-commit-window case is honest only if the scope test is
reliable; with a plain-field carrier it is not.

**Affected entries.** D7 (reap bullet), F76, F79, F71, D12, D5.

**Resolution direction.** Make the scope test an atomic claim on one
properly-synchronized status carrier: the owner's commit entry CASes
`BEGUN → COMMITTING`; the reaper CASes `BEGUN → REAPING` and refuses to
proceed on anything else (in particular it never enters `rollbackInternal`'s
COMMITTING-permissive arm cross-thread); whoever loses the CAS backs off.
The COMMITTING state already spans the whole window the scope must cover
(set at `doCommit:668`, cleared to COMPLETED only at `:699`, after
`internalCommit` returns, which under D8 includes promotion, overlay
publication, and the trailing `forceSnapshot`), so one carrier closes both
the entry edge and the post-`endTxCommit` publication edge. D7's tolerance
sentence narrows to: the reap tolerates a zombie's stale mutex release
(F79's token no-op); it never shares tx state with a live commit.

---

## U18: "The freezer's throwing variant" does not exist as a per-operation routing choice, and both implementable approximations break a design premise — transient internal freezes abort schema commits (D5 violation), or the park-mode backup freeze re-creates the C18 outage [MAJOR]

**The claim under attack.** D7's F78 fold: "the schema commit routes through
the freezer's throwing variant, so DDL against an engaged freeze fails with a
loud storage-frozen error... Data commits keep today's behavior." That
sentence reads as a routing choice between two existing gate behaviors. The
machinery has no such choice: whether `OperationsFreezer.startOperation`
parks or throws is decided by the freeze side, uniformly for every operation.
The gate throws only when the freeze registered a `FreezeParameters` supplier
(`throwFreezeExceptionIfNeeded`, `OperationsFreezer:114`–`:118`), i.e. when
the freeze was engaged via `freezeOperations(throwException != null)`
(`:72`–`:79`); otherwise every operation parks (`:46`–`:47`). The
filesystem-snapshot freeze C18 names is park-mode: `freeze(db, false)` passes
`null` (`AbstractStorage:3905`).

**Both available keys are wrong.** A per-commit-type gate is net-new
machinery, and the two signals it could key on each break a premise:

- **Key on `freezeRequests > 0` (any engaged freeze) → D5 violation.** The
  freezer is also engaged by routine, transient, internal quiesces that hold
  it for milliseconds-to-seconds: `doSynch` (`AbstractStorage:3749`), reached
  from every storage `synch()` (whose storage-level callers include the D20
  import itself, `DatabaseImport:252`, and the automatic index-rebuild task,
  `RecreateIndexesTask:41`), plus the incremental-backup WAL copy
  (`DiskStorage:356`) and the backup segment cut (`DiskStorage:1248`), all
  park-mode (grep-based caller set; may miss polymorphic sites). A schema
  commit that throws whenever any of these is engaged is aborted by
  contention against a healthy, transient internal operation — the exact
  D5 violation ("schema-tx rollback due to contention is not acceptable")
  that F71 closed for the mutex timeout, reopened at the fifth
  synchronization object. The fold's retry guidance ("the operator or
  migration script retries after `release(db)`") does not even apply: these
  freezes have no operator and no `release(db)`.
- **Key on registered `FreezeParameters` (throw-mode freezes only) → C18
  returns.** The backup freeze (`freeze(db, false)`) registers none, so the
  schema commit parks on the gate holding all four locks — the total read
  outage F78 was accepted to prevent, restored for the primary freeze mode
  the finding was about.

**Wiring pin (second half).** The natural implementation point is
`startToApplyOperations` (`AtomicOperationsManager:107`), which is shared by
the storage-internal wrapper paths. On the frontend-commit path a gate throw
unwinds clean: it fires before the table registration (`:118`–`:125`), the
freezer's own counter is balanced before the throw (decrement precedes
`throwFreezeExceptionIfNeeded`, `OperationsFreezer:38`–`:40`), and
`startTxCommit` (`AbstractStorage:2293`) sits outside the inner try whose
finally drives `rollback(error, atomicOperation)` (`:2396`–`:2398`), so no
`endAtomicOperation` call runs against the never-registered operation —
nothing reaches the WAL or disk, and a concurrent frozen snapshot stays
consistent. The wrapper paths are a different story:
`calculateInsideAtomicOperation`'s finally always calls `endAtomicOperation`
(`AtomicOperationsManager:152`–`:154`), which reads
`operation.getCommitTs()`, a throw on the `-1` sentinel
(`AtomicOperationBinaryTracking:203`–`:211`); then the outer finally
calls `writeOperationsFreezer.endOperation()` (`:441`–`:443`), which throws
"Invalid operation depth" for a thread whose gate entry never incremented
the depth (`OperationsFreezer:59`–`:65`). The original storage-frozen error
is masked twice. This cascade is a pre-existing dormant defect (today it
fires only under `freeze(db, true)`, whose production use is marginal); the
F78 design makes a throwing gate routine, so the new gate must be wired at
the frontend-commit call path specifically and must not inherit the wrapper
unwind.

**Affected entries.** D7 (freezer bullet), F78, D19, D5, D12, D20.

**Resolution direction.** Name the mechanism as net-new in D7: the freezer
gains a freeze-kind taxonomy (operator/long-lived freezes vs transient
internal quiesces — a second counter or a kind flag in the freeze
registration), and the schema-commit gate throws only against operator
freezes while parking (bounded, with the F61-style diagnostic) for transient
ones; data commits keep the uniform park behavior. Pin the gate's placement
to the frontend-commit path with the clean-unwind property above, and add a
regression test: schema commit vs engaged `freeze(db, false)` → loud
storage-frozen error, locks released, reads flowing; schema commit vs
in-flight `doSynch`/backup segment freeze → brief park, commit succeeds.

---

## U19: The legacy exporter's failure path finalizes the JSON document and renames the temp file into place — F81's JSON-close check passes exactly the failed-export dumps it was invented to reject, and the import never verifies section presence [MAJOR]

**The claim under attack.** D20's F81 fold, option (c): "the dump is a
single JSON document, so it must parse to a cleanly closed document, which
detects truncation without a manifest." The premise, that an incomplete
export yields an unclosed document, is false for the legacy exporter's real
failure population, in both directions.

**Direction one: the failures the check would catch never reach the final
name.** The legacy exporter writes to `<name>.json.gz.tmp` and promotes it
with an atomic rename only in `close()` (`DatabaseExport:87`, `:291`). A
kill or power loss mid-export leaves the `.tmp` file and no dump at the
final name; a truncated `.gz` additionally fails at decompression (the
import wraps the file in `GZIPInputStream`, `DatabaseImport:138`–`:143`, and
a truncated member throws at read). So the truncation class the JSON-close
check targets is already absorbed by the rename and the gzip envelope.

**Direction two: the failures that do reach the final name are cleanly
closed by construction.** `exportDatabase` runs `close()` in a `finally`
(`:157`–`:158`) — on the failure path too. `close()` writes
`jsonGenerator.writeEndObject()` (`:277`) and calls `jsonGenerator.close()`,
and Jackson's `AUTO_CLOSE_JSON_CONTENT` (enabled by default; this repo's
`JsonFactory` is created with no feature changes, `:97`–`:98`) closes every
still-open array and object. It then renames the temp file into place
(`:291`). Concretely: the realistic mid-export failure, a source-side
`YTIOException` re-thrown from the record loop (`:212`–`:220`) with the
generator inside a record object, produces a dump whose half-written record
object is closed, whose `records` array and root object are auto-closed,
whose gzip trailer is valid, and which sits at the final dump name while the
export command exits with an error. Failures inside the `info`,
`collections`, or `schema` section objects behave the same and additionally
omit every later section. Only an array-context failure point (between
records) makes `close()`'s `writeEndObject` throw and skip the rename.

**The import accepts the result silently.** The import's main loop reads
top-level fields until `}` or end of stream (`DatabaseImport:226`–`:242`)
and never checks which sections were present: a dump missing the whole
`records`, `brokenRids`, or `indexes` section imports as whatever is there
and reports success. Combined with direction two, the end-to-end replay is:
export fails at 60% of records → finally-close finalizes and renames → the
operator (or a script that checks file existence rather than exit status)
imports with the F81 ack flag, which is mandatory on every D20 migration
import and therefore baked into the documented command — its
deliberate-choice signal is void on the primary path → JSON-close passes,
gzip CRC passes, import succeeds → silently incomplete database, verbatim
the F63 failure mode the fold marked closed.

**Affected entries.** D20 (F81 bullet), F81, F63, F75, D14.

**Resolution direction.** Replace the cleanly-closed criterion with a
section-presence criterion the import can enforce from its own tag switch: a
complete v12+ legacy dump always contains `info`, `collections` (or
`clusters`), `schema`, `records`, `brokenRids`, and `indexes` (the last
section written, `DatabaseExport:393`); the import hard-fails a legacy dump
in which any expected section is absent. That catches every
prematurely-finalized dump except one truncated inside the final `indexes`
section, which the ack-flag residue covers honestly. Add to the documented
migration procedure that a dump file can exist at the final name after a
failed export, so the operator must verify the export's success status
before importing — and state that the ack flag is a procedural
acknowledgment, not a detection mechanism, since every D20 import carries
it.

---

## U20: F82's stream-variant tail and directory-fsync pins under-specify scope and platform degradation — the tail must cover the whole stream, and the rename discipline's failure modes should be stated as fail-closed [minor]

Three discipline details, all one-sentence pins in D20's F82 bullet:

1. **Stream-variant trailer scope.** The fsync ordering ("dump durable
   before manifest visible") rides the rename barrier and does not map onto
   the stream variant, where dump and manifest share one file. Page-cache
   writeback is unordered, so after a power loss the file can hold a durable
   self-validating tail over a zero-filled middle; a length-or-checksum
   trailer scoped to the manifest section vouches for nothing but itself.
   Pin: the stream variant fsyncs the file before the export reports
   success, and the trailer covers the entire stream — the existing gzip
   envelope gives this for free (whole-stream CRC32 in the member trailer;
   the current exporter already gzips, `DatabaseExport:90`–`:95`, and the
   import already decompresses, `DatabaseImport:138`–`:143`), so "keep the
   dump gzip-framed and verify full decompression" is the cheapest compliant
   form.
2. **Directory fsync degradation.** The repo's only directory-fsync
   precedent is best-effort with the failure ignored (incremental-backup
   unit files, `DiskStorage:2088`–`:2093`, `:2116`–`:2121`) because
   `FileChannel.open(directory)` fails on non-POSIX platforms. The F82 pin
   should state the same best-effort semantics plus the reason it is safe:
   every lost-rename outcome is fail-closed — a manifest whose directory
   entry did not survive is a missing manifest, and F75's hard-fail fires;
   a dump whose entry did not survive is a missing dump. No silent state
   exists, so a failed directory fsync degrades to a loud retry, not a
   wrong import.
3. **Non-atomic rename fallback.** The utility an implementer will reach
   for, `FileUtils.atomicMoveWithFallback` (`FileUtils:306`–`:319`), falls
   back to a plain `Files.move` when `ATOMIC_MOVE` is unsupported. For the
   manifest this is acceptable for the same fail-closed reason (a torn or
   missing manifest hard-fails the import), but the bullet should say so
   rather than assume the rename is always atomic.

**Affected entries.** F82, F75, D20.

---

## Attacks run that produced no new finding

- **F78 drain coverage across the full schema commit window (the mission's
  second F78 attack).** Clean: `freeze(db, …)` takes `stateLock.readLock()`
  as its first act (`AbstractStorage:3889`) and parks behind the
  schema commit's held write lock until the commit releases after promotion
  and the trailing `forceSnapshot` (D19/F52 scope), so the drain is bounded
  by `stateLock`, not by the freezer's operations counter (whose window ends
  earlier, at `endAtomicOperation`'s outer finally,
  `AtomicOperationsManager:441`–`:443`). The transient internal freezes that
  do not take `stateLock` first are covered inside U18.
- **F78 throw-then-unwind residue on the frozen snapshot (the mission's
  first F78 attack).** Clean on the frontend-commit path: the gate sits
  before the table registration, the tx body writes nothing under D1
  (pass-6 ground), the freezer counter balances before the throw, and a
  `startTxCommit` failure bypasses the inner finally that drives
  `rollback(error, op)` — no WAL record, no physical write, no table entry,
  nothing for the frozen snapshot or a later replay to see. The wrapper-path
  masking cascade is recorded inside U18 as a wiring pin.
- **F77 crash between population's puts and re-derivation's puts.** Both put
  phases are page records inside the one commit unit; every pre-`commitChanges`
  crash leaves no end record and recovery discards the unit whole, and an
  end-record-bearing unit replays page-by-page, content-agnostic — a
  forward-correct build replays correctly. The F66 regression pair stays a
  forward-correctness pair; replay coverage belongs to the YTDB-1099
  kill-mid-physical-phase matrix as already specified.
- **F77 skip-source starvation by the eager delete-flush.** Fails: the
  population skip set is `recordOperations` (`FrontendTransactionImpl:83`),
  a distinct map from the flush-drained `operationsBetweenCallbacks` (`:88`,
  cleared at `:782`); `recordOperations` is cleared only at tx end
  (`clearUnfinishedChanges:999`). Re-derivation's final-state values are the
  same live objects `commitEntry` serializes, so they are present at commit
  by construction.
- **F80 crash after `commitChanges`, before registry publication.** Premises
  unchanged by the commit-local allocator: the allocated ids are persisted in
  the unit's own config records, so the restart's registry rebuild
  (`createCollectionFromConfig`, open-time caller PSI-verified in pass 7)
  reads exactly what replay applied; the never-published in-memory state died
  with the process. Pass-6 ground, not re-tilled beyond the premise check.
- **F80 id reuse after a rolled-back commit under F55 partial replay.**
  Cannot compose: a rolled-back unit has no end record and `restoreFrom`
  applies units only on their end record, so not even a partial replay of a
  rolled-back commit exists; its ids, names, and in-memory bookings leave no
  durable trace (the booking residue self-heals on retry via the
  negative-entry re-book, pass-7 ground).
- **F81 concatenated/multi-document dump forms.** None exist: the dump is
  one JSON object document (root opened in the constructor,
  `DatabaseExport:99`/`:112`, closed only in `close()`, `:277`) for both
  the file and stream constructors, and the file path enforces gzip
  (`:82`–`:84`). The single-document premise of the F81 check holds; its
  detection-power defect is U19, not the document model.
- **F82 multi-file dump.** The export is a single file (one gzip stream);
  schema and records are sections of one document, never separate files, so
  the "dump file(s)" wording covers reality.
- **F76 scoped-out mid-commit-window strand, dead owner (envelope
  honesty).** Honest for the schema-commit case: the dead thread holds
  `stateLock.writeLock`, so every reader parks and the outage is loud;
  restart replays an end-record-bearing unit or discards an unfinished one
  (F55 prerequisite), and the leaked process-lifetime state (the freezer's
  stranded operations count, on which a later `freezeOperations` spins at
  `OperationsFreezer:81`–`:83`; the IN_PROGRESS table entry pinning segment
  cuts; the held locks) all clears with the restart the outage forces. The
  pre-existing data-commit analog (read lock, no outage, invisible WAL
  growth) is unchanged by this design.
- **F76 back edge: a strand inside the post-`endTxCommit` publication
  window.** No separate finding: `status` stays COMMITTING from
  `doCommit:668` until `:699`, which spans `internalCommit` and therefore
  promotion, overlay publication, and the trailing `forceSnapshot`; the
  atomic carrier U17 prescribes covers this edge by construction, and a
  dead owner there strands the metadata locks, which is the same loud
  outage-then-restart envelope (durable state already complete; the restart
  load path rebuilds the registries from it).
- **F80 engine publication order vs allocated ids.** Same-thread,
  deterministic, and the open path does not depend on slot order anyway —
  `openIndexes` re-slots engines by persisted `IndexEngineData.indexId`
  (F29). One-line plan pin at most.
- **F77/F70 enqueue-window interaction.** Unchanged by the F77 fold; the
  residual concurrent-data-commit window stays documented in D12 with
  closure in YTDB-1101. Premises identical, not re-tilled.
