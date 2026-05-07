# Track 18: Index

## Description

Write tests for the index management layer — index engines, index
iterators, and index operations.

> **Reconstruction note:** the original backlog body for Track 18 was
> in a recovery gap (see `implementation-plan.md` § Operational Notes
> → "Reconstruct-on-demand tracks"). The What/How/Constraints/
> Interactions block below is regenerated from (a) the plan's
> `**Scope:**` indicator, (b) the design's Component Map cluster
> mapping for `core/index*`, and (c) the post-Track-17 coverage
> baseline (`./mvnw -pl core -am clean package -P coverage` produced
> `.coverage/reports/youtrackdb-core/jacoco.xml`).

> **What:**
> - Raise per-package coverage to 85% line / 70% branch on each
>   in-scope `core/index*` package by adding unit tests targeted at
>   the uncovered lines and branches identified in the Track-17
>   coverage report. Aggregate gap on entry: ~1 325 uncovered lines,
>   ~6 330 total lines across six packages.
> - In-scope packages and their post-Track-17 baseline (line% /
>   branch% — `uncov / total` lines):
>   - `internal.core.index` — **67.7% / 59.0%** (1 029 / 3 190) — the
>     dominant gap; ~39 production files covering Index lifecycle,
>     IndexAbstract, IndexUnique / IndexNotUnique / IndexOneValue /
>     IndexMultiValues, IndexManagerAbstract / IndexManagerEmbedded,
>     IndexFactory / DefaultIndexFactory / Indexes / IndexesSnapshot,
>     ClassIndexManager, IndexMetadata, IndexAbstractCursor,
>     IndexCursor, IndexKeyCursor, IndexUpdateAction,
>     IndexKeyUpdater, IndexRebuildOutputListener,
>     IndexStreamSecurityDecorator, RecreateIndexesTask, exceptions,
>     plus `*IndexDefinition` / `CompositeKey` / `CompositeCollate` /
>     `SimpleKeyIndexDefinition` / `PropertyMapIndexDefinition` /
>     `PropertyLinkBag*IndexDefinition` / `PropertyListIndexDefinition`.
>   - `internal.core.index.iterator` — **43.3% / 44.2%** (85 / 150) —
>     5 production files (worst percentage in the bucket; small
>     absolute gap).
>   - `internal.core.index.comparator` — **50.0% / 100.0%** (5 / 10) —
>     4 production files; trivial top-up.
>   - `internal.core.index.multivalue` — **66.7% / 100.0%** (1 / 3) —
>     single production file; trivial top-up.
>   - `internal.core.index.engine` — **90.8% / 84.6%** (134 / 1 451) —
>     already past the line target; needs branch top-up only and a
>     handful of dead-or-reachable shape pins.
>   - `internal.core.index.engine.v1` — **86.5% / 82.5%** (71 / 526) —
>     already past the line target; branch top-up only.
> - 23 existing test files in `core/index/` root (21 concrete `*Test`
>   classes + 2 abstract bases —
>   `SchemaPropertyLinkBagAbstractIndexDefinition.java`,
>   `SchemaPropertyLinkBagSecondaryAbstractIndexDefinition.java` —
>   reused by the link-bag tests). Includes the
>   `IndexAbstractHistogramDelegationTest` introduced by Track 16 for
>   histogram delegation. Extend in place where scope fits per
>   Constraint 6; only create new test classes when no existing one
>   covers the area.
>
> **How:**
> - At the **start** of Phase B, regenerate per-package coverage
>   numbers for the entry baseline. The Description above uses the
>   post-Track-17 baseline — re-running locally before Phase B
>   absorbs any drift introduced by upstream `develop` rebases.
> - For each package, work from JaCoCo's class-level uncovered-lines
>   column and prefer the largest uncovered-line concentrations
>   first. Use `coverage-analyzer.py` to print per-package totals;
>   use the JaCoCo HTML report (or
>   `.coverage/reports/youtrackdb-core/jacoco.xml`) for class-level
>   detail.
> - Decompose into ~5 steps (one commit per step, fully tested):
>   1. **Index definitions cluster** — composite key + property
>      definitions + simple-key + multivalue definition. Most of
>      this surface is **session-coupled**, not standalone:
>      `PropertyIndexDefinition.getDocumentValueToIndex /
>      createValue / toMap / serializeToMap` (and the same methods
>      on `CompositeIndexDefinition` and `SimpleKeyIndexDefinition`)
>      take a `FrontendTransaction` or `DatabaseSessionEmbedded`,
>      and every existing test in this cluster
>      (`SchemaPropertyIndexDefinitionTest`, `CompositeIndexDefinitionTest`,
>      `SimpleKeyIndexDefinitionTest`, `CompositeKeyTest`) extends
>      `DbTestBase`. Tests therefore extend `DbTestBase` per the
>      established precedent. Only `CompositeKey` (compare / equals /
>      hashCode), `CompositeCollate`, and the `comparator/` subpackage
>      are genuinely standalone.
>      *Extension candidates (Constraint 6):*
>      `SchemaPropertyIndexDefinitionTest`, `CompositeIndexDefinitionTest`,
>      `SimpleKeyIndexDefinitionTest`, `CompositeKeyTest`,
>      `SchemaPropertyMapIndexDefinitionTest`,
>      `SchemaPropertyListIndexDefinitionTest`,
>      `SchemaPropertyEmbeddedLinkBagIndexDefinitionTest`,
>      `SchemaPropertyEmbeddedLinkBagSecondaryIndexDefinitionTest`.
>      *New classes (only when no existing extension target):* tests
>      for `CompositeCollate`, `comparator/` package, and the
>      currently-untested 60 lines of `SimpleKeyIndexDefinition`.
>   2. **Live spliterators (TX-aware iteration)** — the
>      `iterator/` subpackage's four `PureTx*BetweenIndex*Spliterator`
>      classes are the live coverage target. PSI
>      `ReferencesSearch` confirms the gap is concentrated in the
>      two **backward** variants (~70 of the 85 uncovered iterator
>      lines): `PureTxBetweenIndexBackwardSpliterator` (30/30 lines
>      uncovered, but **live** — referenced 5× from
>      `IndexOneValue.streamEntriesMajor / Minor / Between` with
>      `ascSortOrder=false`) and
>      `PureTxMultiValueBetweenIndexBackwardSplititerator` (40/40
>      uncovered, **live** — same shape from `IndexMultiValues`).
>      Tests extend `DbTestBase`, build a real transactional index,
>      and walk it descending.
>      Plus a small `*DeadCodeTest` shape pin (Track-17 precedent)
>      for the dead cluster — `IndexCursor`, `IndexAbstractCursor`,
>      `IndexCursorStream` (one lockstep group) and `IndexKeyCursor`
>      (separate). PSI `ReferencesSearch` (all-scope) confirms 0
>      production references for all four; they are forwarded to
>      Track 22's deferred-cleanup queue with `WHEN-FIXED: Track 22`
>      markers. Do NOT manufacture synthetic call sites to hit live
>      coverage on these classes.
>      *Extension candidates:* none (no existing test covers the
>      iterator subpackage). New class:
>      `PureTxBetweenIndexSpliteratorTest` (forward + backward,
>      single + multi value).
>   3. **Manager / factory / lifecycle** — DbTestBase mandatory.
>      Centerpiece: `RecreateIndexesTask` (101 / 101 uncovered lines —
>      0 % — the single largest class-level gap in `core/index`).
>      Drive it via direct construction
>      (`new RecreateIndexesTask(indexManager, sharedContext).run()`)
>      with both a healthy index store (happy path) and a
>      deliberately-corrupted store (catch-branch coverage). Other
>      targets in the step: `IndexManagerAbstract`,
>      `IndexManagerEmbedded`, `IndexFactory`, `DefaultIndexFactory`,
>      `Indexes`, `IndexesSnapshot`, `ClassIndexManager`,
>      `IndexRebuildOutputListener`. If implementation reveals that
>      `RecreateIndexesTask` alone needs >25 lines of test fixture,
>      Phase B may split this step into 3a (Manager / factory) +
>      3b (RecreateIndexesTask + IndexRebuildOutputListener) — that
>      keeps the 5-step budget at 6 steps total, still inside the
>      ~5–7 ceiling.
>      *Extension candidates:* `IndexesSnapshotClearTest`,
>      `IndexesSnapshotVisibilityFilterTest`. New classes for
>      `IndexManagerEmbedded`, `RecreateIndexesTask`, `Indexes`,
>      `DefaultIndexFactory` (no existing tests cover them
>      directly).
>   4. **Index implementations** — `IndexUnique`, `IndexNotUnique`,
>      `IndexOneValue`, `IndexMultiValues`, `IndexAbstract`,
>      `IndexStreamSecurityDecorator`, `IndexKeyUpdater`,
>      `IndexUpdateAction`, `IndexMetadata`, exception types.
>      DbTestBase mandatory; cover hot edge cases (null keys, RID
>      collisions, transactional vs non-transactional behaviour,
>      collation-aware key comparison).
>      *Excluded (Track-16 territory in
>      `IndexAbstractHistogramDelegationTest`):*
>      `IndexAbstract.getStatistics`, `getHistogram`,
>      `analyzeHistogram`, `setBulkLoading`,
>      `buildHistogramAfterFill`, plus their
>      retry-on-`InvalidIndexEngineIdException` paths. Step 4 covers
>      the remaining ~108 uncovered lines (load/`init`/flush, key
>      normalization, `getRebuildVersion`, non-histogram exception
>      classification, `recreateIndexBoundary`, `clear` / `drop`,
>      configuration deserialization).
>      *Extension candidates:* `UniqueIndexTest`, `BigKeyIndexTest`,
>      `TxUniqueIndexWithCollationTest`,
>      `TxNonUniqueIndexWithCollationTest`. Do NOT add tests to
>      `IndexAbstractHistogramDelegationTest` — that file is owned
>      by Track 16's contract.
>   5. **Engine top-up + comparator/multivalue + verification** —
>      branch fan-out for `engine/` and `engine/v1/` (mostly small
>      conditional branches at the engine SPI surface) and the two
>      ~100 % branch packages. Note: exercising the BTree engines
>      (`BTreeIndexEngine`, `BTreeSingleValueIndexEngine`,
>      `BTreeMultiValueIndexEngine` in `engine/v1/`) transitively
>      touches `core/storage/index/sbtree/...` lines via the
>      delegation chain — that's expected and helpful for Track 21;
>      do NOT stub the storage layer. Validate the aggregate
>      `core/index*` gate, regenerate the per-package report, and
>      record a `track-18-baseline.md` on disk.
> - **Existing-class-preferred discipline** (Constraint 6, reinforced
>   by every prior track): scan the existing 23 test classes in
>   `core/index/` first; if a class covers the same production
>   surface, extend it. Only create new classes when no existing one
>   covers the area or when the test fixture would diverge sharply.
> - **No production-code modifications.** Per the plan's Non-Goals
>   section, production code is touched only for testability
>   refactors of internal classes or to fix bugs surfaced by tests.
>   Shape-pin observable behaviour with WHEN-FIXED markers and
>   forward refactor / dead-code candidates to Track 22's queue.
> - **PSI for symbol audits.** When the plan claims a method has no
>   production callers (typical Track 22 dead-code candidate
>   feeders) or that an SPI has a fixed implementer set, route
>   through mcp-steroid PSI find-usages — not grep — per
>   `conventions.md` §1.4 *Tooling discipline*.
>
> **Constraints:**
> - **In-scope packages**: `core/index`, `core/index/comparator`,
>   `core/index/engine`, `core/index/engine/v1`,
>   `core/index/iterator`, `core/index/multivalue`.
> - **Out-of-scope (Track 21 territory)**: every `core/storage/index/*`
>   package — `sbtree.singlevalue.{v1,v3}`, `sbtree.multivalue.v2`,
>   `sbtree.local.{v1,v2}`, `sbtree`, `engine`, `versionmap`,
>   `nkbtree.normalizers`. Don't add tests for these as part of
>   Track 18 even when the test would touch them transitively.
> - **Out-of-scope (already covered)**:
>   `core/serialization/serializer/binary/impl/index` (98.9 % —
>   serialization tracks).
> - **Coverage target**: 85 % line / 70 % branch per in-scope
>   package (none of these fall under D4's storage exception). The
>   gate in `coverage-gate.py` checks changed lines only; aggregate
>   per-package gates are an internal target enforced at Phase C
>   completion, not by CI.
> - **Test framework**: JUnit 4 with `surefire-junit47` runner per
>   the plan's Constraint 1.
> - **DbTestBase lifecycle** for index manager / factory / lifecycle
>   tests; standalone tests where the production class is pure
>   (definitions, comparators, simple key arithmetic).
> - **No parallel test processes** in this worktree (Constraint 3).
> - **Spotless apply** before every commit (Constraint 4) — run
>   `./mvnw -pl core spotless:apply` per step.
> - **Coverage verification at Track close** (Constraint 5): run
>   `./mvnw -pl core -am clean package -P coverage` and
>   `coverage-analyzer.py` against the produced `jacoco.xml`.
> - **Disk-mode parity** (Constraint 8): every test must pass under
>   both `youtrackdb.test.env=memory` (default) and
>   `youtrackdb.test.env=ci` (disk-storage CI mode).
> - **Test descriptions** (Constraint 10): every test must explain
>   the scenario and expected outcome via either descriptive method
>   names or comments.
> - **No production-code modifications** unless a test reveals a bug
>   (Plan §Non-Goals). Bugs found get a fix + regression test;
>   refactor / dead-code candidates feed Track 22's queue.
> - **Rebase before Phase A** (Constraint 11). Pre-rebase HEAD:
>   `e991ac26f0fcaa344323896448dd805ba579027b` (`Mark Track 17
>   complete`). Post-rebase HEAD: `f26cbe0c483f11bb9a9ebb5c8605564795da30fa`
>   was already the merge base at session start — the local branch
>   was rebased onto the latest `origin/develop` during the prior
>   Track 17 session and `./mvnw -pl core -am clean package -P
>   coverage` ran clean on `45b5caea0b Record post-Track-17
>   coverage baseline`. No further rebase performed in this session.
>
> **Interactions:**
> - **Depends on Track 1** (coverage measurement infrastructure)
>   for the per-package report; Track 1 is `[x]`.
> - **Builds on Track 16** (Metadata Schema & Functions): Track 16
>   added `IndexAbstractHistogramDelegationTest` to `core/index/`
>   for the histogram delegation path. Track 18 must not duplicate
>   that scope; existing-class-preferred discipline applies.
> - **Independent of Tracks 19–21** (storage internals — separate
>   subsystem). Findings about `IndexEngine` SPI implementations
>   may inform Track 21's tests for the storage-side B-tree
>   implementations; surface those as cross-track hints in step
>   episodes.
> - **Track 22 deferred-cleanup feeders**: any
>   IndexAbstract / IndexInternal / IndexCursor refactor or dead-code
>   candidate goes to Track 22's queue (production-code changes are
>   out of scope for Track 18). **One feeder is already discovered**:
>   the dead `IndexCursor` / `IndexAbstractCursor` / `IndexCursorStream`
>   cluster (one lockstep group) plus `IndexKeyCursor` (separate
>   lockstep) — committed to Track 22's absorption block in this
>   Phase A commit alongside the description rewrite.
> - **Cross-cutting**: Track 8 (SQL Executor) and Track 5 (SQL
>   Operators) interact with the Index API at the query side; their
>   episodes did not flag any blocked-on-index-tests issue.

## Progress
- [x] Review + decomposition
- [ ] Step implementation (1/5 complete)
- [ ] Track-level code review

## Base commit
`04a1f5072a0172b111da7454b3421c78a934ecac`

## Reviews completed
- [x] Technical (iter-1: 1 blocker / 2 should-fix / 4 suggestions; all
      7 findings accepted and applied inline to Description above and
      to Track 22's absorption block in `implementation-backlog.md`).
      No iter-2 gate verification sub-agent: every finding maps to a
      mechanical edit (reword Step 1's "standalone" framing, move 4
      dead-code classes to Track 22 + rewrite Step 2 around live
      backward spliterators + a dead-code shape pin, surface
      `RecreateIndexesTask` as Step 3 centerpiece, exclude Track-16
      histogram delegation from Step 4, add per-step extension
      candidates, footnote abstract-base test files, note transitive
      storage coverage in Step 5). PSI `ReferencesSearch` (all-scope)
      backed every reference-accuracy claim; mcp-steroid was reachable
      throughout.

## Steps

- [x] Step 1: Cover `core/index` definitions cluster (composite key,
      property definitions, simple-key, multivalue, comparator,
      collate)
  - [x] Context: safe
  > **Risk:** low — default (test-additive only; no production-code
  > changes; no shared test-fixture changes — every new test extends
  > the existing `DbTestBase` precedent or stands alone).
  >
  > **What was done:** Created 4 new test classes (`CompositeCollateTest`,
  > `IndexComparatorTest` under `comparator/`, `MultiValuesTransformerTest`
  > under `multivalue/`, `IndexDefinitionFactoryTest`) and extended 4
  > existing ones (`CompositeKeyTest`, `SimpleKeyIndexDefinitionTest`,
  > `SchemaPropertyIndexDefinitionTest`, `CompositeIndexDefinitionTest`)
  > with 192 passing tests. Coverage: composite-key compare / equals /
  > hashCode / size / `asCompositeKey`; `CompositeCollate` transform and
  > equality; `AscComparator`, `DescComparator`, `AlwaysGreaterKey`,
  > `AlwaysLessKey`; `MultiValuesTransformer` identity cast;
  > `IndexDefinitionFactory` field-name extraction plus single / multi /
  > map / list / linkbag definition creation; `SimpleKeyIndexDefinition`
  > `toCreateIndexDDL` / `withCollates` / `isAutomatic` and the
  > `AbstractIndexDefinition.setCollate(null)` guard +
  > `setNullValuesIgnored` toggle; `PropertyIndexDefinition`
  > `toCreateIndexDDL` / `getFieldsToIndex`-with-collate;
  > `CompositeIndexDefinition` `toCreateIndexDDL` / `getCollate`. Commit
  > `c6f9c77dc6`.
  >
  > **What was discovered:**
  > - Two API mismatches confirmed via compile errors: (1)
  >   `SchemaClass.createProperty` has no session-first overload — schema
  >   operations must execute outside an active transaction (a session-active
  >   transaction yields `SchemaException`). Future Steps 3 and 4 must call
  >   `session.begin()` only after schema setup. (2)
  >   `IndexDefinitionFactory.createSingleFieldIndexDefinition` takes 7
  >   args including a `String indexKind` between `linkedType` and
  >   `INDEX_BY`.
  > - `CompositeKey.extractFieldName(...)` empty-guard (`length == 0`)
  >   never fires for whitespace-only input because `Pattern.split` yields
  >   `["", ""]` not `[]`; the test pins the four-token reassembly path
  >   instead.
  > - `PropertyMapIndexDefinition` requires a non-null `INDEX_BY`
  >   argument — passing `null` throws NPE. Step 4 must supply
  >   `INDEX_BY.KEY` or `INDEX_BY.VALUE` when exercising the map-index
  >   path.
  >
  > **Key files:**
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/CompositeCollateTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/IndexDefinitionFactoryTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/comparator/IndexComparatorTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/multivalue/MultiValuesTransformerTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/CompositeIndexDefinitionTest.java` (modified)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/CompositeKeyTest.java` (modified)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/SchemaPropertyIndexDefinitionTest.java` (modified)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/SimpleKeyIndexDefinitionTest.java` (modified)

- [ ] Step 2: Cover live TX-aware spliterators (`iterator/`) +
      dead-code shape pin for the `IndexCursor*` cluster
  > **Risk:** low — default (test-additive only; the `*DeadCodeTest`
  > shape pin follows the Track-17 precedent of pure reflection-based
  > pins, no production-code changes).
  >
  > **Live targets** (~70 of the 85 uncovered iterator lines):
  > - `PureTxBetweenIndexBackwardSpliterator` (30 lines, 0 % — live,
  >   exercised by `IndexOneValue.streamEntriesMajor / Minor /
  >   Between` with `ascSortOrder=false`).
  > - `PureTxMultiValueBetweenIndexBackwardSplititerator` (40 lines,
  >   0 % — live, exercised by `IndexMultiValues.streamEntriesMajor /
  >   Minor / Between` with `ascSortOrder=false`).
  > - Any residual branch gap on the two forward variants
  >   (`PureTxBetweenIndexForwardSpliterator` 28/30, ~93 %, plus
  >   `PureTxMultiValueBetweenIndexForwardSpliterator` 37/40, ~92 %).
  >
  > **Dead-code shape pin (per Track-17 precedent):**
  > One new `IndexCursorClusterDeadCodeTest` (or two — one per
  > lockstep group) reflectively pins the constructor signatures /
  > method shapes of `IndexCursor`, `IndexAbstractCursor`,
  > `IndexCursorStream`, `IndexKeyCursor` with `WHEN-FIXED: Track 22`
  > markers. The four classes are forwarded to Track 22's absorption
  > block in this Phase A commit (`implementation-backlog.md` Track
  > 22 section). Do NOT add live tests against any of the four —
  > PSI `ReferencesSearch` (all-scope) confirms 0 production
  > references, and synthetic call sites are forbidden by the
  > project's no-fake-coverage discipline.
  >
  > **New class:** `PureTxBetweenIndexSpliteratorTest` (DbTestBase) —
  > builds a small transactional index, walks both single- and
  > multi-value variants in both directions, asserts emitted
  > key / RID order matches the deterministic descending walk through
  > `FrontendTransactionIndexChanges`. No existing test covers the
  > iterator subpackage.

- [ ] Step 3: Cover index manager / factory / lifecycle, with
      `RecreateIndexesTask` as the centerpiece
  > **Risk:** low — default (test-additive only; no shared test-fixture
  > changes — the recovery setup for `RecreateIndexesTask` is local
  > to its dedicated test class). If implementation reveals that
  > driving `RecreateIndexesTask` requires >25 lines of fixture and
  > the resulting test class is reused by sibling lifecycle tests,
  > Phase B may upgrade to `medium` per
  > `risk-tagging.md` § Override rules and split this step into 3a
  > (manager / factory) + 3b (RecreateIndexesTask + listener).
  >
  > **Centerpiece — `RecreateIndexesTask`** (101 / 101 uncovered, 0 %
  > — the single largest class-level gap in `core/index`):
  > - Happy path: construct directly with a `SharedContext` bound to
  >   a memory DB containing one or two indexes, invoke `run()`,
  >   assert each index is rebuilt (use `IndexRebuildOutputListener`
  >   to capture progress events).
  > - Catch branch: deliberately corrupt one index store (drop the
  >   underlying engine via reflection or by stubbing the engine's
  >   `loadIndex` to throw `InvalidIndexEngineIdException`), invoke
  >   `run()`, assert the task continues with the remaining indexes
  >   and the corrupted one is logged.
  > - Concurrency-safety check: not in scope for Track 18 (Track 18
  >   is test-additive; if the existing implementation has a TOCTOU
  >   between `loadAll` and `run()`, that's a Track 22 finding).
  >
  > **Other targets in this step:** `IndexManagerAbstract` (~30
  > uncovered lines: rebuild paths, `recreateIndexes` dispatch,
  > listener registration), `IndexManagerEmbedded` (~70 uncovered
  > lines: `addClusterToIndex`, `removeClusterFromIndex`,
  > `getDirtyIndexes`, `flushDirtyIndexes`,
  > `getRebuildVersion` propagation), `IndexFactory` SPI surface,
  > `DefaultIndexFactory` (algorithm / valueContainerAlgorithm
  > selection), `Indexes` (static dispatcher), `IndexesSnapshot`
  > (the residual non-snapshot-clear paths), `ClassIndexManager`
  > (~90 uncovered lines: per-class index lifecycle hooks),
  > `IndexRebuildOutputListener` (status-event coverage).
  >
  > **Extension candidates first (Constraint 6):**
  > `IndexesSnapshotClearTest`, `IndexesSnapshotVisibilityFilterTest`.
  > **New classes:** `IndexManagerEmbeddedTest`,
  > `RecreateIndexesTaskTest`, `IndexesTest`, `DefaultIndexFactoryTest`,
  > `ClassIndexManagerTest`, `IndexRebuildOutputListenerTest` — none
  > of these production classes have a dedicated test today.

- [ ] Step 4: Cover index implementations (`IndexUnique`,
      `IndexNotUnique`, `IndexOneValue`, `IndexMultiValues`,
      `IndexAbstract` non-histogram surface,
      `IndexStreamSecurityDecorator`, `IndexKeyUpdater`,
      `IndexUpdateAction`, `IndexMetadata`, exceptions)
  > **Risk:** low — default (test-additive only; no production-code
  > changes; no shared test-fixture changes).
  >
  > **Targets (post-Track-17 uncovered slices):**
  > - `IndexAbstract` — ~108 uncovered lines after Track-16's
  >   histogram delegation territory is excluded (load / `init` /
  >   flush, key normalization, `recreateIndexBoundary`, clear /
  >   drop, configuration deserialization, non-histogram
  >   `InvalidIndexEngineIdException` retry classification).
  > - `IndexUnique` (~uniqueness-violation, RID-ID conflict,
  >   transactional commit / rollback paths).
  > - `IndexNotUnique` (~80 uncovered lines: collated key
  >   compare-and-update branches, RID-set merge in transactional
  >   stash).
  > - `IndexOneValue` (~101 uncovered lines: descending iteration
  >   helper paths, `getInternal` no-result branch, transactional
  >   accumulator merge).
  > - `IndexMultiValues` (~102 uncovered lines: similar shape to
  >   `IndexOneValue` but with multi-value containers).
  > - `IndexStreamSecurityDecorator` (filter-bypass / `peek` paths
  >   under restricted user role).
  > - `IndexKeyUpdater`, `IndexUpdateAction`, `IndexMetadata`,
  >   exception types (mostly small fan-out).
  >
  > **Excluded (Track-16 territory):** `IndexAbstract.getStatistics`,
  > `getHistogram`, `analyzeHistogram`, `setBulkLoading`,
  > `buildHistogramAfterFill`, plus their retry-on-
  > `InvalidIndexEngineIdException` paths. Do NOT add tests to
  > `IndexAbstractHistogramDelegationTest` — that file is owned by
  > Track 16's contract.
  >
  > **Extension candidates first (Constraint 6):** `UniqueIndexTest`,
  > `BigKeyIndexTest`, `TxUniqueIndexWithCollationTest`,
  > `TxNonUniqueIndexWithCollationTest`. **New classes:**
  > `IndexNotUniqueTest`, `IndexOneValueTxTest`,
  > `IndexMultiValuesTxTest`, `IndexStreamSecurityDecoratorTest`,
  > `IndexAbstractCorePathsTest` (for the non-histogram remainder).

- [ ] Step 5: Engine top-up (`engine/`, `engine/v1/`) + comparator /
      multivalue gap closure + Track-18 verification
  > **Risk:** low — default (test-additive only; final-step
  > verification reads the JaCoCo XML and writes a baseline file —
  > no production-code or shared-fixture changes).
  >
  > **Targets:**
  > - `core/index/engine` (134 uncov / 1 451 — already 90.8 % line,
  >   84.6 % branch): branch fan-out at the `BaseIndexEngine` SPI
  >   surface (`getValueContainerAlgorithm`, `acquireAtomicExclusiveLock`
  >   error paths, `delete` / `clear` / `flush` non-happy branches),
  >   plus any uncovered methods in the engine factories.
  > - `core/index/engine/v1` (71 uncov / 526 — 86.5 % line, 82.5 %
  >   branch): branch top-up on the V1 `BTreeIndexEngine`
  >   delegation surface — exercising the public methods with a
  >   real DB will transitively exercise `core/storage/index/sbtree/...`
  >   delegate calls, which is helpful for Track 21 and **expected**;
  >   do NOT stub the storage layer.
  > - `core/index/comparator` (5 uncov / 10 — 50 % line, 100 %
  >   branch): trivial top-up on the four comparator classes.
  > - `core/index/multivalue` (1 uncov / 3 — 66.7 % line, 100 %
  >   branch): single-line top-up.
  >
  > **Verification:** at end of step, run
  > `./mvnw -pl core -am clean package -P coverage`, then
  > `python3 .github/scripts/coverage-analyzer.py
  > --coverage-dir .coverage/reports/youtrackdb-core` to assert each
  > Track-18 in-scope package meets 85 % line / 70 % branch (or
  > document any miss + recovery plan inline). Write the resulting
  > per-package report to
  > `docs/adr/unit-test-coverage/_workflow/track-18-baseline.md`.
  >
  > **Extension candidates first (Constraint 6):** existing engine
  > tests under `core/src/test/.../core/index/engine/{,v1}` (35 + 4
  > files — see `engine/` test subdir). **New classes only when no
  > existing test class covers the targeted method.**
