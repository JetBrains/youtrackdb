<!-- MANIFEST
findings: 11   severity: {blocker: 2, should-fix: 7, suggestion: 2}
index:
  - {id: T1, sev: blocker,    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/AbstractMatchPlanStep.java:694, anchor: "### T1 ", cert: C3, basis: "projectOrSkip is a per-row projector; a LIST arm cannot express fold and erases the per-element projection mode"}
  - {id: T2, sev: blocker,    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RecognitionContext.java:270, anchor: "### T2 ", cert: C10, basis: "no read accessor for the current ResultShaping; terminators cannot append an op or compose two"}
  - {id: T3, sev: should-fix, loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/UnionStepRecogniser.java:108, anchor: "### T3 ", cert: C17, basis: "union child agreement rests on op reference identity; a value-equal fold/tail op turns decline into one list over the concatenation"}
  - {id: T4, sev: should-fix, loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinStepWalker.java:172, anchor: "### T4 ", cert: C7, basis: "FoldStep also carries fold(seed, biOperator); registering the class alone mistranslates it as a list drain"}
  - {id: T5, sev: should-fix, loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/ListShapingOp.java:22, anchor: "### T5 ", cert: C7, basis: "UnfoldStep.flatMap also handles Map (entrySet), arrays and scalars; groupCount().unfold() is a common Cucumber shape"}
  - {id: T6, sev: should-fix, loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/ListShapingOp.java:35, anchor: "### T6 ", cert: C9, basis: "carrier javadoc says apply runs once per child plan; the multi-plan step applies shaping once over the concatenation"}
  - {id: T7, sev: should-fix, loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/AbstractMatchPlanStep.java:386, anchor: "### T7 ", cert: C16, basis: "op instances are shared across re-arms and across concurrent clones; no acceptance line covers either"}
  - {id: T8, sev: should-fix, loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/gql/parser/GqlMatchStatement.java:88, anchor: "### T8 ", cert: C12, basis: "GQL rides the same builder and the same empty-matchExpressions planner entry, so item 1a's fix moves GQL too"}
  - {id: T9, sev: should-fix, loc: jmh-ldbc/src/main/java/com/jetbrains/youtrackdb/benchmarks/ldbc/LdbcBenchmarkState.java:53, anchor: "### T9 ", cert: C14, basis: "the module's state requires the LDBC SF1 dataset, so no baseline is capturable locally"}
  - {id: T10, sev: suggestion, loc: docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:52, anchor: "### T10 ", cert: C2, basis: "Pre-Flight says BoundaryOutputType has three constants; it has four, and three sibling javadocs are stale"}
  - {id: T11, sev: suggestion, loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RangeGlobalStepRecogniser.java:88, anchor: "### T11 ", cert: C20, basis: "TailGlobalStepPlaceholder.getLimit() pins the GValue variable before returning, including on the decline path"}
evidence_base: {section: "## Evidence base", certs: 20, matches: 12}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: PARTIAL, anchor: "#### C2 "}
  - {id: C3, verdict: CONFIRMED, anchor: "#### C3 "}
  - {id: C4, verdict: CONFIRMED, anchor: "#### C4 "}
  - {id: C5, verdict: CONFIRMED, anchor: "#### C5 "}
  - {id: C6, verdict: CONFIRMED, anchor: "#### C6 "}
  - {id: C7, verdict: PARTIAL, anchor: "#### C7 "}
  - {id: C8, verdict: CONFIRMED, anchor: "#### C8 "}
  - {id: C9, verdict: WRONG, anchor: "#### C9 "}
  - {id: C10, verdict: NOT FOUND, anchor: "#### C10 "}
  - {id: C11, verdict: CONFIRMED, anchor: "#### C11 "}
  - {id: C12, verdict: CALLERS AT RISK, anchor: "#### C12 "}
  - {id: C13, verdict: CONFIRMED, anchor: "#### C13 "}
  - {id: C14, verdict: PARTIAL, anchor: "#### C14 "}
  - {id: C15, verdict: CONFIRMED, anchor: "#### C15 "}
  - {id: C16, verdict: n/a, anchor: "#### C16 "}
  - {id: C17, verdict: n/a, anchor: "#### C17 "}
  - {id: C18, verdict: CONFIRMED, anchor: "#### C18 "}
  - {id: C19, verdict: CONFIRMED, anchor: "#### C19 "}
  - {id: C20, verdict: n/a, anchor: "#### C20 "}
flags: [CONTRACT_OK]
-->

# Track 9 technical review — iteration 1

Every class, interface, and surefire execution the track names resolves in the working tree, and the two claims it flagged for re-verification at decomposition — the `TailGlobalStepContract` placeholder pair and the TinkerPop fork's `fold` / `unfold` / `reverse` semantics — hold. Two blockers sit in the mechanism the track picked for `fold` and in a walker seam that does not yet exist. Seven should-fix findings cover silent-wrong-result paths the plan does not gate, plus one deliverable that cannot be produced under the track's own local-only constraint.

**Tooling caveat.** `steroid_execute_code` times out on this repository, so every symbol audit below used `find` / `grep` / `javap` on the resolved fork jar rather than PSI. Reference accuracy is high for declarations (literal-text reads) and for the negatives established by reading candidate call sites end-to-end (`projectOrSkip`, `mergedTargetFilter`, `MatchPatternBuilder` consumers). Grep cannot prove a universal negative; where a finding rests on "the only reader", the certificate records how the negative was checked.

## Findings

### T1 [blocker]
**Certificate**: C3 (`projectOrSkip` exhaustiveness), C4 (Track 7 carrier), C19 (`PostConcatOp` javadoc)
**Location**: Track 9 Plan of Work item 2 and `## Interfaces and Dependencies` "In scope (new)"; `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/AbstractMatchPlanStep.java:694`; `.../step/BoundaryOutputType.java:23`

**Issue**: Item 2 says "`FoldStep` recogniser → `BoundaryOutputType.LIST`; extend the exhaustive `projectOrSkip` switch with a `LIST` case + a drain stage". The first half is the wrong mechanism and the second half has no implementable body.

`projectOrSkip(Result row)` is a per-row projector: one MATCH row in, one traverser payload (or the `SKIP` sentinel) out. `fold()` is an N→1 drain over the whole payload stream, which a per-row function cannot express. A `case LIST ->` arm would have nothing to return.

Worse, `outputType` is the only thing that tells the boundary how to project each element. `g.V().fold()` needs `ELEMENT` per row and then a fold; `g.V().values("name").fold()` needs `SINGLE_VALUE` per row and then a fold; `g.V().valueMap().fold()` needs `MAP`. Re-pinning `outputType` to `LIST` erases exactly the information the drain needs to build the list's contents.

The repository already carries the right home, and says so. Track 7's `ListShapingOp` javadoc classifies `fold` as an "N→1 / window drain — several payloads in, one or a bounded window out (`fold` drains the whole stream into one list; `tail(n)` keeps the last `n`)". `AbstractMatchPlanStep.applyListShaping` (line 386) threads those ops over whichever source `openShapedPayloads` selected. Track 8's `PostConcatOp` javadoc names the split from the other side: these are "not the Track 9 list-shaping terminators (`fold`/`unfold`/`reverse`/`tail`), which ride `ResultShaping#listShapingOps()`".

The track's `## Context and Orientation` already half-states the correct answer ("`fold`/`unfold`/`tail` are barrier / flat-map / window transforms that need a drain / flat-map / ring-buffer stage … not per-row `projectOrSkip` cases") and then item 2 contradicts it. The remaining pre-split language is also wrong on the neighbouring detail: the drain does **not** belong "alongside the existing group `accumulateMap` branch". `accumulateMap` selects a *source* in `openShapedPayloads`; a third source branch there would sit outside `applyListShaping` and could not order `reverse().unfold()` against `unfold().reverse()`, which is the whole reason Track 7 built an ordered carrier.

**Proposed fix**: Drop `BoundaryOutputType.LIST` and the `projectOrSkip` `LIST` case from item 2, from `## Context and Orientation`, and from `## Interfaces and Dependencies`. Rewrite item 2 as: `FoldStep` recogniser registers a `ListShapingOp` that drains the upstream payload iterator into one `List` and emits it as a single payload (a dry upstream still emits one empty list), leaving `outputType` at whatever the preceding terminator pinned. No enum change, no switch change, no compile break to work around. Keep the `## Context and Orientation` note that a *hypothetical* enum addition would break the switch, but restate it as the reason not to add one.

### T2 [blocker]
**Certificate**: C10 (`RecognitionContext` shaping accessor)
**Location**: Track 9 Plan of Work items 2–3 and `## Interfaces and Dependencies` "In scope (modified)"; `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RecognitionContext.java:270`

**Issue**: The four terminator recognisers have to *append* to `ResultShaping.listShapingOps()`, and `RecognitionContext` gives them no way to read what is there.

The interface exposes `void setResultShaping(@Nonnull ResultShaping shaping)` and nothing else, and its javadoc pins the current contract: "A terminator builds the exact combination from `ResultShaping#NONE` plus its overrides and **calls this once**". Two of the track's own accepted shapes break that contract:

- `g.V().values("name").fold()` — `PropertiesStepRecogniser` has already pinned `dropOnAbsent` plus the presence keys. A `FoldStep` recogniser that calls `setResultShaping(ResultShaping.NONE.withListShapingOps(...))` silently wipes them; one that wants to preserve them has no accessor to read them from.
- `reverse().unfold()` and `unfold().reverse()` — two ops must land in one ordered list. The second recogniser cannot see the first one's op.

`WalkerContext` holds the field (`ResultShaping shaping = ResultShaping.NONE`, line 128) and a package-private `shaping()` reader (line 583), but neither is on the `RecognitionContext` interface the recognisers see. `SubTraversalPredicateAdapter.setResultShaping` swallows the call outright (line 397), so whatever is added needs a defined answer there too.

The track's "In scope (modified)" list names `GremlinStepWalker`, the boundary base, `BoundaryOutputType`, and the item-1a sites. It does not name `RecognitionContext`, `WalkerContext`, or `SubTraversalPredicateAdapter`.

**Proposed fix**: Add the seam to item 3 and to `## Interfaces and Dependencies` "In scope (modified)". Preferred shape: `void appendListShapingOp(@Nonnull ListShapingOp op)` on `RecognitionContext`, implemented on `WalkerContext` as `shaping = shaping.withListShapingOps(<existing + op>)`, so no recogniser can accidentally overwrite a sibling terminator's flags. Decide and document `SubTraversalPredicateAdapter`'s behaviour explicitly — swallowing is defensible for a sub-walk, but a swallowed append inside a combinator child means the child silently loses a list-shaper it appeared to accept, so a decline is safer. Update the `setResultShaping` javadoc's "calls this once" clause in the same commit.

### T3 [should-fix]
**Certificate**: C17 (union child carrying a list-shaping op), C19 (`PostConcatOp` record-singleton house style)
**Location**: Track 9 Plan of Work item 4 and the third `## Validation and Acceptance` bullet; `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/UnionStepRecogniser.java:106-111`

**Issue**: `union(__.out().fold(), __.in().fold())` will translate to one list over the concatenation where native produces one list per child, and the only thing standing between the track and that silent wrong answer is lambda reference identity.

`UnionStepRecogniser` walks each child to a full single-plan translation and then compares `!agreedShaping.equals(childResult.shaping())`. Nothing else gates a child's list-shaping ops. `ResultShaping` is a record, so `equals` compares `listShapingOps` element-wise:

- If `fold` / `tail` ops are implemented as lambdas or per-call `new` instances, two children each carrying one produce unequal lists, the agreement check declines, and the wrong answer never reaches a user. Safe by accident.
- If they are implemented as record singletons — which is this codebase's own house style for exactly this kind of op, see `PostConcatOp.Count.INSTANCE` and `PostConcatOp.Dedup.INSTANCE`, and the natural shape for a parameterised `tail` is `record TailOp(long n)` — the lists compare equal, the union accepts, and `MultiPlanMatchStep` applies one fold over the whole concatenation.

Nothing in the child walk tells the fork it is a union child, so `D3`'s last-step rule does not stop it: from the child fork's point of view the `fold()` *is* the last step. The result is a correctness gate that depends on an implementation detail Track 9 is free to change without noticing.

`unfold` and `reverse` are unaffected (both are per-payload, so once-over-the-concatenation and once-per-child coincide). Only the two window drains, `fold` and `tail`, diverge.

**Proposed fix**: Add an explicit gate to item 4 and a matching acceptance line: `UnionStepRecogniser` declines when any child's `shaping().listShapingOps()` is non-empty, checked before and independently of the `agreedShaping.equals` comparison. Add `union(__.out().fold(), __.in().fold())` and `union(__.out().tail(1), __.in().tail(1))` to the item-5 test list as decline cases, so the gate is witnessed rather than inferred. Add `UnionStepRecogniser` to `## Interfaces and Dependencies` "In scope (modified)".

### T4 [should-fix]
**Certificate**: C7 (`FoldStep` reference semantics)
**Location**: Track 9 Plan of Work item 2; `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinStepWalker.java:143-172` (registry)

**Issue**: `FoldStep` is two steps wearing one class. `javap` on the resolved fork jar shows two constructors —`FoldStep(Traversal.Admin)` (the list fold) and `FoldStep(Traversal.Admin, Supplier<E>, BiFunction<E,S,E>)` (the seeded reduce that `fold(seed, operator)` compiles to) — and a `public boolean isListFold()` accessor reading a `listFold` boolean field that distinguishes them.

Under D9 the registry keys on the concrete runtime class, so a `Map.entry(FoldStep.class, FoldStepRecogniser.INSTANCE)` claims both forms. A recogniser that maps `FoldStep` unconditionally to a list drain turns `g.V().values("age").fold(0, Operator.sum)` — native: one summed scalar — into a list of ages. It translates, so the wrong answer is silent, the same failure mode as item 1a.

The track's `## Context and Orientation` and item 2 both describe `fold()` only as "drain the stream into one `List` (empty input → empty list)" and never mention the seeded form.

**Proposed fix**: State in item 2 that the recogniser declines when `!step.isListFold()`. Add `fold(seed, operator)` to the item-5 test list as a decline case and to `## Validation and Acceptance`.

### T5 [should-fix]
**Certificate**: C7 (`UnfoldStep.flatMap` reference semantics)
**Location**: Track 9 `## Context and Orientation`, Plan of Work item 3, and the first `## Validation and Acceptance` bullet; `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/ListShapingOp.java:22`

**Issue**: The track describes `unfold()` as "flat-maps per emission" and `ListShapingOp`'s javadoc as "`unfold` expands a list payload into its elements". `UnfoldStep.flatMap`'s actual bytecode dispatches five ways on the traverser value:

1. `Iterator` → returned as-is
2. `Iterable` → `.iterator()`
3. **`Map` → `.entrySet().iterator()`**
4. array → per-element iterator (`handleArrays`, both `Object[]` and primitive arrays via reflection)
5. anything else → `IteratorUtils.of(value)`, a one-element iterator

Case 3 is the one that matters here. `MAP` is a live boundary output type: `group()`, `groupCount()`, `valueMap()`, `elementMap()`, `project()`, and multi-alias `select()` all pin it, and `AbstractMatchPlanStep.projectMap` / `accumulatedGroupMapSource` emit `LinkedHashMap` payloads. `g.V().groupCount().unfold()` and `g.V().valueMap().unfold()` are ordinary Gremlin idioms and are represented in the TinkerPop feature suite this track has to turn green. An `unfold` op written to the track's description would leave a Map payload unchanged instead of emitting its entries.

Case 5 matters too: `g.V().unfold()` over `ELEMENT` payloads must pass each vertex through unchanged, not drop it.

**Proposed fix**: Pin the full five-way contract in item 3, correct the `ListShapingOp` javadoc's one-line description of `unfold`, and add Map / array / scalar payload cases to item 5's test list and to the `## Validation and Acceptance` `unfold` line — at minimum `groupCount().unfold()` and `valueMap().unfold()` matched against native.

### T6 [should-fix]
**Certificate**: C9 (`MultipleExecutionStream` concatenation vs the carrier javadoc)
**Location**: Track 9 Plan of Work item 4; `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/ListShapingOp.java:35`

**Issue**: Item 4's load-bearing claim is correct and the in-repo documentation an implementer will read contradicts it.

The claim holds. `MultiPlanMatchStep.startPlanStream()` returns one `MultipleExecutionStream` over a lazy per-child producer, the base's `openShapedPayloads()` runs once per arming over that single stream, and `MultiPlanMatchStep`'s class javadoc states the consequence outright: "the base projects the concatenation as if it were one stream, so row projection and the ordered list-shaping post-process apply once over the whole union (this is what lets a later `union().fold()` fold the whole union into one list rather than one list per child)".

`ListShapingOp`'s javadoc says the opposite: "The boundary base rebuilds its shaped iterator on every (re)open of an arming — after a `reset()` and reopen, **and once per child plan for a multi-plan boundary** — calling `apply` afresh each time." That clause is false; nothing in `AbstractMatchPlanStep` or `MultiPlanMatchStep` rebuilds the shaped iterator per child. An implementer who takes it at face value would conclude `union(...).fold()` yields one list per child, which is the exact outcome the track's third `## Validation and Acceptance` bullet forbids.

The surrounding advice in that paragraph (allocate the buffer inside the returned iterator, hold no state across calls) stays correct for the reset-and-reopen case, so only the parenthetical needs to go.

**Proposed fix**: Correct the clause as part of this track — replace "and once per child plan for a multi-plan boundary" with the multi-plan reality (one arming spans the whole concatenation; a re-arm rebuilds). Add `ListShapingOp` to `## Interfaces and Dependencies` "In scope (modified)".

### T7 [should-fix]
**Certificate**: C16 (drain against the seven-state lifecycle)
**Location**: Track 9 Plan of Work item 5 and `## Validation and Acceptance`; `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/AbstractMatchPlanStep.java:386, 683`

**Issue**: The seven-state lifecycle itself is fine for a drain — the trace in C16 shows a `fold` drain lands cleanly on `OPEN → DRAINED` and on the failure path — but two properties the ops must have are asserted nowhere in the track.

**Re-arm.** `applyListShaping` calls `op.apply(...)` afresh on every open, and there are three open routes (`NEW`, `REARMED`, `REARMED_AFTER_CLOSE`). An op that allocates its buffer once outside the returned iterator replays the first pass's payloads on the second. `ListShapingOp`'s javadoc warns about it; the track has no acceptance line that would catch it. `toList(); admin.reset(); toList()` is the ordinary route through `REARMED_AFTER_CLOSE`, per the base's own class comment.

**Clone.** `AbstractStep.clone()` copies `shaping` by reference and `resetLifecycleForClone()` deliberately does not touch it, so two concurrent clones of a translated traversal share the *same* `ListShapingOp` instances. A stateful `fold` or `tail` op is then a data race, not just a re-arm bug — and the ops most likely to be written as shared singletons (see T3) are exactly the two that need buffers.

Item 5's test list covers `tail` boundaries, empty-input `fold`, the `reverse` value transform, the `unfold` buffer, declared-order combinations, and the placeholder form. It covers neither re-arm nor clone.

**Proposed fix**: Add two acceptance lines and matching tests to item 5. First: a `fold()` and a `tail(n)` boundary produce identical results across `toList(); reset(); toList()`, exercised from both `DRAINED` (reset before close) and `CLOSED` (reset after close). Second: two clones of a `fold()` boundary iterated concurrently each see their own full result — `MultiPlanMatchStepTest` already has the clone-isolation idiom to copy.

### T8 [should-fix]
**Certificate**: C11 (item 1a mechanism), C12 (`MatchPatternBuilder` consumers)
**Location**: Track 9 Plan of Work item 1a and `## Interfaces and Dependencies`; `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gql/parser/GqlMatchStatement.java:88`

**Issue**: Item 1a's mechanism is exactly right — all four claims verified (see C11) — but its blast radius reaches GQL, and the track scopes it at "roughly 3–5 files (`MatchPatternBuilder`, the filter-binding site the fix lands on, and the equivalence tests)".

`GqlMatchStatement` reaches the planner through `new MatchExecutionPlanner(ir.pattern(), ir.aliasClasses(), ir.aliasFilters())` — the same pattern-plus-alias-maps entry the translator uses, with `matchExpressions` left empty. `GqlMatchPatternAssembler` builds `ir` through the same shared `MatchPatternBuilder` (D6). So GQL satisfies both preconditions of the defect: its per-alias filters live only in `aliasFilters`, and `rebindFilters` iterates an empty `matchExpressions` and pushes nothing into the path items `MatchEdgeTraverser` reads. Whether GQL's surface actually produces the dropped shape needs a witness, but the mechanism is shared and any fix inside `MatchPatternBuilder` moves GQL's IR with it.

Second interaction the track does not mention. `rebindFilters` exists to push *post-optimization* filters back into the item AST — its call site at `MatchExecutionPlanner:2064` carries the comment "`detectNotInAntiJoin()` may have stripped NOT IN conditions from `aliasFilters` … Without this, the MatchStep would still evaluate the original un-stripped filter." If the fix pre-populates item filters at build time while `matchExpressions` stays empty, that push-back still never runs, so a stripped `NOT IN` would be left standing in the item AST — the precise failure that comment guards against. Whichever site the fix lands on has to answer this.

The track is honest that the site is undecided ("whichever of `MatchEdgeTraverser`'s AST reads or `rebindFilters`' `matchExpressions` walk the chosen fix touches"). The gap is that the decision has consequences the track has not enumerated.

**Proposed fix**: Extend item 1a with (a) a GQL witness — one GQL `MATCH` with a filter on a non-root alias, asserted before and after the fix, alongside the Gremlin four; (b) an explicit statement of which fix site is chosen and how the post-optimization strip is preserved on the empty-`matchExpressions` path (rebinding over the `Pattern`'s edge items rather than `matchExpressions` is the option that keeps `rebindFilters`' purpose intact, since `MatchPatternBuilder.buildNotExpression` shows the pattern's edges hold the very `SQLMatchPathItem` objects the traverser reads); (c) revise the file estimate, and add `GqlMatchPatternAssembler` / the GQL prettyPrint regression tests Track 1 established to `## Interfaces and Dependencies`.

### T9 [should-fix]
**Certificate**: C14 (`jmh-ldbc` module shape), C13 (no CI signal)
**Location**: Track 9 Plan of Work item 7 and the last `## Validation and Acceptance` bullet; `jmh-ldbc/src/main/java/com/jetbrains/youtrackdb/benchmarks/ldbc/LdbcBenchmarkState.java:29-53`

**Issue**: Item 7 promises "capture a baseline with the plan cache enabled" as a track deliverable, and the track's own Clarification says "Every gate in this track is verified locally." Those two cannot both hold in `jmh-ldbc`.

`LdbcBenchmarkState` is the module's only `@State`, and it "Reuses a pre-built YouTrackDB database if present at the configured path, otherwise creates and loads one from the LDBC CSV dataset" — a ~21-minute load for SF 1, or a pre-built archive fetched from Hetzner Object Storage. No dataset, no run. The module has no self-contained fixture graph.

The module *can* host the work: `LdbcBenchmarkState` already carries a `YTDBGraphTraversalSource traversal` field, so a Gremlin mirror has a source to walk, and the benchmark-base/`@State` split gives the on/off harness a template.

Two smaller points on the same item. `GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED` exists and is read by `GremlinToMatchStrategy:338`, so the on/off axis is real. But JMH forks run with assertions disabled by default, so "asserts the boundary step is installed" must be an explicit `instanceof AbstractMatchPlanStep` check that throws, not a Java `assert` — a bare `assert` would make the harness's central guarantee a no-op in every measured fork.

**Proposed fix**: Split item 7's deliverable. In-track: the mirrored benchmark classes plus the on/off harness land, compile, and the boundary-installation check is exercised by a plain JUnit test in `jmh-ldbc/src/test` (where `LdbcQueryCorrectnessTest` already lives) rather than only inside a benchmark fork. Out-of-track or explicitly Hetzner-scoped: the baseline numbers, captured per the `run-jmh-benchmarks-hetzner` skill. Say in the acceptance bullet which of the two the track is gated on. Keep the by-id `g.V(rid)` shape — C15 confirms it is still the pathological case.

### T10 [suggestion]
**Certificate**: C2 (`BoundaryOutputType` constants), C19
**Location**: Track 9 `## Context and Orientation` § Clarifications, first bullet (line 52)

**Issue**: The Pre-Flight clarification records "`BoundaryOutputType`'s three existing constants" as a verified file read. There are four: `ELEMENT`, `MAP`, `SINGLE_VALUE`, and `SCALAR`, the last added by Track 6's aggregations. `projectOrSkip` has four arms accordingly. The error does not change T1's conclusion but it is a wrong fact in a bullet whose entire purpose is to record what was verified.

Three sibling javadocs are stale in ways this track will otherwise leave behind:

- `UnionStepRecogniser` class javadoc: "the list-shaping terminators (`fold` and friends) are not translated yet".
- `RecognitionContext.setResultShaping` and `AbstractMatchPlanStep.shaping`: both say "the seven flags", which was accurate before Track 7 added `listShapingOps` to the record.
- `BoundaryOutputType`'s own class javadoc lists `SCALAR` in the constant list but the opening sentence still names only `YTDBMatchPlanStep` as the emitter, predating the Track 7 base and Track 8's `MultiPlanMatchStep`.

**Proposed fix**: Correct the clarification bullet to four constants. Add the three javadoc touch-ups to the track's doc-sync work — they are one-line edits in files the track is already opening.

### T11 [suggestion]
**Certificate**: C20 (`getLimit()` GValue pinning)
**Location**: Track 9 Plan of Work item 3 (`n<0` → decline); `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RangeGlobalStepRecogniser.java:88`

**Issue**: `TailGlobalStepPlaceholder.getLimit()` is not a pure read. Its bytecode checks `GValue.isVariable()` and, when true, calls `traversal.getGValueManager().pinVariable(name)` before returning the concrete `Long`. So a recogniser that reads the limit and then declines (the `n<0` path item 3 specifies) leaves the traversal's GValue manager mutated, and a parameterised `tail(n)` loses its variable status at strategy time.

This is precedent-consistent rather than novel: `RangeGlobalStepPlaceholder.getLowRange()` has byte-identical pinning logic, and `RangeGlobalStepRecogniser.normalize` reads it before its own decline branches. So Track 9 would be matching Track 6, not introducing a new hazard, and the mutation is on TinkerPop's GValue manager rather than on `WalkerContext`, so D9's no-mutation-on-decline discipline is not literally violated.

Also worth knowing: `TailGlobalStepContract.CONCRETE_STEPS` is `List.of(TailGlobalStep.class, TailGlobalStepPlaceholder.class)` — an authoritative source for the pair the registry must register, rather than two hand-written literals that could drift if the fork adds a third form.

**Proposed fix**: Note the pinning behaviour in item 3 so the decomposer chooses deliberately (read `getLimitAsGValue()` first and decline on `isVariable()` before touching `getLimit()`, or accept the precedent). Optionally cite `TailGlobalStepContract.CONCRETE_STEPS` as the registration source of truth.

## Evidence base

#### C1 Premise: the boundary base's lifecycle has seven states and the enum is private
- **Track claim**: "`AbstractMatchPlanStep.State` now carries seven constants — `NEW`, `OPEN`, `DRAINED`, `REARMED`, `CLOSED`, plus Track 10's `CLOSED_UNSTARTED` and `REARMED_AFTER_CLOSE`. … The enum is `private`, so the base class is the only thing that switches on it".
- **Search performed**: `find . -name 'AbstractMatchPlanStep.java'` (single match, package matches), then full read. PSI unavailable.
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/AbstractMatchPlanStep.java:188-248`
- **Actual behavior**: `private enum State { NEW, OPEN, DRAINED, REARMED, CLOSED, CLOSED_UNSTARTED, REARMED_AFTER_CLOSE }`, field `private State state = State.NEW`. The class carries a full state-transition table in its javadoc (lines 97-113). The three `NEW`-as-pristine readers the track names are present: the rewind skip at line 521, `close()`'s `CLOSED_UNSTARTED` mapping at line 654, and `reset()`'s `CLOSED_UNSTARTED → NEW` edge at line 623.
- **Verdict**: CONFIRMED
- **Detail**: Reference-accuracy caveat — `private` visibility bounds the reader set to this one file, which was read end to end, so the "only the base switches on it" negative is established by exhaustive read rather than by grep.

#### C2 Premise: `BoundaryOutputType` has three constants today
- **Track claim**: Clarification bullet — "the `AbstractMatchPlanStep.State` constant list, `BoundaryOutputType`'s **three** existing constants, and the `core/pom.xml` surefire execution shape — were read directly from the files".
- **Search performed**: `find . -name 'BoundaryOutputType.java'` (single match), full read; `grep -rn "BoundaryOutputType\." --include=*.java`.
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/BoundaryOutputType.java:23-47`
- **Actual behavior**: four constants — `ELEMENT`, `MAP`, `SINGLE_VALUE`, `SCALAR`. `SCALAR` is documented as the aggregate terminator output (`count`, `sum`, `min`, `max`, `mean`) and is asserted on by `GremlinAggregateRecogniserTest` and `GremlinStepWalkerTest`.
- **Verdict**: PARTIAL
- **Detail**: The constant list is wrong by one. It does not change the compile-break conclusion in C3, but it is a factual error in a Pre-Flight verification record. Produces T10.

#### C3 Premise: adding a constant breaks a compile-exhaustive `projectOrSkip` switch
- **Track claim**: "adding `BoundaryOutputType.LIST` breaks the compile-exhaustive `projectOrSkip` switch, which must gain a `LIST` case".
- **Search performed**: `grep -rn "projectOrSkip" --include=*.java .` (excluding `.claude/worktrees`), then read of each hit.
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/AbstractMatchPlanStep.java:694-701`; sole caller at line 418
- **Actual behavior**:

  ```java
  private Object projectOrSkip(Result row) {
    return switch (outputType) {
      case ELEMENT -> projectElement(row, armingGraph);
      case MAP -> projectMap(row);
      case SINGLE_VALUE -> projectSingleValue(row);
      case SCALAR -> projectScalar(row);
    };
  }
  ```

  A switch *expression* over an enum with no `default` — adding a constant is a compile error. Three grep hits total for `projectOrSkip`, all in this file: the `SKIP` sentinel javadoc (line 144), the single call site inside `rowProjectionSource()` (line 418), and the declaration. The method is `private`.
- **Verdict**: CONFIRMED
- **Detail**: The compile-break half of the track's claim holds. The remedial half does not: the method's signature is `Object projectOrSkip(Result row)` — one row in, one payload out — so a `LIST` arm has no value to return for a drain, and re-pinning `outputType` to `LIST` would discard the per-element projection mode the four existing arms encode. Produces T1. Reference-accuracy caveat: the "sole reader of `outputType`" claim rests on a repository-wide grep for `outputType` / `getOutputType()`, whose only behavioural hit is this switch; every other hit is constructor plumbing, a `toString`, or a test assertion.

#### C4 Premise: Track 7's ordered post-process carrier is the terminators' registration target
- **Track claim**: "The terminators register ordered ops into the post-process carrier Track 7 built"; the drain / flat-map / ring-buffer stages "belong alongside the existing group `accumulateMap` branch".
- **Search performed**: `find . -name 'ListShapingOp.java' -o -name 'ResultShaping.java'` (single match each), full reads; read of `AbstractMatchPlanStep.openShapedPayloads` / `applyListShaping`.
- **Code location**: `.../step/ListShapingOp.java:1-54`; `.../step/ResultShaping.java:37-109`; `.../step/AbstractMatchPlanStep.java:372-396`
- **Actual behavior**: `ListShapingOp` is a `@FunctionalInterface` `Iterator<Object> apply(Iterator<Object> upstream)` — a stream stage that may change cardinality. Its javadoc already assigns all four terminators to cardinality classes, naming `fold` and `tail(n)` as "N→1 / window drain". `ResultShaping` carries `List<ListShapingOp> listShapingOps` with a `withListShapingOps` copier. `openShapedPayloads()` selects a *source* (`accumulateMap ? accumulatedGroupMapSource() : rowProjectionSource()`) and then `applyListShaping()` threads the ops over it left to right, with a structural bypass on the empty list.
- **Verdict**: CONFIRMED
- **Detail**: The registration-target half is confirmed and is the right mechanism. The "alongside the `accumulateMap` branch" half is wrong about placement: `accumulateMap` picks a source, the ops are stages applied after source selection. A drain added as a third source branch would sit outside `applyListShaping` and could not order `reverse().unfold()` against `unfold().reverse()`. Feeds T1.

#### C5 Premise: `TailGlobalStepContract.getLimit()` with both concrete forms implementing it
- **Track claim**: "`tail(n)` arrives as either `TailGlobalStep` or `TailGlobalStepPlaceholder`, both implementing `TailGlobalStepContract.getLimit()`".
- **Search performed**: `unzip -l` + `javap -cp` on the resolved fork jar `io.youtrackdb:gremlin-core:3.8.1-67860f6-SNAPSHOT` (the version pinned by `pom.xml:114`). PSI unavailable; the classes are in a dependency jar, not in the source tree, so `find` would not resolve them.
- **Code location**: `org/apache/tinkerpop/gremlin/process/traversal/step/filter/TailGlobalStepContract.class` and the two implementations, in the fork jar
- **Actual behavior**: `public interface TailGlobalStepContract<S> extends Step<S,S>, Bypassing, FilteringBarrier<TraverserSet<S>>` with `public abstract java.lang.Long getLimit()`, a default `getLimitAsGValue()`, and `public static final List<Class<? extends Step>> CONCRETE_STEPS`. Both `TailGlobalStep<S>` and `TailGlobalStepPlaceholder<S>` are `final` and declare `implements TailGlobalStepContract<S>`; the placeholder additionally implements `GValueHolder<S,S>`. `CONCRETE_STEPS` static initialiser is `List.of(TailGlobalStep.class, TailGlobalStepPlaceholder.class)`.
- **Verdict**: CONFIRMED
- **Detail**: `getLimit()` returns a boxed `Long`, so the recogniser must null-check before unboxing, exactly as `RangeGlobalStepRecogniser.normalize` does for `getLowRange()` / `getHighRange()`.

#### C6 Premise: Track 6's `range` precedent registers both classes and keys on the Contract
- **Track claim**: "Track 6 already solved the identical shape for `range` by registering `RangeGlobalStep` and its placeholder".
- **Search performed**: read of `GremlinStepWalker.PRODUCTION_RECOGNISERS` and of `RangeGlobalStepRecogniser` in full.
- **Code location**: `.../strategy/GremlinStepWalker.java:163-164`; `.../strategy/RangeGlobalStepRecogniser.java:25`
- **Actual behavior**: the registry carries two entries mapping to one recogniser instance — `Map.entry(RangeGlobalStep.class, RangeGlobalStepRecogniser.INSTANCE)` and `Map.entry(RangeGlobalStepPlaceholder.class, RangeGlobalStepRecogniser.INSTANCE)` — and the recogniser's first act is `if (!(step instanceof RangeGlobalStepContract<?> range)) return Outcome.DECLINE;`. `GremlinStepWalker.postUnionSuffixTranslatable`'s javadoc calls out the two-keys-one-recogniser arrangement explicitly.
- **Verdict**: CONFIRMED
- **Detail**: The precedent is exactly as the track describes, including the Contract-interface cast inside `recognize`.

#### C7 Premise: `UnfoldStep.flatMap` / `ReverseStep.map` / `FoldStep` reference semantics
- **Track claim**: "`unfold()` flat-maps per emission"; "`reverse()` is a per-traverser **value** transform mirroring `ReverseStep.map` (NOT a stream-order reverse)"; "`fold()` → drain the stream into one `List`".
- **Search performed**: `javap -c -p` on the resolved fork jar (as C5).
- **Code location**: `org/apache/tinkerpop/gremlin/process/traversal/step/map/{UnfoldStep,ReverseStep,FoldStep}.class` in the fork jar
- **Actual behavior**:
  - `UnfoldStep<S,E> extends FlatMapStep<S,E>`; `flatMap` dispatches on the traverser value: `Iterator` → itself; `Iterable` → `.iterator()`; **`Map` → `.entrySet().iterator()`**; array → `handleArrays` (an `ArrayIterator` for `Object[]`, reflective copy for primitives); everything else → `IteratorUtils.of(value)`.
  - `ReverseStep<S,E> extends ScalarMapStep<S,E>`; `map` returns `null` for null; a reversed `String` via `StringBuilder.reverse()`; for `Iterable` / `Iterator` / array, `IteratorUtils.asList(...)` followed by `Collections.reverse` on the new list; otherwise the value unchanged. Nothing touches stream order.
  - `FoldStep<S,E> extends ReducingBarrierStep<S,E>` with **two** constructors — `(Traversal.Admin)` and `(Traversal.Admin, Supplier<E>, BiFunction<E,S,E>)` — plus `public boolean isListFold()` reading a `listFold` boolean field.
- **Verdict**: PARTIAL
- **Detail**: The `reverse` claim is fully confirmed and is the one the track flagged as load-bearing. Two gaps: `unfold`'s contract is five-way, not "expand a list" (Map → entries matters because `MAP` is a live boundary output type — produces T5); and `FoldStep` also carries the seeded `fold(seed, operator)` form that must decline (produces T4).

#### C8 Premise: `POST_UNION_RECOGNISERS` membership and its two readers
- **Track claim**: "`GremlinStepWalker.POST_UNION_RECOGNISERS`, today `count` / `range` / `dedup` … Both readers of the set are the walker's own (`dispatchAll`'s fail-closed gate and `postUnionSuffixTranslatable`'s look-ahead) … so adding the recognisers to the one field covers both paths."
- **Search performed**: `grep -n "POST_UNION_RECOGNISERS" GremlinStepWalker.java` plus a repository-wide grep for the identifier; read of both reader methods.
- **Code location**: `.../strategy/GremlinStepWalker.java:193-197` (declaration), `:322` (`dispatchAll`), `:380` (`postUnionSuffixTranslatable`)
- **Actual behavior**: `private static final Set<StepRecogniser> POST_UNION_RECOGNISERS = Set.of(CountGlobalStepRecogniser.INSTANCE, RangeGlobalStepRecogniser.INSTANCE, DedupGlobalStepRecogniser.INSTANCE);`. Exactly two reads, both in this file: the per-step gate `if (ctx.hasUnionCarrier() && !POST_UNION_RECOGNISERS.contains(recogniser)) return false;` and the look-ahead's `if (recogniser == null || !POST_UNION_RECOGNISERS.contains(recogniser)) return false;`. The field's javadoc states the two-readers-one-field invariant itself.
- **Verdict**: CONFIRMED
- **Detail**: `private` visibility bounds the reader set to this file, which was read in full, so the "both readers" negative is exhaustive rather than grep-inferred. The gate's rationale also confirms the terminators' contributions survive the multi-plan branch: the javadoc says `buildResult`'s multi-plan branch "reads only the boundary metadata, the shaping, and the post-concat ops", and `buildResult:466` does pass `ctx.shaping()` into `TranslationResult.multiPlan`.

#### C9 Premise: the union concatenation folds once, not once per child
- **Track claim**: "`MultipleExecutionStream` (DR-U1) already concatenates the child plans into one stream, so `union(...).fold()` folds the concatenation once rather than per child."
- **Search performed**: `find . -name 'MultiPlanMatchStep.java'` (single match), full read; cross-read of `AbstractMatchPlanStep.openShapedPayloads` and `ListShapingOp`'s javadoc.
- **Code location**: `.../step/MultiPlanMatchStep.java:307-342` (`startPlanStream`) and its class javadoc lines 35-48; `.../step/AbstractMatchPlanStep.java:331-336, 372-376`
- **Actual behavior**: `startPlanStream()` builds one `MultipleExecutionStream` over a lazy `ExecutionStreamProducer` and returns it (possibly wrapped in `PostConcatStreams` decorators). The base builds `shapedPayloads` once per arming on first pull. `MultiPlanMatchStep`'s class javadoc states the consequence in those words: "row projection and the ordered list-shaping post-process apply once over the whole union (this is what lets a later `union().fold()` fold the whole union into one list rather than one list per child)".
- **Verdict**: WRONG — for the *documentation*, not for the track. The track's claim is right; `ListShapingOp`'s javadoc (line 35) contradicts it with "and once per child plan for a multi-plan boundary", which no code path implements.
- **Detail**: Produces T6.

#### C10 Premise: a terminator can append a list-shaping op to the walk's shaping
- **Track claim**: item 3 — "`UnfoldStep` / `ReverseStep` / `TailGlobalStep` recognisers **registering ordered ops** into the Track 7 post-process carrier … `reverse().unfold()` / `unfold().reverse()` accepted (order preserved)".
- **Search performed**: full read of `RecognitionContext` (the interface every recogniser sees), `grep -n "shaping"` across `WalkerContext` / `RecognitionContext` / `SubTraversalPredicateAdapter`.
- **Code location**: `.../strategy/RecognitionContext.java:262-270`; `.../strategy/WalkerContext.java:128, 577-584`; `.../strategy/SubTraversalPredicateAdapter.java:397-398`
- **Actual behavior**: the interface declares `void setResultShaping(@Nonnull ResultShaping shaping)` and no reader. Its javadoc pins the contract: "A terminator builds the exact combination from `ResultShaping#NONE` plus its overrides and calls this once". `WalkerContext` holds the field and a *package-private* `ResultShaping shaping()` accessor at line 583, not exposed on the interface. `SubTraversalPredicateAdapter.setResultShaping` is a documented no-op.
- **Verdict**: NOT FOUND
- **Detail**: There is no seam through which a second terminator can read the first one's ops, and none through which `fold` can preserve a preceding `values(key)`'s presence flags. Produces T2.

#### C11 Premise: item 1a's mechanism (four sub-claims)
- **Track claim**: "`MatchEdgeTraverser` reads the target constraint from the pattern item's AST (`item.getFilter().getFilter()` and `.getClassName()`) rather than from `aliasFilters`, and `MatchPatternBuilder` builds positive path items through `SQLMatchFilter.fromAliasAndClass(toAlias, null)` with neither a `WHERE` nor a class. The one routine that would populate them, `rebindFilters`, walks `matchExpressions`, which is empty on the additive translator path … `MatchPatternBuilder.mergedTargetFilter` already performs this merge for NOT expressions and is the natural extension point."
- **Search performed**: `grep -n "getFilter()\|getClassName()\|aliasFilters" MatchEdgeTraverser.java`; `grep -rn "mergedTargetFilter\|rebindFilters\|fromAliasAndClass" --include=*.java core/src/main/java`; reads of each hit; read of `GremlinStepWalker.buildResult`'s `MatchPlanInputs.builder(...)` chain.
- **Code location**: `.../sql/executor/match/MatchEdgeTraverser.java:476, 481`; `.../match/builder/MatchPatternBuilder.java:147-151, 344-403`; `.../match/MatchExecutionPlanner.java:6012-6023`; `.../gremlin/translator/strategy/GremlinStepWalker.java:483-497`
- **Actual behavior**: all four sub-claims hold.
  1. `MatchEdgeTraverser` line 476 `return item.getFilter().getFilter();` and line 481 `return item.getFilter().getClassName(iCommandContext);`. No `aliasFilters` reference anywhere in the file.
  2. `addEdge` builds `var toFilter = SQLMatchFilter.fromAliasAndClass(toAlias, null);` and only sets a `WHERE` when the caller passed an `edgeFilter` (which on the Gremlin path is the *edge* filter, not the target alias's).
  3. `rebindFilters` is `for (var expression : matchExpressions) { … }`. `buildResult` populates `notMatchExpressions` and never `matchExpressions`, and `MatchPlanInputs`' compact constructor normalises the absent list to empty — so the loop body never executes on the translator path.
  4. `mergedTargetFilter` (line 377) already AND-merges `aliasClasses.get(alias)`, `aliasFilters.get(alias)`, and a supplemental map into a path item's filter. Repository-wide grep finds exactly one call site, `buildNotExpression:365`, so extending it to the positive path is a genuine reuse rather than a rewrite.
- **Verdict**: CONFIRMED
- **Detail**: The mechanism is right. The blast-radius claim is where the gap is — see C12 and T8. Reference-accuracy caveat: `mergedTargetFilter` is `private`, so the one-call-site negative is bounded by a full read of the declaring file.

#### C12 Integration: `MatchPatternBuilder` and the shared planner entry
- **Plan claim**: item 1a's fix costs "roughly 3–5 files (`MatchPatternBuilder`, the filter-binding site the fix lands on, and the equivalence tests that do not currently witness it)".
- **Actual entry point**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gql/parser/GqlMatchStatement.java:88` — `var planner = new MatchExecutionPlanner(ir.pattern(), ir.aliasClasses(), ir.aliasFilters());`
- **Caller analysis**: `grep -rln "MatchPatternBuilder" --include=*.java core/src/main/java` returns eleven production files: the builder itself, `MatchEdgePathItems`, eight translator-package files, and `GqlMatchPatternAssembler`. The GQL assembler's own javadoc states it builds "a `MatchPatternBuilder.PatternIR` that `MatchExecutionPlanner` consumes". `aliasFilters` appears 110 times inside `MatchExecutionPlanner`, so the "pre-filter descriptors / hash-join branches / `$matched` back-refs also read `aliasFilters`" claim in the plan's Track 9 checklist entry is directionally right about the density of readers.
- **Breaking change risk**: GQL satisfies both preconditions of the item-1a defect — a shared builder producing path items with no alias filter, and a planner entry that leaves `matchExpressions` empty so `rebindFilters` pushes nothing back. Any fix inside `MatchPatternBuilder` therefore changes GQL's IR and its `prettyPrint` plan output, which Track 1 pinned with regression tests. Separately, pre-populating item filters at build time leaves `rebindFilters`' documented purpose (pushing *post-optimization* stripped filters back — see the `detectNotInAntiJoin` comment at `MatchExecutionPlanner:2058-2064`) still unserved on the empty-`matchExpressions` path.
- **Verdict**: CALLERS AT RISK
- **Detail**: Produces T8. Reference-accuracy caveat: the consumer list is a grep over `core/src/main/java` only; a consumer in another module or one reaching the builder through a re-export would not appear.

#### C13 Premise: the Cucumber runner's surefire wiring and how the suite is actually run
- **Track claim**: Clarification — "develop's `9b9dfa20fd` gives `YTDBGraphFeatureTest` its own `gremlin-feature-compliance-tests` surefire execution inside the `gremlin-compliance-suites` profile (activated on `!test`), with `<failIfNoTests>true</failIfNoTests>` and no group filter, while `sequential-tests` excludes `**/gremlintest/**`. The core Cucumber runner executes under `./mvnw -pl core test`."
- **Search performed**: `grep -n` over `core/pom.xml` for the execution ids and filters, then a full read of lines 196-300 and 386-450; read of `YTDBGraphFeatureTest.java`.
- **Code location**: `core/pom.xml:236-292` (the profile), `:394-412` (`default-test`), `:419-450` (`sequential-tests`); `core/src/test/java/.../gremlintest/YTDBGraphFeatureTest.java`
- **Actual behavior**: exactly as claimed. The profile activates on `<name>!test</name>`; the `gremlin-feature-compliance-tests` execution includes `**/YTDBGraphFeatureTest.java` with `failIfNoTests=true` and configures neither `<groups>` nor `<excludedGroups>`. `default-test` carries `<excludedGroups>SequentialTest</excludedGroups>` plus `<exclude>**/gremlintest/**</exclude>`; `sequential-tests` carries `<groups>SequentialTest</groups>` plus the same exclude. `YTDBGraphFeatureTest` is `@RunWith(Cucumber.class)` over two feature roots (the upstream TinkerPop `test/features` classpath tree plus a YTDB-local one) with eleven `not @…` tag exclusions.
- **Verdict**: CONFIRMED
- **Detail**: One operational consequence the track does not state but which follows from the pom's own comment block: because the profile is keyed on the *absence* of `-Dtest`, supplying `-Dtest=YTDBGraphFeatureTest` deactivates the whole profile, and surefire discards every execution's includes/excludes once `-Dtest` is set. So the Cucumber suite is reachable only through a bare `./mvnw -pl core test`, which is also what forces the ~32-minute `MatchStatementExecutionTest` cost the track already books, twice.

#### C14 Premise: `jmh-ldbc` supports a mirrored Gremlin on/off harness
- **Track claim**: item 7 — "Add the mirrored Gremlin JMH benchmark classes + on/off harness pinned to verified-recognised shapes, asserting boundary-step installation; capture a baseline with the plan cache enabled."
- **Search performed**: directory listing and `find`/`grep` over `jmh-ldbc`; read of `LdbcBenchmarkState`'s header and state fields; `grep -n` over `jmh-ldbc/pom.xml`.
- **Code location**: `jmh-ldbc/src/main/java/com/jetbrains/youtrackdb/benchmarks/ldbc/LdbcBenchmarkState.java:29-62`; `jmh-ldbc/pom.xml:194-210` (the `bench` profile)
- **Actual behavior**: the module has one `@State(Scope.Benchmark)` class, six benchmark classes over four bases, a `ParameterCurator`, a shade plugin, and a `bench` exec profile. `LdbcBenchmarkState` already declares `YTDBGraphTraversalSource traversal`, so a Gremlin mirror has a source. But the state "Reuses a pre-built YouTrackDB database if present at the configured path, otherwise creates and loads one from the LDBC CSV dataset" — a ~21-minute load for SF 1, or a pre-built archive from Hetzner Object Storage. There is no self-contained fixture graph in the module.
- **Verdict**: PARTIAL
- **Detail**: The module can *host* the classes; it cannot produce a baseline without the dataset, which contradicts the track's "Every gate in this track is verified locally" clarification. Produces T9. Second point on the same item: JMH forks run with assertions disabled by default, so a Java `assert` for boundary-step installation would be a no-op in every measured fork.

#### C15 Premise: `g.V(rid)` is still the pathological shape after Track 10
- **Track claim**: item 7 — "RID-bearing walks set `cacheEligible=false`, so a by-id lookup compiles an uncached MATCH plan where the native path ran no query at all, and that is the one shape where translator-on can be strictly slower than translator-off (Track 10 R2)."
- **Search performed**: read of `GremlinStepWalker.buildResult`'s `singlePlan(...)` call, `WalkerContext.ridBearing()`, and `GremlinToMatchStrategy`'s plan-build branch; read of `GremlinPlanCache`.
- **Code location**: `.../strategy/GremlinStepWalker.java:507` (`!ctx.ridBearing()` supplies `cacheEligible`); `.../strategy/WalkerContext.java` `markRidBearing` / `ridBearing`; `.../strategy/GremlinToMatchStrategy.java:419-421`
- **Actual behavior**: `buildResult` passes `!ctx.ridBearing()` as the translation's `cacheEligible`; the strategy's build path short-circuits with `if (!translation.cacheEligible()) { return buildPlanUncached(session, requireInputs(translation)); }`, skipping both the fingerprint computation and the `GremlinPlanCache` get/put. So every `g.V(rid)` execution recompiles.
- **Verdict**: CONFIRMED
- **Detail**: Track 10's closing note that the per-call plan recompile for by-id lookups is still unfixed holds at HEAD; the shape is still worth pinning in the JMH harness.

#### C16 Edge case: a `fold()` drain against the seven-state lifecycle
- **Trigger**: `g.V().fold()` on a translated traversal, iterated to exhaustion, then `reset()` and iterated again.
- **Code path trace**:
  1. Entry: `processNextStart()` @ `AbstractMatchPlanStep.java:315` — state `NEW`, so `openStream = openArming()` (no rewind, the plan is pristine), state → `OPEN`, `shapedPayloads = null`.
  2. `shapedPayloads = openShapedPayloads()` @ `:335`, inside the try — `accumulateMap` is false for an element path, so the source is `rowProjectionSource()`, then `applyListShaping` wraps it in the fold op.
  3. `shapedPayloads.hasNext()` @ `:337` — the fold op drains the whole underlying `ExecutionStream` here. A failure during the drain lands in the `catch (RuntimeException | Error)` at `:348`, which sets `CLOSED` and calls `releaseStreamAndClosePlan()`, so the plan does not leak.
  4. One list payload is emitted @ `:344`.
  5. Second call: state is `OPEN`, `shapedPayloads` is non-null and dry → state `DRAINED`, `releaseStream()` (stream closed, plan left open), `FastNoSuchElementException`.
  6. `reset()` @ `:617` — `DRAINED` → `REARMED`. Next open rewinds the plan and rebuilds `shapedPayloads`, calling `op.apply(...)` a second time.
- **Outcome**: correct. The seven-state machine needs no change for a drain, and the eager drain is already inside the guarded region the group barrier established. Two hazards fall out of step 6 rather than out of the states themselves: `apply` is re-invoked, so an op holding a buffer outside its returned iterator replays stale payloads; and `AbstractStep.clone()` copies `shaping` by reference while `resetLifecycleForClone()` (line 683) deliberately leaves it alone, so concurrent clones share the same op instances.
- **Track coverage**: no — item 5's test list covers `tail` boundaries, empty `fold`, the `reverse` transform, the `unfold` buffer, order combinations, and the placeholder form, but neither re-arm nor clone. Produces T7.

#### C17 Edge case: a union child that carries its own list-shaping op
- **Trigger**: `g.V().union(__.out().fold(), __.in().fold())`.
- **Code path trace**:
  1. Entry: `UnionStepRecogniser.recognize` @ `:50`. `postUnionSuffixTranslatable` sees no suffix after the union and returns true (the vacuous case).
  2. `host.walkFork(childSuffixWithoutEnd(child))` @ `:95` runs a complete single-plan walk of prefix + child. Nothing in that walk knows it is a union child, so the child's trailing `fold()` looks like a last step and, once Track 9 registers `FoldStep`, is accepted — pinning a `ListShapingOp` into the child's `ResultShaping`.
  3. `!agreedShaping.equals(childResult.shaping())` @ `:108` — `ResultShaping` is a record, so `equals` compares the `List<ListShapingOp>` element-wise.
  4a. Ops implemented as lambdas or fresh instances → unequal → `Outcome.DECLINE`. Safe.
  4b. Ops implemented as record singletons (`PostConcatOp.Count.INSTANCE` and `PostConcatOp.Dedup.INSTANCE` are this codebase's own precedent, and `record TailOp(long n)` is the natural shape for a parameterised tail) → equal → accept.
  5. On 4b: `ctx.setResultShaping(agreedShaping)` @ `:124`, then `buildResult`'s multi-plan branch passes that shaping to `MultiPlanMatchStep`, whose base applies the fold **once** over the `MultipleExecutionStream` concatenation (C9).
- **Outcome**: under 4b, one list over both children where native yields one list per child. Silent — the traversal translates, exactly one boundary step, no decline. `unfold` and `reverse` are immune because they are per-payload; only `fold` and `tail` diverge.
- **Track coverage**: no — item 4 discusses only the *post*-union suffix, and the third `## Validation and Acceptance` bullet covers `union(...).fold()` (the suffix form), not `union(__.fold(), __.fold())` (the child form). Produces T3.

#### C18 Premise: the plan cache does not key on boundary shaping
- **Track claim**: implicit — items 2, 3, and 7 all assume terminator differences that leave the MATCH statement unchanged are safe with the plan cache on.
- **Search performed**: full read of `GremlinPlanCache`; read of `GremlinToMatchStrategy`'s cache get/put path.
- **Code location**: `.../strategy/GremlinPlanCache.java:32-110`; `.../strategy/GremlinToMatchStrategy.java:419-436`
- **Actual behavior**: `GremlinPlanCache extends AbstractMetadataUpdateCache<String, InternalExecutionPlan>` — the value is the compiled plan alone, keyed by `GremlinPlanFingerprint.fingerprint(inputs)` over `MatchPlanInputs`. `BoundaryOutputType`, `ResultShaping`, and therefore `listShapingOps` are re-derived by the walker on every compilation and installed on the freshly-constructed boundary step, never stored in or read from the cache.
- **Verdict**: CONFIRMED
- **Detail**: No finding. `g.V()`, `g.V().fold()`, `g.V().tail(3)`, and `g.V().tail(5)` all fingerprint to the same MATCH statement and correctly share one cached plan, because the difference lives entirely on the boundary step. Recorded because the opposite result would have been a blocker for items 2, 3, and 7.

#### C19 Premise: `PostConcatOp` is a separate concat-time mechanism, not a competing carrier
- **Track claim**: plan Track 8 strategy refresh — "Track 8's `PostConcatOp` (a sealed type permitting `Count` / `Range` / `Dedup` only) is a separate concat-time mechanism rather than a competing one".
- **Search performed**: `find . -name 'PostConcatOp.java'` (single match), full read; read of `MultiPlanMatchStep.applyPostConcatOp`.
- **Code location**: `.../step/PostConcatOp.java:16-44`; `.../step/MultiPlanMatchStep.java:434-449`
- **Actual behavior**: `public sealed interface PostConcatOp permits PostConcatOp.Count, PostConcatOp.Range, PostConcatOp.Dedup`, with `Count` and `Dedup` as parameterless records exposing an `INSTANCE` singleton and `Range(long skip, long limit)` validating `skip >= 0`. `applyPostConcatOp`'s switch is exhaustive over the three with no default. The interface's javadoc names the split with Track 9: these are the barriers that must see the concatenated multiset, "not the Track 9 list-shaping terminators (`fold`/`unfold`/`reverse`/`tail`), which ride `ResultShaping#listShapingOps()`".
- **Verdict**: CONFIRMED
- **Detail**: Two things follow. The repository already commits, in code, to the carrier choice T1 argues for. And the `Count.INSTANCE` / `Dedup.INSTANCE` record-singleton idiom is the house pattern a Track 9 implementer would naturally copy for `fold` / `unfold` / `reverse`, which is precisely what makes C17's 4b branch reachable.

  Ordering, checked while here: post-concat ops decorate the raw `ExecutionStream` before projection, and list-shaping ops run after it. Because D3 confines list-shapers to the last step, the two never interleave — `union(...).dedup().fold()` dedups rows, projects, then folds, matching native. No finding.

#### C20 Edge case: reading `getLimit()` on a parameterised `tail` placeholder
- **Trigger**: `g.V().tail(n)` where `n` arrives as a GValue variable and the recogniser reads the limit before its `n < 0` decline check.
- **Code path trace**:
  1. Entry: `TailGlobalStepPlaceholder.getLimit()` (fork jar). Bytecode: load field `limit` (a `GValue<Long>`), call `GValue.isVariable()`.
  2. If variable: `traversal.getGValueManager().pinVariable(gvalue.getName())` — a mutation of the host traversal's GValue manager — result discarded.
  3. Return `((Long) limit.get())`.
  4. Recogniser sees `n < 0` and returns `Outcome.DECLINE`; the walk declines and the traversal runs natively, with the variable now pinned.
- **Outcome**: the traversal is left mutated by a walk that declined, and a parameterised `tail` loses its variable status at strategy time.
- **Track coverage**: no. But `RangeGlobalStepPlaceholder.getLowRange()` has byte-identical pinning logic and `RangeGlobalStepRecogniser.normalize` (line 88) reads it ahead of its own decline branches, so Track 9 following the same shape matches Track 6 rather than introducing a new hazard. The mutation is on TinkerPop's GValue manager, not on `WalkerContext`, so D9's no-mutation-on-decline discipline is not literally breached. Produces T11 at suggestion severity only.
