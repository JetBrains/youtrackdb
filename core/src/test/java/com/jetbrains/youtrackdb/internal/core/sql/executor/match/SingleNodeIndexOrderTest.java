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
 * Edge-free MATCH {@code ORDER BY} rewritten onto the synthetic root SELECT ({@link
 * SingleNodeIndexOrder}) so fetch matches SELECT ({@code filter index → VALUES → class}), distinct
 * from hop-based {@link IndexOrderedPlanner}.
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
   * remain eligible in the index ({@code orderFullyCovered=false}); the step sorts within
   * primary-key groups (ties / null bucket) rather than re-sorting the whole stream.
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
   * Without an index on the ordered property, the root SELECT falls back to a class fetch and its
   * in-memory OrderByStep (MATCH elides a second sort for a single primary key).
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
   * An index that ignores null values must not serve ORDER BY: the key-less row would disappear.
   * SELECT (and MATCH via the same root) falls back; all rows including the nullish one remain.
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
   * Control: multi-property ORDER BY with no covering index still buffers in the root SELECT's
   * OrderByStep (MATCH elides a second sort) and trips the lowered heap cap when unbounded.
   */
  @Test
  @Category(SequentialTest.class)
  public void bareMatch_multiPropertyWithoutCoveringIndex_buffersAndHitsHeapCap() {
    seedNamedScores(false);
    // name is not leading-indexed; SELECT OrderByStep buffers after the rewritten bare keys.
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
   * Narrow elision pushes LIMIT onto the synthetic root SELECT, so ORDER BY without a covering
   * index still uses a bounded heap (top-N) and succeeds under a heap cap equal to LIMIT. Asserts
   * which rows, not only the row count.
   */
  @Test
  @Category(SequentialTest.class)
  public void bareMatch_orderByWithoutIndex_limitUsesBoundedHeap() {
    seedNamedScores(false);
    var query = "MATCH {class: Scored, as: s} RETURN s.name AS name"
        + " ORDER BY s.name ASC LIMIT 2";
    var previous = GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.getValueAsInteger();
    GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.setValue(2);
    try {
      var names = new ArrayList<String>();
      try (var rs = session.query(query)) {
        rs.forEachRemaining(row -> names.add(String.valueOf((Object) row.getProperty("name"))));
      }
      assertThat(names).containsExactly("a", "b");
    } finally {
      GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.setValue(previous);
    }
  }

  /**
   * SKIP must ride with LIMIT on the pushed root SELECT: after name ASC, SKIP 1 LIMIT 1 yields
   * the second name, not the first.
   */
  @Test
  public void bareMatch_orderByWithoutIndex_skipAndLimitSelectCorrectRow() {
    seedNamedScores(false);
    var query = "MATCH {class: Scored, as: s} RETURN s.name AS name"
        + " ORDER BY s.name ASC SKIP 1 LIMIT 1";
    var names = new ArrayList<String>();
    try (var rs = session.query(query)) {
      rs.forEachRemaining(row -> names.add(String.valueOf((Object) row.getProperty("name"))));
    }
    assertThat(names).containsExactly("b");
  }

  /**
   * UNWIND changes cardinality after the root scan, so SingleNode must not inject ORDER BY into
   * MatchFirstStep. Plan keeps a MATCH-level OrderByStep after UNWIND; LIMIT applies to expanded
   * rows (alpha, bravo, charlie), not to persons sorted by the tags array.
   */
  @Test
  public void bareMatch_unwindBeforeOrderBy_doesNotInjectRootSelectOrder() {
    session.execute("CREATE CLASS UWItem EXTENDS V").close();
    session.execute("CREATE PROPERTY UWItem.tags EMBEDDEDLIST STRING").close();
    session.begin();
    session.execute("CREATE VERTEX UWItem SET name = 'alice', tags = ['zulu', 'bravo', 'alpha']")
        .close();
    session.execute("CREATE VERTEX UWItem SET name = 'bob', tags = ['yankee', 'charlie']").close();
    session.execute("CREATE VERTEX UWItem SET name = 'carol', tags = ['delta']").close();
    session.commit();

    var query = "MATCH {class: UWItem, as: p} RETURN p.name AS name, p.tags AS tags"
        + " ORDER BY tags ASC UNWIND tags LIMIT 3";
    var planText = plan(query);
    assertThat(planText)
        .as("UNWIND must keep MATCH OrderBy off the root SELECT:\n%s", planText)
        .doesNotContain("FETCH FROM INDEX VALUES")
        .contains("+ UNWIND")
        .contains("+ ORDER BY");

    var tags = new ArrayList<String>();
    try (var rs = session.query(query)) {
      rs.forEachRemaining(row -> tags.add(String.valueOf((Object) row.getProperty("tags"))));
    }
    assertThat(tags).containsExactly("alpha", "bravo", "charlie");
  }

  /**
   * Same grain guard with an indexed ORDER BY key: without UNWIND the root opens VALUES; with
   * UNWIND injection is skipped so the plan has no VALUES on that index.
   */
  @Test
  public void bareMatch_unwind_skipsInjectionWhenIndexWouldApplyOtherwise() {
    session.execute("CREATE CLASS UWIdx EXTENDS V").close();
    session.execute("CREATE PROPERTY UWIdx.k LONG").close();
    session.execute("CREATE PROPERTY UWIdx.payload EMBEDDEDLIST STRING").close();
    session.execute("CREATE INDEX UWIdx_k ON UWIdx (k) NOTUNIQUE").close();
    session.begin();
    session.execute("CREATE VERTEX UWIdx SET k = 1, payload = ['b', 'a']").close();
    session.execute("CREATE VERTEX UWIdx SET k = 2, payload = ['c']").close();
    session.commit();

    var withoutUnwind =
        "MATCH {class: UWIdx, as: v} RETURN v.payload AS payload ORDER BY v.k ASC";
    assertThat(plan(withoutUnwind)).contains("FETCH FROM INDEX VALUES");

    var withUnwind =
        "MATCH {class: UWIdx, as: v} RETURN v.payload AS payload"
            + " ORDER BY v.k ASC UNWIND payload";
    var planText = plan(withUnwind);
    assertThat(planText)
        .contains("+ UNWIND")
        .contains("+ ORDER BY")
        .doesNotContain("FETCH FROM INDEX VALUES ASC UWIdx_k");
  }

  /**
   * Duplicate RETURN aliases: ORDER BY resolves to the last expression (same as projection), so
   * collation / values come from that property — not the first alias hit.
   */
  @Test
  public void bareMatch_duplicateReturnAlias_ordersByLastExpression() {
    var cls = session.createVertexClass("DupAlias");
    cls.createProperty("name", PropertyType.STRING);
    session.execute("ALTER PROPERTY DupAlias.name COLLATE ci").close();
    cls.createProperty("surname", PropertyType.STRING);
    session.begin();
    session.execute("CREATE VERTEX DupAlias SET name = 'first', surname = 'Zebra'").close();
    session.execute("CREATE VERTEX DupAlias SET name = 'second', surname = 'ada'").close();
    session.execute("CREATE VERTEX DupAlias SET name = 'third', surname = 'Ada'").close();
    session.commit();

    var query = "MATCH {class: DupAlias, as: a} RETURN a.name AS x, a.surname AS x ORDER BY x";
    var values = new ArrayList<String>();
    try (var rs = session.query(query)) {
      rs.forEachRemaining(row -> values.add(String.valueOf((Object) row.getProperty("x"))));
    }
    assertThat(values).containsExactly("Ada", "Zebra", "ada");
  }

  /**
   * Multi-property ORDER BY on an exact-width composite matches SELECT: VALUES scan, no class
   * fetch, no second MATCH OrderByStep, full (score, name) sequence.
   */
  @Test
  public void bareMatch_multiProperty_usesCompositeIndexValuesLikeSelect() {
    var cls = session.createVertexClass("ScoredPair");
    cls.createProperty("score", PropertyType.INTEGER);
    cls.createProperty("name", PropertyType.STRING);
    session.execute("CREATE INDEX ScoredPair_score_name ON ScoredPair (score, name) NOTUNIQUE")
        .close();
    session.begin();
    session.execute("CREATE VERTEX ScoredPair SET score = 1, name = 'b'").close();
    session.execute("CREATE VERTEX ScoredPair SET score = 1, name = 'a'").close();
    session.execute("CREATE VERTEX ScoredPair SET score = 2, name = 'c'").close();
    session.commit();

    var matchQuery = "MATCH {class: ScoredPair, as: s} RETURN s.name AS name"
        + " ORDER BY s.score ASC, s.name ASC";
    var selectQuery = "SELECT name FROM ScoredPair ORDER BY score ASC, name ASC";
    assertThat(plan(matchQuery))
        .contains("FETCH FROM INDEX VALUES ASC ScoredPair_score_name")
        .doesNotContain("FETCH FROM CLASS ScoredPair")
        .doesNotContain("+ ORDER BY");
    assertThat(plan(selectQuery))
        .contains("FETCH FROM INDEX VALUES ASC ScoredPair_score_name")
        .doesNotContain("FETCH FROM CLASS ScoredPair")
        .doesNotContain("+ ORDER BY");

    var matchNames = new ArrayList<String>();
    try (var rs = session.query(matchQuery)) {
      rs.forEachRemaining(row -> matchNames.add(String.valueOf((Object) row.getProperty("name"))));
    }
    var selectNames = new ArrayList<String>();
    try (var rs = session.query(selectQuery)) {
      rs.forEachRemaining(row -> selectNames.add(String.valueOf((Object) row.getProperty("name"))));
    }
    assertThat(matchNames).containsExactly("a", "b", "c").isEqualTo(selectNames);
  }

  /**
   * Multi-property ORDER BY plus trailing {@code @rid} on an exact-width composite: SELECT opens
   * VALUES on the composite; RID elision admits coverage so MATCH drops OrderByStep; equal
   * (score, name) ties follow {@code @rid}.
   */
  @Test
  public void bareMatch_multiPropertyWithRid_usesCompositeAndCoversRid() {
    var cls = session.createVertexClass("ScoredPairRid");
    cls.createProperty("score", PropertyType.INTEGER);
    cls.createProperty("name", PropertyType.STRING);
    session.execute(
        "CREATE INDEX ScoredPairRid_score_name ON ScoredPairRid (score, name) NOTUNIQUE")
        .close();
    session.begin();
    session.execute("CREATE VERTEX ScoredPairRid SET score = 1, name = 'a'").close();
    session.execute("CREATE VERTEX ScoredPairRid SET score = 1, name = 'a'").close();
    session.execute("CREATE VERTEX ScoredPairRid SET score = 2, name = 'b'").close();
    session.commit();

    var query = "MATCH {class: ScoredPairRid, as: s} RETURN s"
        + " ORDER BY s.score ASC, s.name ASC, s.@rid ASC";
    assertThat(plan(query))
        .contains("FETCH FROM INDEX VALUES ASC ScoredPairRid_score_name")
        .doesNotContain("FETCH FROM CLASS ScoredPairRid")
        .doesNotContain("+ ORDER BY");

    var expected = new ArrayList<Object[]>();
    for (var v : graph.traversal().V().hasLabel("ScoredPairRid").toList()) {
      expected.add(new Object[] {v.value("score"), v.value("name"), v.id()});
    }
    expected.sort(
        Comparator
            .<Object[], Object>comparing(r -> r[0], SingleNodeIndexOrderTest::compareNullsFirst)
            .thenComparing(r -> r[1], SingleNodeIndexOrderTest::compareNullsFirst)
            .thenComparing(r -> r[2], SingleNodeIndexOrderTest::compareNullsFirst));
    var expectedRids = expected.stream().map(r -> r[2].toString()).toList();

    var actual = new ArrayList<String>();
    try (var rs = session.query(query)) {
      rs.forEachRemaining(row -> actual.add(row.getVertex("s").getIdentity().toString()));
    }
    assertThat(actual).isEqualTo(expectedRids);
  }

  /**
   * Range filter on the ordered property: root SELECT opens the score index (same queue as {@code
   * SELECT FROM Scored WHERE score >= 2 ORDER BY score DESC}). Result order of the filtered rows
   * must be correct.
   */
  @Test
  public void bareMatch_withWhereOnIndexedKey_usesIndexWithoutClassFetch() {
    seedNamedScores(false);
    var query = "MATCH {class: Scored, as: s, where: (score >= 2)} RETURN s.name AS name"
        + " ORDER BY s.score DESC";
    var selectControl = "SELECT name FROM Scored WHERE score >= 2 ORDER BY score DESC";
    assertThat(plan(query))
        .contains("FETCH FROM INDEX Scored_score")
        .doesNotContain("FETCH FROM CLASS Scored")
        .doesNotContain("+ ORDER BY");
    assertThat(plan(selectControl))
        .contains("FETCH FROM INDEX Scored_score")
        .doesNotContain("FETCH FROM CLASS Scored");

    var names = new ArrayList<String>();
    try (var rs = session.query(query)) {
      rs.forEachRemaining(row -> names.add(String.valueOf((Object) row.getProperty("name"))));
    }
    assertThat(names).containsExactly("d", "c");
  }

  /**
   * WHERE on another property with no competing index: SELECT (and MATCH) open sort-only VALUES on
   * the ORDER BY key and apply the name predicate as a residual. RID secondary is not covered, so
   * MATCH keeps OrderByStep; result matches the DESC score+RID oracle over the filtered set.
   */
  @Test
  public void bareMatch_withWhereOnOtherProperty_usesValuesAndKeepsRidOrderBy() {
    seedNamedScores(false);
    var query = "MATCH {class: Scored, as: s, where: (name <> 'nullish')} RETURN s"
        + " ORDER BY s.score DESC, s.@rid DESC";
    var planText = plan(query);
    assertThat(planText)
        .as("residual WHERE must not block VALUES on the order key:\n%s", planText)
        .contains("FETCH FROM INDEX VALUES DESC Scored_score")
        .doesNotContain("FETCH FROM CLASS Scored")
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
   * WHERE {@code score IS NOT NULL AND name >= …}: the name index can steal the root SELECT, so
   * RID elision stays off. Plan must not claim VALUES coverage; RID sequence matches the
   * score+RID oracle.
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
   * by {@code name} while MATCH claims RID coverage and drops its OrderByStep — on≠off for Gremlin
   * RID tie-break and wrong MATCH {@code ORDER BY score, @rid}.
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
        .doesNotContain("+ ORDER BY");

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
   * DESC + {@code @rid} with {@code score IS NOT NULL}: null keys are excluded from the result, so
   * {@code acceptsRidTieBreak} admits full coverage. Plan streams the index values scan and omits
   * MATCH OrderByStep; RID sequence matches the DESC oracle over the non-null scores only.
   */
  @Test
  public void bareMatchDescWithRid_isNotNullFilter_fullyCoversIndexOrder() {
    seedNamedScores(false);
    var query = "MATCH {class: Scored, as: s, where: (score IS NOT NULL)} RETURN s"
        + " ORDER BY s.score DESC, s.@rid DESC";
    var planText = plan(query);
    assertThat(planText)
        .as("presence filter admits covered DESC+RID:\n%s", planText)
        .contains("FETCH FROM INDEX VALUES DESC Scored_score")
        .doesNotContain("FETCH FROM CLASS Scored")
        .doesNotContain("+ ORDER BY");

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
   * stored outside the sorted tree and always walks RID ascending. OrderByStep is hinted that the
   * primary key is already sorted, so it only reorders within equal-score (and null) groups.
   * Result must match the full DESC+RID oracle, including RID order inside the null group.
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
   * GROUP BY changes grain before ORDER BY. SingleNode must not inject (or elide) root SELECT
   * order — otherwise raw rows would be sorted/covered before aggregation. Plan keeps MATCH-level
   * ORDER BY; groups are ordered by the grouping key.
   */
  @Test
  public void bareMatch_groupBy_skipsRootSelectOrderInject() {
    seedNamedScores(false);
    var query = "MATCH {class: Scored, as: s} RETURN s.score AS score, count(*) AS cnt"
        + " GROUP BY score ORDER BY score ASC";
    var planText = plan(query);
    assertThat(planText)
        .as("GROUP BY must not open sort-only VALUES on the root:\n%s", planText)
        .doesNotContain("FETCH FROM INDEX VALUES")
        .contains("GROUP BY")
        .contains("ORDER BY");

    var scores = new ArrayList<Object>();
    var counts = new ArrayList<Long>();
    try (var rs = session.query(query)) {
      rs.forEachRemaining(row -> {
        scores.add(row.getProperty("score"));
        counts.add(((Number) row.getProperty("cnt")).longValue());
      });
    }
    // seedNamedScores: scores 1,1,2,3,null → groups ordered nulls-first or by ASC score
    assertThat(scores).hasSize(4);
    assertThat(counts.stream().mapToLong(Long::longValue).sum()).isEqualTo(5L);
    // Non-null scores ascend; null group is present exactly once.
    var nonNull = scores.stream().filter(s -> s != null).map(s -> ((Number) s).intValue()).toList();
    assertThat(nonNull).containsExactly(1, 2, 3);
    assertThat(scores).containsNull();
  }

  /**
   * RETURN DISTINCT + LIMIT: inject may still open VALUES, but elision must not drop MATCH
   * OrderBy (DISTINCT changes cardinality before LIMIT). Result is the first LIMIT distinct
   * scores in order.
   */
  @Test
  public void bareMatch_distinctLimit_keepsMatchOrderAndCorrectTopN() {
    seedNamedScores(false);
    var query = "MATCH {class: Scored, as: s} RETURN DISTINCT s.score AS score"
        + " ORDER BY score ASC LIMIT 2";
    var planText = plan(query);
    assertThat(planText)
        .as("DISTINCT+LIMIT must keep a MATCH-level OrderByStep:\n%s", planText)
        .contains("+ ORDER BY");

    var scores = new ArrayList<Object>();
    try (var rs = session.query(query)) {
      rs.forEachRemaining(row -> scores.add(row.getProperty("score")));
    }
    assertThat(scores).hasSize(2);
    // ASC: null group first (seed has a null score), then 1 — or 1 then 2 if nulls last.
    // Pin against the SELECT control so placement stays aligned with the engine default.
    var selectScores = new ArrayList<Object>();
    try (var rs = session.query(
        "SELECT DISTINCT score FROM Scored ORDER BY score ASC LIMIT 2")) {
      rs.forEachRemaining(row -> selectScores.add(row.getProperty("score")));
    }
    assertThat(scores).isEqualTo(selectScores);
  }

  /**
   * Multi-property ORDER BY with an index only on the leading key: MATCH injects both bare keys
   * like SELECT (no composite VALUES). Result order matches the SELECT control; neither plan
   * claims a composite VALUES scan.
   */
  @Test
  public void bareMatch_multiProperty_leadingIndexOnly_matchesSelectOrder() {
    seedNamedScores(false);
    var matchQuery = "MATCH {class: Scored, as: s} RETURN s.name AS name"
        + " ORDER BY s.score ASC, s.name ASC";
    var selectQuery = "SELECT name FROM Scored ORDER BY score ASC, name ASC";
    assertThat(plan(matchQuery)).doesNotContain("FETCH FROM INDEX VALUES ASC Scored_score_name");
    assertThat(plan(selectQuery)).doesNotContain("FETCH FROM INDEX VALUES ASC Scored_score_name");

    var matchNames = new ArrayList<String>();
    try (var rs = session.query(matchQuery)) {
      rs.forEachRemaining(row -> matchNames.add(String.valueOf((Object) row.getProperty("name"))));
    }
    var selectNames = new ArrayList<String>();
    try (var rs = session.query(selectQuery)) {
      rs.forEachRemaining(row -> selectNames.add(String.valueOf((Object) row.getProperty("name"))));
    }
    assertThat(matchNames).isEqualTo(selectNames).hasSize(5);
  }

  /**
   * Residual OR on a non-order property still injects: SELECT (and MATCH) may open VALUES on the
   * order key and apply the OR as a residual. Filtered names match the SELECT control in score
   * order.
   */
  @Test
  public void bareMatch_residualOrWhere_usesValuesLikeSelect() {
    seedNamedScores(false);
    var matchQuery = "MATCH {class: Scored, as: s, where: (name = 'a' OR name = 'c')}"
        + " RETURN s.name AS name ORDER BY s.score ASC";
    var selectQuery = "SELECT name FROM Scored WHERE name = 'a' OR name = 'c' ORDER BY score ASC";
    assertThat(plan(matchQuery))
        .contains("FETCH FROM INDEX VALUES ASC Scored_score")
        .doesNotContain("FETCH FROM CLASS Scored");
    assertThat(plan(selectQuery))
        .contains("FETCH FROM INDEX VALUES ASC Scored_score")
        .doesNotContain("FETCH FROM CLASS Scored");

    var matchNames = new ArrayList<String>();
    try (var rs = session.query(matchQuery)) {
      rs.forEachRemaining(row -> matchNames.add(String.valueOf((Object) row.getProperty("name"))));
    }
    var selectNames = new ArrayList<String>();
    try (var rs = session.query(selectQuery)) {
      rs.forEachRemaining(row -> selectNames.add(String.valueOf((Object) row.getProperty("name"))));
    }
    assertThat(matchNames).containsExactly("a", "c").isEqualTo(selectNames);
  }

  /**
   * LIMIT must stay after a NOT anti-join. Pushing LIMIT onto the root SELECT would keep only the
   * lowest-score vertex; if that vertex has an outgoing edge, NOT removes it and the query returns
   * empty. Correct: first vertex without outgoing edges after ORDER BY score.
   */
  @Test
  public void bareMatch_notPattern_limitAppliesAfterAntiJoin() {
    session.execute("CREATE CLASS NotNode EXTENDS V").close();
    session.execute("CREATE PROPERTY NotNode.score INTEGER").close();
    session.execute("CREATE INDEX NotNode_score ON NotNode (score) NOTUNIQUE").close();
    session.execute("CREATE CLASS NotEdge EXTENDS E").close();
    session.begin();
    session.execute("CREATE VERTEX NotNode SET score = 1, name = 'has-edge'").close();
    session.execute("CREATE VERTEX NotNode SET score = 2, name = 'no-edge'").close();
    session.execute(
        "CREATE EDGE NotEdge FROM (SELECT FROM NotNode WHERE score = 1)"
            + " TO (SELECT FROM NotNode WHERE score = 2)")
        .close();
    session.commit();

    var query = "MATCH {class: NotNode, as: a}, NOT {as: a}-->{}"
        + " RETURN a.name AS name ORDER BY a.score ASC LIMIT 1";
    var planText = plan(query);
    assertThat(planText)
        .as("NOT+LIMIT must keep MATCH OrderBy / not push LIMIT into MatchFirst:\n%s", planText)
        .contains("+ ORDER BY");

    var names = new ArrayList<String>();
    try (var rs = session.query(query)) {
      rs.forEachRemaining(row -> names.add(String.valueOf((Object) row.getProperty("name"))));
    }
    assertThat(names).containsExactly("no-edge");
  }

  /**
   * Aggregate without GROUP BY must see every root row. Pushing LIMIT under the aggregate would
   * sum only the first ORDER BY row (1) instead of 1+2+3.
   */
  @Test
  public void bareMatch_aggregateNoGroupBy_limitDoesNotCutAggregateInput() {
    session.execute("CREATE CLASS AggScore EXTENDS V").close();
    session.execute("CREATE PROPERTY AggScore.score INTEGER").close();
    session.execute("CREATE INDEX AggScore_score ON AggScore (score) NOTUNIQUE").close();
    session.begin();
    session.execute("CREATE VERTEX AggScore SET score = 1").close();
    session.execute("CREATE VERTEX AggScore SET score = 2").close();
    session.execute("CREATE VERTEX AggScore SET score = 3").close();
    session.commit();

    var query = "MATCH {class: AggScore, as: s} RETURN sum(s.score) AS total"
        + " ORDER BY s.score ASC LIMIT 1";
    var planText = plan(query);
    assertThat(planText)
        .as("aggregate+LIMIT must keep MATCH OrderBy:\n%s", planText)
        .contains("+ ORDER BY");

    try (var rs = session.query(query)) {
      assertThat(rs.hasNext()).isTrue();
      assertThat(((Number) rs.next().getProperty("total")).longValue()).isEqualTo(6L);
      assertThat(rs.hasNext()).isFalse();
    }
  }

  /**
   * Mixed ASC/DESC among property keys cannot ride an index VALUES scan for a trailing {@code
   * @rid}. Refuse SingleNode inject so MATCH keeps the full sort (including {@code @rid}).
   */
  @Test
  public void bareMatch_mixedDirectionRid_keepsMatchSort() {
    seedNamedScores(false);
    var query = "MATCH {class: Scored, as: s} RETURN s.name AS name, s.score AS score"
        + " ORDER BY s.score DESC, s.name ASC, s.@rid DESC";
    var planText = plan(query);
    assertThat(planText)
        .as("mixed property directions + @rid must not elide MATCH OrderBy:\n%s", planText)
        .contains("+ ORDER BY")
        .doesNotContain("FETCH FROM INDEX VALUES");

    var expected = new ArrayList<String>();
    try (var rs = session.query(
        "SELECT name FROM Scored ORDER BY score DESC, name ASC, @rid DESC")) {
      rs.forEachRemaining(row -> expected.add(String.valueOf((Object) row.getProperty("name"))));
    }
    var actual = new ArrayList<String>();
    try (var rs = session.query(query)) {
      rs.forEachRemaining(row -> actual.add(String.valueOf((Object) row.getProperty("name"))));
    }
    assertThat(actual).isEqualTo(expected);
  }

  /**
   * {@code RETURN s.child AS s} rebinds the pattern alias. ORDER BY s.score must sort by the
   * child's score, not the parent's — SingleNode must not inject using the pattern node.
   */
  @Test
  public void bareMatch_returnAliasShadowsPattern_sortsByProjectedEntity() {
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

    // Keep ORDER BY on the shadowed alias; project tag beside it for a stable assertion.
    var query = "MATCH {class: ShadowParent, as: s}"
        + " RETURN s.child.tag AS tag, s.child AS s ORDER BY s.score ASC";
    var planText = plan(query);
    assertThat(planText)
        .as("shadowed alias must not open parent score VALUES:\n%s", planText)
        .doesNotContain("FETCH FROM INDEX VALUES ASC ShadowParent_score")
        .contains("+ ORDER BY");

    var tags = new ArrayList<String>();
    try (var rs = session.query(query)) {
      rs.forEachRemaining(row -> tags.add(String.valueOf((Object) row.getProperty("tag"))));
    }
    assertThat(tags).containsExactly("low-child", "high-child");
  }

  /**
   * primaryKeySortedInput must survive SELECT ORDER BY rewrite so plan-cache {@code copy()} does
   * not throw when the projection adds expression aliases beside a RID secondary.
   */
  @Test
  public void bareMatch_primaryKeyHint_survivesPlanCacheCopy() {
    seedNamedScores(false);
    // Distinct scores avoid RID-tie ambiguity; expression alias `d` forces ORDER BY rewrite.
    var query = "MATCH {class: Scored, as: s, where: (name = 'a' OR name = 'c' OR name = 'd')}"
        + " RETURN s.name AS n, s.score * 2 AS d"
        + " ORDER BY s.score ASC, s.@rid ASC LIMIT 2";
    // Warm the plan cache, then execute again from the cached plan (copy path).
    try (var rs = session.query(query)) {
      assertThat(rs.stream().count()).isEqualTo(2);
    }
    var names = new ArrayList<String>();
    try (var rs = session.query(query)) {
      rs.forEachRemaining(row -> names.add(String.valueOf((Object) row.getProperty("n"))));
    }
    assertThat(names).containsExactly("a", "c");
  }

  /**
   * No index on the ORDER BY key plus trailing {@code @rid}: do not inject an unbounded root
   * SELECT sort while MATCH keeps OrderBy+LIMIT. MATCH alone does the bounded top-N under the
   * heap cap.
   */
  @Test
  @Category(SequentialTest.class)
  public void bareMatch_noIndexRidTrailing_limitStaysBoundedOnMatch() {
    session.execute("CREATE CLASS PlainKey EXTENDS V").close();
    session.execute("CREATE PROPERTY PlainKey.k LONG").close();
    session.begin();
    for (var i = 0; i < 1000; i++) {
      session.execute("CREATE VERTEX PlainKey SET k = " + i).close();
    }
    session.commit();

    var query = "MATCH {class: PlainKey, as: c} RETURN c.k AS k"
        + " ORDER BY c.k ASC, c.@rid ASC LIMIT 10";
    var planText = plan(query);
    assertThat(planText)
        .as("no-index + @rid must not inject root SELECT OrderBy:\n%s", planText)
        .contains("+ ORDER BY")
        .doesNotContain("FETCH FROM INDEX VALUES");

    var previous = GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.getValueAsInteger();
    GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.setValue(10);
    try {
      var keys = new ArrayList<Long>();
      try (var rs = session.query(query)) {
        rs.forEachRemaining(row -> keys.add(((Number) row.getProperty("k")).longValue()));
      }
      assertThat(keys).containsExactly(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);
    } finally {
      GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.setValue(previous);
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
