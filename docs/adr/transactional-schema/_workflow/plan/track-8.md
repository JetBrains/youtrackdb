<!-- workflow-sha: 3e9c22298dfe68d2980646704850c781f8af88d5 -->
# Track 8: Genesis bootstrap and schema-format migration (D18, D20)

## Purpose / Big Picture
After this track, a fresh database bootstraps its schema and default users through
the new transactional path, and an existing database moves to the per-class-record
format through an operator-driven export/import that fails loudly rather than
silently on any partial result.

**Move-2 scope triad (vs the frozen In-scope list below; WI8d carry from the design doc):**

- **ADDED:** the R3 storage-embedded blob collections — `AbstractStorage.doCreate` creates
  `$blob<i>` inside the storage-create WAL atomic op and `SharedContext.create`'s blob loop
  becomes register-only (ruling R3; neither file is in the frozen In-scope list — this is the
  recorded scope expansion); the G1 bootstrap-valid empty-schema root in `SchemaShared.create`
  (+ the Q-G3 conditional IM-root symmetry fix); genesis-failure containment in
  `YouTrackDBInternalEmbedded.createStorage` (cleanup-on-exception, open-time
  genesis-completion marker, drop-path exemption — design §A1/CN54/CS45); the Q-M1
  spill-threshold knob in `GlobalConfiguration`; the M2.a-7 schema-section version-field
  rewrite; the WI3 operator migration-procedure page under `docs/`; the WI5 id-renumbering
  de-risk sweep.
- **MODIFIED:** the manifest is an in-dump trailing section (ruling R2), not a sidecar file —
  the frozen "temp + fsync + rename" signature transfers to the dump file itself (amended
  promote recipe: file force + REPLACE_EXISTING rename + parent-dir fsync); import strictness
  keys off the dump's declared exporter version (rulings R1/Q-M2/SR2 — `== 15` strict,
  `>= 16` reject-with-redirect, undeclared/malformed rejected fail-closed), with no separate
  migration flag; the open-time version gate is CONFIRMED as Track 2's deliverable (this track
  only pins the redirect), matching the frozen out-of-scope note.
- **REMOVED:** two clauses of this file's frozen Validation bullet 2 are SUPERSEDED by ruling
  R1 (design-doc §"Frozen-plan supersession record (pass-1 WC1)"): "a complete legacy dump
  missing any expected section is refused" and "a legacy dump requires the explicit
  unverified-import acknowledgment flag" — declared-legacy (`<= 14`) dumps keep today's
  lenient behavior; the ack gate keys off the v15 best-effort marker. **No test names may be
  generated from the superseded clauses** (see the annotation in §Validation and Acceptance).
  Nothing else is dropped from the frozen scope.

This track packs two terminal autonomous units under the footprint ceiling.
**Genesis (D18):** restructure `SecurityShared.create` and the sibling metadata
creators into a schema transaction (building `OUser.name` at commit) followed by a
data transaction that inserts the default roles and users — the end-to-end smoke test
of the Part-1 core. **Migration (D20):** add the operator-driven export/import that
migrates the per-class-record format change, with a manifest written last, whole-stream
gzip validation, a version reject-and-redirect gate on open, an `EXPORTER_VERSION`
bump to 15, and per-record spill-to-temp. The two units share no files; the packing
justification is in `## Interfaces and Dependencies`.

## Progress
- [x] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion
- [x] 2026-07-23T12:00Z [ctx=safe] Review + decomposition complete (6-step roster approved)
- [x] 2026-07-23T21:31Z [ctx=safe] Step 1 complete (commit 6611cbf6b2)
- [x] 2026-07-23T23:30Z [ctx=safe] Step 1 review-fix iteration 1 complete (commit 931e264f48;
  baseline + crash-safety reviews: 0 blockers, 1 should-fix (CS47, docs), 8 suggestions — all
  applied or dispositioned)
- [x] 2026-07-24T02:52Z [ctx=safe] Step 2 complete (commit 908a2374e6; red-first shown; Q-G3
  verdict: GREEN, no IM symmetric fix)
- [x] 2026-07-24T04:15Z [ctx=safe] Step 2 review-fix iteration 1 complete (commit 1858811c1e;
  baseline + crash-safety reviews: 0 blockers, 0 should-fix, 7 suggestions — all applied or
  dispositioned)
- [x] 2026-07-24T09:22Z [ctx=safe] Step 3 complete (commit 4d23111516; CS50/W6 window CLOSED;
  three necessary enablers surfaced — see Episodes)
- [x] 2026-07-24T14:29Z [ctx=safe] Step 3 review-fix iteration 1 complete (commit 6b61334581;
  baseline + crash-safety + concurrency reviews: 0 blockers, 2 should-fix (CS52, CQ17), ~15
  suggestions — all applied or dispositioned)
- [x] 2026-07-24T19:16Z [ctx=safe] Step 4 complete (commit 2433d684ae; red-first M.5 #1 shown;
  one as-built deviation: a minimal manifest-consuming arm in DatabaseImport — see Episodes)

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->

- **2026-07-23 (Step 1, WI5 sweep):** the static `#\d+:\d+` grep found NO layout-sensitive
  fixtures (54 files carry RID literals; all are parser-level, synthetic in-memory RIDs,
  nonexistent-record probes, or collection 0 = `internal`, which keeps its id), and NO stored
  dump fixtures exist anywhere in the repo (every export/import test generates its dump at
  runtime — regenerable by construction). The real casualties were invisible to the recipe's
  grep pattern: three tests pinned fresh-DB genesis RIDs in **constructor form**
  (`new RecordId(c, p)`), which `#\d+:\d+` cannot match. `CommandExecutorSQLTruncateTest`
  (`#1:3`) and `EntityImplTest` (`#2:1`) accidentally depended on genesis security records that
  existed at those slots under the old layout (the `$blob*` renumbering turned the loads into
  `RecordNotFoundException`/null); `StringsTest` (tests module) pinned a provisional RID's
  collection id (`#7:-2` → `#15:-2`) in a toString assertion. All three were made
  layout-independent (dynamic RID resolution) per the fixture rule. Future sweeps should grep
  `new RecordId(` with literal arguments too.
- **2026-07-23 (Step 1):** the WI5-named suites `DbImportExportTest` and
  `DbImportStreamExportTest` are entirely `@Disabled` at HEAD ("not included in active test
  suite") — pre-existing, so the suite-run leg of the sweep for them is vacuous; the renumbered
  round-trip coverage comes from `DatabaseExportImportRoundTripTest` (green) and the core
  `DatabaseImport*` tests (green).
- **2026-07-23 (Step 1, verification-methodology trap):** running `./mvnw -pl tests clean test`
  WITHOUT `core` in the reactor resolves `youtrackdb-core` from `~/.m2` — which held a
  January-2026 snapshot predating Track 7's Step-2 reload guard — producing a phantom
  deterministic `EmbeddedTestSuite.testQueryCount` failure (the exact defect Track 7 commit
  fafac7e8b3 fixed) that was initially misread as a standing red at HEAD. With core in the
  reactor (`-pl core,tests`) the module is fully green. Rule: never run the `tests` module
  without `core` in the same reactor invocation.
- **2026-07-23 (Step 1, Q-G3 pre-observation — CORRECTED by review CS47):** ~~genesis blob-set
  persistence at HEAD relies on a schema-root save that happens downstream of
  `SharedContext.create`'s blob loop (the loop itself only mutates the in-memory set)~~ — that
  claim was WRONG. The traced mechanism (crash-safety review, CS47): **each
  `SchemaShared.addBlobCollection` call self-commits the schema root synchronously, per
  iteration**, via `releaseSchemaWriteLock(session)` (default `iSave=true`) → `saveInternal` →
  `session.executeInTx(toStream)`; NOTHING downstream of the loop saves the schema. The
  register-only rewrite keeps that identical call and path (only the id source changed), and
  the new test pins the persisted root payload directly. Step-3 consequence: the loop can NOT
  be tx-wrapped "unchanged" — under an active transaction the direct `SchemaShared` call's
  `saveInternal` throws `SchemaException` ("Cannot change the schema while a transaction is
  active"); the registration must be re-routed through `SchemaProxy.resolveForWrite()`/the
  session (design G2.b's letter) when Step 3 wraps it (seam annotation added to Step 3's spec).
- **2026-07-23 (Step 1, review CS48):** the FM-M16 importer blob-id-mapping window is ARMED
  in-tree from commit 6611cbf6b2 until Step 5's §A3 fix lands: `DatabaseImport.importSchema`
  (`DatabaseImport.java:528-541`) resolves a dump's raw blob-collection ids in the target id
  space, so importing a blob-bearing pre-Track-8 dump into a fresh (renumbered) target
  misregisters class collections as blob collections. Design-acknowledged sequencing (FM-M16,
  pinned by M.5 #13) — do not trust blob-bearing legacy-dump imports on this branch mid-track.
- **2026-07-24 (Step 2, review CS50):** the CS35-accepted W6 silent-reopen window is ARMED
  in-tree from commit 908a2374e6 until Step 3's genesis-completion marker lands: a crash in the
  K4 window (schema + IM root shells and pointers durable, BEFORE the first `security.create`
  DDL self-commit) now reopens with ZERO signal — the bootstrap-valid root parses cleanly, so
  the pre-fix "Database's schema is empty!" error-log breadcrumb never fires (the branch itself
  stays in code for legacy corpses). Design-accepted trade (CS35, folded into §A1); Step 3's
  open-time marker check closes it — `crash-safety-step2-iter1.md`'s K4/W6 row is the exact
  state the marker must refuse. **CLOSED by Step 3 (commit 4d23111516):** the marker check
  refuses exactly that state on every session-minting open.
- **2026-07-24 (Step 3, pre-existing defect found + fixed):** the schema-carry commit SILENTLY
  LOST committed classes when the schema root's `classes` link set was btree-backed
  (`LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD` at/below the class count): the
  threshold-triggered conversion fires MID-SERIALIZATION inside the commit window — after the
  commit gathered its working set and link-bag locks — so the converted bag's btree content
  missed the commit. Reproduced at HEAD `df7b3b2841` with a plain user schema tx (threshold=-1,
  in-tx `createClass`, commit → class gone from the committed schema). Pre-existing since the
  commit-time schema promotion landed (Track 4/5); the restructured genesis made it
  deterministic in three storage-test classes that set threshold=-1. Fixed by pinning link sets
  owned by internal-metadata-collection records EMBEDDED (`EntityLinkSetImpl`; bounded
  cardinality; the schema must parse at open without btree components). Databases whose root
  set is ALREADY durably btree-backed (>threshold classes, created pre-pin) keep their form —
  their schema-carry commits remain exposed to the underlying commit-window/btree-bag
  interaction, which still deserves a root fix by the commit-machinery owners.
  **Extended by the Step-3 reviews (BG17/CN57(b)/CS57):** the follow-up must also own (a) the
  ownerless `EntityLinkSetImpl` constructors (`newLinkSet()`, the delta-serializer site) that
  bypass the constructor half of the pin — no live path attaches such a set to a metadata
  record today; (b) `LinkBag`'s conversion, unsuppressed — safe today only because
  metadata-owned LinkBags are cardinality-1 tracker back-bags; (c) the btree→embedded
  down-conversion arm — now ALSO suppressed for metadata records (commit 6b61334581), but the
  pre-pin-btree-root exposure under a non-default bottom threshold belongs to the same root
  fix. The follow-up issue record is being drafted by the orchestrator.
- **2026-07-24 (Step 3 review-fix, pre-existing observation):** the storage OPEN-FAILURE path
  leaks partially-initialized native resources (WAL/disk-cache direct-memory buffers are not
  released when `storage.open` throws mid-initialization, e.g. on a W1/W2 corpse with only a
  `dirty.fl` file) — under the test harness's direct-memory tracking the leak kills the JVM at
  shutdown, which is why the CS54 never-opened-corpse discard fix carries no drop-level suite
  test. Pre-existing (independent of Track 8); recorded for a follow-up.
- **2026-07-24 (Step 3 review CS56, recorded boundary):** `restore(...)` is OUTSIDE the
  genesis-marker belt — the restored database's marker state is the BACKUP's, and a mid-restore
  crash can leave a marker-bearing half-restored database that opens silently (the pre-existing
  restore crash envelope; the marker was never specified to gate restores). Restoring a backup
  taken from a pre-marker build yields a refused database after a successful restore (same
  dev-only acceptance as §A1's compatibility note). Optional hardening (clear-then-reset around
  `restoreFromBackup`) left to a follow-up; also noted in design-drafts §A1.
- **2026-07-24 (Step 3, new fixture-casualty class):** two storage tests
  (`BTreeGetVisibleTest`, `SharedLinkBagBTreeReadMethodsTest`) pinned absolute snapshot
  timestamps/versions (25–50) that implicitly depended on the DOZENS of atomic-operation ids
  the legacy per-DDL genesis burned before the tests ran; the two-phase genesis creates a
  database in a handful of operations, so those entries suddenly sat ABOVE the visibility
  horizon ("in-flight future") and vanished from reads. Fixed by advancing the horizon
  explicitly in the fixtures (100 empty atomic ops). Sweep-recipe note: operation-id-horizon
  pinning is a third invisible-to-grep fixture class, next to Step 1's constructor-form RID
  pins.

## Decision Log
<!-- The track-canonical live decision carrier (D7). Seeded from the frozen
design.md D-records this track owns. -->

#### D18: Genesis bootstrap is two-phase — a schema tx, then a data tx
- **Alternatives considered**: one unified genesis transaction.
- **Rationale**: under the transactional model, `SecurityShared.create` and the sibling metadata creators restructure into a schema transaction that creates every internal class, property, and index (including the `OUser.name` UNIQUE index) and commits — building the indexes at commit — followed by a data transaction that inserts the default roles and admin/reader/writer users into the now-committed classes. The two-phase shape builds `OUser.name` before any user insert, so the user-creation code's direct index lookups resolve against a real engine. A unified single transaction would expose a same-tx unbuilt index to a direct (non-planner) lookup, which throws unless routed through a scan fallback.
- **Risks/Caveats**: the schema transaction engages the D7 mutex (no contention at genesis) and is the first-ever schema transaction (it seeds the tx-local copy from the empty committed schema and writes the first schema record); the following data transaction never touches schema, so it does not engage the mutex. Genesis exercises the full commit path against an empty starting schema, so it is the natural end-to-end smoke test of Part 1.
- **Implemented in**: this track
- **Full design**: design.md §"Genesis bootstrap"

#### D20: Schema-format migration is operator-driven JSON export/import, not in-place
- **Alternatives considered**: an in-place on-open migrator (carries a partial-migration crash-safety burden); backporting manifest emission to a terminal old-format release (couples the migration story to shipping one more old-format release).
- **Rationale**: export reads the logical schema (not raw record bytes) and import rebuilds through the schema API, so the new code never parses the old format and the imported database is written in the per-class-record format. There is no partial-migration state to recover. Opening an old-format database with new binaries is rejected on the schema version check (D14's bump) with a redirect to the export/import procedure; the version bump is a reject-and-redirect gate, not a migrator.
- **Risks/Caveats**: export and import must be fail-closed and a record exported whole or not at all, including its copy-out into the dump. Export emits a manifest (class/index/record counts) strictly last and atomically (temp + fsync + rename); import hard-fails on a missing or unparsable manifest, a missing expected section, or an incompletely-consumed gzip stream. The whole-stream gzip validation holds only under single-member framing and a fully-consumed check via inflater arithmetic (exhaustion probes are forbidden). The new exporter bumps `EXPORTER_VERSION` 14→15, rethrows record-scan failures by default (best-effort is an explicit opt-out recorded in the dump's info section), and promotes nothing on failure. A large but healthy record spills to a transient file beyond a memory threshold so memory stays bounded and the record is still exported, not shed. This hardening protects the next format migration, not this one.
- **Implemented in**: this track
- **Full design**: design.md §"Schema-format migration"

## Outcomes & Retrospective
<!-- Continuous-log. Empty at Phase 1. -->

## Context and Orientation
**Genesis:** `SecurityShared.create` and the sibling metadata creators today create
internal classes, properties, and indexes through per-operation self-commits, then
insert the default roles and users. The user-creation code looks each user up by the
`OUser.name` UNIQUE index directly (not through the query planner), so that index's
engine must be built before any user insert.

**Migration:** `DatabaseExport.exportSchema` walks `schema.getClasses()` from the
immutable snapshot and writes class/property/index definitions as JSON; it never
serializes the schema record's on-disk bytes. `DatabaseImport.importSchema` recreates
classes, properties, and indexes through the schema API, so an imported database is
written in whatever format the current code produces. The current exporter is a
streaming JSON writer with no terminal marker, writes `<name>.json.gz.tmp` and promotes
only in `close()` (which the failure path also runs), and converts a mid-collection scan
failure into a success exit (only `YTIOException` rethrows). The importer has a silent
plain-JSON fallback. `EXPORTER_VERSION` is 14.

This track depends on the per-class-record format (Track 2) for both units, on the
commit machinery (Track 4) for genesis, and on the commit-time index build (Track 5)
for building `OUser.name`. Migration is otherwise independent of the concurrency
machinery.

## Plan of Work
**Genesis:** split `SecurityShared.create` (and the sibling metadata creators) into a
schema transaction that creates every internal class, property, and index and commits
(building `OUser.name` at commit), then a data transaction that inserts the default
roles and users into the committed classes. The schema transaction engages the mutex
and writes the first schema record; the data transaction is an ordinary record tx.

**Migration:** turn the open-time version check into a reject-and-redirect gate (the
gate itself lands in Track 2; this track confirms the export/import is the redirect
target). Harden export: bump `EXPORTER_VERSION` to 15, rethrow record-scan failures by
default with an explicit best-effort opt-out recorded in the info section, render each
record to a bounded buffer that spills to a transient file beyond a threshold and
writes whole-or-discarded, set a completion flag only after the last section and promote
only when it is set, and emit the manifest strictly last and atomically. Harden import:
verify the manifest, add the section-presence check, validate the whole gzip stream as a
single member via inflater arithmetic, reject non-gzip input on the migration path, and
require an explicit acknowledgment flag for a best-effort-marked dump.

Ordering constraints: genesis must build and commit `OUser.name` before the data
transaction's first user lookup; migration's manifest must be the last thing written and
the dump fsynced before the manifest becomes visible; the completion flag gates the
promote-rename so a truncated dump is never promoted.

## Concrete Steps

> **Authoritative design of record:** `track-8-design-drafts.md` (sibling of this file in
> `plan/` — unlike Track 7's drafts, which sit one level up) in full — base Drafts G/M,
> the §0 locked rulings (R1–R4 + the honored-not-owned/MOOT scope boundary), the 2026-07-23
> Rulings (Q-G1..Q-G3, Q-M1..Q-M3, Admin) including the Supplementary rulings SR1/SR2, the
> §Amendments pass-1 triage (§A1 genesis-failure containment, §A2 import pre-flight deferral,
> §A3 blob-id mapping, the 32-finding disposition table, the WC1 frozen-plan supersession
> record), and the §Gate verification iteration 1 micro-amendments (gate-1 CS45, CS46, CN54,
> WI11, WI12, WC6, OBS-2). Where this file's frozen `## Plan of Work`, `## Decision Log`, and
> `## Validation and Acceptance` describe the pre-ruling design (a sidecar manifest; a legacy
> ack flag; "three coarse" genesis crash states; a `>= 15` strict arm), the amended design
> supersedes them — most prominently the WC1 supersession of Validation bullet 2 (no test
> names from the superseded clauses). The steps below cite the design sections, rulings, and
> finding IDs each discharges; "pin G.5/M.5 #n" refers to the design doc's test-pin lists.
> The 6-step roster below is USER-APPROVED (2026-07-23); implementation has not started (all
> commit slots `_pending_` until each step lands).

**No standing red-at-HEAD tests exist for this track** (unlike Track 7). The design's three
red-first candidates are new tests that must be written and shown red before their fix lands:
pin G.5 #1 (virgin-DB schema-tx seed → Step 2), pin M.5 #1 (injected-scan-failure export
promote → Step 4), pin M.5 #3 (truncated-gzip import → Step 5).

1. **R3 de-risk sweep + storage-embedded blob collections (register-only loop)** (design G2.b;
   ruling R3; pass-1 CN50, WI5, WI8d; FM-G4/FM-G5/FM-G6). Run the WI5 executable sweep FIRST
   (static grep of tests/fixtures/stored dumps for `#\d+:\d+` RID literals and hard-coded
   collection ids — before any code change), then land the storage embed: `doCreate` creates
   `$blob0..N-1` next to the `internal` collection inside the same WAL atomic op, reading
   `STORAGE_BLOB_COLLECTIONS_COUNT` exactly ONCE from the create-time `contextConfiguration`
   (CN50); `SharedContext.create`'s blob loop becomes register-only by enumerating the
   storage's actual `$blob*` collections by name (no second config read — CN50), still on the
   legacy self-commit path at this step. Then run the named suites against the renumbered
   layout and fix flagged fixtures (regenerable fixtures re-exported; stored dumps stay valid
   by name-mapping). — risk: medium (Test blast-radius; Crash-safety — inherits the
   storage-create envelope)  [x]  commit: 6611cbf6b2
   - **Goal:** blob collections become storage-birth system collections atomic with create;
     the genesis blob step shrinks to a pure schema registration; the collection-id
     renumbering blast radius is measured and cleared BEFORE the two-phase restructure.
   - **In-scope files:** `core/.../storage/impl/local/AbstractStorage.java` (`doCreate` blob
     loop next to `:1510`); `core/.../db/SharedContext.java` (register-only name-enumeration
     loop); test fixtures flagged by the sweep.
   - **Discharges:** ruling R3; design G2.b incl. the single-read pin (CN50); WI5 recipe steps
     (1)-(3); the WI8d triad recording (done in this file's Purpose section); FM-G4/FM-G5
     rows.
   - **Tests (design pins):** G.5 #7 (blob registration equals the storage-created `$blob*`
     ids; blob record round-trip, both profiles); G.5 #8 (the sweep itself — ordering, named
     suites, grep, fixture rule).
   - **Verification:** static grep sweep first (no build); then
     `./mvnw -pl core clean test -Dtest=DatabaseExportImportRoundTripTest` plus the new blob
     tests; `./mvnw -pl core,tests clean test` (runs `DbImportExportTest` /
     `DbImportStreamExportTest` in the suite); storage-create + backup/restore paths touched →
     `./mvnw -pl core clean verify -P ci-integration-tests` (`StorageBackup*`,
     `LocalPaginatedStorageRestore*` ITs).
   - **Depends on / seam ownership:** first step; no dependency. OWNS `AbstractStorage.doCreate`
     (no later step touches it) and the *mechanics* of `SharedContext.create`'s blob loop;
     Step 3 later wraps that loop into the phase-1 transaction (tx-wrapping is Step 3's seam,
     loop mechanics are this step's). **CS47 correction:** "unchanged" was inaccurate — the
     tx-wrap requires re-routing the registration call (see the seam annotation in Step 3).

2. **G1 bootstrap-valid empty-schema root + Q-G3 IM-root symmetry verify** (design G2.a; ruling
   Q-G3; FM-G1; pass-1 WC2 pin split, WC4 hand-off). Make `SchemaShared.create` persist the
   bootstrap-valid empty-schema payload — the `toStream` shape for an empty schema
   (`schemaVersion` 6, empty `classes` link set, empty `globalProperties`, `collectionCounter`
   0, empty `blobCollections`) — routed through `toStream` itself (single serializer, no twin
   format), inside `create`'s existing `computeInTx`. Execute the Q-G3 verify-first check: if
   the genesis commit's index reconciliation or the reopen load chokes on the empty
   `IndexManagerEmbedded` root shell the way `copyForTx` does on the schema root, extend the
   fix symmetrically; record the verdict in this file (Episodes). — risk: medium (Crash-safety /
   schema-format compatibility)  [x]  commit: 908a2374e6
   - **Goal:** any schema transaction opened against a virgin database seeds cleanly
     (`copyForTx`'s `globalProperties` precondition, `SchemaShared.java:300-301`, holds from
     the instant the root exists) — the enabler for the genesis schema tx.
   - **In-scope files:** `core/.../metadata/schema/SchemaShared.java` (`create`);
     `core/.../index/IndexManagerEmbedded.java` (ONLY if the Q-G3 verify is red); new genesis
     bootstrap test class.
   - **Discharges:** design G2.a (mandatory enabler); FM-G1; ruling Q-G3 (verify-first arm);
     the WC4 design-final hand-off is recorded, not implemented (design.md reconciliation is
     Phase 4's).
   - **Tests (design pins):** G.5 #1; G.5 #2(a) (unit-level: the persisted root parses as the
     bootstrap-valid shape before phase 1 runs).
   - **Verification:** `./mvnw -pl core clean test -Dtest=<genesis bootstrap test class>`;
     schema-record format touched → `./mvnw -pl core clean verify -P ci-integration-tests`.
   - **Red-first / acceptance:** pin G.5 #1 — *virgin-DB schema tx seeds cleanly* — is written
     FIRST and shown RED at HEAD (the `copyForTx:300` assert trips in test builds; the
     `fromStream:887-894` error branch fires in production shape); it must go green with the
     payload fix.
   - **Depends on / seam ownership:** independent of Step 1 (ordered after it so the sweep
     baseline predates all schema-side churn); MUST precede Step 3. Sole owner of
     `SchemaShared.create`.

3. **Two-phase genesis restructure + failure containment** (design G2.c; rulings Q-G1/Q-G2;
   I-U4; design §A1 — CS34+CN48+CS35+CS36 (+CS37 record), gate-1 CS45 W9a acceptance, gate-1
   CN54 drop-path exemption; pass-1 WI9, CN53 constraint; FM-G2/G3/G5/G7/G8). Restructure
   `SharedContext.create`: phase 1 = ONE schema transaction spanning the `SecurityShared.create`
   DDL half, the sibling creators' DDL (OFunction/OSequence/OSchedule), the O/V/E classes, and
   the blob registration (Step 1's loop, now tx-wrapped — **CS47 seam annotation:** the
   registration MUST be re-routed through `SchemaProxy.resolveForWrite()`/the session when
   tx-wrapped; the Step-1 loop calls `SchemaShared.addBlobCollection` DIRECTLY, whose
   `releaseSchemaWriteLock`→`saveInternal` self-commit throws
   `SchemaException("Cannot change the schema while a transaction is active…")` under an active
   tx (`SchemaShared.java:1508-1513`); routed through the proxy, the write lands on the
   tx-local copy whose `saveInternal` returns early — design G2.b's letter. Step-3 reviewer
   obligations carried from the Step-1 reviews: re-grep that
   `STORAGE_BLOB_COLLECTIONS_COUNT` still has exactly ONE production read after the tx-wrap
   (TQ12/CN50), note the register loop's defensive name-snapshot (`List.copyOf`) exists
   because each registration self-commits while iterating (CQ14), and — from the Step-2
   reviews — verify `schema.create`/`indexManager.create` remain OUTSIDE the phase-1 genesis
   tx (CQ15: `schema.create` has the entry assert as belt — a joined outer tx would persist a
   provisional schema record id via `setSchemaRecordId`; `indexManager.create` has NO such
   assert — obligation-only)) — engaging the
   metadata-write mutex on
   first write and committing through the schema-carry path (engines built at commit); phase 2
   = ONE data transaction inserting default roles + users (Q-G2, supersedes today's two-tx
   shape). Preserve: guard-first tx-free no-op (`SecurityShared:595`), the import call site
   (`DatabaseImport.java:404`), the system-DB skip, the removed mid-create `forceSnapshot`s.
   Land the §A1 containment in the same step: cleanup-on-exception in
   `YouTrackDBInternalEmbedded.createStorage` (maps purged, storage closed, on-disk residue
   deleted, `exists()` false); the genesis-completion marker (storage-config property written
   after the phase-2 commit — "sequence ran to completion", not "users exist"); the open-time
   marker check refusing W6/W7 corpses loudly; the CN54 drop-path exemption (`drop()`'s
   internal `openNoAuthenticate` tolerates/bypasses the refusal, deletion succeeds); the CS45
   W9a fail-closed false refusal accepted and pinned. — risk: high (Crash-safety / Durability;
   Concurrency; every-DB-creation blast radius)  [x]  commit: 4d23111516
   - **Goal:** I-U4 discharged (schema built and committed before any user insert; mutex in
     phase 1 only) and a half-genesis database can neither be silently reopened nor silently
     no-op'd over by create-retry.
   - **In-scope files:** `core/.../db/SharedContext.java` (two-phase `create`);
     `core/.../metadata/security/SecurityShared.java` (DDL/data split; single phase-2 tx);
     `core/.../schedule/SchedulerImpl.java`, `core/.../metadata/function/FunctionLibraryImpl.java`,
     `core/.../metadata/sequence/SequenceLibraryImpl.java` (creators join the genesis tx;
     standalone lazy sites stay legacy per Q-G1/CN49 — live sites are
     `createFunction→init` / `createSequence→init`);
     `core/.../db/YouTrackDBInternalEmbedded.java` (cleanup-on-exception; marker write;
     open-time check; drop-path exemption); `core/.../db/DatabaseSessionEmbedded.java` (only
     if the open-path check wiring needs it); genesis test classes.
   - **Discharges:** design G2.c incl. the WI9 lock-interaction argument; rulings Q-G1 (one
     schema tx; CN49-corrected citations), Q-G2 (one data tx); §A1 in full (CS34, CN48, CS35
     signal, CS36 enumeration, CS37 record); gate-1 CS45 + CN54; FM-G2/G3/G5/G7/G8;
     invariant I-U4.
   - **Tests (design pins):** G.5 #2(b) (reopen shows the genesis-POPULATED schema); #3 (mutex
     engaged phase 1, not phase 2); #4 (engine-before-insert — `OUser.name` built, users found
     via the index); #5 (import-nested create + guard no-op); #6 (system-DB genesis, NO
     parallel first-touch per CN53/OBS-2); #9 (containment against the W-states: exception
     cleanup incl. `failIfExists=false` re-create; W6/W7 refusal on `open`/`openNoAuthenticate`;
     `drop()` discards without surfacing the refusal; W9a accepted false refusal; marker present
     on success, both profiles); #10 (phase 2 commits exactly once).
   - **Verification:** `./mvnw -pl core clean test` (genesis touches every DB creation — full
     core unit run, not a targeted subset); `./mvnw -pl core,tests clean test`;
     `./mvnw -pl core clean verify -P ci-integration-tests`.
   - **Depends on / seam ownership:** depends on Steps 1 and 2. OWNS the tx-wrapping of
     `SharedContext.create` (Step 1's enumeration mechanics; the registration call is re-routed
     per the CS47 seam annotation above) and everything in
     `YouTrackDBInternalEmbedded` (no other step touches it). Does NOT touch
     `AbstractStorage.doCreate` (Step 1's seam) or `DatabaseExport`/`DatabaseImport`
     (Steps 4-6). The genesis-session double-`callOnCreateListeners` observation (CN
     observations row) is recorded: genesis tests must not pin a single listener invocation.

4. **Export hardening + validated-gzip primitive** (design M2.a-1..7; pass-1 CS40, CS41, CS43,
   CN51 export side, CN52, WI7, WI8a/b/c; ruling Q-M1; FM-M1..M5, M9, M15, M17). Bump
   `EXPORTER_VERSION` to 15; rethrow-by-default with the best-effort opt-out recorded as the
   info-section marker; per-record bounded buffer with the Q-M1 `GlobalConfiguration`
   spill-threshold knob (default tens of MB) and whole-or-discarded copy-out;
   completion-flag-gated promote with NO upfront final-name delete (CS41) and a per-export
   unique `CREATE_NEW` temp file (CN52); in-dump trailing manifest, shape pinned (WI8c:
   `"manifest"` tag; `classes`/`indexes`/`records`/`brokenRids` totals) with exporter-tallied
   count provenance (CN51 — never re-derived from a fresh snapshot); the CS40 promote recipe
   (reopened-channel `force(true)` → `ATOMIC_MOVE`+`REPLACE_EXISTING` → parent-dir fsync;
   fail-closed fallback) in a new `FileUtils` variant; primary-exception preservation
   (suppressed close secondaries); the schema-section version-field rewrite (M2.a-7). Build the
   **validated-gzip `GZIPInputStream` subclass in this step as an isolated primitive** with its
   own unit tests pinning the CS43 sequence (multi-member continuation disabled → decompressed
   drain to EOF → `inflater.finished()` → seekable-source arithmetic) before Step 5 wires it
   into the importer — the Track 7 primitive-before-consumer pattern. — risk: high
   (Crash-safety / Durability)  [x]  commit: 2433d684ae
   - **Goal:** a failed or crashed export can never promote, mask its primary failure, corrupt
     a record into the dump, or destroy the operator's previous dump; the gzip validator
     exists, isolated-tested, ready for import wiring.
   - **In-scope files:** `core/.../db/tool/DatabaseExport.java`;
     `core/.../common/io/FileUtils.java` (fsync-capable move);
     `core/.../api/config/GlobalConfiguration.java` (spill knob);
     new `core/.../db/tool/…GZIPInputStream` subclass;
     `core/.../db/tool/DatabaseImpExpAbstract.java` (best-effort option plumbing — options-only,
     packing premise preserved); export + primitive test classes.
   - **Discharges:** M2.a-1..7; CS40/CS41/CS43(primitive)/CN51(export)/CN52; WI7; WI8a/b/c;
     Q-M1; FM-M1/M2/M3/M4/M5/M9/M15/M17.
   - **Tests (design pins):** M.5 #1, #2 (default abort vs best-effort discard-whole), #8
     (spill + boundary + spill-file deletion), #17 (fsync-move on promote; unflagged close
     never renames; concurrent exporters; self-consistent manifest under concurrent DDL); CS43
     primitive unit tests (valid / truncated / trailing-garbage / multi-member / corrupt-trailer
     fixtures at the stream level).
   - **Verification:** `./mvnw -pl core clean test -Dtest=DatabaseExportImportRoundTripTest`
     plus the new export/primitive test classes; `./mvnw -pl core,tests clean test`;
     import/export ITs → `./mvnw -pl core clean verify -P ci-integration-tests`.
   - **Red-first / acceptance:** pin M.5 #1 — *injected scan failure* — written FIRST and shown
     RED at HEAD (`DatabaseExport.java:212-239` swallows; `close()` in the `finally` promotes
     unconditionally at `:291`); green = loud primary, final name untouched, nothing promoted.
   - **Depends on / seam ownership:** independent of Steps 1-3 (disjoint files); ordered after
     them to keep the migration fixtures (which Step 5-6 tests consume) generated on the
     final genesis/collection layout. OWNS `DatabaseExport` wholly; produces the gzip
     primitive that Step 5 consumes; does NOT touch `DatabaseImport`.

5. **Import hardening — pre-flight deferral, v15 structural strictness, blob-id mapping**
   (design M2.b-1..4; §A2 CS38 + gate-1 WI11; §A3 WI1; pass-1 WI6, WI10a/b/c, CN51 import
   side; rulings SR1, SR2 (+gate-1 CS46 trigger); FM-M6/M7/M8/M13/M14/M16). Defer the entire
   preamble block `DatabaseImport.java:214-:223` INCLUSIVE (plus `removeDefaultCollections`
   via both call sites) until the info section parses and pre-flight validation passes (WI11);
   wire the SR2 trigger (first non-`info` tag or EOF without a parseable exporter-version →
   reject); arm the strict structural matrix for `== 15`: non-gzip rejection (Q-M3, no
   override), whole-stream validation via Step 4's subclass, manifest verify against
   importer-tallied consumption counts (CN51), section presence incl. duplicates (WI10c) and
   brokenRids-without-marker (WI10b), the version-gated `manifest` tag (WI6), the best-effort
   ack gate (M2.b-4); route the dump's blob-collection ids through
   `collectionToCollectionMapping` / `$blob*` name-match (§A3, WI1). **Resolve the WI10a
   stream-ctor validation scope here as an explicit obligation** — pin which checks the
   InputStream ctor applies (sequence steps (1)-(2); the size arithmetic (3) requires a sizable
   source) and record the decision in this file. SR1's condemn-target doctrine governs the
   structural rejections. — risk: high (Crash-safety; compatibility blast radius on the
   lenient path)  [ ]  commit: _pending_
   - **Goal:** a v15 dump that is truncated, tampered, incomplete, or unacknowledged-best-effort
     can never import silently; ruled pre-flight rejections precede ALL target mutation; a
     declared-legacy dump behaves byte-for-byte as at HEAD.
   - **In-scope files:** `core/.../db/tool/DatabaseImport.java`; import test classes + fixture
     dumps (v15 valid/corrupt shapes generated via Step 4's exporter; a v14-layout dump WITH
     blob content for pin #13).
   - **Discharges:** §A2 (CS38, WI11), §A3 (WI1), M2.b-1..4, WI6, WI10a/b/c, CN51(import),
     SR2+CS46 trigger wiring, SR1 scope; FM-M6/M7/M8/M13/M14/M16.
   - **Tests (design pins):** M.5 #3, #4 (trailing garbage / multi-member), #5 (missing or
     count-mismatched manifest, missing/duplicated sections), #6 (plain-JSON v15 refused;
     plain-JSON declared-v14 accepted), #7 (ack gate both directions), #13 (cross-layout blob
     import), #16 (structural-rejection condemnation — loud failure asserted, target-clean NOT
     asserted); the declared-v14 lenient half of #11.
   - **Verification:** `./mvnw -pl core clean test -Dtest=DatabaseImportTest,DatabaseExportImportRoundTripTest,DatabaseImportSimpleCompatibilityTest`
     plus new fail-closed classes; `./mvnw -pl core,tests clean test`;
     `./mvnw -pl core clean verify -P ci-integration-tests`.
   - **Red-first / acceptance:** pin M.5 #3 — *truncated gzip dump imports silently* — written
     FIRST and shown RED at HEAD (ctor fallback `:136-143`; no consumption check); green =
     loud rejection.
   - **Depends on / seam ownership:** depends on Step 4 (consumes the gzip primitive and
     v15 fixture dumps). OWNS `DatabaseImport`'s structural skeleton: the section loop, the
     deferral block boundary (`:214-:223`), the dispatch, and the manifest/section machinery.
     Step 6 confines itself to the `importInfo` field-validation internals and rejection
     messages — a disjoint seam inside the same file; Step 5 lands first to absorb the
     skeleton churn.

6. **Q-M2/SR2 info-validation matrix + operator migration-procedure doc + end-to-end
   rehearsal** (design M2.b-5; ruling R4 + Q-M2 full matrix + SR2; gate-1 WI12a/b, WC6;
   pass-1 WI3 (+CS44 fold); FM-M10/M11/M12). Implement the ruled info-field matrix inside the
   deferred pre-flight: exporter-version dispatch (`<= 14` lenient / `== 15` strict / `>= 16`
   reject-with-redirect naming both versions / undeclared-or-malformed rejected per SR2);
   `schema-version` mandatory, `MIN_IMPORTABLE (6) <= declared <= CURRENT (6)` as a
   one-constant-bump range; mandatory fields = exporter-version + schema-version; known
   optional fields type-checked if present (WI12b); unknown fields tolerated + logged; all
   rejection messages name declared vs supported values; dangling-field parse rejection.
   Author the `docs/` operator migration-procedure page (WI3, folding CS44 and the SR1
   doctrine: export-exit-status gate; fresh out-of-service target; ANY failure condemns the
   target; import completeness = importer exit 0; **plus, per Step-3 review CN59: the
   genesis-incomplete refusal's operator guidance — incl. that a crashed OSystem-database
   genesis refuses server startup loudly until the corpse directory is discarded, with no
   automated self-heal**) and index it in `docs/README.md`. Close with
   the end-to-end rehearsal (pin #12) and the compatibility round-trips (#11). — risk: medium
   (Compatibility / validation; documentation-sync)  [ ]  commit: _pending_
   - **Goal:** every cell of the ruled Q-M2/SR2 matrix has an implemented, tested outcome; the
     operator procedure the fail-closed story leans on exists and is indexed.
   - **In-scope files:** `core/.../db/tool/DatabaseImport.java` (`importInfo` validation matrix
     + messages only — Step 5 owns the skeleton); `docs/` migration-procedure page +
     `docs/README.md` index entry; matrix test class + fixtures.
   - **Discharges:** M2.b-5; R4; Q-M2 (all four ruled items); SR2 incl. the CS46 trigger
     wording; WI12a/b; WC6 (the scoped "before mutation" contract as implemented); WI3 + CS44;
     FM-M10/M11/M12.
   - **Tests (design pins):** M.5 #9 (dangling field), #10 (old-format open → redirect — pins
     Track 2's gate as this track's entry), #11 (v14 + streaming round-trips unchanged), #12
     (end-to-end rehearsal with `DatabaseCompare`-level equivalence + manifest verified), #14
     (version cells incl. malformed — WI12a), #15 (fields, unknown-field tolerance, pre-flight
     leaves target unmutated — SR1 scope boundary), #18 (doc exists with the mandated content).
   - **Verification:** `./mvnw -pl core clean test -Dtest=DatabaseImportTest,<matrix test class>`;
     `./mvnw -pl core,tests clean test`; final full-track check
     `./mvnw -pl core clean verify -P ci-integration-tests`.
   - **Depends on / seam ownership:** depends on Step 5 (the deferred pre-flight seam it fills)
     and Step 4 (fixtures). `importInfo` internals + messages are this step's seam;
     no other file shared.

**Step ordering rationale and cross-step dependencies.** Steps run sequentially. Step 1 goes
first because WI5's recipe demands the static sweep before ANY code change and the R3 embed
before the suites re-run; its renumbered layout is the baseline every later fixture assumes.
Step 2 (G1 enabler) must precede Step 3 (the genesis schema tx cannot seed without it) and sits
after Step 1 only to keep the sweep baseline clean of schema churn. Step 3 packs the two-phase
restructure WITH the §A1 containment because the containment's states (W6/W7) only exist once
the two-phase shape exists — landing them apart would ship a crash window with no belt. Steps
4-6 are the migration unit: Step 4 before Step 5 because the importer consumes both the gzip
primitive and v15 fixture dumps the exporter produces (Track 7's primitive-before-consumer
pattern, CS43 tested in isolation first); Step 5 before Step 6 because Step 6 fills the
pre-flight seam Step 5 creates (`importInfo` matrix inside the deferred block) — same file,
disjoint seams, skeleton-first minimizes churn. Genesis (1-3) and migration (4-6) units share
NO production files (the design's packing premise, re-verified after amendments: WI1's fix
lands in `DatabaseImport.java`, Draft M's file). Seam-ownership summary: `AbstractStorage.doCreate`
+ blob-loop mechanics → Step 1; `SchemaShared.create` → Step 2; `SharedContext.create`
tx-wrapping + `SecurityShared` + creators + `YouTrackDBInternalEmbedded` → Step 3;
`DatabaseExport` + `FileUtils` + `GlobalConfiguration` + the gzip primitive → Step 4;
`DatabaseImport` skeleton (section loop, deferral `:214-:223`, dispatch, manifest machinery) →
Step 5; `DatabaseImport.importInfo` matrix + messages + the `docs/` page → Step 6. No design
pin is orphaned: G.5 #1-#2a → Step 2; #2b, #3-#6, #9, #10 → Step 3; #7-#8 → Step 1; M.5 #1,
#2, #8, #17 (+CS43 primitive) → Step 4; #3-#7, #13, #16 (+#11 lenient half, WI10a) → Step 5;
#9-#12, #14, #15, #18 → Step 6.

## Episodes
<!-- Continuous-log. Empty at Phase 1. -->

### Step 1 — commit 6611cbf6b2, 2026-07-23T21:31Z [ctx=safe]
**What was done:** (1) The WI5 de-risk sweep ran FIRST, before any code change: static grep of
tests/fixtures for `#\d+:\d+` RID literals (54 files, all categorized layout-insensitive) and
hard-coded collection ids (all on synthetic in-memory RIDs); no stored dump fixtures exist in
the repo. (2) The R3 storage embed: `AbstractStorage.doCreate` creates `$blob0..N-1` via
`doAddCollection` in the same WAL atomic operation as the `internal` collection (next to the
`:1510` precedent, shared by both storage profiles), reading `STORAGE_BLOB_COLLECTIONS_COUNT`
exactly once from the create-time `contextConfiguration` (CN50); the fresh-DB layout becomes
`internal` = 0, `$blob0..N-1` = 1..N, class collections from N+1. (3) `SharedContext.create`'s
blob loop rewritten register-only: it enumerates the storage's actual `$blob*` collections by
name (`\$blob\d+` pattern against `storage.getCollectionNames()`, no second config read — CN50)
and calls `schema.addBlobCollection` for each resolved id, still on the legacy self-commit path
(tx-wrapping is Step 3's seam). (4) The renumbered-layout suite runs flagged three
constructor-form RID pins the static grep could not see — `CommandExecutorSQLTruncateTest`
(`new RecordId(1, 3)`), `EntityImplTest` (`new RecordId(2, 1)`), `StringsTest` (`#7:-2`
provisional-RID toString) — all fixed layout-independent per the fixture rule (see Surprises).
(5) New `StorageEmbeddedBlobCollectionsTest` (4 tests, pin G.5 #7): registration equality
(schema set == storage-created `$blob*` ids, in memory AND on the persisted root record), the
pinned id layout, blob record round-trip on BOTH profiles, a full disk close/reopen, and the
frozen-at-birth count semantics (custom create-time count 3 vs process default 8).

**Key files:** `core/.../storage/impl/local/AbstractStorage.java` (`doCreate` blob loop);
`core/.../db/SharedContext.java` (register-only loop + name pattern);
`core/.../db/StorageEmbeddedBlobCollectionsTest.java` (new); fixture fixes in
`CommandExecutorSQLTruncateTest`, `EntityImplTest` (core), `StringsTest` (tests).

**Verification:** sweep first (no build); `./mvnw -pl core clean test
-Dtest=DatabaseExportImportRoundTripTest,StorageEmbeddedBlobCollectionsTest` → 9/9 green;
`./mvnw -pl core,tests clean test` → BUILD SUCCESS (core 17449 + 2219 sequential + 18 vmlens;
tests 1300; 0 failures); `./mvnw -pl core clean verify -P ci-integration-tests` → BUILD SUCCESS
(513 ITs — `StorageBackupTest` 14/14, `LocalPaginatedStorageRestoreFromWALIT`,
`StorageBackupMTIT` 2/2, `StorageBackupMTRestoreIT` 3/3 all green); spotless clean; coverage
gate vs origin/develop → PASSED, 88.4% line (2064/2334), 83.1% branch (949/1142).

**Surprises:** the three constructor-form RID pins (grep-invisible — recipe gap recorded); the
WI5-named `DbImportExportTest`/`DbImportStreamExportTest` are `@Disabled` at HEAD (vacuous
suite leg); a stale ~/.m2 January core snapshot produced a phantom `testQueryCount` red when
the `tests` module was run without `core` in the reactor — fully attributed, HEAD is green (all
three in `## Surprises & Discoveries`). Q-G3's verify-first item remains Step 2's (this step
only recorded a blob-set persistence baseline observation — whose mechanism claim was later
CORRECTED by review CS47: the registration self-commits the root per iteration; see the
Surprises bullet and the Step 1 review-fix episode).

**Discharges:** ruling R3; design G2.b incl. CN50; WI5 recipe steps (1)-(3); FM-G4/FM-G5
(mechanism: the whole `doCreate` body is one WAL atomic operation — no partial blob set is ever
exposed; unregistered-blob inertness unchanged pending Step 3's containment); pins G.5 #7
(implemented) and #8 (executed — the sweep itself). The WI8d Move-2 triad was already recorded
in this file's Purpose section at decomposition.

### Step 2 — commit 908a2374e6, 2026-07-24T02:52Z [ctx=safe]
**What was done:** the G1 bootstrap-valid empty-schema root (design G2.a/FM-G1) plus the Q-G3
verify-first check. **Red-first:** pin G.5 #1 (`virginDbSchemaTransactionSeedsCleanly`) was
written FIRST and shown RED at HEAD 85e517ed1f — failure signature exactly as the design
predicted: `java.lang.AssertionError: copyForTx requires a bootstrapped committed schema
carrying global properties` (the `copyForTx` bootstrapped-schema assert; test builds run
`-ea`). Pin G.5 #2(a) was red alongside it (`schemaVersion expected:<6> but was:<null>` — the
empty entity carries no payload). **Fix:** `SchemaShared.create` now writes the bootstrap-valid
empty-schema payload through `toStream` itself (single serializer — no twin format), INSIDE
create's existing `computeInTx`: the root's provisional identity is assigned before the
`toStream` call (the transaction serves the just-created record by its provisional id;
`ChangeableRecordId` promotes in place at commit), so no crash point can expose a payload-less
root. Persisted shape: `schemaVersion` = CURRENT (6), empty `globalProperties` EMBEDDEDLIST,
`collectionCounter` 0, empty `blobCollections` EMBEDDEDSET, NO `classes` link set (toStream's
empty shape leaves it unallocated — `fromStream`/`copyForTx` parse the absent set as empty).
The CS35 note is honored: the silenced `fromStream` "schema is empty" breadcrumb's replacement
(the genesis-completion marker) is Step 3's deliverable and its storage-config mechanism is
independent of the root payload — nothing Step 3 needs was destroyed.

**Q-G3 verify-first verdict: GREEN — no IM-root symmetric fix needed** (ruling's verify arm;
`IndexManagerEmbedded.java` untouched). Trace: every reader/writer of the empty IM root shell
tolerates it — `load`/`reload` parse via `getLinkSet(CONFIG_INDEXES)` which is null-guarded
(`IndexManagerAbstract.load:231-246`); the commit-time index reconciliation writes via
`getOrCreateLinkSet` (`enrollReconciledIndexRecords`, `IndexManagerEmbedded:1186-1187`); the
legacy `addIndexInternalNoLock(updateEntity=true)` path also uses `getOrCreateLinkSet`. Unlike
the schema root there is NO version field, NO parse precondition, and NO `copyForTx`-style
assert — the tx-local index view is an overlay over the in-memory committed maps, not a
root-record re-parse. Pinned empirically by
`emptyIndexManagerRootShellToleratesReopenLoad` (fresh IM instance loads the just-created
empty shell → no throw, zero indexes), which was GREEN already at HEAD in the red run.

**Key files:** `core/.../metadata/schema/SchemaShared.java` (`create`); new
`core/.../metadata/schema/GenesisSchemaBootstrapTest.java` (3 tests — pins G.5 #1, G.5 #2(a),
Q-G3 empirical arm; the virgin state is reconstructed unit-level via a fresh `SchemaEmbedded`
instance, since mid-genesis is the only production reach of that state).

**Verification:** red run first (2 failures, signatures above; IM arm green);
`-Dtest=GenesisSchemaBootstrapTest` → 3/3 green post-fix; `./mvnw -pl core,tests clean test`
→ BUILD SUCCESS (core 17454 — +3 new tests — + 2219 sequential; tests 1300; 0 failures);
schema-record format touched → `./mvnw -pl core clean verify -P ci-integration-tests` → BUILD
SUCCESS (513 ITs, 3:18 h); spotless clean; coverage gate vs origin/develop → PASSED, 90.9%
line (2117/2330), 83.0% branch (969/1168).

**Discharges:** design G2.a (mandatory enabler); FM-G1; ruling Q-G3 (verify-first arm — verdict
recorded above); the WC4 design-final hand-off stays recorded, not implemented (Phase 4's
`design-final.md` reconciliation). Surprises: none — the red-first signature and the Q-G3
outcome both matched the design's predictions exactly.

### Step 2 review-fix iteration 1 — commit 1858811c1e, 2026-07-24T04:15Z [ctx=safe]
**What was done:** applied the two Step 2 review reports
(`track-8/reviews/{baseline,crash-safety}-step2-iter1.md`; 0 blockers, 0 should-fix, 7
suggestions). (1) **CQ15 (code):** `SchemaShared.create` gains an entry precondition —
`assert !session.getTransactionInternal().isActive()` — rejecting an active outer transaction
loudly BEFORE any mutation; the message names the brick hazard (a joined outer tx leaves the
root's `ChangeableRecordId` provisional when `setSchemaRecordId` stringifies it, persisting a
provisional rid into the storage config). This is the belt against Step 3 accidentally
swallowing `schema.create` into the phase-1 genesis tx; the Step-3 reviewer must ALSO verify
`schema.create`/`indexManager.create` stay pre-tx (obligation carried alongside the existing
CS47/TQ12 items). (2) **BG13+CS51 (code, same root):** create's failure path now nulls the
`identity` field (it aliased the rolled-back root's dead provisional rid; pre-change it stayed
null), keeping the failure state identical to pre-create. Both pinned by new tests:
`createInsideActiveTransactionIsRejected` (assert fires, message named, no root allocated) and
`failedCreateLeavesNoDanglingIdentity` (injected `toStream` throw → rollback → identity null).
(3) **CQ16 (test):** the two schema tests gained the repointing-containment comment the IM test
carried. (4) **CS50 (docs):** the armed W6 silent-reopen interval recorded in §Surprises
(908a2374e6 → Step 3's marker; K4/W6 row of `crash-safety-step2-iter1.md` is the exact state
the marker must refuse). (5) **TQ14 (disposition):** fresh-context byte-deserialization of the
virgin root is deferred to Step 3's reopen pin G.5 #2(b) — the same-session cache-served read
is a fidelity MATCH to production genesis, not a gap; no code. (6) **TQ15 (disposition):**
accepted — pin G.5 #1's red signature is `-ea`-dependent by nature (the production shape
degrades to `fromStream`'s silent early return); `createdRootPersistsAsBootstrapValidEmptySchema`
is the assert-independent regression net — do not delete it believing test 1 subsumes it.

**Key files:** `core/.../metadata/schema/SchemaShared.java` (entry assert; failure-path
null-out); `GenesisSchemaBootstrapTest` (+2 tests, now 5; comments); `track-8.md` (CS50
bullet).

**Verification:** targeted `GenesisSchemaBootstrapTest` → 5/5 green; `./mvnw -pl core clean
test` → BUILD SUCCESS (17456 — +2 new tests — + 2219 sequential; 0 failures); spotless clean;
coverage gate vs origin/develop → PASSED, 90.9% line (2126/2340), 83.0% branch (976/1176).
**ITs deliberately not re-run:** the production diff is an entry assert (no-op on every valid
path; assert lines are coverage-exempt and compiled out under `-da`), a failure-path null-out
(reachable only when database creation aborts entirely), and comments — the schema-record
format and every valid-path byte are identical to the state Step 2's full IT run (513 ITs,
3:18 h, green) already verified.

### Step 3 — commit 4d23111516, 2026-07-24T09:22Z [ctx=safe]
**What was done:** the two-phase genesis restructure + §A1 failure containment. (1)
`SharedContext.create`: root shells (`schema.create`/`indexManager.create`) stay PRE-tx
(CQ15); PHASE 1 = ONE `executeInTx` spanning `security.createSecuritySchema` (the new DDL half
on `SecurityInternal`), the OFunction/OSequence/OSchedule creators (they already route via the
session proxy, so they join the tx unmodified — Q-G1/CN49), the O/V/E classes and the blob
registration — re-routed through `SchemaProxy` per the CS47 seam annotation (enumeration
mechanics + `List.copyOf` snapshot unchanged); the mid-create `forceSnapshot` is gone (the
schema-carry commit owns the trailing one). PHASE 2 = ONE merged data tx
(`security.insertDefaultSecurity`: roles + users, Q-G2; system-DB skip and
`CREATE_DEFAULT_USERS` inside; `initPredicateSecurityOptimizations` after, as today).
`SecurityShared.create` keeps the tx-free guard-first no-op and runs the same two-phase
sequence for the import call site. (2) §A1 containment: `createStorage` cleanup-on-exception
(`cleanUpFailedCreate` — maps purged, storage closed+deleted, `exists()` false, suppressed
cleanup failures); the genesis-completion marker (`SharedContext.GENESIS_COMPLETED_PROPERTY`,
written via `storage.setProperty` as the LAST act of the create sequence — its own durability
event, so the W9a window spans up to it, incl. the no-op geospatial listener); the open-time
check in `getAndOpenStorage` (`GenesisIncompleteException`, new exception class) gating EVERY
session-minting open; the CN54 drop exemption (`drop()` catches the refusal via cause-chain
walk, skips `onDrop`, deletes the corpse).

**W-state test mapping (pin G.5 #9):** exception path W1-analogues —
`failedPhaseOneCleansUpAndRetrySucceeds` / `failedPhaseTwoCleansUpAndRetrySucceeds` (REAL
phase failures injected through the config `SessionListener.onBeforeTxCommit` seam, both
profiles) + `createIfNotExistsRecreatesAfterFailedCreate` (the silent-no-op hazard); crash
path W6/W7 — `markerlessDatabaseIsRefusedOnOpenAndOpenNoAuthenticate` (marker flipped off a
complete DB — which is simultaneously the W9a shape, so the same test pins the CS45 accepted
FALSE refusal); CN54 — `dropDiscardsCorpseWithoutSurfacingRefusal`; success —
`markerPresentAfterSuccessfulCreateOnBothProfiles` (W9). W2–W5 fail loudly pre-marker as
before (crash-safety-step2's K-table) and are additionally condemned by the marker.

**Other pins:** G.5 #2(b) `reopenShowsGenesisPopulatedSchema` (full context close + disk
reopen — absorbs the TQ14 byte-deserialization deferral); #3+#10
`mutexEngagedInPhaseOneOnlyAndPhaseTwoCommitsOnce` (listener-observed: EXACTLY ONE
mutex-engaged commit; EXACTLY ONE record-carrying commit after it); #4
`oUserNameEngineIsBuiltBeforeFirstUserInsert` (engine BUILT at the phase-2 commit's
`onBeforeTxCommit` + post-create indexed lookup); #5 guard half
`repeatSecurityCreateIsTxFreeNoOp` (import path end-to-end via the green import suites); #6
`systemDatabaseGenesisCreatesSchemaWithoutDefaultUsers` (strictly sequential — CN53/OBS-2;
listener-count NOT pinned per the CN observations row).

**Necessary enablers surfaced (deviations from the frozen in-scope list, each recorded):**
(a) `Storage`/`AbstractStorage.getProperty` accessor (the marker check needs a config-property
read; `doCreate` untouched — Step 1's seam respected); (b)
`SchemaClassEmbedded.addProperty` de-guarded with the established `!owner.txLocal &&` pattern
(the design's "every mutation routes tier-3 through resolveForWrite" premise required in-tx
property creation, which no prior track had de-guarded; `SchemaClassOperationsTest` updated to
the new tx-local contract); (c) structural-record link maintenance made explicit end-to-end:
legacy `saveInternal` serializes tracker-suppressed (symmetric with the commit window — a
tracked legacy drop of a commit-created bag-less class record threw
`LinksConsistencyException`), and the legacy `IndexAbstract.delete`/`rebuild` paths unlink
`CONFIG_INDEXES` explicitly (`IndexManagerAbstract.unlinkIndexRecord`) instead of relying on
the tracker's back-bag auto-cleanup (dangling-link reopen failures on the import path);
(d) `EntityLinkSetImpl` metadata-record embedded pin + `MetadataDefault.COLLECTION_INTERNAL_ID`
— fixes the PRE-EXISTING silent class-loss (see Surprises).

**Reviewer-obligation results:** TQ12 re-grep → `STORAGE_BLOB_COLLECTIONS_COUNT` still has
exactly ONE production read (`AbstractStorage:1522`; the `:1534` hit is the guard message's
`.getKey()`); CQ14 → the register loop's `List.copyOf` snapshot survives the tx-wrap (now a
pure tx-local write, the copy stays as future-proofing); CQ15 → `schema.create` (:214) and
`indexManager.create` (:215) remain strictly BEFORE the phase-1 `executeInTx` (:225) — belt
assert intact.

**Verification:** `./mvnw -pl core clean test` → BUILD SUCCESS (17467 — +11 new tests — +
2219 sequential; 0 failures); `./mvnw -pl core,tests clean test` → BUILD SUCCESS (tests
module 1300; 0 failures); `./mvnw -pl core clean verify -P ci-integration-tests` → BUILD
SUCCESS (513 ITs, 3:10 h); spotless clean; coverage gate vs origin/develop → PASSED, 90.7%
line (2124/2341), 83.0% branch (974/1174).

**Surprises:** the pre-existing schema-carry-commit/btree-bag silent class-loss and the
operation-id-horizon fixture-casualty class (both in §Surprises & Discoveries); the
`createProperty` de-guard gap (the design assumed tier-3 routing covered it); no red-first pin
was mandated for this step and none was needed — the restructure's failures were all caught
by the existing suites plus the new pins. The CS50/W6 armed window is CLOSED as of this
commit.

### Step 4 — commit 2433d684ae, 2026-07-24T19:16Z [ctx=safe]
**What was done:** the M2.a export hardening + the CS43 validated-gzip primitive.
**Red-first (pin M.5 #1):** `injectedScanFailureAbortsWithoutTouchingFinalName` written FIRST
and shown RED at HEAD bac3747535 — failure signature: `AssertionError: a mid-scan failure must
abort the export loudly` (the listener-injected mid-records-phase failure was swallowed into a
success exit; the constructor had ALREADY deleted the pre-existing final-name dump and the
finally's close() promoted the partial dump over it — FM-M1/M3/CS41 in one signature). Green
with the hardening: loud `DatabaseExportException` carrying the injected cause, final name
byte-identical to the sentinel, zero temp residue. (No other red-first pins were mandated for
this step.)

**Implementation per design element:** (1) `EXPORTER_VERSION` 15. (2) Rethrow-by-default
(M2.a-2): the collection-scan arm always rethrows wrapped; the per-record arm aborts by
default with a message naming the `-bestEffort=true` opt-out; under the opt-out the record is
discarded WHOLE, lands in `brokenRids`, and the `best-effort` scalar marker rides the info
section. (3) Whole-or-discarded rendering (M2.a-3/Q-M1): per-record `SpillableRecordBuffer`
(own generator, `AUTO_CLOSE_TARGET` off so the generator close cannot cascade into the
buffer's delete-on-close lifecycle) spilling beyond the new
`GlobalConfiguration.EXPORT_RECORD_SPILL_THRESHOLD` knob (default 32 MB) to a collision-free
temp file in the dump's directory, deleted on every path; chunked raw copy-out
(`writeRawValue("")` + `writeRaw` — memory-bounded both ways), whole-or-fatal. (4)
Completion-flag-gated promote (M2.a-4/CS41/CN52): `completed` set only after the manifest was
written and the stream closed cleanly; per-export unique `CREATE_NEW` temp name
(`<final>.<uuid>.tmp`); NO upfront final-name delete (both `prepareForFileCreationOrReplacement`
calls gone); `close()` without completion is an abort — deletes the temp, never renames. (5)
Manifest last (M2.a-5/R2/WI8c/CN51): trailing `manifest` section with
`classes`/`indexes`/`records`/`brokenRids` totals tallied by the writing loops themselves.
Promote recipe (CS40) in the new `FileUtils.durableAtomicMove`: reopened-channel
`force(true)` → `ATOMIC_MOVE`+`REPLACE_EXISTING` → parent-dir fsync; fail-closed (no copy
fallback; the dir-fsync leg is best-effort only where the platform cannot open a directory
channel, e.g. Windows — logged). (6) Primary-exception preservation (M2.a-6): failure-path
cleanup suppresses secondaries onto the primary. (7) Schema-section `version` field writes
`SchemaShared.CURRENT_VERSION_NUMBER` (M2.a-7). Streaming variant: no rename to gate; the
manifest itself is its completion marker. (8) `ValidatedGZIPInputStream` (CS43): single-member
decoder with continuation disabled BY CONSTRUCTION, drain-to-EOF → trailer CRC32+ISIZE verify
→ `verifyFullyConsumed()` (incl. in-window trailing-residue rejection) →
`verifyPhysicalSize()` arithmetic (`headerLen + getBytesRead() + 8 == physicalSize`);
sequence-order enforced (verification before the drain is an error).

**As-built deviations (recorded):** (a) the primitive extends `InflaterInputStream`
(`GZIPInputStream`'s superclass) rather than `GZIPInputStream` itself — the JDK decoder's
member-continuation is private and unhookable; the behavior contract (the design's Signatures
section) is implemented exactly. (b) `DatabaseImport` gained a MINIMAL manifest-consuming arm
(`skipManifest`, no validation) — the step's seam note says "does NOT touch DatabaseImport",
but without it every v15 dump import throws "unsupported tag 'manifest'" and the
step-mandated round-trip suites cannot stay green; Step 5's v15-strict skeleton replaces it
with the version-gated validating arm (WI6/CN51). (c) a protected `renderRecord` seam was
extracted so tests can inject deterministic render failures. (d) `DatabaseImpExpAbstract` was
NOT touched (the `-bestEffort` option lives in `DatabaseExport.parseSetting`; the importer's
ack flag is Step 5's own surface — packing premise intact).

**Tests:** `DatabaseExportHardeningTest` (7): pin M.5 #1 (red-first),
`renderFailureAbortsByDefault` + `bestEffortDiscardsWholeRecordAndRecordsTheMarker` (pin #2,
incl. the info marker and manifest-vs-content equality), `oversizedRecordSpillsAndIsExportedWhole`
(pin #8), `unflaggedCloseNeverRenames` + `concurrentExportersUseUniqueTempFilesAndPromoteConsistentDumps`
+ `manifestStaysSelfConsistentUnderConcurrentDdl` (pin #17, CN52/CN51; the mid-export DDL runs
deterministically from the listener on a second session); every promoted dump is re-read
through the primitive with the FULL CS43 sequence. `ValidatedGZIPInputStreamTest` (9): valid /
truncated-deflate / truncated-trailer / trailing-garbage / multi-member / corrupt-trailer-CRC
/ corrupt-trailer-ISIZE / non-gzip-at-construction / sequence-order-enforced.
`SpillableRecordBufferTest` (4): threshold boundary (at-threshold in memory, +1 spills),
single-byte crossing, round-trip fidelity, spill-file deletion on the copy-out AND discard
paths. `FileUtilsDurableAtomicMoveTest` (2): replace-existing + fresh-target moves.

**Verification:** `-Dtest=DatabaseExportImportRoundTripTest,+new classes,+import suites` →
green; `./mvnw -pl core clean test` → BUILD SUCCESS (17490 — +20 new tests — + 2219
sequential; 0 failures); `./mvnw -pl core,tests clean test` → BUILD SUCCESS (tests 1300);
step-mandated import/export ITs → `./mvnw -pl core clean verify -P ci-integration-tests` →
BUILD SUCCESS (513 ITs, 3:10 h); spotless clean; coverage gate vs origin/develop → PASSED,
89.5% line (2252/2515), 82.4% branch (1023/1242). (The `FileUtilsDurableAtomicMoveTest` was
added after the full battery as a test-only pin of the already-exercised promote primitive;
verified targeted.)

**Discharges:** M2.a-1..7; CS40/CS41/CS43(primitive)/CN51(export)/CN52; WI7; WI8a/b/c; Q-M1;
FM-M1/M2/M3/M4/M5/M9/M15/M17; pins M.5 #1/#2/#8/#17 + the CS43 primitive suite. WI10a (the
stream-ctor validation scope) stays Step 5's explicit obligation — the primitive already
exposes steps (1)+(2) stream-only and step (3) for sizable sources.

### Step 3 review-fix iteration 1 — commit 6b61334581, 2026-07-24T14:29Z [ctx=safe]
**What was done:** applied the three Step 3 review reports
(`track-8/reviews/{baseline,crash-safety,concurrency}-step3-iter1.md`; 0 blockers, 2
should-fix, ~15 suggestions).

**Should-fix.** (1) **CS52 — mechanism chosen: belt relocation.** The genesis-completion
refusal moved from `getAndOpenStorage` into `SharedContext.load`, immediately AFTER
`schema.load` — so Track 2's schema-version gate (fromStream's export/reimport redirect) owns
old-format databases FIRST, v6-format marker-less corpses are still refused on the first
session of every context (the `loaded` flag is set only at the end, so a refused load re-runs
and re-refuses), and marked databases open normally. Chosen over the message-fold (which would
leave pin M.5 #10 pinning the wrong exception) and over a storage-level format discriminator
(reading the schema version requires schema machinery unavailable at the storage seam); it
restores §A1's compatibility clause ("every database these binaries can open passed Track 2's
gate") to the letter. Pinned by
`oldFormatDatabaseGetsMigrationRedirectNotGenesisRefusal` (schema root rewritten to legacy
version 5, marker removed, fresh disk context → the ConfigurationException redirect fires and
NO GenesisIncompleteException appears in the chain) — Step 6 pin M.5 #10's prerequisite.
(2) **CQ17** — all three tracker-suppression comments rewritten to the true mechanism: the
windows close before the enclosing commit; they work because
`FrontendTransactionImpl.deleteRecord` runs before-deletion checks SYNCHRONOUSLY inside them;
the ADD side still runs tracking-ON at commit (legacy-created records DO carry bags); a
refactor deferring deleteRecord's callbacks must move the suppression. `SecurityShared.create`'s
dead "joins an active transaction" claim replaced with the tx-free precondition (every live
caller satisfies it; the import site is provably tx-free).

**Code suggestions applied.** BG14/CN55/CS55: `unregisterGenesisIncompleteCorpse` closes and
unregisters a refused corpse's storage on every open path (non-pooled via `newSessionInstance`,
pooled via both `poolOpen*` arms) — no pinned file locks/WAL buffers; the
`doCreate(failIfExists=false)` route probes the marker and refuses a marker-less residue
LOUDLY, naming BOTH recovery routes (drop-and-recreate for corpses, open-for-migration-guidance
for old-format) — never unconditional discard advice; the config-only boolean
`createIfNotExists` overload keeps its `exists()` short-circuit contract (boundary recorded in
the test javadoc — the next open refuses loudly anyway). Gate-residual RG4: the create-probe
also surfaces OPEN failures for existing-but-unopenable databases (W1/W2 residue, or a healthy
database under a mismatched configuration such as encryption) at create-time, where the old
arm silently no-op'd — an intended fail-loud boundary change. CN56: `catch (Error)` arm added to
`createStorage` (cleanup best-effort, Error rethrown unwrapped;
`cleanUpFailedCreate` widened to `Throwable`). CS53/CN58: `initCustomStorage`'s create arm
gains the same cleanup-on-exception. CS54: `doDelete` skips the dirty-flag write for a
never-opened storage (drop() now deletes W1/W2 corpse files instead of failing on the missing
startup-metadata channel); the drop-level suite test is INFEASIBLE — the corpse's failed open
trips the pre-existing open-failure native-buffer leak (new Surprises bullet) — so the guard
ships with the production comment and this disposition. CN57(a): the btree→embedded
down-conversion arm gets the same metadata-record suppression (containment symmetry).

**Test suggestions applied.** TQ16: `createPropertyInsideTransactionPersistsAtCommit` (commit
half + forced re-parse of the persisted records). TQ17: the failed-create stage of
`createIfNotExistsRecreatesAfterFailedCreate` now verifies the injected cause in the chain.
TQ18(a): the refusal and drop-exemption pins rebuilt on DISK against a FRESH context via
`createMarkerlessDiskCorpse` (the reopened-config read of the marker), plus the BG14
unregistration asserted after each refusal.

**Dispositions (docs/records).** CS56: restore-outside-the-belt boundary recorded (Surprises
bullet + design-drafts §A1 as-built note). CN59: the crashed-OSystem-genesis loud-brick
behavior (strictly better than the pre-marker silent corpse-open, but with no automated
discard) is owed to Step 6's WI3 operator page — added to its content list in the Step 6 spec
below. BG16: in-tx `fireDatabaseMigration` semantics documented at the de-guard (migration
rewrites join the caller's tx, no per-batch commits; rollback discards); no guard — the
semantics are consistent, the cost is the caller's. BG17/CN57(b)/CS57: folded into the
extended btree-bag Surprises bullet; the follow-up issue record is the orchestrator's (not
created here). CQ18: `dropProperty`'s in-tx throw asymmetry — pre-existing, deferred to the
de-guard follow-up with this record. TQ18(b): the import-nested "users recreated" half stays
delegated — the guard no-op arm is the live behavior at every real import call site (importing
into a database with ANY class), so a named users-recreated assertion would pin a practically
dead arm; the import suites exercise the call site end-to-end.

**Verification:** targeted (GenesisFailureContainmentTest 8, TwoPhaseGenesisTest 5,
SchemaClassOperationsTest 31, GenesisSchemaBootstrapTest 5, StorageEmbeddedBlobCollectionsTest
6, DatabaseImportTest, DatabaseExportImportRoundTripTest, MetadataWriteMutexTest) → BUILD
SUCCESS; `./mvnw -pl core clean test` → BUILD SUCCESS (17470 + 2219; 0 failures);
`./mvnw -pl core,tests clean test` → BUILD SUCCESS (tests 1300); the CS52/BG14 fixes touch the
open/create/drop lifecycle → full `./mvnw -pl core clean verify -P ci-integration-tests` →
BUILD SUCCESS (513 ITs, 3:11 h); spotless clean; coverage gate vs origin/develop → PASSED,
89.5% line (2192/2449), 83.1% branch (981/1180).

### Step 1 review-fix iteration 1 — commit 931e264f48, 2026-07-23T23:30Z [ctx=safe]
**What was done:** applied the two Step 1 review reports
(`track-8/reviews/{baseline,crash-safety}-step1-iter1.md`; 0 blockers, 1 should-fix, 8
suggestions). (1) **CS47 (should-fix, docs+seam):** the Step-1 record's blob-set persistence
baseline was WRONG — `SchemaShared.addBlobCollection` self-commits the schema root
synchronously per iteration (`releaseSchemaWriteLock`→`saveInternal`→`executeInTx(toStream)`);
nothing downstream of the loop saves it. Corrected the Surprises bullet and Episode text;
added the Step-3 seam annotation (tx-wrap MUST re-route the registration through
`SchemaProxy.resolveForWrite()`/the session — the direct `SchemaShared` call throws
`SchemaException` under an active tx) and the matching as-built correction at design-drafts
§G2.b. No routing change now — that is Step 3's seam. (2) **CS48 (docs):** recorded that the
FM-M16 importer blob-id-mapping window is ARMED from 6611cbf6b2 until Step 5's §A3 fix.
(3) **CS49/TQ12 (dispositions):** the enlarged create op's first direct crash-path tests arrive
with Step 3's pin G.5 #9; the Step-3 reviewer must re-grep the single production read of
`STORAGE_BLOB_COLLECTIONS_COUNT` after the tx-wrap (both carried in the Step-3 seam
annotation). (4) **BG12 (code):** `doCreate` rejects a NEGATIVE blob-collections count loudly
at create time naming the knob key (throw inside the create atomic op → whole create rolls
back; zero stays allowed as a deliberate blob-less DB) — pinned by two new tests
(`negativeBlobCollectionsCountIsRejectedAtCreateTime` asserts the cause chain names the key;
`zeroBlobCollectionsCountCreatesBlobLessDatabase` pins empty physical+registered sets).
(5) **CQ13 (code):** the `$blob` prefix is now the shared constant
`MetadataDefault.BLOB_COLLECTION_NAME_PREFIX` (creator loop + register pattern both derive from
it); tests keep independent `$blob` literals deliberately — the name shape is design-pinned
(R3). (6) **CQ14 (code):** the register loop snapshots the collection names
(`List.copyOf`) instead of iterating the live view while self-committing. (7) **TQ11 (test):**
`StringsTest` re-pins provisional-RID distinctness and the `#collection:-position` text shape
dynamically. (8) **TQ13 (test):** `blobLayoutSurvivesDiskReopen`'s drop moved to an outer
finally spanning both session blocks; `CommandExecutorSQLTruncateTest` closes its `ResultSet`.

**Key files:** `core/.../metadata/MetadataDefault.java` (new constant);
`core/.../storage/impl/local/AbstractStorage.java` (negative-count guard);
`core/.../db/SharedContext.java` (derived pattern; defensive copy);
`StorageEmbeddedBlobCollectionsTest` (+2 tests, hygiene), `CommandExecutorSQLTruncateTest`,
`StringsTest`; `track-8.md` + `track-8-design-drafts.md` (CS47/CS48 records, Step-3 seam
annotation).

**Verification:** targeted `-pl core -Dtest=StorageEmbeddedBlobCollectionsTest,
CommandExecutorSQLTruncateTest,EntityImplTest` → 35/35 green; `./mvnw -pl core,tests clean
test` → BUILD SUCCESS (core 17451 — +2 new tests — + 2219 sequential; tests 1300 incl. the
hardened `StringsTest`; 0 failures); coverage gate vs origin/develop → PASSED, 88.6% line
(2072/2339), 83.2% branch (950/1142); spotless clean. **ITs deliberately not re-run this
iteration:** the production diff is a misconfiguration-only guard (valid-config create path
byte-identical), a same-value constant extraction, and a read-side defensive copy — none
alters the storage-create/backup/restore behavior the `ci-integration-tests` suite exercises,
and Step 1's full IT run (3:09h, 513 ITs green) covered the identical valid-path behavior.

## Validation and Acceptance
- A fresh database genesis builds the `OUser.name` index before any user insert; the
  default users are created through real engine lookups, not scan fallbacks. The schema
  transaction engages the mutex (no contention at genesis); the following data
  transaction does not (I-U4).
- A truncated or corrupt dump fails the import loudly; a mid-export crash leaves no
  well-formed manifest; opening an old-format database with new binaries is rejected (not
  migrated in place); ~~a complete legacy dump missing any expected section is refused; a
  legacy dump requires the explicit unverified-import acknowledgment flag~~
  (I-migration-fail-closed). **SUPERSEDED (ruling R1, 2026-07-23; recorded as pass-1 WC1 in
  `track-8-design-drafts.md` §"Frozen-plan supersession record"):** the two struck clauses
  described legacy-dump strictness; under R1, declared-legacy (`<= 14`) dumps keep today's
  lenient behavior (missing sections tolerated; no legacy ack flag — the ack gate keys off the
  v15 best-effort marker). A dump with NO declared exporter-version is rejected fail-closed
  (SR2). **No test names may be generated from the superseded clauses** — the amended contract
  is pinned by design test-pins M.5 #5/#7 (v15 strictness) and #11/#14 (declared-legacy
  leniency, SR2 rejection).
- A mid-record I/O failure leaves no file at the final name; an oversized-but-healthy
  record is present in the dump, not dropped; a mid-copy-out failure aborts with no
  promoted dump; a record that fails to render is recorded in `brokenRids` and the export
  continues only in best-effort mode (I-migration-isolation).
- Injecting a record-scan failure gives exit ≠ 0, no file at the final name, and the scan
  exception (not a close-path secondary) as the primary; a best-effort dump imported
  without the ack flag is refused; a dump with a pending field name is parse-rejected
  (I-migration-failfast).

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

> **Move-3 note (decomposition):** per-step acceptance lines are carried as the design doc's
> numbered test pins (G.5 #1-#10, M.5 #1-#18), cited per step in `## Concrete Steps`; test
> method names derive from those pins under the AMENDED contract — never from the two
> R1-superseded clauses struck in bullet 2 above (WC1).

## Idempotence and Recovery
<!-- Phase A placeholder. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Often empty. -->

## Interfaces and Dependencies
**Packing justification (argumentation gate).** This track packs two terminal
autonomous units — genesis (D18) and migration (D20) — to minimize track count per the
sizing rule's *maximize* preference. They share no files (`SecurityShared` and the
metadata creators versus `DatabaseExport` / `DatabaseImport` and the gzip-framing
subclass), so reviewing them together costs no more than reviewing them apart, and
neither is large enough on its own to approach the footprint ceiling. Both are
end-of-series: genesis depends on the full Part-1 core plus the index build (Tracks 2,
4, 5) and migration on the format change (Track 2), so neither blocks an earlier track.
Migration touches no file any of Tracks 3–7 touch, so placing it last adds zero rebase
conflict despite its only hard dependency being Track 2.

- **In scope**: the two-phase genesis restructure of `SecurityShared.create` and the
  sibling metadata creators; the export/import hardening (`DatabaseExport` /
  `DatabaseImport`: `EXPORTER_VERSION` 15, rethrow-by-default with best-effort opt-out,
  per-record bounded buffer with spill-to-temp, completion-flag-gated promote, manifest
  written last and atomically); the import-side manifest verify, section-presence check,
  whole-stream gzip validation subclass, non-gzip rejection on the migration path, and
  best-effort acknowledgment-flag gate; genesis + migration tests.
- **Out of scope**: the per-class-record format and the open-time version-bump gate
  itself (Track 2 — this track is its redirect target); the commit machinery (Track 4);
  the index build internals (Track 5).
- **Inter-track dependencies**: depends on Track 2 (the format both units target), Track 4
  (genesis's commit path), and Track 5 (genesis's commit-time `OUser.name` build). No
  downstream track depends on this one.
- **Signatures**: the two-phase genesis transactions in `SecurityShared.create`;
  `EXPORTER_VERSION` 14→15 and the v15 best-effort scalar marker; the `GZIPInputStream`
  subclass comparing `Inflater.getBytesRead()` plus the parsed header length and the
  8-byte trailer against the physical file size; the manifest temp+fsync+rename
  discipline.
