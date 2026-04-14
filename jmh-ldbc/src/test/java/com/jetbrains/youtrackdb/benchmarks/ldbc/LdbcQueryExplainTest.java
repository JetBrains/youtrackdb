package com.jetbrains.youtrackdb.benchmarks.ldbc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Verifies that EXPLAIN plans for LDBC SNB queries reflect the predicate
 * push-down and adjacency list intersection optimizations introduced in
 * YTDB-603.
 *
 * <p>Uses the same LDBC schema ({@code ldbc-schema.sql}) and a minimal
 * test graph so the optimizer's plan-time decisions (class resolution,
 * index lookup, back-reference detection) are exercised against real
 * schema metadata.
 *
 * <p>Queries tested:
 * <ul>
 *   <li><b>IC5</b>  — back-reference intersection on
 *       {@code creator.@rid = $matched.person.@rid}</li>
 *   <li><b>IC7</b>  — back-reference intersection (optional) on
 *       {@code knowsStart.@rid = $matched.startPerson.@rid}</li>
 *   <li><b>IS7</b>  — back-reference intersection (optional) on
 *       {@code knowsCheck.@rid = $matched.author.@rid}</li>
 *   <li><b>IC10</b> — class filter push-down ({@code @class = 'Post'})
 *       and RID filter on {@code expand(in('HAS_CREATOR'))}</li>
 *   <li><b>IC3</b>  — index pre-filter ({@code Message.creationDate})
 *       and RID filter on {@code expand(in('HAS_CREATOR'))}</li>
 * </ul>
 */
public class LdbcQueryExplainTest {

  private static final String DB_NAME = "ldbc_explain_test";

  private static YouTrackDB db;
  private static YTDBGraphTraversalSource g;
  private static Path dbPath;

  @BeforeClass
  public static void setupDatabase() throws Exception {
    dbPath = Files.createTempDirectory("ldbc-explain-");
    db = YourTracks.instance(dbPath.toString());
    db.create(DB_NAME, DatabaseType.MEMORY, "admin", "admin", "admin");
    g = db.openTraversal(DB_NAME, "admin", "admin");

    createSchema();
    loadMinimalData();
  }

  @AfterClass
  public static void tearDown() throws Exception {
    if (g != null) {
      g.close();
    }
    if (db != null) {
      db.drop(DB_NAME);
      db.close();
    }
    if (dbPath != null) {
      try (var files = Files.walk(dbPath)) {
        files.sorted(Comparator.reverseOrder())
            .forEach(p -> {
              try {
                Files.deleteIfExists(p);
              } catch (IOException ignored) {
              }
            });
      }
    }
  }

  // ==================== MATCH back-reference intersection ====================

  /**
   * IC5: The reverse single-chain MATCH pattern traverses
   * Person -> HAS_CREATOR -> Post -> CONTAINER_OF -> Forum ->
   * outE(HAS_MEMBER) -> inV (back-ref to person).
   *
   * <p>The planner should detect the {@code .inV()} back-reference
   * ({@code @rid = $matched.person.@rid}), propagate the edge class
   * from the preceding {@code .outE('HAS_MEMBER')} step, and set an
   * intersection descriptor on the HAS_MEMBER edge traversal step.
   */
  @Test
  public void testIC5_backReferenceIntersection() {
    String plan = explain(LdbcQuerySql.IC5,
        "personId", 1L, "minDate", new Date(epochMillis(2010, 1, 1)),
        "limit", 10);
    assertTrue(
        "IC5 plan should show intersection optimization on the HAS_MEMBER "
            + "edge traversal step (back-ref from .inV()). Plan was:\n" + plan,
        plan.contains("intersection:"));
  }

  /**
   * IC7: The MATCH pattern checks whether a liker KNOWS the start person
   * via an optional edge: {@code .out('KNOWS'){where: (@rid = $matched.startPerson.@rid),
   * optional: true}}. The planner should detect the back-reference and
   * set an intersection descriptor on the KNOWS edge traversal step.
   */
  @Test
  public void testIC7_backReferenceIntersectionOptional() {
    String plan = explain(LdbcQuerySql.IC7, "personId", 1L, "limit", 10);
    assertTrue(
        "IC7 plan should show intersection optimization on the KNOWS "
            + "optional back-reference edge. Plan was:\n" + plan,
        plan.contains("intersection:"));
  }

  /**
   * IS7: The MATCH pattern checks whether a reply author KNOWS the original
   * message author via an optional edge: {@code .out('KNOWS'){where:
   * (@rid = $matched.author.@rid), optional: true}}. The planner should
   * detect the back-reference and set an intersection descriptor.
   */
  @Test
  public void testIS7_backReferenceIntersectionOptional() {
    String plan = explain(LdbcQuerySql.IS7, "messageId", 800L, "limit", 10);
    assertTrue(
        "IS7 plan should show intersection optimization on the KNOWS "
            + "optional back-reference edge. Plan was:\n" + plan,
        plan.contains("intersection:"));
  }

  // ==================== Index pre-filter on edge properties ====================

  /**
   * IC11: The MATCH pattern traverses Person -> KNOWS(depth<2) -> outE(WORK_AT)
   * -> inV() -> out(IS_LOCATED_IN). The planner should:
   * <ul>
   *   <li>Infer class WORK_AT on the workEdge alias (from outE('WORK_AT'))</li>
   *   <li>Infer class Organisation on the company alias (from WORK_AT.in LINK)</li>
   *   <li>Infer class Place on the country alias (from IS_LOCATED_IN.in LINK)</li>
   *   <li>Detect the WORK_AT.workFrom index for the {@code workFrom < :workFromYear}
   *       condition and attach an IndexLookup pre-filter</li>
   *   <li>Attach collection ID filters on company and country steps</li>
   * </ul>
   */
  @Test
  public void testIC11_indexPreFilterOnWorkAt() {
    String plan = explain(LdbcQuerySql.IC11,
        "personId", 1L, "countryName", "China",
        "workFromYear", 2010, "limit", 10);

    // Index pre-filter should detect WORK_AT.workFrom index on the workEdge step
    assertTrue(
        "IC11 plan should show index pre-filter on WORK_AT.workFrom. "
            + "Plan was:\n" + plan,
        plan.contains("index WORK_AT.workFrom"));

    // Index pre-filter should detect Place.name index on the country step
    assertTrue(
        "IC11 plan should show index pre-filter on Place.name for the "
            + "country filter. Plan was:\n" + plan,
        plan.contains("index Place.name"));
  }

  // ==================== expand() predicate push-down ====================

  /**
   * IC10: The LET subqueries use {@code expand(in('HAS_CREATOR'))} with
   * {@code WHERE @class = 'Post'}, which should produce a class filter
   * push-down in the EXPAND step (skipping Comment records with zero I/O).
   * The {@code @rid = $parent.$current.fofVertex} should produce a RID
   * filter push-down.
   */
  @Test
  public void testIC10_classFilterPushDown() {
    String plan = explain(LdbcQuerySql.IC10,
        "personId", 1L, "startMd", "0601", "endMd", "0801",
        "wrap", false, "limit", 10);
    assertTrue(
        "IC10 plan should show class filter push-down on EXPAND for "
            + "'Post' filtering. Plan was:\n" + plan,
        plan.contains("class filter"));
  }

  /**
   * IC3: The MATCH chain traverses {@code {person}.in('HAS_CREATOR'){message}}
   * with {@code WHERE creationDate >= :startDate AND creationDate < :endDate}.
   * Since {@code Message.creationDate} is indexed, the planner should use
   * adjacency list intersection with the index during the MATCH step.
   */
  @Test
  public void testIC3_indexIntersection() {
    String plan = explain(LdbcQuerySql.IC3,
        "personId", 1L,
        "startDate", new Date(epochMillis(2012, 6, 1)),
        "endDate", new Date(epochMillis(2012, 7, 1)),
        "countryX", "China", "countryY", "India", "limit", 10);
    assertTrue(
        "IC3 plan should show index intersection on HAS_CREATOR step for "
            + "Message.creationDate range. Plan was:\n" + plan,
        plan.contains("intersection: index Message.creationDate"));
  }

  // ==================== Helpers ====================

  /**
   * Runs EXPLAIN on the given query and returns the execution plan string.
   * Prepends "EXPLAIN " to the query SQL.
   */
  private String explain(String querySql, Object... keyValues) {
    // Prepend EXPLAIN to the outermost statement
    String explainSql = "EXPLAIN " + querySql;
    var results = g.computeInTx(t -> {
      var ytg = (YTDBGraphTraversalSource) t;
      return ytg.yql(explainSql, keyValues).toList().stream()
          .map(obj -> (Map<String, Object>) obj)
          .toList();
    });
    assertEquals("EXPLAIN should return exactly 1 result", 1, results.size());
    String plan = (String) results.get(0).get("executionPlanAsString");
    assertNotNull("EXPLAIN should produce executionPlanAsString", plan);
    return plan;
  }

  private static long epochMillis(int year, int month, int day) {
    return LocalDate.of(year, month, day).atStartOfDay()
        .toInstant(ZoneOffset.UTC).toEpochMilli();
  }

  private static void createSchema() {
    List<String> statements =
        LdbcBenchmarkState.loadSqlStatements("/ldbc-schema.sql");
    g.executeInTx(t -> {
      var ytg = (YTDBGraphTraversalSource) t;
      for (String stmt : statements) {
        ytg.yql(stmt).iterate();
      }
    });
  }

  /**
   * Loads a minimal graph sufficient for the optimizer to make plan-time
   * decisions. EXPLAIN only needs schema metadata and parameter types;
   * actual data volume is irrelevant for plan verification.
   */
  private static void loadMinimalData() {
    g.executeInTx(t -> {
      var ytg = (YTDBGraphTraversalSource) t;

      // ---- Places ----
      insertPlace(ytg, 100, "Springfield", "City");
      insertPlace(ytg, 200, "China", "Country");
      insertPlace(ytg, 201, "India", "Country");
      insertPlace(ytg, 300, "Asia", "Continent");
      ytg.yql(
          "CREATE EDGE IS_PART_OF FROM (SELECT FROM Place WHERE id = :from)"
              + " TO (SELECT FROM Place WHERE id = :to)",
          "from", 100L, "to", 200L).iterate();
      ytg.yql(
          "CREATE EDGE IS_PART_OF FROM (SELECT FROM Place WHERE id = :from)"
              + " TO (SELECT FROM Place WHERE id = :to)",
          "from", 200L, "to", 300L).iterate();
      ytg.yql(
          "CREATE EDGE IS_PART_OF FROM (SELECT FROM Place WHERE id = :from)"
              + " TO (SELECT FROM Place WHERE id = :to)",
          "from", 201L, "to", 300L).iterate();

      // ---- Tags & TagClasses ----
      ytg.yql(
          "INSERT INTO TagClass SET id = :id, name = :name, url = :url",
          "id", 500L, "name", "MusicalArtist",
          "url", "http://dbpedia.org/MusicalArtist").iterate();
      ytg.yql(
          "INSERT INTO Tag SET id = :id, name = :name, url = :url",
          "id", 400L, "name", "Java",
          "url", "http://dbpedia.org/Java").iterate();
      ytg.yql(
          "CREATE EDGE HAS_TYPE FROM (SELECT FROM Tag WHERE id = :from)"
              + " TO (SELECT FROM TagClass WHERE id = :to)",
          "from", 400L, "to", 500L).iterate();

      // ---- Persons ----
      insertPerson(ytg, 1, "Alice", "Aaa", "female",
          epochMillis(1990, 6, 25), epochMillis(2010, 1, 1));
      insertPerson(ytg, 2, "Bob", "Bbb", "male",
          epochMillis(1991, 3, 15), epochMillis(2010, 2, 1));
      insertPerson(ytg, 3, "Carol", "Ccc", "female",
          epochMillis(1992, 1, 20), epochMillis(2010, 3, 1));

      createEdge(ytg, "IS_LOCATED_IN", "Person", 1, "Place", 100);
      createEdge(ytg, "IS_LOCATED_IN", "Person", 2, "Place", 100);
      createEdge(ytg, "IS_LOCATED_IN", "Person", 3, "Place", 100);

      createEdge(ytg, "HAS_INTEREST", "Person", 1, "Tag", 400);

      // ---- KNOWS (bidirectional) ----
      createKnows(ytg, 1, 2, epochMillis(2011, 1, 1));
      createKnows(ytg, 2, 3, epochMillis(2011, 6, 1));

      // ---- Forum ----
      ytg.yql(
          "INSERT INTO Forum SET id = :id, title = :title, creationDate = :cd",
          "id", 700L, "title", "Wall of Alice",
          "cd", epochMillis(2010, 6, 1)).iterate();
      createEdge(ytg, "HAS_MODERATOR", "Forum", 700, "Person", 1);
      ytg.yql(
          "CREATE EDGE HAS_MEMBER FROM (SELECT FROM Forum WHERE id = :from)"
              + " TO (SELECT FROM Person WHERE id = :to) SET joinDate = :jd",
          "from", 700L, "to", 2L, "jd", epochMillis(2012, 1, 1)).iterate();

      // ---- Posts ----
      insertPost(ytg, 800, "Hello world", epochMillis(2012, 6, 1));
      insertPost(ytg, 801, "Goodbye world", epochMillis(2012, 6, 15));
      createEdge(ytg, "HAS_CREATOR", "Post", 800, "Person", 1);
      createEdge(ytg, "HAS_CREATOR", "Post", 801, "Person", 2);
      createEdge(ytg, "IS_LOCATED_IN", "Post", 800, "Place", 200);
      createEdge(ytg, "IS_LOCATED_IN", "Post", 801, "Place", 201);
      createEdge(ytg, "HAS_TAG", "Post", 800, "Tag", 400);
      createEdge(ytg, "CONTAINER_OF", "Forum", 700, "Post", 800);
      createEdge(ytg, "CONTAINER_OF", "Forum", 700, "Post", 801);

      // ---- Comments ----
      insertComment(ytg, 900, "Nice post", epochMillis(2012, 7, 1));
      createEdge(ytg, "HAS_CREATOR", "Comment", 900, "Person", 2);
      createEdge(ytg, "IS_LOCATED_IN", "Comment", 900, "Place", 200);
      createEdge(ytg, "REPLY_OF", "Comment", 900, "Post", 800);

      // ---- Companies (for IC11) ----
      ytg.yql(
          "INSERT INTO Company SET id = :id, name = :name, url = :url, type = :type",
          "id", 1000L, "name", "Acme Corp", "url", "http://acme.com",
          "type", "company").iterate();
      ytg.yql(
          "INSERT INTO Company SET id = :id, name = :name, url = :url, type = :type",
          "id", 1001L, "name", "Globex Inc", "url", "http://globex.com",
          "type", "company").iterate();
      // Companies located in countries
      createEdge(ytg, "IS_LOCATED_IN", "Company", 1000, "Place", 200); // China
      createEdge(ytg, "IS_LOCATED_IN", "Company", 1001, "Place", 201); // India

      // ---- WORK_AT edges (for IC11) ----
      ytg.yql(
          "CREATE EDGE WORK_AT FROM (SELECT FROM Person WHERE id = :from)"
              + " TO (SELECT FROM Company WHERE id = :to) SET workFrom = :wf",
          "from", 2L, "to", 1000L, "wf", 2008).iterate();
      ytg.yql(
          "CREATE EDGE WORK_AT FROM (SELECT FROM Person WHERE id = :from)"
              + " TO (SELECT FROM Company WHERE id = :to) SET workFrom = :wf",
          "from", 3L, "to", 1001L, "wf", 2012).iterate();

      // ---- LIKES ----
      ytg.yql(
          "CREATE EDGE LIKES FROM (SELECT FROM Person WHERE id = :from)"
              + " TO (SELECT FROM Post WHERE id = :to) SET creationDate = :cd",
          "from", 2L, "to", 800L, "cd", epochMillis(2012, 8, 1)).iterate();
    });
  }

  private static void insertPlace(YTDBGraphTraversalSource ytg,
      long id, String name, String type) {
    ytg.yql(
        "INSERT INTO Place SET id = :id, name = :name, url = :url, type = :type",
        "id", id, "name", name, "url", "http://dbpedia.org/" + name,
        "type", type).iterate();
  }

  private static void insertPerson(YTDBGraphTraversalSource ytg,
      long id, String firstName, String lastName, String gender,
      long birthday, long creationDate) {
    ytg.yql(
        "INSERT INTO Person SET id = :id, firstName = :fn, lastName = :ln,"
            + " gender = :g, birthday = :bd, creationDate = :cd,"
            + " locationIP = :ip, browserUsed = :br,"
            + " languages = :lang, emails = :emails",
        "id", id, "fn", firstName, "ln", lastName, "g", gender,
        "bd", birthday, "cd", creationDate,
        "ip", "1.1.1.1", "br", "Chrome",
        "lang", List.of("en"), "emails", List.of(firstName.toLowerCase() + "@test.com"))
        .iterate();
  }

  private static void insertPost(YTDBGraphTraversalSource ytg,
      long id, String content, long creationDate) {
    ytg.yql(
        "INSERT INTO Post SET id = :id, content = :content,"
            + " creationDate = :cd, locationIP = :ip, browserUsed = :br,"
            + " language = :lang, length = :len",
        "id", id, "content", content,
        "cd", creationDate, "ip", "1.1.1.1", "br", "Chrome",
        "lang", "en", "len", content.length()).iterate();
  }

  private static void insertComment(YTDBGraphTraversalSource ytg,
      long id, String content, long creationDate) {
    ytg.yql(
        "INSERT INTO Comment SET id = :id, content = :content,"
            + " creationDate = :cd, locationIP = :ip, browserUsed = :br,"
            + " length = :len",
        "id", id, "content", content,
        "cd", creationDate, "ip", "1.1.1.1", "br", "Chrome",
        "len", content.length()).iterate();
  }

  private static void createEdge(YTDBGraphTraversalSource ytg,
      String edgeLabel, String fromClass, long fromId,
      String toClass, long toId) {
    ytg.yql(
        "CREATE EDGE " + edgeLabel
            + " FROM (SELECT FROM " + fromClass + " WHERE id = :from)"
            + " TO (SELECT FROM " + toClass + " WHERE id = :to)",
        "from", fromId, "to", toId).iterate();
  }

  private static void createKnows(YTDBGraphTraversalSource ytg,
      long person1, long person2, long creationDate) {
    String sql = "CREATE EDGE KNOWS"
        + " FROM (SELECT FROM Person WHERE id = :from)"
        + " TO (SELECT FROM Person WHERE id = :to)"
        + " SET creationDate = :cd";
    ytg.yql(sql, "from", person1, "to", person2, "cd", creationDate).iterate();
    ytg.yql(sql, "from", person2, "to", person1, "cd", creationDate).iterate();
  }
}
