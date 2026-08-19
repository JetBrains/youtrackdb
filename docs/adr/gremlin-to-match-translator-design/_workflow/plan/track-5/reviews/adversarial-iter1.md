<!-- MANIFEST
findings: 6   severity: {blocker: 1, should-fix: 3, suggestion: 2}
index:
  - {id: A1, sev: blocker,    loc: "plan/track-5.md:53", anchor: "### A1 ", cert: C4, basis: "post-walk generic-statement fingerprint via SQLMatchStatement.toGenericStatement() omits notMatchExpressions; NOT-bearing Gremlin shapes collide or serve wrong cached plans"}
  - {id: A2, sev: should-fix, loc: "plan/track-5.md:51", anchor: "### A2 ", cert: C3, basis: "Track 4 Surprise values→properties rewrite applies to hasNot child; plan item 3 does not pin PROPERTY return-type acceptance like TraversalFilterStepRecogniser"}
  - {id: A3, sev: should-fix, loc: "plan/track-5.md:27", anchor: "### A3 ", cert: C5, basis: "A5 detached-NOT builder capability still 'decomposer picks one' with no pinned choice before Concrete Steps"}
  - {id: A4, sev: should-fix, loc: "plan/track-5.md:55", anchor: "### A4 ", cert: C6, basis: "R6 determinism, A4 alias-isolation, and A6 NOT-shape tests live in Validation only; Concrete Steps empty so decomposition cannot gate them"}
  - {id: A5, sev: suggestion, loc: "implementation-plan.md:433", anchor: "### A5 ", cert: C1, basis: "Interfaces recount lands 18–22 files vs ~16 scope line; still inside ~25 ceiling"}
  - {id: A6, sev: suggestion, loc: "plan/track-5.md:9", anchor: "### A6 ", cert: C2, basis: "no Track 5 leakage from Track 4 on HEAD — seam held, dependencies present"}
evidence_base: {section: "## Evidence base", certs: 6, matches: 6}
cert_index:
  - {id: C1, verdict: SURVIVES, anchor: "#### C1 "}
  - {id: C2, verdict: HOLDS, anchor: "#### C2 "}
  - {id: C3, verdict: CONSTRUCTIBLE, anchor: "#### C3 "}
  - {id: C4, verdict: CONSTRUCTIBLE, anchor: "#### C4 "}
  - {id: C5, verdict: FRAGILE, anchor: "#### C5 "}
  - {id: C6, verdict: FRAGILE, anchor: "#### C6 "}
flags: [CONTRACT_OK]
-->

Adversarial review, iteration 1 — Track 5: Logical filters + plan cache (D5). Compares the plan-of-work items 1–5 and `## Interfaces and Dependencies` against HEAD after Track 4 completion: projected footprint, Track 4 seam integrity, and the test/fingerprint gaps called out in the user checklist. Track 5 code is not started (`## Progress` unchecked; no `*StepRecogniser` / `GremlinPlanCache` / `bindParam` on HEAD). PSI was not used this session; symbol claims rest on direct file reads and grep (reference-accuracy caveat on caller enumeration).

## Findings

### A1 [blocker]
**Certificate**: C4 (violation scenario — NOT-blind cache fingerprint)
**Target**: Plan of Work item 5 (`GremlinPlanCache`, post-walk generic-statement fingerprint, A3) and Validation lines on cache reuse / R6 determinism.
**Challenge**: Item 5 keys the cache on the post-walk generic-statement fingerprint. The natural reuse path — `SQLMatchStatement.toGenericStatement()` — serializes only `matchExpressions`, not `notMatchExpressions`. A test in-tree documents this explicitly (`MatchStatementTest.testToGenericStatementComplex`: "toGenericStatement() serializes only matchExpressions, not notMatchExpressions"). Item 3 emits edge-bearing NOT into `notMatchExpressions`; item 5 never says the fingerprint builder must include that list. Two traversals with the same positive pattern and different NOT sub-patterns (`g.V().not(out("a"))` vs `g.V().not(out("b"))`, or NOT vs no-NOT) can fingerprint-identical while plans differ — a silent wrong-plan serve, worse than the pre-walk value-collision A3 already fixed. The additive `MatchExecutionPlanner(MatchPlanInputs)` path also leaves `statement == null` today (`GremlinToMatchStrategy.buildPlan` passes `useCache=false`), so GremlinPlanCache cannot mirror `YqlExecutionPlanCache.get(statement.getOriginalStatement())` without a dedicated fingerprint from `MatchPlanInputs` (including `notMatchExpressions`, param slots as `?`, and inline structural tokens).
**Evidence**: `SQLMatchStatement.toGenericStatement()` (`SQLMatchStatement.java:451-505`) iterates `matchExpressions` only; `notMatchExpressions` omitted. `MatchStatementTest.java:275-276`. `GremlinStepWalker.buildResult` does not yet wire `.notMatchExpressions(...)` (`GremlinStepWalker.java:275-282`). Track 5 Decision Log A3 (value-fork immunity) covers WHERE/param shapes, not NOT-list omission.
**Proposed fix**: In item 5 and the D5 Decision Log, pin the fingerprint contract: build from the full post-walk `MatchPlanInputs` AST (positive pattern + `notMatchExpressions` + alias filters with `SQLPositionalParameter` placeholders + inline structural literals), not from `SQLMatchStatement.toGenericStatement()` alone. Add an R6 test that two NOT-differing shapes do not share a cache entry.

### A2 [should-fix]
**Certificate**: C3 (violation scenario — `hasNot` declines under production rewrite)
**Target**: Plan of Work item 3 (`NotStepRecogniser`, values-child-first `hasNot(key)` → `isNotDefined`); Track 4 `## Surprises & Discoveries` Step 3 entry (values→properties rewrite).
**Challenge**: Track 4 discovered that `has(key)` reaches g2m as `TraversalFilterStep` with a `properties(key)` child, not `values(key)` — an optimization strategy rewrites `values→properties` before the provider-phase translator runs. Track 4 fixed `has(key)` by accepting both `PropertyType.VALUE` and `PropertyType.PROPERTY` in `TraversalFilterStepRecogniser.presenceKey` (`TraversalFilterStepRecogniser.java:89-91`), with `propertiesKeyPresence_contributesIsDefined` pinning the production shape. Track 4's Surprise explicitly flags the same rewrite for `hasNot(key)` (`NotStep(__.values(key))`), but item 3 only says "branches the values-child form first" with no PROPERTY acceptance pin. A `NotStepRecogniser` that matches only `PropertyType.VALUE` declines `hasNot(k)` in production exactly the way pre-fix `has(k)` did — whole-traversal native fallback under D3, not a test-only gap.
**Evidence**: Track 4 `track-4.md` Surprise 2026-07-16T14:14Z; `TraversalFilterStepRecogniser.java:66-70,89-91`; `TraversalFilterStepRecogniserTest.propertiesKeyPresence_contributesIsDefined`. No `NotStepRecogniser` on HEAD (grep). Reference-accuracy caveat: the rewrite strategy class name is not pinned in-tree; the production shape is empirically pinned by Track 4 tests.
**Proposed fix**: One sentence in item 3 (and the `hasNot` Validation line): the values-child presence branch accepts both `PropertyType.VALUE` and `PropertyType.PROPERTY` on the lone `PropertiesStep`, mirroring `TraversalFilterStepRecogniser`. Add `hasNot_propertiesChild_contributesIsNotDefined` beside the existing `hasNot` / `IS NOT DEFINED` equivalence case.

### A3 [should-fix]
**Certificate**: C5 (assumption test — A5 builder capability choice)
**Target**: Decision Log A5; Plan of Work item 3 (edge-bearing NOT → detached `SQLMatchExpression`); `## Interfaces and Dependencies` (`MatchPatternBuilder` extension vs direct-AST exemption).
**Challenge**: Edge-bearing NOT needs a detached `SQLMatchExpression` chain; `MatchPatternBuilder.build()` returns positive `PatternIR` only and locks the builder (`MatchPatternBuilder.java:255-262`). Decision Log A5 lists two options ("`MatchPatternBuilder` extension" or "assemble AST directly inside the NOT recogniser with an explicit D6 exemption") and says "the decomposer picks one" — but `## Concrete Steps` is still empty at Phase A. Without a pinned choice, decomposition can split NOT assembly across two incompatible approaches (builder extension touches shared D6 discipline; direct-AST exemption needs a documented carve-out in the track file).
**Evidence**: `MatchPatternBuilder.build()` one-shot positive IR (`MatchPatternBuilder.java:255-262`); `MatchPlanInputs.notMatchExpressions` slot exists (`MatchPlanInputs.java:48,154`); `manageNotPatterns` consumes detached expressions (`MatchExecutionPlanner.java:759-771`). No detached-NOT builder on HEAD.
**Proposed fix**: Record the choice in the Decision Log before decomposition (prefer a small `MatchPatternBuilder.buildNotExpression(...)` or sibling helper that keeps D6 shared-builder discipline, unless review finds the NOT AST cannot be expressed through the builder — then pin the direct-AST exemption with file/method scope).

### A4 [should-fix]
**Certificate**: C6 (assumption test — test requirements assigned to steps)
**Target**: `## Validation and Acceptance` (R6 determinism, A4 alias isolation, A6 NOT shapes); empty `## Concrete Steps`.
**Challenge**: The user checklist items are present at track level but not bound to decomposed steps. Validation pins: (R6) `eq(null)` vs `eq(v)` distinct cache entries, collection-size classes non-colliding, schema invalidation, deterministic slot ordering; (A4) `and(__.out("a"), __.out("b"))` with differing targets; (A6) positive-alias WHERE + NOT translates, two disqualifying NOT shapes decline via eager-build net. Plan item 1 names the sub-walk decline-boundary unit test (A7); item 5 names R6 tests — but with `## Concrete Steps` empty, nothing forces R6 / A4 / A6 into the step sequence or the Phase B coverage gate. Track 4's narrowed-scope adversarial PASS required footprint + seam checks; Track 5 repeats the same gap Track 4 closed only after Concrete Steps landed.
**Evidence**: `track-5.md:55-68` (Validation); `track-5.md:49-53` (Plan items 1–5); `track-5.md:55-56` (Concrete Steps placeholder). Track 4 `track-4.md:84-87` shows the expected post-decomposition step binding pattern.
**Proposed fix**: When decomposition fills `## Concrete Steps`, bind explicitly: one step owns R6 + structural-inline vs param split tests; combinators step owns A4 alias isolation + A6 NOT-shape equivalence (including the two decline shapes run on native with no surfaced exception, R4); sub-walker step owns A7 decline-boundary unit test.

### A5 [suggestion]
**Certificate**: C1 (scope challenge — footprint vs ~16 claim)
**Target**: `implementation-plan.md:433` (`**Scope:** ~16 files`) vs `track-5.md` `## Interfaces and Dependencies`.
**Challenge**: Enumerating named artifacts: **new** — `AndStepRecogniser`, `OrStepRecogniser`, `NotStepRecogniser`, `WhereTraversalStepRecogniser`, `WherePredicateStepRecogniser`, `SubTraversalPredicateAdapter` + sub-context type (1–2 files), `GremlinPlanCache` (7–8 production files). **Modified** — `GremlinPredicateAdapter`, `MatchPatternBuilder`, `WalkerContext`, `RecognitionContext`, `GremlinToMatchStrategy`, `GremlinToMatchTranslator`, `YTDBMatchPlanStep`, `SharedContext`, `GremlinStepWalker` registry + `buildResult` wiring (9 files). **Tests** — logical-combinator equivalence, sub-context / alias isolation, NOT shapes, plan-cache determinism, sub-walk decline boundary, `hasNot` presence (4–6 files). Nominal **18–22** files; +1 if sub-context splits into two types. Still below the **~25** split ceiling from the Track 4 A1 split (~29–38 merged). The ~16 figure is ~2–6 files low but not a re-split trigger.
**Evidence**: File-by-file list above; `GremlinStepWalker.PRODUCTION_RECOGNISERS` currently four entries (`GremlinStepWalker.java:98-103`) — five new step classes fit in `Map.of` (≤10 pairs). A5 builder work stays inside already-counted `MatchPatternBuilder`.
**Proposed fix**: Annotate scope as "~16–20 (~22 with sub-context split + full R6 suite)" so the decomposer is not surprised at the ceiling check.

### A6 [suggestion]
**Certificate**: C2 (assumption test — Track 4 seam / no leakage)
**Target**: Track 4 completion vs Track 5 in-scope surface.
**Challenge**: Verify Track 4 did not land logical filters, `hasNot`, sub-walker, or D5 on HEAD. Grep finds no `AndStepRecogniser`, `GremlinPlanCache`, or `bindParam`. `TraversalFilterStepRecogniser` handles `has(key)` only and documents `hasNot` as Track 5 (`TraversalFilterStepRecogniser.java:22-23`). `GremlinPredicateAdapter` still renders comparison values via `MatchLiteralBuilder.toLiteral` (`GremlinPredicateAdapter.java:270`). `GremlinToMatchStrategy.buildPlan` uses `useCache=false` (`GremlinToMatchStrategy.java:390-391`). Track 4 Progress is complete; dependencies (`MatchWhereBuilder.and/or/not`, `isNotDefined`, AND-composing `putAliasFilter`, full `GremlinPredicateAdapter`) are on HEAD.
**Evidence**: Grep over `core/.../gremlin/translator`; Track 4 `track-4.md` Progress markers; Track 4 adversarial A8 seam language (preserved).
**Proposed fix**: None — seam holds. Carry Track 4's `values→properties` Surprise into item 3 (A2).

## Evidence base

#### C1 SURVIVES — Scope challenge: post-split footprint vs ~25 ceiling
- **Chosen approach**: ~16-file Track 5 after Track 4 A1 split.
- **Best rejected alternative**: re-merge with Track 4 (over ~25) or fold cache into Track 6 (breaks D5 seam).
- **Counterargument trace**: 7–8 new + 9 modified + 4–6 test = 18–22 nominal (A5). Below ~25; above fold-back floor. Merged predicate+logical estimate was ~29–38 (`track-5.md:9`).
- **Survival test**: YES — sizing OK; scope line slightly low.

#### C2 HOLDS — Assumption test: Track 4 outputs exist; no Track 5 leakage
- **Claim**: Track 5 builds on Track 4 predicate surface + Track 1 factories; Track 4 did not smuggle Track 5 work.
- **Code evidence**: `GremlinPredicateAdapter` full adapter with inline literals; `HasStepRecogniser` + `TraversalFilterStepRecogniser` registered; `WalkerContext.putAliasFilter` AND-composes; `MatchWhereBuilder.isNotDefined` / `not` present; no `notMatchExpressions` on walker; no logical-step recognisers; strategy cache off.
- **Verdict**: HOLDS.

#### C3 CONSTRUCTIBLE — Violation scenario: `hasNot` declines when child is `properties(key)`
- **Invariant claim**: `hasNot(k)` translates to `IS NOT DEFINED` with native multiset parity.
- **Violation construction**:
  1. Start state: vertex with and without property `k`; production strategy order applies values→properties rewrite to `NotStep` child (Track 4 Surprise).
  2. Action: `g.V().hasNot("k")` → `NotStep` with `PropertiesStep(k, PROPERTY)` child.
  3. Intermediate: item 3 recogniser matches VALUE-only → no presence branch → attempts generic sub-walk → `PropertiesStep` has no recogniser (Track 6) → DECLINE.
  4. Observable: native handles query; translator declines whole traversal — capability gap on a headline Track 5 shape.
- **Feasibility**: CONSTRUCTIBLE — Track 4 already pins the rewrite for `has(key)`.

#### C4 CONSTRUCTIBLE — Violation scenario: NOT-blind cache key serves wrong plan
- **Invariant claim**: one cached plan per shape; R6 distinct entries for shape forks.
- **Violation construction**:
  1. Start state: cache keyed via `toGenericStatement()`-style string omitting NOT list.
  2. Action: translate and cache `g.V().not(out("knows"))`; then `g.V().not(out("created"))` — same positive `g.V()` pattern, different `notMatchExpressions`.
  3. Intermediate: fingerprints match on positive MATCH + RETURN only.
  4. Violation: second query receives first query's NOT plan — wrong multiset or planner failure caught only by eager-build net (R4), not by cache logic.
- **Feasibility**: CONSTRUCTIBLE — `toGenericStatement()` omission is documented in tests.

#### C5 FRAGILE — Assumption test: A5 detached-NOT builder choice
- **Claim**: decomposer can pick builder extension vs direct AST without scope drift.
- **Stress**: D6 shared-builder discipline vs one-off AST in recogniser; `MatchPatternBuilder` one-shot `build()` cannot emit NOT today.
- **Verdict**: FRAGILE — both paths viable; choice must be pinned before Concrete Steps.

#### C6 FRAGILE — Assumption test: R6 / A4 / A6 tests enforced at decomposition
- **Claim**: Validation lines become Phase B gates.
- **Stress**: `## Concrete Steps` empty; item 5 mentions R6 but items 2–4 do not name A4/A6 test ownership.
- **Verdict**: FRAGILE — requirements exist; step binding missing until decomposition (A4 fix).

## Plan items 1–5 vs projected footprint

| Item | Planned deliverable | HEAD | Projected files |
|------|-------------------|------|-----------------|
| 1 | `SubTraversalPredicateAdapter` + sub-walker, A4 contract, A7 decline test | Not started | 1–2 new + `WalkerContext` / `RecognitionContext` edits |
| 2 | `AndStepRecogniser`, `OrStepRecogniser` | Not started | 2 new + registry |
| 3 | `NotStepRecogniser`, A5 NOT builder, A6 decline rule | Not started | 1 new + `MatchPatternBuilder` + `buildResult.notMatchExpressions` |
| 4 | `WhereTraversalStepRecogniser`, `WherePredicateStepRecogniser` | Not started | 2 new + registry |
| 5 | `GremlinPlanCache`, `bindParam`, param map install, A3 fingerprint, R3 bypass, R6 tests | Not started | 1 new + 6–7 modified (`GremlinPredicateAdapter`, strategy, translator, boundary, `SharedContext`) |

**Track 4 leakage:** none on HEAD (A6 / C2).

**Split ceiling (~25):** projected 18–22 — within bounds (A5 / C1).

**hasNot + values→properties:** Track 4 Surprise recorded; plan item 3 not yet updated (A2 / C3) — fix before decomposition.

**Plan cache + inline structural vs params:** Context and item 5 state the split correctly (params for predicate values; inline class/`~label`/RID; R3 bypass). Blocker is fingerprint construction including NOT + full `MatchPlanInputs`, not the inline/param policy itself (A1 / C4).
