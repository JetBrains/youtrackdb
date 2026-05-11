# Technical Review — Cycle 2

Reviewer: R-v2  
Date: 2026-04-23  
Scope: Ch. 8 §"Inside the histogram tier", Ch. 12 §12.7.1, Ch. 7 §7.9 + Ch. 17 updates

---

## Section 1 — Chapter 8: "Inside the histogram tier — bucket-level interpolation"

### Issues

**[nit-8-1] Formula presents `scalarize` as a single-argument function**

The chapter writes:

```
fraction = (scalarize(key) − scalarize(lower(b)))
         / (scalarize(upper(b)) − scalarize(lower(b)))
```

`ScalarConversion.scalarize` has signature `scalarize(Object value, Object lo, Object hi)`.
The `lo`/`hi` parameters affect the result for string keys (common-prefix stripping).
For numeric keys and dates the extra parameters are unused, so the worked example is
arithmetically correct. But a reader who follows the citation to
`ScalarConversion.java:63–77` will see a three-argument signature and may be confused.
Suggest updating the inline formula to `scalarize(key, lower(b), upper(b))` to match the
actual API.

**[nit-8-2] `fractionOf` intermediary not mentioned**

The chapter describes the path from `estimateGreaterThanHistogram` (lines 191–203)
directly to `continuousFraction` (lines 606–617), skipping the `fractionOf` helper at
line 552. `fractionOf` is not a pass-through: it contains the single-value bucket
optimisation (`distinctCounts[b] == 1`) which switches to discrete logic and returns
1.0, 0.0, or 0.5 depending on `FractionMode`, bypassing `continuousFraction` entirely.
The chapter implies `continuousFraction` always runs; in reality it only runs for
multi-value buckets. For the worked example (four buckets, 1 000 records each) the
single-value path is not taken, so the arithmetic is unaffected. Flag as a nit because
the worked example is self-consistent, but the general exposition misrepresents the
control flow.

**[nit-8-3] `remainingInB` uses `Math.max(h.frequencies()[b], 0)`**

The chapter states `remainingInB = (1.0 − fraction) × frequencies[b]`.
The actual code is `(1.0 - fraction) * Math.max(h.frequencies()[b], 0)`.
For the worked example `frequencies[b] = 1000 > 0`, so the result is identical.
The guard exists to handle negative-frequency buckets caused by delete-without-rebalance
drift. The omission is harmless for the example but is worth a sentence under "a word on
the approximation".

### Summary

The worked example is arithmetically correct: `findBucket(75)` returns 0-indexed bucket 1
(`[50, 100)`), fraction = 0.5, remainingInB = 500, aboveBuckets = 2000, selectivity =
2500/4000 = 0.625. All five steps in Table 8.2 match the code. The equi-depth property
("approximately the same count of records per bucket") is a standard definition correctly
stated. `findBucket` is 0-indexed (returns values in `[0, bucketCount - 1]`), which
matches `b = 1` for bucket `[50, 100)` — correct. `nonNullCount` is the correct
denominator; the Javadoc on `SelectivityEstimator` explicitly states that selectivity is
expressed as a fraction of non-null entries, and the chapter mirrors this. Three nits;
no blockers.

---

## Section 2 — Chapter 12: §12.7.1 `LazyRecursiveTraversalStream`

### Issues

**[blocker-12-1] `pathAlias`-without-`maxDepth` divergence concern is REAL and unguarded**

The chapter flags this as an open question. The answer from the code is: the concern is
real and there is no secondary cycle guard.

`LazyRecursiveTraversalStream.shouldExpand` (line 317) terminates expansion only when:
- `maxDepth != null && depths[idx] >= maxDepth`, OR
- `whileCondition != null && whileCondition.matchesFilters(...) == false`

When `pathAlias` is declared, `dedupVisited` is set to `null` (line 406 of
`MatchEdgeTraverser.java`), so the `if (dedupVisited != null)` guards at lines 227 and
280 are never entered. If `whileCondition` is an always-true predicate (e.g.,
`while: ($depth >= 0)`) on a graph with cycles, and `maxDepth` is absent, the traversal
loops forever. The entry guard at line 368 (`whileCondition == null && maxDepth == null
→ single-hop`) means this code path is only reachable when at least one of the two is
set, but neither alone is sufficient to guarantee termination on a cyclic graph when
`pathAlias` suppresses deduplication.

This is a real runtime correctness issue (infinite loop / OOM), not a documentation
inaccuracy. The chapter should state the finding explicitly: "There is no secondary cycle
guard. A user who declares `pathAlias` without `maxDepth` on a cyclic graph with a
non-terminating `while:` predicate will produce an infinite traversal. Mitigation:
always pair `pathAlias` with `maxDepth` when the graph may contain cycles."

**[fix-12-2] Line reference for `dedupVisited` guard in `pushNextNeighbor` is off by one**

Chapter says "lines 227, 280" for the `if (dedupVisited != null)` guards. The actual
lines are:
- `pushNextNeighbor`: `if (dedupVisited != null)` is at line 227 — correct.
- `pushFrame`: `if (!alreadyMarked && dedupVisited != null ...)` is at line 280 — correct.

Both citations are correct; this is a pre-emptive check to confirm no fix needed.

**[fix-12-3] Chapter says `dedupVisited` is passed to stream and guard is `if (dedupVisited != null)` in `pushFrame` — phrasing is imprecise**

The chapter says the root node is "added to the set in `pushFrame` (line 280)". This is
correct in outcome but the code path is: `alreadyMarked = false` for the root (constructor
at line 111 passes `false`), so `pushFrame` executes
`if (!alreadyMarked && dedupVisited != null ...) dedupVisited.add(rid)`. For neighbors,
`pushNextNeighbor` does the add via the `dedupVisited.add()` return value and passes
`alreadyMarked = true`, so `pushFrame` skips the add. The description in the chapter
("The root node is also added to the set in `pushFrame` (line 280)") captures the root
case correctly but could confuse readers into thinking `pushFrame` always adds to the
set. Minor wording fix warranted.

**[nit-12-4] `$depth` context variable not mentioned**

`pushFrame` sets `VAR_DEPTH` (line 263: `ctx.setSystemVariable(CommandContext.VAR_DEPTH,
depth)`) and `advance` also sets it at line 172 before calling `traversePatternEdge`.
The chapter discusses `$matchPath` metadata but does not mention `$depth` being set on
the context. Users who write `where: ($depth < N)` in a filter rely on this variable;
§12.7.1 would benefit from a sentence noting that `$depth` is updated per slot before
the SELF and NEIGHBORS phases.

### Summary

The DFS pre-order claim is correct and verified against the code: the `advance()` loop
yields `selfResults[top]` before expanding neighbors, which is pre-order DFS. The
parallel-array stack structure is correctly described. The `pathAlias` opt-out mechanism
(`dedupVisited = null` when `pathAlias != null`) is correctly described. The divergence
concern is real and confirmed: there is no secondary cycle guard when `pathAlias` is set
and `maxDepth` is unset on a cyclic graph. This is a blocker — the chapter must provide
a definitive answer rather than flagging uncertainty. Two fixes and one nit beyond the
blocker.

---

## Section 3 — Chapter 7: §7.9 plan/statement cache + Chapter 17 updates

### Issues

**[blocker-17-1] Table 17.1 describes `LazyRecursiveTraversalStream` as "BFS"**

Table 17.1 row for `LazyRecursiveTraversalStream.java` reads:
"Pull-based **BFS** stream for WHILE (recursive) patterns"

The implementation is DFS (depth-first, pre-order), as verified from the code and as
Chapter 12 itself correctly states. The glossary entry for `LazyRecursiveTraversalStream`
at §17.4 also says "performs a level-by-level **BFS**", which is wrong. Both must be
changed to "DFS" / "depth-first". This contradicts Chapter 12 within the same book.

**[fix-7-1] Chapter 7 lists three bypass conditions; the actual put-side guard has a fourth**

Section "When the plan cache is bypassed" states "Three conditions cause
`createExecutionPlan()` to skip the plan cache entirely". The three listed are: profiling
enabled, AST not cacheable, concurrent schema change detected.

The actual put-side guard at lines 627–631 of `MatchExecutionPlanner.java` has four
conditions:

```java
if (useCache
    && !enableProfiling
    && statement.executinPlanCanBeCached(session)
    && result.canBeCached()                          // MISSING from chapter
    && YqlExecutionPlanCache.getLastInvalidation(session) < planningStart)
```

`result.canBeCached()` delegates to `SelectExecutionPlan.canBeCached()` (line 280),
which returns `false` if any step in the assembled plan reports itself as non-cacheable.
The chapter should list this as a fourth bypass condition: "One or more execution steps
in the assembled plan signals non-cacheability via `canBeCached() == false`."

**[fix-7-2] SharedContext line citation is off**

Chapter 7 says the two caches live at `SharedContext.java:41–43`. The actual lines are:
`YqlStatementCache` at line 41, `YqlExecutionPlanCache` at line 43. The range `41–43`
is technically correct but there is also `GqlStatementCache` at line 42 between them,
which the range implies is also covered by the citation. Tighten to "lines 41 and 43" to
avoid confusion.

**[fix-7-3] `putInternal` line citation**

Chapter 7 cites `YqlExecutionPlanCache.java:104` for the `copy()` call. The actual line
is 104: `internal = internal.copy(ctx);`. Correct. The `close()` at line 106 and
`cache.put()` at line 107 are omitted from the citation but referenced in the surrounding
prose ("immediately closed"). No change needed, just confirming no error.

**[nit-7-4] `COMMAND_TIMEOUT` trigger is a cache clear via `getInternal()`, not a bypass**

The chapter places the `COMMAND_TIMEOUT` change trigger in a separate paragraph
("One additional trigger") after the bypass conditions. This is architecturally correct:
it fires on `getInternal()`, not at the put-side guard. The distinction is correctly
drawn in the prose. The line citation `YqlExecutionPlanCache.java:121–126` should be
`121–125` (the check and invalidate block ends at line 125, with line 126 being the
`lastGlobalTimeout` update). Minor.

**[nit-7-5] `executinPlanCanBeCached` spelling in prose**

The chapter uses the correct spelling of the method name `executinPlanCanBeCached` (one
`o` missing in "execution"), which matches the actual method name in the source. This is
a source-code typo that the book faithfully reproduces. Consider a parenthetical noting
the typo so readers searching the code find it.

### Summary

The two-cache design is correctly described: both caches are per-database via
`SharedContext`, both are sized by `STATEMENT_CACHE_SIZE` (default 100,
`GlobalConfiguration.java:952`), and the copy-on-read contract is accurately stated —
`putInternal()` stores a closed copy as a quiescent template, `getInternal()` returns
`result.copy(ctx)` to the caller. The `COMMAND_TIMEOUT` invalidation path is confirmed
at `YqlExecutionPlanCache.java:121–126`. The `MetadataUpdateListener` wholesale-clear
behaviour is confirmed (`onSchemaUpdate`, `onIndexManagerUpdate`,
`onFunctionLibraryUpdate`, `onSequenceLibraryUpdate`, `onStorageConfigurationUpdate` all
call `invalidate()`). One blocker (BFS vs DFS in Ch. 17), one fix (missing
`result.canBeCached()` bypass condition), and three minor items.

---

## Aggregate count

| Section | Blocker | Fix | Nit | Total |
|---|---|---|---|---|
| Ch. 8 histogram interpolation | 0 | 0 | 3 | 3 |
| Ch. 12 §12.7.1 `LazyRecursiveTraversalStream` | 1 | 1 | 2 | 4 |
| Ch. 7 §7.9 + Ch. 17 updates | 1 | 2 | 2 | 5 |
| **Total** | **2** | **3** | **7** | **12** |

---

## Answers to the three posed questions

**(a) Is the histogram arithmetic correct?**

Yes. The worked example in Table 8.2 is fully correct: `findBucket(75)` returns
0-indexed bucket 1 (`[50, 100)`), fraction = 0.5, remainingInB = 500,
aboveBuckets = 2000, selectivity = 2500/4000 = 0.625. All five steps match the
production code in `estimateGreaterThanHistogram` (lines 191–202). The formula
presentation omits the `fractionOf` intermediary and the three-argument signature of
`scalarize`, but these are presentation simplifications that do not affect the computed
values for numeric keys.

**(b) Is the `pathAlias`-without-`maxDepth` divergence concern real?**

Yes, it is real and confirmed. There is no secondary cycle guard when `pathAlias` is
declared. Setting `pathAlias` sets `dedupVisited = null`, disabling all RID
deduplication. Termination then depends solely on `maxDepth` or `whileCondition`
returning `false`. A cyclic graph with an always-true `while:` predicate and no
`maxDepth` will loop indefinitely. The chapter should state this finding definitively
as a usage constraint rather than leaving it as an open uncertainty.

**(c) Do the cache claims hold?**

Largely yes, with two corrections. The two-cache design, per-database ownership via
`SharedContext`, shared `STATEMENT_CACHE_SIZE` capacity, copy-on-read contract, and
`MetadataUpdateListener` wholesale invalidation are all confirmed against the source.
Two corrections are needed: (1) the bypass condition list omits `result.canBeCached()`
as a fourth put-side guard; (2) Table 17.1 in Chapter 17 describes
`LazyRecursiveTraversalStream` as "BFS" when it is DFS — this contradicts both Chapter
12 and the implementation.
