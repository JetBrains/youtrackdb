package com.jetbrains.youtrackdb.benchmarks.ldbc;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Single-threaded benchmark for queries that exercise the {@code bothE} / {@code both}
 * bidirectional traversal pre-filter optimization.
 *
 * <h2>What is measured</h2>
 *
 * <p>The benchmark runs the {@code BothE-KNOWS} query (see
 * {@link LdbcQuerySql#BOTH_E_KNOWS}), which uses
 * {@code .bothE('KNOWS'){where: (creationDate >= :minDate)}} to find recent
 * KNOWS connections of a person in both directions.
 *
 * <p>With the {@code KNOWS.creationDate} index (created for this microbench only —
 * see {@link KnowsCreationDateIndex}), the MATCH planner builds a RID set from the
 * index and hands it to {@code MatchEdgeTraverser.applyPreFilter()}. Previously,
 * {@code bothE()} returned a plain {@code IterableUtils.chainedIterable} which is
 * not a {@code PreFilterableLinkBagIterable}, so the pre-filter was silently
 * bypassed and all KNOWS edges were loaded before the date condition was checked.
 * After the fix, both the {@code out_KNOWS} and {@code in_KNOWS} link bags are
 * intersected against the index RID set in memory before any edge record is loaded
 * from disk.
 *
 * <h2>Why this query is realistic</h2>
 *
 * <p>In the LDBC Social Network dataset, KNOWS edges are stored bidirectionally
 * (A→B and B→A edges both exist). A real application might ask:
 * "Show me all people I have connected with since I joined this organisation"
 * without caring which side initiated the connection — a natural use case for
 * {@code bothE}. A production implementation would add {@code DISTINCT} to
 * deduplicate; this benchmark omits it intentionally to maximise the number of
 * edges touched and make the pre-filter benefit more visible.
 *
 * <h2>Schema requirement</h2>
 *
 * <p>{@code bothEKnows_recentConnections} creates {@code KNOWS.creationDate} for
 * its Trial and drops it afterwards so the shared on-disk LDBC DB used by IC/IS
 * forks does not keep an unused KNOWS secondary index (which regresses IC1).
 * {@code LdbcBenchmarkState.tearDown()} repeats the drop as a safety net, because
 * JMH does not order teardowns across {@code @State} classes. Other methods in
 * this class use indexes already present in {@code ldbc-schema.sql}.
 *
 * <h2>Why this class is excluded from the A/B compare suite</h2>
 *
 * <p>{@code ldbc-jmh-compare.yml} passes {@code -e} to skip this class on the
 * full-suite path. Two reasons. It is the only benchmark class that writes to the
 * shared on-disk DB, and an index build over every KNOWS edge evicts hot
 * Person/KNOWS pages that the IC/IS benchmarks measure against — IC1 traverses
 * KNOWS three hops deep and is the most exposed. It also yields no comparison
 * signal, because the fork-point side of an A/B run does not have these
 * benchmarks at all, so every row reads as "new". Run it on demand with
 * {@code -Djmh.args="LdbcSingleThreadBothEBenchmark"}.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 3, time = 10)
@Fork(value = 5, jvmArgsAppend = {
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
@Threads(1)
public class LdbcSingleThreadBothEBenchmark {

  private static final int LIMIT = 20;

  /**
   * Trial-scoped helper that installs {@code KNOWS.creationDate} only while
   * {@link #bothEKnows_recentConnections} runs, then removes it so later IC/IS
   * benchmarks sharing the same DB path do not pay for the unused index.
   */
  @State(Scope.Benchmark)
  public static class KnowsCreationDateIndex {

    @Setup(Level.Trial)
    public void setup(LdbcBenchmarkState state) {
      state.ensureKnowsCreationDateIndex();
    }

    @TearDown(Level.Trial)
    public void tearDown(LdbcBenchmarkState state) {
      state.dropKnowsCreationDateIndex();
    }
  }

  /**
   * Trial-scoped helper that computes the hub-bench parameters (top-100 Forums by
   * {@code HAS_MEMBER} bag size, plus the selective {@code joinDate} bounds).
   *
   * <p>These live behind a state class rather than in
   * {@code LdbcBenchmarkState.setup()} because that setup is shared by every
   * benchmark class: the Forum scan would then run in every IC/IS fork, both
   * lengthening their setup and changing the page-cache state they measure
   * against. Declaring it here scopes the work to forks of this class.
   */
  @State(Scope.Benchmark)
  public static class ForumHubParams {

    @Setup(Level.Trial)
    public void setup(LdbcBenchmarkState state) {
      state.computeForumHubParams();
    }
  }

  /**
   * BothE-KNOWS: recent connections via bidirectional KNOWS traversal with a
   * date pre-filter.
   *
   * <p>Traverses both {@code out_KNOWS} and {@code in_KNOWS} link bags of a
   * Person vertex and filters edges by {@code creationDate >= :minDate}. With
   * the {@code KNOWS.creationDate} index, the MATCH engine intersects both bags
   * against the index RID set via {@code PreFilterableChainedIterable} before
   * loading any edge record from disk.
   */
  @Benchmark
  public List<Map<String, Object>> bothEKnows_recentConnections(
      LdbcBenchmarkState state, KnowsCreationDateIndex knowsCreationDateIndex) {
    // Parameter forces JMH to run KnowsCreationDateIndex Trial setup/teardown.
    Objects.requireNonNull(knowsCreationDateIndex);
    long i = state.nextIndex();
    return state.executeSql(
        LdbcQuerySql.BOTH_E_KNOWS,
        "personId", state.bothEKnowsPersonId(i),
        "minDate", state.bothEKnowsMinDate(i),
        "limit", LIMIT);
  }

  /**
   * Both-KNOWS (vertex): named KNOWS neighbors via vertex-to-vertex
   * {@code both('KNOWS')}. Unlike {@link #bothEKnows_recentConnections} (which
   * traverses edge records via {@code bothE}), this exercises the
   * <em>vertex</em> {@code both()} path
   * ({@code VertexEntityImpl.getVertices(BOTH)}) fixed by YTDB-646, which builds
   * a single {@code PreFilterableChainedIterable} over the {@code out_KNOWS} and
   * {@code in_KNOWS} link bags.
   *
   * <p>Because KNOWS is stored bidirectionally in LDBC, a Person's out_KNOWS and
   * in_KNOWS bags are BOTH populated — the two-direction shape that actually
   * constructs the chained iterable (single-direction shapes collapse to a
   * single bag). KNOWS is symmetric (out=Person, in=Person), so the planner
   * infers the target class Person and, with the {@code Person.firstName}
   * index, intersects both bags against the index RID set before loading any
   * neighbor vertex. A regression in the chained vertex path (broken per-sub
   * pre-filter delegation, or reintroduction of the BG1/PF2 empty-direction
   * fallback) would show up here as a throughput drop rather than silently
   * degrading to unfiltered iteration.
   */
  @Benchmark
  public List<Map<String, Object>> bothKnowsVertex_namedFriends(
      LdbcBenchmarkState state) {
    long i = state.nextIndex();
    return state.executeSql(
        LdbcQuerySql.BOTH_KNOWS_VERTEX,
        "personId", state.bothEKnowsPersonId(i),
        "firstName", state.bothKnowsFirstName(i),
        "limit", LIMIT);
  }

  /**
   * BothE-HAS_MEMBER: recent joiners of a popular Forum — hub-shape variant of
   * the pre-filter benchmark. Traverses {@code HAS_MEMBER} edges of a Forum in
   * the top-100 by bag size (thousands of members) with a selective
   * {@code joinDate} lower bound.
   *
   * <p>This is where YTDB-646 is designed to shine: the
   * {@code HAS_MEMBER.joinDate} index lets {@code MatchEdgeTraverser}
   * intersect the Forum's {@code out_HAS_MEMBER} link bag against a small RID
   * set before loading any edge record, skipping most edge loads — in
   * contrast to the small-bag {@code BothE-KNOWS} case (Person averages ~100
   * KNOWS edges) where pre-filter overhead matches the savings.
   */
  @Benchmark
  public List<Map<String, Object>> bothEHasMember_recentJoiners(
      LdbcBenchmarkState state, ForumHubParams forumHubParams) {
    // Parameter forces JMH to run ForumHubParams Trial setup, which populates
    // the forumHubId / forumHubMinJoinDate pools read below.
    Objects.requireNonNull(forumHubParams);
    long i = state.nextIndex();
    return state.executeSql(
        LdbcQuerySql.FORUM_RECENT_JOINERS,
        "forumId", state.forumHubId(i),
        "minDate", state.forumHubMinJoinDate(i),
        "limit", LIMIT);
  }

  /**
   * BothE-HAS_MEMBER count-only: maximises the visible speedup of the
   * pre-filter by eliminating everything except the edge scan/filter.
   * No {@code .inV()} vertex loads, no ORDER BY materialization, no
   * attribute projection — the only remaining cost is
   * "how many edges from the bag pass the joinDate filter", which maps
   * directly to the edge-load count reduction delivered by
   * {@code PreFilterableChainedIterable}.
   *
   * <p>Uses the 99th-percentile lower-bound date so selectivity is ~1%:
   * hub Forum with thousands of members → dozens of survivors →
   * the ratio "edges loaded without pre-filter / edges loaded with
   * pre-filter" is maximal.
   */
  @Benchmark
  public List<Map<String, Object>> bothEHasMember_joinerCount(
      LdbcBenchmarkState state, ForumHubParams forumHubParams) {
    // Parameter forces JMH to run ForumHubParams Trial setup, which populates
    // the forumHubId / forumHubVeryNarrowJoinDate pools read below.
    Objects.requireNonNull(forumHubParams);
    long i = state.nextIndex();
    return state.executeSql(
        LdbcQuerySql.FORUM_JOINER_COUNT,
        "forumId", state.forumHubId(i),
        "minDate", state.forumHubVeryNarrowJoinDate(i));
  }
}
