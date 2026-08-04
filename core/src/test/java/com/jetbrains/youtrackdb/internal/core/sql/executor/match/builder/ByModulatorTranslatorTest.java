package com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.TraversalParent;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.PropertiesStep;
import org.apache.tinkerpop.gremlin.structure.PropertyType;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Test;

/**
 * Unit tests for {@link ByModulatorTranslator}: key-side field resolution, value-side accumulators,
 * sort-direction parsing, and decline paths for unsupported modulator shapes.
 */
public class ByModulatorTranslatorTest extends GraphBaseTest {

  private static final String ALIAS = "$g2m_v0";

  /** {@code by("name")} resolves to {@code alias.name}. */
  @Test
  public void keySide_stringProperty_resolvesFieldAccess() {
    var modulator = graph.traversal().V().project("n").by("name").asAdmin().getSteps().getLast();
    var ring =
        ((org.apache.tinkerpop.gremlin.process.traversal.step.map.ProjectStep<?, ?>) modulator)
            .getTraversalRing()
            .getTraversals()
            .getFirst();

    var field = ByModulatorTranslator.translateKeyModulator(ALIAS, ring);

    assertThat(field).isPresent();
    assertThat(field.get().toString()).contains("name");
  }

  /** {@code by(T.id)} resolves to {@code alias.@rid}. */
  @Test
  public void keySide_idToken_resolvesRid() {
    var modulator = graph.traversal().V().project("n").by(T.id).asAdmin().getSteps().getLast();
    var ring =
        ((org.apache.tinkerpop.gremlin.process.traversal.step.map.ProjectStep<?, ?>) modulator)
            .getTraversalRing()
            .getTraversals()
            .getFirst();

    var field = ByModulatorTranslator.translateKeyModulator(ALIAS, ring);

    assertThat(field).isPresent();
    assertThat(field.get().toString()).contains("@rid");
  }

  /** {@code by(__.values("age"))} unwraps to the same field access as {@code by("age")}. */
  @Test
  public void keySide_valuesTraversal_unwrapsToProperty() {
    var modulator =
        graph.traversal().V().project("n").by(__.values("age")).asAdmin().getSteps().getLast();
    var ring =
        ((org.apache.tinkerpop.gremlin.process.traversal.step.map.ProjectStep<?, ?>) modulator)
            .getTraversalRing()
            .getTraversals()
            .getFirst();

    var field = ByModulatorTranslator.translateKeyModulator(ALIAS, ring);

    assertThat(field).isPresent();
    assertThat(field.get().toString()).contains("age");
  }

  /** {@code by(__.count())} maps to the {@link ByModulatorTranslator.ValueAccumulator.CountStar} shape. */
  @Test
  public void valueSide_count_resolvesCountStar() {
    var groupStep =
        graph.traversal().V().group().by("k").by(__.count()).asAdmin().getSteps().stream()
            .filter(s -> s.getClass().getSimpleName().contains("Group"))
            .findFirst()
            .orElseThrow();
    var modulator =
        ((org.apache.tinkerpop.gremlin.process.traversal.step.TraversalParent) groupStep)
            .getLocalChildren()
            .get(1);

    var accumulator = ByModulatorTranslator.translateValueModulator(ALIAS, modulator);

    assertThat(accumulator)
        .containsInstanceOf(ByModulatorTranslator.ValueAccumulator.CountStar.class);
  }

  /** {@code by(__.fold())} maps to {@link ByModulatorTranslator.ValueAccumulator.FoldList}. */
  @Test
  public void valueSide_fold_resolvesFoldList() {
    var groupStep =
        graph.traversal().V().group().by("k").by(__.fold()).asAdmin().getSteps().stream()
            .filter(s -> s.getClass().getSimpleName().contains("Group"))
            .findFirst()
            .orElseThrow();
    var modulator =
        ((org.apache.tinkerpop.gremlin.process.traversal.step.TraversalParent) groupStep)
            .getLocalChildren()
            .get(1);

    var accumulator = ByModulatorTranslator.translateValueModulator(ALIAS, modulator);

    assertThat(accumulator).isPresent();
    assertThat(accumulator.get())
        .isInstanceOf(ByModulatorTranslator.ValueAccumulator.FoldList.class);
    assertThat(((ByModulatorTranslator.ValueAccumulator.FoldList) accumulator.get()).matchAlias())
        .isEqualTo(ALIAS);
  }

  /** {@code Order.shuffle} declines — no MATCH sort equivalent. */
  @Test
  public void sortDirection_shuffle_declines() {
    assertThat(ByModulatorTranslator.parseSortDirection(Order.shuffle)).isEmpty();
  }

  /** {@code Order.desc} maps to {@code DESC}. */
  @Test
  public void sortDirection_desc_mapsToDesc() {
    assertThat(ByModulatorTranslator.parseSortDirection(Order.desc))
        .contains(com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderByItem.DESC);
  }

  /** Edge-bearing modulators ({@code by(__.out(L).count())}) decline under Phase 1. */
  @Test
  public void valueSide_edgeTraversal_declines() {
    var modulator = __.out("knows").count().asAdmin();

    assertThat(ByModulatorTranslator.translateValueModulator(ALIAS, modulator)).isEmpty();
    assertThat(ByModulatorTranslator.translateKeyModulator(ALIAS, modulator)).isEmpty();
  }

  /**
   * {@code by(__.values("age").sum())} (and min/max/mean/count) maps to {@link
   * ByModulatorTranslator.ValueAccumulator.PropertyAggregate} over {@code alias.age}.
   */
  @Test
  public void valueSide_propertyAggregates_resolvePropertyAggregate() {
    assertPropertyAggregate(__.values("age").sum().asAdmin(),
        ByModulatorTranslator.ValueAccumulator.AggregateFunction.SUM);
    assertPropertyAggregate(__.values("age").min().asAdmin(),
        ByModulatorTranslator.ValueAccumulator.AggregateFunction.MIN);
    assertPropertyAggregate(__.values("age").max().asAdmin(),
        ByModulatorTranslator.ValueAccumulator.AggregateFunction.MAX);
    assertPropertyAggregate(__.values("age").mean().asAdmin(),
        ByModulatorTranslator.ValueAccumulator.AggregateFunction.MEAN);
    assertPropertyAggregate(__.values("age").count().asAdmin(),
        ByModulatorTranslator.ValueAccumulator.AggregateFunction.COUNT);
  }

  /** {@code by(__.label())} resolves to {@code alias.@class}. */
  @Test
  public void keySide_labelToken_resolvesClass() {
    var modulator = graph.traversal().V().project("n").by(T.label).asAdmin().getSteps().getLast();
    var ring =
        ((org.apache.tinkerpop.gremlin.process.traversal.step.map.ProjectStep<?, ?>) modulator)
            .getTraversalRing()
            .getTraversals()
            .getFirst();

    var field = ByModulatorTranslator.translateKeyModulator(ALIAS, ring);

    assertThat(field).isPresent();
    assertThat(field.get().toString()).contains("@class");
  }

  /**
   * The key side declines the element form, and the value form beside it still resolves. The bodies
   * are read after {@code applyStrategies()} because that is what production delivers: a written
   * {@code by(__.values("age"))} never arrives as a {@code PropertiesStep} at all — the by-modulator
   * optimisation converts a one-step value projection into a {@code ValueTraversal} first — so the
   * element form on this side is only ever hand-written, and keying on a {@code VertexProperty}
   * element as if it were its payload merges buckets native keeps apart.
   */
  @Test
  public void keySide_postStrategyElementForm_declines_whileValuesResolves() {
    var elementForm =
        postStrategyModulator(
            graph.traversal().V().group().by(__.properties("age")).by(__.count()), 0);
    assertThat(firstStepReturnType(elementForm))
        .as("premise: nothing rewrites a hand-written key-side element form before the translator")
        .isEqualTo(PropertyType.PROPERTY);

    assertThat(ByModulatorTranslator.translateKeyModulator(ALIAS, elementForm)).isEmpty();

    var valueForm =
        postStrategyModulator(
            graph.traversal().V().group().by(__.values("age")).by(__.count()), 0);
    assertThat(ByModulatorTranslator.translateKeyModulator(ALIAS, valueForm))
        .as("the decline must be specific to the element form")
        .isPresent();
  }

  /**
   * The value side resolves the post-strategy element form for {@code count}, and for {@code count}
   * only. {@code AdjacentToIncidentStrategy} rewrites the projection of a written
   * {@code by(__.values("age").count())} into the element form before the translator sees the body, so
   * a value-only predicate on this side withdraws a shape callers do write — measured as the whole
   * {@code group()} losing its translation. One property element per value leaves the count unchanged,
   * which is what makes this arm safe and the accumulating arms not: the {@code sum} sibling keeps the
   * value form, because the strategy rewrites only in front of a count.
   */
  @Test
  public void valueSide_postStrategyElementFormCount_resolvesAggregate() {
    var countBody =
        postStrategyModulator(
            graph.traversal().V().group().by("name").by(__.values("age").count()), 1);
    assertThat(firstStepReturnType(countBody))
        .as("premise: the strategy must have rewritten values(age) into the element form")
        .isEqualTo(PropertyType.PROPERTY);

    var countAccumulator = ByModulatorTranslator.translateValueModulator(ALIAS, countBody);
    assertThat(countAccumulator)
        .containsInstanceOf(ByModulatorTranslator.ValueAccumulator.PropertyAggregate.class);
    var aggregate =
        (ByModulatorTranslator.ValueAccumulator.PropertyAggregate) countAccumulator.orElseThrow();
    assertThat(aggregate.function())
        .isEqualTo(ByModulatorTranslator.ValueAccumulator.AggregateFunction.COUNT);
    assertThat(aggregate.field().toString()).contains("age");

    var sumBody =
        postStrategyModulator(
            graph.traversal().V().group().by("name").by(__.values("age").sum()), 1);
    assertThat(firstStepReturnType(sumBody))
        .as("the strategy rewrites only in front of a count, so the sum body keeps the value form")
        .isEqualTo(PropertyType.VALUE);
    assertThat(ByModulatorTranslator.translateValueModulator(ALIAS, sumBody))
        .containsInstanceOf(ByModulatorTranslator.ValueAccumulator.PropertyAggregate.class);

    // The widening is specific to count: an accumulator that reads the payload still declines the
    // element form, which no strategy produces in that position anyway.
    assertThat(
        ByModulatorTranslator.translateValueModulator(
            ALIAS, __.properties("age").sum().asAdmin()))
        .isEmpty();
  }

  /**
   * The presence conjunct's gate, read directly over each outcome its contract names: a property key
   * comes back, a record attribute does not, and neither does an unrecognised or absent body. The
   * record-attribute arm cannot be observed through rows at all — every record carries a RID and a
   * class, so an {@code IS DEFINED} conjunct on one filters nothing and a case that dropped the
   * filter would keep passing — while the spurious conjunct still distorts root selection, so this is
   * the only place the arm can be checked.
   *
   * <p>Both spellings of each arm are driven: the {@code ValueTraversal} / {@code TokenTraversal}
   * bodies production delivers after TinkerPop's by-modulator optimisation, and the hand-built step
   * bodies that reach the classifier unrewritten.
   */
  @Test
  public void keyModulatorPropertyKey_returnsPropertyKeysOnly() {
    var valueTraversalBody =
        postStrategyModulator(graph.traversal().V().group().by("age").by(__.count()), 0);
    assertThat(ByModulatorTranslator.keyModulatorPropertyKey(valueTraversalBody))
        .as("by(\"age\") names a property, so the presence conjunct is on age")
        .contains("age");

    var tokenTraversalBody =
        postStrategyModulator(graph.traversal().V().group().by(T.label).by(__.count()), 0);
    assertThat(ByModulatorTranslator.keyModulatorPropertyKey(tokenTraversalBody))
        .as("by(T.label) names @class, which every record has, so no conjunct is contributed")
        .isEmpty();

    assertThat(ByModulatorTranslator.keyModulatorPropertyKey(__.values("age").asAdmin()))
        .contains("age");
    assertThat(ByModulatorTranslator.keyModulatorPropertyKey(__.id().asAdmin())).isEmpty();
    assertThat(ByModulatorTranslator.keyModulatorPropertyKey(__.label().asAdmin())).isEmpty();
    assertThat(ByModulatorTranslator.keyModulatorPropertyKey(__.out("knows").asAdmin()))
        .as("an edge traversal is not a key the conjunct can name")
        .isEmpty();
    assertThat(ByModulatorTranslator.keyModulatorPropertyKey(null)).isEmpty();
  }

  /**
   * The {@code by(...)} body at {@code childIndex} after TinkerPop's strategies have run. Hand-built
   * bodies are not what production delivers — {@code AdjacentToIncidentStrategy} rewrites a
   * {@code values(key)} in front of a {@code count()} into the element form, and the by-modulator
   * optimisation folds a one-step value projection into a {@code ValueTraversal} — so a case built
   * without {@code applyStrategies()} classifies a shape the translator never receives. The
   * Gremlin-to-MATCH translator is switched off around the call: with it on, a recognised traversal is
   * replaced by a plan step and the modulator disappears with it.
   */
  private Traversal.Admin<?, ?> postStrategyModulator(
      GraphTraversal<?, ?> traversal, int childIndex) {
    var original =
        session
            .getConfiguration()
            .getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED);
    try {
      session
          .getConfiguration()
          .setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, false);
      var admin = traversal.asAdmin();
      admin.applyStrategies();
      var groupStep =
          admin.getSteps().stream()
              .filter(step -> step.getClass().getSimpleName().contains("Group"))
              .findFirst()
              .orElseThrow();
      return ((TraversalParent) groupStep).getLocalChildren().get(childIndex);
    } finally {
      session
          .getConfiguration()
          .setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, original);
    }
  }

  /**
   * The {@link PropertyType} of a modulator body's first step, or {@code null} when that step is not a
   * property projection (a {@code ValueTraversal} body has no steps at all).
   */
  private static PropertyType firstStepReturnType(Traversal.Admin<?, ?> modulator) {
    var steps = modulator.getSteps();
    if (steps.isEmpty() || !(steps.getFirst() instanceof PropertiesStep<?> propertiesStep)) {
      return null;
    }
    return propertiesStep.getReturnType();
  }

  private static void assertPropertyAggregate(
      org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin<?, ?> modulator,
      ByModulatorTranslator.ValueAccumulator.AggregateFunction expected) {
    var accumulator = ByModulatorTranslator.translateValueModulator(ALIAS, modulator);
    assertThat(accumulator)
        .containsInstanceOf(ByModulatorTranslator.ValueAccumulator.PropertyAggregate.class);
    var agg = (ByModulatorTranslator.ValueAccumulator.PropertyAggregate) accumulator.get();
    assertThat(agg.function()).isEqualTo(expected);
    assertThat(agg.field().toString()).contains("age");
  }
}
