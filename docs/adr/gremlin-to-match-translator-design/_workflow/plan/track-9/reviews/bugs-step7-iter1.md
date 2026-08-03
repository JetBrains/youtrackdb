<!-- MANIFEST
findings: 4   severity: {blocker: 0, should-fix: 3, suggestion: 1}
index:
  - {id: BG1, sev: should-fix, loc: RangeGlobalStepRecogniser.java:128, anchor: "### BG1 ", cert: C1, basis: "the uncovered branch is reachable — RangeGlobalStep's constructor has no low >= 0 check, so union(...).skip(-5) and union(...).range(-5, 10) both reach it; behaviour is safe, the recorded justification is false"}
  - {id: BG2, sev: should-fix, loc: GremlinStepWalker.java:411, anchor: "### BG2 ", cert: C6, basis: "the positional gate keys on RangeGlobalStepRecogniser.INSTANCE rather than on a property, so a later POST_UNION_RECOGNISERS member (Track 11 item 4 proposes tail) gets the membership gate and no positional gate"}
  - {id: BG3, sev: should-fix, loc: RangeGlobalStepRecogniser.java:67, anchor: "### BG3 ", cert: C7, basis: "pre-existing, single-plan side: a slice followed by a hop becomes a statement-level SQL SKIP/LIMIT, so g.V().limit(2).out() slices the hop's output instead of its input"}
  - {id: BG4, sev: suggestion, loc: GremlinStepWalker.java:368, anchor: "### BG4 ", cert: C10, basis: "the mirror's own Javadoc still claims the two halves cannot disagree because they read one field, which the new positional rule makes false"}
evidence_base: {section: "## Evidence base", certs: 10, matches: 4}
cert_index:
  - {id: C1,  verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2,  verdict: REFUTED,   anchor: "#### C2 "}
  - {id: C3,  verdict: REFUTED,   anchor: "#### C3 "}
  - {id: C4,  verdict: REFUTED,   anchor: "#### C4 "}
  - {id: C5,  verdict: REFUTED,   anchor: "#### C5 "}
  - {id: C6,  verdict: CONFIRMED, anchor: "#### C6 "}
  - {id: C7,  verdict: CONFIRMED, anchor: "#### C7 "}
  - {id: C8,  verdict: REFUTED,   anchor: "#### C8 "}
  - {id: C9,  verdict: REFUTED,   anchor: "#### C9 "}
  - {id: C10, verdict: CONFIRMED, anchor: "#### C10 "}
flags: [CONTRACT_OK]
-->

## Findings

### BG1 [should-fix] The uncovered branch is reachable; the constructor does not reject a negative low

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RangeGlobalStepRecogniser.java` (line 128, `normalize` at lines 144-165)

**Issue**: The step records `selectsPositionally`'s `normalized != null` false arm as unreachable from the Gremlin DSL because `RangeGlobalStep` rejects a negative low at construction. It does not. The constructor's only guard is `low != -1 && high != -1 && low > high`; there is no lower bound on `low`. `g.V().union(out(), in()).skip(-5)` and `g.V().union(out(), in()).range(-5, 10)` both build, both survive `applyStrategies()` with the negative-low step sitting immediately after the `UnionStep`, and both drive `postUnionSuffixTranslatable` into `selectsPositionally`, where `normalize` returns `null` on the `low < 0` check at line 152.

The behaviour on that path is correct. `selectsPositionally` answers `false`, the look-ahead applies no positional gate, the fork is paid, and `recognizePostUnion` declines at its own `normalize == null` check. The cost is N discarded sub-walks for a query nobody writes, and the answer stays right. So this is a false coverage justification, not a wrong-results defect.

The claim probably generalised from `limit(-5)`, which does throw: `limit(n)` builds `RangeGlobalStep(0, n)`, and `0 > -5` with both bounds differing from `-1` trips the guard. `skip(n)` builds `RangeGlobalStep(n, -1)`, and the `high != -1` conjunct short-circuits the guard away entirely.

**Evidence** (`#### C1`): measured, not derived. Compiling three probes against the project's own `gremlin-core-3.8.1-67860f6-SNAPSHOT` and the `core` test classpath:

```
skip(-5)       -> BUILT RangeGlobalStep low=-5 high=-1
range(-5, 10)  -> BUILT RangeGlobalStep low=-5 high=10
limit(-5)      -> THREW IllegalArgumentException: Not a legal range: [0, -5]
```

Running the same two shapes behind a `union` through a full `applyStrategies()` on `TinkerGraph`:

```
g.V().union(out(), in()).skip(-5)      -> [TinkerGraphStep, UnionStep([...]), RangeGlobalStep(-5,-1)]
g.V().union(out(), in()).range(-5, 10) -> [TinkerGraphStep, UnionStep([...]), RangeGlobalStep(-5,10)]
```

No strategy in `org.apache.tinkerpop.gremlin.process.traversal.strategy.verification` references `RangeGlobalStep`, so nothing upstream of the translator rewrites or rejects the step.

**Refutation considered**: I checked whether the GValue overload changes the answer, in case the concrete-step route is not the one production takes. It does not — `GValueReductionStrategy` reduces `range(GValue.ofLong("lo", -5L), GValue.ofLong("hi", 10L))` to the same `RangeGlobalStep(-5,10)`. I also checked whether the negative low could produce a wrong answer rather than a decline, and it cannot: both `selectsPositionally` and `recognizePostUnion` route it through the same `normalize`, and the recogniser's `null` branch declines.

**Suggestion**: Two small changes. Correct the recorded justification — the reachable route is `skip(-n)` and `range(-n, m)`, and only `limit(-n)` throws. Then close the coverage gap with the test the claim was standing in for:

```java
/**
 * A negative low reaches selectsPositionally: RangeGlobalStep's constructor rejects only
 * low > high with both bounds set, so skip(-5) builds. The predicate answers false, which
 * lets the shape through the look-ahead to the recogniser that declines it.
 */
@Test
public void selectsPositionally_negativeLow_isFalse() {
  var admin = graph.traversal().V().skip(-5).asAdmin();

  assertThat(RangeGlobalStepRecogniser.selectsPositionally(admin.getSteps().get(1))).isFalse();
}
```

### BG2 [should-fix] The positional gate keys on one recogniser's identity, so the allow-list is no longer fail-closed on that axis

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinStepWalker.java` (lines 411-418); sufficiency half in `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RangeGlobalStepRecogniser.java` (lines 91-98)

**Issue**: `POST_UNION_RECOGNISERS` exists so that a recogniser added later is declined post-union until someone deliberately teaches it to branch on the carrier — the field's Javadoc calls this "true by construction". The positional rule the step adds does not get that property. The walker tests `recogniser == RangeGlobalStepRecogniser.INSTANCE` before applying the gate, and the recogniser's half lives inside `recognizePostUnion`. Both are bound to one identity rather than to "this recogniser selects rows by position".

The consequence is concrete and already scheduled. Track 9's own `## Surprises & Discoveries` records that Track 11 item 4 proposes widening this same allow-list with `tail` and `fold`. `tail(n)` selects by position by definition, and it selects from the *end* of the stream, which is the position the branch-major concatenation and native's per-traverser interleaving disagree about hardest. A `TailGlobalStepRecogniser` added to the set would pass the membership gate at line 409, skip the identity test at line 411, and translate — reinstating the exact defect this step closes, with nothing in the build or the test suite objecting.

**Evidence** (`#### C6`): the gate at lines 411-418 is one `if` keyed on an instance identity, and it is the only positional check in the look-ahead. The in-loop gate at line 342 tests membership alone. Neither the `StepRecogniser` interface nor `POST_UNION_RECOGNISERS`' construction carries any positional obligation, so a new member inherits none. Grep across the repository (PSI unavailable — see the method caveat in `#### C1`) finds exactly two readers of `POST_UNION_RECOGNISERS`, `dispatchAll` at line 342 and `postUnionSuffixTranslatable` at line 409, and neither treats membership as sufficient today. The gap is what a third member would inherit, not what the two readers do now.

**Refutation considered**: I checked whether the Javadoc closes the gap by instruction rather than by construction. The field's new paragraph says membership is "necessary, not sufficient" and explains the range case, which warns a reader who opens the file. It does not stop a widening commit that never opens it, and the field's original claim is that the allow-list holds "by construction" precisely so that no one has to read the prose. I also checked whether `fold` is exempt — it is not positional itself, but `ResultShaping.listShapingOps()` carries `reverse` and `tail` alongside it, and `PostConcatOp`'s own Javadoc groups the four together.

**Suggestion**: Move the knowledge onto the seam the walker already dispatches through. Add a default method to `StepRecogniser`:

```java
/**
 * Whether this recogniser's step would select rows by position out of the post-union
 * concatenation, whose row order is not native's. A recogniser that answers true only
 * translates post-union when a count() follows immediately; see RangeGlobalStepRecogniser.
 */
default boolean selectsPositionally(Step<?, ?> step) {
  return false;
}
```

`RangeGlobalStepRecogniser` overrides it with the body it already has, and the walker replaces the identity test with `recogniser.selectsPositionally(step)`. A future `tail` recogniser then either overrides it or ships a defect its author has to write past rather than omit. If the seam change is too wide for this step, the cheaper stopgap is an assertion at the `POST_UNION_RECOGNISERS` declaration that pins the set's membership, so a widening commit has to touch a line that names the rule.

### BG3 [should-fix] Pre-existing: a single-plan slice followed by a hop slices the hop's output, not its input

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RangeGlobalStepRecogniser.java` (lines 60-73)

**Issue**: This one is outside the step-7 diff and I flag it because it is the same defect class on the other branch of the recogniser the step edits. On the single-plan path the recogniser writes the slice into `ctx.setSkip` / `ctx.setLimit`, which `buildResult` hands to `MatchPlanInputs` as a statement-level `SKIP` / `LIMIT` over the assembled MATCH. Nothing then stops a later step from extending the pattern. `g.V().limit(2).out()` becomes roughly `MATCH {as: v0} -out-> {as: v1} RETURN v1 LIMIT 2` — the first two out-neighbours across every vertex in the graph. Native means the out-neighbours of the first two vertices, which is a different row set and usually a different cardinality.

The guard exists for two neighbours of this shape and not for hops. `RangeGlobalStepRecogniser.ctxHasSkipOrLimit` (line 167) declines a second slice, and `GremlinAggregateAssembler.hasPreAggregateCardinalityClause` (line 42) declines `count` / `sum` / `min` / `max` / `mean` / `group` after a slice. A hop reaches `GremlinPatternAssembler.claimFoldedHop`, which checks the boundary alias and the edge-label arity and nothing else.

**Evidence** (`#### C7`): grep over the whole `translator/strategy` package finds two readers of `ctx.limit()` / `ctx.skip()` — `GremlinAggregateAssembler` at lines 42-43 and `RangeGlobalStepRecogniser` at line 167. No hop, filter, or projection recogniser consults either. `claimFoldedHop` (`GremlinPatternAssembler.java:63-79`) declines on a null boundary alias and an untranslatable edge label, then appends the hop and re-pins the boundary. The gremlin translator test tree contains no traversal that puts a hop after a `limit` / `skip` / `range`, so the shape is uncovered as well as unguarded.

**Refutation considered**: I looked for a gate above the recognisers — a shape check in `GremlinToMatchStrategy`, or a "terminal clause pinned" flag on the context. Neither exists; `GremlinToMatchStrategy` contains no reference to `RangeGlobalStep`. I did **not** execute the shape against a live session, so the trace rests on reading the recognisers and the plan-input assembly rather than on a measured multiset. Confirm by execution before acting on it: drain `g.V().limit(2).out()` with the translator on and off against a fixture whose first two vertices have fewer than two out-edges between them.

**Suggestion**: Verify first, then decline the shape the way the aggregate path already declines it. If the trace holds, `claimFoldedHop` and the other pattern-extending recognisers need the same `hasPreAggregateCardinalityClause` test the aggregates use, under a name that says what it means for a hop ("a slice already fixed the row set, so extending the pattern would re-slice a different one"). This belongs to a follow-up rather than to step 7 — it predates the commit and its blast radius is the single-plan path, which is most of the translator.

### BG4 [suggestion] The mirror's Javadoc still claims the two halves cannot disagree

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinStepWalker.java` (lines 366-370, 411-418)

**Issue**: Line 368 says the look-ahead "Reads the same field the in-loop gate reads, so the two can never disagree about which suffix is translatable." That was true before this step and is not true now. The positional rule is a second rule, derived from neither `POST_UNION_RECOGNISERS` nor anything the in-loop gate reads, and it is spelled differently on each side. `followedByCount` (`RangeGlobalStepRecogniser.java:109-112`) tests `next.getClass() == CountGlobalStep.class`; the look-ahead (line 415) tests `recognisers.get(next.getClass()) != CountGlobalStepRecogniser.INSTANCE`. The paragraph the step adds at lines 390-397 describes the new rule but leaves the older absolute claim standing three lines above it, so the file now asserts and contradicts the same invariant.

The two spellings agree today and both drift directions are fail-safe, so no wrong answer follows from this (`#### C3`, `#### C8`). The cost is to the next person maintaining the mirror, who reads line 368 and concludes there is one rule to keep in step when there are two.

**Evidence** (`#### C10`): the sentence at line 368 quantifies over "which suffix is translatable", which is exactly what the positional gate at lines 411-418 decides, and that gate reads `RangeGlobalStepRecogniser.selectsPositionally` plus a registry lookup rather than the field. `CLAUDE.md` § Comments and Documentation makes keeping a comment in sync with the behaviour it describes a standing requirement.

**Refutation considered**: I checked whether the new paragraph at lines 390-397 supersedes line 368 clearly enough that a reader would not be misled. It does not — it explains why the mirror exists and what it reads, without retracting the "same field" claim, and the two sentences sit in the same Javadoc block.

**Suggestion**: Narrow line 368 to what the field actually guarantees ("Reads the same allow-list the in-loop gate reads, so the two agree on which recognisers may claim a post-union step; the positional rule below is a second gate, mirrored deliberately"). While the file is open, giving the two halves one spelling of "the next step is a count" removes the remaining asymmetry: have the look-ahead call a shared predicate on `RangeGlobalStepRecogniser` beside `selectsPositionally`, the way it already delegates the normalisation. The performance review's `#### C5` reaches the same fix from the wasted-fork angle; one change closes both.

## Evidence base

#### C1 The `normalized != null` false arm is reachable from the Gremlin DSL — CONFIRMED

`RangeGlobalStep`'s constructor guards only `low != -1 && high != -1 && low > high`, so `skip(-5)` (which builds `RangeGlobalStep(-5, -1)` and short-circuits on the `high != -1` conjunct) and `range(-5, 10)` both construct. Both survive full `applyStrategies()` sitting immediately after the `UnionStep`, and `normalize`'s `low < 0` check at line 152 then returns `null`. Measured against the project's own gremlin-core snapshot; the probe output is in BG1. Raised as BG1.

METHOD CAVEAT: reference questions in this review were answered by grep and by decompiling the pinned `gremlin-core-3.8.1-67860f6-SNAPSHOT` jar, not by PSI — `steroid_execute_code` times out on this repository. The reachability claim rests on executed probes rather than on a symbol search, so a missed reference would not flip it. The `POST_UNION_RECOGNISERS` reader count in C6 is the one grep-based claim in this file whose accuracy depends on the search being complete.

#### C2 `normalize`'s `high < low` branch is reachable — REFUTED

CLAIM: line 160's `if (high < low) { high = low; }` is a live branch, so the same coverage question BG1 answers applies to it too.

REFUTATION: it is dead on both construction routes. `range(3, 1)` throws `IllegalArgumentException: Not a legal range: [3, 1]` from `RangeGlobalStep`'s constructor, since both bounds differ from `-1` and `low > high`. The GValue overload builds a `RangeGlobalStepPlaceholder`, whose own constructor checks only that neither `GValue` is null — but `GValueReductionStrategy` reduces the placeholder through `asConcreteStep()` during `applyStrategies()`, which calls the same `RangeGlobalStep` constructor and throws there. Measured: `g.V().union(out(), in()).range(GValue.ofLong("lo", 3L), GValue.ofLong("hi", 1L))` throws at strategy time.

Any surviving `high < low` needs `high == -1` or `low == -1`, and both route elsewhere: `high < 0` takes the `unboundedHigh` branch at line 155, and `low < 0` returns `null` at line 152. So the branch cannot be reached.

RESIDUE: the performance review's `#### C4` lists `range(hi, lo)` with `hi < lo` among the shapes that "normalize maps to limit 0". That shape never reaches `normalize`. The correction does not change that finding's disposition, which rests on `limit(0)` and `range(k, k)`; those two are real (`#### C5`).

#### C3 The look-ahead can decline a shape the recogniser would accept — REFUTED

CLAIM: the mirror is stricter than the rule it mirrors somewhere, so a shape that translated before the step now declines before the fork and stops translating.

REFUTATION: I enumerated the accept condition on both sides and walked every shape that reaches it.

The recogniser accepts a post-union slice when `normalize` returns non-null and non-noop and `cursor.peek().getClass() == CountGlobalStep.class`. The look-ahead declines when `selectsPositionally(step)` is true and the next significant step does not map to `CountGlobalStepRecogniser.INSTANCE`. `selectsPositionally` is `normalize(range) != null && !noop()`, the same two tests, on the same step object. So the two differ only in how they identify the count, and `CountGlobalStep.class` is the sole `PRODUCTION_RECOGNISERS` key mapping to `CountGlobalStepRecogniser` (`GremlinStepWalker.java:179`). Under the production registry the two conditions are equivalent.

Shapes walked, look-ahead verdict against recogniser verdict:

- `union(...).limit(3).count()` — both accept.
- `union(...).skip(3).count()`, `union(...).range(1,3).count()` — both accept.
- `union(...).skip(0).limit(3).count()` — both accept; the no-op appends nothing, so the later slice sees empty `postConcatOps`.
- `union(...).limit(3)`, `.skip(3)`, `.range(2,5)` — both decline; this is the defect the step closes.
- `union(...).skip(0)`, `union(...).range(0,-1)`, `union(...).limit(Long.MAX_VALUE)` — both accept; `normalize` calls all three `noop`, so `selectsPositionally` answers false and no gate applies.
- `union(...).limit(3).dedup().count()` — both decline.
- `union(...).dedup().limit(3).count()`, `union(...).dedup().range(1,3).count()` — both accept.
- `union(...).limit(3).limit(2).count()` — both decline.
- `union(...).count().limit(3)` — both decline; the look-ahead now declines it before the fork rather than after, which is the intended direction.
- `union(...).limit(3).count().count()` — look-ahead accepts, recogniser declines on the second count. Wasted fork, correct answer; this is the documented "necessary condition, not a simulation" direction.
- `union(...).skip(-5)`, `union(...).range(-5,10)` — look-ahead accepts (C1), recogniser declines. Same safe direction.

No shape lands on look-ahead-declines-recogniser-accepts. The cursor positions match as well (`#### C9`).

#### C4 A `PostConcatOp.Range` can reach the executor without a following `Count` — REFUTED

CLAIM: the gate protects the recogniser but some other path still builds a `Range` op, so `MultiPlanMatchStep` can still apply a positional slice whose rows reach the caller.

REFUTATION: `new PostConcatOp.Range(...)` has exactly one production construction site, `RangeGlobalStepRecogniser.java:99`, and it sits after the `followedByCount` check at line 96. The step the check saw is then the next one dispatch hands out, and it is a `CountGlobalStep`, so `CountGlobalStepRecogniser` runs next. `configurePostUnionCount` (`GremlinAggregateAssembler.java:91-115`) declines only on a null boundary alias or a `Count` already in the ops list, and neither holds — the boundary was pinned by `UnionStepRecogniser`, and a `Count` already present would have made `recognizePostUnion` decline at its own first loop. So it appends the `Count`. Every successful walk that carries a `Range` carries a `Count` immediately after it.

RESIDUE: the lone-`Range` executor path is now dead in production. `MultiPlanMatchStep` and `PostConcatStreams.skip` still handle a `Range` that is the last op, and only their unit tests reach it. That is a coverage-attribution point for the test reviewers, not a defect.

#### C5 The zero-width slice decline is a wrong-results defect — REFUTED

CLAIM: `selectsPositionally` answers true for `limit(0)` and `range(k, k)`, so the gate calls a slice positional when it selects nothing.

REFUTATION: the predicate is over-inclusive and the behaviour is still right. `normalize` maps `limit(0)` to `NormalizedRange(0, 0, noop=false)` — measured: `g.V().union(out(), in()).limit(0)` survives strategies as `RangeGlobalStep(0,0)`. An empty result is the same in any arrival order, so these shapes were safe to translate and now decline to native, which also returns empty. The row set the caller sees is unchanged either way; only the plan differs.

Both mirror halves agree on the decline, so this is not a mirror disagreement. The performance review reports the same over-width as `#### C4` and recommends leaving it, on the grounds that a carve-out widens the accept surface on the predicate that just shipped a wrong answer. I reach the same disposition from the correctness side: there is nothing to fix, and the only residue is that `selectsPositionally`'s Javadoc phrase "a slice that selects by position" over-describes what the method returns. Not reported.

#### C6 The positional gate is bound to an identity rather than to a property — CONFIRMED

The walker's gate at `GremlinStepWalker.java:411` tests `recogniser == RangeGlobalStepRecogniser.INSTANCE`, and it is the only positional check in the look-ahead; the in-loop gate at line 342 tests set membership alone. Neither `StepRecogniser` nor the `POST_UNION_RECOGNISERS` declaration carries a positional obligation a new member would inherit. Track 9's `## Surprises & Discoveries` records that Track 11 item 4 proposes adding `tail` and `fold` to this set, and `tail(n)` is positional. Raised as BG2.

#### C7 A single-plan slice followed by a hop mistranslates — CONFIRMED

Grep over `translator/strategy` finds `ctx.limit()` / `ctx.skip()` read in two places only: `GremlinAggregateAssembler.hasPreAggregateCardinalityClause` (lines 42-43) and `RangeGlobalStepRecogniser.ctxHasSkipOrLimit` (line 167). `GremlinPatternAssembler.claimFoldedHop` (lines 63-79) is the shared entry for `VertexHopRecogniser` and `CombinatorFoldedHopRecogniser`, and it declines only on a null boundary alias, an untranslatable edge label, and a failed label bind. `GremlinToMatchStrategy` contains no reference to `RangeGlobalStep`, so no shape gate sits above the recognisers either. Raised as BG3, with the execution caveat stated in that finding: the trace is static, and the shape has no test in the gremlin translator tree to contradict or confirm it.

#### C8 `followedByCount` can be fooled by a `CountGlobalStep` subclass — REFUTED

CLAIM: `followedByCount` matches the exact class, and its Javadoc reasons about "a `CountGlobalStep` subclass" — so the direction the Javadoc calls unsafe is a live case.

REFUTATION: `CountGlobalStep` is `final` in the pinned fork (`public final class org.apache.tinkerpop.gremlin.process.traversal.step.map.CountGlobalStep<S> extends ReducingBarrierStep<S, Long>`), so no subclass exists to match or mis-match. The exact-class test is correct and the Javadoc's reasoning is defensive rather than live. Nothing to report.

RESIDUE: the fork ships no count placeholder either. Its twenty `*Placeholder` classes include `RangeGlobalStepPlaceholder` and `TailGlobalStepPlaceholder` but nothing for `CountGlobalStep`, which is why the two spellings in `#### C3` coincide today.

#### C9 The two halves read different cursor positions — REFUTED

CLAIM: the look-ahead peeks from before the slice while the recogniser peeks from after it, so the two are looking at different steps and the mirror compares nothing.

REFUTATION: they land on the same step. `UnionStepRecogniser.recognize` calls `cursor.take()` on the union first, so `postUnionSuffixTranslatable` starts its scan at `peek(0)` = the first significant step after the union; at `ahead = k` the slice, `peek(ahead + 1)` is the next significant step after it. `RangeGlobalStepRecogniser.recognize` calls `cursor.take()` on the slice, so `followedByCount`'s `cursor.peek()` is that same next significant step. Both routes skip transparent steps: `StepStreamCursor.peek(int)` skips them in its probe loop (lines 64-74) and `peek()` skips them through `skipTransparent()` (line 51). A `NoOpBarrierStep` wedged between the slice and the count is invisible to both.

The one behavioural difference is a side effect, not a reading difference: `peek()` advances the position past leading transparent steps while `peek(int)` does not. On the decline path the whole walk is discarded, so the advance is unobservable.

#### C10 The Javadoc's "cannot disagree" claim is now false — CONFIRMED

`GremlinStepWalker.java:368` states that the look-ahead reads the same field the in-loop gate reads "so the two can never disagree about which suffix is translatable". The positional gate at lines 411-418 decides exactly that question and reads `RangeGlobalStepRecogniser.selectsPositionally` plus a registry lookup instead. The paragraph added at lines 390-397 documents the new rule without retracting the old claim, leaving both in one Javadoc block. Raised as BG4.
