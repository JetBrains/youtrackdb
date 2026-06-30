package com.jetbrains.youtrackdb.internal.core.query.analyzed;

/// The analyzed-expression IR's own binary operator tag, carried by {@link AnalyzedExpr.BinaryOp}.
///
/// These are the operators this slice's lowering subset covers: the four arithmetic operators
/// and the six comparisons. This enum is deliberately distinct from the AST's
/// `SQLMathExpression.Operator` — the IR carries its own small, closed operator set so the
/// IR stays decoupled from the parse-tree's operator model (which also includes shifts,
/// bitwise, remainder, and null-coalescing constants this IR does not represent).
public enum BinaryOperator {
  PLUS, MINUS, STAR, SLASH, EQ, NE, LT, LE, GT, GE
}
