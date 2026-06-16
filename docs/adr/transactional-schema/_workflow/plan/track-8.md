<!-- workflow-sha: 3e9c22298dfe68d2980646704850c781f8af88d5 -->
# Track 8: Genesis bootstrap and schema-format migration (D18, D20)

## Purpose / Big Picture
After this track, a fresh database bootstraps its schema and default users through
the new transactional path, and an existing database moves to the per-class-record
format through an operator-driven export/import that fails loudly rather than
silently on any partial result.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

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
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->

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
<!-- Phase A placeholder. -->

## Episodes
<!-- Continuous-log. Empty at Phase 1. -->

## Validation and Acceptance
- A fresh database genesis builds the `OUser.name` index before any user insert; the
  default users are created through real engine lookups, not scan fallbacks. The schema
  transaction engages the mutex (no contention at genesis); the following data
  transaction does not (I-U4).
- A truncated or corrupt dump fails the import loudly; a mid-export crash leaves no
  well-formed manifest; opening an old-format database with new binaries is rejected (not
  migrated in place); a complete legacy dump missing any expected section is refused; a
  legacy dump requires the explicit unverified-import acknowledgment flag
  (I-migration-fail-closed).
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
