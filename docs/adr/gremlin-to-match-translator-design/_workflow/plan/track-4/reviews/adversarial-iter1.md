<!-- MANIFEST
findings: 7   severity: {blocker: 0, should-fix: 6, suggestion: 1}
index:
  - {id: A1, sev: should-fix, loc: _workflow/plan/track-4.md:88, anchor: "### A1 ", cert: C1, basis: "realistic footprint ~29-38 files vs ~20 claimed; over the ~25 split bound with no written justification; 4 D5/NOT integration files unlisted"}
  - {id: A2, sev: should-fix, loc: GremlinStepWalker.java:90, anchor: "### A2 ", cert: C2, basis: "NotFilterStepRecogniser and NotStepRecogniser collide on the single final NotStep.class registry key; Map.of duplicate key = Error the safety net does not catch"}
  - {id: A3, sev: should-fix, loc: _workflow/plan/track-4.md:54, anchor: "### A3 ", cert: C3, basis: "track names two different D5 cache keys; the pre-walk traversal-fingerprint reading serves a 'k = ?' plan to eq(null) -> silent wrong result"}
  - {id: A4, sev: should-fix, loc: WalkerContext.java:156, anchor: "### A4 ", cert: C4, basis: "sub-context contract understated: per-child alias sequences collide ($g2m_anon_0 twice) and hop children re-pin the outer boundary -> silent wrong multiset"}
  - {id: A5, sev: should-fix, loc: MatchPatternBuilder.java:255, anchor: "### A5 ", cert: C6, basis: "no shipped builder API produces the detached SQLMatchExpression manageNotPatterns consumes; NOT edge-bearing branch needs an unlisted builder/assembler capability"}
  - {id: A6, sev: should-fix, loc: MatchExecutionPlanner.java:767, anchor: "### A6 ", cert: C5, basis: "second NOT decline condition misreads the planner precondition (origin inline filter, not aliasFilters) and declines g.V().has(..).not(out(..)), the dominant NOT shape"}
  - {id: A7, sev: suggestion, loc: RecognitionContext.java:24, anchor: "### A7 ", cert: C7, basis: "plan's no-mutation-on-decline invariant is disavowed by HEAD's shipped contract; step-6 'canonical pin' needs rescoping"}
evidence_base: {section: "## Evidence base", certs: 9, matches: 2}
cert_index:
  - {id: C1, verdict: NO, anchor: "#### C1 "}
  - {id: C2, verdict: WEAK, anchor: "#### C2 "}
  - {id: C3, verdict: CONSTRUCTIBLE, anchor: "#### C3 "}
  - {id: C4, verdict: CONSTRUCTIBLE, anchor: "#### C4 "}
  - {id: C5, verdict: WEAK, anchor: "#### C5 "}
  - {id: C6, verdict: BREAKS, anchor: "#### C6 "}
  - {id: C7, verdict: BREAKS, anchor: "#### C7 "}
  - {id: C8, verdict: HOLDS, anchor: "#### C8 "}
  - {id: C9, verdict: HOLDS, anchor: "#### C9 "}
flags: [CONTRACT_OK]
-->

Adversarial review, iteration 1 — Track 4: Filtering (predicates + logical filters). Narrowed to track realization (D9): scope/sizing, cross-track-episode reality, invariant violation. Facts pinned by the Phase A technical and risk reviews were verified as build-on premises, not re-litigated. All symbol claims verified by direct Read of HEAD source plus grep on unique literals and `javap` on the TinkerPop fork jar; mcp-steroid was reachable but no finding hinges on a polymorphic-caller sweep, so PSI find-usages was not load-bearing here (the one caller claim, `classEquals`, is a unique literal — grep-clean).

## Findings

### A1 [should-fix]
**Certificate**: C1
**Target**: Track sizing (two-sided bound, `planning.md` §Track descriptions; scope indicator "~20 files")
**Challenge**: The realized footprint is ~29–38 files, not ~20 — past the ~20-25 split bound with no written justification in the track file (Track 1 carries one for its under-floor size; Track 4 carries none for its overrun).
**Evidence**: The `## Interfaces and Dependencies` list alone names 19 source files (12 new + 7 modified) before any test file. Four integration files the Plan of Work implies are absent from the list: `GremlinToMatchStrategy` (the D5 cache replaces the documented `useCache=false` eager build — GremlinToMatchStrategy.java:132-136, plan built at :390), `GremlinToMatchTranslator` (the `TranslationResult` record at :66 carries no bound-parameter values, and a shared cached plan needs the per-walk values delivered to the boundary), `YTDBMatchPlanStep` (the per-execution `BasicCommandContext` at :420 is where `ctx.setInputParameters` lands), and `SharedContext` (both existing plan caches are `SharedContext` fields with listener wiring — SharedContext.java:43-44, 87-94; `GremlinPlanCache` "reusing the YQL invalidation hook" follows the same pattern). A5 adds `MatchPatternBuilder`. On tests, Track 3's shipped convention is one unit-test file per recogniser (`VertexStepRecogniserTest`, `VertexHopRecogniserTest`, `EdgeHopRecogniserTest`); 8 new recognisers plus the named equivalence/NULL/collection/logical/plan-cache suites plus the R2/R5/R6 test requirements put the test count at ~10-14, not the ~5 a 20-file total implies.
**Proposed fix**: Decide split-or-justify at decomposition. Natural seam: Plan of Work steps 1–4 (predicate surface — full adapter, `HasStepRecogniser`, presence forms, D-TEXT-OPS) versus steps 5–7 (logical filters + sub-walker + D5 cache); each side lands ~14-18 files and is independently mergeable (steps 1–4 have no dependency on 5–7). If the track stays whole, write the out-of-bounds justification into the track file, correct the scope indicator, and add the unlisted files to In scope.

### A2 [should-fix]
**Certificate**: C2
**Target**: D9 (one recogniser per exact runtime class) as realized by the In-scope file list
**Challenge**: `NotFilterStepRecogniser` and `NotStepRecogniser` are listed as two sibling recognisers, but `hasNot(key)` (desugared to `NotStep(__.values(key))`) and logical `not(traversal)` arrive as the same runtime class — one registry key for two planned owners.
**Evidence**: The fork's `NotStep` is `final` (`javap`, gremlin-core-3.8.1-fccfc5a jar), so exact-class dispatch cannot separate the two forms. The production registry is a `Map.of` (GremlinStepWalker.java:90-93); a duplicate key throws `IllegalArgumentException` during class initialization, surfacing as `ExceptionInInitializerError` — an `Error` the strategy's `RuntimeException`-only net deliberately does not catch (GremlinToMatchStrategy.java:105-110), so every Gremlin compilation on the server would fail, not decline. Registered singly instead, ordering still matters: `PropertiesStep` (the `values(key)` child) has no recogniser until Track 5, so a generic sub-walk of `hasNot(key)`'s child declines — the presence-form branch must run before the sub-walk. The same values-child-first rule applies inside `TraversalFilterStepRecogniser` for `has(key)`.
**Proposed fix**: Pin the routing in the track file, mirroring Track 3's `VertexStepRecogniser` → `VertexHopRecogniser`/`EdgeHopRecogniser` precedent: one recogniser registered under `NotStep.class` that branches values-child (presence form) first, then `hasEdgeHops`; make `NotFilterStepRecogniser` an unregistered delegate or fold it into `NotStepRecogniser`. Adjust the In-scope list accordingly.

### A3 [should-fix]
**Certificate**: C3
**Target**: D5 (plan cache keyed on a value-independent fingerprint)
**Challenge**: The track names two different keys — "value-independent traversal fingerprint" (Purpose, track-4.md:9) and "value-independent generic-statement fingerprint" (Plan of Work step 7). Under the pre-walk (traversal-shape) reading the cache is unsound, because translation itself is value-dependent.
**Evidence**: The track's own rules fork on value: `eq(null)` renders `IS NULL` (no parameter slot), `eq(v)` renders `field = ?`, and size-1 collection equality declines while sizes 0/≥2 translate (Context and Orientation). A value-blind pre-walk key maps `has(k, eq(null))` and `has(k, eq(5))` to one entry; serving the cached `k = ?` plan for the null query evaluates `k = null` → false (`QueryOperatorEquals.equals` null short-circuit, QueryOperatorEquals.java:71-73) where `IS NULL` semantics are required — a silent empty result. The post-walk generic-statement key is immune (the two shapes render different statements before the key is computed) and is the only reading consistent with D5's "the cache spares only the expensive planner pass".
**Proposed fix**: Pin the key computation point to the post-walk generic statement in both Purpose and step 7. Extend the R6 test list with value-fork cases: `eq(null)` vs `eq(v)` must occupy distinct entries, collection size classes must not collide. State whether `within(...)` value lists bind as one collection parameter or inline per element — both are correct, but the fingerprint (and reuse rate) differs.

### A4 [should-fix]
**Certificate**: C4
**Target**: Plan of Work steps 5–6 — sub-walker ("nested `StepCursor` over the child sub-traversal against the same registry, its recognition context inheriting the parent `boundaryAlias`")
**Challenge**: Inheriting `boundaryAlias` is the only sub-context rule the track states; four other interception rules are load-bearing, and the naive realization — a fresh nested `WalkerContext` per child — produces silent wrong results, not declines.
**Evidence**: (1) Alias minting: `AliasSequence` restarts at 0 per context (WalkerContext.java:156-163, ctor :165-168), so `and(__.out("a"), __.out("b"))` with per-child contexts mints `$g2m_anon_0` twice; in MATCH one alias is one binding, so the pattern silently over-constrains to "both edges reach the *same* vertex" — a wrong multiset the equivalence fixture only catches if a two-children AND test exists. (2) Boundary corruption: a hop child dispatches `VertexHopRecogniser`, whose assembler call "re-pins the boundary / single RETURN column to the target" (VertexHopRecogniser.java:80-84; `setSingleReturnColumn` clears and replaces the projection, WalkerContext.java:253-263) — on a delegating context this moves the outer traversal's result column onto the AND-child's target. (3) OR children: their `putAliasFilter` contributions must be captured as composable `SQLBooleanExpression`s for `MatchWhereBuilder.or`, never committed to `aliasFilters`. (4) NOT edge-bearing children must not touch the positive `patternBuilder` at all (see A5 for where their output goes).
**Proposed fix**: Enumerate the sub-context contract in the track file before decomposition, not during it: alias minting delegates to the parent's sequences; `pinBoundary`/`setSingleReturnColumn` are swallowed; `putAliasFilter` is captured per-combinator; pattern contributions forward only for AND edge-bearing children and are captured for NOT. Add an `and(out("a"), out("b"))`-shaped case to the acceptance list so the alias-collision trap is pinned by test.

### A5 [should-fix]
**Certificate**: C6
**Target**: In scope (modified) — the `notMatchExpressions` wiring ("a new `RecognitionContext` sink, wired into `buildResult`'s `MatchPlanInputs.builder(...)`")
**Challenge**: A sink list is necessary but not sufficient: nothing Tracks 1–3 shipped can produce the detached `SQLMatchExpression` AST that `manageNotPatterns` consumes, so the NOT edge-bearing branch needs a builder/assembler capability the In-scope list does not carry.
**Evidence**: `manageNotPatterns` iterates `SQLMatchExpression.getOrigin()`/`getItems()` (MatchExecutionPlanner.java:750-790); `MatchPlanInputs.notMatchExpressions` and its builder method exist (MatchPlanInputs.java:48, :154). But `MatchPatternBuilder` constructs `SQLMatchExpression` only internally (MatchPatternBuilder.java:153, :204) and its sole public output is the positive-pattern `PatternIR` (`build()`, :255) — there is no API for a detached NOT chain. Precedent: Track 3's Phase-A review hit the same class of gap — "edge filtering needs an edge-as-node builder extension the plan had assumed away" (implementation-plan.md, Track 2 strategy-refresh note) — and it cost a builder extension mid-track.
**Proposed fix**: Name the mechanism in the track file: either a `MatchPatternBuilder`/assembler extension producing a detached `SQLMatchExpression` chain (and add `MatchPatternBuilder` to In scope (modified)), or direct AST assembly inside the NOT recogniser with an explicit exemption from the D6 shared-builder discipline.

### A6 [should-fix]
**Certificate**: C5
**Target**: Plan of Work step 5 — NOT decline rule, second condition ("when that alias would carry a WHERE filter")
**Challenge**: The second decline condition misreads the planner precondition and, as written, declines the dominant NOT usage for no planner reason.
**Evidence**: `manageNotPatterns`'s second throw fires on `exp.getOrigin().getFilter() != null` (MatchExecutionPlanner.java:766-771) — an *inline filter on the NOT expression's own origin item*, which the translator constructs and can always leave empty. It never inspects `aliasFilters` for the origin alias: SQL MATCH runs `MATCH {as:p, where:(age=30)}, NOT {as:p}.out('knows'){as:x}` today, with the positive WHERE carried on the positive pattern. Under the track's wording, `g.V().has("age", 30).not(__.out("knows"))` — a positive-alias WHERE plus NOT, the most common NOT shape — declines whole to native even though the planner handles it.
**Proposed fix**: Decline only when the NOT origin alias is absent from the positive pattern; build the NOT origin as a bare alias reference and drop the second condition. If kept as defense-in-depth, reword it to what the recogniser actually controls — "the NOT expression's origin item carries an inline filter" — so the decomposer does not implement the aliasFilters over-decline.

### A7 [suggestion]
**Certificate**: C7
**Target**: Plan invariant "No-mutation-on-decline" + Plan of Work step 6 ("canonical no-mutation-on-decline pin")
**Challenge**: HEAD's shipped contract explicitly disavows the invariant the step-6 test is meant to canonize.
**Evidence**: RecognitionContext.java:20-24 and WalkerContext.java:22-26: "A recogniser may read and contribute in any order. A DECLINE ... makes the walker discard the whole context ... 'Validate before you mutate' is unnecessary here." Under D3 all-or-nothing, any decline anywhere discards the walk, so per-recogniser purity is neither enforced nor needed at HEAD — the plan's per-recogniser wording is already violated by design.
**Proposed fix**: Reconcile in the track's Decision Log and flag the plan-level invariant for Phase 4: restate it as "a declined walk leaks nothing because the context is discarded whole", and define step 6's `decline_doesNotCommitPartialStateToOuterContext` as pinning the sub-walk *capture* boundary (a declined child leaves the parent's committed state untouched — defense-in-depth if plan-build ever goes lazy), not a general no-mutation rule.

## Evidence base

Certificates grouped by review criterion. Verification tooling: direct Read of HEAD source, grep on unique literals, `javap` against the fork jar (`~/.m2/.../io/youtrackdb/gremlin-core/3.8.1-fccfc5a-SNAPSHOT`). The `classEquals` caller sweep is grep-based on a unique literal; reference-accuracy risk is negligible (no polymorphism — a single concrete method on a final builder class).

### Scope challenges

#### C1 Challenge: track sizing — "~20 files" vs the realized footprint
- **Chosen approach**: one track carrying the full predicate adapter, 8 recognisers, 2 new SQL AST ops + collate transform, logical filters, sub-walker, and the D5 plan cache, sized "~20 files".
- **Best rejected alternative**: split at the steps-1–4 / steps-5–7 seam (predicate surface vs combinators+cache), two independently mergeable PRs.
- **Counterargument trace**:
  1. The Interfaces list names 19 source files before tests (12 new: 8 recognisers, `SubTraversalPredicateAdapter`, sub-context, `SQLEndsWithCondition`, `GremlinPlanCache`; 7 modified: adapter, `SQLMatchesCondition`, `SQLContainsTextCondition`, `MatchWhereBuilder`, `WalkerContext`, `RecognitionContext`, `GremlinStepWalker`).
  2. D5 integration touches four more files the list omits: `GremlinToMatchStrategy` (cache get/put replaces the eager `useCache=false` build documented at GremlinToMatchStrategy.java:132-136 and executed at :390), `GremlinToMatchTranslator` (`TranslationResult`, :66, must carry per-walk parameter values for a shared plan), `YTDBMatchPlanStep` (per-execution isolated `BasicCommandContext` at :420 — the `setInputParameters` site; `CommandContext.setInputParameters` verified at CommandContext.java:106), `SharedContext` (existing caches are fields with schema-listener wiring, SharedContext.java:43-44, 87-94, 263-267; `YqlExecutionPlanCache implements MetadataUpdateListener`, YqlExecutionPlanCache.java:23, invalidation :141-172). A5 adds `MatchPatternBuilder`.
  3. Track 3's test convention is one unit-test file per recogniser (three exist for its three recognisers) plus fixture/dispatch tests — projecting Track 4's 8 recognisers + adapter + cache + R2/R5/R6 requirements yields ~10-14 test files.
- **Codebase evidence**: file listing of `core/.../gremlin/translator/strategy/` (16 main, 9 test files after three tracks — Track 3 alone landed a comparable footprint for a third of Track 4's surface).
- **Survival test**: NO — realistic total ~29-38 files against a ~20-25 split bound; the sizing as written does not survive. Split or write the justification the two-sided-bound rule requires.

### Decision challenges

#### C2 Challenge: D9 realization — two planned recognisers, one `NotStep.class` key
- **Chosen approach**: In-scope list ships `NotFilterStepRecogniser` (presence form, `hasNot(key)`) and `NotStepRecogniser` (logical NOT) as sibling recognisers; D9 dispatch is `map.get(step.getClass())` on the exact runtime class.
- **Best rejected alternative**: one registered `NotStep` recogniser routing internally (the arrangement D9's own plan text prescribes: "`NotStep` is one recognizer branching on `hasEdgeHops`"), with the presence form as its first branch.
- **Counterargument trace**:
  1. `hasNot(key)` desugars to `NotStep(__.values(key))`; logical `not(traversal)` is also `NotStep`. The fork's `NotStep` is `final` (`javap`) — exact-class dispatch cannot tell the two apart.
  2. Registering both in the `Map.of` registry (GremlinStepWalker.java:90-93) throws `IllegalArgumentException` at class init → `ExceptionInInitializerError` → an `Error` the strategy net re-throws by design (GremlinToMatchStrategy.java:105-110) → every Gremlin compile fails hard, not a decline.
  3. Registered singly without a pinned branch order, the presence form dies anyway: a generic sub-walk of `values(key)` hits `PropertiesStep`, which has no recogniser until Track 5, so `hasNot(key)` declines silently.
- **Codebase evidence**: GremlinStepWalker.java:90-93 (Map.of registry); Track 3's router precedent `VertexStepRecogniser` → `VertexHopRecogniser`/`EdgeHopRecogniser` (GremlinStepWalker.java:82-84 Javadoc).
- **Survival test**: WEAK — D9 itself survives (the decision is right); the track's file-level realization contradicts it and needs one clarifying paragraph plus a file-list fix.

#### C5 Challenge: NOT decline rule — second condition vs the actual planner precondition
- **Chosen approach**: recogniser declines when the first NOT alias is absent from the positive pattern *or* when that alias would carry a WHERE filter, "because `manageNotPatterns` throws in both cases".
- **Best rejected alternative**: decline only on origin-alias-absent; build the NOT origin bare.
- **Counterargument trace**:
  1. The second throw is `if (exp.getOrigin().getFilter() != null)` (MatchExecutionPlanner.java:766-771) — a property of the NOT expression the translator itself builds, not of the alias's entry in `aliasFilters`.
  2. SQL MATCH already runs positive-alias WHERE + NOT (`MATCH {as:p, where:(age=30)}, NOT {as:p}.out('knows'){as:x}`): the positive WHERE travels on the positive pattern and `manageNotPatterns` never sees it.
  3. The track's wording therefore declines `g.V().has("age",30).not(__.out("knows"))` — the most common NOT composition — costing coverage for no planner constraint.
- **Codebase evidence**: MatchExecutionPlanner.java:750-771 (both preconditions; the third — `SQLMultiMatchPathItem` at :776-780 — is unreachable from the translator's assembly).
- **Survival test**: WEAK — the decline-as-defense-in-depth framing survives; the condition-2 wording does not and will over-decline if implemented as written.

### Invariant challenges

#### C3 Violation scenario: "one cached plan serves every value" under a pre-walk value-blind key
- **Invariant claim**: translator-on/off multiset equality for every recognized shape, with D5 serving one cached plan across predicate values.
- **Violation construction**:
  1. Start state: empty `GremlinPlanCache`; class `Person` with nullable property `k`; the decomposer implements the Purpose-line reading ("value-independent *traversal* fingerprint", track-4.md:9) — key computed from the traversal shape with values elided, before the walk.
  2. Action sequence: run `g.V().has("k", P.eq(5))` → walk renders `k = ?`, binds 5, planner pass, cache PUT under shape key S. Then run `g.V().has("k", P.eq(null))` → same value-blind shape key S.
  3. Intermediate state: cache HIT on S returns the `k = ?` plan; the null value binds into the positional slot.
  4. Violation point: the track's own NULL rule (Context and Orientation) requires `k IS NULL` for `eq(null)`; the cached plan evaluates `k = null`, and `QueryOperatorEquals.equals` short-circuits null operands to false (QueryOperatorEquals.java:71-73).
  5. Observable consequence: zero rows where native Gremlin returns the null-valued-property vertices — silent multiset divergence, no exception, no decline.
- **Feasibility**: CONSTRUCTIBLE under the pre-walk reading; INFEASIBLE under the step-7 post-walk generic-statement reading (the two queries render different statements, hence different keys). The track file currently licenses both readings — that ambiguity is the finding (A3).

#### C4 Violation scenario: sub-walk with per-child contexts — alias collision and boundary re-pin
- **Invariant claim**: translator-on/off multiset equality; `and(childA, childB)` filters rows where each child matches independently.
- **Violation construction**:
  1. Start state: vertices `v1 -a-> x`, `v1 -b-> y` (distinct targets); traversal `g.V().and(__.out("a"), __.out("b"))`.
  2. Action sequence: `AndStepRecogniser` sub-walks each edge-bearing child with a fresh nested `WalkerContext` (the naive reading of "its recognition context inheriting the parent boundaryAlias"). Each child dispatches `VertexHopRecogniser`, which mints `ctx.nextAnonVertexAlias()` (VertexHopRecogniser.java:83) — both children get `$g2m_anon_0` because `AliasSequence` restarts at 0 per context (WalkerContext.java:156-163).
  3. Intermediate state: shared pattern holds edges `boundary -a-> $g2m_anon_0` and `boundary -b-> $g2m_anon_0` — one alias, one binding.
  4. Violation point: MATCH semantics require the same vertex to close both edges; `v1` (whose `a`/`b` targets differ) drops out. Additionally each child's `appendFoldedHop` "re-pins the boundary / single RETURN column to the target" (VertexHopRecogniser.java:80-84; `setSingleReturnColumn` clears the projection lists, WalkerContext.java:253-263), so if writes delegate to the parent the outer query now returns the AND-child's targets instead of the filtered sources.
  5. Observable consequence: wrong multiset (missing `v1`, or `$g2m_anon_0` values returned instead of sources) — silent, no decline.
- **Feasibility**: CONSTRUCTIBLE — both traps follow directly from HEAD's shipped alias/boundary mechanics; only an explicit sub-context interception contract (A4) rules them out.

### Assumption challenges

#### C6 Assumption test: "notMatchExpressions wiring = a WalkerContext list + buildResult builder call"
- **Claim**: the NOT edge-bearing branch needs only "a new `notMatchExpressions` list on `WalkerContext` (via a new `RecognitionContext` sink, wired into `buildResult`'s `MatchPlanInputs.builder(...)`)".
- **Stress scenario**: implement `not(__.out("knows"))` — the recogniser must place a detached `SQLMatchExpression` chain into the sink.
- **Code evidence**: `MatchPlanInputs.notMatchExpressions` and `Builder.notMatchExpressions` exist (MatchPlanInputs.java:48, :154) and `manageNotPatterns` consumes them (MatchExecutionPlanner.java:750-790) — the downstream is real. But no shipped producer exists: `MatchPatternBuilder` builds `SQLMatchExpression` only internally for the positive pattern (MatchPatternBuilder.java:153, :204) and exposes only `PatternIR build()` (:255); `RecognitionContext` offers pattern contributions, never detached expressions (RecognitionContext.java:65-99).
- **Verdict**: BREAKS — the sink is wired downstream but has no upstream producer; an assembler capability is missing from scope (A5). Track 3's "edge-as-node builder extension the plan had assumed away" is the precedent for exactly this gap class.

#### C7 Assumption test: no-mutation-on-decline is a live per-recogniser invariant
- **Claim**: plan Invariants — "a recognizer that returns `false` leaves `WalkerContext` unmutated (per-recogniser unit invariant)"; Track 4 step 6 names its test "the canonical no-mutation-on-decline pin".
- **Stress scenario**: any Track 4 recogniser that binds a parameter or contributes a filter before a later container/child declines.
- **Code evidence**: HEAD's contract disavows the discipline in both files: "A recogniser may read and contribute in any order. A DECLINE ... makes the walker discard the whole context ... 'Validate before you mutate' is unnecessary here" (RecognitionContext.java:20-24; WalkerContext.java:22-26). Under D3 the discard makes partial contributions unobservable, so the per-recogniser wording is untestable as stated and already violated by design.
- **Verdict**: BREAKS as worded (the plan invariant and HEAD contradict); the *system-level* property (a declined walk leaks nothing) HOLDS via whole-context discard. Suggestion A7 rescopes rather than deletes.

#### C8 Assumption test: Tracks 1–3 handoffs exist at HEAD as the track assumes
- **Claim**: the track builds on — `GremlinPredicateAdapter` skeleton (flat scalar `Compare`, `neq` presence guard); `Outcome recognize(StepCursor, RecognitionContext)` contract with `take`/`takeIf`/`takeWhile`/`peek(int)`; `WalkerContext implements RecognitionContext`, no stepIndex; `MatchWhereBuilder` factories; `MatchPlanInputs.notMatchExpressions`; the equivalence fixture; `promoteStaticRidsFromFilters`; `manageNotPatterns` throws; `SQLPositionalParameter` / `CommandContext.setInputParameters`; the YQL invalidation hook; `QueryOperatorEquals` unbox/null lines; no `SQLEndsWithCondition` yet.
- **Stress scenario**: any one handoff differing at HEAD from the track's wording would invalidate a Plan of Work step.
- **Code evidence**: all verified — adapter skeleton + `neq` guard (GremlinPredicateAdapter.java:88-117); cursor contract (StepCursor.java:41-101); context shape (WalkerContext.java:28, :165-168); builder surface `classEquals`:65 / `in`:99 / `notIn`:112 / `containsText`:133 / `startsWith`:152 / `and`:172 / `or`:180 / `isDefined`:263 / `isNotDefined`:276 / `not`:288, with `endsWith`/`matchesRegex` absent as planned; `MatchPlanInputs.notMatchExpressions` (:48, :154); fixture extensible by design ("Tracks 4–6 extend it", EdgeTraversalEquivalenceTest.java class Javadoc) asserting boundary engagement + RID-multiset equality; `promoteStaticRidsFromFilters` (MatchExecutionPlanner.java:4758) already relied on by `StartStepRecogniser` (:126); `manageNotPatterns` (:750); `SQLPositionalParameter` present, `SQLEndsWithCondition` absent (parser package listing); `CommandContext.setInputParameters` (:106); `YqlExecutionPlanCache implements MetadataUpdateListener` with schema-change invalidation (:23, :141-172); `QueryOperatorEquals` singleton unbox + null short-circuit (:63-73). `classEquals` has test callers only (grep on unique literal) — "Track 4 first production caller" holds.
- **Verdict**: HOLDS — the cross-track handoff surface is real; no Plan of Work step rests on a phantom artifact. (The gaps found are in what the track *adds* — A1–A6 — not in what it inherits.)

#### C9 Assumption test: same-alias filters overwrite at HEAD (premise of the AND-compose rule)
- **Claim**: Context and Orientation — `putAliasFilter` and the `buildResult` merge replace rather than AND, so Track 4 must change both or `g.V(ids).has(k,v)` silently drops a filter.
- **Stress scenario**: `g.V(id1,id2).hasLabel("Person")` — start step puts `@rid IN [...]`, `HasStepRecogniser` puts `classEquals` on the same alias.
- **Code evidence**: `putAliasFilter` is `aliasFilters.put(alias, where)` (WalkerContext.java:236-238, Javadoc "entries here override"); `buildResult` merges with `putAll` semantics, recogniser entries overriding builder entries (GremlinStepWalker.java:242-243). Second put wins → the RID constraint vanishes → every Person returned.
- **Verdict**: HOLDS — the track's premise is accurate and its mandated AND-compose fix is grounded; no finding.
