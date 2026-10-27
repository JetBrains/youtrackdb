package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.config.OrderByNullsPlacement;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.Cardinality;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.Recognition;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import java.util.List;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/**
 * Sequence equality between the translated arm and the native arm for a text {@code order()}, on
 * both sides of the collation model: a property that declares nothing must order by plain
 * comparison, and a property declared case-insensitive must order by that declaration.
 *
 * <p>Rows are compared in arrival order, so a divergence in the comparison rule fails here. A
 * multiset renderer would compare the same five names against themselves and pass whatever the two
 * arms did with them.
 *
 * <p>Each case also pins the absolute sequence, not only the agreement of the two arms. Agreement
 * alone is satisfied by two arms that are wrong in the same way, and the whole point of the change
 * is which of the two rules each arm follows.
 */
public class OrderCollationEquivalenceTest extends GraphBaseTest {

  private final TranslatorEquivalenceSupport support =
      new TranslatorEquivalenceSupport(() -> session);

  /**
   * Scenario: a schema-less text property, so no collation is declared for it, holding two spellings
   * of one name plus an accented value. Expected: plain code-point order on both arms — every
   * capital before every lower-case letter, and the accented {@code Ähhhh} last, because {@code Ä}
   * is code point 196. That is the TinkerPop rule, and the default collation equals it.
   */
  @Test
  public void undeclaredTextProperty_ordersByPlainComparisonOnBothArms() {
    seedNames("Thing");

    assertEquivalentOrdered(
        "undeclared collation: g.V().order().by(name).values(name)",
        () -> graph.traversal().V().order().by("name").values("name"));

    assertThat(graph.traversal().V().order().by("name").values("name").toList())
        .as("a property with no declaration orders by plain comparison")
        .containsExactly("Ada", "Bob", "Cara", "Zebra", "ada", "Ähhhh");
  }

  /**
   * Scenario: the same six names in a property declared case-insensitive. Expected: the declaration
   * is followed on both arms, so {@code ada} sorts beside {@code Ada} instead of after {@code Zebra}.
   * Case variants tie under {@code ci}; relative order among them is the record-id micro-tie-break
   * (insertion order here: {@code ada} before {@code Ada}), and both arms must agree.
   */
  @Test
  public void caseInsensitiveDeclaredProperty_ordersByTheDeclarationOnBothArms() {
    var person = session.createVertexClass("Person");
    person.createProperty("name", PropertyType.STRING).setCollate("ci");
    seedNames("Person");

    assertEquivalentOrdered(
        "declared ci: g.V().order().by(name).values(name)",
        () -> graph.traversal().V().order().by("name").values("name"));

    assertCiNameOrder(
        graph.traversal().V().order().by("name").values("name").toList(),
        List.of("Bob", "Cara", "Zebra", "Ähhhh"),
        true);
  }

  /**
   * Scenario: the descending direction over the declared case-insensitive property. Expected: the
   * non-tied names reverse relative to ascending; the Ada/ada pair stays a ci tie group (relative
   * order is the rid micro-tie-break, often still ascending). Both arms must agree.
   */
  @Test
  public void caseInsensitiveDeclaredProperty_descendingMatchesOnBothArms() {
    var person = session.createVertexClass("Person");
    person.createProperty("name", PropertyType.STRING).setCollate("ci");
    seedNames("Person");

    assertEquivalentOrdered(
        "declared ci desc: g.V().order().by(name, desc).values(name)",
        () -> graph.traversal().V().order().by("name", Order.desc).values("name"));

    assertCiNameOrder(
        graph.traversal().V().order().by("name", Order.desc).values("name").toList(),
        List.of("Ähhhh", "Zebra", "Cara", "Bob"),
        false);
  }

  /**
   * Scenario: a schema-less property contains values from several TinkerPop order categories.
   * Expected: translated MATCH and native Gremlin return Boolean, Number, then String in the same
   * order, regardless of which execution arm accepts the traversal.
   */
  @Test
  public void schemaLessMixedProperty_ordersByTinkerPopTypePriorityOnBothArms() {
    for (var value : List.of("10", true, 2)) {
      graph.addVertex(T.label, "Probe", "value", value);
    }
    graph.tx().commit();

    assertEquivalentOrdered(
        "schema-less mixed types: g.V().hasLabel(Probe).order().by(value).values(value)",
        () -> graph.traversal().V().hasLabel("Probe").order().by("value").values("value"));

    assertThat(
        graph.traversal().V().hasLabel("Probe").order().by("value").values("value").toList())
        .as("TinkerPop ranks Boolean before Number before String")
        .containsExactly(true, 2, "10");
  }

  /**
   * Scenario: the declared case-insensitive property, with an unrelated vertex class declaring the
   * same property name and no collation, and the traversal constrained to the declaring class.
   * Expected: both arms follow the declaration of the constrained class.
   *
   * <p>The MATCH planner reads the declaration off the class the alias is bound to, so a sibling
   * declaration cannot reach it. The native strategy used to read it across every vertex and edge
   * class, saw the disagreement, and fell back to the default collation — one query, two sequences,
   * depending on which arm ran it.
   */
  @Test
  public void labelConstrainedSort_followsThatClassDeclarationOnBothArms() {
    var person = session.createVertexClass("Person");
    person.createProperty("name", PropertyType.STRING).setCollate("ci");
    var animal = session.createVertexClass("Animal");
    animal.createProperty("name", PropertyType.STRING);
    seedNames("Person");
    graph.addVertex(T.label, "Animal", "name", "Sibling");
    graph.tx().commit();

    assertEquivalentOrdered(
        "declared ci beside a disagreeing sibling class:"
            + " g.V().hasLabel(Person).order().by(name).values(name)",
        () -> graph.traversal().V().hasLabel("Person").order().by("name").values("name"));

    assertCiNameOrder(
        graph.traversal().V().hasLabel("Person").order().by("name").values("name").toList(),
        List.of("Bob", "Cara", "Zebra", "\u00c4hhhh"),
        true);
  }

  /** Multi-label roots use their common ancestor, not an unrelated default declaration. */
  @Test
  public void multiLabelRoot_usesCommonAncestorCollationOnBothArms() {
    var parent = session.createVertexClass("Named");
    parent.createProperty("name", PropertyType.STRING).setCollate("ci");
    session.getMetadata().getSchema().createClass("First", parent);
    session.getMetadata().getSchema().createClass("Second", parent);
    session.createVertexClass("Unrelated").createProperty("name", PropertyType.STRING);
    graph.addVertex(T.label, "First", "name", "alice");
    graph.addVertex(T.label, "Second", "name", "Zebra");
    graph.tx().commit();

    assertEquivalentOrdered("multi-label ancestor collation",
        () -> graph.traversal().V().hasLabel("First", "Second")
            .order().by("name", Order.asc).values("name"));
    assertThat(graph.traversal().V().hasLabel("First", "Second")
        .order().by("name", Order.asc).values("name").toList())
        .containsExactly("alice", "Zebra");
  }

  /** LINK inference must exclude unrelated default declarations for outgoing targets. */
  @Test
  public void outgoingHop_usesLinkedHierarchy() {
    seedLinkedNames();
    assertDirections(() -> graph.traversal().V().hasLabel("Source").out("Link"), true);
  }

  /** Incoming hops read the out endpoint declaration, not the starting class. */
  @Test
  public void incomingHop_usesLinkedHierarchy() {
    seedLinkedNames();
    assertDirections(() -> graph.traversal().V().hasLabel("Named").in("Link"), false);
  }

  /** A symmetric both hop has one inferred endpoint class despite unrelated declarations. */
  @Test
  public void bothHop_usesSymmetricLinkedHierarchy() {
    seedLinkedNames();
    assertDirections(() -> graph.traversal().V().hasLabel("Named").both("Peer"), true);
  }

  /** Edge predicates between outE and inV preserve the edge LINK source. */
  @Test
  public void filteredEdgeInVertex_usesLinkedHierarchy() {
    seedLinkedNames();
    assertDirections(() -> graph.traversal().V().hasLabel("Source")
        .outE("Link").has("active", true).inV(), true);
  }

  /** Edge predicates between inE and outV preserve the out endpoint class. */
  @Test
  public void filteredEdgeOutVertex_usesLinkedHierarchy() {
    seedLinkedNames();
    assertDirections(() -> graph.traversal().V().hasLabel("Named")
        .inE("Link").has("active", true).outV(), false);
  }

  /** otherV uses the far endpoint selected by the edge-hop direction. */
  @Test
  public void filteredEdgeOtherVertex_usesLinkedHierarchy() {
    seedLinkedNames();
    assertDirections(() -> graph.traversal().V().hasLabel("Source")
        .outE("Link").has("active", true).otherV(), true);
  }

  /** Filtered bothE/otherV uses equal LINK endpoints and falls back for unequal endpoints. */
  @Test
  public void filteredBothEdgeOtherVertex_resolvesOnlySymmetricEndpoints() {
    seedLinkedNames();
    graph.traversal().E().hasLabel("Peer").next().property("active", true);
    graph.tx().commit();
    for (var symmetric : List.of(true, false)) {
      Supplier<GraphTraversal<?, ?>> source = () -> graph.traversal().V()
          .hasLabel(symmetric ? "Named" : "Source")
          .bothE(symmetric ? "Peer" : "Link").has("active", true).otherV();
      for (var direction : List.of(Order.asc, Order.desc)) {
        support.assertEquivalent("filtered bothE/otherV " + symmetric + direction,
            Recognition.DECLINED, Cardinality.NON_EMPTY,
            OrderCollationEquivalenceTest::orderedRows,
            () -> source.get().order().by("name", direction).values("name"));
        var expected = symmetric ? List.of("alice", "Zebra") : List.of("Zebra", "alice");
        assertThat(source.get().order().by("name", direction).values("name").toList())
            .containsExactlyElementsOf(direction == Order.asc ? expected : expected.reversed());
      }
    }
  }

  /** A successful translated hop must inspect declarations that exist only on LINK subclasses. */
  @Test
  public void linkedHop_subclassOnlyDeclarationsUseCi() {
    var base = session.createVertexClass("Base");
    for (var child : List.of("ChildA", "ChildB")) {
      session.getMetadata().getSchema().createClass(child, base)
          .createProperty("name", PropertyType.STRING).setCollate("ci");
    }
    var source = session.createVertexClass("Source");
    source.createProperty("name", PropertyType.STRING);
    var link = session.createEdgeClass("Link");
    link.getProperty("out").setLinkedClass(source);
    link.getProperty("in").setLinkedClass(base);
    var s = graph.addVertex(T.label, "Source");
    s.addEdge("Link", graph.addVertex(T.label, "ChildA", "name", "alice"));
    s.addEdge("Link", graph.addVertex(T.label, "ChildB", "name", "Zebra"));
    graph.tx().commit();
    assertDirections(() -> graph.traversal().V().hasLabel("Source").out("Link"), true);
  }

  /** Raw walks with identical MATCH topology retain their different comparison contracts. */
  @Test
  public void immediateSelect_comparisonClassesDistinguishFingerprintBeforeStrategyDecline() {
    seedLinkedNames();
    var direct = GremlinStepWalker.production().walk(graph.traversal().V().hasLabel("Source")
        .out("Link").order().by("name").values("name").asAdmin());
    var selected = GremlinStepWalker.production().walk(graph.traversal().V().hasLabel("Source")
        .out("Link").as("c").select("c").order().by("name").values("name").asAdmin());
    assertThat(direct).isNotNull();
    assertThat(selected).isNotNull();
    assertThat(direct.inputs().orderCollationClasses().get(direct.boundaryAlias()))
        .containsExactlyInAnyOrder("Named", "First", "Second");
    assertThat(selected.inputs().orderCollationClasses().get(selected.boundaryAlias()))
        .contains("Source");
    var directKey = GremlinPlanFingerprint.fingerprint(direct.inputs(), direct.shaping());
    assertThat(directKey).isNotEqualTo(
        GremlinPlanFingerprint.fingerprint(selected.inputs(), selected.shaping()));
    // Removing only comparison metadata recreates the collision. No strategy decline is involved.
    direct.inputs().orderCollationClasses().clear();
    selected.inputs().orderCollationClasses().clear();
    assertThat(GremlinPlanFingerprint.fingerprint(direct.inputs(), direct.shaping()))
        .isEqualTo(GremlinPlanFingerprint.fingerprint(selected.inputs(), selected.shaping()));
  }

  /** Different predicate literals reuse one physical template and bind their own result rows. */
  @Test
  public void sameShapeDifferentLiterals_shareOneOrderedPlan() {
    seedLinkedNames();
    graph.tx().readWrite();
    var db = ((com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction) graph.tx())
        .getDatabaseSession();
    var cache = GremlinPlanCache.instance(db);
    support.withTranslatorRestored(() -> {
      support.setTranslatorEnabled(true);
      cache.invalidate();
      var misses = cache.getMisses();
      var hits = cache.getTranslationHits();
      String fingerprint = null;
      for (var literal : List.of("alice", "Zebra")) {
        var traversal = graph.traversal().V().hasLabel("Source").has("name", literal)
            .out("Link").order().by("name").values("name").asAdmin();
        var walk = GremlinStepWalker.production().walk(traversal);
        assertThat(walk).isNotNull();
        var key = GremlinPlanFingerprint.fingerprint(walk.inputs(), walk.shaping());
        if (fingerprint != null) {
          assertThat(key).isEqualTo(fingerprint);
        }
        fingerprint = key;
        traversal.applyStrategies();
        assertThat(TranslatorEquivalenceSupport.countBoundarySteps(traversal)).isEqualTo(1);
        assertThat(traversal.toList()).containsExactly(literal);
        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.getMisses()).isEqualTo(misses + 1);
      }
      assertThat(cache.getTranslationHits()).isEqualTo(hits + 1);
    });
  }

  /** Cached declines must preserve direct edge ci and select-alias fallback in either order. */
  @Test
  public void directAndSelectedEdge_cacheKeepsEachComparisonInBothInvocationOrders() {
    seedLinkedNames();
    session.getMetadata().getSchema().getClass("Link")
        .createProperty("name", PropertyType.STRING).setCollate("ci");
    var edges = graph.traversal().E().hasLabel("Link").toList();
    edges.get(0).property("name", "alice");
    edges.get(1).property("name", "Zebra");
    graph.tx().commit();
    graph.tx().readWrite();
    var db = ((com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction) graph.tx())
        .getDatabaseSession();
    var cache = GremlinPlanCache.instance(db);
    support.withTranslatorRestored(() -> {
      support.setTranslatorEnabled(true);
      for (var selectedFirst : List.of(false, true)) {
        cache.invalidate();
        var hits = cache.getTranslationHits();
        for (var selected : List.of(selectedFirst, !selectedFirst, selectedFirst, !selectedFirst)) {
          var traversal = graph.traversal().V().hasLabel("Source").outE("Link");
          if (selected) {
            traversal = traversal.as("e").select("e");
          }
          var ordered = traversal.order().by("name").values("name").asAdmin();
          ordered.applyStrategies();
          assertThat(TranslatorEquivalenceSupport.countBoundarySteps(ordered))
              .as("selected=%s steps=%s", selected, ordered).isZero();
          assertThat(ordered.toList()).containsExactlyElementsOf(
              selected ? List.of("Zebra", "alice") : List.of("alice", "Zebra"));
        }
        assertThat(cache.getTranslationHits()).isGreaterThan(hits);
      }
    });
  }

  /** Translated direct and selected vertex sorts use distinct physical keys and warm correctly. */
  @Test
  public void translatedDirectAndSelectedVertex_cacheSeparatesTopologyInBothOrders() {
    seedLinkedNames();
    var place = graph.addVertex(T.label, "Place");
    for (var named : graph.traversal().V().hasLabel("Named").toList()) {
      named.addEdge("LOCATED_IN", place);
    }
    graph.tx().commit();
    graph.tx().readWrite();
    var db = ((com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction) graph.tx())
        .getDatabaseSession();
    var cache = GremlinPlanCache.instance(db);
    Supplier<GraphTraversal<?, ?>> direct = () -> graph.traversal().V().hasLabel("Source")
        .out("Link").order().by("name").values("name");
    Supplier<GraphTraversal<?, ?>> selected = () -> graph.traversal().V().hasLabel("Source")
        .out("Link").as("c").out("LOCATED_IN").select("c")
        .order().by("name").values("name");
    var directWalk = GremlinStepWalker.production().walk(direct.get().asAdmin());
    var selectedWalk = GremlinStepWalker.production().walk(selected.get().asAdmin());
    assertThat(directWalk).isNotNull();
    assertThat(selectedWalk).isNotNull();
    assertThat(GremlinPlanFingerprint.fingerprint(directWalk.inputs(), directWalk.shaping()))
        .isNotEqualTo(
            GremlinPlanFingerprint.fingerprint(selectedWalk.inputs(), selectedWalk.shaping()));
    support.withTranslatorRestored(() -> {
      support.setTranslatorEnabled(true);
      for (var selectedFirst : List.of(false, true)) {
        cache.invalidate();
        var hits = cache.getTranslationHits();
        for (var select : List.of(selectedFirst, !selectedFirst, selectedFirst, !selectedFirst)) {
          var traversal = (select ? selected : direct).get().asAdmin();
          traversal.applyStrategies();
          assertThat(TranslatorEquivalenceSupport.countBoundarySteps(traversal)).isEqualTo(1);
          assertThat(orderedRows(traversal.toList())).containsExactlyElementsOf(
              select ? List.of("Zebra", "alice") : List.of("alice", "Zebra"));
        }
        assertThat(cache.getTranslationHits()).isGreaterThan(hits);
      }
    });
  }

  /** A post-hop label overrides a LINK hierarchy with conflicting child declarations. */
  @Test
  public void targetLabel_winsOverLinkedHierarchy() {
    seedLinkedNames();
    var base = session.createVertexClass("Mixed");
    var collated = session.getMetadata().getSchema().createClass("CollatedChild", base);
    collated.createProperty("name", PropertyType.STRING).setCollate("ci");
    session.getMetadata().getSchema().createClass("DefaultChild", base)
        .createProperty("name", PropertyType.STRING);
    var edge = session.createEdgeClass("MixedLink");
    edge.getProperty("out").setLinkedClass(session.getMetadata().getSchema().getClass("Source"));
    edge.getProperty("in").setLinkedClass(base);
    var source = graph.traversal().V().hasLabel("Source").next();
    for (var name : List.of("alice", "Zebra")) {
      source.addEdge("MixedLink", graph.addVertex(T.label, "CollatedChild", "name", name));
    }
    graph.tx().commit();
    assertDirections(() -> graph.traversal().V().hasLabel("Source").out("MixedLink")
        .hasLabel("CollatedChild"), true);
    assertDirections(() -> graph.traversal().V().hasLabel("Source").out("MixedLink"), false);
  }

  /** Multiple labels after a hop use their common hierarchy and exclude unrelated defaults. */
  @Test
  public void multiLabelAfterHop_usesAncestorHierarchy() {
    seedLinkedNames();
    assertDirections(() -> graph.traversal().V().hasLabel("Source").out("Link")
        .hasLabel("First", "Second"), true);
  }

  /** Unlabelled roots retain the default fallback when declarations conflict anywhere in V. */
  @Test
  public void unlabelledRoot_conflictingDeclarationsKeepPlainOrder() {
    seedLinkedNames();
    assertEquivalentOrdered("unlabelled conflicting hierarchy",
        () -> graph.traversal().V().order().by("name", Order.asc).values("name"));
  }

  /** Heterogeneous both endpoints cannot infer one class and retain the default fallback. */
  @Test
  public void heterogeneousBoth_keepsHierarchyFallback() {
    seedLinkedNames();
    assertDirections(() -> graph.traversal().V().hasLabel("Source").both("Link"), false);
  }

  /** Missing keys and present nulls survive according to presence, direction, and null settings. */
  @Test
  public void linkedCollation_preservesNullAndMissingPolicies() {
    seedLinkedNames();
    var source = graph.traversal().V().hasLabel("Source").next();
    source.addEdge("Link", graph.addVertex(T.label, "First", "tag", "missing"));
    // Write through the same graph transaction, bypassing Gremlin's null-property removal.
    var nullVertex = graph.addVertex(T.label, "First", "tag", "null");
    ((com.jetbrains.youtrackdb.internal.core.gremlin.YTDBElementImpl) nullVertex)
        .getRawEntity().setProperty("name", null);
    source.addEdge("Link", nullVertex);
    graph.tx().commit();
    var config = session.getConfiguration();
    var missingKey = GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY;
    var previousMissing = config.getValueAsBoolean(missingKey);
    var ascKey = GlobalConfiguration.QUERY_ORDER_BY_NULLS_PLACEMENT_ASC;
    var descKey = GlobalConfiguration.QUERY_ORDER_BY_NULLS_PLACEMENT_DESC;
    var previousAsc = config.getValue(ascKey);
    var previousDesc = config.getValue(descKey);
    try {
      for (var missing : List.of(false, true)) {
        config.setValue(missingKey, missing);
        for (var placement : OrderByNullsPlacement.values()) {
          config.setValue(ascKey, placement);
          config.setValue(descKey, placement);
          for (var direction : List.of(Order.asc, Order.desc)) {
            assertEquivalentOrdered("null policy " + missing + placement + direction,
                () -> graph.traversal().V().hasLabel("Source").out("Link")
                    .order().by("name", direction));
            var count = graph.traversal().V().hasLabel("Source").out("Link")
                .order().by("name", direction).count().next();
            assertThat(count).isEqualTo(missing ? 4L : 3L);
          }
        }
      }
    } finally {
      config.setValue(missingKey, previousMissing);
      config.setValue(ascKey, previousAsc);
      config.setValue(descKey, previousDesc);
    }
  }

  /** Sliced hops compare the same LINK-resolved keys before choosing the returned row. */
  @Test
  public void linkedHopSlice_usesCollationBeforeRange() {
    seedLinkedNames();
    assertEquivalentOrdered("sliced linked hop", () -> graph.traversal().V().hasLabel("Source")
        .out("Link").order().by("name", Order.asc).range(0, 1).values("name"));
    assertThat(graph.traversal().V().hasLabel("Source").out("Link")
        .order().by("name", Order.asc).range(0, 1).values("name").toList())
        .containsExactly("alice");
  }

  /** A LINK target without a declaration restores default comparison despite the source ci. */
  @Test
  public void linkedTargetWithoutDeclaration_restoresDefaultComparison() {
    var person = session.createVertexClass("Person");
    person.createProperty("name", PropertyType.STRING).setCollate("ci");
    var company = session.createVertexClass("Company");
    var edge = session.createEdgeClass("WORKS_AT");
    edge.getProperty("out").setLinkedClass(person);
    edge.getProperty("in").setLinkedClass(company);
    var source = graph.addVertex(T.label, "Person", "name", "source");
    for (var name : List.of("alice", "Zebra")) {
      source.addEdge("WORKS_AT", graph.addVertex(T.label, "Company", "name", name));
    }
    graph.tx().commit();
    assertDirections(() -> graph.traversal().V().hasLabel("Person").out("WORKS_AT"), false);
  }

  /** A selected vertex keeps the whole graph fallback instead of reusing its LINK class. */
  @Test
  public void selectedVertex_keepsFallback() {
    seedLinkedNames();
    var place = graph.addVertex(T.label, "Place");
    for (var company : graph.traversal().V().hasLabel("Named").toList()) {
      company.addEdge("LOCATED_IN", place);
    }
    graph.tx().commit();
    assertDirections(() -> graph.traversal().V().hasLabel("Source").out("Link").as("c")
        .out("LOCATED_IN").select("c"), false);
  }

  /** A selected edge keeps fallback comparison even when its own class declares ci. */
  @Test
  public void selectedEdge_keepsFallback() {
    seedLinkedNames();
    session.getMetadata().getSchema().getClass("Link")
        .createProperty("name", PropertyType.STRING).setCollate("ci");
    var edges = graph.traversal().E().hasLabel("Link").toList();
    edges.get(0).property("name", "alice");
    edges.get(1).property("name", "Zebra");
    graph.tx().commit();
    assertEquivalentOrdered("selected edge", () -> graph.traversal().V().hasLabel("Source")
        .outE("Link").as("e").inV().select("e").order().by("name").values("name"));
    assertThat(graph.traversal().V().hasLabel("Source").outE("Link").as("e").inV()
        .select("e").order().by("name").values("name").toList())
        .containsExactly("Zebra", "alice");
  }

  /** Unrelated post-hop labels must not inherit the narrower LINK class. */
  @Test
  public void unrelatedLabelsAfterHop_useHierarchyFallback() {
    seedLinkedNames();
    graph.traversal().V().hasLabel("Source").next()
        .addEdge("Link", graph.addVertex(T.label, "First", "name", "Zebra"));
    graph.tx().commit();
    assertDirections(() -> graph.traversal().V().hasLabel("Source").out("Link")
        .hasLabel("First", "Source"), false);
  }

  /** Comparison metadata keeps planner inputs and schedules while both arms prefetch Named. */
  @Test
  public void comparisonClass_isIndependentOfPlannerInputs() {
    seedLinkedNames();
    var current = GremlinStepWalker.production().walk(graph.traversal().V().hasLabel("Source")
        .out("Link").order().by("name").limit(10).asAdmin());
    var fallback = GremlinStepWalker.production().walk(graph.traversal().V().hasLabel("Source")
        .out("Link").order().by("name").limit(10).asAdmin());
    assertThat(current).isNotNull();
    assertThat(fallback).isNotNull();
    var inputs = current.inputs();
    var oldInputs = fallback.inputs();
    assertThat(inputs.orderCollationClasses().get(current.boundaryAlias()))
        .contains("Named", "First", "Second").doesNotContain("Source");
    assertThat(inputs.aliasClasses()).containsEntry(current.boundaryAlias(), "V");
    oldInputs.orderCollationClasses().clear();
    assertThat(inputs.aliasClasses()).isEqualTo(oldInputs.aliasClasses());
    assertThat(inputs.aliasFilters().toString()).isEqualTo(oldInputs.aliasFilters().toString());
    assertThat(GremlinPlanFingerprint.fingerprint(inputs, current.shaping()))
        .isNotEqualTo(GremlinPlanFingerprint.fingerprint(oldInputs, fallback.shaping()));
    graph.tx().readWrite();
    var db = ((com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction) graph.tx())
        .getDatabaseSession();
    var context = new com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext(db);
    var planner =
        new com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchExecutionPlanner(inputs);
    var oldPlanner =
        new com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchExecutionPlanner(
            oldInputs);
    var plan = planner.createExecutionPlan(context, false, false);
    var oldPlan = oldPlanner.createExecutionPlan(context, false, false);
    // The default comparison already infers LINK classes. Declared comparison must use that
    // same plan form, including the prefetch class. Comparison metadata has its own cache key.
    assertThat(plan.prettyPrint(0, 2)).contains("FETCH FROM CLASS Named")
        .doesNotContain("FETCH FROM CLASS V\n")
        .isEqualTo(oldPlan.prettyPrint(0, 2));
    assertThat(inputs.orderBy().getItems().getFirst().getDeclaredCollate().getName())
        .isEqualTo("ci");
    assertThat(oldInputs.orderBy().getItems().getFirst().getDeclaredCollate()).isNull();
  }

  /** A slice through a ci tie records both paths without imposing a new tie policy. */
  @Test
  public void slicedCaseVariants_recordTieOutcome() {
    var person = session.createVertexClass("Person");
    person.createProperty("name", PropertyType.STRING).setCollate("ci");
    seedNames("Person");
    support.withTranslatorRestored(() -> {
      for (var direction : List.of(Order.asc, Order.desc)) {
        support.setTranslatorEnabled(true);
        var translated = graph.traversal().V().hasLabel("Person")
            .order().by("name", direction).range(0, direction == Order.asc ? 1 : 5)
            .values("name").asAdmin();
        translated.applyStrategies();
        assertThat(TranslatorEquivalenceSupport.countBoundarySteps(translated)).isEqualTo(1);
        var on = translated.toList();
        support.setTranslatorEnabled(false);
        var off = graph.traversal().V().hasLabel("Person").order().by("name", direction)
            .range(0, direction == Order.asc ? 1 : 5).values("name").toList();
        assertThat(on).hasSameSizeAs(off);
        assertThat(on.getLast()).isIn("Ada", "ada");
        assertThat(off.getLast()).isIn("Ada", "ada");
      }
    });
  }

  /** Compare actual sequence order and pin translation for both explicit directions. */
  private void assertDirections(Supplier<GraphTraversal<?, Vertex>> source, boolean ci) {
    for (var direction : List.of(Order.asc, Order.desc)) {
      assertEquivalentOrdered("linked class " + direction,
          () -> source.get().order().by("name", direction).values("name"));
      var expected = ci ? List.of("alice", "Zebra") : List.of("Zebra", "alice");
      if (direction == Order.desc) {
        expected = expected.reversed();
      }
      assertThat(source.get().order().by("name", direction).values("name").toList())
          .containsExactlyElementsOf(expected);
    }
  }

  /** Two endpoint hierarchies deliberately disagree, while child classes inherit ci. */
  private void seedLinkedNames() {
    var named = session.createVertexClass("Named");
    named.createProperty("name", PropertyType.STRING).setCollate("ci");
    session.getMetadata().getSchema().createClass("First", named);
    session.getMetadata().getSchema().createClass("Second", named);
    var source = session.createVertexClass("Source");
    source.createProperty("name", PropertyType.STRING);
    var link = session.createEdgeClass("Link");
    link.getProperty("out").setLinkedClass(source);
    link.getProperty("in").setLinkedClass(named);
    var peer = session.createEdgeClass("Peer");
    peer.getProperty("out").setLinkedClass(named);
    peer.getProperty("in").setLinkedClass(named);
    var first = graph.addVertex(T.label, "First", "name", "alice");
    var second = graph.addVertex(T.label, "Second", "name", "Zebra");
    graph.addVertex(T.label, "Source", "name", "alice")
        .addEdge("Link", first, "active", true);
    graph.addVertex(T.label, "Source", "name", "Zebra")
        .addEdge("Link", second, "active", true);
    first.addEdge("Peer", second);
    graph.tx().commit();
  }

  /** An edge-only declaration governs union sorts over vertices through the native whole graph set. */
  @Test
  public void unionSortWithEdgeOnlyCollation_declinesWithAndWithoutSlice() {
    session.createEdgeClass("CollatedEdge")
        .createProperty("name", PropertyType.STRING).setCollate("ci");
    assertCollatedUnionDeclines();
  }

  /** A declaration only on a subclass must be found by the shared hierarchy walk. */
  @Test
  public void unionSortWithSubclassOnlyCollation_declinesWithAndWithoutSlice() {
    var parent = session.createVertexClass("Parent");
    session.getMetadata().getSchema().createClass("CollatedChild", parent)
        .createProperty("name", PropertyType.STRING).setCollate("ci");
    assertCollatedUnionDeclines();
  }

  /** A declared default disagrees with ci, so native and translated union sorts use plain order. */
  @Test
  public void unionSortWithDisagreeingCollations_keepsTranslation() {
    session.createEdgeClass("CollatedEdge")
        .createProperty("name", PropertyType.STRING).setCollate("ci");
    session.createVertexClass("DefaultClass").createProperty("name", PropertyType.STRING);
    seedUnionNames();
    support.assertEquivalent("union default fallback", Recognition.RECOGNIZED_MULTI_PLAN,
        Cardinality.NON_EMPTY, OrderCollationEquivalenceTest::unionNames,
        () -> graph.traversal().V().union(__.out("next"), __.in("next")).order().by("name"));
  }

  /**
   * Branches duplicate six names. A collated second key must decline even when the first key is
   * uncollated. A slice plus count reaches the order recogniser without relaxing the slice gate.
   */
  private void assertCollatedUnionDeclines() {
    seedUnionNames();
    support.assertEquivalent("collated union sort", Recognition.DECLINED,
        Cardinality.NON_EMPTY, OrderCollationEquivalenceTest::unionNames,
        () -> graph.traversal().V().union(__.out("next"), __.in("next"))
            .order().by(T.label).by("name"));
    var expected = List.of("ada", "Ada", "ada", "Ada", "Bob", "Bob", "Cara", "Cara",
        "Zebra", "Zebra", "Ähhhh", "Ähhhh");
    // Case variants tie, so verify the ci group separately from its RID-defined inner sequence.
    var names = unionNames(graph.traversal().V().union(__.out("next"), __.in("next"))
        .order().by(T.label).by("name").toList());
    assertThat(names.subList(0, 4)).containsExactlyInAnyOrderElementsOf(expected.subList(0, 4));
    assertThat(names.subList(4, 12)).isEqualTo(expected.subList(4, 12));
    support.assertEquivalent("collated union sort with slice", Recognition.DECLINED,
        Cardinality.NON_EMPTY, OrderCollationEquivalenceTest::unionNames,
        () -> graph.traversal().V().union(__.out("next"), __.in("next"))
            .order().by("name").limit(3));
    support.assertEquivalent("collated union sort with counted slice", Recognition.DECLINED,
        Cardinality.NON_EMPTY, OrderCollationEquivalenceTest::orderedRows,
        () -> graph.traversal().V().union(__.out("next"), __.in("next"))
            .order().by("name").limit(3).count());
  }

  /** A committed ring makes each branch emit every name once and supports translated hops. */
  private void seedUnionNames() {
    seedNames("Thing");
    var vertices = graph.traversal().V().toList();
    for (var index = 0; index < vertices.size(); index++) {
      vertices.get(index).addEdge("next", vertices.get((index + 1) % vertices.size()));
    }
    graph.tx().commit();
  }

  private static List<String> unionNames(List<?> rows) {
    return rows.stream().map(row -> ((Vertex) row).<String>value("name")).toList();
  }

  /**
   * Six names under {@code className}, inserted in an order that matches neither of the two
   * sequences under test, so an unsorted answer cannot pass either case.
   */
  private void seedNames(String className) {
    for (var name : List.of("Cara", "ada", "Zebra", "Ada", "Ähhhh", "Bob")) {
      graph.addVertex(T.label, className, "name", name);
    }
    graph.tx().commit();
  }

  /**
   * Pins ci grouping: Ada/ada as a tied pair (order among them is rid, not CS), and the remaining
   * names in folded order. {@code adaPairFirst} places that pair at the start (ASC) or end (DESC).
   */
  private static void assertCiNameOrder(
      List<?> rawNames, List<String> otherNamesInOrder, boolean adaPairFirst) {
    var names = rawNames.stream().map(String::valueOf).toList();
    assertThat(names).hasSize(2 + otherNamesInOrder.size());
    var pair = adaPairFirst ? names.subList(0, 2) : names.subList(names.size() - 2, names.size());
    var rest = adaPairFirst ? names.subList(2, names.size()) : names.subList(0, names.size() - 2);
    assertThat(pair).containsExactlyInAnyOrder("Ada", "ada");
    assertThat(rest).containsExactlyElementsOf(otherNamesInOrder);
  }

  private void assertEquivalentOrdered(
      String scenario, Supplier<GraphTraversal<?, ?>> traversalSupplier) {
    support.assertEquivalent(
        scenario,
        Recognition.RECOGNIZED,
        Cardinality.NON_EMPTY,
        OrderCollationEquivalenceTest::orderedRows,
        traversalSupplier);
  }

  /** Arrival-order rendering: sorting here would hide the sequence differences under test. */
  private static List<String> orderedRows(List<?> results) {
    return results.stream().map(String::valueOf).toList();
  }
}
