# Track 11: Index-path synthetic column leak

## Purpose

Stop minted `_$$$ORDER_BY_ALIAS$$$_N` columns from reaching callers when an index
satisfies ORDER BY (`orderApplied = true` skips today's rebuild strip).

## Plan of work

1. Add `orderByMintAliases` (exact set) on `QueryPlanningInfo`; populate in
   `calculateAdditionalOrderByProjections`; set `internalAlias` on mint identifiers.
2. Add cacheable `RemovePropertyExecutionStep` using `ResultInternal.removeProperty`.
3. Chain it after user projection / after `handleOrderBy` when the mint set is
   non-empty, **before** `handleDistinct`, on Path A and Path B.
4. Tests: LET + sort-only index column-set (TQ1719); aggregate + index; DISTINCT
   cardinality; wildcard non-regression; deferred plain ORDER BY unchanged.

## Acceptance

- No visible property name starts with `_$$$ORDER_BY_ALIAS$$$_` on the repro shapes.
- Sort-only still uses `FetchFromIndexValuesStep` when eligible.
- Spotless + focused SQL planner/execution tests green.
