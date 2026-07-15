# Technical Review R4 — Chapters 11, 12, 15
Reviewer: R4 (factual accuracy against live source tree, 2026-04-23)

---

## Chapter 11 — The Step Pipeline

### Summary
The chapter is structurally sound. Every step class named (`MatchFirstStep`, `MatchStep`,
`MatchPrefetchStep`, `OptionalMatchStep`, `RemoveEmptyOptionalsStep`,
`FilterNotMatchPatternStep`, all four return steps) exists in the source tree. The described
contracts — prefetch cache check, `flatMap`-then-traverser delegation, sentinel-based optional
semantics, NOT nested-loop via `ChainStep` inner class — are all faithful to the
implementation. Three issues need correction before publication.

### Issues

**[fix] `MatchStep.java:86–91` code snippet truncated by one line.**
The chapter shows the closing brace of `internalStart` as part of line 91. The method body
spans lines 86–92 in the live file (the closing `}` is line 92). The snippet itself is
correct; only the cited range is one line short. Update to `86–92`.

**[fix] `MatchFirstStep.java:112–114` range is slightly off.**
The chapter states "`$matched` is set … at line 114". The `data.map(…)` call begins at line
110; the lambda body with `setProperty` is at line 113 and `setSystemVariable(VAR_MATCHED)`
is at line 114. The description is accurate but the cited range should be `110–116` to cover
the whole wrapping expression, or at minimum changed from `112` to `110` as the start of that
block. As written, citing line 112 points to the first line of the lambda body, which is
`var newResult = …` — not the `setProperty` call — so a reader following the cite will land
on the wrong line.

**[nit] `MatchResultRow.getProperty` snippet omits the guard assertion.**
The chapter shows the three-branch `getProperty` starting at line 88 but omits the
`assert checkSession()` call on line 89. The omission makes the snippet accurate for teaching
but inconsistent with the source. A note such as "assertion omitted for clarity" would prevent
confusion if a reader is tracing exact line 89.

---

## Chapter 12 — Traversers

### Summary
All six traverser strategies are correctly identified and their class hierarchy matches the
source. The behavioral descriptions — alias swap in `MatchReverseEdgeTraverser`, sentinel
lifecycle in `OptionalMatchEdgeTraverser`, field-expression evaluation without `applyPreFilter`
in `MatchFieldTraverser`, two-level loop in `MatchMultiEdgeTraverser`, back-reference equality
check in `MatchEdgeTraverser.computeNext` — are factually accurate. Line numbers in the
Further Reading section are accurate for `computeNext` (212), `executeTraversal` (347),
`traversePatternEdge` (530), and `applyPreFilter` (556). Two issues found.

### Issues

**[fix] `MatchStep.java:97–98` range for `createNextResultSet` is off by one.**
The chapter cites lines 97–98 for the two-line method body, but the method spans three lines
(97: signature, 98: body, 99: closing brace). The cited range should be `97–99`. Minor, but
a reader following the cite will miss the closing brace and may think the snippet is
incomplete.

**[fix] `MatchFieldTraverser` method override line is cited as 44 but is 45.**
The chapter's Further Reading entry states "field-expression traversal (line 44)". In the
live file, line 44 is `@Override` and line 45 is the `protected ExecutionStream
traversePatternEdge(…)` signature. The body reference should point to line 45. The inline
prose section (§12.6) says "overrides only `traversePatternEdge` (line 44 in
`MatchFieldTraverser.java`)" — same discrepancy. Update both occurrences to line 45.

**[nit] `CommandContext` variable description ordering.**
§12.9 introduces the variables in the order `$matched`, `$currentMatch`, `$current` and
describes `$currentMatch` as "Written immediately before calling `matchesFilters`". The actual
write-point is inside the `filter()` method (which calls `matchesFilters`), not inside
`executeTraversal` — the Javadoc table in `MatchEdgeTraverser` says "`$currentMatch` | set
by `executeTraversal`", but the code sets it inside the private `filter()` helper called from
`executeTraversal`. The description in §12.9 is effectively correct; the discrepancy is only
with the source Javadoc, so no text change is required in the chapter, but an editor reviewing
the Javadoc should fix the attribution.

---

## Chapter 15 — Nine Queries

### Summary
The nine walkthrough queries are internally self-consistent and the cross-references to
Chapters 9–14 are all correct: citations of Chapter 9 for cardinality estimation, Chapter 10
for scheduling, Chapter 11 for steps, Chapter 12 for traversers, Chapter 13 for hash joins,
and Chapter 14 for index pre-filters all correspond to what those chapters actually cover. The
`MatchExecutionPlanner` line citations for `manageNotPatterns` (673), `splitDisjointPatterns`
(4185), `dependsOnExecutionContext` (643), `estimateRootEntries` (4775),
`getHashJoinThreshold` (338), and `getHashJoinUpstreamMin` (348) are all accurate. One
blocker and one fix found.

### Issues

**[blocker] Phase 2 misidentified in §15.6.**
Section 15.6 states: "Phase 2 (`collectAliasesFromWhilePatterns`,
`MatchExecutionPlanner.java:4403`) identifies `reached` as a WHILE alias and quarantines it."
This is wrong on both counts. In the live planner source, Phase 2 is `splitDisjointPatterns()`
(line 492, comment "Phase 2: Identify disconnected sub-graphs…"). The call to
`collectAliasesFromWhilePatterns` (line 4403) occurs inside `buildPatterns()`, which is Phase
1. The "quarantine" mechanism works by inflating the alias estimate to `Long.MAX_VALUE` in the
post-Phase-3 estimate map, not by removing the alias from root candidates during Phase 2. The
correct description is: Phase 1 (`buildPatterns`, which internally calls
`collectAliasesFromWhilePatterns`) marks WHILE aliases for class-inference suppression; then
after Phase 3 (`estimateRootEntries`), the planner inflates those aliases' estimates to
`Long.MAX_VALUE` (lines 503–505) so they never win the root competition. The chapter's
statement that this happens in Phase 2 will mislead a reader tracing the code.

**[fix] `MatchExecutionPlanner.java:328` cited as prefetch threshold location, but the
constant `THRESHOLD` is at line 328 while the chapter claims it is in an expression at
"line 328".**
Both §15.1 and §15.2 say "prefetch threshold at line 328 (`MatchExecutionPlanner.java:328`)".
That line is `private static final long THRESHOLD = 100;` — the declaration of the constant,
not its use. The citation is accurate for "where the threshold value is defined", but the
prose says "the planner will emit a `MatchPrefetchStep` ahead of `MatchFirstStep`" which
suggests readers might look for the decision code there. The actual prefetch decision (filtering
by `THRESHOLD`) is at lines 511–515. A parenthetical like "threshold declared at line 328,
applied at lines 511–515" would remove ambiguity. As-is this is not factually wrong but will
confuse readers who go looking for the emit-decision at line 328.

---

## Cross-chapter consistency

All chapter cross-references verified consistent: Chapter 11 correctly points readers to
Chapter 12 for traversers; Chapter 12 correctly points to Chapters 11, 13, and 14; Chapter 15
correctly cites Chapters 6, 9, 10, 11, 12, 13, and 14 for the features used in each query.
No phantom chapter references or numbering mismatches found.
