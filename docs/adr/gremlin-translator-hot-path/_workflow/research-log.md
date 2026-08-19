# Research log

## Initial request

mamy regresje dla wielu translated gremlin traversals vs translator off: [JMH LdbcGremlinTranslatorBenchmark SF1 table]. powiny byc szybsze, trzeva jakos zmienjszyc cene wykonywana na kazdy call przez traversal (zeby cache hit dzialal na takim poziomie jak z translator off) - zaprojektuj jakis sposob na to

## Decision Log

## Surprises & Discoveries

- 2026-08-17T15:50Z [ctx=safe] `GremlinPlanCache` already amortises MATCH *planning* for cache-eligible shapes. JMH invocations rebuild a fresh traversal every call (`computeInTx(t -> GremlinTraversalShapes.knowsFirstNames(t, id).toList())`), so `GremlinToMatchStrategy.apply` re-walks the step list, rebuilds `MatchPlanInputs`, fingerprints that AST, then `GremlinPlanCache.get` + `SelectExecutionPlan.copy`. The walker is the SQL-parse analog and is not cached. Declining-shape deltas (−0.4% veto-before-walk, −4% … −12% walk-then-decline) isolate that front-end; translating high-QPS shapes (−8% … −24%) sit in the same band; 2–3 hop / `groupCount` (−25% … −41%) do not — those are MATCH execution, not compile.
- 2026-08-17T15:50Z [ctx=safe] RID-bearing walks still set `cacheEligible=false` (`StartStepRecogniser` / `HasStepRecogniser` `markRidBearing`, `buildResult` passes `!ridBearing`). Bare `g.V(rid)` already declines. A RID *followed by a hop* still translates and still bypasses the plan cache. Binding the RID as a positional parameter (same as `has("id", n)`) would make that shape cache-eligible.
- 2026-08-17T15:50Z [ctx=safe] `GremlinPlanCache.get` already deep-copies the step chain (`SelectExecutionPlan.copy` → each `ExecutionStepInternal.copy`). `YTDBMatchPlanStep.clone()` copies again; typical `toList()` applies strategies on the iterating instance so the second copy may not fire on the JMH path. One copy per invocation remains on the cache-hit path.
- 2026-08-17T16:10Z [ctx=safe] Points 2 and 4 implemented: `GremlinShapeKey` + `GremlinTranslationTemplate` on a second Guava map in `GremlinPlanCache` (cleared by `invalidate()`). `applyOrDecline` looks up the shape before the walker; TRANSLATE splices the stored template with harvested bindings, DECLINE returns immediately. Binding-count mismatch falls through to a full walk. `YTDBMatchPlanStep` copies a shared template on first `openArming()` (`copyOnOpen`); `apply()` no longer copies a cache-eligible plan. Count / RID-bearing / uncacheable plans stay eager. Union children still copy at build so `MultiPlanMatchStep` does not close a cache entry.

## Open Questions

- 2026-08-17T15:50Z [ctx=safe] Split of walk / fingerprint / plan.copy / MATCH-execute on the SF1 shapes is not measured in-process. A pin in `GremlinToMatchStrategy.apply` + `AbstractMatchPlanStep.openArming` would confirm whether the front-end cache closes the −8%…−24% band before any MATCH-engine work.
- 2026-08-17T15:50Z [ctx=safe] Whether linear (no join-reorder) shapes should keep going through MATCH at all, or decline to native once the front-end is cheap, is a product call. The triangle (+2387%) is the MATCH win; the rest of Phase-1 is currently a loss at SF1.
- 2026-08-17T15:50Z [ctx=safe] New aim lives under `docs/adr/gremlin-translator-hot-path/`, not the completed `gremlin-to-match-translator-design` plan. That older `_workflow/` has 5-commit stamped drift (`d2dfcc2d44` → HEAD); not consumed by this research.

## Baseline and re-validation

## Adversarial gate record
