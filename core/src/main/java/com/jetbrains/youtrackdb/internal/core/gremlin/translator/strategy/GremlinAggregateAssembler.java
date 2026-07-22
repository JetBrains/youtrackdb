package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.ByModulatorTranslator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.ParseException;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGroupBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
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
   * Bare {@code count()} → {@code RETURN count(*)} with {@link BoundaryOutputType#SCALAR}. Declines
   * when the walk is non-polymorphic (native {@code YTDBGraphCountStrategy} covers that case).
   */
  static Outcome configureCount(RecognitionContext ctx) {
    if (!ctx.polymorphic()) {
      return Outcome.DECLINE;
    }
    var boundary = ctx.boundaryAlias();
    if (boundary == null) {
      return Outcome.DECLINE;
    }
    ctx.clearReturnProjection();
    ctx.appendReturnColumn(parseAggregate("count(*)"), null);
    ctx.setGroupBy(null);
    ctx.setDropNullRows(false);
    ctx.setDropOnAbsent(false);
    ctx.setLastPropertyProjection(null);
    ctx.setPresencePropertyKeys(List.of());
    ctx.setWrapMapValuesInLists(false);
    ctx.setAccumulateMap(false);
    ctx.setUnwrapSingletonMap(false);
    ctx.setElementMapTokens(false);
    ctx.pinBoundary(boundary, BoundaryOutputType.SCALAR, Vertex.class);
    return Outcome.ACCEPTED;
  }

  /**
   * {@code sum}/{@code min}/{@code max}/{@code mean} over the preceding single-key {@code
   * values(key)} field ({@link RecognitionContext#lastPropertyProjection}). Sets {@code
   * dropNullRows} so empty input emits no traverser.
   */
  static Outcome configurePropertyAggregate(RecognitionContext ctx, String functionName) {
    var boundary = ctx.boundaryAlias();
    var field = ctx.lastPropertyProjection();
    if (boundary == null || field == null) {
      return Outcome.DECLINE;
    }
    ctx.clearReturnProjection();
    ctx.appendReturnColumn(parseAggregate(functionName + "(" + field + ")"), null);
    ctx.setGroupBy(null);
    ctx.setDropNullRows(true);
    ctx.setDropOnAbsent(false);
    ctx.setLastPropertyProjection(null);
    ctx.setPresencePropertyKeys(List.of());
    ctx.setWrapMapValuesInLists(false);
    ctx.setAccumulateMap(false);
    ctx.setUnwrapSingletonMap(false);
    ctx.setElementMapTokens(false);
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
    ctx.setDropNullRows(false);
    ctx.setDropOnAbsent(false);
    ctx.setLastPropertyProjection(null);
    ctx.setPresencePropertyKeys(List.of());
    ctx.setWrapMapValuesInLists(false);
    ctx.setAccumulateMap(true);
    ctx.setUnwrapSingletonMap(false);
    ctx.setElementMapTokens(false);
    ctx.pinBoundary(boundary, BoundaryOutputType.MAP, Vertex.class);
    return Outcome.ACCEPTED;
  }

  /**
   * {@code groupCount()} / {@code groupCount().by(key)} → GROUP BY + {@code count(*)} value column.
   */
  static Outcome configureGroupCount(RecognitionContext ctx, Traversal.Admin<?, ?> keyTraversal) {
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
    ctx.appendReturnColumn(parseAggregate("count(*)"), GROUP_VALUE_ALIAS);
    ctx.setGroupBy(groupBy);
    ctx.setDropNullRows(false);
    ctx.setDropOnAbsent(false);
    ctx.setLastPropertyProjection(null);
    ctx.setPresencePropertyKeys(List.of());
    ctx.setWrapMapValuesInLists(false);
    ctx.setAccumulateMap(true);
    ctx.setUnwrapSingletonMap(false);
    ctx.setElementMapTokens(false);
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
    // null / missing value-side → default fold list (matches bare group() / group().by(key)).
    if (valueTraversal == null || valueTraversal instanceof IdentityTraversal) {
      return parseAggregate("list($currentMatch)");
    }
    var accumulator = ByModulatorTranslator.translateValueModulator(alias, valueTraversal);
    if (accumulator.isEmpty()) {
      return null;
    }
    return switch (accumulator.get()) {
      case ByModulatorTranslator.ValueAccumulator.CountStar ignored ->
          parseAggregate("count(*)");
      case ByModulatorTranslator.ValueAccumulator.FoldList ignored ->
          parseAggregate("list($currentMatch)");
      case ByModulatorTranslator.ValueAccumulator.PropertyAggregate prop ->
          parseAggregate(
              prop.function().name().toLowerCase(Locale.ROOT) + "(" + prop.field() + ")");
    };
  }

  static SQLExpression parseAggregate(String itemSql) {
    try {
      var sql = "SELECT " + itemSql + " FROM V";
      var parser =
          new YouTrackDBSql(new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8)));
      var stmt = (SQLSelectStatement) parser.parse();
      var projection = stmt.getProjection();
      if (projection == null || projection.getItems() == null || projection.getItems().isEmpty()) {
        throw new IllegalArgumentException("failed to parse aggregate: " + itemSql);
      }
      var expr = projection.getItems().getFirst().getExpression();
      if (expr == null) {
        throw new IllegalArgumentException("failed to parse aggregate: " + itemSql);
      }
      return expr;
    } catch (ParseException e) {
      throw new IllegalArgumentException("failed to parse aggregate: " + itemSql, e);
    }
  }
}
