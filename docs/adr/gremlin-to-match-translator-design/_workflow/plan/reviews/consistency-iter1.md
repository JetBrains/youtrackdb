<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 0, suggestion: 1}
index:
  - {id: CR1, sev: suggestion, loc: "design.md:169-346 (Class Design)", anchor: "### CR1 ", cert: "RC-ADDEDGE,RC-WALKERCTX,RC-NOTREC", basis: "frozen design class diagram lags as-built Track 2/3 surface; already Phase-4-scheduled, no impact on pending tracks"}
evidence_base: {section: "## Evidence base", certs: 31, matches: 28}
flags: [CONTRACT_OK]
-->

## Findings

### CR1 [suggestion]
**Certificate**: RC-ADDEDGE, RC-WALKERCTX, RC-NOTREC (Design ↔ Code / Design ↔ Plan)
**Location**: `design.md` §"Class Design" (lines 169-346), plus §"Scope: recognized step set" lines 117/124; as-built code in `MatchPatternBuilder.java`, `WalkerContext.java`.
**Issue**: The frozen `design.md` class diagram describes the pre-Track-2/3-rework translator surface, so several class shapes it draws no longer match the code the completed tracks landed. None mislead the pending tracks (4-7 all cite the real current shapes), but the class-diagram drift is not individually captured in any track's Surprises log the way §"Parameter binding" and §"Schema polymorphism" already are, so Phase-4 `design-final.md` has no pointer to it.
**Evidence**:
- `MatchPatternBuilder` diagram shows `addEdge(from, to, dir, label, edgeAlias, edgeFilter, while_, maxDepth)` (8 params incl. `edgeAlias`). As-built `addEdge` has 7 params and no `edgeAlias`; the edge-alias/edge-filter form is a separate `addEdgeAsNode(...)` method added by Track 3's rework (`MatchPatternBuilder.java:122`, `:189`). The plan's Track 3 entry already notes "edge filtering needs an edge-as-node builder extension the plan had assumed away," so this is a known consequence.
- `WalkerContext` diagram lists fields `traversal`, `aliasRids`, `boundParams`, `anonAliasGenerator`/`anonEdgeAliasGenerator`, `stepIndex`, and methods `rebindBoundaryProjection(alias)` / `bindParam(Object)`. As-built `WalkerContext` has none of `traversal`/`aliasRids`/`stepIndex`; anon generators are `AliasSequence anonVertexAliases`/`edgeAliases`; the projection re-pin method is `setSingleReturnColumn(alias)`, not `rebindBoundaryProjection`. `boundParams`/`bindParam` are legitimately deferred (Track 5 adds them). Track 4's Context even states "there is no `aliasRids`/`aliasClasses` slot on the context," confirming the plan tracks the real shape while the diagram does not.
- §Scope table + Class Design name a `NotFilterStepRecogniser` (for `hasNot(key)`) alongside a separate `HasStepRecogniser` family; the plan consolidated this to one `NotStepRecogniser` under `NotStep.class` (adversarial A2) and moved `has(key)` to `TraversalFilterStepRecogniser`. This is a documented replan divergence (Design ↔ Plan), expected per the frozen-DR rule.
**Proposed fix**: No change to the plan or tracks — they are correct. Record in the Phase-4 `design-final.md` reconciliation scope that the class diagram must be redrawn to the as-built surface: the `addEdge` + `addEdgeAsNode` split, the real `WalkerContext` field set (no `aliasRids`/`stepIndex`/`traversal`; `setSingleReturnColumn`; `AliasSequence` generators), and the single-`NotStepRecogniser` consolidation. Optionally add a one-line pointer to a track Surprises log so the Phase-4 sweep does not miss it.
**Classification**: design-decision
**Justification**: `design.md` is frozen and never edited during execution; the correction is a Phase-4 design-final reconciliation choice (like the §"Parameter binding" / §"Schema polymorphism" items the tracks already flag), not a single unambiguous mechanical edit the orchestrator can auto-apply — and it touches a frozen artifact, so it must not be silently rewritten.

## Evidence base

**Tooling note.** mcp-steroid PSI (`steroid_execute_code`) was non-responsive this session — every call, including a trivial `DumbService.isDumb` probe, hit the ~60 s MCP HTTP timeout (IDE apparently stuck indexing). Per the consistency-review fallback rule I verified symbols with `grep`/`find` and, for the load-bearing shapes, by reading the full source of the cited files (`Read`, not a name-match). Reference-accuracy caveat: caller-set / "no-other-reference" negatives were not machine-confirmed via find-usages; none of the certificates below rely on such a negative — each is a declaration-existence or declaration-shape check read directly from source, which grep+Read establishes reliably. The one finding (CR1) is grounded in full-source reads of `MatchPatternBuilder.java` and `WalkerContext.java` against the `design.md` diagram text, not a symbol search.

### Plan ↔ Code / Track ↔ Code certificates

#### RC-PATTERNIR — `MatchPatternBuilder.build()` → `PatternIR`
- **Document claim**: design/plan; Track 5 "MatchPatternBuilder.build() (:255 — positive PatternIR only)".
- **Search**: Read `MatchPatternBuilder.java` in full.
- **Code location**: `MatchPatternBuilder.java:255` (`public PatternIR build()`); `PatternIR` is a nested `record` at `:46`.
- **Verdict**: MATCHES. `PatternIR(Pattern, Map aliasClasses, Map aliasFilters)`; `build()` at exactly :255. The earlier "no PatternIR.java file" signal was a nested-type false alarm.

#### RC-MPI-NOTMATCH — `MatchPlanInputs.notMatchExpressions` (:48, :154)
- **Document claim**: Track 5 Signatures "MatchPlanInputs.notMatchExpressions (:48, :154)".
- **Search**: Read `MatchPlanInputs.java`.
- **Code location**: record component at `:48`; `Builder.notMatchExpressions(...)` at `:154`.
- **Verdict**: MATCHES. Exact line numbers.

#### RC-MEP-CTOR — additive `MatchExecutionPlanner(MatchPlanInputs)` ctor (D2)
- **Document claim**: plan D2 / design §Overview; one additive ctor, existing ctors preserved.
- **Search**: grep ctors in `MatchExecutionPlanner.java`.
- **Code location**: ctors at `:403`, `:416`, `:442` (SQLMatchStatement), `:499` (`MatchPlanInputs`).
- **Verdict**: MATCHES. The `MatchPlanInputs` ctor is additive alongside the pre-existing ones.

#### RC-MANAGENOT — `MatchExecutionPlanner.manageNotPatterns` two throws
- **Document claim**: Track 5 A6 — second throw fires on `exp.getOrigin().getFilter() != null` (":766-771"); first on NOT origin alias absent from positive pattern.
- **Search**: grep + `sed` 755-775 of `MatchExecutionPlanner.java`.
- **Code location**: method `:750`; throw #1 at `:760-762` (`pattern.aliasToNode.get(exp.getOrigin().getAlias()) == null`); throw #2 at `:767-770` (`exp.getOrigin().getFilter() != null`).
- **Verdict**: MATCHES. Substance exact; both throws present, on origin-alias-absent and origin-filter as described (band :766-771 covers throw #2).

#### RC-PROMOTERID — `MatchExecutionPlanner.promoteStaticRidsFromFilters`
- **Document claim**: Track 4 R3 — RID-inline preserves this direct-RID fast path; Signatures cite it.
- **Search**: grep in `MatchExecutionPlanner.java`.
- **Code location**: `static ... promoteStaticRidsFromFilters(...)` at `:4758`; invoked at `:4677`, `:4729`.
- **Verdict**: MATCHES.

#### RC-QOE — `QueryOperatorEquals.equals` singleton-unbox + null short-circuit
- **Document claim**: design §"Predicate translation" / Track 4 — "lines 63-69 auto-unbox a singleton collection against a scalar; lines 71-73 short-circuit null operands to false".
- **Search**: `sed` 60-75 of `QueryOperatorEquals.java`.
- **Code location**: `:63-69` singleton-collection-vs-scalar unbox (`col.size() == 1`, both operand orders); `:71-73` `if (iLeft == null || iRight == null) return false;`.
- **Verdict**: MATCHES. Exact line numbers and semantics.

#### RC-RECOGCONTRACT — `StepRecogniser.recognize(StepCursor, RecognitionContext): Outcome`
- **Document claim**: Track 4/5 — post-Track-3 contract `Outcome recognize(StepCursor, RecognitionContext)`, head via `cursor.take()`, trailing via `takeIf`/`takeWhile`, returns `ACCEPTED`/`DECLINE`.
- **Search**: Read `StepRecogniser.java`, `StepCursor.java`, `Outcome.java`.
- **Code location**: `StepRecogniser.java:47` (`Outcome recognize(StepCursor cursor, RecognitionContext ctx)`); `StepCursor` has `take()`, `takeIf(Class,Predicate)`, `takeWhile(Class,Predicate)`, `peek()`, `peek(int)`; `Outcome{ACCEPTED, DECLINE}`.
- **Verdict**: MATCHES.

#### RC-WALKER-POLY — `WalkerContext.polymorphic()`
- **Document claim**: Track 4 — folded-`hasLabel` narrowing gated on `ctx.polymorphic()`.
- **Search**: Read `WalkerContext.java` + `RecognitionContext.java`.
- **Code location**: `WalkerContext.java:173` (`public boolean polymorphic()`), backing field `:77`; declared on `RecognitionContext.java:37`.
- **Verdict**: MATCHES.

#### RC-SETSINGLECOL — `WalkerContext.setSingleReturnColumn` (:253-263)
- **Document claim**: Track 5 A4 — `setSingleReturnColumn` clears and replaces the projection (WalkerContext.java:253-263).
- **Search**: Read `WalkerContext.java`.
- **Code location**: `setSingleReturnColumn(String alias)` at `:253-263` (clears three parallel lists, adds one column).
- **Verdict**: MATCHES. Exact line band.

#### RC-VERTEXHOP — `VertexHopRecogniser` re-pins boundary/single column
- **Document claim**: Track 5 A4 — VertexHopRecogniser re-pins boundary / single RETURN column to its target (:80-84).
- **Search**: `sed` 75-90 of `VertexHopRecogniser.java`.
- **Code location**: re-pin comment `:79-81`, `GremlinPatternAssembler.appendFoldedHop(...)` call `:84`.
- **Verdict**: MATCHES. Substance correct; the actual mutation is inside `appendFoldedHop`.

#### RC-PUTALIASFILTER — `WalkerContext.putAliasFilter` overwrites today (Track 4 modifies to AND-compose)
- **Document claim**: Track 4 — `putAliasFilter` (+ `buildResult` merge) must AND-compose, not overwrite.
- **Search**: Read `WalkerContext.java`/`RecognitionContext.java`.
- **Code location**: `WalkerContext.java:236-238` (`aliasFilters.put(alias, where)` — overwrite; comment "entries here override builder entries").
- **Verdict**: MATCHES (current-state accurate). The AND-compose behavior is a Track-4 target-state modification; the track correctly lists it under "In scope (modified)".

#### RC-BINDPARAM — `bindParam` sink absent from context today
- **Document claim**: Track 5 — adds `bindParam(value) → SQLPositionalParameter` to `RecognitionContext`, implemented by `WalkerContext`.
- **Search**: Read both files.
- **Code location**: NOT present on `RecognitionContext` or `WalkerContext` at HEAD.
- **Verdict**: MATCHES (target-state). Correctly absent — Track 5 creates it; Track 4 renders inline literals.

#### RC-SQLPOSPARAM — `SQLPositionalParameter.getValue(params)`
- **Document claim**: Track 5 Signatures.
- **Search**: grep `SQLPositionalParameter.java`.
- **Code location**: `:49` `public Object getValue(Map<Object,Object> params)`.
- **Verdict**: MATCHES.

#### RC-CMDCTX — `CommandContext.setInputParameters(map)` (:106)
- **Document claim**: Track 5 Signatures.
- **Search**: grep `CommandContext.java`.
- **Code location**: `:106` `void setInputParameters(Map<Object,Object> inputParameters)`.
- **Verdict**: MATCHES. Exact line.

#### RC-SHAREDCTX — `SharedContext` cache field + `MetadataUpdateListener` invalidation pattern
- **Document claim**: Track 5 — `SharedContext` gains a `GremlinPlanCache` field + schema-listener invalidation wiring mirroring `YqlExecutionPlanCache`.
- **Search**: grep `SharedContext.java`.
- **Code location**: `class SharedContext extends ListenerManger<MetadataUpdateListener>` `:30`; `YqlExecutionPlanCache yqlExecutionPlanCache` `:43`, constructed `:86`, `registerListener(...)` `:91`.
- **Verdict**: MATCHES. The exact pattern Track 5 mirrors exists.

#### RC-TRANSLRESULT — `GremlinToMatchTranslator.TranslationResult`
- **Document claim**: Track 5 — `GremlinToMatchTranslator` (`TranslationResult` carries the per-walk parameter values).
- **Search**: grep `GremlinToMatchTranslator.java`.
- **Code location**: `final class GremlinToMatchTranslator` `:33`; `record TranslationResult(...)` `:66`; `translate(...)` `:50`.
- **Verdict**: MATCHES (declaration). Carrying param values is a Track-5 target-state addition.

#### RC-WALKER-REGISTRY — `GremlinStepWalker` `Map.of` registry (duplicate-key throw)
- **Document claim**: Track 5 A2 — two registry entries for one class throw a `Map.of` duplicate-key `IllegalArgumentException` at class init (GremlinStepWalker.java:90-93).
- **Search**: `sed` 85-95.
- **Code location**: `PRODUCTION_RECOGNISERS = Map.of(GraphStep.class -> StartStepRecogniser, VertexStep.class -> VertexStepRecogniser)` at `:90-93`.
- **Verdict**: MATCHES. `Map.of` duplicate-key semantics confirmed.

#### RC-STRATEGY-NET — `GremlinToMatchStrategy` RuntimeException net
- **Document claim**: Track 5 A2/R4 — the strategy's `RuntimeException` net catches `manageNotPatterns`'s `CommandExecutionException` → native decline, while `Error`/`AssertionError` propagate (GremlinToMatchStrategy.java:105-110).
- **Search**: `sed` 100-115.
- **Code location**: Javadoc `:100-115` documents the catch narrowed to `RuntimeException`, with `Error`/`AssertionError` propagating untouched.
- **Verdict**: MATCHES. (A `Map.of` duplicate key throws `IllegalArgumentException` at static-init → `ExceptionInInitializerError`, surfaced loudly regardless; the A2 conclusion "every Gremlin compile fails, not declines" holds.)

#### RC-NOTSTEP-FINAL — fork `NotStep` is `final`; `TraversalFilterStep` exists
- **Document claim**: Track 5 A2 — fork `NotStep` is `final` (`javap`); Track 4 — `has(key)` desugars to `TraversalFilterStep(__.values(key))`.
- **Search**: `javap` from `io.youtrackdb:gremlin-core:3.8.1-af9db90-SNAPSHOT`.
- **Code location**: `public final class org.apache.tinkerpop...filter.NotStep extends FilterStep`; `public final class ...filter.TraversalFilterStep extends FilterStep`.
- **Verdict**: MATCHES. Both final; exact-class dispatch cannot separate `hasNot(key)`/`not(...)` → one `NotStep` recogniser, as A2 concludes.

#### RC-PREDADAPTER — `GremlinPredicateAdapter` skeleton exists
- **Document claim**: Track 4 — Track 3 left a `GremlinPredicateAdapter` skeleton; Track 4 makes it the full chokepoint.
- **Search**: grep `GremlinPredicateAdapter.java`.
- **Code location**: `final class GremlinPredicateAdapter` `:56`; `SQLBooleanExpression toFilter(HasContainer container)` `:72`.
- **Verdict**: MATCHES.

#### RC-WHEREBUILDER — `MatchWhereBuilder` existing vs new methods
- **Document claim**: Track 4 — `classEquals` (used, no prod caller yet), `startsWith` (exists), `isDefined`/`isNotDefined` (exist), `and`/`or`/`not`/`containsText` (exist); `endsWith`/`matchesRegex` are NEW here.
- **Search**: Read `MatchWhereBuilder.java` in full.
- **Code location**: present — `classEquals` `:65`, `startsWith` `:152`, `isDefined` `:263`, `isNotDefined` `:276`, `and` `:172`, `or` `:180`, `not` `:288`, `containsText` `:133`. Absent — `endsWith`, `matchesRegex`.
- **Verdict**: MATCHES. Existing methods present; new ones correctly absent (Track 4 target-state).

#### RC-DELETED — `MatchClassFilters` deleted; `SQLEndsWithCondition` not yet created
- **Document claim**: Track 4/plan — `MatchClassFilters` deleted in Track 3 rework; `SQLEndsWithCondition` is a new Track-4 AST node.
- **Search**: `find -name`.
- **Code location**: neither `.java` exists at HEAD.
- **Verdict**: MATCHES. `MatchClassFilters` absence confirms the deletion claim; `SQLEndsWithCondition` absence is correct target-state.

#### RC-SQLMATCHES — `SQLMatchesCondition` full-match today (find-mode is new)
- **Document claim**: Track 4 — add a find-mode flag on `SQLMatchesCondition`.
- **Search**: grep `SQLMatchesCondition.java`.
- **Code location**: `matches(...)` uses `p.matcher(value).matches()` (full match) `:59-68`; no `findMode` field.
- **Verdict**: MATCHES (current-state). Find-mode flag is a Track-4 target-state addition.

#### RC-CONTAINSTEXT — `SQLContainsTextCondition` no collate today (collate transform is new)
- **Document claim**: Track 4 — add collate transform to `SQLContainsTextCondition` (makes SQL `CONTAINSTEXT` collation-aware too).
- **Search**: grep `SQLContainsTextCondition.java`.
- **Code location**: `setLeft`/`setRight`/`evaluateAny`/`evaluateAllFunction`; no collate field.
- **Verdict**: MATCHES (current-state). Collate transform is Track-4 target-state.

#### RC-ENTITY-COUNT — Track 6 existing-code references (`EntityImpl.hasProperty`, count helpers)
- **Document claim**: Track 6 Signatures — `EntityImpl.hasProperty(key)`, `getPropertyAndType`; `SelectExecutionPlanner.handleHardwiredCountOnClass` / `...UsingIndex`.
- **Search**: grep.
- **Code location**: `EntityImpl.java:3180` `hasProperty`, `:390` `getPropertyAndType`; `SelectExecutionPlanner.java:491` `handleHardwiredCountOnClass`, `:556` `handleHardwiredCountOnClassUsingIndex` (called from `:475`/`:478`).
- **Verdict**: MATCHES.

#### RC-TARGET-NEW — Track 4/5/6/7 new recognisers / builders correctly absent
- **Document claim**: `HasStepRecogniser`, `TraversalFilterStepRecogniser` (T4); `AndStepRecogniser`, `OrStepRecogniser`, `NotStepRecogniser`, `WhereTraversalStepRecogniser`, `WherePredicateStepRecogniser`, `SubTraversalPredicateAdapter`, `GremlinPlanCache` (T5); `GremlinProjectionAssembler`, `ByModulatorTranslator` (T6); `UnionStepRecogniser`, `MultiPlanMatchStep` (T7).
- **Search**: `find -name`.
- **Code location**: none present at HEAD.
- **Verdict**: MATCHES (target-state). All correctly absent — these `[ ]` tracks create them; not findings per the intent-axis pre-screen.

### Design ↔ Code / Design ↔ Plan certificates

#### RC-ADDEDGE — design diagram `addEdge(...edgeAlias...)` vs as-built `addEdge` + `addEdgeAsNode`
- **Document claim**: `design.md` Class Design draws `MatchPatternBuilder.addEdge(from, to, dir, label, edgeAlias, edgeFilter, while_, maxDepth)`.
- **Search**: Read `MatchPatternBuilder.java`.
- **Code location**: `addEdge(fromAlias, toAlias, dir, edgeLabel, edgeFilter, whileCondition, maxDepth)` `:122` (7 params, no `edgeAlias`); separate `addEdgeAsNode(fromAlias, edgeAlias, toAlias, edgeDir, edgeLabel, closingVertexDir, edgeFilter)` `:189` (the edge-alias/edge-filter form).
- **Verdict**: PARTIAL. Frozen design predates Track 3's edge-as-node rework. Feeds CR1. No pending-track impact (Track 3 done; Track 4/5 cite the real methods).

#### RC-WALKERCTX — design diagram `WalkerContext` fields vs as-built
- **Document claim**: diagram lists `traversal`, `aliasRids`, `boundParams`, `anonAliasGenerator`/`anonEdgeAliasGenerator`, `stepIndex`, `rebindBoundaryProjection`, `bindParam`.
- **Search**: Read `WalkerContext.java`.
- **Code location**: as-built has no `traversal`/`aliasRids`/`stepIndex`; generators are `AliasSequence anonVertexAliases`/`edgeAliases` `:159`,`:163`; projection re-pin is `setSingleReturnColumn` `:253`, not `rebindBoundaryProjection`; `boundParams`/`bindParam` deferred to Track 5.
- **Verdict**: PARTIAL. Frozen-design lag from the Track 2/3 step-cursor rework (the plan's Track 2/3 strategy-refresh notes document the shift off `stepIndex`; Track 4 Context notes the missing `aliasRids` slot). Feeds CR1.

#### RC-NOTREC — design `NotFilterStepRecogniser` vs plan single `NotStepRecogniser`
- **Document claim**: `design.md` §Scope line 124 / Class Design name `NotFilterStepRecogniser` (hasNot) separate from `HasStepRecogniser` family.
- **Search**: cross-read design vs track-5.
- **Code location**: plan/track-5 consolidates to one `NotStepRecogniser` under `NotStep.class` (adversarial A2, user-approved 2026-07-15); `has(key)` on `TraversalFilterStepRecogniser`.
- **Verdict**: PARTIAL (Design ↔ Plan). Expected per the frozen-DR-divergence rule (a documented replan supersedes the frozen design); folded into CR1 for Phase-4 visibility, not a standalone finding.

### Gaps

#### RC-GAP-CACHE — `GremlinPlanCache` reuses YQL invalidation hook — existing pattern referenced
- **Document claim**: D5 / Track 5 — reuse the YQL plan-cache schema-change invalidation hook.
- **Search**: grep `SharedContext.java`, `find YqlExecutionPlanCache`.
- **Code location**: `YqlExecutionPlanCache.java` exists; registered as `MetadataUpdateListener` in `SharedContext` `:91`.
- **Verdict**: MATCHES. Plan correctly anchors the new cache on the existing SPI/pattern (no orphan-construct gap).

#### RC-GAP-COUNT — shared count short-circuit anchors on existing `CountFromClassStep` / `CountFromIndexWithKeyStep`
- **Document claim**: D11 / Track 6 — factor `handleHardwiredCountOnClass*` into a shared helper; `CountFromClassStep`/`CountFromIndexWithKeyStep` win a constant factor.
- **Search**: `find` + grep.
- **Code location**: `CountFromClassStep.java`, `CountFromIndexWithKeyStep.java`, `SelectExecutionPlanner.handleHardwiredCountOnClass*` all present.
- **Verdict**: MATCHES. No missing-construct gap.
