<!-- MANIFEST
findings: 10   severity: {blocker: 3, should-fix: 5, suggestion: 2}
index:
  - {id: T19, sev: blocker,    loc: docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:41, anchor: "### T19 ", cert: C45, basis: "the translator-off control prints the same single Running GQL Match Support line and finishes in 17 s, so that line marks no stall position; the local features already run green with the translator on"}
  - {id: T20, sev: blocker,    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchExecutionPlanner.java:6012, anchor: "### T20 ", cert: C38, basis: "item 2's named preferred fix rebinds over Pattern edge items with rebindFilters' overwrite semantics, which nulls the edge WHERE that lives only on the edge path item"}
  - {id: T21, sev: blocker,    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/gql/parser/GqlMatchVisitor.java:50, anchor: "### T21 ", cert: C40, basis: "GQL builds node-only patterns, so it has no path item and no non-root alias; the GQL witness and its acceptance criterion cannot be produced"}
  - {id: T22, sev: should-fix, loc: docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:92, anchor: "### T22 ", cert: C44, basis: "surefire:test@id runs no lifecycle phase, so every pinned gate measures the previously compiled classes and item 3 would republish the pre-fix number"}
  - {id: T23, sev: should-fix, loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchExecutionPlanner.java:5615, anchor: "### T23 ", cert: C37, basis: "buildPatterns returns early when pattern is pre-built, so :5677 is SQL-only; a same-named private rebindFilters also exists in SQLMatchStatement"}
  - {id: T24, sev: should-fix, loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/MatchEdgePathItems.java:84, anchor: "### T24 ", cert: C34, basis: "addEdgeAsNode builds its target-vertex item outside addEdge, so a builder-side fix confined to mergedTargetFilter leaves every outE(L).inV() target unbound"}
  - {id: T25, sev: should-fix, loc: docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:87, anchor: "### T25 ", cert: C41, basis: "no translator recogniser produces the back-ref NOT IN shape, so the criterion is a SQL MATCH no-regression clause with no named witness"}
  - {id: T26, sev: should-fix, loc: docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:70, anchor: "### T26 ", cert: C45, basis: "the ESCALATE trigger is self-referential and its branch has no complement; items 3, 4 and the first two acceptance criteria all assume item 1 succeeded"}
  - {id: T27, sev: suggestion, loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchEdgeTraverser.java:486, anchor: "### T27 ", cert: C48, basis: "targetRid is a third item-AST target read the mechanism sentence omits, and hasId on a post-hop alias is a named acceptance shape"}
  - {id: T28, sev: suggestion, loc: docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:39, anchor: "### T28 ", cert: C45, basis: "the stall evidence, the early-abort evidence and the per-directory table all cite /tmp logs, against the track's own recorded-artifact rule"}
evidence_base: {section: "## Evidence base", certs: 17, matches: 8}
cert_index:
  - {id: C32, verdict: CONFIRMED, anchor: "#### C32 "}
  - {id: C33, verdict: CONFIRMED, anchor: "#### C33 "}
  - {id: C34, verdict: PARTIAL, anchor: "#### C34 "}
  - {id: C35, verdict: CONFIRMED, anchor: "#### C35 "}
  - {id: C36, verdict: MATCHES, anchor: "#### C36 "}
  - {id: C37, verdict: WRONG, anchor: "#### C37 "}
  - {id: C38, verdict: n/a, anchor: "#### C38 "}
  - {id: C39, verdict: PARTIAL, anchor: "#### C39 "}
  - {id: C40, verdict: MISMATCHES, anchor: "#### C40 "}
  - {id: C41, verdict: PARTIAL, anchor: "#### C41 "}
  - {id: C42, verdict: MATCHES, anchor: "#### C42 "}
  - {id: C43, verdict: CONFIRMED, anchor: "#### C43 "}
  - {id: C44, verdict: WRONG, anchor: "#### C44 "}
  - {id: C45, verdict: WRONG, anchor: "#### C45 "}
  - {id: C46, verdict: CONFIRMED, anchor: "#### C46 "}
  - {id: C47, verdict: CONFIRMED, anchor: "#### C47 "}
  - {id: C48, verdict: PARTIAL, anchor: "#### C48 "}
flags: [CONTRACT_OK]
-->

# Track 9 technical review — post-split scope, iteration 4

The dropped-filter mechanism the track states is right in every particular, and the numbers it carries reproduce exactly: 369 + 811 + 175 + 204 + 134 + 97 + 98 = 1888 upstream scenarios with 10 + 22 + 7 + 3 = 42 failures, plus 42 local scenarios green, summing to the 1930 the translator-off single fork reports. Track 10's disposition table sums to 21 with nine carrying the dropped-filter label. Every production class and surefire execution the track names resolves. Three blockers sit elsewhere: item 1's narrowing is refuted by the control run already on disk, item 2's named preferred fix site would silently clear the edge predicate it is not supposed to touch, and the GQL half of the acceptance set cannot be written because GQL builds node-only patterns.

The pre-split panel's C11 certificate holds on re-verification (C33, C34, C35 below). What C11 did not check is the part of the blast-radius sentence the split promoted into a decision: `rebindFilters`' second call site is on the SQL path only, `rebindFilters` never binds a class name, and `MatchPatternBuilder` is not the only place a positive path item gets built.

**Tooling caveat.** `steroid_execute_code` timed out on this repository again (120 s limit, minimal `findClass` snippet, project `design.md` whose path is this working tree). Every symbol result below is `find` / `grep` / `unzip` plus an end-to-end read of the declaring file. Declaration-level reads are exact. The negatives that carry weight — "GQL never calls `addEdge`", "`edgeFilters` never reaches `aliasFilters`", "`SQLMatchStatement` is not a `MatchPatternBuilder` consumer", "no translator recogniser emits the back-ref `NOT IN` shape" — were each cross-checked by reading the candidate producer end to end, which bounds the usual grep risk without eliminating it. Re-verify through PSI at decomposition if the IDE recovers.

## Findings

### T19 [blocker]
**Certificate**: C45 (Cucumber stall evidence vs. the control)
**Location**: Track 9 `## Context and Orientation`, the "Both observed stalls stop at the same feature" paragraph (line 41), and `## Plan of Work` item 1; `/tmp/t9-cuke-off.log:22`, `/tmp/t9-cuke-on.log:22`, `/tmp/t9risk-cuc-localonly-on.log`

**Issue**: The paragraph reads a surefire report line as progress output. It is not one, and the control that proves so is in the same directory as the evidence the track cites.

The track argues: a 15-minute translator-on run "printed exactly one `[INFO] Running` line — `GQL Match Support` — and then produced no further output"; Track 10's run "reached the same feature and died there"; therefore item 1 should first test "whether the stall is the GQL feature itself under the translator, or that feature running after the upstream ones have accumulated state", which is "a much narrower starting point than 'somewhere in the suite'".

The **successful** translator-off run prints the identical line. `/tmp/t9-cuke-off.log:22` is `[INFO] Running GQL Match Support`, and line 32 of the same 2445-byte log is `Tests run: 1930, Failures: 0, Errors: 0, Skipped: 14` after 17.05 s. `/tmp/t9risk-cucumber-off.log` matches at lines 21 and 31. The mechanism is presumably that the JUnit47 provider emits one `Running <name>` per JUnit test class and cucumber-junit exposes the whole suite as one class; what matters is the observation, which needs no mechanism: the run that finishes and the run that hangs print the same string at the same position. The line carries no positional information, so neither observed stall has been located.

The second branch of the hypothesis is also already measured and green. `/tmp/t9risk-cuc-localonly-on.log` runs the local feature directory with the translator **on** and reports `Tests run: 42, Failures: 0, Errors: 0, Skipped: 0` in 5.395 s. `GQL Match Support` under the translator is not the stall. The track states this itself three paragraphs later ("plus 42 local scenarios green") and then proposes testing it.

What the evidence does establish is stronger than what the paragraph claims and points somewhere else. Partitioned into seven Maven invocations with the translator on, all 1888 upstream plus 42 local scenarios execute — exactly the 1930 the single fork reports when the translator is off, with the same 14 skips (6 in `map`, 8 in `sideEffect`). No scenario hangs in isolation. The failure is a property of running them in one JVM.

**Proposed fix**: Delete the "Both observed stalls stop at the same feature" paragraph and the narrowing it derives. Replace it with the per-directory identity above (1888 + 42 = 1930, 14 skips matching, every directory seconds-fast) and state the real question: what accumulates across scenarios inside one fork. Name a first diagnostic that discriminates — bisect by fork content rather than by feature (`-Dcucumber.features=` with two upstream directories, then four, until it stalls) and capture `jcmd <pid> Thread.print` plus a heap histogram on the stalled fork before killing it, since a thread dump distinguishes deadlock from leak and neither hypothesis in the current text does.

### T20 [blocker]
**Certificate**: C38 (Pattern-edge rebind on the additive path), C39 (`rebindFilters` binds no class)
**Location**: Track 9 `## Plan of Work` item 2 and `## Interfaces and Dependencies` "Signatures"; `MatchExecutionPlanner.java:6012-6022`, `SQLMatchFilter.java:88-101`, `MatchEdgePathItems.java:62-67`, `WalkerContext.java:57,452`, `GremlinStepWalker.java:470-476`

**Issue**: Item 2 names one option as the one that keeps `rebindFilters`' purpose intact — "rebinding over the `Pattern`'s edge items rather than `matchExpressions`". Implemented with `rebindFilters`' current semantics, that option reintroduces this track's own defect class on a different filter.

`rebindFilters`' body is an unconditional overwrite:

```java
for (var item : expression.getItems()) {
  newFilter = aliasFilters.get(item.getFilter().getAlias());
  item.getFilter().setFilter(newFilter);
}
```

and `SQLMatchFilter.setFilter(null)` clears an existing clause rather than leaving it alone — it walks `items`, finds the first with a non-null `filter`, and assigns the argument. On the SQL path that is safe because `aliasFilters` was built from those very expressions by `addAliases`. On the additive path it is not.

Walking the `Pattern`'s edge items visits **edge** path items, not only target-vertex ones. `MatchEdgePathItems.edgeMethodItem` puts the edge `WHERE` on the edge alias's `SQLMatchFilter` (line 62-67), and that clause lives nowhere else: `EdgeHopRecogniser:137` records it via `ctx.putEdgeFilter`, which writes `WalkerContext.edgeFilters` — a map whose own javadoc calls it "for observability" (line 55-57) — and `GremlinStepWalker.buildResult` composes `MatchPlanInputs.aliasFilters` from `ir.aliasFilters()` merged with `ctx.aliasFilters` only. So `aliasFilters.get("$g2m_edge_0")` is null, and the rebind would null out the edge predicate. Every `outE(L).has(p, v).inV()` shape would start over-matching, silently, translating with no decline — the same signature the track exists to remove.

Second defect in the same option. `rebindFilters` sets only the `WHERE`; it never touches `getClassName`. `MatchEdgeTraverser.targetClassName` reads `item.getFilter().getClassName(iCommandContext)` (line 481), and forward traversal has no planner-side class annotation — `EdgeTraversal.leftClass` is the *source* class, consumed by `MatchReverseEdgeTraverser` as its target (C42). So a planner-side fix modelled on `rebindFilters` fixes `has(name, vadas)` and `hasId(vadas)` and leaves `g.V(marko).out().hasLabel(software)` returning three rows — one of the four shapes `## Validation and Acceptance` requires to return one.

`MatchPatternBuilder.mergedTargetFilter` (line 377-402) already has the correct shape for both problems: it reads `aliasClasses.get(alias)` as well as `aliasFilters.get(alias)`, copies an existing item filter rather than replacing it, and AND-merges through `mergeWhere` instead of overwriting.

**Proposed fix**: Item 2 must specify the binding as a **merge**, not a rebind, and say so in the same sentence that names the site: bind class **and** `WHERE`, AND-compose with whatever the item already carries, and skip the item when `aliasFilters` has no entry for its alias. Add "edge-alias path items keep their own `WHERE`" as an explicit constraint and an acceptance line witnessing it (`g.V().outE(L).has(p, v).inV()` returns the native multiset before and after). Add `g.V(marko).out().hasLabel(software)` to the class-binding side of the criterion rather than leaving it in the undifferentiated four.

### T21 [blocker]
**Certificate**: C40 (GQL builds node-only patterns)
**Location**: Track 9 `## Validation and Acceptance` ("A GQL `MATCH` with a filter on a non-root alias returns the corrected rows") and `## Interfaces and Dependencies` "In scope (new)" ("a GQL non-root-alias witness"); `GqlMatchVisitor.java:38,50`, `GqlMatchPatternAssembler.java:34-40`, `MatchExecutionPlanner.java:300-301,668-674`

**Issue**: GQL has no non-root alias to filter, so neither the criterion nor the deliverable can be produced.

`GqlMatchVisitor extends GQLBaseVisitor<Void>` and overrides exactly one rule, `visitNode_pattern`, accumulating a flat `List<SQLMatchFilter>`. The grammar does carry edges (`GQL.g4:58` `path_pattern: node_pattern (edge_pattern quantifier? node_pattern)*`, with six `edge_pattern` alternatives at :64-71), but nothing consumes them: the base visitor descends into children, the node rule fires per node, and the edge context is discarded. `GqlMatchPatternAssembler.add` then calls `builder.addNode(alias, effectiveType(...), filter.getFilter(), false)` and nothing else — a repository-wide search for `addEdge(` / `addEdgeAsNode(` in `core/src/main/java` returns `WalkerContext`, `GremlinPatternAssembler`, `SubTraversalPredicateAdapter` and `MatchPatternBuilder` itself, and no GQL file.

A pattern with nodes and no edges reaches `MatchExecutionPlanner` as N disconnected components. The planner splits them (`subPatterns`, line 300-301) and takes the Cartesian product of independently rooted results (line 668-674). Every alias is its own root, every root's filter is read from `aliasFilters`, and the path-item filter the defect drops never exists.

The `## Interfaces and Dependencies` consequence the track draws from T8 is still right for the other reason: a builder-side fix changes the IR GQL produces and therefore its `prettyPrint` output, which `GqlMatchStatementPlanPrettyPrintTest` pins. The reason stated — that GQL exhibits the same defect — is what fails.

**Proposed fix**: Replace the acceptance criterion with the regression form GQL can actually meet: a multi-node GQL `MATCH` with inline property filters produces the same rows and the same `prettyPrint` plan text before and after the fix, witnessed by `GqlMatchStatementPlanPrettyPrintTest` and `GqlMatchStatementTest`. Drop "a GQL non-root-alias witness" from "In scope (new)" and keep `GqlMatchPatternAssembler` plus the Track 1 prettyPrint tests in "In scope (modified)" under the regression rationale. If it is worth recording, note that `MATCH (a:Person)->(b:Post)` currently parses and silently yields a Cartesian product — a pre-existing GQL gap this track does not own.

### T22 [should-fix]
**Certificate**: C44 (`surefire:test@id` runs no lifecycle phase)
**Location**: Track 9 `## Validation and Acceptance` first criterion (line 82) and the pinned-commands criterion (line 92); `~/.m2/.../maven-surefire-plugin-3.5.6.jar!META-INF/maven/plugin.xml`

**Issue**: The command the track pins for both the completion gate and the fast iteration loop compiles nothing, so after item 2 lands it measures the pre-fix build.

The surefire `test` mojo descriptor carries `<phase>test</phase>` and no `executePhase` / `executeGoal` element. A direct goal invocation (`mvn surefire:test@<id>`) therefore runs that mojo alone: no `compile`, no `test-compile`. The ~20 s the track cites against ~31 min for the full suite is exactly the saving of skipping them. Item 2 edits `MatchPatternBuilder` and/or `MatchExecutionPlanner`, both main sources; item 3 is "re-measure immediately after item 2" and is described as the gate on item 2; item 4's re-run is "the baseline Track 11 reads". Run as pinned, all three read `core/target/classes` as it stood before the fix and report a number that looks like a clean before/after pair while being two copies of "before".

This is the failure the track's own second Decision Log entry exists to prevent — "recompute the measured baseline whenever anything moves the measured behaviour" — arriving through the measurement command rather than through a stale artifact. The rule as written watches for a rebase and for the track's own fixes; it does not watch for a gate that cannot see either.

**Proposed fix**: Pin `./mvnw -pl core -o test-compile surefire:test@gremlin-feature-compliance-tests` at every site that currently pins the bare goal (the first acceptance criterion, the pinned-commands criterion, and the per-directory `-Dcucumber.features=` loop). Add one clause to the second Decision Log entry: every number published as a baseline records the commit SHA it was taken at and comes from an invocation that compiled in the same run.

### T23 [should-fix]
**Certificate**: C37 (`rebindFilters` call sites and their reachability)
**Location**: Track 9 `## Context and Orientation` ("called from `:2064` and `:5677`, both on the common path") and the identical clause in `## Interfaces and Dependencies` "Signatures"; `MatchExecutionPlanner.java:5609-5622,5677,2064`, `SQLMatchStatement.java:226,232`

**Issue**: One of the two call sites is unreachable from the front-ends this track changes, and a second method of the same name exists on the SQL path.

`:5677` is the last statement of `buildPatterns(CommandContext)`. That method opens with an early return:

```java
private void buildPatterns(CommandContext ctx) {
  if (this.pattern != null) {
    if (promoteFilterRidsOnBuild) { ... }
    return;
  }
```

`this.pattern != null` is precisely the pre-built-pattern condition — the `(Pattern, aliasClasses, aliasFilters)` constructor GQL uses and the `MatchPlanInputs` constructor the translator uses both set it before planning. So on both additive paths `buildPatterns` returns before `:5677`. Only `:2064`, inside `createPlanForPattern`, is reached by all three front-ends. The blast-radius sentence overstates the shared surface by one call site, and item 2's choice rests on that sentence.

Separately, `SQLMatchStatement` declares its own `private void rebindFilters(Map<String, SQLWhereClause>)` at `:232`, called from its `buildPatterns()` at `:226`. It walks the statement's own `matchExpressions` and runs before the planner is constructed. An implementer grepping `rebindFilters` gets two declarations and four call sites across two files; the track names one declaration and two call sites without saying the other exists.

**Proposed fix**: Correct both sentences to "called from `:2064` (reached by all three front-ends) and `:5677` (inside `buildPatterns`, which returns early on the pre-built-pattern path, so SQL `MATCH` only)". Add one line to "Signatures" naming `SQLMatchStatement.rebindFilters` (private, `:232`) as a same-named SQL-path method this track does not touch.

### T24 [should-fix]
**Certificate**: C34 (positive path-item construction sites)
**Location**: Track 9 `## Context and Orientation` mechanism paragraph and `## Interfaces and Dependencies` "In scope (modified)" ("`MatchPatternBuilder`'s positive-path-item construction and its `mergedTargetFilter` merge"); `MatchPatternBuilder.java:148,199-222`, `MatchEdgePathItems.java:84`

**Issue**: Positive path items are built in two places, and the second one is outside `MatchPatternBuilder`.

The mechanism paragraph names `SQLMatchFilter.fromAliasAndClass(toAlias, null)`, which is `addEdge`'s `toFilter` at `MatchPatternBuilder.java:148`. `addEdgeAsNode` builds its target-vertex item through `MatchEdgePathItems.vertexMethodItem`, whose body is `item.setFilter(SQLMatchFilter.fromAliasAndClass(targetAlias, null))` at `MatchEdgePathItems.java:84` — same alias-only filter, different file, different package (`sql/parser`, not `sql/executor/match/builder`). `MatchEdgePathItems` is one of the eleven `MatchPatternBuilder` consumers the track counted, so it is inside the grep the blast-radius claim rests on and outside the fix site the claim names.

`EdgeHopRecogniser` routes every `outE(L)[.has(...)].inV()` shape through `addEdgeAsNode`, so a builder-side fix confined to `addEdge` and `mergedTargetFilter` leaves the whole edge-as-node family's target vertex unbound and the four named acceptance shapes still green — the residue would only show up in the Cucumber set.

**Proposed fix**: Either name both sites in item 2 and in "In scope (modified)", or specify the merge in `MatchPatternBuilder.build()` over the assembled `Pattern`'s edge items, which makes the construction-site count stop mattering. The second is the smaller surface and composes with T20's merge-not-overwrite requirement.

### T25 [should-fix]
**Certificate**: C41 (`detectNotInAntiJoin` reachability on the additive path)
**Location**: Track 9 `## Validation and Acceptance` (line 87) and `## Plan of Work` item 2's "must answer how a post-optimization `NOT IN` strip still reaches the item AST"; `MatchExecutionPlanner.java:2058-2064,4408,4581`, `GremlinPredicateAdapter.java:300,322`, `HashJoinPlannerIntegrationTest.java:1197,2505`

**Issue**: The criterion is stated as if it applies to the path this track changes; it applies to SQL `MATCH`, and no test is named for it.

`detectNotInAntiJoin` fires from the pre-filter pass at `:4408`, inside `createPlanForPattern`'s optimize stage, and the strip is pushed back by the `:2064` rebind over `matchExpressions`. `matchExpressions` is empty on both additive paths (C35). Nothing in the translator produces the shape the detector looks for either: `NotStepRecogniser` emits `notMatchExpressions`, and `GremlinPredicateAdapter.without` emits a value-list `NOT IN` (`key IS DEFINED AND NOT(key IN [...])`, line 300, 322), not the `$currentMatch NOT IN $matched.X.out('E')` back-ref form. So on the Gremlin and GQL paths there is nothing to strip and nothing to push back today, and the criterion reduces to "do not break SQL `MATCH`'s existing push-back" — real, but a different obligation from the one the sentence describes.

The track names `MatchStatementExecutionTest` as gate cost only for the planner-side option and names no witness for this criterion at all. `HashJoinPlannerIntegrationTest` already carries anti-join regressions that reference `detectNotInAntiJoin` by name (`:1197`, `:2505`).

**Proposed fix**: Restate the criterion as a SQL-`MATCH` no-regression clause and name its witnesses: `HashJoinPlannerIntegrationTest` unconditionally, plus `MatchStatementExecutionTest` when the planner-side site is chosen. Keep item 2's question, narrowed to "does the chosen site change what `:2064` pushes on the SQL path".

### T26 [should-fix]
**Certificate**: C45 (stall evidence), C43 (surefire execution order)
**Location**: Track 9 `## Plan of Work` item 1 ESCALATE trigger (line 70) and its `embedded` clause; items 3 and 4; `## Validation and Acceptance` criteria 1 and 2; `implementation-plan.md:687-690`

**Issue**: The trigger cannot be evaluated, and the branch it guards has no other side.

"If the cause is not localized to a fixable defect within this step, escalate" defines the exit condition in terms of the thing being decided. The `embedded` clause is tighter in form and looser in substance: "apply the same ESCALATE trigger if it fails to complete for a cause this track cannot localize". Neither gives a wall-clock budget, an attempt count, or an observation whose absence ends the search. Track 10's precedent that the track cites produced this shape because the cost of absorbing an undiagnosed engine fault is unbounded; a trigger that never fires does not bound it.

The larger gap is the missing complement. Items 3 and 4 both open with "re-run both runners"; the first two acceptance criteria both require a single fork that completes; the handoff criterion requires "both runners re-run, recorded, and named as the number Track 11 reads". If item 1 escalates, none of those has a defined substitute, and Track 11's stated dependency ("a completing feature suite and a post-fix baseline") has no fallback either.

**Proposed fix**: Give the trigger an observable boundary — a named artifact that must exist before escalation is allowed (a `jcmd Thread.print` and heap histogram from the stalled fork, committed under `plan/track-9/`) plus a budget stated in attempts or hours. Write the escalation branch: the seven-invocation per-directory run becomes the published baseline, items 3 and 4 re-measure in that shape, acceptance criteria 1 and 2 relax to "completes per directory with the recorded failure set", and the Track 11 dependency line in `## Interfaces and Dependencies` and in `implementation-plan.md` restates against it.

### T27 [suggestion]
**Certificate**: C48 (`MatchEdgeTraverser`'s three target reads)
**Location**: Track 9 `## Context and Orientation` mechanism paragraph and `## Interfaces and Dependencies` "Signatures"; `MatchEdgeTraverser.java:475-487`, `MatchExecutionPlanner.java:5655-5677`

**Issue**: The mechanism sentence names two of three target-constraint reads. Alongside `getTargetFilter` (`:476`) and `targetClassName` (`:481`), `targetRid(SQLMatchPathItem, CommandContext)` at `:486` returns `item.getFilter().getRid(iCommandContext)`, and `matchesRid` is one of the three conjuncts in the traverser's accept test. `hasId` on a post-hop alias is one of the four named acceptance shapes, so which slot the fix populates is load-bearing rather than incidental.

In practice binding the `WHERE` is enough: the translator lowers `hasId` into an `@rid` / `@rid IN` term in `aliasFilters`, and `promoteStaticRidsFromFilters` deliberately leaves that term in place ("The @rid term stays in aliasFilters on purpose", `:5666-5673`) while copying it into `aliasPinnedRids`. `SQLMatchFilter.fromAliasAndClass` never populates the `rid` slot, so it stays null and `matchesRid` passes. Saying that explicitly is cheaper than having decomposition rediscover it.

**Proposed fix**: Extend the mechanism sentence to "three reads — `getFilter()`, `.getClassName()` and `.getRid()`" and add the one-line reason the RID slot needs no population.

### T28 [suggestion]
**Certificate**: C45 (stall evidence), C43 (early-abort evidence)
**Location**: Track 9 `## Context and Orientation` (line 39, `/tmp/track10-final-verify.log:4713-4748`) and `## Validation and Acceptance` (line 92, `/tmp/core-final2-track10.log:4624`); the per-directory table's source logs

**Issue**: Three load-bearing claims cite files outside the repository. All three verify today — `/tmp/track10-final-verify.log` reaches `[INFO] Running GQL Match Support`, reports `Tests run: 0` and dies with `The forked VM terminated without properly saying goodbye` after 31:35; `/tmp/core-final2-track10.log:4624` is the `gremlin-process-compliance-tests` abort with 16 failures and 6 errors; the seven `/tmp/t9risk-bisect-*.log` files carry the per-directory counts. None survives a reboot, and the track's third Decision Log entry says every criterion reads against a recorded artifact.

**Proposed fix**: Item 1's committed baseline artifact should carry the excerpts these claims rest on — the `Running` / `Tests run:` / fork-death lines from both runners and the per-directory table with its source command — and the track file should cite `plan/track-9/<artifact>.md` rather than `/tmp`.

## Evidence base

#### C32 Premise: every production class and surefire execution the track names resolves
- **Track claim**: the classes named across `## Purpose / Big Picture`, `## Context and Orientation`, `## Plan of Work`, `## Decision Log` and `## Interfaces and Dependencies` — `MatchPatternBuilder`, `MatchExecutionPlanner`, `MatchEdgeTraverser`, `SQLMatchStatement`, `GqlMatchStatement`, `GqlMatchPatternAssembler`, `GremlinToMatchStrategy`, `SQLMatchFilter`, `SQLMatchPathItem`, `GlobalConfiguration`, `YTDBGraphFeatureTest`, `EmbeddedGraphFeatureTest`, `GraphFeatureWorld`, `EdgeTraversalEquivalenceTest`, `PredicateTraversalEquivalenceTest`, `MatchStatementExecutionTest`.
- **Search performed**: `find . -name '<ClassName>.java' -not -path '*/target/*'` for each, filtered to the main working tree (each name also matches under `.claude/worktrees/**`, which are separate checkouts, not ambiguity). PSI `findClass` attempted first and timed out — see the tooling caveat.
- **Code location**: single main-tree match each, package matching the reconstructed FQN. `MatchPatternBuilder` → `core/.../sql/executor/match/builder/`; `MatchEdgeTraverser`, `MatchExecutionPlanner` → `core/.../sql/executor/match/`; `SQLMatchStatement`, `SQLMatchFilter`, `SQLMatchPathItem`, `MatchEdgePathItems` → `core/.../sql/parser/`; `GqlMatchStatement`, `GqlMatchPatternAssembler`, `GqlMatchVisitor` → `core/.../gql/parser/`; `GremlinToMatchStrategy`, `WalkerContext`, `EdgeHopRecogniser` → `core/.../gremlin/translator/strategy/`; `GlobalConfiguration` → `core/.../api/config/`; `EmbeddedGraphFeatureTest` → `embedded/src/test/.../shade/`.
- **Actual behavior**: all resolve. `GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED` is declared at `GlobalConfiguration.java:1019` with default `true`, read at `GremlinToMatchStrategy.java:338` as the track states. `MatchStatementExecutionTest` carries 159 `@Test` methods, matching the track's "159 test methods". The three compliance executions exist in `core/pom.xml`'s `gremlin-compliance-suites` profile (`:250`, `:263`, `:276`), each with `failIfNoTests=true` and an `<includes>` naming one runner, and the profile activates on `!test` (`:238-242`).
- **Verdict**: CONFIRMED
- **Detail**: Reference-accuracy caveat — filename search plus package check, not PSI. Adequate for existence; a name collision inside an unrelated package would not surface.

#### C33 Premise: `MatchEdgeTraverser` reads the target constraint from the item AST
- **Track claim**: "`MatchEdgeTraverser` reads the target constraint from the pattern item's AST (`item.getFilter().getFilter()` and `.getClassName()`) rather than from `aliasFilters`" (pre-split C11 sub-claim 1, re-verified).
- **Search performed**: `grep -n "getFilter()\|getClassName(\|aliasFilters" MatchEdgeTraverser.java`, full read of lines 460-490.
- **Code location**: `core/src/main/java/.../match/MatchEdgeTraverser.java:474-487`
- **Actual behavior**: `getTargetFilter(SQLMatchPathItem item)` returns `item.getFilter().getFilter()` (`:476`); `targetClassName` returns `item.getFilter().getClassName(iCommandContext)` (`:481`); `targetRid` returns `item.getFilter().getRid(iCommandContext)` (`:486`). No `aliasFilters` reference anywhere in the file. The three feed `matchesFilters` / `matchesClassCached` / `matchesRid` as one conjunction at `:462-465`.
- **Verdict**: CONFIRMED
- **Detail**: C11 holds. The third read is C48.

#### C34 Premise: positive path items are built with an alias-only filter
- **Track claim**: "`MatchPatternBuilder` builds positive path items through `SQLMatchFilter.fromAliasAndClass(toAlias, null)` with neither a `WHERE` nor a class."
- **Search performed**: `grep -rn "fromAliasAndClass" core/src/main/java core/src/test/java`; full reads of `MatchPatternBuilder.addEdge` (`:132-168`), `addEdgeAsNode` (`:199-222`), `buildNotExpression` / `mergedTargetFilter` (`:344-402`), and `MatchEdgePathItems` (`:40-86`).
- **Code location**: `MatchPatternBuilder.java:147-148,215,354,386`; `MatchEdgePathItems.java:62,84`
- **Actual behavior**: `addEdge` builds `toFilter = SQLMatchFilter.fromAliasAndClass(toAlias, null)` and sets a `WHERE` only when the caller passed `edgeFilter` — which on the translator path is always null (`WalkerContext.java:420` passes `null, null, null`). `addNode`'s `:386` site does pass a class name. `fromAliasAndClass` (`SQLMatchFilter.java:65-76`) sets only the alias, plus a class item when `className` is non-blank; it never populates `filter` or `rid`. **The claim is incomplete**: `addEdgeAsNode` (`:199-222`) builds its target-vertex item through `MatchEdgePathItems.vertexMethodItem`, whose body is `item.setFilter(SQLMatchFilter.fromAliasAndClass(targetAlias, null))` at `MatchEdgePathItems.java:84` — a second construction site outside `MatchPatternBuilder`, in `sql/parser`.
- **Verdict**: PARTIAL
- **Detail**: Produces T24. `mergedTargetFilter` (`:377-402`) is the merge the track names and does what the track says: it copies an existing item filter when present, seeds `className` from `aliasClasses.get(alias)`, AND-merges `aliasFilters.get(alias)` and any supplemental clause via `mergeWhere`. Its only call site is `buildNotExpression:365` (private method, declaring file read end to end).

#### C35 Premise: `rebindFilters` walks `matchExpressions`, empty on both additive paths
- **Track claim**: "The one routine that would populate them, `rebindFilters`, walks `matchExpressions`, which is empty on the additive translator path because the pattern arrives pre-built."
- **Search performed**: read of `MatchExecutionPlanner.rebindFilters` (`:6007-6022`), all four `MatchExecutionPlanner` constructors (`:438-560`), `GremlinStepWalker.buildResult` (`:470-500`), `MatchPlanInputs` compact constructor (`:71`).
- **Code location**: `MatchExecutionPlanner.java:6012-6022,451-467,534-556`; `GremlinStepWalker.java:481-496`; `MatchPlanInputs.java:71`
- **Actual behavior**: `rebindFilters` is `for (var expression : matchExpressions) { origin.setFilter(...); for (var item : expression.getItems()) { item.getFilter().setFilter(...); } }`. The GQL 3-arg constructor assigns `this.matchExpressions = List.of()` outright (`:453`). The `MatchPlanInputs` constructor copies `inputs.matchExpressions()`, and `GremlinStepWalker.buildResult`'s builder chain sets `notMatchExpressions` but never `matchExpressions`, which `MatchPlanInputs`' compact constructor normalises to `List.of()` (`:71`). The loop body never runs on either additive path.
- **Verdict**: CONFIRMED

#### C36 Integration: planner entry points and the `MatchPatternBuilder` consumer set
- **Plan claim**: "Three entry points construct the planner: `SQLMatchStatement:191,201`, `GremlinToMatchStrategy:486`, `GqlMatchStatement:88` … Only the last two use `MatchPatternBuilder` — a repository-wide grep over `core/src/main/java` returns eleven consumers and `SQLMatchStatement` is not among them."
- **Actual entry point**: `SQLMatchStatement.java:191` and `:201` (`new MatchExecutionPlanner(this)`, in `createExecutionPlan` and `createExecutionPlanNoCache`); `GremlinToMatchStrategy.java:486` (`new MatchExecutionPlanner(inputs)`); `GqlMatchStatement.java:88` (`new MatchExecutionPlanner(ir.pattern(), ir.aliasClasses(), ir.aliasFilters())`).
- **Caller analysis**: `grep -rn "new MatchExecutionPlanner" core/src server/src embedded/src tests/src` returns exactly those four production sites plus test-only sites (`MatchPlannerHelpersTest:494`, `MatchExecutionPlannerInputsTest` ×8, `GqlExecutionPlanTest:351`, `AndStepRecogniserTest:162`, `NotStepRecogniserTest:202`). `grep -rl "MatchPatternBuilder" core/src/main/java` returns eleven files: the builder itself, `MatchEdgePathItems`, `GqlMatchPatternAssembler`, and eight translator-package files (`EdgeHopRecogniser`, `GremlinPatternAssembler`, `GremlinStepWalker`, `NotStepRecogniser`, `RecognitionContext`, `SubTraversalPredicateAdapter`, `VertexHopRecogniser`, `WalkerContext`). `SQLMatchStatement` is absent.
- **Breaking change risk**: a builder-side change reaches the Gremlin translator and GQL; a change at `MatchExecutionPlanner.rebindFilters` additionally reaches SQL `MATCH` through `:2064`.
- **Verdict**: MATCHES
- **Detail**: The count and the negative both reproduce. Reference-accuracy caveat: grep over `core/src/main/java`; a consumer in another module or reached through a re-export would not appear. `GremlinToMatchStrategy` is itself absent from the eleven — it is the front-end, and the builder is reached through `WalkerContext` / `GremlinStepWalker`, which is what the track means by "the last two use `MatchPatternBuilder`".

#### C37 Premise: `rebindFilters`' two call sites, and which are on the common path
- **Track claim**: "`rebindFilters` — a private method of `MatchExecutionPlanner` called from `:2064` and `:5677`, both on the common path."
- **Search performed**: `grep -rn "rebindFilters" core/src`; reads of `createPlanForPattern` (`:2042-2075`), `buildPatterns` (`:5609-5680`), `SQLMatchStatement.buildPatterns` / `rebindFilters` (`:210-242`).
- **Code location**: `MatchExecutionPlanner.java:2064,5677,6012`; `SQLMatchStatement.java:226,232`
- **Actual behavior**: `private void rebindFilters(...)` at `:6012`, two call sites at `:2064` and `:5677` — the declaration count and site count are right. `:2064` sits in `createPlanForPattern`, reached by every front-end. `:5677` is the last statement of `buildPatterns(CommandContext)`, which opens `if (this.pattern != null) { if (promoteFilterRidsOnBuild) {…} return; }` at `:5610-5621`. Both additive constructors set `pattern` before planning, so `:5677` is SQL-path-only. Separately, `SQLMatchStatement` declares its own `private void rebindFilters(Map<String, SQLWhereClause>)` at `:232`, called from its `buildPatterns()` at `:226`, walking the statement's own `matchExpressions`.
- **Verdict**: WRONG
- **Detail**: Produces T23. The error is in "both on the common path" and in the implicit uniqueness of the name.

#### C38 Edge case: a Pattern-edge rebind over the additive path's items
- **Trigger**: item 2's named preferred option — rebinding over the `Pattern`'s edge items with `rebindFilters`' assignment semantics — applied to a translated `g.V().outE("created").has("weight", 0.4).inV()`.
- **Code path trace**:
  1. `EdgeHopRecogniser` translates the `has` containers into one AND-merged clause and calls `ctx.putEdgeFilter(edgeAlias, edgeWhere)` @ `EdgeHopRecogniser.java:137`, then `GremlinPatternAssembler.appendEdgeAsNode(..., edgeWhere)` @ `:144`.
  2. `WalkerContext.putEdgeFilter` writes `edgeFilters` @ `WalkerContext.java:452-453` — a map whose declaration comment reads "Populated by putEdgeFilter for observability; the same clause also travels on …" @ `:55-57`.
  3. `MatchPatternBuilder.addEdgeAsNode` → `MatchEdgePathItems.edgeMethodItem` sets the clause on the **edge alias's** `SQLMatchFilter` @ `MatchEdgePathItems.java:62-67`; `Pattern.addExpression` turns each item into a `PatternEdge` @ `Pattern.java:72`, so the edge item is reachable from a Pattern walk.
  4. `GremlinStepWalker.buildResult` composes `finalAliasFilters` from `ir.aliasFilters()` merged with `ctx.aliasFilters` @ `:470-476`. `edgeFilters` is not read. `aliasFilters.get("$g2m_edge_0")` is null.
  5. A rebind doing `item.getFilter().setFilter(aliasFilters.get(alias))` calls `SQLMatchFilter.setFilter(null)` @ `SQLMatchFilter.java:88`, which walks `items`, finds the entry whose `filter` is non-null, and assigns null.
- **Outcome**: the edge predicate is discarded. The traverser evaluates no edge `WHERE`, the query over-emits, and nothing declines — the same silent-wrong-result signature this track exists to remove, on a different filter.
- **Track coverage**: no. Item 2 names the option and its `matchExpressions`-avoidance rationale, and says nothing about merge-versus-overwrite or about edge-alias items.

#### C39 Premise: `rebindFilters` binds no class name
- **Track claim**: implicit in item 2 — that a planner-side fix at `rebindFilters` is a candidate for the four named acceptance shapes, which include `g.V(marko).out().hasLabel(software)`.
- **Search performed**: read of `rebindFilters` (`:6012-6022`); `grep -rn "setLeftClass\|leftClass\|rightClass" core/src/main/java/.../match/`; reads of `MatchExecutionPlanner:1957-1971,2072-2079` and `MatchReverseEdgeTraverser:20-67`.
- **Code location**: `MatchExecutionPlanner.java:6012-6022,2075`; `MatchReverseEdgeTraverser.java:51-67`
- **Actual behavior**: `rebindFilters` sets only `setFilter(...)` on the origin and on each item; there is no `setClassName` anywhere in it. The planner does annotate edges with a class, but `setLeftClass(aliasClasses.get(edge.edge.out.alias))` (`:2075`) is the **source** alias's class, consumed by `MatchReverseEdgeTraverser.targetClassName` which returns `edge.getLeftClass()` (`:53-55`). Forward traversal has no such annotation and falls back to the item AST. There is no `rightClass`.
- **Verdict**: PARTIAL
- **Detail**: A `rebindFilters`-shaped planner fix binds the `WHERE` and leaves the class dropped, so `hasLabel` on a post-hop alias stays broken. Feeds T20.

#### C40 Integration: GQL and the non-root-alias witness
- **Plan claim**: "A GQL `MATCH` with a filter on a non-root alias returns the corrected rows"; `## Interfaces and Dependencies` lists "a GQL non-root-alias witness" as in scope (new).
- **Actual entry point**: `GqlMatchStatement.buildPlan` @ `:80-91` → `GqlMatchPatternAssembler.fromFilters(matchFilters)` → `new MatchExecutionPlanner(ir.pattern(), ir.aliasClasses(), ir.aliasFilters())`.
- **Caller analysis**: `GqlMatchVisitor extends GQLBaseVisitor<Void>` (`:38`) overrides exactly one rule, `visitNode_pattern` (`:50`), accumulating `List<SQLMatchFilter> matchFilters` (`:47`). `GqlMatchPatternAssembler.add` calls only `builder.addNode(alias, effectiveType(...), filter.getFilter(), false)` (`:34-40`). `grep -rn "addEdge(\|addEdgeAsNode(" core/src/main/java` returns no GQL file. `GQL.g4:58` does declare `path_pattern: node_pattern (edge_pattern quantifier? node_pattern)*` with six edge alternatives at `:64-71`, so edges parse and are then dropped by the visitor. `GqlMatchStatementTest` contains no arrow syntax. `MatchExecutionPlanner` splits disconnected components into `subPatterns` (`:300-301`) and Cartesian-products them (`:668-674`), so each GQL node is independently rooted and its filter is read from `aliasFilters`.
- **Breaking change risk**: the real GQL exposure from a builder-side fix is IR and `prettyPrint` drift, pinned by `GqlMatchStatementPlanPrettyPrintTest` and `GqlMatchStatementTest`.
- **Verdict**: MISMATCHES
- **Detail**: Produces T21. The defect needs a path item; GQL produces none.

#### C41 Edge case: a post-optimization `NOT IN` strip on the additive path
- **Trigger**: item 2's constraint that "a post-optimization `NOT IN` strip still reaches the item AST", evaluated for a translated traversal.
- **Code path trace**:
  1. `detectNotInAntiJoin(targetFilter, targetAliasJ, boundAliases)` @ `MatchExecutionPlanner.java:4408`, inside the pre-filter pass of the optimize stage; declaration at `:4581`.
  2. The strip mutates `aliasFilters` — the field comment at `:465` says so and the 3-arg constructor defensively copies for exactly that reason.
  3. `rebindFilters(aliasFilters)` @ `:2064` pushes the stripped map into `matchExpressions`' items; the comment at `:2060-2063` states the purpose.
  4. On the additive path `matchExpressions` is empty (C35), so step 3 is a no-op.
  5. Producer check: `NotStepRecogniser` emits `notMatchExpressions`, not a `$currentMatch NOT IN $matched.X` clause; `GremlinPredicateAdapter.without` emits `key IS DEFINED AND NOT(key IN [literals])` (`:300`, `:322`), a value-list form the detector does not match.
- **Outcome**: on the Gremlin and GQL paths there is nothing to strip and nothing to push back today. On the SQL path the existing `:2064` push-back works and stays working unless the fix changes `rebindFilters`' body. The obligation is a SQL-`MATCH` no-regression clause.
- **Track coverage**: partial. The criterion exists; its scope is misattributed and no witness test is named. `HashJoinPlannerIntegrationTest:1197,2505` already carry `detectNotInAntiJoin` regressions.

#### C42 Integration: a populated item filter and the reverse traverser
- **Plan claim**: none stated — checked because the fix populates an AST slot both traversers can see.
- **Actual entry point**: `MatchReverseEdgeTraverser.java:51-67`
- **Caller analysis**: the reverse traverser overrides all three target reads — `targetClassName` → `edge.getLeftClass()`, `targetRid` → `edge.getLeftRid()`, `getTargetFilter` → `edge.getLeftFilter()`. The item's own filter is not consulted for the accept test; `traversePatternEdge` uses only `item.getMethod().executeReverse(...)` (`:78-80`). The planner sets the three `left*` values from `aliasClasses` / `aliasPinnedRids` / `aliasFilters` at `:2075-2077` and `:1957-1971`.
- **Breaking change risk**: none found. Populating the item filter does not double-filter or mis-filter a reverse-scheduled edge, because the reverse traverser reads the planner-supplied maps instead.
- **Verdict**: MATCHES

#### C43 Premise: the five surefire executions, their order, and the early-abort trap
- **Track claim**: "`core/pom.xml` binds five surefire executions to `test` in order — `default-test`, `sequential-tests`, `gremlin-process-compliance-tests`, `gremlin-structure-compliance-tests`, `gremlin-feature-compliance-tests` — so a bare `./mvnw -pl core test` stops at the third … (`/tmp/core-final2-track10.log:4624` is exactly that abort)."
- **Search performed**: `grep -n "<execution>\|<id>\|<phase>" core/pom.xml`; reads of `:228-292` (profile) and `:366-460` (base plugin); `sed -n '4615,4632p' /tmp/core-final2-track10.log`.
- **Code location**: `core/pom.xml:393` (`default-test`), `:418` (`sequential-tests`), `:250`, `:263`, `:276` (the three compliance executions, inside the `gremlin-compliance-suites` profile activated on `!test` at `:238-242`)
- **Actual behavior**: the two base executions are declared on the module's own surefire plugin; the three compliance ones arrive by profile injection, which appends profile executions after the model's own, so the effective order is the one the track states. The behavioural evidence confirms it: `/tmp/core-final2-track10.log:4616` reads `Tests run: 965, Failures: 16, Errors: 6, Skipped: 57` and `:4624` is `Failed to execute goal … (gremlin-process-compliance-tests) … There are test failures`, with the build ending there after 15:44 and never reaching the structure or feature executions.
- **Verdict**: CONFIRMED
- **Detail**: The reasoning for `-Dmaven.test.failure.ignore=true` on every full-suite gate is sound. Note the profile also deactivates whenever `-Dtest` is supplied (`:220-235` comment), so a `-Dtest=`-scoped run silently drops all three compliance executions — worth knowing during triage, not a defect in the track.

#### C44 Premise: `surefire:test@<id>` runs no lifecycle phase
- **Track claim**: "the Cucumber iteration loop uses the targeted `./mvnw -pl core -o surefire:test@gremlin-feature-compliance-tests` (20 s, versus ~31 min for the full suite)", and the same command is the first acceptance criterion.
- **Search performed**: `unzip -p ~/.m2/.../maven-surefire-plugin-3.5.6.jar META-INF/maven/plugin.xml` and inspection of the `test` mojo descriptor.
- **Code location**: `maven-surefire-plugin-3.5.6.jar!META-INF/maven/plugin.xml`, `<mojo><goal>test</goal>` block
- **Actual behavior**: the descriptor carries `<requiresDependencyResolution>test</requiresDependencyResolution>`, `<requiresProject>true</requiresProject>` and `<phase>test</phase>`, and no `executePhase` / `executeGoal` / `executeLifecycle` element. A direct goal invocation therefore executes that mojo alone — no `compile`, no `test-compile`. Classes come from `core/target/classes` and `core/target/test-classes` as last built.
- **Verdict**: WRONG
- **Detail**: Produces T22. The 20 s figure and the staleness are the same property.

#### C45 Premise: the Cucumber stall evidence and its control
- **Track claim**: "Both observed stalls stop at the same feature… So the first thing item 1 should test is whether the stall is the GQL feature itself under the translator, or that feature running after the upstream ones have accumulated state."
- **Search performed**: `grep -n "^\[INFO\] Running \|Tests run:"` over `/tmp/t9-cuke-off.log`, `/tmp/t9-cuke-on.log`, `/tmp/t9risk-cucumber-off.log`, `/tmp/t9risk-cucumber-on2.log`, `/tmp/t9risk-cuc-localonly-on.log`; `grep -m2 "Tests run:"` over the seven `/tmp/t9risk-bisect-*.log`; `sed -n '4705,4750p' /tmp/track10-final-verify.log`.
- **Code location**: `/tmp/t9-cuke-off.log:22,32`; `/tmp/t9-cuke-on.log:22`; `/tmp/t9risk-cuc-localonly-on.log:22,28`; `/tmp/track10-final-verify.log:~4726-4740`
- **Actual behavior**: the translator-**off** run that succeeds prints `[INFO] Running GQL Match Support` at line 22 and then `Tests run: 1930, Failures: 0, Errors: 0, Skipped: 14` in 17.05 s. The hung translator-on run prints the same line at the same position and stops. The line is surefire's per-test-class report header for the single cucumber-junit suite class, not per-feature progress. The local-features-only run with the translator **on** completes: `Tests run: 42, Failures: 0, Errors: 0, Skipped: 0` in 5.395 s. The per-directory numbers reproduce exactly — `filter` 369/10F, `map` 811/22F/6S, `integrated` 175/7F, `sideEffect` 204/3F/8S, `branch` 134 green, `semantics` 97 green, `data` 98 green; 1888 total with 42 failures and 14 skips, plus 42 local, summing to the 1930/14 the off-run reports. `track10-final-verify.log` reaches `Running GQL Match Support`, reports `Tests run: 0` and dies with `The forked VM terminated without properly saying goodbye` after 31:35, ending in `BUILD FAILURE` with `Error occurred in starting fork`.
- **Verdict**: WRONG
- **Detail**: Produces T19, and feeds T26 and T28. The A/B attribution to the strategy stands; the localization does not. What the evidence supports is that no individual scenario hangs and the fault is cumulative within one fork.

#### C46 Premise: the `embedded` runner's wiring and its stale-install hazard
- **Track claim**: "`-pl embedded` leaves `core` out of the reactor, so `youtrackdb-core:0.5.0-SNAPSHOT` resolves from the local repository — on the branch machine a jar installed 2026-07-02 … The `core` **test**-jar is equally load-bearing."
- **Search performed**: read of `embedded/pom.xml:50-110,400-448`, `embedded/src/test/.../EmbeddedGraphFeatureTest.java`, `ShadedJarSmokeTest.java`; `grep -n -A12 "maven-jar-plugin" core/pom.xml`; `ls -la ~/.m2/repository/io/youtrackdb/youtrackdb-core/0.5.0-SNAPSHOT/`.
- **Code location**: `embedded/pom.xml:54,69-75`; `core/pom.xml:478-490`; `~/.m2/repository/io/youtrackdb/youtrackdb-core/0.5.0-SNAPSHOT/`
- **Actual behavior**: `embedded` declares `youtrackdb-core` (compile) and `youtrackdb-core` `<type>test-jar</type>` (test), the latter commented "provides YTDBGraphInitUtil and other test helpers". `EmbeddedGraphFeatureTest` declares `features = {"classpath:/org/apache/tinkerpop/gremlin/test/features", "classpath:/com/jetbrains/youtrackdb/internal/core/gremlin/gremlintest/features"}` and extends `GraphFeatureWorld` from the core test-jar, with the same tag filter as `YTDBGraphFeatureTest` — so the same ~1930-scenario set. The local repository holds `youtrackdb-core-0.5.0-SNAPSHOT.jar` dated Jul 2 14:05 and `-tests.jar` dated Jul 2 14:11, confirming the clarification's date on both artifacts. `core/pom.xml:483-489` binds the `test-jar` goal with no phase override, so it runs at `package` and `-DskipTests` (which skips execution, not test compilation) still produces it. `embedded`'s surefire has one plain configuration with no executions and no skip, and the module has exactly two test classes (`EmbeddedGraphFeatureTest`, `ShadedJarSmokeTest`), both running at `test` before the shade plugin's `package` binding.
- **Verdict**: CONFIRMED
- **Detail**: The clarification and the `-pl core -am install -DskipTests` prerequisite are correct, including for the test-jar. Minor note for decomposition: `-pl embedded test` also runs `ShadedJarSmokeTest`, so the module's build result is not the feature runner's result alone.

#### C47 Premise: Track 10's handover arithmetic and the equivalence-suite gap
- **Track claim**: "21 `gremlin-process-compliance-tests` failures survive … Nine carry the dropped-filter signature (`HasTest` ×5, `AndTest` ×2, `WhereTest`, `SelectTest`); the other twelve are separate defects"; and "`EdgeTraversalEquivalenceTest` and `PredicateTraversalEquivalenceTest` are both green while those four return three rows".
- **Search performed**: read of `plan/track-10/core-compliance-failure-dispositions.md:48-63`; `grep -c "@Test"` and `grep -n "hasId\|hasLabel\|has(\"name\""` over both equivalence tests.
- **Code location**: `docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-10/core-compliance-failure-dispositions.md:50-63`; `core/src/test/java/.../strategy/EdgeTraversalEquivalenceTest.java`, `PredicateTraversalEquivalenceTest.java`
- **Actual behavior**: the dispositions table's item-2 rows sum to 5 + 2 + 1 + 1 = 9 and its item-4 rows to 2 + 2 + 1 + 1 + 1 + 1 + 1 + 1 + 1 + 1 = 12, total 21, with the class names and signatures the track lists. `EdgeTraversalEquivalenceTest` has 33 `@Test` methods covering bare and edge-as-node hops with no post-hop predicate; `PredicateTraversalEquivalenceTest` has 42, and every `hasLabel` / `hasId` / `has(key, value)` case it carries is rooted at `g.V()` — the class javadoc describes it as pinning the polymorphism contract at the boundary node. Neither suite exercises a predicate on a post-hop alias.
- **Verdict**: CONFIRMED
- **Detail**: The "watch it fail before production code changes" acceptance line is well-founded: the gap is real and the shapes are additions, not modifications.

#### C48 Premise: the third target read and whether the RID slot needs populating
- **Track claim**: the mechanism sentence names `item.getFilter().getFilter()` and `.getClassName()`; `## Validation and Acceptance` requires `g.V(marko).out().hasId(vadas)` to return one row.
- **Search performed**: read of `MatchEdgeTraverser:460-490`; `SQLMatchFilter.fromAliasAndClass` (`:65-76`); `MatchExecutionPlanner.buildPatterns`'s RID promotion comment (`:5655-5677`).
- **Code location**: `MatchEdgeTraverser.java:485-487`; `SQLMatchFilter.java:65-76`; `MatchExecutionPlanner.java:5666-5677`
- **Actual behavior**: `targetRid` is the third read and `matchesRid` the third conjunct of the accept test. `fromAliasAndClass` never populates the `rid` slot, so it is null on every additive-path item and `matchesRid` passes vacuously. The translator lowers `hasId` into an `@rid` / `@rid IN` term inside `aliasFilters`; `promoteStaticRidsFromFilters` copies it into `aliasPinnedRids` and the comment at `:5666-5673` states the term is left in `aliasFilters` on purpose, so binding the `WHERE` is sufficient for `hasId`.
- **Verdict**: PARTIAL
- **Detail**: The omission is documentary, not a defect in the plan — but the fix's behaviour on one of the four named acceptance shapes depends on it. Produces T27.
