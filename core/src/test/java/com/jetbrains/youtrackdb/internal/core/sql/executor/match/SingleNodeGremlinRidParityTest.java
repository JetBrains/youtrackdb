package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Clean-transaction proofs for the single-node shapes that move from class-scan+sort on develop to
 * an index stream with a Gremlin {@code @rid} tie-break. Pending-transaction coverage stays in
 * {@link SingleNodeOrderParityTest}.
 */
@Category(SequentialTest.class)
public class SingleNodeGremlinRidParityTest extends GraphBaseTest {

  private static final int LOW_HEAP_CAP = 2;

  /**
   * Composite index {@code (a, p)} with {@code ORDER BY a, p, @rid}: MATCH and Gremlin stream the
   * index; SELECT still sorts for an explicit {@code @rid} (non-goal).
   */
  @Test
  public void compositeAscWithRid_matchAndGremlinStreamUnderLowHeap() {
    seedComposite();
    var match = "MATCH {class: Comp, as: s} RETURN s ORDER BY s.a ASC, s.p ASC, s.@rid ASC";
    assertThat(plan(match))
        .contains("FETCH FROM INDEX")
        .doesNotContain("FETCH FROM CLASS Comp")
        .doesNotContain("PREFETCH")
        .contains("+ ORDER BY");
    var expected = oracleAsc("Comp", List.of("a", "p"), null);
    underLowCap(() -> {
      assertThat(matchRids(match)).isEqualTo(expected);
      assertThat(gremlinRids(
          graph.traversal().V().hasLabel("Comp").order().by("a", Order.asc).by("p", Order.asc)))
          .isEqualTo(expected);
    });
    assertThat(plan("SELECT FROM Comp ORDER BY a ASC, p ASC")).doesNotContain("+ ORDER BY");
    assertThat(plan("SELECT FROM Comp ORDER BY a ASC, p ASC, @rid ASC")).contains("+ ORDER BY");
  }

  /**
   * Descending composite with {@code IS NOT NULL} on both keys streams under the heap cap in a
   * clean transaction.
   */
  @Test
  public void compositeDescWithRid_presenceFilterStreamsUnderLowHeap() {
    seedComposite();
    var match = "MATCH {class: Comp, as: s, where: (a IS NOT NULL AND p IS NOT NULL)} RETURN s"
        + " ORDER BY s.a DESC, s.p DESC, s.@rid DESC";
    var expected = oracleDesc("Comp", List.of("a", "p"),
        row -> row.getProperty("a") != null && row.getProperty("p") != null);
    // MATCH WHERE uses IS NOT NULL, which proves RID order for DESC. Gremlin has(p) is IS DEFINED
    // and keeps the sort, so only MATCH is checked under the heap cap.
    underLowCap(() -> assertThat(matchRids(match)).isEqualTo(expected));
    assertThat(gremlinRids(graph.traversal().V().hasLabel("Comp")
        .has("a").has("p")
        .order().by("a", Order.desc).by("p", Order.desc)))
        .isEqualTo(expected);
  }

  /**
   * Range filter on the ordered key: SELECT elides sort; MATCH and Gremlin with {@code @rid} share
   * one RID sequence under the heap cap.
   */
  @Test
  public void rangeAscWithRid_matchSelectAndGremlinAgreeUnderLowHeap() {
    seedScores();
    var select = "SELECT FROM Scored WHERE score >= 2 AND score <= 4 ORDER BY score ASC";
    var match = "MATCH {class: Scored, as: s, where: (score >= 2 AND score <= 4)} RETURN s"
        + " ORDER BY s.score ASC, s.@rid ASC";
    assertThat(plan(select)).contains("FETCH FROM INDEX").doesNotContain("+ ORDER BY");
    assertThat(plan(match)).contains("FETCH FROM INDEX").contains("+ ORDER BY");
    var expected = oracleAsc("Scored", List.of("score"), row -> {
      var score = (Integer) row.getProperty("score");
      return score != null && score >= 2 && score <= 4;
    });
    underLowCap(() -> {
      assertThat(matchRids(match)).isEqualTo(expected);
      assertThat(gremlinRids(graph.traversal().V().hasLabel("Scored")
          .has("score", P.gte(2).and(P.lte(4)))
          .order().by("score", Order.asc)))
          .isEqualTo(expected);
    });
  }

  /**
   * Equality prefix on a composite index leaves the second key ordered: MATCH and Gremlin pass
   * through RID ties under the heap cap.
   */
  @Test
  public void equalityPrefixWithRid_matchAndGremlinStreamUnderLowHeap() {
    seedComposite();
    var match = "MATCH {class: Comp, as: s, where: (a = 1)} RETURN s"
        + " ORDER BY s.p ASC, s.@rid ASC";
    assertThat(plan(match)).contains("FETCH FROM INDEX").contains("+ ORDER BY");
    var expected = oracleAsc("Comp", List.of("p"),
        row -> Integer.valueOf(1).equals(row.getProperty("a")));
    underLowCap(() -> {
      assertThat(matchRids(match)).isEqualTo(expected);
      assertThat(gremlinRids(graph.traversal().V().hasLabel("Comp").has("a", 1)
          .order().by("p", Order.asc)))
          .isEqualTo(expected);
    });
  }

  /**
   * Residual non-indexed filter keeps the order-only index and streams with {@code @rid} under the
   * heap cap.
   */
  @Test
  public void residualFilterWithRid_usesOrderIndexAndStreamsUnderLowHeap() {
    seedScoresWithTag();
    var match = "MATCH {class: Tagged, as: s, where: (tag = 'keep')} RETURN s"
        + " ORDER BY s.score ASC, s.@rid ASC";
    assertThat(plan(match))
        .contains("FETCH FROM INDEX")
        .contains("Tagged_score")
        .doesNotContain("FETCH FROM CLASS Tagged");
    var expected = oracleAsc("Tagged", List.of("score"),
        row -> "keep".equals(row.getProperty("tag")));
    underLowCap(() -> assertThat(matchRids(match)).isEqualTo(expected));
  }

  /**
   * IN-list on the leading composite key cannot promise order: MATCH keeps a sort.
   */
  @Test
  public void inListPrefixKeepsMatchSortForPropertyAndRidOrder() {
    seedComposite();
    var match = "MATCH {class: Comp, as: s, where: (a IN [1, 2])} RETURN s"
        + " ORDER BY s.p ASC, s.@rid ASC";
    assertThat(plan(match)).contains("+ ORDER BY");
    var expected = oracleAsc("Comp", List.of("p"), row -> {
      var a = (Integer) row.getProperty("a");
      return a != null && (a == 1 || a == 2);
    });
    assertThat(matchRids(match)).isEqualTo(expected);
  }

  /**
   * Gremlin ASC and DESC RID sequences on a single indexed integer key are exact reverses when
   * every score is present.
   */
  @Test
  public void gremlinSingleKeyRidSequencesAreExactReversesWhenAllKeysPresent() {
    seedScoresNoNull();
    var asc = gremlinRids(graph.traversal().V().hasLabel("Scored").order().by("score", Order.asc));
    var desc = gremlinRids(
        graph.traversal().V().hasLabel("Scored").order().by("score", Order.desc));
    assertThat(asc).isEqualTo(oracleAsc("Scored", List.of("score"), null));
    assertThat(desc).isEqualTo(new ArrayList<>(asc).reversed());
  }

  /**
   * Descending range with both bounds excludes nulls, so MATCH and Gremlin stream under the heap
   * cap.
   */
  @Test
  public void rangeDescWithRid_streamsUnderLowHeap() {
    seedScores();
    var match = "MATCH {class: Scored, as: s, where: (score >= 2 AND score <= 4)} RETURN s"
        + " ORDER BY s.score DESC, s.@rid DESC";
    var expected = oracleDesc("Scored", List.of("score"), row -> {
      var score = (Integer) row.getProperty("score");
      return score != null && score >= 2 && score <= 4;
    });
    underLowCap(() -> {
      assertThat(matchRids(match)).isEqualTo(expected);
      assertThat(gremlinRids(graph.traversal().V().hasLabel("Scored")
          .has("score", P.gte(2).and(P.lte(4)))
          .order().by("score", Order.desc)))
          .isEqualTo(expected);
    });
  }

  private void seedComposite() {
    session.execute("CREATE CLASS Comp EXTENDS V").close();
    session.execute("CREATE PROPERTY Comp.a INTEGER").close();
    session.execute("CREATE PROPERTY Comp.p INTEGER").close();
    session.execute("CREATE INDEX Comp_ap ON Comp (a, p) NOTUNIQUE").close();
    for (var a = 1; a <= 2; a++) {
      for (var p = 1; p <= 3; p++) {
        graph.addVertex(T.label, "Comp", "a", a, "p", p);
        graph.addVertex(T.label, "Comp", "a", a, "p", p);
      }
    }
    graph.addVertex(T.label, "Comp", "a", 1);
    graph.tx().commit();
  }

  private void seedScores() {
    session.execute("CREATE CLASS Scored EXTENDS V").close();
    session.execute("CREATE PROPERTY Scored.score INTEGER").close();
    session.execute("CREATE INDEX Scored_score ON Scored (score) NOTUNIQUE").close();
    for (var score : List.of(1, 1, 2, 3, 4, 4)) {
      graph.addVertex(T.label, "Scored", "score", score);
    }
    graph.addVertex(T.label, "Scored");
    graph.tx().commit();
  }

  private void seedScoresNoNull() {
    session.execute("CREATE CLASS Scored EXTENDS V").close();
    session.execute("CREATE PROPERTY Scored.score INTEGER").close();
    session.execute("CREATE INDEX Scored_score ON Scored (score) NOTUNIQUE").close();
    for (var score : List.of(1, 1, 2, 3, 4, 4)) {
      graph.addVertex(T.label, "Scored", "score", score);
    }
    graph.tx().commit();
  }

  private void seedScoresWithTag() {
    session.execute("CREATE CLASS Tagged EXTENDS V").close();
    session.execute("CREATE PROPERTY Tagged.score INTEGER").close();
    session.execute("CREATE PROPERTY Tagged.tag STRING").close();
    session.execute("CREATE INDEX Tagged_score ON Tagged (score) NOTUNIQUE").close();
    graph.addVertex(T.label, "Tagged", "score", 1, "tag", "keep");
    graph.addVertex(T.label, "Tagged", "score", 2, "tag", "drop");
    graph.addVertex(T.label, "Tagged", "score", 2, "tag", "keep");
    graph.addVertex(T.label, "Tagged", "score", 3, "tag", "keep");
    graph.tx().commit();
  }

  private String plan(String query) {
    try (var rs = session.query("EXPLAIN " + query)) {
      return String.valueOf((Object) rs.next().getProperty("executionPlanAsString"));
    }
  }

  private List<String> matchRids(String query) {
    var rows = new ArrayList<String>();
    try (var rs = session.query(query)) {
      rs.forEachRemaining(row -> rows.add(row.getVertex("s").getIdentity().toString()));
    }
    return rows;
  }

  private List<String> gremlinRids(GraphTraversal<Vertex, Vertex> traversal) {
    traversal.asAdmin().applyStrategies();
    assertThat(traversal.asAdmin().getStartStep()).isInstanceOf(YTDBMatchPlanStep.class);
    return traversal.toList().stream().map(v -> v.id().toString()).toList();
  }

  private interface RowFilter {
    boolean keep(Result row);
  }

  private List<String> oracleAsc(String label, List<String> keys, @Nullable RowFilter filter) {
    return oracle(label, keys, false, filter);
  }

  private List<String> oracleDesc(String label, List<String> keys, @Nullable RowFilter filter) {
    return oracle(label, keys, true, filter);
  }

  private List<String> oracle(String label, List<String> keys, boolean desc,
      @Nullable RowFilter filter) {
    // Class scan without ORDER BY / index so the oracle cannot hide index defects.
    assertThat(plan("SELECT FROM " + label)).contains("FETCH FROM CLASS")
        .doesNotContain("FETCH FROM INDEX");
    var rows = new ArrayList<Result>();
    try (var rs = session.query("SELECT FROM " + label)) {
      rs.forEachRemaining(rows::add);
    }
    rows.removeIf(row -> filter != null && !filter.keep(row));
    rows.sort((left, right) -> {
      for (var key : keys) {
        var compared = compareNullable(left.getProperty(key), right.getProperty(key));
        if (compared != 0) {
          return desc ? -compared : compared;
        }
      }
      var compared = left.getIdentity().compareTo(right.getIdentity());
      return desc ? -compared : compared;
    });
    return rows.stream().map(row -> row.getIdentity().toString()).toList();
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static int compareNullable(Object left, Object right) {
    if (left == null && right == null) {
      return 0;
    }
    if (left == null) {
      return -1;
    }
    if (right == null) {
      return 1;
    }
    return ((Comparable) left).compareTo(right);
  }

  private void underLowCap(Runnable check) {
    var previous = GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.getValueAsInteger();
    GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.setValue(LOW_HEAP_CAP);
    try {
      check.run();
    } finally {
      GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.setValue(previous);
    }
  }
}
