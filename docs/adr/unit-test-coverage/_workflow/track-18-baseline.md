# Track 18 — Index — Post-Track Baseline

Coverage measurement performed at the end of Phase B Step 5 with
`./mvnw -pl core -am clean package -P coverage -DskipITs`. Track 18
is purely test-additive (zero production-source changes from base commit
to HEAD), so the coverage gate on changed lines is trivially `n/a
(test-additive)`.

**Track 18 base commit:** `04a1f5072a0172b111da7454b3421c78a934ecac`
(Phase B kickoff commit for Track 18 — `Phase A review and decomposition
for Track 18 (Index)`).

**Post-Step-5 HEAD at measurement time:** `912cff57a4` is the workflow-
update commit from Step 4; Step 5 adds 2 more test files on top but the
coverage numbers below reflect the full Track-18 test suite through Step 5.

**JaCoCo report path:**
`.coverage/reports/youtrackdb-core/jacoco.xml`.

## Aggregate (whole `core` module)

- **Line coverage:** 78.2% (73 907 / 94 503 covered, 20 596 uncov)
- **Branch coverage:** 68.5% (31 683 / 46 221 covered, 14 538 uncov)
- **Packages:** 178

For comparison:

| Baseline | Line% | Branch% | Packages |
|---|---|---|---|
| Original Phase 1 (pre-Track-1) | 63.6% | 53.3% | 177 |
| Post-Track-16 (track-16-baseline.md) | 76.1% | 66.7% | 178 |
| Post-Track-17 (track-17-baseline.md) | 77.7% | 68.1% | 178 |
| **Post-Track-18 (this document)** | **78.2%** | **68.5%** | **178** |

Track 18 raised aggregate line coverage by **+0.5 pp** (from 77.7%) and
branch coverage by **+0.4 pp** (from 68.1%). The cumulative gain since
Phase 1 is **+14.6 pp** line / **+15.2 pp** branch.

## Six in-scope index packages — per-package gate results

| Package (short) | Line% | Branch% | Uncov Lines | Total | Gate |
|---|---|---|---|---|---|
| `core/index` | 80.3% | 69.4% | 628 | 3 190 | **MISS** |
| `core/index/iterator` | 86.0% | 89.5% | 21 | 150 | PASS |
| `core/index/comparator` | 100.0% | 100.0% | 0 | 10 | PASS |
| `core/index/multivalue` | 100.0% | 100.0% | 0 | 3 | PASS |
| `core/index/engine` | 90.9% | 85.1% | 132 | 1 451 | PASS |
| `core/index/engine/v1` | 87.1% | 85.4% | 68 | 526 | PASS |

Gate thresholds: 85% line / 70% branch per package.

## `core/index` package — gap analysis (80.3% line / 69.4% branch)

This is the one package that did not meet either threshold. The 628
uncovered lines are concentrated in:

| Class | Line% | Uncov | Total |
|---|---|---|---|
| `IndexAbstract` | 79.9% | 93 | 462 |
| `ClassIndexManager` | 63.6% | 90 | 247 |
| `CompositeIndexDefinition$CompositeWrapperMap` | 32.9% | 51 | 76 |
| `IndexManagerEmbedded` | 80.4% | 51 | 260 |
| `RecreateIndexesTask` | 55.4% | 45 | 101 |
| `CompositeIndexDefinition` | 82.9% | 43 | 251 |
| `IndexOneValue` | 85.0% | 35 | 234 |
| `IndexMultiValues` | 86.6% | 35 | 261 |
| `IndexAbstractCursor` | 0.0% | 34 | 34 |
| `SimpleKeyIndexDefinition` | 80.4% | 22 | 112 |
| `Index` (interface) | 68.3% | 19 | 60 |
| `PropertyMapIndexDefinition` | 82.1% | 17 | 95 |
| `PropertyIndexDefinition` | 85.5% | 16 | 110 |
| `IndexDefinitionFactory` | 87.1% | 15 | 116 |
| `IndexRebuildOutputListener` | 75.0% | 13 | 52 |
| (remainder — 13 classes) | varied | ~48 | varied |

### Root causes of the miss

1. **`IndexAbstract` (93 uncov)**: Large class (~462 lines) with multiple
   heavyweight paths — `init(session, metadata)` full bootstrap path,
   `recreateIndexBoundary` variants, bulk-loading state transitions,
   `getRebuildVersion` with the retry-on-`InvalidIndexEngineIdException`
   loop, and the `flush`/`close` lifecycle methods. These require a
   fully-booted DB with a disk-backed index; the Step 4 tests exercised
   the simpler non-histogram surface but left the engine-init and
   rebuild paths uncovered.

2. **`ClassIndexManager` (90 uncov)**: Steps 3 and 4 covered the
   create/update/delete hooks and `reIndex` but left the full
   `addIndexesEntries` SQL path, `isIndexRebuildScheduled`,
   `processIndexUpdate` variants, and several class-event dispatch
   branches uncovered. These require multi-class schemas.

3. **`CompositeIndexDefinition$CompositeWrapperMap` (51 uncov)**:
   Inner map-view class exposed by `CompositeIndexDefinition.getFieldsToIndex()`.
   Its entrySet / keySet / containsKey methods were not reached by any
   Step 1–4 test. Extension candidate: `CompositeIndexDefinitionTest`.

4. **`IndexManagerEmbedded` (51 uncov)**: Step 3 drove the main test
   surface but left `autoRecreateIndexesAfterCrash` crash-detection path,
   `reloadAllIndexes`, and `reLoadIndexes(name)` uncovered.

5. **`RecreateIndexesTask` (45 uncov)**: Step 3 added both happy path and
   catch-branch, but the catch-branch coverage is only ~55% because
   the proxy-stub only exercises part of the catch logic (individual
   index reconstruction failures vs. full-rebuild abort).

### Recovery plan (Phase C extension candidates)

The gap is ~4.7 pp (85% − 80.3%) below the line threshold. Recovery
options in priority order:

1. **Extend `CompositeIndexDefinitionTest`** to call
   `getFieldsToIndex()` and exercise `CompositeWrapperMap` methods
   (entrySet, keySet, containsKey) — ~40 lines recoverable.
2. **Extend `IndexAbstractCorePathsTest`** or add a dedicated class for
   the `init` full-bootstrap path and `recreateIndexBoundary` —
   ~30 lines recoverable via DbTestBase sessions with a real index.
3. **Extend `ClassIndexManagerTest`** for `addIndexesEntries` full path
   — requires a multi-property schema with actual INSERT statements.
4. **Extend `IndexManagerEmbeddedTest`** for `reloadAllIndexes` /
   `reLoadIndexes(name)`.

Any one of items 1–2 combined would likely close the gap. All are
DbTestBase-safe extension candidates; no new test class is needed.

Forwarded to Phase C review as a potential remediation request.

## `core/index/engine` package — residual gap (90.9% line / 85.1% branch)

132 uncovered lines remain. Both thresholds are met (85% line / 70%
branch). The residual gap is concentrated in:

- `HistogramStatsPage` — WAL-redo paths that require a real page cache
- `HistogramStatsPageWriteSnapshotOp` / `HistogramStatsPageWriteHllToPage1Op`
  / `HistogramStatsPageWriteEmptyOp` — `equals`/`hashCode`/`toString`
  not exercised (serialization round-trip tests cover them only partially)
- `UniqueIndexEngineValidator` — all branches now covered by Step 5

No action required; both thresholds met.

## `core/index/engine/v1` package — residual gap (87.1% line / 85.4% branch)

68 uncovered lines remain. Both thresholds are met. The residual gap is
concentrated in:

- `BTreeSingleValueIndexEngine.doClearTree` / `BTreeMultiValueIndexEngine.doClearTree`
  error paths (the "removed 0 entries" throw is live but not hit by unit
  tests — it requires actual B-tree data with page invalidation)
- `BTreeSingleValueIndexEngine.rawKeyStreamForHistogram` empty-tree path
  (already partially covered; the non-empty path is covered by histogram tests)
- `VersionedIndexOps` — a few assert-guarded paths

No action required; both thresholds met.

## Coverage gate result

Gate command:
```
python3 .github/scripts/coverage-gate.py \
  --line-threshold 85 --branch-threshold 70 \
  --compare-branch origin/develop \
  --coverage-dir .coverage/reports
```

Result: **PASSED** (100.0% line / 100.0% branch on changed lines).
Track 18 is test-additive; no production-source lines appear in the diff.
