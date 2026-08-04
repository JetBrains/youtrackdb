<!-- MANIFEST
findings: 4   severity: {blocker: 0, should-fix: 2, suggestion: 2}
index:
  - {id: BG1, sev: should-fix, loc: "RecognitionContext.java:404; SubTraversalPredicateAdapter.java:489; SubTraversalPredicateAdapterTest.java:508", anchor: "### BG1 ", cert: C1, basis: "the worked example the whole seam rationale rests on cannot reach the failure it describes — g.V().and(__.out().fold()) declines at the pre-existing edge-bearing child gate under the swallow alternative too, so the same spelling in item 5's decline roster passes under the bug it names"}
  - {id: BG2, sev: should-fix, loc: "SubTraversalPredicateAdapter.java:489-490; SubTraversalPredicateAdapterTest.java:508-509", anchor: "### BG2 ", cert: C2, basis: "two of the four rationale copies state the failure direction backwards (rows survive) against the interface javadoc, the commit message and DR-T2 (rows disappear); the direction also flips per combinator, so no single sentence covers the family"}
  - {id: BG3, sev: suggestion,  loc: "RecognitionContext.java:358-361", anchor: "### BG3 ", cert: C3, basis: "the setResultShaping javadoc states the last-step rule in the present tense as one of two protections against clobber; no code at this commit enforces it, so the stated guarantee is unverifiable until item 4a lands"}
  - {id: BG4, sev: suggestion,  loc: "RecognitionContext.java:395-400; WalkerContext.java:654-663", anchor: "### BG4 ", cert: C4, basis: "the two-context taxonomy (top level true / combinator child false) omits the union fork, a third context that gets its own WalkerContext and answers true, which is the path DR-T3 requires a separate gate for"}
evidence_base: {section: "## Evidence base", certs: 9, matches: 4}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: CONFIRMED, anchor: "#### C3 "}
  - {id: C4, verdict: CONFIRMED, anchor: "#### C4 "}
  - {id: C5, verdict: REFUTED,   anchor: "#### C5 "}
  - {id: C6, verdict: REFUTED,   anchor: "#### C6 "}
  - {id: C7, verdict: REFUTED,   anchor: "#### C7 "}
  - {id: C8, verdict: REFUTED,   anchor: "#### C8 "}
  - {id: C9, verdict: REFUTED,   anchor: "#### C9 "}
flags: [CONTRACT_OK, GREP_NOT_PSI, NO_TEST_RUN]
-->

## Findings

### BG1 [should-fix] The worked example the seam rationale rests on cannot reach the failure it describes

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RecognitionContext.java` (line 402-405), with the same claim at `SubTraversalPredicateAdapter.java:488-490` and `SubTraversalPredicateAdapterTest.java:507-509`

**Issue**: The javadoc justifies the boolean decline channel over the swallow alternative with one worked example: a swallowed append "turns `g.V().and(__.out().fold())` into an existence filter … so rows disappear with nothing to see". That spelling declines today for a reason unrelated to list shaping, and it would decline under the swallow too, so it demonstrates nothing about the choice it is cited for.

`AndStepRecogniser.recognize` (line 46-52) walks each child through `walkChild`, then calls `ConnectiveStepSupport.anyEdgeBearing(adapters)` and declines when any child contributed a hop. `__.out()` sets `hasEdges` on the adapter (`SubTraversalPredicateAdapter:319`), so the `AndStep` declines before anything is committed to the parent — the child's `fold()` never gets to influence the answer. Under the swallow the child sub-walk would accept and still be edge-bearing, so the gate fires and the whole traversal declines to native, which is the correct answer. Under the seam as shipped the child declines first and the traversal declines to native as well. The two designs are observationally identical on this spelling. The same holds for `or`, `where` and `filter`, which route through `ConnectiveStepSupport.commitPositiveFilterChild` (line 130-140) and decline on `adapter.hasEdges()`.

The consequence reaches the tests. `## Plan of Work` item 5 still lists `g.V().and(__.out().fold())` as a decline case, described as "a silent wrong answer if missed". A result-comparison test over that spelling passes whether or not the seam exists, which is the same non-discriminating-witness family A14, A15 and A17 already removed three cases from. Step 1's own white-box test (`supportsListShaping_falseOnSubWalk_trueOnTheParentItWraps`) is fine — it reads the boolean directly — but it inherits the false rationale in its javadoc.

Two reachable shapes do demonstrate the harm, and they differ in kind. `not(...)` accepts an edge-bearing child as a detached anti-join (`NotStepRecogniser:85-116`), so `g.V().not(__.out().fold())` is a live wrong answer under the swallow: native returns nothing (the child always has a result, so the `not` is false for every vertex) while the swallowed translation returns every vertex with no out-edge. A hop-free child under `and` / `where` is the other, for example a child ending in `fold()` over a property projection rather than a hop: there native passes every row and the swallowed translation drops the rows whose key is absent.

**Evidence**: `AndStepRecogniser.java:46-52` → `ConnectiveStepSupport.java:113-120` (`anyEdgeBearing`) and `:130-140` (`commitPositiveFilterChild`); `SubTraversalPredicateAdapter.java:319`/`:333` (`hasEdges` set by `addEdge` / `addEdgeAsNode`); `NotStepRecogniser.java:85-116` (edge-bearing branch accepted). Certificate C1.

**Refutation considered**: whether `ConnectiveStrategy` or `InlineFilterStrategy` rewrites the shape before the translator sees it, so that the `AndStep` never reaches `AndStepRecogniser` — the child holds a hop, so the all-filter inline path does not apply, and either way a rewritten shape makes the quoted example even less reachable. Whether the fold recogniser accepting inside the child could clear `hasEdges` — it cannot; the flag is set by the hop's own `addEdge` / `addEdgeAsNode` and nothing resets it. Whether the harm claim is about a future tree rather than this one — item 4 adds gates but removes none, and the edge-bearing decline predates this track.

**Suggestion**: replace the example in all three sites with one that discriminates. `g.V().not(__.out().fold())` is the cheapest: it is reachable today, its native answer (empty) and its swallowed answer (the sink vertices) differ on any graph with a sink, and it keeps the "a dry upstream still emits one empty list" mechanism as the reason. Then correct item 5's decline roster to the same spelling, or drop its claim that the roster witnesses the swallow and let the white-box pin carry it (A14's second option, which step 1 already implements).

### BG2 [should-fix] Two of the four rationale copies state the failure direction backwards

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/SubTraversalPredicateAdapter.java` (line 489-490), and `SubTraversalPredicateAdapterTest.java` (line 508-509)

**Issue**: The four copies of the rationale disagree about which way the row set moves under the swallow, and the two named above pick the direction the cited mechanism rules out. The adapter javadoc ends "so every row the `and` should have dropped survives instead"; the test javadoc ends "so every row the `and` should drop would survive". Both cite the fold semantics one clause earlier: a dry upstream still emits one empty list. That is exactly why native `and(__.out().fold())` drops nothing — the child always has a result, so the filter is true for every vertex. A swallow can therefore only remove rows on an `and` / `where` shape, never let extra ones survive. The interface javadoc (`RecognitionContext:405`) and the commit message both say "rows disappear", and DR-T2 says "rows silently disappear".

All four copies also attach "always true" to the translated form ("becomes an existence filter that is always true", "translated as an always-true existence filter"). The always-true one is the native answer; the swallowed translation is a plain existence filter, which is the whole point. As written, the interface sentence contradicts itself — an always-true filter cannot make rows disappear.

The direction is worth getting right because it does not generalise: on `not(...)` the swallow adds rows rather than losing them (native returns nothing, the anti-join returns the sinks), which is probably why the copies drifted apart in the first place. A single sentence cannot cover the family, so whichever example BG1 settles on decides the wording.

Concretely, an implementer building item 5's witness from the adapter javadoc expects the buggy arm to return *more* rows than the correct one and sizes the fixture accordingly — a graph where every vertex has an out-edge satisfies that reading and makes both arms agree, so the witness passes under the bug.

**Evidence**: `SubTraversalPredicateAdapter.java:488-490` and `SubTraversalPredicateAdapterTest.java:507-509` against `RecognitionContext.java:404-405`, the commit message of `0eaf97ad07`, and `plan/track-11.md` DR-T2. TinkerPop's fold-on-empty behaviour is the one both sides cite and `## Context and Orientation` states. Certificate C2.

**Refutation considered**: whether "should have dropped" could mean "should have dropped under the translation" rather than under the correct answer — the sentence contrasts the buggy translation with what the `and` ought to do, so the referent is the correct answer, and under the correct answer nothing is dropped. Whether the sentence might be describing a different combinator where survival is the right direction — `not(...)` is that combinator, but the sentence names `and`. Whether the two phrasings could both be true of different graphs — no: for a fixed spelling the swallowed translation is a subset of the native answer on `and` / `where` for every graph.

**Suggestion**: make one site canonical (the interface's `supportsListShaping()` javadoc is the natural home, and `review-test-structure`'s TS4 asks for the same consolidation for a different reason) and state the mechanism in two steps rather than one clause: native answers the shape with every row because a dry upstream still emits one empty list; a swallowed append reduces it to a bare existence filter, so rows disappear. Reduce the adapter javadoc, the `appendListShapingOp` inline comment and the test javadoc to a one-line summary plus a `{@link}`. If BG1's replacement example is the `not(...)` spelling, the direction sentence inverts and needs to say so.

### BG3 [suggestion] The last-step rule is stated in the present tense with nothing enforcing it yet

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RecognitionContext.java` (line 358-361)

**Issue**: The new paragraph names two rules that "keep the write paths from colliding", the first being that "the list-shaping terminators are accepted only as the traversal's last step, so no flag-pinning terminator can follow one". At this commit nothing enforces that. The only readers of `ResultShaping.listShapingOps()` in production are `WalkerContext.appendListShapingOp` (line 649) and `AbstractMatchPlanStep.applyListShaping` (line 387); `dispatchAll`'s two in-loop gates read the union carrier and the captured cardinality clause and neither looks at the op list. The gate lands in item 4a. The second rule checks out — `UnionStepRecogniser` calls `setResultShaping(agreedShaping)` at line 124, before the walker dispatches any post-union suffix step — so the paragraph is half verifiable and half forward-looking, with nothing in the text separating the two.

The wording follows `## Plan of Work` item 1, which asked for both limits to be written down in this commit, so this is about tense rather than about the claim being wrong. Nothing appends an op yet, so there is no live defect; the cost is that a reader at this commit (or a bisect landing here) reads a shipped guarantee where there is an intention.

**Evidence**: grep over `core/src/main` for `listShapingOps` returns `WalkerContext:649`, `AbstractMatchPlanStep:158`/`:387`, `ResultShaping`, `ListShapingOp` and `PostConcatOp` javadoc only; `GremlinStepWalker.dispatchAll` (line 415-451) reads `hasUnionCarrier()` and `capturedCardinalityClause(ctx)`. `UnionStepRecogniser.java:124`. Certificate C3.

**Refutation considered**: whether a recogniser-level position check already exists that grep would miss — no list-shaping recogniser exists at all yet, so there is nothing to carry one. Whether the plan intends the rule to be a recogniser obligation rather than a walker gate — R10 moved it to the walker precisely so no recogniser has to remember it, and item 4a is where it lands.

**Suggestion**: mark the first rule as pending in one clause ("once item 4a's walker gate lands, the list-shaping terminators are accepted only as …"), or state it as the constraint the terminators are being built under rather than as current behaviour. The second rule can stay in the present tense.

### BG4 [suggestion] The context taxonomy behind `supportsListShaping()` omits the union fork

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RecognitionContext.java` (line 395-400), and `WalkerContext.java` (line 654-663)

**Issue**: The javadoc presents two kinds of context: "The top-level walk answers `true`; a combinator child sub-walk answers `false`, and that answer is the decline channel for all four list-shaping terminators." A third kind exists. `UnionForkHostImpl.walkFork` builds a flat `prefix ++ childSuffix` traversal and runs `GremlinStepWalker.production().walk(forked, prefix.size())` (line 92), which constructs a fresh `WalkerContext` (`GremlinStepWalker:365`). A union arm is therefore a full top-level walk whose context answers `true`, and a trailing `fold()` in an arm appends onto that arm's own shaping. `WalkerContext.supportsListShaping()`'s javadoc reinforces the two-way reading: "this is the top-level walk's own context, so … an appended op reaches the projected payload stream", which is true of a fork context as well but means something different there.

That path is the one DR-T3 says must decline: native gives one list per arm, the translation gives one list over the concatenation, and today the only thing preventing it is that per-call lambda instances make `agreedShaping.equals(childResult.shaping())` fail by accident. Item 4 adds the explicit non-empty-`listShapingOps` gate in the child loop. An implementer reading only this javadoc has grounds to think the seam already covers every child path.

**Evidence**: `UnionForkHostImpl.java:74-92`; `GremlinStepWalker.java:365`; `UnionStepRecogniser.java:95` (`host.walkFork`), `:101-111` (the agreement comparison), `:124`; `plan/track-11.md` DR-T3. Certificate C4.

**Refutation considered**: whether `walkFork` reuses the parent context, in which case the arm would inherit the parent's answer — it does not; it calls the static `walk` entry point, which builds its own context and its own fork host. Whether an appended op in an arm is discarded anyway, making the `true` harmless — it is not discarded: it travels in `childResult.shaping()` into `agreedShaping` and then onto the parent through `setResultShaping` at line 124.

**Suggestion**: add one sentence to the interface javadoc naming the union fork as a top-level walk that answers `true`, and pointing at the separate child-loop gate as the decline for that path. One clause on `WalkerContext.supportsListShaping()` saying the answer is about the context's own boundary, not about being the outermost walk, closes the second half.

## Evidence base

#### C1 `g.V().and(__.out().fold())` cannot reach the claimed wrong answer — CONFIRMED

Survived. `AndStepRecogniser:46-52` → `ConnectiveStepSupport.anyEdgeBearing` (`:113-120`) declines whenever a child set `hasEdges`, which `__.out()` does at `SubTraversalPredicateAdapter:319`, so both the swallow design and the shipped seam decline this spelling to the native pipeline; `or` / `where` / `filter` decline the same way through `commitPositiveFilterChild` (`:130-140`), and only `not(...)` (`NotStepRecogniser:85-116`) accepts an edge-bearing child.

#### C2 The swallow removes rows on `and` / `where` and adds them on `not` — CONFIRMED

Survived. Native `and(__.out().fold())` passes every vertex because the fold emits one empty list on a dry upstream (the mechanism all four copies cite), so a swallowed append can only narrow the answer there, while `not(__.out().fold())` is false for every vertex natively and the swallowed anti-join returns the sinks — which makes "every row the `and` should have dropped survives" wrong for the spelling it is attached to.

#### C3 No code enforces the last-step rule at `0eaf97ad07` — CONFIRMED

Survived. The production readers of `listShapingOps()` are `WalkerContext:649` and `AbstractMatchPlanStep:387` only, and `dispatchAll`'s two in-loop gates (`GremlinStepWalker:442`, `:449`) read the union carrier and the captured cardinality clause, so no dispatch-time position check over list-shaping ops exists yet.

#### C4 A union arm is a top-level walk with its own context answering `true` — CONFIRMED

Survived. `UnionForkHostImpl.walkFork:92` calls `GremlinStepWalker.production().walk(forked, prefix.size())`, which builds a new `WalkerContext` at `GremlinStepWalker:365`, and the arm's shaping reaches the parent through `UnionStepRecogniser:105`/`:124`.

#### C5 `appendListShapingOp` publishes a caller-visible mutable list into the pinned shaping — REFUTED

Claim: `WalkerContext.appendListShapingOp` (line 648-652) builds `new ArrayList<>(shaping.listShapingOps())`, mutates it, and hands it to `withListShapingOps`, so the record could end up aliasing a list the method still holds, and a later append could mutate a shaping already read by the boundary step.

Checked `ResultShaping`'s compact constructor: line 55-58 runs `listShapingOps = List.copyOf(listShapingOps)`, so `withListShapingOps` (line 106) snapshots the argument and the `ArrayList` the method built is unreachable after the assignment. The local is also discarded at method exit. Each append produces a fresh immutable list, so a `ResultShaping` instance already handed to `AbstractMatchPlanStep` cannot change underneath it.

Verdict: not a defect. The append is a correct copy-on-write over an immutable record, it preserves the seven flags (the failure mode the plan named), and declared order matches the order `applyListShaping` (line 386-396) applies the ops in.

#### C6 The adapter's `UnsupportedOperationException` escapes `TraversalStrategy.apply()` — REFUTED

Claim: `SubTraversalPredicateAdapter.appendListShapingOp` (line 498-507) throws unconditionally, and the inline comment's assertion that the strategy's net catches it may be stale, in which case a recogniser bug becomes a user-visible query failure rather than a decline.

Checked `GremlinToMatchStrategy.apply` (line 213-235): the body is wrapped in a `try` that catches `ReservedAliasException` first and re-throws it, then catches `RuntimeException` and degrades to a native decline; `Error` and `AssertionError` are deliberately outside the catch. `UnsupportedOperationException` is a `RuntimeException`, so it lands in the decline arm.

Verdict: not a defect, and the comment is accurate — including its point that this is why the throw cannot serve as the decline channel. A16 recorded the same correction in DR-T2.

#### C7 The union half of the `setResultShaping` javadoc's collision argument is false — REFUTED

Claim: the paragraph says `UnionStepRecogniser` "calls this with the agreed child shaping before any post-union suffix op appends"; if the union recogniser ran after some suffix step, or pinned the shaping on a different context, a post-union append would be clobbered.

Traced the union path: `UnionStepRecogniser.recognize` is dispatched at the `UnionStep` itself and calls `ctx.setResultShaping(agreedShaping)` at line 124 before returning `ACCEPTED`; the walker's `dispatchAll` loop only then reaches the suffix steps, and the post-union gate at `GremlinStepWalker:442` restricts which recognisers may claim them. Nothing on the parent context can append before the union step is dispatched, because a list-shaping terminator ahead of a union is not a shape any recogniser claims.

Verdict: the claim holds as written. Only the first of the paragraph's two rules is unverifiable at this commit, which is BG3.

#### C8 The multi-plan result drops the shaping, so a post-union append is silently lost — REFUTED

Claim: `UnionStepRecogniser`'s own comment says "the prefix-only pattern on this context is discarded by `buildResult` when the union carrier is present", so `WalkerContext.supportsListShaping()` returning an unconditional `true` may be wrong on the union path — an appended op would answer `true` and then vanish.

Read `GremlinStepWalker.buildResult` (line 717-775): the multi-plan branch passes `ctx.shaping()` to `TranslationResult.multiPlan` (line 726) and the single-plan branch passes it to `singlePlan` (line 774). Only the pattern is discarded on the union path, not the shaping. `MultiPlanMatchStep` inherits `applyListShaping` from `AbstractMatchPlanStep`.

Verdict: not a defect. The `true` answer is sound for both result shapes; the gap on that path is documentation only (BG4).

#### C9 A third `RecognitionContext` implementation exists and no longer compiles — REFUTED

Claim: `supportsListShaping()` and `appendListShapingOp` are declared non-default, so every implementer must supply a body; a hand-written test stub or an anonymous implementation elsewhere would break the build.

Searched for `implements RecognitionContext` across the repository (excluding `.claude/worktrees`) and for `new RecognitionContext` in `core`, `server`, `embedded`, `tests`: two implementers only, `WalkerContext:45` and `SubTraversalPredicateAdapter:101`, both in the same package, both updated in this commit. The single `new RecognitionContext` hit is `new RecognitionContext.PropertyProjection(...)` at `GremlinProjectionAssembler:106`, a nested record. The interface is package-private, so no other module can implement it.

Verdict: not a defect. This negative is grep-based rather than PSI-backed — see the reference-accuracy note below.

## Reviewer notes

**Reference accuracy: grep, not PSI.** mcp-steroid is reachable and `steroid_list_projects` reports the IDE open on this working tree, but `steroid_execute_code` timed out on a single find-implementations plus find-usages script (the cold Kotlin scripting host exceeds the MCP call limit on this repository — the same failure this track's `### Clarifications` records and the step-1 implementer hit). Findings BG1, BG2 and BG3 rest on control-flow traces and on reads of the returned sites, which are reliable. The two negatives that carry weight are grep-bounded rather than established: "no production caller of `appendListShapingOp` / `supportsListShaping` exists" (BG3, C3) and "only two implementers of `RecognitionContext`" (C9). Both symbols are new and unique, so a textual sweep is close to exhaustive here, and the interface's package-private scope bounds C9 structurally — but a PSI re-check is cheap if the IDE recovers.

**No test run.** The two new test classes were not executed. CLAUDE.md forbids concurrent test processes in one worktree and this review runs beside other track agents, so a `-Dtest=` run risked a false failure elsewhere. The findings are static and none of them depends on a test outcome; compile-level checks were done by inspection (`ArrayList` already imported at `WalkerContext:22`, `assertThatThrownBy` already imported in the adapter test, `ListShapingOp` needed no import for the lambda arguments).

**Scope note on the ops themselves.** The seam introduces no shared mutable state — one `WalkerContext` per walk, and a `DECLINE` discards the whole context per the class contract at `WalkerContext:39-43`, so a partial append never leaks. The shared-state hazard this track carries lives one step later: `## Context and Orientation` records that `AbstractStep.clone()` copies `shaping` by reference while `resetLifecycleForClone()` leaves it alone, so two clones share the same `ListShapingOp` instances, and the two ops that need buffers (`fold`, `tail`) are the ones most likely to be written as singletons. No such op exists in this diff. Worth a concurrency triage when steps 2-3 land.
