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
  best-effort ack gate, info-field validation).
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
  and honored as out of scope by Track 7 (`track-7-design-drafts.md` §0). Draft G removes the
  single largest legacy top-level DDL consumer (genesis) but does not close the gap; Draft M's
  import keeps its DDL on the legacy path deliberately (consistent with the gap's planned removal
  in an upcoming PR). **YouTrack ID for the gap: pending from the user** (not present anywhere in
  the repo; see Open Questions).

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

### G2.b — R3: blob collections embedded into storage creation

Per ruling R3, `AbstractStorage.doCreate` gains a loop next to the `internal` create
(`:1510`): `doAddCollection(atomicOperation, "$blob" + i)` for
`i < contextConfiguration.getValueAsInteger(STORAGE_BLOB_COLLECTIONS_COUNT)` — same atomic
operation, so the blob collections are **atomic with storage birth** and WAL-reverted on a crashed
create (`doCreateCollection` javadoc contract, `AbstractStorage.java:7040-7044`) **[verified
mechanism; new call is the only code change]**. Both storage profiles inherit it (`doCreate` is
shared). Collection ids become: `internal` = 0, `$blob0..N-1` = 1..N, class collections from N+1.

`SharedContext.create`'s blob loop becomes register-only: resolve
`session.getCollectionIdByName("$blob" + i)` and call `schema.addBlobCollection(...)` — which
routes through `resolveForWrite()` (`SchemaProxy.java:460-462`), i.e. **inside the genesis schema
tx it is a pure tx-local root-payload write** picked up by the commit's root-diff
(`SchemaShared.java:1309`). The awkward direct-storage-op-inside-metadata-creation disappears; the
genesis schema tx contains only schema writes.

Documented semantic consequence: `STORAGE_BLOB_COLLECTIONS_COUNT` becomes a storage-birth property
frozen at create (it is already effectively read-once-at-genesis today). The id-renumbering caveat
and its mandatory early test sweep are on the record in §0/R3.

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

## G.3 Failure-mode analysis

| # | Scenario | HEAD verdict / design answer |
|---|---|---|
| FM-G1 | First schema tx on a virgin DB trips the `copyForTx` bootstrap assert (`SchemaShared.java:300`) or the empty-root error branch (`:887-894`) | **broken at HEAD for the restructured flow** — closed by G2.a (bootstrap-valid root) |
| FM-G2 | Unified single-tx genesis exposes an unbuilt `OUser.name` to UNIQUE enforcement mid-tx | avoided structurally by the two-phase split (D18, frozen); not re-litigated |
| FM-G3 | Crash mid-genesis | today: dozens of intermediate self-commit states; after: three coarse states (storage-created-only / phase-1-committed / complete). A failed create propagates and the DB is discarded, not reopened **[inferred — creation-failure protocol, to pin with a test]** |
| FM-G4 | Crash inside storage create with embedded blobs | whole `doCreate` body is one WAL atomic operation — no partial blob set survives **[verified mechanism]** |
| FM-G5 | Blobs physically present but unregistered (crash between storage create and phase-1 commit) | readers consult only the schema set (`getBlobCollectionIds:5028`) → blobs inert, DB is a discarded half-create anyway; benign |
| FM-G6 | Collection-id renumbering breaks id-pinned fixtures/dumps/tests | de-risked by the mandatory early sweep (R3); production lookups verified dynamic (t60.e2) |
| FM-G7 | Import-nested `security.create` (validation off, null user, possibly non-default session state) breaks under the restructure | closed by the guard-first + nesting-compat requirements (G2.c); pinned by a dedicated test |
| FM-G8 | Phase-1 commit failure leaves a half-registered schema | Track 4's undo/reconciliation arms own this path; genesis adds no new mechanism — it is the natural end-to-end exerciser (D18 rationale) |

## G.4 Invariants discharged

- **I-U4 — genesis builds the schema before it inserts users.** Discharged by the two-phase shape:
  phase 1 commits every internal class/property/index (engines built at commit) before phase 2's
  first insert; the mutex engages in phase 1 only. Test-pinned in both directions (§G.5).

## G.5 Test-pin candidates

1. *Virgin-DB schema tx seeds cleanly* — open a schema tx immediately after DB create; assert the
   seed succeeds and the tx-local copy is empty (red at HEAD: FM-G1). **Red-first candidate.**
2. *Bootstrap root round-trips* — create → close → reopen; assert schema loads with version 6,
   empty classes, counter 0 (pins the G2.a payload shape on both profiles).
3. *I-U4 positive* — during genesis phase 1 the mutex is engaged; during phase 2 it is not
   (observable via `MetadataWriteMutex` holder state, as `MetadataWriteMutexTest` already does).
4. *Engine-before-insert* — after phase 1 commits, `OUser.name` resolves to a built engine
   (`getIndexId() >= 0`) before the first user insert; default users are then found via the index.
5. *Import-nested create* — an import that reaches `removeDefaultCollections` recreates the
   security schema and users; guard no-op path also pinned (create returns null when classes
   exist, without opening a tx).
6. *System-DB genesis* — phase 2 empty (no roles/users), phase 1 identical.
7. *Blob registration* — `getBlobCollectionIds()` equals the storage-created `$blob*` ids; blob
   record round-trip on both profiles.
8. *Id-renumbering sweep* — the R3 de-risk sweep: run the suites most likely to pin fresh-DB
   collection ids (import/export, backup/restore, RID-literal tests) against the renumbered layout
   **before** the restructure lands; fix fixtures found.
9. *Crash/abort protocol* — a phase-1 abort leaves a DB that create-retry or discard handles
   loudly (pins FM-G3's protocol).

## G.6 File-level footprint

| File | Change |
|---|---|
| `core/.../metadata/schema/SchemaShared.java` | G2.a bootstrap payload in `create` |
| `core/.../storage/impl/local/AbstractStorage.java` | R3 blob loop in `doCreate` (next to `:1510`) |
| `core/.../db/SharedContext.java` | two-phase restructure of `create`; register-only blob loop |
| `core/.../metadata/security/SecurityShared.java` | DDL/data split of `create`; guard stays first |
| `core/.../schedule/SchedulerImpl.java`, `metadata/function/FunctionLibraryImpl.java`, `metadata/sequence/SequenceLibraryImpl.java` | creator tx-compatibility (join the genesis tx; Q-G1 governs their standalone call sites) |
| `core/.../index/IndexManagerEmbedded.java` | only if Q-G3 requires the IM-root symmetric fix |
| Tests: new genesis bootstrap test class + sweep fixes | §G.5 |

~7-9 production files + tests; within the plan's ~14-file combined budget.

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
(Track 2 — confirmed present); any in-place migrator (D20 non-goal); the general (<15) import
path's leniency, which is preserved byte-for-byte.

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
   written and the stream closed cleanly; `close()` promotes `.tmp` → final **only when the flag
   is set**. The failure path closes and deletes/leaves the `.tmp`, never renames — this kills the
   promote-on-failure defect (M.0). The streaming (OutputStream) variant has no rename to gate;
   its completion marker is the manifest section itself (import verifies it, path-agnostic).
5. **Manifest last + durable promote.** The in-dump trailing manifest section (R2) carries class /
   index / record counts (counts of *exported* records, so a best-effort dump's manifest matches
   what is present) plus the brokenRids count. Write order pinned: manifest is the final section
   before the closing brace. Promote order pinned: flush + close → **fsync the dump file** → rename
   (a new fsync-capable variant of `FileUtils.atomicMoveWithFallback`; the existing helper renames
   without fsync, `FileUtils.java:306-320`, so a crash after rename could otherwise expose a
   truncated final-name file).
6. **Primary-exception preservation.** The scan/render failure propagates as the primary;
   `close()`-path secondaries are caught and attached as suppressed, never replacing the primary
   (at HEAD a throw from `close()` in the `finally` at `:157-160` would mask the scan exception
   **[verified Java semantics; reachability inferred]**).
7. **Schema `version` field**: the section keeps its slot for format stability but writes
   `SchemaShared.CURRENT_VERSION_NUMBER` instead of the meaningless generation token (M.0); import
   never consumed the old value, so this is compatible.

### M2.b — Import hardening (v15-strict per R1)

The importer reads `exporter-version` from the info section as today. **If >= 15, the strict
matrix arms**; below 15, behavior is unchanged (lenient general path, plain-JSON fallback kept,
with the known consequence recorded).

1. **Non-gzip rejection.** The silent fallback (`ctor:136-143`) is restructured: the stream is
   opened as today, but once the info section declares `>= 15`, having arrived via the plain-JSON
   fallback is a hard failure ("a v15 dump is gzip-framed; refusing unverifiable input"). A
   manually-gunzipped v15 dump is therefore rejected (fail-closed; Q-M3 offers the user the final
   word). Detection is necessarily post-info-parse — the version lives inside the stream.
2. **Whole-stream gzip validation.** A `GZIPInputStream` subclass validates single-member framing
   and full consumption via inflater arithmetic: `Inflater.getBytesRead()` + parsed header length
   + 8-byte trailer must equal the physical stream size at EOF; **exhaustion probes are forbidden**
   (they consume trailing residue into the dead decoder buffer — design.md §"Schema-format
   migration" gotcha). Applied when the source is seekable/sizable (file path); the pure-stream
   ctor validates what it can (single-member + clean trailer) — exact stream-variant scope pinned
   at decomposition.
3. **Manifest verify + section presence.** After the section loop, a v15 import hard-fails on: a
   missing or unparsable manifest section; any expected section absent (info, collections, schema,
   records, indexes, brokenRids, manifest); manifest counts disagreeing with what was imported.
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
| FM-M4 | Crash after rename, before data hits disk → truncated final-name dump | **latent at HEAD** (no fsync in `atomicMoveWithFallback:306-320`) — closed by fsync-before-rename (M2.a-5) |
| FM-M5 | Close-path secondary masks the scan primary | **latent at HEAD** — closed by suppressed-attachment (M2.a-6) |
| FM-M6 | Truncated/corrupt gzip imports silently (fallback `:136-143`; no consumption check) | **defect at HEAD** — closed by non-gzip rejection + full-consumption validation (M2.b-1/2). Red-first. |
| FM-M7 | Dump missing a whole section imports silently (`:226-242` loop) | **defect at HEAD** — closed by section-presence + manifest verify (M2.b-3) |
| FM-M8 | Best-effort dump imported as if complete | new-in-v15 surface — closed by marker + ack gate (M2.a-2 / M2.b-4) |
| FM-M9 | Oversized record sheds or OOMs the export | closed by spill-to-temp; the record is exported (M2.a-3) |
| FM-M10 | Dangling field name read as a valid record | closed by parse rejection (M2.b-5) |
| FM-M11 | Old-format DB opened in place by new binaries | **already closed** by Track 2's gate (`SchemaShared.java:895-904`); this track only pins the redirect test |
| FM-M12 | v14 and older dumps regress under the new strictness | prevented structurally by R1's `>= 15` keying — the lenient path is untouched; pinned by a v14-dump compatibility test |
| FM-M13 | Streaming export/import variants break under promote/manifest logic | promote gate is a no-op for the stream ctor; manifest is in-dump so streams carry it; pinned by the existing stream round-trip test |

## M.4 Invariants discharged

- **I-migration-fail-closed** — a truncated or corrupt dump fails the import loudly (FM-M6/M7); a
  mid-export crash leaves no completion-flagged dump and no manifest section (M2.a-4/5); an
  old-format open is rejected, not migrated (FM-M11); a missing section is refused (FM-M7); a
  best-effort dump requires the ack flag (FM-M8).
- **I-migration-isolation** — a record is exported whole or not at all, including its copy-out
  (M2.a-3); a mid-record I/O failure leaves no file at the final name (M2.a-4); an
  oversized-but-healthy record is present, not dropped (FM-M9); a failed render lands in
  `brokenRids` and continues only in best-effort mode (M2.a-2).
- **I-migration-failfast** — an injected record-scan failure produces a loud primary exception
  (`DatabaseExportException` whose *cause* is the scan exception; close-path secondaries
  suppressed), no file at the final name, nothing promoted (M2.a-2/4/6). (The plan's "exit ≠ 0"
  maps to this exception contract: no CLI exists in-repo — the `console` module is empty — so the
  thrown-primary contract *is* the operator surface.)

## M.5 Test-pin candidates

1. *Injected scan failure* — export throws with the scan exception as primary cause; no file at
   the final name; `.tmp` not promoted (red at HEAD: FM-M1/M3). **Red-first candidate.**
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

## M.6 File-level footprint

| File | Change |
|---|---|
| `core/.../db/tool/DatabaseExport.java` | v15; rethrow-by-default + opt-out; bounded buffer/spill; completion flag; manifest section; promote rewire |
| `core/.../db/tool/DatabaseImport.java` | v15-strict arm: non-gzip rejection, manifest/section verify, ack gate, R4 info validation |
| new `core/.../db/tool/…GZIPInputStream` subclass | single-member + full-consumption validation (M2.b-2) |
| `core/.../common/io/FileUtils.java` | fsync-capable atomic move variant |
| Tests: `DatabaseExportImportRoundTripTest` (extend), new fail-closed test class(es), fixture dumps | §M.5 |

~4 production files + tests; disjoint from Draft G's footprint (the packing premise holds).

---

## Invariant cross-reference

- **I-U4** — Draft G §G.4: two-phase shape, mutex in phase 1 only, engine-before-insert pinned
  positively (tests 3-4).
- **I-migration-fail-closed / I-migration-isolation / I-migration-failfast** — Draft M §M.4: each
  clause mapped to a mechanism (M2.a/M2.b) and a failure mode (FM-M1..M13), with the R1 keying
  guaranteeing the legacy path cannot regress.

## Provenance / verification key

- **[verified]** — established by reading the cited code at HEAD `d664589d7f` (recon t60.e1,
  feasibility t60.e2).
- **[inferred]** — reachability- or protocol-dependent; consistent with the code but not proven
  without execution (FM-G3's discard protocol; M2.a-6's masking reachability; the D18
  counterfactual's post-Track-5 behavior). Tagged inline.

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
  description can cite it.

---

## Rulings — user design review (pending)

<!-- Reserved. Filled when the user rules on the Open Questions above and approves/amends the
drafts. Binding inputs to the adversarial review and step decomposition. -->

---

## Amendments — adversarial review (reserved)

<!-- Reserved for adversarial-pass triage against the agreed design, per the Track 7 precedent
(pass reports + user-approved triage; reversed decisions amend the drafts above explicitly,
superseded sentences named). Empty until the first pass lands. -->
