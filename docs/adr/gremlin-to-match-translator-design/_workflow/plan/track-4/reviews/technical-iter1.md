<!-- MANIFEST
findings: 3   severity: {blocker: 0, should-fix: 1, suggestion: 2}
index:
  - {id: T1, sev: should-fix, loc: "core/.../translator/strategy/StartStepRecogniser.java:228; MatchWhereBuilder.java:99", anchor: "### T1 ", cert: "P-hasId, I-ridfilter", basis: "hasId ~id branch needs @rid record-attribute IN; MatchWhereBuilder.in emits a plain-property IN that findRidInList rejects and the executor resolves as an absent property -> empty multiset"}
  - {id: T2, sev: suggestion, loc: "core/.../sql/parser/SQLMatchesCondition.java:281", anchor: "### T2 ", cert: "E-roundtrip", basis: "R2 round-trip site list (copy + toGenericStatement) omits splitForAggregation/equals/hashCode, which also reconstruct the node; a new findMode field would drop there"}
  - {id: T3, sev: suggestion, loc: "plan/track-4.md:43", anchor: "### T3 ", cert: "P-ytdbhaslabel", basis: "wording: YTDBHasLabelStep class does exist in the tree; what is absent at translation time is a step-list instance"}
evidence_base: {section: "## Evidence base", certs: 16, matches: 14}
cert_index:
  - {id: P-classlist, verdict: MATCHES, anchor: "#### P-classlist "}
  - {id: P-wherebuilder, verdict: MATCHES, anchor: "#### P-wherebuilder "}
  - {id: P-classEquals, verdict: MATCHES, anchor: "#### P-classEquals "}
  - {id: P-cursor, verdict: MATCHES, anchor: "#### P-cursor "}
  - {id: P-adapter, verdict: MATCHES, anchor: "#### P-adapter "}
  - {id: P-promote, verdict: MATCHES, anchor: "#### P-promote "}
  - {id: P-applyprior, verdict: MATCHES, anchor: "#### P-applyprior "}
  - {id: P-queryequals, verdict: MATCHES, anchor: "#### P-queryequals "}
  - {id: P-sqlnodes, verdict: MATCHES, anchor: "#### P-sqlnodes "}
  - {id: E-haskey, verdict: MATCHES, anchor: "#### E-haskey "}
  - {id: E-hasstep, verdict: MATCHES, anchor: "#### E-hasstep "}
  - {id: I-andcompose, verdict: MATCHES, anchor: "#### I-andcompose "}
  - {id: P-hasId, verdict: PARTIAL, anchor: "#### P-hasId "}
  - {id: I-ridfilter, verdict: PARTIAL, anchor: "#### I-ridfilter "}
  - {id: E-roundtrip, verdict: PARTIAL, anchor: "#### E-roundtrip "}
  - {id: P-ytdbhaslabel, verdict: PARTIAL, anchor: "#### P-ytdbhaslabel "}
flags: [CONTRACT_OK]
-->

## Findings

### T1 [should-fix]
**Certificate**: P-hasId, I-ridfilter
**Location**: track-4.md `## Context and Orientation` (line 43) and `## Plan of Work` step 2 (line 51) — the `~id` → `@rid IN [...]` branch; codebase: `StartStepRecogniser.buildRidInExpression` (StartStepRecogniser.java:228-244), `MatchWhereBuilder.in` (MatchWhereBuilder.java:99), `SQLWhereClause.isBareRidExpression` (SQLWhereClause.java:1155-1166).
**Issue**: The track routes `hasId(...)` through the HasStep recogniser's `~id` branch to an "`@rid IN [...]` WHERE clause on the alias filter", relying on `promoteStaticRidsFromFilters` for the direct-RID fast path. But the only `IN` builder on the shared `MatchWhereBuilder` is `in(field, values)`, whose left side is `fieldExpression(field)` = a plain `SQLIdentifier` (MatchWhereBuilder.java:99, 364-366). An `@rid IN` built that way is wrong on two counts: (a) `promoteStaticRidsFromFilters` recognises a RID-IN only via `findRidInList` → `isBareRidExpression`, which requires the left side to be a `SQLRecordAttribute` named `@rid` (SQLWhereClause.java:1145, 1155-1166) — a plain identifier is never promoted; and (b) the executor resolves a bare identifier `@rid`/`rid` as an ordinary (absent) property, so the filter matches nothing → an empty multiset instead of the id-selected vertices. The only correct construction in the tree is `StartStepRecogniser.buildRidInExpression` (record-attribute left side, RID literals), which is `private static` and is not called out for reuse; the track's `## Interfaces and Dependencies` does not list `StartStepRecogniser` among the modified files. The track also omits id-normalisation for `~id` (the `normaliseIds` decline-on-unconvertible logic StartStepRecogniser carries), and does not note that `hasId` is a filter (set membership), so it must NOT inherit StartStepRecogniser's duplicate-id decline — that exists only because `g.V(ids)` has one-emission-per-occurrence seek semantics.
**Proposed fix**: In `## Plan of Work` step 2 / `## Interfaces and Dependencies`, state that the `~id` branch builds `@rid IN` via the record-attribute form — extract `StartStepRecogniser.buildRidInExpression` (and the id-normalisation) into a shared helper both recognisers call, rather than `MatchWhereBuilder.in`. Add `StartStepRecogniser` (or a new shared `RidFilters` helper) to the in-scope-modified file list. Note the seek-vs-filter dedup difference so the `~id` path does not decline on repeated ids. Confirmed by the existing acceptance line "`hasId` (single + multi) translate to the same multiset as native".

### T2 [suggestion]
**Certificate**: E-roundtrip
**Location**: track-4.md `## Plan of Work` step 4 (line 53, the R2 round-trip note) and `## Validation and Acceptance` (line 72); codebase: `SQLMatchesCondition.splitForAggregation` (SQLMatchesCondition.java:275-289), `SQLContainsTextCondition.splitForAggregation` (SQLContainsTextCondition.java:297-307).
**Issue**: The R2 note enumerates the round-trip sites a new `SQLMatchesCondition` find-mode field must survive as "`copy()` and `toGenericStatement()`". Both are real (SQLMatchesCondition.java:166, 112), but the same class reconstructs itself field-by-field a third time in `splitForAggregation()` (lines 281-288), and carries `equals()`/`hashCode()` (lines 192, 215) that a new field should join. A find-mode field added to only `copy()`/`toGenericStatement()` is silently dropped by `splitForAggregation`. Live reachability in Phase 1 is low: `splitForAggregation` returns `this` unchanged when `isAggregate()` is false (line 278-280), and a Gremlin-translated regex filter (`field MATCHES literal`) is never an aggregate, so the drop cannot fire until aggregations (Track 6) can compose with a translated regex. This is a completeness gap in the site enumeration, not a live bug — but R2's whole purpose is to prevent a silently-dropped field.
**Proposed fix**: Broaden the R2 note to "must round-trip through `copy()`, `toGenericStatement()`, and `splitForAggregation()`, and be reflected in `equals()`/`hashCode()`", so the implementer updates every reconstruction site when adding the find-mode flag. (`SQLEndsWithCondition` is new, so its author writes all sites at once; the risk is specific to editing the existing `SQLMatchesCondition`.)

### T3 [suggestion]
**Certificate**: P-ytdbhaslabel
**Location**: track-4.md `## Context and Orientation` (line 43): "neither `YTDBHasLabelStep` nor a `HasIdStep` class exists at translation time"; codebase: `YTDBHasLabelStep.java` exists.
**Issue**: `YTDBHasLabelStep` resolves to a real class in the tree (`core/.../gremlin/traversal/step/filter/YTDBHasLabelStep.java`); `HasIdStep` does not. The literal claim "class exists at translation time" is true for `HasIdStep` (no such class) but imprecise for `YTDBHasLabelStep` — the class always exists; what is absent at translation time is a `YTDBHasLabelStep` *instance in the step list* (the GraphStep fold that would mint one runs later, in `YTDBGraphStepStrategy`, after the g2m strategy). The surrounding paragraph ("no GraphStep fold has happened … `hasLabel` is never folded onto the start step") already conveys the correct intent, so this is a wording nit, not a design error.
**Proposed fix**: Reword to distinguish the two: "no `HasIdStep` class exists, and no `YTDBHasLabelStep` instance appears in the step list at translation time (the fold that mints one runs later)."

## Evidence base

#### P-classlist: every production class named in the track file resolves (or is correctly marked new/deleted)
- **Track claim**: The four Phase-1 sections + Plan of Work name `MatchWhereBuilder`, `MatchLiteralBuilder`, `MatchPatternBuilder`, `GremlinPredicateAdapter`, `SQLContainsTextCondition`, `SQLMatchesCondition`, `SQLInCondition`, `SQLNotInCondition`, `SQLBetweenCondition`, `QueryOperatorEquals`, `StartStepRecogniser`, `GremlinStepWalker`, `StepRecogniser`, `StepCursor`, `RecognitionContext`, `WalkerContext`, `GremlinToMatchStrategy`, `YTDBGraphStepStrategy`, `MatchEdgePathItems`, `MatchPlanInputs`, `GremlinPatternAssembler`, `MatchExecutionPlanner`, `SQLIsDefinedCondition`, `SQLIsNotDefinedCondition` as existing; `SQLEndsWithCondition`, `HasStepRecogniser`, `TraversalFilterStepRecogniser` as new; `MatchClassFilters`, `HasLabelStepRecogniser`, `HasIdStepRecogniser`, `HasIdStep` as non-existent.
- **Search performed**: `find … -name '<Class>.java'` over the working tree (mcp-steroid `steroid_execute_code` was non-functional this session — see caveat — so PSI find-class was unavailable; used `find` fallback per the prompt's fallback rule).
- **Code location**: all "existing" names resolve to a single `core/src/main/java/...` file each; `MatchClassFilters`, `HasLabelStepRecogniser`, `HasIdStepRecogniser`, `HasIdStep`, `SQLEndsWithCondition`, `HasStepRecogniser`, `TraversalFilterStepRecogniser`, `SubTraversalPredicateAdapter`, `GremlinPlanCache` return zero matches in the current tree.
- **Actual behavior**: Existing names have exactly one non-worktree match at the expected package. The absent-and-planned-new names (`SQLEndsWithCondition`, `HasStepRecogniser`, `TraversalFilterStepRecogniser`) are explicitly marked "In scope (new)" in `## Interfaces and Dependencies`. The absent-and-claimed-deleted `MatchClassFilters` matches the track's "Track 3's rework deleted the interim `MatchClassFilters`". `HasLabelStepRecogniser`/`HasIdStepRecogniser`/`HasIdStep` matching zero confirms the "no separate recogniser / no `HasIdStep` class" claim. Track 5-only names (`SubTraversalPredicateAdapter`, `GremlinPlanCache`) are absent and correctly listed out-of-scope.
- **Verdict**: MATCHES
- **Detail**: Reference-accuracy caveat: filename `find` gives an unambiguous single match per name (package matches the reconstructed FQN), so existence is solid; it cannot see reflective/generated references, but none of these names are reached reflectively.

#### P-wherebuilder: MatchWhereBuilder has classEquals/startsWith/isDefined/and but NOT endsWith/matchesRegex
- **Track claim**: `startsWith` exists; `endsWith` / `matchesRegex` are new here; `classEquals`, `isDefined`, `and`, `containsText`, `in`, `between` exist.
- **Search performed**: Read of MatchWhereBuilder.java in full.
- **Code location**: MatchWhereBuilder.java — `classEquals` L65, `op` L82, `in` L99, `notIn` L112, `between` L121, `containsText` L133, `startsWith` L152, `and` L172, `or` L180, `andOptional` L200, `isNull` L229/238, `isDefined` L263, `isNotDefined` L276, `not` L288.
- **Actual behavior**: No `endsWith` and no `matchesRegex` method present. `classEquals` emits an exact `@class = 'name'` via `SQLRecordAttribute` (L65-76), matching the track's "excludes subclasses" claim. `startsWith` builds the half-open range `>= p AND < p⁺` (L152-159).
- **Verdict**: MATCHES

#### P-classEquals: MatchWhereBuilder.classEquals has no production caller yet
- **Track claim**: "`MatchWhereBuilder.classEquals` has no production caller yet — Track 4's folded `hasLabel` is its first."
- **Search performed**: `grep -rn "classEquals" core/src/main/java --include=*.java | grep -v /test/`.
- **Code location**: matches only in MatchWhereBuilder.java:65 (declaration) and three Javadoc/comment mentions (VertexHopRecogniser.java:33, WalkerContext.java:76, GremlinPatternAssembler.java:25). No invocation site.
- **Actual behavior**: `classEquals` is invoked nowhere in production; the three references are prose in Javadoc.
- **Verdict**: MATCHES
- **Detail**: Reference-accuracy caveat: grep (not PSI find-usages) — `classEquals` is a plain public method reached by direct call only, so a grep of the identifier is reliable here; no polymorphic/generic dispatch or reflective call path exists.

#### P-cursor: StepCursor / StepRecogniser / Outcome contract matches the track's stated shape
- **Track claim**: recognisers implement `Outcome recognize(StepCursor, RecognitionContext)`; head via `cursor.take()`, trailing shape via `takeIf`/`takeWhile`, returning `ACCEPTED`/`DECLINE`.
- **Search performed**: Read of StepCursor.java, StepRecogniser.java; grep of Outcome.java.
- **Code location**: StepRecogniser.java:47 `Outcome recognize(StepCursor cursor, RecognitionContext ctx)`; StepCursor.java `peek()` L47, `peek(int)` L62, `take()` L72, `takeIf` L81/94, `takeWhile` L91/99; Outcome.java:19 `ACCEPTED, DECLINE`.
- **Actual behavior**: Exactly the post-Track-3 cursor contract the track describes. Matching is by exact class (`step.getClass() == exact`), consistent with the D9 exact-class dispatch premise.
- **Verdict**: MATCHES

#### P-adapter: GremlinPredicateAdapter is a Compare-only skeleton with the neq presence guard already in place
- **Track claim**: Track 3 left a skeleton translating only `has(...)` in edge chains; it "already emits `has(k, neq(v))` as `k IS DEFINED AND k <> v`"; other comparisons need auditing.
- **Search performed**: Read of GremlinPredicateAdapter.java in full.
- **Code location**: GremlinPredicateAdapter.java `toFilter(HasContainer)` L72; reserved-key decline via `WalkerContext.isReservedHasKey` L77; `Compare` gate L89; `MatchLiteralBuilder.toLiteral` with `IllegalArgumentException` decline L102-106; neq guard `WHERE.and(WHERE.isDefined(key), comparison)` L108-117.
- **Actual behavior**: Handles the six scalar `Compare` operators over a single literal; declines null/reserved/non-Compare/unrenderable. The `neq` → `IS DEFINED AND <>` guard is present and its inline comment states the other five comparisons need no guard (absent → operator false → excluded, matching native) — exactly the divergence the track says Track 4 audits.
- **Verdict**: MATCHES

#### P-promote: MatchExecutionPlanner.promoteStaticRidsFromFilters exists and handles @rid IN
- **Track claim**: the single-`hasId` case relies on `promoteStaticRidsFromFilters` to collapse an `@rid IN` to the direct-RID fetch; multi routes through `@rid IN`.
- **Search performed**: Read of MatchExecutionPlanner.java L4758-4877.
- **Code location**: `promoteStaticRidsFromFilters` L4758 (static); handles `@rid = x` via `findRidEquality` (L4772) and `@rid IN [...]` via `findRidInList`+`toPromotedSqlRidList` (L4790-4806, L4831).
- **Actual behavior**: An `@rid IN [inline-literal-list]` on an alias filter with no involved aliases and an early-calculable right side is promoted to `aliasPinnedRids`; a size-1 IN collapses to a single pinned RID (direct fetch). Inline literals (the track's Track-4 rendering) are early-calculable, so the fast path fires. `SQLWhereClause.findRidInList` recurses into AND/OR blocks (SQLWhereClause.java:953-975), so AND-composing `@rid IN` with a `has` clause preserves promotion (see I-andcompose).
- **Verdict**: MATCHES

#### P-applyprior: YTDBGraphStepStrategy.applyPrior() names GremlinToMatchStrategy
- **Track claim**: "The g2m translator runs before `YTDBGraphStepStrategy` (`YTDBGraphStepStrategy.applyPrior()` returns `{GremlinToMatchStrategy.class}`)".
- **Search performed**: grep of YTDBGraphStepStrategy.java.
- **Code location**: YTDBGraphStepStrategy.java:181-182 `public Set<...> applyPrior() { return Set.of(GremlinToMatchStrategy.class); }`.
- **Actual behavior**: Verbatim match — the half-measure strategy lists the translator in its prior set, so TinkerPop's topological sort runs the translator first and the GraphStep fold has not happened at translation time.
- **Verdict**: MATCHES

#### P-queryequals: QueryOperatorEquals.equals singleton-unbox and null short-circuit at the cited lines
- **Track claim**: `## Interfaces … Signatures`: "`QueryOperatorEquals.equals` (lines 63-69 unbox, 71-73 null short-circuit)"; D3 declines size-1 collection equality because of the auto-unbox.
- **Search performed**: Read of QueryOperatorEquals.java L40-109.
- **Code location**: QueryOperatorEquals.java L63-69 (Collection size-1 unbox against a scalar, symmetric for left/right), L71-73 (`if (iLeft == null || iRight == null) return false;`).
- **Actual behavior**: The cited line numbers are exact. The size-1 collection is unboxed to its element before comparison, substantiating the D3 rationale that `P.eq([a])` cannot be faithfully represented at translation time when field cardinality is unknown.
- **Verdict**: MATCHES

#### P-sqlnodes: SQLMatchesCondition / SQLContainsTextCondition current shape supports the D-TEXT-OPS plan
- **Track claim**: `SQLMatchesCondition` gets a new find-mode flag and must round-trip; `SQLContainsTextCondition` gets a collate transform (making SQL CONTAINSTEXT collation-aware, R5); `SQLEndsWithCondition` is new.
- **Search performed**: Read of both files in full.
- **Code location**: SQLMatchesCondition.java — `evaluate` uses `Pattern.compile(regex).matcher(v).matches()` (L59-72, full/anchored, case-sensitive), `copy()` L166, `toGenericStatement()` L112, `splitForAggregation()` L275; no find-mode field. SQLContainsTextCondition.java — `evaluate` is a plain case-sensitive `String.indexOf > -1` (L42, L63) with no collation, `copy()` L156, `splitForAggregation()` L297.
- **Actual behavior**: MATCHES emits a full-match; a find-mode flag switching to `.find()` is a coherent addition (Gremlin `TextP.regex` is a partial match). CONTAINSTEXT is currently case-sensitive with no collation, so a collate transform genuinely changes existing SQL semantics on `ci`-collated properties (R5 correctly flagged). Both nodes carry a `splitForAggregation` reconstruction — see E-roundtrip / T2.
- **Verdict**: MATCHES

#### E-haskey: has(key) desugars to TraversalFilterStep(__.values(key)), not HasStep
- **Trigger**: `has("k")` single-arg presence form at translation time.
- **Code path trace**:
  1. `GraphTraversal.has(String)` in the fork `io.youtrackdb:gremlin-core:3.8.1-af9db90` — javap `-c` of the default method shows `new …/step/filter/TraversalFilterStep` (offset 31) with `__.values([String])` (offset 49) as its child traversal (offset 52 `<init>`).
  2. `TraversalFilterStep` is `public final class` (javap), so the D9 exact-class key `TraversalFilterStep.class` catches it and no subclass can slip through.
- **Outcome**: `has(key)` lands as a `TraversalFilterStep`, bypassing `HasStep`/`HasStepRecogniser`, exactly as the track's Plan of Work step 3 requires. The new `TraversalFilterStepRecogniser` Case A must accept only the `__.values(key)` single-child shape and decline other TraversalFilterStep shapes (e.g. `filter(traversal)`) under D3 — feasible; no other Phase-1 track registers `TraversalFilterStep.class`.
- **Track coverage**: yes (line 43, step 3).

#### E-hasstep: has(k,v) / hasLabel / hasId all arrive as one plain HasStep at translation time
- **Trigger**: `has(k,v)`, `hasLabel(L)`, `hasId(id)` before the provider GraphStep fold.
- **Code path trace**:
  1. `GraphStep` (fork) implements `Configuring, GraphStepContract` but NOT `HasContainerHolder` (javap) — so no has-container fold onto the start step is possible before `YTDBGraphStepStrategy` runs.
  2. The g2m strategy runs before `YTDBGraphStepStrategy` (P-applyprior), so the fold has not happened.
  3. `GraphTraversal.has(String,Object)` → `has(String,P)` → `new HasContainer(String,P)` → `TraversalHelper.addHasContainer(admin, container)` (javap offsets 54/63/66); `hasLabel(String,String...)` and `hasId(Object,Object...)` likewise route through `TraversalHelper.addHasContainer` (javap). `addHasContainer` appends to a trailing `HasStep` or mints one, producing a plain `HasStep` (a `HasContainerHolder`, `getHasContainers()` per javap).
  4. `HasStep` is a non-final `public class`, but dispatch is by exact runtime class; `has`/`hasLabel`/`hasId` all produce the plain `HasStep`, and no `HasStep` subclass instance exists at translation time.
- **Outcome**: One `HasStepRecogniser` keyed on `HasStep.class`, iterating `getHasContainers()` and branching on key (`~label`/`~id`/property), is a sound single-recogniser design — validating the collapse recorded in the superseded pre-split T1/T2 blockers.
- **Track coverage**: yes (line 43-51).

#### I-andcompose: same-alias filter contributions must AND-compose; putAliasFilter/buildResult currently overwrite
- **Plan claim**: "`putAliasFilter` (and the `buildResult` merge) must AND an incoming clause with any existing one via `MatchWhereBuilder.and` rather than replace it"; the scenario `g.V(ids).has(k,v)` contributes `@rid IN` then `has` to one alias.
- **Actual entry point**: `WalkerContext.putAliasFilter` (WalkerContext.java:236) is `aliasFilters.put(alias, where)` — a Map put that OVERWRITES on a same-alias second call; `GremlinStepWalker.buildResult` (GremlinStepWalker.java:239-243) does `new LinkedHashMap<>(ir.aliasFilters()); finalAliasFilters.putAll(ctx.aliasFilters)` — recogniser entries OVERRIDE builder entries on the same alias (Javadoc L237 says exactly this).
- **Caller analysis**: `StartStepRecogniser.recognize` writes the `g.V(ids)` `@rid IN` filter for the single AND multi case via `ctx.putAliasFilter(BOUNDARY_ALIAS, …)` (StartStepRecogniser.java:132), using a hand-built `@rid` record-attribute IN (L228-244). Track 4's `HasStepRecogniser` writing a `has` filter to the same `BOUNDARY_ALIAS` via `putAliasFilter` would therefore overwrite the `@rid IN` under today's semantics → the exact over-match the track warns of.
- **Breaking change risk**: The AND-composition change is behavior-additive (Tracks 2-3 never place two filters on one alias, so no existing translated shape changes). `MatchWhereBuilder.and`/`andOptional` (MatchWhereBuilder.java:172/200) exist to compose the clauses; `findRidInList` recursing into AND (SQLWhereClause.java:953-975) means the composed `(@rid IN) AND (has)` still promotes the RID, so the fast path is retained.
- **Verdict**: MATCHES — the track correctly identifies both merge points and the fix is feasible.

#### P-hasId: the ~id → @rid IN construction is under-specified (T1)
- **Track claim**: "`~id` → an `@rid IN [...]` WHERE clause on the alias filter".
- **Search performed**: Read of StartStepRecogniser.java, MatchWhereBuilder.java, SQLWhereClause.java (findRidInList/isBareRidExpression).
- **Code location**: `MatchWhereBuilder.in` L99 (plain-identifier left); `StartStepRecogniser.buildRidInExpression` L228-244 (record-attribute left, private); `SQLWhereClause.isBareRidExpression` L1155-1166 (requires `SQLRecordAttribute` `@rid`).
- **Actual behavior**: The shared builder cannot produce a promotable/executable `@rid IN`; only the private StartStepRecogniser helper can. The track does not name the record-attribute requirement, the helper reuse, the id-normalisation, or the filter-vs-seek dedup difference.
- **Verdict**: PARTIAL → T1.

#### I-ridfilter: promotion + executor both require the @rid record-attribute IN shape (T1)
- **Plan claim**: single `hasId` collapses to the direct-RID fetch via `promoteStaticRidsFromFilters`.
- **Actual entry point**: `promoteStaticRidsFromFilters` → `findRidInList` → `tryExtractRidInFromTerm` → `isBareRidExpression` (SQLWhereClause.java:1137-1166).
- **Caller analysis**: promotion fires only when the IN's left is a bare `@rid` `SQLRecordAttribute`. A `MatchWhereBuilder.in`-built IN (plain identifier) is neither promoted nor matches any record.
- **Breaking change risk**: If implemented via `MatchWhereBuilder.in`, `hasId` returns an empty multiset (correctness), and even a corrected non-record-attribute form loses the fast path.
- **Verdict**: PARTIAL → T1.

#### E-roundtrip: SQLMatchesCondition find-mode field must survive splitForAggregation too (T2)
- **Trigger**: a new `findMode` boolean added to `SQLMatchesCondition`, then a plan clone / aggregation split.
- **Code path trace**:
  1. `copy()` (L166) and `toGenericStatement()` (L112) — the two sites the R2 note lists.
  2. `splitForAggregation()` (L275-289) reconstructs a fresh `SQLMatchesCondition` field-by-field and would drop a `findMode` not copied there — BUT returns `this` unchanged when `isAggregate()` is false (L278-280), and a Gremlin regex filter (`field MATCHES literal`) is never an aggregate, so the drop is unreachable until Track 6 aggregations can compose with a translated regex.
  3. `equals()`/`hashCode()` (L192/215) omit a new field unless updated.
- **Outcome**: No live Phase-1 bug, but the R2 site enumeration is incomplete; a future aggregate+regex combination would silently revert find-mode to full-match.
- **Track coverage**: partial (only copy/toGenericStatement listed) → T2.

#### P-ytdbhaslabel: YTDBHasLabelStep class exists in the tree (T3)
- **Track claim**: "neither `YTDBHasLabelStep` nor a `HasIdStep` class exists at translation time".
- **Search performed**: `find -name 'YTDBHasLabelStep.java' / 'HasIdStep.java'`.
- **Code location**: `YTDBHasLabelStep.java` exists at `core/.../gremlin/traversal/step/filter/`; `HasIdStep.java` returns zero matches.
- **Actual behavior**: The `YTDBHasLabelStep` class exists; only a step-list *instance* is absent at translation time (the fold runs later). `HasIdStep` genuinely does not exist.
- **Verdict**: PARTIAL → T3 (wording precision only; design intent is correct).
