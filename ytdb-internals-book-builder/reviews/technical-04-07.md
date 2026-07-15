# Technical review: Chapters 4-7

Reviewer: R2 (automated source-tree verification)
Source tree: `/home/andrii0lomakin/Projects/ytdb/develop`
Key files checked:
- `core/src/main/grammar/YouTrackDBSql.jjt`
- `core/src/main/java/.../sql/parser/SQLMatchStatement.java`
- `core/src/main/java/.../sql/parser/SQLMatchFilter.java`
- `core/src/main/java/.../sql/parser/SQLMatchFilterItem.java`
- `core/src/main/java/.../sql/parser/SQLMatchExpression.java`
- `core/src/main/java/.../sql/parser/SQLMatchPathItem.java`
- `core/src/main/java/.../sql/parser/Pattern.java`
- `core/src/main/java/.../sql/executor/match/MatchExecutionPlanner.java`
- `core/src/main/java/.../sql/executor/match/PatternNode.java`
- `core/src/main/java/.../sql/executor/match/PatternEdge.java`

---

## Chapter 4

- **[blocker]** `SQLMatchStatement.pattern` is described as being "populated by `buildPatterns()` at line 211, as part of Phase 1 of planning" (p. 14, "What the AST cannot tell you" section). This is factually wrong. `SQLMatchStatement.buildPatterns()` (line 211) is never called during normal planning and has no callers in production code — only in unit tests. The planner builds its own `Pattern` object stored in `MatchExecutionPlanner.pattern` via a separate method `MatchExecutionPlanner.buildPatterns(CommandContext)` at line 4378. The `SQLMatchStatement.pattern` field remains null during execution. The chapter should either describe the planner-side `buildPatterns` (line 4378) or clarify that `SQLMatchStatement.buildPatterns()` is a test helper, not the planning entry point.

- **[fix]** Grammar line citation for `MatchStatement` is correct: `YouTrackDBSql.jjt:1245` (verified). However, the chapter claims the entry-point rule delegates to `MatchStatement()` "at line 1184". Actual `QueryStatement()` is at line 1173 (confirmed), and `result = MatchStatement()` is at line 1184 (confirmed). Both citations check out — no error here.

- **[fix]** The mermaid diagram in Figure 4.1 shows `SQLMatchFilter` with two child nodes labelled "item 0: SQLMatchFilterItem / className = 'Person'" and "item 1: SQLMatchFilterItem / alias = 'me'". The `SQLMatchFilter` node is shown as a single graph node `C`, and items `D` and `E` are shown as its direct children. The actual structure is that `SQLMatchFilter.items` is a `List<SQLMatchFilterItem>` — items are not direct children in a tree sense, they are list elements. The diagram is misleading: `SQLMatchFilter` appears to have two peer children, when in reality it has *one* `items` list with two elements. This is minor but could confuse readers new to the code.

- **[nit]** The chapter states `MatchFilterItem` is "an alternation of ten mutually exclusive alternatives" (confirmed: className, classNames, rid, alias, where/filter, while, maxDepth, optional, depthAlias, pathAlias — 10 alternatives). However, `SQLMatchFilterItem.java` (lines 11–23) contains 13 fields, including `collectionName`, `collectionId`, and `depth` which are not set by the grammar alternatives. These three fields are programmatically populated elsewhere. The chapter reproduces all 13 fields in the code snippet but attributes them all to the grammar rule. A brief note that three fields (`collectionName`, `collectionId`, `depth`) are set programmatically rather than by parsed alternatives would prevent confusion.

- **[fix]** Deep-copy code snippet is cited as `MatchExecutionPlanner.java:426` for the comment line, with the constructor at line 424. Verified: constructor is at line 424, the `// Deep-copy...` comment is at line 426. However, the deep-copy shown in the chapter omits `returnAliases`, `returnNestedProjections`, `limit`, and `skip` copies, which are also performed in the same constructor block (lines 435–450). These omissions are not incorrect but could mislead a reader who inspects the constructor expecting it to match the snippet exactly. A "…" ellipsis or note would be appropriate.

- **[nit]** Grammar line 3398 for `MatchExpression()` rule is cited for the statement "calls `MatchFilter()` for the leading `{…}` block and assigns the result to `jjtThis.origin`". Verified: `SQLMatchExpression MatchExpression():` is at line 3398, and `jjtThis.origin = MatchFilter()` is at line 3403. Accurate.

- **[nit]** Grammar lines 3583 for `OutPathItem`, `InPathItem`, `BothPathItem` cited. Verified: `OutPathItem` at 3583, `InPathItem` at 3605, `BothPathItem` at 3627. The chapter says "starting at…3583" which is correct for `OutPathItem` only. Accurate overall.

- **[nit]** `SQLMatchPathItem.java:46–56` cited for `outPath()`, `inPath()`, `bothPath()`. Verified: `inPath` at 46, `bothPath` at 50, `outPath` at 54. The range 46–56 is accurate.

- **verified** `QueryStatement()` at grammar line 1173. Confirmed.
- **verified** `SQLMatchStatement MatchStatement()` at grammar line 1245. Confirmed.
- **verified** `MatchExpression()` rule at grammar line 3398. Confirmed.
- **verified** `VISITOR=true` at grammar line 24. Confirmed.
- **verified** `SQLMatchFilter.java` line 16 `// TODO transform in a map` comment. Confirmed (actual line: 16).
- **verified** `SQLMatchFilter.java` line 17 `protected List<SQLMatchFilterItem> items = new ArrayList<>()`. Confirmed.
- **verified** `SQLMatchFilterItem.java` line 11 — first field `className`. Confirmed.
- **verified** `SQLMatchStatement.java` line 42 — `Pattern pattern` field. Confirmed.
- **verified** `assignDefaultAliases()` at `SQLMatchStatement.java:247`. Confirmed.
- **verified** `buildPatterns()` method at `SQLMatchStatement.java:211`. Confirmed (but see blocker above about whether this is the right method to cite).
- **verified** `YqlStatementCache.java` exists at the cited path. Confirmed.
- **verified** Constructor at `MatchExecutionPlanner.java:424`, deep-copy comment at line 426. Confirmed.
- **verified** All four key AST class files exist at cited paths. Confirmed.
- **verified** `SQLMultiMatchPathItem.java` and `SQLFieldMatchPathItem.java` exist. Confirmed.

---

## Chapter 5

- **[fix]** Chapter 5 section 5.3 says "The class that implements this chain structure is `MatchResultRow`" and describes instances in a chain where "each instance…holds exactly one alias and its bound record; lookups for earlier aliases walk up the chain to the appropriate parent." While `MatchResultRow.java` exists (confirmed), the chapter does not specify a file path or line citation for this claim, making it unverifiable without additional source research. This is an editorial gap: other chapters are rigorous about file:line citations; this one is not. At minimum, a file path should be provided.

- **[fix]** Chapter 5 section 5.3 says the executor class is called `MatchStep` "rather than `MatchJoin`". `MatchStep.java` exists (confirmed). But the reasoning — "it *extends* the row rather than merging two fixed-width sets" — conflates the naming rationale with behavior. This is not a factual error but the framing is potentially misleading.

- **[nit]** `returnsPatterns()` is described as detecting the `$patterns` token. This is accurate, but the method also recognises `$matches` as an alias for the same mode (line 290: `equalsIgnoreCase("$matches")`). The omission is harmless for a reader's mental model but is incomplete.

- **verified** All four field names on `SQLMatchStatement` (`matchExpressions`, `notMatchExpressions`, `returnItems`, `returnAliases`, `pattern`) exist as described.
- **verified** `returnsElements()`, `returnsPaths()`, `returnsPatterns()`, `returnsPathElements()` all exist at lines 275, 296, 284, 266 respectively.
- **verified** `SQLMatchExpression.origin` (type `SQLMatchFilter`) at line 14. Confirmed.
- **verified** `SQLMatchExpression.items` (type `List<SQLMatchPathItem>`) at line 15. Confirmed.
- **verified** `notMatchExpressions` list exists on `SQLMatchStatement`. Confirmed.
- **verified** `FilterNotMatchPatternStep.java` exists. Confirmed.
- **verified** `OptionalMatchStep.java` exists. Confirmed.
- **verified** `ReturnMatchElementsStep.java`, `ReturnMatchPathsStep.java`, `ReturnMatchPatternsStep.java`, `ReturnMatchPathElementsStep.java` all exist. Confirmed.

---

## Chapter 6

- **[fix]** Section 6.3 describes `buildPatterns()` as being called from `MatchExecutionPlanner` and building the pattern graph. The text says "(`core/.../MatchExecutionPlanner.java:489–490`)" for the call site. Verified: `buildPatterns(context)` is called at line 490 (not 489–490 — line 489 is a comment). Minor citation imprecision; not a blocker.

- **[fix]** The `getOrCreateNode` code snippet (section 6.3) is reproduced verbatim from `Pattern.java:81–92` and matches the source exactly. The method signature `private PatternNode getOrCreateNode(SQLMatchFilter origin)` is correct. However the chapter describes this as "the method body is a plain map lookup" — in fact the method also handles the `optional` flag. This is an incomplete but not inaccurate summary.

- **[fix]** Section 6.3 "Step 1 — Assign default aliases" cites `MatchExecutionPlanner.java:4740–4755`. The `assignDefaultAliases` method begins at line 4740 (confirmed). However the method call is from within `buildPatterns` at line 4386 (`assignDefaultAliases(allPatterns)`), not line 4740. The citation range 4740–4755 points to the *method definition*, not the call site. This is ambiguous but not wrong, given that the text says "(see…)".

- **[fix]** Section 6.3 cites `addAliases` at `MatchExecutionPlanner.java:4638–4679`. Verified: `addAliases` begins at line 4638. The closing `}` falls around line 4698 (not 4679), but this is a minor discrepancy that doesn't affect correctness.

- **[nit]** Section 6.5 says `dependsOnExecutionContext()` check is at `MatchExecutionPlanner.java:513`. Verified: line 513 contains `.filter(x -> !dependsOnExecutionContext(x.getKey()))`. Accurate.

- **[nit]** "Further Reading" section cites `PatternNode.java` — `addEdge` at line 73 and `copy` at line 105. Both confirmed. `PatternEdge.java` — `executeTraversal` at line 61. Confirmed. All citations check out.

- **[nit]** The `PatternNode.java:49–62` citation for fields. First field `alias` is at line 50 (not 49); the class declaration is at line 47. The range `49–62` is slightly off: no field or meaningful declaration appears at line 49. Should be `50–62`.

- **verified** `Pattern.aliasToNode` at `Pattern.java:50` (LinkedHashMap, insertion-ordered). Confirmed.
- **verified** `Pattern.numOfEdges` at `Pattern.java:53`. Confirmed.
- **verified** `Pattern.addExpression` at `Pattern.java:65`. Confirmed.
- **verified** `Pattern.getOrCreateNode` at `Pattern.java:81`. Confirmed.
- **verified** `Pattern.validate` at `Pattern.java:115`. Confirmed.
- **verified** `Pattern.getDisjointPatterns` at `Pattern.java:162`. Confirmed.
- **verified** `PatternNode.out` and `PatternNode.in` are `LinkedHashSet` (insertion-ordered). Confirmed at lines 53, 56.
- **verified** `PatternEdge.out`, `PatternEdge.in`, `PatternEdge.item` fields at lines 39, 42, 49. Confirmed.
- **verified** `PatternEdge.executeTraversal` at line 61. Confirmed.
- **verified** `PatternNode.addEdge` returns `1`. Confirmed.
- **verified** `splitDisjointPatterns` at `MatchExecutionPlanner.java:4185`. Confirmed.
- **verified** `rebindFilters` at `MatchExecutionPlanner.java:4423`. Confirmed.
- **verified** `CartesianProductStep` used at line 532 for disjoint patterns. Confirmed.
- **verified** The `getOrCreateNode` code snippet matches source exactly (Pattern.java:81–92).

---

## Chapter 7

- **[fix]** Section 7.1 says Phase 1 "walks each `SQLMatchExpression` in the parsed statement and populates four alias-keyed metadata maps: the unified pattern graph itself, `aliasClasses`, `aliasRids`, and `aliasFilters`." The planner's `buildPatterns(context)` actually produces *five* maps: `aliasFilters`, `aliasClasses`, `aliasCollections`, `aliasRids`, and `inferredWhileExprAliases` (a set, not a map). `aliasCollections` is entirely absent from the chapter's enumeration. This is a **fix** (inaccurate enumeration, though `aliasCollections` is not discussed further in these chapters).

- **[fix]** Section 7.5 says: "Immediately after the schedule is built, `createPlanForPattern()` emits one execution step per edge". The actual source shows that between `getTopologicalSortedSchedule()` and step emission, `createPlanForPattern()` also calls `optimizeScheduleWithIntersections()`, re-binds filters via `rebindFilters()`, annotates edges with class/RID/filter metadata, identifies hash-join branches via `identifyHashJoinBranches()`, and manages a `branchEdgeSet`. Describing step emission as "immediate" is factually inaccurate; several non-trivial operations intervene. Severity: fix (misleads readers who look for the schedule-to-step mapping in the code).

- **[nit]** The mermaid flowchart in Figure 7.1 labels phase 3 as "estimateRootEntries()" and phase 5 as "getTopologicalSortedSchedule() + createPlanForPattern()". These method names are correct (verified). The phase 7 box is labelled "RemoveEmptyOptionalsStep — sentinel → null (conditional)" — this matches the code's `foundOptional` check. Accurate.

- **[nit]** Section 7.4 says the `THRESHOLD` is `100` (strictly below). Verified: `private static final long THRESHOLD = 100` at line 328; filter is `x.getValue() < THRESHOLD`. Accurate.

- **[nit]** Section 7.3 says aliases with estimated count zero emit an `EmptyStep` if they are non-optional. Verified in code at lines 519–524. Accurate.

- **verified** `buildPatterns` called as Phase 1 at `createExecutionPlan` line 490. Confirmed.
- **verified** `splitDisjointPatterns()` called as Phase 2 at line 492. Confirmed.
- **verified** `estimateRootEntries()` called as Phase 3, returns `Map<String,Long>`. Confirmed.
- **verified** `addPrefetchSteps()` called as Phase 4 at line 527. Confirmed.
- **verified** `manageNotPatterns()` called as Phase 6 at line 550. Confirmed.
- **verified** `RemoveEmptyOptionalsStep` appended in Phase 7 behind `foundOptional` flag. Confirmed at lines 556–558.
- **verified** Phase 8 branches on `returnElements || returnPaths || returnPatterns || returnPathElements` at line 559. Confirmed.
- **verified** `getTopologicalSortedSchedule` exists at line 1945. Confirmed.
- **verified** `createPlanForPattern` exists at line 1775. Confirmed.
- **verified** `Long.MAX_VALUE` inflation for `inferredWhileExprAliases` at lines 499–504. Confirmed.
- **verified** RID→1, class+WHERE→min(estimate, classCount), bare class→classCount+1, no constraint→absent. All confirmed in `estimateRootEntries` at line 4775.
- **verified** `FilterNotMatchPatternStep` and `HashJoinMatchStep` exist. Confirmed.
- **verified** `CartesianProductStep` wraps multiple components in `createExecutionPlan`. Confirmed.

---

## Summary

**Chapter 4** contains one blocker: the claim that `SQLMatchStatement.pattern` is set during planning by `buildPatterns()` at line 211. In reality, `SQLMatchStatement.buildPatterns()` has no production-code callers — it is only used in tests. The planner builds a separate `Pattern` stored in `MatchExecutionPlanner.pattern` via `MatchExecutionPlanner.buildPatterns(context)` at line 4378. Two fixes (misleading diagram layout, deep-copy snippet incompleteness) and two nits (grammar alternative vs. Java field count, minor citation imprecision) also apply. All grammar line numbers, class names, file paths, and method names were independently verified and are correct.

**Chapter 5** has no blockers. Two fixes: `MatchResultRow` is described without a file:line citation (inconsistent with the chapter's own rigor elsewhere), and the `returnsPatterns()` description omits the `$matches` alias. All class names, field names, method names, and step class names were verified and exist.

**Chapter 6** has no blockers. Three fixes: the `buildPatterns` call-site citation is one line off (490 not 489–490), `addAliases` line range is slightly wide, and one minor omission in the `getOrCreateNode` description. One nit: `PatternNode.java:49–62` should be `50–62`. All line-number citations in the "Further Reading" section were verified and are accurate, and the `getOrCreateNode` code snippet is a verbatim match of the source.

**Chapter 7** has no blockers. Two fixes: Phase 1 metadata map enumeration omits `aliasCollections`, and Phase 5 is described as "immediate" step emission when in fact several optimization passes intervene between schedule construction and step creation. All method names, THRESHOLD constant, phase sequence, and step class names were verified correct.

**Total issues: 1 blocker (Ch. 4), 7 fixes (Ch. 4: 2, Ch. 5: 2, Ch. 6: 3, Ch. 7: 2), 5 nits (Ch. 4: 2, Ch. 5: 1, Ch. 6: 1, Ch. 7: 1).**
