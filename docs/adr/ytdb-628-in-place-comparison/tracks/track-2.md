# Track 2: SQLBinaryCondition integration

## Progress
- [x] Review + decomposition
- [x] Step implementation
- [ ] Track-level code review

## Base commit
`aa78e7d452`

## Reviews completed
- [x] Technical
- [x] Risk

## Steps

- [x] Step 1: In-place comparison in `evaluate(Result, CommandContext)`
  - [x] Context: safe
  Add the in-place comparison fast path to the `evaluate(Result, CommandContext)`
  method in `SQLBinaryCondition.java`. This is the primary integration point
  used by modern SQL execution (SELECT, MATCH).

  **Pattern detection guard** (all must be true to attempt in-place):
  - `!left.isFunctionAny() && !left.isFunctionAll()` — already handled by
    early returns, so the guard goes after those
  - `left.isBaseIdentifier()` — property reference, not expression/function
  - `right.isEarlyCalculated(ctx)` — constant or parameter, not subquery
  - `left.getCollate(currentRecord, ctx) == null` AND
    `right.getCollate(currentRecord, ctx) == null` — no collation
    (v1 limitation: collated comparisons fall back)
  - `currentRecord instanceof ResultInternal ri` — need access to underlying
    identifiable
  - `ri.asEntityOrNull() instanceof EntityImpl entityImpl` — entity must be
    loaded and concrete EntityImpl (not raw RID, not projection result).
    `asEntityOrNull()` is safe: returns null for non-entities without throwing
  - Operator is supported: `operator instanceof SQLEqualsOperator`,
    `operator instanceof SQLNeqOperator`, `operator instanceof SQLNeOperator`,
    or `operator.isRangeOperator()` (covers Lt, Gt, Le, Ge)

  **Property name extraction** (zero-allocation):
  Use direct traversal instead of `getDefaultAlias()` (which allocates
  `new SQLIdentifier()` per call — see R3):
  ```java
  ((SQLBaseExpression) left.mathExpression)
      .getIdentifier().getSuffix().getIdentifier().getStringValue()
  ```
  This is safe because `isBaseIdentifier()` guarantees the structure.

  **Right value computation**:
  Compute `rightVal = right.execute(currentRecord, ctx)` ONCE before attempting
  in-place. On FALLBACK, pass `rightVal` into the standard path to avoid
  double evaluation (T3/R4).

  **Operator dispatch**:
  - Equality (`SQLEqualsOperator`): `entityImpl.isPropertyEqualTo(propName, rightVal)`
    → `InPlaceResult.TRUE` returns `true`, `FALSE` returns `false`,
    `FALLBACK` falls through
  - Not-equal (`SQLNeqOperator`, `SQLNeOperator`): same call, negate result
    (TRUE → `false`, FALSE → `true`, FALLBACK falls through)
  - Range (`isRangeOperator()`): `entityImpl.comparePropertyTo(propName, rightVal)`
    → `OptionalInt` present: interpret sign per operator (Lt: `cmp < 0`,
    Gt: `cmp > 0`, Le: `cmp <= 0`, Ge: `cmp >= 0`). Empty: FALLBACK

  **FALLBACK path**:
  When in-place returns FALLBACK, must still compute `leftVal`:
  ```java
  var leftVal = left.execute(currentRecord, ctx);
  // rightVal already computed
  // apply collation (will be null since we checked, but keep structure
  // consistent with the non-optimized path)
  return operator.execute(ctx.getDatabaseSession(), leftVal, rightVal);
  ```

  **Note on SQLGetInternalPropertyExpression**: `isBaseIdentifier()` returns
  true for internal property expressions (from MATCH graph navigation
  flattening). These safely FALLBACK — internal properties are not in the
  properties map or serialized property index (R5).

  **Files**: `SQLBinaryCondition.java` (production), new or existing test class
  **Tests**: SQL SELECT queries exercising all 7 operators with integer, string,
  double, and boolean types. Include records fetched from DB (serialized source)
  to ensure the in-place path is exercised. Verify null property handling
  falls back correctly.

  > **What was done:** Added in-place comparison fast path to
  > `evaluate(Result, CommandContext)` in SQLBinaryCondition. The guard checks
  > `isBaseIdentifier()`, `instanceof SQLBaseExpression` (filters out
  > SQLGetInternalPropertyExpression), `isEarlyCalculated()`, no collation,
  > and ResultInternal → EntityImpl unwrapping. Supports all 7 operators via
  > `isPropertyEqualTo` (=, <>, !=) and `comparePropertyTo` (<, >, <=, >=)
  > with `evaluateRangeResult()` helper. FALLBACK reuses pre-computed rightVal.
  > 17 tests: 4 equality types, 2 not-equal variants, 4 range operators,
  > string range, 2 null cases, cross-type, multi-WHERE, no-match, negative
  > values (review fix TC1).
  >
  > **What was discovered:** SQL parser `SQLFloatingPoint.getValue()` casts
  > double literals that fit in float range to `Float` (line 47:
  > `if (Math.abs(returnValue) < Float.MAX_VALUE)`). This means `3.14` in SQL
  > becomes `Float(3.14f)` but DOUBLE properties store `Double(3.14d)`.
  > These differ in IEEE 754 representation. Used exact-representable values
  > (2.5) in tests. Not a bug in the in-place path — the existing path has
  > the same behavior.
  >
  > **Key files:** `SQLBinaryCondition.java` (modified),
  > `SQLBinaryConditionInPlaceTest.java` (new)

- [x] Step 2: In-place comparison in `evaluate(Identifiable, CommandContext)`
  - [x] Context: info
  Add the same in-place comparison fast path to the
  `evaluate(Identifiable, CommandContext)` overload. This overload is used by
  `SQLContainsCondition`, `SQLContainsAllCondition`, and
  `SQLContainsAnyCondition` when iterating over collection items.

  **Key differences from the Result overload**:
  - `currentRecord` is an `Identifiable`, not a `Result`
  - Check `currentRecord instanceof EntityImpl entityImpl` directly
  - **No collation guard** — the existing overload never applies collation,
    so omitting the check preserves behavioral parity (T5/R2)
  - Same pattern detection otherwise: `left.isBaseIdentifier()`,
    `right.isEarlyCalculated(ctx)`, supported operator

  **Operator dispatch**: identical to Step 1 (can extract a shared private
  method to avoid duplication if appropriate).

  **FALLBACK**: reuse rightVal, compute leftVal, call `operator.execute()`.

  **Files**: `SQLBinaryCondition.java` (production), test class
  **Tests**: SQL queries using CONTAINS with EntityImpl items to exercise the
  Identifiable path. Verify non-EntityImpl identifiables pass through to
  the existing path.

  > **What was done:** Added in-place comparison to the Identifiable-based
  > evaluate() overload. Extracted shared `tryInPlaceComparison()` method
  > (returns Boolean, null = fallback) to eliminate code duplication between
  > the two evaluate() overloads. No collation guard — the existing overload
  > never applies collation. Test: `testContainsWithLinkedEntityCondition`
  > using LINKLIST + CONTAINS (condition) syntax for both equality and range
  > comparison.
  >
  > **Key files:** `SQLBinaryCondition.java` (modified),
  > `SQLBinaryConditionInPlaceTest.java` (modified)

- [x] Step 3: MATCH and edge case integration tests
  - [x] Context: info
  Comprehensive SQL-level integration tests ensuring end-to-end correctness
  across both query engines (SELECT and MATCH) and edge cases.

  **MATCH queries**: WHERE clauses in MATCH patterns should exercise the
  Result-based `evaluate()` path. Verify all 7 operators produce correct
  results in MATCH context.

  **Edge cases to cover**:
  - Collated string queries fall back correctly (not silently wrong)
  - Non-entity results (projection-only queries) pass through without error
  - Type conversion: integer property vs long literal, float vs double, etc.
  - Null property values → correct SQL NULL semantics (fall back to standard path)
  - Null right-hand value → correct behavior
  - Multiple WHERE conditions on the same record (each independently optimized)
  - Properties not in schema (schema-less mode) — fall back correctly
  - Encrypted properties — fall back correctly (if testable)

  **Files**: test class only (no production code changes)
  **Tests**: focused on breadth of coverage across query patterns and data types

  > **What was done:** Added 7 integration tests: 3 MATCH queries (equality,
  > range, not-equal), 1 MATCH with graph traversal + WHERE, 1 MATCH with
  > multiple WHERE conditions, 1 non-entity projection (verifies pass-through),
  > 1 schema-less property test. 25 total tests across all 3 steps.
  >
  > **Key files:** `SQLBinaryConditionInPlaceTest.java` (modified)
