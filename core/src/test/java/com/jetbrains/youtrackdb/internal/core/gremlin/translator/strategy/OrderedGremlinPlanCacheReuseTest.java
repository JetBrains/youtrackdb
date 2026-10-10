package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.CoreMetrics;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.MetricsRegistry;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.Ratio;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.SessionPoolImpl;
import com.jetbrains.youtrackdb.internal.core.db.SharedContext;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.MultiPlanMatchStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.IndexOrderedEdgeStep;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/** Actual ordered cache engagement, bound predicates, and private execution across graph sessions. */
public class OrderedGremlinPlanCacheReuseTest extends GraphBaseTest {

  /** A complete warm single-plan hit skips both seams and rebinds alternating source and target values. */
  @Test
  public void warmSinglePlanSkipsWalkerAndPlannerAndMatchesNativeForAlternatingBindings()
      throws Exception {
    seed();
    var db = graphSession(graph);
    var cache = GremlinPlanCache.instance(db);
    var walks = new AtomicInteger();
    var builds = new AtomicInteger();
    var strategy = new GremlinToMatchStrategy(t -> {
      walks.incrementAndGet();
      return GremlinToMatchTranslator.translate(t);
    }, (s, translation, scope) -> {
      builds.incrementAndGet();
      return GremlinToMatchStrategy.buildPlan(s, translation, scope);
    }, true);
    var hits = cache.getTranslationHits();
    var registry = mock(MetricsRegistry.class);
    var successRatio = mock(Ratio.class);
    var declineRatio = mock(Ratio.class);
    var errorRatio = mock(Ratio.class);
    when(registry.globalMetric(CoreMetrics.GREMLIN_TRANSLATION_SUCCESS_RATIO))
        .thenReturn(successRatio);
    when(registry.globalMetric(CoreMetrics.GREMLIN_TRANSLATION_DECLINE_RATIO))
        .thenReturn(declineRatio);
    when(registry.globalMetric(CoreMetrics.GREMLIN_TRANSLATION_ERROR_RATIO))
        .thenReturn(errorRatio);
    var metrics = new GremlinTranslationMetrics(registry);
    // This fixture owns its database. Replace only its holder, never the engine-global registry.
    var metricsField = SharedContext.class.getDeclaredField("gremlinTranslationMetrics");
    metricsField.setAccessible(true);
    metricsField.set(db.getSharedContext(), metrics);
    InternalExecutionPlan template = null;
    for (var bindings : List.of(new int[] {1, 2}, new int[] {2, 8}, new int[] {1, 2})) {
      var traversal = ordered(graph, bindings[0], bindings[1]);
      var expected = nativeRows(graph, bindings[0], bindings[1]);
      strategy.apply(traversal.asAdmin());
      var boundary = boundary(traversal);
      var closed = boundary.getPlan();
      assertThat(closed.getSteps()).anyMatch(IndexOrderedEdgeStep.class::isInstance);
      if (template == null) {
        template = closed;
      } else {
        assertThat(closed).isSameAs(template);
      }
      assertThat(names(traversal.toList())).isEqualTo(expected);
      assertThat(boundary.getPlan()).isNotSameAs(closed);
      assertThat(boundary.getPlan().getContext().getParent()).isNull();
      assertThat(closed.getContext().hasSystemVariable(CommandContext.VAR_INDEX_ORDERED_PRE_SORTED))
          .isFalse();
      assertThat(orderedStep(closed).getChosenRuntimePath()).isNull();
    }
    assertThat(walks.get()).isEqualTo(1);
    assertThat(builds.get()).isEqualTo(1);
    assertThat(cache.getTranslationHits()).isEqualTo(hits + 2);
    assertThat(metrics.getSuccesses()).isEqualTo(3);
    assertThat(metrics.getAttempts()).isEqualTo(3);
    assertThat(metrics.getDeclines()).isZero();
    assertThat(metrics.getErrors()).isZero();
    assertThat(metrics.topDeclinedShapes(10)).isEmpty();
    verify(successRatio, times(3)).record(1, 1);
    verify(declineRatio, times(3)).record(0, 1);
    verify(errorRatio, times(3)).record(0, 1);
  }

  /** Multi-plan calls rerun the walker but both eligible ordered children hit physical templates. */
  @Test
  public void warmMultiPlanWalksAgainAndHitsEachOrderedChild() {
    seed();
    var db = graphSession(graph);
    var cache = GremlinPlanCache.instance(db);
    var walks = new AtomicInteger();
    var strategy = new GremlinToMatchStrategy(t -> {
      walks.incrementAndGet();
      return GremlinToMatchTranslator.translate(t);
    }, GremlinToMatchStrategy::buildPlan, true);
    db.getConfiguration().setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED,
        false);
    List<String> expected;
    try {
      expected = names(orderedUnion().toList());
    } finally {
      enable(graph);
    }
    assertThat(expected).containsExactly("s1-0", "s1-1", "s1-2", "s1-3",
        "s1-19", "s1-18", "s1-17", "s1-16");
    for (var call = 0; call < 2; call++) {
      var traversal = orderedUnion();
      var key = GremlinStepWalker.extractShape(traversal.asAdmin(), db).key();
      var hits = cache.getHits();
      strategy.apply(traversal.asAdmin());
      assertThat(traversal.asAdmin().getStartStep()).isInstanceOf(MultiPlanMatchStep.class);
      var boundary = (MultiPlanMatchStep<?, ?>) traversal.asAdmin().getStartStep();
      for (var child : boundary.getPlans()) {
        assertThat(child.getContext().getParent()).isNull();
        assertThat(child.getSteps()).anyMatch(IndexOrderedEdgeStep.class::isInstance);
      }
      assertThat(names(traversal.toList())).containsExactlyElementsOf(expected);
      if (call == 1) {
        assertThat(cache.getHits()).isEqualTo(hits + 2);
      }
      assertThat(cache.containsTranslation(key)).isFalse();
    }
    assertThat(walks.get()).isEqualTo(2);
  }

  /** Guarded ordered plans share warm templates but never reuse another literal type's guard. */
  @Test
  public void guardedOrderedPlanHitsAndKeepsLiteralTypesSeparate() {
    seed();
    var db = graphSession(graph);
    var cache = GremlinPlanCache.instance(db);
    var walks = new AtomicInteger();
    var builds = new AtomicInteger();
    var strategy = new GremlinToMatchStrategy(t -> {
      walks.incrementAndGet();
      return GremlinToMatchTranslator.translate(t);
    }, (s, translation, scope) -> {
      builds.incrementAndGet();
      return GremlinToMatchStrategy.buildPlan(s, translation, scope);
    }, true);
    var hits = cache.getTranslationHits();
    var keys = new ArrayList<String>();
    var templates = new ArrayList<InternalExecutionPlan>();
    for (var minimum : List.of(0, "0", 4, "1")) {
      var traversal = guardedOrdered(minimum);
      var extraction = GremlinStepWalker.extractShape(traversal.asAdmin(), db);
      assertThat(extraction.complete()).isTrue();
      var translation = GremlinToMatchTranslator.translate(traversal.asAdmin());
      assertThat(translation).isNotNull();
      assertThat(translation.inputs().aliasFilters().values()).anyMatch(f -> !f.isCacheable(db));
      var fingerprint =
          GremlinPlanFingerprint.fingerprint(translation.inputs(), translation.shaping());
      db.getConfiguration().setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED,
          false);
      List<String> expected;
      try {
        expected = names(guardedOrdered(minimum).toList());
      } finally {
        enable(graph);
      }
      strategy.apply(traversal.asAdmin());
      var template = boundary(traversal).getPlan();
      assertThat(template.getSteps()).anyMatch(IndexOrderedEdgeStep.class::isInstance);
      assertThat(cache.peekStored(fingerprint)).isSameAs(template);
      assertThat(cache.containsTranslation(extraction.key())).isTrue();
      assertThat(names(traversal.toList())).containsExactlyElementsOf(expected);
      assertThat(boundary(traversal).getPlan()).isNotSameAs(template);
      if (minimum instanceof Integer value) {
        assertThat(expected).containsExactlyElementsOf(
            java.util.stream.IntStream.range(value, value + 8).mapToObj(i -> "s1-" + i).toList());
      } else {
        assertThat(expected).isEmpty();
      }
      keys.add(extraction.key());
      templates.add(template);
    }
    assertThat(keys.get(0)).isEqualTo(keys.get(2)).isNotEqualTo(keys.get(1));
    assertThat(keys.get(1)).isEqualTo(keys.get(3));
    assertThat(templates.get(0)).isSameAs(templates.get(2)).isNotSameAs(templates.get(1));
    assertThat(templates.get(1)).isSameAs(templates.get(3));
    assertThat(cache.getTranslationHits()).isEqualTo(hits + 2);
    assertThat(walks.get()).isEqualTo(2);
    assertThat(builds.get()).isEqualTo(2);
  }

  /** A guarded union child and its sibling both publish and hit independent physical templates. */
  @Test
  public void guardedUnionChildKeepsBothPhysicalCacheRoutes() {
    seed();
    var db = graphSession(graph);
    var cache = GremlinPlanCache.instance(db);
    db.getConfiguration().setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED,
        false);
    List<String> expected;
    try {
      expected = names(guardedUnion().toList());
    } finally {
      enable(graph);
    }
    assertThat(expected).containsExactly("s1-0", "s1-1", "s1-2", "s1-3", "s1-4", "s1-5",
        "s1-6", "s1-7", "s1-0", "s1-1", "s1-2", "s1-3", "s1-4", "s1-5", "s1-6", "s1-7");
    for (var call = 0; call < 2; call++) {
      var traversal = guardedUnion();
      var key = GremlinStepWalker.extractShape(traversal.asAdmin(), db).key();
      var translation = GremlinToMatchTranslator.translate(traversal.asAdmin());
      assertThat(translation).isNotNull();
      assertThat(translation.childPlans().getFirst().inputs().aliasFilters().values())
          .anyMatch(f -> !f.isCacheable(db));
      var hits = cache.getHits();
      var misses = cache.getMisses();
      GremlinToMatchStrategy.instance().apply(traversal.asAdmin());
      assertThat(traversal.asAdmin().getStartStep()).isInstanceOf(MultiPlanMatchStep.class);
      assertThat(names(traversal.toList())).containsExactlyElementsOf(expected);
      assertThat(cache.getHits()).isEqualTo(hits + 2 * call);
      assertThat(cache.getMisses()).isEqualTo(misses + 2 * (1 - call));
      for (var child : translation.childPlans()) {
        var fingerprint = GremlinPlanFingerprint.fingerprint(child.inputs(), translation.shaping());
        assertThat(cache.peekStored(fingerprint)).isNotNull();
      }
      assertThat(cache.containsTranslation(key)).isFalse();
    }
  }

  private GraphTraversal<Vertex, Vertex> guardedOrdered(Object minimum) {
    return graph.traversal().V().hasLabel("CacheSrc").has("id", 1)
        .out("CacheLink").hasLabel("CacheTgt").has("tag", P.gte(minimum))
        .order().by("score").limit(8);
  }

  private GraphTraversal<Vertex, Vertex> guardedUnion() {
    return graph.traversal().V().hasLabel("CacheSrc").has("id", 1).union(
        __.out("CacheLink").hasLabel("CacheTgt").has("tag", P.gte(0))
            .order().by("score").limit(8),
        __.out("CacheLink").hasLabel("CacheTgt").order().by("score").limit(8));
  }

  /** A RID union arm stays private while its RID-free sibling publishes and hits on the next call. */
  @Test
  public void ridUnionArmDoesNotDisableSiblingPhysicalCache() {
    seed();
    var db = graphSession(graph);
    var rid = graph.traversal().V().hasLabel("CacheTgt").has("name", "s1-3").next().id();
    var cache = GremlinPlanCache.instance(db);
    cache.invalidate();
    for (var call = 0; call < 2; call++) {
      var traversal = graph.traversal().V().hasLabel("CacheSrc").has("id", 1).union(
          __.out("CacheLink").hasId(rid),
          __.out("CacheLink").hasLabel("CacheTgt").order().by("score").limit(4));
      var key = GremlinStepWalker.extractShape(traversal.asAdmin(), db).key();
      var translation = GremlinToMatchTranslator.translate(traversal.asAdmin());
      assertThat(translation).isNotNull();
      var privateChild = translation.childPlans().get(0);
      var eligibleChild = translation.childPlans().get(1);
      assertThat(privateChild.cacheEligible()).isFalse();
      assertThat(eligibleChild.cacheEligible()).isTrue();
      var privateKey =
          GremlinPlanFingerprint.fingerprint(privateChild.inputs(), translation.shaping());
      var eligibleKey =
          GremlinPlanFingerprint.fingerprint(eligibleChild.inputs(), translation.shaping());
      InternalExecutionPlan injected = null;
      if (call == 1) {
        injected = GremlinPlanCache.put(privateKey, cache.peekStored(eligibleKey), db,
            cache.getGeneration());
        assertThat(injected).isNotNull();
      }
      var hits = cache.getHits();
      var misses = cache.getMisses();
      GremlinToMatchStrategy.instance().apply(traversal.asAdmin());
      assertThat(traversal.asAdmin().getStartStep()).isInstanceOf(MultiPlanMatchStep.class);
      assertThat(names(traversal.toList()))
          .containsExactly("s1-3", "s1-0", "s1-1", "s1-2", "s1-3");
      assertThat(cache.getHits()).isEqualTo(hits + call);
      assertThat(cache.getMisses()).isEqualTo(misses + 1 - call);
      assertThat(cache.peekStored(privateKey)).isSameAs(injected);
      assertThat(cache.peekStored(eligibleKey)).isNotNull();
      assertThat(cache.containsTranslation(key)).isFalse();
    }
  }

  /** Overlapping sessions copy one template, close one cursor, and preserve the other's state. */
  @Test
  public void overlappingBoundaryExecutionsKeepFiltersContextsAndResourcesPrivate()
      throws Exception {
    seed();
    var warm = ordered(graph, 1, 0);
    GremlinToMatchStrategy.instance().apply(warm.asAdmin());
    var template = boundary(warm).getPlan();
    warm.toList();
    try (var other = independentGraph()) {
      enable(other);
      var firstExpected = nativeRows(graph, 1, 0);
      var secondExpected = nativeRows(other, 2, 5);
      var first = ordered(graph, 1, 0);
      var second = ordered(other, 2, 5);
      GremlinToMatchStrategy.instance().apply(first.asAdmin());
      GremlinToMatchStrategy.instance().apply(second.asAdmin());
      assertThat(boundary(first).getPlan()).isSameAs(template);
      assertThat(boundary(second).getPlan()).isSameAs(template);
      var firstRows = new ArrayList<String>();
      firstRows.add(first.next().value("name"));
      var secondRows = new ArrayList<String>();
      secondRows.add(second.next().value("name"));
      var firstPlan = boundary(first).getPlan();
      var secondPlan = boundary(second).getPlan();
      assertThat(firstPlan).isNotSameAs(secondPlan).isNotSameAs(template);
      assertThat(firstPlan.getContext()).isNotSameAs(secondPlan.getContext());
      assertThat(firstPlan.getContext().getParent()).isNull();
      assertThat(secondPlan.getContext().getParent()).isNull();
      for (var field : List.of("targetFilter", "comparisonItem")) {
        assertThat(field(orderedStep(firstPlan), field))
            .isNotSameAs(field(orderedStep(secondPlan), field))
            .isNotSameAs(field(orderedStep(template), field));
      }
      firstRows.addAll(names(first.toList()));
      assertThat(firstRows).isEqualTo(firstExpected);
      first.close();
      secondRows.addAll(names(second.toList()));
      assertThat(secondRows).isEqualTo(secondExpected);
      second.close();
      assertThat(orderedStep(template).getChosenRuntimePath()).isNull();
      assertThat(
          template.getContext().hasSystemVariable(CommandContext.VAR_INDEX_ORDERED_PRE_SORTED))
          .isFalse();
      assertThat(names(ordered(graph, 1, 0).toList())).isEqualTo(firstExpected);
      graphSession(other);
      other.tx().rollback();
    } finally {
      graphSession(graph);
    }
  }

  /** Two worker threads run warm boundary copies with separate sessions and different bindings. */
  @Test
  public void concurrentWarmCopiesHaveNoCrossSessionStateBleed() throws Exception {
    seed();
    var cold = ordered(graph, 1, 0);
    GremlinToMatchStrategy.instance().apply(cold.asAdmin());
    var template = boundary(cold).getPlan();
    cold.toList();
    var firstExpected = nativeRows(graph, 1, 0);
    var secondExpected = nativeRows(graph, 2, 6);
    var ready = new CountDownLatch(2);
    var go = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var futures = new ArrayList<java.util.concurrent.Future<List<String>>>();
      for (int source : List.of(1, 2)) {
        futures.add(executor.submit(() -> {
          try (var ownGraph = independentGraph()) {
            enable(ownGraph);
            var traversal = ordered(ownGraph, source, source == 1 ? 0 : 6);
            GremlinToMatchStrategy.instance().apply(traversal.asAdmin());
            assertThat(boundary(traversal).getPlan()).isSameAs(template);
            ready.countDown();
            assertThat(go.await(10, TimeUnit.SECONDS)).isTrue();
            return names(traversal.toList());
          }
        }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      assertThat(futures.get(0).get(30, TimeUnit.SECONDS)).isEqualTo(firstExpected);
      assertThat(futures.get(1).get(30, TimeUnit.SECONDS)).isEqualTo(secondExpected);
    } finally {
      go.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
      graphSession(graph);
    }
  }

  /** Committed templates serve another session's pending indexed creates, deletes, and RID ties. */
  @Test
  public void committedTemplateHitsWithPendingWritesInAnotherSession() {
    pendingWrites(false);
  }

  /** Templates built with pending indexed writes do not leak provisional records to clean sessions. */
  @Test
  public void pendingWriteTemplateHitsInCleanSession() {
    pendingWrites(true);
  }

  private void pendingWrites(boolean buildWithPending) {
    seed();
    try (var other = independentGraph()) {
      enable(other);
      var builder = buildWithPending ? other : graph;
      var consumer = buildWithPending ? graph : other;
      var writer = buildWithPending ? builder : consumer;
      if (buildWithPending) {
        addPendingWrites(writer);
      }
      var cold = ordered(builder, 1, 0);
      GremlinToMatchStrategy.instance().apply(cold.asAdmin());
      cold.toList();
      if (!buildWithPending) {
        addPendingWrites(writer);
      }
      var db = graphSession(consumer);
      var cache = GremlinPlanCache.instance(db);
      var expected = nativeRows(consumer, 1, 0);
      var hits = cache.getTranslationHits();
      var traversal = ordered(consumer, 1, 0);
      GremlinToMatchStrategy.instance().apply(traversal.asAdmin());
      assertThat(cache.getTranslationHits()).isEqualTo(hits + 1);
      assertThat(boundary(traversal).getPlan().getSteps())
          .anyMatch(IndexOrderedEdgeStep.class::isInstance);
      assertThat(names(traversal.toList())).isEqualTo(expected);
      assertThat(expected.contains("pending")).isEqualTo(!buildWithPending);
      writer.tx().rollback();
      graphSession(other);
      other.tx().rollback();
    } finally {
      graphSession(graph);
    }
  }

  /** Schema-private ordered plans sharing a warm key do not replace either shared map. */
  @Test
  public void schemaPrivatePlanRemainsPrivateAcrossThreeSessions() {
    seed();
    ordered(graph, 1, 0).toList();
    var committed = ordered(graph, 1, 0);
    GremlinToMatchStrategy.instance().apply(committed.asAdmin());
    var template = boundary(committed).getPlan();
    try (var privateGraph = independentGraph(); var third = independentGraph()) {
      enable(privateGraph);
      enable(third);
      var db = graphSession(privateGraph);
      var cache = GremlinPlanCache.instance(db);
      var key = GremlinStepWalker.extractShape(ordered(privateGraph, 1, 0).asAdmin(), db).key();
      var sharedTranslation = cache.getTranslationInternal(key, db);
      db.getMetadata().getSchema().createClass("PrivateOrderedClass");
      assertThat(db.getTxSchemaState()).isNotNull();
      var hits = cache.getTranslationHits();
      var traversal = ordered(privateGraph, 1, 0);
      GremlinToMatchStrategy.instance().apply(traversal.asAdmin());
      var privatePlan = boundary(traversal).getPlan();
      assertThat(privatePlan).isNotSameAs(template);
      assertThat(names(traversal.toList())).hasSize(8);
      assertThat(boundary(traversal).getPlan()).isSameAs(privatePlan);
      assertThat(cache.getTranslationHits()).isEqualTo(hits);
      var thirdDb = graphSession(third);
      assertThat(cache.getTranslationInternal(key, thirdDb)).isSameAs(sharedTranslation);
      var reused = ordered(third, 1, 0);
      GremlinToMatchStrategy.instance().apply(reused.asAdmin());
      assertThat(boundary(reused).getPlan()).isSameAs(template).isNotSameAs(privatePlan);
      assertThat(names(reused.toList())).hasSize(8);
      graphSession(privateGraph);
      privateGraph.tx().rollback();
      graphSession(third);
      third.tx().rollback();
    } finally {
      graphSession(graph);
    }
  }

  private GraphTraversal<Vertex, Vertex> orderedUnion() {
    return graph.traversal().V().hasLabel("CacheSrc").has("id", 1)
        .union(__.out("CacheLink").hasLabel("CacheTgt").order().by("score").limit(4),
            __.out("CacheLink").hasLabel("CacheTgt").order().by("score", Order.desc).limit(4));
  }

  private YTDBGraph independentGraph() {
    // Each graph owns a separate pool so closing it cannot close the original graph's sessions.
    return new SessionPoolImpl(youTrackDB, databaseName, adminUser, adminPassword).asGraph();
  }

  private void seed() {
    var source = session.createVertexClass("CacheSrc");
    source.createProperty("id", PropertyType.INTEGER).createIndex(SchemaClass.INDEX_TYPE.UNIQUE);
    var target = session.createVertexClass("CacheTgt");
    target.createProperty("score", PropertyType.INTEGER)
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
    session.createEdgeClass("CacheLink");
    for (int s = 1; s <= 2; s++) {
      var vertex = graph.addVertex(T.label, "CacheSrc", "id", s);
      for (int score = 0; score < 20; score++) {
        vertex.addEdge("CacheLink", graph.addVertex(T.label, "CacheTgt", "score", score,
            "name", "s" + s + "-" + score, "tag", score));
      }
    }
    graph.tx().commit();
    enable(graph);
    GremlinPlanCache.instance(graphSession(graph)).invalidate();
  }

  private static GraphTraversal<Vertex, Vertex> ordered(YTDBGraph graph, int id, int minimum) {
    return graph.traversal().V().hasLabel("CacheSrc").has("id", id).out("CacheLink")
        .hasLabel("CacheTgt").has("score", P.gte(minimum)).order().by("score").limit(8);
  }

  private static void addPendingWrites(YTDBGraph graph) {
    var source = graph.traversal().V().hasLabel("CacheSrc").has("id", 1).next();
    source.addEdge("CacheLink", graph.addVertex(T.label, "CacheTgt", "score", 0,
        "name", "pending"));
    graph.traversal().V().hasLabel("CacheTgt").has("name", "s1-0").drop().iterate();
  }

  private static List<String> nativeRows(YTDBGraph graph, int id, int minimum) {
    var db = graphSession(graph);
    db.getConfiguration().setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED,
        false);
    try {
      return names(ordered(graph, id, minimum).toList());
    } finally {
      db.getConfiguration().setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED,
          true);
    }
  }

  private static List<String> names(List<?> rows) {
    return rows.stream().map(Vertex.class::cast).map(v -> v.<String>value("name")).toList();
  }

  private static YTDBMatchPlanStep<?, ?> boundary(GraphTraversal<?, ?> traversal) {
    assertThat(traversal.asAdmin().getStartStep()).isInstanceOf(YTDBMatchPlanStep.class);
    return (YTDBMatchPlanStep<?, ?>) traversal.asAdmin().getStartStep();
  }

  private static IndexOrderedEdgeStep orderedStep(InternalExecutionPlan plan) {
    return plan.getSteps().stream().filter(IndexOrderedEdgeStep.class::isInstance)
        .map(IndexOrderedEdgeStep.class::cast).findFirst().orElseThrow();
  }

  private static Object field(Object object, String name) throws Exception {
    Field field = object.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(object);
  }

  private static void enable(YTDBGraph graph) {
    graphSession(graph).getConfiguration().setValue(
        GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, true);
  }

  private static DatabaseSessionEmbedded graphSession(YTDBGraph graph) {
    var tx = (YTDBTransaction) graph.tx();
    try {
      tx.getDatabaseSession().activateOnCurrentThread();
    } catch (IllegalStateException noTransaction) {
      // A new graph has no session until readWrite opens its transaction.
    }
    tx.readWrite();
    var db = tx.getDatabaseSession();
    db.activateOnCurrentThread();
    return db;
  }
}
