package com.jetbrains.youtrackdb.internal.core.query.analyzed;

import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExpr.BinaryOp;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExpr.Const;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExpr.FuncCall;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExpr.Param;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExpr.UnaryOp;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExpr.Var;

/// A visitor over the [AnalyzedExpr] IR, with one `visitX` method per variant.
///
/// The visitor carries no default methods: a direct implementer (the evaluator, or any future
/// pass that returns something other than `AnalyzedExpr`) must enumerate every variant. Adding
/// a seventh variant therefore breaks every direct implementer at compile time — the strictness
/// that backs the IR's exhaustive-dispatch guarantee. The relaxation for rewrite passes
/// (recurse-into-children defaults)
/// lives only on [AnalyzedExprTransform] and never touches this base visitor.
///
/// Dispatch is centralized in [AnalyzedExpr#dispatch]; visitors are invoked through it rather
/// than via an `accept(visitor)` method on the nodes.
///
/// @param <T> the result type each `visitX` produces
public interface AnalyzedExprVisitor<T> {

  T visitVar(Var var);

  T visitConst(Const constant);

  T visitParam(Param param);

  T visitBinaryOp(BinaryOp binaryOp);

  T visitUnaryOp(UnaryOp unaryOp);

  T visitFuncCall(FuncCall funcCall);
}
