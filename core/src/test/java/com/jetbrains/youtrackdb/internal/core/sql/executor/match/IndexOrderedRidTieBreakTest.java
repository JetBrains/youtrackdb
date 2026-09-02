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
        .isEqualTo(expectedOrder(true, false)));
  }

  /**
   * Scenario: the same query over an indexed text property that declares no collation. Expected: it
   * streams as well, because the index compares text the same way the default collation does.
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
        .as("the text shape must stream every row")
        .hasSize(SCORED_TARGETS));
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
        .isEqualTo(expectedOrder(true, false)));
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
   * Scenario: the descending query narrowed by a filter that keeps only rows where the ordered
   * property is defined — which is what the Gremlin translation states for every property sort.
   * Expected: the appended item is accepted again, because no null key can reach the answer.
   */
  @Test
  public void descendingOrder_streamsWhenTheFilterExcludesNullKeys() {
    seedTargets(5);

    var query = "MATCH {class: Src, as: s}.out('LINK')"
        + "{class: Tgt, as: m, where: (score is defined)} RETURN m"
        + " ORDER BY m.score DESC, m.@rid DESC";
    assertThat(planText(query)).contains(IndexOrderedEdgeStep.RID_TIE_BREAK_MARKER);
    withLoweredHeapCap(() -> assertThat(orderedRowsOf(query))
        .as("with null keys filtered out the descending scan is the sort")
        .isEqualTo(expectedOrder(false, true)));
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
        .isEqualTo(expectedOrder(true, true)));
  }

  /**
   * Scenario: the descending sibling of the translated ascending case. Expected: it streams too. The
   * translation states that the ordered property is defined, so the null-key group is excluded and
   * the descending scan describes the sort exactly.
   */
  @Test
  public void translatedDescendingTraversal_streamsUnderTheLoweredCap() {
    seedTargets(0);

    withLoweredHeapCap(
        () -> assertThat(orderedTargets(() -> targets().order().by("score", Order.desc)))
            .as("the translated descending traversal must stream")
            .isEqualTo(expectedOrder(false, true)));
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
        .isEqualTo(expectedOrder(true, true));
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
        + "{class: Tgt, as: m, where: (score is defined)} RETURN m"
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
   * The sequence the two sort items describe, computed here from the stored rows: the property with
   * a null counting as the smallest value, then the identifier, with {@code ascending} applied to
   * both. {@code definedOnly} drops the rows without the property, which is what a Gremlin
   * {@code by(key)} modulator and an {@code IS DEFINED} filter both do.
   *
   * <p>Rows are read through the graph rather than through a second session, so an uncommitted row
   * of the running transaction is part of the oracle.
   */
  private List<String> expectedOrder(boolean ascending, boolean definedOnly) {
    var rows = new ArrayList<Object[]>();
    for (var vertex : graph.traversal().V().hasLabel("Tgt").toList()) {
      var score = vertex.<Integer>property("score").orElse(null);
      if (definedOnly && score == null) {
        continue;
      }
      rows.add(new Object[] {score, vertex.id()});
    }
    Comparator<Object[]> byScore =
        Comparator.comparing(
            row -> (Integer) row[0], Comparator.nullsFirst(Comparator.naturalOrder()));
    @SuppressWarnings("unchecked")
    Comparator<Object[]> byIdentifier =
        Comparator.comparing(row -> (Comparable<Object>) row[1]);
    var comparator = byScore.thenComparing(byIdentifier);
    rows.sort(ascending ? comparator : comparator.reversed());
    return rows.stream().map(row -> row[1].toString()).toList();
  }

  /**
   * One source vertex, {@code SCORED_TARGETS} indexed targets whose property values repeat so that
   * every value ties several rows, plus {@code withoutScore} targets that lack the property. Values
   * repeat on purpose: an untied sort key would leave the appended item unobservable.
   */
  private void seedTargets(int withoutScore) {
    var target = session.createVertexClass("Tgt");
    target.createProperty("score", PropertyType.INTEGER)
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
    target.createProperty("name", PropertyType.STRING)
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
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
