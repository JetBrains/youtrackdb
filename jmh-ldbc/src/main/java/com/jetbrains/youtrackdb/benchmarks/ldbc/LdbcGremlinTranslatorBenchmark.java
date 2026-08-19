package com.jetbrains.youtrackdb.benchmarks.ldbc;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Translator-on against translator-off over the Gremlin shapes in {@link GremlinTraversalShapes}, on
 * the LDBC schema.
 *
 * <p>The A/B axis is a JMH {@code @Param} on {@link TranslatorArm}, so each arm gets its own
 * forks and the kill-switch is flipped once per trial in-process. Nothing is set through
 * {@code -DargLine=}: on some modules a CLI {@code argLine} replaces the POM's block wholesale
 * (taking {@code -ea}, the heap sizing and every {@code --add-opens} with it) and on others plugin
 * configuration wins and the CLI value is inert. Neither failure is visible in the numbers.
 *
 * <p><b>Two groups of benchmark, read differently.</b> A translating shape's delta is MATCH against
 * the native pipeline. A declining shape's delta is the cost of the decline itself —
 * {@code GremlinToMatchStrategy} runs on the on-arm, walks the traversal and only then hands it back
 * — and doubles as the baseline for the day a recogniser claims that shape. The two groups are
 * separated below and each declining shape's Javadoc says so, because a 0% delta means opposite
 * things in the two groups.
 *
 * <p><b>What these numbers are not.</b> They are not comparable to this module's IC / IS figures —
 * see {@link GremlinTraversalShapes}. The baseline is Hetzner-scoped; a local run measures the
 * harness, not the feature.
 *
 * <p><b>Why the trial setup checks engagement and throws.</b> An A/B whose two arms both ran the
 * same path reports a difference of zero and looks like a clean result. {@link
 * TranslatorArm#setUp} therefore builds two witness traversals — one from each group — applies
 * strategies, and throws unless the boundary step is present exactly where the arm says it should
 * be. It throws rather than asserting because the launcher at {@code jmh-ldbc/pom.xml} runs
 * {@code java} without {@code -ea} — see {@link GremlinTraversalShapes#requireTranslated}.
 *
 * <p>Run one arm only with {@code -Djmh.args=".*LdbcGremlinTranslator.* -p translatorEnabled=true"}.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 5)
@Measurement(iterations = 5, time = 10)
@Threads(1)
@Fork(value = 3, jvmArgsAppend = {
    "-Xms4g", "-Xmx4g",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
    "--add-opens=java.base/java.io=ALL-UNNAMED",
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
    "--add-opens=java.base/java.net=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED",
    "--add-opens=java.base/sun.security.x509=ALL-UNNAMED",
    "--add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED",
})
public class LdbcGremlinTranslatorBenchmark {

  /**
   * Per-arm trial state: the kill-switch position, a RID pool for the by-id shape, and the person
   * and message id pools the walk shapes rotate through.
   *
   * <p>Kept separate from {@link LdbcBenchmarkState} so that class stays untouched and {@code
   * curatedParams} stays private. The pools are built through the public {@code isPersonId} /
   * {@code isMessageId} accessors, which is all the shapes need — the by-id shape wants a RID while
   * the curated parameters hold LDBC {@code id} longs, so the resolution happens once here rather
   * than per invocation.
   */
  @State(Scope.Benchmark)
  public static class TranslatorArm {

    /**
     * The A/B axis. JMH runs a separate set of forks per value, so a trial never sees both
     * positions of the kill-switch.
     */
    @Param({"true", "false"})
    public boolean translatorEnabled;

    /** Pool size: large enough that invocation N and invocation N+1 rarely repeat a record. */
    private static final int POOL_SIZE = 64;

    private long[] personIds;
    private Object[] personRids;
    private long[] messageIds;
    private String[] placeNames;
    private boolean flagBeforeTrial;

    /**
     * Resolves the id and RID pools and proves the arm is really installed.
     *
     * <p>Order matters: the flag is set before anything else runs, so setup and measurement share
     * one arm. The pools are then resolved from curated ids, skipping ids the dataset does not
     * hold, and an empty pool throws — an empty parameter feed would otherwise surface only as
     * suspiciously fast numbers on the first Hetzner run.
     */
    @Setup(Level.Trial)
    public void setUp(LdbcBenchmarkState state) {
      flagBeforeTrial =
          GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED.getValueAsBoolean();
      GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED.setValue(translatorEnabled);

      var ids = new ArrayList<Long>(POOL_SIZE);
      var rids = new ArrayList<Object>(POOL_SIZE);
      var messages = new ArrayList<Long>(POOL_SIZE);
      // The place-name pool feeds the anti-join shape only. It is resolved here rather than through
      // ParameterCurator so the curated-params cache version (and its Hetzner canonical file) stays
      // untouched — the same reason the RID pool is resolved here. Names come from places persons
      // actually live in, so the anti-join has something to exclude rather than always emptying the
      // NOT set.
      var places = new ArrayList<String>(POOL_SIZE);
      state.traversal.executeInTx(t -> {
        for (var i = 0; i < POOL_SIZE; i++) {
          var personId = state.isPersonId(i);
          // Resolve the RID through a by-property lookup rather than a second curated pool: the
          // curated parameters hold LDBC id longs only, and V(rid) needs the record identity.
          var vertex = t.V().hasLabel(GremlinTraversalShapes.PERSON_LABEL)
              .has("id", personId)
              .tryNext();
          if (vertex.isPresent()) {
            ids.add(personId);
            rids.add(vertex.get().id());
          }
          var messageId = state.isMessageId(i);
          if (t.V().hasLabel(GremlinTraversalShapes.MESSAGE_LABEL)
              .has("id", messageId)
              .tryNext()
              .isPresent()) {
            messages.add(messageId);
          }
          var placeName = t.V().hasLabel(GremlinTraversalShapes.PERSON_LABEL)
              .has("id", personId)
              .out(GremlinTraversalShapes.IS_LOCATED_IN_LABEL)
              .<String>values("name")
              .tryNext();
          if (placeName.isPresent()) {
            places.add(placeName.get());
          }
        }
      });

      if (ids.isEmpty()) {
        throw new IllegalStateException(
            "No curated Person id resolved to a record, so every benchmark below would measure an"
                + " empty traversal. Check that the LDBC database at -Dldbc.db.path is loaded.");
      }
      if (messages.isEmpty()) {
        throw new IllegalStateException(
            "No curated Message id resolved to a record, so the IS4 / IS5 / IS7 shapes would"
                + " measure an empty traversal. Check that the LDBC database at -Dldbc.db.path is"
                + " loaded.");
      }
      personIds = new long[ids.size()];
      for (var i = 0; i < ids.size(); i++) {
        personIds[i] = ids.get(i);
      }
      personRids = rids.toArray();
      messageIds = new long[messages.size()];
      for (var i = 0; i < messages.size(); i++) {
        messageIds[i] = messages.get(i);
      }
      // A person with no location resolves no place name; fall back to a literal so the anti-join
      // shape still runs (its NOT set is simply empty for a name no place carries).
      placeNames = places.isEmpty() ? new String[] {""} : places.toArray(new String[0]);

      checkArmInstalled(state);
    }

    /**
     * Builds one witness from each shape group, applies strategies, and throws unless the boundary
     * step's presence matches the arm. Without this an arm that failed to flip reports a difference
     * of zero and reads as a clean null result.
     *
     * <p>The declining witness is checked on both arms, which is what keeps the declining group
     * honest: it fails the day {@code repeat(...)} starts translating, at which point every
     * declining-shape number in this class stops measuring the decline path and needs re-reading.
     */
    private void checkArmInstalled(LdbcBenchmarkState state) {
      state.traversal.executeInTx(t -> {
        var translating = GremlinTraversalShapes.knowsFirstNames(t, personIds[0]).asAdmin();
        translating.applyStrategies();
        if (translatorEnabled) {
          GremlinTraversalShapes.requireTranslated("knowsFirstNames", translating);
        } else {
          GremlinTraversalShapes.requireNotTranslated("knowsFirstNames", translating);
        }

        // The anti-join is the highest decline-risk translating shape in this class: its edge-
        // bearing not(...) is claimed by a recogniser branch newer than every other shape's, so a
        // core that predates it declines silently and the A/B reads as a false 0%. Witness it on
        // the on-arm so that failure is loud.
        var antiJoin =
            GremlinTraversalShapes.friendsNotLocatedInPlace(t, personIds[0], placeNames[0])
                .asAdmin();
        antiJoin.applyStrategies();
        if (translatorEnabled) {
          GremlinTraversalShapes.requireTranslated("friendsNotLocatedInPlace", antiJoin);
        } else {
          GremlinTraversalShapes.requireNotTranslated("friendsNotLocatedInPlace", antiJoin);
        }

        var declining = GremlinTraversalShapes.repeatKnowsToThreeHops(t, personIds[0]).asAdmin();
        declining.applyStrategies();
        GremlinTraversalShapes.requireNotTranslated("repeatKnowsToThreeHops", declining);
      });
    }

    /** Restores the flag so a same-JVM run of anything else is not left on this arm. */
    @TearDown(Level.Trial)
    public void restoreFlag() {
      GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED.setValue(flagBeforeTrial);
    }

    long personId(long idx) {
      return personIds[(int) (idx % personIds.length)];
    }

    Object personRid(long idx) {
      return personRids[(int) (idx % personRids.length)];
    }

    long messageId(long idx) {
      return messageIds[(int) (idx % messageIds.length)];
    }

    String placeName(long idx) {
      return placeNames[(int) (idx % placeNames.length)];
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Translating shapes. Delta = MATCH against the native pipeline.
  // ---------------------------------------------------------------------------------------------

  /** Shape 2 — the {@code KNOWS} walk under {@code values}: one row per friend. */
  @Benchmark
  public List<String> gremlinKnowsFirstNames(LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.knowsFirstNames(t, arm.personId(i)).toList());
  }

  /** Shape 3 — the same walk under {@code count()}: the aggregate pushes into the MATCH plan. */
  @Benchmark
  public Long gremlinKnowsFirstNameCount(LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.knowsFirstNameCount(t, arm.personId(i)).next());
  }

  /**
   * Shape 4 — the same walk under {@code fold()}: the drain the boundary step applies after
   * projection. A run whose {@code youtrackdb-core} predates the {@code FoldStep} registry entry
   * declines the shape, so both arms run natively and the two numbers coincide.
   */
  @Benchmark
  public List<String> gremlinKnowsFirstNamesFolded(LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.knowsFirstNamesFolded(t, arm.personId(i)).next());
  }

  /** Shape 5 — IS1's {@code IS_LOCATED_IN} join with the city columns. */
  @Benchmark
  public List<Map<Object, Object>> gremlinIs1PersonCityProfile(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.is1PersonCityProfile(t, arm.personId(i)).toList());
  }

  /** Shape 6 — IS3's friend columns under an {@code ORDER BY}. */
  @Benchmark
  public List<Map<Object, Object>> gremlinIs3FriendsWithNames(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.is3FriendsWithNames(t, arm.personId(i)).toList());
  }

  /** Shape 7 — IS5 whole: the message's author, every column projected. */
  @Benchmark
  public List<Map<Object, Object>> gremlinIs5MessageCreator(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.is5MessageCreator(t, arm.messageId(i)).toList());
  }

  /** Shape 8 — two chained {@code KNOWS} hops: MATCH path enumeration against two native passes. */
  @Benchmark
  public List<String> gremlinTwoHopKnows(LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.twoHopKnows(t, arm.personId(i)).toList());
  }

  /**
   * Shape 9 — two hops with an indexed filter on the intermediate one, where MATCH can enter the
   * pattern from the filtered alias and native cannot.
   */
  @Benchmark
  public List<String> gremlinKnowsFilteredByFriendFirstName(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes
            .knowsFilteredByFriendFirstName(t, arm.personId(i), state.ic1FirstName(i))
            .toList());
  }

  /** Shape 10 — three hops with a {@code where()} back-reference to the first hop's alias. */
  @Benchmark
  public List<String> gremlinThreeHopKnowsExcludingIntermediate(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.threeHopKnowsExcludingIntermediate(t, arm.personId(i))
            .toList());
  }

  /** Shape 11 — IS1's full projection: both aliases reached through {@code select}. */
  @Benchmark
  public List<Map<String, Object>> gremlinIs1FullProfile(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.is1FullProfile(t, arm.personId(i)).toList());
  }

  /** Shape 12 — {@code GROUP BY} with {@code count(*)} pushed into the plan. */
  @Benchmark
  public Map<Object, Long> gremlinKnowsGroupCountByLastName(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.knowsGroupCountByLastName(t, arm.personId(i)).next());
  }

  /**
   * Shape 13 — a person's friends not located in a given place: MATCH's hash anti-join against the
   * native pipeline's per-candidate re-walk of {@code IS_LOCATED_IN}.
   */
  @Benchmark
  public List<String> gremlinFriendsNotLocatedInPlace(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes
            .friendsNotLocatedInPlace(t, arm.personId(i), arm.placeName(i))
            .toList());
  }

  /**
   * Shape 14 — a mutual-friend triangle closed by a {@code where()} back-reference to the start:
   * MATCH's topological self-join against the native pipeline's full three-hop expansion.
   */
  @Benchmark
  public List<String> gremlinMutualFriendTriangle(LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.mutualFriendTriangle(t, arm.personId(i)).toList());
  }

  // ---------------------------------------------------------------------------------------------
  // Declining shapes. Delta = the cost of the decline path, and the baseline for the day the shape
  // starts translating. A 0% delta here means "MATCH never ran", not "MATCH did not help" — the
  // both-arms requireNotTranslated in the trial witness and in
  // LdbcGremlinShapeTranslationTest is what keeps that reading true.
  // ---------------------------------------------------------------------------------------------

  /**
   * A bare {@code g.V(rid)} point-lookup. Native resolves the id without a query, while a translated
   * bare lookup would compile an uncached MATCH plan (a RID-bearing walk sets {@code
   * cacheEligible=false}) with no join to optimise, so the translator DECLINES it — both arms run
   * natively. The A/B measures the decline-path cost and is the baseline for the day a bare RID
   * lookup starts translating again. A RID start followed by a hop still translates.
   */
  @Benchmark
  public List<Vertex> gremlinVertexByRidDeclines(LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.personByRid(t, arm.personRid(i)).toList());
  }

  /** {@code ORDER BY} with {@code SKIP} / {@code LIMIT} paging: a slice behind a captured sort. */
  @Benchmark
  public List<String> gremlinKnowsOrderedPageDeclines(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.knowsOrderedPage(t, arm.personId(i)).toList());
  }

  /**
   * IC1's variable-depth walk: vetoed by {@code RepeatDeclineStrategy} before the walker runs, so
   * this is the cheapest decline route in the group and the floor the other two are read against.
   */
  @Benchmark
  public List<String> gremlinRepeatKnowsToThreeHopsDeclines(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.repeatKnowsToThreeHops(t, arm.personId(i)).toList());
  }

  /** IS4's {@code coalesce} projection: declines part-way through the walk on {@code CoalesceStep}. */
  @Benchmark
  public List<String> gremlinCoalesceMessageContentDeclines(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.coalesceMessageContent(t, arm.messageId(i)).toList());
  }

  /** IS7's optional hop: declines part-way through the walk, and reserves the Phase 2 baseline. */
  @Benchmark
  public List<String> gremlinOptionalFriendOfCreatorDeclines(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.optionalFriendOfCreator(t, arm.messageId(i)).toList());
  }

  /**
   * IS3 whole: edge {@code creationDate} and friend {@code firstName} via {@code select}. Declines
   * because the {@code as("k")} label on {@code outE(KNOWS)} would bind to the edge-as-node vertex
   * alias, so {@code select("k")} would read the target vertex rather than the friendship edge —
   * see {@link GremlinTraversalShapes#is3FriendsWithDates}. Both arms run natively.
   */
  @Benchmark
  public List<Map<String, Object>> gremlinIs3FriendsWithDatesDeclines(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.is3FriendsWithDates(t, arm.personId(i)).toList());
  }
}
