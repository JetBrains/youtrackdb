package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.apache.tinkerpop.gremlin.process.traversal.P.neq;
import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.TraversalParent;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.WherePredicateStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.PropertyMapStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.LazyBarrierStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.PathRetractionStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.TraverserRequirement;
import org.apache.tinkerpop.gremlin.structure.PropertyType;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Test;

public class PropertyMapBulkingTest extends GraphBaseTest {

  private final TranslatorEquivalenceSupport support =
      new TranslatorEquivalenceSupport(this::graphSession);

  /** Native two-hop barriers bulk equal vertices before either map, not equal property values. */
  @Test
  public void vertexMapsBuildOncePerIdentityAndPropagateBulkToBothConsumers() {
    seed();
    for (boolean elementMap : List.of(false, true)) {
      Supplier<GraphTraversal<?, ?>> shape = () -> {
        var walk = graph.traversal().V().hasLabel("BulkSource").out("first").out("second");
        return elementMap ? walk.elementMap("name", "absent", "nullable")
            : walk.valueMap("name", "absent", "nullable");
      };
      support.withTranslator(false, () -> {
        var nativeWalk = shape.get().asAdmin();
        nativeWalk.applyStrategies();
        assertThat(nativeWalk.getEndStep().getPreviousStep()).isInstanceOf(NoOpBarrierStep.class);
        assertThat(((NoOpBarrierStep<?>) nativeWalk.getEndStep().getPreviousStep())
            .getMaxBarrierSize()).isEqualTo(2500);
      });
      assertMapsAndBulk(shape, 4, 2, List.of(2L, 2L));
    }
  }

  /** Edge-ending maps stay native, with and without an explicit barrier, retaining all arrivals. */
  @Test
  public void edgeValueAndElementMapsStayNativeWithMultiplicity() {
    seed();
    for (boolean explicitBarrier : List.of(false, true)) {
      for (boolean elementMap : List.of(false, true)) {
        Supplier<GraphTraversal<?, ?>> shape = () -> {
          var walk = graph.traversal().V().hasLabel("BulkSource").out("first").outE("second");
          if (explicitBarrier) {
            walk.barrier(2500);
          }
          return elementMap ? walk.elementMap("weight") : walk.valueMap("weight");
        };
        assertThat(PropertyMapBulkingAdmission.capacity(shape.get().asAdmin())).isZero();
        support.withTranslatorRestored(() -> {
          support.setTranslatorEnabled(false);
          var nativeRows = shape.get().toList();
          assertThat(nativeRows).hasSize(4);
          support.setTranslatorEnabled(true);
          var admin = shape.get().asAdmin();
          admin.applyStrategies();
          assertThat(TranslatorEquivalenceSupport.countBoundarySteps(admin)).isZero();
          assertThat(PropertyMapBulkingAdmission.capacity(admin)).isZero();
          assertThat(new ArrayList<Object>(admin.toList()))
              .containsExactlyElementsOf(nativeRows);
        });
      }
    }
  }

  /** A translated edge selected from a closed hop stays ungrouped even at an explicit barrier. */
  @Test
  public void selectedEdgeMapsTranslateButRefuseBulking() {
    seed();
    for (boolean elementMap : List.of(false, true)) {
      Supplier<GraphTraversal<?, ?>> shape = () -> {
        var walk = graph.traversal().V().hasLabel("BulkSource").out("first")
            .outE("second").as("edge").inV().select("edge").barrier(2500);
        return elementMap ? walk.elementMap("weight") : walk.valueMap("weight");
      };
      assertThat(PropertyMapBulkingAdmission.capacity(shape.get().asAdmin())).isZero();
      support.withTranslatorRestored(() -> {
        support.setTranslatorEnabled(false);
        var nativeRows = shape.get().toList();
        assertThat(nativeRows).hasSize(4);
        support.setTranslatorEnabled(true);
        var admin = shape.get().asAdmin();
        admin.applyStrategies();
        assertThat(admin.getStartStep()).isInstanceOf(YTDBMatchPlanStep.class);
        var boundary = (YTDBMatchPlanStep<?, ?>) admin.getStartStep();
        assertThat(boundary.getPropertyMapBulkingCapacity()).isZero();
        assertThat(new ArrayList<Object>(admin.toList()))
            .containsExactlyInAnyOrderElementsOf(nativeRows);
      });
    }
  }

  /** Empty and singleton feeds still engage and allocate zero and one maps respectively. */
  @Test
  public void emptyAndSingletonAdmittedFeeds() {
    seed();
    assertMapsAndBulk(() -> graph.traversal().V().hasLabel("BulkSource")
        .out("missing").out("second").barrier(2500).valueMap("name"), 0, 0, List.of());
    assertMapsAndBulk(() -> graph.traversal().V().hasLabel("BulkSource")
        .out("first").out("missing").barrier(2500).elementMap("name"),
        0, 0, List.of());
    var source = graph.addVertex(T.label, "SingleSource");
    var mid = graph.addVertex(T.label, "SingleMid");
    var target = graph.addVertex(T.label, "SingleTarget", "name", "single");
    source.addEdge("first", mid);
    mid.addEdge("second", target);
    graph.tx().commit();
    for (boolean elementMap : List.of(false, true)) {
      assertMapsAndBulk(() -> {
        var walk = graph.traversal().V().hasLabel("SingleSource")
            .out("first").out("second").barrier(2500);
        return elementMap ? walk.elementMap("name") : walk.valueMap("name");
      }, 1, 1, List.of(1L));
    }
  }

  /** A final has filter has no adjacent native barrier, so both empty and nonempty feeds bypass G6. */
  @Test
  public void hasBeforeMapWithoutAdjacentBarrierKeepsUngroupedResults() {
    seed();
    for (String name : List.of("same", "missing")) {
      Supplier<GraphTraversal<?, ?>> shape = () -> graph.traversal().V().hasLabel("BulkSource")
          .out("first").out("second").has("name", name).elementMap("name");
      support.withTranslatorRestored(() -> {
        support.setTranslatorEnabled(false);
        var nativeRows = shape.get().toList();
        assertThat(nativeRows).hasSize(name.equals("same") ? 4 : 0);
        support.setTranslatorEnabled(true);
        var admin = shape.get().asAdmin();
        admin.applyStrategies();
        assertThat(admin.getStartStep()).isInstanceOf(YTDBMatchPlanStep.class);
        var boundary = (YTDBMatchPlanStep<?, ?>) admin.getStartStep();
        assertThat(boundary.getPropertyMapBulkingCapacity()).isZero();
        assertThat(new ArrayList<Object>(admin.toList()))
            .containsExactlyInAnyOrderElementsOf(nativeRows);
      });
    }
  }

  /** Admission needs a vertex output, a bounded adjacent barrier and a plain property map. */
  @Test
  public void admissionChecksOutputTypeBarrierBoundsAndChildMaps() {
    var refused = List.<GraphTraversal<?, ?>>of(
        __.start().barrier(2500).valueMap("name"),
        graph.traversal().E().barrier(2500).elementMap("weight"),
        graph.traversal().V().valueMap("name"),
        graph.traversal().V().barrier(2500).id(),
        graph.traversal().V().out().valueMap("name"),
        graph.traversal().V().barrier(2501).valueMap("name"),
        graph.traversal().V().barrier(0).valueMap("name"),
        graph.traversal().V().barrier(1).out().barrier(2500).valueMap("name"),
        graph.traversal().V().outE().barrier(2500).valueMap("weight"),
        graph.traversal().V().outE().has("weight", 1).barrier(2500).elementMap("weight"),
        graph.traversal().V().barrier(2500).valueMap("name").by(__.unfold()));
    for (var shape : refused) {
      assertThat(PropertyMapBulkingAdmission.capacity(shape.asAdmin()))
          .as("admission for %s", shape).isZero();
    }
    assertThat(PropertyMapBulkingAdmission.capacity(
        graph.traversal().V().barrier(1).valueMap("name").asAdmin())).isEqualTo(1);
    assertThat(PropertyMapBulkingAdmission.capacity(graph.traversal().V().outE().inV()
        .barrier(2500).elementMap("name").asAdmin())).isEqualTo(2500);
  }

  /** Exact native-map shortcuts must not bypass extra requirements or children on subclasses. */
  @Test
  public void propertyMapSubclassKeepsTheSameRequirementsAndChildDecisions() {
    for (var requirement : List.of(TraverserRequirement.OBJECT, TraverserRequirement.PATH,
        TraverserRequirement.LABELED_PATH, TraverserRequirement.SACK,
        TraverserRequirement.ONE_BULK)) {
      var raw = graph.traversal().V().out().barrier(2500).valueMap("name").asAdmin();
      raw.removeStep(raw.getSteps().size() - 1);
      raw.addStep(new PropertyMapStep<>(raw, PropertyType.VALUE, "name") {
        @Override
        public Set<TraverserRequirement> getRequirements() {
          return Set.of(requirement);
        }
      });
      assertThat(PropertyMapBulkingAdmission.capacity(raw)).as("%s", requirement)
          .isEqualTo(requirement == TraverserRequirement.OBJECT ? 2500 : 0);
    }
    var raw = graph.traversal().V().out().barrier(2500).valueMap("name").asAdmin();
    raw.removeStep(raw.getSteps().size() - 1);
    raw.addStep(new PropertyMapStep<>(raw, PropertyType.VALUE, "name") {
      @Override
      public List getLocalChildren() {
        return List.of(__.identity().asAdmin());
      }
    });
    assertThat(PropertyMapBulkingAdmission.capacity(raw)).isZero();
  }

  /** Labels, path, sack and ONE_BULK cannot select G6, including direct pre-decoration calls. */
  @Test
  public void observableStateAndUnprovedOrderKeepOldPath() {
    seed();
    var shapes = List.<Supplier<GraphTraversal<?, ?>>>of(
        () -> graph.traversal().V().hasLabel("BulkSource").as("a")
            .out("first").out("second").valueMap("name"),
        () -> graph.traversal().withPath().V().hasLabel("BulkSource")
            .out("first").out("second").valueMap("name"),
        () -> graph.traversal().withSack(1).V().hasLabel("BulkSource")
            .out("first").out("second").valueMap("name"),
        () -> graph.traversal().withBulk(false).V().hasLabel("BulkSource")
            .out("first").out("second").valueMap("name"),
        () -> graph.traversal().V().hasLabel("BulkSource").out("first").out("second")
            .order().by("name").valueMap("name"),
        () -> graph.traversal().V().hasLabel("BulkSource").out("first").out("second")
            .valueMap("name").fold());
    for (var shape : shapes) {
      assertThat(PropertyMapBulkingAdmission.capacity(shape.get().asAdmin())).isZero();
      support.withTranslator(true, () -> {
        var admin = shape.get().asAdmin();
        admin.applyStrategies();
        for (var step : admin.getSteps()) {
          if (step instanceof YTDBMatchPlanStep<?, ?> boundary) {
            assertThat(boundary.getPropertyMapBulkingCapacity()).isZero();
          }
        }
      });
      support.assertEquivalent("state refuses bulking", recognition(shape),
          TranslatorEquivalenceSupport.Cardinality.NON_EMPTY,
          TranslatorEquivalenceSupport::sortedStrings, shape);
    }
  }

  /** An otherwise admissible adjacent barrier must not group labelled vertex arrivals. */
  @Test
  public void labelsRefuseBulkingAtAdjacentBarrier() {
    seed();
    assertStateRefusesAtBarrier(() -> graph.traversal().V().hasLabel("BulkSource").as("a")
        .out("first").out("second").barrier(2500).valueMap("name"),
        TranslatorEquivalenceSupport.Recognition.RECOGNIZED);
  }

  /** An explicit path requirement refuses G6 even when a bounded barrier precedes the map. */
  @Test
  public void pathRefusesBulkingAtAdjacentBarrier() {
    seed();
    assertStateRefusesAtBarrier(() -> graph.traversal().withPath().V().hasLabel("BulkSource")
        .out("first").out("second").barrier(2500).valueMap("name"),
        TranslatorEquivalenceSupport.Recognition.DECLINED);
  }

  /** ONE_BULK refuses G6 at an otherwise admissible barrier without losing native rows. */
  @Test
  public void oneBulkRefusesBulkingAtAdjacentBarrier() {
    seed();
    assertStateRefusesAtBarrier(() -> graph.traversal().withBulk(false).V().hasLabel("BulkSource")
        .out("first").out("second").barrier(2500).valueMap("name"),
        TranslatorEquivalenceSupport.Recognition.DECLINED);
  }

  /** Sack state keeps the translated boundary ungrouped despite its adjacent bounded barrier. */
  @Test
  public void sackRefusesBulkingAtAdjacentBarrier() {
    seed();
    assertStateRefusesAtBarrier(() -> graph.traversal().withSack(1).V().hasLabel("BulkSource")
        .out("first").out("second").barrier(2500).valueMap("name"),
        TranslatorEquivalenceSupport.Recognition.RECOGNIZED);
  }

  /**
   * A flatMap child inherits root ONE_BULK. Its translated, ungrouped boundary must emit both
   * parallel-edge arrivals rather than asking a one-bulk generator to carry a grouped count.
   */
  @Test
  public void nestedOneBulkKeepsUngroupedTranslationAndBothArrivals() {
    var source = graph.addVertex(T.label, "NestedBulkSource");
    var mid = graph.addVertex(T.label, "NestedBulkMid");
    var target = graph.addVertex(T.label, "NestedBulkTarget", "name", "a");
    source.addEdge("first", mid);
    source.addEdge("first", mid);
    mid.addEdge("second", target);
    graph.tx().commit();
    support.withTranslator(true, () -> {
      var child = __.V().hasLabel("NestedBulkSource").out("first").out("second")
          .barrier(2500).valueMap("name").asAdmin();
      // Prove the child shape qualifies before attachment, so only inherited context refuses it.
      assertThat(PropertyMapBulkingAdmission.capacity(child)).isEqualTo(2500);
      var admin = graph.traversal().withBulk(false).inject(1).flatMap(child).asAdmin();
      assertThat(child.isRoot()).isFalse();
      assertThat(PropertyMapBulkingAdmission.capacity(child)).isZero();
      admin.applyStrategies();
      assertThat(TranslatorEquivalenceSupport.countBoundarySteps(admin)).isZero();
      var parent = (TraversalParent) admin.getStartStep().getNextStep();
      var translatedChild = parent.getLocalChildren().getFirst();
      assertThat(TranslatorEquivalenceSupport.countBoundarySteps(translatedChild)).isEqualTo(1);
      assertThat(translatedChild.getStartStep()).isInstanceOf(YTDBMatchPlanStep.class);
      var boundary = (YTDBMatchPlanStep<?, ?>) translatedChild.getStartStep();
      assertThat(boundary.getPropertyMapBulkingCapacity()).isZero();
      assertThat(new ArrayList<Object>(admin.toList()))
          .containsExactly(Map.of("name", List.of("a")), Map.of("name", List.of("a")));
    });
  }

  /** Both cache layers distinguish admitted shaping from sack-refused identical step structure. */
  @Test
  public void coldAndWarmCachesSeparateBulkingInEitherOrder() {
    seed();
    for (boolean direct : List.of(false, true)) {
      for (boolean refusedFirst : List.of(false, true)) {
        var cache = GremlinPlanCache.instance(graphSession());
        cache.invalidate();
        String admittedKey = null;
        String refusedKey = null;
        Object admittedPlan = null;
        Object refusedPlan = null;
        for (boolean refused : List.of(refusedFirst, !refusedFirst, refusedFirst, !refusedFirst)) {
          var source = refused ? graph.traversal().withSack(1) : graph.traversal();
          var admin = source.V().hasLabel("BulkSource").out("first").out("second")
              .barrier(2500).valueMap("name").asAdmin();
          // One explicit final barrier suppresses native insertion at the same position.
          var extraction = GremlinStepWalker.extractShape(admin, graphSession());
          var translation = GremlinStepWalker.production().walk(admin);
          assertThat(translation).isNotNull();
          assertThat(extraction.propertyMapBulkingCapacity())
              .isEqualTo(translation.shaping().propertyMapBulkingCapacity());
          var fingerprint = GremlinPlanFingerprint.fingerprint(translation.inputs(),
              translation.shaping());
          var offFingerprint = GremlinPlanFingerprint.fingerprint(translation.inputs(),
              translation.shaping().withPropertyMapBulkingCapacity(0));
          if (!refused) {
            assertThat(fingerprint).isNotEqualTo(offFingerprint);
            admittedKey = extraction.key();
          } else {
            refusedKey = extraction.key();
          }
          support.setTranslatorEnabled(true);
          if (direct) {
            GremlinToMatchStrategy.instance().apply(admin);
          } else {
            admin.applyStrategies();
          }
          assertThat(admin.getStartStep()).isInstanceOf(YTDBMatchPlanStep.class);
          var boundary = (YTDBMatchPlanStep<?, ?>) admin.getStartStep();
          assertThat(boundary.getPropertyMapBulkingCapacity()).isEqualTo(refused ? 0 : 2500);
          var plan = boundary.getPlan();
          if (refused) {
            if (refusedPlan != null) {
              assertThat(plan).isSameAs(refusedPlan);
            }
            refusedPlan = plan;
          } else {
            if (admittedPlan != null) {
              assertThat(plan).isSameAs(admittedPlan);
            }
            admittedPlan = plan;
          }
          assertThat(admin.toList()).hasSize(4);
        }
        assertThat(admittedKey).isNotEqualTo(refusedKey);
        assertThat(admittedPlan).isNotSameAs(refusedPlan);
      }
    }
  }

  /** A missing lazy strategy leaves the same query translated but ungrouped, without a G6 switch. */
  @Test
  public void noNativeBarrierKeepsUngroupedMultiplicity() {
    seed();
    support.withTranslator(true, () -> {
      var admin = graph.traversal().withoutStrategies(LazyBarrierStrategy.class)
          .V().hasLabel("BulkSource").out("first").out("second").valueMap("name").asAdmin();
      admin.applyStrategies();
      var boundary = (YTDBMatchPlanStep<?, ?>) admin.getStartStep();
      assertThat(boundary.getPropertyMapBulkingCapacity()).isZero();
      assertThat(admin.toList()).hasSize(4);
    });
  }

  /** Two different intermediate friends reach equal-property vertices after labels retract. */
  @Test
  public void crossLabelWhereRetractsRootAndIntermediateLabelsBeforeBothMaps() {
    seed();
    var source = graph.traversal().V().hasLabel("BulkSource").next();
    var other = graph.addVertex(T.label, "BulkMid");
    source.addEdge("first", other);
    for (var target : graph.traversal().V().hasLabel("BulkTarget").toList()) {
      other.addEdge("second", target);
    }
    graph.tx().commit();
    for (boolean intermediate : List.of(false, true)) {
      for (boolean elementMap : List.of(false, true)) {
        Supplier<GraphTraversal<?, ?>> shape = () -> {
          var walk = graph.traversal().V().hasLabel("BulkSource").as("a").out("first");
          if (intermediate) {
            walk.as("friend");
          }
          walk.out("second").where(neq("a"));
          return elementMap ? walk.elementMap("name", "absent", "nullable")
              : walk.valueMap("name", "absent", "nullable");
        };
        support.withTranslator(false, () -> {
          var nativeWalk = shape.get().asAdmin();
          nativeWalk.applyStrategies();
          var barrier = (NoOpBarrierStep<?>) nativeWalk.getEndStep().getPreviousStep();
          var traverser = barrier.next();
          assertThat(traverser.path().isEmpty()).isTrue();
          assertThat(traverser.bulk()).isEqualTo(3);
        });
        assertMapsAndBulk(shape, 6, 2, List.of(3L, 3L));
      }
    }
  }

  /** Several labelled q9 roots share endpoints through its four-hop direction sequence. */
  @Test
  public void q9RootsRetractBeforeGroupingSharedEndpoints() {
    var target = graph.addVertex(T.label, "Q9Target", "name", "shared");
    var org = graph.addVertex(T.label, "Q9Org");
    var person = graph.addVertex(T.label, "Q9Person");
    org.addEdge("HAS_CREATOR", target);
    org.addEdge("WORK_AT", person);
    for (int i = 0; i < 3; i++) {
      var post = graph.addVertex(T.label, "Q9Post");
      var forum = graph.addVertex(T.label, "Q9Forum");
      post.addEdge("CONTAINER_OF", forum);
      person.addEdge("LIKES", forum);
    }
    graph.tx().commit();
    assertMapsAndBulk(() -> graph.traversal().V().hasLabel("Q9Post").as("a")
        .out("CONTAINER_OF").in("LIKES").in("WORK_AT").out("HAS_CREATOR")
        .where(neq("a")).valueMap("name"), 3, 1, List.of(3L));
  }

  /** The native keep-label fact, not only step structure, separates both caches in either order. */
  @Test
  public void keepLabelFactsSeparateExtractionWalkerAndColdWarmCaches() {
    seed();
    support.withTranslator(true, () -> {
      for (boolean refusedFirst : List.of(false, true)) {
        GremlinPlanCache.instance(graphSession()).invalidate();
        var keys = new LinkedHashMap<Boolean, String>();
        var plans = new LinkedHashMap<Boolean, Object>();
        var fingerprints = new LinkedHashMap<Boolean, String>();
        for (boolean refused : List.of(refusedFirst, !refusedFirst, refusedFirst, !refusedFirst)) {
          var raw = graph.traversal().V().hasLabel("BulkSource").as("a")
              .out("first").out("second").where(neq("a"))
              .barrier(2500).valueMap("name").asAdmin();
          var where = (WherePredicateStep<?>) raw.getSteps().get(raw.getSteps().size() - 3);
          where.setKeepLabels(refused ? java.util.Set.of("a") : java.util.Set.of());
          var extraction = GremlinStepWalker.extractShape(raw, graphSession());
          var walked = GremlinStepWalker.production().walk(raw);
          assertThat(walked).isNotNull();
          int capacity = refused ? 0 : 2500;
          assertThat(PropertyMapBulkingAdmission.capacity(raw)).isEqualTo(capacity);
          assertThat(extraction.propertyMapBulkingCapacity()).isEqualTo(capacity);
          assertThat(walked.shaping().propertyMapBulkingCapacity()).isEqualTo(capacity);
          var fingerprint = GremlinPlanFingerprint.fingerprint(walked.inputs(), walked.shaping());
          keys.put(refused, extraction.key());
          fingerprints.put(refused, fingerprint);
          // Preserve the differing keep facts. applyStrategies would recompute both to empty.
          GremlinToMatchStrategy.instance().apply(raw);
          var boundary = (YTDBMatchPlanStep<?, ?>) raw.getStartStep();
          assertThat(boundary.getPropertyMapBulkingCapacity()).isEqualTo(capacity);
          if (plans.containsKey(refused)) {
            assertThat(boundary.getPlan()).isSameAs(plans.get(refused));
          }
          plans.put(refused, boundary.getPlan());
          assertThat(raw.toList()).hasSize(4);
          assertThat(boundary.mapConstructions()).isEqualTo(refused ? 0 : 2);
        }
        assertThat(keys.get(false)).isNotEqualTo(keys.get(true));
        assertThat(fingerprints.get(false)).isNotEqualTo(fingerprints.get(true));
        assertThat(plans.get(false)).isNotSameAs(plans.get(true));
      }
    });
  }

  /** Before strategies, null keep labels do not prove that a labelled path is empty. */
  @Test
  public void directWhereWithoutRetractionFactRefusesBulking() {
    var raw = labelledWhere();
    assertThat(PropertyMapBulkingAdmission.capacity(raw.asAdmin())).isZero();
  }

  /** Labels on where are added after its retraction, so the empty-path proof must refuse them. */
  @Test
  public void labelOnWhereRefusesAfterRetraction() {
    var raw = labelledWhere();
    retraction(raw, java.util.Set.of());
    raw.asAdmin().getSteps().get(raw.asAdmin().getSteps().size() - 3).addLabel("b");
    assertThat(PropertyMapBulkingAdmission.capacity(raw.asAdmin())).isZero();
  }

  /** Labels on the final barrier and map cannot be represented by an identity-only boundary. */
  @Test
  public void labelsOnBarrierAndMapRefuseBulking() {
    for (int offset : List.of(1, 2)) {
      var raw = labelledWhere();
      retraction(raw, java.util.Set.of());
      raw.asAdmin().getSteps().get(raw.asAdmin().getSteps().size() - offset).addLabel("b");
      assertThat(PropertyMapBulkingAdmission.capacity(raw.asAdmin())).isZero();
    }
  }

  /** A nonempty keep set leaves root state live even though the map itself reads no label. */
  @Test
  public void retainedWhereLabelsRefuseBulking() {
    var raw = labelledWhere();
    retraction(raw, java.util.Set.of("a"));
    assertThat(PropertyMapBulkingAdmission.capacity(raw.asAdmin())).isZero();
  }

  /** Where by children need another equivalence proof, even when their keep set is empty. */
  @Test
  public void modulatedWhereRefusesBulking() {
    var raw = graph.traversal().V().as("a").out().out()
        .where(neq("a")).by("name").barrier(2500).valueMap("name");
    retraction(raw, java.util.Set.of());
    assertThat(PropertyMapBulkingAdmission.capacity(raw.asAdmin())).isZero();
  }

  /** Traversal-form where, dedup, order and slices stay outside the admitted middle grammar. */
  @Test
  public void unsupportedMiddleStepsRefuseAtOtherwiseEligibleBarrier() {
    var shapes = List.<GraphTraversal<?, ?>>of(
        graph.traversal().V().out().where(__.out()).barrier(2500).valueMap("name"),
        graph.traversal().V().out().dedup().barrier(2500).valueMap("name"),
        graph.traversal().V().out().order().barrier(2500).valueMap("name"),
        graph.traversal().V().out().range(0, 2).barrier(2500).valueMap("name"),
        graph.traversal().V().out().barrier(2).out().barrier(2500).valueMap("name"));
    for (var raw : shapes) {
      assertThat(PropertyMapBulkingAdmission.capacity(raw.asAdmin())).as("%s", raw).isZero();
    }
  }

  /** Map children and suffix label/path readers must not inherit an admitted capacity. */
  @Test
  public void mapChildrenAndSuffixReadersRefuseBulking() {
    var child = labelledWhere().by(__.select("a"));
    retraction(child, java.util.Set.of());
    assertThat(PropertyMapBulkingAdmission.capacity(child.asAdmin())).isZero();
    for (var raw : List.of(labelledWhere().select("a"), labelledWhere().path())) {
      assertThat(PropertyMapBulkingAdmission.capacity(raw.asAdmin())).isZero();
    }
  }

  /**
   * A hop label added after empty retraction distinguishes two paths to one endpoint. Native and
   * translated barriers must retain two bulk-one arrivals rather than grouping by endpoint alone.
   */
  @Test
  public void intermediateLabelAfterRetractionRefusesBulkingAndPreservesBothArrivals() {
    var source = graph.addVertex(T.label, "LateSource");
    var gate = graph.addVertex(T.label, "LateGate");
    var left = graph.addVertex(T.label, "LateMid");
    var right = graph.addVertex(T.label, "LateMid");
    var target = graph.addVertex(T.label, "LateTarget", "name", "shared");
    source.addEdge("gate", gate);
    gate.addEdge("mid", left);
    gate.addEdge("mid", right);
    left.addEdge("end", target);
    right.addEdge("end", target);
    graph.tx().commit();
    Supplier<GraphTraversal<?, ?>> shape = () -> {
      // Keep the late label live. Automatic retraction would remove this distinguishing state.
      var raw = graph.traversal().withoutStrategies(LazyBarrierStrategy.class,
          PathRetractionStrategy.class).V().hasLabel("LateSource").as("a")
          .out("gate").where(neq("a")).out("mid").as("late")
          .out("end").barrier(2500).valueMap("name");
      raw.asAdmin().getSteps().stream().filter(s -> s instanceof WherePredicateStep<?>)
          .map(s -> (WherePredicateStep<?>) s).forEach(s -> s.setKeepLabels(java.util.Set.of()));
      return raw;
    };
    assertThat(PropertyMapBulkingAdmission.capacity(shape.get().asAdmin())).isZero();
    support.withTranslatorRestored(() -> {
      support.setTranslatorEnabled(false);
      var nativeWalk = shape.get().asAdmin();
      nativeWalk.applyStrategies();
      var barrier = (NoOpBarrierStep<?>) nativeWalk.getEndStep().getPreviousStep();
      var nativeBulks = new ArrayList<Long>();
      while (barrier.hasNext()) {
        var traverser = barrier.next();
        assertThat(traverser.path().hasLabel("late")).isTrue();
        nativeBulks.add(traverser.bulk());
      }
      assertThat(nativeBulks).containsExactly(1L, 1L);
      var nativeRows = new ArrayList<Object>(shape.get().toList());
      assertThat(nativeRows).containsExactly(
          Map.of("name", List.of("shared")), Map.of("name", List.of("shared")));
      support.setTranslatorEnabled(true);
      var translated = shape.get().asAdmin();
      translated.applyStrategies();
      assertThat(translated.getStartStep()).isInstanceOf(YTDBMatchPlanStep.class);
      assertThat(((YTDBMatchPlanStep<?, ?>) translated.getStartStep())
          .getPropertyMapBulkingCapacity()).isZero();
      var translatedBulks = new ArrayList<Long>();
      var expanded = new ArrayList<Object>();
      while (translated.hasNext()) {
        var traverser = translated.nextTraverser();
        translatedBulks.add(traverser.bulk());
        for (long i = 0; i < traverser.bulk(); i++) {
          expanded.add(traverser.get());
        }
      }
      assertThat(translatedBulks).containsExactlyElementsOf(nativeBulks);
      assertThat(expanded).containsExactlyElementsOf(nativeRows);
    });
  }

  private GraphTraversal<?, ?> labelledWhere() {
    return graph.traversal().V().hasLabel("BulkSource").as("a")
        .out("first").out("second").where(neq("a")).barrier(2500).valueMap("name");
  }

  private static void retraction(GraphTraversal<?, ?> raw, java.util.Set<String> keep) {
    ((WherePredicateStep<?>) raw.asAdmin().getSteps().get(raw.asAdmin().getSteps().size() - 3))
        .setKeepLabels(keep);
  }

  private void assertStateRefusesAtBarrier(Supplier<GraphTraversal<?, ?>> shape,
      TranslatorEquivalenceSupport.Recognition expectedRecognition) {
    var raw = shape.get().asAdmin();
    assertThat(raw.getEndStep().getPreviousStep()).isInstanceOf(NoOpBarrierStep.class);
    assertThat(PropertyMapBulkingAdmission.capacity(raw)).isZero();
    support.withTranslator(true, () -> {
      var admin = shape.get().asAdmin();
      admin.applyStrategies();
      assertThat(TranslatorEquivalenceSupport.countBoundarySteps(admin))
          .isEqualTo(expectedRecognition == TranslatorEquivalenceSupport.Recognition.RECOGNIZED
              ? 1 : 0);
      for (var step : admin.getSteps()) {
        if (step instanceof YTDBMatchPlanStep<?, ?> boundary) {
          assertThat(boundary.getPropertyMapBulkingCapacity()).isZero();
        }
      }
    });
    support.assertEquivalent("state at adjacent barrier refuses bulking", expectedRecognition,
        TranslatorEquivalenceSupport.Cardinality.NON_EMPTY,
        TranslatorEquivalenceSupport::sortedStrings, shape);
  }

  private TranslatorEquivalenceSupport.Recognition recognition(
      Supplier<GraphTraversal<?, ?>> shape) {
    support.setTranslatorEnabled(true);
    var admin = shape.get().asAdmin();
    admin.applyStrategies();
    return TranslatorEquivalenceSupport.countBoundarySteps(admin) == 0
        ? TranslatorEquivalenceSupport.Recognition.DECLINED
        : TranslatorEquivalenceSupport.Recognition.RECOGNIZED;
  }

  private void assertMapsAndBulk(Supplier<GraphTraversal<?, ?>> shape, int rows, int maps,
      List<Long> expectedBulks) {
    support.withTranslatorRestored(() -> {
      support.setTranslatorEnabled(false);
      var nativeRows = shape.get().toList();
      assertThat(nativeRows).hasSize(rows);
      support.setTranslatorEnabled(true);
      var admin = shape.get().asAdmin();
      admin.applyStrategies();
      assertThat(admin.getStartStep()).isInstanceOf(YTDBMatchPlanStep.class);
      var boundary = (YTDBMatchPlanStep<?, ?>) admin.getStartStep();
      assertThat(boundary.getPropertyMapBulkingCapacity()).isEqualTo(2500);
      var bulk = new ArrayList<Long>();
      var expanded = new ArrayList<Object>();
      while (admin.hasNext()) {
        var traverser = admin.nextTraverser();
        bulk.add(traverser.bulk());
        for (long i = 0; i < traverser.bulk(); i++) {
          expanded.add(traverser.get());
        }
      }
      assertThat(bulk).containsExactlyInAnyOrderElementsOf(expectedBulks);
      assertThat(expanded).containsExactlyElementsOf(nativeRows);
      assertThat(boundary.mapConstructions()).isEqualTo(maps);
      assertThat(new ArrayList<Object>(shape.get().toList()))
          .containsExactlyElementsOf(nativeRows);
      var ungrouped = shape.get().asAdmin();
      ungrouped.setStrategies(ungrouped.getStrategies().clone()
          .removeStrategies(LazyBarrierStrategy.class, PathRetractionStrategy.class));
      // Removing the pre-map barrier selects the ungrouped translated boundary without a switch.
      for (int i = ungrouped.getSteps().size() - 1; i >= 0; i--) {
        if (ungrouped.getSteps().get(i) instanceof NoOpBarrierStep<?>) {
          ungrouped.removeStep(i);
        }
      }
      ungrouped.applyStrategies();
      var ungroupedBoundary = (YTDBMatchPlanStep<?, ?>) ungrouped.getStartStep();
      assertThat(ungroupedBoundary.getPropertyMapBulkingCapacity()).isZero();
      var ungroupedRows = new ArrayList<Object>(ungrouped.toList());
      assertThat(ungroupedRows).containsExactlyInAnyOrderElementsOf(nativeRows);
      var firstSeen = new LinkedHashMap<Object, Long>();
      ungroupedRows.forEach(row -> firstSeen.merge(row, 1L, Long::sum));
      var groupedRows = new ArrayList<Object>();
      firstSeen.forEach((row, count) -> {
        for (long i = 0; i < count; i++) {
          groupedRows.add(row);
        }
      });
      assertThat(expanded).containsExactlyElementsOf(groupedRows);
    });
  }

  private void seed() {
    var source = graph.addVertex(T.label, "BulkSource");
    var mid = graph.addVertex(T.label, "BulkMid");
    var a = graph.addVertex(T.label, "BulkTarget", "name", "same");
    var b = graph.addVertex(T.label, "BulkTarget", "name", "same");
    a.property("nullable", null);
    source.addEdge("first", mid);
    source.addEdge("first", mid);
    mid.addEdge("second", a, "weight", 1);
    mid.addEdge("second", b, "weight", 1);
    graph.tx().commit();
    support.setTranslatorEnabled(true);
    GremlinPlanCache.instance(graphSession()).invalidate();
  }

  private DatabaseSessionEmbedded graphSession() {
    graph.tx().readWrite();
    return ((YTDBTransaction) graph.tx()).getDatabaseSession();
  }
}
