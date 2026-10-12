package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBQueryConfigParam;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.YTDBStrategyUtil;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.TailGlobalStepContract;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.PropertiesStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.OptionsStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.StandardOrderSemanticsStrategy;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Test;

public class GremlinTypeDeclineTest extends GraphBaseTest {

  /** Every shared contract admits its classes, including both tails and consumed subclasses. */
  @Test
  public void admissionMatchesRegistryTransparencyAndClaims() {
    var classes = new java.util.HashSet<>(GremlinStepWalker.PRODUCTION_RECOGNISERS.keySet());
    classes.addAll(GremlinStepWalker.TRANSPARENT_STEPS);
    classes.add(EdgeHopRecogniser.CONSUMED_CLOSING_STEP);
    classes.addAll(OrderedExpandAccept.CONSUMED_FAMILIES);
    classes.addAll(TailGlobalStepContract.CONCRETE_STEPS);
    classes.add(new HasStep<>(__.V().asAdmin()) {
    }.getClass());
    classes.add(new PropertiesStep<>(__.V().asAdmin(),
        org.apache.tinkerpop.gremlin.structure.PropertyType.VALUE, "name") {
    }.getClass());
    for (var type : classes) {
      assertThat(RootStepAdmission.admits(
          (org.apache.tinkerpop.gremlin.process.traversal.Step<?, ?>) mock(type)))
          .as(type.getName()).isTrue();
    }
    assertThat(RootStepAdmission.admits(__.path().asAdmin().getStartStep())).isFalse();
  }

  /** Type declines skip all execution-setting and extraction reads and invoke no map callback. */
  @Test
  public void typeDeclinesKeepNativeStepsWithoutSettingsExtractionWalkOrCache() {
    var support = new TranslatorEquivalenceSupport(this::graphSession);
    support.setTranslatorEnabled(true);
    var sharedCache = GremlinPlanCache.instance(graphSession());
    var callbacks = new AtomicInteger();
    var strategy = new GremlinToMatchStrategy(t -> {
      throw new AssertionError("type decline must not walk");
    }, (inputs, shaping, session) -> {
      throw new AssertionError("type decline must not plan");
    }, true);
    for (int call = 0; call < 3; call++) {
      var cache = call == 2 ? new GremlinPlanCache(0) : sharedCache;
      for (var traversal : List.of(graph.traversal().V().coalesce(__.values("name")),
          graph.traversal().V().optional(__.out()), graph.traversal().V().map(t -> {
            callbacks.incrementAndGet();
            return t.get();
          }), graph.traversal().V().path())) {
        var admin = traversal.asAdmin();
        var steps = List.copyOf(admin.getSteps());
        var metrics = GremlinTranslationMetrics.of(graphSession());
        long declines = metrics.getDeclines(), errors = metrics.getErrors();
        long hits = cache.getTranslationHits(), misses = cache.getTranslationMisses();
        try (var caches = mockStatic(GremlinPlanCache.class, CALLS_REAL_METHODS);
            var settings = mockStatic(YTDBStrategyUtil.class, invocation -> {
              if (List.of("orderIncludesMissingKey", "orderByNullsPlacements", "isPolymorphic")
                  .contains(invocation.getMethod().getName())) {
                throw new AssertionError("type decline resolved execution settings");
              }
              return CALLS_REAL_METHODS.answer(invocation);
            }); var walker = mockStatic(GremlinStepWalker.class, invocation -> {
              if (invocation.getMethod().getName().equals("extractShape")) {
                throw new AssertionError("type decline extracted shape");
              }
              return CALLS_REAL_METHODS.answer(invocation);
            })) {
          caches.when(() -> GremlinPlanCache.instance(graphSession())).thenReturn(cache);
          strategy.apply(admin);
        }
        assertThat(admin.getSteps()).containsExactlyElementsOf(steps);
        assertThat(metrics.getDeclines()).isEqualTo(declines + 1);
        assertThat(metrics.getErrors()).isEqualTo(errors);
        assertThat(cache.getTranslationHits()).isEqualTo(hits);
        assertThat(cache.getTranslationMisses()).isEqualTo(misses);
        assertThat(cache.containsTranslation(
            GremlinStepWalker.extractShape(admin, graphSession()).key())).isFalse();
      }
    }
    assertThat(callbacks.get()).isZero();
  }

  /** Bad explicit values retain their error route before reserved labels on either root shape. */
  @Test
  public void invalidOptionsPrecedeReservedLabelsAndNeverPublish() {
    new TranslatorEquivalenceSupport(this::graphSession).setTranslatorEnabled(true);
    for (var option : List.of(YTDBQueryConfigParam.orderIncludesMissingKey,
        YTDBQueryConfigParam.orderByNullsPlacementAsc,
        YTDBQueryConfigParam.orderByNullsPlacementDesc, YTDBQueryConfigParam.polymorphicQuery)) {
      for (boolean unsupported : List.of(false, true)) {
        for (boolean reserved : List.of(false, true)) {
          var traversal = graph.traversal().with(option.name(), "invalid").V();
          if (unsupported) {
            traversal.path();
          }
          if (reserved) {
            traversal.as("$reserved");
          }
          var metrics = GremlinTranslationMetrics.of(graphSession());
          long errors = metrics.getErrors(), declines = metrics.getDeclines();
          GremlinToMatchStrategy.instance().apply(traversal.asAdmin());
          assertThat(metrics.getErrors()).isEqualTo(errors + 1);
          assertThat(metrics.getDeclines()).isEqualTo(declines);
          var options = traversal.asAdmin().getStrategies().getStrategy(OptionsStrategy.class)
              .orElseThrow();
          if (option == YTDBQueryConfigParam.orderIncludesMissingKey) {
            assertThatThrownBy(() -> YTDBStrategyUtil.explicitOrderIncludesMissingKey(
                options, traversal.asAdmin())).isInstanceOf(ClassCastException.class);
          } else if (option == YTDBQueryConfigParam.polymorphicQuery) {
            assertThatThrownBy(() -> YTDBStrategyUtil.explicitPolymorphic(options))
                .isInstanceOf(ClassCastException.class);
          } else {
            assertThatThrownBy(() -> YTDBStrategyUtil.explicitNullPlacements(options))
                .isInstanceOf(IllegalArgumentException.class);
          }
        }
      }
    }
  }

  /** Lexical labels anywhere in the root and order contradictions throw without telemetry. */
  @Test
  public void deliberateErrorsDoNotRecordOutcomes() {
    new TranslatorEquivalenceSupport(this::graphSession).setTranslatorEnabled(true);
    for (var traversal : List.of(graph.traversal().V().as("$before").path(),
        graph.traversal().V().path().as("$after"),
        graph.traversal().V().path().barrier().as("$barrier"))) {
      var metrics = GremlinTranslationMetrics.of(graphSession());
      long attempts = metrics.getAttempts();
      assertThatThrownBy(() -> GremlinToMatchStrategy.instance().apply(traversal.asAdmin()))
          .isInstanceOf(ReservedAliasException.class);
      assertThat(metrics.getAttempts()).isEqualTo(attempts);
    }
    var traversal =
        graph.traversal().with(YTDBQueryConfigParam.orderIncludesMissingKey.name(), true)
            .withStrategies(StandardOrderSemanticsStrategy.instance()).V().path().asAdmin();
    long attempts = GremlinTranslationMetrics.of(graphSession()).getAttempts();
    assertThatThrownBy(() -> GremlinToMatchStrategy.instance().apply(traversal))
        .isInstanceOf(ContradictoryOrderSemanticsException.class);
    assertThat(GremlinTranslationMetrics.of(graphSession()).getAttempts()).isEqualTo(attempts);
  }

  /** Child-only types and sack configuration stay on extraction and walk rather than the proof. */
  @Test
  public void proofDoesNotApplyRootGrammarToChildrenOrSack() {
    new TranslatorEquivalenceSupport(this::graphSession).setTranslatorEnabled(true);
    var walks = new AtomicInteger();
    var strategy = new GremlinToMatchStrategy(t -> {
      walks.incrementAndGet();
      return null;
    });
    strategy.apply(graph.traversal().V().where(__.is(1)).asAdmin());
    strategy.apply(graph.traversal().withSack(1).V().asAdmin());
    assertThat(walks.get()).isEqualTo(2);
  }

  private com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded graphSession() {
    var tx = (com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction) graph.tx();
    tx.readWrite();
    return tx.getDatabaseSession();
  }

  /** ON and OFF keep results for type declines, supported sack routing and the repeat veto. */
  @Test
  public void declinesAreEquivalentWithTranslatorOnAndOff() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    alice.addEdge("link", alice);
    graph.tx().commit();
    var support = new TranslatorEquivalenceSupport(this::graphSession);
    for (int kind = 0; kind < 6; kind++) {
      int shape = kind;
      support.assertEquivalent("route " + kind, shape == 4
          ? TranslatorEquivalenceSupport.Recognition.RECOGNIZED
          : TranslatorEquivalenceSupport.Recognition.DECLINED,
          TranslatorEquivalenceSupport.Cardinality.NON_EMPTY,
          rows -> rows.stream().map(Object::toString).toList(), () -> {
            var source = graph.traversal().V();
            return switch (shape) {
              case 0 -> source.coalesce(__.values("name"));
              case 1 -> source.optional(__.out()).values("name");
              case 2 -> source.map(t -> t.get().value("name"));
              case 3 -> source.values("name").path();
              case 4 -> graph.traversal().withSack(1).V().order().by("name")
                  .limit(1).out("link").values("name");
              default -> source.repeat(__.identity()).times(2).values("name");
            };
          });
    }
  }
}
