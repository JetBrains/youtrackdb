package com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder;

import com.jetbrains.youtrackdb.internal.core.sql.parser.ParseException;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderByItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.TokenTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.ValueTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.CountGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.FoldStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.IdStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.LabelStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.MaxGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.MeanGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.MinGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.PropertiesStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.SumGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.SideEffectStep;
import org.apache.tinkerpop.gremlin.structure.PropertyType;
import org.apache.tinkerpop.gremlin.structure.T;

/**
 * Shared resolver for Gremlin {@code by(...)} modulator slots across {@code order}, {@code select},
 * {@code dedup}, {@code group}, and {@code project}. Recognised key-side shapes map to field-access
 * {@link SQLExpression}s on a caller-supplied alias; recognised value-side shapes map to aggregate
 * descriptors consumed by group terminators (Track 6 Step 6).
 */
public final class ByModulatorTranslator {

  /** Value-side accumulator for {@code group().by(<key>).by(<value>)} modulators. */
  public sealed interface ValueAccumulator {
    /** {@code count(*)} over the group bucket. */
    record CountStar() implements ValueAccumulator {
    }

    /** {@code list($currentMatch)} — default no-by {@code group()} shape. */
    record FoldList(String matchAlias) implements ValueAccumulator {
    }

    /** {@code count|sum|min|max|mean(alias.property)} over a single property column. */
    record PropertyAggregate(AggregateFunction function, SQLExpression field)
        implements ValueAccumulator {
    }

    enum AggregateFunction {
      COUNT, SUM, MIN, MAX, MEAN
    }
  }

  private ByModulatorTranslator() {
    // Static helper — no instances.
  }

  /**
   * Returns {@code true} when the modulator slot count matches the label/key count exactly. TinkerPop
   * cycles spare modulators; Phase 1 declines that shape.
   */
  public static boolean exactModulatorCount(int labelCount, int modulatorCount) {
    return labelCount > 0 && labelCount == modulatorCount;
  }

  /**
   * Resolves a key-side {@code by(...)} modulator to {@code alias.property}, {@code alias.@rid}, or
   * {@code alias.@class}. Declines lambdas, edge traversals, nested aggregates, and {@code
   * Order.shuffle}.
   */
  public static Optional<SQLExpression> translateKeyModulator(
      String alias, Traversal.Admin<?, ?> modulator) {
    if (alias == null || alias.isBlank() || modulator == null) {
      return Optional.empty();
    }
    if (modulator instanceof ValueTraversal<?, ?> valueTraversal) {
      return optionalPropertyField(alias, valueTraversal.getPropertyKey());
    }
    if (modulator instanceof TokenTraversal<?, ?> tokenTraversal) {
      return optionalIdentityField(alias, tokenTraversal.getToken());
    }
    var steps = modulator.getSteps();
    if (steps.isEmpty()) {
      return Optional.empty();
    }
    if (steps.size() == 1) {
      return switch (steps.getFirst()) {
        case PropertiesStep ps when isSingleValueProperty(ps) ->
            optionalPropertyField(alias, ps.getPropertyKeys()[0]);
        case IdStep ignored -> Optional.of(aliasRecordAttribute(alias, "@rid"));
        case LabelStep ignored -> Optional.of(aliasRecordAttribute(alias, "@class"));
        default -> Optional.empty();
      };
    }
    return Optional.empty();
  }

  /**
   * Resolves a value-side {@code by(...)} modulator ({@code __.count()}, {@code __.fold()}, {@code
   * __.values(k).count()}, …). Declines edge traversals, side effects, and unrecognized shapes.
   */
  public static Optional<ValueAccumulator> translateValueModulator(
      String alias, Traversal.Admin<?, ?> modulator) {
    if (alias == null || alias.isBlank() || modulator == null) {
      return Optional.empty();
    }
    if (containsSideEffectOrEdge(modulator.getSteps())) {
      return Optional.empty();
    }
    var steps = modulator.getSteps();
    if (steps.size() == 1) {
      return switch (steps.getFirst()) {
        case CountGlobalStep ignored -> Optional.of(new ValueAccumulator.CountStar());
        case FoldStep ignored -> Optional.of(new ValueAccumulator.FoldList(alias));
        default -> Optional.empty();
      };
    }
    if (steps.size() == 2 && steps.get(0) instanceof PropertiesStep ps
        && isSingleValueProperty(ps)) {
      var field = aliasProperty(alias, ps.getPropertyKeys()[0]);
      return switch (steps.get(1)) {
        case CountGlobalStep ignored ->
            Optional.of(new ValueAccumulator.PropertyAggregate(
                ValueAccumulator.AggregateFunction.COUNT, field));
        case SumGlobalStep ignored ->
            Optional.of(new ValueAccumulator.PropertyAggregate(
                ValueAccumulator.AggregateFunction.SUM, field));
        case MinGlobalStep ignored ->
            Optional.of(new ValueAccumulator.PropertyAggregate(
                ValueAccumulator.AggregateFunction.MIN, field));
        case MaxGlobalStep ignored ->
            Optional.of(new ValueAccumulator.PropertyAggregate(
                ValueAccumulator.AggregateFunction.MAX, field));
        case MeanGlobalStep ignored ->
            Optional.of(new ValueAccumulator.PropertyAggregate(
                ValueAccumulator.AggregateFunction.MEAN, field));
        default -> Optional.empty();
      };
    }
    return Optional.empty();
  }

  /**
   * Maps a TinkerPop sort comparator to {@link SQLOrderByItem#ASC} / {@link SQLOrderByItem#DESC}.
   * {@link Order#shuffle} and custom comparators decline ({@code empty}).
   */
  public static Optional<String> parseSortDirection(@Nullable Comparator<?> comparator) {
    if (comparator == null) {
      return Optional.empty();
    }
    if (comparator == Order.asc) {
      return Optional.of(SQLOrderByItem.ASC);
    }
    if (comparator == Order.desc) {
      return Optional.of(SQLOrderByItem.DESC);
    }
    return Optional.empty();
  }

  /** {@code alias.propertyKey} parsed through the SQL parser so the AST matches hand-written RETURN. */
  public static SQLExpression aliasProperty(String alias, String propertyKey) {
    if (propertyKey == null || propertyKey.isBlank()) {
      throw new IllegalArgumentException("blank property key");
    }
    return parseReturnItem(alias + "." + propertyKey);
  }

  /** {@code alias.@rid} / {@code alias.@class} for identity token modulators. */
  public static SQLExpression aliasRecordAttribute(String alias, String attribute) {
    return parseReturnItem(alias + "." + attribute);
  }

  private static Optional<SQLExpression> optionalPropertyField(String alias, String propertyKey) {
    if (propertyKey == null || propertyKey.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(aliasProperty(alias, propertyKey));
  }

  private static Optional<SQLExpression> optionalIdentityField(String alias, T token) {
    if (token == null) {
      return Optional.empty();
    }
    if (T.id.equals(token)) {
      return Optional.of(aliasRecordAttribute(alias, "@rid"));
    }
    if (T.label.equals(token)) {
      return Optional.of(aliasRecordAttribute(alias, "@class"));
    }
    return Optional.empty();
  }

  private static boolean isSingleValueProperty(PropertiesStep<?> step) {
    var returnType = step.getReturnType();
    return (returnType == PropertyType.VALUE || returnType == PropertyType.PROPERTY)
        && step.getPropertyKeys().length == 1;
  }

  private static boolean containsSideEffectOrEdge(List<?> steps) {
    for (var step : steps) {
      if (step instanceof SideEffectStep) {
        return true;
      }
      var className = step.getClass().getSimpleName();
      if (className.contains("VertexStep")
          || className.contains("EdgeStep")
          || className.contains("GraphStep")) {
        return true;
      }
    }
    return false;
  }

  private static SQLExpression parseReturnItem(String itemSql) {
    try {
      var sql = "SELECT " + itemSql + " FROM V";
      var parser =
          new YouTrackDBSql(new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8)));
      var stmt = (SQLSelectStatement) parser.parse();
      var projection = stmt.getProjection();
      if (projection == null || projection.getItems() == null || projection.getItems().isEmpty()) {
        throw new IllegalArgumentException("failed to parse return item: " + itemSql);
      }
      var expr = projection.getItems().getFirst().getExpression();
      if (expr == null) {
        throw new IllegalArgumentException("failed to parse return item: " + itemSql);
      }
      return expr;
    } catch (ParseException e) {
      throw new IllegalArgumentException("failed to parse return item: " + itemSql, e);
    }
  }
}
