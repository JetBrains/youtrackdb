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
 * arms, so a broken A/B is caught by an ordinary build instead of on Hetzner.
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
 * <p>Five people, two places and two messages — the smallest graph that distinguishes the shapes:
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
 *       the IS4 / IS5 / IS7 shapes.
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

      insertMessage(t, "Post", POST, "post-1000", 1_600_000_100_000L);
      insertMessage(t, "Comment", COMMENT, "c-1001", 1_600_000_200_000L);
      createHasCreator(t, POST, BOB);
      createHasCreator(t, COMMENT, CAROL);
      createReplyOf(t, COMMENT, POST);
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
   * Shape 1 — {@code g.V(rid)} translates to one boundary step with the kill-switch on, runs
   * natively with it off, and returns Alice either way.
   *
   * <p>This is the shape whose on-arm can be slower than its off-arm, so it is also the shape whose
   * A/B is worth measuring at all; a harness that failed to translate it would report parity and
   * hide that.
   */
  @Test
  public void vertexByRidTranslatesOnAndRunsNativeOff() {
    assertTranslates(
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
   * Shape 5 — IS1's join and city column translate, and return Zurich's id and name for Alice.
   *
   * <p>Berlin is in the fixture and Bob lives there, so a plan that drops the {@code IS_LOCATED_IN}
   * join and scans {@code Place} returns two rows where this expects one.
   */
  @Test
  public void is1PersonCityProfileTranslatesOnAndRunsNativeOff() {
    assertTranslates(
        "IS1 reduced: …has(id).out(IS_LOCATED_IN).valueMap(id, name)",
        t -> GremlinTraversalShapes.is1PersonCityProfile(t, ALICE),
        List.of("{id=[" + ZURICH + "], name=[Zurich]}"));
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

  // -------------------------------------------------------------------------------------------
  // Declining shapes: no boundary step on either arm. Each assertion is a tripwire — it fails the
  // day a recogniser claims the shape, which is when the benchmark's recorded baseline changes
  // meaning from "decline-path overhead" to "MATCH against native".
  // -------------------------------------------------------------------------------------------

  /**
   * {@code order().by(firstName).range(1, 3)} declines on both arms and returns the second and
   * third of Erin's four friends either way.
   *
   * <p>Compared in stream order. Erin's friends sort to Alice, Bob, Carol, Dave, so the page is Bob
   * then Carol, and a run that paged before sorting returns a different pair. Both arms produce it
   * natively: a slice behind a captured {@code ORDER BY} declines, which {@code core}'s
   * {@code OrderRangeStepRecogniserTest} pins for this exact spelling alongside a translating
   * control.
   *
   * <p>Failing here means a recogniser now claims a slice after a sort, so this shape's recorded
   * number stops describing the decline path and starts describing a MATCH plan.
   */
  @Test
  public void knowsOrderedPageDeclinesOnBothArmsInSortedOrder() {
    assertDeclinesInOrder(
        "…out(KNOWS).order().by(firstName).range(1, 3).values(firstName)",
        t -> GremlinTraversalShapes.knowsOrderedPage(t, ERIN),
        List.of("Bob", "Carol"));
  }

  /**
   * Shape 13 — IS3 whole translates and returns each friend with the friendship date in {@code
   * firstName} order.
   */
  @Test
  public void is3FriendsWithDatesTranslatesOnAndRunNativeOffInSortedOrder() {
    assertTranslatesInOrder(
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
        "INSERT INTO Person SET id = :id, firstName = :firstName, lastName = :lastName",
        "id", id, "firstName", firstName, "lastName", firstName + "son").iterate();
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
}
