<!--MANIFEST
review: test-structure
scope: track-5
range: 54000d904b4daf34f9e1c3488ac1f549e2401859..HEAD
iteration: 1
level: high
prefix: TS
findings_total: 4
evidence_base: { certs: 0 }
cert_index: []
flags: [evidence-trail-exempt]
index:
  - id: TS1
    sev: should-fix
    anchor: "TS1 [should-fix] Concurrency tests in SchemaDeguardTest and TxAwareSchemaSnapshotTest lack the stuck-thread class Timeout the sibling test uses"
    loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/TxAwareSchemaSnapshotTest.java:710
    cert: n/a
    basis: "read the three test classes + DbTestBase; DbTestBase's only @Rule is TestName (fresh DB/test), no class Timeout; CommitTimeIndexBuildTest has a stuck-thread Timeout, the two concurrency siblings do not"
  - id: TS2
    sev: suggestion
    anchor: "TS2 [suggestion] Test name says PreservesVersion but the assertion now checks the version advances"
    loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaSharedLockApiTest.java:221
    cert: n/a
    basis: "diff: assertion changed from versionBefore+1==versionAfter to versionAfter>versionBefore; method name unchanged"
  - id: TS3
    sev: suggestion
    anchor: "TS3 [suggestion] deferredIndexCreateResolvesCollectionWithoutThrowing Javadoc lead overstates what the body verifies"
    loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/CommitTimeIndexBuildTest.java:185
    cert: n/a
    basis: "diff: opening sentence claims provisional-id (<=-2) resolution; body indexes a class whose collection is already real/committed; second paragraph admits the pure same-tx path is unreachable"
  - id: TS4
    sev: suggestion
    anchor: "TS4 [suggestion] SchemaDeguardTest has accreted a distinct index-overlay integration cluster"
    loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaDeguardTest.java:43
    cert: n/a
    basis: "read: ~420 new lines of overlay routing/snapshot-rebuild/planner-skip/concurrency tests in a 1399-line class; IndexOverlayTest Javadoc names SchemaDeguardTest as the home"
-->

## Findings

### TS1 [should-fix] Concurrency tests in SchemaDeguardTest and TxAwareSchemaSnapshotTest lack the stuck-thread class Timeout the sibling test uses

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/TxAwareSchemaSnapshotTest.java`, method `concurrentSessionSnapshotNeverSeesATxCreatedClassOrProvisionalId` (line 710); same gap in `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaDeguardTest.java`, method `overlayIsInvisibleToAConcurrentReaderOnAnotherThread` (line 1300).

**Issue**: Both classes spawn a second session on a reader thread and drive schema/index DDL on the main thread. Neither class declares a class-level `Timeout` rule, and `DbTestBase`'s only `@Rule` is `TestName` (verified: it creates a fresh DB per method, so DB-level isolation is fine, but it provides no timeout). The sibling `CommitTimeIndexBuildTest` (line 68) installs a `Timeout.builder().withTimeout(120, SECONDS).withLookingForStuckThread(true)` rule precisely because "a commit-window primitive that regressed to re-taking the non-reentrant `stateLock` would busy-spin forever rather than throw, hanging the whole surefire fork with no signal." The track's own episode guidance states concurrent stress tests carry stuck-thread class timeouts. The reader threads here are bounded by `join(timeout)` / latch `await(timeout)`, but the main-thread schema operations (`createClass`, `createIndex`, `commit` under the schema-carry write lock) have no timeout backstop. Under exactly the busy-spin / deadlock regression class this whole track guards against, a hang on the main thread poisons the shared surefire fork for every other test in it, with no diagnostic naming the stuck thread.

**Suggestion**: Add the same stuck-thread `Timeout` `@Rule` these two classes are missing (matching `CommitTimeIndexBuildTest`), so a regression that hangs a main-thread schema commit fails fast with a thread dump instead of stalling the fork.

### TS2 [suggestion] Test name says PreservesVersion but the assertion now checks the version advances

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaSharedLockApiTest.java`, method `releaseSchemaWriteLockWithoutSaveDropsSnapshotButPreservesVersion` (line 221).

**Issue**: The assertion changed in this diff from `versionBefore + 1 == versionAfter` to `versionAfter > versionBefore`, and the comment was updated to explain the process-wide version generator. The method name still says `...PreservesVersion`, which reads as "keeps the version the same" and directly contradicts the assertion that the version advances. A reader scanning method names for what is under test gets the wrong contract.

**Suggestion**: Rename to reflect the verified behavior, e.g. `releaseSchemaWriteLockWithoutSaveDropsSnapshotButAdvancesVersion`.

### TS3 [suggestion] deferredIndexCreateResolvesCollectionWithoutThrowing Javadoc lead overstates what the body verifies

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/CommitTimeIndexBuildTest.java`, method `deferredIndexCreateResolvesCollectionWithoutThrowing` (line 185).

**Issue**: The Javadoc opens with "A class created earlier in the same transaction, then indexed later in that transaction, resolves its provisional collection id (`<= -2`) at commit...". The body creates the class and its property at the top level (so the class owns a real, committed collection), then creates the index in a separate transaction, so the provisional-id (`<= -2`) resolution path the lead sentence advertises is never exercised. The later paragraph honestly concedes the pure same-transaction path is unreachable, but the contradictory lead can mislead a maintainer into believing provisional-id resolution is covered here. Tests are read as documentation, so the lead sentence should describe what this test actually asserts.

**Suggestion**: Rewrite the opening sentence to describe the exercised scenario (a deferred index on a committed class resolving its real collection name without throwing), and keep the provisional-id path as an explicit "not covered until property-create is de-guarded" forward note.

### TS4 [suggestion] SchemaDeguardTest has accreted a distinct index-overlay integration cluster

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaDeguardTest.java` (line 43; the file is now 1399 lines).

**Issue**: This diff adds roughly 420 lines to `SchemaDeguardTest` covering a coherent but distinct behavior area from schema de-guarding: the per-session index-overlay routing seam, the snapshot force-rebuild, the planner skip-unbuilt guard, the membership ripple, and cross-session/cross-thread overlay isolation. `IndexOverlayTest`'s own class Javadoc points at `SchemaDeguardTest` as the home for "the routing seam, the snapshot force-rebuild, the planner skip", so the placement is deliberate, but the class is drifting toward a catch-all that mixes two behavior areas in one large file.

**Suggestion**: Consider extracting the overlay-integration tests into a dedicated class (for example `IndexOverlayIntegrationTest`) so each class stays focused on one behavior area and the overlay tests are discoverable next to `IndexOverlayTest` and `CommitTimeIndexBuildTest`. Low priority; no correctness impact.

## Evidence base

<!-- This dimension is evidence-trail-exempt: reason (a) no refutation or certificate
phase to persist. No #### C<n> certificate entries are written; evidence_base certs: 0. -->
