package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import java.util.List;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.TextP;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Test;

/** Exact native/translated rows and translation engagement for typed connective filters. */
public class LabelConnectiveEquivalenceTest extends GraphBaseTest {

  private final TranslatorEquivalenceSupport support =
      new TranslatorEquivalenceSupport(() -> session);

  private void seed() {
    var parent = session.createVertexClass("ProbeParent");
    var sub = session.getSchema().createClass("ProbeSub", parent);
    var a = session.getSchema().createClass("ProbeA", sub);
    session.getSchema().createClass("ProbeB", sub);
    session.getSchema().createClass("ProbeAA", a);
    session.getSchema().createClass("ProbeOther", parent);
    session.getSchema().createClass("ProbeSource", parent);
    session.createEdgeClass("ProbeLink");
    var source = graph.addVertex(T.label, "ProbeSource", "name", "source", "keep", true);
    for (var entry : List.of(List.of("ProbeParent", "parent"),
        List.of("ProbeSub", "sam"), List.of("ProbeA", "amy"),
        List.of("ProbeB", "betty"), List.of("ProbeAA", "alex"),
        List.of("ProbeOther", "other"))) {
      var target = graph.addVertex(T.label, entry.get(0), "name", entry.get(1), "keep", true);
      source.addEdge("ProbeLink", target);
    }
    graph.tx().commit();
  }

  /** Multi-label children keep their explicit alternatives when MATCH re-types to their LCA. */
  @Test
  public void multiLabelWithPropertyInConnectivesMatchesNative() {
    seed();
    for (boolean polymorphic : new boolean[] {true, false}) {
      withPolymorphic(polymorphic, () -> {
        for (boolean barrier : new boolean[] {false, true}) {
          String tag = "multi poly=" + polymorphic + " barrier=" + barrier;
          assertRows(tag + " top", polymorphic
              ? List.of("alex", "amy", "betty") : List.of("amy", "betty"),
              () -> graph.traversal().V().hasLabel("ProbeA", "ProbeB").values("name"));
          assertRows(tag + " or", polymorphic
              ? List.of("amy", "parent") : List.of("parent"),
              () -> parentStart(barrier).or(
                  __.hasLabel("ProbeA", "ProbeB").has("name", "amy"),
                  __.has("name", "parent")).values("name"));
          assertRows(tag + " not", polymorphic
              ? List.of("alex", "betty", "other", "parent", "sam", "source")
              : List.of("parent"),
              () -> parentStart(barrier).not(
                  __.hasLabel("ProbeA", "ProbeB").has("name", "amy"))
                  .values("name"));
          // Without a parent barrier, and/where fold conflicting labels into one GraphStep.
          // A parent barrier keeps the filters separate, so the translator accepts them.
          assertRows(tag + " and", polymorphic ? List.of("amy") : List.of(),
              () -> parentStart(barrier).and(
                  __.hasLabel("ProbeA", "ProbeB").has("name", "amy"),
                  __.has("keep", true)).values("name"),
              barrier ? 1 : 0);
          assertRows(tag + " where", polymorphic ? List.of("amy") : List.of(),
              () -> parentStart(barrier).where(
                  __.hasLabel("ProbeA", "ProbeB").has("name", "amy"))
                  .values("name"),
              barrier ? 1 : 0);
          assertRows(tag + " ancestor or", polymorphic
              ? List.of("alex", "amy", "betty", "parent", "sam") : List.of("parent"),
              () -> parentStart(barrier).or(
                  __.hasLabel("ProbeSub", "ProbeA").has("keep", true),
                  __.has("name", "parent")).values("name"));
        }
      });
    }
  }

  /** Both typed source layouts keep multi-label child alternatives across a child-local barrier. */
  @Test
  public void capturedMultiLabelsWithBarrierAndTypedSourcesMatchNative() {
    seed();
    for (boolean polymorphic : new boolean[] {true, false}) {
      withPolymorphic(polymorphic, () -> {
        for (boolean alternatives : new boolean[] {false, true}) {
          for (boolean barrier : new boolean[] {false, true}) {
            String tag = "typed alternatives=" + alternatives + " poly=" + polymorphic
                + " barrier=" + barrier;
            List<String> yes = alternatives || polymorphic ? List.of("amy") : List.of();
            List<String> no = alternatives
                ? polymorphic ? List.of("alex", "betty") : List.of("betty")
                : polymorphic ? List.of("alex", "betty", "other", "parent", "sam", "source")
                    : List.of("parent");
            assertRows(tag + " or", yes,
                () -> typedStart(alternatives).or(labelChild(barrier, "ProbeA", "ProbeB"),
                    __.has("name", "absent")).values("name"));
            assertRows(tag + " not", no,
                () -> typedStart(alternatives).not(labelChild(barrier, "ProbeA", "ProbeB"))
                    .values("name"));
            if (!alternatives) {
              // A child barrier prevents and/where from folding its labels into the source.
              assertRows(tag + " and", yes,
                  () -> typedStart(false).and(labelChild(barrier, "ProbeA", "ProbeB"),
                      __.has("keep", true)).values("name"),
                  barrier ? 1 : 0);
              assertRows(tag + " where", yes,
                  () -> typedStart(false).where(labelChild(barrier, "ProbeA", "ProbeB"))
                      .values("name"),
                  barrier ? 1 : 0);
            }
            assertRows(tag + " ancestor or", yes,
                () -> typedStart(alternatives).or(labelChild(barrier, "ProbeSub", "ProbeA"),
                    __.has("name", "absent")).values("name"));
          }
          assertRows("typed top alternatives=" + alternatives + " poly=" + polymorphic,
              alternatives || polymorphic ? List.of("amy") : List.of(),
              () -> typedStart(alternatives).barrier(2).hasLabel("ProbeA", "ProbeB")
                  .barrier(2).has("name", TextP.startingWith("am")).values("name"));
        }
      });
    }
  }

  /**
   * A nested B filter changes the captured class after the outer A/B filter. OR and NOT must
   * decline this captured refinement and keep the native rows.
   */
  @Test
  public void nestedMultiLabelConnectivesKeepNativeRowsAndTranslation() {
    var parent = session.createVertexClass("ProbeParent");
    var sub = session.getSchema().createClass("ProbeSub", parent);
    session.getSchema().createClass("ProbeA", sub);
    session.getSchema().createClass("ProbeB", sub);
    session.getSchema().createClass("ProbeOther", parent);
    for (var entry : List.of(List.of("ProbeA", "alex"), List.of("ProbeA", "amy"),
        List.of("ProbeB", "ben"), List.of("ProbeOther", "other"))) {
      graph.addVertex(T.label, entry.get(0), "name", entry.get(1), "keep", true);
    }
    graph.tx().commit();
    for (boolean polymorphic : new boolean[] {true, false}) {
      withPolymorphic(polymorphic, () -> {
        String tag = "nested multi poly=" + polymorphic;
        assertRows(tag + " where or", List.of("ben", "other"),
            () -> graph.traversal().V().or(
                __.hasLabel("ProbeA", "ProbeB").barrier(2)
                    .where(__.hasLabel("ProbeB").barrier(2).has("keep", true)),
                __.has("name", "other")).values("name"),
            0);
        assertRows(tag + " and or", List.of("ben", "other"),
            () -> graph.traversal().V().or(
                __.hasLabel("ProbeA", "ProbeB").barrier(2)
                    .and(__.hasLabel("ProbeB").barrier(2).has("keep", true)),
                __.has("name", "other")).values("name"),
            0);
        assertRows(tag + " where not", List.of("alex", "amy", "other"),
            () -> graph.traversal().V().not(
                __.hasLabel("ProbeA", "ProbeB").barrier(2)
                    .where(__.hasLabel("ProbeB").barrier(2).has("keep", true)))
                .values("name"),
            0);
      });
    }
  }

  private GraphTraversal<?, ?> labelChild(boolean barrier, String... labels) {
    var child = __.hasLabel(labels[0], labels[1]);
    return (barrier ? child.barrier(2) : child).has("name", TextP.startingWith("am"));
  }

  private GraphTraversal<?, ?> typedStart(boolean alternatives) {
    return alternatives ? graph.traversal().V().hasLabel("ProbeA", "ProbeB")
        : graph.traversal().V().hasLabel("ProbeParent");
  }

  private GraphTraversal<?, ?> parentStart(boolean barrier) {
    var traversal = graph.traversal().V().hasLabel("ProbeParent");
    return barrier ? traversal.barrier(2) : traversal;
  }

  private void withPolymorphic(boolean value, Runnable body) {
    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    var config = tx.getDatabaseSession().getConfiguration();
    var old = config.getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT);
    config.setValue(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT, value);
    try {
      body.run();
    } finally {
      config.setValue(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT, old);
    }
  }

  private void assertRows(String name, List<String> expected,
      Supplier<GraphTraversal<?, ?>> traversal) {
    assertRows(name, expected, traversal, 1);
  }

  /** Checks the exact translation boundary count and both native and translator-on rows. */
  private void assertRows(String name, List<String> expected,
      Supplier<GraphTraversal<?, ?>> traversal, int expectedBoundaries) {
    var sorted = expected.stream().sorted().toList();
    support.withTranslator(false, () -> {
      var nativeTraversal = traversal.get().asAdmin();
      nativeTraversal.applyStrategies();
      assertThat(TranslatorEquivalenceSupport.countBoundarySteps(nativeTraversal)).isZero();
      assertThat(nativeTraversal.toList().stream().map(String::valueOf).sorted().toList())
          .as(name + " native").isEqualTo(sorted);
    });
    support.withTranslator(true, () -> {
      var translated = traversal.get().asAdmin();
      translated.applyStrategies();
      var boundaries = TranslatorEquivalenceSupport.countBoundarySteps(translated);
      assertThat(boundaries).as(name + " translated boundary").isEqualTo(expectedBoundaries);
      assertThat(translated.toList().stream().map(String::valueOf).sorted().toList())
          .as(name + " translated rows").isEqualTo(sorted);
    });
  }
}
