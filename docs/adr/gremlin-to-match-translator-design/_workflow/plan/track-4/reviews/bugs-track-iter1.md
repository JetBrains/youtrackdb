<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 0, suggestion: 1}
index:
  - {id: BG1, sev: suggestion, loc: GremlinPredicateAdapter.java:346-354, anchor: "### BG1 ", cert: C6, basis: "declared-String startingWith index range cannot throw on schema-violating non-String operand — silent row exclusion vs native query abort (documented tradeoff)"}
evidence_base: {section: "## Evidence base", certs: 8, matches: 1}
cert_index:
  - {id: C1, verdict: REFUTED, anchor: "#### C1 "}
  - {id: C2, verdict: REFUTED, anchor: "#### C2 "}
  - {id: C3, verdict: REFUTED, anchor: "#### C3 "}
  - {id: C4, verdict: REFUTED, anchor: "#### C4 "}
  - {id: C5, verdict: REFUTED, anchor: "#### C5 "}
  - {id: C6, verdict: NOTE, anchor: "#### C6 "}
  - {id: C7, verdict: REFUTED, anchor: "#### C7 "}
  - {id: C8, verdict: REFUTED, anchor: "#### C8 "}
flags: [CONTRACT_OK]
-->

## Findings

### BG1 [suggestion] Declared-String `startingWith` index range cannot mirror native throw on schema-violating operands

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinPredicateAdapter.java` (lines 346–354, `startsWithFilter`); reached from `HasStepRecogniser` (lines 125–126, `typeGate` keyed on the same-step `~label` class) and `EdgeHopRecogniser` (lines 111–112, gate keyed on the edge label).

**Issue**: When `PropertyTypeGate.isDeclaredString` is true, `startsWithFilter` emits the index-aware half-open range from `MatchWhereBuilder.startsWith` rather than the strict `SQLStartsWithCondition` node. That range compares operands without a strict String type check — a documented limitation (`MatchWhereBuilder.java` lines 208–211). If a row stores a non-`String` value under a property the schema declares as `STRING`, native `Text.startingWith` / `Text.notStartingWith` aborts the traversal with a `ClassCastException`, while the translated range evaluates the row to false (positive `startingWith`) or true under the guarded negation (`notStartingWith`), so the query can return a partial multiset instead of erroring.

This is not reachable on declared non-`String` properties (the gate routes those to `startsWithStrict`, which throws — covered by `startingWithDeclaredNonString_translatesStrict_andBothThrow` in `PredicateTraversalEquivalenceTest`). It is a schema-violation / mixed-data edge case on the performance path, not the common declared-type path.

**Evidence**: Code trace in cert C6; step-level review BG1 (polymorphic subclass non-`String` decline) is obsolete on HEAD — the cumulative diff replaced `isNonStringProperty` decline with strict-mode `Text` nodes that throw at execution, and `polymorphicNonStringTextOnSubclassOnlyProperty_translatesStrict_andBothThrow` pins parity without a subclass type sweep.

## Evidence base

#### C1 REFUTED — same-alias filter overwrite (WalkerContext / buildResult AND-composition)

Claim: `putAliasFilter` or `GremlinStepWalker.buildResult` still replaces an earlier alias filter, so `g.V(ids).has(k,v)` or `hasLabel(L).has(k,v)` would drop the `@rid IN` or class-narrowing clause.

Refuted: `WalkerContext.putAliasFilter` (lines 293–305) AND-composes with any existing entry via `MatchWhereBuilder.and`. `GremlinStepWalker.buildResult` (lines 267–268) merges recogniser-supplied filters into builder-supplied ones with the same `andWhere` merge function. `PredicateTraversalEquivalenceTest.ridInAndHas_andCompose_onSameAlias` and `hasLabelAndHas_andCompose_onSameAlias` exercise both shapes end-to-end. `SQLWhereClause.findRidInList` recurses into `SQLAndBlock` (lines 963–966), so promotion of `@rid IN` still works when AND-composed with a property predicate.

#### C2 REFUTED — `@rid IN` record-attribute seam / promotion broken across StartStep and HasStep

Claim: `HasStepRecogniser` builds `~id` filters with `MatchWhereBuilder.in` (plain identifier) or diverges from `StartStepRecogniser`, so `promoteStaticRidsFromFilters` never lifts co-located RID filters.

Refuted: `HasStepRecogniser.translateHasId` (lines 210–216) calls `StartStepRecogniser.buildRidInExpression` unchanged — left side is `SQLRecordAttribute @rid`, matching the start-step path. `HasStepRecogniserTest.hasIdSingle_contributesRidIn` and equivalence tests pin single/multi/duplicate-id semantics. Step 3 review C2 (duplicate `@rid IN` emits twice) was refuted empirically — MATCH collapses duplicate pinned roots.

#### C3 REFUTED — polymorphic subclass non-`String` `Text` silent divergence (step 3 BG1 regression)

Claim: polymorphic `hasLabel(parent).has(subclassOnlyIntProp, Text…)` translates to lenient `CONTAINSTEXT` returning rows while native throws, because `isDeclaredStringProperty` / the old `isNonStringProperty` gate resolves only the named class and superclasses.

Refuted on HEAD: `GremlinPredicateAdapter` emits strict `Text` nodes (`containsText` / `endsWith` / `matchesRegex` with `strict=true`; `startingWith` falls back to `startsWithStrict` when not declared String). `PredicateTraversalEquivalenceTest.polymorphicNonStringTextOnSubclassOnlyProperty_translatesStrict_andBothThrow` asserts both pipelines throw. The step 3 gate’s subclass-sweep fix targeted the superseded decline gate; strict mode makes the sweep unnecessary for `Text` parity.

#### C4 REFUTED — `HasStepRecogniser` mutates context before validating all containers (A6)

Claim: an untranslatable container in a mixed `HasStep` leaves partial `WalkerContext` state.

Refuted: second pass translates every `~id` / property container into `whereExprs` before any `addNode` / `putAliasFilter` (lines 128–164). Decline paths in the first pass (`~label` validation) return before mutation. `HasStepRecogniserTest.reservedKeyContainer_declinesWithNoMutation` pins zero mutation on decline.

#### C5 REFUTED — `TraversalFilterStepRecogniser` misses the `values→properties` rewrite

Claim: `has(key)` declines in production because the optimiser rewrites `values(key)` to `properties(key)`.

Refuted: `presenceKey` accepts both `PropertyType.VALUE` and `PropertyType.PROPERTY` (lines 89–91). `TraversalFilterStepRecogniserTest.propertiesKeyPresence_contributesIsDefined` covers the production shape. Presence filters AND-compose via the same `putAliasFilter` path as other boundary filters.

#### C6 NOTE — index-range `startingWith` on schema-violating non-`String` data (BG1)

Survives as a suggestion only: when `typeGate.isDeclaredString` is true and a finite prefix range is built, comparison operators evaluate false on a present non-`String` operand instead of throwing. Native `Text.startingWith` / `notStartingWith` error on the first offending row; the translated plan can return other rows. Documented intentional tradeoff for the index path (`MatchWhereBuilder.startsWithStrict` Javadoc). Not a regression on declared non-`String` properties (strict node) or on strict `containing` / `endingWith` / `regex` paths.

#### C7 REFUTED — `EdgeHopRecogniser` type gate omits edge class / leaves lenient `Text` on non-`String` edge props

Claim: edge `has(...)` still routes through `toFilter` without schema context, diverging from native on non-`String` edge properties.

Refuted: `EdgeHopRecogniser` (lines 111–116) passes `key -> ctx.isDeclaredStringProperty(edgeLabel, key)` into `toFilter`. All `Text` / regex predicates emit strict nodes regardless; the gate only selects `startingWith` range vs strict. `PredicateTraversalEquivalenceTest.edgeContainingNonStringProperty_translatesStrict_andBothThrow` and `edgeContainingStringProperty_matchesNative` cover the edge path end-to-end.

#### C8 REFUTED — `eq(null)` bare `IS NULL` over-matches absent properties through recogniser stack

Claim: property `has(key, eq(null))` reaching `HasStepRecogniser` → adapter emits bare `IS NULL`.

Refuted: `GremlinPredicateAdapter.translateCompare` rewrites `eq(null)` to `guarded(key, WHERE.isNull(key))` (lines 254–256), i.e. `IS DEFINED AND IS NULL`. Unit coverage in `GremlinPredicateAdapterTest.eqNull_mapsToGuardedIsNull`. Negated forms (`neq`, `without`, `not*` text, `NotP`) carry per-leaf `IS DEFINED` guards as required by A2.
