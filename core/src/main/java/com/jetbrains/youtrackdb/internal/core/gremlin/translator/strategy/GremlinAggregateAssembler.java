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
   * A grouping terminator has already fixed the row set, and a terminator that follows it consumes
   * the maps the first one emits rather than the rows that fed it: {@code
   * g.V().group().by(label).count()} is 1, one map, and {@code g.V().group().by(name).groupCount()}
   * counts the single emitted map. Every assembler here would instead overwrite the grouped plan —
   * {@code configureCount} clears the projection and nulls the GROUP BY, the two grouping methods
   * call {@code setGroupBy} over the top of the existing one — so they decline and let the native
   * pipeline consume the grouped output.
   *
   * <p>All four assemblers read this: {@link #configureCount}, {@link #configureGroup}, {@link
   * #configureGroupCount} and {@link #configurePropertyAggregate}. The fourth is additionally
   * protected by the property projection both grouping assemblers null before returning, so it
   * would decline on a null projection even without this gate; it carries the gate anyway so the
   * rule does not depend on writes in two other methods.
   *
   * <p>The two slice recognisers hold the same line for the clauses they set: {@code
   * RangeGlobalStepRecogniser} and {@code OrderGlobalStepRecogniser} both decline on a captured
   * {@code GROUP BY}, because a statement-level {@code SKIP} / {@code LIMIT} / {@code ORDER BY}
   * lands on the grouped rows rather than on the single map the terminator emitted.
   */
  private static boolean hasGrouping(RecognitionContext ctx) {
    return ctx.groupBy() != null;
  }

  /**
   * Bare {@code count()} → {@code RETURN count(*)} with {@link BoundaryOutputType#SCALAR}.
   * Non-polymorphic {@code hasLabel(L)} keeps its exact {@code @class = 'L'} filter; MATCH
   * short-circuit folds that into {@code countClass(L, false)}. Bare {@code g.V()}/{@code g.E()}
   * stay unfiltered → polymorphic class size (same as native).
   *
   * @implNote Every recognised count shape translates, including the two {@code YTDBGraphCountStrategy}
   *     also serves ({@code g.V().count()} and {@code g.V().hasLabel(L).count()}). The translator
   *     runs first, so on a non-polymorphic session those two now take the MATCH route where they
   *     previously fell through to a single {@code YTDBClassCountStep} allocation. Both routes read
   *     the count from class metadata, so the answer and its asymptotics are unchanged; the accepted
   *     cost is a constant per compilation — fingerprint, an always-missing plan-cache probe
   *     ({@code CountFromClassStep.canBeCached()} is false, so the plan is never stored), a plan
   *     build, and a plan copy. The trade is a single owner for count translation: splitting it back
   *     by polymorphism flag would route identical query shapes through different planners depending
   *     on a session setting, and leave the wider shapes ({@code hasLabel(L).has(k, v).count()})
   *     without an owner at all.
   */
  static Outcome configureCount(RecognitionContext ctx) {
    if (ctx.hasUnionCarrier()) {
      return configurePostUnionCount(ctx);
    }
    if (hasPreAggregateCardinalityClause(ctx)) {
      return Outcome.DECLINE;
    }
    if (hasGrouping(ctx)) {
      return Outcome.DECLINE;
    }
    var boundary = ctx.boundaryAlias();
    if (boundary == null) {
      return Outcome.DECLINE;
    }
    // count() after values(key) counts the values that step emitted, and values(key) drops an
    // element without the property. That drop lives in the boundary's row projection, which this
    // body is about to discard, so restate it as a pattern conjunct first: g.V().values("age")
    // .count() over two aged and two ageless vertices is 2, not 4.
    var projection = ctx.lastPropertyProjection();
    if (projection != null) {
      ByModulatorPresence.requireProjectedProperty(
          ctx, projection.alias(), projection.propertyKey());
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
    // With no other op stashed this count is the lone one, so the strategy pushes RETURN count(*)
    // into every child. That rewrite drops each child's own LIMIT / SKIP / RETURN DISTINCT, which
    // would count rows the child never emits — union(out().limit(2), in()).count() would report the
    // full out-degree of the first arm. Decline the same shapes hasPreAggregateCardinalityClause
    // declines on the single-plan path. With an op already stashed the push-down does not fire and
    // the children keep their clauses, so only the lone-count case needs the gate.
    if (ctx.postConcatOps().isEmpty() && ctx.anyUnionChildHasCardinalityClause()) {
      return Outcome.DECLINE;
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
    // The grouping gate is here for the same reason it is in the other three assemblers, even
    // though no shape reaches it today: both grouping assemblers null the property projection
    // before returning, so a sum() after a group() declines two lines below on a null projection
    // instead. That leaves this method's safety resting on two writes in two other methods, so the
    // gate states the rule locally.
    if (hasPreAggregateCardinalityClause(ctx) || hasGrouping(ctx)) {
      return Outcome.DECLINE;
    }
    var boundary = ctx.boundaryAlias();
    var projection = ctx.lastPropertyProjection();
    if (boundary == null || projection == null) {
      return Outcome.DECLINE;
    }
    // The values(key) step this aggregate re-points at drops elements without the property, and
    // that drop lives in the boundary's row projection — which this method is about to replace
    // with a single aggregate column. Restate it as a pattern conjunct so the aggregate reduces
    // the same input Gremlin does: g.V().values("foo").sum() over a graph with no foo must match
    // nothing and emit nothing, where sum() over six null-valued rows emits a zero.
    ByModulatorPresence.requireProjectedProperty(
        ctx, projection.alias(), projection.propertyKey());
    ctx.clearReturnProjection();
    ctx.appendReturnColumn(
        MatchProjectionBuilder.propertyAggregate(functionName, projection.expression()), null);
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
    if (hasPreAggregateCardinalityClause(ctx) || hasGrouping(ctx)) {
      return Outcome.DECLINE;
    }
    var boundary = ctx.boundaryAlias();
    if (boundary == null) {
      return Outcome.DECLINE;
    }
    var keyExpr = resolveGroupKey(ctx, boundary, keyTraversal);
    if (keyExpr == null) {
      return Outcome.DECLINE;
    }
    var valueExpr = resolveGroupValue(ctx, boundary, valueTraversal);
    if (valueExpr == null) {
      return Outcome.DECLINE;
    }
    requireGroupKeyPresent(ctx, boundary, keyTraversal);
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
    if (hasPreAggregateCardinalityClause(ctx) || hasGrouping(ctx)) {
      return Outcome.DECLINE;
    }
    var boundary = ctx.boundaryAlias();
    if (boundary == null) {
      return Outcome.DECLINE;
    }
    var keyExpr = resolveGroupKey(ctx, boundary, keyTraversal);
    if (keyExpr == null) {
      return Outcome.DECLINE;
    }
    requireGroupKeyPresent(ctx, boundary, keyTraversal);
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

  /**
   * Restates the group key's Gremlin-side drop as a pattern conjunct. Both routes into {@link
   * #resolveGroupKey} can drop an element: an explicit {@code by(key)} drops one without the
   * property, and the no-{@code by} route re-points at a preceding {@code values(key)} whose own
   * drop lived in the boundary row projection this assembler is about to replace with group
   * columns. Without it the SQL {@code GROUP BY} collects those elements into a {@code null}
   * bucket — {@code g.V().groupCount().by("age")} gains a {@code null=2} entry the native answer
   * does not have.
   */
  private static void requireGroupKeyPresent(
      RecognitionContext ctx, String boundary, Traversal.Admin<?, ?> keyTraversal) {
    if (keyTraversal != null && !(keyTraversal instanceof IdentityTraversal)) {
      ByModulatorPresence.requireModulatedProperty(ctx, boundary, keyTraversal);
      return;
    }
    var projection = ctx.lastPropertyProjection();
    if (projection != null) {
      ByModulatorPresence.requireProjectedProperty(
          ctx, projection.alias(), projection.propertyKey());
    }
  }

  /**
   * The GROUP BY key expression. With no {@code by(...)} the group key is whatever the walk is
   * already projecting: the property behind a preceding {@code values(key)} if there is one, the
   * element RID otherwise. {@code g.V().out("created").values("name").groupCount()} keys on the
   * names — before this distinction existed it keyed on the vertices and returned
   * {@code {v[lop]=3, v[ripple]=1}} where Gremlin returns {@code {lop=3, ripple=1}}.
   */
  private static SQLExpression resolveGroupKey(
      RecognitionContext ctx, String alias, Traversal.Admin<?, ?> keyTraversal) {
    if (keyTraversal == null || keyTraversal instanceof IdentityTraversal) {
      var projection = ctx.lastPropertyProjection();
      if (projection != null) {
        return projection.expression();
      }
      return ByModulatorTranslator.aliasRecordAttribute(alias, "@rid");
    }
    return ByModulatorTranslator.translateKeyModulator(alias, keyTraversal).orElse(null);
  }

  private static SQLExpression resolveGroupValue(
      RecognitionContext ctx, String alias, Traversal.Admin<?, ?> valueTraversal) {
    // null / missing value-side → default fold list of whatever the walk is emitting, which is the
    // same rule resolveGroupKey applies on the key side: after values(k) that is the property, so
    // g.V().values("name").group() buckets the names, not the vertices that carry them. Without a
    // value projection collect the boundary alias itself — $currentMatch is a match-time variable
    // and yields null in a grouped RETURN, whereas list(alias) collects the grouped vertices.
    if (valueTraversal == null || valueTraversal instanceof IdentityTraversal) {
      return foldOfWhateverIsProjected(ctx, alias);
    }
    var accumulator = ByModulatorTranslator.translateValueModulator(alias, valueTraversal);
    if (accumulator.isEmpty()) {
      return null;
    }
    return switch (accumulator.get()) {
      case ByModulatorTranslator.ValueAccumulator.CountStar ignored ->
          MatchProjectionBuilder.countStar();
      case ByModulatorTranslator.ValueAccumulator.FoldList ignored ->
          foldOfWhateverIsProjected(ctx, alias);
      case ByModulatorTranslator.ValueAccumulator.PropertyAggregate prop ->
          // MatchProjectionBuilder.propertyAggregate lowercases the function name itself, so pass
          // the enum name as-is rather than lowercasing here too. MEAN names the SQL "mean"
          // aggregate, which divides in floating point the way Gremlin's mean() does — see
          // PropertyAggregateStepRecogniser for why avg() is the wrong target.
          MatchProjectionBuilder.propertyAggregate(prop.function().name(), prop.field());
    };
  }

  /**
   * The fold every bare or {@code by(fold())} value side performs, over whatever the walk is
   * emitting. That is the same rule {@link #resolveGroupKey} applies on the key side: after
   * {@code values(k)} the stream holds the property, so {@code g.V().values("name").group()} buckets
   * the names, and only without a value projection does the fold collect the grouped elements
   * themselves.
   */
  private static SQLExpression foldOfWhateverIsProjected(RecognitionContext ctx, String alias) {
    var projection = ctx.lastPropertyProjection();
    if (projection != null) {
      return MatchProjectionBuilder.listExpression(projection.expression());
    }
    return MatchProjectionBuilder.listAlias(alias);
  }
}
