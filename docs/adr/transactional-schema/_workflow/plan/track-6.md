<!-- workflow-sha: 3e9c22298dfe68d2980646704850c781f8af88d5 -->
# Track 6: Base-keyed engine files and metadata-only rename (D11, D16, D17)

## Purpose / Big Picture
After this track, renaming a class touches zero storage files and the class's
indexes keep accelerating queries under the new name, because collection names come
from a counter alone and engine files are keyed by the stable engine id.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Generate collection names from a counter alone so a class rename touches no
collection file, derive every index-engine file base (data, null-bucket, histogram)
from the stable engine id, and apply the commit-only class-rename re-association that
re-keys `classPropertyIndex` and updates each affected definition's `className` so the
index keeps accelerating under the new class name. The inert index-name rename and
`ALTER INDEX … RENAME` stay deferred to YTDB-1066.

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

#### D11: Artificial collection names, decoupled from class names
- **Alternatives considered**: keep deriving the collection name from the class name (`<className>_<counter>`).
- **Rationale**: today a class rename physically renames all four collection files via `writeCache.renameFile`, which runs outside the WAL and is not crash-revertible — the only non-WAL-safe physical collection mutation. Generating the collection name from a counter alone removes that path and strengthens D9's "structurally inert" rename to "touches zero storage."
- **Risks/Caveats**: contained to the name-generation site (`SchemaEmbedded.createCollections`) plus neutering `SchemaClassImpl.renameCollection`; the metadata layer is already id-based.
- **Implemented in**: this track
- **Full design**: design.md §"Base-keyed engine files and metadata-only rename"

#### D16: Stable-base-keyed engine files; index rename is metadata-only
- **Alternatives considered**: id-keyed files for every engine plus migrate the legacy name-keyed files (re-introduces the non-WAL-safe `writeCache.renameFile` path for zero benefit); a dual-base compatibility path (id for new engines, name for pre-existing).
- **Rationale**: under D20's import-only migration no name-keyed engine file can exist in a v1 database, so the dual-base path is dropped and every engine file base derives from the stable engine id unconditionally. The data, null-bucket, and histogram (`.ixs`) files all derive from the base, so `StorageComponent` stores an immutable file base and `setName` changes only the logical name. An index rename then re-keys metadata only and never touches the engine, its files, or its B-tree data.
- **Risks/Caveats**: base-keying also dissolves the same-name drop-and-recreate file collision, so the code needs no file-name recycle branch and replays the WAL through one uniform path.
- **Implemented in**: this track
- **Full design**: design.md §"Base-keyed engine files and metadata-only rename"

#### D17: v1 does the metadata-only class-rename re-association; index-name rename deferred
- **Alternatives considered**: ship the full inert index-name rename and `ALTER INDEX … RENAME` now (deferred to YTDB-1066).
- **Rationale**: a class rename re-keys `classPropertyIndex` (old to new class name) and updates each affected definition's `className` (recursing composites), so the index keeps accelerating because the planner resolves by class name. The index's own name lags (an auto-named `Foo.prop` reads as the old name on class `Bar`) but stays correct and accelerated.
- **Risks/Caveats**: the re-association is commit-only, so the renaming tx's own queries on the renamed class fall back to an unaccelerated scan until commit (the same staleness D13 accepts). The commit-time application publishes replacement objects via the CHM put rather than field writes into the shared definition, so lock-free readers never see a torn `className`. `IndexDefinition.className` has no setter today.
- **Implemented in**: this track
- **Full design**: design.md §"Base-keyed engine files and metadata-only rename"

## Outcomes & Retrospective
<!-- Continuous-log. Empty at Phase 1. -->

## Context and Orientation
Today a collection name is derived from the class name (`<className>_<counter>` at
`SchemaEmbedded.createCollections`), so renaming the class renames its collection
files through `writeCache.renameFile` (`SchemaClassImpl.renameCollection`), the one
non-WAL-safe physical collection mutation. Index-engine file names today are keyed by
the index name: the data file is `getFullName()`, the null-bucket file is
`getName() + nullFileExtension`, and the histogram `.ixs` component sources its own
name. `IndexDefinition.className` has no setter; the planner resolves an index by its
class name.

This track depends on the commit machinery (Track 4) for commit-only application and
on the index overlay (Track 5) for the in-place rename category. It removes the last
non-WAL-safe physical path and makes rename a pure metadata change.

## Plan of Work
Generate collection names from a counter alone at the name-generation site and neuter
`renameCollection` so a class rename renames no collection file. Decouple the
index-engine file base from the logical name: store an immutable base on
`StorageComponent` keyed by the stable engine id, derive the data, null-bucket, and
histogram files from it, and make `setName` change only the logical name. Add the
`IndexDefinition.className` setter and apply the class-rename re-association
commit-only — re-key `classPropertyIndex` old-to-new and update each affected
definition's `className` (recursing composites), publishing replacement objects via
the CHM put under the commit's exclusive lock.

Ordering constraints: the base-keying must land before or with the rename
re-association so a renamed class's engine files do not move; the re-association is
commit-only and rides Track 4's exclusive-lock window.

## Concrete Steps
<!-- Phase A placeholder. -->

## Episodes
<!-- Continuous-log. Empty at Phase 1. -->

## Validation and Acceptance
- Rename a class and assert zero file renames, zero collection create/drop, and intact
  data; a crash during the rename commit leaves a recoverable, consistent state
  because no file is renamed (I-U2).
- Rename a class with an auto-named index, then query the renamed class; the query
  accelerates through the same engine with no rebuild and no engine-file rename (I-U3).
- A same-name drop-and-recreate in one workload produces no file collision (base-keying
  dissolves the recycle branch).

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Often empty. -->

## Interfaces and Dependencies
- **In scope**: the counter-only collection-name generation (`SchemaEmbedded.createCollections`);
  neutering `SchemaClassImpl.renameCollection`; the immutable engine file base on
  `StorageComponent` (data, null-bucket, histogram derive from it) with `setName`
  changing only the logical name; the `IndexDefinition.className` setter; the commit-only
  class-rename re-association (`classPropertyIndex` re-key, `className` update recursing
  composites, CHM-put publication); rename/crash tests.
- **Out of scope**: the inert index-name rename and `ALTER INDEX … RENAME` (YTDB-1066);
  the overlay machinery itself (Track 5 — this track rides its in-place rename category);
  the commit's lock acquisition (Track 4).
- **Inter-track dependencies**: depends on Track 4 (commit-only application under the
  exclusive lock) and Track 5 (the overlay's in-place rename category). No downstream
  track consumes this one's output directly; genesis (Track 8) and migration (Track 8)
  are independent of rename.
- **Signatures**: `IndexDefinition.className` setter (recursing composites); an immutable
  file base on `StorageComponent` keyed by the stable engine id; counter-only collection
  name generation.
