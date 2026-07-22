package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.ByModulatorTranslator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.ParseException;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderByItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
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
      var fieldSql = resolveSortFieldSql(boundary, pair.getValue0());
      if (fieldSql == null) {
        return Outcome.DECLINE;
      }
      items.add(parseOrderByItem(fieldSql, direction.get()));
    }

    var orderBy = new SQLOrderBy(-1);
    orderBy.setItems(items);
    ctx.setOrderBy(orderBy);
    return Outcome.ACCEPTED;
  }

  /**
   * Identity modulators (bare {@code order()} / {@code by(Order.asc)}) sort by element RID; other
   * shapes go through {@link ByModulatorTranslator}.
   */
  private static String resolveSortFieldSql(String alias, Traversal.Admin<?, ?> modulator) {
    if (modulator instanceof IdentityTraversal) {
      return alias + ".@rid";
    }
    return ByModulatorTranslator.translateKeyModulator(alias, modulator)
        .map(Object::toString)
        .orElse(null);
  }

  private static boolean ctxHasOrderBy(RecognitionContext ctx) {
    // WalkerContext exposes the field for tests; RecognitionContext has only the setter — read via
    // package-visible WalkerContext when available.
    if (ctx instanceof WalkerContext walker) {
      return walker.orderBy != null;
    }
    return false;
  }

  private static SQLOrderByItem parseOrderByItem(String fieldSql, String direction) {
    try {
      var sql = "SELECT FROM V ORDER BY " + fieldSql + " " + direction;
      var parser =
          new YouTrackDBSql(new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8)));
      var stmt = (SQLSelectStatement) parser.parse();
      var orderBy = stmt.getOrderBy();
      if (orderBy == null || orderBy.getItems() == null || orderBy.getItems().isEmpty()) {
        throw new IllegalArgumentException("failed to parse ORDER BY item: " + fieldSql);
      }
      return orderBy.getItems().getFirst();
    } catch (ParseException e) {
      throw new IllegalArgumentException("failed to parse ORDER BY item: " + fieldSql, e);
    }
  }
}
