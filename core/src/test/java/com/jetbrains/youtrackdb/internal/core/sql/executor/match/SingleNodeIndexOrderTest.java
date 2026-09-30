package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization.YTDBOrderRidTieBreakStrategy;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import java.util.ArrayList;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Edge-free MATCH {@code ORDER BY} served by {@code FetchFromIndexValues} through the synthetic
 * root SELECT ({@link SingleNodeIndexOrder}), distinct from hop-based {@link IndexOrderedPlanner}.
 */
public class SingleNodeIndexOrderTest extends GraphBaseTest {

  /** Duplicate timestamps: {@code i / 2} over {@code ITEMS} rows → two rows per key. */
  private static final int ITEMS = 200;

  private static final int LOW_HEAP_CAP = 10;

  private static final String BUFFERING_FAILURE = "in-heap ORDER BY";

  private void seedIndexedItems() {
    session.execute("CREATE CLASS IndexedItem EXTENDS V").close();
    session.execute("CREATE PROPERTY IndexedItem.timestamp LONG").close();
    session.execute("CREATE INDEX IndexedItem_timestamp ON IndexedItem (timestamp) NOTUNIQUE")
        .close();
    for (var i = 0; i < ITEMS; i++) {
      graph.addVertex(T.label, "IndexedItem", "timestamp", (long) (i / 2));
    }
    graph.tx().commit();
  }

  private void seedNamedScores(boolean ignoreNullValues) {
    var cls = session.createVertexClass("Scored");
    cls.createProperty("score", PropertyType.INTEGER);
    cls.createProperty("name", PropertyType.STRING);
    var metadata = ignoreNullValues ? " METADATA {ignoreNullValues: true}" : "";
    session.execute(
        "CREATE INDEX Scored_score ON Scored (score) NOTUNIQUE" + metadata)
        .close();
    session.begin();
    session.execute("CREATE VERTEX Scored SET score = 1, name = 'a'").close();
    session.execute("CREATE VERTEX Scored SET score = 1, name = 'b'").close();
    session.execute("CREATE VERTEX Scored SET score = 2, name = 'c'").close();
    session.execute("CREATE VERTEX Scored SET score = 3, name = 'd'").close();
    session.execute("CREATE VERTEX Scored SET name = 'nullish'").close();
    session.commit();
  }

  private String plan(String query) {
    try (var rs = session.query("EXPLAIN " + query)) {
      return String.valueOf((Object) rs.next().getProperty("executionPlanAsString"));
    }
  }

  private static void assertIndexValuesScan(String planText, String indexName) {
    assertThat(planText)
        .as("plan:\n%s", planText)
        .contains("FETCH FROM INDEX VALUES")
        .contains(indexName)
        .doesNotContain("FETCH FROM CLASS IndexedItem")
        .doesNotContain("FETCH FROM CLASS Scored");
  }

  /**
   * Bare MATCH ordered by an indexed property streams the index values scan instead of a class
   * fetch plus a separate sort.
   */
  @Test
  public void bareMatchDesc_usesIndexValuesAndKeepsDuplicateKeyOrder() {
    seedIndexedItems();
    var query = "MATCH {class: IndexedItem, as: item} RETURN item"
        + " ORDER BY item.timestamp DESC";
    assertIndexValuesScan(plan(query), "IndexedItem_timestamp");
    assertThat(plan(query)).doesNotContain("+ ORDER BY");

    try (var result = session.query(query)) {
      assertThat(result.next().getVertex("item").<Long>getProperty("timestamp"))
          .isEqualTo(ITEMS / 2L - 1);
      assertThat(result.next().getVertex("item").<Long>getProperty("timestamp"))
          .isEqualTo(ITEMS / 2L - 1);
      var count = 2;
      while (result.hasNext()) {
        result.next();
        count++;
      }
      assertThat(count).isEqualTo(ITEMS);
    }
  }

  /**
   * Ascending bare MATCH uses the ascending index values scan and omits MATCH-level OrderByStep.
   */
  @Test
  public void bareMatchAsc_usesIndexValuesWithoutMatchOrderBy() {
    seedIndexedItems();
    var query = "MATCH {class: IndexedItem, as: item} RETURN item"
        + " ORDER BY item.timestamp ASC";
    var planText = plan(query);
    assertThat(planText)
        .contains("FETCH FROM INDEX VALUES ASC IndexedItem_timestamp")
        .doesNotContain("FETCH FROM CLASS IndexedItem")
        .doesNotContain("+ ORDER BY");

    try (var result = session.query(query)) {
      assertThat(result.next().getVertex("item").<Long>getProperty("timestamp")).isEqualTo(0L);
      assertThat(result.next().getVertex("item").<Long>getProperty("timestamp")).isEqualTo(0L);
    }
  }

  /**
   * ORDER BY a RETURN projection alias that is a bare {@code alias.property} still reaches the
   * index values path.
   */
  @Test
  public void bareMatch_orderByProjectionAlias_usesIndex() {
    seedIndexedItems();
    var query = "MATCH {class: IndexedItem, as: item} RETURN item.timestamp AS ts"
        + " ORDER BY ts DESC";
    assertIndexValuesScan(plan(query), "IndexedItem_timestamp");
    try (var result = session.query(query)) {
      assertThat(result.next().<Long>getProperty("ts")).isEqualTo(ITEMS / 2L - 1);
    }
  }

  /**
   * Plain SELECT control: same fixture, index values DESC, no class fetch, no separate sort.
   */
  @Test
  public void selectControl_usesIndexValuesDesc() {
    seedIndexedItems();
    var query = "SELECT FROM IndexedItem ORDER BY timestamp DESC";
    assertThat(plan(query))
        .contains("FETCH FROM INDEX VALUES DESC IndexedItem_timestamp")
        .doesNotContain("FETCH FROM CLASS IndexedItem")
        .doesNotContain("+ ORDER BY");
    try (var result = session.query(query)) {
      assertThat(result.next().<Long>getProperty("timestamp")).isEqualTo(ITEMS / 2L - 1);
      assertThat(result.next().<Long>getProperty("timestamp")).isEqualTo(ITEMS / 2L - 1);
      var count = 2;
      while (result.hasNext()) {
        result.next();
        count++;
      }
      assertThat(count).isEqualTo(ITEMS);
    }
  }

  /**
   * Gremlin with RID tie-break translates to MATCH, keeps {@code .@rid} in the order clause, and
   * still opens the timestamp index (class fetch is gone). DESC + null-key index may keep
   * OrderByStep for soundness; row count and leading duplicates stay correct.
   */
  @Test
  public void gremlinWithRidTieBreak_usesIndex() {
    seedIndexedItems();
    var planText = translatedPlan(graph.traversal());
    assertThat(planText).contains(".@rid DESC");
    assertIndexValuesScan(planText, "IndexedItem_timestamp");
  }

  /**
   * Same Gremlin without the RID strategy: no {@code @rid} in the order, index values scan, no
   * MATCH-level OrderByStep needed for the single property.
   */
  @Test
  public void gremlinWithoutRidTieBreak_usesIndexWithoutRidOrder() {
    seedIndexedItems();
    var planText = translatedPlan(
        graph.traversal().withoutStrategies(YTDBOrderRidTieBreakStrategy.class));
    assertThat(planText).doesNotContain(".@rid");
    assertIndexValuesScan(planText, "IndexedItem_timestamp");
  }

  /**
   * Without an index on the ordered property, MATCH falls back to a class fetch and an in-memory
   * OrderByStep.
   */
  @Test
  public void bareMatch_withoutIndex_usesClassFetchAndOrderBy() {
    session.execute("CREATE CLASS PlainItem EXTENDS V").close();
    session.execute("CREATE PROPERTY PlainItem.timestamp LONG").close();
    for (var i = 0; i < 20; i++) {
      graph.addVertex(T.label, "PlainItem", "timestamp", (long) i);
    }
    graph.tx().commit();

    var query = "MATCH {class: PlainItem, as: item} RETURN item ORDER BY item.timestamp DESC";
    var planText = plan(query);
    assertThat(planText)
        .contains("FETCH FROM CLASS PlainItem")
        .contains("+ ORDER BY")
        .doesNotContain("FETCH FROM INDEX VALUES");
  }

  /**
   * An index that ignores null values must not serve MATCH ORDER BY: the key-less row would
   * disappear. Plan falls back; all rows including the nullish one remain.
   */
  @Test
  public void bareMatch_ignoreNullsIndex_keepsKeylessRow() {
    seedNamedScores(true);
    var query = "MATCH {class: Scored, as: s} RETURN s.name AS name ORDER BY s.score ASC";
    var planText = plan(query);
    assertThat(planText)
        .as("null-ignoring index must not drive ORDER BY:\n%s", planText)
        .doesNotContain("FETCH FROM INDEX VALUES");

    var names = new ArrayList<String>();
    try (var rs = session.query(query)) {
      rs.forEachRemaining(row -> names.add(row.getProperty("name")));
    }
    assertThat(names).contains("nullish").hasSize(5);
  }

  /**
   * Ascending MATCH with an explicit {@code @rid} secondary that the index scan already produces
   * streams under a lowered heap cap (no buffering OrderByStep).
   */
  @Test
  @Category(SequentialTest.class)
  public void bareMatchAscWithRid_streamsUnderLowHeapCap() {
    seedNamedScores(false);
    var query = "MATCH {class: Scored, as: s} RETURN s"
        + " ORDER BY s.score ASC, s.@rid ASC";
    assertThat(plan(query))
        .contains("FETCH FROM INDEX VALUES ASC Scored_score")
        .doesNotContain("FETCH FROM CLASS Scored");

    var previous = GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.getValueAsInteger();
    GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.setValue(LOW_HEAP_CAP);
    try {
      try (var rs = session.query(query)) {
        var count = 0;
        while (rs.hasNext()) {
          rs.next();
          count++;
        }
        assertThat(count).isEqualTo(5);
      }
    } finally {
      GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.setValue(previous);
    }
  }

  /**
   * Control: forcing a buffering sort (no usable index order for a non-indexed secondary beyond
   * what we accept) still trips the lowered heap cap — proves the instrument works on this
   * fixture shape.
   */
  @Test
  @Category(SequentialTest.class)
  public void bareMatch_multiPropertyWithoutCoveringIndex_buffersAndHitsHeapCap() {
    seedNamedScores(false);
    // name is not leading-indexed; planner cannot fully cover — OrderByStep buffers.
    var query = "MATCH {class: Scored, as: s} RETURN s ORDER BY s.name ASC, s.score ASC";
    var previous = GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.getValueAsInteger();
    GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.setValue(2);
    try {
      assertThatThrownBy(() -> {
        try (var rs = session.query(query)) {
          while (rs.hasNext()) {
            rs.next();
          }
        }
      })
          .hasMessageContaining(BUFFERING_FAILURE);
    } finally {
      GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.setValue(previous);
    }
  }

  /**
   * WHEN the WHERE also hits the ordered index, SELECT prefers a filtered index range over the
   * sort-only values scan. The root still must not fall back to a class fetch, and DESC order of
   * the filtered rows must be correct.
   */
  @Test
  public void bareMatch_withWhereOnIndexedKey_usesIndexWithoutClassFetch() {
    seedNamedScores(false);
    var query = "MATCH {class: Scored, as: s, where: (score >= 2)} RETURN s.name AS name"
        + " ORDER BY s.score DESC";
    assertThat(plan(query))
        .contains("FETCH FROM INDEX Scored_score")
        .doesNotContain("FETCH FROM CLASS Scored");

    var names = new ArrayList<String>();
    try (var rs = session.query(query)) {
      rs.forEachRemaining(row -> names.add(String.valueOf((Object) row.getProperty("name"))));
    }
    assertThat(names).containsExactly("d", "c");
  }

  /**
   * WHERE on a non-indexed property leaves sort-only index values as the ORDER BY path.
   */
  @Test
  public void bareMatch_withWhereOnOtherProperty_usesIndexValuesForOrder() {
    seedNamedScores(false);
    var query = "MATCH {class: Scored, as: s, where: (name <> 'nullish')} RETURN s.name AS name"
        + " ORDER BY s.score DESC";
    assertThat(plan(query))
        .contains("FETCH FROM INDEX VALUES DESC Scored_score")
        .doesNotContain("FETCH FROM CLASS Scored");

    var names = new ArrayList<String>();
    try (var rs = session.query(query)) {
      rs.forEachRemaining(row -> names.add(String.valueOf((Object) row.getProperty("name"))));
    }
    assertThat(names).hasSize(4).startsWith("d", "c").contains("a", "b");
  }

  /**
   * Duplicate score ties: ASC + {@code @rid} matches the identifier order the index scan emits.
   */
  @Test
  public void bareMatchAscWithRid_tieBreakMatchesRidOrder() {
    seedNamedScores(false);
    var query = "MATCH {class: Scored, as: s} RETURN s"
        + " ORDER BY s.score ASC, s.@rid ASC";

    record Row(String rid, Integer score, String name) {
    }
    var rows = new ArrayList<Row>();
    try (var rs = session.query(query)) {
      rs.forEachRemaining(r -> {
        var v = r.getVertex("s");
        rows.add(new Row(
            v.getIdentity().toString(),
            v.<Integer>getProperty("score"),
            String.valueOf((Object) v.getProperty("name"))));
      });
    }
    var scored = rows.stream().filter(row -> row.score != null && row.score == 1).toList();
    assertThat(scored).hasSize(2);
    assertThat(scored.get(0).rid.compareTo(scored.get(1).rid)).isLessThan(0);
  }

  private String translatedPlan(GraphTraversalSource source) {
    var traversal = source.V().hasLabel("IndexedItem")
        .order().by("timestamp", Order.desc).asAdmin();
    traversal.applyStrategies();
    assertThat(traversal.getStartStep()).isInstanceOf(YTDBMatchPlanStep.class);
    var planText = ((YTDBMatchPlanStep<?, ?>) traversal.getStartStep())
        .getPlan().prettyPrint(0, 2);
    var count = 0;
    while (traversal.hasNext()) {
      traversal.next();
      count++;
    }
    assertThat(count).isEqualTo(ITEMS);
    return planText;
  }
}
