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
import org.apache.tinkerpop.gremlin.structure.T;
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
   * is followed on both arms, so {@code ada} sorts beside {@code Ada} instead of after {@code Zebra},
   * and the two spellings of that one name keep a stable relative order because the collation falls
   * back to the raw comparison when the folded forms tie.
   */
  @Test
  public void caseInsensitiveDeclaredProperty_ordersByTheDeclarationOnBothArms() {
    var person = session.createVertexClass("Person");
    person.createProperty("name", PropertyType.STRING).setCollate("ci");
    seedNames("Person");

    assertEquivalentOrdered(
        "declared ci: g.V().order().by(name).values(name)",
        () -> graph.traversal().V().order().by("name").values("name"));

    assertThat(graph.traversal().V().order().by("name").values("name").toList())
        .as("a property declared case-insensitive orders by that declaration")
        .containsExactly("Ada", "ada", "Bob", "Cara", "Zebra", "Ähhhh");
  }

  /**
   * Scenario: the descending direction over the declared case-insensitive property. Expected: the
   * exact reverse of the ascending sequence, on both arms, which pins that the direction is applied
   * to the collated comparison rather than beside it.
   */
  @Test
  public void caseInsensitiveDeclaredProperty_descendingMatchesOnBothArms() {
    var person = session.createVertexClass("Person");
    person.createProperty("name", PropertyType.STRING).setCollate("ci");
    seedNames("Person");

    assertEquivalentOrdered(
        "declared ci desc: g.V().order().by(name, desc).values(name)",
        () -> graph.traversal().V().order().by("name", Order.desc).values("name"));

    assertThat(graph.traversal().V().order().by("name", Order.desc).values("name").toList())
        .as("descending must reverse the collated order, not the plain one")
        .containsExactly("Ähhhh", "Zebra", "Cara", "Bob", "ada", "Ada");
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
