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
   * Gremlin with RID tie-break translates to MATCH, keeps {@code .@rid} in the order clause, and
   * still opens the timestamp index (class fetch is gone). Full vertex id sequence matches the
   * DESC index oracle on this fixture (no null keys). MATCH keeps OrderByStep because null keys
   * remain eligible in the index ({@code orderFullyCovered=false}).
   */
  @Test
  public void gremlinWithRidTieBreak_usesIndex() {
    seedIndexedItems();
    var planText = translatedPlan(graph.traversal());
    assertThat(planText).contains(".@rid DESC");
    assertIndexValuesScan(planText, "IndexedItem_timestamp");
    assertThat(planText)
        .as("DESC+@rid with null keys still indexable must keep MATCH OrderByStep:\n%s", planText)
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
   * Ascending MATCH with an explicit {@code @rid} secondary: VALUES scan + OrderByStep present, but
   * clean-tx pass-through streams under a lowered heap cap (IndexOrdered-style PRE_SORTED signal).
   */
  @Test
  @Category(SequentialTest.class)
  public void bareMatchAscWithRid_streamsUnderLowHeapCapWhenTxClean() {
    seedNamedScores(false);
    var query = "MATCH {class: Scored, as: s} RETURN s"
        + " ORDER BY s.score ASC, s.@rid ASC";
    assertThat(plan(query))
        .contains("FETCH FROM INDEX VALUES ASC Scored_score")
        .doesNotContain("FETCH FROM CLASS Scored")
        .contains("+ ORDER BY");

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
   * Inside an open transaction, an uncommitted row can sit anywhere in its score group in the
   * index. MatchFirstStep withdraws PRE_SORTED so OrderByStep sorts; equal-score ties follow
   * {@code @rid}, including the pending row.
   */
  @Test
  public void bareMatchAscWithRid_pendingTx_keepsRidTieBreakOrder() {
    seedNamedScores(false);
    session.begin();
    session.execute("CREATE VERTEX Scored SET score = 1, name = 'pending'").close();

    var query = "MATCH {class: Scored, as: s} RETURN s"
        + " ORDER BY s.score ASC, s.@rid ASC";
    assertThat(plan(query))
        .contains("FETCH FROM INDEX VALUES ASC Scored_score")
        .contains("+ ORDER BY");

    // Oracle from the same tx visibility as MATCH (graph.traversal can miss SQL-created pending).
    var expectedRows = new ArrayList<Object[]>();
    try (var rs = session.query("SELECT @rid AS r, score FROM Scored")) {
      rs.forEachRemaining(row -> expectedRows.add(new Object[] {
          row.getProperty("score"),
          row.getProperty("r")
      }));
    }
    expectedRows.sort(
        Comparator
            .<Object[], Object>comparing(r -> r[0], SingleNodeIndexOrderTest::compareNullsFirst)
            .thenComparing(r -> r[1], SingleNodeIndexOrderTest::compareNullsFirst));
    var expected = expectedRows.stream().map(r -> r[1].toString()).toList();
    assertThat(expected).as("fixture plus pending row").hasSize(6);

    var actual = new ArrayList<String>();
    try (var rs = session.query(query)) {
      rs.forEachRemaining(row -> actual.add(row.getVertex("s").getIdentity().toString()));
    }
    assertThat(actual)
        .as("pending row must sit in @rid order inside score=1, not provisional index order")
        .isEqualTo(expected);
    session.rollback();
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
   * A range filter on the ordered property ({@code score >= 2}) is not a presence-only check, so
   * {@link SingleNodeIndexOrder} refuses the candidate and MATCH keeps OrderByStep. The root SELECT
   * still serves the filter via a range index fetch (not a class scan); DESC order of the filtered
   * rows must be correct.
   */
  @Test
  public void bareMatch_withWhereOnIndexedKey_usesIndexWithoutClassFetch() {
    seedNamedScores(false);
    var query = "MATCH {class: Scored, as: s, where: (score >= 2)} RETURN s.name AS name"
        + " ORDER BY s.score DESC";
    assertThat(plan(query))
        .contains("FETCH FROM INDEX Scored_score")
        .doesNotContain("FETCH FROM CLASS Scored")
        .contains("+ ORDER BY");

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

  /**
   * When the class also has a composite {@code (score, name)} index, sort-only planning must still
   * open the single-field {@code (score)} index. Picking the composite would order equal-score ties
   * by {@code name} while MATCH OrderBy finishes {@code @rid} — on≠off for Gremlin RID tie-break
   * and wrong MATCH {@code ORDER BY score, @rid}.
   *
   * <p>{@code getIndexesInternal()} returns a {@code HashSet}, so creating the composite before the
   * single-field index does not force iteration order; the plan assertions below are what catch a
   * revert to first-match selection whenever the composite happens to come first.
   */
  @Test
  public void bareMatchAscWithRid_prefersSingleFieldIndexOverCompositeLeadingSameKey() {
    var cls = session.createVertexClass("ScoredComp");
    cls.createProperty("score", PropertyType.INTEGER);
    cls.createProperty("name", PropertyType.STRING);
    session.execute("CREATE INDEX ScoredComp_score_name ON ScoredComp (score, name) NOTUNIQUE")
        .close();
    session.execute("CREATE INDEX ScoredComp_score ON ScoredComp (score) NOTUNIQUE").close();
    session.begin();
    // Same score; name order (a then b) is the reverse of insert RID order (b then a).
    session.execute("CREATE VERTEX ScoredComp SET score = 1, name = 'b'").close();
    session.execute("CREATE VERTEX ScoredComp SET score = 1, name = 'a'").close();
    session.execute("CREATE VERTEX ScoredComp SET score = 2, name = 'c'").close();
    session.commit();

    var query = "MATCH {class: ScoredComp, as: s} RETURN s"
        + " ORDER BY s.score ASC, s.@rid ASC";
    var planText = plan(query);
    assertThat(planText)
        .as("narrow single-field index must win over composite leading score:\n%s", planText)
        .contains("FETCH FROM INDEX VALUES ASC ScoredComp_score")
        .doesNotContain("ScoredComp_score_name")
        .doesNotContain("FETCH FROM CLASS ScoredComp")
        .contains("+ ORDER BY");

    var expected = new ArrayList<Object[]>();
    for (var v : graph.traversal().V().hasLabel("ScoredComp").toList()) {
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
    assertThat(actual)
        .as("equal-score ties must follow @rid, not composite name order")
        .isEqualTo(expectedRids);
  }

  /**
   * DESC + {@code @rid} with {@code score IS NOT NULL}: VALUES scan and OrderByStep; clean-tx
   * pass-through still matches the DESC RID oracle over non-null scores.
   */
  @Test
  public void bareMatchDescWithRid_isNotNullFilter_usesIndexAndKeepsOrderBy() {
    seedNamedScores(false);
    var query = "MATCH {class: Scored, as: s, where: (score IS NOT NULL)} RETURN s"
        + " ORDER BY s.score DESC, s.@rid DESC";
    var planText = plan(query);
    assertThat(planText)
        .as("presence filter admits score VALUES + OrderBy (pass-through when tx clean):\n%s",
            planText)
        .contains("FETCH FROM INDEX VALUES DESC Scored_score")
        .doesNotContain("FETCH FROM CLASS Scored")
        .contains("+ ORDER BY");

    var expected = expectedScoredRids(false).stream()
        .filter(rid -> {
          for (var v : graph.traversal().V().hasLabel("Scored").toList()) {
            if (v.id().toString().equals(rid)) {
              return v.property("score").isPresent();
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
   * DESC + {@code @rid} with null keys still in the index: open the score index for the primary
   * stream, keep MATCH OrderByStep ({@code orderFullyCovered=false}) because the null-key group is
   * stored outside the sorted tree and always walks RID ascending. Result must match the full
   * DESC+RID oracle, including RID order inside the null group.
   */
  @Test
  public void bareMatchDescWithRid_nullKeyGroupKeepsMatchOrderBy() {
    seedNamedScores(false);
    session.begin();
    session.execute("CREATE VERTEX Scored SET name = 'nullish-2'").close();
    session.commit();

    var query = "MATCH {class: Scored, as: s} RETURN s"
        + " ORDER BY s.score DESC, s.@rid DESC";
    var planText = plan(query);
    assertThat(planText)
        .as("primary key still streams from the index:\n%s", planText)
        .contains("FETCH FROM INDEX VALUES DESC Scored_score")
        .doesNotContain("FETCH FROM CLASS Scored");
    assertThat(planText)
        .as("null-key RID group is not DESC-native — MATCH must keep OrderByStep:\n%s", planText)
        .contains("+ ORDER BY");

    var actual = new ArrayList<String>();
    try (var rs = session.query(query)) {
      rs.forEachRemaining(row -> actual.add(row.getVertex("s").getIdentity().toString()));
    }
    assertThat(actual).isEqualTo(expectedScoredRids(false));
  }

  /**
   * {@code RETURN s.child AS s} rebinds the pattern alias. ORDER BY s.score must sort by the
   * child's score. SingleNode must not inject/elide using the parent node's index — that sorted by
   * parent score and dropped MATCH OrderBy.
   */
  @Test
  public void bareMatch_returnAliasShadowsPattern_doesNotInjectOrElide() {
    session.execute("CREATE CLASS ShadowParent EXTENDS V").close();
    session.execute("CREATE CLASS ShadowChild EXTENDS V").close();
    session.execute("CREATE PROPERTY ShadowParent.score INTEGER").close();
    session.execute("CREATE PROPERTY ShadowParent.child LINK ShadowChild").close();
    session.execute("CREATE PROPERTY ShadowChild.score INTEGER").close();
    session.execute("CREATE PROPERTY ShadowChild.tag STRING").close();
    session.execute("CREATE INDEX ShadowParent_score ON ShadowParent (score) NOTUNIQUE").close();
    session.begin();
    session.execute("CREATE VERTEX ShadowChild SET score = 1, tag = 'low-child'").close();
    session.execute("CREATE VERTEX ShadowChild SET score = 100, tag = 'high-child'").close();
    session.execute(
        "CREATE VERTEX ShadowParent SET score = 10, child = "
            + "(SELECT FROM ShadowChild WHERE score = 1)")
        .close();
    session.execute(
        "CREATE VERTEX ShadowParent SET score = 1, child = "
            + "(SELECT FROM ShadowChild WHERE score = 100)")
        .close();
    session.commit();

    var query = "MATCH {class: ShadowParent, as: s}"
        + " RETURN s.child.tag AS tag, s.child AS s ORDER BY s.score ASC";
    var planText = plan(query);
    assertThat(planText)
        .as("shadowed alias must not open parent score VALUES / elide MATCH OrderBy:\n%s", planText)
        .doesNotContain("FETCH FROM INDEX VALUES ASC ShadowParent_score")
        .contains("+ ORDER BY");

    var tags = new ArrayList<String>();
    try (var rs = session.query(query)) {
      rs.forEachRemaining(row -> tags.add(String.valueOf((Object) row.getProperty("tag"))));
    }
    // Child scores 1 then 100 — not parent scores 1 then 10 (that would be high-child, low-child).
    assertThat(tags).containsExactly("low-child", "high-child");
  }

  /**
   * Bare {@code ORDER BY s} after {@code RETURN s.k AS s} resolves through the projection to
   * {@code s.k}. That is not a shadow of the pattern vertex — SingleNode must still open the index
   * on {@code k} and return keys in index order.
   */
  @Test
  public void bareMatch_returnPropertyAsPatternAlias_orderByAlias_usesIndex() {
    session.execute("CREATE CLASS ProjItem EXTENDS V").close();
    session.execute("CREATE PROPERTY ProjItem.k LONG").close();
    session.execute("CREATE INDEX ProjItem_k ON ProjItem (k) NOTUNIQUE").close();
    session.begin();
    session.execute("CREATE VERTEX ProjItem SET k = 3").close();
    session.execute("CREATE VERTEX ProjItem SET k = 1").close();
    session.execute("CREATE VERTEX ProjItem SET k = 2").close();
    session.commit();

    var query = "MATCH {class: ProjItem, as: s} RETURN s.k AS s ORDER BY s ASC";
    var planText = plan(query);
    assertThat(planText)
        .as("projection alias ORDER BY s must still use ProjItem_k:\n%s", planText)
        .contains("FETCH FROM INDEX VALUES ASC ProjItem_k")
        .doesNotContain("FETCH FROM CLASS ProjItem")
        .doesNotContain("+ ORDER BY");

    var keys = new ArrayList<Long>();
    try (var rs = session.query(query)) {
      rs.forEachRemaining(row -> keys.add(((Number) row.getProperty("s")).longValue()));
    }
    assertThat(keys).containsExactly(1L, 2L, 3L);
  }

  /**
   * Last RETURN binding wins: {@code RETURN s.k AS s, s AS s} restores the pattern alias, so
   * {@code ORDER BY s.k} may open the index (same as develop before the first-wins shadow check).
   */
  @Test
  public void bareMatch_returnPropertyThenBareAlias_orderByProperty_usesIndex() {
    session.execute("CREATE CLASS LastWin EXTENDS V").close();
    session.execute("CREATE PROPERTY LastWin.k LONG").close();
    session.execute("CREATE INDEX LastWin_k ON LastWin (k) NOTUNIQUE").close();
    session.begin();
    session.execute("CREATE VERTEX LastWin SET k = 3").close();
    session.execute("CREATE VERTEX LastWin SET k = 1").close();
    session.execute("CREATE VERTEX LastWin SET k = 2").close();
    session.commit();

    var query = "MATCH {class: LastWin, as: s} RETURN s.k AS s, s AS s ORDER BY s.k ASC LIMIT 3";
    var planText = plan(query);
    assertThat(planText)
        .as("last-wins bare s must still admit LastWin_k VALUES:\n%s", planText)
        .contains("FETCH FROM INDEX VALUES ASC LastWin_k")
        .doesNotContain("FETCH FROM CLASS LastWin");

    var keys = new ArrayList<Long>();
    try (var rs = session.query(query)) {
      rs.forEachRemaining(
          row -> keys.add(((Number) row.getVertex("s").getProperty("k")).longValue()));
    }
    assertThat(keys).containsExactly(1L, 2L, 3L);
  }

  /**
   * Indexed {@code ORDER BY k} plus {@code UNWIND} and {@code LIMIT}: expanded rows must be the
   * first tags in {@code k} order (UNWIND before ORDER BY/LIMIT). SingleNode may open the index;
   * eliding the post-UNWIND sort is only safe while the LIMIT still applies to expanded rows in
   * that same {@code k} order.
   */
  @Test
  public void bareMatch_unwind_orderByIndexedKey_limitExpandedRows() {
    session.execute("CREATE CLASS UwIdx EXTENDS V").close();
    session.execute("CREATE PROPERTY UwIdx.k LONG").close();
    session.execute("CREATE PROPERTY UwIdx.tags EMBEDDEDLIST STRING").close();
    session.execute("CREATE INDEX UwIdx_k ON UwIdx (k) NOTUNIQUE").close();
    session.begin();
    // k=2 has three tags; k=1 and k=3 have one each. LIMIT 3 after UNWIND must take both tags
    // from k=1? No — k ASC: first parent k=1 (one tag), then k=2 (three tags) → limit 3 is
    // t1-a, t2-x, t2-y.
    session.execute("CREATE VERTEX UwIdx SET k = 2, tags = ['t2-x', 't2-y', 't2-z']").close();
    session.execute("CREATE VERTEX UwIdx SET k = 1, tags = ['t1-a']").close();
    session.execute("CREATE VERTEX UwIdx SET k = 3, tags = ['t3-a']").close();
    session.commit();

    var query = "MATCH {class: UwIdx, as: p} RETURN p.k AS k, p.tags AS tags"
        + " ORDER BY k ASC UNWIND tags LIMIT 3";
    var planText = plan(query);
    // Document whether SingleNode engages; either plan must still yield correct expanded LIMIT.
    assertThat(planText).contains("+ UNWIND");

    var tags = new ArrayList<String>();
    var keys = new ArrayList<Long>();
    try (var rs = session.query(query)) {
      rs.forEachRemaining(row -> {
        keys.add(((Number) row.getProperty("k")).longValue());
        tags.add(String.valueOf((Object) row.getProperty("tags")));
      });
    }
    assertThat(keys).containsExactly(1L, 2L, 2L);
    assertThat(tags).containsExactly("t1-a", "t2-x", "t2-y");
  }

  /**
   * {@code ORDER BY} the unwound field (not the indexed key). SingleNode must not satisfy this via
   * the {@code k} index; results are lexicographic tags with LIMIT.
   */
  @Test
  public void bareMatch_unwind_orderByUnwoundField_notIndexedKey() {
    session.execute("CREATE CLASS UwTag EXTENDS V").close();
    session.execute("CREATE PROPERTY UwTag.k LONG").close();
    session.execute("CREATE PROPERTY UwTag.tags EMBEDDEDLIST STRING").close();
    session.execute("CREATE INDEX UwTag_k ON UwTag (k) NOTUNIQUE").close();
    session.begin();
    session.execute("CREATE VERTEX UwTag SET k = 1, tags = ['zulu', 'bravo']").close();
    session.execute("CREATE VERTEX UwTag SET k = 9, tags = ['alpha']").close();
    session.execute("CREATE VERTEX UwTag SET k = 5, tags = ['yankee', 'charlie']").close();
    session.commit();

    var query = "MATCH {class: UwTag, as: p} RETURN p.k AS k, p.tags AS tags"
        + " ORDER BY tags ASC UNWIND tags LIMIT 3";
    assertThat(plan(query))
        .doesNotContain("FETCH FROM INDEX VALUES")
        .contains("+ ORDER BY")
        .contains("+ UNWIND");

    var tags = new ArrayList<String>();
    try (var rs = session.query(query)) {
      rs.forEachRemaining(row -> tags.add(String.valueOf((Object) row.getProperty("tags"))));
    }
    assertThat(tags).containsExactly("alpha", "bravo", "charlie");
  }

  /**
   * {@code GROUP BY} the indexed key with {@code ORDER BY} the same key and {@code LIMIT}.
   * SingleNode elision clears MATCH/SELECT {@code ORDER BY}, which enables aggregation early-stop
   * on {@code LIMIT}. That is only correct when groups emerge in index order (LinkedHashMap
   * first-seen) matching the requested {@code ORDER BY}.
   */
  @Test
  public void bareMatch_groupByIndexedKey_orderBySameKey_limit() {
    seedNamedScores(false);
    var query = "MATCH {class: Scored, as: s} RETURN s.score AS score, count(*) AS cnt"
        + " GROUP BY score ORDER BY score ASC LIMIT 2";
    var planText = plan(query);
    // Prefer documenting engagement; assert result regardless.
    var scores = new ArrayList<Object>();
    var counts = new ArrayList<Long>();
    try (var rs = session.query(query)) {
      rs.forEachRemaining(row -> {
        scores.add(row.getProperty("score"));
        counts.add(((Number) row.getProperty("cnt")).longValue());
      });
    }
    // Fixture: null, 1 (×2), 2, 3 → ASC with nulls first: null then 1
    assertThat(scores)
        .as("plan:\n%s", planText)
        .containsExactly(null, 1);
    assertThat(counts).containsExactly(1L, 2L);
  }

  /**
   * {@code GROUP BY name ORDER BY name} while only {@code score} is indexed. SingleNode must not
   * open the score index for a name sort; groups must still be name-ordered.
   */
  @Test
  public void bareMatch_groupByName_orderByName_scoreIndexMustNotDriveOrder() {
    seedNamedScores(false);
    var query = "MATCH {class: Scored, as: s} RETURN s.name AS name, count(*) AS cnt"
        + " GROUP BY name ORDER BY name ASC";
    assertThat(plan(query))
        .doesNotContain("FETCH FROM INDEX VALUES ASC Scored_score");

    var names = new ArrayList<String>();
    try (var rs = session.query(query)) {
      rs.forEachRemaining(row -> names.add(String.valueOf((Object) row.getProperty("name"))));
    }
    assertThat(names).containsExactly("a", "b", "c", "d", "nullish");
  }

  /**
   * {@code GROUP BY score ORDER BY cnt}: SingleNode must not treat this as a covered score order.
   * Groups ordered by count, not by score / index first-seen.
   */
  @Test
  public void bareMatch_groupByScore_orderByCount_notIndexOrder() {
    session.execute("CREATE CLASS GrpCnt EXTENDS V").close();
    session.execute("CREATE PROPERTY GrpCnt.score INTEGER").close();
    session.execute("CREATE INDEX GrpCnt_score ON GrpCnt (score) NOTUNIQUE").close();
    session.begin();
    session.execute("CREATE VERTEX GrpCnt SET score = 1").close();
    session.execute("CREATE VERTEX GrpCnt SET score = 1").close();
    session.execute("CREATE VERTEX GrpCnt SET score = 2").close();
    session.execute("CREATE VERTEX GrpCnt SET score = 3").close();
    session.execute("CREATE VERTEX GrpCnt SET score = 3").close();
    session.execute("CREATE VERTEX GrpCnt SET score = 3").close();
    session.commit();

    var query = "MATCH {class: GrpCnt, as: s} RETURN s.score AS score, count(*) AS cnt"
        + " GROUP BY score ORDER BY cnt ASC, score ASC";
    var planText = plan(query);
    var scores = new ArrayList<Object>();
    try (var rs = session.query(query)) {
      rs.forEachRemaining(row -> scores.add(row.getProperty("score")));
    }
    // counts: score2→1, score1→2, score3→3
    assertThat(scores)
        .as("ORDER BY cnt must win over score index stream; plan:\n%s", planText)
        .containsExactly(2, 1, 3);
  }

  /**
   * {@code GROUP BY name, score ORDER BY score DESC}: if SingleNode opens ASC-incompatible
   * streaming and clears {@code ORDER BY}, LinkedHashMap first-seen on a wrong stream would
   * mis-order. Expect highest score group first.
   */
  @Test
  public void bareMatch_groupByName_orderByIndexedScore_mustSortGroupsByScore() {
    session.execute("CREATE CLASS GrpScore EXTENDS V").close();
    session.execute("CREATE PROPERTY GrpScore.score INTEGER").close();
    session.execute("CREATE PROPERTY GrpScore.name STRING").close();
    session.execute("CREATE INDEX GrpScore_score ON GrpScore (score) NOTUNIQUE").close();
    session.begin();
    session.execute("CREATE VERTEX GrpScore SET score = 1, name = 'z'").close();
    session.execute("CREATE VERTEX GrpScore SET score = 2, name = 'a'").close();
    session.execute("CREATE VERTEX GrpScore SET score = 3, name = 'm'").close();
    session.commit();

    var desc =
        "MATCH {class: GrpScore, as: s} RETURN s.name AS name, s.score AS score, count(*) AS cnt"
            + " GROUP BY name, score ORDER BY score DESC";
    var planText = plan(desc);
    var namesDesc = new ArrayList<String>();
    try (var rs = session.query(desc)) {
      rs.forEachRemaining(row -> namesDesc.add(String.valueOf((Object) row.getProperty("name"))));
    }
    assertThat(namesDesc)
        .as("GROUP BY + ORDER BY score DESC; plan:\n%s", planText)
        .containsExactly("m", "a", "z");

    var asc =
        "MATCH {class: GrpScore, as: s} RETURN s.name AS name, s.score AS score, count(*) AS cnt"
            + " GROUP BY name, score ORDER BY score ASC";
    var namesAsc = new ArrayList<String>();
    try (var rs = session.query(asc)) {
      rs.forEachRemaining(row -> namesAsc.add(String.valueOf((Object) row.getProperty("name"))));
    }
    assertThat(namesAsc).containsExactly("z", "a", "m");
  }

  /**
   * DESC {@code GROUP BY score ORDER BY score LIMIT 1} with a null key in the index. Aggregation
   * early-stop after SingleNode clears {@code ORDER BY} must still return the top non-null score
   * group under default null placement (nulls first on DESC would wrongly win LIMIT 1).
   */
  @Test
  public void bareMatch_groupByScore_descLimit1_withNullKey() {
    seedNamedScores(false);
    var query = "MATCH {class: Scored, as: s} RETURN s.score AS score, count(*) AS cnt"
        + " GROUP BY score ORDER BY score DESC LIMIT 1";
    var planText = plan(query);
    try (var rs = session.query(query)) {
      assertThat(rs.hasNext()).isTrue();
      var row = rs.next();
      assertThat(row.<Object>getProperty("score"))
          .as("DESC LIMIT 1 must be score 3 not the null group; plan:\n%s", planText)
          .isEqualTo(3);
      assertThat(rs.hasNext()).isFalse();
    }
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
