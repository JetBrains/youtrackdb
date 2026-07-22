package com.jetbrains.youtrackdb.internal.core.query.analyzed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/// The analyzed-expression intermediate representation (IR): a data-only expression tree the
/// optimizer and evaluator read instead of the SQL parse tree (AST).
///
/// `AnalyzedExpr` is a sealed interface permitting exactly six immutable record variants
/// ([Var], [Const], [Param], [BinaryOp], [UnaryOp], [FuncCall]). The variants carry data
/// only — equality, hashing, and accessors come from record defaults; no node carries
/// behavior. Sealing lets the compiler enforce an exhaustive `switch` over the variant set,
/// so the nodes need no `accept(visitor)` method: dispatch is a single static `switch` in
/// [#dispatch], and each downstream `visitX` call is a direct (monomorphic) call.
///
/// This follows the established sealed-interface-permitting-record idiom already used in the
/// codebase by `StorageReadResult` and `SqlCommandExecutionResult`.
///
/// VARIANT-ADDITION: adding a seventh variant is an intended compile-time break, not a
/// regression. A new variant breaks [#dispatch]'s `switch` (no `default` clause) and every
/// [AnalyzedExprVisitor] implementer (no default methods), forcing an audit of every dispatch
/// site. [AnalyzedExprTransform] is the one exception: its recurse-into-children defaults make
/// a new variant pass through silently, so adding a variant obliges a manual audit of every
/// transform pass — see the VARIANT-ADDITION note on [AnalyzedExprTransform].
public sealed interface AnalyzedExpr
    permits AnalyzedExpr.Var,
    AnalyzedExpr.Const,
    AnalyzedExpr.Param,
    AnalyzedExpr.BinaryOp,
    AnalyzedExpr.UnaryOp,
    AnalyzedExpr.FuncCall {

  /// An unresolved lexical name path — `["name"]` for a bare column reference.
  ///
  /// The `List<String>` shape is kept for a later slice that will replace `Var` with
  /// range-table-resolved references (names bound to their `FROM`-clause source), so this
  /// slice holds the lexical shape and does not bake in a resolution model that slice would
  /// discard.
  record Var(List<String> path) implements AnalyzedExpr {
  }

  /// A literal value (integer, string, boolean, or a number whose sign the parser already
  /// folded into the literal, so a negative literal arrives as one negated value).
  record Const(Object value) implements AnalyzedExpr {
  }

  /// A bind parameter reference — identity only, never a resolved value.
  ///
  /// Positional parameters (`?`) carry a `paramNumber` and a null `paramName`; named
  /// parameters (`:name`) carry both `paramNumber` and `paramName`. The value is resolved
  /// at evaluation time from the command context's input-parameter map, re-resolving on every
  /// execution — no value is baked into the IR, so a copied/reused plan picks up fresh
  /// parameter bindings.
  record Param(int paramNumber, @Nullable String paramName) implements AnalyzedExpr {
  }

  /// A binary operation: the four arithmetic operators (`+ - * /`), the six comparisons
  /// (`= != < <= > >=`), and the two boolean connectives (`AND`, `OR`), tagged by the IR's
  /// own [BinaryOperator].
  record BinaryOp(BinaryOperator op, AnalyzedExpr left, AnalyzedExpr right)
      implements AnalyzedExpr {
  }

  /// A unary operation: boolean `NOT` and `IS_NULL`, tagged by the IR's own [UnaryOperator].
  record UnaryOp(UnaryOperator op, AnalyzedExpr operand) implements AnalyzedExpr {
  }

  /// A function call. Method-call coercion syntax (e.g. `.asInteger()`) is structurally a
  /// function call and lowers to this variant.
  record FuncCall(String name, List<AnalyzedExpr> args) implements AnalyzedExpr {
  }

  /// Routes `expr` to the matching `visitX` method on `visitor` and returns its result.
  ///
  /// This is the one `switch` over the sealed variant set. It has no `default` clause: the
  /// sealed permits-list is a closed, known set, so the compiler enforces exhaustiveness, and
  /// adding a seventh variant fails to compile here until a new `case` is added.
  static <T> T dispatch(AnalyzedExpr expr, AnalyzedExprVisitor<T> visitor) {
    return switch (expr) {
      case Var v -> visitor.visitVar(v);
      case Const c -> visitor.visitConst(c);
      case Param p -> visitor.visitParam(p);
      case BinaryOp b -> visitor.visitBinaryOp(b);
      case UnaryOp u -> visitor.visitUnaryOp(u);
      case FuncCall f -> visitor.visitFuncCall(f);
    };
  }

  /// Recurses one level into `expr`'s children under transform `t`, sharing structure by
  /// reference identity.
  ///
  /// Per-variant behavior:
  ///
  /// - Leaf variants ([Var], [Const], [Param]) have no children and return `expr` itself.
  /// - Compound variants ([BinaryOp], [UnaryOp]) transform each child; when every child
  ///   returns the same instance it received (`==`), the input node is returned unchanged;
  ///   a new parent record is built only when at least one child changed.
  /// - [FuncCall] copies its argument list lazily — only on the first changed argument — so an
  ///   unchanged argument list is never reallocated.
  ///
  /// "Same instance" is reference identity (`==`), not value equality. A transform that
  /// rebuilds an `equals`-but-distinct copy of an unchanged node counts as "changed" and
  /// defeats the sharing; transform authors must return the input reference when no change
  /// applies and never rebuild an equal copy.
  static AnalyzedExpr transformChildren(AnalyzedExpr expr, AnalyzedExprTransform t) {
    return switch (expr) {
      case Var v -> v;
      case Const c -> c;
      case Param p -> p;
      case BinaryOp b -> {
        AnalyzedExpr left = dispatch(b.left(), t);
        AnalyzedExpr right = dispatch(b.right(), t);
        if (left == b.left() && right == b.right()) {
          yield b;
        }
        yield new BinaryOp(b.op(), left, right);
      }
      case UnaryOp u -> {
        AnalyzedExpr operand = dispatch(u.operand(), t);
        if (operand == u.operand()) {
          yield u;
        }
        yield new UnaryOp(u.op(), operand);
      }
      case FuncCall f -> {
        List<AnalyzedExpr> args = f.args();
        // newArgs stays null until the first changed argument and is non-null thereafter, so it
        // doubles as the "have I started accumulating?" flag: a null value at the end means no
        // argument changed and the call is returned unchanged.
        List<AnalyzedExpr> newArgs = null;
        for (int i = 0; i < args.size(); i++) {
          AnalyzedExpr arg = args.get(i);
          AnalyzedExpr transformed = dispatch(arg, t);
          if (transformed != arg && newArgs == null) {
            // First changed argument: copy the prefix that was unchanged so far, then
            // accumulate the rest. Leaving newArgs null until here keeps an all-unchanged
            // call from allocating a new list at all.
            newArgs = new ArrayList<>(args.subList(0, i));
          }
          if (newArgs != null) {
            newArgs.add(transformed);
          }
        }
        if (newArgs == null) {
          yield f;
        }
        // Single allocation: the accumulator itself backs the new node's arg list, wrapped
        // unmodifiable so the rebuilt list is read-only. The unchanged path above shares the
        // caller's original list verbatim, so neither path makes a second copy.
        yield new FuncCall(f.name(), Collections.unmodifiableList(newArgs));
      }
    };
  }
}
