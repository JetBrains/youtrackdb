# Track 4 Risk Review — Filtering + predicates

Phase A iteration 1. Reviewer: risk-review sub-agent.

Tooling: mcp-steroid PSI not invoked in this session — fall back to grep / Read.
Reference-accuracy caveat applies to claims that count call sites or polymorphic
dispatch resolution. Each finding records the search method used.

---

## Part 1: Evidence Certificates

### CRITICAL PATH EXPOSURE

#### Exposure E1: `HasStep` recogniser is invisible without removing the strategy's `hasContainers`-empty gate
- **Track claim**: "`HasStep` — for each `HasContainer` invokes the predicate adapter,
  ANDs all containers, attaches the result as the `where` of the current
  `SQLMatchFilter` via `MatchPatternBuilder`."
- **Critical path trace**:
  1. `GremlinToMatchStrategy.apply()` at
     `core/.../gremlin/translator/strategy/GremlinToMatchStrategy.java:164`
     hard-declines when `graphStep.getHasContainers().isEmpty()` is false.
  2. `YTDBGraphStepStrategy.rebuildTraversal` at
     `core/.../gremlin/traversal/strategy/optimization/YTDBGraphStepStrategy.java:119-128`
     **absorbs every `HasStep` that follows the start `GraphStep`** into
     `YTDBGraphStep.hasContainers`, which is exactly how property predicates land
     in production.
  3. Net effect today: `g.V().has("name","Alice")` produces `[YTDBGraphStep
     (hasContainers=[name=Alice])]`, not `[YTDBGraphStep, HasStep(name=Alice)]`.
  4. EdgeTraversalEquivalenceTest line 156 already asserts this case as
     `Expected.DECLINED`: "Track 4 territory: declines for now because
     hasContainers fold into the start step and the strategy's hasContainers gate
     fires."
  5. `StartStepRecogniser.recognize` at line 108 *also* declines on non-empty
     `getHasContainers()` — defence-in-depth that must be removed in lockstep.
- **Blast radius**: the entire Track 4 `HasStep` story is a no-op until *both* the
  strategy gate and the start-step recogniser learn to translate folded
  hasContainers. The plan describes a `HasStep` recogniser pattern that will
  almost never see a real HasStep in production — most has-chains land on the
  start step. Without addressing this, Track 4 ships:
  - working `hasNot` / mid-chain `hasLabel` / `hasId` if a user *separates* the
    has-chain from the start step (rare in idiomatic Gremlin)
  - silent decline for the dominant LDBC-shape `g.V().has(...).has(...).out(...)`
- **Existing safeguards**:
  - `EdgeTraversalEquivalenceTest.V_has_name_Alice` is currently DECLINED, so a
    Cucumber regression net catches a *false-positive translation*, but **not**
    "Track 4 shipped without making the case RECOGNIZED".
  - The strategy's outer try/catch swallows planner exceptions and declines.
- **Residual risk**: HIGH — Track 4 step file does not call out the gate-removal
  work or the StartStepRecogniser change, even though it lists "`HasStep` with
  `T.label`/`T.id`/`hasNot`" as recogniser shapes. The work item is implicit;
  surfacing it explicitly is required so step decomposition allocates a step
  for "promote folded hasContainers to translated filters in the start-step
  recogniser" with regression coverage.

#### Exposure E2: `aliasFilters` overwrite-not-merge in `MatchPatternBuilder.addNode`
- **Track claim**: "`HasStep` — … attaches the result as the `where` of the
  current `SQLMatchFilter` via `MatchPatternBuilder`."
- **Critical path trace**:
  1. `MatchPatternBuilder.addNode` at
     `core/.../sql/executor/match/builder/MatchPatternBuilder.java:89-91`:
     `if (where != null) { aliasFilters.put(alias, where); }`. This is
     **overwrite on non-null**, *not* AND-merge.
  2. `StartStepRecogniser` writes `@class = 'V'` (non-polymorphic) and `@rid IN
     [...]` (multi-ID) directly to `ctx.aliasFilters`, *not* through the
     pattern builder (line 150-151 of StartStepRecogniser).
  3. `GremlinStepWalker.buildResult` line 168-169:
     ```
     finalAliasFilters = new LinkedHashMap<>(ir.aliasFilters());
     finalAliasFilters.putAll(ctx.aliasFilters);   // ctx wins
     ```
  4. Track 4's HasStep handler claims to write through `MatchPatternBuilder`.
     If it does:
     - `patternBuilder.aliasFilters[alias]` ← hasStepWhere
     - After build → `ir.aliasFilters()[alias]` = hasStepWhere
     - Final → `putAll(ctx.aliasFilters)` overrides → **`@class = 'V'`** wins,
       hasStepWhere is silently dropped.
- **Blast radius**: every non-polymorphic traversal with HasStep filters
  produces wrong results — Cucumber's polymorphic-default scenarios pass, but a
  customer running with `polymorphicQuery=false` sees their `has("name","Alice")`
  filter silently disappear and gets all V instances back.
- **Existing safeguards**:
  - `combineAnd` helper exists in StartStepRecogniser (lines 285-299) but is
    private. The plan's "lift deferred DRY items" includes
    `MatchClassFilters` consolidation but does NOT mention combineAnd.
  - The plan does say "`hasLabel` ... must … ensure any prior chain-target
    `aliasFilters` entry is replaced rather than shadowed" — so the merge
    issue is recognised for `hasLabel` *only*. **The same hazard applies to
    HasStep proper** (and `hasId`, and `hasNot`) and the plan does not
    document it.
- **Residual risk**: HIGH — the plan describes the write path for HasStep as
  "via `MatchPatternBuilder`", which is the wrong write path. The correct
  pattern (mirroring how `hasLabel` was already specified) is "merge into
  `ctx.aliasFilters` with AND-combine of any pre-existing entry". Step
  decomposition must call this out as a uniform write contract for *all*
  Track 4 recognisers.

#### Exposure E3: 4 new recognisers run on every traversal — dispatch loop is O(n×m)
- **Track claim**: "`StepRecogniser` implementations registered in
  `GremlinStepWalker.PRODUCTION_RECOGNISERS` (after the prior track's
  start/vertex/no-op-barrier entries; declaration order is first-match wins)."
- **Critical path trace**:
  1. `GremlinStepWalker.walk` line 110-122: `for (Step) for (recogniser)
     recogniser.recognize(step, ctx)`. Today m=3, n = traversal step count.
  2. After Track 4: m grows to 7 (HasStep, HasStep+T.label, YTDBHasLabelStep,
     HasStep+hasId, HasStep+hasNot — though the plan reuses HasStep as a
     dispatch root, so realistically m=4 new recognisers, total m=7).
  3. Recogniser registry visits in order; first to claim wins. Common-case
     traversal: `g.V().out(label).out(label)` walks 5 steps (including 2
     barriers), each step probing 7 recognisers → 35 instanceof checks +
     guards. Already O(n×m) without the plan's PF6 deferred fix.
  4. Per-step cost is dominated by the `instanceof` cascade plus `Step.getLabels`
     allocation in some recognisers; recogniser order matters because StartStep
     is at index 0 and would fall through every later step's check.
- **Blast radius**: planning-time per-traversal regression scales linearly with
  recogniser count. For LDBC traversals (5-15 steps), Track 4 grows m from 3 to
  7 → ~2× dispatch cost relative to Track 3.
- **Existing safeguards**:
  - All recognisers have early-return `instanceof` guards; the cost is bounded
    per recogniser.
  - Track 3's Track-level review filed PF1-PF6 against the dispatch loop; Track
    12's perf baseline measures against the recognised set as it stands at end
    of Phase 1 — Track 4 grows that set but the baseline absorbs the cost.
- **Residual risk**: MEDIUM — for Phase 1 the per-step overhead is acceptable
  (instanceof on 7 candidates is ~50ns at most). But Track 5+ continues to
  add recognisers; without a dispatch index keyed on step class (see Track 3's
  PF6) the cost grows linearly. Step decomposition should at minimum measure
  the dispatch overhead at end of Track 4 so Track 12's baseline has signal.

### UNKNOWNS & ASSUMPTIONS

#### Assumption A1: `aliasClasses[alias]` write target is "natural" for hasLabel under polymorphic mode
- **Track claim**: "In polymorphic mode (default), the natural write target is
  `aliasClasses[alias]` — the planner handles subclass expansion via `class IN
  [...]`."
- **Evidence search**: read MatchExecutionPlanner.java around `createSelectStatement`
  (line 4403) and `buildPatterns` (line 4433); grep for "polymorphic", "subclass",
  "class IN".
- **Code evidence**: `createSelectStatement` line 4411-4412 writes
  `fromItem.setIdentifier(new SQLIdentifier(targetClass))`. The resulting
  `SELECT FROM Person` is *implicitly polymorphic* by SQL semantics — it matches
  Person and all subclasses, full stop. There is **no `class IN [...]`**
  expansion in the planner; the planner does not enumerate subclasses. The
  "subclass expansion" is just MATCH/SQL polymorphic-by-default.
- **Verdict**: PARTIALLY VALIDATED — the write target *is* correct, but the
  description "the planner handles subclass expansion via `class IN [...]`" is
  **incorrect**. Polymorphic semantics come from `SELECT FROM Class` defaulting
  to all-subclasses; non-polymorphic mode is enforced by an explicit `WHERE
  @class = '...'` filter (which `StartStepRecogniser` already adds and the
  Track 4 description correctly identifies as the override target).
- **Detail**: misleading wording — the correct mental model is "polymorphic
  uses `aliasClasses[alias]` only; non-polymorphic uses `aliasClasses[alias]`
  PLUS an `@class = '<className>'` AND-merged into `aliasFilters[alias]`".

#### Assumption A2: alias-filter merge direction unchanged since Track 3
- **Track claim**: "the walker's merge order pins context entries to override
  builder entries on the same alias (`finalAliasFilters.putAll(ctx.aliasFilters)`)"
- **Evidence search**: read `GremlinStepWalker.buildResult` lines 165-175.
- **Code evidence**:
  ```java
  Map<String, SQLWhereClause> finalAliasFilters = new LinkedHashMap<>(ir.aliasFilters());
  finalAliasFilters.putAll(ctx.aliasFilters);
  ```
  `putAll` overwrites on key collision — `ctx` wins. Track 4's claim matches
  the code byte-for-byte.
- **Verdict**: VALIDATED.
- **Detail**: the merge direction is stable. Track 4's hasLabel narrowing
  must indeed write through `ctx.aliasFilters` to win. Note however that
  even writing through `ctx.aliasFilters` does NOT solve Track 4's broader
  HasStep merge problem (E2): two writes to the same `ctx.aliasFilters[alias]`
  also overwrite (it's a `LinkedHashMap.put`); Track 4 must AND-merge with
  any pre-existing `ctx.aliasFilters[alias]` value before storing.

#### Assumption A3: Cached `SQLInCondition.operator` reflection is safe to share between MatchWhereBuilder and recognisers
- **Track claim**: "Cached `SQLInCondition.operator` reflection — already
  duplicated in `MatchWhereBuilder.setInOperator` and
  `StartStepRecogniser.setInOperator` (with cached `SQL_IN_OPERATOR_FIELD`).
  The multi-ID `hasId` path is the third call site; lift the cached field +
  helper into a shared utility under `match.builder/`."
- **Evidence search**: read both `setInOperator` methods. MatchWhereBuilder
  resolves the field on every call (no caching!) at lines 210-220.
  StartStepRecogniser caches it at class-load time at line 309.
- **Code evidence**:
  - `MatchWhereBuilder.setInOperator` does **NOT** cache — it calls
    `SQLInCondition.class.getDeclaredField("operator")` on every invocation
    (line 212).
  - `StartStepRecogniser.setInOperator` uses the class-loaded cache.
  - These are two *different* reflective approaches.
- **Verdict**: CONTRADICTED on a detail.
- **Detail**: The plan asserts "duplicated" but the two implementations are
  *not* duplicates — they differ in caching strategy. The lift is correct in
  spirit (one helper for both) but the work is NOT just "move the cached
  field"; it includes converting MatchWhereBuilder's per-call resolution to
  the cached form. Same package (`internal/core/sql/executor/match/builder/`),
  no classloader concerns. Visibility: the helper must be at least
  package-private, ideally same package as MatchWhereBuilder.

#### Assumption A4: `Text` predicates exist as `Text.containing` etc. in the TinkerPop fork
- **Track claim**: "`Text.containing` → `containsText(field, substring)`.
  `Text.startingWith` / `endingWith` → `startsWith` / `endsWith`."
- **Evidence search**: grep TinkerPop usage in codebase.
- **Code evidence**: `core/src/test/java/.../YTDBHasLabelProcessTest.java:16`
  imports `org.apache.tinkerpop.gremlin.process.traversal.TextP` — TinkerPop
  fork uses **TextP**, not `Text`. The class is `TextP`, with static factory
  methods `TextP.startingWith(String)`, `TextP.containing(String)`, etc.
- **Verdict**: CONTRADICTED on naming.
- **Detail**: The plan uses old TinkerPop nomenclature (`Text` is from earlier
  versions). In TinkerPop 3.4+ the class is `TextP` and its `BiPredicate` is
  accessed via `predicate.getBiPredicate()` returning a `Text` enum (which
  *is* still called `Text` and lives in
  `org.apache.tinkerpop.gremlin.process.traversal.Text` as the enum). Track 4
  must dispatch on both `TextP` instance check AND `Text` enum BiPredicate
  check — the surface dispatch type for the user is `TextP`. Step
  decomposition's predicate-adapter step needs concrete naming so the step
  doesn't ship with a half-correct enum-only check.

#### Assumption A5: `P.between/inside/outside` are recursively encoded as `P.and/or` — adapter must handle both forms
- **Track claim**: "`P.between(lo, hi)` → `between(field, lo, hi)`. `P.inside(lo,
  hi)` → `and(op(field, GT, lo), op(field, LT, hi))`. … `P.and(...)` /
  `P.or(...)` / `P.not(...)` → recursive composition via
  `MatchWhereBuilder.and/or/not`."
- **Evidence search**: design.md line 346-353 (read above).
- **Code evidence**: design.md notes "P.between, P.inside, P.outside are
  typically implemented as P.and(gte, lt) or P.or(lt, gt) in TinkerPop, and
  recursion handles them. We override the common cases for cleaner output".
  TinkerPop's `P.between(lo, hi)` is indeed implemented as
  `AndP(gte(lo), lt(hi))` — a `ConnectiveP<AndP>`.
- **Verdict**: VALIDATED but with a subtle pitfall.
- **Detail**: the adapter must NOT match on the surface API method name (e.g.
  detect "between" by string) — it must match on the resulting `ConnectiveP`
  subtree shape if the override-for-cleaner-output path is desired. If the
  adapter only handles ConnectiveP recursively, `P.between(1, 10)` works but
  emits `AND(>= 1, < 10)` instead of `BETWEEN 1 AND 10` — semantically
  identical, but the planner's range-aware index lookup may not fire on the
  decomposed form. The plan says "override the common cases" but doesn't say
  how — the step file should record either (a) "shape-detect ConnectiveP for
  between/inside/outside" or (b) "accept the AND-decomposed form, accept
  index-lookup degradation for Phase 1".

#### Assumption A6: Custom user-defined `P` instances are detectable by BiPredicate type-check
- **Track claim** (implicit): "`Compare.eq` → `op(field, EQ, lit)` …" — plan
  enumerates known predicates. Design.md line 361-364: "If `P.getBiPredicate()`
  is not an instance of `Compare`, `Contains`, `Text`, or a recognized
  YTDB-side predicate, decline."
- **Evidence search**: existing YTDBGraphQueryBuilder line 263-280 dispatches
  on `predicate instanceof Compare` and `predicate instanceof Contains` —
  not on getBiPredicate. The two are different patterns (P vs the BiPredicate
  it wraps).
- **Code evidence**: For TinkerPop's `P.eq(value)`, `P.getBiPredicate()`
  returns the `Compare.eq` enum constant. For `P.and(p1, p2)` (a `ConnectiveP`),
  `P.getBiPredicate()` returns the AND-combinator BiPredicate, not Compare.eq.
- **Verdict**: VALIDATED — the BiPredicate type-check is the right
  discriminator for non-connective predicates; ConnectiveP detection requires
  a separate `instanceof ConnectiveP` check before testing the BiPredicate.
- **Detail**: the adapter has TWO levels of dispatch: (1) `if (p instanceof
  ConnectiveP) recurse on .getPredicates()`; (2) else `switch (p.getBiPredicate())`.
  This is well-supported by TinkerPop's class hierarchy but the step file
  must spell it out — otherwise an implementer who reads only the plan's
  predicate table writes a flat `getBiPredicate()` switch that misses
  `P.and(...)` entirely.

### PERFORMANCE IMPLICATIONS

(Covered by E3 above; no additional certificate needed — the dispatch loop
cost is the dominant Phase-1 risk.)

### TESTABILITY & COVERAGE

#### Testability T1: Predicate adapter unit tests vs end-to-end equivalence
- **Coverage target**: 85% line / 70% branch on
  `GremlinPredicateAdapter` and the four new recognisers.
- **Difficulty assessment**:
  - Adapter is pure: input `P<?>`, output `SQLBooleanExpression`. Unit-testable
    without graph fixture; assertions on AST shape (operator class, leaf values)
    are stable and cheap. Achievable to 100% line + branch with 8-12 tests
    covering the predicate matrix.
  - Recognisers each have a 5-10 line decline cascade and a commit body. Same
    test pattern as `VertexStepRecogniserTest` — pre-populate `WalkerContext`
    with a successful StartStepRecogniser claim, invoke `recognize`, assert
    state mutations or absence. Achievable.
  - End-to-end equivalence (`EdgeTraversalEquivalenceTest` extension) needs
    schema with multiple classes, properties, predicates — significantly more
    fixture setup than current Phase-1 cases. The harness currently has 13
    test cases; Track 4 needs to add ~30+ (5 predicates × 4 has-shapes × 2
    polymorphism modes minus duplicates).
- **Existing test infrastructure**:
  - `VertexStepRecogniserTest` provides the recogniser unit-test pattern;
    `WalkerContextFixtures` (deferred DRY lift) would centralise the
    pre-populate boilerplate.
  - `EdgeTraversalEquivalenceTest` is parameterised; case list is a static
    `List.of(...)`. Adding 30 more entries is mechanical.
  - `GraphBaseTest` provides the schema/graph lifecycle.
- **Feasibility**: ACHIEVABLE.
- **Detail**: per-step coverage targets are reachable; the dimensional risk is
  not coverage but case explosion in EdgeTraversalEquivalenceTest. The
  parameterised harness is fine for ~50 cases; beyond that the test
  matrix becomes unreviewable. The step file should split the equivalence
  cases across the 5 implementation steps so review tractable.

#### Testability T2: Non-polymorphic mode coverage in equivalence harness
- **Coverage target**: equivalence-harness coverage for non-polymorphic mode.
- **Difficulty assessment**:
  - Existing `EdgeTraversalEquivalenceTest` only exercises the default
    polymorphic mode (the seed schema has `Person`/`Place` flat without a
    subclass hierarchy, so the polymorphism flag is largely a no-op).
  - Non-polymorphic mode + `hasLabel` requires:
    - schema with class hierarchy (e.g. Person ← Employee)
    - traversal source configured with `polymorphicQuery=false`
    - assertion that the result excludes Employee instances
  - The plan explicitly calls out chain-target hasLabel under both polymorphism
    modes as a verification target ("chain-target hasLabel under both
    polymorphism modes to lock in the merge-direction fix").
- **Existing test infrastructure**:
  - `YTDBStrategyUtil.isPolymorphic(traversal)` reads from the traversal's
    OptionsStrategy; tests can flip it via `traversal.withStrategies(...)`.
  - No existing test in the equivalence harness uses non-polymorphic mode.
- **Feasibility**: DIFFICULT.
- **Detail**: the harness extension for non-polymorphic is non-trivial — needs
  a per-case polymorphism flag, schema-class hierarchy, and an assertion that
  result divergence between modes is captured. Step decomposition should
  allocate a dedicated step for "non-polymorphic equivalence harness" or fold
  it into the hasLabel step explicitly with the schema fixture work itemised.

### ROLLBACK & RECOVERY

#### Testability T3: Rollback story for the deferred-DRY lift
- **Coverage target**: cached reflection field consolidated into
  `match.builder/` shared helper.
- **Difficulty assessment**:
  - The lift moves a *static final* field with class-load-time initialisation.
    If the lift introduces a regression that loses the cache (e.g. by moving
    to per-call resolution), the perf hit is hidden in plan-time microseconds —
    not user-visible without a benchmark.
  - The lift also touches `MatchWhereBuilder.setInOperator` (currently per-call
    resolution) which must be converted to cached form simultaneously. A
    half-applied lift (helper exists, MatchWhereBuilder still does per-call)
    is silently undetectable.
- **Existing safeguards**:
  - JMH benchmarks (LDBC SNB) are on Track 12's roadmap; would catch a
    regression but only at end-of-phase.
  - Unit tests for IN-condition encoding don't measure timing — would pass
    even with broken cache.
- **Feasibility**: ACHIEVABLE WITH CARE.
- **Detail**: the lift is reversible (restore both call sites to their pre-lift
  forms); state is purely in-memory. The risk is silent perf degradation, not
  data corruption. Mitigation: a unit test that calls `setInOperator` 10× in
  a loop and asserts the cached field is reused (via a fixture that wraps
  `getDeclaredField` and counts calls) would lock in the cache contract. Step
  decomposition should include this test as part of the lift step.

---

## Part 2: Findings

### Finding R1 [blocker]
**Certificate**: Exposure E1
**Location**: track-4.md description; `GremlinToMatchStrategy.java:164` (the
`hasContainers`-empty gate); `StartStepRecogniser.java:108` (the same
defence-in-depth check).
**Issue**: The plan describes a `HasStep` recogniser pattern, but in the
production strategy chain `HasStep`s following the start `GraphStep` are
**absorbed** into `YTDBGraphStep.hasContainers` by `YTDBGraphStepStrategy`
(running BEFORE `GremlinToMatchStrategy`). The strategy then declines the whole
traversal at the empty-hasContainers gate, so the `HasStep` recogniser
realistically never sees a real HasStep — only the rare mid-chain has-after-vertex
pattern. **Likelihood: certain** — this is how the strategy chain runs every
day. **Impact: HIGH** — Track 4 ships without translating the dominant
LDBC-shape `g.V().has(...)`. EdgeTraversalEquivalenceTest line 156 already
documents this case as DECLINED for "Track 4 territory"; the case must flip to
RECOGNIZED in Track 4, which requires removing the gate.
**Proposed fix**:
1. Add a step (or expand the HasStep step) in track-4.md that explicitly:
   - Removes the `graphStep.getHasContainers().isEmpty()` decline gate from
     `GremlinToMatchStrategy.apply()`.
   - Removes the same gate from `StartStepRecogniser.recognize()`.
   - Teaches `StartStepRecogniser` to translate folded `hasContainers` from the
     `YTDBGraphStep` by invoking the predicate adapter on each `HasContainer`
     and AND-merging the result into `ctx.aliasFilters[$g2m_v0]` (with proper
     merge — see Finding R2).
2. Flip `V_has_name_Alice` in `EdgeTraversalEquivalenceTest` from DECLINED to
   RECOGNIZED in the same step.
3. Add equivalence cases for `g.V().has(prop, eq(v)).out(label)` to verify
   folded predicates ride through the chain.

### Finding R2 [blocker]
**Certificate**: Exposure E2
**Location**: track-4.md "HasStep — for each HasContainer invokes the predicate
adapter, ANDs all containers, attaches the result as the `where` of the current
`SQLMatchFilter` via `MatchPatternBuilder`."
**Issue**: The plan instructs the HasStep recogniser to write through
`MatchPatternBuilder.addNode(alias, …, where, …)`. But `addNode`'s where
handling is overwrite-on-non-null (`MatchPatternBuilder.java:89-91`), and the
walker's merge order (`finalAliasFilters.putAll(ctx.aliasFilters)`) makes
`ctx.aliasFilters` win on alias collision. So a HasStep filter written through
the builder is silently overridden by any `ctx.aliasFilters` entry on the
same alias (e.g. `StartStepRecogniser`'s non-polymorphic `@class = 'V'`).
**Likelihood: certain** for non-polymorphic traversals with HasStep filters.
**Impact: HIGH** — `polymorphicQuery=false` users see their property filters
silently disappear and get the wrong (over-broad) result set.
**Proposed fix**: Update track-4.md to specify a uniform write contract for
*all* Track 4 recognisers (HasStep, hasLabel, hasId, hasNot):
- Always write filter contributions through `ctx.aliasFilters`, never through
  `MatchPatternBuilder.addNode(where=…)`.
- Before writing, AND-merge with any pre-existing `ctx.aliasFilters[alias]`
  value (using the lifted `combineAnd` helper — see R5 below).
- Document the contract once in the plan, applied to all four recognisers.

### Finding R3 [should-fix]
**Certificate**: Assumption A1
**Location**: track-4.md "the planner handles subclass expansion via `class IN
[...]`."
**Issue**: The wording is misleading. The planner does NOT enumerate subclasses
into a `class IN [...]` predicate. Polymorphic semantics come for free from
SQL's `SELECT FROM Class` defaulting to all-subclasses; non-polymorphic mode
is enforced by an explicit `WHERE @class = '<className>'` filter. Anyone
implementing track-4 from the plan alone would search for non-existent
"subclass expansion" code in the planner.
**Proposed fix**: Replace "the planner handles subclass expansion via `class
IN [...]`" with "the planner uses MATCH/SQL polymorphic-by-default
semantics — `SELECT FROM Class` matches Class plus all subclasses without
explicit enumeration; non-polymorphic mode requires an explicit `@class =
'<className>'` AND-merged into `aliasFilters[alias]`".

### Finding R4 [should-fix]
**Certificate**: Assumption A4
**Location**: track-4.md "`Text.containing` → `containsText(field, substring)`.
`Text.startingWith` / `endingWith` → `startsWith` / `endsWith`."
**Issue**: TinkerPop fork uses `TextP` (not `Text`) as the user-facing predicate
factory class. The `Text` enum still exists as the underlying BiPredicate
token, but the dispatch surface is `TextP` instances. The plan's wording
suggests dispatching on `Text.containing`, which works at the BiPredicate level
but obscures that the *step* sees `TextP` instances on `HasContainer.predicate`.
**Likelihood: low** for a careful implementer who reads design.md too;
**impact: low** — at worst a 30-minute pivot during step implementation.
**Proposed fix**: Update track-4.md predicate matrix to clarify:
- "TextP.containing(s) (BiPredicate = Text.containing) → containsText(field, s)"
- "TextP.startingWith(s) (BiPredicate = Text.startingWith) → startsWith(field, s)"
- "TextP.endingWith(s) (BiPredicate = Text.endingWith) → endsWith(field, s)"
This makes the two-level dispatch (instanceof TextP, then enum-switch on
BiPredicate) explicit.

### Finding R5 [should-fix]
**Certificate**: Assumption A3 + Exposure E2
**Location**: track-4.md "Lift deferred DRY items in this track: … Cached
`SQLInCondition.operator` reflection — already duplicated …"
**Issue**: Two related issues:
(a) The lift is described as duplicated cached reflection, but
`MatchWhereBuilder.setInOperator` does NOT cache today — it does per-call
resolution. The lift work is bigger than "move a field": it includes
*introducing* caching at the MatchWhereBuilder call site.
(b) The lift inventory misses a third deferred-DRY item required by Track 4:
`combineAnd` (currently private in StartStepRecogniser, used to AND-merge
optional boolean expressions). Track 4's filter merge contract (R2) needs
this helper, plus the existing call site uses it. Lifting it now consolidates
three call sites: StartStepRecogniser's existing one, the new HasStep merge,
and the new hasLabel chain-target merge.
**Proposed fix**: Update the "Lift deferred DRY items" section to:
- Spell out that `MatchWhereBuilder.setInOperator` switches to cached form in
  the same step.
- Add `combineAnd` (or a `MatchWhereBuilder.andOptional(SQLBooleanExpression,
  SQLBooleanExpression)` helper) to the lift list.
- Add a unit test for the cached field that asserts cache reuse (e.g. via a
  reflection-call counter) so a future regression to per-call resolution is
  caught.

### Finding R6 [should-fix]
**Certificate**: Assumption A5 + A6
**Location**: track-4.md "P.and(...) / P.or(...) / P.not(...) → recursive
composition via MatchWhereBuilder.and/or/not."
**Issue**: The plan's predicate adapter description does not surface the
two-level dispatch needed for ConnectiveP (`AndP`, `OrP`) recursion. An
implementer reading only the predicate matrix could write a flat
`switch (p.getBiPredicate())` that fails to detect `P.and(p1, p2)` — TinkerPop
encodes `P.and` as `AndP(p1, p2)` (a ConnectiveP subclass), and its
BiPredicate is the AND-combinator, not `Compare.eq`. Same for `P.between`
which decomposes to `AndP(gte, lt)` internally.
**Proposed fix**: Add to track-4.md predicate adapter section:
- "Dispatch in two levels: (1) `if (p instanceof ConnectiveP)` recurse on
  `getPredicates()` and combine via `MatchWhereBuilder.and/or`; (2) else
  switch on `p.getBiPredicate()` (Compare/Contains/Text enum constants)."
- "`P.between(lo, hi)` arrives as `AndP(gte(lo), lt(hi))`; the recursive
  ConnectiveP path handles it cleanly. The plan's optional override to a
  single `SQLBetweenCondition` requires shape-detecting the AndP children
  (gte+lt with same field, hoisting the field through both children)."
- Document whether Track 4 ships shape-detect (cleaner output, more code) or
  accepts AND-decomposed form (simpler, may lose range index lookup).

### Finding R7 [suggestion]
**Certificate**: Testability T2
**Location**: track-4.md "chain-target hasLabel under both polymorphism modes
to lock in the merge-direction fix."
**Issue**: The current `EdgeTraversalEquivalenceTest` does not exercise
non-polymorphic mode at all (no per-case polymorphism flag, no class-hierarchy
schema in the seed fixture). Track 4 needs both — schema with subclass
relations, and a mechanism for per-case polymorphism. Without an explicit
sub-step for this work, the verification described in the plan is not
achievable as-is.
**Proposed fix**: Add an explicit step (or expand the hasLabel step) that:
- Extends the seed fixture with a class hierarchy (e.g. `Person ←
  Employee ← Manager`).
- Adds a per-case polymorphism flag to the `Object[] cases()` parameter
  array, plumbed through to `withStrategies(OptionsStrategy.build()...)`.
- Adds at least 4 cases: polymorphic+subclass-hasLabel, polymorphic+
  superclass-hasLabel, non-polymorphic+exact, non-polymorphic+subclass.

### Finding R8 [suggestion]
**Certificate**: Exposure E3 + Testability T1
**Location**: track-4.md, "Verification methodology" section.
**Issue**: Track 4 grows the `EdgeTraversalEquivalenceTest` case list from 13
to ~50+ entries. The parameterised harness is fine for ~50 cases but reviewing
a 50-case static `List.of(...)` for completeness is hard. Mistakes (typos,
missing combinations, mis-classified RECOGNIZED/DECLINED) silently slip past.
**Proposed fix**: Split the equivalence-test additions across the 5
implementation steps so each step adds ~5-10 cases tightly scoped to its
handler. Document the split in the step file: predicate-adapter step adds
basic predicate cases; HasStep step adds property-filter cases; hasLabel
step adds class-narrowing + polymorphism cases; hasId step adds RID-filter
cases; hasNot step adds null-check cases. This keeps each step's review
unit small and targeted.

### Finding R9 [suggestion]
**Certificate**: Exposure E3
**Location**: track-4.md "registered in `GremlinStepWalker.PRODUCTION_RECOGNISERS`
(after the prior track's start/vertex/no-op-barrier entries; declaration order
is first-match wins, so insertion point matters)."
**Issue**: Track 4 grows the recogniser list from 3 to ~7. Track 3's PF1-PF6
deferred performance findings flagged the dispatch loop; Track 4 doubles m
without addressing the dispatch index. Track 12's perf baseline measures the
overall translation cost but cannot easily attribute regressions to specific
recogniser additions.
**Proposed fix**: Either (a) accept Phase 1's dispatch overhead as
documented (already on Track 12's measurement scope), or (b) include a
microbenchmark in Track 4's verification that measures `walk()` cost on a
representative 10-step traversal before and after Track 4's recogniser
additions, so any non-linear regression surfaces inside the track. Option
(a) is acceptable; (b) is defensive.

---

**Summary**: 9 findings — 2 blocker, 5 should-fix, 2 suggestion.
