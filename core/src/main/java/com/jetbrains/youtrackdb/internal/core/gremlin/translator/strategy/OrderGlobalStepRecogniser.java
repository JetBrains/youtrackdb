package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.ByModulatorTranslator;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchProjectionBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.ProjectionExpressionFactories;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderByItem;
import java.util.ArrayList;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.IdentityTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderGlobalStep;

/**
 * Recogniser for {@link OrderGlobalStep}: {@code order()} / {@code order().by(...)} → {@link
 * SQLOrderBy}. Bare {@code order()} and identity modulators sort by {@code @rid}; {@code
 * Order.shuffle} declines.
 *
 * <h2>An {@code order()} after a grouping terminator</h2>
 *
 * A grouping terminator ({@code group()} / {@code groupCount()}) emits one map, so a following
 * {@code order()} sorts a one-element stream and returns the map whole, whatever key the
 * {@code by()} names. Measured on four people, two of whom carry {@code age}:
 * {@code g.V().groupCount().by(name).order().by(age)} returns all four entries natively — and so
 * does {@code order().by(zzz)} for a key nothing in the graph carries. The translation read that
 * {@code by(key)} as a sort over the grouping's <em>input</em> instead — it committed
 * {@code key IS DEFINED} as a pattern conjunct on the rows feeding the {@code GROUP BY} and appended
 * {@code ORDER BY <alias>.key} — returning {@code {Alice=1, Bob=1}}, the two ageless names removed by
 * a filter the query never asked for.
 *
 * <p>Two mechanisms have to line up for the map to survive, and only one of them is the absent sort.
 * {@code OrderGlobalStep.processAllStarts} projects the modulator once per traverser <em>before</em>
 * any comparison, and drops a traverser whose projection is non-productive, so a projection is
 * observable on a one-element stream even though the comparator is not. It survives here because a
 * {@code by(key)} over a {@code Map} is productive whatever the map holds: {@code ValueTraversal}
 * reads {@code map.get(key)} and yields the result, including {@code null} for an absent key, where
 * its {@code Element} branch marks an absent property as having no starts. Over elements the drop is
 * real — {@code g.V().order().by(k)} does exclude the elements that lack {@code k} — which is why the
 * pattern conjunct the translation emitted looked equivalent.
 *
 * <p>So a captured {@code GROUP BY} declines here, matching the grouping gates in
 * {@code GremlinAggregateAssembler} and {@code RangeGlobalStepRecogniser}. The cost is the
 * {@code group().by(k).order().by(k2)} surface, which runs on the native traverser pipeline instead.
 */
final class OrderGlobalStepRecogniser implements StepRecogniser {

  /** Singleton — the recogniser is stateless and cheap to share across walker instances. */
  static final OrderGlobalStepRecogniser INSTANCE = new OrderGlobalStepRecogniser();

  private OrderGlobalStepRecogniser() {
    // Singleton — instantiate via INSTANCE.
  }

  @Override
  public Outcome recognize(StepCursor cursor, RecognitionContext ctx) {
    var step = cursor.take();
    if (!(step instanceof OrderGlobalStep<?, ?> orderStep)) {
      return Outcome.DECLINE;
    }
    // Post-union order needs an in-memory sort of the concatenation; not in this cut (count /
    // limit / dedup cover the push-down / early-stop post-concat set). The walker's post-union
    // allow-list already declines the traversal before this recogniser is dispatched, so in
    // production this branch is a second line of defence: it keeps a direct invocation honest and
    // makes re-adding order() to the allow-list a decline rather than a silent mistranslation.
    if (ctx.hasUnionCarrier()) {
      return Outcome.DECLINE;
    }
    var boundary = ctx.boundaryAlias();
    if (boundary == null) {
      return Outcome.DECLINE;
    }
    // A second order() has no clear MATCH composition rule in Phase 1.
    if (ctxHasOrderBy(ctx)) {
      return Outcome.DECLINE;
    }
    // An order() after a grouping terminator sorts the single map that terminator emitted, not the
    // rows that fed it — see the class Javadoc's "An order() after a grouping terminator". Checked
    // before the comparator loop so the declining path commits no presence conjunct.
    if (ctx.groupBy() != null) {
      return Outcome.DECLINE;
    }

    var comparators = orderStep.getComparators();
    if (comparators == null || comparators.isEmpty()) {
      return Outcome.DECLINE;
    }

    var items = new ArrayList<SQLOrderByItem>(comparators.size());
    for (var pair : comparators) {
      var direction = ByModulatorTranslator.parseSortDirection(pair.getValue1());
      if (direction.isEmpty()) {
        return Outcome.DECLINE;
      }
      var ascending = SQLOrderByItem.ASC.equals(direction.get());
      var item =
          resolveSortItem(ctx, boundary, pair.getValue0(), ascending, ctx::resolveUserLabel);
      if (item == null) {
        return Outcome.DECLINE;
      }
      items.add(item);
    }

    // Contribution — reached only after every comparator resolved, so a declining modulator leaves
    // the context unmutated.
    for (var pair : comparators) {
      requireModulatedPropertyForOrder(ctx, boundary, pair.getValue0());
    }
    // TinkerPop may migrate an upstream {@code as(...)} label onto the {@code order()} step (e.g.
    // {@code inV().as("friend").order().by(name)} arrives as {@code OrderGlobalStep@[friend]}).
    // Register it on the current boundary so a following {@code select("friend")} resolves.
    if (!ctx.bindStepLabels(orderStep, boundary)) {
      return Outcome.DECLINE;
    }
    ctx.setOrderBy(MatchProjectionBuilder.orderBy(items));
    return Outcome.ACCEPTED;
  }

  /**
   * Identity modulators (bare {@code order()} / {@code by(Order.asc)}) sort by the value the walk
   * is already projecting — the property behind a preceding {@code values(key)} if there is one,
   * the element RID otherwise. {@code g.V().values("name").order()} sorts names, not RIDs, and
   * before this distinction existed it emitted the six names in RID order and called it sorted.
   * Other shapes go through {@link ByModulatorTranslator}. Built as AST — no SQL-text round-trip.
   */
  private static SQLOrderByItem resolveSortItem(
      RecognitionContext ctx,
      String alias,
      Traversal.Admin<?, ?> modulator,
      boolean ascending,
      java.util.function.Function<String, String> labelResolver) {
    if (modulator instanceof IdentityTraversal) {
      var projection = ctx.lastPropertyProjection();
      if (projection != null) {
        return ProjectionExpressionFactories.orderByProperty(
            projection.alias(), projection.propertyKey(), ascending);
      }
      return ProjectionExpressionFactories.orderByRecordAttribute(alias, "@rid", ascending);
    }
    return ByModulatorTranslator.translateOrderModulator(alias, modulator, ascending, labelResolver)
        .orElse(null);
  }

  private static void requireModulatedPropertyForOrder(
      RecognitionContext ctx, String boundary, Traversal.Admin<?, ?> modulator) {
    ByModulatorTranslator.orderModulatorPresenceTarget(boundary, modulator, ctx::resolveUserLabel)
        .ifPresent(
            target -> ByModulatorPresence.requireModulatedProperty(
                ctx, target.alias(), target.propertyKey()));
  }

  private static boolean ctxHasOrderBy(RecognitionContext ctx) {
    return ctx.orderBy() != null;
  }

}
