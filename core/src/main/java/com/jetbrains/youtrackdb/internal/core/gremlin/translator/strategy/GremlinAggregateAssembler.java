package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.PostConcatOp;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ResultShaping;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.ByModulatorTranslator;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchProjectionBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGroupBy;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.IdentityTraversal;
import org.apache.tinkerpop.gremlin.structure.Vertex;

/**
 * Builds RETURN / GROUP BY wiring for Gremlin aggregate terminators ({@code count}, {@code sum}/
 * {@code min}/{@code max}/{@code mean}, {@code group}, {@code groupCount}) and pins {@link
 * BoundaryOutputType}.
 */
final class GremlinAggregateAssembler {

  /** RETURN alias for group map keys. */
  static final String GROUP_KEY_ALIAS = "key";

  /** RETURN alias for group map values. */
  static final String GROUP_VALUE_ALIAS = "value";

  private GremlinAggregateAssembler() {
    // Static helper — no instances.
  }

  /**
   * A reducing / grouping terminator cannot follow a captured {@code limit} / {@code skip} /
   * {@code dedup}. MATCH applies SKIP / LIMIT and RETURN DISTINCT <em>after</em> the aggregate
   * projection, but Gremlin applies them to the reducer's input stream, so the two diverge:
   * {@code limit(5).count()} would ignore the limit, {@code skip(2).count()} would drop the single
   * count row (count must emit one value), and {@code out().dedup().count()} would emit
   * {@code RETURN DISTINCT count(*)} and count duplicates. Declining hands the whole traversal to the
   * native pipeline, which orders the operations correctly. An {@code orderBy} does not gate here —
   * ordering does not change an aggregate's result, and an order-then-limit is already caught by the
   * limit.
   */
  private static boolean hasPreAggregateCardinalityClause(RecognitionContext ctx) {
    return ctx.limit() != null || ctx.skip() != null || ctx.returnDistinct();
  }

  /**
   * Bare {@code count()} → {@code RETURN count(*)} with {@link BoundaryOutputType#SCALAR}.
   * Non-polymorphic {@code hasLabel(L)} keeps its exact {@code @class = 'L'} filter; MATCH
   * short-circuit folds that into {@code countClass(L, false)}. Bare {@code g.V()}/{@code g.E()}
   * stay unfiltered → polymorphic class size (same as native).
   */
  static Outcome configureCount(RecognitionContext ctx) {
    if (ctx.hasUnionCarrier()) {
      return configurePostUnionCount(ctx);
    }
    if (hasPreAggregateCardinalityClause(ctx)) {
      return Outcome.DECLINE;
    }
    var boundary = ctx.boundaryAlias();
    if (boundary == null) {
      return Outcome.DECLINE;
    }
    ctx.clearReturnProjection();
    ctx.appendReturnColumn(MatchProjectionBuilder.countStar(), null);
    ctx.setGroupBy(null);
    ctx.setLastPropertyProjection(null);
    ctx.setResultShaping(ResultShaping.NONE);
    ctx.pinBoundary(boundary, BoundaryOutputType.SCALAR, Vertex.class);
    return Outcome.ACCEPTED;
  }

  /**
   * {@code union(…).count()}: stash a {@link
   * com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.PostConcatOp.Count} and pin
   * {@link BoundaryOutputType#SCALAR}. Child plans stay ELEMENT until build-time push-down (lone
   * count) or stream-count (count after limit/dedup). Non-poly children keep exact {@code @class}
   * filters so per-child short-circuit can use {@code countClass(L, false)}.
   */
  private static Outcome configurePostUnionCount(RecognitionContext ctx) {
    var boundary = ctx.boundaryAlias();
    if (boundary == null) {
      return Outcome.DECLINE;
    }
    // A second count after union has no Gremlin meaning we translate.
    for (var op : ctx.postConcatOps()) {
      if (op instanceof PostConcatOp.Count) {
        return Outcome.DECLINE;
      }
    }
    ctx.appendPostConcatOp(PostConcatOp.Count.INSTANCE);
    ctx.setResultShaping(ResultShaping.NONE);
    ctx.pinBoundary(boundary, BoundaryOutputType.SCALAR, Vertex.class);
    return Outcome.ACCEPTED;
  }

  /**
   * {@code sum}/{@code min}/{@code max}/{@code mean} over the preceding single-key {@code
   * values(key)} field ({@link RecognitionContext#lastPropertyProjection}). Sets {@code
   * dropNullRows} so empty input emits no traverser.
   */
  static Outcome configurePropertyAggregate(RecognitionContext ctx, String functionName) {
    if (hasPreAggregateCardinalityClause(ctx)) {
      return Outcome.DECLINE;
    }
    var boundary = ctx.boundaryAlias();
    var field = ctx.lastPropertyProjection();
    if (boundary == null || field == null) {
      return Outcome.DECLINE;
    }
    ctx.clearReturnProjection();
    ctx.appendReturnColumn(MatchProjectionBuilder.propertyAggregate(functionName, field), null);
    ctx.setGroupBy(null);
    ctx.setLastPropertyProjection(null);
    // dropNullRows so empty input (no matched vertices) emits no traverser.
    ctx.setResultShaping(ResultShaping.NONE.withDropNullRows(true));
    ctx.pinBoundary(boundary, BoundaryOutputType.SCALAR, Vertex.class);
    return Outcome.ACCEPTED;
  }

  /**
   * {@code group()} / {@code group().by(key)} / {@code group().by(key).by(value)} → GROUP BY + MAP
   * projection.
   */
  static Outcome configureGroup(
      RecognitionContext ctx,
      Traversal.Admin<?, ?> keyTraversal,
      Traversal.Admin<?, ?> valueTraversal) {
    if (hasPreAggregateCardinalityClause(ctx)) {
      return Outcome.DECLINE;
    }
    var boundary = ctx.boundaryAlias();
    if (boundary == null) {
      return Outcome.DECLINE;
    }
    var keyExpr = resolveGroupKey(boundary, keyTraversal);
    if (keyExpr == null) {
      return Outcome.DECLINE;
    }
    var valueExpr = resolveGroupValue(boundary, valueTraversal);
    if (valueExpr == null) {
      return Outcome.DECLINE;
    }
    var groupBy = new SQLGroupBy(-1);
    groupBy.addItem(keyExpr);
    ctx.clearReturnProjection();
    ctx.appendReturnColumn(keyExpr, GROUP_KEY_ALIAS);
    ctx.appendReturnColumn(valueExpr, GROUP_VALUE_ALIAS);
    ctx.setGroupBy(groupBy);
    ctx.setLastPropertyProjection(null);
    // accumulateMap so the boundary drains every GROUP BY row into one map and emits one traverser.
    ctx.setResultShaping(ResultShaping.NONE.withAccumulateMap(true));
    ctx.pinBoundary(boundary, BoundaryOutputType.MAP, Vertex.class);
    return Outcome.ACCEPTED;
  }

  /**
   * {@code groupCount()} / {@code groupCount().by(key)} → GROUP BY + {@code count(*)} value column.
   */
  static Outcome configureGroupCount(RecognitionContext ctx, Traversal.Admin<?, ?> keyTraversal) {
    if (hasPreAggregateCardinalityClause(ctx)) {
      return Outcome.DECLINE;
    }
    var boundary = ctx.boundaryAlias();
    if (boundary == null) {
      return Outcome.DECLINE;
    }
    var keyExpr = resolveGroupKey(boundary, keyTraversal);
    if (keyExpr == null) {
      return Outcome.DECLINE;
    }
    var groupBy = new SQLGroupBy(-1);
    groupBy.addItem(keyExpr);
    ctx.clearReturnProjection();
    ctx.appendReturnColumn(keyExpr, GROUP_KEY_ALIAS);
    ctx.appendReturnColumn(MatchProjectionBuilder.countStar(), GROUP_VALUE_ALIAS);
    ctx.setGroupBy(groupBy);
    ctx.setLastPropertyProjection(null);
    // accumulateMap so the boundary drains every GROUP BY row into one map and emits one traverser.
    ctx.setResultShaping(ResultShaping.NONE.withAccumulateMap(true));
    ctx.pinBoundary(boundary, BoundaryOutputType.MAP, Vertex.class);
    return Outcome.ACCEPTED;
  }

  private static SQLExpression resolveGroupKey(String alias, Traversal.Admin<?, ?> keyTraversal) {
    if (keyTraversal == null || keyTraversal instanceof IdentityTraversal) {
      return ByModulatorTranslator.aliasRecordAttribute(alias, "@rid");
    }
    return ByModulatorTranslator.translateKeyModulator(alias, keyTraversal).orElse(null);
  }

  private static SQLExpression resolveGroupValue(String alias,
      Traversal.Admin<?, ?> valueTraversal) {
    // null / missing value-side → default fold list of the grouped element (matches bare group() /
    // group().by(key)). Collect the boundary alias itself — $currentMatch is a match-time variable
    // and yields null in a grouped RETURN, whereas list(alias) collects the grouped vertices.
    if (valueTraversal == null || valueTraversal instanceof IdentityTraversal) {
      return MatchProjectionBuilder.listAlias(alias);
    }
    var accumulator = ByModulatorTranslator.translateValueModulator(alias, valueTraversal);
    if (accumulator.isEmpty()) {
      return null;
    }
    return switch (accumulator.get()) {
      case ByModulatorTranslator.ValueAccumulator.CountStar ignored ->
          MatchProjectionBuilder.countStar();
      case ByModulatorTranslator.ValueAccumulator.FoldList ignored ->
          MatchProjectionBuilder.listAlias(alias);
      case ByModulatorTranslator.ValueAccumulator.PropertyAggregate prop ->
          // MatchProjectionBuilder.propertyAggregate lowercases the function name itself, so pass the
          // enum name as-is rather than lowercasing here too (was a redundant double lowercase).
          MatchProjectionBuilder.propertyAggregate(prop.function().name(), prop.field());
    };
  }
}
