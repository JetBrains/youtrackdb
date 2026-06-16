<!--
MANIFEST
dimension: crash-safety
track: 2
step: 1
commit_range: 9eaeb3781e~1..9eaeb3781e
iteration: 1
verdict: pass
counts: {blocker: 0, should-fix: 0, suggestion: 0, informational: 3}
evidence_base: present
cert_index: [C1, C2, C3, C4]
flags: []
index:
  - id: CS1
    sev: informational
    anchor: "#cs1-informational-multi-record-schema-write-is-one-atomic-executeintx-commit"
    loc: "core/.../metadata/schema/SchemaShared.java:865-877,662-722"
    cert: C1
    basis: "PSI find-usages: toStream(1p) sole caller is saveInternal, which wraps it in executeInTx; executeInTx = begin/finishTx atomic unit"
  - id: CS2
    sev: informational
    anchor: "#cs2-informational-temp-to-persistent-rid-resolution-is-in-place-on-the-shared-changeablerecordid"
    loc: "core/.../metadata/schema/SchemaShared.java:684-696; core/.../id/ChangeableRecordId.java"
    cert: C2
    basis: "getIdentity() returns the entity's ChangeableRecordId by reference; setRecordId binds the same instance; commit mutates it in place"
  - id: CS3
    sev: informational
    anchor: "#cs3-informational-version-gate-and-link-set-type-check-fail-closed-against-an-old-format-record"
    loc: "core/.../metadata/schema/SchemaShared.java:506-515,540; core/.../record/impl/EntityImpl.java:1141-1154"
    cert: C3
    basis: "gate rejects any schemaVersion != 6 before the load loop; getLinkSet throws DatabaseException if classes is not a LinkSet"
-->

# Crash Safety & Durability Review — Track 2, Step 1 (per-class schema records)

Overall: safe to ship within this track's storage-leads boundary. The multi-record
schema write (root record + per-class records + drops) is committed as one atomic
`executeInTx` unit, so a crash leaves the schema either fully at the old state or
fully at the new state — never torn. Recovery is the unchanged storage-leads WAL
path; this step adds no new persistent state outside ordinary transactional records,
introduces no WAL bypass, and per D14/D20 carries no migration crash-recovery work
(the migrator is Track 8). Zero blocker / should-fix findings; three informational
notes recording the durability properties that were verified.

## Findings

### CS1 [informational] Multi-record schema write is one atomic `executeInTx` commit

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java` (lines 865-877 `saveInternal`, 662-722 `toStream`)
- **Observation**: `saveInternal` runs `session.executeInTx(transaction -> toStream(session))`
  (line 874) and only then `forceSnapshot()` (line 876). Inside `toStream` the root
  record is loaded (`session.load(identity)`), the per-class records are created
  (`session.newInternalInstance()`) or re-loaded and rewritten (`c.toStream(session,
  classRecord)`), the link set is mutated (`classLinks.add(...)` / `.remove(...)`),
  and dropped records are `delete()`d. Every one of those is a transactional record
  operation enrolled in the single transaction `executeInTx` opens; `finishTx(true)`
  commits them together (`DatabaseSessionEmbedded.executeInTxInternal`, lines
  3494-3510).
- **Crash scenario**: A crash before `finishTx` commits leaves the on-disk schema at
  the pre-save state (no records, links, or deletes persisted); a crash during the
  commit is covered by the commit's own atomic-operation WAL (the storage-leads model
  this step does not change). There is no window in which the root links a record
  that was not written, or a per-class record exists without its link, or a dropped
  record is deleted while still linked.
- **Recovery impact**: WAL replay reconstructs either the complete old schema record
  or the complete new set of records + root link set. `fromStream` then rebuilds the
  in-memory schema from whatever durably committed; no half-applied state is
  reachable.
- **Why informational, not a finding**: this is the intended and correct behavior;
  recorded so a later track that changes the save path (Track 4 selective write) knows
  the atomic-unit boundary it must preserve.

### CS2 [informational] Temp-to-persistent RID resolution is in-place on the shared `ChangeableRecordId`

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java` (lines 684-696); `core/src/main/java/com/jetbrains/youtrackdb/internal/core/id/ChangeableRecordId.java`
- **Observation**: For a new class, `toStream` does `classRecord =
  session.newInternalInstance()` (a record with a temp `ChangeableRecordId`),
  `c.setRecordId(classRecord.getIdentity())`, and `classLinks.add(classRecord
  .getIdentity())`. `RecordAbstract.getIdentity()` returns the entity's own
  `recordId` field by reference (final accessor, RecordAbstract.java:92-96), so
  `SchemaClassImpl.recordId`, the link-set entry, and the entity all hold the same
  `ChangeableRecordId` instance. At commit the temp id is resolved via
  `setCollectionAndPosition` (a CAS on the internal `AtomicReference`), mutating that
  one shared object in place. After commit `c.getRecordId()` therefore resolves to the
  persistent RID with no second write, and the link set serializes the resolved RID.
- **Round-trip durability**: this is exactly the `IndexManagerAbstract.addIndex
  InternalNoLock` / `load` pattern (verified at IndexManagerAbstract.java:195-217), so
  a `toStream` -> `fromStream` round-trip rebinds each class to its persistent record
  RID (SchemaShared.java:559). The test `createdClassPersistsAsStandaloneLinkedRecord`
  asserts `ridBefore.isPersistent()` post-commit and the reopen re-binding, which
  would catch a regression where `recordId` stayed a dangling temp RID.
- **Why informational**: the mechanism is correct; recorded because a stale `recordId`
  would have been a silent durability bug (the next save would `session.load` a temp
  RID or create a duplicate), and Track 3 re-parses through this serializer to seed its
  tx-local copy.

### CS3 [informational] Version gate and link-set type check fail closed against an old-format record

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java` (lines 506-515 gate, 540 load); `core/src/main/java/com/jetbrains/youtrackdb/internal/core/record/impl/EntityImpl.java` (lines 1141-1154)
- **Observation**: `fromStream` now rejects any `schemaVersion != CURRENT_VERSION_NUMBER`
  (= 6) with the export/import `ConfigurationException` before reaching the link-set
  load loop, so neither a v4 nor the legacy v5 embedded-set record is ever fed to
  `getLinkSet("classes")`. As defense-in-depth, `getLinkSet` itself throws
  `DatabaseException` if `"classes"` resolves to a non-`LinkSet` property
  (EntityImpl.java:1152), so an embedded-set record that somehow bypassed the gate
  would fail loudly rather than mis-parse into a corrupt in-memory schema. Both arms
  (v4 and v5) are covered by `versionFourDatabaseIsRejectedAndRedirected` /
  `legacyVersionFiveDatabaseIsRejectedAndRedirected`.
- **Recovery impact**: an old-format database opened by a new binary cannot silently
  parse stale bytes as the new link-set format; it is rejected at open, matching the
  D20 import-only migration contract. No partial-migration state is ever written.
- **Why informational**: the gate behaves correctly; recorded because a silent
  mis-parse of an old-format record would be the highest-severity corruption hazard
  this format change could introduce, and it is closed off twice.

## Evidence base

#### C1 — atomicity of the multi-record write (CONFIRMED safe)

PSI find-usages on `SchemaShared.toStream`: sole caller is `saveInternal`
(SchemaShared.java:874), which wraps it in `executeInTx`. `executeInTxInternal`
(DatabaseSessionEmbedded.java:3494-3510) is `begin()` ... `finishTx(ok)` — one
transaction, one commit. All record ops created inside `toStream` (root mutate,
per-class create/rewrite, link add/remove, dropped-record delete) enroll in that one
transaction. CONFIRMED: the write is a single atomic unit; no torn-state crash window.

#### C2 — temp->persistent RID resolution and round-trip (CONFIRMED safe)

`RecordAbstract.getIdentity()` returns the `recordId` field by reference;
`ChangeableRecordId` resolves its temp id in place via a CAS on its internal
`AtomicReference` at commit. `setRecordId(classRecord.getIdentity())` binds the same
instance the entity and link set hold, so all three see the persistent RID after
commit with no second write. Mirrors the verified IndexManagerAbstract pattern.
CONFIRMED: round-trip RID + root payload preservation holds.

#### C3 — fail-closed version gate (CONFIRMED safe)

Refutation attempted: could an old-format (v4/v5) record reach the link-set parser and
mis-read its embedded `"classes"` set as a link set, producing a silently corrupt
schema? Checked the gate at SchemaShared.java:506-515 — it throws before the load loop
for any version != 6, and `getLinkSet` (EntityImpl.java:1152) throws on a non-LinkSet
property as a backstop. REFUTED: no path feeds an old-format record to the link-set
reader; the hazard is closed twice.

#### C4 — no missed out-of-footprint reader of the raw schema-record "classes" field (REFUTED as a hazard)

Refutation attempted: is there a reader besides `DatabaseCompare` that still parses the
schema record's raw `"classes"` property as an EMBEDDEDSET and would mis-read the new
link set (corrupting a comparison, backup, or restore across a crash/restart IT)?
PSI `processAllFilesWithWord("classes")` over the project, filtered to schema / db-tool
/ export-import, returned: `DatabaseExport`/`DatabaseImport` (logical-JSON `"classes"`
array via `jsonGenerator` / `importSchema`, not raw-record parsing — confirmed at
DatabaseExport.java:462, DatabaseImport.java:235; they touch the schema record only
through `getSchemaRecordId` and `CURRENT_VERSION_NUMBER`, the documented Track-8
coupling), the schema-API classes (`ImmutableSchema`, `SchemaClass*`, etc., which never
parse the raw record), and the two test sites updated in this diff. `DatabaseCompare`
was the only raw-record reader and its `convertSchemaDoc` special-case is correctly
removed — the record walk now content-compares the per-class records directly, which is
valid because both compared databases are the same v6 format. PSI also confirms the old
1-arg `SchemaClassImpl.toStream` has zero remaining callers (sole caller is the new
2-arg form at SchemaShared.java:694). REFUTED: no missed reader; no test-green or
restore-corruption hazard from a stale parser.
