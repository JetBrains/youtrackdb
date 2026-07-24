# Crash-safety / durability review — Track 8 Step 2, iteration 1

- **Commit under review:** `908a2374e6` ("Persist bootstrap-valid empty-schema root at creation")
- **HEAD at review time:** `fbbf661d3e` (branch `transactional-schema`)
- **Perspective:** crash-safety / durability. Finding IDs continue from **CS50**.
- **Binding spec:** `plan/track-8.md` Step 2 (track-8.md:369-395), `plan/track-8-design-drafts.md`
  §G2.a (design-drafts:97-121), FM-G1 (design-drafts:209), amended FM-G3 W-state table
  (design-drafts §A1, :754-768), CS35 fold (design-drafts:700, :787-789).
- **Mode:** read-only; no Maven; no file modification outside this report.

## 0. Diff footprint (scope check)

`git show 908a2374e6 --stat`: exactly two files —
`core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java`
(+17/-1, all inside `create()`) and the new test
`core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/GenesisSchemaBootstrapTest.java`
(+121). No storage, WAL, config, importer, or `IndexManagerEmbedded` production code is touched.
This matches Step 2's declared seam ("Sole owner of `SchemaShared.create`", track-8.md:394-395)
and the Q-G3 GREEN verdict (`IndexManagerEmbedded.java` untouched, track-8.md:552-563).

## 1. Criteria and premises

**Criteria.**

- C1 (charter 1): the bootstrap payload must be written in the *same durable unit* as root-record
  creation — no crash may leave a created-but-payload-less root on disk (the FM-G1 state the fix
  exists to eliminate). Every crash point through `create()` → `computeInTx` → commit →
  `setSchemaRecordId` must classify into the amended FM-G3 W-state table with no new state.
- C2 (charter 2): for each reachable intermediate on-disk state, the reopen contract
  (`SharedContext.load` → `SchemaShared.fromStream` / `IndexManagerEmbedded.load` /
  `SecurityShared.load`) must not make any state *silently openable* that previously failed or
  signalled loudly — except within the design-accepted CS35 scope (the `fromStream` "schema is
  empty" breadcrumb superseded by Step 3's genesis-completion marker).
- C3 (charter 3): pre-existing databases — (a) old-create root later rewritten by a normal schema
  save must be unaffected; (b) an old-create root never saved (broken virgin at rest) must not be
  handled worse (or misleadingly better) by the new code on open.
- C4 (charter 4): the fix must not alter the WAL/atomic-operation composition of storage create
  in any way not already covered by the Step 1 crash-safety review
  (`crash-safety-step1-iter1.md`).

**Premises (each verified against HEAD source).**

- P1. `SchemaShared.create` at HEAD (SchemaShared.java:1387-1414): under the schema write lock,
  `session.computeInTx(...)` runs a lambda that (i) creates the root via
  `session.newInternalInstance()`, (ii) assigns `identity = root.getIdentity()`
  (SchemaShared.java:1403), (iii) returns `toStream(session)` (SchemaShared.java:1404); after the
  tx returns, `this.identity = entity.getIdentity()` (:1407) and
  `session.getStorage().setSchemaRecordId(...)` (:1408).
- P2. `newInternalInstance` (DatabaseSessionEmbedded.java:2109-2126) allocates an `EntityImpl`
  with a provisional `ChangeableRecordId` and enrols it in the current tx as a `CREATED` record
  operation. **Nothing is written to storage at this point** — the record insert is deferred to
  commit.
- P3. `computeInTx` → `computeInTxInternal` (DatabaseSessionEmbedded.java:5303-5315) is
  begin/apply/commit with **no retry loop**; a lambda throw rolls back (`finishTx(false)`).
- P4. A frontend transaction owns exactly **one** WAL atomic operation, started at `begin()`
  (`FrontendTransactionImpl.java:241` → `startStorageTx` →
  `atomicOperationsManager.startAtomicOperation()`, AbstractStorage.java:6637-6640) and ended
  once at commit (`endAtomicOperation`, AbstractStorage.java:6624). The commit apply
  (`AbstractStorage.commit:2519` → `applyCommitOperations:2806`) calls `makeStorageDirty()`
  (AbstractStorage.java:2817; `DiskStorage.makeStorageDirty:611-613` writes the startup-metadata
  dirty flag) *before* applying record operations inside that atomic operation.
- P5. `toStream(session)` (full-write overload, SchemaShared.java:1076-1080 → 1101-1249) loads
  the root by `identity` (`session.load(identity)`, SchemaShared.java:1126) and, for an empty
  schema (no classes), dirties **only the root entity**: `classes` map empty → per-class loop
  writes nothing, `existingLinks == null`, drop loop no-op; the payload block
  (SchemaShared.java:1231-1241) sets `schemaVersion` = `CURRENT_VERSION_NUMBER` (= 6,
  SchemaShared.java:71), empty `globalProperties` EMBEDDEDLIST, `collectionCounter` (0), empty
  `blobCollections` EMBEDDEDSET. No `classes` link set is allocated (`getOrCreateLinkSet` never
  called on the empty path).
- P6. The in-tx load of the provisional id is served from the transaction itself:
  `executeReadRecord` consults `getTransactionInternal().getRecord(rid)`
  (DatabaseSessionEmbedded.java:2239) **before** the `isValidPosition` rejection
  (DatabaseSessionEmbedded.java:2288-2290), so `toStream`'s `session.load(identity)` on the
  provisional `ChangeableRecordId` returns the tx-enrolled entity, not a storage read.
- P7. `setSchemaRecordId` (AbstractStorage.java:8117-8140) is a **separate** atomic operation
  (`makeStorageDirty` + `executeInsideAtomicOperation` writing the `schemaRecordId` config
  property, CollectionBasedStorageConfiguration.java:666-670). Same two-step shape for the IM
  pointer (AbstractStorage.java:8242-8265; `IndexManagerEmbedded.create:608-621`).
- P8. A fresh storage configuration never initializes `schemaRecordId`/`indexMgrRecordId`
  (`init`, CollectionBasedStorageConfiguration.java:2068-…, sets neither; the properties are only
  preloaded if present, :2060-2067). `getSchemaRecordId()` therefore returns `null` until
  `setSchemaRecordId` commits; `RecordIdInternal.fromString(null, false)` returns a fresh
  `ChangeableRecordId` (RecordIdInternal.java:124-131) whose position is invalid.
- P9. Reopen path: `SharedContext.load` (SharedContext.java:132-155) runs `schema.load` →
  `schema.forceSnapshot` → `indexManager.load` → `security.load` → creators.
  `SchemaShared.load` (SchemaShared.java:1366-1385) throws `SchemaNotCreatedException` when the
  pointer is invalid (:1370-1375). `IndexManagerEmbedded.load` (IndexManagerEmbedded.java:110-126)
  parses the IM pointer and `loadEntity`s it; an invalid rid throws
  (`DatabaseException("Invalid record id …")`, DatabaseSessionEmbedded.java:2288-2290).
  `IndexManagerAbstract.load` (IndexManagerAbstract.java:231-246) null-guards
  `getLinkSet(CONFIG_INDEXES)` (:235-236) — the empty IM shell parses. `SecurityShared.load` →
  `setupPredicateSecurity` (SecurityShared.java:1074, 1078-1101) even **creates**
  `OSecurityPolicy` when absent (self-mutating open).
- P10. The `fromStream` "schema is empty" branch is **retained** at HEAD
  (SchemaShared.java:886-895: `schemaVersion == null` → error log + early return); the diff did
  not touch it. The version-mismatch reject (`schemaVersion != 6` → `ConfigurationException`)
  is at :895-903.
- P11. Genesis call chain: `YouTrackDBInternalEmbedded.internalCreate`
  (YouTrackDBInternalEmbedded.java:773-777) → `newCreateSessionInstance` →
  `DatabaseSessionEmbedded.internalCreate` (DatabaseSessionEmbedded.java:572-587) →
  `createMetadata` (:598-603) → `SharedContext.create` (SharedContext.java:196-241) →
  `schema.create(session)` (:199) → `indexManager.create` (:200) → `security.create` (:201) →
  creators → `createClass(O/V/E)` (:208-210) → blob registration loop (:222-226, each
  `addBlobCollection` self-commits per the CS47 correction, track-8.md:74-88). The session is
  fresh; **no user transaction is active** when `schema.create` runs, so `computeInTx` opens and
  commits a real top-level transaction.
- P12. WAL recovery replays a durable **prefix** of the log in order; a torn trailing atomic
  operation is rolled back when the dirty flag is set (mechanism inherited; the doCreate-local
  clean-flag exception is the pre-existing W2 window, owned by Step 1's review C3 and the design
  W-table, unchanged by this diff).

## 2. Charter (1) — is the payload in the same durable unit as root creation?

**Verdict: YES — verified by mechanism; null defect.**

The bootstrap payload is not a *second write* that follows root creation; it is the **content of
the single record-insert operation** that creates the root. Chain: the root exists only as a
tx-enrolled `CREATED` operation until commit (P2); `toStream` mutates that same in-memory entity
inside the same tx (P5, P6); the commit applies the one `CREATED` operation — root record *with*
payload — inside the transaction's single WAL atomic operation (P4). There is no on-disk
instant, even *inside* the atomic operation, at which the root exists without its payload bytes:
the insert's record content is the serialized entity, payload included. A rollback (crash before
the op's commit is durable, with the dirty flag set per P4) removes the root entirely.

The implementer's claim "no crash window exposes a payload-less root" is therefore **confirmed**,
with the standard premise P12 (prefix replay + dirty-flag recovery) — the same premise every
other tx commit in the system rests on, not a new one introduced by this change.

### 2.1 Exhaustive crash-point enumeration through `create()` and its genesis surroundings

Durable-unit ordering on the disk profile (each `U` is one WAL atomic operation):

- **U0** — storage create (`AbstractStorage.doCreate`, one op incl. `internal` + `$blob*` +
  config; ends with `clearStorageDirty`). *Covered by Step 1's review C0–C3; unchanged.*
- **U1** — `schema.create`'s tx commit: root record insert **with payload** (P1–P6).
- **U2** — `setSchemaRecordId` config-property op (P7).
- **U3** — `indexManager.create`'s tx commit: empty IM root insert
  (IndexManagerEmbedded.java:608-619).
- **U4** — `setIndexMgrRecordId` config-property op (IndexManagerEmbedded.java:620;
  AbstractStorage.java:8242-8265).
- **U5..Un** — `security.create` DDL, creators, `createClass(O/V/E)`, per-iteration blob
  registrations — each a legacy self-commit that **rewrites the root with the full payload**
  (`saveInternal` → `executeInTx(toStream)`, SchemaShared.java:1513-1533; CS47 record,
  track-8.md:74-88). *Step 3 will restructure these; out of this diff.*

| # | Crash window (process death; durable prefix per P12) | Durable on-disk state | Reopen behavior at HEAD | W-state | Δ vs pre-Step-2 |
|---|---|---|---|---|---|
| K0 | inside U0 | Step 1's C0–C3 (incl. the pre-existing W2 clean-flag corpse) | config load fails loudly / WAL rolls back | W0–W2 | **none** — diff does not touch `doCreate` |
| K1 | after U0, before U1 durable — includes: mid-lambda (nothing durable, P2), mid-commit-apply (torn U1, dirty flag set at AbstractStorage:2817 → recovery rolls it back) | no root record; `schemaRecordId` unset (P8) | `SchemaShared.load` → `SchemaNotCreatedException` (SchemaShared.java:1370-1375) — **loud** | W3 | **none**. Payload rides U1: it cannot exist without the root, and the root cannot become durable without it |
| K2 | U1 durable, U2 not (incl. torn U2 → rolled back) | orphaned root record in `internal` collection, **with payload**; pointer unset | identical to K1 — `SchemaNotCreatedException`, **loud** | W4 | orphan carries payload bytes instead of an empty entity; reopen signature identical; one leaked record slot in a condemned DB (design: "condemned by the marker", W4 row) |
| K3 | U2 durable, before U4 durable (either side of U3) | root + schema pointer good; IM root and/or pointer absent | schema parses **cleanly** (schemaVersion 6, P5); then `IndexManagerEmbedded.load` parses a null pointer → invalid rid → `DatabaseException` (P8, P9) — open **fails loudly** | W5 | pre-Step-2 this window *additionally* logged the `fromStream:887-894` breadcrumb before failing; post-Step-2 the log line is gone but the open still throws. Detectability preserved by the IM failure (the design's W5 "IM load fails (loud)" is confirmed by mechanism) |
| K4 | U4 durable, before U5 (first security DDL self-commit) durable | complete root shells: bootstrap-valid schema root + pointer, empty IM root + pointer; **zero classes, zero users**; `$blob*` physically present but unregistered (payload's `blobCollections` empty) | schema parses cleanly; IM shell tolerated (IndexManagerAbstract.java:235-236 null-guard; Q-G3, pinned by `emptyIndexManagerRootShellToleratesReopenLoad`); `SecurityShared.load` proceeds and even self-mutates (`setupPredicateSecurity`, SecurityShared.java:1078-1101); an unauthenticated/internal open **SUCCEEDS silently**; an authenticated open fails with a credentials-shaped error | W6 | **the CS35-accepted regression**: pre-Step-2 this state produced the "Database's schema is empty!" error log (SchemaShared.java:886-894) and then opened anyway; post-Step-2 it opens with **zero signal**. Accepted BY DESIGN (design-drafts:114-118, :787-789) pending Step 3's marker — see finding CS50 for the bookkeeping gap |
| K5 | between U5..Un self-commits (partial genesis DDL) | root carries a partial schema, always with payload | pre-existing partial-genesis states; the breadcrumb never fired here even pre-Step-2 (the first self-commit already wrote the payload) | pre-W6/W7 spectrum | **none** |

Unregistered-blob note for K4/K5: readers consult only the schema's registered set
(`getBlobCollectionIds`, design-drafts FM-G5 row), so physically-present unregistered `$blob*`
collections are inert — FM-G5's "benign, condemned by §A1" answer carries over unchanged.

**No new W-state exists; no state moved to a *worse* row.** The only classification change is
K4/W6 losing its log line — exactly the CS35 scope.

### 2.2 Exception paths (no process crash) — checked for completeness

- **E1 — lambda or commit throws** (e.g. `toStream` failure, commit conflict):
  `computeInTxInternal`'s finally rolls back (P3) → nothing durable → on-disk = K1/W3 class.
  *New in-memory residue:* the `identity` **field** now retains a dead provisional
  `ChangeableRecordId` (assigned at SchemaShared.java:1403 before the commit), where pre-Step-2
  it stayed `null` until after a successful commit. Analysis: benign — see finding CS51.
- **E2 — `setSchemaRecordId` throws after U1 committed:** durable state = K2/W4; `create()`
  propagates; pre-Step-3 HEAD performs no cleanup (`exists()` stays true — the known §A1 gap
  owned by Step 3, design-drafts:738-747). Unchanged by this diff except that the in-memory
  `identity` is a *valid persistent* rid rather than null — irrelevant, the context is condemned
  and `SchemaShared.load` overwrites `identity` on any later load (SchemaShared.java:1370).

## 3. Charter (2) — reopen contracts; detectability beyond the CS35 scope?

**Verdict: null — no detectability regression beyond the design-accepted CS35 scope.**

Exhaustive check of "loud → silent" transitions. Pre-Step-2, the breadcrumb
(SchemaShared.java:886-894 — a **log line + early return**, never a throw) fired for exactly the
reopen window [U2 durable … first `saveInternal` durable]. That window decomposes into K3 and K4:

- **K3** (IM pointer not yet durable): pre-Step-2 = breadcrumb *then* a loud IM-load throw;
  post-Step-2 = the same loud throw without the breadcrumb. The open still fails; only a
  redundant log line is lost. Not a silent-open transition.
- **K4** (shells complete): pre-Step-2 = breadcrumb + open proceeds; post-Step-2 = open proceeds
  with no signal. This is precisely the state the design's §G2.a "Completeness signal" paragraph
  predicts ("G2.a alone would *worsen* post-crash diagnosability", design-drafts:114-118) and
  the §A1 CS35 fold accepts, with Step 3's marker as the strictly-stronger replacement
  (design-drafts:787-789). **In scope of the accepted design; not a new defect.** The residual
  concern is purely bookkeeping — the *in-tree armed interval* between this commit and Step 3's
  landing is not recorded the way the analogous FM-M16 window was (CS48 precedent,
  track-8.md:90-97) → finding **CS50**.

No state that previously **threw** now opens (K1/K2/K3 all still throw, same or same-class
exceptions, verified at SchemaShared.java:1370-1375, DatabaseSessionEmbedded.java:2288-2290).
No state that previously opened now throws (K4/K5 still open; healthy DBs parse identically —
the payload written by `create` is the same `toStream` shape every later save rewrites).

**Step 3 information-preservation check (CS35 note):** Step 2 destroyed nothing Step 3 needs.
(a) The genesis-completion marker is a storage-config property with an open-time check in
`YouTrackDBInternalEmbedded` — a mechanism entirely independent of the root payload; neither
file was touched (§0). (b) The W3/W4/W5 loud signatures the marker check will subsume are
unchanged (table above). (c) The `fromStream` breadcrumb branch itself is **retained** in code
(P10) for pre-fix legacy corpses; it is silenced only in the sense that new-code roots never
trigger it. (d) The Step-2 episode's claim to this effect (track-8.md:544-548) is accurate.

## 4. Charter (3) — pre-existing databases

**Verdict: null — both legacy scenarios behave exactly as before the fix.**

- **(a) Old-create root + at least one normal schema save:** every committed schema save routes
  `saveInternal` → `executeInTx(transaction -> toStream(session))` (SchemaShared.java:1530),
  the full write that always writes the root payload (SchemaShared.java:1231-1241 under
  `changedLower == null`). Such a root is byte-shape-identical to what the new `create` writes
  plus its accumulated classes. On open, `fromStream` parses it; `SchemaShared.create` is not on
  the open path at all (only `internalCreate`/genesis calls it, P11). **Unaffected.**
- **(b) Old-create root never saved (broken virgin at rest, e.g. a pre-fix mid-genesis crash
  residue):** on open with the new code, `fromStream` still takes the retained
  `schemaVersion == null` branch (SchemaShared.java:886-894): error log + early return —
  **identical to pre-fix behavior**, loud in exactly the same (log-line) sense. A schema tx
  against it still trips `copyForTx`'s precondition (assert at SchemaShared.java:300-301 in test
  builds; in production shape the copy seeds empty and downstream behavior is the pre-existing
  one). The new code neither silently "heals" such a corpse (create never runs on open; the
  `!hasGlobalProperties` self-heal at SchemaShared.java:1056-1060 is unreachable past the early
  return) nor degrades its detectability. Under Step 3 it will additionally be refused by the
  missing marker. **Unchanged.**

## 5. Charter (4) — WAL/atomic-op composition of storage create

**Verdict: null — zero composition change beyond Step 1's coverage.**

The genesis durable-unit sequence U0…Un is **identical in count, kind, and order** to the
pre-Step-2 sequence; the diff changes only the *content* of U1's single record insert (payload
bytes instead of an empty entity). Specifically: `doCreate` untouched (Step 1's seam,
track-8.md:361); `setSchemaRecordId`/`setIndexMgrRecordId` untouched (P7); the tx-commit
machinery untouched (P4); no new `makeStorageDirty`/`clearStorageDirty` call, no new flush or
fsync, no new atomic operation. `toStream` on the empty schema dirties no record other than the
root (P5), so U1 still contains exactly one record operation. The dirty-flag/W2 window position
is a `doCreate`-internal fact (Step 1 review H9) and is unaffected.

## 6. Alternative-hypothesis check & hypothesis log

| # | Hypothesis | Outcome |
|---|---|---|
| H1 | The payload could land in a different durable unit than the root insert (create-then-update split) | **Rejected.** One tx = one atomic op (FrontendTransactionImpl.java:241; AbstractStorage.java:6624); the root is a single `CREATED` op whose content includes the payload (P2, P5, P6) |
| H2 | A caller with an already-active tx could nest `create()`'s `computeInTx` (txStartCounter > 0), deferring the commit — `setSchemaRecordId` would then persist a *provisional* rid string | **Rejected as unreachable and pre-existing.** Sole production caller is `SharedContext.create:199` via `createMetadata` on a fresh genesis session with no active tx (P11); the `computeInTx` + pointer-write shape predates this diff (parent commit had the identical structure) |
| H3 | `toStream`'s in-tx `session.load(identity)` on a provisional id could bypass the tx and hit storage (or throw on invalid position) | **Rejected.** Tx lookup precedes the validity check (DatabaseSessionEmbedded.java:2239 vs :2288-2290) |
| H4 | The "schema is empty" branch was deleted, destroying the legacy-corpse breadcrumb | **Rejected.** Branch retained (SchemaShared.java:886-895); diff touches only `create()` |
| H5 | A crash mid-commit of U1 could survive as a torn root (clean-flag skip) | **Rejected.** `applyCommitOperations` sets the dirty flag before writing (AbstractStorage.java:2817; DiskStorage.java:611-613); no `clearStorageDirty` follows the schema commit — the clean-flag window is `doCreate`-local (W2, Step 1's) |
| H6 | The persisted `collectionCounter = 0` on a W6 corpse could collide with the storage-born collections on a silent reopen | **Rejected.** Class collections are named `c_<n>` and `nextCollectionName` skips names already in storage (SchemaShared.java:1545-1560); `internal`/`$blob*` occupy a different namespace; the corpse is a design-condemned state regardless |
| H7 | The empty IM root shell chokes reopen (Q-G3 symmetric-fix trigger) | **Rejected empirically and by trace.** `IndexManagerAbstract.load` null-guards `getLinkSet(CONFIG_INDEXES)` (IndexManagerAbstract.java:235-236); pinned by `emptyIndexManagerRootShellToleratesReopenLoad` (GenesisSchemaBootstrapTest.java:104-120) |
| H8 | `computeInTx` retries could double-create roots / leak identities | **Rejected.** No retry loop (DatabaseSessionEmbedded.java:5303-5315) |
| H9 | Pre-write `identity` field assignment leaks a provisional id to concurrent readers | **Rejected for the durable/crash domain.** `getIdentity()` takes the schema read lock (`create` holds the write lock); at genesis the factory monitor spans create (design WI9) so no other session exists. Exception-path residue → CS51 |
| H10 | The new payload write changes what W6's *silent* open does afterwards (e.g. blob registry, counter) vs the pre-Step-2 breadcrumbed open | **Rejected.** Pre-Step-2 the early return left counter 0 / empty sets in memory; post-Step-2 the parse yields the same values from the payload. FM-G5's inert-blob answer carries over |

## 7. Findings

### CS50 — suggestion (docs) — track-8.md `## Surprises & Discoveries`

**The CS35-accepted W6 silent-reopen regression is ARMED in-tree from `908a2374e6` until Step 3
lands, and the armed interval is not recorded.** From this commit onward, a database that
crashes in the K4 window (schema + IM root shells and pointers durable — after
`IndexManagerEmbedded.create:620`'s pointer op — but before `security.create`'s first DDL
self-commit) reopens with **zero signal**: the bootstrap root parses cleanly
(SchemaShared.java:886 branch no longer triggered), the IM shell is null-guard-tolerated
(IndexManagerAbstract.java:235-236), and `SecurityShared.load` even self-mutates
(SecurityShared.java:1078-1101). Pre-Step-2 the same corpse at least emitted the
"Database's schema is empty!" error log. The design accepts this *end state* explicitly
(design-drafts:114-118, §A1 fold 3 :787-789) with Step 3's marker as the replacement — but the
*mid-track exposure interval* is exactly the kind of armed window the Step-1 review recorded for
FM-M16 (CS48 precedent, track-8.md:90-97: "ARMED in-tree from commit 6611cbf6b2 until Step 5's
§A3 fix lands"). The Step-2 episode records the supersession (track-8.md:544-548) but not the
exposure. **Suggested action:** add a Surprises bullet mirroring CS48: "the W6 silent-reopen
window is ARMED from 908a2374e6 until Step 3's genesis-completion marker lands — do not trust a
mid-genesis-crashed DB on this branch to announce itself at open." Dev-only exposure (branch
builds), same class as §A1's accepted pre-marker compatibility note.

*Counterexample gist:* on a build at HEAD, kill the process between `setIndexMgrRecordId`
durability and the first `security.create` class-save; reopen unauthenticated → open succeeds,
no log line, zero classes.

### CS51 — suggestion (code observation; accept-as-is is a valid disposition)

**New exception-path in-memory residue: `identity` retains a dead provisional rid on a failed
create tx.** The field assignment moved *inside* the lambda (SchemaShared.java:1403, required
because `toStream` reads the field at :1126). If `toStream` or the commit throws (E1), the
committed `SchemaShared` instance now holds a non-null, never-persistent `ChangeableRecordId`
where pre-Step-2 it held `null`. Traced consumers: `SchemaShared.load` overwrites the field from
the config pointer before any use (SchemaShared.java:1370); a failed genesis condemns the whole
context (pre-Step-3 §A1 gap; Step 3's cleanup will purge the maps); `getIdentity()` readers
cannot exist at genesis (H9). No durable state is affected — this is not a crash-state defect,
which is why it is not rated higher. **Optional hardening:** null the field in a catch (or
assign a local and copy to the field only for `toStream`'s benefit, resetting on failure) so a
condemned instance is not distinguishable from a virgin one by a dangling id. Fine to disposition
as "superseded by Step 3's cleanup-on-exception."

*Counterexample gist:* inject a commit failure inside `create()`'s tx → `schema.getIdentity()`
returns `#N:-2`-shaped garbage instead of null on the dead context. No on-disk effect.

## 8. Verdict summary per charter item

| Charter | Verdict |
|---|---|
| (1) genesis write-sequence atomicity | **Null defect.** Payload and root creation are one record-insert in one tx atomic op (P2/P4/P5/P6); K0–K5 enumerated exhaustively; every state maps onto the amended W-table (W0–W6) with no new state and no worsened row. The implementer's "no crash window exposes a payload-less root" claim is confirmed |
| (2) reopen contracts / detectability | **Null beyond CS35 scope.** Only K4/W6 loses its (log-line-only) breadcrumb — the design-accepted CS35 trade, replacement marker intact and unimpeded for Step 3; K1–K3 still fail loudly with unchanged signatures. Bookkeeping gap → CS50 (suggestion) |
| (3) pre-existing DBs | **Null.** Saved roots: byte-shape-equivalent, create not on open path. Broken-virgin corpses: the retained `fromStream:886-895` branch fires exactly as before; no silent heal, no regression |
| (4) WAL/atomic-op composition | **Null.** Same durable-unit count, kind, and order as pre-Step-2; only U1's record content differs; nothing outside Step 1's already-reviewed envelope changed |

**Overall: 0 blockers, 0 should-fix, 2 suggestions (CS50 docs, CS51 code observation).**

## 9. Compact findings block

| ID | Severity | Location | Summary | Counterexample gist |
|---|---|---|---|---|
| CS50 | suggestion | `track-8.md` §Surprises & Discoveries (docs) | W6 silent-reopen window is armed in-tree from 908a2374e6 until Step 3's marker; interval not recorded (CS48 precedent) | kill process after IM-pointer durability, before first security DDL self-commit → reopen succeeds with zero signal (pre-fix: error log) |
| CS51 | suggestion | `SchemaShared.java:1403` | Failed create tx leaves `identity` = dead provisional rid (was null pre-fix); benign — overwritten by `load`, superseded by Step 3 cleanup | injected commit failure in `create()` → `getIdentity()` returns a provisional id on the condemned context; no durable effect |
