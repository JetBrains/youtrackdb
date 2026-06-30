<!--MANIFEST
dimension: test-structure
iteration: 1
target: track
prefix: TS
evidence_base: { certs: 0 }
flags: { evidence_trail_exempt: true, exempt_reason: "(a) no refutation or certificate phase to persist" }
cert_index: []
index:
  - id: TS1
    sev: should-fix
    anchor: "TS1"
    loc: "core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaDeguardTest.java:511"
    cert: n/a
    basis: "diff + source read; track-file Surprises 2026-06-29T16:47Z; @Ignore grep across the three files"
  - id: TS2
    sev: suggestion
    anchor: "TS2"
    loc: "core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaCommitReconciliationTest.java:280"
    cert: n/a
    basis: "source read of the two failed-commit tests"
  - id: TS3
    sev: suggestion
    anchor: "TS3"
    loc: "core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaCommitReconciliationTest.java:642"
    cert: n/a
    basis: "source read; cross-test reload-path comparison"
-->

## Findings

### TS1 [should-fix] Known-red test committed without `@Ignore` poisons the suite signal

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaDeguardTest.java`, method `renameClassInsideTransactionRecordsNewNameOnly` (line 511)

**Issue**: This test is red on the branch and is committed without `@Ignore`. The track
file records it (Surprises 2026-06-29T16:47Z): the Step 6 implementer confirmed it is
red at the Step-5 tip (`9a622c0eb3`) by stashing Step 6's edits, but its origin within
Track 4 is unresolved and it was never on the documented known-red list (which carried
only `MetadataWriteMutexTest`). `SchemaDeguardTest` is in the Track 4 cumulative diff —
Step 2 added this test (the `RenameBefore`/`RenameAfter` rename-recording assertions),
and Steps 2/4/5 added the other 7 new tests to the same class. A committed-red test
turns the whole `SchemaDeguardTest` class permanently red, so a future regression in any
of its ~30 tests no longer flips the suite from green to red — the new failure hides
inside an already-failing run. This is the test-structure cost: the class stops being a
trustworthy regression signal. The assertion itself reads as a correct invariant ("the
rename must NOT record the old name, an absent name reads as a drop at commit"), so the
red is either a genuine production bug or a stale test — either way it must be resolved,
not left red.

**Failure scenario**: A developer later breaks `markClassChanged` recording on, say, the
abstract-alter path. `SchemaDeguardTest` was already red because of
`renameClassInsideTransactionRecordsNewNameOnly`, so the CI run for that class is red
before and after the regression; the new break does not change the class's pass/fail
verdict and ships unnoticed.

**Suggestion**: Resolve before merge per the track file's own gate ("it is red and not
`@Ignore`d"). Either (a) fix the production path so the test passes, or (b) if the test
encodes a behavior a later track owns, mark it `@Ignore("<reason>; tracked by <ref>")`
with the same self-documenting breadcrumb style the file already uses for
`crashBeforeCommitOfSchemaCreateLeavesNoCollectionAfterRestore`, so the class returns to
green and keeps its regression signal. Reconciling whether it is pre-track or a mid-track
regression (the open Phase C question) decides which path applies.

### TS2 [suggestion] Failed-commit tests catch `RuntimeException` broadly with an empty body

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaCommitReconciliationTest.java`, methods `failedSchemaCommitLeavesNoPhantomRegistration` (catch at line 280) and `failedSchemaCommitWithDropRestoresDroppedRegistration` (catch at line 353)

**Issue**: Both tests inject a `CommandInterruptedException` through the in-window hook,
then wrap `session.commit()` in `try { … fail(…); } catch (final RuntimeException expected) { /* empty */ }`.
The `fail(…)` before the catch correctly guards the no-throw case, and the comments
explain the routing well. The gap is that the catch swallows *any* `RuntimeException`
without asserting it is the injected fault (or its family). If a future change makes the
commit throw a *different* `RuntimeException` earlier — before reconciliation publishes
or before the drop removes the registration — the registry-cleanliness assertions
afterward would pass trivially (nothing was published to undo), and the test would
report green while no longer exercising the published-but-undone / drop-restore arm it
is named for. The test documents that this distinction is load-bearing (the long comment
explains why a retry-family fault is chosen so the storage stays OPEN), but does not
assert it.

**Failure scenario**: A refactor moves a validation check ahead of `reconcileCollections`
so a schema commit throws `IllegalStateException` before publishing the new collection.
`failedSchemaCommitLeavesNoPhantomRegistration` still catches it (a `RuntimeException`),
`namesBefore` still equals the post-failure registry (nothing was ever published), the
reuse assertion still holds — the test passes without ever reaching the undo arm it
exists to cover.

**Suggestion**: Assert the caught exception identity, e.g.
`assertTrue(expected instanceof CommandInterruptedException || expected.getCause() instanceof CommandInterruptedException)`,
or that its message carries the injected marker (`"injected in-window commit fault"`), so
the test fails loudly if the commit starts failing for a reason other than the in-window
hook.

### TS3 [suggestion] Reload-then-reopen idiom is duplicated across seven tests without a shared helper

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaCommitReconciliationTest.java`, e.g. `classCreateAdvancesCounterPersistedThroughRestartSoNamesDoNotCollide` (line 642), and repeated at lines 124-125, 231-232, 645-646, 727-728, 763-764, 867-868

**Issue**: The two-line durable-round-trip idiom
`schemaShared().reload(session); reOpen("admin", ADMIN_PASSWORD);` appears verbatim in
seven tests. It reads clearly today, but the pairing is load-bearing (the reload re-reads
on-disk per-class records and the reopen forces a `fromStream` re-parse "on every storage
profile", per the class Javadoc) and the order matters. Duplicated, a later change to the
restart contract has to be applied in seven places, and a test that does only one of the
two (a likely copy-paste slip) would silently weaken its durability claim. This is a
readability/maintainability nit, not a correctness problem — the tests are correct as
written.

**Suggestion**: Extract a small private helper, e.g.
`private void forceDurableReload() { schemaShared().reload(session); reOpen("admin", ADMIN_PASSWORD); }`,
with one Javadoc line stating why both calls are needed, and call it from the seven
sites. The intent ("force a durable round trip") becomes named, and the restart contract
lives in one place.

## Evidence base
