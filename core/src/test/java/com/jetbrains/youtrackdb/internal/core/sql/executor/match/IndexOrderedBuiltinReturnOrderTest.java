package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.sql.executor.EmptyStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.OrderByStep;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Edge MATCH must apply the complete ORDER BY before slicing when the index provides only
 * primary-key order. These tests cover both built-in and custom return projections.
 */
public class IndexOrderedBuiltinReturnOrderTest extends GraphBaseTest {

  private void seedDuplicateScores() {
    session.execute("CREATE CLASS PathSrc EXTENDS V").close();
    session.execute("CREATE CLASS PathTgt EXTENDS V").close();
    session.execute("CREATE PROPERTY PathTgt.score INTEGER").close();
    session.execute("CREATE PROPERTY PathTgt.name STRING").close();
    session.execute("CREATE INDEX PathTgt_score ON PathTgt (score) NOTUNIQUE").close();
    session.execute("CREATE CLASS PathLink EXTENDS E").close();
    session.begin();
    session.execute("CREATE VERTEX PathSrc SET id = 1").close();
    // Same primary key, secondary name must decide order: a before b before c within score 1,
    // then score 2.
    session.execute("CREATE VERTEX PathTgt SET score = 1, name = 'b'").close();
    session.execute("CREATE VERTEX PathTgt SET score = 1, name = 'a'").close();
    session.execute("CREATE VERTEX PathTgt SET score = 2, name = 'c'").close();
    session.execute("CREATE VERTEX PathTgt SET score = 1, name = 'c'").close();
    session.execute(
        "CREATE EDGE PathLink FROM (SELECT FROM PathSrc) TO (SELECT FROM PathTgt)")
        .close();
    session.commit();
  }

  /** DISTINCT uses the full order and applies SKIP and LIMIT to distinct rows. */
  @Test
  @Category(SequentialTest.class)
  public void distinctPartialOrderSortsByRankBeforeLimit() {
    session.execute("CREATE CLASS PathSrc EXTENDS V").close();
    session.execute("CREATE CLASS PathTgt EXTENDS V").close();
    session.execute("CREATE PROPERTY PathTgt.score INTEGER").close();
    session.execute("CREATE INDEX PathTgt_score ON PathTgt (score) NOTUNIQUE").close();
    session.execute("CREATE CLASS PathLink EXTENDS E").close();
    session.begin();
    session.execute("CREATE VERTEX PathSrc").close();
    session.execute("CREATE VERTEX PathTgt SET score = 0").close();
    session.execute("CREATE VERTEX PathTgt SET score = 0").close();
    session.execute("CREATE EDGE PathLink FROM (SELECT FROM PathSrc) TO (SELECT FROM PathTgt)")
        .close();
    session.commit();

    // Assign ranks in cursor order, independent of the order in which RIDs were allocated.
    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex("PathTgt_score");
    String firstTarget;
    try (var entries = index.stream(session)) {
      var targets = entries.toList();
      assertThat(targets).hasSize(2);
      firstTarget = targets.getFirst().second().toString();
      session.execute("UPDATE " + firstTarget + " SET rank = 9").close();
      session.execute("UPDATE " + targets.getLast().second() + " SET rank = 1").close();
    }
    session.commit();

    session.begin();
    var match = "MATCH {class: PathSrc, as: s}.out('PathLink'){class: PathTgt, as: m}";
    for (var builtin : List.of(true, false)) {
      var projection = builtin ? "$paths" : "m.score AS score, m.rank AS rank";
      var base = match + " RETURN DISTINCT " + projection + " ORDER BY m.score ASC";
      // The primary key alone retains the index order. The secondary key reverses the tie.
      assertThat(firstPair(base + " LIMIT 1", builtin)).containsExactly(0, 9);
      assertThat(firstPair(base + ", m.rank ASC LIMIT 1", builtin)).containsExactly(0, 1);
    }

    // A parallel link puts two copies of (0, 9) ahead of (0, 1) in the requested order.
    session.execute("CREATE EDGE PathLink FROM (SELECT FROM PathSrc) TO " + firstTarget).close();
    // More distinct inputs than the cap must not grow an all-seen set.
    session.execute("CREATE VERTEX PathTgt SET score = 1, rank = 8").close();
    session.execute("CREATE VERTEX PathTgt SET score = 1, rank = 7").close();
    session.execute("CREATE EDGE PathLink FROM (SELECT FROM PathSrc)"
        + " TO (SELECT FROM PathTgt WHERE score = 1)").close();
    session.commit();

    var unindexedMatch = seedUnindexedRanks();
    for (var indexed : List.of(true, false)) {
      var pattern = indexed ? match : unindexedMatch;
      var projected = pattern + " RETURN DISTINCT m.score AS score, m.rank AS rank";
      var raw = projected + " ORDER BY m.score ASC, m.rank DESC";
      assertThat(pairs(raw + " LIMIT 2", Map.of(), indexed))
          .containsExactly(List.of(0, 9), List.of(0, 1));
      assertThat(pairs(raw + " SKIP 1 LIMIT 1", Map.of(), indexed))
          .containsExactly(List.of(0, 1));
      withHeapCap(1, () -> assertThat(pairs(projected + " ORDER BY m.score, m.rank LIMIT 1",
          Map.of(), indexed)).containsExactly(List.of(0, 1)));
      withHeapCap(2, () -> assertThatThrownBy(() -> pairs(raw + " LIMIT 2", Map.of(), indexed))
          .isInstanceOf(CommandExecutionException.class).hasMessageContaining("in-heap ORDER BY"));
      var query = projected + " ORDER BY score ASC, rank DESC SKIP :offset LIMIT :count";
      withHeapCap(2, () -> {
        assertThat(pairs(query, Map.of("offset", 0, "count", 2), indexed))
            .containsExactly(List.of(0, 9), List.of(0, 1));
        assertThat(session.getSharedContext().getYqlExecutionPlanCache().contains(query)).isTrue();
        assertThat(pairs(query, Map.of("offset", 1, "count", 1), indexed))
            .containsExactly(List.of(0, 1));
        assertThat(pairs(query, Map.of("offset", 0, "count", 1), indexed))
            .containsExactly(List.of(0, 9));
        assertThatThrownBy(() -> pairs(query, Map.of("offset", 2, "count", 1), indexed))
            .isInstanceOf(CommandExecutionException.class).hasMessageContaining("in-heap ORDER BY");
      });
      assertThat(pairs(query, Map.of("offset", 0, "count", 0), indexed)).isEmpty();
      assertThat(pairs(query, Map.of("offset", 0, "count", -1), indexed))
          .containsExactly(List.of(0, 9), List.of(0, 1), List.of(1, 8), List.of(1, 7));
    }

    // ORDER BY uses both a visible alias and a synthetic column for the hidden rank.
    // The synthetic rank must not distinguish two rows with the same visible score.
    withHeapCap(2, () -> {
      var hidden = match + " RETURN DISTINCT m.score AS score, 'same' AS grouping"
          + " ORDER BY grouping, m.rank DESC LIMIT 2";
      try (var rows = session.query(hidden)) {
        var scores = new ArrayList<Integer>();
        rows.forEachRemaining(row -> {
          assertThat(row.getPropertyNames()).containsExactlyInAnyOrder("score", "grouping");
          scores.add(row.getProperty("score"));
        });
        assertThat(scores).containsExactly(0, 1);
      }
    });
  }

  @Test
  @Category(SequentialTest.class)
  public void hiddenRankSelectsBestDistinctNamesBeforePositionalSlice() {
    seedDuplicateScores();
    session.begin();
    session.execute("UPDATE PathTgt SET rank = 1 WHERE name = 'c' AND score = 1").close();
    session.execute("UPDATE PathTgt SET rank = 2 WHERE name = 'c' AND score = 2").close();
    session.execute("UPDATE PathTgt SET rank = 3 WHERE name = 'a'").close();
    session.execute("UPDATE PathTgt SET rank = 4 WHERE name = 'b'").close();
    session.commit();
    var match = "MATCH {class: PathSrc, as: s}.out('PathLink'){class: PathTgt, as: m}";
    var suffix = " RETURN DISTINCT m.name AS person ORDER BY m.rank, person SKIP ? LIMIT ?";
    var query = match + suffix;
    withHeapCap(2, () -> {
      try (var rows = session.query(query, 0, 2)) {
        assertThat(rows.getExecutionPlan().prettyPrint(0, 2)).contains("distinct buffer size: 2");
        assertThat(rows.toList()).extracting(row -> row.<String>getProperty("person"))
            .containsExactly("c", "a");
      }
      assertThat(session.getSharedContext().getYqlExecutionPlanCache().contains(query)).isTrue();
      try (var rows = session.query(query, 1, 1)) {
        assertThat(rows.toList()).extracting(row -> row.<String>getProperty("person"))
            .containsExactly("a");
      }
      // The comparator needs this alias before sorting, but the hidden-column rebuild
      // would convert an arbitrary value. A schemaless field supplies no scalar proof.
      var unknown = match + " RETURN DISTINCT m.rank AS place ORDER BY m.name, place LIMIT 2";
      assertThatThrownBy(() -> session.query(unknown).close())
          .isInstanceOf(CommandExecutionException.class).hasMessageContaining("in-heap ORDER BY");
      // The requested bound exceeds the cap, but the two matching rows share one key.
      var small = match.replace("as: m", "as: m, where: (name = 'c')") + suffix;
      try (var rows = session.query(small, 0, 100)) {
        assertThat(rows.toList()).extracting(row -> row.<String>getProperty("person"))
            .containsExactly("c");
      }
    });
  }

  private String seedUnindexedRanks() {
    session.execute("CREATE CLASS UnindexedTgt EXTENDS V").close();
    session.execute("CREATE CLASS UnindexedLink EXTENDS E").close();
    session.begin();
    session.execute("CREATE VERTEX UnindexedTgt SET score = 0, rank = 9").close();
    session.execute("CREATE VERTEX UnindexedTgt SET score = 0, rank = 1").close();
    session.execute("CREATE VERTEX UnindexedTgt SET score = 1, rank = 8").close();
    session.execute("CREATE VERTEX UnindexedTgt SET score = 1, rank = 7").close();
    session.execute("CREATE EDGE UnindexedLink FROM (SELECT FROM PathSrc)"
        + " TO (SELECT FROM UnindexedTgt)").close();
    session.execute("CREATE EDGE UnindexedLink FROM (SELECT FROM PathSrc)"
        + " TO (SELECT FROM UnindexedTgt WHERE rank = 9)").close();
    session.commit();
    return "MATCH {class: PathSrc, as: s}.out('UnindexedLink'){class: UnindexedTgt, as: m}";
  }

  @Test
  public void distinctLimitDoesNotEvaluateLateProjectionOfLosingRow() {
    seedDuplicateScores();
    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex("PathTgt_score");
    try (var entries = index.stream(session)) {
      var targets = entries.toList();
      for (var i = 0; i < targets.size(); i++) {
        var first = i == 0;
        session.execute("UPDATE " + targets.get(i).second() + " SET rank = " + (first ? 9 : i)
            + ", value = '" + (first ? "not an integer" : i) + "'").close();
      }
    }
    session.commit();

    // The first raw match sorts after two valid rows. Its value is not needed by ORDER BY.
    var query = "MATCH {class: PathSrc, as: s}.out('PathLink'){class: PathTgt, as: m}"
        + " RETURN DISTINCT m.value.asInteger() AS converted ORDER BY m.score ASC, m.rank ASC";
    try (var rows = session.query(query)) {
      assertThat(rows.getExecutionPlan().prettyPrint(0, 2)).contains("INDEX ORDERED MATCH");
      assertThat(rows.next().<Integer>getProperty("converted")).isEqualTo(1);
      assertThat(rows.next().<Integer>getProperty("converted")).isEqualTo(2);
    }
    try (var rows = session.query(query + " LIMIT 2")) {
      assertThat(rows.toList()).extracting(row -> row.<Integer>getProperty("converted"))
          .containsExactly(1, 2);
    }
    try (var rows = session.query(query + " LIMIT 3")) {
      assertThat(rows.next().<Integer>getProperty("converted")).isEqualTo(1);
      assertThat(rows.next().<Integer>getProperty("converted")).isEqualTo(2);
      assertThatThrownBy(rows::hasNext).isInstanceOf(NumberFormatException.class);
    }
    try (var rows = session.query(query + " LIMIT 0")) {
      assertThat(rows.hasNext()).isFalse();
    }
    // SKIP probes DISTINCT on startup even for a zero limit.
    assertThatThrownBy(() -> session.query(query + " SKIP 2 LIMIT 0").close())
        .isInstanceOf(NumberFormatException.class);
  }

  @Test
  @Category(SequentialTest.class)
  public void aliasConversionInOrderByIsDemandedOnlyWhenRowsAreCompared() {
    session.execute("CREATE CLASS UnindexedValue EXTENDS V").close();
    session.begin();
    session.execute("CREATE VERTEX UnindexedValue SET value = 'bad'").close();

    var query = "MATCH {class: UnindexedValue, as: m}"
        + " RETURN DISTINCT m.value AS v ORDER BY v.asInteger() LIMIT :count";
    withHeapCap(1, () -> {
      for (var count : List.of(2, 1, -1)) {
        try (var rows = session.query(query, Map.of("count", count))) {
          var plan = rows.getExecutionPlan().prettyPrint(0, 2);
          assertThat(plan).contains("+ ORDER BY").doesNotContain("INDEX ORDERED MATCH",
              "distinct buffer size");
          assertThat(plan.contains("buffer size: 1")).isEqualTo(count == 1);
          assertThat(rows.toList()).extracting(row -> row.<String>getProperty("v"))
              .containsExactly("bad");
        }
      }
    });

    session.execute("CREATE VERTEX UnindexedValue SET value = '1'").close();
    session.commit();
    withHeapCap(2, () -> {
      for (var count : List.of(1, 2, -1)) {
        assertThatThrownBy(() -> session.query(query, Map.of("count", count)).close())
            .isInstanceOf(NumberFormatException.class);
      }
    });
  }

  @Test
  public void cachedCustomOrderRejectsNegativeSkipWithOrWithoutAnExplicitSortStep() {
    seedDuplicateScores();
    var cache = session.getSharedContext().getYqlExecutionPlanCache();
    for (var order : List.of("name", "m.score")) {
      var indexOrdered = order.equals("m.score");
      var projection = indexOrdered ? "m.score AS k" : "m.name AS name";
      var key = indexOrdered ? "k" : "name";
      for (var limit : List.of("", " LIMIT :count")) {
        // The finite DISTINCT sort is cacheable. Use plain RETURN for the other plans,
        // whose ordinary DISTINCT step does not opt into plan caching.
        var distinct = !indexOrdered && !limit.isEmpty() ? "DISTINCT " : "";
        // A range lookup supplies index order and is cacheable, unlike a full index scan.
        var filter = indexOrdered ? ", where: (score >= 1)" : "";
        var query = "MATCH {class: PathTgt, as: m" + filter + "} RETURN " + distinct + projection
            + " ORDER BY " + order + " SKIP :offset" + limit;
        assertThat(cache.contains(query)).isFalse();
        assertNegativeSkip(query, Map.of("offset", -1, "count", 1));
        assertThat(cache.contains(query)).isFalse();
        try (var rows = session.query(query, Map.of("offset", 0, "count", 2))) {
          var plan = rows.getExecutionPlan();
          assertThat(plan.getSteps().stream().anyMatch(OrderByStep.class::isInstance))
              .isEqualTo(!indexOrdered);
          if (indexOrdered) {
            assertThat(plan.prettyPrint(0, 2)).contains("FETCH FROM INDEX", "PathTgt_score");
          }
          assertThat(rows.next().<Object>getProperty(key)).isEqualTo(indexOrdered ? 1 : "a");
        }
        assertThat(cache.contains(query)).as("Cached plan for %s", query).isTrue();
        for (var count : List.of(1, 0, -1)) {
          assertNegativeSkip(query, Map.of("offset", -1, "count", count));
        }
        var offset = indexOrdered ? 3 : 1;
        try (var rows = session.query(query, Map.of("offset", offset, "count", -1))) {
          assertThat(rows.next().<Object>getProperty(key)).isEqualTo(indexOrdered ? 2 : "b");
        }
      }
    }
  }

  @Test
  public void emptyClassRejectsNegativeSkipOnCachedAndFreshCustomOrderedMatch() {
    seedDuplicateScores();
    var match = "MATCH {class: PathTgt, as: m, where: (score >= 1)}";
    var ordered = match + " RETURN m.score AS k ORDER BY m.score";
    var cached = ordered + " SKIP :offset";
    var fresh = ordered + " ASC SKIP :offset";
    var zero = Map.<String, Object>of("offset", 0);
    var negative = Map.<String, Object>of("offset", -1);
    var cache = session.getSharedContext().getYqlExecutionPlanCache();

    session.begin();
    try (var rows = session.query(cached, zero)) {
      assertThat(rows.toList()).hasSize(4);
    }
    assertThat(cache.contains(cached)).isTrue();
    session.execute("DELETE VERTEX FROM PathTgt").close();
    session.commit();

    assertThat(cache.contains(cached)).isTrue();
    try (var rows = session.query(cached, zero)) {
      // Fresh planning would return an EmptyStep instead of the cached index lookup.
      assertThat(rows.getExecutionPlan().prettyPrint(0, 2)).contains("FETCH FROM INDEX");
      assertThat(rows.toList()).isEmpty();
    }
    assertNegativeSkip(cached, negative);

    assertThat(cache.contains(fresh)).isFalse();
    try (var rows = session.query(fresh, zero)) {
      assertThat(rows.getExecutionPlan().getSteps()).hasSize(1);
      assertThat(rows.getExecutionPlan().getSteps().getFirst()).isInstanceOf(EmptyStep.class);
      assertThat(rows.toList()).isEmpty();
    }
    assertThat(cache.contains(fresh)).isFalse();
    assertNegativeSkip(fresh, negative);
    assertNegativeSkip(ordered + " SKIP -1", Map.of());

    // Cover both the direct class count and the count above EmptyStep.
    for (var countMatch : List.of("MATCH {class: PathTgt, as: m}", match)) {
      try (var rows = session.query(countMatch + " RETURN count(*) AS total")) {
        assertThat(rows.toList()).extracting(row -> row.<Long>getProperty("total"))
            .containsExactly(0L);
      }
    }
  }

  private void assertNegativeSkip(String query, Map<String, Object> args) {
    assertThatThrownBy(() -> session.query(query, args).close())
        .isInstanceOf(CommandExecutionException.class)
        .hasMessageContaining("Cannot execute a query with a negative SKIP");
  }

  @Test
  @Category(SequentialTest.class)
  public void zeroLimitClosesSortWithoutReadingOnlyWhenSkipIsAbsent() {
    session.execute("CREATE CLASS UnindexedScore EXTENDS V").close();
    session.begin();
    session.execute("CREATE VERTEX UnindexedScore SET score = 9").close();
    session.execute("CREATE VERTEX UnindexedScore SET score = 1").close();
    session.commit();
    var cache = session.getSharedContext().getYqlExecutionPlanCache();
    for (var order : List.of("m.score", "score")) {
      var base = "MATCH {class: UnindexedScore, as: m} RETURN DISTINCT m.score AS score"
          + " ORDER BY " + order;
      var withoutSkip = base + " LIMIT :count";
      var explicitZero = base + " SKIP 0 LIMIT :count";
      var boundZero = base + " SKIP :offset LIMIT :count";
      withHeapCap(1, () -> {
        assertThat(unindexedScores(withoutSkip, Map.of("count", 0), 0)).isEmpty();
        assertThat(cache.contains(withoutSkip)).isTrue();
        assertThat(unindexedScores(withoutSkip, Map.of("count", 1), 1)).containsExactly(1);
        assertThat(unindexedScores(withoutSkip, Map.of("count", 0), 0)).isEmpty();
        assertThatThrownBy(() -> session.query(withoutSkip, Map.of("count", 2)).close())
            .isInstanceOf(CommandExecutionException.class).hasMessageContaining("in-heap ORDER BY");
        assertThatThrownBy(() -> session.query(explicitZero, Map.of("count", 0)).close())
            .isInstanceOf(CommandExecutionException.class).hasMessageContaining("in-heap ORDER BY");
        assertThat(unindexedScores(boundZero, Map.of("offset", 0, "count", 1), 1))
            .containsExactly(1);
        assertThat(cache.contains(boundZero)).isTrue();
        for (var offset : List.of(0, 1)) {
          assertThatThrownBy(
              () -> session.query(boundZero, Map.of("offset", offset, "count", 0)).close())
              .isInstanceOf(CommandExecutionException.class)
              .hasMessageContaining("in-heap ORDER BY");
        }
      });
      withHeapCap(2, () -> {
        assertThat(unindexedScores(explicitZero, Map.of("count", 0), null)).isEmpty();
        for (var offset : List.of(0, 1)) {
          assertThat(unindexedScores(boundZero, Map.of("offset", offset, "count", 0), null))
              .isEmpty();
        }
      });
    }
  }

  private List<Integer> unindexedScores(String query, Map<String, Object> args, Integer bound) {
    try (var rows = session.query(query, args)) {
      var plan = rows.getExecutionPlan().prettyPrint(0, 2);
      assertThat(plan).contains("+ ORDER BY").doesNotContain("INDEX ORDERED MATCH");
      if (bound == null) {
        assertThat(plan).doesNotContain("buffer size:");
      } else {
        assertThat(plan).contains("buffer size: " + bound);
      }
      return rows.toList().stream().map(row -> row.<Integer>getProperty("score")).toList();
    }
  }

  private List<List<Integer>> pairs(String query, Map<String, Object> args, boolean indexed) {
    try (var rows = session.query(query, args)) {
      var plan = rows.getExecutionPlan().prettyPrint(0, 2);
      assertThat(plan.contains("INDEX ORDERED MATCH")).as("index plan: %s", plan)
          .isEqualTo(indexed);
      var pairs = new ArrayList<List<Integer>>();
      rows.forEachRemaining(row -> {
        assertThat(row.getPropertyNames()).containsExactlyInAnyOrder("score", "rank");
        pairs.add(List.of(row.getProperty("score"), row.getProperty("rank")));
      });
      return pairs;
    }
  }

  private static void withHeapCap(int cap, Runnable action) {
    var setting = GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP;
    var prior = setting.getValue();
    setting.setValue(cap);
    try {
      action.run();
    } finally {
      setting.setValue(prior);
    }
  }

  private List<Integer> firstPair(String query, boolean builtin) {
    try (var rows = session.query(query)) {
      var plan = rows.getExecutionPlan().prettyPrint(0, 2);
      assertThat(plan).contains("INDEX ORDERED MATCH");
      var row = rows.next();
      assertThat(rows.hasNext()).isFalse();
      if (builtin) {
        var vertex = row.getVertex("m");
        return List.of(vertex.getProperty("score"), vertex.getProperty("rank"));
      }
      return List.of(row.getProperty("score"), row.getProperty("rank"));
    }
  }

  private List<String> namesFromAliasM(String query) {
    var names = new ArrayList<String>();
    try (var rs = session.query(query)) {
      rs.forEachRemaining(
          row -> names.add(String.valueOf((Object) row.getVertex("m").getProperty("name"))));
    }
    return names;
  }

  private String planText(String query) {
    try (var rs = session.query("EXPLAIN " + query)) {
      return String.valueOf((Object) rs.next().getProperty("executionPlanAsString"));
    }
  }

  /**
   * {@code RETURN $paths} with two sort keys: IndexOrdered covers the score index only. The
   * secondary {@code name} order must still come from {@code OrderByStep}, with and without LIMIT.
   */
  @Test
  public void returnPaths_multiFieldOrder_sortsBySecondaryKey() {
    seedDuplicateScores();
    var base = "MATCH {class: PathSrc, as: s}.out('PathLink'){class: PathTgt, as: m}"
        + " RETURN $paths ORDER BY m.score ASC, m.name ASC";
    assertThat(planText(base))
        .as("must still use IndexOrdered for the primary key:\n%s", planText(base))
        .contains("INDEX ORDERED MATCH");

    assertThat(namesFromAliasM(base)).containsExactly("a", "b", "c", "c");
    assertThat(namesFromAliasM(base + " LIMIT 2")).containsExactly("a", "b");
  }

  /**
   * Same shape with {@code RETURN $patterns}: the composite sort must not be elided into a
   * pass-through of the primary-key-only index stream.
   */
  @Test
  public void returnPatterns_multiFieldOrder_sortsBySecondaryKey() {
    seedDuplicateScores();
    var base = "MATCH {class: PathSrc, as: s}.out('PathLink'){class: PathTgt, as: m}"
        + " RETURN $patterns ORDER BY m.score ASC, m.name ASC";
    assertThat(planText(base)).contains("INDEX ORDERED MATCH");
    assertThat(namesFromAliasM(base)).containsExactly("a", "b", "c", "c");
    assertThat(namesFromAliasM(base + " LIMIT 3")).containsExactly("a", "b", "c");
  }

  /**
   * Custom RETURN control on the same fixture: two executions exercise the plan cache. The second
   * run must still copy {@code primaryKeySortedInput} after projection rewriting (rebind after
   * {@code optimizeQuery}).
   */
  @Test
  public void customReturn_multiFieldOrder_secondCachedExecutionKeepsOrder() {
    seedDuplicateScores();
    var query = "MATCH {class: PathSrc, as: s}.out('PathLink'){class: PathTgt, as: m}"
        + " RETURN m.name AS name, m.score AS score"
        + " ORDER BY m.score ASC, m.name ASC";
    List<String> first = new ArrayList<>();
    try (var rs = session.query(query)) {
      rs.forEachRemaining(row -> first.add(String.valueOf((Object) row.getProperty("name"))));
    }
    List<String> second = new ArrayList<>();
    try (var rs = session.query(query)) {
      rs.forEachRemaining(row -> second.add(String.valueOf((Object) row.getProperty("name"))));
    }
    assertThat(first).containsExactly("a", "b", "c", "c");
    assertThat(second).isEqualTo(first);
  }
}
