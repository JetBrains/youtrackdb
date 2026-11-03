package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.PlainPlanDecision.Reason;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Before;
import org.junit.Test;

/** Production routing tests never use the test-only force-MATCH control. */
public class GremlinEngineRoutingTest extends GraphBaseTest {

  private DatabaseSessionEmbedded db;
  private Object rid;

  @Before
  public void seedRoutingFixture() {
    var first = graph.addVertex(T.label, "RouteVertex", "name", "one");
    first.addEdge("RouteEdge", graph.addVertex(T.label, "RouteVertex", "name", "two"));
    rid = first.id();
    // Keep the equality estimate above the materializing prefetch threshold. The slice must
    // follow a streaming filtered scan, not a blocking prefetch.
    for (int i = 0; i < 200; i++) {
      graph.addVertex(T.label, "RouteVertex", "name", "one");
    }
    graph.tx().commit();
    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    db = tx.getDatabaseSession();
    db.getConfiguration().setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED,
        true);
    GremlinPlanCache.instance(db).invalidate();
    assertThat(GremlinToMatchStrategy.FORCE_MATCH_FOR_TESTS.get()).isNull();
  }

  /** Plain plans, protected slices, sorted plans and cached capability declines have distinct paths. */
  @Test
  public void coldAndWarmPathsReuseOneWalkAndBuildButKeepDistinctOutcomes() {
    checkCached(() -> graph.traversal().V().hasLabel("RouteVertex"), true, false);
    checkCached(() -> graph.traversal().V().hasLabel("RouteVertex").has("name", "one")
        .limit(1), false, false);
    checkCached(() -> graph.traversal().V().hasLabel("RouteVertex").order().by("name"), false,
        false);
    checkCached(() -> graph.traversal().V().hasLabel("MissingRouteVertex"), false, true);
  }

  private void checkCached(Supplier<GraphTraversal<?, ?>> shape, boolean nativeChoice,
      boolean decline) {
    var cache = GremlinPlanCache.instance(db);
    cache.invalidate();
    var walks = new AtomicInteger();
    var builds = new AtomicInteger();
    var strategy = countingStrategy(walks, builds);
    var metrics = GremlinTranslationMetrics.of(db);
    long nativeBefore = metrics.getRuleNative(), successBefore = metrics.getSuccesses();
    long declineBefore = metrics.getDeclines(), errorsBefore = metrics.getErrors();
    long hits = cache.getTranslationHits(), misses = cache.getTranslationMisses();
    List<?> firstRows = null;
    for (int call = 0; call < 2; call++) {
      var traversal = shape.get().asAdmin();
      var originalSteps = List.copyOf(traversal.getSteps());
      strategy.apply(traversal);
      assertThat(TranslatorEquivalenceSupport.countBoundarySteps(traversal))
          .isEqualTo(nativeChoice || decline ? 0 : 1);
      if (nativeChoice || decline) {
        assertThat(traversal.getSteps()).containsExactlyElementsOf(originalSteps);
      }
      // Do not invoke the strategy again while draining the deliberately untouched traversal.
      db.getConfiguration().setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED,
          false);
      List<?> rows;
      try {
        rows = traversal.toList();
      } finally {
        db.getConfiguration().setValue(
            GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED,
            true);
      }
      if (firstRows == null) {
        firstRows = rows;
        if (!decline) {
          assertThat(rows).isNotEmpty();
        }
      } else {
        assertThat(rows).isEqualTo(firstRows);
      }
    }
    assertThat(walks.get()).isEqualTo(1);
    assertThat(builds.get()).isEqualTo(decline ? 0 : 1);
    assertThat(cache.getTranslationHits()).isEqualTo(hits + 1);
    assertThat(cache.getTranslationMisses()).isEqualTo(misses + 1);
    assertThat(metrics.getRuleNative()).isEqualTo(nativeBefore + (nativeChoice ? 2 : 0));
    assertThat(metrics.getSuccesses())
        .isEqualTo(successBefore + (!nativeChoice && !decline ? 2 : 0));
    assertThat(metrics.getDeclines()).isEqualTo(declineBefore + (decline ? 2 : 0));
    assertThat(metrics.getErrors()).isEqualTo(errorsBefore);
    var extraction = GremlinStepWalker.extractShape(shape.get().asAdmin(), db);
    var entry = cache.getTranslationInternal(extraction.key(), db);
    if (!decline) {
      var translated = (GremlinTranslationTemplate.Translate) entry;
      assertThat(translated.decision().plain()).isEqualTo(nativeChoice);
      if (shape.get().asAdmin().getSteps().stream()
          .anyMatch(s -> s.getClass().getSimpleName().equals("RangeGlobalStep"))) {
        assertThat(translated.decision().reason()).isEqualTo(Reason.SLICE_AFTER_FILTER);
      }
    } else {
      assertThat(entry).isInstanceOf(GremlinTranslationTemplate.Decline.class);
    }
  }

  /** RID-start single hops keep MATCH on repeated calls and never acquire a translation entry. */
  @Test
  public void ridSingleHopProtectionKeepsMatchWithoutIntroducingRidCaching() {
    var walks = new AtomicInteger();
    var builds = new AtomicInteger();
    var strategy = countingStrategy(walks, builds);
    var cache = GremlinPlanCache.instance(db);
    long hits = cache.getTranslationHits();
    long nativeBefore = GremlinTranslationMetrics.of(db).getRuleNative();
    for (int call = 0; call < 2; call++) {
      var traversal = graph.traversal().V(rid).out("RouteEdge").asAdmin();
      strategy.apply(traversal);
      assertThat(TranslatorEquivalenceSupport.countBoundarySteps(traversal)).isEqualTo(1);
      assertThat(traversal.toList()).hasSize(1);
    }
    assertThat(walks.get()).isEqualTo(2);
    assertThat(builds.get()).isEqualTo(2);
    assertThat(cache.getTranslationHits()).isEqualTo(hits);
    assertThat(GremlinTranslationMetrics.of(db).getRuleNative()).isEqualTo(nativeBefore);
  }

  /** A private unused plan closes once, while an explicitly shared identity never closes here. */
  @Test
  public void nativeExitClosesOnlyThePrivatePlanIdentity() {
    for (boolean shared : List.of(false, true)) {
      var plans = new ArrayList<InternalExecutionPlan>();
      var strategy = new GremlinToMatchStrategy(GremlinToMatchTranslator::translate,
          (session, translation, scope) -> {
            var privateScope = new GremlinToMatchStrategy.CacheScope(scope.generation, false);
            var plan = spy(GremlinToMatchStrategy.buildPlan(session, translation, privateScope));
            plans.add(plan);
            return shared ? scope.shared(plan) : plan;
          });
      var traversal = graph.traversal().V().hasLabel("RouteVertex").asAdmin();
      strategy.apply(traversal);
      assertThat(TranslatorEquivalenceSupport.countBoundarySteps(traversal)).isZero();
      assertThat(plans).hasSize(1);
      if (shared) {
        verify(plans.getFirst(), never()).close();
        plans.getFirst().close();
      } else {
        verify(plans.getFirst()).close();
      }
    }
  }

  /** Cleanup cannot turn a fatal classifier error into a swallowed runtime fallback. */
  @Test
  public void classifierErrorPropagatesWithCleanupFailureSuppressed() {
    var fatal = new AssertionError("classifier failure");
    var cleanup = new IllegalStateException("cleanup failure");
    var strategy = new GremlinToMatchStrategy(GremlinToMatchTranslator::translate,
        (session, translation, scope) -> {
          var privateScope = new GremlinToMatchStrategy.CacheScope(scope.generation, false);
          var plan = spy(GremlinToMatchStrategy.buildPlan(session, translation, privateScope));
          doThrow(fatal).when(plan).getSteps();
          doThrow(cleanup).when(plan).close();
          return plan;
        });
    assertThatThrownBy(() -> strategy.apply(graph.traversal().V().asAdmin()))
        .isSameAs(fatal).hasSuppressedException(cleanup);
  }

  /** Union translations remain MATCH even when each child is an ordinary scan. */
  @Test
  public void multiPlanRoutingKeepsMatch() {
    var traversal = graph.traversal().V().hasLabel("RouteVertex")
        .union(__.out("RouteEdge"), __.in("RouteEdge")).asAdmin();
    GremlinToMatchStrategy.instance().apply(traversal);
    assertThat(TranslatorEquivalenceSupport.countMultiPlanSteps(traversal.getSteps())).isEqualTo(1);
    assertThat(traversal.toList()).hasSize(2);
  }

  private GremlinToMatchStrategy countingStrategy(AtomicInteger walks, AtomicInteger builds) {
    return new GremlinToMatchStrategy(traversal -> {
      walks.incrementAndGet();
      return GremlinToMatchTranslator.translate(traversal);
    }, (session, translation, scope) -> {
      builds.incrementAndGet();
      return GremlinToMatchStrategy.buildPlan(session, translation, scope);
    }, true);
  }
}
