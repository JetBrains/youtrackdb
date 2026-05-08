# Track 21: Storage B-tree & Impl

## Description
    
    Write tests for B-tree index storage and storage implementation
    internals. These are the lowest-level storage components, tightly
    coupled to page-based I/O and WAL operations — expect to fall short of
    85%/70% targets per Decision Record D4 in `implementation-plan.md`
    (*Accept lower coverage for storage internals*).
    
    > **Reconstruction note:** the original backlog body for Track 21 was
    > in the recovery gap (see `implementation-plan.md` § Operational Notes
    > → "Reconstruct-on-demand tracks"). The
    > What/How/Constraints/Interactions block below is regenerated from the
    > plan's `**Scope:**` indicator (~5 steps covering B-tree multivalue,
    > B-tree singlevalue, B-tree local, storage impl, and verification),
    > the design's `## Testing Storage & Cache Components` section, the
    > post-Track-20 per-package coverage measurement (re-run via
    > `coverage-analyzer.py` against the existing
    > `.coverage/reports/youtrackdb-core/jacoco.xml` at Phase A entry),
    > Track 18's explicit cross-track hints to Track 21 (BTree
    > `doClearTree` error paths, `RemoteIndexEngine` factory branch,
    > `BTreeEngineConstructorValidationTest` shape pins already in place),
    > and Track 19/20's accumulated patterns. **Phase A reviews (technical
    > + risk + adversarial, iter-1) revised the decomposition budget from
    > "~5 steps" to "~6–7 steps" with small-package quick-wins first,
    > corrected a critical misreference (the live B-tree engine class is
    > `singlevalue/v3/BTree`, NOT the non-existent
    > `CellBTreeSingleValueV3` / `CellBTreeMultiValueV2` / `SBTreeV2`),
    > reframed `multivalue/v2` and `local/v2` as WAL-replay-only D4
    > packages reachable via direct bucket / `*Op` round-trip tests (not
    > via the live engine), added a `PageEntryFixture` extraction as the
    > first artifact (Track 20's deferred TS7), and tightened the
    > bug-fix-inline policy for data-corruption / index-consistency /
    > recovery-path defects.** Phase B will re-measure per-package coverage
    > on entry to absorb post-Phase-A drift before per-step decomposition
    > is acted on.
    
    > **What:**
    > - Raise per-package coverage on the storage **B-tree** and
    >   **impl/local** clusters with **concrete numeric targets per D4**,
    >   not aspirational 85%/70% with vague fallback. Per-package targets
    >   (committed at decomposition; verified at the verification step):
    >   - `storage.impl.local` (top-level, dominated by `AbstractStorage`
    >     ~3 100 instr-lines per JaCoCo, ~6 700 raw lines including
    >     blanks/comments) — target **~75% line / ~62% branch** on entry
    >     (post-Track-20 baseline 62.8% / 59.0%, 1 157 uncov lines / 3 114
    >     total). Aspirational 85% line is unreachable from surefire scope
    >     because most of the recovery / freeze / async-rebuild paths in
    >     `AbstractStorage` are exercised only via integration tests
    >     (`StorageTestIT`, `LocalPaginatedStorageRestoreFromWALIT`,
    >     `StorageBackupMTIT`, `StorageEncryptionTestIT`) discovered by
    >     the default failsafe `**/*IT.java` glob under the
    >     `ci-integration-tests` profile — surefire (`package -P coverage`)
    >     does not run failsafe, so these don't contribute to JaCoCo. Many
    >     small-class helpers in this package (`AtomicOperationIdGen`,
    >     `TsMinHolder`, `StaleTransactionMonitor`, `WALVacuum`,
    >     `CollectionBrowse{Entry,Page}`) are unit-testable and contribute
    >     to closing the gap to 75%.
    >   - `storage.index.sbtree.singlevalue.v3` — target **≥85% / ≥70%**
    >     on entry (baseline 84.3% / 70.5%, 403 uncov / 2 563 total —
    >     close-to-gate top-up only). **This is the live B-tree engine
    >     used by version 4 (the only live version per
    >     `BTreeMultiValueIndexEngine.java:73-83` and
    >     `BTreeSingleValueIndexEngine.java`); the lifecycle owner is
    >     `com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.BTree`.**
    >   - `storage.index.sbtree.multivalue.v2` — **WAL-replay-only D4
    >     package**, target **≥85% / ≥70%** on entry (baseline 81.6% /
    >     65.9%, 338 uncov / 1 838 total). Production engines reject
    >     `version == 2` with `IllegalArgumentException`
    >     (`BTreeMultiValueIndexEngine.java:73`); only WAL replay against
    >     legacy databases reaches this code via `*Op.java` records
    >     registered in `PageOperationRegistry`. Coverage strategy is
    >     direct bucket-class construction + `*Op` round-trip tests, NOT
    >     live-engine lifecycle through `BTree<>`.
    >   - `storage.index.sbtree.local.v2` — **WAL-replay-only D4 package**,
    >     target **≥85% / ≥70%** on entry (baseline 87.6% / 69.9%, 111
    >     uncov / 896 total — branch-only top-up). Same WAL-replay-only
    >     framing as `multivalue/v2`: no top-level `SBTreeV2` lifecycle
    >     class exists; the package contains `SBTreeBucketV2` /
    >     `SBTreeNullBucketV2` plus `*Op` WAL records. Coverage strategy
    >     is direct bucket / Op tests.
    >   - `storage.index.nkbtree.normalizers` — target **TBD at Phase B
    >     Step 1** (baseline 71.5% / 20.0%, 45 uncov / 158 total —
    >     pathological 20% branch%). Adversarial review (A3) identified
    >     dead helpers in `DecimalKeyNormalizer.java:43-101`
    >     (`scaleToDecimal128`, `clampAndRound`, `ensureExactRounding`,
    >     `unsigned`) with zero production callers driving the phantom
    >     uncovered branches. Risk review (R7) flagged that JaCoCo's
    >     `assert`-line phantom-branch trap may also contribute. **Phase
    >     B Step 1 will re-measure with `coverage-gate.py` (which strips
    >     `assert`-line branches) on this package; if real branch% is
    >     already ≥70%, the package is smoke-pin completion only.**
    >     Otherwise commit to a lower branch% target (e.g. ≥50%) with the
    >     dead-helper deletion forwarded to Track 22.
    >   - `storage.index.sbtree` (top-level, `TreeInternal.java`) — target
    >     **≥85% / ≥70%** (baseline 0% / 0%, 7 uncov / 7 total — small
    >     interface/util slice).
    >   - `storage.index.engine` and `storage.index.versionmap` — target
    >     **≥85%** line where measurable (baselines 0% line / 100% branch
    >     and 0% line / 100% branch respectively, 26 + 20 uncov / 46 total
    >     — mostly interface-only declarations; the "100% branch" is a
    >     JaCoCo unmeasurable-sentinel for branchless interfaces, not
    >     actual branch coverage. Treat as smoke-pin shape coverage only).
    >   - `storage.impl.local.paginated` (top-level, the seven non-WAL /
    >     non-atomicoperations classes) — target **≥85% / ≥70%** on entry
    >     (baseline 79.5% / 54.2%, 46 uncov / 224 total — small package,
    >     branch-only top-up).
    >   - **D4 dead-code accepted (0% line, forwarded to Track 22 for
    >     deletion via `*DeadCodeTest` shape pins per Track 17 / Track 18
    >     precedent):**
    >     - `storage.index.sbtree.singlevalue.v1` — entire package, 242
    >       LOC, **0 production references project-wide** (PSI-confirmed
    >       at Phase A: `CellBTreeBucketSingleValueV1` and
    >       `CellBTreeSingleValueEntryPointV1` both report 0 main + 0
    >       test refs).
    >     - `storage.index.sbtree.local.v1` — **no coverage target;
    >       entire package forwarded to Track 22 deletion**. PSI-confirmed
    >       partial dead code: `SBTreeBucketV1` (0 main / 23 test refs),
    >       `SBTreeNullBucketV1` (0 main / 4 test refs), `SBTreeValue`
    >       (8 main refs but **all intra-package within v1 itself**, so
    >       transitively dead once buckets are deleted). Existing
    >       `SBTreeLeafBucketV1Test`, `SBTreeNonLeafBucketV1Test`,
    >       `SBTreeNullBucketV1Test` are themselves coverage tests of
    >       dead code — Track 22 deletes the v1 source + the legacy tests
    >       + the new Track-21 `*DeadCodeTest` shape pin in one
    >       coordinated commit. Baseline 66.6% / 44.4%, 102 uncov lines
    >       accepted under D4 dead-code allowance (NOT counted against
    >       Track 21's coverage budget).
    >     - **Adversarial-review note (A2 deferred):** the iter-1
    >       adversarial review argued for inline deletion in Track 21 of
    >       v1 source + stale tests rather than forwarding to Track 22.
    >       Decision: keep status quo (Track 22 absorbs deletion in
    >       lockstep). Rationale: (a) consistent with Track 17 / Track 18
    >       precedent; (b) keeps Track 21 PR test-additive only —
    >       expanding it to delete production source plus 7 stale test
    >       files broadens the diff and review surface non-trivially;
    >       (c) the "double-touch" cost is cosmetic — Track 22's deletion
    >       commit removes dead source + legacy tests + Track 21's new
    >       DeadCodeTest pin atomically.
    > - **Aggregate gap on entry (in-scope, post-Track-20 baseline):**
    >   ~2 600 uncovered lines across 12 packages. Closing ~50% of those
    >   yields ~1.4 pp aggregate line gain — Track 21 + Track 22 must
    >   together close ~6 pp line / ~1 pp branch to reach the 85% / 70%
    >   plan target.
    
    > **How:**
    > - **At Phase B Step 1 start:** regenerate per-package coverage via
    >   `./mvnw -pl core -am clean package -P coverage` +
    >   `coverage-analyzer.py` against the fresh
    >   `.coverage/reports/youtrackdb-core/jacoco.xml`. The targets above
    >   are anchored to the post-Track-20 jacoco snapshot (re-measured at
    >   Phase A entry); verify before committing per-step strategy in case
    >   any incidental drift altered the picture. **Additionally re-measure
    >   `nkbtree.normalizers` via `coverage-gate.py`** (which strips
    >   `assert`-line branches) to determine the real branch% baseline
    >   before committing to a target.
    > - **Build cost discipline:** the coverage build is ~10 min per run.
    >   For per-step iteration use `./mvnw -pl core test -Dtest=<NewClass>`
    >   (~30 s); only invoke `package -P coverage` once per step at the
    >   end to verify the gate. Track 19 ran ~7 coverage cycles, Track 20
    >   ran ~6. Track 21's revised step count of 6–7 implies **~6–7 step
    >   verifications + 1 Phase C run + 1 Phase A pre-entry re-measure
    >   if drift is suspected**. Build-cost ceiling loosened from the
    >   original "~5 cycles + 1 Phase C" to match precedent.
    > - **IT-coverage caveat:** `package -P coverage` only runs surefire,
    >   not failsafe. Many `AbstractStorage` paths are exercised only via
    >   integration tests (`StorageTestIT`, `LocalPaginatedStorageRestoreFromWALIT`,
    >   `StorageBackupMTIT`, `StorageEncryptionTestIT`) discovered by the
    >   default failsafe `**/*IT.java` glob under the `ci-integration-tests`
    >   profile (these classes are NOT enumerated in `core/pom.xml`'s
    >   failsafe `<includes>`; they pick up via the default name glob).
    >   Surefire does not run failsafe, so these don't contribute to JaCoCo.
    >   Before adding a unit test for an `AbstractStorage` branch, sanity-
    >   check whether an existing `*IT` already exercises the path —
    >   prefer extracting a focused public-API slice over reimplementing
    >   IT scenarios as unit tests.
    > - **Existing direct-construction precedent for B-tree surefire
    >   tests:** the Track 19 / Track 20 page-level direct-memory pattern
    >   applies here verbatim (`ByteBufferPool.acquireDirect()` →
    >   `CachePointer` → `incrementReadersReferrer` → exclusive lock →
    >   ops → `decrementReferrer` in `finally` + `bufferPool.clear()` +
    >   `allocator.checkMemoryLeaks()` in `@After`). **Step 1 of Track 21
    >   will extract this scaffold into a shared `PageEntryFixture` test
    >   utility** (resolves Track 20's deferred TS7 — `LockFreeReadCacheFileOpsTest`,
    >   `BoundedBufferRingTest`, `BoundedBufferDrainTest`, `CacheEntryImplTest`
    >   plus all new Track 21 bucket tests will share it). Pure test
    >   infrastructure (no production change), placed under
    >   `core/src/test/.../storage/cache/PageEntryFixture.java` or
    >   `test-commons` as decomposition decides. Subsequent steps consume
    >   the fixture rather than copy-pasting.
    > - **Live-engine vs WAL-replay packages — class-name discipline:**
    >   - Live B-tree engine (version 4, the only production version):
    >     **`com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.BTree`**
    >     — used for both single-value and multi-value engines via two
    >     wrapped `BTree<>` instances (`BTreeMultiValueIndexEngine.java:75-83`).
    >     Lifecycle tests against this class.
    >   - WAL-replay-only packages (`multivalue/v2`, `local/v2`): **direct
    >     bucket / `*Op` round-trip tests only**. Construct the bucket
    >     class with a directly-allocated `CachePointer`, perform mutations,
    >     read back; for `*Op` records, build the WAL record, serialize
    >     to bytes, deserialize, replay against a fresh page, assert
    >     post-replay state. Do NOT route through `BTree<>` — the live
    >     engine rejects version 2 / 3.
    >   - The non-existent class names `CellBTreeSingleValueV3`,
    >     `CellBTreeMultiValueV2`, `SBTreeV2` from earlier draft must
    >     not appear in any test class name. PSI-confirmed at iter-1
    >     review.
    > - **Component lifecycle tests via temp dir** (design.md §Testing
    >   Storage & Cache Components): create component → open → operations
    >   → close → verify state, using a `Files.createTempDirectory(...)`
    >   path with UUID-suffix per the project-global temp-path rule. Use
    >   for `BTree<>` (singlevalue/v3) lifecycle and `AbstractStorage`
    >   slices that need a real on-disk presence.
    > - **Concurrency probes (Track 20 CyclicBarrier MT-race pattern):**
    >   one falsifiable MT probe is in scope for Track 21 —
    >   **`StaleTransactionMonitor`** carries a `volatile ScheduledFuture<?>`
    >   and a non-atomic `if (scheduledFuture != null) return` start
    >   guard (`StaleTransactionMonitor.java:86, 120-126, 142, 363`).
    >   Add a CyclicBarrier MT probe that races concurrent `start()`
    >   calls and asserts at-most-one schedule. Reusable shape pin if
    >   the production code is later hardened.
    > - **Cross-track hints from Track 18 to fold in:**
    >   - `BTreeSingleValueIndexEngine.doClearTree` and
    >     `BTreeMultiValueIndexEngine.doClearTree` "removed 0 entries"
    >     error paths — uncovered, require real B-tree data with
    >     page-cursor invalidation. Cover via the `core/index/engine/v1`
    >     test extension or via a focused B-tree-engine lifecycle test.
    >     **Pre-step probe with `coverage-gate.py` to confirm which
    >     uncov lines are post-`assert`-exclusion candidates** — JaCoCo
    >     shows `BTreeSingleValueIndexEngine` at 84.5% line (35 missed),
    >     `BTreeMultiValueIndexEngine` at 86.5% line (33 missed); a
    >     fraction of the missed lines are likely assert-failure-message
    >     phantom branches and may already gate-pass after exclusion.
    >   - `DefaultIndexFactory.createIndexEngine("remote", ...)` returns
    >     `RemoteIndexEngine` — Track 21 may explicitly cover that
    >     factory branch via a tiny smoke pin.
    >   - `BTreeEngineConstructorValidationTest` already shape-pins
    >     version-guard constructor branches — do **not** re-test.
    > - **Test-record-ID discipline (Track 20 forward):** Track 20
    >   established `Track20WALTestRecordIds` with the `[600, 699]` test
    >   range. If Track 21 needs to add new WAL records (it should not —
    >   B-tree uses pre-existing WAL records), define
    >   `Track21WALTestRecordIds` for `[700, 799]` per the same pattern.
    >   Most likely Track 21 reuses existing WAL types via the page-level
    >   pattern.
    > - **Mockito void-stub trap (Track 20 codify-note):** prefer
    >   `doReturn(...)` and rely on default-null for void methods when
    >   stubbing `WriteCache` / `CacheEntry` / `AtomicOperation`
    >   collaborators.
    > - **Falsifiable regression + WHEN-FIXED markers** (Tracks 5–17
    >   precedent): pin every latent production bug as a falsifiable
    >   regression test asserting current (buggy) behaviour with a
    >   `// WHEN-FIXED: Track 22` marker — **except for data-corruption,
    >   index-consistency, and recovery-path bugs, which are fixed inline
    >   in Track 21 per the strengthened constraint below**.
    
    > **Constraints:**
    > - **D4 storage-internal allowance** applies: per-package gates may
    >   be set below the standard 85%/70% with explicit justification
    >   (IT-shadowed paths, integration-shaped logic, dead-helper-driven
    >   structural ceilings). The aggregate 85%/70% remains the
    >   plan-level invariant — Track 22's final sweep absorbs the
    >   residual gap.
    > - **Test-additive plus targeted inline production fixes.** Production-
    >   source modifications are permitted in Track 21 in two cases:
    >   (a) **data-corruption, index-consistency, or recovery-path bugs
    >   surfaced during testing** — fix inline with a regression test
    >   asserting the fixed behaviour, per the plan's Non-Goals exception
    >   ("All bugs found during testing or code review must be fixed and
    >   covered by regression tests"). (b) **Test infrastructure
    >   refactoring** that increases testability without changing public
    >   API (e.g., `PageEntryFixture` extraction). Lower-severity bugs
    >   (Javadoc, non-idempotent close, vacuous assertions, etc.) and
    >   structural dead-code deletions are forwarded to Track 22 via
    >   WHEN-FIXED markers and the absorption block.
    > - **Build cost ceiling:** ~6–7 full coverage runs (one per step's
    >   verification) + 1 Phase C re-measure + 1 Phase A pre-entry
    >   re-measure if drift is suspected. Per-step iteration uses
    >   targeted `-Dtest=<Class>` invocations.
    > - **No backwards compatibility** for v1 packages: the v1
    >   single-value bucket / entry-point classes have 0 production
    >   references; any test added against them is shape-pin /
    >   dead-code-marker only, never a coverage test that would inhibit
    >   Track 22 deletion.
    > - **Project-global temp-path rule:** every `Files.createTempDirectory`
    >   / `/tmp/...` path must include a UUID or `$$` suffix. **Tests
    >   touching `AbstractStorage` (which routes through `OEngine`
    >   registry by database name) must additionally use a per-test
    >   database name with UUID/`$$` suffix, not just a per-test temp
    >   directory** — concurrent surefire forks otherwise collide on
    >   `OEngine.getStorage(name)`. Tests must delete on `@After` and
    >   not rely on JVM-shutdown cleanup.
    > - **Page-level cleanup invariants** (Track 19 codified, Track 20
    >   reinforced, Track 21 Step 1 extracts to fixture): tests that
    >   allocate via `ByteBufferPool.acquireDirect()` must
    >   `bufferPool.clear()` and `allocator.checkMemoryLeaks()` in
    >   `@After`. Step 1 produces `PageEntryFixture`; Steps 2+ consume it.
    > - **Spotless** (project-global): every commit must pass
    >   `./mvnw -pl core spotless:check`. Tests follow project import
    >   order (static first, no wildcards).
    > - **Static-state isolation:** any test that mutates process-wide
    >   static state (engine registries, schema version overrides) must
    >   carry `@Category(SequentialTest)` + `@FixMethodOrder` +
    >   snapshot-and-assert + UUID-qualified marker, per Tracks 6/7
    >   precedent.
    
    > **Interactions:**
    > - **Depends on Track 1** (coverage-analyzer.py + coverage gate
    >   tooling) — already in place since Phase 1 of the project.
    > - **Reads from Track 18's hand-off:** B-tree `doClearTree` error
    >   paths, `RemoteIndexEngine` factory branch, established
    >   `BTreeEngineConstructorValidationTest` shape pins.
    > - **Reads from Tracks 19/20's hand-off:** page-level direct-memory
    >   pattern (Track 21 Step 1 extracts to `PageEntryFixture`),
    >   CyclicBarrier MT-race pattern (used for the
    >   `StaleTransactionMonitor` start-guard probe), test-record-ID
    >   discipline, Mockito void-stub trap, paginated subpackage
    >   boundary (`paginated/wal*` is Track 20-owned;
    >   `paginated/atomicoperations*` and `paginated/base*` are
    >   incidentally well-covered, do not duplicate test files).
    > - **Forwards to Track 22:**
    >   - **Dead-code lockstep groups** (forwarded to Track 22 absorption
    >     block): `{CellBTreeBucketSingleValueV1, CellBTreeSingleValueEntryPointV1}`
    >     (singlevalue/v1, 242 LOC, 0 main + 0 test refs); the v1 bucket
    >     classes plus `SBTreeValue`
    >     `{SBTreeBucketV1, SBTreeNullBucketV1, SBTreeValue}` (local/v1,
    >     bucket pair has 0 main / 23+4 test refs; `SBTreeValue` has
    >     8 main refs all intra-package and is transitively dead once
    >     buckets are deleted). Track 22 deletes v1 source + legacy
    >     `SBTree*V1Test` files + Track 21's new `*DeadCodeTest` shape
    >     pin in one coordinated commit per Track 17 / Track 18 precedent.
    >   - **`DecimalKeyNormalizer.java:43-101` dead helpers**
    >     (`scaleToDecimal128`, `clampAndRound`, `ensureExactRounding`,
    >     `unsigned`) — unreachable from any production caller. Forward
    >     deletion to Track 22 absorption block. If Phase B Step 1
    >     re-measure on `nkbtree.normalizers` shows `assert`-stripped
    >     branch% is already ≥70%, no test work needed; otherwise
    >     accept lower branch% target with WHEN-FIXED forward.
    >   - **WHEN-FIXED bug pins** (anything Track 21 testing surfaces
    >     except data-corruption / index-consistency / recovery-path
    >     defects, which are fixed inline).
    >   - **DRY / cleanup candidates** surfaced by Phase C track-level
    >     review (oversized test classes, fixture extraction beyond
    >     `PageEntryFixture`, magic numbers, etc.).
    > - **No Component Map or Decision Record changes expected.** D4
    >   reinforced again; track ordering remains.
    > - **No cross-track impact on Track 22 scope** beyond growing the
    >   already-existing absorption queue.
    
    ```mermaid
    flowchart TD
        subgraph LiveEngine["Live B-tree engine (v4 only)"]
            BTREE["sbtree/singlevalue/v3/BTree\n(generic, used by single + multi engines)"]
            SVV3["sbtree/singlevalue/v3 package\n84.3% / 70.5%, 403 uncov"]
        end
    
        subgraph WALReplay["WAL-replay-only D4 packages\n(direct bucket / Op tests)"]
            MVV2["sbtree/multivalue/v2\n81.6% / 65.9%, 338 uncov"]
            LOCV2["sbtree/local/v2\n87.6% / 69.9%, 111 uncov"]
        end
    
        subgraph SmallPkgs["Small / interface packages"]
            NKBN["index/nkbtree/normalizers\n71.5% / 20.0% (likely assert-trap)\n45 uncov"]
            SBTOP["sbtree top + sbtree/singlevalue\n0% / 0%, 7+1 uncov"]
            IDXENG["index/engine + index/versionmap\n0% line, branchless\n26+20 uncov"]
            PAGTOP["impl/local/paginated top-level\n79.5% / 54.2%, 46 uncov"]
        end
    
        subgraph ImplLoc["storage/impl/local cluster"]
            IMPLLOC["AbstractStorage ~3 100 instr-lines\n62.8% / 59.0%, 1 157 uncov\n(target ~75% / ~62%)"]
            HELPERS["Helpers: StaleTransactionMonitor (368 LOC),\nTsMinHolder (189), AtomicOperationIdGen (20),\nWALVacuum (15), CollectionBrowse* (37)"]
        end
    
        subgraph DeadCode["D4-accepted dead-code\n(Track 22 deletion, *DeadCodeTest pins)"]
            SVV1["sbtree/singlevalue/v1\n0% / 0%, 242 LOC\n(0 main + 0 test refs)"]
            LOCV1["sbtree/local/v1\n66.6% / 44.4%, 102 uncov\n(0 main refs, transitively dead)"]
        end
    
        subgraph Hints["Track 18 hand-off"]
            DCT["BTree.doClearTree error paths\n(post-assert-exclusion candidates)"]
            RIE["RemoteIndexEngine factory branch"]
        end
    
        LiveEngine -->|"shape pin"| DeadCode
        WALReplay -->|"direct bucket / Op"| DeadCode
        Hints -->|"transitive coverage"| LiveEngine
        Hints -->|"smoke pin"| ImplLoc
        HELPERS -.->|"closes gap to ~75%"| IMPLLOC
    ```
    
    ## Progress
- [x] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review

## Reviews completed
- [x] Technical: PASS at iteration 2 (6 findings — T1/T2/T3 should-fix accepted, T4/T5/T6 suggestions accepted). Iter-2 gate verified: `local/v1` row added with deletion-bound rationale; IT-shadow caveat reworded to cite the default `**/*IT.java` glob under `ci-integration-tests` profile (NOT failsafe `<includes>`); step count loosened to ~6–7; JaCoCo `100% branch` annotated as branchless-interface unmeasurable-sentinel; `AbstractStorage` LOC corrected to ~3 100 instr-lines; `coverage-gate.py` `assert`-strip pre-step probe added for engine `doClearTree` paths and `nkbtree.normalizers`. Zero new findings.
- [x] Risk: PASS at iteration 2 (7 findings — R1/R2 high-severity accepted, R3/R4 medium accepted, R5/R6/R7 low accepted). Iter-2 gate verified: **R2 (CRITICAL)** the non-existent class names `CellBTreeSingleValueV3`/`CellBTreeMultiValueV2`/`SBTreeV2` now appear ONLY inside explicit "do NOT use" callouts (PSI re-confirmed `BTree` class in `sbtree/singlevalue/v3` is the live engine; the v2 lifecycle classes do not exist); `multivalue/v2` and `local/v2` reframed as WAL-replay-only D4 packages with direct-bucket/`*Op` round-trip strategy; `StaleTransactionMonitor` CyclicBarrier MT probe scoped; per-test database-name UUID rule added to Constraints (OEngine.getStorage(name) collision rationale); build-cost ceiling loosened to ~6–7 cycles; `PageEntryFixture` extraction committed as Step 1 (resolves Track 20 TS7); `nkbtree.normalizers` target deferred to Phase B Step 1 with `coverage-gate.py` assert-strip re-measure. Zero new findings.
- [x] Adversarial: PASS at iteration 2 (7 findings — A1/A4/A6 should-fix accepted, A3 partially accepted, A5/A7 suggestions accepted, A2 rejected with documented Track 17/18-precedent rationale). Iter-2 gate verified: 6–7-step decomposition with quick-wins-first ordering; A2 rejection rationale (test-additive PR, atomic Track 22 deletion) documented in step file; `nkbtree.normalizers` target deferred + dead-helper deletion forwarded to Track 22; `PageEntryFixture` named as Step 1 in three anchors; constraint strengthened to permit inline production fixes for data-corruption / index-consistency / recovery-path bugs; `storage.impl.local` line target tightened from ~70% to ~75% (branch ~62%). Zero new findings.

## Steps

- [ ] Step 1: Extract `PageEntryFixture` shared test utility for direct-memory page-level tests
  > **Risk:** medium — test infrastructure (shared fixture; consumed by Steps 5/6 here and absorbed by Track 22 `cache.local` test cleanup TS7 backlog item).
  > Resolves Track 20's deferred TS7. Encapsulates the Track 19/20 page-level pattern: `ByteBufferPool.acquireDirect()` → `CachePointer` → `incrementReadersReferrer` → return an `AutoCloseable` page handle; `@After`-side `bufferPool.clear()` + `allocator.checkMemoryLeaks()`. Place under `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/cache/PageEntryFixture.java` (or `test-commons` if cross-module reuse is anticipated; decide at implementation time). Spotless-clean. No production source changes. Verify Track 20 tests `LockFreeReadCacheFileOpsTest`, `BoundedBufferRingTest`, `BoundedBufferDrainTest`, `CacheEntryImplTest` either adopt the fixture (preferred) or are explicitly noted as Track 22 follow-ups. End-of-step: `./mvnw -pl core test -Dtest=PageEntryFixtureSmokeTest` plus the impacted Track 20 classes pass; full `core` unit suite remains green.

- [ ] Step 2: Small-package quick-wins — `nkbtree.normalizers`, `sbtree` top-level, `index/engine`, `index/versionmap`
  > **Risk:** low — default (tests-only on existing code; no shared fixture beyond what Step 1 already provides).
  > Add unit tests to lift the four small/interface-level packages. (a) Re-measure `nkbtree.normalizers` with `coverage-gate.py` to determine post-`assert`-exclusion branch% — if real branch% ≥70%, smoke-pin completion only; else commit to a lower D4 target with the `DecimalKeyNormalizer.java:43-101` dead-helper deletion forwarded to Track 22. (b) `sbtree/TreeInternal.java` (7 uncov / 7 total): direct standalone tests for the small interface/util slice. (c) `storage.index.engine` and `storage.index.versionmap` (interface-only, branchless): smoke-pin shape coverage — instantiate / hash / equals / toString as applicable. End-of-step: per-package gates met or D4-accepted with rationale; `./mvnw -pl core clean package -P coverage` confirms aggregate drift is bounded.

- [ ] Step 3: `storage/impl/local` helpers + `paginated` top-level (`StaleTransactionMonitor` MT probe inclusive)
  > **Risk:** medium — concurrency test infrastructure (CyclicBarrier MT probe pattern for `StaleTransactionMonitor.start()` idempotency is reusable test infra; helpers tests follow standard DbTestBase + standalone patterns).
  > Cover `StaleTransactionMonitor` (368 LOC; volatile `ScheduledFuture<?>` + non-atomic start guard at lines 86, 120-126, 142, 363) — extend `StaleTransactionMonitorTest` with: (1) `start()` idempotency under `CyclicBarrier`-synchronised concurrent calls, asserting at-most-one schedule (CountDownLatch start gate, `AtomicReference<Throwable>` for thread errors, < 5 s timeout); (2) `stop()` after never-started; (3) `WarnState` cycle-counter shape pins. Cover `TsMinHolder` (189 LOC), `AtomicOperationIdGen` (20 LOC), `WALVacuum` (15 LOC), `CollectionBrowse{Entry,Page}` and `paginated` top-level seven non-WAL/non-atomicops classes (`LinkBag{Delete,Update}SerializationOperation`, `RecordOperationMetadata`, `RecordSerialization{Context,Operation}`, `StorageStartupMetadata`, `StorageTransaction`, `EnterpriseStorageOperationListener`) via standalone or `DbTestBase` as appropriate. WAL-record additions: NONE expected (use existing types); if any new test-record IDs are needed define `Track21WALTestRecordIds` in `[700, 799]`. End-of-step: helper-package gates met, `paginated` top-level ≥85%/≥70%; `core` unit suite green.

- [ ] Step 4: `AbstractStorage` public-API slices — toward ~75% line / ~62% branch on `storage/impl/local`
  > **Risk:** medium — test-isolation discipline (per-test database-name UUID requirement; pattern is shared across Step 4 tests and serves as canonical example for Track 22 storage-test absorption).
  > Targeted slices of `AbstractStorage` reachable from surefire scope: constructor argument validation, exception-translation (`makeStorageDirty` family), atomic-operation lifecycle helpers, snapshot/cleanup helpers (mirroring existing `EdgeSnapshotIndexCleanupTest`/`IndexesSnapshotCleanupTest` pattern), public-getter shape pins. Use `DbTestBase` with **per-test database name** containing `UUID.randomUUID().toString()` to avoid `OEngine.getStorage(name)` collisions across parallel surefire forks. Avoid reimplementing IT scenarios — sanity-check `StorageTestIT`, `LocalPaginatedStorageRestoreFromWALIT`, `StorageBackupMTIT`, `StorageEncryptionTestIT` first to identify the surefire-reachable surface. Inline-fix any data-corruption / index-consistency / recovery-path bug surfaced (with regression test); forward lower-severity bugs to Track 22 as WHEN-FIXED pins. End-of-step: `storage.impl.local` ≈75% line / ≈62% branch, `core` unit suite green.

- [ ] Step 5: B-tree singlevalue/v3 (live engine) — `BTree` lifecycle + bucket round-trip
  > **Risk:** low — default (tests-only on the live engine; consumes `PageEntryFixture` from Step 1; no new shared infrastructure).
  > Cover `com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.BTree` (the only live B-tree engine, used by both `BTreeSingleValueIndexEngine` and `BTreeMultiValueIndexEngine` via two wrapped instances) plus `CellBTreeSingleValueBucketV3` and any other classes in `singlevalue/v3` and `singlevalue` (top-level). Use `DbTestBase` for `BTree<>` lifecycle (open → put → get → remove → close) and `PageEntryFixture` for bucket-level round-trip mutations. Fold in Track 18's hand-off: `BTreeSingleValueIndexEngine.doClearTree` and `BTreeMultiValueIndexEngine.doClearTree` "removed 0 entries" error paths (post-`assert`-exclusion gate-pass check first), and a smoke pin on `DefaultIndexFactory.createIndexEngine("remote", ...)` returning `RemoteIndexEngine`. End-of-step: `singlevalue/v3` ≥85%/≥70%, `core` unit suite green.

- [ ] Step 6: B-tree multivalue/v2 + local/v2 (WAL-replay-only) + V1 dead-code shape pins
  > **Risk:** low — default (tests-only on dead-code/replay-only packages; direct bucket/`*Op` construction; no production change; *DeadCodeTest pins per Track 17/18 precedent).
  > Cover `multivalue/v2` (`CellBTreeMultiValueV2Bucket`, `CellBTreeMultiValueEntryPointV2`, plus `*Op` WAL records registered in `PageOperationRegistry`) and `local/v2` (`SBTreeBucketV2`, `SBTreeNullBucketV2`, plus `*Op` records) via direct construction + round-trip tests using `PageEntryFixture`. Do NOT route through `BTree<>` — the live engine rejects version 2 (`BTreeMultiValueIndexEngine.java:73`). For each `*Op`: build the WAL record, serialize → deserialize → replay against a fresh page → assert post-replay state. V1 dead-code shape pins: add `CellBTreeBucketSingleValueV1DeadCodeTest`, `CellBTreeSingleValueEntryPointV1DeadCodeTest`, `SBTreeBucketV1DeadCodeTest`, `SBTreeNullBucketV1DeadCodeTest`, `SBTreeValueDeadCodeTest` per Track 17/18 precedent — minimal pins asserting class exists + at-most-trivial-ops behaviour, with `// WHEN-FIXED: Track 22` markers indicating Track 22 deletes the source + legacy `*V1Test.java` files + these new pins atomically. End-of-step: `multivalue/v2` ≥85%/≥70%, `local/v2` ≥85%/≥70%, `singlevalue/v1` and `local/v1` accepted at 0% / dead-code under D4, `core` unit suite green.

- [ ] Step 7: Verification + top-up + `track-21-baseline.md` + track episode prep
  > **Risk:** low — default (verification-only; coverage measurement, baseline document, top-up tests where any per-package gate misses).
  > Run `./mvnw -pl core -am clean package -P coverage` and `coverage-analyzer.py` to produce the post-Track-21 baseline. For each in-scope package: confirm gate met or D4-accepted with rationale (if a gate falls short of the committed target, add focused top-up tests within this step). Write `docs/adr/unit-test-coverage/_workflow/track-21-baseline.md` mirroring the Track 19/20 baseline structure (aggregate, per-package table, gate-result block, gate command). Record any deferred items in `implementation-backlog.md` Track 22 absorption block (dead-helper deletions, WHEN-FIXED pins surfaced during Phase B, DRY/cleanup follow-ups). End-of-step: `coverage-gate.py` reports gate PASS or D4-accepted on changed lines; baseline file committed; `core` unit suite green.

