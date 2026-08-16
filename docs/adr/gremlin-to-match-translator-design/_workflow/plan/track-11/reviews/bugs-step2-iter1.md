<!-- MANIFEST
findings: 4   severity: {blocker: 0, should-fix: 2, suggestion: 2}
index:
  - {id: BG1, sev: should-fix, loc: GremlinStepWalker.java:659, anchor: "### BG1 ", cert: C1, basis: "one identity-keyed set answers both may-follow and is-drain, so item 4b's drain-last-behind-a-stage row is inexpressible: reverse().fold() over-declines and the single-set fix admits fold().unfold()"}
  - {id: BG2, sev: should-fix, loc: GremlinStepWalker.java:639, anchor: "### BG2 ", cert: C2, basis: "the stated membership test admits a setResultShaping caller, which drops every captured op; nothing pins the invariant, so an allow-listed unfold that pins a flag loses a preceding reverse silently"}
  - {id: BG3, sev: suggestion, loc: RecognitionContext.java:358, anchor: "### BG3 ", cert: C3, basis: "setResultShaping's rewritten rule says a terminator is accepted only as the last step, which the same commit's may-follow relaxation contradicts"}
  - {id: BG4, sev: suggestion, loc: GremlinStepWalker.java:461, anchor: "### BG4 ", cert: C4, basis: "the gate makes post-union fold/tail unreachable, voiding item 4's tail-ahead-of-count constraint and item 4c's decision; restoring them by widening the allow-list ships count(*) over pre-stage rows"}
evidence_base: {section: "## Evidence base", certs: 12, matches: 12}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: CONFIRMED, anchor: "#### C3 "}
  - {id: C4, verdict: CONFIRMED, anchor: "#### C4 "}
  - {id: C5, verdict: REFUTED, anchor: "#### C5 "}
  - {id: C6, verdict: REFUTED, anchor: "#### C6 "}
  - {id: C7, verdict: REFUTED, anchor: "#### C7 "}
  - {id: C8, verdict: REFUTED, anchor: "#### C8 "}
  - {id: C9, verdict: REFUTED, anchor: "#### C9 "}
  - {id: C10, verdict: REFUTED, anchor: "#### C10 "}
  - {id: C11, verdict: REFUTED, anchor: "#### C11 "}
  - {id: C12, verdict: REFUTED, anchor: "#### C12 "}
flags: [CONTRACT_OK]
-->

## Findings

### BG1 [should-fix] One allow-list answers two different questions, so item 4b's third row cannot be expressed

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinStepWalker.java` (line 659, with the rule at 688-691 and the latch at 488-490)

**Issue:** `POST_LIST_SHAPING_RECOGNISERS` is read at two sites that ask different questions of it. At the gate (line 461-465) it answers "may this recogniser claim a step behind a captured stage?"; at the latch (line 488) it answers "was the recogniser that just appended a drain?". Item 4b states three rows, not two: `unfold` / `reverse` may follow a stage, nothing may follow `fold` / `tail`, and `fold` / `tail` must be **last** — which is a position rule, so a drain sitting last behind a per-payload stage satisfies it. The shipped rule can express the first two rows and declines the third.

With the allow-list the javadoc plans (`{unfold, reverse}`), `g.V().values("name").reverse().fold()` declines the whole walk: `reverse` appends and stays off the latch, then `fold` reaches the gate with `carriesListShapingOp()` true and `mayFollow.contains(fold)` false. The same holds for `unfold().fold()` and `reverse().tail(3)`. `AbstractMatchPlanStep.applyListShaping` (lines 386-396) threads the ops left to right over the projection stream, so `[reverse, fold]` produces exactly the native answer — the declined shapes are correct ones.

The trap is the natural repair. Adding `FoldStepRecogniser` to the allow-list to recover them also clears the latch when `fold` appends, because the latch tests the same set, so `fold().unfold()` and `fold().tail(3)` start translating — the shapes `mayFollowListShaping`'s own javadoc (line 670-671) names as the reason the latch exists, and the shapes `## Context and Orientation` requires declined. The same fail-open appears if step 4 implements the four terminators as fewer recogniser instances than step classes: one instance serving both a per-payload step and a drain step is either in the set (latch never arms) or out of it (nothing composes).

**Evidence:** `mayFollowListShaping` is `!afterDrain && mayFollow.contains(recogniser)` (line 690) and the latch is `capturedListShapingOp(ctx) && !POST_LIST_SHAPING_RECOGNISERS.contains(recogniser)` (line 488). Both key on recogniser identity against the same field, so membership cannot be true for the gate and false for the latch on one recogniser. Item 4b in `plan/track-11.md` line 88 states the position rule; the step's own episode draft names `values("name").reverse().fold()` as collateral, which is the same shape from the other side.

**Refutation considered:** Checked whether the declined shapes are unsafe rather than merely unsupported — they are not: `applyListShaping` applies ops in declared order over the post-projection stream, which is where Gremlin applies them, and `ListShapingOp`'s javadoc (lines 8-11) states the ordered-carrier property the diff relies on for `reverse().unfold()`. Checked whether D3's "last step only" rule already forbids them — item 4b is the relaxation of D3 that this step implements, and it forbids only what follows a drain. Checked whether the shipped set can be extended without the fail-open — it cannot, because the latch reads it. Checked reachability today: no production recogniser appends, so nothing regresses now; the cost lands on steps 3 and 4.

**Suggestion:** Split the two questions. Keep `POST_LIST_SHAPING_RECOGNISERS` as the may-follow set and add a second, drain-only set the latch reads (`LIST_SHAPING_DRAIN_RECOGNISERS = {fold, tail}`), so `fold` can be admitted behind a stage while still arming the latch. If the collateral decline is instead accepted deliberately, say so at the field — the javadoc's current argument for excluding `fold` / `tail` is about what follows a drain, which the latch already handles, so it does not carry the exclusion — and amend item 4b so step 4's author does not read the plan and the code as agreeing when they do not.

### BG2 [should-fix] The stated membership test admits a recogniser that silently drops every captured op

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinStepWalker.java` (lines 639-641)

**Issue:** The membership paragraph reads "Membership asks what `POST_CARDINALITY_RECOGNISERS` asks — can this recogniser change the row set, its order, or its multiplicity as the statement sees it". That test is sufficient for the cardinality gate, where the hazard is clause timing, and insufficient here, where a second hazard exists: `setResultShaping` replaces the whole record including `listShapingOps` (`RecognitionContext` line 351). A member that pins any shaping flag through `setResultShaping` therefore erases the stage the gate just admitted it behind, and the walk still returns ACCEPTED, so `buildResult` ships the clobbered shaping and the boundary applies no stage.

The three recognisers the paragraph points at as the model all do exactly that: `PropertiesStepRecogniser` through `GremlinProjectionAssembler` line 109 and `PropertyMapStepRecogniser` / `ElementMapStepRecogniser` through line 175, each rebuilding from `ResultShaping.NONE`. The reachable instance is not a hypothetical projection but step 4's own `unfold`: `UnfoldStep.flatMap` dispatches five ways and the `MAP` arm is live (`## Context and Orientation`), so an `unfold` recogniser that pins a payload-shape flag through `setResultShaping` will pass the stated test, be admitted by the gate, and drop a preceding `reverse` — turning `g.V().values("name").reverse().unfold()` into an unreversed result while the gate reports the composition as sanctioned. Nothing catches it: the sibling `POST_UNION_RECOGNISERS` has a reflective test that fails the build when a member skips its second axis, and this field has no equivalent.

**Evidence:** `setResultShaping`'s own javadoc states the clobber ("a later `setResultShaping` still overwrites every op appended before it", line 355-356). `WalkerContext.carriesListShapingOp()` reads `!shaping.listShapingOps().isEmpty()` (line 677), so the gate disarms in the same instant the ops vanish — the walk's later steps see a clean context and the loss produces no decline anywhere. The `POST_LIST_SHAPING_RECOGNISERS` opening sentence ("whose entire contribution is one more stage on the same stream") is the correct criterion; the operative paragraph below it is the imported one.

**Refutation considered:** Checked whether the loop already protects the ops — it does not; the gate runs before the recogniser and reads only whether ops exist, never whether they survived. Checked whether an allow-listed member could be prevented from calling `setResultShaping` by the interface — no, both context implementations expose it. Checked whether the four planned terminators avoid the mutator today — DR-T1 keeps `outputType` where the preceding step pinned it, so as specified they only append; the risk arrives with any flag a payload-reshaping terminator turns out to need, which is step 4's open question. Checked whether the clobber is reachable through the current allow-list — it is empty, so this is latent, which is why it is should-fix rather than blocker. Reference accuracy: the `setResultShaping` caller set was established by grep (`steroid_execute_code` times out on this repository), so an indirect caller reached through a helper I did not read could exist; the three assembler sites above are direct and verified.

**Suggestion:** State the second condition at the field — a member contributes only through `appendListShapingOp` and never calls `setResultShaping` — and pin it mechanically rather than by javadoc. The cheapest pin is in the loop itself: capture `boolean carriedBefore = capturedListShapingOp(ctx)` beside `positionBefore` and assert `!carriedBefore || capturedListShapingOp(ctx)` after an ACCEPTED, so a member that drops the stage fails loudly under `-ea` instead of returning a wrong shape. A unit test over the field in the shape of the `POST_UNION_RECOGNISERS` reflective test is the alternative.

### BG3 [suggestion] `setResultShaping`'s rewritten rule states the pre-relaxation last-step rule the same commit relaxes

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RecognitionContext.java` (lines 358-366)

**Issue:** The paragraph now reads "A list-shaping terminator is accepted only as the traversal's last step, so no flag-pinning terminator can follow one". Under the rule the same commit introduces a terminator is accepted mid-position: `GremlinStepWalker` lines 646-648 say `reverse().unfold()` and `unfold().reverse()` are both accepted, so `reverse` is a terminator claimed with a step after it. A reader of the seam contract gets D3's superseded formulation, and the two files disagree about what the gate guarantees. The consequence is not cosmetic for step 3 and 4: an implementer who believes terminators are last-step-only will not ask whether the flag it pins survives a preceding append, which is the question BG2 is about.

The paragraph also carries "and no flag-pinning recogniser is on that list" as a maintained invariant. It holds vacuously while the list is empty and nothing enforces it afterwards.

**Evidence:** The contradiction is internal to commit `57bc6cac1b`: `RecognitionContext` line 358-359 against `GremlinStepWalker` line 644-648. `carriesListShapingOp()`'s own javadoc (line 435-437) states the rule correctly as a may-follow gate, so the file already holds both readings.

**Refutation considered:** Checked whether "accepted only as the last step" could be true of the *drains* only, making the sentence a loose but defensible summary — the sentence's subject is "a list-shaping terminator", all four, and the clause it justifies ("no flag-pinning terminator can follow one") needs only the may-follow rule, which the colon clause states accurately. Checked whether the claim is load-bearing anywhere downstream — no code reads it, so this is documentation only, hence suggestion.

**Suggestion:** Restate the first rule as the gate actually enforces it: nothing may claim a step behind a captured op unless the walker's may-follow allow-list admits it, and no member of that list writes shaping wholesale. Then the sentence stays true when step 4 lands `reverse().unfold()`, and it names the invariant BG2 asks to be pinned.

### BG4 [suggestion] The gate makes post-union `fold` / `tail` unreachable, voiding item 4's and 4c's constraints

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinStepWalker.java` (lines 461-465)

**Issue:** Item 4 requires a `tail` recogniser to answer `selectsPositionally` true, "which leaves it translatable only ahead of an immediate `count()`", and item 4c decides `fold`'s answer with one option costed as "leaving `union(...).fold().count()` translatable (both arms give 1)". Both spellings now decline at this gate: `tail` / `fold` appends an op and arms the latch, then `count` reaches the gate with `carriesListShapingOp()` true and is refused. `union(...).tail(1)` without the `count` is already refused by `postUnionSuffixTranslatable`'s positional mirror. So after this step, adding either drain to `POST_UNION_RECOGNISERS` is dead weight and 4c's choice between its two answers has no observable difference.

The gate's decline is the right answer — `count(*)` rides the statement and would count the concatenation's rows rather than the one drained payload, which is the `.fold().count()` defect this diff's own javadoc names. The hazard is the direction step 5 might take to satisfy the plan text: admitting `CountGlobalStepRecogniser` to `POST_LIST_SHAPING_RECOGNISERS` restores `union(...).fold().count()` and ships a wrong scalar.

**Evidence:** Traced `union(a,b).tail(1).count()` through `dispatchAll` with step 5's `POST_UNION_RECOGNISERS` extended: the union gate (line 447) and cardinality gate (line 454) pass, the list-shaping gate passes for `tail` (no op yet), the latch arms at line 488 because `tail` is off the allow-list, and `count`'s dispatch returns false at line 464. `UnionStepRecogniser` line 124 puts the arms' agreed shaping on the parent context, so an arm-side drain arms the same latch. Per-payload post-union suffixes are unaffected, matching DR-T3's note that only `fold` and `tail` diverge over a concatenation.

**Refutation considered:** Checked whether the declined shapes are actually correct under translation — they are not: both `fold().count()` and `tail(1).count()` compile a statement-level count that ignores the stage, so declining them is required. Checked whether `postUnionSuffixTranslatable` should mirror this gate the way it mirrors the positional one — not for correctness, since the in-loop gate still declines; only to avoid N discarded sub-walks per compilation, which is a performance question for another dimension. Checked whether step 5 could reach the same conclusion unaided — item 4's text asserts the opposite, so the collision is worth writing down before it is implemented.

**Suggestion:** Record at the field, or in the track file's decision log, that post-union drains are unreachable by construction after this gate, so step 5 keeps `fold` and `tail` out of `POST_UNION_RECOGNISERS` (4c's plainer reading) and no author widens the list-shaping allow-list to restore a shape that cannot translate.

## Evidence base

#### C1 BG1 — the two allow-list reads ask different questions and one identity-keyed set cannot answer both: CONFIRMED, traced through `mayFollowListShaping` (line 690) and the latch (line 488) against item 4b.

#### C2 BG2 — the imported membership test admits a `setResultShaping` caller and the clobber is silent: CONFIRMED against `RecognitionContext` line 351-356 and `GremlinProjectionAssembler` lines 109 / 175.

#### C3 BG3 — the rewritten `setResultShaping` rule contradicts the same commit's may-follow relaxation: CONFIRMED against `GremlinStepWalker` lines 644-648.

#### C4 BG4 — post-union drains are unreachable after this gate, so item 4's and 4c's post-union constraints are void: CONFIRMED by tracing the extended `POST_UNION_RECOGNISERS` case through `dispatchAll`.

#### C5 REFUTED — the latch arms on a recogniser that appended nothing

**Claim:** the latch condition at line 488 tests only "ops exist and the recogniser is off the allow-list", not "this recogniser appended", so a recogniser that appends nothing while ops are already present arms the latch and over-declines the rest of the walk.

**Check:** the gate at 461-465 runs first, so reaching the latch means one of two states. Either `carriesListShapingOp()` was false at the gate, in which case the ops appeared during this recogniser's run and the classification is exact; or it was true, in which case `mayFollowListShaping` returned true, which requires the recogniser to be in the allow-list, which makes the latch's second term false. There is no third path: a non-member cannot pass the gate while ops exist.

**Verdict:** REFUTED. The proxy classification is precise under the current gate ordering. It stops being precise if the gate is ever moved after the recogniser call, so the ordering is load-bearing and undocumented — worth one clause at the latch comment, not a finding.

#### C6 REFUTED — a sub-walk runs behind a captured op, so the adapter's hard `false` hides a stage

**Claim:** `SubTraversalPredicateAdapter.carriesListShapingOp()` returns false unconditionally, so a child dispatched behind a parent's captured stage is gated on nothing.

**Check:** there are two routes into a sub-walk and both are gated at the parent's level. `walkChild` is called only from `ConnectiveStepSupport` (and/or), `NotStepRecogniser`, `TraversalFilterStepRecogniser`, and `WhereTraversalStepRecogniser` — every one reached through a step the loop dispatches, so the parent gate refuses it behind a captured op. `walkFork` is called only from `UnionStepRecogniser`, likewise loop-dispatched, and it walks each arm through a fresh `WalkerContext`, so an arm's own ops are visible to the arm's own gate. Nested adapters delegate nothing here, so no parent value leaks in.

**Verdict:** REFUTED. The adapter's javadoc claim holds — conditionally on no combinator recogniser ever joining the may-follow allow-list, which BG2's suggested pin would also cover.

#### C7 REFUTED — shipping `POST_LIST_SHAPING_RECOGNISERS` empty leaves a silent hole

**Claim:** the admit branch is unreachable end-to-end, so the rule ships unexercised and something could slip through it.

**Check:** an empty set makes `mayFollowListShaping` return false for every input, which collapses the gate to "any step behind a captured op declines" — the fail-closed reading, never fail-open. The decline branch is exercised end-to-end by `walk_stepBehindACapturedListShapingOp_declinesTheWholeWalk` with a fixture appender plus a same-registry control, and the commit message records a mutation run that reddens it with the gate disabled and leaves the control green. Both rows of the rule are asserted over a synthetic set through the package-private method.

**Verdict:** REFUTED as a hole. What the empty list leaves untested is the composition of a non-empty production allow-list with the loop, which is step 4's own test obligation, and the over-decline it causes in the meantime is BG1.

#### C8 REFUTED — `Set.contains` throws on a null recogniser

**Claim:** `mayFollow.contains(recogniser)` and `POST_LIST_SHAPING_RECOGNISERS.contains(recogniser)` follow the same pattern `postUnionSuffixTranslatable` guards against with an explicit null check ("`Set.of(...).contains(null)` throws").

**Check:** `dispatchAll` resolves the recogniser at line 439 and returns false at 441 when the registry has no entry, so every subsequent `contains` sees a non-null value. The two new call sites sit below that guard. `mayFollowListShaping` itself has no null guard and is package-private, but its only non-test caller is the guarded one.

**Verdict:** REFUTED. No NPE path in production.

#### C9 REFUTED — a union arm's ops reach the parent shaping without arming the latch

**Claim:** `UnionStepRecogniser` pins the arms' agreed shaping through `setResultShaping` rather than through an append, so ops can arrive on the parent context by a path the latch does not observe.

**Check:** the latch does not observe the append, it observes the context. `ctx.setResultShaping(agreedShaping)` at `UnionStepRecogniser` line 124 makes `carriesListShapingOp()` true, and `UnionStepRecogniser` is off the allow-list, so the latch arms and every post-union suffix step declines. That is the fail-closed direction the latch comment claims.

**Verdict:** REFUTED. Note for step 5: the walk can still *end* on the union with ops on the shaping, producing a multi-plan result that folds once over the concatenation — DR-T3's wrong answer. This gate does not close that; only item 4's non-empty-`listShapingOps` child gate does, and today the shape declines by lambda identity rather than by design.

#### C10 REFUTED — the new gate changes behavior on shapes that translate today

**Claim:** a third in-loop gate over every dispatched step can only narrow what translates, so some currently-translating shape declines.

**Check:** `carriesListShapingOp()` is `!shaping.listShapingOps().isEmpty()` on `WalkerContext` and hard false on the adapter. No production recogniser appends an op yet (grep over `appendListShapingOp` in `core/src/main/java` finds the two implementations and no caller), and `setResultShaping`'s callers all rebuild from `ResultShaping.NONE`, so the field is empty on every production walk and the gate never fires. Interface-wise, the non-default `carriesListShapingOp()` has exactly two implementers and no anonymous test implementation, so nothing else needed an override.

**Verdict:** REFUTED. The step is behavior-neutral in production, which is why none of the four findings is a blocker. Reference accuracy: the implementer and caller sets are grep-based (PSI unavailable on this repository), so a caller behind reflection or a generated source would not have surfaced.

#### C11 REFUTED — the terminators' absence from `POST_CARDINALITY_RECOGNISERS` is a defect of this step

**Claim:** the new gate has a twin that also needs updating: `g.V().limit(2).values("name").fold()` will decline at the cardinality gate even though a stage applied after the plan cannot disturb a statement-level clause, so the step left half the rule unwritten.

**Check:** the shape declines, and the decline is safe — `LIMIT` bounds the rows, then the stage runs over the bounded stream, which is Gremlin's order too, so admitting the terminators there would be sound and lose nothing. The omission costs coverage on `skip` / `limit` / `dedup` followed by a terminator.

**Verdict:** REFUTED as a defect in this step; the membership question belongs to the step that lands the recognisers, since a set cannot list a recogniser that does not exist. Recorded here so step 3 or 4 asks it rather than inheriting the decline silently.

#### C12 REFUTED — the new latch introduces shared mutable state

**Claim:** `GremlinStepWalker` is a shared singleton (`PRODUCTION_INSTANCE`), so per-walk latch state on it would be visible across concurrently compiling traversals.

**Check:** `afterListShapingDrain` is a local variable in the static `dispatchAll`, so it lives on the calling thread's stack, one instance per walk and per sub-walk. The walker's only field stays the immutable registry, and the ops it gates on live on the per-walk `WalkerContext`.

**Verdict:** REFUTED. The step adds no shared mutable state, so no concurrency triage gap is flagged from this diff. Had the latch been a field, it would have been both a sequential lifecycle bug and an interleaving one.
