package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.Cardinality;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.Recognition;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/**
 * Equivalence tests for Phase 1 gap closure: edge {@code as(...)} binding and projection, {@code
 * bothE.has.otherV}, multi-label and missing-class {@code hasLabel}, and multi-key / cross-alias
 * {@code order().by(...)}.
 */
public class Phase1GapClosureEquivalenceTest extends GraphBaseTest {

  private final TranslatorEquivalenceSupport support =
      new TranslatorEquivalenceSupport(() -> session);

  // ---------------------------------------------------------------------------
  // Edge alias — bind, project, sort.
  // ---------------------------------------------------------------------------

  /**
   * {@code outE(L).as(k).inV().select(k).by(since)} declines: the edge as() label would bind to the
   * edge-as-node vertex alias, so {@code select(k)} would return the target vertex not the edge.
   */
  @Test
  public void edgeAlias_selectByProperty_declines() {
    var ids = seedKnowsWithSince();
    assertEquivalent(
        "g.V(alice).outE(knows).as(k).inV().select(k).by(since)",
        Recognition.DECLINED,
        () -> graph.traversal().V(ids.alice()).outE("knows").as("k").inV().select("k").by("since"));
  }

  /** Edge alias without an interposed {@code has(...)} still declines (edge-alias select). */
  @Test
  public void edgeAlias_noHasFilter_selectByProperty_declines() {
    var ids = seedKnowsWithSince();
    assertEquivalent(
        "g.V(alice).outE(knows).as(k).inV().select(k).by(since) (no has)",
        Recognition.DECLINED,
        () -> graph.traversal().V(ids.alice()).outE("knows").as("k").inV().select("k").by("since"));
  }

  /** Two-label {@code select(k, friend).by(...)} without order — edge alias declines. */
  @Test
  public void edgeAlias_selectTwoLabels_noOrder_declines() {
    var ids = seedKnowsWithSinceAndNames();
    assertEquivalent(
        "…outE(k).inV().as(friend).select(k,friend).by(since).by(name)",
        Recognition.DECLINED,
        () -> graph.traversal().V(ids.alice())
            .outE("knows").as("k")
            .inV().as("friend")
            .select("k", "friend").by("since").by("name"));
  }

  /** {@code order().by(name)} on the hop target after edge alias bind — declines (edge alias). */
  @Test
  public void edgeAlias_orderByFriendName_declines() {
    var ids = seedKnowsWithSinceAndNames();
    assertEquivalentOrdered(
        "…outE(k).inV().as(friend).order().by(name)",
        Recognition.DECLINED,
        () -> graph.traversal().V(ids.alice())
            .outE("knows").as("k")
            .inV().as("friend")
            .order().by("name", Order.asc));
  }

  /** IS3-shaped order + two-label select — declines (edge alias). */
  @Test
  public void edgeAlias_is3Shape_selectTwoLabels_declines() {
    var ids = seedKnowsWithSinceAndNames();
    assertEquivalentOrdered(
        "…outE(knows).as(k).inV().as(friend).order().by(firstName)"
            + ".select(k,friend).by(since).by(name)",
        Recognition.DECLINED,
        () -> graph.traversal().V(ids.alice())
            .outE("knows").as("k")
            .inV().as("friend")
            .order().by("name")
            .select("k", "friend").by("since").by("name"));
  }

  /** Single cross-alias {@code order().by(select(k).by(since))} modulator — declines (edge alias). */
  @Test
  public void orderBy_selectKModulatorOnly_declines() {
    var ids = seedKnowsWithSinceAndNames();
    assertEquivalentOrdered(
        "…order().by(select(k).by(since))",
        Recognition.DECLINED,
        () -> graph.traversal().V(ids.alice())
            .outE("knows").as("k")
            .inV().as("friend")
            .order().by(__.select("k").by("since"), Order.asc));
  }

  /** Cross-alias sort with a bound edge alias modulator — declines (edge alias). */
  @Test
  public void orderBy_selectEdgeModulator_thenVertexProperty_declines() {
    var ids = seedKnowsWithSinceAndNames();
    assertEquivalentOrdered(
        "…order().by(select(k).by(since)).by(name)",
        Recognition.DECLINED,
        () -> graph.traversal().V(ids.alice())
            .outE("knows").as("k")
            .inV().as("friend")
            .order().by(__.select("k").by("since"), Order.asc)
            .by(__.select("friend").by("name"), Order.asc));
  }

  /** Two boundary keys on the same alias sort lexically like native. */
  @Test
  public void orderBy_multiKeySameAlias_matchesNative() {
    seedOrderedPeople();
    assertEquivalentOrdered(
        "g.V().order().by(age).by(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().order().by("age", Order.asc).by("name", Order.asc));
  }

  // ---------------------------------------------------------------------------
  // bothE.has.otherV — variants.
  // ---------------------------------------------------------------------------

  /**
   * {@code bothE(L).has(...).otherV()} from a pinned start declines: the only MATCH rewrite excludes
   * the walk source via {@code @rid <> source}, which wrongly drops self-loop endpoints.
   */
  @Test
  public void bothE_has_otherV_fromPinnedStart_declines() {
    var ids = seedBidirectionalKnows();
    assertEquivalent(
        "g.V(alice).bothE(knows).has(since, lt 2015).otherV()",
        Recognition.DECLINED,
        () -> graph.traversal().V(ids.alice())
            .bothE("knows").has("since", P.lt(2015)).otherV());
  }

  /** {@code inE(L).has(...).outV()} analogue of the edge-filter chain. */
  @Test
  public void inE_has_outV_matchesNative() {
    var ids = seedBidirectionalKnows();
    assertEquivalent(
        "g.V(bob).inE(knows).has(since, gte 2010).outV()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V(ids.bob())
            .inE("knows").has("since", P.gte(2010)).outV());
  }

  /** {@code bothE(L).has(...).otherV()} with an edge alias and projection — declines (both BOTH-hop and edge alias). */
  @Test
  public void bothE_has_otherV_withEdgeAlias_select_declines() {
    var ids = seedBidirectionalKnows();
    assertEquivalent(
        "g.V(alice).bothE(knows).has(since, lt 2015).otherV().select(k).by(since)",
        Recognition.DECLINED,
        () -> graph.traversal().V(ids.alice())
            .bothE("knows").as("k").has("since", P.lt(2015)).otherV()
            .select("k").by("since"));
  }

  // ---------------------------------------------------------------------------
  // hasLabel — multi-label and missing class.
  // ---------------------------------------------------------------------------

  /** Multi-label {@code hasLabel} + property filter — declines under default polymorphic mode. */
  @Test
  public void hasLabelMultiLabel_withPropertyFilter_declines() {
    seedPersonEmployeeHierarchy();
    graph.addVertex(T.label, "Person", "name", "Zara");
    graph.tx().commit();
    assertEquivalent(
        "g.V().hasLabel(Person, Employee).has(name, Eve)",
        Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Person", "Employee").has("name", "Eve"));
  }

  /** Three-label {@code hasLabel} via {@code within(...)} — declines under default polymorphic mode. */
  @Test
  public void hasLabelThreeLabels_declines() {
    var person = session.createVertexClass("Person");
    session.getSchema().createClass("Employee", person);
    session.getSchema().createClass("Manager", person);
    graph.addVertex(T.label, "Person", "name", "p");
    graph.addVertex(T.label, "Employee", "name", "e");
    graph.addVertex(T.label, "Manager", "name", "m");
    graph.tx().commit();
    assertEquivalent(
        "g.V().hasLabel(Person, Employee, Manager)",
        Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Person", "Employee", "Manager"));
  }

  /**
   * Never-used label declines to native and returns empty on both arms. A non-existent class is
   * resolved at execution time by native (it may be created later in the same tx), so a translated
   * plan compiled against a schema without the class would diverge; declining keeps on==off.
   */
  @Test
  public void hasLabelNonExistent_declinesEmptyBothArms() {
    seedPersonEmployeeHierarchy();
    assertEquivalent(
        "g.V().hasLabel(Foo)",
        Recognition.DECLINED,
        Cardinality.MAY_BE_EMPTY,
        () -> graph.traversal().V().hasLabel("Foo"));
  }

  /** Post-hop multi-label on a hop target — declines under default polymorphic mode. */
  @Test
  public void postHopHasLabelMultiLabel_declines() {
    var person = session.createVertexClass("Person");
    session.getSchema().createClass("Employee", person);
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var eve = graph.addVertex(T.label, "Employee", "name", "Eve");
    alice.addEdge("knows", eve);
    graph.tx().commit();
    assertEquivalent(
        "g.V(alice).out(knows).hasLabel(Person, Employee)",
        Recognition.DECLINED,
        () -> graph.traversal().V(alice.id()).out("knows").hasLabel("Person", "Employee"));
  }

  // ---------------------------------------------------------------------------
  // Helpers — fixtures and assertion drivers.
  // ---------------------------------------------------------------------------

  private record VertexIds(Object alice, Object bob) {
  }

  private VertexIds seedKnowsWithSince() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    alice.addEdge("knows", bob, "since", 2010);
    graph.tx().commit();
    return new VertexIds(alice.id(), bob.id());
  }

  private VertexIds seedKnowsWithSinceAndNames() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    var carol = graph.addVertex(T.label, "Person", "name", "Carol");
    alice.addEdge("knows", bob, "since", 2010, "name", "ab");
    alice.addEdge("knows", carol, "since", 2011, "name", "ac");
    graph.tx().commit();
    return new VertexIds(alice.id(), bob.id());
  }

  private VertexIds seedBidirectionalKnows() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    var carol = graph.addVertex(T.label, "Person", "name", "Carol");
    alice.addEdge("knows", bob, "since", 2010);
    carol.addEdge("knows", alice, "since", 2011);
    graph.tx().commit();
    return new VertexIds(alice.id(), bob.id());
  }

  private void seedOrderedPeople() {
    graph.addVertex(T.label, "Person", "name", "Ann", "age", 20);
    graph.addVertex(T.label, "Person", "name", "Ben", "age", 30);
    graph.addVertex(T.label, "Person", "name", "Cy", "age", 20);
    graph.tx().commit();
  }

  private void seedPersonEmployeeHierarchy() {
    var person = session.createVertexClass("Person");
    session.getSchema().createClass("Employee", person);
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Employee", "name", "Eve");
    graph.tx().commit();
  }

  private void assertEquivalent(
      String scenario,
      Recognition expected,
      Supplier<GraphTraversal<?, ?>> traversalSupplier) {
    assertEquivalentInternal(scenario, expected, Cardinality.NON_EMPTY, traversalSupplier, false);
  }

  private void assertEquivalent(
      String scenario,
      Recognition expected,
      Cardinality cardinality,
      Supplier<GraphTraversal<?, ?>> traversalSupplier) {
    assertEquivalentInternal(scenario, expected, cardinality, traversalSupplier, false);
  }

  private void assertEquivalentOrdered(
      String scenario,
      Recognition expected,
      Supplier<GraphTraversal<?, ?>> traversalSupplier) {
    assertEquivalentInternal(scenario, expected, Cardinality.NON_EMPTY, traversalSupplier, true);
  }

  private void assertEquivalentInternal(
      String scenario,
      Recognition expected,
      Cardinality cardinality,
      Supplier<GraphTraversal<?, ?>> traversalSupplier,
      boolean ordered) {
    support.assertEquivalent(
        scenario,
        expected,
        cardinality,
        results -> canonicalize(results, ordered),
        traversalSupplier);
  }

  private static List<String> canonicalize(List<?> results, boolean ordered) {
    var mapped = new ArrayList<String>(results.size());
    for (Object result : results) {
      mapped.add(canonicalizeOne(result));
    }
    if (!ordered) {
      mapped.sort(Comparator.naturalOrder());
    }
    return mapped;
  }

  private static String canonicalizeOne(Object value) {
    if (value == null) {
      return "null";
    }
    if (value instanceof Vertex vertex) {
      return "V:" + Objects.toString(vertex.id());
    }
    if (value instanceof Map<?, ?> map) {
      return map.entrySet().stream()
          .sorted(Comparator.comparing(e -> Objects.toString(e.getKey())))
          .map(e -> canonicalizeOne(e.getKey()) + "=" + canonicalizeOne(e.getValue()))
          .collect(Collectors.joining(",", "{", "}"));
    }
    if (value instanceof Collection<?> collection) {
      return collection.stream().map(Phase1GapClosureEquivalenceTest::canonicalizeOne).sorted()
          .collect(Collectors.joining(",", "[", "]"));
    }
    if (value instanceof Number number) {
      if (number.doubleValue() == Math.rint(number.doubleValue())) {
        return "N:" + number.longValue();
      }
      return "N:" + number.doubleValue();
    }
    return value.getClass().getSimpleName() + ":" + value;
  }
}
