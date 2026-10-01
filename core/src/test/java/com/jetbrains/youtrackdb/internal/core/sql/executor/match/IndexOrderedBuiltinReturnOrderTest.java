package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Edge MATCH with IndexOrdered + multi-field {@code ORDER BY} must still apply the composite sort
 * when the return mode is a built-in ({@code $paths} / {@code $patterns}). Clearing only the
 * primary-key early-stop hint while leaving {@code indexOrderedUpstream} true made
 * {@code OrderByStep} pass rows through unsorted.
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
