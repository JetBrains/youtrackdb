package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.collate.CaseInsensitiveCollate;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.lambda.CollatedSortKeyTraversal;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.lambda.RecordIdSortKeyTraversal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import java.util.Comparator;
import java.util.List;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.Scope;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.ValueTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderLocalStep;
import org.apache.tinkerpop.gremlin.structure.T;
import org.javatuples.Pair;
import org.junit.Test;

/**
 * {@link YTDBOrderCollationStrategy} moves a native {@code order().by(key)} onto the collation the
 * property declares, and leaves everything else exactly as it found it.
 *
 * <p>The inertness cases carry as much weight as the replacement ones. A property that declares no
 * collation must keep the plain TinkerPop comparison, because that is what the declaration says, and
 * anything else would change unrelated Gremlin results.
 */
public class YTDBOrderCollationStrategyTest extends GraphBaseTest {

  /**
   * Scenario: an element sort by a property declared case-insensitive. Expected: the plain property
   * modulator is replaced by the collated key, carrying the same property name, the same collation,
   * and the same sort direction on that slot.
   */
  @Test
  public void apply_declaredCaseInsensitiveProperty_replacesThePropertyModulator() {
    declareCaseInsensitiveName("Person");

    var admin = graph.traversal().V().order().by("name", Order.desc).asAdmin();
    YTDBOrderCollationStrategy.instance().apply(admin);

    var comparators = comparators(orderStep(admin));
    assertThat(comparators).hasSize(1);
    var modulator = comparators.get(0).getValue0();
    assertThat(modulator).isInstanceOf(CollatedSortKeyTraversal.class);
    var collated = (CollatedSortKeyTraversal<?>) modulator;
    assertThat(collated.getPropertyKey()).isEqualTo("name");
    assertThat(collated.getCollate()).isInstanceOf(CaseInsensitiveCollate.class);
    assertThat(comparators.get(0).getValue1()).isEqualTo(Order.desc);
  }

  /**
   * Scenario: an element sort by a property that declares no collation. Expected: the modulator is
   * untouched, because the default collation is plain case-sensitive comparison, which is what
   * TinkerPop already does.
   */
  @Test
  public void apply_defaultCollationProperty_leavesTheModulatorAlone() {
    var person = session.createVertexClass("Person");
    person.createProperty("name", PropertyType.STRING);

    var admin = graph.traversal().V().order().by("name").asAdmin();
    YTDBOrderCollationStrategy.instance().apply(admin);

    assertThat(comparators(orderStep(admin)).get(0).getValue0())
        .isInstanceOf(ValueTraversal.class);
  }

  /**
   * Scenario: a property name that one vertex class declares case-insensitive while another declares
   * with the default collation. Expected: nothing is replaced, because a stream can hold both
   * classes and no single rule governs the column — the same fallback the engine comparison makes
   * for a polymorphic target.
   */
  @Test
  public void apply_graphClassesDisagree_leavesTheModulatorAlone() {
    declareCaseInsensitiveName("Person");
    var animal = session.createVertexClass("Animal");
    animal.createProperty("name", PropertyType.STRING);

    var admin = graph.traversal().V().order().by("name").asAdmin();
    YTDBOrderCollationStrategy.instance().apply(admin);

    assertThat(comparators(orderStep(admin)).get(0).getValue0())
        .isInstanceOf(ValueTraversal.class);
  }

  /**
   * Scenario: the same two disagreeing classes, with the traversal constrained to the one that
   * declares the collation. Expected: the modulator is replaced, because the sort can only see rows
   * of that class and the other declaration governs nothing here.
   *
   * <p>This is the rule the MATCH planner already follows: the translator re-types the pattern node
   * to the label, and the planner reads the declaration off that class alone. Resolving over every
   * vertex and edge class instead made the two arms answer two different orders for one query.
   */
  @Test
  public void apply_labelConstrainedStream_readsTheDeclarationOfThatLabelAlone() {
    declareCaseInsensitiveName("Person");
    var animal = session.createVertexClass("Animal");
    animal.createProperty("name", PropertyType.STRING);

    var admin = graph.traversal().V().hasLabel("Person").order().by("name").asAdmin();
    YTDBOrderCollationStrategy.instance().apply(admin);

    assertThat(comparators(orderStep(admin)).get(0).getValue0())
        .isInstanceOf(CollatedSortKeyTraversal.class);
  }

  /**
   * Scenario: a multi-label traversal includes a case-insensitive class and a default-collate class.
   * Expected: no replacement, because the translated class-membership filter remains rooted at the
   * generic source class and the combined stream has no single non-default collation.
   */
  @Test
  public void apply_multiLabelConstraint_usesTheGenericSourceHierarchy() {
    declareCaseInsensitiveName("Person");
    var animal = session.createVertexClass("Animal");
    animal.createProperty("name", PropertyType.STRING);

    var admin = graph.traversal().V().hasLabel("Person", "Animal").order().by("name").asAdmin();
    YTDBOrderCollationStrategy.instance().apply(admin);

    assertThat(comparators(orderStep(admin)).get(0).getValue0())
        .isInstanceOf(ValueTraversal.class);
  }

  /**
   * Scenario: the traversal constrained to the class that declares nothing, while a sibling class
   * declares the collation. Expected: no replacement, because the constrained class governs the
   * column and it declares the default collation.
   */
  @Test
  public void apply_labelConstrainedStream_ignoresASiblingDeclaration() {
    declareCaseInsensitiveName("Person");
    var animal = session.createVertexClass("Animal");
    animal.createProperty("name", PropertyType.STRING);

    var admin = graph.traversal().V().hasLabel("Animal").order().by("name").asAdmin();
    YTDBOrderCollationStrategy.instance().apply(admin);

    assertThat(comparators(orderStep(admin)).get(0).getValue0())
        .isInstanceOf(ValueTraversal.class);
  }

  /**
   * Scenario: an edge class declares the sorted property name with the default collation while a
   * vertex class declares it case-insensitive, and the traversal sorts vertices. Expected: the
   * vertex declaration is followed, because an edge class is not in the stream at all.
   */
  @Test
  public void apply_edgeClassDeclarationDoesNotGovernAVertexSort() {
    declareCaseInsensitiveName("Person");
    var knows = session.createEdgeClass("Knows");
    knows.createProperty("name", PropertyType.STRING);

    var admin = graph.traversal().V().order().by("name").asAdmin();
    YTDBOrderCollationStrategy.instance().apply(admin);

    assertThat(comparators(orderStep(admin)).get(0).getValue0())
        .isInstanceOf(CollatedSortKeyTraversal.class);
  }

  /**
   * Scenario: the strategy applied twice to one traversal. Expected: the second application changes
   * nothing, because it replaces a plain property modulator only and the first application left a
   * collated key in that slot.
   */
  @Test
  public void apply_appliedTwice_changesNothing() {
    declareCaseInsensitiveName("Person");

    var admin = graph.traversal().V().order().by("name").asAdmin();
    YTDBOrderCollationStrategy.instance().apply(admin);
    var afterFirst = renderComparators(orderStep(admin));

    YTDBOrderCollationStrategy.instance().apply(admin);

    assertThat(renderComparators(orderStep(admin))).isEqualTo(afterFirst);
    assertThat(afterFirst).hasSize(1);
  }

  /**
   * Scenario: the record identifier tie-break key already appended to the sort. Expected: the
   * property slot becomes collated and the tie-break slot is left alone, because that key compares
   * identifiers rather than text and no collation applies to it.
   */
  @Test
  public void apply_leavesTheRecordIdTieBreakSlotAlone() {
    declareCaseInsensitiveName("Person");

    var admin = graph.traversal().V().order().by("name").asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);
    YTDBOrderCollationStrategy.instance().apply(admin);

    var comparators = comparators(orderStep(admin));
    assertThat(comparators).hasSize(2);
    assertThat(comparators.get(0).getValue0()).isInstanceOf(CollatedSortKeyTraversal.class);
    assertThat(comparators.get(1).getValue0()).isInstanceOf(RecordIdSortKeyTraversal.class);
  }

  /**
   * Scenario: a local sort of folded elements by a property declared case-insensitive. Expected: the
   * local slot is collated too, because {@code order(local)} compares the same projected values.
   */
  @Test
  public void apply_localOrderOverFoldedElements_replacesThePropertyModulator() {
    declareCaseInsensitiveName("Person");

    var admin = graph.traversal().V().fold().order(Scope.local).by("name").asAdmin();
    YTDBOrderCollationStrategy.instance().apply(admin);

    var comparators = localComparators(localOrderStep(admin));
    assertThat(comparators).hasSize(1);
    assertThat(comparators.get(0).getValue0()).isInstanceOf(CollatedSortKeyTraversal.class);
  }

  /**
   * Scenario: a sort key that is a traversal rather than a plain property projection. Expected: no
   * replacement, because the declaration governs one property of one record and such a traversal may
   * not be reading one.
   */
  @Test
  public void apply_traversalSortKey_leavesTheModulatorAlone() {
    declareCaseInsensitiveName("Person");

    var admin = graph.traversal().V().order().by(__.values("name")).asAdmin();
    YTDBOrderCollationStrategy.instance().apply(admin);

    assertThat(comparators(orderStep(admin)).get(0).getValue0())
        .isNotInstanceOf(CollatedSortKeyTraversal.class);
  }

  /**
   * Scenario: three names differing only in letter case, sorted natively on the declined pipeline.
   * Expected: case-insensitive order, {@code ada} first, rather than the code-point order that puts
   * every capital ahead of every lower-case letter.
   */
  @Test
  public void execute_declaredCaseInsensitiveProperty_ordersIgnoringLetterCase() {
    disableTranslator();
    declareCaseInsensitiveName("Person");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.addVertex(T.label, "Person", "name", "ada");
    graph.addVertex(T.label, "Person", "name", "Cara");
    graph.tx().commit();

    assertThat(graph.traversal().V().order().by("name").values("name").toList())
        .containsExactly("ada", "Bob", "Cara");
  }

  /**
   * Scenario: one vertex carrying no value for the ordered property, sorted natively on the declined
   * pipeline. Expected: that row is dropped, exactly as it is when the plain property modulator does
   * the projection. The drop is part of the answer of {@code order().by(key)}, so the replacement
   * modulator has to reproduce it.
   */
  @Test
  public void execute_rowWithoutTheOrderedProperty_isStillDropped() {
    disableTranslator();
    declareCaseInsensitiveName("Person");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.addVertex(T.label, "Person", "name", "ada");
    graph.addVertex(T.label, "Person", "tag", "nameless");
    graph.tx().commit();

    assertThat(graph.traversal().V().count().next())
        .as("the fixture must hold the nameless vertex, or the drop below is vacuous")
        .isEqualTo(3L);
    assertThat(graph.traversal().V().order().by("name").values("name").toList())
        .containsExactly("ada", "Bob");
  }

  /** A vertex class whose {@code name} property is declared case-insensitive. */
  private void declareCaseInsensitiveName(String className) {
    var vertexClass = session.createVertexClass(className);
    vertexClass.createProperty("name", PropertyType.STRING).setCollate("ci");
  }

  /**
   * Forces the executing cases onto the native pipeline, which is the arm this strategy rewrites. A
   * translated shape has no {@code order()} step left and is covered by the equivalence suite
   * instead.
   */
  private void disableTranslator() {
    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    tx.getDatabaseSession()
        .getConfiguration()
        .setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, false);
  }

  /** Comparator slots as text, so two applications of the strategy can be compared as lists. */
  @SuppressWarnings("rawtypes")
  private static List<String> renderComparators(OrderGlobalStep step) {
    return comparators(step).stream()
        .map(pair -> pair.getValue0() + "/" + pair.getValue1())
        .toList();
  }

  private static OrderGlobalStep<?, ?> orderStep(Traversal.Admin<?, ?> admin) {
    return admin.getSteps().stream()
        .filter(OrderGlobalStep.class::isInstance)
        .map(step -> (OrderGlobalStep<?, ?>) step)
        .findFirst()
        .orElseThrow();
  }

  private static OrderLocalStep<?, ?> localOrderStep(Traversal.Admin<?, ?> admin) {
    return admin.getSteps().stream()
        .filter(OrderLocalStep.class::isInstance)
        .map(step -> (OrderLocalStep<?, ?>) step)
        .findFirst()
        .orElseThrow();
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static List<Pair<Traversal.Admin, Comparator>> comparators(OrderGlobalStep step) {
    return step.getComparators();
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static List<Pair<Traversal.Admin, Comparator>> localComparators(
      OrderLocalStep<?, ?> step) {
    return (List) step.getComparators();
  }
}
