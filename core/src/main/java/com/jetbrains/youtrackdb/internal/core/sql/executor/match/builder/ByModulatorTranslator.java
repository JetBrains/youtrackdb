package com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder;

import com.jetbrains.youtrackdb.internal.core.sql.parser.ProjectionExpressionFactories;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderByItem;
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
    if (alias == null || alias.isBlank()) {
      return Optional.empty();
    }
    return classifyKey(modulator).map(field -> field.toFieldExpression(alias));
  }

  /**
   * Same key-side classification as {@link #translateKeyModulator}, but produces an {@link
   * SQLOrderByItem} directly (no SQL-text round-trip) for {@code order().by(...)}.
   */
  public static Optional<SQLOrderByItem> translateKeyModulatorOrderItem(
      String alias, Traversal.Admin<?, ?> modulator, boolean ascending) {
    if (alias == null || alias.isBlank()) {
      return Optional.empty();
    }
    return classifyKey(modulator).map(field -> field.toOrderItem(alias, ascending));
  }

  /** Classifies a key-side modulator into a field reference, independent of the target alias. */
  private static Optional<FieldRef> classifyKey(Traversal.Admin<?, ?> modulator) {
    if (modulator == null) {
      return Optional.empty();
    }
    if (modulator instanceof ValueTraversal<?, ?> valueTraversal) {
      return fieldRefProperty(valueTraversal.getPropertyKey());
    }
    if (modulator instanceof TokenTraversal<?, ?> tokenTraversal) {
      return fieldRefToken(tokenTraversal.getToken());
    }
    var steps = modulator.getSteps();
    if (steps.size() == 1) {
      return switch (steps.getFirst()) {
        case PropertiesStep ps when isSingleValueProperty(ps) ->
            fieldRefProperty(ps.getPropertyKeys()[0]);
        case IdStep ignored -> Optional.of(new FieldRef(true, "@rid"));
        case LabelStep ignored -> Optional.of(new FieldRef(true, "@class"));
        default -> Optional.empty();
      };
    }
    return Optional.empty();
  }

  private static Optional<FieldRef> fieldRefProperty(String propertyKey) {
    if (propertyKey == null || propertyKey.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(new FieldRef(false, propertyKey));
  }

  private static Optional<FieldRef> fieldRefToken(T token) {
    if (T.id.equals(token)) {
      return Optional.of(new FieldRef(true, "@rid"));
    }
    if (T.label.equals(token)) {
      return Optional.of(new FieldRef(true, "@class"));
    }
    return Optional.empty();
  }

  /** A resolved key field: either a record attribute ({@code @rid}/{@code @class}) or a property. */
  private record FieldRef(boolean recordAttr, String name) {
    SQLExpression toFieldExpression(String alias) {
      return recordAttr
          ? ProjectionExpressionFactories.aliasRecordAttribute(alias, name)
          : ProjectionExpressionFactories.aliasProperty(alias, name);
    }

    SQLOrderByItem toOrderItem(String alias, boolean ascending) {
      return recordAttr
          ? ProjectionExpressionFactories.orderByRecordAttribute(alias, name, ascending)
          : ProjectionExpressionFactories.orderByProperty(alias, name, ascending);
    }
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

  /** {@code alias.propertyKey} built as AST — see {@link ProjectionExpressionFactories#aliasProperty}. */
  public static SQLExpression aliasProperty(String alias, String propertyKey) {
    return ProjectionExpressionFactories.aliasProperty(alias, propertyKey);
  }

  /** {@code alias.@rid} / {@code alias.@class} built as AST for identity token modulators. */
  public static SQLExpression aliasRecordAttribute(String alias, String attribute) {
    return ProjectionExpressionFactories.aliasRecordAttribute(alias, attribute);
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

}
