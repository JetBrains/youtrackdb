<!-- workflow-sha: 6b81c6b970b0c58300e4c053a5883c2482d3dd25 -->
# Technical gate verification — Track 4 (Evaluator + round-trip parity), iter2

- review_type: technical
- phase: 3A
- track: Track 4: Evaluator + round-trip parity
- overall: PASS
- findings: 0

## verdicts
- T1: VERIFIED
- T2: VERIFIED
- T3: VERIFIED

#### Verify T1: collate chain was guard-free
- **Original issue**: the collate-resolution chain in Plan of Work step 5 and Decision Log D6-R was stated without guards — a guard-free happy path would throw on a schemaless row or absent column where the AST returns `null` collate, breaking parity.
- **Fix applied**: D6-R pseudocode (track lines 198-207) and Plan of Work step 5 (lines 328-334) rewritten to the guarded form mirroring `SQLSuffixIdentifier.getCollate`: `result.isEntity()` guard, `(EntityImpl) asEntity()` cast, `getImmutableSchemaClass(session)`, null-guard the schema class, collate off `SchemaClass.getProperty(name).getCollate()`, `null` on any miss and for any non-`Var` operand. A schemaless/absent-column matrix row was added (line 382: "`ci-column = 'foo'` on a schemaless row / absent column → Collate helper returns `null` (guarded) instead of NPE/CCE").
- **Re-check**:
  - Track-file location: D6-R block (`## Decision Log`, the fenced pseudocode); Plan of Work step 5; Validation matrix row (T1/A3).
  - Current state: the pseudocode now guards every hop and returns `null` on each miss, matching the AST oracle exactly. Inline comments correctly attach `getImmutableSchemaClass` to `EntityImpl` (not the `Entity` interface) and `getProperty` to the schema class (not `Entity.getProperty`, which returns the column value).
  - Criteria met: technical accuracy against the live AST source; parity-preserving null behavior on the non-entity / absent-column path.
- **PSI verification** (oracle: `SQLSuffixIdentifier.getCollate(Result, CommandContext)` body):
  - `isEntity()` guard, `(EntityImpl) currentResult.asEntity()` cast, `record.getImmutableSchemaClass(session)`, null-guard, `schemaClass.getProperty(...)`, null-guard, `return property.getCollate();`, `return null` on any miss — byte-for-byte the shape the fix mirrors.
  - `getImmutableSchemaClass(DatabaseSessionEmbedded)` is declared on `EntityImpl` (confirmed); the public `Entity` interface does not carry it — the cast is required.
  - `getImmutableSchemaClass` returns `SchemaImmutableClass`, whose `getProperty(String)` returns `SchemaProperty`; `SchemaProperty.getCollate()` returns `Collate`. Chain resolves end to end.
  - `Result` (`...internal.core.query.Result`) is an interface with `isEntity():boolean` and `asEntity():Entity` — the guard and cast are well-typed.
- **Regression check**: checked the matrix and the comparison sequenceDiagram note (lines 281-284) for consistency — both now describe the guarded single-property resolution returning `null` for non-`Var`/non-entity operands; no contradiction introduced.
- **Verdict**: VERIFIED

#### Verify T2: parity oracle stated uniformly as `execute`
- **Original issue**: the round-trip parity oracle was stated uniformly as `execute`, which is wrong for comparison/boolean fragments whose AST oracle is `SQLBinaryCondition.evaluate`.
- **Fix applied**: Validation intro (lines 359-366) and Plan of Work step 7 (lines 340-346) now state the oracle is shape-dependent — `SQLExpression.execute(Result, ctx)` for arithmetic, `SQLBinaryCondition.evaluate(Result, ctx)` for comparison/boolean — with the harness dispatching the oracle by parsed shape.
- **Re-check**:
  - Track-file location: `## Validation and Acceptance` intro; Plan of Work step 7.
  - Current state: both passages describe the two-shape oracle and parsed-shape dispatch; `Objects.equals` compares boxed `Boolean` on comparison rows.
  - Criteria met: oracle selection is now technically correct per fragment shape; consistent across the two passages and I1.
- **PSI verification**: the two oracle methods are the established AST surfaces named in the track's "Relevant shapes (PSI-confirmed on develop)" block — `SQLBinaryCondition` carries both `evaluate(Identifiable, ctx)` and `evaluate(Result, ctx)` (recorded in D3/D15, re-confirmed in prior Phase A PSI rounds); no signature claim in the fix is new or contradicted. The fix is a test-design correctness statement, not a new symbol claim.
- **Regression check**: I1 (line 440-444) and the matrix `NOT a = b` row (line 383) both route comparison/boolean through `lowerBoolean` and the `evaluate` oracle — consistent with step 7; no drift.
- **Verdict**: VERIFIED

#### Verify T3: NumericOps delegation assumed one uniform entry
- **Original issue**: arithmetic delegation assumed a single uniform `NumericOps` entry; calling `NumericOps.apply(Number, Operator, Number)` directly throws on a null operand and skips `Date ± Long` / `String` concat, so the null-propagation and `Date + Long` matrix rows would go red.
- **Fix applied**: Plan of Work step 2 (lines 304-312) now maps the IR `BinaryOperator` arithmetic constant to its `SQLMathExpression.Operator` counterpart and calls that constant's `apply(Object, Object)` — routing through `plusObject`/`minusObject`/`applyObject` and carrying null-propagation, `Date ± Long`, and `String` concat — explicitly NOT `NumericOps.apply(Number, Operator, Number)`. D-log Surprises entry and the Component Map remain consistent.
- **Re-check**:
  - Track-file location: Plan of Work step 2; cross-referenced by the `## Surprises & Discoveries` arithmetic bullet (lines 36-39).
  - Current state: the delegation path is the object-level `Operator.apply(Object, Object)` entry, with the four-constant IR→AST map authored in-track (no reusable inverse map exists — `AnalyzedExprLowerer.toArithmeticOperator` is AST→IR and private).
  - Criteria met: technically correct delegation that preserves null-propagation and object-level arithmetic semantics; the avoided `NumericOps.apply(Number, Operator, Number)` is correctly identified as the throw-on-null path.
- **PSI verification**:
  - `SQLMathExpression.Operator` is an enum; it carries `apply(Object, Object) : Object` (alongside the typed `apply(Integer,Integer)` … `apply(BigDecimal,BigDecimal)` overloads). The object-level entry the fix targets exists.
  - `NumericOps` (`...internal.core.sql.util.NumericOps`) carries `applyObject(Operator, Object, Object) : Object`, `plusObject(Object, Object) : Object`, `minusObject(Object, Object) : Object` — the routing targets named in the fix all exist — and also the `apply(Number, Operator, Number) : Number` overload the fix says NOT to call directly. The "throws on null / skips object semantics" claim is consistent with that overload's `Number`-typed signature (no object/null/Date/String path).
- **Regression check**: the matrix `Date + Long` and null-propagation rows (lines 386-388) and the Surprises arithmetic bullet now align with step 2's object-level delegation; no contradiction with Track 2's `NumericOps` contract as recorded in the plan.
- **Verdict**: VERIFIED

## Findings
<!-- No new findings surfaced by this verification pass. -->
