<!-- MANIFEST
findings: 5   severity: {blocker: 1, should-fix: 3, suggestion: 1}
index:
  - {id: T29, sev: blocker,    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/WalkerContext.java:411, anchor: "### T29 ", cert: C49, basis: "every translator addNode passes a null WHERE, so MatchPatternBuilder.aliasFilters is empty on the translated path and both builder-side options in item 2 bind nothing"}
  - {id: T30, sev: should-fix, loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/builder/MatchPatternBuilder.java:381, anchor: "### T30 ", cert: C50, basis: "mergedTargetFilter reads aliasClasses only in the branch no additive-path item ever takes, so reusing it produces the WHERE-only fix item 2 forbids"}
  - {id: T31, sev: should-fix, loc: docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:108, anchor: "### T31 ", cert: C51, basis: "none of item 2's three fix sites changes GQL's IR, so the stated reason for the prettyPrint pin and for keeping GqlMatchPatternAssembler in scope does not hold"}
  - {id: T32, sev: should-fix, loc: docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:83, anchor: "### T32 ", cert: C53, basis: "the escalation branch restates the Track 11 dependency in the two places that are already shape-agnostic and skips plan/track-11.md, which asserts a completing suite three times"}
  - {id: T33, sev: suggestion, loc: docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:132, anchor: "### T33 ", cert: C54, basis: "Signatures names EdgeHopRecogniser.putEdgeFilter; the method is declared on RecognitionContext and implemented on WalkerContext and SubTraversalPredicateAdapter"}
verdicts:
  - {id: T19, verdict: VERIFIED}
  - {id: T20, verdict: REGRESSION}
  - {id: T21, verdict: REGRESSION}
  - {id: T22, verdict: VERIFIED}
  - {id: T23, verdict: VERIFIED}
  - {id: T24, verdict: REGRESSION}
  - {id: T25, verdict: VERIFIED}
  - {id: T26, verdict: VERIFIED}
  - {id: T27, verdict: VERIFIED}
  - {id: T28, verdict: VERIFIED}
overall: FAIL
evidence_base: {section: "## Evidence base", certs: 6, matches: 1}
cert_index:
  - {id: C49, verdict: WRONG, anchor: "#### C49 "}
  - {id: C50, verdict: WRONG, anchor: "#### C50 "}
  - {id: C51, verdict: MISMATCHES, anchor: "#### C51 "}
  - {id: C52, verdict: CONFIRMED, anchor: "#### C52 "}
  - {id: C53, verdict: PARTIAL, anchor: "#### C53 "}
  - {id: C54, verdict: WRONG, anchor: "#### C54 "}
flags: [CONTRACT_OK]
-->

# Track 9 technical gate verification — iteration 5

Seven of the ten fixes land clean. Three — T20, T21 and T24 — replaced a wrong statement with a differently-wrong one, and the shared root is that item 2 now specifies the fix against `MatchPatternBuilder`'s `aliasFilters` map, which is populated by GQL and empty on the translated path. Every translator `addNode` passes a null `WHERE`; the post-hop `has()` predicate lands in `WalkerContext.aliasFilters` and is merged into the plan inputs *after* `patternBuilder.build()` has already returned. Both builder-side options in item 2 therefore read an empty map and bind nothing, and the method the track names as the reference merge never binds a class on any item this fix will touch. The line numbers, the surefire descriptor claim, the RID reasoning and the escalation budget all re-verify exactly.

**Tooling caveat.** `steroid_execute_code` timed out again on this repository (minimal `findClass` probe, 90 s). `steroid_list_projects` reports the IDE open on this working tree, so the failure is the Kotlin script compiler, not the connection. Every symbol result below is `grep` / `sed` / `unzip` plus an end-to-end read of the declaring file. The negatives that carry weight — "no translator caller passes a non-null `where` to `addNode`", "GQL never reaches `addEdge`, `addEdgeAsNode` or `buildNotExpression`" — were each established by reading every call site the grep returned, which bounds the usual grep risk without eliminating it. Re-verify through PSI at decomposition if the IDE recovers.

## Verification certificates

#### Verify T19: the `Running GQL Match Support` line marks no stall position
- **Original issue**: the track read a surefire report header as per-feature progress and derived a narrowed first diagnostic from it, while the translator-off control on disk prints the same line and finishes.
- **Fix applied**: `## Context and Orientation` line 42 replaced the "Both observed stalls stop at the same feature" paragraph. Line 44 states the per-directory identity. Item 1 (line 79) names bisect-by-fork-content plus `jcmd Thread.print` and a heap histogram as the first diagnostic.
- **Re-check**:
  - Location: `plan/track-9.md:42,44,79`.
  - Current state: line 42 states the JUnit47 provider emits one `Running <name>` per test class, that cucumber-junit exposes the suite as one class, that the control prints the identical line at the same position, and that neither stall has been located. It also records the local-features-only translator-on run at 42 scenarios / 0 failures / 5.4 s and concludes `GQL Match Support` under the translator is not the stall. Line 44 carries 1888 + 42 = 1930 with 14 skips (6 `map`, 8 `sideEffect`) and states the failure is a property of one JVM. Item 1's first diagnostic is `-Dcucumber.features=` bisection by fork content with a thread dump and heap histogram captured before the kill.
  - Criteria met: the refuted narrowing is gone, the replacement claim is the one the evidence supports, and the named diagnostic discriminates deadlock from leak.
- **Regression check**: checked whether any surviving sentence still reads the line positionally. Line 40 says the Track 10 run "reaches `[INFO] Running GQL Match Support` and dies there after 31:35", and line 37's table lists "no per-feature progress output" among the killed run's symptoms. Line 42 immediately follows with the explicit correction and its `(T19)` tag, so a sequential reader is not misled, but both phrasings would read better as "prints one report header and stops" and as a plain statement that no per-feature output exists in either arm. Cosmetic; not a finding.
- **Verdict**: VERIFIED

#### Verify T20: the binding must be a merge, not a rebind
- **Original issue**: item 2's named preferred option rebound over the `Pattern`'s edge items with `rebindFilters`' overwrite semantics, which nulls the edge `WHERE` that lives only on the edge path item, and bound no class.
- **Fix applied**: item 2 gained the "The binding is a merge, not a rebind (T20)" paragraph (line 86). `## Validation and Acceptance` split `hasLabel(software)` onto the class-binding side (line 106) and added an edge-alias preservation criterion (line 107).
- **Re-check**:
  - Location: `plan/track-9.md:86,106,107`.
  - Current state: the paragraph states the three requirements — bind class and `WHERE`, AND-compose with whatever the item carries, skip aliases with no `aliasFilters` entry — and the edge-`WHERE` hazard. Each of its factual premises re-verifies: `SQLMatchFilter.setFilter` walks `items`, finds the first with a non-null `filter` and assigns the argument, so `setFilter(null)` clears (`SQLMatchFilter.java:88-101`); `MatchEdgePathItems.edgeMethodItem` puts the edge `WHERE` on the edge alias's filter (`:64-66`); `WalkerContext.edgeFilters` carries the javadoc "for observability" (`:55-57`) and `GremlinStepWalker.buildResult` never reads it (`:470-476`); `rebindFilters` contains no `setClassName` (`:6012-6022`). Acceptance criterion 4 now names `hasLabel(software)` separately on the class-binding side, and criterion 5 pins `g.V().outE(knows).has(weight, 0.5).inV()` before and after.
  - Criteria met: the merge-not-overwrite requirement, the class-binding requirement and the edge-alias criterion are all stated and all correct.
- **Regression check**: the paragraph's closing sentence — "`MatchPatternBuilder.mergedTargetFilter` (`:377-402`) already has that shape — it reads `aliasClasses` as well as `aliasFilters`, copies rather than replaces, and AND-merges through `mergeWhere`" — is new and is wrong in both halves for the path this fix targets. The `aliasFilters` it reads is the builder's own map, which no translator caller ever populates (C49), and the `aliasClasses` read sits in the branch no additive-path item ever takes (C50). An implementer told the reference implementation already has the shape will reuse it and ship a fix that binds nothing.
- **Verdict**: REGRESSION — see T29 and T30

#### Verify T21: GQL cannot witness the fix
- **Original issue**: the GQL acceptance criterion and the "GQL non-root-alias witness" deliverable could not be produced, because GQL builds node-only patterns and has no non-root alias to filter.
- **Fix applied**: a new `## Context and Orientation` paragraph (line 58) states GQL does not exhibit the defect. The acceptance criterion (line 108) was rewritten as a `prettyPrint` regression criterion. "A GQL non-root-alias witness" was dropped from "In scope (new)" (line 128).
- **Re-check**:
  - Location: `plan/track-9.md:58,108,128,129`.
  - Current state: line 58 reproduces exactly — `GqlMatchVisitor extends GQLBaseVisitor<Void>` overriding only `visitNode_pattern` (`:50`), `GqlMatchPatternAssembler.add` calling only `builder.addNode(` (`:39`), no `addEdge` / `addEdgeAsNode` in any GQL file, disconnected components split and Cartesian-producted by the planner. The Cartesian-product aside is recorded as a pre-existing gap and appears in "Out of scope". Line 128 no longer lists a GQL witness. Line 108 is a regression criterion naming `GqlMatchStatementPlanPrettyPrintTest` (file exists at `core/src/test/.../gql/parser/`) and `GqlMatchStatementTest`.
  - Criteria met: the unproducible criterion and the unproducible deliverable are both gone.
- **Regression check**: the replacement rationale is a new false claim. Line 108 states "A builder-side fix still changes the IR GQL produces, which is why the pin matters", and line 58 says the same. None of item 2's three enumerated sites changes GQL's IR: GQL never calls `addEdge` or `addEdgeAsNode`, its `Pattern` has zero edge items for a `build()`-level merge to walk, and `mergedTargetFilter` is reachable only from `buildNotExpression`, which GQL never calls (C51). The criterion is cheap to keep, but its stated reason is wrong and it is the sole justification for the `GqlMatchPatternAssembler` line in "In scope (modified)".
- **Verdict**: REGRESSION — see T31

#### Verify T22: pinned commands compile
- **Original issue**: `mvn surefire:test@<id>` runs no lifecycle phase, so every pinned gate measured the previously compiled classes and items 3 and 4 would have published two copies of the pre-fix number.
- **Fix applied**: `test-compile` was inserted before the surefire goal at the first acceptance criterion (line 103) and the pinned-commands criterion (line 114); items 3 and 4 carry the rule (lines 93, 94); a fourth `## Decision Log` entry requires a published number to record its SHA and come from an invocation that compiled (line 26).
- **Re-check**:
  - Location: `plan/track-9.md:26,93,94,103,114`; `maven-surefire-plugin-3.5.6.jar!META-INF/maven/plugin.xml`.
  - Current state: I re-extracted the `test` mojo descriptor from the 3.5.3, 3.5.5 and 3.5.6 jars. All three carry `<phase>test</phase>` and `<requiresDependencyResolution>test</requiresDependencyResolution>` and none carries `executePhase`, `executeGoal` or `executeLifecycle` (C52). A direct goal invocation runs the mojo alone, so the `test-compile` prefix is required and sufficient — invoking the phase runs the default lifecycle through `test-compile`, which is strictly before the `test` phase the compliance executions bind to, so the prefix adds compilation without triggering the other four surefire executions.
  - Criteria met: every command the track pins as a gate or an iteration loop now compiles.
- **Regression check**: I grepped both files for every `mvnw` and `surefire:test` occurrence. Three bare-goal mentions survive and each is correct as written — line 26 is the Decision Log's negative example, line 37 is a historical record of a run already taken, line 75 and line 104 use `test` and `install`, which are phases. Two residues worth a sentence each. First, the 1930-scenario figure on line 37 that acceptance criterion 1 compares against was itself measured with the bare goal, so it is a published baseline the track's own new Decision Log rule would reject; item 1 re-takes it, but criterion 1 reads as if 1930 were authoritative. Second, `plan/track-11.md:60` still pins the bare `surefire:test@gremlin-feature-compliance-tests` for its iteration loop, and the Decision Log entry that bans it lives only in Track 9's file — Track 11's own Phase A panel owns that.
- **Verdict**: VERIFIED

#### Verify T23: `rebindFilters`' reachability and the same-named SQL method
- **Original issue**: the blast-radius sentence called both `rebindFilters` call sites shared, and did not mention that `SQLMatchStatement` declares its own method of the same name.
- **Fix applied**: line 54 carries both corrections; "Signatures" (line 132) and "Out of scope" (line 130) reflect them.
- **Re-check**:
  - Location: `plan/track-9.md:54,130,132`; `MatchExecutionPlanner.java:2064,5609-5621,5677,6012`; `SQLMatchStatement.java:226,232`.
  - Current state: `rebindFilters(aliasFilters)` is at `:2064` inside `createPlanForPattern` and at `:5677` as the last statement of `buildPatterns`; the declaration is at `:6012`. `buildPatterns` opens `if (this.pattern != null) { … return; }` at `:5609-5621`, with a comment naming the additive path explicitly. `SQLMatchStatement.rebindFilters(Map<String, SQLWhereClause>)` is declared at `:232` and called at `:226`, walking the statement's own `matchExpressions`. All four numbers and both characterisations match the track.
  - Criteria met: the shared-surface claim is now accurate and the name collision is disclosed in the two places an implementer looks.
- **Regression check**: checked that "Out of scope" and "Signatures" agree with line 54 and with each other. They do — `:5677` and `SQLMatchStatement.rebindFilters` (`:232`) are named out of scope, and `:2064` is named as the shared site in both the Signatures list and item 2's fix-site enumeration. Clean.
- **Verdict**: VERIFIED

#### Verify T24: both positive-path-item construction sites
- **Original issue**: the mechanism paragraph named only `MatchPatternBuilder.addEdge`'s `toFilter`; `addEdgeAsNode` builds its target-vertex item through `MatchEdgePathItems.vertexMethodItem`, a second site in a different package, and `EdgeHopRecogniser` routes the whole edge-as-node family through it.
- **Fix applied**: the mechanism paragraph (line 48) names both sites; item 2 gained "Cover both construction sites, or bind above them (T24)" (line 88); "In scope (modified)" (line 129) enumerates three fix-site options.
- **Re-check**:
  - Location: `plan/track-9.md:48,88,129`; `MatchPatternBuilder.java:148,199-222`; `MatchEdgePathItems.java:84`.
  - Current state: `addEdge` builds `toFilter = SQLMatchFilter.fromAliasAndClass(toAlias, null)` at `:148`; `vertexMethodItem` does `item.setFilter(SQLMatchFilter.fromAliasAndClass(targetAlias, null))` at `:84`. `GremlinPatternAssembler.appendEdgeAsNode` (`:88-101`) routes `EdgeHopRecogniser` through `ctx.addEdgeAsNode`, which calls both `MatchEdgePathItems` factories. Both sites and the routing claim are correct, and `## Interfaces and Dependencies` matches item 2.
  - Criteria met: the two-site enumeration landed everywhere the original finding asked for it.
- **Regression check**: the newly offered alternative — "specify the merge in `MatchPatternBuilder.build()` over the assembled `Pattern`'s edge items, which makes the site count stop mattering" — cannot work as written. `build()` is a no-arg method whose only filter source is the builder's own `aliasFilters` field, which is empty on the translated path, and `GremlinStepWalker.buildResult` merges `ctx.aliasFilters` in only after `build()` has returned (C49). The option that was supposed to make the site count stop mattering makes the binding stop happening. The same defect sinks the enumerate-both-sites option, since neither construction site has the alias's `WHERE` available at construction time either.
- **Verdict**: REGRESSION — see T29

#### Verify T25: the `NOT IN` question is a SQL-`MATCH` concern
- **Original issue**: the criterion was written as if the post-optimization `NOT IN` strip applied to the translated path, and named no witness test.
- **Fix applied**: item 2's question was narrowed to the SQL path (line 90); the acceptance criterion was restated as a SQL-`MATCH` no-regression clause naming `HashJoinPlannerIntegrationTest` and, planner-side only, `MatchStatementExecutionTest` (line 109).
- **Re-check**:
  - Location: `plan/track-9.md:90,109`; `HashJoinPlannerIntegrationTest.java:1197,2505`.
  - Current state: line 90 states the strip fires inside `createPlanForPattern`'s optimize stage, that its push-back rides the `:2064` rebind over an empty `matchExpressions` on both additive paths, and that `GremlinPredicateAdapter.without` emits a value-list form the detector does not match. The remaining obligation is stated as "does the chosen site change what `:2064` pushes on the SQL path". Line 109 names `HashJoinPlannerIntegrationTest` unconditionally; both cited lines are javadoc in anti-join regression tests that reference `detectNotInAntiJoin` by name, as claimed.
  - Criteria met: scope corrected and both witnesses named.
- **Regression check**: line 109 and line 110 both require `MatchStatementExecutionTest` under the planner-side option. The two are consistent — line 110 adds the ~32-minute gate-cost booking — so the overlap is redundancy, not contradiction. Clean.
- **Verdict**: VERIFIED

#### Verify T26: the ESCALATE trigger has a boundary and a branch
- **Original issue**: the trigger defined its exit condition in terms of the thing being decided, and the branch it guarded had no other side — items 3 and 4 and the first two acceptance criteria all assumed item 1 succeeded.
- **Fix applied**: item 1 gained an evidence precondition and a budget (line 81) and an explicit escalation branch (line 83). Acceptance criteria 1 and 2 carry the relaxation (lines 103, 104). The Track 11 dependency line carries the conditional restatement (line 131). The plan file's Track 9 entry was rewritten to match (`implementation-plan.md:687-694`).
- **Re-check**:
  - Location: `plan/track-9.md:81,83,103,104,131`; `implementation-plan.md:687-694`.
  - Current state: the trigger now fires only when both hold — a committed thread dump and heap histogram from a stalled fork, and a bisect that has run two working days or six attempts without pinning a fixable defect. That is observable and bounded. The branch states the substitute for every item and criterion the finding listed: the per-directory run becomes the published baseline, items 3 and 4 re-measure in that shape, criteria 1 and 2 relax, the Track 11 dependency restates, and the single-fork goal moves to a follow-up. The plan file's Track 9 entry carries a faithful summary of all of it.
  - Criteria met: every substitute the finding asked for is written.
- **Regression check**: I traced the branch's restatement list against the documents it names. `implementation-plan.md`'s Track 11 entry (`:698-717`) and `plan/track-9.md`'s own Interfaces section are already shape-agnostic — they say "Track 9's post-fix baseline", which the per-directory artifact satisfies. The document that does assume completion is `plan/track-11.md`, which the branch does not name: line 5, line 9 and line 87 each assert a completing feature suite, and line 104 repeats it in the dependency line. Track 9's own `## Purpose / Big Picture` is also unamended and still promises both runners complete. The finding's literal ask was met, so this is new ground rather than a residue of T26.
- **Verdict**: VERIFIED — see T32 for the uncovered documents

#### Verify T27: the third item-AST target read
- **Original issue**: the mechanism sentence named two of three target-constraint reads, and the fix's behaviour on `hasId` depends on the third.
- **Fix applied**: line 48 names all three reads; line 50 adds the reason the RID slot needs no population.
- **Re-check**:
  - Location: `plan/track-9.md:48,50`; `MatchEdgeTraverser.java:474-488`; `MatchExecutionPlanner.java:5610-5620,5666-5674`.
  - Current state: `getTargetFilter` returns `item.getFilter().getFilter()` at `:476`, `targetClassName` returns `item.getFilter().getClassName(iCommandContext)` at `:481`, `targetRid` returns `item.getFilter().getRid(iCommandContext)` at `:486`. Line 50's reasoning also holds, and holds on the right branch: `promoteStaticRidsFromFilters` runs for the translator inside `buildPatterns`' *early return*, gated on `promoteFilterRidsOnBuild` (set only by the `MatchPlanInputs` constructor at `:545`), and the comment at `:5666-5673` states the `@rid` term stays in `aliasFilters` on purpose. `SQLMatchFilter.fromAliasAndClass` populates neither `filter` nor `rid`, so `matchesRid` passes vacuously.
  - Criteria met: the three reads are enumerated and the RID conclusion is stated with its reason.
- **Regression check**: verified the promotion claim on the additive path specifically rather than on the SQL rebuild branch, since the two call `promoteStaticRidsFromFilters` from different places. Both leave the term in `aliasFilters`. Clean.
- **Verdict**: VERIFIED

#### Verify T28: load-bearing claims cite `/tmp`
- **Original issue**: the stall evidence, the early-abort evidence and the per-directory table all rested on `/tmp` logs, against the track's own recorded-artifact rule.
- **Fix applied**: item 1 (line 79) now requires the committed artifact to carry the excerpts the claims rest on, named individually.
- **Re-check**:
  - Location: `plan/track-9.md:79,114`.
  - Current state: item 1 requires the artifact to carry "the `Running` / `Tests run:` / fork-death lines from both runners, each per-directory count with its source command", with the stated purpose that no criterion depends on a `/tmp` log a reboot destroys. Line 114 keeps its `/tmp/core-final2-track10.log:4624` citation but adds "item 1's artifact carries the excerpt".
  - Criteria met: the load-bearing half of the proposed fix landed — the artifact obligation is now explicit and itemised.
- **Regression check**: line 40's `/tmp/track10-final-verify.log:4713-4748` citation did not get the same rider line 114 got. Since item 1 creates the artifact, the track cannot cite it by path yet, so the asymmetry is cosmetic and resolves when the artifact lands. Not worth a finding.
- **Verdict**: VERIFIED

## Findings

### T29 [blocker]
**Certificate**: C49 (`MatchPatternBuilder.aliasFilters` on the translated path)
**Location**: Track 9 `## Plan of Work` item 2, both the merge paragraph (line 86) and the construction-site paragraph (line 88), plus `## Interfaces and Dependencies` "In scope (modified)" (line 129); `WalkerContext.java:410-411`, `GremlinStepWalker.java:468-476`, `MatchPatternBuilder.java:108-112`, `GqlMatchPatternAssembler.java:39`

**Issue**: Both builder-side options bind against a map that is empty for every query this track exists to fix.

`MatchPatternBuilder.aliasFilters` is written in exactly one place — `addNode`, and only when the caller passes a non-null `where` (`:110-112`). Every translator caller passes null. `WalkerContext.addNode` is `patternBuilder.addNode(alias, className, null, false)` (`:411`); `SubTraversalPredicateAdapter.addNode` is the same (`:247`); `appendFrom` copies `source.aliasFilters`, which is empty for the same reason. The only non-null-`where` call site in `core/src/main/java` is `GqlMatchPatternAssembler.java:39`. So the map is populated by the front-end that has no edges and no defect, and empty for the front-end that has both.

The post-hop predicate lives somewhere else and arrives later. `HasStepRecogniser` routes `has(name, vadas)` through `ctx.putAliasFilter(boundary, …)` (`:167-169`), which writes `WalkerContext.aliasFilters`. `GremlinStepWalker.buildResult` calls `ctx.patternBuilder.build()` *first* (`:468`) and only then merges `ctx.aliasFilters` into `finalAliasFilters` (`:470-476`). The builder is locked and its `PatternIR` already returned by the time the filter exists in a map anyone can reach.

Both consequences are fatal to the two builder-side options as item 2 states them. A merge in `build()` over the assembled `Pattern`'s edge items sees `aliasFilters.get(alias) == null` for every alias, and item 2's own "skip any item whose alias has no `aliasFilters` entry" rule then skips all of them — the fix compiles, changes nothing, and the four acceptance shapes still return three rows. A merge at the two construction sites is worse: at `addEdge` / `vertexMethodItem` time the `has()` step has not even been walked yet.

The class half is not affected. `WalkerContext.addNode` does pass `className`, and `HasStepRecogniser:162` re-types the boundary through it, so `aliasClasses` is populated inside the builder and is available at `build()`. Only the `WHERE` half is missing.

Of the three enumerated options only the planner-side one has the data at the site: `MatchExecutionPlanner`'s `aliasFilters` field comes from `MatchPlanInputs.aliasFilters()`, which is `finalAliasFilters` after the merge.

**Proposed fix**: State in item 2 that the builder's `aliasFilters` is GQL-only and that the translated path's per-alias `WHERE` is not available until `GremlinStepWalker.buildResult` has merged `ctx.aliasFilters`. Then re-cast the two builder-side options against that fact — either thread the merged map in explicitly (a `build(Map<String, SQLWhereClause>)` overload, or a post-`build()` pass in `buildResult` over `ir.pattern()`'s edge items using `finalAliasFilters` and `ir.aliasClasses()`), or drop them and let the planner-side site at `:2064` stand as the option that already has both maps. Whichever survives, the class binding can come from `aliasClasses` at either site; only the `WHERE` forces the choice.

### T30 [should-fix]
**Certificate**: C50 (`mergedTargetFilter`'s dead class read)
**Location**: Track 9 `## Plan of Work` item 2, the closing sentence of the merge paragraph (line 86), and `## Interfaces and Dependencies` "In scope (modified)" (line 129); `MatchPatternBuilder.java:377-402`

**Issue**: The method item 2 names as the reference implementation never binds a class on any item this fix touches.

`mergedTargetFilter` reads `aliasClasses.get(alias)` into a local and then uses it in one branch only:

```java
var className = aliasClasses.get(alias);
SQLMatchFilter filter;
if (existingItemFilter != null) {
  filter = existingItemFilter.copy();     // className unused
} else {
  filter = SQLMatchFilter.fromAliasAndClass(alias, className);
}
```

Every positive path item on the additive path already carries an `SQLMatchFilter` — `addEdge` does `pathItem.setFilter(toFilter)` (`:161`) and `vertexMethodItem` does `item.setFilter(…)` (`MatchEdgePathItems.java:84`) — so `existingItemFilter` is never null and the copy branch always wins. The method AND-merges the `WHERE` correctly and returns a filter with no class item, which is precisely the `WHERE`-only outcome item 2 forbids two sentences earlier: `MatchEdgeTraverser.targetClassName` reads null and `g.V(marko).out().hasLabel(software)` stays at three rows.

The shape matters more than it looks, because `hasLabel` reaches the planner as a class and not as a predicate in the common case. `HasStepRecogniser:160-165` re-types the boundary node through `ctx.addNode(boundary, labelClass)` and adds a `@class = 'L'` term to the `WHERE` **only** under non-polymorphic mode. In polymorphic mode the class is the entire constraint, so a fix that binds only the `WHERE` loses it completely.

Acceptance criterion 4 would catch this, which is why it is a should-fix rather than a blocker. But the criterion catches it after the implementation, and item 2's job is to prevent it.

**Proposed fix**: Replace "already has that shape" with what the method actually does: `mergedTargetFilter` is the right merge for the `WHERE` and the right template for copy-not-replace, and its class seeding is dead on any item that already carries a filter. Say that the chosen site must set the class on the copied filter as well — the class is available from `aliasClasses` at every candidate site — and that reusing `mergedTargetFilter` unchanged produces exactly the `hasLabel` failure the criterion pins.

### T31 [should-fix]
**Certificate**: C51 (GQL IR invariance under all three fix sites)
**Location**: Track 9 `## Context and Orientation` (line 58, closing sentences) and `## Validation and Acceptance` (line 108); `## Interfaces and Dependencies` "In scope (modified)" (line 129); `GqlMatchPatternAssembler.java:29-41`, `MatchPatternBuilder.java:344-365,417-424`

**Issue**: The stated reason for keeping GQL in scope does not survive the fix-site enumeration the same round added.

Line 108 says "A builder-side fix still changes the IR GQL produces, which is why the pin matters", and line 58 says the same. Neither is true for any of item 2's three options. GQL calls `addNode` and `build()` and nothing else, so an `addEdge` / `vertexMethodItem` change is unreachable from it. Its `Pattern` has N nodes and zero edges, so a `build()`-level merge over edge items iterates an empty collection and `build()` returns the same deep copy it returns today. `mergedTargetFilter` has one call site, `buildNotExpression:365`, which GQL never reaches. The planner-side option is not builder-side at all, and `rebindFilters` is a no-op for GQL anyway because the 3-arg constructor sets `matchExpressions = List.of()`.

The criterion itself is harmless — it will pass trivially — and there is a real reason to keep a GQL pin, just not the one stated: `SQLMatchFilter.setFilter` and `fromAliasAndClass` are shared mutators that `GqlMatchVisitor` also builds through, and item 2 lists `setFilter` in "Signatures". A fix that changes those semantics rather than its own call site would reach GQL.

**Proposed fix**: Rewrite both sentences to the reason that holds — the pin guards the shared `SQLMatchFilter` mutators, not GQL's own IR, and it is expected to be a no-op under all three enumerated sites. Downgrade `GqlMatchPatternAssembler` in "In scope (modified)" from a file that changes to a file that is read and re-verified, or drop it and keep only the two prettyPrint tests.

### T32 [should-fix]
**Certificate**: C53 (escalation-branch complement coverage)
**Location**: Track 9 `## Plan of Work` item 1's escalation branch (line 83), `## Purpose / Big Picture` (line 5), and `## Validation and Acceptance` (line 113); `plan/track-11.md:5,9,87,104`

**Issue**: The escalation branch restates the Track 11 dependency in the two places that already tolerate either shape and skips the file that does not.

The branch names `## Interfaces and Dependencies` and "the plan file's Track 11 entry". Both already read shape-agnostically — Track 9's dependency line and `implementation-plan.md:698-717` both say "Track 9's post-fix baseline", which a per-directory artifact satisfies without amendment. The document that hard-codes completion is `plan/track-11.md`: its Purpose line says "the full TinkerPop Cucumber suite shows no regression", line 9 says Track 9 "delivers a feature suite that completes", acceptance line 87 says "The full TinkerPop Cucumber suite **completes** and shows no regression", and the dependency line 104 says "a feature suite that completes". Under escalation, Track 11's headline acceptance criterion is unmeetable and nothing points at the relaxation.

Track 9's own `## Purpose / Big Picture` has the same gap in miniature: it promises "both TinkerPop feature runners … complete with the translator on", and the escalation branch retires that promise ("the single-fork completion goal moves to a follow-up") without amending it or cross-referencing it. Criterion 13 (line 113) inherits the problem — "a suite that completes in `core` and hangs in `embedded` has not met this track's Purpose" reads against a Purpose the branch has quietly changed.

The `embedded` half is a smaller version of the same thing. The branch defines the fallback as "the seven-invocation per-directory run", which is a `core` shape; criterion 2 says only "the same escalation-branch relaxation applies", leaving the `embedded` fallback shape undefined.

**Proposed fix**: Add `plan/track-11.md` (lines 5, 9, 87 and 104) to the branch's restatement list. Give `## Purpose / Big Picture` a one-clause escalation complement, or a pointer to item 1's branch, and make criterion 13 read against the relaxed completion gate rather than the unqualified Purpose. State the `embedded` fallback shape as "whatever partition item 1 finds it runs in", matching criterion 3's existing phrasing.

### T33 [suggestion]
**Certificate**: C54 (`putEdgeFilter`'s declaring types)
**Location**: Track 9 `## Interfaces and Dependencies` "Signatures" (line 132); `RecognitionContext.java:160`, `WalkerContext.java:452`, `SubTraversalPredicateAdapter.java:290`, `EdgeHopRecogniser.java:139`

**Issue**: "Signatures" names `EdgeHopRecogniser.putEdgeFilter`. No such method exists.

`putEdgeFilter(String, SQLWhereClause)` is declared on the `RecognitionContext` interface (`:160`) and implemented twice — `WalkerContext:452`, which writes the observability map the same Signatures line already names, and `SubTraversalPredicateAdapter:290`, which writes the sub-traversal capture. `EdgeHopRecogniser` is a *caller*, at `:139`. Signatures is the list an implementer greps before touching anything, and the second implementation is the one it would most want to know about: if the fix changes how the edge `WHERE` travels, the captured-fragment path has its own copy.

**Proposed fix**: Change the entry to "`RecognitionContext.putEdgeFilter`, implemented on `WalkerContext` (`:452`) and `SubTraversalPredicateAdapter` (`:290`), called from `EdgeHopRecogniser` (`:139`)".

## Evidence base

#### C49 Premise: `MatchPatternBuilder.aliasFilters` is empty on the translated path
- **Track claim**: item 2's two builder-side options — "specify the merge in `MatchPatternBuilder.build()` over the assembled `Pattern`'s edge items" and the two-construction-site variant — plus "skip any item whose alias has no `aliasFilters` entry".
- **Search performed**: `grep -rn "\.addNode(" core/src/main/java`; reads of `MatchPatternBuilder.addNode` (`:93-114`), `build()` (`:417-424`), `appendFrom` (`:288-300`), `WalkerContext.addNode` (`:410-412`) and `putAliasFilter` (`:437-449`), `SubTraversalPredicateAdapter.addNode` / `putAliasFilter` (`:240-289`), `HasStepRecogniser` contribution block (`:156-171`), `GremlinPatternAssembler.appendFoldedHop` / `appendEdgeAsNode` (`:47-101`), `GremlinStepWalker.buildResult` (`:457-500`), `GqlMatchPatternAssembler` end to end.
- **Code location**: `MatchPatternBuilder.java:110-112`; `WalkerContext.java:411`; `SubTraversalPredicateAdapter.java:247`; `GqlMatchPatternAssembler.java:39`; `GremlinStepWalker.java:468-476`
- **Actual behavior**: `addNode` writes `aliasFilters` only under `if (where != null)`. Eight `addNode` call sites exist in `core/src/main/java`; seven route through `WalkerContext.addNode` or `SubTraversalPredicateAdapter.addNode`, both of which hard-code `null` for `where`, and the eighth is `GqlMatchPatternAssembler:39`, which passes `filter.getFilter()`. `appendFrom` re-reads `source.aliasFilters`, so it propagates emptiness. On the translated path the per-alias `WHERE` lands in `WalkerContext.aliasFilters` via `putAliasFilter` (written by `HasStepRecogniser:167-169`) and is merged into `finalAliasFilters` at `GremlinStepWalker.java:470-476` — after `ctx.patternBuilder.build()` at `:468`. `aliasClasses` is the opposite case: `WalkerContext.addNode` does forward `className`, and `HasStepRecogniser:162` re-types the boundary through it, so the class is inside the builder.
- **Verdict**: WRONG
- **Detail**: Produces T29. Reference-accuracy caveat — grep over `core/src/main/java` plus an end-to-end read of every call site it returned; a caller in another module or reached by reflection would not appear. The negative is bounded by `MatchPatternBuilder` being package-visible to a small consumer set (eleven files, per the pre-split C36).

#### C50 Premise: `mergedTargetFilter` binds a class only when the item has no filter
- **Track claim**: "`MatchPatternBuilder.mergedTargetFilter` (`:377-402`) already has that shape — it reads `aliasClasses` as well as `aliasFilters`, copies rather than replaces, and AND-merges through `mergeWhere`."
- **Search performed**: full read of `mergedTargetFilter` (`:377-402`), its single call site `buildNotExpression:344-375`, `mergeWhere` (`:405-408`), `SQLMatchFilter.fromAliasAndClass` (`:65-79`) and `setFilter` (`:88-101`); reads of `addEdge` (`:132-168`) and `MatchEdgePathItems.vertexMethodItem` (`:78-85`) to establish whether an item filter is ever absent; `HasStepRecogniser:156-171` for how `hasLabel` reaches the planner.
- **Code location**: `MatchPatternBuilder.java:379-385`; `MatchEdgePathItems.java:84`; `MatchPatternBuilder.java:161`; `HasStepRecogniser.java:160-165`
- **Actual behavior**: `className` is read at `:381` and consumed only at `:385`, inside `else { filter = SQLMatchFilter.fromAliasAndClass(alias, className); }`. The `if (existingItemFilter != null)` branch copies and never sets a class. Every additive-path positive item carries a filter — `addEdge` assigns `pathItem.setFilter(toFilter)` and `vertexMethodItem` assigns unconditionally — so the copy branch always fires and the class is never bound. The `WHERE` merge and the copy-not-replace behaviour are exactly as the track describes. Separately, `HasStepRecogniser` sends `hasLabel(L)` to `ctx.addNode(boundary, L)` and adds a `@class = 'L'` term to the `WHERE` only when `!ctx.polymorphic()`, so under polymorphic mode the class is the whole constraint.
- **Verdict**: WRONG
- **Detail**: Produces T30. The error is in "already has that shape", not in the three verbs that follow it: two of the three are accurate, and the third — reading `aliasClasses` — is accurate as a statement about the source text and false as a statement about behaviour on this path.

#### C51 Integration: does any of item 2's three sites change GQL's IR?
- **Plan claim**: "A builder-side fix still changes the IR GQL produces, which is why the pin matters (T21)"; `GqlMatchPatternAssembler` listed under "In scope (modified)".
- **Actual entry point**: `GqlMatchStatement.buildPlan` (`:80-92`) → `GqlMatchPatternAssembler.fromFilters` → `new MatchExecutionPlanner(ir.pattern(), ir.aliasClasses(), ir.aliasFilters())`.
- **Caller analysis**: `GqlMatchPatternAssembler` (43 lines, read end to end) calls `builder.addNode(alias, effectiveType(...), filter.getFilter(), false)` at `:39` and `builder.build()` at `:43`, and nothing else. Its `Pattern` therefore holds `PatternNode`s and no `PatternEdge`s. Option (a) touches `addEdge:148` and `MatchEdgePathItems.vertexMethodItem:84` — neither reachable from `addNode`. Option (b) walks `Pattern` edge items inside `build()` — an empty walk for GQL, and `build()`'s other two outputs (`Collections.unmodifiableMap(aliasClasses/aliasFilters)`) are untouched by an edge-item merge. Option (c) is `MatchExecutionPlanner.rebindFilters` at `:2064`, whose loop runs over `matchExpressions`, set to `List.of()` by the GQL 3-arg constructor. `mergedTargetFilter`'s only caller is `buildNotExpression`, which GQL never invokes.
- **Breaking change risk**: the one real GQL exposure is a change to the shared `SQLMatchFilter` mutators (`setFilter`, `fromAliasAndClass`), which `GqlMatchVisitor` builds through and which item 2 lists in "Signatures". `GqlMatchStatementPlanPrettyPrintTest` exists and would catch that.
- **Verdict**: MISMATCHES
- **Detail**: Produces T31. The criterion is worth keeping; only its stated justification fails.

#### C52 Premise: the surefire `test` mojo runs no lifecycle phase, and `test-compile` fixes it
- **Track claim**: "the surefire `test` mojo declares `<phase>test</phase>` with no `executePhase`, so a direct goal invocation runs that mojo alone and reads `core/target/classes` as it stood before the last edit"; every pinned command now prefixes `test-compile`.
- **Search performed**: `unzip -p` over the 3.5.3, 3.5.5 and 3.5.6 surefire jars, extracting the `<mojo>` block whose `<goal>` is `test` and scanning it for `phase`, `executePhase`, `executeGoal`, `executeLifecycle` and `requiresDependencyResolution`; `grep -n "mvnw\|surefire:test"` over `plan/track-9.md`, `implementation-plan.md` and `plan/track-11.md`.
- **Code location**: `~/.m2/repository/org/apache/maven/plugins/maven-surefire-plugin/{3.5.3,3.5.5,3.5.6}/…!META-INF/maven/plugin.xml`
- **Actual behavior**: all three descriptors carry `<phase>test</phase>` and `<requiresDependencyResolution>test</requiresDependencyResolution>`, and none carries any `execute*` element. The claim holds for every version resolvable on this machine. The remedy is also correct and minimal: invoking the `test-compile` phase runs the default lifecycle through `compile` and `test-compile`, both strictly before `test`, so the module's five `test`-bound surefire executions do not fire and only the explicitly named goal runs. Command inventory: `plan/track-9.md` lines 103 and 114 carry the prefix; lines 75 and 104 use `test` / `install`, which are phases; line 26 is the Decision Log's negative example and line 37 is a historical record. `implementation-plan.md` pins no surefire goal. `plan/track-11.md:60` still pins the bare goal.
- **Verdict**: CONFIRMED

#### C53 Premise: which documents assume item 1 succeeded
- **Track claim**: the escalation branch's restatement list — "the Track 11 dependency restates against the per-directory baseline in both `## Interfaces and Dependencies` and the plan file's Track 11 entry".
- **Search performed**: reads of `plan/track-9.md` `## Purpose / Big Picture`, `## Plan of Work` items 1, 3 and 4, all fourteen acceptance criteria and `## Interfaces and Dependencies`; `git diff` of `implementation-plan.md`; `awk` extraction of the plan file's Track 11 entry (`:698-717`); `grep -n "Track 9\|baseline\|completing\|Cucumber"` over `plan/track-11.md`.
- **Code location**: `plan/track-9.md:5,81,83,103,104,113,131`; `implementation-plan.md:687-694,698-717`; `plan/track-11.md:5,9,87,104`
- **Actual behavior**: every item and criterion the T26 finding enumerated now has a stated substitute, and the trigger is bounded by an artifact precondition plus "two working days or six bisect attempts". The two documents the branch names as needing restatement do not need it — Track 9's dependency line and the plan file's Track 11 entry both say "post-fix baseline" with no shape attached. The four sentences that do assume single-fork completion are all in `plan/track-11.md`, which the branch does not name, plus Track 9's own Purpose paragraph and criterion 13, which the branch does not amend. The `embedded` fallback shape is asserted ("the same escalation-branch relaxation applies") but not defined, since the seven-invocation partition is `core`'s.
- **Verdict**: PARTIAL
- **Detail**: Produces T32. The finding's literal ask was satisfied; the gap is in documents the finding did not list.

#### C54 Premise: who declares `putEdgeFilter`
- **Track claim**: `## Interfaces and Dependencies` "Signatures" lists "`WalkerContext.edgeFilters` (observability-only) and `EdgeHopRecogniser.putEdgeFilter`".
- **Search performed**: `grep -rn "putEdgeFilter\|edgeFilters" core/src/main/java`; reads of `WalkerContext.java:52-57,437-455`, `RecognitionContext.java:155-162`, `SubTraversalPredicateAdapter.java:100-106,285-292`, `EdgeHopRecogniser.java:110-145`.
- **Code location**: `RecognitionContext.java:160`; `WalkerContext.java:452-453`; `SubTraversalPredicateAdapter.java:290`; `EdgeHopRecogniser.java:139`
- **Actual behavior**: the interface method is declared at `RecognitionContext:160` and implemented at `WalkerContext:452` (writes `edgeFilters`, whose javadoc at `:55` reads "Populated by `putEdgeFilter` for observability; the same clause also travels on …") and at `SubTraversalPredicateAdapter:290` (writes `capturedEdgeFilters`, whose javadoc at `:103` mirrors the observability note and confirms "the filter also travels on the edge path item"). `EdgeHopRecogniser` calls it at `:139`, one line off the `:137` the pre-split certificate recorded. The observability-only characterisation the track relies on for T20 is corroborated twice in source javadoc.
- **Verdict**: WRONG
- **Detail**: Produces T33. The mis-attribution is documentary; the behavioural claim it sits beside is right.
