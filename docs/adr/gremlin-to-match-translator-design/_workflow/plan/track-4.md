<!-- workflow-sha: d2dfcc2d44fabd3ac76c5fd7620f1e6013675ad9 -->
# Track 4: Filtering — predicates (has / hasLabel / hasId, P / Text / TextP)

## Purpose / Big Picture
After this track the Gremlin predicate surface translates: property / label / id predicates (`has` / `hasLabel` / `hasId`), the `P` / `Text` / `TextP` predicate algebra, and the bare presence form `has(key)` (`IS DEFINED`). The step-level logical filters and the plan cache split off to Track 5.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Fills out the predicate adapter with the full `P` set, adds a single `HasStep` recogniser (unpacking property / `~label` / `~id` containers), and adds `Text` / `TextP` translations via the new `SQLEndsWithCondition` / find-mode `SQLMatchesCondition` operators and the collate transform on `SQLContainsTextCondition` (D-TEXT-OPS). The bare presence form `has(key)` recognises via `TraversalFilterStepRecogniser` and emits `IS DEFINED` (D-IS-DEFINED). Predicate comparison values render as inline literals here (today's `MatchLiteralBuilder.toLiteral` behavior); Track 5 flips them to positional parameters when it lands the `GremlinPlanCache` (D5). The step-level logical filters (`and` / `or` / `not` / `where`), the `hasNot(key)` negation, and the sub-walker split off to Track 5 (adversarial A1, user-approved 2026-07-15).

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->
- 2026-07-15 Phase A risk review (R3): `design.md` §"Parameter binding" (≈1283–1286) lists RID arguments as "Bound, out of the key", but binding RIDs as `?` params defeats `MatchExecutionPlanner.promoteStaticRidsFromFilters` (it skips non-early-calculable values), turning `g.V(id)` into a class scan. Track 4 keeps RIDs inline/structural (see Decision Log); reconcile the design in Phase 4.
- 2026-07-15 Phase A risk review (R1) / technical (T6): `design.md` §"Schema polymorphism" is stale (references the deleted `MatchClassFilters`), and `MatchWhereBuilder.classEquals` emits exact `@class =` (excludes subclasses). Polymorphic `hasLabel` needs subclass-inclusive narrowing; Track 4 pins mode-gated behavior (see Decision Log) and flags the design for Phase 4. Overlaps the open BC2 reconciliation item.

## Decision Log
<!-- Continuous-log. -->
- 2026-07-15 (Phase A, R3): **Keep `hasId` RIDs inline/structural, not parameterized.** Inlining preserves `promoteStaticRidsFromFilters` (the direct-RID fast path); parameterizing would demote `g.V(id)` to a class scan. Consequence: id-anchored shapes fingerprint per id set and gain no `GremlinPlanCache` reuse, so the plan cache (Track 5, D5) bypasses RID-bearing traversals to avoid single-use-entry thrash — see Track 5 Decision Log. Track 4 renders all predicate comparison values inline; the switch to positional parameters lands with the cache in Track 5. Contradicts `design.md` §"Parameter binding"; flagged for Phase-4 reconciliation.
- 2026-07-15 (Phase A, R1/T6): **Gate folded-`hasLabel` narrowing on `ctx.polymorphic()`.** Non-polymorphic → `MatchWhereBuilder.classEquals` (exact `@class =`). Polymorphic → subclass-inclusive narrowing via the polymorphic MATCH `class:` node type if the builder supports it, else decline to native (the safe fallback). The polymorphic-vs-non-polymorphic equivalence test pins both. Subclass-inclusive correctness is unvalidated against native today (overlaps the open Phase-4 BC2 item).

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. -->
**Pre-split Phase A, superseded by the A1 split (below).** The Technical and Risk reviews ran against the pre-split whole-track scope (predicates + logical filters + D5). Their predicate-surface fixes carry over to this trimmed track unchanged; the logical-filter and D5 fixes moved to Track 5. The trimmed track re-enters Phase A after the State-0 re-run of the 7-track plan, which re-validates it against the reduced scope.
- [x] Technical: PASS at iteration 3 (8 findings: 2 blockers + 5 should-fix + 1 suggestion; all 8 accepted). Blockers T1/T2 — `has` / `hasLabel` / `hasId` all arrive as one `HasStep` (the g2m translator runs before `YTDBGraphStepStrategy`, `GraphStep` is not a `HasContainerHolder`), so the planned three-recogniser split can't dispatch; collapsed to a single `HasStepRecogniser` branching on `HasContainer` key. Should-fix T3–T6 corrected same-alias AND-composition, `bindParam` on `RecognitionContext`, edge-bearing NOT wiring + the second `manageNotPatterns` precondition, and the polymorphic `classEquals` gate. Iter-2 gate VERIFIED T1–T7 against HEAD and caught T8 (stale `YTDBHasLabelStep`/`HasIdStep` triad in the Purpose headline + plan entry); scrubbed and re-verified.
- [x] Risk: PASS at iteration 2 (6 findings: 5 should-fix + 1 suggestion; all accepted). R4 corrected the technical review's wrong "hard query failure" premise — the eager-build strategy catches `manageNotPatterns`'s `CommandExecutionException` in `apply()`'s `RuntimeException` net → native decline (verified against source). R3 pinned inline/structural RIDs + cache-bypass for RID-bearing traversals (contradicts `design.md` §"Parameter binding" → Phase-4 flag). R1 pinned mode-gated polymorphic `hasLabel`. R2/R5/R6 added AST-round-trip, existing-`CONTAINSTEXT`, and D5-determinism test requirements. Gate iter-2 VERIFIED all six, no regressions.
- Adversarial: RAN at iteration 1 (7 findings: 6 should-fix + 1 suggestion). A1 — realized footprint ~29–38 files, over the ~25 split ceiling, with a clean predicate-surface / logical-filters seam → user-approved SPLIT (2026-07-15), EXECUTED as an inline replan the same day: this track keeps the predicate surface; the logical filters (`and` / `or` / `not` / `where`), `hasNot(key)`, the sub-walker, and `GremlinPlanCache` (D5) moved to Track 5. Track 5 carries the distributed findings — A2 (single `NotStep` recogniser under `NotStep.class`), A3 (post-walk D5 cache key), A4 (sub-context contract), A5 (detached-NOT builder capability), A6 (NOT-decline correction), A7 (no-mutation-on-decline rescope), plus R3 (cache bypass), R4 (eager-build native decline), and R6 (D5 determinism tests). The Technical (T1–T8) and Risk (R1, R2, R5) fixes already applied to this file carry over unchanged. Findings in `reviews/adversarial-iter1.md`.

## Context and Orientation
Track 3 left a `GremlinPredicateAdapter` skeleton (it translated only the `has(...)` inside edge-filter chains). Track 4 makes it the full chokepoint between TinkerPop's predicate algebra and `SQLBooleanExpression`. The mapping is mostly mechanical (`Compare.eq/neq/gt/gte/lt/lte` → YTDB operators 1:1, `Contains.within/without` → `SQLInCondition` / `SQLNotInCondition`), but several corner cases require care and are spelled out in design §"Predicate translation":
- **NULL semantics:** `P.eq(null)` / `P.neq(null)` rewrite to `field IS NULL` / `field IS NOT NULL` (YTDB's `=`/`!=` return FALSE on null operands, diverging from TP).
- **Absent-property semantics:** Track 3's `GremlinPredicateAdapter` already emits `has(k, neq(v))` as `k IS DEFINED AND k <> v` — the MATCH executor treats `<>` on an absent property as true while native excludes absent rows, so raw `<>` over-matches. Track 4 preserves this and audits the other comparisons (`gt` / `gte` / `lt` / `lte`) for the same divergence, adding the `IS DEFINED` guard only where executor and native disagree.
- **Singleton-collection equality:** `P.eq([a])` / `P.neq([a])` (size-1 literal) **decline** under D3 — `QueryOperatorEquals` auto-unboxes singletons against scalars, and field cardinality is unknown at translation time in schema-less/mixed classes. Size 0 and ≥2 translate normally.
- **`between`:** Gremlin `between` is right-exclusive `[lo, hi)`; YTDB `SQLBetweenCondition` is closed `[lo, hi]`. Translate to `AND(>=lo, <hi)`, never `SQLBetweenCondition`. `inside`/`outside` are open both ends → `AND(>lo, <hi)` / `OR(<lo, >hi)`.

The g2m translator runs **before** `YTDBGraphStepStrategy` (`YTDBGraphStepStrategy.applyPrior()` returns `{GremlinToMatchStrategy.class}`), and `GraphStep` is not a `HasContainerHolder`, so at translation time no GraphStep fold has happened, no `YTDBHasLabelStep` exists, and `hasLabel` is never folded onto the start step. `has(key,value)`, `hasLabel(label)`, and `hasId(id)` therefore all arrive as a single `HasStep` distinguished only by `HasContainer` key — a property key, `T.label.getAccessor()` (`~label`), or `T.id.getAccessor()` (`~id`). One `HasStepRecogniser` iterates `HasStep.getHasContainers()` and branches on the key: `~label` → `MatchWhereBuilder.classEquals` **only when `ctx.polymorphic()` is false** (`classEquals` emits an exact `@class =` that excludes subclasses, so polymorphic mode must decline or emit a subclass-inclusive predicate); `~id` → an `@rid IN [...]` WHERE clause on the alias filter (the single-id case relies on the planner's `promoteStaticRidsFromFilters` collapse — there is no `aliasRids`/`aliasClasses` slot on the context); a property key → the predicate adapter. This container-key branching runs **before** the adapter's reserved-`~`-key decline. `MatchWhereBuilder.classEquals` has no production caller yet — Track 4's folded `hasLabel` is its first (Track 3's rework deleted the interim `MatchClassFilters` helper). `has(key)` desugars (TinkerPop 3.8.1) to `TraversalFilterStep(__.values(key))` — it does not land on `HasStep`, so it bypasses `HasStepRecogniser` and routes through the new `TraversalFilterStepRecogniser` Case A, emitting `IS DEFINED` (not `IS NULL`, which over-matches null-valued properties). `hasNot(key)` desugars to `NotStep(__.values(key))`, the same `final` class as logical `not(...)`, so its handling moves wholly to Track 5's single `NotStep` recogniser (adversarial A2); Track 4 owns only the `has(key)` presence form.

Track 4's recognisers implement the post-Track-3 contract `Outcome recognize(StepCursor, RecognitionContext)` (head via `cursor.take()`, trailing shape via `takeIf` / `takeWhile`, returning `ACCEPTED` / `DECLINE`), the model Track 3's rework left in place of the retired `boolean recognize(Step, WalkerContext)` + manual `stepIndex` form.

Same-alias filter contributions must **AND-compose**, not overwrite. Track 4 routinely contributes two WHERE clauses to one alias — `g.V(ids).has(k,v)` (start `@rid IN`, then `has`) and `hasLabel(L).has(k,v)` — so `putAliasFilter` (and the `buildResult` merge) must AND an incoming clause with any existing one via `MatchWhereBuilder.and` rather than replace it. An overwrite silently drops the earlier filter and returns a wrong (over-large) multiset. Track 5's `where` / `and` contributions reuse this same AND-composition.

## Plan of Work
1. **Full predicate adapter** (`GremlinPredicateAdapter`): the complete `P` set (`Compare`, `Contains`, composite `P.and/or/not`, `inside`/`outside`/`between` decompositions), the NULL and singleton-collection rules above, and custom-predicate decline (`getBiPredicate()` not `Compare`/`Contains`/`Text`).
2. **Single `HasStepRecogniser`** (keyed on `HasStep.class`; D9 permits one recogniser per exact runtime class, and `has` / `hasLabel` / `hasId` all produce `HasStep`). It iterates `HasStep.getHasContainers()` and branches on container key: a property key → the predicate adapter (`has(key,value)` / `has(key,predicate)`, multiple property containers AND together); `~label` → `MatchWhereBuilder.classEquals` gated on `ctx.polymorphic()` false (decline or a subclass-inclusive predicate otherwise); `~id` → an `@rid IN [...]` WHERE on the alias filter (single and multi both route through `@rid IN`, the single case collapsed by the planner's `promoteStaticRidsFromFilters`). The `~label` / `~id` interception runs before the adapter's reserved-key decline. No separate `HasLabelStepRecogniser` / `HasIdStepRecogniser` — neither `YTDBHasLabelStep` nor a `HasIdStep` class exists at translation time.
3. **Presence form:** `TraversalFilterStepRecogniser` Case A (`has(key)` → `MatchWhereBuilder.isDefined`) via Track 1's factory (D-IS-DEFINED). `hasNot(key)` moves to Track 5 (A2 — it desugars to `NotStep`, the class Track 5's single `NotStep` recogniser owns).
4. **`Text` / `TextP` translation** (D-TEXT-OPS): `containing` / `notContaining` → `SQLContainsTextCondition` (+ collate transform, making SQL `CONTAINSTEXT` collation-aware too); `startingWith` → range `field >= p AND field < p⁺` via `MatchWhereBuilder.startsWith` (index-aware, collation-respecting; declines on empty `p` or undeclared/schema-less property); `endingWith` → new `SQLEndsWithCondition` AST node; `regex` → find-mode flag on `SQLMatchesCondition`. New AST nodes report `isIndexAware()==false`; `regex` stays case-sensitive. The new `SQLEndsWithCondition` and the `SQLMatchesCondition` find-mode flag must round-trip through `copy()` and `toGenericStatement()` — the SQL engine deep-copies plans on `clone()` / cache-get, so a dropped field is a silent wrong-result bug (R2); Track 5's `GremlinPlanCache` relies on the same round-trip. The `SQLContainsTextCondition` collate transform changes existing SQL `CONTAINSTEXT` semantics on `ci`-collated properties too, not only Gremlin, so carry a regression check on existing SQL `CONTAINSTEXT` (R5).

The logical filters (`and` / `or` / `not` / `where`), the `hasNot(key)` negation, the `SubTraversalPredicateAdapter` + sub-walker, and the `GremlinPlanCache` (D5) — the former steps 5–7 — moved to Track 5 in the A1 split. This track ends at the predicate surface.

## Concrete Steps
<!-- Phase A placeholder. -->

## Episodes
<!-- Continuous-log. Empty at Phase 1. -->

## Validation and Acceptance
- `has(key,value)` / `has(key,predicate)` / `has(label,key,value)` / `hasLabel` / `hasId` (single + multi) translate to the same multiset as native.
- `hasLabel(L)` narrows via `classEquals` in non-polymorphic mode; in polymorphic mode it still matches subclasses of `L` (via decline-to-native or a subclass-inclusive predicate) — a polymorphic-vs-non-polymorphic equivalence test pins both.
- Two filters on one alias AND-compose: `g.V(id1,id2).has("age",30)` returns only the age-30 vertices among the two ids (not every age-30 vertex), and `hasLabel(L).has(k,v)` intersects both.
- `eq(null)` / `neq(null)` against a null-valued property match native; `eq([a])` / `neq([a])` (size-1) decline and fall back to native; `eq([a,b])` / `eq([])` translate and match native.
- `between` returns the right-exclusive multiset (no off-by-one on the high bound); `inside` / `outside` match native.
- `containing` / `startingWith` / `endingWith` / `regex` (and `not*` variants) translate and match native; `startingWith` uses an index range scan; collation-aware predicates honor `default` (case-sensitive) and `ci` (case-insensitive) properties; non-String fields decline.
- `has(key)` → `IS DEFINED` matches native on absent and present-with-null properties (distinct from `IS NULL`). (`hasNot(key)` → `IS NOT DEFINED` is Track 5.)
- An inlined-RID shape (`g.V(id)`) takes the direct-RID fetch, not a class scan.
- The new `Text` / `TextP` AST nodes survive `copy()` / `toGenericStatement()` round-trips (SQL plan-clone equivalence); existing SQL `CONTAINSTEXT` on a `ci`-collated property still matches its pre-change multiset.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — acceptance lines. -->

## Idempotence and Recovery
<!-- Phase A placeholder. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Often empty. -->

## Interfaces and Dependencies
**In scope (new):** full `GremlinPredicateAdapter`; a single `HasStepRecogniser` (unpacks property / `~label` / `~id` containers) and `TraversalFilterStepRecogniser` (the `has(key)` presence form); `SQLEndsWithCondition` AST node + find-mode flag on `SQLMatchesCondition` (D-TEXT-OPS); predicate-equivalence / NULL / collection / string-predicate tests.
**In scope (modified):** `SQLContainsTextCondition` (collate transform); `MatchWhereBuilder` — `startsWith` already exists, while `endsWith` / `matchesRegex` are **new** here (alongside `SQLEndsWithCondition` and the `SQLMatchesCondition` find-mode); `WalkerContext` (AND-composing `putAliasFilter` + `buildResult` merge per the same-alias rule) plus the matching `RecognitionContext` accessors; the registry registration sites for the two recognisers.
**Out of scope:** the logical filters (`and` / `or` / `not` / `where`), the `hasNot(key)` negation, the sub-walker, and the `GremlinPlanCache` (D5) — all Track 5; projections / labels / dedup / order / aggregates (Track 6); union + list-shaping (Track 7); the singleton-collection schema-aware rewrite and edge-bearing OR (Phase 2 — design §"Out of scope"); grammar changes (the new operators are AST + evaluator only, reachable programmatically).
**Inter-track dependencies:** depends on Track 3 (predicate-adapter skeleton, `GremlinPatternAssembler`, the equivalence fixture) and Track 1 (`isDefined`, `MatchWhereBuilder`). Supplies the full predicate algebra to Track 5 (logical filters reuse it for `where(P)` and sub-predicates) and Track 6 (by-modulator value resolution reuses it).
**Signatures:** `P.getBiPredicate()`; `HasContainer{key, predicate}`; `QueryOperatorEquals.equals` (lines 63-69 unbox, 71-73 null short-circuit); `MatchExecutionPlanner.promoteStaticRidsFromFilters` (the direct-RID fast path `hasId` inlining preserves).

## Invariants & Constraints
<!-- Combined per-track invariants + constraints (conventions-execution.md §2.1 §14).
Added by workflow migration (#1145). Strategic invariants/constraints for this track remain
in implementation-plan.md § High-level plan (Architecture Notes) and this track's ## Decision
Log — the conservative migration retained the plan Architecture Notes rather than folding them here. -->

## Base commit
<!-- Phase B records the HEAD SHA here at session start; Phase C reads it to compute the
cumulative track diff (conventions-execution.md §2.1 §15). Added by workflow migration (#1145). -->
