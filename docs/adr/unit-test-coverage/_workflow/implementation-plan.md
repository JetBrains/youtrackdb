# Unit Test Coverage — Core Module

## Design Document
[design.md](design.md)

## High-level plan

### Goals

Raise the `core` module's unit test coverage from the original baseline
(63.6% line / 53.3% branch) to a realistic target of **~82–83% line /
~70–71% branch** coverage (amended at Track 22 Phase A iter-1 — the
original 85% / 70% headline proved mathematically unreachable in the
final sweep track per adversarial-review finding A4; see Track 22 split
into 22a/22b/22c below). This requires covering approximately **19,000
additional lines** and **7,300 additional branches** across 177 packages.

The work is a systematic sweep: identify the lowest-coverage packages,
write focused unit tests for their uncovered code paths, and track
progress using a per-package coverage analyzer.

Tracks 2-22a/22b/22c are mutually independent in the same testability-tier
sense — they can be reordered during execution based on priority without
affecting correctness, **except that 22b depends on 22a** (its dead-code
deletion classification consumes 22a's PSI safe-delete confirmations) and
**22c depends on 22b** (its marker rewrite filters out clusters deleted
by 22b). The track ordering reflects a testability-tier strategy (D1)
but is not a hard dependency chain. Their only shared dependency is
Track 1 (coverage measurement infrastructure).

### Constraints

1. **JUnit 4** — Core module tests use JUnit 4 with `surefire-junit47`
   runner. All new tests must follow this convention.
2. **DbTestBase lifecycle** — Tests requiring a database session must
   extend `DbTestBase` (creates/destroys an in-memory database per test
   method via `@Before`/`@After`). Tests that can run without a database
   should be standalone (no base class).
3. **No parallel test processes** — Only one `./mvnw test` invocation may
   run in a given worktree at a time (see CLAUDE.md).
4. **Spotless formatting** — Run `./mvnw -pl core spotless:apply` before
   every commit.
5. **Coverage verification** — After each track, run
   `./mvnw -pl core -am clean package -P coverage` and verify improvement
   using the coverage analyzer script (Track 1).
6. **Existing test classes preferred** — Add tests to existing test
   classes when the scope fits. Create new classes only when no suitable
   existing class covers the area.
7. **Coverage exclusions** — The following should not receive tests:
   - *JaCoCo exclusions (not measured by JaCoCo):*
     - `**/core/sql/parser/**` (generated SQL parser)
     - `**/core/gql/parser/gen/**` (generated GQL parser)
     - `**/api/gremlin/*.class` (Gremlin API top-level)
   - *Testing exclusions (measured by JaCoCo but not targeted by this plan):*
     - `**/api/gremlin/embedded/schema/**` (Gremlin schema manipulation —
       not ready for testing)
     - `**/api/gremlin/tokens/schema/**` (Gremlin schema tokens — not ready
       for testing)
   Note: The testing exclusions ARE included in JaCoCo reports and will
   affect aggregate coverage numbers. The coverage analyzer should be
   aware of this distinction.
8. **Disk-based test environment** — CI runs tests with
   `-Dyoutrackdb.test.env=ci` (disk storage). Tests must pass in both
   memory and disk modes.
9. **Coverage measurement** — The existing `coverage-gate.py` checks only
   changed lines in PRs. A separate overall coverage analyzer is needed
   (Track 1) to measure and report per-package totals.
10. **Test descriptions** — Every test must have a descriptive method name
    or comment explaining the scenario and expected outcome.
11. **Rebase on `origin/develop` at the start of every track** — This plan
    is very large and long-lived; staying in sync with the remote is
    mandatory to avoid drift and painful late-stage merge conflicts. At the
    beginning of each track (before Phase A):
    1. `git fetch origin` and `git rebase origin/develop`.
    2. Resolve any conflicts in source, tests, or formatting.
    3. Run the full `core` unit-test suite
       (`./mvnw -pl core clean test`) and fix any new failures introduced
       by the rebase — do NOT proceed to Phase A until the suite is green.
    4. If the rebase touches areas covered by integration tests, also run
       `./mvnw -pl core clean verify -P ci-integration-tests`.
    5. Re-run `./mvnw -pl core spotless:apply` to pick up any formatter
       changes on develop.
    Record the pre-rebase and post-rebase SHAs in the track's step file so
    the rebase is auditable. If conflicts force non-trivial rework of
    already-committed steps, ESCALATE — the plan may need adjustment.

### Architecture Notes

#### Component Map

```mermaid
flowchart TD
    subgraph TestInfra["Test Infrastructure"]
        CA["coverage-analyzer.py\n(new — Track 1)"]
        DB["DbTestBase\n(existing)"]
        BMI["BaseMemoryInternalDatabase\n(existing)"]
        GB["GraphBaseTest\n(existing)"]
    end

    subgraph CoverageTargets["Coverage Target Areas"]
        SQL["SQL Layer\nsql/operator, sql/filter,\nsql/functions, sql/method,\nsql/executor"]
        CMN["Common Layer\ncommon/util, common/parser,\ncommon/io, common/concur"]
        SER["Serialization Layer\nserializer/record/string,\nserializer/record/binary"]
        DBPKG["DB & Record Layer\ndb, db/tool, db/record,\nrecord/impl"]
        META["Metadata & Security\nmetadata/schema, security,\nmetadata/security"]
        IDX["Index Layer\nindex, index/engine"]
        STR["Storage Layer\nstorage/cache, storage/impl,\nstorage/index/sbtree"]
        CMD["Command & Query\ncommand/script, query,\nfetch, schedule"]
        GRM["Gremlin & Other\ngremlin, tx, engine"]
    end

    CA -->|"measures"| CoverageTargets
    DB -->|"base class for"| SQL
    DB -->|"base class for"| DBPKG
    DB -->|"base class for"| META
    DB -->|"base class for"| IDX
    DB -->|"base class for"| CMD
    GB -->|"base class for"| GRM
    GB -->|"extends"| DB
    BMI -->|"extends"| DB
```

- **coverage-analyzer.py** (new): Parses JaCoCo XML reports and produces
  per-package overall coverage summaries. Used to track progress across
  tracks. Not a production component — a developer/CI tool.
- **DbTestBase** (existing): Base class for tests requiring a database
  session. Creates an in-memory YouTrackDB instance per test method.
  Used by SQL, DB, Metadata, Index, Command, and Gremlin tests.
- **BaseMemoryInternalDatabase** (existing): Extends DbTestBase. Used when
  tests specifically need in-memory storage guarantees.
- **GraphBaseTest** (existing): Extends DbTestBase, adds Gremlin graph
  setup. Used by Gremlin and graph-related tests.
- **Standalone unit tests** (no base class): Used for pure utility
  classes, serialization round-trips, and any code testable without a
  database. Preferred when possible — faster and more isolated.
- **Coverage target areas**: Nine clusters of packages organized by
  functional area and testability tier. Tracks are ordered so
  highest-testability areas (SQL functions, utilities) come first,
  hardest areas (storage internals) come last.

#### D1: Test-first ordering by testability tier
- **Alternatives considered**: (a) Order by package size (largest gap
  first), (b) Order by functional area (storage, then SQL, then DB),
  (c) Order by testability (easiest first).
- **Rationale**: Option (c) wins because quick wins build momentum,
  validate the approach early, and yield measurable coverage improvements
  per track. Large-gap packages like `sql/executor` (1,735 uncov) are
  medium-testability and scheduled mid-plan. Hard packages like
  `storage/cache` are deferred to late tracks where the remaining gap is
  clearest.
- **Risks/Caveats**: Late tracks targeting storage internals may face
  diminishing returns — some code paths (WAL replay, crash recovery) may
  require integration tests rather than unit tests.
- **Implemented in**: Track ordering (Tracks 2-7 = high testability,
  8-17 = medium, 18-21 = low (storage internals), 22a/22b/22c = mixed
  (final sweep — Track 22 split during inline-replan after Phase A iter-1
  per A3 BLOCKER; 22a main-coverage, 22b deletion lockstep, 22c WHEN-FIXED
  issue creation))

#### D2: Standalone tests over DbTestBase where possible
- **Alternatives considered**: (a) All tests extend DbTestBase for
  uniformity, (b) Standalone tests for pure utility code.
- **Rationale**: Option (b) — standalone tests are faster (no DB
  lifecycle), more isolated (no shared state), and better for true unit
  testing. DbTestBase should only be used when the code under test
  genuinely requires a database session.
- **Risks/Caveats**: Some classes appear standalone but internally depend
  on a database context (e.g., `SQLFunction.execute()` often needs a
  session). The execution agent must check imports and dependencies
  before choosing standalone vs. DbTestBase.
- **Implemented in**: All tracks — the execution agent decides per test
  class.

#### D3: Coverage measurement via Python analyzer
- **Alternatives considered**: (a) Modify existing `coverage-gate.py` to
  support overall mode, (b) Create a separate script, (c) Use JaCoCo's
  HTML report.
- **Rationale**: Option (b) — the existing gate script is tightly coupled
  to git diff logic and PR comments. A separate analyzer is simpler,
  avoids risk to the CI gate, and can produce per-package breakdowns
  needed for tracking progress across tracks.
- **Risks/Caveats**: Two scripts to maintain. Mitigated by keeping the
  analyzer simple (read-only, no CI integration beyond optional output).
- **Implemented in**: Track 1

#### D4: Accept lower coverage for storage internals
- **Alternatives considered**: (a) Target 85%/70% uniformly across all
  packages, (b) Accept lower targets for inherently hard-to-test code.
- **Rationale**: Option (b) — packages like `storage/cache/local`
  (WOWCache, 4,457 lines of concurrent cache code),
  `storage/index/sbtree` (B-tree internals), and `storage/impl/local`
  (disk I/O with WAL) require integration-level tests with complex setup.
  Forcing 85% line coverage here would mean either fragile tests or
  excessive mocking. Instead, target ~65-70% line coverage for storage
  and compensate with higher coverage in more testable areas.
- **Risks/Caveats**: Overall 85% target may be tight if storage coverage
  remains low. Mitigated by aggressive coverage in SQL, common, and
  serialization areas.
- **Implemented in**: Tracks 19-21

#### D5: One PR per track
- **Alternatives considered**: (a) One giant PR, (b) One PR per step,
  (c) One PR per track.
- **Rationale**: Option (c) — each track is a coherent unit of work
  targeting a specific area. One PR per track keeps reviews manageable
  (5-7 commits) and allows incremental merging. The `[no-test-number-check]`
  PR title tag can be used since we're adding tests without changing
  production code.
- **Risks/Caveats**: 24 PRs is a lot (the final-sweep track was split
  into three sub-tracks during inline replanning — see the inline-
  replan note below — which adds two PRs over the pre-split count).
  Tracks can be batched into larger PRs if the team prefers.
- **Inline-replan note (after Track 22 Phase A iter-1)**: Track 22 was
  split into 22a/22b/22c, producing three squashed merge-commits instead
  of one. The split honours D5's intent (each sub-track is independently
  reviewable). 22a (~6 steps) and 22c (~1–2 steps) sit within the 5-7
  commit cap; 22b's `~8 steps — one per in-track-deletion cluster` is a
  controlled exception — each cluster is one bisectable commit, and 22b
  Phase A may pack two narrow clusters into one step if the final
  inventory exceeds 7. No D5 amendment needed.
- **Implemented in**: All tracks

#### Integration Points

- `coverage-analyzer.py` reads JaCoCo XML from
  `.coverage/reports/youtrackdb-core/jacoco.xml` (produced by
  `./mvnw -pl core -am clean package -P coverage`)
- New tests integrate with existing surefire configuration: parallel fork
  (4 threads) for default tests, sequential fork for `@SequentialTest`
- Tests using `DbTestBase` depend on the in-memory YouTrackDB lifecycle
  managed by `@Before`/`@After`

#### Non-Goals

- **Modifying production code** — Production code changes are permitted
  in two cases: (1) Refactoring of internal classes to increase
  testability, but not public API changes. (2) All bugs found during
  testing or code review must be fixed and covered by regression tests.
- **Integration tests** — This plan targets unit tests only (surefire).
  Integration tests (failsafe, `-P ci-integration-tests`) are out of
  scope.
- **Other modules** — Only the `core` module is in scope. `server`,
  `driver`, `embedded`, `tests`, and `docker-tests` are future work.
- **100% coverage** — The target is ~82–83% line / ~70–71% branch
  overall (see §Goals — amended at the final-sweep track's Phase A
  iter-1 because the original 85% / 70% headline proved mathematically
  unreachable under the post-deletion denominator). Some packages will
  remain below this if the code is inherently hard to unit test. The
  goal is to raise the aggregate.
- **Gremlin schema manipulation tests** — Classes in
  `api/gremlin/embedded/schema` and `api/gremlin/tokens/schema` are
  excluded — not ready for testing.

## Checklist

- [x] Track 1: Coverage Measurement Infrastructure
  > Create a Python script (`coverage-analyzer.py`) that parses JaCoCo
  > XML reports and produces per-package overall coverage summaries.
  > Unlike the existing `coverage-gate.py` (which checks only changed
  > lines in PRs), this script computes totals across all lines in each
  > package and generates a sorted table of packages by uncovered line
  > count.
  >
  > **Track episode:**
  > Created coverage measurement infrastructure for all subsequent tracks.
  > Added `.github/scripts/coverage-analyzer.py` (185 lines) — parses
  > JaCoCo XML and outputs per-package markdown tables sorted by uncovered
  > lines. Recorded baseline in `coverage-baseline.md`: 63.6% line /
  > 53.3% branch / 177 packages. Baseline confirms plan gap analysis:
  > ~19,000 lines and ~7,300 branches needed to reach 85%/70% targets.
  > No cross-track impact — read-only tooling used by all future tracks.
  >
  > **Step file:** `tracks/track-1.md` (2 steps, 0 failed)
  >
  > **Strategy refresh:** CONTINUE — no downstream impact detected.

- [x] Track 2: Common Pure Utilities
  > Write unit tests for the `common` package's pure utility classes that
  > require no database session. These are self-contained classes with
  > clear inputs/outputs, making them ideal first targets.
  >
  > **Track episode:**
  > Added 432 unit tests across 20 new and 4 extended test files for all 7
  > target packages. Found and fixed a genuine bug in
  > `RawPairLongObject.equals()` (cast to wrong type). Documented several
  > pre-existing issues: `Binary.compareTo` different-length limitations,
  > `ModifiableInteger` overflow bypass, `LRUCache` off-by-one capacity,
  > `ErrorCode` reflection failures, `Streams` dedup asymmetry. Track-level
  > code review identified additional `MultiValue` branch coverage gaps
  > (add/remove/getValue/setValue/contains) suitable for Track 22 final
  > sweep. No cross-track impact — only `common` package tests and one
  > production bug fix.
  >
  > **Step file:** `tracks/track-2.md` (5 steps, 0 failed)
  >
  > **Strategy refresh:** CONTINUE — no downstream impact detected. All
  > discoveries localized to `common` package; `MultiValue` gaps noted for
  > Track 22 final sweep.

- [x] Track 3: Common I/O, Parser & Logging
  > Write unit tests for common infrastructure classes that handle I/O,
  > parsing, and logging. Most of these are pure utilities but some have
  > external dependencies (file system, native libraries).
  >
  > **Track episode:**
  > Added ~250 unit tests across 11 test files (all new except IOUtilsTest
  > extended) covering common/parser (95.4%/90.4%), common/io (87.7%/79.2%),
  > common/profiler/metrics (95.2%/75.8%), and common/log (68.1%/51.1%).
  > Discovered pre-existing bugs: StringParser.indexOfOutsideStrings backward
  > search exits after one position, VariableParser loses default during
  > recursion, IOUtils.isLong("") vacuous-truth bug, FileUtils.getSizeAsNumber("")
  > same pattern, IOUtils.getRelativePathIfAny crashes when base equals URL.
  > Track-level review added @SequentialTest to MetricsRegistryTest for JMX
  > isolation, strengthened vacuous assertions, and added 11 boundary tests.
  > No cross-track impact — only common package test files added.
  >
  > **Step file:** `tracks/track-3.md` (4 steps, 0 failed)
  >
  > **Strategy refresh:** CONTINUE — no downstream impact detected. All
  > discoveries localized to `common` package (parser bugs, I/O vacuous-truth
  > bugs, logging details). Track 4 targets independent subsystems.

- [x] Track 4: Common Concurrency & Memory
  > Write tests for concurrency primitives and direct memory management.
  > These require careful testing with thread synchronization.
  >
  > **Track episode:**
  > Added ~250 unit tests across 22 test files (19 new, 3 extended) for all
  > 4 target packages. Found and fixed 2 production bugs in
  > PartitionedLockManager: `releaseSLock()` called `sharedLock()` instead of
  > `sharedUnlock()`, and `acquireExclusiveLocksInBatch(int[])` allocated a
  > zero-filled array instead of copying input values. Documented pre-existing
  > behaviors: ReadersWriterSpinLock no read→write upgrade, NonDaemonThreadFactory
  > inherits daemon flag, SourceTraceExecutorService bypasses checked exceptions.
  > Coverage: lock 87.0%/71.7% PASS, resource 84.5%/77.8% (0.5% below line),
  > thread 95.6%/92.5% PASS, directmemory 70.1%/59.7% (PROFILE_MEMORY paths).
  > No cross-track impact — only common package tests and 2 production bug fixes.
  >
  > **Step file:** `tracks/track-4.md` (5 steps, 0 failed)
  >
  > **Strategy refresh:** CONTINUE — no downstream impact detected. All
  > discoveries localized to `common` package; directmemory/resource
  > coverage shortfalls accepted (PROFILE_MEMORY paths, 0.5% margin).

- [x] Track 5: SQL Operators & Filters
  > Write tests for SQL operator and filter classes — the lowest-coverage
  > area in the SQL layer (sql/operator at 20.9%, sql/filter at 39.9%).
  >
  > **Track episode:**
  > Added ~560 unit tests across 10 test files (8 new, 2 extended) covering
  > sql/operator, sql/operator/math, and sql/filter. Rewrote Plus/Minus/
  > Multiply/Divide math tests from monolithic to focused. Final coverage:
  > sql/operator 83.0%/75.3% (+17%/+19%), sql/operator/math 91.1%/90.2%,
  > sql/filter 78.0%/64.8%. sql/operator is 2% below line target and
  > sql/filter is 7% below — remaining uncovered paths are BinaryField/
  > EntitySerializer comparator paths and full SQL-execution contexts
  > covered by integration tests. Fixed 2 production bugs with falsifiable
  > regression tests: QueryOperatorContainsValue early-return in condition
  > loop; QueryOperatorTraverse FieldAny.FULL_NAME copy-paste where FieldAll
  > was intended. Documented 9 pre-existing bugs/inconsistencies with
  > WHEN-FIXED markers: And/Or null-right NPE asymmetry; ContainsText
  > ignoreCase never consulted; QueryOperatorEquals dead-code branch;
  > In operator Set.contains() bypasses type coercion; ContainsAll
  > over-counting with duplicate left elements; Instanceof left/right
  > asymmetry; Mod dispatches on left type only (silent truncation);
  > tryDownscaleToInt exclusive-boundary off-by-one; IS DEFINED
  > SQLFilterItemField branch uses Object.toString identity as field-name
  > key. Track-level code review (1 iteration, PASS): applied 13 should-fix
  > improvements — strengthened assertions in SQLFilterClassesTest; added
  > LIKE regex-escape tests for 8 untested chars; MATCHES malformed-regex
  > and null-context tests; IS DEFINED sentinel tests;
  > DefaultQueryOperatorFactoryTest exactly-one-of-class; removed duplicate
  > isSupportingBinaryEvaluate tests and dead createClass setup; added
  > WHEN-FIXED markers to bug-pinning tests. No cross-track impact.
  >
  > **Step file:** `tracks/track-5.md` (6 steps, 0 failed)
  >
  > **Strategy refresh:** CONTINUE — no downstream impact detected. Track 6
  > (SQL Functions) uses independent SQLFunctionFactory dispatch path; all
  > Track 5 discoveries localized to operator/filter subsystem. Carry forward
  > falsifiable-regression + WHEN-FIXED-marker convention.

- [x] Track 6: SQL Functions
  > Write tests for SQL function implementations. Functions are
  > self-contained with clear `execute()` contracts, making them highly
  > testable.
  >
  > **Track episode:**
  > Added 940 `@Test` methods across 83 test files under
  > `core/src/test/java/.../sql/functions/**` covering all nine target
  > subpackages (graph, coll, misc, math, stat, text, conversion, geo,
  > result) plus factory infrastructure. No production code was modified
  > — Track 6 is purely test-additive. All latent bugs and inconsistencies
  > discovered were pinned as falsifiable regressions with `// WHEN-FIXED:`
  > markers (~20 markers total) for Track 22's production-side fixes.
  >
  > Key discoveries with cross-track impact:
  > `CustomSQLFunctionFactory` uses a process-wide `HashMap` mutated
  > without synchronization — latent flakiness under parallel surefire.
  > Mitigated in Track 6 via `@Category(SequentialTest)` + UUID-qualified
  > prefix + alphabetical `@FixMethodOrder`. Production-side fix
  > (`HashMap → ConcurrentHashMap` or `Collections.synchronizedMap` +
  > defensive copy in `getFunctionNames`) deferred to Track 22 along with
  > a concurrent register/lookup contract test (TX2, BC10, TX5).
  > `SQLFunctionRuntime` is coupled to the SQL parser and not unit-testable
  > — explicitly deferred to Tracks 7/8. `misc.SQLFunctionFormat` is dead
  > code (not registered in `DefaultSQLFunctionFactory`) — pinned via
  > `SQLFunctionFormatMiscDeadTest` with a WHEN-FIXED marker flagging it
  > for removal in Track 22. `session.commit()` detaches returned
  > `Iterable<Vertex>` wrappers — graph-dispatcher tests must collect
  > identities into a local `List` before committing (pattern for Tracks
  > 7, 8, 14, 22). `DbTestBase` shares one session across test methods in
  > a class; a test that leaks an open transaction cascade-fails the whole
  > class — established `@After rollbackIfLeftOpen` safety-net idiom
  > (itself a DRY candidate for Track 22, CQ2).
  >
  > Plan deviations: track grew from ~6 scope-indicator steps to 8 actual
  > steps because track-review flagged `SQLMethod*` classes physically
  > under `sql/functions/` (text/, conversion/, coll/, misc/) that JaCoCo
  > attributes to Track 6. Absorbed into steps 4, 6, 8 with the corrected
  > `execute(iThis, record, context, ioResult, params)` signature
  > (different from `SQLFunction.execute` order).
  >
  > Track-level code review ran 3 iterations: iter-1 surfaced 1 blocker
  > (BC1/TX1 — `CustomSQLFunctionFactory` race) + 18 should-fix + 22
  > suggestions, all in-scope resolved across commits `4aad8dd..7e32145`.
  > Iter-2 gate check found one should-fix regression **introduced by
  > iter-1's own fix**: PM-window WHEN-FIXED sentinel's `Assume.assumeTrue`
  > gated on production-mutated result's `AM_PM`, causing silent SKIP on
  > every runner once the bug is fixed. Fixed in `14c72eb` by reading
  > `AM_PM` from the raw input instant; also tightened Astar
  > Identifiable-options test (TB14) to pin the middle hop. Iter-3 final
  > gate (BC+TB dimensions only) PASS with 1 suggestion. Zero open
  > blockers, zero should-fix at track end; ~15 suggestion-grade items
  > legitimately deferred (most map to Track 22's scope).
  >
  > **Step file:** `tracks/track-6.md` (8 steps, 0 failed)
  >
  > **Strategy refresh:** CONTINUE — all cross-track discoveries are deferred
  > cleanly to Track 22 (CustomSQLFunctionFactory race, SQLFunctionFormat
  > dead code, MultiValue gaps, rollbackIfLeftOpen DRY) or are patterns to
  > carry forward (falsifiable-regression + WHEN-FIXED marker, Iterable.
  > commit() detach pattern, SQLMethod.execute signature awareness).
  > SQLFunctionRuntime coverage naturally falls out of Tracks 7/8 SQL
  > execution. No Component Map changes; Track 7's `sql/method/*` scope is
  > disjoint from Track 6's absorbed `sql/functions/*` SQLMethod classes.

- [x] Track 7: SQL Methods & SQL Core
  > Write tests for SQL method implementations and the SQL root/query
  > packages.
  >
  > **Track episode:**
  > Added ~1,200 unit tests across 41 test files (40 new, 1 extended) covering
  > all Track 7 scope packages. Coverage deltas: `sql/method/misc` 58.6%/41.6%
  > → **92.2%/88.0%**; `sql/method` 62.0%/36.2% → **87.1%/81.2%**;
  > `sql/method/sequence` 23.1%/16.7% → **100%/100%**; `sql` (live)
  > 39.7%/34.7% → **80.1%/76.9%** (aggregate capped by pinned dead code for
  > Track 22 deletion); `sql/query` 2.9%/2.6% → **79.1%/57.9%** (exceeded
  > the 30-40% decomposition expectation). Aggregate module coverage
  > 63.6%/53.3% → **70.6%/61.0%** (+7.0pp line / +7.7pp branch).
  >
  > **Production bugs pinned as WHEN-FIXED regressions (~16 entries for
  > Track 22 queue)**: SQLMethodContains `&&→||` guard, SQLMethodNormalize
  > iParams[0↔1] mix-up, SQLMethodLastIndexOf/IndexOf/Prefix/CharAt null-
  > guard asymmetries, SQLMethodField null-unguarded isArray NPE,
  > DefaultSQLMethodFactory.createMethod case-sensitivity mismatch,
  > SQLMethodFunctionDelegate no-no-arg-ctor dead Class<?> registration,
  > AbstractSQLMethod.getParameterValue AIOBEs (empty string, single quote),
  > SQLFunctionRuntime.java:104 type-pun (instanceof checks iCurrentRecord
  > but casts iCurrentResult — CCE hazard), SQLMethodRuntime iEvaluate dead
  > flag, IndexSearchResult.equals two latent NPEs, IndexSearchResult.mergeFields
  > branch-2 drops right's containsNullValues, RuntimeResult.getResult line 73
  > overwrites canExcludeResult, BasicLegacyResultSet + ConcurrentLegacyResultSet
  > iterator strict-`>` guard, BasicLegacyResultSet UOE message copy-paste
  > drift, LiveLegacyResultSet.setCompleted commented-out body,
  > SQLHelper.parseStringNumber suffix-strip bug. Plus 3 concurrency pins
  > (DefaultSQLMethodFactory HashMap race, SQLEngine.registerOperator
  > non-atomic SORTED_OPERATORS clear, SQLEngine.scanForPlugins partial
  > cache clear).
  >
  > **Plan corrections absorbed into Track 22** (via iter-1 update to this
  > file): CQ3/TS5 shared test-fixture extraction; TS3/TS6 oversized-test-
  > class splits; TS4/TS7/TS9 @Parameterized conversions; TX5 multi-threaded
  > race-exercising tests paired with WHEN-FIXED production-side fixes;
  > CQ1/TC3 license-banner cleanup + unicode/Turkish-locale string-method
  > coverage.
  >
  > **Patterns carried forward**: falsifiable regression + WHEN-FIXED marker
  > convention; `@After rollbackIfLeftOpen` safety-net idiom using
  > `getActiveTransactionOrNull() + tx.isActive()`; `session.begin()` +
  > `tx.rollback()` in finally for entity-populating tests; SequentialTest +
  > FixMethodOrder + UUID-qualified marker + snapshot-and-assert for tests
  > that mutate process-wide static state; counting CommandContext wrapper
  > (introduced in iter-2) for fallback-branch mutation-testing where both
  > primary and fallback resolve to identical values.
  >
  > **Cross-track impact**: Minor-to-moderate. No Component Map or Decision
  > Record changes. Track 22's scope expands by ~16 production-fix queue
  > entries + DRY cleanup items (cataloged above). Step 4 bridged Track 6's
  > `sql/functions` package for SQLFunctionRuntime — no artifact duplication.
  > Track 8 (executor) inherits SQLScriptEngine + CommandExecutorSQLAbstract
  > indirect-coverage expectation (deferred from Track 7). Plan grew from
  > ~5 scope-indicator steps to 8 actual steps (matches Track 6 precedent
  > under dimensional review).
  >
  > **Track-level code review**: 2 iterations, 6 dimensions (CQ, BC, TB, TC,
  > TX, TS). Iter-1: 0 blockers / 17 should-fix / 39 suggestions; applied
  > 13 should-fix fixes, deferred remaining to Track 22. Iter-2 gate-check:
  > all 13 iter-1 fixes VERIFIED; 1 new should-fix (TB13 — vacuous variable-
  > fallback test strengthened via counting CommandContext) + 1 suggestion
  > (TS13 — misleading comment corrected) fixed in iter-2. Final verdict:
  > **PASS**. 0 open blockers, 0 open should-fix; ~10 suggestion-grade
  > items deferred or accepted as merge-ready.
  >
  > **Step file:** `tracks/track-7.md` (8 steps, 0 failed)
  >
  > **Strategy refresh:** CONTINUE — no downstream impact detected. Track 7's
  > legacy result-set pins (`core/sql/query`) are disjoint from Track 8's
  > modern `core/sql/executor/resultset` scope. **Correction (per Track 8
  > Phase A reviews):** Track 7's earlier expectation that
  > `SQLScriptEngine` and `CommandExecutorSQLAbstract` would "fall out of
  > Track 8's executor steps" is structurally wrong — both classes live in
  > `core/sql/` (the package Track 7 itself owned), not in
  > `core/sql/executor/*`. `SQLScriptEngine` (192 LOC, 35.8% coverage) is
  > best handled by Track 9 (Command & Script) or Track 22; Track 8 will
  > absorb only `CommandExecutorSQLAbstract`'s trivial 2-method tail
  > opportunistically. Track 22 queue grew by ~16 WHEN-FIXED entries + DRY
  > items (already documented in plan). Carry forward to Track 8:
  > falsifiable-regression + WHEN-FIXED convention; `@After rollbackIfLeftOpen`
  > idiom; `Iterable` detach-after-commit pattern; SequentialTest guard for
  > static-state tests; counting CommandContext wrapper for fallback-branch
  > mutation testing.

- [x] Track 8: SQL Executor & Result Sets
  > Write tests for SQL execution step classes, the SELECT planner, the
  > result-collection wrappers, and the metadata-execution helpers. This is
  > the largest coverage gap in the SQL layer (~2,109 uncov lines) but at
  > medium testability since most production classes here require a live
  > `DatabaseSessionEmbedded` to exercise their uncovered branches.
  >
  > **Track episode:**
  > Added ~19,971 lines of new tests across 52 files covering `core/sql/executor/*`,
  > `core/sql/executor/resultset/*`, and `core/sql/executor/metadata/*`. Purely
  > test-additive except one production change in Step 4 (dead-code removal of five
  > zero-caller package-private helpers in `FetchFromIndexStep.java`).
  >
  > **Key discoveries with cross-track impact:**
  > - **Global-LET stream-exhaustion behavior** (TB15 via iter-2 gate check): a
  >   promoted global-LET `$sub = (SELECT FROM className)` is materialized once but
  >   its stream is consumed by the first outer row's `size()` call, leaving
  >   subsequent rows with an empty view (`row[0].cnt == 3`, `rows[1..].cnt == 0`).
  >   Pinned via observed-shape assertion with `WHEN-FIXED: Track 22` marker —
  >   semantic question (stream-exhaustion vs. per-outer-row resolution) queued
  >   for Track 22.
  > - **Four dead/semi-dead classes** pinned with WHEN-FIXED markers for Track 22
  >   deletion: `InfoExecutionPlan`, `InfoExecutionStep`, `TraverseResult`,
  >   `BatchStep` (BatchStep's public ctor is zero-callers; the `-1` batchSize
  >   fallthrough path is reachable but unused; Step 4's iter-1 fix pinned the
  >   batchSize=0 ArithmeticException under an active tx).
  > - **Test-strategy precedent codified for later tracks**: DbTestBase-by-default
  >   for executor-step tests (per-track D2 override); direct-step tests (stubbed
  >   `AbstractExecutionStep` + manual `ResultInternal` predecessors) for
  >   step-internal branches; SQL round-trip reserved for `SelectExecutionPlanner`
  >   branch coverage; falsifiable-regression + WHEN-FIXED markers for latent
  >   bugs; `@After rollbackIfLeftOpen` safety net on `TestUtilsFixture`;
  >   `// forwards-to: Track NN` convention for cross-track bug pinning.
  >
  > **Plan corrections** (applied via commit `7b9313eb4b`): Track 22 scope expanded
  > to absorb iter-1 deferrals — CQ1/TS1 (hoist `newContext`/`sourceStep`/`drain`
  > into `TestUtilsFixture`), CQ2/TS2 (`uniqueSuffix` hoist), CQ3 (extract
  > `streamOfInts`/`CloseTracker`/`NoOpStep` to shared resultset helper),
  > CQ4 (inline-FQN replacements in `FetchFromIndexStepTest`,
  > `ExecutionStreamWrappersTest`, `SmallPlannerBranchTest`), CQ8/TS8 (remove
  > try/catch/rollback boilerplate where `rollbackIfLeftOpen` covers it), 8
  > corner-case TC pins (TC3–TC9, TC12 — CreateRecord total<0, Update*Step
  > non-ResultInternal pass-through, FetchFromCollection unknown/negative ID,
  > FetchFromClass partial-collections subset, LetExpressionStep subquery-throws,
  > Skip→Limit composition, UpsertStep multi-row, InsertValuesStep rows<tuples),
  > and ~37 suggestion-tier items across CQ5–CQ10, BC1–BC2, TB8–TB9, TC13–TC21,
  > TS3/TS6–TS7/TS9–TS14, TX1/TX3–TX8. Iter-2 additionally surfaced CQ11–CQ13,
  > TS15–TS17, TB16–TB17 which fold into the existing Track 22 entries without
  > needing new bullets.
  >
  > **Track-level code review (3 iterations, max reached; final PASS):**
  > - Iter-1 (6 dimensions: CQ/BC/TB/TC/TS/TX): 2 blockers + 25 should-fix + 37
  >   suggestions. Applied 13 should-fix items in commit `dea1b1a219`
  >   (TB1/TB2 blockers dropped non-falsifiable `"colleciton"||"collection"` and
  >   `createVertex_defaultTargetV` identity-only; TB3–TB7 precision tightens;
  >   TC1, TC2, TC10, TC11 completeness pins; TS4, TS5 javadoc corrections;
  >   TX2 InterruptResultSet daemon-thread + `isAlive()` gate).
  > - Iter-2 (6-dimension gate check): BC/CQ/TC/TS/TX PASS; TB FAIL with 5
  >   new should-fix (TB10–TB14, all siblings of iter-1 patterns the earlier
  >   sweep missed) + TB15 observed-shape pin + TB16/TB17 suggestions. Applied
  >   in commit `a4895ac92e`.
  > - Iter-3 (TB-only gate check): PASS. All iter-2 TB fixes VERIFIED against
  >   production sources (`SelectExecutionPlanner.java:1585`,
  >   `UpdateExecutionPlanner.java:193`, `ResultInternal.java:497-557`,
  >   `SQLMathExpression.java:1353`). Zero new findings.
  > - Final state: 0 open blockers, 0 open should-fix. All 202 tests in the
  >   5 iter-2-touched classes pass; Spotless clean.
  >
  > **Step file:** `tracks/track-8.md` (10 steps, 0 failed)
  >
  > **Strategy refresh:** CONTINUE — Track 8's discoveries (global-LET
  > stream-exhaustion pin TB15, four dead/semi-dead classes, test-strategy
  > precedents) are all already absorbed into Track 22 via commit
  > `7b9313eb4b`. No downstream impact on Tracks 9–21. Phase A of Track 9
  > should decide whether `SQLScriptEngine` / `SQLScriptEngineFactory`
  > (located in `core/sql/`) belong in Track 9's scope or stay deferred
  > to Track 22 — this is a decomposition-level call, not a plan change.

- [x] Track 9: Command & Script
  > Write tests for the command and script execution infrastructure.
  >
  > **Track episode:**
  > Landed comprehensive unit tests for the command and script subsystem
  > across 5 steps / 15 commits / 30 files / ~10,755 inserted lines —
  > purely test-additive, zero production-code changes. Step 4 split
  > into 4a (registries, 1,024 LOC) + 4b (executors + wrappers +
  > bindings, 1,889 LOC) per the anticipated fallback for commits
  > > ~1,500 test LOC. Step-level dimensional reviews ran at iter-1
  > per step (0 blockers overall for Steps 1–3; Step 4 had 2 blockers
  > both fixed in-step; Step 5 had 1 blocker fixed in-step). Track-level
  > Phase C ran 6 dimensional sub-agents (CQ/BC/TB/TC/TS/TX) to
  > iteration 2/3 and PASSED all dimensions: iter-1 surfaced 20
  > should-fix + ~25 suggestions — 13 should-fix fixes applied in
  > `f66b1bc474`; iter-2 gate check VERIFIED all 26 iter-1 items plus
  > 1 new should-fix (CQ5 FQN-leak residue) + 3 promoted suggestions
  > (CQ6 plan-absorption gap, CQ7 comment lag, TB8 reflection-fragility
  > marker), all fixed in `d2bc352a2f` + `68791bcf15`. Final coverage
  > gate: 100.0% line / 100.0% branch on changed production lines
  > (Step 5 verification run).
  >
  > Key discoveries with cross-track impact — all absorbed into
  > Track 22:
  >
  > (a) **~1,770 LOC of `core/command/script` is dead code** reachable
  > only through paths with no production callers (Phase A T1/R1):
  > `CommandExecutorScript` (719 LOC), `CommandScript.execute` stub,
  > `CommandManager`'s legacy class-based dispatch cluster,
  > `ScriptExecutorRegister` SPI, zero-impl `ScriptInterceptor` +
  > `ScriptInjection` register/unregister loops,
  > `ScriptManager.bind(...)` + `bindLegacyDatabaseAndUtil` +
  > `ScriptDocumentDatabaseWrapper` (261 LOC) + `ScriptYouTrackDbWrapper`
  > (42 LOC), `SQLScriptEngine.eval(Reader, Bindings)`. All pinned with
  > `// WHEN-FIXED: Track 22` markers.
  >
  > (b) **Production bugs pinned as WHEN-FIXED regressions** for
  > Track 22 hardening: `BasicCommandContext.copy()` null-child NPE
  > (T4 — zero callers, safest to delete); `executeFunction(unknown-name)`
  > NPE rather than named exception; `Traverse.hasNext`
  > abnormal-termination branch unreachable through normal flow;
  > `TraverseContext.pop` warn-branch only partially pinned (needs
  > LogManager appender capture); `PolyglotScriptBinding.clear()` CME
  > risk on GraalVM upgrade; `ScriptManager.throwErrorMessage`
  > malformed-Rhino NFE/SIOOBE + `"()"` anonymous-function leak;
  > `MapTransformer` registry asymmetry (in `transformers` but not
  > `resultSetTransformers`); Ruby formatter `skip("\r")` NSE on
  > missing CR; `SQLScriptEngine.eval(Reader, Bindings)`
  > `StringReader.ready()`-always-true infinite loop; polyglot Value
  > `asHostObject` CCE on JS primitive arrays.
  >
  > (c) **CHM race RISK-B refuted** (R5): `PolyglotScriptExecutor.
  > resolveContext` uses atomic `computeIfAbsent`. No stage test, no
  > production fix.
  >
  > (d) **DRY-cleanup items added to Track 22**: rollbackIfLeftOpen
  > hoist into `TestUtilsFixture` (CQ1); traverse-domain fixture
  > helpers across five Traverse*Test files into a package-private
  > `TraverseTestFixtures` helper (CQ2); `createStoredFunction`
  > helper across `Jsr223ScriptExecutorTest` /
  > `ScriptLegacyWrappersTest` / `SQLScriptEngineTest` into a
  > package-private helper in `command/script/` or test-commons (CQ3).
  >
  > (e) **Test-infrastructure precedent validated**:
  > `TestUtilsFixture` extension + `@After rollbackIfLeftOpen` safety
  > net carried forward from Tracks 5–8; polyglot-state hygiene pattern
  > (mutate-in-try / restore-in-finally + `@Category(SequentialTest)`
  > for GlobalConfiguration mutations) codified for future script-
  > execution tests; dead-code pins via dedicated `*DeadCodeTest`
  > classes with `// WHEN-FIXED: Track 22 — delete <class>` markers.
  >
  > No deviations affecting Tracks 10–21. Track 22 scope grew
  > substantially via three plan-update commits (`8ed372383d`,
  > `bc8164412c`, `68791bcf15`) — all absorptions are explicitly
  > recorded in the Track 22 section of this plan.
  >
  > **Step file:** `tracks/track-9.md` (5 steps, 0 failed — Step 4
  > split into 4a + 4b per anticipated fallback)
  >
  > **Strategy refresh:** CONTINUE — no downstream impact on Tracks 10–21.
  > All command/script discoveries (dead-code pins, production-bug
  > WHEN-FIXED markers, DRY-cleanup items, `TraverseTest.java` dead
  > locals) are already absorbed into Track 22's section of this plan.
  > Test-infrastructure precedents from Tracks 5–9 (`TestUtilsFixture` +
  > `@After rollbackIfLeftOpen`, polyglot-state hygiene, dead-code pin
  > pattern) continue to apply.

- [x] Track 10: Query & Fetch
  > Write tests for query infrastructure and fetch plan execution.
  >
  > **Track episode:**
  > Added ~4,800 LOC of test code across 12 new/extended files + 1
  > baseline doc covering `core/query`, `core/query/live`, `core/fetch`,
  > `core/fetch/remote`, and the `sql/fetch` callable surface. Purely
  > test-additive: zero production code modified. Track 10 confirmed
  > that **live-query and fetch subsystems are substantially dead code**
  > — cross-module grep found 0 callers in `server/`, `driver/`,
  > `embedded/`, `gremlin-annotations/`, `tests/` modules for
  > `FetchHelper`, `FetchPlan`, `FetchContext`, `FetchListener`, and the
  > entire `core/query/live/` public-static surface (the only live
  > surface is `LiveQueryHookV2.unboxRidbags`, called from
  > `CopyRecordContentBeforeUpdateStep.java:52`). This mirrors Track 9's
  > `CommandExecutorScript` situation and was reframed (per Phase A
  > iter-1) as dead-code pinning via `LiveQueryDeadCodeTest` /
  > `FetchHelperDeadCodeTest` + `// WHEN-FIXED: Track 22` markers —
  > rather than trying to drive live paths that no production code
  > reaches. Step 1 covered query defaults with a Turkish-locale
  > lowercasing pin driven by input characters (U+0130) to avoid a
  > `Locale.setDefault` race with surefire's `<parallel>classes</parallel>`
  > config. `DepthFetchPlanTest` was modernized to `TestUtilsFixture` +
  > `executeInTx` callbacks.
  >
  > Production bugs / known issues pinned for Track 22:
  > `LiveQueryHookV2.calculateProjections` always-returns-empty-or-null
  > (the consequence is that `calculateBefore`/`calculateAfter` load ALL
  > properties regardless of subscriber projection filters); V1 `break`
  > vs V2 `continue` divergent `InterruptedException` handling;
  > `ExecutionStep.java:41` duplicate `getSubSteps()` call whose return
  > value is discarded. Plus six deletion items absorbed: entire
  > `core/query/live/` package, three orphan listener interfaces in
  > `core/query/`, entire `core/fetch/` package, `DepthFetchPlanTest`
  > style modernization consistency, and `ExecutionStep.java:41`
  > duplicate-call cleanup.
  >
  > Track-level code review ran 2 iterations (6 dimensions
  > CQ/BC/TB/TC/TS/TX). Iter-1: 0 blockers / 20 should-fix / ~40
  > suggestions; 13 should-fix fixes applied across `a8c918b74b`
  > (live-query falsifiability + fixture hygiene) and `adc9ce95bb`
  > (fetch/query completeness). Iter-2 gate check PASSED on all 6
  > dimensions with all 13 iter-1 fixes VERIFIED; 3 recommended-tier
  > findings fixed in `3488e0db2e` (whitespace-pin name correction
  > `Accepts→Rejects`, symmetric `getDepthLevel(null, 0)` NPE pin,
  > rename vacuous `…OnClosedSessionIsNoOp→…DoesNotThrow` and drop the
  > vacuous `pendingOps.size()` preservation assertion). Zero open
  > blockers / zero open should-fix at track end. ~25 suggestion-grade
  > items deferred — several fold into Track 22's DRY sweep.
  >
  > Test count: 273 tests across the 12 new/extended classes, all
  > passing. Spotless clean. Coverage gate: 100.0% line / 100.0% branch
  > on changed production lines (trivially, since Track 10 is purely
  > test-additive).
  >
  > No plan corrections to Tracks 11–21. Track 22's queue expanded by
  > ~6 deletion items + ~2 production-fix markers + ~10 DRY/cleanup
  > items, all cataloged in the Track 22 section of this plan (entries
  > already landed in prior commits).
  >
  > **Step file:** `tracks/track-10.md` (4 steps, 0 failed)
  >
  > **Strategy refresh:** CONTINUE — no downstream impact on Tracks 11–21.
  > All Track 10 discoveries (live-query / fetch dead-code reframe, V1/V2
  > `InterruptedException` divergence, `LiveQueryHookV2.calculateProjections`
  > always-empty bug, `ExecutionStep.java:41` duplicate `getSubSteps()`,
  > `DepthFetchPlanTest` modernization) are already cataloged in the
  > Track 22 section of this plan. Test-infrastructure precedents
  > (`TestUtilsFixture` + `executeInTx` callbacks, `@After
  > rollbackIfLeftOpen`, `@Category(SequentialTest)` for global-state
  > mutations, dead-code pinning via `*DeadCodeTest` + WHEN-FIXED markers)
  > continue to apply.

- [x] Track 11: Scheduler
  > Write tests for the task scheduler subsystem.
  >
  > **Track episode:**
  > Added ~3,375 LOC of test code across 8 new files + 1 shared fixture
  > covering `core/schedule` (CronExpression, ScheduledEvent / Builder,
  > SchedulerImpl, SchedulerProxy). Purely test-additive: zero production
  > code modified. Aggregate package coverage rose from baseline 45.7%
  > line / n/a branch to **86.4% line / 75.1% branch** — exceeds the
  > project-wide 85% line / 70% branch target. All R6-style per-file
  > acceptances (drafted in Phase A as `~75% line / ~60% branch` for
  > `SchedulerImpl` and `~75% line / ~55–60% branch` for `ScheduledEvent`)
  > were materially exceeded — actual outer-class coverage 97.4% / 88.9%
  > and 98.1% / 75.0%. The residual gap concentrates in
  > `ScheduledEvent$ScheduledTimerTask` (60.0% / 55.0%) where the
  > retry-loop catch branches and run-time interrupt race are
  > out-of-scope-by-design.
  >
  > Production bugs / known issues pinned for the deferred-cleanup track
  > via falsifiable regression tests with WHEN-FIXED markers: (a)
  > `ScheduledEvent` ctor silently swallows `ParseException` and leaves
  > `cron == null` (paired with the cron-field unsafe-publication
  > finding — `cron` is non-final / non-volatile while reads are
  > timer-locked); (b) `executeEventFunction` retry-loop bug where the
  > 10× loop runs unconditionally because `catch NeedRetryException` is
  > mis-scoped inside the lambda; (c) `SchedulerImpl.onEventDropped` NPE
  > when the dropped-events custom-data map was never populated; (d)
  > `CronExpression` DOM-field parser leniency (e.g., `"0 0 12 5X * ?"`
  > silently dropped trailing `X`). Plus deletion items absorbed:
  > `CronExpression` lazy `TimeZone.getDefault()` fallback in
  > `getTimeZone()` (refined from track plan's broader scope — the
  > `setTimeZone(TimeZone)` setter itself stays live), deprecated
  > `Scheduler.{load, close, create}` interface methods + their three
  > `SchedulerProxy` overrides, and a residual-gap entry covering the
  > two log-and-swallow `catch (Exception)` paths in `SchedulerImpl`
  > plus the interrupt-during-run race (recorded as out-of-scope-by-design
  > rather than as deletion candidates).
  >
  > Track-level code review ran 2 iterations (6 dimensions:
  > CQ/BC/TB/TC/TS/TX). Iter-1: 0 blockers / 3 should-fix (missing
  > SchedulerProxy live-method delegation tests, null-PROP_STATUS
  > branch, getEvents live-mutation observability) / ~17 suggestions;
  > TX returned PASS. Iter-1 fix commit `59520943a7` addressed all 3
  > should-fix items + the higher-value suggestions (DAY_OF_WEEK
  > overflow remap pin, isSatisfiedBy null-time-after pin,
  > all-builder-setters-accept-null parameterized pin, builder
  > reuse-after-build invariant, onEventDropped null-map NPE pin, etc.)
  > and added the new `SchedulerProxyTest` covering live-method
  > delegation. Iter-2 gate-check PASSED on all 5 dimensions
  > (CQ/BC/TB/TC/TS — TX needed no re-run): all iter-1 fixes VERIFIED
  > (or REJECTED where the stronger iter-1 fix made the original
  > suggestion moot), zero open blockers, zero open should-fix, ~17
  > new suggestion-tier findings. Three high-leverage suggestions fixed
  > in `634a8a5a83`: replaced `firstView instanceof ConcurrentHashMap`
  > with `assertEquals(ConcurrentHashMap.class, firstView.getClass())`
  > (strictly more falsifiable — catches subclass-wrapper regressions),
  > replaced 4× vacuous `assertNotNull(<builder-returned ref>)` with
  > load-bearing `assertNotSame(<builder-returned>, <registered>)`
  > assertions pinning the dual-instance invariant, and replaced the
  > inline FQN `EntityImpl` reference in `SchedulerProxyTest` with a
  > regular import.
  >
  > Test count: **161 tests** across 8 new test files (78
  > CronExpression + 16 CronExpressionDeadCode + 10
  > ScheduledEventBuilder + 11 ScheduledEvent + 24 SchedulerImpl + 11
  > SchedulerProxy + 5 SchedulerSurfaceDeadCode + 6 pre-existing
  > SchedulerTest end-to-end) plus the shared `SchedulerTestFixtures`
  > package-private helper. Spotless clean. Coverage gate: 100.0%
  > line / 100.0% branch on changed production lines (trivially, since
  > the track is purely test-additive).
  >
  > No plan corrections to Tracks 12–21. The deferred-cleanup track's
  > existing scheduler-absorption block (committed in `d7395358fc`)
  > already captures the substantive deletion items, production-bug
  > fixes, and out-of-scope-by-design entries. ~14 iter-2 suggestion-
  > tier items (interrupt-with-null-timer branch, tab-separator parse,
  > DST spring-forward test, direct `SchedulerImpl.{create, load}`
  > pins needed once proxy deprecated methods are deleted, plus
  > DRY/cohesion sweep candidates) are recorded in the step-file
  > Iter-2 summary and may be picked up at the deferred-cleanup
  > track's discretion.
  >
  > **Step file:** `tracks/track-11.md` (4 steps, 0 failed)
  >
  > **Strategy refresh:** CONTINUE — no downstream impact on Tracks 12–21.
  > All Track 11 discoveries (scheduler dead code, WHEN-FIXED bug pins,
  > residual-gap acceptances) are scheduler-internal and already cataloged
  > in the Track 22 section of this plan. Test-infrastructure precedents
  > from Tracks 5–11 (`TestUtilsFixture` + `@After rollbackIfLeftOpen`,
  > falsifiable-regression + WHEN-FIXED marker convention, dead-code pins
  > via `*DeadCodeTest` classes, `@Category(SequentialTest)` for static-
  > state mutations, `// forwards-to: Track NN` cross-track bug-pin
  > convention, `Iterable` detach-after-commit pattern) continue to apply.
  > `common/serialization` (146 uncov, 34.5%) is owned by Track 12 per
  > Track 3's explicit deferral — no boundary conflict. Track 8's D2
  > override (DbTestBase-by-default for executor-step tests) is per-track
  > and does not propagate to Track 12; default D2 (standalone over
  > DbTestBase) applies for serialization round-trips except where link/
  > embedded resolution genuinely needs a session.

- [x] Track 12: Serialization — String & Core
  > Write tests for the string record serializer and core serialization
  > infrastructure. The string serializer has very low coverage (30.9%)
  > and is a legacy format.
  >
  > **Track episode:**
  > Added ~8,400 LOC of test code across 24 new/modified test files
  > covering the serialization stack: byte-converters
  > (`SafeBinaryConverter`, `UnsafeBinaryConverter`,
  > `BinaryConverterFactory`), root-level helpers (`BinaryProtocol`,
  > `MemoryStream`, `StreamableHelper` / `StreamableInterface` dead-code
  > surface, `SerializationThreadLocal` dead-code surface), serializer
  > infrastructure (`JSONReader`, `JSONWriter` dead-code surface,
  > `RecordSerializer` interface, `StringSerializerHelper`,
  > `StreamSerializerRID`), the JSON Jackson serializer (3 mode-instance
  > round-trip suites: default + import-instance + import-backwards-compat),
  > the legacy CSV string serializer (dead-code pins + simple-value /
  > embedded-map / static-helper coverage), and `FieldTypesString`. Eight
  > step commits (Step 4 split into 4a + 4b at decomposition time after
  > the `JSONSerializerJackson` test class crossed the 1500-LOC sizing
  > band) plus four iter-1 review-fix commits, one iter-2 review-fix
  > commit, and one plan-update commit absorbing the deferred-cleanup
  > queue — 13 commits total. Purely test-additive: **zero production
  > code modified across all 13 commits.**
  >
  > Coverage outcome (post-Step-6 vs. pre-track baseline):
  > `core/serialization/serializer/record/string` 30.9% → **62.8% line /
  > 58.3% branch**; `core/serialization/serializer` 41.4% → **66.3% /
  > 59.8%**; `core/serialization` (root) 14.2% → **75.9% / 71.8%**;
  > `core/serialization/serializer/record` 0.0% → **78.6%** (no branches);
  > `core/serialization/serializer/stream` 60.9% → **82.6% / 100.0%**;
  > `common/serialization` 82.1% → **83.4% / 62.9%** (corrected
  > post-Step-1 baseline — see below). Aggregate package targets (85%
  > line / 70% branch) are met for the **live subset** of every targeted
  > package; the residual on the three string-serializer packages traces
  > to the legacy `RecordSerializerCSVAbstract` instance API (402 lines,
  > 10.4% covered, dead) and the `JSONSerializerJackson`
  > `IMPORT_BACKWARDS_COMPAT_INSTANCE` legacy 1.x export branches
  > (~5pp residual; matches Phase A's "≤ ~5pp" forecast). Deletion of
  > the dead surface raises `record/string` aggregate to ~83.0% on the
  > same numerator. Coverage gate: **PASSED** — 100.0% line (6/6) /
  > 100.0% branch (2/2) on changed production lines.
  >
  > Step 1 surprise — pre-existing **inert converter tests**: the three
  > `*ConverterTest` files in `common/serialization` had eight `testPut*`
  > methods on the abstract base + eight overrides each on the two
  > subclasses (16 newly-active tests after repair), all of which carried
  > *no* `@Test` annotation, so JUnit 4 silently never ran any of them.
  > Bodies also called `Assert.assertEquals(byte[], byte[])` (resolving
  > to the `Object` overload — reference identity) and used wrong scalar
  > argument order. Step 1 repaired the three files and re-measured: the
  > `common/serialization` baseline jumped from the inflated **34.5% line
  > / 27.1% branch** the original Track 12 plan cited to **82.1% / 61.4%**
  > — the corrected baseline against which subsequent step targets were
  > measured. Iter-1 review fix `4ce8111501` refactored the inert-test
  > surface into the codebase-idiomatic helper-method + subclass `@Test`
  > shape (precedent: `AbstractComparatorTest`).
  >
  > Production bugs / known issues: **none** found. The serialization
  > stack under test has stable, well-established surface. Dead-code
  > surface is *pinned* (not deleted) via `*DeadCodeTest` classes that
  > lock in structural shape (modifiers, signatures, dispatcher tables)
  > so a future refactor either updates the pin in lockstep or fails
  > loudly. Five dead-code deletion items absorbed into the
  > deferred-cleanup track: (a) `RecordSerializerCSVAbstract` instance
  > API, (b) `RecordSerializerStringAbstract` abstract instance API +
  > four unused statics, (c) `JSONWriter`, (d) `Streamable` interface +
  > `StreamableHelper`, (e) `SerializationThreadLocal` listener path
  > (`$1` synthetic inner class). Six residual-coverage gaps forwarded
  > with explicit deferred-cleanup-track rationale: (f) JSON Jackson
  > legacy 1.x export branches, (g) `StringSerializerHelper` parser-token
  > branches, (h) `MemoryStream` record-id paths (re-measured after
  > Tracks 14–15 migrate `RecordId*` / `RecordBytes` callers off the
  > `@Deprecated` class), (i) `UnsafeBinaryConverter` platform-detection
  > cold path, (j) `StreamSerializerRID` deprecated two-arg ctor +
  > wrapper.
  >
  > Track-level code review ran **2 iterations** (7 dimensions:
  > CQ / BC / TB / TC / SE / TS / TX). Iter-1: 0 blockers /
  > ~25 should-fix / ~20 suggestions; fix commit `58dd5bda3d` (8 test
  > files, +270 / -23) addressed all should-fix items via four buckets —
  > test-correctness (`assertEquals → assertSame` for reference identity,
  > drop tautological `assertSame`, split combined boolean), test-isolation
  > (`@After SerializationThreadLocal.INSTANCE.remove()` against surefire
  > worker reuse), diagnostic precision (cause-chain walking on three
  > Jackson rejection tests via `chainMessagesOf`), and boundary
  > completeness (8 boundary pins on MemoryStream / BinaryProtocol /
  > JSONReader unicode-escape edge cases). Iter-2 gate-check: **PASSED**
  > all 7 dimensions; one new should-fix raised — TC21, empty
  > typed-collection JSON round-trip path uncovered. Fix commit
  > `8aa6b4e40f` adds 6 round-trip tests covering the empty-loop branches
  > in `parseLinkList` / `parseLinkSet` / `parseLinkMap` /
  > `parseEmbeddedList` / `parseEmbeddedSet` / `parseEmbeddedMap`
  > (`JSONSerializerJacksonInstanceRoundTripTest` total: 53, was 47).
  > All deferred suggestions across both iterations (CQ / TB / TC / SE /
  > TS / TX) catalogued in the iter-1 / iter-2 step-file sections; the
  > high-leverage structural items (DRY JSON-test base class, security
  > commentary on `Streamable` + `IMPORT_BACKWARDS_COMPAT` permissive
  > flags, `streamableClassLoader` save/restore) are forwarded to the
  > deferred-cleanup track.
  >
  > No plan corrections to subsequent tracks from iter-2. The
  > deferred-cleanup track's existing absorption block (committed in
  > `a6301e4fdb`) already captures all dead-code deletion items, residual
  > coverage gaps with forwarding rationale, and the inert-converter-test
  > repair recorded for traceability. Iter-2's new deferred suggestions
  > (~12 items spanning code-quality cosmetics, test-behavior pin
  > tightening, additional completeness pins, defense-in-depth security
  > pins, and test-structure cleanups) extend the same deferral queue
  > and may be picked up at the deferred-cleanup track's discretion.
  >
  > Test count: **~480 new tests** across 24 new/modified test files
  > plus the 16 newly-active converter tests Step 1 repaired. Spotless
  > clean. Coverage gate: 100.0% line / 100.0% branch on changed
  > production lines (trivially, since the track is purely test-additive).
  >
  > Cross-track impact: **A6 / A9 deferred to Track 13 strategy refresh**
  > — the binary serializer's record-type dispatching may overlap with
  > the deferred-cleanup absorptions; Track 13 will assess at strategy
  > refresh time whether any string-serializer dead-code pinning shape
  > precedes binary-serializer test design. All other Track 12
  > discoveries (corrected baseline, helper-method test refactor pattern,
  > `*DeadCodeTest` shape pinning, falsifiable-regression + WHEN-FIXED
  > marker convention from prior tracks) localize to Track 12 + the
  > deferred-cleanup track.
  >
  > **Step file:** `tracks/track-12.md` (8 steps, 0 failed; Step 4 split into 4a + 4b — both done)
  >
  > **Strategy refresh:** CONTINUE — all Track 12 discoveries either
  > localize to string/core serialization, are already absorbed into
  > Track 22's deferred-cleanup queue, or are test patterns to carry
  > forward. The explicit A6/A9 Track 13 hand-off resolves cleanly:
  > Track 12's `RecordSerializerInterfaceTest` (stub-implementor UOE
  > pin) and `*DeadCodeTest` shape pins (CSV / String-abstract /
  > JSONWriter / Streamable / SerializationThreadLocal) are disjoint
  > from Track 13's binary scope (`RecordSerializerBinary`,
  > `RecordSerializerBinaryV1`, `RecordSerializerNetwork`,
  > `BinarySerializerFactory`); Track 13 adds behavioral round-trip
  > coverage and references the interface test for contract-level
  > pinning rather than duplicating it. **Corrected baseline note:**
  > `common/serialization` rose from the originally-cited 34.5%/27.1%
  > to **83.4%/62.9%** post-Track-12 after Step 1's inert-test repair —
  > Track 13 Phase A must remeasure live coverage of `common/
  > serialization/types` against the post-Track-12 baseline rather
  > than original plan numbers. Carry forward to Track 13:
  > `*DeadCodeTest` shape-pin convention, helper-method + per-subclass
  > `@Test` refactor (`AbstractComparatorTest` precedent), `@After`
  > thread-local/static-state cleanup hygiene, falsifiable-regression
  > + WHEN-FIXED-marker convention, boundary completeness pinning
  > (unicode / empty collections / negative offsets), and `// forwards-
  > to: Track NN` cross-track bug-pin convention.

- [x] Track 13: Serialization — Binary
  > Write tests for the binary record serializer. Binary serialization
  > already has decent coverage (74.8%) but a large absolute gap (850
  > uncov) due to the codebase size.
  >
  > **Track episode:**
  > Added ~9,871 LOC of test code across 25 new test files covering the
  > binary-serializer stack: V1 simple-type and collection round-trips
  > with paired byte-shape pins, EntitySerializerDelta round-trips with
  > wire-format markers, BinaryComparatorV0 cross-type and DATE paths,
  > two index serializers (CompositeKey + IndexMultiValuKey) with
  > WAL-overlay coverage, UUID/Null dispatcher contracts, and binary-
  > serializer dead-code shape pins (`SerializableWrapper`,
  > `RecordSerializationDebug`, `RecordSerializationDebugProperty`,
  > `MockSerializer`). Two new shared fixtures extracted at iter-2:
  > `BinaryComparatorV0TestFixture.field()` and
  > `RecordSerializerBinaryTestFixture.runInTx()`. Purely test-additive
  > across all 17 commits — zero production code modified.
  >
  > Production bugs / latent issues pinned with WHEN-FIXED markers
  > (forwarded to the deferred-cleanup track): `BytesContainer` zero-
  > capacity infinite-loop hang via the byte-array constructor;
  > `SerializableWrapper.fromStream` security gap (no
  > `ObjectInputFilter`, no class allow-list, no length cap on
  > `ObjectInputStream.readObject()`); asymmetric version-byte handling
  > in `RecordSerializerBinary.fromStream(byte[])` (unguarded
  > `serializerByVersion[iSource[0]]` AIOOBE + Base64-of-input WARN-log
  > path that amplifies log-injection); `BinarySerializerFactory.create()`
  > registers a fresh `new NullSerializer()` rather than the singleton;
  > `MockSerializer.preprocess` returns null instead of input;
  > `RecordSerializationDebug*` carries `faildToRead` typo; cluster-id
  > `(short)` cast in LinkSerializer / CompactedLinkSerializer is
  > unreachable through public API but the silent truncation would
  > surface if the upstream `RecordId.checkCollectionLimits` guard
  > relaxed.
  >
  > Dead-code surface pinned for deletion (4 classes via `*DeadCodeTest`
  > shape pins): `SerializableWrapper`, `RecordSerializationDebug`,
  > `RecordSerializationDebugProperty`, `MockSerializer` (sentinel —
  > needs lockstep removal of the `BinarySerializerFactory` registration
  > for `PropertyTypeInternal.EMBEDDED` id `-10`).
  >
  > Track-level code review: 3 iterations (max reached). Iter-1: 0
  > blockers / 31 should-fix / 33 suggestions across CQ/BC/TB/TC/SE/TS,
  > fix sweep `dad3e0764c`. Iter-2: deferred-group fix sweep
  > `ce8be16633` covering G5 (cluster-id boundary reframed as
  > constructor-rejection + max-cluster round-trip), G6 / G7 (shared
  > fixtures), G8 (split bundled `allObjectSize*` / `allDeserialize*`
  > into 11 per-overload `@Test` methods), G9 (Mockito
  > `preprocessReturnsNullEvenForNonNullInput` falsifiability pin), G12
  > (VarInt 9-byte explicit decoded-value), G14 (empty-delta + dry-run
  > null-target), G16 (V1 / Delta SECURITY javadoc), G17
  > (`registerSerializer(null)` NPE), G18 (`Integer.MIN/MAX_VALUE` 5-byte
  > canonical-length). Iter-3 gate-check: all 5 spawned dimensions
  > (CQ/TB/TC/SE/TS) PASS, 6 new suggestions, cosmetic sweep
  > `baf9284ab4` applied 3 trivial mechanical fixes (FQN imports +
  > `assertNull`); 3 design-level suggestions (Javadoc shape, LinkBag
  > middle-byte change-tracker pin gap, CompactedLinkSerializer
  > WAL-overlay max-cluster pin gap) forwarded to the deferred-cleanup
  > track absorption block (entries cc / dd / ee in the backlog).
  >
  > Cross-track impact: minor. All production-bug pins, dead-code shape
  > pins, DRY/refactor candidates (`runInTx` helper, `field()` helper,
  > `assertCanonicalBytes` helper, sibling `*SerializerTest` extension),
  > and residual coverage gaps (B-tree-backed LinkBag/LinkSet write
  > paths, `EntitySerializerDelta` dry-run path, `CompositeKeySerializer`
  > Map-flatten preprocess negative branches) are absorbed into the
  > deferred-cleanup track section of the backlog. Test-infrastructure
  > precedents carried forward and extended with two new shared-fixture
  > extractions for later serialization tracks.
  >
  > **Step file:** `tracks/track-13.md` (7 steps, 0 failed)
  >
  > **Strategy refresh:** CONTINUE — no downstream impact on Tracks 14–21.
  > Track 13's binary-serializer scope is disjoint from Track 14's `core/db`
  > scope. All production-bug pins, dead-code shape pins, and DRY/refactor
  > candidates are already absorbed into the deferred-cleanup track section
  > of the backlog. Test-infrastructure precedents from Tracks 5–13
  > (`*DeadCodeTest` shape pinning, falsifiable-regression + WHEN-FIXED
  > convention, `@After rollbackIfLeftOpen`, shared-fixture extraction at
  > iter-2, `@Category(SequentialTest)` for static-state mutations,
  > `Iterable` detach-after-commit, `// forwards-to: Track NN` cross-track
  > bug pin) continue to apply. Track 14 leans on `DbTestBase` heavily
  > (per-track decomposition call, not a D2 change).

- [x] Track 14: DB Core & Config
  > Write tests for the core database package — database lifecycle,
  > configuration, and record management.
  >
  > **Track episode:**
  > Added ~8,903 LOC of test code across 30 new/extended test files
  > covering `core/db`, `core/db/config`, `core/db/record`,
  > `core/db/record/record`, and `core/db/record/ridbag`. Purely
  > test-additive: zero production code modified across all 9 commits.
  > Final aggregate coverage on the touched packages: `core/db` 71.6%/57.1%
  > (+4.8pp/+4.5pp; falls 3.4pp/4.9pp short of the Step 1 acceptance band
  > with the residual concentrated in `DatabaseSessionEmbedded`'s 636
  > remaining uncov lines, out-of-scope-by-design per the Step 5
  > coverage-gate framing for the 4 618-LOC class); `core/db/config` 0% →
  > **95.4%/100.0%** (the dead-code shape pin drove every public-method
  > branch); `core/db/record` 72.6% → **92.0%/80.0%**; `core/db/record/record`
  > 58.4% → **89.2%/76.4%**; `core/db/record/ridbag` 84.0% → 87.3%/78.3%
  > (B-tree conversion paths require storage-IT-level fixtures, forwarded).
  > Aggregate `core` module: 75.1%/65.8% → **75.9%/66.4%** (+0.8pp/+0.6pp);
  > Phase 1 cumulative through Track 14: +12.3pp line / +13.1pp branch.
  >
  > **Reframe at Phase A**: three independent reviews (technical, risk,
  > adversarial — all PSI-grounded) converged on two blockers and matching
  > should-fix items. The original "drive `db/config` builder round-trips"
  > framing was reframed to dead-code pins + Track 22 deletion absorption
  > after PSI all-scope `ReferencesSearch` confirmed every public class in
  > `core/db/config` (5 dead public classes + 3 dead Builders) has zero
  > production callers across all 5 modules; the same applied to
  > `DatabasePoolBase`, `RecordMultiValueHelper`, `HookReplacedRecordThreadLocal`,
  > `DatabaseLifecycleListenerAbstract`, `LiveQueryBatchResultListener`, and
  > the `EntityHookAbstract`/`RecordHookAbstract` test-only-reachable pair.
  > No code fixes were needed for the Phase A blockers — the reframes
  > absorbed directly into the step file's Description and into the Step
  > decomposition.
  >
  > **Production bugs pinned with WHEN-FIXED markers** (forwarded to
  > Track 22): `LRUCache.removeEldestEntry` off-by-one (`>=` instead of
  > `>` caps `StringCache` at `cacheSize-1`); `DatabaseSessionEmbedded.
  > setCustom(name, null)` latent NPE for any non-clear name (line
  > 552–561 short-circuit chain); misleading TIMEZONE backward-compat
  > comment plus the lowercase-input fallback to GMT; `setCustom`
  > `"" + iValue` stringification bug for `char[]`; `SystemDatabase`
  > latent shape where `openSystemDatabaseSession()` skips `init()`
  > when the DB exists, leaving `serverId` null for callers expecting
  > it populated; `CommandTimeoutChecker.startCommand(Long.MAX_VALUE)`
  > deadline-addition overflow; `setParent`'s child-side null branches
  > in `YouTrackDBConfigImpl`. Each pinned via falsifiable observed-shape
  > regression so a production-side fix naturally breaks the pin.
  >
  > **Dead-code surface pinned for Track 22 deletion** (10 classes via
  > `*DeadCodeTest` shape pins): entire `core/db/config` package
  > (`MulticastConfguration`, `NodeConfiguration`,
  > `UDPUnicastConfiguration` + their three Builders), `DatabasePoolBase`,
  > `DatabasePoolAbstract` (1 dead subclass + 1 test subclass),
  > `RecordMultiValueHelper`, `HookReplacedRecordThreadLocal`,
  > `DatabaseLifecycleListenerAbstract`, `LiveQueryBatchResultListener`,
  > `EntityHookAbstract`/`RecordHookAbstract` (test-only-reachable —
  > deletion contingent on retargeting test subclasses at `RecordHook`
  > directly).
  >
  > **Track-level code review**: 3 iterations, 6 dimensions
  > (CQ/BC/TB/TC/TS/TX). Iter-1 surfaced 5 plan-level blockers (all
  > absorbed as Description reframes — no code fixes) plus 12 should-fix
  > items, fix commit `beb12a22d1`. Iter-2 gate check FAILed CQ/TC/TS/TX
  > with mechanical sweeps the iter-1 fix missed (spawn-helper rollout,
  > 5 boundary tests, defensive @Before assumeNotNull); applied in fix
  > commit `587dfae4e6` (11 files, +241/-44). Iter-3 gate-check **PASSED
  > all 6 dimensions**: 14/14 cumulative iter-1/iter-2 items VERIFIED;
  > 6 new suggestion-tier findings (CQ20/CQ21/TB20/TB21/TC18/TX9)
  > deferred to Track 22 backlog absorption block. Final state: 0 open
  > blockers, 0 open should-fix.
  >
  > **Cross-track impact**: minor. No Component Map or Decision Record
  > changes. Track 22's deferred-cleanup absorption block grew by ~10
  > production-fix WHEN-FIXED markers + ~10 dead-code deletion items +
  > 12 iter-2/iter-3 suggestion-tier entries (TS12-14, TC15-17, TX9,
  > BC12-13, CQ20-21, TB20-21). No propagation to Tracks 15-21.
  >
  > **Patterns carried forward and codified**: corrected-baseline rule
  > (Step 1 always re-measures live coverage rather than trusting
  > plan-cited figures — Track 12 lesson); `*DeadCodeTest` shape-pin
  > convention for classes pending deletion; `@Category(SequentialTest)`
  > for static-state mutations (`SystemDatabase`, `ExecutionThreadLocal`,
  > `HookReplacedRecordThreadLocal`); tracked-`spawn()` helper for
  > worker-thread tests with `@After` join discipline (formalised in
  > iter-2 fix); defensive `@Before Assume.assumeNotNull` for static
  > volatile dispatchers vulnerable to engine-shutdown races; reflective
  > field-stays-null pin pattern for dead-decoration `assertNotNull(probe)`
  > replacement; observed-shape `Map.of(...).toString()` /
  > `List.of(...).toString()` exact-equality for `toString()` contract
  > pins (replaces vacuous `contains("k")` patterns).
  >
  > **Step file:** `tracks/track-14.md` (6 steps, 0 failed)
  >
  > **Strategy refresh:** CONTINUE — Track 14's discoveries are confined to
  > the touched packages or queued in Track 22's deferred-cleanup block. No
  > propagation to Tracks 15–21; no Component Map or Decision Record
  > changes; carry-forward patterns are already absorbed into Track 15's
  > "Carry forward Tracks 5–14 conventions" instruction.

- [x] Track 15: Record Implementation & DB Tool
  > Write tests for the record implementation layer and database tool
  > utilities. EntityImpl is the core document model; DB tools handle
  > export, import, repair, and compare.
  >
  > **Track episode:**
  > Added ~9,700 LOC of test code across 42 new/extended files covering
  > `core/db/tool*` and `core/record*`. Purely test-additive: zero
  > production code modified across all 6 step commits + 2 review-fix
  > commits. Final aggregate coverage on the touched packages
  > (post-iter-3): live-drive surfaces meet the 85% line / 70% branch
  > bar; dead-code residues (530 of 889 `core/db/tool` uncov lines)
  > are pinned via `*DeadCodeTest` shape pins for lockstep deletion in
  > the deferred-cleanup track. Aggregate `core` module coverage drift
  > is +0.3pp / +0.3pp; this track's value lives in the cross-track
  > dead-code reframe (8 deletion lockstep groups) more than in raw
  > coverage.
  >
  > **Reframe at Phase A**: three independent reviews
  > (technical / risk / adversarial — all PSI-grounded) converged on
  > two blockers and matching should-fix items. The original "drive
  > EntityImpl + DB-tool round-trip" framing was reframed to dead-code
  > pins + Track 22 deletion absorption after PSI all-scope
  > `ReferencesSearch` confirmed every `core/db/tool` orphan
  > (`DatabaseRepair`, `BonsaiTreeRepair`) plus the test-only-reachable
  > trio (`DatabaseCompare`, `GraphRepair`, `CheckIndexTool`) and the
  > `core/record*` chain-dead helpers (`RecordVersionHelper`,
  > `RecordStringable`, `RecordListener`, 12 of 17 `EntityHelper`
  > public methods, `EntityComparator`). Track 12's "MemoryStream
  > caller migration" framing was unimplementable — closed via
  > deletion (forwarded to Track 22) instead.
  >
  > **Step 4 incident and recovery**: First Step 4 attempt escalated
  > `DESIGN_DECISION_NEEDED` after PSI re-confirmation contradicted
  > Phase A's `RecordBytes.fromInputStream` dead claim — the 1-arg
  > overload has a live caller at `JSONSerializerJackson:623`. The
  > implementer's prescribed revert sequence ran `git clean -fd`,
  > wiping 50+ untracked workflow files. Recovery used IntelliJ Local
  > History + agent transcripts; PR #1022 on `develop` fixed the
  > rulebook (forbid `ScheduleWakeup` + replace `git clean -fd` with
  > snapshot-and-diff). Step 4 resumed via Alternative B: pin only the
  > 2-arg `fromInputStream(InputStream, int)` overload as
  > test-only-reachable in `RecordBytesTestOnlyOverloadTest` (NOT
  > `*DeadCodeTest` — the 1-arg overload is live).
  >
  > **Production bugs pinned for the deferred-cleanup track (forwarded
  > via WHEN-FIXED markers)**: `OPPOSITE_LINK_CONTAINER_PREFIX`
  > should-be-final (0 writes per PSI). The MemoryStream-scratch-buffer
  > body of `RecordBytes.fromInputStream(InputStream)` is queued for
  > rewrite to `ByteArrayOutputStream` to sever the dependency.
  >
  > **Dead-code surface pinned for deferred-cleanup-track deletion**
  > (8 lockstep groups): `core/db/tool` orphans, `core/db/tool`
  > test-only-reachable trio (deletion contingent on retargeting test
  > subclasses), `core/record` chain-dead helpers, 12 dead
  > `EntityHelper` public methods (each pinned individually so partial
  > deletion stays valid), `EntityComparator` (chain-dead AND
  > test-only-reachable from one `tests/CRUDDocumentValidationTest`
  > sort-stability assertion), `EntityImpl.hasSameContentOf(EntityImpl)`
  > lockstep, `RecordBytes.fromInputStream(InputStream, int)` 2-arg
  > overload, `RecordBytes.fromInputStream(InputStream)` body
  > MemoryStream-scratch-buffer rewrite. The earlier "RecordBytes
  > `fromInputStream` + `toStream(MemoryStream)` overload deletions"
  > line item was RETRACTED — `toStream(MemoryStream)` does not exist
  > on `RecordBytes`. `EntityHelper.RIDMapper` is no longer chain-dead
  > after iter-1 introduced a live caller from
  > `DatabaseExportImportRoundTripTest`'s round-trip harness.
  >
  > **Track-level code review (3 iterations, max reached; final
  > PASS):**
  > - Iter-1 (5 dimensions: CQ/BC/TB/TC/TS): 3 blockers / 27
  >   should-fix / 24 suggestions. Applied 3 blockers + 4 should-fix
  >   in commit `fb5881c66a`. The fix commit silently skipped TS1,
  >   TC1, and CQ11 (the latter being a sweep miss on hyphenated
  >   `Step-1`/`Step-2`, `Pre-Track-22`, and `Phase A` variants the
  >   original regex did not match).
  > - Iter-2 (5-dimension gate check): BC/TB PASS; CQ/TC/TS FAIL on
  >   the 3 silently-skipped iter-1 items. Applied in commit
  >   `bd95f88906`: TS1 (try/finally drop in
  >   `EntityImplTest#testRemovingReadonlyField` + `#testUndo`), TC1
  >   (`testVisitFieldOnNullValueReturnsNull` in `LinksRewriterTest`),
  >   CQ11 (sweep 7 ephemeral-identifier residues across 4 files).
  >   TC2 was REJECTED — production `LinkTrackedMultiValue.checkValue`
  >   rejects null at write time, making the abstract-base null arm
  >   structurally unreachable through the four Link converters. New
  >   iter-2 suggestions deferred: CQ12 (231-line round-trip method
  >   extraction), TC13 (Embedded Set/Map null-element symmetry).
  > - Iter-3 (CQ/TC/TS gate check): PASS on all 3 dimensions.
  >   CQ11/TS1/TC1 fixes VERIFIED. TC2 rejection stands with caveat
  >   about `EntityLinkSetImpl` rejecting via NPE rather than
  >   `checkValue`. New iter-3 suggestion TC14 (`EntityLinkSetImpl`
  >   null-NPE pin) deferred.
  > - Final state: 0 open blockers, 0 open should-fix; 3 deferred
  >   suggestions (CQ12, TC13, TC14) absorbed into the deferred-
  >   cleanup-track backlog block via commit `c23770e79a`.
  >
  > **Cross-track impact**: substantial. Track 22's deferred-cleanup
  > absorption block grew by 8 dead-code deletion lockstep groups + 1
  > production-fix WHEN-FIXED marker
  > (`OPPOSITE_LINK_CONTAINER_PREFIX`) + 3 iter-2/iter-3
  > suggestion-tier entries (CQ12, TC13, TC14). The earlier
  > "RecordBytes `toStream(MemoryStream)` overload deletion" line was
  > RETRACTED. The `EntityHelper.RIDMapper` chain-dead claim was
  > retracted after the iter-1 round-trip harness introduced a live
  > caller. No propagation to Tracks 16–21. No Component Map or
  > Decision Record changes.
  >
  > **Patterns carried forward and codified**: dead-code reframe via
  > all-scope PSI `ReferencesSearch` before driving "live coverage";
  > per-method dead-code pinning so partial deletion stays valid;
  > `*DeadCodeTest` vs `*TestOnlyOverloadTest` naming distinction (the
  > latter when the surrounding class has live overloads); name-keyed
  > `RIDMapper`-backed round-trip fidelity assertion (replaces
  > size-only link checks in `DatabaseExportImportRoundTripTest`);
  > broader ephemeral-identifier sweep regex
  > (`Track[ -]?[0-9]+|Step[ -]?[0-9]+|\bPhase [A-Z]\b`) for iter-2
  > gate checks to catch hyphenated variants the narrow iter-1 regex
  > misses.
  >
  > **Step file:** `tracks/track-15.md` (6 steps, 0 failed)
  >
  > **Strategy refresh:** CONTINUE — discoveries are confined to
  > `core/db/tool` and `core/record*`; all propagating items are already
  > absorbed into Track 22's deferred-cleanup queue (8 dead-code lockstep
  > groups + 1 WHEN-FIXED `OPPOSITE_LINK_CONTAINER_PREFIX` + 3 deferred
  > suggestions CQ12/TC13/TC14). No impact on Tracks 16–21. PR #1022
  > rulebook fix is merged into `develop` and pulled into this branch
  > (verified `MUST NOT ScheduleWakeup` and `Do NOT run git clean -fd`
  > present in `implementer-rules.md`).

- [x] Track 16: Metadata Schema & Functions
  > Write tests for schema management and function/sequence libraries.
  > Schema operations (classes, properties, cluster selection) are the
  > largest gap; function and sequence libraries are smaller but
  > self-contained.
  >
  > **Track episode:**
  > Added ~9,300 LOC of test code across 27 new/extended test files
  > covering `core/metadata/schema*` (incl. `clusterselection`),
  > `core/metadata/function`, and `core/metadata/sequence`. Purely
  > test-additive: zero production code modified across all 7 step
  > commits + 2 review-fix commits. Final aggregate coverage on the
  > touched packages (post-iter-3): `core/metadata/schema` 71.7% →
  > **84.8%/69.7%** (1231 → 662 uncov);
  > `core/metadata/schema/clusterselection` 63.3% → **93.9%/75.0%**
  > (18 → 3 uncov, 3 residual = dead-pinned LOC awaiting Track 22's
  > lockstep delete); `core/metadata/function` 73.3% → **86.1%/77.8%**
  > (71 → 37 uncov); `core/metadata/sequence` 85.4% →
  > **90.6%/75.5%** (70 → 45 uncov). Aggregate `core` module: 76.1%/66.7%
  > → **76.8%/67.4%** (+0.7pp / +0.7pp).
  >
  > **Track-level code review (3 iterations, max reached; final PASS):**
  > - Iter-1 (6 dimensions: CQ/BC/TB/TC/TX/TS): 1 blocker + 14
  >   should-fix + 17 suggestions. Applied in commit `075ed92aaf` —
  >   tracked-`spawn()` + `@After` join discipline in both concurrency
  >   tests; positive-latch rewrite of
  >   `writersAreSerializedAcrossThreads` + `writerExcludesReader`;
  >   `dropFunctionByNameThrowsNpeOnAbsentName` narrowed to
  >   `NPE | DatabaseException`; `factoryRegisterDefaultFunctionsIsNoOp`
  >   / `configIsNoOp` got observable post-state checks; equals
  >   symmetry pinned both directions on `SchemaClassProxyBoundaryTest`;
  >   `assertNotSame` swap on `SchemaImmutableClassShapeTest`;
  >   reflective method/field-signature pins extended on three
  >   `*DeadCodeTest` classes; precise `Mockito.times(1)` interaction
  >   shape on `BalancedCollectionSelectionStrategyDeadCodeTest`.
  > - Iter-2 (6-dim gate check): all PASS except TB (1 STILL OPEN —
  >   `configIsNoOp` post-state pin structurally vacuous) plus 8 new
  >   should-fix items across CQ / TB / TX. Applied in commit
  >   `9409a1a66d` — TB STILL OPEN
  >   (`configIsNoOpAndAdapterHasNoStateBeyondFunction` reflective
  >   field-count pin), TB9 (`deprecatedCreateIsIdempotent` rewrite on
  >   populated library), TX5 (one-sided-invariant doc-comment), TX6
  >   (daemon thread + `!isAlive()` + `interrupt()` + `fail(...)`
  >   discipline mirrored across both concurrency test files), CQ5
  >   (static-imported JUnit asserts in three files).
  > - Iter-3 (TB / TX / CQ gate check): **PASS** on all three. All
  >   iter-1 + iter-2 findings VERIFIED; 0 STILL OPEN, 0 REGRESSION,
  >   0 new findings on the focal dimensions. Review loop closed.
  >
  > **Cross-track impact (forwarded to Track 22 absorption queue):**
  > - Dead-code deletion lockstep groups: `IndexConfigProperty` solo
  >   delete (13 uncov, 0 prod refs); cluster-selection trio
  >   (`Balanced`/`Default`CollectionSelectionStrategy +
  >   `CollectionSelectionFactory.{getStrategy,registerStrategy,newInstance}`
  >   methods + 2 SPI entries — refined to method-level granularity at
  >   Step 1 because `CollectionSelectionFactory`'s constructor is
  >   reachable via `SchemaShared`'s field initializer; RoundRobin entry
  >   + class stay live).
  > - Latent production issues pinned by current tests (no
  >   `WHEN-FIXED` markers needed; observable behaviour is the pin):
  >   `PropertyTypeInternal:1699` static `convert(session, value,
  >   targetClass)` dispatcher null-session NPE;
  >   `SchemaProperty.get(LINKEDTYPE/TYPE)` returning the internal
  >   `PropertyTypeInternal` enum despite `Object` contract;
  >   `session.newEmbeddedEntity(linkedClass)` undocumented
  >   abstract-class requirement; `PropertyTypeInternal.convert`
  >   abstract-base `Object` return widening;
  >   `SchemaShared.releaseSchemaWriteLock(session, false)`
  >   unconditional version bump; `FunctionLibraryImpl.dropFunction`
  >   NPE-on-absent-name vs `SequenceLibraryImpl.dropSequence`
  >   no-op asymmetry; `Function#execute(Object...)` deprecated
  >   overload throws "No database session found" because
  >   `executeInContext` reads `iContext.getDatabaseSession()` before
  >   the callback short-circuit; pre-existing
  >   `FunctionLibraryTest.testFunctionCreateDrop` inert final
  >   assertion on a different name than was dropped.
  > - Iter-2 deferred items: TC9/10/11 (`PropertyTypeInternal` Result-
  >   arm coverage gaps for LINK / EMBEDDEDMAP / EMBEDDED), TS10
  >   (pre-existing `FunctionLibraryTest` `test*` naming), plus ~17
  >   suggestion-tier readability nits (BC5/6/7, CQ6/7/8/9, TC12/13,
  >   TX7, TS5/6/7/8/9, TB10) absorbed into the Track 22 backlog
  >   block via commit `acd42ee30a`.
  >
  > No upcoming-track assumption is invalidated.
  >
  > **Step file:** `tracks/track-16.md` (7 steps, 0 failed)
  >
  > **Strategy refresh:** CONTINUE — no downstream impact on Tracks 17–21.
  > Track 16's discoveries (cluster-selection trio + IndexConfigProperty
  > deletion lockstep groups, ~17 suggestion-tier readability nits, latent
  > schema/function/sequence-library production issues pinned by
  > observable behaviour) are already absorbed into Track 22's
  > deferred-cleanup queue via commit `acd42ee30a`. Track 17 (Security)
  > targets a disjoint package surface (`core/security`,
  > `core/metadata/security`, token/encryption). Carry forward to Track 17:
  > corrected-baseline rule, dead-code reframe via all-scope PSI
  > `ReferencesSearch` before driving live coverage, `*DeadCodeTest`
  > reflective method/field-signature shape pins,
  > `@Category(SequentialTest)` + `@Before Assume.assumeNotNull` for
  > static volatile dispatchers (relevant to `OSecurity*` singletons),
  > tracked-`spawn()` + `@After` join discipline for concurrency tests,
  > falsifiable-regression + WHEN-FIXED-marker convention, and
  > `@After rollbackIfLeftOpen` safety net.

- [x] Track 17: Security
  > Write tests for the security subsystem — authentication,
  > authorization, token management, and encryption.
  >
  > **Track episode:**
  > Added ~9,800 LOC of test code across 37 new/extended test files
  > covering the eight in-scope security packages (`core/security`,
  > `core/security/authenticator`, `core/security/symmetrickey`,
  > `core/security/kerberos`, `core/metadata/security`,
  > `core/metadata/security/auth`, `core/metadata/security/binary`,
  > `core/metadata/security/jwt`). Purely test-additive: zero
  > production source modified across all 7 step commits + 3
  > step-level review-fix commits + 1 Phase C iter-1 review-fix
  > commit. Final aggregate coverage on the touched packages
  > post-Phase-C: **77.7% line / 68.1% branch** (+1.6 pp / +1.4 pp
  > vs post-Track-16 baseline). Per-package highlights: `core/security`
  > 33.3% → 82% (+48.9 pp); `core/security/authenticator` 25.5% →
  > 79% (+53.2 pp); `core/metadata/security/binary` 0% → 90%
  > (+89.6 pp); `core/metadata/security/jwt` and
  > `core/metadata/security/auth` 0% → 100%. Three packages sit
  > below the project gate by design (kerberos 28%, symmetrickey
  > 43%, binary 90% on live subset) — all queued for Track 22
  > deletion.
  >
  > **Phase C track-level code review (2 iterations, PASS at iter-2):**
  > - Iter-1 (7 dimensions: CQ/BC/TB/TC/TX/SE/TS): 16 in-scope
  >   findings applied in commit `7a569757cc` —
  >   `@Category(SequentialTest)` on `SecurityManagerTest` and
  >   `DefaultSecuritySystemReloadTest` (cross-reviewer consensus
  >   for the parallel-class JVM-global-mutation race);
  >   falsifiable observable pins for the 4 documented latent bugs
  >   that the plan's "pinned by observable behaviour" claim did
  >   not actually exercise (SALT_CACHE algorithm-omission,
  >   `populateSystemRoles` NPE, `UserSymmetricKeyConfig` line 133
  >   NPE) plus the new `TokenSignImpl.readKeyFromConfig` latent
  >   bug discovered mid-review; `@After rollbackIfLeftOpen` on
  >   two missed `DbTestBase` subclasses; six
  >   `DefaultSecuritySystemReloadTest` no-op tests tightened
  >   with `assertThatNoException`; tautological assertions
  >   replaced; malformed salted-hash inputs pinned; inline FQN
  >   cleanup; non-standard `///` Javadoc converted to `/** */`;
  >   dead `DEFAULT_CHAIN_TYPES` field wired into a chain-ordering
  >   pin.
  > - Iter-2 (gate-check fan-out across all 7 dimensions): every
  >   dimension PASS; 7 new suggestion-tier observations
  >   (FQN/naming/style — absorbed into Track 22 D-block).
  >
  > **NEW latent production bug discovered during Phase C iter-1
  > review** (`TokenSignImpl.readKeyFromConfig`): the inner
  > condition is the logical negation of the outer guard, making
  > the `Base64` decode unreachable. A non-null
  > `NETWORK_TOKEN_SECRETKEY` configured at the operator's request
  > is silently ignored — every `TokenSignImpl` falls through to a
  > per-instance `SecureRandom` key. **Tokens cannot be verified
  > across server restarts or cluster nodes regardless of
  > operator configuration.** Pinned via
  > `TokenSignImplTest.readKeyFromConfigIgnoresConfiguredSecretKeyLatentBugPin`
  > with WHEN-FIXED Track 22 marker. Added as latent-issue C6 to
  > Track 22's absorption block; Track 17's "5 latent issues"
  > became 6.
  >
  > **Phase C synthesis correction** (worth surfacing): the
  > original synthesis claim "DbTestBase provides fresh DB per
  > test, so reader role has no policies" was incorrect —
  > `SecurityShared` populates the reader role with a default
  > `database.class.*.*` policy at DB creation. The implementer
  > rewrote the test to pin both branches (default-bound reader;
  > empty freshly-created role) — strictly more coverage than the
  > synthesis proposed. Recorded so future Phase C synthesis
  > work knows to verify role-creation defaults against
  > `SecurityShared` rather than assume freshness implies
  > emptiness.
  >
  > **Cross-track impact** (committed to
  > `implementation-backlog.md` Track 22 in `6df674f6cf`):
  > - 5 dead-code lockstep deletion groups (kerberos pair,
  >   binary-token quintet, JWT trio, CI plug-in chain,
  >   symmetric-key trio).
  > - 21 per-method `SymmetricKey` deletions in 3 PSI-confirmed
  >   lockstep phases (corrected from the original "18" estimate
  >   during Step 7).
  > - 6 latent production issues pinned by observable behaviour
  >   (SALT_CACHE bug; `createServerUser` empty-password;
  >   `populateSystemRoles` NPE; `UserSymmetricKeyConfig` NPE;
  >   deprecated `Function#execute` always-throws; **NEW**:
  >   `TokenSignImpl.readKeyFromConfig` unreachable inner branch).
  > - Suggestion-tier deferrals: PBKDF2 iteration-override
  >   extraction; `DefaultSecuritySystem` helper extraction;
  >   existing-class-preferred Track 22 discipline; deferred live
  >   paths; plus Phase C iter-1 additions — ~400 LOC
  >   `Token`/`TokenHeader` stub extraction (TS-5/CQ1/CQ2);
  >   `rollbackIfLeftOpen` hoist into `DbTestBase` (CQ3); BC4
  >   5-min TOCTOU window deferred alongside binary-token cluster
  >   deletion; minor FQN/naming/style nits.
  >
  > No upcoming-track assumption is invalidated.
  >
  > **Step file:** `tracks/track-17.md` (7 steps, 0 failed)
  >
  > **Strategy refresh:** CONTINUE — no downstream impact on Tracks 18–21.
  > Track 17 was purely test-additive (zero production source modified) and
  > all cross-track items (5 dead-code groups, 21 `SymmetricKey` deletions,
  > 6 latent production issues incl. C6 `TokenSignImpl.readKeyFromConfig`)
  > are already absorbed into Track 22's queue via commit `6df674f6cf`.

- [x] Track 18: Index
  > Write tests for the index management layer — index engines, index
  > iterators, and index operations.
  >
  > **Track episode:**
  > Added ~1,400 LOC of test code across 18 new test classes and 4
  > extended ones (~390 new tests) covering the six in-scope index
  > packages (`core/index`, `core/index/iterator`, `core/index/comparator`,
  > `core/index/multivalue`, `core/index/engine`, `core/index/engine/v1`).
  > Purely test-additive: zero production source modified across 18
  > Track-18 commits (1 Phase A decomposition + 1 base-commit record
  > + 5 step impl + 5 step-episode workflow updates + 2 Phase C
  > review-fix commits + 4 Phase C workflow-update commits). Full
  > core suite (1 876 tests) green throughout.
  >
  > **Coverage outcome** (per `track-18-baseline.md` measured at end
  > of Phase B Step 5; iter-1 / iter-2 added tests on top, so the
  > end-of-track numbers are slightly above this snapshot):
  > Aggregate `core` module **78.2% line / 68.5% branch** (+0.5 pp /
  > +0.4 pp vs post-Track-17). Cumulative gain since Phase 1
  > (63.6% / 53.3%): **+14.6 pp line / +15.2 pp branch.** Per-package
  > gates: 5 of 6 PASS (`core/index/iterator` 86.0% / 89.5%;
  > `comparator` 100% / 100%; `multivalue` 100% / 100%; `engine`
  > 90.9% / 85.1%; `engine/v1` 87.1% / 85.4%); `core/index` MISSES
  > at **80.3% / 69.4%** (short by ~4.7 pp line, ~0.6 pp branch).
  > Largest contributors: `IndexAbstract` (~93 uncov — engine-init /
  > rebuild paths needing a fully-booted DB), `ClassIndexManager`
  > (~90 uncov — `addIndexesEntries` SQL path), `CompositeIndexDefinition$CompositeWrapperMap`
  > (~51 uncov — inner map-view), `RecreateIndexesTask` (~45 uncov —
  > catch-branch only partially exercised by the proxy stub).
  >
  > **Phase C track-level code review (2 iterations, gate PASS at
  > iter-2):**
  > - **Iter-1** (5 dimensions: code-quality, bugs/concurrency,
  >   test-behavior, test-completeness, test-structure): 28 distinct
  >   findings synthesised → 22 in-scope + 8 deferred to Track 22.
  >   Iter-1 fixes landed in `d914bfd067` (14 test files, 379 inserts
  >   / 145 deletes; 56 index-package classes, 1 304 tests, 0
  >   failures). Phase C implementer hit a contract violation
  >   mid-iteration (silent exit, no `RESULT` block, leftover stuck
  >   Maven test + self-referential `pgrep -f surefire` poll loop) —
  >   root cause + workflow-fix proposal documented in
  >   `WORKFLOW_ISSUE_implementer_silent_exit.md`. Orchestrator
  >   recovered via finalizer + direct commit. The rulebook fix later
  >   landed on `develop` as PR #1043 (commit `c49a897a53`, picked up
  >   on the next Phase A rebase) and the recovery path is now part
  >   of the codified protocol.
  > - **Iter-2 gate-check** (3 dimensions re-run on commit
  >   `84e117de31`): all three returned **PASS**. The 7 in-scope
  >   iter-1 gate-check findings — TC13 / TB17 / CQ14 (joint), TB18,
  >   TB19, TC14, TC15, CQ12, CQ13 — were genuinely fixed in a
  >   single Opus spawn (55/55 targeted tests passed; spotless clean).
  >   7 new suggestion-tier findings (CQ15-17 / TC16-19) absorbed
  >   into Track 22's deferred-cleanup queue; iteration 3 was **not**
  >   consumed.
  >
  > **Key technical discoveries:**
  > - **TC13's load-bearing observable for catch-continue is
  >   `rebuildCompleted == true`**, not the per-stub
  >   `AtomicBoolean configRequested` flags. Both flags fire during
  >   the snapshot phase
  >   (`IndexManagerEmbedded.getIndexesConfiguration` calls each
  >   Index's `getConfiguration()` BEFORE the recreation loop runs);
  >   they prove snapshot-phase visitation, not loop-body
  >   catch-absorption. The strengthened test in
  >   `RecreateIndexesTaskTest` correctly anchors on
  >   `rebuildCompleted`, and the rewritten Javadoc honestly narrows
  >   the AtomicBoolean claim. PSI-confirmed via
  >   `ReferencesSearch` on `Index.getConfiguration` (2 production
  >   callers — only the snapshot-phase one is on the recreation
  >   path).
  > - **Cleared-TX branch is duplicated 5+ times per index class.**
  >   The iter-1 prescription enumerated only `streamEntriesBetween`;
  >   iter-2 covered `streamEntries(keys, asc)` /
  >   `streamEntriesMajor` / `streamEntriesMinor` for both
  >   `IndexOneValue` and `IndexMultiValues`, plus `descStream` for
  >   `IndexMultiValues`. Four whole-index variants remain uncovered
  >   (`IndexOneValue.stream`, `IndexOneValue.descStream`,
  >   `IndexMultiValues.stream`, `IndexOneValue.getRids`
  >   inverse-cleared) — surfaced as TC16-19 and forwarded to Track
  >   22.
  > - **`IndexRebuildOutputListener.onBegin` has no public
  >   observable.** It writes only to private fields (`startTime`,
  >   `lastDump`, `rebuild`); the only meaningful contract without
  >   reflection is "did not throw". Iter-2 documented this scope
  >   explicitly to prevent a future reviewer from re-introducing
  >   the tautological `assertTrue(onProgress(...))` follow-up.
  >
  > **Cross-track impact** (committed to `implementation-backlog.md`
  > Track 22 absorption queue in `7d995d45a2`):
  > - **Dead-code lockstep groups** (carried from Phase A):
  >   `{IndexCursor, IndexAbstractCursor, IndexCursorStream}` (one
  >   lockstep, 3 classes) plus `{IndexKeyCursor}` (separate).
  >   `*DeadCodeTest` shape pins are in place per Track-17
  >   precedent.
  > - **Phase C iter-2 absorption** (new): TC16 / TC17 / TC18 / TC19
  >   cleared-TX coverage gaps + CQ15 (descStream symmetry) + CQ16
  >   (cleared-TX setup helper extraction) + CQ17 (`assertFalse`
  >   idiom). All 7 are pure test-additive, picked up
  >   opportunistically when Track 22 visits the index test files.
  >
  > **Cross-track hints to Track 21** (storage / sbtree):
  > - `BTreeSingleValueIndexEngine` and `BTreeMultiValueIndexEngine`
  >   `doClearTree` "removed 0 entries" error paths remain uncovered
  >   — require actual B-tree data with page-cursor invalidation.
  >   Track 21 covers them transitively.
  > - `DefaultIndexFactory.createIndexEngine("remote", ...)` returns
  >   `RemoteIndexEngine` — not exercisable from memory/disk test
  >   sessions. Track 21 may want to cover that branch explicitly.
  > - The BTree version-guard constructor branches are now
  >   shape-pinned in `BTreeEngineConstructorValidationTest`; Track
  >   21 does not need to re-test them.
  >
  > No upcoming-track assumption is invalidated. The `core/index`
  > gate miss is documented as known scope per Step 5's baseline;
  > Track 22 absorption may close it via TC16-19 if budget permits.
  >
  > **Step file:** `tracks/track-18.md` (5 steps, 0 failed)
  >
  > **Strategy refresh:** CONTINUE — no downstream impact on Tracks 19–22.
  > Track 18 was purely test-additive (zero production source modified). Cross-track
  > hints to Track 21 (BTree `doClearTree` error paths, `RemoteIndexEngine` factory branch,
  > `BTreeEngineConstructorValidationTest` shape pins) ride in the Track-18 episode for
  > Track 21's Phase A to read; Track 22's absorption queue (TC16-19 cleared-TX gaps,
  > CQ15-17 DRY findings, dead-code lockstep groups `{IndexCursor, IndexAbstractCursor,
  > IndexCursorStream}` and `{IndexKeyCursor}`) was committed in `7d995d45a2`. Track 19's
  > scope (`core/storage/{config,memory,fs,disk,collection,ridbag}*`) is functionally
  > independent of `core/index*`, so the residual `core/index` gate miss does not
  > invalidate any Track 19 assumption.

- [x] Track 19: Storage Fundamentals
  > Write tests for storage subsystem components that are more testable
  > than the core cache/WAL/impl internals (storage config, memory
  > storage, filesystem, disk, collections, ridbag).
  >
  > **Track episode:**
  > Test-additive coverage track across nine `core/storage/*` packages,
  > ~7,200 lines of new test code (28+ files: 12 new, 16+ extended; 386+
  > new tests). Three test patterns landed: standalone JUnit 4 (data
  > classes, page-level helpers, fs wrappers, reflection-based static
  > helpers), `DbTestBase + AtomicOperation` (config / memory-storage /
  > ridbag iterator), and the page-level direct-memory pattern
  > (`ByteBufferPool` → `CachePointer` → exclusive lock → ops →
  > `decrementReferrer` in `finally`). Per-package coverage gates were
  > met or accepted under D4 storage-internal allowance for
  > `core.storage.config` (68.2/51.4 — `toStream`/`copy` need disk-mode
  > backup tests, blocked by item 1 below), `core.storage.fs` (83.0/81.8
  > — three branches fundamentally untestable from unit scope), and
  > `core.storage.ridbag` (90.0/67.9 — phantom-assert branches plus
  > rollback-scenario paths blocked by item 1 below). Phase C ran two
  > iterations (iter-3 reserve unused) closing direct-memory safety
  > (TX1/TC4/TX2/TS1/TX3 — try/finally discipline plus per-file
  > `withTwoPages` helpers), critical falsifiability gaps (the toString
  > smoke-pin sweep tightening 24+ assertions to op-specific field
  > values), and a license-header malformation. Both gate checks PASS;
  > 293/293 tests pass at iter-2; Spotless clean.
  > **Cross-track impact (forwarded to Track 22):** four production-bug
  > pins with `WHEN-FIXED:` markers — (1) `CollectionBasedStorageConfiguration.setMinimumCollections`
  > deadlock (Step 1, commentary-only — daemon-thread-leak risk),
  > (2) `CollectionBasedStorageConfiguration.removeProperty` cache
  > staleness (Step 1, executable assertions), (3) `AbstractLinkBag.EnhancedIterator.reset()`
  > stale `nextPair` (iter-1 PSI investigation; two-line fix),
  > (4) `DiskStorage.XXHashOutputStream.write(byte[], int, int)` length
  > vs end-index mismatch (iter-1 PSI investigation; latent because
  > all current callers pass `off == 0` but a future non-zero-offset
  > caller would silently corrupt backup hashes). Plus three suggestion-
  > tier items (TS12 stale Javadoc, TS13 shutdownNow await,
  > PageOperation toString chain non-accumulation). All recorded in
  > `implementation-backlog.md` Track 22 absorption block. **Note**:
  > the step-file's `## Base commit` records `141f874b6b` (a pre-rebase
  > SHA); the actual on-branch Phase B parent is `ea9e82198f`. This
  > didn't affect correctness (Phase C used the actual parent) but
  > should be addressed as a workflow self-improvement.
  >
  > **Step file:** `tracks/track-19.md` (5 steps, 0 failed)
  >
  > **Strategy refresh:** CONTINUE — Track 19's discoveries are confined to the storage cluster and absorbed by Track 22's existing absorption block (4 production-bug pins + 3 suggestion-tier items). The page-level direct-memory test pattern (`ByteBufferPool` → `CachePointer` → exclusive lock → ops → `decrementReferrer` in `finally`) carries forward unchanged into Tracks 20–21. D4 (storage-internal coverage allowance) reinforced by three accepted per-package gates. No downstream impact on Tracks 20–22.

- [x] Track 20: Storage Cache & WAL
  > Write tests for the write cache (WOWCache), read cache, double-write
  > log, and WAL components. These are complex concurrent subsystems —
  > expect to fall short of 85%/70% targets per D4.
  >
  > **Track episode:**
  > Test-additive coverage track for the storage cache + WAL clusters
  > (`cache.local`, `cache.local.doublewritelog`, `paginated.wal.cas`,
  > `paginated.wal`, `wal.common`, `wal.common.deque`, `cache`,
  > `cache.chm`, `cache.chm.readbuffer`, `cache.chm.writequeue`). Six
  > steps landed the per-package coverage targets committed at
  > decomposition (D4-allowed ceilings: `cache.local` ~78%/~62%,
  > `doublewritelog` ~70%/~52%, the others ≥85%/≥70%). Phase C
  > dimensional review fan-out (9 sub-agents) surfaced 1 blocker, 22
  > should-fix, and 27 suggestion-tier findings. Two implementer
  > iterations applied the in-scope subset:
  > `Review fix: Track 20 Phase C iteration 1` (12 fixes — closed the
  > blocker plus 11 falsifiability + concurrency-correctness items;
  > notable: replaced a single-threaded WOWCache "double-add" probe
  > with a true `CyclicBarrier` N-writer race, fixed bare `await()` /
  > `join()` patterns in `CachePointerPageFrameTest`, eliminated a
  > `Thread.sleep` polling loop in `CASDiskWriteAheadLogLifecycleTest`,
  > added close-and-reopen durability proof for `logFlushAndReadRoundTrip`,
  > and added a real cut-prevention test for `addCutTillLimit`); and
  > `Review fix: Track 20 Phase C iteration 2` (13 fixes —
  > empty-payload boundary tests for length-prefixed WAL records,
  > `BUFFER_SIZE` boundary for `MPSCFAAArrayDequeue`, central
  > `CoverageTestWALRecordIds` constant for the test-record-ID
  > discipline, snapshot/restore for `DIRECT_MEMORY_TRACK_MODE`, UUID
  > suffixes on static `TEST_DIR` paths per the project-global
  > temp-path rule, and quiescence of the commit executor via
  > `wal.close()` before exactly-once `callCount` checks). Both iter
  > gate-checks PASS across all re-run dimensions. ~27 suggestion-tier
  > items forwarded to Track 22 absorption (file splits, fixture
  > extractions, magic-number cleanups, MT probe for `BoundedBuffer`,
  > direct-memory-pool cleanup hygiene, reflection-fragility
  > hardening, and a small diagnostic-toString re-pin for
  > `Cursor`/`Node`). Key discoveries: (1) the original "end > begin"
  > falsifiability hypothesis on the fresh-WAL test was incorrect —
  > the constructor publishes `end` at the same `RECORDS_OFFSET` where
  > it logged the StartWALRecord; pin tightened to
  > `end.getPosition() == RECORDS_OFFSET`; (2)
  > `CASDiskWriteAheadLog.close()` is **not** idempotent — the iter-2
  > implementer adopted a local-boolean closed-flag pattern to avoid
  > double-close in the existing finally block, and Track 22 may
  > consider hardening `close()` itself; (3) `MPSCFAAArrayDequeue.Node.enqidx`
  > initialises to 1 (not 0), so an offer of `BUFFER_SIZE = 1024`
  > items fills slots 1..1023 in node 1 and the 1024th offer triggers
  > a fresh node — the new boundary test passes for the right reason,
  > the test name is flagged for clarification in Track 22; (4) the
  > `CoverageTestWALRecordIds` compile-time anti-collision invariant
  > was inactive at end of Phase B (lifecycle test hardcoded ID 460
  > locally); the discipline is now live. Cross-track impact: Track 22's
  > WHEN-FIXED pin in `WOWCacheConcurrencyShapesTest` is now
  > significantly stronger — the new `CyclicBarrier`-based MT race on
  > the same `(fileId, pageIndex)` will properly fail-over to the
  > correct-behaviour assertion when the production `addOnlyWriters`
  > fix lands. Track 22 absorption block extended in
  > `implementation-backlog.md` with the deferred suggestions
  > (commit `609b47d2d9`).
  >
  > **Step file:** `tracks/track-20.md` (6 steps, 0 failed)
  >
  > **Strategy refresh:** CONTINUE — all Track 20 cross-track impact already absorbed into Track 22 backlog (commit 609b47d2d9). No Component Map shift, no Decision Record changes; D4 (storage-internal coverage allowance) reinforced by three accepted per-package gates. Track 21 inherits the Track 19/20 page-level direct-memory pattern (`ByteBufferPool.acquireDirect()` → `CachePointer` → `incrementReadersReferrer` → exclusive lock → ops → `decrementReferrer` in `finally` + `bufferPool.clear()` + `allocator.checkMemoryLeaks()` in `@After`) plus the Track 20 CyclicBarrier MT-race pattern for storage-cluster concurrency probes. Heads-ups for Track 21 Phase A: (1) backlog has no Track 21 section — reconstruct scope from the Component Map and the actual `core/storage/index/sbtree/{multivalue,singlevalue,local}/{v1,v2,v3}` + `nkbtree` + `versionmap` + `engine` + `impl/local*` package layout; (2) some `impl/local/paginated/{wal,base,atomicoperations}` lines may already be incidentally covered by Tracks 19/20 — cross-check at Phase A to avoid duplicate test files; (3) prefer Mockito `doReturn(...)` over `when(...).thenReturn(...)` for void-returning `WriteCache`/`CacheEntry` stubs (Track 20 Mockito void-stub trap codify-note).

- [x] Track 21: Storage B-tree & Impl
  > Write tests for B-tree index storage and storage implementation
  > internals. These are the lowest-level storage components, tightly
  > coupled to page-based I/O and WAL operations — expect to fall short
  > of 85%/70% targets per D4.
  >
  > **Track episode:**
  > Test-additive coverage track for the storage B-tree and impl/local
  > clusters (`storage.index.sbtree.{multivalue/v2, singlevalue/v3,
  > local/v2}` plus `nkbtree.normalizers`, `sbtree` top-level,
  > `index/engine`, `index/versionmap`, and
  > `storage.impl.local{,/paginated}`). Seven steps landed against the
  > per-package targets committed at decomposition (D4 ceilings:
  > `impl/local` ~63%/~59%, `paginated` top-level ~88%/~65%,
  > `nkbtree.normalizers` ~75%/~23%; the rest ≥85%/≥70%). Aggregate
  > post-track baseline: **79.5% line / 69.4% branch** (+0.5 pp /
  > +0.4 pp from Track 20). Phase C dimensional review fan-out
  > (8 sub-agents) surfaced 0 blockers, ~25 should-fix, ~30 suggestions;
  > three implementer iterations applied the in-scope subset:
  > `Review fix: Track 21 Phase C iteration 1 — falsifiability +
  > ephemeral identifier sweep` (`19857464e5`, 11 fixes — durable-
  > content "Track NN" sweep across 8 test sources, falsifiable
  > strengthening of two `StaleTransactionMonitor` probes,
  > atomicising `FakeScheduledExecutor.scheduledCount`,
  > message-contains assertions on `CellBTreeSingleValueV3Exception`
  > ctors, `RemoteIndexEngine` factory propagation pin);
  > `Review fix: Track 21 Phase C iteration 2 — regression revert +
  > test strengthening` (`ff10bf63f5`, 17 fixes — REGRESSION revert on
  > `testStartIdempotentUnderConcurrentRace` upper bound back to
  > `<= racerCount` after empirical 6/8 flake reproduction;
  > non-vacuous strengthening of `testRunDoesNotPropagateExceptions`;
  > `validatedPut` substitution-branch test in the live B-tree engine;
  > stop-during-start race under `CyclicBarrier(2)`;
  > corruption-with-backup recovery + legacy 9-byte / 1-byte format
  > tests for `StorageStartupMetadata`; `PageOperation.equals` chain
  > pin; antisymmetry assertions on `compareTo`; per-test UUID DB
  > names; `volatile cancelled`); and
  > `Review fix: Track 21 Phase C iteration 3 — close residual RID
  > import` (`42f45cbc90`, 1 fix — `RID` inline FQN at
  > `BTreeMVBucketV2BulkOpsTest:460` converted to import). All six
  > re-run gate-check dimensions (CQ/BC/TB/TC/TS/TX) PASS at iter-3
  > with zero new findings. Cumulative track diff is test-additive
  > (no `core/src/main/**` changes vs `origin/develop`), so coverage
  > gate is `n/a (test-additive)` per the rulebook short-circuit.
  > Key discoveries: (1) the live B-tree engine class is
  > `singlevalue/v3.BTree`, NOT the non-existent
  > `CellBTreeSingleValueV3` / `CellBTreeMultiValueV2` / `SBTreeV2` —
  > Phase A reviews caught and corrected this misreference;
  > `multivalue/v2` and `local/v2` are WAL-replay-only D4 packages
  > reachable via direct bucket / `*Op` round-trip, not the live
  > engine. (2) `BTreeLifecycleTest` needs
  > `@Category(SequentialTest.class)` because it mutates
  > `GlobalConfiguration.BTREE_MAX_KEY_SIZE` and surefire's parallel
  > execution races schema init in sibling test classes. (3) The
  > `multivalue/v2` displayed branch% (69.2%) is gate-passing —
  > `coverage-gate.py` strips ~6 phantom branches from `assert`
  > statements; future top-up must use the gate as authoritative,
  > not raw JaCoCo. (4) `StorageStartupMetadata.open()` legacy paths
  > (size ≤ 9) silently rely on `ByteBuffer`'s default BIG_ENDIAN
  > rather than `nativeOrder()` — surfaced when the iter-2 implementer
  > initially wrote little-endian and the test failed. Cross-track
  > impact: Track 22 absorption block extended with three new
  > Phase-C-sourced items (`StorageStartupMetadata` BIG_ENDIAN
  > documentation, `AbstractStorage` null-snapshot test-helper
  > deferred, ephemeral-identifier sweep across ~7 earlier-track
  > test files) plus seven Phase B items (commit `6ea5492d4c`).
  >
  > **Step file:** `tracks/track-21.md` (7 steps, 0 failed)
  >
  > **Strategy refresh:** CONTINUE — Track 21 outcomes already absorbed
  > by Track 22's existing absorption block (7 items pushed in
  > `6ea5492d4c` and `350e642fd5`); no Component Map shifts; no
  > Decision Record changes; no new inter-track dependencies. Two
  > Pre-Flight clarifications captured for Track 22's step file:
  > (1) WHEN-FIXED issue creation deferred to Track 22 itself with
  > `// WHEN-FIXED: Track 22` → `// WHEN-FIXED: YTDB-NNNN` marker
  > rewrite in lockstep, no-issue convention for clusters deleted
  > in-track; (2) dead-code deletion policy is hybrid cluster-by-
  > cluster — Phase A adversarial review classifies each cluster via
  > PSI find-usages and the `mcp-steroid://ide/safe-delete` recipe,
  > SPI-safe clusters deleted atomically with their `*DeadCodeTest`
  > pins, SPI-risky clusters (abstract bases, public SPIs, exception
  > types) issue-and-defer to follow-up PRs. Step decomposition
  > guidance grows from "~6 + ~3–4" to roughly "~6 main coverage +
  > ~8–10 deletion lockstep + ~1–2 marker-rewrite" steps; Phase A
  > may inline-pack deletion into the related coverage step.

- [ ] Track 22a: Main Coverage Sweep — Transactions, Gremlin & Remaining Core
  > Write tests for transaction management, Gremlin integration, engine
  > lifecycle, exception/compression/config, and remaining uncovered core
  > packages (cache, id, conflict, dictionary, servlet, replication,
  > type, collate, `api/exception`, `api/config`). Closes the test-additive
  > subset of the deferred-cleanup queue accumulated by earlier tracks
  > (full inventory in the backlog).
  >
  > **Scope:** ~6 steps covering main-package coverage sweep, smaller
  > packages, and a final coverage-build verification step (re-runs
  > `coverage-analyzer.py` and updates `coverage-baseline.md` after
  > 22a's test additions). Aggregate target after 22a: ~81–82% line /
  > ~70% branch (pre-22b denominator drop).
  >
  > **Depends on:** Track 1
  >
  > **Operational note:** see backlog § Inherited absorption queue for
  > recovery context, the iter-1 mechanical-fix audit, and the Phase A
  > re-validation protocol.

- [ ] Track 22b: In-Track Dead-Code Deletion Lockstep
  > Atomic per-cluster commits removing dead production code together
  > with its `*DeadCodeTest.java` shape pin, classified via
  > `mcp-steroid://ide/safe-delete` (SPI-safe clusters deleted in-track;
  > SPI-risky clusters deferred to 22c). Each in-track-deletion cluster
  > is one bisectable commit, with `coverage-analyzer.py` re-run after
  > each so the post-deletion denominator drop is reflected in
  > `coverage-baseline.md` (full cluster inventory in the backlog).
  >
  > **Scope:** ~9 steps — one per in-track-deletion cluster (controlled
  > exception per D5; 22b Phase A may pack two narrow clusters into
  > one step if final inventory exceeds 7). Inventory grew by one
  > Track-22a-Phase-C forwarded cluster (`BasicCommandContext.copy()`
  > partial-class-trim — see backlog); license-header normalization
  > is folded into per-cluster commits, no separate step. Aggregate
  > target after 22b: ~82–83% line / ~70–71% branch (denominator drop
  > from deletions).
  >
  > **Depends on:** Track 1, Track 22a (consumes 22a's PSI safe-delete
  > confirmations, the post-22a coverage baseline, and the Phase C
  > forwarded items in the backlog).

- [ ] Track 22c: WHEN-FIXED Issue Creation & Marker Rewrite
  > Open YTDB tracking issues for production-fix WHEN-FIXED pins (the
  > 101 non-dead-code markers across 164 test files, one issue per
  > logical fix) plus the SPI-risky dead-code clusters deferred from
  > 22b. Rewrite the inline `// WHEN-FIXED: Track 22` markers in the
  > corresponding test sources to reference the new YTDB-NNNN IDs;
  > clusters deleted by 22b are skipped (full deferred-cluster list
  > and per-issue commit policy in the backlog).
  >
  > **Scope:** ~1–2 steps — one batched issue-creation + marker-rewrite
  > step covering all surviving WHEN-FIXED markers, plus an optional
  > verification step that greps for any unrewritten `Track 22` /
  > `deferred-cleanup track` markers across `core/src/test/`. No
  > coverage delta expected; this is tracker hygiene.
  >
  > **Depends on:** Track 1, Track 22a, Track 22b (consumes 22b's
  > final cluster-disposition list as the filter — only markers in
  > clusters NOT deleted by 22b need YTDB issues).

## Plan Review
- [x] Plan review (consistency + structural) — passed at iteration 2

**Auto-fixed (mechanical)**:
- **CR1** (blocker): dropped phantom `OTokenHandlerImpl` reference from
  the backlog Track 22b Binary Token / JWT cluster bullet; replaced with
  a pointer to the Track 17 absorption block which already enumerates
  the live binary-token quintet and JWT trio.
- **CR2** (should-fix): synced the plan Non-Goals headline target from
  `85% line / 70% branch` to `~82–83% line / ~70–71% branch`, matching
  the amended §Goals.
- **CR3** (should-fix): synced `design.md` Overview ¶1 + Workflow
  mermaid loop predicate to the amended target; mermaid now delegates
  the threshold to plan §Goals.
- **CR4** (should-fix): updated `design.md` Overview ¶2 track count
  from "22 tracks total" to "24 tracks total" reflecting the 22a/22b/22c
  split.
- **CR5** (should-fix): rewrote three Class Design "Used by" bullets in
  `design.md` from `Track 22 (...)` to `Track 22a (...)` since 22a is
  the only sub-track that authors test classes.
- **CR6** (suggestion): updated the D5 risk caveat from "22 PRs" to
  "24 PRs" with inline note about the split.
- **S2** (should-fix): deleted the plan §Operational Notes block (69
  lines) — its self-stated retirement criterion was met (PR #1022 merged
  into `develop`); the recovery context was absorbed into the backlog
  `### Inherited absorption queue` HTML comment.
- **S3** (should-fix): trimmed Track 22a intro paragraph from 5
  sentences to 2 with a backlog-pointer for the deferred-cleanup queue.
- **S4** (should-fix): replaced Track 22a's 9-line `**Operational note:**`
  block with a 3-line pointer to the backlog `### Inherited absorption
  queue`; the iter-1 mechanical-fix audit list now lives in the backlog
  HTML comment.
- **S5** (suggestion): trimmed Track 22b intro paragraph and removed the
  14-line "Strong candidates" enumeration from the scope-indicator (full
  list preserved verbatim in the backlog `**What** (deletion clusters)`
  block).
- **S6** (suggestion): trimmed Track 22c intro paragraph from 4
  sentences to 2 with a backlog-pointer for the deferred-cluster list and
  per-issue commit policy.
- **S7** (suggestion): replaced Track 22b's scope-indicator enumeration
  with a discoverability cross-reference to D5's controlled-exception
  clause.
- **S8** (suggestion): rewrote a phantom past-tense reference in the
  backlog reconstruction-protocol prose that pointed at the now-deleted
  plan §Operational Notes section; the protocol description survives
  intact.

**Escalated (design decisions)**: none.

**Acknowledged but deferred**:
- **S1** (should-fix): plan-file total length is 1938 lines after the
  S2/S3/S4 trims (down from 2034), still ~28% over the soft ~1500-line
  budget. The remaining overage is dominated by user-approved
  completed-track episode prose (Tracks 7–21). The structural-review
  rule's proposed fix uses *recommend* language rather than prescribing
  a single rendering, and there is no documented per-section budget for
  episode prose, so an autonomous global trim would risk losing
  strategic context that future tracks may need. Recorded here so the
  user can authorize a dedicated trim pass if desired.

## Final Artifacts
- [ ] Phase 4: Final artifacts (`design-final.md`, `adr.md`)
