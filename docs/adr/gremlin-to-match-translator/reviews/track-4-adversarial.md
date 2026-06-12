# Track 4 Adversarial Review — Filtering + predicates

Phase A iteration 1. Reviewer: adversarial-review sub-agent (devil's advocate).

Tooling note: mcp-steroid was not invoked in this session — fall back to
grep / Read. Findings whose verdict hinges on counting call sites or
polymorphic dispatch are flagged with an explicit reference-accuracy
caveat. Source paths cited use absolute paths.

---

## Part 1: Challenge Certificates

### DECISION CHALLENGES

#### Challenge D2: 19-field record vs builder for `MatchPlanInputs`

- **Chosen approach**: 19-field flat record (`MatchPlanInputs`).
- **Best rejected alternative**: Builder-style API
  (`MatchPlanInputs.builder().pattern(p).aliasFilters(m)…build()`).
- **Counterargument trace**:
  1. Track 4 must aggregate filters across multiple `HasStep` invocations
     for the same alias (per plan: "ANDs all containers, attaches the
     result as the where of the current SQLMatchFilter via
     MatchPatternBuilder").
  2. The current `MatchPlanInputs` is a frozen record, so successive
     filter contributions must mutate `WalkerContext.aliasFilters` /
     `MatchPatternBuilder.aliasFilters` and aggregate before the record
     is constructed at the end (in `GremlinStepWalker.buildResult`).
  3. The aggregation choice (AND vs replace) currently lives in the
     mutator (builder) — but `MatchPatternBuilder.addNode`'s contract is
     **overwrite-on-non-null** (see
     `/home/sandra-adamiec/IdeaProjects/youtrackdb/core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/builder/MatchPatternBuilder.java:89`:
     `if (where != null) { aliasFilters.put(alias, where); }`).
  4. Two HasSteps in sequence on the same alias would each call
     `addNode(alias, null, where, false)` — the second OVERWRITES the
     first, silently dropping the first filter. The record's structure
     does not surface this bug; a builder API explicitly exposing
     `addAliasFilter(alias, expr)` (AND-merge by contract) would have.
- **Codebase evidence**:
  `MatchPatternBuilder.java:89` (overwrite-on-non-null write semantics);
  `GremlinStepWalker.java:168-169` (`putAll` replaces, does not AND).
- **Survival test**: WEAK. The record itself is fine for the post-walk
  hand-off, but the **walk-time aggregation contract** is implicit and
  the plan does not state it. Track 4 must invent an AND-merge helper
  (probably in `WalkerContext` or `MatchPatternBuilder`) that the plan
  does not call out.

#### Challenge D3: All-or-nothing — does Track 4 surface a partial-recognition opportunity?

- **Chosen approach**: All-or-nothing translation.
- **Best rejected alternative**: Hybrid prefix with recognised-set gate.
- **Counterargument trace**:
  1. Track 4 must recognise BOTH:
     (a) folded `hasContainers` already absorbed into `YTDBGraphStep`
     by `YTDBGraphStepStrategy.rebuildTraversal` (see
     `/home/sandra-adamiec/IdeaProjects/youtrackdb/core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/traversal/strategy/optimization/YTDBGraphStepStrategy.java:119-128`),
     and (b) un-folded `HasStep` mid-chain.
  2. The folded predicates use TinkerPop's `HasContainer(key, P)` with
     arbitrary `P`. The plan ENUMERATES the supported P-shapes (eq, neq,
     gt, gte, lt, lte, within, without, between, inside, outside,
     containing, startingWith, endingWith, and/or/not) but does not
     enumerate the unsupported shapes (eg `regex`, custom `P` subclasses,
     null-valued `eq`, value lists for non-container P, etc.). Any
     unsupported shape forces the WHOLE traversal to decline.
  3. With a hybrid prefix, an unsupported `P` mid-pattern would cut the
     translation at that step and let the native pipeline handle the
     suffix — significantly more LDBC-relevant queries would translate
     partially. All-or-nothing forfeits this.
  4. Worse: under D3, a SINGLE unrecognized HasContainer key (eg
     `T.id` with a Gremlin Element value rather than a string/RID) is
     enough to decline an entire 12-step LDBC query.
- **Codebase evidence**: `YTDBGraphStepStrategy.java:119` (HasStep
  fold); plan's enumeration list in `track-4.md:9-20`.
- **Survival test**: WEAK. The chosen approach is consistent with
  Track 2's revised D3, but Track 4 amplifies the cost: every new
  predicate shape becomes binary (translate or decline), and the
  blast radius is now the entire traversal.

#### Challenge: D6 vs alternative — `DEFAULT_VERTEX_CLASS` lift target

- **Chosen approach**: Lift `DEFAULT_VERTEX_CLASS = "V"` to a new
  shared constant under `internal/core/gremlin/translator/strategy/`
  or `match.builder/`.
- **Best rejected alternative**: Use existing
  `SchemaClass.VERTEX_CLASS_NAME = "V"` (already defined at
  `/home/sandra-adamiec/IdeaProjects/youtrackdb/core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/schema/SchemaClass.java:34`).
- **Counterargument trace**:
  1. `SchemaClass.VERTEX_CLASS_NAME` is the canonical constant in the
     codebase, used by `YTDBGraphQueryBuilder.buildClassList`
     (`YTDBGraphQueryBuilder.java:111`) and
     `YTDBGraphCountStrategy.java:71`.
  2. Creating a new constant in `match.builder/` duplicates a
     well-established convention; if YTDB ever renames the abstract
     vertex root, two places need updating instead of one.
  3. The plan's "promote to a shared constant" misses the higher-level
     refactor: just import `SchemaClass.VERTEX_CLASS_NAME` in all three
     recognisers.
- **Codebase evidence**: `SchemaClass.java:34`; `YTDBGraphQueryBuilder.java:111`.
- **Survival test**: WEAK. The plan's chosen lift target is a parallel
  redundant constant when the canonical one already exists.

#### Challenge: D4 ordering — should the strategy run BEFORE GraphStepStrategy?

- **Chosen approach**: Run AFTER `YTDBGraphStepStrategy` and
  `YTDBGraphCountStrategy`, BEFORE `YTDBGraphMatchStepStrategy`.
- **Best rejected alternative**: Run BEFORE `YTDBGraphStepStrategy`,
  reading raw `HasStep` instances directly without the fold.
- **Counterargument trace**:
  1. The current ordering means Track 4 must reverse-engineer the
     fold: it gets a `YTDBGraphStep` whose `hasContainers` mixes
     `T.label` predicates (which become class constraints) with
     property predicates (which become SQL WHERE filters). The walker
     has to demux them.
  2. If we ran BEFORE GraphStepStrategy, the start step is a plain
     `GraphStep` followed by separate `HasStep` instances, each
     containing distinct `HasContainer`s. The recogniser logic is
     simpler.
  3. The cited rationale ("we receive YTDBGraphStep with hasContainers
     already attached, which the translator reads as the root
     selectivity of the pattern") is post-hoc — Track 4 does not in
     fact use the folded structure as "root selectivity"; it just
     translates the predicates one-by-one regardless.
  4. Running before GraphStepStrategy also avoids the
     `YTDBHasLabelStep` mid-chain insertion, which Track 4 has to
     handle as a separate recogniser.
- **Codebase evidence**:
  `YTDBGraphStepStrategy.java:95-164` (fold logic);
  `YTDBHasLabelStep.java:18-105` (mid-chain hasLabel handling).
- **Survival test**: WEAK. The ordering decision was made when the
  recognised set was minimal (g.V() only). Track 4 forces it to be
  re-litigated; the rationale should at least acknowledge the cost
  of demuxing folded predicates.

### SCOPE CHALLENGES

#### Challenge: Six steps for predicate adapter + 4 has* recognisers + DRY lifts

- **Concern**: Track 4 collapses a wide range of work into 6 steps:
  - Predicate adapter (covering ~14 P shapes)
  - HasStep recogniser (property + T.label demux)
  - YTDBHasLabelStep recogniser
  - HasStep with hasId routing (single + multi-ID)
  - HasStep with hasNot
  - 3 DRY lifts
  - Equivalence-test harness extension
- **Rejected approach**: Split into Track 4a (predicate adapter +
  basic property HasStep) and Track 4b (T.label / hasLabel /
  hasId / hasNot / DRY lifts).
- **Counterargument trace**:
  1. The predicate adapter alone has **~14 distinct P shapes**, each
     with a Compare/Contains/TextP discriminator. Each needs unit
     tests covering valid+invalid input.
  2. The plan's `Compare.eq → op(field, EQ, lit)` glosses over
     Gremlin/SQL three-valued-logic divergence: `eq(null) → IS NULL`
     and `neq(null) → IS NOT NULL` (see
     `/home/sandra-adamiec/IdeaProjects/youtrackdb/core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/YTDBGraphQueryBuilder.java:79-89`).
     `neq(value) → SQL "x <> 'value'"` is wrong: in three-valued logic,
     `name <> 'alice'` excludes `name IS NULL` records, but Gremlin's
     `P.neq` includes them. The native pipeline adds `name IS NOT NULL
     AND name <> 'value'` for negative predicates. The plan does not
     mention this — implementing it correctly raises step count.
  3. The DRY lifts (3 items) are scope creep on an already-broad
     track. Whether they belong here is debatable; arguably they
     should be a Track 3.5 housekeeping commit so Track 4 stays
     scoped to filtering.
- **Codebase evidence**: `YTDBGraphQueryBuilder.java:79-105, 282-286`.
- **Survival test**: WEAK. Six steps is plausible but tight; the
  three-valued-logic gotcha alone is a step's worth of work, and
  the plan has no slot for it.

#### Challenge: Cheap addition — TraversalFilterStep, IsStep, FilterStep alongside HasStep

- **Concern**: TinkerPop has many filter-step variants (IsStep,
  TraversalFilterStep, ...). The plan covers HasStep / HasLabelStep /
  HasIdStep / HasNotStep but skips IsStep (`is(P)` — filter on the
  current binding's value, not a property).
- **Rejected approach**: Add IsStep recogniser as a cheap addition.
- **Counterargument trace**:
  1. IsStep wraps the current traverser through a P. After
     `g.V().values("age").is(P.gt(30))` the step is IsStep wrapping
     `P.gt(30)`. But this requires the predicate adapter to run
     against the CURRENT binding, not a property name — which is a
     different shape than HasStep.
  2. IsStep is rare in pattern-match Gremlin; TraversalFilterStep is
     handled by Track 5 (where with sub-traversal). So skipping it
     in Track 4 is defensible.
- **Survival test**: HOLDS. The skip is correct; the question is
  whether the plan should explicitly call it out as a non-goal.

### INVARIANT CHALLENGES

#### Challenge: Cucumber suite test count >= before — break under hasLabel translation

- **Invariant claim**: "Cucumber suite test count: count after Phase 1
  >= count before Phase 1."
- **Violation construction**:
  1. `YTDBHasLabelStep` stores `List<P<? super String>>` predicates and
     a `polymorphic` flag (see `YTDBHasLabelStep.java:20-21`). It
     supports `hasLabel("Person", "Software")`, `hasLabel(P.eq("X"))`,
     `hasLabel(P.within("A", "B"))`.
  2. The plan's Track 4 description says "`YTDBHasLabelStep` — narrows
     the current alias to a specific class" — singular. It does not
     handle multi-class hasLabel, P.within, or P.regex on labels.
  3. Construct: `g.V().out("knows").hasLabel("Person", "Software")`.
     After GraphStepStrategy, this is `[YTDBGraphStep, VertexStep,
     YTDBHasLabelStep([P.within("Person", "Software")])]`.
  4. Track 4's hasLabel recogniser tries to extract a single class
     name from `predicates.get(0)` — but the predicate is `P.within`,
     not `P.eq`. If the recogniser declines, the WHOLE traversal
     declines (D3) and runs natively — Cucumber stays green.
  5. If the recogniser tries to extract and gets it wrong (eg
     pulls "Person" only from a within), the plan changes the
     traversal's result set. Cucumber breaks.
- **Feasibility**: CONSTRUCTIBLE. TinkerPop's Cucumber suite has
  scenarios with `hasLabel("X", "Y")`. The plan's terse "narrows to a
  specific class" leaves room for incorrect implementation.
- **Recommendation**: Track 4 must DECLINE on multi-predicate or
  non-`Compare.eq`/`Contains.within`-string `YTDBHasLabelStep`. This
  needs to be an explicit step, not glossed in the description.

#### Challenge: Strategy idempotency — re-applied strategy adds duplicate has filters

- **Invariant claim**: "Re-applying the strategy to a traversal that
  already contains `YTDBMatchPlanStep` is a no-op."
- **Violation construction**:
  1. The current idempotency gate (`GremlinToMatchStrategy.java:157`)
     is `containsBoundaryStep(traversal)` — short-circuits on
     `YTDBMatchPlanStep` anywhere in the step list.
  2. Track 4 does not modify this gate. So re-applying after a
     filter-only translation is still a no-op.
  3. BUT: Track 4 must lift the
     `graphStep.getHasContainers().isEmpty()` gate
     (`GremlinToMatchStrategy.java:164`) to translate folded
     predicates. Once lifted, the strategy must NOT re-translate a
     `YTDBGraphStep` whose hasContainers are present because the
     `applyStrategies` pipeline could conceivably run twice on a
     declined traversal — first decline, second decline again, no
     issue. Verified.
  4. Different concern: the boundary step must NOT be the start step
     of a re-applied traversal — but it is single-shot per instance
     (per Track 2 episode), so re-applying calls clone() and rebinds.
- **Feasibility**: INFEASIBLE for the stated invariant — idempotency
  itself holds. But the lift-the-gate change introduces a NEW
  invariant the plan does not state: "after gating change, declined
  traversals must remain idempotent under re-application". This
  needs a regression test.

### ASSUMPTION CHALLENGES

#### Challenge: hasNot(key) maps cleanly to `field IS NULL`

- **Claim**: `hasNot(key)` maps to `field IS NULL` /
  `NOT exists(field)` via `MatchWhereBuilder.not(op(field, EQ, ...))`.
- **Stress scenario**:
  1. The plan suggests `MatchWhereBuilder.not(op(field, EQ, ...))` —
     but EQ what? The plan does not specify. `not(field = field)`?
     `not(field = some literal)`? Neither captures "field is absent".
  2. `SQLIsNullCondition` exists at
     `/home/sandra-adamiec/IdeaProjects/youtrackdb/core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLIsNullCondition.java:18`
     and has a public `setExpression` setter — so a builder helper
     would be `isNull(field)` returning an `SQLIsNullCondition`.
  3. `MatchWhereBuilder` does NOT currently have an `isNull` helper.
     The plan glosses this with "or a dedicated null-check helper",
     but the helper does not exist.
  4. What if the key is a record attribute (`@class`, `@rid`)? Then
     the LEFT side must be `SQLRecordAttribute`, not `SQLIdentifier`
     (per the same trick `StartStepRecogniser.buildRidInExpression`
     uses). The plan does not address this — `hasNot("@class")` is a
     legal Gremlin call.
  5. What if the value (in `has("name", null)`) is null? Gremlin's
     `has("name", null)` calls `eq(null)` which maps to IS NULL in
     YTDBGraphQueryBuilder (line 79-89). The predicate adapter must
     handle null literals — the plan's `Compare.eq → op(field, EQ,
     lit)` would render `name = NULL` which is always NULL in SQL
     three-valued logic.
- **Code evidence**:
  `SQLIsNullCondition.java:18, 72`;
  `MatchWhereBuilder.java:36-220` (no isNull helper);
  `YTDBGraphQueryBuilder.java:79-89`.
- **Verdict**: BREAKS. The plan's `not(op(EQ))` formulation is
  wrong, and the missing builder helper plus the null-literal gotcha
  add another step's worth of work.

#### Challenge: Text.containing → containsText etc. — do these methods exist?

- **Claim**: `Text.containing` → `containsText`, `Text.startingWith`
  → `startsWith`, `Text.endingWith` → `endsWith` via
  `MatchWhereBuilder`.
- **Stress scenario**:
  1. TinkerPop's predicate is `TextP` (not `Text`). The plan has a
     naming error. `TextP.containing`, `TextP.startingWith`,
     `TextP.endingWith`, `TextP.notContaining`,
     `TextP.notStartingWith`, `TextP.notEndingWith` are the actual
     enum values.
  2. `MatchWhereBuilder.containsText` exists
     (`MatchWhereBuilder.java:87`). `startsWith` exists
     (`MatchWhereBuilder.java:102`). `endsWith` exists
     (`MatchWhereBuilder.java:109`). Good.
  3. But Track 4 plan does not list `notContaining`,
     `notStartingWith`, `notEndingWith` — these would need
     `MatchWhereBuilder.not(containsText(...))` etc. Builder has
     `not()` (line 135). Doable but plan does not enumerate.
  4. CONTAINSTEXT semantics (`SQLContainsTextCondition.java:42`) use
     `String.indexOf > -1` (case-sensitive). TinkerPop's
     `TextP.containing` also uses `String.contains()` (case-
     sensitive). Match.
  5. `startsWith` uses `LIKE 'prefix%'`. The Javadoc warns about
     LIKE metachar escaping (`MatchWhereBuilder.java:98-101`):
     "the prefix is concatenated verbatim — no escaping". But
     TinkerPop's `TextP.startingWith("a%b")` matches a literal
     "a%b" prefix — not "a anything b". The plan's translation
     would produce `LIKE 'a%b%'` which matches "axb...", "ab..."
     etc. SEMANTIC DIVERGENCE.
- **Code evidence**:
  `MatchWhereBuilder.java:98-111`;
  `SQLContainsTextCondition.java:42-43`.
- **Verdict**: FRAGILE. The naming error (Text vs TextP) is
  cosmetic, but the LIKE-metachar-escape issue is a silent
  correctness bug that will fail Cucumber if any scenario uses a
  prefix containing `%` or `_` or `\`.

#### Challenge: hasId(...) routes through aliasRids for single-ID — but g.V(id).hasId(other_id)?

- **Claim**: Single-ID hasId routes through `aliasRids`; multi-ID
  through `aliasFilters`.
- **Stress scenario**:
  1. `g.V("#10:0").hasId("#10:1")` is a legal Gremlin call. The
     start step has `hasId="#10:0"`, the HasStep has
     `T.id = "#10:1"`. The intersection is empty.
  2. The start step recogniser at
     `StartStepRecogniser.java:138-141` writes
     `aliasRids[boundaryAlias] = #10:0`.
  3. Track 4's hasId recogniser sees `aliasRids` already populated.
     Single-ID write path overwrites — the resulting plan looks for
     `#10:1`, contradicting the start step's intent.
  4. Multi-ID write path puts an `@rid IN [#10:1]` into
     `ctx.aliasFilters`. The walker's merge writes it through. The
     planner sees both `aliasRids[boundary] = #10:0` AND
     `aliasFilters[boundary] = @rid IN [#10:1]`. Result depends on
     planner semantics — likely AND, so empty.
  5. Native Gremlin: `g.V("#10:0").hasId("#10:1")` returns empty.
     Match: probably also empty. Coincidentally correct, but for
     wrong reasons.
  6. Worse: `g.V("#10:0").hasId("#10:0")` should return one vertex.
     If the recogniser overwrites, the answer is still one vertex —
     also coincidentally correct.
  7. What about `g.V().hasId("#10:0").hasId("#10:1")`? Gremlin
     intersects: empty. Naive Track 4: writes `aliasRids` twice,
     second wins, returns vertex `#10:1` — WRONG.
- **Code evidence**:
  `StartStepRecogniser.java:138-141`;
  `WalkerContext.java:65-66` (single-RID-per-alias contract).
- **Verdict**: BREAKS. The plan's "single-ID routes through
  aliasRids" must include a check: if `aliasRids` is already
  populated for this alias, OR if multiple `hasId` steps appear in
  sequence, switch routing to `@rid IN [...]` (intersection
  semantics) and AND-merge with existing entries. The plan does
  not state this.

#### Challenge: hasLabel after VertexStepRecogniser — multi-hop with multiple hasLabels

- **Claim**: `hasLabel` chain-target narrowing must write through
  `ctx.aliasFilters` to override the stale `WHERE @class = 'V'`
  from `VertexStepRecogniser`'s chain-target narrowing.
- **Stress scenario**:
  1. `g.V().hasLabel("Person").out("knows").hasLabel("Software")`
     under non-polymorphic mode.
  2. After GraphStepStrategy: `[YTDBGraphStep, VertexStep,
     YTDBHasLabelStep("Software")]`. The first hasLabel was folded
     into `YTDBGraphStep.hasContainers` as `T.label = "Person"`.
  3. Track 4's StartStep recogniser (modified to handle
     hasContainers) writes `aliasClasses[$g2m_v0] = "Person"` AND
     `aliasFilters[$g2m_v0] = @class = 'Person'` (under
     non-polymorphic).
  4. VertexStepRecogniser writes
     `aliasClasses[$g2m_anon_1] = "V"` AND
     `aliasFilters[$g2m_anon_1] = @class = 'V'` (non-polymorphic).
  5. Track 4's YTDBHasLabelStep recogniser sees the
     non-polymorphic mode, must overwrite
     `aliasFilters[$g2m_anon_1]` from `@class = 'V'` to
     `@class = 'Software'`. The plan says: "ensure any prior
     chain-target `aliasFilters` entry is replaced rather than
     shadowed". Good.
  6. BUT: what about `aliasClasses`? It still says "V". The
     planner reads `aliasClasses` for class-IN expansion. Would
     the planner pick a Software-only seed but expand to V? Or
     would it pick V (because aliasClasses says so) and then
     filter to Software (because aliasFilters says so) — net
     correct but slow?
  7. The plan says: "(a) choose between writing aliasClasses vs
     aliasFilters per polymorphism". In non-polymorphic mode,
     write both. In polymorphic mode, write aliasClasses only.
     The plan does not state this clearly.
  8. Worse: if HasStep mid-chain writes `where("name = 'alice'")`
     via `MatchPatternBuilder.addNode`, then merge happens:
     `ir.aliasFilters[$g2m_anon_1] = name = 'alice'`,
     `ctx.aliasFilters[$g2m_anon_1] = @class = 'Software'`,
     `putAll` REPLACES — the property filter is dropped.
- **Code evidence**:
  `MatchPatternBuilder.java:71-93`;
  `GremlinStepWalker.java:168-169`;
  `VertexStepRecogniser.java:145-149`.
- **Verdict**: BREAKS. The merge contract under multi-hop
  filtering loses property filters. Track 4 must redesign the
  merge to AND-combine, or write all filters through
  `ctx.aliasFilters` with explicit AND-merge.

### SIMPLIFICATION CHALLENGES

#### Challenge: Predicate adapter as static method instead of class

- **Claim**: `GremlinPredicateAdapter` is a class.
- **Counterargument**: It has no state — all methods are pure
  translations of `P` instances to `SQLBooleanExpression`. A static
  utility (like `MatchClassFilters`) is simpler.
- **Codebase evidence**: `MatchClassFilters` is a `final` utility
  class with all-static methods (`MatchClassFilters.java:28`).
- **Verdict**: HOLDS. A static utility is more consistent with
  Track 3's pattern.

#### Challenge: 4 has* recognisers can collapse into one

- **Claim**: Plan calls for HasStep, T.label-on-HasStep,
  YTDBHasLabelStep, hasId, hasNot.
- **Counterargument**: A single `HasStepRecogniser` can dispatch on
  HasContainer key (T.id → RID path, T.label → class path,
  property name → filter path) and the YTDBHasLabelStep is its own
  type that needs a separate recogniser anyway.
- **Verdict**: HOLDS for HasStep variants (one recogniser, dispatch
  inside). YTDBHasLabelStep is genuinely a different step class
  and needs its own recogniser.

#### Challenge: Strategy-on/strategy-off equivalence harness for filters

- **Claim**: Plan mandates extending the EdgeTraversalEquivalence
  harness for filters.
- **Counterargument**: Filters don't exercise edge-traversal
  cardinality issues. SQL-MATCH parity may suffice.
- **Counter-counterargument**: Three-valued logic (eq/neq null,
  IS NULL handling) IS a Gremlin/SQL semantic divergence — exactly
  the kind of issue that escapes encoding-only tests.
- **Verdict**: HOLDS. Equivalence harness IS needed, but for
  three-valued logic and TextP semantic edge cases, not for
  cardinality.

---

## Part 2: Findings

### Finding A1 [blocker]
**Certificate**: Challenge "hasLabel after VertexStepRecogniser —
multi-hop with multiple hasLabels"
**Target**: Invariant — alias-filter merge under chain-target hasLabel
**Challenge**: Property-filter HasStep writes via
`MatchPatternBuilder.addNode(alias, null, where, false)` which goes
to `ir.aliasFilters`. Class-narrowing hasLabel writes via
`ctx.aliasFilters`. The walker's merge
(`finalAliasFilters.putAll(ctx.aliasFilters)`) REPLACES per-alias
entries instead of AND-combining. Property filters from HasStep are
silently dropped on aliases that also have a class narrowing.
**Evidence**:
- `/home/sandra-adamiec/IdeaProjects/youtrackdb/core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinStepWalker.java:168-169`
  (`putAll` replaces);
- `/home/sandra-adamiec/IdeaProjects/youtrackdb/core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/builder/MatchPatternBuilder.java:89`
  (overwrite-on-non-null write);
- Concrete failing case:
  `g.V().hasLabel("Person").out("knows").has("name", "alice")`
  under non-polymorphic mode loses `name = 'alice'`.
**Proposed fix**: Track 4 must introduce an AND-merge contract.
Either: (a) all alias-filter writes go through
`ctx.aliasFilters` with explicit AND-merge inside a `WalkerContext`
helper method (`addAliasFilter(alias, expr)` that AND-combines if
present); OR (b) the walker's `buildResult` performs AND-merge per
alias, not `putAll`. Add a step to Track 4 explicitly designing this
merge contract and add equivalence tests for the multi-hop
multi-filter scenario above.

### Finding A2 [blocker]
**Certificate**: Challenge "Compare.eq → op(field, EQ, lit) — null
literal handling and three-valued logic"
**Target**: Assumption — Gremlin's `eq`/`neq` map cleanly to SQL
binary comparison
**Challenge**: The plan ignores Gremlin/SQL three-valued-logic
divergence. `has("name", null)` (Gremlin) maps to `name IS NULL`,
not `name = NULL` (always-NULL in SQL). `has("name", P.neq("x"))`
(Gremlin) matches records where `name IS NULL` OR `name <> 'x'`,
but SQL `name <> 'x'` excludes nulls. The native pipeline
(`YTDBGraphQueryBuilder.requiresAdditionalNotNull`) adds the
nullness guard explicitly. The plan does not.
**Evidence**:
- `/home/sandra-adamiec/IdeaProjects/youtrackdb/core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/YTDBGraphQueryBuilder.java:79-89,
  282-286`;
- TinkerPop's `Compare.neq`/`Compare.eq` operate on Java equality
  (Objects.equals), so `null != "x"` returns true.
**Proposed fix**: Predicate adapter must:
1. Detect `Compare.eq(null)` → emit `SQLIsNullCondition`.
2. Detect `Compare.neq(null)` → emit `NOT (field IS NULL)`.
3. For all negative predicates (`neq`, `without`,
   `notContaining`, etc.), emit
   `(field IS NOT NULL AND field <op> value)` — preserving
   Gremlin's null-inclusion semantics inverted.
Add an explicit step "predicate adapter null/three-valued-logic
handling" to Track 4.

### Finding A3 [should-fix]
**Certificate**: Challenge "Text.containing → containsText etc."
**Target**: Assumption — TextP predicate translation is direct
**Challenge**: (a) Plan uses `Text` instead of `TextP` (cosmetic);
(b) Plan misses `notContaining`, `notStartingWith`,
`notEndingWith` shapes; (c) `MatchWhereBuilder.startsWith`
concatenates `prefix + "%"` without escaping LIKE metachars,
producing SEMANTIC DIVERGENCE for prefixes containing `%`, `_`,
or `\` (eg `TextP.startingWith("100%off_")`).
**Evidence**:
- `/home/sandra-adamiec/IdeaProjects/youtrackdb/core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/builder/MatchWhereBuilder.java:98-111`
  (Javadoc explicitly warns about no-escape contract).
**Proposed fix**: Track 4 must:
1. Use `TextP` consistently in plan and code.
2. Enumerate the not* shapes and translate via `not(...)`.
3. Either escape LIKE metachars in
   `MatchWhereBuilder.startsWith/endsWith`, or have the
   predicate adapter escape the Gremlin prefix before passing
   it. The escape rule is non-trivial (LIKE has 3 metachars
   and YTDB adds regex translations). Cucumber scenarios with
   escape-relevant prefixes will fail silently.

### Finding A4 [should-fix]
**Certificate**: Challenge "hasId(...) routes through aliasRids
for single-ID — but g.V(id).hasId(other_id)?"
**Target**: Assumption — single-ID hasId is harmlessly written to
aliasRids
**Challenge**: `aliasRids` is single-RID-per-alias. Multiple
`hasId` calls or `g.V(id1).hasId(id2)` produce intersection
semantics in Gremlin but the naive single-ID write OVERWRITES the
existing aliasRid, returning the LATER ID's vertex (wrong).
Multi-ID scenarios that include an existing aliasRid must
intersect, not concat.
**Evidence**:
- `/home/sandra-adamiec/IdeaProjects/youtrackdb/core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/StartStepRecogniser.java:138-141`;
- `WalkerContext.java:64-66` (single-RID-per-alias contract).
**Proposed fix**: Add explicit step in Track 4 for hasId-merge
contract: if `aliasRids[alias]` is already populated, compare new
ID(s) — if equal, no-op; if different, switch to
`aliasFilters[alias] = @rid IN [intersection]` (which is empty if
no intersection, or single-element). The same logic for repeated
`hasId(...)` chains.

### Finding A5 [should-fix]
**Certificate**: Challenge "hasNot(key) maps cleanly to field IS NULL"
**Target**: Assumption — null-check helper exists
**Challenge**: `MatchWhereBuilder.not(op(field, EQ, ...))` is
nonsensical — there's no value to compare against. The plan needs
a dedicated `isNull` helper which does not exist in
`MatchWhereBuilder` today. `SQLIsNullCondition` is the right AST
node; a builder helper is missing.
**Evidence**:
- `/home/sandra-adamiec/IdeaProjects/youtrackdb/core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLIsNullCondition.java:18,
  72`;
- `MatchWhereBuilder.java:36-220` (no isNull helper).
**Proposed fix**: Track 4 adds `MatchWhereBuilder.isNull(field)`
helper returning `SQLIsNullCondition`. Add `MatchWhereBuilder.notExists(field)`
if needed (depending on whether Gremlin `hasNot` includes
property-not-defined vs property-set-to-null distinction).
Also: the `SQLRecordAttribute` left-side branch (for
`hasNot("@class")`) needs a `isNull` overload accepting the AST
node directly, since `@class` IS NULL is a legal SQL.

### Finding A6 [should-fix]
**Certificate**: Challenge "Cucumber test count violation under
multi-class hasLabel"
**Target**: Invariant — Cucumber suite count >= before
**Challenge**: `YTDBHasLabelStep` stores `List<P<? super String>>`
predicates, not a single class name. `hasLabel("Person",
"Software")` becomes `[P.within("Person", "Software")]`. The plan's
"narrows the current alias to a specific class" (singular) glosses
over multi-class and predicate-shape variability. Wrong
implementation can violate Cucumber count gate.
**Evidence**:
- `/home/sandra-adamiec/IdeaProjects/youtrackdb/core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/traversal/step/filter/YTDBHasLabelStep.java:20-21`.
**Proposed fix**: Track 4's hasLabel recogniser must:
(a) Recognise single `P.eq("X")` predicate → class narrowing to X;
(b) Recognise single `P.within("A", "B", ...)` predicate → class
    IN [A, B, ...] narrowing;
(c) DECLINE on any other shape (P.neq, P.regex, multi-predicate,
    etc.) so D3 falls back to native, preserving Cucumber count.
Add explicit decline tests for these shapes.

### Finding A7 [should-fix]
**Certificate**: Challenge "Strategy gate at hasContainers must
be lifted"
**Target**: Non-Goal — plan's enumerated step list ignores the
strategy-level gate
**Challenge**: The strategy declines outright when
`graphStep.getHasContainers().isEmpty()` is false
(`GremlinToMatchStrategy.java:164`). The plan describes Track 4 as
"adds HasStep recognisers" but the recognisers cannot fire on
folded predicates because the strategy rejects the traversal
before the walker runs. Track 4 must (a) lift the strategy's
hasContainers gate, AND (b) lift StartStepRecogniser's
`getHasContainers().isEmpty()` gate (line 108), AND (c) translate
each fold-target HasContainer through the predicate adapter.
The plan implies the third but does not enumerate the first two
gate-lift changes.
**Evidence**:
- `GremlinToMatchStrategy.java:164` (strategy gate);
- `StartStepRecogniser.java:108` (recogniser gate);
- `YTDBGraphStepStrategy.java:119-128` (where the fold happens).
**Proposed fix**: Add an explicit step "lift hasContainers gates
and translate folded predicates in StartStepRecogniser" as the
FIRST step of Track 4. Without this, Track 4 work is unreachable
on `g.V().has(...)` patterns — the most common LDBC shape.

### Finding A8 [suggestion]
**Certificate**: Challenge "DEFAULT_VERTEX_CLASS lift target"
**Target**: Decision D6 — DRY lift convention
**Challenge**: Plan creates a new `DEFAULT_VERTEX_CLASS = "V"`
constant. `SchemaClass.VERTEX_CLASS_NAME = "V"` already exists and
is the canonical project-wide convention.
**Evidence**:
- `/home/sandra-adamiec/IdeaProjects/youtrackdb/core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/schema/SchemaClass.java:34`;
- `/home/sandra-adamiec/IdeaProjects/youtrackdb/core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/YTDBGraphQueryBuilder.java:111`.
**Proposed fix**: Use `SchemaClass.VERTEX_CLASS_NAME` directly in
all three call sites; do not create a parallel constant. Adjust
the plan's DRY lift item.

### Finding A9 [suggestion]
**Certificate**: Challenge "Predicate adapter as static utility"
**Target**: Architecture — adapter shape
**Challenge**: `GremlinPredicateAdapter` has no state; the rest
of Track 3's helpers (`MatchClassFilters`,
`AnonAliasGenerator.isReserved`) are utility classes with all-
static methods. A class is unnecessary boilerplate.
**Evidence**:
- `MatchClassFilters.java:28` (utility class pattern).
**Proposed fix**: Implement `GremlinPredicateAdapter` as a
package-private utility class with all-static methods. Saves
allocation and matches surrounding style.

### Finding A10 [suggestion]
**Certificate**: Challenge "Six steps for predicate adapter +
4 has* + DRY lifts"
**Target**: Scope — track decomposition
**Challenge**: Track 4 packs ~14 P shapes (predicate adapter), 5
recogniser variants, 3 DRY lifts, three-valued-logic handling
(unstated), null-literal handling (unstated), TextP escape
handling (unstated), and equivalence-harness extension into 6
steps. Realistic step count is 8-10.
**Evidence**: Findings A1-A6 each add a step's worth of work the
plan does not enumerate.
**Proposed fix**: Either expand Track 4 to ~9 steps, or split
into Track 4a (predicate adapter + null/three-valued-logic +
basic property HasStep) and Track 4b (T.label / hasLabel /
hasId / hasNot / DRY lifts). The latter is closer to the
"5-7 steps" track-sizing convention from the workflow context.

### Finding A11 [suggestion]
**Certificate**: Challenge "D4 ordering re-litigation"
**Target**: Decision D4 — strategy ordering
**Challenge**: The chosen ordering forces Track 4 to demux
folded T.label vs property predicates from the same `hasContainers`
list, plus separately handle `YTDBHasLabelStep` mid-chain. Running
BEFORE GraphStepStrategy would simplify both. The original
rationale ("we receive YTDBGraphStep as 'root selectivity'") is
post-hoc — Track 4 does not exploit the root-selectivity shape;
it just translates each predicate.
**Evidence**:
- `YTDBGraphStepStrategy.java:119-148`;
- `YTDBHasLabelStep.java:18-105`.
**Proposed fix**: Re-validate D4 ordering once Track 4 is
underway. If the demux complexity exceeds the run-after value,
escalate D4 for revision before Track 4's StartStepRecogniser
modifications land.
