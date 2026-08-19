<!-- MANIFEST
findings: 5   severity: {blocker: 0, should-fix: 2, suggestion: 3}
index:
  - {id: CQ1, sev: should-fix, loc: HasStepRecogniser.java:41, anchor: "### CQ1 ", cert: n/a, basis: "class Javadoc still describes non-String Text decline and a Text type gate; Step 3 pivoted to strict-throw and startingWith-only routing"}
  - {id: CQ2, sev: should-fix, loc: PredicateTraversalEquivalenceTest.java:342, anchor: "### CQ2 ", cert: n/a, basis: "test Javadoc describes subclass-sweep decline; body asserts strict CONTAINSTEXT throw parity"}
  - {id: CQ3, sev: suggestion, loc: SQLEndsWithCondition.java:1, anchor: "### CQ3 ", cert: n/a, basis: "SQLEndsWithCondition and SQLStartsWithCondition share ~600 lines of near-identical AST boilerplate after TextCollationResolver extraction"}
  - {id: CQ4, sev: suggestion, loc: HasStepRecogniser.java:73, anchor: "### CQ4 ", cert: n/a, basis: "recognize() ~95 lines with two container passes mixes label resolution, translation, and contribution"}
  - {id: CQ5, sev: suggestion, loc: HasStepRecogniserTest.java:293, anchor: "### CQ5 ", cert: n/a, basis: "recogniser-test harness copied across four test classes; Track 5-7 will re-copy again"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
cert_index: []
flags: [CONTRACT_OK]
-->

## Findings

### CQ1 [should-fix] HasStepRecogniser Javadoc still describes the Step 2 non-String Text decline model

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/HasStepRecogniser.java` (lines 41-42, 48-49, 94)

**Issue**: The class Javadoc and the first-pass comment still say property keys route through the adapter "with the schema-aware non-String Text type gate" and list "a non-String Text" among containers that decline the whole step. Step 3 pivoted the design: `PropertyTypeGate` / `RecognitionContext.isDeclaredStringProperty` now route only `startingWith` between the index-aware prefix range and the strict full-scan node; every other `Text` / `TextP` predicate translates in strict mode and throws at execution on a present non-String operand, matching native rather than declining. `GremlinPredicateAdapter`'s class Javadoc was updated for this pivot; `HasStepRecogniser` was not. A reviewer tracing the translate-all-then-contribute contract from this recogniser's docs will look for a decline path that no longer exists and miss the strict-throw seam Track 5 must preserve.

**Suggestion**: Rewrite the three stale passages to match the adapter: property keys route through `GremlinPredicateAdapter.toFilter(container, typeGate)` where `typeGate` keys `startingWith` routing on the step's `~label` class (if any); untranslatable containers are reserved keys, multi-label / conflicting `~label`, unconvertible `~id`, and size-1 collection equality — not non-String Text.

### CQ2 [should-fix] Stale test Javadoc in PredicateTraversalEquivalenceTest contradicts the assertion

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/PredicateTraversalEquivalenceTest.java` (lines 342-351, method `polymorphicNonStringTextOnSubclassOnlyProperty_translatesStrict_andBothThrow`)

**Issue**: The block Javadoc still describes the superseded Step 3 BG1 design — a polymorphic subclass type-gate sweep that declines when a non-String property is declared only on an included subclass, with "the gate must decline instead, so both runs throw." The method name, inline comments (lines 361-364), and body call `assertTranslatedAndNativeThrow` on a **recognized** strict `CONTAINSTEXT` that throws on the Integer operand. The test is correct; the Javadoc is wrong and will mislead anyone using it as the contract for polymorphic hierarchy + Text behavior. CLAUDE.md's "keep comments in sync" rule applies to test descriptions.

**Suggestion**: Replace the Javadoc with the strict-throw contract the body exercises: polymorphic `hasLabel(Person)` includes the `Employee` row; strict `CONTAINSTEXT` throws on the Integer `age` exactly where native throws; no subclass sweep or whole-traversal decline is involved because the type gate no longer gates Text predicates.

### CQ3 [suggestion] SQLEndsWithCondition and SQLStartsWithCondition duplicate most AST boilerplate

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLEndsWithCondition.java` (new, ~299 lines); `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLStartsWithCondition.java` (new, ~304 lines)

**Issue**: Step 1 extracted collation resolution and strict operand checking into `TextCollationResolver`, but the two new hand-written nodes still carry near-verbatim copies of the sibling-node scaffold: strict flag field + Javadoc, dual `evaluate` overloads, `copy` / `equals` / `hashCode` / `splitForAggregation`, index-awareness stubs, and the inherited `needsAliases` negated-operand quirk. Only the comparison helper (`endsWithCollated` vs `startsWithCollated`) and the `toGenericStatement` token differ. Future round-trip or strict-flag fixes must land twice.

**Suggestion**: Acceptable if the team treats each `SQL*Condition` as a standalone copy (the established parser-node pattern). If maintenance cost matters, consider a package-private abstract base holding the shared strict-flag plumbing and boilerplate methods, with subclasses supplying only the comparison predicate and generic-statement token. Not blocking — `TextCollationResolver` already deduplicated the hot path.

### CQ4 [suggestion] HasStepRecogniser.recognize mixes three concerns across ~95 lines

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/HasStepRecogniser.java` (lines 73-166)

**Issue**: `recognize` runs a label-resolution first pass (`singleEqLabel`, conflicting-label checks, `isVertexClass`), a translation second pass (`translateHasId`, adapter dispatch), and a contribution block (`addNode`, `classEquals`, `putAliasFilter`). The translate-all-then-contribute ordering is correct and well-commented, but the method exceeds the ~40-line guideline and makes the no-mutation-on-decline boundary harder to scan — every early `return Outcome.DECLINE` in the first pass must be confirmed to precede any `whereExprs` accumulation or `addNode` call.

**Suggestion**: Extract the first pass into a small result type or helper (e.g. `resolveLabelClass(containers, ctx)` returning the validated class name or a decline sentinel) and leave `recognize` as gates → resolve label → translate containers → contribute. The helper is independently testable and mirrors the peek-ahead extraction suggested for `EdgeStepRecogniser` in Track 3's code-quality review.

### CQ5 [suggestion] Recogniser-test harness copied across four test classes

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/HasStepRecogniserTest.java` (helpers, line 293); `TraversalFilterStepRecogniserTest.java` (helpers, line 130); plus the pre-existing copies in `EdgeHopRecogniserTest`, `VertexStepRecogniserTest`, `VertexHopRecogniserTest`

**Issue**: The new recogniser tests duplicate `BOUNDARY_ALIAS`, `TRANSPARENT`, `cursorAfterStart`, `renderBoundaryFilter`, and a `contextWithStartBoundary` variant. `countBoundarySteps` appears separately in `PredicateTraversalEquivalenceTest` and `GremlinToMatchSmokeTest`. The plan sequences one recogniser per remaining track (Tracks 5–7), so each will likely copy this block again. The copies are correct and isolated; the cost is contract drift when the pre-seeded boundary shape changes.

**Suggestion**: Lift shared constants and helpers into a package-private test util or thin base (as Track 3 code-quality CQ5 and Step 3 test-structure TS1 both recommended). Keep polymorphism/schema variants of `contextWithStartBoundary` local where they differ meaningfully (`HasStepRecogniserTest`'s `(boolean polymorphic, Schema schema)` overload vs the fixed defaults in `TraversalFilterStepRecogniserTest`).

## Evidence base
