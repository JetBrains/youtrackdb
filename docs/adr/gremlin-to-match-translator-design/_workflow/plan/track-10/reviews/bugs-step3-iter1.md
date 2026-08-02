<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 1, suggestion: 1}
index:
  - {id: BG5, sev: should-fix, loc: MatchFirstStep.java:146, anchor: "### BG5 ", cert: C1, basis: "root MatchFirstStep publishes a dead sub-plan whenever its alias was also prefetched, so a fetch-counting walk over getSubSteps() double-counts every edge pattern"}
  - {id: BG6, sev: suggestion, loc: MatchFirstStep.java:127, anchor: "### BG6 ", cert: C2, basis: "new Javadoc states a cardinality-to-ownership rule that only holds for edge-free patterns, and a DR is about to be written from it"}
evidence_base: {section: "## Evidence base", certs: 12, matches: 6}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: CAVEATED, anchor: "#### C3 "}
  - {id: C4, verdict: CONFIRMED, anchor: "#### C4 "}
  - {id: C5, verdict: CONFIRMED, anchor: "#### C5 "}
  - {id: C6, verdict: CONFIRMED, anchor: "#### C6 "}
  - {id: C7, verdict: CONFIRMED, anchor: "#### C7 "}
  - {id: C8, verdict: REFUTED, anchor: "#### C8 "}
  - {id: C9, verdict: REFUTED, anchor: "#### C9 "}
  - {id: C10, verdict: REFUTED, anchor: "#### C10 "}
  - {id: C11, verdict: REFUTED, anchor: "#### C11 "}
  - {id: C12, verdict: NOTE, anchor: "#### C12 "}
flags: [CONTRACT_OK]
-->

## Findings

### BG5 [should-fix] MatchFirstStep publishes a sub-plan that never runs when its alias was prefetched

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchFirstStep.java` (lines 143-147)

**Issue**: For a MATCH pattern with at least one edge, the planner builds the root
`MatchFirstStep` with a live sub-plan whether or not the same alias is already prefetched.
That sub-plan is dead at runtime: `internalStart` finds the prefetch cache variable and
never calls `executionPlan.start()`. The new `getSubSteps()` publishes it anyway, so one
plan exposes the alias's fetch twice — once under `MatchPrefetchStep`, once under a step
that will not execute it. A caller that counts fetches by walking `getSubSteps()`, which is
the use case the override's own Javadoc names, reports two index fetches for a query that
performs one.

**Evidence**: the path, in order.

- `MatchExecutionPlanner.java:601-607` — `aliasesToPrefetch` collects every alias estimated
  under `THRESHOLD` (100, line 347) with no `$matched` dependency. Nothing removes the alias
  the scheduler will pick as root. `getTopologicalSortedSchedule` picks the cheapest alias,
  so the root is the alias most likely to fall under the threshold.
- `MatchExecutionPlanner.java:4737-4756` (`addPrefetchSteps`) chains a `MatchPrefetchStep`
  for each of those aliases, each owning a sub-plan built by `createSelectStatement`.
- `MatchExecutionPlanner.java:4635-4657` (`addStepsFor`, `first == true`) then builds
  `new MatchFirstStep(context, patternNode, select.createExecutionPlan(subContxt, …), …)`
  with no `prefetchedAliases` check. `prefetchedAliases` reaches
  `createPlanForPattern` (line 1988) but is read only at line 2088, the edge-free branch.
- `MatchFirstStep.java:100-108` — when `ctx.getVariable(PREFETCHED_MATCH_ALIAS_PREFIX + alias)`
  is non-null the step streams the cached list and the sub-plan is never started. The variable
  is always set by then, because `internalStart` drains `prev` first (lines 92-95) and the
  prefetch steps sit ahead of the pattern steps in the same chain
  (`MatchExecutionPlanner.java:630-651`).
- The plan shape is already pinned in the tree.
  `MatchStaticRidPromotionIntegrationTest.dualStaticRidInWhere_bothPromoted_plannerTieBreakDecidesRoot`
  asserts against one EXPLAIN output both that alias `p` has a `+ PREFETCH p` block containing
  `FETCH FROM RIDs` (lines 666-671) and that `p` is the `+ SET` root alias (lines 677-678).
  That plan holds two RID fetches for `p`; after this change `getSubSteps()` reaches both.

Concrete failure: run
`MATCH {class: Person, as: p, where: (name = 'alice')}.out('Knows'){class: Person, as: m} RETURN p.name`
with an index on `Person.name` and Person under 100 rows. The `indexUsages` walker in
`core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/CommandExecutorSQLSelectTest.java:1963-1979`
(an `int` accumulator that recurses through `getSubSteps()`) returns 2 while the query performs
one index fetch. `BaseDBJUnit5Test.indexesUsed`, which accumulates a `Set<String>` of index
names, and boolean scans such as the Gremlin suite's `containsStepOfType` stay correct.

**Refutation considered**:

- Does another construction site guard the prefetched case? Checked all four production sites
  for `MatchFirstStep` — `buildNotPatternPlan` (1824), `buildHashJoinBranchPlan` (1874),
  `createPlanForPattern` (2089 and 2096) and `addStepsFor` (4652). Only 2089 uses the
  no-sub-plan constructor, and only for an isolated node with no edges. The hash-join and
  NOT-pattern builders have the same gap one level down, inside
  `HashJoinMatchStep.getSubSteps()` and `FilterNotMatchPatternStep.getSubSteps()`.
- Is the duplication new? No. `MatchFirstStep.prettyPrint` (lines 158-163) has always inlined
  the dead sub-plan, so `executionPlanAsString` already showed it. The change carries the same
  inaccuracy into the structured document and into every recursive `getSubSteps()` walk, which
  is the surface the override exists to serve — hence should-fix rather than blocker.
- Is a production consumer counting today? No. `ExplainResultSet.java:56` is the only
  production caller of plan `toResult`, and the one production index-usage scan,
  `YTDBGraphQuery.usedIndexes`, never touches `getSubSteps()` (see C3). Live impact is limited
  to EXPLAIN document fidelity plus any counting consumer added later.

**Suggestion**: have `addStepsFor` pass the no-sub-plan constructor when
`prefetchedAliases.contains(patternNode.alias)`, matching what line 2089 already does for the
edge-free shape; that also drops a dead plan object per query and makes `prettyPrint` honest.
If the sub-plan has to stay as a runtime fallback for the case where the prefetch step did not
run, then record why and have `getSubSteps()` report an empty list for a step the planner knows
is prefetch-fed. Either way add a test over an edge pattern whose root alias is prefetched —
the four new sub-step tests all use the edge-free shape, where the planner already nulls the
sub-plan, so this path is untested.

### BG6 [suggestion] The new Javadoc states a prefetch-ownership rule that only holds for edge-free patterns

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchFirstStep.java` (line 127, also 135-137)

**Issue**: The override's Javadoc says `getSubSteps()` returns "an empty list when the alias was
prefetched and this step carries no sub-plan", and that "Below `MatchExecutionPlanner.THRESHOLD`
records the alias is prefetched and the fetch lives under `MatchPrefetchStep` instead; above the
threshold it lives here." Both sentences hold only for an edge-free single-node pattern. With an
edge the alias can be prefetched and the root step still carries a sub-plan, so the fetch lives
in both places at once (C1). A reader who takes the rule as written concludes that a
below-threshold MATCH plan exposes exactly one fetch per alias through `getSubSteps()`, which is
the premise the Decision Record for this step is about to be written from.

**Evidence**: the code trace in C1 shows the rule failing for any pattern with an edge. The same
rule is restated three more times in the step's own material, so a correction has to reach all
of them: `MatchStatementExecutionTest.java:2325` and `:2348` (test Javadoc) and
`MatchStepUnitTest.java:2109-2111` (the section comment above the new tests). `MatchPrefetchStep.java:111-125`
carries the mirror text without the false clause, so it needs no change.

**Refutation considered**: could "below THRESHOLD" be read as shorthand for the isolated-node
case? The sentences generalise over cardinality with no mention of pattern shape, and the
`MatchStepUnitTest` comment states it as a general rule ("Which of the two holds the fetch
depends on cardinality"). Nothing in the three comment blocks scopes the claim to edge-free
patterns, and a reader has no local signal that it is scoped.

**Suggestion**: qualify the rule to what the planner actually does — the empty list appears when
the planner built the step without a sub-plan, which happens for a prefetched alias in an
edge-free pattern; with edges the root step keeps a sub-plan even for a prefetched alias, and the
fetch is then reachable under both steps. Carry the same qualification into the DR so the
"only observable change is EXPLAIN documents" conclusion is not read as "and each fetch appears
once".

## Evidence base

#### C1 Dead root sub-plan is published for a prefetched alias — CONFIRMED

`addStepsFor` (`MatchExecutionPlanner.java:4635-4657`) builds the root `MatchFirstStep` with a
sub-plan unconditionally; only the edge-free branch at line 2088-2089 consults
`prefetchedAliases`. `MatchStaticRidPromotionIntegrationTest.dualStaticRidInWhere_bothPromoted_plannerTieBreakDecidesRoot`
pins a single plan where alias `p` is both prefetched and the `+ SET` root. Basis for BG5.

#### C2 Javadoc ownership rule is false for edge patterns — CONFIRMED

The `MatchFirstStep.getSubSteps()` Javadoc (lines 126-137) asserts an empty list for a prefetched
alias and single-owner semantics keyed on cardinality; C1 shows both fail for any pattern with an
edge. Basis for BG6.

#### C3 Implementer claim 1 — production callers of `getSubSteps()` — CAVEATED

Verified by grep, since `steroid_execute_code` is known to exceed the MCP timeout in this
repository (cold kotlinc); `steroid_list_projects` reported the project open and matching the
working tree, but no PSI query was run. **(grep-only reference-accuracy caveat applies to this
cert and to every claim resting on it, including BG5's blast-radius argument.)**

The claim holds as stated. Production references to `getSubSteps()` are the interface declaration
(`ExecutionStep.java:23`), the `toResult` default (`ExecutionStep.java:41,44`), the
`ExecutionStepInternal` default (line 145), `basicSerialize` (lines 197-199), `basicDeserialize`
(line 230), and the overrides themselves. `ExplainResultSet.java:56` is the only production caller
of plan `toResult`, so EXPLAIN result documents are indeed the only observable production surface.
Neither changed step implements `serialize` or `deserialize`, so both inherit the throwing defaults
(`ExecutionStepInternal.java:172-186`) and the serialization path is unreachable; `basicDeserialize`
additionally needs a no-arg constructor via `Class.forName(...).newInstance()`, which neither step
has.

One correction to the surrounding reasoning, not to the claim. The Javadoc added by this step
motivates the override with "index-usage scans that ask whether a plan fetches from an index."
The only *production* index-usage scan is `YTDBGraphQuery.usedIndexes`
(`core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/YTDBGraphQuery.java:36-64`),
and it walks top-level `getSteps()` plus `GlobalLetQueryStep.getSubExecutionPlans()` only, never
`getSubSteps()`. It therefore still returns 0 for a MATCH plan after this change. The scans the
Javadoc describes are the test helpers, not that API. Worth stating plainly in the DR so a later
reader does not assume `usedIndexes` was fixed here.

#### C4 Implementer claim 2 — `getSubExecutionPlans()` must stay empty — CONFIRMED

`CommandExecutorSQLSelectTest.indexUsages` (`:1963-1979`) accumulates an `int` and recurses
through both `getSubSteps()` and `getSubExecutionPlans()`. `BaseDBJUnit5Test.indexesUsed`
(`tests/src/test/java/com/jetbrains/youtrackdb/junit/BaseDBJUnit5Test.java:594-623`) accumulates a
`Set<String>` of index names over the same two accessors. Publishing the nested plan through both
accessors would therefore double-count in the first helper and be absorbed by the second, exactly
as claimed.

Two notes for the DR. First, neither helper is applied to a MATCH query today — every call site in
`SQLSelectIndexReuseTest`, `SQLSelectByLinkedSchemaPropertyIndexReuseTest` and
`CommandExecutorSQLSelectTest` uses SELECT — so the risk this decision guards against is latent.
Second, the double-count the decision guards against is symmetrical with the one BG5 describes,
which arrives through `getSubSteps()` alone across two steps and is not guarded.

#### C5 Implementer claim 3 — `SelectExecutionPlan.getSteps()` hands out the live list — CONFIRMED

`SelectExecutionPlan.java:138-140` returns `(List) steps`, an unchecked cast of the
`protected List<ExecutionStepInternal> steps` field declared at line 54 and mutated by `chain`
(line 126), `setSteps` (line 165) and `replaceFirst` (line 162). Both new overrides snapshot with
`List.copyOf`, which is the right call; `HashJoinMatchStep.java:411` already does the same.
`FilterNotMatchPatternStep.java:112` returns its live `subSteps` list through a raw cast, so the
`MatchPrefetchStep` Javadoc's "expose it the same way" is true about exposure and loose about
snapshotting.

#### C6 Implementer claim 4 — null-guard asymmetry is safe — CONFIRMED

Safe, though for a narrower reason than the claim gives. `MatchPrefetchStep` has one production
construction site, `MatchExecutionPlanner.addPrefetchSteps` (line 4746), which always passes a
freshly created plan. The only other site is `copy()` (line 143-151), which propagates null only
from an already-null field, so the null branches in `copy()` and `canBeCached()` (line 108) are
unreachable and the unconditional dereference in `getSubSteps()` adds no new failure path. The
constructor assert is not what makes this safe, since asserts are off in production.

The `MatchFirstStep` guard is genuinely load-bearing: `MatchExecutionPlanner.java:2089` passes the
three-argument constructor, which sets `executionPlan` to null.

Checked every construction path, not only the tested ones: 1824, 1874, 2089, 2096, 4652 for
`MatchFirstStep`; 4746 and `copy()` for `MatchPrefetchStep`.

#### C7 Phase A's five-sibling claim versus the implementer's correction — CONFIRMED (correction is right)

`BackRefHashJoinStep.java:839-843`, `CorrelatedOptionalHashJoinStep.java:209-213` and
`InvertedWhileHashJoinStep.java:336-340` each `return List.of()`, and each renders a single
`prettyPrint` line with no nested plan, so none owns nested execution content that the empty
return would hide. `HashJoinMatchStep.java:409-413` returns `List.copyOf(buildPlan.getSteps())`
and `FilterNotMatchPatternStep.java:110-115` returns `(List) subSteps`; those two are the ones
that expose nested content. The implementer's refutation of the Phase A claim stands.
(grep-only, per C3.)

#### C8 NPE in `MatchPrefetchStep.getSubSteps()` via a null `prefetchExecutionPlan` — REFUTED

Hypothesis: `copy()` and `canBeCached()` both tolerate a null `prefetchExecutionPlan`, so the new
unconditional `prefetchExecutionPlan.getSteps()` at line 129 could throw where the class expected
null to be survivable.

Checked producers: the only site that can introduce a null is `copy()` itself, and it does so only
when the source field is already null, which requires a null to have entered through the public
constructor. The one production constructor call
(`MatchExecutionPlanner.addPrefetchSteps`, line 4746) passes
`prefetchStm.createExecutionPlan(context, profilingEnabled)`, which builds and returns a plan.
Checked exposure: `getSubSteps()` fires from `ExecutionStep.toResult`, reached only through
`ExplainResultSet.next()`, which also calls `prettyPrint` on the same plan — and `prettyPrint`
(line 139) dereferences the same field unconditionally. So the new call widens nothing even if a
null ever arrived.

VERDICT: refuted as a bug. The five-member null-tolerance asymmetry inside one class is a
readability matter for `review-code-quality`, not a reachable defect.

#### C9 `List.copyOf` throwing on a null steps list or a null element — REFUTED

Hypothesis: `SelectExecutionPlan.serialize` (line 213) and `toResult` (line 187) both guard with
`steps == null ? null : …`, so the class itself believes the field can be null; `List.copyOf(null)`
would then throw NPE inside the new accessors.

Checked producers: `steps` is initialised to `new ArrayList<>()` at line 54, and `setSteps`
(line 165) dereferences its argument on the next line, so it cannot leave the field null. `chain`
appends non-null steps. The two null guards are unreachable defensive code of the same kind as C8.
`List.copyOf` also rejects null elements, and no path adds one.

VERDICT: refuted.

#### C10 Immutable snapshot breaking `basicDeserialize`'s `getSubSteps().add(...)` — REFUTED

Hypothesis: `ExecutionStepInternal.basicDeserialize` mutates the accessor's return value
(`step.getSubSteps().add(subStep)`, line 230). Returning `List.copyOf(...)` makes that throw
`UnsupportedOperationException` where the previous `Collections.emptyList()` default did too, but
where a future `deserialize` implementation would silently break.

Checked reachability: `basicDeserialize` is a static helper invoked from a concrete step's
`deserialize`. Neither changed step overrides `deserialize`, so the throwing default
(`ExecutionStepInternal.java:184-186`) fires first. The reconstruction path also requires a no-arg
constructor for `Class.forName(className).newInstance()`; neither step has one. `SelectExecutionPlan.serialize`
(line 213) would likewise hit `MatchPrefetchStep.serialize`'s throwing default before reaching
`basicSerialize`, which is pre-existing behaviour this change does not alter.

VERDICT: refuted for the current tree. Worth one line in the DR as a constraint on any future
serializable MATCH step, since the mutable-return contract is what `basicDeserialize` assumes.

#### C11 Existing index-usage assertions regressing on the wider `getSubSteps()` — REFUTED

Hypothesis: the two new overrides make MATCH plans expose nested fetch steps for the first time, so
any existing test asserting an exact index count or the absence of an index step could flip.

Checked every consumer that recurses through `getSubSteps()`: `CommandExecutorSQLSelectTest`
(SELECT queries only), `SQLSelectIndexReuseTest` and `SQLSelectByLinkedSchemaPropertyIndexReuseTest`
via `BaseDBJUnit5Test.indexesUsed` (SELECT only), `IndexTest.assertIndexUsage` (presence only,
line 2403-2415), `YTDBQueryMetricsStrategyTest.containsStepOfType` (boolean). None asserts an exact
count over a MATCH plan. The two Gremlin scenarios that do walk a MATCH plan assert presence and
absence, and the commit message records that both had been red since 6e657ce2b1 and are repaired
by this change, which matches the code: before the override, `containsStepOfType` could reach
neither the class fetch nor the index fetch inside a prefetch sub-plan.

VERDICT: refuted.

#### C12 Concurrency triage gap here — NOTE

`review-concurrency` was not triaged onto this step, and the diff hands out snapshots of
`SelectExecutionPlan`'s live, cached-and-shared step list to an introspection caller — flagging
only that the triage may be needed, with no interleaving analysis performed or implied.
