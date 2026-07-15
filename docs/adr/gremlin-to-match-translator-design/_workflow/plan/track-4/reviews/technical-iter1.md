<!-- MANIFEST
findings: 7   severity: {blocker: 2, should-fix: 4, suggestion: 1}
index:
  - {id: T1, sev: blocker,    loc: "track-4.md:41", anchor: "### T1 ", cert: C8,  basis: "hasId compiles to HasStep(~id) via addHasContainer; no HasIdStep class exists, so a HasIdStepRecogniser cannot compile or be dispatched"}
  - {id: T2, sev: blocker,    loc: "track-4.md:35", anchor: "### T2 ", cert: C9,  basis: "translator runs before YTDBGraphStepStrategy and GraphStep is not a HasContainerHolder, so hasLabel is a plain HasStep(~label) at translation time; folded-start and YTDBHasLabelStep paths never fire"}
  - {id: T3, sev: should-fix, loc: "WalkerContext.java:236", anchor: "### T3 ", cert: C11, basis: "putAliasFilter overwrites; two recognisers contributing to one alias (rid+has, class+has, has+where) silently drop the earlier filter -> over-match"}
  - {id: T4, sev: should-fix, loc: "track-4.md:46", anchor: "### T4 ", cert: C13, basis: "D5 bindParam is placed on WalkerContext but recognisers/adapter see only RecognitionContext; adapter renders inline literals, not SQLPositionalParameter"}
  - {id: T5, sev: should-fix, loc: "track-4.md:44", anchor: "### T5 ", cert: C12, basis: "edge-bearing NOT needs a WalkerContext list + RecognitionContext sink + buildResult wiring (unlisted); manageNotPatterns also throws when the NOT origin alias carries a filter"}
  - {id: T6, sev: should-fix, loc: "track-4.md:34", anchor: "### T6 ", cert: C5,  basis: "classEquals emits exact @class= (non-polymorphic); hasLabel in polymorphic mode must match subclasses, so unconditional classEquals under-matches"}
  - {id: T7, sev: suggestion, loc: "track-4.md:75", anchor: "### T7 ", cert: C6,  basis: "aliasRids[a]/aliasClasses[a] map references and startsWith stub phrasing do not match HEAD (single id uses @rid IN + planner promotion; startsWith is fully built; endsWith/matchesRegex absent)"}
evidence_base: {section: "## Evidence base", certs: 15, matches: 9}
cert_index:
  - {id: C1,  verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2,  verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3,  verdict: CONFIRMED, anchor: "#### C3 "}
  - {id: C4,  verdict: CONFIRMED, anchor: "#### C4 "}
  - {id: C5,  verdict: PARTIAL,   anchor: "#### C5 "}
  - {id: C6,  verdict: PARTIAL,   anchor: "#### C6 "}
  - {id: C7,  verdict: CONFIRMED, anchor: "#### C7 "}
  - {id: C8,  verdict: WRONG,     anchor: "#### C8 "}
  - {id: C9,  verdict: WRONG,     anchor: "#### C9 "}
  - {id: C10, verdict: CONFIRMED, anchor: "#### C10 "}
  - {id: C11, verdict: WRONG,     anchor: "#### C11 "}
  - {id: C12, verdict: PARTIAL,   anchor: "#### C12 "}
  - {id: C13, verdict: CONFIRMED, anchor: "#### C13 "}
  - {id: C14, verdict: CONFIRMED, anchor: "#### C14 "}
  - {id: C15, verdict: CONFIRMED, anchor: "#### C15 "}
flags: [CONTRACT_OK]
-->

# Track 4 technical review — iteration 1

Reviewer role reviewer-technical, phase 3A. Two blockers: the `Has*`-family
recogniser split does not survive contact with the real step shapes. `hasId`
and `hasLabel` are not distinct step classes at translation time — both are a
plain TinkerPop `HasStep` distinguished only by their `HasContainer` key
(`~id` / `~label`), and under D9's exact-class dispatch that means one
`HasStepRecogniser` must unpack all three of `has` / `hasLabel` / `hasId`. The
track's separate `HasIdStepRecogniser` (keyed on a class that does not exist)
and `HasLabelStepRecogniser` (keyed on `YTDBHasLabelStep`, which the translator
never sees) cannot fire. Four should-fixes cover missing interface surface for
filter composition, D5 parameter binding, and the edge-bearing NOT path.

**Reference-accuracy caveat.** mcp-steroid PSI timed out repeatedly (IDE
mid-index), so every symbol claim below rests on `find`/`grep` over source plus
`javap` decompilation of the exact TinkerPop fork jar the build resolves
(`io.youtrackdb:gremlin-core:3.8.1-af9db90-SNAPSHOT`, matching `pom.xml`
`gremlin.version`). For the "what does `hasId`/`hasLabel`/`has(key)` compile to"
questions the jar bytecode is authoritative — more so than PSI, which indexes
source not the dependency jar. Class-existence negatives (`HasIdStep` absent)
were taken from `unzip -l` on that jar, so they are exhaustive for the fork.

## Findings

### T1 [blocker]
**Certificate**: C8 (Premise: `hasId` step class)
**Location**: `track-4.md` `## Plan of Work` item 2 (line 41) and `## Interfaces and Dependencies` (line 74) — the `HasIdStepRecogniser` class
**Issue**: The plan introduces `HasIdStepRecogniser` as a standalone recogniser
(implicitly keyed on a `HasIdStep` step class, matching the sibling
`HasStepRecogniser` / `HasLabelStepRecogniser` pattern). No `HasIdStep` class
exists. `GraphTraversal.hasId(...)` (both the `P` and `Object,Object...`
overloads) builds a `HasContainer(T.id.getAccessor(), P)` and calls
`TraversalHelper.addHasContainer(...)`, which appends the container to a
trailing `HasStep` (or creates a `HasStep`). So `hasId` produces a `HasStep`
whose container key is `~id` — the same runtime class as `has(k,v)`. Under D9
the registry keys on the exact runtime class and permits exactly one recogniser
per class, so `HasStepRecogniser` and `HasIdStepRecogniser` cannot both bind
`HasStep.class`; and a `HasIdStepRecogniser` referencing `HasIdStep.class` will
not compile (no such symbol). Compounding it: the current
`GremlinPredicateAdapter` declines any `~`-prefixed key via
`WalkerContext.isReservedHasKey`, so an unmodified pipeline declines `hasId`
outright.
**Proposed fix**: Drop `HasIdStepRecogniser`. Fold id handling into the single
`HasStepRecogniser`: iterate `HasStep.getHasContainers()`, and for a container
whose key equals `T.id.getAccessor()` (`~id`) extract the `P` value(s), convert
to RIDs, and emit the `@rid IN [...]` filter (reuse
`StartStepRecogniser.buildRidInExpression` / `MatchWhereBuilder.literalCollectionExpression`
— the single-id case relies on the planner's `promoteStaticRidsFromFilters`
collapse, there is no separate `aliasRids` slot). This detection must run
before the reserved-key decline in the adapter path.

### T2 [blocker]
**Certificate**: C9 (Premise: `hasLabel` shape at translation time), C15 (`YTDBHasLabelStep` producer)
**Location**: `track-4.md` `## Context and Orientation` (lines 34–35) and `## Plan of Work` item 2 (line 41) — the folded-`hasLabel` start-recogniser path and `HasLabelStepRecogniser`
**Issue**: Both `hasLabel` paths rest on a false ordering premise. The track
says "`hasLabel(label)` is usually folded by `YTDBGraphStepStrategy` into the
start step's `hasContainers`, so the start recogniser pins `aliasClasses[a]`"
and adds a `HasLabelStepRecogniser` for "the YTDB `YTDBHasLabelStep` subclass".
Neither can fire at translation time:
- `YTDBGraphStepStrategy.applyPrior()` returns `Set.of(GremlinToMatchStrategy.class)`
  (line 182), so the translator runs **before** it. `YTDBGraphStepStrategy` is
  the sole producer of both the GraphStep fold and `YTDBHasLabelStep` (only
  `new YTDBHasLabelStep` call, line 149). So at translation time no fold has
  happened and no `YTDBHasLabelStep` exists — a recogniser keyed on
  `YTDBHasLabelStep.class` is dead code.
- `GraphStep` is not a `HasContainerHolder` (it implements `Configuring` +
  `GraphStepContract`, neither of which extends `HasContainerHolder`), so
  `addHasContainer` never folds `hasLabel` onto the start step either.
  `g.V().hasLabel("P")` is `GraphStep` + a separate `HasStep[~label eq P]`.
- `StartStepRecogniser` explicitly **declines** a start step carrying folded
  containers (lines 106–111: "a HasContainerHolder start step with non-empty
  containers must still decline"), so the "start recogniser pins the class"
  path is contradicted by the recogniser as shipped.

Net effect if built as written: `hasLabel` arrives as `HasStep[~label]`,
`HasStepRecogniser` routes it through the adapter, the adapter declines the
`~label` reserved key, and the whole traversal declines — the track's "hasLabel
translates" acceptance line fails.
**Proposed fix**: Handle `hasLabel` inside the single `HasStepRecogniser`: for
a container whose key equals `T.label.getAccessor()` (`~label`), translate via
`MatchWhereBuilder.classEquals` (gated per T6), before the reserved-key
decline. Delete `HasLabelStepRecogniser` and the folded-start-recogniser
narrative. Update `## Context`/`## Plan of Work` to state the real shape:
`has` / `hasLabel` / `hasId` all arrive as one `HasStep` distinguished by
`HasContainer` key, so one recogniser unpacks all three.

### T3 [should-fix]
**Certificate**: C11 (Integration: alias-filter composition)
**Location**: `WalkerContext.java:236` (`putAliasFilter`), `GremlinStepWalker.java:242` (`buildResult` merge); track-4 `## Plan of Work` items 2/5
**Issue**: `WalkerContext.putAliasFilter` does `aliasFilters.put(alias, where)`
(overwrite), and `buildResult` merges builder + recogniser filters with
`putAll` (overwrite). `RecognitionContext` exposes no read or AND-compose for a
per-alias filter. Track 4 routinely contributes two filters to one alias:
`g.V(ids).has(k,v)` (start pins `@rid IN` via `putAliasFilter`, then `has`
overwrites it), `g.V().hasLabel(L).has(k,v)` (classEquals then `has`),
`has(k,v).where(__.has(...))`, `has(k,v).and(...)`. Each later contribution
silently drops the earlier filter, so e.g. `g.V(id1,id2).has("age",30)` returns
every age-30 vertex instead of the two ids — a wrong multiset, violating the
core translator-on/off equality contract. The plan's "multiple `HasContainer`s
AND together" covers only containers within a single `HasStep`, not
cross-recogniser or cross-alias composition.
**Proposed fix**: Make same-alias contributions AND-compose. Simplest: have
`putAliasFilter` (and the `buildResult` merge) AND an incoming clause with any
existing one via `MatchWhereBuilder.and` rather than overwrite; or add a
`RecognitionContext` read accessor and have recognisers AND explicitly. Add a
regression test for `g.V(ids).has(...)` and `hasLabel(...).has(...)` on one
alias.

### T4 [should-fix]
**Certificate**: C13 (Premise: positional-parameter APIs), C4 (adapter renders inline literals)
**Location**: `track-4.md` `## Plan of Work` item 7 (line 46), `## Interfaces` (line 74) — `WalkerContext.bindParam`
**Issue**: D5 says predicate literals "bind as `SQLPositionalParameter` slots
via a new `bindParam` on `WalkerContext`". Recognisers and
`GremlinPredicateAdapter` only ever see `RecognitionContext`, which has no
`bindParam` (nor does `WalkerContext` today). And the adapter renders literals
**inline** — `MatchLiteralBuilder.toLiteral(value)` returns a concrete
`SQLExpression`, not a parameter placeholder (`GremlinPredicateAdapter.java:103`).
So as specified there is no reachable seam for a recogniser to parameterize a
value, and the value-independent cache key (D5's whole point) cannot form. The
underlying mechanism is sound — `SQLPositionalParameter.getValue(params)` reads
`params.get(paramNumber)` and `CommandContext.setInputParameters(map)` exists —
but the interface surface is under-specified.
**Proposed fix**: Put `bindParam(value) -> SQLPositionalParameter` (slot
allocation + value recording) on `RecognitionContext`, implemented by
`WalkerContext`. Change `GremlinPredicateAdapter` to emit
`SQLPositionalParameter` for predicate values via that sink instead of inline
literals. Explicitly state which literals parameterize (predicate comparison
values) versus stay structural and must **not** (class names for `classEquals`,
`~label` values, RIDs for `@rid IN`) — a structural token bound as a param
would serve a wrong plan (D5 risk note).

### T5 [should-fix]
**Certificate**: C12 (Integration: NOT patterns)
**Location**: `track-4.md` `## Plan of Work` item 5 (line 44), `## Interfaces` "In scope (modified)" (line 75); `GremlinStepWalker.java:249` (`buildResult`), `MatchExecutionPlanner.java:759-771` (`manageNotPatterns`)
**Issue**: Edge-bearing `NotStep` is planned to append an `SQLMatchExpression`
to `notMatchExpressions`, but the wiring does not exist and is not listed as an
addition. `WalkerContext` has no `notMatchExpressions` field,
`RecognitionContext` has no sink for it, and `buildResult` never passes it to
`MatchPlanInputs.builder(...)` (it sets only pattern / aliasClasses /
aliasFilters / return*). `## Interfaces` "In scope (modified)" for
`WalkerContext` lists only `bindParam`. Separately, `manageNotPatterns` throws
`CommandExecutionException` not only when the first NOT alias is absent from the
positive pattern (line 760 — the track's stated pre-validation, correctly
backed by `MatchPatternBuilder.hasAlias`) but **also** when the NOT origin alias
carries a WHERE filter (lines 766–771). Because the plan is built lazily at
boundary-step execution, either throw is a hard query failure, not a native
decline.
**Proposed fix**: Enumerate the three additions (a `notMatchExpressions` list
on `WalkerContext`, a `RecognitionContext` sink method, and the
`.notMatchExpressions(...)` call in `buildResult`) in `## Interfaces`
"In scope (modified)". Add the second precondition to `NotStepRecogniser`'s
decline set: decline when the first NOT alias would carry a filter.

### T6 [should-fix]
**Certificate**: C5 (Premise: `classEquals` semantics)
**Location**: `track-4.md` `## Context` (line 34); `MatchWhereBuilder.java:65` (`classEquals`)
**Issue**: `MatchWhereBuilder.classEquals` emits exact `@class = 'className'`,
which its own Javadoc calls out as selecting "exactly the named class, unlike
the polymorphic MATCH `class:` node type" — it excludes subclass instances.
Gremlin `hasLabel` is polymorphism-sensitive (`YTDBHasLabelStep` respects the
`polymorphicQuery` setting via `YTDBLabelMatcher.matchesAny(..., polymorphic)`).
In polymorphic mode `hasLabel("Person")` must also match `Person`'s subclasses,
so an unconditional `classEquals` under-matches — a wrong (smaller) multiset.
`RecognitionContext.polymorphic()` exists precisely for this gate but the track
never conditions the `classEquals` translation on it. This overlaps the open
Phase-4 "schema-polymorphism BC2" reconciliation item, but it is live for this
track because Track 4 is `classEquals`'s first production caller.
**Proposed fix**: Specify that the `~label` → `classEquals` translation applies
only when `ctx.polymorphic()` is false; in polymorphic mode either decline
(safe, keeps native) or emit a subclass-inclusive predicate. Add a
polymorphic-vs-non-polymorphic `hasLabel` equivalence test.

### T7 [suggestion]
**Certificate**: C6 (Premise: `MatchWhereBuilder` string/rid methods)
**Location**: `track-4.md` `## Context` (line 34), `## Plan of Work` items 2/4 (lines 41,43), `## Interfaces` (lines 75,78)
**Issue**: Minor accuracy drift against HEAD that will mislead the decomposer:
(1) `aliasRids[a]` / `aliasClasses[a]` are described as map slots the recogniser
pins, but HEAD has neither on `WalkerContext` — single-id RID handling goes
through an `@rid IN` alias filter plus the planner's
`promoteStaticRidsFromFilters` collapse (`StartStepRecogniser` lines 125–133),
and class narrowing is a WHERE via `classEquals`, not a map entry. (2)
"`MatchWhereBuilder` (`startsWith` / `endsWith` / `matchesRegex` bodies if
stubbed in Track 1)" — `startsWith` is fully implemented (not a stub;
`MatchWhereBuilder.java:152`), and `endsWith` / `matchesRegex` do not exist at
all, so Track 4 authors them fresh alongside the new `SQLEndsWithCondition` AST
node and the `SQLMatchesCondition` find-mode.
**Proposed fix**: Reword to match HEAD: describe rid/class contribution as
WHERE clauses on the alias filter (not `aliasRids`/`aliasClasses` slots), and
drop the "if stubbed" hedge — state that `endsWith`/`matchesRegex` are new.

## Evidence base

#### C1 Premise: `StepRecogniser` contract is `Outcome recognize(StepCursor, RecognitionContext)`
- **Track claim**: recognisers implement the post-Track-3 contract, head via `cursor.take()`, trailing via `takeIf`/`takeWhile`, returning `ACCEPTED`/`DECLINE`.
- **Search performed**: Read `StepRecogniser.java` (grep fallback; PSI down).
- **Code location**: `StepRecogniser.java:47`
- **Actual behavior**: `@FunctionalInterface interface StepRecogniser { Outcome recognize(StepCursor cursor, RecognitionContext ctx); }` — package-private; a DECLINE discards the whole walk.
- **Verdict**: CONFIRMED

#### C2 Premise: `StepCursor` exposes `take` / `takeIf` / `takeWhile` / `peek` / `peek(int)`
- **Track claim**: "head via `cursor.take()`, trailing shape via `takeIf`/`takeWhile`".
- **Search performed**: Read `StepCursor.java`.
- **Code location**: `StepCursor.java:41-101`
- **Actual behavior**: `peek()`, `peek(int)`, `take()`, `takeIf(Class,Predicate)`, `takeWhile(Class,Predicate)` (+ single-arg defaults). Matching is exact-class (`step.getClass() == exact`), barriers skipped transparently.
- **Verdict**: CONFIRMED

#### C3 Premise: `WalkerContext implements RecognitionContext`; no traversal / step index
- **Track claim**: "`WalkerContext` now implements `RecognitionContext` and no longer holds the traversal or a step index."
- **Search performed**: Read `WalkerContext.java`, `RecognitionContext.java`.
- **Code location**: `WalkerContext.java:28`, `RecognitionContext.java:26`
- **Actual behavior**: `final class WalkerContext implements RecognitionContext`; interface exposes `polymorphic()`, `edgeLabelVerificationEnabled()`, `boundaryAlias()`, alias minting, `addNode`/`addEdge`/`addEdgeAsNode`, `putAliasFilter`, `putEdgeFilter`, `pinBoundary`, `setSingleReturnColumn`. No traversal, strategy list, or index reachable.
- **Verdict**: CONFIRMED

#### C4 Premise: `GremlinPredicateAdapter` neq-guard + flat-`Compare`-only + inline literals
- **Track claim**: adapter emits `has(k, neq(v))` as `k IS DEFINED AND k <> v`; handles only flat scalar `Compare`; declines the rest.
- **Search performed**: Read `GremlinPredicateAdapter.java`.
- **Code location**: `GremlinPredicateAdapter.java:107-118`, `72-106`
- **Actual behavior**: `toFilter(HasContainer)` returns `WHERE.and(WHERE.isDefined(key), comparison)` for `Compare.neq`; declines null/blank/reserved keys, non-`Compare` bi-predicates, null values, and unrenderable literal types. Renders the value **inline** via `MatchLiteralBuilder.toLiteral(value)` (line 103) — no parameter binding.
- **Verdict**: CONFIRMED (the inline-literal fact feeds T4)

#### C5 Premise: `MatchWhereBuilder.classEquals` exists and is exact (non-polymorphic)
- **Track claim**: folded-`hasLabel` `@class` narrowing via `MatchWhereBuilder.classEquals`.
- **Search performed**: Read `MatchWhereBuilder.java`.
- **Code location**: `MatchWhereBuilder.java:65-76`
- **Actual behavior**: builds `@class = 'className'` (`SQLRecordAttribute` + `SQLBinaryCondition`); Javadoc: "selects exactly the named class, unlike the polymorphic MATCH `class:` node type"; throws on null/blank. Method exists, but exactness makes it wrong under a polymorphic `hasLabel`.
- **Verdict**: PARTIAL (produces T6)

#### C6 Premise: `MatchWhereBuilder` method inventory (in/notIn/startsWith/containsText/and/or/not; no endsWith/matchesRegex)
- **Track claim**: Track 4 leans on `startsWith` and adds `endsWith`/`matchesRegex` "if stubbed in Track 1".
- **Search performed**: grep method signatures + Read `MatchWhereBuilder.java`.
- **Code location**: `MatchWhereBuilder.java:99,112,133,152,172,180,263,276,288`
- **Actual behavior**: `eq/op/in/notIn/between/containsText/startsWith/and/or/andOptional/isNull/isDefined/isNotDefined/not/wrap` present; `startsWith` fully implemented (half-open range, throws on empty). `endsWith`/`matchesRegex` absent (not stubs).
- **Verdict**: PARTIAL (produces T7)

#### C7 Premise: `SQLEndsWithCondition` is new; `SQLMatchesCondition`/`SQLContainsTextCondition` exist
- **Track claim**: D-TEXT-OPS adds `SQLEndsWithCondition` + find-mode on `SQLMatchesCondition`; collate on `SQLContainsTextCondition`.
- **Search performed**: `find -name` on each AST node.
- **Code location**: `SQLMatchesCondition.java`, `SQLContainsTextCondition.java` exist; `SQLEndsWithCondition.java` NOT FOUND.
- **Actual behavior**: `SQLContainsTextCondition` has `left`/`right` `SQLExpression` fields and a substring-`indexOf` evaluator (no collation today — collate transform is genuinely new). `SQLMatchesCondition` holds a `String right` regex. `SQLEndsWithCondition` does not exist — correctly a new node this track creates.
- **Verdict**: CONFIRMED

#### C8 Premise: `hasId(...)` step class
- **Track claim**: a `HasIdStepRecogniser` recognises `hasId`.
- **Search performed**: `unzip -l` on `gremlin-core-3.8.1-af9db90-SNAPSHOT.jar`; `javap -c` on `GraphTraversal.hasId`, `TraversalHelper.addHasContainer`, `T`.
- **Code location**: `GraphTraversal.hasId(Object,Object...)` bytecode offsets 359–399 and `hasId(P)` 48–62 — build `HasContainer(T.id.getAccessor(), P)` then `TraversalHelper.addHasContainer`. No `HasIdStep.class` in the jar (only `IdStep` in `step/map`, the `id()` step).
- **Actual behavior**: `hasId` → `HasStep` with a `~id` `HasContainer`. `addHasContainer` appends to a trailing `HasContainerHolder` (`HasStep`) or creates one.
- **Verdict**: WRONG (produces T1)

#### C9 Premise: `hasLabel` shape at translation time (folded into start step)
- **Track claim**: "`hasLabel` is usually folded by `YTDBGraphStepStrategy` into the start step's `hasContainers`, so the start recogniser pins the class."
- **Search performed**: Read `YTDBGraphStepStrategy` (applyPrior line 181-183, fold body 123-156), `GremlinToMatchStrategy` (applyPrior/applyPost 419-426), `StartStepRecogniser` (106-111); `javap` on `GraphStep` interfaces + `Configuring`/`GraphStepContract`.
- **Code location**: `YTDBGraphStepStrategy.java:182` (`applyPrior()` = `{GremlinToMatchStrategy.class}`), `StartStepRecogniser.java:106-111`, `GraphStep` implements `Configuring, GraphStepContract` (neither extends `HasContainerHolder`).
- **Actual behavior**: translator runs before `YTDBGraphStepStrategy`, so no fold has happened at translation time; `GraphStep` is not a `HasContainerHolder`, so `addHasContainer` makes a separate `HasStep[~label]`; and `StartStepRecogniser` declines a start step that carries folded containers. The folded-start premise is false on three counts.
- **Verdict**: WRONG (produces T2)

#### C10 Premise: `has(key)`→`TraversalFilterStep`, `hasNot(key)`→`NotStep`
- **Track claim**: bare presence forms desugar (TinkerPop 3.8.1) to `TraversalFilterStep(__.values(key))` / `NotStep(__.values(key))`.
- **Search performed**: `javap -c` on `GraphTraversal.has(String)` and `hasNot(String)`.
- **Code location**: `has(String)` bytecode `new TraversalFilterStep` + `__.values` + `addStep`; `hasNot(String)` `new NotStep` + `__.values` + `addStep`.
- **Actual behavior**: exactly as claimed — distinct step classes, so `TraversalFilterStepRecogniser` / `NotFilterStepRecogniser` keyed on those classes is sound (contrast T1/T2).
- **Verdict**: CONFIRMED

#### C11 Integration: per-alias filter composition
- **Plan claim**: multiple `HasContainer`s AND together; folded `hasLabel`, `hasId`, and `has` all contribute filters.
- **Actual entry point**: `WalkerContext.putAliasFilter` (`WalkerContext.java:236`), `GremlinStepWalker.buildResult` merge (`GremlinStepWalker.java:242-243`).
- **Caller analysis**: `putAliasFilter` does `aliasFilters.put` (overwrite); `buildResult` does `finalAliasFilters.putAll(ctx.aliasFilters)` (overwrite); `RecognitionContext` has no read/AND accessor. `StartStepRecogniser.java:132` already occupies the boundary alias with `@rid IN`.
- **Breaking change risk**: a second contribution to one alias silently drops the first → over-match (wrong multiset).
- **Verdict**: MISMATCHES (produces T3)

#### C12 Integration: NOT patterns into the planner
- **Plan claim**: edge-bearing NOT appends `SQLMatchExpression` to `notMatchExpressions`, pre-validating the first NOT alias.
- **Actual entry point**: `MatchPlanInputs` has a `notMatchExpressions` component + builder `.notMatchExpressions(...)` (`MatchPlanInputs.java:48,154`); consumed by the additive ctor (`MatchExecutionPlanner.java:516`) and `manageNotPatterns` (`:750-807`).
- **Caller analysis**: `buildResult` (`GremlinStepWalker.java:249-256`) does not call `.notMatchExpressions(...)`; `WalkerContext` has no such list; `RecognitionContext` has no sink. `manageNotPatterns` throws on absent first alias (`:760`) **and** on a filter on the origin alias (`:766-771`). `MatchPatternBuilder.hasAlias` (`:244`) exists for the pre-check.
- **Breaking change risk**: without the added wiring the edge-bearing NOT cannot reach the planner; the second precondition, if unhandled, throws at execution.
- **Verdict**: MISMATCHES / CALLERS AT RISK (produces T5)

#### C13 Premise: positional-parameter APIs for D5
- **Track claim**: literals bind as `SQLPositionalParameter`; boundary installs the map via `ctx.setInputParameters(map)`; `SQLPositionalParameter.getValue(params)`.
- **Search performed**: Read `SQLPositionalParameter.java`; grep `setInputParameters`.
- **Code location**: `SQLPositionalParameter.java:49-55` (`getValue` = `params.get(paramNumber)`), `44-46` (`toGenericStatement` → `PARAMETER_PLACEHOLDER`); `CommandContext.java:106` + `BasicCommandContext.java:499` (`setInputParameters`).
- **Actual behavior**: the resolution + generic-statement fingerprint mechanism exists and is sound. The gap is the missing recogniser-facing `bindParam` seam and the adapter's inline-literal rendering (see C4).
- **Verdict**: CONFIRMED (mechanism); interface gap surfaced in T4

#### C14 Premise: `QueryOperatorEquals.equals` singleton unbox + null short-circuit (D3 rationale)
- **Track claim**: `QueryOperatorEquals.equals` lines 63-69 unbox singletons, 71-73 null short-circuit — justifying the size-1 collection decline.
- **Search performed**: Read `QueryOperatorEquals.java:55-80`.
- **Code location**: `QueryOperatorEquals.java:~62-73`
- **Actual behavior**: if one operand is a size-1 `Collection` and the other is not a `Collection`, it unboxes the singleton; then `if (iLeft == null || iRight == null) return false`. Confirms `P.eq([a])` on an unknown-cardinality field is ambiguous → decline is the safe call.
- **Verdict**: CONFIRMED

#### C15 Premise: `YTDBHasLabelStep` type and sole producer
- **Track claim**: `HasLabelStepRecogniser` handles "the YTDB `YTDBHasLabelStep` subclass".
- **Search performed**: Read `YTDBHasLabelStep.java`; grep `new YTDBHasLabelStep`.
- **Code location**: `YTDBHasLabelStep.java:17` (`extends FilterStep<S>`); `YTDBGraphStepStrategy.java:149` (only `new YTDBHasLabelStep`).
- **Actual behavior**: `YTDBHasLabelStep` is produced only by `YTDBGraphStepStrategy`, which runs after the translator (C9), so it never exists at translation time. A recogniser keyed on it is dead code.
- **Verdict**: CONFIRMED (reinforces T2)
