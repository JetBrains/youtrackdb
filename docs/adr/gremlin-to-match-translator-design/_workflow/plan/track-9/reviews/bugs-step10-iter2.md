<!-- MANIFEST
findings: 5   severity: {blocker: 1, should-fix: 2, suggestion: 2}
index:
  - {id: BG29, sev: blocker,     loc: MatchWhereBuilder.java:133, anchor: "### BG29 ", cert: C1, basis: "plan-cache key erases the guard's type-name list, so a later query with a different literal type is served the earlier query's guard and returns wrong rows; measured 5 rows against native's 4"}
  - {id: BG30, sev: should-fix,  loc: UnionForkHostImpl.java:87,  anchor: "### BG30 ", cert: C2, basis: "the union fork's synthesised prefix makes the latch call a union arm's has folded, so a cross-type range in a union arm stays unguarded and diverges; measured 6 translated against 0 native"}
  - {id: BG31, sev: should-fix,  loc: NotStepRecogniserTest.java:433, anchor: "### BG31 ", cert: C3, basis: "the re-pinned between case asserts a discrimination it does not have and its Javadoc states the opposite; the DECLINED assertion it replaced did witness the connective recursion"}
  - {id: BG32, sev: suggestion,  loc: GremlinPredicateAdapter.java:157, anchor: "### BG32 ", cert: C4, basis: "two now-callerless toFilter overloads keep the fail-open default reachable; deleting them makes a later recogniser state its fold position or fail to compile"}
  - {id: BG33, sev: suggestion,  loc: RangeTypeGuardEquivalenceTest.java:449, anchor: "### BG33 ", cert: C5, basis: "the plan-shape test passes with the equality form too, so the IN-form choice is guarded only by the node-type unit assertion"}
evidence_base: {section: "## Evidence base", certs: 13, matches: 8}
cert_index:
  - {id: C1,  verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2,  verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3,  verdict: CONFIRMED, anchor: "#### C3 "}
  - {id: C4,  verdict: CONFIRMED, anchor: "#### C4 "}
  - {id: C5,  verdict: CONFIRMED, anchor: "#### C5 "}
  - {id: C6,  verdict: REFUTED,   anchor: "#### C6 "}
  - {id: C7,  verdict: REFUTED,   anchor: "#### C7 "}
  - {id: C8,  verdict: REFUTED,   anchor: "#### C8 "}
  - {id: C9,  verdict: REFUTED,   anchor: "#### C9 "}
  - {id: C10, verdict: REFUTED,   anchor: "#### C10 "}
  - {id: C11, verdict: REFUTED,   anchor: "#### C11 "}
  - {id: C12, verdict: REFUTED,   anchor: "#### C12 "}
  - {id: C13, verdict: REFUTED,   anchor: "#### C13 "}
flags: [CONTRACT_OK]
-->

## Findings

### BG29 [blocker] The plan-cache key drops the guard's type-name list, so one shape can be served another's guard

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/builder/MatchWhereBuilder.java` (line 133), with
`core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinPlanFingerprint.java` (line 114)

**Issue**: `typeIn` emits the comparability block as inline string literals. `GremlinPlanFingerprint.appendAliasFilters` renders each alias filter through `toGenericStatement`, which collapses every string literal to `?`. The block names are the guard's only content, so two traversals whose blocks have the same number of names produce a byte-identical cache key. The second one to compile is served the first one's plan, complete with the first one's block, and answers a different row set from native.

`STRING` and `BOOLEAN` are both one-name blocks, so they collide. Numeric (7 names) and Date (2 names) differ in placeholder arity and do not.

The exposure is any guarded conjunct that reaches the key only through the `;F:` section: a pattern with no edges, or a guard on the pattern's root alias. `bindPathItemConstraints` copies a non-root alias's filter onto its path item, and `;E:` renders path items verbatim, which is why the post-hop shapes in `RangeTypeGuardEquivalenceTest` do not collide. The shapes that do collide are the ones this step exists to enable: `not(has(k, range))`, a root-level `or(...)` arm, and `barrier().has(k, range)` all produce an edge-free single-node pattern.

**Evidence**: measured, throwaway probe in `core/src/test/.../translator/strategy/`, removed after the run. Five `Types` vertices under an undeclared key `v`: `"alpha"`, `"zulu"`, `true`, `false`, `10`.

```
PROBE-FP not/boolean = P:7:$g2m_v01:V;E:;F:7:$g2m_v029:NOT v.type() IN [?] AND v < ?;N:;R:7:$g2m_v0+7:$g2m_v0;G:;O:;L:;S:;D:false
PROBE-FP not/string  = P:7:$g2m_v01:V;E:;F:7:$g2m_v029:NOT v.type() IN [?] AND v < ?;N:;R:7:$g2m_v0+7:$g2m_v0;G:;O:;L:;S:;D:false
PROBE-FP COLLIDE ? true

PROBE-CACHE native not(lt(true)) = [t_bool_true, t_int_10, t_string_hi, t_string_lo]
PROBE-CACHE native not(lt(m))    = [t_bool_false, t_bool_true, t_int_10, t_string_hi]
PROBE-CACHE run1 not(lt(true))   = [t_bool_true, t_int_10, t_string_hi, t_string_lo] (boundary=1)
PROBE-CACHE run3 not(lt(m))      = [t_bool_false, t_bool_true, t_int_10, t_string_hi, t_string_lo] (boundary=1)
```

Run 3 returns five rows against native's four. The extra row is `t_string_lo`, which is exactly what `NOT (v.type() IN ["BOOLEAN"] AND v < ?)` keeps — the cached plan from run 1. Run the two in the opposite order and the error moves with them. The plan cache is on by default (`STATEMENT_CACHE_SIZE` = 100, `SharedContext.java:147`) and these shapes are cache-eligible, so nothing gates the collision off in production.

The defect is new with this diff. Before it, `not(has(k, range))` declined at the deleted gate and never produced a cached plan, and no shape emitted a `type()` conjunct.

**Refutation considered**: the hop-shape probe first ran the same experiment on `g.V().hasLabel("Hub").out("holds").has("v", …)` and the fingerprints differed, which looked like a refutation. The difference comes from `;E:` rendering the bound path item with `item.toString(NO_PARAMS, …)`, verbatim — an accident of where `bindPathItemConstraints` puts a non-root alias's filter, not a property of the guard. Re-running on an edge-free pattern confirmed the collision. I also checked that the block names are not positional parameters (only the comparison value goes through `bindParam`), and that `SQLBaseExpression.toGenericStatement` really emits `PARAMETER_PLACEHOLDER` for a `string` field (`SQLBaseExpression.java:112`).

**Suggestion**: render alias filters in the fingerprint the way path items are already rendered — `toString(NO_PARAMS, scratch)` instead of `toGenericStatement(scratch)` in `appendAliasFilters`. That keeps the key value-independent (a bound comparison value renders as `null`, visible in the `;E:` output above) while preserving inline literals such as the block names. Check the other inline-literal producers on the way in: in production every comparison value routes through `bindParam`, so `MatchLiteralBuilder.toLiteral` is a unit-test-only path, but `WHERE.classEquals` also emits an inline literal and its effect on cache reuse is worth one look. `GremlinPlanFingerprint`'s own Javadoc already carries the same argument for `limit` / `skip`, so the fix is in the file's existing idiom.

### BG30 [should-fix] The union fork tells the latch a union arm's `has` is folded

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/UnionForkHostImpl.java` (lines 74-87), with `GremlinStepWalker.java` (lines 444-446)

**Issue**: `walkFork` builds a fresh traversal from the recognised prefix plus the child's steps and calls `walk` on it. The prefix starts with the `GraphStep`, so the latch opens on it and a leading `has` in the arm is classified as folded and takes no guard. Natively the arm is a child traversal: `rebuildTraversal` scans only the top level, the arm's `HasStep` survives unfolded, and TinkerPop's comparator answers it. The two arms of the equivalence then disagree.

This contradicts the rule `RecognitionContext.atTraversalStart()` states at line 140 — "a sub-walk answers `false` unconditionally: a child traversal's steps are never visited by `rebuildTraversal`'s top-level scan". `SubTraversalPredicateAdapter` honours that rule for `where` / `and` / `not` children; the union fork routes around it by re-entering `walk` with a synthesised top-level step list.

**Evidence**: measured on the modern graph, same probe class.

```
PROBE A1 g.V().union(has(name, gt(27)))
    boundary=1  translated=[josh, lop, marko, peter, ripple, vadas]  native=[]
PROBE A2 g.V().union(has(name, gt(27)), has(name, eq(marko)))
    boundary=1  translated=[josh, lop, marko, marko, peter, ripple, vadas]  native=[marko]
PROBE A3 g.V().union(hasLabel(Person).has(name, gt(27)), has(name, eq(marko)))
    boundary=1  translated=[josh, marko, marko, peter, vadas]  native=[marko]
PROBE-STEPS A1 :: [YTDBGraphStep(vertex,[]), UnionStep([[HasStep([name.gt(27)]), EndStep]])]
```

The step dump confirms the arm's container stayed a `HasStep` and did not reach `YTDBGraphStep`, so the native side is the unfolded comparator.

The row divergence itself predates this diff — before it no position emitted a guard, so `union(has(name, gt(27)))` answered 6 against 0 then too. What is new is that the mechanism now has a rule for this question and answers it wrongly, in the fail-open direction.

**Refutation considered**: I checked whether TinkerPop applies provider strategies to union children at all, since if it did not the arm might reach the fold by another route. `DefaultTraversal.applyStrategies` recurses into global children, and the step dump above shows the arm's container outside the `YTDBGraphStep`, so the arm is unfolded. I also checked that `subWalk` (the `where` / `and` / `not` path) is unaffected: it strips leading `GraphStep`s and its adapter's `atTraversalStart()` is a hard `false`, and the `or`-arm cases in `RangeTypeGuardEquivalenceTest` pass.

**Suggestion**: give the fork a way to close the latch across the prefix/child seam. The narrowest form is for `walkFork` to hand the child suffix in with the latch already closed — for example a `walk` variant that takes an "everything after index N is child-scoped" boundary, or a marker step the loop treats as fold-closing. Whatever the shape, `atTraversalStart()`'s Javadoc should name the union fork explicitly, because today it reads as though `SubTraversalPredicateAdapter` is the only child path.

### BG31 [should-fix] The re-pinned `between` case claims a discrimination it does not have

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/NotStepRecogniserTest.java` (lines 433-448)

**Issue**: the rewritten Javadoc on `notWithBetweenPredicate_translatesToTheSameRows` says "A guard applied only to leaf predicates at the top would leave both arms unguarded and this case would answer differently from native." On the modern-graph fixture it would not. `age` is an Integer on all four `Person` vertices and absent on both `Software` vertices (`ModernGraphFixture.java:58-63`), so the comparison is same-type on every row that has the key and the guard changes nothing. Guarded and unguarded both return `{vadas, peter, lop, ripple}`, which is native's answer.

The version this replaced asserted `DECLINED`, and that assertion did witness the connective recursion: `P.between(28, 33)` arrives as `AndP[gte, lt]`, so a `predicateHasRangeComparison` that inspected only leaf predicates would have let the shape translate and turned the boundary count from 0 to 1. The flip therefore traded a discriminating assertion for a non-discriminating one while the Javadoc asserts the reverse.

No mechanism is left unguarded — `GremlinPredicateAdapterTest.guardedRange_reachesUnderTheConnectives` covers the recursion at unit level with a direct assertion on the emitted text. The problem is the claim, and a claim like this is what a later reader trusts when deciding whether the recursion is still covered.

Of the three flipped cases, this is the only one that lost anything. `notWithCrossTypeRangeComparison_translatesAndAgreesWithNative` gained discrimination (native's six is pinned, and the guard is what makes the translated arm return six rather than none). `notWithRangeComparisonBehindHop_translatesToTheSameRows` says in its own Javadoc that the multiset equality holds either way and that the boundary step is the discriminating assertion, which is accurate.

**Evidence**: fixture read plus the row derivation above. `NotStepRecogniserTest` is green at 18 tests, so nothing here is failing; the finding is about what the green means.

**Refutation considered**: I checked whether the between case discriminates through some other channel — an absent-property interaction, or the `NotStep` wrapper's own guard. The `NotStep` pure-filter path emits `NOT(inner)` with no `IS DEFINED`, so the two `Software` vertices are kept by the inner being false, guard or no guard. I also checked whether `P.between` might reach `translateCompare` as a leaf rather than an `AndP`; it does not, which is why the recursion matters at all.

**Suggestion**: either move the case onto a fixture where the key holds mixed runtime types (the `Loose` / `Anyp` classes in `RangeTypeGuardEquivalenceTest` already do this) so the claim becomes true, or rewrite the Javadoc to say what the case now establishes — that a negated `between` translates and returns native's rows on a same-type fixture — and point at `guardedRange_reachesUnderTheConnectives` for the recursion.

### BG32 [suggestion] Two callerless overloads keep the fail-open default reachable

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinPredicateAdapter.java` (lines 157 and 167)

**Issue**: the implementer's own note is right that a recogniser added later can emit a range comparison in an unfolded position, forget the fourth argument, and inherit the divergence rather than a compile error. What makes that cheap to close is that the two-argument and three-argument overloads now have no callers at all. Both production sites pass the four-argument form (`HasStepRecogniser.java:156`, `EdgeHopRecogniser.java:121`), and the tests use only the one-argument convenience form and the four-argument form (`GremlinPredicateAdapterTest.java:850-854, 862`).

Deleting the two dead overloads makes the fail-open default unreachable from a `paramSink`-carrying call, which is every production call. A recogniser added later either passes the flag or does not compile. The one-argument test convenience can stay: it takes no `paramSink`, so it cannot be a production path.

**Evidence**: grep over `core/src/main` and `core/src/test` for `toFilter(`. Two production call sites, both four-argument. mcp-steroid PSI was not used (it has timed out in this repository), so this call-site count carries a reference-accuracy caveat: a reflective or generated caller would not appear. Neither is plausible for a package-private method on a package-private class.

**Suggestion**: delete the two-argument and three-argument overloads and update the two Javadoc `{@link …#toFilter(HasContainer, PropertyTypeGate)}` references that point at one of them.

### BG33 [suggestion] The plan-shape test does not separate the `IN` form from the equality form

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RangeTypeGuardEquivalenceTest.java` (line 449)

**Issue**: `guardedAliasAboveTheEstimatorThreshold_doesNotCaptureThePlanRoot` is written as the guard against the `estimateFilterSelectivity` hazard, but it would pass with the equality form too. The measurement it came from records that outcome directly: "The guard did not move the MATCH root … alias `a` carrying a weak indexed range …, then `.type() = 'STRING'`, then `.type() IN ['STRING']` … All four planned `{a}.out("link"){b}` — same root, same direction" (`step15-measurement.md` § Index selection and root choice).

Both of the case's premises hold, so this is about strength rather than correctness. The class carries 604 rows, which clears `SQLWhereClause.estimate`'s early bail (`count = classCount / 2 = 302`, threshold 100, `SQLWhereClause.java:94-97`), and the emitted node really is an `SQLInCondition` that the estimator skips: `estimateSubExpression` returns `-1.0` for anything that is not an `SQLBinaryCondition` or a nested And/Or block (`MatchExecutionPlanner.java:3869-3887`), so the guard contributes nothing to the compound-AND product. Had it been an equality, `estimateSingleConditionSelectivity` would fall past the class-attribute and histogram tiers to `1.0 / divisor` with `divisor = classCount`, which is the one-row-alias score the hazard describes.

**Evidence**: code read of the two estimators plus the measurement's own negative result on the equality form.

**Refutation considered**: I checked whether the compound-AND wrapper changes the answer — the guard is always emitted as `AND(typeIn, comparison)`, so it reaches `estimateCompoundAndSelectivity` rather than the top-level dispatch, and the `-1.0` from the `IN` sub-block is skipped by the `sel >= 0.0` filter with `anyEstimated` left to the comparison. It does not reach the tier-3 default either way.

**Suggestion**: state in the case's Javadoc that the node-type assertion in `MatchWhereBuilderTest.typeIn_buildsAnInConditionOverTheMethodCall` is the guard against re-introducing the equality form, and that this case pins the plan shape rather than the form. Otherwise someone relaxing the node-type assertion will believe this test still has them covered.

## Evidence base

#### C1 The guard's block names survive only in the fingerprint's `;E:` section — CONFIRMED

Measured: identical fingerprints and a five-against-four row divergence for `g.V().not(has("v", lt(true)))` followed by `g.V().not(has("v", lt("m")))`. Backs BG29.

#### C2 The union fork re-enters `walk` with a synthesised prefix and opens the latch — CONFIRMED

Measured: `g.V().union(__.has("name", gt(27)))` returns 6 translated against 0 native with `boundary=1`, and the step dump shows the arm's container outside `YTDBGraphStep`. Backs BG30.

#### C3 The re-pinned `between` case is same-type on its fixture — CONFIRMED

`ModernGraphFixture` stores `age` as an Integer on every vertex that has it, so the guard cannot change the answer and the Javadoc's counterfactual is false. Backs BG31.

#### C4 The two- and three-argument `toFilter` overloads have no remaining callers — CONFIRMED

Grep over main and test sources; both production sites use the four-argument form. Backs BG32.

#### C5 The plan-shape case passes with either node form — CONFIRMED

The measurement it derives from recorded that the equality form also kept the root at the origin on a 604-row fixture. Backs BG33.

#### C6 `EdgeHopRecogniser`'s unconditional `true` could be wrong for some shape that reaches it — REFUTED

CLAIM: `EdgeHopRecogniser.java:121` passes `rangeTypeGuard = true` without reading the latch, so a shape where the edge `has` is folded would be over-guarded and would lose rows the folded comparator returns.

Checked three ways.

- Structural. The recogniser is reached only from `VertexStepRecogniser` when the head is an edge-returning `VertexStep`, and it consumes that head before any `HasStep` (`EdgeHopRecogniser.java:72-100`). `rebuildTraversal`'s `else` branch clears `isTraversalStart` on a `VertexStep` (`YTDBGraphStepStrategy.java:162-164`), so every container in the run that follows is unfolded by construction. A `VertexStep` cannot be the traversal's first step, and in a sub-walk the latch is a hard `false` anyway.
- The narrower question the code comment raises — whether reading the latch there would answer for the `outE` rather than for the `has` — is real: `takeWhile` consumes the whole `has` run at once, so the loop classifies the head only. Passing the constant sidesteps that correctly.
- Row-level. `RangeTypeGuardEquivalenceTest.undeclaredEdgePropertyRange_keepsTranslatingAndKeepsItsRows` and the 36 `EdgeTraversalEquivalenceTest` cases are green.

VERDICT: REFUTED — the constant is correct, and the comment's justification holds.

#### C7 `WherePredicateStepRecogniser.toMatchedLabelFilter` can reach a field-against-literal comparison — REFUTED

CLAIM: the unguarded `toMatchedLabelFilter` path could emit a range comparison against a literal, which would need the guard.

`translateMatchedLabelPredicate`'s leaf branch requires `biPredicate instanceof Compare compare && value instanceof String refLabel`, then resolves `refLabel` through `LabelResolver` to a pattern alias and emits `$matched.<alias>.@rid <cmp> $matched.<alias>.@rid` (`GremlinPredicateAdapter.java:629-639`). A non-String comparand falls through to `return null` and declines the whole traversal; a String that names no pattern node also declines. There is no branch that puts a literal on the right-hand side. The left operand is either the boundary `@rid` or another alias's `@rid` (`leftMatchedOperand`, lines 595-602).

VERDICT: REFUTED — the shape cannot arise, and no comparability block would be nameable if it could, since both operands are RIDs.

#### C8 The latch misses a barrier the cursor swallowed mid-recogniser — REFUTED

CLAIM: `takeIf` and `takeWhile` both call `skipTransparent()` before their class check and leave the position advanced even when they match nothing, so a recogniser could swallow a `NoOpBarrierStep` invisibly and leave the latch open across it.

The mechanism is real but unreachable. The latch is open only immediately after a `GraphStep` or a run of `HasStep`s, so only `StartStepRecogniser` and `HasStepRecogniser` can run while it is open, and both consume exactly one step with a plain `cursor.take()` (`StartStepRecogniser.java:90`, `HasStepRecogniser.java:80`). `EdgeHopRecogniser` is the only recogniser in the package that calls `takeIf` or `takeWhile`, and it always runs with the latch already closed by its own `VertexStep` head.

Measured both directions:

```
PROBE B1 g.V().has(age, eq(29)).barrier().has(name, gt(27))  translated=[] native=[] AGREE
PROBE B2 g.V().barrier().has(name, gt(27))                   translated=[] native=[] AGREE
PROBE-STEPS B1 :: [YTDBGraphStep(vertex,[age.eq(29)]), NoOpBarrierStep(null), HasStep([name.gt(27)])]
```

B1 is the discriminating one: the first `has` folds, the barrier closes the fold, and the second `has` must take the guard. Without the position-comparison fix it would return `[marko]` against native's `[]`.

VERDICT: REFUTED — the `positionBeforePeek` comparison covers every reachable swallow.

#### C9 The latch's rule diverges from `rebuildTraversal` on some other step class — REFUTED

CLAIM: `head instanceof GraphStep || (head instanceof HasStep && atTraversalStart())` might not mirror `rebuildTraversal`'s `isTraversalStart` for some step the walker dispatches.

`rebuildTraversal` sets the flag true on any `GraphStep`, leaves it untouched in the `HasStep` branch, and clears it in the `else` (`YTDBGraphStepStrategy.java:109-164`). The latch expression is that rule term for term. Both use `instanceof`, and the walker's registry dispatches on exact class, so only a plain `HasStep` ever reaches the `HasStep` term. The mid-traversal `GraphStep` restart is mirrored but unreachable: `StartStepRecogniser` declines a second start step once the boundary is pinned, which declines the whole walk.

I also walked the strategy interactions the brief flags. `FilterRankingStrategy` ranks `NoOpBarrierStep` at 0 and so never hoists a `has` past it, while it does hoist a `has` above `dedup`, `order`, `not` and `filter` — but the walker and `rebuildTraversal` both read the post-strategy list, so a hoist moves the container for both. `limit()` before a `has` breaks the fold natively and is latent, because the cardinality gate declines any `has` after a captured slice (`GremlinStepWalker.java:417`).

VERDICT: REFUTED for the top-level walk. The one place the mirror fails is the union fork, reported as BG30.

#### C10 The type guard's identifier disagrees with the comparison's identifier for a dotted key — REFUTED

CLAIM: `propertyMethodCall(field, "type")` builds a bare `SQLIdentifier` while the comparison beside it might build a nested field access, so a key containing a dot would guard a different path than it compares.

Both sides build the same node. `MatchWhereBuilder.fieldExpression` is `new SQLExpression(new SQLIdentifier(name))` (line 566) and `propertyMethodCall` is `new SQLExpression(new SQLIdentifier(propertyKey), modifier)` (`ProjectionExpressionFactories.java:721`). No dot splitting on either side. The injection case is covered by `propertyMethodCall_injectionKeyStaysLiteral`.

VERDICT: REFUTED.

#### C11 The new `SQLInCondition` confuses the planner's static-RID promotion — REFUTED

CLAIM: `promoteStaticRidsFromFilters` reads the alias filter through `SQLWhereClause.findRidInList()`, and the guard adds a second `SQLInCondition` to the same AND block, so the promotion could lift the type-name list into the alias's RID slot.

`tryMatchRidInCondition` accepts an `SQLInCondition` only when `isBareRidExpression(inCond.getLeft())` holds, which requires a record-attribute suffix named `@rid` and `attrBase.getModifier() == null` (`SQLWhereClause.java:1394-1424`). The guard's left side is a plain identifier carrying a method-call modifier, so it fails on both counts.

VERDICT: REFUTED.

#### C12 `SQLMethodCall` built by hand can NPE on its parameter list — REFUTED

CLAIM: `propertyMethodCall` sets only `methodName` on a fresh `SQLMethodCall`, so `toString` / `execute` could dereference a null `params`.

`params` is initialised at its declaration: `protected List<SQLExpression> params = new ArrayList<SQLExpression>();` (`SQLMethodCall.java:37`). The hand-built node also takes the `isEntityPropertyType` fast path in `SQLBaseExpression.execute`, which requires exactly `params.isEmpty()` (`SQLBaseExpression.java:125-134`), so the empty list is what routes it to the intended evaluator.

VERDICT: REFUTED.

#### C13 The additions to the shared MATCH construction surface can change GQL behaviour — REFUTED

CLAIM: `MatchWhereBuilder`, `MatchPatternBuilder` and `ProjectionExpressionFactories` are shared by the GQL and Gremlin front-ends, so a change there reaches GQL.

Every change to those three files in this commit is additive or comment-only. `MatchWhereBuilder` gains `typeIn` and nothing else; `ProjectionExpressionFactories` gains `propertyMethodCall` and nothing else; `MatchPatternBuilder`'s diff is two Javadoc rewrites on `edgeCount` and `appendFrom` with no code change. `typeIn` has one caller (`GremlinPredicateAdapter.translateCompare`) and `propertyMethodCall` has one (`typeIn`), both on the Gremlin path.

VERDICT: REFUTED — no GQL-reachable behaviour changed.

**Method note.** mcp-steroid PSI was not used: `steroid_execute_code` has timed out for every agent on this repository. Every call-site and override claim above rests on grep over `core/src/main` and `core/src/test`, which carries the usual reference-accuracy caveat for polymorphic dispatch and generated callers. All row-level claims are measured, not read: a throwaway probe class under `core/src/test/.../translator/strategy/`, run with `./mvnw -pl core -o test-compile surefire:test`, deleted afterwards; the working tree carries no leftover from this review. The affected suites were run unmodified and are green — `RangeTypeGuardEquivalenceTest` 15, `NotStepRecogniserTest` 18, `GremlinPredicateAdapterTest` 54, `GremlinStepWalkerTest` 70, `MatchWhereBuilderTest` 58, `ProjectionExpressionFactoriesTest` 10, `EdgeTraversalEquivalenceTest` 36, `PredicateTraversalEquivalenceTest` 55, plus the rest of the translator package.
