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
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Drives the four {@link GremlinTraversalShapes} builders the JMH harness measures, on both
 * kill-switch arms, so a broken A/B is caught by an ordinary build instead of on Hetzner.
 *
 * <p>The {@code @Benchmark} bodies themselves are dataset-bound and cannot run here. What can run
 * is the part that silently goes wrong: whether flipping
 * {@code QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED} actually changes which pipeline each shape
 * compiles to. If it does not, the benchmark's two arms measure the same path and report a
 * difference of zero, which reads as a clean null result rather than as a broken harness.
 *
 * <p><b>Why the off-arm assertion is not run on its own.</b> "No {@code AbstractMatchPlanStep} in
 * the step list" is also what a traversal that never built, a closed session, or an empty fixture
 * produces. Each test here therefore runs the on-arm first — where {@link
 * GremlinTraversalShapes#requireTranslated} throws unless the boundary step is demonstrably
 * present — and only then the off-arm. Both arms are additionally checked against a hand-computed
 * result, so neither can pass over a degenerate graph.
 *
 * <p>The fixture is the smallest graph that distinguishes the shapes: Alice knows Bob and Carol,
 * and Bob knows Dave. The Bob-Dave edge is there so a plan that over-emits KNOWS targets returns
 * three names where the assertion expects two.
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
      createKnows(t, ALICE, BOB);
      createKnows(t, ALICE, CAROL);
      createKnows(t, BOB, DAVE);
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
    assertBothArms(
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
    assertBothArms(
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
    assertBothArms(
        "…out(KNOWS).values(firstName).count()",
        t -> GremlinTraversalShapes.knowsFirstNameCount(t, ALICE),
        List.of("2"));
  }

  /**
   * Shape 4 — the same walk under {@code fold()} translates on, runs natively off, and returns one
   * list of both names on either arm.
   *
   * <p>This is the shape Track 11 adds the recogniser for. Until {@code FoldStep} is registered,
   * the on-arm declines and {@link GremlinTraversalShapes#requireTranslated} fails here — which is
   * the point of running the harness in-track rather than compiling it: the assertion is the gate
   * on the terminator, not a restatement of it.
   */
  @Test
  public void knowsFirstNamesFoldedTranslateOnAndRunNativeOff() {
    assertBothArms(
        "…out(KNOWS).values(firstName).fold()",
        t -> GremlinTraversalShapes.knowsFirstNamesFolded(t, ALICE),
        List.of("[Bob, Carol]"));
  }

  /**
   * Runs one shape on both arms and checks four things: the boundary step is present with the
   * kill-switch on, absent with it off over a non-empty native pipeline, and each arm returns
   * {@code expected}.
   *
   * <p>The on-arm runs first on purpose. Its {@code requireTranslated} throws before the off-arm
   * is reached, so the off-arm's "absent" reading can only be produced by a traversal that the
   * on-arm has already shown the translator does engage on.
   */
  private static void assertBothArms(
      String shape,
      Function<YTDBGraphTraversalSource, Traversal<?, ?>> builder,
      List<String> expected) {
    var flagBefore =
        GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED.getValueAsBoolean();
    try {
      var onResults = runArm(shape, true, builder);
      var offResults = runArm(shape, false, builder);

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
   * Flips the kill-switch, builds the shape, applies strategies, checks engagement matches the arm,
   * and returns the normalised result.
   *
   * <p>The engagement check throws rather than using a Java {@code assert}: the JMH launcher runs
   * without {@code -ea}, so an assert-based check would hold here and vanish under measurement,
   * and the two callers must not be able to drift apart on that point.
   */
  private static List<String> runArm(
      String shape,
      boolean translatorEnabled,
      Function<YTDBGraphTraversalSource, Traversal<?, ?>> builder) {
    GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED.setValue(translatorEnabled);
    return g.computeInTx(t -> {
      var admin = builder.apply(t).asAdmin();
      admin.applyStrategies();
      if (translatorEnabled) {
        GremlinTraversalShapes.requireTranslated(shape, admin);
      } else {
        GremlinTraversalShapes.requireNotTranslated(shape, admin);
      }
      return normalise(admin.toList());
    });
  }

  /**
   * Renders a result list to sorted strings so the two arms are compared as multisets.
   *
   * <p>MATCH reorders, so stream order is not comparable between the arms; sorting keeps
   * multiplicity while dropping order. Element results are keyed on their RID rather than on
   * {@code toString()}, which for a vertex would carry no identity.
   */
  private static List<String> normalise(List<?> results) {
    return results.stream()
        .map(LdbcGremlinShapeTranslationTest::render)
        .sorted()
        .toList();
  }

  private static String render(Object value) {
    if (value instanceof Element element) {
      return "element:" + element.id();
    }
    if (value instanceof Collection<?> collection) {
      return collection.stream()
          .map(LdbcGremlinShapeTranslationTest::render)
          .sorted()
          .collect(Collectors.joining(", ", "[", "]"));
    }
    return String.valueOf(value);
  }

  private static void insertPerson(YTDBGraphTraversalSource t, long id, String firstName) {
    t.yql(
        "INSERT INTO Person SET id = :id, firstName = :firstName, lastName = :lastName",
        "id", id, "firstName", firstName, "lastName", firstName + "son").iterate();
  }

  private static void createKnows(YTDBGraphTraversalSource t, long from, long to) {
    t.yql(
        "CREATE EDGE KNOWS FROM (SELECT FROM Person WHERE id = :from)"
            + " TO (SELECT FROM Person WHERE id = :to)",
        "from", from, "to", to).iterate();
  }
}
