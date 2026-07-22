package com.jetbrains.youtrackdb.internal.core.query.analyzed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNotBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.Test;

/// Round-trip parity suite for {@link AnalyzedExprEvaluator}, the whole acceptance bar for this
/// slice: there is no live consumer, so correctness is defined as the IR evaluating to the same value
/// the AST produces on the same row.
///
/// For every covered SQL fragment the test asserts
/// {@code Objects.equals(lower(parse(sql)).evaluate(row, ctx), parse(sql).<oracle>(row, ctx))}. The
/// AST is the oracle; a divergence is a real evaluator (or shared numeric-promotion) bug, never a
/// reason to relax an assertion. The oracle method is shape-dependent — {@link
/// SQLExpression#execute(Result, CommandContext)} for arithmetic and function-call fragments, {@link
/// SQLBinaryCondition#evaluate(Result, CommandContext)} for comparison and boolean fragments — so the
/// harness dispatches the oracle by parsed shape and lowers comparison / {@code NOT} fragments through
/// {@link AnalyzedExprLowerer#lowerBoolean} (public since Track 04), which is why this suite lives
/// in the {@code query.analyzed} package.
///
/// Each matrix row pins a mechanism a naive implementation would get wrong: precedence and
/// associativity in the arithmetic fold, integer-vs-double widening through the shared promotion
/// engine, the collation transform and the EQ/NE session difference in comparison, cross-type operand
/// coercion, the guarded collate fetch on a schemaless / absent-column row, {@code NOT} negation, and
/// the method-call path. Null-propagation and a {@code Date + Long} row pin promotion semantics.
public class AnalyzedExprEvaluatorTest extends DbTestBase {

  private static YouTrackDBSql parser(String sql) {
    return new YouTrackDBSql(new ByteArrayInputStream(sql.getBytes()));
  }

  private static SQLExpression parseExpression(String sql) {
    try {
      return parser(sql).Expression();
    } catch (Exception e) {
      throw new IllegalStateException("failed to parse expression: " + sql, e);
    }
  }

  private static SQLBinaryCondition parseComparison(String sql) {
    try {
      return (SQLBinaryCondition) parser(sql).BinaryCondition();
    } catch (Exception e) {
      throw new IllegalStateException("failed to parse comparison: " + sql, e);
    }
  }

  private static SQLNotBlock parseNotBlock(String sql) {
    try {
      return parser(sql).NotBlock();
    } catch (Exception e) {
      throw new IllegalStateException("failed to parse NOT block: " + sql, e);
    }
  }

  /// Parses a boolean block (AND/OR/comparison) via the OrBlock production.
  private static SQLBooleanExpression parseOrBlock(String sql) {
    try {
      return parser(sql).OrBlock();
    } catch (Exception e) {
      throw new IllegalStateException("failed to parse OR block: " + sql, e);
    }
  }

  private CommandContext context() {
    return new BasicCommandContext(session);
  }

  /// Asserts arithmetic / value parity: the IR evaluation of {@code sql} equals {@link
  /// SQLExpression#execute(Result, CommandContext)} on the same row. The AST result is computed first
  /// and used as the expected value, so a divergence reports the AST's value against the IR's.
  private void assertValueParity(String sql, Result row) {
    var ctx = context();
    SQLExpression ast = parseExpression(sql);
    Object oracle = ast.execute(row, ctx);
    Object ir =
        AnalyzedExprEvaluator.evaluate(AnalyzedExprLowerer.lower(parseExpression(sql)), row, ctx);
    assertEquals("value parity for: " + sql, oracle, ir);
  }

  /// Asserts comparison parity: the IR evaluation of a comparison {@code sql} equals {@link
  /// SQLBinaryCondition#evaluate(Result, CommandContext)} on the same row. Comparisons are parsed and
  /// lowered through the boolean entry, matching how the AST reaches the collation-applying overload.
  private void assertComparisonParity(String sql, Result row) {
    var ctx = context();
    SQLBinaryCondition ast = parseComparison(sql);
    boolean oracle = ast.evaluate(row, ctx);
    AnalyzedExpr ir = AnalyzedExprLowerer.lowerBoolean(parseComparison(sql));
    Object irResult = AnalyzedExprEvaluator.evaluate(ir, row, ctx);
    assertTrue("comparison parity must produce a Boolean for: " + sql, irResult instanceof Boolean);
    assertEquals("comparison parity for: " + sql, oracle, irResult);
  }

  /// Asserts boolean-block parity for a boolean expression parsed via the OrBlock production
  /// (AND/OR/IS NULL/IS NOT NULL/comparison): the IR evaluation equals the AST oracle.
  private void assertBooleanParity(String sql, Result row) {
    var ctx = context();
    SQLBooleanExpression ast = parseOrBlock(sql);
    boolean oracle = ast.evaluate(row, ctx);
    AnalyzedExpr ir = AnalyzedExprLowerer.lowerBoolean(parseOrBlock(sql));
    Object irResult = AnalyzedExprEvaluator.evaluate(ir, row, ctx);
    assertEquals("boolean parity for: " + sql, oracle, irResult);
  }

  /// Asserts boolean-block parity for a {@code NOT} fragment: the IR evaluation equals {@link
  /// SQLBooleanExpression#evaluate(Result, CommandContext)} on the same row.
  private void assertNotParity(String sql, Result row) {
    var ctx = context();
    SQLBooleanExpression ast = parseNotBlock(sql);
    boolean oracle = ast.evaluate(row, ctx);
    AnalyzedExpr ir = AnalyzedExprLowerer.lowerBoolean(parseNotBlock(sql));
    Object irResult = AnalyzedExprEvaluator.evaluate(ir, row, ctx);
    assertEquals("NOT parity for: " + sql, oracle, irResult);
  }

  /// Asserts boolean-block parity for a hand-built {@link SQLNotBlock}: the IR lowered through {@link
  /// AnalyzedExprLowerer#lowerBoolean} evaluates to the same value the block's own {@link
  /// SQLNotBlock#evaluate(Result, CommandContext)} oracle produces. The block is passed in directly
  /// (not parsed) because the {@code NotBlock()} grammar produces only a single negation level and a
  /// non-negated block, so nested and non-negated shapes are constructed via the AST's public setters.
  private void assertNotBlockParity(SQLNotBlock block, Result row) {
    var ctx = context();
    boolean oracle = block.evaluate(row, ctx);
    AnalyzedExpr ir = AnalyzedExprLowerer.lowerBoolean(block);
    Object irResult = AnalyzedExprEvaluator.evaluate(ir, row, ctx);
    assertEquals("NOT block parity", oracle, irResult);
  }

  /// Asserts value parity allowing the divergent operation to throw: the AST oracle and the IR
  /// evaluation of {@code sql} must agree on the same row by either producing equal values or
  /// throwing the same exception class. A divide-by-zero through integer operands throws in both, so
  /// a plain {@link #assertValueParity} (which evaluates the oracle eagerly) would error before it
  /// could assert; this captures each side's outcome and compares the outcomes.
  private void assertValueParityOrSameThrow(String sql, Result row) {
    var ctx = context();
    Object oracle = null;
    Class<? extends Throwable> oracleThrow = null;
    try {
      oracle = parseExpression(sql).execute(row, ctx);
    } catch (RuntimeException e) {
      oracleThrow = e.getClass();
    }
    Object ir = null;
    Class<? extends Throwable> irThrow = null;
    try {
      ir = AnalyzedExprEvaluator.evaluate(AnalyzedExprLowerer.lower(parseExpression(sql)), row,
          ctx);
    } catch (RuntimeException e) {
      irThrow = e.getClass();
    }
    assertEquals("throw-class parity for: " + sql, oracleThrow, irThrow);
    if (oracleThrow == null) {
      assertEquals("value parity for: " + sql, oracle, ir);
    }
  }

  /// Builds a schema-free {@link ResultInternal} row carrying the given properties. Numeric arithmetic
  /// needs no schema (no collation, no property type), so a plain projection-style row is the parity
  /// reference for the arithmetic and function-call fragments.
  private Result row(Object... namesAndValues) {
    var r = new ResultInternal(session);
    for (int i = 0; i < namesAndValues.length; i += 2) {
      r.setProperty((String) namesAndValues[i], namesAndValues[i + 1]);
    }
    return r;
  }

  // ---- Arithmetic precedence and associativity (precedence fold + shared promotion) ----

  /// WHEN `a + b * c` is evaluated, THE tighter-binding `*` is applied before the `+`, so the IR
  /// value matches the AST's. The row uses distinct values so a mis-nesting would change the result.
  @Test
  public void arithmeticMixedPrecedence() {
    assertValueParity("a + b * c", row("a", 2, "b", 3, "c", 4));
  }

  /// WHEN `a * b + c` is evaluated (operators in the other order), THE precedence fold still applies
  /// `*` first, matching the AST.
  @Test
  public void arithmeticMixedPrecedenceOtherOrder() {
    assertValueParity("a * b + c", row("a", 2, "b", 3, "c", 4));
  }

  /// WHEN `a - b - c` is evaluated, THE left-associative reduction `(a - b) - c` matches the AST,
  /// not the right-associative `a - (b - c)` (which differs in value for subtraction).
  @Test
  public void arithmeticLeftAssociativeSubtraction() {
    assertValueParity("a - b - c", row("a", 10, "b", 3, "c", 2));
  }

  /// WHEN `a - b + c` is evaluated, THE mixed same-priority operators fold left-associatively,
  /// matching the AST.
  @Test
  public void arithmeticMixedSamePriorityLeftAssociative() {
    assertValueParity("a - b + c", row("a", 10, "b", 3, "c", 2));
  }

  /// WHEN `a / b / c` is evaluated with integer operands, THE integer-vs-double divide widening
  /// through the shared promotion engine matches the AST, and left-associativity `(a / b) / c` is
  /// the value-sensitive case division pins.
  @Test
  public void arithmeticDivideWideningLeftAssociative() {
    assertValueParity("a / b / c", row("a", 100, "b", 5, "c", 2));
  }

  /// WHEN `(a + b) * c` is evaluated, THE parenthesis recursion overrides precedence — the IR nests
  /// the addition under the multiplication — matching the AST.
  @Test
  public void arithmeticParenthesisOverridesPrecedence() {
    assertValueParity("(a + b) * c", row("a", 2, "b", 3, "c", 4));
  }

  /// WHEN `a * (b + c)` is evaluated, THE parenthesis groups on the right, matching the AST.
  @Test
  public void arithmeticParenthesisGroupsOnRight() {
    assertValueParity("a * (b + c)", row("a", 2, "b", 3, "c", 4));
  }

  /// WHEN an operand is null (`a + b` with `a` absent so it resolves to null), THE shared promotion
  /// engine propagates null and the IR matches the AST's null result — the object-level `apply`
  /// entry the evaluator routes through is what carries null-propagation, which a direct
  /// `NumericOps.apply(Number, …)` call would throw on.
  @Test
  public void arithmeticNullPropagation() {
    assertValueParity("a + b", row("b", 3));
  }

  /// WHEN a null operand reaches each of `* / -` (and a null left vs. null right under `-`), THE IR
  /// matches the AST per operator — the four operators do NOT share a null contract: `STAR`/`SLASH`
  /// null-guard to null before promotion, `MINUS` returns the other operand and negates on a null
  /// left, while `PLUS` (covered above) returns the other operand. Each row routes its operator to a
  /// different `Operator.apply(Object, Object)` body, so a mis-mapped IR->AST operator constant that a
  /// PLUS-only null row would hide surfaces here.
  @Test
  public void arithmeticNullPropagationPerOperator() {
    assertValueParity("a * b", row("b", 3)); // null * 3 -> null (STAR null-guard)
    assertValueParity("a / b", row("b", 3)); // null / 3 -> null (SLASH null-guard)
    assertValueParity("a - b", row("b", 3)); // null - 3 -> -3 (minusObject negates null left)
    assertValueParity("a - b", row("a", 3)); // 3 - null -> 3 (minusObject keeps left)
    assertValueParity("a * b", row("a", 3)); // 3 * null -> null (STAR null-guard, right null)
  }

  /// WHEN `+` joins non-numeric operands (`'foo' + 'bar'`) or a mixed String/Number pair, THE
  /// `plusObject` `String.valueOf(left) + right` concatenation fallback runs and the IR matches the
  /// AST; the contrasting `-` over the same non-numeric pair returns null (`minusObject` final else).
  /// String literals are in the lowering subset, so these fragments lower; this pins the object-level
  /// promotion fallback a direct numeric `apply(Number, ...)` call would throw `ClassCastException`
  /// on, and the `+`-concatenates-while-`-`-returns-null asymmetry.
  @Test
  public void arithmeticStringConcatAndMixed() {
    assertValueParity("a + b", row("a", "foo", "b", "bar")); // "foobar"
    assertValueParity("a + b", row("a", "foo", "b", 1)); // "foo1" (mixed String/Number)
    assertValueParity("a + b", row("a", 1, "b", "foo")); // "1foo" (mixed Number/String)
    assertValueParity("a - b", row("a", "foo", "b", "bar")); // null (minusObject final else)
  }

  /// WHEN an integer divide does not divide evenly (`7 / 2`), THE result widens to double through the
  /// shared promotion engine and matches the AST — the arm the even-divisor `100 / 5 / 2` row never
  /// reaches. AND WHEN an integer divide-by-zero (`7 / 0`) is evaluated, THE AST and the IR must agree
  /// on the outcome (both throw `ArithmeticException` through the same `left % right` boundary), pinned
  /// by the throw-class parity assertion so a divergence in the value-sensitive zero-divisor boundary
  /// is caught.
  @Test
  public void arithmeticDivideWideningAndByZero() {
    assertValueParity("a / b", row("a", 7, "b", 2)); // 3.5 (double-widening arm)
    assertValueParityOrSameThrow("a / b", row("a", 7, "b", 0)); // AST vs IR must agree on by-zero
  }

  /// WHEN a date column is shifted by a Long (`d + n` with a {@link Date} and a Long), THE
  /// `Date + Long` promotion in the shared engine produces the same shifted date as the AST. This
  /// pins the object-level promotion path the direct numeric entry would skip.
  @Test
  public void arithmeticDatePlusLong() {
    assertValueParity("d + n", row("d", new Date(1_000_000L), "n", 5_000L));
  }

  // ---- Comparison: collation, session threading, cross-type coercion ----

  /// WHEN a `ci`-collated column is compared case-insensitively (`name = 'Foo'` against stored
  /// `'foo'`), THE IR applies the same collate transform the AST applies on its `Result` overload,
  /// so both report a match. Without the collate transform the raw `equals` would differ.
  @Test
  public void comparisonCollationCaseInsensitiveMatch() {
    session.execute("CREATE class CollEq");
    session.execute("CREATE PROPERTY CollEq.name STRING (COLLATE ci)");
    session.begin();
    session.execute("INSERT INTO CollEq SET name = 'foo'");
    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM CollEq")) {
      Result stored = rs.next();
      // Mixed-case literal vs lower-case stored value: equal only under ci collation.
      assertComparisonParity("name = 'Foo'", stored);
    }
    session.commit();
  }

  /// WHEN a type-coercing not-equal is evaluated (`val != 'x'` on an integer column), THE NE operator
  /// passes a null session to coercion where EQ passes the live one, so EQ and NE can resolve a
  /// mixed-type comparison differently. Reproducing the AST's concrete operator class makes the IR
  /// match the AST for both. This row pins the NE arm of that session difference.
  @Test
  public void comparisonNotEqualSessionThreading() {
    session.execute("CREATE class NeCoerce");
    session.execute("CREATE PROPERTY NeCoerce.val INTEGER");
    session.begin();
    session.execute("INSERT INTO NeCoerce SET val = 7");
    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM NeCoerce")) {
      Result stored = rs.next();
      assertComparisonParity("val != 'x'", stored);
      // EQ arm on the same mixed-type operands, to pin both sides of the session difference.
      assertComparisonParity("val = 'x'", stored);
    }
    session.commit();
  }

  /// WHEN a Long column is compared against an Integer literal (`val != 1` on a LONG column), THE
  /// operand value-type fidelity through `visitVar` / `visitConst` plus the operator's cross-type
  /// coercion matches the AST's `left.execute` / `right.execute` + operator path.
  @Test
  public void comparisonLongColumnVsIntegerLiteral() {
    session.execute("CREATE class LongCmp");
    session.execute("CREATE PROPERTY LongCmp.val LONG");
    session.begin();
    session.execute("INSERT INTO LongCmp SET val = 1");
    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM LongCmp")) {
      Result stored = rs.next();
      assertComparisonParity("val != 1", stored);
      assertComparisonParity("val = 1", stored);
    }
    session.commit();
  }

  /// WHEN a `ci`-collated comparison runs against a schemaless / absent-column row, THE guarded
  /// collate helper returns null (no entity / no schema class / no property) instead of throwing,
  /// so the IR matches the AST's non-entity behavior. This pins parity on the path where an
  /// unguarded collate fetch would NPE / CCE.
  @Test
  public void comparisonCollateGuardOnSchemalessRow() {
    // A plain projection row is not an entity, so getCollate must short-circuit to null on both
    // sides exactly as the AST's getCollate does for a non-entity Result.
    assertComparisonParity("name = 'foo'", row("name", "Foo"));
  }

  /// WHEN each comparison operator runs against a plain row with matching and non-matching operands,
  /// THE IR matches the AST for all six operators (and both `!=` spellings). This sweeps the operator
  /// reconstruction across every concrete class, including the ordering `doCompare`-vs-0 mapping.
  @Test
  public void comparisonAllOperatorsParity() {
    Result r = row("a", 3, "b", 5);
    assertComparisonParity("a = b", r);
    assertComparisonParity("a != b", r);
    assertComparisonParity("a <> b", r);
    assertComparisonParity("a < b", r);
    assertComparisonParity("a <= b", r);
    assertComparisonParity("a > b", r);
    assertComparisonParity("a >= b", r);
    // Also the equal-operands case, so `=` true and the ordering boundaries (<=, >=) true are pinned.
    Result eq = row("a", 5, "b", 5);
    assertComparisonParity("a = b", eq);
    assertComparisonParity("a <= b", eq);
    assertComparisonParity("a >= b", eq);
    assertComparisonParity("a < b", eq);
  }

  // ---- Boolean NOT ----

  /// WHEN `NOT a = b` (unparenthesized) is evaluated, THE IR's `visitUnaryOp(NOT)` over the lowered
  /// `SQLNotBlock` negates the comparison, matching the AST. Both the true and false comparison
  /// cases are checked so the negation is pinned in both directions.
  @Test
  public void notNegatesComparison() {
    assertNotParity("NOT a = b", row("a", 3, "b", 5)); // a != b so a = b is false, NOT -> true
    assertNotParity("NOT a = b", row("a", 5, "b", 5)); // a = b true, NOT -> false
  }

  /// WHEN a `NOT` wraps another `NOT` (double negation), THE lowerer recurses NOT-over-NOT through the
  /// same boolean entry, producing `UnaryOp(NOT, UnaryOp(NOT, EQ))`, and the evaluator composes two
  /// `visitUnaryOp(NOT)` negations to match the block's own oracle. The `NotBlock()` grammar yields
  /// only one negation level, so the nested block is built via the AST's public setters with an
  /// inner parsed `NOT a = b`. Both true and false comparison rows are checked so the double negation
  /// is pinned in both directions (it must collapse to the un-negated truth value).
  @Test
  public void notDoubleNegationComposes() {
    SQLNotBlock outer = new SQLNotBlock(-1);
    outer.setNegate(true);
    outer.setSub(parseNotBlock("NOT a = b")); // inner NOT(a = b)
    assertNotBlockParity(outer, row("a", 5, "b", 5)); // NOT NOT (true) -> true
    assertNotBlockParity(outer, row("a", 3, "b", 5)); // NOT NOT (false) -> false
  }

  /// WHEN a `SQLNotBlock` is not negated, THE lowerer collapses it to a transparent pass-through (the
  /// `isNegate() == false` arm) — the lowered sub with no wrapping `UnaryOp` — and the IR matches the
  /// block's own oracle, which likewise returns the sub's value unchanged. The grammar admits a
  /// non-negated block, but parsing one is ambiguous, so it is built via the public setters wrapping a
  /// parsed comparison.
  @Test
  public void notBlockNonNegatedPassesThrough() {
    SQLNotBlock passThrough = new SQLNotBlock(-1);
    passThrough.setNegate(false);
    passThrough.setSub(parseComparison("a = b"));
    assertNotBlockParity(passThrough, row("a", 5, "b", 5)); // pass-through of true
    assertNotBlockParity(passThrough, row("a", 3, "b", 5)); // pass-through of false
  }

  // ---- Function call (method-call coercion) ----

  /// WHEN a method-call coercion `name.asInteger()` is evaluated against a string column, THE IR's
  /// `visitFuncCall` resolves the method by name and invokes it on the base value, matching the AST's
  /// `SQLEngine.getMethod` / `SQLMethod.execute` path.
  @Test
  public void funcCallMethodCoercionParity() {
    assertValueParity("name.asInteger()", row("name", "42"));
  }

  /// WHEN a method-call coercion carries a parameter (`name.indexof('cd')` on a string column), THE
  /// IR's `visitFuncCall` evaluates the parameter into `params[]` (the `args[1..n]` loop body that a
  /// zero-arg method leaves unrun) and the result matches the AST. `indexof` takes one argument, the
  /// argument is a string literal that lowers to a `Const`, so the parameterized method-call modifier
  /// is in the S0 subset; this pins the param-index arithmetic and the parameter sub-expression
  /// recursion `name.asInteger()` cannot reach.
  @Test
  public void funcCallWithParameterParity() {
    assertValueParity("name.indexof('cd')", row("name", "abcdef")); // index of "cd" -> 2
  }

  // ---- Identifiable adapter (future-caller seam) ----

  /// WHEN an {@link Identifiable} is wrapped via {@link AnalyzedExprEvaluator#wrap} and a
  /// `ci`-collated comparison is evaluated through the resulting synthetic {@link Result}, THE
  /// comparison runs through the collation-applying path: a mixed-case literal matches the
  /// lower-case stored value case-insensitively. This exercises the deliberate convergence where the
  /// unified `Result` path applies collation that the AST's `Identifiable` overload skips.
  @Test
  public void identifiableAdapterEvaluatesThroughCollationPath() {
    session.execute("CREATE class AdaptColl");
    session.execute("CREATE PROPERTY AdaptColl.name STRING (COLLATE ci)");
    session.begin();
    session.execute("INSERT INTO AdaptColl SET name = 'foo'");
    session.commit();
    Identifiable stored;
    session.begin();
    try (var rs = session.query("SELECT FROM AdaptColl")) {
      stored = rs.next().asEntity().getIdentity();
    }
    session.commit();

    session.begin();
    var ctx = context();
    Result wrapped = AnalyzedExprEvaluator.wrap(stored, session);
    AnalyzedExpr ir = AnalyzedExprLowerer.lowerBoolean(parseComparison("name = 'Foo'"));
    Object result = AnalyzedExprEvaluator.evaluate(ir, wrapped, ctx);
    // ci collation makes 'Foo' match the stored 'foo' through the unified Result path.
    assertEquals(Boolean.TRUE, result);
    session.commit();
  }

  /// WHEN the wrapped {@link Identifiable} path compares against a non-matching value, THE comparison
  /// is false — confirming the adapter does not merely always return true and that real values flow
  /// through the synthetic row.
  @Test
  public void identifiableAdapterReturnsFalseOnNonMatch() {
    session.execute("CREATE class AdaptCollNeg");
    session.execute("CREATE PROPERTY AdaptCollNeg.name STRING (COLLATE ci)");
    session.begin();
    session.execute("INSERT INTO AdaptCollNeg SET name = 'foo'");
    session.commit();
    Identifiable stored;
    session.begin();
    try (var rs = session.query("SELECT FROM AdaptCollNeg")) {
      stored = rs.next().asEntity().getIdentity();
    }
    session.commit();

    session.begin();
    var ctx = context();
    Result wrapped = AnalyzedExprEvaluator.wrap(stored, session);
    AnalyzedExpr ir = AnalyzedExprLowerer.lowerBoolean(parseComparison("name = 'bar'"));
    Object result = AnalyzedExprEvaluator.evaluate(ir, wrapped, ctx);
    assertEquals(Boolean.FALSE, result);
    session.commit();
  }

  // ---- Direct evaluator unit assertions (independent of the AST oracle) ----

  /// WHEN a single-segment {@link AnalyzedExpr.Var} is evaluated against a row that lacks that
  /// property, THE evaluator returns null rather than throwing. The round-trip rows always carry
  /// their columns, so this pins the absent-property branch of `visitVar` directly.
  @Test
  public void varResolvesNullForAbsentProperty() {
    Object value =
        AnalyzedExprEvaluator.evaluate(new AnalyzedExpr.Var(java.util.List.of("missing")),
            row("present", 1), context());
    assertEquals(null, value);
  }

  /// WHEN a {@link AnalyzedExpr.Var} is evaluated against a null row, THE evaluator returns null
  /// rather than dereferencing the row. The round-trip suite always supplies a row, so this pins the
  /// null-row guard branch of `visitVar` directly.
  @Test
  public void varResolvesNullForNullRow() {
    Object value =
        AnalyzedExprEvaluator.evaluate(new AnalyzedExpr.Var(java.util.List.of("any")), null,
            context());
    assertEquals(null, value);
  }

  /// WHEN a {@link AnalyzedExpr.Var} names a result metadata key (not a normal property), THE
  /// evaluator falls back to the metadata value, mirroring the AST's column-resolution fallback
  /// chain. This pins the metadata-fallback branch a normal-property row leaves uncovered.
  @Test
  public void varResolvesResultMetadataFallback() {
    var r = new ResultInternal(session);
    r.setMetadata("meta", 7);
    Object value =
        AnalyzedExprEvaluator.evaluate(new AnalyzedExpr.Var(java.util.List.of("meta")), r,
            context());
    assertEquals(7, value);
  }

  /// WHEN a {@link AnalyzedExpr.Var} names a temporary property (neither a normal property nor a
  /// metadata key), THE evaluator falls back to the temporary-property value, completing the
  /// AST-mirroring resolution chain and pinning its last fallback branch.
  @Test
  public void varResolvesTemporaryPropertyFallback() {
    var r = new ResultInternal(session);
    r.setTemporaryProperty("tmp", "value");
    Object value =
        AnalyzedExprEvaluator.evaluate(new AnalyzedExpr.Var(java.util.List.of("tmp")), r,
            context());
    assertEquals("value", value);
  }

  /// WHEN a {@link AnalyzedExpr.FuncCall} names a method the engine does not know, THE evaluator
  /// throws {@link UnsupportedAnalyzedNodeException} rather than dereferencing a null method. The
  /// lowering pass does not validate method existence, so this guard is the evaluator's, and a
  /// hand-built FuncCall with an unknown name is the way to reach it.
  @Test
  public void funcCallUnknownMethodThrows() {
    AnalyzedExpr ir =
        new AnalyzedExpr.FuncCall(
            "thisMethodDoesNotExist", java.util.List.of(new AnalyzedExpr.Const("x")));
    org.junit.Assert.assertThrows(
        UnsupportedAnalyzedNodeException.class,
        () -> AnalyzedExprEvaluator.evaluate(ir, row(), context()));
  }

  /// WHEN a multi-segment {@link AnalyzedExpr.Var} is evaluated, THE evaluator throws {@link
  /// IllegalStateException} rather than silently reading the first path segment. The lowering pass
  /// only emits single-segment Vars, so this pins the production guard on that lowering-contract
  /// invariant (a hand-built two-segment Var is the way to reach it).
  @Test
  public void varMultiSegmentThrows() {
    AnalyzedExpr ir = new AnalyzedExpr.Var(java.util.List.of("a", "b"));
    org.junit.Assert.assertThrows(
        IllegalStateException.class,
        () -> AnalyzedExprEvaluator.evaluate(ir, row("a", 1), context()));
  }

  /// WHEN a {@link AnalyzedExpr.FuncCall} carries no arguments, THE evaluator throws {@link
  /// IllegalStateException} rather than indexing args.get(0) out of bounds. The lowering pass always
  /// puts the target value at args[0], so this pins the production guard on that lowering-contract
  /// invariant (a hand-built empty-args FuncCall is the way to reach it).
  @Test
  public void funcCallEmptyArgsThrows() {
    AnalyzedExpr ir = new AnalyzedExpr.FuncCall("anyMethod", java.util.List.of());
    org.junit.Assert.assertThrows(
        IllegalStateException.class,
        () -> AnalyzedExprEvaluator.evaluate(ir, row(), context()));
  }

  // ---- AND / OR short-circuit ----

  /// WHEN `a = 1 AND b = 2` is evaluated with a = 1 and b = 2, THE AND evaluates to true
  /// (both sides true). When a = 1 and b = 3, the AND evaluates to false.
  @Test
  public void andEvaluatesBothSides() {
    assertBooleanParity("a = 1 AND b = 2", row("a", 1, "b", 2));
    assertBooleanParity("a = 1 AND b = 2", row("a", 1, "b", 3));
  }

  /// WHEN the left side of an AND is false, THE right side is NOT evaluated (short-circuit).
  @Test
  public void andShortCircuitsOnFalseLeft() {
    assertBooleanParity("a = 99 AND b = 2", row("a", 1, "b", 2));
    assertBooleanParity("a = 99 AND b = 2", row("a", 1));
  }

  /// WHEN `a = 1 OR b = 2` is evaluated with a = 1, THE OR short-circuits to true.
  @Test
  public void orShortCircuitsOnTrueLeft() {
    assertBooleanParity("a = 1 OR b = 2", row("a", 1, "b", 3));
    assertBooleanParity("a = 1 OR b = 2", row("a", 1));
  }

  /// WHEN the left side of an OR is false, THE right side IS evaluated.
  @Test
  public void orEvaluatesRightWhenLeftFalse() {
    assertBooleanParity("a = 1 OR b = 2", row("a", 99, "b", 2)); // false OR true -> true
    assertBooleanParity("a = 1 OR b = 2", row("a", 99, "b", 99)); // false OR false -> false
  }

  /// WHEN AND short-circuits on a false left, THE right side must not be evaluated. Proven by
  /// making the right side a FuncCall to an unknown method that would throw if evaluated.
  @Test
  public void andShortCircuitProvenBySkippingThrowingRhs() {
    AnalyzedExpr left = new AnalyzedExpr.BinaryOp(
        BinaryOperator.EQ, new AnalyzedExpr.Var(List.of("a")), new AnalyzedExpr.Const(99));
    AnalyzedExpr throwingRight = new AnalyzedExpr.FuncCall(
        "thisMethodDoesNotExist", List.of(new AnalyzedExpr.Const(1)));
    AnalyzedExpr and = new AnalyzedExpr.BinaryOp(BinaryOperator.AND, left, throwingRight);
    Object result = AnalyzedExprEvaluator.evaluate(and, row("a", 1), context());
    assertEquals(false, result);
  }

  /// WHEN OR short-circuits on a true left, THE right side must not be evaluated.
  @Test
  public void orShortCircuitProvenBySkippingThrowingRhs() {
    AnalyzedExpr left = new AnalyzedExpr.BinaryOp(
        BinaryOperator.EQ, new AnalyzedExpr.Var(List.of("a")), new AnalyzedExpr.Const(1));
    AnalyzedExpr throwingRight = new AnalyzedExpr.FuncCall(
        "thisMethodDoesNotExist", List.of(new AnalyzedExpr.Const(1)));
    AnalyzedExpr or = new AnalyzedExpr.BinaryOp(BinaryOperator.OR, left, throwingRight);
    Object result = AnalyzedExprEvaluator.evaluate(or, row("a", 1), context());
    assertEquals(true, result);
  }

  // ---- IS NULL / IS NOT NULL ----

  /// WHEN `name IS NULL` / `name IS NOT NULL` is evaluated, THE IR matches the AST.
  @Test
  public void isNullParity() {
    assertBooleanParity("name IS NULL", row());
    assertBooleanParity("name IS NULL", row("name", "hello"));
  }

  @Test
  public void isNotNullParity() {
    assertBooleanParity("name IS NOT NULL", row());
    assertBooleanParity("name IS NOT NULL", row("name", "hello"));
  }

  /// WHEN NOT(IS_NULL) composes with AND, THE two-valued composition is correct.
  @Test
  public void isNotNullWithAndComposition() {
    assertBooleanParity("name IS NOT NULL AND val = 1", row("name", "x", "val", 1));
    assertBooleanParity("name IS NOT NULL AND val = 1", row("val", 1));
  }

  /// WHEN AND/OR combine with null operands, THE IR matches the AST.
  @Test
  public void andOrWithNullOperands() {
    assertBooleanParity("a = 1 AND b = 2", row("a", 1));
    assertBooleanParity("a = 1 OR b = 2", row("b", 2));
  }

  // ---- Param resolution ----

  /// WHEN a positional param is evaluated, THE value comes from ctx.getInputParameters().
  @Test
  public void paramPositionalResolution() {
    var ctx = context();
    Map<Object, Object> params = new HashMap<>();
    params.put(0, 42);
    ctx.setInputParameters(params);
    AnalyzedExpr ir = new AnalyzedExpr.Param(0, null);
    Object result = AnalyzedExprEvaluator.evaluate(ir, row(), ctx);
    assertEquals(42, result);
  }

  /// WHEN a named param is evaluated, THE value comes by name first, then falls back to index.
  @Test
  public void paramNamedResolution() {
    var ctx = context();
    Map<Object, Object> params = new HashMap<>();
    params.put("myParam", "hello");
    ctx.setInputParameters(params);
    AnalyzedExpr ir = new AnalyzedExpr.Param(0, "myParam");
    Object result = AnalyzedExprEvaluator.evaluate(ir, row(), ctx);
    assertEquals("hello", result);
  }

  /// WHEN a named param name is not found, THE evaluator falls back to positional index.
  @Test
  public void paramNamedFallsBackToPositional() {
    var ctx = context();
    Map<Object, Object> params = new HashMap<>();
    params.put(0, "fallback");
    ctx.setInputParameters(params);
    AnalyzedExpr ir = new AnalyzedExpr.Param(0, "notInMap");
    Object result = AnalyzedExprEvaluator.evaluate(ir, row(), ctx);
    assertEquals("fallback", result);
  }

  /// WHEN a Param IR tree is evaluated twice with DIFFERENT params, THE Param re-resolves.
  @Test
  public void paramReResolvesAcrossExecutions() {
    AnalyzedExpr ir = new AnalyzedExpr.BinaryOp(
        BinaryOperator.EQ,
        new AnalyzedExpr.Var(List.of("a")),
        new AnalyzedExpr.Param(0, null));

    var ctx1 = context();
    Map<Object, Object> p1 = new HashMap<>();
    p1.put(0, 5);
    ctx1.setInputParameters(p1);
    assertEquals(true, AnalyzedExprEvaluator.evaluate(ir, row("a", 5), ctx1));

    var ctx2 = context();
    Map<Object, Object> p2 = new HashMap<>();
    p2.put(0, 99);
    ctx2.setInputParameters(p2);
    assertEquals(false, AnalyzedExprEvaluator.evaluate(ir, row("a", 5), ctx2));
  }

  // ---- YTDB-628 fast path ----

  /// WHEN EQ/NE comparisons run against an EntityImpl, THE fast path fires.
  @Test
  public void fastPathEqNeOnEntity() {
    session.execute("CREATE class FastEq");
    session.begin();
    session.execute("INSERT INTO FastEq SET name = 'foo'");
    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM FastEq")) {
      Result stored = rs.next();
      assertComparisonParity("name = 'foo'", stored);
      assertComparisonParity("name = 'bar'", stored);
      assertComparisonParity("name != 'foo'", stored);
      assertComparisonParity("name != 'bar'", stored);
    }
    session.commit();
  }

  /// WHEN range operators run against an EntityImpl, THE fast path fires.
  @Test
  public void fastPathRangeOnEntity() {
    session.execute("CREATE class FastRange");
    session.execute("CREATE PROPERTY FastRange.val INTEGER");
    session.begin();
    session.execute("INSERT INTO FastRange SET val = 5");
    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM FastRange")) {
      Result stored = rs.next();
      assertComparisonParity("val < 10", stored);
      assertComparisonParity("val < 3", stored);
      assertComparisonParity("val <= 5", stored);
      assertComparisonParity("val > 3", stored);
      assertComparisonParity("val > 10", stored);
      assertComparisonParity("val >= 5", stored);
    }
    session.commit();
  }

  /// WHEN a ci-collated column is compared, THE fast path FALLS BACK to the slow path.
  @Test
  public void fastPathFallsBackOnCiCollation() {
    session.execute("CREATE class FastCi");
    session.execute("CREATE PROPERTY FastCi.name STRING (COLLATE ci)");
    session.begin();
    session.execute("INSERT INTO FastCi SET name = 'foo'");
    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM FastCi")) {
      Result stored = rs.next();
      assertComparisonParity("name = 'Foo'", stored);
    }
    session.commit();
  }

  /// WHEN a `ci`-collated comparison runs on a schemaless-class entity (an entity whose class
  /// defines no property for the column), THE collate helper returns null through the
  /// absent-property branch and the comparison still matches the AST. This pins the
  /// `property == null` guard of the collate chain on a real entity row, distinct from the
  /// non-entity short-circuit pinned elsewhere.
  @Test
  public void collateGuardOnEntityWithAbsentProperty() {
    // A schemaless class: properties are stored dynamically with no SchemaProperty, so
    // schemaClass.getProperty(name) returns null and the collate fetch short-circuits there.
    session.execute("CREATE class CollAbsentProp");
    session.begin();
    session.execute("INSERT INTO CollAbsentProp SET name = 'Foo'");
    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM CollAbsentProp")) {
      Result stored = rs.next();
      // No COLLATE on this dynamic property, so 'Foo' = 'foo' is case-sensitive false in both
      // AST and IR — the point is the collate helper returns null without throwing.
      assertComparisonParity("name = 'foo'", stored);
      assertComparisonParity("name = 'Foo'", stored);
    }
    session.commit();
  }

  /// WHEN the right operand of a comparison carries the collated column (the left being a literal),
  /// THE collate helper resolves the collate from the right operand after the left returns null,
  /// matching the AST's left-then-right collate fetch. This pins the right-operand collate branch.
  @Test
  public void collateResolvedFromRightOperand() {
    session.execute("CREATE class CollRight");
    session.execute("CREATE PROPERTY CollRight.name STRING (COLLATE ci)");
    session.begin();
    session.execute("INSERT INTO CollRight SET name = 'foo'");
    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM CollRight")) {
      Result stored = rs.next();
      // Literal on the left, collated column on the right: the helper must fall through to the
      // right operand to find the ci collate, exactly as the AST does.
      assertComparisonParity("'Foo' = name", stored);
    }
    session.commit();
  }

  /// WHEN a {@link AnalyzedExpr.Const} is evaluated, THE evaluator returns the literal value
  /// unchanged, including a null literal.
  @Test
  public void constReturnsLiteralValue() {
    var ctx = context();
    assertEquals(42, AnalyzedExprEvaluator.evaluate(new AnalyzedExpr.Const(42), row(), ctx));
    assertEquals(null, AnalyzedExprEvaluator.evaluate(new AnalyzedExpr.Const(null), row(), ctx));
  }

  /// WHEN a `NOT` is applied directly over a boolean comparison that evaluates true, THE result is
  /// false — a direct unit check on the negation independent of the parser round-trip.
  @Test
  public void notNegatesBooleanDirectly() {
    var ctx = context();
    // a = a is true on a row where a = 5, so NOT(a = a)-shaped tree negates to false. Build the IR
    // tree directly: NOT over EQ(Var a, Const 5) against a row with a = 5.
    AnalyzedExpr eq =
        new AnalyzedExpr.BinaryOp(BinaryOperator.EQ, new AnalyzedExpr.Var(java.util.List.of("a")),
            new AnalyzedExpr.Const(5));
    AnalyzedExpr not = new AnalyzedExpr.UnaryOp(UnaryOperator.NOT, eq);
    assertEquals(false, AnalyzedExprEvaluator.evaluate(not, row("a", 5), ctx));
    // And NOT over a false comparison negates to true.
    AnalyzedExpr neq =
        new AnalyzedExpr.BinaryOp(BinaryOperator.EQ, new AnalyzedExpr.Var(java.util.List.of("a")),
            new AnalyzedExpr.Const(6));
    AnalyzedExpr notNeq = new AnalyzedExpr.UnaryOp(UnaryOperator.NOT, neq);
    assertEquals(true, AnalyzedExprEvaluator.evaluate(notNeq, row("a", 5), ctx));
  }

  /// WHEN the round-trip helpers' `Objects.equals` contract is exercised on a value the AST and IR
  /// both produce, THE values are equal — a guard that the parity helpers compare boxed values
  /// rather than identity. (Sanity check on the harness itself.)
  @Test
  public void parityHarnessComparesByValue() {
    var ctx = context();
    Result r = row("a", 2, "b", 3);
    SQLExpression ast = parseExpression("a + b");
    Object oracle = ast.execute(r, ctx);
    Object ir =
        AnalyzedExprEvaluator.evaluate(AnalyzedExprLowerer.lower(parseExpression("a + b")), r, ctx);
    assertTrue(Objects.equals(oracle, ir));
    assertFalse(Objects.equals(oracle, "definitely-not-the-value"));
  }
}
