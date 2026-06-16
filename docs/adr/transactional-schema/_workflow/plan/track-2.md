<!-- workflow-sha: 3e9c22298dfe68d2980646704850c781f8af88d5 -->
# Track 2: Per-class schema records (D14)

## Purpose / Big Picture
After this track, a one-class schema change writes one class record plus the root
when its payload changed, instead of rewriting the whole schema, and the schema
version gate rejects an old-format database with a redirect to export/import.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Replace the single monolithic schema record with a root record that links to one
entity record per class, mirroring the index-manager pattern, add the net-new
per-class record-RID field bound at load, write only the changed class records
plus the root when its non-link payload changes, and bump the schema version into
a reject-and-redirect gate. This is the persistence foundation and the
write-amplification reduction YTDB-382 exists for; the tx-local seed (Track 3)
binds the per-class RIDs this track introduces.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->

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

This track is foundational: it changes the on-disk format that every later track
builds on. It runs the change under today's storage-leads model (the inversion
arrives in Tracks 3 and 4), so the format change is reviewable in isolation.

## Plan of Work
Rework `SchemaShared.toStream`/`fromStream` so the root schema record carries a
link set to per-class entity records plus the non-link root payload, and each
class serializes into its own record. Add the net-new record-RID field to
`SchemaClassImpl`, bound at load from the link set the same way the index manager
binds each index. Ensure a `toStream`→`fromStream` round-trip preserves the per-class
RID, since Track 3's tx-local seed depends on it. Wire the root-record dirtiness rule
so a property-create (global-property table) and an alter-add-collection (counter)
put the root in the write set. Bump `CURRENT_VERSION_NUMBER` and turn the version
check on open into a reject-and-redirect gate pointing at export/import (the migrator
itself is Track 8).

Ordering constraints: the record-RID binding at load must precede any commit-time
load-by-RID write path, and the round-trip RID preservation must hold before Track 3
seeds a tx-local copy from a `fromStream` re-parse.

## Concrete Steps
<!-- Phase A placeholder. -->

## Episodes
<!-- Continuous-log. Empty at Phase 1. -->

## Validation and Acceptance
- A one-class change writes only that class's record (plus the root when its
  payload changed), not the full schema.
- A committed property-create followed by a restart and a read returns the
  property (no null `globalRef` NPE) and allocates a fresh non-colliding collection
  name (no stale counter) — the F59 regression.
- A `toStream`→`fromStream` round-trip preserves each class's record RID.
- Opening an old-format (pre-bump) database with the new binaries is rejected with
  a redirect to export/import, not migrated in place.

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
  load, the root-record dirtiness wiring, the `CURRENT_VERSION_NUMBER` bump, the
  open-time reject-and-redirect gate, and serializer/round-trip tests.
- **Out of scope**: the export/import migrator (Track 8); the tx-local view and
  commit-time inversion (Tracks 3, 4); any index-manager record change (its format
  is already per-entity, D15).
- **Inter-track dependencies**: depends on Track 1 (clean replay underneath).
  Track 3 consumes the per-class RID binding (the tx-local seed re-parses through
  the link-set-aware serializer); Track 4's commit writes per-class records;
  Track 8's migration migrates this format.
- **Signatures**: the per-class record-RID field on `SchemaClassImpl` (bound at
  load, allocated at commit for a new class); the schema version constant; no
  public schema API change.
