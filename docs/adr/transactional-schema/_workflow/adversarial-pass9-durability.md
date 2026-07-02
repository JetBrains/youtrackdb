# YTDB-382 — Adversarial pass 9: durability (2026-06-11)

Verdict: 3 new findings (0 BLOCKER, 2 MAJOR, 1 MINOR). This pass attacked only
the pass-8 settlement text, the decision-log diff
`589116eee3..f1c0c4928d`: D20's rewritten F81 bullet (F90 section-presence
criterion), D20's F82 pins (F91 whole-stream validation, best-effort directory
fsync, warn-logged move fallback), D7's rewritten abnormal-termination bullet
(owner-thread-only teardown, reaping withdrawn to YTDB-1114), D7's composed
freezer bullet (F86+F87), D3's allocator-seed sentence (F88), and the F83–F91
resolution records in §2a as specs. Both MAJORs are premise contradictions in
fresh settlement text rather than new mechanisms that corrupt state on their
own: D7's owner-thread-only invariant is asserted at exactly the teardown
entry points the tree exempts from thread checks, and F90's residue
enumeration misses a success-exit data-loss path the legacy exporter ships
today.

Method: the settlement diff read hunk by hunk, the full current D3/D7/D20
entries and F75–F91 records read for context, all eight prior reports read
including every failed-attack list. mcp-steroid was reachable with the
`transactional-schema` project open at the repo root; every reference-accuracy
claim below (caller sets, registrar inventories) was re-verified with PSI
`ReferencesSearch` and is marked PSI-verified. Line-anchor and control-flow
claims were verified by reading the live tree (`DatabaseExport`,
`DatabaseImport`, `FileUtils`, `OperationsFreezer`, `AtomicOperationsManager`,
`FrontendTransactionImpl`, `AbstractStorage`, `DiskStorage`,
`IndexManagerEmbedded`, `YTDBGremlinSession`, `YTDBAbstractOpProcessor`). Each
attack asks "the process dies, the scheduler fires, or the routine failure
lands at instruction boundary X; what do the durable state, recovery, or the
import see?" against the real code.

---

## U21: D7's owner-thread-only invariant is asserted but unenforced at the teardown entry points — the tree's exempted cross-thread `rollbackInternal` plus two live scheduler-thread initiators contradict "rejected or no-op" exactly where the violation durably commits torn schema state [MAJOR]

The rewritten abnormal-termination bullet states the design's invariant:
"Teardown is **owner-thread-only** — the design's invariant for every
tx-scoped resource: the D7 mutex, the freezer engagement, the D19 lock, the
`tsMin` holder accounting, and the commit-local structural-id allocator state.
… cross-thread teardown attempts are rejected or no-op, extending the
thread-id-gate semantics that ship today (`close()` skips `resetTsMin` for
foreign threads, `FrontendTransactionImpl:954`)." The F85 resolution record
rests on the same claim: "No reaper, no second claimant: `status` stays a
plain single-writer field and the `BEGUN, COMMITTING ->` arm again serves only
the owner's own commit-failure unwind."

The tree contradicts both sentences. The shipped thread-id gate covers only
the `resetTsMin` call: inside `close()`
(`FrontendTransactionImpl:948`–`:970`) the `storageTxThreadId` check guards
`resetTsMin` alone (`:954`–`:956`), while `clear()`,
`atomicOperation.deactivate()` (`:953`), the `atomicOperation = null` write
(`:964`), and `status = INVALID` (`:969`) all execute on whatever thread
called in. The teardown entry points are deliberately exempt from the
owning-thread assertion — `assertOnOwningThread`'s Javadoc: "Excluded from
this check: `close()` and `rollbackInternal()`, which may be called
cross-thread during pool shutdown" (`FrontendTransactionImpl:130`–`:133`).
`rollbackInternal`'s `BEGUN, COMMITTING ->` arm proceeds for a foreign caller
(`:368`–`:386`): it sets `ROLLBACKING`, runs `clear()` (`:385`), and at
`txStartCounter == 0` runs `close()` (`:400`). And the second claimant the
F85 dissolution denies exists in the server today: the Gremlin
evaluation-timeout hooks call `tx.rollback()` from the scheduler thread
against the shared traversal-source transaction
(`YTDBGremlinSession:219`–`:224` and `YTDBAbstractOpProcessor:614`–`:619`,
verified in the tree; the settlement's own F84 note records the same sites on
YTDB-1113). Nothing on that path is rejected and nothing no-ops except the
`tsMin` decrement.

The unsafe schedule, on the design's primary new feature path:

1. A client runs DDL through the Gremlin server (create class plus a unique
   index in one tx). The evaluation timeout is sized for queries.
2. The owner thread enters `doCommit`: `status = COMMITTING`
   (`FrontendTransactionImpl:668`), then `internalCommit` (`:670`) — the
   schema-commit window with all four locks, the registered atomic operation,
   and D12's commit-time index population. For a populated class the window
   is F48-scale wall time and exceeds the timeout.
3. The timeout fires. The scheduler thread runs `afterTimeout` →
   `tx.isOpen()` → `tx.rollback()` → `rollbackInternal` on the foreign
   thread; the exemption admits it and the `BEGUN, COMMITTING ->` arm
   proceeds. **← second writer enters the live commit**
4. Foreign `clear()` (`:972`–`:988`) unloads records, nulls
   `record.txEntry`, and clears track data on the same objects the owner's
   `commitEntry` serialization is reading inside the atomic operation;
   foreign `close()` deactivates and nulls the `atomicOperation` mid-apply.
5. The race decides the outcome. Benign arm: the owner throws on the torn
   state before `commitChanges` writes the end record; recovery discards the
   unit. Corrupting arm: the owner's remaining page writes serialize
   concurrently-cleared record state and the end record lands; recovery
   replays the unit and the torn schema/record bytes are durable.

This is the F85/U17 tear shape, and the settlement knows the foreign caller
exists — it records the sites on YTDB-1113 and calls them "today-bugs
independent of this design." The fresh defect is in that classification. The
design widens the `COMMITTING` window from sub-millisecond data commits to
F48-scale schema commits, which turns timeout-fires-mid-commit from a tail
race into the expected schedule for long DDL over Gremlin, the exact arm the
hooks guard. Meanwhile the rewritten bullet's resource enumeration omits the
only two tx-scoped resources whose cross-thread teardown corrupts durable
state — the open atomic operation and the tx record-operation state feeding
`commitEntry` serialization — and the design makes the YTDB-1113
owner-executor fix neither a prerequisite nor a named dependency. As
specified, v1 ships an invariant the tree violates on its highest-stakes
path.

Severity: MAJOR — the spec's premise ("rejected or no-op"; "no second
claimant") is contradicted by the tree. Not BLOCKER because the corrupting
entry point is a pre-existing server defect the design could close by
fiat: either pin YTDB-1113's owner-executor fix as a v1 prerequisite of D7,
or extend the owner-thread-only enumeration with the atomic operation and tx
record state and give `rollbackInternal` a real `COMMITTING`-window gate in
place of an exempted assert.

## U22: F90's residue claim is contradicted by the tree — the legacy exporter turns a mid-collection iteration failure into a success exit with the collection's tail silently absent, so exit status, section presence, and the ack flag all pass a dump that lost records [MAJOR]

D20's rewritten F81 bullet claims the closure is complete up to one named
residue: the ack flag "honestly covers the one residue section presence
cannot see: damage inside the final `indexes` section. Procedure pin: a dump
file at the final name proves nothing about export success (the failure path
renames too), so the operator verifies the export's exit status before
importing."

The tree has a second residue class, and it defeats the exit-status pin too.
In `exportRecords` the per-collection `try` wraps the whole iterator loop
(`DatabaseExport:201`–`:241`). Only `YTIOException` is rethrown
(`:212`–`:220`). Every other exception thrown by `it.hasNext()`/`it.next()`
— the corrupted-record and broken-page class of failures the surrounding log
message ("It seems corrupted") exists for — lands in `catch (Exception t)`
(`:221`), which logs (and when `rec == null`, because the collection's first
fetch failed, logs nothing at all), adds nothing to `brokenRids`, and does
not rethrow. The loop then proceeds to the next collection. `brokenRids` is
populated only inside `exportRecord` (`:582` ff.), which covers serialization
failures of records that were successfully fetched; iterator-level failures
never reach it. `exportDatabase` then completes normally and the tool exits
with success.

The failure schedule needs no crash, only one unreadable record in the old
database — the population `brokenRids` was invented to serve:

1. The old binaries run the D20 export. Collection X's scan throws a non-IO
   exception at record k (broken page, deserialization failure).
2. The outer catch swallows it; records k..n of collection X are silently
   absent from the dump. **← the loss** Export continues and exits 0.
3. The operator follows the settled procedure exactly: exit status 0 ✓,
   section presence ✓ (all six sections written; the `records` array is
   well-formed, merely short), ack flag passed ✓.
4. The import completes and reports success. Records k..n exist in the old
   database, are absent from the new one, and are absent from `brokenRids`,
   so nothing downstream of the export log can detect the loss.

The information does surface in the export listener output — the
per-collection progress line prints `OK (records=current/approximateTotal)`
(`:243`–`:245`) and an error log line appears when `rec != null` — but the
settled procedure pins only the exit status, and the residue sentence claims
section presence misses nothing except final-section damage. Both premises
are contradicted by the same file the fold's spot-check cites.

Severity: MAJOR — same class as F90 itself: a verification mechanism whose
stated coverage passes exactly a case it was invented to catch, on the
primary migration path. Closure cannot patch the old binaries (option (a)
stays rejected), so the residue enumeration must widen and the procedure must
gain a check that sees this loss: rephrase the ack flag's coverage as "any
source-side loss the old exporter does not report," and pin a count
comparison (per-collection record counts read from the old binaries against
the import's reported counts) or at minimum an export-log review step.

## U23: "Whole-stream CRC32 for free" equates gzip's per-member trailer with the whole-stream requirement — Java's decoder reads a malformed next-member header as clean EOF, so "verify full decompression" validates only a prefix under multi-member framing that nothing pins away [MINOR]

D20's F82 bullet: "the dump's existing gzip envelope supplies a whole-stream
CRC32 for free, so 'keep it gzip-framed and verify full decompression' is the
cheapest compliant form."

The premise is a per-member fact, stated as a whole-stream one. RFC 1952's
CRC32 and ISIZE live in each member's trailer, and a gzip file is a
concatenation of members. The JDK decoder makes the gap exploitable in
exactly the direction F91 worried about: `GZIPInputStream.readTrailer()`
probes for a next member and swallows the `IOException` from a malformed
header, reporting clean end-of-stream. Trailing garbage, a zero-filled
region at a member boundary, and truncation at a member boundary all read as
a fully-validated successful decompression of a prefix. Today the claim
holds only by coincidence of implementation: the current exporter writes a
single member (one `GZIPOutputStream`, `DatabaseExport:90`–`:98`), and no
settlement text pins that framing. Flush-per-section multi-member output is
the natural way an implementer adds streaming flush boundaries to the
net-new stream variant the bullet is specifying.

No reachable unsafe schedule escapes both layers, which caps the severity:
with the manifest as the trailing section, every silent prefix-stop also
drops the manifest from the decompressed view and F75 hard-fails, and a
zero-filled middle inside a single member fails inflate loudly. The defect
is the false equivalence — an implementer told the envelope is already
"compliant" with the whole-stream requirement can ship multi-member framing
believing the decompression check covers the stream, at which point the
design's two independent validation layers silently collapse to one
(manifest presence). Severity: MINOR, an under-specification an implementer
could plausibly fill in wrong. Pin one sentence: the dump stays a single
gzip member, or decompression success is necessary and never sufficient,
with the manifest/section checks remaining the authority.

---

## Attacks run that produced no new finding

- **Freezer wiring-pin direction (throw before depth increment).** The
  existing gate already throws (`OperationsFreezer:40`) before the depth
  increment (`:56`) with `operationsCount` balanced by the preceding
  decrement (`:38`); `endAtomicOperation`'s `endOperation()` is unconditional
  (`AtomicOperationsManager:441`–`:443`), and the frontend path bypasses it
  on a `startTxCommit` failure (pass-8 ground re-checked). An implementer
  following both wiring pins reproduces the existing balanced structure; the
  corrupted-count square of the 2×2 is only reachable by violating a pin.
- **Freeze-kind taxonomy vs the registration-site inventory.** PSI-verified:
  `ReferencesSearch` on `AtomicOperationsManager#freezeWriteOperations`
  returns exactly the five claimed sites (`AbstractStorage:3749` doSynch,
  `:3901`/`:3905` both `freeze()` arms, `DiskStorage:356` copyWALToBackup,
  `:1248` storeBackupDataToStream) and no sixth caller. The three transient
  sites all pass a null supplier (park-mode), matching the taxonomy's
  classification.
- **In-window backstop throw leaving durable residue.** The gate is
  `startToApplyOperations`' first statement (`AtomicOperationsManager:107`),
  before table registration and any WAL contact; a backstop throw leaves no
  record for recovery. Pass-8 dry-list premises unchanged by the composed
  mechanism.
- **Transient-quiesce park holding three metadata locks (durability arm).**
  The parked schema commit has written nothing durable (the park precedes
  `stateLock.write` and the atomic operation) and both backup quiesces
  release after a bounded cut/flush; a wedged quiesce is an availability
  residue for the concurrency lens. Noted in passing for that lens: "Data
  commits keep today's uniform park everywhere" misdescribes today — an
  engaged `freeze(db, true)` throws data commits via the registered supplier
  (`OperationsFreezer:114`–`:118`); wording drift only, no durability
  content.
- **D3 seed read vs registrar interleave and crash seams.** PSI-verified:
  `addIndexEngine`'s callers are exactly `IndexAbstract#create:196` and
  `IndexAbstract#rebuild:305` (the latter reachable from the user rebuild
  API and the `RecreateIndexesTask` thread spawned at
  `IndexManagerEmbedded:499`); `loadExternalIndexEngine`'s sole caller is
  `IndexAbstract#load:240`. All run under `stateLock.write`, so the pinned
  in-window seed read cannot interleave with any of them.
- **Allocator seed vs WAL replay.** Replayed units carry their allocated ids
  in their own config page records; the commit-local allocator exists only
  in process memory and recovery rebuilds the registries from replayed
  bytes. A crashed or failed commit leaves no durable trace of its ids
  (pass-6/8 ground re-checked against the F88 pin).
- **Lost-rename outcomes beyond "missing" (re-export overwrite schedule).**
  The exporter deletes the final-name file at construction
  (`FileUtils.prepareForFileCreationOrReplacement:283`–`:287`), so a stale
  dump at the final name needs both the delete and the dump rename lost
  while the later manifest rename survives — out-of-order metadata
  durability that ordered-journal filesystems exclude. Where conceivable,
  the manifest's counts mismatch the stale dump except for count-identical
  sources, and the converse combination (fresh dump, stale manifest)
  hard-fails on mismatch. The "every lost-rename outcome is fail-closed"
  sentence survives every schedule I could construct; a plan note pinning
  "export into an empty directory" would retire the residue entirely.
- **Non-atomic `Files.move` fallback torn mid-copy.** A partial manifest at
  the final name is unparsable and F75 hard-fails; a partial dump fails gzip
  decode or section presence. Fail-closed as the bullet states. The F91
  correction is accurate: the fallback warn-logs (`FileUtils:310`–`:317`).
- **Import-side gzip-header fallback.** `DatabaseImport:138`–`:143` swallows
  a corrupted gzip header and reroutes to plain-JSON parsing, which fails
  loudly on binary garbage; truncation throws mid-tag-loop rather than at
  the cited `:138` (the constructor reads only the header), after partial
  target population — still loud, and F63's out-of-service procedure covers
  the partial target. Anchor imprecision, no unsafe outcome.
- **Section-presence false positives.** This fork's exporter has no
  include/exclude options (`DatabaseImpExpAbstract.parseSetting` handles only
  `-useLineFeedForRecords`; `DatabaseExport` adds compression knobs), and all
  six sections are written unconditionally in fixed order with `indexes`
  last (`exportInfo`/`exportCollections`/`exportSchema:449`/`exportRecords`
  incl. `brokenRids:261`/`exportIndexDefinitions:393`). The check cannot
  reject a legitimate complete dump.
- **Owner-thread-only withdrawal re-opening a committed-state hole.** A
  stranded tx's pins are in-memory only (mutex token, `tsMin`, freezer
  depth, held locks); process death clears them and WAL recovery is
  owner-agnostic. A between-operations strand holds no table entry and no
  WAL pin (F74 ground), so the leak is heap growth plus DDL unavailability,
  reported by the YTDB-550 monitor — the reaper's withdrawal changes
  reclamation, never durable state. (U21 attacks the live-foreign-caller
  case, a different seam.)
- **Mid-commit-window strand, dead owner, under the rewritten bullet.**
  Unchanged from the pass-8 dry-listed walk: the held `stateLock.write`
  makes the outage loud, and restart discards a no-end-record unit or
  replays a complete one.
- **Legacy export success-exit then host crash (old binaries never fsync the
  dump).** A lost or truncated dump fails gzip decode or section presence,
  or is simply absent — loud at import; re-running the export after a host
  crash is the obvious operator move and the procedure pin already implies
  it. Fail-closed.
- **F84/F79 same-thread double-release guard.** `close()` is idempotent via
  the `atomicOperation` null-out (`FrontendTransactionImpl:951`–`:966`) and
  the `resetTsMin` underflow throw is live (`IllegalStateException` in
  `AbstractStorage.resetTsMin`). The dissolution's "stays guarded" claim
  holds.
