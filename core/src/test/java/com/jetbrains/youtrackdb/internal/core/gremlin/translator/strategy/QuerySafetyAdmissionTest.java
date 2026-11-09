package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.PBiPredicate;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.TraversalParent;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.AbstractStep;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Rule;
import org.junit.Test;

/** Checks both the immutable admission evidence and the installed execution path. */
public class QuerySafetyAdmissionTest extends GraphBaseTest {

  @Rule
  public final ForceMatchTranslationRule forceMatch = new ForceMatchTranslationRule();

  private final TranslatorEquivalenceSupport support =
      new TranslatorEquivalenceSupport(() -> session);

  private void refused(Supplier<GraphTraversal<?, ?>> shape, QuerySafetyAdmission.Reason reason) {
    support.withTranslator(true, () -> {
      var traversal = shape.get().asAdmin();
      traversal.applyStrategies();
      assertThat(QuerySafetyAdmission.stored(traversal).reason()).isEqualTo(reason);
      assertNoBoundary(traversal);
      assertThat(GremlinTranslationMetrics.of(session).topDeclinedShapes(100))
          .extracting(GremlinTranslationMetrics.ShapeCount::shape)
          .contains("safety:" + reason.name());
    });
  }

  private static void assertNoBoundary(Traversal.Admin<?, ?> traversal) {
    assertThat(TranslatorEquivalenceSupport.countBoundarySteps(traversal)).isZero();
    for (var step : traversal.getSteps()) {
      if (step instanceof TraversalParent parent) {
        parent.getLocalChildren().forEach(QuerySafetyAdmissionTest::assertNoBoundary);
        parent.getGlobalChildren().forEach(QuerySafetyAdmissionTest::assertNoBoundary);
      }
    }
  }

  /** Writes at the root and in both child scopes suppress even independent vertex children. */
  @Test
  public void writesInEveryScopeVetoTheWholeTree() {
    refused(() -> graph.traversal().addV("PrivateLiteral"), QuerySafetyAdmission.Reason.WRITE);
    refused(() -> graph.traversal().V().local(__.addV("PrivateLiteral")),
        QuerySafetyAdmission.Reason.WRITE);
    refused(() -> graph.traversal().V().union(__.V(), __.addV("PrivateLiteral")),
        QuerySafetyAdmission.Reason.WRITE);
    refused(() -> graph.traversal().V().drop(), QuerySafetyAdmission.Reason.WRITE);
    refused(() -> graph.traversal().V().property("private", "literal"),
        QuerySafetyAdmission.Reason.WRITE);
  }

  /** Service names need not exist. Admission never resolves or starts their services. */
  @Test
  public void serviceAndUnmarkedSideEffectsVetoByKind() {
    support.withTranslator(true, () -> {
      var service = graph.traversal().V().asAdmin();
      service.addStep(new org.apache.tinkerpop.gremlin.process.traversal.step.map.CallStep<>(
          service, false, "not-a-service-private-literal"));
      // Drive admission directly. The fluent builder validates the registry before admission.
      GremlinToMatchStrategy.instance().apply(service);
      assertThat(QuerySafetyAdmission.stored(service).reason())
          .isEqualTo(QuerySafetyAdmission.Reason.SIDE_EFFECT);
      assertNoBoundary(service);
    });
    refused(() -> graph.traversal().V().aggregate("private"),
        QuerySafetyAdmission.Reason.SIDE_EFFECT);
    refused(() -> graph.traversal().V().sideEffect(__.V().count()),
        QuerySafetyAdmission.Reason.SIDE_EFFECT);
  }

  /** Admission inspects user functions without evaluating them, including deferred-hop filters. */
  @Test
  public void functionsPredicatesComparatorsAndSeededFoldsNeverRunDuringAdmission() {
    var calls = new AtomicInteger();
    PBiPredicate<Object, Object> predicate = (a, b) -> {
      calls.incrementAndGet();
      return true;
    };
    refused(() -> graph.traversal().V().map(t -> {
      calls.incrementAndGet();
      return t.get();
    }), QuerySafetyAdmission.Reason.USER_FUNCTION);
    refused(() -> graph.traversal().V().has("name", new P<>(predicate, "private")),
        QuerySafetyAdmission.Reason.USER_FUNCTION);
    refused(() -> graph.traversal().V().order().by("name").out("link")
        .has("name", new P<>(predicate, "private")).limit(1),
        QuerySafetyAdmission.Reason.USER_FUNCTION);
    refused(() -> graph.traversal().V().order().by("name", (a, b) -> {
      calls.incrementAndGet();
      return 0;
    }).out("link"), QuerySafetyAdmission.Reason.USER_FUNCTION);
    refused(() -> graph.traversal().V().fold(new ArrayList<>(), (a, b) -> {
      calls.incrementAndGet();
      return a;
    }), QuerySafetyAdmission.Reason.USER_FUNCTION);
    assertThat(calls.get()).isZero();
  }

  /** Unknown step subclasses are not trusted because their base step is normally pure. */
  @Test
  public void unknownBehaviorFailsClosed() {
    refused(() -> {
      var traversal = graph.traversal().V();
      traversal.asAdmin().addStep(new AbstractStep<>(traversal.asAdmin()) {
        @Override
        protected org.apache.tinkerpop.gremlin.process.traversal.Traverser.Admin<Object>
            processNextStart() {
          throw new AssertionError("admission must not execute a step");
        }
      });
      return traversal;
    }, QuerySafetyAdmission.Reason.UNKNOWN_BEHAVIOR);
  }

  /** A duplicate binding anywhere in the tree and a selected side-effect key are root vetoes. */
  @Test
  public void nameConflictsSuppressIndependentChildren() {
    refused(() -> graph.traversal().V().as("same")
        .union(__.V().as("same"), __.V()),
        QuerySafetyAdmission.Reason.DUPLICATE_LABEL);
    refused(() -> graph.traversal().withSideEffect("secret", 1).V().as("secret")
        .select("secret"), QuerySafetyAdmission.Reason.SIDE_EFFECT_NAME);
  }

  /** A later reserved label remains an input error even after an unsafe step was seen. */
  @Test
  public void deliberateInputErrorsPrecedeSafetyRefusal() {
    support.withTranslator(true,
        () -> assertThatThrownBy(() -> graph.traversal().V().map(t -> t.get()).as("$private")
            .asAdmin().applyStrategies())
            .isInstanceOf(ReservedAliasException.class));
  }

  /** A child cannot manufacture root evidence when it is prepared out of order. */
  @Test
  public void childWithMissingRootEvidenceRemainsNative() {
    var child = __.V().count().asAdmin();
    var root = graph.traversal().inject(1).flatMap(child).asAdmin();
    child.setGraph(graph);
    support.withTranslator(true, () -> GremlinToMatchStrategy.instance().apply(child));
    assertThat(QuerySafetyAdmission.stored(root)).isNull();
    assertNoBoundary(child);
  }

  /** A warm matching shape cannot reuse a cached plan after a side-effect key changes selection. */
  @Test
  public void vetoRunsBeforeWarmCacheReuse() {
    graph.addVertex(T.label, "SafetyVertex", "name", "a");
    graph.tx().commit();
    support.withTranslator(true, () -> {
      for (int round = 0; round < 2; round++) {
        var safe = graph.traversal().V().hasLabel("SafetyVertex").as("x").select("x")
            .asAdmin();
        safe.applyStrategies();
        // Both cold and warm invocations inspect GraphStep, HasStep and SelectOneStep once.
        assertThat(QuerySafetyAdmission.stored(safe).visitedSteps()).isEqualTo(3);
        assertThat(TranslatorEquivalenceSupport.countBoundarySteps(safe)).isEqualTo(1);
        assertThat(safe.toList()).hasSize(1);
      }
      var cache = GremlinPlanCache.instance(session);
      long hits = cache.getTranslationHits();
      refused(() -> graph.traversal().withSideEffect("x", 1).V().hasLabel("SafetyVertex")
          .as("x").select("x"), QuerySafetyAdmission.Reason.SIDE_EFFECT_NAME);
      assertThat(cache.getTranslationHits()).isEqualTo(hits);
    });
  }

  /** Repeated preparation and clones retain exactly the same immutable result and step count. */
  @Test
  public void oneWalkAndCloneOwnership() {
    var root = __.V().union(__.V().count(), __.V().count()).asAdmin();
    var result = QuerySafetyAdmission.prepare(root);
    assertThat(result.reason()).isEqualTo(QuerySafetyAdmission.Reason.SAFE);
    // Both union children include their native EndStep.
    assertThat(result.visitedSteps()).isEqualTo(8);
    assertThat(QuerySafetyAdmission.prepare(root)).isSameAs(result);
    var clone = root.clone();
    assertThat(QuerySafetyAdmission.prepare(clone)).isSameAs(result);
    root.lock();
    var parent = (TraversalParent) root.getEndStep();
    assertThat(QuerySafetyAdmission.stored(parent.getGlobalChildren().getFirst()))
        .isSameAs(result);
  }

  /** An unexpected inspection failure stores a veto before native fallback reaches descendants. */
  @Test
  public void inspectionFailureIsStickyAndChildNeverRescansRoot() {
    var child = __.V().count().asAdmin();
    var root = graph.traversal().V().map(child).asAdmin();
    var broken = org.mockito.Mockito.spy(root);
    org.mockito.Mockito.when(broken.getSteps()).thenThrow(new IllegalStateException("private"));
    assertThatThrownBy(() -> QuerySafetyAdmission.prepare(broken))
        .isInstanceOf(IllegalStateException.class);
    assertThat(QuerySafetyAdmission.stored(broken).reason())
        .isEqualTo(QuerySafetyAdmission.Reason.ADMISSION_FAILURE);
    assertThat(QuerySafetyAdmission.prepare(broken).reason())
        .isEqualTo(QuerySafetyAdmission.Reason.ADMISSION_FAILURE);
    org.mockito.Mockito.verify(broken, org.mockito.Mockito.times(1)).getSteps();

    var descendant = __.V().count().asAdmin();
    var safe = graph.traversal().inject(1).flatMap(descendant).asAdmin();
    var observed = org.mockito.Mockito.spy(safe);
    var parent = org.mockito.Mockito.spy(safe.getEndStep());
    org.mockito.Mockito.doReturn(observed).when(parent).getTraversal();
    descendant.setParent((TraversalParent) parent);
    var result = QuerySafetyAdmission.prepare(observed);
    org.mockito.Mockito.verify(observed, org.mockito.Mockito.times(1)).getSteps();
    org.mockito.Mockito.doThrow(new AssertionError("child rescanned the root"))
        .when(observed).getSteps();
    assertThat(QuerySafetyAdmission.prepare(descendant)).isSameAs(result);
  }

  /** Concurrent independent preparations never share the mutable scan or change global strategies. */
  @Test
  public void parallelPreparationsOwnTheirEvidence() {
    var futures = new ArrayList<CompletableFuture<QuerySafetyAdmission.Result>>();
    for (int i = 0; i < 16; i++) {
      final boolean unsafe = i % 2 == 0;
      futures.add(CompletableFuture.supplyAsync(() -> QuerySafetyAdmission.prepare(
          unsafe ? __.V().drop().asAdmin() : __.V().count().asAdmin())));
    }
    for (int i = 0; i < futures.size(); i++) {
      assertThat(futures.get(i).join().reason()).isEqualTo(i % 2 == 0
          ? QuerySafetyAdmission.Reason.WRITE : QuerySafetyAdmission.Reason.SAFE);
    }
    assertThat(QuerySafetyAdmission.stored(__.V().asAdmin())).isNull();
  }

  /** Built-in modulators and value-producing aggregates remain pure rather than named effects. */
  @Test
  public void ordinaryBuiltinsRemainAdmitted() {
    for (var traversal : List.of(__.V().groupCount(), __.V().group().by("name"),
        __.V().order().by("name"), __.V().fold())) {
      assertThat(QuerySafetyAdmission.prepare(traversal.asAdmin()).reason())
          .isEqualTo(QuerySafetyAdmission.Reason.SAFE);
    }
  }
}
