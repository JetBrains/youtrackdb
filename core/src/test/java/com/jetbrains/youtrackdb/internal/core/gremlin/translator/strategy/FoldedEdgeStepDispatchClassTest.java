package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.sideeffect.YTDBGraphStep;
import java.util.List;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.EdgeOtherVertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.EdgeVertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStepPlaceholder;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Assert;
import org.junit.Test;

/**
 * Pins the concrete post-{@code applyStrategies()} step classes for the edge-traversal shapes the
 * upcoming recognisers must key on, so a change in TinkerPop's structural folding (or in YTDB's
 * fork) fails loudly here instead of silently declining a shape the recogniser registry was keyed
 * for. This is the decomposition-time dispatch-class verification the plan requires before any
 * edge recogniser is registered.
 *
 * <h2>Why the translator is disabled here</h2>
 *
 * The recognisers run <em>inside</em> the translator strategy, which fires after TinkerPop's
 * structural folders ({@code IncidentToAdjacentStrategy}, {@code LazyBarrierStrategy}) but before
 * {@code YTDBGraphStepStrategy}. So the shape a recogniser sees is the native, structurally-folded
 * step list. These tests disable the translator (kill-switch off) and read the step list after
 * {@code applyStrategies()}, capturing exactly that native folded shape. Disabling the translator
 * also keeps the assertions stable across later tracks: once a {@code VertexStep} recogniser lands,
 * an enabled translator would collapse {@code g.V().out()} to a single boundary step, which is not
 * what we want to pin here.
 *
 * <p>The start step folds to {@code YTDBGraphStep} once {@code YTDBGraphStepStrategy} runs, but the
 * <em>follow-up</em> step classes (the edge hop, the interleaved {@code has}, the closing vertex
 * hop) are unaffected by that fold — they are the classes the recognisers dispatch on, and they are
 * what these tests pin. Assertions compare fully-qualified class names so a failure prints the
 * observed shape directly.
 *
 * <h2>Recorded observations (2026-07, this branch)</h2>
 *
 * <ul>
 *   <li>{@code out(L)} / {@code in(L)} / {@code both(L)} and the adjacent folded {@code
 *       outE(L).inV()} / {@code bothE(L).otherV()} shapes all collapse to a single {@link
 *       VertexStep} — so a single {@code VertexStep} recogniser handles both bare hops and folded
 *       adjacent edge chains.</li>
 *   <li>The non-adjacent {@code outE(L).has(...).inV()} shape does <em>not</em> fold: the edge step
 *       is a {@link VertexStep} with {@code returnsEdge() == true} (the same class as {@code
 *       out(L)}, distinguished only by {@code returnsEdge()}), the interleaved filter is a {@link
 *       HasStep}, and the closing hop is an {@link EdgeVertexStep} (for {@code inV}/{@code outV}) or
 *       an {@link EdgeOtherVertexStep} (for {@code otherV}).</li>
 *   <li>No {@link VertexStepPlaceholder} appears for literal string labels — the deferred-{@code
 *       GValue} placeholder is not produced here. A parameterised label may differ; a later step
 *       that binds label parameters should re-confirm.</li>
 * </ul>
 */
public class FoldedEdgeStepDispatchClassTest extends GraphBaseTest {

  private void seedKnowsGraph() {
    var a = graph.addVertex(T.label, "Person", "name", "A");
    var b = graph.addVertex(T.label, "Person", "name", "B");
    a.addEdge("knows", b, "w", 1);
    graph.tx().commit();
  }

  /**
   * Returns the fully-qualified step class names of {@code traversal} after {@code
   * applyStrategies()} runs with the translator disabled — i.e. the native structurally-folded
   * shape a recogniser sees.
   */
  private List<String> nativeFoldedClassNames(Traversal.Admin<?, ?> traversal) {
    withTranslatorDisabled(traversal::applyStrategies);
    return traversal.getSteps().stream().map(step -> step.getClass().getName()).toList();
  }

  /**
   * {@code out(L)} / {@code in(L)} / {@code both(L)} each fold to a single {@link VertexStep} after
   * the start {@code YTDBGraphStep} — one recogniser key covers all three directions.
   */
  @Test
  public void bareHops_foldToSingleVertexStep() {
    seedKnowsGraph();

    for (var shape : List.of(
        graph.traversal().V().out("knows").asAdmin(),
        graph.traversal().V().in("knows").asAdmin(),
        graph.traversal().V().both("knows").asAdmin())) {
      var classNames = nativeFoldedClassNames(shape);
      assertThat(classNames)
          .as("bare hop native fold")
          .containsExactly(YTDBGraphStep.class.getName(), VertexStep.class.getName());
    }
  }

  /**
   * Adjacent {@code outE(L).inV()} and {@code bothE(L).otherV()} are folded by {@code
   * IncidentToAdjacentStrategy} to a single {@link VertexStep} — the same shape as a bare hop, so
   * the folded adjacent edge chain needs no separate recogniser key.
   */
  @Test
  public void adjacentEdgeChains_foldToSingleVertexStep() {
    seedKnowsGraph();

    var outFold = nativeFoldedClassNames(graph.traversal().V().outE("knows").inV().asAdmin());
    assertThat(outFold)
        .as("adjacent outE.inV fold")
        .containsExactly(YTDBGraphStep.class.getName(), VertexStep.class.getName());

    var bothFold = nativeFoldedClassNames(graph.traversal().V().bothE("knows").otherV().asAdmin());
    assertThat(bothFold)
        .as("adjacent bothE.otherV fold")
        .containsExactly(YTDBGraphStep.class.getName(), VertexStep.class.getName());
  }

  /**
   * The non-adjacent {@code outE(L).has(...).inV()} shape does not fold: it arrives as an edge
   * {@link VertexStep}, a {@link HasStep}, and a closing {@link EdgeVertexStep}. The edge step is
   * the same class as a bare hop but is distinguished by {@code returnsEdge() == true} — the branch
   * the future single {@code VertexStep} recogniser keys on. {@code inE().has().outV()} produces the
   * same class sequence (the closing {@code outV} is also an {@code EdgeVertexStep}).
   */
  @Test
  public void nonAdjacentOutInEdgeFilter_arrivesAsEdgeStepHasStepEdgeVertexStep() {
    seedKnowsGraph();

    var outAdmin = graph.traversal().V().outE("knows").has("w", 1).inV().asAdmin();
    withTranslatorDisabled(outAdmin::applyStrategies);
    var outSteps = outAdmin.getSteps();
    assertThat(outSteps.stream().map(step -> step.getClass().getName()).toList())
        .as("non-adjacent outE.has.inV shape")
        .containsExactly(
            YTDBGraphStep.class.getName(),
            VertexStep.class.getName(),
            HasStep.class.getName(),
            EdgeVertexStep.class.getName());
    // The edge step is a VertexStep with returnsEdge() == true — the key distinction from a bare
    // out() hop, which the future single VertexStep recogniser branches on.
    var edgeStep = (VertexStep<?>) outSteps.get(1);
    assertThat(edgeStep.returnsEdge())
        .as("the non-adjacent outE step must be an edge-returning VertexStep")
        .isTrue();

    var inClasses =
        nativeFoldedClassNames(graph.traversal().V().inE("knows").has("w", 1).outV().asAdmin());
    assertThat(inClasses)
        .as("non-adjacent inE.has.outV shape")
        .containsExactly(
            YTDBGraphStep.class.getName(),
            VertexStep.class.getName(),
            HasStep.class.getName(),
            EdgeVertexStep.class.getName());
  }

  /**
   * The non-adjacent {@code bothE(L).has(...).otherV()} shape closes on an {@link
   * EdgeOtherVertexStep} rather than an {@link EdgeVertexStep} — the distinct closing class the
   * {@code both} edge chain uses, which the future edge recogniser must accept alongside {@code
   * EdgeVertexStep}.
   */
  @Test
  public void nonAdjacentBothEdgeFilter_closesOnEdgeOtherVertexStep() {
    seedKnowsGraph();

    var classNames =
        nativeFoldedClassNames(graph.traversal().V().bothE("knows").has("w", 1).otherV().asAdmin());
    assertThat(classNames)
        .as("non-adjacent bothE.has.otherV shape")
        .containsExactly(
            YTDBGraphStep.class.getName(),
            VertexStep.class.getName(),
            HasStep.class.getName(),
            EdgeOtherVertexStep.class.getName());
  }

  /**
   * No {@link VertexStepPlaceholder} is produced for literal string labels across every target
   * shape — the deferred-{@code GValue} placeholder the plan flagged as a defensive concern does
   * not appear here, so the recognisers may key on the concrete {@code VertexStep}. (A
   * parameterised label may still yield a placeholder; a later step that binds label parameters
   * should re-confirm.)
   */
  @Test
  public void literalLabelShapes_produceNoVertexStepPlaceholder() {
    seedKnowsGraph();

    for (var shape : List.of(
        graph.traversal().V().out("knows").asAdmin(),
        graph.traversal().V().outE("knows").inV().asAdmin(),
        graph.traversal().V().outE("knows").has("w", 1).inV().asAdmin(),
        graph.traversal().V().bothE("knows").has("w", 1).otherV().asAdmin())) {
      var classNames = nativeFoldedClassNames(shape);
      assertThat(classNames)
          .as("no VertexStepPlaceholder for literal labels")
          .doesNotContain(VertexStepPlaceholder.class.getName());
    }
  }

  /**
   * Runs {@code body} with {@code QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED} forced to false,
   * restoring the previous value afterwards, so {@code applyStrategies()} produces the native
   * folded shape rather than a translated boundary step.
   */
  private void withTranslatorDisabled(Runnable body) {
    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    var config = tx.getDatabaseSession().getConfiguration();
    Assert.assertNotNull(config);
    var previous =
        config.getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED);
    config.setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, false);
    try {
      body.run();
    } finally {
      config.setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, previous);
    }
  }
}
