package com.jetbrains.youtrackdb.benchmarks.ldbc;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
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
 * <p>With the {@code KNOWS.creationDate} index (declared in {@code ldbc-schema.sql}),
 * the MATCH planner builds a RID set from the index and hands it to
 * {@code MatchEdgeTraverser.applyPreFilter()}. Previously, {@code bothE()} returned
 * a plain {@code IterableUtils.chainedIterable} which is not a
 * {@code PreFilterableLinkBagIterable}, so the pre-filter was silently bypassed and
 * all KNOWS edges were loaded before the date condition was checked. After the fix,
 * both the {@code out_KNOWS} and {@code in_KNOWS} link bags are intersected against
 * the index RID set in memory before any edge record is loaded from disk.
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
 * <p>Requires the {@code KNOWS.creationDate NOTUNIQUE} index declared in
 * {@code ldbc-schema.sql}. If running against a pre-built database that was created
 * without this index, the benchmark will still produce correct results, but the
 * pre-filter will not engage and throughput will reflect the unoptimised path.
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
  public List<Map<String, Object>> bothEKnows_recentConnections(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    return state.executeSql(
        LdbcQuerySql.BOTH_E_KNOWS,
        "personId", state.bothEKnowsPersonId(i),
        "minDate", state.bothEKnowsMinDate(i),
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
      LdbcBenchmarkState state) {
    long i = state.nextIndex();
    return state.executeSql(
        LdbcQuerySql.FORUM_RECENT_JOINERS,
        "forumId", state.forumHubId(i),
        "minDate", state.forumHubMinJoinDate(i),
        "limit", LIMIT);
  }
}
