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
 * Gremlin translator benchmarks over {@link GremlinTraversalShapes} on the LDBC schema.
 *
 * <h2>Primary use: head vs {@code develop} (translator on both sides)</h2>
 *
 * <p>The {@code ldbc-jmh-compare} workflow checks out the fork-point and the branch tip, runs this
 * class on each, and compares throughput — with {@code -p translatorEnabled=true} so base and head
 * both measure the production MATCH path. Read PR deltas on translating shapes as "did this branch
 * change Gremlin+translator performance vs {@code develop}?", the same way as {@code
 * LdbcSingleThread*} / {@code LdbcMultiThread*} SQL rows beside them.
 *
 * <h2>Secondary use: translator on vs off (same commit)</h2>
 *
 * <p>{@link TranslatorArm} exposes {@code translatorEnabled} as a JMH {@code @Param}. Each value
 * gets its own forks; the kill-switch is flipped once per trial in-process. Nothing is set through
 * {@code -DargLine=}: on some modules a CLI {@code argLine} replaces the POM's block wholesale
 * (taking {@code -ea}, the heap sizing and every {@code --add-opens} with it) and on others plugin
 * configuration wins and the CLI value is inert. Neither failure is visible in the numbers.
 *
 * <p>Every {@code @Benchmark} method starts with {@code gremlin_}, so the compare workflow's
 * {@code queries=gremlin} filter ({@code .*gremlin_.*}) selects this class without a special case.
 * Shapes that echo an IC/IS query keep the SQL id ({@code gremlin_is1_...}). The one complete twin
 * reuses the SQL method suffix: {@code gremlin_is5_messageCreator} next to {@code
 * is5_messageCreator}. Translator primitives have no query id ({@code gremlin_knowsFirstNames}).
 *
 * <p>Run both arms locally with {@code -Djmh.args=".*gremlin_.*"} (no {@code -p} filter). For
 * {@code jmh-compare.py}, pass {@code --gremlin-arms both} to include off-arm rows in the markdown
 * comment.
 *
 * <h2>Two benchmark groups, read differently</h2>
 *
 * <p><b>Translating shapes</b> — in CI, head-vs-base delta is MATCH throughput. In the optional
 * on/off A/B, delta is MATCH vs native on one commit.
 *
 * <p><b>Declining shapes</b> — in CI, both sides run native; head-vs-base is not evidence that
 * MATCH helped or hurt. In the optional on/off A/B, delta prices decline overhead ({@code
 * GremlinToMatchStrategy} runs on the on-arm, walks, then hands back). A ~0% PR delta here usually
 * means "still declining on both sides", not "translator made no difference".
 *
 * <p><b>What these numbers are not.</b> Not comparable to this module's IC/IS SQL throughput — see
 * {@link GremlinTraversalShapes}. Hetzner-scoped baselines; a local run validates the harness, not
 * a production regression gate.
 *
 * <p><b>Trial setup witness.</b> An arm whose kill-switch failed to flip reports ~0% vs the other
 * arm and looks clean. {@link TranslatorArm#setUp} builds witness traversals from each group,
 * applies strategies, and throws unless the boundary step matches the arm — see {@link
 * GremlinTraversalShapes#requireTranslated}.
 *
 * <p>Reproduce the CI arm only: {@code -Djmh.args=".*gremlin_.* -p translatorEnabled=true"}.
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
     * Kill-switch position for this fork. JMH runs separate forks per value; {@code ldbc-jmh-compare}
     * defaults to {@code true} only so base and head both measure the production path.
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
  // Translating shapes. CI: head vs base with translator on. Optional A/B: MATCH vs native.
  // ---------------------------------------------------------------------------------------------

  /** LDBC: none. One-hop {@code KNOWS} under {@code values}. */
  @Benchmark
  public List<String> gremlin_knowsFirstNames(LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.knowsFirstNames(t, arm.personId(i)).toList());
  }

  /** LDBC: none. {@link GremlinTraversalShapes#knowsFirstNames} under {@code count()}. */
  @Benchmark
  public Long gremlin_knowsFirstNameCount(LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.knowsFirstNameCount(t, arm.personId(i)).next());
  }

  /**
   * LDBC: none. {@link GremlinTraversalShapes#knowsFirstNames} under {@code fold()}. A core that
   * predates {@code FoldStep} declines the shape and both arms run natively.
   */
  @Benchmark
  public List<String> gremlin_knowsFirstNamesFolded(LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.knowsFirstNamesFolded(t, arm.personId(i)).next());
  }

  /** LDBC: IS1 reduced. City-side columns of the person–city join. */
  @Benchmark
  public List<Map<Object, Object>> gremlin_is1_personCityProfile(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.is1PersonCityProfile(t, arm.personId(i)).toList());
  }

  /** LDBC: IS3 reduced. Friend columns under {@code ORDER BY firstName}; no edge date. */
  @Benchmark
  public List<Map<Object, Object>> gremlin_is3_friendsWithNames(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.is3FriendsWithNames(t, arm.personId(i)).toList());
  }

  /**
   * LDBC: IS5 complete. Message author; every SQL column. JMH name matches
   * {@code is5_messageCreator}.
   */
  @Benchmark
  public List<Map<Object, Object>> gremlin_is5_messageCreator(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.is5MessageCreator(t, arm.messageId(i)).toList());
  }

  /**
   * LDBC: IS2 reduced. Person messages newest first; no original-post climb, coalesce, or LIMIT.
   */
  @Benchmark
  public List<Map<Object, Object>> gremlin_is2_personMessages(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.is2PersonMessages(t, arm.personId(i)).toList());
  }

  /**
   * LDBC: IS6 reduced. Post's Forum and moderator; no {@code REPLY_OF} climb from a Comment.
   */
  @Benchmark
  public List<Map<String, Object>> gremlin_is6_forumOfPost(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.is6ForumOfPost(t, arm.messageId(i)).toList());
  }

  /**
   * LDBC: IS7 reduced. Reply authors; no optional KNOWS, coalesce, or LIMIT.
   */
  @Benchmark
  public List<Map<Object, Object>> gremlin_is7_repliesWithAuthors(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.is7RepliesWithAuthors(t, arm.messageId(i)).toList());
  }

  /**
   * LDBC: IC2 reduced. Friends' messages before the curated max date, newest first; no coalesce
   * or LIMIT. Uses {@code ic2PersonId}/{@code ic2MaxDate}, not the IS person pool.
   */
  @Benchmark
  public List<Map<Object, Object>> gremlin_ic2_friendsMessagesOrdered(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes
            .ic2FriendsMessagesOrdered(t, state.ic2PersonId(i), state.ic2MaxDate(i))
            .toList());
  }

  /**
   * LDBC: IC7 reduced. Likers' first names; no like-edge date, optional KNOWS, or GROUP BY.
   * Uses {@code ic7PersonId}.
   */
  @Benchmark
  public List<String> gremlin_ic7_likers(LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.ic7Likers(t, state.ic7PersonId(i)).toList());
  }

  /**
   * LDBC: IC8 reduced. Comments replying to a person's messages, newest first; no creator
   * columns, coalesce, or LIMIT. Uses {@code ic8PersonId}.
   */
  @Benchmark
  public List<Map<Object, Object>> gremlin_ic8_recentRepliesOrdered(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.ic8RecentRepliesOrdered(t, state.ic8PersonId(i)).toList());
  }

  /**
   * LDBC: IC11 reduced. Direct friends' companies in the curated country; no FoF, workFrom, or
   * LIMIT. Uses {@code ic11PersonId}/{@code ic11CountryName}.
   */
  @Benchmark
  public List<Map<String, Object>> gremlin_ic11_friendsCompaniesInCountry(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.ic11FriendsCompaniesInCountry(
            t, state.ic11PersonId(i), state.ic11CountryName(i))
            .toList());
  }

  /** LDBC: none. Two chained {@code KNOWS} hops. */
  @Benchmark
  public List<String> gremlin_twoHopKnows(LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.twoHopKnows(t, arm.personId(i)).toList());
  }

  /**
   * LDBC: none. Two-hop {@code KNOWS} with an indexed filter on the intermediate hop.
   */
  @Benchmark
  public List<String> gremlin_knowsFilteredByFriendFirstName(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes
            .knowsFilteredByFriendFirstName(t, arm.personId(i), state.ic1FirstName(i))
            .toList());
  }

  /** LDBC: none. Three-hop {@code KNOWS} with {@code where(neq)} against a mid-walk alias. */
  @Benchmark
  public List<String> gremlin_threeHopKnowsExcludingIntermediate(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.threeHopKnowsExcludingIntermediate(t, arm.personId(i))
            .toList());
  }

  /** LDBC: IS1 reduced. {@code select("p", "city")} of firstName and city id. */
  @Benchmark
  public List<Map<String, Object>> gremlin_is1_fullProfile(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.is1FullProfile(t, arm.personId(i)).toList());
  }

  /** LDBC: none. {@code groupCount().by(lastName)}. */
  @Benchmark
  public Map<Object, Long> gremlin_knowsGroupCountByLastName(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.knowsGroupCountByLastName(t, arm.personId(i)).next());
  }

  /**
   * LDBC: none. Hash anti-join of friends not located in a place.
   */
  @Benchmark
  public List<String> gremlin_friendsNotLocatedInPlace(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes
            .friendsNotLocatedInPlace(t, arm.personId(i), arm.placeName(i))
            .toList());
  }

  /**
   * LDBC: none. Three-hop {@code KNOWS} triangle closed by {@code where(eq(start))}.
   */
  @Benchmark
  public List<String> gremlin_mutualFriendTriangle(LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.mutualFriendTriangle(t, arm.personId(i)).toList());
  }

  // ---------------------------------------------------------------------------------------------
  // Declining shapes. CI: both sides native — PR delta is not a MATCH regression. Optional on/off
  // A/B prices decline overhead; LdbcGremlinShapeTranslationTest keeps groups honest.
  // ---------------------------------------------------------------------------------------------

  /**
   * LDBC: none. Bare {@code g.V(rid)} point-lookup; translator declines, both CI arms native.
   */
  @Benchmark
  public List<Vertex> gremlin_vertexByRidDeclines(LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.personByRid(t, arm.personRid(i)).toList());
  }

  /** LDBC: none. {@code order().by(firstName).range(1, 3)}; slice-after-sort declines. */
  @Benchmark
  public List<String> gremlin_knowsOrderedPageDeclines(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.knowsOrderedPage(t, arm.personId(i)).toList());
  }

  /**
   * LDBC: IC1 fragment. {@code repeat(out(KNOWS)).times(3)}; {@code RepeatDeclineStrategy} veto.
   */
  @Benchmark
  public List<String> gremlin_ic1_repeatKnowsToThreeHopsDeclines(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.repeatKnowsToThreeHops(t, arm.personId(i)).toList());
  }

  /** LDBC: IS4 fragment. {@code coalesce(imageFile, content)}; declines on {@code CoalesceStep}. */
  @Benchmark
  public List<String> gremlin_is4_coalesceMessageContentDeclines(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.coalesceMessageContent(t, arm.messageId(i)).toList());
  }

  /** LDBC: IS7 fragment. {@code optional(out(KNOWS))} after the author; declines on {@code optional()}. */
  @Benchmark
  public List<String> gremlin_is7_optionalFriendOfCreatorDeclines(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.optionalFriendOfCreator(t, arm.messageId(i)).toList());
  }

  /**
   * LDBC: IS3 full attempt. Edge date plus friend name via {@code select}; declines on edge
   * {@code as("k")}. See {@link GremlinTraversalShapes#is3FriendsWithDates}.
   */
  @Benchmark
  public List<Map<String, Object>> gremlin_is3_friendsWithDatesDeclines(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.is3FriendsWithDates(t, arm.personId(i)).toList());
  }
}
