package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.Cardinality;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.Recognition;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.IndexOrderedEdgeStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchStep;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Sequence equality between the two arms for the ordered shape that reaches the index-ordered scan.
 * The translated arm streams that scan and never sorts, while the declined arm sorts on the native
 * traverser pipeline, so these cases are the only place where the restored shortcut is compared
 * against a sort that actually ran.
 *
 * <p>Rows are compared in arrival order and rendered as identifiers, because the fixture ties every
 * sort key several times over and the appended record identifier item is what separates the tied
 * rows. A sorting renderer would compare the same forty rows against themselves.
 *
 * <p>The in-heap cap is lowered for the ascending cases, which pins the streaming as well: a
 * translated arm that buffered would exceed the cap and fail the query instead of returning rows.
 * The descending case runs at the normal cap, because it buffers by design — its own Javadoc says
 * why.
 *
 * <p>Each case also pins the absolute sequence, not only the agreement of the two arms. Agreement
 * alone is satisfied by two arms that are wrong in the same way, so the sequence is computed here
 * from the stored rows: the sort key, then the identifier breaking its ties.
 *
 * <p>That cap is a global setting, so this class runs in the sequential surefire execution. Left in
 * the parallel one it would lower the cap under whatever else was running at the time.
 */
@Category(SequentialTest.class)
public class OrderIndexShortcutEquivalenceTest extends GraphBaseTest {

  /** Targets per fixture, four times the lowered cap. */
  private static final int TARGETS = 40;

  /** The lowered in-heap cap, below which any buffering sort fails. */
  private static final int LOW_HEAP_CAP = 10;

  private final TranslatorEquivalenceSupport support =
      new TranslatorEquivalenceSupport(() -> session);

  /**
   * Scenario: an ascending ordered hop onto an indexed property whose values repeat, with no limit.
   * Expected: the translated arm, which streams the ascending index scan, returns the same sequence
   * as the native sort.
   */
  @Test
  public void ascendingOrderOverIndexedProperty_matchesNative() {
    seedTargets();

    withLoweredHeapCap(() -> assertEquivalentOrdered(
        "g.V().hasLabel(Src).out(LINK).hasLabel(Tgt).order().by(score)",
        () -> targets().order().by("score"),
        expectedOrder("score", true)));
  }

  /**
   * Scenario: the descending form of the same shape. Expected: the same sequence equality, at the
   * normal in-heap cap.
   *
   * <p>The cap stays where it is because this shape buffers. A descending scan hands its null-key
   * group back in ascending identifier order whichever direction it runs, and the presence conjunct
   * the translation states does not remove that group — a property stored as an explicit null is
   * present. The descending shapes that do stream carry a null-excluding filter or a null-free index,
   * and they live in {@code IndexOrderedRidTieBreakTest}.
   */
  @Test
  public void descendingOrderOverIndexedProperty_matchesNative() {
    seedTargets();

    assertEquivalentOrdered(
        "g.V().hasLabel(Src).out(LINK).hasLabel(Tgt).order().by(score, desc)",
        () -> targets().order().by("score", Order.desc),
        expectedOrder("score", false));
  }

  /**
   * Scenario: an ascending sort over an indexed text property that declares no collation. Expected:
   * equality again, because the index compares text exactly as the default collation does.
   */
  @Test
  public void ascendingTextOrderOverIndexedProperty_matchesNative() {
    seedTargets();

    withLoweredHeapCap(() -> assertEquivalentOrdered(
        "g.V().hasLabel(Src).out(LINK).hasLabel(Tgt).order().by(name)",
        () -> targets().order().by("name"),
        expectedOrder("name", true)));
  }

  /** A two-key buffered sort after an ordered hop must keep each Message paired with its Reply. */
  @Test
  public void downstreamBindingsMatchNativeAfterOrderedHop() {
    session.execute("CREATE CLASS Person EXTENDS V").close();
    session.execute("CREATE PROPERTY Person.id INTEGER").close();
    session.execute("CREATE INDEX Person_id ON Person (id) UNIQUE").close();
    session.execute("CREATE CLASS Message EXTENDS V").close();
    session.execute("CREATE PROPERTY Message.creationDate INTEGER").close();
    session.execute("CREATE INDEX Message_date ON Message (creationDate) NOTUNIQUE").close();
    session.execute("CREATE CLASS Reply EXTENDS V").close();
    session.execute("CREATE CLASS LIKES EXTENDS E").close();
    session.execute("CREATE CLASS NEXT EXTENDS E").close();
    session.begin();
    session.execute("CREATE VERTEX Person SET id = 0").close();
    for (var i = 0; i < 2; i++) {
      session.execute("CREATE VERTEX Message SET creationDate = " + i).close();
      session.execute("CREATE VERTEX Reply SET id = " + i).close();
      session.execute("CREATE EDGE LIKES FROM (SELECT FROM Person)"
          + " TO (SELECT FROM Message WHERE creationDate = " + i + ")").close();
      session.execute("CREATE EDGE NEXT FROM (SELECT FROM Message WHERE creationDate = " + i
          + ") TO (SELECT FROM Reply WHERE id = " + i + ")").close();
    }
    session.commit();
    // Label the hops and keep property filters on both targets. Native filter folding then
    // retains the bindings on property-bearing steps, while MATCH still types both targets.
    Supplier<GraphTraversal<?, ?>> traversal = () -> graph.traversal().V()
        .hasLabel("Person").has("id", 0)
        .out("LIKES").as("m").hasLabel("Message").has("creationDate", P.gte(0))
        .out("NEXT").as("r").hasLabel("Reply").has("id", P.gte(0))
        .order().by(__.select("m").by("creationDate")).by("id")
        .limit(2).select("m", "r").by("creationDate").by("id");
    support.assertEquivalent("ordered hop with downstream bindings", Recognition.RECOGNIZED,
        Cardinality.NON_EMPTY, rows -> rows.stream().map(String::valueOf).toList(), traversal);
    var expected = List.of(Map.of("m", 0, "r", 0), Map.of("m", 1, "r", 1));
    support.withTranslator(false, () -> assertThat(traversal.get().toList()).isEqualTo(expected));
    support.withTranslator(true, () -> {
      var admin = traversal.get().asAdmin();
      admin.applyStrategies();
      assertThat(admin.getSteps().stream().filter(YTDBMatchPlanStep.class::isInstance).count())
          .isEqualTo(1);
      var boundary = admin.getSteps().stream().filter(YTDBMatchPlanStep.class::isInstance)
          .map(YTDBMatchPlanStep.class::cast).findFirst().orElseThrow();
      var steps = boundary.getPlan().getSteps();
      var ordered = steps.stream().filter(IndexOrderedEdgeStep.class::isInstance)
          .map(IndexOrderedEdgeStep.class::cast).findFirst().orElseThrow();
      assertThat(steps.subList(steps.indexOf(ordered) + 1, steps.size()))
          .anyMatch(MatchStep.class::isInstance);
      assertThat(admin.toList()).isEqualTo(expected);
      var executed = boundary.getPlan().getSteps().stream()
          .filter(IndexOrderedEdgeStep.class::isInstance).map(IndexOrderedEdgeStep.class::cast)
          .findFirst().orElseThrow();
      assertThat(executed.getChosenRuntimePath())
          .isEqualTo(IndexOrderedEdgeStep.RuntimePath.LOAD_SORT);
      assertThat(ordered.getChosenRuntimePath()).as("the shared template never executes").isNull();
    });
  }

  private GraphTraversal<Vertex, Vertex> targets() {
    return graph.traversal().V().hasLabel("Src").out("LINK").hasLabel("Tgt");
  }

  private void assertEquivalentOrdered(
      String scenario,
      Supplier<GraphTraversal<?, ?>> traversalSupplier,
      List<String> expected) {
    support.assertEquivalent(
        scenario,
        Recognition.RECOGNIZED,
        Cardinality.NON_EMPTY,
        OrderIndexShortcutEquivalenceTest::arrivalOrderIdentifiers,
        traversalSupplier);
    assertThat(arrivalOrderIdentifiers(traversalSupplier.get().toList()))
        .as(scenario + ": the sequence must be the sort key then the identifier")
        .isEqualTo(expected);
  }

  /**
   * The sequence the sort describes, computed here from the stored rows: {@code propertyName} then
   * the identifier, with {@code ascending} applied to both. Insertion order is not assumed to be
   * identifier order, which it need not be once a class spans several collections.
   */
  private List<String> expectedOrder(String propertyName, boolean ascending) {
    var rows = new ArrayList<>(graph.traversal().V().hasLabel("Tgt").toList());
    Comparator<Vertex> comparator =
        Comparator.<Vertex, Object>comparing(
            vertex -> vertex.value(propertyName),
            OrderIndexShortcutEquivalenceTest::compareValues)
            .thenComparing(vertex -> (Identifiable) vertex.id());
    rows.sort(ascending ? comparator : comparator.reversed());
    return rows.stream().map(vertex -> vertex.id().toString()).toList();
  }

  /** Natural order over any stored value class, which the fixture keeps to numbers and text. */
  @SuppressWarnings("unchecked")
  private static int compareValues(Object left, Object right) {
    return ((Comparable<Object>) left).compareTo(right);
  }

  /** Arrival-order identifiers: sorting here would hide the tie order under test. */
  private static List<String> arrivalOrderIdentifiers(List<?> results) {
    return results.stream().map(row -> ((Vertex) row).id().toString()).toList();
  }

  private void withLoweredHeapCap(Runnable body) {
    var previous = GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.getValue();
    GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.setValue(LOW_HEAP_CAP);
    try {
      body.run();
    } finally {
      GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.setValue(previous);
    }
  }

  /**
   * One source vertex linked to {@code TARGETS} indexed targets. Seven distinct numeric values and
   * five distinct text values over forty rows leave every sort key tied several times, which is what
   * makes the appended item decide the sequence. The target class carries the class constraint the
   * traversal needs, because an unconstrained hop target has no index to scan.
   */
  private void seedTargets() {
    var target = session.createVertexClass("Tgt");
    target.createProperty("score", PropertyType.INTEGER)
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
    target.createProperty("name", PropertyType.STRING)
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
    session.createVertexClass("Src");
    session.createEdgeClass("LINK");

    var source = graph.addVertex(T.label, "Src", "tag", "source");
    for (var i = 0; i < TARGETS; i++) {
      source.addEdge("LINK",
          graph.addVertex(T.label, "Tgt", "score", i % 7, "name", "n" + (i % 5)));
    }
    graph.tx().commit();
  }
}
