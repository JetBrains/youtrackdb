# Track 10 — Query & Fetch Coverage Baseline

Post-Phase-A dead-code exclusion for the `core/query`, `core/query/live`,
and `core/fetch` scope. This file is auditable tracking only — the
canonical LOC accounting for the 85%/70% gate lives in the track's
coverage-gate run at Step 4.

## Pre-Track baseline (from `coverage-baseline.md` / plan)

| Package | Line % | Branch % | Uncovered Lines |
|---|---|---|---|
| `core/query` | 38.8% | n/a | 237 |
| `core/query/live` | 13.4% | n/a | 272 |
| `core/fetch` | 46.6% | n/a | 248 |

## Dead-LOC exclusion (pinned in Steps 2 and 3 via `*DeadCodeTest` classes)

Pins are falsifiable observable-behavior tests tagged
`// WHEN-FIXED: Track 22 — delete <class>` (or equivalent). Track 22
removes the class or the SPI; this file then falls out of scope.

### `core/query/live` — entire package (dead)

Cross-module grep (Phase A iter-1 synthesis): 0 production callers in
`server/`, `driver/`, `embedded/`, `gremlin-annotations/`, `tests/`
outside the `core/` subtree. Only `LiveQueryHookV2.unboxRidbags` is
live — exercised via `CopyRecordContentBeforeUpdateStep.java:52`.

| Pinned region | Reason | LOC |
|---|---|---:|
| `LiveQueryHook` (all public-static surface) | zero production callers for `subscribe`/`unsubscribe`/`addOp`/`notifyForTxChanges`/`removePendingDatabaseOps`/`hasListeners`/`hasToken` | ~164 |
| `LiveQueryHookV2` (all public-static surface minus `unboxRidbags`) | zero production callers for `subscribe`/`unsubscribe`/`addOp`/`notifyForTxChanges`/`removePendingDatabaseOps`/`calculateBefore`/`calculateProjections` | ~250 (326 − 76 for live `unboxRidbags`) |
| `LiveQueryQueueThread` (V1 thread + run loop) | reached only via dead `LiveQueryHook` subscribers | ~107 |
| `LiveQueryQueueThreadV2` (V2 thread + run loop) | reached only via dead `LiveQueryHookV2` subscribers | ~94 |
| `LiveQueryListener` / `LiveQueryListenerV2` (interfaces) | zero impls in core | ~30 + ~33 |

### `core/query` orphan listener interfaces (dead)

| Pinned region | Reason | LOC |
|---|---|---:|
| `BasicLiveQueryResultListener` | zero production implementors | ~43 |
| `LiveQueryResultListener` | zero production implementors | ~8 |
| `LiveQueryMonitor` | zero production implementors | ~11 |

### `core/fetch` — entire package (dead)

Cross-module grep (Phase A iter-1 synthesis): 0 production callers in
`server/`, `driver/`, `embedded/`, `gremlin-annotations/`, `tests/`.
Only `DepthFetchPlanTest` (test-only) exercises it in `core`. Historical
binary-protocol fetch path no longer wired in.

| Pinned region | Reason | LOC |
|---|---|---:|
| `FetchHelper` (all static methods) | zero production callers for `fetch`/`isEmbedded`/`buildFetchPlan`/`checkFetchPlanValid`/`processRecordRidMap`/`removeParsedFromMap` | ~1027 |
| `FetchPlan` (parser + has + getDepthLevel) | parser exercised only by `DepthFetchPlanTest` | ~253 |
| `FetchContext` / `FetchListener` (interfaces) | zero cross-module implementors | small |
| `core/fetch/remote/RemoteFetchContext` + `RemoteFetchListener` | no-op callbacks; no callers | small |

**Estimated dead-LOC exclusion across the three packages: ≈ 2,200 LOC**
(cross-module grep confirmed; deletion scheduled in Track 22).

## Live target denominator post-exclusion

| Area | Live LOC (approx) | Notes |
|---|---:|---|
| `core/query` (live subset) | ~110 | `QueryHelper.like` (31 lines) + `BasicResultSet` default methods (~95 lines) + `QueryRuntimeValueMulti` (~40 lines) |
| `core/query/live` (live subset) | ~20 | `LiveQueryHookV2.unboxRidbags` only |
| `core/fetch` (live subset) | ~0 | entire package dead |
| `core/sql/executor/ExecutionStep.toResult` (interface default) | ~13 | one-method default; tested standalone |

## Additional covered targets (not dead)

| Target | Class/File | Rationale |
|---|---|---|
| `ExecutionStep.toResult` default | `core/query/ExecutionStep.java` | live via `InsertExecutionPlan`, etc. Covered by `ExecutionStepToResultTest`. |
| `QueryRuntimeValueMulti` | `core/query/QueryRuntimeValueMulti.java` | live via filter/operator evaluation. Standalone test. |
| `BasicResultSet` default methods | `core/query/BasicResultSet.java` | inherited by `ResultSet` and children; covered standalone with a package-private stub `TestResultSet`. |
| `Result.asEntity/asVertex/asEdge/asBlob` dispatch | `core/query/Result.java` | live via `session.query("SELECT FROM V")`. DB-backed test. |

## Verification method (Step 4)

At Step 4 the `coverage-gate.py` runs over changed lines only. The gate's
85%/70% thresholds are measured against *live* code; pinned dead classes
are excluded because the dead-code tests add them to the coverage
denominator anyway via direct exercise (interface/class-shape pins).
Track 22 deletes the pinned code, at which point the denominator shrinks
and the aggregate package coverage rises naturally.
