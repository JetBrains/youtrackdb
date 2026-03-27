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
import org.openjdk.jmh.annotations.Warmup;

/**
 * Abstract base class for medium-speed LDBC SNB Interactive Complex (IC) queries.
 * Contains IC2, IC7, IC11: multi-hop analytical traversals with throughput
 * between 5 and 30 ops/s (single-threaded, SF 1).
 *
 * <p>These queries exhibit parameter-dependent throughput variation (different
 * persons have vastly different neighborhoods). Longer measurement iterations
 * (60s) are used to average out this parameter sensitivity within each iteration.
 * 3 forks x 5 iterations gives 15 data points with enough parameter diversity
 * per data point to achieve score-error below 7%.
 *
 * <p>Fast queries (IS1-IS7, IC8, IC13) are in {@link LdbcISBenchmarkBase}.
 * Slow queries (IC1, IC3-IC6, IC9, IC10, IC12) are in {@link LdbcICSlowBenchmarkBase}.
 *
 * <p>Subclasses specify the thread count via @Threads annotation.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1, time = 30)
@Measurement(iterations = 5, time = 60)
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
public abstract class LdbcICBenchmarkBase {

  private static final int LIMIT = 20;

  // IC1 (transitiveFriends) is in LdbcICSlowBenchmarkBase — needs longer measurement windows.

  /**
   * IC2: Recent messages by friends.
   * Find most recent Messages from a Person's friends before a given date.
   */
  @Benchmark
  public List<Map<String, Object>> ic2_recentFriendMessages(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    return state.executeSql(LdbcQuerySql.IC2,
        "personId", state.ic2PersonId(i),
        "maxDate", state.ic2MaxDate(i),
        "limit", LIMIT);
  }

  // IC3 (friendsInCountries) is in LdbcICSlowBenchmarkBase — needs longer measurement windows.
  // IC4 (newTopics) is in LdbcICSlowBenchmarkBase — needs longer measurement windows.
  // IC5 (newGroups) is in LdbcICSlowBenchmarkBase — needs longer measurement windows.
  // IC6 (tagCoOccurrence) is in LdbcICSlowBenchmarkBase — needs longer measurement windows.

  /**
   * IC7: Recent likers.
   * Find most recent likes on a Person's Messages.
   */
  @Benchmark
  public List<Map<String, Object>> ic7_recentLikers(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    return state.executeSql(LdbcQuerySql.IC7,
        "personId", state.ic7PersonId(i),
        "limit", LIMIT);
  }

  // IC8 (recentReplies) is in LdbcISBenchmarkBase — fast enough for the IS tier.
  // IC9 (recentFofMessages) is in LdbcICSlowBenchmarkBase — needs longer measurement windows.
  // IC10 (friendRecommendation) is in LdbcICSlowBenchmarkBase — needs longer measurement windows.

  /**
   * IC11: Job referral.
   * Find friends/FoF who started working in a Company in a given Country
   * before a given year.
   */
  @Benchmark
  public List<Map<String, Object>> ic11_jobReferral(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    return state.executeSql(LdbcQuerySql.IC11,
        "personId", state.ic11PersonId(i),
        "countryName", state.ic11CountryName(i),
        "workFromYear", 2010,
        "limit", LIMIT);
  }

  // IC12 (expertSearch) is in LdbcICSlowBenchmarkBase — needs longer measurement windows.
  // IC13 (shortestPath) is in LdbcISBenchmarkBase — fast enough for the IS tier.
}
