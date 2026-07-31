package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.ByModulatorTranslator;
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
    // limit / dedup cover the push-down / early-stop post-concat set). Decline to native.
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
      var item = resolveSortItem(boundary, pair.getValue0(), ascending);
      if (item == null) {
        return Outcome.DECLINE;
      }
      items.add(item);
    }

    var orderBy = new SQLOrderBy(-1);
    orderBy.setItems(items);
    ctx.setOrderBy(orderBy);
    return Outcome.ACCEPTED;
  }

  /**
   * Identity modulators (bare {@code order()} / {@code by(Order.asc)}) sort by element RID; other
   * shapes go through {@link ByModulatorTranslator}. Built as AST — no SQL-text round-trip.
   */
  private static SQLOrderByItem resolveSortItem(
      String alias, Traversal.Admin<?, ?> modulator, boolean ascending) {
    if (modulator instanceof IdentityTraversal) {
      return ProjectionExpressionFactories.orderByRecordAttribute(alias, "@rid", ascending);
    }
    return ByModulatorTranslator.translateKeyModulatorOrderItem(alias, modulator, ascending)
        .orElse(null);
  }

  private static boolean ctxHasOrderBy(RecognitionContext ctx) {
    return ctx.orderBy() != null;
  }

}
