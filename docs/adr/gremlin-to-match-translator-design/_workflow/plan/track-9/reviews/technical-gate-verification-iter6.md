<!-- MANIFEST
findings: 3   severity: {blocker: 0, should-fix: 2, suggestion: 1}
index:
  - {id: T34, sev: should-fix, loc: docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:102, anchor: "### T34 ", cert: C59, basis: "option 3's bullet omits that rebindFilters' loop runs zero times on the translated path, so a fix written inside its body ships the same silent no-op T29 removed from options 1 and 2"}
  - {id: T35, sev: should-fix, loc: docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:87, anchor: "### T35 ", cert: C61, basis: "the escalation branch's must-amend list for plan/track-11.md misses line 69, the Plan-of-Work item that performs the run and names the single-fork surefire execution, plus the line-52 diagram node"}
  - {id: T36, sev: suggestion, loc: docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:90, anchor: "### T36 ", cert: C55, basis: "three citations of GremlinStepWalker.buildResult and one of mergedTargetFilter are one line short of the code they name"}
verdicts:
  - {id: T20, verdict: VERIFIED}
  - {id: T21, verdict: VERIFIED}
  - {id: T24, verdict: VERIFIED}
  - {id: T29, verdict: VERIFIED}
  - {id: T30, verdict: VERIFIED}
  - {id: T31, verdict: VERIFIED}
  - {id: T32, verdict: VERIFIED}
  - {id: T33, verdict: VERIFIED}
overall: PASS
evidence_base: {section: "## Evidence base", certs: 8, matches: 4}
cert_index:
  - {id: C55, verdict: PARTIAL, anchor: "#### C55 "}
  - {id: C56, verdict: PARTIAL, anchor: "#### C56 "}
  - {id: C57, verdict: CONFIRMED, anchor: "#### C57 "}
  - {id: C58, verdict: CONFIRMED, anchor: "#### C58 "}
  - {id: C59, verdict: MISMATCHES, anchor: "#### C59 "}
  - {id: C60, verdict: CONFIRMED, anchor: "#### C60 "}
  - {id: C61, verdict: PARTIAL, anchor: "#### C61 "}
  - {id: C62, verdict: CONFIRMED, anchor: "#### C62 "}
flags: [CONTRACT_OK]
-->

# Track 9 technical gate verification — iteration 6

All eight findings under re-check verify. The T29 and T30 rewrites are factually right this time: every claim about `MatchPatternBuilder.addNode`, `build`, `appendFrom` and `mergedTargetFilter`, about `WalkerContext` and `SubTraversalPredicateAdapter`, about `GremlinStepWalker.buildResult`, `HasStepRecogniser` and `GqlMatchPatternAssembler` re-checks against source. Option 2 is viable and I confirmed the one thing that could have sunk it — `Pattern.copy()` shares its `SQLMatchPathItem` AST nodes rather than duplicating them, and the planner takes `inputs.pattern()` without a further copy, so a post-`build()` mutation reaches the objects `MatchEdgeTraverser` reads. Option 1 is coherent as an overload.

Three new items, none a blocker. Option 3's bullet inherits the defect T29 removed from the other two: `rebindFilters` at `:2064` iterates `matchExpressions`, which the translator leaves empty, so its loop body never runs on the path this track exists to fix. The escalation branch's `plan/track-11.md` amendment list names four lines and misses the Plan-of-Work item that actually performs the run. And four line pointers landed one line short of the code they name. The first two are decomposition-time amendments; the gate itself is clear.

**Tooling caveat — PSI unavailable, and for a new reason.** `steroid_list_projects` reports one open project, `design.md`; the IDE is not open on this working tree, so `steroid_execute_code` returns `Project not found: "youtrackdb"` rather than timing out as it did in the previous two rounds. Every symbol result below is `grep -n` plus an end-to-end read of the declaring file. The load-bearing negatives — "only `GqlMatchPatternAssembler` passes a non-null `where`", "`edgeFilters` has no reader", "`mergedTargetFilter` has one caller" — were each established by reading every site the grep returned across `core/src/main/java`, which bounds the usual grep risk without eliminating it. A caller in another module or reached by reflection would not appear. Re-verify through PSI at decomposition if the IDE is reopened on this tree.

## Verification certificates

#### Verify T20: the binding must be a merge, not a rebind
- **Original issue**: iteration 5 marked this a REGRESSION. The merge-not-rebind paragraph was correct, but its closing sentence claimed `MatchPatternBuilder.mergedTargetFilter` "already has that shape", which is false for the class half on the path this fix touches.
- **Fix applied**: the closing sentence was replaced by a full paragraph at `plan/track-9.md:96` distinguishing the `WHERE` merge (right template) from the class (wrong), with the polymorphic-mode consequence.
- **Re-check**:
  - Location: `plan/track-9.md:94,96,122,123`; `MatchExecutionPlanner.java:6012-6022`; `SQLMatchFilter.java:91-101`; `MatchEdgePathItems.java:62-66`; `WalkerContext.java:57,453`.
  - Current state: every premise of the merge-not-rebind paragraph re-verifies. `rebindFilters`' body is `item.getFilter().setFilter(aliasFilters.get(alias))` with no null guard (`:6018-6019`). `SQLMatchFilter.setFilter` walks `items`, finds the first with a non-null `filter`, and assigns the argument, so a null argument clears (`:91-101`). `MatchEdgePathItems.edgeMethodItem` builds `fromAliasAndClass(edgeAlias, null)` and calls `filter.setFilter(edgeFilter)` on it (`:62-66`), so the edge `WHERE` lives on the edge alias's path-item filter. `WalkerContext.edgeFilters` is written at `:453` and read nowhere — the only other `edgeFilters` occurrences in `core/src/main/java` are the javadoc mirror at `SubTraversalPredicateAdapter:103` and an unrelated local variable in `EdgeHopRecogniser:115-136`. Criterion 4 (`:122`) keeps `hasLabel(software)` on the class-binding side; criterion 5 (`:123`) pins the edge-alias shape before and after.
  - Criteria met: merge-not-overwrite, class-binding and edge-alias preservation are all stated, and the sentence that made this a REGRESSION is gone.
- **Regression check**: read the replacement paragraph against `mergedTargetFilter`'s source (C56). It is accurate in both halves now. Checked separately whether `SQLMatchFilter.copy()` preserves the alias, since the paragraph endorses the copy-not-replace template — it does: alias lives inside `items` (`getAlias`/`setAlias` at `:28-50`) and `SQLMatchFilterItem.copy` copies `alias` at `:201`. Clean.
- **Verdict**: VERIFIED

#### Verify T21: GQL cannot witness the fix
- **Original issue**: iteration 5 marked this a REGRESSION. The replacement rationale claimed a builder-side fix changes the IR GQL produces, which none of the three sites does.
- **Fix applied**: `## Context and Orientation` line 60 now says GQL stays in scope as a guard on the shared `SQLMatchFilter` mutators, walks through why none of the three sites reaches GQL's IR, and states the pin is expected to pass as a no-op. Criterion `:124` carries the same reframing. `GqlMatchPatternAssembler` moved to a new "Read and re-verified, not modified" line at `:146`.
- **Re-check**:
  - Location: `plan/track-9.md:60,124,146`; `GqlMatchPatternAssembler.java:33-45`; `MatchPatternBuilder.java:365,377`; `MatchExecutionPlanner.java:451-453`; `GqlMatchVisitor.java:75,80`.
  - Current state: each of the four negatives holds. GQL's assembler calls `builder.addNode` (`:39-40`) and `builder.build()` (`:44`) and nothing else, so its `Pattern` carries zero edges. `mergedTargetFilter`'s only call site is `buildNotExpression:365`, which GQL never invokes. The 3-arg planner constructor sets `this.matchExpressions = List.of()` at `:453`, so `rebindFilters` is a no-op for GQL. The positive claim holds too and is the reason to keep the pin: `GqlMatchVisitor` builds through `SQLMatchFilter.fromGqlNode` (`:75`), which delegates to `fromAliasAndClass` (`SQLMatchFilter:56`), and calls `filter.setFilter(whereClause)` (`:80`). Both mutators are named in the track's Signatures line. `GqlMatchStatementPlanPrettyPrintTest` and `GqlMatchStatementTest` both exist under `core/src/test/.../gql/parser/`.
  - Criteria met: the criterion's stated reason is now the reason that holds, and the in-scope classification matches.
- **Regression check**: checked that `## Interfaces and Dependencies` and the criterion agree. Line 146 says no fix site is reachable from `GqlMatchPatternAssembler` and that the prettyPrint tests run as a no-op guard; criterion `:124` says the same. No residue of the withdrawn "changes the IR GQL produces" claim survives — grepped both files for "IR GQL" and "GQL produces" and got nothing. Clean.
- **Verdict**: VERIFIED

#### Verify T24: both positive-path-item construction sites
- **Original issue**: iteration 5 marked this a REGRESSION. The two-construction-site option and the `build()`-level merge were both offered as workable, and neither has the per-alias `WHERE` where it runs.
- **Fix applied**: the option was moved off the numbered list into a standalone paragraph at `plan/track-9.md:104` that names both construction sites, states the timing objection first, and keeps the `addEdgeAsNode` coverage objection as the secondary reason. `## Interfaces and Dependencies` (`:145`) lists the three surviving options and no longer offers the construction sites.
- **Re-check**:
  - Location: `plan/track-9.md:50,104,145`; `MatchPatternBuilder.java:148,161,199-221`; `MatchEdgePathItems.java:84`.
  - Current state: the coverage fact still holds — `addEdge` builds `toFilter = fromAliasAndClass(toAlias, null)` at `:148` and assigns it at `:161`, while `addEdgeAsNode` routes through `MatchEdgePathItems.vertexMethodItem`, which does `item.setFilter(fromAliasAndClass(targetAlias, null))` at `:84`. Line 104's timing objection is the one C55 establishes. The mechanism paragraph at `:50` still names both sites, so the fact an implementer needs for any option that walks path items survives the demotion.
  - Criteria met: the disqualified option is off the list with both reasons recorded, and the enumeration an implementer picks from carries only options that can work.
- **Regression check**: verified that removing the option did not orphan the two-site fact. It is cited at `:50` (mechanism), `:104` (the demotion paragraph) and `:96` (the "every positive path item already carries a filter" premise), so the three places that depend on it each state it. Clean.
- **Verdict**: VERIFIED

#### Verify T29: the builder's `aliasFilters` is empty on the translated path
- **Original issue**: both builder-side options bound against `MatchPatternBuilder.aliasFilters`, which the translator never populates.
- **Fix applied**: `## Plan of Work` item 2 gained the "Where the data is, and where it is not" paragraph (`:90`) plus the class-half complement (`:92`), and the three candidate sites were re-cast as a numbered list (`:100-102`). Item 2's opening sentence (`:88`) now says the blast radius is the second constraint.
- **Re-check**:
  - Location: `plan/track-9.md:88,90,92,100-102`; `MatchPatternBuilder.java:93-114,288-298`; `WalkerContext.java:410-411`; `SubTraversalPredicateAdapter.java:240-247`; `GqlMatchPatternAssembler.java:39-40`; `GremlinStepWalker.java:469-477`; `HasStepRecogniser.java:162,167-169`.
  - Current state: every factual claim re-verifies (C55). `addNode` writes `aliasFilters` only under `if (where != null)` at `:110-112`. Fourteen `addNode` occurrences exist in `core/src/main/java`; of the 4-arg calls, `WalkerContext:411` and `SubTraversalPredicateAdapter:247` hard-code null, `appendFrom:292,296` re-read `source.aliasFilters`, and `GqlMatchPatternAssembler:39-40` is the sole non-null caller. Every 2-arg `RecognitionContext.addNode` call site (`ConnectiveStepSupport:60`, `StartStepRecogniser:121`, `GremlinPatternAssembler:54,99`, `HasStepRecogniser:162`) routes through one of the two null-passing implementations. The class half is the opposite case exactly as `:92` says. The ordering claim holds: `build()` runs before the `ctx.aliasFilters` merge in `buildResult`.
  - Criteria met: the enumeration now tells an implementer which options have the data and which do not, and why.
- **Regression check**: checked whether the re-cast list stays consistent with the merge-not-overwrite requirement, the class-binding requirement and the edge-alias constraint. Options 1 and 2 satisfy all three — both have `ir.aliasClasses()` or the builder's own `aliasClasses`, both can AND-compose, both can skip aliases absent from the map. Option 3 satisfies the data requirement (`MatchExecutionPlanner` holds both maps as fields, `:310` and the `MatchPlanInputs` constructor at `:534-540`) but its bullet omits the structural fact that its loop iterates nothing on the translated path — see T34. Four line pointers are one line short — see T36. Neither breaks the finding's fix.
- **Verdict**: VERIFIED

#### Verify T30: `mergedTargetFilter`'s dead class read
- **Original issue**: item 2 named `mergedTargetFilter` as a reference implementation that "already has that shape", when it never binds a class on any item this fix touches.
- **Fix applied**: `plan/track-9.md:96` replaces the claim with what the method does — right template for the `WHERE` merge and copy-not-replace, wrong for the class — plus the polymorphic-mode consequence.
- **Re-check**:
  - Location: `plan/track-9.md:96,122,145`; `MatchPatternBuilder.java:377-403`; `MatchEdgePathItems.java:84`; `HasStepRecogniser.java:158-166`; `SQLMatchFilter.java`.
  - Current state: `className` is read at `:381` and consumed only inside the `else` branch, at `:386` (`filter = SQLMatchFilter.fromAliasAndClass(alias, className);`). The `if (existingItemFilter != null)` branch at `:383-384` copies and sets no class. Every additive-path positive item carries a filter — `addEdge:161`, `MatchEdgePathItems:66` and `:84` — so the copy branch always fires. The `WHERE` merge (`:388-401`) and `mergeWhere` (`:405-408`) are as described. The polymorphic claim holds: `HasStepRecogniser` calls `ctx.addNode(boundary, labelClass)` at `:162` unconditionally and adds `WHERE.classEquals(labelClass)` only under `if (!ctx.polymorphic())` at `:163-164`.
  - Criteria met: the reference-implementation claim is corrected, the class-setting obligation is stated, and the failure it prevents is named.
- **Regression check**: the fix creates a downstream obligation — setting a class on a copied filter. `SQLMatchFilter` has `getClassName(CommandContext)` at `:118` but no class setter; the class lives as an `SQLMatchFilterItem.className` populated only by `fromAliasAndClass:65-79`. The track already books this: `## Interfaces and Dependencies` `:145` ends with "plus `SQLMatchFilter` if the merge needs a class-setting helper the copy path lacks". Correctly anticipated. The `:385` pointer is one line short of `:386` — see T36.
- **Verdict**: VERIFIED

#### Verify T31: the GQL pin's stated reason
- **Original issue**: the criterion and the Context paragraph both justified the GQL pin by claiming a builder-side fix changes GQL's IR.
- **Fix applied**: both were rewritten to the shared-mutator reason, and `GqlMatchPatternAssembler` moved out of "In scope (modified)".
- **Re-check**: covered by the T21 certificate above and C60 — the same edit satisfies both findings. Location: `plan/track-9.md:60,124,146`.
  - Current state: the pin is framed as a guard on `SQLMatchFilter.setFilter` and `fromAliasAndClass`, expected to pass as a no-op, with a failure interpreted as changed mutator semantics. That reading is correct and testable.
  - Criteria met: the stated justification now matches the code.
- **Regression check**: checked that moving `GqlMatchPatternAssembler` out of "In scope (modified)" left no dangling reference. The Signatures line still names `GqlMatchVisitor.visitNode_pattern`, which is a read target and belongs there. Clean.
- **Verdict**: VERIFIED

#### Verify T32: the escalation branch's complement coverage
- **Original issue**: the branch restated the Track 11 dependency in the two places already shape-agnostic and skipped `plan/track-11.md`, plus Track 9's own Purpose and criterion 13.
- **Fix applied**: item 1 gained "What the branch must amend, and what already tolerates it" (`:87`) naming `plan/track-11.md` lines 5, 9, 87 and 104 plus this track's Purpose and criterion 13. `## Purpose / Big Picture` gained an escalation complement (`:7`). Criterion 13 (`:129`) now reads against the relaxed gate. The `embedded` fallback shape is stated at `:85` as "whatever partition item 1 finds it runs in".
- **Re-check**:
  - Location: `plan/track-9.md:7,85,87,129`; `plan/track-11.md:5,9,87,104`.
  - Current state: all four named `plan/track-11.md` lines assert what the branch says they assert (C61). Line 9 says Track 9 "delivers a feature suite that completes"; line 87 is the headline criterion with "**completes**"; line 104 repeats "a feature suite that completes"; line 5 promises "the full TinkerPop Cucumber suite shows no regression". Track 9's Purpose complement at `:7` defers rather than drops the completion half and points at item 1's branch. Criterion 13 at `:129` now reads "Under item 1's escalation branch this criterion reads against the relaxed per-partition gate in criteria 1 and 2", and keeps the two-runner requirement unrelaxed. The `embedded` fallback shape at `:85` matches criterion 3's phrasing at `:121`.
  - Criteria met: every amendment the finding asked for landed.
- **Regression check**: swept `plan/track-11.md` for any completion-assuming site the four-line list misses. Two more exist — Plan-of-Work item 6 at `:69` and the diagram node at `:52` — see T35. Separately, the new paragraph at `:87` puts Track 9's own `## Interfaces and Dependencies` dependency line on the "already tolerates it" side and characterises it as saying "Track 9's post-fix baseline". That line (`:148`) actually reads "for a completing feature suite and a post-fix baseline", so the characterisation is loose, but no action follows: the same sentence carries its own escalation restatement clause, so skipping it under the branch is correct. Cosmetic.
- **Verdict**: VERIFIED — see T35 for the two uncovered `plan/track-11.md` sites

#### Verify T33: `putEdgeFilter`'s declaring types
- **Original issue**: "Signatures" attributed `putEdgeFilter` to `EdgeHopRecogniser`, which is a caller.
- **Fix applied**: the entry now reads `RecognitionContext.putEdgeFilter` (`:160`), implemented on `WalkerContext` (`:452`) and `SubTraversalPredicateAdapter` (`:290`), called from `EdgeHopRecogniser` (`:139`), with the captured-fragment note.
- **Re-check**:
  - Location: `plan/track-9.md:149`; `RecognitionContext.java:160`; `WalkerContext.java:452`; `SubTraversalPredicateAdapter.java:290`; `EdgeHopRecogniser.java:139`.
  - Current state: all four line numbers are exact (C62). The interface declaration is `void putEdgeFilter(String edgeAlias, SQLWhereClause where);` at `RecognitionContext:160`; `WalkerContext:452-454` writes `edgeFilters`; `SubTraversalPredicateAdapter:290-292` writes `capturedEdgeFilters`; `EdgeHopRecogniser:139` is `ctx.putEdgeFilter(edgeAlias, edgeWhere);`.
  - Criteria met: the declaring type, both implementations and the caller are each named where an implementer greps.
- **Regression check**: the same round added `MatchPatternBuilder.addNode` (`:93-114`), its three callers, `WalkerContext.putAliasFilter` / `HasStepRecogniser:156-171`, and `GremlinStepWalker.buildResult` to the same Signatures line. Verified each: `addNode` spans `:93-114`; `WalkerContext.addNode` spans `:410-412`; `SubTraversalPredicateAdapter.addNode`'s builder call is `:247`; `GqlMatchPatternAssembler:39` is the non-null caller; `HasStepRecogniser`'s contribution block runs `:157-170`, so `:156-171` brackets it. The `buildResult` sub-citations are one line short — see T36. The line is now long enough that a decomposition-time split into a short list plus a table would read better; cosmetic, not a finding.
- **Verdict**: VERIFIED

## Findings

### T34 [should-fix]
**Certificate**: C59 (`rebindFilters`' iteration source on the translated path)
**Location**: Track 9 `## Plan of Work` item 2, numbered option 3 (line 102); `MatchExecutionPlanner.java:2064,6012-6022`; `MatchPlanInputs.java:71`; `GremlinStepWalker.java:483-497`

**Issue**: Option 3 carries the same silent no-op that T29 removed from options 1 and 2, and its bullet does not say so.

The bullet reads: "already has both maps (`MatchPlanInputs.aliasFilters()` is `finalAliasFilters` post-merge) and is reached by all three front-ends, so it additionally moves SQL `MATCH`". Both halves are true of the *call site*. Neither is true of the *loop body*. `rebindFilters` iterates `matchExpressions`:

```java
private void rebindFilters(Map<String, SQLWhereClause> aliasFilters) {
  for (var expression : matchExpressions) {
    ...
    for (var item : expression.getItems()) { ... }
  }
}
```

`GremlinStepWalker.buildResult` never calls `MatchPlanInputs.Builder.matchExpressions`, and the record's compact constructor normalises the unset field to `List.of()` (`MatchPlanInputs:71`). So on the translated path the outer loop runs zero times, and an implementer who picks option 3, adds class binding to the body, and runs the four acceptance shapes gets three rows back — the identical failure mode T29 documented for the builder-side options.

The fact is in the track, twice: the mechanism paragraph at `:50` says `rebindFilters` "walks `matchExpressions`, which is empty on the additive translator path", and `:58` says a build-time pre-population "would leave that push-back still never running". Neither sentence is attached to the option an implementer picks from, and the numbered list is the artifact whose stated purpose is that "the implementer needs to know why". Options 1 and 2 each carry their disqualifying or enabling fact inline; option 3 does not.

The consequence is a wasted implementation cycle rather than a shipped defect, since acceptance criterion 4 (`:122`) fails on it. That is the same reasoning that graded T30 should-fix.

**Proposed fix**: Add one clause to option 3 saying that the existing `rebindFilters` body iterates `matchExpressions`, which is empty on both additive paths, so the planner-side fix must walk `pattern`'s edge items rather than reuse the existing loop — and that this is what makes it a second loop at the same call site rather than an edit to the SQL push-back. That also sharpens the blast-radius trade-off the bullet already states: a separate pattern walk touches SQL `MATCH` only by proximity, while editing the existing loop touches it for real.

### T35 [should-fix]
**Certificate**: C61 (`plan/track-11.md`'s completion-assuming sites)
**Location**: Track 9 `## Plan of Work` item 1's escalation branch (line 87); `plan/track-11.md:52,69`

**Issue**: The must-amend list names four `plan/track-11.md` lines and misses the one that performs the run.

`plan/track-11.md:69` is Plan-of-Work item 6: "**Re-run the full Cucumber suite — both runners — and show no regression against Track 9's post-fix baseline.** The suite runs from `YTDBGraphFeatureTest` under `core`'s `gremlin-feature-compliance-tests` execution and from `EmbeddedGraphFeatureTest` in the `embedded` module". Under the escalation branch that instruction is unexecutable as written: the named surefire execution is the single-fork invocation that does not terminate, and item 6 would have to run the `-Dcucumber.features=` partition instead. The four lines the branch does name (5, 9, 87, 104) are a Purpose sentence, a dependency sentence, an acceptance criterion and an interfaces line — statements about the run. Line 69 is the run.

`plan/track-11.md:52` is the smaller companion: the track diagram's node reads `item 6: full suite, no regression\nvs Track 9's post-fix baseline`.

Everything else in that file already tolerates either shape. Line 26's Decision Log entry says "Track 9 re-runs both runners after its final fix and publishes that artifact explicitly for this purpose", with no shape attached; line 103's out-of-scope entry and line 60's clarification are shape-neutral. The list is otherwise exactly right — I checked all sixteen occurrences of "complet", "Cucumber", "suite", "baseline" and "Track 9" in the file.

An incomplete must-amend list is the same defect T32 raised, so the amendment is worth making exhaustive rather than nearly so.

**Proposed fix**: Extend the branch's list to `plan/track-11.md` lines 5, 9, 52, 69, 87 and 104, and say for line 69 specifically that the named surefire execution is replaced by the partition shape item 1 published. Separately, `plan/track-11.md:60` still pins the bare `surefire:test@gremlin-feature-compliance-tests` for its iteration loop, which the `## Decision Log` rule at Track 9 `:28` would reject; that belongs to Track 11's own Phase A panel and is recorded here only so the panel inherits it.

### T36 [suggestion]
**Certificate**: C55 (`GremlinStepWalker.buildResult` line pointers), C56 (`mergedTargetFilter`'s class consumption)
**Location**: Track 9 `## Plan of Work` item 2 (lines 90, 96) and `## Interfaces and Dependencies` "Signatures" (line 149); `GremlinStepWalker.java:469,471-477`; `MatchPatternBuilder.java:386`

**Issue**: Four citations point one line short of the code they name.

- `:90` says the merge happens at `GremlinStepWalker.buildResult:470-476` after `build()` "has already run at `:468`". `build()` is at `:469`; `:468` is blank. The merge block is `:471-477` (`finalAliasFilters` declared at `:471`, the loop at `:475-477`).
- `:149` repeats both, inside the Signatures entry: "`:457-500`, where `finalAliasFilters` is merged at `:470-476`, after `build()` at `:468`". The method spans `:457-509`.
- `:96` says `mergedTargetFilter` "reads `aliasClasses` at `:381` but consumes the value only in the `else` branch at `:385`". The read is at `:381`; `:385` is `} else {` and the consumption is at `:386`.

Every behavioural conclusion drawn from these pointers is correct, and a reader who opens the file self-corrects in a second. The drift is worth fixing because this track's pointers are cited by three downstream documents and because the four wrong numbers were inherited verbatim from iteration 5's own certificates, which is how a pointer error survives a review round.

**Proposed fix**: `:469` for `build()`, `:471-477` for the merge, `:457-509` for `buildResult`, `:386` for the class consumption.

## Evidence base

#### C55 Premise: the builder's `aliasFilters` is GQL-only, and the walker's merge runs after `build()`
- **Track claim**: `plan/track-9.md:90` — "`MatchPatternBuilder.aliasFilters` is written in exactly one place — `addNode`, and only when the caller passes a non-null `where` (`:110-112`). Every translator caller passes null … The one non-null-`where` caller in `core/src/main/java` is `GqlMatchPatternAssembler:39` … reaches a map anyone can read only at `GremlinStepWalker.buildResult:470-476`, which merges it into `finalAliasFilters` **after** `ctx.patternBuilder.build()` has already run at `:468`."
- **Search performed**: `grep -rn "addNode(" core/src/main/java` (14 hits, every one read); full reads of `MatchPatternBuilder.addNode` (`:93-114`), `appendFrom` (`:288-309`) and `build()` (`:417-424`); `grep -n` for `buildResult`, `ctx.patternBuilder.build()`, `finalAliasFilters` and `MatchPlanInputs.builder` in `GremlinStepWalker.java`; reads of `WalkerContext:405-415,435-455`, `SubTraversalPredicateAdapter:240-252,285-295`, `HasStepRecogniser:150-175`, `GqlMatchPatternAssembler` end to end.
- **Code location**: `MatchPatternBuilder.java:110-112,292,296`; `WalkerContext.java:411`; `SubTraversalPredicateAdapter.java:247`; `GqlMatchPatternAssembler.java:39-40`; `GremlinStepWalker.java:469,471,476`
- **Actual behavior**: `addNode` writes `aliasFilters` only under `if (where != null)` at `:110-112`. Of the four 4-arg call sites, `WalkerContext:411` and `SubTraversalPredicateAdapter:247` pass a literal `null`, `appendFrom:292` and `:296` read `source.aliasFilters` (empty for the same reason on any translator-built source builder), and `GqlMatchPatternAssembler:39-40` passes `filter.getFilter()`. Every 2-arg `RecognitionContext.addNode` caller — `ConnectiveStepSupport:60`, `StartStepRecogniser:121`, `GremlinPatternAssembler:54` and `:99`, `HasStepRecogniser:162` — dispatches to one of the two null-passing implementations. The post-hop predicate reaches `WalkerContext.aliasFilters` through `putAliasFilter` (`:437-449`), written by `HasStepRecogniser:169`, and is merged into `finalAliasFilters` in `buildResult`. `grep -n` puts `var ir = ctx.patternBuilder.build();` at **`:469`**, `Map<String, SQLWhereClause> finalAliasFilters = new LinkedHashMap<>(ir.aliasFilters());` at **`:471`**, and the merge call at **`:476`**, with the loop closing at `:477`. `buildResult` spans `:457-509`. `aliasClasses` is the opposite case as `:92` states: `WalkerContext.addNode` forwards `className` and `HasStepRecogniser:162` re-types the boundary through it.
- **Verdict**: PARTIAL
- **Detail**: Every behavioural claim holds; the ordering conclusion the finding turns on is exactly right. Three line pointers (`:468`, `:470-476`, `:457-500`) are one line short — produces T36. Reference-accuracy caveat: grep over `core/src/main/java` plus an end-to-end read of every returned site; a caller in another module or reached by reflection would not appear. `MatchPatternBuilder` has eleven consumer files, all in `core`.

#### C56 Premise: `mergedTargetFilter` is the right `WHERE` template and the wrong class template
- **Track claim**: `plan/track-9.md:96` — "the right template for the `WHERE` merge and for copy-not-replace, and **not** for the class … It reads `aliasClasses` at `:381` but consumes the value only in the `else` branch at `:385`; every positive path item on the additive path already carries a filter (`addEdge:161`, `MatchEdgePathItems:84`) … `HasStepRecogniser:160-165` adds a `@class = 'L'` term to the `WHERE` **only** when `!ctx.polymorphic()`."
- **Search performed**: full read of `mergedTargetFilter` (`:377-403`), its single caller `buildNotExpression` (`:344-375`), `mergeWhere` (`:405-408`); `grep -n` for public members of `SQLMatchFilter` plus reads of `fromAliasAndClass` (`:65-79`), `getFilter` (`:82-90`), `setFilter` (`:91-101`), `copy` (`:242-248`), `getAlias`/`setAlias` (`:28-50`); `SQLMatchFilterItem.copy` (`:194-210`); `MatchEdgePathItems:55-86`; `HasStepRecogniser:150-175`.
- **Code location**: `MatchPatternBuilder.java:381,383-387`; `MatchEdgePathItems.java:66,84`; `MatchPatternBuilder.java:161`; `HasStepRecogniser.java:162-165`
- **Actual behavior**: `var className = aliasClasses.get(alias);` at `:381`; `if (existingItemFilter != null) { filter = existingItemFilter.copy(); }` at `:383-384`; `} else {` at `:385`; `filter = SQLMatchFilter.fromAliasAndClass(alias, className);` at **`:386`**. Every additive-path positive item carries a filter: `addEdge` assigns at `:161`, `edgeMethodItem` at `:66`, `vertexMethodItem` at `:84`. So the copy branch always fires and no class is bound. The `WHERE` merge at `:388-401` and the copy-not-replace behaviour are exactly as described, and `copy()` preserves the alias because alias lives in `items` (`SQLMatchFilterItem.copy:201`). `HasStepRecogniser` calls `ctx.addNode(boundary, labelClass)` unconditionally at `:162` and gates `whereExprs.add(WHERE.classEquals(labelClass))` on `if (!ctx.polymorphic())` at `:163-164`. `SQLMatchFilter` exposes `getClassName(CommandContext)` at `:118` and no class setter, which is why `## Interfaces and Dependencies` books a possible helper.
- **Verdict**: PARTIAL
- **Detail**: The characterisation is now correct in all three halves. The `:385` pointer is one line short of `:386` — produces T36.

#### C57 Integration: is option 2 viable, and does a defensive copy defeat it?
- **Plan claim**: `plan/track-9.md:101` — "A post-`build()` pass in `GremlinStepWalker.buildResult` over `ir.pattern()`'s edge items, using `finalAliasFilters` and `ir.aliasClasses()` — the first point at which both maps exist. Confined to the translator; does not move GQL or SQL `MATCH`."
- **Actual entry point**: `GremlinStepWalker.buildResult:469` → `MatchPlanInputs.builder(ir.pattern())` (`:484`) → `new MatchExecutionPlanner(MatchPlanInputs)` (`MatchExecutionPlanner:534`) → `createPlanForPattern` → `MatchEdgeTraverser`.
- **Caller analysis**: `PatternIR` is a record with `pattern`, `aliasClasses` and `aliasFilters` components (`MatchPatternBuilder:51-55`), so `ir.pattern()` and `ir.aliasClasses()` both exist and are local to `buildResult`. `build()` returns `pattern.copy()`, and `Pattern.copy()` (`:235-259`) creates new `PatternNode` and `PatternEdge` instances but assigns `edgeCopy.item = edge.item` — its own javadoc says "{@link SQLMatchPathItem} AST nodes are shared". The planner takes the pattern by reference: `this.pattern = inputs.pattern();` (`MatchExecutionPlanner:534`), with defensive copies applied to the three maps only. `getDisjointPatterns` (`Pattern:162-199`) reuses the same `PatternNode` objects in each component, so the items survive that split too. `MatchEdgeTraverser` reads `item.getFilter().getFilter()` (`:476`), `.getClassName(ctx)` (`:481`) and `.getRid(ctx)` (`:486`) off those same objects.
- **Breaking change risk**: none for GQL or SQL `MATCH` — the pass lives in `buildResult`, which only the translator reaches. One nuance the track does not raise and does not need to: binding the `WHERE` onto the path item while leaving it in `finalAliasFilters` applies a conjunctive predicate twice, which is idempotent and is what `rebindFilters` already does on the SQL path.
- **Verdict**: CONFIRMED
- **Detail**: Option 2 is pickable as written. The deep-copy hazard the verification focus asked about does not exist, because the copy is node-and-edge-deep and item-shallow.

#### C58 Integration: is option 1 coherent given `build()`'s contract and callers?
- **Plan claim**: `plan/track-9.md:100` — "A merge inside `MatchPatternBuilder.build()` over the assembled `Pattern`'s edge items — viable only if the merged map is threaded in explicitly (a `build(Map<String, SQLWhereClause>)` overload)."
- **Actual entry point**: `build()` at `MatchPatternBuilder:417-424`, returning `PatternIR(pattern.copy(), unmodifiableMap(aliasClasses), unmodifiableMap(aliasFilters))` and setting the one-shot `built` flag.
- **Caller analysis**: `grep -rn "\.build()"` filtered to builder consumers returns exactly two production callers — `GremlinStepWalker:469` and `GqlMatchPatternAssembler:44` (reached through `fromFilters:30`). An overload is therefore additive: GQL keeps the no-arg form untouched, which is what the T31 no-op expectation rests on. Inside the overload the builder can AND-merge the passed map with its own `aliasFilters` field, apply the result to the assembled pattern's items, and return the merged map as `PatternIR.aliasFilters`, so `buildResult` would drop its own `:471-477` merge loop rather than duplicate it. `checkNotBuilt` and the `built` flag are unaffected.
- **Breaking change risk**: the overload widens `MatchPatternBuilder`'s public surface, which eleven `core` files consume; no existing caller changes.
- **Verdict**: CONFIRMED
- **Detail**: Option 1 is pickable. It costs one more public method than option 2 and buys nothing option 2 lacks, but the track does not claim otherwise.

#### C59 Premise: does option 3's loop run on the translated path?
- **Plan claim**: `plan/track-9.md:102` — "The planner-side site, `MatchExecutionPlanner.rebindFilters` at `:2064` — already has both maps … and is reached by all three front-ends."
- **Search performed**: read of `rebindFilters` (`:6011-6022`) and its `:2064` call site in `createPlanForPattern` (`:2055-2070`); `grep -n "aliasClasses"` over `MatchExecutionPlanner.java`; read of the `MatchPlanInputs` constructor (`:534-560`); `grep -n "matchExpressions"` over `MatchPlanInputs.java`; read of `buildResult`'s `MatchPlanInputs.builder` chain (`GremlinStepWalker:483-497`).
- **Code location**: `MatchExecutionPlanner.java:2064,6012-6022,310,534-540`; `MatchPlanInputs.java:71`; `GremlinStepWalker.java:483-497`
- **Actual behavior**: the maps claim holds — `aliasClasses` is a field at `:310`, populated from `inputs.aliasClasses()` at `:539`, and `aliasFilters` from `inputs.aliasFilters()` at `:540`, which is `finalAliasFilters`. The reachability claim holds for the call site. The loop does not: `rebindFilters` iterates `matchExpressions`, and `buildResult`'s builder chain sets `notMatchExpressions` but never `matchExpressions`, which the `MatchPlanInputs` compact constructor normalises to `List.of()` at `:71`. Zero iterations on the translated path. The track states this fact twice elsewhere (`:50` and `:58`) and does not attach it to the option.
- **Verdict**: MISMATCHES
- **Detail**: Produces T34. The bullet is not false; it is incomplete in the one dimension T29 established as decisive for the other two options.

#### C60 Integration: does any of the three sites change GQL's IR, and what does GQL share?
- **Plan claim**: `plan/track-9.md:60,124` — GQL stays in scope "as a **guard on the shared mutators**, and not because any of item 2's three sites changes its IR (T31) … What GQL *does* share is `SQLMatchFilter.setFilter` and `fromAliasAndClass`, which `GqlMatchVisitor` builds through".
- **Actual entry point**: `GqlMatchStatement.buildPlan` → `GqlMatchPatternAssembler.fromFilters` (`:25-31`) → `new MatchExecutionPlanner(ir.pattern(), ir.aliasClasses(), ir.aliasFilters())`.
- **Caller analysis**: the assembler (54 lines, read end to end) calls `builder.addNode` at `:39-40` and `builder.build()` at `:44`; nothing else. Its `Pattern` therefore holds nodes and no edges, so options 1 and 2 iterate an empty edge collection. Option 3 is `rebindFilters`, whose loop the 3-arg constructor empties (`this.matchExpressions = List.of();` at `MatchExecutionPlanner:453`). `mergedTargetFilter`'s only call site is `buildNotExpression:365`, which GQL never reaches. The shared-mutator exposure is real and precise: `GqlMatchVisitor:75` builds each filter through `SQLMatchFilter.fromGqlNode`, which is a one-line delegate to `fromAliasAndClass` (`SQLMatchFilter:55-57`), and `:80` calls `filter.setFilter(whereClause)`.
- **Breaking change risk**: a change to `setFilter` or `fromAliasAndClass` semantics reaches GQL. `GqlMatchStatementPlanPrettyPrintTest` and `GqlMatchStatementTest` both exist under `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gql/parser/` and pin it.
- **Verdict**: CONFIRMED

#### C61 Premise: which `plan/track-11.md` sites assume a completing suite
- **Track claim**: `plan/track-9.md:87` — "`plan/track-11.md` at lines 5, 9, 87 and 104 — its Purpose, its Track 9 dependency sentence, its headline acceptance criterion … and its `## Interfaces and Dependencies` line."
- **Search performed**: `grep -n "complet\|Cucumber\|Track 9\|full suite\|baseline\|suite"` over `plan/track-11.md` (16 hits, each read in context); reads of `plan/track-9.md` `## Purpose / Big Picture`, item 1's branch, and criteria 1, 2, 3 and 13.
- **Code location**: `plan/track-11.md:5,9,52,69,87,104`
- **Actual behavior**: the four named lines each assert what the branch says. Two further sites do too. Line 69 is Plan-of-Work item 6, which instructs a re-run of "the full Cucumber suite" and names `core`'s `gremlin-feature-compliance-tests` execution — the single-fork invocation that does not terminate. Line 52 is the diagram node `item 6: full suite, no regression`. The remaining hits are shape-agnostic: line 26's Decision Log entry says "re-runs both runners after its final fix" with no shape, line 60 is a command clarification, line 86 is the order-scope criterion, lines 98 and 103 are bookkeeping. On the Track 9 side, criterion 13 (`:129`) and the Purpose complement (`:7`) both landed; the `embedded` fallback shape at `:85` matches criterion 3's wording at `:121`.
- **Verdict**: PARTIAL
- **Detail**: Produces T35. Four of six sites named; the two missed are the instruction that performs the run and the diagram node depicting it.

#### C62 Premise: `putEdgeFilter`'s declaring type and the new Signatures entries
- **Track claim**: `plan/track-9.md:149` — "`RecognitionContext.putEdgeFilter` (`:160`), implemented on `WalkerContext` (`:452`) and `SubTraversalPredicateAdapter` (`:290`), called from `EdgeHopRecogniser` (`:139`)", plus `MatchPatternBuilder.addNode` (`:93-114`), `WalkerContext.addNode` (`:410-412`), `SubTraversalPredicateAdapter.addNode` (`:247`), `GqlMatchPatternAssembler` (`:39`), `HasStepRecogniser:156-171`.
- **Search performed**: `grep -rn "edgeFilters" core/src/main/java`; targeted `sed` reads of `RecognitionContext:152-165`, `WalkerContext:405-415,435-458`, `SubTraversalPredicateAdapter:240-252,285-295`, `EdgeHopRecogniser:130-145`, `HasStepRecogniser:150-175`, `MatchPatternBuilder:93-114`.
- **Code location**: `RecognitionContext.java:160`; `WalkerContext.java:452`; `SubTraversalPredicateAdapter.java:290`; `EdgeHopRecogniser.java:139`; `MatchPatternBuilder.java:93-114`; `WalkerContext.java:410-412`; `SubTraversalPredicateAdapter.java:247`; `GqlMatchPatternAssembler.java:39`; `HasStepRecogniser.java:157-170`
- **Actual behavior**: every number is exact. The interface declaration sits at `RecognitionContext:160` under a javadoc that itself records the observability framing and the "filter also travels on the edge path item" note. `WalkerContext:452-454` writes `edgeFilters`, declared at `:57` with the observability javadoc at `:55-57`; `SubTraversalPredicateAdapter:290-292` writes `capturedEdgeFilters`, mirrored at `:103`. `EdgeHopRecogniser:139` is the caller. The observability-only claim holds under grep: the only `edgeFilters` occurrences in `core/src/main/java` are the field, its javadoc, the write, the `SubTraversalPredicateAdapter` mirror javadoc, and an unrelated `ArrayList<SQLBooleanExpression>` local in `EdgeHopRecogniser:115-136`. `HasStepRecogniser`'s contribution block runs `:157-170`, which `:156-171` brackets.
- **Verdict**: CONFIRMED
