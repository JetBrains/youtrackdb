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
import java.util.Comparator;
import java.util.List;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Edge-free MATCH {@code ORDER BY} served by {@code FetchFromIndexValues} through the synthetic
 * root SELECT ({@link SingleNodeIndexOrder}), distinct from hop-based {@link IndexOrderedPlanner}.
 */
public class SingleNodeIndexOrderTest extends GraphBaseTest {

  /** Duplicate timestamps: {@code i / 2} over {@code ITEMS} rows → two rows per key. */
  private static final int ITEMS = 200;

  private static final int LOW_HEAP_CAP = 2;

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
   * Sequence the index values scan emits for {@code IndexedItem.timestamp}: property order, then
   * record id in the same direction (multi-value index key {@code (property, rid)}).
   */
  private List<String> expectedIndexedItemRids(boolean ascending) {
    var rows = new ArrayList<Object[]>();
    for (var vertex : graph.traversal().V().hasLabel("IndexedItem").toList()) {
      rows.add(new Object[] {vertex.value("timestamp"), vertex.id()});
    }
    Comparator<Object[]> comparator =
        Comparator.<Object[], Object>comparing(
            row -> row[0], SingleNodeIndexOrderTest::compareNullsFirst)
            .thenComparing(row -> row[1], SingleNodeIndexOrderTest::compareNullsFirst);
    rows.sort(ascending ? comparator : comparator.reversed());
    return rows.stream().map(row -> row[1].toString()).toList();
  }

  private List<Long> expectedIndexedItemTimestamps(boolean ascending) {
    var rows = new ArrayList<Object[]>();
    for (var vertex : graph.traversal().V().hasLabel("IndexedItem").toList()) {
      rows.add(new Object[] {vertex.value("timestamp"), vertex.id()});
    }
    Comparator<Object[]> comparator =
        Comparator.<Object[], Object>comparing(
            row -> row[0], SingleNodeIndexOrderTest::compareNullsFirst)
            .thenComparing(row -> row[1], SingleNodeIndexOrderTest::compareNullsFirst);
    rows.sort(ascending ? comparator : comparator.reversed());
    return rows.stream().map(row -> (Long) row[0]).toList();
  }

  private List<String> expectedScoredRids(boolean ascending) {
    var rows = new ArrayList<Object[]>();
    for (var vertex : graph.traversal().V().hasLabel("Scored").toList()) {
      var score = vertex.property("score").isPresent() ? vertex.<Object>value("score") : null;
      rows.add(new Object[] {score, vertex.id()});
    }
    Comparator<Object[]> comparator =
        Comparator.<Object[], Object>comparing(
            row -> row[0], SingleNodeIndexOrderTest::compareNullsFirst)
            .thenComparing(row -> row[1], SingleNodeIndexOrderTest::compareNullsFirst);
    rows.sort(ascending ? comparator : comparator.reversed());
    return rows.stream().map(row -> row[1].toString()).toList();
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static int compareNullsFirst(@Nullable Object left, @Nullable Object right) {
    if (left == null) {
      return right == null ? 0 : -1;
    }
    if (right == null) {
      return 1;
    }
    return ((Comparable) left).compareTo(right);
  }

  private List<String> matchItemRids(String query) {
    var rows = new ArrayList<String>();
    try (var rs = session.query(query)) {
      rs.forEachRemaining(row -> rows.add(row.getVertex("item").getIdentity().toString()));
    }
    return rows;
  }

  private List<Long> matchTimestamps(String query, String column) {
    var rows = new ArrayList<Long>();
    try (var rs = session.query(query)) {
      rs.forEachRemaining(row -> rows.add(((Number) row.getProperty(column)).longValue()));
    }
    return rows;
  }

  private List<String> selectRids(String query) {
    var rows = new ArrayList<String>();
    try (var rs = session.query(query)) {
      rs.forEachRemaining(row -> rows.add(row.getIdentity().toString()));
    }
    return rows;
  }

  /**
   * Bare MATCH ordered by an indexed property streams the index values scan instead of a class
   * fetch plus a separate sort. The full RID sequence matches the index order oracle.
   */
  @Test
  public void bareMatchDesc_usesIndexValuesAndKeepsDuplicateKeyOrder() {
    seedIndexedItems();
    var query = "MATCH {class: IndexedItem, as: item} RETURN item"
        + " ORDER BY item.timestamp DESC";
    assertIndexValuesScan(plan(query), "IndexedItem_timestamp");
    assertThat(plan(query)).doesNotContain("+ ORDER BY");
    assertThat(matchItemRids(query))
        .as("DESC index values must match (timestamp DESC, @rid DESC)")
        .isEqualTo(expectedIndexedItemRids(false));
  }

  /**
   * Ascending bare MATCH uses the ascending index values scan and omits MATCH-level OrderByStep.
   * The full RID sequence matches the index order oracle.
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
    assertThat(matchItemRids(query))
        .as("ASC index values must match (timestamp ASC, @rid ASC)")
        .isEqualTo(expectedIndexedItemRids(true));
  }

  /**
   * ORDER BY a RETURN projection alias that is a bare {@code alias.property} still reaches the
   * index values path and returns the full timestamp sequence in index order.
   */
  @Test
  public void bareMatch_orderByProjectionAlias_usesIndex() {
    seedIndexedItems();
    var query = "MATCH {class: IndexedItem, as: item} RETURN item.timestamp AS ts"
        + " ORDER BY ts DESC";
    assertIndexValuesScan(plan(query), "IndexedItem_timestamp");
    assertThat(matchTimestamps(query, "ts"))
        .isEqualTo(expectedIndexedItemTimestamps(false));
  }

  /**
   * Plain SELECT control: same fixture, index values DESC, no class fetch, no separate sort, full
   * RID sequence matches the oracle.
   */
  @Test
  public void selectControl_usesIndexValuesDesc() {
    seedIndexedItems();
    var query = "SELECT FROM IndexedItem ORDER BY timestamp DESC";
    assertThat(plan(query))
        .contains("FETCH FROM INDEX VALUES DESC IndexedItem_timestamp")
        .doesNotContain("FETCH FROM CLASS IndexedItem")
        .doesNotContain("+ ORDER BY");
    assertThat(selectRids(query)).isEqualTo(expectedIndexedItemRids(false));
  }

  /**
   * Gremlin with RID tie-break on DESC: the index may still hold null keys, so SingleNodeIndexOrder
   * does not claim a covered scan (that would open the index and still fully sort). Plan keeps a
   * class fetch + MATCH OrderByStep; result order still matches the DESC+RID oracle.
   */
  @Test
  public void gremlinWithRidTieBreak_descKeepsClassFetchAndMatchOrder() {
    seedIndexedItems();
    var planText = translatedPlan(graph.traversal());
    assertThat(planText).contains(".@rid DESC");
    assertThat(planText)
        .as("DESC+@rid without null-key exclusion must not open a non-covering index scan")
        .doesNotContain("FETCH FROM INDEX VALUES")
        .contains("FETCH FROM CLASS IndexedItem")
        .contains("+ ORDER BY");
    assertThat(gremlinOrderedIds(graph.traversal()))
        .isEqualTo(expectedIndexedItemRids(false));
  }

  /**
   * Same Gremlin without the RID strategy: no {@code @rid} in the order, index values scan, full
   * id sequence still matches the DESC index oracle.
   */
  @Test
  public void gremlinWithoutRidTieBreak_usesIndexWithoutRidOrder() {
    seedIndexedItems();
    var source = graph.traversal().withoutStrategies(YTDBOrderRidTieBreakStrategy.class);
    var planText = translatedPlan(source);
    assertThat(planText).doesNotContain(".@rid");
    assertIndexValuesScan(planText, "IndexedItem_timestamp");
    assertThat(gremlinOrderedIds(source)).isEqualTo(expectedIndexedItemRids(false));
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
      rs.forEachRemaining(row -> names.add(String.valueOf((Object) row.getProperty("name"))));
    }
    assertThat(names).contains("nullish").hasSize(5);
  }

  /**
   * Ascending MATCH with an explicit {@code @rid} secondary that the index scan already produces
   * streams under a lowered heap cap and matches the full RID oracle.
   */
  @Test
  @Category(SequentialTest.class)
  public void bareMatchAscWithRid_streamsUnderLowHeapCap() {
    seedNamedScores(false);
    var query = "MATCH {class: Scored, as: s} RETURN s"
        + " ORDER BY s.score ASC, s.@rid ASC";
    assertThat(plan(query))
        .contains("FETCH FROM INDEX VALUES ASC Scored_score")
        .doesNotContain("FETCH FROM CLASS Scored")
        .doesNotContain("+ ORDER BY");

    var previous = GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.getValueAsInteger();
    GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.setValue(LOW_HEAP_CAP);
    try {
      var rids = new ArrayList<String>();
      try (var rs = session.query(query)) {
        rs.forEachRemaining(row -> rids.add(row.getVertex("s").getIdentity().toString()));
      }
      assertThat(rids).isEqualTo(expectedScoredRids(true));
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
   * WHERE on another property can steal a different index, so SingleNodeIndexOrder stays off and
   * MATCH keeps OrderByStep (including an explicit RID tie-break). Result order matches the DESC
   * score oracle over the filtered set.
   */
  @Test
  public void bareMatch_withWhereOnOtherProperty_keepsMatchOrderBy() {
    seedNamedScores(false);
    var query = "MATCH {class: Scored, as: s, where: (name <> 'nullish')} RETURN s"
        + " ORDER BY s.score DESC, s.@rid DESC";
    assertThat(plan(query))
        .doesNotContain("FETCH FROM INDEX VALUES DESC Scored_score")
        .contains("+ ORDER BY");

    var expected = expectedScoredRids(false).stream()
        .filter(rid -> {
          for (var v : graph.traversal().V().hasLabel("Scored").toList()) {
            if (v.id().toString().equals(rid)) {
              return !"nullish".equals(v.value("name"));
            }
          }
          return false;
        })
        .toList();
    var actual = new ArrayList<String>();
    try (var rs = session.query(query)) {
      rs.forEachRemaining(row -> actual.add(row.getVertex("s").getIdentity().toString()));
    }
    assertThat(actual).isEqualTo(expected);
  }

  /**
   * WHERE {@code score IS NOT NULL AND name = …} must not admit SingleNodeIndexOrder: the name
   * index can steal the root SELECT, drop the {@code @rid} secondary, and reorder equal-score ties.
   * Plan keeps MATCH OrderByStep; RID sequence matches the score+RID oracle.
   */
  @Test
  public void bareMatch_whereNotNullAndOtherIndexedProperty_keepsRidTieBreak() {
    var cls = session.createVertexClass("Scored2");
    cls.createProperty("score", PropertyType.INTEGER);
    cls.createProperty("name", PropertyType.STRING);
    session.execute("CREATE INDEX Scored2_score ON Scored2 (score) NOTUNIQUE").close();
    session.execute("CREATE INDEX Scored2_name ON Scored2 (name) NOTUNIQUE").close();
    session.begin();
    // Same score; name-index order (a then b) is the reverse of insert RID order (b then a).
    session.execute("CREATE VERTEX Scored2 SET score = 1, name = 'b'").close();
    session.execute("CREATE VERTEX Scored2 SET score = 1, name = 'a'").close();
    session.execute("CREATE VERTEX Scored2 SET score = 2, name = 'c'").close();
    session.commit();

    var query = "MATCH {class: Scored2, as: s, where: (score IS NOT NULL AND name >= 'a')}"
        + " RETURN s ORDER BY s.score ASC, s.@rid ASC";
    assertThat(plan(query))
        .as("AND with a second indexed conjunct must not claim covered index order")
        .doesNotContain("FETCH FROM INDEX VALUES")
        .contains("+ ORDER BY");

    var expected = new ArrayList<Object[]>();
    for (var v : graph.traversal().V().hasLabel("Scored2").toList()) {
      expected.add(new Object[] {v.value("score"), v.id()});
    }
    expected.sort(
        Comparator
            .<Object[], Object>comparing(r -> r[0], SingleNodeIndexOrderTest::compareNullsFirst)
            .thenComparing(r -> r[1], SingleNodeIndexOrderTest::compareNullsFirst));
    var expectedRids = expected.stream().map(r -> r[1].toString()).toList();

    var actual = new ArrayList<String>();
    try (var rs = session.query(query)) {
      rs.forEachRemaining(row -> actual.add(row.getVertex("s").getIdentity().toString()));
    }
    assertThat(actual).isEqualTo(expectedRids);
  }

  /**
   * Duplicate score ties: ASC + {@code @rid} matches the full identifier oracle.
   */
  @Test
  public void bareMatchAscWithRid_tieBreakMatchesRidOrder() {
    seedNamedScores(false);
    var query = "MATCH {class: Scored, as: s} RETURN s"
        + " ORDER BY s.score ASC, s.@rid ASC";
    var actual = new ArrayList<String>();
    try (var rs = session.query(query)) {
      rs.forEachRemaining(row -> actual.add(row.getVertex("s").getIdentity().toString()));
    }
    assertThat(actual).isEqualTo(expectedScoredRids(true));
  }

  private List<String> gremlinOrderedIds(GraphTraversalSource source) {
    return source.V().hasLabel("IndexedItem")
        .order().by("timestamp", Order.desc)
        .toList()
        .stream()
        .map(v -> ((Vertex) v).id().toString())
        .toList();
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
