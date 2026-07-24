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
import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExpr.Param;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExpr.UnaryOp;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExpr.Var;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.record.impl.InPlaceResult;
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
import java.util.Map;
import java.util.OptionalInt;

/// The runtime over the analyzed-expression IR ([AnalyzedExpr]): walks a lowered tree and produces
/// the value it evaluates to against a [Result] row.
///
/// The evaluator is a pure-static utility class with no instance state. Per-evaluation state (the
/// row and the command context) is threaded as method parameters through the private recursive
/// [#eval] switch, so evaluation allocates nothing per row and incurs zero ThreadLocal overhead.
/// Tracks 04/05 call `evaluate` per row in a hot loop, so zero-allocation and minimal state-access
/// cost are hard constraints.
///
/// The private [#eval] method uses a `switch` over the sealed [AnalyzedExpr] variant set (the same
/// sealed permits list the [AnalyzedExprVisitor] pattern-matches against). Because the switch has
/// no `default` clause, adding a new IR variant breaks this class at compile time — the same
/// exhaustiveness guarantee the visitor provides, without the visitor indirection.
///
/// The evaluator has two reuse seams that keep it from re-deriving semantics the AST already owns,
/// so the AST and the IR cannot drift:
///
/// - **Arithmetic** reaches the shared numeric-promotion engine through the AST
///   [Operator#apply(Object, Object)] entry (which routes through `NumericOps`), after mapping the
///   IR [BinaryOperator] arithmetic constant to its [Operator] counterpart. That object-level entry
///   carries null-propagation, `Date ± Long`, and `String` concat — semantics a direct
///   `NumericOps.apply(Number, Operator, Number)` call would skip or throw on.
/// - **Comparison** has two paths. The **fast path** (ported from YTDB-628) runs before the slow
///   path for `property <op> constant` patterns where the row is an `EntityImpl`: it calls
///   `isPropertyEqualTo` (EQ/NE) or `comparePropertyTo` (range ops) directly on the entity,
///   avoiding deserialization. The **slow path** replicates
///   [com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCondition]'s `evaluate(Result, ctx)`
///   sequence: evaluate both operands, fetch the collate left-then-right, apply the collate transform
///   when non-null, then delegate to a pre-built static [SQLBinaryCompareOperator] singleton. The
///   operators are stateless, so a pre-built instance runs the AST's exact `execute` body and
///   reproduces both the EQ/NE session difference and the ordering `doCompare`-vs-0 mapping by class
///   identity.
/// - **AND/OR** short-circuit: LEFT is evaluated first; AND returns false immediately when left is
///   false; OR returns true immediately when left is true; otherwise the right operand is evaluated.
/// - **IS_NULL** is untyped: the operand is evaluated and compared to null directly (no Boolean
///   cast), returning `operand == null`. `IS NOT NULL` composes as `NOT(IS_NULL(expr))`.
public final class AnalyzedExprEvaluator {

  // Pre-built static operator singletons — the operators are stateless, so one instance per
  // concrete class avoids per-comparison allocation. The constructor argument is the parser
  // node id, unused at evaluate time, so -1 is the conventional "not parser-built" id.
  private static final SQLBinaryCompareOperator OP_EQ = new SQLEqualsOperator(-1);
  private static final SQLBinaryCompareOperator OP_NE = new SQLNeOperator(-1);
  private static final SQLBinaryCompareOperator OP_LT = new SQLLtOperator(-1);
  private static final SQLBinaryCompareOperator OP_LE = new SQLLeOperator(-1);
  private static final SQLBinaryCompareOperator OP_GT = new SQLGtOperator(-1);
  private static final SQLBinaryCompareOperator OP_GE = new SQLGeOperator(-1);

  private AnalyzedExprEvaluator() {
  }

  /// Evaluates `expr` against `row` under `ctx` and returns the produced value.
  ///
  /// This is the single public entry. The analyzed layer evaluates over [Result] rows only (a single
  /// overload); an `Identifiable`-only caller wraps its input in a synthetic entity-backed [Result]
  /// via [#wrap(Identifiable, DatabaseSessionEmbedded)] first, so it too evaluates through the
  /// collation-applying path. The evaluator allocates nothing per row: `(row, ctx)` flow as method
  /// parameters through the recursive [#eval] switch — no ThreadLocal, no instance fields, no
  /// shared mutable state.
  public static Object evaluate(AnalyzedExpr expr, Result row, CommandContext ctx) {
    return eval(expr, row, ctx);
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

  /// Recursive evaluation switch over the sealed [AnalyzedExpr] variant set.
  ///
  /// This has no `default` clause: the sealed permits-list is a closed, known set, so adding a
  /// new IR variant fails to compile here until a new `case` is added — the same exhaustiveness
  /// guarantee the [AnalyzedExprVisitor] provides, without the visitor indirection. `(row, ctx)`
  /// flow as direct parameters, incurring zero ThreadLocal or instance-field overhead per call.
  private static Object eval(AnalyzedExpr expr, Result row, CommandContext ctx) {
    return switch (expr) {
      case AnalyzedExpr.Var v -> evalVar(v, row);
      case AnalyzedExpr.Const c -> c.value();
      case AnalyzedExpr.Param p -> evalParam(p, ctx);
      case AnalyzedExpr.BinaryOp b -> evalBinaryOp(b, row, ctx);
      case AnalyzedExpr.UnaryOp u -> evalUnaryOp(u, row, ctx);
      case AnalyzedExpr.FuncCall f -> evalFuncCall(f, row, ctx);
    };
  }

  /// Single-segment lexical name resolution, mirroring the AST's
  /// SQLSuffixIdentifier.execute(Result) column lookup: read the property when present, otherwise
  /// fall back to result metadata / temporary properties, otherwise null. The lowering pass only
  /// produces single-segment Vars (a multi-segment path throws at lowering), so path() has exactly
  /// one element here.
  ///
  /// NOTE: $-prefixed identifiers (LET / context variables) are rejected at the lowerer, so they
  /// never reach this method. Resolution of $-vars via ctx.getVariable() is deferred to a later
  /// slice (S17).
  private static Object evalVar(AnalyzedExpr.Var var, Result row) {
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

  /// Resolves a bind parameter at evaluation time from the command context's input-parameter map,
  /// mirroring the AST exactly: named params try paramName first, then fall back to paramNumber;
  /// positional params use paramNumber only. Re-resolves every execution (no value baked in).
  private static Object evalParam(AnalyzedExpr.Param param, CommandContext ctx) {
    if (ctx == null) {
      return null;
    }
    Map<Object, Object> params = ctx.getInputParameters();
    if (params == null) {
      return null;
    }
    // Named parameter: resolve by name if present; otherwise fall through to positional index.
    if (param.paramName() != null && params.containsKey(param.paramName())) {
      return params.get(param.paramName());
    }
    return params.get(param.paramNumber());
  }

  private static Object evalBinaryOp(BinaryOp binaryOp, Result row, CommandContext ctx) {
    return switch (binaryOp.op()) {
      case PLUS, MINUS, STAR, SLASH -> evaluateArithmetic(binaryOp, row, ctx);
      case EQ, NE, LT, LE, GT, GE -> evaluateComparison(binaryOp, row, ctx);
      case AND -> evaluateAnd(binaryOp, row, ctx);
      case OR -> evaluateOr(binaryOp, row, ctx);
    };
  }

  private static Object evalUnaryOp(UnaryOp unaryOp, Result row, CommandContext ctx) {
    return switch (unaryOp.op()) {
      case NOT -> {
        Object operand = eval(unaryOp.operand(), row, ctx);
        // The lowering subset produces NOT only over a boolean sub-expression (a comparison, a
        // nested NOT, or IS_NULL), so the operand is a Boolean here. Cast rather than coerce:
        // a non-Boolean operand is a lowering-contract violation, not a value to
        // truthiness-convert, so a ClassCastException is the right failure.
        yield !((Boolean) operand);
      }
      case IS_NULL -> {
        // Untyped null check: evaluate the operand and test for null. No Boolean cast — this is
        // distinct from NOT which expects a Boolean operand. IS NOT NULL composes as
        // NOT(IS_NULL(expr)), so the two-valued engine (comparisons return false on null
        // operands) composes correctly.
        Object operand = eval(unaryOp.operand(), row, ctx);
        yield operand == null;
      }
    };
  }

  /// Evaluates a [FuncCall] (method-call coercion). The first argument is the target value;
  /// remaining arguments are method parameters.
  private static Object evalFuncCall(FuncCall funcCall, Result row, CommandContext ctx) {
    List<AnalyzedExpr> args = funcCall.args();
    // FuncCall always carries at least the target as args[0] (the lowering pass puts the base value
    // there before any method parameter). An empty args list is a lowering-contract violation.
    if (args.isEmpty()) {
      throw new IllegalStateException("FuncCall must carry at least the target argument");
    }
    Object target = eval(args.get(0), row, ctx);

    SQLMethod method = SQLEngine.getMethod(funcCall.name());
    if (method == null) {
      throw new UnsupportedAnalyzedNodeException(FuncCall.class);
    }

    Object[] params = new Object[args.size() - 1];
    for (int i = 1; i < args.size(); i++) {
      params[i - 1] = eval(args.get(i), row, ctx);
    }

    // The AST's SQLModifier seeds $current with the current record before the method runs, but only
    // when it is unset, so a method that reads $current sees the row. Mirror that guarded seed.
    if (ctx != null && ctx.getSystemVariable(CommandContext.VAR_CURRENT) == null && row != null) {
      ctx.setSystemVariable(CommandContext.VAR_CURRENT, row);
    }
    return method.execute(target, row, ctx, target, params);
  }

  /// Evaluates AND with mandatory lazy short-circuit: evaluate LEFT first; if left is false,
  /// return false WITHOUT evaluating right. Mirrors the AST's `SQLAndBlock.evaluate()`.
  private static Object evaluateAnd(BinaryOp binaryOp, Result row, CommandContext ctx) {
    Object left = eval(binaryOp.left(), row, ctx);
    if (Boolean.FALSE.equals(left)) {
      return false;
    }
    return eval(binaryOp.right(), row, ctx);
  }

  /// Evaluates OR with mandatory lazy short-circuit: evaluate LEFT first; if left is true,
  /// return true WITHOUT evaluating right. Mirrors the AST's `SQLOrBlock.evaluate()`.
  private static Object evaluateOr(BinaryOp binaryOp, Result row, CommandContext ctx) {
    Object left = eval(binaryOp.left(), row, ctx);
    if (Boolean.TRUE.equals(left)) {
      return true;
    }
    return eval(binaryOp.right(), row, ctx);
  }

  /// Evaluates an arithmetic [BinaryOp] through the AST [Operator#apply(Object, Object)] entry.
  ///
  /// Mapping the IR operator to its AST [Operator] and calling the object-level `apply` keeps every
  /// numeric-promotion rule (null-propagation, `Date ± Long`, `String` concat) in the one shared
  /// engine, so AST and IR arithmetic stay byte-for-byte identical.
  private static Object evaluateArithmetic(BinaryOp binaryOp, Result row, CommandContext ctx) {
    Object left = eval(binaryOp.left(), row, ctx);
    Object right = eval(binaryOp.right(), row, ctx);
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

  /// Evaluates a comparison [BinaryOp] by first attempting the YTDB-628 in-place fast path, then
  /// falling back to the AST's slow-path sequence.
  ///
  /// **Fast path** (ported from `SQLBinaryCondition.tryInPlaceComparison`): when the left operand
  /// is a single `Var`, the right operand resolves to a value without row access (`Const` or
  /// `Param`), and the row wraps an `EntityImpl`, the comparison is delegated directly to
  /// `EntityImpl.isPropertyEqualTo` (EQ/NE) or `comparePropertyTo` (range ops). The mapping of
  /// `InPlaceResult`/`OptionalInt` replicates the AST exactly, including the NE inversion
  /// (`TRUE→false`, `FALSE→true`). Any `FALLBACK` or empty result (including non-default
  /// collation like `ci`) falls through to the slow path which applies collation correctly.
  ///
  /// **Slow path**: evaluate both operands; fetch the collate from the left operand, then the right
  /// if the left has none; apply the collate transform to both operands when non-null; delegate to
  /// a pre-built static [SQLBinaryCompareOperator] singleton.
  private static Object evaluateComparison(BinaryOp binaryOp, Result row, CommandContext ctx) {
    // YTDB-628 in-place fast path: avoid deserialization for simple "property <op> constant"
    // patterns. Guards mirror SQLBinaryCondition.evaluate(Result, ctx) exactly.
    if (binaryOp.left() instanceof Var var && var.path().size() == 1
        && isEarlyResolvable(binaryOp.right())
        && row instanceof ResultInternal ri
        && ri.asEntityOrNull() instanceof EntityImpl entityImpl) {
      String propName = var.path().get(0);
      Object rightVal = eval(binaryOp.right(), row, ctx);

      Boolean fastResult = tryInPlaceComparison(
          binaryOp.op(), entityImpl, propName, rightVal);
      if (fastResult != null) {
        return fastResult;
      }
      // FALLBACK: rightVal already computed, only need leftVal for slow path
      Object leftVal = eval(binaryOp.left(), row, ctx);
      return evaluateComparisonSlow(
          binaryOp.op(), binaryOp, leftVal, rightVal, row, ctx);
    }

    // Slow path: evaluate both operands, apply collation, delegate to operator.
    Object leftVal = eval(binaryOp.left(), row, ctx);
    Object rightVal = eval(binaryOp.right(), row, ctx);
    return evaluateComparisonSlow(
        binaryOp.op(), binaryOp, leftVal, rightVal, row, ctx);
  }

  /// Whether the given IR node resolves to a value without accessing the current row —
  /// `Const` (literal) or `Param` (resolved from the command context, not the row).
  private static boolean isEarlyResolvable(AnalyzedExpr expr) {
    return expr instanceof Const || expr instanceof Param;
  }

  /// Attempts in-place comparison of an entity property against a right-hand value, replicating
  /// `SQLBinaryCondition.tryInPlaceComparison` EXACTLY. Returns the comparison result (true/false)
  /// when in-place succeeded, or `null` when the caller must fall back to the slow path.
  private static Boolean tryInPlaceComparison(
      BinaryOperator op, EntityImpl entityImpl, String propName, Object rightVal) {
    return switch (op) {
      case EQ -> {
        InPlaceResult r = entityImpl.isPropertyEqualTo(propName, rightVal);
        yield r != InPlaceResult.FALLBACK ? (r == InPlaceResult.TRUE) : null;
      }
      case NE -> {
        // NE inverts: TRUE means equal → NE false; FALSE means not-equal → NE true.
        InPlaceResult r = entityImpl.isPropertyEqualTo(propName, rightVal);
        yield r != InPlaceResult.FALLBACK ? (r == InPlaceResult.FALSE) : null;
      }
      case LT -> {
        OptionalInt cmp = entityImpl.comparePropertyTo(propName, rightVal);
        yield cmp.isPresent() ? (cmp.getAsInt() < 0) : null;
      }
      case LE -> {
        OptionalInt cmp = entityImpl.comparePropertyTo(propName, rightVal);
        yield cmp.isPresent() ? (cmp.getAsInt() <= 0) : null;
      }
      case GT -> {
        OptionalInt cmp = entityImpl.comparePropertyTo(propName, rightVal);
        yield cmp.isPresent() ? (cmp.getAsInt() > 0) : null;
      }
      case GE -> {
        OptionalInt cmp = entityImpl.comparePropertyTo(propName, rightVal);
        yield cmp.isPresent() ? (cmp.getAsInt() >= 0) : null;
      }
      // AND/OR/arithmetic never reach the comparison path.
      default -> null;
    };
  }

  /// Slow-path comparison: apply collation then delegate to the pre-built operator singleton.
  private static Object evaluateComparisonSlow(
      BinaryOperator op, BinaryOp binaryOp,
      Object leftVal, Object rightVal, Result row, CommandContext ctx) {
    Collate collate = collateFor(binaryOp.left(), row, ctx);
    if (collate == null) {
      collate = collateFor(binaryOp.right(), row, ctx);
    }
    if (collate != null) {
      leftVal = collate.transform(leftVal);
      rightVal = collate.transform(rightVal);
    }
    SQLBinaryCompareOperator operator = comparisonOperator(op);
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
  private static Collate collateFor(AnalyzedExpr operand, Result row, CommandContext ctx) {
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

  /// Returns the pre-built static [SQLBinaryCompareOperator] singleton for this IR comparison
  /// operator.
  ///
  /// The operators are stateless, so a single pre-built instance per concrete class avoids the
  /// per-comparison allocation the old `new SQLXxxOperator(-1)` path incurred. Both `!=`
  /// spellings (`!=`, `<>`) collapsed to NE during lowering, so NE uses the `SQLNeOperator`
  /// singleton for either spelling — behaviorally identical since both AST classes share the
  /// same `execute` body.
  private static SQLBinaryCompareOperator comparisonOperator(BinaryOperator op) {
    return switch (op) {
      case EQ -> OP_EQ;
      case NE -> OP_NE;
      case LT -> OP_LT;
      case LE -> OP_LE;
      case GT -> OP_GT;
      case GE -> OP_GE;
      // The arithmetic/connective constants never reach here (visitBinaryOp dispatches them to
      // their own paths), so the default arm is an unreachable-by-construction programming-error
      // guard.
      default -> throw new IllegalStateException(
          "non-comparison operator routed to comparison path: " + op);
    };
  }
}
