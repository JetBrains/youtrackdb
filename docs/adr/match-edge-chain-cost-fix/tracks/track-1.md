# Track 1: Chain detection helper

## Progress
- [x] Review + decomposition
- [x] Step implementation (2/2 complete)
- [x] Track-level code review

## Base commit
`cca739f215debc26bb82422ed9aaff3566d2e590`

## Reviews completed
- [x] Technical

## Steps

- [x] Step 1: Introduce `ChainedTarget` record + `resolveChainedTarget` helper with structural detection rule + rejection-case unit tests
  - [x] Context: unavailable
  > **What was done:** Added `MatchExecutionPlanner.ChainedTarget` record (`effectiveTargetAlias`, `effectiveTargetClass`) and a package-private static `resolveChainedTarget(edge, neighbor, visitedEdges, aliasClasses, session)` helper placed immediately after `resolveTargetClass`. The helper applies the full structural detection rule from the plan (null guards on first `item`/`method`, lower-cased first-method whitelist `oute`/`ine`/`bothe`, `neighbor.out.size()==1` + not-visited, `neighbor.in.size()==1` + identity match against `edge`, lower-cased second-method whitelist `inv`/`outv`/`bothv`). On match it returns `Optional.of(new ChainedTarget(downstreamEdge.in.alias, null))`; the class field is intentionally left null in Step 1 (Step 2 fills it in). Added 22 rejection-case + 4 happy-path tests in `MatchExecutionPlannerMutationTest`. Review fix commit expanded the first- and second-method whitelist rejection suites (`in`/`both` near-misses, edge-hop in vertex position, unknown name, separate `item==null` test) and clarified the defense-in-depth Javadoc on the identity check.
  > **Key files:**
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchExecutionPlanner.java` (modified) — added `Optional` import, `ChainedTarget` record, `resolveChainedTarget` helper.
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchExecutionPlannerMutationTest.java` (modified) — added `LinkedHashSet` import, `resolveChainedTarget_*` tests (26 total), `ChainFixture` record + `buildChain` helpers.
  > **Files:**
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchExecutionPlanner.java` — add `ChainedTarget` record and `resolveChainedTarget` static method. Class placeholder: for this step the record's `effectiveTargetClass` field is always `null` (class inference lands in Step 2).
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchExecutionPlannerMutationTest.java` — add rejection-case unit tests following the existing `mockEdgeWithMethod` / `mockEdgeWithMethodAndParam` pattern (:517-558).
  >
  > **Helper contract (from plan Track 1):**
  > - Signature: `static Optional<ChainedTarget> resolveChainedTarget(PatternEdge edge, PatternNode neighbor, Set<PatternEdge> visitedEdges, Map<String, String> aliasClasses, DatabaseSessionEmbedded session)`.
  > - `aliasClasses` and `session` are accepted but unused in Step 1 (class field is always null). Wiring them in the signature now avoids a method-rename commit in Step 2.
  > - Pre-check: `edge.item != null && edge.item.getMethod() != null` → else `Optional.empty`.
  > - Method-name check: `getMethodNameString()` lower-cased (`Locale.ENGLISH`) is `oute`/`ine`/`bothe`. Mirror the pattern at :2303, :2359.
  > - `neighbor.out.size() == 1` and single edge not in `visitedEdges`.
  > - `neighbor.in.size() == 1` and single incoming edge IS `edge` (identity, not equals — PatternEdge has no equals override; see plan T7 note).
  > - Second-hop method-name check: `inv`/`outv`/`bothv` (null-safe lookup on downstream edge's method).
  > - On match: extract `effectiveTargetAlias = downstreamEdge.in.alias`; return `Optional.of(new ChainedTarget(alias, null))`.
  >
  > **Rejection-case tests (MatchExecutionPlannerMutationTest):**
  > 1. `edge.item == null` → empty.
  > 2. `edge.item.getMethod() == null` → empty.
  > 3. First edge method is `out` (not `oute`) → empty.
  > 4. First edge method name is `null` from `getMethodNameString()` → empty.
  > 5. `neighbor.out.size() == 0` → empty.
  > 6. `neighbor.out.size() == 2` → empty.
  > 7. Single out-edge is in `visitedEdges` → empty.
  > 8. `neighbor.in.size() == 0` → empty.
  > 9. `neighbor.in.size() == 2` → empty (user joined intermediate alias).
  > 10. Single in-edge is a different edge (identity mismatch) → empty.
  > 11. Second-hop method is `out` (not `outv`) → empty.
  > 12. Second-hop method-name null → empty.
  > 13. Reverse traversal: `neighbor = edge.out` (source side) with no `inV` continuation → empty (covers T1's structural-rejection guarantee).
  >
  > **Happy-path sanity tests (Step 1 only — class field always null):**
  > - outE→inV with valid structure → `Optional.of(ChainedTarget("tag", null))`.
  > - inE→outV with valid structure → `Optional.of(ChainedTarget("post", null))`.
  > - bothE→bothV with valid structure → `Optional.of(ChainedTarget("vertex", null))`.
  >
  > **Implementation notes:**
  > - Place the record and helper as `package-private static` members of `MatchExecutionPlanner`, near `resolveTargetClass` (:2511) for proximity.
  > - Use `Locale.ENGLISH` for all `toLowerCase(…)` calls (matches `parseDirection` at :2935).
  > - The helper is pure; no state mutation, no schema calls yet.
  >
  > **Acceptance:**
  > - `./mvnw -pl core spotless:apply` clean.
  > - `./mvnw -pl core test -Dtest=MatchExecutionPlannerMutationTest` green.
  > - 85% line / 70% branch coverage of changed lines via `coverage-gate.py`.

- [x] Step 2: Add class-inference precedence to `resolveChainedTarget` + direction-variant happy-path unit tests
  - [x] Context: unavailable
  > **What was done:** Populated `ChainedTarget.effectiveTargetClass` per the plan's precedence rule: (1) `aliasClasses.get(effectiveTargetAlias)` first, (2) fallback to edge-schema derivation via new private helper `inferDownstreamVertexClassFromEdge`. The helper hard-codes the chain direction → linked-property mapping: `oute` → edge's `in` linked class, `ine` → edge's `out` linked class, `bothe` → null (no single endpoint is uniquely "downstream"). `aliasClasses` is `@Nullable` and the helper defensively returns null on null session, missing edge class name, absent edge class in schema, null schema snapshot, null linked property, or null linked class. Added 15 happy-path tests (precedence-1 per direction × precedence-2 per direction × ordering precedence-1-wins-over-schema for both outE and inE × all five null-fallback paths for precedence-2).
  > **What was discovered:** The plan recommended inlining the schema-navigation code (~8 lines). In practice the full path — bothE short-circuit + null-session guard + `extractEdgeClassName` + schema/edgeClass/linkedProp/linkedClass null-chain — came to ~25 lines, so I extracted `inferDownstreamVertexClassFromEdge` to keep `resolveChainedTarget` focused on structural detection. Deviation is local to Track 1 and doesn't affect Track 2 or Track 3 contracts.
  > **Key files:**
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchExecutionPlanner.java` (modified) — precedence logic in `resolveChainedTarget` + new private helper `inferDownstreamVertexClassFromEdge`.
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchExecutionPlannerMutationTest.java` (modified) — 15 happy-path tests for the Step 2 precedence matrix.
  > **Files:**
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchExecutionPlanner.java` — populate `ChainedTarget.effectiveTargetClass` via the precedence rule.
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchExecutionPlannerMutationTest.java` — add happy-path tests exercising both precedence branches for all three direction variants.
  >
  > **Precedence logic (from plan Track 1):**
  > 1. `aliasClasses.get(effectiveTargetAlias)` — if non-null, use directly. This is the normal path for outE→inV / inE→outV because `addAliases` pre-populates via `inferClassFromEdgeSchema` (called at :4518). Also the only path for `bothE→bothV` when the user supplied `{class: ...}`.
  > 2. Fallback — defensive for while-expression aliases skipped by `whileAliases` filter at :4495: derive from the first edge's class name + direction. Reuse `extractEdgeClassName(SQLMethodCall)` at :2956. Map direction → linked property: `oute` → `in`, `ine` → `out`, `bothe` → no inference (returns null). Mirror `resolveTargetClass`'s explicit-alias branch at :2517-2520 style-wise.
  >
  > Schema lookup requires a non-null `session` — null-guard it (tests will exercise the null-session path by asserting fallback returns null class when aliasClasses miss + session null).
  >
  > **Happy-path tests (direction × precedence matrix):**
  > 1. outE→inV with `aliasClasses.get("tag") = "VITag"` → class = "VITag" (precedence 1).
  > 2. outE→inV with empty `aliasClasses`; edge class `VIHasTag` has `in LINK VITag` → class = "VITag" (precedence 2).
  > 3. inE→outV with `aliasClasses.get("post") = "VIPost"` → class = "VIPost" (precedence 1).
  > 4. inE→outV with empty `aliasClasses`; edge class `VIHasTag` has `out LINK VIPost` → class = "VIPost" (precedence 2).
  > 5. bothE→bothV with `aliasClasses.get("vertex") = "VITag"` → class = "VITag" (precedence 1, critical for Track 3 test 4).
  > 6. bothE→bothV with empty `aliasClasses` → class = null (precedence 2 explicitly returns null for bothE; verifies design contract).
  > 7. Edge class name missing (`.outE()` without arg) + empty aliasClasses → class = null (precedence 2 fallback: `extractEdgeClassName` returns null).
  > 8. Edge class name unknown in schema + empty aliasClasses → class = null (precedence 2 schema lookup fails).
  > 9. Edge class exists but linked property absent → class = null.
  > 10. Null `session` + empty `aliasClasses` → class = null (defensive null-guard).
  >
  > **Test infrastructure:**
  > - Reuse `mockEdgeClassWithVertexLinks(...)` at :491 for schema mocks (existing helper populates `out LINK X` and `in LINK Y`).
  > - `DatabaseSessionEmbedded` is mocked via Mockito — see existing test setup at :60-100.
  >
  > **Implementation notes:**
  > - Keep the fallback lookup in the same method body (no new helper needed; the schema-navigation code is ~8 lines).
  > - Add a one-line code comment: "Precedence fallback: defensive for while-expression aliases skipped by addAliases (MatchExecutionPlanner.java:4495)."
  >
  > **Acceptance:**
  > - `./mvnw -pl core spotless:apply` clean.
  > - `./mvnw -pl core test -Dtest=MatchExecutionPlannerMutationTest` green.
  > - 85% line / 70% branch coverage of changed lines.
  > - Helper is fully functional — Track 2 can wire it into the sort loop without further helper changes.
