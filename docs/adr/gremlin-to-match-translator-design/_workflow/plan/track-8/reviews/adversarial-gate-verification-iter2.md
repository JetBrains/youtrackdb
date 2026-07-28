<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
verdicts:
  - {id: A1, verdict: VERIFIED}
  - {id: A2, verdict: VERIFIED}
  - {id: A3, verdict: VERIFIED}
  - {id: A4, verdict: VERIFIED}
  - {id: A5, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

# Adversarial gate verification — Track 8, iteration 2

All five iter1 findings (blockers A1/A2/A3, should-fix A4, suggestion A5) verify against the
amended `plan/track-8.md`. No new findings. Gate result: **PASS**.

Reference-accuracy caveat: mcp-steroid PSI (`steroid_execute_code`) times out in this repo, so
symbol re-checks below rest on grep plus full declaration reads of the named files. The one
load-bearing code fact new to this pass — `MultipleExecutionStream` as a lazy one-live-stream
concatenator — was verified by a direct source read, not grep.

## Findings

## Verification certificates

#### Verify A1: post-union suffix policy (blocker)
- **Original issue**: The track never said what happens to steps after the `UnionStep`; the walker
  keeps dispatching and the registered count/dedup/order/range recognisers do not distribute over
  concatenation — silent wrong multiset.
- **Fix applied**: DR-U4 pins union as the last recognised step in Track 8 — after consuming the
  `UnionStep` the recogniser declines unless the cursor is exhausted; Track 9 relaxes this only for
  the sanctioned list-shaping terminators.
- **Re-check**:
  - Track-file location: Decision Log DR-U4 (track-8.md:33); Plan of Work item 3 (:63, "a
    non-exhausted cursor after the union (union is the last recognised step — DR-U4)"); Purpose
    (:5, "recognises only when it is the last recognised step"); Surprises bullet (:22); Validation
    (:74, `g.V().union(out(), in()).count()` and a `dedup` variant decline).
  - Current state: the wrong-multiset gap is closed at claim time — a non-exhausted cursor after
    the union declines, so no suffix recogniser can run against the prefix-only pattern builder.
  - Criteria met: the iter1 proposed fix verbatim (last-recognised-step pin + the two decline
    tests + the Track 9 relaxation boundary).
- **Regression check**: Validation's "any non-list-shaping step after the union declines" reads as
  the durable (post-Track-9) form of the rule; in Track 8 itself the list-shaping terminators have
  no recognisers, so a `fold` suffix declines anyway — the two statements are consistent in both
  phases. Clean.
- **Verdict**: VERIFIED

#### Verify A2: agreement gate = full projection contract (blocker)
- **Original issue**: enum-only `BoundaryOutputType` agreement passes children with divergent
  `ResultShaping` (presence-key drop against the wrong key) or divergent boundary aliases (null
  payloads), because the base holds exactly one shaping (:117) and one alias (:104).
- **Fix applied**: DR-U3 pins the gate as equal `BoundaryOutputType` + equal return class +
  record-equal `ResultShaping` + one canonical boundary alias rewritten onto every child's RETURN
  at translation time.
- **Re-check**:
  - Track-file location: Decision Log DR-U3 (track-8.md:32); Plan of Work item 3 (:63,
    "full-projection-contract + canonical-alias agreement gate"); Context and Orientation (:46);
    Purpose (:5); Surprises bullet (:21); Validation (:74) plus Plan of Work item 4 (:64,
    "projection-contract-mismatch decline and canonical-alias parity").
  - Current state: both constructible failures from C3 (`values("name")` vs `values("age")`
    presence keys; hop-count alias divergence) are excluded by the widened gate, and both test
    scenarios the fix demanded are pinned.
  - Criteria met: full-contract gate + canonical alias + both tests — the iter1 proposed fix in
    full. Code re-check: `ResultShaping` is a record (ResultShaping.java:37), so record equality is
    available as the gate expression; the base's single `shaping`/`boundaryAlias` fields confirmed
    at AbstractMatchPlanStep.java:117/:104.
- **Regression check**: "canonical boundary alias reused from the base" (:46) and "rewritten onto
  every child's RETURN at translation time" (DR-U3) describe the same mechanism from the two ends —
  no contradiction. Clean.
- **Verdict**: VERIFIED

#### Verify A3: re-point from per-plan-reopen to `MultipleExecutionStream` (blocker)
- **Original issue**: the Track 7 episode hand-off prescribed per-plan NEW/REARMED reopen, which
  rebuilds `shapedPayloads` per child (Track 9's `union().fold()` folds per child — multiset
  violation) and needs a base advance seam that does not exist (private lifecycle, `reset()`
  aliasing).
- **Fix applied**: DR-U1 re-points the realization to one `MultipleExecutionStream` supplied
  through the existing `startPlanStream()` hook, over an `ExecutionStreamProducer` that lazily
  opens each child against its own session-rebound context; one base arming spans all N plans.
- **Re-check**:
  - Track-file location: Decision Log DR-U1 (track-8.md:30); Context and Orientation (:46);
    Surprises bullet (:20, explicitly recording the overturned Track 7 prescription at
    plan/track-7.md:25–26,96); Plan of Work item 1 (:61); Validation (:77, `union().fold()` folds
    the whole union — the Track 9 readiness note the fix demanded); Inter-track dependencies (:94).
  - Current state: the per-plan-reopen wording is gone everywhere; the design is the concatenating
    stream, and the lifecycle acceptance lines (one live stream, exception never opens N+1,
    close-all-including-un-run) are kept as the fix required.
  - Criteria met: direct source read of
    `core/.../sql/executor/resultset/MultipleExecutionStream.java` confirms the lazy one-live-stream
    concatenator (closes the drained child before opening the next, opens on demand, an exception
    in a child's `hasNext`/`next` propagates before the producer advances, `close()` closes current
    stream + producer). Production users confirmed by grep: `ParallelExecStep`,
    `FetchFromIndexStep` (both named in DR-U1), plus two edge-fetch steps. `startPlanStream()` is a
    protected abstract hook (AbstractMatchPlanStep.java:805); the private lifecycle
    (`state` :170, `inputParameters` install :422–423) matches the DR's "no advance seam" rationale,
    and the base skips parameter install on an empty map, so DR-U2's per-child-context install does
    not collide.
- **Regression check**: the fix's alternative branch (base in modified scope) is correctly carried
  as a conditional — `AbstractMatchPlanStep` **only if** decomposition finds per-child context
  threading needs a seam (:92, T1/R1), with the residual (`openArming` rebinds the session once,
  `rowProjectionSource` captures ctx once) flagged in the Reference-accuracy note (:48) rather than
  hidden. Clean.
- **Verdict**: VERIFIED

#### Verify A4: modified-scope inaccuracies (should-fix)
- **Original issue**: In scope (modified) pointed registration at the strategy (registry lives in
  `GremlinStepWalker.PRODUCTION_RECOGNISERS`), carried an already-done broaden-scan sub-item, and
  omitted the real strategy splice/build seams and the `TranslationResult` carrier; walk-time plan
  building sat outside the DDL guard and foreclosed the cache-policy pin.
- **Fix applied**: In scope (modified) rewritten (track-8.md:92): `GremlinStepWalker` for
  registration; `GremlinToMatchTranslator`/`TranslationResult` multi-plan carrier;
  `GremlinToMatchStrategy` `applyTranslation`/`buildPlan`/`replaceAllStepsWithBoundary` multi-plan
  branch; broaden-scan dropped ("no edit needed — technical T4"). DR-U2 pins the seam as sub-walk →
  per-child `MatchPlanInputs`, strategy builds N plans inside the guarded path; DR-U5 pins the
  cache policy to whole-union bypass (`cacheEligible = false`).
- **Re-check**:
  - Code re-check: `PRODUCTION_RECOGNISERS` lives in
    `translator/strategy/GremlinStepWalker.java` (grep-confirmed); `TranslationResult` carries one
    `@Nonnull MatchPlanInputs` plus an existing `cacheEligible` boolean documented as the
    RID-bypass flag (GremlinToMatchTranslator.java:69–90), so DR-U5's "mirroring the RID-bypass
    path" is accurate and `GremlinPlanCache`'s absence from the modified list is correct under the
    bypass policy.
  - Criteria met: all four elements of the iter1 proposed fix (registration target, dropped
    sub-item, named splice/build seams + carrier, cache policy pinned to one of the two coherent
    options). Surprises bullet (:24) records the correction.
- **Regression check**: the walk-time-plan-build wording is gone — Plan of Work item 3 has the
  recogniser yielding per-child `MatchPlanInputs`, item 2 has the strategy building inside the
  guarded path. Clean.
- **Verdict**: VERIFIED

#### Verify A5: nested union in a child (suggestion)
- **Original issue**: a nested `UnionStep` inside a child was unpinned — the child→one-plan path
  cannot represent it.
- **Fix applied**: DR-U4 pins the Phase 1 behavior: a child whose sub-walk hits a nested
  `UnionStep` declines the whole union; flattening is a Phase 2 option.
- **Re-check**:
  - Track-file location: Decision Log DR-U4 (track-8.md:33); Plan of Work item 3 (:63, "a nested
    union inside a child (DR-U4)" in the decline list); Purpose (:5); Validation (:74, nested-union
    decline test); Out of scope (:93, "nested-union flattening (Phase 2 …)").
  - Criteria met: decline pinned with a test, flattening deferred — the iter1 proposed fix exactly.
- **Regression check**: none — the pin only narrows the recognised shape. Clean.
- **Verdict**: VERIFIED

## Summary

PASS — all three blockers (A1, A2, A3) and both lower-severity findings (A4, A5) VERIFIED; no new
findings.
