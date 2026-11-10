package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.LogRecordCollector;
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

  /** The first binding decides a resident shape. Warm calls neither walk nor prove again. */
  @Test
  public void indexedColdWarmRebindingAndGenerationInvalidation() {
    createRoutingIndex();
    var proofs = new AtomicInteger();
    var strategy = countingStrategy(new AtomicInteger(), new AtomicInteger());
    strategy.nativeStartProof = (traversal, session) -> {
      proofs.incrementAndGet();
      return NativeStartProof.prove(traversal, session);
    };
    for (String value : List.of("two", "one", "missing")) {
      var traversal =
          graph.traversal().V().hasLabel("IndexedRouteVertex").has("name", value).asAdmin();
      strategy.apply(traversal);
      assertThat(TranslatorEquivalenceSupport.countBoundarySteps(traversal)).isZero();
      assertThat(traversal.toList())
          .hasSize(value.equals("two") ? 1 : value.equals("one") ? 201 : 0);
    }
    assertThat(proofs.get()).isEqualTo(1);
    GremlinPlanCache.instance(db).invalidate();
    strategy
        .apply(graph.traversal().V().hasLabel("IndexedRouteVertex").has("name", "two").asAdmin());
    assertThat(proofs.get()).isEqualTo(2);
    var first = GremlinStepWalker.extractShape(
        graph.traversal().V().hasLabel("IndexedRouteVertex").has("name", "two").asAdmin(), db);
    var number = GremlinStepWalker.extractShape(
        graph.traversal().V().hasLabel("IndexedRouteVertex").has("name", 2).asAdmin(), db);
    var nil = GremlinStepWalker.extractShape(
        graph.traversal().V().hasLabel("IndexedRouteVertex").has("name", (Object) null).asAdmin(),
        db);
    assertThat(first.key()).isNotEqualTo(number.key()).isNotEqualTo(nil.key());
  }

  /** A failed proof signals once, keeps MATCH, and never catches fatal or input errors. */
  @Test
  public void optionalProofFailureIsObservableAndErrorsPropagate() {
    createRoutingIndex();
    var strategy = countingStrategy(new AtomicInteger(), new AtomicInteger());
    var proofs = new AtomicInteger();
    var metrics = GremlinTranslationMetrics.of(db);
    long proofErrors = metrics.getNativeProofErrors();
    long errors = metrics.getErrors();
    long declines = metrics.getDeclines();
    strategy.nativeStartProof = (traversal, session) -> {
      proofs.incrementAndGet();
      throw new IllegalStateException("private predicate");
    };
    try (var logs = LogRecordCollector.attachTo(GremlinToMatchStrategy.class)) {
      for (int i = 0; i < 2; i++) {
        var raw = indexedRoute();
        strategy.apply(raw);
        assertThat(TranslatorEquivalenceSupport.countBoundarySteps(raw)).isEqualTo(1);
        assertThat(metrics.getNativeProofErrors()).isEqualTo(proofErrors + 1);
        assertThat(metrics.getErrors()).isEqualTo(errors);
        assertThat(metrics.getDeclines()).isEqualTo(declines);
        // JUL WARNING is SLF4J WARN. The cold failure logs once and warm reuse adds nothing.
        assertThat(logs.messages()).singleElement().asString()
            .startsWith("WARNING ")
            .contains("NATIVE_PROOF_ERROR", IllegalStateException.class.getName())
            .doesNotContain("private predicate", "two");
      }
    }
    assertThat(proofs.get()).isEqualTo(1);
    var extraction = GremlinStepWalker.extractShape(indexedRoute(), db);
    var entry = (GremlinTranslationTemplate.Translate) GremlinPlanCache
        .getTranslation(extraction.key(), db);
    assertThat(entry.decision().reason()).isEqualTo(Reason.NATIVE_PROOF_ERROR);
    assertThat(entry.decision().offendingStepClass()).isNull();
    GremlinPlanCache.instance(db).invalidate();
    strategy.nativeStartProof = (traversal, session) -> {
      throw new AssertionError("fatal");
    };
    assertThatThrownBy(() -> strategy.apply(indexedRoute())).isInstanceOf(AssertionError.class);
    strategy.nativeStartProof = (traversal, session) -> {
      throw new ReservedAliasException("bad");
    };
    assertThatThrownBy(() -> strategy.apply(indexedRoute()))
        .isInstanceOf(ReservedAliasException.class);
    // Contradictory order semantics are deliberate input rejection, not optional-proof failure.
    strategy.nativeStartProof = (traversal, session) -> {
      throw new ContradictoryOrderSemanticsException("bad order");
    };
    assertThatThrownBy(() -> strategy.apply(indexedRoute()))
        .isInstanceOf(ContradictoryOrderSemanticsException.class);
  }

  /** Cheap order checks, private calls and metadata races must not authorize native index starts. */
  @Test
  public void proofAdmissionAndInvalidationKeepMatch() {
    createRoutingIndex();
    var strategy = countingStrategy(new AtomicInteger(), new AtomicInteger());
    var proofs = new AtomicInteger();
    strategy.nativeStartProof = (traversal, session) -> {
      proofs.incrementAndGet();
      var evidence = NativeStartProof.prove(traversal, session);
      GremlinPlanCache.instance(session).invalidate();
      return evidence;
    };
    var ordered = graph.traversal().V().hasLabel("IndexedRouteVertex").has("name", "two")
        .order().by("name").asAdmin();
    strategy.apply(ordered);
    assertThat(proofs.get()).isZero();
    var raw = indexedRoute();
    strategy.apply(raw);
    assertThat(proofs.get()).isEqualTo(1);
    assertThat(TranslatorEquivalenceSupport.countBoundarySteps(raw)).isEqualTo(1);
    assertThat(GremlinPlanCache.getTranslation(
        GremlinStepWalker.extractShape(indexedRoute(), db).key(), db)).isNull();
    var uncached = new GremlinToMatchStrategy(GremlinToMatchTranslator::translate,
        GremlinToMatchStrategy::buildPlan);
    uncached.nativeStartProof = (traversal, session) -> {
      throw new AssertionError("no proof");
    };
    raw = indexedRoute();
    uncached.apply(raw);
    assertThat(TranslatorEquivalenceSupport.countBoundarySteps(raw)).isEqualTo(1);
  }

  /** A disabled existing cache must execute index starts through MATCH without optional proof. */
  @Test
  public void disabledCacheNeverBuildsNativeEvidence() throws Exception {
    createRoutingIndex();
    var context = db.getSharedContext();
    var field = com.jetbrains.youtrackdb.internal.core.db.SharedContext.class
        .getDeclaredField("gremlinPlanCache");
    field.setAccessible(true);
    var original = field.get(context);
    try {
      field.set(context, new GremlinPlanCache(0));
      var strategy = countingStrategy(new AtomicInteger(), new AtomicInteger());
      strategy.nativeStartProof = (traversal, session) -> {
        throw new AssertionError("disabled");
      };
      for (int i = 0; i < 2; i++) {
        var raw = indexedRoute();
        strategy.apply(raw);
        assertThat(TranslatorEquivalenceSupport.countBoundarySteps(raw)).isEqualTo(1);
        assertThat(raw.toList()).hasSize(1);
      }
    } finally {
      field.set(context, original);
    }
  }

  /** Parallel cold work may duplicate proof, but publication reuses the first resident decision. */
  @Test
  public void overlappingColdProofsReuseTheWinningResidentDecision() {
    createRoutingIndex();
    var proofs = new AtomicInteger();
    var winner = countingStrategy(new AtomicInteger(), new AtomicInteger());
    winner.nativeStartProof = (traversal, session) -> {
      proofs.incrementAndGet();
      return NativeStartProof.prove(traversal, session);
    };
    var late = countingStrategy(new AtomicInteger(), new AtomicInteger());
    late.nativeStartProof = (traversal, session) -> {
      proofs.incrementAndGet();
      // Park the first caller's proof while another caller completes and publishes.
      winner.apply(indexedRoute());
      return null;
    };
    var raw = indexedRoute();
    late.apply(raw);
    assertThat(TranslatorEquivalenceSupport.countBoundarySteps(raw)).isZero();
    late.apply(indexedRoute());
    assertThat(proofs.get()).isEqualTo(2);
  }

  /** Schema-local changes bypass native evidence and rollback restores ordinary cache access. */
  @Test
  public void schemaTransactionBypassesIndexedDecisionAndProof() {
    createRoutingIndex();
    var strategy = countingStrategy(new AtomicInteger(), new AtomicInteger());
    strategy.apply(indexedRoute());
    db.getSchema().createClass("RoutingTxOnly", db.getSchema().getClass("V"));
    strategy.nativeStartProof = (traversal, session) -> {
      throw new AssertionError("tx proof");
    };
    var raw = indexedRoute();
    strategy.apply(raw);
    assertThat(TranslatorEquivalenceSupport.countBoundarySteps(raw)).isEqualTo(1);
    graph.tx().rollback();
    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    db = tx.getDatabaseSession();
    strategy.nativeStartProof = NativeStartProof::prove;
    raw = indexedRoute();
    strategy.apply(raw);
    assertThat(TranslatorEquivalenceSupport.countBoundarySteps(raw)).isZero();
  }

  /** Index removal and replacement invalidate the retained first-call access facts. */
  @Test
  public void removedAndReplacedIndexRebuildsTheDecision() {
    createRoutingIndex();
    var strategy = countingStrategy(new AtomicInteger(), new AtomicInteger());
    strategy.apply(indexedRoute());
    var key = GremlinStepWalker.extractShape(indexedRoute(), db).key();
    var cache = GremlinPlanCache.instance(db);
    graph.tx().commit();
    session.activateOnCurrentThread();
    session.execute("DROP INDEX IndexedRouteVertex.name").close();
    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    db = tx.getDatabaseSession();
    assertThat(cache.containsTranslation(key)).isFalse();
    strategy.apply(indexedRoute());
    var scan = (GremlinTranslationTemplate.Translate) GremlinPlanCache.getTranslation(key, db);
    assertThat(scan.decision().facts().startKind())
        .isEqualTo(
            com.jetbrains.youtrackdb.internal.core.sql.executor.match.PlainPlanDecision.StartKind.CLASS_SCAN);
    graph.tx().commit();
    session.activateOnCurrentThread();
    session.execute("CREATE INDEX RoutingReplacement ON IndexedRouteVertex (name) NOTUNIQUE")
        .close();
    tx.readWrite();
    db = tx.getDatabaseSession();
    assertThat(cache.containsTranslation(key)).isFalse();
    strategy.apply(indexedRoute());
    var indexed = (GremlinTranslationTemplate.Translate) GremlinPlanCache.getTranslation(key, db);
    assertThat(indexed.decision().facts().startKind())
        .isEqualTo(
            com.jetbrains.youtrackdb.internal.core.sql.executor.match.PlainPlanDecision.StartKind.INDEX);
  }

  /** Binding disagreement prevents retention and must skip optional proof on every cold call. */
  @Test
  public void unretainedBindingDisagreementDoesNotRunProof() {
    createRoutingIndex();
    var failures = new ArrayList<String>();
    var strategy = new GremlinToMatchStrategy(traversal -> {
      var translation = GremlinToMatchTranslator.translate(traversal);
      var extra = new java.util.HashMap<Object, Object>(translation.inputParameters());
      extra.put("unused", "two");
      return new GremlinToMatchTranslator.TranslationResult(
          translation.inputs(), translation.childPlans(), translation.postConcatOps(),
          translation.boundaryAlias(), translation.outputType(), translation.returnClass(),
          extra, translation.cacheEligible(), translation.shaping(), translation.hasContributions(),
          translation.userLabelAliases());
    }, GremlinToMatchStrategy::buildPlan, true, failures::add);
    var proofs = new AtomicInteger();
    strategy.nativeStartProof = (traversal, session) -> {
      proofs.incrementAndGet();
      return NativeStartProof.prove(traversal, session);
    };
    for (int call = 0; call < 2; call++) {
      var raw = indexedRoute();
      strategy.apply(raw);
      assertThat(TranslatorEquivalenceSupport.countBoundarySteps(raw)).isEqualTo(1);
    }
    assertThat(failures).containsExactly("Cold translation binding agreement failed",
        "Cold translation binding agreement failed");
    assertThat(proofs.get()).isZero();
  }

  private void createRoutingIndex() {
    var type = db.createVertexClass("IndexedRouteVertex");
    type.createProperty("name",
        com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType.STRING)
        .createIndex(
            com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE.NOTUNIQUE);
    graph.addVertex(T.label, "IndexedRouteVertex", "name", "two");
    for (int i = 0; i < 201; i++) {
      graph.addVertex(T.label, "IndexedRouteVertex", "name", "one");
    }
    graph.tx().commit();
    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    db = tx.getDatabaseSession();
  }

  private org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin<?, ?> indexedRoute() {
    return graph.traversal().V().hasLabel("IndexedRouteVertex").has("name", "two").asAdmin();
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
