<!-- workflow-sha: 3e9c22298dfe68d2980646704850c781f8af88d5 -->
# Track 2: Per-class schema records (D14)

## Purpose / Big Picture
After this track, the schema persists as a root record that links one entity record
per class (the format that removes schema-wide write amplification), with each class
bound to its own record RID, and the open-time version check rejects an old-format
database with a redirect to export/import. The selective per-class write that turns
this format into the actual write-amplification win lands in Track 4 (D6 per-property
dirty tracking); under Track 2's storage-leads model `toStream` still rewrites the
whole schema on every change, so the reduction is not yet observable here.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Replace the single monolithic schema record with a root record that links to one
entity record per class, mirroring the index-manager pattern, add the net-new
per-class record-RID field bound at load, and bump the schema version into a
reject-and-redirect gate. Writing only the changed class records (plus the root
when its non-link payload changes) is the write-amplification win this format
enables, but it depends on D6 dirty tracking and the commit-time reconciliation in
Track 4; under Track 2's storage-leads `toStream`, every save still rewrites the
full schema. Track 2 lands the persistence foundation YTDB-382 exists for; the
tx-local seed (Track 3) binds the per-class RIDs this track introduces.

## Progress
- [x] Review + decomposition
- [x] Step implementation
- [x] Track-level code review (skipped — single-step track, fully reviewed in Phase B)
- [x] Track completion
- [x] 2026-06-16T14:03Z [ctx=info] Review + decomposition complete
- [x] 2026-06-16T16:14Z [ctx=safe] Step 1 complete (commit 9c7409305b)
- [x] 2026-06-16T16:14Z [ctx=safe] Step implementation complete (Phase B done; 1/1 step)
- [x] 2026-06-17T00:00Z [ctx=safe] Track-level code review skipped (single-step risk:high track; step-level dimensional review in Phase B covered the identical diff)
- [x] 2026-06-17T00:00Z [ctx=safe] Track completion approved; one review-mode fix applied (commit f645af9f39, drop redundant read lock in toStream); episode written to plan file

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->
- 2026-06-16T16:14Z Step 1: `DatabaseCompare.convertSchemaDoc` was already dead
  code. Schema records live in internal collection id 0, which `skipRecord`
  skips unconditionally, so it was unreachable before this change. It is
  dropped, not rewritten. Any future schema-record comparison work must account
  for the collection-0 skip. See Episodes §Step 1.
- 2026-06-16T16:14Z Step 1: the schema version is now 6 (was 4). Track 8's
  migration (EXPORTER_VERSION 14→15) and any `DatabaseExport` / `DatabaseImport`
  schema-version field must agree on 6. See Episodes §Step 1.
- 2026-06-16T16:14Z Step 1: the serializer now asserts
  `isWriteLockedByCurrentThread()`, documenting the write-lock contract. Track 7
  must revisit this invariant if it inverts the schema lock model (BC2 flagged
  the prior read-lock acquire as latent under the single-writer commit model).
  See Episodes §Step 1.
- 2026-06-16T16:14Z Step 1: the round-trip preserves per-class RIDs and the root
  non-link payload (test-verified via durable reload), so Track 3's tx-local
  seed binds RIDs faithfully; the load reader rejects a non-persistent linked
  record id with a diagnosable `ConfigurationException`, which Track 4's
  selective per-class write can rely on. See Episodes §Step 1.

## Decision Log
<!-- The track-canonical live decision carrier (D7). Seeded from the frozen
design.md D-records this track owns. -->

#### D14: Split the schema into per-class records, killing write amplification
- **Alternatives considered**: keep all classes in one EMBEDDEDSET schema record (today's format, F1).
- **Rationale**: a schema record that links to per-class entity records, mirroring the index manager's `CONFIG_INDEXES` link set (F20), means a one-class change writes one record. Each `SchemaClassImpl` carries its own record RID as a net-new field, bound at load from the schema-record link set exactly as `IndexManagerAbstract.load` binds each index. At commit, `toStream` writes each class into its own record and per-property dirty tracking (D6) limits the write to the classes that actually changed. This directly attacks the issue's "big amount of storage writes." Inheritance needs no inter-record RID coupling because superclasses are referenced by name in the serialized form.
- **Risks/Caveats**: a new class is a new record (temp→persistent RID at commit, D2); a dropped class deletes its record and unlinks it. The root record keeps the non-link payload (the global-property table, `collectionCounter`, `blobCollections`) and must be written whenever the class link set or any of that payload changes — the tx sets those properties on the root entity so D6's dirty tracking puts the root in the write set for free. Without the root in the write set, a committed property-create restarts into a null `globalRef` NPE and a stale counter regenerates colliding collection names (F59). The change overturns the earlier "record format unchanged, no migration" assumption; existing databases migrate via export/import (D20, Track 8), so this track carries no migration crash-recovery work, only the version bump as a reject-and-redirect gate.
- **Implemented in**: this track (step references added during execution)
- **Full design**: design.md §"Per-class schema records"

## Outcomes & Retrospective
<!-- Continuous-log. Empty at Phase 1. -->
- [x] Technical: PASS at iteration 2 (4 findings; 0 blocker / 2 should-fix / 2 suggestion; all 4 accepted). Surfaced the version-gate `VERSION_NUMBER_V5` trap, the `DatabaseCompare.convertSchemaDoc` out-of-footprint parser, and the selective-write/F59 Track-4 ownership.
- [x] Risk: PASS at iteration 2 (5 findings; 0 blocker / 3 should-fix / 2 suggestion; all 5 accepted). Independently confirmed the version-gate and `DatabaseCompare` issues plus the storage-leads acceptance mismatch; added the `DatabaseExport`/`DatabaseImport` schema-version coupling note.
- [x] Adversarial: PASS at iteration 2 (4 findings; 0 blocker / 2 should-fix / 2 suggestion; all 4 accepted). Confirmed `DatabaseCompare` as a hard test-green blocker, traced the legacy-V5 silent-misparse, and softened the over-stated Track 1 dependency rationale.
- Gate verification (consolidated technical+risk+adversarial, iteration 2): PASS — all 13 findings VERIFIED, 0 still open, 0 regression. Two new suggestions acknowledged, not acted: G1 = D14's Risks bullet keeps the imprecise "D2" record-RID citation (the operative Plan of Work already states "not D2"; D14 is the frozen design seed, left verbatim); G2 = plan invariant I-U1 still bundles the Track-4 "root written exactly when its payload changes" clause — carried to Track 4's Phase A as the home for that clause and its F59 test.

## Context and Orientation
Today `SchemaShared` serializes every class into one EMBEDDEDSET inside a single
schema record (`CURRENT_VERSION_NUMBER` 4), so any class change rewrites the whole
record. The index manager already uses the target pattern: its record holds a
`CONFIG_INDEXES` link set pointing at per-index entity records, and
`IndexManagerAbstract.load` binds each index to its record identity. `SchemaClassImpl`
has no record-RID field today (F45).

The serialized class form already references superclasses by name (`toStream` sets
`superClass`/`superClasses` to names), so the per-class split needs no new inter-record
RID coupling for inheritance. The root record's non-link payload — the global-property
table, the collection counter, and the blob-collections set — stays on the root.

The open-time version gate already exists: `SchemaShared.fromStream` (~line 501)
throws a `ConfigurationException` with an export/import redirect when
`schemaVersion != CURRENT_VERSION_NUMBER && VERSION_NUMBER_V5 != schemaVersion`. The
catch is that it accepts two versions, the current number (`VERSION_NUMBER_V4` = 4)
and the legacy `VERSION_NUMBER_V5` (5, a 2.0-M1/M2 compatibility marker), so bumping
the constant alone is a trap (see Plan of Work). A second raw-record reader of the
schema record's `"classes"` field lives outside `SchemaShared`:
`DatabaseCompare.convertSchemaDoc` parses `"classes"` as an EMBEDDEDSET of embedded
class docs and is exercised by the standing backup/restore and import/export ITs, so
the link-set switch must update it to keep those suites green.

This track is foundational: it changes the on-disk format that every later track
builds on. It runs the change under today's storage-leads model (the inversion
arrives in Tracks 3 and 4), so the format change is reviewable in isolation.

## Plan of Work
Rework `SchemaShared.toStream`/`fromStream` so the root schema record carries a link
set to per-class entity records plus the non-link root payload, and each class
serializes into its own standalone record. Today `SchemaClassImpl.toStream` returns
an embedded sub-entity with no RID; the split must save each class as a standalone
record, add its RID to the root's link set on create, and delete the record and
unlink it on drop, mirroring `IndexManagerAbstract.addIndexInternalNoLock`
(`getOrCreateLinkSet(CONFIG_INDEXES).add(...)`) and `IndexManagerAbstract.load`
(bind each entity from the link set), and running inside `saveInternal`'s
`executeInTx` so the multi-record write is atomic. Add the net-new record-RID field
to `SchemaClassImpl`, bound at load from the link set the same way the index manager
binds each index; a new class's record RID resolves through the ordinary
temp→persistent record-id path at commit, not D2's provisional-collection-id scheme.
Ensure a `toStream`→`fromStream` round-trip preserves each class's per-class RID and
the root's non-link payload (global-property table, counter, blob-collections), since
Track 3's tx-local seed re-parses through this serializer.

Under Track 2's storage-leads model `saveInternal` calls `toStream` wholesale, so
every save rewrites the root and every class record. The selective per-class write
(write only the changed class plus the root when its non-link payload changed), the
root-record dirtiness rule, and the F59 root-omission regression depend on D6 dirty
tracking and commit-time reconciliation, which are Track 4. Track 2 delivers the
format those build on, not the write reduction itself.

Bump `CURRENT_VERSION_NUMBER` to a value distinct from both `VERSION_NUMBER_V4` (4)
and the legacy `VERSION_NUMBER_V5` (5), for example 6, and tighten the open-time gate
in `fromStream` so the accepted set is exactly the new current number: drop the
`VERSION_NUMBER_V5 != schemaVersion` accept-arm so the predicate becomes
`schemaVersion != CURRENT_VERSION_NUMBER`. The `ConfigurationException` redirect
message already exists and only needs to keep firing; the work is the constant bump
plus the gate tightening, so both an old version-4 database and a legacy version-5
database reject-and-redirect instead of falling through into the new link-set parser.
Update `DatabaseCompare.convertSchemaDoc` for the link-set root record (resolve the
linked per-class records, or drop the now-redundant root special-case since the
record walk already content-compares the per-class records) and keep the standing
backup/restore and import/export ITs green. The migrator itself is Track 8.

Ordering constraints: the record-RID binding at load must precede any commit-time
load-by-RID write path, and the round-trip RID preservation must hold before Track 3
seeds a tx-local copy from a `fromStream` re-parse.

## Concrete Steps
<!-- Phase A placeholder. -->

1. Migrate the schema to per-class records. Rework `SchemaShared.toStream`/`fromStream` so the root schema record carries a link set to per-class standalone entity records plus the non-link root payload (global-property table, `collectionCounter`, `blobCollections`); save each class as a standalone record and add its RID to the root's link set on create, delete the record and unlink on drop, all inside `saveInternal`'s `executeInTx` so the multi-record write is atomic, mirroring `IndexManagerAbstract.addIndexInternalNoLock` (`getOrCreateLinkSet(CONFIG_INDEXES).add(...)`) and `IndexManagerAbstract.load` (bind each entity from the link set). Add the net-new record-RID field to `SchemaClassImpl`, bound at load from the link set; a new class's record RID resolves through the ordinary temp→persistent record-id path at commit (not D2). Preserve each class's record RID and the root's non-link payload across a `toStream`→`fromStream` round-trip. In the SAME commit: bump `CURRENT_VERSION_NUMBER` to a value distinct from both `VERSION_NUMBER_V4` (4) and `VERSION_NUMBER_V5` (5) and tighten the `fromStream` gate to `schemaVersion != CURRENT_VERSION_NUMBER` (drop the `VERSION_NUMBER_V5` accept-arm) so both a version-4 and a legacy version-5 database reject-and-redirect rather than falling through into the new link-set parser; and update `DatabaseCompare.convertSchemaDoc` for the link-set root (resolve the per-class records, or drop the now-redundant root special-case since the record walk already content-compares them) so the standing `StorageBackup*` / `DbImportExport*` / `LocalPaginatedStorageRestore*` ITs stay green. Ship serializer round-trip tests (per-class RID + root-payload preservation), a standalone-record create/drop test, and a version-gate reject test covering both v4 and legacy v5. The three pieces must land together: the new `fromStream` parses the link set, so the version bump and the `DatabaseCompare` update cannot be split into separate commits without a broken intermediate state. — risk: high (crash-safety/durability + architecture: modifies on-disk schema record serialization and the multi-record atomic schema-save write, the foundational format every later track builds on, shared by the standing backup/restore/import ITs)  [x] commit: 9c7409305b

## Episodes
<!-- Continuous-log. Empty at Phase 1. -->

### Step 1 — commit 9c7409305b, 2026-06-16T16:14Z [ctx=safe]
**What was done:** Replaced the monolithic schema record with a root record
carrying a `classes` LINKSET to one standalone record per class plus the
non-link root payload (global-property table, `collectionCounter`,
`blobCollections`), mirroring the index manager's `CONFIG_INDEXES` link set.
Added a nullable record-RID field to `SchemaClassImpl`, bound at load from the
link set and allocated at commit through the ordinary temp→persistent
record-id path; `SchemaClassImpl.toStream` now writes into a caller-supplied
standalone record instead of an embedded entity. Bumped `CURRENT_VERSION_NUMBER`
from 4 to 6 and tightened the `fromStream` gate to
`schemaVersion != CURRENT_VERSION_NUMBER`, dropping the legacy
`VERSION_NUMBER_V5` accept-arm so both a version-4 and a legacy version-5
database reject-and-redirect to export/import. Removed
`DatabaseCompare.convertSchemaDoc`. Shipped `PerClassSchemaRecordTest`
(round-trip RID + root-payload preservation with durable reload, standalone
create/drop, version-gate reject for v4 and legacy v5) and updated
`CaseSensitiveClassNameTest` and `DatabaseCompareDeadCodeTest`. Four
dimensional reviewers ran on the step (bugs-concurrency, crash-safety,
test-crash-safety, test-structure); crash-safety passed clean, and one
`Review fix:` iteration (9c7409305b) resolved 2 should-fix plus 7 suggestion
findings, all VERIFIED at the iteration-2 gate check with no regression.

**What was discovered:** `DatabaseCompare`'s schema special-case was already
dead code before this change. The schema root and per-class records live in
internal collection id 0, which `skipRecord` skips unconditionally, so
`convertSchemaDoc` was never reached; under the link-set format its
embedded-set coercion would also have corrupted the LINKSET. Dropping it was
the correct one of the two options the step offered, and it keeps the standing
backup/restore `DatabaseCompare` suites green. The `DbImportExport*` ITs named
in the plan are all `@Disabled`, so the live `DatabaseCompare` gate is the
`StorageBackup*` and `LocalPaginatedStorageRestore*` core suites (verified
green). A class left with a non-persistent record id self-heals on the next
schema mutation, because the serializer re-streams every class on each save.
One trivial below-gate nit remains: a message-less `assertNotNull` at
`PerClassSchemaRecordTest:151`, which the test-structure gate check flagged but
could not raise at suggestion severity; Phase C's track-level test review sees
it. Cross-track facts are promoted to Surprises below.

**What changed from the plan:** The plan offered "update
`DatabaseCompare.convertSchemaDoc` or drop the redundant root special-case";
the implementer dropped it after confirming it was unreachable dead code, which
resolves D14's `DatabaseCompare` sub-task by deletion. No track-level deviation.
The selective per-class write, the root-record dirtiness rule, and the F59
root-omission regression stay deferred to Track 4 (D6 dirty tracking); the
migrator stays in Track 8.

**Key files:**
- `SchemaShared.java` (modified)
- `SchemaClassImpl.java` (modified)
- `DatabaseCompare.java` (modified)
- `PerClassSchemaRecordTest.java` (new)
- `CaseSensitiveClassNameTest.java` (modified)
- `DatabaseCompareDeadCodeTest.java` (modified)

## Validation and Acceptance
- A `toStream`→`fromStream` round-trip preserves each class's record RID and the
  root's non-link payload (global-property table, collection counter,
  blob-collections), so Track 3 can re-parse a tx-local copy faithfully.
- Each class serializes into its own standalone record reachable from the root's link
  set; creating a class adds a linked record, dropping a class deletes the record and
  removes the link.
- Opening a pre-bump database with the new binaries is rejected with a redirect to
  export/import, not migrated in place — assert this for BOTH a version-4 record and a
  legacy version-5 record, so the gate does not silently parse the legacy V5 form as
  the new link-set format.
- The standing backup/restore and import/export ITs that compare schema records
  (`DatabaseCompare`-based: `StorageBackup*`, `DbImportExport*`,
  `LocalPaginatedStorageRestore*`) stay green after the `"classes"` link-set switch.

Deferred to Track 4 (D6 dirty tracking plus commit-time reconciliation make them
observable; not testable at Track 2's storage-leads boundary):
- A one-class change writes only that class's record plus the root when its payload
  changed, not the full schema (the write-amplification win).
- The F59 regression: a committed property-create survives a restart with no null
  `globalRef` NPE and a fresh non-colliding collection name (the root-omission hazard
  only arises once selective per-class writes can omit the root).

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Often empty. -->

## Interfaces and Dependencies
- **In scope**: `SchemaShared` serialization (`toStream`/`fromStream`), the
  schema-record link set, the net-new `SchemaClassImpl` record-RID field bound at
  load, the standalone-per-class-record save/link/delete, the
  `CURRENT_VERSION_NUMBER` bump plus the `fromStream` gate tightening (drop the
  `VERSION_NUMBER_V5` accept-arm), `DatabaseCompare.convertSchemaDoc` (the second
  raw-record `"classes"` parser, updated for the link set), and
  serializer/round-trip/version-gate tests.
- **Out of scope**: the export/import migrator (Track 8); the tx-local view and
  commit-time inversion (Tracks 3, 4); the selective per-class write, the
  root-record dirtiness rule, and the F59 root-omission regression (Track 4, D6
  dirty tracking); any index-manager record change (its format is already
  per-entity, D15).
- **Inter-track dependencies**: depends on Track 1 only as an ordering convenience —
  Track 2 creates schema records, not collection files, so its own write path does
  not exercise Track 1's `FileCreatedWALRecord` missing-file replay fix; sequencing
  it on a clean-replay base keeps the format change ahead of Track 4 (the real D10
  consumer). Track 3 consumes the per-class RID binding (the tx-local seed re-parses
  through the link-set-aware serializer); Track 4's commit writes per-class records
  selectively and owns the F59 regression; Track 8's migration migrates this format.
  Bumping `CURRENT_VERSION_NUMBER` also changes the `schema-version` field that
  `DatabaseExport` writes and `DatabaseImport` reads, so Track 8 (D20,
  EXPORTER_VERSION 14→15) must agree on the bumped value.
- **Signatures**: the per-class record-RID field on `SchemaClassImpl` (bound at
  load, allocated at commit for a new class); the schema version constant; no
  public schema API change.

## Base commit
c078c7e1fe17c65ac617174a1de278f02d427e0b
