# Track 8 Design Drafts — Genesis bootstrap (D18) + schema-format migration (D20)

**Status:** design-phase artifact for the mandatory user design review. Not implementation.
**Grounded at HEAD `d664589d7f`.** All line numbers cite HEAD. Verified-by-reading claims are
marked **[verified]**; anything reachability- or timing-dependent that could not be proven without
running code is marked **[inferred]**. This document reproduces the two-draft design report in
full: Draft G = two-phase genesis bootstrap; Draft M = fail-closed export/import schema-format
migration. Grounding sources: the Track 8 recon (episode t60.e1) and the blob-collection
storage-embedding feasibility investigation (episode t60.e2).

---

## 0. User rulings already locked (2026-07-23) — settled inputs, not open for re-litigation

These four rulings were issued by the user before drafting and are baked into the drafts below as
settled design. The drafts elaborate their consequences; the Open Questions section at the end does
NOT re-ask them.

- **R1 — Migration-path detection: v15-only strictness.** The strict fail-closed import checks key
  off the dump's declared exporter version being **>= 15**. There is no separate migration
  flag/mode switch. A dump declaring `exporter-version < 15` keeps today's lenient general-import
  behavior (including the plain-JSON fallback); a dump declaring `>= 15` gets the full strict
  matrix (manifest verify, section presence, gzip full-consumption, non-gzip rejection,
  best-effort ack gate, info-field validation). *(Refined 2026-07-23, pass-1 WC3: the Q-M2 ruling
  splits the `>= 15` arm — the strict matrix = v15 exactly, `>= 16` short-circuits to
  reject-with-redirect — and SR2 rejects an UNDECLARED exporter-version fail-closed.)*
- **R2 — Manifest: in-dump trailing section.** The manifest (class/index/record counts) is a
  trailing JSON section **inside the dump**, not a sidecar file. Consequence: the frozen plan's
  "manifest written last and atomically (temp + fsync + rename)" discipline transfers to the dump
  file itself — the manifest section is written strictly last, then the dump is fsynced, then
  promoted by rename; the completion flag gates the promote (Draft M §M.2).
- **R3 — Blob collections: EMBEDDED into storage creation.** `AbstractStorage.doCreate` creates the
  `$blob<i>` collections inside the same WAL atomic operation that already creates the `internal`
  collection (`AbstractStorage.java:1510` precedent). `SharedContext.create`'s blob loop
  (`SharedContext.java:198-204`) becomes resolve-by-name + schema-register-only — a pure
  root-payload write that rides the genesis schema tx. **Caveat carried on the record:** this
  renumbers collection ids in every fresh database (blobs move from the highest slots to `1..N`;
  class collections shift up by N). All production lookups are name/schema-dynamic **[verified —
  t60.e2]**, but test fixtures, stored dumps, or tests pinning fresh-DB collection ids may break.
  **[Corrected 2026-07-23, pass-1 WI1: one production exception exists —
  `DatabaseImport.importSchema`'s blob-collection mapping resolves raw dump ids in the target id
  space (`DatabaseImport.java:528-541`); Draft M owns the fix, see Amendments §A3.]**
  **De-risking requirement:** an early, targeted test sweep for id-pinning fixtures is a mandatory
  first-step activity of the genesis unit, before the restructure lands.
- **R4 — v15 import validation of info fields.** A v15 dump's `schema-version` and sibling info
  fields are validated fail-closed on import (they are silently ignored today,
  `DatabaseImport.importInfo:409-421` reads only `exporter-version`). The exact validation matrix
  is Q-M2 below (the *that* is ruled; the *what-exactly* needs one ruling).

### Scope boundary carried on the record

- **The top-level-DDL mutex-bypass gap is HONORED, NOT OWNED.** Legacy non-transactional DDL paths
  (including `DatabaseImport.importSchema`'s class/property/index creation, which runs outside any
  transaction) bypass the metadata-write mutex — documented at `IndexManagerEmbedded.java:787-791`
  and honored as out of scope by Track 7 (`_workflow/track-7-design-drafts.md` §0 — one directory
  above this file, not a `plan/` sibling; pass-1 WC5b). Draft G removes the
  single largest legacy top-level DDL consumer (genesis) but does not close the gap; Draft M's
  import keeps its DDL on the legacy path deliberately (consistent with the gap's planned removal
  in an upcoming PR). ~~**YouTrack ID for the gap: pending from the user** (not present anywhere in
  the repo; see Open Questions).~~ **RESOLVED 2026-07-23: MOOT** — the legacy top-level DDL path
  is removed in an upcoming PR (user ruling, see Rulings §Admin); no YouTrack ID will be cited.

---

# DRAFT G — Genesis bootstrap (D18)

## G.0 The machinery as it exists at HEAD (baseline facts)

| Piece | Where | Behavior |
|---|---|---|
| Genesis entry | `YouTrackDBInternalEmbedded.internalCreate:773` → `DatabaseSessionEmbedded.internalCreate:572` → `createMetadata:598-603` → `SharedContext.create:184-221` | storage → session → metadata creation chain; **no wrapping transaction** [verified] |
| Genesis sequence | `SharedContext.create:184-221` | `schema.create` → `indexManager.create` → `security.create` → `FunctionLibraryImpl.create` → `SequenceLibraryImpl.create` → `SchedulerImpl.create` → `forceSnapshot` → O/V/E classes → blob loop → geospatial listener, all under `SharedContext.lock` |
| Schema root shell | `SchemaShared.create:1387-1398` | `computeInTx(newInternalInstance)` — an **empty entity** (no `schemaVersion`, no `globalProperties`); sets `setSchemaRecordId`; never calls `toStream` |
| IM root shell | `IndexManagerEmbedded.create:608-621` | same shape: empty internal entity, `setIndexMgrRecordId` |
| Legacy DDL path | `SchemaProxedResource.resolveForWrite:108-117` | outside a tx returns the committed delegate → per-op self-commit via `SchemaShared.saveInternal:1498-1518` (`executeInTx(toStream)`); **genesis today = dozens of self-commits** [verified] |
| Tx seed precondition | `SchemaShared.copyForTx:278-320`, assert at `:300-301` | "copyForTx requires a bootstrapped committed schema carrying global properties" — seeds by re-parsing the committed root under `computeWithFreshCommittedReads` |
| Empty-root parse | `SchemaShared.fromStream:887-894` | `schemaVersion == null` → error-log "Database's schema is empty!" and return (production); the `:300` assert fires first in test builds |
| Security creator | `SecurityShared.create:594-626` | no-op guard `getClasses().isEmpty()` at `:595`; DDL (Identity, OSecurityPolicy, ORole, OUser + `OUser.name` UNIQUE at `:899`); system-DB skip of roles/users at `:614`; `createDefaultRoles:628` (own `executeInTx`), `createDefaultUsers:637` (own `computeInTx`, honors `CREATE_DEFAULT_USERS`) |
| User lookups | `SecurityShared.getUserInternal:1103-1117`, `getUserRID:1177-1190` | planner queries (`select from OUser where name = ?`) — the built-engine dependency is UNIQUE enforcement on insert + planner acceleration, not a literal direct `index.get` [verified; plan wording stale] |
| Blob loop | `SharedContext.create:198-204` | `session.addCollection("$blob"+i)` (`DatabaseSessionEmbedded:3023` → `storage.addCollection:1668`, direct structural op, own atomic op) + `schema.addBlobCollection` (root-payload `blobCollections` EMBEDDEDSET, `SchemaShared.java:1243-1244`, root-diff at `:1309`) |
| Blob readers | `DatabaseSessionEmbedded.getBlobCollectionIds:5028` → schema; `ResultInternal.java:780`; `DatabaseExport.java:456` | the schema root's set is the authoritative registry — storage is never consulted [verified] |
| Storage-created collection precedent | `AbstractStorage.doCreate:1442-1527`, `:1510` | the `internal` collection is created inside the storage-create WAL atomic op (`executeInsideAtomicOperation:1493`); shared by `DiskStorage` and `DirectMemoryStorage` |
| Import call site | `DatabaseImport.removeDefaultCollections:386-404` | calls `security.create(session)` at `:404` mid-import (validation disabled, user null) |
| Two-phase enablers | Track 4 commit reconciliation; Track 5 commit-time index build + D21 tx-aware snapshot; Track 3/7 mutex lifecycle | all landed; genesis "no contention at genesis" premise holds |

## G.1 Scope

**In:** the G1 bootstrap-valid root payload; the R3 storage-embedded blob collections + the
register-only rewrite of the blob loop; the two-phase restructure of `SharedContext.create` /
`SecurityShared.create` and the sibling creators (function, sequence, scheduler, O/V/E classes);
preservation of the `DatabaseImport.java:404` call-site semantics; the id-renumbering de-risk test
sweep; genesis tests. **Out:** the commit machinery (Track 4), the index build internals (Track 5),
the top-level-DDL mutex gap (honored, not owned — §0), cross-format migration (Draft M).

## G.2 Design narrative

### G2.a — G1: bootstrap-valid empty-schema root (MANDATORY enabler)

The frozen plan's premise "the first-ever schema transaction seeds the tx-local copy from the empty
committed schema" is **contradicted at HEAD**: `copyForTx` requires a root record carrying
`globalProperties` (`SchemaShared.java:300-301`), but `SchemaShared.create` persists an empty
entity (`:1387-1398`). The restructured genesis's first DDL would trip the assert (test builds) or
fall into `fromStream`'s "schema is empty" error branch (`:887-894`) **[verified]**.

**Design:** `SchemaShared.create` persists a bootstrap-valid empty-schema payload — the same shape
`toStream` writes for an empty schema (`schemaVersion` = `CURRENT_VERSION_NUMBER` (6), empty
`classes` link set, empty `globalProperties`, `collectionCounter` = 0, empty `blobCollections`) —
so `copyForTx`'s precondition holds from the instant the root exists, and any schema transaction
(genesis or user) opened against a virgin database seeds cleanly. Preferred mechanism: route the
initial write through `toStream` itself (single serializer, no hand-rolled twin format to drift);
the write stays inside `create`'s existing `computeInTx`. The IM root shell has the same
empty-entity shape; whether it needs a symmetric fix is a verify-first item (Q-G3).

**Completeness signal (pass-1 CS35, folded into the §A1 containment):** the bootstrap payload
silences the only open-time breadcrumb an empty root produces today (`fromStream:887-894` no
longer fires), so G2.a alone would *worsen* post-crash diagnosability. The replacement signal is
the open-time genesis-completion check (Amendments §A1): a half-genesis database refuses the open
loudly instead of error-logging — strictly stronger than the breadcrumb it replaces.

**Design-final hand-off (pass-1 WC4):** design.md:504-506's clause "the schema transaction …
writes the first schema record" is superseded in letter — the bootstrap root pre-exists the
genesis tx, which writes the first *per-class* records and rewrites the root. Reconciled in the
Phase-4 `design-final.md`, not here.

### G2.b — R3: blob collections embedded into storage creation

Per ruling R3, `AbstractStorage.doCreate` gains a loop next to the `internal` create
(`:1510`): `doAddCollection(atomicOperation, "$blob" + i)` for
`i < contextConfiguration.getValueAsInteger(STORAGE_BLOB_COLLECTIONS_COUNT)` — same atomic
operation, so the blob collections are **atomic with storage birth** and WAL-reverted on a crashed
create (`doCreateCollection` javadoc contract, `AbstractStorage.java:7040-7044`) **[verified
mechanism; new call is the only code change]**. Both storage profiles inherit it (`doCreate` is
shared). Collection ids become: `internal` = 0, `$blob0..N-1` = 1..N, class collections from N+1.

`SharedContext.create`'s blob loop becomes register-only: enumerate the storage's **actual**
`$blob*` collections by name and call `schema.addBlobCollection(...)` for each resolved id — which
routes through `resolveForWrite()` (`SchemaProxy.java:460-462`), i.e. **inside the genesis schema
tx it is a pure tx-local root-payload write** picked up by the commit's root-diff
(`SchemaShared.java:1309`). The awkward direct-storage-op-inside-metadata-creation disappears; the
genesis schema tx contains only schema writes.

Documented semantic consequence: `STORAGE_BLOB_COLLECTIONS_COUNT` becomes a storage-birth property
frozen at create (it is already effectively read-once-at-genesis today). The id-renumbering caveat
and its mandatory early test sweep are on the record in §0/R3.

**Single-read pin (pass-1 CN50):** the count is read exactly ONCE, in `doCreate`, from the
create-time `contextConfiguration`. The register loop performs NO second config read — a second
read would route through `ContextConfiguration.getValue`'s process-global mutable fallback
(`ContextConfiguration.java:90-96`) and could observe a different value than storage birth did
(registering bogus ids, or leaving physical blobs unregistered). Enumerating the actual `$blob*`
collections by name implements the frozen-at-birth semantic literally.

### G2.c — Two-phase genesis restructure

**Phase 1 — the schema transaction.** `SharedContext.create` wraps the DDL portion in one
transaction: the `SecurityShared.create` DDL half (Identity, OSecurityPolicy, ORole, OUser +
indexes), the sibling creators' DDL (OFunction, OSequence, OSchedule classes/properties/indexes),
the O/V/E base classes, and the blob-collection registration (G2.b). Inside the tx every mutation
routes tier-3 through `resolveForWrite()` → `ensureTxSchemaState()` — the transaction **engages the
metadata-write mutex** on its first write (no contention at genesis) and commits through the
schema-carry path: per-class records + root payload written, `OUser.name` and the other engines
**built at commit** (Track 5). The tx granularity (one tx vs per-creator) is Q-G1.

**Phase 2 — the data transaction.** After phase 1 commits, the default roles and users are inserted
into the now-committed classes (today's `createDefaultRoles`/`createDefaultUsers` bodies,
`SecurityShared.java:628-656`). Phase 2 never touches schema, so it **does not engage the mutex**
(I-U4's second half). UNIQUE enforcement on the user inserts resolves against the real, built
`OUser.name` engine — the positive acceptance property: **the `OUser.name` engine exists and is
built before the first user insert**, asserted directly in tests (not via the counterfactual
"unified tx throws", which post-Track-5 scan-fallback machinery may no longer reproduce
**[inferred]**). Whether phase 2 is one tx or keeps today's two is Q-G2.

**Preserved behaviors:** the `getClasses().isEmpty()` no-op guard stays **first and tx-free**
(`SecurityShared.java:595`) so the import call site (`DatabaseImport.java:404`) and any repeat call
remain a cheap no-op when classes exist; the system-DB skip (`:614`) keeps phase 2 empty for the
system database; the geospatial lifecycle listener stays outside the schema tx (lucene module
excluded from the build — no-op in practice); `initPredicateSecurityOptimizations` runs after
phase 2 as today. The now-redundant mid-create `schema.forceSnapshot()` calls are removed — the
schema-carry commit owns the single trailing `forceSnapshot` (Track 4).

**Import call-site compatibility (MANDATORY):** `DatabaseImport.removeDefaultCollections:404`
invokes the restructured `security.create` mid-import with validation disabled and `user = null`.
The restructured create must work there unchanged: guard-first (usually no-op), and when it does
run, its phase-1 schema tx nests correctly inside the import flow — Track 4's link-consistency
save/restore (track-4.md:378) exists precisely for this nesting and must not regress.

**Lock interaction (pass-1 WI9):** the phase-1 commit runs while `SharedContext.create` holds
`SharedContext.lock`, so the four-lock commit order (mutex → `SchemaShared.lock` → IM lock →
`stateLock.writeLock`) nests entirely inside that lock. Safe by construction at genesis: the
factory monitor spans the whole create (concurrency report P1/P2), so no other session exists to
form a cycle. The import-nested call site differs precisely in NOT holding `SharedContext.lock`,
so no new lock-order edge appears there either.

**Failure containment (pass-1 CS34+CN48):** genesis exception/crash containment —
cleanup-on-exception in `YouTrackDBInternalEmbedded.createStorage` plus the open-time
genesis-completion marker — is specified in Amendments §A1; FM-G3/FM-G5, pin G.5 #9, and the G.6
footprint are amended accordingly.

## G.3 Failure-mode analysis

| # | Scenario | HEAD verdict / design answer |
|---|---|---|
| FM-G1 | First schema tx on a virgin DB trips the `copyForTx` bootstrap assert (`SchemaShared.java:300`) or the empty-root error branch (`:887-894`) | **broken at HEAD for the restructured flow** — closed by G2.a (bootstrap-valid root) |
| FM-G2 | Unified single-tx genesis exposes an unbuilt `OUser.name` to UNIQUE enforcement mid-tx | avoided structurally by the two-phase split (D18, frozen); not re-litigated |
| FM-G3 | Crash/abort mid-genesis | **AMENDED (pass-1 CS34+CN48+CS36+CS37 — supersedes the original "three coarse states / discarded, not reopened [inferred]" row, which was false at HEAD):** the designed sequence has the W0–W9 durable crash-state enumeration (Amendments §A1). Containment: cleanup-on-exception in `createStorage` (exception path) + the open-time genesis-completion marker (crash path) — W6/W7 refuse the open loudly; W1/W2 residues are removed by cleanup or fail configuration load |
| FM-G4 | Crash inside storage create with embedded blobs | whole `doCreate` body is one WAL atomic operation — no partial blob set is ever exposed to a reader **[verified mechanism]**; the pre-existing clean-flag window (W2, pass-1 CS37) corrects the envelope to "WAL-rolls-back OR fails configuration load", both unusable-and-discarded — carried in §A1's enumeration |
| FM-G5 | Blobs physically present but unregistered (crash between storage create and phase-1 commit) | readers consult only the schema set (`getBlobCollectionIds:5028`) → blobs inert; the half-create is condemned by the §A1 containment (completion marker refuses the open) — benign **(amended, pass-1 CS34: "discarded anyway" is now enforced, not assumed)** |
| FM-G6 | Collection-id renumbering breaks id-pinned fixtures/dumps/tests | de-risked by the mandatory early sweep (R3, made executable by pass-1 WI5 — pin #8); production lookups dynamic **except** the importer's blob-id mapping (`DatabaseImport.java:528-541`), fixed by Draft M per Amendments §A3 (pass-1 WI1) — the fixture sweep cannot catch that production path |
| FM-G7 | Import-nested `security.create` (validation off, null user, possibly non-default session state) breaks under the restructure | closed by the guard-first + nesting-compat requirements (G2.c); pinned by a dedicated test |
| FM-G8 | Phase-1 commit failure leaves a half-registered schema | Track 4's undo/reconciliation arms own this path; genesis adds no new mechanism — it is the natural end-to-end exerciser (D18 rationale) |

## G.4 Invariants discharged

- **I-U4 — genesis builds the schema before it inserts users.** Discharged by the two-phase shape:
  phase 1 commits every internal class/property/index (engines built at commit) before phase 2's
  first insert; the mutex engages in phase 1 only. Test-pinned in both directions (§G.5).

## G.5 Test-pin candidates

1. *Virgin-DB schema tx seeds cleanly* — open a schema tx immediately after DB create; assert the
   seed succeeds and the tx-local copy is empty (red at HEAD: FM-G1). **Red-first candidate.**
2. *Bootstrap root round-trips* **(reworded, pass-1 WC2)** — (a) unit-level: the root persisted
   by `SchemaShared.create` parses as the bootstrap-valid empty-schema shape (version 6, empty
   classes, counter 0) *before phase 1 runs*; (b) DB-level: create → close → reopen loads the
   genesis-POPULATED schema without the FM-G1 error branch (a completed create can never show an
   empty schema).
3. *I-U4 positive* — during genesis phase 1 the mutex is engaged; during phase 2 it is not
   (observable via `MetadataWriteMutex` holder state, as `MetadataWriteMutexTest` already does).
4. *Engine-before-insert* — after phase 1 commits, `OUser.name` resolves to a built engine
   (`getIndexId() >= 0`) before the first user insert; default users are then found via the index.
5. *Import-nested create* — an import that reaches `removeDefaultCollections` recreates the
   security schema and users; guard no-op path also pinned (create returns null when classes
   exist, without opening a tx).
6. *System-DB genesis* — phase 2 empty (no roles/users), phase 1 identical; the test MUST avoid
   parallel system-DB first-touch (the pre-existing unsynchronized `init()` race — CN53,
   deferred; constraint copied here per gate-1 OBS-2).
7. *Blob registration* — `getBlobCollectionIds()` equals the storage-created `$blob*` ids; blob
   record round-trip on both profiles.
8. *Id-renumbering sweep* **(made executable, pass-1 WI5)** — ordering: (1) static sweep before
   any code change — grep tests/fixtures/stored dumps for `#\d+:\d+` RID literals and hard-coded
   collection ids; (2) land the R3 `doCreate` blob loop alone; (3) run the named suites against
   the renumbered layout: `DbImportExportTest`, `DbImportStreamExportTest`,
   `DatabaseExportImportRoundTripTest`, the `StorageBackup*` / `LocalPaginatedStorageRestore*`
   ITs, and the RID-literal tests in `tests/`; (4) only then land the two-phase restructure.
   Fixture rule: regenerable fixtures are re-exported with new binaries; stored dumps stay valid
   by construction (import maps collections by name; the blob row is closed by §A3).
9. *Failure containment* **(rewritten, pass-1 CS34+CN48)** — against the §A1 W-states: (a)
   exception path: an injected phase-1/phase-2 failure propagates AND cleans up — storage absent
   from `storages`/`sharedContexts`, on-disk residue removed, `exists()` false, create-retry
   succeeds, and `create(failIfExists=false)` re-creates instead of silently no-op'ing; (b) crash
   path: a W6/W7-state DB (completion marker absent) refuses `open`/`openNoAuthenticate` loudly
   with the discard-and-recreate message, and `drop()` discards the corpse WITHOUT surfacing the
   refusal (gate-1 CN54); (c) a completed create opens with the marker present, on both profiles
   (the W9a crash window — phase-2 durable, marker not yet — is an accepted fail-closed false
   refusal, gate-1 CS45).
10. *Single data transaction (Q-G2)* **(added, pass-1 WI4)** — phase 2 commits exactly once
    (roles + users in one tx), observable via commit-count instrumentation or the tx listener.

## G.6 File-level footprint

| File | Change |
|---|---|
| `core/.../metadata/schema/SchemaShared.java` | G2.a bootstrap payload in `create` |
| `core/.../storage/impl/local/AbstractStorage.java` | R3 blob loop in `doCreate` (next to `:1510`) |
| `core/.../db/SharedContext.java` | two-phase restructure of `create`; register-only blob loop |
| `core/.../metadata/security/SecurityShared.java` | DDL/data split of `create`; guard stays first |
| `core/.../schedule/SchedulerImpl.java`, `metadata/function/FunctionLibraryImpl.java`, `metadata/sequence/SequenceLibraryImpl.java` | creator tx-compatibility (join the genesis tx; Q-G1 governs their standalone call sites) |
| `core/.../index/IndexManagerEmbedded.java` | only if Q-G3 requires the IM-root symmetric fix |
| `core/.../db/YouTrackDBInternalEmbedded.java` | **(added, pass-1 CS34+CN48)** cleanup-on-exception in `createStorage`; open-time genesis-completion check wiring |
| Tests: new genesis bootstrap test class + sweep fixes | §G.5 |

~8-10 production files + tests; within the plan's ~14-file combined budget.

---

# DRAFT M — Schema-format migration (D20)

## M.0 The machinery as it exists at HEAD (baseline facts)

| Piece | Where | Behavior |
|---|---|---|
| Version | `DatabaseExport.java:59` | `EXPORTER_VERSION = 14` |
| Open-time gate | `SchemaShared.fromStream:895-904` | accepts only schema version 6; rejects with the export/reimport redirect (**Track 2 landed it — this track is the redirect target, confirmed**) |
| Promote-on-failure | `DatabaseExport.exportDatabase:157-160` (`close()` in `finally`) + `close():270-301` | `close()` unconditionally renames `.tmp` → final (`atomicMoveWithFallback:291`) — **the failure path also promotes** [verified] |
| Scan-failure swallow | `DatabaseExport.java:212-239` | only `YTIOException` rethrows; generic `Exception` is logged and the export continues → success exit |
| Per-record corruption | `DatabaseExport.exportRecord:584-590` | renders straight into the shared `jsonGenerator`; a mid-render throw leaves partial JSON in the stream AND the export continues [verified] |
| Info section | `DatabaseExport.exportInfo:360-386` | writes `exporter-version` 14, `schema-version` = `SchemaShared.CURRENT_VERSION_NUMBER` (6, `:380`) |
| Schema section | `DatabaseExport.exportSchema:449-580` | logical schema from the immutable snapshot; writes `schema.getVersion()` (`:454`) — now a process-wide generation token (Track 5 BC1), meaningless to consumers |
| No manifest / no completion flag / no gzip validation | `core/.../db/tool/` | none exist [verified] |
| Import fallback | `DatabaseImport` ctor `:136-143` | `catch (Exception) → reset → plain stream` — silent plain-JSON fallback |
| Import info | `importInfo:406-424` | reads only `exporter-version` (`<14` → backwards-compat serializer); everything else skipped |
| Import sections | `importDatabase:226-242` | processes whatever tags are present; unknown tag throws; **a missing section is silently tolerated** |
| Rename helper | `FileUtils.atomicMoveWithFallback:306-320` | rename only, **no fsync** of content or directory [verified] |
| Streaming variants | `DatabaseExport` OutputStream ctor (`tempFileName == null`), `DatabaseImport` InputStream ctor | both live; used by `DbImportStreamExportTest` |

## M.1 Scope

**In:** export hardening (v15 bump; rethrow-by-default with explicit best-effort opt-out recorded
in the info section; per-record bounded buffer with spill-to-temp and whole-or-discarded copy-out;
completion-flag-gated promote; in-dump trailing manifest (R2) + fsync-before-rename via a new
fsync-capable move); import hardening under R1's v15-strictness (manifest verify, section-presence
check, single-member gzip full-consumption validation, non-gzip rejection, best-effort ack gate,
R4 info-field validation); migration tests. **Out:** the format itself and the open-time gate
(Track 2 — confirmed present); any in-place migrator (D20 non-goal); the general (declared
`<= 14`) import path's leniency, which is preserved byte-for-byte (an UNDECLARED exporter-version
is no longer lenient — rejected fail-closed per SR2).

## M.2 Design narrative

### M2.a — Export hardening

1. **`EXPORTER_VERSION` 14 → 15.** The dump self-declares the strict contract (R1 keys off this).
2. **Rethrow-by-default.** The generic-`Exception` swallow arms (`:221-239` collection scan;
   `exportRecord:592-615` per-record) rethrow by default. An explicit best-effort opt-out flag
   (surface: an `-option`, exact name at decomposition) restores skip-and-continue for the
   per-record arm only; the choice is recorded as a scalar marker in the dump's **info section**
   so the importer can enforce the ack gate (M2.b-4). Skipped RIDs continue to land in
   `brokenRids`.
3. **Whole-or-discarded record rendering.** Each record renders into a per-record bounded buffer
   (its own `JsonGenerator`), never directly into the shared stream. On success the buffer is
   copied out raw into the dump; on a render failure the buffer is discarded whole (best-effort
   mode) or the export aborts (default). Beyond a size threshold (Q-M1) the buffer spills its
   overflow to a transient file, so memory stays bounded and an oversized-but-healthy record is
   exported, not shed. Spill-file lifecycle: collision-free name, deleted on every path (the
   design pins the property; the mechanism is the implementer's). A copy-out I/O failure is
   whole-or-fatal: abort, no promote.
4. **Completion-flag-gated promote.** A boolean set only after the last section (the manifest) is
   written and the stream closed cleanly; `close()` promotes the temp file → final **only when
   the flag is set**. The failure path closes and deletes/leaves the temp file, never renames —
   this kills the promote-on-failure defect (M.0). **(Amended, pass-1 CS41):** the constructor's
   upfront delete of the previous final-name dump (`DatabaseExport:85`,
   `prepareForFileCreationOrReplacement`) is DROPPED — the final name is replaced only at a
   verified promote (REPLACE_EXISTING-capable atomic move), so a failed export preserves the
   operator's last good dump. **(Amended, pass-1 CN52):** the temp filename is per-export unique
   (UUID/pid-suffixed) and opened `CREATE_NEW`, so concurrent exporters of the same target cannot
   interleave bytes in one temp file, and each promote publishes one internally consistent dump.
   The streaming (OutputStream) variant has no rename to gate; its completion marker is the
   manifest section itself (import verifies it, path-agnostic).
5. **Manifest last + durable promote.** The in-dump trailing manifest section (R2) carries class /
   index / record counts (counts of *exported* records, so a best-effort dump's manifest matches
   what is present) plus the brokenRids count. **Shape pinned (pass-1 WI8c):** section tag
   `"manifest"`, fields `classes`, `indexes`, `records`, `brokenRids` — total counts
   (per-collection granularity declined for v1). **Count provenance pinned (pass-1 CN51):** the
   exporter increments its own per-section counters as it writes — the manifest is NEVER
   re-derived from a fresh schema snapshot at manifest/close time (`getImmutableSchemaSnapshot`
   mints fresh snapshots when unpinned, `MetadataDefault.java:137-145`, so re-derivation under
   concurrent DDL would fail-closed-reject a good dump); the importer verifies against its own
   consumption tallies, never against target-DB queries. Write order pinned: manifest is the
   final section before the closing brace. **Promote recipe pinned (amended, pass-1 CS40 —
   supersedes "flush + close → fsync the dump file → rename"):** close the gzip stream → reopen a
   `FileChannel` on the temp file and `force(true)` (the stream is already closed, so the fsync
   needs its own channel) → `ATOMIC_MOVE` + `REPLACE_EXISTING` rename → **fsync the parent
   directory** (POSIX rename durability). The non-atomic fallback arm of the new move helper is
   fail-closed on the v15 export path — no silent copy fallback, which would reintroduce the
   torn-final-name state this recipe closes.
6. **Primary-exception preservation.** The scan/render failure propagates as the primary;
   `close()`-path secondaries are caught and attached as suppressed, never replacing the primary
   (at HEAD a throw from `close()` in the `finally` at `:157-160` would mask the scan exception
   **[verified Java semantics; reachability inferred]**).
7. **Schema `version` field**: the section keeps its slot for format stability but writes
   `SchemaShared.CURRENT_VERSION_NUMBER` instead of the meaningless generation token (M.0); import
   never consumed the old value, so this is compatible.

### M2.b — Import hardening (v15-strict per R1)

The importer reads `exporter-version` from the info section as today. **Dispatch (harmonized,
pass-1 WC3 + SR2):** `== 15` arms the full strict matrix; `>= 16` short-circuits to
reject-with-redirect naming both versions (Q-M2); an undeclared or malformed `exporter-version`
is rejected fail-closed (SR2); a declared `<= 14` keeps today's lenient behavior (plain-JSON
fallback kept, with the known consequence recorded).

**Pre-flight ordering (pass-1 CS38 — blocker remedy; block boundary pinned by gate-1 WI11).**
ALL import-preamble mutations of the target — the contiguous block `DatabaseImport.java:214-:223`
INCLUSIVE: `removeDefaultNonSecurityClasses` (`:214`), the auto-index snapshot (`:216-222`), and
the order-coupled `beforeImportSchemaSnapshot` capture (`:223`, consumed by `importRecords`'
system-record classification — it must move WITH the drop it observes, or `importRecords` sees a
snapshot still containing the to-be-dropped classes) — plus `removeDefaultCollections` (via
`importSchema:497` / `importCollections:848`) —
are DEFERRED until after the info section is parsed and the Q-M2 pre-flight matrix has passed.
A dump whose first tag is not `info` is rejected by SR2's trigger before any deferred mutation
can be unlocked.
For `>= 15` the info section is required first (compatible: every exporter writes `info` first,
`exportDatabase:136`); the `< 15` path defers identically — behavior-preserving, since nothing
between `:214` and the first section consumes the dropped classes. This makes every ruled
pre-flight rejection genuinely precede all target mutation; the structural post-mutation
rejections are scoped by SR1.

1. **Non-gzip rejection.** The silent fallback (`ctor:136-143`) is restructured: the stream is
   opened as today, but once the info section declares `>= 15`, having arrived via the plain-JSON
   fallback is a hard failure ("a v15 dump is gzip-framed; refusing unverifiable input"). A
   manually-gunzipped v15 dump is therefore rejected (fail-closed; Q-M3 offers the user the final
   word). Detection is necessarily post-info-parse — the version lives inside the stream.
2. **Whole-stream gzip validation** **(sequence pinned, pass-1 CS43 — supersedes the bare
   arithmetic sentence).** A `GZIPInputStream` subclass that **disables multi-member
   continuation** (this is what makes draining safe). Validation sequence, in order: (1) after
   the manifest parses, drain the *decompressed* stream to EOF — reads return -1 once the
   subclass has verified the trailer, without probing for a next member; (2) assert
   `inflater.finished()`; (3) for seekable sources, assert `headerLen + Inflater.getBytesRead()
   + 8 == physicalSize`. The forbidden "exhaustion probe" is the raw-stream / JDK next-member
   probe (it consumes trailing residue into the dead decoder buffer — design.md §"Schema-format
   migration" gotcha), NOT the decompressed-side drain: skipping the drain leaves
   `getBytesRead()` legitimately short of the deflate stream and would false-reject valid dumps.
   Layered detection: truncated deflate → EOF/inflate exception during parse; corrupt trailer →
   subclass trailer verify; trailing garbage → arithmetic; content corruption → CRC32. The
   pure-stream ctor applies (1)-(2) (single-member + trailer + finished); the physical-size
   arithmetic (3) needs a sizable source — exact stream-variant scope carried as an explicit
   decomposition obligation (pass-1 WI10a).
3. **Manifest verify + section presence.** After the section loop, a v15 import hard-fails on: a
   missing or unparsable manifest section; any expected section absent (info, collections, schema,
   records, indexes, brokenRids, manifest); a duplicated section (the presence tracker counts
   occurrences — pass-1 WI10c); manifest counts disagreeing with the importer's own consumption
   tallies (provenance per M2.a-5 / CN51); non-empty `brokenRids` without the best-effort marker
   (an honest default-mode v15 export aborts before producing brokenRids, so the combination
   proves tampering/inconsistency — pass-1 WI10b). **Version gating (pass-1 WI6):** the new
   `manifest` tag case in the shared section loop arms only for `exporterVersion >= 15`; below,
   the tag remains unsupported and throws as at HEAD — preserving the `< 15` path byte-for-byte.
   **Contract scope (SR1):** these are structural whole-stream rejections and are inherently
   post-mutation — a v15 import rejected here has mutated the target, which is CONDEMNED (the
   operator procedure mandates import-into-a-fresh-database and discard-on-any-failure; no
   two-pass import).
4. **Best-effort ack gate.** A dump whose info section carries the best-effort marker is refused
   unless the importer was given an explicit acknowledgment flag; only a v15-aware importer knows
   to enforce this (the marker rides the info section, M2.a-2).
5. **R4 info validation.** A v15 dump's info fields are validated fail-closed rather than skipped:
   `schema-version` must be acceptable (exact matrix = Q-M2), `exporter-version` must be a known
   version (> 15 handling = Q-M2), mandatory fields present. Parse-level strictness: a dangling
   field name (name written, value missing — the mid-write crash shape) is a parse rejection, not
   a tolerated tail.

## M.3 Failure-mode analysis

| # | Scenario | HEAD verdict / design answer |
|---|---|---|
| FM-M1 | Mid-collection scan failure → success exit + promoted dump (`:221-239` + `:270-301`) | **defect at HEAD** — closed by rethrow-by-default + completion flag (M2.a-2/4). Red-first. |
| FM-M2 | Mid-render failure leaves partial JSON in the shared stream and the export continues (`:584-615`) | **defect at HEAD** — closed by the bounded buffer + whole-or-discarded copy-out (M2.a-3) |
| FM-M3 | Failure path promotes `.tmp` → final (`close()` in `finally`) | **defect at HEAD** — closed by the completion-flag gate (M2.a-4) |
| FM-M4 | Crash after rename before data is durable → truncated final-name dump; or the rename itself lost (directory entry not durable) after exit 0 | **latent at HEAD** (no fsync in `atomicMoveWithFallback:306-320`) — closed by the amended M2.a-5 recipe: file `force(true)` + rename + parent-directory fsync (pass-1 CS40) |
| FM-M5 | Close-path secondary masks the scan primary | **latent at HEAD** — closed by suppressed-attachment (M2.a-6) |
| FM-M6 | Truncated/corrupt gzip imports silently (fallback `:136-143`; no consumption check) | **defect at HEAD** — closed by non-gzip rejection + full-consumption validation (M2.b-1/2). Red-first. |
| FM-M7 | Dump missing a whole section imports silently (`:226-242` loop) | **defect at HEAD** — closed by section-presence + manifest verify (M2.b-3) |
| FM-M8 | Best-effort dump imported as if complete | new-in-v15 surface — closed by marker + ack gate (M2.a-2 / M2.b-4) |
| FM-M9 | Oversized record sheds or OOMs the export | closed by spill-to-temp; the record is exported (M2.a-3) |
| FM-M10 | Dangling field name read as a valid record | closed by parse rejection (M2.b-5) |
| FM-M11 | Old-format DB opened in place by new binaries | **already closed** by Track 2's gate (`SchemaShared.java:895-904`); this track only pins the redirect test |
| FM-M12 | v14 and older dumps regress under the new strictness | prevented structurally by the harmonized dispatch — a declared `<= 14` rides the lenient path unchanged (phrasing harmonized per gate-1 WC6; was "R1's `>= 15` keying"); pinned by a v14-dump compatibility test |
| FM-M13 | Streaming export/import variants break under promote/manifest logic | promote gate is a no-op for the stream ctor; manifest is in-dump so streams carry it; pinned by the existing stream round-trip test |
| FM-M14 | Import preamble mutates the target before any v15 rejection can fire (`importDatabase:214` vs `:230`) | **defect at the HEAD flow** — closed by the CS38 pre-flight deferral (M2.b intro); pinned by M.5 #15 |
| FM-M15 | Two concurrent exporters interleave one fixed `.tmp`; both set the completion flag; a corrupt dump is promoted over the good one | **defect of the un-amended design** — closed by the unique `CREATE_NEW` temp name (M2.a-4, pass-1 CN52); pinned by M.5 #17 |
| FM-M16 | Dump blob-collection ids resolved raw in the R3-renumbered target — a class collection silently registered as a blob collection (`DatabaseImport.java:528-541`) | **defect at HEAD under R3** — closed by routing through `collectionToCollectionMapping` / `$blob*` name-match (Amendments §A3, pass-1 WI1); pinned by M.5 #13 |
| FM-M17 | Manifest counts re-derived from a fresh snapshot under concurrent DDL → false fail-closed rejection of a good dump | **defect of the un-amended design** — closed by exporter-tallied / importer-tallied count provenance (M2.a-5, pass-1 CN51) |

## M.4 Invariants discharged

- **I-migration-fail-closed** — a truncated or corrupt dump fails the import loudly (FM-M6/M7); a
  mid-export crash leaves no completion-flagged dump and no manifest section (M2.a-4/5); an
  old-format open is rejected, not migrated (FM-M11); a missing section is refused (FM-M7); a
  best-effort dump requires the ack flag (FM-M8).
- **I-migration-isolation** — a record is exported whole or not at all, including its copy-out
  (M2.a-3); a mid-record I/O failure leaves the final name **untouched** (a pre-existing dump is
  preserved — reworded per pass-1 CS41) (M2.a-4); an oversized-but-healthy record is present, not
  dropped (FM-M9); a failed render lands in `brokenRids` and continues only in best-effort mode
  (M2.a-2). Pre-flight import rejections precede all target mutation (CS38); structural
  rejections condemn the target per SR1.
- **I-migration-failfast** — an injected record-scan failure produces a loud primary exception
  (`DatabaseExportException` whose *cause* is the scan exception; close-path secondaries
  suppressed), the final name untouched, nothing promoted (M2.a-2/4/6, reworded per pass-1 CS41).
  (The plan's "exit ≠ 0" maps to this exception contract: no export CLI entry point exists
  in-repo — the `console` module ships packaging/bin scripts but no Java sources (pass-1 WC5c) —
  so the thrown-primary contract *is* the operator surface, and the WI3 operator document pins
  the exit-status gate.)

## M.5 Test-pin candidates

1. *Injected scan failure* **(reworded, pass-1 CS41)** — export throws with the scan exception
   as primary cause; the **final name is untouched** (a pre-existing dump at the final name is
   preserved on failure); the temp file is never promoted (red at HEAD: FM-M1/M3). **Red-first
   candidate.**
2. *Mid-render failure* — default: abort, nothing promoted; best-effort: record discarded whole,
   RID in `brokenRids`, dump parses end-to-end (red at HEAD: FM-M2).
3. *Truncated gzip dump* — v15 import fails loudly (red at HEAD: FM-M6). **Red-first candidate.**
4. *Trailing-garbage / multi-member gzip* — full-consumption check rejects.
5. *Missing section / missing or count-mismatched manifest* — v15 import refuses (FM-M7).
6. *Plain-JSON v15 stream* — refused (FM-M6/M2.b-1); plain-JSON v14 — still accepted (FM-M12).
7. *Best-effort dump without ack* — refused; with ack — accepted (FM-M8).
8. *Oversized record* — spills, is present in the dump, spill file deleted (FM-M9); threshold
   boundary case.
9. *Dangling field name* — parse-rejected (FM-M10).
10. *Old-format DB open* — rejected with the redirect message (FM-M11; pins Track 2's gate as this
    track's entry).
11. *v14 round-trip + streaming round-trip* — unchanged behavior (FM-M12/M13).
12. *End-to-end migration rehearsal* — export a populated new-format DB, import into a fresh DB,
    `DatabaseCompare`-level equivalence; manifest verified.
13. *Cross-layout blob import (pass-1 WI1)* — a v14-layout dump WITH blob content (blobs at the
    highest source ids) imported into an R3-renumbered target: blob records classified as blobs;
    no class collection registered as a blob collection.
14. *Q-M2 matrix — versions (pass-1 WI4)* — `schema-version` missing / malformed / out of range
    → reject naming declared vs supported; a `>= 16` dump → reject-with-redirect naming both
    versions; an undeclared `exporter-version` → reject (SR2); a malformed/unparseable
    `exporter-version` value → rejected, same SR2 outcome as absent (gate-1 WI12a); a declared
    v14 → lenient path unchanged.
15. *Q-M2 matrix — fields + pre-flight scope (pass-1 WI4)* — mandatory fields enforced; unknown
    extra info fields tolerated and logged; a known optional info field present with a wrong
    type → type-check rejection per Q-M2(3) (gate-1 WI12b); a pre-flight rejection leaves the
    target database unmutated (classes, indexes, records intact — pins CS38 + SR1's scope
    boundary).
16. *Structural-rejection condemnation (SR1)* — a v15 import failing manifest/consumption checks
    throws loudly after mutation; the test asserts the loud failure and documents the
    condemn-target contract (no assertion that the target is clean).
17. *Durable promote discipline (pass-1 WI7)* — the promote path calls the fsync-capable move
    (file force + rename + parent-dir fsync); an unflagged `close()` never renames; concurrent
    exporters (CN52) each write a unique `CREATE_NEW` temp file and the promoted dump parses
    end-to-end; an export under concurrent DDL yields a self-consistent manifest (FM-M17/CN51).
18. *Operator procedure doc (pass-1 WI3, folds CS44)* — the migration-procedure page exists under
    `docs/` and mandates: export-exit-status gate before import; import into a fresh,
    out-of-service target; ANY failure (including post-mutation structural rejections) condemns
    the target — discard, never return to service; import completeness = importer exit 0.

## M.6 File-level footprint

| File | Change |
|---|---|
| `core/.../db/tool/DatabaseExport.java` | v15; rethrow-by-default + opt-out; bounded buffer/spill; completion flag; manifest section; promote rewire |
| `core/.../db/tool/DatabaseImport.java` | v15-strict arm: non-gzip rejection, manifest/section verify, ack gate, R4 info validation |
| new `core/.../db/tool/…GZIPInputStream` subclass | single-member + full-consumption validation (M2.b-2) |
| `core/.../common/io/FileUtils.java` | fsync-capable atomic move variant (file force + REPLACE_EXISTING rename + parent-dir fsync; fail-closed fallback — pass-1 CS40/CS41) |
| `core/.../api/config/GlobalConfiguration.java` | **(added, pass-1 WI8a)** the Q-M1 spill-threshold knob |
| `core/.../db/tool/DatabaseImpExpAbstract.java` | **(noted, pass-1 WI8b)** possibly touched by the best-effort / ack option plumbing — options-only, so the two-unit packing premise survives |
| `docs/` operator migration-procedure page | **(added, pass-1 WI3)** export-exit-status gate, fresh out-of-service target, discard-on-any-failure (folds CS44 + carries the SR1 doctrine) |
| Tests: `DatabaseExportImportRoundTripTest` (extend), new fail-closed test class(es), fixture dumps | §M.5 |

~5-6 production files + one docs page + tests; the Draft G / Draft M production footprints remain
disjoint (WI1's fix lands in `DatabaseImport.java`, already Draft M's file — packing premise
holds).

---

## Invariant cross-reference

- **I-U4** — Draft G §G.4: two-phase shape, mutex in phase 1 only, engine-before-insert pinned
  positively (tests 3-4).
- **I-migration-fail-closed / I-migration-isolation / I-migration-failfast** — Draft M §M.4: each
  clause mapped to a mechanism (M2.a/M2.b) and a failure mode (FM-M1..M17), with the R1 keying
  guaranteeing the declared-legacy path cannot regress (structural-rejection scope per SR1).

## Provenance / verification key

- **[verified]** — established by reading the cited code at HEAD `d664589d7f` (recon t60.e1,
  feasibility t60.e2).
- **[inferred]** — reachability- or protocol-dependent; consistent with the code but not proven
  without execution (FM-G3's original discard-protocol inference — since superseded by the §A1
  containment; M2.a-6's masking reachability; the D18 counterfactual's post-Track-5 behavior).
  Tagged inline.

---

## Open Questions for user ruling

Only genuinely unresolved points found while drafting. The four §0 rulings are settled and not
re-asked.

- **Q-G1 (genesis tx granularity + standalone creators):** should phase 1 be ONE schema
  transaction spanning all creators and the O/V/E classes (single commit, single mutex
  engagement, single all-or-nothing unit — the plan's letter), or one schema tx per creator
  (smaller commits, partial-genesis states return)? And the sibling creators have standalone
  non-genesis call sites (`FunctionLibraryProxy.java:54`, `SequenceLibraryProxy.java:72` lazily
  create their classes on first use): do those sites open their own transaction post-restructure,
  or stay on the legacy top-level path until its removal? Recommendation: one genesis tx;
  standalone sites stay legacy (consistent with the honored-not-owned gap).
- **Q-G2 (phase-2 shape):** one merged data transaction for roles + users (the plan's singular
  "data transaction") or keep today's two (`createDefaultRoles` tx + `createDefaultUsers` tx,
  `SecurityShared.java:628-656`)? I-U4 holds either way. Recommendation: merge to one, matching
  the plan's letter and giving a single all-or-nothing default-security unit.
- **Q-G3 (IM root symmetry — verify-first):** the index-manager root is the same empty-entity
  shell (`IndexManagerEmbedded.create:608-621`). If verification shows the genesis commit's index
  reconciliation or the reopen load chokes on the empty IM root the way `copyForTx` does on the
  schema root, does the G2.a bootstrap fix extend to it symmetrically? Recommendation: verify
  during step 1; extend symmetrically if red.
- **Q-M1 (spill threshold):** constant or `GlobalConfiguration` knob, and the default (proposal:
  a knob, default in the tens of MB; the threshold is operational tuning, not correctness).
- **Q-M2 (R4 validation matrix):** the exact fail-closed rules for a v15 dump's info fields —
  `schema-version` strictly `== 6` vs `<= CURRENT`; `exporter-version > 15` (a future dump into
  these binaries): reject-with-redirect or attempt? mandatory-field list; unknown extra fields
  tolerated (recommended: yes, forward-compat) or rejected?
- **Q-M3 (manually-gunzipped v15 dump):** always rejected (fail-closed recommendation, as drafted
  in M2.b-1) or admissible via an explicit override flag for operator convenience? An override
  weakens the whole-stream validation story (no gzip trailer to verify).
- **Admin:** the YouTrack issue ID for the top-level-DDL mutex-bypass gap (honored-not-owned, §0)
  is still not recorded anywhere in the repo — please supply it so the track file and PR
  description can cite it. **RESOLVED 2026-07-23 (see Rulings §Admin): MOOT** — the legacy path is
  removed in an upcoming PR; no ID will be cited.

---

## Rulings — user design review (2026-07-23)

All six open questions plus the admin item ruled by the user on 2026-07-23. These rulings are
binding inputs to the adversarial review and step decomposition. The four §0 rulings (R1–R4) were
locked before drafting and stand unchanged.

- **Q-G1 — one schema transaction, as recommended.** Phase 1 is ONE schema transaction spanning
  all creators and the O/V/E classes: single commit, single mutex engagement, all-or-nothing. The
  standalone lazy creator call sites stay on the legacy top-level path until that path's removal
  (whose upcoming-PR status is now on the record — see §Admin below). **[Citation corrected
  2026-07-23, pass-1 CN49 — decision unchanged: the sites originally cited here
  (`FunctionLibraryProxy.java:54`, `SequenceLibraryProxy.java:72`) are test-only dead code; the
  LIVE lazy-creation sites are `FunctionLibraryImpl.createFunction:137-139 → init:164-185`
  (including its index-repair arm) and `SequenceLibraryImpl.createSequence:103-123 → init`. The
  Open Questions Q-G1 text retains the original citations as historical record.]**
- **Q-G2 — one merged data transaction, as recommended.** Phase 2 is ONE data transaction for the
  default roles + users — a single all-or-nothing default-security unit (supersedes today's
  two-tx shape at `SecurityShared.java:628-656`).
- **Q-G3 — verify-first, as recommended.** During the first step, verify whether the genesis
  commit's index reconciliation or the reopen load chokes on the empty `IndexManagerEmbedded`
  root shell (`IndexManagerEmbedded.create:608-621`) the way `copyForTx` does on the schema root
  (`SchemaShared.java:300-301`); if red, extend the G2.a bootstrap-payload fix to it
  symmetrically.
- **Q-M1 — `GlobalConfiguration` knob, as recommended.** The spill threshold is a
  `GlobalConfiguration` knob with a default in the tens of MB. Operational tuning, not
  correctness.
- **Q-M2 — full validation matrix ruled.** (1) **exporter-version dispatch:** `<= 14` → the
  legacy lenient path, unchanged (the migration vehicle, per R1); `== 15` → the strict path;
  `>= 16` → reject-with-redirect naming both versions ("produced by newer binaries; import with a
  release supporting exporter version N"). (2) **schema-version:** mandatory in v15 dumps and
  must satisfy `MIN_IMPORTABLE` (= 6) `<= declared <= CURRENT` (= 6) — exact equality today,
  expressed as a range so the future version bump is a one-constant change; missing or malformed →
  reject. (3) **Mandatory fields** = `exporter-version` + `schema-version` (the fields import
  decision logic consumes); other known info fields are type-checked if present but not required.
  (4) **Unknown extra fields tolerated** (logged, not rejected — as recommended): the version
  number is the compatibility contract, not field enumeration. All rejections throw BEFORE any
  mutation of the target database (I-migration-isolation / I-migration-failfast), with messages
  naming the declared vs supported values. *(Scoped by SR1 — inline pointer added per gate-1
  WC6: this sentence governs the PRE-FLIGHT rejections; structural whole-stream rejections are
  inherently post-mutation and condemn the target, see Rulings §SR1.)*
- **Q-M3 — always rejected, as recommended (as drafted in M2.b-1).** A manually-gunzipped v15
  dump is ALWAYS rejected; no override flag. An override would gut the whole-stream validation
  story — with no gzip trailer there is nothing to verify.
- **Admin — RESOLVED: MOOT.** The top-level-DDL mutex-bypass gap needs no YouTrack ID: the legacy
  top-level DDL path is removed in an upcoming PR (user, 2026-07-23). The §0 honored-not-owned
  note and the Open Questions admin bullet are updated in place (historical text retained, marked
  resolved); the track file and PR description cite the upcoming-removal rationale instead of an
  issue ID.

These rulings complete the mandatory user design review for Track 8. Next gates:
pre-implementation adversarial review of this agreed design, then step decomposition into
track-8.md.

### Supplementary rulings — adversarial pass-1 triage (2026-07-23)

Issued by the user during the pass-1 triage (see Amendments). They scope/extend the rulings above
without re-opening them.

- **SR1 (resolves CS39 + CN51-secondary) — "rejections before mutation" is SCOPED to pre-flight.**
  The Q-M2 guarantee "All rejections throw BEFORE any mutation of the target database" applies to
  the PRE-FLIGHT rejections (the info-section matrix: version dispatch, schema-version range,
  mandatory fields, framing/fallback detection, ack gate), which the CS38 deferral makes genuinely
  pre-mutation. Structural whole-stream rejections — manifest counts, gzip trailer/consumption,
  section presence — are inherently post-mutation; the new operator migration-procedure document
  (WI3) mandates import-into-a-fresh-database and discard-on-any-failure, so a structurally
  rejected target is CONDEMNED, never returned to service. **NO two-pass import** (the
  considered-and-rejected alternative is on the record in the durability report §CS39).
- **SR2 (resolves CS42 + WI2) — a dump with NO declared exporter-version is REJECTED
  fail-closed.** Trigger precise per gate-1 CS46: the rejection fires at the **first non-`info`
  section tag, or at end of stream, whichever comes first**, if no parseable `exporter-version`
  has been declared by then. (The version is always declared *inside* the section loop via
  `importInfo`, so the superseded "reaches its section loop" phrasing could be misread as
  rejecting every dump at loop entry.) This also closes the loop with CS38: a dump whose first
  tag is not `info` is rejected before any deferred preamble mutation can be unlocked. A dump
  rejected here does not ride the lenient path. Legitimate legacy
  dumps always declare a version (every exporter since the legacy era writes `info` first), so the
  only inputs this rejects are corrupt, truncated (e.g. the streaming crash shapes), or
  hand-damaged dumps — exactly the fail-closed set. This closes the strict matrix's bootstrapping
  hole (arming previously required the very field the missing section carries).

---

## Amendments — adversarial pass 1 triage (2026-07-23)

Adversarial pass 1 attacked the agreed design (drafts + §0 rulings + 2026-07-23 rulings) from
three perspectives. Reports: `track-8/reviews/adversarial-durability-pass1.md` (CS34–CS44),
`track-8/reviews/adversarial-concurrency-pass1.md` (CN48–CN53),
`track-8/reviews/adversarial-completeness-pass1.md` (WI1–WI10, WC1–WC5). User-approved triage
(2026-07-23): three blocker-remedy clusters adopted (§A1 genesis-failure containment, §A2 import
pre-flight deferral, §A3 blob-id import mapping), two supplementary rulings issued (SR1, SR2 —
recorded in the Rulings section), every should-fix adopted, every suggestion dispositioned below.
No settled ruling was re-litigated. Where an amendment changes earlier text the superseded
sentence is named; mechanical consequences (FM rows, test pins, footprints, recipe sentences) are
edited in place in the draft body, each edit tagged `(pass-1 <ID>)`.

### Disposition table (every finding; nothing silently dropped)

| ID | Severity | Disposition |
|---|---|---|
| CS34 | blocker | ADOPTED — §A1 (cleanup-on-exception + open-time completion marker); FM-G3/FM-G5, pin G.5 #9, G.6 footprint amended |
| CS35 | should-fix | FOLDED into §A1 — the marker refusal is the replacement completeness signal (G2.a amended) |
| CS36 | should-fix | ADOPTED in §A1 — FM-G3 rewritten to the W-state enumeration; pointer-fold hardening considered and DECLINED (scope) |
| CS37 | suggestion | RECORDED as pre-existing observation, no code action — the corrected claim ("WAL-rolls-back OR fails config load") carried in §A1 W1/W2 and the amended FM-G4 row |
| CS38 | blocker | ADOPTED — §A2 pre-flight deferral (M2.b intro amended; FM-M14; pin M.5 #15) |
| CS39 | should-fix | RESOLVED by SR1 — contract scoped; condemn-target doctrine; NO two-pass import |
| CS40 | should-fix | ADOPTED — promote recipe: file `force(true)` + REPLACE_EXISTING rename + parent-dir fsync; fail-closed fallback (M2.a-5 amended; FM-M4 updated) |
| CS41 | should-fix | ADOPTED — upfront final-name delete dropped; promote-time replace only; M.4 + pin M.5 #1 reworded to "final name untouched / pre-existing dump preserved" |
| CS42 | should-fix | RESOLVED by SR2 — undeclared exporter-version rejected fail-closed |
| CS43 | should-fix | ADOPTED — exact gzip validation sequence pinned (M2.b-2 rewritten) |
| CS44 | suggestion | FOLDED into WI3's operator document (exit-0 gate + condemn-target; pin M.5 #18) |
| CN48 | should-fix | ADOPTED — merged into §A1 (same remedy as CS34; footprint gains `YouTrackDBInternalEmbedded.java`) |
| CN49 | should-fix | ADOPTED — citation correction only, decision unchanged (Rulings §Q-G1 annotated; live sites named) |
| CN50 | should-fix | ADOPTED — single config read at storage create; register loop enumerates actual `$blob*` collections by name (G2.b amended) |
| CN51 | should-fix | primary ADOPTED — exporter-tallied / importer-tallied count provenance (M2.a-5, M2.b-3, FM-M17); secondary RESOLVED by SR1 |
| CN52 | should-fix | ADOPTED — per-export unique `CREATE_NEW` temp name (M2.a-4; FM-M15; pin M.5 #17) |
| CN53 | suggestion | DEFERRED — pre-existing system-DB first-touch race, unchanged by Track 8; pin G.5 #6 must avoid parallel first-touch; an opportunistic one-line fix may ride the genesis unit if free |
| WI1 | blocker | ADOPTED — §A3 (Draft M owns the blob-id mapping fix); §0 R3 / FM-G6 `[verified]` claim corrected; pin M.5 #13 |
| WI2 | should-fix | RESOLVED by SR2 |
| WI3 | should-fix | ADOPTED — operator migration-procedure page added to M.6 + pin M.5 #18 (folds CS44, carries the SR1 doctrine) |
| WI4 | should-fix | ADOPTED — Q-M2 matrix pins M.5 #14-15 (+#16 for the SR1 scope); Q-G2 single-tx pin G.5 #10 |
| WI5 | should-fix | ADOPTED — sweep made executable (ordering, named suites, grep patterns, fixture rule — pin G.5 #8 rewritten) |
| WI6 | suggestion | ADOPTED — `manifest` tag case version-gated (M2.b-3) |
| WI7 | suggestion | ADOPTED — FM-M4 gains pin M.5 #17 (fsync-move called on promote; unflagged close never renames) |
| WI8 | suggestion | ADOPTED — (a) `GlobalConfiguration.java` in M.6; (b) `DatabaseImpExpAbstract` options touch noted, packing premise survives; (c) manifest JSON shape pinned (M2.a-5); (d) the Move-2 ADDED/MODIFIED/REMOVED triad must record the R3 scope expansion (`AbstractStorage`, `SharedContext`) and M2.a-7 vs the frozen track-8.md In-scope list |
| WI9 | suggestion | ADOPTED — `SharedContext.lock` interaction argument added (G2.c) |
| WI10 | suggestion | (a) stream-ctor validation scope carried as an explicit decomposition obligation (M2.b-2); (b) ADOPTED — brokenRids-without-marker rejected; (c) ADOPTED — duplicate sections rejected (both M2.b-3) |
| WC1 | should-fix | ADOPTED — R1's supersession of frozen track-8.md Validation bullet 2 recorded below; Move 2/3 carries it into track-8.md |
| WC2 | should-fix | ADOPTED — pin G.5 #2 rewritten (post-genesis reopen shows the genesis-POPULATED schema) |
| WC3 | suggestion | ADOPTED — dispatch phrasing harmonized (§0 R1 note; M2.b intro) |
| WC4 | suggestion | ADOPTED — design-final reconciliation hand-off recorded (G2.a): "writes the first schema record" superseded in letter |
| WC5 | suggestion | (a) RECORDED — implementation-plan.md's checklist shows Tracks 5-7 unchecked though landed; plan-file hygiene at Move 2 (this commit touches only this file); (b) ADOPTED — track-7 drafts path spelled in §0; (c) ADOPTED — "console module is empty" made precise in M.4 |
| CN observations (no ID) | — | RECORDED for the implementer: `callOnCreateListeners` fires twice per create at HEAD (`YouTrackDBInternalEmbedded:757` + `:770`) and the genesis session is never closed by `createStorage` — genesis tests must not pin a single listener invocation; pre-existing, outside the Track 8 footprint |

The three reports' null-verdict sets (durability N-G1..N-G3 / N-M1..N-M4; concurrency
V/F/L/I/S/E tables; completeness N1–N8) stand as recorded proofs; no action.

### A1 — CS34 + CN48 + CS36 (+ CS35 folded): genesis-failure containment

**What the pass found.** FM-G3's "A failed create propagates and the DB is discarded, not
reopened [inferred]" is FALSE at HEAD: `createStorage` performs no cleanup on the exception path
(`YouTrackDBInternalEmbedded.java:744-755` puts the storage into `storages` before
`internalCreate`, and the catch only wraps and rethrows); every post-`preCreateSteps` crash state
makes `exists()` true; `create(failIfExists=false)` then silently no-ops on the residue
(`:760-766`); and the W6/W7 states reopen SILENTLY (post-G2.a the bootstrap root parses without
the `fromStream:887-894` breadcrumb, and `SecurityShared.load` even creates `OSecurityPolicy` on
open, `setupPredicateSecurity:1078-1101`). "Three coarse states" also undercounts the designed
sequence's durable crash states.

**Supersessions.** This amendment supersedes: FM-G3's original row in full (both the "three
coarse states (storage-created-only / phase-1-committed / complete)" enumeration and the
"discarded, not reopened [inferred]" claim); FM-G5's "DB is a discarded half-create anyway" (now
enforced, not assumed); test pin G.5 #9's original wording; G.6's original footprint list and
count.

**Corrected crash-state enumeration (durability report §2, adopted as the design's FM-G3).**

| State | Crash point | At reopen | Design answer |
|---|---|---|---|
| W0 | before `preCreateSteps` | no residue | clean re-create (benign) |
| W1 | inside the create atomic op, before `clearStorageDirty` | dirty → WAL rolls the create op back → configuration absent → open fails loudly | residue removed by cleanup (exception path) or manual discard (crash); no longer blocks re-create |
| W2 | after durable `clearStorageDirty` (`AbstractStorage:1524`), before the op's WAL commit is durable | clean-flagged corpse: recovery skipped, configuration load fails loudly (CS37) | same as W1 — unusable either way; mechanism claim corrected from "WAL-reverted" to "rolls back OR fails config load" |
| W3 | storage durable, before the schema-root tx | `SchemaNotCreatedException` (loud) | condemned by the missing completion marker |
| W4 | schema root committed, `setSchemaRecordId` pointer op not (`AbstractStorage:8088-8100` is a separate atomic op) | same signature as W3 | condemned by the marker |
| W5 | schema root + pointer done, IM root/pointer not (`IndexManagerEmbedded:608-621`, same two-step shape) | IM load fails (loud) | condemned by the marker |
| W6 | root shells done, phase-1 not committed | **silent open under the un-amended design** (bootstrap root parses; zero classes/users) | **refused loudly by the open-time completion check** |
| W7 | phase-1 committed, phase-2 not | **silent open, zero users; authenticated open fails with a credentials-shaped error** | **refused loudly by the open-time completion check** |
| W8 | between roles/users txs | eliminated by Q-G2's single data tx | closed by design |
| W9a | phase-2 commit durable, completion marker not yet durable (the marker write is its own durability event — gate-1 CS45) | genesis-complete but marker-less → the belt check refuses the open | **fail-closed FALSE REFUSAL of a genuinely-complete DB — ACCEPTED**: the refusal's discard-and-recreate procedure is cheap and correct for a fresh, data-free DB; no unsafe state opens |
| W9 | marker durable, before listeners | complete DB; listeners are runtime-only | benign **(narrowed, gate-1 CS45: W9 begins only once the marker is durable — the earlier "after the phase-2 commit" span belongs to W9a)** |

**Adopted mechanism (BOTH, per the user-approved remedy).**
1. **Primary — cleanup-on-exception in `createStorage`.** On any failure out of
   `internalCreate`/genesis: remove the storage from `storages` and the context from
   `sharedContexts`, close the storage, and delete the on-disk residue (disk profile) — restoring
   `exists() == false`, so a create-retry re-creates cleanly and `create(failIfExists=false)` can
   never silently no-op on a condemned residue.
2. **Belt — open-time genesis-completion check.** A genesis-completion marker (storage-config
   property) written immediately after the phase-2 commit completes — for the system DB and the
   `CREATE_DEFAULT_USERS=false` case too: the marker means "the genesis sequence ran to
   completion", NOT "users exist" (a zero-OUser-rows heuristic would false-positive on
   `createDefaultUsers=false`). At open, a database with internal metadata but no marker refuses
   the open loudly with a "genesis incomplete — discard and re-create" message. The marker write
   is a separate durability event, so the W9a crash window (phase-2 durable, marker not yet) is
   reachable; its fail-closed false refusal of a genuinely-complete DB is ACCEPTED (gate-1 CS45,
   see the W-table). Compatibility:
   every database these binaries can open passed Track 2's schema-v6 gate; databases created by
   pre-Track-8 builds of this branch lack the marker — dev-only exposure, accepted and recorded.
3. **CS35 fold — the replacement completeness signal.** The marker refusal replaces (and is
   strictly stronger than) the `fromStream:887-894` breadcrumb G2.a silences; W6/W7 go from
   silent-open to loud refusal.
4. **CS36 residue.** The honest enumeration above is mandatory; the optional hardening (folding
   the `setSchemaRecordId`/`setIndexMgrRecordId` pointer writes into the create atomic op) was
   considered and DECLINED as scope — W3-W5 already fail loudly and are condemned by the marker.
5. **Drop-path exemption (gate-1 CN54).** The completion check gates session-minting opens *for
   use*; it must NOT break the very discard it prescribes. `drop()` internally mints a session
   via `openNoAuthenticate` (`YouTrackDBInternalEmbedded.java:814`) to fire `onDrop` listeners,
   then deletes in its `finally` (`:820-841`) — under a naive belt check, dropping a W6/W7
   corpse would delete successfully yet still throw the refusal out of `drop()`. Specified
   handling: the drop path BYPASSES or TOLERATES the refusal (exempt the check on drop's internal
   open, or catch-and-proceed to deletion) — corpse deletion must succeed without surfacing the
   refusal; `onDrop` listeners not firing for a corpse (no usable session can be minted) is
   accepted and recorded. Pinned by the extended pin G.5 #9(b).

### A2 — CS38: import pre-flight deferral

**What the pass found.** The import preamble mutates the target before any v15 rejection can
fire: `removeDefaultNonSecurityClasses` (`importDatabase:214`) drops every non-security class and
its indexes BEFORE `importInfo` (`:230`) can learn the dump's version — so every ruled Q-M2
rejection would fire post-mutation, violating the ruling's letter.

**Adopted remedy** (specified in the amended M2.b intro; block boundary pinned by gate-1 WI11 as
`DatabaseImport.java:214-:223` INCLUSIVE — the order-coupled `beforeImportSchemaSnapshot` capture
at `:223` is part of the deferred preamble): ALL preamble target mutations (`:214`, the
auto-index snapshot `:216-222`, the `:223` snapshot capture, `removeDefaultCollections` via
`importSchema:497` / `importCollections:848`) are deferred until after the info section parses
and the Q-M2 pre-flight matrix passes; `>= 15` requires the info section first (compatible — every exporter writes `info`
first, `exportDatabase:136`); the `< 15` path defers identically (behavior-preserving: nothing
between `:214` and the first section consumes the dropped classes). Supersedes the draft's silent
retention of the HEAD import order. Post-mutation structural rejections are scoped by SR1.

### A3 — WI1: blob-collection import mapping under R3

**What the pass found.** `DatabaseImport.importSchema` maps the dump's `blob-collections` ids RAW
into the target id space (`DatabaseImport.java:528-541`, never consulting
`collectionToCollectionMapping`), so under R3's renumbering a v14-layout dump's blob ids (highest
slots) resolve to CLASS collections in the fresh target (blobs at `1..N`) — a class collection
silently registered as a blob collection on the flagship migration path. This also falsifies §0
R3's unqualified "[verified] All production lookups are name/schema-dynamic".

**Adopted remedy.** Draft M owns the fix (it lands in `DatabaseImport.java`, already in M.6 — the
two-unit packing premise holds): the blob registration routes through
`collectionToCollectionMapping` (or `$blob*` name-match — decomposition picks; both eliminate the
cross-layout misclassification). Supersedes: §0 R3's and FM-G6's unqualified name-dynamic claim
(both corrected in place). New pin: M.5 #13 (a v14-layout dump WITH blob content into an
R3-renumbered target). New FM row: FM-M16.

### Frozen-plan supersession record (pass-1 WC1)

Ruling R1 (2026-07-23) SUPERSEDES two clauses of frozen track-8.md §"Validation and Acceptance"
bullet 2: "a complete legacy dump missing any expected section is refused" and "a legacy dump
requires the explicit unverified-import acknowledgment flag". Under R1, declared-legacy (`<= 14`)
dumps keep today's lenient behavior — a missing section is tolerated and no legacy ack flag
exists (the ack gate keys off the v15 best-effort marker, M2.b-4). Move 2/3 MUST carry this
supersession into track-8.md before the EARS lines are used as test method names, so
decomposition generates tests from the amended contract, not the frozen bullets.

### Triage verdict

Pass-1 triage complete and user-approved (2026-07-23). Amendments applied to the draft body in
place (tagged `(pass-1 <ID>)`); SR1/SR2 recorded in the Rulings section. Next gate per the track
workflow: step decomposition into track-8.md (Move 2/3), carrying the WC1 supersession, the WI8d
Move-2 triad obligations, and the WI10a stream-ctor decomposition obligation.

### Gate verification — iteration 1 (2026-07-23)

Two gate reports verified the pass-1 amendments finding-by-finding:
`track-8/reviews/gate-design-pass1-cscn-iter1.md` (CS34–CS44, CN48–CN53) and
`track-8/reviews/gate-design-pass1-wiwc-iter1.md` (WI1–WI10, WC1–WC5). **Outcome: all 32 pass-1
findings VERIFIED; zero REJECTED / STILL OPEN / MOOT** — both blockers (CS34, CS38, WI1)
conclusively, both supplementary rulings (SR1, SR2) correctly recorded and propagated. The gates
filed six new suggestion-severity findings plus one carried observation; all were user-approved
2026-07-23 and micro-amended in place, each edit tagged `(gate-1 <ID>)`:

| ID | Source gate | Disposition |
|---|---|---|
| CS45 | CS/CN gate | ADOPTED — W9a row added to the §A1 W-table (phase-2 durable, marker not yet durable → fail-closed FALSE REFUSAL of a genuinely-complete DB, ACCEPTED); W9 narrowed to begin at marker durability; §A1 mechanism 2 and pin G.5 #9(c) carry the accepted window |
| CS46 | CS/CN gate | ADOPTED — SR2's trigger reworded to the precise condition: first non-`info` section tag or EOF, whichever comes first, without a parseable `exporter-version` (the superseded "reaches its section loop" phrasing was literally satisfiable by every dump); cross-linked to CS38's deferral |
| CN54 | CS/CN gate | ADOPTED — §A1 mechanism 5: the completion check gates session-minting opens for use; the `drop()` internal `openNoAuthenticate` (`YouTrackDBInternalEmbedded:814`) bypasses/tolerates the refusal, corpse deletion must succeed without surfacing it, `onDrop`-not-firing accepted; pin G.5 #9(b) extended |
| WI11 | WI/WC gate | ADOPTED — §A2 / M2.b deferred-block boundary pinned as `DatabaseImport.java:214-:223` INCLUSIVE (the order-coupled `beforeImportSchemaSnapshot` capture at `:223` moves with the drop it observes) |
| WI12 | WI/WC gate | ADOPTED — pin M.5 #14 extended: malformed/unparseable `exporter-version` → rejected (same SR2 outcome as absent); pin M.5 #15 extended: known optional info field with a wrong type → type-check rejection per Q-M2(3) |
| WC6 | WI/WC gate | ADOPTED — inline SR1 scope pointer added to the Q-M2 ruling bullet's "before mutation" sentence (matching the R1/R3/Q-G1 annotation convention); FM-M12's stale "R1's `>= 15` keying" phrasing harmonized to "declared `<= 14` → lenient" |
| OBS-2 | CS/CN gate (observation) | ADOPTED — CN53's "avoid parallel system-DB first-touch" test constraint copied from the disposition table into pin G.5 #6's own text |

Residual gate observations carried without edits: OBS-1 (the `:223` snapshot's consumers are
drop-invariant — subsumed by WI11's block pin), OBS-3 (bookkeeping, no orphaned supersessions),
and the WI/WC gate's count note (the pass-1 disposition table carries 32 IDs, not 31 as the gate
tasking stated — the table was and is complete). Design phase closes here; next gate: step
decomposition into track-8.md (Move 2/3) with the carry list from the Triage verdict above.
