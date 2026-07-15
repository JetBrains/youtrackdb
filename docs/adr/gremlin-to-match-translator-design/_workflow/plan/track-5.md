<!-- workflow-sha: d2dfcc2d44fabd3ac76c5fd7620f1e6013675ad9 -->
# Track 5: Logical filters + plan cache — and / or / not / where, sub-walker, GremlinPlanCache (D5)

## Purpose / Big Picture
After this track the step-level logical filters translate — `and` / `or` / `not` / `where(traversal)` / `where(P)` — plus the bare-presence negation `hasNot(key)`, each composed by a sub-walker that runs the child sub-traversal against the same recogniser registry; and the `GremlinPlanCache` (D5) makes one planner pass serve every predicate value.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Split off from Track 4 at decomposition: the merged predicate + logical surface realized at ~29–38 files, past the ~25 split ceiling, with a clean seam between the predicate surface (Track 4) and the combinators + cache (here) — adversarial finding A1, user-approved 2026-07-15. This track builds on Track 4's predicate surface and adds `AndStepRecogniser` (pure-filter children AND-composed; edge-bearing children append pattern fragments), `OrStepRecogniser` (all-children-pure-filter, composes via `MatchWhereBuilder.or`), a single `NotStepRecogniser` registered under `NotStep.class` (A2: `hasNot(key)` and logical `not(...)` are the same `final` runtime class, so one recogniser branches values-child-first then `hasEdgeHops`), and the `WhereTraversalStep` / `WherePredicateStep` recognisers. A `SubTraversalPredicateAdapter` + sub-walker translates each child sub-traversal under an explicit interception contract (A4). Finally the `GremlinPlanCache` (D5): predicate values bind as `SQLPositionalParameter` slots so one cached plan serves every value — the key is the value-independent post-walk generic-statement fingerprint (A3), and RID-bearing shapes bypass the cache (R3).

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->
- 2026-07-15 (inline replan, from Track 4 adversarial A7): HEAD's `RecognitionContext` / `WalkerContext` contract (RecognitionContext.java:20-24, WalkerContext.java:22-26) disavows the plan's per-recogniser "no-mutation-on-decline" invariant — a DECLINE discards the whole context, so per-recogniser purity is neither enforced nor needed. Step 1's sub-walk test pins the *capture boundary* (a declined child leaves the parent's committed state untouched), not a general no-mutation rule. Flag the plan-level invariant for Phase 4 reconciliation.
- 2026-07-15 (inline replan, from Track 4 adversarial A3): D5's cache key must be the post-walk generic-statement fingerprint, not a pre-walk traversal-shape fingerprint — translation is value-dependent (`eq(null)` → `IS NULL`, `eq(v)` → `field = ?`, size-1 collection equality declines), so a value-blind key serves a wrong cached plan silently (`has(k, eq(null))` reusing the `k = ?` plan returns zero rows where native returns the null-valued vertices). The RID-inline decision itself lives in Track 4; its cache-bypass consequence (R3) lands here.

## Decision Log
<!-- Continuous-log. -->
- 2026-07-15 (inline replan, A2): **All `NotStep` handling lives in one recogniser registered under `NotStep.class`.** `hasNot(key)` desugars to `NotStep(__.values(key))` and logical `not(traversal)` is also a `NotStep`; the fork's `NotStep` is `final` (`javap`), so exact-class dispatch cannot separate them, and two registry entries throw a `Map.of` duplicate-key `IllegalArgumentException` at class init — an `Error` the strategy's `RuntimeException` net rethrows, so every Gremlin compile fails rather than declines (GremlinStepWalker.java:90-93, GremlinToMatchStrategy.java:105-110). The single recogniser branches the values-child form FIRST (`hasNot(key)` presence → `MatchWhereBuilder.isNotDefined`), then `hasEdgeHops` (logical NOT). Ordering is load-bearing: `PropertiesStep` (the `values(key)` child) has no recogniser until Track 6, so the presence branch must run before any generic sub-walk. Track 4 keeps only `has(key)` presence, which arrives on the distinct `TraversalFilterStep` class.
- 2026-07-15 (inline replan, A3): **Key `GremlinPlanCache` on the post-walk generic-statement fingerprint, not a pre-walk traversal-shape fingerprint.** A value-blind pre-walk key maps `has(k, eq(null))` and `has(k, eq(5))` to one entry; serving the cached `k = ?` plan for the null query evaluates `k = null` → FALSE (QueryOperatorEquals.java:71-73) where `IS NULL` semantics are required — a silent empty result. The post-walk key is immune: the two shapes render different statements before the key is computed. R6 determinism tests pin `eq(null)` vs `eq(v)` into distinct entries and keep collection-size classes from colliding.
- 2026-07-15 (inline replan, A6): **Decline NOT only when the NOT origin alias is absent from the positive pattern.** `manageNotPatterns`'s second throw fires on `exp.getOrigin().getFilter() != null` (MatchExecutionPlanner.java:766-771) — an inline filter on the NOT expression's own origin item, which the translator constructs and can leave empty — never on `aliasFilters`. Build the NOT origin as a bare alias reference and drop the positive-alias-WHERE decline, so `g.V().has("age",30).not(out("knows"))` — the dominant NOT shape — still translates (SQL MATCH already runs positive-alias WHERE + NOT: `MATCH {as:p, where:(age=30)}, NOT {as:p}.out('knows'){as:x}`).
- 2026-07-15 (inline replan, A5): **Edge-bearing NOT needs a builder capability for the detached `SQLMatchExpression`.** Nothing Tracks 1–4 ship produces the detached NOT chain `manageNotPatterns` consumes: `MatchPatternBuilder` constructs `SQLMatchExpression` only internally and exposes only the positive `PatternIR build()` (MatchPatternBuilder.java:153, :204, :255). Add a `MatchPatternBuilder` / assembler extension that emits a detached `SQLMatchExpression` chain, or assemble the AST directly inside the NOT recogniser with an explicit exemption from D6's shared-builder discipline; the decomposer picks one and records which.
- 2026-07-15 (inline replan, R4): **The edge-bearing-NOT decline is defense-in-depth, not the sole barrier.** `manageNotPatterns` builds eagerly inside the strategy's `apply()`, whose `RuntimeException` net catches its `CommandExecutionException` and degrades to a clean native decline. The recogniser-side decline skips a wasted plan-build and stays correct should plan-build ever go lazy; it is not what keeps a disqualifying NOT shape from throwing.

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. -->

## Context and Orientation
The step-level logical filters (`AndStep` / `OrStep` / `NotStep` / `WhereTraversalStep` / `WherePredicateStep`) are the `ConnectiveStrategy` form — each child carries a sub-traversal of arbitrary recognized steps. The recognisers implement the post-Track-3 contract `Outcome recognize(StepCursor, RecognitionContext)` (head via `cursor.take()`, trailing shape via `takeIf` / `takeWhile`, returning `ACCEPTED` / `DECLINE`). A logical child is translated by a **sub-walker**: a nested `StepCursor` over the child sub-traversal, run against the same registry, whose recognition context inherits the parent `boundaryAlias`.

The sub-context contract is load-bearing — a naive fresh nested `WalkerContext` per child produces silent wrong results, not declines (adversarial A4):
- **Alias minting delegates to the parent's `AliasSequence`.** `AliasSequence` restarts at 0 per context (WalkerContext.java:156-163), so per-child contexts mint `$g2m_anon_0` twice for `and(__.out("a"), __.out("b"))`; in MATCH one alias is one binding, silently over-constraining to "both edges reach the *same* vertex" and dropping every source whose two targets differ.
- **`pinBoundary` / `setSingleReturnColumn` are swallowed.** A hop child dispatches `VertexHopRecogniser`, which re-pins the boundary / single RETURN column to its target (VertexHopRecogniser.java:80-84; `setSingleReturnColumn` clears and replaces the projection, WalkerContext.java:253-263) — on a delegating context that moves the outer traversal's result column onto the child's target.
- **`putAliasFilter` is captured per-combinator.** OR children's WHERE contributions must be collected as composable `SQLBooleanExpression`s for `MatchWhereBuilder.or`, never committed to `aliasFilters` (which override rather than compose).
- **Pattern contributions forward for AND edge-bearing children, and are captured for NOT.** A NOT edge-bearing child's output goes to the `notMatchExpressions` sink, never the positive `patternBuilder`.

`hasNot(key)` and logical `not(...)` share the `final NotStep` runtime class (A2), so one recogniser owns `NotStep.class` and branches the values-child presence form before `hasEdgeHops`. Edge-bearing NOT emits a detached `SQLMatchExpression` into a new `notMatchExpressions` list on `WalkerContext` (a new `RecognitionContext` sink, wired into `buildResult`'s `MatchPlanInputs.builder(...)`), produced by the A5 builder capability. The recogniser declines only when the NOT origin alias is absent from the positive pattern (A6); the eager-build safety net is the backstop for the disqualifying shapes (R4).

The `GremlinPlanCache` (D5) closes the seam Track 4 leaves open. Track 4's predicate adapter renders comparison values as inline literals (`MatchLiteralBuilder.toLiteral`, today's behavior). This track adds `bindParam(value) → SQLPositionalParameter` (slot allocation + value recording) to `RecognitionContext`, implemented by `WalkerContext`, and switches `GremlinPredicateAdapter` to emit `SQLPositionalParameter` for **predicate comparison values** — so recognisers and the adapter, which see only `RecognitionContext`, can reach it. Structural tokens stay inline and must **not** parameterize (class names for `classEquals`, `~label` values, RIDs for `@rid IN`), because a structural token bound as a param serves a wrong plan. The boundary step installs the per-walk param map through `ctx.setInputParameters(map)`; the cache keys on the value-independent post-walk generic-statement fingerprint (A3) and reuses the YQL plan-cache schema-change invalidation hook. RID-bearing traversals bypass the cache (R3 — they fingerprint per id set and gain no reuse; see Track 4 Decision Log for the RID-inline decision they follow).

## Plan of Work
1. **`SubTraversalPredicateAdapter` + sub-walker:** a nested `StepCursor` over each child sub-traversal against the same registry, with the A4 interception contract — alias minting delegates to the parent's `AliasSequence`; `pinBoundary` / `setSingleReturnColumn` are swallowed; `putAliasFilter` is captured per-combinator; pattern contributions forward only for AND edge-bearing children and are captured for NOT. Its `decline_doesNotCommitPartialStateToOuterContext` unit test pins the sub-walk capture boundary (A7): a declined child leaves the parent's committed state untouched. The decomposer pins the concrete sub-context type against HEAD.
2. **`AndStepRecogniser`** and **`OrStepRecogniser`** (two files so the asymmetry — AND distributes, OR does not — is visible in code): `AndStep` accepts pure-filter children (AND-composed into `where`), edge-bearing children (append pattern fragments / NOT expressions), and mixed; `OrStep` requires all children pure-filter (composes via `MatchWhereBuilder.or`) and declines if any child carries edges, with the `hasEdges` flag propagating recursively.
3. **Single `NotStepRecogniser`** registered under `NotStep.class` (A2): branches the values-child form first (`hasNot(key)` → `MatchWhereBuilder.isNotDefined`), then `hasEdgeHops` — pure-filter → `MatchWhereBuilder.not(...)` into `where`; edge-bearing → a detached `SQLMatchExpression` into the `notMatchExpressions` sink via the A5 builder capability. Declines only when the NOT origin alias is absent from the positive pattern (A6); the eager-build net is the safety backstop (R4).
4. **`WhereTraversalStepRecogniser`** (the positive counterpart of NOT) and **`WherePredicateStepRecogniser`** (`$matched.<label>` references).
5. **`GremlinPlanCache`** (D5): add the `bindParam` positional-parameter sink to `RecognitionContext` (implemented by `WalkerContext`); switch `GremlinPredicateAdapter` to emit `SQLPositionalParameter` for predicate comparison values while keeping structural tokens inline; install the per-walk param map at the boundary via `ctx.setInputParameters(map)`; key on the post-walk generic-statement fingerprint (A3); reuse the YQL schema-change invalidation hook; bypass the cache for RID-bearing shapes (R3). Pin fingerprint stability, value independence, and the value-fork cases (`eq(null)` vs `eq(v)` into distinct entries, collection-size classes not colliding) with R6 tests.

## Concrete Steps
<!-- Phase A placeholder. -->

## Episodes
<!-- Continuous-log. Empty at Phase 1. -->

## Validation and Acceptance
- `and` (pure / edge-bearing / mixed children), `or` (pure-filter children; an edge-bearing child declines), `not` (both shapes), `where(traversal)`, and `where(P)` translate or decline per design; every declined walk leaves the parent context unmutated via whole-context discard.
- `and(__.out("a"), __.out("b"))` over vertices whose `a`/`b` targets differ matches native (the two edges are not collapsed onto one alias) — pins the sub-context alias-isolation trap (A4).
- `hasNot(key)` → `IS NOT DEFINED` matches native on absent and present-with-null properties (distinct from `IS NULL`).
- `g.V().has("age",30).not(__.out("knows"))` (a positive-alias WHERE plus NOT) translates and matches native (A6); the two disqualifying NOT shapes — the NOT origin alias absent from the positive pattern, and the NOT origin item carrying an inline filter — run on native with no exception surfaced (they decline via the eager-build net, R4).
- The plan cache serves one plan for the same traversal shape across distinct predicate values; `eq(null)` and `eq(v)` occupy distinct cache entries; collection-size classes do not collide; a schema change invalidates the cache (D5 / A3 / R6).
- A RID-bearing shape (`g.V(id).has(...)`) takes the direct-RID fetch and does not populate the plan cache (R3).
- The plan-cache fingerprint is stable across walks of the same shape and value-independent across predicate values (deterministic walk-to-slot parameter ordering, R6).

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — acceptance lines. -->

## Idempotence and Recovery
<!-- Phase A placeholder. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Often empty. -->

## Interfaces and Dependencies
**In scope (new):** `AndStepRecogniser`, `OrStepRecogniser`, a single `NotStepRecogniser` (registered under `NotStep.class`), `WhereTraversalStepRecogniser`, `WherePredicateStepRecogniser`; `SubTraversalPredicateAdapter` + a nested sub-walker recognition context (concrete type pinned at decomposition); `GremlinPlanCache` + a `RecognitionContext.bindParam` positional-parameter sink implemented by `WalkerContext`; a detached-`SQLMatchExpression` builder capability for edge-bearing NOT (A5 — either a `MatchPatternBuilder` extension or a documented direct-AST exemption); logical-combinator / sub-context / NOT-shape / plan-cache-determinism tests.
**In scope (modified):** `GremlinPredicateAdapter` (emit `SQLPositionalParameter` for predicate comparison values in place of Track 4's inline literals; structural tokens stay inline); `MatchPatternBuilder` (the detached-NOT `SQLMatchExpression` capability, A5); `WalkerContext` (the `notMatchExpressions` list, the `bindParam` sink, and the sub-context alias / boundary interception) plus the matching `RecognitionContext` accessors and `buildResult`'s `.notMatchExpressions(...)` wiring; `GremlinToMatchStrategy` (cache get/put replaces the `useCache=false` eager build); `GremlinToMatchTranslator` (`TranslationResult` carries the per-walk parameter values a shared cached plan needs); `YTDBMatchPlanStep` (per-execution `ctx.setInputParameters` install); `SharedContext` (the `GremlinPlanCache` field + schema-listener invalidation wiring, mirroring `YqlExecutionPlanCache`); the registry registration sites.
**Out of scope:** the predicate algebra and presence `has(key)` (Track 4); projections / labels / dedup / order / aggregates (Track 6); union + list-shaping terminators (Track 7); edge-bearing OR and the singleton-collection schema-aware rewrite (Phase 2 — design §"Out of scope").
**Inter-track dependencies:** depends on Track 4 (the full predicate algebra its sub-predicates and `where(P)` reuse, and the `GremlinPredicateAdapter` whose value rendering D5 flips to positional parameters) and Track 1 (`isNotDefined` factory, `MatchWhereBuilder.and` / `or` / `not`). Supplies the sub-walker to Track 6 (its `by(__.count())` / `by(__.fold())` value-side accumulators) and Track 7 (the `union` child sub-walk), and the plan cache to every later track.
**Signatures:** `NotStep` edge detection on the sub-traversal (`hasEdgeHops`); `MatchExecutionPlanner.manageNotPatterns` (throws `CommandExecutionException` when the NOT origin alias is absent from the positive pattern, or when the NOT origin item carries an inline filter — caught by the eager-build `apply()` net → native decline); `MatchPlanInputs.notMatchExpressions` (:48, :154); `MatchPatternBuilder.build()` (:255 — positive `PatternIR` only today); `SQLPositionalParameter.getValue(params)`; `CommandContext.setInputParameters(map)` (:106); the `SharedContext` cache-field + `MetadataUpdateListener` invalidation pattern (`YqlExecutionPlanCache`).

## Invariants & Constraints
<!-- Combined per-track invariants + constraints (conventions-execution.md §2.1 §14).
Added by workflow migration (#1145). Strategic invariants/constraints for this track remain
in implementation-plan.md § High-level plan (Architecture Notes) and this track's ## Decision
Log — the conservative migration retained the plan Architecture Notes rather than folding them here. -->

## Base commit
<!-- Phase B records the HEAD SHA here at session start; Phase C reads it to compute the
cumulative track diff (conventions-execution.md §2.1 §15). Added by workflow migration (#1145). -->
