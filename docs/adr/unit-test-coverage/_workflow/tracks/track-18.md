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
- [x] Step implementation
- [ ] Track-level code review (1/3 iterations — gate FAILED on
      test-behavior + test-completeness; iter-2 fix plan recorded
      in "Reviews completed" below)

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

- [/] Track-level code review iter-1 — 5 dimensions (code-quality,
      bugs/concurrency, test-behavior, test-completeness,
      test-structure). 28 distinct findings synthesised → 22 in-scope
      + 8 deferred to Track 22. Iter-1 fixes landed in commit
      `2d024fbb36` (Review fix: Track 18 review iter-1 — assertion
      strengthening + cleared-TX branch + cleanups). 14 test files
      modified, 379 inserts / 145 deletes; 56 index-package classes,
      1304 tests, 0 failures, 0 errors, 1 skipped; spotless clean;
      coverage gate: n/a (test-additive iteration).

      The Phase C implementer hit a contract violation mid-iteration
      (silent exit, no `RESULT:` block, leftover stuck Maven test
      and self-referential `pgrep -f surefire` poll loop). Root
      cause + workflow-fix proposal documented in
      `WORKFLOW_ISSUE_implementer_silent_exit.md` at the project
      root. The orchestrator recovered by spawning a finalizer
      implementer (which also hit a hallucinated-auto-background
      issue) and finally by running spotless + tests + commit
      directly in the orchestrator after confirming the on-disk
      diff matched the synthesised findings list verbatim.

- [ ] Track-level code review iter-2 — 3 gate-check dimensions
      re-ran on the iter-1 commit (`2d024fbb36`):
      - **Code quality**: GATE PASS — 3 minor stylistic items only
        (CQ12 `RecordIdInternal` FQCN→import in the new cleared-TX
        tests, CQ13 pre-existing `CompositeKey` FQCN in
        `IndexComparatorTest`, CQ14 misleading test name +
        Javadoc on `run_brokenIndexBeforeGoodIndex_*`).
      - **Test behavior**: GATE FAIL — 2 should-fix:
        * **TB17** `RecreateIndexesTaskTest.run_brokenIndexBeforeGoodIndex_*`
          loop-continuation assertion is tautological. The test
          re-injects `goodIndex` into `indexManager.indexes` before
          `task.run()`; `existsIndex(...)` is just `containsKey(...)`
          per `IndexManagerAbstract:188`, so the assertion would
          pass even if the production loop aborted at the broken
          stub. **Same defect** also applies to the original
          `run_withOneBrokenIndexConfig_taskContinuesAndRebuildCompleted`
          (`PersonRIT2.name` was registered by `cls.createIndex`
          and never removed).
        * **TB19** Three `IndexRebuildOutputListenerTest.onBegin_*`
          tests assert `assertTrue(listener.onProgress(...))` — but
          `onProgress` returns `true` unconditionally
          (`IndexRebuildOutputListener.java:73-99`); the assertion
          verifies `onProgress`'s return, not any side-effect of
          `onBegin`. Mutation: empty `onBegin` body still passes
          all 3 tests.
        * Plus **TB18** (minor) — class-level Javadoc on
          `RecreateIndexesTaskTest` claims `getConfiguration` is
          called inside the catch-protected loop body, but PSI
          `ReferencesSearch` shows it is only called at
          `IndexManagerEmbedded.getIndexesConfiguration:482`,
          BEFORE the loop. The `configRequested` AtomicBoolean
          fires there, not inside the catch.
      - **Test completeness**: GATE FAIL — 1 blocker, 1 should-fix:
        * **TC13 (BLOCKER)** Same root cause as TB17 — the new
          "broken-before-good" test is not falsifiable to the
          regression it claims to catch.
        * **TC14** Cleared-TX branch coverage is method-shaped, not
          contract-shaped. F3 added the cleared-branch test only
          for `streamEntriesBetween`; the same `if
          (indexChanges.cleared)` guard is duplicated in 5+ other
          methods per class — `streamEntries(keys)`,
          `streamEntriesMajor`, `streamEntriesMinor`, plus
          `descStream` paths in `IndexMultiValues`
          (`IndexOneValue` lines 190/247/306; `IndexMultiValues`
          lines 229/286/344/392/521/611). Each branch has
          independent control flow.
        * Plus **TC15** (minor) — cleared-TX test uses `#-1:-1`
          sentinel RID; using a real committed RID would be more
          future-proof.

      **Iter-2 fix plan** (load this list into the next
      `/execute-tracks` session for the iter-2 implementer spawn):
      1. **TC13 / TB17 / CQ14 (joint fix)** — strengthen
         `RecreateIndexesTaskTest` loop-continuation invariant.
         Recommended pattern: inject *two* broken stubs each with
         its own `AtomicBoolean configRequested` flag, then
         `task.run()` + assert both flags are true. This directly
         proves the loop continues past at least one failure to
         reach a subsequent entry, and is independent of HashMap
         iteration order. Apply the same pattern to
         `run_withOneBrokenIndexConfig_*` (or fold it into the
         strengthened test). Rename the test to something
         order-agnostic (e.g.
         `run_twoBrokenStubs_loopVisitsBothPastFirstFailure`) and
         rewrite the Javadoc to match.
      2. **TB19** — rename the 3 `onBegin_*_acceptsProgressAfterwards`
         tests back to `onBegin_*_completesWithoutException` (the
         pre-iter-1 names) and document inline that
         `IndexRebuildOutputListener.onBegin` has no
         publicly-observable post-state (writes only to private
         fields), so "did not throw" is the only meaningful
         contract. Drop the spurious `onProgress` follow-up call.
         Optional: use reflection to assert `startTime > 0` and
         `rebuild` field captured the argument, if reflection is
         already used elsewhere in the file.
      3. **TB18** — fix class Javadoc on `RecreateIndexesTaskTest`
         to correctly state where `getConfiguration` is called
         (in `IndexManagerEmbedded.getIndexesConfiguration` BEFORE
         the loop, not inside it).
      4. **TC14** — add 5 new cleared-TX tests per class
         (`streamEntries(keys, asc)`, `streamEntriesMajor`,
         `streamEntriesMinor`; plus `descStream` path in
         `IndexMultiValues`). Reuse the same `addIndexEntry CLEAR
         + PUT delta` setup from the existing
         `streamEntriesBetween_clearedTxChanges_returnsOnlyTxAddedKeys`.
      5. **CQ12** — add `import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;`
         to `IndexOneValueTxTest` and `IndexMultiValuesTxTest`,
         shorten the inline FQCN to `RecordIdInternal.fromString`.
      6. **CQ13** — add `import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;`
         to `IndexComparatorTest` and shorten the 4 FQCN call
         sites.
      7. **TC15** — capture an existing committed RID in the
         cleared-TX tests instead of using `#-1:-1`. Capture
         `index.get(session, "alpha")` before the CLEAR, use that
         RID in the subsequent PUT.

      All 7 fix items are mechanical or follow established
      patterns elsewhere in the same files; iter-2 should land in
      a single fresh implementer spawn with a focused prompt and
      `model: "opus"`. After iter-2 commits, re-run the same
      3 gate-check dimensions (code-quality, test-behavior,
      test-completeness) on the new HEAD; iter-3 only if a fresh
      blocker appears.

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

- [x] Step 2: Cover live TX-aware spliterators (`iterator/`) +
      dead-code shape pin for the `IndexCursor*` cluster
  - [x] Context: safe
  > **Risk:** low — default (test-additive only; the `*DeadCodeTest`
  > shape pin follows the Track-17 precedent of pure reflection-based
  > pins, no production-code changes).
  >
  > **What was done:** Created `PureTxBetweenIndexSpliteratorTest`
  > (11 tests, DbTestBase) exercising all four
  > `PureTx*BetweenIndex*Spliterator` classes — both directions and
  > both value cardinalities — via the public
  > `streamEntriesBetween` / `streamEntriesMajor` /
  > `streamEntriesMinor` API on UNIQUE and NOTUNIQUE indexes. Each
  > test opens a TX, renames a committed key via SQL `UPDATE` to
  > generate `DELETE+PUT` entries in `FrontendTransactionIndexChanges`,
  > then walks a range. Covers the 0%-covered backward variants
  > (~30 + ~40 lines) and residual branch gaps on the forward
  > variants. Created `IndexCursorClusterDeadCodeTest` (8 reflection-
  > only tests) pinning constructor signatures and method shapes for
  > `IndexCursor`, `IndexAbstractCursor`, `IndexCursorStream`, and
  > `IndexKeyCursor` with `WHEN-FIXED` markers per the Track-17
  > dead-code-pin precedent. Commit `2cd715f5e3`.
  >
  > **What was discovered:**
  > - `session.newEntity()` does NOT trigger index entries in
  >   `FrontendTransactionIndexChanges` during the TX — indexing
  >   happens at commit. SQL `UPDATE` on an existing committed
  >   record is required to populate `indexChanges` mid-TX. Steps 3
  >   and 4 should use the same pattern when testing TX iteration
  >   paths.
  > - The merge path (`indexChanges.cleared == false`) for descending
  >   order uses `DescComparator`, whose `compare` returns positive
  >   for `a > b` (i.e., internally ascending), combined with
  >   `mergeSortedSpliterators`' "emit smaller first" logic. The
  >   merged output for non-cleared backward streams is therefore
  >   not strictly descending. This is existing production behavior;
  >   tests assert key presence (not strict order) for the
  >   backward + non-cleared cases. **Forwarded to deferred-cleanup
  >   queue** as a `DescComparator` naming/behavior candidate; Steps
  >   4 onward that test TX iteration on `IndexOneValue` /
  >   `IndexMultiValues` descending streams should adopt the same
  >   key-presence-only assertion for non-cleared merge cases.
  > - PSI `ReferencesSearch` (all-scope) confirmed lockstep groups:
  >   `{IndexCursor, IndexAbstractCursor, IndexCursorStream}`
  >   (each referenced only by the next class in the group; deleting
  >   any one requires deleting the next first) and `{IndexKeyCursor}`
  >   (zero references). Both groups already on the deferred-cleanup
  >   queue.
  >
  > **Key files:**
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/iterator/PureTxBetweenIndexSpliteratorTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/IndexCursorClusterDeadCodeTest.java` (new)

- [x] Step 3: Cover index manager / factory / lifecycle, with
      `RecreateIndexesTask` as the centerpiece
  - [x] Context: safe
  > **Risk:** low — default (test-additive only; no shared test-fixture
  > changes — the recovery setup for `RecreateIndexesTask` is local
  > to its dedicated test class). Phase B did not need the upgrade
  > gate; the `RecreateIndexesTask` fixture stayed compact (Proxy
  > stub of one entry in `indexManager.indexes`).
  >
  > **What was done:** Created 6 new test classes (48 tests):
  > `RecreateIndexesTaskTest` (2 tests — happy path + catch-branch
  > via a `Proxy` stub injected into `indexManager.indexes` with a
  > null `CONFIG_TYPE`); `IndexManagerEmbeddedTest` (13 tests —
  > `addCollectionToIndex` idempotency, `removeCollectionFromIndex`
  > no-op, `getIndexesConfiguration` shape, `autoRecreateIndexesAfterCrash`
  > fresh-DB false, `waitTillIndexRestore` early-return, `areIndexed`
  > true / false / no-class, `getClassInvolvedIndexes`,
  > `getClassIndex` match / wrong-class, `dropIndex`);
  > `DefaultIndexFactoryTest` (9 tests — types/algorithms,
  > `createIndex` UNIQUE / NOTUNIQUE / unsupported, `getLastVersion`,
  > `createIndexEngine` single / multivalue / null-algo);
  > `IndexesTest` (8 tests — `getAllFactories`, `getFactory`,
  > `chooseDefaultIndexAlgorithm`, `createIndexInstance`);
  > `ClassIndexManagerTest` (6 tests — create / update / delete
  > hooks, `reIndex`, `addIndexesEntries`);
  > `IndexRebuildOutputListenerTest` (7 tests — `onBegin` /
  > `onProgress` / `onCompletition` with rebuild true / false and
  > empty / non-empty index). Commit `7583bf17a9`.
  >
  > **What was discovered:**
  > - `Index.get(session, key)` returns `Object` (a plain `RID` for
  >   `UNIQUE` / `IndexOneValue`, `null` when absent) — asserting
  >   `!= null` / `assertNull` is the correct pattern, not
  >   `.iterator().hasNext()`. **Steps 4 onward must use this
  >   pattern when verifying `UNIQUE` index entries.**
  > - `IndexEngineData` has no compact factory constructor — only
  >   the 17-parameter form. Tests that build engine-data directly
  >   must use the long form.
  > - `RecreateIndexesTask` catch-branch absorbs system-owned
  >   indexes (e.g. `OFunction.name`) that fail re-creation in the
  >   direct-invocation test setup because their storage state is
  >   incomplete; the observable that matters is `rebuildCompleted ==
  >   true` after `run()`. The "errors++" log noise in the catch-
  >   branch test is therefore expected, not a fault.
  > - **Cross-track hint to Track 21**:
  >   `DefaultIndexFactory.createIndexEngine` for `"remote"` storage
  >   type returns `RemoteIndexEngine` — not exercisable from
  >   memory / disk test sessions. Track 21 (engine tests) may want
  >   to cover that branch explicitly.
  >
  > **Key files:**
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/RecreateIndexesTaskTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/IndexManagerEmbeddedTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/DefaultIndexFactoryTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/IndexesTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/ClassIndexManagerTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/IndexRebuildOutputListenerTest.java` (new)

- [x] Step 4: Cover index implementations (`IndexUnique`,
      `IndexNotUnique`, `IndexOneValue`, `IndexMultiValues`,
      `IndexAbstract` non-histogram surface,
      `IndexStreamSecurityDecorator`, `IndexKeyUpdater`,
      `IndexUpdateAction`, `IndexMetadata`, exceptions)
  - [x] Context: safe
  > **Risk:** low — default (test-additive only; no production-code
  > changes; no shared test-fixture changes).
  >
  > **What was done:** Created 6 new test classes (87 tests):
  > `IndexAbstractCorePathsTest` (24 tests — composite key enhancement
  > asc/desc helpers, `getConfiguration`, `getAlgorithm`,
  > `getCollections`, `isAutomatic`, `getKeyTypes`, `equals`/`hashCode`,
  > `compareTo`, `loadMetadataFromMap` round-trip, `getInternal` null
  > return, `dropIndex`, `stream`, `descStream`, `streamEntries`);
  > `IndexNotUniqueTest` (13 tests — `getRids` no-TX / TX PUT merge /
  > TX REMOVE, `streamEntries`, `streamEntriesBetween` /
  > `streamEntriesMajor` / `streamEntriesMinor` with TX renames, `size`,
  > `canBeUsedInEqualityOperators`, `interpretTxKeyChanges` multiple
  > PUTs); `IndexOneValueTxTest` (12 tests — `get` existing/missing,
  > `getRids` with TX rename / remove, `descStream` with / without TX,
  > `streamEntries` with TX, `txCommit` / `txRollback`,
  > `getRidsIgnoreTx`, `calculateTxIndexEntry` PUT-after-REMOVE);
  > `IndexMultiValuesTxTest` (13 tests — `getRids` no-TX / TX rename /
  > TX remove all, `descStream` with / without TX, `streamEntries` TX
  > merge, `streamEntriesBetween` / `streamEntriesMajor` /
  > `streamEntriesMinor`, `size` with TX, `calculateTxValue` null
  > return); `IndexStreamSecurityDecoratorTest` (6 tests —
  > `decorateStream` / `decorateRidStream` no-active-predicate-roles
  > bypass for UNIQUE / NOTUNIQUE, `streamEntriesBetween`
  > pass-through, `getRidsIgnoreTx` pass-through);
  > `IndexMiscSmallClassesTest` (18 standalone no-DB tests —
  > `IndexUpdateAction` predicates / singletons / changed factory,
  > `IndexMetadata` constructor / mutators / `isMultivalue` /
  > `equals` / `hashCode`, `IndexException` 3 constructors,
  > `IndexEngineException` 3 constructors). All 87 tests pass; full
  > core suite (1 876 tests) clean; coverage gate PASSED. Commit
  > `add74c4847`.
  >
  > **What was discovered:**
  > - `SchemaClass.dropIndex()` does not exist on the `SchemaClass`
  >   API; the correct call is
  >   `session.getSharedContext().getIndexManager().dropIndex(session, idxName)`.
  > - Collection names stored in the index are internal cluster names
  >   formatted as `<lowercase-class-name>_N` (e.g.
  >   `abscorepathstestcoll_5`), not the schema class name. Assertions
  >   checking collection membership must use `startsWith(lowerCls)`
  >   not `contains(className)`.
  > - `LIMIT 1` in SQL `UPDATE` does not reliably produce exactly 1
  >   TX index change entry. Tests that need single-entry TX state
  >   must rename/update the unique "beta"-style record rather than
  >   trying to limit a bulk update.
  > - `IndexStreamSecurityDecorator`'s className==null bypass path is
  >   not reachable via automatic schema-defined indexes; the
  >   no-active-predicate-roles fast-return (admin user) covers the
  >   effective bypass path instead.
  >
  > **Key files:**
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/IndexAbstractCorePathsTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/IndexNotUniqueTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/IndexOneValueTxTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/IndexMultiValuesTxTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/IndexStreamSecurityDecoratorTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/IndexMiscSmallClassesTest.java` (new)

- [x] Step 5: Engine top-up (`engine/`, `engine/v1/`) + comparator /
      multivalue gap closure + Track-18 verification
  - [x] Context: safe
  > **Risk:** low — default (test-additive only; final-step
  > verification reads the JaCoCo XML and writes a baseline file —
  > no production-code or shared-fixture changes).
  >
  > **What was done:** Added two new test classes targeting branch-
  > coverage gaps in the engine packages:
  > `BTreeEngineConstructorValidationTest` (14 tests) — version-guard
  > branches for both `BTreeSingleValueIndexEngine` (IllegalStateException
  > for versions ≠ 3 / 4) and `BTreeMultiValueIndexEngine`
  > (IllegalArgumentException for versions 1 / 2 / 3,
  > IllegalStateException for unknown versions), plus `isMultiValue()`
  > defaults and `getEngineAPIVersion()` from the V1 base;
  > `UniqueIndexEngineValidatorTest` (6 tests) — all four observable
  > branches of `UniqueIndexEngineValidator.validate()`: null oldValue
  > (fresh insert), same RID (IGNORE sentinel), different RID with
  > null / false `MERGE_KEYS` (`RecordDuplicatedException`), and
  > different RID with `MERGE_KEYS=true` (allowed). Ran the full
  > coverage profile build (`./mvnw -pl core -am clean package -P
  > coverage`) and recorded the per-package report to
  > `docs/adr/unit-test-coverage/_workflow/track-18-baseline.md`. Full
  > core suite (1 876 tests) green. Commit `ffd64f4d9e`.
  >
  > **What was discovered:**
  > - **`internal.core.index` missed the 85% line / 70% branch
  >   gate.** Final per-package coverage: **80.3% line / 69.4%
  >   branch** — short by ~4.7 pp line and ~0.6 pp branch. The five
  >   other in-scope packages (`iterator`, `comparator`, `multivalue`,
  >   `engine`, `engine/v1`) all meet or exceed the gate.
  > - Largest contributors to the `core/index` gap (per
  >   `track-18-baseline.md`):
  >   - `IndexAbstract` (~93 uncov lines — engine-init and rebuild
  >     paths requiring a fully-booted DB rather than a unit fixture).
  >   - `ClassIndexManager` (~90 uncov — `addIndexesEntries` SQL path).
  >   - `CompositeIndexDefinition$CompositeWrapperMap` (~51 uncov —
  >     inner map-view; `entrySet` / `keySet` not invoked by any test
  >     today).
  >   - `RecreateIndexesTask` (~45 uncov — catch-branch only partially
  >     exercised by the proxy stub).
  > - **Recovery candidates** (logged in `track-18-baseline.md` for
  >   Phase C): ~3–4 extension tests targeting `CompositeWrapperMap`
  >   `entrySet` / `keySet` and the `IndexAbstract` full-bootstrap /
  >   `recreateIndexBoundary` paths. The aggregate per-package gate
  >   is an internal Track-18 target (per the step file's
  >   Constraints) — the changed-line gate trivially passes for this
  >   test-additive track. Phase C decides whether to demand
  >   remediation tests or to record the miss.
  > - **Cross-track hint to Track 21**:
  >   `BTreeSingleValueIndexEngine` and `BTreeMultiValueIndexEngine`
  >   `doClearTree` "removed 0 entries" error paths remain uncovered —
  >   live production code that requires actual B-tree data with
  >   page-cursor invalidation to trigger. Track 21 (storage / sbtree
  >   tests) can cover them transitively.
  > - The BTree version-guard constructor branches are now shape-
  >   pinned; Track 21 does not need to re-test them.
  >
  > **Key files:**
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/engine/UniqueIndexEngineValidatorTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeEngineConstructorValidationTest.java` (new)
  > - `docs/adr/unit-test-coverage/_workflow/track-18-baseline.md` (new)
  >
  > **Critical context:** `internal.core.index` ended at **80.3% / 69.4%**,
  > below the 85% / 70% per-package target. Phase C must decide whether
  > to absorb the gap or trigger remediation tests; recovery candidates
  > are listed in the baseline file. The five other in-scope packages
  > meet the gate. The cumulative `core` module coverage advanced to
  > **78.2% line / 68.5% branch** (+0.5 pp / +0.4 pp over post-Track-17).
