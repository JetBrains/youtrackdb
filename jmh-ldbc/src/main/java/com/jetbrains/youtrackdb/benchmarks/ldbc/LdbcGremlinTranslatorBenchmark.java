package com.jetbrains.youtrackdb.benchmarks.ldbc;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import java.util.ArrayList;
import java.util.Date;
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
   * Per-arm trial state: the kill-switch position, a RID pool for the by-id shape, the person and
   * message id pools the walk shapes rotate through, and a Gremlin-only IC2/IC9
   * {@code (person, maxDate)} pool derived from friend-message dates (canonical curated dates for
   * those queries can all miss the person's friends' messages).
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
    /**
     * Gremlin-only IC2/IC9 feed. Canonical curated {@code (person, maxDate)} pairs for those
     * queries can all sit before the person's friends' first message, so every invocation would
     * measure an empty walk. Person ids still come from the curated IC2/IC9 pools; maxDates are
     * spread across that person's friend-message creationDate range so the shapes return rows.
     * SQL benchmarks keep the S3 curated file untouched.
     */
    private long[] ic2PersonIds;
    private Date[] ic2MaxDates;
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

      buildIc2DatePool(state);

      checkArmInstalled(state);
    }

    /**
     * Builds the Gremlin IC2/IC9 {@code (personId, maxDate)} pool from curated person ids and the
     * live friend-message date range. See {@link #ic2PersonIds}.
     */
    private void buildIc2DatePool(LdbcBenchmarkState state) {
      final int probeLimit = 64;
      var holder = new Object[3]; // personId Long, min Date, max Date
      state.traversal.executeInTx(t -> {
        for (var i = 0; i < probeLimit; i++) {
          // IC2 and IC9 share the FoF-selected curated person pool; probe both accessors so a
          // future curator split still finds a workable seed.
          long personId = i < probeLimit / 2
              ? state.ic2PersonId(i)
              : state.ic9PersonId(i - probeLimit / 2);
          var minRaw = t.V().hasLabel(GremlinTraversalShapes.PERSON_LABEL)
              .has("id", personId)
              .out(GremlinTraversalShapes.KNOWS_LABEL)
              .in(GremlinTraversalShapes.HAS_CREATOR_LABEL)
              .values("creationDate")
              .min()
              .tryNext();
          if (minRaw.isEmpty()) {
            continue;
          }
          var maxRaw = t.V().hasLabel(GremlinTraversalShapes.PERSON_LABEL)
              .has("id", personId)
              .out(GremlinTraversalShapes.KNOWS_LABEL)
              .in(GremlinTraversalShapes.HAS_CREATOR_LABEL)
              .values("creationDate")
              .max()
              .tryNext();
          if (maxRaw.isEmpty()) {
            continue;
          }
          holder[0] = personId;
          holder[1] = toDate(minRaw.get());
          holder[2] = toDate(maxRaw.get());
          return;
        }
      });
      if (holder[0] == null) {
        throw new IllegalStateException(
            "No curated IC2/IC9 person has friends with messages, so gremlin_ic2 / gremlin_ic9"
                + " would measure an empty traversal. Check that the LDBC database at"
                + " -Dldbc.db.path is loaded.");
      }
      long personId = (Long) holder[0];
      long minMs = ((Date) holder[1]).getTime();
      long maxMs = ((Date) holder[2]).getTime();
      long span = Math.max(1L, maxMs - minMs);
      ic2PersonIds = new long[POOL_SIZE];
      ic2MaxDates = new Date[POOL_SIZE];
      for (var k = 0; k < POOL_SIZE; k++) {
        ic2PersonIds[k] = personId;
        // Spread maxDate from just after the earliest friend message to just after the latest so
        // every invocation has at least one hit and later slots approach the full friend-message
        // set (ORDER BY + LIMIT 20 still caps the result).
        long maxDateMs = (k == POOL_SIZE - 1)
            ? maxMs + 1L
            : minMs + 1L + (span * k) / (POOL_SIZE - 1);
        ic2MaxDates[k] = new Date(maxDateMs);
      }
    }

    private static Date toDate(Object raw) {
      if (raw instanceof Date date) {
        return date;
      }
      if (raw instanceof Number number) {
        return new Date(number.longValue());
      }
      throw new IllegalStateException(
          "creationDate is " + raw.getClass().getName() + ", expected Date or Number");
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

    long ic2PersonId(long idx) {
      return ic2PersonIds[(int) (idx % ic2PersonIds.length)];
    }

    Date ic2MaxDate(long idx) {
      return ic2MaxDates[(int) (idx % ic2MaxDates.length)];
    }

    /** Same pool as {@link #ic2PersonId}: Gremlin IC9 reuses the IC2 walk. */
    long ic9PersonId(long idx) {
      return ic2PersonId(idx);
    }

    /** Same pool as {@link #ic2MaxDate}: Gremlin IC9 reuses the IC2 walk. */
    Date ic9MaxDate(long idx) {
      return ic2MaxDate(idx);
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

  /** LDBC: IS1 complete — see {@link GremlinTraversalShapes#is1PersonCityProfile}. */
  @Benchmark
  public List<Map<String, Object>> gremlin_is1_personCityProfile(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.is1PersonCityProfile(t, arm.personId(i)).toList());
  }

  /** LDBC: IS2 reduced — see {@link GremlinTraversalShapes#is2PersonMessages}. */
  @Benchmark
  public List<Map<Object, Object>> gremlin_is2_personMessages(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.is2PersonMessages(t, arm.personId(i)).toList());
  }

  /** LDBC: IS3 reduced — see {@link GremlinTraversalShapes#is3FriendsWithNames}. */
  @Benchmark
  public List<Map<Object, Object>> gremlin_is3_friendsWithNames(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.is3FriendsWithNames(t, arm.personId(i)).toList());
  }

  /** LDBC: IS4 reduced — see {@link GremlinTraversalShapes#is4MessageContent}. */
  @Benchmark
  public List<Map<Object, Object>> gremlin_is4_messageContent(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.is4MessageContent(t, arm.messageId(i)).toList());
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

  /** LDBC: IS6 reduced — see {@link GremlinTraversalShapes#is6ForumOfPost}. */
  @Benchmark
  public List<Map<String, Object>> gremlin_is6_forumOfPost(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.is6ForumOfPost(t, arm.messageId(i)).toList());
  }

  /** LDBC: IS7 reduced — see {@link GremlinTraversalShapes#is7RepliesWithAuthors}. */
  @Benchmark
  public List<Map<String, Object>> gremlin_is7_repliesWithAuthors(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.is7RepliesWithAuthors(t, arm.messageId(i)).toList());
  }

  /**
   * LDBC: IC1 reduced — see {@link GremlinTraversalShapes#ic1FriendsWithName}. Uses
   * {@code ic1PersonId}/{@code ic1FirstName}.
   */
  @Benchmark
  public List<Map<String, Object>> gremlin_ic1_friendsWithName(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes
            .ic1FriendsWithName(t, state.ic1PersonId(i), state.ic1FirstName(i))
            .toList());
  }

  /**
   * LDBC: IC2 reduced — see {@link GremlinTraversalShapes#ic2FriendsMessagesOrdered}. Uses the
   * Gremlin-only {@link TranslatorArm#ic2PersonId}/{@link TranslatorArm#ic2MaxDate} pool (curated
   * dates can miss friend messages entirely).
   */
  @Benchmark
  public List<Map<String, Object>> gremlin_ic2_friendsMessagesOrdered(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes
            .ic2FriendsMessagesOrdered(t, arm.ic2PersonId(i), arm.ic2MaxDate(i))
            .toList());
  }

  /**
   * LDBC: IC3 reduced — see {@link GremlinTraversalShapes#ic3FriendsMessagesInCountry}. Date window
   * is {@code [ic3StartDate, ic3StartDate + 30 days)}.
   */
  @Benchmark
  public List<Map<String, Object>> gremlin_ic3_friendsMessagesInCountry(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    var start = state.ic3StartDate(i);
    var end = new Date(start.getTime() + 2_592_000_000L);
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.ic3FriendsMessagesInCountry(
            t, state.ic3PersonId(i), state.ic3CountryX(i), start, end)
            .toList());
  }

  /**
   * LDBC: IC4 reduced — see {@link GremlinTraversalShapes#ic4FriendPostTags}. Date window is
   * {@code [ic4StartDate, ic4StartDate + 30 days)}.
   */
  @Benchmark
  public Map<Object, Long> gremlin_ic4_friendPostTags(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    var start = state.ic4StartDate(i);
    var end = new Date(start.getTime() + 2_592_000_000L);
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes
            .ic4FriendPostTags(t, state.ic4PersonId(i), start, end)
            .next());
  }

  /** LDBC: IC5 reduced — see {@link GremlinTraversalShapes#ic5FriendPostForums}. */
  @Benchmark
  public List<Map<String, Object>> gremlin_ic5_friendPostForums(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.ic5FriendPostForums(t, state.ic5PersonId(i)).toList());
  }

  /** LDBC: IC6 reduced — see {@link GremlinTraversalShapes#ic6FriendPostTagCounts}. */
  @Benchmark
  public Map<Object, Long> gremlin_ic6_friendPostTagCounts(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.ic6FriendPostTagCounts(t, state.ic6PersonId(i)).next());
  }

  /**
   * LDBC: IC7 reduced — see {@link GremlinTraversalShapes#ic7Likers}. Uses {@code ic7PersonId}.
   */
  @Benchmark
  public List<String> gremlin_ic7_likers(LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.ic7Likers(t, state.ic7PersonId(i)).toList());
  }

  /**
   * LDBC: IC8 reduced — see {@link GremlinTraversalShapes#ic8RecentRepliesOrdered}. Uses
   * {@code ic8PersonId}.
   */
  @Benchmark
  public List<Map<String, Object>> gremlin_ic8_recentRepliesOrdered(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.ic8RecentRepliesOrdered(t, state.ic8PersonId(i)).toList());
  }

  /**
   * LDBC: IC9 reduced — see {@link GremlinTraversalShapes#ic9FriendsMessagesOrdered}. Uses the
   * same Gremlin-only pool as {@link #gremlin_ic2_friendsMessagesOrdered}.
   */
  @Benchmark
  public List<Map<String, Object>> gremlin_ic9_friendsMessagesOrdered(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes
            .ic9FriendsMessagesOrdered(t, arm.ic9PersonId(i), arm.ic9MaxDate(i))
            .toList());
  }

  /** LDBC: IC10 reduced — see {@link GremlinTraversalShapes#ic10FriendsOfFriendsInCity}. */
  @Benchmark
  public List<Map<String, Object>> gremlin_ic10_friendsOfFriendsInCity(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.ic10FriendsOfFriendsInCity(t, state.ic10PersonId(i)).toList());
  }

  /**
   * LDBC: IC11 reduced — see {@link GremlinTraversalShapes#ic11FriendsCompaniesInCountry}. Uses
   * {@code ic11PersonId}/{@code ic11CountryName}.
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

  /** LDBC: IC12 reduced — see {@link GremlinTraversalShapes#ic12FriendCommentPostTags}. */
  @Benchmark
  public List<Map<String, Object>> gremlin_ic12_friendCommentPostTags(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.ic12FriendCommentPostTags(t, state.ic12PersonId(i)).toList());
  }

  /** LDBC: IC13 reduced — see {@link GremlinTraversalShapes#ic13DirectKnowsCount}. */
  @Benchmark
  public Long gremlin_ic13_directKnowsCount(LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes
            .ic13DirectKnowsCount(t, state.ic13Person1Id(i), state.ic13Person2Id(i))
            .next());
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

  /**
   * LDBC: none. Friends {@code order().by(firstName).range(1, 3)} — NOTUNIQUE sort, same-boundary
   * slice translates (YQL-equivalent ties).
   */
  @Benchmark
  public List<String> gremlin_knowsOrderedPage(LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.knowsOrderedPage(t, arm.personId(i)).toList());
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

  /**
   * LDBC: IS4 fragment. {@code coalesce} only — declines. Missing vs SQL: {@code creationDate}.
   */
  @Benchmark
  public List<String> gremlin_is4_coalesceMessageContentDeclines(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.coalesceMessageContent(t, arm.messageId(i)).toList());
  }

  /**
   * LDBC: IS7 fragment. {@code optional(KNOWS)} — declines. Missing vs SQL: reply/author columns,
   * coalesce, ifnull, {@code ORDER BY}.
   */
  @Benchmark
  public List<String> gremlin_is7_optionalFriendOfCreatorDeclines(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.optionalFriendOfCreator(t, arm.messageId(i)).toList());
  }

  /**
   * LDBC: IC1 fragment. {@code repeat} KNOWS depth 3 — declines. Missing vs SQL: LET, GROUP BY,
   * profile columns, {@code ORDER BY}, {@code LIMIT}.
   */
  @Benchmark
  public List<String> gremlin_ic1_repeatKnowsToThreeHopsDeclines(
      LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.repeatKnowsToThreeHops(t, arm.personId(i)).toList());
  }

}
