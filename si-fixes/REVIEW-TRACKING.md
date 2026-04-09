# SI Indexes — Second Code Review Fix Tracking

## Validation Summary

12 findings from the code review were investigated against the actual source code.
3 were found invalid, 9 are valid and have fix prompts.

### Invalid Findings (no action needed)

| # | Finding | Reason |
|---|---------|--------|
| 2 | IndexesSnapshot.clear() race with addSnapshotPair | Engine exclusive lock (`acquireAtomicExclusiveLock`) serializes all same-engine operations. Sub-map scoping prevents cross-engine interference. Eviction race is benign (idempotent removes + clamped counter). |
| 8 | extractKey allocates CompositeKey per visible entry | Already fast-pathed for single-field indexes (the common case — returns raw element, zero allocation). Composite-key allocation is structurally necessary. Not fixable without deep refactor. |
| 11 | IndexCountDelta public fields inconsistent with HistogramDelta | Required — `AbstractStorage` (different package) accesses `delta.totalDelta` and `delta.nullDelta` directly. `HistogramDelta` uses a different pattern (passes whole object to same-package manager). |

## Fix Status

| ID | Severity | Title | Prompt File | Status | Commit |
|----|----------|-------|-------------|--------|--------|
| R-01 | should-fix | Document eager counter update semantics on rollback | `review-fix-01-counter-rollback.md` | [ ] TODO | — |
| R-02 | should-fix | Add getVisibleStream() for multi-value get() optimization | `review-fix-02-multivalue-get-visible.md` | [ ] TODO | — |
| R-03 | suggestion | Extract shared versioned put/remove into VersionedIndexOps | `review-fix-03-dry-doput.md` | [ ] TODO | — |
| R-04 | suggestion | Unify duplicated extractKey() into parameterized helper | `review-fix-04-dry-extract-key.md` | [ ] TODO | — |
| R-05 | suggestion | Initialize approximate count from TREE_SIZE on upgrade | `review-fix-05-upgrade-count-init.md` | [ ] TODO | — |
| R-06 | suggestion | Replace @Nullable Optional with two doPutSingleValue overloads | `review-fix-06-nullable-optional.md` | [ ] TODO | — |
| R-07 | suggestion | Add visibilityFilterValues() to avoid RawPair allocation | `review-fix-07-visibility-filter-values.md` | [ ] TODO | — |
| R-08 | suggestion | Recalibrate null count in multi-value buildInitialHistogram | `review-fix-08-null-count-recalibrate.md` | [ ] TODO | — |
| R-09 | minor | Avoid unnecessary CompositeKey allocation when rangeTo is null | `review-fix-09-rangeto-null-alloc.md` | [ ] TODO | — |

## Dependencies

- **R-04** depends on **R-03** (extractKey helper goes into VersionedIndexOps if created)
- **R-07** is partially superseded by **R-02** (if getVisibleStream() is done, visibilityFilterValues is lower priority for get(), but still useful for other callers)
- **R-08** can optionally use `visibilityFilterValues()` from **R-07** (falls back to `.count()` otherwise)
- All other fixes are independent

## Recommended Order

1. R-01 (documentation — no code logic changes, safe first step)
2. R-09 (minor, standalone, quick win)
3. R-06 (code quality, standalone)
4. R-05 (upgrade safety, standalone)
5. R-03 (DRY refactor — larger change)
6. R-04 (DRY, builds on R-03)
7. R-07 (performance, standalone)
8. R-08 (null count recalibration, optionally uses R-07)
9. R-02 (performance, largest change — new BTree method + tests)

## Completion Checklist

After all fixes:
- [ ] Run full unit test suite: `./mvnw -pl core clean test`
- [ ] Run spotless: `./mvnw -pl core spotless:apply`
- [ ] Run integration tests: `./mvnw -pl core clean verify -P ci-integration-tests`
- [ ] Force-push branch with `--force-with-lease`
