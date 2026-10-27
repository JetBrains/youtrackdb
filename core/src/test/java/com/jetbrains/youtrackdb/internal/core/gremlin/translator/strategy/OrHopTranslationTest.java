package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.Cardinality;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.Recognition;
import java.util.List;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.AndStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.TraversalFilterStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.InlineFilterStrategy;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/** Differential witnesses for scoped OR checks, with explicit nonempty expected rows. */
@Category(SequentialTest.class)
public class OrHopTranslationTest extends GraphBaseTest {

  private final TranslatorEquivalenceSupport support =
      new TranslatorEquivalenceSupport(() -> session);

  private void check(String scenario, Recognition recognition,
      Supplier<GraphTraversal<?, ?>> traversal) {
    support.assertEquivalent(scenario, recognition, Cardinality.NON_EMPTY,
        TranslatorEquivalenceSupport::sortedIdsOrValues, traversal);
  }

  private void seed() {
    for (var name : List.of("filter", "hop", "both", "neither")) {
      var article = graph.addVertex(T.label, "Article", "name", name, "slug",
          name.equals("filter") || name.equals("both") ? "yes" : "no");
      if (name.equals("hop") || name.equals("both")) {
        for (int i = 0; i < 3; i++) {
          article.addEdge("category", graph.addVertex(T.label, "Category", "uid", "u"),
              "weight", 2);
        }
      }
      for (int i = 0; i < 2; i++) {
        graph.addVertex(T.label, "Source").addEdge("entry", article);
      }
    }
    graph.tx().commit();
  }

  /** Both cache layers must reuse structure while returning changed answers for fresh operands. */
  @Test
  public void cacheReplayAndConcurrentCopiesUseFreshBindings() throws Exception {
    seed();
    support.withTranslator(true, () -> {
      var cache = GremlinPlanCache.instance(graphSession());
      cache.invalidate();
      var first = cachedSelection("u", "yes").asAdmin();
      GremlinToMatchStrategy.instance().apply(first);
      assertThat(TranslatorEquivalenceSupport.countBoundarySteps(first)).isEqualTo(1);
      var template =
          ((com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep<?,
              ?>) first.getStartStep()).getPlan();
      assertThat(first.toList()).hasSize(3);
      long hits = cache.getTranslationHits();
      var second = cachedSelection("absent", "no").asAdmin();
      GremlinToMatchStrategy.instance().apply(second);
      assertThat(TranslatorEquivalenceSupport.countBoundarySteps(second)).isEqualTo(1);
      assertThat(cache.getTranslationHits()).isEqualTo(hits + 1);
      assertThat(((com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep<
          ?, ?>) second.getStartStep()).getPlan()).isSameAs(template);
      assertThat(second.toList()).hasSize(2);
      var translation =
          GremlinStepWalker.production().walk(cachedSelection("absent", "no").asAdmin());
      assertThat(translation).isNotNull();
      var key = GremlinPlanFingerprint.fingerprint(translation.inputs(), translation.shaping());
      var ctx =
          new com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext(graphSession());
      ctx.setInputParameters(translation.inputParameters());
      var copy = GremlinPlanCache.get(key, ctx, graphSession());
      assertThat(copy).isNotNull().isNotSameAs(template);
      var stream = copy.start();
      try {
        assertThat(stream.stream(ctx).toList()).hasSize(2);
      } finally {
        stream.close(ctx);
        copy.close();
      }
    });
    graph.tx().commit();
    var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
    var barrier = new java.util.concurrent.CyclicBarrier(2);
    try {
      var futures = new java.util.ArrayList<java.util.concurrent.Future<Object[]>>();
      for (boolean matching : List.of(true, false)) {
        futures.add(executor.submit(() -> {
          try {
            var db = graphSession();
            var traversal = cachedSelection(matching ? "u" : "absent", matching ? "yes" : "no")
                .asAdmin();
            GremlinToMatchStrategy.instance().apply(traversal);
            assertThat(TranslatorEquivalenceSupport.countBoundarySteps(traversal)).isEqualTo(1);
            var step =
                (com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep<?,
                    ?>) traversal.getStartStep();
            var template = step.getPlan();
            barrier.await(30, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(traversal.hasNext()).isTrue();
            var copy = step.getPlan();
            assertThat(copy).isNotSameAs(template);
            barrier.await(30, java.util.concurrent.TimeUnit.SECONDS);
            var names = traversal.toList().stream()
                .map(v -> ((org.apache.tinkerpop.gremlin.structure.Vertex) v)
                    .value("name"))
                .toList();
            assertThat(names).containsExactlyInAnyOrderElementsOf(matching
                ? List.of("both", "filter", "hop") : List.of("hop", "neither"));
            return new Object[] {template, copy, db, copy.getContext()};
          } finally {
            graph.tx().rollback();
          }
        }));
      }
      var a = futures.get(0).get(30, java.util.concurrent.TimeUnit.SECONDS);
      var b = futures.get(1).get(30, java.util.concurrent.TimeUnit.SECONDS);
      assertThat(a[0]).isSameAs(b[0]);
      assertThat(a[1]).isNotSameAs(b[1]);
      assertThat(a[2]).isNotSameAs(b[2]);
      assertThat(a[3]).isNotSameAs(b[3]);
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }
  }

  private com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded graphSession() {
    var tx = (com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction) graph.tx();
    tx.readWrite();
    return tx.getDatabaseSession();
  }

  private GraphTraversal<?, ?> cachedSelection(String uid, String slug) {
    return graph.traversal().V().hasLabel("Article")
        .or(__.out("category").has("uid", uid), __.has("slug", slug));
  }

  private GraphTraversal<?, ?> selected() {
    return graph.traversal().V().hasLabel("Article")
        .or(__.where(__.out("category").has("uid", "u")), __.has("slug", "yes"));
  }

  /** Filter-only, hop-only and both pass once each. Fan-out must not multiply incoming rows. */
  @Test
  public void mixedArmsPreserveRowsAndShaping() {
    seed();
    check("issue shape", Recognition.RECOGNIZED, this::selected);
    check("count after tree", Recognition.RECOGNIZED, () -> selected().count());
    check("dedup after tree", Recognition.RECOGNIZED, () -> selected().dedup());
    check("limit after tree", Recognition.RECOGNIZED,
        () -> selected().order().by("name").limit(2));
    check("existing dedup-before-limit restriction", Recognition.DECLINED,
        () -> selected().order().by("name").dedup().limit(2));
    support.withTranslator(true, () -> {
      assertThat(selected().values("name").toList()).containsExactlyInAnyOrder("filter", "hop",
          "both");
      assertThat(selected().count().next()).isEqualTo(3L);
      assertThat(selected().order().by("name").values("name").toList())
          .containsExactly("both", "filter", "hop");
      assertThat(selected().order().by("name").limit(2).values("name").toList())
          .containsExactly("both", "filter");
    });
    check("repeated upstream rows", Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Source").out("entry")
            .or(__.out("category"), __.has("slug", "yes")));
    support.withTranslator(true, () -> assertThat(graph.traversal().V().hasLabel("Source")
        .out("entry").or(__.out("category"), __.has("slug", "yes")).values("name")
        .toList()).containsExactlyInAnyOrder("both", "both", "filter", "filter", "hop", "hop"));
    check("dedup repeated upstream rows", Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Source").out("entry")
            .or(__.out("category"), __.has("slug", "yes")).dedup());
    support.withTranslator(true, () -> assertThat(graph.traversal().V().hasLabel("Source")
        .out("entry").or(__.out("category"), __.has("slug", "yes")).dedup()
        .values("name").toList()).containsExactlyInAnyOrder("both", "filter", "hop"));
  }

  /** Independent arms read one boundary, including canonical edge filters and detached NOT. */
  @Test
  public void sharedBoundaryEdgeFiltersAndNotArms() {
    seed();
    check("tree-bearing union arms and count", Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Article")
            .union(__.or(__.out("category"), __.has("slug", "yes")),
                __.or(__.out("category"), __.has("slug", "yes")))
            .count());
    check("two hop arms", Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Article")
            .or(__.out("category").has("uid", "u"), __.out("category")));
    var wrong = graph.addVertex(T.label, "Article", "name", "wrong", "slug", "no");
    wrong.addEdge("category", graph.addVertex(T.label, "Category", "uid", "u"), "weight", 1);
    graph.tx().commit();
    check("edge-filter arm", Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Article")
            .or(__.outE("category").has("weight", 2).inV(), __.has("slug", "yes")));
    support.withTranslator(true, () -> assertThat(graph.traversal().V().hasLabel("Article")
        .or(__.outE("category").has("weight", 2).inV(), __.has("slug", "yes"))
        .values("name").toList()).containsExactlyInAnyOrder("both", "filter", "hop"));
    check("NOT plus filter in arm", Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Article")
            .or(__.not(__.out("category")).has("slug", "no"), __.has("slug", "yes")));
    check("missing property", Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Article")
            .or(__.out("category"), __.hasNot("absent")));
    support.withTranslator(true, () -> assertThat(graph.traversal().V().hasLabel("Article")
        .or(__.not(__.out("category")).has("slug", "no"), __.has("slug", "yes"))
        .values("name").toList()).containsExactlyInAnyOrder("neither", "filter", "both"));
  }

  /** A terminal equality reads an already-bound outer vertex without publishing arm targets. */
  @Test
  public void terminalEqualityReadsOuterLabel() {
    var a = graph.addVertex(T.label, "Article", "name", "a");
    var b = graph.addVertex(T.label, "Article", "name", "b");
    a.addEdge("entry", b);
    b.addEdge("back", a);
    graph.tx().commit();
    check("outer label in probe", Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Article").as("start").out("entry")
            .or(__.out("back").where(P.eq("start")), __.has("name", "missing")));
    support.withTranslator(true, () -> assertThat(graph.traversal().V().hasLabel("Article")
        .as("start").out("entry")
        .or(__.out("back").where(P.eq("start")), __.has("name", "missing"))
        .values("name").toList()).containsExactly("b"));
  }

  /**
   * A boundary predicate stays inside its hop arm and excludes a hop-only row that the other arm
   * does not match. Captured sibling checks remain in the same conjunction.
   */
  @Test
  public void boundaryFilterBeforeHopIsAnArmConjunction() {
    seed();
    var excluded = graph.addVertex(T.label, "Article", "name", "wrong-slug", "slug", "no");
    excluded.addEdge("category", graph.addVertex(T.label, "Category"));
    graph.tx().commit();
    Supplier<GraphTraversal<?, ?>> traversal = () -> graph.traversal().V().hasLabel("Article")
        .or(__.has("slug", "yes").out("category"),
            __.has("name", P.within("hop", "both")));
    check("filter before hop", Recognition.RECOGNIZED, traversal);
    support.withTranslator(true, () -> assertThat(traversal.get().values("name").toList())
        .doesNotContain("wrong-slug")
        .containsExactlyInAnyOrder("hop", "both"));
    check("check and filter before hop", Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Article")
            .or(__.not(__.out("missing")).has("slug", "yes").out("category"),
                __.has("name", "hop")));
  }

  /** Nested AND operands and independent OR trees retain their grouping on cache replay. */
  @Test
  public void connectiveGroupingHasDistinctKeysAndWarmCacheAnswers() {
    seed();
    Supplier<GraphTraversal<?, ?>> first = () -> graph.traversal().V().hasLabel("Article")
        .or(__.and(__.out("category"), __.has("slug", "yes")),
            __.has("name", "hop"), __.has("name", "filter"));
    Supplier<GraphTraversal<?, ?>> second = () -> graph.traversal().V().hasLabel("Article")
        .or(__.and(__.out("category"), __.has("slug", "yes"), __.has("name", "hop")),
            __.has("name", "filter"));
    assertCacheSeparation(first, second, List.of("both", "hop", "filter"), List.of("filter"));
    Supplier<GraphTraversal<?, ?>> stacked = () -> graph.traversal().V().hasLabel("Article")
        .or(__.out("category"), __.has("slug", "yes"))
        .or(__.out("category"), __.has("slug", "no"));
    check("independent OR checks combine conjunctively", Recognition.RECOGNIZED, stacked);
    support.withTranslator(true, () -> assertThat(stacked.get().values("name").toList())
        .containsExactlyInAnyOrder("both", "hop"));
  }

  /** Pure-filter AND operands require child boundaries even without a boolean-check tree. */
  @Test
  public void pureFilterGroupingHasDistinctKeysAndWarmCacheAnswers() {
    seed();
    // The barrier keeps the pure-filter AND intact under InlineFilterStrategy.
    Supplier<GraphTraversal<?, ?>> first = () -> graph.traversal().V().hasLabel("Article")
        .or(__.and(__.has("slug", "yes").barrier(), __.has("name", "both")),
            __.has("name", "hop"), __.has("name", "filter"));
    Supplier<GraphTraversal<?, ?>> second = () -> graph.traversal().V().hasLabel("Article")
        .or(__.and(__.has("slug", "yes").barrier(), __.has("name", "both"),
            __.has("name", "hop")), __.has("name", "filter"));
    assertCacheSeparation(first, second, List.of("both", "hop", "filter"), List.of("filter"));
  }

  private void assertCacheSeparation(Supplier<GraphTraversal<?, ?>> first,
      Supplier<GraphTraversal<?, ?>> second, List<String> firstNames, List<String> secondNames) {
    check("first grouping", Recognition.RECOGNIZED, first);
    check("second grouping", Recognition.RECOGNIZED, second);
    support.withTranslator(false, () -> {
      var a = first.get().asAdmin();
      var b = second.get().asAdmin();
      a.applyStrategies();
      b.applyStrategies();
      var ka = GremlinStepWalker.extractShape(a, graphSession());
      var kb = GremlinStepWalker.extractShape(b, graphSession());
      assertThat(ka.complete()).isTrue();
      assertThat(kb.complete()).isTrue();
      assertThat(ka.key()).isNotEqualTo(kb.key());
      assertThat(ka.key().replaceAll("children:1:[0-9]+|6:armEnd", ""))
          .isEqualTo(kb.key().replaceAll("children:1:[0-9]+|6:armEnd", ""));
    });
    support.withTranslator(true, () -> {
      var cache = GremlinPlanCache.instance(graphSession());
      cache.invalidate();
      assertThat(first.get().values("name").toList())
          .containsExactlyInAnyOrderElementsOf(firstNames);
      assertThat(second.get().values("name").toList())
          .containsExactlyInAnyOrderElementsOf(secondNames);
      long hits = cache.getTranslationHits();
      assertThat(first.get().values("name").toList())
          .containsExactlyInAnyOrderElementsOf(firstNames);
      assertThat(second.get().values("name").toList())
          .containsExactlyInAnyOrderElementsOf(secondNames);
      assertThat(cache.getTranslationHits()).isEqualTo(hits + 2);
    });
  }

  private void assertWrapper(Class<?> wrapper, Supplier<GraphTraversal<?, ?>> supplier) {
    support.withTranslator(false, () -> {
      var admin = supplier.get().asAdmin();
      admin.applyStrategies();
      assertThat(admin.getSteps().stream().anyMatch(wrapper::isInstance)).isTrue();
    });
  }

  /** Positive wrappers forward complete trees. Unsupported continuation must not erase a tree. */
  @Test
  public void captureBoundariesForwardOrDecline() {
    seed();
    Supplier<GraphTraversal<?, ?>> where = () -> graph.traversal()
        .withoutStrategies(InlineFilterStrategy.class).V().hasLabel("Article")
        .where(__.or(__.out("category"), __.has("slug", "yes")));
    Supplier<GraphTraversal<?, ?>> filter = () -> graph.traversal()
        .withoutStrategies(InlineFilterStrategy.class).V().hasLabel("Article")
        .filter(__.or(__.out("category"), __.has("slug", "yes")));
    Supplier<GraphTraversal<?, ?>> and = () -> graph.traversal()
        .withoutStrategies(InlineFilterStrategy.class).V().hasLabel("Article")
        .and(__.or(__.out("category"), __.has("slug", "yes")), __.has("slug", "no"));
    assertWrapper(TraversalFilterStep.class, where);
    assertWrapper(TraversalFilterStep.class, filter);
    assertWrapper(AndStep.class, and);
    check("where forwards tree", Recognition.RECOGNIZED, where);
    check("filter forwards tree", Recognition.RECOGNIZED, filter);
    check("and forwards complete child", Recognition.RECOGNIZED, and);
    check("wrapped tree before outer hop", Recognition.DECLINED,
        () -> graph.traversal().withoutStrategies(InlineFilterStrategy.class)
            .V().hasLabel("Article")
            .where(__.or(__.out("category"), __.has("slug", "yes"))).out("category"));
    check("tree before positive hop", Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Article")
            .where(__.or(__.out("category"), __.has("slug", "yes")).out("category")));
    check("top-level hop after tree", Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Article")
            .or(__.out("category"), __.has("slug", "yes")).out("category"));
    check("union after tree", Recognition.DECLINED,
        () -> selected().union(__.identity(), __.identity()));
    check("nested OR stays native", Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Article")
            .or(__.or(__.out("category"), __.has("slug", "yes")), __.has("name", "hop")));
    check("NOT over tree stays native", Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Article")
            .not(__.or(__.out("category"), __.has("slug", "yes"))));
  }
}
