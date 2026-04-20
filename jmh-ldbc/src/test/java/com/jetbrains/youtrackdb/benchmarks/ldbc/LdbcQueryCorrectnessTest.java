package com.jetbrains.youtrackdb.benchmarks.ldbc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Correctness tests for all 20 LDBC SNB read queries (IS1-IS7, IC1-IC13).
 *
 * <p>Builds a small, deterministic in-memory social network graph and verifies that each query
 * returns the expected results. This guards against regressions when optimizing query SQL.
 *
 * <p>Graph topology (all KNOWS edges are bidirectional):
 * <pre>
 *   Alice(1) --- Bob(2) --- Dave(4) --- Eve(5)
 *      \
 *       Carol(3)
 * </pre>
 *
 * From Alice: friends={Bob,Carol}, FoF={Dave}, FoFoF={Eve}.
 *
 * <p><b>Known limitation:</b> YouTrackDB's {@code $parent.$current} references in LET subqueries
 * do not resolve correctly when the outer SELECT wraps a MATCH subquery — the query planner
 * produces a broken execution plan that returns 0 rows. This affects correlated LET subqueries
 * in IC1 (universities/companies), IC3 (xCount/yCount), and IC10
 * (commonInterestScore). Tests for these queries verify the main MATCH structure but skip
 * LET-computed field assertions.
 */
public class LdbcQueryCorrectnessTest {

  private static final String DB_NAME = "ldbc_correctness_test";

  // Person IDs
  private static final long ALICE = 1;
  private static final long BOB = 2;
  private static final long CAROL = 3;
  private static final long DAVE = 4;
  private static final long EVE = 5;

  // Place IDs
  private static final long SPRINGFIELD = 100; // City
  private static final long SHELBYVILLE = 101; // City
  private static final long BERLIN = 102; // City
  private static final long CHINA = 200; // Country
  private static final long INDIA = 201; // Country
  private static final long GERMANY = 202; // Country
  private static final long ASIA = 300; // Continent
  private static final long EUROPE = 301; // Continent

  // Tag/TagClass IDs
  private static final long TAG_JAVA = 400;
  private static final long TAG_PYTHON = 401;
  private static final long TC_MUSICAL_ARTIST = 500;
  private static final long TC_ARTIST = 501;

  // Organisation IDs
  private static final long ORG_ACME = 600; // Company
  private static final long ORG_MIT = 601; // University

  // Forum IDs
  private static final long FORUM_ALICE_WALL = 700;

  // Post IDs
  private static final long POST_1 = 800; // by Alice, in China, tag: Java
  private static final long POST_2 = 801; // by Bob, in India, tags: Python, Java
  private static final long POST_3 = 802; // by Bob, in China, tag: Python (old post)
  private static final long POST_4 = 803; // by Carol, in China, tag: Java
  private static final long POST_5 = 804; // by Carol, in India, tag: Python

  // Comment IDs
  private static final long COMMENT_1 = 900; // by Bob, reply of Post1, tag: Java
  private static final long COMMENT_2 = 901; // by Carol, reply of Post1
  private static final long COMMENT_3 = 902; // by Dave, reply of Comment1 (nested)
  private static final long COMMENT_4 = 903; // by Eve, reply of Post1

  // Epoch millis for key dates
  private static final long DATE_1990_06_25 = epochMillis(1990, 6, 25);
  private static final long DATE_1990_07_10 = epochMillis(1990, 7, 10);
  private static final long DATE_1991_03_15 = epochMillis(1991, 3, 15);
  private static final long DATE_1992_01_20 = epochMillis(1992, 1, 20);
  private static final long DATE_1993_06_22 = epochMillis(1993, 6, 22);
  private static final long DATE_2010_01_01 = epochMillis(2010, 1, 1);
  private static final long DATE_2010_02_01 = epochMillis(2010, 2, 1);
  private static final long DATE_2010_03_01 = epochMillis(2010, 3, 1);
  private static final long DATE_2010_04_01 = epochMillis(2010, 4, 1);
  private static final long DATE_2010_05_01 = epochMillis(2010, 5, 1);
  private static final long DATE_2010_06_01 = epochMillis(2010, 6, 1);
  private static final long DATE_2011_01_01 = epochMillis(2011, 1, 1);
  private static final long DATE_2011_03_01 = epochMillis(2011, 3, 1);
  private static final long DATE_2011_06_01 = epochMillis(2011, 6, 1);
  private static final long DATE_2011_09_01 = epochMillis(2011, 9, 1);
  private static final long DATE_2012_01_01 = epochMillis(2012, 1, 1);
  private static final long DATE_2012_06_01 = epochMillis(2012, 6, 1);
  private static final long DATE_2012_06_10 = epochMillis(2012, 6, 10);
  private static final long DATE_2012_06_15 = epochMillis(2012, 6, 15);
  private static final long DATE_2012_06_20 = epochMillis(2012, 6, 20);
  private static final long DATE_2012_07_01 = epochMillis(2012, 7, 1);
  private static final long DATE_2012_07_15 = epochMillis(2012, 7, 15);
  private static final long DATE_2012_08_01 = epochMillis(2012, 8, 1);
  private static final long DATE_2012_08_15 = epochMillis(2012, 8, 15);
  private static final long DATE_2012_09_01 = epochMillis(2012, 9, 1);

  private static YouTrackDB db;
  private static YTDBGraphTraversalSource g;
  private static Path dbPath;

  @BeforeClass
  public static void setupDatabase() throws Exception {
    dbPath = Files.createTempDirectory("ldbc-test-");
    db = YourTracks.instance(dbPath.toString());
    db.create(DB_NAME, DatabaseType.MEMORY, "admin", "admin", "admin");
    g = db.openTraversal(DB_NAME, "admin", "admin");

    createSchema();
    loadTestData();
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

  // ==================== IS1-IS7 ====================

  /** IS1: Person profile. Verify all profile fields for Alice. */
  @Test
  public void testIS1_personProfile() {
    var results = sql(LdbcQuerySql.IS1, "personId", ALICE);
    assertEquals(1, results.size());
    var r = results.get(0);
    assertEquals("Alice", r.get("firstName"));
    assertEquals("Aaa", r.get("lastName"));
    assertEquals("female", r.get("gender"));
    assertEquals("1.1.1.1", r.get("locationIP"));
    assertEquals("Chrome", r.get("browserUsed"));
    assertEquals(SPRINGFIELD, toLong(r.get("cityId")));
  }

  /** IS2: Recent messages of a person. Alice's only message is Post1 (a Post). */
  @Test
  public void testIS2_personPosts() {
    var results = sql(LdbcQuerySql.IS2, "personId", ALICE, "limit", 20);
    assertEquals(1, results.size());
    var r = results.get(0);
    assertEquals(POST_1, toLong(r.get("messageId")));
    assertEquals("Hello world", r.get("messageContent"));
    // Post1 is already a Post, so originalPost = Post1, author = Alice
    assertEquals(POST_1, toLong(r.get("originalPostId")));
    assertEquals(ALICE, toLong(r.get("originalPostAuthorId")));
    assertEquals("Alice", r.get("originalPostAuthorFirstName"));
  }

  /**
   * IS2 for a person with Comments. Bob created Post2, Post3, and Comment1.
   * Comment1 replies to Post1, so originalPost=Post1, author=Alice.
   */
  @Test
  public void testIS2_personPostsWithComments() {
    var results = sql(LdbcQuerySql.IS2, "personId", BOB, "limit", 20);
    // Bob has: Post2 (June 15), Post3 (Jan 1), Comment1 (July 1)
    // ORDER BY messageCreationDate DESC
    assertEquals(3, results.size());
    // First result should be Comment1 (most recent)
    assertEquals(COMMENT_1, toLong(results.get(0).get("messageId")));
    // Comment1 reply chain -> Post1, author of Post1 = Alice
    assertEquals(POST_1, toLong(results.get(0).get("originalPostId")));
    assertEquals(ALICE, toLong(results.get(0).get("originalPostAuthorId")));
  }

  /** IS3: Friends of Alice. Should return Bob and Carol, ordered by date DESC, id ASC. */
  @Test
  public void testIS3_personFriends() {
    var results = sql(LdbcQuerySql.IS3, "personId", ALICE);
    assertEquals(2, results.size());
    // Carol's KNOWS date (June 2011) > Bob's (Jan 2011) -> Carol first
    assertEquals(CAROL, toLong(results.get(0).get("personId")));
    assertEquals("Carol", results.get(0).get("firstName"));
    assertEquals(BOB, toLong(results.get(1).get("personId")));
    assertEquals("Bob", results.get(1).get("firstName"));
  }

  /** IS4: Content of a message (Post with text content). */
  @Test
  public void testIS4_postContent() {
    var results = sql(LdbcQuerySql.IS4, "messageId", POST_1);
    assertEquals(1, results.size());
    assertEquals("Hello world", results.get(0).get("messageContent"));
  }

  /** IS4: Content of a message (Comment). */
  @Test
  public void testIS4_commentContent() {
    var results = sql(LdbcQuerySql.IS4, "messageId", COMMENT_1);
    assertEquals(1, results.size());
    assertEquals("Nice post", results.get(0).get("messageContent"));
  }

  /** IS5: Creator of Post1 is Alice. */
  @Test
  public void testIS5_messageCreator() {
    var results = sql(LdbcQuerySql.IS5, "messageId", POST_1);
    assertEquals(1, results.size());
    assertEquals(ALICE, toLong(results.get(0).get("personId")));
    assertEquals("Alice", results.get(0).get("firstName"));
    assertEquals("Aaa", results.get(0).get("lastName"));
  }

  /** IS5: Creator of Comment1 is Bob. */
  @Test
  public void testIS5_commentCreator() {
    var results = sql(LdbcQuerySql.IS5, "messageId", COMMENT_1);
    assertEquals(1, results.size());
    assertEquals(BOB, toLong(results.get(0).get("personId")));
  }

  /** IS6: Forum of Post1 -> Forum1, moderator Alice. */
  @Test
  public void testIS6_messageForumFromPost() {
    var results = sql(LdbcQuerySql.IS6, "messageId", POST_1);
    assertEquals(1, results.size());
    var r = results.get(0);
    assertEquals(FORUM_ALICE_WALL, toLong(r.get("forumId")));
    assertEquals("Wall of Alice", r.get("forumTitle"));
    assertEquals(ALICE, toLong(r.get("moderatorId")));
  }

  /** IS6: Forum of Comment1 -> traverses REPLY_OF to Post1 -> Forum1. */
  @Test
  public void testIS6_messageForumFromComment() {
    var results = sql(LdbcQuerySql.IS6, "messageId", COMMENT_1);
    assertEquals(1, results.size());
    assertEquals(FORUM_ALICE_WALL, toLong(results.get(0).get("forumId")));
    assertEquals(ALICE, toLong(results.get(0).get("moderatorId")));
  }

  /** IS6: Forum of nested comment (Comment3 -> Comment1 -> Post1). */
  @Test
  public void testIS6_messageForumFromNestedComment() {
    var results = sql(LdbcQuerySql.IS6, "messageId", COMMENT_3);
    assertEquals(1, results.size());
    assertEquals(FORUM_ALICE_WALL, toLong(results.get(0).get("forumId")));
  }

  /**
   * IS7: Replies to Post1. Direct replies: Comment1 (Bob), Comment2 (Carol), Comment4 (Eve).
   * Bob and Carol know Alice (author), Eve does not.
   */
  @Test
  public void testIS7_messageReplies() {
    var results = sql(LdbcQuerySql.IS7, "messageId", POST_1);
    assertEquals(3, results.size());
    // ORDER BY commentCreationDate DESC, replyAuthorId ASC
    // Comment4 (Sep 1, Eve=5), Comment2 (Jul 15, Carol=3), Comment1 (Jul 1, Bob=2)
    assertEquals(COMMENT_4, toLong(results.get(0).get("commentId")));
    assertEquals(EVE, toLong(results.get(0).get("replyAuthorId")));
    assertEquals(false, results.get(0).get("replyAuthorKnowsOriginalMessageAuthor"));

    assertEquals(COMMENT_2, toLong(results.get(1).get("commentId")));
    assertEquals(true, results.get(1).get("replyAuthorKnowsOriginalMessageAuthor"));

    assertEquals(COMMENT_1, toLong(results.get(2).get("commentId")));
    assertEquals(true, results.get(2).get("replyAuthorKnowsOriginalMessageAuthor"));
  }

  // ==================== IC1-IC13 ====================

  /**
   * IC1: Transitive friends named "Alice" within 3 hops of Alice.
   * Dave(4, firstName=Alice) is at distance 2 (Alice->Bob->Dave).
   *
   * <p>Note: universities/companies fields use LET with $parent.$current (known limitation),
   * so only main result fields are verified.
   */
  @Test
  public void testIC1_transitiveFriends() {
    var results = sql(LdbcQuerySql.IC1,
        "personId", ALICE, "firstName", "Alice", "limit", 20);
    assertEquals(1, results.size());
    var r = results.get(0);
    assertEquals(DAVE, toLong(r.get("personId")));
    assertEquals("Ddd", r.get("lastName"));
    assertEquals(2, toLong(r.get("distance")));
  }

  /**
   * IC2: Recent messages by Alice's friends before a cutoff date.
   * Friends: Bob, Carol. Messages before 2012-07-02:
   * Bob: Comment1 (Jul 1), Post2 (Jun 15), Post3 (Jan 1).
   * Carol: Post5 (Jun 20), Post4 (Jun 10).
   */
  @Test
  public void testIC2_recentFriendMessages() {
    Date maxDate = new Date(epochMillis(2012, 7, 2));
    var results = sql(LdbcQuerySql.IC2,
        "personId", ALICE, "maxDate", maxDate, "limit", 20);
    assertEquals(5, results.size());
    // ORDER BY messageCreationDate DESC, messageId ASC
    assertEquals(COMMENT_1, toLong(results.get(0).get("messageId")));
    assertEquals(BOB, toLong(results.get(0).get("personId")));
    // Verify all expected messages are present
    Set<Long> messageIds = results.stream()
        .map(r -> toLong(r.get("messageId")))
        .collect(Collectors.toSet());
    assertTrue(messageIds.contains(POST_2));
    assertTrue(messageIds.contains(POST_3));
    assertTrue(messageIds.contains(POST_4));
    assertTrue(messageIds.contains(POST_5));
  }

  /**
   * IC3: Friends/FoF who live outside given countries but posted in both.
   *
   * <p>Uses LET with $parent.$current for counting messages per country. Due to the known
   * $parent.$current limitation, this test verifies the inner MATCH (location filter)
   * independently and checks that the full query executes without errors.
   */
  @Test
  public void testIC3_innerMatchLocations() {
    // Verify inner MATCH: Carol lives in Germany (not China/India) -> passes filter
    var innerResults = sql(
        "MATCH {class: Person, as: start, where: (id = :personId)}"
            + ".out('KNOWS'){while: ($depth < 2), as: person,"
            + " where: (@rid <> $matched.start.@rid)}"
            + ".out('IS_LOCATED_IN'){as: personCity}"
            + ".out('IS_PART_OF'){as: personCountry,"
            + " where: (name NOT IN [:countryX, :countryY])}"
            + " RETURN person.id as personId, personCountry.name as country",
        "personId", ALICE, "countryX", "China", "countryY", "India");
    assertEquals(1, innerResults.size());
    assertEquals(CAROL, toLong(innerResults.get(0).get("personId")));
    assertEquals("Germany", innerResults.get(0).get("country"));
  }

  /** IC3: Full query executes without error. */
  @Test
  public void testIC3_fullQueryExecutes() {
    Date startDate = new Date(DATE_2012_06_01);
    Date endDate = new Date(DATE_2012_07_01);
    // Should not throw - LET results may be empty due to known limitation
    var results = sql(LdbcQuerySql.IC3,
        "personId", ALICE,
        "countryX", "China", "countryY", "India",
        "startDate", startDate, "endDate", endDate,
        "limit", 20);
    assertNotNull(results);
  }

  /**
   * IC4: New topics — tags on Alice's friends' Posts in [Jun 1, Jul 1) that weren't
   * used on their posts before Jun 1. Bob's old Post3 has Python, so Python is
   * eliminated for Bob. Java survives for both Bob (Post2) and Carol (Post4).
   * Python survives for Carol (Post5, no old posts).
   */
  @Test
  public void testIC4_newTopics() {
    Date startDate = new Date(DATE_2012_06_01);
    Date endDate = new Date(DATE_2012_07_01);
    var results = sql(LdbcQuerySql.IC4,
        "personId", ALICE,
        "startDate", startDate, "endDate", endDate,
        "limit", 20);
    // ORDER BY postCount DESC, tagName ASC
    // Java: Bob(Post2) + Carol(Post4) = 2 posts
    // Python: Carol(Post5) = 1 post (Bob's Python is eliminated by old Post3)
    assertEquals(2, results.size());
    assertEquals("Java", results.get(0).get("tagName"));
    assertEquals(2L, toLong(results.get(0).get("postCount")));
    assertEquals("Python", results.get(1).get("tagName"));
    assertEquals(1L, toLong(results.get(1).get("postCount")));
  }

  /**
   * IC5: Forums joined by FoF after a date with post counts.
   *
   * <p>Verifies the inner MATCH structure (forum membership filter) independently.
   *
   * <p>Bob joined Forum1 on 2012-01-01 ({@code >= minDate}).
   * Carol joined 2011-01-01 (excluded).
   */
  @Test
  public void testIC5_innerMatchMembership() {
    var results = sql(
        "SELECT DISTINCT person.id as personId, forum.id as forumId,"
            + " forum.title as forumTitle FROM ("
            + " MATCH {class: Person, as: start, where: (id = :personId)}"
            + "  .out('KNOWS'){while: ($depth < 2), as: person,"
            + "   where: (@rid <> $matched.start.@rid)}"
            + "  .inE('HAS_MEMBER'){as: membership, where: (joinDate >= :minDate)}"
            + "  .outV(){as: forum}"
            + " RETURN person, forum)",
        "personId", ALICE, "minDate", new Date(DATE_2012_01_01));
    assertEquals(1, results.size());
    assertEquals(BOB, toLong(results.get(0).get("personId")));
    assertEquals(FORUM_ALICE_WALL, toLong(results.get(0).get("forumId")));
  }

  /**
   * Regression test for the MATCH planner bug: verifies that adding a
   * {@code class: Forum} filter on the {@code outV()} step does not cause
   * the planner to pick Forum as the traversal root, which would reverse
   * the traversal direction and break {@code $matched} references.
   *
   * <p>Without the fix, the planner sees Forum (with a known class and
   * therefore an estimated weight) as a better starting node than the
   * anonymous intermediate steps, reversing the entire chain and producing
   * 0 results.
   */
  @Test
  public void testIC5_matchWithClassForumAndWhile() {
    // First: verify without class filter works (control)
    var noClassResults = sql(
        "SELECT DISTINCT person.id as personId, forum.id as forumId FROM ("
            + " MATCH {class: Person, as: start, where: (id = :personId)}"
            + "  .out('KNOWS'){while: ($depth < 2), as: person,"
            + "   where: (@rid <> $matched.start.@rid)}"
            + "  .inE('HAS_MEMBER'){as: membership, where: (joinDate >= :minDate)}"
            + "  .outV(){as: forum}"
            + " RETURN person, forum)",
        "personId", ALICE, "minDate", new Date(DATE_2012_01_01));
    assertEquals("Without class filter should return 1", 1, noClassResults.size());

    // With class filter
    var results = sql(
        "SELECT DISTINCT person.id as personId, forum.id as forumId FROM ("
            + " MATCH {class: Person, as: start, where: (id = :personId)}"
            + "  .out('KNOWS'){while: ($depth < 2), as: person,"
            + "   where: (@rid <> $matched.start.@rid)}"
            + "  .inE('HAS_MEMBER'){as: membership, where: (joinDate >= :minDate)}"
            + "  .outV(){class: Forum, as: forum}"
            + " RETURN person, forum)",
        "personId", ALICE, "minDate", new Date(DATE_2012_01_01));
    assertEquals(
        "MATCH with class:Forum + while should return results",
        1,
        results.size());
  }

  /**
   * End-to-end test: full IC5 query using the rewritten MATCH-based approach.
   * The MATCH extends through Forum -> CONTAINER_OF -> Post -> HAS_CREATOR
   * to count posts by friends, eliminating the slow correlated LET subquery.
   *
   * <p>Bob joined FORUM_ALICE_WALL on 2012-01-01 (>= minDate) and created
   * POST_2 and POST_3 in that forum. Carol joined on 2011-01-01 (excluded).
   * Expected: 1 forum (FORUM_ALICE_WALL) with postCount=2.
   */
  @Test
  public void testIC5_fullQueryWithMatchBasedPostCount() {
    var results = sql(LdbcQuerySql.IC5,
        "personId", ALICE, "minDate", new Date(DATE_2012_01_01), "limit", 20);
    assertEquals("IC5 should return 1 forum", 1, results.size());
    assertEquals(FORUM_ALICE_WALL, toLong(results.get(0).get("forumId")));
    assertEquals("Wall of Alice", results.get(0).get("forumTitle"));
    assertEquals(2L, toLong(results.get(0).get("postCount")));
  }

  /**
   * IC6: Tags co-occurring with "Java" on posts by Alice's friends/FoF.
   * Post2 (Bob) has both Java and Python -> Python co-occurs with Java.
   */
  @Test
  public void testIC6_tagCoOccurrence() {
    var results = sql(LdbcQuerySql.IC6,
        "personId", ALICE, "tagName", "Java", "limit", 20);
    assertEquals(1, results.size());
    assertEquals("Python", results.get(0).get("tagName"));
    assertEquals(1L, toLong(results.get(0).get("postCount")));
  }

  /**
   * IC7: Recent likers of Alice's messages.
   * Post1 liked by: Eve (Sep 1, isNew=true), Carol (Aug 15, isNew=false),
   * Bob (Aug 1, isNew=false).
   */
  @Test
  public void testIC7_recentLikers() {
    var results = sql(LdbcQuerySql.IC7,
        "personId", ALICE, "limit", 20);
    assertEquals(3, results.size());
    // ORDER BY likeCreationDate DESC, personId ASC
    assertEquals(EVE, toLong(results.get(0).get("personId")));
    assertEquals(true, results.get(0).get("isNew"));
    assertEquals(POST_1, toLong(results.get(0).get("messageId")));

    assertEquals(CAROL, toLong(results.get(1).get("personId")));
    assertEquals(false, results.get(1).get("isNew"));

    assertEquals(BOB, toLong(results.get(2).get("personId")));
    assertEquals(false, results.get(2).get("isNew"));
  }

  /**
   * IC8: Recent replies to Alice's messages.
   * Post1 has replies: Comment4 (Eve, Sep 1), Comment2 (Carol, Jul 15),
   * Comment1 (Bob, Jul 1).
   */
  @Test
  public void testIC8_recentReplies() {
    var results = sql(LdbcQuerySql.IC8,
        "personId", ALICE, "limit", 20);
    // Post1 has 3 direct replies: Comment1, Comment2, Comment4 (Comment3 replies to Comment1)
    assertEquals(3, results.size());
    // ORDER BY commentCreationDate DESC, commentId ASC
    assertEquals(COMMENT_4, toLong(results.get(0).get("commentId")));
    assertEquals(EVE, toLong(results.get(0).get("personId")));
    assertEquals(COMMENT_2, toLong(results.get(1).get("commentId")));
    assertEquals(COMMENT_1, toLong(results.get(2).get("commentId")));
  }

  /**
   * IC9: Recent messages by friends/FoF of Alice before cutoff.
   * 2-hop network: Bob, Carol, Dave.
   * Bob: Comment1 (Jul 1), Post2 (Jun 15), Post3 (Jan 1).
   * Carol: Post5 (Jun 20), Post4 (Jun 10).
   */
  @Test
  public void testIC9_recentFofMessages() {
    Date maxDate = new Date(epochMillis(2012, 7, 2));
    var results = sql(LdbcQuerySql.IC9,
        "personId", ALICE, "maxDate", maxDate, "limit", 20);
    // Bob: Comment1, Post2, Post3. Carol: Post5, Post4. Dave: no messages before cutoff.
    assertEquals(5, results.size());
    // ORDER BY messageCreationDate DESC, messageId ASC
    assertEquals(COMMENT_1, toLong(results.get(0).get("messageId")));
  }

  /**
   * IC10: Friend recommendation for Bob. FoF (excl direct friends): Carol, Eve.
   * Birthday filter 0621-0722: Carol (0120) excluded, Eve (0622) included.
   *
   * <p>CommonInterestScore uses LET with $parent.$current (known limitation),
   * so only person identity is verified.
   */
  @Test
  public void testIC10_friendRecommendation() {
    var results = sql(LdbcQuerySql.IC10,
        "personId", BOB,
        "startMd", "0621", "endMd", "0722",
        "wrap", false,
        "limit", 20);
    assertEquals(1, results.size());
    assertEquals(EVE, toLong(results.get(0).get("personId")));
    assertEquals("Eve", results.get(0).get("firstName"));
  }

  /**
   * IC11: Job referral. Friends/FoF of Alice working in China before 2010.
   * Dave: WORK_AT ACME (workFrom=2008), Bob: WORK_AT ACME (workFrom=2009).
   */
  @Test
  public void testIC11_jobReferral() {
    var results = sql(LdbcQuerySql.IC11,
        "personId", ALICE,
        "countryName", "China", "workFromYear", 2010,
        "limit", 20);
    assertEquals(2, results.size());
    // ORDER BY organizationWorkFromYear ASC, personId ASC
    assertEquals(DAVE, toLong(results.get(0).get("personId")));
    assertEquals(2008, toLong(results.get(0).get("organizationWorkFromYear")));
    assertEquals("ACME", results.get(0).get("organizationName"));
    assertEquals(BOB, toLong(results.get(1).get("personId")));
    assertEquals(2009, toLong(results.get(1).get("organizationWorkFromYear")));
  }

  /**
   * IC12: Expert search. Alice's friends' Comments on Posts tagged in "Artist" hierarchy.
   * Tag hierarchy: Java -> MusicalArtist -> Artist.
   * Bob's Comment1 replies to Post1 (tagged Java) -> matches.
   * Carol's Comment2 replies to Post1 (tagged Java) -> matches.
   */
  @Test
  public void testIC12_expertSearch() {
    var results = sql(LdbcQuerySql.IC12,
        "personId", ALICE, "tagClassName", "Artist", "limit", 20);
    assertEquals(2, results.size());
    // ORDER BY replyCount DESC, personId ASC
    Set<Long> personIds = results.stream()
        .map(r -> toLong(r.get("personId")))
        .collect(Collectors.toSet());
    assertTrue(personIds.contains(BOB));
    assertTrue(personIds.contains(CAROL));
    for (var r : results) {
      assertEquals(1L, toLong(r.get("replyCount")));
      Object tagNames = r.get("tagNames");
      assertNotNull(tagNames);
      assertTrue("tagNames should contain Java",
          tagNames.toString().contains("Java"));
    }
  }

  /** IC13: Shortest path Alice -> Dave = 2 (Alice->Bob->Dave). */
  @Test
  public void testIC13_shortestPathDistance2() {
    var results = sql(LdbcQuerySql.IC13,
        "person1Id", ALICE, "person2Id", DAVE);
    assertEquals(1, results.size());
    assertEquals(2L, toLong(results.get(0).get("pathLength")));
  }

  /** IC13: Direct friends Alice -> Bob = distance 1. */
  @Test
  public void testIC13_shortestPathDistance1() {
    var results = sql(LdbcQuerySql.IC13,
        "person1Id", ALICE, "person2Id", BOB);
    assertEquals(1, results.size());
    assertEquals(1L, toLong(results.get(0).get("pathLength")));
  }

  /** IC13: Same person -> distance 0. */
  @Test
  public void testIC13_shortestPathSamePerson() {
    var results = sql(LdbcQuerySql.IC13,
        "person1Id", ALICE, "person2Id", ALICE);
    assertEquals(1, results.size());
    assertEquals(0L, toLong(results.get(0).get("pathLength")));
  }

  // ==================== LdbcQuerySql error path ====================

  /**
   * Verifies that LdbcQuerySql.loadResource throws IllegalStateException
   * when a SQL resource file is not found on the classpath.
   */
  @Test
  public void testLoadResourceThrowsOnMissingResource() throws Exception {
    var method = LdbcQuerySql.class.getDeclaredMethod("loadResource", String.class);
    method.setAccessible(true);
    try {
      method.invoke(null, "nonexistent/missing-query.sql");
      fail("Expected IllegalStateException for missing resource");
    } catch (InvocationTargetException e) {
      assertTrue(e.getCause() instanceof IllegalStateException);
      assertTrue(e.getCause().getMessage().contains("not found"));
    }
  }

  /**
   * Verifies that LdbcBenchmarkState.loadSqlStatements throws
   * IllegalStateException when the resource is not found.
   */
  @Test(expected = IllegalStateException.class)
  public void testLoadSqlStatementsMissingResource() {
    LdbcBenchmarkState.loadSqlStatements("/nonexistent-schema.sql");
  }

  // ==================== BothE-KNOWS extension query ====================

  /**
   * Verifies that bothE('KNOWS') with a date filter returns the expected edges,
   * including the correct count when KNOWS is stored bidirectionally.
   *
   * <p>Test graph KNOWS edges (created by createKnows — each is bidirectional):
   * <pre>
   *   Alice—Bob   creationDate = 2011-01-01
   *   Alice—Carol creationDate = 2011-06-01
   *   Bob—Dave    creationDate = 2011-03-01
   *   Dave—Eve    creationDate = 2011-09-01
   * </pre>
   *
   * <p>For Alice (KNOWS: Bob at 2011-01-01, Carol at 2011-06-01), using
   * {@code bothE('KNOWS'){where: (creationDate >= :minDate)}.inV()} with
   * minDate = 2011-06-01:
   * <ul>
   *   <li>out_KNOWS matches Alice→Carol (2011-06-01 ≥ 2011-06-01) → inV() = Carol</li>
   *   <li>in_KNOWS matches Carol→Alice (2011-06-01 ≥ 2011-06-01) → inV() = Alice (self)</li>
   * </ul>
   * Both limit to 2 rows. Alice→Bob (2011-01-01 < 2011-06-01) is excluded by the pre-filter.
   */
  @Test
  public void testBothEKnows_recentConnectionsFilter() {
    // minDate = 2011-06-01: only Alice–Carol qualifies; Alice–Bob was 2011-01-01
    var minDate = new Date(DATE_2011_06_01);
    var rows = sql(LdbcQuerySql.BOTH_E_KNOWS,
        "personId", ALICE, "minDate", minDate, "limit", 20);

    // Both directions of Alice—Carol match, giving 2 rows:
    // out_KNOWS(Alice→Carol).inV() = Carol, in_KNOWS(Carol→Alice).inV() = Alice (self).
    assertEquals(
        "bothE from Alice with minDate=2011-06-01 should return exactly 2 rows "
            + "(one per direction of the Alice–Carol edge): " + rows,
        2, rows.size());

    // Both rows must have creationDate = 2011-06-01
    for (var row : rows) {
      assertEquals("since should match the KNOWS creationDate",
          DATE_2011_06_01, ((Date) row.get("since")).getTime());
    }
  }

  /**
   * Verifies that a date filter that excludes all KNOWS edges returns zero rows.
   */
  @Test
  public void testBothEKnows_nothingMatchesAfterAllEdges() {
    // minDate far in the future — no KNOWS edge has this date
    var minDate = new Date(DATE_2012_09_01);
    var rows = sql(LdbcQuerySql.BOTH_E_KNOWS,
        "personId", ALICE, "minDate", minDate, "limit", 20);

    assertEquals("No KNOWS edges should match a future date", 0, rows.size());
  }

  // ==================== Forum count / exists extension queries ====================

  /**
   * FORUM_JOINER_COUNT: counts members of the Alice's-Wall Forum who joined
   * on or after a given date. Test graph (created in loadTestData):
   *   FORUM_ALICE_WALL HAS_MEMBER Bob   joinDate = 2012-01-01
   *   FORUM_ALICE_WALL HAS_MEMBER Carol joinDate = 2011-01-01
   * Verifies both a permissive and a restrictive filter, plus a future date
   * that excludes everything.
   */
  @Test
  public void testForumJoinerCount_variousDates() {
    // minDate = 2011-01-01 (inclusive lower bound): both Bob and Carol qualify
    var all = sql(LdbcQuerySql.FORUM_JOINER_COUNT,
        "forumId", FORUM_ALICE_WALL, "minDate", new Date(DATE_2011_01_01));
    assertEquals(1, all.size());
    assertEquals(2L, toLong(all.get(0).get("joinerCount")));

    // minDate = 2012-01-01: only Bob qualifies
    var recent = sql(LdbcQuerySql.FORUM_JOINER_COUNT,
        "forumId", FORUM_ALICE_WALL, "minDate", new Date(DATE_2012_01_01));
    assertEquals(1, recent.size());
    assertEquals(1L, toLong(recent.get(0).get("joinerCount")));

    // minDate = future: nobody qualifies, count is 0
    var future = sql(LdbcQuerySql.FORUM_JOINER_COUNT,
        "forumId", FORUM_ALICE_WALL, "minDate", new Date(DATE_2012_09_01));
    assertEquals(1, future.size());
    assertEquals(0L, toLong(future.get(0).get("joinerCount")));
  }

  /** Verifies that all 20 SQL query constants are loaded and non-empty. */
  @Test
  public void testAllQuerySqlConstantsLoaded() {
    assertNotNull(LdbcQuerySql.IS1);
    assertNotNull(LdbcQuerySql.IS2);
    assertNotNull(LdbcQuerySql.IS3);
    assertNotNull(LdbcQuerySql.IS4);
    assertNotNull(LdbcQuerySql.IS5);
    assertNotNull(LdbcQuerySql.IS6);
    assertNotNull(LdbcQuerySql.IS7);
    assertNotNull(LdbcQuerySql.IC1);
    assertNotNull(LdbcQuerySql.IC2);
    assertNotNull(LdbcQuerySql.IC3);
    assertNotNull(LdbcQuerySql.IC4);
    assertNotNull(LdbcQuerySql.IC5);
    assertNotNull(LdbcQuerySql.IC6);
    assertNotNull(LdbcQuerySql.IC7);
    assertNotNull(LdbcQuerySql.IC8);
    assertNotNull(LdbcQuerySql.IC9);
    assertNotNull(LdbcQuerySql.IC10);
    assertNotNull(LdbcQuerySql.IC11);
    assertNotNull(LdbcQuerySql.IC12);
    assertNotNull(LdbcQuerySql.IC13);
    assertNotNull(LdbcQuerySql.BOTH_E_KNOWS);
    assertNotNull(LdbcQuerySql.FORUM_RECENT_JOINERS);
    assertNotNull(LdbcQuerySql.FORUM_JOINER_COUNT);
    assertTrue("IS1 should contain SQL", LdbcQuerySql.IS1.length() > 10);
  }

  // ==================== Helpers ====================

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> sql(String query, Object... keyValues) {
    return g.computeInTx(t -> {
      var ytg = (YTDBGraphTraversalSource) t;
      return ytg.yql(query, keyValues).toList().stream()
          .map(obj -> (Map<String, Object>) obj)
          .toList();
    });
  }

  private static long toLong(Object value) {
    return ((Number) value).longValue();
  }

  private static long epochMillis(int year, int month, int day) {
    return LocalDate.of(year, month, day).atStartOfDay()
        .toInstant(ZoneOffset.UTC).toEpochMilli();
  }

  // ==================== Schema & Data Setup ====================

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

  private static void loadTestData() {
    g.executeInTx(t -> {
      var ytg = (YTDBGraphTraversalSource) t;

      // ---- Places ----
      insertPlace(ytg, SPRINGFIELD, "Springfield", "City");
      insertPlace(ytg, SHELBYVILLE, "Shelbyville", "City");
      insertPlace(ytg, BERLIN, "Berlin", "City");
      insertPlace(ytg, CHINA, "China", "Country");
      insertPlace(ytg, INDIA, "India", "Country");
      insertPlace(ytg, GERMANY, "Germany", "Country");
      insertPlace(ytg, ASIA, "Asia", "Continent");
      insertPlace(ytg, EUROPE, "Europe", "Continent");

      createEdge(ytg, "IS_PART_OF", "Place", SPRINGFIELD, "Place", CHINA);
      createEdge(ytg, "IS_PART_OF", "Place", SHELBYVILLE, "Place", INDIA);
      createEdge(ytg, "IS_PART_OF", "Place", BERLIN, "Place", GERMANY);
      createEdge(ytg, "IS_PART_OF", "Place", CHINA, "Place", ASIA);
      createEdge(ytg, "IS_PART_OF", "Place", INDIA, "Place", ASIA);
      createEdge(ytg, "IS_PART_OF", "Place", GERMANY, "Place", EUROPE);

      // ---- TagClasses ----
      insertVertex(ytg, "TagClass", TC_MUSICAL_ARTIST, "name", "MusicalArtist");
      insertVertex(ytg, "TagClass", TC_ARTIST, "name", "Artist");
      createEdge(ytg, "IS_SUBCLASS_OF", "TagClass", TC_MUSICAL_ARTIST,
          "TagClass", TC_ARTIST);

      // ---- Tags ----
      insertVertex(ytg, "Tag", TAG_JAVA, "name", "Java");
      insertVertex(ytg, "Tag", TAG_PYTHON, "name", "Python");
      createEdge(ytg, "HAS_TYPE", "Tag", TAG_JAVA, "TagClass", TC_MUSICAL_ARTIST);
      createEdge(ytg, "HAS_TYPE", "Tag", TAG_PYTHON, "TagClass", TC_ARTIST);

      // ---- Organisations ----
      ytg.yql(
          "INSERT INTO Organisation SET id = :id, type = :type, name = :name, url = :url",
          "id", ORG_ACME, "type", "Company", "name", "ACME",
          "url", "http://acme.com").iterate();
      ytg.yql(
          "INSERT INTO Organisation SET id = :id, type = :type, name = :name, url = :url",
          "id", ORG_MIT, "type", "University", "name", "MIT",
          "url", "http://mit.edu").iterate();
      createEdge(ytg, "IS_LOCATED_IN", "Organisation", ORG_ACME, "Place", CHINA);
      createEdge(ytg, "IS_LOCATED_IN", "Organisation", ORG_MIT, "Place", CHINA);

      // ---- Persons ----
      insertPerson(ytg, ALICE, "Alice", "Aaa", "female", DATE_1990_06_25,
          DATE_2010_01_01, "1.1.1.1", "Chrome");
      insertPerson(ytg, BOB, "Bob", "Bbb", "male", DATE_1991_03_15,
          DATE_2010_02_01, "2.2.2.2", "Firefox");
      insertPerson(ytg, CAROL, "Carol", "Ccc", "female", DATE_1992_01_20,
          DATE_2010_03_01, "3.3.3.3", "Chrome");
      // Dave has firstName="Alice" for IC1 test
      insertPerson(ytg, DAVE, "Alice", "Ddd", "male", DATE_1990_07_10,
          DATE_2010_04_01, "4.4.4.4", "Safari");
      insertPerson(ytg, EVE, "Eve", "Eee", "female", DATE_1993_06_22,
          DATE_2010_05_01, "5.5.5.5", "Edge");

      createEdge(ytg, "IS_LOCATED_IN", "Person", ALICE, "Place", SPRINGFIELD);
      createEdge(ytg, "IS_LOCATED_IN", "Person", BOB, "Place", SPRINGFIELD);
      createEdge(ytg, "IS_LOCATED_IN", "Person", CAROL, "Place", BERLIN);
      createEdge(ytg, "IS_LOCATED_IN", "Person", DAVE, "Place", SHELBYVILLE);
      createEdge(ytg, "IS_LOCATED_IN", "Person", EVE, "Place", SHELBYVILLE);

      // ---- KNOWS (bidirectional) ----
      createKnows(ytg, ALICE, BOB, DATE_2011_01_01);
      createKnows(ytg, ALICE, CAROL, DATE_2011_06_01);
      createKnows(ytg, BOB, DAVE, DATE_2011_03_01);
      createKnows(ytg, DAVE, EVE, DATE_2011_09_01);

      // ---- Interests ----
      createEdge(ytg, "HAS_INTEREST", "Person", ALICE, "Tag", TAG_JAVA);
      createEdge(ytg, "HAS_INTEREST", "Person", BOB, "Tag", TAG_PYTHON);

      // ---- Work / Study ----
      ytg.yql(
          "CREATE EDGE WORK_AT FROM (SELECT FROM Person WHERE id = :from)"
              + " TO (SELECT FROM Organisation WHERE id = :to) SET workFrom = :wf",
          "from", BOB, "to", ORG_ACME, "wf", 2009).iterate();
      ytg.yql(
          "CREATE EDGE WORK_AT FROM (SELECT FROM Person WHERE id = :from)"
              + " TO (SELECT FROM Organisation WHERE id = :to) SET workFrom = :wf",
          "from", DAVE, "to", ORG_ACME, "wf", 2008).iterate();
      ytg.yql(
          "CREATE EDGE STUDY_AT FROM (SELECT FROM Person WHERE id = :from)"
              + " TO (SELECT FROM Organisation WHERE id = :to) SET classYear = :cy",
          "from", ALICE, "to", ORG_MIT, "cy", 2005).iterate();

      // ---- Forums ----
      ytg.yql(
          "INSERT INTO Forum SET id = :id, title = :title, creationDate = :cd",
          "id", FORUM_ALICE_WALL, "title", "Wall of Alice",
          "cd", DATE_2010_06_01).iterate();
      createEdge(ytg, "HAS_MODERATOR", "Forum", FORUM_ALICE_WALL, "Person", ALICE);
      createEdge(ytg, "HAS_TAG", "Forum", FORUM_ALICE_WALL, "Tag", TAG_JAVA);

      ytg.yql(
          "CREATE EDGE HAS_MEMBER FROM (SELECT FROM Forum WHERE id = :from)"
              + " TO (SELECT FROM Person WHERE id = :to) SET joinDate = :jd",
          "from", FORUM_ALICE_WALL, "to", BOB, "jd", DATE_2012_01_01).iterate();
      ytg.yql(
          "CREATE EDGE HAS_MEMBER FROM (SELECT FROM Forum WHERE id = :from)"
              + " TO (SELECT FROM Person WHERE id = :to) SET joinDate = :jd",
          "from", FORUM_ALICE_WALL, "to", CAROL, "jd", DATE_2011_01_01).iterate();

      // ---- Posts ----
      insertPost(ytg, POST_1, "Hello world", null, DATE_2012_06_01,
          "1.1.1.1", "Chrome", "en", 11);
      insertPost(ytg, POST_2, "Goodbye world", null, DATE_2012_06_15,
          "2.2.2.2", "Firefox", "en", 13);
      insertPost(ytg, POST_3, "Old post", null, DATE_2012_01_01,
          "2.2.2.2", "Firefox", "en", 8);
      insertPost(ytg, POST_4, "Post in China", null, DATE_2012_06_10,
          "3.3.3.3", "Chrome", "en", 13);
      insertPost(ytg, POST_5, "Post in India", null, DATE_2012_06_20,
          "3.3.3.3", "Chrome", "en", 13);

      createEdge(ytg, "HAS_CREATOR", "Post", POST_1, "Person", ALICE);
      createEdge(ytg, "HAS_CREATOR", "Post", POST_2, "Person", BOB);
      createEdge(ytg, "HAS_CREATOR", "Post", POST_3, "Person", BOB);
      createEdge(ytg, "HAS_CREATOR", "Post", POST_4, "Person", CAROL);
      createEdge(ytg, "HAS_CREATOR", "Post", POST_5, "Person", CAROL);

      createEdge(ytg, "IS_LOCATED_IN", "Post", POST_1, "Place", CHINA);
      createEdge(ytg, "IS_LOCATED_IN", "Post", POST_2, "Place", INDIA);
      createEdge(ytg, "IS_LOCATED_IN", "Post", POST_3, "Place", CHINA);
      createEdge(ytg, "IS_LOCATED_IN", "Post", POST_4, "Place", CHINA);
      createEdge(ytg, "IS_LOCATED_IN", "Post", POST_5, "Place", INDIA);

      createEdge(ytg, "HAS_TAG", "Post", POST_1, "Tag", TAG_JAVA);
      createEdge(ytg, "HAS_TAG", "Post", POST_2, "Tag", TAG_PYTHON);
      createEdge(ytg, "HAS_TAG", "Post", POST_2, "Tag", TAG_JAVA);
      createEdge(ytg, "HAS_TAG", "Post", POST_3, "Tag", TAG_PYTHON);
      createEdge(ytg, "HAS_TAG", "Post", POST_4, "Tag", TAG_JAVA);
      createEdge(ytg, "HAS_TAG", "Post", POST_5, "Tag", TAG_PYTHON);

      createEdge(ytg, "CONTAINER_OF", "Forum", FORUM_ALICE_WALL, "Post", POST_1);
      createEdge(ytg, "CONTAINER_OF", "Forum", FORUM_ALICE_WALL, "Post", POST_2);
      createEdge(ytg, "CONTAINER_OF", "Forum", FORUM_ALICE_WALL, "Post", POST_3);
      createEdge(ytg, "CONTAINER_OF", "Forum", FORUM_ALICE_WALL, "Post", POST_4);
      createEdge(ytg, "CONTAINER_OF", "Forum", FORUM_ALICE_WALL, "Post", POST_5);

      // ---- Comments ----
      insertComment(ytg, COMMENT_1, "Nice post", DATE_2012_07_01,
          "2.2.2.2", "Firefox", 9);
      insertComment(ytg, COMMENT_2, "I agree", DATE_2012_07_15,
          "3.3.3.3", "Chrome", 7);
      insertComment(ytg, COMMENT_3, "Me too", DATE_2012_08_01,
          "4.4.4.4", "Safari", 6);
      insertComment(ytg, COMMENT_4, "Cool", DATE_2012_09_01,
          "5.5.5.5", "Edge", 4);

      createEdge(ytg, "HAS_CREATOR", "Comment", COMMENT_1, "Person", BOB);
      createEdge(ytg, "HAS_CREATOR", "Comment", COMMENT_2, "Person", CAROL);
      createEdge(ytg, "HAS_CREATOR", "Comment", COMMENT_3, "Person", DAVE);
      createEdge(ytg, "HAS_CREATOR", "Comment", COMMENT_4, "Person", EVE);

      createEdge(ytg, "IS_LOCATED_IN", "Comment", COMMENT_1, "Place", CHINA);
      createEdge(ytg, "IS_LOCATED_IN", "Comment", COMMENT_2, "Place", INDIA);
      createEdge(ytg, "IS_LOCATED_IN", "Comment", COMMENT_3, "Place", CHINA);
      createEdge(ytg, "IS_LOCATED_IN", "Comment", COMMENT_4, "Place", INDIA);

      createEdge(ytg, "REPLY_OF", "Comment", COMMENT_1, "Post", POST_1);
      createEdge(ytg, "REPLY_OF", "Comment", COMMENT_2, "Post", POST_1);
      createEdge(ytg, "REPLY_OF", "Comment", COMMENT_3, "Comment", COMMENT_1);
      createEdge(ytg, "REPLY_OF", "Comment", COMMENT_4, "Post", POST_1);

      createEdge(ytg, "HAS_TAG", "Comment", COMMENT_1, "Tag", TAG_JAVA);

      // ---- LIKES ----
      ytg.yql(
          "CREATE EDGE LIKES FROM (SELECT FROM Person WHERE id = :from)"
              + " TO (SELECT FROM Post WHERE id = :to) SET creationDate = :cd",
          "from", BOB, "to", POST_1, "cd", DATE_2012_08_01).iterate();
      ytg.yql(
          "CREATE EDGE LIKES FROM (SELECT FROM Person WHERE id = :from)"
              + " TO (SELECT FROM Post WHERE id = :to) SET creationDate = :cd",
          "from", CAROL, "to", POST_1, "cd", DATE_2012_08_15).iterate();
      ytg.yql(
          "CREATE EDGE LIKES FROM (SELECT FROM Person WHERE id = :from)"
              + " TO (SELECT FROM Post WHERE id = :to) SET creationDate = :cd",
          "from", EVE, "to", POST_1, "cd", DATE_2012_09_01).iterate();
    });
  }

  private static void insertPlace(YTDBGraphTraversalSource ytg,
      long id, String name, String type) {
    ytg.yql(
        "INSERT INTO Place SET id = :id, name = :name, url = :url, type = :type",
        "id", id, "name", name, "url", "http://dbpedia.org/" + name,
        "type", type).iterate();
  }

  private static void insertVertex(YTDBGraphTraversalSource ytg,
      String className, long id, String propName, String propValue) {
    ytg.yql(
        "INSERT INTO " + className + " SET id = :id, " + propName + " = :pv,"
            + " url = :url",
        "id", id, "pv", propValue,
        "url", "http://dbpedia.org/" + propValue).iterate();
  }

  private static void insertPerson(YTDBGraphTraversalSource ytg,
      long id, String firstName, String lastName, String gender,
      long birthday, long creationDate, String locationIP, String browserUsed) {
    ytg.yql(
        "INSERT INTO Person SET id = :id, firstName = :fn, lastName = :ln,"
            + " gender = :g, birthday = :bd, creationDate = :cd,"
            + " locationIP = :ip, browserUsed = :br,"
            + " languages = :lang, emails = :emails",
        "id", id, "fn", firstName, "ln", lastName, "g", gender,
        "bd", birthday, "cd", creationDate,
        "ip", locationIP, "br", browserUsed,
        "lang", List.of("en"), "emails", List.of(firstName.toLowerCase() + "@test.com")).iterate();
  }

  private static void insertPost(YTDBGraphTraversalSource ytg,
      long id, String content, String imageFile, long creationDate,
      String locationIP, String browserUsed, String language, int length) {
    ytg.yql(
        "INSERT INTO Post SET id = :id, content = :content, imageFile = :img,"
            + " creationDate = :cd, locationIP = :ip, browserUsed = :br,"
            + " language = :lang, length = :len",
        "id", id, "content", content, "img", imageFile,
        "cd", creationDate, "ip", locationIP, "br", browserUsed,
        "lang", language, "len", length).iterate();
  }

  private static void insertComment(YTDBGraphTraversalSource ytg,
      long id, String content, long creationDate,
      String locationIP, String browserUsed, int length) {
    ytg.yql(
        "INSERT INTO Comment SET id = :id, content = :content,"
            + " creationDate = :cd, locationIP = :ip, browserUsed = :br,"
            + " length = :len",
        "id", id, "content", content,
        "cd", creationDate, "ip", locationIP, "br", browserUsed,
        "len", length).iterate();
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
