package com.jetbrains.youtrackdb.internal.core.query.analyzed;

import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExpr.BinaryOp;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExpr.Const;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExpr.FuncCall;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExpr.Param;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExpr.UnaryOp;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExpr.Var;

/// A rewrite pass over the [AnalyzedExpr] IR — the shape later optimizer slices will use.
///
/// Each of the six `visitX` methods carries a recurse-into-children default: leaf variants
/// ([Var], [Const], [Param]) return themselves, and compound variants ([BinaryOp], [UnaryOp],
/// [FuncCall]) delegate to [AnalyzedExpr#transformChildren], which recurses one level and
/// shares unchanged subtrees by reference identity. A pass that rewrites one variant overrides
/// only that `visitX` and inherits pass-through for the rest, so it writes no boilerplate for
/// the variants it does not touch.
///
/// The defaults keep the base [AnalyzedExprVisitor] strict (it has no defaults), so a direct
/// visitor such as the evaluator still breaks at compile time on a new variant.
///
/// VARIANT-ADDITION: this is the one place the compile-time exhaustiveness guarantee does
/// not reach. Because every `visitX` here has a pass-through default, adding a seventh IR
/// variant does not break a transform pass at compile time — a pass that needs special
/// handling for the new variant would default-recurse silently instead. Adding a variant
/// therefore obliges a manual audit of every transform pass to confirm the pass-through
/// default is correct for it. (This slice ships no transform pass, so the mechanical
/// reflective backstop is a later-slice obligation.)
public interface AnalyzedExprTransform extends AnalyzedExprVisitor<AnalyzedExpr> {

  @Override
  default AnalyzedExpr visitVar(Var var) {
    return var;
  }

  @Override
  default AnalyzedExpr visitConst(Const constant) {
    return constant;
  }

  /// Leaf pass-through: [Param] has no children and returns itself unchanged.
  @Override
  default AnalyzedExpr visitParam(Param param) {
    return param;
  }

  @Override
  default AnalyzedExpr visitBinaryOp(BinaryOp binaryOp) {
    return AnalyzedExpr.transformChildren(binaryOp, this);
  }

  @Override
  default AnalyzedExpr visitUnaryOp(UnaryOp unaryOp) {
    return AnalyzedExpr.transformChildren(unaryOp, this);
  }

  @Override
  default AnalyzedExpr visitFuncCall(FuncCall funcCall) {
    return AnalyzedExpr.transformChildren(funcCall, this);
  }
}
