package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.Cardinality;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.Recognition;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchBooleanCheck;
import java.util.List;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.AndStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.NotStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.OrStep;
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
      // Removing all child delimiters reconstructs the ambiguous, unscoped representation.
      var childTokens = "children:1:[0-9]+|6:armEnd|10:localChild|11:globalChild|8:childEnd";
      assertThat(ka.key().replaceAll(childTokens, ""))
          .isEqualTo(kb.key().replaceAll(childTokens, ""));
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

  /** Negation preserves duplicates and applies a total order before limiting surviving rows. */
  @Test
  public void negatedTreePreservesMultiplicityAndShaping() {
    seed();
    Supplier<GraphTraversal<?, ?>> negated = () -> graph.traversal().V().hasLabel("Article")
        .not(__.or(__.out("category"), __.has("slug", "yes")));
    check("NOT OR", Recognition.RECOGNIZED, negated);
    check("NOT OR count", Recognition.RECOGNIZED, () -> negated.get().count());
    check("NOT OR ordered limit", Recognition.RECOGNIZED,
        () -> negated.get().order().by("name").limit(1));
    Supplier<GraphTraversal<?, ?>> repeated = () -> graph.traversal().V().hasLabel("Source")
        .out("entry").not(__.or(__.out("category"), __.has("slug", "yes")));
    check("repeated NOT OR", Recognition.RECOGNIZED, repeated);
    check("dedup NOT OR", Recognition.RECOGNIZED, () -> repeated.get().dedup());
    support.withTranslator(true, () -> {
      assertThat(negated.get().values("name").toList()).containsExactly("neither");
      assertThat(negated.get().order().by("name").limit(1).values("name").toList())
          .containsExactly("neither");
      assertThat(repeated.get().values("name").toList())
          .containsExactlyInAnyOrder("neither", "neither");
      assertThat(repeated.get().dedup().values("name").toList()).containsExactly("neither");
    });
    // Insert survivors out of name order so omitting ORDER BY changes the limited answer.
    for (var name : List.of("z-last", "a-first", "m-middle")) {
      graph.addVertex(T.label, "Article", "name", name, "slug", "no");
    }
    graph.tx().commit();
    for (boolean enabled : List.of(false, true)) {
      support.withTranslator(enabled, () -> assertThat(negated.get().order().by("name")
          .limit(3).values("name").toList()).containsExactly("a-first", "m-middle", "neither"));
    }
    check("multiple survivors ordered limit", Recognition.RECOGNIZED,
        () -> negated.get().order().by("name").limit(3));
  }

  /** Every NOT operand includes its boundary filters, hop and detached negative checks. */
  @Test
  public void negationScopesTheCompleteChildAndNestedArms() {
    seed();
    Supplier<GraphTraversal<?, ?>> complete = () -> graph.traversal()
        .withoutStrategies(InlineFilterStrategy.class).V().hasLabel("Article")
        .not(__.has("slug", "yes")
            .or(__.out("category"), __.has("name", "neither")));
    check("complete child negation", Recognition.RECOGNIZED, complete);
    support.withTranslator(true, () -> assertThat(complete.get().values("name").toList())
        .containsExactlyInAnyOrder("filter", "hop", "neither"));
    Supplier<GraphTraversal<?, ?>> negativeArm = () -> graph.traversal().V().hasLabel("Article")
        .not(__.or(__.not(__.out("category")), __.has("slug", "yes")));
    check("detached NOT under NOT OR", Recognition.RECOGNIZED, negativeArm);
    support.withTranslator(true, () -> assertThat(negativeArm.get().values("name").toList())
        .containsExactly("hop"));
    Supplier<GraphTraversal<?, ?>> filteredHop = () -> graph.traversal().V().hasLabel("Article")
        .not(__.or(__.has("slug", "yes").out("category"), __.has("name", "filter")));
    check("complete hop arm negation", Recognition.RECOGNIZED, filteredHop);
    support.withTranslator(true, () -> assertThat(filteredHop.get().values("name").toList())
        .containsExactlyInAnyOrder("hop", "neither"));
    Supplier<GraphTraversal<?, ?>> missing = () -> graph.traversal().V().hasLabel("Article")
        .not(__.or(__.out("category"), __.has("absent", "x")));
    check("missing property under NOT", Recognition.RECOGNIZED, missing);
    support.withTranslator(true, () -> assertThat(missing.get().values("name").toList())
        .containsExactlyInAnyOrder("filter", "neither"));
    Supplier<GraphTraversal<?, ?>> nested = () -> graph.traversal()
        .withoutStrategies(InlineFilterStrategy.class).V().hasLabel("Article")
        .or(__.and(__.or(__.out("category"), __.has("slug", "yes")),
            __.or(__.not(__.or(__.out("category"), __.has("name", "neither"))),
                __.has("name", "hop"))),
            __.has("name", "neither"));
    check("multiple nested levels", Recognition.RECOGNIZED, nested);
    support.withTranslator(true, () -> assertThat(nested.get().values("name").toList())
        .containsExactlyInAnyOrder("filter", "hop", "neither"));
    check("plain nested exists remains native", Recognition.DECLINED,
        () -> graph.traversal().withoutStrategies(InlineFilterStrategy.class)
            .V().hasLabel("Article").not(__.where(__.out("missing"))));
    check("hop then tree remains native", Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Article")
            .not(__.out("category").or(__.out("missing"), __.has("uid", "u"))));
    Supplier<GraphTraversal<?, ?>> boundary = () -> graph.traversal()
        .withoutStrategies(InlineFilterStrategy.class).V().hasLabel("Article")
        .not(__.or(__.has("name", "hop"), __.has("name", "both")));
    support.withTranslator(false, () -> {
      var admin = boundary.get().asAdmin();
      admin.applyStrategies();
      var not = (NotStep<?>) admin.getSteps().stream().filter(NotStep.class::isInstance)
          .findFirst().orElseThrow();
      assertThat(not.getLocalChildren().getFirst().getSteps().stream()
          .anyMatch(OrStep.class::isInstance)).isTrue();
      var walked = GremlinStepWalker.production().walk(boundary.get().asAdmin());
      assertThat(walked).isNotNull();
      assertThat(walked.inputs().booleanCheck()).isNull();
    });
    check("pure boundary NOT OR", Recognition.RECOGNIZED, boundary);
    support.withTranslator(true, () -> assertThat(boundary.get().values("name").toList())
        .containsExactlyInAnyOrder("filter", "neither"));
  }

  /** NOT child boundaries distinguish identical step sequences with different negation scopes. */
  @Test
  public void negationPlacementSeparatesBothCacheKeysAndWarmAnswers() {
    seed();
    Supplier<GraphTraversal<?, ?>> inside = () -> graph.traversal()
        .withoutStrategies(InlineFilterStrategy.class).V().hasLabel("Article")
        .not(__.or(__.out("category"), __.has("name", "filter")).has("slug", "yes"));
    Supplier<GraphTraversal<?, ?>> outside = () -> graph.traversal()
        .withoutStrategies(InlineFilterStrategy.class).V().hasLabel("Article")
        .not(__.or(__.out("category"), __.has("name", "filter"))).has("slug", "yes");
    // Outside is empty for these operands. Use explicit lists rather than NON_EMPTY here.
    support.assertEquivalent("NOT placement outside", Recognition.RECOGNIZED,
        Cardinality.MAY_BE_EMPTY,
        TranslatorEquivalenceSupport::sortedIdsOrValues, outside);
    check("NOT placement inside", Recognition.RECOGNIZED, inside);
    support.withTranslator(false, () -> {
      // Check the unoptimised sequence too. Filter ranking can move the outside has to the start.
      assertThat(GremlinStepWalker.extractShape(inside.get().asAdmin(), graphSession()).key())
          .isNotEqualTo(
              GremlinStepWalker.extractShape(outside.get().asAdmin(), graphSession()).key());
      var a = inside.get().asAdmin();
      var b = outside.get().asAdmin();
      a.applyStrategies();
      b.applyStrategies();
      assertThat(GremlinStepWalker.extractShape(a, graphSession()).key())
          .isNotEqualTo(GremlinStepWalker.extractShape(b, graphSession()).key());
      var wa = GremlinStepWalker.production().walk(inside.get().asAdmin());
      var wb = GremlinStepWalker.production().walk(outside.get().asAdmin());
      assertThat(wa).isNotNull();
      assertThat(wb).isNotNull();
      assertThat(GremlinPlanFingerprint.fingerprint(wa.inputs(), wa.shaping()))
          .isNotEqualTo(GremlinPlanFingerprint.fingerprint(wb.inputs(), wb.shaping()));
      var flat = GremlinStepWalker.production().walk(graph.traversal().V().hasLabel("Article")
          .not(__.out("category")).asAdmin());
      assertThat(flat).isNotNull();
      assertThat(flat.inputs().booleanCheck()).isNull();
      assertThat(flat.inputs().notMatchExpressions()).hasSize(1);
      // Pin the fingerprint of flat not(out()) with one negative expression and no boolean tree.
      assertThat(GremlinPlanFingerprint.fingerprint(flat.inputs(), flat.shaping()))
          .isEqualTo("P:7:$g2m_v07:Article0;E:;F:;M:;N:46:{as: $g2m_v0}.out(\"category\")"
              + "{as: $g2m_anon_0};X:;R:7:$g2m_v0+7:$g2m_v0-;G:;O:;U:;L:;S:;D:false;"
              + "RM:0000;BS:00000000;PK:;AP:;MO:;RI:;EK:;RD:;OS:;LS:");
    });
    support.withTranslator(true, () -> {
      var cache = GremlinPlanCache.instance(graphSession());
      cache.invalidate();
      for (int i = 0; i < 2; i++) {
        long hits = cache.getTranslationHits();
        assertThat(inside.get().values("name").toList())
            .containsExactlyInAnyOrder("hop", "neither");
        assertThat(outside.get().values("name").toList()).isEmpty();
        if (i == 1) {
          assertThat(cache.getTranslationHits()).isEqualTo(hits + 2);
        }
      }
    });
  }

  /** OR, AND and each NOT depth have distinct plan keys and replay fresh operands on real hits. */
  @Test
  public void booleanOperatorsHaveDistinctKeysAndFreshCacheHits() {
    seed();
    support.withTranslator(true, () -> {
      var cache = GremlinPlanCache.instance(graphSession());
      cache.invalidate();
      var keys = new java.util.HashSet<String>();
      for (int operator = 0; operator < 4; operator++) {
        for (int binding = 0; binding < 2; binding++) {
          var traversal = operatorSelection(operator, binding == 0 ? "yes" : "no").asAdmin();
          var walked = GremlinStepWalker.production().walk(traversal);
          assertThat(walked).isNotNull();
          var key = GremlinPlanFingerprint.fingerprint(walked.inputs(), walked.shaping());
          if (binding == 0) {
            assertThat(keys.add(key)).isTrue();
          } else {
            assertThat(keys).contains(key);
          }
          long hits = cache.getTranslationHits();
          GremlinToMatchStrategy.instance().apply(traversal);
          assertThat(TranslatorEquivalenceSupport.countBoundarySteps(traversal)).isEqualTo(1);
          var names = traversal.toList().stream()
              .map(v -> ((org.apache.tinkerpop.gremlin.structure.Vertex) v).value("name"))
              .toList();
          List<String> expected = switch (operator) {
            case 0, 2 -> binding == 0 ? List.of("filter", "hop", "both")
                : List.of("hop", "both", "neither");
            case 1 -> binding == 0 ? List.of("neither") : List.of("filter");
            default -> binding == 0 ? List.of("both") : List.of("hop");
          };
          assertThat(names).containsExactlyInAnyOrderElementsOf(expected);
          if (binding == 1) {
            assertThat(cache.getTranslationHits()).isEqualTo(hits + 1);
          }
        }
      }
    });
  }

  private GraphTraversal<?, ?> operatorSelection(int operator, String slug) {
    var traversal = graph.traversal().withoutStrategies(InlineFilterStrategy.class)
        .V().hasLabel("Article");
    return switch (operator) {
      case 0 -> traversal.or(__.out("category"), __.has("slug", slug));
      case 1 -> traversal.not(__.or(__.out("category"), __.has("slug", slug)));
      case 2 -> traversal.not(__.not(__.or(__.out("category"), __.has("slug", slug))));
      default -> traversal.and(__.out("category"), __.has("slug", slug));
    };
  }

  /** Mandatory AND siblings retain scan filters, flat checks and a whole OR tree. */
  @Test
  public void treeBearingAndKeepsMandatoryPlanInputs() {
    seed();
    Supplier<GraphTraversal<?, ?>> traversal = () -> graph.traversal()
        .withoutStrategies(InlineFilterStrategy.class).V().hasLabel("Article")
        .and(__.or(__.out("category"), __.has("slug", "yes")),
            __.has("name", "hop"), __.out("category"), __.not(__.out("missing")));
    assertWrapper(AndStep.class, traversal);
    support.withTranslator(false, () -> {
      var walked = GremlinStepWalker.production().walk(traversal.get().asAdmin());
      assertThat(walked).isNotNull();
      var inputs = walked.inputs();
      assertThat(inputs.aliasFilters()).containsKey("$g2m_v0");
      assertThat(inputs.aliasFilters().get("$g2m_v0").toString()).contains("name");
      assertThat(inputs.existsMatchExpressions()).hasSize(1);
      assertThat(inputs.notMatchExpressions()).hasSize(1);
      assertThat(inputs.booleanCheck()).isInstanceOf(MatchBooleanCheck.Or.class);
    });
    check("mandatory AND siblings", Recognition.RECOGNIZED, traversal);
    support.withTranslator(true, () -> assertThat(traversal.get().values("name").toList())
        .containsExactly("hop"));
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
    check("nested OR", Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Article")
            .or(__.or(__.out("category"), __.has("slug", "yes")), __.has("name", "hop")));
    check("NOT over tree", Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Article")
            .not(__.or(__.out("category"), __.has("slug", "yes"))));
  }
}
