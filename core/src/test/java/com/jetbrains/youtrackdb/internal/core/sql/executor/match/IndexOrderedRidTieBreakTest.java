package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * The index-ordered shortcut over a sort that carries the appended record identifier item.
 *
 * <h2>Why the in-heap cap is the instrument</h2>
 *
 * A streaming plan and a buffering plan return the same rows, so no assertion over the answer can
 * tell them apart. Lowering {@code QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP} below the row count does:
 * a buffering sort exceeds the cap and fails the query, while a streaming one never holds more than
 * one row. Every case below therefore reads a completed query as streamed and the cap failure as
 * buffered, over a few dozen rows rather than over a large fixture.
 *
 * <h2>What each answer is checked against</h2>
 *
 * A streaming case also pins the row sequence against an oracle computed in this test from the
 * stored rows — the property ascending or descending with the identifier breaking its ties, and a
 * null property counting as the smallest value. Rows render as their identifier alone, which
 * identifies each row completely.
 *
 * <h2>The refusal cases</h2>
 *
 * A refusal case carries a control query over {@code score}, whose index is always the plain
 * single-value default-collate one. Without that control a refusal assertion would also pass for a
 * fixture the shortcut never reached at all.
 *
 * <p>The cap is a global setting, so this class runs in the sequential surefire execution. Left in
 * the parallel one it would lower the cap under whatever else was running at the time.
 */
@Category(SequentialTest.class)
public class IndexOrderedRidTieBreakTest extends GraphBaseTest {

  /** Rows per fixture: enough to exceed the lowered cap several times over. */
  private static final int SCORED_TARGETS = 40;

  /** The lowered in-heap cap. Any buffering sort over the fixture exceeds it. */
  private static final int LOW_HEAP_CAP = 10;

  /** The failure a buffering sort raises once the fixture outgrows the lowered cap. */
  private static final String BUFFERING_FAILURE = "in-heap ORDER BY";

  /** The plan text of the index-ordered step, present whenever the shortcut applies at all. */
  private static final String INDEX_ORDERED_STEP = "INDEX ORDERED MATCH";

  /**
   * Scenario: an ascending ordered query over an indexed numeric property, with no limit, whose sort
   * carries the appended record identifier item. Expected: the plan accepts that item, the query
   * streams the index scan without ever buffering, and the sequence equals the sort the two items
   * describe.
   */
  @Test
  public void ascendingIndexedNumericOrder_streamsAndKeepsTheSortSequence() {
    seedTargets(0);

    assertThat(planText(ascendingByScore()))
        .as("the appended item must be accepted on an ascending scan")
        .contains(IndexOrderedEdgeStep.RID_TIE_BREAK_MARKER);
    withLoweredHeapCap(() -> assertThat(orderedRowsOf(ascendingByScore()))
        .as("an accepted shape streams the scan and answers the two-item sort")
        .isEqualTo(expectedOrder("score", true, false)));
  }

  /**
   * Scenario: the same query over an indexed text property that declares no collation. Expected: it
   * streams as well, because the index compares text the same way the default collation does, and
   * the sequence equals the sort the two text items describe.
   */
  @Test
  public void ascendingIndexedTextOrder_streamsWhenNoCollationIsDeclared() {
    seedTargets(0);

    var query = "MATCH {class: Src, as: s}.out('LINK'){class: Tgt, as: m} RETURN m"
        + " ORDER BY m.name ASC, m.@rid ASC";
    assertThat(planText(query))
        .as("an undeclared text property compares as the index does")
        .contains(IndexOrderedEdgeStep.RID_TIE_BREAK_MARKER);
    withLoweredHeapCap(() -> assertThat(orderedRowsOf(query))
        .as("the text shape must stream and answer the two-item sort")
        .isEqualTo(expectedOrder("name", true, false)));
  }

  /**
   * Scenario: an ascending query over a fixture that also holds rows without the ordered property,
   * so the index's null-key group takes part. Expected: it streams, and the null rows arrive first
   * in ascending identifier order, which is where the sort puts them.
   */
  @Test
  public void ascendingOrder_streamsWithNullKeysFirst() {
    seedTargets(5);

    withLoweredHeapCap(() -> assertThat(orderedRowsOf(ascendingByScore()))
        .as("null keys sort first, in identifier order, on the scan as in the sort")
        .isEqualTo(expectedOrder("score", true, false)));
  }

  /**
   * Scenario: a descending query over an index that admits null keys, with every row carrying the
   * property. Expected: the plan refuses the appended item and the query buffers. The null-key group
   * lives outside the sorted tree and is always scanned in ascending identifier order, so a
   * descending sort cannot claim it — and the refusal follows the index declaration rather than the
   * rows, because a null row can be inserted after the plan is cached.
   */
  @Test
  public void descendingOrder_refusesWhileTheIndexAdmitsNullKeys() {
    seedTargets(0);

    assertThat(planText(descendingByScore()))
        .as("a descending scan over an index that admits null keys must refuse")
        .doesNotContain(IndexOrderedEdgeStep.RID_TIE_BREAK_MARKER);
    assertBuffers(() -> orderedRowsOf(descendingByScore()));
  }

  /**
   * Scenario: the descending query narrowed by {@code IS DEFINED} on the ordered property — the
   * conjunct the Gremlin translation states for every property sort it emits — over a fixture that
   * holds a row whose property is stored as an explicit null. Expected: refusal all the same.
   *
   * <p>{@code IS DEFINED} is the entity-layer presence test, so the explicit-null row passes it and
   * still sits in the index's null-key group. That group is scanned in ascending identifier order
   * whichever direction the scan runs, so a descending sort over it is not what the scan produces.
   * The fixture pins the premise: the presence filter counts the explicit-null row.
   */
  @Test
  public void descendingOrder_refusesWhileTheFilterOnlyStatesPresence() {
    seedTargets(0);
    session.begin();
    session.execute("UPDATE Tgt SET score = null WHERE name = 'n0'").close();
    session.commit();

    assertThat(countOf("SELECT count(*) AS c FROM Tgt WHERE score IS DEFINED AND score IS NULL"))
        .as("the presence filter must count the explicit-null rows, or the refusal has no premise")
        .isPositive();
    var query = "MATCH {class: Src, as: s}.out('LINK')"
        + "{class: Tgt, as: m, where: (score is defined)} RETURN m"
        + " ORDER BY m.score DESC, m.@rid DESC";
    assertThat(planText(query))
        .as("a presence filter leaves the null-key group in the answer, so the scan cannot claim it")
        .doesNotContain(IndexOrderedEdgeStep.RID_TIE_BREAK_MARKER);
    assertBuffers(() -> orderedRowsOf(query));
  }

  /**
   * Scenario: the descending query narrowed by {@code IS NOT NULL} on the ordered property.
   * Expected: the appended item is accepted, because that filter removes the whole null-key group
   * from the answer — the absent rows and the explicit-null ones alike.
   */
  @Test
  public void descendingOrder_streamsWhenTheFilterExcludesNullValues() {
    seedTargets(5);

    var query = "MATCH {class: Src, as: s}.out('LINK')"
        + "{class: Tgt, as: m, where: (score is not null)} RETURN m"
        + " ORDER BY m.score DESC, m.@rid DESC";
    assertThat(planText(query)).contains(IndexOrderedEdgeStep.RID_TIE_BREAK_MARKER);
    withLoweredHeapCap(() -> assertThat(orderedRowsOf(query))
        .as("with null values filtered out the descending scan is the sort")
        .isEqualTo(expectedOrder("score", false, true)));
  }

  /**
   * Scenario: descending {@code ORDER BY} over an index built with {@code ignoreNullValues}.
   * Expected: the whole index-ordered shortcut is refused — such an index holds no entry for a
   * target that lacks the ordered property, so a scan would drop that row instead of sorting it as
   * null. A control sort over a default-keeping index still streams.
   */
  @Test
  public void descendingOrder_refusesWhenTheIndexIgnoresNullValues() {
    seedTargets(0, target -> {
      target.createProperty("score", PropertyType.INTEGER)
          .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE, Map.of("ignoreNullValues", true));
      target.createProperty("name", PropertyType.STRING)
          .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
    });

    assertThat(planText(
        "MATCH {class: Src, as: s}.out('LINK'){class: Tgt, as: m} RETURN m"
            + " ORDER BY m.name ASC, m.@rid ASC"))
        .as("the control sort must still reach the shortcut, or the refusal below is vacuous")
        .contains(IndexOrderedEdgeStep.RID_TIE_BREAK_MARKER);
    assertThat(planText(descendingByScore()))
        .as("an ignore-null index cannot claim the full ordered row set")
        .doesNotContain(INDEX_ORDERED_STEP);
    assertBuffers(() -> orderedRowsOf(descendingByScore()));
  }

  /**
   * Scenario: a sort over a property the schema declares case-insensitive, with and without a limit.
   * Expected: the shortcut is refused outright — no index-ordered step at all — while the control
   * sort over the default-collate property still takes it.
   *
   * <p>The index stores the folded key alone, so {@code Ada} and {@code ada} share one key and come
   * back in identifier order, while the comparison separates them by the raw values. No scan of that
   * index reproduces the comparison, with one sort item or two.
   */
  @Test
  public void declaredCaseInsensitiveProperty_refusesTheShortcut() {
    seedTargets(0, target -> {
      target.createProperty("score", PropertyType.INTEGER)
          .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
      target.createProperty("name", PropertyType.STRING)
          .setCollate("ci")
          .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
    });

    assertThat(planText(ascendingByScore()))
        .as("the control sort must still reach the shortcut, or the refusals below are vacuous")
        .contains(IndexOrderedEdgeStep.RID_TIE_BREAK_MARKER);
    assertThat(planText("MATCH {class: Src, as: s}.out('LINK'){class: Tgt, as: m} RETURN m"
        + " ORDER BY m.name ASC, m.@rid ASC"))
        .as("a declared collation refuses the two-item sort")
        .doesNotContain(INDEX_ORDERED_STEP);
    assertThat(planText("MATCH {class: Src, as: s}.out('LINK'){class: Tgt, as: m} RETURN m"
        + " ORDER BY m.name ASC LIMIT 5"))
        .as("a declared collation refuses the single-item sort as well")
        .doesNotContain(INDEX_ORDERED_STEP);
  }

  /**
   * Scenario: an index that states a collation of its own while the property it indexes declares
   * none. Expected: the shortcut is refused, because the sort item compares with the default
   * collation and the stored keys are folded, so the scan sequence is not the comparison sequence.
   */
  @Test
  public void indexCollationDifferingFromTheProperty_refusesTheShortcut() {
    seedTargets(0, target -> {
      target.createProperty("score", PropertyType.INTEGER)
          .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
      target.createProperty("name", PropertyType.STRING);
      session.execute("CREATE INDEX Tgt.nameCi ON Tgt (name COLLATE ci) NOTUNIQUE").close();
    });

    assertThat(planText(ascendingByScore()))
        .as("the control sort must still reach the shortcut, or the refusal below is vacuous")
        .contains(IndexOrderedEdgeStep.RID_TIE_BREAK_MARKER);
    assertThat(planText("MATCH {class: Src, as: s}.out('LINK'){class: Tgt, as: m} RETURN m"
        + " ORDER BY m.name ASC, m.@rid ASC"))
        .as("the collation of the matched index decides, not only the property declaration")
        .doesNotContain(INDEX_ORDERED_STEP);
  }

  /**
   * Scenario: a sort over an indexed collection property. Expected: refusal, because such an index
   * writes one entry per element, so its scan sequence is an element sequence and one row comes back
   * once per element it holds.
   */
  @Test
  public void collectionValuedIndex_refusesTheShortcut() {
    seedTargets(0, target -> {
      target.createProperty("score", PropertyType.INTEGER)
          .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
      target.createProperty("name", PropertyType.STRING);
      target.createProperty("tags", PropertyType.EMBEDDEDLIST, PropertyType.STRING)
          .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
    });

    assertThat(planText(ascendingByScore()))
        .as("the control sort must still reach the shortcut, or the refusal below is vacuous")
        .contains(IndexOrderedEdgeStep.RID_TIE_BREAK_MARKER);
    assertThat(planText("MATCH {class: Src, as: s}.out('LINK'){class: Tgt, as: m} RETURN m"
        + " ORDER BY m.tags ASC, m.@rid ASC"))
        .as("a collection index reports one property but writes one entry per element")
        .doesNotContain(INDEX_ORDERED_STEP);
  }

  /**
   * Scenario: a pattern whose later edge reads {@code $matched}, which forces every upstream alias to
   * stay bound in the emitted row. Expected: the plan reaches a bound multi-source mode and refuses
   * the appended item there, because a bound mode emits one row per source per target and two rows
   * sharing a target tie on both sort items.
   *
   * <p>The chain shape pins the schedule: the later edge starts at {@code m}, so it cannot be
   * scheduled before the edge that reaches {@code m}.
   */
  @Test
  public void boundMultiSourceMode_refusesTheAppendedItem() {
    seedTargets(0);
    session.createVertexClass("Oth");
    session.createEdgeClass("OTHER");
    var target = graph.traversal().V().hasLabel("Tgt").next();
    target.addEdge("OTHER", graph.addVertex(T.label, "Oth", "tag", "other"));
    graph.tx().commit();

    var plan = planText("MATCH {class: Src, as: s}.out('LINK'){class: Tgt, as: m}"
        + ".out('OTHER'){class: Oth, as: o, where: ($matched.s.@rid is not null)} RETURN m"
        + " ORDER BY m.score ASC, m.@rid ASC");
    assertThat(plan)
        .as("the shape must land in a bound mode, or the refusal below is about something else")
        .contains(IndexOrderedPlanner.MultiSourceMode.UNFILTERED_BOUND.name());
    assertThat(plan)
        .as("a bound mode ties two rows that share a target, so the two items are not a total order")
        .doesNotContain(IndexOrderedEdgeStep.RID_TIE_BREAK_MARKER);
  }

  /**
   * Scenario: an appended record identifier item naming the source alias rather than the ordered
   * one. Expected: refusal, because the scan orders the targets and the identifier of another alias
   * is not what it produces.
   */
  @Test
  public void appendedItemOfAnotherAlias_refusesTheAppendedItem() {
    seedTargets(0);

    assertThat(planText("MATCH {class: Src, as: s}.out('LINK'){class: Tgt, as: m} RETURN m"
        + " ORDER BY m.score ASC, s.@rid ASC"))
        .as("the identifier of an unordered alias must keep the refusal")
        .doesNotContain(IndexOrderedEdgeStep.RID_TIE_BREAK_MARKER);
  }

  /**
   * Scenario: the ascending Gremlin traversal the translator turns into the accepted shape, with no
   * limit. Expected: it streams under the lowered cap, which is the regression this work repairs —
   * the appended item used to make the same traversal buffer every row and fail.
   */
  @Test
  public void translatedAscendingTraversal_streamsUnderTheLoweredCap() {
    seedTargets(0);

    withLoweredHeapCap(() -> assertThat(orderedTargets(() -> targets().order().by("score")))
        .as("the translated ascending traversal must stream")
        .isEqualTo(expectedOrder("score", true, true)));
  }

  /**
   * Scenario: the descending sibling of the translated ascending case. Expected: it buffers, and it
   * still answers the two-item sort. The translation states presence of the ordered property, and
   * presence leaves an explicit-null row inside the null-key group, so a descending scan cannot
   * claim that group — the sort has to run.
   */
  @Test
  public void translatedDescendingTraversal_buffersUnderTheLoweredCap() {
    seedTargets(0);

    assertBuffers(() -> orderedTargets(() -> targets().order().by("score", Order.desc)));
    assertThat(orderedTargets(() -> targets().order().by("score", Order.desc)))
        .as("the buffered sort must answer the two-item order")
        .isEqualTo(expectedOrder("score", false, true));
  }

  /**
   * Scenario: the translated ascending traversal run inside a transaction that has created a further
   * target row. Expected: the query buffers, and it still answers the two-item sort over all rows,
   * the uncommitted one included. The index entry of an uncommitted record is written under a
   * provisional identifier and can arrive anywhere inside its key group, so the step withdraws its
   * pre-sorted signal for that execution.
   */
  @Test
  public void pendingTransactionChange_buffersAndStillAnswersTheSortSequence() {
    seedTargets(0);
    addPendingTarget();

    assertBuffers(() -> orderedTargets(() -> targets().order().by("score")));
    assertThat(orderedTargets(() -> targets().order().by("score")))
        .as("the buffered sort must answer the two-item order, the pending row included")
        .isEqualTo(expectedOrder("score", true, true));
    graph.tx().rollback();
  }

  /**
   * Scenario: the same ascending sort under {@code RETURN DISTINCT}. Expected: the plan refuses the
   * appended item, because a deduplication step decides which of two equal rows survives and the
   * scan order alone no longer describes the answer.
   */
  @Test
  public void deduplicatedQuery_refusesTheAppendedItem() {
    seedTargets(0);

    assertThat(planText(
        "MATCH {class: Src, as: s}.out('LINK'){class: Tgt, as: m} RETURN DISTINCT m"
            + " ORDER BY m.score ASC, m.@rid ASC"))
        .as("a deduplicated query must keep the refusal")
        .doesNotContain(IndexOrderedEdgeStep.RID_TIE_BREAK_MARKER);
  }

  /**
   * Scenario: an appended item whose direction disagrees with the scan direction. Expected: refusal,
   * because equal keys come back in scan direction and no other direction describes them.
   */
  @Test
  public void appendedItemAgainstTheScanDirection_refusesTheAppendedItem() {
    seedTargets(0);

    var query = "MATCH {class: Src, as: s}.out('LINK')"
        + "{class: Tgt, as: m, where: (score is not null)} RETURN m"
        + " ORDER BY m.score DESC, m.@rid ASC";
    assertThat(planText(query))
        .as("an ascending appended item does not describe a descending scan")
        .doesNotContain(IndexOrderedEdgeStep.RID_TIE_BREAK_MARKER);
    assertBuffers(() -> orderedRowsOf(query));
  }

  /**
   * Scenario: a sort of three items whose last one is the record identifier. Expected: refusal, since
   * the middle item is a second sort field the scan knows nothing about.
   */
  @Test
  public void thirdSortItem_refusesTheAppendedItem() {
    seedTargets(0);

    assertThat(planText(
        "MATCH {class: Src, as: s}.out('LINK'){class: Tgt, as: m} RETURN m"
            + " ORDER BY m.score ASC, m.name ASC, m.@rid ASC"))
        .as("a genuine second sort field must keep the refusal")
        .doesNotContain(IndexOrderedEdgeStep.RID_TIE_BREAK_MARKER);
  }

  /**
   * Scenario: the same sort where the row also projects the source alias. Expected: refusal, because
   * one row per source per target makes rows that share a target tie on both items, so the sort is
   * not total over the returned row.
   */
  @Test
  public void secondAliasInTheRow_refusesTheAppendedItem() {
    seedTargets(0);

    assertThat(planText(
        "MATCH {class: Src, as: s}.out('LINK'){class: Tgt, as: m} RETURN m, s"
            + " ORDER BY m.score ASC, m.@rid ASC"))
        .as("a bound second alias must keep the refusal")
        .doesNotContain(IndexOrderedEdgeStep.RID_TIE_BREAK_MARKER);
  }

  private static String ascendingByScore() {
    return "MATCH {class: Src, as: s}.out('LINK'){class: Tgt, as: m} RETURN m"
        + " ORDER BY m.score ASC, m.@rid ASC";
  }

  private static String descendingByScore() {
    return "MATCH {class: Src, as: s}.out('LINK'){class: Tgt, as: m} RETURN m"
        + " ORDER BY m.score DESC, m.@rid DESC";
  }

  /** The traversal shape that reaches the index-ordered plan: a small source, a big target class. */
  private GraphTraversal<Vertex, Vertex> targets() {
    return graph.traversal().V().hasLabel("Src").out("LINK").hasLabel("Tgt");
  }

  /** Runs {@code body} with the in-heap cap lowered, restoring it afterwards. */
  private void withLoweredHeapCap(Runnable body) {
    var previous = GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.getValue();
    GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.setValue(LOW_HEAP_CAP);
    try {
      body.run();
    } finally {
      GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.setValue(previous);
    }
  }

  /** Pins that {@code query} collects its rows in heap, by running it under the lowered cap. */
  private void assertBuffers(Runnable query) {
    withLoweredHeapCap(() -> assertThatThrownBy(query::run)
        .as("a refused shape must buffer, which the lowered cap turns into a failure")
        .hasStackTraceContaining(BUFFERING_FAILURE));
  }

  /** The plan of {@code query}, rendered as text. */
  private String planText(String query) {
    try (var result = session.query(query)) {
      return result.getExecutionPlan().prettyPrint(0, 2);
    }
  }

  /** The single {@code c} count of an aggregate query. */
  private long countOf(String query) {
    try (var result = session.query(query)) {
      return result.next().<Number>getProperty("c").longValue();
    }
  }

  /** The identifier of every row's {@code m} column, in arrival order. */
  private List<String> orderedRowsOf(String query) {
    var rows = new ArrayList<String>();
    try (var result = session.query(query)) {
      result.forEachRemaining(
          row -> rows.add(((Identifiable) row.getProperty("m")).getIdentity().toString()));
    }
    return rows;
  }

  /** The same rendering for a traversal's vertices, in arrival order. */
  private List<String> orderedTargets(java.util.function.Supplier<GraphTraversal<?, ?>> traversal) {
    return traversal.get().toList().stream()
        .map(row -> ((Vertex) row).id().toString())
        .toList();
  }

  /**
   * The sequence the two sort items describe, computed here from the stored rows: {@code
   * propertyName} with an absent value counting as the smallest, then the identifier, with {@code
   * ascending} applied to both. {@code definedOnly} drops the rows without the property, which is
   * what a Gremlin {@code by(key)} modulator and a null-excluding filter both do.
   *
   * <p>Rows are read through the graph rather than through a second session, so an uncommitted row
   * of the running transaction is part of the oracle.
   */
  private List<String> expectedOrder(
      String propertyName, boolean ascending, boolean definedOnly) {
    var rows = new ArrayList<Object[]>();
    for (var vertex : graph.traversal().V().hasLabel("Tgt").toList()) {
      var value = vertex.property(propertyName).isPresent()
          ? vertex.<Object>value(propertyName)
          : null;
      if (definedOnly && value == null) {
        continue;
      }
      rows.add(new Object[] {value, vertex.id()});
    }
    Comparator<Object[]> comparator =
        Comparator.<Object[], Object>comparing(row -> row[0],
            IndexOrderedRidTieBreakTest::compareNullsFirst)
            .thenComparing(row -> row[1], IndexOrderedRidTieBreakTest::compareNullsFirst);
    rows.sort(ascending ? comparator : comparator.reversed());
    return rows.stream().map(row -> row[1].toString()).toList();
  }

  /** Natural order with a null counting as the smallest value, over any stored value class. */
  @SuppressWarnings("unchecked")
  private static int compareNullsFirst(@Nullable Object left, @Nullable Object right) {
    if (left == null) {
      return right == null ? 0 : -1;
    }
    if (right == null) {
      return 1;
    }
    return ((Comparable<Object>) left).compareTo(right);
  }

  /**
   * One source vertex, {@code SCORED_TARGETS} indexed targets whose property values repeat so that
   * every value ties several rows, plus {@code withoutScore} targets that lack the property. Values
   * repeat on purpose: an untied sort key would leave the appended item unobservable.
   */
  private void seedTargets(int withoutScore) {
    seedTargets(withoutScore, target -> {
      target.createProperty("score", PropertyType.INTEGER)
          .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
      target.createProperty("name", PropertyType.STRING)
          .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
    });
  }

  /**
   * The same fixture with {@code declare} deciding what the target class declares and indexes. Every
   * refusal case declares the plain {@code score} index unchanged, so the control query over it
   * proves the shape reaches the shortcut at all.
   */
  private void seedTargets(int withoutScore, Consumer<SchemaClass> declare) {
    var target = session.createVertexClass("Tgt");
    declare.accept(target);
    session.createVertexClass("Src");
    session.createEdgeClass("LINK");

    var source = graph.addVertex(T.label, "Src", "tag", "source");
    for (var i = 0; i < SCORED_TARGETS; i++) {
      source.addEdge("LINK",
          graph.addVertex(T.label, "Tgt", "score", i % 7, "name", "n" + (i % 5)));
    }
    for (var i = 0; i < withoutScore; i++) {
      source.addEdge("LINK", graph.addVertex(T.label, "Tgt", "name", "blank" + i));
    }
    graph.tx().commit();
  }

  /** Adds one further target inside an open transaction, left uncommitted on purpose. */
  private void addPendingTarget() {
    var source = graph.traversal().V().hasLabel("Src").next();
    source.addEdge("LINK", graph.addVertex(T.label, "Tgt", "score", 3, "name", "pending"));
  }
}
