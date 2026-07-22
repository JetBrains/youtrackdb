package com.jetbrains.youtrackdb.internal.core.query.analyzed;

/// The analyzed-expression IR's own binary operator tag, carried by {@link AnalyzedExpr.BinaryOp}.
///
/// These are the operators this slice's lowering subset covers: the four arithmetic operators,
/// the six comparisons, and the two boolean connectives (`AND`, `OR`). This enum is deliberately
/// distinct from the AST's `SQLMathExpression.Operator` — the IR carries its own small, closed
/// operator set so the IR stays decoupled from the parse-tree's operator model (which also
/// includes shifts, bitwise, remainder, and null-coalescing constants this IR does not represent).
/// `AND` and `OR` are binary here even though the AST models them as n-ary `subBlocks`; the
/// lowerer left-folds an n-ary block into nested binary `BinaryOp` nodes.
public enum BinaryOperator {
  PLUS, MINUS, STAR, SLASH, EQ, NE, LT, LE, GT, GE, AND, OR
}
