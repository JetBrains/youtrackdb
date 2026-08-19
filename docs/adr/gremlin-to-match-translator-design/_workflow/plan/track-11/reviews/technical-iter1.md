<!-- MANIFEST
findings: 8   severity: {blocker: 1, should-fix: 4, suggestion: 3}
index:
  - {id: T1, sev: blocker,    loc: "plan/track-11.md:33,64,66,80; GremlinAggregateAssembler.java:79", anchor: "### T1 ", cert: "E1/E2/C21", basis: "the tail rule is the sole guard against eleven ResultShaping.NONE clobber sites and against SQL-level order/limit/dedup running before list shaping; stated as 'last step', which the accepted reverse().unfold() composition contradicts"}
  - {id: T2, sev: should-fix, loc: "plan/track-11.md:64-66; SubTraversalPredicateAdapter.java:397", anchor: "### T2 ", cert: "C3/I3", basis: "only FoldStep is given a supportsListShaping() decline; the adapter's mandatory appendListShapingOp body is left unspecified and DR-T2 forbids both candidate bodies"}
  - {id: T3, sev: should-fix, loc: "plan/track-11.md:67,80,84; MultiPlanMatchStep.java:308-338", anchor: "### T3 ", cert: "C11/I2", basis: "post-concat ops always run before list-shaping ops regardless of declared order; item 4 relaxes the allow-list without stating this or testing a mixed suffix"}
  - {id: T4, sev: should-fix, loc: "plan/track-11.md:64,68; SubTraversalPredicateAdapterTest.java:60", anchor: "### T4 ", cert: "C3/I3", basis: "supportsListShaping() default true inverts to false under a Mockito mock, so every combinator-child decline assertion can pass vacuously"}
  - {id: T5, sev: should-fix, loc: "plan/track-11.md:70,88; LdbcBenchmarkState.java:64,243", anchor: "### T5 ", cert: "C17", basis: "curatedParams is private with no setter and every parameter accessor dereferences it, so a by-id benchmark method cannot be driven from the in-memory fixture item 7 names"}
  - {id: T6, sev: suggestion, loc: "PostConcatOp.java:8; GremlinStepWalker.java:188-191", anchor: "### T6 ", cert: "C6/C7", basis: "two stale javadoc sites in files item 4 already opens are missing from the track's four-site list"}
  - {id: T7, sev: suggestion, loc: "plan/track-11.md:33,66; TailGlobalStepContract getLimit", anchor: "### T7 ", cert: "E3/C13", basis: "tail(n) boundary set covers n=0 and n<0 but not n > Integer.MAX_VALUE, which an ArrayDeque ring buffer cannot size"}
  - {id: T8, sev: suggestion, loc: "plan/track-11.md:70; GremlinToMatchStrategy.java:351", anchor: "### T8 ", cert: "C15", basis: "the cited GremlinToMatchStrategy:338 kill-switch read drifted to :351 when Track 9 step 1 landed"}
evidence_base: {section: "## Evidence base", certs: 27, matches: 16}
cert_index:
  - {id: C1,  verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2,  verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3,  verdict: PARTIAL,   anchor: "#### C3 "}
  - {id: C4,  verdict: CONFIRMED, anchor: "#### C4 "}
  - {id: C5,  verdict: CONFIRMED, anchor: "#### C5 "}
  - {id: C6,  verdict: PARTIAL,   anchor: "#### C6 "}
  - {id: C7,  verdict: PARTIAL,   anchor: "#### C7 "}
  - {id: C8,  verdict: CONFIRMED, anchor: "#### C8 "}
  - {id: C9,  verdict: CONFIRMED, anchor: "#### C9 "}
  - {id: C10, verdict: CONFIRMED, anchor: "#### C10 "}
  - {id: C11, verdict: CONFIRMED, anchor: "#### C11 "}
  - {id: C12, verdict: CONFIRMED, anchor: "#### C12 "}
  - {id: C13, verdict: CONFIRMED, anchor: "#### C13 "}
  - {id: C14, verdict: CONFIRMED, anchor: "#### C14 "}
  - {id: C15, verdict: PARTIAL,   anchor: "#### C15 "}
  - {id: C16, verdict: CONFIRMED, anchor: "#### C16 "}
  - {id: C17, verdict: PARTIAL,   anchor: "#### C17 "}
  - {id: C18, verdict: CONFIRMED, anchor: "#### C18 "}
  - {id: C19, verdict: CONFIRMED, anchor: "#### C19 "}
  - {id: C20, verdict: PARTIAL,   anchor: "#### C20 "}
  - {id: C21, verdict: WRONG,     anchor: "#### C21 "}
  - {id: E1,  verdict: WRONG,     anchor: "#### E1 "}
  - {id: E2,  verdict: WRONG,     anchor: "#### E2 "}
  - {id: E3,  verdict: PARTIAL,   anchor: "#### E3 "}
  - {id: I1,  verdict: MATCHES,   anchor: "#### I1 "}
  - {id: I2,  verdict: MATCHES,   anchor: "#### I2 "}
  - {id: I3,  verdict: "CALLERS AT RISK", anchor: "#### I3 "}
flags: [CONTRACT_OK]
-->

# Track 11 — technical review, iteration 1

**Reference-accuracy caveat, applies to every finding below.** mcp-steroid is
reachable and `steroid_list_projects` reports the IDE open on this working tree,
but `steroid_execute_code` timed out on the first PSI call (cold kotlinc exceeds
the MCP limit), which reproduces the caveat Track 9's three Phase A iterations
carried. Every symbol result here is grep plus an end-to-end read of each
returned site, and `javap` against the resolved fork jar
(`io.youtrackdb:gremlin-core:3.8.1-67860f6-SNAPSHOT`, the version
`pom.xml:114` pins). Declaration-level reads and bytecode reads are reliable;
"no other caller" negatives are bounded, not established.

Reviewed at `54cc0a708f`, which already carries Track 9 step 1
(`b35ac67d2f`, `RepeatDeclineStrategy`). Track 9 steps 2–6 have not landed, so
no finding below rests on the absence of Track 9's baseline numbers.

## Findings

### T1 [blocker]
**Certificate**: C21 (eleven `ResultShaping.NONE` clobber sites), E1
(`g.V().fold().count()` on the single-plan path), E2
(`union(a,b).fold().count()` on the count push-down path), C20 (D3 is
all-or-nothing decline, not a last-step rule), C5, C9, C11

**Location**: `plan/track-11.md` `## Context and Orientation` (line 33),
`## Plan of Work` items 1, 3 and 4 (lines 64, 66, 67), acceptance lines 80 and
84; `GremlinAggregateAssembler.java:79,112,136,172,200`,
`GremlinProjectionAssembler.java:57,83,134`, `SelectStepRecogniser.java:63`,
`SelectOneStepRecogniser.java:60`, `ProjectStepRecogniser.java:47`

**Issue**: The track's whole no-clobber story rests on one rule, and the rule is
stated in a form its own accept set contradicts.

Item 1 is explicit about the dependency: `setResultShaping` "remains a full
replace of the whole record including `listShapingOps`, so the append's
no-clobber guarantee covers only recognisers that use the new method, and what
keeps the two from colliding is **D3's last-step rule** plus
`UnionStepRecogniser` calling `setResultShaping(agreedShaping)` before any suffix
op appends."

The clobber surface is larger than the track implies. Every one of the eleven
non-union production `setResultShaping` call sites builds from
`ResultShaping.NONE`, so each silently discards every appended op: `count`
(`GremlinAggregateAssembler:79`), post-union `count` (`:112`),
`sum`/`min`/`max`/`mean` (`:136`), `group` (`:172`), `groupCount` (`:200`),
`values`/`valueMap`/`elementMap`/`properties` (`GremlinProjectionAssembler:57`,
`:83`, `:134`), `select` (`SelectStepRecogniser:63`,
`SelectOneStepRecogniser:60`), and `project` (`ProjectStepRecogniser:47`). None
of the eleven reads `listShapingOps()`, and item 1 deliberately does not make
`setResultShaping` non-clobbering, so none of them can be the guard.

Alongside the clobber there is a second, disjoint hazard on the same rule.
`order` / `limit` / `range` / `skip` / `dedup` do not call `setResultShaping` at
all — they write `ORDER BY` / `LIMIT` / `SKIP` / `RETURN DISTINCT`, which the SQL
engine applies to rows *before* the boundary projects them and therefore before
`applyListShaping` runs (C10). An op appended by an earlier terminator survives
but executes on the wrong side of the clause.

So the rule is load-bearing twice over, and as written it is not a rule an
implementer can apply. Line 33 and item 3 say the terminators are accepted "only
as the **last** step (D3)", yet line 33 and acceptance line 80 also require
`reverse().unfold()` and `unfold().reverse()` to translate with declared order
preserved — in which `reverse` is not the last step. The operative rule can only
be "the traversal's tail is a run of recognised list-shaping terminators with
nothing after it", and the track never writes that sentence. The decline set it
does enumerate (`fold().unfold()`, `fold().tail(3)`, "any mid-traversal
list-shaper") names only shapes where the following step is *itself* a
list-shaper; a following step that is not a list-shaper appears nowhere in either
the accept set or the decline set.

Two consequences an implementer would not derive from the text:

- `g.V().values("age").fold().count()` — the `fold` op is wiped at
  `GremlinAggregateAssembler:79`, the plan emits `RETURN count(*)`, and the
  traversal returns the row count where native returns `1` (E1).
- `union(a,b).fold().count()` is worse, structurally. Both recognisers are
  allow-listed after item 4, so `postUnionSuffixTranslatable` passes;
  `postConcatOps` is a lone `Count`, so `isPushDownCountOnly`
  (`MultiPlanMatchStep:311`) rewrites every child to `RETURN count(*)` at build
  time and sums the scalars, and the surviving `fold` then wraps that one scalar
  (E2). Nothing in the track's union analysis covers a list-shaper *before* a
  post-concat op — DR-T3 and item 4 both reason only about children and about
  suffix ops appending onto `agreedShaping`.

Also note that D3 is not the rule being cited. `implementation-plan.md:168-180`
defines D3 as all-or-nothing decline, and `:740` says so outright: "D3 is
*all-or-nothing decline*, not the terminators." Citing `(D3)` for the last-step
rule points an implementer at a Decision Record that does not contain it (C20).

The resolved fork's feature suite contains the shapes. Grepping the 160
upstream `.feature` files for a terminator followed by another step returns,
among others, `.unfold().values(` ×10, `.unfold().order(` ×11, `.fold().order(`
×10, `.unfold().dedup(` ×4, `.fold().count(` ×3, `.fold().dedup(` ×3,
`.unfold().limit(` ×2, `.unfold().groupCount(` ×2, `.unfold().range(` ×1,
`.unfold().out(` ×1. Most are protected today by an unregistered step elsewhere
in the chain (`Scope.local` variants map to `TailLocalStep` / `OrderLocalStep` /
`DedupLocalStep` / `CountLocalStep`, none registered; `is()`, `aggregate()`,
`cap()`, `index()` likewise), and that protection is incidental rather than
designed. The cleanest witness whose every top-level step class is or becomes
registered is
`g.V().hasLabel("person").group().by("name").by(__.outE().values("weight").sum()).unfold().order().by(Column.values, Order.desc)`
— `GraphStep`, `HasStep`, `GroupStep`, `UnfoldStep`, `OrderGlobalStep`.

Because these shapes decline today (`FoldStep`, `UnfoldStep`, `ReverseStep`,
`TailGlobalStep` are all unregistered — DR-T2 makes the same point), they pass
natively and count as passes in Track 9's baseline. Getting the tail rule wrong
therefore fails item 6's headline gate directly: new Cucumber failures against
the baseline the track measures itself against.

**Proposed fix**:
1. Replace "accepted only as the **last** step (D3)" everywhere it appears
   (`## Context and Orientation` line 33; item 3's "Mid-traversal use declines
   (D3)") with the operative rule: *a list-shaping terminator declines unless
   every step remaining after it is also a recognised list-shaping terminator.*
   Drop the `(D3)` citation or restate it as "under D3's all-or-nothing decline",
   matching `implementation-plan.md:740`.
2. Implement it once, in a shared guard the four recognisers call after `take()`,
   rather than four copies of a `cursor.peek()` test.
   `CombinatorFoldedHopRecogniser:43` is the in-repo precedent for the
   `cursor.peek() != null` idiom (C19); the guard here needs the class-membership
   variant.
3. Add to item 5's decline set and to `## Validation and Acceptance`:
   `g.V().values("age").fold().count()`, `g.V().fold().dedup()`,
   `g.V().valueMap().unfold().select(...)` (or another projection shape), and
   `union(__.out(), __.in()).fold().count()`. Each returns native's answer, and
   each fails before the guard lands.
4. Record in `## Decision Log` that the eleven `ResultShaping.NONE` call sites
   are the clobber surface the guard protects, so a future track adding a twelfth
   knows what the guard is for.

### T2 [should-fix]
**Certificate**: C3 (`SubTraversalPredicateAdapter` is one of exactly two
production `RecognitionContext` implementors; `setResultShaping` swallow at
`:397`), I3 (adding a non-`default` interface method)

**Location**: `plan/track-11.md` items 1, 2 and 3 (lines 64–66), acceptance line
83; `RecognitionContext.java:270,286-288`,
`SubTraversalPredicateAdapter.java:397`

**Issue**: The decline channel is specified for one recogniser out of four, and
the adapter side of it has no legal implementation.

Item 2 gives `FoldStep` the clause — "declines when `supportsListShaping()` is
false". Item 3 gives `UnfoldStep` / `ReverseStep` / `TailGlobalStep` no such
clause; it mentions only mid-traversal decline. Item 4 says combinator children
"decline through the item-1 seam" without saying which side performs the check.
Acceptance line 83 nonetheless requires `g.V().where(__.out().tail(1))` to
decline, and `tail` is one of the three item 3 leaves unspecified.

The adapter side is a harder gap. Item 1 declares
`void appendListShapingOp(@Nonnull ListShapingOp op)` on `RecognitionContext`
with no `default`, so both implementors must supply a body.
`SubTraversalPredicateAdapter` therefore needs one, and DR-T2 rules out both
candidates by name: copying the `setResultShaping` swallow (`:397`) turns
`g.V().and(__.out().fold())` into an existence filter, and copying
`appendPostConcatOp`'s throw breaks the all-or-nothing contract. "Its answer is
**decline**, specifically neither swallow nor throw" describes what
`supportsListShaping()` returns, not what the mutator body contains, and leaves
the implementer with no instruction.

The resolution is available in the repo. `appendPostConcatOp` is a `default` that
throws `UnsupportedOperationException` (`RecognitionContext:286-288`) and is
never reached because every post-concat recogniser gates on `hasUnionCarrier()`
first. That is a defensive unreachable-guard, not a decline mechanism, and it is
the right shape here for the same reason — provided every one of the four
recognisers gates on `supportsListShaping()` before appending.

**Proposed fix**: extend item 3's text so all three of `unfold` / `reverse` /
`tail` carry the same "declines when `supportsListShaping()` is false" clause as
item 2, or state the check once as a shared precondition all four share. In item
1, specify the adapter's `appendListShapingOp` body as a defensive throw reached
only on a recogniser bug, and add one sentence to DR-T2 separating "the throw is
wrong as the decline mechanism" from "the throw is right as the post-check
guard", so the two readings cannot be conflated.

### T3 [should-fix]
**Certificate**: C11 (`MultiPlanMatchStep.startPlanStream` wraps the
concatenator with `PostConcatOp` decorators before the base projects rows), I2
(the multi-plan `buildResult` branch does carry `ctx.shaping()`), C10

**Location**: `plan/track-11.md` item 4 (line 67), acceptance lines 80 and 84;
`MultiPlanMatchStep.java:308-338`, `AbstractMatchPlanStep.java:372-396`

**Issue**: `postConcatOps` and `listShapingOps` are two carriers with a fixed
relative order, and the track never states it or tests across it.

`MultiPlanMatchStep.startPlanStream()` builds the `MultipleExecutionStream` and
then wraps it with one `PostConcatStreams` decorator per `PostConcatOp` in
recognised order (`:337-338`). The base's `openShapedPayloads()` runs afterward
over that already-decorated stream, projecting rows and then threading the
list-shaping ops (`AbstractMatchPlanStep:372-396`). So every post-concat op runs
before every list-shaping op, whatever order the recognisers claimed them in.

Item 4's relaxation of `POST_UNION_RECOGNISERS` from three entries to seven is
what makes mixed suffixes reachable, and the track's order language does not
reach that far. DR-T1 and item 3 promise "declared order preserved", which holds
*within* the `listShapingOps` list; acceptance line 80 tests exactly that pair
(`reverse().unfold()` versus `unfold().reverse()`). Acceptance line 84 covers the
union suffix but only for a suffix made of one list-shaper. No line covers a
suffix mixing the two carriers.

Under T1's tail rule the ordering happens to come out right for every
still-accepted mixed shape — `union(...).dedup().fold()` and
`union(...).range(0,2).fold()` both want post-concat first — and wrong only for
the shapes T1's guard declines. That makes this finding's severity contingent on
T1 rather than independent, which is precisely why it is worth writing down: the
correctness of item 4 as accepted currently depends on an unstated coincidence
between two carriers' fixed order and one guard's decline set.

**Proposed fix**: add one paragraph to item 4 recording that post-concat ops
always precede list-shaping ops on the multi-plan path, with the two file
references. Add one acceptance line for a mixed suffix that stays accepted —
`union(__.out(), __.in()).dedup().fold()` yields one list over the deduped
concatenation — and one for a mixed suffix that must decline, which is the union
half of T1's proposed decline set.

### T4 [should-fix]
**Certificate**: C3, I3 — `SubTraversalPredicateAdapterTest` drives four tests
through `mock(RecognitionContext.class)` (`:60`, `:86`, `:104`, `:127`)

**Location**: `plan/track-11.md` item 1 (line 64) and item 5 (line 68),
acceptance line 83; `SubTraversalPredicateAdapterTest.java:60,86,104,127`

**Issue**: `supportsListShaping()` as a `default` returning `true` is the first
default on `RecognitionContext` whose mocked value inverts its production value,
and the inversion makes a decline assertion pass for the wrong reason.

Mockito stubs interface `default` methods rather than calling them, and its
default answer for a `boolean` is `false`. Every existing default on this
interface survives that: `hasUnionCarrier()` returns `false` in production and
`false` when mocked, `postConcatOps()` and `anyUnionChildHasCardinalityClause()`
likewise, and `appendPostConcatOp()` throws in production while a mock silently
does nothing (a hazard, but a pre-existing one this track does not widen).
`supportsListShaping()` returning `true` in production and `false` when mocked is
new.

The direction of the failure is the bad one. A unit test asserting that a
terminator *accepts* against a mocked context fails loudly and gets fixed. A
test asserting that a combinator child *declines* — item 5's
`g.V().and(__.out().fold())` and `g.V().where(__.out().tail(1))`, both named in
acceptance line 83 as "each a silent wrong answer if missed" — passes whether or
not the recogniser ever consults the seam. This is the same vacuous-pass shape
Track 9's Phase A hit twice (A6, then A7) and caught only by measuring rather
than deriving.

**Proposed fix**: state in item 5 that the two combinator-child decline tests
run end-to-end through a real `WalkerContext`-rooted walk, not against a mocked
`RecognitionContext`; and that any recogniser-level unit test using a mock stubs
`supportsListShaping()` explicitly. Add one line to item 1 recording the
mock-inversion beside the default's declaration, so a later reader adding a
fifth terminator does not rediscover it.

### T5 [should-fix]
**Certificate**: C17 (`LdbcBenchmarkState`: `@Setup(Level.Trial)` is
dataset-bound; `db` / `traversal` package-private; `curatedParams` private with
no setter), C16 (`LdbcQueryCorrectnessTest` builds an in-memory fixture and does
run in an ordinary build)

**Location**: `plan/track-11.md` item 7 (line 70), acceptance line 88;
`LdbcBenchmarkState.java:60-64,243-273`

**Issue**: Item 7's premise about `jmh-ldbc/src/test` checks out; its route from
that fixture to the harness entry point does not, for the one shape item 7
singles out.

The premise is right. `jmh-ldbc` is a reactor module (`pom.xml:54`),
`LdbcQueryCorrectnessTest` creates a `DatabaseType.MEMORY` database in a temp
directory and drives it through a `YTDBGraphTraversalSource` (`:128-137`), and
`jmh-ldbc/target/surefire-reports/` holds results for both of the module's test
classes, so those tests do run in an ordinary build. The gremlin surface is
reachable from `jmh-ldbc` through its single `youtrackdb-core` dependency.

The route is where it breaks. Existing benchmark methods take
`LdbcBenchmarkState` as a parameter and read `state.traversal` plus a curated
per-query parameter (`LdbcISBenchmarkBase:53-80`). `db` and `traversal` are
package-private (`:60-61`), so a same-package test can install the in-memory
fixture without running `setup()` — that part works. But `curatedParams` is
`private` with no setter (`:64`), it is populated only by `ParameterCurator`
after the dataset load, and every parameter accessor dereferences it
(`isPersonId`, `isMessageId`, `ic1PersonId`, …). Any mirrored Gremlin
`@Benchmark` method that reads a parameter therefore NPEs against the in-memory
fixture — and item 7's own headline shape, "a `g.V(rid)` by-id shape", is
parameter-bearing by construction.

Nothing else about item 7 is at risk: the "installation check throws, not a Java
`assert`" clause is right (JMH forks run `-da`), and the on/off flip works
because the flag resolves per traversal through `ContextConfiguration.getValue`,
which falls back to the live `GlobalConfiguration` value when the session carries
no local override (C15). The boundary-step presence assertion item 7 already
specifies is what catches a flip that did not take, so that half is
self-witnessing.

**Proposed fix**: item 7 names the seam. Either add a package-private
`curatedParams` setter (or a `CuratedParams` factory over the in-memory fixture)
and say so, or pin the in-track execution to a parameter-free entry point and
state explicitly that the by-id shape's in-track drive is out of scope while its
Hetzner measurement is not. Acceptance line 88 currently says "one recognised
shape" without naming it, which lets the gap survive decomposition; naming the
shape closes it.

### T6 [suggestion]
**Certificate**: C6 (`PostConcatOp` class javadoc), C7
(`POST_UNION_RECOGNISERS` javadoc)

**Location**: `plan/track-11.md` `## Interfaces and Dependencies` (line 102, the
four-site stale-javadoc list); `PostConcatOp.java:8`,
`GremlinStepWalker.java:188-191`

**Issue**: Two stale javadoc sites sit in files item 4 already opens, and
neither is on the track's list.

`PostConcatOp`'s class javadoc says these are the barriers that must see the
concatenated multiset "not the **Track 9** list-shaping terminators
(`fold`/`unfold`/`reverse`/`tail`), which ride `ResultShaping#listShapingOps()`"
(`:8`). The 2026-08-03 split moved the terminators to Track 11. DR-T3 already
cites `PostConcatOp.Count.INSTANCE` as this codebase's singleton house style, so
the file is open.

`POST_UNION_RECOGNISERS`' javadoc opens "the only recognisers allowed to claim a
step *after* `UnionStepRecogniser` has stashed a multi-plan carrier — **the three
whose step maps to a `PostConcatOp`** the concatenation can absorb" (`:188-191`).
Item 4 adds four recognisers whose steps map to a `ListShapingOp` instead, which
makes that sentence wrong about both the count and the criterion. The rest of the
same javadoc stays correct and is worth leaving alone: it lists "the shaping"
among what `buildResult`'s multi-plan branch reads (`:194-197`), which is exactly
the fact that makes item 4's relaxation legitimate rather than a silent-discard
hazard.

**Proposed fix**: add both to item 4's javadoc-correction list, beside the
`ListShapingOp` once-per-child clause it already names.

### T7 [suggestion]
**Certificate**: E3 (`tail(n)` with `n` above `Integer.MAX_VALUE`), C13
(`TailGlobalStepContract.getLimit()` returns `Long`)

**Location**: `plan/track-11.md` `## Context and Orientation` (line 33), item 3
(line 66), acceptance line 79

**Issue**: The `tail(n)` boundary set stops one case short.

`TailGlobalStepContract.getLimit()` returns a `Long` (byte-confirmed on both
concrete forms), so `n` ranges over the whole `long` domain. The track names
`n=0` (emit nothing) and `n<0` (decline) and prescribes "a bounded `ArrayDeque`
ring buffer" for the rest. `new ArrayDeque<>(int)` cannot express a capacity
above `Integer.MAX_VALUE`, and a narrowing cast of, say,
`tail(Long.MAX_VALUE)` yields `-1`, which `ArrayDeque` rejects with
`IllegalArgumentException` — thrown from inside `TraversalStrategy.apply()` if
the buffer is sized at recognition time, or from the first pull if sized inside
the returned iterator. Native `TailGlobalStep` handles the shape fine, so this
would be a translated-path-only failure on a legal traversal. No upstream feature
scenario uses it (the suite's global forms are `tail(10)` and
`tail(Scope.local, …)`), which is why it is a suggestion rather than a
should-fix.

**Proposed fix**: extend item 3 and acceptance line 79 with the third boundary:
clamp the ring-buffer capacity at recognition time (`(int) Math.min(n,
SOME_CAP)`) and treat `n` at or above the cap as "keep everything", or decline
above `Integer.MAX_VALUE`. Either is one line; the point is that the decision is
recorded rather than discovered by an `IllegalArgumentException`.

### T8 [suggestion]
**Certificate**: C15 — the kill-switch read is at
`GremlinToMatchStrategy.java:351`, not `:338`

**Location**: `plan/track-11.md` item 7 (line 70); also `plan/track-9.md`
`## Interfaces and Dependencies` (line 189)

**Issue**: Item 7 says
`GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED` "is read by
`GremlinToMatchStrategy:338`". At `54cc0a708f`, `:338` is inside
`resolveSessionIfEnabled`'s body before the configuration is fetched; the read is
`configuration.getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED)`
at `:350-351`. Track 9 step 1 (`b35ac67d2f`) added a javadoc paragraph to that
method explaining that `RepeatDeclineStrategy` reads the same switch, which
pushed the read down by roughly a dozen lines. Track 9's own
`## Interfaces and Dependencies` carries the same stale `:338`, so the drift is
shared rather than a Track 11 error.

Every other line reference in this track file resolves exactly, which is worth
recording so a reader does not treat this as a symptom of general drift:
`RecognitionContext.appendPostConcatOp:286`, `RecognitionContext.walkChild:333`,
`WalkerContext.walkChild:598`, `SubTraversalPredicateAdapter:89` / `:397` /
`:413`, `ResultShaping.withListShapingOps:106`, `UnionForkHost.walkFork:40`,
`UnionForkHostImpl.walkFork:74`, `GremlinStepWalker.subWalk:399-411`.

**Proposed fix**: update the citation to `:351` in item 7. The safer form, given
that this file keeps moving, is to cite the method name
(`GremlinToMatchStrategy.resolveSessionIfEnabled`) rather than the line.

## Evidence base

#### C1 Premise: `RecognitionContext` carries the seam sites item 1 extends
- **Track claim**: item 1 adds `appendListShapingOp` and
  `supportsListShaping()` to `RecognitionContext`; `## Interfaces and
  Dependencies` cites `appendPostConcatOp` (`:286`) as the throwing precedent and
  `walkChild` (`:333`).
- **Search performed**: `find -name RecognitionContext.java`; full Read. PSI
  `findClass` attempted first and timed out.
- **Code location**:
  `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RecognitionContext.java`
- **Actual behavior**: package-private `interface RecognitionContext extends
  ParamSink` (`:38`). `void setResultShaping(@Nonnull ResultShaping shaping)` at
  `:270`. `default void appendPostConcatOp(@Nonnull PostConcatOp op) { throw new
  UnsupportedOperationException("post-concat ops are top-level only"); }` at
  `:286-288`. `SubTraversalPredicateAdapter walkChild(Traversal.Admin<?, ?>
  child)` at `:333`. `default boolean hasUnionCarrier() { return false; }` at
  `:277-279`; `default @Nonnull List<PostConcatOp> postConcatOps()` at `:291`;
  `default boolean anyUnionChildHasCardinalityClause()` at `:304`; `@Nullable
  default UnionForkHost unionForkHost()` at `:340`.
- **Verdict**: CONFIRMED
- **Detail**: the package is `gremlin.translator.strategy`, not the
  `gremlin.tomatch` an FQN reconstruction from the track file's prose would
  guess. Note also that `setResultShaping`'s javadoc says "the seven flags"
  (`:263`) while the record has eight components — the stale wording the track
  already lists for correction.

#### C2 Premise: `WalkerContext` is the top-level implementor the append lands on
- **Track claim**: item 1 implements the append on `WalkerContext` as
  `withListShapingOps(existing + op)`; `WalkerContext.shaping()` is a
  package-private reader; `WalkerContext.walkChild` is at `:598`.
- **Search performed**: `find -name WalkerContext.java`; targeted greps for
  `shaping`, `walkChild`, `setResultShaping`.
- **Code location**: `…/strategy/WalkerContext.java`
- **Actual behavior**: `final class WalkerContext implements RecognitionContext`
  (`:43`). Field `ResultShaping shaping = ResultShaping.NONE;` (`:128`) with the
  javadoc the track flags ("the seven flags … a terminator replaces it through
  `setResultShaping`", `:120-127`). `setResultShaping` is a plain field write
  (`:577-579`). `ResultShaping shaping()` package-private reader at `:583-585`.
  `walkChild` at `:598`, throwing when the context was built without a registry
  (`:604`).
- **Verdict**: CONFIRMED

#### C3 Premise: `SubTraversalPredicateAdapter` is the decline-side implementor
- **Track claim**: item 1 overrides `supportsListShaping()` to `false` on the
  adapter; `## Interfaces and Dependencies` cites `:89` (shared-registry
  comment), `:397` (`setResultShaping` swallow), `:413` (`walkChild`).
- **Search performed**: full Read of the cited ranges; `grep -rn "implements
  RecognitionContext" core/src`; `grep -rln RecognitionContext core/src/test`.
- **Code location**: `…/strategy/SubTraversalPredicateAdapter.java`
- **Actual behavior**: `final class SubTraversalPredicateAdapter implements
  RecognitionContext` (`:83`). The shared-registry field comment is at `:89-91`.
  `setResultShaping` at `:397` is a documented swallow ("boundary row-projection
  shaping is pinned by terminal recognisers on the outer context only").
  `walkChild` at `:413` returns `GremlinStepWalker.subWalk(child, this,
  recognisers)`, so a nested combinator's grandchild also runs on an adapter.
  Exactly two production implementors: this and `WalkerContext`. Two test classes
  reference the interface; `SubTraversalPredicateAdapterTest` uses
  `mock(RecognitionContext.class)` at `:60`, `:86`, `:104`, `:127` and
  `AndStepRecogniserTest` drives the production registry.
- **Verdict**: PARTIAL
- **Detail**: the two-implementor count is grep-based, so a third implementor
  behind generic dispatch or an anonymous class would be missed. Item 1's plan
  leaves the adapter's mandatory `appendListShapingOp` body unspecified (T2), and
  the mocked-interface tests are the vacuous-pass surface for
  `supportsListShaping()` (T4).

#### C4 Premise: `ResultShaping.withListShapingOps` exists at `:106` with no production caller
- **Track claim**: "`ResultShaping.withListShapingOps(@Nonnull
  List<ListShapingOp>)` exists at `ResultShaping.java:106`, replaces the list
  wholesale, and has no production caller yet."
- **Search performed**: full Read of `ResultShaping.java`; `grep -rn
  "withListShapingOps\|listShapingOps()" core/src --include=*.java`.
- **Code location**: `…/step/ResultShaping.java:106-109`
- **Actual behavior**: an 8-component record; `withListShapingOps` returns a copy
  with `ops` substituted wholesale. The compact constructor `List.copyOf`s both
  list components (`:55-58`), so the record is genuinely immutable and its
  `equals` compares `listShapingOps` element-wise. Call sites: thirteen in
  `YTDBMatchPlanStepTest`, zero in `src/main` outside the declaration.
- **Verdict**: CONFIRMED
- **Detail**: reference-accuracy caveat — the "no production caller" half is a
  grep negative, bounded rather than established.

#### C5 Premise: `ListShapingOp` is the carrier, and its javadoc contradicts the union behaviour
- **Track claim**: `## Context and Orientation` says `ListShapingOp`'s javadoc
  falsely claims the base rebuilds its shaped iterator "once per child plan for a
  multi-plan boundary"; item 3 says the `unfold` one-liner needs correcting.
- **Search performed**: full Read.
- **Code location**: `…/step/ListShapingOp.java`
- **Actual behavior**: `@FunctionalInterface` with one method
  `Iterator<Object> apply(Iterator<Object> upstream)` (`:53`). `:35-37` reads
  "The boundary base rebuilds its shaped iterator on every (re)open of an arming
  — after a `reset()` and reopen, and once per child plan for a multi-plan
  boundary — calling `apply` afresh each time." The `unfold` description at
  `:21-22` is "`unfold` expands a list payload into its elements", which covers
  one of the five `flatMap` arms. The surrounding advice — allocate the buffer
  inside the returned iterator, hold no state across `apply` calls — is correct
  for the reset-and-reopen case.
- **Verdict**: CONFIRMED
- **Detail**: the false clause is contradicted by `MultiPlanMatchStep`'s own
  class javadoc and by the code (C11).

#### C6 Premise: `PostConcatOp.Count.INSTANCE` is the singleton house style DR-T3 cites
- **Track claim**: DR-T3 cites `PostConcatOp.Count.INSTANCE` as the record
  singleton whose `equals` would defeat reference-identity decline.
- **Search performed**: full Read.
- **Code location**: `…/step/PostConcatOp.java`
- **Actual behavior**: `sealed interface PostConcatOp permits Count, Range,
  Dedup`; `record Count() { public static final Count INSTANCE = new Count(); }`
  (`:21-23`); `record Dedup()` likewise (`:38-40`); `record Range(long skip, long
  limit)` with a validating compact constructor. `isPushDownCountOnly(ops)` at
  `:43-45`.
- **Verdict**: PARTIAL
- **Detail**: the singleton claim is confirmed. The class javadoc's opening
  (`:8`) attributes the list-shaping terminators to "Track 9", which the split
  moved to Track 11 — a stale site the track's four-site list omits (T6).

#### C7 Premise: `POST_UNION_RECOGNISERS` is one field read from two places
- **Track claim**: item 4 says "both readers are the walker's own — `dispatchAll`'s
  fail-closed gate and `postUnionSuffixTranslatable`'s look-ahead — so the one
  field covers both paths."
- **Search performed**: full Read of `GremlinStepWalker.java`.
- **Code location**: `…/strategy/GremlinStepWalker.java:206-210`, read at `:335`
  and `:393`
- **Actual behavior**: `private static final Set<StepRecogniser>
  POST_UNION_RECOGNISERS = Set.of(CountGlobalStepRecogniser.INSTANCE,
  RangeGlobalStepRecogniser.INSTANCE, DedupGlobalStepRecogniser.INSTANCE);`.
  `dispatchAll` consults it per step at `:335`
  (`if (ctx.hasUnionCarrier() && !POST_UNION_RECOGNISERS.contains(recogniser))
  return false;`). `postUnionSuffixTranslatable` consults it as a look-ahead at
  `:393`, reached from `UnionForkHostImpl.postUnionSuffixTranslatable` (`:68-70`)
  before any fork. Both readers reach the same field; the two-reader claim holds.
- **Verdict**: PARTIAL
- **Detail**: the mechanism is confirmed, the javadoc is not. `:188-191` describes
  the set as "the three whose step maps to a `PostConcatOp`", which item 4's four
  additions falsify (T6). Its `:194-197` clause does list "the shaping" among what
  the multi-plan `buildResult` reads, so the relaxation is sound (I2).

#### C8 Premise: a union child is walked as a full top-level walk with a fresh context
- **Track claim**: item 4's union-child gate reads `childResult.shaping()`;
  `## Interfaces and Dependencies` cites `UnionForkHost.walkFork:40` and
  `UnionForkHostImpl.walkFork:74`.
- **Search performed**: full Read of both files.
- **Code location**: `…/strategy/UnionForkHost.java:40-41`,
  `…/strategy/UnionForkHostImpl.java:72-88`
- **Actual behavior**: `walkFork` clones the memoised recognised prefix plus the
  child suffix into a fresh `DefaultGraphTraversal`, copies the parent's
  strategies, and calls `GremlinStepWalker.production().walk(forked)` — a
  complete top-level walk against a fresh `WalkerContext`, returning `null` on
  decline. So a union child's context is a `WalkerContext`, whose
  `supportsListShaping()` would inherit the `true` default; the child's `fold()`
  is claimed, which is exactly why DR-T3's separate non-empty-`listShapingOps`
  gate is needed rather than relying on the adapter override.
- **Verdict**: CONFIRMED

#### C9 Premise: `UnionStepRecogniser` pins `agreedShaping` before any suffix op appends
- **Track claim**: item 1 says the append and `setResultShaping` are kept from
  colliding partly by "`UnionStepRecogniser` calling
  `setResultShaping(agreedShaping)` before any suffix op appends"; DR-T3 says the
  recogniser compares `!agreedShaping.equals(childResult.shaping())`.
- **Search performed**: full Read.
- **Code location**: `…/strategy/UnionStepRecogniser.java`
- **Actual behavior**: ordering inside `recognize` is `postUnionSuffixTranslatable`
  look-ahead (`:69`) → prefix and children non-empty gates (`:73-81`) → per-child
  `walkFork` loop with the three-way contract comparison
  `!agreedOutputType.equals(…) || !agreedReturnClass.equals(…) ||
  !agreedShaping.equals(childResult.shaping())` (`:106-108`) →
  `ctx.pinBoundary(…)` (`:123`) → `ctx.setResultShaping(agreedShaping)` (`:124`)
  → `stashAcceptedChildren` (`:125`) → `ACCEPTED`. `dispatchAll` then dispatches
  the suffix, so a suffix append lands on `agreedShaping`. The class javadoc says
  "the list-shaping terminators (`fold` and friends) are not translated yet"
  (`:28-29`) — the stale comment the track lists.
- **Verdict**: CONFIRMED
- **Detail**: DR-T3's accidental-decline reasoning holds. `ListShapingOp` is a
  `@FunctionalInterface`, so per-call lambdas compare by identity and two
  children's ops are unequal; a record singleton or a value-equal record
  (`TailOp(1)` in both children) compares equal and would ship the wrong answer.

#### C10 Premise: the boundary base's lifecycle, open routes, and list-shaping stage
- **Track claim**: seven `State` constants; `applyListShaping` calls
  `op.apply(...)` afresh on every open across three open routes;
  `resetLifecycleForClone()` deliberately does not touch `shaping`.
- **Search performed**: targeted Reads of `:140-260`, `:300-440`, `:683-688`.
- **Code location**: `…/step/AbstractMatchPlanStep.java`
- **Actual behavior**: `private enum State { NEW, OPEN, DRAINED, REARMED, CLOSED,
  CLOSED_UNSTARTED, REARMED_AFTER_CLOSE }` (`:188-246`) — seven, as claimed.
  `processNextStart()` opens on `NEW`, `REARMED`, `REARMED_AFTER_CLOSE` (`:320`)
  and nulls `shapedPayloads` (`:328`), so `openShapedPayloads()` (`:372-376`) and
  through it `applyListShaping()` (`:386-396`) run once per arming across three
  routes. `applyListShaping` returns `source` untouched on an empty op list (the
  structural bypass) and otherwise folds `op.apply(shaped)` left to right.
  `resetLifecycleForClone()` (`:683-688`) clears `openStream`, `armingGraph`,
  `shapedPayloads` and sets `state = NEW`; it does not touch `shaping`, which is
  `private final` and therefore shallow-copied by reference through
  `AbstractStep.clone()`.
- **Verdict**: CONFIRMED
- **Detail**: both statefulness hazards the track records are real — an op
  allocating its buffer outside the returned iterator replays on the second
  arming, and two clones share the same op instances.

#### C11 Premise: `MultiPlanMatchStep` applies list shaping once over the concatenation, after post-concat ops
- **Track claim**: `## Context and Orientation` says `startPlanStream()` returns
  one `MultipleExecutionStream` and `openShapedPayloads()` runs once per arming
  over it; item 4 says the union suffix "still folds once over the whole
  concatenation".
- **Search performed**: Read of `:28-160`; targeted grep for `startPlanStream`,
  `postConcatOps`, `MultipleExecutionStream`.
- **Code location**: `…/step/MultiPlanMatchStep.java:28-48`, `:308-338`
- **Actual behavior**: the class javadoc states it outright — "the base projects
  the concatenation as if it were one stream, so row projection and the ordered
  list-shaping post-process apply once over the whole union (this is what lets a
  later `union().fold()` fold the whole union into one list rather than one list
  per child)" (`:44-48`). `startPlanStream()` branches on
  `PostConcatOp.isPushDownCountOnly(postConcatOps)` (`:311`) into
  `sumChildCountStreams()`, otherwise builds the `MultipleExecutionStream`
  (`:337`) and wraps it with one decorator per op in recognised order (`:338`).
  The base's `openShapedPayloads()` then runs over the decorated stream.
- **Verdict**: CONFIRMED
- **Detail**: this both confirms item 4's union-suffix claim and establishes the
  fixed cross-carrier order T3 reports — post-concat ops always execute before
  list-shaping ops, and the lone-`Count` push-down rewrites children at build
  time, which is what makes E2 structurally worse than E1.

#### C12 Premise: `BoundaryOutputType` keeps four constants and names only `YTDBMatchPlanStep`
- **Track claim**: DR-T1 says the enum keeps its four constants;
  `## Interfaces and Dependencies` lists the class-javadoc opening sentence as a
  stale site.
- **Search performed**: Read of `:1-20`.
- **Code location**: `…/step/BoundaryOutputType.java`
- **Actual behavior**: `ELEMENT`, `MAP`, `SINGLE_VALUE`, `SCALAR`. The javadoc
  opens "The shape that `{@link YTDBMatchPlanStep}` emits as TinkerPop traversers"
  (`:4`), which predates `MultiPlanMatchStep` and the shared base.
- **Verdict**: CONFIRMED

#### C13 Premise: the four terminator step classes in the resolved fork jar
- **Track claim**: `FoldStep` has two constructors distinguished by
  `isListFold()`; `UnfoldStep.flatMap` dispatches five ways; `ReverseStep.map` is
  a value transform; `TailGlobalStepContract.CONCRETE_STEPS` is `List.of(
  TailGlobalStep.class, TailGlobalStepPlaceholder.class)`.
- **Search performed**: `javap` (and `javap -c` for the `CONCRETE_STEPS` static
  initialiser) against
  `~/.m2/repository/io/youtrackdb/gremlin-core/3.8.1-67860f6-SNAPSHOT/gremlin-core-3.8.1-67860f6-SNAPSHOT.jar`,
  the version `pom.xml:114` pins.
- **Code location**: fork jar, `org.apache.tinkerpop.gremlin.process.traversal.step.{map,filter}`
- **Actual behavior**: `FoldStep<S,E> extends ReducingBarrierStep` with
  `FoldStep(Traversal$Admin)` and `FoldStep(Traversal$Admin, Supplier, BiFunction)`
  plus `public boolean isListFold()`. `UnfoldStep<S,E> extends FlatMapStep` with
  `protected Iterator<E> flatMap(Traverser$Admin)`. `ReverseStep<S,E> extends
  ScalarMapStep` with `protected E map(Traverser$Admin)`. `TailGlobalStep` and
  `TailGlobalStepPlaceholder` both `implements TailGlobalStepContract`, which
  declares `public abstract Long getLimit()` and `public default GValue<Long>
  getLimitAsGValue()`. The `CONCRETE_STEPS` static initialiser is
  `ldc TailGlobalStep; ldc TailGlobalStepPlaceholder; invokestatic List.of(Object,Object)`
  — byte-confirmed exactly as claimed.
- **Verdict**: CONFIRMED
- **Detail**: the FQNs are `…step.map.UnfoldStep` and `…step.map.ReverseStep`,
  both in `map` rather than a `flatmap` package. No `FoldStepPlaceholder`,
  `UnfoldStepPlaceholder`, or `ReverseStepPlaceholder` exists in the jar, so
  `tail` is the only one of the four with a two-form registration. Because
  `getLimitAsGValue()` is a `default` on the contract, item 3's "read
  `getLimitAsGValue()` and decline on `isVariable()` before touching
  `getLimit()`" is available on both forms.

#### C14 Premise: reading `TailGlobalStepPlaceholder.getLimit()` pins the GValue variable
- **Track claim**: "`TailGlobalStepPlaceholder.getLimit()` is not a pure read: it
  checks `GValue.isVariable()` and, when true, calls
  `traversal.getGValueManager().pinVariable(name)` before returning the concrete
  `Long`."
- **Search performed**: `javap -c -p` on `TailGlobalStepPlaceholder`.
- **Code location**: fork jar, `TailGlobalStepPlaceholder.getLimit()`
- **Actual behavior**: the bytecode is
  `limit.isVariable()` → if false jump to the return path; otherwise
  `traversal.getGValueManager().pinVariable(limit.getName())`, discard the
  `boolean`, then `limit.get()` cast to `Long` and return.
- **Verdict**: CONFIRMED
- **Detail**: the mutation lands on TinkerPop's `GValueManager`, not on
  `WalkerContext`, so the track's reading of D9's no-mutation-on-decline
  discipline holds. The `RangeGlobalStepPlaceholder.getLowRange()` precedent was
  not re-decompiled this iteration.

#### C15 Premise: the kill-switch and where it is read
- **Track claim**: item 7 says
  `GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED` "is read by
  `GremlinToMatchStrategy:338`, so the on/off axis is real."
- **Search performed**: grep for the constant in
  `GlobalConfiguration.java` and `GremlinToMatchStrategy.java`; Read of
  `GremlinToMatchStrategy:330-365` and `ContextConfiguration:90-155`.
- **Code location**: `…/api/config/GlobalConfiguration.java:1019-1027`;
  `…/strategy/GremlinToMatchStrategy.java:345-353`;
  `…/config/ContextConfiguration.java:90-96,149-155`
- **Actual behavior**: the enum constant is
  `youtrackdb.query.gremlin.toMatchTranslator.enabled`, `Boolean.class`, true by
  default. `resolveSessionIfEnabled` resolves the session, null-guards
  `session.getConfiguration()`, then reads
  `configuration.getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED)`
  at `:350-351`. `ContextConfiguration.getValue(GlobalConfiguration)` returns its
  own map entry when present and otherwise falls back to `iConfig.getValue()` —
  the live global.
- **Verdict**: PARTIAL
- **Detail**: the on/off axis is real and, because the read is per traversal
  through a global-falling-back lookup, an in-process flip between two runs over
  one fixture does take effect unless the session's `ContextConfiguration`
  carries a local override. Item 7's boundary-step presence assertion is what
  witnesses that either way, so the method is sound. The cited line is stale:
  `:338` is inside the method body before the configuration is fetched (T8).

#### C16 Premise: `jmh-ldbc/src/test` provides a runnable in-memory fixture
- **Track claim**: item 7 says "`jmh-ldbc/src/test` is not fixture-less —
  `LdbcQueryCorrectnessTest` builds a small deterministic in-memory social graph
  and asserts all 20 LDBC read queries against it, and those tests run in an
  ordinary build."
- **Search performed**: `grep -n "<module>" pom.xml`; `find jmh-ldbc/src/test`;
  Read of `LdbcQueryCorrectnessTest:1-140`; `ls jmh-ldbc/target/surefire-reports`;
  Read of `jmh-ldbc/pom.xml:1-115`.
- **Code location**: `jmh-ldbc/src/test/java/…/LdbcQueryCorrectnessTest.java:128-137`
- **Actual behavior**: `jmh-ldbc` is a reactor module (`pom.xml:54`). `@BeforeClass
  setupDatabase()` creates a temp directory, `db.create(DB_NAME,
  DatabaseType.MEMORY, …)`, then `g = db.openTraversal(…)` returning a
  `YTDBGraphTraversalSource`, then `createSchema()` and `loadTestData()`. The
  module's POM adds no surefire skip and
  `jmh-ldbc/target/surefire-reports/` holds results for both
  `LdbcQueryCorrectnessTest` and `LdbcQueryExplainTest`, so they do run.
  `jmh-ldbc` depends only on `youtrackdb-core`, which carries the gremlin surface
  and the translator strategy.
- **Verdict**: CONFIRMED

#### C17 Premise: the harness entry point can be driven from that fixture
- **Track claim**: item 7 says to "point an in-track JUnit test at that fixture
  pattern and drive one recognised shape through the harness's own entry point
  twice, kill-switch on and off", including "a `g.V(rid)` by-id shape".
- **Search performed**: Read of `LdbcBenchmarkState.java:25-75,243-305`; grep of
  its accessors; Read of `LdbcISBenchmarkBase.java` method shapes; `ls
  jmh-ldbc/src/main`.
- **Code location**: `jmh-ldbc/src/main/java/…/LdbcBenchmarkState.java:60-64,243-273`
- **Actual behavior**: `@State(Scope.Benchmark)` with `YouTrackDB db;` and
  `YTDBGraphTraversalSource traversal;` package-private (`:60-61`) and `private
  ParameterCurator.CuratedParams curatedParams;` (`:64`) with no setter. Every
  parameter accessor (`isPersonId`, `isMessageId`, `ic1PersonId`, `ic2MaxDate`, …)
  dereferences `curatedParams`. `@Setup(Level.Trial) setup()` (`:243-273`)
  resolves `ldbc.db.path` / `ldbc.dataset.path`, creates a `DatabaseType.DISK`
  database, and loads CSV or reuses a prebuilt archive, throwing
  `IllegalStateException` when neither is present. Existing `@Benchmark` methods
  take `LdbcBenchmarkState` and read `state.traversal` plus a curated parameter
  (`LdbcISBenchmarkBase:53-80`). The module has no existing Gremlin benchmark
  class — `LdbcQuerySql` indicates the current benchmarks are SQL-driven.
- **Verdict**: PARTIAL
- **Detail**: `db` / `traversal` being package-private makes a same-package
  injection possible, so item 7's route is not closed. `curatedParams` being
  private with no setter closes it for any parameter-bearing entry point,
  including the by-id shape item 7 names (T5).

#### C18 Premise: `MultiPlanMatchStepTest` already carries the clone-isolation idiom
- **Track claim**: item 5 says "`MultiPlanMatchStepTest` already has the
  clone-isolation idiom to copy."
- **Search performed**: `grep -n clone MultiPlanMatchStepTest.java`.
- **Code location**: `core/src/test/java/…/step/MultiPlanMatchStepTest.java:591-668`
- **Actual behavior**: three clone tests —
  `clone_copiesEveryChildPlan_forIndependentExecution` (`:596`),
  `clone_copiesEachChildAgainstItsOwnIsolatedChildContext` (`:626`),
  `clone_givesEachCloneAndTheOriginalItsOwnCoordinatorContext` (`:655`). The third
  builds two clones from one original, which is the shape item 5's "two
  concurrently-iterated clones of a `fold()` boundary" needs.
- **Verdict**: CONFIRMED

#### C19 Premise: an in-repo last-step check idiom exists
- **Track claim**: implied by "accepted only as the last step" — the recognisers
  need a way to ask.
- **Search performed**: `grep -rn "peek()" strategy/*Recogniser.java`.
- **Code location**: `…/strategy/CombinatorFoldedHopRecogniser.java:43`
- **Actual behavior**: `if (cursor.peek() != null)` is the existing
  nothing-follows guard. `StepCursor.peek()` skips transparent steps and counts
  them consumed; `peek(int)` is the pure look-ahead. Both are available to a
  terminator recogniser, so the class-membership variant T1 proposes is
  expressible with no cursor change.
- **Verdict**: CONFIRMED

#### C20 Premise: what D3 actually says
- **Track claim**: `## Context and Orientation` line 33 and item 3 cite `(D3)`
  for the last-step and mid-traversal-decline rules.
- **Search performed**: grep for `D3` in `implementation-plan.md`; Read of
  `:160-200` and `:690-745`.
- **Code location**: `_workflow/implementation-plan.md:168-180`, `:740`
- **Actual behavior**: D3 is "All-or-nothing translation, no hybrid prefix" —
  one unrecognised step declines the whole traversal, implemented in Track 2 and
  "enforced by every recognizer". Line 740 states the distinction explicitly:
  "D3 is *all-or-nothing decline*, not the terminators — it is enforced by every
  recogniser, including Track 11's four, whose mid-traversal and child-path
  declines are the split's new D3 surface." The plan's Track 11 entry describes
  the four as "last-step recognisers … with mid-traversal use and both child
  paths declining under D3".
- **Verdict**: PARTIAL
- **Detail**: the plan reconciles the naming; the track file does not. Citing
  `(D3)` as the source of the last-step rule points at a Decision Record that
  contains only the decline mechanism, and the rule itself is defined nowhere
  (T1).

#### C21 Premise: `setResultShaping` clobbers `listShapingOps` at every production call site
- **Track claim**: item 1 says "`setResultShaping` remains a full replace of the
  whole record including `listShapingOps`, so the append's no-clobber guarantee
  covers only recognisers that use the new method, and what keeps the two from
  colliding is D3's last-step rule."
- **Search performed**: `grep -rn "setResultShaping(" gremlin/translator
  --include=*.java`; Read of `GremlinAggregateAssembler.configureCount`.
- **Code location**: `GremlinAggregateAssembler.java:79,112,136,172,200`;
  `GremlinProjectionAssembler.java:57,83,134`; `SelectStepRecogniser.java:63`;
  `SelectOneStepRecogniser.java:60`; `ProjectStepRecogniser.java:47`;
  `UnionStepRecogniser.java:124`
- **Actual behavior**: twelve production call sites. Eleven build from
  `ResultShaping.NONE` (`count`, post-union `count`, `sum`/`min`/`max`/`mean`,
  `group`, `groupCount`, `values`/`valueMap`/`elementMap`/`properties`, `select`
  ×2, `project`), so each discards any appended `ListShapingOp`. The twelfth
  (`UnionStepRecogniser:124`) passes `agreedShaping` through.
  `GremlinAggregateAssembler.configureCount` gates on `hasUnionCarrier()`, on
  `hasPreAggregateCardinalityClause(ctx)` and on a null boundary, then calls
  `ctx.setResultShaping(ResultShaping.NONE)` at `:79`; it never inspects
  `listShapingOps()`.
- **Verdict**: WRONG
- **Detail**: not wrong about the mechanism — wrong about the size of what the
  last-step rule has to guard. The track presents the clobber as a limit on the
  append's guarantee; it is the whole correctness basis for eleven recognisers
  that will silently discard an appended op, and the guard is a rule the track
  states in a form its own accept set contradicts (T1).

#### E1 Edge case: `g.V().values("age").fold().count()` on the single-plan path
- **Trigger**: a non-list-shaping recognised terminator follows a list-shaping
  terminator, with no union carrier.
- **Code path trace**:
  1. `GremlinStepWalker.dispatchAll` @ `GremlinStepWalker.java:323` dispatches
     `GraphStep` → `PropertiesStep` → `FoldStep` → `CountGlobalStep`.
  2. The Track 11 `FoldStep` recogniser calls `ctx.appendListShapingOp(drainOp)`;
     `WalkerContext.shaping` becomes
     `NONE.withUnwrap…(…).withListShapingOps([drainOp])`.
  3. `CountGlobalStepRecogniser.recognize` @
     `CountGlobalStepRecogniser.java:19-25` delegates to
     `GremlinAggregateAssembler.configureCount(ctx)`.
  4. `configureCount` @ `GremlinAggregateAssembler.java:65-81`: no union carrier,
     no pre-aggregate cardinality clause, boundary non-null → sets
     `RETURN count(*)` and `ctx.setResultShaping(ResultShaping.NONE)` @ `:79`.
     `drainOp` is gone.
  5. `buildResult` @ `GremlinStepWalker.java:514-521` packages
     `ctx.shaping()` = `NONE`.
  6. `AbstractMatchPlanStep.applyListShaping` @ `:386-390` takes the empty-list
     structural bypass; the boundary emits one `SCALAR` payload holding the row
     count.
- **Outcome**: the traversal returns the number of `age` values where native
  `fold().count()` returns `1`. Silent — one boundary step installed, no decline,
  no exception.
- **Track coverage**: no. The decline set names `fold().unfold()` and
  `fold().tail(3)` — both list-shapers. A following non-list-shaper appears in
  neither the accept nor the decline set (T1).

#### E2 Edge case: `union(__.out(), __.in()).fold().count()` on the count push-down path
- **Trigger**: the same shape after a union, with item 4's four terminators
  added to `POST_UNION_RECOGNISERS`.
- **Code path trace**:
  1. `UnionStepRecogniser.recognize` @ `UnionStepRecogniser.java:69` calls
     `host.postUnionSuffixTranslatable()`, which scans the suffix
     (`FoldStep`, `CountGlobalStep`) against `POST_UNION_RECOGNISERS`
     @ `GremlinStepWalker.java:383-397`. Both allow-listed after item 4 → `true`.
  2. Children walk and agree; `ctx.setResultShaping(agreedShaping)` @ `:124`;
     `ACCEPTED`.
  3. `dispatchAll` dispatches `FoldStep`; the recogniser appends `drainOp` onto
     `agreedShaping` (item 1's stated protection holds here).
  4. `dispatchAll` dispatches `CountGlobalStep`; `configureCount` sees
     `hasUnionCarrier()` → `configurePostUnionCount` @
     `GremlinAggregateAssembler.java:88-115`, which appends
     `PostConcatOp.Count.INSTANCE` and calls
     `ctx.setResultShaping(ResultShaping.NONE)` @ `:112`.
  5. `buildResult`'s multi-plan branch @ `GremlinStepWalker.java:471-480` passes
     `ctx.shaping()` (`drainOp` already discarded at step 4) and
     `ctx.postConcatOps()` = `[Count]`.
  6. `MultiPlanMatchStep.startPlanStream` @ `:311` sees
     `isPushDownCountOnly` → `sumChildCountStreams()`: every child was rewritten
     to `RETURN count(*)` at build time, opened, read for its single scalar row,
     and summed.
- **Outcome**: one scalar row holding the summed child counts, where native
  `union(…).fold().count()` returns `1`. Structurally worse than E1: the child
  plans were rewritten at build time, so the element concatenation the `fold`
  was supposed to drain is never materialised at all.
- **Track coverage**: no. Item 4 reasons about children carrying ops and about
  suffix ops appending onto `agreedShaping`; a list-shaper *preceding* a
  post-concat op is not considered (T1, T3).

#### E3 Edge case: `tail(n)` with `n` above `Integer.MAX_VALUE`
- **Trigger**: `g.V().tail(Long.MAX_VALUE)`, or any `n > 2^31-1`.
- **Code path trace**:
  1. The `TailGlobalStep` recogniser reads `getLimit()` — a `Long`, byte-confirmed
     on both concrete forms (C13).
  2. Item 3 prescribes "a bounded `ArrayDeque` ring buffer" sized from `n`.
  3. `new ArrayDeque<>(int)` requires an `int`. `(int) Long.MAX_VALUE` is `-1`;
     `ArrayDeque` throws `IllegalArgumentException` on a negative capacity.
  4. If the buffer is sized at recognition time, the throw escapes through
     `TraversalStrategy.apply()` — the loud failure DR-T2 rules out. If it is
     sized inside the returned iterator (which `ListShapingOp`'s javadoc requires
     for a different reason), the throw lands on the first pull, inside
     `processNextStart`'s terminal handler @
     `AbstractMatchPlanStep.java:348-363`, which closes the plan and rethrows.
- **Outcome**: a legal traversal that native `TailGlobalStep` handles fails on
  the translated path, either at strategy-apply time or at first pull.
- **Track coverage**: no. `## Context and Orientation` and acceptance line 79
  name `n=0` and `n<0` only. No upstream feature scenario uses a global `tail`
  above 10, which is why this is a suggestion (T7).

#### I1 Integration: item 1's seam against Track 9 step 3's `buildResult` edit
- **Plan claim**: not made by the track; raised by the spawning orchestrator —
  whether item 1's seam and item 4's child gating collide with Track 9 step 3's
  post-`build()` per-alias filter merge in `GremlinStepWalker.buildResult`.
- **Actual entry point**: `GremlinStepWalker.buildResult` @ `:470-522`;
  Track 9's default site is option 2, "a post-`build()` pass in
  `GremlinStepWalker.buildResult` over `ir.pattern()`'s edge items, using
  `finalAliasFilters` and `ir.aliasClasses()`" (`plan/track-9.md` item 2).
- **Caller analysis**: `buildResult` is `private static`, called once from
  `walk` @ `:311`. `walk` is reached from `GremlinToMatchStrategy` and — per
  child — from `UnionForkHostImpl.walkFork` @ `:87`.
- **Breaking change risk**: low, and semantically disjoint. Track 9's fix mutates
  `SQLMatchPathItem` filters and classes on the assembled `Pattern`, reading
  `finalAliasFilters` (built @ `:484-490`) and `ir.aliasClasses()`. Track 11's
  ops travel on `ResultShaping.listShapingOps()`, read at `:479` (multi-plan) and
  `:521` (single-plan), and never touch the pattern. Track 11 item 1 does not
  edit `GremlinStepWalker` at all; item 4 edits only the `POST_UNION_RECOGNISERS`
  static field @ `:206-210` and the registry @ `:156-185`, both far from
  `buildResult`. The single shared file is `GremlinStepWalker.java`, at
  non-overlapping regions, so the exposure is a textual rebase conflict rather
  than a semantic one. Union children reach Track 9's fix because `walkFork`
  runs a full `walk()` per child (C8), and item 4's child gate fires afterward on
  the returned result, so the two are sequenced rather than interleaved.
- **Verdict**: MATCHES

#### I2 Integration: the `POST_UNION_RECOGNISERS` relaxation against the multi-plan `buildResult` branch
- **Plan claim**: item 4 adds the four terminator recognisers to the allow-list.
- **Actual entry point**: `GremlinStepWalker.buildResult` multi-plan branch @
  `:471-480`; the allow-list's own javadoc @ `:193-199`.
- **Caller analysis**: the allow-list is read at `:335` (`dispatchAll`) and `:393`
  (`postUnionSuffixTranslatable`), per C7.
- **Breaking change risk**: none for the mechanism. The javadoc's stated hazard
  is that a post-union recogniser writing into state the multi-plan branch
  discards has its contribution silently dropped — and it names "the shaping"
  among what that branch *does* read (`:194-197`), which `:479`'s
  `ctx.shaping()` confirms. So a shaping-writing recogniser is a legitimate
  addition, and item 4's relaxation does not create a silent-discard path. What
  it does create is reachability for mixed suffixes across two carriers with a
  fixed relative order (C11, T3), and reachability for E2.
- **Verdict**: MATCHES

#### I3 Integration: adding a non-`default` method to `RecognitionContext`
- **Plan claim**: item 1 adds `void appendListShapingOp(@Nonnull ListShapingOp
  op)` (no `default`) plus `default boolean supportsListShaping() { return
  true; }`.
- **Actual entry point**: `RecognitionContext.java:38-343`
- **Caller analysis**: two production implementors, `WalkerContext:43` and
  `SubTraversalPredicateAdapter:83`; both must supply an
  `appendListShapingOp` body. Two test classes touch the interface:
  `SubTraversalPredicateAdapterTest` mocks it (`:60`, `:86`, `:104`, `:127`) and
  `AndStepRecogniserTest` drives the production registry. A Mockito mock absorbs
  a new abstract method without a compile break, so no existing test fails to
  compile.
- **Breaking change risk**: the compile surface is safe; the behavioural surface
  is not. `SubTraversalPredicateAdapter` needs a body the track does not specify
  and whose two obvious candidates DR-T2 forbids (T2). And
  `supportsListShaping()` returning `true` by default inverts to `false` under a
  mock, which is the first default on this interface where the mocked value
  differs from the production value — every existing default already returns
  `false`, an empty list, or throws (T4).
- **Verdict**: CALLERS AT RISK
