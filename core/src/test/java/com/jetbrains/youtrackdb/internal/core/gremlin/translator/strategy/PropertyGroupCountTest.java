package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.SessionPoolImpl;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.AbstractMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.MultiPlanMatchStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AggregateProjectionCalculationStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ProjectionCalculationStep;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.ProductiveByStrategy;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Test;

/** Direct-count engagement and native parity, including the complete grouping shape. */
public class PropertyGroupCountTest extends GraphBaseTest {

  private final TranslatorEquivalenceSupport support =
      new TranslatorEquivalenceSupport(() -> session);

  /** Repeated keys, distinct numeric types, null and absence must keep native key/count types. */
  @Test
  public void mixedKeysAndProductiveRules() {
    graph.addVertex("p", "same");
    graph.addVertex("p", "same");
    graph.addVertex("p", 7);
    graph.addVertex("p", 7L);
    graph.addVertex().property("p", null);
    graph.addVertex();
    graph.tx().commit();

    var expected = new HashMap<Object, Long>();
    expected.put("same", 2L);
    expected.put(7, 1L);
    expected.put(7L, 1L);
    expected.put(null, 1L);
    assertThat(parity(() -> graph.traversal().V().groupCount().by("p")))
        .containsExactly(expected);
    expected.put(null, 2L);
    assertThat(parity(() -> graph.traversal().withStrategies(ProductiveByStrategy.instance())
        .V().groupCount().by("p"))).containsExactly(expected);
    var configured = ProductiveByStrategy.build().productiveKeys("p").create();
    expected.put(null, 1L);
    assertThat(parity(() -> graph.traversal().withStrategies(configured)
        .V().groupCount().by("p"))).containsExactly(expected);
    expected.put(null, 2L);
    assertThat(parity(() -> graph.traversal().withStrategies(
        ProductiveByStrategy.build().productiveKeys("other").create())
        .V().groupCount().by("p"))).containsExactly(expected);
  }

  /** values(p) drops missing independently of productive modulators, including on empty input. */
  @Test
  public void valuesAndEmptyInput() {
    graph.addVertex("p", "same");
    graph.addVertex().property("p", null);
    graph.addVertex();
    graph.tx().commit();
    var expected = new HashMap<Object, Long>();
    expected.put("same", 1L);
    expected.put(null, 1L);
    assertThat(parity(() -> graph.traversal().withStrategies(ProductiveByStrategy.instance())
        .V().values("p").groupCount())).containsExactly(expected);
    assertThat(parity(() -> graph.traversal().V().has("p", "nobody").groupCount().by("p")))
        .containsExactly(Map.of());
    assertThat(parity(() -> graph.traversal().V().values("absent").groupCount()))
        .containsExactly(Map.of());
  }

  /** Parallel paths to one target count twice. Whole-map shaping never slices MATCH input rows. */
  @Test
  public void pathsAndWholeMapOperations() {
    var start = graph.addVertex(T.label, "Person", "id", 1L);
    var target = graph.addVertex(T.label, "Person", "lastName", "Smith");
    start.addEdge("KNOWS", target);
    start.addEdge("KNOWS", target);
    graph.tx().commit();
    // This is the knowsGroupCountByLastName benchmark shape.
    assertThat(parity(() -> graph.traversal().V().hasLabel("Person").has("id", 1L)
        .out("KNOWS").groupCount().by("lastName")))
        .containsExactly(Map.of("Smith", 2L));
    assertThat(parity(() -> graph.traversal().V().groupCount().by("lastName").limit(1)))
        .containsExactly(Map.of("Smith", 1L));
    assertThat(parity(() -> graph.traversal().V().groupCount().by("lastName").skip(1)))
        .isEmpty();
    assertThat(parity(() -> graph.traversal().V().groupCount().by("lastName").fold()))
        .containsExactly(List.of(Map.of("Smith", 1L)));
  }

  /** Link-valued keys use native vertex and edge wrappers, including a preceding values step. */
  @Test
  public void graphElementKeys() {
    var first = graph.addVertex("marker", true);
    var second = graph.addVertex();
    var edge = first.addEdge("link", second);
    first.property("p", second);
    second.property("p", edge);
    graph.tx().commit();
    var result = parity(() -> graph.traversal().V().groupCount().by("p"));
    assertThat(((Map<?, ?>) result.getFirst()).keySet().toArray())
        .containsExactlyInAnyOrder(second, edge);
    assertThat(parity(() -> graph.traversal().V().values("p").groupCount()))
        .isEqualTo(result);
  }

  /** Internal attributes are absent in the native provider even if the SQL layer can read them. */
  @Test
  public void internalNamesAreAbsentBeforeProductiveRules() {
    graph.addVertex("p", "safe");
    graph.tx().commit();
    for (var key : List.of("@rid", "@class")) {
      assertThat(parity(() -> graph.traversal().V().groupCount().by(key)))
          .containsExactly(Map.of());
      var nullKey = new HashMap<Object, Long>();
      nullKey.put(null, 1L);
      assertThat(parity(() -> graph.traversal().withStrategies(ProductiveByStrategy.instance())
          .V().groupCount().by(key))).containsExactly(nullKey);
    }
  }

  /** Grouped entries, bare element keys and union children retain general aggregation. */
  @Test
  public void excludedShapesKeepGeneralProjection() {
    graph.addVertex("p", "same");
    graph.tx().commit();
    List<Supplier<GraphTraversal<?, ?>>> shapes = List.of(
        () -> graph.traversal().V().groupCount().by("p").unfold(),
        () -> graph.traversal().V().groupCount(),
        () -> graph.traversal().V().union(__.groupCount().by("p"), __.groupCount().by("p")));
    for (var shape : shapes) {
      var translated = new ArrayList<Object>();
      support.withTranslator(true, () -> {
        var admin = shape.get().asAdmin();
        admin.applyStrategies();
        var boundary = (AbstractMatchPlanStep<?, ?>) admin.getSteps().getFirst();
        assertThat(boundary.getPropertyGroupCount()).isNull();
        if (boundary instanceof YTDBMatchPlanStep<?, ?> single) {
          assertThat(single.getPlan().getSteps())
              .anyMatch(AggregateProjectionCalculationStep.class::isInstance);
        } else {
          assertThat(boundary).isInstanceOf(MultiPlanMatchStep.class);
          for (var plan : ((MultiPlanMatchStep<?, ?>) boundary).getPlans()) {
            assertThat(plan.getSteps())
                .anyMatch(AggregateProjectionCalculationStep.class::isInstance);
          }
        }
        translated.addAll(admin.toList());
        assertThat(translated).isNotEmpty();
      });
      support.withTranslator(false, () -> {
        var admin = shape.get().asAdmin();
        admin.applyStrategies();
        assertThat(TranslatorEquivalenceSupport.countBoundarySteps(admin)).isZero();
        assertThat(translated).isEqualTo(admin.toList());
      });
    }
  }

  /** A consumed map survives reset, clone, early close, copied-plan restart and a warm cache hit. */
  @Test
  public void lifecycleDoesNotMutateConsumerMaps() throws Exception {
    graph.addVertex("p", "same");
    graph.tx().commit();
    var admin = graph.traversal().V().groupCount().by("p").asAdmin();
    admin.applyStrategies();
    assertDirect(admin);
    Map<Object, Long> expected = Map.of("same", 1L);
    var map = admin.next();
    assertThat(map).isEqualTo(expected);
    map.put("consumer-before-reset", 9L);
    admin.reset();
    assertThat(map).containsExactlyInAnyOrderEntriesOf(
        Map.of("same", 1L, "consumer-before-reset", 9L));
    var restartedMap = admin.next();
    assertThat(restartedMap).isNotSameAs(map).isEqualTo(expected);
    assertThat(map).containsEntry("consumer-before-reset", 9L);

    restartedMap.put("consumer-before-clone", 7L);
    var clone = admin.clone();
    assertThat(restartedMap).containsEntry("consumer-before-clone", 7L);
    var cloneResults = clone.toList();
    assertThat(cloneResults).containsExactly(expected);
    assertThat(cloneResults.getFirst()).isNotSameAs(map).isNotSameAs(restartedMap);
    assertThat(restartedMap).containsExactlyInAnyOrderEntriesOf(
        Map.of("same", 1L, "consumer-before-clone", 7L));

    admin.close();
    assertThat(restartedMap).containsEntry("consumer-before-clone", 7L);
    admin.reset();
    var copiedPlanResults = admin.toList();
    assertThat(copiedPlanResults).containsExactly(expected);
    assertThat(copiedPlanResults.getFirst()).isNotSameAs(map).isNotSameAs(restartedMap)
        .isNotSameAs(cloneResults.getFirst());
    assertThat(map).containsEntry("consumer-before-reset", 9L);
    assertThat(restartedMap).containsEntry("consumer-before-clone", 7L);
    assertThat(parity(() -> graph.traversal().V().groupCount().by("p")))
        .containsExactly(expected);
  }

  /** Warm template hits retain the key and productive rule without sharing execution state. */
  @Test
  public void cachedCountingContractsStayIndependent() {
    graph.addVertex("p", "same", "q", 12);
    graph.addVertex();
    graph.tx().commit();
    var first = graph.traversal().V().groupCount().by("p").asAdmin();
    first.applyStrategies();
    var template = ((YTDBMatchPlanStep<?, ?>) first.getStartStep()).getPlan();
    assertThat(first.toList()).containsExactly(Map.of("same", 1L));
    var cache = GremlinPlanCache.instance(session);
    var hits = cache.getTranslationHits();
    var warm = graph.traversal().V().groupCount().by("p").asAdmin();
    warm.applyStrategies();
    assertDirect(warm);
    assertThat(cache.getTranslationHits()).isEqualTo(hits + 1);
    assertThat(((YTDBMatchPlanStep<?, ?>) warm.getStartStep()).getPlan()).isSameAs(template);
    assertThat(warm.toList()).containsExactly(Map.of("same", 1L));
    assertThat(parity(() -> graph.traversal().V().groupCount().by("q")))
        .containsExactly(Map.of(12, 1L));
    var productive = new HashMap<Object, Long>();
    productive.put("same", 1L);
    productive.put(null, 1L);
    for (int repeat = 0; repeat < 2; repeat++) {
      assertThat(parity(() -> graph.traversal().withStrategies(ProductiveByStrategy.instance())
          .V().groupCount().by("p"))).containsExactly(productive);
      assertThat(parity(() -> graph.traversal().withStrategies(
          ProductiveByStrategy.build().productiveKeys("p").create())
          .V().groupCount().by("p"))).containsExactly(Map.of("same", 1L));
    }
  }

  /** Separate sessions concurrently copy one cached plan with different bound filter values. */
  @Test
  public void concurrentCachedExecutionsKeepMapsAndContextsPrivate() throws Exception {
    graph.addVertex("bucket", 1, "p", "one");
    graph.addVertex("bucket", 2, "p", "two");
    graph.addVertex("bucket", 2, "p", "two");
    graph.tx().commit();
    var expected = new ArrayList<List<Object>>();
    for (int bucket : List.of(1, 2)) {
      expected.add(parity(() -> graph.traversal().V().has("bucket", bucket)
          .groupCount().by("p")));
    }
    var warm = graph.traversal().V().has("bucket", 1).groupCount().by("p").asAdmin();
    warm.applyStrategies();
    assertDirect(warm);
    var template = ((YTDBMatchPlanStep<?, ?>) warm.getStartStep()).getPlan();
    warm.toList();
    var ready = new CountDownLatch(2);
    var go = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var futures = new ArrayList<Future<Map<Object, Long>>>();
      for (int bucket : List.of(1, 2)) {
        futures.add(executor.submit(() -> {
          try (var ownGraph = new SessionPoolImpl(youTrackDB, databaseName,
              adminUser, adminPassword).asGraph()) {
            ownGraph.tx().readWrite();
            var ownSession = ((YTDBTransaction) ownGraph.tx()).getDatabaseSession();
            ownSession.getConfiguration().setValue(
                GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, true);
            var admin = ownGraph.traversal().V().has("bucket", bucket)
                .groupCount().by("p").asAdmin();
            admin.applyStrategies();
            assertDirect(admin);
            var boundary = (YTDBMatchPlanStep<?, ?>) admin.getStartStep();
            assertThat(boundary.getPlan()).isSameAs(template);
            ready.countDown();
            assertThat(go.await(10, TimeUnit.SECONDS)).isTrue();
            var map = admin.next();
            assertThat(boundary.getPlan()).isNotSameAs(template);
            assertThat(boundary.getPlan().getContext()).isNotSameAs(template.getContext());
            assertThat(boundary.getPlan().getContext().getDatabaseSession()).isSameAs(ownSession);
            admin.close();
            return map;
          }
        }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      var first = futures.get(0).get(30, TimeUnit.SECONDS);
      var second = futures.get(1).get(30, TimeUnit.SECONDS);
      assertThat(List.of(first)).isEqualTo(expected.get(0));
      assertThat(List.of(second)).isEqualTo(expected.get(1));
      assertThat(first).isNotSameAs(second);
      first.put("consumer", 9L);
      assertThat(second).isEqualTo(Map.of("two", 2L));
    } finally {
      go.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
      session.activateOnCurrentThread();
    }
  }

  /** A property read policy hides stored secrets before productive-by rules decide inclusion. */
  @Test
  public void propertyReadPolicyNeverExposesStoredValue() {
    session.createVertexClass("Secret");
    graph.addVertex(T.label, "Secret", "p", "classified", "visible", false);
    graph.addVertex(T.label, "Secret", "p", "public", "visible", true);
    graph.tx().commit();
    session.begin();
    var security = session.getSharedContext().getSecurity();
    var policy = security.createSecurityPolicy(session, "hideProperty");
    policy.setActive(true);
    policy.setReadRule("visible = true");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "reader"),
        "database.class.Secret.p", policy);
    session.commit();
    try (var readerGraph = new SessionPoolImpl(youTrackDB, databaseName,
        readerUser, readerPassword).asGraph()) {
      readerGraph.tx().readWrite();
      var readerSession = ((YTDBTransaction) readerGraph.tx()).getDatabaseSession();
      var readerSupport = new TranslatorEquivalenceSupport(() -> readerSession);
      for (boolean productive : List.of(false, true)) {
        var expected = new HashMap<Object, Long>();
        expected.put("public", 1L);
        if (productive) {
          expected.put(null, 1L);
        }
        for (boolean enabled : List.of(true, false)) {
          readerSupport.withTranslator(enabled, () -> {
            var source = productive ? readerGraph.traversal()
                .withStrategies(ProductiveByStrategy.instance()) : readerGraph.traversal();
            var admin = source.V().hasLabel("Secret").groupCount().by("p").asAdmin();
            admin.applyStrategies();
            if (enabled) {
              assertDirect(admin);
            } else {
              assertThat(TranslatorEquivalenceSupport.countBoundarySteps(admin)).isZero();
            }
            assertThat(admin.toList()).containsExactly(expected);
          });
        }
      }
      readerSession.getConfiguration().setValue(
          GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, true);
    } finally {
      session.activateOnCurrentThread();
    }
  }

  private List<Object> parity(Supplier<GraphTraversal<?, ?>> supplier) {
    var on = new ArrayList<Object>();
    support.withTranslator(true, () -> {
      var admin = supplier.get().asAdmin();
      admin.applyStrategies();
      assertDirect(admin);
      on.addAll(admin.toList());
      for (var value : on) {
        if (value instanceof Map<?, ?>) {
          assertThat(value).isExactlyInstanceOf(LinkedHashMap.class);
        }
      }
    });
    support.withTranslator(false, () -> {
      var admin = supplier.get().asAdmin();
      admin.applyStrategies();
      assertThat(TranslatorEquivalenceSupport.countBoundarySteps(admin)).isZero();
      var nativeResults = admin.toList();
      assertThat(on).isEqualTo(nativeResults);
      // Native groupCount uses HashMap. Key iteration order is not part of its contract.
      for (var value : nativeResults) {
        if (value instanceof Map<?, ?>) {
          assertThat(value).isExactlyInstanceOf(HashMap.class);
        }
      }
    });
    return on;
  }

  private static void assertDirect(Traversal.Admin<?, ?> admin) {
    assertThat(admin.getSteps().getFirst()).isInstanceOf(YTDBMatchPlanStep.class);
    var boundary = (YTDBMatchPlanStep<?, ?>) admin.getSteps().getFirst();
    assertThat(boundary.getPropertyGroupCount()).isNotNull();
    assertThat(boundary.getPlan().getSteps())
        .noneMatch(step -> step instanceof ProjectionCalculationStep
            || step instanceof AggregateProjectionCalculationStep);
  }
}
