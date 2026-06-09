<!-- manifest
phase: 2
review: consistency
iteration: 1
verdict: PASS
findings: 0
evidence_base: 28 certificates (25 Ref MATCHES, 1 Flow MATCHES, 2 Invariant ENFORCED/ASPIRATIONAL-noted); all current-state symbols verified present with the described shape.
index: []
-->

# Consistency Review — iteration 1

Plan: `docs/adr/gremlin-to-match-translator-design/_workflow/implementation-plan.md`
Design: `docs/adr/gremlin-to-match-translator-design/_workflow/design.md`
Tracks: `plan/track-1.md` … `plan/track-6.md` (all six pending `[ ]`)

**Verdict: PASS — no blocker / should-fix / suggestion findings.**

Every current-state symbol the plan and design reference exists in this
worktree's source tree with the described shape. The DR→track mapping
resolves, the track dependency chain is acyclic, and no orphan tracks /
DRs / design sections were found. Target-state references (the new
translator classes) were pre-screened out per the intent-axis rule and
all resolve to reachable construction over verified primitives.

**Tooling caveat.** mcp-steroid's open IntelliJ project is the main
checkout, not this worktree (cwd mismatch), so PSI was not used. Every
current-state symbol below was verified with `grep -rn` + `Read` inside
this worktree's source tree, which is authoritative for this branch.
The certificates are plain existence-and-shape checks (class/method/field
present, signature, line-cited grammar production, jar-membership of a
fork class) — the category where grep+Read is reliable. No certificate
below depends on reference-completeness (find-all-callers / find-all-
overrides), so the grep-miss failure modes (polymorphic call sites,
Javadoc matches, renamed symbols) do not affect any verdict. Had a
finding turned on "X has no other caller" it would carry an explicit
reference-accuracy caveat; none did.

---

## Findings

(none)

---

## Evidence base

### Design ↔ Code

#### Ref: MatchExecutionPlanner — 3 existing constructors
- **Document claim**: design class diagram annotates the new ctor
  `"ADDITIVE: 3 existing ctors preserved"`; D2 / constraints say one
  additive `MatchExecutionPlanner(MatchPlanInputs)` ctor leaves the
  three existing ones untouched.
- **Search performed**: `grep -nE 'public MatchExecutionPlanner'`
  in `…/sql/executor/match/MatchExecutionPlanner.java`.
- **Code location**: lines 385, 398, 424.
- **Actual signature/role**: `MatchExecutionPlanner(Pattern, Map)`,
  `MatchExecutionPlanner(Pattern, Map, …)`, `MatchExecutionPlanner(SQLMatchStatement)`
  — exactly three.
- **Verdict**: MATCHES

#### Ref: MatchExecutionPlanner.createExecutionPlan(ctx, prof, useCache)
- **Document claim**: workflow diagram calls
  `createExecutionPlan(ctx, prof, useCache=true)`; track-2 Signatures list
  `createExecutionPlan(ctx, prof, useCache)`.
- **Search performed**: `Read` of MatchExecutionPlanner.java:472-483.
- **Code location**: line 472.
- **Actual signature/role**:
  `public InternalExecutionPlan createExecutionPlan(CommandContext context, boolean enableProfiling, boolean useCache)`.
  3-arg form; honours the cache when `useCache && !enableProfiling`.
- **Verdict**: MATCHES (a stale 2-arg javadoc cross-ref at line 135 does
  not reflect the real signature; not a plan/design claim).

#### Ref: MatchExecutionPlanner internally calls handleProjectionsBlock
- **Document claim**: D2 + Workflow — the planner already calls
  `SelectExecutionPlanner.handleProjectionsBlock` inside
  `createExecutionPlan`; the strategy must NOT call it (double-append).
- **Search performed**: `grep -nE 'handleProjectionsBlock' MatchExecutionPlanner.java`.
- **Code location**: line 623 (call site), after pattern finalization.
- **Actual signature/role**: `SelectExecutionPlanner.handleProjectionsBlock(result, info, context, enableProfiling)`.
- **Verdict**: MATCHES

#### Ref: MatchExecutionPlanner.buildPatterns / manageNotPatterns / DEFAULT_ALIAS_PREFIX
- **Document claim**: design references count short-circuit "after
  `buildPatterns`", NOT-pattern precondition in `manageNotPatterns`, and
  generator aliases avoiding `MatchExecutionPlanner.DEFAULT_ALIAS_PREFIX`.
- **Search performed**: `grep -nE 'buildPatterns|manageNotPatterns|DEFAULT_ALIAS_PREFIX'`.
- **Code location**: `buildPatterns` line 4583 (called at 490);
  `manageNotPatterns` line 673 (called at 550);
  `DEFAULT_ALIAS_PREFIX = "$YOUTRACKDB_DEFAULT_ALIAS_"` line 248.
- **Actual signature/role**: all three present with the described roles.
- **Verdict**: MATCHES

#### Ref: SelectExecutionPlanner.handleProjectionsBlock / handleHardwiredCountOnClass / …UsingIndex
- **Document claim**: D2 + design "Aggregation barrier semantics" — count
  short-circuit factored from `handleHardwiredCountOnClass` /
  `handleHardwiredCountOnClassUsingIndex`; `handleProjectionsBlock` is the
  shared projection helper.
- **Search performed**: `grep -nE 'handleProjectionsBlock|handleHardwiredCountOnClass…'`
  in SelectExecutionPlanner.java.
- **Code location**: `handleProjectionsBlock` line 320 (public static);
  `handleHardwiredCountOnClass` line 488 (private);
  `handleHardwiredCountOnClassUsingIndex` line 553 (private).
- **Actual signature/role**: present; both count methods are private (the
  factor-out to a shared helper is target-state work owned by Track 5,
  not a current-state mismatch).
- **Verdict**: MATCHES

#### Ref: GqlMatchStatement.buildPlan / buildWhereClause / toLiteral
- **Document claim**: design §Overview + §GQL refactor + track-1 — refactor
  `buildPlan`, the static `buildWhereClause(Map<String,Object>)` called by
  `GqlMatchVisitor`, and `toLiteral(Object)` onto the shared builders.
- **Search performed**: `grep -nE 'buildPlan|buildWhereClause|toLiteral'`
  in `gql/parser/GqlMatchStatement.java`.
- **Code location**: `buildPlan` line 86 (private → GqlExecutionPlan);
  `buildWhereClause(Map<String, Object>)` line 127 (static → SQLWhereClause);
  `toLiteral(Object)` line 151 (private static → SQLExpression).
- **Actual signature/role**: all three present; `buildWhereClause` is
  `static`, matching the "static helper" description.
- **Verdict**: MATCHES

#### Ref: SQLIsDefinedCondition / SQLIsNotDefinedCondition — AST shape + isDefinedFor + isIndexAware
- **Document claim**: D-IS-DEFINED + design §"Phase 1 dependency" + track-1
  — existing AST nodes routing through `isDefinedFor`, `isIndexAware()==false`,
  builder constructs them and wires the `SQLExpression` child.
- **Search performed**: `grep -nE 'class|ctor|expression|isDefinedFor|isIndexAware|evaluate'`
  in both AST files.
- **Code location**: `SQLIsDefinedCondition` — `protected SQLExpression expression;`
  line 23; ctors `(int id)` line 25, `(YouTrackDBSql, int)` line 29;
  `evaluate(Identifiable,ctx)` → `expression.isDefinedFor(db,(Entity)elem)` line 45;
  `evaluate(Result,ctx)` → `expression.isDefinedFor(currentRecord)` line 59;
  `isIndexAware` line 141; `copy()` uses `new SQLIsDefinedCondition(-1)`.
- **Actual signature/role**: a builder can `new SQLIsDefinedCondition(-1)`
  and set `.expression` — exactly the design's factory mechanism.
- **Verdict**: MATCHES

#### Ref: Grammar IsDefinedCondition / IsNotDefinedCondition productions
- **Document claim**: design §"Phase 1 dependency" + track-1 — jjt lines
  ≈2897-2913: `Expression() <IS> <DEFINED>` / `Expression() <IS> <NOT> <DEFINED>`.
- **Search performed**: `grep -nE 'IsDefinedCondition|<DEFINED>'`
  in `core/src/main/grammar/YouTrackDBSql.jjt`.
- **Code location**: `IsDefinedCondition()` line 2897 (body
  `Expression() <IS> <DEFINED>` line 2901); `IsNotDefinedCondition()` line
  2905 (body `Expression() <IS> <NOT> <DEFINED>` line 2909).
- **Actual signature/role**: matches the cited production text and line band.
- **Verdict**: MATCHES

#### Ref: Grammar IDENTIFIER production accepts $-prefix
- **Document claim**: design §"Anonymous alias generation" — jjt ~line 590
  `IDENTIFIER : (<DOLLAR>|<LETTER>)(<PART_LETTER>)*`, so `$`-prefix
  restriction is translator policy, not a lexical constraint.
- **Search performed**: `sed -n '585,595p'` of the jjt.
- **Code location**: `< IDENTIFIER: ( ((<DOLLAR>) | <LETTER>) (<PART_LETTER>)* ) >`.
- **Actual signature/role**: matches; minor line-number drift only.
- **Verdict**: MATCHES

#### Ref: QueryOperatorEquals singleton-unbox (63-69) + null short-circuit (71-73)
- **Document claim**: design §"NULL and collection comparison" — lines 63-69
  auto-unbox a size-1 collection against a non-collection scalar; lines
  71-73 short-circuit null operands to FALSE.
- **Search performed**: `sed -n '60,75p'` of
  `sql/operator/QueryOperatorEquals.java`.
- **Code location**: `static boolean equals(session, iLeft, iRight)` —
  `iLeft/iRight instanceof Collection && !(other instanceof Collection) && col.size()==1` → unbox (lines ≈63-69);
  `if (iLeft == null || iRight == null) return false;` (lines ≈71-73).
- **Actual signature/role**: exactly the two branches the truth table relies on.
- **Verdict**: MATCHES

#### Ref: EntityImpl.getPropertyAndType / hasProperty
- **Document claim**: design §"Track 5 commitment" + track-5 — projection
  uses `EntityImpl.getPropertyAndType(key)` (null only when absent) and
  `EntityImpl.hasProperty(key)` for absent-vs-null classification.
- **Search performed**: `grep -nE 'getPropertyAndType|hasProperty'`
  in `record/impl/EntityImpl.java` + `Read` of the method body.
- **Code location**: `getPropertyAndType(String)` line 390 (returns null
  when `!isPropertyAccessible`, else delegates to
  `getPropertyAndChooseReturnValue`); `hasProperty(String)` line 3180 (public).
- **Actual signature/role**: both present and public/usable. The design's
  cited absent-check line (488-491) has drifted into
  `getPropertyAndChooseReturnValue`, but the behavioral contract holds and
  the method is the entry point the plan names.
- **Verdict**: MATCHES

#### Ref: YTDBElementImpl.readFromEntity uses getPropertyAndType / propFactory.empty
- **Document claim**: design §"Track 5 commitment" — `YTDBElementImpl.readFromEntity`
  calls `getPropertyAndType` and returns `propFactory.empty()` for absent,
  a real property for present-with-null.
- **Search performed**: `grep -nE 'readFromEntity|getPropertyAndType|propFactory.empty'`
  in `gremlin/YTDBElementImpl.java`.
- **Code location**: `readFromEntity(...)` line 128; `source.getPropertyAndType(key)`
  line 136; `propFactory.empty()` used at line 110.
- **Actual signature/role**: present; the wrapper-level absent-vs-null
  distinction the commitment relies on is real.
- **Verdict**: MATCHES

#### Ref: ResultInternal.getProperty (query-layer absent/null collapse)
- **Document claim**: design §"Track 5 commitment" — `Result.getProperty`
  returns null for both absent and null-valued (query-layer collapses them).
- **Search performed**: `grep -nE 'getProperty'` in `sql/executor/ResultInternal.java`.
- **Code location**: `getProperty(String)` line 462.
- **Actual signature/role**: present at the cited band (460-476); the
  collapse behavior is the documented motivation for the entity-layer
  presence check.
- **Verdict**: MATCHES

#### Ref: SQLMatchPathItem.filter (edge-side filter slot)
- **Document claim**: D10 + design §"Edge filtering" + track-3 — the IR
  already supports edge-side filters via `SQLMatchPathItem.filter`, so no
  executor/planner change is needed.
- **Search performed**: `grep -nE 'filter'` in `sql/parser/SQLMatchPathItem.java`.
- **Code location**: `protected SQLMatchFilter filter;` line 22.
- **Actual signature/role**: field present; `addEdge(..., edgeFilter, …)`
  parking onto this slot is feasible (target-state builder work).
- **Verdict**: MATCHES

#### Ref: Reused MATCH execution steps (14 classes)
- **Document claim**: design §"Reused execution steps" — table of
  MatchFirstStep, MatchStep, OptionalMatchStep, FilterNotMatchPatternStep,
  CartesianProductStep, ReturnMatch{Elements,Paths,Patterns,PathElements}Step,
  ProjectionCalculationStep, DistinctExecutionStep, OrderByStep,
  SkipExecutionStep, LimitExecutionStep — all consumed unchanged.
- **Search performed**: `find -name '<Class>.java'` for each.
- **Code location**: all 14 found under `sql/executor` (or `…/match`).
- **Actual signature/role**: present.
- **Verdict**: MATCHES

#### Ref: CountFromClassStep / CountFromIndexWithKeyStep + canBeCached + session.countClass
- **Document claim**: design §"Aggregation barrier semantics" + track-5 —
  count short-circuit produces `CountFromClassStep` /
  `CountFromIndexWithKeyStep`; `CountFromClassStep.canBeCached()==false`;
  reads `session.countClass(name, true)`.
- **Search performed**: `find` for both classes; `grep -nE 'canBeCached'`
  in CountFromClassStep; `grep -rnE 'countClass'` in DatabaseSessionEmbedded.
- **Code location**: both step classes present; `CountFromClassStep.canBeCached()`
  line 79; `DatabaseSessionEmbedded.countClass(String)` line 3010,
  `countClass(String, boolean)` line 3019.
- **Actual signature/role**: present; `countClass(name, polymorphic)` matches
  the design's single-class-fast-path call.
- **Verdict**: MATCHES

#### Ref: YTDBHasLabelStep / YTDBClassCountStep
- **Document claim**: design §"Recogniser dispatch" — `YTDBHasLabelStep extends HasStep`
  routes via concrete getClass(); design §"Aggregation barrier" —
  `YTDBClassCountStep` reads the same `countClass`.
- **Search performed**: `find -name 'YTDBHasLabelStep.java' / 'YTDBClassCountStep.java'`.
- **Code location**: `gremlin/traversal/step/filter/YTDBHasLabelStep.java`;
  `gremlin/traversal/step/map/YTDBClassCountStep.java`.
- **Actual signature/role**: present (target-state recogniser keys on the
  former; the latter is a current-state count step).
- **Verdict**: MATCHES

#### Ref: TinkerPop fork strategy + step classes (io.youtrackdb gremlin-core 3.8.1)
- **Document claim**: constraints + design — recognizers key on the fork's
  `Step` classes; `IncidentToAdjacentStrategy` / `ConnectiveStrategy` /
  `LazyBarrierStrategy` fold before the strategy fires; NoOpBarrierStep,
  TraversalFilterStep, RangeGlobalStep, TailGlobalStep, UnfoldStep,
  FoldStep, ReverseStep, OptionalStep, UnionStep, NotStep, AndStep, OrStep,
  WhereTraversalStep, WherePredicateStep, HasStep, HasContainer,
  EdgeVertexStep, VertexStep, GraphStep, PropertiesStep, PropertyMapStep,
  SelectStep, ProjectStep, OrderGlobalStep, DedupGlobalStep.
- **Search performed**: `<gremlin.version>` resolved to
  `3.8.1-af9db90-SNAPSHOT`; `unzip -l` of that jar grepped for each class.
- **Code location**: all three strategies present under
  `…/strategy/{decoration,optimization}/`; every named step present under
  `…/step/{branch,filter,map,util}/`. `EdgeOtherVertexStep` (for `otherV()`)
  also present.
- **Actual signature/role**: the full set the recognizers dispatch on exists
  in the build's resolved fork jar.
- **Verdict**: MATCHES

### Plan ↔ Code (integration points)

#### Ref: ProviderOptimizationStrategy + half-measure strategies are standard editable strategies (D4)
- **Document claim**: D4 + design §Strategy — each half-measure strategy
  lists `GremlinToMatchStrategy` in its own `applyPrior()`; the translator
  declares empty prior/post; registered in the provider optimization chain.
- **Search performed**: `grep -nE 'applyPrior|ProviderOptimizationStrategy|class'`
  in YTDBGraphStepStrategy.java; `grep -rln 'YTDBGraphStepStrategy'`.
- **Code location**: `YTDBGraphStepStrategy extends AbstractTraversalStrategy<ProviderOptimizationStrategy>
  implements ProviderOptimizationStrategy` lines 21-23; registered via
  `gremlin/YTDBGraphImplAbstract.java`.
- **Actual signature/role**: standard TinkerPop strategy classes whose
  `applyPrior()` is editable — D4's ordering mechanism is feasible against
  the real strategy shape.
- **Verdict**: MATCHES

#### Ref: YqlExecutionPlanCache schema-change invalidation hook (D5)
- **Document claim**: D5 + Integration Points + design §"Parameter binding"
  — `GremlinPlanCache` reuses the YQL plan-cache schema-change invalidation
  hook; CREATE CLASS / CREATE INDEX flushes plans.
- **Search performed**: `grep -nE 'public|invalidat|class'`
  in `sql/parser/YqlExecutionPlanCache.java`.
- **Code location**: `class YqlExecutionPlanCache implements MetadataUpdateListener`
  line 23; `onSchemaUpdate→invalidate()` line 149; `onIndexManagerUpdate→invalidate()`
  line 155; `invalidate()` line 141; `getLastInvalidation` line 41.
- **Actual signature/role**: a real `MetadataUpdateListener`-driven
  invalidation hook exists to reuse.
- **Verdict**: MATCHES

#### Ref: SQLPositionalParameter.toGenericStatement / getValue (D5 / Parameter binding)
- **Document claim**: design §"Parameter binding" — `toGenericStatement`
  renders each slot as `PARAMETER_PLACEHOLDER` (value-independent key);
  `getValue(params)` reads `params.get(paramNumber)` at run time.
- **Search performed**: `grep -nE 'getValue|toGenericStatement|PARAMETER_PLACEHOLDER'`
  in `sql/parser/SQLPositionalParameter.java`.
- **Code location**: `toGenericStatement` line 44 appends `PARAMETER_PLACEHOLDER`
  line 45; `getValue(Map<Object,Object> params)` line 49.
- **Actual signature/role**: matches exactly.
- **Verdict**: MATCHES

#### Ref: CommandContext.setInputParameters (boundary-step parameter install)
- **Document claim**: design §"Parameter binding" — the boundary step
  installs the per-walk param map via `ctx.setInputParameters(map)`.
- **Search performed**: `grep -rnE 'setInputParameters'`
  in CommandContext.java / BasicCommandContext.java.
- **Code location**: `CommandContext.setInputParameters(Map<Object,Object>)`
  interface line 106; impl line 499.
- **Actual signature/role**: present; signature matches `setInputParameters(map)`.
- **Verdict**: MATCHES

#### Ref: YTDBStrategyUtil.isPolymorphic(traversal)
- **Document claim**: design §"Schema polymorphism" + track-2 — translator
  reads the polymorphic flag via `YTDBStrategyUtil.isPolymorphic(traversal)`
  once per apply().
- **Search performed**: `grep -nE 'isPolymorphic'`
  in `gremlin/traversal/strategy/YTDBStrategyUtil.java`.
- **Code location**: `public static Boolean isPolymorphic(Admin<?,?> traversal)` line 29.
- **Actual signature/role**: matches `isPolymorphic(traversal)`.
- **Verdict**: MATCHES

#### Ref: MatchExecutionPlanner.createExecutionPlan honours plan cache via executinPlanCanBeCached
- **Document claim**: design §"Aggregation barrier" — a MATCH plan ending in
  `CountFromClassStep` is not cached (`canBeCached()==false`), consistent
  with the planner's cache gate.
- **Search performed**: `Read` of MatchExecutionPlanner.java:472-490.
- **Code location**: gate `useCache && !enableProfiling && statement.executinPlanCanBeCached(session)`
  line 478.
- **Actual signature/role**: planner consults a cacheability predicate; an
  uncacheable count plan is naturally skipped. (Note: the cache gate keys on
  `statement` — wiring the translator's cacheability is target-state Track 2
  work, not a current-state mismatch.)
- **Verdict**: MATCHES

### Design ↔ Plan

#### Flow: applyStrategies → walk → MatchPlanInputs → createExecutionPlan → handleProjectionsBlock (internal) → YTDBMatchPlanStep
- **Document claim**: design §Workflow sequence diagram — strategy walks the
  step list, builds `MatchPlanInputs`, calls
  `new MatchExecutionPlanner(inputs)` then `createExecutionPlan(ctx, prof, true)`;
  the planner internally calls `handleProjectionsBlock`; strategy does NOT
  call it; result wrapped in `YTDBMatchPlanStep`.
- **Trace**:
  1. `createExecutionPlan(ctx, prof, useCache)` @ MatchExecutionPlanner.java:472
     — cache check, then `buildPatterns(ctx)` @490, `splitDisjointPatterns()` @492.
  2. `manageNotPatterns(...)` @550 — NOT-pattern handling (the precondition
     the NotStep recogniser pre-validates).
  3. `SelectExecutionPlanner.handleProjectionsBlock(result, info, ctx, prof)` @623
     — projection/order/limit chain appended internally.
  4. Returns `SelectExecutionPlan` (target-state boundary `YTDBMatchPlanStep`
     wraps it — target, not asserted against current code).
- **Divergence point**: none for the current-state portion (steps 1-3). The
  additive ctor and the boundary step are target-state.
- **Verdict**: MATCHES — the existing planner pipeline the diagram targets
  is real; the strategy-side double-append hazard is correctly documented as
  "do not call handleProjectionsBlock from the strategy."

#### Ref: DR→track mapping resolves; no orphan DRs/tracks; acyclic deps
- **Document claim**: each DR carries `Implemented in: Track N`; six tracks
  with linear dependencies.
- **Search performed**: `grep -nE 'Implemented in'` and
  `grep -nE 'Track [0-9]|Depends on:'` in implementation-plan.md.
- **Code location**: D1-D7,D9→T2; D6,D-IS-DEFINED→T1; D8→T6; D10→T3;
  D-TEXT-OPS→T4. Tracks: T1 (no dep); T2←T1; T3←T2,T1; T4←T3,T1; T5←T4,T1;
  T6←T5.
- **Actual signature/role**: every DR targets an existing track; every track
  1-6 has a matching `plan/track-N.md`; the dependency graph is acyclic
  (T1 is the root foundation; chain is single-threaded with T1 fan-in).
- **Verdict**: MATCHES — no orphan DR, no orphan track, no dependency cycle.

### Invariants

#### Invariant: handleProjectionsBlock is invoked exactly once (no double-append)
- **Document claim**: D2 + Workflow — the planner calls
  `handleProjectionsBlock` internally; the strategy must not call it again.
- **Code evidence**: MatchExecutionPlanner.java:623 (the single internal
  call site inside `createExecutionPlan`).
- **Mechanism**: the translator feeds `MatchPlanInputs` and lets
  `createExecutionPlan` run unmodified; the projection block is appended
  exactly once at line 623. The plan/design explicitly forbid a second call
  from the strategy.
- **Verdict**: ENFORCED (for the current-state planner side) — the design's
  guard is consistent with the single internal call site. The
  no-second-call discipline is a target-state contract on the not-yet-written
  strategy, correctly stated and reachable.

#### Invariant: count short-circuit reuses an existing exact, SI primitive (not a new fast path)
- **Document claim**: design §"Aggregation barrier" — `CountFromClassStep`
  reads `session.countClass(name, true)`, an exact snapshot-isolated scan;
  the short-circuit is factored from existing SELECT helpers.
- **Code evidence**: `handleHardwiredCountOnClass` (SelectExecutionPlanner:488),
  `handleHardwiredCountOnClassUsingIndex` (:553); `CountFromClassStep`,
  `CountFromIndexWithKeyStep`; `DatabaseSessionEmbedded.countClass(String,boolean)`:3019.
- **Mechanism**: the SELECT-side helpers and the count steps already exist;
  Track 5 factors the two private helpers into a shared method invoked by
  `MatchExecutionPlanner`.
- **Verdict**: ASPIRATIONAL (factor-out is target-state Track 5 work) — NOT a
  finding: the helpers and steps it builds on are all present and the
  refactor is reachable. The two SELECT helpers being `private` today is the
  expected pre-refactor state, not a contradiction.
