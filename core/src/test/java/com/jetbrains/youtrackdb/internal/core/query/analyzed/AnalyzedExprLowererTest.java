package com.jetbrains.youtrackdb.internal.core.query.analyzed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExpr.BinaryOp;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExpr.Const;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExpr.FuncCall;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExpr.UnaryOp;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExpr.Var;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNotBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.junit.Test;

/// Lowering unit test for {@link AnalyzedExprLowerer}.
///
/// Two property families are checked. First, each in-subset AST shape lowers to the exact expected IR
/// tree — the IR variants are records with structural `equals`, so the produced {@link AnalyzedExpr}
/// tree is asserted directly in-track, no evaluator needed. The mixed-precedence and same-precedence
/// cases are the load-bearing ones: a mis-nesting bug in the precedence fold would surface here rather
/// than two tracks later in the round-trip parity suite. Second, every out-of-subset shape throws
/// {@link UnsupportedAnalyzedNodeException} rather than returning a partial tree — the no-silent-
/// fallback contract a successful `lower(...)` promises. The throw cases are exhaustive over the kinds
/// of out-of-subset shape: out-of-subset {@link SQLExpression} fields, out-of-subset arithmetic
/// operators that reach the in-subset `mathExpression` field, out-of-subset comparison operators and
/// non-comparison boolean shapes, subqueries and CASE, top-level function / `@this` / inline-collection
/// identifiers, multi-segment column paths, and bind parameters.
///
/// AST inputs are produced by parsing real SQL through {@link YouTrackDBSql}, so the test exercises the
/// genuine parse shapes the lowering pass meets in production rather than hand-built nodes. Comparison
/// and `NOT` inputs are parsed directly into {@link SQLBinaryCondition} / {@link SQLNotBlock} and lowered
/// through {@link AnalyzedExprLowerer#lowerBoolean}: a boolean expression reaches the public field walk
/// only inside a {@link SQLExpression} whose `booleanExpression` field is set, and the AST exposes no
/// public setter for that field, so the same-package boolean entry is the in-test route.
public class AnalyzedExprLowererTest {

  /// Parses a value expression (e.g. `a + b * c`, `5`, `name.asInteger()`) into an
  /// {@link SQLExpression} through the real grammar production.
  private static SQLExpression parseExpression(String sql) {
    try {
      return parser(sql).Expression();
    } catch (Exception e) {
      throw new IllegalStateException("failed to parse expression: " + sql, e);
    }
  }

  /// Parses a comparison condition (e.g. `a = b`) directly into an {@link SQLBinaryCondition}.
  private static SQLBinaryCondition parseComparison(String sql) {
    try {
      return (SQLBinaryCondition) parser(sql).BinaryCondition();
    } catch (Exception e) {
      throw new IllegalStateException("failed to parse comparison: " + sql, e);
    }
  }

  /// Parses a `NOT …` boolean block directly into an {@link SQLNotBlock}.
  private static SQLNotBlock parseNotBlock(String sql) {
    try {
      return parser(sql).NotBlock();
    } catch (Exception e) {
      throw new IllegalStateException("failed to parse NOT block: " + sql, e);
    }
  }

  /// Parses an `AND` / `OR` boolean block (a non-comparison boolean shape) into an
  /// {@link SQLBooleanExpression}, used to exercise the boolean throw-default.
  private static SQLBooleanExpression parseOrBlock(String sql) {
    try {
      return parser(sql).OrBlock();
    } catch (Exception e) {
      throw new IllegalStateException("failed to parse OR block: " + sql, e);
    }
  }

  private static YouTrackDBSql parser(String sql) {
    return new YouTrackDBSql(new ByteArrayInputStream(sql.getBytes()));
  }

  private static AnalyzedExpr lower(String sql) {
    return AnalyzedExprLowerer.lower(parseExpression(sql));
  }

  private static Var var(String name) {
    return new Var(List.of(name));
  }

  // ---- Arithmetic precedence fold ----

  /// WHEN `a + b * c` is lowered, THE precedence fold nests the tighter-binding `*` under the `+`, so
  /// the IR is `PLUS(a, STAR(b, c))` rather than the naive left-to-right `STAR(PLUS(a, b), c)`. This is
  /// the mixed-precedence case the round-trip parity suite ultimately backs; a mis-nesting here would
  /// change the computed value.
  @Test
  public void mixedPrecedenceNestsTighterOperatorDeeper() {
    AnalyzedExpr expected =
        new BinaryOp(BinaryOperator.PLUS, var("a"), new BinaryOp(BinaryOperator.STAR, var("b"),
            var("c")));
    assertEquals(expected, lower("a + b * c"));
  }

  /// WHEN `a - b - c` is lowered, THE same-precedence operators fold left-associatively into
  /// `MINUS(MINUS(a, b), c)`, matching the AST's `<=` reduction — not the right-associative
  /// `MINUS(a, MINUS(b, c))`, which differs in value.
  @Test
  public void samePrecedenceFoldsLeftAssociative() {
    AnalyzedExpr expected =
        new BinaryOp(BinaryOperator.MINUS, new BinaryOp(BinaryOperator.MINUS, var("a"), var("b")),
            var("c"));
    assertEquals(expected, lower("a - b - c"));
  }

  /// WHEN a longer mixed expression `a * b + c * d` is lowered, THE two multiplications each bind
  /// tighter than the addition, so the IR is `PLUS(STAR(a, b), STAR(c, d))`. This pins the fold across
  /// two independent tighter-binding runs around a looser operator.
  @Test
  public void mixedPrecedenceAcrossTwoTighterRuns() {
    AnalyzedExpr expected =
        new BinaryOp(
            BinaryOperator.PLUS,
            new BinaryOp(BinaryOperator.STAR, var("a"), var("b")),
            new BinaryOp(BinaryOperator.STAR, var("c"), var("d")));
    assertEquals(expected, lower("a * b + c * d"));
  }

  /// WHEN each of the four arithmetic operators appears, THE fold maps it to the matching IR operator.
  /// A flat two-operand expression has one operator, so each case isolates one mapping.
  @Test
  public void eachArithmeticOperatorMapsToItsIrOperator() {
    assertEquals(new BinaryOp(BinaryOperator.PLUS, var("a"), var("b")), lower("a + b"));
    assertEquals(new BinaryOp(BinaryOperator.MINUS, var("a"), var("b")), lower("a - b"));
    assertEquals(new BinaryOp(BinaryOperator.STAR, var("a"), var("b")), lower("a * b"));
    assertEquals(new BinaryOp(BinaryOperator.SLASH, var("a"), var("b")), lower("a / b"));
  }

  // ---- Parenthesized grouping ----

  /// WHEN `(a + b) * c` is lowered, THE grouping parenthesis is transparent — the lowerer recurses into
  /// the grouped expression and the IR tree's own nesting expresses the grouping, producing
  /// `STAR(PLUS(a, b), c)`. There is no paren IR node.
  @Test
  public void parenthesizedGroupingRecursesAndNestsByTreeShape() {
    AnalyzedExpr expected =
        new BinaryOp(BinaryOperator.STAR, new BinaryOp(BinaryOperator.PLUS, var("a"), var("b")),
            var("c"));
    assertEquals(expected, lower("(a + b) * c"));
  }

  // ---- Leaf shapes ----

  /// WHEN a bare single-segment column reference `name` is lowered, THE IR is a single-segment
  /// {@link Var}.
  @Test
  public void singleSegmentColumnLowersToVar() {
    assertEquals(var("name"), lower("name"));
  }

  /// WHEN the `null` literal is lowered, THE IR is a {@link Const} carrying a `null` value — the
  /// `isNull` field of the union expression.
  @Test
  public void nullLiteralLowersToConstNull() {
    assertEquals(new Const(null), lower("null"));
  }

  /// WHEN the boolean literals `true` and `false` are lowered, THE IR is a {@link Const} carrying the
  /// boxed boolean — the `booleanValue` field of the union expression.
  @Test
  public void booleanLiteralsLowerToConst() {
    assertEquals(new Const(true), lower("true"));
    assertEquals(new Const(false), lower("false"));
  }

  /// WHEN a positive integer literal `42` is lowered, THE IR is a {@link Const} carrying the parsed
  /// numeric value.
  @Test
  public void integerLiteralLowersToConst() {
    assertEquals(new Const(42), lower("42"));
  }

  /// WHEN a negative integer literal `-5` is lowered, THE sign is already folded into the literal value
  /// by the parser, so the IR is one {@link Const} carrying `-5` — not a unary-minus wrapper.
  @Test
  public void negativeIntegerLiteralLowersToOneNegativeConst() {
    assertEquals(new Const(-5), lower("-5"));
  }

  /// WHEN a string literal `'hello'` is lowered, THE IR is a {@link Const} carrying the unquoted,
  /// escape-decoded string.
  @Test
  public void stringLiteralLowersToConst() {
    assertEquals(new Const("hello"), lower("'hello'"));
  }

  /// WHEN a method-call coercion `name.asInteger()` is lowered, THE IR is a {@link FuncCall} named for
  /// the method, with the lowered base column as its first argument. Method-call syntax is structurally
  /// a function call on the base value.
  @Test
  public void methodCallLowersToFuncCallOverBase() {
    assertEquals(
        new FuncCall("asInteger", List.of(var("name"))), lower("name.asInteger()"));
  }

  /// WHEN a method call carries an argument `name.append('x')` is lowered, THE IR is a {@link FuncCall}
  /// whose argument list is the lowered base followed by each lowered method argument. This exercises
  /// the argument-lowering loop a zero-argument method call leaves uncovered.
  @Test
  public void methodCallWithArgumentLowersArgumentsAfterBase() {
    assertEquals(
        new FuncCall("append", List.of(var("name"), new Const("x"))),
        lower("name.append('x')"));
  }

  // ---- Comparison ----

  /// WHEN each of the six comparison operators is lowered, THE concrete operator class maps to its IR
  /// comparison operator and the operands lower to their leaf IR. Both `!=` spellings (`!=` and `<>`)
  /// collapse to {@link BinaryOperator#NE}.
  @Test
  public void eachComparisonOperatorMapsToItsIrOperator() {
    assertEquals(comparison(BinaryOperator.EQ), lowerComparison("a = b"));
    assertEquals(comparison(BinaryOperator.NE), lowerComparison("a != b"));
    assertEquals(comparison(BinaryOperator.NE), lowerComparison("a <> b"));
    assertEquals(comparison(BinaryOperator.LT), lowerComparison("a < b"));
    assertEquals(comparison(BinaryOperator.LE), lowerComparison("a <= b"));
    assertEquals(comparison(BinaryOperator.GT), lowerComparison("a > b"));
    assertEquals(comparison(BinaryOperator.GE), lowerComparison("a >= b"));
  }

  private static BinaryOp comparison(BinaryOperator operator) {
    return new BinaryOp(operator, var("a"), var("b"));
  }

  private static AnalyzedExpr lowerComparison(String sql) {
    return AnalyzedExprLowerer.lowerBoolean(parseComparison(sql));
  }

  // ---- NOT (UnaryOp) ----

  /// WHEN `NOT a = b` is lowered, THE negated block becomes a {@link UnaryOp} carrying `NOT` over the
  /// lowered comparison. The condition is left unparenthesized on purpose: a parenthesized boolean
  /// `(a = b)` parses as a `SQLParenthesisBlock`, an out-of-subset boolean wrapper that throws, so the
  /// bare form is what exercises a `NOT` directly over a {@link SQLBinaryCondition}.
  @Test
  public void negatedNotBlockLowersToUnaryNot() {
    AnalyzedExpr expected = new UnaryOp(UnaryOperator.NOT, comparison(BinaryOperator.EQ));
    assertEquals(expected, AnalyzedExprLowerer.lowerBoolean(parseNotBlock("NOT a = b")));
  }

  /// WHEN a parenthesized boolean `NOT (a = b)` is lowered, THE pass throws: the parenthesis around the
  /// comparison parses as a `SQLParenthesisBlock`, a boolean wrapper outside the comparison-or-`NOT`
  /// subset, so it hits the boolean throw-default. This pins that the in-subset boolean shapes are a
  /// bare comparison and a bare `NOT`, not a parenthesized boolean.
  @Test
  public void parenthesizedBooleanBlockThrows() {
    assertThrows(
        UnsupportedAnalyzedNodeException.class,
        () -> AnalyzedExprLowerer.lowerBoolean(parseNotBlock("NOT (a = b)")));
  }

  /// WHEN a non-negated boolean block (a bare comparison parsed through the NOT-block production with
  /// `negate` false) is lowered, THE wrapper is transparent: the result is the lowered sub-expression
  /// with no `UnaryOp`. This pins the pass-through branch of the `NOT` handling.
  @Test
  public void nonNegatedNotBlockPassesThroughToSub() {
    SQLNotBlock block = parseNotBlock("a = b");
    // The NotBlock production wraps a bare condition with negate=false; the lowerer must unwrap it
    // rather than emit a UnaryOp.
    assertEquals(comparison(BinaryOperator.EQ), AnalyzedExprLowerer.lowerBoolean(block));
  }

  // ---- Throw cases: out-of-subset SQLExpression fields ----

  /// WHEN an `@rid` literal (the `rid` field) is lowered, THE pass throws — `rid` is an out-of-subset
  /// field caught by the field walk's throw-default.
  @Test
  public void ridFieldThrows() {
    assertThrows(UnsupportedAnalyzedNodeException.class, () -> lower("#12:0"));
  }

  /// WHEN an array-concat expression (`a || b`, the `arrayConcatExpression` field) is lowered, THE pass
  /// throws as an out-of-subset field.
  @Test
  public void arrayConcatFieldThrows() {
    assertThrows(UnsupportedAnalyzedNodeException.class, () -> lower("a || b"));
  }

  /// WHEN a JSON literal (the `json` field) is lowered, THE pass throws as an out-of-subset field.
  @Test
  public void jsonFieldThrows() {
    assertThrows(UnsupportedAnalyzedNodeException.class, () -> lower("{'k': 1}"));
  }

  // ---- Throw cases: out-of-subset arithmetic operators on an in-subset math expression ----

  /// WHEN remainder `a % b` is lowered, THE pass throws: `%` reaches the in-subset `mathExpression`
  /// field carried on the operator list, so the field walk's throw-default never sees it — the
  /// operator-level gate is what rejects it.
  @Test
  public void remainderOperatorThrows() {
    assertThrows(UnsupportedAnalyzedNodeException.class, () -> lower("a % b"));
  }

  /// WHEN a left-shift `a << b` is lowered, THE pass throws as an out-of-subset arithmetic operator,
  /// covering a shift operator distinct from the remainder case above.
  @Test
  public void leftShiftOperatorThrows() {
    assertThrows(UnsupportedAnalyzedNodeException.class, () -> lower("a << b"));
  }

  /// WHEN a bitwise-and `a & b` is lowered, THE pass throws as an out-of-subset arithmetic operator,
  /// covering a bitwise operator.
  @Test
  public void bitwiseAndOperatorThrows() {
    assertThrows(UnsupportedAnalyzedNodeException.class, () -> lower("a & b"));
  }

  // ---- Throw cases: out-of-subset comparison and non-comparison boolean shapes ----

  /// WHEN a `LIKE` comparison is lowered, THE operator mapping throws: `LIKE` is one of the eight
  /// out-of-subset comparison operators that may sit inside a {@link SQLBinaryCondition}. Parsing
  /// through the comparison production (rather than an `OR` block) exercises the operator-to-IR mapping
  /// throw rather than the boolean throw-default.
  @Test
  public void likeComparisonOperatorThrows() {
    assertThrows(
        UnsupportedAnalyzedNodeException.class,
        () -> AnalyzedExprLowerer.lowerBoolean(parseComparison("a like 'x%'")));
  }

  /// WHEN a `CONTAINSKEY` comparison is lowered, THE operator mapping throws, covering a second
  /// out-of-subset comparison operator that reaches the operator mapping inside a
  /// {@link SQLBinaryCondition}.
  @Test
  public void containsKeyComparisonOperatorThrows() {
    assertThrows(
        UnsupportedAnalyzedNodeException.class,
        () -> AnalyzedExprLowerer.lowerBoolean(parseComparison("a containskey 'k'")));
  }

  /// WHEN an `IN` condition is lowered, THE pass throws: `IN` parses as a dedicated `SQLInCondition`
  /// boolean subtype (not a {@link SQLBinaryCondition}), so it hits the boolean throw-default rather
  /// than the operator mapping. This complements the operator-mapping throws above by covering an
  /// out-of-subset boolean subtype.
  @Test
  public void inConditionThrows() {
    assertThrows(
        UnsupportedAnalyzedNodeException.class,
        () -> AnalyzedExprLowerer.lowerBoolean(parseOrBlock("a in [1, 2]")));
  }

  /// WHEN a non-comparison boolean shape `a = 1 AND b = 2` (an `AND` block) is lowered, THE pass throws:
  /// the IR models no `AND` / `OR` connective, so every boolean shape other than a comparison or `NOT`
  /// hits the boolean throw-default.
  @Test
  public void andBooleanShapeThrows() {
    assertThrows(
        UnsupportedAnalyzedNodeException.class,
        () -> AnalyzedExprLowerer.lowerBoolean(parseOrBlock("a = 1 AND b = 2")));
  }

  /// WHEN a method call carries an argument the subset does not lower `name.f(a = b)`, THE pass throws.
  /// The argument `a = b` parses as a boolean inside the method-call modifier, an out-of-subset modifier
  /// shape, so the whole expression is rejected. This pins that a method call is lowered only when its
  /// modifier and arguments are themselves in subset.
  @Test
  public void methodCallWithOutOfSubsetArgumentThrows() {
    assertThrows(UnsupportedAnalyzedNodeException.class, () -> lower("name.f(a = b)"));
  }

  // ---- Throw cases: parenthesis subquery and CASE ----

  /// WHEN a parenthesized subquery `(SELECT FROM Foo)` is lowered, THE pass throws: the parenthesis
  /// carries a statement, not a grouped expression, so the `statement != null` guard fires before any
  /// grouping recursion.
  @Test
  public void parenthesizedSubqueryThrows() {
    assertThrows(UnsupportedAnalyzedNodeException.class, () -> lower("(SELECT FROM Foo)"));
  }

  /// WHEN a CASE expression is lowered, THE pass throws — CASE is out of subset, reached as an
  /// out-of-subset leaf shape.
  @Test
  public void caseExpressionThrows() {
    assertThrows(
        UnsupportedAnalyzedNodeException.class,
        () -> lower("CASE WHEN a = 1 THEN 2 ELSE 3 END"));
  }

  // ---- Throw cases: top-level (levelZero) identifiers ----

  /// WHEN a top-level function call `count()` is lowered, THE pass throws: a `levelZero` function-call
  /// identifier carries no recognized leaf shape and is out of subset.
  @Test
  public void topLevelFunctionCallThrows() {
    assertThrows(UnsupportedAnalyzedNodeException.class, () -> lower("count()"));
  }

  /// WHEN the iteration function `any()` is lowered, THE pass throws specifically — `any()` carries
  /// property-iteration comparison semantics the IR comparison evaluator does not reproduce, so it must
  /// not slip through as a `FuncCall`.
  @Test
  public void anyFunctionThrows() {
    assertThrows(UnsupportedAnalyzedNodeException.class, () -> lower("any()"));
  }

  /// WHEN the `@this` self reference is lowered, THE pass throws as an out-of-subset `levelZero` shape.
  @Test
  public void thisSelfReferenceThrows() {
    assertThrows(UnsupportedAnalyzedNodeException.class, () -> lower("@this"));
  }

  /// WHEN an inline collection `[1, 2, 3]` is lowered, THE pass throws as an out-of-subset `levelZero`
  /// shape.
  @Test
  public void inlineCollectionThrows() {
    assertThrows(UnsupportedAnalyzedNodeException.class, () -> lower("[1, 2, 3]"));
  }

  // ---- Throw cases: multi-segment column path and bind parameter ----

  /// WHEN a multi-segment column path `p.name` is lowered, THE pass throws: only a single-segment `Var`
  /// is in subset, and the trailing `.name` surfaces as a suffix modifier that is rejected.
  @Test
  public void multiSegmentColumnPathThrows() {
    assertThrows(UnsupportedAnalyzedNodeException.class, () -> lower("p.name"));
  }

  /// WHEN a bind parameter `:p` is lowered, THE pass throws: bind parameters are not lowered in this
  /// subset.
  @Test
  public void bindParameterThrows() {
    assertThrows(UnsupportedAnalyzedNodeException.class, () -> lower(":p"));
  }
}
