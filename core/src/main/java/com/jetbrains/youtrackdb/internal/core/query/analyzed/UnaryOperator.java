package com.jetbrains.youtrackdb.internal.core.query.analyzed;

/// The analyzed-expression IR's own unary operator tag, carried by {@link AnalyzedExpr.UnaryOp}.
///
/// Boolean `NOT` is the only unary operator in this slice's subset. There is intentionally no
/// `MINUS`: the grammar has no `-expr` node for non-literals — unary minus is a parse-time
/// `sign` flag folded into a numeric literal — so lowering never produces a unary-minus node.
public enum UnaryOperator {
  NOT
}
