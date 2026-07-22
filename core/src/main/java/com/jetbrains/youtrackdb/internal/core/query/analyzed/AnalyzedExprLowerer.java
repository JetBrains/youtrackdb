package com.jetbrains.youtrackdb.internal.core.query.analyzed;

import com.jetbrains.youtrackdb.internal.core.sql.parser.AnalyzedAstAccess;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLAndBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBaseExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBaseIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCompareOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLEqualsOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGeOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGtOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInputParameter;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIsNotNullCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIsNullCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLeOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLtOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMathExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMathExpression.Operator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMethodCall;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLModifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNamedParameter;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNeOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNeqOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNotBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNumber;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLParenthesisExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLPositionalParameter;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSuffixIdentifier;
import java.util.List;

/// Lowers the covered subset of the SQL parse tree (AST) into the analyzed-expression IR
/// ([AnalyzedExpr]).
///
/// The lowering pass reads {@code SQL*} parse nodes and produces [AnalyzedExpr] nodes. It modifies
/// no AST node. The covered subset is arithmetic over four operators (`+ - * /`), the six
/// comparisons (`= != < <= > >=`), boolean `NOT`, parenthesized grouping, single-segment column
/// references, numeric / string literals, and method-call coercions. Everything outside that subset
/// throws [UnsupportedAnalyzedNodeException] rather than returning a partial tree — a successful
/// `lower(...)` therefore means the whole input was covered. That no-silent-fallback contract is
/// what later slices that consume the IR rely on.
///
/// The covered subset includes arithmetic over four operators (`+ - * /`), the six comparisons
/// (`= != < <= > >=`), boolean `AND`, `OR`, `NOT`, `IS NULL`, `IS NOT NULL`, parenthesized
/// grouping, single-segment column references (rejecting `$`-prefixed context variables), numeric
/// / string literals, bind parameters (positional and named), and method-call coercions.
///
/// The pass owns three mechanisms a naive field-by-field copy would get wrong:
///
/// - **Exhaustive-or-throw field walk** over the union-style [SQLExpression]. [SQLExpression] holds
///   a fixed bag of fields with exactly one non-null per parsed expression; the walk dispatches on
///   the recognized in-subset fields and throws on everything else as its default, so an
///   out-of-subset field (`rid`, `arrayConcatExpression`, `json`) or a future parser field throws
///   rather than silently mis-reading.
/// - **Parenthesis recursion.** A grouping parenthesis is transparent at evaluate time, so the pass
///   recurses into the grouped expression and lets the IR tree's own nesting express the grouping;
///   no IR paren node exists. A parenthesis wrapping a subquery statement throws.
/// - **Precedence-climbing fold.** [SQLMathExpression] stores arithmetic as one flat list of
///   operands and operators and resolves precedence at evaluate time. The pass reproduces the AST's
///   precedence-and-associativity nesting structurally to build a correctly-nested
///   [AnalyzedExpr.BinaryOp] tree. The fold determines nesting only — it computes no value — so
///   all numeric-promotion semantics stay with the evaluator and cannot drift between the AST and
///   the IR.
public final class AnalyzedExprLowerer {

  private AnalyzedExprLowerer() {
  }

  /// Lowers an [SQLExpression] to its [AnalyzedExpr] IR tree, or throws
  /// [UnsupportedAnalyzedNodeException] for any out-of-subset shape.
  ///
  /// This is the field walk: it dispatches on the one recognized in-subset field that is set and
  /// throws on everything else. The inherited `SimpleNode.value` field is not a dispatch key —
  /// the generated parser mirrors the chosen typed field into `value`, but a recognized typed field
  /// is always co-present, so the walk keys on the typed field and a non-null `value` is never
  /// treated as out-of-subset.
  public static AnalyzedExpr lower(SQLExpression expression) {
    if (AnalyzedAstAccess.isNull(expression)) {
      return new AnalyzedExpr.Const(null);
    }
    Boolean booleanValue = AnalyzedAstAccess.booleanValue(expression);
    if (booleanValue != null) {
      return new AnalyzedExpr.Const(booleanValue);
    }
    SQLBooleanExpression booleanExpression = AnalyzedAstAccess.booleanExpression(expression);
    if (booleanExpression != null) {
      return lowerBoolean(booleanExpression);
    }
    SQLMathExpression mathExpression = expression.getMathExpression();
    if (mathExpression != null) {
      return lowerMath(mathExpression);
    }
    // Default: rid, arrayConcatExpression, json, literalValue, or any field a future parser change
    // adds. Throwing here is what keeps the no-silent-fallback contract robust against the AST
    // growing fields this subset does not model.
    throw new UnsupportedAnalyzedNodeException(expression.getClass());
  }

  /// Lowers a [SQLMathExpression] node.
  ///
  /// The math node is one of three shapes: a [SQLBaseExpression] leaf (number / identifier / string
  /// / bind parameter, optionally with a modifier), a [SQLParenthesisExpression] grouping, or a
  /// generic [SQLMathExpression] carrying the flat operand/operator lists for an arithmetic
  /// expression. A single-operand arithmetic node is unwrapped by the parser to its sole child, so
  /// a generic [SQLMathExpression] reaching here always carries at least two operands and one
  /// operator.
  private static AnalyzedExpr lowerMath(SQLMathExpression mathExpression) {
    if (mathExpression instanceof SQLBaseExpression baseExpression) {
      return lowerBase(baseExpression);
    }
    if (mathExpression instanceof SQLParenthesisExpression parenthesis) {
      return lowerParenthesis(parenthesis);
    }
    // Generic SQLMathExpression: a flat list of operands joined by arithmetic operators.
    List<SQLMathExpression> children = mathExpression.getChildExpressions();
    List<Operator> operators = mathExpression.getOperators();
    if (children == null || operators == null || children.size() < 2
        || operators.size() != children.size() - 1) {
      // The covered arithmetic shape is a flat operand list joined by one fewer operator. Any other
      // generic-math shape (an unexpected node subclass, a malformed list) is out of subset.
      throw new UnsupportedAnalyzedNodeException(mathExpression.getClass());
    }
    return foldArithmetic(children, operators);
  }

  /// Folds the flat operand/operator lists into a nested [AnalyzedExpr.BinaryOp] tree that matches
  /// the AST's precedence-and-associativity nesting.
  ///
  /// The AST resolves precedence at evaluate time by a precedence-climbing reduction keyed on
  /// [Operator#getPriority] (a lower priority number binds tighter) with left-associative
  /// reduction. This reproduces that nesting structurally: `a + b * c` (STAR binds tighter than
  /// PLUS) becomes `PLUS(a, STAR(b, c))`, and `a - b - c` (equal precedence, left-associative)
  /// becomes `MINUS(MINUS(a, b), c)`. The fold determines nesting only and computes no value.
  private static AnalyzedExpr foldArithmetic(
      List<SQLMathExpression> children, List<Operator> operators) {
    // Precedence climbing over the flat list. The cursor walks operand/operator pairs left to
    // right; climb(minPriority) consumes every run of operators that bind at least as tight as
    // minPriority, building the subtree for that run before returning to a looser-binding caller.
    // The single caller (lowerMath) has already rejected any list shorter than two operands or with
    // a mismatched operator count; co-locate that invariant here so the get(0) below is provably
    // safe even if a future caller is added. Assert lines are excluded from the coverage gate, so
    // this is free.
    assert children != null && children.size() >= 2 && operators.size() == children.size() - 1;
    int[] cursor = {0};
    AnalyzedExpr first = lowerMath(children.get(cursor[0]));
    return climb(children, operators, cursor, first, Integer.MAX_VALUE);
  }

  /// Consumes operators while they bind at least as tight as {@code minPriority}, folding them
  /// left-associatively into {@code left}.
  ///
  /// `cursor[0]` is the index of the last operand already folded into `left`; `operators.get(i)`
  /// joins `children.get(i)` and `children.get(i + 1)`. A lower priority number binds tighter, so
  /// an operator participates at this level when its priority is `<= minPriority`. The right
  /// operand of a tighter-binding operator is built by a nested climb bounded one step tighter
  /// (`priority - 1`), which makes equal-priority operators left-associative — the AST's `<=`
  /// reduction.
  private static AnalyzedExpr climb(
      List<SQLMathExpression> children,
      List<Operator> operators,
      int[] cursor,
      AnalyzedExpr left,
      int minPriority) {
    while (cursor[0] < operators.size()) {
      Operator operator = operators.get(cursor[0]);
      int priority = operator.getPriority();
      if (priority > minPriority) {
        break;
      }
      cursor[0]++;
      AnalyzedExpr right = lowerMath(children.get(cursor[0]));
      // Left-associative: bind the right operand only as tightly as operators strictly tighter
      // than this one, so a following equal-priority operator reduces with this result on its left.
      right = climb(children, operators, cursor, right, priority - 1);
      left = new AnalyzedExpr.BinaryOp(toArithmeticOperator(operator), left, right);
    }
    return left;
  }

  /// Maps an AST arithmetic [Operator] to its IR [BinaryOperator], or throws for the operators the
  /// IR does not model.
  ///
  /// The IR carries only the four arithmetic operators; the AST's `Operator` enum also has
  /// remainder, the three shifts, the three bitwise operators, and null-coalescing. Those eight
  /// arrive on the in-subset `mathExpression` field, so the field walk's throw-default never sees
  /// them — this operator-level throw is what keeps them out, the operator-level analog of the
  /// field-level exhaustive-or-throw.
  private static BinaryOperator toArithmeticOperator(Operator operator) {
    return switch (operator) {
      case PLUS -> BinaryOperator.PLUS;
      case MINUS -> BinaryOperator.MINUS;
      case STAR -> BinaryOperator.STAR;
      case SLASH -> BinaryOperator.SLASH;
      default -> throw new UnsupportedAnalyzedNodeException(operator.getClass());
    };
  }

  /// Lowers a [SQLParenthesisExpression]. A grouping parenthesis recurses into its inner
  /// expression; a parenthesis wrapping a subquery statement throws.
  ///
  /// The `statement != null` check comes first: the two payloads are mutually exclusive, and
  /// reading them in the wrong order would mis-handle a subquery as grouping.
  private static AnalyzedExpr lowerParenthesis(SQLParenthesisExpression parenthesis) {
    if (AnalyzedAstAccess.parenStatement(parenthesis) != null) {
      throw new UnsupportedAnalyzedNodeException(parenthesis.getClass());
    }
    SQLExpression inner = AnalyzedAstAccess.parenExpression(parenthesis);
    if (inner == null) {
      throw new UnsupportedAnalyzedNodeException(parenthesis.getClass());
    }
    return lower(inner);
  }

  /// Lowers a [SQLBaseExpression] leaf.
  ///
  /// The leaf is one of: a numeric literal ([SQLNumber]), a single-segment column reference or a
  /// method call (an identifier, optionally with a method-call modifier), a string literal
  /// (optionally with a method-call modifier), or a bind parameter. Everything else throws.
  private static AnalyzedExpr lowerBase(SQLBaseExpression baseExpression) {
    SQLNumber number = AnalyzedAstAccess.number(baseExpression);
    if (number != null) {
      // The concrete SQLNumber subclass (SQLInteger / SQLFloatingPoint) folds the parse-time sign
      // into its value, so a negative literal arrives as one negative Const. The base
      // SQLNumber.getValue() returns null; the value is read through the polymorphic override.
      return new AnalyzedExpr.Const(number.getValue());
    }
    SQLInputParameter inputParam = AnalyzedAstAccess.inputParam(baseExpression);
    if (inputParam != null) {
      return lowerInputParam(inputParam);
    }
    String stringLiteral = baseExpression.getStringLiteralValue();
    if (stringLiteral != null) {
      return lowerWithOptionalModifier(
          baseExpression, new AnalyzedExpr.Const(stringLiteral));
    }
    SQLBaseIdentifier identifier = baseExpression.getIdentifier();
    if (identifier != null) {
      return lowerIdentifier(baseExpression, identifier);
    }
    throw new UnsupportedAnalyzedNodeException(baseExpression.getClass());
  }

  /// Lowers an identifier-bearing base expression to a single-segment [AnalyzedExpr.Var], or —
  /// when a method-call modifier is present — to a [AnalyzedExpr.FuncCall].
  ///
  /// Only the single-segment `suffix` column shape lowers to a `Var`. The `levelZero` form (a
  /// top-level function call including `any()` / `all()`, the `@this` self reference, or an inline
  /// collection) is out of subset and throws: those carry semantics the IR does not reproduce, and
  /// a `levelZero` matches no recognized leaf shape.
  private static AnalyzedExpr lowerIdentifier(
      SQLBaseExpression baseExpression, SQLBaseIdentifier identifier) {
    if (identifier.getLevelZero() != null) {
      throw new UnsupportedAnalyzedNodeException(identifier.getLevelZero().getClass());
    }
    SQLSuffixIdentifier suffix = identifier.getSuffix();
    if (suffix == null || suffix.getIdentifier() == null) {
      // A record-attribute suffix (e.g. @rid) or a star suffix is not a plain column reference.
      throw new UnsupportedAnalyzedNodeException(baseExpression.getClass());
    }
    String columnName = suffix.getIdentifier().getStringValue();
    // Reject $-prefixed identifiers: these are LET / context variables whose resolution
    // semantics the IR does not model (deferred to a later slice). Throwing here causes the
    // AST carrier to evaluate the predicate via its own path, so correctness is preserved.
    if (columnName.startsWith("$")) {
      throw new UnsupportedAnalyzedNodeException(baseExpression.getClass());
    }
    AnalyzedExpr base = new AnalyzedExpr.Var(List.of(columnName));
    return lowerWithOptionalModifier(baseExpression, base);
  }

  /// Wraps {@code base} in a [AnalyzedExpr.FuncCall] when the base expression carries a single
  /// method-call modifier, or returns {@code base} unchanged when there is no modifier.
  ///
  /// A method-call modifier (`name.asInteger()`) is structurally a function call on the base value:
  /// the method name becomes the call name and the base value its first argument. Any other
  /// modifier shape (a multi-segment suffix chain `p.name`, an array selector, a chained modifier)
  /// is out of subset and throws — only the single-segment `Var` and a single method-call
  /// modifier are covered.
  private static AnalyzedExpr lowerWithOptionalModifier(
      SQLBaseExpression baseExpression, AnalyzedExpr base) {
    SQLModifier modifier = baseExpression.getModifier();
    if (modifier == null) {
      return base;
    }
    SQLMethodCall methodCall = modifier.getMethodCall();
    if (methodCall == null || modifier.getNext() != null) {
      // A non-method modifier, or a chained modifier (`a.b.c`, `a.m().n()`), is out of subset. A
      // multi-segment column path `p.name` surfaces here as a suffix modifier and throws.
      throw new UnsupportedAnalyzedNodeException(baseExpression.getClass());
    }
    String methodName = methodCall.getMethodNameString();
    if (methodName == null) {
      throw new UnsupportedAnalyzedNodeException(methodCall.getClass());
    }
    // Build the argument array once and wrap it in an immutable List via List.of: the base value is
    // the first argument, each lowered method parameter follows. This avoids the second defensive
    // copy a separate accumulator-plus-List.copyOf would cost while keeping the FuncCall's backing
    // list read-only (FuncCall.args() is read-only by convention).
    List<SQLExpression> params = methodCall.getParams();
    AnalyzedExpr[] args = new AnalyzedExpr[params.size() + 1];
    args[0] = base;
    for (int i = 0; i < params.size(); i++) {
      args[i + 1] = lower(params.get(i));
    }
    return new AnalyzedExpr.FuncCall(methodName, List.of(args));
  }

  /// Lowers a [SQLBooleanExpression]: a comparison ([SQLBinaryCondition]) becomes a comparison
  /// [AnalyzedExpr.BinaryOp], a [SQLNotBlock] becomes a [AnalyzedExpr.UnaryOp] (or a pass-through
  /// when not negated), and every other boolean shape throws.
  ///
  /// `AND` / `OR` blocks are left-folded into nested binary `BinaryOp(AND/OR, ...)` nodes.
  /// `IS NULL` lowers to `UnaryOp(IS_NULL, expr)` and `IS NOT NULL` to
  /// `UnaryOp(NOT, UnaryOp(IS_NULL, expr))`. The `any()` / `all()` modifier variants of
  /// IS NULL / IS NOT NULL are out of subset and throw. `IN`, `BETWEEN`, `LIKE`, and the other
  /// boolean subtypes are out of subset and throw.
  ///
  /// Public entry point for boolean expressions. A [SQLBooleanExpression] reaches the [#lower]
  /// field walk only through a [SQLExpression] whose `booleanExpression` field is set, but callers
  /// outside this package (e.g. [FilterStep]) hold a [SQLBooleanExpression] directly from
  /// [SQLWhereClause#getBaseExpression] and need this entry without wrapping.
  public static AnalyzedExpr lowerBoolean(SQLBooleanExpression booleanExpression) {
    if (booleanExpression instanceof SQLBinaryCondition condition) {
      return lowerComparison(condition);
    }
    if (booleanExpression instanceof SQLNotBlock notBlock) {
      // The sub of a NOT block is itself a boolean expression, so it lowers through this same
      // boolean entry rather than the value-expression field walk. SQLNotBlock.sub is a nullable
      // field (the AST class treats a null sub as a real state in its own evaluate guard), so a
      // same-package caller holding a hand-built NOT block with sub unset would NPE in the
      // recursion. Throw the contract's typed failure instead, keeping the boolean entry's "throw,
      // never anything else" promise total.
      SQLBooleanExpression notSub = notBlock.getSub();
      if (notSub == null) {
        throw new UnsupportedAnalyzedNodeException(notBlock.getClass());
      }
      AnalyzedExpr sub = lowerBoolean(notSub);
      if (notBlock.isNegate()) {
        return new AnalyzedExpr.UnaryOp(UnaryOperator.NOT, sub);
      }
      // A non-negated NOT block is a transparent wrapper: pass through to the lowered
      // sub-expression.
      return sub;
    }
    if (booleanExpression instanceof SQLAndBlock andBlock) {
      return lowerConnective(andBlock.getSubBlocks(), BinaryOperator.AND);
    }
    if (booleanExpression instanceof SQLOrBlock orBlock) {
      return lowerConnective(orBlock.getSubBlocks(), BinaryOperator.OR);
    }
    if (booleanExpression instanceof SQLIsNullCondition isNull) {
      return lowerIsNull(isNull);
    }
    if (booleanExpression instanceof SQLIsNotNullCondition isNotNull) {
      return lowerIsNotNull(isNotNull);
    }
    throw new UnsupportedAnalyzedNodeException(booleanExpression.getClass());
  }

  /// Lowers a comparison condition to a comparison [AnalyzedExpr.BinaryOp], mapping the concrete
  /// [SQLBinaryCompareOperator] to the IR comparison operator.
  private static AnalyzedExpr lowerComparison(SQLBinaryCondition condition) {
    BinaryOperator operator = toComparisonOperator(condition.getOperator());
    AnalyzedExpr left = lower(condition.getLeft());
    AnalyzedExpr right = lower(condition.getRight());
    return new AnalyzedExpr.BinaryOp(operator, left, right);
  }

  /// Maps an AST [SQLBinaryCompareOperator] to its IR comparison [BinaryOperator], or throws for
  /// the operators the IR does not model.
  ///
  /// Seven in-subset operator classes map to six IR constants — both `!=` spellings
  /// ([SQLNeqOperator] for `<>` and [SQLNeOperator] for `!=`) collapse to [BinaryOperator#NE]. The
  /// other eight comparison operators (`CONTAINSKEY`, `CONTAINSVALUE`, `IN`, `LIKE`, `LUCENE`,
  /// `NEAR`, `&&`, `WITHIN`) are out of subset and throw. The mapping is by concrete type because
  /// the operators are distinct classes, not enum constants.
  private static BinaryOperator toComparisonOperator(SQLBinaryCompareOperator operator) {
    return switch (operator) {
      case SQLEqualsOperator ignored -> BinaryOperator.EQ;
      // Both `!=` spellings collapse to NE: `<>` (SQLNeqOperator) and `!=` (SQLNeOperator). A single
      // case label cannot carry two type patterns in Java 21, so they stay two adjacent cases.
      case SQLNeqOperator ignored -> BinaryOperator.NE;
      case SQLNeOperator ignored -> BinaryOperator.NE;
      case SQLLtOperator ignored -> BinaryOperator.LT;
      case SQLLeOperator ignored -> BinaryOperator.LE;
      case SQLGtOperator ignored -> BinaryOperator.GT;
      case SQLGeOperator ignored -> BinaryOperator.GE;
      // SQLBinaryCompareOperator is not sealed (15 concrete subtypes), so the default arm is required
      // for exhaustiveness and keeps every out-of-subset operator on the throw path.
      default -> throw new UnsupportedAnalyzedNodeException(operator.getClass());
    };
  }

  /// Left-folds an n-ary list of boolean sub-blocks into nested binary [AnalyzedExpr.BinaryOp]
  /// nodes under the given connective operator (AND or OR).
  ///
  /// A single sub-block lowers to just that block (no wrapping `BinaryOp`). An empty sub-block
  /// list is structurally invalid in a parsed AST (the parser always produces at least one
  /// sub-block), so it throws.
  private static AnalyzedExpr lowerConnective(
      List<SQLBooleanExpression> subBlocks, BinaryOperator op) {
    if (subBlocks == null || subBlocks.isEmpty()) {
      // Pass the op-appropriate class so the exception names the actual block type.
      throw new UnsupportedAnalyzedNodeException(
          op == BinaryOperator.AND ? SQLAndBlock.class : SQLOrBlock.class);
    }
    AnalyzedExpr result = lowerBoolean(subBlocks.get(0));
    for (int i = 1; i < subBlocks.size(); i++) {
      result = new AnalyzedExpr.BinaryOp(op, result, lowerBoolean(subBlocks.get(i)));
    }
    return result;
  }

  /// Lowers `expr IS NULL` to `UnaryOp(IS_NULL, lower(expr))`. The `any()` / `all()` modifier
  /// variants are out of subset — the IR does not model property-iteration semantics — so they
  /// throw.
  private static AnalyzedExpr lowerIsNull(SQLIsNullCondition isNull) {
    SQLExpression expr = isNull.getExpression();
    if (expr.isFunctionAny() || expr.isFunctionAll()) {
      throw new UnsupportedAnalyzedNodeException(isNull.getClass());
    }
    return new AnalyzedExpr.UnaryOp(UnaryOperator.IS_NULL, lower(expr));
  }

  /// Lowers `expr IS NOT NULL` to `UnaryOp(NOT, UnaryOp(IS_NULL, lower(expr)))`. The `any()` /
  /// `all()` modifier variants are out of subset and throw.
  private static AnalyzedExpr lowerIsNotNull(SQLIsNotNullCondition isNotNull) {
    SQLExpression expr = AnalyzedAstAccess.isNotNullExpression(isNotNull);
    if (expr.isFunctionAny() || expr.isFunctionAll()) {
      throw new UnsupportedAnalyzedNodeException(isNotNull.getClass());
    }
    return new AnalyzedExpr.UnaryOp(
        UnaryOperator.NOT,
        new AnalyzedExpr.UnaryOp(UnaryOperator.IS_NULL, lower(expr)));
  }

  /// Lowers a bind parameter to a [AnalyzedExpr.Param] carrying the parameter's identity
  /// (number and optional name) but never a resolved value.
  private static AnalyzedExpr lowerInputParam(SQLInputParameter inputParam) {
    if (inputParam instanceof SQLPositionalParameter positional) {
      return new AnalyzedExpr.Param(
          AnalyzedAstAccess.positionalParamNumber(positional), null);
    }
    if (inputParam instanceof SQLNamedParameter named) {
      return new AnalyzedExpr.Param(
          AnalyzedAstAccess.namedParamNumber(named),
          AnalyzedAstAccess.namedParamName(named));
    }
    // Unknown SQLInputParameter subclass — out of subset.
    throw new UnsupportedAnalyzedNodeException(inputParam.getClass());
  }
}
