<!-- MANIFEST
findings: 12   severity: {blocker: 0, should-fix: 7, suggestion: 5}
index:
  - {id: CQ1, sev: should-fix, loc: "core/.../translator/step/MultiPlanMatchStep.java:230-450; core/.../translator/strategy/{RangeGlobalStepRecogniser,DedupGlobalStepRecogniser,GremlinAggregateAssembler}.java; core/.../strategy/UnionTraversalEquivalenceTest.java", cert: n/a, basis: "commit stat c6c66a62b9 + grep over core/src/test", anchor: "### CQ1 "}
  - {id: CQ2, sev: should-fix, loc: "core/.../translator/strategy/UnionStepRecogniser.java:16-28; core/.../translator/step/MultiPlanMatchStep.java:25-86", cert: n/a, basis: "source read + track-8.md DR-U4", anchor: "### CQ2 "}
  - {id: CQ3, sev: should-fix, loc: "core/.../translator/step/MultiPlanMatchStep.java:99-145", cert: n/a, basis: "grep for `new MultiPlanMatchStep` across core/src", anchor: "### CQ3 "}
  - {id: CQ4, sev: should-fix, loc: "core/.../translator/strategy/RangeGlobalStepRecogniser.java:33-101; core/.../translator/strategy/DedupGlobalStepRecogniser.java:38-98", cert: n/a, basis: "side-by-side source read", anchor: "### CQ4 "}
  - {id: CQ5, sev: should-fix, loc: "core/.../translator/step/MultiPlanMatchStep.java:228-450", cert: n/a, basis: "source read", anchor: "### CQ5 "}
  - {id: CQ6, sev: should-fix, loc: "core/.../translator/step/MultiPlanMatchStep.java:329-341", cert: n/a, basis: "source read + AbstractMatchPlanStep projectScalar", anchor: "### CQ6 "}
  - {id: CQ7, sev: should-fix, loc: "core/.../translator/strategy/GremlinToMatchTranslator.java:82-190; core/.../strategy/GremlinStepWalker.java:429-440; core/.../strategy/GremlinToMatchStrategy.java:440-465", cert: n/a, basis: "source read", anchor: "### CQ7 "}
  - {id: CQ8, sev: suggestion, loc: "core/.../translator/strategy/PostConcatSupport.java:51-53; core/.../translator/step/PostConcatOp.java:42; core/.../strategy/GremlinToMatchStrategyTest.java", cert: n/a, basis: "grep for isPushDownCountOnly across core/src", anchor: "### CQ8 "}
  - {id: CQ9, sev: suggestion, loc: "core/.../sql/executor/match/MatchExecutionPlanner.java:742-763; core/.../sql/executor/HardwiredCountOptimizations.java:73-135", cert: n/a, basis: "source read", anchor: "### CQ9 "}
  - {id: CQ10, sev: suggestion, loc: "core/.../translator/strategy/UnionStepRecogniser.java:139-141", cert: n/a, basis: "grep for rewriteReturnAlias across core/src", anchor: "### CQ10 "}
  - {id: CQ11, sev: suggestion, loc: "core/.../strategy/UnionTraversalEquivalenceTest.java:226-278", cert: n/a, basis: "grep for countBoundarySteps across core/src/test", anchor: "### CQ11 "}
  - {id: CQ12, sev: suggestion, loc: "core/.../common/profiler/monitoring/YTDBQueryMetricsStep.java:88-107", cert: n/a, basis: "source read", anchor: "### CQ12 "}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
cert_index: []
flags: [CONTRACT_OK, EVIDENCE_TRAIL_EXEMPT]
-->

## Findings

The four roster steps land a coherent, well-commented union path — `MultiPlanMatchStep`, the multi-plan carrier, `UnionForkHost`, and the recogniser each explain their invariants in prose a reviewer can check. No API-boundary violation (every new type is under `internal`), no missing `META-INF/services` registration, no JUnit-4/5 mixing. The quality debt clusters in the two follow-up commits that landed after the roster closed. `c6c66a62b9` (post-concat pipeline) added roughly 560 production lines across 13 files against 59 test lines, left the two class Javadocs it invalidated in place, and built its recogniser branches by copying the single-plan branches and deleting the comments. That is the cross-step drift signal, and it accounts for CQ1 through CQ5.

**Reference-accuracy caveat.** Per the known repo behaviour recorded for this branch, mcp-steroid `steroid_execute_code` times out here (cold Kotlin script compilation exceeds the 60 s MCP limit), so I did not attempt PSI. Findings whose load-bearing claim is "this symbol has no other caller" — CQ3, CQ8, CQ10, and the untested-path enumeration in CQ1 — rest on grep across `core/src` plus full declaration reads of every call site found. Grep can miss reflective or string-literal references; none of these symbols is a plausible reflection target, so the residual risk is low, but it is not zero.

### CQ1 [should-fix] Post-concat pipeline ships with no unit tests; the follow-up never extended `MultiPlanMatchStepTest`

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/MultiPlanMatchStep.java` (lines 230-450), `PostConcatOp.java`, `RangeGlobalStepRecogniser.java` (lines 70-101), `DedupGlobalStepRecogniser.java` (lines 74-98), `GremlinAggregateAssembler.java` (lines 72-95)

**Issue**: `c6c66a62b9` grew `MultiPlanMatchStep` by 274 lines and added `PostConcatOp` plus four recogniser branches, while adding 53 lines to `UnionTraversalEquivalenceTest` and 6 to `GremlinToMatchStrategyTest`. It left `MultiPlanMatchStepTest` — the 888-line unit-test home for exactly this class — untouched. The end-to-end suite covers three suffix shapes (`.count()`, `.dedup()`, `.limit(2)`), which leaves these new production paths with no test at all:

- `countConcatStream(...)` (~48 lines) — the non-push-down count. Reached only by `union(…).limit(n).count()` or `union(…).dedup().count()`; neither shape appears in any test.
- `SkipExecutionStream` (~32 lines) — constructed only when `range.skip() > 0`. `limit(2)` yields `Range(0, 2)`, so skip is always 0 in the suite.
- The skip-only branch of `RangeGlobalStepRecogniser.recognizePostUnion` (`new PostConcatOp.Range(low, -1L)`) — reached by `union(…).skip(n)`.
- The `for (PostConcatOp op : postConcatOps)` composition loop in `applyPostConcatOp` — every covered shape has exactly one op, so the loop never iterates twice.
- `PostConcatOp.Range`'s compact-constructor `skip < 0` throw.
- Every decline branch added by the follow-up: second range, range after count, second count, dedup after dedup or count.

The 85 % line / 70 % branch gate on changed code is a real risk here, independent of the correctness question.

**Suggestion**: Add unit tests to `MultiPlanMatchStepTest` over synthetic child plans for the three stream operators (`countConcatStream`, `SkipExecutionStream`, the multi-op composition), mirroring the `ListStream` double already in that file. Add `union(…).skip(n)`, `union(…).limit(n).count()`, and `union(…).dedup().count()` to `UnionTraversalEquivalenceTest`. Cover the recogniser decline branches with direct `recognize(cursor, ctx)` assertions in the style `OrderRangeStepRecogniserTest.secondLimit_declines` already uses.

### CQ2 [should-fix] Two class Javadocs contradict the behaviour the post-concat commit shipped

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/UnionStepRecogniser.java` (lines 16-28), `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/MultiPlanMatchStep.java` (lines 25-86)

**Issue**: `UnionStepRecogniser`'s class Javadoc still states that "a nested union inside a child, or any significant step after the union declines the whole walk." Since `c6c66a62b9` that is false — `count`, `limit`, `range`, `skip`, and `dedup` after the union are recognised as `PostConcatOp`s. The class doc contradicts an inline comment eleven lines below it in the same file ("Post-concat barriers (count / limit / dedup) may follow"), and it contradicts the revised DR-U4 in `track-8.md`. `OrderGlobalStepRecogniser` got its explanatory comment in the same commit; this one was missed.

Separately, `MultiPlanMatchStep`'s class Javadoc carries five `<h2>` sections covering concatenation, one-live-stream, per-child context, close-all, and clone — and never mentions the post-concat pipeline, which is now the class's second-largest responsibility (count push-down and summation, stream count, skip, limit, dedup). A reader of the class doc cannot discover that `startPlanStream()` may return something other than the plain concatenator.

CLAUDE.md § Comments and Documentation makes keeping comments in sync with behaviour a hard rule; a doc that states the opposite of the code is worse than none.

**Suggestion**: Rewrite the `UnionStepRecogniser` sentence to name the accepted suffix set and the still-declining cases (`order()`, list-shaping terminators, nested union). Add an `<h2>Post-concatenation reductions</h2>` section to `MultiPlanMatchStep`'s class doc describing the push-down-vs-stream-count split and the ordering guarantee `applyPostConcatOp` relies on.

### CQ3 [should-fix] Unused six-argument constructor whose Javadoc misdescribes it

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/MultiPlanMatchStep.java` (lines 111-122)

**Issue**: The `(traversal, returnClass, plans, boundaryAlias, outputType, shaping)` overload has no callers. Production splices through the seven-argument form in `GremlinToMatchStrategy:549`; all four test call sites use the five-argument form. Its Javadoc reads "Full constructor including row-projection shaping **and ordered post-concatenation reductions**", but the body passes `List.of()` for `postConcatOps` — the one thing the sentence promises is exactly what it does not do. Two of the three constructors are also both labelled "Full constructor", so the Javadoc gives no way to tell them apart.

**Suggestion**: Delete the six-argument overload. If it is being kept as a deliberate seam for Track 9's list-shaping terminators, say so in the Javadoc and correct the sentence to "with shaping and no post-concat reductions"; rename the seven-argument doc to something distinguishing ("Canonical constructor …").

### CQ4 [should-fix] Post-union recogniser branches are comment-stripped copies of the single-plan branches

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RangeGlobalStepRecogniser.java` (lines 33-101), `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/DedupGlobalStepRecogniser.java` (lines 38-98)

**Issue**: `RangeGlobalStepRecogniser.recognizePostUnion` repeats roughly 25 lines of `recognize` verbatim — the same `getLowRange`/`getHighRange` null check, the same `low < 0` decline, the same `high < 0 || high == Long.MAX_VALUE` unbounded test, the same `if (high < low) { high = low; }` clamp — differing only in the sink (`ctx.setSkip`/`ctx.setLimit` versus `ctx.appendPostConcatOp`). The copy dropped both explanatory comments the original carries: `// range(0, -1) / skip(0) is a no-op — accept without clauses.` and `// Native emits no traversers; LIMIT 0 matches that empty result.` A maintainer reading only the union branch sees an unexplained silent clamp and an unexplained bare `return ACCEPTED`.

`DedupGlobalStepRecogniser.recognizePostUnion` does the same with the scope-key loop: lines 84-95 repeat lines 49-69 and collapse the two distinct declines (`internalAlias == null` versus `!boundary.equals(internalAlias)`) into one condition, discarding the "Prior-hop labels would change uniqueness without changing the emitted object" rationale that justifies the second one.

The duplication is the maintenance hazard: a future fix to the range-normalisation rules has to be applied twice, and nothing in either file points at the other copy.

**Suggestion**: In `RangeGlobalStepRecogniser`, extract the parse-and-normalise step into a private helper returning a small carrier (for example `record NormalizedRange(long skip, long limit, boolean noop)` with a `null` return for decline) and have both branches consume it; the comments then live in one place. In `DedupGlobalStepRecogniser`, extract the scope-key validation into a `private static boolean scopeKeysNameOnlyBoundary(ctx, dedup)` used by both branches.

### CQ5 [should-fix] Duplicated count-stream scaffolding, and the whole post-concat operator family inlined in the boundary step

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/MultiPlanMatchStep.java` (lines 228-450)

**Issue**: `sumChildCountStreams()` (lines 269-327) and `countConcatStream(...)` (lines 359-406) are two anonymous `ExecutionStream` classes with an identical skeleton: a `pending` field, a `computed` latch, `hasNext`/`next`/`close` delegating to `ensure(ctx)`, and an identical terminal `new ResultInternal((DatabaseSessionEmbedded) ctx.getDatabaseSession()); result.setProperty("count", total);`. Only the accumulation inside `ensure` differs — sum the children's scalar rows versus count the concatenated rows. That is roughly 35 duplicated lines and a `"count"` string literal repeated in two places.

`MultiPlanMatchStep` is now 521 lines and owns five concerns: the N-plan concatenation and lifecycle it was designed for, plus count push-down summation, stream counting, skipping, and deduplication. The four post-concat operators are generic `ExecutionStream` decorators with no tie to the boundary-step lifecycle; only their construction site puts them in this class.

**Suggestion**: Extract a package-private `PostConcatStreams` (or one small named class per operator, matching how `SkipExecutionStream` is already a named nested class) holding the four operators plus a shared `singleCountRow(ctx, total)` factory for the duplicated terminal. `MultiPlanMatchStep.applyPostConcatOp` then reduces to a dispatch switch. That also gives the operators a natural unit-test home, which CQ1 needs.

### CQ6 [should-fix] `scalarCount` silently returns `0` on two malformed-row paths

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/MultiPlanMatchStep.java` (lines 329-341)

**Issue**: The helper has no Javadoc and two silent `return 0L` paths — a first non-boundary column that is not a `Number`, and a row carrying only the boundary column. For a count aggregation, `0` is the single worst fallback value: it is indistinguishable from a legitimate empty result, so a translator bug that mis-shapes a child's `RETURN count(*)` surfaces to the caller as "no matches" rather than as a failure. The sibling `next()` methods in the same file do throw `IllegalStateException` on their impossible states, so the file is inconsistent with itself about how it handles "cannot happen".

The method also leans on two couplings it never names: `AbstractMatchPlanStep.projectScalar`'s "first non-boundary RETURN column" convention, and `PostConcatSupport.rewriteToCountStar`'s guarantee of exactly one numeric column.

**Suggestion**: Add a Javadoc stating the two invariants it relies on (child was rewritten to a bare `RETURN count(*)`; the boundary column is the only other candidate) and throw `IllegalStateException` with the offending row's property names instead of returning `0L`, matching the `"no summed count row"` style already used ten lines above.

### CQ7 [should-fix] `TranslationResult` carries three parallel child lists plus a nullable `inputs`, with no `singlePlan` counterpart to `multiPlan`

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinToMatchTranslator.java` (lines 82-190), `GremlinStepWalker.java` (lines 429-440), `GremlinToMatchStrategy.java` (lines 440-465)

**Issue**: The record now has eleven components, of which four encode one concept: `childInputs`, `childInputParameters`, and `childCacheEligible` must stay index-aligned, and `inputs` must be `null` exactly when they are non-empty. The compact constructor spends five of its six validation branches policing that arrangement. `GremlinToMatchStrategy.buildChildPlans` then indexes all three lists by hand at lines 450-453. A `record ChildPlan(MatchPlanInputs inputs, Map<Object, Object> parameters, boolean cacheEligible)` and a single `List<ChildPlan>` would make three of those five validations structurally impossible and remove the index juggling. The three lists arrived in two separate commits (`660b3be634` added two, the cache follow-up added the third), which is exactly how parallel-list drift starts.

Separately, `multiPlan(...)` is a named factory but there is no `singlePlan(...)`. Both single-plan construction sites therefore call the eleven-argument canonical constructor with four bare `List.of()` arguments in a row — `GremlinStepWalker:431-434` and `GremlinToMatchStrategy:459-462` — where nothing at the call site says which empty list is which.

**Suggestion**: Fold the three parallel lists into one `List<ChildPlan>`. Add a `static TranslationResult singlePlan(...)` factory mirroring `multiPlan(...)` and route both call sites through it. The prior step's own dim-review already flagged the adjacent asymmetry (`multiPlan` accepting a `cacheEligible` flag it ignores); this is the same seam.

### CQ8 [suggestion] Redundant `isPushDownCountOnly` wrapper, and an inline `java.util.List` FQN

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/PostConcatSupport.java` (lines 51-53), `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/PostConcatOp.java` (line 42)

**Issue**: `PostConcatSupport.isPushDownCountOnly(ops)` does nothing but `return PostConcatOp.isPushDownCountOnly(ops);`. The result is one predicate reachable under two names, and the two production call sites split across them: `GremlinToMatchStrategy:455` goes through the wrapper, `MultiPlanMatchStep:232` calls `PostConcatOp` directly. A reader comparing the two sites has to check that they mean the same thing.

`PostConcatOp` also declares that method as `static boolean isPushDownCountOnly(@Nonnull java.util.List<? extends PostConcatOp> ops)` — a fully-qualified `java.util.List` inline where a normal import would do, in a file that already imports `javax.annotation.Nonnull`. The same inline-FQN habit shows up in the new test code (`java.util.ArrayList` at `GremlinToMatchStrategyTest:3521` of the diff, `org.mockito.Mockito.doThrow` at 3691) in a file that otherwise imports everything.

**Suggestion**: Delete the wrapper and have `GremlinToMatchStrategy` call `PostConcatOp.isPushDownCountOnly` directly; `PostConcatSupport` then holds only `rewriteToCountStar`, matching its Javadoc. Add `import java.util.List;` to `PostConcatOp` and replace the FQNs in the new test code with imports.

### CQ9 [suggestion] `count(*)` detection duplicated and done by AST `toString()` comparison

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchExecutionPlanner.java` (lines 742-763), `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/HardwiredCountOptimizations.java` (lines 73-135)

**Issue**: `isBareCountStarWithoutGroupBy()` (new in `e3d460ef49`) and `tryHardwiredMatchCount` now carry the identical three-line predicate — `returnItems != null`, `size() == 1`, `"count(*)".equalsIgnoreCase(returnItems.getFirst().toString().trim())` — with the same magic literal in both. The two guards must stay in lockstep, since one deciding "this is a bare count" while the other disagrees is what produced the DR-U6 hole in the first place.

`HardwiredCountOptimizations.isExactClassEqualsOnly` takes the same `toString()`-on-AST approach for `@class` and the class literal, plus a hand-rolled `stripQuotes` that leaves escapes intact. The inline comment is candid about why (structural walks diverge between the builder-built and parser-built shapes), and the new `HardwiredCountExactClassFilterTest` pins both shapes. The choice is documented. It stays a fragile contract, and it is now load-bearing for a planner short-circuit that changes result semantics (polymorphic versus leaf-exact counts).

**Suggestion**: Extract a single `private boolean isCountStarReturn()` in `MatchExecutionPlanner` and have both callers use it, so the literal appears once. For `isExactClassEqualsOnly`, add a Javadoc `@implNote` recording that the `toString()` comparison is the deliberate bridge between the two AST producers and that a third producer would need a test in `HardwiredCountExactClassFilterTest` before it can be trusted.

### CQ10 [suggestion] `rewriteReturnAlias` is package-visible with only in-class callers

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/UnionStepRecogniser.java` (lines 139-141)

**Issue**: `static MatchPlanInputs rewriteReturnAlias(...)` is package-private while its two siblings, `containsNestedUnion` and `childSuffixWithoutEnd`, are `private static`. Its only call site is line 93 in the same class, and no test references it. Nothing in the package needs the wider visibility.

**Suggestion**: Make it `private static`, or add a comment naming the intended future caller if the visibility is a deliberate seam.

### CQ11 [suggestion] Fifth copy of the equivalence-test harness, and a redundant boolean parameter

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/UnionTraversalEquivalenceTest.java` (lines 226-278)

**Issue**: `countBoundarySteps` plus the translator-on/translator-off `assertEquivalent` harness now lives in five test classes — `GremlinToMatchSmokeTest`, `ProjectionEquivalenceTest`, `PredicateTraversalEquivalenceTest`, `EdgeTraversalEquivalenceTest`, and now `UnionTraversalEquivalenceTest`. The duplication predates this track, so the new file follows the house pattern. It is also the newest copy of a body that has since diverged: this one adds `countMultiPlanSteps` and a `Recognition` enum the others lack.

The `assertEquivalent(scenario, expected, supplier, expectMultiPlan)` signature also carries a boolean that duplicates the enum: every `Recognition.DECLINED` call site passes `false`, and the method ignores `expectMultiPlan` on that branch. A bare `true` or `false` at the call site says nothing about what it toggles.

**Suggestion**: Extract the shared harness into a `TranslatorEquivalenceSupport` helper (or a common base test) that all five classes use, as a separate cleanup outside this track. In this file, fold the boolean into the enum as a third constant (`RECOGNIZED_MULTI_PLAN`), or pass it as a named local at each call site.

### CQ12 [suggestion] Dead empty-list branch and two near-identical lookups in `capturedExecutionPlan`

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/common/profiler/monitoring/YTDBQueryMetricsStep.java` (lines 88-107)

**Issue**: `plans.isEmpty() ? null : plans.getFirst()` guards a state the `MultiPlanMatchStep` constructor already rejects — it throws `IllegalArgumentException` on an empty plan list. The branch is unreachable and untestable. The method also runs two near-identical `TraversalHelper.getFirstStepOfAssignableClass(...) → isPresent() → get()` blocks that differ only in which accessor they call, where one lookup for the shared `AbstractMatchPlanStep` base followed by a type switch would read more directly.

**Suggestion**: Drop the `isEmpty()` branch (or replace it with an `assert` naming the constructor invariant it mirrors). Optionally collapse the two lookups into a single `getFirstStepOfAssignableClass(AbstractMatchPlanStep.class, traversal)` plus a pattern switch on the two subtypes.

## Evidence base
