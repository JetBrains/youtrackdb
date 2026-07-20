<!-- MANIFEST
findings: 6   severity: {blocker: 0, should-fix: 3, suggestion: 3}
index:
  - {id: TQ1, sev: should-fix, loc: PredicateTraversalEquivalenceTest.java:225, anchor: "### TQ1 ", cert: n/a, basis: "Track 4 Validation A1/A2 require translator-on/off multiset parity for eq(null), neq(null), and negated predicates on absent properties; only AST-shape tests exist in GremlinPredicateAdapterTest"}
  - {id: TQ2, sev: should-fix, loc: PredicateTraversalEquivalenceTest.java:225, anchor: "### TQ2 ", cert: n/a, basis: "between/inside/outside and singleton-collection eq/neq acceptance lines have no equivalence fixture; between is AST-rendered only"}
  - {id: TQ3, sev: should-fix, loc: PredicateTraversalEquivalenceTest.java:329, anchor: "### TQ3 ", cert: n/a, basis: "String Text positive parity covers containing and startingWith only; endingWith/regex/not* on declared String properties lack translator-on/off tests"}
  - {id: TQ4, sev: suggestion, loc: StringPredicateCollationTest.java:414, anchor: "### TQ4 ", cert: n/a, basis: "SQLContainsTextCondition has no splitForAggregation reconstruction test while STARTSWITH/ENDSWITH/MATCHES do; Step 1 acceptance requires all four nodes"}
  - {id: TQ5, sev: suggestion, loc: PredicateTraversalEquivalenceTest.java:283, anchor: "### TQ5 ", cert: n/a, basis: "has(key) presence test excludes present-null vertex; Validation distinguishes IS DEFINED from IS NULL"}
  - {id: TQ6, sev: suggestion, loc: HasStepRecogniserTest.java:66, anchor: "### TQ6 ", cert: n/a, basis: "isVertexClass guard on hasLabel(~label) has no recogniser unit test; edge-class label should decline"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
cert_index: []
flags: [CONTRACT_OK]
-->

## Findings

### TQ1 [should-fix] NULL and absent-property semantics lack end-to-end equivalence tests

Track 4 Validation pins A1/A2 behavior with hard acceptance lines: `eq(null)` / `neq(null)` must match native on both a null-valued property and an **absent** property (a vertex lacking the key is excluded by both pipelines), and negated predicates (`without`, `notContaining`, `P.not(...)`) must likewise exclude absent rows. `GremlinPredicateAdapterTest` covers the emitted AST shapes (`IS DEFINED AND IS NULL`, guarded `NOT IN`, guarded `NOT(...)`) but never executes them against a graph with absent vs present-null fixtures. `PredicateTraversalEquivalenceTest` — the translator-on/off parity layer — has no cases for `P.eq(null)`, `P.neq(null)`, `P.without(...)`, `TextP.notContaining(...)`, or `P.not(...)`.

Without at least one equivalence case per A1/A2 family (two-vertex fixture: one absent key, one present-null value), a regression that drops the `IS DEFINED` guard would still pass the adapter's render assertions while diverging from native at execution time. The polymorphic `hasLabel` and AND-compose lines show the intended pattern: seed the distinguishing vertices, run `assertEquivalent`, assert sorted RID multisets match.

### TQ2 [should-fix] Range and singleton-collection acceptance lines have no parity tests

Validation requires `between` to return the right-exclusive multiset (no off-by-one on the high bound) and `inside` / `outside` to match native; it also requires size-1 `eq([a])` / `neq([a])` to **decline** while size-2 and empty collections **translate**. `GremlinPredicateAdapterTest` asserts AST decomposition (`>= low AND < high`, open/closed bounds, decline returns null) but no test runs those predicates through the full strategy chain. `PredicateTraversalEquivalenceTest` has no `between` / `inside` / `outside` or singleton-collection cases either.

A wrong high-bound operator (`<=` instead of `<` on `between`) or a broken singleton decline would surface only in production Gremlin traversals, not in the current unit suite. Minimal fix: one equivalence test per range shape with boundary values on the inclusive/exclusive edge, plus one `Recognition.DECLINED` case for `P.eq(List.of(v))` and one `RECOGNIZED` case for `P.eq(List.of(v1, v2))`.

### TQ3 [should-fix] String Text positive equivalence is incomplete on declared String properties

Validation states that `containing` / `startingWith` / `endingWith` / `regex` and their `not*` variants translate and match native on declared String fields. The equivalence fixture covers positive parity for `TextP.containing` and `TextP.startingWith` (`stringTextPredicate_translates_matchesNative`, `startingWithDeclaredStringIndexed_matchesNative`) and throw-parity for non-String operands, but no translator-on/off case exercises `endingWith`, `regex`, `notContaining`, `notStartingWith`, `notEndingWith`, or `notRegex` on a declared `STRING` property returning a non-empty matching multiset.

Adapter-level tests in `GremlinPredicateAdapterTest` pin AST shapes for all six forms; the gap is behavioral. A collation or strict-flag bug on `SQLEndsWithCondition` or find-mode `SQLMatchesCondition` would not be caught until a Gremlin integration path hits it. Add at least one `assertEquivalent` per remaining positive Text variant (negated forms need fixtures where absent-property exclusion matters — ties to TQ1).

### TQ4 [suggestion] SQLContainsTextCondition splitForAggregation is untested

Step 1 acceptance and Decision Log R2 require every new/modified Text AST node to survive `splitForAggregation()` reconstruction. `StringPredicateCollationTest` exercises that path for `SQLStartsWithCondition`, `SQLEndsWithCondition`, and `SQLMatchesCondition`; `MatchWhereBuilderTest` covers `copy()` / `equals()` / fingerprint-distinctness for `containsText` strict vs lenient. No test drives `SQLContainsTextCondition#splitForAggregation` on an aggregate left operand — the collate-transform node that changed existing SQL semantics. Given `internal/core/sql/parser` is excluded from JaCoCo, behavioral reconstruction tests are the correctness gate for hand-written nodes.

Mirror the existing `endsWith_splitForAggregationPreservesBothOperands` pattern: inject an aggregate projection item as the left operand, assert both operands (and strict flag if applicable) survive reconstruction.

### TQ5 [suggestion] has(key) presence does not distinguish present-null from absent

Validation: `has(key)` → `IS DEFINED` must match native on absent and present-with-null properties, distinct from `IS NULL`. `PredicateTraversalEquivalenceTest.hasKeyPresence_matchesNative` seeds vertices with a value, with a value, and **without** the key — it never adds a vertex carrying `nickname = null`. `TraversalFilterStepRecogniserTest` asserts the `IS DEFINED` SQL text only. A regression to bare `IS NULL` would pass the current fixture (all three vertices lack a matching value filter in the absent-only sense) while diverging on the present-null row native excludes from `has(key)` but includes in `has(key, null)`.

Add a fourth vertex with an explicit null property and assert it is included in the translated multiset (or excluded, if native semantics differ — pin whichever native returns first, per the R1 pin pattern).

### TQ6 [suggestion] HasStepRecogniser lacks edge-class hasLabel decline coverage

Step 3 added an `isVertexClass` guard so `hasLabel("knows")` on an edge class declines rather than re-typing to a class whose `SELECT FROM` would error. `HasStepRecogniserTest` covers polymorphic/non-polymorphic vertex labels, multi-label decline, conflicting labels, and missing classes, but no case passes a known **edge** class name through the `~label` branch. `PredicateTraversalEquivalenceTest` also omits this shape. A recogniser regression that dropped the guard would fail at runtime, not in the current unit suite. One decline test with `session.createEdgeClass("knows")` and `assertContributedNothing` closes the gap at recogniser level; an equivalence decline case is optional follow-up.

## Evidence base

No certificate drill-down — findings are coverage gaps against `track-4.md` Validation and Acceptance and the four named test classes, verified by reading those files and searching the gremlin translator test tree for matching case names (`eq(null)`, `between`, `splitForAggregation` + `ContainsText`, `hasLabel` + edge class, present-null + `has(key)`).
