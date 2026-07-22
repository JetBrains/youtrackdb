package com.jetbrains.youtrackdb.internal.core.query.analyzed;

/// The analyzed-expression IR's own unary operator tag, carried by {@link AnalyzedExpr.UnaryOp}.
///
/// `NOT` and `IS_NULL` are the two unary operators. There is intentionally no `MINUS`: the
/// grammar has no `-expr` node for non-literals — unary minus is a parse-time `sign` flag folded
/// into a numeric literal — so lowering never produces a unary-minus node. `IS NOT NULL` is
/// represented as `NOT(IS_NULL(expr))` — a composed form, not a separate operator.
public enum UnaryOperator {
  NOT, IS_NULL
}
