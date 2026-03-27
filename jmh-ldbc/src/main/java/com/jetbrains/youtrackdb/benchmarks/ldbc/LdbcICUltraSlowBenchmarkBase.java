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
 * Abstract base class for ultra-slow LDBC SNB Interactive Complex (IC) queries.
 * Contains IC3, IC5, IC10 which have extremely low throughput (< 0.05 ops/s
 * single-threaded on SF 1): individual queries take 40-125 seconds each.
 *
 * <p>Even with curated parameters, these queries need long measurement windows
 * because individual operations are so slow. 120s iterations ensure at least
 * 1-3 operations per iteration for IC10 (40s/op) and IC3 (83s/op).
 * 3 forks × 3 iterations gives 9 data points.
 *
 * <p>Subclasses specify the thread count via @Threads annotation.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1, time = 60)
@Measurement(iterations = 3, time = 120)
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
public abstract class LdbcICUltraSlowBenchmarkBase {

  private static final int LIMIT = 20;

  /**
   * IC3: Friends in countries.
   * Find friends/friends-of-friends who posted in both given countries
   * within a time period. ~83s per query on SF 1 (ST).
   */
  @Benchmark
  public List<Map<String, Object>> ic3_friendsInCountries(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    Date startDate = state.ic3StartDate(i);
    Date endDate = new Date(startDate.getTime() + 30L * 24 * 60 * 60 * 1000);

    return state.executeSql(LdbcQuerySql.IC3,
        "personId", state.ic3PersonId(i),
        "countryX", state.ic3CountryX(i),
        "countryY", state.ic3CountryY(i),
        "startDate", startDate,
        "endDate", endDate,
        "limit", LIMIT);
  }

  /**
   * IC5: New groups.
   * Find Forums joined by friends/friends-of-friends after a given date,
   * counting Posts by those friends. ~125s per query on SF 1 (ST).
   */
  @Benchmark
  public List<Map<String, Object>> ic5_newGroups(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    return state.executeSql(LdbcQuerySql.IC5,
        "personId", state.ic5PersonId(i),
        "minDate", state.ic5Date(i),
        "limit", LIMIT);
  }

  /**
   * IC10: Friend recommendation.
   * Find friends-of-friends born in a date range, score by common interests.
   * ~40s per query on SF 1 (ST).
   */
  @Benchmark
  public List<Map<String, Object>> ic10_friendRecommendation(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    int startMonth = 6;
    String startMd = String.format("%02d21", startMonth);
    String endMd = String.format("%02d22", startMonth + 1);

    return state.executeSql(LdbcQuerySql.IC10,
        "personId", state.ic10PersonId(i),
        "startMd", startMd,
        "endMd", endMd,
        "wrap", false,
        "limit", LIMIT);
  }
}
