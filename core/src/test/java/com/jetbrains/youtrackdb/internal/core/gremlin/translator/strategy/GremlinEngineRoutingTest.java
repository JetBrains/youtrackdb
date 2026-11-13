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
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.YTDBStrategyUtil;
import com.jetbrains.youtrackdb.internal.core.sql.OrderByNullsUtil;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.PlainPlanDecision.Reason;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.OptionsStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.StandardOrderSemanticsStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.ProductiveByStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.verification.EdgeLabelVerificationStrategy;
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

  /** Invalid options win before early gates, while valid options preserve safety diagnostics. */
  @Test
  public void earlyDeclinesDoNotIntroduceValidationOrChangeSignatures() {
    for (int mode = 0; mode < 3; mode++) {
      var source = graph.traversal();
      if (mode == 0) {
        source = source.with("orderByNullsPlacementAsc", "invalid");
      } else if (mode == 2) {
        source = source.with("orderIncludesMissingKey", true)
            .withStrategies(StandardOrderSemanticsStrategy.instance());
      }
      var edge = source.E().as("$reserved").asAdmin();
      var repeat = source.V().as("$reserved").repeat(__.out()).times(2).asAdmin();
      RepeatDeclineStrategy.instance().apply(repeat);
      var duplicate = source.V().as("$reserved").out().as("$reserved").asAdmin();
      var collision = source.V().as("$reserved").select("side").asAdmin();
      collision.getSideEffects().register("side", () -> "value", (a, b) -> b);
      for (var traversal : List.of(edge, repeat, duplicate, collision)) {
        var metrics = GremlinTranslationMetrics.of(db);
        long attempts = metrics.getAttempts(), declines = metrics.getDeclines();
        long errors = metrics.getErrors();
        var signatures = metrics.topDeclinedShapes(256);
        var steps = List.copyOf(traversal.getSteps());
        if (mode == 2) {
          assertThatThrownBy(() -> GremlinToMatchStrategy.instance().apply(traversal))
              .isInstanceOf(ContradictoryOrderSemanticsException.class);
          assertThat(metrics.getAttempts()).isEqualTo(attempts);
        } else {
          GremlinToMatchStrategy.instance().apply(traversal);
          assertThat(metrics.getAttempts()).isEqualTo(attempts + 1);
        }
        assertThat(traversal.getSteps()).containsExactlyElementsOf(steps);
        assertThat(metrics.getErrors()).isEqualTo(errors + (mode == 0 ? 1 : 0));
        assertThat(metrics.getDeclines()).isEqualTo(declines + (mode == 1 ? 1 : 0));
        if (mode != 1) {
          assertThat(metrics.topDeclinedShapes(256)).isEqualTo(signatures);
          assertThat(QuerySafetyAdmission.stored(traversal)).isNull();
        } else {
          var reason = QuerySafetyAdmission.stored(traversal).reason();
          var expected = traversal == duplicate ? "safety:DUPLICATE_LABEL"
              : traversal == collision ? "safety:SIDE_EFFECT_NAME"
                  : steps.stream().map(s -> s.getClass().getSimpleName())
                      .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
          assertThat(reason).isEqualTo(traversal == duplicate
              ? QuerySafetyAdmission.Reason.DUPLICATE_LABEL
              : traversal == collision ? QuerySafetyAdmission.Reason.SIDE_EFFECT_NAME
                  : QuerySafetyAdmission.Reason.SAFE);
          assertThat(metrics.topDeclinedShapes(256))
              .anyMatch(s -> s.shape().equals(expected));
        }
      }
    }
  }

  /** Failed root validation leaves no evidence, so each of N children records its own error. */
  @Test
  public void invalidRootOptionsRecordOneErrorPerApplicationIncludingChildren() {
    for (boolean inject : List.of(false, true)) {
      var source = graph.traversal().with("orderByNullsPlacementAsc", "bad-asc");
      var root = inject
          ? source.inject(1).flatMap(__.V().hasLabel("RouteVertex")).asAdmin()
          : source.V().filter(__.V().hasLabel("RouteVertex"))
              .filter(__.V().hasLabel("RouteVertex")).asAdmin();
      int children = inject ? 1 : 2;
      var metrics = GremlinTranslationMetrics.of(db);
      long errors = metrics.getErrors(), attempts = metrics.getAttempts();
      long declines = metrics.getDeclines();
      root.applyStrategies();
      assertThat(QuerySafetyAdmission.stored(root)).isNull();
      assertThat(metrics.getErrors()).isEqualTo(errors + children + 1);
      assertThat(metrics.getAttempts()).isEqualTo(attempts + children + 1);
      assertThat(metrics.getDeclines()).isEqualTo(declines);
      assertThat(TranslatorEquivalenceSupport.countBoundarySteps(root)).isZero();
    }
  }

  /** Root admission, cold walk, cached decline and child calls all report the base text once. */
  @Test
  public void remainingDeclineSitesPreserveTextAndAccounting() {
    var child = __.V().hasLabel("MissingRouteVertex").asAdmin();
    var parent = graph.traversal().V().union(child).asAdmin();
    child.setGraph(graph);
    QuerySafetyAdmission.prepare(parent);
    var metrics = GremlinTranslationMetrics.of(db);
    long attempts = metrics.getAttempts(), declines = metrics.getDeclines();
    for (var traversal : List.of(graph.traversal().V().path().asAdmin(),
        graph.traversal().V().hasLabel("MissingRouteVertex").asAdmin(),
        graph.traversal().V().hasLabel("MissingRouteVertex").asAdmin(), child)) {
      var expected = traversal.getSteps().stream().map(s -> s.getClass().getSimpleName())
          .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
      long before = metrics.topDeclinedShapes(256).stream()
          .filter(s -> s.shape().equals(expected)).mapToLong(s -> s.count()).sum();
      GremlinToMatchStrategy.instance().apply(traversal);
      assertThat(metrics.topDeclinedShapes(256)).contains(
          new GremlinTranslationMetrics.ShapeCount(expected, before + 1));
    }
    assertThat(parent.getSteps()).hasSize(2);
    assertThat(metrics.getAttempts()).isEqualTo(attempts + 4);
    assertThat(metrics.getDeclines()).isEqualTo(declines + 4);
  }

  /** Each application reads fresh defaults and root overrides, without retaining its snapshot. */
  @Test
  public void settingsSnapshotIsLocalAndMatchesIndividualResolvers() {
    var configuration = db.getConfiguration();
    var key = GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT;
    var original = configuration.getValueAsBoolean(key);
    try {
      var traversal = graph.traversal().V().asAdmin();
      for (boolean polymorphic : List.of(false, true, false)) {
        configuration.setValue(key, polymorphic);
        var settings =
            com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.YTDBStrategyUtil
                .translationSettings(traversal, db);
        assertThat(settings.polymorphic()).isEqualTo(polymorphic);
        assertThat(settings.orderIncludesMissingKey()).isEqualTo(
            com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.YTDBStrategyUtil
                .orderIncludesMissingKey(traversal));
        assertThat(settings.nullPlacements()).isEqualTo(
            com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.YTDBStrategyUtil
                .orderByNullsPlacements(traversal));
      }
      var child = __.V().asAdmin();
      graph.traversal().with("polymorphicQuery", !original)
          .with("orderIncludesMissingKey", false).with("orderByNullsPlacementAsc", "FIRST")
          .V().union(child);
      var settings =
          com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.YTDBStrategyUtil
              .translationSettings(child, db);
      assertThat(settings.polymorphic()).isEqualTo(!original);
      assertThat(settings.orderIncludesMissingKey()).isFalse();
      assertThat(settings.nullPlacements().ascending())
          .isEqualTo(com.jetbrains.youtrackdb.api.config.OrderByNullsPlacement.FIRST);
      var session = org.mockito.Mockito.mock(DatabaseSessionEmbedded.class);
      assertThat(com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.YTDBStrategyUtil
          .translationSettings(child, session)).isNull();
      for (var type : List.of(GremlinToMatchStrategy.class,
          org.apache.tinkerpop.gremlin.process.traversal.util.DefaultTraversal.class)) {
        assertThat(type.getDeclaredFields()).noneMatch(field -> field.getType().getSimpleName()
            .equals("TranslationSettings"));
      }
    } finally {
      configuration.setValue(key, original);
    }
  }

  /** Invalidation during the first settings read makes this application's publication obsolete. */
  @Test
  public void cacheGenerationPrecedesSettingsAndSessionResolvesOnce() {
    var cache = GremlinPlanCache.instance(db);
    var session = spy(db);
    var configuration = spy(db.getConfiguration());
    org.mockito.Mockito.doReturn(configuration).when(session).getConfiguration();
    var reads = new AtomicInteger();
    org.mockito.Mockito.doAnswer(invocation -> {
      if (reads.incrementAndGet() == 1) {
        cache.invalidate();
      }
      return invocation.callRealMethod();
    }).when(configuration).getValueAsBoolean(
        GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY);
    var traversal = graph.traversal().V().hasLabel("MissingRouteVertex").asAdmin();
    var generation = cache.getGeneration();
    try (var util = org.mockito.Mockito.mockStatic(
        com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.YTDBStrategyUtil.class,
        org.mockito.Mockito.CALLS_REAL_METHODS)) {
      util.when(
          () -> com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.YTDBStrategyUtil
              .resolveYtdbSession(traversal))
          .thenReturn(session);
      new GremlinToMatchStrategy(t -> null, GremlinToMatchStrategy::buildPlan, true)
          .apply(traversal);
      util.verify(
          () -> com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.YTDBStrategyUtil
              .resolveYtdbSession(traversal),
          org.mockito.Mockito.times(1));
    }
    assertThat(cache.getGeneration()).isGreaterThan(generation);
    assertThat(reads.get()).isEqualTo(1);
    var extraction = GremlinStepWalker.extractShape(traversal, db);
    assertThat(extraction.complete()).isTrue();
    assertThat(cache.containsTranslation(extraction.key())).isFalse();
  }

  /** Reapplying one traversal and sharing strategies cannot retain settings from a previous call. */
  @Test
  public void repeatedApplicationsAndSharedStrategiesReadFreshSettings() {
    var configuration = db.getConfiguration();
    var key = GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT;
    var original = configuration.getValueAsBoolean(key);
    var observed = new ArrayList<Boolean>();
    var strategy = new GremlinToMatchStrategy(new GremlinToMatchStrategy.TraversalTranslator() {
      @Override
      public GremlinToMatchTranslator.TranslationResult translate(Traversal.Admin<?, ?> t) {
        throw new AssertionError("resolved settings must reach the translator");
      }

      @Override
      public GremlinToMatchTranslator.TranslationResult translate(Traversal.Admin<?, ?> t,
          Boolean order, com.jetbrains.youtrackdb.internal.core.sql.ResolvedOrderByNullsPlacement n,
          Boolean poly) {
        observed.add(poly);
        assertThat(order).isEqualTo(YTDBStrategyUtil.orderIncludesMissingKey(t));
        assertThat(n).isEqualTo(YTDBStrategyUtil.orderByNullsPlacements(t));
        return null;
      }
    });
    try {
      var root = graph.traversal().V().asAdmin();
      var sibling = graph.traversal().V().asAdmin();
      sibling.setStrategies(root.getStrategies());
      var child = __.V().asAdmin();
      var childRoot = graph.traversal().V().filter(child).asAdmin();
      QuerySafetyAdmission.prepare(childRoot);
      child.setGraph(graph);
      for (boolean value : List.of(false, true, false)) {
        configuration.setValue(key, value);
        for (var t : List.of(root, sibling, child)) {
          int before = observed.size();
          strategy.apply(t);
          assertThat(observed.size()).as("application %s graph %s", t, t.getGraph())
              .isEqualTo(before + 1);
        }
      }
      assertThat(observed).containsExactly(false, false, false, true, true, true,
          false, false, false);
      observed.clear();
      YTDBStrategyUtil.rootTraversal(child).setStrategies(root.getStrategies());
      for (boolean value : List.of(true, false, true)) {
        root.getStrategies().addStrategies(OptionsStrategy.build()
            .with("polymorphicQuery", value).with("orderIncludesMissingKey", true)
            .with("orderByNullsPlacementAsc", "FIRST").create());
        strategy.apply(root);
        strategy.apply(sibling);
        strategy.apply(child);
      }
      assertThat(observed).containsExactly(true, true, true, false, false, false, true, true, true);
    } finally {
      configuration.setValue(key, original);
    }
  }

  /** Cold, warm, RID, child and union runs read settings once across the whole application. */
  @Test
  public void productionApplicationsResolveSessionAndSettingsExactlyOnce() {
    var unchanged = graph.traversal().V().hasLabel("RouteVertex").asAdmin();
    var child = __.V().hasLabel("MissingRouteVertex").asAdmin();
    var childRoot = graph.traversal().V().filter(child).asAdmin();
    QuerySafetyAdmission.prepare(childRoot);
    child.setGraph(graph);
    var sorted = graph.traversal().V(rid).out("RouteEdge").order().by("name").asAdmin();
    var union = graph.traversal().V().union(__.out("RouteEdge"), __.in("RouteEdge"))
        .order().by("name").asAdmin();
    for (var traversal : List.of(unchanged, unchanged, sorted, child, union)) {
      var strategies = spy(traversal.getStrategies().clone());
      traversal.setStrategies(strategies);
      var root = YTDBStrategyUtil.rootTraversal(traversal);
      var rootStrategies = root == traversal ? strategies : spy(root.getStrategies().clone());
      root.setStrategies(rootStrategies);
      var options = spy(OptionsStrategy.build().create());
      rootStrategies.addStrategies(options);
      var session = spy(db);
      var configuration = spy(db.getConfiguration());
      org.mockito.Mockito.doReturn(configuration).when(session).getConfiguration();
      try (var util = org.mockito.Mockito.mockStatic(YTDBStrategyUtil.class,
          org.mockito.Mockito.CALLS_REAL_METHODS);
          var nulls = org.mockito.Mockito.mockStatic(OrderByNullsUtil.class,
              org.mockito.Mockito.CALLS_REAL_METHODS)) {
        util.when(() -> YTDBStrategyUtil.resolveYtdbSession(
            org.mockito.ArgumentMatchers.any())).thenReturn(session);
        // Exclude spy and mock setup, but retain every read made during apply.
        org.mockito.Mockito.clearInvocations(session, configuration, strategies,
            rootStrategies, options);
        util.clearInvocations();
        GremlinToMatchStrategy.instance().apply(traversal);
        util.verify(() -> YTDBStrategyUtil.translationSettings(traversal, session),
            org.mockito.Mockito.times(1));
        util.verify(() -> YTDBStrategyUtil.resolveYtdbSession(
            org.mockito.ArgumentMatchers.any()), org.mockito.Mockito.times(1));
        // The kill-switch is separate from settings. Cache and planner configuration access
        // also occurs during apply, so count the settings reads by their specific operations.
        verify(configuration, org.mockito.Mockito.times(1)).getValueAsBoolean(
            GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED);
        verify(configuration, org.mockito.Mockito.times(1)).getValueAsBoolean(
            GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY);
        verify(configuration, org.mockito.Mockito.times(1)).getValueAsBoolean(
            GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT);
        util.verify(() -> YTDBStrategyUtil.explicitNullPlacements(options),
            org.mockito.Mockito.times(1));
        nulls.verify(() -> OrderByNullsUtil.resolvePlacements(configuration),
            org.mockito.Mockito.times(1));
        verify(configuration, org.mockito.Mockito.times(2)).getContextKeys();
        verify(options, org.mockito.Mockito.times(4)).getOptions();
        verify(rootStrategies, org.mockito.Mockito.times(1)).getStrategy(OptionsStrategy.class);
        verify(rootStrategies, org.mockito.Mockito.times(1))
            .getStrategy(StandardOrderSemanticsStrategy.class);
        verify(strategies, org.mockito.Mockito.times(1))
            .getStrategy(EdgeLabelVerificationStrategy.class);
        verify(strategies, org.mockito.Mockito.times(1)).getStrategy(ProductiveByStrategy.class);
      }
    }
  }

  /** Warm production entries are keyed by this application, not the first shared snapshot. */
  @Test
  public void defaultChangesSelectFreshColdAndWarmEntries() {
    var key = GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT;
    var configuration = db.getConfiguration();
    var original = configuration.getValueAsBoolean(key);
    var cache = GremlinPlanCache.instance(db);
    cache.invalidate();
    var hits = cache.getTranslationHits();
    var misses = cache.getTranslationMisses();
    var root = graph.traversal().V().hasLabel("MissingRouteVertex").asAdmin();
    try {
      for (boolean value : List.of(false, true, false)) {
        configuration.setValue(key, value);
        GremlinToMatchStrategy.instance().apply(root);
        assertThat(cache.containsTranslation(GremlinStepWalker.extractShape(root, db).key()))
            .isTrue();
      }
      assertThat(cache.getTranslationHits()).isEqualTo(hits + 1);
      assertThat(cache.getTranslationMisses()).isEqualTo(misses + 2);
    } finally {
      configuration.setValue(key, original);
    }
  }

  /** Shared helpers preserve explicit overrides, standard-order opt-out and deliberate rejection. */
  @Test
  public void snapshotAndNativeResolversAgreeForAllOrderOverrides() {
    for (boolean standard : List.of(false, true)) {
      for (var explicit : List.of("absent", "true", "false")) {
        var source = graph.traversal().with("polymorphicQuery", false)
            .with("orderByNullsPlacementAsc", "FIRST")
            .with("orderByNullsPlacementDesc", "LAST");
        if (standard) {
          source = source.withStrategies(StandardOrderSemanticsStrategy.instance());
        } else {
          source = source.withoutStrategies(StandardOrderSemanticsStrategy.class);
        }
        if (!explicit.equals("absent")) {
          source = source.with("orderIncludesMissingKey", Boolean.valueOf(explicit));
        }
        var t = source.V().asAdmin();
        var strategies = spy(t.getStrategies().clone());
        t.setStrategies(strategies);
        if (standard && explicit.equals("true")) {
          assertThatThrownBy(() -> YTDBStrategyUtil.translationSettings(t, db))
              .isInstanceOf(ContradictoryOrderSemanticsException.class);
          assertThatThrownBy(() -> YTDBStrategyUtil.orderIncludesMissingKey(t))
              .isInstanceOf(ContradictoryOrderSemanticsException.class);
        } else {
          var settings = YTDBStrategyUtil.translationSettings(t, db);
          verify(strategies, org.mockito.Mockito.times(1))
              .getStrategy(StandardOrderSemanticsStrategy.class);
          assertThat(settings.orderIncludesMissingKey())
              .isEqualTo(YTDBStrategyUtil.orderIncludesMissingKey(t));
          assertThat(settings.nullPlacements())
              .isEqualTo(YTDBStrategyUtil.orderByNullsPlacements(t));
          assertThat(settings.polymorphic()).isEqualTo(YTDBStrategyUtil.isPolymorphic(t));
        }
      }
    }
  }

  /** Competing failures retain order-before-null-before-poly on every late decline route. */
  @Test
  public void competingOptionFailuresKeepBasePrecedenceAndTelemetry() {
    for (int path = 0; path < 4; path++) {
      for (boolean contradiction : List.of(true, false)) {
        var source = graph.traversal();
        Traversal.Admin<?, ?> t;
        if (path == 0) {
          t = source.V().path().asAdmin();
        } else if (path == 3) {
          t = __.V().hasLabel("MissingRouteVertex").asAdmin();
          source.V().filter(t);
          t.setGraph(graph);
        } else {
          t = source.V().hasLabel("MissingRouteVertex").asAdmin();
        }
        if (path == 2) {
          GremlinToMatchStrategy.instance().apply(t);
          assertThat(GremlinPlanCache.instance(db).containsTranslation(
              GremlinStepWalker.extractShape(t, db).key())).isTrue();
        }
        var root = YTDBStrategyUtil.rootTraversal(t);
        var options = OptionsStrategy.build().with("polymorphicQuery", "invalid")
            .with("orderByNullsPlacementAsc", "bad-asc")
            .with("orderByNullsPlacementDesc", "bad-desc");
        if (contradiction) {
          options.with("orderIncludesMissingKey", true);
          root.getStrategies().addStrategies(StandardOrderSemanticsStrategy.instance());
        }
        root.getStrategies().addStrategies(options.create());
        var metrics = GremlinTranslationMetrics.of(db);
        long attempts = metrics.getAttempts(), errors = metrics.getErrors();
        long declines = metrics.getDeclines();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        try (var util = org.mockito.Mockito.mockStatic(YTDBStrategyUtil.class,
            org.mockito.Mockito.CALLS_REAL_METHODS)) {
          util.when(() -> YTDBStrategyUtil.explicitNullPlacements(
              org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
                try {
                  return invocation.callRealMethod();
                } catch (IllegalArgumentException ex) {
                  failure.set(ex);
                  throw ex;
                }
              });
          if (contradiction) {
            assertThatThrownBy(() -> GremlinToMatchStrategy.instance().apply(t))
                .isInstanceOf(ContradictoryOrderSemanticsException.class);
            assertThat(failure.get()).isNull();
          } else {
            GremlinToMatchStrategy.instance().apply(t);
            assertThat(failure.get()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orderByNullsPlacementAsc").hasMessageContaining("bad-asc");
          }
        }
        assertThat(metrics.getAttempts()).isEqualTo(attempts + (contradiction ? 0 : 1));
        assertThat(metrics.getErrors()).isEqualTo(errors + (contradiction ? 0 : 1));
        assertThat(metrics.getDeclines()).isEqualTo(declines);
      }
    }
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

  /** A warm native decision builds neither bindings nor MATCH-only HasStep contexts. */
  @Test
  public void warmNativeHitLeavesHarvestAndMatchContextUntouched() {
    createRoutingIndex();
    var strategy = countingStrategy(new AtomicInteger(), new AtomicInteger());
    strategy.apply(indexedRoute());
    var observed = new java.util.ArrayList<GremlinShapeExtractor.Extraction>();
    strategy.extractionObserver = observed::add;
    try (var contexts = org.mockito.Mockito.mockStatic(HasBindingContext.class,
        org.mockito.Mockito.CALLS_REAL_METHODS)) {
      var warm = indexedRoute();
      strategy.apply(warm);
      assertThat(TranslatorEquivalenceSupport.countBoundarySteps(warm)).isZero();
      assertThat(observed).hasSize(1);
      assertThat(observed.getFirst().encoder().harvested()).isFalse();
      contexts.verify(() -> HasBindingContext.forVertex(org.mockito.ArgumentMatchers.anyList(),
          org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean(),
          org.mockito.ArgumentMatchers.any()), org.mockito.Mockito.never());
      contexts.verify(() -> HasBindingContext.forEdge(org.mockito.ArgumentMatchers.any()),
          org.mockito.Mockito.never());
      contexts.verify(() -> HasBindingContext.schemaFacts(org.mockito.ArgumentMatchers.any()),
          org.mockito.Mockito.never());
    }
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
