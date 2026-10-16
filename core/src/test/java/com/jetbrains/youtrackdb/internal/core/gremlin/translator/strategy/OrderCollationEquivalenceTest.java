package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

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
