package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.Cardinality;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.Recognition;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/**
 * Sequence equality between the translated arm and the native arm for the element {@code order()}
 * shapes that carry the appended record identifier sort key. Rows are compared in arrival order, so
 * a tie broken differently on the two arms fails here — a multiset renderer would hide exactly the
 * defect these cases exist to pin.
 *
 * <p>The transaction case is the reason the key is a projected type rather than the record
 * identifier itself. A vertex created inside an open transaction carries a different identifier
 * class from a committed one, and TinkerPop orderability compares two sibling classes by class name
 * before it compares values, so the native arm grouped every pending vertex ahead of every committed
 * one while the translated arm sorted all of them numerically.
 *
 * <p>The two property {@code id} cases cover the deleted skip rule. A sort whose last key was a
 * property named {@code id} used to receive no appended key at all, because that property was
 * assumed unique per class.
 */
public class OrderRidTieBreakEquivalenceTest extends GraphBaseTest {

  /** The tags of {@link #seedDuplicateIdPeople} in the order they were inserted. */
  private static final List<String> INSERTION_ORDER_TAGS =
      List.of("b-first", "a-first", "b-second", "a-second");

  /** The tags of {@link #seedDuplicateIdHopTargets} in the order their edges were added. */
  private static final List<String> EDGE_INSERTION_ORDER_TAGS =
      List.of("t1", "t2", "t3", "t4");

  private final TranslatorEquivalenceSupport support =
      new TranslatorEquivalenceSupport(() -> session);

  /**
   * Bare {@code order()} over vertices. The strategy replaces the synthetic identity slot with the
   * record identifier key, and the translation reads the same slot as {@code @rid}, so the tag
   * sequence must match. Tags differ per vertex, so the sequence is observable.
   */
  @Test
  public void bareOrderOverElements_matchesNative() {
    seedTaggedPeople();

    assertEquivalentOrdered(
        "g.V().order().values(tag)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().order().values("tag"));
  }

  /** A user-written {@code by(T.id)} becomes the same key, so it keeps native sequence too. */
  @Test
  public void orderByTokenIdOverElements_matchesNative() {
    seedTaggedPeople();

    assertEquivalentOrdered(
        "g.V().order().by(T.id).values(tag)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().order().by(T.id).values("tag"));
  }

  /**
   * Every age is equal, so the written sort key separates nothing and the appended key decides the
   * whole sequence. The expected sequence is computed here from the identifiers themselves,
   * collection first and position second, which is the order the key promises and the order a MATCH
   * {@code @rid} item produces.
   */
  @Test
  public void orderByTiedProperty_matchesNativeAndFollowsRecordIdOrder() {
    seedTaggedPeople();

    assertEquivalentOrdered(
        "g.V().order().by(age).values(tag)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().order().by("age").values("tag"));

    var byRecordId = tagsInRecordIdOrder();
    assertThat(byRecordId).as("the oracle must cover every seeded row").hasSize(3);
    assertThat(graph.traversal().V().order().by("age").values("tag").toList())
        .as("tied ages leave the appended record identifier key in charge")
        .isEqualTo(byRecordId);
  }

  /**
   * A descending primary key with an ascending appended key. The appended key is always ascending,
   * whatever the primary direction, so the two arms have to agree on that too.
   */
  @Test
  public void orderByPropertyDescWithTies_matchesNative() {
    seedTaggedPeople();

    assertEquivalentOrdered(
        "g.V().order().by(age, desc).values(tag)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().order().by("age", Order.desc).values("tag"));
  }

  /**
   * An edge hop before the sort. {@code out()} is a flat-map step, so this stream was unproven
   * before the classification fix and received a bare identity instead of the key — which orders
   * mixed identifier classes by class name.
   */
  @Test
  public void edgeHopThenOrderByProperty_matchesNative() {
    seedKnowsChain();

    assertEquivalentOrdered(
        "g.V().out(knows).order().by(name).values(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().out("knows").order().by("name").values("name"));
  }

  /** The same hop with a tied sort key, so the appended key alone fixes the sequence. */
  @Test
  public void edgeHopThenOrderByTiedProperty_matchesNative() {
    seedKnowsChain();

    assertEquivalentOrdered(
        "g.V().out(knows).order().by(age).values(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().out("knows").order().by("age").values("name"));
  }

  /**
   * The parity case that motivates the projected key. Three vertices are committed and two more are
   * created in the still-open transaction, all five tied on {@code age}, so only the appended key
   * orders them. Both arms must return one sequence over the mixture of identifier classes.
   */
  @Test
  public void openTransactionTiedCommittedAndPendingRows_matchOnBothArms() {
    graph.addVertex(T.label, "Person", "age", 30, "tag", "committed-1");
    graph.addVertex(T.label, "Person", "age", 30, "tag", "committed-2");
    graph.addVertex(T.label, "Person", "age", 30, "tag", "committed-3");
    graph.tx().commit();

    // Left uncommitted on purpose: these two carry a changeable record identifier while the three
    // above carry an immutable one, and one ordered query sees both classes.
    graph.addVertex(T.label, "Person", "age", 30, "tag", "pending-1");
    graph.addVertex(T.label, "Person", "age", 30, "tag", "pending-2");

    assertThat(graph.traversal().V().count().next())
        .as("the open transaction must expose the pending vertices to the sort")
        .isEqualTo(5L);
    assertEquivalentOrdered(
        "open tx: g.V().order().by(age).values(tag)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().order().by("age").values("tag"));
  }

  /**
   * Several vertices sharing one value in a property named {@code id}. The strategy used to skip a
   * sort whose last key was such a property, on the assumption that it was unique per class. Two
   * rows share each of the two values here, so the appended key is the only thing that orders a
   * group, and without it the two arms were free to answer different sequences.
   */
  @Test
  public void duplicateIdPropertyWithoutABound_matchesNativeAndFollowsRecordIdOrder() {
    seedDuplicateIdPeople();

    assertEquivalentOrdered(
        "g.V().order().by(id).values(tag)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().order().by("id").values("tag"));

    var oracle = tagsByIdThenRecordIdOrder();
    assertThat(oracle).as("the oracle must cover every seeded row").hasSize(4);
    assertThat(oracle)
        .as("the fixture must separate sorted order from insertion order, or the case is blind")
        .isNotEqualTo(INSERTION_ORDER_TAGS);
    assertThat(graph.traversal().V().order().by("id").values("tag").toList())
        .as("the duplicate id values tie, so the appended key decides inside each group")
        .isEqualTo(oracle);
  }

  /**
   * The discriminating duplicate {@code id} shape. A hop puts its targets in edge insertion order,
   * which this fixture makes the reverse of the record identifier order, so a stable sort over one
   * tie group and a record identifier sort answer opposite sequences. A plain scan cannot witness
   * that, because there arrival order and record identifier order are the same order.
   */
  @Test
  public void duplicateIdPropertyAfterAHopWithoutABound_followsRecordIdOrder() {
    seedDuplicateIdHopTargets();

    assertEquivalentOrdered(
        "g.V().out(knows).order().by(id).values(tag)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().out("knows").order().by("id").values("tag"));

    var oracle = hopTargetTagsInRecordIdOrder();
    assertThat(oracle).as("the oracle must cover every hop target").hasSize(4);
    assertThat(oracle)
        .as("the fixture must separate record identifier order from edge insertion order, or a "
            + "missing tie-break cannot be witnessed here")
        .isNotEqualTo(EDGE_INSERTION_ORDER_TAGS);
    assertThat(graph.traversal().V().out("knows").order().by("id").values("tag").toList())
        .as("every target ties on id, so the appended key decides the whole sequence")
        .isEqualTo(oracle);
  }

  /**
   * The same hop under a bound of three, which cuts inside the single four-row tie group. The shape
   * declines, because a real slice behind a captured {@code ORDER BY} does, so the prefix assertion
   * rather than the two-arm comparison carries the claim: which three targets survive is decided by
   * the appended key.
   */
  @Test
  public void duplicateIdPropertyAfterAHopWithALimit_keepsTheOrderedPrefix() {
    seedDuplicateIdHopTargets();

    assertEquivalentOrdered(
        "g.V().out(knows).order().by(id).limit(3).values(tag)",
        Recognition.DECLINED,
        () -> graph.traversal().V().out("knows").order().by("id").limit(3).values("tag"));

    var oracle = hopTargetTagsInRecordIdOrder();
    assertThat(oracle)
        .as("the fixture must separate record identifier order from edge insertion order")
        .isNotEqualTo(EDGE_INSERTION_ORDER_TAGS);
    assertThat(
        graph.traversal().V().out("knows").order().by("id").limit(3).values("tag").toList())
        .as("a bound cutting inside a tie group must keep the ordered prefix")
        .isEqualTo(oracle.subList(0, 3));
  }

  /**
   * The tags of every stored vertex, sorted by collection identifier and then by collection
   * position. An independent oracle for the appended key, read off the identifiers rather than off
   * an ordered query.
   */
  private List<String> tagsInRecordIdOrder() {
    return graph.traversal().V().toList().stream()
        .sorted(byRecordId())
        .map(vertex -> vertex.<String>value("tag"))
        .toList();
  }

  /**
   * Four people over two values of a property named {@code id}, two rows each, inserted so that
   * insertion order and sorted order disagree. Sorting by {@code id} therefore leaves two tie groups
   * of two, which is what a bound of three cuts into.
   */
  private void seedDuplicateIdPeople() {
    graph.addVertex(T.label, "Person", "id", "b", "tag", "b-first");
    graph.addVertex(T.label, "Person", "id", "a", "tag", "a-first");
    graph.addVertex(T.label, "Person", "id", "b", "tag", "b-second");
    graph.addVertex(T.label, "Person", "id", "a", "tag", "a-second");
    graph.tx().commit();
  }

  /**
   * One source knowing four targets that all share one value in a property named {@code id}. The
   * targets are created in the reverse of the order their edges are added, so the hop's arrival order
   * and the record identifier order are opposite sequences over one tie group. A fixture where the
   * two coincide cannot witness a missing tie-break, because a stable sort on equal keys returns
   * arrival order and the record identifier sort returns the other one.
   */
  private void seedDuplicateIdHopTargets() {
    var fourth = graph.addVertex(T.label, "Person", "id", "dup", "tag", "t4");
    var third = graph.addVertex(T.label, "Person", "id", "dup", "tag", "t3");
    var second = graph.addVertex(T.label, "Person", "id", "dup", "tag", "t2");
    var first = graph.addVertex(T.label, "Person", "id", "dup", "tag", "t1");
    var source = graph.addVertex(T.label, "Person", "tag", "source");
    source.addEdge("knows", first);
    source.addEdge("knows", second);
    source.addEdge("knows", third);
    source.addEdge("knows", fourth);
    graph.tx().commit();
  }

  /**
   * The tags of every stored vertex, sorted by the {@code id} property and then by collection
   * identifier and position. The oracle for the plain-scan duplicate {@code id} case: the written key
   * first, the appended record identifier key second, read off the stored values themselves.
   */
  private List<String> tagsByIdThenRecordIdOrder() {
    return graph.traversal().V().toList().stream()
        .sorted(
            Comparator
                .comparing((Vertex vertex) -> vertex.<String>value("id"))
                .thenComparing(byRecordId()))
        .map(vertex -> vertex.<String>value("tag"))
        .toList();
  }

  /** The tags of the hop's targets in record identifier order — they all tie on {@code id}. */
  private List<String> hopTargetTagsInRecordIdOrder() {
    return graph.traversal().V().out("knows").toList().stream()
        .sorted(byRecordId())
        .map(vertex -> vertex.<String>value("tag"))
        .toList();
  }

  /** Collection identifier first, collection position second — the order the appended key promises. */
  private static Comparator<Vertex> byRecordId() {
    return Comparator
        .comparingInt((Vertex vertex) -> ((RID) vertex.id()).getCollectionId())
        .thenComparingLong(vertex -> ((RID) vertex.id()).getCollectionPosition());
  }

  /** Three people with distinct tags and one shared age, committed. */
  private void seedTaggedPeople() {
    graph.addVertex(T.label, "Person", "age", 30, "tag", "c");
    graph.addVertex(T.label, "Person", "age", 30, "tag", "a");
    graph.addVertex(T.label, "Person", "age", 30, "tag", "b");
    graph.tx().commit();
  }

  /** One source knowing three targets that share an age, so a hop yields a tied element stream. */
  private void seedKnowsChain() {
    var source = graph.addVertex(T.label, "Person", "name", "source", "age", 10);
    var zoe = graph.addVertex(T.label, "Person", "name", "Zoe", "age", 30);
    var ada = graph.addVertex(T.label, "Person", "name", "Ada", "age", 30);
    var bob = graph.addVertex(T.label, "Person", "name", "Bob", "age", 30);
    source.addEdge("knows", zoe);
    source.addEdge("knows", ada);
    source.addEdge("knows", bob);
    graph.tx().commit();
  }

  private void assertEquivalentOrdered(
      String scenario, Recognition expected, Supplier<GraphTraversal<?, ?>> traversalSupplier) {
    support.assertEquivalent(
        scenario,
        expected,
        Cardinality.NON_EMPTY,
        OrderRidTieBreakEquivalenceTest::orderedRows,
        traversalSupplier);
  }

  /** Arrival-order rendering: sorting here would hide the sequence differences under test. */
  private static List<String> orderedRows(List<?> results) {
    return results.stream().map(OrderRidTieBreakEquivalenceTest::render).toList();
  }

  private static String render(Object value) {
    return value instanceof Vertex vertex ? "V:" + vertex.id() : String.valueOf(value);
  }
}
