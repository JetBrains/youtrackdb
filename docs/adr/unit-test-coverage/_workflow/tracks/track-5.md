# Track 5: SQL Operators & Filters

## Description

Write tests for SQL operator and filter classes — the lowest-coverage
area in the SQL layer (sql/operator at 20.9%, sql/filter at 39.9%).

> **What**: Tests for `sql/operator` and `sql/operator/math` (And, Or,
> Equals, GreaterThan, In, Between, Like, Plus, Minus, Multiply,
> Divide, Mod) and `sql/filter` (parsing, evaluation,
> `SQLFilterItemField`, `SQLFilterCondition`).
>
> **How**: Standalone JUnit 4 tests for operator dispatch and value
> coercion; DbTestBase only when the operator path requires a session.
> Cross-product type matrix per operator (String × String, Integer ×
> Integer, mixed types, null operands, edge values).
>
> **Constraints**: In-scope: only the listed `sql/operator*` and
> `sql/filter` sub-packages. Out-of-scope: `sql/functions` (Track 6),
> `sql/method*` (Track 7), full SQL execution paths (covered in
> integration tests).
>
> **Interactions**: Depends on Track 1. Establishes the
> falsifiable-regression + WHEN-FIXED marker convention for downstream
> SQL tracks. No production code changes (production-bug fixes for
> two operators land via separate WHEN-FIXED-paired commits).

## Progress
- [x] Review + decomposition
- [x] Step implementation (6/6 complete)
- [x] Track-level code review (1/3 iterations — passed, fixes applied)

## Base commit
`92c2b9da39`

## Reviews completed
- [x] Technical
- [x] Risk
- [x] Adversarial

## Review key findings summary

All three reviews converge: the plan's "self-contained operators" claim is
partially wrong. Operators fall into three testability tiers:

1. **Truly standalone** (null context): Math operators, QueryOperator base,
   DefaultQueryOperatorFactory
2. **BasicCommandContext** (no real session): Like, ContainsKey, ContainsText,
   And, Or, Not, Matches, Is; comparison operators for same-type operands
3. **DbTestBase required** (active transaction/schema): Instanceof, Contains
   w/ condition, ContainsAll, ContainsValue, Traverse; ALL filter classes

Steps are ordered by dependency tier: standalone first, DB-dependent last.
This matches the D1 strategy (easiest first) and allows early coverage wins.

Latent bugs identified during review (to document/fix during implementation):
- QueryOperatorContainsValue:144 — early return on first false in condition loop
- QueryOperatorTraverse:135 — copy-paste: FieldAny.FULL_NAME used where
  FieldAll was intended
- QueryOperatorContainsText — ignoreCase field never consulted in evaluateRecord
- QueryOperatorEquals:87-91 — duplicate `iRight instanceof Result` check (dead code)

## Steps

- [x] Step 1: Math operators + operator base/factory (standalone)
  - [x] Context: info
  Complete math operator coverage and test the operator infrastructure:
  - Create `QueryOperatorModTest` following the existing math test pattern
    (pass null for iRecord, iCurrentResult, iCondition, iContext; use
    `RecordSerializerBinary.INSTANCE.getCurrentSerializer()`)
  - Extend existing `QueryOperatorPlusTest`, `QueryOperatorMinusTest`,
    `QueryOperatorMultiplyTest`, `QueryOperatorDivideTest` with missing paths:
    Date+Number arithmetic, String concatenation (Plus only), null operand
    propagation, Short type combinations, BigDecimal combinations
  - Test `QueryOperator` base class: `compare()` for BEFORE/AFTER/EQUAL/
    UNKNOWNED orderings, `toString()`, static operator ordering
  - Test `DefaultQueryOperatorFactory`: `getOperators()` returns all expected
    operators, set is unmodifiable
  - All standalone — no DbTestBase, no BasicCommandContext
  > **What was done:** Created QueryOperatorModTest (31 tests) and
  > DefaultQueryOperatorFactoryTest (8 tests) as new files. Rewrote
  > QueryOperatorPlusTest (23 tests), QueryOperatorMinusTest (17 tests),
  > QueryOperatorMultiplyTest (33 tests), QueryOperatorDivideTest (17 tests)
  > from monolithic single-method patterns into focused individual tests with
  > an `eval()` helper. Extended QueryOperatorTest with compare(), toString(),
  > getSyntax(), isUnary(), configure(), canBeMerged(), canShortCircuit(),
  > isSupportingBinaryEvaluate() tests. Total: 141 tests across 7 files.
  > **What was discovered:** (1) `tryDownscaleToInt` has a pre-existing
  > off-by-one: uses strict `<`/`>` instead of `<=`/`>=`, so exact
  > Integer.MAX_VALUE and MIN_VALUE stay as Long. Documented with boundary
  > tests. (2) Mod operator dispatches on left type only (unlike other
  > operators that use getMaxPrecisionClass), causing silent truncation when
  > right operand has higher precision. (3) BigDecimal division without
  > RoundingMode throws ArithmeticException for non-terminating results.
  > (4) Integer/Long division by zero throws uncaught ArithmeticException.
  > (5) Java widens short arithmetic to int — all Short operator results
  > are Integer, not Short.
  > **Key files:** QueryOperatorModTest.java (new), DefaultQueryOperatorFactoryTest.java (new),
  > QueryOperatorPlusTest.java (modified), QueryOperatorMinusTest.java (modified),
  > QueryOperatorMultiplyTest.java (modified), QueryOperatorDivideTest.java (modified),
  > QueryOperatorTest.java (modified)

- [x] Step 2: Standalone comparison operators (BasicCommandContext, no session)
  - [x] Context: info
  Test comparison operators that do NOT need a database session:
  - `QueryOperatorLike` — delegates to `QueryHelper.like()`, test wildcards
    (*/%/?) and regex patterns, case sensitivity, null operands
  - `QueryOperatorContainsKey` — pure `Map.containsKey`, test with Map/
    EntityImpl, null key, empty map
  - `QueryOperatorContainsText` — pure `indexOf`, test substring matching,
    case sensitivity (document that ignoreCase field is never consulted — 
    pre-existing inconsistency)
  - `QueryOperatorAnd` — boolean logic in evaluateRecord, RID range narrowing
    (getBeginRidRange takes MAX, getEndRidRange takes MIN)
  - `QueryOperatorOr` — boolean logic in evaluateRecord, RID range widening
    (getBeginRidRange takes MIN, getEndRidRange takes MAX)
  - `QueryOperatorNot` — two modes: standalone (negate iLeft) and wrapping
    another operator (negate next.evaluateRecord())
  - `QueryOperatorIs` — null IS null (identity), IS NOT_NULL sentinel,
    IS DEFINED sentinel (needs Result with properties)
  - `QueryOperatorMatches` — regex matching with BasicCommandContext for
    Pattern caching (getVariable/setVariable). Test first-time compile and
    cache reuse
  - Use BasicCommandContext without session for all tests. No DbTestBase.
  > **What was done:** Created StandaloneComparisonOperatorsTest.java with 80
  > tests covering 8 operators: Like (wildcards %, ?, exact match, case
  > insensitivity, regex escaping for . * +, empty strings, MultiValue),
  > ContainsKey (bidirectional map lookup, empty/absent/null-value, non-map),
  > ContainsText (substring indexOf, null, empty-string-always-matches,
  > ignoreCase inconsistency documented), And/Or (boolean truth tables,
  > null-left→false, null-right→NPE documented, canShortCircuit, index reuse
  > types with both null sides), Not (standalone negation, wrapping operator,
  > unary, getNext), Is (null identity, NOT_NULL sentinel both sides, object
  > identity vs equality using SQLFilterCondition wrapper), Matches (regex,
  > full-string requirement, null, pattern caching with assertSame reuse
  > verification).
  > **What was discovered:** (1) IS operator requires non-null SQLFilterCondition
  > — cannot pass null for iCondition because evaluateExpression calls
  > iCondition.getLeft() immediately. Solved with SQLFilterCondition("stub",
  > is, right) wrapper. (2) And/Or throw NullPointerException when left is
  > non-null but right is null — Boolean unboxing of null at line 52.
  > Asymmetric null handling: left-null is guarded but right-null is not.
  > (3) ContainsText ignoreCase field is never consulted in evaluateRecord
  > — confirmed pre-existing inconsistency from Phase A review.
  > (4) String.indexOf("") returns 0, so CONTAINSTEXT "" matches everything.
  > **Key files:** StandaloneComparisonOperatorsTest.java (new)

- [x] Step 3: Equality hierarchy + basic comparison operators (DbTestBase)
  - [x] Context: warning
  Test the core operator equality hierarchy and session-dependent comparison
  operators. This is the highest-value step — the `QueryOperatorEquality.
  evaluateRecord()` method (100 lines) is inherited by all equality operators.
  - `QueryOperatorEquality.evaluateRecord()`: Test QueryRuntimeValueMulti
    with ALL semantics (left and right), ANY semantics (left and right),
    BinaryField comparison path. These are the 748-uncovered-line cascade
    targets.
  - `QueryOperatorEqualityNotNulls`: Verify null-guard returns false for
    null left/right operands across representative subclasses
  - `QueryOperatorEquals` / `QueryOperatorNotEquals` / `QueryOperatorNotEquals2`:
    Same-type comparisons (Integer, String, Date, BigDecimal), cross-type
    coercion (Integer vs Long, String vs Number), RID comparison,
    Collection/Map equality. Document dead code branch at lines 87-91.
  - `QueryOperatorMajor` / `QueryOperatorMajorEquals` / `QueryOperatorMinor`
    / `QueryOperatorMinorEquals`: Numeric ordering, String ordering, Date
    ordering, cross-type with PropertyTypeInternal.convert
  - `QueryOperatorBetween`: 3-element multi-value format [lower, AND, upper],
    inclusive/exclusive boundaries, type coercion for boundary values
  - `QueryOperatorIn`: Collection membership, RID in collection, null in
    collection, empty collection
  - Extend DbTestBase. Use BasicCommandContext with session for cross-type
    coercion paths.
  > **What was done:** Created EqualityComparisonOperatorsTest.java (92 tests)
  > extending DbTestBase. Covers Equals (same-type, cross-type Integer/Long,
  > BigDecimal/Integer, Double/Float, null, both-null, single-element
  > collection unwrap, multi-element not unwrapped, byte arrays, identity
  > short-circuit, index reuse), NotEquals/NotEquals2 (negation, null both
  > sides), Major/MajorEquals/Minor/MinorEquals (numeric/string/date ordering,
  > cross-type Integer/Long and BigDecimal/Integer, null both sides, index
  > reuse), Between (inclusive/exclusive boundaries, both-exclusive, inverted
  > range, point range, cross-type numeric, string with comment, validation
  > with message check), In (List/Set/Array right, left-multi-value, both-
  > multi-value intersection, cross-type List, scalar-both-sides fallthrough,
  > cross-type Set bug documented), static equals() paths.
  > **What was discovered:** (1) In operator Set.contains() path bypasses
  > QueryOperatorEquals type coercion — Set<Long>.contains(Integer) returns
  > false even when numeric value matches. Pre-existing bug: the Set fast
  > path at line 151-152 uses raw contains() instead of iterating with
  > equals(). The List iteration path correctly handles cross-type via
  > QueryOperatorEquals.equals(). (2) QueryOperatorEquals lines 87-91 have
  > dead code: duplicate `iRight instanceof Result` check where the second
  > branch is unreachable. (3) 3.14f and 3.14d are not IEEE 754 equal —
  > cross-type float/double tests must use exactly representable values.
  > **Key files:** EqualityComparisonOperatorsTest.java (new)

- [x] Step 4: Entity/schema-dependent comparison operators (DbTestBase)
  - [x] Context: warning
  Test operators that require active transactions with persisted entities
  and schema access:
  - `QueryOperatorContains` — test with collection of simple values (no
    entity loading), collection of identifiable records (triggers
    transaction.loadEntity()), and with SQLFilterCondition sub-condition
  - `QueryOperatorContainsAll` — test with arrays and collections of
    primitives, cross-type containment
  - `QueryOperatorContainsValue` — test Map value containment with simple
    values and schema-dependent paths. Document the early return bug at
    line 144 (returns on first false in condition loop instead of
    continuing). Per plan Non-Goals, fix if confirmed as bug.
  - `QueryOperatorInstanceof` — test with schema class hierarchy
    (create base class + subclass, test instanceof checks).
    Requires session.getMetadata().getImmutableSchemaSnapshot()
  - `QueryOperatorTraverse` — test with linked entities, traverse depth.
    Document copy-paste bug at line 135 (FieldAny.FULL_NAME where
    FieldAll was intended). Fix if confirmed as bug.
  > **What was done:** Created EntitySchemaOperatorsTest.java (95 tests)
  > extending DbTestBase. Covers Contains (simple value, condition with
  > Identifiable/Map/skip paths, right-side iterable, index reuse),
  > ContainsAll (array vs array/collection, condition path, vacuous truth,
  > cross-type, counting bug documentation), ContainsValue (map containment,
  > right-side map, left-side condition path, entity-to-map conversion, bug
  > fix regression), Instanceof (direct/sub/unrelated class, string class
  > name, invalid class exception, non-existent left class asymmetry),
  > Traverse (configure, getters/toString/syntax, linked entities, depth
  > limit, start level, specific fields, cycle detection, ALL branch fix
  > regression, map values). Fixed 2 production bugs with falsifiable
  > regression tests.
  > **What was discovered:** (1) ContainsAll has a pre-existing counting bug:
  > outer loop iterates LEFT, incrementing matches per left element found in
  > RIGHT. Duplicate left values matching the same right element over-count,
  > causing false negatives (e.g., {2,2,3} CONTAINSALL {2,3} returns false).
  > Documented with test, not fixed (semantic change with regression risk).
  > (2) Instanceof has left/right asymmetry: non-existent right class throws
  > CommandExecutionException, but non-existent left class silently returns
  > false. Documented with test. (3) session.newInstance(className) throws
  > for vertex/edge classes — must use plain classes (not EXTENDS V) for
  > entity creation in unit tests. (4) session.command() returns void in the
  > core module — must use metadata API (schema.createClass, createProperty)
  > for schema setup.
  > **Key files:** EntitySchemaOperatorsTest.java (new),
  > QueryOperatorContainsValue.java (bug fix), QueryOperatorTraverse.java
  > (bug fix + import)
  - All extend DbTestBase. Create test schema with classes, properties,
    and persisted entities in @Before setup.

- [x] Step 5: SQL Filter classes (DbTestBase)
  - [x] Context: warning
  Test the filter evaluation infrastructure. All classes require DbTestBase
  for parsing. Use the parse-and-evaluate pattern (as in existing
  FilterOptimizerTest) for broad coverage per test:
  - `SQLFilterCondition` — direct construction for simple operator/operand
    evaluate() paths; parse-and-evaluate via SQLEngine.parseCondition()
    for complex paths including binary evaluation (BinaryField comparison,
    RecordSerializerBinary paths), type coercion in checkForConversion()
    (Integer, Float, Date, RID), collate-based comparison
  - `SQLFilter` — parse SQL filter text, evaluate against records. Test
    compound conditions (AND/OR), nested conditions, null handling
  - `SQLPredicate` — parsing logic: extractConditions, extractCondition,
    extractConditionItem, extractConditionOperator, resetOperatorPrecedence
  - `SQLTarget` — target resolution against schema: class targets, cluster
    targets, RID targets, metadata targets. Test with defined and undefined
    class names.
  - Extend `FilterOptimizer` tests — add cases for additional optimization
    rules beyond the existing 4 tests
  - `SQLFilterItem*` classes — SQLFilterItemField getValue/setValue,
    SQLFilterItemFieldAll/FieldAny multi-value evaluation,
    SQLFilterItemParameter/Variable resolution
  > **What was done:** Created SQLFilterClassesTest.java with 119 tests
  > covering all filter infrastructure classes: SQLFilter (parsing, AND/OR,
  > operator precedence, braces), SQLFilterCondition (evaluate, toString,
  > asString, getters/setters, getInvolvedFields, RID range, type conversion
  > via checkForConversion for Integer/Float/Date/RID/BigDecimal, collate,
  > short-circuit), SQLPredicate (text parsing, null root, evaluate
  > overloads, addParameter, upperCase multi-char expansion, NOT/BETWEEN/IN/
  > LIKE/IS NULL/IS NOT NULL/MATCHES/CONTAINSTEXT operators), SQLTarget
  > (class, CLASS: prefix, RID, RID collection, INDEX:, INDEXVALUES:/ASC/DESC,
  > METADATA:SCHEMA/INDEXMANAGER, $variable, COLLECTION: single/multi, empty
  > text), SQLFilterItemField (getValue, isFieldChain, getFieldChain,
  > belongsTo, method chain, dot field chain, getRoot, hasChainOperators,
  > getCollate, getLastChainOperator, asString, @rid preloaded field),
  > SQLFilterItemParameter (getValue, setValue, getName, toString), 
  > SQLFilterItemFieldAll/Any (constants, ALL()/ANY() evaluation),
  > FilterOptimizer (null condition, null-operator unwrap, non-null right,
  > OR partial, null value, field mismatch, INDEX_OPERATOR Major/Minor).
  > Review fixes added: variable filter tests ($expected, $unset), MultiValue
  > collection path, strengthened assertions throughout (exact toString,
  > list sizes, specific exception types).
  > **What was discovered:** (1) SQLTarget uppercases class names before
  > schema lookup — class names must be uppercase to match. (2) SQLTarget
  > COLLECTION: prefix also uppercases collection names. (3) Entity created
  > in one transaction cannot be used in another without reload —
  > session.getActiveTransaction().load(identity) required. (4) EntityImpl
  > implements Entity which extends Result — no asResult() needed. (5)
  > session.save() does not exist in core module — entities are persisted
  > by transaction commit. (6) SQLFilterCondition.toString() uses
  > Object.toString() for SQLFilterItemField left operand (shows class@hash),
  > while asString() uses field name. (7) "not-a-date" > 42 comparison
  > succeeds through checkForConversion (string→integer conversion path)
  > rather than hitting the exception handler.
  > **Key files:** SQLFilterClassesTest.java (new)

- [x] Step 6: Coverage verification + gap filling
  - [x] Context: info
  > **What was done:** Ran coverage build and identified per-class gaps.
  > Initial post-steps-1-5 coverage: sql/operator 66.1%/55.9%,
  > sql/filter 77.8%/64.6%, sql/operator/math 91.1%/90.2%.
  > Created OperatorCoverageGapTest.java (87 tests) targeting the largest
  > uncovered areas: RID range methods for Major/MajorEquals/Minor/MinorEquals
  > (direct RID and SQLFilterItemParameter paths), And/Or/Not (SQLFilterCondition-
  > based range narrowing/widening), In (RID collection min/max, parameter
  > resolution), Between (RID range begin/end, null elements).
  > QueryOperatorEquality.evaluateRecord with QueryRuntimeValueMulti (ALL/ANY
  > semantics, left/right, empty arrays, null values). QueryOperatorIn.
  > evaluateExpression (left-Set, left-array, right-array, left-multi vs
  > right-collection, scalar fallthrough). Final coverage: sql/operator
  > 83.0%/75.3% (+17%/+19%), sql/filter 78.0%/64.8%, sql/operator/math
  > 91.1%/90.2%.
  > **What was discovered:** (1) sql/operator is 2% below the 85% line target.
  > The remaining 161 uncovered lines are in deeply nested paths: BinaryField
  > evaluation in Major/MajorEquals/Minor/MinorEquals/Equals (requires binary
  > record serializer setup with EntitySerializer.getComparator()), and
  > SQLFilterCondition.evaluate() paths with complex type coercion chains.
  > These paths are exercised through integration tests, not unit tests.
  > (2) sql/filter is 7% below the 85% line target. The 212 uncovered lines
  > are in SQLFilterCondition.evaluate() (71 lines of complex runtime
  > dispatching), SQLPredicate parsing (43 lines of rarely-used extraction
  > methods), and SQLFilterItemAbstract/Field (55 lines of method chain
  > evaluation that requires full SQL parsing context). These paths are
  > covered by SQL integration tests. (3) sql/operator branch coverage at
  > 75.3% exceeds the 70% target — PASS. (4) sql/operator/math at
  > 91.1%/90.2% — PASS.
  > **Key files:** OperatorCoverageGapTest.java (new)
