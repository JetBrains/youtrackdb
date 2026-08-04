<!-- MANIFEST
findings: 4   severity: {blocker: 0, should-fix: 3, suggestion: 1}
index:
  - {id: T9,  sev: should-fix, loc: "track-11.md:64 (item 1), :24 (DR-T2); RecognitionContext.java:357; SubTraversalPredicateAdapter.java:471-474; RangeGlobalStepRecogniser.java:210", anchor: "### T9 ", cert: "P1/P2/I1", basis: "The query-plus-decline seam DR-T2 concludes it must invent already exists as dropsRowsOnAbsentProperty — non-default on the interface, false on the adapter, read-and-declined-on by a recogniser; declaring supportsListShaping() non-default the same way retires T4's mock inversion structurally instead of mitigating it"}
  - {id: T10, sev: should-fix, loc: "track-11.md:84 (item 10's 2026-08-04 amendment); GremlinStepWalker.java:163", anchor: "### T10 ", cert: "P5", basis: "TRANSPARENT_STEPS occurs only in production and has zero test-tree occurrences, against the amendment's sixteen hand-mirrored classes; the other four re-measured counts also disagree with HEAD, and today's plan re-pricing leaned on all five"}
  - {id: T11, sev: should-fix, loc: "track-11.md:120 (## Interfaces and Dependencies § Signatures)", anchor: "### T11 ", cert: "P2/P3", basis: "Every cited line number in § Signatures drifted when Track 9 grew these files — appendPostConcatOp :286 to :373, walkChild :333 to :433, the adapter's three sites :89/:397/:413 to :106/:460/:488 — so a decomposed step citing them sends the implementer to the wrong place; iter1's T8 caught one instance of the same defect"}
  - {id: T12, sev: suggestion, loc: "implementation-plan.md Track 11 Scope line (re-priced 2026-08-04); track-11.md:79 (item 9)", anchor: "### T12 ", cert: "P6", basis: "The re-priced Scope line says 19 hand-built AST sites; the figure at HEAD is 18 across seven files, exactly item 9's own enumeration — DR-S1's plus-one is true of the raw grep total but the added site is the toArray array idiom, not a hand-built node"}
evidence_base: {section: "## Evidence base", certs: 12, matches: 4}
cert_index:
  - {id: P1,  verdict: CONFIRMED, anchor: "#### P1 "}
  - {id: P2,  verdict: PARTIAL,   anchor: "#### P2 "}
  - {id: P3,  verdict: PARTIAL,   anchor: "#### P3 "}
  - {id: P4,  verdict: CONFIRMED, anchor: "#### P4 "}
  - {id: P5,  verdict: WRONG,     anchor: "#### P5 "}
  - {id: P6,  verdict: PARTIAL,   anchor: "#### P6 "}
  - {id: P7,  verdict: CONFIRMED, anchor: "#### P7 "}
  - {id: P8,  verdict: CONFIRMED, anchor: "#### P8 "}
  - {id: P9,  verdict: CONFIRMED, anchor: "#### P9 "}
  - {id: P10, verdict: CONFIRMED, anchor: "#### P10 "}
  - {id: I1,  verdict: MATCHES,   anchor: "#### I1 "}
  - {id: I2,  verdict: MATCHES,   anchor: "#### I2 "}
flags: [CONTRACT_OK, ORCHESTRATOR_RUN, GREP_NOT_PSI]
-->

# Track 11 technical review — iteration 2

No blockers. Iteration 1's blocker T1 and its four should-fixes are discharged or superseded; three new should-fixes and one suggestion take their place, and all four are about the track file describing a tree that has moved rather than about the design being wrong.

The strongest is T9: item 1 spends a Decision Record (DR-T2) establishing that no in-repo template exists for a query-plus-decline seam, and one already does, three declarations away in the same interface. Following it also removes the vacuous-acceptance hazard iteration 1 raised as T4, structurally rather than by adding a positive control. T10 and T12 are stale counts, one of them introduced into the plan earlier today by this orchestrator. T11 is line-number rot across the whole § Signatures block.

## Reviewer notes

**Run conditions.** This pass ran as the orchestrator inline, not as a sub-agent: two consecutive sub-agent attempts died mid-stream at the point of writing their output file, and the user directed the review inline rather than re-spending on the same shape. The `ORCHESTRATOR_RUN` flag records it. Scope was therefore prioritised rather than exhaustive — twelve certificates against iteration 1's twenty-seven — concentrating on the premises whose failure would mis-size decomposition. Items 2, 3 and 5's fork-jar step semantics were **not** re-derived; iteration 1 verified them by `javap` against the resolved `io.youtrackdb` jar and nothing in Track 9's landing touches the fork.

**Reference accuracy.** Grep plus end-to-end Reads of every returned site, not PSI. mcp-steroid is reachable and reports the IDE open on this working tree, but `steroid_execute_code` times out on this repository (cold kotlinc exceeds the 60 s MCP limit) — the same failure recorded in this track's `### Clarifications` and hit by all three iteration-1 panels. Declaration reads, control-flow traces and count comparisons below are reliable. "No other caller" negatives are bounded, not established; T10's zero-occurrence result for `TRANSPARENT_STEPS` is the one negative doing real work, and it is stated as "re-enumerate", not as proof.

**Counting caveat on T10.** The figures below count *files* containing a pattern, via `grep -rln`, where item 10's amendment counts *copies*. The two need not agree, so T10 asks for a re-enumeration rather than asserting exact replacements. The `TRANSPARENT_STEPS` result is different in kind: zero files, so no copy count can be non-zero.

**What iteration 1 left behind.** T1 (blocker — the last-step rule stated three inconsistent ways, and its exposure to eleven `ResultShaping.NONE` clobber sites) is superseded by today's pre-flight amendment, which put the post-strategy-step-list obligation into `## Context and Orientation` and made the rule a claim about the rewritten tree rather than the authored one. T2 is folded into T9 below. T3 (post-concat ops always run before list-shaping ops) still stands on the code but is now stated in item 4, so it is absorbed rather than open. T4 is retired structurally by T9's proposed fix. T5 (`curatedParams` private, no setter) is discharged: item 7's amendment routes through the public accessors, which exist (P9). T6 and T8 are subsumed by T11's wider line-drift finding.

## Findings

### T9 [should-fix]
**Certificate**: P1 (the existing seam), P2 (the interface's shape), I1 (the recogniser that declines on it)
**Location**: `plan/track-11.md:64` (item 1), `:24` (DR-T2); `RecognitionContext.java:357`; `SubTraversalPredicateAdapter.java:465-474`; `RangeGlobalStepRecogniser.java:47-50,210`
**Issue**: DR-T2 reasons that the append seam needs a decline channel, that a `void` mutator cannot carry one, and that "both in-repo templates are wrong for this" — the adapter's swallow of `setResultShaping` and `appendPostConcatOp`'s throw. It concludes the seam must pair a new query with the mutator. The conclusion is right and the premise is incomplete: a third template sits in the same interface and does exactly this. `RecognitionContext.dropsRowsOnAbsentProperty()` (`:357`) is a plain boolean query — **not** a `default` method — implemented on `WalkerContext:636`, overridden to `false` on `SubTraversalPredicateAdapter:472` with a javadoc explaining why the answer is not delegated to the parent, and read by a recogniser that declines on it: `RangeGlobalStepRecogniser:210` guards on it, and its class javadoc at `:47-50` describes the mechanism as "declines once ... The guard reads one boolean". That is the `supportsListShaping()` shape, already shipped and already tested.

Two consequences. Item 1 describes inventing a mechanism it should describe copying, which prices the work higher than it is and invites a second, divergent idiom for the same job. And the polarity matters: `dropsRowsOnAbsentProperty()` is non-default, so every implementer must state an answer, while item 1 specifies `default boolean supportsListShaping() { return true; }` — and a `default true` is precisely what iteration 1's T4 flagged, because a Mockito mock of `RecognitionContext` returns `false` and every combinator-child decline assertion then passes without exercising the decline.
**Proposed fix**: declare `supportsListShaping()` as a non-default interface method, following `dropsRowsOnAbsentProperty` verbatim — `WalkerContext` answers `true`, `SubTraversalPredicateAdapter` answers `false` with the same javadoc shape, and each terminator recogniser reads-and-declines the way `RangeGlobalStepRecogniser:210` does. Name `dropsRowsOnAbsentProperty` in item 1 as the pattern. Amend DR-T2's "both in-repo templates are wrong" to name the third and say why it is right, so the Decision Record does not read as refuted later. T4's positive control is then belt rather than the only defence: no default exists for a mock to invert.

### T10 [should-fix]
**Certificate**: P5
**Location**: `plan/track-11.md:84` (item 10's 2026-08-04 amendment); `GremlinStepWalker.java:163`; `implementation-plan.md` Track 11 Scope line
**Issue**: item 10's amendment was measured on 2026-08-04 and presents five exact figures — "nine hand-rolled copies of the translator toggle under five different helper names, eleven copies of `countBoundarySteps`, five of `assertEquivalent` plus its recognition enum ... and `GremlinStepWalker`'s private `TRANSPARENT_STEPS` mirrored by hand in sixteen test classes, one of which has diverged to a bare `Set.of()`". At HEAD (`f2b1230db0`) the last one does not hold at all: `TRANSPARENT_STEPS` appears three times in the repository, all in production — the declaration at `GremlinStepWalker.java:163` and two reads at `:374` and `:670` — and `grep -rln TRANSPARENT_STEPS core/src/test` returns zero files. Sixteen hand-mirrored copies cannot be reduced from zero occurrences. Either Track 9's Phase C retired them (it made `POST_UNION_RECOGNISERS` package-private with a reflective test pinning it, which is the same move) or the mirrors never carried that identifier.

The other four figures also disagree with HEAD, by file count: 10 files carry a translator-toggle helper against nine claimed copies, 10 carry `countBoundarySteps` against eleven, 5 carry a recognition or cardinality enum against six, and 10 reference `assertEquivalent` against five. File counts and copy counts are different measures, so these four are a signal to re-derive rather than a contradiction.
**Proposed fix**: item 10 re-enumerates all five figures at its own base commit as the first act of its step, and the sixteen-mirror clause is dropped or re-derived before any step body cites it. This reaches past item 10: today's re-pricing of the plan's Scope line to ~35–45 files rests on these numbers, so it is re-checked in the same pass. An item whose scope was measured yesterday and is stale today is an argument for deriving the count in-step rather than carrying it in the plan.

### T11 [should-fix]
**Certificate**: P2, P3
**Location**: `plan/track-11.md:120` (`## Interfaces and Dependencies`, § Signatures)
**Issue**: § Signatures cites eight line numbers as the implementer's map into the seam, and Track 9's steps 10 and 11 grew every file they sit in, so all of the ones checked have drifted. Measured at HEAD: `RecognitionContext.appendPostConcatOp` cited as `:286`, actual `:373`; `RecognitionContext.walkChild` cited as `:333`, actual `:433`; `SubTraversalPredicateAdapter`'s shared-registry comment cited as `:89`, actual `:106`; its `setResultShaping` swallow cited as `:397`, actual `:460`; its `walkChild` cited as `:413`, actual `:488`. Two cited numbers do hold — `ResultShaping.withListShapingOps` at `ResultShaping.java:106` and `subWalk` in the `:399-411` range — which is what makes the block dangerous rather than obviously stale: a reader who spot-checks one correct citation will trust the rest. Iteration 1's T8 caught a single instance of this (`GremlinToMatchStrategy:338` drifting to `:351`); the defect is the whole block, not that one line.
**Proposed fix**: strip the line numbers from § Signatures and cite symbol names only — the names are unambiguous and grep-resolvable, and the numbers buy nothing that survives a sibling track's edit. Where a number is genuinely load-bearing (a specific comment or a swallowed body with no unique symbol name), re-derive it at decomposition and say which commit it was read at, the way `plan/track-9.md` does for its measurements.

### T12 [suggestion]
**Certificate**: P6
**Location**: `implementation-plan.md` Track 11 Scope line (re-priced 2026-08-04); `plan/track-11.md:79` (item 9)
**Issue**: the Scope line re-priced earlier today says item 9 covers "19 hand-built AST sites across at least seven files". The figure at HEAD is **18** across exactly **seven** files. Production `new SQL` occurrences in the translator package total 24; six are the `toArray(new SQLBooleanExpression[0])` array idiom inside calls that already route through the builders, leaving 18, distributed exactly as item 9 enumerates them — `StartStepRecogniser` 5, `WalkerContext` 3, `GremlinProjectionAssembler` 3, `GremlinPredicateAdapter` 3, `GremlinAggregateAssembler` 2, `UnionStepRecogniser` 1, `OrderGlobalStepRecogniser` 1. The plus-one came from DR-S1's warning that Track 9's guard "adds one", which is true of the raw grep total (23 at the commit item 9 counted, 24 now) but wrong about what was added: the new site is an array idiom, not a hand-built node, so item 9's own figure of 18 never moved.
**Proposed fix**: correct the Scope line to 18 sites across seven files, and correct DR-S1's warning in the same edit so item 9's audit is not sent looking for a nineteenth site that does not exist. Item 9's stated figure needs no change.

## Evidence base

All searches: `grep -rn` / `grep -rln` plus a Read of every returned site, at HEAD `f2b1230db0`. PSI unavailable (see § Reviewer notes).

#### P1 Premise: the append seam needs a decline channel with no in-repo template
- **Track claim**: DR-T2 — "Both in-repo templates are wrong for this ... The seam therefore pairs a query with the mutator — `supportsListShaping()`, overridden `false` on the adapter."
- **Search performed**: `grep -rn "dropsRowsOnAbsentProperty"` across the translator package; Read of each site.
- **Code location**: `RecognitionContext.java:357` (declaration), `WalkerContext.java:636` (implementation), `SubTraversalPredicateAdapter.java:465-474` (the `false` override plus its rationale javadoc), `RangeGlobalStepRecogniser.java:210` (the read), `:47-50` (the mechanism javadoc).
- **Actual behavior**: `boolean dropsRowsOnAbsentProperty();` — a non-default interface method. The adapter returns `false` under a javadoc reading "Always false, and not delegated to the parent. Since `setResultShaping` is swallowed this context never holds a shaping of its own". The recogniser guards `if (ctx.dropsRowsOnAbsentProperty()) { … }` and its class javadoc describes it as "declines once ... The guard reads one boolean".
- **Verdict**: CONFIRMED (the seam shape is needed) — but the "no template" premise is WRONG; the template is shipped.
- **Detail**: produced T9.

#### P2 Premise: `RecognitionContext`'s cited members exist at the cited lines
- **Track claim**: § Signatures — `appendPostConcatOp` at `:286`, `walkChild` at `:333`; `setResultShaping` a full replace.
- **Search performed**: `grep -n "setResultShaping\|appendPostConcatOp\|walkChild" RecognitionContext.java`.
- **Code location**: `RecognitionContext.java:38` (`interface RecognitionContext extends ParamSink`), `:348` `setResultShaping`, `:373` `default appendPostConcatOp`, `:433` `walkChild`.
- **Actual behavior**: every member exists with the described contract; `appendPostConcatOp` is a `default` that throws, as DR-T2 says. The line numbers are not the cited ones.
- **Verdict**: PARTIAL
- **Detail**: members CONFIRMED, line citations drifted — produced T11.

#### P3 Premise: `SubTraversalPredicateAdapter`'s cited sites exist at the cited lines
- **Track claim**: § Signatures — `:89` shared-registry comment, `:397` `setResultShaping` swallow, `:413` `walkChild`.
- **Search performed**: `grep -n` on the file, then Read of `:452-494`.
- **Code location**: `:100` (`final class … implements RecognitionContext`), `:106` registry comment, `:460` the swallow, `:488` `walkChild`.
- **Actual behavior**: all three sites exist with the described behavior; the swallow's comment reads "boundary row-projection shaping is pinned by terminal recognisers on the outer context only".
- **Verdict**: PARTIAL
- **Detail**: behavior CONFIRMED, line citations drifted by 17 to 75 lines — produced T11.

#### P4 Premise: item 4's second allow-list axis exists and its test fails the build
- **Track claim**: item 4 — "`StepRecogniser.selectsPositionally(Step)`, safe-defaulting to `false`, with a unit test over `POST_UNION_RECOGNISERS` that **fails the build** if a member inherits the default."
- **Search performed**: `grep -rn "POST_UNION_RECOGNISERS\|selectsPositionally"` repository-wide.
- **Code location**: `GremlinStepWalker.java:267` (`static final Set<StepRecogniser> POST_UNION_RECOGNISERS = Set.of(CountGlobalStepRecogniser.INSTANCE, RangeGlobalStepRecogniser.INSTANCE, DedupGlobalStepRecogniser.INSTANCE)`), read at `:442` (the `dispatchAll` gate) and `:629` (the look-ahead); overrides at `CountGlobalStepRecogniser.java:34` and `RangeGlobalStepRecogniser.java:208`; the reflective test at `GremlinStepWalkerTest.java:1328-1345`.
- **Actual behavior**: the test iterates `POST_UNION_RECOGNISERS`, asserts each member declares `selectsPositionally` itself rather than inheriting the default, and fails with the message "is on the post-union allow-list, so it must override selectsPositionally". Membership is exactly the three item 4 names (Track 8 DR-U4), and both readers are the walker's own, so one field covers both paths.
- **Verdict**: CONFIRMED

#### P5 Premise: item 10's five re-measured consolidation counts hold
- **Track claim**: item 10's 2026-08-04 amendment — nine toggle copies, eleven `countBoundarySteps`, five `assertEquivalent` plus six recognition enums, sixteen hand-mirrored `TRANSPARENT_STEPS` sets.
- **Search performed**: `grep -rn "TRANSPARENT_STEPS" core/src`; `grep -rln` per pattern over `core/src/test`.
- **Code location**: `GremlinStepWalker.java:163` (`private static final Set<Class<?>> TRANSPARENT_STEPS`), read at `:374` and `:670`. Zero occurrences under `core/src/test`.
- **Actual behavior**: the identifier exists only in production. File counts for the other four: toggle helper 10, `countBoundarySteps` 10, recognition/cardinality enum 5, `assertEquivalent` 10.
- **Verdict**: WRONG (for the `TRANSPARENT_STEPS` clause); the other four are unreconciled measures.
- **Detail**: produced T10.

#### P6 Premise: item 9's hand-built AST site count
- **Track claim**: item 9 — 18 hand-built nodes; DR-S1 — Track 9's guard "adds one", so the count is stale.
- **Search performed**: `grep -rn "new SQL"` over the translator package excluding `/test/`, then the same minus the `toArray(new SQL` idiom, grouped by file.
- **Code location**: 24 raw production occurrences; 18 after excluding six `toArray` sites; distribution `StartStepRecogniser` 5, `WalkerContext` 3, `GremlinProjectionAssembler` 3, `GremlinPredicateAdapter` 3, `GremlinAggregateAssembler` 2, `UnionStepRecogniser` 1, `OrderGlobalStepRecogniser` 1.
- **Actual behavior**: 18 across seven files, matching item 9's enumeration exactly. The raw total moved 23 → 24 and the `toArray` count 5 → 6.
- **Verdict**: PARTIAL
- **Detail**: item 9's 18 is CONFIRMED; DR-S1's plus-one applies to the array idiom, not to a hand-built node, so the plan's re-priced "19" is wrong — produced T12.

#### P7 Premise: `ResultShaping.withListShapingOps` exists at `:106` with no production caller
- **Track claim**: item 1 — "exists at `ResultShaping.java:106`, replaces the list wholesale, and has no production caller yet."
- **Search performed**: `grep -rn "withListShapingOps"` repository-wide.
- **Code location**: `ResultShaping.java:106` (`public ResultShaping withListShapingOps(@Nonnull List<ListShapingOp> ops)`); six call sites, all in `YTDBMatchPlanStepTest.java` (`:1212`, `:1265`, `:1337`, `:1374`, `:1393`, `:1429`).
- **Actual behavior**: declaration and line number both hold; every caller is a test. `appendListShapingOp` and `supportsListShaping` have zero occurrences repository-wide, consistent with item 1 creating them.
- **Verdict**: CONFIRMED

#### P8 Premise: the boundary base's lifecycle and clone hazard are as described
- **Track claim**: `## Context and Orientation` — seven lifecycle states, three open routes, `apply` called afresh on every open, and `clone()` copying `shaping` by reference while `resetLifecycleForClone()` does not touch it.
- **Search performed**: `grep -n` for the state table and shaping members; Read of `AbstractMatchPlanStep.java:683-700`.
- **Code location**: state table at `:100-113` (NEW, OPEN, DRAINED, REARMED, CLOSED, CLOSED_UNSTARTED, REARMED_AFTER_CLOSE), `shaping` at `:161`, `openShapedPayloads` at `:372`, `applyListShaping` at `:386` with `shaped = op.apply(shaped)` at `:393`, `resetLifecycleForClone` at `:683`.
- **Actual behavior**: seven constants; three transitions open (NEW, REARMED, REARMED_AFTER_CLOSE); `resetLifecycleForClone` sets `openStream`, `armingGraph`, `shapedPayloads` and `state` and does **not** touch `shaping`, so two clones share op instances. `projectOrSkip` at `:694` is a four-arm switch over `ELEMENT` / `MAP` / `SINGLE_VALUE` / `SCALAR` taking one `Result row`, so DR-T1's "an N→1 drain has no expressible arm there" holds.
- **Verdict**: CONFIRMED

#### P9 Premise: item 7 can reach a RID pool without touching `LdbcBenchmarkState`
- **Track claim**: item 7 — the benchmark state resolves RIDs "through the public `isPersonId` / `ic1PersonId` accessors ... `curatedParams` stays private (T5)."
- **Search performed**: `grep -rn "isPersonId\|ic1PersonId\|curatedParams\|loadSqlStatements" LdbcBenchmarkState.java`.
- **Code location**: `:64` `private ParameterCurator.CuratedParams curatedParams`, `:79` `public long isPersonId(long idx)`, `:92` `public long ic1PersonId(long idx)`.
- **Actual behavior**: both accessors are public and dereference the private field internally. Iteration 1's T5 is discharged by the amendment's design.
- **Verdict**: CONFIRMED

#### P10 Premise: item 7's out-of-band harness commits are what the track file says
- **Track claim**: item 7 — "commit `06caa2f962`, based on Track 9's `ffb57fe5cf`"; item 8 — probe at `b1fc04a030`.
- **Search performed**: `git log --oneline -3 t11-item7-jmh`; `git merge-base --is-ancestor 06caa2f962 t11-item7-jmh`.
- **Code location**: branch tip `b1fc04a030` ("Extend the Gremlin JMH harness to LDBC-shaped work") → `06caa2f962` ("Add the on/off Gremlin JMH harness") → `ffb57fe5cf`.
- **Actual behavior**: the chain is exactly as both items describe; `06caa2f962` is an ancestor of the tip, and the branch is unmerged.
- **Verdict**: CONFIRMED

#### I1 Integration: a recogniser reading a context query and declining on it
- **Plan claim**: item 1 — "each recogniser declines when it reads false."
- **Actual entry point**: `RangeGlobalStepRecogniser.java:210`, guarded inside `normalize`; contract described in the class javadoc at `:47-50`.
- **Caller analysis**: one production reader today (`RangeGlobalStepRecogniser`), one production implementer (`WalkerContext:636`), one adapter override (`SubTraversalPredicateAdapter:472`). Grep-based, so additional readers behind polymorphic dispatch are not excluded.
- **Breaking change risk**: none — item 1 adds a sibling query rather than changing this one.
- **Verdict**: MATCHES

#### I2 Integration: the post-union suffix gate item 4 relaxes
- **Plan claim**: item 4 — "both readers are the walker's own — `dispatchAll`'s fail-closed gate and `postUnionSuffixTranslatable`'s look-ahead — so the one field covers both paths."
- **Actual entry point**: `GremlinStepWalker.java:442` (`if (ctx.hasUnionCarrier() && !POST_UNION_RECOGNISERS.contains(recogniser))`) and `:629` (`if (recogniser == null || !POST_UNION_RECOGNISERS.contains(recogniser))`).
- **Caller analysis**: the set is package-private `static final` and read at those two sites plus the reflective test at `GremlinStepWalkerTest.java:1337`; javadoc cross-references at `:577` and `:609`.
- **Breaking change risk**: adding members widens both gates simultaneously, which is what item 4 intends; the reflective test fails the build if a new member inherits the `selectsPositionally` default.
- **Verdict**: MATCHES
