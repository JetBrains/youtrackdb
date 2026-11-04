package com.jetbrains.youtrackdb.benchmarks.ldbc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/** Independent expected answers distinguish membership, path duplicates, empty maps and fallbacks. */
public class PartialTraversalShapesTest {

  private static YouTrackDB db;
  private static YTDBGraphTraversalSource g;

  @BeforeClass
  public static void createGraph() {
    db = YourTracks.instance("memory:partial-shapes");
    db.create("partial", DatabaseType.MEMORY, "admin", "admin", "admin");
    g = db.openTraversal("partial", "admin", "admin");
    g.executeInTx(t -> {
      for (var sql : LdbcBenchmarkState.loadSqlStatements("/ldbc-schema.sql")) {
        t.yql(sql).iterate();
      }
    });
    g.executeInTx(t -> {
      for (long id = 90; id <= 96; id++) {
        t.yql("INSERT INTO Person SET id = :id", "id", id).iterate();
      }
      for (long id = 200; id <= 202; id++) {
        t.yql("INSERT INTO Forum SET id = :id, title = :title",
            "id", id, "title", "forum-" + id).iterate();
      }
      t.yql("INSERT INTO Organisation SET id = 500").iterate();
      t.yql("INSERT INTO Tag SET id = 600, name = 'rock'").iterate();
      t.yql("INSERT INTO Tag SET id = 601, name = 'jazz'").iterate();
      for (long id = 101; id <= 106; id++) {
        t.yql("INSERT INTO " + (id == 105 ? "Comment" : "Post")
            + " SET id = :id, content = :content, creationDate = :date",
            "id", id, "content", "text-" + id, "date", new Date(id)).iterate();
      }
      t.yql("UPDATE Message SET imageFile = 'image-105' WHERE id = 105").iterate();
      edge(t, "KNOWS", "Person", 90, "Person", 91);
      edge(t, "KNOWS", "Person", 90, "Person", 92);
      edge(t, "KNOWS", "Person", 91, "Person", 92);
      edge(t, "KNOWS", "Person", 92, "Person", 90);
      edge(t, "WORK_AT", "Person", 90, "Organisation", 500);
      for (long id = 101; id <= 106; id++) {
        long author = id == 102 ? 92 : id == 103 ? 93 : id == 106 ? 90 : 91;
        edge(t, "HAS_CREATOR", "Message", id, "Person", author);
      }
      for (long post : new long[] {101, 102, 103, 106}) {
        edge(t, "CONTAINER_OF", "Forum", 200, "Post", post);
      }
      edge(t, "CONTAINER_OF", "Forum", 201, "Post", 102);
      edge(t, "CONTAINER_OF", "Forum", 202, "Post", 104);
      edge(t, "HAS_MODERATOR", "Forum", 200, "Person", 91);
      // Distinct forums can share a moderator, whose result must retain both occurrences.
      edge(t, "HAS_MODERATOR", "Forum", 201, "Person", 91);
      edge(t, "HAS_TAG", "Post", 101, "Tag", 600);
      edge(t, "HAS_TAG", "Post", 102, "Tag", 600);
      edge(t, "HAS_TAG", "Post", 101, "Tag", 601);
      membership(t, 200, 91, 100);
      membership(t, 200, 91, 100);
      membership(t, 200, 92, 99);
      // Post 102 qualifies in both forums, so IC5 must deduplicate forum/post pairs.
      membership(t, 200, 92, 100);
      membership(t, 200, 93, 100);
      membership(t, 200, 90, 100);
      membership(t, 201, 92, 101);
      membership(t, 202, 91, 99);
      // Only a two-hop friendship reaches this author. These posts make forum 201 win by count,
      // opposite to ascending forum id, while post 102 still qualifies in both forums.
      edge(t, "KNOWS", "Person", 91, "Person", 94);
      for (long id = 107; id <= 108; id++) {
        t.yql("INSERT INTO Post SET id = :id, content = 'two-hop', creationDate = :d",
            "id", id, "d", new Date(id)).iterate();
        edge(t, "HAS_CREATOR", "Post", id, "Person", 94);
        edge(t, "CONTAINER_OF", "Forum", 201, "Post", id);
      }
      membership(t, 201, 94, 100);
      edge(t, "WORK_AT", "Person", 91, "Organisation", 500);
      // A separate component exercises the exact top twenty and the id tie-breaker.
      edge(t, "KNOWS", "Person", 96, "Person", 95);
      // Reverse insertion makes encounter order disagree with the ascending id tie-breaker.
      for (long id = 1021; id >= 1000; id--) {
        t.yql("INSERT INTO Post SET id = :id, content = :c, creationDate = :d",
            "id", id, "c", "tie-" + id, "d", new Date(100)).iterate();
        edge(t, "HAS_CREATOR", "Post", id, "Person", 95);
        t.yql("INSERT INTO Forum SET id = :id, title = :title",
            "id", id, "title", "tied-" + id).iterate();
        edge(t, "CONTAINER_OF", "Forum", id, "Post", id);
        membership(t, id, 95, 100);
      }
    });
  }

  @AfterClass
  public static void closeGraph() {
    if (g != null) {
      g.close();
    }
    if (db != null) {
      db.close();
    }
  }

  /** A date cutoff excludes newer messages. Image content wins when both properties exist. */
  @Test
  public void recentMessagesUseDateOrderAndCoalesce() {
    check(t -> PartialTraversalShapes.recentFriendMessages(t, 90, new Date(106)),
        List.of("image-105", "text-104", "text-102", "text-101"), true);
    check(t -> PartialTraversalShapes.recentFriendMessages(t, 90, new Date(102)),
        List.of("text-101"), true);
  }

  /** Twenty tied dates select the lowest ids. Removing limit or either ordering rule fails. */
  @Test
  public void recentMessagesFreezeTopTwentyAndIdTieBreaker() {
    var expected = java.util.stream.LongStream.range(1000, 1020)
        .mapToObj(id -> "tie-" + id).toList();
    check(t -> PartialTraversalShapes.recentFriendMessages(t, 96, new Date(101)),
        expected, true);
  }

  /**
   * Three distinct forums emit moderator 91 twice and unmoderated forum 202 once.
   * Repeated post paths do not repeat forums, but shared moderators retain their multiplicity.
   */
  @Test
  public void distinctForumsPreserveOptionalFallbackAndCount() {
    check(t -> PartialTraversalShapes.distinctForumsWithModerator(t, 90),
        List.of(91L, 91L, 202L), false);
    check(t -> PartialTraversalShapes.distinctForumCount(t, 90), List.of(3L), true);
    check(t -> PartialTraversalShapes.distinctForumCount(t, 93), List.of(0L), true);
  }

  /** Tag counts preserve multiplicity. Formatting an empty aggregate emits no map entries. */
  @Test
  public void tagCountsFormatTheCompleteMap() {
    check(t -> PartialTraversalShapes.tagCounts(t, 90),
        List.of(Map.of("tag", "rock", "postCount", 2L),
            Map.of("tag", "jazz", "postCount", 1L)),
        false);
    check(t -> PartialTraversalShapes.tagCounts(t, 93), List.of(), false);
  }

  /** The cycle closes before optional. A missing known endpoint produces no fallback row. */
  @Test
  public void cycleAndEndpointConsumeTheirPredicatesBeforeOptional() {
    check(t -> PartialTraversalShapes.mutualFriendCycle(t, 90), List.of(500L), false);
    check(t -> PartialTraversalShapes.mutualFriendCycle(t, 92), List.of(92L), false);
    check(t -> PartialTraversalShapes.mutualFriendCycle(t, 93), List.of(), false);
    check(t -> PartialTraversalShapes.knownEndVertex(t, 90, 91), List.of(500L), false);
    check(t -> PartialTraversalShapes.knownEndVertex(t, 90, 92), List.of(92L), false);
    check(t -> PartialTraversalShapes.knownEndVertex(t, 90, 93), List.of(), false);
  }

  /**
   * IC5 counts posts 102/107/108 in forum 201 before posts 101/102 in forum 200.
   * Descending count order must win over ascending forum id, with unequal counts of three and two.
   * It excludes old and wrong-person memberships, the start person and duplicate post paths.
   * The shared post counts once in each forum, including the inclusive membership boundary.
   */
  @Test
  public void fullIc5MatchesSqlIncludingInclusiveMembershipDate() {
    var expected = List.of(
        Map.of("forumId", 201L, "forumTitle", "forum-201", "postCount", 3L),
        Map.of("forumId", 200L, "forumTitle", "forum-200", "postCount", 2L));
    check(t -> PartialTraversalShapes.fullIc5(t, 90, new Date(100)), expected, true);
    check(t -> PartialTraversalShapes.fullIc5(t, 90, new Date(102)), List.of(), true);
    g.executeInTx(t -> {
      var rows = t.yql(LdbcQuerySql.IC5, "personId", 90L, "minDate", new Date(100),
          "limit", 20).toList();
      assertEquals(2, rows.size());
      for (int i = 0; i < rows.size(); i++) {
        var row = (Map<?, ?>) rows.get(i);
        assertEquals(expected.get(i).get("forumId"), row.get("forumId"));
        assertEquals(expected.get(i).get("forumTitle"), row.get("forumTitle"));
        assertEquals(expected.get(i).get("postCount"),
            ((Number) row.get("postCount")).longValue());
      }
    });
  }

  /** Equal post counts sort by forum id, not insertion order, and return only twenty forums. */
  @Test
  public void fullIc5LimitsTiedForumsById() {
    var expected = java.util.stream.LongStream.range(1000, 1020)
        .mapToObj(id -> Map.of("forumId", id, "forumTitle", "tied-" + id, "postCount", 1L))
        .toList();
    check(t -> PartialTraversalShapes.fullIc5(t, 96, new Date(100)), expected, true);
  }

  /** A deliberately wrong expectation must fail, so native fallback cannot make equality vacuous. */
  @Test
  public void resultOracleRejectsWrongAnswers() {
    assertThrows(AssertionError.class,
        () -> check(t -> PartialTraversalShapes.distinctForumCount(t, 90), List.of(99L), true));
  }

  private static void check(Function<YTDBGraphTraversalSource, Traversal<?, ?>> build,
      List<?> expected, boolean ordered) {
    var flag = GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED;
    boolean before = flag.getValueAsBoolean();
    try {
      for (boolean enabled : new boolean[] {false, true}) {
        flag.setValue(enabled);
        var actual = g.computeInTx(t -> {
          try (var traversal = build.apply(t)) {
            return traversal.toList();
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        });
        assertEquals("translator enabled=" + enabled,
            ordered ? expected : frequencies(expected), ordered ? actual : frequencies(actual));
      }
    } finally {
      flag.setValue(before);
    }
  }

  private static Map<?, Long> frequencies(List<?> rows) {
    return rows.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
  }

  private static void edge(YTDBGraphTraversalSource t, String label, String fromClass,
      long from, String toClass, long to) {
    t.yql("CREATE EDGE " + label + " FROM (SELECT FROM " + fromClass + " WHERE id = :a)"
        + " TO (SELECT FROM " + toClass + " WHERE id = :b)", "a", from, "b", to).iterate();
  }

  private static void membership(YTDBGraphTraversalSource t, long forum, long person, long date) {
    t.yql("CREATE EDGE HAS_MEMBER FROM (SELECT FROM Forum WHERE id = :f)"
        + " TO (SELECT FROM Person WHERE id = :p) SET joinDate = :d",
        "f", forum, "p", person, "d", new Date(date)).iterate();
  }
}
