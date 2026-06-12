# Track 4 Technical Review — Filtering + predicates

## Part 1: Evidence Certificates

### Premise certificates

**P1 — `MatchWhereBuilder.containsText` / `startsWith` / `endsWith` exist.**
- Track claim: predicate adapter calls `containsText`, `startsWith`, `endsWith`
  on `MatchWhereBuilder`.
- Search: read
  `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/builder/MatchWhereBuilder.java`.
- Code location: lines 87–111.
- Actual behavior: `containsText(field, substring)` builds an
  `SQLContainsTextCondition` (`field CONTAINSTEXT 'substring'`).
  `startsWith(field, prefix)` and `endsWith(field, suffix)` build LIKE
  expressions with `prefix + "%"` / `"%" + suffix`. Neither method
  escapes LIKE meta-characters in the input.
- Verdict: **CONFIRMED** with caveat — the LIKE methods do not escape
  `%`, `_`, `\`. Track 4 must either escape upstream or document the
  divergence vs Gremlin's literal-substring `TextP.startingWith` /
  `TextP.endingWith` (which do not interpret meta-characters).

**P2 — `MatchWhereBuilder.between(field, lo, hi)` accepts `SQLExpression`.**
- Track claim: `P.between(lo, hi)` → `between(field, lo, hi)`.
- Search: same file, lines 77–83.
- Actual behavior: signature is
  `between(String field, SQLExpression lo, SQLExpression hi)`. Caller must
  convert raw Gremlin values to `SQLExpression` via `MatchLiteralBuilder`.
- Verdict: **CONFIRMED**.

**P3 — `MatchWhereBuilder.in` left side is hard-coded to `SQLIdentifier`.**
- Track claim: multi-ID `hasId` must hand-build the `IN` AST because
  `@rid` is `SQLRecordAttribute`, not `SQLIdentifier`.
- Search: same file, lines 59–65, plus
  `StartStepRecogniser.java` lines 244–278.
- Actual behavior: `MatchWhereBuilder.in` calls
  `condition.setLeft(fieldExpression(name))` where `fieldExpression` wraps
  a bare `SQLIdentifier`. `StartStepRecogniser.buildRidInExpression`
  already constructs a hand-rolled `SQLInCondition` with an
  `SQLRecordAttribute` left side and re-uses cached
  `SQL_IN_OPERATOR_FIELD` reflection.
- Verdict: **CONFIRMED**. Track 4's third call site is genuinely the
  natural lift point for the cached field.

**P4 — `WalkerContext.polymorphic` is pinned by `StartStepRecogniser`.**
- Track claim: hasLabel recogniser must read `WalkerContext.polymorphic`,
  not call `YTDBStrategyUtil.isPolymorphic(traversal)` again.
- Search:
  `WalkerContext.java` lines 97–108;
  `StartStepRecogniser.java` lines 126–158.
- Actual behavior: polymorphism flag pinned by start-step recogniser at
  first claim, default `true` until pinned. Convention is
  StartStepRecogniser always claims first.
- Verdict: **CONFIRMED**. Reading `ctx.polymorphic` is correct.

**P5 — Walker merge order: `finalAliasFilters.putAll(ctx.aliasFilters)` —
context overrides builder.**
- Track claim: hasLabel must write through `ctx.aliasFilters` to override
  the prior `VertexStepRecogniser` entry; otherwise stale `@class = 'V'`
  shadows the more-specific class.
- Search: `GremlinStepWalker.java` lines 165–199.
- Actual behavior: `Map<String, SQLWhereClause> finalAliasFilters = new
  LinkedHashMap<>(ir.aliasFilters()); finalAliasFilters.putAll(ctx.aliasFilters);`
  — context entries override builder entries on the same alias.
- Verdict: **CONFIRMED**. The track's merge-direction analysis is
  correct.

**P6 — `aliasClasses` overwrite semantics in `MatchPatternBuilder.addNode`.**
- Track claim: hasLabel writes via `aliasClasses` (polymorphic mode) so
  later aliasClasses entries overwrite the earlier "V" default.
- Search: `MatchPatternBuilder.java` lines 71–93.
- Actual behavior: `addNode` is merge-not-replace; `className` overwrites
  when non-null/non-blank, preserves when null/blank. So a later
  `addNode(alias, "Person", null, false)` correctly overwrites the prior
  `addNode(alias, "V", null, false)` in the `aliasClasses` map.
- Verdict: **CONFIRMED**.

**P7 — Polymorphic class lookup: `aliasClasses[alias] = "Person"` does NOT
"expand to class IN [...]".**
- Track claim: "In polymorphic mode the natural write target is
  `aliasClasses[alias]` — the planner handles subclass expansion via
  `class IN [...]`."
- Search:
  `MatchExecutionPlanner.java` lines 4403–4417 (`createSelectStatement`),
  full file scan for `"class IN"` (no matches).
- Actual behavior: `createSelectStatement` builds `SELECT FROM <class>`
  with the class as `SQLIdentifier`. YTDB SQL `SELECT FROM Class` is
  **polymorphic by default** — i.e. it returns instances of `Class` and
  all its subclasses without any `class IN [...]` mechanism. Non-
  polymorphic narrowing is achieved by adding `WHERE @class = '<name>'`
  to the filter (which is exactly what `VertexStepRecogniser` does).
  There is no `class IN [...]` rewrite anywhere in the planner.
- Verdict: **WRONG (description error, not a functional bug).** The
  desired behavior — polymorphic match — is what naturally happens when
  `aliasClasses[alias]` is set; the description's mechanism is wrong but
  the outcome is right. The track's prose should be corrected so future
  readers don't go searching for non-existent `class IN` code.

**P8 — `g.V().has(key, val)` with HasContainers absorbed into
`YTDBGraphStep`.**
- Track claim: a `HasStep` recogniser invokes the predicate adapter for
  each `HasContainer` and ANDs them as the alias's WHERE.
- Search:
  `YTDBGraphStepStrategy.java` lines 95–164;
  `GremlinToMatchStrategy.java` lines 161–166;
  `EdgeTraversalEquivalenceTest.java` lines 154–156 (existing DECLINED
  case `V_has_name_Alice`).
- Actual behavior: `YTDBGraphStepStrategy.rebuildTraversal` absorbs every
  `HasStep` immediately following the `GraphStep` into the `YTDBGraphStep`
  via `currentGraphStep.addHasContainer(hc)` (lines 122–128) and removes
  the original `HasStep`. After this strategy fires (it runs before
  `GremlinToMatchStrategy` per `applyPrior`), `g.V().has("name", "Alice")`
  is a single `YTDBGraphStep[hasContainers=[name=Alice]]` — there is no
  `HasStep` for Track 4's `HasStep` recogniser to claim. `GremlinToMatchStrategy.apply`
  at line 164 declines (`if (!graphStep.getHasContainers().isEmpty()) return;`).
  The existing equivalence test `V_has_name_Alice` is `DECLINED` with
  comment "Track 4 territory: declines for now because hasContainers fold
  into the start step and the strategy's hasContainers gate fires."
- Verdict: **WRONG.** Track 4's recogniser-only approach cannot reach
  `g.V().has(...)` traversals because there is no `HasStep` left to
  recognise — the start step has already absorbed the containers. Track 4
  must additionally either (a) remove the strategy gate at line 164 of
  `GremlinToMatchStrategy` AND extend `StartStepRecogniser` to translate
  the absorbed `HasContainers`, or (b) add an alternative
  pre-translation pass that re-injects the absorbed containers as a
  HasStep before the walker runs (worse — fights the platform). This is
  the dominant filter-translation case and the track description does not
  mention it.

**P9 — Mid-chain `g.V().out("knows").has("name", "Alice")` keeps a
`HasStep`.**
- Track claim: HasStep recogniser handles HasStep mid-chain.
- Search: same code paths as P8. After `out("knows")` the
  `isTraversalStart` flag in `YTDBGraphStepStrategy.rebuildTraversal`
  becomes `false` (line 159: `else { isTraversalStart = false; }`),
  so subsequent HasSteps are NOT absorbed into the start step. They stay
  as plain `HasStep` objects in the chain (only `T.label` containers get
  pulled out and reinjected as `YTDBHasLabelStep`).
- Verdict: **CONFIRMED.** Mid-chain `has()` survives as `HasStep`.

**P10 — `T.label` HasContainer routing through `YTDBHasLabelStep`.**
- Track claim: `HasStep` with a `T.label` container that's not yet folded
  into the graph step (rare after `YTDBGraphStepStrategy`) — translates
  to a class constraint.
- Search: `YTDBGraphStepStrategy.java` lines 130–148.
- Actual behavior: when `isTraversalStart=false`, T.label predicates are
  pulled out of the HasStep and packaged into a NEW `YTDBHasLabelStep`
  (line 144). The original HasStep keeps any remaining non-label
  containers; if it becomes empty, it is removed (line 154–157). So
  mid-chain `hasLabel("Person")` ALWAYS appears as `YTDBHasLabelStep`
  after `YTDBGraphStepStrategy`, never as a `HasStep` carrying a T.label
  container. The "rare" framing in the track is incorrect — it is in
  fact **never** the observable shape after the prior strategy runs.
- Verdict: **PARTIAL.** The intended translation behavior is fine, but
  the description should clarify that mid-chain T.label has-containers
  always come through `YTDBHasLabelStep`, not via a `HasStep` with
  T.label. (For the start-step case with absorbed T.label containers,
  see P8.)

**P11 — `Text.containing` lives in `TextP`, not `Text`.**
- Track claim: "`Text.containing` → `containsText(field, substring)`."
- Search: `YTDBHasLabelProcessTest.java` line 16 (`import
  org.apache.tinkerpop.gremlin.process.traversal.TextP;`) and lines
  237/241/250 (`TextP.startingWith("J")`, `TextP.endingWith("s")`).
- Actual behavior: in TinkerPop 3.7+ the text predicates live on
  `TextP`, not on a `Text` enum/class. The track description's
  shorthand `Text.containing` should be `TextP.containing`.
- Verdict: **PARTIAL (cosmetic).** Implementation must use
  `org.apache.tinkerpop.gremlin.process.traversal.TextP`. Description is
  imprecise.

**P12 — `containsText` is case-sensitive `indexOf`.**
- Track claim: `Text.containing` → `containsText`.
- Search: `SQLContainsTextCondition.java` lines 32–43 / 46–63.
- Actual behavior: `((String) leftValue).indexOf((String) rightValue) > -1`
  — case-sensitive. TinkerPop `TextP.containing` is also case-sensitive
  substring. Semantic match.
- Verdict: **CONFIRMED.**

**P13 — `MatchWhereBuilder.startsWith` does not escape LIKE
meta-characters.**
- Track claim: `Text.startingWith` → `startsWith`.
- Search: `MatchWhereBuilder.java` lines 94–111.
- Actual behavior: `startsWith` and `endsWith` concatenate the raw input
  to `%`. Javadoc explicitly says "the prefix is concatenated verbatim —
  no escaping. SQL LIKE treats `%`, `_`, and `\` as metacharacters …
  callers escape upstream when a literal match is required."
- Verdict: **CONFIRMED.** Track 4 must escape the value (or document the
  semantic divergence) — TinkerPop `TextP.startingWith("foo%")` matches
  the literal string `foo%`, while routing through `startsWith` would
  match anything beginning with `foo`. Equivalence tests would silently
  pass on inputs without meta-chars, then fail in production.

**P14 — `hasNot(key)` HasContainer shape.**
- Track claim: `HasStep` with `hasNot(key)` — translates to `field IS
  NULL` or `NOT exists(field)`.
- Search: TinkerPop sources are not directly visible (the source jar is
  not extractable in this sandbox); searched the project for
  `hasNot`/`Existence` references and found tests using `g.V().hasNot(
  "lang")` (`YTDBQueryMetricsStrategyTest.java` line 286, 294) but no
  use-site for the resulting HasContainer's predicate.
  `YTDBGraphQueryBuilder` line 79–89 handles `Compare.eq`/`Compare.neq`
  with `value == null` as `IS NULL` / `IS NOT NULL`, but does NOT have
  a dedicated hasNot path — implying TinkerPop's `hasNot(key)` produces
  a `HasContainer` whose predicate either is the existence-negation
  custom predicate OR a `Compare.neq(null)` form.
- Verdict: **PARTIAL — reference-accuracy caveat (PSI not reachable).**
  The track's translation target ("`field IS NULL` / `NOT exists(field)`")
  conflates two different SQL idioms — `field IS NULL` is true when the
  property is set to null, while `NOT exists(field)` (more accurately
  the absence of the property) is true when the property is unset. In
  YouTrackDB document storage these are distinct: a vertex can have
  `name = null` set, or it can have no `name` property at all. Gremlin
  `hasNot("name")` is documented as "absence of property", which is the
  second form. Without confirming the HasContainer shape via a runtime
  trace, the track's mapping is ambiguous. **Translation must canonicalise
  to one of the two YouTrackDB forms and equivalence tests must cover both
  scenarios** (vertex with `name=null` and vertex without `name`).

**P15 — `hasId(...)` value can be a list (multi-ID).**
- Track claim: single-ID routes through `aliasRids`, multi-ID routes
  through `aliasFilters` with `@rid IN [...]`.
- Search: `StartStepRecogniser.java` lines 138–151 (existing pattern for
  `g.V(ids)`).
- Actual behavior: `hasId(id1, id2, ...)` produces a HasContainer with
  `key = T.id.getAccessor()` and a predicate (`Contains.within` for
  multi-ID, `Compare.eq` for single-ID). The track's description
  correctly mirrors the start-step routing.
- Verdict: **CONFIRMED with caveat** — the recogniser must inspect the
  HasContainer's predicate (`Compare.eq` vs `Contains.within`) and the
  value's shape (singleton vs collection). For chain-target hasId (e.g.
  `g.V().out("knows").hasId(rid)`) the alias is the chain terminal
  (`$g2m_anon_N`), not `$g2m_v0`. The single-RID-per-alias grammar
  constraint applies to ANY alias, so the multi-ID routing through
  `aliasFilters` is the same regardless of which alias is being
  constrained.

**P16 — `Compare`, `Contains` enums match TinkerPop's predicate
algebra.**
- Track claim: `Compare.eq`/`neq`/`gt`/`gte`/`lt`/`lte` and
  `Contains.within`/`without`.
- Search: `YTDBGraphQueryBuilder.java` lines 264–276.
- Actual behavior: `formatPredicate` covers exactly
  `Compare.eq/gt/gte/lt/lte/neq` and `Contains.within/without`. Track 4's
  enum coverage matches.
- Verdict: **CONFIRMED.**

**P17 — `eq(null)` / `neq(null)` semantics are special-cased today.**
- Track claim: `Compare.eq` → `op(field, EQ, lit)`.
- Search: `YTDBGraphQueryBuilder.java` lines 79–90.
- Actual behavior: existing code handles `(Compare.eq, value=null)` as
  `IS NULL` and `(Compare.neq, value=null)` as `IS NOT NULL` —
  divergent from raw equality. Translating raw `field = NULL` would
  always be false at SQL evaluation (NULL comparison semantics).
- Verdict: **WRONG.** Track 4's plain `op(field, EQ, lit)` translation
  for `Compare.eq` would silently break the `has(key, P.eq(null))` and
  `has(key, P.neq(null))` cases. The equivalence test for native vs
  translated would diverge whenever a Gremlin user writes
  `has("name", null)` or `has("name", P.eq(null))`. The predicate adapter
  must mirror `YTDBGraphQueryBuilder`'s special-case for null operands.

### Edge case certificates

**E1 — `has(key, P.eq(null))`.**
- Trigger: Gremlin user writes `g.V().has("name", null)` (sugar for
  `has("name", P.eq(null))`).
- Code path trace: predicate adapter sees `Compare.eq` + value=null.
  Naive translation per track description: `op("name", EQ,
  literalNull)` — produces SQL `name = NULL`, which evaluates to NULL
  (never true) under SQL three-valued logic.
- Outcome: every row filtered out; native pipeline returns rows where
  `name` is null.
- Track coverage: NOT addressed — the description does not mention null
  handling. See P17.

**E2 — `hasNot("propertyName")` against a vertex without the property.**
- Trigger: `g.V().hasNot("nonExistentProp")`.
- Code path trace: depends on TinkerPop's HasContainer shape for hasNot
  (Existence-negate vs Compare.eq(null)). YTDB document storage has
  `IS NULL` (property set to null) vs property-absent semantics that
  diverge.
- Outcome: ambiguous — track description says
  "`field IS NULL` or `NOT exists(field)`" without picking one.
- Track coverage: AMBIGUOUS — see P14.

**E3 — `hasId(rid1, "non-rid-string", rid3)`.**
- Trigger: `g.V().hasId(rid1, "garbage", rid3)`.
- Code path trace: `StartStepRecogniser.normaliseIds` already handles
  this — returns `null` to decline. The hasId recogniser must mirror
  this: any unconvertible ID forces decline (under D3 the entire
  traversal declines).
- Outcome: clean decline.
- Track coverage: not explicitly mentioned. Recommend documenting the
  decline-on-any-bad-id contract in the recogniser.

**E4 — `hasLabel("Person", "Place")` (multi-class).**
- Trigger: hasLabel takes varargs of class names.
- Code path trace: `YTDBHasLabelStep.predicates` is a list (one per
  class). Mapping a multi-label hasLabel to MATCH requires `@class IN
  ['Person', 'Place']` — but `aliasClasses` is `Map<String, String>`
  (single class per alias). Single-class is straightforward; multi-class
  needs the same `@rid IN [...]`-shaped construction as the multi-ID
  hasId path, but with `@class` left side and class-name string
  literals.
- Outcome: needs explicit handling.
- Track coverage: NOT addressed. Description says "narrows the current
  alias to a specific class" (singular). Either declare multi-class
  hasLabel out of scope (decline path) or add the multi-class IN
  construction.

**E5 — `hasLabel("Person").hasLabel("Person")` (idempotent).**
- Trigger: chained hasLabel.
- Code path trace: writing `aliasClasses[alias] = "Person"` twice is
  fine (overwrite-with-same). `MatchExecutionPlanner.addAliases` (line
  4716–4734) takes the lower subclass when classes diverge — so
  `hasLabel("Person").hasLabel("Place")` (no hierarchy) would throw
  `CommandExecutionException` ("classes defined for alias … are not in
  the same hierarchy"). Track 4 must either pre-detect this and
  decline, or rely on the planner's exception (caught by
  `GremlinToMatchStrategy.apply`'s outer try/catch — fallback to
  native).
- Outcome: planner exception caught and traversal declines after
  translation attempt; not the cleanest path but functionally correct.
- Track coverage: not addressed. Suggest pre-detecting the hierarchy
  conflict at recogniser time (read schema via
  `ctx.traversal.getGraph()`) for cleaner error reporting.

**E6 — `has(key, P.between(lo, hi))` with `P.inside(lo, hi)`.**
- Trigger: `g.V().has("age", P.inside(18, 65))`.
- Code path trace: `P.inside` is an exclusive range — `field > 18 AND
  field < 65`. Track 4 says "P.inside(lo, hi) → and(op(field, GT, lo),
  op(field, LT, hi))" — correct.
- Outcome: works.
- Track coverage: addressed.

**E7 — `has("name", P.within(list).and(P.startingWith("A")))`** —
composite ConnectiveP.
- Trigger: TinkerPop allows `P.X.and(P.Y)` / `P.X.or(P.Y)` /
  `P.X.negate()` to combine predicates on the same property.
- Code path trace: TinkerPop wraps these as `ConnectiveP` /
  `AndP`/`OrP` instances at runtime. The track says "P.and(...) /
  P.or(...) / P.not(...) → recursive composition via
  MatchWhereBuilder.and/or/not" — correct in spirit, but the recogniser
  needs to detect the ConnectiveP class and recurse, not just dispatch
  on `getBiPredicate()`.
- Outcome: requires explicit ConnectiveP handling code.
- Track coverage: described at high level but no implementation hint.
  The dispatch mechanism (instanceof ConnectiveP vs check
  `predicate.getBiPredicate()`) is not surfaced.

**E8 — Combination: `has(a, gt(1)).has(b, lt(10))` on the same alias.**
- Trigger: two separate HasStep instances after the chain target.
- Code path trace: each HasStep recogniser ANDs its containers into
  `aliasFilters[alias]`. Concern: when the recogniser writes to
  `ctx.aliasFilters[alias]`, does it AND with an existing filter or
  overwrite? Looking at `StartStepRecogniser.combineAnd` (lines 285–
  299), it AND-combines two values. The hasStep recogniser must merge
  with any already-present filter (from VertexStep non-polymorphic
  narrowing or a prior HasStep on the same alias) — overwrite would
  silently drop earlier filters.
- Outcome: requires explicit merge-AND helper, not straight `put`.
- Track coverage: track says "ANDs all containers" — addresses
  intra-step combination but not inter-HasStep combination on the same
  alias. The merge-with-existing case must be handled.

### Integration certificates

**I1 — Strategy-level gate at `GremlinToMatchStrategy.java:164` blocks
all start-step has-translation.**
- Plan claim: Track 4 implements predicate handling on the start step.
- Actual entry point: `GremlinToMatchStrategy.apply` line 164:
  `if (!graphStep.getHasContainers().isEmpty()) { return; }`.
- Caller analysis: the gate fires whenever `g.V().has(...)` reaches the
  strategy — i.e. always, after `YTDBGraphStepStrategy` absorbs the
  `HasContainer`s into the `YTDBGraphStep`. The gate must be removed (or
  relaxed) for Track 4 to function on the dominant case.
- Breaking change risk: Track 2 added the gate intentionally to avoid
  half-translating a start-step with absorbed predicates that the
  Track-2 minimal translator could not yet handle. Removing it without
  also teaching `StartStepRecogniser` to translate the absorbed
  containers would crash translation on every `g.V().has(...)` shape
  (the recogniser today still has its own `getHasContainers().isEmpty()`
  decline at line 108 of `StartStepRecogniser`). Both must be removed
  together.
- Verdict: **MISMATCHES.** The track description does not mention
  modifying `GremlinToMatchStrategy` or `StartStepRecogniser`, yet both
  must change for Track 4 to translate `g.V().has(...)`. **Blocker.**

**I2 — `YTDBGraphMatchStepStrategy` runs after our strategy and folds
hasLabel from `match()` sub-traversals.**
- Plan claim: ordering pinned via `applyPrior`/`applyPost`.
- Actual entry point: `YTDBGraphMatchStepStrategy.apply` lines 73–138,
  ordered after `YTDBGraphStepStrategy` and before our strategy by
  `GremlinToMatchStrategy`'s `applyPost` declaration.
- Caller analysis: this strategy folds hasLabel/has from `match()` step
  sub-traversals into the start step. It does NOT affect `g.V().has(...)`
  patterns directly — the absorption happens earlier in
  `YTDBGraphStepStrategy`. So Track 4 sees the post-fold shape for both
  ordinary and `match()`-bearing traversals. Our strategy declines any
  traversal containing `match()` because `MatchStep` has no recogniser —
  so the YTDBGraphMatchStepStrategy fold's effect is inconsequential to
  Track 4 (those traversals decline before reaching us).
- Verdict: **MATCHES** for the in-scope case (no mid-traversal `match()`).

**I3 — Walker registry insertion point: first-match-wins ordering.**
- Plan claim: "registered in `GremlinStepWalker.PRODUCTION_RECOGNISERS`
  (after the prior track's start/vertex/no-op-barrier entries; declaration
  order is first-match wins, so insertion point matters)."
- Actual entry point: `GremlinStepWalker.java` lines 67–71. Order today:
  StartStep, VertexStep, NoOpBarrier.
- Caller analysis: adding `HasStepRecogniser`, `HasLabelStepRecogniser`
  (i.e. for `YTDBHasLabelStep`), and `HasIdStepRecogniser` after the
  existing entries is safe: each is `instanceof`-discriminated against a
  distinct step class (`HasStep`, `YTDBHasLabelStep`). No conflict with
  prior entries.
- Verdict: **MATCHES.**

**I4 — `YTDBHasLabelStep` is the right class for mid-chain hasLabel,
NOT TinkerPop's `HasStep` with T.label.**
- Plan claim: "`YTDBHasLabelStep` — narrows the current alias to a
  specific class."
- Actual entry point: `YTDBGraphStepStrategy.java` lines 130–148
  ("`hasLabel` steps that don't directly follow a GraphStep are replaced
  by YTDBHasLabelStep").
- Caller analysis: every mid-chain `hasLabel(...)` becomes
  `YTDBHasLabelStep` after `YTDBGraphStepStrategy` runs. Track 4's
  recogniser correctly targets `YTDBHasLabelStep`. The track also
  mentions a separate "HasStep with T.label" recogniser path; that path
  is unreachable (see P10).
- Verdict: **MATCHES** for `YTDBHasLabelStep` recognition.
  **MISMATCHES** for the dead-code "HasStep with T.label" path —
  recommend deleting that bullet from the description.

**I5 — Cucumber suite engagement.**
- Plan claim: "Cucumber suite remain green with 4 new recognisers."
- Actual entry point: `YTDBGraphFeatureTest.java`.
- Caller analysis: The Cucumber suite produces ~1900 scenarios. With
  D3 all-or-nothing, any traversal touching any unrecognised step
  declines; Track 4 broadens the recognised set so more traversals will
  translate. The risk is correctness drift — a recogniser that
  translates a shape with subtly wrong semantics (e.g. the `eq(null)`
  case in P17, `startsWith` LIKE meta-chars in P13, hasNot ambiguity in
  P14) would silently fail Cucumber scenarios.
- Verdict: **CALLERS AT RISK.** The verification section names
  parameterised equivalence tests but does not enumerate the corner
  cases (eq(null), startsWith with `%`, multi-class hasLabel,
  hierarchy conflicts). Recommend the equivalence test list be
  expanded explicitly in step decomposition.

**I6 — `MatchExecutionPlanner.addAliases` throws on hierarchy conflict.**
- Plan claim: hasLabel narrowing.
- Actual entry point: `MatchExecutionPlanner.java` lines 4716–4734.
- Caller analysis: when two distinct classes are written to the same
  alias's `aliasClasses`, the planner takes `getLowerSubclass(...)`
  and throws `CommandExecutionException` if neither is a subclass of
  the other. With Track 4's hasLabel-overwrite via `addNode(alias,
  className, null, false)`, a sequence of incompatible hasLabels (e.g.
  `hasLabel("Person").hasLabel("Place")`) WILL crash the planner.
  `GremlinToMatchStrategy.apply`'s outer try/catch swallows the
  `RuntimeException` (line 184–189) and the traversal falls back to
  native — functionally correct, but logs a WARN.
- Verdict: **MATCHES** with caveat — recommend pre-detecting hierarchy
  conflict at recogniser time to avoid the WARN-log path.

## Part 2: Findings

### Finding T1 [blocker]
**Certificate**: P8, I1
**Location**: Track 4 description, all four `HasStep` bullets;
`core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinToMatchStrategy.java:164`;
`core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/StartStepRecogniser.java:108`.
**Issue**: The dominant filter case `g.V().has("name", "Alice")` cannot
translate via Track 4's described "HasStep recogniser" because
`YTDBGraphStepStrategy` runs first and absorbs all start-step
`HasContainer`s into the `YTDBGraphStep`. By the time
`GremlinToMatchStrategy` fires, there is no `HasStep` for the recogniser
to claim. Worse, two existing decline gates explicitly reject the
absorbed-container shape:
- `GremlinToMatchStrategy.apply` line 164:
  `if (!graphStep.getHasContainers().isEmpty()) return;`
- `StartStepRecogniser.recognize` line 108:
  `if (!graphStep.getHasContainers().isEmpty()) return false;`
Both must be removed for Track 4 to translate `g.V().has(...)`. The
existing equivalence test `V_has_name_Alice` already documents this:
`Expected.DECLINED` with comment "Track 4 territory: declines for now
because hasContainers fold into the start step and the strategy's
hasContainers gate fires."
**Proposed fix**: Add explicit step language to Track 4:
1. Remove the strategy gate at `GremlinToMatchStrategy.java:164`.
2. Extend `StartStepRecogniser` to translate absorbed `HasContainer`s
   from `YTDBGraphStep.getHasContainers()` into the alias's
   `aliasFilters` (using the new predicate adapter), with single-class
   T.label containers narrowing `aliasClasses[$g2m_v0]` and
   `T.id`-keyed containers routing through the same
   single-vs-multi-RID logic that `g.V(ids)` already uses.
3. Remove the `getHasContainers().isEmpty()` decline at
   `StartStepRecogniser.java:108` once (2) is in place.
4. Flip the `V_has_name_Alice` case in `EdgeTraversalEquivalenceTest`
   from `DECLINED` to `RECOGNIZED` and add the assertion that the
   translated traversal returns Alice (already covered by the
   equivalence comparison).
This work cannot be skipped — without it, the entire `g.V().has(...)`
shape (including all property predicates, hasLabel on the start step,
hasId on the start step) declines, defeating Track 4's purpose.

### Finding T2 [blocker]
**Certificate**: P17, E1
**Location**: Track 4 description, "Compare.eq → op(field, EQ, lit);
neq → NE" bullet.
**Issue**: The straight `op(field, EQ, lit)` translation does not
match TinkerPop semantics for null operands. Gremlin's `has(key,
P.eq(null))` and `has(key, null)` (sugar for the same) match traversers
whose property is null/missing. SQL `field = NULL` evaluates to NULL
(never true) under three-valued logic, so the translated path returns
zero rows where the native pipeline returns the rows where `key` is
absent or null. The existing `YTDBGraphQueryBuilder` already
special-cases this (lines 79–90: `(eq, null)` → `IS NULL`, `(neq,
null)` → `IS NOT NULL`); Track 4's adapter must mirror it.
**Proposed fix**: In the predicate adapter:
- For `Compare.eq` with value `null` → emit `field IS NULL`.
- For `Compare.neq` with value `null` → emit `field IS NOT NULL`.
- For all other operators with null value (e.g. `gt(null)`) → decline
  the predicate (force a clean traversal-decline rather than producing
  nonsense SQL). This matches the existing query builder's "NOT
  CONVERTED" path.
Add equivalence-test cases: `g.V().has("optional_prop",
P.eq(null))`, `g.V().has("optional_prop", null)` (sugar), and
`g.V().has("optional_prop", P.neq(null))`, against a graph with both
null-valued and absent properties.

### Finding T3 [blocker]
**Certificate**: P14, E2
**Location**: Track 4 description, "HasStep with hasNot(key)" bullet.
**Issue**: Description "translates to `field IS NULL` / `NOT exists(field)`"
gives two non-equivalent SQL idioms. In YouTrackDB document storage,
`field IS NULL` matches a record that has `field` set to null; the
property's absence is a different state. Gremlin `hasNot(key)` is the
"absence" form per TinkerPop docs. Without picking one canonical form,
the translation is ambiguous and equivalence tests will silently pass on
graphs where the distinction does not arise (every vertex either has the
property or does not, but never both states present).
**Proposed fix**:
1. Decide on one canonical form. The closest semantic match in YTDB SQL
   for "property absent" is typically `NOT (field IS DEFINED)` or
   `field IS NULL` (YTDB's NULL semantics conflate the two for most
   index purposes — but verify by reading
   `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLBinaryCondition.java`
   and the IS NULL operator's evaluation).
2. Add a dedicated `MatchWhereBuilder.isNull(field)` /
   `MatchWhereBuilder.isNotNull(field)` helper rather than synthesising
   it via `not(op(field, EQ, ...))` — IS NULL is its own AST node in
   the parser grammar (it's a unary operator suffix), and the
   plan-pretty-print shape diverges from a `NOT (field = NULL)` block.
3. Add equivalence tests on a graph that contains both
   property-absent and property-set-to-null vertices, and assert that
   `hasNot(key)` and `has(key, P.eq(null))` and `has(key, P.neq(null))`
   each produce the expected multiset.

### Finding T4 [blocker]
**Certificate**: P13, E1
**Location**: Track 4 description, "Text.startingWith / endingWith →
startsWith / endsWith" bullet.
**Issue**: `MatchWhereBuilder.startsWith` / `endsWith` do NOT escape
LIKE meta-characters (`%`, `_`, `\`) in the input, while TinkerPop
`TextP.startingWith("foo%")` matches the literal string `foo%`. A user
who writes `g.V().has("name", TextP.startingWith("100%"))` would have
the LIKE evaluator interpret the trailing `%` as a wildcard, producing
divergent results from the native pipeline. Equivalence tests on inputs
without meta-chars would silently pass.
**Proposed fix**: Either (a) escape the input upstream in the predicate
adapter (canonical: replace `\` → `\\`, `%` → `\%`, `_` → `\_`, then
append/prepend `%`) before passing to `MatchWhereBuilder.startsWith` /
`endsWith`, or (b) extend `MatchWhereBuilder.startsWith` / `endsWith`
to take an "escape" flag (clean), or (c) add new
`MatchWhereBuilder.startsWithLiteral` / `endsWithLiteral` helpers that
do the escaping. Add equivalence tests with inputs containing each of
`%`, `_`, `\`.

### Finding T5 [should-fix]
**Certificate**: P11
**Location**: Track 4 description, `Text.containing` /
`Text.startingWith` / `Text.endingWith` references.
**Issue**: TinkerPop's text predicates live on `TextP` (not `Text`).
The class is `org.apache.tinkerpop.gremlin.process.traversal.TextP`.
Existing project test code uses the correct `TextP` import.
**Proposed fix**: Update description prose to use `TextP.containing`,
`TextP.startingWith`, `TextP.endingWith` so step decomposition does not
later have to disambiguate.

### Finding T6 [should-fix]
**Certificate**: P7
**Location**: Track 4 description, "YTDBHasLabelStep" bullet ("the
planner handles subclass expansion via `class IN [...]`").
**Issue**: The planner does not have any `class IN [...]` rewrite
mechanism. The desired polymorphic-class-match behavior is achieved
purely by setting `aliasClasses[alias] = "ClassName"`, because YTDB
SQL `SELECT FROM ClassName` is polymorphic by default (returns
instances of ClassName and all subclasses). The track's prose suggests
a non-existent code path and will mislead step implementers into
searching for it.
**Proposed fix**: Replace "the planner handles subclass expansion via
`class IN [...]`" with: "the planner emits `SELECT FROM <class>` which
is polymorphic by default in YTDB SQL — instances of the named class
and all subclasses match". Confirm in the implementation that no
extra IN-construction is required.

### Finding T7 [should-fix]
**Certificate**: P10, I4
**Location**: Track 4 description, "HasStep with a `T.label` container
that's not yet folded" bullet.
**Issue**: This bullet describes a code path that cannot occur after
`YTDBGraphStepStrategy` runs. That strategy unconditionally extracts
T.label HasContainers from non-start-step HasSteps and packages them
into a `YTDBHasLabelStep` (lines 130–148). So mid-chain HasStep with
T.label is observable only if `YTDBGraphStepStrategy` is bypassed —
which is not a production scenario. The "rare" framing is not just
imprecise; it's "never" in production. Including this bullet adds dead
code to the recogniser registry and dead test cases.
**Proposed fix**: Delete the bullet. If the implementer wants
defence-in-depth, they can add an `assert` or decline path inside the
HasStep recogniser: any HasContainer with key `T.label.getAccessor()`
declines the recogniser (treat as walker-level signal to fall back to
native). Update the equivalence test set accordingly.

### Finding T8 [should-fix]
**Certificate**: E4, P6
**Location**: Track 4 description, "YTDBHasLabelStep — narrows the
current alias to a specific class" (singular).
**Issue**: `YTDBHasLabelStep` carries a list of predicates (one per
class in `hasLabel("A", "B", "C")`), not a single class. The
description and the code path implied (write to `aliasClasses[alias]`)
only handle the singleton case. Multi-class hasLabel is a real Gremlin
shape that surfaces in LDBC and other production traversals.
**Proposed fix**: Pick one of:
- (a) Decline at the recogniser when the predicate list has > 1 entry
  (carve out, with explicit equivalence-test DECLINED case).
- (b) Translate multi-class hasLabel as `aliasFilters[alias] WHERE
  @class IN ['A','B','C']` (under non-polymorphic mode) or as a
  `class IN [...]` filter alongside `aliasClasses[alias] = <commonAncestor>`
  (under polymorphic mode — find the lowest common ancestor in the
  schema or fall back to "V"). Reuse the cached
  `SQL_IN_OPERATOR_FIELD` helper (same as the multi-ID hasId case).
Add equivalence tests for `g.V().hasLabel("Person", "Place")` and
`g.V().out("knows").hasLabel("Person", "Place")` under both
polymorphism modes.

### Finding T9 [should-fix]
**Certificate**: E8
**Location**: Track 4 description, "HasStep — for each `HasContainer`
invokes the predicate adapter, ANDs all containers, attaches the result
as the `where`".
**Issue**: When two HasStep recognisers run for the same alias (e.g.
`g.V().out("knows").has("a", gt(1)).has("b", lt(10))`), the second
recogniser must AND its filter with the existing one. A naive
`ctx.aliasFilters.put(alias, newClause)` overwrites the first
HasStep's contribution. Similarly, when a hasLabel recogniser writes a
`@class = 'Person'` filter (non-polymorphic) and a subsequent has-step
adds a property predicate, both must be AND-merged. The existing
`StartStepRecogniser.combineAnd` provides the pattern — track 4 should
extract a shared helper.
**Proposed fix**: Add a `WalkerContext.mergeAliasFilter(alias,
clause)` helper (or reuse `StartStepRecogniser.combineAnd` lifted into
`MatchClassFilters` or a new `MatchAliasFilterMerger`) that ANDs into
any existing entry. Document the contract on `WalkerContext.aliasFilters`
that direct `put` is only safe when the recogniser knows no prior entry
exists; in mixed scenarios, use the merge helper. Add equivalence test
`V_has_name_Alice_has_age_gt_30` and `V_out_Knows_has_name_Bob_has_age_gt_25`.

### Finding T10 [should-fix]
**Certificate**: E7
**Location**: Track 4 description, "P.and(...) / P.or(...) / P.not(...)
→ recursive composition via MatchWhereBuilder.and/or/not".
**Issue**: TinkerPop's `P.and`/`P.or`/`P.not` produce `ConnectiveP`
(or `AndP`/`OrP`) instances, not plain `P` with composite
`BiPredicate`. The dispatch shape in the predicate adapter must be
`if (predicate instanceof ConnectiveP)` to recurse, NOT just the
`getBiPredicate()` switch the existing `YTDBGraphQueryBuilder` uses
(which only handles atomic `Compare` / `Contains` predicates and
declines `ConnectiveP`).
**Proposed fix**: Document the dispatch shape explicitly. Add unit and
equivalence tests for `P.gt(5).or(P.lt(2))`, `P.gt(0).and(P.lt(100))`,
`P.between(0, 100).negate()`. Verify the recursion terminates correctly
on `P.X.and(P.Y).or(P.Z)` (left-associative).

### Finding T11 [should-fix]
**Certificate**: I6
**Location**: Track 4 description, hasLabel handling.
**Issue**: `MatchExecutionPlanner.addAliases` throws
`CommandExecutionException` when two unrelated classes are pinned to
the same alias (e.g. `hasLabel("Person").hasLabel("Place")` where
neither is a subclass of the other). The strategy's outer try/catch
catches this and falls back to native, but it produces a misleading
WARN log and a wasted plan-construction cycle.
**Proposed fix**: At the hasLabel recogniser, when overwriting
`aliasClasses[alias]`, check the existing entry's hierarchy
relationship via `ctx.traversal.getGraph()`'s schema. If the new class
is incompatible (neither is a sub/super of the other), decline the
recogniser cleanly so the traversal declines via the standard
all-or-nothing path rather than via an exception. Equivalence test:
`g.V().hasLabel("Person").hasLabel("Place")` decline.

### Finding T12 [should-fix]
**Certificate**: P15, E3
**Location**: Track 4 description, "HasStep with `hasId(...)`" bullet.
**Issue**: The bullet says "Single-ID routes through `aliasRids`" and
"Multi-ID routes through `aliasFilters`", but does not specify behavior
on **mixed valid/invalid IDs** (e.g. `hasId(rid1, "garbage")`). The
existing `StartStepRecogniser.normaliseIds` returns null on any
unconvertible ID and the recogniser declines cleanly. Track 4's hasId
recogniser must mirror this contract.
**Proposed fix**: Document explicitly that any unconvertible ID in
the hasId argument list causes a clean recogniser decline (force the
whole traversal to decline under D3). Add equivalence test
`V_hasId_rid_and_garbage` with `Expected.DECLINED`.

### Finding T13 [suggestion]
**Certificate**: P3
**Location**: Track 4 description, "lift the cached field into a shared
helper in `match.builder/`".
**Issue**: The lift target should expose the cached field through a
small shared helper class (e.g.
`MatchInOperatorReflection.setOperator(SQLInCondition condition)`) so
the three call sites (MatchWhereBuilder, StartStepRecogniser, new
hasId recogniser) all delegate. The track description says "lift the
cached field" — this could be misread as "expose the field directly"
(public/package-private static field). Direct field exposure leaks
implementation; a static helper method preserves encapsulation.
**Proposed fix**: Add a sentence to the track description:
"the helper exposes a single `setOperator(SQLInCondition)` method —
not the field directly — so the reflection cost stays paid-once per
class load and the three call sites remain shallow delegators".

### Finding T14 [suggestion]
**Certificate**: P5, P6
**Location**: Track 4 description, "the recogniser must (a) choose
between writing `aliasClasses` vs `aliasFilters` per polymorphism, and
(b) ensure any prior chain-target `aliasFilters` entry is replaced
rather than shadowed".
**Issue**: The description correctly identifies the merge-direction
issue but does not specify the exact write algorithm under each mode.
The implementer might pick either of two equivalent strategies and
diverge from the prior recognisers.
**Proposed fix**: Spell out the algorithm:
- Polymorphic mode: write `patternBuilder.addNode(alias, className,
  null, false)` (overwrites `aliasClasses[alias]`); leave
  `ctx.aliasFilters[alias]` alone (it will be empty for a chain-target
  in polymorphic mode by `VertexStepRecogniser`'s contract).
- Non-polymorphic mode: ALSO write `patternBuilder.addNode(alias,
  className, null, false)` AND replace `ctx.aliasFilters[alias]` with
  `wrapWhere(classEq(className))` (overwrites the prior `WHERE
  @class = 'V'` from `VertexStepRecogniser`). When a property predicate
  has already been written to `ctx.aliasFilters[alias]` (e.g. by a
  prior HasStep), AND-merge instead of overwrite (see T9).
Add a unit test that runs `g.V().out("knows").hasLabel("Person")`
under both polymorphism modes and asserts `aliasClasses[$g2m_anon_0] =
"Person"` plus `aliasFilters[$g2m_anon_0]` either absent (polymorphic)
or `WHERE @class = 'Person'` (non-polymorphic).

### Finding T15 [suggestion]
**Certificate**: I5
**Location**: Track 4 verification methodology paragraph.
**Issue**: The verification description says "extend the prior track's
`EdgeTraversalEquivalenceTest` harness" and lists shape categories
("each predicate, each `has*` shape, combination cases") but does not
enumerate the corner cases this review surfaces (eq(null), startsWith
with `%`, hasNot ambiguity, multi-class hasLabel, hierarchy conflict,
hasId mixed-valid). Step decomposition risks producing a thin test
list that misses real semantic divergences.
**Proposed fix**: Append a list to the verification methodology naming
the corner cases that must have at least one equivalence-test entry:
- `has(key, P.eq(null))`, `has(key, null)`, `has(key, P.neq(null))`
- `has(key, TextP.startingWith("foo%"))` (LIKE meta-char escape)
- `has(key, TextP.containing("a"))` and `TextP.containing("")`
- `hasNot(key)` against absent vs null-valued property
- `hasLabel("A", "B")` (multi-class)
- `hasLabel("Person").hasLabel("Place")` (incompatible hierarchy)
- `hasId(rid, "garbage")` (mixed-valid IDs)
- `has(a, gt(1)).has(b, lt(10))` (multi-step same-alias AND)
- `hasLabel("Person").has("name", "Alice")` (class + property AND)
- `has("k", P.gt(5).or(P.lt(2)))` (ConnectiveP)
- `has("k", P.between(0, 100).negate())` (ConnectiveP with negation)

### Finding T16 [suggestion]
**Certificate**: P4
**Location**: Track 4 description, "read `WalkerContext.polymorphic`
for the flag (no fresh `YTDBStrategyUtil.isPolymorphic` call — the
start-step recogniser already pinned it on the context)".
**Issue**: This guidance is correct but should be reinforced with a
defence: if a future track adds a recogniser that runs before
`StartStepRecogniser`, `ctx.polymorphic` could be stale (default
true). Track 4's hasLabel recogniser cannot run before
`StartStepRecogniser` because `boundaryAlias` would be null (no chain
to narrow), but a defensive null-check on `ctx.boundaryAlias` already
handles this — and if Track 4's recogniser also gates on
`boundaryAlias != null`, the read of `ctx.polymorphic` is safe by
construction.
**Proposed fix**: Add to the description: "the hasLabel recogniser
gates on `ctx.boundaryAlias != null` first (mirrors
VertexStepRecogniser line 115), which makes reading `ctx.polymorphic`
safe by construction — a recogniser running before the start-step
recogniser would have a null boundary alias and decline before
reading polymorphic".

---

## Summary

**Total findings by severity**: 4 blockers, 8 should-fix, 4 suggestions.

**Top 3 most important findings (one-liner each)**:
1. T1 (blocker): Track 4 cannot translate `g.V().has(...)` without removing two
   decline gates and extending `StartStepRecogniser` — the dominant case is
   silently uncovered by the current track description.
2. T2 (blocker): `Compare.eq` with null operand must map to `IS NULL`/`IS NOT
   NULL`, not raw `field = NULL` (which always evaluates to false).
3. T3 (blocker): `hasNot(key)` translation is ambiguous between `field IS NULL`
   and "property absent"; pick one canonical form and test against both states.

**Blocker-level concerns affecting step decomposition**:
- T1 adds two new sub-tasks (remove strategy gate; extend StartStepRecogniser
  to translate absorbed HasContainers) — each substantial, each with its own
  test surface; cannot be folded into a single "HasStepRecogniser" step.
- T2 / T3 / T4 each require dedicated equivalence-test suites against
  carefully seeded graphs (null-valued vs absent properties; LIKE meta-chars).
  These are not a free addition to the existing `EdgeTraversalEquivalenceTest`
  graph fixture — additional vertices with null-valued and absent properties,
  plus inputs with `%`/`_`/`\`, must be seeded.
