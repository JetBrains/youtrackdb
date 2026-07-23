# Track 8 — Adversarial pre-implementation design review, concurrency perspective (pass 1)

**Findings prefix:** CN, numbering from CN48.
**Object under review:** `docs/adr/transactional-schema/_workflow/plan/track-8-design-drafts.md`
(Draft G — two-phase genesis bootstrap D18; Draft M — export/import hardening v15), including §0
locked rulings and the 2026-07-23 Rulings section (settled; not re-litigated here).
**Grounding:** HEAD `6913cd321d`, branch `transactional-schema`,
repo `/home/andrii0lomakin/Projects/ytdb/transactional-schema`. Every behavioral claim below was
re-verified by reading the cited code at this HEAD; the draft's citations were re-checked, not
trusted.
**Mode:** read-only. No Maven, no product-code changes.

---

## 0. Decision criteria and method

**Severity vocabulary:** *blocker* — the design as specified is interleaving-unsound in its core
scope and cannot be implemented as written; *should-fix* — a real gap or wrong claim in the design
that must be corrected/pinned before or during decomposition (missing mechanism, wrong grounding,
unpinned provenance that concurrency can exploit); *suggestion* — pre-existing or adjacent hazard
worth a note or opportunistic fix.

**Decision procedure per charter item:** (1) reconstruct the designed sequence from the draft;
(2) trace the real serialization structure in code (locks, monitors, file locks, tx machinery);
(3) enumerate interleavings along the designed sequence exhaustively, marking each **checked**
(counterexample or proof of exclusion) or **out-of-scope** (pre-existing, unchanged by the design,
outside the honored-gap boundary); (4) for each defect give a concrete interleaving traced through
code; for each null verdict give the exclusion proof; (5) run an alternative-hypothesis check
before finalizing.

---

## 1. Verified baseline (re-verification of the draft's grounding)

All of the following draft citations were re-verified at HEAD and found **accurate** unless noted:

| Draft claim | Verified at |
|---|---|
| Genesis chain: `internalCreate:773` → session `internalCreate:572` → `createMetadata:598-603` → `SharedContext.create`; no wrapping tx | `YouTrackDBInternalEmbedded.java:773-777`, `DatabaseSessionEmbedded.java:572-586, 598-603` — `createMetadata` calls `shared.create(this)` directly, no `executeInTx` [confirmed] |
| `SharedContext.create` sequence under `SharedContext.lock` | `SharedContext.java:184-221` (schema/IM/security/function/sequence/scheduler creates, forceSnapshot, O/V/E, blob loop, geospatial, `loaded = true`) [confirmed] |
| Schema root shell: empty entity, `setSchemaRecordId`, no `toStream` | `SchemaShared.java:1387-1398` [confirmed] |
| IM root shell same shape | `IndexManagerEmbedded.java:608-621` [confirmed] |
| `copyForTx` bootstrap assert | `SchemaShared.java:300-301` ("requires a bootstrapped committed schema carrying global properties") [confirmed] |
| Empty-root parse error branch / version gate | `SchemaShared.java:887-894` / `:895-904` [confirmed] |
| Legacy write path: `resolveForWrite` outside tx → committed delegate → `saveInternal` self-commit | `SchemaProxedResource.java:108-113`, `SchemaShared.java:1498-1518` [confirmed] |
| `SecurityShared.create`: guard `:595`, system-DB skip `:614`, `createDefaultRoles:628`, `createDefaultUsers:637` | `SecurityShared.java:593-656` [confirmed] |
| Blob loop: `session.addCollection` (`DatabaseSessionEmbedded:3023` → storage) + `schema.addBlobCollection` (root EMBEDDEDSET, root-diff `:1309`) | `DatabaseSessionEmbedded.java:3023-3029`, `SchemaProxy.java:460-463`, `SchemaShared.java:1305-1311` [confirmed] |
| Blob readers consult schema only | `DatabaseSessionEmbedded.java:5028-5031` [confirmed] |
| `internal` collection created inside storage-create WAL atomic op | `AbstractStorage.java:1493` (`executeInsideAtomicOperation`), `:1510` (`doAddCollection(..., COLLECTION_INTERNAL_NAME)`) [confirmed] |
| Import call site: `security.create(session)` inside `removeDefaultCollections` | `DatabaseImport.java:386-404` (create call is the method's last statement, `:403-404`) [confirmed] |
| Export promote-on-failure, scan swallow, `EXPORTER_VERSION = 14`, no-fsync move | `DatabaseExport.java:150-160` (finally→close), `:212-239`, `:59`; `FileUtils.java:306-320` [confirmed] |
| Import gzip fallback / info reads only `exporter-version` / tolerant section loop | `DatabaseImport.java:133-143`, `:405-424`, `:226-242` [confirmed] |
| Honored gap documentation | `IndexManagerEmbedded.java:787-793` [confirmed] |

**One grounding claim found WRONG** (Draft G / Q-G1 ruling): "the sibling creators have standalone
non-genesis call sites (`FunctionLibraryProxy.java:54`, `SequenceLibraryProxy.java:72` lazily
create their classes on first use)". See **CN49**.

---

## 2. The actual serialization structure (traced)

Facts every verdict below rests on:

- **P1 — factory monitor spans the whole genesis.** `createStorage`
  (`YouTrackDBInternalEmbedded.java:705-771`) wraps `synchronized (this)` around: name checks,
  storage construction, `storages.put(name, storage)` (`:744`), `internalCreate` (`:745` →
  `storage.create` + session + `SharedContext.create`, i.e. **both restructured genesis phases**),
  the `createOps` callback, and the first `callOnCreateListeners()` (`:757`).
- **P2 — every session-minting path takes the same monitor.** `open(name,user,pass[,config])`
  (`:378-400`), `openNoAuthenticate` (`:321-340`), `openNoAuthorization` (`:357-376`),
  `open(AuthenticationInfo,…)` (`:402-427`), `poolOpen` (`:497-509`), `poolOpenNoAuthenticate`
  (`:512-525`); the async/exec helpers (`execute`, `executeNoAuthorizationAsync/Sync`) route
  through those. `exists`, `getStorage`, `listDatabases`, `loadAllDatabases`,
  `forceDatabaseClose`, `checkAndCloseStorages` (auto-close), `internalClose` are all
  `synchronized`. The `storages` / `sharedContexts` maps are private with no unsynchronized
  production readers.
- **P3 — cross-instance/cross-process opens are excluded loudly.** DiskStorage's startup metadata
  takes a `FileLock` on the dirty-flag file at create/open
  (`StorageStartupMetadata.java:79-109`, `lockFile():131-142`, gated by
  `GlobalConfiguration.FILE_LOCK`); an overlapping open fails with "Database is locked by another
  process", it does not read a partial state. (With `FILE_LOCK` disabled this protection is gone —
  pre-existing, unchanged by Track 8.)
- **P4 — the metadata-write mutex is per-SharedContext and fresh at genesis.**
  `SharedContext.java:59` (final field, created with the context); `MetadataWriteMutex.engage`
  probes only the *engaging session's* teardown mark/status (`MetadataWriteMutex.java:151-160`).
- **P5 — the schema-carry commit's freezer gate aborts only on OPERATOR freezes.** Entry probe
  `AbstractStorage.java:2545` (`schemaCarry && isOperatorFreezeActive()`), armed apply
  `startTxCommit:6695-6697`; OPERATOR freezes originate from `freeze()`
  (`AbstractStorage.java:5835-5840`, reached via a session — `DatabaseSessionEmbedded.java:3325`).
  TRANSIENT_QUIESCE freezes (`doSynch:5638-5680`, WAL segment roll `DiskStorage.java:1256-1270`)
  only park commits briefly — including schema commits, by documented design (`doSynch` comment).
- **P6 — the stale-tx monitor started inside `doCreate` (`AbstractStorage.java:1505`,
  `:8808-8823`) only WARNs**; it never aborts a transaction (`StaleTransactionMonitor` javadoc +
  body).
- **P7 — `checkSecurity` is a no-op for a null user** (`DatabaseSessionEmbedded.java:2918-2945`),
  so `openNoAuthenticate`/`openNoAuthorization` yield fully usable sessions on a database with no
  users.
- **P8 — export runs inside ONE transaction** (`DatabaseExport.java:134-141`); the schema section
  reads the immutable snapshot (`:453`); `getImmutableSchemaSnapshot` returns the pinned
  thread-local snapshot when pinned, otherwise a **fresh** `makeSnapshot()` per call
  (`MetadataDefault.java:137-145`).

---

## 3. Interleaving enumeration (per charter item)

### 3.1 Genesis visibility races (charter §1)

Designed sequence: storage create (with R3 blobs) → root shells (schema `computeInTx`, IM
`computeInTxInternal`) → phase-1 schema tx (mutex engaged, schema-carry commit) → phase-2 data tx
→ `loaded = true` → createOps → listeners.

| # | Interleaving | Verdict |
|---|---|---|
| V1 | Another thread opens/creates the same DB via this factory mid-genesis (any of the P2 paths) | **checked — excluded** by P1+P2: every minting path blocks on the monitor until `createStorage` exits; no path reads the maps unsynchronized. Null. |
| V2 | Auto-close / `forceDatabaseClose` / `internalClose` fires mid-genesis | **checked — excluded**: all synchronized on the same monitor (P2). Null. |
| V3 | Second `YouTrackDBInternalEmbedded` (or another process) opens the directory mid-genesis | **checked — excluded loudly** by P3 (dirty-flag FileLock, both in-JVM `OverlappingFileLockException` → throw, and cross-process). With `FILE_LOCK=false`: pre-existing exposure, not changed by Track 8 → out-of-scope. |
| V4 | A DbLifecycleListener in the in-monitor `callOnCreateListeners` (`:757`) re-enters the factory (e.g., opens/creates the system DB) | **checked — safe**: `synchronized` is reentrant on the same thread; no cross-thread hand-off inside listeners was found. Null. |
| V5 | **Failure/crash interleaving**: phase-2 (or phase-1) fails or the process crashes between commits; a later open observes schema-without-security | **checked — DEFECT**: no discard exists; see **CN48**. |
| V6 | Commit-fired `MetadataUpdateListener` callbacks (plan caches, storage-config listener) taking `SharedContext.lock` or the factory monitor → self-deadlock under the restructure's in-lock commits | **checked — excluded**: listeners registered at `SharedContext.init` (`SharedContext.java:104-115`) invalidate caches only; none acquire `SharedContext.lock` or the factory monitor. Null. |

### 3.2 Genesis schema tx vs Track 7 machinery (charter §2)

| # | Interleaving | Verdict |
|---|---|---|
| F1 | Operator freeze active when the phase-1 schema-carry commit runs → loud gate abort of genesis | **checked — excluded**: an OPERATOR freeze needs a session on this DB (P5); none can exist (P1/P2/P3). Null. |
| F2 | Background TRANSIENT_QUIESCE (WAL segment roll `DiskStorage.java:1256`, `doSynch`) overlaps the phase-1 commit | **checked — benign by design**: schema commits park behind transient quiesce exactly like data commits (P5, `doSynch` comment). Null. |
| F3 | `STORAGE_MAKE_FULL_CHECKPOINT_AFTER_CREATE` `synch()` (`AbstractStorage.java:1430-1436`) vs the later genesis commits | **checked — sequential** on the creating thread before metadata creation; the freeze it takes is released before phase 1 begins. Null. |
| F4 | Stale-transaction monitor (started inside `doCreate`, P6) aborts a slow genesis tx | **checked — excluded**: WARN-only. Null. |
| F5 | Mutex handoff window (phase-1 commit releases mutex → phase-2 begins): interleaved DDL/DML from another session | **checked — excluded at genesis** by P1/P2 (no other session can exist); the same window **at the import call site** is reachable but strictly narrower than HEAD's (dozens of self-commits today) → no NEW exposure; the pre-existing exposure is the honored gap's territory. Null (no new defect). |
| F6 | Phase-2 accidentally engages the mutex (violating I-U4's "phase 1 only") | **checked — holds as designed**: `createDefaultRoles`/`createDefaultUsers`/`setSecurityPolicy*`/`addRule`/`save` are record writes; `updateAllFilteredProperties`/`initPredicateSecurityOptimizations` are queries + in-memory maps (`SecurityShared.java:456-500, 628-656, 1209-1245`); `validatePolicyWithIndexes` reads the snapshot only. No schema/index write ⇒ no `ensureTxSchemaState` ⇒ no engage. Null. |
| F7 | `engage()` spuriously rejects the genesis session (teardown/status probes) | **checked — excluded**: genesis session status is OPEN (`internalCreate` sets it, `DatabaseSessionEmbedded.java:578`), no teardown mark, fresh mutex (P4). Null. |
| F8 | Root-shell txs (schema `computeInTx`, IM `computeInTxInternal`) engage the mutex before phase 1 | **checked — they don't**: `newInternalInstance` record writes don't route through the schema proxies; `setSchemaRecordId`/`setIndexMgrRecordId` are storage-config writes. Null (and consistent with I-U4). |

### 3.3 Lazy-creator races (charter §3)

| # | Interleaving | Verdict |
|---|---|---|
| L1 | Lazy creator racing the genesis txs | **checked — excluded**: needs a session mid-genesis; impossible (P1/P2). Null. |
| L2 | Two lazy creators racing each other post-genesis on a fresh DB | **checked — vacuous** post-restructure: OFunction/OSequence exist from phase 1; `init` no-ops (`FunctionLibraryImpl.java:164-173`, `SequenceLibraryImpl.java:~210`). On pre-existing old DBs the race remains — the honored gap itself, not new. Null (no new composition). |
| L3 | The design's inventory of the standalone lazy sites | **checked — DEFECT (grounding)**: the cited sites are dead code; the live ones are elsewhere. See **CN49**. |

### 3.4 Import concurrency (charter §4)

| # | Interleaving | Verdict |
|---|---|---|
| I1 | Restructured `security.create` at `DatabaseImport.java:404` opening its phase-1 tx while another session holds the mutex | **checked — benign**: import parks on `engage()` (unbounded wait + WARN cadence); import holds no lock the mutex-holder could need (its own DDL self-commits release everything) ⇒ no deadlock cycle. Null. |
| I2 | Guard no-op premise at the call site | **checked — holds**: after `removeDefaultNonSecurityClasses` (`:429-484`) + `removeDefaultCollections` (`:386-404`) Identity et al. remain ⇒ `getClasses()` non-empty ⇒ `create` returns null tx-free (`SecurityShared.java:594-596`), matching the design. Null. |
| I3 | Tx-nesting of the phase-1 tx at the call site | **checked — no active tx there**: `importDatabase`'s section loop (`:226-242`) and both `removeDefaultCollections` callers (`importSchema:495-498`, `importCollections:847-849`) run outside any `executeInTx`; the phase-1 tx will be top-level. The design's Track 4 nesting belt is a defensible extra, not load-bearing. Null. |
| I4 | Phase-1→phase-2 window at the import site: concurrent session inserts a user named `admin` before phase 2 → UNIQUE conflict → import fails midway | **checked — pre-existing and narrower than HEAD** (HEAD's window spans dozens of self-commits); no new exposure. Out-of-scope (honored gap). |
| I5 | `skipRoleHasPredicateSecurityForClassUpdate` (`SecurityShared.java:87`, read at `:1210`) — non-volatile shared flag spanning both phases while concurrent sessions run role ops at the import site | **checked — pre-existing, window comparable to HEAD** (the flag already spans the whole create today). Out-of-scope. |
| I6 | v15 manifest verification vs concurrent sessions mutating the target DB during import | **checked — DEFECT (unpinned provenance)**: see **CN51**. |
| I7 | Import DDL (drops/creates) racing other sessions' schema txs | out-of-scope by declared design (legacy path, honored gap, removal in upcoming PR). |

### 3.5 System-DB path (charter §5)

| # | Interleaving | Verdict |
|---|---|---|
| S1 | System-DB genesis rides `createStorage` under the monitor; concurrent `openSystemDatabaseSession` blocks | **checked** — same exclusion as V1. Null. |
| S2 | Concurrent first-touch `init()` double-create race; loser throws; window for a system-DB session before `createSystemRoles` | **checked — pre-existing defect, unchanged by Track 8**: see **CN53** (suggestion). |
| S3 | Phase-2-empty for system DB + post-create `createSystemRoles` (data-only, verified `DefaultSecuritySystem.java:123-160`) | **checked — composes correctly** with the restructure (roles insert into phase-1 classes). Null. |

### 3.6 Export concurrency (Draft M)

| # | Interleaving | Verdict |
|---|---|---|
| E1 | Concurrent record commits during the export tx | **checked — isolated** by the export's single tx snapshot (P8). Null. |
| E2 | Concurrent DDL during export vs the new manifest's class/index counts | **checked — DEFECT (unpinned provenance)**: see **CN51**. |
| E3 | Two concurrent exports to the same target path | **checked — DEFECT**: fixed `.tmp` name defeats the completion-flag gate; see **CN52**. |
| E4 | Export vs operator freeze/backup | **checked — pre-existing**, unchanged by Draft M (export is reads + file I/O). Out-of-scope. |

---

## 4. Findings

### CN48 — should-fix — FM-G3's "failed create → DB is discarded, not reopened" is false at HEAD; the design specifies no discard mechanism and its footprint omits the file that would host one

**Design §:** Draft G §G.3 FM-G3, §G.5 test 9, §G.6 footprint.
**Code:** `YouTrackDBInternalEmbedded.java:744-755` (no cleanup on failure), `:779-787`
(`getOrCreateSharedContext` leaves the context mapped), `DiskStorage.java:798-822` (`exists` keys
off `dirty.fl`/config files written by `storage.create`), `DatabaseSessionEmbedded.java:2918-2945`
(P7).

**Premises.**
1. `createStorage` puts the storage into `storages` at `:744` *before* `internalCreate` (`:745`);
   the `catch (Exception e)` at `:750-755` wraps and rethrows **without removing** the storage
   from `storages`, without removing the SharedContext from `sharedContexts`, and without closing
   or deleting the storage. No caller performs cleanup either (`YouTrackDBImpl.create` overloads
   delegate straight through; verified no catch-and-drop).
2. After a successful `storage.create`, `exists(name)` is true (storage in map → `storage.exists()`;
   or on disk, `DiskStorage.exists` sees `dirty.fl`).
3. `open`/`openNoAuthenticate`/`openNoAuthorization` on that name find the mapped storage,
   `storage.open()` no-ops on an already-OPEN storage ("THIS OPEN THE STORAGE ONLY THE FIRST
   TIME", `:430-446`), and `SharedContext.load` runs (loaded=false after a failed create) — it
   loads the committed phase-1 schema successfully (G2.a makes the root bootstrap-valid; classes
   incl. OUser exist, engines built at the phase-1 commit).
4. By P7, unauthenticated open paths (used by `drop()`, `execute(db,user,task)`,
   `executeNoAuthorization*`) yield a **fully usable session** on the schema-without-security
   database. Authenticated opens fail (no users) — the DB is present but permanently
   admin-less.

**Counterexample interleaving (exception case).** T1: `create("db")` → storage create OK → root
shells committed → phase-1 tx commits (OUser class + built `OUser.name` engine) → phase-2 tx
fails (any injected failure) → exception propagates out of `createStorage`; maps retain the
storage+context. T1 (or any thread, later): `openNoAuthenticate("db", "admin")` → monitor free →
mapped storage found → session minted on the phase-1-only DB. The design's claimed protocol ("the
DB is discarded, not reopened") never runs — there is nothing that runs it.

**Crash case.** Process dies between phase-1 and phase-2 commits (both are durable commits — the
two-phase design *creates* this crisp durable state). On restart, `exists()` is true, `open`
loads the schema-without-users DB. No genesis-completion marker exists; nothing distinguishes a
half-genesis DB from a healthy user DB whose users were dropped.

**Why should-fix, not blocker.** At HEAD the same non-cleanup exists with *more* partial states
(dozens of self-commits), so Track 8 does not regress; but the design (a) asserts a protocol that
does not exist, (b) pins a test (G.5 #9) that will go red with no designed mechanism to green it,
and (c) omits `YouTrackDBInternalEmbedded.java` from the G.6 footprint although the failure path
lives there. The design must specify: on create failure — remove from `storages`/`sharedContexts`,
close (and for disk, delete or mark) the storage; for the crash case — either a
genesis-completion marker checked at open, or an explicit documented recovery ruling
(drop-and-recreate), and the footprint updated. Also: FM-G3's "three coarse states" undercounts —
the root-shell `computeInTx`/`computeInTxInternal` commits precede phase 1, so there are four
durable intermediate states (storage-only / roots-only / phase-1 / complete).

### CN49 — should-fix — the "standalone lazy creator sites" cited by Draft G and the Q-G1 ruling are dead code; the live lazy-creation sites are elsewhere and stay unaccounted

**Design §:** Draft G §G.0 (scope boundary), Q-G1 + its 2026-07-23 ruling ("standalone lazy
creator call sites (`FunctionLibraryProxy.java:54`, `SequenceLibraryProxy.java:72`) stay on the
legacy top-level path until that path's removal").
**Code:** `FunctionLibraryProxy.java:52-55`, `SequenceLibraryProxy.java:70-73` — the `create()`
proxy methods have **zero production callers** (repo-wide grep: only
`SequenceLibraryProxyTest.java:207-208`). The **live** first-use lazy creators are:
`FunctionLibraryImpl.createFunction:137-139` → `init:164-185` (legacy `createClass("OFunction")`,
`createProperty`, `createIndex(UNIQUE)`, **plus** an index-repair arm
`if (prop.getAllIndexes().isEmpty()) prop.createIndex(...)` that fires on any DB missing the
name index) and `SequenceLibraryImpl.createSequence:103-123` → `init` (legacy
`createClass(DBSequence.CLASS_NAME)`, `SequenceLibraryImpl.java:~210-216`).

**Why it matters (concurrency accounting, not the gap itself).** The ruling's decision —
standalone sites stay legacy until the legacy path's removal — is right in substance for the real
sites too (they are equally legacy, self-committing, mutex-bypassing). But the design's inventory
of remaining legacy top-level DDL consumers after genesis is wrong: a reader of the track file
(or the author of the upcoming legacy-path-removal PR) who removes/adapts the proxies' `create()`
believes the lazy creators are handled while `createFunction`/`createSequence` `init()` keeps
racing. Concrete residual interleaving (on a pre-OFunction database): sessions A and B both call
`createFunction` → `FunctionLibraryImpl.createFunction` is `synchronized` per SharedContext, so
A and B serialize per storage, but the DDL inside `init` self-commits without the mutex and races
any concurrent schema transaction on the same DB — at a site the design does not name.
**Fix:** re-anchor the design text and the Q-G1 ruling record on
`FunctionLibraryImpl.createFunction→init` and `SequenceLibraryImpl.createSequence→init` (and note
the index-repair arm); note that the proxy `create()` methods are test-only surface.

### CN50 — should-fix — R3 register-only blob loop double-reads `STORAGE_BLOB_COLLECTIONS_COUNT` across a mutable-global fallback, contradicting the design's own "frozen at create" semantics

**Design §:** Draft G §G2.b.
**Code:** design adds a read in `AbstractStorage.doCreate` (next to `:1510`) from the
`contextConfiguration` parameter; the register loop in `SharedContext.create` (today
`SharedContext.java:198-204`) reads `storage.getContextConfiguration()` —
`CollectionBasedStorageConfiguration.create:184-210` stores the **same** `ContextConfiguration`
instance (`this.configuration = contextConfiguration`, `:192`) and `getContextConfiguration:432-440`
returns it; but `ContextConfiguration.getValue:90-96` **falls back to the process-global mutable
`GlobalConfiguration` value** whenever the key is absent from the context map (the common case —
nobody sets this key per-DB).

**Counterexample interleaving.** T1 creates a DB: `doCreate` reads count=8 → creates
`$blob0..7` inside the storage atomic op. T2 (a test harness, an operator console, another DB's
config path) calls `GlobalConfiguration.STORAGE_BLOB_COLLECTIONS_COUNT.setValue(10)`. T1 proceeds
to `SharedContext.create`'s register loop, re-reads count=10 → for i=8,9
`getCollectionIdByName("$blob8")` returns -1 → `schema.addBlobCollection(session, -1)` registers a
bogus id in the schema root (or throws mid-genesis, feeding CN48's failure path). Downward drift
leaves physical collections unregistered and silently shifts the frozen-at-birth property.
The design *states* "`STORAGE_BLOB_COLLECTIONS_COUNT` becomes a storage-birth property frozen at
create" — the double-read implementation shape it sketches does not implement that statement.
**Fix (pin in the design):** exactly one read. Either (a) the register loop enumerates the actual
`$blob*` collections present in the storage by name and registers those ids, or (b) `doCreate`
persists the birth count as a storage-config property and the register loop reads it back.

### CN51 — should-fix — Draft M's manifest counts have no pinned provenance; under concurrent DDL (export side) or concurrent sessions (import side) re-derived counts produce false fail-closed rejections of good dumps

**Design §:** Draft M §M2.a-5 (manifest content), §M2.b-3 (manifest verify), M.4
(I-migration-fail-closed).
**Code:** `DatabaseExport.exportDatabase:134-141` — the sections run inside ONE tx;
`exportSchema:449-453` reads `getImmutableSchemaSnapshot`; `MetadataDefault.java:137-145` — the
snapshot is pinned only while a pin is held; unpinned calls mint a **fresh** snapshot.
`DatabaseImport.importDatabase` runs on an ordinary session with **no exclusivity** — other
sessions commit freely during import (nothing in `DatabaseImport.java` or the factory excludes
them).

**Counterexample (export side).** Export tx writes the schema section from the pinned snapshot
(class X absent). Concurrent session creates class X and commits. Export tx ends (pin released);
the manifest — written last, plausibly in/near `close()` per M2.a-4/5 — derives its class count
from a *fresh* snapshot (X present) → manifest says N+1, schema section carries N → the strict
v15 import rejects a dump that was produced successfully. **Counterexample (import side).** The
v15 import finishes its records; a concurrent session inserts 100 records into an imported class;
if "what was imported" is verified by querying the target DB rather than by importer-tracked
counters, the count mismatches → hard failure AFTER the DB was fully mutated.
**Fix (pin in the design):** both sides count what they themselves wrote/read — exporter
increments per-section counters as it writes (never re-derives at manifest time), importer
verifies manifest counts against its own tallies (never against target-DB queries). Secondary
clause to make explicit: the manifest-count verify is necessarily a **post-mutation** rejection;
the Q-M2 ruling's "all rejections throw BEFORE any mutation" holds for info-field validation
only, and the design text should say so to keep I-migration-isolation honest.

### CN52 — should-fix — fixed `.tmp` name lets two concurrent exporters of the same path defeat the completion-flag promote gate and replace a good dump with corrupt bytes, both reporting success

**Design §:** Draft M §M2.a-4/5 (completion-flag-gated promote, fsync-before-rename).
**Code:** `DatabaseExport.java:87` — `tempFileName = fileName + ".tmp"` (deterministic); `:88` —
`prepareForFileCreationOrReplacement` deletes an existing tmp; `:91` — plain
`new FileOutputStream(tempFileName)` (truncating, non-exclusive); `close():288-301` — rename.

**Counterexample interleaving.** Two scheduled backups overlap (cron drift): E1 starts writing
`dump.gz.tmp`; E2 starts, deletes/truncates the same path, both keep writing through independent
FDs at independent offsets → the single file is interleaved garbage. Each exporter writes all its
sections and its trailing manifest without error → **each legitimately sets the new completion
flag** → each fsyncs and renames; the final name now holds corrupt bytes, and the previous good
dump that lived there is gone. Both exporters report success. The v15 strict import catches the
corruption — but only at restore time, when the good artifact has already been destroyed. The
completion flag's stated guarantee ("flag set ⇒ dump complete") is void under this interleaving.
**Fix (one line, pin in the design):** per-export unique temp name (UUID/pid-suffixed) opened with
`CREATE_NEW`, so concurrent exporters produce independent temp files and the last atomic rename
publishes one *internally consistent* dump.

### CN53 — suggestion — pre-existing: concurrent first-touch of the system DB makes the losing initializer throw, and a session can open the system DB before `createSystemRoles` completes

**Design §:** charter item 5 / Draft G G2.c (system-DB skip), G.5 test 6.
**Code:** `SystemDatabase.openSystemDatabaseSession:58-65` — `if (!exists()) { init(); }` is
unsynchronized; `init():91-111` re-checks `exists()` then calls
`context.create(SYSTEM_DB_NAME, …)` with `failIfExists=true`
(`YouTrackDBInternalEmbedded.create:663-666` → `createStorage`, throw at `:760-762`); the
post-create `createSystemRoles` session (`:105-108`) runs **outside** the factory monitor.

**Interleavings.** (a) T1 and T2 both evaluate `exists()`=false pre-monitor → both call
`context.create` → T2 blocks on the monitor, then hits the in-monitor `exists` re-check → true →
`failIfExists=true` → T2's `openSystemDatabaseSession` throws "already exists" instead of opening.
(b) T2 opens the system DB after T1's create returns but before T1's `createSystemRoles` commits →
T2 sees a system DB without root/guest/monitor roles. Both are **pre-existing and unchanged by
Track 8** (the restructure keeps everything inside the same monitor; phase-2-empty for the system
DB composes correctly with `createSystemRoles`, which is data-only —
`DefaultSecuritySystem.java:123-160`). Logged because the design's system-DB test (G.5 #6) should
avoid parallel first-touch, and because a one-line fix (synchronize `init`, or
`failIfExists=false`) could ride the genesis unit opportunistically.

---

## 5. Null verdicts (summary)

- **No genesis visibility race exists through the factory** (V1-V4, V6): the factory monitor
  spans storage create + both phases + createOps (P1), and every session-minting or map-reading
  path takes the same monitor (P2). Cross-instance/process observation is excluded loudly by the
  dirty-flag FileLock (P3). No unsynchronized reader of `storages`/`sharedContexts` exists.
- **No freezer-gate/checkpoint/backup interaction can disturb genesis** (F1-F4): OPERATOR freezes
  need a session (impossible mid-genesis); TRANSIENT freezes park schema commits by documented
  design; the stale-tx monitor is WARN-only.
- **The phase-1→phase-2 handoff window is unexploitable at genesis** (F5) — no session can exist
  to exploit it; at the import call site the window exists but is strictly narrower than HEAD's
  and belongs to the honored gap.
- **I-U4's "mutex in phase 1 only" is implementable as designed** (F6-F8): phase-2 bodies are
  verified data-only; the root-shell txs don't route through the schema proxies.
- **Lazy creators cannot race genesis** (L1) and are vacuous on fresh DBs post-restructure (L2);
  the only defect is the misidentified inventory (CN49).
- **The import call-site premises hold** (I1-I3): guard-first no-op verified; no active tx at the
  call site; mutex parking is deadlock-free (import holds nothing a mutex-holder needs).
- **Export record reads are tx-snapshot-isolated** (E1); the only Draft M concurrency defects are
  the unpinned count provenance (CN51) and the fixed temp name (CN52).
- **No blocker was found**: the core two-phase shape is sound because the entire genesis is
  single-threaded under the factory monitor and both phases stay inside
  `SharedContext.create`.

## 6. Alternative-hypothesis log

- *CN48 alt:* "some caller cleans up a failed create" — checked `YouTrackDBImpl.create` overloads
  (pure delegation) and `AbstractStorage.create`'s own catch (`closeIfPossible` only for
  storage-create-step failures, and it never touches the factory maps). Rejected — the residue
  persists. Also checked whether `open()` would fail loudly on the residue: it does not
  (P7 + `storage.open` first-time-only semantics).
- *CN49 alt:* "proxy.create() has callers via the `FunctionLibrary`/`SequenceLibrary` interfaces
  or server/tools modules" — repo-wide grep over `core/src`, `server/src`, `tools`: only
  `SequenceLibraryProxyTest`. Rejected.
- *CN50 alt:* "both reads hit an immutable per-DB config" — traced
  `CollectionBasedStorageConfiguration.create:192` (same instance) and
  `ContextConfiguration.getValue:90-96` (global fallback). The fallback is live for unset keys;
  hypothesis rejected.
- *CN51 alt:* "the export tx pin makes any manifest-count derivation safe" — only while the pin
  is held; the design places the manifest write after the sections and does not state it stays
  inside the tx/pin; `getImmutableSchemaSnapshot` mints fresh snapshots unpinned
  (`MetadataDefault.java:137-145`). Hypothesis insufficient — pin required in the design text.
- *CN52 alt:* "the strict import matrix makes the corrupt promote harmless" — it detects, but
  only at restore time, after the good artifact at the final name was replaced; the design's own
  promote-gate story claims more than that. Rejected as a reason to drop the finding.
- *General alt for the null verdicts:* hunted for any unsynchronized production reader of the
  factory maps and for any session-minting path outside the monitor (including gremlin/
  YTDBGraphFactory, pools, SystemDatabase, server execute helpers) — none found.

## 7. Observations (non-findings, for the implementer's awareness)

- `createStorage` invokes `callOnCreateListeners()` **twice** — once inside the monitor
  (`YouTrackDBInternalEmbedded.java:757`) and once after it (`:770`) — so DB lifecycle `onCreate`
  fires twice per create at HEAD. Same-thread sequential, so not a concurrency defect, but genesis
  tests counting listener invocations will see 2, and the genesis session created by
  `newCreateSessionInstance` is never closed by `createStorage`. Pre-existing; outside the Track 8
  footprint.
- The G2.c narrative "three coarse states" (FM-G3) is four once the root-shell txs are counted
  (see CN48 tail) — worth correcting when the FM-G3 row is amended.
