package com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
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
