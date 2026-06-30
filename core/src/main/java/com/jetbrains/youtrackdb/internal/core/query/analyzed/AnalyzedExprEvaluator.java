package com.jetbrains.youtrackdb.internal.core.query.analyzed;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Collate;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaProperty;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExpr.BinaryOp;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExpr.Const;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExpr.FuncCall;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExpr.UnaryOp;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExpr.Var;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.method.SQLMethod;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCompareOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLEqualsOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGeOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGtOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLeOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLtOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMathExpression.Operator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNeOperator;
import java.util.List;

/// The runtime over the analyzed-expression IR ([AnalyzedExpr]): walks a lowered tree and produces
/// the value it evaluates to against a [Result] row.
///
/// `AnalyzedExprEvaluator` implements [AnalyzedExprVisitor]&lt;[Object]&gt; directly. Because the
/// visitor has no default methods and the IR is a sealed set, the compiler forces this class to
/// enumerate every variant; a future IR variant breaks it at compile time. It carries
/// per-evaluation state (the row and the command context), so an instance is single-use per
/// `evaluate` call — the public [#evaluate] entry constructs one and discards it.
///
/// The evaluator has two reuse seams that keep it from re-deriving semantics the AST already owns,
/// so the AST and the IR cannot drift:
///
/// - **Arithmetic** reaches the shared numeric-promotion engine through the AST
///   [Operator#apply(Object, Object)] entry (which routes through `NumericOps`), after mapping the
///   IR [BinaryOperator] arithmetic constant to its [Operator] counterpart. That object-level entry
///   carries null-propagation, `Date ± Long`, and `String` concat — semantics a direct
///   `NumericOps.apply(Number, Operator, Number)` call would skip or throw on.
/// - **Comparison** replicates [com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCondition]'s
///   slow-path `evaluate(Result, ctx)` sequence: evaluate both operands, fetch the collate
///   left-then-right, apply the collate transform when non-null, then delegate to a freshly
///   constructed [SQLBinaryCompareOperator] of the same concrete class the AST uses for the operator.
///   The operators are stateless, so a reconstructed instance runs the AST's exact `execute` body and
///   reproduces both the EQ/NE session difference and the ordering `doCompare`-vs-0 mapping by class
///   identity.
///
/// This is the slow path only: the AST evaluation fast paths (in-place comparison, `AND`/`OR`
/// short-circuit) are not mirrored here because this slice ships no live executor consumer and its
/// only acceptance gate is round-trip parity against the AST oracle. A later slice that evaluates the
/// IR on the hot path must add those fast paths back.
public final class AnalyzedExprEvaluator implements AnalyzedExprVisitor<Object> {

  private final Result row;
  private final CommandContext ctx;

  private AnalyzedExprEvaluator(Result row, CommandContext ctx) {
    this.row = row;
    this.ctx = ctx;
  }

  /// Evaluates `expr` against `row` under `ctx` and returns the produced value.
  ///
  /// This is the single public entry. The analyzed layer evaluates over [Result] rows only (a single
  /// overload); an `Identifiable`-only caller wraps its input in a synthetic entity-backed [Result]
  /// via [#wrap(Identifiable, DatabaseSessionEmbedded)] first, so it too evaluates through the
  /// collation-applying path.
  public static Object evaluate(AnalyzedExpr expr, Result row, CommandContext ctx) {
    return AnalyzedExpr.dispatch(expr, new AnalyzedExprEvaluator(row, ctx));
  }

  /// Wraps an [Identifiable] in a synthetic entity-backed [Result] so it can be evaluated through the
  /// single [Result] overload.
  ///
  /// The analyzed layer unifies the AST's two `evaluate` overloads into one [Result] path that always
  /// applies collation. The AST's `evaluate(Identifiable, ctx)` overload skips collation as a
  /// deliberate fast-path inconsistency; wrapping an `Identifiable` here and evaluating through the
  /// [Result] path applies collation uniformly. That is an intentional correctness convergence: a
  /// `ci`-collated comparison begins matching case-insensitively where the AST `Identifiable` path did
  /// not. A caller on this path must have validated that convergence for its comparison sites first.
  public static Result wrap(Identifiable identifiable, DatabaseSessionEmbedded session) {
    return new ResultInternal(session, identifiable);
  }

  @Override
  public Object visitVar(Var var) {
    // Single-segment lexical name resolution, mirroring the AST's
    // SQLSuffixIdentifier.execute(Result) column lookup: read the property when present, otherwise
    // fall back to result metadata / temporary properties, otherwise null. The lowering pass only
    // produces single-segment Vars (a multi-segment path throws at lowering), so path() has exactly
    // one element here. A multi-segment Var is a lowering-contract violation, not a value to read
    // through; throw in production rather than silently reading path.get(0) (an assert would be a
    // no-op without -ea and let the broken invariant produce a wrong result).
    if (var.path().size() != 1) {
      throw new IllegalStateException("evaluator expects single-segment Var, got " + var.path());
    }
    String name = var.path().get(0);
    if (row == null) {
      return null;
    }
    if (row.hasProperty(name)) {
      return row.getProperty(name);
    }
    if (row instanceof ResultInternal resultInternal) {
      if (resultInternal.getMetadataKeys().contains(name)) {
        return resultInternal.getMetadata(name);
      }
      if (resultInternal.getTemporaryProperties().contains(name)) {
        return resultInternal.getTemporaryProperty(name);
      }
    }
    return null;
  }

  @Override
  public Object visitConst(Const constant) {
    return constant.value();
  }

  @Override
  public Object visitBinaryOp(BinaryOp binaryOp) {
    return switch (binaryOp.op()) {
      case PLUS, MINUS, STAR, SLASH -> evaluateArithmetic(binaryOp);
      case EQ, NE, LT, LE, GT, GE -> evaluateComparison(binaryOp);
    };
  }

  @Override
  public Object visitUnaryOp(UnaryOp unaryOp) {
    return switch (unaryOp.op()) {
      case NOT -> {
        Object operand = AnalyzedExpr.dispatch(unaryOp.operand(), this);
        // The lowering subset produces NOT only over a boolean sub-expression (a comparison or a
        // nested NOT), so the operand is a Boolean here. Cast rather than coerce: a non-Boolean
        // operand is a lowering-contract violation, not a value to truthiness-convert, so a
        // ClassCastException is the right failure.
        yield !((Boolean) operand);
      }
    };
  }

  @Override
  public Object visitFuncCall(FuncCall funcCall) {
    // Method-call coercion (e.g. name.asInteger()) lowers to a FuncCall whose first argument is the
    // target value and whose remaining arguments are the method parameters. Reproduce the AST's
    // SQLMethodCall path: resolve the method by name, evaluate the target and parameters, then invoke
    // SQLMethod.execute with the target as both the receiver and the ioResult (the AST passes the
    // base value as targetObjects and ioResult). FuncCall.args() is read-only by convention, so it
    // is read without mutation.
    List<AnalyzedExpr> args = funcCall.args();
    // FuncCall always carries at least the target as args[0] (the lowering pass puts the base value
    // there before any method parameter). An empty args list is a lowering-contract violation; throw
    // in production rather than indexing args.get(0) out of bounds (an assert would be a no-op
    // without -ea and let the broken invariant produce a late, opaque crash).
    if (args.isEmpty()) {
      throw new IllegalStateException("FuncCall must carry at least the target argument");
    }
    Object target = AnalyzedExpr.dispatch(args.get(0), this);

    SQLMethod method = SQLEngine.getMethod(funcCall.name());
    if (method == null) {
      // An unresolved method name is out of the covered subset for this slice. The lowering pass
      // does not validate method existence, so reject it here rather than NPE on a null method.
      throw new UnsupportedAnalyzedNodeException(FuncCall.class);
    }

    Object[] params = new Object[args.size() - 1];
    for (int i = 1; i < args.size(); i++) {
      params[i - 1] = AnalyzedExpr.dispatch(args.get(i), this);
    }

    // The AST's SQLModifier seeds $current with the current record before the method runs, but only
    // when it is unset, so a method that reads $current sees the row. Mirror that guarded seed.
    if (ctx != null && ctx.getSystemVariable(CommandContext.VAR_CURRENT) == null && row != null) {
      ctx.setSystemVariable(CommandContext.VAR_CURRENT, row);
    }
    return method.execute(target, row, ctx, target, params);
  }

  /// Evaluates an arithmetic [BinaryOp] through the AST [Operator#apply(Object, Object)] entry.
  ///
  /// Mapping the IR operator to its AST [Operator] and calling the object-level `apply` keeps every
  /// numeric-promotion rule (null-propagation, `Date ± Long`, `String` concat) in the one shared
  /// engine, so AST and IR arithmetic stay byte-for-byte identical.
  private Object evaluateArithmetic(BinaryOp binaryOp) {
    Object left = AnalyzedExpr.dispatch(binaryOp.left(), this);
    Object right = AnalyzedExpr.dispatch(binaryOp.right(), this);
    return toArithmeticOperator(binaryOp.op()).apply(left, right);
  }

  /// Maps an IR arithmetic [BinaryOperator] to its AST [Operator] counterpart.
  ///
  /// The lowering pass (`AnalyzedExprLowerer.toArithmeticOperator`) maps AST `Operator` to IR
  /// `BinaryOperator`, but that map is private and AST→IR; the evaluator needs the inverse. The IR
  /// carries only the four arithmetic constants here, so the comparison constants cannot reach this
  /// switch — `visitBinaryOp` routes them to the comparison path before this is called.
  private static Operator toArithmeticOperator(BinaryOperator op) {
    return switch (op) {
      case PLUS -> Operator.PLUS;
      case MINUS -> Operator.MINUS;
      case STAR -> Operator.STAR;
      case SLASH -> Operator.SLASH;
      // The comparison constants never reach here (visitBinaryOp dispatches them to the comparison
      // path), so the default arm is an unreachable-by-construction programming-error guard.
      default -> throw new IllegalStateException(
          "comparison operator routed to arithmetic path: " + op);
    };
  }

  /// Evaluates a comparison [BinaryOp] by replicating the AST's slow-path
  /// `SQLBinaryCondition.evaluate(Result, ctx)` sequence.
  ///
  /// The four steps reproduce the AST exactly: evaluate both operands; fetch the collate from the
  /// left operand, then the right if the left has none; apply the collate transform to both operands
  /// when non-null; delegate to a freshly built operator instance of the AST's own concrete operator
  /// class. Reconstructing the operator (the IR discarded the AST operator instance during lowering)
  /// reproduces the EQ/NE session-threading difference and the ordering `doCompare`-vs-0 mapping by
  /// class identity, since the operators are stateless.
  private Object evaluateComparison(BinaryOp binaryOp) {
    Object leftVal = AnalyzedExpr.dispatch(binaryOp.left(), this);
    Object rightVal = AnalyzedExpr.dispatch(binaryOp.right(), this);

    Collate collate = collateFor(binaryOp.left());
    if (collate == null) {
      collate = collateFor(binaryOp.right());
    }
    if (collate != null) {
      leftVal = collate.transform(leftVal);
      rightVal = collate.transform(rightVal);
    }

    SQLBinaryCompareOperator operator = comparisonOperator(binaryOp.op());
    DatabaseSessionEmbedded session = ctx == null ? null : ctx.getDatabaseSession();
    return operator.execute(session, leftVal, rightVal);
  }

  /// Resolves the [Collate] for a comparison operand, mirroring the AST's single-property resolution
  /// in `SQLSuffixIdentifier.getCollate` — guarded at every hop.
  ///
  /// The collate applies only to a [Var] operand (a column reference); a literal or computed
  /// sub-expression has no column to resolve and returns `null`, matching the AST's behavior for a
  /// non-column operand. For a `Var`, the resolution is `isEntity()` guard → `EntityImpl` downcast →
  /// `getImmutableSchemaClass` → `SchemaClass.getProperty(name)` → `SchemaProperty.getCollate()`,
  /// returning `null` on any miss. The guards keep a schemaless row or an absent column returning
  /// `null` collate rather than throwing — the guard-free happy path would NPE/CCE where the AST
  /// returns `null` collate, breaking parity.
  private Collate collateFor(AnalyzedExpr operand) {
    if (!(operand instanceof Var var)) {
      return null;
    }
    // Only the single-segment Var carries a column to resolve; the lowering subset produces no
    // multi-segment Var, so a single name is expected.
    if (var.path().size() != 1 || row == null || ctx == null) {
      return null;
    }
    if (!row.isEntity()) {
      return null;
    }
    // getImmutableSchemaClass is declared on EntityImpl, not the public Entity interface, so the
    // downcast is required to reach the schema class the AST's getCollate chain consumes.
    EntityImpl entity = (EntityImpl) row.asEntity();
    SchemaClass schemaClass = entity.getImmutableSchemaClass(ctx.getDatabaseSession());
    if (schemaClass == null) {
      return null;
    }
    SchemaProperty property = schemaClass.getProperty(var.path().get(0));
    if (property == null) {
      return null;
    }
    return property.getCollate();
  }

  /// Builds a fresh [SQLBinaryCompareOperator] of the concrete class the AST uses for this IR
  /// comparison operator.
  ///
  /// The operators are stateless, so a reconstructed instance runs the identical `execute` body as
  /// the AST's. The constructor argument is the parser node id, unused at evaluate time, so `-1` is
  /// the conventional "not parser-built" id. `INSTANCE` is absent on `SQLNeOperator` / `SQLGeOperator`,
  /// so a uniform shared-instance accessor would not compile; the `new SQLXxxOperator(-1)` constructor
  /// is the uniform construction path. Both `!=` spellings (`!=`, `<>`) collapsed to NE during
  /// lowering, so NE reconstructs the `SQLNeOperator` class for either spelling — behaviorally
  /// identical since both AST classes share the same `execute` body.
  private static SQLBinaryCompareOperator comparisonOperator(BinaryOperator op) {
    return switch (op) {
      case EQ -> new SQLEqualsOperator(-1);
      case NE -> new SQLNeOperator(-1);
      case LT -> new SQLLtOperator(-1);
      case LE -> new SQLLeOperator(-1);
      case GT -> new SQLGtOperator(-1);
      case GE -> new SQLGeOperator(-1);
      // The arithmetic constants never reach here (visitBinaryOp dispatches them to the arithmetic
      // path), so the default arm is an unreachable-by-construction programming-error guard.
      default -> throw new IllegalStateException(
          "arithmetic operator routed to comparison path: " + op);
    };
  }
}
