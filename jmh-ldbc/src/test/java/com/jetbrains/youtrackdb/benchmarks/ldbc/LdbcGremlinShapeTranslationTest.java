package com.jetbrains.youtrackdb.benchmarks.ldbc;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Drives every {@link GremlinTraversalShapes} builder the JMH harness measures, on both kill-switch
 * arms, so a broken engagement check is caught by an ordinary build instead of on Hetzner.
 *
 * <p>This class validates the optional translator on/off axis ({@code translatorEnabled}). The
 * {@code ldbc-jmh-compare} PR comment instead compares head vs {@code develop} with translator on
 * both sides — see {@link GremlinTraversalShapes} and {@link LdbcGremlinTranslatorBenchmark}.
 *
 * <p>The {@code @Benchmark} bodies themselves are dataset-bound and cannot run here. What can run
 * is the part that silently goes wrong: whether flipping
 * {@code QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED} actually changes which pipeline each shape
 * compiles to. If it does not, the benchmark's two arms measure the same path and report a
 * difference of zero, which reads as a clean null result rather than as a broken harness.
 *
 * <h2>Neither group's assertion can pass vacuously</h2>
 *
 * <p>"No {@code AbstractMatchPlanStep} in the step list" is also what a traversal that never built,
 * a closed session, or an empty fixture produces, so an absence check alone would pass for the wrong
 * reason. A <b>translating</b> shape therefore runs its on-arm first, where {@link
 * GremlinTraversalShapes#requireTranslated} throws unless the boundary step is demonstrably present,
 * and only then its off-arm. A <b>declining</b> shape has no such on-arm, so {@link
 * #assertDeclines} first proves the kill-switch reaches traversals in this session at all by
 * translating a known-translating witness, and only then asserts that this shape declines on both
 * arms. Every shape is additionally checked against a hand-computed result, so none can pass over a
 * degenerate graph.
 *
 * <h2>Running this test</h2>
 *
 * <p>{@code ./mvnw -pl core,jmh-ldbc test -Dtest=LdbcGremlinShapeTranslationTest
 * -Dsurefire.failIfNoSpecifiedTests=false}. The two modules have to share one reactor, or an
 * install-first {@code ./mvnw -pl core -am install -DskipTests} has to precede the run. This module
 * declares an ordinary {@code youtrackdb-core} dependency, so {@code -pl jmh-ldbc} on its own
 * resolves that jar from the local repository and measures whatever was installed there instead of
 * the working tree.
 *
 * <p>Measured while landing this class: against a stale installed jar the run reports three errors
 * — the {@code fold} terminator, IS1's full projection and the ordered page — which are exactly the
 * three shapes whose group depends on a recogniser the jar predates. Through the shared reactor the
 * same tree reports none. Three errors that read as harness defects are the symptom of the wrong
 * invocation.
 *
 * <h2>Fixture</h2>
 *
 * <p>Five people, two places, two messages, one forum and one organisation — the smallest graph
 * that distinguishes the shapes:
 *
 * <ul>
 *   <li>{@code KNOWS}: Alice→Bob, Alice→Carol, Bob→Dave, Bob→Alice, Carol→Erin, and Erin→everyone
 *       else. Bob→Dave makes an over-emitting one-hop plan return three names where the assertion
 *       expects two; Bob→Alice puts a cycle in reach of the two- and three-hop shapes so a
 *       {@code where()} back-reference has something to drop; Erin's four out-edges give the paging
 *       and grouping shapes more than one page and more than one group.
 *   <li>{@code IS_LOCATED_IN}: Alice→Zurich, Bob→Berlin — two cities, so an IS1 plan that ignores
 *       the join returns both.
 *   <li>A {@code Post} authored by Bob and a {@code Comment} authored by Carol replying to it, for
 *       the IS4 / IS5 / IS7 shapes. Alice likes the post (IC7). Forum {@code Wall} contains the
 *       post and Alice moderates it (IS6). Bob works at organisation Acme in China (IC11).
 * </ul>
 *
 * <p>The flag is flipped through {@link GlobalConfiguration}, not through a session-local
 * override, and never through {@code -DargLine=} — on some modules a CLI {@code argLine} replaces
 * the POM's block wholesale and on others it is inert.
 */
public class LdbcGremlinShapeTranslationTest {

  private static final String DB_NAME = "ldbc_gremlin_shapes_test";

  private static final long ALICE = 1;
  private static final long BOB = 2;
  private static final long CAROL = 3;
  private static final long DAVE = 4;
  private static final long ERIN = 5;

  private static final long ZURICH = 100;
  private static final long BERLIN = 200;

  /** A {@code Post} authored by Bob; {@code imageFile} is left unset so IS4's coalesce falls back. */
  private static final long POST = 1000;

  /** A {@code Comment} authored by Carol, replying to {@link #POST}. */
  private static final long COMMENT = 1001;

  private static final long POST_AT = 1_600_000_100_000L;
  private static final long COMMENT_AT = 1_600_000_200_000L;
  /** Exclusive upper bound for IC2's {@code creationDate < maxDate} filter. */
  private static final long IC2_MAX_DATE = 1_600_000_300_000L;

  private static final long FORUM = 10;
  private static final long COMPANY = 50;
  private static final long CHINA = 300;

  /**
   * {@code KNOWS.creationDate} values. Distinct and ordered so a projection of the edge property is
   * checked against a specific edge rather than against "some date".
   */
  private static final long KNOWS_ALICE_BOB_AT = 1_600_000_003_000L;
  private static final long KNOWS_ALICE_CAROL_AT = 1_600_000_002_000L;

  private static YouTrackDB db;
  private static YTDBGraphTraversalSource g;
  private static Path dbPath;

  /** RID of Alice, resolved once — {@code g.V(rid)} needs record identity, not the LDBC id. */
  private static Object aliceRid;

  @BeforeClass
  public static void setUpDatabase() throws Exception {
    dbPath = Files.createTempDirectory("ldbc-gremlin-shapes-");
    db = YourTracks.instance(dbPath.toString());
    db.create(DB_NAME, DatabaseType.MEMORY, "admin", "admin", "admin");
    g = db.openTraversal(DB_NAME, "admin", "admin");

    // The same schema the benchmark runs against, from the same resource, so a schema change
    // cannot leave the harness measuring a shape this test never verified.
    var statements = LdbcBenchmarkState.loadSqlStatements("/ldbc-schema.sql");
    g.executeInTx(t -> {
      for (var statement : statements) {
        t.yql(statement).iterate();
      }
    });

    g.executeInTx(t -> {
      insertPerson(t, ALICE, "Alice");
      insertPerson(t, BOB, "Bob");
      insertPerson(t, CAROL, "Carol");
      insertPerson(t, DAVE, "Dave");
      insertPerson(t, ERIN, "Erin");

      insertPlace(t, ZURICH, "Zurich");
      insertPlace(t, BERLIN, "Berlin");
      createLocatedIn(t, ALICE, ZURICH);
      createLocatedIn(t, BOB, BERLIN);

      createKnows(t, ALICE, BOB, KNOWS_ALICE_BOB_AT);
      createKnows(t, ALICE, CAROL, KNOWS_ALICE_CAROL_AT);
      createKnows(t, BOB, DAVE, 1_600_000_001_000L);
      createKnows(t, BOB, ALICE, 1_600_000_005_000L);
      createKnows(t, CAROL, ERIN, 1_600_000_004_000L);
      createKnows(t, ERIN, ALICE, 1_600_000_011_000L);
      createKnows(t, ERIN, BOB, 1_600_000_012_000L);
      createKnows(t, ERIN, CAROL, 1_600_000_013_000L);
      createKnows(t, ERIN, DAVE, 1_600_000_014_000L);

      insertMessage(t, "Post", POST, "post-1000", POST_AT);
      insertMessage(t, "Comment", COMMENT, "c-1001", COMMENT_AT);
      createHasCreator(t, POST, BOB);
      createHasCreator(t, COMMENT, CAROL);
      createReplyOf(t, COMMENT, POST);

      insertForum(t, FORUM, "Wall");
      createContainerOf(t, FORUM, POST);
      createHasModerator(t, FORUM, ALICE);
      createLikes(t, ALICE, POST);
      insertOrganisation(t, COMPANY, "Acme");
      insertPlace(t, CHINA, "China");
      createWorkAt(t, BOB, COMPANY);
      createOrgLocatedIn(t, COMPANY, CHINA);
    });

    aliceRid = g.computeInTx(
        t -> t.V().hasLabel(GremlinTraversalShapes.PERSON_LABEL).has("id", ALICE).next().id());
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
        files.sorted(Comparator.reverseOrder()).forEach(p -> {
          try {
            Files.deleteIfExists(p);
          } catch (IOException ignored) {
            // Temp-directory cleanup only; a leftover file must not fail the test run.
          }
        });
      }
    }
  }

  // -------------------------------------------------------------------------------------------
  // Translating shapes: boundary step present with the kill-switch on, absent with it off.
  // -------------------------------------------------------------------------------------------

  /**
   * Shape 1 — a bare {@code g.V(rid)} point-lookup DECLINES to native on both arms and returns
   * Alice either way.
   *
   * <p>Native resolves the RID straight to a record with no query, while a translated {@code
   * g.V(rid)} would compile an uncached MATCH plan every call (a RID-bearing walk sets {@code
   * cacheEligible=false}) — a net loss with no join to optimise. The translator therefore declines
   * the bare lookup, so both arms run natively and the on-arm no longer pays a per-call compile.
   * A RID start FOLLOWED by a hop still translates (the join is where MATCH can win).
   */
  @Test
  public void vertexByRidDeclinesOnBothArms() {
    assertDeclines(
        "g.V(rid)",
        t -> GremlinTraversalShapes.personByRid(t, aliceRid),
        List.of("element:" + aliceRid));
  }

  /**
   * Shape 2 — the {@code KNOWS} walk under {@code values} translates on, runs natively off, and
   * returns exactly Alice's two friends' first names on both arms.
   *
   * <p>Bob's edge to Dave is in the fixture precisely so that an over-emitting plan returns three
   * names here rather than two.
   */
  @Test
  public void knowsFirstNamesTranslateOnAndRunNativeOff() {
    assertTranslates(
        "g.V().hasLabel(Person).has(id).out(KNOWS).values(firstName)",
        t -> GremlinTraversalShapes.knowsFirstNames(t, ALICE),
        List.of("Bob", "Carol"));
  }

  /**
   * Shape 3 — the same walk under {@code count()} translates on, runs natively off, and returns 2
   * on both arms.
   */
  @Test
  public void knowsFirstNameCountTranslatesOnAndRunsNativeOff() {
    assertTranslates(
        "…out(KNOWS).values(firstName).count()",
        t -> GremlinTraversalShapes.knowsFirstNameCount(t, ALICE),
        List.of("2"));
  }

  /**
   * Shape 4 — the same walk under {@code fold()} translates on, runs natively off, and returns one
   * list of both names on either arm.
   *
   * <p>The newest recogniser this class covers, and the one that makes the run's classpath visible:
   * a {@code youtrackdb-core} without the {@code FoldStep} registry entry declines the shape and
   * {@link GremlinTraversalShapes#requireTranslated} fails here. That is what makes the invocation
   * note in this class's Javadoc load-bearing rather than tidy.
   */
  @Test
  public void knowsFirstNamesFoldedTranslateOnAndRunNativeOff() {
    assertTranslates(
        "…out(KNOWS).values(firstName).fold()",
        t -> GremlinTraversalShapes.knowsFirstNamesFolded(t, ALICE),
        List.of("[Bob, Carol]"));
  }

  /**
   * Shape 5 — IS1's complete person+city projection translates and returns Alice's profile
   * fields beside Zurich's id.
   *
   * <p>Berlin is in the fixture and Bob lives there, so a plan that drops the {@code IS_LOCATED_IN}
   * join and scans {@code Place} returns two rows where this expects one.
   */
  @Test
  public void is1PersonCityProfileTranslatesOnAndRunsNativeOff() {
    assertTranslates(
        "IS1 complete: …as(firstName,…,creationDate).out(IS_LOCATED_IN).as(cityId).select(…)",
        t -> GremlinTraversalShapes.is1PersonCityProfile(t, ALICE),
        List.of(
            "{birthday=date:0, browserUsed=Chrome, cityId=" + ZURICH
                + ", creationDate=date:" + POST_AT
                + ", firstName=Alice, gender=neutral, lastName=Aliceson,"
                + " locationIP=127.0.0.1}"));
  }

  /**
   * Shape 6 — IS3's friend columns translate, and come back sorted by {@code firstName}.
   *
   * <p>Compared in stream order, not as a multiset: the {@code ORDER BY} is the part of IS3 this
   * shape keeps, and a multiset comparison would pass on a plan that returned the right rows in the
   * wrong order.
   */
  @Test
  public void is3FriendsWithNamesTranslateOnAndRunNativeOffInSortedOrder() {
    assertTranslatesInOrder(
        "IS3 reduced: …outE(KNOWS).inV().order().by(firstName).valueMap(id, firstName, lastName)",
        t -> GremlinTraversalShapes.is3FriendsWithNames(t, ALICE),
        List.of(
            "{firstName=[Bob], id=[" + BOB + "], lastName=[Bobson]}",
            "{firstName=[Carol], id=[" + CAROL + "], lastName=[Carolson]}"));
  }

  /**
   * Shape 7 — IS5 translates whole and returns the post's author, Bob.
   *
   * <p>Carol authored the comment that replies to this post, so a plan that walks {@code REPLY_OF}
   * as well as {@code HAS_CREATOR} returns her too.
   */
  @Test
  public void is5MessageCreatorTranslatesOnAndRunsNativeOff() {
    assertTranslates(
        "IS5: …hasLabel(Message).has(id).out(HAS_CREATOR).valueMap(id, firstName, lastName)",
        t -> GremlinTraversalShapes.is5MessageCreator(t, POST),
        List.of("{firstName=[Bob], id=[" + BOB + "], lastName=[Bobson]}"));
  }

  /**
   * IS2 reduced — Bob's messages newest first. Only the post is his; Carol's comment must not
   * appear. Compared in stream order because the shape keeps {@code ORDER BY creationDate DESC}.
   */
  @Test
  public void is2PersonMessagesTranslatesOnAndRunsNativeOffInDateOrder() {
    assertTranslatesInOrder(
        "IS2 reduced: …in(HAS_CREATOR).hasLabel(Message).order().by(creationDate, desc)"
            + ".valueMap(id, content, creationDate)",
        t -> GremlinTraversalShapes.is2PersonMessages(t, BOB),
        List.of(
            "{content=[post-1000], creationDate=[date:" + POST_AT + "], id=[" + POST + "]}"));
  }

  /**
   * IS6 reduced — the post's forum id/title and moderator id/name fields. SQL climbs
   * {@code REPLY_OF} from any Message; this shape starts at the Post and walks
   * {@code in(CONTAINER_OF)}. Labels bind on {@code has()} like IS1, not on {@code hasLabel}.
   */
  @Test
  public void is6ForumOfPostTranslatesOnAndRunsNativeOff() {
    assertTranslates(
        "IS6 reduced: …in(CONTAINER_OF).as(forumId, forumTitle).out(HAS_MODERATOR)"
            + ".as(moderator…).select(…)",
        t -> GremlinTraversalShapes.is6ForumOfPost(t, POST),
        List.of(
            "{forumId=" + FORUM + ", forumTitle=Wall, moderatorFirstName=Alice,"
                + " moderatorId=" + ALICE + ", moderatorLastName=Aliceson}"));
  }

  /**
   * IS7 reduced — direct replies + authors (comment + reply-author columns). Carol replied;
   * Alice did not, so a plan that walked {@code KNOWS} instead of {@code REPLY_OF} would return
   * her.
   */
  @Test
  public void is7RepliesWithAuthorsTranslatesOnAndRunsNativeOff() {
    assertTranslates(
        "IS7 reduced: …in(REPLY_OF).as(comment…).out(HAS_CREATOR).as(replyAuthor…).select(…)",
        t -> GremlinTraversalShapes.is7RepliesWithAuthors(t, POST),
        List.of(
            "{commentContent=c-1001, commentCreationDate=date:" + COMMENT_AT
                + ", commentId=" + COMMENT
                + ", replyAuthorFirstName=Carol, replyAuthorId=" + CAROL
                + ", replyAuthorLastName=Carolson}"));
  }

  /**
   * IC2 reduced — Alice's friends' messages before {@link #IC2_MAX_DATE}, newest first. Carol's
   * comment then Bob's post, with friend + message columns. Dave has no messages, so a plan that
   * ignored {@code KNOWS} and scanned {@code Message} would still pass if it also ignored the date
   * filter; the two-row ordered list is the discriminant.
   */
  @Test
  public void ic2FriendsMessagesOrderedTranslatesOnAndRunsNativeOffInDateOrder() {
    assertTranslatesInOrder(
        "IC2 reduced: …out(KNOWS).as(person…).in(HAS_CREATOR).as(message…)"
            + ".has(creationDate, lt).order().by(creationDate, desc).limit.select(…)",
        t -> GremlinTraversalShapes.ic2FriendsMessagesOrdered(t, ALICE, new Date(IC2_MAX_DATE)),
        List.of(
            "{firstName=Carol, lastName=Carolson, messageContent=c-1001,"
                + " messageCreationDate=date:" + COMMENT_AT + ", messageId=" + COMMENT
                + ", personId=" + CAROL + "}",
            "{firstName=Bob, lastName=Bobson, messageContent=post-1000,"
                + " messageCreationDate=date:" + POST_AT + ", messageId=" + POST
                + ", personId=" + BOB + "}"));
  }

  /**
   * IC7 reduced — first names of people who liked Bob's messages. Alice liked the post; a plan
   * that walked {@code KNOWS} instead of {@code LIKES} would also return Carol and Dave.
   */
  @Test
  public void ic7LikersTranslatesOnAndRunsNativeOff() {
    assertTranslates(
        "IC7 reduced: …in(HAS_CREATOR).in(LIKES).values(firstName)",
        t -> GremlinTraversalShapes.ic7Likers(t, BOB),
        List.of("Alice"));
  }

  /**
   * IC8 reduced — comments that reply to Bob's messages, newest first, with comment + author
   * columns. Carol's comment replies to Bob's post; a plan that returned the post itself would
   * fail the reply walk.
   */
  @Test
  public void ic8RecentRepliesOrderedTranslatesOnAndRunsNativeOffInDateOrder() {
    assertTranslatesInOrder(
        "IC8 reduced: …in(HAS_CREATOR).in(REPLY_OF).as(comment…).out(HAS_CREATOR)"
            + ".as(person…).order().by(select(commentCreationDate)).limit.select(…)",
        t -> GremlinTraversalShapes.ic8RecentRepliesOrdered(t, BOB),
        List.of(
            "{commentContent=c-1001, commentCreationDate=date:" + COMMENT_AT
                + ", commentId=" + COMMENT
                + ", firstName=Carol, lastName=Carolson, personId=" + CAROL + "}"));
  }

  /**
   * IC11 reduced — companies of Alice's friends located in China (friend + organisation columns).
   * Bob works at Acme in China; Carol does not work anywhere, so a plan that skipped
   * {@code WORK_AT} returns nothing and a plan that skipped the country filter still has only
   * Acme in this fixture.
   */
  @Test
  public void ic11FriendsCompaniesInCountryTranslatesOnAndRunsNativeOff() {
    assertTranslates(
        "IC11 reduced: …out(KNOWS).as(person…).out(WORK_AT).as(organizationName)"
            + ".out(IS_LOCATED_IN).has(name, China).select(…)",
        t -> GremlinTraversalShapes.ic11FriendsCompaniesInCountry(t, ALICE, "China"),
        List.of(
            "{firstName=Bob, lastName=Bobson, organizationName=Acme, personId=" + BOB + "}"));
  }

  /**
   * Shape 8 — the two-hop {@code KNOWS} walk translates and returns one name per two-hop path.
   *
   * <p>Three paths from Alice: Bob→Dave, Bob→Alice (the cycle, so Alice appears as her own
   * friend-of-friend) and Carol→Erin. A plan that deduplicated vertices would return two.
   */
  @Test
  public void twoHopKnowsTranslatesOnAndRunsNativeOff() {
    assertTranslates(
        "…out(KNOWS).out(KNOWS).values(firstName)",
        t -> GremlinTraversalShapes.twoHopKnows(t, ALICE),
        List.of("Alice", "Dave", "Erin"));
  }

  /**
   * Shape 9 — the two-hop walk with a filter on the intermediate hop translates and keeps only the
   * paths through Bob.
   *
   * <p>Alice's other friend Carol leads to Erin, so a plan that applied the {@code firstName} filter
   * to the wrong alias — or after the second hop — would return Erin as well.
   */
  @Test
  public void knowsFilteredByFriendFirstNameTranslatesOnAndRunsNativeOff() {
    assertTranslates(
        "…out(KNOWS).has(firstName, Bob).out(KNOWS).values(firstName)",
        t -> GremlinTraversalShapes.knowsFilteredByFriendFirstName(t, ALICE, "Bob"),
        List.of("Alice", "Dave"));
  }

  /**
   * Shape 10 — three hops with a {@code where()} back-reference translate, and the filter drops
   * exactly the two paths that return to the intermediate friend.
   *
   * <p>Six three-hop paths leave Alice; Alice→Bob→Alice→Bob and Alice→Carol→Erin→Carol end on the
   * friend they passed through, so {@code where(P.neq("f"))} removes them and four names remain. An
   * unfiltered plan returns six, with Bob and Carol twice.
   */
  @Test
  public void threeHopKnowsExcludingIntermediateTranslatesOnAndRunsNativeOff() {
    assertTranslates(
        "…out(KNOWS).as(f).out(KNOWS).out(KNOWS).where(neq(f)).values(firstName)",
        t -> GremlinTraversalShapes.threeHopKnowsExcludingIntermediate(t, ALICE),
        List.of("Alice", "Bob", "Carol", "Dave"));
  }

  /**
   * Shape 11 — IS1's full projection translates on, runs natively off, and returns Alice's first
   * name beside Zurich's id on both arms.
   *
   * <p>The shape was written as a declining one, on the reading that {@code select("p", "city")}
   * could not resolve a user {@code as(...)} label. Both labels resolve now that the {@code has}
   * recogniser binds them, so the group flipped and the assertion with it — which is the tripwire
   * the declining group was built to fire.
   *
   * <p>The two arms agree here. They need not everywhere: a spelling whose label is dropped by the
   * native graph-step fold answers {@code []} natively while the translated arm answers correctly,
   * and that family is asserted against a hand-computed oracle in {@code core} rather than against
   * native. This shape is not in it — {@code as("p")} sits on the {@code has} step and survives.
   */
  @Test
  public void is1FullProfileTranslatesOnAndRunsNativeOff() {
    assertTranslates(
        "IS1 full: …as(p).out(IS_LOCATED_IN).as(city).select(p, city).by(firstName).by(id)",
        t -> GremlinTraversalShapes.is1FullProfile(t, ALICE),
        List.of("{city=" + ZURICH + ", p=Alice}"));
  }

  /**
   * Shape 12 — {@code groupCount().by(lastName)} translates and returns one group per friend.
   *
   * <p>Erin's four friends have four distinct last names, so every count is 1 and a plan that
   * grouped on the wrong key collapses them into fewer entries.
   */
  @Test
  public void knowsGroupCountByLastNameTranslatesOnAndRunsNativeOff() {
    assertTranslates(
        "…out(KNOWS).groupCount().by(lastName)",
        t -> GremlinTraversalShapes.knowsGroupCountByLastName(t, ERIN),
        List.of("{Aliceson=1, Bobson=1, Carolson=1, Daveson=1}"));
  }

  /**
   * Shape 13 — the hash anti-join translates and keeps only the friends not located in the named
   * place.
   *
   * <p>Alice's friends are Bob and Carol. Bob is located in Berlin and Carol nowhere, so excluding
   * friends located in Berlin leaves Carol. A plan that applied the {@code name} filter to the
   * wrong alias, or negated the whole pattern rather than the anti-join, would return Bob as well
   * or drop Carol.
   */
  @Test
  public void friendsNotLocatedInPlaceTranslatesOnAndRunsNativeOff() {
    assertTranslates(
        "…out(KNOWS).not(out(IS_LOCATED_IN).has(name, Berlin)).values(firstName)",
        t -> GremlinTraversalShapes.friendsNotLocatedInPlace(t, ALICE, "Berlin"),
        List.of("Carol"));
  }

  /**
   * Shape 14 — the mutual-friend triangle translates and returns the start person once per closing
   * three-hop cycle.
   *
   * <p>The one cycle back to Alice within three {@code KNOWS} hops is Alice→Carol→Erin→Alice, so
   * {@code where(P.eq("start"))} keeps a single path and {@code values("firstName")} reads the
   * closing vertex, Alice. An unclosed plan would return every three-hop endpoint instead.
   */
  @Test
  public void mutualFriendTriangleTranslatesOnAndRunsNativeOff() {
    assertTranslates(
        "…as(start).out(KNOWS).out(KNOWS).out(KNOWS).where(eq(start)).values(firstName)",
        t -> GremlinTraversalShapes.mutualFriendTriangle(t, ALICE),
        List.of("Alice"));
  }

  // -------------------------------------------------------------------------------------------
  // Declining shapes: no boundary step on either arm. Each assertion is a tripwire — it fails the
  // day a recogniser claims the shape, which is when the benchmark's recorded baseline changes
  // meaning from "decline-path overhead" to "MATCH against native".
  // -------------------------------------------------------------------------------------------

  /**
   * {@code order().by(firstName).range(1, 3)} translates and returns the second and third of
   * Erin's four friends in sorted order.
   *
   * <p>Compared in stream order. Erin's friends sort to Alice, Bob, Carol, Dave, so the page is
   * Bob then Carol. This branch accepts same-boundary {@code order}+{@code range}; failing here
   * means the ordered-slice recogniser declined again and the shape fell back to native on both
   * arms (still correct rows, but no longer measuring MATCH).
   */
  @Test
  public void knowsOrderedPageTranslatesOnAndRunsNativeOffInSortedOrder() {
    assertTranslatesInOrder(
        "…out(KNOWS).order().by(firstName).range(1, 3).values(firstName)",
        t -> GremlinTraversalShapes.knowsOrderedPage(t, ERIN),
        List.of("Bob", "Carol"));
  }

  /**
   * Shape IS3 whole declines on both arms and returns each friend with the friendship date in
   * {@code firstName} order either way.
   *
   * <p>The {@code as("k")} label on {@code outE(KNOWS)} would bind to the edge-as-node vertex alias,
   * so {@code select("k").by("creationDate")} would read the target vertex rather than the
   * friendship edge — runtime-incorrect, so the shape falls back to native on both arms. Failing
   * here means a recogniser has started claiming an edge-alias select again.
   */
  @Test
  public void is3FriendsWithDatesDeclinesOnBothArmsInSortedOrder() {
    assertDeclinesInOrder(
        "IS3 full: …outE(KNOWS).as(k).inV().as(friend).order().by(firstName)"
            + ".select(k, friend).by(creationDate).by(firstName)",
        t -> GremlinTraversalShapes.is3FriendsWithDates(t, ALICE),
        List.of(
            "{friend=Bob, k=date:" + KNOWS_ALICE_BOB_AT + "}",
            "{friend=Carol, k=date:" + KNOWS_ALICE_CAROL_AT + "}"));
  }

  /**
   * IC1's variable-depth {@code repeat()} walk declines on both arms and returns every person
   * reachable from Alice within three hops.
   *
   * <p>All five, because Bob→Alice and Erin's four out-edges put the whole fixture within three hops
   * of Alice. Failing here means {@code RepeatDeclineStrategy} stopped vetoing — which on the
   * grateful-dead fixture is what made {@code repeat(out()).times(8)} non-terminating, so a failure
   * here is a regression to investigate rather than a baseline to re-read.
   */
  @Test
  public void repeatKnowsToThreeHopsDeclinesOnBothArms() {
    assertDeclines(
        "IC1-shaped: …repeat(out(KNOWS)).times(3).emit().dedup().values(firstName)",
        t -> GremlinTraversalShapes.repeatKnowsToThreeHops(t, ALICE),
        List.of("Alice", "Bob", "Carol", "Dave", "Erin"));
  }

  /**
   * IS4's {@code coalesce} projection declines on both arms and falls back to {@code content},
   * because the post's {@code imageFile} is unset.
   *
   * <p>Failing here means {@code CoalesceStep} has a recogniser.
   */
  @Test
  public void coalesceMessageContentDeclinesOnBothArms() {
    assertDeclines(
        "IS4-shaped: …hasLabel(Message).has(id).coalesce(values(imageFile), values(content))",
        t -> GremlinTraversalShapes.coalesceMessageContent(t, POST),
        List.of("post-1000"));
  }

  /**
   * IS7's optional hop declines on both arms and returns the one friend of the comment's author.
   *
   * <p>Carol wrote the comment and knows only Erin, so the optional hop is productive here and the
   * result is Erin rather than Carol. Failing here means Phase 2's optional hop has landed.
   */
  @Test
  public void optionalFriendOfCreatorDeclinesOnBothArms() {
    assertDeclines(
        "IS7-shaped: …out(HAS_CREATOR).optional(out(KNOWS)).values(firstName)",
        t -> GremlinTraversalShapes.optionalFriendOfCreator(t, COMMENT),
        List.of("Erin"));
  }

  // -------------------------------------------------------------------------------------------
  // Arm drivers.
  // -------------------------------------------------------------------------------------------

  /** How the two arms' results are compared against the hand-computed expectation. */
  private enum Comparison {
    /** Sorted before comparing: MATCH reorders, so stream order is not comparable. */
    AS_MULTISET,
    /** Compared as emitted: for shapes whose {@code ORDER BY} is the point. */
    IN_ORDER
  }

  /**
   * Asserts a translating shape: boundary step present with the kill-switch on, absent with it off
   * over a non-empty native pipeline, and {@code expected} on both arms as a multiset.
   */
  private static void assertTranslates(
      String shape,
      Function<YTDBGraphTraversalSource, Traversal<?, ?>> builder,
      List<String> expected) {
    runBothArms(shape, true, Comparison.AS_MULTISET, builder, expected);
  }

  /** {@link #assertTranslates} with the two arms compared in stream order. */
  private static void assertTranslatesInOrder(
      String shape,
      Function<YTDBGraphTraversalSource, Traversal<?, ?>> builder,
      List<String> expected) {
    runBothArms(shape, true, Comparison.IN_ORDER, builder, expected);
  }

  /**
   * Asserts a declining shape: no boundary step on <em>either</em> arm, and {@code expected} on both
   * as a multiset.
   */
  private static void assertDeclines(
      String shape,
      Function<YTDBGraphTraversalSource, Traversal<?, ?>> builder,
      List<String> expected) {
    runBothArms(shape, false, Comparison.AS_MULTISET, builder, expected);
  }

  /** {@link #assertDeclines} with the two arms compared in stream order. */
  private static void assertDeclinesInOrder(
      String shape,
      Function<YTDBGraphTraversalSource, Traversal<?, ?>> builder,
      List<String> expected) {
    runBothArms(shape, false, Comparison.IN_ORDER, builder, expected);
  }

  /**
   * Runs one shape on both kill-switch positions and checks engagement and result on each.
   *
   * <p>For a translating shape the on-arm runs first on purpose: its {@code requireTranslated}
   * throws before the off-arm is reached, so the off-arm's "absent" reading can only be produced by
   * a traversal the on-arm has already shown the translator engages on.
   *
   * <p>A declining shape has no such on-arm, and "absent on both arms" is exactly what a
   * kill-switch that never flipped would also produce. {@link #requireKillSwitchReachesTraversals}
   * closes that hole before either arm runs.
   *
   * @param translating {@code true} for a shape expected to carry a boundary step on the on-arm,
   *     {@code false} for one expected to decline on both arms
   */
  private static void runBothArms(
      String shape,
      boolean translating,
      Comparison comparison,
      Function<YTDBGraphTraversalSource, Traversal<?, ?>> builder,
      List<String> expected) {
    var flagBefore =
        GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED.getValueAsBoolean();
    try {
      if (!translating) {
        requireKillSwitchReachesTraversals(shape);
      }
      var onResults = runArm(shape, true, translating, comparison, builder);
      var offResults = runArm(shape, false, false, comparison, builder);

      assertEquals(
          shape + ": translator-off must return the hand-computed result; a mismatch here means"
              + " the fixture is not the graph the assertions were written against",
          expected,
          offResults);
      assertEquals(
          shape + ": translator-on must return the same result as translator-off",
          expected,
          onResults);
    } finally {
      GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED.setValue(flagBefore);
    }
  }

  /**
   * Throws unless flipping the kill-switch on demonstrably changes a traversal in this session.
   *
   * <p>Runs before a declining shape's arms. Without it, "no boundary step with the flag on" reads
   * the same whether the shape declined or the flag never took effect, and every declining-shape
   * assertion in this class would pass against a translator that was never installed. The witness
   * is {@link GremlinTraversalShapes#knowsFirstNames}, whose every step has been recognised since
   * long before the terminators.
   *
   * @param shape the declining shape about to be checked, so a failure names what it invalidates
   */
  private static void requireKillSwitchReachesTraversals(String shape) {
    GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED.setValue(true);
    g.executeInTx(t -> {
      var witness = GremlinTraversalShapes.knowsFirstNames(t, ALICE).asAdmin();
      witness.applyStrategies();
      GremlinTraversalShapes.requireTranslated(
          "kill-switch witness for declining shape '" + shape + "'", witness);
    });
  }

  /**
   * Flips the kill-switch, builds the shape, applies strategies, checks engagement matches what the
   * arm expects, and returns the result.
   *
   * <p>The engagement check throws rather than using a Java {@code assert}: the JMH launcher runs
   * without {@code -ea}, so an assert-based check would hold here and vanish under measurement,
   * and the two callers must not be able to drift apart on that point.
   *
   * @param expectTranslated whether this arm expects the boundary step; false both for a
   *     translating shape's off-arm and for either arm of a declining shape
   */
  private static List<String> runArm(
      String shape,
      boolean translatorEnabled,
      boolean expectTranslated,
      Comparison comparison,
      Function<YTDBGraphTraversalSource, Traversal<?, ?>> builder) {
    GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED.setValue(translatorEnabled);
    return g.computeInTx(t -> {
      var admin = builder.apply(t).asAdmin();
      admin.applyStrategies();
      var armLabel = shape + " [translator " + (translatorEnabled ? "on" : "off") + "]";
      if (expectTranslated) {
        GremlinTraversalShapes.requireTranslated(armLabel, admin);
      } else {
        GremlinTraversalShapes.requireNotTranslated(armLabel, admin);
      }
      return render(admin.toList(), comparison);
    });
  }

  /**
   * Renders a result list to strings, sorted or as emitted.
   *
   * <p>Element results are keyed on their RID rather than on {@code toString()}, which for a vertex
   * would carry no identity. Dates render as epoch milliseconds so an expected value can be written
   * from the fixture constant without depending on the JVM's default time zone.
   */
  private static List<String> render(List<?> results, Comparison comparison) {
    var rendered = results.stream().map(LdbcGremlinShapeTranslationTest::render);
    return comparison == Comparison.AS_MULTISET ? rendered.sorted().toList() : rendered.toList();
  }

  private static String render(Object value) {
    if (value instanceof Element element) {
      return "element:" + element.id();
    }
    if (value instanceof Date date) {
      return "date:" + date.getTime();
    }
    if (value instanceof Map<?, ?> map) {
      // Entries sorted: a map's iteration order is not part of the result the arms must agree on.
      return map.entrySet().stream()
          .map(entry -> render(entry.getKey()) + "=" + render(entry.getValue()))
          .sorted()
          .collect(Collectors.joining(", ", "{", "}"));
    }
    if (value instanceof Collection<?> collection) {
      return collection.stream()
          .map(LdbcGremlinShapeTranslationTest::render)
          .sorted()
          .collect(Collectors.joining(", ", "[", "]"));
    }
    return String.valueOf(value);
  }

  // -------------------------------------------------------------------------------------------
  // Fixture builders.
  // -------------------------------------------------------------------------------------------

  private static void insertPerson(YTDBGraphTraversalSource t, long id, String firstName) {
    t.yql(
        "INSERT INTO Person SET id = :id, firstName = :firstName, lastName = :lastName,"
            + " birthday = :birthday, locationIP = :ip, browserUsed = :browser,"
            + " gender = :gender, creationDate = :cd",
        "id", id,
        "firstName", firstName,
        "lastName", firstName + "son",
        "birthday", new Date(0L),
        "ip", "127.0.0.1",
        "browser", "Chrome",
        "gender", "neutral",
        "cd", new Date(POST_AT)).iterate();
  }

  private static void insertPlace(YTDBGraphTraversalSource t, long id, String name) {
    t.yql("INSERT INTO Place SET id = :id, name = :name", "id", id, "name", name).iterate();
  }

  /** Inserts a {@code Post} or {@code Comment}; {@code imageFile} stays unset on purpose. */
  private static void insertMessage(
      YTDBGraphTraversalSource t, String className, long id, String content, long creationDate) {
    t.yql(
        "INSERT INTO " + className
            + " SET id = :id, content = :content, creationDate = :creationDate",
        "id", id, "content", content, "creationDate", new Date(creationDate)).iterate();
  }

  private static void createKnows(
      YTDBGraphTraversalSource t, long from, long to, long creationDate) {
    t.yql(
        "CREATE EDGE KNOWS FROM (SELECT FROM Person WHERE id = :from)"
            + " TO (SELECT FROM Person WHERE id = :to) SET creationDate = :creationDate",
        "from", from, "to", to, "creationDate", new Date(creationDate)).iterate();
  }

  private static void createLocatedIn(YTDBGraphTraversalSource t, long personId, long placeId) {
    t.yql(
        "CREATE EDGE IS_LOCATED_IN FROM (SELECT FROM Person WHERE id = :person)"
            + " TO (SELECT FROM Place WHERE id = :place)",
        "person", personId, "place", placeId).iterate();
  }

  private static void createHasCreator(
      YTDBGraphTraversalSource t, long messageId, long personId) {
    t.yql(
        "CREATE EDGE HAS_CREATOR FROM (SELECT FROM Message WHERE id = :message)"
            + " TO (SELECT FROM Person WHERE id = :person)",
        "message", messageId, "person", personId).iterate();
  }

  private static void createReplyOf(YTDBGraphTraversalSource t, long commentId, long messageId) {
    t.yql(
        "CREATE EDGE REPLY_OF FROM (SELECT FROM Message WHERE id = :comment)"
            + " TO (SELECT FROM Message WHERE id = :message)",
        "comment", commentId, "message", messageId).iterate();
  }

  private static void insertForum(YTDBGraphTraversalSource t, long id, String title) {
    requireCreated(
        "INSERT INTO Forum",
        t.yql(
            "INSERT INTO Forum SET id = :id, title = :title, creationDate = :cd",
            "id", id, "title", title, "cd", POST_AT).toList());
  }

  private static void insertOrganisation(YTDBGraphTraversalSource t, long id, String name) {
    requireCreated(
        "INSERT INTO Organisation",
        t.yql(
            "INSERT INTO Organisation SET id = :id, type = :type, name = :name",
            "id", id, "type", "company", "name", name).toList());
  }

  private static void createContainerOf(
      YTDBGraphTraversalSource t, long forumId, long messageId) {
    createEdge(t, "CONTAINER_OF", "Forum", forumId, "Post", messageId);
  }

  private static void createHasModerator(
      YTDBGraphTraversalSource t, long forumId, long personId) {
    createEdge(t, "HAS_MODERATOR", "Forum", forumId, "Person", personId);
  }

  private static void createLikes(YTDBGraphTraversalSource t, long personId, long messageId) {
    createEdge(t, "LIKES", "Person", personId, "Message", messageId);
  }

  private static void createWorkAt(YTDBGraphTraversalSource t, long personId, long orgId) {
    requireCreated(
        "CREATE EDGE WORK_AT",
        t.yql(
            "CREATE EDGE WORK_AT FROM (SELECT FROM Person WHERE id = :from)"
                + " TO (SELECT FROM Organisation WHERE id = :to) SET workFrom = :wf",
            "from", personId, "to", orgId, "wf", 2008).toList());
  }

  private static void createOrgLocatedIn(
      YTDBGraphTraversalSource t, long orgId, long placeId) {
    createEdge(t, "IS_LOCATED_IN", "Organisation", orgId, "Place", placeId);
  }

  private static void createEdge(
      YTDBGraphTraversalSource t,
      String edgeLabel,
      String fromClass,
      long fromId,
      String toClass,
      long toId) {
    requireCreated(
        "CREATE EDGE " + edgeLabel,
        t.yql(
            "CREATE EDGE " + edgeLabel
                + " FROM (SELECT FROM " + fromClass + " WHERE id = :from)"
                + " TO (SELECT FROM " + toClass + " WHERE id = :to)",
            "from", fromId, "to", toId).toList());
  }

  private static void requireCreated(String what, List<?> created) {
    if (created.isEmpty()) {
      throw new IllegalStateException(what + " produced no rows");
    }
  }
}
