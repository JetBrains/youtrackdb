package com.jetbrains.youtrackdb.benchmarks.ldbc;

import java.util.Date;
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
 * Abstract base class for slow LDBC SNB Interactive Complex (IC) queries.
 * Contains IC1, IC4, IC6, IC9, IC12 which have low throughput (0.1-3 ops/s
 * single-threaded on SF 1).
 *
 * <p>With curated parameters eliminating parameter-dependent variance, 30s
 * measurement iterations are sufficient for queries at 0.1-3 ops/s (3-90
 * operations per iteration). 3 forks × 5 iterations gives 15 data points.
 *
 * <p>Ultra-slow queries (IC3, IC5, IC10) with < 0.1 ops/s are in
 * {@link LdbcICUltraSlowBenchmarkBase} with longer measurement windows.
 *
 * <p>Subclasses specify the thread count via @Threads annotation.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1, time = 30)
@Measurement(iterations = 5, time = 30)
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
public abstract class LdbcICSlowBenchmarkBase {

  private static final int LIMIT = 20;

  /**
   * IC1: Transitive friends with a certain name.
   * Find Persons with a given first name connected within 3 hops via KNOWS.
   * ~1.6 ops/s (ST) on SF 1 — highly parameter-sensitive.
   */
  @Benchmark
  public List<Map<String, Object>> ic1_transitiveFriends(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    return state.executeSql(LdbcQuerySql.IC1,
        "personId", state.ic1PersonId(i),
        "firstName", state.ic1FirstName(i),
        "limit", LIMIT);
  }

  /**
   * IC4: New topics.
   * Find Tags attached to friends' Posts in a time interval that were never
   * used before. ~0.85 ops/s (ST) on SF 1.
   * Extended warmup: at 0.85 ops/s the default 1×30s warmup gives only ~25
   * invocations — not enough for JIT to fully optimize the MATCH+NOT traversal.
   * 3×30s gives ~76 invocations across 3 rounds, allowing JIT recompilation
   * cycles to settle.
   */
  @Benchmark
  @Warmup(iterations = 3, time = 30)
  public List<Map<String, Object>> ic4_newTopics(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    Date startDate = state.ic4StartDate(i);
    Date endDate = new Date(startDate.getTime() + 30L * 24 * 60 * 60 * 1000);

    return state.executeSql(LdbcQuerySql.IC4,
        "personId", state.ic4PersonId(i),
        "startDate", startDate,
        "endDate", endDate,
        "limit", LIMIT);
  }

  // IC5 (newGroups) is in LdbcICUltraSlowBenchmarkBase — needs 300s+ measurement windows.

  /**
   * IC6: Tag co-occurrence.
   * Find Tags that co-occur with a given Tag on friends' Posts.
   * ~0.13 ops/s (ST) on SF 1.
   */
  @Benchmark
  public List<Map<String, Object>> ic6_tagCoOccurrence(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    return state.executeSql(LdbcQuerySql.IC6,
        "personId", state.ic6PersonId(i),
        "tagName", state.ic6TagName(i),
        "limit", LIMIT);
  }

  /**
   * IC9: Recent messages by friends or friends of friends.
   * Find most recent Messages created by friends/FoF before a given date.
   * ~0.11 ops/s (ST) on SF 1.
   */
  @Benchmark
  public List<Map<String, Object>> ic9_recentFofMessages(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    return state.executeSql(LdbcQuerySql.IC9,
        "personId", state.ic9PersonId(i),
        "maxDate", state.ic9MaxDate(i),
        "limit", LIMIT);
  }

  /**
   * IC12: Expert search.
   * Find friends' Comments replying to Posts with Tags in a given TagClass
   * hierarchy. ~1.6 ops/s (ST) on SF 1 — parameter-sensitive.
   */
  @Benchmark
  public List<Map<String, Object>> ic12_expertSearch(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    return state.executeSql(LdbcQuerySql.IC12,
        "personId", state.ic12PersonId(i),
        "tagClassName", state.ic12TagClassName(i),
        "limit", LIMIT);
  }
}
