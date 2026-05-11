# Technical Review — Chapters 8, 9, 10

**Reviewer**: R3 (factual accuracy against source tree)
**Date**: 2026-04-23
**Source base**: `develop` branch, commit `cca739f215`

---

## Chapter 8 — Counting Without Counting: Cardinality, Selectivity, and Fan-out

### Issues

**[nit] Two overloads of `estimateFilterSelectivity()` — description conflates them**

The chapter states (§ "Selectivity: the fraction that passes"):

> "the MATCH planner reaches it through the private method `estimateFilterSelectivity()`
> (`MatchExecutionPlanner.java:2551`)"

There are two distinct overloads:

1. `private static double estimateFilterSelectivity(@Nullable SQLWhereClause where, @Nullable String className, CommandContext ctx)` at **line 2399** — used inside `applyTargetSelectivity`. This is genuinely `private` but does _not_ contain AND/OR composition; it delegates to `TraversalPreFilterHelper.findIndexForFilter` and falls back to `SelectivityEstimator.defaultSelectivity()`.

2. `static double estimateFilterSelectivity(SQLWhereClause filter, long classCount, @Nullable SchemaClassInternal schemaClass, @Nullable DatabaseSessionEmbedded session)` at **line 2551** — package-private (not `private`). This is the overload that contains the AND-multiply / OR-inclusion-exclusion logic the chapter describes, and is called from `applyTargetSelectivity` indirectly when the simpler overload cannot produce a histogram estimate.

The line number cited (2551) points to the correct overload for the AND/OR formula description, so the substance of the section is accurate. The word "private" is wrong for the 2551 overload (it is package-private / `static`), and readers who search for "private method" in an IDE will find the wrong one first.

**Suggested fix**: replace "private method" with "static helper" and add a parenthetical noting the two overloads.

---

**[nit] `GlobalConfiguration` constant name mismatch in cost-formula prose**

The prose says the `randomPageReadCost` constant defaults to 4.0 (`GlobalConfiguration.java:1263`). The actual enum entry is named `QUERY_STATS_COST_RANDOM_PAGE_READ` (not `randomPageReadCost`). The `randomPageReadCost()` name belongs to the helper method in `CostModel.java:67`, not to the configuration key. The line number 1263 is correct. This is a naming-only inconsistency that could confuse readers searching the source.

**Suggested fix**: name the constant `QUERY_STATS_COST_RANDOM_PAGE_READ` (the GlobalConfiguration key) and separately note the `CostModel.randomPageReadCost()` accessor.

---

### Summary

Chapter 8's core content — the three-tier SelectivityEstimator, the BOTH-direction fan-out logic, the AND/OR selectivity composition, the fallback constants and their GlobalConfiguration line numbers, the `CostModel.edgeTraversalCost` formula, and the clamp at `SelectivityEstimator.java:708` — all check out against the source. The two issues above are naming/scoping nits that do not affect the correctness of any described formula or algorithm.

---

## Chapter 9 — Choosing Where to Start: Root Selection

### Issues

No blockers or fixes found. All cited line numbers, code snippets, and algorithmic descriptions were verified against the source:

- `estimateRootEntries()` signature and four rules (`MatchExecutionPlanner.java:4775–4824`) — correct.
- `classCount + 1` bias at line 4819 — confirmed.
- `Math.min(filter.estimate(...), classCount)` at line 4815 — confirmed.
- Inflation loop at lines 503–506 — confirmed, including `Long.MAX_VALUE` overwrite and guard `containsKey`.
- Zero-cardinality short-circuit at lines 519–523 — confirmed, including `EmptyStep` and optional-alias exemption.
- `inferClassFromEdgeSchema()` at line 4558 — confirmed.
- `collectAliasesFromWhilePatterns()` at line 4439 — confirmed.
- `addAliases()` class-inference snippet at lines 4520–4521 — confirmed exactly.
- `isBidirectional()` at `SQLMatchPathItem.java:58` — confirmed, including the three modifiers (`while`, `maxdepth`, `optional`) and the delegate to `method.isBidirectional()`.
- `estimatedRootEntries` returned as `LinkedHashMap` (line 4788) — confirmed.
- `PairLongObject` sort and `remainingStarts` construction at lines 1956–1968 — confirmed.
- `THRESHOLD = 100` at line 328 — confirmed.

**[nit] Minor wording imprecision: "Rule 4 / alias omitted"**

The chapter (§9.1, Rule 4) says an alias with neither class nor RID "does not appear in the returned map at all". The code at 4798–4800 confirms this. The chapter also says "The scheduler will still consider it as a root candidate — it appends all aliases from `pattern.aliasToNode.keySet()` after the estimated ones at line 1968." This is correct. The nit: line 1968 is `remainingStarts.addAll(pattern.aliasToNode.keySet())`, which is a `LinkedHashSet` — the `addAll` only inserts keys not already present (since `LinkedHashSet` deduplicates), so aliases that were already added via `rootWeights` are not re-inserted. The chapter's description implies a clean two-phase append that is consistent with this, so no correction is required — it could be made slightly more precise but is not wrong.

---

### Summary

Chapter 9 is factually correct throughout. Every cited line number was confirmed in the live source. The MAX_VALUE inflation, the zero-cardinality short-circuit, and the tie-breaking via `LinkedHashMap` insertion order are all described accurately.

---

## Chapter 10 — Scheduling the Walk: Order and Direction

### Issues

**[fix] `createPlanForPattern` line number is wrong**

The chapter (§10.9, "Step Generation") states:

> "`createPlanForPattern` (`MatchExecutionPlanner.java:1763`)"

The actual method declaration is at **line 1775**. The discrepancy is 12 lines. Not a blocker but it will cause readers to land at the middle of the preceding method when jumping from the text.

**Suggested fix**: update to line 1775.

---

**[nit] `estimateEdgeCost` result described as fed directly into cost — misses intermediate call chain**

The chapter (§10.4 formula) presents:

```
edge_cost = base_cost × target_selectivity × depth_multiplier
```

and says `base_cost = CostModel.edgeTraversalCost(...)`. The actual call chain in `updateScheduleStartingAt` (lines 2118–2124) is:

```java
cost = estimateEdgeCost(edge, sourceAlias, sourceRows, aliasClasses, session);
if (cost < Double.MAX_VALUE) {
  cost = applyTargetSelectivity(cost, ...);
  cost = applyDepthMultiplier(cost, ...);
}
```

`estimateEdgeCost` (line 2291) calls `CostModel.edgeTraversalCost` internally. The chapter's formula is correct; the nit is that `estimateEdgeCost` is an intermediary wrapper that also guards against `Double.MAX_VALUE` (a schemaless edge with unknown fan-out) before passing to the adjustment methods. The chapter could mention that `Double.MAX_VALUE` means "unknown cost / skip adjustment" to avoid confusion when readers inspect the source.

---

**[nit] `applyTargetSelectivity` described as inspecting "WHERE AST shape" directly**

The chapter (§10.4.3) says `applyTargetSelectivity` "inspects the WHERE AST shape: a simple equality reduces the cost by roughly `1 / distinctCount`". The actual implementation (lines 2460–2501) first calls the `estimateFilterSelectivity(filter, classCount, schemaClass, session)` helper (the line-2551 overload) which handles AND/OR composition; it falls back to a ratio `targetEstimate / classCount` from the pre-computed cardinality map only when `estimateFilterSelectivity` returns a negative value. The description is a correct high-level summary but omits the delegation to `estimateFilterSelectivity` and implies more direct AST inspection than actually occurs.

---

### Line-number spot-check (confirmed correct)

| Claim | Cited line | Actual line | Status |
|---|---|---|---|
| `getTopologicalSortedSchedule` | 1945 | 1945 | ✓ |
| `updateScheduleStartingAt` | 2032 | 2032 | ✓ |
| `estimateEdgeCost` | 2291 | 2291 | ✓ |
| `applyTargetSelectivity` | 2460 | 2460 | ✓ |
| `applyDepthMultiplier` | 2814 | 2814 | ✓ |
| `DEFAULT_WHILE_DEPTH = 10` | 2830 | 2830 | ✓ |
| `getDependencies` | 4160 | 4160 | ✓ |
| `splitDisjointPatterns` | 4185 | 4185 | ✓ |
| `addStepsFor` | 4213 | 4213 | ✓ |
| `createPlanForPattern` | 1763 | **1775** | **WRONG** |
| `EdgeTraversal.isConsumed()` | 179 | 179 | ✓ |
| Back-ref direction code | 2205–2210 | 2205–2210 | ✓ |
| `CartesianProductStep` block | 530–538 | 530–538 | ✓ |
| `$matched` dep check | 2150–2158 | 2150–2158 | ✓ |
| `isBidirectional()` | `SQLMatchPathItem.java:58` | 58 | ✓ |

---

### Summary

Chapter 10's algorithmic content — the two-level DFS, the direction-decision matrix, the `isBidirectional()` check, the depth multiplier, the `$matched` dependency enforcement, the back-reference equality-check logic, and the `CartesianProductStep` for disjoint components — is all accurate and matches the source. The single fix needed is the `createPlanForPattern` line number (1763 → 1775). Two nits note that `estimateEdgeCost` guards against `Double.MAX_VALUE` before applying adjustments, and that `applyTargetSelectivity` delegates to a selectivity helper rather than inspecting the AST directly.
