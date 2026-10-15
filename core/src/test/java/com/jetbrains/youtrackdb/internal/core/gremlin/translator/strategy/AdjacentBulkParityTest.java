package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.AbstractMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.TraversalFilterStep;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.TraverserRequirement;
import org.apache.tinkerpop.gremlin.process.traversal.util.DefaultTraversal;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Test;

/** Graph-backed checks pin translation, real bulk emission and exact native expanded order. */
public class AdjacentBulkParityTest extends GraphBaseTest {

  private final TranslatorEquivalenceSupport support =
      new TranslatorEquivalenceSupport(() -> session);

  /** Duplicate graph paths compress both elements and scalar projections, including MATCH labels. */
  @Test
  public void duplicatesAndInternalLabelsCompressAfterShaping() {
    seed();
    assertThat(parity(() -> graph.traversal().V().has("start", true).out("link"), true))
        .containsExactly(3L);
    assertThat(parity(() -> graph.traversal().V().has("start", true).as("source")
        .out("link").where(org.apache.tinkerpop.gremlin.process.traversal.P.neq("source"))
        .values("p"), true)).containsExactly(3L);
    assertThat(parity(() -> graph.traversal().V().has("start", true).out("link")
        .order().by("p").range(1, 3).values("p"), true)).containsExactly(2L);
    graph.addVertex("p", "same");
    graph.tx().commit();
    assertThat(parity(() -> graph.traversal().V().values("p").dedup(), false))
        .containsExactly(1L);
    graph.addVertex("items", List.of("x", "x", "y", "x"));
    graph.tx().commit();
    assertThat(parity(() -> graph.traversal().V().values("items").unfold(), true))
        .containsExactly(2L, 1L, 1L);
  }

  /** An ordered unique-key stream preserves adjacent and separated repeats after final projection. */
  @Test
  public void orderedSeparatedDuplicatesKeepTheirPositions() {
    var values = List.of("a", "a", "b", "a");
    for (int i = 0; i < values.size(); i++) {
      graph.addVertex("rank", i, "p", values.get(i));
    }
    graph.tx().commit();
    assertThat(parity(() -> graph.traversal().V().has("rank").order().by("rank")
        .values("p"), true)).containsExactly(2L, 1L, 1L);
  }

  /** Root and child executions share a warm template, but only the live root may merge. */
  @Test
  public void warmCacheChecksLiveBoundaryRatherThanTemplatePosition() throws Exception {
    seed();
    Supplier<GraphTraversal<?, ?>> shape = () -> graph.traversal().V().has("p", "same")
        .in("link").values("start");
    var root = shape.get().asAdmin();
    root.applyStrategies();
    var boundary = (YTDBMatchPlanStep<?, ?>) root.getStartStep();
    var template = boundary.getPlan();
    assertThat(root.nextTraverser().bulk()).isEqualTo(3);
    root.close();
    var cache = GremlinPlanCache.instance(session);
    var hits = cache.getTranslationHits();
    var child = shape.get().asAdmin();
    child.applyStrategies();
    assertThat(((YTDBMatchPlanStep<?, ?>) child.getStartStep()).getPlan()).isSameAs(template);
    assertThat(cache.getTranslationHits()).isEqualTo(hits + 1);
    new TraversalFilterStep(new DefaultTraversal<>(graph), child);
    var bulks = new ArrayList<Long>();
    while (child.getStartStep().hasNext()) {
      bulks.add(child.getStartStep().next().bulk());
    }
    assertThat(bulks).containsExactly(1L, 1L, 1L);
    child.close();
    assertThat(parity(shape, true)).containsExactly(3L);
  }

  /** Sacks retain unit emission. Disabled bulk declines translation instead of activating D1. */
  @Test
  public void sacksAndDisabledBulkAreNegativeControls() {
    seed();
    assertThat(parity(() -> graph.traversal().withSack(1, Integer::sum)
        .V().has("start", true).out("link").values("p"), false))
        .containsExactly(1L, 1L, 1L);
    support.withTranslator(true, () -> {
      var admin = graph.traversal().withBulk(false).V().has("start", true)
          .out("link").values("p").asAdmin();
      admin.applyStrategies();
      assertThat(TranslatorEquivalenceSupport.countBoundarySteps(admin)).isZero();
      var disabledRows = admin.toList();
      assertThat(disabledRows).isNotEmpty();
      support.withTranslator(false, () -> assertThat(disabledRows).isEqualTo(
          graph.traversal().withBulk(false).V().has("start", true)
              .out("link").values("p").toList()));
    });
  }

  /** Group maps, per-union-child maps and folded lists remain separate unit-bulk payloads. */
  @Test
  public void containersAndEmptyInputKeepTheirContracts() {
    seed();
    assertThat(parity(() -> graph.traversal().V().has("start", true).out("link")
        .groupCount().by("p"), false)).containsExactly(1L);
    assertThat(parity(() -> graph.traversal().V().has("start", true)
        .union(__.out("link").groupCount().by("p"),
            __.out("link").groupCount().by("p")),
        false)).containsExactly(1L, 1L);
    assertThat(parity(() -> graph.traversal().V().has("start", true).out("link")
        .values("p").fold(), false)).containsExactly(1L);
    assertThat(parity(() -> graph.traversal().V().has("start", "absent")
        .values("p"), false)).isEmpty();
  }

  private void seed() {
    var start = graph.addVertex(T.label, "Person", "start", true);
    var target = graph.addVertex(T.label, "Person", "p", "same");
    for (int i = 0; i < 3; i++) {
      start.addEdge("link", target);
    }
    graph.tx().commit();
  }

  private List<Long> parity(Supplier<GraphTraversal<?, ?>> shape, boolean expectCompression) {
    var expanded = new ArrayList<Object>();
    var bulks = new ArrayList<Long>();
    support.withTranslator(true, () -> {
      var admin = shape.get().asAdmin();
      admin.applyStrategies();
      assertThat(admin.getStartStep()).isInstanceOf(AbstractMatchPlanStep.class);
      assertThat(admin.getTraverserGenerator().getProvidedRequirements())
          .contains(TraverserRequirement.BULK);
      while (admin.hasNext()) {
        var traverser = admin.nextTraverser();
        bulks.add(traverser.bulk());
        for (long i = 0; i < traverser.bulk(); i++) {
          expanded.add(traverser.get());
        }
      }
    });
    if (expectCompression) {
      assertThat(bulks).anyMatch(bulk -> bulk > 1);
      assertThat(bulks.size()).isLessThan(expanded.size());
    } else {
      assertThat(bulks).allMatch(bulk -> bulk == 1);
    }
    support.withTranslator(false, () -> {
      Traversal.Admin<?, ?> nativeAdmin = shape.get().asAdmin();
      nativeAdmin.applyStrategies();
      assertThat(TranslatorEquivalenceSupport.countBoundarySteps(nativeAdmin)).isZero();
      assertThat(expanded).isEqualTo(nativeAdmin.toList());
    });
    return bulks;
  }
}
