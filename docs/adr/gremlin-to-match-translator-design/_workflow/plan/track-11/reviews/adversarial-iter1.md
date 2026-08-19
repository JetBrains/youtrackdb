<!-- MANIFEST
findings: 9   severity: {blocker: 2, should-fix: 5, suggestion: 2}
index:
  - {id: A1, sev: blocker,    loc: "GremlinStepWalker.java:206 (POST_UNION_RECOGNISERS); track-11.md:67,84", anchor: "### A1 ", cert: V1, basis: "Measured: union(...).range(2,5) and .limit(3) already return a different MULTISET translator-on vs -off at HEAD; item 4 adds tail and fold to the same allow-list, so the union-suffix acceptance criterion cannot pass and the track widens a live silent-wrong-answer surface under a default-on switch"}
  - {id: A2, sev: blocker,    loc: "jmh-ldbc/src/main/java/com/jetbrains/youtrackdb/benchmarks/ldbc/LdbcBenchmarkState.java:234; track-11.md:70,88", anchor: "### A2 ", cert: AT1, basis: "The named in-track execution path never engages the translator: every LdbcQueryCorrectnessTest query and the only shared harness entry point take a YQL string, and jmh-ldbc/src contains zero Gremlin traversal chains, so the installed/absent boundary assertion is false on both arms"}
  - {id: A3, sev: should-fix, loc: "track-11.md:68,83", anchor: "### A3 ", cert: AT2, basis: "Measured: native g.V().where(__.out().tail(1)) returns exactly the same rows as g.V().where(__.out()), so the swallow bug the case exists to catch produces the passing answer"}
  - {id: A4, sev: should-fix, loc: "UnionStepRecogniser.java:106-108; track-11.md:67,82", anchor: "### A4 ", cert: V2, basis: "The criterion demands the explicit non-empty-listShapingOps gate be the witness, but per-call op instances (which the track's own clone analysis requires) make the pre-existing agreedShaping.equals gate decline first, and no black-box result test can tell the two apart"}
  - {id: A5, sev: should-fix, loc: "GremlinToMatchStrategy.java:224-232; RecognitionContext.java:286; track-11.md:24", anchor: "### A5 ", cert: AT3, basis: "DR-T2 rejects the throw template because it 'throws out of TraversalStrategy.apply()'; apply() catches RuntimeException and declines, so the stated consequence does not occur and an implementer may write a test asserting an escape that can never happen"}
  - {id: A6, sev: should-fix, loc: "track-11.md:69,87,104", anchor: "### A6 ", cert: AT4, basis: "Track 9 explicitly hands Track 11 a reciprocal ancestry/re-trigger obligation on the baseline SHA; item 6 carries none, and item 7 plus Phase C fixes land after item 6's run, so the track can close on a stale no-regression number"}
  - {id: A7, sev: should-fix, loc: "implementation-plan.md Track 11 Scope line; track-11.md:70,101", anchor: "### A7 ", cert: C1, basis: "There is no Gremlin JMH benchmark in the repo to mirror and the LDBC query set is not translator-recognised, so 'mirrored JMH classes' is unsized; a faithful mirror of the five bases plus ten concrete classes alone exhausts the ~14-20 file scope line"}
  - {id: A8, sev: suggestion, loc: "track-11.md:39,66,68", anchor: "### A8 ", cert: AT5, basis: "Measured: graph.traversal().V().tail(1) yields TailGlobalStep, never the placeholder, so the placeholder-form test needs a construction path the track does not name, and the GValue pinning choice is left to the implementer on the last track of the branch"}
  - {id: A9, sev: suggestion, loc: ".claude/workflow/track-review.md:844-846; _workflow/phase-ledger.md", anchor: "### A9 ", cert: AT6, basis: "D14 pins design_gate=yes spawns to Fable 5 and this pass ran on Opus; the ledger carries tier but no design_gate field, so D14's ledger-first read has no resolvable value and no written fallback"}
evidence_base: {section: "## Evidence base", certs: 14, matches: 5}
cert_index:
  - {id: C1,   verdict: WEAK,          anchor: "#### C1 "}
  - {id: V1,   verdict: CONSTRUCTIBLE, anchor: "#### V1 "}
  - {id: V2,   verdict: CONSTRUCTIBLE, anchor: "#### V2 "}
  - {id: V3,   verdict: INFEASIBLE,    anchor: "#### V3 "}
  - {id: AT1,  verdict: BREAKS,        anchor: "#### AT1 "}
  - {id: AT2,  verdict: BREAKS,        anchor: "#### AT2 "}
  - {id: AT3,  verdict: BREAKS,        anchor: "#### AT3 "}
  - {id: AT4,  verdict: BREAKS,        anchor: "#### AT4 "}
  - {id: AT5,  verdict: FRAGILE,       anchor: "#### AT5 "}
  - {id: AT6,  verdict: BREAKS,        anchor: "#### AT6 "}
  - {id: AT7,  verdict: HOLDS,         anchor: "#### AT7 "}
  - {id: AT8,  verdict: HOLDS,         anchor: "#### AT8 "}
  - {id: AT9,  verdict: HOLDS,         anchor: "#### AT9 "}
  - {id: AT10, verdict: HOLDS,         anchor: "#### AT10 "}
flags: [CONTRACT_OK]
-->

# Track 11 adversarial review — iteration 1

Two blockers. The first is measured and lands on live code: a positional suffix after a translated `union(...)` already returns a **different multiset** than native at HEAD, so the track's own union-suffix acceptance criterion cannot pass, and item 4 is about to add two more order-sensitive terminators to the same allow-list. The second is that item 7's in-track execution claim names a fixture and an entry point that never engage the translator, so the boundary-installed / boundary-absent assertion it rests on would be false on both arms.

The rest is a mixed picture. Three of item 5's four decline cases hold up under measurement and one does not: `where(__.out().tail(1))` returns the identical rows to `where(__.out())` natively, so the bug it exists to catch produces the passing answer. DR-T1, DR-T3's per-child divergence, and the `fold(seed, operator)` decline all survive challenge on measured evidence. Single-plan translated arrival order matches native exactly across eight probed shapes, which retires the order worry for everything except the multi-plan path.

## Reviewer notes

**Measurement discipline.** Every behavioural claim below was measured, not derived. A scratch probe (`ScratchA11ProbeTest`) built each shape, dumped the step tree TinkerPop's strategy chain hands the translator, and compared translator-on against translator-off results verbatim. The probe ran in a detached worktree pinned to `54cc0a708f`, because two other agents were editing `core` in the shared checkout while this review ran — one on `GremlinStepWalker` / `GremlinToMatchStrategy` / `RepeatDeclineStrategy`, one on `GremlinStepWalker.buildResult` / `MatchPatternBuilder` / `SQLMatchFilter`. The probe and its worktree were deleted before this file was returned; the shared checkout was left clean.

**Reference accuracy.** mcp-steroid is reachable but `steroid_execute_code` times out on this repository, the same condition Track 9's three panels recorded. Symbol claims here are grep plus a read of each returned site. "No production caller" for `ResultShaping.withListShapingOps` is bounded, not established.

**Model pin.** This spawn ran on Opus; D14 pins it to Fable 5. See A9.

## Findings

### A1 [blocker]
**Certificate**: V1 — violation scenario, CONSTRUCTIBLE and measured
**Target**: Invariant — "Translator-on and translator-off produce equal result multisets for every `RECOGNIZED` shape" (`implementation-plan.md` § Invariants); and the acceptance criterion at `track-11.md:84`
**Challenge**: A positional suffix after a translated `union(...)` already returns a different multiset than native, on code that shipped in Track 8. Track 11 item 4 adds `fold` and `tail` — the two order-sensitive terminators — to the same `POST_UNION_RECOGNISERS` allow-list that `range` and `limit` sit in, and then asserts that `union(...).fold()` and `union(...).tail(n)` "translate and match native as multisets". They cannot. The track's own relaxation clause ("as unordered multisets otherwise") does not rescue them: for `fold()` the top-level multiset has exactly one member and that member is a `List`, whose equality is order-sensitive; for `tail(n)` the selection is positional, so a differently ordered concatenation selects different elements.

**Evidence**: Measured at `54cc0a708f` on an eight-vertex `knows` chain. `g.V().union(__.out(), __.in())` returns fourteen rows on both arms with the same multiset but a different order. Append the positional suffix and the multisets diverge:

| Shape | translator on | translator off |
|---|---|---|
| `union(out(), in()).limit(3)` | `#22:2, #23:0, #22:1` | `#22:2, #23:0, #25:0` |
| `union(out(), in()).range(2,5)` | `#22:1, #25:0, #22:0` | `#25:0, #22:1, #22:2` |
| `union(out(), in()).count()` | `14` | `14` |

The order-free suffix agrees; both positional ones return an element the other never produced. The same probe shows single-plan arrival order matching native byte-for-byte across eight shapes including `g.V().limit(3)` and `g.V().range(2,5)` (AT8), so the divergence is specific to the multi-plan concatenation, not general.

**Proposed fix**: Three parts, and the third is not optional. (1) Split the allow-list addition: add `unfold` and `reverse` (per-payload, order-insensitive) to `POST_UNION_RECOGNISERS` and decline `fold` and `tail` after a union until multi-plan arrival order is pinned — this is a one-line difference in item 4 and removes the unmeetable criterion. (2) Rewrite the `track-11.md:84` criterion so the union-suffix clause covers only the terminators actually allow-listed. (3) The pre-existing `range` / `limit` divergence is a silent wrong answer on a recognised shape under a default-on kill switch, which Track 9's item-4 rule classes as needing a fix exit, a decline exit, or a recorded waiver. Track 11 is the last track; nothing after it inherits this. Give it a destination in `## Decision Log` — most cheaply, decline positional suffixes after a union — or escalate it.

### A2 [blocker]
**Certificate**: AT1 — assumption test, BREAKS
**Target**: Assumption — `track-11.md:70` item 7, "The harness runs in-track, not only compiles… Point an in-track JUnit test at that fixture pattern and drive one recognised shape through the harness's own entry point twice, kill-switch on and off, asserting the boundary step is installed in the first and absent in the second"
**Challenge**: The harness's own entry point cannot carry a Gremlin traversal, so driving it proves nothing about the translator. `LdbcBenchmarkState.executeSql(String sql, Object...)` is the single shared entry point all twenty `@Benchmark` methods wrap, and it takes a query string, routing through `YTDBGraphTraversalSource.yql(...)` — which compiles to one `CallStep` against the `yql` service and reaches the SQL MATCH planner, never `GremlinToMatchStrategy`. Under that entry point the boundary step is absent on **both** arms of the kill-switch A/B, so the assertion item 7 specifies fails on the on-arm for a reason unrelated to the kill switch, and the "harness runs in-track" claim is satisfied by a test that measures nothing.

**Evidence**: `jmh-ldbc/src/main/java/.../LdbcBenchmarkState.java:234` is the entry point; `LdbcQueryCorrectnessTest` builds its 29-vertex / 70-edge `DatabaseType.MEMORY` fixture correctly and cheaply (~4 s) but issues every query and every fixture write through `ytg.yql(...)`. A sweep of `jmh-ldbc/src` finds zero Gremlin traversal chains — no `.V(`, no step chaining, only three `YTDBGraphTraversalSource` casts. `LdbcBenchmarkState.setup()` is doubly gated besides: it throws `IllegalStateException` when the dataset directories are absent (`:256-266`) and `ParameterCurator.curate` throws again unless `curated-params-v3.json` is present (`ParameterCurator.java:193-206`), so the harness's own `@Setup` cannot run against the in-memory fixture either.

**Proposed fix**: The reusable part of `LdbcQueryCorrectnessTest` is the fixture-building pattern, not its queries and not the entry point. Item 7 must say so and must specify the new Gremlin-accepting entry point it is adding — the on/off assertion then targets that new code, which is honest but is a different claim from "the harness's own entry point". Restate the acceptance criterion at `track-11.md:88` accordingly, and name which recognised shape the in-track test drives (a Phase 1 shape over the LDBC schema; see A7 on why it cannot be an LDBC query).

### A3 [should-fix]
**Certificate**: AT2 — assumption test, BREAKS on one of four cases
**Target**: Assumption — item 5's decline list and the acceptance criterion at `track-11.md:83`, "`g.V().and(__.out().fold())` and `g.V().where(__.out().tail(1))` return native's multiset. Neither silently becomes an existence filter"
**Challenge**: The `where(__.out().tail(1))` half cannot detect the bug it exists to detect. Measured natively, it returns exactly the rows `g.V().where(__.out())` returns — `tail(1)` inside a filter traversal is a per-invocation barrier over a stream that is non-empty exactly when `out()` is non-empty, so it degenerates to an existence test. If the `tail` recogniser wrongly swallows in a combinator child and the translated shape becomes a bare existence filter, the answer is still right and the test still passes. The criterion's own words — "neither silently becomes an existence filter" — describe an outcome indistinguishable from success on this shape.

**Evidence**: On a three-vertex chain, native `g.V().where(__.out())` and native `g.V().where(__.out().tail(1))` both return `{#22:0, #23:0}`. The companion case discriminates properly: native `g.V().and(__.out())` returns two vertices and native `g.V().and(__.out().fold())` returns all three, because a dry upstream still folds to one empty list, so a swallow shows up as 3-vs-2. Both controls translate today (boundary step count 1), which is what makes the `and` case non-vacuous — a point worth recording, since the whole decline list would be vacuous if the un-terminated controls declined for their own reasons.

**Proposed fix**: Replace the `where(__.out().tail(1))` case with `g.V().where(__.out().fold())` — native returns every vertex, a swallow returns only the ones with an out-edge, and the shapes stay one `where` and one `and`. Keep `where(__.out().tail(1))` if it is wanted for coverage, but not as the criterion's witness. Add the direct unit assertion the black-box test cannot make: `SubTraversalPredicateAdapter.supportsListShaping()` is `false`, and each of the four recognisers declines when it reads `false`.

### A4 [should-fix]
**Certificate**: V2 — violation scenario, CONSTRUCTIBLE
**Target**: Acceptance criterion `track-11.md:82` — "witnessed by the explicit non-empty-`listShapingOps` gate rather than inferred from op reference identity"
**Challenge**: The criterion asks a black-box test to witness which of two gates fired, and the track's own guidance makes the wrong one fire first. `UnionStepRecogniser` declines when `!agreedShaping.equals(childResult.shaping())`, and `ResultShaping` is a record whose `equals` compares `listShapingOps` element-wise. The track's `## Context and Orientation` establishes that a stateful `fold` or `tail` op cannot be a shared singleton, because `AbstractStep.clone()` copies `shaping` by reference and two clones would race on one buffer. An implementer who follows that constraint allocates a fresh op per recognition; two fresh ops without a value `equals` compare unequal; the agreement gate declines `union(__.out().fold(), __.in().fold())` before the new gate is ever consulted. Delete the new gate entirely and the acceptance test still passes.

**Evidence**: `UnionStepRecogniser.java:106-108` is the agreement comparison; `walkFork` (`UnionForkHostImpl.java:74-88`) builds a fresh top-level traversal per child and runs `GremlinStepWalker.production().walk(forked)`, so a union child gets a full `WalkerContext` with `supportsListShaping()` true and its `fold` really is claimed — the gate is genuinely needed, which is what makes the untestability matter rather than being moot. Measured natively, `g.V().union(__.out().fold(), __.in().fold())` returns one list per child (`[[v,v],[v,v]]`) while a translated fold over the concatenation would return one list, so DR-T3's divergence is real (AT10).

**Proposed fix**: Make the witness white-box. Assert at unit level that `UnionStepRecogniser` declines when a child result carries a non-empty `listShapingOps`, using two `ResultShaping` values whose ops are `equals`-equal (a record singleton, or a small test op with value equality) so the agreement gate provably cannot be the one that fired. Keep the end-to-end shape as a result check. Alternatively, state in `## Decision Log` that the ops carry value equality by design and the agreement gate is therefore not a second line of defence — but then say so, rather than asserting a witness the test does not provide.

### A5 [should-fix]
**Certificate**: AT3 — assumption test, BREAKS
**Target**: DR-T2 — "copying `appendPostConcatOp` throws `UnsupportedOperationException` out of `TraversalStrategy.apply()`, breaking the all-or-nothing contract loudly"
**Challenge**: That consequence does not occur at HEAD. `GremlinToMatchStrategy.apply` wraps the whole body in a throw-safety net: `ReservedAliasException` is re-thrown, and every other `RuntimeException` is routed to `declineOnThrow`, which leaves the native step list untouched. An `UnsupportedOperationException` raised by a combinator child's append is therefore swallowed and degrades to exactly the native decline that `supportsListShaping()` produces. The chosen design survives, but not for the stated reason, and the stated reason is the kind an implementer verifies: a test asserting the exception escapes `apply()` will fail no matter how the seam is written.

**Evidence**: `RecognitionContext.java:286` is the throwing default (`"post-concat ops are top-level only"`), inherited by `SubTraversalPredicateAdapter`, which overrides neither it nor `postConcatOps()`. `GremlinToMatchStrategy.java:215-232` is the net; its own comment states the intent — "any recognizer/planner RuntimeException here must degrade to a decline… never abort compilation" — and narrows the catch to `RuntimeException` so `Error` and `AssertionError` still propagate.

**Proposed fix**: Restate DR-T2's rejection of the throw template on grounds that survive contact with the net: an exception on a path that runs on every Gremlin compilation is a cost, and a decline that arrives through the catch-all is undiagnosable at the point of decision, where `supportsListShaping()` names it. The decision does not change; the sentence supporting it does.

### A6 [should-fix]
**Certificate**: AT4 — assumption test, BREAKS
**Target**: Assumption — item 6 and the acceptance criterion at `track-11.md:87`, that reading "the artifact Track 9 publishes after its last fix" is sufficient
**Challenge**: Track 9 assigns Track 11 a reciprocal obligation that Track 11 does not carry, and Track 11's own step order can invalidate its own measurement. Track 9's `## Decision Log` states it outright: "Track 11 inherits the reciprocal obligation: before reading the baseline it confirms the recorded SHA is an ancestor of its own base with no intervening `core` commit, and re-takes the measurement otherwise." Item 6 says only which artifact to read. Worse, item 6 is item 6 of 7: item 7 lands after it, and Phase C review fixes land after that. Track 9 deliberately ordered its own publish step last and docs-only "so the commit cannot invalidate its own measurement"; Track 11 inverts that order and states no re-trigger rule, so the track can close having measured no regression against a tree it then changed.

**Evidence**: `plan/track-9.md` § Decision Log, the recompute rule and the trigger-list rule (`git log <sha>..HEAD -- core embedded` non-empty as the qualifying test, Phase C fixes explicitly included); `plan/track-9.md` step 6 is docs-only for exactly this reason. Track 10's `a0b3e96e15` → `5db5b41a3d` → `7c77a4544f` sequence is the recorded worked example of the failure. `track-11.md:69` and `:87` carry neither the ancestry check nor the re-trigger rule.

**Proposed fix**: Add both to item 6, in the words Track 9 uses so the two documents cannot drift: confirm the baseline SHA is an ancestor of Track 11's base with no intervening `core` or `embedded` commit before reading it, and re-run the suite when any qualifying commit lands between the run and track completion. Then either move the Cucumber re-run after item 7, or state that item 7 touches no `core` or `embedded` file so the re-trigger cannot fire on it — which A2's fix may make untrue, since a new Gremlin entry point has to live somewhere.

### A7 [should-fix]
**Certificate**: C1 — scope challenge, WEAK
**Target**: Scope and sizing — the plan's Track 11 `**Scope:** ~14–20 files` line, and `## Interfaces and Dependencies` "the mirrored Gremlin JMH benchmark classes"
**Challenge**: "Mirrored" has no referent, and the count it implies does not fit. There is no Gremlin JMH benchmark anywhere in the repository, so nothing is being mirrored in the sense of adapting an existing class. Mirroring the LDBC shape instead means five `Ldbc*BenchmarkBase` classes plus ten concrete single/multi-thread subclasses — fifteen files before the harness, the in-track test, or a single recogniser — against a scope line of fourteen to twenty for the whole track. And the LDBC query set cannot be mirrored in content either: the twenty queries are SQL MATCH text with `LET` and correlated subqueries, none of which are Phase 1 translator-recognised Gremlin shapes, so a Gremlin mirror measures different queries against the same schema, which is a different benchmark rather than an A/B against the existing numbers.

**Evidence**: 21 files in the repo carry `@Benchmark`; the two plausible candidates in `core/src/test` (`VertexTraversalBenchmark`, `MatchPlanCacheBenchmark`) use the record API and a SQL `MATCH` string respectively, with no Gremlin traversal in either. `jmh-ldbc` holds five base classes and ten concrete benchmark classes. `jmh-ldbc/pom.xml` depends on `youtrackdb-core` at compile scope with JUnit 4 for tests and no `core` test-jar, so `GraphBaseTest` and the existing boundary-step assertion idioms are not available there. The challenge is graded WEAK because the fix is a specification change rather than a split: the JMH half is genuinely small once "mirrored" is dropped.

**Proposed fix**: Say what the benchmarks measure. Name the two or three Phase 1 recognised shapes over the LDBC schema that the Gremlin benchmark classes cover, state the class count that implies (one base plus one or two concrete classes is enough for an on/off axis), and drop the word "mirrored". Update the plan's Scope line if the count moves. If the JMH half still looks unbounded after that, it shares no file with items 1–6 and splits on exactly the boundary DR-S1 used for Track 9.

### A8 [suggestion]
**Certificate**: AT5 — assumption test, FRAGILE
**Target**: Item 3's deferred `getLimit()` GValue decision and item 5's "placeholder-form `tail`" test
**Challenge**: The placeholder form is not reachable through the API the rest of the track's tests use, and the track leaves the decision that depends on it to the implementer. Measured, `graph.traversal().V().tail(1)` compiles to the concrete `TailGlobalStep`, never `TailGlobalStepPlaceholder` — the placeholder needs a GValue-parameterised construction path (GremlinLang or bytecode) that the track names nowhere. So item 5's placeholder test has no stated way to build its subject, and the pinning side effect that motivates the whole discussion is confined to a path no in-track test currently reaches. Separately, item 3 says to "decide the `getLimit()` GValue question deliberately… or match the Track 6 precedent and record why", which defers a correctness-adjacent choice to the implementer on the last track of a branch whose switch defaults on.

**Evidence**: Probe output for `g.V().tail(1)` and `g.V().tail(0)` shows `TailGlobalStep [limit=1]` and `[limit=0]` pre- and post-strategy on both arms. `TailGlobalStepContract` (fork jar) declares both `getLimit()` and the default `getLimitAsGValue()`, and `CONCRETE_STEPS` lists both classes, so the registration source is right; only the test's construction path is missing. The plan's no-mutation-on-decline invariant is scoped to `WalkerContext`, so the pinning is not literally a violation, as the track already says.

**Proposed fix**: Name the construction path for the placeholder form in item 5, or drop that test and say why. Make the `getLimitAsGValue()` choice in the track file rather than leaving it open — the clean branch (read `getLimitAsGValue()`, decline on `isVariable()` before touching `getLimit()`) costs one line and removes a side effect on a decline path, and the Track 6 precedent is a reason to allow the other branch, not a reason to leave the choice unmade.

### A9 [suggestion]
**Certificate**: AT6 — assumption test, BREAKS
**Target**: Workflow — D14's model pin for this spawn
**Challenge**: D14 pins the adversarial spawn by `design_gate`: `yes` → Fable 5, `no` → Opus 4.x, read ledger-first. This branch has a `design.md` and its ledger records `tier=full`, so `design_gate` resolves to `yes` and the pin is Fable 5; Track 9's spawn was recorded that way. This pass ran on Opus. The deviation is recorded here as instructed. The second half is the reason it happened: `_workflow/phase-ledger.md` carries `tier`, `phase`, `track`, `substate` and `gate` fields but no `design_gate` field at all, so D14's ledger-first read has no value to find and D14 states no fallback.

**Evidence**: `.claude/workflow/track-review.md:844-846` is the pin; `_workflow/phase-ledger.md` (38 lines) has no `design_gate` occurrence; `_workflow/design.md` exists and `planning.md:91` ties `design_gate=no` to a plan authored without a design.

**Proposed fix**: Record the deviation in Track 11's `## Outcomes & Retrospective` alongside the panel verdict, as Track 9 recorded its pin. If the gate-verification pass is re-spawned, pin it to Fable 5 so the iteration is model-consistent. Separately, D14 needs a fallback sentence for a ledger with no `design_gate` field — "a `design.md` under `_workflow/` means `yes`" would resolve every branch on disk today.

## Evidence base

#### V1 Violation scenario: translator-on and translator-off produce equal result multisets for every RECOGNIZED shape
- **Invariant claim**: For any shape the translator recognises, the on-arm and off-arm result multisets are equal (`implementation-plan.md` § Invariants).
- **Violation construction**:
  1. Start state: eight `Person` vertices in a `knows` chain (Hank→Gina→Fay→Eve→Dave→Carol→Bob→Alice), committed, translator kill-switch at its default.
  2. Action: run `g.V().union(__.out(), __.in()).range(2, 5)` with the switch on, then off, draining both. `RangeGlobalStepRecogniser.INSTANCE` is in `GremlinStepWalker.POST_UNION_RECOGNISERS` (`:206-210`), so the shape is RECOGNIZED — measured boundary-step count 1 on the on-arm.
  3. Intermediate state: `g.V().union(__.out(), __.in())` returns fourteen rows on both arms. Counting by RID, the multisets are identical; the emission orders are not (on: `#22:2, #23:0, #22:1, #25:0, #22:0, #21:1, #21:0, #25:0, #22:2, #22:1, #21:1, #20:0, #21:0, #22:0`; off: `#22:2, #23:0, #25:0, #22:1, #22:2, #25:0, #22:1, #22:0, #21:1, #21:1, #20:0, #21:0, #21:0, #22:0`).
  4. Violation point: the positional suffix reads positions 2–4 of two different orders. On-arm `#22:1, #25:0, #22:0`; off-arm `#25:0, #22:1, #22:2`. `#22:0` appears only on-arm, `#22:2` only off-arm.
  5. Observable consequence: a wrong multiset returned silently on a recognised shape, with `QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED` defaulting true. `limit(3)` reproduces it (`#22:1` vs `#25:0` as the third element); `count()` does not (14 on both), confirming the fault is positional selection over a reordered concatenation and not a cardinality error.
- **Relevance to Track 11**: item 4 adds `fold` and `tail` to the same allow-list. `tail(n)` selects by position, so it inherits the defect directly. `fold()` produces one payload whose value is a `List`, and `List.equals` is order-sensitive, so a one-element multiset comparison is an ordered comparison — the criterion at `track-11.md:84` fails on the fourteen-row shape above even though the underlying multiset agrees.
- **Feasibility**: CONSTRUCTIBLE — measured at `54cc0a708f`, no unusual conditions.

#### V2 Violation scenario: the union-child decline is witnessed by the explicit non-empty-listShapingOps gate
- **Invariant claim**: `track-11.md:82` — the union-child decline is "witnessed by the explicit non-empty-`listShapingOps` gate rather than inferred from op reference identity".
- **Violation construction**:
  1. Start state: item 4 implemented, but the new gate omitted (or written after the agreement comparison rather than before it).
  2. Action: the implementer, following the track's clone-safety analysis, allocates a fresh `ListShapingOp` per recognition rather than sharing a singleton.
  3. Intermediate state: `walkFork` returns two child results whose `shaping().listShapingOps()` hold distinct instances with identity equality.
  4. Violation point: `UnionStepRecogniser.java:106-108`, `!agreedShaping.equals(childResult.shaping())` is true on the second child, and the recogniser declines there.
  5. Observable consequence: `union(__.out().fold(), __.in().fold())` declines, the acceptance test passes, and the criterion's claimed witness never executed. The test's verdict is the same with and without the thing it certifies.
- **Feasibility**: CONSTRUCTIBLE. The child path really does reach a full `WalkerContext` — `UnionForkHostImpl.walkFork` (`:74-88`) builds a fresh traversal from prefix plus child suffix and calls `GremlinStepWalker.production().walk(forked)`, so `supportsListShaping()` is true for union children and the fold is claimed. The gate is needed; it is only unobservable.

#### V3 Violation scenario: a combinator child can bypass the item-1 seam and reach the boundary
- **Invariant claim**: A combinator child carrying a list-shaping op declines rather than silently becoming an existence filter.
- **Violation construction**: attempted for `g.V().and(__.out().fold())`. The child sub-walk runs against `SubTraversalPredicateAdapter`, which must implement `appendListShapingOp` because `RecognitionContext` declares it. If the adapter delegates the append to its parent instead of declining, the op lands on the top-level context and the outer boundary folds; measured natively the shape returns all three vertices, while an existence filter returns two, so the divergence is observable.
- **Feasibility**: INFEASIBLE as a silent failure, provided the acceptance test asserts the result rather than only the decline. The 3-vs-2 gap is large enough that a delegation bug fails the test. Recorded because it is the one place the criterion at `track-11.md:83` does its job — see AT2 for the companion case where it does not.

#### AT1 Assumption test: the JMH harness can be exercised in-track through its own entry point over LdbcQueryCorrectnessTest's fixture
- **Claim**: `track-11.md:70` — point an in-track JUnit test at that fixture pattern, drive one recognised shape through the harness's own entry point twice with the kill-switch on and off, and assert the boundary step is installed then absent.
- **Stress scenario**: write that test and ask what it observes.
- **Code evidence**: `LdbcBenchmarkState.java:234`, `List<Map<String,Object>> executeSql(String sql, Object... keyValues)`, is the only shared entry point and all twenty `@Benchmark` methods wrap it; it routes through `YTDBGraphTraversalSource.yql(...)`, which `YTDBGraphTraversalSourceDSL.java:138-146` compiles into one `CallStep` against the `yql` service. `LdbcQueryCorrectnessTest` builds a 29-vertex / 70-edge `DatabaseType.MEMORY` graph in ~4 s and asserts against it through the same `yql` helper; a sweep of `jmh-ldbc/src` finds no Gremlin traversal chain. `LdbcBenchmarkState.setup()` throws without the dataset (`:256-266`) and `ParameterCurator.curate` throws without curated parameters (`ParameterCurator.java:193-206`).
- **Verdict**: BREAKS. The fixture is real and cheap and the module runs in an ordinary build (unconditional reactor member, no surefire skip, no `@Ignore`, no assumption guard). What breaks is the entry point: driving it engages the SQL MATCH planner, so the boundary step is absent on both arms and the assertion is vacuously wrong rather than merely weak.

#### AT2 Assumption test: each item-5 decline case declines for the reason item 5 claims
- **Claim**: the four decline cases — `fold(seed, operator)`, `union(__.out().fold(), __.in().fold())`, `g.V().and(__.out().fold())`, `g.V().where(__.out().tail(1))` — are "each a silent wrong answer if missed".
- **Stress scenario**: build each shape, dump what TinkerPop's strategy chain hands the translator, and compare the native answer against the answer the anticipated bug would produce.
- **Code evidence**: measured at `54cc0a708f` on a three-vertex chain.
  - `g.V().values("age").fold(0, Operator.sum)` arrives as `FoldStep [isListFold=false]`; the list form arrives as `FoldStep [isListFold=true]`. No strategy rewrites the seeded reduce into `SumGlobalStep`. Native returns `120`, the list form returns `[50, 30, 40]` — a mistranslation is loud. HOLDS.
  - `union(__.out().fold(), __.in().fold())` returns one list per child natively (`[[#23:0, #18:0], [#23:0, #22:0]]`) against one list over the concatenation if mistranslated. HOLDS.
  - `g.V().and(__.out())` translates (boundary 1) and returns two of three vertices; `g.V().and(__.out().fold())` returns all three natively. A swallow shows as 3-vs-2. HOLDS.
  - `g.V().where(__.out())` translates (boundary 1) and returns `{#22:0, #23:0}`; `g.V().where(__.out().tail(1))` returns the same `{#22:0, #23:0}`. The swallow bug's answer equals the correct answer.
- **Verdict**: BREAKS on the fourth case, HOLDS on the other three. Both un-terminated controls translate today, so the list is not vacuous as a whole — which is worth stating, because the obvious way for it to be vacuous (the controls declining for their own reasons) was checked and ruled out.

#### AT3 Assumption test: a throwing append escapes TraversalStrategy.apply()
- **Claim**: DR-T2 — "copying `appendPostConcatOp` throws `UnsupportedOperationException` out of `TraversalStrategy.apply()`, breaking the all-or-nothing contract loudly".
- **Stress scenario**: trace what happens when a recogniser raises `UnsupportedOperationException` inside a sub-walk.
- **Code evidence**: `RecognitionContext.java:286` is the throwing default and `SubTraversalPredicateAdapter` does not override it. `GremlinToMatchStrategy.java:215-232`: `apply` calls `applyOrDecline` inside a try, re-throws `ReservedAliasException`, and routes every other `RuntimeException` to `declineOnThrow`. Its comment states the intent and notes that the catch is narrowed so `Error` and `AssertionError` still propagate.
- **Verdict**: BREAKS. Nothing escapes `apply()` and nothing is loud; the throw degrades to the same native decline the chosen design produces. The design survives on cost and diagnosability, not on the stated mechanism.

#### AT4 Assumption test: item 6 can read Track 9's baseline as published
- **Claim**: `track-11.md:69` — the baseline is "the artifact Track 9 publishes after its **last** fix", and reading it is sufficient.
- **Stress scenario**: the baseline SHA is not an ancestor of Track 11's base, or `core` moves between item 6's run and track completion.
- **Code evidence**: `plan/track-9.md` § Decision Log states the reciprocal obligation on Track 11 verbatim and defines the qualifying-commit test as `git log <sha>..HEAD -- core embedded` non-empty, with Phase C fixes included and Track 10's `a0b3e96e15` → `5db5b41a3d` → `7c77a4544f` as the recorded failure. Track 9's step 6 is docs-only so it cannot invalidate its own measurement. `track-11.md` items 6 and 7 put the code-bearing item after the measuring one and state no re-trigger rule.
- **Verdict**: BREAKS. The obligation was handed over explicitly and was not picked up, and the step order makes the omission bite rather than being theoretical.

#### AT5 Assumption test: the TailGlobalStepPlaceholder form is reachable in an in-track unit test
- **Claim**: item 5 tests "placeholder-form `tail`"; item 3 registers from `TailGlobalStepContract.CONCRETE_STEPS`.
- **Stress scenario**: build `tail(n)` the way every other test in this package builds traversals and see which class arrives.
- **Code evidence**: probe output for `g.V().tail(1)` and `g.V().tail(0)` shows `TailGlobalStep [limit=1]` / `[limit=0]` pre- and post-strategy on both arms — the concrete class, never the placeholder. `TailGlobalStepContract` (fork jar) declares `getLimit()`, a default `getLimitAsGValue()`, and `CONCRETE_STEPS` listing both classes, so registration from the contract is correct and covers a placeholder if one ever arrives.
- **Verdict**: FRAGILE. The registration is right; the test's construction path is unnamed, and the GValue side effect that motivates item 3's open question lives only on that unnamed path.

#### AT6 Assumption test: the panel's model pin is unconstrained for Track 11
- **Claim**: implicit in the spawn — the orchestrator could not confirm `design_gate`, so the pass ran on Opus.
- **Stress scenario**: resolve `design_gate` from the artifacts on disk.
- **Code evidence**: `.claude/workflow/track-review.md:844-846` pins `design_gate=yes` → Fable 5. `_workflow/design.md` exists; `_workflow/phase-ledger.md` records `tier=full` on every line and no `design_gate` field anywhere; `planning.md:91` ties `design_gate=no` to a plan authored without a design. Track 9's `## Outcomes & Retrospective` records its adversarial pass as "Model pinned to Fable 5 per D14 (`design_gate=yes`)".
- **Verdict**: BREAKS. `design_gate` is `yes` on the evidence available, D14 requires Fable 5, and this pass deviated. The ledger's missing field is the proximate cause and D14 provides no fallback for it.

#### AT7 Assumption test: FoldStep's two forms are distinguishable at the registry
- **Claim**: `track-11.md:35` — one registry entry claims both `FoldStep` forms, distinguished by `isListFold()`, and mapping both to a list drain would silently turn a summed scalar into a list of ages.
- **Stress scenario**: check that TinkerPop's strategy chain does not rewrite the seeded reduce into a step the translator already handles differently.
- **Code evidence**: `javap` on the fork jar shows `FoldStep extends ReducingBarrierStep` with both constructors and `isListFold()`. Measured, `g.V().values("age").fold(0, Operator.sum)` arrives as `FoldStep [isListFold=false]` unchanged through the whole chain, and `g.V().values("age").fold()` as `FoldStep [isListFold=true]`. Native answers are `120` and `[50, 30, 40]`.
- **Verdict**: HOLDS. The decline is real, the discriminator is present, and the wrong answer it prevents is loud.

#### AT8 Assumption test: translated arrival order matches native, so positional terminators are comparable
- **Claim**: `track-11.md:86` — the multiset standard and positional terminators "agree only where translated arrival order matches native traversal order, and nothing pins that".
- **Stress scenario**: measure arrival order directly on the shapes the translator recognises today, rather than inferring from `OrderTest`'s deferred failure.
- **Code evidence**: eight vertices, translator on versus off, ordered comparison of the full drained list. `g.V()`, `g.V().out()`, `g.V().values("name")`, `g.V().out().values("name")`, `g.V().limit(3)`, `g.V().range(2,5)`, `g.V().order().by("name").limit(3)` and `g.V().out().limit(3)` all match element-for-element, including element identity for the two positional shapes.
- **Verdict**: HOLDS for single-plan shapes, and the track is more pessimistic than the code. The caveat is worth keeping (nothing guarantees it), but the "validated as unordered multisets otherwise" fallback is not needed on this path, and the real exposure is the multi-plan path in V1, which `track-11.md:86` does not mention.

#### AT9 Assumption test: DR-T2's native semantics for a combinator child carrying a fold
- **Claim**: DR-T2 — swallowing `setResultShaping` "turns `g.V().and(__.out().fold())` into an existence filter (native is always true, because a dry upstream still emits one empty list) and rows silently disappear".
- **Stress scenario**: measure both the correct and the buggy answer on a graph with a vertex that has no out-edge.
- **Code evidence**: three-vertex chain. Native `g.V().and(__.out().fold())` returns all three vertices; native `g.V().and(__.out())` returns two. The leaf vertex is retained by the fold form and dropped by the existence form.
- **Verdict**: HOLDS, exactly as stated, including the direction of the error.

#### AT10 Assumption test: DR-T3's per-child divergence for union children
- **Claim**: DR-T3 — `union(__.out().fold(), __.in().fold())` must decline because native produces one list per child while translation would produce one list over the concatenation; only `fold` and `tail` diverge, `unfold` and `reverse` coincide.
- **Stress scenario**: measure the native answer for both divergent terminators inside union children.
- **Code evidence**: native `g.V().union(__.out().fold(), __.in().fold())` returns `[[#23:0, #18:0], [#23:0, #22:0]]` — two lists. Native `g.V().union(__.out().tail(1), __.in().tail(1))` returns two elements, one per child, where a tail over the four-element concatenation would return one.
- **Verdict**: HOLDS. The divergence is real for both, and the blanket gate over all four is a coverage cost with no correctness risk, as DR-T3 says.

#### C1 Challenge: Scope — item 7 belongs in Track 11 as specified
- **Chosen approach**: item 7 adds "the mirrored Gremlin JMH benchmark classes, the on/off harness, and its in-track `jmh-ldbc/src/test` execution" inside a track scoped at ~14–20 files.
- **Best rejected alternative**: specify the benchmark set concretely (one base class plus one or two concrete classes over named Phase 1 shapes), or split item 7 out on the same "shares no file" boundary DR-S1 used to split Track 9.
- **Counterargument trace**:
  1. "Mirrored" implies an existing Gremlin benchmark to mirror. There is none: 21 files carry `@Benchmark`, and the two candidates in `core/src/test` use the record API and a SQL `MATCH` string.
  2. Read as mirroring the LDBC structure instead, it means five bases plus ten concrete classes — fifteen files against a whole-track budget of fourteen to twenty.
  3. Read as mirroring the LDBC queries, it is impossible: the twenty queries are SQL MATCH text with `LET` and correlated subqueries, none of them Phase 1 recognised Gremlin shapes.
- **Codebase evidence**: `jmh-ldbc/src/main/java/.../Ldbc{IC,ICSlow,ICUltraSlow,IS,ISUltraFast}BenchmarkBase.java` plus ten `LdbcSingleThread*` / `LdbcMultiThread*` classes; `jmh-ldbc/pom.xml` depends on `youtrackdb-core` at compile scope with JUnit 4 and no `core` test-jar, so the boundary-assertion idioms in `core/src/test` are not reusable there.
- **Survival test**: WEAK. The track survives as a unit — the JMH half is small once the word "mirrored" is dropped — but the specification does not survive, and an implementer reading "mirrored" literally builds fifteen files.
