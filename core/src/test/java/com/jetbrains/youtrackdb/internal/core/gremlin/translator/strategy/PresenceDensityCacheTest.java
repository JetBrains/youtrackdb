package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/** Binding-sensitive density choices remain reusable without retaining the first binding's rows. */
public class PresenceDensityCacheTest extends GraphBaseTest {

  /** Both cache paths retain the first root but return disjoint, nonempty binding-specific bags. */
  @Test
  public void twoBindings_reuseDensityRootAndRebindValuesOnBothHitPaths() throws Exception {
    var source = session.createVertexClass("CacheSource");
    source.createProperty("bucket", PropertyType.INTEGER)
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
    var target = session.createVertexClass("CacheTarget");
    target.createProperty("k", PropertyType.INTEGER).setNotNull(true);
    target.getProperty("k").createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
    var edge = session.createEdgeClass("CacheLink");
    edge.getProperty("out").setLinkedClass(source);
    edge.getProperty("in").setLinkedClass(target);
    var targets = new ArrayList<Vertex>();
    for (int i = 0; i < 500; i++) {
      var vertex = graph.addVertex(T.label, "CacheTarget");
      if (i % 100 == 0 || i == 499) {
        vertex.property("k", i);
      }
      targets.add(vertex);
    }
    for (int i = 0; i < 1000; i++) {
      graph.addVertex(T.label, "CacheSource", "bucket", i < 450 ? 1 : i >= 999 ? 3 : 2)
          .addEdge("CacheLink", targets.get(i % 500));
    }
    graph.tx().commit();
    session.getSharedContext().getIndexManager().getIndex("CacheSource.bucket").rebuild(session);
    graph.tx().readWrite();
    var db = ((YTDBTransaction) graph.tx()).getDatabaseSession();
    db.getConfiguration().setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED,
        true);
    var cache = db.getSharedContext().getGremlinPlanCache();
    graph.tx().rollback();
    var walks = new AtomicInteger();
    var builds = new AtomicInteger();
    var strategy = new GremlinToMatchStrategy(t -> {
      walks.incrementAndGet();
      return GremlinToMatchTranslator.translate(t);
    }, (s, translation, scope) -> {
      builds.incrementAndGet();
      return GremlinToMatchStrategy.buildPlan(s, translation, scope);
    }, true);
    var first = query(strategy, 1, List.of(0, 100, 200, 300, 400));
    assertThat(first).contains("<----");
    var hits = cache.getTranslationHits();
    assertThat(query(strategy, 3, List.of(499))).isEqualTo(first);
    assertThat(cache.getTranslationHits()).isEqualTo(hits + 1);
    assertThat(walks.get()).isEqualTo(1);
    assertThat(builds.get()).isEqualTo(1);
    // Remove only the translation layer so the next lookup must use the physical fingerprint.
    var field = GremlinPlanCache.class.getDeclaredField("translationCache");
    field.setAccessible(true);
    var translationCache = field.get(cache);
    var clear = translationCache.getClass().getMethod("invalidateAll");
    clear.setAccessible(true);
    clear.invoke(translationCache);
    var physicalHits = cache.getHits();
    assertThat(query(strategy, 3, List.of(499))).isEqualTo(first);
    assertThat(cache.getHits()).isEqualTo(physicalHits + 1);
    assertThat(walks.get()).isEqualTo(2);
    assertThat(builds.get()).isEqualTo(2);
    cache.invalidate();
    assertThat(query(strategy, 3, List.of(499))).doesNotContain("<----");
  }

  private String query(GremlinToMatchStrategy strategy, int bucket, List<Integer> expected) {
    var traversal = graph.traversal().V().hasLabel("CacheSource").has("bucket", bucket)
        .out("CacheLink").hasLabel("CacheTarget").values("k").limit(10000);
    strategy.apply(traversal.asAdmin());
    assertThat(traversal.asAdmin().getStartStep()).isInstanceOf(YTDBMatchPlanStep.class);
    var step = (YTDBMatchPlanStep<?, ?>) traversal.asAdmin().getStartStep();
    var text = step.getPlan().prettyPrint(0, 2);
    try {
      assertThat(traversal.toList()).containsExactlyInAnyOrderElementsOf(expected);
      return text;
    } finally {
      graph.tx().rollback();
    }
  }
}
